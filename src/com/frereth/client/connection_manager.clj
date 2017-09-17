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

The protocol contract is really more of the handshake/cert exchange
sort of thing. Once that part's done, this should hand off
to a world-manager/WorldManager.

Note that multiple end-users can use the connection being established
here. The credentials exchange shows that the other side has access to
the server's private key, and that this side has access to the appropriate
client private key (assuming the server checks).

It says nothing about the end-users who are using this connection."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            ;; Note that this is really only being used here for specs
            ;; So far. This seems wrong.
            [com.frereth.client.world-manager :as manager]
            [com.frereth.common.async-zmq :as async-zmq]
            [com.frereth.common.schema :as fr-skm]
            [com.frereth.common.system :as com-sys]
            [com.frereth.common.util :as util]
            [com.frereth.common.zmq-socket :as zmq-socket]
            [hara.event :refer (raise)]
            [integrant.core :as ig]
            [taoensso.timbre :as log])
  (:import [clojure.lang ExceptionInfo]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Specs

(s/def ::server-id-type :com.frereth.common.schema/generic-id)
(s/def ::url #_:cljeromq.common/zmq-url any?)

(s/def ::server-connection-map (s/and deref
                                      (s/map-of #_:cljeromq.common/zmq-url any? :com.frereth.client.world-manager/world-manager)))

(s/def ::client-keys #_:cljeromq.curve/key-pair any?)
(s/def ::local-url ::url)
(s/def ::message-context :com.frereth.common.zmq-socket/context-wrapper)
(s/def ::server-connections ::server-connection-map)
(s/def ::server-key #_:cljeromq.curve/public any?)

(s/def ::status-check :com.frereth.common.schema/async-channel)
(s/def ::manager (s/keys :req [::client-keys
                               ::local-url
                               ::message-context
                               ::server-connections
                               ::server-key
                               ::status-check]))
(s/def ::connection-manager-ctor-opts (s/keys :opt [::client-keys ::local-url ::server-key]))

(s/def ::respond :com.frereth.common.schema/async-channel)
;; Where do I send responses back to?
(s/def ::callback-channel (s/keys :req [::respond]))

;; Q: Does this make sense any more?
;; A: That depends.
;; Q: What was the initial point?
;; (first guess: request a new connection to a specific URL on a world.
;; If that's close, then this is missing details like the renderer session ID,
;; and it probably doesn't belong here.)
(s/def ::connection-request (s/keys :req [::url :com.frereth.client.world-manager/world-id-type]))

(s/def ::connection-callback (s/merge ::connection-request ::callback-channel))

(s/def ::server-id ::server-id-type)
(s/def ::server-connection-description (s/keys :req [::server-id ::url]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Component

;; It's a little annoying to need to pre-declare this.
;; But it's a public function to which the Component needs
;; access for its default local connection.
;; I'd rather handle it this way than mess with my file layout
;; conventions.
(declare establish-server-connection!)

(defmethod ig/init-key ::manager
  [_ {:keys [::local-url
             ::server-connections
             ::status-check]
      :as this}]
   ;; Create pieces that weren't supplied externally
  (let [this (assoc this
                    :status-check (or status-check (async/chan))
                    :server-connections (or server-connections (atom {})))]
    (comment) (establish-server-connection! this
                                            {::server-id :local
                                             ::url local-url})
    this))

(defmethod ig/halt-key! ::manager
  [_ {:keys [::server-connections
             ::status-check]}]
  (when server-connections
    (let [actual-connections @server-connections]
      ;; TODO: Hang on to the individual world description
      ;; Seems like it would make reconnecting
      ;; or session restoration easier
      ;; Then again, that's really a higher-level scope.
      ;; And it's YAGNI
      (doseq [connection (vals actual-connections)]
        (ig/halt! connection))))
  (when status-check
    ;; TODO: Track whether this was supplied or we created it
    ;; here.
    ;; As-written, we may or may not be responsible for cleaning
    ;; it up.
    (async/close! status-check)))

(s/fdef initialize-server-connection!
        :args (s/cat :this ::manager
                     :server ::server-connection-description)
        :ret ::manager)
(defn initialize-server-connection
  [{:keys [client-keys
           server-key
           message-context]
    :as this}
   {:keys [::server-id ::url]
    :as connection-description}]
  (let [event-loop-params {::com-sys/client-keys client-keys
                           ;; This part's broken now.
                           ;; I really need to be able to override this from here,
                           ;; even though it's far more convenient for the Server
                           ;; to just let common set up its own.
                           ::com-sys/context message-context
                           ::com-sys/event-loop-name server-id
                           ::com-sys/server-key server-key
                           ::com-sys/url url}
        event-loop-description (com-sys/build-event-loop-description event-loop-params)
        dscr (assoc event-loop-description
                    ::manager/world-manager {:event-loop (ig/ref ::async-zmq/event-loop)})]
    (ig/init dscr)))
(comment
  (let [baseline (initialize-server-connection
                  {:client-keys {:public "ac" :private "dc"}
                   :server-key {:public "This is safe to share"}
                   :ctx "Hmm. Supplying this isn't trivial"}
                  {::server-id "repl-check"
                   ::url #:cljeromq.common {:zmq-address "127.0.0.1"
                                            :zmq-protocol :tcp
                                            :port 21}})]
    (keys baseline))
  )
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/fdef establish-server-connection!
        :args (s/cat :this ::manager
                     :server ::server-connection-description)
        :ret ::manager)
(defn establish-server-connection!
  "Possibly alters ConnectionManager with a new world-manager"
  [{:keys [server-connections]
    :as this}
   {:keys [::server-id]
    :as connection-description}]
  (when-not (-> server-connections deref (get server-id))
    (let [initialized (initialize-server-connection this
                                                    connection-description)]
      ;; This probably needs to be a ref.
      ;; If two threads try to connect to the same server at the same time,
      ;; Bad Things(TM) will ensue.
      ;; Or maybe I should just do compare-and-set!...but that seems like it
      ;; would mean reinventing STM
      (swap! server-connections #(assoc %
                                        server-id
                                        (ig/init initialized))))
    this))

(comment
  (let [descr #:com.frereth.common.system {:client-keys (curve/new-key-pair)
                                           :context {:mock "Don't need any of this, do I?"}
                                           :event-loop-name :sample
                                           :server-key "Public, byte array"
                                           :url #:cljeromq.common {:zmq-address "127.0.0.1"
                                                                   :zmq-protocol :tcp
                                                                   :port 21}}
          baseline (com-sys/build-event-loop descr)]
    (keys baseline))
  )

(s/fdef connect-to-world!
        :args (s/cat :this ::manager
                     :renderer-session-id :com.frereth.client.world-manager/session-id-type
                     :server-id ::server-id-type
                     :world-id :com.frereth.client.world-manager/world-id-type)
        :ret ::manager/renderer-session)
(defn connect-to-world!
  [{conns :server-connections}
   renderer-session-id
   server-id
   world-id]
  (if-let [world-manager (-> conns deref (get server-id))]
    (manager/connect-renderer-to-world! world-manager world-id renderer-session-id)
    (throw (ex-info "Must call establish-server-connection! first"))))

(s/fdef disconnect-from-server!
        :args (s/cat :this ::manager
                     :server-id ::server-id-type)
        :ret ::manager)
(defn disconnect-from-server!
  [this server-id]
  (throw (ex-info "Not Implemented" {})))

(s/fdef disconnect-from-world!
        :args (s/cat :this ::manager
                     :renderer-session-id :manager/session-id-type
                     :server-id ::server-id-type
                     :world-id :manager/world-id-type)
        :ret ::manager)
(defn disconnect-from-world!
  [this
   renderer-session-id
   server-id
   world-id]
  (let [world-manager (-> this ::server-connections deref (get server-id))]
    (assert world-manager "Can't disconnect from a world if there's no server-connection")
    ;; It's very tempting to check the world connection count here and disconnect from the
    ;; server if/when it drops to 0.
    ;; But that's a higher-level consideration that needs to be handled by whatever called this.
    ;; TODO: Get that written
    (manager/disconnect-renderer-from-world! world-manager world-id renderer-session-id)
    this))

(s/fdef rpc
        :args (s/cat :this ::manager
                     :world-id  ::manager/world-id-type
                     :method keyword?
                     :data any?
                     ;; Q: What about 0 for no wait and < 0 for infinite?
                     :timeout-ms (s/and integer? pos?))
        :ret (s/nilable ::fr-skm/async-channel))
(defn rpc
  "For plain-ol' asynchronous request/response exchanges

TODO: This seems generally useful and probably belongs in common instead"
  ([this
    world-id
    method
    data
    timeout-ms]
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
  ([this
    request-id
    method
    data]
   (rpc this request-id method data (* 5 util/seconds))))

(s/fdef rpc-sync
        :args (s/cat :this ::manager
                     :request-id ::manager/world-id-type
                     :method keyword?
                     :data any?
                     :timeout (s/and integer? pos?)))
(defn rpc-sync
  "True synchronous Request/Reply"
  ([this
    request-id
    method
    data
    timeout]
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
  ([this
    request-id
    method
    data]
   (rpc-sync this request-id method data (* 5 (util/seconds)))))

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
