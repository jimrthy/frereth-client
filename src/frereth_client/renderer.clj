(ns frereth-client.renderer
  (:require [clojure.core.async :refer :all]
            [cljeromq.core :as mq])
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

 ;; For example: this pretty desperately needs a timeout
 (read socket)
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
 (send socket "PONG"))

(defn fsm [chan socket]
  ;; The basic idea that I want to do is:

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
        (throw (RuntimeException. "Do something not-stupid")))))
