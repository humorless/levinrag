(ns replware.levinrag.ingest.markdown
  "Parse Markdown → flat, document-ordered section list (SPEC.md §7.3).

   Uses commonmark-java with block source spans. ATX and Setext headings
   both open a section; content before the first heading goes into an
   implicit level-0 section. The tree shape is carried by :trail (the
   chain of ancestor headings), which is what the chunker and the
   contextual header need.

   Offsets are Java string (UTF-16) indices into the original input,
   so (subs md char-start char-end) returns the raw text."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [org.commonmark.ext.front.matter YamlFrontMatterBlock YamlFrontMatterExtension]
           [org.commonmark.ext.gfm.tables TableBlock TableCell TablesExtension]
           [org.commonmark.node BlockQuote Code FencedCodeBlock Heading HtmlBlock
            IndentedCodeBlock Link ListBlock Node Paragraph SoftLineBreak Text ThematicBreak]
           [org.commonmark.parser IncludeSourceSpans Parser]))

(def ^Parser parser
  "commonmark parser with front matter, GFM tables and block source spans
   (shared with the document viewer)."
  (-> (Parser/builder)
      (.extensions [(YamlFrontMatterExtension/create) (TablesExtension/create)])
      (.includeSourceSpans IncludeSourceSpans/BLOCKS)
      .build))

(defn- children [^Node n]
  (take-while some? (iterate #(.getNext ^Node %) (.getFirstChild n))))

(defn- span [^Node n]
  (let [ss (.getSourceSpans n)
        s (first ss)
        e (last ss)]
    {:char-start (.getInputIndex s)
     :char-end (+ (.getInputIndex e) (.getLength e))}))

(defn- plain-text
  "Inline text of a node with markup stripped. With `code?` false, code
   spans become a space instead of their literal."
  ([n] (plain-text n true))
  ([^Node n code?]
   (cond
     (instance? Text n) (.getLiteral ^Text n)
     (instance? Code n) (if code? (.getLiteral ^Code n) " ")
     (instance? SoftLineBreak n) " "
     :else (apply str (map #(plain-text % code?) (children n))))))

(defn- block-type [^Node n]
  (condp instance? n
    Paragraph :paragraph
    FencedCodeBlock :code
    IndentedCodeBlock :code
    TableBlock :table
    ListBlock :list
    BlockQuote :blockquote
    HtmlBlock :html
    :other))

(defn- open-section [trail-stack ^Heading h]
  (let [level (.getLevel h)
        heading (str/trim (plain-text h))
        stack (conj (vec (take-while #(< (:level %) level) trail-stack))
                    {:level level
                     :heading heading})]
    [stack
     (merge {:level level
             :heading heading
             :trail (mapv :heading stack)
             :blocks []}
            (span h))]))

(defn- close-section [sections sec]
  (if sec
    (conj sections (cond-> sec
                     (seq (:blocks sec)) (assoc :char-end (:char-end (peek (:blocks sec))))))
    sections))

(defn- parse-sections [^Node root]
  (loop [[n & more :as nodes] (children root)
         stack []
         cur nil
         sections []]
    (cond
      (empty? nodes)
      (close-section sections cur)

      (or (instance? YamlFrontMatterBlock n) (instance? ThematicBreak n))
      (recur more stack cur sections)

      (instance? Heading n)
      (let [[stack' sec] (open-section stack n)]
        (recur more stack' sec (close-section sections cur)))

      :else
      (let [block (assoc (span n) :type (block-type n))
            cur (or cur {:level 0
                         :heading nil
                         :trail []
                         :blocks []
                         :char-start (:char-start block)})]
        (recur more stack (update cur :blocks conj block) sections)))))

;; --- Frontmatter parsing (simple YAML, MVP version) ---

(defn- parse-scalar
  "Parse a YAML-ish value. Reads it as EDN when the whole value is one EDN
   form (string, number, boolean, vector); bare words stay as the raw string.
   Symbols inside vectors become strings ([hr, policy] → [\"hr\" \"policy\"])."
  [^String v]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. v))
        x (try (edn/read {:eof ::eof} rdr) (catch Exception _ ::bad))]
    (cond
      (or (#{::bad ::eof} x) (symbol? x) (keyword? x)
          (not (str/blank? (slurp rdr)))) v
      (sequential? x) (mapv #(if (symbol? %) (str %) %) x)
      :else x)))

(defn parse-frontmatter
  "Extract YAML frontmatter from Markdown string. Returns a map or nil.

   Handles simple YAML:
     key: value
     key: [item1, item2]
     key: \"quoted string\"

   Per SPEC.md: frontmatter supports title, tags, read_groups, and
   any other fields are stored as EDN blob in :doc/frontmatter.

   Bare words stay strings; list items become strings
   (e.g., [hr, policy] → [\"hr\" \"policy\"])."
  [^String md]
  (when-let [[_ body] (re-find #"(?s)^---\r?\n(.*?)\r?\n?---" md)]
    (reduce
      (fn [m line]
        (if-let [[_ k v] (re-find #"^([^:]+):\s+(.*)$" line)]
          (assoc m (keyword (str/trim k)) (parse-scalar (str/trim v)))
          m))
      {}
      (str/split-lines body))))

;; --- Links (SPEC.md §7.5) ---

(defn- node-seq [^Node n]
  (mapcat #(cons % (node-seq %)) (children n)))

(defn- link-destinations
  "Destinations of all inline links, in document order. Code spans and
   code blocks never produce Link nodes."
  [^Node root]
  (->> (node-seq root)
       (filter #(instance? Link %))
       (mapv #(.getDestination ^Link %))))

(defn- wikilinks
  "Names inside [[...]] in paragraph, heading and table-cell text.
   An alias or anchor ([[Page|alias]], [[Page#part]]) is dropped."
  [^Node root]
  (->> (node-seq root)
       (filter #(or (instance? Paragraph %) (instance? Heading %) (instance? TableCell %)))
       (mapcat #(re-seq #"\[\[([^\]\[|#]+)(?:[|#][^\]\[]*)?\]\]" (plain-text % false)))
       (mapv (comp str/trim second))))

(defn top-level-blocks
  "[{:node :char-start :char-end}] for the document's top-level blocks
   that have a source span (the document viewer's render unit)."
  [^Node root]
  (vec (for [n (children root)
             :when (seq (.getSourceSpans ^Node n))]
         (assoc (span n) :node n))))

(defn parse-markdown
  "Parse Markdown into {:frontmatter <map or nil>, :sections [section ...]}.

   Section: {:level 0-6, :heading <string, nil for level 0>,
             :trail [<ancestor headings incl. self>],
             :char-start, :char-end,
             :blocks [{:type, :char-start, :char-end} ...]}
   Block :type is one of :paragraph :code :table :list :blockquote :html
   :other; thematic breaks are dropped.

   :links holds the raw link targets for §7.5 resolution:
   {:paths [<Markdown link destinations>], :wiki [<[[Page Name]] names>]}."
  [^String md]
  (let [root (.parse parser md)]
    {:frontmatter (parse-frontmatter md)
     :sections (parse-sections root)
     :links {:paths (link-destinations root)
             :wiki (wikilinks root)}}))

(defn parse-text
  "Parse a .txt file: one level-0 section whose blocks are the
   blank-line-separated paragraphs (SPEC.md §7.3 step 6). Same shape as
   parse-markdown; plain text has no frontmatter."
  [^String s]
  (let [m (re-matcher #"(?m)(?:^[^\n]*\S[^\n]*(?:\n|\z))+" s)
        blocks (loop [acc []]
                 (if (.find m)
                   (recur (conj acc {:type :paragraph
                                     :char-start (.start m)
                                     :char-end (cond-> (.end m)
                                                 (str/ends-with? (.group m) "\n") dec)}))
                   acc))]
    {:frontmatter nil
     :sections (if (seq blocks)
                 [{:level 0
                   :heading nil
                   :trail []
                   :blocks blocks
                   :char-start (:char-start (first blocks))
                   :char-end (:char-end (peek blocks))}]
                 [])}))

(defn doc-title
  "Document title: frontmatter title → first H1 → file name without
   extension (SPEC.md §7.3)."
  [{:keys [frontmatter sections]} rel-path]
  (or (some-> (:title frontmatter) str str/trim not-empty)
      (some #(when (= 1 (:level %)) (:heading %)) sections)
      (-> rel-path (str/split #"/") last (str/replace #"\.[^.]+$" ""))))
