(ns com.frereth.client.server
  (:refer-clojure :exclude [read-strings])
  (:require [clojure.tools.reader.edn :as edn]
            [cljeromq.core :as mq]))

(defn negotiate-local-connection
  "Tell local server who we are.

TODO: Verify/document/validate using spec"
  [socket]
  ;; This seems silly-verbose.
  ;; But, we do need to verify that the server is present
  (mq/send! socket :ohai)
  (let [raw-resp (mq/recv! socket)
        resp (edn/read-string raw-resp)]
    (assert (= resp :oryl?)))
  (mq/send! (list :icanhaz? {:me-speekz {:frereth [0 0 1]}}))
  (edn/read-string (mq/recv! socket)))
