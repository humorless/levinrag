(ns replware.levinrag.api.traces-test
  "GET /api/v1/traces/{id} (SPEC.md §11): admin or the trace's owner;
   anyone else and unknown ids → 404."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [jsonista.core :as json]
            [replware.levinrag.auth.token :as token]
            [replware.levinrag.fixtures :as fx]
            [replware.levinrag.trace :as trace]
            [replware.levinrag.web-fixtures :as wf]))

(use-fixtures :once fx/with-sample-index)
(use-fixtures :each wf/with-app-users)

(defn- get-trace [id user]
  (let [resp ((wf/handler) {:request-method :get
                            :uri (str "/api/v1/traces/" id)
                            :scheme :http
                            :server-name "localhost"
                            :headers {"accept" "application/json"
                                      "authorization" (str "Bearer " (:token (token/create-token! wf/*app* user "t")))}})]
    (update resp :body #(when % (json/read-value (if (string? %) % (slurp %)) json/keyword-keys-object-mapper)))))

(deftest test-api-traces
  (let [id (trace/write! wf/*app* {:username "alice"
                                   :kind :search
                                   :query "特休"
                                   :stages {:lexical {:ms 3
                                                      :top [["hr/leave.md::1" 1.5]]}
                                            :flags #{:acl-starvation}}})]
    (doseq [u ["alice" "admin"]]
      (let [{:keys [status body]} (get-trace id u)]
        (is (= 200 status) u)
        (is (= "特休" (:query body)))
        (is (= "alice" (:username body)))
        (is (= 3 (get-in body [:stages :lexical :ms])))))
    (is (= 404 (:status (get-trace id "bob"))))
    (is (= 404 (:status (get-trace (random-uuid) "admin"))))
    (is (= 404 (:status (get-trace "not-a-uuid" "admin"))))))
