(ns frereth-client.system
  (:gen-class)
  (:require [frereth-client.config :as config]
            [clojure.tools.nrepl.server :as nrepl]
            ;; FIXME: Don't use this
            [qbits.jilch.mq :as mq]))

(defn init 
  "Returns a new instance of the whole application"
  []
  ;; Honestly, this seems more than a little stupid
  {:renderer-connection (atom nil)})

(defn start
  "Performs side effects to initialize the system, acquire resources,
and start it running. Returns an updated instance of the system."
  [universe]
  (assert (not (:renderer-connection universe)))
  {;; Is there any real point to putting this into an atom?
   :renderer-connection (atom (nrepl/start-server config/*renderer-port*))})

(defn- kill-renderer-listener [universe]
  (when-let [connection-holder (:renderer-connection universe)]
    (io! (nrepl/stop-server @connection-holder))
    (swap! connection-holder (fn [_] nil))))

(defn stop
  "Performs side-effects to shut down the system and release its
resources. Returns an updated instance of the system"
  [universe]
  (kill-renderer-listener universe))



