
(deftask cider "CIDER profile"
  []
  (require 'boot.repl)

  (swap! @(resolve 'boot.repl/*default-dependencies*)
         concat '[[cider/cider-nrepl "0.15.1"]
                  [com.billpiel/sayid "0.0.15"]
                  [org.clojure/tools.nrepl "0.2.13"]
                  [refactor-nrepl "2.3.1"]])

  (swap! @(resolve 'boot.repl/*default-middleware*)
         concat
         '[cider.nrepl/cider-middleware
           refactor-nrepl.middleware/wrap-refactor])
  identity)
