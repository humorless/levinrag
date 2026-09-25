(ns replware.levinrag.bb.dev-plan
  "What `bb dev:models` must start for the local Mac recipe in
   VLLM_SETUP.md: LM Studio serves the embed and chat endpoints, llama.cpp's
   llama-server the rerank endpoint. Only endpoints whose URL is local are
   touched. Pure (no I/O): unit-tested on the JVM; the bb side is
   replware.levinrag.bb.dev."
  (:require [clojure.string :as str]))

(def ^:private defaults
  "The code's defaults when a URL is unset (replware.levinrag.config)."
  {"VLLM_EMBED_BASE_URL" "http://localhost:8001/v1"
   "VLLM_RERANK_BASE_URL" "http://localhost:8002"})

(def default-rerank-hf "gpustack/bge-reranker-v2-m3-GGUF:Q8_0")
(def default-chat-context 8192)

(defn- url [env k] (or (get env k) (get defaults k)))

(defn- host-port [u]
  (when u
    (when-let [[_ host port] (re-find #"^https?://([^/:]+)(?::(\d+))?" u)]
      [host (some-> port parse-long)])))

(defn local-url? [u]
  (contains? #{"localhost" "127.0.0.1" "::1" "[::1]"} (first (host-port u))))

(defn- required [env k]
  (or (not-empty (get env k))
      (throw (ex-info (str k " 未設定（.env）") {:env k}))))

(defn- chat-context [env]
  (if-let [s (not-empty (get env "LOCAL_CHAT_CONTEXT"))]
    (or (parse-long (str/trim s))
        (throw (ex-info (str "LOCAL_CHAT_CONTEXT 必須是整數：" s) {})))
    default-chat-context))

(defn models-plan
  "{:lmstudio {:port :models [{:model :context?}]} :llama {:port :hf}}, with
   only the parts whose endpoints are local; {} when none is."
  [env]
  (let [embed? (local-url? (url env "VLLM_EMBED_BASE_URL"))
        chat? (local-url? (get env "VLLM_CHAT_BASE_URL"))
        ports (distinct (keep identity [(when embed? (second (host-port (url env "VLLM_EMBED_BASE_URL"))))
                                        (when chat? (second (host-port (get env "VLLM_CHAT_BASE_URL"))))]))
        rerank-url (url env "VLLM_RERANK_BASE_URL")]
    (when (next ports)
      (throw (ex-info (str "LM Studio 同時提供 embedding 與 chat：VLLM_EMBED_BASE_URL 與 VLLM_CHAT_BASE_URL "
                           "必須是同一個 port（目前是 " (str/join " 與 " ports) "）") {})))
    (cond-> {}
      (or embed? chat?)
      (assoc :lmstudio {:port (first ports)
                        :models (cond-> []
                                  embed? (conj {:model (required env "VLLM_EMBED_MODEL")})
                                  chat? (conj {:model (required env "VLLM_CHAT_MODEL")
                                               :context (chat-context env)}))})

      (local-url? rerank-url)
      (assoc :llama {:port (second (host-port rerank-url))
                     :hf (or (not-empty (get env "LOCAL_RERANK_HF")) default-rerank-hf)}))))

(defn to-load
  "The planned LM Studio models not in `loaded` (parsed `lms ps --json`)."
  [{:keys [models]} loaded]
  (let [ids (set (map #(get % "identifier") loaded))]
    (vec (remove #(ids (:model %)) models))))

(defn llama-command
  "llama-server argv, flags as in VLLM_SETUP.md: an 8192-token context and
   batch so a full chunk plus query fits (`bb vllm:check`'s rerank-long)."
  [{:keys [port hf]}]
  ["llama-server" "-hf" hf "--reranking" "--port" (str port) "--host" "127.0.0.1"
   "-ub" "8192" "-b" "8192" "-c" "8192" "-np" "1"])
