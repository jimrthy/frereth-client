(ns com.frereth.client.system
  "How it's all wired together

TODO: Needs something like slamhound to eliminate unused pieces directly below"
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [com.frereth.client
             [connection-manager :as cxn-mgr]
             [config :as cfg]
             [world-manager]]
            [com.frereth.common.async-zmq]
            [com.frereth.common.schema]
            [com.frereth.common.util :as util]
            [com.frereth.common.zmq-socket :as zmq-sock]
            [integrant.core :as ig]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Specs

(s/def ::client-keys #_:cljeromq.curve/key-pair any?)
(s/def ::ctx-thread-count (s/and integer?
                                 pos?))
(s/def ::local-url #_:cljeromq.common/zmq-url any?)
(s/def ::server-key #_:cljeromq.curve/public any?)
(s/def ::system-opts (s/keys :opt [::client-keys
                                   ::ctx-thread-count
                                   ::local-url
                                   ::server-key]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Components

(defmethod ig/init-key ::done
  [_ _]
  (promise))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/fdef init
        :args (s/cat :opts ::system-opts)
        :ret #_:component-dsl.system/nested-definition any?)
(defn init
  [{:keys [client-keys
           ctx-thread-count
           local-url
           server-key]
    :or {;client-keys (curve/new-key-pair)
         ctx-thread-count (-> (util/core-count) dec (max 1))
         ;; Q: What do I want to do about connecting to external servers?
         ;; Do each of those requests need their own ConnectionManager?
         ;; It seems like a waste for them to bounce through the local
         ;; Server, though that may well have been what I had in mind
         local-url #:cljeromq.common {:zmq-address "127.0.0.1"
                                      :zmq-protocol :tcp
                                      :port (cfg/auth-port)}
         ;; TODO: Just eliminate this short-cut magic key completely
         ;server-key (curve/z85-decode "8C))+8}}<P[p8%c<j)bpj2aJO5:VCU>DvB@@#LqW")
         }
    :as overrides}]
  (set! *warn-on-reflection* true)

  (assert local-url)

  ;; We really need multiple instances of this, one per connected world
  ;; It's owned by the connection manager
  ;; More accurately, it's owned by the world-manager's EventLoop.
  ;; :auth-sock

  ;; Also really need multiples of this
  ;; Probably makes the most sense to let the connection-manager also
  ;; handle these
  ;; :message-loop-manager com.frereth.client.manager/ctor
  {::cxn-mgr/manager {::cxn-mgr/message-context ::zmq-sock/context
                      ::cxn-mgr/client-keys client-keys
                      ::cxn-mgr/local-url local-url
                      ::cxn-mgr/server-key server-key}
   ::zmq-sock/context {::zmq-sock/thread-count ctx-thread-count}})
