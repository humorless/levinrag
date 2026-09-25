(ns hybridrag.config
  "Plain env-var config for standalone CLI tools (bb tasks) that must not
   boot the full Integrant/Ring system. See SPEC.md §5 for the full table."
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [jsonista.core :as json]))

(defn- env [k] (System/getenv k))
(defn- env-or [k default] (or (env k) default))
(defn- env-long [k default] (parse-long (env-or k (str default))))

(defn embed-config []
  {:base-url (env-or "VLLM_EMBED_BASE_URL" "http://localhost:8001/v1")
   :model (env-or "VLLM_EMBED_MODEL" "BAAI/bge-m3")
   :dims (parse-long (env-or "VLLM_EMBED_DIMS" "1024"))
   :read-timeout-ms (env-long "VLLM_EMBED_TIMEOUT_MS" 30000)
   :api-key (or (env "VLLM_EMBED_API_KEY") (env "VLLM_API_KEY"))})

(defn rerank-config []
  {:base-url (env-or "VLLM_RERANK_BASE_URL" "http://localhost:8002")
   :path (env-or "VLLM_RERANK_PATH" "/v1/rerank")
   :model (env-or "VLLM_RERANK_MODEL" "BAAI/bge-reranker-v2-m3")
   :read-timeout-ms (env-long "VLLM_RERANK_TIMEOUT_MS" 10000)
   :api-key (or (env "VLLM_RERANK_API_KEY") (env "VLLM_API_KEY"))})

(defn- json-env
  "Env var holding a JSON object, parsed with keyword keys; nil if unset.
   Anything else (bad JSON, an array, a string) throws."
  [k]
  (when-let [s (env k)]
    (let [v (try (json/read-value s json/keyword-keys-object-mapper)
                 (catch Exception e
                   (throw (ex-info (str k " is not valid JSON") {:env k} e))))]
      (when-not (map? v)
        (throw (ex-info (str k " must be a JSON object") {:env k})))
      v)))

(defn chat-config []
  {:base-url (env "VLLM_CHAT_BASE_URL")
   :model (env "VLLM_CHAT_MODEL")
   :extra-body (json-env "VLLM_CHAT_EXTRA_BODY")
   :read-timeout-ms (env-long "VLLM_CHAT_TIMEOUT_MS" 120000)
   :api-key (or (env "VLLM_CHAT_API_KEY") (env "VLLM_API_KEY"))})

(defn split-groups
  "Group names from a comma-separated string (ROOT_READ_GROUPS)."
  [s]
  (->> (str/split (or s "") #",")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn corpus-config
  "Ingestion settings (SPEC.md §5) for CLI tools that do not boot the
   Integrant system. The server reads the same env vars, with the same
   defaults, through the :hybridrag.config/paths key of config.edn."
  []
  {:data-dir (env-or "DATA_DIR" "data")
   :corpus-dir (env-or "CORPUS_DIR" "./corpus")
   :root-read-groups (split-groups (env "ROOT_READ_GROUPS"))})

;; config.edn's single source of data/corpus paths; other keys #ref it
(defmethod ig/init-key ::paths [_ paths] paths)
