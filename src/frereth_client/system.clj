(ns frereth-client.system
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]            
            #_[clojure.tools.nrepl.server :as nrepl]
            [com.stuartsierra.component :as component]
            [frereth-client
             [communicator :as comm]
             [config :as config]
             [renderer :as render]
             [repl :as repl]
             #_[server :as server]]
            [taoensso.timbre :as log])
  (:gen-class))

(defn init
  "Returns a new instance of the whole application"
  [overriding-config-options]
  (log/info "Initializing Frereth Client")
  (set! *warn-on-reflection* true)

  (let [cfg (into (config/defaults) overriding-config-options)]
    ;; TODO: I really need to configure logging...don't I?
    (-> (component/system-map
         :config cfg
         ;; For connecting over nrepl...I have severe doubts about just
         ;; leaving this in place and open.
         ;; Oh well. It's an obvious back door and easy enough to close.
         :controller (atom nil)
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

