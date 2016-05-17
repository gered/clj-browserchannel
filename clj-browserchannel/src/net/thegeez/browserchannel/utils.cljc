(ns net.thegeez.browserchannel.utils)

(defn run-middleware
  [middleware final-handler & args]
  (let [wrap    (fn [handler [f & more]]
                  (if f
                    (recur (f handler) more)
                    handler))
        handler (wrap final-handler middleware)]
    (apply handler args)))

(defn get-handlers
  [middleware k]
  (->> middleware
       (map k)
       (remove nil?)
       (doall)))

(defn get-middleware-handler-map
  [middleware handler-ks]
  (reduce
    (fn [m k]
      (assoc m k (get-handlers middleware k)))
    {}
    handler-ks))