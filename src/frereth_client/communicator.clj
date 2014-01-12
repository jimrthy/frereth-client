(ns frereth-client.communicator
  (:require [cljeromq.core :as mq]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [frereth-client.config :as config]
            [ribol.core :refer :all]
            [taoensso.timbre :as timbre])
  (:import [org.zeromq ZMQ$Poller])
  (:gen-class))

"Can I handle all of the networking code in here?
Well, obviously I could. Do I want to?"

(set! *warn-on-reflection* true)

(defn init []
  {:context (atom nil)
   :local-server (atom nil)
   :renderer-publisher (atom nil)  ; Multicast messages to renderer(s)
   :renderer-puller (atom nil)  ; Incoming messages from renderer(s)
   :remote-servers (atom [])})

(defn add-server!
  "Server handshake really should establish the communication style that's
most appropriate to this server. This is really just a minimalist version."
  ([system ctx uri]
     (add-server! system ctx uri :req))
  ([system ctx uri type]
     (let [sock (mq/connected-socket ctx type uri)]
       (swap! (:server-sockets system) (fn [current]
                                         (assoc current uri sock)))
       system)))

(defn disconnect-server!
  [system uri]
  (if-let [server-atoms (:server-sockets system)]
    (do (when-let [sock (get @server-atoms uri)]
          (mq/close! sock))
        (swap! server-atoms #(dissoc % uri))
        system)
    (timbre/error "Whoa! No associated server atom for network connections?!")))

(defn server->view!
  "src has sent some kind of message. Forward it along to all views that might be interested.
TODO: Don't trust raw data. Absolutely must be run through some sort of filter.
Actually, this almost definitely requires a server that we know about,
so we can do whatever data massaging is appropriate.
YAGNI(yet)"
  [src dst]
  (when (mq/recv-more? src)
    ;; TODO: How does this work out in practice, with multiple frames?
    (mq/send dst (mq/recv src))
    (recur src dst)))

(defn view->servers!
  "For starters, send everything to everyone.
This is a bad approach.
I think.
If remotes is a router, this might be perfect."
  [src home remotes]
  (let [frames (mq/recv-all src)]
    (mq/send-all home frames)
    (mq/send-all remotes frames)))

(defn translate 
  "This is where the vast majority of its life will be spent.
It's very tempting to run this in a background thread, especially since
I foresee a bunch of other functionality happening in here. That just
makes life drastically more complicated. So, for now, plan on polling
it frequently."
  ([system timeout]
     (let [home (-> :local-server system deref)
           view-pub (-> :renderer-publisher system deref)
           view-pull (-> :renderer-puller system deref)
           remote (-> :remote-servers system deref)
           ;; Will be polling on home, pull, and the various remotes
           n (count remote)
           polled-sockets [home remote view-pull]
           ^ZMQ$Poller poller (mq/poller n)]
       ;; I'm almost positive that I don't want to rebuild this
       ;; every time through. But only almost.
       (mq/socket-poller-in! polled-sockets)
       (let [available-sockets (mq/poll poller)]
         (when (> available-sockets 0)
           ;; TODO: Need a smarter priortization scheme
           ;; Messages from the local server are the top priority
           ;; TODO: Need to type-hint poller.
           (when (.pollin poller 0)
             (server->view! home view-pub))
           (when (.pollin poller 1)
             (server->view! remote view-pub))
           (when (.pollin poller 2)
             (view->servers! view-pull home remote))))))
  ([system]
     (translate system 1)))

(defn start [system]
  (when @(:context system)
    (throw (RuntimeException. "Trying to restart networking on a system that isn't dead")))
  (when-let [local-server @(:local-server system)]
    (throw (RuntimeException. "Trying to start networking when already connected to the local server")))

  (let [server-connections @(:remote-servers system)]
    (when (> 0 (count server-connections))
      (throw (RuntimeException. "Trying to start networking when there are live server connections"))))

  ;; Q: Is there any possible reason to have more than 1 thread here?
  ;; A: Of course.
  ;; That's really the entire point to this: shuffling messages between renderers and servers.
  ;; Well, at least at first. Step Two will be to make this actually interpret the data
  ;; that's going back and forth. Renderers shouldn't have the vaguest idea what they're
  ;; rendering.
  ;; Should probably make this configurable.
  ;; Which, realistically, means that there needs to be a way to
  ;; change this on the fly.
  ;; Q: How can I tell whether more threads might help?
  ;; Q: When will it become worth the pain to scrap the existing context and
  ;; replace all the existing sockets on the fly?
  (let [ctx (mq/context (config/messaging-threads))]
    (reset! (:context system) ctx)

    ;; Don't worry about remote servers yet.
    ;; Still. I almost definitely do *not* want a REQ socket.
    (swap! (:local-server system)
           (fn [_]
             (mq/connected-socket ctx :req (config/local-server-url))))

    (swap! (:renderer-publisher system)
           (fn [_]
             (mq/bound-socket ctx :pub (config/render-url-from-server))))
    (swap! (:renderer-puller system)
           (fn [_]
             (mq/bound-socket ctx :pull (config/render-url-from-renderer))))

    ;; Go ahead and create a router socket...which is going to be doing lots of
    ;; connects. How will this work out in practice?
    (swap! (:remote-servers system)
           (fn [_]
             (mq/socket ctx :router)))

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

      ;; FIXME: Close renderer sockets!

      (log/trace "Closing messaging context")
      (mq/terminate! ctx))))
