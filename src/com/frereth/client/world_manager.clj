(ns com.frereth.client.world-manager
  "Manage connections between Renderers and worlds on a single Server.

This is designed to work in concert with the ConnectionManager: that
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
(s/def ::state (s/and deref
                      (s/or :success #{::initialized
                                       ::connecting
                                       ::protocol-negotiation
                                       ::rejected
                                       ::timeout
                                       ::connected
                                       ::disconnected}
                            :fail (s/map-of #(= ::error %) any?))))

;; Q: How can I avoid the copy/paste between this and the ctor opts?
(s/def ::world-manager (s/keys :req-un [::ctrl-chan
                                        ::dispatcher
                                        ::event-loop
                                        ::remote-mix
                                        ::remotes
                                        ::state]))
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
           mix-out]
    :as this}]
  (let [server-> (-> event-loop :ex-chan :ch)]
    (assert server-> (str "Missing the from-server channel in the event-loop\n"
                          "Available keys in there:\n"
                          (keys event-loop)
                          "\nAvailable keys under its :ex-chan component:\n"
                          (keys (:ex-chan event-loop))))
    (assert ctrl-chan (str "Missing control-channel in WorldManager Component.\nAvailable keys:\n"
                           (keys this)))
    (assert mix-out (str "Missing mixer output in WorldManager Component.\nAvailable keys:\n"
                           (keys this)))
    (let [dispatch-thread
          (async/go
            (log/info "Entering World Manager's dispatcher thread loop.\nStarting by polling on:"
                      (str ctrl-chan) ", a " (class ctrl-chan) "\n"
                      (str server->) ", a " (class server->) "\n"
                      (str mix-out) ", a " (class mix-out))
            (loop [[val ch] (async/alts! [ctrl-chan server-> mix-out])]
              (let [continue? (condp = ch
                                ctrl-chan (dispatch-control-message! this val)
                                server-> (dispatcher/server-> this val)
                                ;; Message arrived at mix-out. Which means from the server, right?
                                ;; It's a bad that I've already forgotten how I wanted this part to work.
                                ;; But it seems like it would make the most sense for this to mean I need
                                ;; to pick a renderer socket based on the message and forward this there.
                                (throw (ex-info "Not Implemented" {:problem "This is where it gets interesting"
                                                                   :source ch
                                                                   :received val})))]
                (when continue?
                  (log/debug "World Manager dispatcher looping")
                  (recur (async/alts! [ctrl-chan server-> mix-out])))))
            (log/warn "Dispatcher loop exiting"))])))

(defn negotiate-connection!
  "Honestly, this is going to deserve its own namespace"
  [{:keys [event-loop state transition]
    :as this}]
  (async/go
    (let [out (-> event-loop :ex-chan :ch)
          in (-> event-loop :interface :in-chan :ch)]
      ;; Q: Worth switching to alts! and a timeout?
      (if (async/offer! in :ohai)
        (let [challenge (async/<! out)]
          (if (= challenge :oryl?)
            ;; Q: Worth namespacing the actual comms protocol here?
            (if (async/offer! in (list 'icanhaz? {:com.frereth.authentication/protocol-versions {:frereth [[0 0 1]]}}))
              (do
                (reset! state ::protocol-negotiation)
                ;; TODO: Ditch the magic number. The timeout needs to be a parameter. Or maybe an atom associated with the initial ::connecting state
                (let [[agreement ch] (async/alts! [out (async/timeout (* 5 (util/seconds)))])]
                  (if agreement
                    (if (= agreement {:name :frereth :version [0 0 1]})
                      (reset! state ::connected)
                      (reset! state {::error {:problem "Unknown protocol response"
                                              :server-demanded agreement}}))
                    (reset! state ::timeout))))
              (reset! state {::error {::problem "Event Loop quit listening"}}))

            (reset! state {::error {::problem "challenge"
                                    ::received challenge}})))
        (reset! state {::error "Event Loop not available"})))))

(s/fdef connect-renderer-to-connected-world!
        :args (s/cat :this ::world-manager
                     :world-id ::world-id-type
                     :renderer-session ::session-id-type)
        :ret ::renderer-session)
(defn connect-renderer-to-connected-world!
  [this world-id renderer-session]
  (throw (ex-info "Need to cope with already-connected worlds" {:problem "How should this work?"})))

(s/fdef establish-new-connection!
        :args (s/cat :this ::world-manager
                     :world-id ::world-id-type
                     :renderer-session ::session-id-type)
        :ret ::renderer-session)
(defn establish-new-connection!
  [this world-id renderer-session]
  ;; TODO: negotiate-connection! is probably a good starting point
  (throw (ex-info "Need to do World connection"
                  {:problem "What do we do when not connected?"})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Components

(defrecord WorldManager [ctrl-chan
                         dispatcher
                         event-loop
                         mix-out
                         remote-mix
                         remotes
                         state
                         ;; async channel to trigger FSM adjustments
                         transition]
  component/Lifecycle
  (start
    [this]
    ;; Caller supplies the event-loop. We maintain responsibility for
    ;; starting/stopping.
    ;; This is an advantage of hara.event.
    ;; Q: Is this a feature that's worth adding to component-dsl?
    (let [mix-out (or mix-out (async/chan)) ; Trust this to get GC'd w/ remote-mix
          state (atom ::initialized)
          this (assoc this
                      :state state
                      ;; Avoiding this was a major factor in recent cpt-dsl changes.
                      ;; Q: Do I still need to do this?
                      :event-loop (component/start event-loop)
                      :ctrl-chan (async/chan)
                      :mix-out mix-out
                      :remote-mix (async/mix mix-out))
          sans-dispatcher (if-not remotes
                            (assoc this :remotes (atom {}))
                            this)
          ;; Q: If/when this turns into a bottleneck, will I gain any performance benefits
          ;; by expanding to multiple instances/go-loop threads?
          ;; TODO: Figure out some way to benchmark this
          result (assoc sans-dispatcher :dispatcher (build-dispatcher-loop! this))]
      (add-watch (:state result) :fsm (fn [k _ old new]
                                        ;; TODO: Be more useful.
                                        ;; Main thing is that we need to notify renderers
                                        ;; that they can start connecting to Worlds.
                                        ;; And add identity...maybe the server ID
                                        ;; or URL, at least
                                        (log/info (str "WorldManager: " (util/pretty this)
                                                       "\nstate changing from " old
                                                       "\nto " new))
                                        (when (= new ::timeout)
                                          (throw (ex-info "Not Implemented"
                                                          ;; Note that this needs to back off
                                                          {:todo "Try to reconnect?"})))))
      (reset! state ::connecting)
      ;; TODO: Move this back into connection-manager
      ;; Q: Is that where it belongs?
      (negotiate-connection! result)
      result))
  (stop
    [this]
    (reset! state ::disconnecting)
    (when event-loop
      (component/stop event-loop))
    (when ctrl-chan
      ;; Note that this signals the dispatcher loop to close
      (async/close! ctrl-chan))
    (when remote-mix
      ;; Q: Does this make any sense?
      ;; What we really want to do is close the channels
      ;; coming in to the mixer.
      ;; But we don't own them, do we?
      (async/close! remote-mix))
    (when mix-out
      (async/close! mix-out))

    (let [this (assoc this
                      :ctrl-chan nil
                      :dispatcher nil
                      :event-loop nil
                      :mix-out nil
                      :remote-mix nil)
          disconnected (if remotes
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
                         this)]
      (reset! state ::initialized)
      (remove-watch state :fsm)
      disconnected)))

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
  (if-let [existing-session (-> this
                                :remotes
                                (get world-id)
                                (get renderer-session))]
    (throw (ex-info "Attempting to duplicate a session" {:world-id world-id
                                                         :renderer-session-id renderer-session
                                                         :existing existing-session}))
    (let [state (-> this :state deref)]
      (if (not= state ::connected)
        (establish-new-connection! this world-id renderer-session)
        (connect-renderer-to-connected-world! this world-id renderer-session)))))

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
