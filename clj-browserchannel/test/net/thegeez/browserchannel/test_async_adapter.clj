(ns net.thegeez.browserchannel.test-async-adapter
  (:require
    [net.thegeez.browserchannel.async-adapter :as bc-async-adapter]))

(def async-output (atom {}))

(defn write-async-output!
  [data]
  (if (map? data)
    (swap! async-output merge data)
    (swap! async-output (fn [{:keys [body] :as output}]
                          (assoc output :body (str body data))))))

(defn closed-async-output?
  []
  (:closed? @async-output))

(defn async-output-fixture [f]
  (reset! async-output {})
  (f))

(deftype TestAsyncResponse
  [write-fn closed?-fn]
  bc-async-adapter/IAsyncAdapter

  (head [this status headers]
    (let [headers (assoc headers "Transfer-Encoding" "chunked")]
      (write-fn {:status status :headers headers})))

  (write-chunk [this data]
    (if-not (closed?-fn)
      (write-fn data)
      (throw bc-async-adapter/ConnectionClosedException)))

  (close [this]
    (write-fn {:closed? true})))

(defn wrap-test-async-adapter
  [handler & [options]]
  (fn [request]
    (let [resp (handler request)]
      (if (= :http (:async resp))
        (let [reactor (:reactor resp)
              emit    (TestAsyncResponse. write-async-output! closed-async-output?)]
          (reactor emit)
          {:status 200})
        resp))))