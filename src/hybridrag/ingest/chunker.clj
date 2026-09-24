(ns hybridrag.ingest.chunker
  "Section-aware chunker (SPEC.md §7.3 steps 1–5) and contextual header
   (§7.4).

   Works on the section list from hybridrag.ingest.markdown. Every chunk
   is a contiguous span of the original string, so
   (subs md char-start char-end) = :text, overlap included. Chunks never
   cross a section.

   Cut points, the only places a chunk may start or a block may be split:
   block starts; inside prose blocks, after 。！？； / after .!? followed
   by whitespace / at a line break; inside fenced code and tables, at line
   breaks only. A piece still over :max-tokens is hard-cut and its chunk
   is marked :hard-cut?."
  (:require [clojure.string :as str]
            [hybridrag.ingest.tokens :as tokens]))

(def default-config
  {:target-tokens 350
   :max-tokens 500
   :min-tokens 60
   :overlap-tokens 60})

(def ^:private prose-cut #"[。！？；]|[.!?](?=\s)|\n")
(def ^:private line-cut #"\n")

(defn- skip-ws [^String s ^long i ^long end]
  (if (and (< i end) (Character/isWhitespace (.charAt s (int i))))
    (recur s (inc i) end)
    i))

(defn- trim-end [^String s ^long start ^long i]
  (if (and (> i start) (Character/isWhitespace (.charAt s (int (dec i)))))
    (recur s start (dec i))
    i))

(defn- est [^String s start end]
  (tokens/estimate-tokens (subs s start end)))

(defn- block-cuts
  "Cut points of one block: its start plus the positions following each
   in-block boundary (leading whitespace skipped)."
  [^String s {:keys [char-start char-end]
              :as block}]
  (let [m (re-matcher (if (#{:code :table} (:type block)) line-cut prose-cut)
                      (subs s char-start char-end))]
    (loop [acc [char-start]]
      (if (.find m)
        (let [c (skip-ws s (+ char-start (.end m)) char-end)]
          (recur (cond-> acc (< c char-end) (conj c))))
        (distinct acc)))))

(defn- hard-cut
  "Split [start, end) into pieces of at most `max-tokens`."
  [^String s start end max-tokens]
  (loop [a start, acc []]
    (if (>= a end)
      acc
      (let [e (tokens/fit-end s a end max-tokens)
            ;; guarantee progress even if a single code point is over budget
            e (if (= e a) (+ a (Character/charCount (.codePointAt s (int a)))) e)]
        (recur (skip-ws s e end)
               (conj acc {:start a
                          :end (trim-end s a e)
                          :hard-cut? true}))))))

(defn- block-units
  "A block as packing units: the whole block when it fits in max-tokens,
   otherwise its cut-point segments, hard-cut when still too big."
  [^String s {:keys [char-start char-end]
              :as block} max-tokens]
  (if (<= (est s char-start char-end) max-tokens)
    [{:start char-start
      :end char-end}]
    (let [cuts (block-cuts s block)]
      (mapcat (fn [a b]
                (let [b (trim-end s a b)]
                  (if (<= (est s a b) max-tokens)
                    [{:start a
                      :end b}]
                    (hard-cut s a b max-tokens))))
              cuts
              (concat (rest cuts) [char-end])))))

(defn- overlap-start
  "Start of the next chunk given the previous chunk and the first unit of
   the next: the earliest cut point inside `prev` whose suffix of `prev`
   fits in `budget` tokens (step 4, whole sentences only), or the unit's
   own start when none fits."
  [^String s cuts prev unit budget]
  (or (some (fn [c]
              (when (and (< (:start prev) c (:end prev))
                         (<= (est s c (:end prev)) budget))
                c))
            cuts)
      (:start unit)))

(defn- finish [^String s {:keys [start end hard-cut?]}]
  (cond-> {:char-start start
           :char-end end
           :text (subs s start end)
           :tokens (est s start end)}
    hard-cut? (assoc :hard-cut? true)))

(defn- pack
  "Greedy packing of units into chunks (steps 2 and 4)."
  [^String s units cuts {:keys [target-tokens max-tokens overlap-tokens]}]
  (loop [[u & more :as units] units, cur nil, prev nil, chunks []]
    (cond
      (empty? units)
      (cond-> chunks cur (conj cur))

      ;; open a chunk, carrying overlap from the previous one if any
      (nil? cur)
      (let [start (if prev
                    (overlap-start s cuts prev u
                                   (min overlap-tokens
                                        (- max-tokens (est s (:start u) (:end u)))))
                    (:start u))
            cur (assoc u :start start :fresh (:start u))]
        (if (>= (est s start (:end u)) target-tokens)
          (recur more nil cur (conj chunks cur))
          (recur more cur prev chunks)))

      ;; adding u would exceed max: close cur, retry u in a new chunk
      (> (est s (:start cur) (:end u)) max-tokens)
      (recur units nil cur (conj chunks cur))

      :else
      (let [cur (assoc cur :end (:end u) :hard-cut? (or (:hard-cut? cur) (:hard-cut? u)))]
        (if (>= (est s (:start cur) (:end cur)) target-tokens)
          (recur more nil cur (conj chunks cur))
          (recur more cur prev chunks))))))

(defn- merge-small-tail
  "Step 5: fold a last chunk whose own content (overlap excluded) is under
   min-tokens into the previous one when the merged span still fits in
   max-tokens."
  [^String s {:keys [min-tokens max-tokens]} chunks]
  (let [n (count chunks)]
    (if (< n 2)
      chunks
      (let [prev (nth chunks (- n 2))
            last-c (peek chunks)]
        (if (and (< (est s (:fresh last-c) (:end last-c)) min-tokens)
                 (<= (est s (:start prev) (:end last-c)) max-tokens))
          (conj (subvec chunks 0 (- n 2))
                (assoc prev :end (:end last-c)
                       :hard-cut? (or (:hard-cut? prev) (:hard-cut? last-c))))
          chunks)))))

(defn chunk-section
  "Chunks of one section: [{:char-start :char-end :text :tokens
   :hard-cut?} ...]. A section without blocks yields no chunks."
  ([^String s section] (chunk-section s section default-config))
  ([^String s {:keys [blocks]} config]
   (let [config (merge default-config config)
         units (vec (mapcat #(block-units s % (:max-tokens config)) blocks))
         cuts (sort (mapcat #(block-cuts s %) blocks))]
     (->> (pack s units cuts config)
          (merge-small-tail s config)
          (mapv (partial finish s))))))

(defn chunk-doc
  "Chunks of a whole parsed document, in order. Each chunk also gets
   :ordinal (0-based within the doc) and :section-index (index into
   `sections`)."
  ([^String s sections] (chunk-doc s sections default-config))
  ([^String s sections config]
   (->> sections
        (map-indexed (fn [i sec]
                       (map #(assoc % :section-index i) (chunk-section s sec config))))
        (apply concat)
        (map-indexed #(assoc %2 :ordinal %1))
        vec)))

(defn index-text
  "`:chunk/index-text` = contextual header + blank line + chunk text
   (SPEC.md §7.4). The 章節 line is omitted for level-0 content, which
   has no heading trail."
  [doc-title trail text]
  (str "文件：" doc-title "\n"
       (when (seq trail) (str "章節：" (str/join " > " trail) "\n"))
       "\n"
       text))
