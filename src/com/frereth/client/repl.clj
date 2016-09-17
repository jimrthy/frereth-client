(ns com.frereth.client.repl
  "Probably obsolete after 1.7 or 8: have a port open to listen for an nrepl connection"
  (:require [cider.nrepl :refer (cider-nrepl-handler)]  ;; TODO: Make cider references go away
            [clojure.spec :as s]
            [clojure.tools.nrepl.server :as nrepl-server]
            [com.stuartsierra.component :as component]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Specs

(s/def ::stopper (s/fspec :args (s/cat)
                          :ret boolean?))
;; TODO: Surely I've already defined this in a dozen different places
(s/def ::port (and integer? pos? #(< % 65536)))
(s/def ::repl (s/keys :req-un [::port ::stopper]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Component

(defrecord REPL [port
                 stopper]
  component/Lifecycle
  (start
    [this]
    (assoc this stopper
           (nrepl-server/start-server :port port :handler cider-nrepl-handler)))

  (stop
    [this]
    (when stopper
      (nrepl-server/stop-server stopper))
    (assoc this stopper nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public

(s/fdef new-repl
        :args (s/cat :opts (s/keys :opt [::port])))
(defn new-repl
  [{:keys [::port]}]
  ;; This won't work because the schema requires the
  ;; stopper that will be generated during the call to (start)
  (map->REPL {:port port}))
