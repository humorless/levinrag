(ns hybridrag.llm.check-test
  (:require [clojure.test :refer [deftest is]]
            [hybridrag.llm.check :as check]))

(deftest test-long-probe-ok
  (is (check/long-probe-ok? [{:index 0
                              :relevance-score 3.2} {:index 1
                                                     :relevance-score -9.0}]))
  (is (not (check/long-probe-ok? [{:index 1
                                   :relevance-score 1.0} {:index 0
                                                          :relevance-score -2.0}])))
  (is (not (check/long-probe-ok? [{:index 1
                                   :relevance-score -9.0}])))
  (is (not (check/long-probe-ok? [{:index 0
                                   :relevance-score ##NaN} {:index 1
                                                            :relevance-score -9.0}]))))

(deftest test-long-document-fills-the-rerank-cut
  ;; :rerank/max-chars is 1500 (SPEC.md §9.6)
  (is (= 1500 (count check/long-document))))
