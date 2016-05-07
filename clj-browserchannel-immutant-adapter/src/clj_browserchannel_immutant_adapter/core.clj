(ns clj-browserchannel-immutant-adapter.core
  (:require
    [immutant.web :as iweb]
    [immutant.web.async :as iasync]
    [net.thegeez.async-adapter :as bc-async-adapter]))

(deftype ImmutantResponse [channel]
  bc-async-adapter/IAsyncAdapter
  (head [this status headers]
    (let [headers (assoc headers "Transfer-Encoding" "chunked")]
      (iasync/send! channel {:status status :headers headers})))
  (write-chunk [this data]
    (if (iasync/open? channel)
      (iasync/send! channel data)
      (throw bc-async-adapter/ConnectionClosedException)))
  (close [this]
    (iasync/close channel)))

(defn wrap-immutant-async-adapter
  [handler]
  (fn [request]
    (let [resp (handler request)]
      (if (= :http (:async resp))
        (iasync/as-channel
          request
          {:on-open
           (fn [channel]
             (let [reactor (:reactor resp)
                   emit    (ImmutantResponse. channel)]
               (reactor emit)))})

        resp))))

(defn run-immutant [handler options]
  (-> handler
      (wrap-immutant-async-adapter)
      (iweb/run options)))
