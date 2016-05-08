(defproject gered/clj-browserchannel-server "0.2.2"
  :description "BrowserChannel server implementation in Clojure"
  :dependencies [[ring/ring-core "1.4.0"]
                 [org.clojure/data.json "0.2.6"]
                 [prismatic/dommy "1.1.0"]]
  :profiles {:provided
              {:dependencies
                [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.8.51"]]}})
