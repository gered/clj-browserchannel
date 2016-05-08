(defproject gered/clj-browserchannel-jetty-adapter "0.0.9"
  :description "Jetty async adapter for BrowserChannel"
  :dependencies [[ring/ring-core "1.4.0"]
                 [ring/ring-servlet "1.4.0"]
                 [org.eclipse.jetty/jetty-server "8.1.16.v20140903"];; includes ssl
                 [gered/clj-browserchannel "0.3"]]
  :profiles {:provided
              {:dependencies
                [[org.clojure/clojure "1.8.0"]]}})
