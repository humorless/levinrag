(ns hybridrag.retrieval.rerank
  "Rerank stage with degradation (SPEC.md §9.6, D7): any rerank failure
   keeps the incoming order (fused candidates, then graph candidates) and
   is reported, never thrown."
  (:require [clojure.tools.logging :as log]))

(def default-opts {:max-chars 1500})

(defn- truncate [^String s n]
  (if (> (count s) n) (subs s 0 n) s))

(defn- valid-results?
  "Every result has an in-range integer index (no repeats) and a numeric score."
  [results n]
  (and (sequential? results)
       (every? #(and (integer? (:index %)) (< -1 (:index %) n) (number? (:relevance-score %))) results)
       (apply distinct? -1 (map :index results))))

(defn rerank
  "Rerank `candidates` (each with :chunk/index-text; fused ones first, then
   graph) against `query` using (rerank-fn query docs top-n) →
   [{:index :relevance-score} ...].

   Returns {:ranked [candidate + :rerank score, best-first] :failed? bool
   :error msg :ms n}. Candidates the reranker did not score keep their
   relative order after the scored ones, with :rerank nil. On failure the
   input order is kept and every :rerank is nil."
  [rerank-fn query candidates opts]
  (let [{:keys [max-chars]} (merge default-opts opts)
        t0 (System/nanoTime)
        ms #(quot (- (System/nanoTime) t0) 1000000)
        n (count candidates)]
    (if (zero? n)
      {:ranked []
       :failed? false
       :ms 0}
      (try
        (let [results (rerank-fn query (mapv #(truncate (:chunk/index-text %) max-chars) candidates) n)]
          (when-not (valid-results? results n)
            (throw (ex-info "rerank returned malformed results" {:results (take 3 results)})))
          (let [scores (into {} (map (juxt :index :relevance-score)) results)
                scored (->> (range n)
                            (filter scores)
                            (sort-by (fn [i] [(- (scores i)) i])))
                unscored (remove scores (range n))]
            {:ranked (mapv #(assoc (nth candidates %) :rerank (scores %)) (concat scored unscored))
             :failed? false
             :ms (ms)}))
        (catch Exception e
          (log/warn "[RERANK] failed, keeping fused order:" (ex-message e))
          {:ranked (mapv #(assoc % :rerank nil) candidates)
           :failed? true
           :error (ex-message e)
           :ms (ms)})))))
