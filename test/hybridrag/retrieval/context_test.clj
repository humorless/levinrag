(ns hybridrag.retrieval.context-test
  "Context expansion + packing (SPEC.md §9.7, T2.4 AC: budget never
   exceeded; no duplicate sentences after merging; contiguous numbering),
   plus the §18.3 check that neighbor expansion cannot escalate access."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hybridrag.fixtures :as fx]
            [hybridrag.retrieval.context :as context]
            [hybridrag.retrieval.datalevin :as rd]
            [hybridrag.retrieval.protocol :as p]))

(use-fixtures :once fx/with-sample-index)

(defn- retriever [] (rd/retriever fx/*index* fx/hash-embed))

(defn- pack-for [user ids opts]
  (let [r (retriever)
        principal (fx/principals user)]
    (context/pack (p/chunks r principal ids) #(p/neighbors r principal % {}) opts)))

(defn- sentences [s] (remove str/blank? (map str/trim (str/split s #"(?<=[。！？])|\n"))))

(deftest test-merge-has-no-duplicate-sentences
  (let [{:keys [passages]} (pack-for "bob" ["engineering/release-notes.md::5"] {})
        p (first passages)
        original (slurp "corpus-sample/engineering/release-notes.md")]
    (is (= 1 (count passages)))
    (is (= ["engineering/release-notes.md::4" "engineering/release-notes.md::5" "engineering/release-notes.md::6"]
           (:chunk-ids p)) "±1 neighbors in the same section")
    (is (= ["engineering/release-notes.md::5"] (:selected-ids p)))
    (testing "overlap removed: the passage is exactly the original span"
      (is (= (apply subs original (:char-range p)) (:text p))))
    (let [ss (sentences (:text p))]
      (is (= (count ss) (count (distinct ss))) "no sentence twice"))))

(deftest test-numbering-and-order
  (let [{:keys [passages]} (pack-for "alice" ["hr/leave.md::1" "public/handbook.md::0" "hr/payroll/bonus.md::2"] {})]
    (is (= (range 1 (inc (count passages))) (map :n passages)))
    (is (= ["hr/leave.md" "public/handbook.md" "hr/payroll/bonus.md"]
           (distinct (map :doc/path passages))) "ordered by the selected chunk's rank")
    (testing "sections are never merged together"
      (is (every? #(= 1 (count (set (map (fn [id] (subs id 0 (.lastIndexOf ^String id "::"))) (:chunk-ids %)))))
                  passages)))
    (is (every? (comp seq :section/trail) (filter #(= "hr/leave.md" (:doc/path %)) passages)))))

(deftest test-budget
  (let [ids (for [i (range 0 30 3)] (str "engineering/release-notes.md::" i))]
    (doseq [budget [100 300 600 1200 6000]]
      (let [{:keys [passages tokens dropped]} (pack-for "bob" ids {:max-tokens budget})]
        (is (<= tokens budget) (str "budget " budget))
        (is (= tokens (reduce + (map :tokens passages))))
        (is (= (range 1 (inc (count passages))) (map :n passages)))
        (when (< budget 6000) (is (seq dropped)))))
    ;; ::0 is ~46 tokens, ::3 and ::6 ~360 each (all three ≈ 762)
    (testing "neighbors go before any selected chunk"
      (let [{:keys [passages dropped]} (pack-for "bob" (take 3 ids) {:max-tokens 800})
            kept-selected (set (mapcat :selected-ids passages))]
        (is (= (set (take 3 ids)) kept-selected) "all selected survive when dropping neighbors suffices")
        (is (seq dropped))
        (is (not-any? (set (take 3 ids)) dropped))))
    (testing "once neighbors are gone, the weakest selected chunk goes first"
      (let [{:keys [passages]} (pack-for "bob" (take 3 ids) {:max-tokens 450})
            kept (set (mapcat :selected-ids passages))]
        (is (= #{(first ids) (second ids)} kept))))))

(deftest test-neighbors-cannot-escalate
  (testing "a readable selected chunk only pulls readable neighbors"
    (let [groups (fx/doc-groups fx/*index*)
          ids ["public/handbook.md::0" "hr/leave.md::1" "engineering/release-notes.md::5"
               "finance/invoice-guide.md::0" "hr/payroll/bonus.md::2"]]
      (doseq [[user principal] fx/principals
              :let [r (retriever)
                    {:keys [passages]} (context/pack (p/chunks r principal ids)
                                                     #(p/neighbors r principal % {}) {})]
              path (mapcat (fn [ps] (map #(subs % 0 (.lastIndexOf ^String % "::")) (:chunk-ids ps))) [passages])]
        (is (fx/readable? groups principal path) (str user " got " path)))))
  (testing "no selected chunks → no passages"
    (is (= {:passages []
            :tokens 0
            :dropped []} (context/pack [] (constantly []) {})))))
