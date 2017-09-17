(ns com.frereth.client.core
  (:require [com.frereth.client.system :as system]
            [integrant.core :as ig]))

(defn -main
  "Run the client-system as a stand-alone until it signals completion"
  [& args]
  (let [pre-universe (system/init {})]
    (let [universe (ig/init pre-universe)]
      (try
        (let [done (::system/done universe)]
          @done)
        (finally
          (ig/halt! universe))))))
