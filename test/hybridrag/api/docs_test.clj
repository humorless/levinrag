(ns hybridrag.api.docs-test
  "GET /api/v1/docs/{path} (SPEC.md §11): metadata + ACL-visible chunks,
   404 (not 403) for unreadable docs."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hybridrag.auth.token :as token]
            [hybridrag.fixtures :as fx]
            [hybridrag.web-fixtures :as wf]
            [jsonista.core :as json]))

(use-fixtures :once fx/with-sample-index)
(use-fixtures :each wf/with-app-users)

(defn- get-doc [path user]
  (let [resp ((wf/handler) {:request-method :get
                            :uri (str "/api/v1/docs/" path)
                            :scheme :http
                            :server-name "localhost"
                            :headers (cond-> {"accept" "application/json"}
                                       user (assoc "authorization" (str "Bearer " (:token (token/create-token! wf/*app* user "t")))))})]
    (update resp :body #(when % (json/read-value (if (string? %) % (slurp %)) json/keyword-keys-object-mapper)))))

(deftest test-api-docs
  (let [{:keys [status body]} (get-doc "hr/leave.md" "alice")]
    (is (= 200 status))
    (is (= "hr/leave.md" (:doc_path body)))
    (is (string? (:title body)))
    (is (seq (:chunks body)))
    (is (every? #(every? (partial contains? %) [:chunk_id :ordinal :section_trail :char_range]) (:chunks body))))
  (is (= 404 (:status (get-doc "hr/leave.md" "bob"))))
  (is (= "not_found" (get-in (get-doc "hr/leave.md" "bob") [:body :error :code])))
  (is (= 401 (:status (get-doc "hr/leave.md" nil)))))
