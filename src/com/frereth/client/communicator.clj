(ns com.frereth.client.communicator
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [com.frereth.client.config :as config]
            [hara.event :refer (raise)]
            [integrant.core :as ig]
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

(defmethod ig/init-key ::zmq-ctx
  [_ {:keys [::thread-count]
      :as this}]
  (let [msg (str "Creating a 0mq Context with " thread-count " (that's a "
                 (class thread-count) ") threads")]
    (println msg))
  (let [ctx #_(mq/context thread-count) (throw (RuntimeException. "New message scheme"))]
    (assoc this :context ctx)))

(defmethod ig/halt-key! ::zmq-ctx
  [_ {:keys [::ctx]}]
  (throw (RuntimeException. "We *do* need to halt this, don't we?")))

;;; TODO: This should just go away completely
;;; Switch to zmq-url from cljeromq.common
(defrecord URI [protocol
                address
                port])

;;; This represents a socket for renderers to connect to.
;;; It's really pretty obsolete, if I do the sensible (?)
;;; thing and use this as a library instead of middleware.

(defmethod ig/init-key ::renderer-socket
  [_ {:keys [::context
             ::renderer-url]
      :as this}]
  (let [sock (comment (mq/socket! (:context context) :router))
        actual-url (build-url renderer-url)]
    (try
      ;; TODO: Make this another option. It's really only
      ;; for debugging.
      ;; Although, really, when would I ever turn it off?
      ;; TODO: Look up (and document) what it actually does
      (comment
        (mq/set-router-mandatory! sock true)
        (mq/bind! sock actual-url))
      (throw (RuntimeException. "Need something along those lines"))
      (catch ExceptionInfo ex
        (raise {:cause ex
                :binding renderer-url
                :details actual-url})))
    ;; Q: What do I want to do about the renderers?
    (assoc this :socket sock)))

(defmethod ig/halt-key! ::renderer
  [_ {:keys [::renderer-url
             ::socket]
      :as this}]
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
        (comment
          (mq/unbind! socket (build-url renderer-url)))
        (throw (RuntimeException. "Does unbind make any sense?"))
        (catch ExceptionInfo ex
          (log/info ex "This usually isn't a real problem")))
      (log/debug "Can't unbind an inproc socket"))
    (comment (mq/close! socket))
    (throw (RuntimeException. "What about closing?"))))

;; Q: How do I want to handle the actual server connections?
(defmethod ig/init-key ::server
  [_ {:keys [::context
             ::url]
      :as this}]
  (let [sock (comment (mq/socket! (:context context) :dealer))]
    (comment
      (println "Connecting server socket to" url)
      (mq/connect! sock (build-url url)))
    (throw (RuntimeException. "Reimplement connect!"))
    (assoc this :socket sock)))

(defmethod ig/halt-key! ::server
  [_ {:keys [::server
             ::url]}]
  (comment
    (when socket
      (mq/set-linger! socket 0)
      (mq/disconnect! socket (build-url url))
      (mq/close! socket)))
  (throw (RuntimeException. "Surely we need to do some sort of cleanup")))

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
  {::context nil
   ::thread-count thread-count})

(defn new-renderer-url
  [{:keys [renderer-protocol renderer-address renderer-port] :as cfg}]
  (println "Setting up the renderer URL based on:\n" cfg)
  {::protocol renderer-protocol
   ::address renderer-address
   ::port renderer-port})

(defn new-renderer-handler
  [params]
  (let [cfg (select-keys params [:config])]
    {::config cfg
     ::context nil
     ::renderers (atom {})
     ::socket nil}))

(s/fdef default-server-url
        :args (s/cat :protocol string?
                     :address string?
                     :port integer?)
        :ret ::uri)
(defn default-server-url
  ([protocol address port]
   {::protocol protocol
    ::address address
    ::port port})
  ([]
   ;; Start by defaulting to action
   (default-server-url "tcp" "localhost" 7841)))
