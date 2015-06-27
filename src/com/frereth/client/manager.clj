(ns com.frereth.client.manager
  (:require [com.frereth.common.async-zmq]
            [com.frereth.common.zmq-socket]
            [com.stuartsierra.component :as component]
            [schema.core :as s])
  (:import [com.frereth.common.async_zmq EventPair]
           [com.frereth.common.zmq_socket SocketDescription]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(s/defrecord CommunicationsLoopManager [local-action :- EventPair
                                        action-socket :- SocketDescription
                                        local-controller :- EventPair
                                        controller-socket :- SocketDescription
                                        ;; Realistically and theoretically, it's probably
                                        ;; better to just put the local action and
                                        ;; controller sockets under remotes.
                                        ;; But they're really specially cases that happen
                                        ;; to be especially interesting at the beginning
                                        ;; TODO: Revisit this once the rope's across the
                                        ;; gorge
                                        remotes :- (s/maybe {SocketDescription EventPair})]
  component/Lifecycle
  (start
   [this]
   ;; Q: Since I'm really dealing with nested SystemMap instances (aren't I?),
   ;; do I need to handle calling start on the various dependencies?
   ;; TODO: If not, this might as well just be a plain ol' hashmap
   this)
  (stop
   [this]
   this))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/defn ctor :- CommunicationsLoopManager
  [options]
  (map->CommunicationsLoopManager options))
