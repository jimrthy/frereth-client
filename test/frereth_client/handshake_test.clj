(ns handshake-test
  (:require [cljeromq.core :as mq]))

(comment
  (let '[sock (-> system :server :socket)]
    (mq/send! sock "PING")))

(deftest echo
  []
  (println "FIXME: Do something interesting"))
