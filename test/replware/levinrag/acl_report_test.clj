(ns replware.levinrag.acl-report-test
  "`bb acl:report` (SPEC.md §9.3, §18.3): who can read what, computed with
   the retrieval layer's own ACL function, plus warnings for likely
   permission mistakes."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [replware.levinrag.acl-report :as report]
            [replware.levinrag.auth.users :as users]
            [replware.levinrag.fixtures :as fx]
            [replware.levinrag.tmp :as tmp]))

(use-fixtures :once fx/with-sample-index)

(defn- build [principals] (report/build (d/db fx/*index*) principals))

(deftest test-readers-match-the-stored-groups
  (let [r (build fx/principals)
        groups (fx/doc-groups fx/*index*)]
    (is (= (set (keys groups)) (set (map :path (:docs r)))))
    (doseq [{:keys [path readers]} (:docs r)]
      (is (= (set (for [[u p] fx/principals
                        :when (and (not (:admin? p)) (fx/readable? groups p path))]
                    u))
             (set readers))
          path))
    (is (= ["alice"] (:readers (first (filter #(= "hr/leave.md" (:path %)) (:docs r))))))))

(deftest test-summaries
  (let [r (build fx/principals)
        by-group (into {} (map (juxt :group identity)) (:groups r))
        by-user (into {} (map (juxt :username identity)) (:users r))]
    (is (= 5 (:docs (by-group "hr"))))
    (is (= ["alice"] (:users (by-group "hr"))))
    (is (= ["alice" "bob" "carol"] (:users (by-group "all"))))
    (is (= 22 (:readable (by-user "admin"))) "admins read every doc")
    (is (= (+ 7 5) (:readable (by-user "alice"))) "7 `all` docs + 5 `hr` docs")))

(deftest test-warnings
  (testing "groups no user holds, and the docs only admins can therefore read"
    (let [w (:warnings (build fx/principals))]
      (is (= ["finance-lead" "hr-lead"] (:unheld-groups w)))
      (is (= ["finance/budget-2025.md" "hr/investigations/case-handling.md"]
             (map :path (:admin-only-docs w))))
      (is (empty? (:users-without-groups w)))
      (is (empty? (:unused-user-groups w)))))
  (testing "a user without groups; a user group no doc uses (a typo on the user side)"
    (let [w (:warnings (build (assoc fx/principals
                                     "dave" {:username "dave"
                                             :groups #{}
                                             :admin? false}
                                     "erin" {:username "erin"
                                             :groups #{"all" "finanace"}
                                             :admin? false})))]
      (is (= ["dave"] (:users-without-groups w)))
      (is (= ["finanace"] (:unused-user-groups w))))))

(deftest test-text
  (let [r (build fx/principals)
        t (report/text r {:docs? false
                          :ingest-errors 0})
        t-docs (report/text r {:docs? true
                               :ingest-errors 3})]
    (is (str/includes? t "hr-lead"))
    (is (str/includes? t "可能是打錯字"))
    (is (not (str/includes? t "hr/leave.md")) "per-doc lines only with --docs")
    (is (str/includes? t-docs "hr/leave.md"))
    (is (str/includes? t-docs "最近一次匯入有 3 個錯誤"))
    (is (not (str/includes? t "匯入有")) "no ingest line when the last ingest had no errors")))

(deftest test-principals-from-app-db
  (tmp/with-app-conn
    (fn [conn]
      (users/create-user! conn "alice" {:groups ["all" "hr"]})
      (users/create-user! conn "root" {:admin? true})
      (is (= {"alice" {:username "alice"
                       :groups #{"all" "hr"}
                       :admin? false}
              "root" {:username "root"
                      :groups #{}
                      :admin? true}}
             (report/app-principals (d/db conn)))))))
