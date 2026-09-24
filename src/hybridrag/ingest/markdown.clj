(ns hybridrag.ingest.markdown
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
           [org.commonmark.ext.gfm.tables TableBlock TablesExtension]
           [org.commonmark.node BlockQuote Code FencedCodeBlock Heading HtmlBlock
            IndentedCodeBlock ListBlock Node Paragraph SoftLineBreak Text ThematicBreak]
           [org.commonmark.parser IncludeSourceSpans Parser]))

(def ^:private ^Parser parser
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
  "Inline text of a node with markup stripped."
  [^Node n]
  (cond
    (instance? Text n) (.getLiteral ^Text n)
    (instance? Code n) (.getLiteral ^Code n)
    (instance? SoftLineBreak n) " "
    :else (apply str (map plain-text (children n)))))

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

(defn- parse-sections [^String md]
  (loop [[n & more :as nodes] (children (.parse parser md))
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

(defn parse-markdown
  "Parse Markdown into {:frontmatter <map or nil>, :sections [section ...]}.

   Section: {:level 0-6, :heading <string, nil for level 0>,
             :trail [<ancestor headings incl. self>],
             :char-start, :char-end,
             :blocks [{:type, :char-start, :char-end} ...]}
   Block :type is one of :paragraph :code :table :list :blockquote :html
   :other; thematic breaks are dropped."
  [^String md]
  {:frontmatter (parse-frontmatter md)
   :sections (parse-sections md)})

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
