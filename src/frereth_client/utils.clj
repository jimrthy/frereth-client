(ns frereth-client.utils
  (:require [ribol.core :refer (raise)]))

(set! *warn-on-reflection* true)

(defn error
  "This should really go away"
  [obj]
  ;; This seems likely to make the stack trace even more useless
  (raise [:obsolete {:reason "Ribol does this better. This should just go away"}]))
