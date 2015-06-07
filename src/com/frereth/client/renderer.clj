(ns frereth-client.renderer
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            #_[cljeromq.core :as mq]
            [zeromq.zmq :as mq]
            [frereth-client.config :as config]
            [frereth-client.translator :as trnsltr]
            [ribol.core :refer :all]
            [taoensso.timbre :as timbre]))

(comment (defn renderer-heartbeats
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
           (async/go
             (mq/with-poller [renderer-heartbeat ctx socket]
               (loop []

                 (log/info "Waiting on renderer")
                 ;; For example: this pretty desperately needs a timeout
                 ;; I want to check the control-chan periodically to see
                 ;; whether it's time to quit. Blocking here means that
                 ;; can't possibly work.
                 (let [input
                       ;; FIXME: Honestly, this should probably be a poll
                       (mq/recv socket)]

                   (log/info "Responding to renderer")

                   (if (= input "PING")
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

                     (do
                       ;; FIXME: There's something that's completely and totally wrong here.
                       ;; I'm listening and writing to the same channel here. This is really
                       ;; just a feedback loop.
                       ;; Really need to re-think this architecture:
                       ;; Right now, my goesintas are just reading from the goesouttas.
                       (async/>!! control-chan (trnsltr/client->server input))
                       (raise :start-here))))

                 ;; Seems like there must be an easier way to poll for an existing message.
                 ;; 1 ms here isn't all that big a deal, but this still seems like a waste
                 (let [to (async/timeout 1)]
                   (let [[v ch] (alts! [control-chan] {:default nil})]
                     (when (= ch :default)
                       (recur)))))))))

(comment (defn fsm
           "This name isn't really all that well.
This function kicks off background threads to translate communications
between the front-end renderer and the server.

Naive in that it ignores that multiple servers might (and probably
should) be involved.

More naive in that there's no possibility of cleanup."
           [ctx channel]
           ;; FIXME: Build socket during start. Stop it and release it
           ;; for GC during stop. The current approach is just begging
           ;; for failures when I try to run a reset.
           (let [socket (mq/bound-socket ctx :dealer (config/render-url))]
             ;; The basic idea that I want to do is:

             (log/info "Kicking off heartbeat with renderer")
             (try
               (renderer-heartbeats channel ctx socket)

               (log/trace "Entering communications loop with renderer")
               ;; Important:
               ;; pretty much all operations involved here should
               ;; depend on some variant of alt and a timeout.
               (async/go
                 (try
                   (loop []
                     ;; Switch to dispatching messages between the channel
                     ;; and socket.
                     (let [to (async/timeout (config/server-timeout))
                           [msg src] (async/alts! [channel to])]
                       (if (not= src to)
                         (do
                           (mq/send socket (trnsltr/server->client msg))
                           (if (not= msg :client-exit)
                             ;; FIXME: Need to run this through the translator!
                             (do
                               ;; Receive a quit message from the channel: notify
                               ;; the renderer that it's time to exit.
                               ;; For now, just act as a straight conduit
                               (log/info (format "Sending {0} to the renderer" msg))
                               (async/close! channel)
                               ;; No recur...time to quit
                               )))
                         (do
                           ;; If we start getting communication timeouts,
                           ;; really want to be smart about it. This involves
                           ;; command/control stuff from the client.

                           ;; This next sequence is really in place because cljeromq's handling
                           ;; of messages (especially things like keywords) is (hopefully was)
                           ;; on very shaky ground.
                           (log/trace "Getting ready to transmit a keyword")
                           ;; FIXME: Do I want to do this at all?
                           (mq/send socket :server-delay)
                           (log/trace "How well did that work?")
                           (recur)))))
                   (finally
                     (mq/close! socket))))

               (catch Throwable ex
                 ;; Should only get into here if setting up the heartbeat failed.
                 ;; The go block should be running in its own thread. If it
                 ;; throws, we should get into java's "Unhandled exception" handler.
                 (mq/close! socket)
                 (throw))))))
