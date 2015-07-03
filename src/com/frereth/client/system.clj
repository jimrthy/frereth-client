(ns com.frereth.client.system
  "TODO: Almost all of this should go away"
  (:require [cljeromq.core :as mq]
            [clojure.core.async :as async]
            [com.frereth.client
             [config :as cfg]]
            [com.frereth.common.util :as util]
            [com.stuartsierra.component :as component]
            [component-dsl.system :as cpt-dsl]
            [schema.core :as s]
            #_[taoensso.timbre :as log])
  (:import [com.stuartsierra.component SystemMap]))

(s/defn init :- SystemMap
  [{:keys [ctx-thread-count
           auth-url
           local-control-url
           local-control-chan]
    :or {ctx-thread-count (-> (util/core-count) dec)
         ;; Here are prime examples of why my component-dsl needs to support update-in
         ;; TODO: Don't allow default channels here
         ;; These really need to be their own component,
         ;; supplied by caller.
         ;; But this is convenient for dev work
         local-control-chan (async/chan)
         ;; Server's expecting this to be inproc.
         ;; For the sake of safety/security, should probably go that route.
         ;; For now, I'm trying to come up with something vaguely interesting.
         ;; Which could just as easily happen over the local-action-sock
         local-control-url {:protocol :inproc
                            :address "super-secret-control"}
         auth-url {:address "127.0.0.1"
                   :protocol :tcp
                   :port (cfg/auth-port)}}
    :as overrides}]
  (set! *warn-on-reflection* true)

  (let [struct '{:auth-sock com.frereth.common.zmq-socket/ctor
                 :ctx com.frereth.common.zmq-socket/ctx-ctor
                 :control-message-loop com.frereth.common.async-zmq/ctor  ; definitely for local server
                 :controller-socket com.frereth.common.zmq-socket/ctor
                 :message-loop-manager com.frereth.client.manager/ctor}
        depends {:auth-sock [:ctx]
                 :controller-socket [:ctx]
                 ;; FIXME: This needs to go away
                 ;; Provide it as part of the AUTH socket handshake, if
                 ;; the Subject has any sort of Control authorization
                 :control-message-loop {:ex-sock :controller-socket}
                 :message-loop-manager {:auth-sock :auth-sock
                                        :local-controller :control-message-loop}}
        descr {:structure struct
               :dependencies depends}]
    (cpt-dsl/build descr
                   {:auth-sock {:sock-type :req
                                :url auth-url}
                    :controller-socket {:sock-type :dealer
                                        :url local-control-url}
                    :control-message-loop {:_name "control-loop"
                                           :in-chan local-control-chan}})))

(comment (defn init
           [overriding-config-options]
           (set! *warn-on-reflection* true)

           (let [cfg (into (config/defaults) overriding-config-options)]
             ;; TODO: I really need to configure logging...don't I?
             (-> (component/system-map
                  :config cfg
                  :done (promise)
                  :renderer-handler (comm/new-renderer-handler cfg)
                  :renderer-url (comm/new-renderer-url cfg)
                  :repl (repl/new-repl {:port (:nrepl-port cfg)})
                  :server (comm/new-server)
                  :server-url (comm/default-server-url)
                  :zmq-context (comm/new-context (:zmq-thread-count cfg)))
                 (component/system-using
                  {:renderer-handler {:config :config
                                      :context :zmq-context
                                      :renderer-url :renderer-url}
                   :repl [:config]
                   :server {:config :config
                            :context :zmq-context
                            :url :server-url}
                   :zmq-context [:config]})))))
