(ns hybridrag.handlers
  (:require [datalevin.core :as d]
            [hybridrag.views :as views]
            [reitit-extras.core :as reitit-extras]
            [ring.util.response :as response]))

(defn default-handler
  [error-text status-code]
  (fn [_]
    (-> (views/error-page error-text)
        (reitit-extras/render-html)
        (response/status status-code))))

(defn home-handler
  [_]
  (reitit-extras/render-html views/home-page))

(defn- conn-ok?
  "True if `conn` is a live, queryable Datalevin connection."
  [conn]
  (try
    (some? (d/db conn))
    (catch Exception _ false)))

(defn health-handler
  "GET /api/v1/health — SPEC.md §11. Only checks the two Datalevin
   connections for now; vLLM reachability and index lag are added in T5.1."
  [request]
  (let [{:keys [index-conn app-conn]} (:context request)
        index-ok? (conn-ok? index-conn)
        app-ok? (conn-ok? app-conn)]
    (-> (response/response {:index_db (if index-ok? "ok" "down")
                            :app_db (if app-ok? "ok" "down")})
        (response/status (if (and index-ok? app-ok?) 200 503)))))
