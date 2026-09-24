(ns hybridrag.eval.harness
  "`bb eval` (SPEC.md §15.2): run every question in eval/questions.edn as
   its user through the search pipeline (direct call, no HTTP) for each
   retrieval variant, and report doc-level recall@5/@10, MRR@10, ACL
   leaks, the longest chunk estimate and per-stage p50/p95 latency."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [datalevin.core :as d]
            [hybridrag.retrieval.pipeline :as pipeline])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(def variants
  "SPEC §15.2 variants, in report order."
  (array-map
    "lexical" {:channels #{:lexical}
               :rerank? false
               :graph? false}
    "semantic" {:channels #{:semantic}
                :rerank? false
                :graph? false}
    "hybrid" {:rerank? false
              :graph? false}
    "hybrid+rerank" {:graph? false}
    "hybrid+rerank+graph" {}))

;; --- metrics (doc-level, deduplicated ranking) ---

(defn ranked-docs
  "Distinct doc paths in the order their chunks appear."
  [candidates]
  (vec (distinct (map :doc/path candidates))))

(defn recall-at [k expected docs]
  (if (empty? expected)
    nil
    (/ (count (filter (set (take k docs)) expected)) (double (count expected)))))

(defn mrr-at [k expected docs]
  (if (empty? expected)
    nil
    (let [hit (first (keep-indexed (fn [i d] (when ((set expected) d) i)) (take k docs)))]
      (if hit (/ 1.0 (inc hit)) 0.0))))

(defn- mean [xs] (when (seq xs) (/ (reduce + xs) (double (count xs)))))

(defn- percentile [p xs]
  (when (seq xs)
    (let [v (vec (sort xs))]
      (v (min (dec (count v)) (int (Math/floor (* p (count v)))))))))

;; --- run ---

(defn- section-ids
  "chunk id → section id for the given chunk ids."
  [db chunk-ids]
  (into {} (d/q '[:find ?cid ?sid :in $ [?cid ...]
                  :where [?c :chunk/id ?cid] [?c :chunk/section ?s] [?s :section/id ?sid]]
                db (vec chunk-ids))))

(defn ranked-sections
  "Distinct section ids in the order their chunks appear."
  [db candidates]
  (let [sid (section-ids db (map :chunk/id candidates))]
    (vec (distinct (keep (comp sid :chunk/id) candidates)))))

(defn- run-one
  "One question. Scored at section level when it has :expected-sections
   (\"<path>#<n>\" ids), else at doc level with :expected-docs."
  [deps db principal {:keys [query expected-docs expected-sections must-not-docs]} opts]
  (let [res (pipeline/search deps principal query opts)
        docs (ranked-docs (:candidates res))
        [expected ranking] (if (seq expected-sections)
                             [expected-sections (ranked-sections db (:candidates res))]
                             [expected-docs docs])
        shown (into (set docs) (map :doc/path (:passages res)))]
    {:docs (vec (take 10 ranking))
     :recall-5 (recall-at 5 expected ranking)
     :recall-10 (recall-at 10 expected ranking)
     :mrr-10 (mrr-at 10 expected ranking)
     :leaks (vec (filter shown must-not-docs))
     :degraded (:degraded res)
     :stage-ms (into {} (keep (fn [[k v]] (when-let [ms (:ms v)] [k ms]))) (:stages res))}))

(defn- summarize [rows]
  (let [scored (remove (comp nil? :recall-5) rows)
        stages (distinct (mapcat (comp keys :stage-ms) rows))]
    {:questions (count rows)
     :scored (count scored)
     :recall-5 (mean (map :recall-5 scored))
     :recall-10 (mean (map :recall-10 scored))
     :mrr-10 (mean (map :mrr-10 scored))
     :leaks (reduce + (map (comp count :leaks) rows))
     :degraded (count (filter (comp seq :degraded) rows))
     :stage-ms (into (sorted-map)
                     (for [s stages :let [xs (keep #(get-in % [:stage-ms s]) rows)]]
                       [s {:p50 (percentile 0.5 xs)
                           :p95 (percentile 0.95 xs)}]))}))

(defn run-eval
  "Evaluate `questions` for the named `variant-names`. `deps` is the
   pipeline deps map; `principals` maps user names to principals;
   `index-conn` is read for section ids and the longest-chunk statistic."
  [deps index-conn {:keys [questions principals variant-names opts]}]
  (let [per-variant (into (array-map)
                          (for [v variant-names]
                            (let [rows (mapv (fn [q]
                                               (let [p (or (principals (:user q))
                                                           (throw (ex-info (str "unknown user " (:user q)) {:q (:id q)})))]
                                                 (assoc (run-one deps (d/db index-conn) p q (merge opts (variants v)))
                                                        :id (:id q))))
                                             questions)]
                              [v {:summary (summarize rows)
                                  :rows rows}])))]
    {:at (str (LocalDateTime/now))
     :variants per-variant
     :acl-leaks (reduce + (map (comp :leaks :summary) (vals per-variant)))
     :max-chunk-tokens (or (d/q '[:find (max ?t) . :where [_ :chunk/tokens ?t]] (d/db index-conn)) 0)}))

;; --- output ---

(defn- fmt [x] (if (number? x) (format "%.3f" (double x)) "-"))

(defn table
  "Terminal table of the per-variant summaries."
  [{:keys [acl-leaks max-chunk-tokens]
    :as report}]
  (let [header (format "%-22s %9s %9s %8s %6s %9s" "variant" "recall@5" "recall@10" "MRR@10" "leaks" "degraded")
        lines (for [[v {:keys [summary]}] (:variants report)]
                (format "%-22s %9s %9s %8s %6d %9d" v (fmt (:recall-5 summary)) (fmt (:recall-10 summary))
                        (fmt (:mrr-10 summary)) (:leaks summary) (:degraded summary)))
        stage-lines (for [[v {:keys [summary]}] (:variants report)]
                      (str "  " v ": "
                           (str/join ", " (for [[s {:keys [p50 p95]}] (:stage-ms summary)]
                                            (str (name s) " p50 " p50 "/p95 " p95 " ms")))))]
    (str/join "\n" (concat [header (apply str (repeat (count header) "-"))] lines
                           ["" (str "ACL leaks: " acl-leaks "   longest chunk: " max-chunk-tokens " est. tokens")
                            "stage latency:"]
                           stage-lines))))

(defn write-results!
  "Write the full report to <dir>/<timestamp>.edn; returns the path."
  [dir report]
  (let [f (io/file dir (str (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")) ".edn"))]
    (io/make-parents f)
    (spit f (with-out-str (pprint/pprint report)))
    (str f)))

(defn load-principals
  "eval/users.edn → user name → principal."
  [path]
  (into {} (map (fn [[u m]] [u (assoc m :username u)])) (edn/read-string (slurp path))))
