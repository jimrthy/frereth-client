(ns frereth-client.renderer
  (:require [clojure.core.async :refer :all]
            [clojure.tools.logging :as log]
            [cljeromq.core :as mq]
            [frereth-client.config :as config])
  (:gen-class))

(defn renderer-heartbeats
  "Start exchanging heartbeats with the renderer over
socket, until we get a notice over the channel that
we've established a connection with the server (or,
for that matter, that such a connection failed)."
 [control-chan ctx socket]
 ;; Don't bother with anything fancy for now.
 ;; Realistically, this seems pretty tricky and fraught
 ;; with peril. Which is why I'm trying to get a rope thrown
 ;; across first.

 (log/info "Beginning renderer heartbeat")
 (go
  (mq/with-poller [renderer-heartbeat ctx socket]
    (loop []

      (log/info "Waiting on renderer")
      ;; For example: this pretty desperately needs a timeout
      ;; I want to check the control-chan periodically to see
      ;; whether it's time to quit. Blocking here means that
      ;; can't possibly work.
      (mq/recv socket)

      (log/info "Responding to renderer")
      ;; And I really shouldn't just accept anything at all.
      ;; Want *some* sort of indication that the other side is
      ;; worth talking to.
      ;; For example...which drivers does it implement? Or some
      ;; such.
      ;; This should probably be an extremely momentous thing.
      ;; Especially since multiple clients should probably be
      ;; able to connect at the same time.
      ;; Then again, maybe that's the province of a plugin/alternative
      ;; renderer to tee/merge that sort of thing.
      (mq/send socket "PONG")

      ;; Seems like there must be an easier way to poll for an existing message.
      ;; 1 ms here isn't all that big a deal, but this still seems like a waste
      (let [to (timeout 1)]
        (let [[v ch] (alts! [control-chan] {:default nil})]
          (when (= ch :default)
            (recur))))))))

(defn fsm [ctx channel]
  (let [;; It seems more than a little wrong to be setting up the renderer
        ;; socket here. It doesn't need to be so widely available.
        ;; All communication with it should go through the renderer-channel.
        ;; FIXME: Make that so.
        socket (mq/bound-socket ctx :dealer (config/render-url))]

    ;; The basic idea that I want to do is:

    (log/info "Kicking off heartbeat with renderer")
    (renderer-heartbeats chan ctx socket)

    ;; Important:
    ;; pretty much all operations involved here should
    ;; depend on some variant of alt and a timeout.
    (go
     (loop []
       ;; Switch to dispatching messages between the channel
       ;; and socket.
       (let [to (timeout (config/server-timeout))
             [msg src] (alts! [channel to])]
         (if (not= src to)
           ;; Receive a quit message from the channel: notify
           ;; the renderer that it's time to exit.
           ;; For now, just act as a straight conduit
           (mq/send socket msg)
           (do
             ;; If we start getting communication timeouts,
             ;; really want to be smart about it. This involves
             ;; command/control stuff from the client.
             (println "Getting ready to transmit a keyword")
             (mq/send socket :server-delay)
             (println "How well did that work?"))))
       ;; For now, just make it an infinite loop
       (recur)))))
