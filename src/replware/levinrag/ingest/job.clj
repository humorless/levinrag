(ns replware.levinrag.ingest.job
  "Incremental ingestion (SPEC.md §7.6): walk → collections → per-file
   hash delta → delete missing docs → resolve links → wait for index."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [datalevin.core :as d]
            [replware.levinrag.ingest.chunker :as chunker]
            [replware.levinrag.ingest.walker :as walker]
            [replware.levinrag.ingest.writer :as writer])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Paths]))

(defn- indexed-docs
  "path → {:hash :groups} for every doc currently in the index."
  [db]
  (into {}
        (map (fn [d]
               [(:doc/path d) {:hash (:doc/hash d)
                               :content-hash (:doc/content-hash d)
                               :groups (set (:doc/effective-groups d))}]))
        (d/q '[:find [(pull ?d [:doc/path :doc/hash :doc/content-hash :doc/effective-groups]) ...]
               :where [?d :doc/path]]
             db)))

(defn- ingest-file!
  "Index one walker file map; returns {:path :status ...}."
  [conn indexed {:keys [rel-path abs-path effective-groups]
                 :as file} {:keys [chunk-config embed-fn]}]
  (let [raw (Files/readAllBytes (Paths/get ^String abs-path (make-array String 0)))
        sha (writer/sha256-hex raw)
        old (get indexed rel-path)]
    (cond
      (and old (= sha (:hash old)) (= (:groups old) (set (map str effective-groups))))
      {:path rel-path
       :status :skipped}

      (and old (= sha (:hash old)))
      (do (writer/update-acl! conn rel-path effective-groups)
          {:path rel-path
           :status :acl-updated})

      :else
      (let [text (String. ^bytes raw StandardCharsets/UTF_8)
            chash (writer/content-hash text)
            doc (writer/build-doc (assoc file :hash sha :content-hash chash) text chunk-config)
            ;; unchanged chunks keep their vectors, so an edit of only the
            ;; frontmatter read_groups re-embeds nothing (SPEC §7.2 rule 3)
            n (writer/index-doc! conn doc embed-fn)]
        {:path rel-path
         :status (cond
                   (not old) :added
                   (= chash (:content-hash old)) :acl-updated
                   :else :updated)
         :chunks n
         :max-chunk-tokens (reduce max 0 (map :tokens (:chunks doc)))}))))

(defn- remove-stale!
  "Delete `rel-path`'s indexed copy after it failed to (re)ingest; true when
   there was one and it is gone. Keeping it would leave the old text
   searchable under the old — possibly wider — groups (SPEC.md §7.2)."
  [conn indexed rel-path]
  (when (contains? indexed rel-path)
    (try (writer/delete-doc! conn rel-path)
         true
         (catch Exception e
           (log/error e "[INGEST] could not remove the stale copy of" rel-path)
           false))))

(defn ingest!
  "Bring index.dtlv in line with `corpus-dir`. Per-file failures are
   reported, not thrown. Returns the ingestion report (SPEC.md §7.6).

   opts: :corpus-dir, :root-read-groups, :embed-fn (vector of strings →
   vector of float vectors), :chunk-config (optional),
   :index-timeout-ms (optional)."
  [conn {:keys [corpus-dir root-read-groups chunk-config index-timeout-ms]
         :or {root-read-groups []
              chunk-config chunker/default-config
              index-timeout-ms 60000}
         :as opts}]
  ;; a missing corpus would look like "every doc was deleted": refuse
  ;; instead of wiping the index (web runner and `bb ingest` alike)
  (when-not (.isDirectory (io/file corpus-dir))
    (throw (ex-info (str "CORPUS_DIR 不存在：" corpus-dir) {:corpus-dir corpus-dir})))
  (let [t0 (System/nanoTime)
        {:keys [edns broken]} (walker/scan-collection-edns corpus-dir)
        files (walker/walk-corpus corpus-dir edns root-read-groups broken)
        on-disk (set (map :rel-path files))
        _ (writer/upsert-collections! conn (writer/collection-dirs on-disk edns) edns root-read-groups)
        indexed (indexed-docs (d/db conn))
        opts (assoc opts :chunk-config chunk-config)
        results (mapv (fn [{:keys [rel-path acl-error]
                            :as file}]
                        (try
                          (if acl-error
                            ;; fail closed (SPEC.md §7.2 rule 5): never index
                            ;; it with a guess, and drop a copy indexed earlier
                            (let [removed? (remove-stale! conn indexed rel-path)]
                              (log/warn "[INGEST] acl fail-closed:" rel-path
                                        (if removed? "(removed from index)" "(not indexed)") acl-error)
                              {:path rel-path
                               :status :error
                               :error (str "權限設定錯誤，未匯入" (when removed? "（已從索引移除）") "：" acl-error)})
                            (ingest-file! conn indexed file opts))
                          (catch Exception e
                            (log/error e "[INGEST] failed:" rel-path)
                            (let [removed? (remove-stale! conn indexed rel-path)]
                              (when removed? (log/warn "[INGEST] removed the stale copy of" rel-path))
                              {:path rel-path
                               :status :error
                               :error (str (ex-message e) (when removed? "（已從索引移除，重新匯入成功後會加回）"))}))))
                      files)
        deleted (vec (sort (remove on-disk (keys indexed))))
        _ (doseq [p deleted] (writer/delete-doc! conn p))
        unresolved (writer/resolve-links! conn)
        wait (d/wait-for-secondary-index conn {:timeout-ms index-timeout-ms})
        by-status (group-by :status results)
        n (fn [k] (count (get by-status k)))]
    {:corpus-dir corpus-dir
     :added (n :added)
     :updated (n :updated)
     :acl-updated (n :acl-updated)
     :skipped (n :skipped)
     :deleted (count deleted)
     :deleted-paths deleted
     :errors (vec (get by-status :error))
     :chunks-written (reduce + (keep :chunks results))
     :total-chunks (count (d/q '[:find [?c ...] :where [?c :chunk/id]] (d/db conn)))
     :max-chunk-tokens (reduce max 0 (keep :max-chunk-tokens results))
     :unresolved-links unresolved
     :index-lag (:unfinished-count wait)
     :elapsed-ms (quot (- (System/nanoTime) t0) 1000000)}))
