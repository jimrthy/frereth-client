(ns com.frereth.client.dispatcher
  "For shuffling messages among the server and attached renderers.

This is really only interesting because it needs to support multiple protocols
and backwards compatibility.

Which is YAGNI...for this pass, this really should just be a piece of the
ConnectionManager."
  (:require [clojure.spec :as s]
            [com.stuartsierra.component :as component]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Specs

(s/def ::render-protocol-version :com.frereth.common.communication/protocol-version)
(s/def ::server-protocol-version :com.frereth.common.communication/protocol-version)

(s/def :unq/Dispatcher
  (s/keys ::req-un [::render-protocol-version ::server-protocol-version]
          ::opt-un []))

;; For now, at least, this is the SESSION_ID that immutant assigns when a client makes
;; a web socket connection
(s/def ::renderer-id string?)

(s/def ::connected-session-atom (fn [x]
                                  (let [connected-session @x]
                                    ;; Q: Is this the way it's supposed to work?
                                    (s/conform ::connected-session connected-session))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Component

;;; One Dispatcher per server connection
(defrecord Dispatcher
    ;; this needs an atom of connected renderer sessions.
    ;; They're all connected to the same Server, by definition.
    ;; The renderer-id and :world-id should be good enough keys
    ;; to find the server-side cookie.
    ;; Probably need to include the renderer-id as an encrypted
    ;; session cookie so we can find that on the response.
    ;; Or should I just keep another map of server/world session
    ;; IDs in here to reduce the risk of other malicious clients
    ;; from hijacking these sessions?
    [connected-sessions
     render-protocol-version
     server-protocol-version]
  :load-ns true   ; Q: Is this new?
  component/Lifecycle
  (start [this]
    (let [connected-sessions (or connected-sessions
                                 (atom {}))])
    this)
  (stop [this]
    this))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/fdef renderer->
        :args (s/cat :this :unq/Dispatcher
                     :renderer-id ::renderer-id
                     :world-id :com.frereth.client.world-manager/world-id-type
                     :msg :com.frereth.common.communication/message))
(defn renderer->
  [this renderer-id world-id msg]
  (let []
    (throw (ex-info "Not Implemented" {}))))

(s/fdef server->
        :args (s/cat :this :unq/Dispatcher
                     ;; This is wrong. We're on the dealer side of this socket
                     :msg :com.frereth.common.communication/router-message)
        :ret boolean?)
(defn server->
  "Msg just arrived from the server. Need to forward it along to the appropriate Renderer.

Can't do that here, or we wind up with circular dependencies between this and world-manager.

This needs to translate the message from the server-side comms protocol to what the renderer
consumes and do just enough analysis to decide which renderer connection receives the message."
  [this msg]
  (throw (ex-info "Not Implemented" {})))
