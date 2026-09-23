(ns hybridrag.ingest.walker
  "Walk corpus/ directory, resolve ACL for each file, and return
   ingest-ready file maps.

   Per SPEC.md §7.1:
   - Accept extensions: .md, .markdown, .txt
   - Skip files/dirs starting with \".\" or \"_\" (except _collection.edn)
   - Walk recursively from corpus root."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hybridrag.ingest.acl :as acl]))

;; --- Acceptable extensions ---

(def accepted-extensions
  "Set of accepted file extensions (without dot)."
  #{"md" "markdown" "txt"})

(defn acceptable-extension?
  "Check if a filename has an accepted extension.
  Returns false for dotfiles (files starting with .)."
  [filename]
  (when (string? filename)
    (if-let [ext (str/last-index-of filename \.)]
      (and (> ext 0)  ; dotfiles start at index 0
           (contains? accepted-extensions (subs filename (inc ext))))
      false)))

;; --- _collection.edn discovery ---

(defn find-collection-edns
  "Find all _collection.edn files under corpus-dir, returning a map
   of relative directory path → parsed EDN map.

   _collection.edn at path \"hr/\" is keyed as \"hr\" in the map.
   Root _collection.edn is keyed as \"\".

   If collection-edns is nil, discovers them from corpus-dir."
  [corpus-dir]
  (let [root (io/file corpus-dir)]
    (when (.exists root)
      (->>
        (file-seq root)
        (filter #(.isFile %))
        (filter #(= "_collection.edn" (.getName %)))
        (reduce
          (fn [edns file]
            (let [file-dir (.getParent file)]
              (if file-dir
                (let [root-path (.toPath root)
                      file-path (.toPath (io/file file-dir))
                      rel (.relativize root-path file-path)]
                  (try
                    (let [parsed (edn/read-string (slurp file))]
                      (assoc edns
                             (if (str/blank? rel) "" (str/join "/" (seq rel)))
                             (if (map? parsed) parsed {})))
                    (catch Exception _
                      edns)))
                (try
                  (let [parsed (edn/read-string (slurp file))]
                    (assoc edns "" (if (map? parsed) parsed {})))
                  (catch Exception _
                    edns))))))
          {}))))

;; --- File metadata ---

(defn file-meta
  "Return metadata for a file: {:rel-path, :abs-path, :size, :mtime}."
  [corpus-dir file]
  (let [root-path (.toPath (io/file corpus-dir))
        file-path (.toPath file)
        rel (.relativize root-path file-path)
        rel-path (if (str/blank? rel) (.getName file) (str/join "/" rel))
        size (.length file)
        mtime (.lastModified file)]
    {:rel-path rel-path
     :abs-path (.getAbsolutePath file)
     :size size
     :mtime mtime}))

;; --- File collection ---

(defn collect-markdown-files
  "Recursively collect .md/.markdown/.txt files under corpus-dir,
   skipping dotfiles/dotdirs.

   Returns: vector of file maps with {:rel-path, :abs-path, :size, :mtime}."
  [corpus-dir]
  (let [root (io/file corpus-dir)]
    (when (.exists root)
      (->>
        (file-seq root)
        (filter #(.isFile %))
        (filter (fn [f] (acceptable-extension? (.getName f))))
        (map #(file-meta corpus-dir %))
        vec))))

;; --- Frontmatter parsing (simple YAML, MVP version) ---

(defn parse-frontmatter
  "Extract YAML frontmatter from Markdown string. Returns a map or nil.

   Handles simple YAML:
     key: value
     key: [item1, item2]
     key: \"quoted string\"

   Per SPEC.md: frontmatter supports title, tags, read_groups, and
   any other fields are stored as EDN blob in :doc/frontmatter.

   Note: For tags and read_groups, converts symbols to strings
   (e.g., [hr, policy] → [\"hr\" \"policy\"])."
  [^String md]
  (when (str/starts-with? md "---")
    (if-let [[_ body] (re-find #"(?s)^---\n(.*?)\n?---" md)]
      (let [lines (str/split-lines body)
            parsed (if (empty? (remove str/blank? lines))
                     {}
                     (reduce
                       (fn [m line]
                         (if-let [[_ k v] (re-find #"^([^:]+):\s+(.*)$" line)]
                           (let [k (str/trim k)
                                 v (str/trim v)]
                             (assoc m
                               (keyword k)
                               (try (edn/read-string v) (catch Exception _ v))))
                           m))
                       {}
                       lines))]
        ;; Convert symbols to strings for specific keys
        (-> parsed
            (update :tags #(when % (mapv str %)))
            (update :read_groups #(when % (mapv str %)))))
      nil)))

;; --- Full ingestion walk ---

(defn resolve-acl-for-file
  "Given a file's relative path, metadata, collection-edns, and
   root-read-groups, resolve the full ACL for the file.

   Returns a map with:
     :rel-path            — relative path from corpus root
     :abs-path            — absolute file path
     :size                — file size in bytes
     :mtime               — last modified timestamp
     :declared-groups     — groups from nearest _collection.edn :read-groups
     :effective-groups    — effective groups after ACL resolution
     :collection          — collection directory path
     :frontmatter         — parsed frontmatter (may be nil)"
  [rel-path fm collection-edns root-read-groups]
  (let [abs-path (:abs-path fm)
        content (try (slurp abs-path :encoding "UTF-8") (catch Exception _ ""))
        frontmatter (parse-frontmatter content)
        acl-result (acl/resolve-effective-groups rel-path collection-edns root-read-groups frontmatter)]
    (merge fm acl-result {:frontmatter frontmatter})))

(defn walk-corpus
  "Full corpus walk: collect files, resolve ACL for each.

   Parameters:
     corpus-dir         — directory to walk
     collection-edns    — optional map of dir-path → _collection.edn map
                          (if nil, discovers from corpus-dir)
     root-read-groups   — fallback groups for root (default [])

   Returns: vector of ingest-ready file maps."
  ([corpus-dir]
   (walk-corpus corpus-dir nil []))
  ([corpus-dir collection-edns]
   (walk-corpus corpus-dir collection-edns []))
  ([corpus-dir collection-edns root-read-groups]
   (let [cedns (or collection-edns (find-collection-edns corpus-dir))
         files (collect-markdown-files corpus-dir)]
     (mapv #(resolve-acl-for-file (:rel-path %) % cedns root-read-groups) files))))