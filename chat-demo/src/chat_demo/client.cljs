(ns chat-demo.client
  (:require
    [net.thegeez.browserchannel.client :as browserchannel]
    [dommy.core :as dom :refer-macros [by-id]]
    goog.events.KeyHandler
    [goog.events.KeyCodes :as key-codes]
    [goog.events :as events]))

(enable-console-print!)

(defn say [text]
  (browserchannel/send-data! {:msg text}))

(defn toggle-element [elem]
  (dom/toggle-attr! elem :disabled (not (dom/attr elem :disabled))))

(def event-handlers
  {:on-open
   (fn []
     ; enable message sending UI
     (toggle-element (by-id "msg-input"))
     (toggle-element (by-id "send-button")))

   :on-receive
   (fn [data]
     ; show messages received from the server
     (dom/append! (by-id "room")
                  (-> (dom/create-element "div")
                      (dom/set-text! (str "MSG::" (:msg data))))))

   :on-close
   (fn [due-to-error? pending undelivered]
     ; disable message sending UI
     (toggle-element (by-id "msg-input"))
     (toggle-element (by-id "send-button")))})

(defn ^:export run []
  ; wire-up UI events
  (let [msg-input    (by-id "msg-input")
        send-button  (by-id "send-button")
        send-message (fn [e]
                       (say (dom/value msg-input))
                       (dom/set-value! msg-input ""))]
    (events/listen
      (goog.events.KeyHandler. msg-input)
      "key"
      (fn [e]
        (when (= (.-keyCode e) key-codes/ENTER)
          (send-message e))))
    (events/listen
      send-button
      "click"
      send-message))

  ; initiate browserchannel session with the server
  (browserchannel/connect! event-handlers))
