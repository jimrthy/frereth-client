(ns com.frereth.client.connection-manager
  "Sets up basic auth w/ a server"
  (:require [cljeromq.core :as mq]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [com.frereth.common.communication :as com-comm]
            [com.frereth.common.schema :as fr-skm]
            [com.frereth.common.util :as util]
            [com.frereth.common.zmq-socket :as zmq-socket]
            [com.stuartsierra.component :as component]
            [joda-time :as dt]
            [ribol.core :refer (raise)]
            [schema.core :as s]
            [taoensso.timbre :as log])
  (:import [org.joda.time DateTime]
           [com.frereth.common.zmq_socket SocketDescription]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(declare auth-loop-creator)
(s/defrecord ConnectionManager
    [auth-loop :- fr-skm/async-channel
     auth-request :- fr-skm/async-channel
     auth-sock :- mq/Socket]
  component/Lifecycle
  (start
   [this]
   (let [auth-request (async/chan)
         almost (assoc this
                       :auth-request auth-request)
         ;; I'm dubious about placing this in the start loop.
         ;; Setting up connections like this is pretty much the
         ;; entire point.
         ;; But...it feels wrong.
         auth-loop (auth-loop-creator almost)]
     (assoc almost :auth-loop auth-loop)))
  (stop
   [this]
   (when auth-request
     (async/close! auth-request))
   (when auth-loop
     (let [[v c] (async/alts!! [auth-loop (async/timeout 1500)])]
       (when (not= c auth-loop)
         (log/error "Timed out waiting for AUTH loop to exit"))))
   (assoc this
          :auth-request nil
          :auth-loop nil)))

(def auth-dialog-description
  "This is pretty much a half-baked idea.
  Should really be downloading a template of HTML/javascript for doing
the login. e.g. a URL for an OAUTH endpoint (or however those work).
The 'expires' is really just for the sake of transitioning to a newer
login dialog with software updates.

The 'scripting' part is really interesting. It's a microcosm of the
entire architecture. Part of it should run on the client. The rest should
run on the Renderer."
  {:expires DateTime
   :client-script s/Str})
(def optional-auth-dialog-description (s/maybe auth-dialog-description))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(s/defn request-auth-descr!
  "Signal the server that we want to get in touch"
  [auth-sock :- SocketDescription]
  ;; I'm jumping through too many hoops to get this to work.
  ;; TODO: Make dealer-send smarter
  (let [msg {:character-encoding :utf-8
             ;; Tie this socket to its SESSION: first step toward allowing
             ;; secure server side socket to even think about having a
             ;; conversation
             :public-key (util/random-uuid)}
        serialized (-> msg pr-str .getBytes vector)]
    (com-comm/dealer-send! (:socket auth-sock) serialized)))

(s/defn translate-description-frames :- optional-auth-dialog-description
  "TODO: Really, the deserialization seems like it should happen back in
the com-comm layer.
I keep waffling about that."
  [frames :- fr-skm/byte-arrays]
  (when (= (count frames) 1)
    (let [#^bytes frame (first frames)
          s (String. frame)
          descr (edn/read-string s)
          expired (:expires descr)
          now (dt/date-time)]
      (when (< now expired)
        descr))))

(s/defn unexpired-auth-description :- optional-auth-dialog-description
  [this :- ConnectionManager
   potential-description :- optional-auth-dialog-description]
  "If we're missing the description, or it's expired, try to read a more recent"
  (log/debug "The description we have now:\n" (util/pretty potential-description))
  (let [now (dt/date-time)]
    (if (or (not potential-description)
            (not (:expires potential-description))
            (< now (:expires potential-description)))
      (let [auth-sock (:auth-sock this)]
        (if-let [description-frames (com-comm/dealer-recv! (:socket auth-sock))]
          ;; Note that this newly received description could also be expired already
          (translate-description-frames description-frames)  ; Previous request triggered this
          (request-auth-descr! auth-sock)))  ; trigger another request
      potential-description)))  ; Last received version still good

(s/defn send-auth-descr-response!
  [{:keys [respond]}
   current-description :- auth-dialog-description]
  (async/>!! respond current-description))

(s/defn send-wait!
  [{:keys [respond]}]
  (async/>!! respond :hold-please))

(s/defn dispatch-auth-response! :- s/Any
  [this :- ConnectionManager
   v
   c :- fr-skm/async-channel
   current-description :- optional-auth-dialog-description]
  (when-not (= c (:auth-request this))
    (raise {:problem "Auth request on unexpected channel"
            :details {:value v
                      :channel c}
            :possibilities this}))
  (if-let [current-description (unexpired-auth-description this current-description)]
    (send-auth-descr-response! v current-description)
    (send-wait! v)))

(s/defn handle-possible-incoming! :- optional-auth-dialog-description
  "This is pretty awkward.
It was worse before I refactored it out of the middle of auth-loop-creator"
  [{:keys [auth-request]
    :as this}
   dialog-description :- optional-auth-dialog-description
   v :- optional-auth-dialog-description
   c :- fr-skm/async-channel]
  (if v
    ;; Optimal scenario:
    ;; Had a nil dialog-description. Have the real one waiting from the server.
    (dispatch-auth-response! this v c dialog-description)
    (when (not= auth-request c)
      (log/info "Auth Loop Creator: heartbeat")
      dialog-description)))

(s/defn auth-loop-creator :- fr-skm/async-channel
  "Set up the auth loop
This is just an async-zmq/EventPair
TODO: Switch to that"
  [{:keys [auth-request auth-sock]
    :as this} :- ConnectionManager]
  (let [minutes-5 (partial async/timeout (* 5 (util/minute)))]
    ;; It seems almost wasteful to start this before there's any
    ;; interest to express. But the 90% (at least) use case is for
    ;; the local server where there won't ever be any reason
    ;; for it to timeout.
    (request-auth-descr! auth-sock)
    (async/go
      (loop [t-o (minutes-5)
             dialog-description nil]
        (let [updated-dialog-description
              (try
                (let [[v c] (async/alts! [auth-request t-o])]
                  (handle-possible-incoming! this dialog-description v c))
                (catch RuntimeException ex
                  (log/error ex "Dispatching an auth request.\nMake this fatal for dev time.")
                  (raise {:problem ex
                          :component this
                          :details {:dialog-description dialog-description
                                    :auth-request auth-request
                                    :time-out t-o}})))]
          (recur (minutes-5) updated-dialog-description)))
      (log/warn "ConnectionManager's auth-loop exited"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/defn initiate-handshake :- optional-auth-dialog-description
  "TODO: ^:always-validate"
  [this :- ConnectionManager
   attempts :- s/Int
   timeout-ms :- s/Int]
  (let [receiver (async/chan)
        responder {:respond receiver}
        transmitter (:auth-request this)]
    (loop [remaining-attempts attempts]
      (when (< 0 remaining-attempts)
        (let [[v c] (async/alts!! [[transmitter responder] (async/timeout timeout-ms)])]
          (if v
            (if (= v :hold-please)
              (do
                (log/info "Request for AUTH dialog ACK'd. Waiting...")
                (recur (dec remaining-attempts)))
              (do
                (log/debug "Successfully asked transmitter to return reply on:\n " responder)
                responder))
            (if (= c transmitter)
              (log/error "Auth channel closed")
              (do
                (log/warn "Timed out trying to transmit request for AUTH dialog.\n"
                          (dec remaining-attempts) " attempts remaining")
                (recur (dec remaining-attempts))))))))))
(comment
  (initiate-handshake (:connection-manager system) 5 2000))

(s/defn ctor :- ConnectionManager
  [{:keys [url]}]
  (map->ConnectionManager {:url url}))
