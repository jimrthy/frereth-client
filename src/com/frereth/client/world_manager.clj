(ns com.frereth.client.world-manager
  "This is designed to work in concert with the ConnectionManager: that
establishes an initial connection to a Server, then this takes over to
do the long-term bulk work.

Of course, for that to make sense, the ConnectionManager should actually
make the socket connection, and then this should receive the connected
socket and manage the individual user sessions.

TODO: that"
  (:require [cljeromq.common :as mq-cmn]
            [cljeromq.core :as mq]
            [cljeromq.curve :as curve]
            [clojure.core.async :as async]
            [com.frereth.common.async-zmq]
            [com.frereth.common.system :as sys]
            [com.frereth.common.schema :as com-skm]
            [com.frereth.common.util :as util]
            [com.frereth.common.zmq-socket :as zmq-socket]
            [com.stuartsierra.component :as component]
            [component-dsl.system :as cpt-dsl]
            [schema.core :as s]
            [taoensso.timbre :as log])
  (:import [com.frereth.common.async_zmq EventPair]
           [com.frereth.common.zmq_socket SocketDescription]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

;; TODO: Move this into common
;; Note that, currently, it's copy/pasted into web's frereth.globals.cljs
;; And it doesn't work
;; Q: What's the issue? (aside from the fact that it's experimental)
;; TODO: Ask on the mailing list
(def generic-id
  (s/cond-pre s/Keyword s/Str s/Uuid))

;; TODO: Refactor/rename this to world-identifier
;; Or maybe world-id-type
(def world-id generic-id)

(def session-id-type generic-id)
(def renderer-session {:channels {:->renderer com-skm/async-channel
                                  :->server com-skm/async-channel}})

(def remote-map
  {world-id {session-id-type renderer-session}})
(def remote-map-atom
  "Can't really define/test this using schema. Still seems useful for documentation"
  (class (atom remote-map)))
(def session-channel-map
  "Q: What should the values be here?"
  {com-skm/async-channel renderer-session})

(s/defrecord WorldManager [event-loop :- EventPair
                           remotes :- remote-map-atom
                           ctrl-chan :- com-skm/async-channel]
  component/Lifecycle
  (start
    [this]
    ;; Caller supplies the event-loop we maintain responsibility for
    ;; starting/stopping.
    ;; This is an advantage of hara.event.
    ;; Q: Is this a feature that's worth adding to component-dsl?
    (let [this (assoc this
                      :event-loop (component/start event-loop)
                      :ctrl-chan (async/chan))]
      (if-not remotes
        (assoc this :remotes (atom {}))
        this)))
  (stop
    [this]
    (when event-loop
      (component/stop event-loop))
    (when ctrl-chan
      (async/close! ctrl-chan))

    (let [this (assoc this
                      :ctrl-chan nil
                      :event-loop nil)]
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
;;; Internals

(s/defn remotes->incoming-channels :- session-channel-map
  [remotes :- remote-map]
  (reduce (fn [acc [world-id session-map]]
            (into acc
                  (reduce (fn [acc1 [session-id renderer-session]]
                            (throw (ex-info "Not Implemented" {:what "Renderer will write to individual :->server channels"})))
                          session-map)))
          remotes))

(s/defn build-dispatcher-loop! :- com-skm/async-channel
  ;;; Q: Do I have enough variations on this theme yet to make it more generic?
  [{:keys [ctrl-chan
           event-loop
           remotes]
    :as this} :- WorldManager]
  (let [server-chan (-> event-loop :ex-chan :ch)
        static-channels [ctrl-chan server-chan]]
    (async/go-loop []
      (let [channels-from-renderers (remotes->incoming-channels @remotes)]
        (throw (ex-info "What should happen here?"
                        {:known "I'm sure I need to read from all the channels,
dispatch appropriately,
cope with new channels added to list,
(need to notify this loop via ctrl-chan when that happens),
and exit when ctrl-chan closes.

And print a heartbeat notification when timeout closes"}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/defn connect-renderer-to-world! :- renderer-session
  [this :- WorldManager
   world-id :- world-id-type
   renderer-session :- session-id-type]
  (if-let [existing-session (-> this :remotes (get world-id) (get renderer-session))]
    (throw (ex-info "Attempting to duplicate a session" {:world-id world-id
                                                         :renderer-session-id renderer-session
                                                         :existing existing-session}))
    ))

(s/defn ctor :- WorldManager
  [options]
  (map->WorldManager (select-keys options [:event-loop])))
