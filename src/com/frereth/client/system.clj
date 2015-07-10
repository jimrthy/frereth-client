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

(def UnstartedClientSystem
  "Make it easier for others to validate"
  {:auth-sock SocketDescription
   ;; :ctx ContextWrapper
   ;; :control-message-loop EventPair
   :controller-socket SocketDescription
   ;; :message-loop-manager CommunicationsLoopManager
   })

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/defn init :- SystemMap
  [{:keys [ctx-thread-count
           auth-url
           local-control-url
           local-control-chan]
    :or {ctx-thread-count (-> (util/core-count) dec)
         ;; Here are prime examples of why my component-dsl needs to support update-in
         ;; TODO: Don't allow default channels here
         ;; These really need to be their own component,
         ;; supplied by caller.
         ;; But this is convenient for dev work
         local-control-chan (async/chan)
         ;; Server's expecting this to be inproc.
         ;; For the sake of safety/security, should probably go that route.
         ;; For now, I'm trying to come up with something vaguely interesting.
         ;; Which could just as easily happen over the local-action-sock
         local-control-url {:protocol :inproc
                            :address "super-secret-control"}
         auth-url {:address "127.0.0.1"
                   :protocol :tcp
                   :port (cfg/auth-port)}}
    :as overrides}]
  (set! *warn-on-reflection* true)

  (let [struct '{:auth-sock com.frereth.common.zmq-socket/ctor
                 :ctx com.frereth.common.zmq-socket/ctx-ctor
                 :message-loop-manager com.frereth.client.manager/ctor}
        depends {:auth-sock [:ctx]
                 :message-loop-manager [:auth-sock]}
        descr {:structure struct
               :dependencies depends}]
    (cpt-dsl/build descr
                   {:auth-sock {:sock-type :req
                                :url auth-url}})))
