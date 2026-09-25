(ns replware.levinrag.retrieval.pipeline-test
  "Search pipeline wiring (SPEC.md §9.1) and eval variants."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [replware.levinrag.fixtures :as fx]
            [replware.levinrag.retrieval.datalevin :as rd]
            [replware.levinrag.retrieval.pipeline :as pipeline]))

(use-fixtures :once fx/with-sample-index)

(defn- deps [rerank-fn] {:retriever (rd/retriever fx/*index* fx/hash-embed)
                         :rerank-fn rerank-fn})

(defn- reverse-rerank
  "Scores candidates in reverse input order — visibly different from RRF."
  [_ docs _]
  (vec (map-indexed (fn [i _] {:index i
                               :relevance-score (double i)}) docs)))

(deftest test-full-pipeline
  (let [res (pipeline/search (deps reverse-rerank) (fx/principals "alice") "特休天數怎麼計算" {})]
    (is (seq (:passages res)))
    (is (= #{} (:degraded res)))
    (is (<= (count (filter :selected? (:candidates res))) 8))
    (is (= (sort-by (comp - :rerank) (:candidates res)) (:candidates res)) "final order = rerank order")
    (is (= (range 1 (inc (count (:passages res)))) (map :n (:passages res))))
    (testing "stages hold ids and scores, not text"
      (is (every? (fn [[id s]] (and (string? id) (number? s))) (get-in res [:stages :lexical :top])))
      (is (not-any? #(re-find #"特休天數依年資" (pr-str %)) (vals (:stages res)))))))

(deftest test-variants
  (let [alice (fx/principals "alice")
        never (fn [& _] (throw (ex-info "rerank must not run" {})))]
    (testing "lexical only, no rerank, no graph"
      (let [res (pipeline/search (deps never) alice "年終獎金" {:channels #{:lexical}
                                                            :rerank? false
                                                            :graph? false})]
        (is (every? #(= #{:lexical} (set (keys (:channels %)))) (:candidates res)))
        (is (nil? (get-in res [:stages :semantic])))
        (is (nil? (get-in res [:stages :rerank])))))
    (testing "semantic only"
      (let [res (pipeline/search (deps never) alice "年終獎金" {:channels #{:semantic}
                                                            :rerank? false
                                                            :graph? false})]
        (is (every? #(= #{:semantic} (set (keys (:channels %)))) (:candidates res)))))
    (testing "without rerank, graph candidates come after fused ones"
      (let [res (pipeline/search (deps never) alice "新人報到 帳號申請" {:rerank? false
                                                                 :rerank-input 3})
            kinds (map #(if (:graph (:channels %)) :graph :fused) (:candidates res))]
        (is (some #{:graph} kinds))
        (is (= kinds (sort-by {:fused 0
                               :graph 1} kinds)))))))

(deftest test-degradation-and-selection
  (let [alice (fx/principals "alice")]
    (testing "rerank failure degrades, keeps fused order, still returns passages"
      (let [res (pipeline/search (deps (fn [& _] (throw (ex-info "down" {})))) alice "特休" {})]
        (is (= #{:rerank-failed} (:degraded res)))
        (is (contains? (:flags res) :rerank-failed))
        (is (= "down" (get-in res [:stages :rerank :error])))
        (is (seq (:passages res)))))
    (testing "final-k and rerank-min-score"
      (let [res (pipeline/search (deps reverse-rerank) alice "特休" {:final-k 3})]
        (is (= 3 (count (filter :selected? (:candidates res))))))
      (let [res (pipeline/search (deps reverse-rerank) alice "特休" {:rerank-min-score 1e9})]
        (is (empty? (filter :selected? (:candidates res))))
        (is (empty? (:passages res)))))
    (testing "no groups → nothing, without calling rerank"
      (let [res (pipeline/search (deps (fn [& _] (throw (ex-info "called" {})))) fx/nobody "特休" {})]
        (is (= [] (:candidates res) (:passages res)))
        (is (= #{} (:degraded res)))))))
