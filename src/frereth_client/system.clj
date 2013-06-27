(ns frereth-client.system
  (:gen-class)
  (:require [frereth-client.config :as config]
            [clojure.tools.logging :as log]
            [clojure.tools.nrepl.server :as nrepl]
            ;; Can't use this. License isn't
            ;; compatible.
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

(defn- negotiate-local-connection
  "Tell local server who we are."
  [socket]
  (error "Get this written"))

(defn start
  "Performs side effects to initialize the system, acquire resources,
and start it running. Returns an updated instance of the system."
  [universe]
  (assert (not @(:renderer-connection universe)))
  (assert (not @(:local-server-context universe)))
  (println "Building network connections")
  (let [connections
         {;; Q: Is there any real point to putting this into an atom?
          ;; A: Duh. There aren't many alternatives for changing it.
          :renderer-connection (atom (nrepl/start-server :port config/*renderer-port*))

          ;; Is there any reason to have more than 1 thread dedicated
          ;; to connecting to the local server?
          :local-server-context (atom (mq/context 1))}
         sockets (into connections
                       {:local-server-socket (atom (mq/socket 
                                                    @(:local-server-context connections)
                                                    mq/req))})]
        (log/trace "Bare minimum networking configured")
        (let [port config/*server-port*]
          (println "Connectiong to server")
          (mq/connect @(:local-server-socket sockets)
                      (str "tcp://localhost:" port))
          (try
            (println "Negotiating connection with local server")
            (negotiate-local-connection @(:local-server-socket sockets))
            (println "Negotiation succeeded")
            (catch RuntimeException ex
              ;; Surely I can manage better error reporting
              (println "Negotiation failed")
              ;;(throw (RuntimeException. ex))
              )))
        sockets))

(defn- kill-renderer-listener [universe]
  (if-let [connection-holder (:renderer-connection universe)]
    (do
      (try
        (log/trace "Getting ready to stop nrepl")
        (when-let [connection @connection-holder]
          (io! (nrepl/stop-server connection)))
        (log/trace "NREPL stopped")
        (catch RuntimeException ex
          ;; Q: Do I actually care about this?
          ;; A: It seems at least mildly important, especially
          ;; over the long haul. Like, e.g. unbinding socket
          ;; connections
          ;; Q: Why aren't I catching this?
          ;; A: I am. I'm just getting further errors later.
          (log/warn ex "\nTrying to stop the nrepl server")))
      (println "NIL'ing out the NREPL server")
      (swap! connection-holder (fn [_] nil)))
    (println "No existing connection...this seems problematic")))

(defn- kill-local-server-connection
  "Free up the socket that's connected to the 'local' server"
  [universe]
  (println "Killing connection to 'local server'")
  (when-let [local-server-connection @(:local-server-context universe)]
    (try
      (when-let [local-server-port @(:local-server-socket universe)]
        (.close local-server-port)
        (swap! local-server-port (fn [_] nil) ))
      (finally (.term local-server-connection)))))

(defn stop
  "Performs side-effects to shut down the system and release its
resources. Returns an updated instance of the system, ready to re-start."
  [universe]
  (when universe
    (println "Closing connection with renderer")
    (kill-renderer-listener universe)
    (println "Closing connection to main server")
    (kill-local-server-connection universe)
    (println "Setting up replacement system")
    ;; It seems more than a little wrong to just dump the existing system to
    ;; be garbage collected. Odds are, though, that's probably exactly
    ;; what I want to happen in almost all cases.
    (init)))


