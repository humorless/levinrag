(ns replware.levinrag.retrieval.context
  "Context expansion and packing (SPEC.md §9.7).

   1. Add each selected chunk's same-section ±1 neighbors (ACL-filtered by
      the Retriever through `neighbors-fn`).
   2. Merge chunks with consecutive ordinals in the same section into one
      passage. Chunks are exact spans of the source file, so the overlap
      a chunk shares with its predecessor is cut off by char offset — no
      sentence appears twice.
   3. Order passages by their best selected chunk; while the estimated
      total exceeds :max-tokens, drop neighbors first (those of the
      weakest selected chunk first), selected chunks last.
   4. Number passages [1]..[n]."
  (:require [replware.levinrag.ingest.tokens :as tokens]))

(def default-opts {:max-tokens 6000})

(defn- pool
  "Selected chunks plus neighbors, each with :role and :priority (the
   index of the selected chunk it belongs to; lower = better)."
  [selected neighbors-fn]
  (let [sel (map-indexed (fn [i c] (assoc c :role :selected :priority i)) selected)
        sel-ids (set (map :chunk/id sel))
        nbrs (for [s sel
                   n (neighbors-fn (:chunk/id s))
                   :when (not (sel-ids (:chunk/id n)))]
               (assoc n :role :neighbor :priority (:priority s)))]
    (vals (reduce (fn [m c] (update m (:chunk/id c) #(if % (update % :priority min (:priority c)) c)))
                  (into {} (map (juxt :chunk/id identity)) sel)
                  nbrs))))

(defn- runs
  "Chunks grouped into runs of consecutive ordinals within one section."
  [chunks]
  (->> chunks
       (group-by (juxt :doc/path :section/id))
       vals
       (mapcat (fn [cs]
                 (let [cs (sort-by :chunk/ordinal cs)]
                   (reduce (fn [acc c]
                             (let [run (peek acc)]
                               (if (and run (= (inc (:chunk/ordinal (peek run))) (:chunk/ordinal c)))
                                 (conj (pop acc) (conj run c))
                                 (conj acc [c]))))
                           [] cs))))))

(defn- merged-text
  "Concatenate a run's texts, dropping each chunk's overlap with the
   previous one (by char offset); a gap between chunks becomes a blank line."
  [run]
  (:text (reduce (fn [{:keys [text end]} c]
                   (let [start (:chunk/char-start c)
                         t (:chunk/text c)]
                     {:text (cond
                              (nil? end) t
                              (< start end) (str text (subs t (min (count t) (- end start))))
                              :else (str text "\n\n" t))
                      :end (max (or end 0) (:chunk/char-end c))}))
                 {:text nil
                  :end nil}
                 run)))

(defn- passage [run]
  (let [text (merged-text run)
        best (reduce min (map :priority (filter #(= :selected (:role %)) run)))]
    {:doc/path (:doc/path (first run))
     :doc/title (:doc/title (first run))
     :section/trail (:section/trail (first run))
     :chunk-ids (mapv :chunk/id run)
     :selected-ids (vec (keep #(when (= :selected (:role %)) (:chunk/id %)) run))
     :char-range [(:chunk/char-start (first run)) (reduce max (map :chunk/char-end run))]
     :text text
     :tokens (tokens/estimate-tokens text)
     :priority best}))

(defn- passages
  "Passages for a chunk pool. A run holding only neighbors (its selected
   chunk was dropped) is not a passage."
  [chunks]
  (->> (runs chunks)
       (filter (fn [run] (some #(= :selected (:role %)) run)))
       (map passage)
       (sort-by (juxt :priority :doc/path (comp first :char-range)))))

(defn- drop-order
  "Chunks in the order they are sacrificed: neighbors before selected,
   weaker (higher :priority) first."
  [chunks]
  (sort-by (fn [c] [(if (= :neighbor (:role c)) 0 1) (- (:priority c)) (:chunk/id c)]) chunks))

(defn pack
  "Passages for `selected` chunks (best-first chunk maps from the
   Retriever). `neighbors-fn` maps a chunk id to its ACL-filtered
   neighbors. Returns {:passages [{:n ..} ...] :tokens n :dropped [ids]}."
  [selected neighbors-fn opts]
  (let [{:keys [max-tokens]} (merge default-opts opts)
        total #(reduce + (map :tokens %))]
    (loop [chunks (pool selected neighbors-fn)
           dropped []]
      (let [ps (passages chunks)]
        (if (or (<= (total ps) max-tokens) (empty? chunks))
          {:passages (vec (map-indexed (fn [i p] (-> p (assoc :n (inc i)) (dissoc :priority))) ps))
           :tokens (total ps)
           :dropped dropped}
          (let [victim (first (drop-order chunks))]
            (recur (remove #(= (:chunk/id victim) (:chunk/id %)) chunks)
                   (conj dropped (:chunk/id victim)))))))))
