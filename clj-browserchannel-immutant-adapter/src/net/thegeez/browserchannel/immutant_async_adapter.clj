(ns net.thegeez.browserchannel.immutant-async-adapter
  (:require
    [immutant.web :as iweb]
    [immutant.web.async :as iasync]
    [net.thegeez.browserchannel.async-adapter :as bc-async-adapter]))

(deftype ImmutantResponse
  [channel]
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
  "wraps the ring handler with an async adapter necessary for
   browserchannel sessions.

   options available:

   :response-timeout - Timeout after which the server will close the
                       connection. Specified in milliseconds, default
                       is 4 minutes which is the timeout period Google
                       uses."
  [handler & [options]]
  (fn [request]
    (let [resp (handler request)]
      (if (= :http (:async resp))
        (iasync/as-channel
          request
          {:timeout (get options :response-timeout (* 4 60 1000))
           :on-open
           (fn [channel]
             (let [reactor (:reactor resp)
                   emit    (ImmutantResponse. channel)]
               (reactor emit)))})

        resp))))

(defn run-immutant
  "convenience function for wrapping a ring handler with the necessary
   immutant async adapter and also starting up an immutant server
   at the same time. many applications may want to just directly
   use wrap-immutant-async-adapter instead and not use this function.

   options is passed directly to immutant. see immutant.web/run
   for a description of the available options.

   some additional options used by the async adapter are available:

   :response-timeout - Timeout after which the server will close the
                       connection. Specified in milliseconds, default
                       is 4 minutes which is the timeout period Google
                       uses."
  [handler options]
  (-> handler
      (wrap-immutant-async-adapter options)
      (iweb/run options)))
