(ns com.frereth.client.connection-manager
  "Sets up basic connection with a server

There are really 2 phases to this:
1. Cert exchange
   This is part of the curve encryption protocol and invisible to the client
2. Protocol handshake
   Where client and server agree on how they'll be handling future communications

Note that this protocol agreement indicates the communications mechanism
that will probably be most convenient for most apps.

Apps shall be free to use whatever comms protocol works best for them.
This protocol is really more of a recommendation than anything else.

At this point, anyway. That part seems both dangerous and necessary.
n
The protocol contract is really more of the handshake/cert exchange
sort of thing. Once that part's done, this should hand off
to a manager/CommunicationsLoopManager.

Note that multiple end-users can use the connection being established
here. The credentials exchange shows that the other side has access to
the server's private key, and that this side has access to the appropriate
client private key (assuming the server checks).

It says nothing about the end-users who are using this connection.
"
  (:require [cljeromq.common :as mq-cmn]
            [cljeromq.constants :as mq-k]
            [cljeromq.core :as mq]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            ;; Note that this is really only being used here for schema
            ;; So far. This seems wrong.
            [com.frereth.client.manager :as manager]
            [com.frereth.common.communication :as com-comm]
            [com.frereth.common.schema :as fr-skm]
            [com.frereth.common.util :as util]
            [com.frereth.common.zmq-socket :as zmq-socket]
            [com.stuartsierra.component :as component]
            [clj-time.core :as dt]
            [ribol.core :refer (raise)]
            [schema.core :as s]
            [taoensso.timbre :as log])
  (:import [clojure.lang ExceptionInfo]
           [com.frereth.common.manager.CommunicationsLoopManager]
           [com.frereth.common.zmq_socket ContextWrapper SocketDescription]
           [java.util Date]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(def server-connection-map
  {zmq/url CommunicationsLoopManager})

;;;; TODO: None of the schema-defs between here and
;;;; the ConnectionManager defrecord belong in here.
;;;; Most of them probably shouldn't exist at all
(def ui-description
  "TODO: This (and the pieces that build upon it) really belong in common.
Or maybe App.
Since, really, it's the over-arching interface between renderer and server.
And it needs to be fleshed out much more thoroughly.
This is the part that flows down to the Renderer"
  {:data {:type s/Keyword
          :version s/Any
          :name s/Any ; manager/world-id
          :body s/Any
          (s/optional-key :script) [s/Any]
          (s/optional-key :css) [s/Any]}})

(def base-auth-dialog-description
  "TODO: Come up with a better name. auth usually won't be involved, really.

This is pretty much a half-baked idea.

Should really be downloading a template of HTML/javascript for doing
the login. e.g. a URL for an OAUTH endpoint (or however those work).
The 'expires' is really just for the sake of transitioning to a newer
login dialog with software updates.

The 'scripting' part is really interesting. It's a microcosm of the
entire architecture. Part of it should run on the client. The rest should
run on the Renderer."
  {:character-encoding s/Keyword
   :expires Date
   ;; This is actually a byte array, but, for dev testing, I'm just using a UUID
   ;; TODO: Tighten this up once I have something resembling encryption
   :public-key s/Any
   :session-token s/Any})
(def auth-dialog-url-description
  "Server redirects us here to download the 'real' ui-description"
  (assoc base-auth-dialog-description :static-url s/Str))
(def auth-dialog-dynamic-description
  "Server just sends us the ui-description directly"
  (assoc base-auth-dialog-description :world ui-description))
(def auth-dialog-description
  (s/conditional
   :static-url auth-dialog-url-description
   :world auth-dialog-dynamic-description))
(def optional-auth-dialog-description (s/maybe auth-dialog-description))

(def individual-connection
  "Q: What should this actually look like?"
  {:auth-sock mq-cmn/Socket  ; note that this has the URL
   ;; TODO: Refactor this to plain ol' :description
   ;; Except world/UI descriptions don't belong in here.
   :dialog-description optional-auth-dialog-description})

(def auth-connections
  "The thinking behind this is circular.

  Shouldn't have any notion of worlds here.

  Those belong to the manager namespace. Which needs to be renamed."
  {manager/world-id individual-auth-connection})

(declare establish-connection release-world!)
(s/defrecord ConnectionManager
    [auth-request :- fr-skm/async-channel
     local-url :- mq/zmq-url
     message-context :- ContextWrapper
     server-connections :- fr-skm/atom-type  ; server-connection-map
     status-check :- fr-skm/async-channel]
  component/Lifecycle
  (start
   [this]
   ;; Create pieces that weren't supplied externally
    (let [auth-request (or auth-request (async/chan))
          server-connections (or server-connections
                                 (atom {:local (establish-connection :local
                                                                     message-context
                                                                     local-url)}))
          status-check (or status-check (async/chan))
          almost (assoc this
                        :auth-request auth-request
                        :status-check status-check)
          ;; Looking at it from this angle, keeping an auth-loop with a
          ;; fresh dialog to the local server does make some sense.
          auth-loop (auth-loop-creator almost local-auth-url)]
     (assoc almost :auth-loop auth-loop)))
  (stop
   [this]
   (when server-connections
     (let [actual-connections @server-connections]
       ;; TODO: Hang on to the individual world description
       ;; Seems like it would make reconnecting
       ;; or session restoration easier
       ;; Then again, that's really a higher-level scope.
       ;; And it's YAGNI
       (doseq [connection (vals actual-connections)]
         (release-connection! connection))))
   (when auth-request
     (async/close! auth-request))
   (when status-check
     (async/close! status-check))
   (when auth-loop
     (let [[v c] (async/alts!! [auth-loop (async/timeout 1500)])]
       (when (not= c auth-loop)
         (log/error "Timed out waiting for AUTH loop to exit"))))
   (assoc this
          :auth-request nil
          :auth-loop nil
          :status-check nil
          :worlds nil)))

(def callback-channel
  "Where do I send responses back to?"
  {:respond fr-skm/async-channel})

(def connection-request
  {:url mq/zmq-url
   :request-id manager/world-id})

(def connection-callback (into connection-request callback-channel))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(s/defn current-session-key
  "Note that, as-is, this is useless.
Really need some sort of SESSION token"
  []
  (util/random-uuid))

(s/defn request-auth-descr!
  "Signal the server that we want to get in touch"
  [auth-sock :- SocketDescription]
  ;; I'm jumping through too many hoops to get this to work.
  ;; TODO: Make dealer-send do the serialization
  ;; Actually, I thought it was already doing that
  ;; TODO: Verify
  (let [msg {:character-encoding :utf-8
             ;; Tie this socket to its SESSION: first step toward allowing
             ;; secure server side socket to even think about having a
             ;; conversation
             :public-key (current-session-key)}
        serialized #_(-> msg pr-str .getBytes vector) (pr-str msg)]
    (com-comm/dealer-send! (:socket auth-sock) serialized)))

(s/defn expired? :- s/Bool
  [{:keys [expires] :as ui-description} :- optional-auth-dialog-description]
  (let [inverted-result
        (when (and ui-description
                   expires)
          (let [now (dt/date-time)]
            ;; If now is before the expiration date, we have not expired.
            ;; Which means this should return False
            (dt/before? now (dt/date-time expires))))]
    (log/debug "Expiration check based on keys:\n"
               (keys ui-description)
               "'\nexpires on: '"
               expires
               "', the complement of expired? is: '"
               inverted-result "'")
    (not inverted-result)))

(s/defn configure-session!
  [this :- ConnectionManager
   description :- auth-dialog-description]
  ;; TODO: this needs a lot of love.
  ;; This should probably set up its own registrar/dispatcher layer
  ;; Sooner or later, multiple renderers will be connecting to different
  ;; apps.
  ;; This is really the point to that
  (let [description-holder (:dialog-description this)]
    (log/debug "Resetting ConnectionManager's dialog-description atom to\n"
               description
               "\n(yes, this is just a baby step)")
    (reset! description-holder description)))

(s/defn extract-renderer-pieces :- ui-description
  [src :- auth-dialog-description]
  ;; Note that, right now, we also have :character-encoding,
  ;; :public-key, :expires, and :session-token
  ;; Those are all pretty vital...though maybe the Renderer
  ;; should cope w/ character-encoding
  (log/debug "Extracting :world from:\n" (str (keys src)))
  (:world src))

(s/defn public-key-matches? :- s/Bool
  [public-key :- s/Uuid
   request-id :- manager/world-id]
  ;; Really just a placeholder
  ;; TODO: implement
  true)

(s/defn pre-process-auth-dialog :- optional-auth-dialog-description
  "Convert the dialog description to something the renderer can use"
  [this :- ConnectionManager
   {:keys [public-key static-url world]
    :as incoming-frame} :- auth-dialog-description
   ;; Q: Do I want to save this based on the requested URL?
   ;; A: Of course!
   ;; TODO: get that into here.
   ;; Honestly, it should just always stay paired w/ the request
   ;; until we get to a layer that needs them split
   ;; Then again, as written, this is hard-coded to a single
   ;; server URL. So there's a bigger picture item to worry about
   request-id :- manager/world-id]
  (log/debug "Pre-processing:n" (util/pretty incoming-frame))
  (when (public-key-matches? public-key request-id)
    (try
      (let [frame (s/validate auth-dialog-description incoming-frame)]
        (if-not (expired? frame)
          (do
            (log/debug "Not expired!")
            (if world
              (do
                (log/debug "World is hard-coded in response")
                (configure-session! this incoming-frame)
                frame)
              (if static-url
                (raise {:not-implemented (str "Download world from " static-url)})
                (assert false "World missing both description and URL for downloading description"))))
          (log/warn "Incoming frame, with keys:\n"
                    (keys frame)
                    "\nis already expired at: "
                    (:expires frame))))
      (catch ExceptionInfo ex
        (log/error ex "Incoming frame was bad")))))

(s/defn freshen-auth! :- optional-auth-dialog-description
  [auth-sock :- SocketDescription
   {:keys [protocol address port] :as url}
   request-id :- manager/world-id]
  ;; TODO: Need a background loop (would an EventPair be appropriate?)
  ;; that updates :dialog-description as updates arrive
  ;; Should not be accessing a raw socket here, under any circumstances
  ;; When the server notifies us about a change, cope with that notification
  ;; appropriately

  (log/debug "Checking for updated AUTH description")

  ;; It seems like a mistake to just leave this queued on the socket
  ;; until we need it.
  ;; But it was an easy first step
  (try
    (let [description-frames (com-comm/dealer-recv! (:socket auth-sock))]
      ;; server sent because of a previous request
      ;; Have I mentioned that this approach is wrong?
      ;; i.e. this approach will fail the first time, which means it will need to
      ;; be repeated to get here
      ;; It made sense for a first implementation
      (do
        (log/warn "Really need to set up a CommunicationsLoopManager")
        ;; Or maybe that should just happen the first time we establish a connection.
        ;; Reloading an expired login dialog really isn't the sort of thing to be trying to
        ;; handle here.
        ;; There's a part of me that thinks the initial connection to localhost deserves
        ;; special treatment. Generally, I think that part's wrong.
        (raise :start-here)
        description-frames))
    (catch ExceptionInfo ex
      (if-let [errno (:error-number (.getData ex))]
        (if (= errno (mq-k/error->const :again))
          (do
            (log/debug "No fresh description available from server yet. Requesting...")
            (request-auth-descr! auth-sock)
            ;; trigger another request
            nil)
          (throw ex))
        (throw ex)))))

(s/defn unexpired-auth-description :- optional-auth-dialog-description
  "If we're missing the description, or it's expired, try to read a more recent"
  [this :- ConnectionManager
   url :- mq/zmq-url
   world-id :- manager/world-id]
  (if-let [potential-description (-> this :worlds deref (get world-id) :dialog-description)]
    (do
      (log/debug "The description we have now:\n" (util/pretty potential-description))
      (if (not (expired? potential-description))
        (extract-renderer-pieces potential-description)  ; Last received version still good
        (do
          (log/debug "Requesting a new version, since that's expired")
          (freshen-auth! this url world-id))))
    (do
      (log/debug "No current description. Requesting one")
      (freshen-auth! this url world-id))))

(s/defn send-auth-descr-response!
  [{:keys [dialog-description] :as this}
   {:keys [request-id respond] :as destination} :- connection-callback
   new-description :- auth-dialog-description]
  (comment (log/debug "Resetting ConnectionManager's dialog-description atom to\n"
                      new-description)
           ;; Q: Why did I think that eliminating this would be a good idea?
           ;; A: Because this simply does not belong in here
           (reset! dialog-description new-description))
  (log/debug "Forwarding world description to " respond)
  (async/>!! respond (assoc new-description :request-id request-id))
  (log/debug "Description sent"))

(s/defn send-wait!
  [{:keys [respond]}]
  (async/>!! respond :hold-please))

(s/defn dispatch-auth-response! :- s/Any
  [this :- ConnectionManager
   cb :- connection-callback]
  (log/debug "Incoming AUTH request to respond to:\n" cb)
  (if-let [raw-description (unexpired-auth-description
                            this
                            (:url cb)
                            (:request-id cb))]
    (let [current-description
          (-> raw-description pre-process-auth-dialog extract-renderer-pieces)]
      (comment
        (log/debug "Current Description:\n"
                   (util/pretty current-description)))
      (send-auth-descr-response! this cb current-description))
    (do
      (log/debug "No [unexpired] auth dialog description. Waiting...")
      (send-wait! cb))))

(s/defn establish-connection :- individual-auth-connection
  [world-id :- manager/world-id
   ctx :- ContextWrapper
   url :- mq/zmq-url]
  (let [dead-sock (zmq-socket/ctor {:ctx ctx
                                    :url url
                                    :sock-type :dealer
                                    :direction :connect})
        sock (component/start dead-sock)]
    ;; Q: Can this possibly work?
    ;; A: Probably not...but maybe
    ;; N.B. As-is, this will throw an AssertionError if
    ;; anything goes wrong.
    ;; e.g. There's no server listening.
    ;; TODO: Need better error handling
    (try
      (freshen-auth! sock url world-id)
      (catch ExceptionInfo ex
        (let [details (.getData ex)]
          (if-let [errno (:error-number details)]
            (let [eagain (mq-k/error->const :again)]
              (if (= errno eagain)
                (log/info "No instractions auth available from server. Have we requested any?")
                (throw ex)))
            (throw ex))))
      (catch RuntimeException ex
        ;; TODO: Be smarter about this.
        ;; Honestly, we need to propagate a message to
        ;; the piece that tried to start this so it can retry.
        ;; That seems like a poor approach, which means I probably
        ;; need to rethink the wisdom of this stack.
        ;; Yet again.

        ;; N.B. Also: the error contents make a big difference here.
        ;; EAGAIN is one thing.
        ;; Other errors are less benign
        (let [msg (str "Client failed to refresh auth requirements from server\n"
                       "Q: Is the server up and running?")]
          (throw (ex-info msg {:internal ex})))))))

(s/defn release-connection! :- individual-connection
  [connection :- individual-connection]
  (assoc connection :auth-sock (component/stop (:auth-sock connection))))

(comment
  (s/defn auth-loop-creator :- fr-skm/async-channel
    "Set up the auth loop
This is just an async-zmq/EventPair.
Actually, this should just be an async/pipeline-async transducer.
TODO: Switch to that"
    [{:keys [auth-request message-context status-check]
      :as this} :- ConnectionManager
     auth-url :- mq/zmq-url]
    (let [url-string (mq/connection-string auth-url)
          ctx (:ctx message-context)
          auth-sock (mq/connected-socket! ctx :dealer url-string)
          minutes-5 (partial async/timeout (* 5 (util/minute)))
          done (promise)
          interesting-channels [auth-request status-check]]
      ;; It seems almost wasteful to start this before there's any
      ;; interest to express. But the 90% (at least) use case is for
      ;; the local server where there won't ever be any reason
      ;; for it to timeout.
      (request-auth-descr! auth-sock)
      (async/go
        (loop [t-o (minutes-5)]
          (try
            ;; TODO: Really need a ribol manager in here to distinguish
            ;; between the "stop-iteration" signal and actual errors
            (let [[v c] (async/alts! (conj interesting-channels t-o))]
              ;; Right here, we have :url, :request-id, and the response channel in :respond
              (log/debug "Incoming to AUTH loop:\n'"
                         v "' -- a " (class v)
                         "\non\n" c)
              (if (not= t-o c)
                (if v
                  (if (= auth-request c)
                    (dispatch-auth-response! this v)
                    (do
                      (assert (= status-check c))
                      ;; TODO: This absolutely needs to be an offer
                      ;; TODO: Add error handling. Don't want someone to
                      ;; break this loop by submitting, say, a keyword
                      ;; instead of a channel
                      (async/>! v "I'm alive")))
                  (do
                    (log/warn "Incoming channel closed. Exiting AUTH loop")
                    (deliver done true)))
                (log/debug "AUTH loop heartbeat")))
            (catch RuntimeException ex
              (log/error ex "Dispatching an auth request.\nThis should probably be fatal for dev time.")
              (let [dialog-description-atom (:dialog-description this)
                    dialog-description (if dialog-description-atom
                                         (deref dialog-description-atom)
                                         "missing atom")
                    msg {:problem ex
                         :component this
                         :details {:dialog-description dialog-description
                                   :auth-request auth-request
                                   :time-out t-o}}]
                (comment (raise msg))
                (log/warn msg "\nI'm tired of being forced to (reset) every time I have a glitch"))))
          (when (not (realized? done))
            (log/debug "AUTH looping")
            (recur (minutes-5))))
        (log/warn "ConnectionManager's auth-loop exited")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/defn rpc :- (s/maybe fr-skm/async-channel)
  "For plain-ol' asynchronous request/response exchanges"
  ([this :- ConnectionManager
    world-id :- manager/world-id
    method :- s/Keyword
    data :- s/Any
    timeout-ms :- s/Int]
   ;; At this point, we've really moved beyond the original scope of this
   ;; namespace.
   ;; It's getting into real client-server interaction.
   ;; Even if, at this level, it's still just
   ;; trying to set up auth.
   ;; That just happens to be the auth world's entire purpose
   ;; TODO: This needs more thought
   (raise {:move-this "Unless there's already a better implementation in manager ns"})
   (log/debug "Top of RPC:" method)
   (let [receiver (async/chan)
         responder {:respond receiver}
         ;; This causes a NPE, because there is no :worlds atom to deref
         transmitter (-> this :worlds deref (get world-id) :transmitter)
         [v c] (async/alts!! [[transmitter responder] (async/timeout timeout-ms)])]
     (log/debug "Submitting RPC" method "returned" (util/pretty v))
     (if v
       (if (not= v :not-found)
         (do
           (log/debug "Incoming RPC request:\n " (dissoc responder :respond)
                      "\n(hiding the core.async.channel)"
                      "\nResult of send:" v)
           ;; It isn't obvious, but this is the happy path
           responder)
         (raise :not-found))
       (if (= c transmitter)
         (log/error "channel to transmitter for world" world-id " closed")
         (raise :timeout {})))))
  ([this :- ConnectionManager
    request-id :- manager/world-id
    method :- s/Keyword
    data :- s/Any]
   (rpc this request-id method data (* 5 util/seconds))))

(s/defn rpc-sync
  "True synchronous Request/Reply"
  ([this :- ConnectionManager
    request-id :- manager/world-id
    method :- s/Keyword
    data :- s/Any
    timeout :- s/Int]
   (log/debug "Synchronous RPC:\n(" method data ")")
   (let [responder (rpc this request-id method data timeout)
         [result ch] (async/alts!! [responder (async/timeout timeout)])]
     (log/debug "RPC returned:" (pr-str result))
     (when-not result
       (if (= result responder)
         (do
           (log/error "Responder channel unexpectedly closed by other side. This is bad")
           (raise {:unexpected-failure "What could have happened?"}))
         (do
           (log/error "Timed out waiting for a response")
           (raise :timeout))))
     (log/debug "rpc-sync returning:" result)
     result))
  ([this :- ConnectionManager
    request-id :- manager/world-id
    method :- s/Keyword
    data :- s/Any]
   (rpc-sync this request-id method data (* 5 (util/seconds)))))

(s/defn initiate-handshake :- optional-auth-dialog-description
  "TODO: ^:always-validate"
  [this :- ConnectionManager
   request :- connection-request
   attempts :- s/Int
   timeout-ms :- s/Int]
  (let [receiver (async/chan)
        responder (assoc request :respond receiver)
        transmitter (:auth-request this)]
    (loop [remaining-attempts attempts]
      (when (< 0 remaining-attempts)
        (log/debug "Top of handshake loop. Remaining attempts:" remaining-attempts)
        (let [[v c] (async/alts!! [[transmitter responder] (async/timeout timeout-ms)])]
          (if v  ; did the submission succeed?
            ;; TODO: decrement the timeout by however many we spent waiting for submission
            (let [[real-response c] (async/alts!! [receiver (async/timeout timeout-ms)])]
              (if (= real-response :hold-please)
                (do
                  (log/info "Request for AUTH dialog ACK'd. Waiting...")
                  (recur (dec remaining-attempts)))
                (do
                  (log/info "Successfully asked transmitter to return reply on:\n " responder)
                  ;; It isn't obvious, but this is the happy path
                  real-response)))
            (if (= c transmitter)
              (log/error "Auth channel closed")
              (do
                (log/warn "Timed out trying to transmit request for AUTH dialog.\n"
                          (dec remaining-attempts) " attempts remaining")
                (recur (dec remaining-attempts))))))))))

(comment
  (require '[dev])
  ;; Try out the handshake
  ;; Really should be ignoring the URL completely.
  ;; But I have a check in place for when I start trying
  ;; to connect to anywhere else.
  (let [request {:url {:protocol :tcp
                       :port 7848
                       :address "127.0.0.1"}
                 :request-id "also ignored, really"}]
    (if-let [responder
             (initiate-handshake (:connection-manager dev/system) request 5 2000)]
      (do
        (comment (let [[v c] (async/alts!! [(:respond responder) (async/timeout 500)])]
                   (if v
                     v
                     (log/error "Response failed:\n"
                                (if (= c (:respond responder))
                                  "Handshaker closed the response channel. This is bad."
                                  ;; This is happening. But I'm very clearly
                                  ;; sending the auth response just before we try to
                                  ;; read it. Which means that something else must be
                                  ;; pulling it from the channel first.
                                  ;; Doesn't it?
                                  (str "Timed out waiting for response on"
                                       (:respond responder)
                                       ". This isn't great"))))))
        responder)
      (log/error "Failed to submit handshake request")))

  ;; Check on status
  (let [response (async/chan)
        [v c] (async/alts!! [[(-> dev/system :connection-manager :status-check) response]
                             (async/timeout 500)])]
    (if v
      (let [[v c] (async/alts!! [response (async/timeout 500)])]
        (log/info v)
        v)
      (log/error "Couldn't submit status request")))

)

(s/defn ctor :- ConnectionManager
  [{:keys [local-auth-url]}]
  (map->ConnectionManager {:local-auth-url local-auth-url}))
