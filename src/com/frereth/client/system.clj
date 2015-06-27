(ns com.frereth.client.system
  "TODO: Almost all of this should go away"
  (:require [cljeromq.core :as mq]
            [clojure.core.async :as async]
            [com.frereth.client
             [communicator :as comm]
             [config :as config]
             [renderer :as render]
             [repl :as repl]]
            [com.stuartsierra.component :as component]
            [component-dsl.system :as cpt-dsl]
            [schema.core :as s]
            #_[taoensso.timbre :as log])
  (:import [com.stuartsierra.component SystemMap]))

(s/defn init :- SystemMap
  [{:keys [ctx-thread-count
           local-action-url
           local-control-url]
    ;; TODO: Switch to using common.util's core-count instead
    :or {ctx-thread-count (-> (Runtime/getRuntime) .availableProcessors dec)
         ;; Here are prime examples of why my component-dsl needs to support update-in
         local-action-url {:port 7841}
         ;; Server's expecting this to be inproc.
         ;; For the sake of safety/security, should probably go that route.
         ;; For now, I'm trying to come up with something vaguely interesting.
         ;; Which could just as easily happen over the local-action-sock
         local-control-url {:protocol :inproc
                            :address "super-secret-control"}}
    :as overrides}]
  (set! *warn-on-reflection* true)

  (let [struct '{:local-message-loop com.frereth.common.async-zmq/ctor  ; mainly for local server
                 :action-socket com.frereth.common.zmq-socket/ctor
                 :control-message-loop com.frereth.common.async-zmq/ctor  ; definitely for local server
                 :controller-socket com.frereth.common.zmq-socket/ctor
                 :message-loop-manager com.frereth.client.manager/ctor}
        depends {:local-message-loop {:ex-sock :action-socket}
                 :control-message-loop {:ex-sock :controller-socket}
                 :message-loop-manager {:local-action :local-message-loop
                                        :action-socket :action-socket
                                        :local-controller :control-message-loop
                                        :controller-socket :controller-socket}}
        descr {:structure struct
               :dependencies depends}
        ;; TODO: This is broken. Move it into its own Component.
        ctx (mq/context ctx-thread-count)]
    (cpt-dsl/build descr
                   {:action-socket {:ctx ctx
                                    :url local-action-url}
                    :controller-socket {:ctx ctx
                                        :url local-control-url}})))

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
