(ns replware.levinrag.retrieval.datalevin-test
  "Datalevin Retriever: channels, neighbors, links, chunk fetch, and the
   SPEC.md §18.3 ACL security tests at the channel/Retriever level."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [replware.levinrag.fixtures :as fx]
            [replware.levinrag.retrieval.datalevin :as rd]
            [replware.levinrag.retrieval.protocol :as p]))

(use-fixtures :once fx/with-sample-index)

(defn- retriever [] (rd/retriever fx/*index* fx/hash-embed))

(defn- doc-groups []
  (into {} (map (fn [m] [(:doc/path m) (set (:doc/effective-groups m))]))
        (d/q '[:find [(pull ?d [:doc/path :doc/effective-groups]) ...] :where [?d :doc/path]]
             (d/db fx/*index*))))

(defn- readable? [groups-by-doc principal path]
  (or (:admin? principal) (boolean (some (:groups principal) (groups-by-doc path)))))

(defn- doc-chunks [path]
  (sort (d/q '[:find [?id ...] :in $ ?p :where [?d :doc/path ?p] [?c :chunk/doc ?d] [?c :chunk/id ?id]]
             (d/db fx/*index*) path)))

(defn- probe-queries
  "Queries aimed straight at `path`: its title and the start of each chunk."
  [path]
  (let [db (d/db fx/*index*)
        title (d/q '[:find ?t . :in $ ?p :where [?d :doc/path ?p] [?d :doc/title ?t]] db path)]
    (cons title
          (for [id (doc-chunks path)]
            (let [t (d/q '[:find ?t . :in $ ?id :where [?c :chunk/id ?id] [?c :chunk/text ?t]] db id)]
              (subs t 0 (min 60 (count t))))))))

;; --- §18.3 security ---

(deftest test-channels-never-return-unreadable-docs
  (let [r (retriever)
        groups (doc-groups)]
    (doseq [[user principal] fx/principals
            path (keys groups)
            :when (not (readable? groups principal path))
            q (probe-queries path)
            ch [:lexical :semantic]
            :let [res (p/channel r principal ch q {:channel-k 10})]]
      (is (not-any? #(= path (:doc/path %)) (concat (:candidates res) (:extended res)))
          (str user " saw " path " via " ch " for " (pr-str q))))))

(deftest test-restricted-docs-are-actually-findable
  ;; guards against the test above passing vacuously
  (let [r (retriever)
        admin (fx/principals "admin")]
    (doseq [path ["hr/payroll/bonus.md" "finance/budget-2025.md" "hr/investigations/case-handling.md"
                  "engineering/specs/sku-catalog.md"]]
      (is (some #(= path (:doc/path %))
                (:candidates (p/channel r admin :lexical (first (probe-queries path)) {})))
          path))))

(deftest test-neighbors-chunks-and-links-respect-acl
  (let [r (retriever)
        {:strs [alice bob carol admin]} fx/principals]
    (testing "neighbors: same section only, and none for an unreadable chunk"
      (is (= ["engineering/release-notes.md::4" "engineering/release-notes.md::6"]
             (mapv :chunk/id (p/neighbors r bob "engineering/release-notes.md::5" {}))))
      (is (= [] (p/neighbors r alice "engineering/release-notes.md::5" {})))
      (is (= [] (p/neighbors r alice "hr/payroll/bonus.md::1" {})) "one chunk per section")
      (is (= [] (p/neighbors r bob "hr/payroll/bonus.md::1" {}))))
    (testing "chunk fetch drops unreadable ids, keeps order"
      (is (= ["hr/leave.md::1" "hr/leave.md::0"]
             (mapv :chunk/id (p/chunks r alice ["hr/leave.md::1" "finance/budget-2025.md::0"
                                                "hr/investigations/case-handling.md::0" "hr/leave.md::0"]))))
      (is (= [] (p/chunks r carol ["finance/budget-2025.md::0"])) "frontmatter override"))
    (testing "links never lead to unreadable docs, in either direction"
      (is (= ["finance/invoice-guide.md"] (p/linked-docs r carol ["finance/procurement.md"])))
      (is (some #{"engineering/specs/sku-catalog.md"} (p/linked-docs r admin ["finance/procurement.md"])))
      (is (= [] (p/linked-docs r bob ["hr/payroll/bonus.md"])) "unreadable source doc")
      (is (not-any? #{"finance/procurement.md"} (p/linked-docs r bob ["engineering/specs/sku-catalog.md"]))
          "reverse link from an unreadable doc"))))

(deftest test-no-groups-means-empty-without-db-access
  (let [r (rd/retriever :not-a-conn (fn [_] (throw (ex-info "must not embed" {}))))]
    (doseq [ch [:lexical :semantic]]
      (is (= [] (:candidates (p/channel r fx/nobody ch "年終獎金" {})))))
    (is (= [] (p/neighbors r fx/nobody "hr/leave.md::0" {})))
    (is (= [] (p/linked-docs r fx/nobody ["hr/leave.md"])))
    (is (= [] (p/chunks r fx/nobody ["public/handbook.md::0"])))))

;; --- behavior ---

(deftest test-lexical-channel
  (let [res (p/channel (retriever) (fx/principals "alice") :lexical "特休天數怎麼計算" {:channel-k 5})]
    (is (= "hr/leave.md" (:doc/path (first (:candidates res)))))
    (is (= [1 2 3 4 5] (map :rank (:candidates res))))
    (is (apply >= (map :score (:extended res))) "best-first")
    (is (<= (count (:candidates res)) 5))
    (is (= (take 5 (:extended res)) (:candidates res))))
  (testing "exact identifiers via the CJK analyzer"
    (let [top (fn [u q] (:doc/path (first (:candidates (p/channel (retriever) (fx/principals u) :lexical q {})))))]
      (is (= "public/office-guide.md" (top "carol" "GA-03 補發工本費")))
      ;; exact version strings only reach the top 10 here: the "v2" fragment
      ;; (§8.1) is common and BM25 length-normalizes the long release-notes
      ;; chunk down (see docs/decisions.md)
      (is (some #(= "engineering/release-notes.md" (:doc/path %))
                (take 10 (:candidates (p/channel (retriever) (fx/principals "bob") :lexical "v2.7.3" {})))))
      (is (str/starts-with? (top "bob" "E2001") "engineering/specs/api-v2.md")))))

(deftest test-semantic-channel-and-starvation
  (let [r (retriever)
        res (p/channel r (fx/principals "carol") :semantic "anything" {:channel-k 30
                                                                       :overfetch 2})]
    (is (= 60 (:raw-hits res)))
    (is (every? #(<= -1.0 (:score %) 1.0) (:candidates res)))
    (testing "over-fetch exhausted and fewer than channel-k survive ACL → starved"
      (is (< (:after-acl res) 30))
      (is (:starved? res))))
  (testing "admin is never starved by ACL"
    (let [res (p/channel (retriever) (fx/principals "admin") :semantic "anything" {:channel-k 30
                                                                                   :overfetch 2})]
      (is (= 30 (count (:candidates res))))
      (is (not (:starved? res))))))
