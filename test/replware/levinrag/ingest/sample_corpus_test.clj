(ns replware.levinrag.ingest.sample-corpus-test
  "corpus-sample/ and eval/questions.edn stay consistent (SPEC.md §15.3):
   the corpus ingests cleanly with the required features, and every
   question's expected docs are readable by its user while every
   must-not doc is not."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [replware.levinrag.db.index-conn :as index-conn]
            [replware.levinrag.ingest.job :as job]))

(def ^:private seed-users
  "SPEC.md §15.3 seed users."
  {"alice" #{"all" "hr"}
   "bob" #{"all" "engineering"}
   "carol" #{"all" "finance"}
   "admin" :admin})

(defn- stub-embed [texts] (mapv (fn [t] [(double (count t)) 1.0 0.5]) texts))

(defn- with-ingested [f]
  (let [dir (str (java.nio.file.Files/createTempDirectory
                   "sample-index" (make-array java.nio.file.attribute.FileAttribute 0)))
        conn (index-conn/open dir 3)]
    (try
      (f conn (job/ingest! conn {:corpus-dir "corpus-sample"
                                 :embed-fn stub-embed}))
      (finally
        (d/close conn)
        (doseq [x (reverse (file-seq (io/file dir)))] (io/delete-file x true))))))

(defn- doc-groups [db]
  (into {}
        (map (fn [m] [(:doc/path m) (set (:doc/effective-groups m))]))
        (d/q '[:find [(pull ?d [:doc/path :doc/effective-groups]) ...] :where [?d :doc/path]] db)))

(defn- readable? [groups-by-doc user path]
  (let [ug (seed-users user)]
    (or (= :admin ug) (boolean (seq (set/intersection ug (groups-by-doc path)))))))

(deftest test-sample-corpus-ingests-cleanly
  (with-ingested
    (fn [conn rep]
      (let [db (d/db conn)
            docs (doc-groups db)]
        (is (= [] (:errors rep)))
        (is (= [] (:unresolved-links rep)))
        (is (= 22 (:added rep)))
        (is (<= (:max-chunk-tokens rep) 500))
        (testing "ignored paths are not indexed"
          (is (not-any? #(or (str/starts-with? % "_") (str/includes? % "/.") (str/starts-with? % ".")) (keys docs))))
        (testing "ACL structure (§7.1, §7.2)"
          (is (= #{"all"} (docs "public/handbook.md")))
          (is (= #{"all"} (docs "it/vpn.txt")) "no _collection.edn: inherits root")
          (is (= #{"hr"} (docs "hr/payroll/bonus.md")) "inherits hr/")
          (is (= #{"hr-lead"} (docs "hr/investigations/case-handling.md")) "nested collection")
          (is (= #{"finance-lead"} (docs "finance/budget-2025.md")) "frontmatter override")
          (is (= #{"all"} (docs "hr/announcements/year-end-party.md")) "frontmatter widens"))
        (testing "one section over 10k chars, split into many chunks"
          (let [s (slurp "corpus-sample/engineering/release-notes.md")]
            (is (< 10000 (- (count s) (str/index-of s "## 變更紀錄"))))
            (is (< 20 (count (d/q '[:find [?c ...] :where
                                    [?d :doc/path "engineering/release-notes.md"] [?c :chunk/doc ?d]]
                                  db))))))
        (testing "links resolve, including a wikilink and a cross-collection link"
          (let [links (fn [p] (set (map :doc/path (:doc/links-to (d/pull db [{:doc/links-to [:doc/path]}] [:doc/path p])))))]
            (is (contains? (links "public/handbook.md") "hr/onboarding.md"))
            (is (contains? (links "finance/procurement.md") "engineering/specs/sku-catalog.md"))))))))

(deftest test-questions-match-corpus
  (let [qs (edn/read-string (slurp "eval/questions.edn"))
        tagged (fn [t] (count (filter #(some #{t} (:tags %)) qs)))]
    (testing "§15.3 minimums"
      (is (<= 30 (count qs)))
      (is (<= 6 (count (filter :must-not-docs qs))))
      (is (<= 6 (tagged :acl)))
      (is (<= 5 (tagged :identifier)))
      (is (<= 5 (tagged :cross-doc))))
    (is (apply distinct? (map :id qs)))
    (with-ingested
      (fn [conn _]
        (let [docs (doc-groups (d/db conn))]
          (doseq [{:keys [id user expected-docs must-not-docs]} qs]
            (is (contains? seed-users user) id)
            (is (or (seq expected-docs) (seq must-not-docs)) id)
            (doseq [p (concat expected-docs must-not-docs)]
              (is (contains? docs p) (str id ": unknown doc " p)))
            (doseq [p expected-docs]
              (is (readable? docs user p) (str id ": " user " cannot read expected " p)))
            (doseq [p must-not-docs]
              (is (not (readable? docs user p)) (str id ": " user " CAN read must-not " p)))))))))
