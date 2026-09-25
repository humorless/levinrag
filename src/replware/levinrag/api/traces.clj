(ns replware.levinrag.api.traces
  "GET /api/v1/traces/{id} (SPEC.md §11, §14): the trace's owner or an
   admin; anyone else, unknown or malformed ids → 404."
  (:require [datalevin.core :as d]
            [replware.levinrag.auth.middleware :as auth]
            [replware.levinrag.trace :as trace]))

(defn handler [{:keys [context principal path-params]}]
  (let [t (some->> (parse-uuid (:id path-params)) (trace/fetch (d/db (:app-conn context))))]
    (if (and t (or (:admin? principal) (= (:username principal) (:trace/username t))))
      {:status 200
       :body {:id (str (:trace/id t))
              :username (:trace/username t)
              :kind (name (:trace/kind t))
              :query (:trace/query t)
              :at (str (.toInstant ^java.util.Date (:trace/at t)))
              :stages (:trace/stages t)
              :degraded (mapv name (:trace/degraded t))
              :answer (:trace/answer t)}}
      (auth/error-response 404 "not_found" "找不到 trace。"))))
