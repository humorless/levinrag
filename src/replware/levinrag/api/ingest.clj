(ns replware.levinrag.api.ingest
  "POST /api/v1/ingest and GET /api/v1/ingest/{job_id} (SPEC.md §11):
   admin only — others get 404, as for hidden documents; one job at a
   time (409)."
  (:require [clojure.string :as str]
            [replware.levinrag.auth.middleware :as auth]
            [replware.levinrag.ingest.runner :as runner]))

(defn- snake [m]
  (update-keys m #(keyword (str/replace (name %) "-" "_"))))

(defn- job-json [{:keys [id status started-at finished-at report error]}]
  (cond-> {:job_id id
           :status (name status)
           :started_at (some-> ^java.util.Date started-at .toInstant str)}
    finished-at (assoc :finished_at (str (.toInstant ^java.util.Date finished-at)))
    report (assoc :report (snake (select-keys report [:added :updated :acl-updated :skipped :deleted :errors
                                                      :total-chunks :chunks-written :max-chunk-tokens
                                                      :index-lag :elapsed-ms])))
    error (assoc :error error)))

(defn- not-found [] (auth/error-response 404 "not_found" "找不到資源。"))

(defn start [{:keys [context principal]}]
  (if-not (:admin? principal)
    (not-found)
    (let [{:keys [job conflict]} (runner/start! (:ingest context))]
      (if conflict
        (assoc-in (auth/error-response 409 "conflict" "已有 ingest 在執行。") [:body :error :job_id] (:id conflict))
        {:status 202
         :body {:job_id (:id job)}}))))

(defn status [{:keys [context principal path-params]}]
  (if-let [job (and (:admin? principal) (runner/job (:ingest context) (:job_id path-params)))]
    {:status 200
     :body (job-json job)}
    (not-found)))
