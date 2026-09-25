(ns replware.levinrag.api.docs-test
  "GET /api/v1/docs/{path} (SPEC.md §11): metadata + ACL-visible chunks,
   404 (not 403) for unreadable docs."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [replware.levinrag.auth.token :as token]
            [replware.levinrag.docs :as docs]
            [replware.levinrag.fixtures :as fx]
            [replware.levinrag.web-fixtures :as wf]
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

(deftest test-lookup-split
  ;; SPEC.md §9.3: the admin bypass is its own function, not a flag
  (let [db (d/db fx/*index*)]
    (is (= "hr/leave.md" (get-in (docs/lookup-admin db "hr/leave.md") [:doc :doc/path])))
    (is (nil? (docs/lookup-admin db "nope.md")))
    (is (some? (docs/lookup-acl db (fx/principals "alice") "hr/leave.md")))
    (is (nil? (docs/lookup-acl db (fx/principals "bob") "hr/leave.md")))
    (testing "lookup-acl ignores :admin? — admins go through lookup-admin"
      (is (nil? (docs/lookup-acl db (fx/principals "admin") "hr/leave.md"))))
    (is (nil? (docs/lookup-acl db fx/nobody "public/handbook.md")))
    (is (nil? (docs/lookup-acl db (fx/principals "alice") "nope.md")))))
