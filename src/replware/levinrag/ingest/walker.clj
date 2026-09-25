(ns replware.levinrag.ingest.walker
  "Walk corpus/ directory, resolve ACL for each file, and return
   ingest-ready file maps.

   Per SPEC.md §7.1:
   - Accept extensions: .md, .markdown, .txt
   - Skip files/dirs starting with \".\" or \"_\" (except _collection.edn)
   - Walk recursively from corpus root."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [replware.levinrag.ingest.acl :as acl]
            [replware.levinrag.ingest.markdown :as md]))

;; --- Acceptable extensions ---

(def accepted-extensions
  "Set of accepted file extensions (without dot)."
  #{"md" "markdown" "txt"})

(defn acceptable-extension?
  "Check if a filename has an accepted extension.
  Returns false for dotfiles (files starting with .)."
  [filename]
  (boolean
    (when (and (string? filename) (not (str/starts-with? filename ".")))
      (when-let [ext (str/last-index-of filename \.)]
        (contains? accepted-extensions (subs filename (inc ext)))))))

;; --- Path helpers ---

(defn- rel-path-str
  "Relative path from root to file as a \"/\"-joined string (\"\" for root)."
  [root file]
  (let [rel (.relativize (.toPath (io/file root)) (.toPath (io/file file)))]
    (str/join "/" (map str rel))))

(defn- skipped-segment?
  "True if any path segment starts with \".\" or \"_\" (SPEC.md §7.1)."
  [rel-path]
  (some #(or (str/starts-with? % ".") (str/starts-with? % "_"))
        (str/split rel-path #"/")))

;; --- _collection.edn discovery ---

(defn find-collection-edns
  "Find all _collection.edn files under corpus-dir, returning a map
   of relative directory path → parsed EDN map.

   _collection.edn at path \"hr/\" is keyed as \"hr\" in the map.
   Root _collection.edn is keyed as \"\". Unparseable files are skipped."
  [corpus-dir]
  (let [root (io/file corpus-dir)]
    (when (.exists root)
      (reduce
        (fn [edns ^java.io.File file]
          (let [dir (rel-path-str root (.getParentFile file))]
            (if (and (not (str/blank? dir)) (skipped-segment? dir))
              edns
              (try
                (let [parsed (edn/read-string (slurp file))]
                  (assoc edns dir (if (map? parsed) parsed {})))
                (catch Exception _
                  edns)))))
        {}
        (->> (file-seq root)
             (filter #(.isFile ^java.io.File %))
             (filter #(= "_collection.edn" (.getName ^java.io.File %))))))))

;; --- File metadata ---

(defn file-meta
  "Return metadata for a file: {:rel-path, :abs-path, :size, :mtime}."
  [corpus-dir ^java.io.File file]
  {:rel-path (rel-path-str corpus-dir file)
   :abs-path (.getAbsolutePath file)
   :size (.length file)
   :mtime (.lastModified file)})

;; --- File collection ---

(defn collect-markdown-files
  "Recursively collect .md/.markdown/.txt files under corpus-dir,
   skipping files/dirs whose name starts with \".\" or \"_\".

   Returns: vector of file maps with {:rel-path, :abs-path, :size, :mtime}."
  [corpus-dir]
  (let [root (io/file corpus-dir)]
    (when (.exists root)
      (->>
        (file-seq root)
        (filter #(.isFile ^java.io.File %))
        (filter #(acceptable-extension? (.getName ^java.io.File %)))
        (map #(file-meta corpus-dir %))
        (remove #(skipped-segment? (:rel-path %)))
        vec))))

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
        frontmatter (md/parse-frontmatter content)
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