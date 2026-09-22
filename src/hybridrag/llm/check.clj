(ns hybridrag.llm.check
  "Standalone CLI check for `bb vllm:check` (SPEC.md T0.2 AC). Calls all
   three real vLLM endpoints — requires them to actually be running."
  (:require [hybridrag.llm.chat :as chat]
            [hybridrag.llm.embed :as embed]
            [hybridrag.llm.rerank-client :as rerank]))

(defn- report [endpoint-kw f]
  (try
    (f)
    (println (format "[OK]   %s" (name endpoint-kw)))
    true
    (catch clojure.lang.ExceptionInfo e
      (println (format "[FAIL] %s: %s (status=%s)"
                       (name endpoint-kw) (ex-message e) (:http/status (ex-data e))))
      false)))

(defn run-check!
  [_]
  (let [ok? [(report :embed #(embed/embed-batch! ["測試 embedding"]))
             (report :rerank #(rerank/rerank! "測試" ["候選一" "候選二"] 2))
             (report :chat #(chat/complete! [{:role "user"
                                              :content "你好"}]
                                            {:temperature 0.0
                                             :max-tokens 16}))]]
    (when-not (every? true? ok?)
      (System/exit 1))))