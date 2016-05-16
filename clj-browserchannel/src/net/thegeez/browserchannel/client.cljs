(ns net.thegeez.browserchannel.client
  (:require
    [cljs.reader :as reader]
    [dommy.core :refer-macros [sel1]]
    goog.net.BrowserChannel
    goog.net.BrowserChannel.Handler
    [goog.events :as events]
    [goog.debug.Logger.Level :as log-level]))

(def ^:private bch-state-enum-to-keyword
  {
   0 :closed                  ; channel is closed
   1 :init                    ; channel has been initialized but hasn't yet initiated a connection
   2 :opening                 ; channel is in the process of opening a connection to the server
   3 :opened                  ; channel is open
   })

(def ^:private bch-error-enum-to-keyword
  {
   0  :ok                     ; indicates no error has occurred
   2  :request-failed         ; error due to a request failing
   4  :logged-out             ; error due to the user being logged out
   5  :no-data                ; error due to server response which contains no data
   6  :unknown-session-id     ; error due to a server response indicating an unknown session id (also happens after a session timeout)
   7  :stop                   ; error due to a server response requesting to stop the channel (client ideally will not treat this as an error)
   8  :network                ; general network error
   9  :blocked                ; error due to the channel being blocked by a network administrator
   10 :bad-data               ; error due to bad data being returned from the server
   11 :bad-response           ; error due to a response that doesn't start with the magic cookie
   12 :active-x-blocked       ; activex is blocked by the machine's admin settings
   })

(defonce state (atom {:channel            nil
                      :queued-buffer      []
                      :last-error         nil
                      :reconnect-counter  0
                      :reconnect-timer-id nil}))

(defn ^goog.net.BrowserChannel get-channel
  "returns the BrowserChannel object representing the current session"
  []
  (:channel @state))

(defn- get-last-error [] (:last-error @state))
(defn- get-reconnect-counter [] (:reconnect-counter @state))
(defn- get-reconnect-timer-id [] (:reconnect-timer-id @state))

(defn- flush-queued-buffer!
  []
  (let [queued-buffer (:queued-buffer @state)]
    (swap! state assoc :queued-buffer [])
    (doseq [[m context] queued-buffer]
      (.sendMap (get-channel) m context))))

(defn- set-new-channel!
  []
  (swap! state assoc :channel (goog.net.BrowserChannel.))
  (flush-queued-buffer!))

(defn- clear-reconnect-timer!
  []
  (swap! state update-in [:reconnect-timer-id]
         (fn [timer-id]
           (if timer-id (js/clearTimeout timer-id))
           nil)))

(defn- clear-last-error!
  []
  (swap! state assoc :last-error :ok))

(defn- set-last-error!
  [error-code]
  (swap! state assoc :last-error error-code))

(defn- reset-reconnect-attempts-counter!
  []
  (swap! state assoc :reconnect-counter 0))

(defn- increase-reconnect-attempt-counter!
  []
  (swap! state update-in [:reconnect-counter] inc))

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
  (get bch-state-enum-to-keyword (.getState (get-channel)) :unknown))

(defn connected?
  "returns true if the browserchannel connection is currently connected."
  []
  (= (channel-state) :opened))

(defn set-debug-log!
  "sets the debug log level, and optionally a log output handler function
   which defaults to js/console.log"
  [level & [f]]
  (doto (.. (get-channel) getChannelDebug getLogger)
    (.setLevel level)
    (.addHandler (or f #(js/console.log %)))))

(defn send-data
  "sends data to the server over the forward channel. context can
   contain optional callback functions:

   on-success - called when the data has been sent to the server
   on-error   - called if there is an error sending the data to
                the server"
  [data & [context]]
  (if data
    (let [m (encode-map data)]
      (if (get-channel)
        (.sendMap (get-channel) m context)
        (swap! state update-in [:queued-buffer] conj [m context])))))

(defn- get-anti-forgery-token
  []
  (if-let [tag (sel1 "meta[name='anti-forgery-token']")]
    (.-content tag)))

(defn- apply-options!
  [options]
  (let [csrf-token (get-anti-forgery-token)
        headers    (merge
                     (:headers options)
                     (if csrf-token {"X-CSRF-Token" csrf-token}))]
    (set-debug-log! (if (:verbose-logging? options) log-level/FINER log-level/OFF))
    (doto (get-channel)
      (.setExtraHeaders (clj->js headers))
      (.setAllowChunkedMode (boolean (:allow-chunked-mode? options)))
      (.setAllowHostPrefix (boolean (:allow-host-prefix? options)))
      (.setFailFast (boolean (:fail-fast? options)))
      (.setForwardChannelMaxRetries (:max-forward-channel-retries options))
      (.setForwardChannelRequestTimeout (:forward-channel-request-timeout options))
      (.setRetryDelay (:base-connect-retry-delay options)
                      (:connect-retry-delay-seed options)))
    ;; HACK: this is relying on changing a value for a setting that google's
    ;;       documentation lists as private. however, it is a fairly important
    ;;       setting to be able to change, so i think it's worth the risk...
    (set! goog.net.BrowserChannel/BACK_CHANNEL_MAX_RETRIES (:max-back-channel-retries options))))

(defn disconnect!
  "disconnects and closes the browserchannel connection, and stops any
   reconnection attempts"
  []
  (clear-last-error!)
  (clear-reconnect-timer!)
  (if-not (= (channel-state) :closed)
    (.disconnect (get-channel))))

(defn- connect-channel!
  [{:keys [on-opening] :as events}
   {:keys [base] :as options}
   {:keys [old-session-id last-array-id] :as connect-args}]
  (let [state (channel-state)]
    (when (or (= state :closed)
              (= state :init))
      (.connect (get-channel)
                (str base "/test")
                (str base "/bind")
                nil
                old-session-id
                ; -1 is the default value set by goog.net.BrowserChannel
                ; not really sure that passing in -1 is a good idea though
                ; (that is, i don't think -1 ever gets passed in the AID param)
                (if-not (= -1 last-array-id) last-array-id))
      (if on-opening (on-opening)))))

(defn- reconnect!
  [events handler options connect-args]
  (set-new-channel!)
  (increase-reconnect-attempt-counter!)
  (.setHandler (get-channel) handler)
  (apply-options! options)
  (connect-channel! events options connect-args))

(defn- raise-context-callbacks!
  [array callback-k]
  (doseq [m array]
    (let [context (aget m "context")
          callback (get context callback-k)]
      (if callback (callback)))))

(defn- ->browserchannel-handler
  [{:keys [on-open on-close on-receive on-sent on-error] :as events} options]
  (let [handler (goog.net.BrowserChannel.Handler.)]
    (set! (.-channelOpened handler)
          (fn [_]
            (clear-last-error!)
            (reset-reconnect-attempts-counter!)
            (if on-open (on-open))))
    (set! (.-channelClosed handler)
          (fn [_ pending undelivered]
            (let [last-error       (:last-error @state)
                  due-to-error?    (and last-error
                                        (not (some #{last-error} [:stop :ok])))
                  session-timeout? (= last-error :unknown-session-id)]
              (when (and (:auto-reconnect? options)
                         due-to-error?
                         (< (:reconnect-counter @state)
                            (dec (:max-reconnect-attempts options))))
                (clear-reconnect-timer!)
                (js/setTimeout
                  #(reconnect! events handler options
                               (if-not session-timeout?
                                 {:old-session-id (.getSessionId (get-channel))
                                  :last-array-id  (.getLastArrayId (get-channel))}))
                  (if session-timeout? 0 (:reconnect-time options))))
              (when due-to-error?
                (raise-context-callbacks! pending :on-error)
                (raise-context-callbacks! undelivered :on-error))
              (if on-close
                (on-close due-to-error?
                          (decode-queued-map-array pending)
                          (decode-queued-map-array undelivered)))
              (clear-last-error!))))
    (set! (.-channelHandleArray handler)
          (fn [_ m]
            (if on-receive
              (on-receive (decode-map m)))))
    (set! (.-channelSuccess handler)
          (fn [_ delivered]
            (if on-sent
              (let [decoded (decode-queued-map-array delivered)]
                (if (seq decoded) (on-sent decoded))))
            (raise-context-callbacks! delivered :on-success)))
    (set! (.-channelError handler)
          (fn [_ error-code]
            (let [error-code (get bch-error-enum-to-keyword error-code :unknown)]
              (set-last-error! error-code)
              (if on-error (on-error error-code)))))
    handler))

(def default-options
  "default options that will be applied by connect! unless
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

   ; base time delay before another connection attempt is made. note
   ; that a random time between 0 and :connect-retry-delay-seed is
   ; added to this value to determine the final reconnect delay time.
   ; time is in milliseconds
   :base-connect-retry-delay        (* 5 1000)

   ; see description of :base-connect-retry-delay. time is in
   ; milliseconds
   :connect-retry-delay-seed        (* 10 1000)

   ;; whether to enable somewhat verbose debug logging
   :verbose-logging?                false

   ;; whether to automatically reconnect in the event the session
   ;; connection is lost due to some error. if the server requests
   ;; that we disconnect, an automatic reconnect will not occur
   :auto-reconnect?                 true

   ;; time after an error resulting in a disconnect before we try to
   ;; reconnect again (milliseconds)
   :reconnect-time                  (* 3 1000)

   ; sets the max number of reconnection attempts
   :max-reconnect-attempts          3
   })

(defn connect!
  "initializes a browserchannel connection for use, registers your
   application event handlers and setting any specified options.

   events should be a map of event handler functions. you only need
   to include handler functions for events you care about.

   {:on-opening (fn [] ...)
    :on-open    (fn [] ...)
    :on-close   (fn [due-to-error? pending undelivered] ...)
    :on-receive (fn [data] ...)
    :on-sent    (fn [delivered] ...)
    :on-error   (fn [error-code] ...)

   for the supported options, see
   net.thegeez.browserchannel.client/default-options"
  [events & [options]]
  (let [options (merge default-options options)]
    (events/listen js/window "unload" disconnect!)
    (set-new-channel!)
    (.setHandler (get-channel) (->browserchannel-handler events options))
    (apply-options! options)
    (connect-channel! events options nil)))