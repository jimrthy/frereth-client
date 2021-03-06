(ns com.frereth.client.handshake-test
  "Realistically, need to rewrite this
  To use dispatcher instead.
  Well, this is really for testing the ConnectionManager part."
  (:require [cljeromq.core :as mq]
            [cljeromq.curve :as curve]
            [clojure.core.async :as async]
            [clojure.test :refer (deftest is testing)]
            [com.frereth.client.world-manager :as mgr]
            [com.frereth.common.util :as util]
            [com.frereth.common.zmq-socket :as zmq-sock]
            [com.stuartsierra.component :as component]
            [component-dsl.system :as cpt-dsl])
  (:import [com.stuartsierra.component SystemMap]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Helpers

(defn mock-dscr
  []
    (let [descr '{:event-loop com.frereth.common.system/build-event-loop-description
                  :mgr com.frereth.client.world-manager/ctor}
        ;; For the sake of consistency, just use 2 threads.
        ;; That way a simple blocking read won't hose even single-CPU systems,
        ;; but we won't have any surprising differences when we move to systems
        ;; with lots of cores.
        ;; This isn't exactly a good decision, but I'm really not interested in
        ;; trying to unit test the zeromq multi-threading implementation.
        configuration-tree {:event-loop {:client-keys (curve/new-key-pair)
                                         :direction :connect
                                         :event-loop-name "handhsake::mock-up"
                                         ;; Q: Do the contents matter at all for an inproc connection?
                                         :server-key (byte-array 40)
                                         :socket-type :pair
                                         :thread-count 2
                                         :url {:cljeromq.common/zmq-protocol :inproc
                                               :cljeromq.common/zmq-address (name (gensym))}}}
        dependencies {:mgr [:event-loop]}]
      {:descr descr
       :dependencies dependencies
       :configuration-tree configuration-tree}))
(comment
  (let [{:keys [:configuration-tree :dependencies :descr]} (mock-dscr)
        pre-processed (cpt-dsl/pre-process #:component-dsl.system{:structure descr
                                                                  :dependencies dependencies
                                                                  :options configuration-tree})]
    pre-processed)
  )

(defn mock-up
  []
  (let [{:keys [:configuration-tree :dependencies :descr]} (mock-dscr)]
    (cpt-dsl/build {:component-dsl.system/structure descr
                    :component-dsl.system/dependencies dependencies}
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
          loop-name "manual start/stop"
          event-loop (mgr/authorize (:mgr system)
                                    loop-name
                                    (:fake-auth system)
                                    in->out
                                    fake-auth)]
      ;; Don't do this!! (this is where things hang)
      ;; TODO: Figure out why. This is a good test
      #_(component/stop system)
      system))
  )

(comment
  (let [initialized (mock-up)
        started (component/start initialized)]
    (try
      started
      (finally (component/stop started))))
  )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest bogus-auth
  ;; This seems more than a little ridiculous
  (testing "Can start, fake connections, and stop"
    (println "Top of bogus-auth. Thread count: " (util/thread-count))
    (let [system (component/start (mock-up))]
      (try
        (println "Component started. Trying to connect a renderer to a world")
        (mgr/connect-renderer-to-world! (:mgr system)
                                        "World::BogusAuthenticator"
                                        "Renderer::RequestingBogusAuthentication")
        (throw (ex-info "Start here" {}))
        ;; Q: Is there any point to anything else in this let block?
        (let [in->out (async/chan)
              status-requester (async/chan)
              fake-auth (fn [_]
                          (zmq-sock/ctor {:direction :connect
                                          :sock-type :pair
                                          :url {:address (name (gensym))
                                                :protocol :inproc}}))
              loop-name "start/stop"
              pre-event-thread-count (util/thread-count)
              ;; This is really pretty obsolete.
              ;; The only real question is: how much of what's left isn't?
              event-loop (comment (mgr/authorize (:mgr system)
                                                 loop-name
                                                 (:fake-auth system)
                                                 in->out
                                                 status-requester
                                                 fake-auth))]
          (println "After starting System, running " pre-event-thread-count "threads."
                   "After starting the EventPair, have" (util/thread-count))
          (try
            (let [remotes (-> system :mgr :remotes deref)  ; map of name/SystemMap (where SystemMap happens to include the EventPair et al)
                  wrapped-event-loop (get remotes loop-name)]
              (is wrapped-event-loop (str "Missing EventLoop '" loop-name "' in\n" (util/pretty remotes)))
              (is (= 1 (count remotes)))
              (when-not (= wrapped-event-loop event-loop)
                ;; All these tests should fail from that, but I can't really see what's happening
                ;; So try breaking it down into disgusting detail
                (is (= (keys event-loop) (-> wrapped-event-loop keys)))
                ;; This is an implementation detail that is probably wrong.
                ;; (authorize) returns the EventPair when it connects and updates itself
                (is (= wrapped-event-loop event-loop)))
              (testing "Can send/receive status checks"
                (if-let [iface (:event-loop-interface wrapped-event-loop)]
                  (do
                    (println "Kicking off background thread to listen for status check request\nThread count: "
                             (util/thread-count))
                    (async/go
                      ;; This is at least a little obnoxious, but I'm not
                      ;; sure how else you could approach it.
                      (testing "Receive result of status check request"
                        (let [status-sink (:status-out iface)
                              ;; It really shouldn't take a 1/4 second to get here, unless something
                              ;; else went drastically wrong.
                              [v c] (async/alts! [status-sink (async/timeout 250)])]
                          (println "Background status check thread. We either got a result or decided to quit waiting around\nThread Count:"
                                   (util/thread-count))
                          (is (= c status-sink))
                          (is v))))
                    (let [status-chan (:status-chan iface)]
                      (is (= status-chan status-requester))
                      (let [[v c] (async/alts!! [[status-chan :whatever] (async/timeout 250)])]
                        (println "Status request message submitted. Thread count:" (util/thread-count))
                        (is v))))
                  (is false (str "Missing :interface in\n"
                                 (util/pretty wrapped-event-loop)
                                 ("with keys:\n" (keys wrapped-event-loop)))))))
            (finally
              (testing "Stopping hand-shake test system"
                (try
                  (println "Shutting everything down. Thread count: " (util/thread-count)
                           "\nSystem: " (keys system)
                           "\n" (util/pretty system)
                           "\n**************************************")
                  (let [stopped (component/stop system)]
                    (println "Handshake system stopped successfully\n"
                             (util/pretty stopped)))
                  (catch RuntimeException ex
                    (println "Stopping hand-shake test system failed:\n" ex))
                  (finally
                    (println "Hand-shake test complete. Remaining thread count:"
                             (util/thread-count))))))))
        (finally
          (component/stop system))))))

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
