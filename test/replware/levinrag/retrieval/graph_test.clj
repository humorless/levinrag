(ns replware.levinrag.retrieval.graph-test
  "Graph channel (SPEC.md §9.5, T2.2 AC: no duplicate candidates,
   ACL-bound) over the sample corpus."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [replware.levinrag.fixtures :as fx]
            [replware.levinrag.retrieval.datalevin :as rd]
            [replware.levinrag.retrieval.graph :as graph]))

(use-fixtures :once fx/with-sample-index)

(defn- retriever [] (rd/retriever fx/*index* fx/hash-embed))

(defn- fused [& ids]
  (mapv (fn [id] {:chunk/id id
                  :doc/path (subs id 0 (.lastIndexOf ^String id "::"))}) ids))

(deftest test-graph-follows-links-within-acl
  (testing "carol: procurement links to invoice-guide (readable) and sku-catalog (not)"
    (let [gs (graph/candidates (retriever) (fx/principals "carol") (fused "finance/procurement.md::0") {} {})]
      (is (seq gs))
      (is (every? #(= "finance/invoice-guide.md" (:doc/path %)) gs))
      (is (= ["finance/procurement.md"] (get-in (first gs) [:channels :graph :via])))))
  (testing "admin gets the engineering doc through the same link"
    (let [gs (graph/candidates (retriever) (fx/principals "admin") (fused "finance/procurement.md::0") {} {})]
      (is (some #(= "engineering/specs/sku-catalog.md" (:doc/path %)) gs)))))

(defn- doc-groups []
  (into {} (map (fn [m] [(:doc/path m) (set (:doc/effective-groups m))]))
        (d/q '[:find [(pull ?d [:doc/path :doc/effective-groups]) ...] :where [?d :doc/path]]
             (d/db fx/*index*))))

(deftest test-graph-never-leaks-for-any-user
  ;; oracle independent of the Retriever: the doc's stored groups
  (let [groups (doc-groups)
        sources (for [path (keys groups)] (str path "::0"))]
    (doseq [[user principal] fx/principals
            :when (not (:admin? principal))
            src sources
            c (graph/candidates (retriever) principal (fused src) {} {})]
      (is (some (:groups principal) (groups (:doc/path c)))
          (str user " got unreadable " (:chunk/id c) " via " src)))))

(deftest test-graph-no-duplicates-and-limits
  (let [f (fused "public/handbook.md::0" "hr/leave.md::0")
        gs (graph/candidates (retriever) (fx/principals "alice") f {} {})]
    (testing "fused docs are never re-added; no chunk twice"
      (is (not-any? #{"public/handbook.md" "hr/leave.md"} (map :doc/path gs)))
      (is (apply distinct? (map :chunk/id gs))))
    (testing "≤ 2 chunks per linked doc"
      (is (every? #(<= % 2) (vals (frequencies (map :doc/path gs))))))
    (testing "graph-max caps the total"
      (is (= 1 (count (graph/candidates (retriever) (fx/principals "alice") f {} {:graph-max 1}))))))
  (testing "prefers chunks the channels ranked, else the first chunk"
    (let [chs {:lexical {:extended [{:chunk/id "finance/invoice-guide.md::2"
                                     :doc/path "finance/invoice-guide.md"
                                     :rank 3}
                                    {:chunk/id "finance/invoice-guide.md::1"
                                     :doc/path "finance/invoice-guide.md"
                                     :rank 9}
                                    {:chunk/id "finance/invoice-guide.md::0"
                                     :doc/path "finance/invoice-guide.md"
                                     :rank 20}]}}
          gs (graph/candidates (retriever) (fx/principals "carol") (fused "finance/procurement.md::0") chs {})]
      (is (= ["finance/invoice-guide.md::2" "finance/invoice-guide.md::1"] (map :chunk/id gs))))
    (let [gs (graph/candidates (retriever) (fx/principals "carol") (fused "finance/procurement.md::0") {} {})]
      (is (= ["finance/invoice-guide.md::0"] (map :chunk/id gs)))))
  (testing "no groups → nothing"
    (is (= [] (graph/candidates (retriever) fx/nobody (fused "public/handbook.md::0") {} {})))))
