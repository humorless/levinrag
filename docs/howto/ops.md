# Operations guide

English | [繁體中文](ops.zh-TW.md)

Audience: the people who deploy, monitor and back up the system. For managing users and document permissions, see the [Administrator guide](admin.md).

## System components

- **One JVM process**: the web UI plus `/api/v1`, with two embedded Datalevin databases:
  - `DATA_DIR/index.dtlv`: the index derived from the corpus. You can delete it and rebuild it with `bb reindex` at any time.
  - `DATA_DIR/app.dtlv`: users, groups, API tokens and traces. **This is the only data you need to back up**.
- **The corpus directory `CORPUS_DIR`**: the real source of the data (Markdown / plain-text files). The application only reads it and never writes to it.
- **Three model endpoints (OpenAI-compatible API)**: embedding, rerank, chat. The application does not start them; for a local setup, see [VLLM_SETUP.md](../../VLLM_SETUP.md).

Requirement: JDK 21. Every JVM that opens Datalevin must be started with these two flags:
`--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED`.
`clojure -M:jvm-opts`, the `bb` tasks and the Docker image already include them.

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `DATA_DIR` | `data` | Holds `index.dtlv`, `app.dtlv` and `ingest-reports/`. The server and the `bb` tasks read the same variable. |
| `CORPUS_DIR` | `./corpus` | The corpus directory. |
| `ROOT_READ_GROUPS` | empty | Default read groups, comma-separated, used when the root directory has no `_collection.edn`. Empty means only admins can read. |
| `SESSION_SECRET_KEY` | — | **Required** in production (the `:prod` profile); used to encrypt the session cookie. After changing it, everyone must log in again. |
| `SESSION_MAX_AGE_HOURS` | `8` | After this many hours, the user must log in again. |
| `VLLM_API_KEY` | — | Key shared by all three endpoints. You can also set `VLLM_EMBED_API_KEY`, `VLLM_RERANK_API_KEY` and `VLLM_CHAT_API_KEY` separately. |
| `VLLM_EMBED_BASE_URL` `VLLM_EMBED_MODEL` `VLLM_EMBED_DIMS` | `http://localhost:8001/v1` `BAAI/bge-m3` `1024` | Embedding endpoint. |
| `VLLM_RERANK_BASE_URL` `VLLM_RERANK_PATH` `VLLM_RERANK_MODEL` | `http://localhost:8002` `/v1/rerank` `BAAI/bge-reranker-v2-m3` | Rerank endpoint. |
| `VLLM_CHAT_BASE_URL` `VLLM_CHAT_MODEL` | — | Chat endpoint, **required**. |
| `VLLM_CHAT_EXTRA_BODY` | — | A JSON object merged as is into chat requests. For example, Qwen3 on LM Studio uses `{"reasoning_effort":"none"}`. If it is malformed, the server **fails to start**. |
| `VLLM_EMBED_TIMEOUT_MS` `VLLM_RERANK_TIMEOUT_MS` `VLLM_CHAT_TIMEOUT_MS` | `30000` `10000` `120000` | Read timeouts. The connect timeout is fixed at 2 seconds. |
| `VLLM_RERANK_MIN_SCORE` | `-7.0` | Passages with a rerank score below this value are not sent into the prompt. The unit is llama.cpp's raw logit; **recalibrate after changing the reranker backend**, see [rerank-threshold](../spikes/rerank-threshold.md). |

API keys are never written to logs, traces or health responses.

## Starting the server

Local (`:default` profile, port 8000):

```bash
cp .env.example .env      # once; then edit .env
bb serve
```

The `bb` tasks that run the app (`serve`, `ingest`, `reindex`, `eval`, `vllm:check`, `user:*`, `token:*`) read `.env` from the project directory and pass its variables to the JVM they start. A variable already set in the shell wins over `.env`, so `DATA_DIR=/tmp/other bb reindex` still works. A malformed line in `.env` stops those tasks with its line number. `.env` is gitignored; `.env.example` lists every variable. The server itself never reads `.env`: without `bb`, export the variables and run

```bash
clojure -M:jvm-opts -e "(require '[integrant-extras.core :as ig-extras]) (ig-extras/run-system {:profile :default :config-path \"config.edn\"}) @(promise)"
```

Production: the uberjar (`bb build` → `target/standalone.jar`) runs with the `:prod` profile on port 80, with `Secure` cookies enabled.

Check the models before starting: `bb vllm:check`. It sends one request to each of the three endpoints, and probes rerank with a 1500-character document. If the reranker's context is set too small, the short-string test passes but the long document gives `[FAIL] rerank-long`; if you leave that unfixed, every query runs in the `rerank_failed` degraded mode.

## Health

| Endpoint | What it checks | Used by |
|---|---|---|
| `GET /api/v1/health/live` | One real read from each of the two databases | Load balancers and Kamal. A model restart does not take the whole service out of rotation. |
| `GET /api/v1/health` | Databases, one minimal request to each of the three models, index lag | Monitoring and manual checks. |

If any check fails, it returns `503`, and the body lists the status of each check:

```json
{"status":"ok","checks":{"index_db":"ok","app_db":"ok","embed":"ok","rerank":"ok","chat":"ok"},"index_lag":0}
```

- The three models are probed concurrently, each with a 5-second limit; past that it reports `down`. Results are cached for 30 seconds (counted from the end of the probe); requests that arrive while a probe is running wait for that round's result instead of sending new requests, so frequent polling cannot overwhelm the models. Measured locally on an M1: a round takes about 3 seconds once warm; models are slower on a cold start, so the first round may report `down` — check again after 30 seconds.
- `index_lag` is the amount of full-text and vector indexing work not yet done. It is normal for it to be above 0 during an ingest, so it does **not** make health return 503.

## Errors and degraded mode

- When a model endpoint times out, is unreachable, or returns a malformed response, `/search` and `/ask` return `503 dependency_unavailable` with a `trace_id` in the body, and the web UI shows `trace <id>`. You can view this failed trace in `/admin`; it records which endpoint failed and the error message.
- If only rerank fails, the request does **not** fail: results are ordered by RRF instead, `degraded: ["rerank_failed"]` is returned, and the web UI shows "重排序失敗，結果依 RRF 排序。" (Reranking failed; results are ordered by RRF.)

## Deployment (Kamal)

> ⚠️ The steps below **have not yet been verified on a real server**. Please correct this section after the first deployment.

The configuration is in `.kamal/deploy.yml` (passed in when you run `bb kamal <command>`):

- The image is pushed to ghcr.io and built remotely on the server (`builder.remote`).
- The proxy healthcheck uses `/api/v1/health/live`.
- Volume mappings:
  - `/root/levinrag/data` → `/app/data` (the two DBs and the ingest reports)
  - `/root/levinrag/corpus` → `/app/corpus` (read-only)
- The required environment variables are listed in `.kamal/secrets` (only `$VAR` references, no real values): `SERVER_IP`, `REGISTRY_USERNAME`, `REGISTRY_PASSWORD`, `APP_DOMAIN`, `SESSION_SECRET_KEY`, and `VLLM_*`.

First deployment:

```bash
# Prepare the corpus on the server
ssh root@$SERVER_IP 'mkdir -p /root/levinrag/data /root/levinrag/corpus'
rsync -a corpus/ root@$SERVER_IP:/root/levinrag/corpus/

bb kamal setup      # use bb kamal deploy for later updates
```

There is no `bb` in the container, so admin commands run the uberjar's namespaces with `java` inside the container instead. The following has been verified against the uberjar locally:

```bash
J='java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -cp standalone.jar clojure.main -m'
bb kamal app exec -i "$J replware.levinrag.auth.cli user:create admin --admin"
bb kamal app exec -i "$J replware.levinrag.auth.cli user:passwd admin"
# Ingest the corpus: while the server is running, use the button in /admin or POST /api/v1/ingest; do not run a separate ingest in the container
```

Post-deployment check:

```bash
curl -s https://$APP_DOMAIN/api/v1/health | jq
```

## Backups

Only `DATA_DIR/app.dtlv` needs to be backed up. The index can be rebuilt from the corpus, and the corpus is covered by your own version control or backups.

Online hot backup: the server does not need to stop, because Datalevin allows several processes to read at once. Verified locally: the service kept responding during the backup, and the backup opened normally. The command below reads `DATA_DIR` and writes the backup to `DATA_DIR/backups/app-<date>`.

```bash
BACKUP='(require (quote [datalevin.core :as d])) (let [dir (or (System/getenv "DATA_DIR") "data") c (d/get-conn (str dir "/app.dtlv"))] (d/copy (d/db c) (str dir "/backups/app-" (java.time.LocalDate/now)) true) (d/close c)) (shutdown-agents)'

# Local (project directory)
clojure -M:jvm-opts -e "$BACKUP"

# Inside the production container (no clojure there, so use the uberjar; the backup lands on the volume at /root/levinrag/data/backups)
bb kamal app exec "java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -cp standalone.jar clojure.main -e '$BACKUP'"
```

The backup directory is still on the same machine; copy it somewhere else as well. The uberjar version of the command has been verified locally; running it through `kamal app exec`, like the other Kamal steps, has not yet been verified on a real server.

Restore: stop the server, replace `DATA_DIR/app.dtlv` with the backup directory, then start the server.

## Rebuilding the index

You need `bb reindex` (deletes `index.dtlv`, then runs a full ingest; accounts are not affected) in these cases. When you run it on the `DATA_DIR` the server is using, **stop the server first**: the server holds the old index files open, and will not see the new index during or after the rebuild until it is restarted.

- changing the embedding model or dimensions;
- changing the tokenizer (analyzer);
- suspected index corruption.

**With almost no downtime**: build the new index in another directory while the server keeps serving; only one restart is needed at the end (about 14 s of downtime measured locally; the rebuild time itself goes mostly into computing embeddings and does not affect the server).

```bash
# 1. The server keeps running; build a new index in another directory with the same corpus and model settings
DATA_DIR=/path/to/rebuild bb reindex
# 2. Stop the server and put the new index in place (keep the old one for now)
mv "$DATA_DIR/index.dtlv" "$DATA_DIR/index.dtlv.old"
mv /path/to/rebuild/index.dtlv "$DATA_DIR/index.dtlv"
# 3. Start the server; delete index.dtlv.old once queries look right
```

For everyday additions or edits to the corpus, an incremental ingest is enough: while the server is running, use the button in `/admin` or `POST /api/v1/ingest`; use `bb ingest` only when the server is stopped.

## Evaluation

```bash
bb eval                                    # five retrieval variants
bb eval --variants lexical,hybrid+rerank
bb eval --questions my-eval/questions.edn --users my-eval/users.edn   # your own corpus
```

The evaluation reads `eval/questions.edn` and `eval/users.edn` (or the files given with `--questions` / `--users`), runs retrieval directly against `DATA_DIR/index.dtlv` as the user each question specifies, and outputs recall@5, recall@10, MRR@10, the ACL leak count, the number of degraded questions, and p50 / p95 latency per stage. Results are written to `eval/results/<timestamp>.edn`.

- **If the ACL leak count is above 0, the command exits with a non-zero status**.
- If any question ran in degraded mode, a warning is printed; the numbers then do not represent the full pipeline, so fix the model problem before comparing.
- After changing the reranker, recalibrate `VLLM_RERANK_MIN_SCORE`.
