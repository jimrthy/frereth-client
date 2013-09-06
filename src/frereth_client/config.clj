(ns frereth-client.config)

;; These look like globals. But they're really just magic
;; numbers that (realistically) should be loaded from some
;; sort of "real" configuration (like a database) instead.

;; Wrapping them in functions makes it trivial to swap out
;; something more useful when there's a reason.
(defn control-port [] 7839)
(defn renderer-port [] 7840)
(defn server-port [] 7841)

(defn render-url []
  (str "tcp://localhost:" (renderer-port)))

(defn server-url []
  (str "tcp://localhost:" (server-port)))
