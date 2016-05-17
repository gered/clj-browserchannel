(defproject gered/clj-browserchannel-immutant-adapter "0.0.2"
  :description  "Immutant async adapter for BrowserChannel"
  :url          "https://github.com/gered/clj-browserchannel/tree/master/clj-browserchannel-immutant-adapter"

  :dependencies []
  
  :profiles     {:provided
                 {:dependencies
                  [[org.clojure/clojure "1.8.0"]
                   [org.immutant/web "2.1.4"]
                   [gered/clj-browserchannel "0.3"]]}})
