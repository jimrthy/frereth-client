(ns frereth-client.renderer
  (:require [clojure.core.async :refer :all]
            [clojure.tools.logging :as log]
            [cljeromq.core :as mq]
            [frereth-client.config :as config])
  (:gen-class))

(defn placeholder-heartbeats
  "Start exchanging heartbeats with the renderer over
socket, until we get a notice over the channel that
we've established a connection with the server (or,
for that matter, that such a connection failed)."
 [control-chan socket]
 ;; Don't bother with anything fancy for now.
 ;; Realistically, this seems pretty tricky and fraught
 ;; with peril. Which is why I'm trying to get a rope thrown
 ;; across first.

 (log/info "Waiting on renderer")
 ;; For example: this pretty desperately needs a timeout
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
 (send socket "PONG")
 (throw (RuntimeException. "This really needs to happen inside its own thread, with a poller"))
 ;; For that matter, it should really be a loop until the command channel broadcasts an
 ;; "all done" message.
 )

(defn fsm [ctx channel]
  (let [;; It seems more than a little wrong to be setting up the renderer
        ;; socket here. It doesn't need to be so widely available.
        ;; All communication with it should go through the renderer-channel.
        ;; FIXME: Make that so.
        socket (mq/bound-socket ctx :dealer (config/render-url))]

    ;; The basic idea that I want to do is:

    (log/info "Kicking off heartbeat")
    (placeholder-heartbeats chan socket)

    (throw (RuntimeException. "How does the rest of this actually work?"))

    ;; b) Switch to dispatching messages between the channel
    ;; and socket.
    ;; c) Receive a quit message from the channel: notify
    ;; the renderer that it's time to exit.
    ;; d) Exit.

    (go (loop [msg (<! chan)]
          ;; Important:
          ;; pretty much all operations involved here should
          ;; depend on some variant of alt and a timeout.
          (throw (RuntimeException. "Do something not-stupid"))))))
