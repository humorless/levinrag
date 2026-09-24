(ns hybridrag.llm.answer
  "Answer generation (SPEC.md §10): search → prompt → chat → strip
   <think> → validate [n] citations. No passages → the fixed no-evidence
   reply, without calling chat."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hybridrag.retrieval.pipeline :as pipeline]))

(def default-opts
  "SPEC.md §10.1 chat parameters; each can be overridden through opts."
  {:temperature 0.2
   :max-tokens 1024
   :extra-body nil})

(def no-evidence-message "在你有權限存取的資料中找不到相關內容。")
(def empty-answer-message "模型沒有產生回答，請稍後再試。")

(defn prompt
  "System prompt, read on every call so edits apply without a restart."
  []
  (slurp (io/resource "prompts/answer.md")))

(defn strip-think
  "Remove <think>…</think> blocks; an unclosed <think> drops the rest."
  [s]
  (-> s
      (str/replace #"(?s)<think>.*?</think>" "")
      (str/replace #"(?s)<think>.*\z" "")
      str/trim))

(def ^:private citation-re
  ;; [1] [1, 3] ［1］ 【1】, but not a Markdown link text [..](..)
  ;; \x28 is an open paren, spelled out so the pre-commit hook's raw
  ;; bracket count stays even
  #"[\[［【]\s*(\d+(?:\s*[,，、]\s*\d+)*)\s*[\]］】](?!\x28)")

(defn- numbers [group] (mapv parse-long (re-seq #"\d+" group)))

(defn parse-citations
  "Citations in `s` given `n` passages. Returns {:text :cited :invalid}:
   :text has every citation rewritten as [n] and out-of-range ones
   removed; :cited and :invalid are distinct, ascending."
  [s n]
  (let [valid? #(<= 1 % n)
        found (mapcat (comp numbers second) (re-seq citation-re s))]
    {:text (str/replace s citation-re
                        (fn [[_ group]] (apply str (map #(str "[" % "]") (filter valid? (numbers group))))))
     :cited (vec (sort (distinct (filter valid? found))))
     :invalid (vec (sort (distinct (remove valid? found))))}))

(defn messages
  "Chat messages: the system prompt, then <sources> (each passage headed
   `[n] 文件標題｜章節`) and the question (SPEC.md §10.1)."
  [system passages query]
  [{:role "system"
    :content system}
   {:role "user"
    :content (str "<sources>\n"
                  (str/join "\n\n" (for [{:keys [n text] :as p} passages]
                                     (str "[" n "] " (:doc/title p) "｜" (str/join " > " (:section/trail p)) "\n" text)))
                  "\n</sources>\n\n問題：" query)}])

(def ^:private not-found-re #"找不到|查無|沒有相關|(?i)not found|no relevant|cannot find")

(defn- content
  "The reply text; a response without one (e.g. HTTP 200 with an error
   payload) is a chat dependency failure."
  [resp]
  (let [c (get-in resp [:choices 0 :message :content])]
    (if (string? c)
      c
      (throw (ex-info "chat response missing choices[0].message.content"
                      {:llm/endpoint :chat
                       :http/status 200
                       :llm/body-excerpt (let [s (pr-str resp)] (subs s 0 (min 500 (count s))))})))))

(defn ask!
  "Search as `principal`, then answer from the passages. deps:
   {:retriever :rerank-fn :chat-fn, optional :search-fn (default
   pipeline/search)}; chat-fn is (fn [messages chat-opts] openai-response).

   Returns {:answer :citations [passage ..] :no-evidence? :degraded
   :candidates :stages}; :stages adds :generate and the answer flags
   (:uncited-answer, :empty-answer) to the pipeline's stages."
  [{:keys [search-fn chat-fn]
    :or {search-fn pipeline/search}
    :as deps} principal query opts]
  (let [res (search-fn deps principal query opts)
        passages (:passages res)
        base {:candidates (:candidates res)
              :degraded (:degraded res)}]
    (if (empty? passages)
      (assoc base
             :answer no-evidence-message
             :citations []
             :no-evidence? true
             :stages (:stages res))
      (let [t0 (System/nanoTime)
            resp (chat-fn (messages (prompt) passages query)
                          (merge default-opts (select-keys opts (keys default-opts))))
            ms (quot (- (System/nanoTime) t0) 1000000)
            {:keys [text cited invalid]} (parse-citations (strip-think (content resp)) (count passages))
            blank? (str/blank? text)
            by-n (into {} (map (juxt :n identity)) passages)
            flags (cond-> (get-in res [:stages :flags] #{})
                    blank? (conj :empty-answer)
                    (and (not blank?) (empty? cited) (not (re-find not-found-re text))) (conj :uncited-answer))]
        (assoc base
               :answer (if blank? empty-answer-message text)
               :citations (if blank? [] (mapv by-n cited))
               :no-evidence? false
               :stages (assoc (:stages res)
                              :flags flags
                              :generate {:ms ms
                                         :model (:model resp)
                                         :prompt-tokens (get-in resp [:usage :prompt_tokens])
                                         :completion-tokens (get-in resp [:usage :completion_tokens])
                                         :invalid-citations invalid}))))))
