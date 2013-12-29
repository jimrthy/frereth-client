(ns user
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer (pprint)]
            [clojure.repl :refer :all]
            ;; Really? No midje?
            [clojure.test :as test]
            [clojure.tools.namespace.repl :refer (refresh refresh-all)]
            [clojure.tools.trace :as trace]
            [frereth-client.system :as system]))

(def system nil)

(defn init
  "Constructs the current development system."
  []
  (alter-var-root #'system
    (constantly (system/init))))

(defn start
  "Starts the current development system."
  []
  (alter-var-root #'system system/start))

(defn stop
  "Shuts down and destroys the current development system."
  []
  (alter-var-root #'system
    (fn [s] (when s (system/stop s)))))

(defn go
  "Initializes the current development system and starts it running."
  []
  (println "Initializing system")
  (init)
  (println "Restarting system")
  (start))

(defn reset []
  (println "Stopping")
  (stop)
  (println "Refreshing namespaces")
  (refresh :after 'user/go))
