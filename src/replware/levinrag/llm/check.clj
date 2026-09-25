(ns replware.levinrag.llm.check
  "Standalone CLI check for `bb vllm:check` (SPEC.md T0.2 AC). Calls all
   three real vLLM endpoints — requires them to actually be running."
  (:require [replware.levinrag.llm.chat :as chat]
            [replware.levinrag.llm.embed :as embed]
            [replware.levinrag.llm.rerank-client :as rerank]))

(def long-query "特休天數怎麼計算？")

(def long-document
  "1500 characters (the :rerank/max-chars cut, SPEC.md §9.6) whose
   opening answers long-query. A reranker served with a short context
   (e.g. max_model_len 512) truncates or rejects it, which on real
   queries only shows up as silently degraded ranking."
  (let [head "特休天數依年資計算：滿六個月三日，滿一年七日，滿二年十日，滿三年十四日，滿五年十五日，滿十年後每年加一日，最多三十日。"
        pad "員工請假應事先以書面或系統提出申請，主管核准後生效，特休未休完的天數依規定折發工資。"]
    (subs (apply str head (repeat 40 pad)) 0 1500)))

(defn long-probe-ok?
  "The long relevant document (index 0) got a finite score above the
   short unrelated one (index 1)."
  [results]
  (let [by-i (into {} (map (juxt :index :relevance-score)) results)
        a (by-i 0)
        b (by-i 1)]
    (boolean (and (number? a) (number? b)
                  (Double/isFinite (double a))
                  (> a b)))))

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
             (report :rerank-long
                     #(when-not (long-probe-ok? (rerank/rerank! long-query [long-document "今天午餐吃什麼"] 2))
                        (throw (ex-info "a 1500-character relevant document scored below an unrelated short one; check the reranker context length"
                                        {:llm/endpoint :rerank
                                         :http/status 200}))))
             (report :chat #(chat/complete! [{:role "user"
                                              :content "你好"}]
                                            {:temperature 0.0
                                             :max-tokens 16}))]]
    (when-not (every? true? ok?)
      (System/exit 1))))