(ns frereth-client.system
  (:gen-class)
  (:require [frereth-client.config :as config]
            [frereth-client.renderer :as render]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clojure.tools.nrepl.server :as nrepl]
            [cljeromq.core :as mq])
  (:use [frereth-client.utils]))

(defn init 
  "Returns a new instance of the whole application"
  []
  {;; For connecting over nrepl...I have severe doubts about just
   ;; leaving this in place and open.
   ;; Oh well. It's an obvious back door and easy enough to close.
   :controller (atom nil)
   ;; For handling renderer communication.
   :renderer-channel (atom nil)
   ;; Socket for doing the renderer communication
   :renderer-connection (atom nil)
   ;; Owns the sockets and manages communications
   :messaging-context (atom nil)
   ;; Socket to connect to the mastermind where all the "real" processing
   ;; happens
   :local-server-socket (atom nil)
   ;; Q: Do I want to track sockets connected to other servers here?
   ;; A: They should all go away when the context is destroyed...but
   ;; there are issues and memory/resource leaks that can happen if
   ;; I'm not careful. Plus trying to destroy the context while they're
   ;; lingering can lock the system.
   ;; It's an ugly question.
   })

(defn- negotiate-local-connection
  "Tell local server who we are.
FIXME: This doesn't really seem to belong here.
But FI...I'm getting the rope thrown across the gorge."
  [socket]
  (error "Get this written"))

(defn default-config
  "Have to define them somewhere.
Should really be in a config namespace that will be smarter and
make life simpler when I want to change these on the fly.
Actually, it looks like that happened and this isn't called."
  []
  (throw (RuntimeException. "Obsolete"))
  {:ports {:renderer 7840
           :server 7841}})

(defn start
  "Performs side effects to initialize the system, acquire resources,
and start it running. Returns an updated instance of the system."
  [universe]
  (letfn [(check-not [key]
            (assert (not @(key universe))))]
    (doseq [k [:controller :renderer-channel :renderer-connection 
               :controller :local-server-socket]]
      (check-not k)))

  (log/trace "Building network connections")
  (let [;; Is there any possible reason to have more than 1 thread here?
        ctx (mq/context 1)
        renderer-socket (mq/bound-socket ctx :dealer (config/render-url))
        channel (async/chan)]
    ;; Provide some feedback to the renderer ASAP
    (render/fsm channel renderer-socket)
    (let [connections
          {;; Q: Is there any real point to putting these into atoms?
           ;; A: Duh. There aren't many alternatives for changing them.
           ;; Although changing these all needs to be coordinated.
           ;; They wind up stuck inside a var in user, but...
           ;; just go with atoms for now.

           ;; Want to start responding to the renderer ASAP.
           :renderer-connection (atom renderer-socket)
           :renderer-channel (atom channel)
           :controller (atom (nrepl/start-server :port (config/control-port)))
           :messaging-context (atom ctx)
           :local-server-socket (atom (mq/connected-socket ctx :req
                                                           (config/server-url)))}]
      (log/trace "Bare minimum networking configured")
      (try
        (log/trace "Negotiating connection with local server")
        (negotiate-local-connection @(:local-server-socket connections))
        (log/trace "Negotiation succeeded")
        (catch RuntimeException ex
          ;; Surely I can manage better error reporting
          (log/trace "Negotiation failed")
          (throw (RuntimeException. ex))))
      connections)))

(defn- warn-renderer-about-shutdown [channel]
  (async/go (async/>! channel :exit-pending)))

(defn- kill-repl [universe]
  (log/trace "Closing REPL socket")
  (if-let [connection-holder (:controller universe)]
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
      (log/trace "NIL'ing out the rendering socket")
      (swap! connection-holder (fn [_] nil)))
    (log/trace "No existing connection...this seems problematic")))

(defn- kill-local-server-connection
  "Free up the socket that's connected to the 'local' server.
I suspect this thing's totally jacked up."
  [universe]
  (log/trace "Killing connection to 'local server'")
  (try
    (when-let [local-server-atom (:local-server-socket universe)]
      (when-let [local-server-port @local-server-atom]
        (log/trace "Closing local server port")
        (.close local-server-port)
        (log/trace "Resetting local server port")
        (swap! local-server-atom (fn [_] nil))))
    (log/trace "Connection to local server stopped")))

(defn stop-renderer-connection [universe]
  (log/trace "Stopping renderer connection")
  (let [c @(:renderer-channel universe)]
    ;; Yes, this is synchronous and blocking
    (async/>!! c :client-exit)
    (let [s @(:renderer-connection universe)]
      (mq/close s))))

(defn kill-messaging [universe]
    (log/trace "Closing messaging context")
    (when-let [ctx-atom (:messaging-context universe)]
      (when-let [ctx @ctx-atom]
        (mq/terminate ctx))))

(defn stop
  "Performs side-effects to shut down the system and release its
resources. Returns an updated instance of the system, ready to re-start."
  [universe]
  (when universe
    (warn-renderer-about-shutdown @(:renderer-channel universe))

    (kill-repl universe)
    (kill-local-server-connection universe)
    (stop-renderer-connection universe)
    (kill-messaging)

    (log/trace "Setting up dead replacement system")
    ;; It seems more than a little wrong to just dump the existing system to
    ;; be garbage collected. Odds are, though, that's probably exactly
    ;; what I want to happen in almost all cases.
    (init)))



