(ns frereth-client.utils
  (:gen-class))

(defn error
  "Somebody fuckd up"
  [obj]
  ;; This seems likely to make the stack trace even more useless
  (throw (RuntimeException. (str obj))))
