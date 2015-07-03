(ns com.frereth.client.system
  "TODO: Almost all of this should go away"
  (:require [cljeromq.core :as mq]
            [clojure.core.async :as async]
            [com.frereth.client
             [config :as cfg]
             #_[renderer :as render]
             #_[repl :as repl]]
            [com.frereth.common.util :as util]
            [com.stuartsierra.component :as component]
            [component-dsl.system :as cpt-dsl]
            [schema.core :as s]
            #_[taoensso.timbre :as log])
  (:import [com.stuartsierra.component SystemMap]))

(s/defn init :- SystemMap
  [{:keys [ctx-thread-count
           local-action-url
           local-action-chan
           local-auth-url
           local-auth-chan
           local-control-url
           local-control-chan]
    :or {ctx-thread-count (-> (util/core-count)
 dec)
         ;; Here are prime examples of why my component-dsl needs to support update-in
         local-action-url {:protocol :tcp
                           :address "127.0.0.1"
                           :port (cfg/action-port)}
         ;; TODO: Don't allow default channels here
         ;; These really need to be their own component,
         ;; supplied by caller.
         ;; But this is convenient for dev work
         local-action-chan (async/chan)
         local-control-chan (async/chan)
         local-auth-chan (async/chan)
         ;; Server's expecting this to be inproc.
         ;; For the sake of safety/security, should probably go that route.
         ;; For now, I'm trying to come up with something vaguely interesting.
         ;; Which could just as easily happen over the local-action-sock
         local-control-url {:protocol :inproc
                            :address "super-secret-control"}
         local-auth-url {:address "127.0.0.1"
                         :protocol :tcp
                         :port (cfg/auth-port)}}
    :as overrides}]
  (set! *warn-on-reflection* true)

  (let [struct '{:ctx com.frereth.common.zmq-socket/ctx-ctor
                 :local-message-loop com.frereth.common.async-zmq/ctor  ; mainly for local server
                 :action-socket com.frereth.common.zmq-socket/ctor
                 :auth-socket com.frereth.common.zmq-socket/ctor
                 :auth-loop com.frereth.common.async-zmq/ctor
                 :control-message-loop com.frereth.common.async-zmq/ctor  ; definitely for local server
                 :controller-socket com.frereth.common.zmq-socket/ctor
                 :message-loop-manager com.frereth.client.manager/ctor}
        depends {:action-socket [:ctx]
                 :local-message-loop {:ex-sock :action-socket}
                 :auth-socket [:ctx]
                 :auth-loop {:ex-sock :auth-socket}
                 :controller-socket [:ctx]
                 :control-message-loop {:ex-sock :controller-socket}
                 :message-loop-manager {:local-action :local-message-loop
                                        :auth-loop :auth-loop
                                        :local-controller :control-message-loop}}
        descr {:structure struct
               :dependencies depends}]
    (cpt-dsl/build descr
                   {:action-socket {:sock-type :dealer
                                    :url local-action-url}
                    :local-message-loop {:_name "action-loop"
                                         :in-chan local-action-chan}
                    :auth-socket {:sock-type :dealer
                                  :url local-auth-url}
                    :auth-loop {:_name "auth-loop"
                                :in-chan local-auth-chan}
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
