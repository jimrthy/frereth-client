(ns com.frereth.client.core
  (:require [com.stuartsierra.component :as component]
            [com.frereth.client.system :as system])
  (:gen-class))

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
