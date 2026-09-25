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

(defn answer-and-trace!
  "Answer `query` as `principal` and store the trace. `context` is the
   request context ({:app-conn :search}). Returns {:res :trace-id};
   dependency failures throw ex-info with :llm/endpoint. Shared by the
   API and the web page."
  [{:keys [app-conn search]} principal query opts]
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
    {:res res
     :trace-id trace-id}))

(defn handler
  [{:keys [context principal parameters errors]}]
  (if errors
    (auth/error-response 400 "invalid_request" "請求格式不正確：query 必填，長度 1–1000 字元。")
    (let [{:keys [query final_k debug]} (:body parameters)
          opts (cond-> (get-in context [:search :opts])
                 final_k (assoc :final-k final_k))]
      (try
        (let [{:keys [res trace-id]} (answer-and-trace! context principal query opts)]
          {:status 200
           :body (cond-> {:answer (:answer res)
                          :citations (mapv search/passage-json (:citations res))
                          :no_evidence (:no-evidence? res)
                          :degraded (search/degraded-json (:degraded res))
                          :trace_id (str trace-id)}
                   debug (assoc :candidates (mapv search/candidate-json (:candidates res))))})
        (catch clojure.lang.ExceptionInfo e
          (if (:llm/endpoint (ex-data e))
            (search/dependency-failure-response (:app-conn context) principal :ask query e "問答服務暫時無法使用")
            (throw e)))))))
