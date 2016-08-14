(ns com.frereth.client.communicator
  (:require [cljeromq.common :as mq-cmn]
            [cljeromq.core :as mq]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [com.frereth.client.config :as config]
            [com.stuartsierra.component :as component]
            [plumbing.core :as plumbing]
            [hara.event :refer (raise)]
            [schema.core :as s]
            [taoensso.timbre :as timbre])
  (:import [clojure.lang ExceptionInfo]))

;;;; Can I handle all of the networking code in here?
;;;; Well, obviously I could. Do I want to?

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(s/defrecord ZmqContext [context :- mq-cmn/Context
                         thread-count :- s/Int]
  component/Lifecycle
  (start
   [this]
   (let [msg (str "Creating a 0mq Context with " thread-count " (that's a "
                  (class thread-count) ") threads")]
     (println msg))
   (let [ctx (mq/context thread-count)]
     (assoc this :context ctx)))
  (stop
   [this]
   (when context
     (comment (mq/terminate! context)))
   (assoc this :context nil)))

;;; TODO: URI should really just be a hash-map
;;; It also needs a different name, to distinguish it
;;; from the similar thing in java.util.
;;; Or wherever it lives.
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
                             socket :- mq-cmn/Socket
                             renderer-url :- URI]
  component/Lifecycle
  (start
   [this]
   (let [sock  (mq/socket! (:context context) :router)
         actual-url (build-url renderer-url)]
     (try
       ;; TODO: Make this another option. It's really only
       ;; for debugging.
       (mq/set-router-mandatory! sock true)
       (mq/bind! sock actual-url)
       (catch ExceptionInfo ex
         (raise {:cause ex
                 :binding renderer-url
                 :details actual-url})))
     ;; Q: What do I want to do about the renderers?
     (assoc this :socket sock)))
  (stop
   [this]
   (when socket
     (log/info "Trying to unbind: " socket "(a " (class socket) ") from " renderer-url)
     (if (not= "inproc" (:protocol renderer-url))
       (try
         ;; TODO: Don't bother trying to unbind an inproc socket.
         ;; Which really means checking the socket options to see
         ;; what we've got.
         ;; Or being smarter about tracking it.
         ;; Then again...the easy answer is just to check whether
         ;; we're using "inproc" as the protocol
         (mq/unbind! socket (build-url renderer-url))
         (catch ExceptionInfo ex
           (log/info ex "This usually isn't a real problem")))
       (log/debug "Can't unbind an inproc socket"))
     (mq/close! socket))
   (reset! renderers {})
   (assoc this :socket nil)))

(s/defrecord ServerSocket [context :- ZmqContext
                           socket :- mq-cmn/Socket
                           url :- URI]
  component/Lifecycle
  (start
   [this]
   (let [sock (mq/socket! (:context context) :dealer)]
     (println "Connecting server socket to" url)
     (mq/connect! sock (build-url url))
     (assoc this :socket sock)))

  (stop
   [this]
   (when socket
     (mq/set-linger! socket 0)
     (mq/disconnect! socket (build-url url))
     (mq/close! socket))
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

;;; Q: What was this next block for?
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

(s/defn default-server-url :- URI
  ([protocol address port]
     (strict-map->URI {:protocol protocol
                       :address address
                       :port port}))
  ([]
   ;; Start by defaulting to action
   (default-server-url "tcp" "localhost" 7841)))


(defn new-server
  []
  (map->ServerSocket {}))
