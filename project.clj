
(defproject frereth-client "0.1.0-SNAPSHOT"
  :description "You might think of this as the Web Kit piece, although that's really far too grandiose."
  :dependencies [[byte-transforms "0.1.3"]
                 ;;[cider/cider-nrepl "0.8.1"]
                 [com.postspectacular/rotor "0.1.0"]
                 [com.stuartsierra/component "0.2.2"]
                 [com.taoensso/timbre "3.3.1"]
                 [im.chit/ribol "0.4.0"]
                 ;[jimrthy/cljeromq "0.1.0-SNAPSHOT"]  ; Q: Do I *really* want to use this?
                 [org.clojure/tools.logging "0.3.1"]  ; Q: why am I using this?
                 [org.clojure/clojure "1.7.0-alpha1"]
                 [org.clojure/core.async "0.1.338.0-5c5012-alpha"]
                 [org.clojure/tools.nrepl "0.2.6"]
                 [org.zeromq/cljzmq "0.1.4"]
                 [prismatic/plumbing "0.3.5"]
                 [prismatic/schema "0.3.3"]]
  :jvm-opts [~(str "-Djava.library.path=/usr/local/lib:" (System/getenv "LD_LIBRARY_PATH"))]
  :license {:name "Eclipse Public License"
            :url "http://http://www.eclipse.org/legal/epl-v10.html"}
  :main frereth-client.core
  ;; Q: this gets included in production, doesn't it?
  ;; TODO: Look @ https://github.com/clojure-emacs/cider-nrepl
  ;; It has instructions for adding this as a dependency,
  ;; along with the specific middleware to repl-options
  :plugins [[cider/cider-nrepl "0.8.1"]]
  :profiles {:uberjar {:aot :all}
             :dev {:source-paths ["dev"]
                   :dependencies [[clj-ns-browser "1.3.1"]
                                  [midje "1.6.3" :exclusions [joda-time]]
                                  [org.clojure/tools.namespace "0.2.7"]
                                  [org.clojure/java.classpath "0.2.2"]
                                  [com.cemerick/pomegranate "0.3.0" :exclusions [org.codehaus.plexus/plexus-utils]]]}}
  :repl-options {:init-ns user
                 :welcome (println "Run (dev) to start")}
  ;; Q: What's this needed for?
  ;; (I think it's core.async, but I'm not sure)
  ;; TODO: Take it away and see what breaks
  :repositories {"sonatype-nexus-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"}
  :url "http://frereth.com")
