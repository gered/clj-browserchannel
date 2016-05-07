(ns chat-demo.client
  (:require
    [dommy.core :as dom :refer-macros [by-id]]
    goog.net.BrowserChannel
    goog.events.KeyHandler
    [goog.events.KeyCodes :as key-codes]
    [goog.events :as events]))

(defonce channel (goog.net.BrowserChannel.))

(defn say [text]
  (.sendMap channel (clj->js {:msg text})))

(defn toggle-element [elem]
  (if (dom/attr elem :disabled)
    (dom/remove-attr! elem :disabled)
    (dom/set-attr! elem :disabled)))

(defn enable-chat []
  (let [msg-input    (by-id "msg-input")
        send-button  (by-id "send-button")
        send-message (fn [e]
                       (say (dom/value msg-input))
                       (dom/set-value! msg-input ""))]
    (toggle-element msg-input)
    (toggle-element send-button)
    (events/listen
      (goog.events.KeyHandler. msg-input)
      "key"
      (fn [e]
        (when (= (.-keyCode e) key-codes/ENTER)
          (send-message e))))
    (events/listen
      send-button
      "click"
      send-message)))

(defn handler []
  (let [h (goog.net.BrowserChannel.Handler.)]
    (set! (.-channelOpened h)
          (fn [channel]
            (enable-chat)))
    (set! (.-channelHandleArray h)
          (fn [channel data]
            (let [data (js->clj data)
                  msg  (get data "msg")]
              (dom/append! (by-id "room")
                           (-> (dom/create-element "div")
                               (dom/set-text! (str "MSG::" msg)))))))
    h))

(defn ^:export run []
  (events/listen
    js/window "unload"
    (fn []
      (.disconnect channel)
      (events/removeAll)))

  ; disable logging
  (doto (.. channel getChannelDebug getLogger)
    (.setLevel goog.debug.Logger.Level.OFF))

  ; or if you would like to see a ton of browserchannel logging output, uncomment this
  #_(doto (.. channel getChannelDebug getLogger)
    (.setLevel goog.debug.Logger.Level.FINER)
    (.addHandler #(js/console.log %)))

  (doto channel
    (.setHandler (handler))
    (.connect "/channel/test" "/channel/bind")))