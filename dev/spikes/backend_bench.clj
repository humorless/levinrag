;; Model backend benchmark (docs/spikes/llama-cpp-only.md): LM Studio vs
;; llama.cpp on the same machine. Run with bb from the project root, with
;; the backend's settings in .env (or exported):
;;
;;   bb dev/spikes/backend_bench.clj <label> <out.edn>
;;
;; Measures, against VLLM_CHAT_* and VLLM_EMBED_*:
;; - chat, streamed: time to first token (≈ prefill) and generation speed,
;;   3 cold prompts (a nonce first line defeats any prefix cache) and 3
;;   repeats of the last one (prefix cache);
;; - embeddings: throughput over the sample corpus' paragraphs in batches
;;   of 32 (as ingest does), 3 rounds; the first 20 vectors are saved so
;;   two backends can be compared (cosine).
(ns backend-bench
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(require '[replware.levinrag.bb.dotenv :as dotenv])

(def env (merge (dotenv/read-file ".env") (into {} (System/getenv))))
(def client (http/client {:version :http1.1}))
(defn- key-for [k] (or (get env (str "VLLM_" k "_API_KEY")) (get env "VLLM_API_KEY") "none"))

(defn- post [url k body & [opts]]
  (http/post url (merge {:client client
                         :headers {"Authorization" (str "Bearer " (key-for k))
                                   "Content-Type" "application/json"}
                         :body (json/generate-string body)
                         :timeout 600000}
                        opts)))

(def paragraphs
  (->> (fs/glob "corpus-sample" "**.{md,txt}")
       (map str) sort
       (mapcat #(str/split (slurp %) #"\n\s*\n"))
       (map str/trim)
       (remove #(or (str/blank? %) (str/starts-with? % "---")))
       vec))

(def context-text
  ;; ~2k tokens of Chinese policy text, like a real /ask prompt: the leave
  ;; policy first (the question is about it), then other paragraphs
  (let [s (str (slurp "corpus-sample/hr/leave.md") "\n\n" (str/join "\n\n" paragraphs))]
    (subs s 0 3200)))

(defn- chat-once [nonce]
  (let [body (merge {:model (get env "VLLM_CHAT_MODEL")
                     :temperature 0
                     :max_tokens 256
                     :stream true
                     :stream_options {:include_usage true}
                     :messages [{:role "system"
                                 :content "根據資料用繁體中文簡短回答，並標註引用。"}
                                {:role "user"
                                 :content (str nonce "\n資料：\n" context-text "\n\n問題：請逐條整理特休、病假、事假與婚喪假的規定，每條都附引用。")}]}
                    (some-> (get env "VLLM_CHAT_EXTRA_BODY") (json/parse-string true)))
        t0 (System/nanoTime)
        resp (post (str (get env "VLLM_CHAT_BASE_URL") "/chat/completions") "CHAT" body {:as :stream})
        first-token (atom nil)
        usage (atom nil)
        text (StringBuilder.)]
    (with-open [r (io/reader (:body resp))]
      (doseq [line (line-seq r)
              :when (str/starts-with? line "data: ")
              :let [data (subs line 6)]
              :when (not= data "[DONE]")
              :let [m (json/parse-string data true)
                    delta (get-in m [:choices 0 :delta])
                    piece (or (:content delta) (:reasoning_content delta))]]
        (when (and piece (seq piece) (nil? @first-token)) (reset! first-token (System/nanoTime)))
        (when (:content delta) (.append text (:content delta)))
        (when (:usage m) (reset! usage (:usage m)))))
    (let [t1 (System/nanoTime)
          ttft (/ (- @first-token t0) 1e9)
          total (/ (- t1 t0) 1e9)
          {:keys [prompt_tokens completion_tokens]} @usage]
      {:ttft-s ttft
       :total-s total
       :prompt-tokens prompt_tokens
       :completion-tokens completion_tokens
       :prefill-tok-s (when prompt_tokens (/ prompt_tokens ttft))
       :gen-tok-s (when (and completion_tokens (> total ttft)) (/ (dec completion_tokens) (- total ttft)))
       :answer-start (subs (str text) 0 (min 60 (count text)))
       :think-tag? (str/includes? (str text) "<think>")})))

(defn- embed [texts]
  (->> (post (str (get env "VLLM_EMBED_BASE_URL") "/embeddings") "EMBED"
             {:model (get env "VLLM_EMBED_MODEL")
              :input texts})
       :body (#(json/parse-string % true)) :data (sort-by :index) (mapv :embedding)))

(def embed-inputs
  ;; paragraphs the size of real chunks (the chunker caps them at ~500
  ;; estimated tokens); the sample corpus also has one 8k-token block that
  ;; ingest would never send whole
  (filterv #(<= (count %) 1200) paragraphs))

(defn- embed-round []
  (let [t0 (System/nanoTime)]
    (doseq [b (partition-all 32 embed-inputs)] (embed (vec b)))
    (/ (- (System/nanoTime) t0) 1e9)))

(let [[label out] *command-line-args*
      _ (println "backend:" label "| chat" (get env "VLLM_CHAT_BASE_URL") (get env "VLLM_CHAT_MODEL")
                 "| embed" (get env "VLLM_EMBED_BASE_URL") (get env "VLLM_EMBED_MODEL"))
      _ (chat-once "warm-up") ; model load / first-request effects
      cold (vec (for [i (range 3)] (chat-once (str "run-" label "-" (System/nanoTime) "-" i))))
      nonce (str "repeat-" (System/nanoTime))
      _ (chat-once nonce)
      warm (vec (for [_ (range 3)] (chat-once nonce)))
      _ (embed-round)
      embed-s (vec (repeatedly 3 embed-round))
      vectors (embed (vec (take 20 paragraphs)))
      median (fn [xs] (nth (sort xs) (quot (count xs) 2)))
      result {:label label
              :paragraphs (count embed-inputs)
              :cold cold
              :warm warm
              :embed-round-s embed-s
              :vectors vectors}]
  (spit out (pr-str result))
  (println (format "chat cold: ttft %.2fs, prefill %.0f tok/s (%d prompt tok), gen %.1f tok/s, total %.2fs"
                   (median (map :ttft-s cold)) (median (map :prefill-tok-s cold))
                   (:prompt-tokens (first cold)) (median (map :gen-tok-s cold)) (median (map :total-s cold))))
  (println (format "chat warm (same prompt): ttft %.2fs, total %.2fs"
                   (median (map :ttft-s warm)) (median (map :total-s warm))))
  (println (format "embed: %d paragraphs in %.2fs (median of 3)" (count embed-inputs) (median embed-s)))
  (println "answer starts:" (pr-str (:answer-start (first cold))) "| <think> in answer:" (some :think-tag? cold)))
