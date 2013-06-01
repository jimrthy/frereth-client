(ns frereth-client.core
  (:gen-class)
  (:require [qbits.jilch.mq :as mq]
            [frereth-client.config :as config]
            [clojure.tools.nrepl.server :as nrepl]))

;; Realistically, this belongs in an atom.
(defonce ^:dynamic *renderer-connection* (atom (nrepl/start-server config/*renderer-port*)))

(defn- kill-renderer-listener
  "Stop the nrepl listener that's connected to the renderer."
  ([]
     (kill-renderer-listener *renderer-connection*))
  ([connection]
     (nrepl/stop-server @connection)
     (swap! @connection (fn [_] nil))))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (try
    (println "Hello, World!")
    (finally
      (kill-renderer-listener))))
