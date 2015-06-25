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
                :two com.frereth.common.async-zmq/ctor}
        ctx (mq/context 1)
        one-pair (mq/build-internal-pair! ctx)
        two-pair (mq/build-internal-pair! ctx)
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
        configuration-tree {:one {:mq-ctx ctx
                                  :ex-sock (:lhs one-pair)
                                  :in-chan (async/chan)
                                  :external-reader reader
                                  :external-writer writer1}
                            :two {:mq-ctx ctx
                                  :ex-sock (:lhs two-pair)
                                  :in-chan (async/chan)
                                  :external-reader reader
                                  :external-writer writer2}}]
    (assoc (cpt-dsl/build {:structure descr
                           :dependencies {}}
                          configuration-tree)
           :other-sides {:one (:rhs one-pair)
                         :two (:rhs two-pair)})))

(defn started-mock
  []
  (let [initial (mock-up)
        others (:other-sides initial)
        safe (dissoc initial :other-sides)
        ;; Replace the external-sock of loop two
        ;; with the "other-side" of loop one's
        ;; external socket
        combined (assoc-in safe [:two :ex-sock]
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
  (throw (RuntimeException. "Running echo"))
  (testing "Can send a request and get an echo back"
    (is false "echo test is starting, right?")
    (let [test (fn [system]
                 (is false "Getting into the meat of the echo test")
                 (throw (RuntimeException. "Not Implemented")))]
      (with-mock test))))
