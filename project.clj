(defproject frereth-client "0.1.0-SNAPSHOT"
  :description "You might think of this as Web Kit, although that's really far too grandiose."
  :url "http://frereth.com"
  :license {:name "Affero General Public License"
            :url "http://www.gnu.org/licenses/agpl.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 ;; What are the odds that this is actually useful?
                 [org.clojure/tools.nrepl "0.2.3"]
                 ;; Really should check to see what this is to verify
                 ;; that I don't care about it.
                 ;;[cc.qbits/jilch "0.3.0"]
                 [org.zeromq/jzmq "2.2.0"]]
  :main frereth-client.core)
