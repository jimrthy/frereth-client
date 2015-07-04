(ns com.frereth.client.core
  (:require [com.stuartsierra.component :as component]
            [com.frereth.client.system :as system])
  #_(:gen-class))

(defn -main
  "Run the client-system as a stand-alone until it signals completion"
  [& args]
  (let [pre-universe (system/init)]
    (let [universe (component/start pre-universe)]
      (try
        (let [done (:done universe)]
          @done)
        (finally
          (component/stop universe))))))
