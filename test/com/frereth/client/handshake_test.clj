(ns com.frereth.client.handshake-test
  (:require [cljeromq.core :as mq]
            [clojure.core.async :as async]
            [clojure.test :refer (deftest is testing)]
            [com.stuartsierra.component :as component]
            [component-dsl.system :as cpt-dsl]
            [schema.core :as s])
  (:import [com.stuartsierra.component SystemMap]))

(defn mock-up
  "This is copy/pasted from com.frereth.common.async-zmq-test
  TODO: Move it into its own utility/test-helper namespace"
  []
  (let [descr '{:one com.frereth.common.async-zmq/ctor
                :two com.frereth.common.async-zmq/ctor
                :ctx com.frereth.common.zmq-socket/ctx-ctor
                :ex-one com.frereth.common.zmq-socket/ctor
                :ex-two com.frereth.common.zmq-socket/ctor}
        ;; TODO: It's tempting to set these built-ins
        ;; as defaults, but they really won't be useful
        ;; very often
        reader (fn [sock]
                 (comment (println "Mock Reader triggered"))
                 (let [read (mq/raw-recv! sock)]
                   (comment (println "Mock Reader Received:\n" (util/pretty read)))
                   read))
        generic-writer (fn [which sock msg]
                         ;; Q: if we're going to do this,
                         ;; does the event loop need access to the socket at all?
                         ;; A: Yes. Because it spends most of its time polling on that socket
                         (println "Mock writer sending" msg "on Pair" which)
                         (mq/send! sock msg :dont-wait))
        writer1 (partial generic-writer "one")
        writer2 (partial generic-writer "two")
        internal-url (name (gensym))
        configuration-tree {:one {:_name "EventPairOne"
                                  :in-chan (async/chan)
                                  :external-reader reader
                                  :external-writer writer1}
                            :two {:_name "EventPairTwo"
                                  :in-chan (async/chan)
                                  :external-reader reader
                                  :external-writer writer2}
                            :ex-one {:url {:protocol :inproc
                                           :address internal-url}
                                     :sock-type :pair
                                     :direction :bind}
                            :ex-two {:url {:protocol :inproc
                                           :address internal-url}
                                     :sock-type :pair
                                     :direction :connect}
                            :ctx {:thread-count 2}}]
    (cpt-dsl/build {:structure descr
                    :dependencies {:one {:ex-sock :ex-one}
                                   :two {:ex-sock :ex-two}
                                   :ex-one [:ctx]
                                   :ex-two [:ctx]}}
                   configuration-tree)))

(defn started-mock
  []
  (let [initial (mock-up)
        others (:other-sides initial)
        safe (dissoc initial :other-sides)
        ;; Replace the external-sock of loop two
        ;; with the "other-side" of loop one's
        ;; external socket
        combined (assoc-in safe
                           [:two :ex-sock]
                           (:one others))]
    (component/start combined)))

(s/defn with-mock
  "This really isn't a good way to handle this, but it seems like an obvious lazy starter approach

To be fair, the 'proper' approach here is starting to look like a macro.

I've already been down that path with midje.

I'd like to pretend that the results would be happier with macros that
I write, but I know better."
  [f :- (s/=> s/Any SystemMap)]
  (let [system (started-mock)]
    (try
      (f system)
      (finally
        (component/stop system)))))

(deftest echo
  []
  (testing "Can send a request and get an echo back"
    (let [test (fn [system]
                 (let [left-chan (-> system :one :in-chan)
                       right-chan (-> system :two :in-chan)
                       msg '(+ 7 3)]
                   (testing "Request sent"
                     (let [[v c] (async/alts!! [[left-chan msg] (async/timeout 150)])]
                       (is (= c left-chan))
                       (is v)))
                   (let [[v c] (async/alts!! [right-chan (async/timeout 750)])]
                     (testing "Initial request received"
                       (is (= c right-chan))
                       (is (= msg v))))
                   (let [[v c] (async/alts!! [[right-chan msg] (async/timeout 150)])]
                     (testing "Response sent"
                       (is (= c right-chan))
                       (is v)))
                   (let [[v c] (async/alts!! [left-chan (async/timeout 750)])]
                     (testing "Echo received"
                       (is (= c left-chan))
                       (is (= msg v))))))]
      (with-mock test))))
