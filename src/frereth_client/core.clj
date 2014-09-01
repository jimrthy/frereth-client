(ns frereth-client.core
  (:gen-class)
  (:require [com.stuartsierra.component :as component]
            [frereth-client.system :as system]))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (let [pre-universe (system/init)]
    (let [universe (component/start pre-universe)]
      (try
        (let [done (:done universe)]
          @done)
        (finally
          (component/stop universe))))))
