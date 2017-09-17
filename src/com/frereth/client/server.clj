(ns com.frereth.client.server
  (:refer-clojure :exclude [read-strings])
  (:require [clojure.tools.reader.edn :as edn]))

(defn negotiate-local-connection
  "Tell local server who we are.

TODO: Verify/document/validate using spec"
  [socket]
  ;; This seems silly-verbose.
  ;; But, we do need to verify that the server is present
  (comment
    (mq/send! socket :ohai)
    (let [raw-resp (mq/recv! socket)
          resp (edn/read-string raw-resp)]
      (assert (= resp :oryl?)))
    (mq/send! (list :icanhaz? {:me-speekz {:frereth [0 0 1]}}))
    (edn/read-string (mq/recv! socket)))
  (throw (RuntimeException. "Need to convert that to use CurveCP")))
