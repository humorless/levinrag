(ns replware.levinrag.ingest.tokens-test
  "Token estimation tests (SPEC.md §7.7)."
  (:require [clojure.test :refer [deftest is testing]]
            [replware.levinrag.ingest.tokens :as tokens]))

(deftest test-estimate-tokens
  (testing "each CJK code point counts 1, punctuation and whitespace 0"
    (is (= 6 (tokens/estimate-tokens "員工請假規定")))
    (is (= 4 (tokens/estimate-tokens "請假。 規定！")))
    (is (= 5 (tokens/estimate-tokens "ひらがなカ")))
    (is (= 2 (tokens/estimate-tokens "한국"))))
  (testing "a letter/digit run counts ceil(len/4) + 1"
    (is (= 2 (tokens/estimate-tokens "api")))
    (is (= 2 (tokens/estimate-tokens "abcd")))
    (is (= 3 (tokens/estimate-tokens "abcde")))
    (is (= 6 (tokens/estimate-tokens "hello world"))))
  (testing "separators split runs; CJK ends a run"
    (is (= (+ 2 2) (tokens/estimate-tokens "SKU-A123")))
    (is (= (+ 3 2) (tokens/estimate-tokens "iPhone15手機"))))
  (testing "full-width letters/digits are counted, not free"
    (is (= (+ 2 2 2) (tokens/estimate-tokens "ＨＲ－０７表單"))))
  (testing "empty and blank"
    (is (= 0 (tokens/estimate-tokens "")))
    (is (= 0 (tokens/estimate-tokens " \n\t。，")))))

(deftest test-fit-end
  (let [s "一二三四五"]
    (is (= 3 (tokens/fit-end s 0 5 3)))
    (is (= 5 (tokens/fit-end s 0 5 99)))
    (is (= 1 (tokens/fit-end s 1 5 0)) "returns from when nothing fits"))
  (testing "result is the largest prefix within budget"
    (let [s "abcd efgh 中文"]
      (doseq [budget (range 0 8)]
        (let [e (tokens/fit-end s 0 (count s) budget)]
          (is (<= (tokens/estimate-tokens (subs s 0 e)) budget))
          (when (< e (count s))
            (is (> (tokens/estimate-tokens (subs s 0 (inc e))) budget)))))))
  (testing "never splits a surrogate pair"
    (let [s "𠀀𠀀𠀀"]
      (is (= 4 (tokens/fit-end s 0 (count s) 2))))))
