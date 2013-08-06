(ns frereth-client.parsers)

(defn world
  "Obviously, this isn't very interesting yet.
It also seems pretty horribly misguided."
  [_]
  ;; This is the sort of struct that the reader should
  ;; be passing in to here.
  [{:version {:major 0
              :minor 0
              :build 1}
    :header {:title "Minimalist"
             :format "Frereth/unspecified"
             }
    :body [(heading {:content "Ready Player One"})
           (button {:id "First Button"
                    :content "Push Me"
                    :on-click (throw (RuntimeException. 
                                      "Do something interesting"))})]}])
