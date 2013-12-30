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

(defn messaging-threads
  "How many threads should the messaging system use?"
  []
  ;; For now, just start with the default max
  ;; recommended by 0mq:
  ;; core_count - 1
  (dec (.availableProcessors (Runtime/getRuntime))))

;; Messages that originate in the client or server which
;; go out to update the view get multicast through here.
(defn render-url-from-server []
  (str "tcp://localhost:" (->renderer-port)))

(defn render-url-from-renderer []
  "Messages coming in from the renderer (AKA the View)
come in through here"
  (str "tcp://localhost:" (<-renderer-port)))

;; I have my doubts about whether I can get away with
;; just one socket connected to the server(s). Or even
;; one per server. But I'm going to try to make that
;; happen.
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
