(ns hybridrag.retrieval.fusion
  "Reciprocal rank fusion (SPEC.md §9.4).")

(defn rrf
  "Fuse best-first lists of chunk ids by reciprocal rank; an id missing from
   a list gets no contribution from it. Returns [[id score] ...] best-first.
   Ties go to the better rank in the first list (lexical), then the next
   list, then the id — so the order is fully deterministic."
  [k & ranked-lists]
  (let [rank-maps (mapv #(into {} (map-indexed (fn [i id] [id i])) %) ranked-lists)
        scores (reduce (fn [m [id s]] (update m id (fnil + 0.0) s))
                       {}
                       (mapcat (fn [ids] (map-indexed (fn [i id] [id (/ 1.0 (+ k i 1))]) ids))
                               ranked-lists))]
    (->> scores
         (sort-by (fn [[id s]]
                    (into [(- s)] (conj (mapv #(get % id Long/MAX_VALUE) rank-maps) id))))
         vec)))

(defn fuse
  "Fused candidates from channel results {:lexical res :semantic res}
   (either may be absent): [{:chunk/id :doc/path :channels {ch {:rank :score}}
   :rrf s} ...] best-first. Lexical comes first for tie-breaking."
  [k channel-results]
  (let [chs (filterv channel-results [:lexical :semantic])
        by-ch (into {} (for [ch chs]
                         [ch (into {} (map (juxt :chunk/id identity))
                                   (:candidates (channel-results ch)))]))]
    (mapv (fn [[id s]]
            (let [hits (keep (fn [ch] (when-let [c (get-in by-ch [ch id])] [ch c])) chs)]
              {:chunk/id id
               :doc/path (:doc/path (second (first hits)))
               :channels (into {} (for [[ch c] hits] [ch (select-keys c [:rank :score])]))
               :rrf s}))
          (apply rrf k (for [ch chs] (map :chunk/id (:candidates (channel-results ch))))))))
