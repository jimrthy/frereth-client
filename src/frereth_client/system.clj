(ns frereth-client.system
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [clojure.tools.nrepl.server :as nrepl]
            [zeromq.zmq :as mq]
            [zguide.zhelpers :as mqh])
  (:use [frereth-client.utils]))

(defn init 
  "Returns a new instance of the whole application"
  []
  {:renderer (atom nil)  ; Rendering clients communicate over this socket
   :ctx (atom nil)       ; 0mq message context
   :server (atom nil)})  ; The "home world" server

(defn- negotiate-local-connection
  "Tell local server who we are.
FIXME: This doesn't really seem to belong here.
But FI...I'm getting the rope thrown across the gorge."
  [socket]
  (error "Get this written"))

(defn default-config
  "Have to define them somewhere"
  []
  {:ports {:renderer 7840
           :server 7841}})

(defn start
  "Performs side effects to initialize the system, acquire resources,
and start it running. Returns an updated instance of the system."
  [universe]
  (assert (not @(:renderer universe)))
  (assert (not @(:ctx universe)))
  (assert (not @(:server universe)))

  (let [defaults (default-config)
        default-ports (:ports defaults)]
    (println "Building network connections")
    (let [connections
          {;; Is there any possible reason to have more than 1 thread here?
           :ctx (atom (mq/context 1))}
          sockets (into connections
                        {:server (atom (mq/socket 
                                        @(:ctx connections)
                                        :req))
                         :renderer (atom (mq/socket @(:ctx connections) :dealer))})]
      (log/trace "Bare minimum networking configured")
      (let [server-port (:server default-ports)]
        (println "Connectiong to server")
        (mq/connect @(:server sockets)
                    (str "tcp://localhost:" server-port))
        (try
          (println "Negotiating connection with local server")
          (negotiate-local-connection @(:server sockets))
          (println "Negotiation succeeded")
          (catch RuntimeException ex
            ;; Surely I can manage better error reporting
            (println "Negotiation failed")
            ;;(throw (RuntimeException. ex))
            )))
      sockets)))

(defn- kill-renderer-listener [universe]
  (if-let [connection-holder (:renderer (:sockets universe))]
    (do
      (#_ (try
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
              (log/warn ex "\nTrying to stop the nrepl server"))))
      (mq/close @connection-holder)
      (println "NIL'ing out the rendering socket")
      (swap! connection-holder (fn [_] nil)))
    (println "No existing connection...this seems problematic")))

(defn- kill-local-server-connection
  "Free up the socket that's connected to the 'local' server.
I suspect this thing's totally jacked up."
  [universe]
  (println "Killing connection to 'local server'")
  (when-let [message-context @(:ctx universe)]
    (println "Local server connection: " message-context)
    (try
      (when-let [local-server-atom (:server universe)]
        (when-let [local-server-port @local-server-atom]
          (println "Closing local server port")
          (mq/close local-server-port)
          (println "Resetting local server port")
          (swap! local-server-atom (fn [_] nil))))
      (println "Connection to local server stopped")
      (finally
        (println "Terminating connection")
        ;; N.B. It's really important to have no more than one
        ;; connection per application.
        ;; FIXME: This really should get its own function
        ;; And the destroy function seems to be missing from the language binding
        ;; Am I really supposed to just let it get GC'd?
        (.term message-context)
        (swap! (:ctx universe) nil)))))

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



