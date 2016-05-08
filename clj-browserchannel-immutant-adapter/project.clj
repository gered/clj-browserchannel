(defproject gered/clj-browserchannel-immutant-adapter "0.0.1"
  :description "Immutant async adapter for BrowserChannel"
  :dependencies [[gered/clj-browserchannel "0.3"]]
  :profiles {:provided
             {:dependencies
              [[org.clojure/clojure "1.8.0"]
               [org.immutant/web "2.1.4"]]}})
