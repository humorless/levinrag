# Phase 3: Generation (T3.1–T3.2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** principal + query → the Phase 2 search pipeline → an answer from
the chat model with validated `[n]` citations, served as
`POST /api/v1/ask` with a trace.

**Architecture:** `hybridrag.llm.answer` is pure orchestration over
injected functions (`pipeline/search` deps + a `:chat-fn`), so it is
tested with a stub chat; `hybridrag.api.ask` is the Ring handler, a
sibling of `api.search`. The chat fn joins the existing
`:hybridrag.retrieval.system/search` component map.

**Tech Stack:** Clojure 1.12, Datalevin 1.1.0, reitit + malli, hato
(existing `llm.http`), clojure.test.

**Spec:** `SPEC.md` §10 (generation), §11 (`/ask`), §14 (trace
`:generate`), §4.2–4.3 (chat contract, errors), §17 Phase 3, §18.2–18.3.

## Global Constraints

- Prompt lives in `resources/prompts/answer.md`, adjustable without code
  changes (§10.1).
- Chat params `temperature 0.2`, `max_tokens 1024`, both configurable
  (§10.1); `:chat/extra-body` merged verbatim into the request (§4.2).
- `<think>…</think>` blocks are stripped from the answer (§4.2).
- No-evidence message, exact text: 「在你有權限存取的資料中找不到相關內容。」,
  with `no_evidence: true`, and chat is **not** called (§10.3).
- `/ask` body `{query, final_k?, debug?}`; response
  `{answer, citations, no_evidence, degraded, trace_id}`, candidates only
  when `debug=true` (§11). `query` 1–1000 chars. snake_case JSON. Errors
  `{"error": {"code", "message"}}`.
- Trace: `:generate {:ms :model :prompt-tokens :completion-tokens}`, plus
  `:invalid-citations`; `:uncited-answer` flag (§10.2, §14). No chunk text
  in stages; the answer goes in `:trace/answer`.
- API keys never in logs or traces (§4.3).
- Real-model tests are tagged `:vllm` and skip when `VLLM_CHAT_BASE_URL`
  is unset (§18.2).
- Run tests via nREPL (see `CLAUDE.md`); before each commit
  `clojure -X:jvm-opts:test` and `clj-kondo --lint src test`.

## Review Focus

1. **Chat returns only a `<think>` block, or an unclosed one** (thinking
   cut off by `max_tokens`) → the user should get a clear message, not an
   empty answer. → T3.1 `test-empty-after-think`: fixed message
   「模型沒有產生回答，請稍後再試。」 and flag `:empty-answer`.
2. **Bracketed non-citations** — Markdown links `[文件](x.md)`, `[註]`,
   and numbers far out of range such as `[2024]` → links and non-numeric
   brackets untouched; out-of-range integers removed and logged as
   invalid. → T3.1 `test-parse-citations`.
3. **Chat backend answers HTTP 200 with an error payload** (LM Studio for
   an unloaded model) → 503 `dependency_unavailable`, not a 500 or an
   empty 200. → T3.1 `test-chat-malformed` + T3.2 `test-ask-errors`.
4. **User with no readable documents / nothing relevant** → the fixed
   no-evidence reply, chat never called. → T3.2 `test-ask-no-evidence`
   (chat-fn that throws if called).
5. **Restricted document text reaching the answer** (§18.3) → citations
   come only from the ACL-filtered passages, so a restricted doc never
   appears in `citations` or `candidates`. → T3.2 `test-ask-never-leaks`.

---

### Task 1 (T3.1): answer module

**Files:**
- Create: `resources/prompts/answer.md`
- Create: `src/hybridrag/llm/answer.clj`
- Modify: `src/hybridrag/llm/chat.clj` (docstring only: points at
  `hybridrag.llm.answer`)
- Test: `test/hybridrag/llm/answer_test.clj`

**Interfaces:**
- Consumes: `hybridrag.retrieval.pipeline/search` —
  `(search {:retriever :rerank-fn} principal query opts)` →
  `{:passages [{:n :doc/path :doc/title :section/trail :chunk-ids :char-range :text}] :candidates :degraded :flags :stages}`.
- Produces:
  - `(answer/strip-think s)` → string.
  - `(answer/parse-citations s n-passages)` →
    `{:text s' :cited [n ...] :invalid [n ...]}` (`:cited` and `:invalid`
    distinct, ascending; `:text` has citations normalized to `[n]` and invalid ones
    removed).
  - `(answer/messages prompt passages query)` → `[{:role "system" ..} {:role "user" ..}]`.
  - `(answer/ask! deps principal query opts)` where deps =
    `{:retriever :rerank-fn :chat-fn}` and `chat-fn` is
    `(fn [messages {:keys [temperature max-tokens extra-body]}] raw-openai-response)`.
    Returns `{:answer str :citations [passage ...] :no-evidence? bool
    :degraded #{..} :candidates [..] :stages {..}}` where `:stages` is the
    pipeline's stages plus `:generate` and updated `:flags`.
  - `answer/no-evidence-message`, `answer/default-opts`
    (`{:temperature 0.2 :max-tokens 1024 :extra-body nil}`).

- [ ] **Step 1: Prompt resource** — `resources/prompts/answer.md`, the
  system message, §10.1's four rules verbatim in meaning:

```markdown
你是企業內部知識庫的問答助理。請遵守：

1. 只能根據 <sources> 中提供的資料回答，不要用資料以外的知識補充事實。
2. 每個事實性陳述後標註來源編號，格式為 [n]，可以有多個，例如 [1][3]。
3. 資料不足以回答時，明確說明「資料中找不到」哪一部分，不要猜測。
4. 預設以繁體中文回答；使用者以其他語言提問時，用該語言回答。
```

- [ ] **Step 2: Failing tests** — `test/hybridrag/llm/answer_test.clj`:

```clojure
(ns hybridrag.llm.answer-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hybridrag.llm.answer :as answer]))

(deftest test-strip-think
  (is (= "答案" (answer/strip-think "<think>想一想\n很久</think>\n\n答案")))
  (is (= "A B" (answer/strip-think "A <think>x</think>B")))
  (is (= "" (answer/strip-think "<think>沒寫完的思考")) "unclosed block drops the rest")
  (is (= "無標籤" (answer/strip-think "無標籤"))))

(deftest test-parse-citations
  (testing "valid, grouped, comma lists, full-width brackets"
    (let [{:keys [text cited invalid]} (answer/parse-citations "甲[1]。乙[3][1]。丙[2, 3]。丁［2］、【1】" 3)]
      (is (= [1 2 3] cited))
      (is (= [] invalid))
      (is (= "甲[1]。乙[3][1]。丙[2][3]。丁[2]、[1]" text))))
  (testing "out-of-range numbers are removed and reported"
    (let [{:keys [text cited invalid]} (answer/parse-citations "甲[1][4]。乙[0]。丙[2024]" 2)]
      (is (= [1] cited))
      (is (= [0 4 2024] invalid))
      (is (= "甲[1]。乙。丙" text))))
  (testing "links and non-numeric brackets are untouched"
    (let [s "見[文件](hr/leave.md)與[註]、[a1]"]
      (is (= {:text s :cited [] :invalid []} (answer/parse-citations s 3))))))

(def passages
  [{:n 1 :doc/path "hr/leave.md" :doc/title "請假規定" :section/trail ["請假規定" "特休"] :chunk-ids ["hr/leave.md::1"] :text "特休依年資計算。"}
   {:n 2 :doc/path "hr/onboard.md" :doc/title "新人報到" :section/trail ["新人報到"] :chunk-ids ["hr/onboard.md::0"] :text "報到當天領取識別證。"}])

(deftest test-messages
  (let [[sys user] (answer/messages "SYSTEM" passages "特休怎麼算？")]
    (is (= {:role "system" :content "SYSTEM"} sys))
    (is (= "user" (:role user)))
    (is (str/includes? (:content user) "<sources>\n[1] 請假規定｜請假規定 > 特休\n特休依年資計算。"))
    (is (str/includes? (:content user) "[2] 新人報到｜新人報到\n報到當天領取識別證。"))
    (is (str/ends-with? (:content user) "</sources>\n\n問題：特休怎麼算？"))))

(defn- chat-reply [content]
  (fn [_ _] {:model "stub" :choices [{:message {:content content}}]
             :usage {:prompt_tokens 100 :completion_tokens 20}}))

(defn- deps-with [search-result chat-fn]
  ;; ask! calls pipeline/search; tests redefine it to a canned result
  {:search-fn (fn [& _] search-result) :chat-fn chat-fn})

(def found {:passages passages :candidates [] :degraded #{} :flags #{} :stages {:flags #{}}})

(deftest test-ask-answer-and-citations
  (let [calls (atom [])
        res (answer/ask! (deps-with found (fn [m o] (swap! calls conj [m o])
                                            ((chat-reply "<think>嗯</think>特休依年資[1]，另見[9]。") m o)))
                         {:username "alice"} "特休？" {})]
    (is (= "特休依年資[1]，另見。" (:answer res)))
    (is (= ["hr/leave.md"] (map :doc/path (:citations res))))
    (is (false? (:no-evidence? res)))
    (is (= {:temperature 0.2 :max-tokens 1024 :extra-body nil} (second (first @calls))))
    (is (= {:model "stub" :prompt-tokens 100 :completion-tokens 20 :invalid-citations [9]}
           (dissoc (get-in res [:stages :generate]) :ms)))
    (is (not (contains? (get-in res [:stages :flags]) :uncited-answer)))))

(deftest test-ask-uncited-and-not-found
  (is (contains? (get-in (answer/ask! (deps-with found (chat-reply "特休依年資計算。")) {} "q" {}) [:stages :flags])
                 :uncited-answer))
  (is (not (contains? (get-in (answer/ask! (deps-with found (chat-reply "資料中找不到加班費的規定。")) {} "q" {}) [:stages :flags])
                      :uncited-answer))))

(deftest test-ask-no-evidence
  (let [res (answer/ask! (deps-with (assoc found :passages []) (fn [& _] (throw (ex-info "chat called" {}))))
                         {} "q" {})]
    (is (true? (:no-evidence? res)))
    (is (= answer/no-evidence-message (:answer res)))
    (is (= [] (:citations res)))
    (is (nil? (get-in res [:stages :generate])))))

(deftest test-empty-after-think
  (let [res (answer/ask! (deps-with found (chat-reply "<think>想到一半")) {} "q" {})]
    (is (= answer/empty-answer-message (:answer res)))
    (is (contains? (get-in res [:stages :flags]) :empty-answer))
    (is (= [] (:citations res)))))

(deftest test-chat-malformed
  (doseq [bad [{:error "model not loaded"} {:choices []} {:choices [{:message {:content nil}}]}]]
    (let [e (try (answer/ask! (deps-with found (fn [_ _] bad)) {} "q" {}) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :chat (:llm/endpoint (ex-data e))) (pr-str bad)))))
```

  `ask!` takes `:search-fn` in deps (default `pipeline/search`) so it can
  be tested without an index; the API passes the real one implicitly.

- [ ] **Step 3: Run, expect failure** (namespace missing):
  `(require 'hybridrag.llm.answer-test :reload)` → FileNotFound.

- [ ] **Step 4: Implement** `src/hybridrag/llm/answer.clj`:

```clojure
(ns hybridrag.llm.answer
  "Answer generation (SPEC.md §10): search → prompt → chat → strip
   <think> → validate [n] citations. No passages → fixed reply, no chat."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hybridrag.retrieval.pipeline :as pipeline]))

(def default-opts {:temperature 0.2 :max-tokens 1024 :extra-body nil})
(def no-evidence-message "在你有權限存取的資料中找不到相關內容。")
(def empty-answer-message "模型沒有產生回答，請稍後再試。")

(defn prompt
  "System prompt, read on every call so edits apply without a restart."
  []
  (slurp (io/resource "prompts/answer.md")))

(defn strip-think [s]
  (-> s
      (str/replace #"(?s)<think>.*?</think>" "")
      (str/replace #"(?s)<think>.*\z" "")
      str/trim))

(def ^:private citation-re
  ;; [1] [1, 3] ［1］ 【1】 — not followed by "(" (Markdown link)
  #"[\[［【]\s*(\d+(?:\s*[,，、]\s*\d+)*)\s*[\]］】](?!\()")

(defn parse-citations [s n]
  (let [nums (fn [g] (mapv parse-long (re-seq #"\d+" g)))
        valid? #(<= 1 % n)
        found (mapcat (comp nums second) (re-seq citation-re s))]
    {:text (str/replace s citation-re
                        (fn [[_ g]] (apply str (map #(str "[" % "]") (filter valid? (nums g))))))
     :cited (vec (sort (distinct (filter valid? found))))
     :invalid (vec (sort (distinct (remove valid? found))))}))

(defn messages [system passages query]
  [{:role "system" :content system}
   {:role "user"
    :content (str "<sources>\n"
                  (str/join "\n\n" (for [{:keys [n text] :as p} passages]
                                     (str "[" n "] " (:doc/title p) "｜" (str/join " > " (:section/trail p)) "\n" text)))
                  "\n</sources>\n\n問題：" query)}])

(def ^:private not-found-re #"找不到|查無|沒有相關|not found|no relevant|cannot find")

(defn- content [resp]
  (let [c (get-in resp [:choices 0 :message :content])]
    (if (string? c)
      c
      (throw (ex-info "chat response missing choices[0].message.content"
                      {:llm/endpoint :chat :http/status 200
                       :llm/body-excerpt (let [s (pr-str resp)] (subs s 0 (min 500 (count s))))})))))

(defn ask!
  [{:keys [search-fn chat-fn] :or {search-fn pipeline/search} :as deps} principal query opts]
  (let [chat-opts (merge default-opts (select-keys opts (keys default-opts)))
        res (search-fn deps principal query opts)
        passages (:passages res)
        base {:candidates (:candidates res) :degraded (:degraded res)}]
    (if (empty? passages)
      (assoc base :answer no-evidence-message :citations [] :no-evidence? true :stages (:stages res))
      (let [t0 (System/nanoTime)
            resp (chat-fn (messages (prompt) passages query) chat-opts)
            ms (quot (- (System/nanoTime) t0) 1000000)
            raw (strip-think (content resp))
            {:keys [text cited invalid]} (parse-citations raw (count passages))
            empty? (str/blank? text)
            by-n (into {} (map (juxt :n identity)) passages)
            flags (cond-> (get-in res [:stages :flags] #{})
                    empty? (conj :empty-answer)
                    (and (not empty?) (empty? cited) (not (re-find not-found-re text))) (conj :uncited-answer))]
        (assoc base
               :answer (if empty? empty-answer-message text)
               :citations (if empty? [] (mapv by-n cited))
               :no-evidence? false
               :stages (assoc (:stages res)
                              :flags flags
                              :generate {:ms ms
                                         :model (:model resp)
                                         :prompt-tokens (get-in resp [:usage :prompt_tokens])
                                         :completion-tokens (get-in resp [:usage :completion_tokens])
                                         :invalid-citations invalid}))))))
```

  (Shadowing `empty?` is a lint warning — name the local `blank?`.)

- [ ] **Step 5: Run tests, expect PASS**; also run the full suite and lint.
- [ ] **Step 6: Commit** — `T3.1: answer module (prompt, <think> strip, citation validation, no-evidence path)`.

---

### Task 2 (T3.2): `POST /api/v1/ask`

**Files:**
- Create: `src/hybridrag/api/ask.clj`
- Modify: `src/hybridrag/api/search.clj` (make `passage-json`,
  `candidate-json`, `degraded-json` public, reused by ask)
- Modify: `src/hybridrag/routes.clj` (route `/ask`)
- Modify: `src/hybridrag/retrieval/system.clj` (`:chat-fn`)
- Modify: `src/hybridrag/config.clj` (`chat-config` gains `:extra-body`
  from `VLLM_CHAT_EXTRA_BODY`, JSON)
- Modify: `src/hybridrag/trace.clj` — none needed (`:answer` exists)
- Test: `test/hybridrag/api/ask_test.clj`
- Docs: `docs/decisions.md` entry "T3.1–T3.2 generation details";
  `VLLM_SETUP.md` chat section (LM Studio model + env)

**Interfaces:**
- Consumes: `answer/ask!`, `answer/no-evidence-message`,
  `api.search/passage-json`, `candidate-json`, `degraded-json`,
  `trace/write!`.
- Produces: `ask/request-schema`, `ask/handler`; the `::search` component
  map gains `:chat-fn (fn [messages opts] resp)` which calls
  `chat/complete!` with `(config/chat-config)`, merging config
  `:extra-body` into opts when opts has none.

- [ ] **Step 1: Failing tests** — `test/hybridrag/api/ask_test.clj`,
  same fixtures and `post` helper shape as `search_test.clj` (copy them,
  pointing at `/api/v1/ask` and adding a chat-fn argument):
  - `test-auth-and-validation`: 401 without token; 400 for `{}`, `""`,
    1001 chars, `final_k 0`, `debug "yes"`.
  - `test-ask-response-and-trace`: chat stub replies `"特休依年資計算[1]。"`
    for alice asking 「特休天數怎麼計算？」 → 200, `answer` as given,
    `citations` = one passage (with `n`, `doc_path`, `section_trail`,
    `text`), `no_evidence false`, `degraded []`, no `candidates` key;
    with `debug true` → `candidates` present. Trace: kind `:ask`,
    `:trace/answer` stored, `[:trace/stages :generate :model]` = stub
    model, stages contain no passage text.
  - `test-ask-no-evidence`: user `nobody` → `no_evidence true`, answer =
    `answer/no-evidence-message`, `citations []`; chat-fn throws if
    called.
  - `test-ask-errors`: chat-fn returns `{:error "model not loaded"}` →
    503 `dependency_unavailable`; chat-fn throws
    `(ex-info "timed out" {:llm/endpoint :chat})` → 503; rerank down →
    200 with `degraded ["rerank_failed"]` and an answer.
  - `test-ask-never-leaks`: as in `search_test`, for every unreadable
    doc × user with `debug true` and a chat stub citing every passage
    (`"[1][2][3][4][5][6][7][8]"`), the doc is in neither `citations`
    nor `candidates`.
  - `^:vllm test-ask-real-model`: skipped (prints a notice) unless
    `VLLM_CHAT_BASE_URL` is set; ingests `corpus-sample` into a tmp index
    with the real embedder (`config/embed-config`), asks alice's
    「特休天數怎麼計算？」 through the real `::search` component
    (`ig/init-key`), expects 200, non-blank answer, ≥ 1 citation from
    `hr/leave.md`, `no_evidence false`.

- [ ] **Step 2: Run, expect failure** (404 on `/ask`).

- [ ] **Step 3: Implement.** `api/ask.clj`:

```clojure
(def request-schema
  [:map
   [:query [:string {:min 1 :max 1000}]]
   [:final_k {:optional true} [:int {:min 1 :max 50}]]
   [:debug {:optional true} :boolean]])

(defn handler [{:keys [context principal parameters errors]}]
  (if errors
    (auth/error-response 400 "invalid_request" "請求格式不正確：query 必填，長度 1–1000 字元。")
    (let [{:keys [query final_k debug]} (:body parameters)
          {:keys [app-conn search]} context
          opts (cond-> (:opts search) final_k (assoc :final-k final_k))]
      (try
        (let [res (answer/ask! search principal query opts)
              trace-id (trace/write! app-conn {:username (:username principal) :kind :ask :query query
                                               :stages (:stages res) :degraded (:degraded res)
                                               :answer (:answer res)})]
          (log/info "[ASK]" {:trace_id trace-id :user (:username principal)
                             :citations (count (:citations res)) :no_evidence (:no-evidence? res)
                             :degraded (:degraded res)})
          {:status 200
           :body (cond-> {:answer (:answer res)
                          :citations (mapv search/passage-json (:citations res))
                          :no_evidence (:no-evidence? res)
                          :degraded (search/degraded-json (:degraded res))
                          :trace_id (str trace-id)}
                   debug (assoc :candidates (mapv search/candidate-json (:candidates res))))})
        (catch clojure.lang.ExceptionInfo e
          (if-let [endpoint (:llm/endpoint (ex-data e))]
            (do (log/warn "[ASK] dependency failed:" endpoint (ex-message e))
                (auth/error-response 503 "dependency_unavailable"
                                     (str "問答服務暫時無法使用（" (name endpoint) "）。")))
            (throw e)))))))
```

  `system.clj` adds
  `:chat-fn (fn [messages opts] (let [cfg (config/chat-config)] (chat/complete! cfg messages (update opts :extra-body #(or % (:extra-body cfg))))))`.
  Missing `VLLM_CHAT_BASE_URL` → `chat/complete!` would build URL
  `"null/chat/completions"`; guard in the chat-fn: throw
  `(ex-info "VLLM_CHAT_BASE_URL not set" {:llm/endpoint :chat})` → 503.

- [ ] **Step 4: Run tests, expect PASS**; full suite + lint.
- [ ] **Step 5: Real-model check.** LM Studio chat model (`lms load
  qwen/qwen3-8b`), then with `VLLM_CHAT_BASE_URL=http://localhost:1234/v1
  VLLM_CHAT_MODEL=qwen/qwen3-8b` plus the embed/rerank env from
  `VLLM_SETUP.md`, run `test-ask-real-model`. Record model, latency and
  whether `<think>` appeared in `docs/decisions.md`.
- [ ] **Step 6: Commit** — `T3.2: POST /api/v1/ask with trace and real-model test`.

---

## Self-review notes

- §10.1 prompt/params → T3.1 steps 1, 4; configurable via `:opts`
  (`:temperature :max-tokens :extra-body`) and `VLLM_CHAT_EXTRA_BODY`.
- §10.2 invalid removed + traced, citations only cited, `:uncited-answer`
  → `test-parse-citations`, `test-ask-answer-and-citations`,
  `test-ask-uncited-and-not-found`.
- §10.3 → `test-ask-no-evidence` (both tasks). "All below threshold" is
  covered because `pipeline/search` already drops them before packing,
  leaving no passages.
- §11 `/ask` shape, `debug` → T3.2 tests. §14 `:generate` → both tasks.
- §4.2 `<think>` → `test-strip-think`; 200-with-error → Review Focus 3.
