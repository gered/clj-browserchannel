(ns chat-demo.server
  (:gen-class)
  (:require
    [compojure.core :refer [routes GET]]
    [compojure.route :as route]
    [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
    [ring.util.response :refer [response]]
    [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
    [clj-pebble.core :as pebble]
    [net.thegeez.browserchannel.server :as browserchannel]
    [net.thegeez.browserchannel.jetty-async-adapter :as jetty]
    [net.thegeez.browserchannel.immutant-async-adapter :as immutant]
    [environ.core :refer [env]]))

(defonce clients (atom #{}))

(def event-handlers
  {:on-open
   (fn [session-id request]
     (println "session " session-id "connected")
     (swap! clients conj session-id)
     (doseq [client-id @clients]
       (browserchannel/send-map client-id {"msg" (str "client " session-id " connected")})))

   :on-close
   (fn [session-id request reason]
     (println "session " session-id " disconnected: " reason)
     (swap! clients disj session-id)
     (doseq [client-id @clients]
       (browserchannel/send-map client-id {"msg" (str "client " session-id " disconnected " reason)})))

   :on-receive
   (fn [session-id request m]
     (println "session " session-id " sent " m)
     (doseq [client-id @clients]
       (browserchannel/send-map client-id m)))})

(def app-routes
  (routes
    (GET "/" [] (pebble/render-resource
                  "html/index.html"
                  {:dev       (boolean (env :dev))
                   :csrfToken *anti-forgery-token*}))
    (route/resources "/")
    (route/not-found "not found")))

(def handler
  (-> app-routes
      (browserchannel/wrap-browserchannel {:base "/channel" :events event-handlers})
      (wrap-defaults site-defaults)))

(defn run-jetty []
  (println "Using Jetty adapter")
  (jetty/run-jetty-async
    #'handler
    {:join? false
     :port  8080}))

(defn run-immutant []
  (println "Using Immutant adapter")
  (immutant/run-immutant
    #'handler
    {:port 8080}))

(defn -main [& args]
  (if (env :dev) (pebble/set-options! :cache false))

  ;(run-jetty)
  (run-immutant)

  )
