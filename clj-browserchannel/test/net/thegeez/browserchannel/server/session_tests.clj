(ns net.thegeez.browserchannel.server.session-tests
  (:import (net.thegeez.browserchannel.server ArrayBuffer Session))
  (:use
    clojure.test
    net.thegeez.browserchannel.common
    net.thegeez.browserchannel.server))

(def written-responses (atom []))

(def test-options
  (merge
    (select-keys
      default-options
      [:data-threshold
       :session-timeout-interval])
    {:heartbeat-interval (:keep-alive-interval default-options)}))

(defn mock-write
  [response data]
  (is (not (nil? response)))
  (swap! written-responses conj [:write data]))

(defn mock-write-end
  [response]
  (is (not (nil? response)))
  (swap! written-responses conj [:write-end]))

(defn mock-send-off
  [& _])

(defn mock-session-fixture [f]
  (with-redefs [write      mock-write
                write-end  mock-write-end
                send-off   mock-send-off]
    (f)
    (reset! written-responses [])))

(use-fixtures :each mock-session-fixture)

(deftest basic-queue-test
  (let [options      test-options
        back-channel {:respond    {}
                      :chunk      true
                      :bytes-sent 0}
        session      (-> (Session. "test-id"
                                   options
                                   back-channel
                                   (ArrayBuffer. 0 0 (->queue) (->queue))
                                   nil
                                   nil)
                         (queue-string "\"one\"")
                         (queue-string "\"two\""))]
    (is (= (type (:array-buffer session))
           ArrayBuffer))
    (is (= (first (to-flush (:array-buffer session)))
           [[1 "\"one\""]
            [2 "\"two\""]]))
    (is (= (last-acknowledged-id (:array-buffer session))
           0))
    (is (= (outstanding-bytes (:array-buffer session))
           10))))

(deftest basic-flush-test
  (let [options      test-options
        back-channel {:respond    {}
                      :chunk      true
                      :bytes-sent 0}
        session      (-> (Session. "test-id"
                                   options
                                   back-channel
                                   (ArrayBuffer. 0 0 (->queue) (->queue))
                                   nil
                                   nil)
                         (queue-string "\"one\"")
                         (queue-string "\"two\"")
                         (flush-buffer))]
    (is (= (first (to-flush (:array-buffer session)))
           nil))
    (is (= @written-responses
           [[:write "[[1,\"one\"]]"]
            [:write "[[2,\"two\"]]"]]))
    (is (= (get-in session [:back-channel :bytes-sent])
           10))))

(deftest flush-without-back-channel-test
  (let [options      test-options
        back-channel nil
        session      (-> (Session. "test-id"
                                   options
                                   back-channel
                                   (ArrayBuffer. 0 0 (->queue) (->queue))
                                   nil
                                   nil)
                         (queue-string "\"one\"")
                         (queue-string "\"two\"")
                         (flush-buffer))]
    (is (nil? (:back-channel session)))
    (is (= (first (to-flush (:array-buffer session)))
           [[1 "\"one\""]
            [2 "\"two\""]]))
    (is (= @written-responses
           []))))

(deftest flush-with-write-error-test
  (with-redefs [write (fn [response data]
                        ; simple way to intentionally trigger a failure when
                        ; flush-buffer internally calls write
                        (if (.contains data "fail")
                          (throw (new Exception "intentional write failure"))
                          (mock-write response data)))]
    (let [options      test-options
          back-channel {:respond    {}
                        :chunk      true
                        :bytes-sent 0}
          session      (-> (Session. "test-id"
                                     options
                                     back-channel
                                     (ArrayBuffer. 0 0 (->queue) (->queue))
                                     nil
                                     nil)
                           (queue-string "\"one\"")
                           (queue-string "\"two\"")
                           (queue-string "\"fail\"")
                           (queue-string "\"three\"")
                           (flush-buffer))]
      (is (= (first (to-flush (:array-buffer session)))
             [[1 "\"one\""]
              [2 "\"two\""]
              [3 "\"fail\""]
              [4 "\"three\""]]))
      (is (= @written-responses
             [[:write "[[1,\"one\"]]"]
              [:write "[[2,\"two\"]]"]
              [:write-end]]))
      (is (nil? (:back-channel session))))))
