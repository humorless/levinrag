(ns replware.levinrag.eval.harness-test
  "Eval harness (SPEC.md §15.2, T2.6 AC: five-variant report, ACL leaks =
   0, results file written)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [replware.levinrag.eval.harness :as harness]
            [replware.levinrag.fixtures :as fx]
            [replware.levinrag.retrieval.datalevin :as rd]
            [replware.levinrag.tmp :as tmp]))

(use-fixtures :once fx/with-sample-index)

(deftest test-metrics
  (let [docs ["a" "b" "c" "d" "e" "f"]]
    (is (= 1.0 (harness/recall-at 5 ["a" "c"] docs)))
    (is (= 0.5 (harness/recall-at 5 ["a" "f"] docs)))
    (is (= 1.0 (harness/recall-at 10 ["a" "f"] docs)))
    (is (= 1.0 (harness/mrr-at 10 ["a"] docs)))
    (is (= 0.25 (harness/mrr-at 10 ["d" "e"] docs)) "first expected hit counts")
    (is (= 0.0 (harness/mrr-at 3 ["d"] docs)))
    (is (nil? (harness/recall-at 5 [] docs)) "negative-only questions are not scored")
    (is (nil? (harness/mrr-at 10 nil docs))))
  (is (= ["a" "b"] (harness/ranked-docs [{:doc/path "a"} {:doc/path "a"} {:doc/path "b"}]))))

(defn- rerank-stub [_ docs _]
  (vec (map-indexed (fn [i _] {:index i
                               :relevance-score (- (double i))}) docs)))

(deftest test-full-run
  (let [questions (edn/read-string (slurp "eval/questions.edn"))
        report (harness/run-eval {:retriever (rd/retriever fx/*index* fx/hash-embed)
                                  :rerank-fn rerank-stub}
                                 fx/*index*
                                 {:questions questions
                                  :principals (harness/load-principals "eval/users.edn")
                                  :variant-names (keys harness/variants)})]
    (is (= ["lexical" "semantic" "hybrid" "hybrid+rerank" "hybrid+rerank+graph"] (keys (:variants report))))
    (is (= 0 (:acl-leaks report)) "SPEC §1.2: zero ACL leaks on the sample corpus")
    (doseq [[v {:keys [summary rows]}] (:variants report)]
      (is (= (count questions) (:questions summary) (count rows)) v)
      (is (< (:scored summary) (:questions summary)) "ACL-only questions are not scored")
      (is (every? #(<= 0.0 % 1.0) (keep summary [:recall-5 :recall-10 :mrr-10])) v)
      (is (contains? (:stage-ms summary) :context) v))
    (testing "lexical recall is meaningful even with a noise embedder"
      (is (< 0.5 (get-in report [:variants "lexical" :summary :recall-10]))))
    (is (pos? (:max-chunk-tokens report)))
    (testing "table and results file"
      (let [t (harness/table report)]
        (is (str/includes? t "hybrid+rerank+graph"))
        (is (str/includes? t "ACL leaks: 0")))
      (let [dir (tmp/dir "eval-results")
            path (harness/write-results! dir report)]
        (is (= 0 (:acl-leaks (edn/read-string (slurp path)))))
        (tmp/delete-tree! dir)))))

(deftest test-leaks-are-counted
  ;; a retriever that ignores ACL must be caught by the harness
  (let [leaky (rd/retriever fx/*index* fx/hash-embed)
        admin (assoc (get (harness/load-principals "eval/users.edn") "admin") :username "bob")
        report (harness/run-eval {:retriever leaky
                                  :rerank-fn rerank-stub} fx/*index*
                                 {:questions [{:id "q"
                                               :user "bob"
                                               :query "年終獎金的發放標準"
                                               :must-not-docs ["hr/payroll/bonus.md"]}]
                                  :principals {"bob" admin}
                                  :variant-names ["lexical"]})]
    (is (pos? (:acl-leaks report)))))

(deftest test-section-level-scoring
  (let [report (harness/run-eval {:retriever (rd/retriever fx/*index* fx/hash-embed)
                                  :rerank-fn rerank-stub}
                                 fx/*index*
                                 {:questions [{:id "s1"
                                               :user "alice"
                                               :query "特休天數依年資計算"
                                               :expected-sections ["hr/leave.md#2"]}
                                              {:id "s2"
                                               :user "alice"
                                               :query "特休天數依年資計算"
                                               :expected-sections ["hr/leave.md#9"]}]
                                  :principals (harness/load-principals "eval/users.edn")
                                  :variant-names ["lexical"]})
        [r1 r2] (get-in report [:variants "lexical" :rows])]
    (is (every? #(re-find #"#\d+$" %) (:docs r1)) "ranking is over section ids")
    (is (= "hr/leave.md#2" (first (:docs r1))) "天數計算 is section 2 of leave.md")
    (is (= 1.0 (:mrr-10 r1)))
    (is (= 0.0 (:recall-10 r2)) "a section id that does not exist is never found")))

(deftest test-degraded-warning
  (is (nil? (harness/degraded-warning {:variants {"lexical" {:summary {:degraded 0}}}})))
  (is (re-find #"hybrid\+rerank 3"
               (harness/degraded-warning {:variants {"lexical" {:summary {:degraded 0}}
                                                     "hybrid+rerank" {:summary {:degraded 3}}}}))))
