(ns replware.levinrag.ingest.writer
  "index.dtlv writes (SPEC.md §7.6): collections, per-doc indexing with
   one transaction per doc, ACL-only updates, deletion, and second-pass
   link resolution (§7.5).

   Re-indexing a doc updates its section/chunk entities in place, keyed
   by their stable ids (\"<path>#<n>\", \"<path>::<n>\"), and retracts the
   ones that no longer exist. Retracting an entity and re-adding the same
   unique id in one transaction fails in Datalevin 1.1.0 (fulltext
   \"Document does not exist.\"), see docs/decisions.md."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datalevin.core :as d]
            [replware.levinrag.db.schema :as schema]
            [replware.levinrag.ingest.acl :as acl]
            [replware.levinrag.ingest.chunker :as chunker]
            [replware.levinrag.ingest.markdown :as md])
  (:import [java.net URLDecoder]
           [java.security MessageDigest]))

(defn sha256-hex [^bytes bs]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") bs))))

(defn content-hash
  "sha256 of `text` with the frontmatter `read_groups:` line removed, so a
   file whose only change is its ACL keeps the same content hash."
  [^String text]
  (let [fm (re-find #"(?s)^---\r?\n.*?\r?\n?---" text)
        stripped (if fm
                   (str (str/replace fm #"(?m)^read_groups:[^\n]*\n?" "") (subs text (count fm)))
                   text)]
    (sha256-hex (.getBytes ^String stripped "UTF-8"))))

(defn- many? [attr]
  (= :db.cardinality/many (get-in schema/index-schema [attr :db/cardinality])))

(defn- replace-tx
  "Tx ops that make the entity with unique `id-attr` hold exactly the
   attributes of `m` (attributes in `keep` are left alone). A new entity
   just gets `m`; an existing one also gets retractions for attributes
   and cardinality-many values that `m` drops."
  [db id-attr m keep-attrs]
  (let [old (when-let [eid (d/entid db [id-attr (get m id-attr)])]
              (assoc (d/pull db '[*] eid) :db/id eid))
        eid (:db/id old)]
    (concat
      (when old
        (for [[a v] (dissoc old :db/id)
              :when (not (contains? keep-attrs a))
              op (cond
                   (not (contains? m a)) [[:db/retract eid a]]
                   (many? a) (for [x (set/difference (set v) (set (get m a)))]
                               [:db/retract eid a x])
                   :else nil)]
          op))
      [m])))

;; --- collections ---

(defn collection-dirs
  "Every directory holding a file or a _collection.edn, plus all their
   ancestors; the root is \"\"."
  [rel-paths collection-edns]
  (let [up (fn up [dir] (cons dir (when-not (= "" dir)
                                    (lazy-seq (up (acl/parent-dir dir))))))]
    (into (sorted-set)
          (mapcat up (concat (map acl/parent-dir rel-paths) (keys collection-edns))))))

(defn upsert-collections!
  "Upsert one entity per directory (materialized effective groups, §7.2)
   and retract collections whose directory is gone. A directory governed
   by a broken _collection.edn (`broken`, dir → reason) gets no groups,
   not its parent's (§7.2 rule 5)."
  [conn dirs collection-edns root-read-groups broken]
  (let [db (d/db conn)
        stale (remove (set dirs) (d/q '[:find [?p ...] :where [_ :collection/path ?p]] db))
        tempid #(str "coll:" %)]
    (d/transact!
      conn
      (vec
        (concat
          (for [p stale] [:db/retractEntity [:collection/path p]])
          (mapcat
            (fn [dir]
              (let [edn (get collection-edns dir)
                    effective (when-not (acl/governing-broken-dir dir collection-edns broken)
                                (acl/resolve-collection-effective-groups dir collection-edns root-read-groups))]
                (replace-tx db :collection/path
                            (cond-> {:db/id (tempid dir)
                                     :collection/path dir
                                     :collection/name (or (:name edn) (last (str/split dir #"/")) "")}
                              (not= "" dir) (assoc :collection/parent (tempid (acl/parent-dir dir)))
                              (:read-groups edn) (assoc :collection/declared-groups (mapv str (:read-groups edn)))
                              (seq effective) (assoc :collection/effective-groups (mapv str effective)))
                            #{})))
            dirs))))))

;; --- building a doc (pure) ---

(defn- section-parents
  "Index of each section's parent: the nearest earlier heading section
   with a lower level. Level-0 content and top headings have none."
  [sections]
  (vec (map-indexed
         (fn [i {:keys [level]}]
           (when (pos? level)
             (some #(let [l (:level (nth sections %))]
                      (when (< 0 l level) %))
                   (range (dec i) -1 -1))))
         sections)))

(defn- as-strings [x]
  (cond (sequential? x) (mapv str x)
        (some? x) [(str x)]))

(defn build-doc
  "Everything index-doc! writes for one file, computed without the DB.
   `file` is a walker map plus :hash; `content` is the decoded file."
  [{:keys [rel-path collection effective-groups]
    :as file} ^String content chunk-config]
  (let [parsed (if (str/ends-with? rel-path ".txt")
                 (md/parse-text content)
                 (md/parse-markdown content))
        {:keys [frontmatter sections]} parsed
        title (md/doc-title parsed rel-path)
        sec-id #(str rel-path "#" %)
        parent-idx (section-parents sections)]
    {:path rel-path
     :title title
     :hash (:hash file)
     :content-hash (:content-hash file)
     :collection collection
     :tags (as-strings (:tags frontmatter))
     :declared-groups (as-strings (:read_groups frontmatter))
     :effective-groups (mapv str effective-groups)
     :frontmatter frontmatter
     :raw-links (or (:links parsed) {:paths []
                                     :wiki []})
     :sections (vec (map-indexed
                      (fn [i {:keys [heading level trail]}]
                        {:id (sec-id i)
                         :heading heading
                         :level level
                         :trail (str/join " > " trail)
                         :parent (some-> (nth parent-idx i) sec-id)})
                      sections))
     :chunks (mapv (fn [{:keys [section-index ordinal text]
                         :as c}]
                     (merge (select-keys c [:tokens :char-start :char-end :hard-cut?])
                            {:id (str rel-path "::" ordinal)
                             :section (sec-id section-index)
                             :ordinal ordinal
                             :text text
                             :index-text (chunker/index-text
                                           title (:trail (nth sections section-index)) text)}))
                   (chunker/chunk-doc content sections chunk-config))}))

;; --- writing a doc ---

(defn- children-ids [db doc-path]
  {:sections (set (d/q '[:find [?id ...] :in $ ?p
                         :where [?d :doc/path ?p] [?s :section/doc ?d] [?s :section/id ?id]]
                       db doc-path))
   :chunks (set (d/q '[:find [?id ...] :in $ ?p
                       :where [?d :doc/path ?p] [?c :chunk/doc ?d] [?c :chunk/id ?id]]
                     db doc-path))})

(defn- doc-entity [{:keys [path title collection tags declared-groups
                           effective-groups frontmatter raw-links]
                    :as doc}]
  (cond-> {:db/id "doc"
           :doc/path path
           :doc/title title
           :doc/hash (:hash doc)
           :doc/collection [:collection/path collection]
           :doc/raw-links raw-links
           :doc/ingested-at (java.util.Date.)}
    (:content-hash doc) (assoc :doc/content-hash (:content-hash doc))
    (seq tags) (assoc :doc/tags tags)
    (seq declared-groups) (assoc :doc/declared-groups declared-groups)
    (seq effective-groups) (assoc :doc/effective-groups effective-groups)
    frontmatter (assoc :doc/frontmatter frontmatter)))

(defn- stored-vecs
  "chunk id → [index-text vector] for the doc's chunks already indexed."
  [db doc-path]
  (into {} (map (fn [[id t v]] [id [t v]]))
        (d/q (quote [:find ?id ?t ?v :in $ ?p
                     :where [?d :doc/path ?p] [?c :chunk/doc ?d] [?c :chunk/id ?id]
                     [?c :chunk/index-text ?t] [?c :chunk/vec ?v]])
             db doc-path)))

(defn- chunk-vecs
  "One vector per chunk: reused when the chunk id already holds the same
   index-text (e.g. an ACL-only frontmatter edit), otherwise embedded —
   in one embed-fn call for all the new texts."
  [db path chunks embed-fn]
  (let [stored (stored-vecs db path)
        reuse (fn [c] (let [[t v] (stored (:id c))] (when (= t (:index-text c)) v)))
        todo (vec (remove reuse chunks))
        fresh (if (seq todo) (embed-fn (mapv :index-text todo)) [])
        _ (when (not= (count fresh) (count todo))
            (throw (ex-info "embedding count mismatch"
                            {:doc path
                             :chunks (count todo)
                             :vectors (count fresh)})))
        by-id (zipmap (map :id todo) fresh)]
    (mapv #(or (reuse %) (by-id (:id %))) chunks)))

(defn index-doc!
  "Write a built doc in one transaction: upsert the doc, update its
   sections and chunks in place, retract the ones that disappeared.
   `embed-fn` maps a vector of strings to a vector of float vectors; it is
   only called for chunks whose index-text changed. Returns the number of
   chunks written."
  [conn {:keys [path sections chunks]
         :as doc} embed-fn]
  (let [db (d/db conn)
        vecs (chunk-vecs db path chunks embed-fn)
        old (children-ids db path)
        gone (concat (set/difference (:chunks old) (set (map :id chunks)))
                     (set/difference (:sections old) (set (map :id sections))))
        sec-tempid #(str "sec:" %)]
    (d/transact!
      conn
      (vec
        (concat
          (for [id gone]
            [:db/retractEntity (if (str/includes? id "::") [:chunk/id id] [:section/id id])])
          (replace-tx db :doc/path (doc-entity doc) #{:doc/links-to})
          (mapcat (fn [{:keys [id heading level trail parent]}]
                    (replace-tx db :section/id
                                (cond-> {:db/id (sec-tempid id)
                                         :section/id id
                                         :section/doc "doc"
                                         :section/level level
                                         :section/trail trail}
                                  heading (assoc :section/heading heading)
                                  parent (assoc :section/parent (sec-tempid parent)))
                                #{}))
                  sections)
          (mapcat (fn [c v]
                    (replace-tx db :chunk/id
                                (cond-> {:chunk/id (:id c)
                                         :chunk/doc "doc"
                                         :chunk/section (sec-tempid (:section c))
                                         :chunk/ordinal (:ordinal c)
                                         :chunk/text (:text c)
                                         :chunk/index-text (:index-text c)
                                         :chunk/vec (float-array v)
                                         :chunk/tokens (:tokens c)
                                         :chunk/char-start (:char-start c)
                                         :chunk/char-end (:char-end c)}
                                  (:hard-cut? c) (assoc :chunk/hard-cut? true))
                                #{}))
                  chunks vecs))))
    (count chunks)))

(defn update-acl!
  "ACL-only change (§7.2 rule 3): reset :doc/effective-groups without
   touching sections, chunks or embeddings."
  [conn path effective-groups]
  (let [db (d/db conn)
        eid (d/entid db [:doc/path path])
        old (set (:doc/effective-groups (d/pull db [:doc/effective-groups] eid)))
        wanted (set (map str effective-groups))]
    (d/transact! conn (vec (concat (for [g (set/difference old wanted)] [:db/retract eid :doc/effective-groups g])
                                   (for [g (set/difference wanted old)] [:db/add eid :doc/effective-groups g]))))))

(defn delete-doc!
  "Retract a doc with all its sections and chunks, in one transaction."
  [conn path]
  (let [{:keys [sections chunks]} (children-ids (d/db conn) path)]
    (d/transact! conn (vec (concat (for [id chunks] [:db/retractEntity [:chunk/id id]])
                                   (for [id sections] [:db/retractEntity [:section/id id]])
                                   [[:db/retractEntity [:doc/path path]]])))))

;; --- links (second pass, §7.5) ---

(defn- normalize-path
  "Resolve \".\" and \"..\" segments; nil when the path escapes the root."
  [path]
  (loop [[seg & more :as segs] (str/split path #"/"), acc []]
    (cond
      (empty? segs) (str/join "/" acc)
      (or (= "" seg) (= "." seg)) (recur more acc)
      (= ".." seg) (when (seq acc) (recur more (pop acc)))
      :else (recur more (conj acc seg)))))

(defn resolve-path-link
  "Corpus-relative target of a Markdown link from `from-path`, or nil when
   it is not a relative .md link. Anchors and queries are ignored."
  [from-path dest]
  (let [dest (-> dest
                 (str/replace #"[#?].*$" "")
                 (str/replace "+" "%2B")
                 (URLDecoder/decode "UTF-8"))]
    (when (and (re-find #"(?i)\.(md|markdown)$" dest)
               (not (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" dest))
               (not (str/starts-with? dest "/")))
      (let [dir (acl/parent-dir from-path)]
        (normalize-path (if (= "" dir) dest (str dir "/" dest)))))))

(defn- base-name [path]
  (-> path (str/split #"/") last (str/replace #"\.[^.]+$" "")))

(defn resolve-links!
  "Recompute :doc/links-to for every doc from its :doc/raw-links. Runs
   over all docs, so a link to a doc added in this run resolves even
   when the linking doc itself did not change. Returns the unresolved
   links as [{:doc path :link raw}]."
  [conn]
  (let [db (d/db conn)
        docs (sort-by :doc/path
                      (d/q '[:find [(pull ?d [:db/id :doc/path :doc/title :doc/raw-links
                                              {:doc/links-to [:db/id]}]) ...]
                             :where [?d :doc/path]]
                           db))
        by-path (into {} (map (juxt :doc/path :db/id)) docs)
        by-name (reduce (fn [m {:keys [db/id doc/path doc/title]}]
                          (let [add #(if (or (str/blank? %2) (contains? %1 %2)) %1 (assoc %1 %2 id))]
                            (-> m (add (some-> title str/lower-case)) (add (str/lower-case (base-name path))))))
                        {} docs)
        results (for [{:keys [db/id doc/path doc/raw-links doc/links-to]} docs]
                  (let [paths (for [dest (:paths raw-links)
                                    :let [target (resolve-path-link path dest)]
                                    :when (some? target)]
                                [dest (by-path target)])
                        wikis (for [w (:wiki raw-links)]
                                [(str "[[" w "]]") (by-name (str/lower-case w))])
                        resolved (disj (set (keep second (concat paths wikis))) id)]
                    {:eid id
                     :old (set (map :db/id links-to))
                     :links resolved
                     :unresolved (for [[raw target] (concat paths wikis) :when (nil? target)]
                                   {:doc path
                                    :link raw})}))
        tx (mapcat (fn [{:keys [eid old links]}]
                     (concat (for [t (set/difference old links)] [:db/retract eid :doc/links-to t])
                             (for [t (set/difference links old)] [:db/add eid :doc/links-to t])))
                   results)]
    (when (seq tx) (d/transact! conn (vec tx)))
    (vec (mapcat :unresolved results))))
