(ns replware.levinrag.ingest.cli
  "`bb ingest` / `bb reindex` entry point (SPEC.md §7.6). Runs without the
   Integrant system; settings come from env vars (replware.levinrag.config)."
  (:require [clojure.java.io :as io]
            [datalevin.core :as d]
            [replware.levinrag.config :as config]
            [replware.levinrag.db.index-conn :as index-conn]
            [replware.levinrag.ingest.job :as job]
            [replware.levinrag.ingest.report :as report]
            [replware.levinrag.llm.embed :as embed]))

(defn- delete-tree! [f]
  (let [f (io/file f)]
    (when (.exists f)
      (doseq [x (reverse (file-seq f))] (io/delete-file x)))))

(defn ingest-corpus!
  "Ingest the corpus into <data-dir>/index.dtlv; with `reindex?`, delete
   the index first. Returns the report."
  [{:keys [reindex?]}]
  (let [{:keys [data-dir corpus-dir root-read-groups]} (config/corpus-config)
        embed-cfg (config/embed-config)
        index-dir (str (io/file data-dir "index.dtlv"))
        ;; before reindex deletes anything
        _ (when-not (.isDirectory (io/file corpus-dir))
            (throw (ex-info (str "CORPUS_DIR 不存在：" corpus-dir "（索引未變更）") {:corpus-dir corpus-dir})))
        _ (when reindex? (delete-tree! index-dir))
        conn (index-conn/open index-dir (:dims embed-cfg))]
    (try
      (let [rep (job/ingest! conn {:corpus-dir corpus-dir
                                   :root-read-groups root-read-groups
                                   :embed-fn #(embed/embed-all! embed-cfg % 32)})
            path (report/write-report! data-dir rep)]
        (println (report/summary rep))
        (println "report:" path)
        rep)
      (finally (d/close conn)))))

(defn -main [& args]
  (let [code (try
               (let [rep (ingest-corpus! {:reindex? (= "reindex" (first args))})]
                 (if (seq (:errors rep)) 1 0))
               (catch clojure.lang.ExceptionInfo e
                 (binding [*out* *err*] (println "錯誤：" (ex-message e)))
                 2))]
    (shutdown-agents)
    (System/exit code)))
