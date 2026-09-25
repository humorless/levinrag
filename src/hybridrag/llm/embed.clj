(ns hybridrag.llm.embed
  (:require [hybridrag.config :as config]
            [hybridrag.llm.http :as http]))

(def ^:private connect-timeout-ms 2000)
(def ^:private default-batch-size 32)

(defn embed-batch!
  "Embed up to `default-batch-size` strings in one HTTP call. Returns a
   vector of float vectors in the same order as `texts`."
  ([texts] (embed-batch! (config/embed-config) texts))
  ([{:keys [base-url model api-key read-timeout-ms]
     :or {read-timeout-ms 30000}} texts]
   (let [response (http/post-json!
                    {:url (str base-url "/embeddings")
                     :api-key api-key
                     :body {:model model
                            :input (vec texts)}
                     :connect-timeout-ms connect-timeout-ms
                     :read-timeout-ms read-timeout-ms
                     :endpoint-kw :embed})
         vectors (mapv :embedding (:data response))]
     ;; SPEC.md §4.2: HTTP 200 can carry an error payload; any other
     ;; shape would reach the index as an empty or wrong vector
     (when-not (and (= (count texts) (count vectors))
                    (every? #(and (sequential? %) (seq %) (every? number? %)) vectors))
       (throw (ex-info "vLLM embed response has no embedding per input"
                       {:llm/endpoint :embed
                        :http/status 200
                        :llm/body-excerpt (let [s (pr-str response)] (subs s 0 (min 500 (count s))))})))
     vectors)))

(defn embed-all!
  "Embed any number of strings, batching at `batch-size`."
  ([texts] (embed-all! (config/embed-config) texts default-batch-size))
  ([cfg texts batch-size]
   (vec (mapcat #(embed-batch! cfg %) (partition-all batch-size texts)))))