(defproject frereth-client "0.1.0-SNAPSHOT"
  :description "You might think of this as the Web Kit piece, although that's really far too grandiose."
  :url "http://frereth.com"
  :license {:name "Affero General Public License"
            :url "http://www.gnu.org/licenses/agpl.html"}
  :dependencies [[org.clojure/tools.logging "0.2.6"]
                 [org.clojure/clojure "1.5.1"]
                 [org.zeromq/jzmq "2.2.1"]
                 [org.zeromq/cljzmq "0.1.1"]
                 [org.clojure/core.async "0.1.222.0-83d0c2-alpha"]
                 ;; Q: What are the odds that this is actually useful?
                 ;; (Nothing against nrepl, which is obviously awesome.
                 ;; Just that I have my doubts about its usefulness
                 ;; here.
                 ;; Well, at least, after I have a front-end...which
                 ;; will almost definitely need to do at least some
                 ;; communicating over nrepl. So...)
                 ;; A: Pretty darn high.
                 [org.clojure/tools.nrepl "0.2.3"]]
  :git-dependencies [["git@github.com:jimrthy/cljeromq.git"]]
  :main frereth-client.core
  :plugins [[lein-git-deps "0.0.1-SNAPSHOT"]]
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [;; TODO: expectations isn't working for me. Why am I still trying to use it?
                                  ;; Really should switch to either midje or straight
                                  ;; clojure.test. Depends on how that works out with
                                  ;; frereth-server.
                                  [expectations "1.4.49"]
                                  [org.clojure/tools.namespace "0.2.3"]
                                  [org.clojure/java.classpath "0.2.0"]]}}
  :repositories {;; I think this next was only needed for the unreleased SNAPSHOT core.async
                 ;;"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"
                 }
  :source-paths ["src" ".lein-git-deps/cljeromq/src"])
