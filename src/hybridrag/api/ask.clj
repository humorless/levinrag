(ns hybridrag.api.ask
  "POST /api/v1/ask (SPEC.md §10, §11): retrieval + generation, JSON in
   snake_case. Citations use the passage shape of /search."
  (:require [clojure.tools.logging :as log]
            [hybridrag.api.search :as search]
            [hybridrag.auth.middleware :as auth]
            [hybridrag.llm.answer :as answer]
            [hybridrag.trace :as trace]))

(def request-schema
  [:map
   [:query [:string {:min 1
                     :max 1000}]]
   [:final_k {:optional true} [:int {:min 1
                                     :max 50}]]
   [:debug {:optional true} :boolean]])

(defn handler
  [{:keys [context principal parameters errors]}]
  (if errors
    (auth/error-response 400 "invalid_request" "請求格式不正確：query 必填，長度 1–1000 字元。")
    (let [{:keys [query final_k debug]} (:body parameters)
          {:keys [app-conn search]} context
          opts (cond-> (:opts search)
                 final_k (assoc :final-k final_k))]
      (try
        (let [res (answer/ask! search principal query opts)
              trace-id (trace/write! app-conn {:username (:username principal)
                                               :kind :ask
                                               :query query
                                               :stages (:stages res)
                                               :degraded (:degraded res)
                                               :answer (:answer res)})]
          (log/info "[ASK]" {:trace_id trace-id
                             :user (:username principal)
                             :citations (count (:citations res))
                             :no_evidence (:no-evidence? res)
                             :degraded (:degraded res)})
          {:status 200
           :body (cond-> {:answer (:answer res)
                          :citations (mapv search/passage-json (:citations res))
                          :no_evidence (:no-evidence? res)
                          :degraded (search/degraded-json (:degraded res))
                          :trace_id (str trace-id)}
                   debug (assoc :candidates (mapv search/candidate-json (:candidates res))))})
        (catch clojure.lang.ExceptionInfo e
          (if-let [endpoint (:llm/endpoint (ex-data e))]
            (do (log/warn "[ASK] dependency failed:" endpoint (ex-message e))
                (auth/error-response 503 "dependency_unavailable"
                                     (str "問答服務暫時無法使用（" (name endpoint) "）。")))
            (throw e)))))))
