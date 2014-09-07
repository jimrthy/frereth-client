(ns frereth-client.communicator
  (:require [clojure.core.async :as async]
            #_[clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [frereth-client.config :as config]
            [plumbing.core :refer :all]  ; ???
            [ribol.core :refer (raise)]
            [schema.core :as s]
            [schema.macros :as sm]
            [taoensso.timbre :as log]
            [zeromq.zmq :as zmq])
  (:import [org.zeromq ZMQException ZMQ$Poller])
  (:gen-class))

;;;; Can I handle all of the networking code in here?
;;;; Well, obviously I could. Do I want to?

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(sm/defrecord ZmqContext [context thread-count :- s/Int]
  component/Lifecycle
  (start 
   [this]
   (let [msg (str "Creating a 0mq Context with " thread-count " (that's a "
                  (class thread-count) ") threads")]
     (println msg))
   (let [ctx (zmq/context thread-count)]
     (assoc this :context ctx)))
  (stop
   [this]
   (when context
     (zmq/close context))
   (assoc this :context nil)))

(sm/defrecord URI [protocol :- s/Str
                   address :- s/Str
                   port :- s/Int]
  component/Lifecycle
  (start [this] this)
  (stop [this] this))

(declare build-url)
;; Q: How do I want to handle the actual server connections?
(sm/defrecord RendererSocket [context :- ZmqContext
                              renderers
                              socket
                              renderer-url :- URI]
  component/Lifecycle
  (start
   [this]
   (let [sock  (zmq/socket (:context context) :router)
         actual-url (build-url renderer-url)]
     (try
       (zmq/bind sock actual-url)
       (catch ZMQException ex
         (raise {:zmq-failure ex
                 :binding renderer-url
                 :details actual-url})))
     ;; Q: What do I want to do about the renderers?
     (assoc this :socket sock)))
  (stop
   [this]
   (when socket
     (log/info "Trying to unbind: " socket "(a " (class socket) ") from " renderer-url)
     (if (= "inproc" (:protocol renderer-url))
       (try
         ;; TODO: Don't bother trying to unbind an inproc socket.
         ;; Which really means checking the socket options to see
         ;; what we've got.
         ;; Or being smarter about tracking it.
         ;; Then again...the easy answer is just to check whether
         ;; we're using "inproc" as the protocol
         (zmq/unbind socket (build-url renderer-url))
         (catch ZMQException ex
           (log/info ex "This usually isn't a real problem")))
       (log/debug "Can't unbind an inproc socket"))
     (zmq/close socket))
   (reset! renderers {})
   (assoc this :socket nil)))

(sm/defrecord ServerSocket [context
                            socket
                            url :- URI]
  component/Lifecycle
  (start
   [this]
   (let [sock (zmq/socket (:context context) :dealer)]
     (zmq/connect sock (build-url url))
     (assoc this :socket sock)))

  (stop
   [this]
   (when socket
     (zmq/set-linger socket 0)
     (zmq/disconnect socket (build-url url))
     (zmq/close socket))
   (assoc this :socket nil)))

;;; Q: What makes sense to do here?
;;; Do I add a server and have clients act as many-to-many conduits?
;;; Or just limit each client to a single server at once?
;;; That question's pretty vital
(comment (defn add-server!
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
       system))))

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

      ;; FIXME: Close renderer sockets!

      (log/trace "Closing messaging context")
      (mq/terminate! ctx)))))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

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

(sm/defn build-url :- s/Str
  [url :- URI]
  (let [port (:port url)]
    (str (:protocol url) "://"
         (:address url)
         ;; port is meaningless for inproc
         (when port
           (str ":" port)))))

(comment (defrecord Communicator [command-channel
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
                         :renderer-socket nil}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn new-context
  [thread-count]
  (map->ZmqContext {:context nil
                    :thread-count thread-count}))

(defn new-renderer-url
  [{:keys [renderer-protocol renderer-address renderer-port] :as cfg}]
  (println "Setting up the renderer URL based on:\n" cfg)
  (strict-map->URI {:protocol renderer-protocol
                    :address renderer-address
                    :port renderer-port}))

(defn new-renderer-handler
  [params]
  (let [cfg (select-keys params [:config])]
    (map->RendererSocket {:config cfg
                          :context nil
                          :renderers (atom {})
                          :socket nil})))

(sm/defn default-server-url :- URI
  ([protocol address port]
     (strict-map->URI {:protocol protocol
                       :address address
                       :port port}))
  ([]
     (default-server-url "inproc" "local" nil)))


(defn new-server
  []
  (map->ServerSocket {}))
