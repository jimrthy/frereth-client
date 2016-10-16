(ns dev
  (:require [cljeromq.core :as mq]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.pprint :refer (pprint)]
            [clojure.repl :refer :all]
            [clojure.spec :as s]
            [clojure.test :as test]
            [clojure.tools.namespace.repl :refer (refresh refresh-all)]
            ;; Q: Do I want something like this or spyscope?
            ;;[clojure.tools.trace :as trace]
            [com.frereth.client.connection-manager :as con-man]
            [com.frereth.client.system :as system]
            [com.frereth.common.util :as util]
            [com.stuartsierra.component :as component]
            [component-dsl.system :as cpt-dsl]))

(def +frereth-component+
  "Just to help me track which REPL is which"
  'client)

(def system nil)

(defn init
  "Constructs the current development system."
  []
  (alter-var-root #'system
                  (fn [_]
                    (let [nested-client-creator 'system/init
                          struct #:component-dsl.system {:structure {:client 'com.frereth.client.system/init}}
                          options {}]
                      (cpt-dsl/build struct options)))))

(defn start
  "Starts the current development system."
  []
  (alter-var-root #'system component/start))

(defn stop
  "Shuts down and destroys the current development system."
  []
  (alter-var-root #'system
    (fn [s] (when s (component/stop s)))))

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
  (refresh :after 'dev/go))
