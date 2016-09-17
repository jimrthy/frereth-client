(ns com.frereth.client.world-manager
  "This is designed to work in concert with the ConnectionManager: that
establishes an initial connection to a Server, then this takes over to
do the long-term bulk work."
  (:require [cljeromq.common :as mq-cmn]
            [cljeromq.core :as mq]
            [cljeromq.curve :as curve]
            [clojure.core.async :as async]
            [clojure.spec :as s]
            [com.frereth.client.dispatcher :as dispatcher]
            [com.frereth.common.async-zmq]
            [com.frereth.common.system :as sys]
            [com.frereth.common.schema :as com-skm]
            [com.frereth.common.util :as util]
            [com.frereth.common.zmq-socket :as zmq-socket]
            [com.stuartsierra.component :as component]
            [component-dsl.system :as cpt-dsl]
            [taoensso.timbre :as log])
  (:import [com.frereth.common.async_zmq EventPair]
           [com.frereth.common.zmq_socket SocketDescription]
           [com.stuartsierra.component SystemMap]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(s/def ::world-id-type :com.frereth.common.schema/generic-id)

(s/def ::session-id-type :com.frereth.common.schema/generic-id)

(s/def ::->renderer :com.frereth.common.schema/async-channel)
(s/def ::->server :com.frereth.common.schema/async-channel)
(s/def ::channels (s/keys :req [::->renderer ::->server] ))
(s/def ::renderer-session (s/keys :req [::channels]))

(s/def ::session-map (s/map-of ::session-id-type ::renderer-session))
(s/def ::remote-map (s/map-of ::world-id-type ::session-map))

;; Q: What are the odds this will actually work?
(s/def ::remote-map-atom (s/and deref
                                ::remote-map))

(s/def ::session-channel-map (s/map-of :com.frereth.common.schema/async-channel
                                       ::renderer-session))

(s/def ::command #{})
(s/def ::control-message (s/keys :req [::command]))

(s/def ::ctrl-chan :com.frereth.common.schema/async-channel)
(s/def ::dispatcher :com.frereth.common.schema/async-channel)
(s/def ::event-loop :com.frereth.common.async-zmq/event-pair)
(s/def ::remote-mix :com.frereth.common.schema/async-channel)
(s/def ::remotes ::remote-map-atom)

;; Q: How can I avoid the copy/paste between this and the ctor opts?
(s/def ::world-manager (s/keys :req-un [::ctrl-chan
                                        ::dispatcher
                                        ::event-loop
                                        ::remote-mix
                                        ::remotes]))
(s/def ::world-manager-ctor-opts (s/keys :opt-un [::ctrl-chan
                                                  ::dispatcher
                                                  ::event-loop
                                                  ::remote-mix
                                                  ::remotes]))

(defmulti do-ctrl-msg-dispatch!
  "This is the internal dispatch mechanism for handling control messages"
  (fn [this msg]
    ;; TODO: This really should be namespaced
    (::command msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internals

(s/fdef reduce-remote-map->incoming-channels
        :args (s/cat :acc ::session-channel-map
                     :val (s/cat :world-id ::world-id
                                 :session-map ::session-map))
        :ret ::session-channel-map)
(defn reduce-remote-map->incoming-channels
  "Really just a helper function for remotes->incoming-channels

Should probably just define it inline, but it isn't quite that short/sweet.
And I can see it growing once I figure out what it should actually be returning.

The fundamental point is that the dispatcher needs a fast way to decide where
to forward messages based on the channel where it received them."
  [acc [world-id session-map]]
  (into acc
        (reduce (fn [acc1 [session-id renderer-session]]
                  (let [->server (-> renderer-session ::channels ::->server)]
                    ;; Make sure we don't wind up with duplicates
                    (assert (nil? (get acc1 ->server)))
                    (assoc acc1 ->server renderer-session)))
                session-map)))

(s/fdef remotes->incoming-channels
        :args (s/cat :remotes ::remote-map)
        :ret ::session-channel-map)
(defn remotes->incoming-channels
  [remotes]
  (reduce reduce-remote-map->incoming-channels remotes))

(defmethod do-ctrl-msg-dispatch! :default
  [_ msg]
  ;; TODO: Need to implement these
  (throw (ex-info "Not Implemented" {:unhandled msg})))

(s/fdef dispatch-control-message!
        :args (s/cat :this ::world-manager
                     :msg ::control-message)
        :ret boolean?)
(defn dispatch-control-message!
  [this msg]
  ;; If the control message is nil, the channel closed.
  ;; Which is the signal to exit
  (when msg
    (do-ctrl-msg-dispatch! this msg)))

(s/fdef build-dispatcher-loop!
        :args (s/cat :this ::world-manager)
        :ret ::dispatcher)
(defn build-dispatcher-loop!
  ;;; Q: Do I have enough variations on this theme yet for genericizing to be worthwhile?
  [{:keys [ctrl-chan
           event-loop
           remote-mix]
    :as this}]
  (let [server-> (-> event-loop :ex-chan :ch)]
    (let [dispatch-thread
          (async/go
            (loop [[val ch] (async/alts! [ctrl-chan server-> remote-mix])]
              (let [continue? (condp = ch
                                ctrl-chan (dispatch-control-message! this val)
                                server-> (dispatcher/server-> this val)
                                (throw (ex-info "Not Implemented" {:problem "This is where it gets interesting"
                                                                   :source ch
                                                                   :received val})))]
                (when continue? (recur (async/alts! [ctrl-chan server-> remote-mix])))))
            (log/warn "Dispatcher loop exiting"))])))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Components

(defrecord WorldManager [ctrl-chan
                         dispatcher
                         event-loop
                         remote-mix
                         remotes]
  component/Lifecycle
  (start
    [this]
    ;; Caller supplies the event-loop. We maintain responsibility for
    ;; starting/stopping.
    ;; This is an advantage of hara.event.
    ;; Q: Is this a feature that's worth adding to component-dsl?
    (let [underlying-mixer (async/chan)  ; Trust this to get GC'd w/ remote-mix
          this (assoc this
                      :event-loop (component/start event-loop)
                      :ctrl-chan (async/chan)
                      :remote-mix (async/mix underlying-mixer))
          sans-dispatcher (if-not remotes
                            (assoc this :remotes (atom {}))
                            this)]
          ;; Q: If/when this turns into a bottleneck, will I gain any performance benefits
          ;; by expanding to multiple instances/go-loop threads?
          ;; TODO: Figure out some way to benchmark this
      (assoc sans-dispatcher :dispatcher (build-dispatcher-loop! this))))
  (stop
    [this]
    (when event-loop
      (component/stop event-loop))
    (when ctrl-chan
      ;; Note that this signals the dispatcher loop to close
      (async/close! ctrl-chan))
    (when remote-mix
      (async/close! remote-mix))

    (let [this (assoc this
                      :ctrl-chan nil
                      :dispatcher nil
                      :event-loop nil
                      :remote-mix nil)]
      (if remotes
        (do
          (doseq [[_name remote] @remotes]
            ;; Q: Why am I shutting down the event
            ;; loop before closing its communications pathways?
            ;; A: Well, maybe there's an advantage to
            ;; giving them the final opportunity to flush
            ;; the pipeline, but it probably doesn't matter.
            (log/debug "Stopping remote " _name
                       "\nwith keys:" (keys remote)
                       "\nand auth token: " (:auth-token remote))
            ;; FIXME: Shouldn't need to do this
            (component/stop (dissoc remote :auth-token)))
          (log/debug "Communications Loop Manager: Finished stopping remotes")
          (assoc this :remotes nil))
        this))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/fdef connect-renderer-to-world!
        :args (s/cat :this ::world-manager
                     :world-id ::world-id-type
                     :renderer-session ::session-id-type)
        :ret ::renderer-session)
(defn connect-renderer-to-world!
  [this
   world-id
   renderer-session]

  (if-let [existing-session (-> this :remotes (get world-id) (get renderer-session))]
    (throw (ex-info "Attempting to duplicate a session" {:world-id world-id
                                                         :renderer-session-id renderer-session
                                                         :existing existing-session}))
    ;; Needs to start by exchanging a handshake with the server to establish
    ;; the "best" protocol available on both sides.
    ;; Actually, that belongs in the Dispatcher.
    ;; No, the ConnectionManager should have already handled it.
    (throw (ex-info "Not Implemented" {:problem "How should this work?"}))))

(s/fdef disconnect-renderer-from-world!
        :args (s/cat :this ::world-manager
                     :world-id ::world-id-type
                     ::renderer-session ::session-id-type))
(defn disconnect-renderer-from-world!
  [this
   world-id
   renderer-session]
  (let [existing-session (-> this :remotes (get world-id) (get renderer-session))]
    (assert existing-session)
    (throw (ex-info "Not Implemented" {:problem "How should this work?"}))))

(s/fdef ctor
        :args (s/cat :options ::world-manager-ctor-opts)
        :ret ::world-manager)
(defn ctor
  [options]
  (map->WorldManager options))
