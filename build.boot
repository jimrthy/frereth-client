(def project 'com.frereth/client)
(def version "0.1.0-SNAPSHOT")

(set-env! :dependencies   '[[adzerk/boot-test "RELEASE" :scope "test"]
                            [byte-transforms "0.1.5-alpha1" :exclusions [org.clojure/tools.logging
                                                                         riddley]] ;; Q: Does this make sense?
                            ;; TODO: This should really be part of a cider deftask.
                            ;; Don't inflict CIDER on people who don't use it.
                            [cider/cider-nrepl "0.21.0" :exclusions [org.clojure/java.classpath]]
                            [com.cemerick/pomegranate
                             "1.1.0"
                             :exclusions [commons-codec
                                          org.clojure/clojure
                                          org.slf4j/jcl-over-slf4j]
                             :scope "test"]
                            [com.frereth/common "0.0.1-SNAPSHOT" :exclusions [byte-streams
                                                                              commons-codec
                                                                              manifold
                                                                              primitive-math]]
                            ;; Q: Do I want to stick with the clojure.tools.logging approach?
                            [com.postspectacular/rotor "0.1.0" :exclusions [org.clojure/clojure]]
                            [com.taoensso/timbre "4.10.0" :exclusions [org.clojure/clojure
                                                                       org.clojure/tools.reader]]
                            [integrant "0.8.0-alpha2" :exclusions [org.clojure/clojure]]
                            [integrant/repl "0.3.1" :scope "test" :exclusions [integrant
                                                                               org.clojure/clojure]]

                            [org.clojure/clojure "1.10.1-beta2"]
                            [org.clojure/java.classpath "0.3.0"
                             :exclusions [org.clojure/clojure] :scope "test"]
                            [org.clojure/spec.alpha "0.2.176" :exclusions [org.clojure/clojure]]
                            [org.clojure/test.check "0.10.0-alpha4" :scope "test" :exclusions [org.clojure/clojure]]
                            [org.clojure/tools.nrepl "0.2.13" :exclusions [org.clojure/clojure]]
                            [samestep/boot-refresh "0.1.0" :scope "test" :exclusions [org.clojure/clojure]]
                            ;; This is the task that combines all the linters
                            [tolitius/boot-check "0.1.12" :scope "test" :exclusions [org.tcrawley/dynapath]]]
          :project project
          :resource-paths #{"src"}
          :source-paths   #{"dev" "dev-resources" "test"})

(task-options!
 aot {:namespace   #{'com.frereth.client.system}}
 pom {:project     project
      :version     version
      :description "Shared frereth components"
      ;; TODO: Add a real website
      :url         "https://github.com/jimrthy/frereth-client"
      :scm         {:url "https://github.com/jimrthy/frereth-common"}
      :license     {"Eclipse Public License"
                    "http://www.eclipse.org/legal/epl-v10.html"}}
 jar {:file        (str "client-" version ".jar")})

(require '[samestep.boot-refresh :refer [refresh]])
(require '[tolitius.boot-check :as check])

(deftask build
  "Build the project locally as a JAR."
  [d dir PATH #{str} "the set of directories to write to (target)."]
  ;; Note that this approach passes the raw command-line parameters
  ;; to -main, as opposed to what happens with `boot run`
  ;; TODO: Eliminate this discrepancy
  (let [dir (if (seq dir) dir #{"target"})]
    (comp (javac) (aot) (pom) (uber) (jar) (target :dir dir))))

(deftask local-install
  "Create a jar to go into your local maven repository"
  [d dir PATH #{str} "the set of directories to write to (target)."]
  (let [dir (if (seq dir) dir #{"target"})]
    (comp (pom) (jar) (target :dir dir) (install))))

(deftask cider-repl
  "Set up a REPL for connecting from CIDER"
  []
  ;; Just because I'm prone to forget one of the vital helper steps
  (comp (cider) (javac) (repl)))

(deftask run
  "Run the project."
  [f file FILENAME #{str} "the arguments for the application."]
  ;; This is a leftover template from another project that I
  ;; really just copy/pasted over.
  ;; Q: Does it make any sense to keep it around?
  (require '[frereth-cp.server :as app])
  (apply (resolve 'app/-main) file))

(require '[adzerk.boot-test :refer [test]])
