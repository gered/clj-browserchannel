(ns net.thegeez.browserchannel.utils)

(defn run-middleware
  [middleware final-handler & args]
  (let [wrap    (fn [handler [f & more]]
                  (if f
                    (recur (f handler) more)
                    handler))
        handler (wrap (or final-handler (fn [& _])) middleware)]
    (apply handler args)))

(defn get-middleware-handler-map
  [middleware handler-ks]
  (reduce
    (fn [m k]
      (assoc m k (->> middleware (map k) (remove nil?))))
    {}
    handler-ks))