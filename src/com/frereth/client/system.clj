(ns com.frereth.client.system
  "How it's all wired together

TODO: Needs something like slamhound to eliminate unused pieces directly below"
  (:require [cljeromq.common]
            [cljeromq.core :as mq]
            [cljeromq.curve :as curve]
            [clojure.core.async :as async]
            [clojure.spec :as s]
            [com.frereth.client
             [config :as cfg]
             [world-manager]]
            [com.frereth.common.async-zmq]
            [com.frereth.common.schema]
            [com.frereth.common.util :as util]
            [com.frereth.common.zmq-socket :as zmq-sock]
            [com.stuartsierra.component :as component]
            [component-dsl.system :as cpt-dsl])
  (:import #_[com.frereth.client.world_manager WorldManager]
           [com.frereth.common.async_zmq EventPair]
           [com.frereth.common.zmq_socket ContextWrapper SocketDescription]
           [com.stuartsierra.component SystemMap]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Specs

(s/def ::client-keys :cljeromq.curve/key-pair)
(s/def ::ctx-thread-count (s/and integer?
                                 pos?))
(s/def ::local-url :cljeromq.common/zmq-url)
(s/def ::server-key :cljeromq.curve/public)
(s/def ::system-opts (s/keys :opt [::client-keys
                                   ::ctx-thread-count
                                   ::local-url
                                   ::server-key]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/fdef init
        :args (s/cat :opts ::system-opts)
        :ret :component-dsl.system/nested-definition)
(defn init
  [{:keys [client-keys
           ctx-thread-count
           local-url
           server-key]
    :or {client-keys (curve/new-key-pair)
         ctx-thread-count (-> (util/core-count) dec (max 1))
         ;; Q: What do I want to do about connecting to external servers?
         ;; Do each of those requests need their own ConnectionManager?
         ;; It seems like a waste for them to bounce through the local
         ;; Server, though that may well have been what I had in mind
         local-url {:address "127.0.0.1"
                    :protocol :tcp
                    :port (cfg/auth-port)}
         ;; TODO: Just eliminate this short-cut magic key completely
         server-key (curve/z85-decode "8C))+8}}<P[p8%c<j)bpj2aJO5:VCU>DvB@@#LqW")}
    :as overrides}]
  (set! *warn-on-reflection* true)

  (assert local-url)

  (let [struct '{;; We really need multiple instances of this, one per connected world
                 ;; It's owned by the connection manager
                 ;; :auth-sock
                 :connection-manager com.frereth.client.connection-manager/ctor
                 :ctx com.frereth.common.zmq-socket/ctx-ctor
                 ;; Also really need multiples of this
                 ;; Probably makes the most sense to let the connection-manager also
                 ;; handle these
                 ;; :message-loop-manager com.frereth.client.manager/ctor
                 }
        depends {:connection-manager {:message-context :ctx}}]
    #:component-dsl.system {:system-configuration #:component-dsl.system {:structure struct
                                                                          :dependencies depends}
                            :configuration-tree {:connection-manager {:client-keys client-keys
                                                                      :local-url local-url
                                                                      :server-key server-key}
                                                 :ctx {:thread-count ctx-thread-count}}
                            :primary-component :connection-manager}))
