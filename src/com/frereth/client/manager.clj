(ns com.frereth.client.manager
  (:require [cljeromq.core :as mq]
[clojure.core.async :as async]
            [com.frereth.common.async-zmq :as async-zmq]
            [com.frereth.common.schema :as com-skm]
            [com.frereth.common.util :as util]
            [com.frereth.common.zmq-socket :as zmq-socket]
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
       (doseq [remote @remotes]
         (let [interface (-> remote :event-loop :interface)
               chan (:in-chan interface)
               sock (:ex-sock interface)]
           ;; Q: Why am I shutting down the event
           ;; loop before closing its communications pathways?
           ;; A: Well, maybe there's an advantage to
           ;; giving them the final opportunity to flush
           ;; the pipeline, but it probably doesn't matter.
           (component/stop remote)
           ;; Q: Shouldn't that be a system that also stops/closes these?
           (comment (if chan
                      (async/close! chan)
                      (log/warn "Missing :in-chan in\n" (util/pretty remote)))
                    (if sock
                      (component/stop sock)
                      (log/warn "Missing :ex-sock in\n" (util/pretty remote))))))
       (log/debug "Finished stopping remotes")
       (assoc this :remotes nil))
     this)))

(defn initial-system-structure
  []
  '{:sock com.frereth.common.zmq-socket/ctor
    :event-loop com.frereth.common.async-zmq/ctor
    :event-loop-interface com.frereth.common.async-zmq/ctor-interface
   ;; TODO: Really need to be able to just
   ;; specify the same context that descr
   ;; should have already had injected
   })

(defn system-dependencies
  []
  {:event-loop {:interface :event-loop-interface}
   :event-loop-interface {:ex-sock :sock}})

(s/defn describe-system
  []
  {:structure (initial-system-structure)
   :dependencies (system-dependencies)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public
(comment
  ;; Q: Is there any sort of meaningful/useful unit test
  ;; lurking around here?
  (let [prereq-descr {:structure '{:sock com.frereth.common.zmq-socket/ctor
                                   :ctx com.frereth.common.zmq-socket/ctx-ctor}
                      :dependencies {:sock [:ctx]}}
        initial-prereq (cpt-dsl/build prereq-descr
                                      {:sock {:sock-type :pair
                                              :url {:protocol :inproc
                                                    :address (name (gensym))}}
                                       :ctx {:thread-count 2}})
        prereq (component/start initial-prereq)]
    (try
      (let [auth-sock (:sock prereq)
            sys-descr (describe-system)
            url {:protocol :tcp
                 :address "localhost"
                 :port 7883}
            loop-name "who cares?"
            chan (async/chan)
            initialized (cpt-dsl/build sys-descr
                                       {:sock {:sock-type :dealer
                                               :url url}
                                        :event-loop {:_name loop-name
                                                     :in-chan chan}})
            injected (assoc-in initialized [:sock :ctx] (:ctx prereq))]
        injected)
      (finally
        (component/stop prereq)))))

(s/defn authorize :- EventPair
  "Build an AUTH socket based on descr.
Call f with it to handle the negotiation to a 'real'
comms channel. Use that to build an EventPair using chan for the input.
Add that EventPair to this' remotes.

A major piece of this puzzle is that the socket description returned by f
needs to have an AUTH token attached. And the server should regularly
invalidate that token, forcing a re-AUTH."
  ([this :- CommunicationsLoopManager
    loop-name :- s/Str
    auth-descr :- SocketDescription
    chan :- com-skm/async-channel
    f :- (s/=> socket-session SocketDescription)]
   (let [reader (fn [sock]
                  (throw (RuntimeException. "not implemented")))
         writer (fn [sock msg]
                  (throw (RuntimeException. "not implemented")))]
     (authorize this loop-name auth-descr chan f reader writer)))
  ([this :- CommunicationsLoopManager
    loop-name :- s/Str
    auth-descr :- SocketDescription
    chan :- com-skm/async-channel
    f :- (s/=> socket-session SocketDescription)
    reader :- (s/=> com-skm/java-byte-array mq/Socket)
    writer :- (s/=> s/Any mq/Socket com-skm/java-byte-array)]
   (let [auth-sock (component/start auth-descr)]
     (try
       (let [{:keys [url auth-token]} (f auth-sock)
             sys-descr (describe-system)
             initialized (cpt-dsl/build sys-descr
                                        {:sock {:sock-type :dealer
                                                :url url}
                                         :event-loop-interface {:external-reader reader
                                                                :external-writer writer
                                                                :in-chan chan}
                                         :event-loop {:_name loop-name}})
             injected (assoc-in initialized [:sock :ctx] (:ctx auth-sock))
             started (assoc (component/start injected)
                            :auth-token auth-token)]
         (swap! (:remotes this) assoc loop-name started)
         (log/warn "TODO: Really should handle handshake w/ new socket")
         started)
       (finally
         (if auth-sock
           (component/stop auth-sock)
           (log/error "No auth-sock. What happened?")))))))

(s/defn ctor :- CommunicationsLoopManager
  [options]
  (map->CommunicationsLoopManager options))
