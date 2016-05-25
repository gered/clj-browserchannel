(defproject gered/clj-browserchannel "0.3.1"
  :description  "BrowserChannel server implementation in Clojure, with a ClojureScript wrapper for the BrowserChannel API included in Google Closure."
  :url          "https://github.com/gered/clj-browserchannel/tree/master/clj-browserchannel"

  :dependencies [[org.clojure/tools.logging "0.3.1"]
                 [ring/ring-core "1.4.0"]
                 [cheshire "5.6.1"]]
                 
  :profiles     {:provided
                 {:dependencies
                  [[org.clojure/clojure "1.8.0"]
                   [org.clojure/clojurescript "1.8.51"]]}

                 :dev
                 {:dependencies [[pjstadig/humane-test-output "0.8.0"]
                                 [ring/ring-mock "0.3.0"]]
                  :injections   [(require 'pjstadig.humane-test-output)
                                 (pjstadig.humane-test-output/activate!)]}})
