(ns hybridrag.handlers
  (:require [hybridrag.health :as health]
            [hybridrag.views :as views]
            [reitit-extras.core :as reitit-extras]
            [ring.util.response :as response]))

(defn default-handler
  [error-text status-code]
  (fn [_]
    (-> (views/error-page error-text)
        (reitit-extras/render-html)
        (response/status status-code))))

(defn- db-checks [{:keys [index-conn app-conn]}]
  {:index_db (if (health/db-ok? index-conn) "ok" "down")
   :app_db (if (health/db-ok? app-conn) "ok" "down")})

(defn- health-response [checks extra]
  (let [ok? (every? #{"ok"} (vals checks))]
    (-> (response/response (merge {:status (if ok? "ok" "degraded")
                                   :checks checks}
                                  extra))
        (response/status (if ok? 200 503)))))

(defn live-handler
  "GET /api/v1/health/live — both DBs only; the load balancer check, so
   a model restart does not take search and the doc viewer offline."
  [request]
  (health-response (db-checks (:context request)) nil))

(defn health-handler
  "GET /api/v1/health — SPEC.md §11: DBs, the three model endpoints
   (parallel, health/probe-timeout-ms each, cached for
   health/probe-ttl-ms, one round at a time) and index lag, which is
   reported but never a failure (it is normal while ingest runs)."
  [{:keys [context]}]
  (let [models (health/cached-probe (:health-cache context)
                                    #(System/currentTimeMillis)
                                    health/probe-ttl-ms
                                    #(health/probe-models (:search context)))]
    (health-response (merge (db-checks context) models)
                     {:index_lag (health/index-lag (:index-conn context))})))
