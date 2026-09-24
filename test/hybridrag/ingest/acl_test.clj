(ns hybridrag.ingest.acl-test
  "ACL rules unit tests — SPEC.md §7.2 four rules.

   Tests:
   1. _collection.edn :read-groups declared-groups
   2. Child inherits parent groups
   3. Frontmatter :read_groups overrides
   4. Override semantics: deny takes precedence"
  (:require [clojure.test :refer :all]
            [hybridrag.ingest.acl :as acl]))

(def ^:const sample-edns
  "Sample _collection.edn maps for testing."
  {"" {:name "全公司"
       :read-groups ["all"]}
   "hr" {:name "人資"
         :read-groups ["hr"]}
   "hr/payroll" {:name "薪資"
                 :read-groups ["hr" "finance"]}
   "eng" {:name "工程"
          :read-groups ["eng"]}
   "finance" {:name "財務"
              :read-groups ["finance"]}})

(def ^:const root-groups ["all" "internal"])

;; --- Rule 1: _collection.edn :read-groups ---

(deftest test-declared-groups-from-nearest-edn
  ;; Rule 1: nearest _collection.edn :read-groups wins.
  (is (= ["hr"]
         (acl/resolve-collection-effective-groups "hr" sample-edns)))
  (is (= ["hr" "finance"]
         (acl/resolve-collection-effective-groups "hr/payroll" sample-edns)))
  (is (= ["all"]
         (acl/resolve-collection-effective-groups "" sample-edns)))
  (is (= ["eng"]
         (acl/resolve-collection-effective-groups "eng" sample-edns))))

(deftest test-declared-groups-no-edn-inherits-from-parent
  ;; Rule 1: if no _collection.edn, inherit from nearest ancestor.
  (is (= ["hr"]
         (acl/resolve-collection-effective-groups "hr/leave.md" sample-edns)))
  (is (= ["hr" "finance"]
         (acl/resolve-collection-effective-groups "hr/payroll/bonus.md" sample-edns)))
  (is (= ["all"]
         (acl/resolve-collection-effective-groups "public/handbook.md" sample-edns))))

(deftest test-root-read-groups-fallback
  ;; Rule 1: if no _collection.edn anywhere, use root-read-groups.
  (let [no-edns {}]
    (is (= root-groups
           (acl/resolve-collection-effective-groups "" no-edns root-groups)))
    (is (= root-groups
           (acl/resolve-collection-effective-groups "some/where" no-edns root-groups)))))

(deftest test-empty-groups-mean-admin-only
  ;; Rule 4: empty [] means admin only.
  (let [admin-only {"hr" {:name "人資"
                          :read-groups []}}]
    (is (= []
           (acl/resolve-collection-effective-groups "hr" admin-only root-groups)))))

;; --- Rule 2: frontmatter override ---

(deftest test-frontmatter-full-override
  ;; Rule 2: frontmatter :read_groups replaces collection groups entirely.
  (let [coll-groups ["hr" "finance"]]
    (is (= ["finance-lead"]
           (acl/resolve-file-groups coll-groups {:read_groups ["finance-lead"]})))))

(deftest test-no-frontmatter-uses-collection-groups
  ;; Rule 2: no frontmatter → equals collection effective-groups.
  (let [coll-groups ["hr" "finance"]]
    (is (= coll-groups
           (acl/resolve-file-groups coll-groups nil)))
    (is (= coll-groups
           (acl/resolve-file-groups coll-groups {})))))

;; --- Full pipeline ---

(deftest test-resolve-effective-groups-hr-file
  ;; Full pipeline: hr/leave.md with no frontmatter.
  (let [result (acl/resolve-effective-groups "hr/leave.md" sample-edns root-groups {})]
    (is (= ["hr"] (:declared-groups result)))
    (is (= ["hr"] (:effective-groups result)))
    (is (= "hr" (:collection result)))))

(deftest test-resolve-effective-groups-with-frontmatter-override
  ;; Full pipeline: budget.md with frontmatter :read_groups override.
  (let [result (acl/resolve-effective-groups "finance/budget.md"
                                             sample-edns root-groups
                                             {:read_groups ["finance-lead"]})]
    (is (= ["finance"] (:declared-groups result)))
    (is (= ["finance-lead"] (:effective-groups result)))
    (is (= "finance" (:collection result)))))

(deftest test-resolve-effective-groups-child-inherits-parent
  ;; Full pipeline: hr/payroll/bonus.md inherits from parent.
  (let [result (acl/resolve-effective-groups "hr/payroll/bonus.md"
                                             sample-edns root-groups {})]
    (is (= ["hr" "finance"] (:declared-groups result)))
    (is (= ["hr" "finance"] (:effective-groups result)))
    (is (= "hr/payroll" (:collection result)))))

(deftest test-resolve-effective-groups-root-file
  ;; Full pipeline: root-level file with no _collection.edn.
  (let [result (acl/resolve-effective-groups "README.md" sample-edns root-groups {})]
    (is (= ["all"] (:declared-groups result)))
    (is (= ["all"] (:effective-groups result)))
    (is (= "" (:collection result)))))

;; --- ACL overrides (deny/allow) ---

(deftest test-apply-acl-overrides-deny
  ;; Apply deny overrides: remove specified groups.
  (let [groups #{"hr" "intern" "contractor"}]
    (is (= #{"hr" "contractor"}
           (acl/apply-acl-overrides groups [{:type :deny,
                                             :groups ["intern"]}])))))

(deftest test-apply-acl-overrides-allow
  ;; Apply allow overrides: add specified groups.
  (let [groups #{"hr" "finance"}]
    (is (= #{"hr" "finance" "eng"}
           (acl/apply-acl-overrides groups [{:type :allow,
                                             :groups ["eng"]}])))))

(deftest test-apply-acl-overrides-deny-takes-precedence
  ;; Override semantics: if same group in deny and allow, deny wins.
  (let [groups #{"hr" "finance"}]
    (is (= #{"finance"}
           (acl/apply-acl-overrides groups
                                    [{:type :deny,
                                      :groups ["hr"]}
                                     {:type :allow,
                                      :groups ["hr"]}])))))

(deftest test-apply-acl-overrides-no-overrides
  ;; No overrides → return original set unchanged.
  (let [groups #{"hr" "finance" "eng"}]
    (is (= groups
           (acl/apply-acl-overrides groups))))
  (is (= #{"hr" "finance"}
         (acl/apply-acl-overrides #{"hr" "finance"} nil))))

;; --- Nearest collection edn ---

(deftest test-nearest-collection-edn
  ;; Find nearest _collection.edn for a file.
  (is (= {:name "人資"
          :read-groups ["hr"]}
         (acl/nearest-collection-edn "hr/leave.md" sample-edns)))
  (is (= {:name "薪資"
          :read-groups ["hr" "finance"]}
         (acl/nearest-collection-edn "hr/payroll/bonus.md" sample-edns)))
  (is (= {:name "全公司"
          :read-groups ["all"]}
         (acl/nearest-collection-edn "README.md" sample-edns)))
  ;; no _collection.edn in missing/ → falls back to the root one
  (is (= {:name "全公司"
          :read-groups ["all"]}
         (acl/nearest-collection-edn "missing/file.md" sample-edns)))
  (is (nil? (acl/nearest-collection-edn "missing/file.md" (dissoc sample-edns "")))))

(deftest test-parent-dir
  ;; parent-dir helper: compute parent directory of a path.
  (is (= "hr" (acl/parent-dir "hr/leave.md")))
  (is (= "hr/payroll" (acl/parent-dir "hr/payroll/bonus.md")))
  (is (= "hr" (acl/parent-dir "hr/payroll")))
  (is (= "" (acl/parent-dir "README.md")))
  (is (= "" (acl/parent-dir "")))
  (is (= "" (acl/parent-dir "/"))))