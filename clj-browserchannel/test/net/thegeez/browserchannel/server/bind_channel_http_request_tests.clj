(ns net.thegeez.browserchannel.server.bind-channel-http-request-tests
  (:use
    clojure.test
    net.thegeez.browserchannel.common
    net.thegeez.browserchannel.server
    net.thegeez.browserchannel.test-async-adapter)
  (:require
    [clojure.string :as string]
    [cheshire.core :as json]
    [ring.util.response :as response]
    [ring.mock.request :as mock]))

(defn reset-sessions-fixture
  [f]
  (reset! sessions {})
  (f)
  (doseq [[_ session-agent] @sessions]
    (send-off session-agent close nil "reset-sessions-fixture")))

(use-fixtures :each async-output-fixture reset-sessions-fixture)

(defn parse-channel-response
  [body]
  (if-not (string/blank? body)
    (let [[len arrays] (string/split body #"\n" 2)
          arrays       (if-not (string/blank? arrays)
                         (json/parse-string arrays)
                         arrays)]
      [len arrays])
    body))

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
    (is (= 200 (:status resp)))
    (is (contains-all-of? (:headers resp) (:headers options)))
    (let [[len arrays] (parse-channel-response (:body resp))
          [[id [c session-id host-prefix version]]] arrays]
      (is (= "57" len))
      (is (= "c" c))
      (is (and (string? session-id)
               (not (string/blank? session-id))))
      (is (nil? host-prefix))
      (is (= 8 version)))))
