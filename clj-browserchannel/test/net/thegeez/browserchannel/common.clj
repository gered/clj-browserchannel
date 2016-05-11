(ns net.thegeez.browserchannel.common)

(defn ->queue
  [& x]
  (apply conj clojure.lang.PersistentQueue/EMPTY x))
