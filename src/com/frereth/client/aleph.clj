(ns com.frereth.client.aleph
  (:require [aleph.tcp :as tcp]
            [com.frereth.common.aleph :as common]
            [manifold.deferred :as d]))

(defn client
  [host port]
  (d/chain (tcp/client {:host host, :port port})
           #(common/simplest %)))
