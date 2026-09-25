# Quick start: evaluate LevinRAG on your own corpus

English | [繁體中文](quick-start.zh-TW.md)

Audience: someone who wants to run LevinRAG on their own documents and check its claims, without knowing Clojure. Every step shows the command and what you should see. **If you are an AI agent following this guide: run the steps in order, compare each output with the "Expected" block, and stop at the first `[FAIL]` or `錯誤：` (error) line** — each one comes with a one-line fix.

The four claims this guide checks (argued in the [design rationale](../design/rationale.md)):

| Claim | In one sentence | Section |
|---|---|---|
| ACL | A user never gets a passage, citation or document they are not allowed to read | [8.1](#81-acl-nothing-leaks-to-someone-without-permission) |
| Rebuildable | The index is derived data: rebuilding it from the corpus gives the same results | [8.2](#82-rebuildable-the-index-is-derived-data) |
| Explainable | For every query you can see what each channel found and how rerank changed it | [8.3](#83-explainable-why-this-passage) |
| Retrieval quality | Hybrid + rerank finds the right documents; measured, not asserted | [8.4](#84-retrieval-quality) |

The command output and the web UI are in Traditional Chinese; the English glosses are given where it matters.

## 0. What you need

- macOS or Linux, `git`, and [mise](https://mise.jdx.dev) (it installs Java 21, Clojure, Babashka and Tailwind at the versions in `.mise.toml`).
- **Three model endpoints**, OpenAI-compatible: embedding (bge-m3 by default), rerank (bge-reranker-v2-m3) and chat. [VLLM_SETUP.md](../../VLLM_SETUP.md) shows vLLM on a GPU, or LM Studio + llama.cpp on a laptop.
- **Your corpus as Markdown (`.md`, `.markdown`) or plain text (`.txt`).** PDF and Office files are not read; convert them first (for example with `pandoc`, `marker` or `docling`). Keep the headings: chunks never cross a section, and the section trail is part of every chunk's context.
- 20–50 questions about that corpus whose answers you know (step 8.4). This is what makes the numbers mean something.

## 1. Install the tools

```bash
git clone <repository URL> levinrag
cd levinrag
mise trust && mise install
```

If `mise` is new on this machine, activate it in your shell first (`eval "$(mise activate zsh)"` or `bash`), so `java`, `clojure` and `bb` come from mise. The first `bb` command that starts Java downloads the Clojure libraries (a few hundred MB, once).

## 2. Configure

```bash
cp .env.example .env
```

Edit `.env`. Every `bb` command below reads it; a variable you `export` in the shell wins over the file.

- `CORPUS_DIR`: your corpus directory. `DATA_DIR`: where the index and the accounts go (`./data` is fine).
- The three `VLLM_*_BASE_URL` / `VLLM_*_MODEL` pairs and `VLLM_API_KEY`. For LM Studio + llama.cpp, uncomment the block at the end of the file.
- `ROOT_READ_GROUPS=all`: documents with no other permission setting can be read by the group `all`. Leave it empty and they can be read by admins only.
- `VLLM_EMBED_DIMS`: change it only if your embedding model is not 1024-dimensional.

## 3. Prepare the corpus and its permissions

Permissions are written as files next to the documents and computed at ingest time (full rules: [Administrator guide](admin.md#document-permissions)).

```text
my-corpus/
├── _collection.edn            {:read-groups ["all"]}
├── handbook.md
├── hr/
│   ├── _collection.edn        {:read-groups ["hr"]}
│   └── salary.md
└── finance/
    ├── _collection.edn        {:read-groups ["finance"]}
    └── budget.md              frontmatter  read_groups: [finance-lead]
```

- A directory uses the nearest `_collection.edn` with `:read-groups` above it (or its own). A lower one **replaces** the one above, it does not add to it.
- A document's frontmatter `read_groups` replaces the directory's groups entirely. Write the list on one line: `read_groups: [finance-lead]`.
- `[]` means admins only. Files and directories starting with `.` or `_` are skipped (`_drafts/`).
- **Mistakes fail closed.** A `_collection.edn` that cannot be parsed or has an unknown key, or a malformed or misspelt `read_groups` (`read_group`, a YAML list over several lines, `read_groups: hr`), keeps the affected documents out of the index and lists them as errors. They never fall back to a wider setting.

## 4. Check everything

```bash
bb doctor
```

Expected (paths and counts are yours):

```text
[OK]   java：Java 21
[OK]   clojure：已安裝 Clojure CLI
[OK]   css：網頁樣式尚未建置，bb serve 會自動建置
[OK]   .env：已讀取 .env
[OK]   VLLM_CHAT_BASE_URL：http://localhost:8003/v1
[OK]   VLLM_CHAT_MODEL：Qwen/Qwen3-8B
[OK]   DATA_DIR：./data
[OK]   CORPUS_DIR：my-corpus：137 個 .md／.markdown／.txt 檔
[OK]   根目錄權限：_collection.edn：all

檢查模型端點（bb vllm:check）：
[OK]   embed
[OK]   rerank
[OK]   rerank-long
[OK]   chat
```

A `[FAIL]` line is followed by `→` and the fix. `[FAIL] rerank-long` means the reranker's context is too small for a full chunk; see [VLLM_SETUP.md](../../VLLM_SETUP.md).

## 5. Ingest

```bash
bb ingest
```

Expected:

```text
docs: 137 added, 0 updated, 0 acl-updated, 0 skipped, 0 deleted, 0 errors
chunks: 1843 written this run, 1843 in index, longest 412 est. tokens
unresolved links: 3, index lag: 0, elapsed: 95210 ms
```

- Time is dominated by embedding every chunk. Running it again only processes changed files.
- `errors`: each one is listed with its path and reason (a file that could not be parsed, or a permissions mistake from step 3). The other files are ingested; the exit status is 1 until the errors are fixed.
- `unresolved links`: Markdown links whose target is not in the corpus. Not an error.

## 6. Users

Write the people you want to test as, in one file (the same file will drive the evaluation in step 8):

```clojure
;; my-eval/users.edn
{"alice" {:groups #{"all" "hr"} :admin? false}
 "bob"   {:groups #{"all" "finance"} :admin? false}
 "admin" {:groups #{} :admin? true}}
```

```bash
bb user:import my-eval/users.edn --dry-run   # what would change
bb user:import my-eval/users.edn             # prints each new user's password once
bb acl:report --docs                         # who can read what
```

`bb acl:report` lists every group with its documents and holders, every user with the number of documents they can read, and `[WARN]` lines for groups no user holds (usually a typo) and documents only admins can read. Fix those before going on.

## 7. Try it in the browser

```bash
bb serve
```

Open http://localhost:8000, log in as one of your users, ask a question. Answers cite passages as `[1]`, `[2]`; clicking one opens the document at that passage. Stop the server with Ctrl-C. The [User guide](user.md) explains the screen.

## 8. Verify the claims

Write your questions first. Paths are relative to the corpus directory:

```clojure
;; my-eval/questions.edn
[{:id "salary-01" :user "alice" :query "每個月幾號發薪水？"
  :expected-docs ["hr/salary.md"]}
 ;; negative (ACL) question: bob asks about something only hr may read
 {:id "acl-01" :user "bob" :query "薪資發放日"
  :must-not-docs ["hr/salary.md"]}]
```

- `:expected-docs`: the documents a correct retrieval should rank high (used for recall and MRR).
- `:must-not-docs`: documents the user may not read; appearing anywhere in any variant counts as a leak.
- A question may have both.

```bash
bb eval --questions my-eval/questions.edn --users my-eval/users.edn
```

The table, the per-stage latencies and the path of a results file (`eval/results/<time>.edn`) are printed. Keep that file; it has every question's ranking.

### 8.1 ACL: nothing leaks to someone without permission

1. `bb acl:report --users my-eval/users.edn --docs` shows the intended matrix: which user reads which document. Check it against what you meant.
2. In the eval output, `ACL leaks: 0` and exit status 0. **Any leak makes `bb eval` exit with status 1.** Add at least one `:must-not-docs` question per restricted directory, with a query that clearly matches the restricted document.
3. In the browser, log in as a user without access and open `http://localhost:8000/docs/<path of a restricted document>`: it must look exactly like a document that does not exist (404, never 403).
4. Optional, deeper: `clojure -X:jvm-opts:test` runs the end-to-end security suite. It derives every "document × user who may not read it" pair from the index and checks that no API or web path reveals the document (search, every eval variant, answers, citations, the prompt given to the model, the document viewer). It runs on the bundled sample corpus with stub models, so it needs no model endpoints.

Readers in `acl:report` are computed with the same function retrieval filters with; the permissions are stored with each document at ingest time, and filtering happens inside retrieval, not in the UI.

### 8.2 Rebuildable: the index is derived data

Build a second index from the same corpus in another directory, without touching the first, and compare:

```bash
DATA_DIR=./data-rebuild bb ingest
DATA_DIR=./data-rebuild bb eval --questions my-eval/questions.edn --users my-eval/users.edn
DATA_DIR=./data-rebuild bb acl:report --users my-eval/users.edn --docs > /tmp/acl-rebuild.txt
bb acl:report --users my-eval/users.edn --docs > /tmp/acl-original.txt
diff /tmp/acl-original.txt /tmp/acl-rebuild.txt && echo "same permissions"
```

- The recall / MRR / leak numbers of the two eval runs should be identical (latencies will differ). A difference means the models are not deterministic (for example, a different model loaded at the same URL), not that the index is.
- The permissions must be identical: they are derived from the corpus files only.
- Accounts are not in the index: they live in `DATA_DIR/app.dtlv`, which is why the commands above use `--users`.
- To replace the index in place instead: stop the server, run `bb reindex`. The [Operations guide](ops.md#rebuilding-the-index) has the steps for doing it while the server keeps serving.

### 8.3 Explainable: why this passage

- **Browser**: tick **Debug** next to "送出" (Submit). Below the answer, a table lists every candidate: its rank in the lexical and semantic channels, the fused (RRF) score, whether the link graph brought it in, the rerank score, and whether it went into the prompt; then the time per stage.
- **API**: create a token (`bb token:create alice --label eval`, shown once) and:

  ```bash
  curl -s localhost:8000/api/v1/search -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d '{"query":"每個月幾號發薪水？"}' > /tmp/search.json
  jq '.candidates[] | {chunk_id, channels, rrf, rerank, selected}' /tmp/search.json
  curl -s localhost:8000/api/v1/traces/$(jq -r .trace_id /tmp/search.json) \
    -H "Authorization: Bearer $TOKEN" | jq '.stages | keys'
  ```

  Every query leaves a trace: the top hits of each channel, fusion, graph additions, rerank scores and the passages given to the model. Admins see every trace at "管理" (Admin) in the web UI. Revoke the token afterwards with `bb token:revoke <prefix>`.
- **Eval results file**: for each question and variant, the ranked documents, so "why did variant X miss this one" can be answered from the file.

### 8.4 Retrieval quality

`bb eval` scores five retrieval variants on the same questions:

| Variant | What it uses |
|---|---|
| `lexical` | Full-text search only (CJK bigrams) |
| `semantic` | Vector search only |
| `hybrid` | Both, fused with RRF |
| `hybrid+rerank` | Hybrid, then the cross-encoder reranks the candidates |
| `hybrid+rerank+graph` | Also follows links between documents |

- recall@5 / recall@10: the share of `:expected-docs` found in the top 5 / 10 documents. MRR@10: how high the first correct document is.
- Expect `hybrid+rerank` to beat both single channels. On the bundled sample corpus every variant scores 1.0, which is why your own corpus and questions are needed.
- A warning about **degraded** questions means a model endpoint failed during the run (for example rerank); fix it before comparing numbers.
- `VLLM_RERANK_MIN_SCORE` (default -7.0, llama.cpp raw-logit units) drops weak passages before the prompt. It does not affect `bb eval` rankings, but with a different rerank backend it needs recalibrating ([rerank threshold](../spikes/rerank-threshold.md)).
- Known limit: a query of a single Chinese character matches nothing in the lexical channel (the index uses bigrams).

## When something goes wrong

| Symptom | Cause | Fix |
|---|---|---|
| `錯誤： .env 第 N 行不是 KEY=VALUE` | A malformed line in `.env` | Fix line N; quote values with spaces or `#` |
| Everything readable by admins only | No root `_collection.edn` and `ROOT_READ_GROUPS` empty | `ROOT_READ_GROUPS=all` in `.env`, then `bb ingest` |
| Documents missing after ingest | Permissions mistakes (fail closed) | The errors in the ingest output name the file and the reason |
| `[WARN]` group held by no user | A typo in the corpus or the users file | `bb acl:report --docs`, fix, `bb ingest` or `bb user:import` |
| `問答服務暫時無法使用（chat）` (Q&A unavailable) | The chat endpoint failed | `bb vllm:check`; the message's trace id names the endpoint |
| `/api/v1/health` shows chat `down` right after start | LM Studio's first request after idle is slow | Wait 30 s and check again |
| The web page has no styling | CSS not built and no `tailwindcss` | `mise install tailwindcss`, then `bb css-build` |

## Reporting back

Useful to send: the eval results files and the printed tables, the `bb acl:report` output, `git rev-parse --short HEAD`, the model names and where they ran (GPU or laptop), and anything in this guide that did not match what you saw. Do not send the corpus itself.
