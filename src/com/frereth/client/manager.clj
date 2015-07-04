(ns com.frereth.client.manager
  (:require [cljeromq.core :as mq]
[clojure.core.async :as async]
            [com.frereth.common.async-zmq :as async-zmq]
            [com.frereth.common.schema :as com-skm]
            [com.frereth.common.zmq-socket]
            [com.stuartsierra.component :as component]
            [component-dsl.system :as cpt-dsl]
            [schema.core :as s]
            [taoensso.timbre :as log])
  (:import [com.frereth.common.async_zmq EventPair]
           [com.frereth.common.zmq_socket SocketDescription]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(def remote-map
  "This is really pretty meaningless, except possibly for documentation"
  (class (atom {s/Str EventPair})))

(def socket-session
  ""
  {:url SocketDescription
   :auth-token com-skm/java-byte-array})

(s/defrecord CommunicationsLoopManager [remotes :- remote-map]
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
   loop-name :- s/Str
   auth-descr :- SocketDescription
   chan :- com-skm/async-channel
   f :- (s/=> socket-session SocketDescription)]
  (let [auth-sock (component/start auth-descr)]
    (try
      (let [{:keys [url auth-token]} (f auth-sock)
            sys-descr {:structure {:sock com.frereth.common.zmq-socket/ctor
                                   :event-loop com.frereth.common.async-zmq/ctor
                                   ;; TODO: Really need to be able to just
                                   ;; specify the same context that descr
                                   ;; should have already had injected
                                   }
                       :dependencies {:event-loop {:ex-sock :sock}}}
            initialized (cpt-dsl/build sys-descr
                                       {:sock {:sock-type :dealer
                                               :url url}
                                        :event-loop {:_name loop-name
                                                     :in-chan chan}})
            injected (assoc-in initialized [:sock :ctx] (:ctx auth-sock))
            started (assoc (component/start injected)
                           :auth-token auth-token)]
        (swap! (:remotes this) assoc loop-name started)
        (log/warn "TODO: Really should handle handshake w/ new socket")
        started)
      (finally
        (if auth-sock
          (component/stop auth-sock)
          (log/error "No auth-sock. What happened?"))))))

(s/defn ctor :- CommunicationsLoopManager
  [options]
  (map->CommunicationsLoopManager options))
