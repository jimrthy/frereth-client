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
            [cljeromq.curve :as curve]  ; FIXME: Debug only
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            ;; Note that this is really only being used here for schema
            ;; So far. This seems wrong.
            [com.frereth.client.world-manager :as manager]
            [com.frereth.common.communication :as com-comm]
            [com.frereth.common.schema :as fr-skm]
            [com.frereth.common.system :as com-sys]
            [com.frereth.common.util :as util]
            [com.frereth.common.zmq-socket :as zmq-socket]
            [com.stuartsierra.component :as component]
            [clj-time.core :as dt]
            [ribol.core :refer (raise)]
            [schema.core :as s]
            [taoensso.timbre :as log])
  (:import [clojure.lang ExceptionInfo]
           [com.frereth.client.world_manager WorldManager]
           [com.frereth.common.zmq_socket ContextWrapper SocketDescription]
           [com.stuartsierra.component SystemMap]
           [java.util Date]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(def server-connection-map
  (atom {mq/zmq-url CommunicationsLoopManager}))

(declare establish-server-connection!)
(s/defrecord ConnectionManager
    [client-keys :- curve/key-pair
     local-url :- mq/zmq-url
     message-context :- ContextWrapper
     server-connections :- fr-skm/atom-type  ; server-connection-map
     server-key :- mq-cmn/byte-array-type
     status-check :- fr-skm/async-channel]
  component/Lifecycle
  (start
   [this]
   ;; Create pieces that weren't supplied externally
    (let [this (assoc this
                      :status-check (or status-check (async/chan))
                      :server-connections (or server-connections (atom {})))]
      (establish-server-connection! this
                                    {:server-id :local
                                     :url local-url})
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

(def callback-channel
  "Where do I send responses back to?"
  {:respond fr-skm/async-channel})

(def connection-request
  "Q: Does this make sense any more?"
  {:url mq/zmq-url
   :request-id manager/world-id})

(def connection-callback (into connection-request callback-channel))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(s/defn establish-server-connection!
  "Possibly alters ConnectionManager with a new world-manager"
  [{:keys [server-connections
           client-keys
           server-key
           ctx :- ContextWrapper]
    :as con-man} :- ConnectionManager
   {:keys [server-id :- manager/world-id
           url :- mq/zmq-url]}]
  (when-not (server-connections deref (get world-id))
    (let [descr {:client-keys client-keys
                 :context ctx
                 :event-loop-name server-id
                 :server-key server-key
                 :url url}
          ;; The world-manager takes responsibility for starting/stopping
          event-loop (com-sys/build-event-loop descr)
          world-manager (manager/ctor {:event-loop event-loop})]
      (swap! server-connections #(assoc % world-id (component/start world-manager))))))

(comment
  (let [descr {:client-keys (curve/new-key-pair)
               :context {:mock "Don't need any of this, do I?"}
               :event-loop-name :sample
               :server-key "Public, byte array"
               :url  {:address "127.0.0.1"
                      :protocol :tcp
                      :port (cfg/auth-port)}}
          baseline (com-sys/build-event-loop descr)
          ]
    (keys baseline))
  )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/defn rpc :- (s/maybe fr-skm/async-channel)
  "For plain-ol' asynchronous request/response exchanges

TODO: This seems generally useful and probably belongs in common instead"
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
  "The return value is wrong, but we do need something like this"
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
  [{:keys [client-keys local-auth-url server-key]}]
  (map->ConnectionManager {:local-auth-url local-auth-url}))
