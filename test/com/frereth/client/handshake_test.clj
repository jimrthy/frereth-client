(ns com.frereth.client.handshake-test
  (:require [cljeromq.core :as mq]
            [clojure.core.async :as async]
            [clojure.test :refer (deftest is testing)]
            [com.frereth.client.manager :as mgr]
            [com.frereth.common.zmq-socket :as zmq-sock]
            [com.stuartsierra.component :as component]
            [component-dsl.system :as cpt-dsl]
            [schema.core :as s])
  (:import [com.stuartsierra.component SystemMap]))

(defn mock-up
  []
  (let [descr '{:mgr com.frereth.client.manager/ctor
                :ctx com.frereth.common.zmq-socket/ctx-ctor
                :fake-auth com.frereth.common.zmq-socket/ctor}
        configuration-tree {:ctx {:thread-count 2}
                            :fake-auth {:url {:protocol :inproc
                                              :address (name (gensym))}
                                        :sock-type :pair
                                        :direction :connect}}
        dependencies {:fake-auth [:ctx]}]
    (cpt-dsl/build {:structure descr
                    :dependencies dependencies}
                   configuration-tree)))

(comment
  (require '[com.frereth.common.util :as util])
  (util/thread-count)
  (def system
    (let [system (component/start (mock-up))
          in->out (async/chan)
          fake-auth (fn [_]
                      (zmq-sock/ctor {:direction :connect
                                      :sock-type :pair
                                      :url {:address (name (gensym))
                                            :protocol :inproc}}))
          loop-name "start/stop"
          event-loop (mgr/authorize (:mgr system)
                                    loop-name
                                    (:fake-auth system)
                                    in->out
                                    fake-auth)]
      ;; Don't do this!!
      #_(component/stop system)
      system))

)
(deftest bogus-auth
  (testing "Can start, fake connections, and stop"
    (let [system (component/start (mock-up))
          in->out (async/chan)
          fake-auth (fn [_]
                      (zmq-sock/ctor {:direction :connect
                                      :sock-type :pair
                                      :url {:address (name (gensym))
                                            :protocol :inproc}}))
          loop-name "start/stop"
          event-loop (mgr/authorize (:mgr system)
                                    loop-name
                                    (:fake-auth system)
                                    in->out
                                    fake-auth)]
      (try
        (let [remotes (-> system :mgr :remotes deref)  ; map of name/SystemMap (where SystemMap happens to include the EventPair et al)
              wrapped-event-loop (get remotes loop-name)]
          (is (= 1 (count remotes)))
          (is (= (:event-loop wrapped-event-loop) event-loop))
          (testing "Can send/receive status checks"
            (let [iface (:event-loop-interface wrapped-event-loop)]
              (async/go
                (let [status-sink (:status-out iface)
                      [v c] (async/alts! [status-sink (async/timeout 250)])]
                  (is (= c status-sink))
                  (is v)))
              (let [status-chan (:status-chan iface)
                    [v c] (async/alts!! [status-chan (async/timeout 250)])]
              (is v)))))
        (finally
          (testing "Stopping hand-shake test system"
            (try
              (component/stop system)
              (println "Handshake system stopped successfully")
              (catch RuntimeException ex
                (println "Stopping hand-shake test system failed:\n" ex))
              (finally
                (println "Hand-shake test complete")))))))))

(comment
  (deftest check-channel-close
    (testing "Can start, fake connections, and stop"
      (let [system (component/start (mock-up))
            in->out (async/chan)
            event-loop (mgr/authorize (:mgr system)
                                      "start/stop"
                                      (:fake-auth system)
                                      (in->out))]
        (try
          ;; TODO: What happens to system?
          (async/close! in->out)
          (finally
            (component/stop system)))))))
