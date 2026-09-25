(ns replware.levinrag.bb.dev-plan-test
  "`bb dev:models` planning (docs/howto/dev.md): which model endpoints are
   local, which llama-server each one gets, and the exact command with the
   role's tuned flags (docs/spikes/llama-cpp-only.md)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [replware.levinrag.bb.dev-plan :as plan]))

(def local-env
  {"VLLM_EMBED_BASE_URL" "http://localhost:8001/v1"
   "VLLM_EMBED_MODEL" "bge-m3"
   "VLLM_CHAT_BASE_URL" "http://localhost:8003/v1"
   "VLLM_CHAT_MODEL" "qwen3-8b"
   "VLLM_RERANK_BASE_URL" "http://localhost:8002"
   "VLLM_RERANK_MODEL" "bge-reranker-v2-m3"})

(deftest test-local-url
  (is (plan/local-url? "http://localhost:1234/v1"))
  (is (plan/local-url? "http://127.0.0.1:8002"))
  (is (not (plan/local-url? "https://gpu.example.com/v1")))
  (is (not (plan/local-url? nil))))

(deftest test-plan
  (testing "the documented Mac recipe: three llama-servers, embed and chat before rerank"
    (is (= [{:role :embed
             :session "embed"
             :port 8001
             :hf "ggml-org/bge-m3-Q8_0-GGUF"
             :alias "bge-m3"
             :download "約 600 MB"}
            {:role :chat
             :session "chat"
             :port 8003
             :hf "Qwen/Qwen3-8B-GGUF:Q4_K_M"
             :alias "qwen3-8b"
             :download "約 5 GB"
             :context 8192}
            {:role :rerank
             :session "rerank"
             :port 8002
             :hf "gpustack/bge-reranker-v2-m3-GGUF:Q8_0"
             :alias "bge-reranker-v2-m3"
             :download "約 640 MB"}]
           (plan/models-plan local-env))))
  (testing "unset embed / rerank settings mean the code defaults, which are local"
    (is (= [["embed" 8001 "BAAI/bge-m3"] ["chat" 8003 "qwen3-8b"] ["rerank" 8002 "BAAI/bge-reranker-v2-m3"]]
           (map (juxt :session :port :alias)
                (plan/models-plan (select-keys local-env ["VLLM_CHAT_BASE_URL" "VLLM_CHAT_MODEL"]))))))
  (testing "LOCAL_* overrides"
    (let [p (plan/models-plan (assoc local-env "LOCAL_CHAT_CONTEXT" "16384" "LOCAL_CHAT_HF" "x/y:Q8_0"))]
      (is (= 16384 (:context (second p))))
      (is (= "x/y:Q8_0" (:hf (second p))))))
  (testing "remote endpoints are left alone; all remote means nothing to do"
    (is (= [:rerank] (map :role (plan/models-plan (assoc local-env
                                                         "VLLM_EMBED_BASE_URL" "https://gpu.example.com/v1"
                                                         "VLLM_CHAT_BASE_URL" "https://gpu.example.com/v1")))))
    (is (= [] (plan/models-plan {"VLLM_EMBED_BASE_URL" "https://a/v1"
                                 "VLLM_CHAT_BASE_URL" "https://a/v1"
                                 "VLLM_RERANK_BASE_URL" "https://a"}))))
  (testing "mistakes are errors, not guesses"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"不同的 port"
                          (plan/models-plan (assoc local-env "VLLM_CHAT_BASE_URL" "http://localhost:8001/v1"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"LOCAL_CHAT_CONTEXT"
                          (plan/models-plan (assoc local-env "LOCAL_CHAT_CONTEXT" "8k"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"VLLM_CHAT_MODEL"
                          (plan/models-plan (dissoc local-env "VLLM_CHAT_MODEL"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"port"
                          (plan/models-plan (assoc local-env "VLLM_CHAT_BASE_URL" "http://localhost/v1"))))))

(deftest test-llama-command
  (let [[embed chat rerank] (map #(str/join " " (plan/llama-command %)) (plan/models-plan local-env))]
    (testing "each role's tuned flags (docs/spikes/llama-cpp-only.md)"
      (is (= (str "llama-server -hf ggml-org/bge-m3-Q8_0-GGUF --port 8001 --host 127.0.0.1 -a bge-m3 -np 1"
                  " --embedding -c 2048 -b 2048 -ub 2048")
             embed))
      (is (= (str "llama-server -hf Qwen/Qwen3-8B-GGUF:Q4_K_M --port 8003 --host 127.0.0.1 -a qwen3-8b -np 1"
                  " -c 8192 --reasoning off --top-k 20 --top-p 0.8 --min-p 0")
             chat))
      (is (= (str "llama-server -hf gpustack/bge-reranker-v2-m3-GGUF:Q8_0 --port 8002 --host 127.0.0.1 -a bge-reranker-v2-m3 -np 1"
                  " --reranking -c 8192 -b 8192 -ub 8192")
             rerank)))))
