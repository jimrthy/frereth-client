(ns frereth-client.parsers)

(defn clj->cl
  "The bare-bones minimalist version seems to involve translating
clojure EDN syntax into the common-lisp equivalent then translating
it into a string.

I'm not sure how I actually feel about this approach.

This is the sort of struct that the reader should
be passing in to here.
Why do I have this accepting a vector of maps?
  [{:dialect \"frenv\"
    :version {:major 0
               :minor 0
               :build 1}
     :header {:title \"Minimalist\"
              :format \"Frereth/unspecified\"
              }
     :body [(heading {:content \"Ready Player One\"})
            (button {:id \"First Button\"
                     :content \"Push Me\"
                     :on-click (throw (RuntimeException. 'do-something-interesting))})]}]

What should the output look like?"
  [src]
  (throw (RuntimeException. "Get this written")))

(defn world
  "Obviously, this isn't very interesting yet.
It also seems pretty horribly misguided.

My original idea is that callers should generate
a map and pass it in as a #world tagged literal.
This function should convert it into something that
frereth-renderer will be able to deal with.

This idea isn't completely and totally wrong, in
and of itself. It's just that this is the wrong
part of the abstraction layer for this to be happening."
  [src]
  (str (clj->cl src)))
