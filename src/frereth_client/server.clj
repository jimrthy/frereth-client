(ns frereth-client.server
  (:require [cljeromq.core :as mq])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn negotiate-local-connection
  "Tell local server who we are."
  [socket]
  ;; This seems silly-verbose.
  ;; But, we do need to verify that the server is present
  (mq/send socket :ohai)
  (let [resp (mq/recv socket)]
    (assert (= resp :oryl?)))
  (mq/send (list :icanhaz? {:me-speekz {:frereth [0 0 1]}}))
  (mq/recv socket))

