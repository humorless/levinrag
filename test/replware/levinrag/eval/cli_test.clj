(ns replware.levinrag.eval.cli-test
  "`bb eval` arguments (SPEC.md §15.2): variants and the question / user
   files, so an evaluator can point it at their own corpus's files."
  (:require [clojure.test :refer [deftest is testing]]
            [replware.levinrag.eval.cli :as cli]
            [replware.levinrag.tmp :as tmp]))

(deftest test-parse-args
  (testing "defaults"
    (let [o (cli/parse-args [])]
      (is (= "eval/questions.edn" (:questions o)))
      (is (= "eval/users.edn" (:users o)))
      (is (= 5 (count (:variant-names o))))))
  (testing "files and variants in any order"
    (let [o (cli/parse-args ["--users" "u.edn" "--variants" "lexical,hybrid" "--questions" "q.edn"])]
      (is (= "q.edn" (:questions o)))
      (is (= "u.edn" (:users o)))
      (is (= ["lexical" "hybrid"] (:variant-names o)))))
  (testing "mistakes are errors, not silent defaults"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"未知的變體" (cli/parse-args ["--variants" "bm25"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"未知的參數" (cli/parse-args ["--question" "q.edn"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"缺少值" (cli/parse-args ["--questions"])))))

(deftest test-read-inputs
  (let [dir (tmp/dir "eval-cli")
        q (str dir "/q.edn")
        u (str dir "/u.edn")]
    (try
      (spit q "[{:id \"x\" :user \"alice\" :query \"特休\" :expected-docs [\"hr/leave.md\"]}]")
      (spit u "{\"alice\" {:groups #{\"all\"} :admin? false}}")
      (let [{:keys [questions principals]} (cli/read-inputs {:questions q
                                                             :users u})]
        (is (= ["x"] (map :id questions)))
        (is (= {:username "alice"
                :groups #{"all"}
                :admin? false} (get principals "alice"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"找不到題目檔：.*missing.edn"
                            (cli/read-inputs {:questions (str dir "/missing.edn")
                                              :users u})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"找不到使用者檔：.*missing.edn"
                            (cli/read-inputs {:questions q
                                              :users (str dir "/missing.edn")})))
      (finally (tmp/delete-tree! dir)))))

(deftest test-users-file-is-validated
  ;; review 2026-09-25 M3: `{:group ...}` loaded as a user with no groups,
  ;; who reads nothing, so the leak check passed vacuously
  (let [dir (tmp/dir "eval-cli-users")
        q (str dir "/q.edn")
        u (str dir "/u.edn")]
    (try
      (spit q "[]")
      (doseq [bad ["{\"bob\" {:group #{\"all\"}}}" "{\"bob\" {:groups \"hr\"}}"]]
        (spit u bad)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"使用者檔有錯誤"
                              (cli/read-inputs {:questions q
                                                :users u}))
            bad))
      (finally (tmp/delete-tree! dir)))))

(deftest test-missing-docs
  ;; a question whose documents are not in the index measures nothing: a
  ;; :must-not-docs path with a typo, or a doc kept out by §7.2 rule 5,
  ;; can never "leak"
  (is (= [{:id "q1"
           :key :expected-docs
           :path "hr/leav.md"}
          {:id "q2"
           :key :must-not-docs
           :path "hr/gone.md"}]
         (cli/missing-docs [{:id "q1"
                             :expected-docs ["hr/leave.md" "hr/leav.md"]}
                            {:id "q2"
                             :must-not-docs ["hr/gone.md"]
                             :expected-docs ["hr/leave.md"]}]
                           #{"hr/leave.md"}))))
