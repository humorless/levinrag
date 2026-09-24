(ns hybridrag.ingest.chunker-test
  "Chunker tests (SPEC.md §7.3, T1.3 AC): no chunk crosses a section; no
   chunk over max (hard cuts marked); code blocks and tables are not cut
   mid-sentence; overlap is whole sentences within budget; char range
   restores the original text."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hybridrag.ingest.chunker :as ch]
            [hybridrag.ingest.markdown :as md]
            [hybridrag.ingest.tokens :as tokens]))

(def ^:private small
  {:target-tokens 100
   :max-tokens 150
   :min-tokens 20
   :overlap-tokens 30})

(defn- chunks [s cfg]
  (let [{:keys [sections]} (md/parse-markdown s)]
    [sections (ch/chunk-doc s sections cfg)]))

(defn- sentences [n]
  (apply str (map #(str "第" % "條員工每年享有特休假。") (range n))))

(def ^:private mixed-doc
  (str "# 請假\n" (sentences 40) "\n\n"
       "## 程式\n```clj\n" (apply str (repeat 40 "(defn f [x] (inc x))\n")) "```\n\n"
       "## 表格\n| 料號 | 說明 |\n|---|---|\n"
       (apply str (map #(str "| SKU-A" % " | 零件 " % " |\n") (range 60)))
       "\n# Other\nShort tail.\n"))

(deftest test-invariants
  (let [[sections cs] (chunks mixed-doc small)]
    (testing "char range restores the original text"
      (doseq [c cs]
        (is (= (:text c) (subs mixed-doc (:char-start c) (:char-end c))))))
    (testing "no chunk crosses its section"
      (doseq [c cs
              :let [sec (nth sections (:section-index c))]]
        (is (<= (:char-start sec) (:char-start c) (:char-end c) (:char-end sec)))))
    (testing "no chunk exceeds max"
      (doseq [c cs]
        (is (<= (:tokens c) 150))
        (is (= (:tokens c) (tokens/estimate-tokens (:text c))))))
    (testing "ordinals are consecutive and every section with content is covered"
      (is (= (range (count cs)) (map :ordinal cs)))
      (is (= #{0 1 2 3} (set (map :section-index cs)))))))

(deftest test-block-accumulation
  (let [s "# A\npara one.\n\npara two.\n\n- item\n- item2\n"
        [_ cs] (chunks s small)]
    (is (= 1 (count cs)))
    (is (= "para one.\n\npara two.\n\n- item\n- item2" (:text (first cs))))))

(deftest test-long-paragraph-splits-at-sentences
  (let [[_ cs] (chunks (str "# A\n" (sentences 40)) small)]
    (is (< 1 (count cs)))
    (doseq [c cs]
      (is (str/starts-with? (:text c) "第"))
      (is (str/ends-with? (:text c) "。")))))

(deftest test-latin-sentence-boundary
  (let [s (str "# A\n" (str/join " " (repeat 120 "The quick fox jumps. It runs!")))
        [_ cs] (chunks s small)]
    (is (< 1 (count cs)))
    (doseq [c (butlast cs)]
      (is (re-find #"[.!]$" (:text c))))))

(deftest test-code-block-kept-whole-when-it-fits
  (let [code (str "```clj\n" (apply str (repeat 5 "(defn f [x] (inc x))\n")) "```")
        s (str "# A\n" (sentences 7) "\n\n" code "\n")
        [_ cs] (chunks s small)]
    (is (some #(str/includes? (:text %) code) cs)
        "the whole fenced block lands in one chunk")))

(deftest test-oversized-code-and-table-cut-at-line-ends-only
  (let [[_ cs] (chunks mixed-doc small)
        code-start (str/index-of mixed-doc "```clj")
        table-start (str/index-of mixed-doc "| 料號")
        in-code-or-table? (fn [i] (or (< code-start i (str/index-of mixed-doc "```\n\n##"))
                                      (< table-start i (str/index-of mixed-doc "\n\n# Other"))))]
    (doseq [c cs
            :let [a (:char-start c) b (:char-end c)]]
      (when (in-code-or-table? a)
        (is (= \newline (.charAt ^String mixed-doc (dec a))) "starts at a line start"))
      (when (in-code-or-table? b)
        (is (= \newline (.charAt ^String mixed-doc b)) "ends at a line end")))))

(deftest test-overlap
  (let [[_ cs] (chunks (str "# A\n" (sentences 40)) small)]
    (doseq [[p n] (partition 2 1 cs)
            :let [ov (subs (:text p) (- (:char-start n) (:char-start p)))]]
      (testing "the next chunk starts inside the previous one"
        (is (< (:char-start p) (:char-start n) (:char-end p))))
      (testing "overlap is whole sentences within the budget"
        (is (str/starts-with? ov "第"))
        (is (str/ends-with? ov "。"))
        (is (<= (tokens/estimate-tokens ov) 30)))))
  (testing "no overlap across sections"
    (let [[_ cs] (chunks mixed-doc small)]
      (doseq [[p n] (partition 2 1 cs)
              :when (not= (:section-index p) (:section-index n))]
        (is (<= (:char-end p) (:char-start n)))))))

(deftest test-no-overlap-when-last-sentence-exceeds-budget
  (let [long-sentence (str (apply str (repeat 40 "字")) "。")
        [_ cs] (chunks (str "# A\n" (apply str (repeat 6 long-sentence))) small)]
    (is (< 1 (count cs)))
    (doseq [[p n] (partition 2 1 cs)]
      (is (<= (:char-end p) (:char-start n))))))

(deftest test-hard-cut
  (let [s (str "# A\n" (apply str (repeat 400 "字")))
        [_ cs] (chunks s small)]
    (is (< 1 (count cs)))
    (is (every? :hard-cut? cs))
    (is (every? #(<= (:tokens %) 150) cs))
    (is (= (apply str (repeat 400 "字")) (apply str (map :text cs)))
        "hard-cut pieces carry no overlap and lose nothing")))

(deftest test-small-tail-merged
  (testing "a tail under min-tokens folds into the previous chunk"
    (let [s (str "# A\n" (sentences 8) "\n\n尾巴。")
          [_ cs] (chunks s small)]
      (is (= 1 (count cs)))
      (is (str/ends-with? (:text (peek cs)) "尾巴。"))))
  (testing "not merged when the result would exceed max"
    (let [s (str "# A\n" (apply str (repeat 150 "字")) "\n\n短。")
          [_ cs] (chunks s small)]
      (is (= 2 (count cs)))
      (is (= "短。" (:text (peek cs)))))))

(deftest test-heading-only-section-has-no-chunks
  (let [[_ cs] (chunks "# A\n# B\nbody" small)]
    (is (= [1] (map :section-index cs)))))

(deftest test-plain-text
  (let [s "第一段第一行\n第一段第二行\n\n\n第二段\n"
        {:keys [sections]} (md/parse-text s)
        cs (ch/chunk-doc s sections small)]
    (is (= 1 (count sections)))
    (is (= [["第一段第一行\n第一段第二行"] ["第二段"]]
           (map (fn [b] [(subs s (:char-start b) (:char-end b))]) (:blocks (first sections)))))
    (is (= ["第一段第一行\n第一段第二行\n\n\n第二段"] (map :text cs))))
  (is (= [] (:sections (md/parse-text "  \n\n")))))

(deftest test-index-text
  (is (= "文件：請假規定\n章節：請假規定 > 特休\n\n上述天數依年資計算。"
         (ch/index-text "請假規定" ["請假規定" "特休"] "上述天數依年資計算。")))
  (is (= "文件：Doc\n\nbody" (ch/index-text "Doc" [] "body"))))
