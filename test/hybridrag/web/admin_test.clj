(ns hybridrag.web.admin-test
  "Admin page (SPEC.md §12): ingest trigger + status polling, conflict,
   latest report, recent traces and trace detail; 404 for non-admins."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hybridrag.fixtures :as fx]
            [hybridrag.ingest.runner :as runner]
            [hybridrag.tmp :as tmp]
            [hybridrag.trace :as trace]
            [hybridrag.web-client :as wc]
            [hybridrag.web-fixtures :as wf]))

(def ^:dynamic *runner* nil)

(defn- with-runner
  "A runner over the sample index with a fresh data dir (no reports)."
  [t]
  (let [data (tmp/dir "admin-data")]
    (try
      (binding [*runner* (runner/make {:index-conn fx/*index*
                                       :corpus-dir "corpus-sample"
                                       :data-dir data
                                       :root-read-groups []
                                       :embed-fn fx/hash-embed})]
        (t))
      (finally (tmp/delete-tree! data)))))

;; one :each call: a second use-fixtures :each would replace the first
(use-fixtures :once fx/with-sample-index)
(use-fixtures :each wf/with-app-users with-runner)

(defn- admin [] (wf/logged-in "admin" :context {:ingest *runner*}))

(defn- htmx [c method uri]
  (if (= method :post)
    (wc/post! c uri {} :headers {"hx-request" "true"})
    (wc/request! c :get uri :headers {"hx-request" "true"})))

(defn- poll-done [c]
  (loop [i 0]
    (let [body (:body (htmx c :get "/admin/ingest/status"))]
      (if (or (str/includes? body "完成") (str/includes? body "失敗") (> i 300))
        body
        (do (Thread/sleep 100) (recur (inc i)))))))

(deftest test-admin-page
  (let [ids (doall (for [q ["甲" "乙" "丙"]]
                     (trace/write! wf/*app* {:username "alice"
                                             :kind :ask
                                             :query q
                                             :stages {}})))
        body (:body (wc/request! (admin) :get "/admin"))]
    (is (str/includes? body "hx-post=\"/admin/ingest\""))
    (is (str/includes? body "尚無 ingest 紀錄"))
    (doseq [id ids]
      (is (str/includes? body (str "/admin/traces/" id))))
    (is (< (str/index-of body "丙") (str/index-of body "甲")) "newest first")))

(deftest test-admin-ingest
  (let [c (admin)
        started (:body (htmx c :post "/admin/ingest"))]
    (is (str/includes? started "執行中"))
    (is (str/includes? started "hx-trigger=\"every 2s\""))
    (let [done (poll-done c)]
      (is (str/includes? done "完成"))
      (is (str/includes? done "docs:"))
      (is (not (str/includes? done "every 2s")) "polling stops"))
    (testing "the admin page shows the latest report and index lag"
      (let [body (:body (wc/request! c :get "/admin"))]
        (is (str/includes? body "index lag"))
        (is (not (str/includes? body "尚無 ingest 紀錄")))))))

(deftest test-admin-ingest-conflict
  (wf/with-blocking-runner
    (fn [r gate]
      (let [c (wf/logged-in "admin" :context {:ingest r})]
        (is (str/includes? (:body (htmx c :post "/admin/ingest")) "執行中"))
        (is (str/includes? (:body (htmx c :post "/admin/ingest")) "已有 ingest 在執行"))
        (deliver gate true)
        (is (str/includes? (poll-done c) "完成"))))))

(deftest test-trace-detail
  (let [id (trace/write! wf/*app* {:username "alice"
                                   :kind :ask
                                   :query "特休怎麼算"
                                   :stages {:lexical {:ms 12
                                                      :top [["hr/leave.md::1" 3.5]]}}
                                   :answer "依年資[1]"})
        body (:body (wc/request! (admin) :get (str "/admin/traces/" id)))]
    (is (str/includes? body "特休怎麼算"))
    (is (str/includes? body "hr/leave.md::1"))
    (is (str/includes? body "依年資[1]")))
  (is (= 404 (:status (wc/request! (admin) :get "/admin/traces/not-a-uuid"))))
  (is (= 404 (:status (wc/request! (admin) :get (str "/admin/traces/" (random-uuid)))))))

(deftest test-non-admin-404
  (let [c (wf/logged-in "alice" :context {:ingest *runner*})]
    (doseq [[m u] [[:get "/admin"] [:post "/admin/ingest"] [:get "/admin/ingest/status"]
                   [:get (str "/admin/traces/" (random-uuid))]]]
      (is (= 404 (:status (if (= m :post) (wc/post! c u {}) (wc/request! c m u)))) u))))
