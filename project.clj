(defproject frereth-client "0.1.0-SNAPSHOT"
  :description "You might think of this as the Web Kit piece, although that's really far too grandiose."
  :url "http://frereth.com"
  :license {:name "Affero General Public License"
            :url "http://www.gnu.org/licenses/agpl.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 ;; What are the odds that this is actually useful?
                 [org.clojure/tools.nrepl "0.2.3"]
                 [org.zeromq/cljzmq "0.1.1"]]
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[org.clojure/tools.namespace "0.2.3"]
                                  [org.clojure/java.classpath "0.2.0"]
                                  ;; What was I using this next piece for?
                                  [org.clojure/tools.logging "0.2.6"]
                                  [expectations "1.4.49"]]}}
  :main frereth-client.core)
