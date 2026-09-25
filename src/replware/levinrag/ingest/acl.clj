(ns replware.levinrag.ingest.acl
  "ACL rules engine — resolves the SPEC.md §7.2 rules.
   Rules (per SPEC.md §7.2):
   1. Collection effective-groups: nearest ancestor _collection.edn :read-groups wins
   2. File effective-groups: frontmatter :read_groups overrides entirely; else uses
      collection effective-groups
   3. ACL changes only need to recalculate affected groups, no re-chunking needed
   4. Empty [] means \"admin only\"
   Frontmatter :read_groups is a full override (replace, not union).
   Settings that cannot be applied as written fail closed (rule 5): see
   walker/scan-collection-edns and markdown/frontmatter-acl-problem.")

;; --- Collection group resolution ---

(defn parent-dir
  "Return parent directory of a relative path, or \"\" for root.
   Segments are not trimmed: \" hr\" and \"hr\" are different directories,
   and trimming made \" hr/\" miss its own _collection.edn."
  [^String p]
  (let [idx (.lastIndexOf p "/")]
    (if (pos? idx) (subs p 0 idx) "")))

(defn governing-broken-dir
  "The directory whose broken _collection.edn decides `dir`'s groups, or
   nil. Walks up like rule 1: the first directory with a broken file or
   with :read-groups decides (SPEC.md §7.2 rule 5). `broken` is
   dir → reason, as walker/scan-collection-edns returns it."
  [dir collection-edns broken]
  (loop [d dir]
    (cond
      (contains? broken d) d
      (or (:read-groups (get collection-edns d)) (= "" d)) nil
      :else (recur (parent-dir d)))))

(defn- find-nearest-read-groups
  "Walk up from dir-path to root, returning :read-groups from the nearest
   _collection.edn (as a vector of strings). Returns nil if none found."
  [dir-path collection-edns]
  (loop [d dir-path]
    (let [entry (get collection-edns d)]
      (if (and entry (:read-groups entry))
        (vec (:read-groups entry))
        (let [p (parent-dir d)]
          (if (= p d)
            nil
            (recur p)))))))

(defn resolve-collection-effective-groups
  "Resolve effective-groups for a collection directory.
   Per SPEC §7.2 Rule 1: nearest ancestor _collection.edn :read-groups wins.
   Walk up from dir-path; if none found, use root-read-groups.
   Parameters:
     dir-path       — relative directory path (\"\" for root)
     collection-edns — map of dir-path → parsed _collection.edn map
     root-read-groups — fallback groups for root (default [])
   Returns: vector of group strings."
  ([dir-path collection-edns]
   (resolve-collection-effective-groups dir-path collection-edns []))
  ([dir-path collection-edns root-read-groups]
   (or (find-nearest-read-groups dir-path collection-edns)
       root-read-groups)))

;; --- File group resolution ---

(defn resolve-file-groups
  "Resolve effective-groups for a document.
   Per SPEC §7.2 Rule 2:
   - If frontmatter has :read_groups → use it entirely (full override)
   - Otherwise → equals the collection's effective-groups
   Parameters:
     collection-effective-groups — vector of group strings from collection
     frontmatter — parsed frontmatter map (may not have :read_groups)
   Returns: vector of effective group strings."
  [collection-effective-groups frontmatter]
  (if-let [rg (:read_groups frontmatter)]
    (vec rg)
    (vec collection-effective-groups)))

;; --- Full resolution pipeline ---

(defn resolve-effective-groups
  "Full ACL resolution pipeline: declared → inherited → override.
   This is the main entry point. Given a file's relative path, the
   _collection.edn maps, root-read-groups fallback, and frontmatter,
   returns the file's effective-groups.
   Parameters:
     rel-path           — relative path from corpus root (e.g. \"hr/leave.md\")
     collection-edns    — map of dir-path → _collection.edn map
     root-read-groups   — fallback for root (default [])
     frontmatter        — parsed frontmatter map (may be nil)
   Returns: map {:declared-groups <vector> :effective-groups <vector> :collection <string>}."
  ([rel-path collection-edns frontmatter]
   (resolve-effective-groups rel-path collection-edns [] frontmatter))
  ([rel-path collection-edns root-read-groups frontmatter]
   (let [dir (parent-dir rel-path)
         declared (resolve-collection-effective-groups dir collection-edns root-read-groups)
         effective (resolve-file-groups declared frontmatter)]
     {:declared-groups declared
      :effective-groups effective
      :collection dir})))

;; --- Convenience: find nearest _collection.edn for a file ---

(defn nearest-collection-edn
  "Find the _collection.edn map nearest to rel-path, walking up from
   the file's directory to root. Returns the nearest map or nil."
  [rel-path collection-edns]
  (let [dir (parent-dir rel-path)]
    (loop [d dir]
      (let [entry (get collection-edns d)]
        (if (some? entry)
          entry
          (let [p (parent-dir d)]
            (if (= p d)
              nil
              (recur p))))))))