(ns frereth-client.system
  (:gen-class)
  (:require [frereth-client.config :as config]
            [clojure.tools.nrepl.server :as nrepl]
            ;; FIXME: Don't use this
            ;[qbits.jilch.mq :as mq]
            ;; This sort of thing seems like it should be
            ;; part of the language binding.
            [zguide.zhelpers :as mq])
  (:use [frereth-client.utils]))

(defn init 
  "Returns a new instance of the whole application"
  []
  {:renderer-connection (atom nil)
   :local-server-context (atom nil)})

(defn start
  "Performs side effects to initialize the system, acquire resources,
and start it running. Returns an updated instance of the system."
  [universe]
  (assert (not (:renderer-connection universe)))
  (assert (not (:local-server-context universe)))
  (let* [connections
         {;; Is there any real point to putting this into an atom?
          :renderer-connection (atom (nrepl/start-server config/*renderer-port*))
          ;; Is there any reason to have more than 1 thread dedicated
          ;; to connecting to the local server?
          :local-server-context (atom (mq/context 1))}
         sockets (into connections
                       {:local-server-socket (atom (mq/socket 
                                                    (:local-server-context connections)
                                                    (mq/req)))})]
        (let [port config/*server-port*]
          (.connect (mq/connect (:local-server-socket sockets)
                                (str "tcp://localhost:" port)))
          (try
            (negotiate-connection sockets)
            (catch RuntimeException ex
              ;; Surely I can manage better error reporting
              (throw (RuntimeException. ex)))))))

(defn- kill-renderer-listener [universe]
  (when-let [connection-holder (:renderer-connection universe)]
    (io! (nrepl/stop-server @connection-holder))
    (swap! connection-holder (fn [_] nil))))

(defn- kill-local-server-connection
  "Free up the socket that's connected to the 'local' server"
  [universe]
  (if-let [local-server-connection (:local-server-context universe)]
    (try
      (if-let [local-server-port (:local-server-socket universe)]
        (.close local-server-port))
      (finally (.term local-server-connetion)))))

(defn stop
  "Performs side-effects to shut down the system and release its
resources. Returns an updated instance of the system"
  [universe]
  (kill-renderer-listener universe)
  (kill-local-server-connection universe)
  (init))



