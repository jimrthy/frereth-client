(ns frereth-client.repl
  (:require [cider.nrepl :refer (cider-nrepl-handler)]
            [clojure.tools.nrepl.server :as nrepl-server]
            [com.stuartsierra.component :as component]
            [schema.core :as s]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema

(s/defrecord REPL [port :- s/Int 
                    stopper :- (s/make-fn-schema s/Bool [[]])]
  component/Lifecycle

  (start
    [this]
    (assoc this stopper
           (comment (nrepl-server/start-server :port port :handler cider-nrepl-handler))))

  (stop
    [this]
    (when stopper
      (nrepl-server/stop-server stopper))
    (assoc this stopper nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public

(defn new-repl
  [{:keys [port]}]
  ;; This won't work because the schema requires the
  ;; stopper that will be generated during the call to (start)
  (map->REPL {:port port}))
