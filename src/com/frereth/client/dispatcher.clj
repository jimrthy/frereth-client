(ns com.frereth.client.dispatcher
  "For shuffling messages among the server and attached renderers.

This is really only interesting because it needs to support multiple protocols
and backwards compatibility"
  (:require [clojure.spec :as s]
            [com.stuartsierra.component :as component]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Specs

(defrecord Dispatcher
    [render-protocol-version
     server-protocol-version]
  component/Lifecycle
  (start [this]
    this)
  (stop [this]
    this))

(s/def ::major int?)
(s/def ::minor int?)
(s/def ::build int?)
(s/def ::name #{:lolcatz})
(s/def ::render-protocol-version (s/keys :req [::name ::major ::minor ::build]))
(s/def ::server-protocol-version (s/keys :req [::name ::major ::minor ::build]))

(s/def :unq/Dispatcher
  (s/keys ::req-un [::render-protocol-version ::server-protocol-version]
          ::opt-un []))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/fdef server->
        :args (s/cat :this :unq/Dispatcher
                     :msg :com.frereth.common.communication/router-message)
        :ret boolean?)
(defn server->
  "Msg just arrived from the server. Need to forward it along to the appropriate Renderer.

Can't do that here, or we wind up with circular dependencies between this and world-manager.

This needs to translate the message from the server-side comms protocol to what the renderer
consumes and do just enough analysis to decide which renderer connection receives the message."
  [this msg]
  (throw (ex-info "Not Implemented" {})))
