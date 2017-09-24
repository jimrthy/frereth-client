;;;; Deprecated. Switch to using boot

(defproject com.frereth/client "0.1.0-SNAPSHOT"
  :description "You might think of this as the Web Kit piece, although that's really far too grandiose."
  :dependencies [[byte-transforms "0.1.5-alpha1"]  ;; Q: Does this make sense?
                 [com.frereth/common "0.0.1-SNAPSHOT"]
                 [com.postspectacular/rotor "0.1.0"]
                 ;; I should be able to upgrade to alpha20, now
                 ;; that CIDER 0.15.1 is out
                 ;; TODO: Verify.
                 ;; It would still be cleaner to just inherit this from frereth-common.
                 ;; Even though that approach just does not seem to be encouraged.
                 ;; It's probably that whole "explicit is better than implicit" idea
                 ;; from python.
                 [org.clojure/clojure "1.9.0-alpha17"]
                 [org.clojure/tools.nrepl "0.2.13"]]

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
  ;; A: So we can connect directly and work without going through
  ;; renderer. Duh.
  ;; Note that I've had problems with it at least once now
  :plugins [[cider/cider-nrepl "0.15.1" :exclusions [org.clojure/java.classpath]]]
  :profiles {:uberjar {:aot :all}
             :dev {:source-paths ["dev"]
                   :dependencies [[com.cemerick/pomegranate "0.4.0"  :exclusions [org.clojure/clojure
                                                                                  org.codehaus.plexus/plexus-utils]]
                                  [integrant/repl "0.2.0"]
                                  [org.clojure/tools.namespace "0.2.10"]]
                   :global-vars {*warn-on-reflection* true}}}
  :repl-options {:init-ns user
                 :welcome (println "Run (dev) to start")}
  ;; Q: What's this needed for?
  ;; (I think it's core.async, but I'm not sure)
  ;; TODO: Take it away and see what breaks
  :repositories {"sonatype-nexus-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"}
  :url "http://frereth.com")
