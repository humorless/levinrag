(ns hybridrag.api.search
  "POST /api/v1/search (SPEC.md §11): retrieval only, JSON in snake_case."
  (:require [clojure.tools.logging :as log]
            [hybridrag.auth.middleware :as auth]
            [hybridrag.retrieval.pipeline :as pipeline]
            [hybridrag.trace :as trace]))

(def request-schema
  [:map
   [:query [:string {:min 1
                     :max 1000}]]
   [:final_k {:optional true} [:int {:min 1
                                     :max 50}]]
   [:graph {:optional true} :boolean]])

(defn- passage-json [p]
  {:n (:n p)
   :doc_path (:doc/path p)
   :doc_title (:doc/title p)
   :section_trail (:section/trail p)
   :chunk_ids (:chunk-ids p)
   :char_range (:char-range p)
   :text (:text p)})

(defn- candidate-json [c]
  {:chunk_id (:chunk/id c)
   :doc_path (:doc/path c)
   :channels (:channels c)
   :rrf (:rrf c)
   :rerank (:rerank c)
   :selected (:selected? c)})

(defn handler
  [{:keys [context principal parameters errors]}]
  (if errors
    (auth/error-response 400 "invalid_request" "請求格式不正確：query 必填，長度 1–1000 字元。")
    (let [{:keys [query final_k graph]} (:body parameters)
          {:keys [app-conn search]} context
          opts (merge (:opts search)
                      (cond-> {}
                        final_k (assoc :final-k final_k)
                        (some? graph) (assoc :graph? graph)))]
      (try
        (let [res (pipeline/search search principal query opts)
              trace-id (trace/write! app-conn {:username (:username principal)
                                               :kind :search
                                               :query query
                                               :stages (:stages res)
                                               :degraded (:degraded res)})]
          (log/info "[SEARCH]" {:trace_id trace-id
                                :user (:username principal)
                                :passages (count (:passages res))
                                :degraded (:degraded res)})
          {:status 200
           :body {:passages (mapv passage-json (:passages res))
                  :candidates (mapv candidate-json (:candidates res))
                  :degraded (mapv #(case % :rerank-failed "rerank_failed" (name %)) (:degraded res))
                  :trace_id (str trace-id)}})
        (catch clojure.lang.ExceptionInfo e
          (if-let [endpoint (:llm/endpoint (ex-data e))]
            (do (log/warn "[SEARCH] dependency failed:" endpoint (ex-message e))
                (auth/error-response 503 "dependency_unavailable"
                                     (str "檢索服務暫時無法使用（" (name endpoint) "）。")))
            (throw e)))))))
