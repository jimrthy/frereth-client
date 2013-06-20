(ns frereth-client.core
  (:gen-class)
  (:require [frereth-client.system :as system]))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (let [pre-universe (system/init)]
    (let [universe (system/start pre-universe)]
      (try
        (println "Hello, World!")
        (finally
          (system/stop universe))))))
