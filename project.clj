(defproject frereth-client "0.1.0-SNAPSHOT"
  :description "You might think of this as the Web Kit piece, although that's really far too grandiose."
  :url "http://frereth.com"
  :license {:name "Affero General Public License"
            :url "http://www.gnu.org/licenses/agpl.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
<<<<<<< HEAD
                 [org.zeromq/jzmq "2.2.1"]
                 [org.zeromq/cljzmq "0.1.1"]]
=======
                 [org.clojure/core.async "0.1.0-SNAPSHOT"]
                 ;; Q: What are the odds that this is actually useful?
                 ;; (Nothing against nrepl, which is obviously awesome.
                 ;; Just that I have my doubts about its usefulness
                 ;; here.
                 ;; Well, at least, after I have a front-end...which
                 ;; will almost definitely need to do at least some
                 ;; communicating over nrepl. So...)
                 ;; A: Pretty darn high.
                 [org.clojure/tools.nrepl "0.2.3"]
                 [org.zeromq/jzmq "2.2.0"]]
  :repositories {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"}
>>>>>>> 99bf608885a80b4f164705b2cc10b6aae433a773
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[org.clojure/tools.namespace "0.2.3"]
                                  [org.clojure/java.classpath "0.2.0"]
                                  ;; What was I using this next piece for?
                                  [org.clojure/tools.logging "0.2.6"]
                                  [expectations "1.4.49"]]}}
  :main frereth-client.core)
