(ns hybridrag.llm.http-test
  (:require [clojure.test :refer :all]
            [hybridrag.llm.http :as http]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as response]))

(defn- stub-handler [status body]
  (fn [_request]
    (response/response body)
    (assoc (response/response body)
           :status status
           :headers {"Content-Type" "application/json"})))

(defn- start-stub!
  "Start a Jetty stub server. Returns [server url stop-fn]."
  [status body-str]
  (let [handler (stub-handler status body-str)
        server (jetty/run-jetty handler {:port 0
                                         :join? false})]
    (Thread/sleep 200)
    (let [connector (aget (.getConnectors server) 0)
          port (.getLocalPort connector)
          url (str "http://localhost:" port "/")]
      [server url #(.stop server)])))

(deftest test-post-json-success
  (let [[_server url stop-fn] (start-stub! 200 "{\"ok\": true}")]
    (try
      (is (= {:ok true}
             (http/post-json! {:url url
                               :api-key "secret-key-xyz"
                               :body {:q "hi"}
                               :connect-timeout-ms 2000
                               :read-timeout-ms 5000
                               :endpoint-kw :embed})))
      (finally (stop-fn)))))

(deftest test-post-json-error-status-includes-endpoint-and-excerpt-not-key
  (let [[_server url stop-fn] (start-stub! 401 "{\"error\": \"invalid api key\"}")]
    (try
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (http/post-json! {:url url
                                             :api-key "secret-key-xyz"
                                             :body {}
                                             :connect-timeout-ms 2000
                                             :read-timeout-ms 5000
                                             :endpoint-kw :rerank})))]
        (is (= :rerank (:llm/endpoint (ex-data e))))
        (is (= 401 (:http/status (ex-data e))))
        (is (not (re-find #"secret-key-xyz" (pr-str (ex-data e)))))
        (is (not (re-find #"secret-key-xyz" (ex-message e)))))
      (finally (stop-fn)))))
(deftest test-post-json-uses-plain-http-1-1
  ;; LM Studio's server never answers the JDK client's default h2c upgrade
  ;; attempt on http:// URLs, so every call hung until the read timeout.
  (let [seen (atom nil)
        server (jetty/run-jetty (fn [req]
                                  (reset! seen (select-keys req [:protocol :headers]))
                                  {:status 200
                                   :headers {"Content-Type" "application/json"}
                                   :body "{}"})
                                {:port 0
                                 :join? false})
        port (.getLocalPort (aget (.getConnectors server) 0))]
    (try
      (http/post-json! {:url (str "http://localhost:" port "/")
                        :api-key "k"
                        :body {}
                        :connect-timeout-ms 2000
                        :read-timeout-ms 5000
                        :endpoint-kw :embed})
      (is (= "HTTP/1.1" (:protocol @seen)))
      (is (not (contains? (:headers @seen) "upgrade")))
      (finally (.stop server)))))
