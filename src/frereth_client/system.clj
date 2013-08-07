(ns frereth-client.system
  (:gen-class)
  (:require [frereth-client.config :as config]
            [clojure.tools.logging :as log]
            [clojure.tools.nrepl.server :as nrepl]
            [org.zeromq :as mq]
            [zguide.zhelpers :as mqh])
  (:use [frereth-client.utils]))

(defn init 
  "Returns a new instance of the whole application"
  []
  {:renderer-connection (atom nil)
   :messaging-context (atom nil)
   :local-server-socket (atom nil)})

(defn- negotiate-local-connection
  "Tell local server who we are.
FIXME: This doesn't really seem to belong here.
But FI...I'm getting the rope thrown across the gorge."
  [socket]
  (error "Get this written"))

(defn start
  "Performs side effects to initialize the system, acquire resources,
and start it running. Returns an updated instance of the system."
  [universe]
  (assert (not @(:renderer-connection universe)))
  (assert (not @(:messaging-context universe)))
  (println "Building network connections")
  (let [connections
         {;; Q: Is there any real point to putting this into an atom?
          ;; A: Duh. There aren't many alternatives for changing it.
          :renderer-connection (atom (nrepl/start-server :port config/*renderer-port*))

          ;; Is there any possible reason to have more than 1 thread here?
          :messaging-context (atom (mq/context 1))}
         sockets (into connections
                       {:local-server-socket (atom (mq/socket 
                                                    @(:messaging-context connections)
                                                    (:req mq/const)))})]
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
  (when-let [local-server-connection @(:messaging-context universe)]
    (println "Local server connection: " local-server-connection)
    (try
      (when-let [local-server-atom (:messaging-socket universe)]
        (when-let [local-server-port @local-server-atom]
          (println "Closing local server port")
          (.close local-server-port)
          (println "Resetting local server port")
          (swap! local-server-atom (fn [_] nil))))
      (println "Connection to local server stopped")
      (finally
        (println "Terminating connection")
        ;; N.B. It's really important to have no more than one
        ;; connection per application.
        (.term local-server-connection)))))

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



