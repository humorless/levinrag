(ns replware.levinrag.api.ingest-test
  "POST /api/v1/ingest and GET /api/v1/ingest/{job_id} (SPEC.md §11):
   admin only (404 otherwise), one job at a time (409)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [jsonista.core :as json]
            [replware.levinrag.auth.token :as token]
            [replware.levinrag.fixtures :as fx]
            [replware.levinrag.web-fixtures :as wf]))

(use-fixtures :once fx/with-sample-index)
(use-fixtures :each wf/with-app-users)

(defn- call [runner method uri user]
  (let [resp ((wf/handler :context {:ingest runner})
              {:request-method method
               :uri uri
               :scheme :http
               :server-name "localhost"
               :headers (cond-> {"accept" "application/json"}
                          user (assoc "authorization" (str "Bearer " (:token (token/create-token! wf/*app* user "t")))))})]
    (update resp :body #(when % (json/read-value (if (string? %) % (slurp %)) json/keyword-keys-object-mapper)))))

(deftest test-api-ingest
  (wf/with-blocking-runner
    (fn [r gate]
      (let [{:keys [status body]} (call r :post "/api/v1/ingest" "admin")
            id (:job_id body)
            job-status #(get-in (call r :get (str "/api/v1/ingest/" id) "admin") [:body :status])]
        (is (= 202 status))
        (is (string? id))
        (let [again (call r :post "/api/v1/ingest" "admin")]
          (is (= 409 (:status again)))
          (is (= "conflict" (get-in again [:body :error :code])))
          (is (= id (get-in again [:body :error :job_id]))))
        (is (= "running" (job-status)))
        (deliver gate true)
        (loop [i 0]
          (when (and (= "running" (job-status)) (< i 300))
            (Thread/sleep 100)
            (recur (inc i))))
        (let [{:keys [status body]} (call r :get (str "/api/v1/ingest/" id) "admin")]
          (is (= 200 status))
          (is (= "done" (:status body)))
          (is (pos? (get-in body [:report :added]))))
        (is (= 404 (:status (call r :get "/api/v1/ingest/nope" "admin"))))
        (is (= 404 (:status (call r :post "/api/v1/ingest" "alice"))))
        (is (= 404 (:status (call r :get (str "/api/v1/ingest/" id) "alice"))))
        (is (= 401 (:status (call r :post "/api/v1/ingest" nil))))))))
