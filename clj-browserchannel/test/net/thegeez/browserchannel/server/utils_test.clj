(ns net.thegeez.browserchannel.server.utils-test
  (:use
    clojure.test
    net.thegeez.browserchannel.common
    net.thegeez.browserchannel.server))


(deftest request-param-parse-tests
  (is (= (transform-url-data
           {"count"    "2"
            "ofs"      "0"
            "req0_x"   "3"
            "req0_y"   "10"
            "req1_abc" "def"})
         {:ofs  0
          :maps [{"x" "3"
                  "y" "10"}
                 {"abc" "def"}]}))

  (is (= (get-maps
           {:form-params {"count"    "2"
                          "ofs"      "0"
                          "req0_x"   "3"
                          "req0_y"   "10"
                          "req1_abc" "def"}})
         [{"x" "3"
           "y" "10"}
          {"abc" "def"}]))
  (is (= (get-maps
           {:form-params {"count" "0"
                          "ofs"   "0"}})
         []))
  (is (= (get-maps
           {:form-params {}})
         nil)))

(deftest drop-queue-tests
  (let [q (->queue
            [1 "one"]
            [2 "two"]
            [3 "three"]
            [4 "four"]
            [5 "five"])]

    (= (drop-queue q 2)
       (->queue
         [3 "three"]
         [4 "four"]
         [5 "five"]))

    (= (drop-queue q 0)
       q)

    (= (drop-queue q 5)
       (->queue))))

(deftest encoded-map-tests
  (= (encode-map "hello, world")
     {"__edn" "\"hello, world\""})
  (= (decode-map {"__edn" "\"hello, world\""})
     "hello, world")

  (= (encode-map {:foo "bar"})
     {"__edn" "{:foo \"bar\"}"})
  (= (decode-map {"__edn" "{:foo \"bar\"}"})
     {:foo "bar"})

  (= (encode-map nil)
     {"__edn" "nil"})
  (= (decode-map {"__edn" "nil"})
     nil)

  (= (decode-map {:foo "bar"})
     {:foo "bar"}))
