(defproject com.frereth/client "0.1.0-SNAPSHOT"
  :description "You might think of this as the Web Kit piece, although that's really far too grandiose."
  :dependencies [[byte-transforms "0.1.4"]  ;; Q: Does this make sense?
                 [com.frereth/common "0.0.1-SNAPSHOT"]
                 [com.postspectacular/rotor "0.1.0"]
                 ;; I'd much rather just inherit this from frereth.common.
                 ;; CIDER doesn't seem to pick that up correctly.
                 ;; That should be fixed now.
                 ;; And there's a managed-dependencies plugin for lein
                 ;; that should fix the problem
                 ;; TODO: Make this go back away
                 [org.clojure/clojure "1.9.0-alpha14"]
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
  ;; Note that I've had problems with it at least once now
  :plugins [[cider/cider-nrepl "0.13.0" :exclusions [org.clojure/java.classpath]]]
  :profiles {:uberjar {:aot :all}
             :dev {:source-paths ["dev"]
                   :dependencies [[com.cemerick/pomegranate "0.3.1"  :exclusions [org.clojure/clojure
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
