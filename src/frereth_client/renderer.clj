(ns frereth-client.renderer
  (:require [clojure.core.async :refer :all])
  (:gen-class))

(defn fsm [chan socket]
  ;; This is a really dumb way to organize things. It's time for
  ;; that sort of coding.
  ;; Except that it's also completely and totally wrong...
  ;; Communication with the renderer needs to come from the
  ;; socket
  ;; The basic idea that I want to do is:
  ;; a) Start exchanging heartbeats with the renderer over
  ;; socket, until we get a notice over the channel that
  ;; we've established a connection with the server (or,
  ;; for that matter, that such a connection failed).
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
