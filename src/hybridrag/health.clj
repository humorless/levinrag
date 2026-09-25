(ns hybridrag.health
  "Checks behind GET /api/v1/health/live (DBs only, for the load
   balancer) and GET /api/v1/health (DBs, the three model endpoints and
   index lag, SPEC.md §11). Model probes are cached so the endpoint can
   be polled without loading the models."
  (:require [clojure.tools.logging :as log]
            [datalevin.core :as d]))

(def probe-ttl-ms 30000)

;; a model slower than this to answer a one-token probe is reported down
(def probe-timeout-ms 5000)

(defn db-ok?
  "True when `conn` is open and answers a real read of at most one datom
   (an unbounded d/datoms is an eager vector of the whole DB). Datalevin
   asserts on a closed conn (AssertionError, not an Exception), hence
   Throwable."
  [conn]
  (try
    (boolean (and (some? conn)
                  (not (d/closed? conn))
                  (do (d/seek-datoms (d/db conn) :eav nil nil nil 1) true)))
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

(defn- chat-probe [chat-fn]
  (let [resp (chat-fn [{:role "user"
                        :content "ping"}]
                      {:temperature 0.0
                       :max-tokens 1})]
    ;; HTTP 200 with an error payload has no message
    (when-not (map? (get-in resp [:choices 0 :message]))
      (throw (ex-info "chat response has no choices[0].message" {:llm/endpoint :chat})))))

(defn probe-models
  "One minimal real request to each model endpoint, in parallel; an
   endpoint that has not answered within `timeout-ms` of the start is
   \"down\" (its request is left to finish or time out on its own)."
  ([fns] (probe-models fns probe-timeout-ms))
  ([{:keys [embed-fn rerank-fn chat-fn]} timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)
         running {:embed (future (probe :embed #(embed-fn ["健康檢查"])))
                  :rerank (future (probe :rerank #(rerank-fn "健康檢查" ["健康"] 1)))
                  :chat (future (probe :chat #(chat-probe chat-fn)))}]
     (into {} (for [[k f] running]
                [k (deref f (max 0 (- deadline (System/currentTimeMillis))) "down")])))))

(defn cached-probe
  "The cached probe result when younger than `ttl-ms` (by `now-fn`),
   else a fresh (probe-thunk) result, stamped when it finishes. Callers
   arriving while a round runs wait for that round instead of starting
   their own, so a slow model gets at most one probe per window."
  [cache now-fn ttl-ms probe-thunk]
  (let [{:keys [at result]} @cache]
    (if (and at (< (- (now-fn) at) ttl-ms))
      result
      (let [mine (promise)
            [before _] (swap-vals! cache #(if (:pending %) % (assoc % :pending mine)))]
        (if-let [theirs (:pending before)]
          @theirs
          (try
            (let [r (probe-thunk)]
              (reset! cache {:at (now-fn)
                             :result r})
              (deliver mine r)
              r)
            (finally
              ;; a throwing probe must not leave waiters blocked forever
              (when-not (realized? mine)
                (swap! cache dissoc :pending)
                (deliver mine nil)))))))))
