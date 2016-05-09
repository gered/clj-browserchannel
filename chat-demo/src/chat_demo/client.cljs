(ns chat-demo.client
  (:require
    [net.thegeez.browserchannel.client :as browserchannel]
    [dommy.core :as dom :refer-macros [by-id]]
    goog.events.KeyHandler
    [goog.events.KeyCodes :as key-codes]
    [goog.events :as events]))

(enable-console-print!)

(defn say [text]
  (browserchannel/send-data {:msg text}))

(defn toggle-element [elem]
  (dom/toggle-attr! elem :disabled (not (dom/attr elem :disabled))))

(def event-handlers
  {:on-open
   (fn []
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

   :on-receive
   (fn [data]
     (dom/append! (by-id "room")
                  (-> (dom/create-element "div")
                      (dom/set-text! (str "MSG::" (:msg data))))))})

(defn ^:export run []
  (browserchannel/init! event-handlers))