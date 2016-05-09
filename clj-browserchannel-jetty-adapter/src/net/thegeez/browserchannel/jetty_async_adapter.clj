(ns net.thegeez.browserchannel.jetty-async-adapter
  "BrowserChannel adapter for the Jetty webserver, with async HTTP."
  (:import
    [org.eclipse.jetty.server.handler AbstractHandler]
    [org.eclipse.jetty.server Server Request]
    [javax.servlet AsyncContext AsyncListener AsyncEvent]
    [javax.servlet.http HttpServletRequest])
  (:require
    [ring.adapter.jetty :as jetty]
    [ring.util.servlet :as servlet]
    [net.thegeez.browserchannel.async-adapter :as async-adapter]))

;; Based on ring-jetty-async-adapter by Mark McGranaghan
;; (https://github.com/mmcgrana/ring/tree/jetty-async)
;; This has failed write support

(deftype JettyAsyncResponse
  [^AsyncContext async-context]
  async-adapter/IAsyncAdapter

  (head [this status headers]
    (doto (.getResponse async-context)
      (servlet/update-servlet-response {:status status :headers (assoc headers "Transfer-Encoding" "chunked")})
      (.flushBuffer)))

  (write-chunk [this data]
    (doto (.getWriter (.getResponse async-context))
      (.write ^String data)
      (.flush))
    (when (.checkError (.getWriter (.getResponse async-context)))
      (throw async-adapter/ConnectionClosedException)))

  (close [this]
    (doto (.getWriter (.getResponse async-context))
      (.write "")
      (.flush))
    (.complete async-context)))

(defn- proxy-handler
  "Returns an Jetty Handler implementation for the given Ring handler."
  [handler options]
  (proxy [AbstractHandler] []
    (handle [target ^Request base-request ^HttpServletRequest request response]
      (let [request-map  (servlet/build-request-map request)
            response-map (handler request-map)]
        (when response-map
          (condp = (:async response-map)
            nil
            (do
              (servlet/update-servlet-response response response-map)
              (.setHandled base-request true))

            :http
            (let [reactor       (:reactor response-map)
                  async-context ^AsyncContext (.startAsync request) ;; continuation lives until written to!
                  emit          (JettyAsyncResponse. async-context)]
              (.addListener async-context
                            (proxy [AsyncListener] []
                              (onComplete [^AsyncEvent e]
                                nil)
                              (onTimeout [^AsyncEvent e]
                                (let [async-context ^AsyncContext (.getAsyncContext e)]
                                  (.complete async-context)))))

              ;; 4 minutes is google default
              (.setTimeout async-context (get options :response-timeout (* 4 60 1000)))
              (reactor emit))))))))

(defn- configure-jetty-async!
  [^Server server handler options]
  (.setHandler server (proxy-handler handler options)))

(defn ^Server run-jetty
  "Starts a Jetty webserver to serve the given handler with the
   given options. This Jetty instance will have additional async
   support necessary for BrowserChannel sessions.

   The available options are the same as those used by
   ring.adapter.jetty/run-jetty, except for these additions:

   :response-timeout - Timeout after which the server will close the connection.
                       Specified in milliseconds, default is 4 minutes which is
                       the timeout period Google uses."
  [handler options]
  (let [existing-configurator (:configurator options)
        options               (assoc options
                                :configurator (fn [^Server server]
                                                (if existing-configurator
                                                  (existing-configurator server))
                                                (configure-jetty-async! server handler options)))]
    (jetty/run-jetty handler options)))
