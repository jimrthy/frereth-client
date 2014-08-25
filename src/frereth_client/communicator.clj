(ns frereth-client.communicator
  (:require [cljeromq.constants :as mqk]
            [cljeromq.core :as mq]
            [clojure.core.async :as async]
            ;; TODO: Honestly, this is bad. It's an implementation
            ;; detail that I simply do not want to know about
            [clojure.core.async.impl.channels :refer (ManyToManyChannel)]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [frereth-client.config :as config]
            [plumbing.core :refer :all]
            [ribol.core :refer (raise)]
            [schema.core :as s]
            [taoensso.timbre :as timbre])
  (:gen-class))

;;;; Can I handle all of the networking code in here?
;;;; Well, obviously I could. Do I want to?

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Lifecycle

(defn build-local-server-connection
  "For anything resembling an external connection,
encryption and some sort of auth needs to be mandatory.

If you can't trust communications inside the same process...
you have bigger issues."
  [ctx url]
  ;; This needs to be an async both ways.
  ;; The other endpoint really should be hard-coded,
  ;; which makes it very tempting for this to be a
  ;; router.
  ;; Or maybe a dealer.
  ;; Q: Is there an advantage to one of those instead?
  (-> (mq/socket! ctx (-> mqk/const :socket-type :pair))
      (mq/connect! url)))

(defn destroy-connection!
  [sock url]
  (mq/disconnect! sock url)
  (mq/close! sock))

(defn build-renderer-binding!
  ([ctx url]
     ;; This seems like it should really be a :pair socket instead.
     ;; Whatever. I have to start somewhere.
     ;; And this at least introduces the idea that I'm really
     ;; planning for the client to run on a machine that's as
     ;; logically separate from the renderer as I can manage.
     (let [sock (-> mq/socket! ctx (-> mqk/const :socket-type :router))]
       (mq/bind! sock url)))
  ([ctx]
     ;; This approach seems like a horrible failure when
     ;; it becomes time to shut everything down.
     ;; Then again, you can't unbind inproc sockets anyway
     ;; (recognized bug that should be fixed in 4.0.5)
     (build-renderer-binding! ctx "inproc://local-renderer")))

(defn destroy-renderer-binding
  [sock url]
  (mq/unbind! sock url)
  (mq/close sock))

(defrecord Communicator [command-channel
                         context
                         external-server-sockets
                         local-server
                         local-server-url
                         renderer-socket]
    component/Lifecycle

  (start
    [this]
    (let [ctx (mq/context)]
      (into this {:context ctx
                  :external-server-sockets (atom {})
                  :local-server (build-local-server-connection ctx local-server-url)
                  :renderer-socket (build-renderer-binding ctx)})))

  (stop
    [this]
    ;; Q: Do these need to be disconnected first?
    ;; They can't be bound, since the other side can't possibly know about them
    (map mq/close! @external-server-sockets)
    (destroy-renderer-binding renderer-socket)
    (destroy-connection! local-server local-server-url)
    (mq/terminate! context)
    (into this {:context nil
                :external-server-sockets nil
                :local-server nil
                :renderer-socket nil})))

(defn new-communicator
  [params]
  ;; I want to use something like s/defn or defnk
  ;; to be explicit about what I'm getting
  ;; Q: How do I make that work?
  #_[command-channel :- ManyToManyChannel
   local-server :- s/Str]
  (let [real-params (select-keys params [command-channel
                                         local-server-url])]
    (map->Communicator real-params)))

(comment (defn start [system]
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
             system)))

(comment (defn stop [system]
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
               (mq/terminate! ctx)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

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

(defn get-context [system]
  @(:context system))
