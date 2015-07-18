(ns com.frereth.client.system
  "TODO: Almost all of this should go away"
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

(comment
  (def UnstartedClientSystem
  "Make it easier for others to validate

I think this had something to do with the system validation
part of the component library that I'm trying to use in
frereth.web.

I'm not very clear on other scenarios where it would make any sense."
  {:auth-sock SocketDescription
   ;; TODO: This needs to go away
   :controller-socket SocketDescription}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/defn init :- SystemMap
  [{:keys [ctx-thread-count
           auth-url]
    :or {ctx-thread-count (-> (util/core-count) dec)
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
                 :connection-manager [:auth-sock]}
        descr {:structure struct
               :dependencies depends}]
    (cpt-dsl/build descr
                   {:auth-sock {:sock-type :dealer
                                :url auth-url}})))
