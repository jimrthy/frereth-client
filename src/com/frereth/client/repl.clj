(ns com.frereth.client.repl
  "Open an nrepl connection to aid debugging"
  (:require [cider.nrepl :refer (cider-nrepl-handler)]  ;; TODO: Make cider references go away
            [clojure.spec.alpha :as s]
            [clojure.tools.nrepl.server :as nrepl-server]
            [integrant.core :as ig]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Specs

(s/def ::stopper (s/fspec :args (s/cat)
                          :ret boolean?))
;; TODO: Surely I've already defined this in a dozen different places
(s/def ::port (and integer? pos? #(< % 65536)))
(s/def ::repl (s/keys :req [::port]
                      :opt [::stopper]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Component

(defmethod ig/init-key ::repl
  [_ {:keys [::port
             :as this]}]
  (assoc this ::stopper
         (nrepl-server/start-server :port port :handler cider-nrepl-handler)))

(defmethod ig/halt-key! ::repl
  [_ {:keys [::stopper]}]
  (when stopper
    (nrepl-server/stop-server stopper)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public
