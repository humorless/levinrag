(ns hybridrag.health
  "Checks behind GET /api/v1/health/live (DBs only, for the load
   balancer) and GET /api/v1/health (DBs, the three model endpoints and
   index lag, SPEC.md §11). Model probes are cached so the endpoint can
   be polled without loading the models."
  (:require [clojure.tools.logging :as log]
            [datalevin.core :as d]))

(def probe-ttl-ms 30000)

(defn db-ok?
  "True when `conn` is open and answers a real read. Datalevin asserts
   on a closed conn (AssertionError, not an Exception), hence Throwable."
  [conn]
  (try
    (boolean (and (some? conn)
                  (not (d/closed? conn))
                  (do (d/datoms (d/db conn) :eav) true)))
    (catch Throwable _ false)))

(defn index-lag
  "Unfinished secondary-index (fulltext/vector) work on `conn`, or nil
   when it cannot be read."
  [conn]
  (try
    (when-not (d/closed? conn)
      (:unfinished-count (d/wait-for-secondary-index conn {:timeout-ms 0})))
    (catch Throwable _ nil)))

(defn- probe [k f]
  (try
    (f)
    "ok"
    (catch Exception e
      (log/warn "[HEALTH]" (name k) "probe failed:" (ex-message e))
      "down")))

(defn probe-models
  "One minimal real request to each model endpoint."
  [{:keys [embed-fn rerank-fn chat-fn]}]
  {:embed (probe :embed #(embed-fn ["健康檢查"]))
   :rerank (probe :rerank #(rerank-fn "健康檢查" ["健康"] 1))
   :chat (probe :chat #(chat-fn [{:role "user"
                                  :content "ping"}]
                                {:temperature 0.0
                                 :max-tokens 1}))})

(defn cached-probe
  "The cached probe result when younger than `ttl-ms` at `now-ms`, else
   a fresh (probe-thunk) result, which is cached. Concurrent callers on
   an expired cache may each probe; no locking."
  [cache now-ms ttl-ms probe-thunk]
  (let [{:keys [at result]} @cache]
    (if (and at (< (- now-ms at) ttl-ms))
      result
      (let [r (probe-thunk)]
        (reset! cache {:at now-ms
                       :result r})
        r))))
