(ns frereth-client.communicator
  (:require [cljeromq.core :as mq]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [frereth-client.config :as config]
            [ribol.core :refer :all]
            [taoensso.timbre :as timbre])
  (:gen-class))

"Can I handle all of the networking code in here?
Well, obviously I could. Do I want to?"

(defn init []
  {:context (atom nil)
   :local-server (atom nil)
   :server-sockets (atom [])
   ;; Important implementation note:
   ;; renderer-socket is bound. Can easily have multiple renderers connected
   ;; to a single client. Multiplexing out to them becomes an interesting
   ;; opportunity.
   :renderer-socket (atom nil)
   :command-channel (atom nil)})

(defn add-server!
  "Server handshake really should establish the communication style that's
most appropriate to this server. This is really just a minimalist version."
  ([system ctx uri type]
      (let [sock (mq/connected-socket ctx type uri)]
        (swap! (:server-sockets system) (fn [current]
                                          (assoc current uri sock)))
        system))
  ([system ctx uri]
     (add-server! system ctx uri :req)))

(defn disconnect-server!
  [system uri]
  (if-let [server-atoms (:server-sockets system)]
    (do (when-let [sock (get @server-atoms uri)]
          (mq/close! sock))
        (swap! server-atoms #(dissoc % uri))
        system)
    (timbre/error "Whoa! No associated server atom for network connections?!")))

(defn translate 
  "This is where the vast majority of its life will be spent"
  [system]
  (let [control @(:command-channel system)
        renderer @(:renderer-socket system)]
    (async/go
     ;; TODO: Honestly, the local server needs priority.
     ;; Put it in its own atom and check it before anything else.
     (loop [servers (vals @(:server-sockets system))]
       ;; TODO: Poll servers and renderer. Check for anything on the control channel.
       ;; Do any translation needed.
       (raise :not-implemented)))))

(defn start [system]
  (when @(:context system)
    (throw (RuntimeException. "Trying to restart networking on a system that isn't dead")))
  (when-let [local-server @(:local-server system)]
    (throw (RuntimeException. "Trying to start networking when already connected to the local server")))
  (let [server-connections @(:server-sockets system)]
    (when (> 0 (count server-connections))
      (throw (RuntimeException. "Trying to start networking when there are live server connections"))))
  (when @(:renderer-socket system)
    (throw (RuntimeException. "Trying to start networking when we still have a live renderer connection")))

  ;; Q: Is there any possible reason to have more than 1 thread here?
  ;; A: Of course. Should probably make this configurable.
  ;; Which, realistically, means that there needs to be a way to
  ;; change this on the fly.
  ;; Q: How can I tell whether more threads might help?
  ;; Q: When will it become worth the pain to scrap the existing context and
  ;; replace all the existing sockets on the fly?
  (let [ctx (mq/context 1)]
    (reset! (:context system) ctx)

    ;; Add a socket to a local server
    ;; FIXME: In all honesty, should do the server handshake, determine the
    ;; appropriate socket type/url, then connect to that.
    ;; That's more of a remote server thing, though.
    ;; TODO: It's still pretty important.
    (comment (add-server! system ctx (config/local-server-url) :req))
    ;; Don't worry about remote servers yet
    (swap! (:local-server system) (fn [_]
                                    (mq/connected-socket ctx :req (config/local-server-url))))

    ;; Dealer probably isn't the best choice here. Or maybe it is.
    ;; I want a multi-plexer, but I really want out-going messages to
    ;; go to everyone connected. So I really need a :pub socket for that,
    ;; with something like a dealer to handle incoming messages.
    (let [renderer (mq/connected-socket ctx :dealer (config/render-url))]
      (reset! (:renderer-socket system) renderer)

      (let [command-channel (async/chan)]
        (reset! (:command-channel system) command-channel)
        (async/thread-call (fn []
                             (translate system)))))
    system))

(defn get-context [system]
  @(:context system))

(defn stop [system]
  (log/trace "Stopping Networking")
  (when-let [ctx-atom (:context system)]
    ;; FIXME: Notify renderer that we're going down
    (when-let [ctx @ctx-atom]
      (log/info "Disconnecting all servers")
      (when-let [server-sockets-atom (:server-sockets system)]
        (when-let [server-sockets @server-sockets-atom]
          (doseq [k (-> system :server-sockets deref)]
            (disconnect-server! system k))
          (reset! server-sockets-atom {})))
      (when-let [local-server-atom (:local-server system)]
        (when-let [local-server @local-server-atom]
          (mq/close! local-server)
          (reset! local-server-atom nil)))

      ;; FIXME: Close renderer socket

      (log/trace "Closing messaging context")
      (mq/terminate! ctx))))
