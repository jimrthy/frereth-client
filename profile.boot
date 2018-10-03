
(deftask cider "CIDER profile"
  []
  (require 'boot.repl)

  (swap! @(resolve 'boot.repl/*default-dependencies*)
         concat '[[cider/cider-nrepl "0.18.0"]
                  [com.billpiel/sayid "0.0.17"]
                  ;; Switching to 0.4.0 changes namespaces.
                  ;; Q: How does this affect CIDER?
                  [nrepl "0.3.1"]
                  [refactor-nrepl "2.4.0"]])

  (swap! @(resolve 'boot.repl/*default-middleware*)
         concat
         '[cider.nrepl/cider-middleware
           refactor-nrepl.middleware/wrap-refactor])
  identity)
