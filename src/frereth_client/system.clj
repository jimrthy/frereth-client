(ns frereth-client.system
  (:require [frereth-client.communicator :as comm]
            [frereth-client.config :as config]
            [frereth-client.renderer :as render]
            [frereth-client.server :as srvr]
            [clojure.core.async :as async]
            [clojure.tools.nrepl.server :as nrepl]
            [taoensso.timbre :as log])
  (:gen-class))

(defn init 
  "Returns a new instance of the whole application"
  []
  (log/info "Initializing Frereth Client")
  (let [result
        {;; For connecting over nrepl...I have severe doubts about just
         ;; leaving this in place and open.
         ;; Oh well. It's an obvious back door and easy enough to close.
         :controller (atom nil)
                   
         ;; For handling communication back and forth with the renderer
         :renderer-channel (atom nil)

         ;; Owns the sockets and manages communications
         ;; TODO: This now represents an atom that contains other
         ;; atoms. Seems like a bad idea.
         :messaging (atom (comm/init))
         }]
    (log/info "Client Initialized")
    result))

;;; This kind of convoluted start/stop has been recommended as a symptom
;;; that I need to be using prismatic/plumbing.
;;; TODO: Look into that.
         
(defn start
  "Performs side effects to initialize the system, acquire resources,
and start it running. Returns an updated instance of the system."
  [universe]
  (log/info "Starting Frereth Client")
  (letfn [(check-not [key]
            (log/trace "Verifying that" key "is nil")
            (assert (nil? @(key universe))))]
    (doseq [k [:controller :renderer-channel]]
      (check-not k)))

  (log/info "Building network connections")
  (swap! (:messaging universe) comm/start)
  
  (let [ctx (comm/get-context @(:messaging universe))
        ;; TODO: It seems to make a lot more sense to build the async channel to the
        ;; renderer in communicator also.
        ;; Or maybe the renderer.
        ;; Wherever. It just does not belong in here...this namespace should
        ;; really just be glue code.
        renderer-channel (async/chan)]
    ;; Provide some feedback to the renderer ASAP
    (render/build-proxy ctx renderer-channel)
    (let [connections
          {;; Q: Is there any real point to putting these into atoms?
           ;; A: Duh. There aren't many alternatives for changing them.
           ;; Although changing these all needs to be coordinated.
           ;; They wind up stuck inside a var in user, but...
           ;; just go with atoms for now.

           ;; Want to start responding to the renderer ASAP.
           :renderer-channel (atom renderer-channel)

           ;; The REPL handled by controller might or might not be an
           ;; external connection. So it might (or might not) make a lot
           ;; more sense to build it in communicator.
           :controller (atom (nrepl/start-server :port (config/control-port)))}]
      (log/trace "Bare minimum networking configured")
      (try
        ;; TODO: This desperately needs to move into the server namespace.
        (log/trace "Negotiating connection with local server")
        (if-let [initial-screen
                 (if-let [local-server-sock-atom (:local-server-socket connections)]
                   (srvr/negotiate-local-connection @local-server-sock-atom)
                   (log/error "No connection to local server"))]
          (do
            (log/trace "Negotiation succeeded")
            ;; FIXME: This next piece is a bigger deal than it looks.
            ;; The bulk of what the client should do involves
            ;; transferring data back and forth between the renderer
            ;; and the server(s).
            ;; Along with things like translations for different protocols,
            ;; scripting, and pretty much everything that's actually interesting
            ;; on the client (except for the obvious UI parts).

            ;; Really need to set that up to start happening here.
            ;; It's almost a matter of setting up the sockets to just forward
            ;; information back and forth.
            ;; FIXME: Make that happen.

            ;; TODO: Add a timeout handler.
            (async/>!! renderer-channel initial-screen))
          (log/warn "Negotiation failed"))
        (catch RuntimeException ex
          ;; Surely I can manage better error reporting
          (log/trace "Negotiation failed")
          (throw (RuntimeException. ex))))
      (log/info "Frereth Client Started")
      connections)))

(defn- warn-renderer-about-shutdown [channel]
  (async/go (async/>! channel :exit-pending)))

(defn- kill-repl [universe]
  (log/trace "Closing REPL socket")
  (if-let [connection-holder (:controller universe)]
    (do
      #_(try
          (log/trace "Getting ready to stop nrepl")
          (when-let [connection @connection-holder]
            (io! (nrepl/stop-server connection)))
          (log/trace "NREPL stopped")
          (catch RuntimeException ex
            ;; Q: Do I actually care about this?
            ;; A: It seems at least mildly important, especially
            ;; over the long haul. Like, e.g. unbinding socket
            ;; connections
            (log/warn ex "\nTrying to stop the nrepl server")))
      (log/trace "NIL'ing out the rendering socket")
      (swap! connection-holder (fn [_] nil)))
    (log/trace "No existing connection...this seems problematic")))

(defn stop-renderer-connection [universe]
  (log/trace "Stopping renderer connection")
  (let [c @(:renderer-channel universe)]
    ;; Yes, this is synchronous and blocking
    (async/>!! c :client-exit)

    ;; TODO: So...does the other side actually close the channel?
    (throw (RuntimeException. "Make that happen"))
    ))

(defn kill-external-messaging
  "Stop all the pieces that communicate with the outside world"
  [universe]
  (swap! (:messaging universe) (comm/stop (:messaging universe))))

(defn stop
  "Performs side-effects to shut down the system and release its
resources. Returns an updated instance of the system, ready to re-start."
  [universe]
  (log/info "Stopping Frereth Client")
  (when universe
    (warn-renderer-about-shutdown @(:renderer-channel universe))

    (kill-repl universe)
    (stop-renderer-connection universe)
    (kill-external-messaging)

    (log/trace "Setting up dead replacement system")
    ;; It seems more than a little wrong to just dump the existing system to
    ;; be garbage collected. Odds are, though, that's probably exactly
    ;; what I want to happen in almost all cases.
    (let [result (init)]
      (log/info "Frereth Client Stopped")
      result)))

