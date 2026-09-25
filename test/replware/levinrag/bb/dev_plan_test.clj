(ns replware.levinrag.bb.dev-plan-test
  "`bb dev:models` planning (docs/howto/dev.md): which model endpoints are
   local, what the local LM Studio + llama.cpp recipe must start, and the
   exact llama-server command."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [replware.levinrag.bb.dev-plan :as plan]))

(def lm-env
  {"VLLM_EMBED_BASE_URL" "http://localhost:1234/v1"
   "VLLM_EMBED_MODEL" "text-embedding-bge-m3"
   "VLLM_CHAT_BASE_URL" "http://localhost:1234/v1"
   "VLLM_CHAT_MODEL" "qwen/qwen3-8b"
   "VLLM_RERANK_BASE_URL" "http://localhost:8002"})

(deftest test-local-url
  (is (plan/local-url? "http://localhost:1234/v1"))
  (is (plan/local-url? "http://127.0.0.1:8002"))
  (is (not (plan/local-url? "https://gpu.example.com/v1")))
  (is (not (plan/local-url? nil))))

(deftest test-plan
  (testing "the documented Mac recipe: LM Studio for embed + chat, llama.cpp for rerank"
    (is (= {:lmstudio {:port 1234
                       :models [{:model "text-embedding-bge-m3"}
                                {:model "qwen/qwen3-8b"
                                 :context 8192}]}
            :llama {:port 8002
                    :hf "gpustack/bge-reranker-v2-m3-GGUF:Q8_0"}}
           (plan/models-plan lm-env))))
  (testing "LOCAL_* overrides"
    (let [p (plan/models-plan (assoc lm-env "LOCAL_CHAT_CONTEXT" "16384" "LOCAL_RERANK_HF" "x/y:Q4"))]
      (is (= 16384 (:context (second (get-in p [:lmstudio :models])))))
      (is (= "x/y:Q4" (get-in p [:llama :hf])))))
  (testing "remote endpoints are left alone; all remote means nothing to do"
    (is (= {:llama {:port 8002
                    :hf "gpustack/bge-reranker-v2-m3-GGUF:Q8_0"}}
           (plan/models-plan (assoc lm-env
                                    "VLLM_EMBED_BASE_URL" "https://gpu.example.com/v1"
                                    "VLLM_CHAT_BASE_URL" "https://gpu.example.com/v1"))))
    (is (= {} (plan/models-plan {"VLLM_EMBED_BASE_URL" "https://a/v1"
                                 "VLLM_CHAT_BASE_URL" "https://a/v1"
                                 "VLLM_RERANK_BASE_URL" "https://a"}))))
  (testing "unset embed / rerank URLs mean the code defaults (local vLLM ports), not this recipe"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"LM Studio"
                          (plan/models-plan (dissoc lm-env "VLLM_EMBED_BASE_URL")))))
  (testing "mistakes are errors, not guesses"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"同一個 port"
                          (plan/models-plan (assoc lm-env "VLLM_CHAT_BASE_URL" "http://localhost:1235/v1"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"LOCAL_CHAT_CONTEXT"
                          (plan/models-plan (assoc lm-env "LOCAL_CHAT_CONTEXT" "8k"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"VLLM_CHAT_MODEL"
                          (plan/models-plan (dissoc lm-env "VLLM_CHAT_MODEL"))))))

(deftest test-to-load
  (let [lms {:port 1234
             :models [{:model "text-embedding-bge-m3"}
                      {:model "qwen/qwen3-8b"
                       :context 8192}]}]
    (is (= [{:model "qwen/qwen3-8b"
             :context 8192}]
           (plan/to-load lms [{"identifier" "text-embedding-bge-m3"}])))
    (is (= [] (plan/to-load lms [{"identifier" "qwen/qwen3-8b"} {"identifier" "text-embedding-bge-m3"}])))))

(deftest test-llama-command
  (let [cmd (plan/llama-command {:port 8002
                                 :hf "gpustack/bge-reranker-v2-m3-GGUF:Q8_0"})]
    (is (= "llama-server" (first cmd)))
    (testing "the flags VLLM_SETUP.md documents (8192 context so full chunks fit)"
      (is (= "llama-server -hf gpustack/bge-reranker-v2-m3-GGUF:Q8_0 --reranking --port 8002 --host 127.0.0.1 -ub 8192 -b 8192 -c 8192 -np 1"
             (str/join " " cmd))))))
