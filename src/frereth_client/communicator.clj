(ns frereth-client.communicator
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [frereth-client.config :as config]
            [plumbing.core :as plumbing]
            [ribol.core :refer (raise)]
            [schema.core :as s]
            [taoensso.timbre :as timbre]
            [zeromq.zmq :as zmq])
  (:import [org.zeromq ZMQ$Context ZMQException])
  (:gen-class))

;;;; Can I handle all of the networking code in here?
;;;; Well, obviously I could. Do I want to?

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(s/defrecord ZmqContext [context :- ZMQ$Context
                          thread-count :- s/Int]
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
     ;; Only applies to ZContext.
     ;; Which is totally distinct from ZMQ$Context
     (comment (zmq/close context))
     (.close context))
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
                              renderer-url :- URI]
  component/Lifecycle
  (start
   [this]
   (let [sock  (zmq/socket (:context context) :router)
         actual-url (build-url renderer-url)]
     (try
       ;; TODO: Make this another option. It's really only
       ;; for debugging.
       (zmq/set-router-mandatory sock 1)
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
     (if (not= "inproc" (:protocol renderer-url))
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

(s/defrecord ServerSocket [context
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
     (default-server-url "inproc" "local" nil)))


(defn new-server
  []
  (map->ServerSocket {}))
