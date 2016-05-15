(ns net.thegeez.browserchannel.server.bind-channel-http-request-tests
  (:use
    clojure.test
    net.thegeez.browserchannel.common
    net.thegeez.browserchannel.server
    net.thegeez.browserchannel.test-async-adapter)
  (:require
    [clojure.edn :as edn]
    [clojure.string :as string]
    [cheshire.core :as json]
    [ring.util.response :as response]
    [ring.mock.request :as mock]))

(defn reset-sessions-fixture
  [f]
  (reset! sessions {})
  (f)
  (doseq [[_ session-agent] @sessions]
    (send-off session-agent close nil "reset-sessions-fixture"))
  ;; send-off dispatches on another thread, so we need to wait a small bit
  ;; before returning (otherwise the next test might start while a previous
  ;; test's session is still closing)
  (wait-for-agent-send-offs))

(use-fixtures :each reset-sessions-fixture async-output-fixture)

(defn app
  [request & [options]]
  ((-> (fn [{:keys [uri] :as request}]
         (if (or (= "" uri)
                 (= "/" uri))
           (response/response "Hello, world!")
           (response/not-found "not found")))
       (wrap-browserchannel (or options default-options))
       (wrap-test-async-adapter (or options default-options)))
    request))

(deftest create-session-test
  (let [options default-options
        resp    (app (-> (mock/request
                           :post "/channel/bind")
                         (mock/query-string
                           {"VER"  protocol-version
                            "RID"  1
                            "CVER" protocol-version
                            "zx"   (random-string)
                            "t"    1}))
                     options)]
    (wait-for-agent-send-offs)
    (is (= 200 (:status resp)))
    (is (contains-all-of? (:headers resp)
                          (dissoc (:headers options) "Content-Type")))
    (let [arrays (get-response-arrays (:body resp))
          [[id [c session-id host-prefix version]]] (first arrays)]
      (is (= "c" c))
      (is (session-id-string? session-id))
      (is (nil? host-prefix))
      (is (= protocol-version version))
      (is (get @sessions session-id))
      (is (connected? session-id)))))

(deftest backchannel-request-with-no-session-test
  (let [resp (app (-> (mock/request
                        :get "/channel/bind")
                      (mock/query-string
                        {"VER"  protocol-version
                         "RID"  "rpc"
                         "CVER" protocol-version
                         "CI"   0
                         "AID"  0
                         "TYPE" "xmlhttp"
                         "zx"   (random-string)
                         "t"    1})))]
    (is (= 400 (:status resp)))
    (is (empty? @sessions))))

(deftest backchannel-request-with-invalid-sid-test
  (let [resp (app (-> (mock/request
                        :get "/channel/bind")
                      (mock/query-string
                        {"VER"  protocol-version
                         "RID"  "rpc"
                         "SID"  "foobar"
                         "CVER" protocol-version
                         "CI"   0
                         "AID"  0
                         "TYPE" "xmlhttp"
                         "zx"   (random-string)
                         "t"    1})))]
    (is (= 400 (:status resp)))
    (is (empty? @sessions))))

(defn ->new-session-request
  []
  (-> (mock/request
        :post "/channel/bind")
      (mock/query-string
        {"VER"  protocol-version
         "RID"  1
         "CVER" protocol-version
         "zx"   (random-string)
         "t"    1})))

(defn get-session-id
  [new-session-response]
  (is (= 200 (:status new-session-response)))
  (let [arrays (get-response-arrays (:body new-session-response))
        [[_ [c session-id _ _]]] (first arrays)]
    (is (= "c" c))
    (is (session-id-string? session-id))
    session-id))

(deftest create-session-and-open-backchannel-test
  (let [options     default-options
        ; 1. forwardchannel request to create session
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        ; 2. backchannel request (long request, async response)
        back-resp   (app (-> (mock/request
                               :get "/channel/bind")
                             (mock/query-string
                               {"VER"  protocol-version
                                "RID"  "rpc"
                                "SID"  session-id
                                "CVER" protocol-version
                                "CI"   0
                                "AID"  0
                                "TYPE" "xmlhttp"
                                "zx"   (random-string)
                                "t"    1})))
        ; 3. get backchannel response so far (basically nothing other then headers at this point)
        async-resp  @async-output]
    (wait-for-agent-send-offs)
    (let [status (get-status session-id)]
      (is (= 200 (:status back-resp)))
      (is (= 200 (:status async-resp)))
      ; 4. verify session is still connected, backchannel request still alive
      (is (not (:closed? async-resp)))
      (is (contains-all-of? (:headers async-resp)
                            (dissoc (:headers options) "Content-Type")))
      (is (connected? session-id))
      (is (:has-back-channel? status)))))

(defn ->new-backchannel-request
  [session-id]
  (-> (mock/request
        :get "/channel/bind")
      (mock/query-string
        {"VER"  protocol-version
         "RID"  "rpc"
         "SID"  session-id
         "CVER" protocol-version
         "CI"   0
         "AID"  0
         "TYPE" "xmlhttp"
         "zx"   (random-string)
         "t"    1})))

(deftest send-data-to-client-test
  (let [options     default-options
        ; 1. forwardchannel request to create session
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        ; 2. backchannel request (long request, async response)
        back-resp   (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    ; 3. send data to client along backchannel
    (send-data session-id {:foo "bar"})
    (wait-for-agent-send-offs)
    ; 4. get async response so far from the backchannel
    (let [async-resp @async-output
          arrays     (get-response-arrays (:body async-resp))]
      (is (= 200 (:status back-resp)))
      (is (= 200 (:status async-resp)))
      (is (not (:closed? async-resp)))
      (is (contains-all-of? (:headers async-resp)
                            (dissoc (:headers options) "Content-Type")))
      ; 5. verify data was sent
      (is (= (-> arrays ffirst second (get "__edn") (edn/read-string))
             {:foo "bar"})))))

(deftest send-multiple-data-to-client-test
  (let [options     default-options
        ; 1. forwardchannel request to create session
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        ; 2. backchannel request (long request, async response)
        back-resp   (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    ; 3. send data to client along backchannel
    (send-data session-id {:foo "bar"})
    (send-data session-id "hello, world")
    (wait-for-agent-send-offs)
    ; 4. get async response so far from the backchannel
    (let [async-resp @async-output
          arrays     (get-response-arrays (:body async-resp))]
      (is (= 200 (:status back-resp)))
      (is (= 200 (:status async-resp)))
      (is (not (:closed? async-resp)))
      (is (contains-all-of? (:headers async-resp)
                            (dissoc (:headers options) "Content-Type")))
      ; 5. verify data was sent
      (is (= (get-edn-from-arrays arrays 0)
             {:foo "bar"}))
      (is (= (get-edn-from-arrays arrays 1)
             "hello, world")))))

(deftest send-data-to-client-before-backchannel-created
  (let [options     default-options
        ; 1. forwardchannel request to create session
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)]
    (wait-for-agent-send-offs)
    ; 2. send data to client. no backchannel yet, so it should be queued in a buffer
    (send-data session-id {:foo "bar"})
    (wait-for-agent-send-offs)
    ; 3. backchannel request (long request, async response).
    ;    queued buffer of messages is flushed to backchannel when it first opens
    (let [back-resp (app (->new-backchannel-request session-id))]
      (wait-for-agent-send-offs)
      ; 4. get async response so far from the backchannel
      (let [async-resp @async-output
            arrays     (get-response-arrays (:body async-resp))]
        (is (= 200 (:status back-resp)))
        (is (= 200 (:status async-resp)))
        (is (not (:closed? async-resp)))
        (is (contains-all-of? (:headers async-resp)
                              (dissoc (:headers options) "Content-Type")))
        ; 5. verify data was sent
        (is (= (get-edn-from-arrays arrays 0)
               {:foo "bar"}))))))

(deftest send-data-to-client-after-backchannel-reconnect
  (let [options     default-options
        ; 1. forwardchannel request to create session
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        ; 2. backchannel request (long request, async response)
        back-resp   (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    (is (= 200 (:status back-resp)))
    ; 3. close backchannel. we do it via clear-back-channel directly as it better
    ;    simulates either a network disconnect or connection timeout (which can and will happen)
    (send-off (get @sessions session-id) clear-back-channel)
    (wait-for-agent-send-offs)
    ; 4. get async response so far from the backchannel. nothing should have been written at this point.
    ;    also, verify backchannel request has indeed been closed
    (let [async-resp @async-output
          arrays    (get-response-arrays (:body async-resp))]
      (is (= 200 (:status async-resp)))
      (is (:closed? async-resp))
      (is (empty? arrays)))
    ; reset async response output. hacky necessity due to the way we capture this output
    (reset! async-output {})
    ; 5. second backchannel request
    (let [back-resp (app (->new-backchannel-request session-id))]
      (wait-for-agent-send-offs)
      ; 6. send data to client along backchannel
      (send-data session-id {:foo "bar"})
      (wait-for-agent-send-offs)
      ; 7. get async response so far from the backchannel
      (let [async-resp @async-output
            arrays     (get-response-arrays (:body async-resp))]
        (is (= 200 (:status back-resp)))
        (is (= 200 (:status async-resp)))
        (is (not (:closed? async-resp)))
        ; 8. verify data was sent
        (is (= (get-edn-from-arrays arrays 0)
               {:foo "bar"}))))))

(deftest heartbeat-interval-when-active-backchannel-test
  (let [options     (-> default-options
                        (assoc :keep-alive-interval 2))
        ; 1. forwardchannel request to create session
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        ; 2. backchannel request (long request, async response)
        back-resp   (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    (is (:heartbeat-timeout @(get @sessions session-id)))
    ; 3. wait for heartbeat/keepalive interval to elapse
    (wait-for-scheduled-interval (:keep-alive-interval options))
    ; 4. get async response so far from the backchannel
    (let [async-resp @async-output
          arrays     (get-response-arrays (:body async-resp))]
      (is (= 200 (:status back-resp)))
      (is (= 200 (:status async-resp)))
      (is (not (:closed? async-resp)))
      (is (contains-all-of? (:headers async-resp)
                            (dissoc (:headers options) "Content-Type")))
      ; 5. verify data was sent ("noop" is the standard keepalive message that is sent)
      (is (= (get-raw-from-arrays arrays 0)
             ["noop"])))))

(deftest no-heartbeat-interval-without-active-backchannel-test
  (let [options     (-> default-options
                        (assoc :keep-alive-interval 2))
        ; 1. forwardchannel request to create session
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)]
    (wait-for-agent-send-offs)
    (is (connected? session-id))
    (is (not (:heartbeat-timeout @(get @sessions session-id))))
    ; 2. wait for heartbeat/keepalive interval to elapse. since there should be no
    ;    scheduled job for it, nothing should have changed after this wait
    (wait-for-scheduled-interval (:keep-alive-interval options))
    (is (connected? session-id))
    (is (not (:heartbeat-timeout @(get @sessions session-id))))))

(deftest heartbeat-interval-is-reactivated-when-backchannel-reconnects-test
  (let [options     (-> default-options
                        (assoc :keep-alive-interval 2))
        ; 1. forwardchannel request to create session
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        ; 2. backchannel request (long request, async response)
        back-resp   (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    (is (:heartbeat-timeout @(get @sessions session-id)))
    ; 3. close backchannel. we do it via clear-back-channel directly as it better
    ;    simulates either a network disconnect or connection timeout (which can and will happen)
    (send-off (get @sessions session-id) clear-back-channel)
    (wait-for-agent-send-offs)
    ; 4. get async response so far from the backchannel. nothing should have been written at this point.
    ;    also, verify backchannel request has indeed been closed
    (let [async-resp @async-output
          arrays    (get-response-arrays (:body async-resp))]
      (is (= 200 (:status async-resp)))
      (is (:closed? async-resp))
      (is (empty? arrays)))
    ; reset async response output. hacky necessity due to the way we capture this output
    (reset! async-output {})
    ; 5. second backchannel request
    (let [back-resp (app (->new-backchannel-request session-id))]
      (wait-for-agent-send-offs)
      (is (:heartbeat-timeout @(get @sessions session-id)))
      ; 6. wait for heartbeat/keepalive interval to elapse
      (wait-for-scheduled-interval (:keep-alive-interval options))
      ; 7. get async response so far from the backchannel
      (let [async-resp @async-output
            arrays     (get-response-arrays (:body async-resp))]
        (is (= 200 (:status back-resp)))
        (is (= 200 (:status async-resp)))
        (is (not (:closed? async-resp)))
        ; 8. verify data was sent ("noop" is the standard keepalive message that is sent)
        (is (= (get-raw-from-arrays arrays 0)
               ["noop"]))))))

(deftest session-timeout-without-active-backchannel-test
  (let [options     (-> default-options
                        (assoc :session-timeout-interval 3))
        ; 1. forwardchannel request to create session
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)]
    (wait-for-agent-send-offs)
    (is (connected? session-id))
    (is (:session-timeout @(get @sessions session-id)))
    ; 2. wait for session timeout interval to elapse
    (wait-for-scheduled-interval (:session-timeout-interval options))
    ; 3. verify the session is no longer connected (timed out and closed)
    (is (not (connected? session-id)))))

(deftest no-session-timeout-when-backchannel-active-test
  (let [options     (-> default-options
                        (assoc :session-timeout-interval 3))
        ; 1. forwardchannel request to create session
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        ; 2. backchannel request (long request, async response)
        back-resp   (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    (is (connected? session-id))
    (is (not (:session-timeout @(get @sessions session-id))))
    ; 2. wait for session timeout interval to elapse. since there should be no
    ;    scheduled job for it, nothing should have happened by the time this wait finishes
    (wait-for-scheduled-interval (:session-timeout-interval options))
    ; 3. should still have an active session
    (is (connected? session-id))
    (is (not (:session-timeout @(get @sessions session-id))))))

(deftest session-timeout-is-reactivated-after-backchannel-close-test
  (let [options     default-options
        ; 1. forwardchannel request to create session
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        ; 2. backchannel request (long request, async response)
        back-resp   (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    (is (connected? session-id))
    ; 3. no session timeout interval active because backchannel is active
    (is (not (:session-timeout @(get @sessions session-id))))
    ; 4. close backchannel. we do it via clear-back-channel directly as it better
    ;    simulates either a network disconnect or connection timeout (which can and will happen)
    (send-off (get @sessions session-id) clear-back-channel)
    (wait-for-agent-send-offs)
    (is (connected? session-id))
    ; 5. session timeout interval now active because backchannel was disconnected
    (is (:session-timeout @(get @sessions session-id)))))

(deftest open-and-close-event-handlers-test
  (let [event-output (atom {})
        on-open      (fn [session-id request]
                       (swap! event-output assoc :on-open {:session-id session-id
                                                           :request    request}))
        on-close     (fn [session-id request reason]
                       (swap! event-output assoc :on-close {:session-id session-id
                                                            :request    request
                                                            :reason     reason}))
        options      (-> default-options
                         (assoc :events {:on-open  on-open
                                         :on-close on-close}))
        ; 1. forwardchannel request to create session
        create-resp  (app (->new-session-request) options)
        session-id   (get-session-id create-resp)
        ; 2. backchannel request (long request, async response)
        back-resp    (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    ; 3. verify that on-open event was fired and was passed correct arguments
    ;    (see on-open function defined above)
    (is (= (get-in @event-output [:on-open :session-id])
           session-id))
    (is (map? (get-in @event-output [:on-open :request])))
    ; 4. on-close not fired yet
    (is (nil? (:on-close @event-output)))
    ; 5. disconnect the session. this does the same basic thing as disconnect!, but we
    ;    call send-off and close directly like this so we can test passing a (fake) request
    ;    map just to verify that it makes it to the on-close function
    (send-off (get @sessions session-id) close {:fake-request-map true} "close")
    (wait-for-agent-send-offs)
    (is (not (connected? session-id)))
    ; 6. verify that on-close event was fired and passed correct arguments
    ;    (see on-close function defined above)
    (is (= (:on-close @event-output)
           {:session-id session-id
            :request    {:fake-request-map true}
            :reason     "close"}))))

(deftest receive-data-from-client-test
  (let [received     (atom [])
        on-receive   (fn [session-id request data]
                       (swap! received conj {:session-id session-id
                                             :request    request
                                             :data       data}))
        options      (-> default-options
                         (assoc :events {:on-receive on-receive}))
        ; 1. forwardchannel request to create session
        create-resp  (app (->new-session-request) options)
        session-id   (get-session-id create-resp)
        ; 2. backchannel request (long request, async response)
        back-resp    (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    ; 3. forwardchannel request so simulate sending data from client to server
    (let [forward-resp (app (-> (mock/request
                                  :post "/channel/bind")
                                (mock/query-string
                                  {"VER"  protocol-version
                                   "SID"  session-id
                                   "AID"  0
                                   "RID"  1
                                   "CVER" protocol-version
                                   "zx"   (random-string)
                                   "t"    1})
                                (mock/body
                                  {"count"      "1"
                                   "ofs"        "0"
                                   "req0___edn" (pr-str {:foo "bar"})})))
          ; 4. parse response body
          [len status] (string/split (:body forward-resp) #"\n" 2)
          [backchannel-present last-bkch-array-id outstanding-bytes] (json/parse-string status)]
      (wait-for-agent-send-offs)
      ; 5. verify that the response was correct
      (is (= 200 (:status forward-resp)))
      (is (> (Long/parseLong len) 0))
      (is (= 1 backchannel-present))
      (is (= 0 last-bkch-array-id))
      (is (= 0 outstanding-bytes))
      ; 6. verify that the on-receive event was fired and passed correct arguments
      ;    (see on-close function defined above)
      (is (= 1 (count @received)))
      (is (= session-id (:session-id (first @received))))
      (is (map? (:request (first @received))))
      ; the received data
      (is (= (:data (first @received))
             {:foo "bar"}))
      (is (connected? session-id))
      (is (not (:closed? @async-output))))))

(deftest receive-multiple-data-from-client-in-one-request-test
  (let [received     (atom [])
        on-receive   (fn [session-id request data]
                       (swap! received conj {:session-id session-id
                                             :request    request
                                             :data       data}))
        options      (-> default-options
                         (assoc :events {:on-receive on-receive}))
        ; 1. forwardchannel request to create session
        create-resp  (app (->new-session-request) options)
        session-id   (get-session-id create-resp)
        ; 2. backchannel request (long request, async response)
        back-resp    (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    ; 3. forwardchannel request so simulate sending data from client to server
    ;    (2 maps included)
    (let [forward-resp (app (-> (mock/request
                                  :post "/channel/bind")
                                (mock/query-string
                                  {"VER"  protocol-version
                                   "SID"  session-id
                                   "AID"  0
                                   "RID"  1
                                   "CVER" protocol-version
                                   "zx"   (random-string)
                                   "t"    1})
                                (mock/body
                                  {"count"      "2"
                                   "ofs"        "0"
                                   "req0___edn" (pr-str {:foo "bar"})
                                   "req1___edn" (pr-str {:second "map"})})))
          ; 4. parse response body
          [len status] (string/split (:body forward-resp) #"\n" 2)
          [backchannel-present last-bkch-array-id outstanding-bytes] (json/parse-string status)]
      (wait-for-agent-send-offs)
      ; 5. verify that the response was correct
      (is (= 200 (:status forward-resp)))
      (is (> (Long/parseLong len) 0))
      (is (= 1 backchannel-present))
      (is (= 0 last-bkch-array-id))
      (is (= 0 outstanding-bytes))
      ; 6. verify that the on-receive event was fired and passed correct arguments
      ;    (see on-close function defined above)
      (is (= 2 (count @received)))
      (is (= session-id (:session-id (first @received))))
      (is (= session-id (:session-id (second @received))))
      (is (map? (:request (first @received))))
      (is (map? (:request (second @received))))
      ; the received data
      (is (= (:data (first @received))
             {:foo "bar"}))
      (is (= (:data (second @received))
             {:second "map"}))
      (is (connected? session-id))
      (is (not (:closed? @async-output))))))

(defn ->new-forwardchannel-request
  [session-id aid data]
  (-> (mock/request
        :post "/channel/bind")
      (mock/query-string
        {"VER"  protocol-version
         "SID"  session-id
         "AID"  aid
         "RID"  1
         "CVER" protocol-version
         "zx"   (random-string)
         "t"    1})
      (mock/body
        {"count"      "1"
         "ofs"        "0"
         "req0___edn" (pr-str data)})))

(defn parse-forward-response
  [response]
  (is (= 200 (:status response)))
  (is (not (string/blank? (:body response))))
  (let [[len status] (string/split (:body response) #"\n" 2)
        [backchannel-present last-array-id outstanding-bytes] (json/parse-string status)]
    (is (> (Long/parseLong len) 0))
    (is (number? backchannel-present))
    (is (number? last-array-id))
    (is (number? outstanding-bytes))
    {:length              (Long/parseLong len)
     :backchannel-present backchannel-present
     :last-bkch-array-id  last-array-id
     :outstanding-bytes   outstanding-bytes}))

(deftest receive-mulitple-data-from-client-test
  (let [received     (atom [])
        on-receive   (fn [session-id request data]
                       (swap! received conj data))
        options      (-> default-options
                         (assoc :events {:on-receive on-receive}))
        ; 1. forwardchannel request to create session
        create-resp  (app (->new-session-request) options)
        session-id   (get-session-id create-resp)
        ; 2. backchannel request (long request, async response)
        back-resp    (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    ; 3. forwardchannel request to send data from client to server
    (let [forward-resp  (app (->new-forwardchannel-request session-id 0 "hello 1"))
          result1       (parse-forward-response forward-resp)]
      (wait-for-agent-send-offs)
      ; 4. a second forwardchannel request to send more data from client to server
      (let [forward2-resp (app (->new-forwardchannel-request session-id 0 "hello 2"))
            result2       (parse-forward-response forward2-resp)]
        (wait-for-agent-send-offs)
        (is (= 0 (:last-bkch-array-id result1)))
        (is (= 0 (:last-bkch-array-id result2)))
        ; 5. verify that the on-receive event was fired for both pieces of data sent
        ;    (see on-close function defined above)
        (is (= 2 (count @received)))
        (is (= (first @received)
               "hello 1"))
        (is (= (second @received)
               "hello 2"))
        (is (connected? session-id))
        (is (not (:closed? @async-output)))))))

(deftest receive-data-from-client-without-backchannel-open-test
  (let [received     (atom [])
        on-receive   (fn [session-id request data]
                       (swap! received conj data))
        options      (-> default-options
                         (assoc :events {:on-receive on-receive}))
        ; 1. forwardchannel request to create session
        create-resp  (app (->new-session-request) options)
        session-id   (get-session-id create-resp)]
    (wait-for-agent-send-offs)
    ; 2. forwardchannel request to send data from client to server
    (let [forward-resp  (app (->new-forwardchannel-request session-id 0 :foobar))
          result        (parse-forward-response forward-resp)]
      (wait-for-agent-send-offs)
      (is (= 0 (:backchannel-present result)))
      (is (= 0 (:last-bkch-array-id result)))
      (is (= 0 (:outstanding-bytes result)))
      ; 5. verify that the on-receive event was fired
      ;    (see on-close function defined above)
      (is (= 1 (count @received)))
      (is (= (first @received)
             :foobar))
      (is (connected? session-id)))))

(deftest receive-data-from-client-in-session-create-request-test
  (let [received     (atom [])
        on-receive   (fn [session-id request data]
                       (swap! received conj {:session-id session-id
                                             :request    request
                                             :data       data}))
        options      (-> default-options
                         (assoc :events {:on-receive on-receive}))
        ; 1. forwardchannel request to create session. encode a map to be sent as part
        ;    of this request (encoded in same format as any other forwardchannel request
        ;    would do to send client->server data)
        create-resp  (app (-> (mock/request
                                :post "/channel/bind")
                              (mock/query-string
                                {"VER"  protocol-version
                                 "RID"  1
                                 "CVER" protocol-version
                                 "zx"   (random-string)
                                 "t"    1})
                              (mock/body
                                {"count"      "1"
                                 "ofs"        "0"
                                 "req0___edn" (pr-str {:msg "hello, world"})}))
                          options)
        session-id   (get-session-id create-resp)]
    (wait-for-agent-send-offs)
    (is (connected? session-id))
    ; 2. verify that the on-receive event was fired
    ;    (see on-close function defined above)
    (is (= 1 (count @received)))
    (is (= session-id (:session-id (first @received))))
    (is (map? (:request (first @received))))
    (is (= (:data (first @received))
           {:msg "hello, world"}))))

(deftest forward-channel-response-has-correct-outstanding-bytes-test
  (let [received     (atom [])
        on-receive   (fn [session-id request data]
                       (swap! received conj data))
        options      (-> default-options
                         (assoc :events {:on-receive on-receive}))
        ; 1. forwardchannel request to create session
        create-resp  (app (->new-session-request) options)
        session-id   (get-session-id create-resp)
        str-to-send  "this will get queued but not sent because no backchannel is open"
        queued-str   (pr-str (encode-map str-to-send))]
    (wait-for-agent-send-offs)
    ; 2. send data to client. no backchannel is active, so data will be queued in the buffer
    (send-data session-id str-to-send)
    (wait-for-agent-send-offs)
    ; 3. forwardchannel request to send data from client to server
    (let [forward-resp  (app (->new-forwardchannel-request session-id 0 42))
          result        (parse-forward-response forward-resp)]
      (wait-for-agent-send-offs)
      ; 4. verify that the response to the forwardchannel request contained correct backchannel
      ;    data buffer info (e.g. reflecting the fact that we have queued data to be sent)
      (is (= 0 (:backchannel-present result)))
      (is (= 0 (:last-bkch-array-id result)))
      (is (= (count queued-str) (:outstanding-bytes result)))
      ; 5. verify that the on-receive event was fired
      ;    (see on-close function defined above)
      (is (= 1 (count @received)))
      (is (= (first @received)
             42))
      (is (connected? session-id)))))

(deftest flush-buffer-write-failure-resends-buffer
  (let [failed-once? (atom false)
        ; 1. hacky way to trigger a backchannel write failure
        ;    this is made possible by test-only options via wrap-test-async-adapter
        fail-fn      (fn [data]
                       (if (and (not @failed-once?)
                                  (string? data)
                                  (.contains data "fail"))
                         (reset! failed-once? true)))
        options      (-> default-options
                         (assoc :fail-fn fail-fn))
        ; 2. forwardchannel request to create session
        create-resp  (app (->new-session-request) options)
        session-id   (get-session-id create-resp)]
    (wait-for-agent-send-offs)
    ; 3. queue up a bunch of data to be sent. no backchannel has been created yet,
    ;    so this will all sit in the buffer and get flushed once the backchannel
    ;    is opened
    (send-data session-id :first-queued)
    (send-data session-id :second-queued)
    (send-data session-id "fail")
    (send-data session-id :fourth-queued)
    ; 4. backchannel request (long request, async response)
    (let [back-resp (app (->new-backchannel-request session-id) options)]
      (wait-for-agent-send-offs)
      ; 5. an exception should have been thrown during the flush-buffer call, but
      ;    we should have sent out some of the data before then
      (is (= 200 (:status back-resp)))
      (let [arrays-up-till-fail (get-response-arrays (:body @async-output))]
        ; 6. verify that the backchannel was closed (because of the exception),
        ;    and that the first 2 pieces of data were sent
        (is (= 200 (:status @async-output)))
        (is (:closed? @async-output))
        (is (connected? session-id))
        (is (= :first-queued (get-edn-from-arrays arrays-up-till-fail 0)))
        (is (= :second-queued (get-edn-from-arrays arrays-up-till-fail 1)))
        ; hacky requirement for this unit test due to the way we capture async response output
        (reset! async-output {})
        ; 7. re-open backchannel. all 4 items should still be in the buffer and will
        ;    get flushed again at this point
        (let [back-resp (app (->new-backchannel-request session-id) options)]
          (wait-for-agent-send-offs)
          ; 8. at this point, all 4 items should have been sent and the backchannel request
          ;    should still be active
          (is (= 200 (:status back-resp)))
          (let [arrays (get-response-arrays (:body @async-output))]
            (is (= 200 (:status @async-output)))
            (is (not (:closed? @async-output)))
            (is (connected? session-id))
            (is (= :first-queued (get-edn-from-arrays arrays 0)))
            (is (= :second-queued (get-edn-from-arrays arrays 1)))
            (is (= "fail" (get-edn-from-arrays arrays 2)))
            (is (= :fourth-queued (get-edn-from-arrays arrays 3)))))))))
