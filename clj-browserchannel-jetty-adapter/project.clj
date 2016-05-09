(defproject gered/clj-browserchannel-jetty-adapter "0.1.0"
  :description "Jetty async adapter for BrowserChannel"
  :dependencies [[ring/ring-core "1.4.0"]
                 [ring/ring-servlet "1.4.0"]
                 [ring/ring-jetty-adapter "1.4.0"]
                 [gered/clj-browserchannel "0.3"]]
  :profiles {:provided
              {:dependencies
                [[org.clojure/clojure "1.8.0"]]}})
