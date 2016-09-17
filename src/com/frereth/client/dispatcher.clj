(ns com.frereth.client.dispatcher
  "For shuffling messages among the server and attached renderers.

This is really only interesting because it needs to support multiple protocols
and backwards compatibility."
  (:require [clojure.spec :as s]
            ;; Currently just needed for spec
            [com.frereth.common.communication]
            [com.stuartsierra.component :as component]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Specs

(s/def ::render-protocol-version :com.frereth.common.communication/protocol-version)
(s/def ::server-protocol-version :com.frereth.common.communication/protocol-version)

;; For now, at least, this is the SESSION_ID that immutant assigns when a client makes
;; a web socket connection.
(s/def ::renderer-id string?)

(s/def ::renderer-session-pair (s/keys :req [::renderer-id
                                             :com.frereth.client.world-manager/session-id-type]))
(s/def ::world-session-pair (s/keys :req [:com.frereth.client.world-manager/world-id-type
                                          :com.frereth.client.world-manager/session-id-type]))
(s/def ::session-details (s/keys :req [::renderer-session-pair
                                       ::world-session-pair]))

;; Something like s/every-kv would be safer, if we're talking about big data.
;; Which we might be.
;; Yet again: get the basics working first.
(s/def ::connected-session (s/map-of :com.frereth.client.world-manager/sesion-id-type
                                     ::session-details
                                     :conform-keys true))

(s/def ::connected-session-atom (fn [x]
                                  (let [connected-session @x]
                                    ;; Q: Is this the way it's supposed to work?
                                    ;; A: Almost definitely not.
                                    ;; For example: shouldn't throw if x isn't an IDeref.
                                    ;; And I'm pretty sure that this is just a predicate
                                    ;; that's supposed to return true/false.
                                    (s/conform ::connected-session connected-session))))

(s/def :unq/Dispatcher
  (s/keys ::req-un [::conected-session-atom ::render-protocol-version ::server-protocol-version]
          ::opt-un []))

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
    [connected-session-atom
     render-protocol-version
     server-protocol-version]
  :load-ns true   ; Q: Is this new?
  component/Lifecycle
  (start [this]
    (let [connected-session-atom (or connected-session-atom
                                     (atom {}))]
      (assoc this :connected-session-atom connected-session-atom)))
  (stop [this]
    ;; Q: Does it make sense to discard any/all connected sessions here?
    ;; A: Well, it seems like a very obvious thing to do. But it might be
    ;; convenient to leave it be until this part is solidly debugged.
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
