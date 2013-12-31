(ns frereth-client.translator
  (:gen-class))

"Not realistic in the slightest. But I have to start somewhere.
At the very least, should be translating protocols for clients and servers
that speak different 'languages'"

(set! *warn-on-reflection* true)

(defn server->client 
  "TODO: Need to be aware of context.
Or do I?"
  [msg]
  msg)

(defn client->server
  [msg]
  msg)
