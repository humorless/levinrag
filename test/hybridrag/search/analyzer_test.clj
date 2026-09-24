(ns hybridrag.search.analyzer-test
  "SPEC.md §8.3 test vectors (all must pass) and offset behavior."
  (:require [clojure.test :refer [deftest is testing]]
            [hybridrag.search.analyzer :as analyzer]))

(defn- terms [s] (mapv first (analyzer/analyze s)))

(deftest test-spec-vectors
  (is (= ["員工" "工請" "請假" "假規" "規定"] (terms "員工請假規定")))
  (is (= ["sku-a123" "sku" "a123" "的庫" "庫存"] (terms "SKU-A123 的庫存")))
  (is (= ["iphone15" "手機"] (terms "iPhone15手機")))
  (is (= ["hr-07" "hr" "07" "表單"] (terms "ＨＲ－０７表單")))
  (is (= ["v2.5" "v2" "版本"] (terms "v2.5 版本")))
  (is (= ["請"] (terms "請"))))

(deftest test-positions-and-offsets
  (testing "positions follow output order; offsets point into the original"
    (is (= [["sku-a123" 0 0] ["sku" 1 0] ["a123" 2 4] ["的庫" 3 9] ["庫存" 4 10]]
           (analyzer/analyze "SKU-A123 的庫存"))))
  (testing "full-width input keeps original offsets"
    (is (= [["hr-07" 0 0] ["hr" 1 0] ["07" 2 3] ["表單" 3 5]]
           (analyzer/analyze "ＨＲ－０７表單")))))

(deftest test-edges
  (is (= [] (analyzer/analyze "")))
  (is (= [] (analyzer/analyze nil)))
  (is (= [] (analyzer/analyze "。，！ -- ..")))
  (testing "trailing / leading connectors are not part of a run"
    (is (= ["v2"] (terms "v2.")))
    (is (= ["hr"] (terms "-hr-"))))
  (testing "kana and hangul are CJK runs"
    (is (= ["ひら" "らが" "がな"] (terms "ひらがな")))
    (is (= ["한국"] (terms "한국"))))
  (testing "paths and underscores"
    (is (= ["api/v2/items" "api" "v2" "items"] (terms "/api/v2/items")))
    (is (= ["on_hand" "on" "hand"] (terms "on_hand")))))
