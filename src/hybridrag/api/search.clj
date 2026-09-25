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

(defn passage-json [p]
  {:n (:n p)
   :doc_path (:doc/path p)
   :doc_title (:doc/title p)
   :section_trail (:section/trail p)
   :chunk_ids (:chunk-ids p)
   :char_range (:char-range p)
   :text (:text p)})

(defn candidate-json [c]
  {:chunk_id (:chunk/id c)
   :doc_path (:doc/path c)
   :channels (:channels c)
   :rrf (:rrf c)
   :rerank (:rerank c)
   :selected (:selected? c)})

(defn degraded-json [degraded]
  (mapv #(case % :rerank-failed "rerank_failed" (name %)) degraded))

(defn dependency-failure-response
  "503 for the dependency ex-info `e` (it carries :llm/endpoint), after
   writing a failure trace; the body names the trace when one was
   written. `user-message` is the start of the Chinese error text."
  [app-conn principal kind query e user-message]
  (let [endpoint (:llm/endpoint (ex-data e))
        id (trace/write-failure! app-conn {:username (:username principal)
                                           :kind kind
                                           :query query
                                           :endpoint endpoint
                                           :message (ex-message e)})]
    (log/warn "dependency failed:" kind endpoint (ex-message e))
    (cond-> (auth/error-response 503 "dependency_unavailable" (str user-message "（" (name endpoint) "）。"))
      id (assoc-in [:body :trace_id] (str id)))))

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
                  :degraded (degraded-json (:degraded res))
                  :trace_id (str trace-id)}})
        (catch clojure.lang.ExceptionInfo e
          (if (:llm/endpoint (ex-data e))
            (dependency-failure-response app-conn principal :search query e "檢索服務暫時無法使用")
            (throw e)))))))
