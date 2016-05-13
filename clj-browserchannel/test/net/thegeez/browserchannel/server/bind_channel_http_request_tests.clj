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
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
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
        async-resp  @async-output]
    (wait-for-agent-send-offs)
    (let [status (get-status session-id)]
      (is (= 200 (:status back-resp)))
      (is (= 200 (:status async-resp)))
      (is (not (:closed? async-resp)))
      (is (contains-all-of? (:headers async-resp)
                            (dissoc (:headers options) "Content-Type")))
      (is (:connected? status))
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
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        back-resp   (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    (send-data session-id {:foo "bar"})
    (wait-for-agent-send-offs)
    (let [async-resp @async-output
          arrays     (get-response-arrays (:body async-resp))]
      (is (= 200 (:status back-resp)))
      (is (= 200 (:status async-resp)))
      (is (not (:closed? async-resp)))
      (is (contains-all-of? (:headers async-resp)
                            (dissoc (:headers options) "Content-Type")))
      (is (= (-> arrays ffirst second (get "__edn") (edn/read-string))
             {:foo "bar"})))))

(deftest send-multiple-data-to-client-test
  (let [options     default-options
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        back-resp   (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    (send-data session-id {:foo "bar"})
    (send-data session-id "hello, world")
    (wait-for-agent-send-offs)
    (let [async-resp @async-output
          arrays     (get-response-arrays (:body async-resp))]
      (is (= 200 (:status back-resp)))
      (is (= 200 (:status async-resp)))
      (is (not (:closed? async-resp)))
      (is (contains-all-of? (:headers async-resp)
                            (dissoc (:headers options) "Content-Type")))
      (is (= (get-edn-from-arrays arrays 0)
             {:foo "bar"}))
      (is (= (get-edn-from-arrays arrays 1)
             "hello, world")))))

(deftest send-data-to-client-after-backchannel-reconnect
  (let [options     default-options
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        back-resp   (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    (is (= 200 (:status back-resp)))
    ; close backchannel
    (send-off (get @sessions session-id) clear-back-channel)
    (wait-for-agent-send-offs)
    ; nothing should have been written
    (let [async-resp @async-output
          arrays    (get-response-arrays (:body async-resp))]
      (is (= 200 (:status async-resp)))
      (is (:closed? async-resp))
      (is (empty? arrays)))
    (reset! async-output {})
    ; open second backchannel
    (let [back-resp (app (->new-backchannel-request session-id))]
      (wait-for-agent-send-offs)
      (send-data session-id {:foo "bar"})
      (wait-for-agent-send-offs)
      (let [async-resp @async-output
            arrays     (get-response-arrays (:body async-resp))]
        (is (= 200 (:status back-resp)))
        (is (= 200 (:status async-resp)))
        (is (not (:closed? async-resp)))
        (is (= (get-edn-from-arrays arrays 0)
               {:foo "bar"}))))))

(deftest heartbeat-interval-when-active-backchannel-test
  (let [options     (-> default-options
                        (assoc :keep-alive-interval 2))
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        back-resp   (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    (is (:heartbeat-timeout @(get @sessions session-id)))
    (wait-for-scheduled-interval (:keep-alive-interval options))
    (let [async-resp @async-output
          arrays     (get-response-arrays (:body async-resp))]
      (is (= 200 (:status back-resp)))
      (is (= 200 (:status async-resp)))
      (is (not (:closed? async-resp)))
      (is (contains-all-of? (:headers async-resp)
                            (dissoc (:headers options) "Content-Type")))
      (is (= (get-raw-from-arrays arrays 0)
             ["noop"])))))

(deftest no-heartbeat-interval-without-active-backchannel-test
  (let [options     (-> default-options
                        (assoc :keep-alive-interval 2))
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)]
    (wait-for-agent-send-offs)
    (is (connected? session-id))
    (is (not (:heartbeat-timeout @(get @sessions session-id))))
    (wait-for-scheduled-interval (:keep-alive-interval options))
    (is (connected? session-id))
    (is (not (:heartbeat-timeout @(get @sessions session-id))))))

(deftest heartbeat-interval-is-reactivated-when-backchannel-reconnects-test
  (let [options     (-> default-options
                        (assoc :keep-alive-interval 2))
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        back-resp   (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    (is (:heartbeat-timeout @(get @sessions session-id)))
    ; close backchannel
    (send-off (get @sessions session-id) clear-back-channel)
    (wait-for-agent-send-offs)
    ; nothing should have been written
    (let [async-resp @async-output
          arrays    (get-response-arrays (:body async-resp))]
      (is (= 200 (:status async-resp)))
      (is (:closed? async-resp))
      (is (empty? arrays)))
    (reset! async-output {})
    ; open second backchannel
    (let [back-resp (app (->new-backchannel-request session-id))]
      (wait-for-agent-send-offs)
      (is (:heartbeat-timeout @(get @sessions session-id)))
      (wait-for-scheduled-interval (:keep-alive-interval options))
      (let [async-resp @async-output
            arrays     (get-response-arrays (:body async-resp))]
        (is (= 200 (:status back-resp)))
        (is (= 200 (:status async-resp)))
        (is (not (:closed? async-resp)))
        (is (= (get-raw-from-arrays arrays 0)
               ["noop"]))))))

(deftest session-timeout-without-active-backchannel-test
  (let [options     (-> default-options
                        (assoc :session-timeout-interval 3))
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)]
    (wait-for-agent-send-offs)
    (is (connected? session-id))
    (is (:session-timeout @(get @sessions session-id)))
    (wait-for-scheduled-interval (:session-timeout-interval options))
    (is (not (connected? session-id)))))

(deftest no-session-timeout-when-backchannel-active-test
  (let [options     (-> default-options
                        (assoc :session-timeout-interval 3))
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        back-resp   (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    (is (connected? session-id))
    (is (not (:session-timeout @(get @sessions session-id))))
    (wait-for-scheduled-interval (:session-timeout-interval options))
    (is (connected? session-id))
    (is (not (:session-timeout @(get @sessions session-id))))))

(deftest session-timeout-is-reactivated-after-backchannel-close-test
  (let [options     default-options
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        back-resp   (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    (is (connected? session-id))
    (is (not (:session-timeout @(get @sessions session-id))))
    ; close backchannel
    (send-off (get @sessions session-id) clear-back-channel)
    (wait-for-agent-send-offs)
    (is (connected? session-id))
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
        create-resp  (app (->new-session-request) options)
        session-id   (get-session-id create-resp)
        back-resp    (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    (is (= (get-in @event-output [:on-open :session-id])
           session-id))
    (is (map? (get-in @event-output [:on-open :request])))
    (is (nil? (:on-close @event-output)))
    ; close session
    (send-off (get @sessions session-id) close {:fake-request-map true} "close")
    (wait-for-agent-send-offs)
    (is (not (connected? session-id)))
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
        create-resp  (app (->new-session-request) options)
        session-id   (get-session-id create-resp)
        back-resp    (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
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
          [len status] (string/split (:body forward-resp) #"\n" 2)
          [backchannel-present last-bkch-array-id outstanding-bytes] (json/parse-string status)]
      (wait-for-agent-send-offs)
      (is (= 200 (:status forward-resp)))
      (is (> (Long/parseLong len) 0))
      (is (= 1 backchannel-present))
      (is (= 0 last-bkch-array-id))
      (is (= 0 outstanding-bytes))
      (is (= 1 (count @received)))
      (is (= session-id (:session-id (first @received))))
      (is (map? (:request (first @received))))
      (is (= (:data (first @received))
             {:foo "bar"}))
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
        create-resp  (app (->new-session-request) options)
        session-id   (get-session-id create-resp)
        back-resp    (app (->new-backchannel-request session-id))]
    (wait-for-agent-send-offs)
    (let [forward-resp  (app (->new-forwardchannel-request session-id 0 "hello 1"))
          result1       (parse-forward-response forward-resp)]
      (wait-for-agent-send-offs)
      (let [forward2-resp (app (->new-forwardchannel-request session-id 0 "hello 2"))
            result2       (parse-forward-response forward2-resp)]
        (wait-for-agent-send-offs)
        (is (= 0 (:last-bkch-array-id result1)))
        (is (= 0 (:last-bkch-array-id result2)))
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
        create-resp  (app (->new-session-request) options)
        session-id   (get-session-id create-resp)]
    (wait-for-agent-send-offs)
    (let [forward-resp  (app (->new-forwardchannel-request session-id 0 :foobar))
          result        (parse-forward-response forward-resp)]
      (wait-for-agent-send-offs)
      (is (= 0 (:backchannel-present result)))
      (is (= 0 (:last-bkch-array-id result)))
      (is (= 0 (:outstanding-bytes result)))
      (is (= 1 (count @received)))
      (is (= (first @received)
             :foobar))
      (is (connected? session-id)))))

(deftest forward-channel-response-has-correct-outstanding-bytes-test
  (let [received     (atom [])
        on-receive   (fn [session-id request data]
                       (swap! received conj data))
        options      (-> default-options
                         (assoc :events {:on-receive on-receive}))
        create-resp  (app (->new-session-request) options)
        session-id   (get-session-id create-resp)
        str-to-send  "this will get queued but not sent because no backchannel is open"
        queued-str   (pr-str (encode-map str-to-send))]
    (wait-for-agent-send-offs)
    (send-data session-id str-to-send)
    (wait-for-agent-send-offs)
    (let [forward-resp  (app (->new-forwardchannel-request session-id 0 42))
          result        (parse-forward-response forward-resp)]
      (wait-for-agent-send-offs)
      (is (= 0 (:backchannel-present result)))
      (is (= 0 (:last-bkch-array-id result)))
      (is (= (count queued-str) (:outstanding-bytes result)))
      (is (= 1 (count @received)))
      (is (= (first @received)
             42))
      (is (connected? session-id)))))

