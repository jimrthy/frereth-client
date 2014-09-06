(ns frereth-client.config)

(defn version-tag
  "Because lein install doesn't seem to actually be accomplishing anything"
  []
  "2f902ef7dcd05136580f8e0250f507be624c57aa")

(defn control-port [] 7839)

(defn renderer-protocol [] "inproc")
(defn renderer-address [] "renderer<->client")
(defn renderer-port [] (comment 7840) nil)

(defn server-port [] 7841)
(defn nrepl-port [] 7842)

(comment (defn render-url []
           (str "tcp://localhost:" (renderer-port)))

         (defn local-server-url []
           (str "tcp://localhost:" (server-port))))

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
   :renderer-protocol (renderer-protocol)
   :renderer-address (renderer-address)
   :renderer-port (renderer-port)
   :zmq-thread-count (zmq-thread-count)})
