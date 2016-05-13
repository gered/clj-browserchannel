(ns net.thegeez.browserchannel.server.test-channel-http-request-tests
  (:use
    clojure.test
    net.thegeez.browserchannel.common
    net.thegeez.browserchannel.server
    net.thegeez.browserchannel.test-async-adapter)
  (:require
    [cheshire.core :as json]
    [ring.util.response :as response]
    [ring.mock.request :as mock]))

(use-fixtures :each async-output-fixture)

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

;; http://web.archive.org/web/20121226064550/http://code.google.com/p/libevent-browserchannel-server/wiki/BrowserChannelProtocol#Get_Host_Prefixes

(deftest get-host-prefixes-test-incorrect-version
  (let [resp (app (mock/request
                    :get "/channel/test"
                    {"VER"  7
                     "MODE" "init"
                     "zx"   (random-string)
                     "t"    1}))]
    (is (= 400 (:status resp)))))

(deftest get-host-prefixes-test-incorrect-request
  (let [resp (app (mock/request
                    :get "/channel/test"))]
    (is (= 400 (:status resp)))))

(deftest get-host-prefixes-test-no-prefixes
  (let [resp (app (mock/request
                    :get "/channel/test"
                    {"VER"  protocol-version
                     "MODE" "init"
                     "zx"   (random-string)
                     "t"    1}))]
    (is (= 200 (:status resp)))
    (is (contains-all-of? (:headers resp) (:headers default-options)))
    (is (= (json/parse-string (:body resp))
           [nil nil]))))

(deftest get-host-prefixes-test-with-prefixes
  (let [options (merge default-options
                       {:host-prefixes ["a", "b", "c"]})
        resp    (app (mock/request
                       :get "/channel/test"
                       {"VER"  protocol-version
                        "MODE" "init"
                        "zx"   (random-string)
                        "t"    1})
                     options)
        body    (json/parse-string (:body resp))]
    (is (= 200 (:status resp)))
    (is (contains-all-of? (:headers resp) (:headers default-options)))
    (is (not (nil? (some #{(first body)} (:host-prefixes options)))))
    (is (nil? (second body)))))

(deftest buffering-proxy-test
  (let [resp       (app (mock/request
                          :get "/channel/test"
                          {"VER" protocol-version
                           "zx"  (random-string)
                           "t"   1}))
        async-resp @async-output]
    (is (= 200 (:status resp)))
    (is (= 200 (:status async-resp)))
    (is (contains-all-of? (:headers async-resp) (:headers default-options)))
    (is (not (:closed? async-resp)))
    (is (= "11111" (:body async-resp)))
    ; browserchannel's buffering proxy test is supposed to work by initially sending
    ; "11111", then waits for 2 seconds (without closing the response), then sends
    ; "2" and finally closes the response.
    (wait-for-scheduled-interval 2)
    (let [async-resp @async-output]
      (is (:closed? async-resp))
      (is (= "111112" (:body async-resp))))))
