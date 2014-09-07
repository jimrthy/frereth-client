(ns frereth-client.config
  (:require [ribol.core :refer (raise)]
            [schema.core :as s]))

;; These look like globals. But they're really just magic
;; numbers that (realistically) should be loaded from some
;; sort of "real" configuration (like a database) instead.

(set! *warn-on-reflection* true)

;; Wrapping them in functions makes it trivial to swap out
;; something more useful when there's a reason.

(defn control-port [] 7839)

(defn renderer-protocol [] "inproc")
(defn renderer-address [] "renderer<->client")
(defn renderer-port [] (comment 7840) nil)

(defn server-port [] 7841)
(defn <-renderer-port [] 7842)

(defn nrepl-port [] 7843)

(defn render-url-from-renderer []
  "Messages coming in from the renderer (AKA the View)
come in through here"
  (raise [:not-implemented {:reason "Not sure what to do"}])
  (str "tcp://localhost:" (<-renderer-port)))

;; I have my doubts about whether I can get away with
;; just one socket connected to the server(s). Or even
;; one per server. But I'm going to try to make that
;; happen.
(defn local-server-url []
  (str "tcp://localhost:" (server-port)))

(defn zmq-thread-count
  []
  (let [cpu-count (.availableProcessors (Runtime/getRuntime))]
    ;; This is the absolute maximum that's ever recommended.
    ;; 1 seems like a more likely option.
    ;; Seems to depend on bandwidth
    (dec cpu-count)))

(defn server-timeout
  "How long should the client wait, as a baseline, before
notifying the renderer that there are communications issues 
with the server?"
  []
  50)

(defn renderer-pulse
  "How many milliseconds should the heartbeat listener wait for the renderer,
between checking for an exit signal"
  []
  50)

(defn defaults
  []
  {:nrepl-port (nrepl-port)
   :renderer-protocol (renderer-protocol)
   :renderer-address (renderer-address)
   :renderer-port (renderer-port)
   :zmq-thread-count (zmq-thread-count)})
