(ns frereth-client.config)

;; These look like globals. But they're really just magic
;; numbers that (realistically) should be loaded from some
;; sort of "real" configuration (like a database) instead.

;; Wrapping them in functions makes it trivial to swap out
;; something more useful when there's a reason.
(defn control-port [] 7839)
(defn ->renderer-port [] 7840)
(defn server-port [] 7841)
(defn <-renderer-port [] 7842)

;;; N.B. Want to allow remote renderers the possibility to connect.
;;; That really gets into advanced functionality, though.
(defn render-url-from-server []
  (str "tcp://localhost:" (->renderer-port)))

(defn render-url-from-renderer []
  (str "tcp://localhost:" (<-renderer-port)))

(defn local-server-url []
  (str "tcp://localhost:" (server-port)))

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
