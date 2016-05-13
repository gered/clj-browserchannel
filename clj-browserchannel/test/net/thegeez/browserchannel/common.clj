(ns net.thegeez.browserchannel.common
  (:require
    [clojure.edn :as edn]
    [clojure.string :as string]
    [cheshire.core :as json]))

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

;; each chunk of arrays that the server sends out looks like this:
;;
;;   length_of_following_array\n
;;   [[array_id, array],
;;    [array_id, array],
;;    [array_id, array]]
;;
;; there may be 1 or more arrays. this splits each chunk up based on
;; the "length_of_following_array\n" part, and returns all the actual
;; arrays of data as one vector (all chunks together)
(defn get-response-arrays
  [body]
  (if (string? body)
    (as-> body x
          (string/split x #"\d+\n")
          (remove string/blank? x)
          (mapv json/parse-string x))))

(defn get-raw-from-arrays
  [arrays idx]
  (-> (get arrays idx) first second))

(defn get-edn-from-arrays
  [arrays idx]
  (-> (get-raw-from-arrays arrays idx)
      (get "__edn")
      (edn/read-string)))

;; HACK: sleep for an arbitrary period that is based off me throwing in a
;;       random "feels good" number in there... i think this says it all, really
(defn wait-for-agent-send-offs
  []
  (Thread/sleep 500))

(defn wait-for-scheduled-interval
  [secs]
  ; add 1 extra second just to be 100% safe
  ; (intervals specified to scheduled job/task timers are never _exact_)
  (Thread/sleep (+ 1000 (* 1000 secs))))

(defn session-id-string?
  [s]
  (and (string? s)
       (not (string/blank? s))))
