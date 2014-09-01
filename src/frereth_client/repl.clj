(ns frereth-client
  (:require [cider.nrepl :refer (cider-nrepl-handler)]
            [clojure.tools.nrepl.server :as nrepl-server]
            [com.stuartsierra.component :as component]
            [core.schema :as s]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema

(defrecord REPL [port :- s/Int stopper]
  component/Lifecycle

  (start
    [this]
    (assoc this stopper
           (nrepl-server/start-server :port port :handler cider-nrepl-handler)))

  (stop
    [this]
    (nrepl-server/stop-server stopper)
    (assoc this stopper nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public

(defn new-repl
  [params]
  ;; This won't work because the schema requires the
  ;; stopper that will be generated during the call to (start)
  (strict-map->REPL params))
