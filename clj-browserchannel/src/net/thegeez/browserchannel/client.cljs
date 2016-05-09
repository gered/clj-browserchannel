(ns net.thegeez.browserchannel.client
  (:require
    [cljs.reader :as reader]
    [dommy.core :refer-macros [sel1]]
    goog.net.BrowserChannel
    goog.net.BrowserChannel.Handler
    [goog.events :as events]
    [goog.debug.Logger.Level :as log-level]))

(defonce channel (goog.net.BrowserChannel.))

; see: https://google.github.io/closure-library/api/source/closure/goog/net/browserchannel.js.src.html#l470
(def ^:private bch-state-enum-to-keyword
  {0 :closed
   1 :init
   2 :opening
   3 :opened})

; see: https://google.github.io/closure-library/api/source/closure/goog/net/browserchannel.js.src.html#l521
(def ^:private bch-error-enum-to-keyword
  {0  :ok
   2  :request-failed
   4  :logged-out
   5  :no-data
   6  :unknown-session-id
   7  :stop
   8  :network
   9  :blocked
   10 :bad-data
   11 :bad-response
   12 :active-x-blocked})

(defn- encode-map
  [data]
  (doto (js-obj)
    (aset "__edn" (pr-str data))))

(defn- decode-map
  [m]
  (let [m (js->clj m)]
    (if (contains? m "__edn")
      (reader/read-string (str (get m "__edn")))
      m)))

(defn- decode-queued-map
  [queued-map]
  (merge
    {:context (aget queued-map "context")
     :map-id  (aget queued-map "mapId")}
    (let [data (js->clj (aget queued-map "map"))]
      (if (contains? data "__edn")
        {:data (reader/read-string (str (get data "__edn")))}
        {:map data}))))

(defn- decode-queued-map-array
  [queued-map-array]
  (mapv decode-queued-map queued-map-array))

(defn channel-state
  "returns the current state of the browserchannel connection."
  []
  (get bch-state-enum-to-keyword (.getState channel) :unknown))

(defn connected?
  "returns true if the browserchannel connection is currently connected."
  []
  (= (channel-state) :opened))

(defn set-debug-log!
  "sets the debug log level, and optionally a log output handler function
   which defaults to js/console.log"
  [level & [f]]
  (doto (.. channel getChannelDebug getLogger)
    (.setLevel level)
    (.addHandler (or f #(js/console.log %)))))

(defn send-data
  "sends data to the server over the forward channel. when the data has
   been sent and the server sends an acknowledgement, the optional
   on-success callback is invoked."
  [data & [{:keys [on-success]}]]
  (if data
    (.sendMap channel (encode-map data) {:on-success on-success})))

(defn connect!
  "starts the browserchannel connection (initiates a connection to the server).
   under normal circumstances, your application should not call this directly.
   instead your application code should call init!"
  [& [{:keys [base] :as options}]]
  (let [state (channel-state)]
    (if (or (= state :closed)
            (= state :init))
      (.connect channel
                (str base "/test")
                (str base "/bind")))))

(defn disconnect!
  "disconnects and closes the browserchannel connection."
  []
  (if-not (= (channel-state) :closed)
    (.disconnect channel)))

(defn- get-anti-forgery-token
  []
  (if-let [tag (sel1 "meta[name='anti-forgery-token']")]
    (.-content tag)))

(defn- ->handler
  [{:keys [on-open on-close on-receive on-sent on-error]}]
  (let [handler (goog.net.BrowserChannel.Handler.)]
    (set! (.-channelOpened handler)
          (fn [ch]
            (if on-open
              (on-open))))
    (set! (.-channelClosed handler)
          (fn [ch pending undelivered]
            (if on-close
              (on-close (decode-queued-map-array pending)
                        (decode-queued-map-array undelivered)))))
    (set! (.-channelHandleArray handler)
          (fn [ch m]
            (if on-receive
              (on-receive (decode-map m)))))
    (set! (.-channelSuccess handler)
          (fn [ch delivered]
            (if on-sent
              (let [decoded (decode-queued-map-array delivered)]
                (if (seq decoded)
                  (on-sent decoded))))
            (doseq [m delivered]
              (let [{:keys [on-success] :as context} (aget m "context")]
                (if on-success
                  (on-success))))))
    (set! (.-channelError handler)
          (fn [ch error-code]
            (if on-error
              (on-error (get bch-error-enum-to-keyword error-code :unknown)))))
    handler))

(defn- apply-options!
  [options]
  (set-debug-log! (if (:verbose-logging? options) log-level/FINER log-level/OFF))
  (.setAllowChunkedMode channel (boolean (:allow-chunked-mode? options)))
  (.setAllowHostPrefix channel (boolean (:allow-host-prefix? options)))
  (.setFailFast channel (boolean (:fail-fast? options)))
  (.setForwardChannelMaxRetries channel (:max-forward-channel-retries options))
  (.setForwardChannelRequestTimeout channel (:forward-channel-request-timeout options))
  ;; HACK: this is relying on changing a value for a setting that google's
  ;;       documentation lists as private. however, it is a fairly important
  ;;       setting to be able to change, so i think it's worth the risk...
  (set! goog.net.BrowserChannel/BACK_CHANNEL_MAX_RETRIES (:max-back-channel-retries options)))

(def default-options
  "default options that will be applied by init! unless
   overridden."
  {
   ;; base/root url on which to send browserchannel requests to
   :base                            "/channel"

   ;; sets whether chunked mode is allowed. sometimes a useful
   ;; debugging tool
   :allow-chunked-mode?             true

   ;; sets whether the channel allows the user of a subdomain
   :allow-host-prefix?              true

   ;; when true, this changes the behaviour of the forward channel
   ;; (client->server) such that it will not retry failed requests
   ;; even once.
   :fail-fast?                      false

   ;; sets the max number of attempts to connect to the server for
   ;; back channel (server->client) requests
   :max-back-channel-retries        3

   ;; sets the max number of attempts to connect to the server for
   ;; forward channel (client->server) requests
   :max-forward-channel-retries     2

   ;; sets the timeout (in milliseconds) for a forward channel request
   :forward-channel-request-timeout (* 20 1000)

   ;; whether to enable somewhat verbose debug logging
   :verbose-logging?                false
   })

(defn init!
  "initializes a browserchannel connection for use, registers your
   application event handlers and setting any specified options.

   handler should be a map of event handler functions:

   {:on-open    (fn [] ...)
    :on-close   (fn [pending undelivered] ...)
    :on-receive (fn [data] ...)
    :on-sent    (fn [delivered] ...)
    :on-error   (fn [error-code] ...)

   :on-open is called when a connection is (re-)established.
   :on-close is called when a connection is closed.
   :on-receive is called when data is received from the server.
   :on-sent is called when data has been successfully sent to
   the server ('delivered' is a list of what was sent).
   :on-error is only invoked once just before the connection is
   closed, and only if there was an error."
  [handler & [options]]
  (let [options (merge default-options options)]
    (events/listen js/window "unload" #(disconnect!))
    (.setHandler channel (->handler handler))
    (apply-options! options)
    (let [csrf-token (get-anti-forgery-token)
          headers    (merge
                       (:headers options)
                       (if csrf-token {"X-CSRF-Token" csrf-token}))]
      (.setExtraHeaders channel (clj->js headers)))
    (connect! options)))