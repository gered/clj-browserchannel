(ns net.thegeez.browserchannel.server.session-tests
  (:use
    clojure.test
    net.thegeez.browserchannel.common
    net.thegeez.browserchannel.server)
  (:import (net.thegeez.browserchannel.server ArrayBuffer Session)))

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
                         (queue-string "\"one\"" {:a "a"})
                         (queue-string "\"two\"" {:b "b"}))]
    (is (= (type (:array-buffer session))
           ArrayBuffer))
    (is (= (first (to-flush (:array-buffer session)))
           [[1 ["\"one\"" {:a "a"}]]
            [2 ["\"two\"" {:b "b"}]]]))
    (is (= (last-acknowledged-id (:array-buffer session))
           0))
    (is (= (outstanding-bytes (:array-buffer session))
           10))
    (close session nil "close")
    (wait-for-agent-send-offs)))

(deftest basic-queue-test-with-nil-contexts
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
                         (queue-string "\"one\"" {:a "a"})
                         (queue-string "\"two\"" nil))]
    (is (= (type (:array-buffer session))
           ArrayBuffer))
    (is (= (first (to-flush (:array-buffer session)))
           [[1 ["\"one\"" {:a "a"}]]
            [2 ["\"two\"" nil]]]))
    (is (= (last-acknowledged-id (:array-buffer session))
           0))
    (is (= (outstanding-bytes (:array-buffer session))
           10))
    (close session nil "close")
    (wait-for-agent-send-offs)))

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
                         (queue-string "\"one\"" {:a "a"})
                         (queue-string "\"two\"" {:b "b"})
                         (flush-buffer))]
    (is (= (first (to-flush (:array-buffer session)))
           nil))
    (is (= @written-responses
           [[:write "[[1,\"one\"]]"]
            [:write "[[2,\"two\"]]"]]))
    (is (= (get-in session [:back-channel :bytes-sent])
           10))
    (close session nil "close")
    (wait-for-agent-send-offs)))

(deftest basic-flush-test-with-nil-contexts
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
                         (queue-string "\"one\"" {:a "a"})
                         (queue-string "\"two\"" nil)
                         (flush-buffer))]
    (is (= (first (to-flush (:array-buffer session)))
           nil))
    (is (= @written-responses
           [[:write "[[1,\"one\"]]"]
            [:write "[[2,\"two\"]]"]]))
    (is (= (get-in session [:back-channel :bytes-sent])
           10))
    (close session nil "close")
    (wait-for-agent-send-offs)))

(deftest flush-without-back-channel-test
  (let [options      test-options
        back-channel nil
        session      (-> (Session. "test-id"
                                   options
                                   back-channel
                                   (ArrayBuffer. 0 0 (->queue) (->queue))
                                   nil
                                   nil)
                         (queue-string "\"one\"" {:a "a"})
                         (queue-string "\"two\"" {:b "b"})
                         (flush-buffer))]
    (is (nil? (:back-channel session)))
    (is (= (first (to-flush (:array-buffer session)))
           [[1 ["\"one\"" {:a "a"}]]
            [2 ["\"two\"" {:b "b"}]]]))
    (is (= @written-responses
           []))
    (close session nil "close")
    (wait-for-agent-send-offs)))

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
                           (queue-string "\"one\"" {:a "a"})
                           (queue-string "\"two\"" {:b "b"})
                           (queue-string "\"fail\"" {:c "c"})
                           (queue-string "\"three\"" {:d "d"})
                           (flush-buffer))]
      (is (= (first (to-flush (:array-buffer session)))
             [[1 ["\"one\"" {:a "a"}]]
              [2 ["\"two\"" {:b "b"}]]
              [3 ["\"fail\"" {:c "c"}]]
              [4 ["\"three\"" {:d "d"}]]]))
      (is (= @written-responses
             [[:write "[[1,\"one\"]]"]
              [:write "[[2,\"two\"]]"]
              [:write-end]]))
      (is (nil? (:back-channel session)))
      (close session nil "close")
      (wait-for-agent-send-offs))))

(deftest on-sent-callback-test
  (let [sent-count   (atom 0)
        on-sent      (fn []
                       (swap! sent-count inc))
        options      test-options
        back-channel {:respond    {}
                      :chunk      true
                      :bytes-sent 0}
        session      (-> (Session. "test-id"
                                   options
                                   back-channel
                                   (ArrayBuffer. 0 0 (->queue) (->queue))
                                   nil
                                   nil)
                         (queue-string "\"one\"" {:on-sent on-sent})
                         (queue-string "\"two\"" {:on-sent on-sent})
                         (flush-buffer))]
    (is (= (first (to-flush (:array-buffer session)))
           nil))
    (is (= @written-responses
           [[:write "[[1,\"one\"]]"]
            [:write "[[2,\"two\"]]"]]))
    (is (= (get-in session [:back-channel :bytes-sent])
           10))
    (close session nil "close")
    (wait-for-agent-send-offs)
    (is (= 2 @sent-count))))

(deftest on-error-callback-test
  (with-redefs [write (fn [response data]
                        ; simple way to intentionally trigger a failure when
                        ; flush-buffer internally calls write
                        (if (.contains data "fail")
                          (throw (new Exception "intentional write failure"))
                          (mock-write response data)))]
    (let [error-count  (atom 0)
          on-error     (fn []
                         (swap! error-count inc))
          options      test-options
          back-channel {:respond    {}
                        :chunk      true
                        :bytes-sent 0}
          session      (-> (Session. "test-id"
                                     options
                                     back-channel
                                     (ArrayBuffer. 0 0 (->queue) (->queue))
                                     nil
                                     nil)
                           (queue-string "\"one\"" {:on-error on-error})
                           (queue-string "\"two\"" {:on-error on-error})
                           (queue-string "\"fail\"" {:on-error on-error})
                           (queue-string "\"three\"" {:on-error on-error})
                           (flush-buffer))]
      (is (= (first (to-flush (:array-buffer session)))
             [[1 ["\"one\"" {:on-error on-error}]]
              [2 ["\"two\"" {:on-error on-error}]]
              [3 ["\"fail\"" {:on-error on-error}]]
              [4 ["\"three\"" {:on-error on-error}]]]))
      (is (= @written-responses
             [[:write "[[1,\"one\"]]"]
              [:write "[[2,\"two\"]]"]
              [:write-end]]))
      (is (nil? (:back-channel session)))
      (close session nil "close")
      (wait-for-agent-send-offs)
      ; even though 2 were still written, the way flush-buffer works currently
      ; is that on any error, the entire buffer contents are kept and retried
      ; on the next call. when close is called, any items left in the buffer
      ; are assumed to have not been sent due to error (either flush-buffer
      ; failed to write them out to the backchannel, or the client did not
      ; open a backchannel, etc.
      (is (= 4 @error-count)))))