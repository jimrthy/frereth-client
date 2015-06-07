(ns com.frereth.client.utils
  (:require [ribol.core :refer (raise)]))

(comment
  (defn error
  "Somebody messed up"
  [obj]
  ;; This seems likely to make the stack trace even more useless
  (raise [:obsolete {:reason "Ribol does this better. This should just go away"}])))
