(ns com.frereth.client.communicator
  (:require [cljeromq.common :as mq-cmn]
            [cljeromq.core :as mq]
            [clojure.core.async :as async]
            [clojure.spec :as s]
            [clojure.tools.logging :as log]
            [com.frereth.client.config :as config]
            [com.stuartsierra.component :as component]
            [hara.event :refer (raise)]
            [taoensso.timbre :as timbre])
  (:import [clojure.lang ExceptionInfo]))

;;;; Can I handle all of the networking code in here?
;;;; Well, obviously I could. Do I want to?

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Specs

;;; FIXME: Anything using ::uri really should be using :zmq-url from cljeromq.common instead
;;; TODO: Refactor to switch to that
(s/def ::protocol string?)
(s/def ::address string?)
(s/def ::port integer?)
(s/def ::uri (s/keys :req-un [::protocol ::address ::port]))

(s/def ::thread-count (s/and integer? pos?))
(s/def ::zmq-context (s/keys :req-un [:cljeromq.common/context ::thread-count]))

;; Q: What is this?
(s/def ::renderers any?)
(s/def ::renderer-url ::uri)
(s/def ::renderer-socket (s/keys :req-un [:cljeromq.common/context
                                          ::renderers
                                          :cljeromq.common/socket
                                          ::renderer-url]))

(s/def ::server-socket (s/keys :req-un [::zmq-context
                                        :cljeromq.common/socket
                                        ::uri]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(s/fdef build-url
        :args (s/cat :url ::uri)
        :ret string?)
(defn build-url
  "This seems redundant. cljeromq already has something along these lines"
  [url]
  (let [port (:port url)]
    (str (:protocol url) "://"
         (:address url)
         ;; port is meaningless for inproc
         (when port
           (str ":" port)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Components

(defrecord ZmqContext [context
                       thread-count]
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

;;; TODO: This should just go away completely
;;; Switch to zmq-url from cljeromq.common
(defrecord URI [protocol
                address
                port]
  component/Lifecycle
  (start [this] this)
  (stop [this] this))

;; Q: How do I want to handle the actual server connections?
(defrecord RendererSocket [context
                           renderers
                           socket
                           renderer-url]
  component/Lifecycle
  (start
   [this]
   (let [sock  (mq/socket! (:context context) :router)
         actual-url (build-url renderer-url)]
     (try
       ;; TODO: Make this another option. It's really only
       ;; for debugging.
       ;; Although, really, when would I ever turn it off?
       ;; TODO: Look up (and document) what it actually does
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

(defrecord ServerSocket [context
                         socket
                         url]
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
  (map->URI {:protocol renderer-protocol
             :address renderer-address
             :port renderer-port}))

(defn new-renderer-handler
  [params]
  (let [cfg (select-keys params [:config])]
    (map->RendererSocket {:config cfg
                          :context nil
                          :renderers (atom {})
                          :socket nil})))

(s/fdef default-server-url
        :args (s/cat :protocol string?
                     :address string?
                     :port integer?)
        :ret ::uri)
(defn default-server-url
  ([protocol address port]
     (map->URI {:protocol protocol
                :address address
                :port port}))
  ([]
   ;; Start by defaulting to action
   (default-server-url "tcp" "localhost" 7841)))

(defn new-server
  []
  (map->ServerSocket {}))
