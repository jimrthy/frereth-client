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
   :renderer-router (atom nil)  ; Renderers will connect here to send us messages
   :renderer-feedback (atom {}) ; Send feedback back to the renderers
   :remote-servers (atom {})})

(defn add-server!
  "Server handshake really should establish the communication style that's
most appropriate to this server. This is really just a minimalist version."
  ([system ctx uri]
     (add-server! system ctx uri :req))
  ([system ctx uri type]
     (let [sock (mq/connected-socket ctx type uri)]
       ;; Aside from the fact that the name is borked, this entire
       ;; approach fails.
       ;; Each renderer needs its own server connection.
       ;; For that matter, if a renderer has multiple tabs, that will
       ;; mean one server connection per tab.
       ;; The connection to the local server seems like it should be
       ;; special, but I'm starting to doubt the wisdom of that approach.
       (swap! (:remote-servers system) (fn [current]
                                         (assoc current uri sock)))
       system)))

(defn disconnect-server!
  [system uri]
  (if-let [server-atoms (:remote-servers system)]
    (do (when-let [sock (get @server-atoms uri)]
          (mq/close! sock))
        ;; This is totally wrong.
        ;; This needs to be keyed by the ID of the renderer connection.
        (swap! server-atoms #(dissoc % uri))
        system)
    (timbre/error "Whoa! No associated server atom for network connections?!")))

(defn server->view!
  "server has sent some kind of message to the view.
It's coming from the socket in the src parameter.
This doesn't seem right.
Forward it along to all views that might be interested.
TODO: Don't trust raw data. Absolutely must be run through some sort of filter.
Actually, this almost definitely requires a server that we know about,
so we can do whatever data massaging is appropriate.
YAGNI(yet)"
  [src dst]
  (let [frame (mq/recv src)]
    (if (mq/recv-more? src)
      (do
        (mq/send dst frame :send-more)
        (recur src dst))
      (mq/send frame))))

(defn view->servers!
  "For starters, send everything to everyone.
This is a bad approach.

src is a router.
Should be able to pick its associated server based on the ID that should be the first frame of the message.
Whether to send to home or not seems like an interesting question."
  [src home remotes]
  (let [frames (mq/recv-all src)]
    (mq/send-all home frames)
    (mq/send-all remotes frames)))

(defn renderer-hand-shake
  "Pretty much copy/pasted from a REPL session to try to get a starting point"
  []
  (throw (RuntimeException. "Do that")))

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

    (swap! (:renderer-router system)
           (fn [_]
             (mq/bound-socket ctx :router (config/render-url-from-server))))

    (let [msg "Need to set up a hand-shaking thread that sets up renderer-feedback sockets
when a renderer connects to renderer-router.
And ties those to local-server, at least for starters.
I think I want to allow them to restore saved connections from within the renderer, but that
seems like a chicken/egg problem.
I want to start something that triggers all the windows that were active in the last session
to reload, pretty much the same way Chrome restores my active browser session when I restart it.

This should be optional, of course. Since most people probably won't want to have 5 bazillion
tabs open for something this resource-intensive. At least at first.

Anyway. Each of those 'sessions' probably involves multiple (1 in each tab) 'sessions' that are
really connections to remote servers.

And, realistically, at least some of those could be shared to provide multiple views. Although
that seems questionable.

FIXME: Need to implement something along those lines."]
      (throw (RuntimeException. msg)))

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
