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
  (let [options (update-in default-options [:headers] dissoc "Content-Type")
        resp    (app (-> (mock/request
                           :post "/channel/bind")
                         (mock/query-string
                           {"VER"  8
                            "RID"  1
                            "CVER" 8
                            "zx"   (random-string)
                            "t"    1}))
                     options)]
    (wait-for-agent-send-offs)
    (is (= 200 (:status resp)))
    (is (contains-all-of? (:headers resp) (:headers options)))
    (let [arrays (get-response-arrays (:body resp))
          [[id [c session-id host-prefix version]]] (first arrays)]
      (is (= "c" c))
      (is (and (string? session-id)
               (not (string/blank? session-id))))
      (is (nil? host-prefix))
      (is (= 8 version))
      (is (get @sessions session-id))
      (is (connected? session-id)))))

(deftest backchannel-request-with-no-session-test
  (let [resp (app (-> (mock/request
                        :get "/channel/bind")
                      (mock/query-string
                        {"VER"  8
                         "RID"  "rpc"
                         "CVER" 8
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
                        {"VER"  8
                         "RID"  "rpc"
                         "SID"  "foobar"
                         "CVER" 8
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
        {"VER"  8
         "RID"  1
         "CVER" 8
         "zx"   (random-string)
         "t"    1})))

(defn get-session-id
  [new-session-response]
  (is (= 200 (:status new-session-response)))
  (let [arrays (get-response-arrays (:body new-session-response))
        [[_ [c session-id _ _]]] (first arrays)]
    (is (= "c" c))
    (is (and (string? session-id)
             (not (string/blank? session-id))))
    session-id))

(deftest backchannel-request-test
  (let [options     (update-in default-options [:headers] dissoc "Content-Type")
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        back-resp   (app (-> (mock/request
                               :get "/channel/bind")
                             (mock/query-string
                               {"VER"  8
                                "RID"  "rpc"
                                "SID"  session-id
                                "CVER" 8
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
      (is (contains-all-of? (:headers async-resp) (:headers options)))
      (is (:connected? status))
      (is (:has-back-channel? status)))))

(defn ->new-backchannel-request
  [session-id]
  (-> (mock/request
        :get "/channel/bind")
      (mock/query-string
        {"VER"  8
         "RID"  "rpc"
         "SID"  session-id
         "CVER" 8
         "CI"   0
         "AID"  0
         "TYPE" "xmlhttp"
         "zx"   (random-string)
         "t"    1})))

(deftest backchannel-request-send-data-to-client-test
  (let [options     (update-in default-options [:headers] dissoc "Content-Type")
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        back-resp   (app (->new-backchannel-request session-id))]
    (send-data session-id {:foo "bar"})
    (wait-for-agent-send-offs)
    (let [async-resp @async-output
          arrays     (get-response-arrays (:body async-resp))]
      (is (= 200 (:status back-resp)))
      (is (= 200 (:status async-resp)))
      (is (not (:closed? async-resp)))
      (is (contains-all-of? (:headers async-resp) (:headers options)))
      (is (= (-> arrays ffirst second (get "__edn") (edn/read-string))
             {:foo "bar"})))))

(deftest backchannel-request-send-multiple-data-to-client-test
  (let [options     (update-in default-options [:headers] dissoc "Content-Type")
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        back-resp   (app (->new-backchannel-request session-id))]
    (send-data session-id {:foo "bar"})
    (send-data session-id "hello, world")
    (wait-for-agent-send-offs)
    (let [async-resp @async-output
          arrays     (get-response-arrays (:body async-resp))]
      (is (= 200 (:status back-resp)))
      (is (= 200 (:status async-resp)))
      (is (not (:closed? async-resp)))
      (is (contains-all-of? (:headers async-resp) (:headers options)))
      (is (= (get-edn-from-arrays arrays 0)
             {:foo "bar"}))
      (is (= (get-edn-from-arrays arrays 1)
             "hello, world")))))

(deftest backchannel-request-heartbeat-test
  (let [options     (-> default-options
                        (update-in [:headers] dissoc "Content-Type")
                        ; shortened heartbeat interval for the purposes of this test
                        (assoc :keep-alive-interval 2))
        create-resp (app (->new-session-request) options)
        session-id  (get-session-id create-resp)
        back-resp   (app (->new-backchannel-request session-id))]
    (wait-for-heartbeat-interval (:keep-alive-interval options))
    (let [async-resp @async-output
          arrays     (get-response-arrays (:body async-resp))]
      (is (= 200 (:status back-resp)))
      (is (= 200 (:status async-resp)))
      (is (not (:closed? async-resp)))
      (is (contains-all-of? (:headers async-resp) (:headers options)))
      (is (= (get-raw-from-arrays arrays 0)
             ["noop"])))))
