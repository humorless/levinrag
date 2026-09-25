(ns hybridrag.ingest.writer-test
  "Index writer + ingestion job tests (SPEC.md §7.6, T1.4 AC): changing one
   doc rewrites only that doc; a deleted file leaves no chunks behind; an
   ACL-only change does not re-embed. Also: links second pass, collections,
   per-file error isolation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [hybridrag.db.index-conn :as index-conn]
            [hybridrag.ingest.job :as job]
            [hybridrag.ingest.writer :as writer]))

(def ^:dynamic *corpus* nil)
(def ^:dynamic *conn* nil)
(def ^:dynamic *embedded* nil)

(defn- delete-tree! [f]
  (doseq [x (reverse (file-seq (io/file f)))] (io/delete-file x true)))

(defn- tmp-dir [prefix]
  (str (java.nio.file.Files/createTempDirectory prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(use-fixtures :each
  (fn [t]
    (let [corpus (tmp-dir "corpus")
          db-dir (tmp-dir "index")
          conn (index-conn/open db-dir 3)]
      (try
        (binding [*corpus* corpus *conn* conn *embedded* (atom [])]
          (t))
        (finally
          (d/close conn)
          (delete-tree! corpus)
          (delete-tree! db-dir))))))

(defn- put! [rel content]
  (let [f (io/file *corpus* rel)]
    (io/make-parents f)
    (spit f content)))

(defn- rm! [rel] (io/delete-file (io/file *corpus* rel)))

(defn- stub-embed [texts]
  (swap! *embedded* into texts)
  (mapv (fn [t] [(double (count t)) 1.0 0.5]) texts))

(defn- ingest! []
  (job/ingest! *conn* {:corpus-dir *corpus*
                       :root-read-groups ["all"]
                       :embed-fn stub-embed}))

(defn- db [] (d/db *conn*))

(defn- chunk-ids [path]
  (set (d/q '[:find [?id ...] :in $ ?p
              :where [?d :doc/path ?p] [?c :chunk/doc ?d] [?c :chunk/id ?id]]
            (db) path)))

(defn- all-chunk-ids []
  (set (d/q '[:find [?id ...] :where [_ :chunk/id ?id]] (db))))

(defn- chunk-state
  "A chunk entity with its vector as a comparable Clojure vector."
  [id]
  (update (d/pull (db) (quote [*]) [:chunk/id id]) :chunk/vec vec))

(defn- groups [path]
  (set (:doc/effective-groups (d/pull (db) [:doc/effective-groups] [:doc/path path]))))

(defn- fulltext [q]
  (set (d/q '[:find [?id ...] :in $ ?q
              :where [(fulltext $ :chunk/index-text ?q {:top 20}) [[?e _ _]]] [?e :chunk/id ?id]]
            (db) q)))

(defn- seed! []
  (put! "_collection.edn" "{:name \"全公司\" :read-groups [\"all\"]}")
  (put! "hr/_collection.edn" "{:name \"人資\" :read-groups [\"hr\"]}")
  (put! "hr/leave.md" "# 請假規定\n\n## 特休\nannual leave rules. 見 [獎金](payroll/bonus.md)。\n")
  (put! "hr/payroll/bonus.md" "# 年終獎金\n\nbonus policy text.\n")
  (put! "finance/budget.md" "---\ntitle: 預算\nread_groups: [finance-lead]\ntags: [money, plan]\n---\n# Budget\n\nbudget numbers. See [[年終獎金]] and [[Missing Page]].\n")
  (put! "notes.txt" "plain text notes\n\nsecond paragraph\n"))

(deftest test-first-ingest
  (seed!)
  (let [rep (ingest!)]
    (is (= 4 (:added rep)))
    (is (= [] (:errors rep)))
    (is (= 0 (:index-lag rep)))
    (testing "collections carry materialized effective groups"
      (is (= #{"hr"} (set (:collection/effective-groups
                            (d/pull (db) [:collection/effective-groups] [:collection/path "hr/payroll"])))))
      (is (= "hr" (get-in (d/pull (db) [{:collection/parent [:collection/path]}] [:collection/path "hr/payroll"])
                          [:collection/parent :collection/path]))))
    (testing "doc ACL: collection inheritance and frontmatter override"
      (is (= #{"hr"} (groups "hr/payroll/bonus.md")))
      (is (= #{"finance-lead"} (groups "finance/budget.md")))
      (is (= #{"all"} (groups "notes.txt"))))
    (testing "doc attributes"
      (let [doc (d/pull (db) '[*] [:doc/path "finance/budget.md"])]
        (is (= "預算" (:doc/title doc)))
        (is (= #{"money" "plan"} (set (:doc/tags doc))))
        (is (= #{"finance-lead"} (set (:doc/declared-groups doc))))
        (is (= 64 (count (:doc/hash doc))))))
    (testing "sections form a tree; chunks carry the contextual header"
      (let [sec (d/pull (db) '[:section/trail {:section/parent [:section/id]}] [:section/id "hr/leave.md#1"])]
        (is (= "請假規定 > 特休" (:section/trail sec)))
        (is (= "hr/leave.md#0" (get-in sec [:section/parent :section/id]))))
      (let [c (chunk-state "hr/leave.md::0")]
        (is (str/starts-with? (:chunk/index-text c) "文件：請假規定\n章節：請假規定 > 特休\n\n"))
        (is (= (:chunk/text c)
               (subs (slurp (io/file *corpus* "hr/leave.md")) (:chunk/char-start c) (:chunk/char-end c))))))
    (testing "embeddings are computed from index-text"
      (is (some #(str/starts-with? % "文件：請假規定") @*embedded*)))
    (testing "fulltext finds chunks"
      (is (= #{"hr/payroll/bonus.md::0"} (fulltext "policy"))))
    (testing "links: relative .md and wikilink resolve; unknown wikilink is reported"
      (is (= #{"hr/payroll/bonus.md"}
             (set (map :doc/path (:doc/links-to (d/pull (db) [{:doc/links-to [:doc/path]}] [:doc/path "hr/leave.md"]))))))
      (is (= #{"hr/payroll/bonus.md"}
             (set (map :doc/path (:doc/links-to (d/pull (db) [{:doc/links-to [:doc/path]}] [:doc/path "finance/budget.md"]))))))
      (is (= [{:doc "finance/budget.md"
               :link "[[Missing Page]]"}] (:unresolved-links rep))))))

(deftest test-unchanged-rerun-is-skipped
  (seed!)
  (ingest!)
  (reset! *embedded* [])
  (let [rep (ingest!)]
    (is (= 4 (:skipped rep)))
    (is (= 0 (+ (:added rep) (:updated rep) (:acl-updated rep) (:deleted rep))))
    (is (empty? @*embedded*))))

(deftest test-modifying-one-doc-rewrites-only-that-doc
  (seed!)
  (ingest!)
  (let [other-before (chunk-state "hr/leave.md::0")
        at-before (:doc/ingested-at (d/pull (db) [:doc/ingested-at] [:doc/path "hr/leave.md"]))]
    (reset! *embedded* [])
    (put! "hr/payroll/bonus.md" "# 年終獎金\n\nrevised payout schedule.\n")
    (let [rep (ingest!)]
      (is (= 1 (:updated rep)))
      (is (= 3 (:skipped rep)))
      (is (every? #(str/includes? % "文件：年終獎金") @*embedded*) "only the changed doc is embedded")
      (is (= other-before (chunk-state "hr/leave.md::0")))
      (is (= at-before (:doc/ingested-at (d/pull (db) [:doc/ingested-at] [:doc/path "hr/leave.md"])))))
    (testing "old text is gone from the fulltext index, new text is in"
      (is (empty? (fulltext "policy")))
      (is (= #{"hr/payroll/bonus.md::0"} (fulltext "payout"))))))

(deftest test-shrinking-doc-drops-extra-chunks-and-sections
  (put! "a.md" "# One\n\nfirst.\n\n# Two\n\nsecond.\n\n# Three\n\nthird.\n")
  (ingest!)
  (is (= #{"a.md::0" "a.md::1" "a.md::2"} (chunk-ids "a.md")))
  (put! "a.md" "# One\n\nonly this now.\n")
  (ingest!)
  (is (= #{"a.md::0"} (chunk-ids "a.md")))
  (is (= #{"a.md#0"} (set (d/q '[:find [?id ...] :where [_ :section/id ?id]] (db)))))
  (is (empty? (fulltext "third")))
  (testing "vectors of dropped chunks are gone too"
    (is (= 1 (count (d/q '[:find ?e :in $ ?v
                           :where [(vec-neighbors $ :chunk/vec ?v {:top 10}) [[?e _ _]]]]
                         (db) (float-array [1 1 1])))))))

(deftest test-deleted-file-leaves-no-chunks
  (seed!)
  (ingest!)
  (is (seq (chunk-ids "hr/payroll/bonus.md")))
  (rm! "hr/payroll/bonus.md")
  (let [rep (ingest!)]
    (is (= 1 (:deleted rep)))
    (is (= ["hr/payroll/bonus.md"] (:deleted-paths rep)))
    (is (nil? (d/entid (db) [:doc/path "hr/payroll/bonus.md"])))
    (is (not-any? #(str/starts-with? % "hr/payroll/bonus.md") (all-chunk-ids)))
    (is (not-any? #(str/starts-with? % "hr/payroll/bonus.md")
                  (d/q '[:find [?id ...] :where [_ :section/id ?id]] (db))))
    (is (empty? (fulltext "policy")))
    (testing "the link to it is gone and now reported unresolved"
      (is (empty? (:doc/links-to (d/pull (db) [:doc/links-to] [:doc/path "hr/leave.md"]))))
      (is (some #(= {:doc "hr/leave.md"
                     :link "payroll/bonus.md"} %) (:unresolved-links rep))))))

(deftest test-acl-only-change-does-not-reembed
  (seed!)
  (ingest!)
  (let [chunks-before (chunk-state "hr/leave.md::0")]
    (reset! *embedded* [])
    (put! "hr/_collection.edn" "{:name \"人資\" :read-groups [\"hr\" \"hr-lead\"]}")
    (let [rep (ingest!)]
      (is (= 2 (:acl-updated rep)) "leave.md and payroll/bonus.md inherit from hr/")
      (is (= 0 (:updated rep)))
      (is (empty? @*embedded*))
      (is (= #{"hr" "hr-lead"} (groups "hr/leave.md")))
      (is (= #{"hr" "hr-lead"} (groups "hr/payroll/bonus.md")))
      (is (= #{"finance-lead"} (groups "finance/budget.md")) "frontmatter override untouched")
      (is (= chunks-before (chunk-state "hr/leave.md::0"))))
    (testing "removing a _collection.edn falls back to the parent's groups"
      (rm! "hr/_collection.edn")
      (ingest!)
      (is (= #{"all"} (groups "hr/leave.md")))
      (is (nil? (:collection/declared-groups
                  (d/pull (db) [:collection/declared-groups] [:collection/path "hr"])))))))

(deftest test-empty-read-groups-means-no-groups
  (put! "_collection.edn" "{:read-groups []}")
  (put! "secret.md" "# S\n\nhidden.\n")
  (ingest!)
  (is (= #{} (groups "secret.md"))))

(deftest test-link-to-doc-added-later-resolves
  (put! "a.md" "# A\n\nsee [b](b.md).\n")
  (is (= [{:doc "a.md"
           :link "b.md"}] (:unresolved-links (ingest!))))
  (put! "b.md" "# B\n\nhere.\n")
  (let [rep (ingest!)]
    (is (= 1 (:skipped rep)) "a.md itself is unchanged")
    (is (= [] (:unresolved-links rep)))
    (is (= [{:doc/path "b.md"}] (:doc/links-to (d/pull (db) [{:doc/links-to [:doc/path]}] [:doc/path "a.md"]))))))

(deftest test-per-file-errors-are-isolated
  (seed!)
  (let [rep (job/ingest! *conn* {:corpus-dir *corpus*
                                 :root-read-groups ["all"]
                                 :embed-fn (fn [texts]
                                             (if (some #(str/includes? % "bonus policy") texts)
                                               (throw (ex-info "embed down" {}))
                                               (stub-embed texts)))})]
    (is (= [{:path "hr/payroll/bonus.md"
             :status :error
             :error "embed down"}] (:errors rep)))
    (is (= 3 (:added rep)))
    (is (nil? (d/entid (db) [:doc/path "hr/payroll/bonus.md"])) "failed doc is not half-written")))

(deftest test-resolve-path-link
  (is (= "hr/b.md" (writer/resolve-path-link "hr/a.md" "b.md")))
  (is (= "b.md" (writer/resolve-path-link "hr/a.md" "../b.md#part")))
  (is (= "hr/x/c.md" (writer/resolve-path-link "hr/a.md" "./x/c.md?v=1")))
  (is (= "hr/請假 規定.md" (writer/resolve-path-link "hr/a.md" "%E8%AB%8B%E5%81%87%20%E8%A6%8F%E5%AE%9A.md")))
  (is (nil? (writer/resolve-path-link "a.md" "../../outside.md")))
  (is (nil? (writer/resolve-path-link "a.md" "https://x.y/z.md")))
  (is (nil? (writer/resolve-path-link "a.md" "/abs.md")))
  (is (nil? (writer/resolve-path-link "a.md" "image.png"))))

(deftest test-frontmatter-acl-change-does-not-reembed
  ;; SPEC §7.2 rule 3: a frontmatter read_groups change is ACL-only too,
  ;; although it changes the file bytes
  (seed!)
  (ingest!)
  (let [chunks-before (chunk-state "finance/budget.md::0")
        budget "---\ntitle: 預算\nread_groups: [finance-lead, cfo]\ntags: [money, plan]\n---\n# Budget\n\nbudget numbers. See [[年終獎金]] and [[Missing Page]].\n"]
    (reset! *embedded* [])
    (put! "finance/budget.md" budget)
    (let [rep (ingest!)]
      (is (= 1 (:acl-updated rep)))
      (is (= 0 (:updated rep)))
      (is (empty? @*embedded*))
      (is (= #{"finance-lead" "cfo"} (groups "finance/budget.md")))
      (let [after (chunk-state "finance/budget.md::0")]
        (is (= (:chunk/vec chunks-before) (:chunk/vec after)) "vector reused")
        (is (= (:chunk/text chunks-before) (:chunk/text after)))
        (is (= (:chunk/text after) (subs budget (:chunk/char-start after) (:chunk/char-end after)))
            "offsets follow the longer frontmatter line"))
      (is (= (writer/sha256-hex (.getBytes budget "UTF-8"))
             (:doc/hash (d/pull (db) [:doc/hash] [:doc/path "finance/budget.md"])))
          ":doc/hash follows the file, so the viewer's changed-file notice stays right")
      (is (= 0 (:acl-updated (ingest!))) "and the next run skips it")))
  (testing "other frontmatter or body changes still re-index"
    (reset! *embedded* [])
    (put! "finance/budget.md" "---\ntitle: 新預算\nread_groups: [finance-lead, cfo]\ntags: [money, plan]\n---\n# Budget\n\nbudget numbers. See [[年終獎金]] and [[Missing Page]].\n")
    (is (= 1 (:updated (ingest!))))
    (is (seq @*embedded*))
    (reset! *embedded* [])
    (put! "finance/budget.md" "---\ntitle: 新預算\nread_groups: [finance-lead, cfo]\ntags: [money, plan]\n---\n# Budget\n\nnew numbers.\n")
    (is (= 1 (:updated (ingest!))))
    (is (seq @*embedded*))))
