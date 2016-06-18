(ns frereth-client.system
  (:require [clojure.core.async :as async]
            ;; Q: Debug only??
            [clojure.tools.trace :as trace]
            [com.stuartsierra.component :as component]
            [frereth-client.config :as config]
            [frereth-client.communicator :as comm]
            [frereth-client.renderer :as render]
            [frereth-client.repl :as repl]
            [taoensso.timbre :as log]))

(defn init
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
          :zmq-context [:config]}))))
