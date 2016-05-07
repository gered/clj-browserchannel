(defproject chat-demo "0.0.1"
  :description   "Example for using BrowserChannel and a client side with ClojureScript"

  :main          chat-demo.server

  :dependencies  [[org.clojure/clojure "1.8.0"]
                  [org.clojure/clojurescript "1.8.51"]
                  [ring/ring-defaults "0.2.0" :exclusions [javax.servlet/servlet-api]]
                  [ring/ring-devel "1.4.0"]
                  [compojure "1.4.0"]
                  [clj-pebble "0.2.0"]
                  [prismatic/dommy "1.1.0"]
                  [net.thegeez/clj-browserchannel-server "0.2.1"]
                  [net.thegeez/clj-browserchannel-jetty-adapter "0.0.8"]
                  [environ "1.0.3"]]

  :plugins       [[lein-cljsbuild "1.1.3"]
                  [lein-environ "1.0.3"]]

  :clean-targets ^{:protect false} [:target-path
                                    [:cljsbuild :builds :main :compiler :output-dir]
                                    [:cljsbuild :builds :main :compiler :output-to]]
  :cljsbuild     {:builds {:main
                           {:source-paths ["src"]
                            :compiler     {:output-to     "resources/public/cljs/app.js"
                                           :output-dir    "resources/public/cljs/target"
                                           :source-map    true
                                           :optimizations :none
                                           :pretty-print  true}}}}

  :profiles      {:dev     {:env {:dev "true"}}

                  :uberjar {:env       {}
                            :aot       :all
                            :hooks     [leiningen.cljsbuild]
                            :cljsbuild {:jar    true
                                        :builds {:main
                                                 {:compiler ^:replace {:output-to     "resources/public/cljs/app.js"
                                                                       :optimizations :advanced
                                                                       :pretty-print  false}}}}}}

  :aliases {"rundemo" ["do" ["clean"] ["cljsbuild" "once"] ["run"]]
            "uberjar" ["do" ["clean"] ["uberjar"]]}

  )
