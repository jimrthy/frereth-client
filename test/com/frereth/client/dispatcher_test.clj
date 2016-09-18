(ns com.frereth.client.dispatcher-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.frereth.client.dispatcher :as disp]))

(defn create-echo-server
  []
  (throw (ex-info "Start here" {})))

(deftest dispatch-echos
  ;; Q: Why 3?
  (testing "3 connections to an Echo server"
    (let [server (create-echo-server)]
      (is false "Start here"))))
