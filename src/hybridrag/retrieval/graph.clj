(ns hybridrag.retrieval.graph
  "Graph channel, MVP version (SPEC.md §9.5): 1-hop linked docs of the top
   fused docs contribute extra candidates that skip RRF and go straight to
   rerank. ACL comes from the Retriever (linked-docs, chunks)."
  (:require [hybridrag.retrieval.protocol :as p]))

(def default-opts
  {:graph-max 10
   :top-docs 5
   :per-doc 2})

(defn- best-ranks
  "chunk-id → best (lowest) rank over the channels' extended lists."
  [channel-results]
  (reduce (fn [m c] (update m (:chunk/id c) (fnil min Long/MAX_VALUE) (:rank c)))
          {}
          (mapcat :extended (vals channel-results))))

(defn candidates
  "Graph candidates for `fused` (fused candidates, best-first):
   [{:chunk/id :doc/path :channels {:graph {:via [src-doc ...]}}} ...].
   At most :per-doc chunks per linked doc — the best-ranked ones from the
   channels' extended lists, else the doc's first chunk — and at most
   :graph-max in total. Never repeats a fused doc or chunk."
  [retriever principal fused channel-results opts]
  (let [{:keys [graph-max top-docs per-doc]} (merge default-opts opts)
        src-docs (vec (take top-docs (distinct (map :doc/path fused))))
        fused-docs (set (map :doc/path fused))
        ;; one lookup per source doc so each linked doc knows where it came from
        via (reduce (fn [m src]
                      (reduce #(update %1 %2 (fnil conj []) src) m
                              (p/linked-docs retriever principal [src])))
                    {} src-docs)
        linked (remove fused-docs (keys via))
        ranks (best-ranks channel-results)
        by-doc (group-by (fn [id] (subs id 0 (.lastIndexOf ^String id "::"))) (keys ranks))
        picks (fn [doc]
                (let [hits (sort-by ranks (get by-doc doc))]
                  (if (seq hits)
                    (take per-doc hits)
                    [(str doc "::0")])))
        ;; docs with channel hits first (by their best rank), then the rest by path
        ordered (sort-by (fn [doc] [(reduce min Long/MAX_VALUE (map ranks (get by-doc doc))) doc]) linked)
        wanted (take graph-max (mapcat picks ordered))]
    (mapv (fn [c] {:chunk/id (:chunk/id c)
                   :doc/path (:doc/path c)
                   :channels {:graph {:via (get via (:doc/path c))}}})
          (p/chunks retriever principal wanted))))
