(ns frereth-client.communicator
  (:require [cljeromq.core :as mq]
            [clojure.tools.logging :as log]
            [frereth-client.config :as config])
  (:gen-class))

"Can I handle all of the networking code in here?
Well, obviously I could. Do I want to?"

(defn init []
  {:context (atom nil)
   :local-server-socket (atom nil)})

(defn start [system]
  ;; Q: Is there any possible reason to have more than 1 thread here?
  ;; A: Of course. Should probably make this configurable.
  ;; Which, realistically, means that there needs to be a way to
  ;; change this on the fly.
  ;; Q: How can I tell whether more threads might help?
  (when @(:context system)
    (throw (RuntimeException. "Trying to restart networking on a system that isn't dead")))

  (let [ctx (mq/context 1)]
    (reset! (:context system) ctx)
    (reset! (:local-server-socket system) (mq/connected-socket ctx :req
                                                               (config/server-url)))
    ;; N.B. There's a very unfortunate symmetry here.
    ;; renderer handles its own socket, while server defers here.
    ;; TODO: Pick one or the other, then stick to it.
    ;; If nothing else...what happens when multiple server connections get involved?
    )
  system)

(defn get-context [system]
  @(:context system))

(defn stop [system]
  (log/trace "Closing messaging context")
  (when-let [ctx-atom (:context system)]
    (when-let [ctx @ctx-atom]
      (when-let [local-server-socket-atom (:local-server-socket system)]
        (when-let [local-server-socket @local-server-socket-atom]
          (mq/close local-server-socket)
          (reset! local-server-socket-atom nil)))
      (mq/terminate ctx))))
