(ns replware.levinrag.llm.embed-test
  "SPEC.md §4.2: an OpenAI-compatible server can answer HTTP 200 with an
   error payload; the embed client must treat any wrong shape as an embed
   dependency failure (503), not hand an empty vector to the index."
  (:require [clojure.test :refer [deftest is]]
            [replware.levinrag.llm.embed :as embed]
            [ring.adapter.jetty :as jetty]))

(defn- with-stub [body f]
  (let [server (jetty/run-jetty (fn [_] {:status 200
                                         :headers {"Content-Type" "application/json"}
                                         :body body})
                                {:port 0
                                 :join? false})
        port (.getLocalPort (aget (.getConnectors server) 0))]
    (try (f {:base-url (str "http://localhost:" port)
             :model "m"
             :api-key "k"})
         (finally (.stop server)))))

(defn- failure [body texts]
  (with-stub body
    (fn [cfg]
      (try (embed/embed-batch! cfg texts) nil
           (catch clojure.lang.ExceptionInfo e (ex-data e))))))

(deftest test-embed-ok
  (with-stub "{\"data\":[{\"embedding\":[0.1,0.2]},{\"embedding\":[0.3,0.4]}]}"
    (fn [cfg] (is (= [[0.1 0.2] [0.3 0.4]] (embed/embed-batch! cfg ["a" "b"]))))))

(deftest test-embed-wrong-shapes-are-dependency-failures
  (doseq [body ["{\"object\":\"error\",\"message\":\"model not loaded\"}"
                "{\"data\":[{\"embedding\":[0.1,0.2]}]}"
                "{\"data\":[{\"embedding\":null},{\"embedding\":[0.3]}]}"
                "{\"data\":[{\"embedding\":\"x\"},{\"embedding\":[0.3]}]}"]]
    (let [data (failure body ["a" "b"])]
      (is (= :embed (:llm/endpoint data)) body)
      (is (= 200 (:http/status data)) body))))
