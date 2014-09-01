(ns frereth-client.communicator
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [frereth-client.config :as config]
            [plumbing.core :refer :all]  ; ???
            [ribol.core :refer (raise)]
            [schema.core :as s]
            [taoensso.timbre :as timbre]
            [zeromq.zmq :as zmq])
  (:gen-class))

;;;; Can I handle all of the networking code in here?
;;;; Well, obviously I could. Do I want to?

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(s/defrecord ZmqContext [context thread-count :- s/Int]
  component/Lifecycle
  (start 
   [this]
   (let [ctx (zmq/context thread-count)]
     (assoc this :context ctx)))
  (stop
   [this]
   (when context
     (zmq/close context))
   (assoc this :context nil)))

(s/defrecord URI [protocol :- s/Str
                  address :- s/Str
                  port :- s/Int]
  component/Lifecycle
  (start [this] this)
  (stop [this] this))
(declare build-url)

;; Q: How do I want to handle the actual server connections?
(s/defrecord RendererSocket [context :- ZmqContext
                             renderers
                             socket
                             url :- URI]
  component/Lifecycle
  (start
   [this]
   (let [sock  (zmq/socket context :router)
         url (build-url url)]
     (zmq/bind sock url)
     ;; Q: What do I want to do about the renderers?
     (assoc this :socket sock)))
  (stop
   [this]
   (zmq/unbind socket (build-url url))
   (zmq/close socket)
   (reset! renderers {})
   (assoc this :socket nil)))

(s/defrecord ServerSocket [context
                           socket
                           url :- URI]
  component/Lifecycle
  (start
   [this]
   (let [sock (zmq/socket context :dealer)]
     (zmq/connect sock (build-url url))
     (assoc this :socket sock)))

  (stop
   [this]
   (zmq/set-linger socket 0)
   (zmq/disconnect socket (build-url url))
   (zmq/close socket)
   (assoc this :socket nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(s/defn build-url :- s/Str
  [url :- URI]
  (let [port (:port url)]
    (str (:protocol url) "://"
         (:address url)
         ;; port is meaningless for inproc
         (when port
           (str ":" port)))))

(comment (defn build-local-server-connection
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
               (mq/connect! url))))

(comment (defn build-renderer-binding!
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
              (build-renderer-binding! ctx "inproc://local-renderer"))))

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

(defn new-renderer
  [params]
  (let [cfg (select-keys params [:config])
        url {:protocol "tcp"
             :address "*"
             :port (:renderer-port cfg)}]

    (map->RendererSocket {:config cfg
                          :context nil
                          :renderers (atom {})
                          :socket nil})))

(s/defn default-server-url :- URI
  ([protocol address port]
     (strict-map->URI {:protocol protocol
                         :address address
                         :port port}))
  ([]
     (default-server-url "inproc" "local" nil)))

(defn new-server
  []
  (->ServerSocket))
