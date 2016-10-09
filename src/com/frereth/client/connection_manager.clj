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
  (:require [cljeromq.common :as mq-cmn]
            [cljeromq.core :as mq]
            [cljeromq.curve :as curve]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.spec :as s]
            ;; Note that this is really only being used here for specs
            ;; So far. This seems wrong.
            [com.frereth.client.world-manager :as manager]
            [com.frereth.common.schema :as fr-skm]
            [com.frereth.common.system :as com-sys]
            [com.frereth.common.util :as util]
            [com.frereth.common.zmq-socket :as zmq-socket]
            [com.stuartsierra.component :as component]
            [component-dsl.system :as cpt-dsl]
            [hara.event :refer (raise)]
            [taoensso.timbre :as log])
  (:import [clojure.lang ExceptionInfo]
           [com.frereth.client.world_manager WorldManager]
           [com.frereth.common.zmq_socket ContextWrapper]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Specs

(s/def ::server-id-type :com.frereth.common.schema/generic-id)
(s/def ::url :cljeromq.common/zmq-url)

(s/def ::server-connection-map (s/and deref
                                      (s/map-of :cljeromq.common/zmq-url :com.frereth.client.world-manager/world-manager)))

(s/def ::client-keys :cljeromq.curve/key-pair)
(s/def ::local-url ::url)
(s/def ::message-context :com.frereth.common.zmq-socket/context-wrapper)
(s/def ::server-connections ::server-connection-map)
(s/def ::server-key :cljeromq.curve/public)

(s/def ::status-check :com.frereth.common.schema/async-channel)
(s/def ::connection-manager (s/keys :req-un [::client-keys
                                             ::local-url
                                             ::message-context
                                             ::server-connections
                                             ::server-key
                                             ::status-check]))
(s/def ::connection-manager-ctor-opts (s/keys :opt-un [::client-keys ::local-url ::server-key]))

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
(defrecord ConnectionManager
    [client-keys
     local-url
     message-context
     server-connections
     server-key
     status-check]
  component/Lifecycle
  (start
   [this]
   ;; Create pieces that weren't supplied externally
    (let [this (assoc this
                      :status-check (or status-check (async/chan))
                      :server-connections (or server-connections (atom {})))]
      (establish-server-connection! this
                                    {::server-id :local
                                     ::url local-url})
      this))
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
         (component/stop connection))))
   (when status-check
     (async/close! status-check))
    (assoc this
           :message-context nil   ; Q: Do I really want to nil this out?
           :status-check nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn initialize-server-connection!
  [{:keys [server-connections
           client-keys
           server-key
           ctx]
    :as this}
   {:keys [::server-id ::url]
    :as connection-description}]
  (let [event-loop-params {:client-keys client-keys
                           ;; This part's broken now.
                           ;; I really need to be able to override this from here,
                           ;; even though it's far more convenient for the Server
                           ;; to just let common set up its own.
                           ;; Pretty sure this will just break component-dsl.
                           :context ctx
                           :event-loop-name server-id
                           :server-key server-key
                           :url url}
        ;; This is still using the original version where I was nesting
        ;; components manually.
        ;; Really should just be setting this up as a nested ctor
        event-loop-description-wrapper (com-sys/build-event-loop-description event-loop-params)
        event-loop-description (:description event-loop-description-wrapper)
        event-loop-structure (:structure event-loop-description)

        struct (assoc event-loop-structure
                      ;; Q: What about the :dispatcher?
                      :world-manager 'com.frereth.client.world-manager/ctor)
        deps (assoc (:dependencies event-loop-description)
                    :world-manager [:event-loop])
        ;; These really don't fit the classic intended use of Components:
        ;; those are all started/stopped at the same time.
        ;; But it's still a very useful abstraction for this sort of thing.
        system-description #:component-dsl.system {:structure struct
                                                   :dependencies deps}]
    (cpt-dsl/build system-description {})))

(s/fdef establish-server-connection!
        :args (s/cat :this ::connection-manager
                     :server ::server-connection-description)
        :ret ::connection-manager)
(defn establish-server-connection!
  "Possibly alters ConnectionManager with a new world-manager"
  [{:keys [server-connections]
    :as this}
   {:keys [::server-id]
    :as connection-description}]
  (when-not (-> server-connections deref (get server-id))
    (let [initialized (initialize-server-connection! this
                                                     connection-description)]
      (swap! server-connections #(assoc %
                                        server-id
                                        (component/start initialized))))
    this))

(comment
  (let [descr {:client-keys (curve/new-key-pair)
               :context {:mock "Don't need any of this, do I?"}
               :event-loop-name :sample
               :server-key "Public, byte array"
               :url  {:address "127.0.0.1"
                      :protocol :tcp
                      :port 21}}
          baseline (com-sys/build-event-loop descr)]
    (keys baseline))
  )

(s/fdef connect-to-world!
        :args (s/cat :this ::connection-manager
                     :renderer-session-id :com.frereth.client.world-manager/session-id-type
                     :server-id ::server-id-type
                     :world-id :com.frereth.client.world-manager/world-id-type)
        :ret :com.frereth.client.world-manager/renderer-session)
(defn connect-to-world!
  [{conns :server-connections}
   renderer-session-id
   server-id
   world-id]
  (if-let [world-manager (-> conns deref (get server-id))]
    (manager/connect-renderer-to-world! world-manager world-id renderer-session-id)
    (throw (ex-info "Must call establish-server-connection! first"))))

(s/fdef disconnect-from-server!
        :args (s/cat :this ::connection-manager
                     :server-id ::server-id-type)
        :ret ::connection-manager)
(defn disconnect-from-server!
  [this server-id]
  (throw (ex-info "Not Implemented" {})))

(s/fdef disconnect-from-world!
        :args (s/cat :this ::connection-manager
                     :renderer-session-id :com.frereth.client.world-manager/session-id-type
                     :server-id ::server-id-type
                     :world-id :com.frereth.client.world-manager/world-id-type)
        :ret ::connection-manager)
(defn disconnect-from-world!
  [this
   renderer-session-id
   server-id
   world-id]
  (let [world-manager (-> this :server-connections deref (get server-id))]
    (assert world-manager "Can't disconnect from a world if there's no server-connection")
    ;; It's very tempting to check the world connection count here and disconnect from the
    ;; server if/when it drops to 0.
    ;; But that's a higher-level consideration that needs to be handled by whatever called this.
    ;; TODO: Get that written
    (manager/disconnect-renderer-from-world! world-manager world-id renderer-session-id)
    this))

(s/fdef rpc
        :args (s/cat :this ::connection-manager
                     :world-id  :com.frereth.client.world-manager/world-id-type
                     :method keyword?
                     :data any?
                     ;; Q: What about 0 for no wait and < 0 for infinite?
                     :timeout-ms (s/and integer? pos?))
        :ret (s/nilable :com.frereth.common.schema/async-channel))
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
        :args (s/cat :this ::connection-manager
                     :request-id :com.frereth.client.world-manager/world-id-type
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

(s/fdef ctor
        :args (s/cat :opts ::connection-manager-ctor-opts)
        :ret ::connection-manager)
(defn ctor
  [opts]
  (map->ConnectionManager (select-keys opts [:client-keys :local-url :server-key])))
