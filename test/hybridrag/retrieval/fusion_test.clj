(ns hybridrag.retrieval.fusion-test
  "RRF (SPEC.md §9.4, T2.2 AC: unit tests including ties)."
  (:require [clojure.test :refer [deftest is testing]]
            [hybridrag.retrieval.fusion :as fusion]))

(defn- close? [a b] (< (Math/abs (- a b)) 1e-12))

(deftest test-rrf-scores
  (let [res (fusion/rrf 60 ["a" "b" "c"] ["c" "a"])
        m (into {} res)]
    (is (close? (+ (/ 1.0 61) (/ 1.0 62)) (m "a")))
    (is (close? (/ 1.0 62) (m "b")))
    (is (close? (+ (/ 1.0 63) (/ 1.0 61)) (m "c")))
    (is (= ["a" "c" "b"] (map first res)))))

(deftest test-rrf-missing-and-empty
  (is (= [] (fusion/rrf 60)))
  (is (= [] (fusion/rrf 60 [] [])))
  (is (= ["x" "y"] (map first (fusion/rrf 60 ["x" "y"]))))
  (is (= ["x" "y"] (map first (fusion/rrf 60 [] ["x" "y"])))))

(deftest test-rrf-ties
  (testing "equal scores: the better lexical (first-list) rank wins"
    ;; a: lex 1 + sem 2, b: lex 2 + sem 1 → same score
    (is (= ["a" "b"] (map first (fusion/rrf 60 ["a" "b"] ["b" "a"]))))
    (is (= ["b" "a"] (map first (fusion/rrf 60 ["b" "a"] ["a" "b"])))))
  (testing "an id present only in the second list loses a tie to one in the first"
    (is (= ["lex" "sem"] (map first (fusion/rrf 60 ["lex"] ["sem"])))))
  (testing "ties within the same list position fall back to the second list, then id"
    (is (= ["a" "b"] (map first (fusion/rrf 60 [] ["a"] ["b"]))) "second list decides")
    (is (= ["a" "b"] (map first (fusion/rrf 60 ["a"] ["b"] []))))
    (is (= ["a" "b"] (map first (fusion/rrf 60 [] [] ["a"] ["b"])))))
  (testing "deterministic regardless of map ordering"
    (let [ids (map str (range 200))]
      (is (= (fusion/rrf 60 ids (reverse ids)) (fusion/rrf 60 ids (reverse ids)))))))

(deftest test-fuse
  (let [lex {:candidates [{:chunk/id "a::0"
                           :doc/path "a"
                           :rank 1
                           :score 7.0}
                          {:chunk/id "b::0"
                           :doc/path "b"
                           :rank 2
                           :score 5.0}]}
        sem {:candidates [{:chunk/id "b::0"
                           :doc/path "b"
                           :rank 1
                           :score 0.9}
                          {:chunk/id "c::0"
                           :doc/path "c"
                           :rank 2
                           :score 0.8}]}
        fused (fusion/fuse 60 {:lexical lex
                               :semantic sem})]
    (is (= ["b::0" "a::0" "c::0"] (map :chunk/id fused)))
    (is (= {:lexical {:rank 2
                      :score 5.0}
            :semantic {:rank 1
                       :score 0.9}} (:channels (first fused))))
    (is (= {:lexical {:rank 1
                      :score 7.0}} (:channels (second fused))))
    (is (= "c" (:doc/path (last fused)))))
  (testing "a single channel just keeps its order"
    (is (= ["x" "y"] (map :chunk/id (fusion/fuse 60 {:semantic {:candidates [{:chunk/id "x"
                                                                              :rank 1}
                                                                             {:chunk/id "y"
                                                                              :rank 2}]}}))))))
