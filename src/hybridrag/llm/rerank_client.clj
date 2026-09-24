(ns hybridrag.llm.rerank-client
  (:require [hybridrag.config :as config]
            [hybridrag.llm.http :as http]))

(def ^:private connect-timeout-ms 2000)
(def ^:private default-read-timeout-ms 10000)

(defn rerank!
  "Rerank `documents` against `query`. Returns a vector of
   {:index i :relevance-score s}, best-first. Throws ex-info if the vLLM
   response is missing :results — SPEC.md §4.2 notes vLLM can return
   HTTP 200 with an error payload, so that shape has to be checked
   explicitly rather than trusted from the status code alone."
  ([query documents top-n] (rerank! (config/rerank-config) query documents top-n))
  ([{:keys [base-url path model api-key read-timeout-ms]
     :or {read-timeout-ms default-read-timeout-ms}} query documents top-n]
   (let [response (http/post-json!
                    {:url (str base-url path)
                     :api-key api-key
                     :body {:model model
                            :query query
                            :documents (vec documents)
                            :top_n top-n}
                     :connect-timeout-ms connect-timeout-ms
                     :read-timeout-ms read-timeout-ms
                     :endpoint-kw :rerank})]
     (if-let [results (:results response)]
       (mapv (fn [{:keys [index relevance_score]}]
               {:index index
                :relevance-score relevance_score})
             results)
       (throw (ex-info "vLLM rerank response missing :results"
                       {:llm/endpoint :rerank
                        :http/status 200
                        :llm/body-excerpt (subs (str response) 0 (min 500 (count (str response))))}))))))