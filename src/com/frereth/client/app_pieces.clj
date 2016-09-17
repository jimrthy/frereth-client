(ns com.frereth.client.app-pieces
  "Odds and ends that should be part of the app library but were cluttering up files here instead

  Really parts of a first draft that I'm not quite ready to throw out just yet"
  (:require [cljeromq.common :as mq-cmn]
            [cljeromq.constants :as mq-k]
            [cljeromq.core :as mq]
            [cljeromq.curve :as curve]
            [clj-time.core :as dt]
            [clojure.core.async :as async]
            [clojure.spec :as s]
            [com.frereth.client.connection-manager :as connection-manager]
            [com.frereth.client.world-manager :as world-manager]
            [com.frereth.common.communication :as com-comm]
            [com.frereth.common.util :as util]
            [com.frereth.common.zmq-socket :as zmq-socket]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :as log])
  (:import [clojure.lang ExceptionInfo]
           [com.frereth.common.zmq_socket ContextWrapper]
           [java.util Date]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Specs

;;; TODO: This is far too loose
(s/def ::body any?)
(s/def ::name :com.frereth.client.world-manager/world-id-type)
(s/def ::type keyword?)
(s/def ::version any?)
(s/def ::css (s/coll-of any?))
(s/def ::script (s/coll-of any?))
(s/def ::data (s/keys :req [::body
                            ::name
                            ::type
                            ::version]
                      :opt [::css ::script]))
;; TODO: This (and the pieces that build upon it) really belong in common.
;; Or maybe App.
;; Since, really, it's the over-arching interface between renderer and server.
;; And it needs to be fleshed out much more thoroughly.
;; This is the part that flows down to the Renderer
(s/def ::ui-description (s/keys :req [::data]))

(s/def ::character-encoding keyword?)
(s/def ::expires inst?)
;; This is actually a byte array, but, for dev testing, I'm just using a UUID
;; TODO: Tighten this up once I have something resembling encryption
(s/def ::public-key (s/or :real-thing :cljeromq.curve/public
                          :fake-for-debugging uuid?))
(s/def ::session-token :com.frereth.client.world-manager/session-id-type)
;;; TODO: Come up with a better name. auth usually won't be involved, really.
;;;
;;; This is pretty much a half-baked idea.
;;;
;;; Should really be downloading a template of HTML/javascript for doing
;;; the login. e.g. a URL for an OAUTH endpoint (or however those work).
;;; The 'expires' is really just for the sake of transitioning to a newer
;;; login dialog with software updates.
;;;
;;; The 'scripting' part is really interesting. It's a microcosm of the
;;; entire architecture. Part of it should run on the client. The rest should
;;; run on the Renderer.
(s/def ::base-auth-dialog-description (s/keys :req [::character-encoding
                                                    ::expires
                                                    ::public-key
                                                    ::session-token]))

(s/def ::static-url string?)
;; Server redirects us here to download the 'real' ui-description
;; Note that we're long past the idea that this is limited to an AUTH dialog.
;; Like most of this particular namespace, this made sense as a stepping stone
;; to help me figure out how it should work
(s/def ::auth-dialog-url-description (s/merge ::base-auth-dialog-description
                                              (s/keys :req [::static-url])))
;; Server just sends us the ui-description directly
(s/def ::auth-dialog-dynamic-description (s/merge ::base-auth-dialog-description
                                                  (s/keys :req [::ui-description])))
(s/def ::auth-dialog-description (s/or :static-url ::auth-dialog-url-description
                                       :world ::auth-dialog-dynamic-description))
(s/def ::optional-auth-dialog-description (s/nilable ::auth-dialog-description))

(s/def ::auth-sock :cljeromq.common/socket)
(s/def ::dialog-description ::optional-auth-dialog-description)
;; Q: What should this actually look like?
(s/def ::individual-connection (s/keys :req [::auth-sock ::dialog-description]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internals

;;;; At least some variant of these might still make sense in world-manager

(s/fdef current-session-key
        :ret :com.frereth.client.world-manager/session-id-type)
(defn current-session-key
  "Note that, as-is, this is useless.
Really need some sort of SESSION token

But it doesn't belong here.

TODO: Move to manager
(probably after I've renamed it to session-manager)

Q: Which manager ns did I mean?"
  []
  (util/random-uuid))

(s/fdef request-auth-descr!
        :args (s/cat :auth-socket ::socket-description))
(defn request-auth-descr!
  "Signal the server that we want to get in touch"
  [auth-sock]
  (throw (ex-info "obsolete" {:problem "Login App should do this instead"}))
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

(s/fdef expired?
        :args (s/cat :ui-description ::optional-auth-dialog-description)
        :ret boolean?)
(defn expired?
  [{:keys [expires] :as ui-description}]
  ;; This almost seems like it might make sense as part of the session-manager.
  ;; Decide whether our view of a world is still valid.
  ;; But it probably makes a lot more sense as a utility in the App library.
  ;; If it does, then it needs to be expanded drastically.
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

(s/fdef configure-session!
        :args (s/cat :this :com.frereth.client.connection-manager/connection-manager
                     :description ::auth-dialog-description))
(defn configure-session!
  [this description]
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

(s/fdef extract-renderer-pieces
        :args (s/cat :src ::auth-dialog-description)
        :ret ::ui-description)
(defn extract-renderer-pieces
  [src]
  ;; Note that, right now, we also have :character-encoding,
  ;; :public-key, :expires, and :session-token
  ;; Those are all pretty vital...though maybe the Renderer
  ;; should cope w/ character-encoding
  (log/debug "Extracting :world from:\n" (str (keys src)))
  (:world src))

(s/fdef public-key-matches?
        :args (s/cat :public-key ::public-key
                     :request-id :com.frereth.client.world-manager/world-id-type)
        :ret boolean)
(defn public-key-matches?
  [public-key request-id]
  ;; Really just a placeholder
  ;; TODO: implement
  true)

(s/fdef pre-process-auth-dialog
        :args (s/cat :this :com.frereth.client.connection-manager/connection-manager
                     :incoming-frame ::auth-dialog-description
                     :request-id :com.frereth.client.world-manager/world-id-type)
        :ret ::optional-auth-dialog-description)
(defn pre-process-auth-dialog
  "Convert the dialog description to something the renderer can use"
  [this
   {:keys [public-key static-url world]
    :as incoming-frame}
   ;; Q: Do I want to save this based on the requested URL?
   ;; A: Of course!
   ;; TODO: get that into here.
   ;; Honestly, it should just always stay paired w/ the request
   ;; until we get to a layer that needs them split
   ;; Then again, as written, this is hard-coded to a single
   ;; server URL. So there's a bigger picture item to worry about
   request-id]
  (log/debug "Pre-processing:n" (util/pretty incoming-frame))
  (when (public-key-matches? public-key request-id)
    (try
      (let [frame (s/conform ::auth-dialog-description incoming-frame)]
        (if (not= frame :clojure.spec:invalid)
          (if-not (expired? frame)
            (do
              (log/debug "Not expired!")
              (if world
                (do
                  (log/debug "World is hard-coded in response")
                  (configure-session! this incoming-frame)
                  frame)
                (if static-url
                  (throw (ex-info "Not Implemented" {:todo (str "Download world from " static-url)}))
                  (assert false "World missing both description and URL for downloading description"))))
            (log/warn "Incoming frame, with keys:\n"
                      (keys frame)
                      "\nis already expired at: "
                      (:expires frame)))
          (log/warn "Incoming frame, with keys:\n"
                    (keys frame)
                    "\nDoes not conform. Problem:\n"
                    (s/explain ::auth-dialog-description incoming-frame))))
      (catch ExceptionInfo ex
        (log/error ex "Incoming frame was bad")))))

(s/fdef freshen-auth
        :args (s/cat :auth-sock :com.frereth.common.zmq-socket/socket-description
                     :url :com.frereth.common.schema/zmq-protocol
                     :request-id :com.frereth.client.world-manager/world-id-type)
        :ret ::optional-auth-dialog-description)
(defn freshen-auth!
  [auth-sock
   {:keys [protocol address port] :as url}
   request-id]
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
        (throw (ex-info "start-here" {:why "I thought this was where I needed to go next"}))
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

(s/fdef unexpired-auth-description
        :args (s/cat :this :com.frereth.client.connection-manager/connection-manager
                     :url :cljeromq.common/zmq-url
                     :world-id :com.frereth.client.world-manager/world-id-type)
        :ret ::optional-auth-dialog-description)
(defn unexpired-auth-description
  "If we're missing the description, or it's expired, try to read a more recent"
  [this
   url
   world-id]
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

(s/fdef send-auth-descr-response!
        ;; Really just a guess about the type of the first arg.
        ;; Not sure what else it could possibly be.
        :args (s/cat :this ::individual-connection
                     :destination :com.frereth.client.connection-manager/connection-manager
                     :new-description ::auth-dialog-description))
(defn send-auth-descr-response!
  [{:keys [dialog-description] :as this}
   {:keys [request-id respond] :as destination}
   new-description]
  (comment (log/debug "Resetting ConnectionManager's dialog-description atom to\n"
                      new-description)
           ;; Q: Why did I think that eliminating this would be a good idea?
           ;; A: Because this simply does not belong in here
           (reset! dialog-description new-description))
  (log/debug "Forwarding world description to " respond)
  (async/>!! respond (assoc new-description :request-id request-id))
  (log/debug "Description sent"))

(s/fdef send-wait!
        :args (s/cat :channel-wrapper :com.frereth.client.connection-manager/callback-channel)
        :ret any?)
(defn send-wait!
  [{:keys [respond]}]
  (async/>!! respond :hold-please))

(s/fdef dispatch-auth-response!
        :args (s/cat :this :com.frereth.client.connection-manager/connection-manager
                     :c-b :com.frereth.client.connection-manager/connection-callback)
        :ret any?)
(defn dispatch-auth-response!
  [this cb]
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

(comment
  (s2/defn auth-loop-creator :- fr-skm/async-channel
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

(comment
  (s2/defn authorize-obsolete :- EventPair
    "Build an AUTH socket based on descr.
Call f with it to handle the negotiation to a 'real'
comms channel. Use that to build an EventPair using chan for the input.
Add that EventPair to this' remotes.

A major piece of this puzzle is that the socket description returned by f
needs to have an AUTH token attached. And the server should regularly
invalidate that token, forcing a re-AUTH.

The scope on this is really too small. I was thinking in terms of a single
Renderer using a single Client to connect to multiple servers. When, really,
the pattern that's emerging looks like a ton of web browsers connecting to
a web server that uses this library to connect individual users to a slew
of Server instances.
"
    ([this :- WorldManager
      loop-name :- s2/Str
      chan :- com-skm/async-channel
      status-chan :- com-skm/async-channel
      f :- (s2/=> socket-session SocketDescription)]
     (let [reader (fn [sock]
                    ;; Q: What should this do?
                    (throw (RuntimeException. "not implemented")))
           writer (fn [sock msg]
                    ;; Q: What should this do?
                    (throw (RuntimeException. "not implemented")))]
       (authorize this loop-name chan status-chan f reader writer)))
    ([this :- WorldManager
      loop-name :- s2/Str
      remote-address :- [s2/Int]
      remote-port :- s2/Int
      chan :- com-skm/async-channel
      status-chan :- com-skm/async-channel
      ;; Q: Any point to this?
      f :- (s2/=> socket-session SocketDescription)
      reader :- (s2/=> com-skm/java-byte-array mq-cmn/Socket)
      writer :- (s2/=> s2/Any mq-cmn/Socket com-skm/java-byte-array)]
     ;; Better to supply the EventLoop as part of the static System
     ;; definition.
     ;; Except that a huge part of the point is that this isn't
     ;; static.
     ;; Isn't it?
     ;; Connect locally to log in to your local server, but also freely
     ;; connect through here to any compatible server.
     ;; That basic point is why the idea of having the Login/Keystore
     ;; App here is just silly.
     ;; Might have thousands of end-users connected to a single web-server
     ;; part of the Renderer, using this library.
     ;; They aren't all going to open sockets to each server we might know about.
     ;; And, really, half (most?) of the point is connecting from here to other
     ;; untrusted 3rd party servers.
     ;; And, really, the approach I've been taking here is correct.
     (let [socket-description {:direction :connect
                               ;; TODO: Load a cert instead
                               :client-keys (curve/new-key-pair)
                               :server-key "FIXME: Find this"
                               :socket-type :dealer
                               :url {:address remote-address
                                     :port remote-port}}]
       (try
         (let [{:keys [url auth-token]} (comment (f auth-sock))
               sys-descr (describe-system)
               ;; TODO: Switch to common.system.build-event-loop instead
               ;; This isn't really an auth socket, though.
               ;; Still really only want one connection per client to any given
               ;; server.
               ;; That part of my recent thinking hasn't changed.
               _ (throw (ex-info "Start here" {}))
               initialized (cpt-dsl/build sys-descr
                                          {:sock {:sock-type :dealer
                                                  :url url}
                                           :event-loop-interface {:external-reader reader
                                                                  :external-writer writer
                                                                  :in-chan chan
                                                                  :status-chan status-chan}
                                           :event-loop {:_name loop-name}})
               injected (assoc-in initialized [:sock :ctx] (:ctx auth-sock))
               started (assoc (component/start injected)
                              :auth-token auth-token)]
           (swap! (:remotes this) assoc loop-name started)
           (log/warn "TODO: Really should handle handshake w/ new socket")
           started)
         (finally
           (if auth-sock
             (component/stop auth-sock)
             (log/error "No auth-sock. What happened?"))))))))

(comment
  ;; I used this to debug the basic idea behind what's going on in authorize.
  ;; Q: Is there any sort of meaningful/useful unit test
  ;; lurking around here?
  (let [prereq-descr {:structure '{:sock com.frereth.common.zmq-socket/ctor
                                   :ctx com.frereth.common.zmq-socket/ctx-ctor}
                      :dependencies {:sock [:ctx]}}
        initial-prereq (cpt-dsl/build prereq-descr
                                      {:sock {:sock-type :pair
                                              :url {:protocol :inproc
                                                    :address (name (gensym))}}
                                       :ctx {:thread-count 2}})
        prereq (component/start initial-prereq)]
    (try
      (let [auth-sock (:sock prereq)
            sys-descr (describe-system)
            url {:protocol :tcp
                 :address "localhost"
                 :port 7883}
            loop-name "who cares?"
            chan (async/chan)
            initialized (cpt-dsl/build sys-descr
                                       {:sock {:sock-type :dealer
                                               :url url}
                                        :event-loop {:_name loop-name
                                                     :in-chan chan}})
            injected (assoc-in initialized [:sock :ctx] (:ctx prereq))]
        injected)
      (finally
        (component/stop prereq)))))

(s/fdef initiate-handshake
        :args (s/cat :this :com.frereth.client.connection-manager/connection-manager
                     :request :com.frereth.client.connection-manager/connection-request
                     :attempts (s/and integer? pos?)
                     :timeout-ms (s/and integer? pos?))
        :ret ::optional-auth-dialog-description)
(defn initiate-handshake
  "The return value is wrong, but we do need something like this"
  [this
   request
   attempts
   timeout-ms]
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

(s/fdef establish-connection
        :args (s/cat :world-id :com.frereth.client.world-manager/world-id-type
                     :ctx :com.frereth.common.zmq-socket/context-wrapper
                     :url :cljeromq.core/zmq-url)
        :ret ::individual-connection)
(defn establish-connection
  [world-id
   ctx
   url]
  ;; TODO: Move this into its own Component that the ConnectionManager can depend on
  (let [dead-sock (zmq-socket/ctor {:ctx ctx
                                    :url url
                                    ;; FIXME: Key management!!
                                    ;; Doing that right really allows most of this
                                    ;; to go away.
                                    ;; And this approach ties me specifically to servers
                                    ;; that use the "private" key I'm publishing in the
                                    ;; name of the same sort of "worry about it later"
                                    ;; spirit that I'm using here
                                    :client-keys (curve/new-key-pair)
                                    :server-key (curve/z85-decode "vk<(J}0TEPeEsdZv+ZN.1)N[KlYVZPZgK(.36Qrx")
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
