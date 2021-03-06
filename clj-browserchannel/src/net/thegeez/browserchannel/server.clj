(ns net.thegeez.browserchannel.server
  "BrowserChannel server implementation in Clojure."
  (:import
    [java.util.concurrent ScheduledExecutorService Executors TimeUnit Callable ScheduledFuture])
  (:require
    [clojure.tools.logging :as log]
    [clojure.edn :as edn]
    [cheshire.core :as json]
    [ring.middleware.params :as params]
    [net.thegeez.browserchannel.async-adapter :as async-adapter]
    [net.thegeez.browserchannel.utils :refer [get-middleware-handler-map run-middleware]]))

;; @todo: out of order acks and maps - AKH the maps at least is taken care of.
;; @todo use a more specific Exception for failing writes, which
;; indicate closed connection
;; @todo SSL in jetty-async-adapter
;; @todo session-timeout should deduct waiting time for the failed
;; sent heartbeat?




(def protocol-version 8)

(def ^:private noop-string "[\"noop\"]")

;; almost all special cases are for making this work with IE
(def ^:private ie-headers
  {"Content-Type" "text/html"})

;; appended to first write to ie to prevent whole page buffering
(def ^:private ie-stream-padding "7cca69475363026330a0d99468e88d23ce95e222591126443015f5f462d9a177186c8701fb45a6ffee0daf1a178fc0f58cd309308fba7e6f011ac38c9cdd4580760f1d4560a84d5ca0355ecbbed2ab715a3350fe0c479050640bd0e77acec90c58c4d3dd0f5cf8d4510e68c8b12e087bd88cad349aafd2ab16b07b0b1b8276091217a44a9fe92fedacffff48092ee693af\n")






;;;;; Utils
;; to create session ids
(defn- uuid
  []
  (str (java.util.UUID/randomUUID)))

(def ^:private scheduler (Executors/newScheduledThreadPool 1))

;; scheduling a task returns a ScheduledFuture, which can be stopped
;; with (.cancel task false) false says not to interrupt running tasks
(defn- schedule
  [^Callable f ^long secs]
  (.schedule ^ScheduledExecutorService scheduler f secs TimeUnit/SECONDS))

;; json responses are sent as "size-of-response\njson-response"
(defn- size-json-str
  [^String json]
  (let [size (alength (.getBytes json "UTF-8"))]
    (str size "\n" json)))

;; type preserving drop upto for queueus
(defn drop-queue
  [queue id on-drop]
  (let [head (peek queue)]
    (if-not head
      queue
      (if (< id (first head))
        queue
        (do
          (if on-drop (on-drop head))
          (recur (pop queue) id on-drop))))))

        
;; Key value pairs do not always come ordered by request number.
;; E.g. {req0_key1 val01, req1_key1 val11, req0_key2 val02, req1_key2 val12}
(defn transform-url-data
  [data]
  (let [ofs    (get data "ofs" "0")
        pieces (dissoc data "count" "ofs")]
    {:ofs  (Long/parseLong ofs)
     :maps (->> (for [[k v] pieces]
                  (let [[_ n k] (re-find #"req(\d+)_(\w+)" k)]
                    [(Long/parseLong n) {k v}]))
                (group-by first)  ; {0 [[0 [k1 v2]] [0 [k2 v2]]],1 [[1 [k1 v1]] [1 [k2 v2]]]}
                (sort-by first)   ;; order by request number so that messages are recieved on server in same order
                (map #(into {} (map second (val %)))))}))

;; maps are URL Encoded
;;;; URL Encoded data:
;;{ count: '2',
;;   ofs: '0',
;;   req0_x: '3',
;;   req0_y: '10',
;;   req1_abc: 'def'
;;} 
;;as :form-params in req:
;;{"count" "2"
;; "ofs" "0"
;; "req0_x" "3"
;; "req0_y" "10"
;; "req1_abc" "def"}
;; => 
;;{:ofs 0 :maps [{"x" "3" "y" "10"},{"abc": "def"}]}
(defn get-maps
  [req]
  (let [data (:form-params req)]
    (when-not (zero? (count data))
      ;; number of entries in form-params,
      ;; not (get "count" (:form-params req))
      ;; @todo "count" is currently not used to verify the number of
      ;; parsed maps
      (:maps (transform-url-data data)))))

;; rather crude but straight from google
(defn- error-response
  [status-code message]
  {:status status-code
   :body   (str "<html><body><h1>" message "</h1></body></html>")})

(defn- agent-error-handler-fn
  [id]
  (fn [ag e]
    (log/error e (str id " agent exception."))))

(defn- array-item-to-json-pair
  [item]
  (str "[" (first item) "," (first (second item)) "]"))

(defn decode-map
  [m]
  (if (contains? m "__edn")
    (edn/read-string (get m "__edn"))
    m))

(defn encode-map
  [data]
  {"__edn" (pr-str data)})

;;;;;; end of utils






;;;; listeners
;; @todo clean this up, perhaps store listeners in the session?
;; @todo replace with lamina?
;; sessionId -> :event -> [call back]
;; event: :map | :close
(def ^:private listeners-agent (agent {}))
(set-error-handler! listeners-agent (agent-error-handler-fn "listener"))
(set-error-mode! listeners-agent :continue)


(defn- add-listener!
  [session-id event-key f]
  (send-off listeners-agent
            update-in [session-id event-key] #(conj (or % []) f)))

(defn- notify-listeners!
  [session-id request event-key & data]
  (send-off listeners-agent
            (fn [listeners]
              (doseq [callback (get-in listeners [session-id event-key])]
                (apply callback session-id request data))
              listeners)))

(defn- remove-listeners!
  [session-id]
  (send-off listeners-agent dissoc session-id))
;; end of listeners





;; Wrapper around writers on continuations
;; the write methods raise an Exception with the wrapped response in closed
;; @todo use a more specific Exception
(defprotocol IResponseWrapper
  (write-head [this])
  (write [this data])
  (write-raw [this data])
  (write-end [this]))

;; for writing on backchannel to non-IE clients
(deftype XHRWriter
  [;; respond calls functions on the continuation
   respond
   headers]
  IResponseWrapper

  (write-head [this]
    (async-adapter/head respond 200 headers))

  (write [this data]
    (write-raw this (size-json-str data)))

  (write-raw [this data]
    (async-adapter/write-chunk respond data))

  (write-end [this]
    (async-adapter/close respond)))

;; for writing on backchannels to IE clients
(deftype IEWriter
  [;; respond calls functions on the continuation
   respond
   headers
   ;; DOMAIN value from query string
   domain
   ;; first write requires padding,
   ;; padding-sent is a flag for the first time
   ^{:volatile-mutable true} write-padding-sent
   ;; likewise for write raw, used during test phase
   ^{:volatile-mutable true} write-raw-padding-sent]
  IResponseWrapper

  (write-head [this]
    (async-adapter/head respond 200 (merge headers ie-headers))
    (async-adapter/write-chunk respond "<html><body>\n")
    (when (seq domain)
      (async-adapter/write-chunk respond (str "<script>try{document.domain=\"" (pr-str (json/generate-string domain)) "\";}catch(e){}</script>\n"))))

  (write [this data]
    (async-adapter/write-chunk respond (str "<script>try {parent.m(" (pr-str data) ")} catch(e) {}</script>\n"))
    (when-not write-padding-sent
      (async-adapter/write-chunk respond ie-stream-padding)
      (set! write-padding-sent true)))

  (write-raw [this data]
    (async-adapter/write-chunk respond (str "<script>try {parent.m(" (pr-str data) ")} catch(e) {}</script>\n"))
    (when-not write-raw-padding-sent
      (async-adapter/write-chunk respond ie-stream-padding)
      (set! write-raw-padding-sent true)))

  (write-end [this]
    (async-adapter/write-chunk respond "<script>try  {parent.d(); }catch (e){}</script>\n")
    (async-adapter/close respond)))

;;ArrayBuffer
;;buffer of [[id_lowest [data context]] ... [id_highest [data context]]]
(defprotocol IArrayBuffer
  (queue [this string context])
  (acknowledge-id [this id on-ack-fn])
  (to-flush [this])
  (last-acknowledged-id [this])
  (outstanding-bytes [this]))

(deftype ArrayBuffer
  [;; id of the last array that is conj'ed, can't
   ;; always be derived because flush buffer might
   ;; be empty
   array-id

   ;; needed for session status
   last-acknowledged-id

   ;; array that have been flushed, but not yet
   ;; acknowledged, does not contain noop messages
   to-acknowledge-arrays

   ;; arrays to be sent out, may contain arrays
   ;; that were in to-acknowledge-arrays but queued
   ;; again for resending
   ;; format of each array item is [id [data context]]
   ;; where data is the actual string and context is a map or nil
   to-flush-arrays]
  IArrayBuffer

  (queue [this string context]
    (let [next-array-id (inc array-id)]
      (ArrayBuffer. next-array-id
                    last-acknowledged-id
                    to-acknowledge-arrays
                    (conj to-flush-arrays [next-array-id [string context]]))))

  ;; id may cause the following splits:
  ;; normal case:
  ;; ack-arrs <id> flush-arrs
  ;; client is slow case:
  ;; ack-arrs <id> ack-arrs flush-arrs
  ;; after arrays have been requeued:
  ;; ack-arrs flush-arrs <id> flush-arrs
  ;; everything before id can be discarded, everything after id
  ;; becomes new flush-arrs and is resend
  (acknowledge-id [this id on-ack-fn]
    (ArrayBuffer. array-id
                  id
                  clojure.lang.PersistentQueue/EMPTY
                  (into (drop-queue to-acknowledge-arrays id on-ack-fn)
                        (drop-queue to-flush-arrays id on-ack-fn))))

  ;; return [seq-to-flush-array next-array-buffer] or nil if
  ;; to-flush-arrays is empty
  ;; format of each array item is [id [data context]]
  ;; where data is the actual string and context is a map or nil
  (to-flush [this]
    (when-let [to-flush (seq to-flush-arrays)]
      [to-flush (ArrayBuffer. array-id
                              last-acknowledged-id
                              (into to-acknowledge-arrays
                                    (remove (fn [[id [string context]]]
                                              (= string noop-string))
                                            to-flush))
                              clojure.lang.PersistentQueue/EMPTY)]))

  (last-acknowledged-id [this]
    last-acknowledged-id)

  ;; the sum of all the data that is still to be sent
  (outstanding-bytes [this]
    (reduce + 0 (map (comp count first second) to-flush-arrays))))






;; {sessionId -> (agent session)}
(def sessions (atom {}))

;; All methods meant to be fn send to an agent, therefor all need to return a Session
(defprotocol ISession
  ;; a session spans multiple connections, the connections for the
  ;; backward channel is the backchannel of a session
  (clear-back-channel [this])
  (set-back-channel [this
                     ;; respond is a wrapper of the continuation
                     respond
                     request])

  ;; messages sent from server to client are arrays
  ;; the client acknowledges received arrays when creating a new backwardchannel
  (acknowledge-arrays [this array-id-str])

  ;; context is either a map or nil
  (queue-string [this json-string context])

  ;; heartbeat is a timer to send noop over the backward channel
  (clear-heartbeat [this])
  (refresh-heartbeat [this])

  ;; after a backward channel closes the session is kept alive, so
  ;; the client can reconnect. If there is no reconnect before
  ;; session-timeout the session is closed
  (clear-session-timeout [this])
  (refresh-session-timeout [this])
  
  ;; try to send data to the client over the backchannel.
  ;; if there is no backchannel, then nothing happens
  (flush-buffer [this])

  ;; after close this session cannot be reconnected to.
  ;; removes session for sessions
  (close [this request message]))

(defrecord BackChannel
  [;; respond wraps the continuation, which is
   ;; the actual connection of the backward
   ;; channel to the client
   respond
   ;; when true use streaming
   chunk
   ;; this is used for diagnostic purposes by the client
   bytes-sent])

(defrecord Session
  [;; must be unique
   id

   ;; {:address
   ;;  :headers
   ;;  :app-version
   ;;  :heartbeat-interval
   ;;  :session-timeout-interval
   ;;  :data-threshold
   ;;}
   details

   ;; back-channel might be nil, as a session spans
   ;; multiple connections
   back-channel

   ;; ArrayBuffer
   array-buffer

   ;; ScheduleTask or nil
   heartbeat-timeout

   ;; ScheduleTask or nil
   ;; when the backchannel is closed from this
   ;; session, the session is only removes when this
   ;; timer expires during  this time the client can
   ;; reconnect to its session
   session-timeout]
  ISession

  (clear-back-channel [this]
    (log/trace id ": clear-back-channel" (if back-channel "(existing back-channel)" ""))
    (try
      (when back-channel
        (write-end (:respond back-channel)))
      (catch Exception e
        nil ;; close back channel regardless
        ))
    (-> this
        clear-heartbeat
        (assoc :back-channel nil)
        refresh-session-timeout))

  (set-back-channel [this respond req]
    (log/trace id ": set-back-channel")
    (let [bc (BackChannel. respond
                           ;; can we stream responses
                           ;; back?
                           ;; CI is determined client
                           ;; side with /test
                           (= (get-in req [:query-params "CI"]) "0")
                           ;; no bytes sent yet
                           0)]
      (-> this
          clear-back-channel
          ;; clear-back-channel sets the session-timeout
          ;; here we know the session is alive and
          ;; well due to this new backchannel
          clear-session-timeout
          (assoc :back-channel bc)
          refresh-heartbeat
          ;; try to send any data that was buffered
          ;; while there was no backchannel
          flush-buffer)))

  (clear-heartbeat [this]
    (when heartbeat-timeout
      (log/trace id ": clear-heartbeat")
      (.cancel ^ScheduledFuture heartbeat-timeout
               false ;; do not interrupt running tasks
               ))
    (assoc this :heartbeat-timeout nil))

  (refresh-heartbeat [this]
    (log/trace id ": refresh-heartbeat")
    (-> this
        clear-heartbeat
        (assoc :heartbeat-timeout
               ;; *agent* not bound when executed later
               ;; through schedule, therefor passed explicitly
               (let [session-agent *agent*]
                 (schedule
                   (fn []
                     (send-off session-agent #(-> %
                                                  (queue-string noop-string nil)
                                                  flush-buffer)))
                   (:heartbeat-interval details))))))

  (clear-session-timeout [this]
    (when session-timeout
      (log/trace id ": clear-session-timeout")
      (.cancel ^ScheduledFuture session-timeout
               false ;; do not interrupt running tasks
               ))
    (assoc this :session-timeout nil))

  (refresh-session-timeout [this]
    (log/trace id ": refresh-session-timeout")
    (-> this
        clear-session-timeout
        (assoc :session-timeout
               (let [session-agent *agent*]
                 (schedule
                   (fn []
                     (log/trace id ": session timed out")
                     (send-off session-agent close nil "Timed out"))
                   (:session-timeout-interval details))))))

  (queue-string [this json-string context]
    (log/trace id ": queue-string" (pr-str json-string) (if context "(has context)" ""))
    (update-in this [:array-buffer] queue json-string context))

  (acknowledge-arrays [this array-id-str]
    (log/trace id ": acknowledge-arrays" array-id-str)
    (let [array-id (Long/parseLong array-id-str)]
      (update-in this [:array-buffer] acknowledge-id array-id
                 (fn [[id [string {:keys [on-confirm]}]]]
                   (if on-confirm (on-confirm))))))

  ;; tries to do the actual writing to the client
  ;; @todo the composition is a bit awkward in this method due to the
  ;; try catch and if mix
  (flush-buffer [this]
    (log/trace id ": flush-buffer" (if-not back-channel "(no back-channel)" ""))
    (if-not back-channel
      this ;; nothing to do when there's no connection
      ;; only flush unacknowledged arrays
      (if-let [[to-flush next-array-buffer] (to-flush array-buffer)]
        (try
          (log/trace id ": flushing" (count to-flush) "arrays")
          ;; buffer contains [[1 [json-str context]] ...]
          ;; can't use json-str which will double escape the json

          (doseq [item to-flush]
            (let [{:keys [on-sent] :as context} (second (second item))]
              (write (:respond back-channel) (str "[" (array-item-to-json-pair item) "]"))
              (if on-sent (on-sent))))

          ;; size is an approximation
          (let [this (let [size (reduce + 0 (map count (map (comp first second) to-flush)))]
                       (-> this
                           (assoc :array-buffer next-array-buffer)
                           (update-in [:back-channel :bytes-sent] + size)))
                ;; clear-back-channel closes the back
                ;; channel when the channel does not
                ;; support streaming or when a large
                ;; amount of data has been sent
                this (if (or (not (get-in this [:back-channel :chunk]))
                             (< (:data-threshold details) (get-in this [:back-channel :bytes-sent])))
                       (clear-back-channel this)
                       this)]
            ;; this sending of data keeps the connection alive
            ;; make a new heartbeat
            (refresh-heartbeat this))
          (catch Exception e
            (log/trace e id ": exception during flush")
            ;; when write failed
            ;; non delivered arrays are still in buffer
            ;; (note that we don't raise any on-error callbacks for any items in the buffer
            ;; as we will retry sending the items next time since they're still in the buffer)
            (clear-back-channel this)
            ))
        this ;; do nothing if buffer is empty
        )))

  ;; closes the session and removes it from sessions
  (close [this request message]
    (log/trace id ": close" (if message (str "(message: " message ")") ""))
    (-> this
        clear-back-channel
        clear-session-timeout
        ;; the heartbeat timeout is cancelled by clear-back-channel
        )
    (swap! sessions dissoc id)
    ; raise callbacks for any remaining unsent messages
    (let [[remaining _] (to-flush array-buffer)]
      (doseq [[id [string {:keys [on-error] :as context}]] remaining]
        (if on-error (on-error))))
    ; finally raise the session close event
    (notify-listeners! id request :close message)
    (remove-listeners! id)
    nil ;; the agent will no longer wrap a session
    ))

(defn- handle-old-session-reconnect!
  [req old-session-id old-session-agent old-array-id]
  (log/trace old-session-id ": old session reconnect")
  (send-off old-session-agent
            (fn [this]
              (-> (if old-array-id
                    (acknowledge-arrays this old-array-id)
                    this)
                  (close req "Reconnected")))))

;; creates a session agent wrapping session data and
;; adds the session to sessions
(defn- create-session-agent!
  [req events options]
  (let [{initial-rid    "RID"  ;; identifier for forward channel
         app-version    "CVER" ;; client can specify a custom app-version
         old-session-id "OSID"
         old-array-id   "OAID"} (:query-params req)]
    ;; when a client specifies and old session id then that old one
    ;; needs to be removed
    (if-let [old-session-agent (@sessions old-session-id)]
      (handle-old-session-reconnect! req old-session-id old-session-agent old-array-id))
    (let [id            (uuid)
          details       {:address                  (:remote-addr req)
                         :headers                  (:headers req)
                         :app-version              app-version
                         :heartbeat-interval       (:keep-alive-interval options)
                         :session-timeout-interval (:session-timeout-interval options)
                         :data-threshold           (:data-threshold options)}
          session       (Session. id
                                  details
                                  nil ;; backchannel
                                  (ArrayBuffer. 0 ;; array-id, 0 is never used by the
                                                  ;; array-buffer, it is used by the
                                                  ;; first message with the session id.
                                                0 ;; last-acknowledged-id
                                                ;; to-acknowledge-arrays
                                                clojure.lang.PersistentQueue/EMPTY
                                                ;; to-flush-arrays
                                                clojure.lang.PersistentQueue/EMPTY)
                                                nil ;; heartbeat-timeout
                                                nil ;; session-timeout
                                                )
          session-agent (agent session)]
      (log/trace id ": new session created" (if old-session-id (str "(old session id: " old-session-id ")") ""))
      (set-error-handler! session-agent (agent-error-handler-fn (str "session-" (:id session))))
      (set-error-mode! session-agent :continue)
      ;; add new session-agent to our list of active browserchannel sessions
      (swap! sessions assoc id session-agent)
      ;; this first session-timeout is for the case
      ;; when the client never connects with a backchannel
      (send-off session-agent refresh-session-timeout)
      ;; register application-level browserchannel session events
      (let [middleware (get-middleware-handler-map
                         (:middleware options)
                         [:on-open :on-close :on-receive])]
        (add-listener!
          id :close
          (fn [session-id request reason]
            (run-middleware (:on-close middleware) (:on-close events) session-id request reason)))
        (add-listener!
          id :map
          (fn [session-id request data]
            (run-middleware (:on-receive middleware) (:on-receive events) session-id request data)))
        ; on-open is just triggered right now, no listener necessary
        (run-middleware (:on-open middleware) (:on-open events) id req))
      session-agent)))

(defn- session-status
  [session]
  (let [has-back-channel (if (:back-channel session) 1 0)
        array-buffer     (:array-buffer session)]
    [has-back-channel (last-acknowledged-id array-buffer) (outstanding-bytes array-buffer)]))






;; convience function to send arbitrary clojure data structures to a session
;; the data will be queued until there is a backchannel to send it over
(defn- send-raw-data!
  [session-id data context]
  (when-let [session-agent (get @sessions session-id)]
    (let [string (json/generate-string data)]
      (send-off session-agent
                #(-> %
                     (queue-string string context)
                     flush-buffer))
      string)))

(defn send-data!
  "sends data to the client identified by session-id over the backchannel.
   if there is currently no available backchannel for this client, the data
   is queued until one is available. context can contain optional callback
   functions:

   on-sent    - when the data has been written to an active backchannel
   on-confirm - when the client confirms it has received the data. note that
                it may take awhile (minutes) for the client to send an
                acknowledgement
   on-error   - when there was an error (of any kind) sending the data"
  [session-id data & [context]]
  (if data
    (send-raw-data! session-id (encode-map data) context)))

(defn send-data-to-all!
  "sends data to all currently connected clients over their backchannels.
   context can contain optional callback functions which will be used for
   all the data sent. see send-data for a description of the different
   callback functions available."
  [data & [context]]
  (doseq [[session-id _] @sessions]
    (send-data! session-id data context)))

(defn connected?
  "returns true if a client with the given session-id is currently connected."
  [session-id]
  (contains? @sessions session-id))

(defn disconnect!
  "forcefully and instantly closes/disconnects the client session
   identified by session-id.
   NOTE: the client code in net.thegeez.browserchannel.client will treat
         this as an error and may try to reconnect. if you do not wish this
         to happen, use close! instead."
  [session-id & [reason]]
  (if-let [session-agent (get @sessions session-id)]
    (send-off session-agent close nil (or reason "Client disconnected by server"))))

(defn close!
  "sends a message to the client requesting that it not attempt a
   reconnection and when the message has been sent over the backchannel,
   closes/disconnects the session.
   NOTE: because we wait for a message to be sent before closing the session,
         it is possible the session won't be closed right away if e.g. the
         client does not currently have an active backchannel."
  [session-id & [reason]]
  (send-raw-data! session-id ["stop"] {:on-sent #(disconnect! session-id reason)}))

(defn get-status
  "returns connection status info about the client identified by session-id"
  [session-id]
  (if (connected? session-id)
    (let [session-agent    @(get @sessions session-id)
          status           (session-status session-agent)
          has-back-channel (first status)]
      (merge
        {:connected?                    true
         :has-back-channel?             (= 1 has-back-channel)
         :last-acknowledged-array-id    (second status)
         :outstanding-backchannel-bytes (nth status 2)}
        (select-keys (:details session-agent) [:address :headers])))
    {:connected? false}))





;; wrap the respond function from :reactor with the proper
;; responsewrapper for either IE or other clients
(defn- wrap-continuation-writers
  [handler options]
  (fn [req]
    (let [res (handler req)]
      (if (:async res)
        (let [reactor (:reactor res)
              type    (get-in req [:query-params "TYPE"])]
          (assoc res :reactor
                     (fn [respond]
                       (reactor (let [headers (assoc (:headers options)
                                                "Transfer-Encoding" "chunked")]
                                  (if (= type "html")
                                    (let [domain (get-in req [:query-params "DOMAIN"])]
                                      ;; last two false are the padding
                                      ;; sent flags
                                      (IEWriter. respond headers domain false false))
                                    (XHRWriter. respond headers)))))))
        res ;; do not touch responses without :async
        ))))

;; test channel is used to determine which host to connect to
;; and if the connection can support streaming
(defn- handle-test-channel
  [req options]
  (if-not (= (str protocol-version) (get-in req [:query-params "VER"]))
    (error-response 400 (str "Version " protocol-version " required"))
    ;; phase 1
    ;; client requests [random host-prefix or
    ;; nil,blockedPrefix]
    ;; blockedPrefix not supported, always nil
    (if (= (get-in req [:query-params "MODE"]) "init")
      (let [host-prefix (when-let [prefixes (seq (:host-prefixes options))]
                          (rand-nth prefixes))]
        {:status  200
         :headers (assoc (:headers options) "X-Accept" "application/json; application/x-www-form-urlencoded")
         :body    (json/generate-string [host-prefix, nil])})

      ;; else phase 2 for get /test
      ;; client checks if connection is buffered
      ;; send 11111, wait 2 seconds, send 2
      ;; if client gets two chunks, then there is no buffering
      ;; proxy in the way
      {:async   :http
       :reactor (fn [respond]
                  (write-head respond)
                  (write-raw respond "11111")
                  (schedule #(do (write-raw respond "2")
                                 (write-end respond))
                            2))})))

;; POST req client -> server is a forward channel
;; session might be nil, when this is the first POST by client
(defn- handle-forward-channel
  [req session-agent events options]
  (let [[session-agent is-new-session] (if session-agent
                                         [session-agent false]
                                         [(create-session-agent! req events options) true])
        ;; maps contains whatever the messages to the server
        maps (get-maps req)]
    ;; if maps were received in this request, we should forward to listeners
    ;; (client is allowed to send maps in the new session request as well,
    ;; so for both existing sessions and new sessions we do this)
    (if (and (seq maps)
             session-agent)
      (doseq [m maps]
        (let [decoded (decode-map m)]
          (notify-listeners! (:id @session-agent) req :map decoded))))
    (if is-new-session
      ;; first post after a new session is a message with the session
      ;; details.
      ;; response is first array sent for this session:
      ;; [[0,["c", session-id, host-prefix, version (always equal to protocol-version)]]]
      ;; send as json for XHR and IE
      ;; TODO: the server is allowed to flush any queued arrays and include them in the
      ;;       response of this new-session request here (after the required first array
      ;;       with the session-id, etc in it).
      (let [session     @session-agent
            session-id  (:id session)
            ;; @todo extract the used host-prefix from the request if any
            host-prefix nil]
        {:status  200
         :headers (assoc (:headers options) "Content-Type" "application/javascript")
         :body    (size-json-str (json/generate-string [[0, ["c", session-id, host-prefix, protocol-version]]]))})
      ;; For existing sessions:
      ;; reply with
      ;; [backchannelPresent,lastPostResponseArrayId_,numOutstandingBackchannelBytes]
      ;; backchannelPresent = 0 for false, 1 for true
      ;; send as json for XHR and IE
      (let [status (session-status @session-agent)]
        {:status  200
         :headers (:headers options)
         :body    (size-json-str (json/generate-string status))}))))

;; GET req server->client is a backwardchannel opened by client
(defn- handle-backward-channel
  [req session-agent events options]
  (let [type (get-in req [:query-params "TYPE"])
        rid  (get-in req [:query-params "RID"])]
    (cond
      (#{"xmlhttp" "html"} type)
      (if-not (= "rpc" rid)
        (error-response 400 "Invalid RID")
        {:async   :http
         :reactor (fn [respond]
                    (write-head respond)
                    (send-off session-agent set-back-channel respond req))})

      (= type "terminate")
      (do
        ;; this is a request made in an img tag
        ;;end session
        (when session-agent
          (send-off session-agent close req "Client disconnected"))
        {:status  200
         :headers (:headers options)
         :body    ""}
        ))))

;; get to /<base>/bind is client->server msg
;; post to /<base>/bind is initiate server->client channel
(defn- handle-bind-channel
  [req events options]
  (let [SID           (get-in req [:query-params "SID"])
        ;; session-agent might be nil, then it will be created by
        ;; handle-forward-channel.
        ;; however, we must have a non-nil session-agent (and hence, a
        ;; pre-existing session) for all invocations of handle-backward-channel
        session-agent (@sessions SID)]
    (if (or (and (= :get (:request-method req))
                 (not session-agent))
            (and SID
                 (not session-agent)))
      ;; SID refers to an already created session, which therefore
      ;; must exist
      (error-response 400 "Unknown SID")
      (do
        ;; client can tell the server which array it has seen
        ;; up to including AID can be removed from the buffer
        (when session-agent
          (when-let [AID (get-in req [:query-params "AID"])]
            (send-off session-agent acknowledge-arrays AID)))
        (condp = (:request-method req)
          :post (handle-forward-channel req session-agent events options)
          :get (handle-backward-channel req session-agent events options))))))

;; straight from google
(def standard-headers
  {"Content-Type"           "text/plain"
   "Cache-Control"          "no-cache, no-store, max-age=0, must-revalidate"
   "Pragma"                 "no-cache"
   "Expires"                "Fri, 01 Jan 1990 00:00:00 GMT"
   "X-Content-Type-Options" "nosniff"})

(def default-options
  "default options that will be applied by wrap-browserchannel unless
   overridden."
  {
   ;; list of extra subdomain prefixes on which clients can connect
   ;; e.g. a.example, b.example => ["a","b"]
   :host-prefixes            []

   ;; additional response headers. should probably always include
   ;; net.thegeez.browserchannel.server/standard-headers if you
   ;; change this
   :headers                  standard-headers

   ;; base/root url on which to bind the '/test' and '/bind' routes to
   :base                     "/channel"

   ;; interval at which keepalive responses are sent to help ensure
   ;; that clients don't close backchannel connections early.
   ;; specified in seconds. keep less then session-timeout-interval
   :keep-alive-interval      30

   ;; seconds to wait without any client activity before the
   ;; connection is closed.
   :session-timeout-interval 120

   ;; number of bytes that can be sent over a single backchannel
   ;; connection before it is forced closed (at which point the
   ;; client will open a new one)
   :data-threshold           (* 10 1024)
   })


(defn wrap-browserchannel
  "adds browserchannel support to a ring handler.

   events should be a map of event handler functions. you only need to
   include handler functions for events you care about.

   {:on-open    (fn [session-id request] ...)
    :on-close   (fn [session-id request reason] ...)
    :on-receive (fn [session-id request data] ...)}

   for all events, request is a Ring request map and can be used to access
   up to date cookie or (http) session data, or other client info. you
   cannot use these event handlers to update the client's (http) session
   data as no response is returned via these event handler functions.

   for the supported options, see
   net.thegeez.browserchannel.server/default-options"
  [handler events & [options]]
  (let [options (merge default-options options)
        base    (str (:base options))]
    (-> (fn [req]
          (let [uri    ^String (:uri req)
                method (:request-method req)]
            (cond
              (and (.startsWith uri (str base "/test"))
                   (= method :get))
              (handle-test-channel req options)

              (.startsWith uri (str base "/bind"))
              (handle-bind-channel req events options)

              :else
              (handler req))))
        (wrap-continuation-writers options)
        params/wrap-params)))
