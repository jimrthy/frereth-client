(ns frereth-client.core
  (:gen-class)
  (:require [qbits.jilch.mq :as mq]
            [frereth-client.config :as config]
            [clojure.tools.nrepl.server :as nrepl]))

(defonce ^:dynamic *renderer-connection* (nrepl/start-server config/*renderer-port*))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))
