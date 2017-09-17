(ns dev
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.pprint :refer (pprint)]
            [clojure.repl :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.test :as test]
            [clojure.tools.namespace.repl :refer (refresh refresh-all)]
            ;; Q: Do I want something like this or spyscope?
            ;;[clojure.tools.trace :as trace]
            [com.frereth.client.connection-manager :as con-man]
            [com.frereth.client.system :as system]
            [com.frereth.common.util :as util]
            [integrant.repl :refer (clear go halt init reset reset-all)]))

(def +frereth-component+
  "Just to help me track which REPL is which"
  'client)

(defn ctor
  []
  (system/init {}))
(integrant.repl/set-prep! ctor)
