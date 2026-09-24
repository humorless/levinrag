(ns hybridrag.search.analyzer
  "CJK-aware fulltext analyzer (SPEC.md §8.1), used for both indexing and
   querying (§8.2). Registered as a Datalevin UDF; it is runtime state and
   must be supplied on every open of index.dtlv (hybridrag.db.index-conn).

   1. NFKC + lower-case per code point; offsets point into the ORIGINAL
      string.
   2. Runs: CJK (Han, kana, Hangul); ASCII [a-z0-9] with internal - _ . /
      connectors; anything else separates.
   3. CJK run → overlapping bigrams (a 1-char run → that char).
   4. ASCII run → the whole run, plus its connector-separated parts of
      length ≥ 2 when it has connectors.
   5. Positions follow output order.

   Output: [[term position offset] ...], the shape of
   datalevin.analyzer/en-analyzer."
  (:require [clojure.string :as str])
  (:import [java.lang Character$UnicodeScript]
           [java.text Normalizer Normalizer$Form]))

(def udf-descriptor
  {:udf/lang :clojure
   :udf/kind :analyzer
   :udf/id :cjk-analyzer})

(defn- cjk? [^long cp]
  (let [script (Character$UnicodeScript/of (int cp))]
    (or (= script Character$UnicodeScript/HAN)
        (= script Character$UnicodeScript/HIRAGANA)
        (= script Character$UnicodeScript/KATAKANA)
        (= script Character$UnicodeScript/HANGUL))))

(defn- normalized-chars
  "[[cp original-offset] ...] after per-code-point NFKC + lower-case."
  [^String s]
  (loop [i 0, acc (transient [])]
    (if (>= i (count s))
      (persistent! acc)
      (let [cp (.codePointAt s (int i))
            n (Character/charCount cp)
            norm (-> (Normalizer/normalize (String. (Character/toChars cp)) Normalizer$Form/NFKC)
                     str/lower-case)]
        (recur (+ i n)
               (reduce (fn [a c] (conj! a [c i])) acc (.toArray (.codePoints ^String norm))))))))

(defn- ascii-alnum? [^long cp]
  (or (<= (int \a) cp (int \z)) (<= (int \0) cp (int \9))))

(def ^:private connector? #{(int \-) (int \_) (int \.) (int \/)})

(defn- runs
  "Split into [:cjk|:ascii [[cp offset] ...]] runs. ASCII connectors only
   stay inside a run when followed by another alphanumeric."
  [cs]
  (let [cs (vec cs)
        n (count cs)]
    (loop [i 0, acc []]
      (if (>= i n)
        acc
        (let [[cp] (cs i)]
          (cond
            (cjk? cp)
            (let [j (or (some #(when-not (cjk? (first (cs %))) %) (range i n)) n)]
              (recur j (conj acc [:cjk (subvec cs i j)])))

            (ascii-alnum? cp)
            (let [j (loop [j (inc i)]
                      (cond
                        (>= j n) j
                        (ascii-alnum? (first (cs j))) (recur (inc j))
                        (and (connector? (first (cs j)))
                             (< (inc j) n)
                             (ascii-alnum? (first (cs (inc j))))) (recur (+ j 2))
                        :else j))]
              (recur j (conj acc [:ascii (subvec cs i j)])))

            :else (recur (inc i) acc)))))))

(defn- cps->str [cps]
  (let [sb (StringBuilder.)]
    (doseq [[cp] cps] (.appendCodePoint sb (int cp)))
    (str sb)))

(defn- run-terms
  "[[term offset] ...] for one run."
  [[kind cps]]
  (case kind
    :cjk (if (= 1 (count cps))
           [[(cps->str cps) (second (first cps))]]
           (for [[a b] (partition 2 1 cps)]
             [(cps->str [a b]) (second a)]))
    :ascii (let [whole (cps->str cps)
                 start (second (first cps))
                 parts (when (some #(connector? (first %)) cps)
                         (->> (partition-by #(boolean (connector? (first %))) cps)
                              (remove #(connector? (first (first %))))
                              (filter #(>= (count %) 2))
                              (map (fn [p] [(cps->str p) (second (first p))]))))]
             (cons [whole start] parts))))

(defn analyze
  "Analyze `s` into [[term position offset] ...] (SPEC.md §8.1)."
  [^String s]
  (->> (runs (normalized-chars (or s "")))
       (mapcat run-terms)
       (map-indexed (fn [pos [term offset]] [term pos offset]))
       vec))
