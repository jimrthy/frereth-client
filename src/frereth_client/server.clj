(ns frereth-client.server
  (:refer-clojure :exclude [read-strings])
  (:require [clojure.tools.reader.edn :as edn]
            ;;[cljeromq.core :as mq]
            [zeromq.zmq :as mq]))

(set! *warn-on-reflection* true)

(defn negotiate-local-connection
  "Tell local server who we are."
  [socket]
  ;; This seems silly-verbose.
  ;; But, we do need to verify that the server is present
  (mq/send socket :ohai)
  (let [raw-resp (mq/receive socket)
        resp (edn/read-string raw-resp)]
    (assert (= resp :oryl?)))
  (mq/send (list :icanhaz? {:me-speekz {:frereth [0 0 1]}}))
  (edn/read-string (mq/receive socket)))

