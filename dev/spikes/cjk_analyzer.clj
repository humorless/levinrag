(ns spikes.cjk-analyzer
  "Spike: bigram vs HanLP vs hybrid CJK analyzer (docs/spikes/cjk-analyzer.md).
   -main / probe-main: sample corpus, lexical channel only, so the
   analyzer is the only variable. bias-check / run-corpus-eval: any
   corpus + question file with real local models (the book corpus lives
   in no-commit/ and is never committed).

   Run:  clojure -M:jvm-opts:test:dev:spike-hanlp -e \"(require 'spikes.cjk-analyzer) (spikes.cjk-analyzer/-main)\""
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datalevin.core :as d]
            [datalevin.udf :as udf]
            [replware.levinrag.db.schema :as schema]
            [replware.levinrag.eval.harness :as harness]
            [replware.levinrag.fixtures :as fx]
            [replware.levinrag.ingest.job :as job]
            [replware.levinrag.ingest.markdown :as md]
            [replware.levinrag.llm.embed :as embed]
            [replware.levinrag.llm.rerank-client :as rerank-client]
            [replware.levinrag.retrieval.datalevin :as rd]
            [replware.levinrag.retrieval.protocol :as p]
            [replware.levinrag.search.analyzer :as bigram]
            [replware.levinrag.tmp :as tmp])
  (:import [com.hankcs.hanlp.tokenizer TraditionalChineseTokenizer]
           [java.lang Character$UnicodeScript]))

(defn- cjk-char? [^long cp]
  (let [s (Character$UnicodeScript/of (int cp))]
    (or (= s Character$UnicodeScript/HAN) (= s Character$UnicodeScript/HIRAGANA)
        (= s Character$UnicodeScript/KATAKANA) (= s Character$UnicodeScript/HANGUL))))

(defn- has-cjk? [^String s] (boolean (some #(cjk-char? %) (.toArray (.codePoints s)))))

(defn- hanlp-words
  "[[word offset] ...] for the CJK words HanLP finds in `s`."
  [^String s]
  (for [t (TraditionalChineseTokenizer/segment s)
        :let [w (.word t)]
        :when (has-cjk? w)]
    [w (.offset t)]))

(defn- ascii-terms
  "The non-CJK terms of the §8.1 analyzer (identifier handling)."
  [s]
  (for [[term _ offset] (bigram/analyze s)
        :when (not (has-cjk? term))]
    [term offset]))

(defn- with-positions [term-offsets]
  (->> term-offsets
       distinct
       (sort-by (fn [[t o]] [o (- (count t)) t]))
       (map-indexed (fn [i [t o]] [t i o]))
       vec))

(defn hanlp-analyze
  "HanLP words for CJK, §8.1 rules for everything else."
  [s]
  (with-positions (concat (ascii-terms (or s "")) (hanlp-words (or s "")))))

(defn hybrid-analyze
  "§8.1 bigrams + HanLP words of length ≥ 2."
  [s]
  (with-positions (concat (map (fn [[t _ o]] [t o]) (bigram/analyze s))
                          (filter #(>= (count (first %)) 2) (hanlp-words (or s ""))))))

(def analyzers
  (array-map "bigram" bigram/analyze
             "hanlp" hanlp-analyze
             "hybrid" hybrid-analyze))

(defn- open-with [dir id f & [dims]]
  (let [desc {:udf/lang :clojure :udf/kind :analyzer :udf/id (keyword (str "spike-" id))}
        reg (doto (udf/create-registry) (udf/register! desc f))]
    (d/get-conn dir schema/index-schema
                (merge (schema/index-opts (or dims fx/dims))
                       {:runtime-opts {:udf-registry reg}
                        :search-domains {"chunk/index-text" {:analyzer desc}}}))))

(defn- first-hit-rank [expected docs]
  (some (fn [[i d]] (when ((set expected) d) (inc i))) (map-indexed vector docs)))

(defn run-analyzer [id f questions principals]
  (let [dir (tmp/dir (str "spike-" id))
        conn (open-with dir id f)]
    (try
      (let [t0 (System/nanoTime)
            rep (job/ingest! conn {:corpus-dir "corpus-sample" :embed-fn fx/hash-embed})
            ingest-ms (quot (- (System/nanoTime) t0) 1000000)
            r (rd/retriever conn fx/hash-embed)
            rows (for [{:keys [id user query expected-docs]} questions
                       :when (seq expected-docs)]
                   (let [res (p/channel r (principals user) :lexical query {})
                         docs (harness/ranked-docs (:candidates res))]
                     {:id id
                      :top1 (= (first docs) (first (filter (set expected-docs) docs)))
                      :first-rank (first-hit-rank expected-docs docs)
                      :mrr (harness/mrr-at 10 expected-docs docs)
                      :recall-5 (harness/recall-at 5 expected-docs docs)
                      :raw-hits (:raw-hits res)}))
            n (count rows)]
        {:analyzer id
         :errors (count (:errors rep))
         :ingest-ms ingest-ms
         :top1 (count (filter :top1 rows))
         :n n
         :mrr-10 (/ (reduce + (map :mrr rows)) n)
         :recall-5 (/ (reduce + (map :recall-5 rows)) n)
         :mean-raw-hits (/ (reduce + (map :raw-hits rows)) (double n))
         :rows (vec rows)})
      (finally (d/close conn) (tmp/delete-tree! dir)))))

(defn -main []
  (let [questions (edn/read-string (slurp "eval/questions.edn"))
        principals (harness/load-principals "eval/users.edn")
        results (mapv (fn [[id f]] (run-analyzer id f questions principals)) analyzers)]
    (println (format "%-8s %6s %8s %9s %10s %9s" "analyzer" "top1" "MRR@10" "recall@5" "raw-hits" "ingest-ms"))
    (doseq [{:keys [analyzer top1 n mrr-10 recall-5 mean-raw-hits ingest-ms]} results]
      (println (format "%-8s %3d/%-2d %8.3f %9.3f %10.1f %9d" analyzer top1 n mrr-10 recall-5 mean-raw-hits ingest-ms)))
    (println "\nper question (first expected doc rank, lexical):")
    (let [by-id (fn [res] (into {} (map (juxt :id :first-rank)) (:rows res)))
          maps (map by-id results)]
      (doseq [qid (map :id (:rows (first results)))
              :let [ranks (map #(get % qid) maps)]
              :when (apply not= ranks)]
        (println (format "  %-12s %s" qid (str/join "  " (map (fn [a rk] (str a "=" (or rk "-"))) (keys analyzers) ranks))))))
    results))

;; --- exact-term probe: precision/recall of lexical hits for domain terms ---

(def probe-terms
  ["特休" "料號" "表單" "統編" "交期" "補休" "考績" "年資" "申訴人" "識別證"
   "年終獎金" "電源管理" "發票" "回滾" "閒置" "試用期" "補班" "婚假" "報支" "驗收"])

(defn term-probe
  "For each term: of the top-10 lexical hits, the share whose text contains
   the term (precision@10), and the share of containing chunks found in
   the top 10 (recall@10, capped by 10)."
  [id f]
  (let [dir (tmp/dir (str "probe-" id))
        conn (open-with dir id f)]
    (try
      (job/ingest! conn {:corpus-dir "corpus-sample" :embed-fn fx/hash-embed})
      (let [db (d/db conn)
            texts (into {} (d/q '[:find ?id ?t :where [?c :chunk/id ?id] [?c :chunk/text ?t]] db))
            r (rd/retriever conn fx/hash-embed)
            admin {:username "admin" :groups #{} :admin? true}]
        (vec (for [term probe-terms
                   :let [relevant (set (keep (fn [[cid t]] (when (str/includes? t term) cid)) texts))
                         hits (map :chunk/id (take 10 (:candidates (p/channel r admin :lexical term {}))))
                         good (count (filter relevant hits))]]
               {:term term
                :relevant (count relevant)
                :p10 (if (seq hits) (/ good (double (count hits))) 0.0)
                :r10 (if (seq relevant) (/ good (double (min 10 (count relevant)))) 1.0)})))
      (finally (d/close conn) (tmp/delete-tree! dir)))))

(defn probe-main []
  (let [res (into (array-map) (for [[id f] analyzers] [id (term-probe id f)]))
        avg (fn [k rows] (/ (reduce + (map k rows)) (count rows)))]
    (println (format "%-10s %4s   %s" "term" "rel" (str/join "   " (for [id (keys res)] (format "%-15s" (str id " P@10/R@10"))))))
    (doseq [i (range (count probe-terms))]
      (println (format "%-10s %4d   %s" (nth probe-terms i) (:relevant (nth (first (vals res)) i))
                       (str/join "   " (for [rows (vals res) :let [row (nth rows i)]]
                                         (format "%-15s" (format "%.2f / %.2f" (:p10 row) (:r10 row))))))))
    (let [cells (for [rows (vals res)]
                  (format "%-15s" (format "%.2f / %.2f" (avg :p10 rows) (avg :r10 rows))))]
      (println (format "%-10s %4s   %s" "MEAN" "" (str/join "   " cells))))
    res))

;; --- book corpus (local only; the corpus lives in no-commit/) ---

(defn corpus-sections
  "section id (\"<file>#<n>\", as in the index) → section text, for the
   .md files directly under `dir`."
  [dir]
  (into (sorted-map)
        (for [f (.list (io/file dir))
              :when (str/ends-with? f ".md")
              :let [s (slurp (io/file dir f))]
              [i {:keys [char-start char-end]}] (map-indexed vector (:sections (md/parse-markdown s)))]
          [(str f "#" i) (subs s char-start char-end)])))

(defn- cjk-bigrams [s]
  (set (keep (fn [[t]] (when (and (has-cjk? t) (= 2 (count t))) t)) (bigram/analyze s))))

(defn- coverage
  "Share of the `grams` that occur in the bigram set `in`."
  [grams in]
  (if (seq grams) (/ (count (filter in grams)) (double (count grams))) 0.0))

(defn bias-check
  "Lexical-leak check for generated questions: per question, the share of
   its CJK bigrams found in its answer sections, against the same share
   for every section of the corpus. A question whose answer sections
   cover far more of its bigrams than an average section favours the
   lexical channel."
  [corpus-dir questions]
  (let [grams (update-vals (corpus-sections corpus-dir) cjk-bigrams)
        mean (fn [xs] (/ (reduce + xs) (double (count xs))))
        rows (vec (for [{:keys [id query expected-sections]} questions
                        :let [q (cjk-bigrams query)
                              cov (update-vals grams #(coverage q %))
                              ans (map cov expected-sections)
                              others (vals (apply dissoc cov expected-sections))]]
                    {:id id
                     :bigrams (count q)
                     :answer-union (coverage q (reduce into #{} (map grams expected-sections)))
                     :answer-mean (mean ans)
                     :answer-max (apply max ans)
                     :all-mean (mean (vals cov))
                     :other-max (apply max others)
                     :book (coverage q (reduce into #{} (vals grams)))}))]
    {:rows rows
     :mean (into {} (for [k [:bigrams :answer-union :answer-mean :answer-max :all-mean :other-max :book]]
                      [k (mean (map k rows))]))}))

(def local-models
  "Local model endpoints (VLLM_SETUP.md): bge-m3 on LM Studio, the
   reranker on llama.cpp."
  {:embed {:base-url "http://localhost:1234/v1" :model "text-embedding-bge-m3" :dims 1024}
   :rerank {:base-url "http://localhost:8002" :path "/v1/rerank" :model "bge-reranker-v2-m3"}})

(defn- memo-embed
  "An embed-fn that embeds each distinct text once, shared across the
   analyzer indexes (the embeddings do not depend on the analyzer)."
  [cfg]
  (let [cache (atom {})]
    (fn [texts]
      (let [missing (vec (distinct (remove @cache texts)))]
        (when (seq missing)
          (swap! cache into (map vector missing (embed/embed-all! cfg missing 32))))
        (mapv @cache texts)))))

(defn run-corpus-eval
  "Every analyzer × `variant-names` on a real-model index of `corpus-dir`.
   Indexes go to <index-root>/<analyzer> and are deleted afterwards;
   index-root must be outside the repo's committed paths (no-commit/ or
   scratch). Writes one report per analyzer under `results-dir`."
  [{:keys [corpus-dir questions-path users-path index-root results-dir variant-names models]
    :or {variant-names ["lexical" "semantic" "hybrid" "hybrid+rerank"]
         models local-models}}]
  (let [questions (edn/read-string (slurp questions-path))
        principals (harness/load-principals users-path)
        {:keys [embed rerank]} models
        embed-fn (memo-embed embed)]
    (into (array-map)
          (for [[id f] analyzers]
            (let [dir (str (io/file index-root id))
                  _ (tmp/delete-tree! dir)
                  conn (open-with dir id f (:dims embed))]
              (try
                (let [t0 (System/nanoTime)
                      rep (job/ingest! conn {:corpus-dir corpus-dir :embed-fn embed-fn})
                      ingest-ms (quot (- (System/nanoTime) t0) 1000000)
                      report (harness/run-eval
                               {:retriever (rd/retriever conn embed-fn)
                                :rerank-fn #(rerank-client/rerank! rerank %1 %2 %3)}
                               conn
                               {:questions questions :principals principals :variant-names variant-names})
                      report (assoc report :analyzer id :ingest-ms ingest-ms :ingest-errors (:errors rep))]
                  (println (str "\n== " id " (ingest " ingest-ms " ms, results "
                                (harness/write-results! (str (io/file results-dir id)) report) ")"))
                  (println (harness/table report))
                  [id report])
                (finally (d/close conn) (tmp/delete-tree! dir))))))))

(defn corpus-table
  "analyzer × variant → recall@5 / recall@10 / MRR@10."
  [results]
  (str/join "\n"
            (cons (format "%-8s %-15s %9s %9s %8s" "analyzer" "variant" "recall@5" "recall@10" "MRR@10")
                  (for [[a {:keys [variants]}] results
                        [v {{:keys [recall-5 recall-10 mrr-10]} :summary}] variants]
                    (format "%-8s %-15s %9.3f %9.3f %8.3f" a v recall-5 recall-10 mrr-10)))))
