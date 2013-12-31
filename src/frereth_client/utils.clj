(ns frereth-client.utils
  (:gen-class))

(set! *warn-on-reflection* true)

(defn error
  "This should really go away"
  [obj]
  ;; This seems likely to make the stack trace even more useless
  (throw (RuntimeException. (str obj))))
