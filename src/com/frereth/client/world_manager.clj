(ns com.frereth.client.world-manager
  "This is designed to work in concert with the ConnectionManager: that
establishes an initial connection to a Server, then this takes over to
do the long-term bulk work.
"
  (:require [cljeromq.common :as mq-cmn]
            [cljeromq.core :as mq]
            [cljeromq.curve :as curve]
            [clojure.core.async :as async]
            [com.frereth.client.dispatcher :as dispatcher]
            [com.frereth.common.async-zmq]
            [com.frereth.common.system :as sys]
            [com.frereth.common.schema :as com-skm]
            [com.frereth.common.util :as util]
            [com.frereth.common.zmq-socket :as zmq-socket]
            [com.stuartsierra.component :as component]
            [component-dsl.system :as cpt-dsl]
            [schema.core :as s2]
            [taoensso.timbre :as log])
  (:import [com.frereth.common.async_zmq EventPair]
           [com.frereth.common.zmq_socket SocketDescription]
           [com.stuartsierra.component SystemMap]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

;; TODO: Move this into common
;; Note that, currently, it's copy/pasted into web's frereth.globals.cljs
;; And it doesn't work
;; Q: What's the issue? (aside from the fact that it's experimental)
;; TODO: Ask on the mailing list
(def generic-id
  (s2/cond-pre s2/Keyword s2/Str s2/Uuid))

;; TODO: Refactor/rename this to world-identifier
;; Or maybe world-id-type
(def world-id-type generic-id)

(def session-id-type generic-id)
(def renderer-session {:channels {:->renderer com-skm/async-channel
                                  :->server com-skm/async-channel}})

(def remote-map
  {world-id-type {session-id-type renderer-session}})
(def remote-map-atom
  "Can't really define/test this using schema. Still seems useful for documentation"
  (class (atom remote-map)))
(def session-channel-map
  "Q: What should the values be here?"
  {com-skm/async-channel renderer-session})

(declare build-dispatcher-loop!)
(s2/defrecord WorldManager [ctrl-chan :- com-skm/async-channel
                            dispatcher :- com-skm/async-channel
                            event-loop :- EventPair
                            remote-mix :- com-skm/async-channel
                            remotes :- remote-map-atom]
  component/Lifecycle
  (start
    [this]
    ;; Caller supplies the event-loop we maintain responsibility for
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

(defmulti do-ctrl-msg-dispatch!
  "This is the internal dispatch mechanism for handling control messages"
  (fn [this msg]
    (:command msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internals

(s2/defn reduce-remote-map->incoming-channels
  "Really just a helper function for remotes->incoming-channels

Should probably just define it inline, but it isn't quite that short/sweet.
And I can see it growing once I figure out what it should actually be returning.

The fundamental point is that the dispatcher needs a fast way to decide where
to forward messages based on the channel where it received them."
  [acc [world-id session-map]]
  (into acc
        (reduce (fn [acc1 [session-id renderer-session]]
                  (let [->server (-> renderer-session :channels :->server)]
                    ;; Make sure we don't wind up with duplicates
                    (assert (nil? (get acc1 ->server)))
                    (assoc acc1 ->server renderer-session)))
                session-map)))

(s2/defn remotes->incoming-channels :- session-channel-map
  [remotes :- remote-map]
  (reduce reduce-remote-map->incoming-channels remotes))

(defmethod do-ctrl-msg-dispatch! :default
  [_ msg]
  (throw (ex-info "Not Implemented" {:unhandled msg})))

(s2/defn dispatch-control-message! :- s2/Bool
  [msg]
  ;; If the control message is nil, the channel closed.
  ;; Which is the signal to exit
  (when msg
    (do-ctrl-msg-dispatch! msg)))

(s2/defn build-dispatcher-loop! :- com-skm/async-channel
  ;;; Q: Do I have enough variations on this theme yet to make it more generic?
  [{:keys [ctrl-chan
           event-loop
           remote-mix]
    :as this} :- WorldManager]
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
;;; Public

(s2/defn connect-renderer-to-world! :- renderer-session
  [this :- WorldManager
   world-id :- world-id-type
   renderer-session :- session-id-type]
  (if-let [existing-session (-> this :remotes (get world-id) (get renderer-session))]
    (throw (ex-info "Attempting to duplicate a session" {:world-id world-id
                                                         :renderer-session-id renderer-session
                                                         :existing existing-session}))
    ;; Needs to start by exchanging a handshake with the server to establish
    ;; the "best" protocol available on both sides.
    ;; Actually, that belongs in the Dispatcher.
    (throw (ex-info "Not Implemented" {:problem "How should this work?"}))))

(s2/defn disconnect-renderer-from-world!
  [this :- WorldManager
   world-id :- world-id-type
   renderer-session :- session-id-type]
  (let [existing-session (-> this :remotes (get world-id) (get renderer-session))]
    (assert existing-session)
    (throw (ex-info "Not Implemented" {:problem "How should this work?"}))))

(s2/defn ctor :- WorldManager
  [options]
  (map->WorldManager options))
