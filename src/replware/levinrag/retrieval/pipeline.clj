(ns replware.levinrag.retrieval.pipeline
  "Search pipeline (SPEC.md §9.1): channels → RRF → graph → rerank →
   select → context. Storage-independent; ACL lives in the Retriever.

   Variants for eval (§15.2) are expressed with opts:
     :channels #{:lexical :semantic}, :rerank? bool, :graph? bool.
   The returned :candidates are always in final order (reranked, or
   fused followed by graph when rerank is off or failed)."
  (:require [replware.levinrag.retrieval.context :as context]
            [replware.levinrag.retrieval.fusion :as fusion]
            [replware.levinrag.retrieval.graph :as graph]
            [replware.levinrag.retrieval.protocol :as p]
            [replware.levinrag.retrieval.rerank :as rerank]))

(def default-opts
  "SPEC.md §5 defaults."
  {:channel-k 50
   :overfetch 4
   :rrf-k 60
   :rerank-input 40
   :graph-max 10
   :final-k 8
   :rerank-min-score nil
   :max-tokens 6000
   :max-chars 1500
   :channels #{:lexical :semantic}
   :rerank? true
   :graph? true})

(def ^:private trace-top 20)

(defmacro ^:private timed
  "[result ms]"
  [& body]
  `(let [t0# (System/nanoTime)
         r# (do ~@body)]
     [r# (quot (- (System/nanoTime) t0#) 1000000)]))

(defn- channel-stage [res ms]
  {:ms ms
   :raw-hits (:raw-hits res)
   :after-acl (:after-acl res)
   :top (mapv (juxt :chunk/id :score) (take trace-top (:candidates res)))})

(defn- select-ids
  "Chunk ids that make the final cut: top final-k, optionally above
   rerank-min-score (ignored when rerank did not run)."
  [ranked reranked? {:keys [final-k rerank-min-score]}]
  (->> ranked
       (filter #(or (not reranked?) (nil? rerank-min-score)
                    (and (:rerank %) (>= (:rerank %) rerank-min-score))))
       (take final-k)
       (map :chunk/id)
       set))

(defn search
  "Run the pipeline for `principal`. `deps`: {:retriever :rerank-fn}.
   Returns {:passages :candidates :degraded #{..} :flags #{..} :stages {..}}."
  [{:keys [retriever rerank-fn]} principal query opts]
  (let [{:keys [channels rerank? graph? rrf-k rerank-input]
         :as opts} (merge default-opts opts)
        ch-opts (select-keys opts [:channel-k :overfetch])
        results (into {} (for [ch [:lexical :semantic]
                               :when (contains? channels ch)]
                           [ch (timed (p/channel retriever principal ch query ch-opts))]))
        channel-results (update-vals results first)
        [fused fusion-ms] (timed (vec (take rerank-input (fusion/fuse rrf-k channel-results))))
        [graph-cands graph-ms] (timed (if graph?
                                        (graph/candidates retriever principal fused channel-results
                                                          (select-keys opts [:graph-max]))
                                        []))
        pool (into fused graph-cands)
        by-id (into {} (map (juxt :chunk/id identity)) (p/chunks retriever principal (map :chunk/id pool)))
        pool (filterv #(by-id (:chunk/id %)) pool)
        with-text (mapv #(merge % (select-keys (by-id (:chunk/id %)) [:chunk/index-text])) pool)
        {:keys [ranked failed?]
         :as rr} (if rerank?
                   (rerank/rerank rerank-fn query with-text (select-keys opts [:max-chars]))
                   {:ranked with-text
                    :failed? false
                    :ms 0})
        reranked? (and rerank? (not failed?))
        chosen (select-ids ranked reranked? opts)
        selected (filterv #(chosen (:chunk/id %)) ranked)
        [ctx context-ms] (timed (context/pack (mapv #(assoc (by-id (:chunk/id %)) :rerank (:rerank %)) selected)
                                              #(p/neighbors retriever principal % {})
                                              (select-keys opts [:max-tokens])))
        flags (cond-> #{}
                (some :starved? (vals channel-results)) (conj :acl-starvation)
                failed? (conj :rerank-failed))]
    {:passages (:passages ctx)
     :candidates (mapv (fn [c]
                         (cond-> {:chunk/id (:chunk/id c)
                                  :doc/path (:doc/path c)
                                  :channels (:channels c)
                                  :selected? (contains? chosen (:chunk/id c))}
                           (:rrf c) (assoc :rrf (:rrf c))
                           (:rerank c) (assoc :rerank (:rerank c))))
                       ranked)
     :degraded (if failed? #{:rerank-failed} #{})
     :flags flags
     :stages (cond-> {:fusion {:ms fusion-ms
                               :top (mapv (juxt :chunk/id :rrf) (take trace-top fused))}
                      :graph {:ms graph-ms
                              :added (mapv :chunk/id graph-cands)}
                      :context {:passages (count (:passages ctx))
                                :tokens (:tokens ctx)
                                :dropped (:dropped ctx)
                                :ms context-ms}
                      :flags flags}
               (:lexical results) (assoc :lexical (apply channel-stage (:lexical results)))
               (:semantic results) (assoc :semantic (apply channel-stage (:semantic results)))
               rerank? (assoc :rerank (cond-> {:ms (:ms rr)
                                               :failed? failed?
                                               :scores (mapv (juxt :chunk/id :rerank) (filter :rerank ranked))}
                                        failed? (assoc :error (:error rr)))))}))
