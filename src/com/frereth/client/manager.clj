(ns com.frereth.client.manager
  (:require [cljeromq.core :as mq]
[clojure.core.async :as async]
            [com.frereth.common.async-zmq]
            [com.frereth.common.schema :as com-skm]
            [com.frereth.common.zmq-socket]
            [com.stuartsierra.component :as component]
            [schema.core :as s])
  (:import [com.frereth.common.async_zmq EventPair]
           [com.frereth.common.zmq_socket SocketDescription]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(s/defrecord CommunicationsLoopManager [auth-sock :- SocketDescription
                                        local-controller :- EventPair
                                        remotes :- com-skm/atom-type]
  component/Lifecycle
  (start
   [this]
   (if-not remotes
     (assoc this :remotes (atom {}))
     this))
  (stop
   [this]
   (if remotes
     (do
       (doseq [remote remotes]
         (let [chan (:in-chan remote)
               sock (:ex-sock remote)]
           (component/stop remote)
           (async/close! chan)
           (component/stop sock)))
       (assoc this :remotes nil))
     this)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/defn authorize :- EventPair
  "Build an AUTH socket based on descr.
Call f with it to handle the negotiation to a 'real'
comms channel. Use that to build an EventPair using chan for the input.
Add that EventPair to this' remotes.

A major piece of this puzzle is that the socket description returned by f
needs to have an AUTH token attached. And the server should regularly
invalidate that token, forcing a re-AUTH."
  [this :- CommunicationsLoopManager
   descr :- SocketDescription
   chan :- com-skm/async-channel
   f]
  (io!
   (throw (RuntimeException. "Not Implemented: start here"))))

(s/defn ctor :- CommunicationsLoopManager
  [options]
  (map->CommunicationsLoopManager options))
