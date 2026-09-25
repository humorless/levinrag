(ns spikes.rerank-threshold
  "Spike: calibrate :retrieve/rerank-min-score (docs/spikes/rerank-threshold.md).

   For every question, run the full pipeline with real models and keep
   each candidate's rerank score and whether it is an answer (expected
   section or doc). Questions without an answer the user can read
   (unanswerable, ACL-negative) are the control group: a good threshold
   leaves them with no passages (→ no-evidence reply). Rows hold ids and
   scores only, never text, so book-corpus rows can be summarized in the
   doc; the corpus itself stays in no-commit/."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [datalevin.core :as d]
            [replware.levinrag.db.index-conn :as index-conn]
            [replware.levinrag.ingest.job :as job]
            [replware.levinrag.llm.embed :as embed]
            [replware.levinrag.llm.rerank-client :as rerank-client]
            [replware.levinrag.retrieval.datalevin :as rd]
            [replware.levinrag.retrieval.pipeline :as pipeline]
            [replware.levinrag.tmp :as tmp]))

(def local-models
  {:embed {:base-url "http://localhost:1234/v1"
           :model "text-embedding-bge-m3"
           :dims 1024}
   :rerank {:base-url "http://localhost:8002"
            :path "/v1/rerank"
            :model "bge-reranker-v2-m3"}})

(defn- section-of [db chunk-ids]
  (into {} (d/q '[:find ?cid ?sid :in $ [?cid ...]
                  :where [?c :chunk/id ?cid] [?c :chunk/section ?s] [?s :section/id ?sid]]
                db (vec chunk-ids))))

(defn collect
  "Rows {:id :answerable? :degraded? :scores [[rerank-score answer?] ...]}
   (best-first) for `questions` on a fresh real-model index of `corpus-dir`.
   A question is answerable when it has :expected-sections or :expected-docs."
  [{:keys [corpus-dir questions principals index-root models]
    :or {models local-models}}]
  (let [{:keys [embed rerank]} models
        dir (str (io/file index-root "threshold-idx"))
        _ (tmp/delete-tree! dir)
        conn (index-conn/open dir (:dims embed))
        embed-fn #(embed/embed-all! embed % 32)]
    (try
      (job/ingest! conn {:corpus-dir corpus-dir
                         :embed-fn embed-fn})
      (let [deps {:retriever (rd/retriever conn embed-fn)
                  :rerank-fn #(rerank-client/rerank! rerank %1 %2 %3)}]
        (vec (for [{:keys [id user query expected-sections expected-docs]} questions]
               (let [res (pipeline/search deps (principals user) query {:final-k 1000})
                     cands (:candidates res)
                     sec (section-of (d/db conn) (map :chunk/id cands))
                     answer? (cond
                               (seq expected-sections) #((set expected-sections) (sec (:chunk/id %)))
                               (seq expected-docs) #((set expected-docs) (:doc/path %))
                               :else (constantly false))]
                 {:id id
                  :answerable? (boolean (or (seq expected-sections) (seq expected-docs)))
                  :degraded? (boolean (seq (:degraded res)))
                  :scores (vec (for [c cands :when (:rerank c)]
                                 [(:rerank c) (boolean (answer? c))]))}))))
      (finally (d/close conn) (tmp/delete-tree! dir)))))

(defn- selected [scores t final-k]
  (take final-k (filter (fn [[s]] (or (nil? t) (>= s t))) scores)))

(defn sweep
  "Per threshold t (nil = no threshold): answerable questions that keep ≥1
   answer in the final-k selection, ones that lose it vs no threshold,
   answerable questions left with nothing (wrong no-evidence), control
   questions left with nothing (right no-evidence), mean passages kept."
  [rows thresholds & {:keys [final-k]
                      :or {final-k 8}}]
  (let [ans (filter :answerable? rows)
        ctl (remove :answerable? rows)
        hit? (fn [r t] (some second (selected (:scores r) t final-k)))
        empty-sel? (fn [r t] (empty? (selected (:scores r) t final-k)))
        mean (fn [xs] (if (seq xs) (/ (reduce + xs) (double (count xs))) 0.0))]
    (vec (for [t thresholds]
           {:t t
            :hit (count (filter #(hit? % t) ans))
            :lost (count (filter #(and (hit? % nil) (not (hit? % t))) ans))
            :answerable-empty (count (filter #(empty-sel? % t) ans))
            :control-empty (count (filter #(empty-sel? % t) ctl))
            :mean-kept (mean (map #(count (selected (:scores %) t final-k)) rows))
            :n-answerable (count ans)
            :n-control (count ctl)}))))

(defn best-answer-scores
  "For answerable questions: the highest rerank score of an answer chunk
   (nil when no answer was retrieved at all). For control questions: the
   highest score of any chunk."
  [rows]
  {:answerable (sort (keep (fn [r] (some (fn [[s a]] (when a s)) (:scores r))) (filter :answerable? rows)))
   :control (sort (keep (fn [r] (ffirst (:scores r))) (remove :answerable? rows)))})

(defn print-sweep [sw]
  (pprint/print-table [:t :hit :lost :answerable-empty :control-empty :mean-kept] sw))
