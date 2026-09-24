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

(defn- run-one [deps principal {:keys [query expected-docs must-not-docs]} opts]
  (let [res (pipeline/search deps principal query opts)
        docs (ranked-docs (:candidates res))
        shown (into (set docs) (map :doc/path (:passages res)))]
    {:docs (vec (take 10 docs))
     :recall-5 (recall-at 5 expected-docs docs)
     :recall-10 (recall-at 10 expected-docs docs)
     :mrr-10 (mrr-at 10 expected-docs docs)
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
   `index-conn` is only read for the longest-chunk statistic."
  [deps index-conn {:keys [questions principals variant-names opts]}]
  (let [per-variant (into (array-map)
                          (for [v variant-names]
                            (let [rows (mapv (fn [q]
                                               (let [p (or (principals (:user q))
                                                           (throw (ex-info (str "unknown user " (:user q)) {:q (:id q)})))]
                                                 (assoc (run-one deps p q (merge opts (variants v))) :id (:id q))))
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
