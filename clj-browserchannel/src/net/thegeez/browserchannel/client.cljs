(ns net.thegeez.browserchannel.client
  (:require
    [dommy.core :refer-macros [sel1]]
    goog.net.BrowserChannel
    goog.net.BrowserChannel.Handler
    [goog.net.BrowserChannel.State :as bc-state]
    [goog.events :as events]
    [goog.debug.Logger.Level :as log-level]))

(defonce channel (goog.net.BrowserChannel.))

(def default-options
  {:base                            "/channel"
   :allow-chunked-mode?             true
   :allow-host-prefix?              true
   :fail-fast?                      false
   :max-back-channel-retries        3
   :max-forward-channel-retries     2
   :forward-channel-request-timeout (* 20 1000)
   :verbose-logging?                false})

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

(defn- queued-map->clj
  [queued-map]
  {:context (aget queued-map "context")
   :map     (js->clj (aget queued-map "map"))
   :map-id  (aget queued-map "mapId")})

(defn- array-of-queued-map->clj
  [queued-map-array]
  (mapv queued-map->clj queued-map-array))

(defn channel-state []
  (.getState channel))

(defn connected?
  []
  (= (channel-state) bc-state/OPENED))

(defn set-debug-log!
  [level & [f]]
  (doto (.. channel getChannelDebug getLogger)
    (.setLevel level)
    (.addHandler (or f #(js/console.log %)))))

(defn send-map
  [m & [{:keys [on-success]}]]
  (.sendMap channel (clj->js m) {:on-success on-success}))

(defn connect!
  [& [{:keys [base] :as options}]]
  (let [state (channel-state)]
    (if (or (= state bc-state/CLOSED)
            (= state bc-state/INIT))
      (.connect channel
                (str base "/test")
                (str base "/bind")))))

(defn disconnect!
  []
  (if-not (= (channel-state) bc-state/CLOSED)
    (.disconnect channel)))

(defn- get-anti-forgery-token
  []
  (if-let [tag (sel1 "meta[name='anti-forgery-token']")]
    (.-content tag)))

(defn ->handler
  [{:keys [on-open on-close on-receive on-sent on-error]}]
  (let [handler (goog.net.BrowserChannel.Handler.)]
    (set! (.-channelOpened handler)
          (fn [ch]
            (if on-open
              (on-open))))
    (set! (.-channelClosed handler)
          (fn [ch pending undelivered]
            (if on-close
              (on-close (array-of-queued-map->clj pending)
                        (array-of-queued-map->clj undelivered)))))
    (set! (.-channelHandleArray handler)
          (fn [ch m]
            (if on-receive
              (on-receive (js->clj m)))))
    (set! (.-channelSuccess handler)
          (fn [ch delivered]
            (if on-sent
              (on-sent (array-of-queued-map->clj delivered)))
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

(defn init!
  [handler & [options]]
  (let [options (merge default-options options)]
    (events/listen
      js/window "unload"
      (fn []
        (disconnect!)))

    (.setHandler channel (->handler handler))
    (apply-options! options)
    (let [csrf-token (get-anti-forgery-token)
          headers    (merge
                       (:headers options)
                       (if csrf-token {"X-CSRF-Token" csrf-token}))]
      (.setExtraHeaders channel (clj->js headers)))
    (connect! options)))