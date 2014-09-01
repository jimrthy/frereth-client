(ns frereth-client.config)

;; These look like globals. But they're really just magic
;; numbers that (realistically) should be loaded from some
;; sort of "real" configuration (like a database) instead.

;; Wrapping them in functions makes it trivial to swap out
;; something more useful when there's a reason.
(defn control-port [] 7839)
(defn renderer-port [] 7840)
(defn server-port [] 7841)
(defn nrepl-port [] 7842)

(defn render-url []
  (str "tcp://localhost:" (renderer-port)))

(defn local-server-url []
  (str "tcp://localhost:" (server-port)))

(defn server-timeout
  "How long should the client wait, as a baseline, before
notifying the renderer that there are communications issues 
with the server?"
  []
  50)

(defn zmq-thread-count
  []
  (let [cpu-count (.availableProcessors (Runtime/getRuntime))]
    ;; This is the absolute maximum that's ever recommended.
    ;; 1 seems like a more likely option.
    ;; Seems to depend on bandwidth
    (dec cpu-count)))

(defn defaults
  []
  {:nrepl-port (nrepl-port)
   :zmq-thread-count (zmq-thread-count)})
