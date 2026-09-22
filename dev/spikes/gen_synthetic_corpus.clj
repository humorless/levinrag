(ns spikes.gen-synthetic-corpus
  "T0.5 spike (SPEC.md §9.3 / plan Task 5): generate a synthetic index.dtlv
  (10,000 docs / 100,000 chunks / 50 groups) and measure p50 latency of the
  ACL-filtered lexical query pattern from SPEC.md §9.3.

  THROWAWAY SCRIPT — not production code, not covered by clj-kondo/cljfmt
  CI gates, does not touch `hybridrag.db.schema/app-schema`. Opens its own
  disposable Datalevin dir under the OS temp dir (never `data/` or
  `data-test/`, which are the real app's dirs).

  Run (from the repo root; the :jvm-opts alias is required, see deps.edn
  comment + docs/decisions.md — Datalevin 1.1.0 needs --add-opens flags
  that the Clojure CLI does not read from top-level :jvm-opts).

  NOTE: this is invoked via `load-file`, not `-m`/extra-paths on \"dev\" —
  `dev/user.clj` (the project's REPL user ns) is auto-required by the
  Clojure runtime at boot whenever \"dev\" is on the classpath at all
  (RT.init loads a classpath-resident user.clj unconditionally), and that
  ns needs the full :dev+:test alias dep set just to load. `load-file`
  reads this file directly off disk instead, so \"dev\" never needs to be
  an extra-path for this script to run:

    clojure -M:jvm-opts -e '(load-file \"dev/spikes/gen_synthetic_corpus.clj\")(spikes.gen-synthetic-corpus/-main)'

  Optional args (all positional, all optional, defaults match the plan's
  spec): n-docs chunks-per-doc n-groups runs-per-case

    clojure -M:jvm-opts -e '(load-file \"dev/spikes/gen_synthetic_corpus.clj\")(spikes.gen-synthetic-corpus/-main \"10000\" \"10\" \"50\" \"50\")'"
  (:require [datalevin.core :as d]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Schema — mirrors the shape docs/spikes/embedding.md and
;; docs/spikes/fulltext.md confirmed for :chunk/index-text (fulltext,
;; autoDomain, no :db/embedding / :db.type/vec on this attribute — this
;; spike has no vector attribute at all, see docs/datalevin_debug_notes.md
;; §4 on the unrelated :db.type/vec transact! bug this sidesteps).
;; ---------------------------------------------------------------------------

(def schema
  {:doc/path              {:db/valueType :db.type/string
                            :db/unique    :db.unique/identity}
   :doc/effective-groups  {:db/valueType   :db.type/string
                            :db/cardinality :db.cardinality/many}
   :chunk/id              {:db/valueType :db.type/string
                            :db/unique    :db.unique/identity}
   :chunk/doc             {:db/valueType :db.type/ref}
   :chunk/index-text      {:db/valueType             :db.type/string
                            :db/fulltext              true
                            :db.fulltext/autoDomain   true}})

;; Small Traditional-Chinese contract-ish vocabulary — content doesn't
;; matter per the plan, only volume and attribute shape, but real CJK text
;; exercises the same tokenizer path production queries will use.
(def filler-terms
  ["合約" "條款" "甲方" "乙方" "違約" "賠償" "保密" "終止" "續約" "付款"
   "交付" "驗收" "智慧財產權" "授權" "不可抗力" "爭議" "仲裁" "管轄" "生效" "解除"])

;; ---------------------------------------------------------------------------
;; Deterministic RNG helpers (seeded, so numbers are reproducible)
;; ---------------------------------------------------------------------------

(defn- rand-int-below
  ^long [^java.util.Random rng ^long n]
  (.nextInt rng (int n)))

(defn- rand-nth-seeded
  [rng coll]
  (nth coll (rand-int-below rng (count coll))))

(defn- sample-without-replacement
  "Pick k distinct items from coll using rng, order not significant."
  [rng coll k]
  (loop [pool (vec coll) chosen [] n (long k)]
    (if (or (zero? n) (empty? pool))
      chosen
      (let [i (rand-int-below rng (count pool))]
        (recur (into (subvec pool 0 i) (subvec pool (inc i) (count pool)))
               (conj chosen (pool i))
               (dec n))))))

(defn- gen-chunk-text
  [rng]
  ;; IMPORTANT: space-joined, not concatenated. Datalevin's default
  ;; analyzer splits on whitespace/punctuation only (docs/spikes/fulltext.md
  ;; §7: "CJK fulltext requires a custom analyzer UDF" for real prose,
  ;; deferred to Phase 1). Concatenating CJK terms with no separator makes
  ;; the WHOLE chunk text one opaque token, so single-term queries would
  ;; never hit anything and this spike would silently measure an all-miss
  ;; fulltext path. Space-joining terms lets the default analyzer index
  ;; each term individually, giving genuine, checkable BM25 hits while
  ;; still using CJK vocabulary for volume/shape.
  (str/join " " (repeatedly 12 #(rand-nth-seeded rng filler-terms))))

;; ---------------------------------------------------------------------------
;; Data generation
;; ---------------------------------------------------------------------------

(defn- group-names [n-groups]
  (mapv #(str "group-" %) (range n-groups)))

(defn- doc+chunks-tx
  "One doc entity (negative tempid) plus its chunks, all referencing the
  same tempid via :chunk/doc — valid within a single transact! call
  (see datalevin.core :db/id docs: any negative int / string tempid is
  resolved against entities created earlier in the SAME transaction)."
  [rng doc-idx tempid groups chunks-per-doc]
  (let [doc-path   (str "doc-" doc-idx ".md")
        k          (inc (rand-int-below rng 3)) ; 1..3 groups per doc
        doc-groups (sample-without-replacement rng groups k)
        doc-tx     {:db/id                tempid
                    :doc/path             doc-path
                    :doc/effective-groups doc-groups}
        chunk-txs  (for [c (range chunks-per-doc)]
                     {:chunk/id         (str doc-path "#" c)
                      :chunk/doc        tempid
                      :chunk/index-text (gen-chunk-text rng)})]
    (into [doc-tx] chunk-txs)))

(defn generate!
  "Transact n-docs docs (each with chunks-per-doc chunks) into conn, in
  batches, using a seeded RNG for reproducibility. Returns elapsed ms."
  [conn {:keys [n-docs chunks-per-doc n-groups batch-size seed]
         :or   {batch-size 250 seed 42}}]
  (let [rng    (java.util.Random. seed)
        groups (group-names n-groups)
        t0     (System/nanoTime)]
    (doseq [batch-start (range 0 n-docs batch-size)]
      (let [batch-end (min n-docs (+ batch-start batch-size))
            tx-data   (vec
                        (mapcat
                          (fn [doc-idx]
                            (doc+chunks-tx rng doc-idx (- -1 doc-idx)
                                           groups chunks-per-doc))
                          (range batch-start batch-end)))]
        (d/transact! conn tx-data))
      (when (zero? (mod batch-start (* batch-size 10)))
        (println (format "  ... transacted %d/%d docs" batch-start n-docs))))
    (/ (- (System/nanoTime) t0) 1e6)))

;; ---------------------------------------------------------------------------
;; Benchmark: SPEC.md §9.3 ACL-filtered lexical query
;; ---------------------------------------------------------------------------

(defn acl-query
  "The exact query shape from SPEC.md §9.3. index-db is a Datalevin db
  value, query-text a string, user-groups a seq of group-name strings."
  [index-db query-text user-groups]
  (d/q '[:find ?cid ?score
         :in $ ?q [?g ...]
         :where
         [(fulltext $ :chunk/index-text ?q {:top 200 :display :refs+scores})
          [[?e _ _ ?score]]]
         [?e :chunk/doc ?d]
         [?d :doc/effective-groups ?g]
         [?e :chunk/id ?cid]]
       index-db query-text user-groups))

(defn- rand-query-text [rng]
  ;; A single random term. Every chunk is 12 space-joined draws from a
  ;; 20-term vocabulary, so a single term hits ~46% of chunks
  ;; (1 - (19/20)^12) — a deliberately high-recall query that exercises
  ;; the :top 200 over-fetch cap, not an edge case.
  (rand-nth-seeded rng filler-terms))

(defn- percentile
  "p in [0,1]. sorted-ms must already be sorted ascending."
  [sorted-ms p]
  (let [n (count sorted-ms)]
    (nth sorted-ms (int (Math/floor (* p (dec n)))))))

(defn bench-case
  "Run `runs` timed queries for a given number of groups the synthetic
  user belongs to. Varies query text every run, and (for n-user-groups <
  total groups) varies which groups are sampled every run, to avoid
  JIT-warmup skew from repeating one exact query (no criterium on the
  classpath — see deps.edn / plan notes)."
  [index-db all-groups n-user-groups runs seed]
  (let [rng      (java.util.Random. seed)
        ;; warmup: discard first 5 runs' timings
        warmup   5
        total    (+ warmup runs)
        ms       (doall
                   (for [_ (range total)]
                     (let [q      (rand-query-text rng)
                           groups (if (>= n-user-groups (count all-groups))
                                    all-groups
                                    (sample-without-replacement
                                      rng all-groups n-user-groups))
                           t0     (System/nanoTime)
                           _      (acl-query index-db q groups)
                           t1     (System/nanoTime)]
                       (/ (- t1 t0) 1e6))))
        timed    (sort (drop warmup ms))]
    {:n-user-groups n-user-groups
     :runs          runs
     :p50-ms        (percentile timed 0.5)
     :p90-ms        (percentile timed 0.9)
     :min-ms        (first timed)
     :max-ms        (last timed)}))

;; ---------------------------------------------------------------------------
;; Environment info (for the decision doc)
;; ---------------------------------------------------------------------------

(defn env-info []
  {:java-version (System/getProperty "java.version")
   :os           (str (System/getProperty "os.name") " "
                       (System/getProperty "os.version") " "
                       (System/getProperty "os.arch"))
   :cpus         (.availableProcessors (Runtime/getRuntime))
   :max-heap-mb  (long (/ (.maxMemory (Runtime/getRuntime)) 1e6))})

;; ---------------------------------------------------------------------------
;; Cleanup + driver
;; ---------------------------------------------------------------------------

(defn- delete-recursively [^java.io.File f]
  (when (.isDirectory f)
    (run! delete-recursively (.listFiles f)))
  (io/delete-file f true))

(defn -main
  [& args]
  (let [[n-docs chunks-per-doc n-groups runs-per-case]
        (map #(when %2 (Long/parseLong %2))
             [10000 10 50 50] (concat args (repeat nil)))
        n-docs         (or n-docs 10000)
        chunks-per-doc (or chunks-per-doc 10)
        n-groups       (or n-groups 50)
        runs-per-case  (or runs-per-case 50)
        db-dir         (str (System/getProperty "java.io.tmpdir")
                             (when-not (str/ends-with?
                                         (System/getProperty "java.io.tmpdir") "/")
                               "/")
                             "hybridrag-acl-spike-index.dtlv")]
    (println "== T0.5 ACL query perf spike ==")
    (println "env:" (pr-str (env-info)))
    (println "db-dir:" db-dir)
    (let [dir (io/file db-dir)]
      (when (.exists dir)
        (println "  cleaning up stale spike dir...")
        (delete-recursively dir)))

    (let [conn (d/create-conn db-dir schema)]
      (try
        (println (format "generating %d docs x %d chunks, %d groups..."
                          n-docs chunks-per-doc n-groups))
        (let [gen-ms (generate! conn {:n-docs n-docs
                                       :chunks-per-doc chunks-per-doc
                                       :n-groups n-groups})]
          (println (format "  generation done in %.0f ms" gen-ms)))

        (let [db          (d/db conn)
              n-doc-cnt   (d/q '[:find (count ?d) . :where [?d :doc/path]] db)
              n-chunk-cnt (d/q '[:find (count ?e) . :where [?e :chunk/id]] db)]
          (println (format "  verified: %s docs, %s chunks in db"
                            n-doc-cnt n-chunk-cnt))

          (let [all-groups (group-names n-groups)
                cases      [{:label "1 group"   :n 1}
                             {:label "3 groups"  :n 3}
                             {:label (format "all %d groups" n-groups) :n n-groups}]]
            (println "\n== Results ==")
            (println (format "%-16s %5s %10s %10s %10s %10s"
                              "case" "runs" "p50(ms)" "p90(ms)" "min(ms)" "max(ms)"))
            (doseq [{:keys [label n]} cases]
              (let [{:keys [p50-ms p90-ms min-ms max-ms runs]}
                    (bench-case db all-groups n runs-per-case (+ 1000 n))]
                (println (format "%-16s %5d %10.2f %10.2f %10.2f %10.2f"
                                  label runs p50-ms p90-ms min-ms max-ms))))))
        (finally
          (d/close conn))))

    (println "\nDone. (Spike dir left on disk for inspection: " db-dir ")")))
