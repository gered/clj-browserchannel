(ns net.thegeez.browserchannel.common)

(defn ->queue
  [& x]
  (apply conj clojure.lang.PersistentQueue/EMPTY x))

(defn random-string
  [& [n]]
  (let [n        (or n 12)
        chars    (map char (concat (range 48 58) (range 66 91) (range 97 123)))
        password (take n (repeatedly #(rand-nth chars)))]
    (reduce str password)))

(defn contains-all-of?
  [m other-m]
  (->> other-m
       (map (fn [[k v]]
              (= (get m k) v)))
       (remove true?)
       (empty?)))
