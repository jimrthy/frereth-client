(ns com.frereth.client.connection-manager
  "Sets up basic auth w/ a server.

That auth is really more of the handshake/cert exchange
sort of thing. Once that part's done, this should hand off
to a manager/CommunicationsLoopManager.

Most common workflow that I foresee:

That will probably start w/ an auth dialog provides links
to new worlds (which will frequently be on the same server)
and forward back through here when a player decides to connect
with those.
"
  (:require [cljeromq.core :as mq]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [com.frereth.common.communication :as com-comm]
            [com.frereth.common.schema :as fr-skm]
            [com.frereth.common.util :as util]
            [com.frereth.common.zmq-socket :as zmq-socket]
            [com.stuartsierra.component :as component]
            [joda-time :as dt]
            [ribol.core :refer (raise)]
            [schema.core :as s]
            [taoensso.timbre :as log])
  (:import [clojure.lang ExceptionInfo]
           [com.frereth.common.zmq_socket SocketDescription]
           [java.util Date]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(declare auth-loop-creator)
(s/defrecord ConnectionManager
    [auth-loop :- fr-skm/async-channel
     auth-request :- fr-skm/async-channel
     auth-sock :- mq/Socket
     dialog-description :- fr-skm/atom-type
     status-check :- fr-skm/async-channel]
  component/Lifecycle
  (start
   [this]
   ;; Create pieces that weren't supplied externally
   (let [auth-request (or auth-request (async/chan))
         status-check (or status-check (async/chan))
         dialog-description (or dialog-description
                                (atom nil))
         almost (assoc this
                       :auth-request auth-request
                       :dialog-description dialog-description
                       :status-check status-check)
         auth-loop (auth-loop-creator almost)]
     (assoc almost :auth-loop auth-loop)))
  (stop
   [this]
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
          :dialog-description (atom nil)
          :status-check nil)))

;; TODO: Move this into common
;; Note that, currently, it's copy/pasted into web's frereth.globals.cljs
;; And it doesn't work
;; Q: What's the issue? (aside from the fact that it's experimental)
;; TODO: Ask on the mailing list
(def world-id (s/cond-pre s/Keyword s/Str s/Uuid))

(def ui-description
  "TODO: This (and the pieces that build upon it) really belong in common.
Since, really, it's the over-arching interface between renderer and server.
And it needs to be fleshed out much more thoroughly.
This is the part that flows down to the Renderer"
  {:data {:type s/Keyword
          :version s/Any
          :name s/Any ;world-id
          :body s/Any
          (s/optional-key :script) [s/Any]
          (s/optional-key :css) [s/Any]}})

(def base-auth-dialog-description
  "This is pretty much a half-baked idea.
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

(def callback-channel
  "Where do I send responses back to?"
  {:respond fr-skm/async-channel})

(def connection-request
  {;; the url structure is already defined elsewhere
   ;; TODO: Track it down
   :url s/Any
   :request-id world-id})

(def connection-callback (into connection-request callback-channel))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

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
             :public-key (util/random-uuid)}
        serialized (-> msg pr-str .getBytes vector)]
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
    (log/debug "Expiration check based on\n'"
               (util/pretty ui-description)
               "\nwith keys: " (keys ui-description)
               "'\nexpires on: '"
               expires "', a " (class expires)
               ", the complement of expired? is: '"
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
               description)
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
   request-id :- world-id]
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
   request-id :- world-id]
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
  [this :- ConnectionManager
   {:keys [protocol address port] :as url}
   request-id :- world-id]
  (when (not (and (= protocol :tcp)
                  (= port 7848)
                  (= address "127.0.0.1")))
    (raise {:not-implemented url}))
  (let [auth-sock (:auth-sock this)]
    ;; TODO: Need a background loop (would an EventPair be appropriate?)
    ;; that updates :dialog-description as updates arrive
    ;; Should not be accessing a raw socket here, under any circumstances
    ;; When the server notifies us about a change, cope with that notification
    ;; appropriately
    (log/debug "Checking for updated AUTH description")
    ;; It seems like a mistake to just leave this queued on the socket
    ;; until we need it.
    ;; But it was an easy first step
    (if-let [description-frames (com-comm/dealer-recv! (:socket auth-sock))]
      ;; server sent because of a previous request
      ;; Have I mentioned that this approach is wrong?
      ;; i.e. this approach will fail the first time, which means it will need to
      ;; be repeated to get here
      ;; It made sense the first time around
      (do
        (log/debug "Calling pre-process")
        (extract-renderer-pieces (pre-process-auth-dialog this description-frames request-id)))
      (do
        (log/debug "No fresh description available from server yet. Requesting...")
        (request-auth-descr! auth-sock)
        ;; trigger another request
        nil))))

(s/defn unexpired-auth-description :- optional-auth-dialog-description
  "If we're missing the description, or it's expired, try to read a more recent"
  [this :- ConnectionManager
   url
   request-id :- world-id]
  (if-let [dscr-atom (:dialog-description this)]
    (if-let [potential-description (deref dscr-atom)]
      (do
        (log/debug "The description we have now:\n" (util/pretty potential-description))
        (if (not (expired? potential-description))
          (extract-renderer-pieces potential-description)  ; Last received version still good
          (freshen-auth! this url world-id)))
      (do
        (log/debug "No current description. Requesting one")
        (freshen-auth! this url world-id)))
    (log/error "Missing atom for dialog description in " (keys this))))

(s/defn send-auth-descr-response!
  [{:keys [dialog-description] :as this}
   {:keys [request-id respond] :as destination} :- connection-callback
   new-description :- auth-dialog-description]
  (comment (log/debug "Resetting ConnectionManager's dialog-description atom to\n"
                      new-description)
           ;; Q: Why did I think that eliminating this would be a good idea?
           ;; A: Because this simply does not belong in here
           (reset! dialog-description new-description))
  (async/>!! respond (assoc new-description :request-id request-id)))

(s/defn send-wait!
  [{:keys [respond]}]
  (async/>!! respond :hold-please))

(s/defn dispatch-auth-response! :- s/Any
  [this :- ConnectionManager
   cb :- connection-callback]
  (log/debug "Incoming AUTH request to respond to:\n" cb)
  (if-let [current-description (unexpired-auth-description this (:url cb) (:request-id cb))]
    (do
      (log/debug "Current Description:\n"
                 (util/pretty current-description))
      (send-auth-descr-response! this cb current-description))
    (do
      (log/debug "No [unexpired] auth dialog description. Waiting...")
      (send-wait! cb))))

(s/defn auth-loop-creator :- fr-skm/async-channel
  "Set up the auth loop
This is just an async-zmq/EventPair.
Actually, this is just an async/pipeline-async transducer.
TODO: Switch to that"
  [{:keys [auth-request auth-sock status-check]
    :as this} :- ConnectionManager]
  (let [minutes-5 (partial async/timeout (* 5 (util/minute)))
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
      (log/warn "ConnectionManager's auth-loop exited"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/defn rpc :- (s/maybe fr-skm/async-channel)
  "For plain-ol' asynchronous request/response exchanges"
  ([this :- ConnectionManager
    ;; Schema for this is in web's global.cljs
    ;; TODO: Move it into a .cljc in common
    world-id
    method :- s/Keyword
    data :- s/Any
    timeout-ms :- s/Int]
   ;; TODO: Refactor initiate-handshake to use this?
  (let [receiver (async/chan)
        responder {:respond receiver}
        transmitter (-> this :worlds deref (get world-id))]
    (let [[v c] (async/alts!! [[transmitter responder] (async/timeout timeout-ms)])]
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
          (raise :timeout {}))))))
  ([this :- ConnectionManager
    world-id
    method :- s/Keyword
    data :- s/Any]
   (rpc this world-id method data (* 5 util/seconds))))

(s/defn rpc-sync
  "True synchronous Request/Reply"
  ([this :- ConnectionManager
    world-id
    method :- s/Keyword
    data :- s/Any
    timeout :- s/Int]
   (let [responder (rpc this world-id method data timeout)]
     (async/alts!! [responder (async/timeout timeout)])))
  ([this :- ConnectionManager
     world-id
     method :- s/Keyword
     data :- s/Any]
   (rpc-sync this world-id method data (* 5 (util/seconds)))))

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
                  (log/info "Asked transmitter to return reply on:\n " responder
                            "\nResponse:" v)
                  ;; It isn't obvious, but this is the happy path
                  responder)))
            (if (= c transmitter)
              (log/error "Auth channel closed")
              (do
                (log/warn "Timed out trying to transmit request for AUTH dialog.\n"
                          (dec remaining-attempts) " attempts remaining")
                (recur (dec remaining-attempts))))))))))

(comment
  (require '[dev])
  ;; Try out the handshake
  (if-let [responder
           (initiate-handshake (:connection-manager dev/system) 5 2000)]
    (let [[v c] (async/alts!! [(:respond responder) (async/timeout 500)])]
      (if v
        v
        (log/error "Response failed:\n"
                   (if (= c (:respond responder))
                     "Handshaker closed the response channel. This is bad."
                     "Timed out waiting for response. This isn't great"))))
    (log/error "Failed to submit handshake request"))

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
  [{:keys [url]}]
  (map->ConnectionManager {:url url}))
