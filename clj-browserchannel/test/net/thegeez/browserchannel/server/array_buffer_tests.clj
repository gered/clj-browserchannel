(ns net.thegeez.browserchannel.server.array-buffer-tests
  (:use
    clojure.test
    net.thegeez.browserchannel.common
    net.thegeez.browserchannel.server)
  (:import (net.thegeez.browserchannel.server ArrayBuffer)))

(deftest basics-test
  (let [ab (ArrayBuffer. 0 0 (->queue) (->queue))]
    (is (= (to-flush ab)
       nil))
    (is (= (last-acknowledged-id ab)
       0))
    (is (= (outstanding-bytes ab)
       0)))

  (let [ab (ArrayBuffer. 3
                         0
                         (->queue)
                         (->queue
                           [1 ["one" :a]]
                           [2 ["two" :b]]
                           [3 ["three" :c]]))]
    (is (= (first (to-flush ab))
           [[1 ["one" :a]]
            [2 ["two" :b]]
            [3 ["three" :c]]]))
    (is (= (last-acknowledged-id ab)
           0))
    (is (= (outstanding-bytes ab)
           11)))

  (let [ab (ArrayBuffer. 3
                         0
                         (->queue
                           [1 ["one" :a]]
                           [2 ["two" :b]]
                           [3 ["three" :c]])
                         (->queue))]
    (is (= (first (to-flush ab))
           nil))
    (is (= (last-acknowledged-id ab)
           0))
    (is (= (outstanding-bytes ab)
           0))))

(deftest queue-tests
  (let [ab (-> (ArrayBuffer. 0 0 (->queue) (->queue))
               (queue "one" :a))]
    (is (= (first (to-flush ab))
           [[1 ["one" :a]]]))
    (is (= (last-acknowledged-id ab)
           0))
    (is (= (outstanding-bytes ab)
           3)))

  (let [ab (-> (ArrayBuffer. 1 0 (->queue) (->queue [1 ["one" :a]]))
               (queue "two" :b))]
    (is (= (first (to-flush ab))
           [[1 ["one" :a]]
            [2 ["two" :b]]]))
    (is (= (last-acknowledged-id ab)
           0))
    (is (= (outstanding-bytes ab)
           6)))

  (let [ab (-> (ArrayBuffer. 0 0 (->queue) (->queue))
               (queue nil nil))]
    (is (= (first (to-flush ab))
           [[1 [nil nil]]]))
    (is (= (last-acknowledged-id ab)
           0))
    (is (= (outstanding-bytes ab)
           0)))

  (let [ab (-> (ArrayBuffer. 0 0 (->queue) (->queue))
               (queue "one" :a)
               (queue "two" :b)
               (queue "three" :c))]
    (is (= (first (to-flush ab))
           [[1 ["one" :a]]
            [2 ["two" :b]]
            [3 ["three" :c]]]))
    (is (= (last-acknowledged-id ab)
           0))
    (is (= (outstanding-bytes ab)
           11)))

  (let [ab      (-> (ArrayBuffer. 0 0 (->queue) (->queue))
                    (queue "one" :a)
                    (queue "two" :b)
                    (queue "three" :c))
        flushed (second (to-flush ab))]
    (is (= (first (to-flush ab))
           [[1 ["one" :a]]
            [2 ["two" :b]]
            [3 ["three" :c]]]))
    (is (= (first (to-flush flushed))
           nil))))

(deftest acknowledge-no-existing-data-tests
  (let [ab (-> (ArrayBuffer. 0 0 (->queue) (->queue))
               (queue "one" :a)
               (queue "two" :b)
               (queue "three" :c)
               (queue "four" :d)
               (queue "five" :e))]
    (is (= (first (to-flush ab))
           [[1 ["one" :a]]
            [2 ["two" :b]]
            [3 ["three" :c]]
            [4 ["four" :d]]
            [5 ["five" :e]]]))
    (is (= (last-acknowledged-id ab)
           0))

    (let [ack-ab (acknowledge-id ab 2 nil)]
      (is (= (first (to-flush ack-ab))
             [[3 ["three" :c]]
              [4 ["four" :d]]
              [5 ["five" :e]]]))
      (is (= (last-acknowledged-id ack-ab)
             2))
      (is (= (outstanding-bytes ack-ab)
             13)))

    (let [ack-ab (acknowledge-id ab 0 nil)]
      (is (= (first (to-flush ack-ab))
             [[1 ["one" :a]]
              [2 ["two" :b]]
              [3 ["three" :c]]
              [4 ["four" :d]]
              [5 ["five" :e]]]))
      (is (= (last-acknowledged-id ack-ab)
             0))
      (is (= (outstanding-bytes ack-ab)
             19)))

    (let [ack-ab (acknowledge-id ab 6 nil)]
      (is (= (first (to-flush ack-ab))
             nil))
      (is (= (last-acknowledged-id ack-ab)
             6))
      (is (= (outstanding-bytes ack-ab)
             0)))))

(deftest acknowledge-existing-ack-data-tests
  (let [ab (-> (ArrayBuffer. 2
                             0
                             (->queue
                               [1 ["one" :a]]
                               [2 ["two" :b]])
                             (->queue))
               (queue "three" :c)
               (queue "four" :d)
               (queue "five" :e))]
    (is (= (first (to-flush ab))
           [[3 ["three" :c]]
            [4 ["four" :d]]
            [5 ["five" :e]]]))
    (is (= (last-acknowledged-id ab)
           0))

    (let [ack-ab (acknowledge-id ab 4 nil)]
      (is (= (first (to-flush ack-ab))
             [[5 ["five" :e]]]))
      (is (= (last-acknowledged-id ack-ab)
             4))
      (is (= (outstanding-bytes ack-ab)
             4)))

    (let [ack-ab (acknowledge-id ab 2 nil)]
      (is (= (first (to-flush ack-ab))
             [[3 ["three" :c]]
              [4 ["four" :d]]
              [5 ["five" :e]]]))
      (is (= (last-acknowledged-id ack-ab)
             2))
      (is (= (outstanding-bytes ack-ab)
             13)))

    (let [ack-ab (acknowledge-id ab 6 nil)]
      (is (= (first (to-flush ack-ab))
             nil))
      (is (= (last-acknowledged-id ack-ab)
             6))
      (is (= (outstanding-bytes ack-ab)
             0)))))

(deftest acknowledge-existing-to-flush-data-tests
  (let [ab (-> (ArrayBuffer. 2
                             0
                             (->queue)
                             (->queue
                               [1 ["one" :a]]
                               [2 ["two" :b]]))
               (queue "three" :c)
               (queue "four" :d)
               (queue "five" :e))]
    (is (= (first (to-flush ab))
           [[1 ["one" :a]]
            [2 ["two" :b]]
            [3 ["three" :c]]
            [4 ["four" :d]]
            [5 ["five" :e]]]))
    (is (= (last-acknowledged-id ab)
           0))

    (let [ack-ab (acknowledge-id ab 2 nil)]
      (is (= (first (to-flush ack-ab))
             [[3 ["three" :c]]
              [4 ["four" :d]]
              [5 ["five" :e]]]))
      (is (= (last-acknowledged-id ack-ab)
             2))
      (is (= (outstanding-bytes ack-ab)
             13)))

    (let [ack-ab (acknowledge-id ab 0 nil)]
      (is (= (first (to-flush ack-ab))
             [[1 ["one" :a]]
              [2 ["two" :b]]
              [3 ["three" :c]]
              [4 ["four" :d]]
              [5 ["five" :e]]]))
      (is (= (last-acknowledged-id ack-ab)
             0))
      (is (= (outstanding-bytes ack-ab)
             19)))

    (let [ack-ab (acknowledge-id ab 6 nil)]
      (is (= (first (to-flush ack-ab))
             nil))
      (is (= (last-acknowledged-id ack-ab)
             6))
      (is (= (outstanding-bytes ack-ab)
             0)))))

(deftest on-ack-fn-tests
  (let [acknowledged (atom [])
        on-ack-fn    (fn [[id [string context]]]
                       (swap! acknowledged conj context))
        ab           (-> (ArrayBuffer. 0 0 (->queue) (->queue))
                         (queue "one" :a)
                         (queue "two" :b)
                         (queue "three" :c)
                         (queue "four" :d)
                         (queue "five" :e))]
    (let [ack-ab (acknowledge-id ab 2 on-ack-fn)]
      (is (= @acknowledged
             [:a :b]))
      (reset! acknowledged []))

    (let [ack-ab (acknowledge-id ab 0 on-ack-fn)]
      (is (= @acknowledged
             []))
      (reset! acknowledged []))

    (let [ack-ab (acknowledge-id ab 6 on-ack-fn)]
      (is (= @acknowledged
             [:a :b :c :d :e]))
      (reset! acknowledged []))))