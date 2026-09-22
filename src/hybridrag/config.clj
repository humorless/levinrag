(ns hybridrag.config
  "Plain env-var config for standalone CLI tools (bb tasks) that must not
   boot the full Integrant/Ring system. See SPEC.md §5 for the full table.")

(defn- env [k] (System/getenv k))
(defn- env-or [k default] (or (env k) default))

(defn embed-config []
  {:base-url (env-or "VLLM_EMBED_BASE_URL" "http://localhost:8001/v1")
   :model (env-or "VLLM_EMBED_MODEL" "BAAI/bge-m3")
   :dims (parse-long (env-or "VLLM_EMBED_DIMS" "1024"))
   :api-key (or (env "VLLM_EMBED_API_KEY") (env "VLLM_API_KEY"))})

(defn rerank-config []
  {:base-url (env-or "VLLM_RERANK_BASE_URL" "http://localhost:8002")
   :path (env-or "VLLM_RERANK_PATH" "/v1/rerank")
   :model (env-or "VLLM_RERANK_MODEL" "BAAI/bge-reranker-v2-m3")
   :api-key (or (env "VLLM_RERANK_API_KEY") (env "VLLM_API_KEY"))})

(defn chat-config []
  {:base-url (env "VLLM_CHAT_BASE_URL")
   :model (env "VLLM_CHAT_MODEL")
   :api-key (or (env "VLLM_CHAT_API_KEY") (env "VLLM_API_KEY"))})