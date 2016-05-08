(ns chat-demo.client
  (:require
    [net.thegeez.browserchannel.client :as browserchannel]
    [dommy.core :as dom :refer-macros [by-id]]
    goog.events.KeyHandler
    [goog.events.KeyCodes :as key-codes]
    [goog.events :as events]))

(enable-console-print!)

(defn say [text]
  (browserchannel/send-map {:msg text}))

(defn toggle-element [elem]
  (if (dom/attr elem :disabled)
    (dom/remove-attr! elem :disabled)
    (dom/set-attr! elem :disabled)))

(def handler
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
     (let [msg (get data "msg")]
       (dom/append! (by-id "room")
                    (-> (dom/create-element "div")
                        (dom/set-text! (str "MSG::" msg))))))})

(defn ^:export run []
  (events/listen
    js/window "unload"
    (fn []
      (events/removeAll)))

  (browserchannel/init! handler))