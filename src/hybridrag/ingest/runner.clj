(ns hybridrag.ingest.runner
  "In-process ingest jobs for the admin page and POST /api/v1/ingest
   (SPEC.md §11): one job at a time on the server's index-conn, same
   settings and report file as `bb ingest`. Incremental only — reindex
   deletes the index the server holds open, so it stays CLI-only."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [hybridrag.config :as config]
            [hybridrag.ingest.job :as job]
            [hybridrag.ingest.report :as report]
            [hybridrag.llm.embed :as embed]
            [integrant.core :as ig]))

(def ^:private history-size 20)

(defn make
  "Runner over `index-conn`: {:index-conn :corpus-dir :data-dir
   :root-read-groups :embed-fn} plus job state."
  [opts]
  (assoc opts :jobs (atom {:current nil
                           :history []})))

(defn- finish! [{:keys [jobs]} job]
  (swap! jobs (fn [s] (-> s
                          (assoc :current job)
                          (update :history #(vec (take-last history-size (conj % job))))))))

(defn- run-job! [{:keys [index-conn corpus-dir data-dir root-read-groups embed-fn]
                  :as runner} job]
  (let [done (try
               ;; ingest treats a missing corpus as "every doc deleted";
               ;; refuse instead of wiping the index
               (when-not (.isDirectory (io/file corpus-dir))
                 (throw (ex-info (str "CORPUS_DIR 不存在：" corpus-dir) {:corpus-dir corpus-dir})))
               (let [rep (job/ingest! index-conn {:corpus-dir corpus-dir
                                                  :root-read-groups root-read-groups
                                                  :embed-fn embed-fn})
                     path (report/write-report! data-dir rep)]
                 (log/info "[INGEST] job done" (:id job) path)
                 (assoc job :status :done :report rep :report-path path))
               (catch Throwable e
                 (log/error e "[INGEST] job failed" (:id job))
                 (assoc job :status :failed :error (or (ex-message e) (str (class e))))))]
    (finish! runner (assoc done :finished-at (java.util.Date.)))))

(defn start!
  "Start a job unless one is running. {:job job} or {:conflict running-job}."
  [{:keys [jobs]
    :as runner}]
  (let [job {:id (str (random-uuid))
             :status :running
             :started-at (java.util.Date.)}
        [old _] (swap-vals! jobs (fn [s] (if (= :running (get-in s [:current :status]))
                                           s
                                           (assoc s :current job))))]
    (if (= :running (get-in old [:current :status]))
      {:conflict (:current old)}
      (do (future (run-job! runner job))
          {:job job}))))

(defn latest
  "The running or most recent job, or nil."
  [{:keys [jobs]}]
  (:current @jobs))

(defn history
  "Finished jobs, oldest first (at most 20)."
  [{:keys [jobs]}]
  (:history @jobs))

(defn job
  "Job by id: the current one or one from the history."
  [{:keys [jobs]} id]
  (let [{:keys [current]
         done :history} @jobs]
    (some #(when (= id (:id %)) %) (cons current (reverse done)))))

(defn latest-report
  "The newest report under <data-dir>/ingest-reports (also written by
   `bb ingest`), or nil."
  [{:keys [data-dir]}]
  (when-let [f (->> (.listFiles (io/file data-dir "ingest-reports"))
                    (filter #(.endsWith (.getName ^java.io.File %) ".edn"))
                    (sort-by #(.getName ^java.io.File %))
                    last)]
    (try (edn/read-string (slurp f))
         (catch Exception e
           (log/warn "[INGEST] unreadable report" (str f) (ex-message e))
           nil))))

(defmethod ig/init-key ::runner
  [_ {:keys [index-conn]
      :as opts}]
  (let [{:keys [data-dir corpus-dir root-read-groups]} (config/corpus-config)
        embed-cfg (config/embed-config)]
    (make (merge {:corpus-dir corpus-dir
                  :data-dir data-dir
                  :root-read-groups root-read-groups
                  :embed-fn #(embed/embed-all! embed-cfg % 32)}
                 (dissoc opts :index-conn)
                 {:index-conn index-conn}))))
