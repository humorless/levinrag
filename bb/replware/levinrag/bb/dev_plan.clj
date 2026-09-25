(ns replware.levinrag.bb.dev-plan
  "What `bb dev:models` must start for the local Mac recipe in
   VLLM_SETUP.md: one llama.cpp `llama-server` per endpoint (embed, chat,
   rerank), each in its own tmux session, with flags tuned for its role
   (docs/spikes/llama-cpp-only.md). Only endpoints whose URL is local are
   touched. Pure (no I/O): unit-tested on the JVM; the bb side is
   replware.levinrag.bb.dev."
  (:require [clojure.string :as str]))

(def ^:private url-defaults
  "The code's defaults when a URL or model is unset (replware.levinrag.config)."
  {"VLLM_EMBED_BASE_URL" "http://localhost:8001/v1"
   "VLLM_RERANK_BASE_URL" "http://localhost:8002"
   "VLLM_EMBED_MODEL" "BAAI/bge-m3"
   "VLLM_RERANK_MODEL" "BAAI/bge-reranker-v2-m3"})

(def default-chat-context 8192)

(def roles
  "In start order: on a 16 GB Mac, load embed and chat before the reranker."
  [{:role :embed
    :session "embed"
    :url "VLLM_EMBED_BASE_URL"
    :model "VLLM_EMBED_MODEL"
    :hf-env "LOCAL_EMBED_HF"
    :hf "ggml-org/bge-m3-Q8_0-GGUF"
    :download "約 600 MB"}
   {:role :chat
    :session "chat"
    :url "VLLM_CHAT_BASE_URL"
    :model "VLLM_CHAT_MODEL"
    :hf-env "LOCAL_CHAT_HF"
    :hf "Qwen/Qwen3-8B-GGUF:Q4_K_M"
    :download "約 5 GB"}
   {:role :rerank
    :session "rerank"
    :url "VLLM_RERANK_BASE_URL"
    :model "VLLM_RERANK_MODEL"
    :hf-env "LOCAL_RERANK_HF"
    :hf "gpustack/bge-reranker-v2-m3-GGUF:Q8_0"
    :download "約 640 MB"}])

(defn- env-val [env k] (or (not-empty (get env k)) (get url-defaults k)))

(defn- host-port [u]
  (when u
    (when-let [[_ host port] (re-find #"^https?://([^/:]+)(?::(\d+))?" u)]
      [host (some-> port parse-long)])))

(defn local-url? [u]
  (contains? #{"localhost" "127.0.0.1" "::1" "[::1]"} (first (host-port u))))

(defn- chat-context [env]
  (if-let [s (not-empty (get env "LOCAL_CHAT_CONTEXT"))]
    (or (parse-long (str/trim s))
        (throw (ex-info (str "LOCAL_CHAT_CONTEXT 必須是整數：" s) {})))
    default-chat-context))

(defn models-plan
  "[{:role :session :port :hf :alias :download (:context)}] for the local
   endpoints, in start order; [] when none is local."
  [env]
  (let [plan (vec (for [{:keys [url model hf-env hf]
                         :as r} roles
                        :let [u (env-val env url)]
                        :when (local-url? u)]
                    (cond-> (-> (select-keys r [:role :session :download])
                                (assoc :port (or (second (host-port u))
                                                 (throw (ex-info (str url " 要寫明 port：" u) {})))
                                       :hf (or (not-empty (get env hf-env)) hf)
                                       :alias (or (env-val env model)
                                                  (throw (ex-info (str model " 未設定（.env）") {})))))
                      (= :chat (:role r)) (assoc :context (chat-context env)))))
        dup (->> plan (group-by :port) (filter #(next (val %))) first)]
    (when dup
      (throw (ex-info (str "每個端點要用不同的 port：" (str/join "、" (map (comp name :role) (val dup)))
                           " 都設成 " (key dup)) {})))
    plan))

(defn llama-command
  "llama-server argv for one planned endpoint. Why each flag:
   - all: one slot (-np 1) — a single developer; more slots split -c
     between them. -ngl and -fa stay auto (all layers on Metal). -a makes
     the server answer to the model name the app sends.
   - embed: --embedding; the model's own pooling (vectors equal LM Studio's,
     cosine 1.0); -c/-b/-ub 2048 — a non-causal model needs each input in
     one ubatch and the chunker caps chunks at ~500 estimated tokens
     (8192 cost 8 GB for no gain).
   - chat: -c 8192 holds ~6000 tokens of passages + 1024 output;
     --reasoning off (Qwen3 thinking) server-side; Qwen's non-thinking
     sampling (top-k 20, top-p 0.8, min-p 0) — the app sends temperature
     0.2 itself; no presence penalty (short answers that must repeat
     numbers and [n] citations).
   - rerank: --reranking; -c/-b/-ub 8192 so a full chunk plus the query
     fits (bb vllm:check's rerank-long)."
  [{:keys [role hf port context]
    model-alias :alias}]
  (into ["llama-server" "-hf" hf "--port" (str port) "--host" "127.0.0.1" "-a" model-alias "-np" "1"]
        (case role
          :embed ["--embedding" "-c" "2048" "-b" "2048" "-ub" "2048"]
          :chat ["-c" (str context) "--reasoning" "off" "--top-k" "20" "--top-p" "0.8" "--min-p" "0"]
          :rerank ["--reranking" "-c" "8192" "-b" "8192" "-ub" "8192"])))
