(ns frereth-client.handshake-test
  (:require [cljeromq.core :as mq]
            [clojure.test :refer (deftest is)]))

(comment
  (let [sock (-> system :server :socket)]
    (mq/send! sock "PING")))

(deftest echo
  []
  (println "FIXME: Do something interesting"))
