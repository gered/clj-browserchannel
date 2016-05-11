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
                           [1 "one"]
                           [2 "two"]
                           [3 "three"]))]
    (is (= (first (to-flush ab))
           [[1 "one"]
            [2 "two"]
            [3 "three"]]))
    (is (= (last-acknowledged-id ab)
           0))
    (is (= (outstanding-bytes ab)
           11)))

  (let [ab (ArrayBuffer. 3
                         0
                         (->queue
                           [1 "one"]
                           [2 "two"]
                           [3 "three"])
                         (->queue))]
    (is (= (first (to-flush ab))
           nil))
    (is (= (last-acknowledged-id ab)
           0))
    (is (= (outstanding-bytes ab)
           0))))

(deftest queue-tests
  (let [ab (-> (ArrayBuffer. 0 0 (->queue) (->queue))
               (queue "one"))]
    (is (= (first (to-flush ab))
           [[1 "one"]]))
    (is (= (last-acknowledged-id ab)
           0))
    (is (= (outstanding-bytes ab)
           3)))

  (let [ab (-> (ArrayBuffer. 1 0 (->queue) (->queue [1 "one"]))
               (queue "two"))]
    (is (= (first (to-flush ab))
           [[1 "one"]
            [2 "two"]]))
    (is (= (last-acknowledged-id ab)
           0))
    (is (= (outstanding-bytes ab)
           6)))

  (let [ab (-> (ArrayBuffer. 0 0 (->queue) (->queue))
               (queue nil))]
    (is (= (first (to-flush ab))
           [[1 nil]]))
    (is (= (last-acknowledged-id ab)
           0))
    (is (= (outstanding-bytes ab)
           0)))

  (let [ab (-> (ArrayBuffer. 0 0 (->queue) (->queue))
               (queue "one")
               (queue "two")
               (queue "three"))]
    (is (= (first (to-flush ab))
           [[1 "one"]
            [2 "two"]
            [3 "three"]]))
    (is (= (last-acknowledged-id ab)
           0))
    (is (= (outstanding-bytes ab)
           11)))

  (let [ab      (-> (ArrayBuffer. 0 0 (->queue) (->queue))
                    (queue "one")
                    (queue "two")
                    (queue "three"))
        flushed (second (to-flush ab))]
    (is (= (first (to-flush ab))
           [[1 "one"]
            [2 "two"]
            [3 "three"]]))
    (is (= (first (to-flush flushed))
           nil))))

(deftest acknowledge-no-existing-data-tests
  (let [ab (-> (ArrayBuffer. 0 0 (->queue) (->queue))
               (queue "one")
               (queue "two")
               (queue "three")
               (queue "four")
               (queue "five"))]
    (is (= (first (to-flush ab))
           [[1 "one"]
            [2 "two"]
            [3 "three"]
            [4 "four"]
            [5 "five"]]))
    (is (= (last-acknowledged-id ab)
           0))

    (let [ack-ab (acknowledge-id ab 2)]
      (is (= (first (to-flush ack-ab))
             [[3 "three"]
              [4 "four"]
              [5 "five"]]))
      (is (= (last-acknowledged-id ack-ab)
             2))
      (is (= (outstanding-bytes ack-ab)
             13)))

    (let [ack-ab (acknowledge-id ab 0)]
      (is (= (first (to-flush ack-ab))
             [[1 "one"]
              [2 "two"]
              [3 "three"]
              [4 "four"]
              [5 "five"]]))
      (is (= (last-acknowledged-id ack-ab)
             0))
      (is (= (outstanding-bytes ack-ab)
             19)))

    (let [ack-ab (acknowledge-id ab 6)]
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
                               [1 "one"]
                               [2 "two"])
                             (->queue))
               (queue "three")
               (queue "four")
               (queue "five"))]
    (is (= (first (to-flush ab))
           [[3 "three"]
            [4 "four"]
            [5 "five"]]))
    (is (= (last-acknowledged-id ab)
           0))

    (let [ack-ab (acknowledge-id ab 4)]
      (is (= (first (to-flush ack-ab))
             [[5 "five"]]))
      (is (= (last-acknowledged-id ack-ab)
             4))
      (is (= (outstanding-bytes ack-ab)
             4)))

    (let [ack-ab (acknowledge-id ab 2)]
      (is (= (first (to-flush ack-ab))
             [[3 "three"]
              [4 "four"]
              [5 "five"]]))
      (is (= (last-acknowledged-id ack-ab)
             2))
      (is (= (outstanding-bytes ack-ab)
             13)))

    (let [ack-ab (acknowledge-id ab 6)]
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
                               [1 "one"]
                               [2 "two"]))
               (queue "three")
               (queue "four")
               (queue "five"))]
    (is (= (first (to-flush ab))
           [[1 "one"]
            [2 "two"]
            [3 "three"]
            [4 "four"]
            [5 "five"]]))
    (is (= (last-acknowledged-id ab)
           0))

    (let [ack-ab (acknowledge-id ab 2)]
      (is (= (first (to-flush ack-ab))
             [[3 "three"]
              [4 "four"]
              [5 "five"]]))
      (is (= (last-acknowledged-id ack-ab)
             2))
      (is (= (outstanding-bytes ack-ab)
             13)))

    (let [ack-ab (acknowledge-id ab 0)]
      (is (= (first (to-flush ack-ab))
             [[1 "one"]
              [2 "two"]
              [3 "three"]
              [4 "four"]
              [5 "five"]]))
      (is (= (last-acknowledged-id ack-ab)
             0))
      (is (= (outstanding-bytes ack-ab)
             19)))

    (let [ack-ab (acknowledge-id ab 6)]
      (is (= (first (to-flush ack-ab))
             nil))
      (is (= (last-acknowledged-id ack-ab)
             6))
      (is (= (outstanding-bytes ack-ab)
             0)))))
