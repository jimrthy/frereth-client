(ns com.frereth.client.system
  "How it's all wired together"
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
  [{:keys [ctx-thread-count
           auth-url]
    :or {ctx-thread-count (-> (util/core-count) dec)
         ;; Q: What do I want to do about connecting to external servers?
         ;; Do each of those requests need their own ConnectionManager?
         ;; It seems like a waste for them to bounce through the local
         ;; Server, though that may well have been what I had in mind
         auth-url {:address "127.0.0.1"
                   :protocol :tcp
                   :port (cfg/auth-port)}}
    :as overrides}]
  (set! *warn-on-reflection* true)

  (let [struct '{:auth-sock com.frereth.common.zmq-socket/ctor
                 :connection-manager com.frereth.client.connection-manager/ctor
                 :ctx com.frereth.common.zmq-socket/ctx-ctor
                 :message-loop-manager com.frereth.client.manager/ctor}
        depends {:auth-sock [:ctx]
                 ;; Note that the message-loop-manager does not depend
                 ;; on the connection-manager, though it really seems like
                 ;; it should
                 :connection-manager [:auth-sock]}
        descr {:structure struct
               :dependencies depends}]
    (cpt-dsl/build descr
                   {:auth-sock {:sock-type :dealer
                                :url auth-url}})))
