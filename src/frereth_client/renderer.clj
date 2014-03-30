(ns frereth-client.renderer
  (:require [clojure.core.async :as async]
            [cljeromq.core :as mq]
            [frereth-client.config :as config]
            [frereth-client.translator :as trnsltr]
            [ribol.core :refer :all]
            [taoensso.timbre :as log])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn renderer-heartbeats
  "Start exchanging heartbeats with the renderer over
socket, until we get a notice over the channel that
we've established a connection with the server (or,
for that matter, that such a connection failed)."
  [control-chan ctx]
  ;; Don't bother with anything fancy for now.
  ;; Realistically, this seems pretty tricky and fraught
  ;; with peril. Which is why I'm trying to get a rope thrown
  ;; across first.

  ;; This conflicts with the PULL socket (:renderer-puller)
  ;; that was created in communicator.clj.
  ;; Actually, this entire thing might well be obsolete.
  (comment
    (throw (RuntimeException. "This is broken."))
    (async/go
     (let [url (config/render-url-from-renderer)
           sock (mq/bound-socket ctx :rep url)]
       (log/info "Listening for renderer heartbeat on " url)
       (mq/with-poller [renderer-heartbeat ctx sock]
         (loop [n 0
                previous-report-time (System/nanoTime)]
           (let [incoming-heartbeats
                 (mq/poll renderer-heartbeat (config/renderer-pulse))]
             (when (> incoming-heartbeats 0)
               (log/info "Responding to renderer")

               (loop [input (mq/recv sock)]
                 (if (= input :PING)
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
                   (mq/send sock :PONG)

                   (do
                     (mq/send sock :ACK)
                     ;; FIXME: There's something that's completely and totally wrong here.
                     ;; I'm listening and writing to the same channel here. This is really
                     ;; just a feedback loop.
                     ;; Really need to re-think this architecture:
                     ;; Right now, my goesintas are just reading from the goesouttas.
                     (async/>!! control-chan (trnsltr/client->server input))
                     (raise :start-here)))
                 (raise :not-implemented)
                 (when (mq/recv-more? sock)
                   (recur (mq/recv sock))))))

           ;; Seems like there must be an easier way to poll for an existing message.
           ;; 1 ms here isn't all that big a deal, but this still seems like a waste
           (let [to (async/timeout 1)]
             (let [[v ch] (alts! [control-chan] :default nil)]
               (when (= ch :default)
                 ;; FIXME: Each call to this can take > 1 ms. Don't do this sort of timing
                 ;; for real
                 (let [current-time (System/nanoTime)
                       next-frame  (> (- current-time previous-report-time)
                                      15000000000)]
                   (if next-frame
                     (do
                       (log/info "Frame #" n)
                       (recur (inc n) current-time))
                     (recur n previous-report-time))))))))))))

(defn build-proxy
  "This function kicks off background threads to translate communications
between the front-end renderer and the server.

Naive in that it ignores that multiple servers might (and probably
should) be involved.

More naive in that there's no possibility of cleanup.

For that matter, I most definitely do expect multiple renderers (in
the form of multiple windows) as well."
  [ctx channel]
  ;; FIXME: Build socket during start. Stop it and release it
  ;; for GC during stop. The current approach is just begging
  ;; for failures when I try to run a reset.

  ;; The entire idea of using push/pull here crashes and burns when I start
  ;; dealing with multiple renderers. I *am* gonna need that...but not
  ;; yet.


  (renderer-heartbeats channel ctx)

  (log/info "Entering communications loop with renderer")
  (async/go
   ;; Important:
   ;; pretty much all operations involved here should
   ;; depend on some variant of alt and a timeout.
   ;; Also important: go returns a channel. Does this work
   ;; get discarded if I ignore that?
   ;; This part is starting to feel extremely procedural, otherwise.
   (let [out-socket (mq/bound-socket ctx :rep (config/render-url-from-server))]
     ;; The basic idea that I think I want to do is:
     (try
       ;; I really want to do this as an ISeq.
       ;; That would probably be a mistake: an ISeq is
       ;; defined as immutable and persistent, and therefore
       ;; thread-safe. This interface is inherently stateful.
       (loop [to (async/timeout (config/server-timeout))]
         ;; Switch to dispatching messages between the channel
         ;; and socket.
         (let [[msg src] (async/alts! [channel to])]
           (if (not= src to)
             (do
               (log/debug (format "Sending {0} to the renderer" msg))
               (raise-on [IllegalStateException :needs-recv]
                         (mq/send out-socket (trnsltr/server->client msg))))
             (do
               ;; If we start getting communication timeouts,
               ;; really want to be smart about it. This involves
               ;; command/control stuff from the client.

               ;; This next sequence is really in place because cljeromq's handling
               ;; of messages (especially things like keywords) is (hopefully was)
               ;; on very shaky ground.
               (log/debug "Getting ready to transmit a keyword")
               ;; FIXME: Do I want to do this at all?
               ;; At the very most, I'm pretty sure I don't want to send it
               ;; more than once every few seconds.
               (raise-on [IllegalStateException :delayed-needs-received]
                         (mq/send out-socket :server-delay))
               (log/debug "How well did that work?")))

           ;; Because I'm using REQ/REP.
           ;; Don't care about the contents at all.
           ;; Do I?
           ;; For now, at least, I really just want to reset
           ;; the socket's state machine for when I need to
           ;; write to it again.
           (raise-on [IllegalStateException :missing-send]
                     (mq/recv out-socket))

           (if (not= msg :client-exit)
             (recur (async/timeout (config/server-timeout)))
             ;; No recur...time to quit
             (async/close! channel))))
       (finally
         (mq/close! out-socket))))))
