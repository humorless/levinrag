(ns spikes.cjk-analyzer
  "Spike: bigram vs HanLP vs hybrid CJK analyzer on the sample corpus
   (docs/spikes/cjk-analyzer.md). Lexical channel only, so the analyzer
   is the only variable.

   Run:  clojure -M:jvm-opts:test:dev:spike-hanlp -e \"(require 'spikes.cjk-analyzer) (spikes.cjk-analyzer/-main)\""
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datalevin.core :as d]
            [datalevin.udf :as udf]
            [hybridrag.db.schema :as schema]
            [hybridrag.eval.harness :as harness]
            [hybridrag.fixtures :as fx]
            [hybridrag.ingest.job :as job]
            [hybridrag.retrieval.datalevin :as rd]
            [hybridrag.retrieval.protocol :as p]
            [hybridrag.search.analyzer :as bigram]
            [hybridrag.tmp :as tmp])
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

(defn- open-with [dir id f]
  (let [desc {:udf/lang :clojure :udf/kind :analyzer :udf/id (keyword (str "spike-" id))}
        reg (doto (udf/create-registry) (udf/register! desc f))]
    (d/get-conn dir schema/index-schema
                (merge (schema/index-opts fx/dims)
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
