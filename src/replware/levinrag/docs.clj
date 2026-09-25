(ns replware.levinrag.docs
  "Document lookup for the viewer and GET /api/v1/docs (SPEC.md §11, §12):
   ACL-checked metadata and chunk ranges, the source file inside the
   corpus dir, and Markdown rendered per block with chunk anchors."
  (:require [clojure.java.io :as io]
            [datalevin.core :as d]
            [hiccup2.core :as hiccup]
            [replware.levinrag.ingest.markdown :as md]
            [replware.levinrag.retrieval.datalevin :as rd])
  (:import [org.commonmark.ext.front.matter YamlFrontMatterBlock]
           [org.commonmark.ext.gfm.tables TablesExtension]
           [org.commonmark.node Node]
           [org.commonmark.renderer.html HtmlRenderer]))

(defn- doc-eid [db path]
  (d/q '[:find ?d . :in $ ?p :where [?d :doc/path ?p]] db path))

(defn- doc-view
  "{:doc {:doc/path :doc/title :doc/hash :doc/tags} :chunks [..]} for the
   doc entity `eid`. Chunks are ordered by position, each
   {:chunk/id :chunk/ordinal :chunk/char-start :chunk/char-end :section/trail}."
  [db eid]
  {:doc (d/pull db [:doc/path :doc/title :doc/hash :doc/tags] eid)
       :chunks (->> (d/q '[:find [(pull ?c [:chunk/id :chunk/ordinal :chunk/char-start :chunk/char-end
                                            {:chunk/section [:section/trail]}]) ...]
                           :in $ ?d :where [?c :chunk/doc ?d]]
                         db eid)
                    (map (fn [c] (-> c
                                     (assoc :section/trail (get-in c [:chunk/section :section/trail]))
                                     (dissoc :chunk/section))))
                    (sort-by (juxt :chunk/char-start :chunk/ordinal))
                    vec)})

(defn lookup-admin
  "doc-view for `path` with no ACL check — for admins only. A separate
   function rather than a flag on lookup-acl (SPEC.md §9.3)."
  [db path]
  (some->> (doc-eid db path) (doc-view db)))

(defn lookup-acl
  "doc-view for `path` when `principal`'s groups may read it, else nil
   (unknown and unreadable look the same). Ignores :admin?."
  [db principal path]
  (when-let [eid (doc-eid db path)]
    (when (and (seq (:groups principal))
               (contains? (rd/accessible-doc-ids db (:groups principal)) eid))
      (doc-view db eid))))

(defn source-file
  "The file for `path` inside `corpus-dir`, or nil when it would resolve
   outside it or does not exist."
  [corpus-dir path]
  (let [root (.getCanonicalFile (io/file corpus-dir))
        ;; File. (not io/file): an absolute `path` is joined under root
        ;; instead of throwing
        f (.getCanonicalFile (java.io.File. root ^String path))]
    (when (and (.startsWith (.toPath f) (.toPath root))
               (not= f root)
               (.isFile f))
      f)))

(def ^:private ^HtmlRenderer renderer
  (-> (HtmlRenderer/builder)
      (.extensions [(TablesExtension/create)])
      (.escapeHtml true)
      (.sanitizeUrls true)
      .build))

(defn- overlaps? [{:keys [char-start char-end]} c]
  (and (< char-start (:chunk/char-end c)) (< (:chunk/char-start c) char-end)))

(defn render-blocks
  "Hiccup for `md`, one div per top-level block (front matter skipped).
   Each chunk gets an empty anchor `<span id=chunk-id>` before the first
   block it overlaps; blocks overlapping the chunk `highlight-id` are
   marked (data-highlight) and the first scrolls into view."
  [^String md chunks highlight-id]
  (let [blocks (remove #(instance? YamlFrontMatterBlock (:node %))
                       (md/top-level-blocks (.parse md/parser md)))
        first-block (into {} (for [c chunks
                                   :let [b (first (filter #(overlaps? % c) blocks))]
                                   :when b]
                               [(:chunk/id c) (:char-start b)]))
        hl (some #(when (= highlight-id (:chunk/id %)) %) chunks)
        first-hl (some #(when (and hl (overlaps? % hl)) (:char-start %)) blocks)]
    (for [b blocks
          :let [on? (boolean (and hl (overlaps? b hl)))]]
      (list (for [c chunks
                  :when (= (:char-start b) (first-block (:chunk/id c)))]
              [:span {:id (:chunk/id c)}])
            [:div (cond-> {:class ["md-block" "rounded" "px-2" (when on? "bg-amber-100")]}
                    on? (assoc :data-highlight "true")
                    (and on? (= first-hl (:char-start b))) (assoc :x-init "$el.scrollIntoView({block: 'center'})"))
             (hiccup/raw (.render renderer ^Node (:node b)))]))))
