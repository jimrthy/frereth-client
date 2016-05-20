(defproject com.frereth/client "0.1.0-SNAPSHOT"
  :description "You might think of this as the Web Kit piece, although that's really far too grandiose."
  :dependencies [[byte-transforms "0.1.4"]  ;; Q: Does this make sense?
                 [com.frereth/common "0.0.1-SNAPSHOT"]
                 [com.postspectacular/rotor "0.1.0"]
                 #_[org.clojure/java.classpath "0.2.2"]
                 [org.clojure/tools.logging "0.3.1"]  ; Q: why am I using this?
                 [org.clojure/tools.nrepl "0.2.12"]]

  :jvm-opts [~(str "-Djava.library.path=/usr/local/lib:" (System/getenv "LD_LIBRARY_PATH"))
             "-Djava.awt.headless=true"]
  :license {:name "Eclipse Public License"
            :url "http://http://www.eclipse.org/legal/epl-v10.html"}
  ;:main com.frereth.client.core
  ;; Q: this gets included in production, doesn't it?
  ;; TODO: Look @ https://github.com/clojure-emacs/cider-nrepl
  ;; It has instructions for adding this as a dependency,
  ;; along with the specific middleware to repl-options
  ;; Q: Why is this in here? It seems pretty profiles-specific.
  ;; Or was I thinking about using this from the server angle?
  :plugins [[cider/cider-nrepl "0.12.0" :exclusions [org.clojure/java.classpath]]]
  :profiles {:uberjar {:aot :all}
             :dev {:source-paths ["dev"]
                   :dependencies [;; TODO: Should probably go away
                                  [clj-ns-browser "1.3.1" :exclusions [clojure-complete org.clojure/clojure]]
                                  [com.cemerick/pomegranate "0.3.1"  :exclusions [org.clojure/clojure
                                                                                  org.codehaus.plexus/plexus-utils]]
                                  [org.clojure/tools.namespace "0.2.10"]]
                   :global-vars {*warn-on-reflection* true}}}
  :repl-options {:init-ns user
                 :welcome (println "Run (dev) to start")}
  ;; Q: What's this needed for?
  ;; (I think it's core.async, but I'm not sure)
  ;; TODO: Take it away and see what breaks
  :repositories {"sonatype-nexus-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"}
  :url "http://frereth.com")
