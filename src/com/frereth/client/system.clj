(ns com.frereth.client.system
  "How it's all wired together

TODO: Needs something like slamhound to eliminate unused pieces directly below"
  (:require [cljeromq.core :as mq]
            [clojure.core.async :as async]
            [com.frereth.client
             [config :as cfg]
             [manager]]
            [com.frereth.common.async-zmq]
            [com.frereth.common.util :as util]
            [com.frereth.common.zmq-socket :as zmq-sock]
            [com.stuartsierra.component :as component]
            [component-dsl.system :as cpt-dsl]
            [schema.core :as s])
  (:import [com.frereth.client.manager CommunicationsLoopManager]
           [com.frereth.common.async_zmq EventPair]
           [com.frereth.common.zmq_socket ContextWrapper SocketDescription]
           [com.stuartsierra.component SystemMap]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/defn init :- SystemMap
  [{:keys [client-keys
           ctx-thread-count
           local-url
           server-key]
    :or {client-keys (curve/new-key-pair)
         ctx-thread-count (-> (util/core-count) dec (max 1))
         ;; Q: What do I want to do about connecting to external servers?
         ;; Do each of those requests need their own ConnectionManager?
         ;; It seems like a waste for them to bounce through the local
         ;; Server, though that may well have been what I had in mind
         local-url {:address "127.0.0.1"
                    :protocol :tcp
                    :port (cfg/auth-port)}
         ;; TODO: Generate this
         server-key ""}
    :as overrides}]
  (set! *warn-on-reflection* true)

  (assert local-auth-url)

  (let [struct '{;; We really need multiple instances of this, one per connected world
                 ;; It's owned by the connection manager
                 ;; :auth-sock
                 :connection-manager com.frereth.client.connection-manager/ctor
                 :ctx com.frereth.common.zmq-socket/ctx-ctor
                 ;; Also really need multiples of this
                 ;; Probably makes the most sense to let the connection-manager also
                 ;; handle these
                 ;; :message-loop-manager com.frereth.client.manager/ctor
                 }
        depends {:connection-manager {:message-context :ctx}}]
    (cpt-dsl/build {:structure struct
                    :dependencies depends}
                   {:connection-manager {:client-keys client-keys
                                         :local-url local-url
                                         :server-key server-key}
                    :ctx {:thread-count ctx-thread-count}})))
