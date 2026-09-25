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

(def ^:private collection-keys #{:name :read-groups})

(defn- collection-problem
  "Why the parsed _collection.edn `v` would not be applied as written, or
   nil."
  [v]
  (cond
    (not (map? v)) "內容必須是一個 map，例如 {:read-groups [\"hr\"]}"

    (seq (remove collection-keys (keys v)))
    (str "不認得的 key：" (str/join " " (remove collection-keys (keys v)))
         "（可用：" (str/join " " (sort collection-keys)) "）")

    (and (contains? v :read-groups)
         (not (and (sequential? (:read-groups v)) (every? string? (:read-groups v)))))
    "`:read-groups` 必須是字串清單，例如 [\"hr\" \"all\"]"))

(defn- read-edn-values
  "Every EDN value in `s`: a second map after the first must not be
   silently dropped, as edn/read-string would."
  [^String s]
  (let [r (java.io.PushbackReader. (java.io.StringReader. s))]
    (loop [acc []]
      (let [v (edn/read {:eof ::eof} r)]
        (if (= ::eof v) acc (recur (conj acc v)))))))

(defn- misnamed-collection-file?
  "A name that looks meant as _collection.edn but is not exactly it
   (_Collection.edn, collection.edn, _collections.edn): ignoring it would
   silently leave its directory with its parent's groups."
  [^String n]
  (and (not= "_collection.edn" n) (boolean (re-matches #"(?i)_?collections?\.edn" n))))

(defn scan-collection-edns
  "Every _collection.edn under corpus-dir outside skipped directories, keyed
   by relative directory path (\"hr/\" → \"hr\", the root → \"\"):
   {:edns {dir parsed-map} :broken {dir reason}}. A file that cannot be
   read or would not be applied as written is broken; ingest keeps the docs
   it governs out of the index (SPEC.md §7.2 rule 5)."
  [corpus-dir]
  (let [root (io/file corpus-dir)]
    (reduce
      (fn [acc ^java.io.File file]
        (let [dir (rel-path-str root (.getParentFile file))]
          (if (and (not (str/blank? dir)) (skipped-segment? dir))
            acc
            (let [[vs err] (if (misnamed-collection-file? (.getName file))
                             [nil (str "檔名 " (.getName file) " 應為 _collection.edn")]
                             (try [(read-edn-values (slurp file))]
                                  (catch Exception e [nil (str "無法解析：" (ex-message e))])))
                  v (first vs)
                  why (or err
                          (when (next vs) "檔案裡有不只一個 EDN 值，只能有一個 map")
                          (collection-problem v))]
              (if why
                (assoc-in acc [:broken dir] why)
                (assoc-in acc [:edns dir] v))))))
      {:edns {}
       :broken {}}
      (when (.exists root)
        (->> (file-seq root)
             (filter #(.isFile ^java.io.File %))
             (filter #(let [n (.getName ^java.io.File %)]
                        (or (= "_collection.edn" n) (misnamed-collection-file? n)))))))))

(defn find-collection-edns
  "The valid _collection.edn files under corpus-dir (see
   scan-collection-edns), or nil when corpus-dir does not exist."
  [corpus-dir]
  (when (.exists (io/file corpus-dir))
    (:edns (scan-collection-edns corpus-dir))))

(defn- collection-acl-problem
  "The broken _collection.edn that would decide `rel-path`'s groups, as a
   message, or nil. Walks up like ACL resolution: the first directory with
   a broken file or with :read-groups decides."
  [rel-path edns broken]
  (when-let [dir (acl/governing-broken-dir (acl/parent-dir rel-path) edns broken)]
    (str (if (= "" dir) "" (str dir "/")) "_collection.edn：" (get broken dir)
         "；修正前這個目錄下的文件不匯入")))

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
     :frontmatter         — parsed frontmatter (may be nil)
     :acl-error           — set when the permissions settings for this file
                            cannot be applied as written (the file must not
                            be indexed)"
  ([rel-path fm collection-edns root-read-groups]
   (resolve-acl-for-file rel-path fm collection-edns root-read-groups {}))
  ([rel-path fm collection-edns root-read-groups broken]
   (let [abs-path (:abs-path fm)
         content (try (slurp abs-path :encoding "UTF-8") (catch Exception _ ""))
         frontmatter (md/parse-frontmatter content)
         acl-result (acl/resolve-effective-groups rel-path collection-edns root-read-groups frontmatter)
         acl-error (or (collection-acl-problem rel-path collection-edns broken)
                       (md/frontmatter-acl-problem content))]
     (cond-> (merge fm acl-result {:frontmatter frontmatter})
       acl-error (assoc :acl-error acl-error)))))

(defn walk-corpus
  "Full corpus walk: collect files, resolve ACL for each.

   Parameters:
     corpus-dir         — directory to walk
     collection-edns    — optional map of dir-path → _collection.edn map
                          (if nil, discovers from corpus-dir)
     root-read-groups   — fallback groups for root (default [])
     broken             — dir-path → reason, from scan-collection-edns

   Returns: vector of ingest-ready file maps."
  ([corpus-dir]
   (walk-corpus corpus-dir nil []))
  ([corpus-dir collection-edns]
   (walk-corpus corpus-dir collection-edns []))
  ([corpus-dir collection-edns root-read-groups]
   (walk-corpus corpus-dir collection-edns root-read-groups nil))
  ([corpus-dir collection-edns root-read-groups broken]
   ;; without `broken` the corpus is scanned for it: valid edns alone (as
   ;; find-collection-edns returns them) would bring back fail-open
   (let [scan (when-not (and collection-edns broken) (scan-collection-edns corpus-dir))
         cedns (or collection-edns (:edns scan))
         broken (or broken (:broken scan))
         files (collect-markdown-files corpus-dir)]
     (mapv #(resolve-acl-for-file (:rel-path %) % cedns root-read-groups broken) files))))