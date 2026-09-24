(ns hybridrag.config
  "Plain env-var config for standalone CLI tools (bb tasks) that must not
   boot the full Integrant/Ring system. See SPEC.md §5 for the full table."
  (:require [clojure.string :as str]
            [jsonista.core :as json]))

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

(defn- json-env
  "Env var holding a JSON object, parsed with keyword keys; nil if unset."
  [k]
  (when-let [s (env k)]
    (try (json/read-value s json/keyword-keys-object-mapper)
         (catch Exception e
           (throw (ex-info (str k " is not valid JSON") {:env k} e))))))

(defn chat-config []
  {:base-url (env "VLLM_CHAT_BASE_URL")
   :model (env "VLLM_CHAT_MODEL")
   :extra-body (json-env "VLLM_CHAT_EXTRA_BODY")
   :api-key (or (env "VLLM_CHAT_API_KEY") (env "VLLM_API_KEY"))})

(defn corpus-config
  "Ingestion settings (SPEC.md §5). ROOT_READ_GROUPS is comma-separated."
  []
  {:data-dir (env-or "DATA_DIR" "./data")
   :corpus-dir (env-or "CORPUS_DIR" "./corpus")
   :root-read-groups (->> (str/split (env-or "ROOT_READ_GROUPS" "") #",")
                          (map str/trim)
                          (remove str/blank?)
                          vec)})
