
(defproject frereth-client "0.1.0-SNAPSHOT"
  :description "You might think of this as the Web Kit piece, although that's really far too grandiose."
  :url "http://frereth.com"
  :license {:name "Eclipse Public License"
            :url "http://http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[byte-transforms "0.1.3"]
                 [com.postspectacular/rotor "0.1.0"]
                 [com.stuartsierra/component "0.2.1"]
                 [com.taoensso/timbre "3.2.1"]
                 [im.chit/ribol "0.4.0"]
                 [jimrthy/cljeromq "0.1.0-SNAPSHOT"]  ; Q: Do I *really* want to use this?
                 [org.clojure/tools.logging "0.2.6"]  ; Q: why am I using this?
                 [org.clojure/clojure "1.7.0-alpha1"]
                 [org.clojure/core.async "0.1.338.0-5c5012-alpha"]
                 ;; Q: What are the odds that this is actually useful?
                 ;; (Nothing against nrepl, which is obviously awesome.
                 ;; Just that I have my doubts about its usefulness
                 ;; here.
                 ;; Well, at least, after I have a front-end...which
                 ;; will almost definitely need to do at least some
                 ;; communicating over nrepl. So...)
                 ;; A: Pretty darn high.
                 [org.clojure/tools.nrepl "0.2.4"]
                 [prismatic/plumbing "0.3.3"]
                 [prismatic/schema "0.2.6"]]
  ;;:git-dependencies [["git@github.com:jimrthy/cljeromq.git"]]
  :main frereth-client.core
  ;;:plugins [[lein-git-deps "0.0.1-SNAPSHOT"]]
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[clj-ns-browser "1.3.1"]
                                  [midje "1.6.3"]
                                  [org.clojure/tools.namespace "0.2.5"]
                                  [org.clojure/java.classpath "0.2.2"]
                                  #_[org.clojure/tools.trace "0.7.8"]
                                  #_[ritz/ritz-debugger "0.7.0"]]}}
  :repl-options {:init-ns user}
  ;; Q: What's this needed for?
  ;; (I think it's core.async, but I'm not sure)
  ;; TODO: Take it away and see what breaks
  :repositories {"sonatype-nexus-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"})
