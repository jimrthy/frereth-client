(ns frereth-client.system
  (:gen-class)
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]            
            ;; Q: Debug only??
            [clojure.tools.trace :as trace]
            [com.stuartsierra.component :as component]
            [frereth-client.communicator :as comm]
            [frereth-client.config :as config]
            [frereth-client.renderer :as render]
            [frereth-client.repl :as repl]
            [frereth-client.server :as srvr])
  (:use [frereth-client.utils]))

(defn init
  [overriding-config-options]
  (let [cfg (into (config/defaults) overriding-config-options)]
    ;; TODO: I really need to configure logging...don't I?
    (-> (component/system-map
         :config cfg
         :done (promise)
         :renderers (comm/new-renderer cfg)
         :repl (repl/new-repl {:port (:nrepl-port cfg)})
         :server (comm/new-server)
         :server-url (comm/default-server-url)
         :zmq-context (comm/new-context {:thread-count (:zmq-thread-count cfg)}))
        (component/system-using
         {:renderers {:config :config
                      :context :zmq-context}
          :repl {:config :config}
          :server {:config :config
                   :context :zmq-context
                   :url :server-url}
          :zmq-context {:config :config-options}}))))




