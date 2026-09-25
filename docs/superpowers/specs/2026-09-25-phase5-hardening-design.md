# Phase 5 Hardening — design

Date: 2026-09-25. Status: approved in conversation; supplements SPEC.md
§4.3, §11, §13, §14, §17 Phase 5 and §18.3 (those remain the authority;
this file fixes what they leave open and records the backlog items the
user promoted into this phase).

## Goal

Make the MVP deployable and trustworthy: dependency failures are reported
the same way everywhere (503 + a trace), health tells a load balancer and
an operator different things, sessions expire and can be revoked, the
whole of §18.3 runs as one end-to-end suite, and the README and three
HowTos let someone else run it.

Scope: T5.1, T5.2, T5.3 plus the promoted backlog items below.
**T5.4 (SSE streaming) is deferred** (user decision; its tension with
citation validation needs its own design). `/login` rate limiting stays
in the backlog.

## T5.1 — health, timeouts, consistent errors

### Health: two endpoints

| Endpoint | Checks | Status |
|---|---|---|
| `GET /api/v1/health/live` | Both DBs, each with a real read (e.g. first datom of an attribute via `d/datoms`/`d/q`), not just `d/db`. | 200 / 503 |
| `GET /api/v1/health` (SPEC §11) | The DB checks above; embed, rerank and chat each with a minimal real request (embed one short string; rerank one short doc; chat `max_tokens 1`); index lag. | 503 when any DB or model check fails; body lists every check |

- No auth on either (SPEC §11). The full body never contains API keys,
  URLs with credentials, or model error bodies beyond the status and a
  short message.
- Model probe results are cached for 30 s (one cache per process, all
  three probes refreshed together; concurrent requests during a refresh
  may each probe — no locking). DB checks are never cached.
- **Index lag is reported, not a failure**: lag is normal while ingest
  runs. Source: `d/wait-for-secondary-index` with timeout 0 →
  `:unfinished-count` — ⚠️ VERIFY in the REPL first; if a zero timeout is
  not supported, use the smallest timeout that returns promptly and
  record the choice in `docs/decisions.md`.
- Body shape (snake_case):
  `{"status": "ok"|"degraded", "checks": {"index_db": "ok", "app_db": "ok", "embed": "ok", "rerank": "down", "chat": "ok"}, "index_lag": 0}`;
  `live` returns the same shape with only the two DB checks and no
  `index_lag`.

### Kamal

- `proxy.healthcheck.path` → `/api/v1/health/live`. A chat model restart
  must not take the whole app (search, doc viewer) out of the proxy.
- Volumes for `DATA_DIR` and `CORPUS_DIR`; env passes `DATA_DIR`,
  `CORPUS_DIR` and the `VLLM_*` variables (secrets as `secret`).
- The session secret env var stays `SESSION_SECRET_KEY` (what the code
  reads); SPEC §5 says `SESSION_SECRET` — record the difference in
  `docs/decisions.md` rather than rename.

### Timeouts

- Read timeouts configurable: `VLLM_EMBED_TIMEOUT_MS` (default 30000),
  `VLLM_RERANK_TIMEOUT_MS` (10000), `VLLM_CHAT_TIMEOUT_MS` (120000).
  Connect timeout stays 2000 ms. Added to SPEC §5's table.

### Error classification (`llm/http.clj`)

- Every `java.io.IOException` (not only timeout and connect) → `ex-info`
  with `:llm/endpoint`, `:http/status nil`.
- A 2xx body that is not JSON → `ex-info` with `:llm/endpoint`,
  `:http/status`, `:llm/body-excerpt`.
- Result: every dependency failure reaching `/search`, `/ask` (API and
  web) is a 503 `dependency_unavailable`, never a 500. Genuine bugs
  (non-`:llm/endpoint` exceptions) still propagate as 500.

### Traces on failure

- When `/search` or `/ask` (API or web) fails with a dependency error, a
  trace is still written: username, kind, query, `:stages {:error
  {:endpoint :embed :message "..."}}`, `:trace/degraded #{:dependency-failed}`.
  The message is the ex-message only (no body excerpt, no key).
- The 503 JSON adds `trace_id` next to `error`; the web error notice
  shows the trace id so an admin can open it.
- If writing the trace itself fails, log it and still return the 503.

### `bb vllm:check` and `bb eval`

- Rerank probe uses a document of about 1500 CJK characters (the
  `:rerank/max-chars` cut) besides the short pair, and fails when the
  long document's result is missing, non-finite, or the call errors.
  The exact comparison (e.g. long relevant doc must not score below a
  short irrelevant one) is fixed after trying it against the local
  llama.cpp reranker with a deliberately small context; the chosen rule
  goes into `docs/decisions.md`.
- `bb eval` prints a warning with the count when any variant's question
  ran degraded; exit code unchanged (ACL leaks remain the only non-zero
  exit).

## T5.2 — security

### Session lifetime and revocation

- Login stores `{:username .. :issued-at <epoch ms>}` in the cookie
  session.
- `app.dtlv` gains `:user/sessions-valid-after` (`:db.type/instant`).
  `POST /logout` and `bb user:passwd` set it to now.
- `wrap-session-auth` treats the session as absent (→ `/login`, or
  `HX-Redirect` for HTMX) when: `:issued-at` is missing (cookies from
  before this change), older than `SESSION_MAX_AGE_HOURS` (default 8,
  `:session/max-age-hours` in config), or not after
  `:user/sessions-valid-after`.
- Granularity is per user: logging out on one device ends every device's
  session. `bb user:groups` needs no revocation — the principal is
  re-read on every request, so group changes already apply at once.
- API tokens are untouched (`bb token:revoke` remains their revocation).

### `docs/lookup` split (SPEC §9.3)

- `lookup-admin` (no ACL clause) and `lookup-acl` (ACL, empty groups →
  nil) replace the shared `(or (:admin? principal) ...)`. Callers choose
  by `:admin?` in one place each (API docs handler, web viewer).

### §18.3 end-to-end suite — `test/hybridrag/security_test.clj`

Fixture: the sample corpus ingested once (stub embed/rerank/chat), users
alice, bob, carol, admin and a no-group user.

The matrix is **derived from the index**, not hard-coded: every restricted
doc × every seeded non-admin user whose groups do not intersect the doc's
`:doc/effective-groups`. For each pair, queries are the doc title and the
first chunk's text (truncated to 1000 chars). Checks:

1. `/api/v1/search`, each eval variant and `graph` true/false: no passage
   or candidate from the doc.
2. `/api/v1/ask` with `debug true`: no citation or candidate from it.
3. The `messages` handed to the chat stub (system prompt and `<sources>`;
   the echoed question is the probe itself) contain neither the doc's
   path, its title, nor the start of its first chunk — each checked only
   when it does not also occur in text the user may read (a readable doc
   can mention a restricted doc's title or link to its path, e.g.
   `public/handbook.md` → `hr/leave.md` as 請假規定). Every user × doc pair
   must keep at least one of the three to check.
4. `GET /api/v1/docs/<path>` and web `GET /docs/<path>` → 404.
5. Graph: `public/handbook.md` links to `hr/leave.md`; bob's graph
   candidates never include `hr/leave.md`.

Plus: context-expansion neighbours stay within readable docs; a no-group
user gets empty results for every query; another user's trace → 404 for
a non-owner non-admin (API and web).

**Negative control:** the same queries as admin must return the
restricted doc, so each check proves it could have failed.

Session lifetime/revocation tests live in `web/auth_test.clj`.

## Promoted backlog items

- **One source for data and corpus dirs.** `config.edn` gains
  `:data-dir` (profile: `data` / `data-test`) and `:root-read-groups`,
  passed by Integrant to the runner and server; the runner stops calling
  `config/corpus-config`. CLI tools (`bb ingest`) keep reading env vars
  with the same defaults.
- **`trace/recent` without a full scan**: reverse range scan over
  `:trace/at` (`d/rseek-datoms` or equivalent — ⚠️ VERIFY), take n.
- **Citations and think tags**: `(?U)` on `citation-re` and `numbers`,
  full-width digits NFKC-normalized before parsing; ranges (`[1-3]`)
  stay unsupported. A reply with a `</think>` and no opening tag drops
  everything up to and including the first such tag.
- **`VLLM_CHAT_EXTRA_BODY`**: must be a JSON object; checked at
  `ig/init-key` of the search/answer component so a bad value fails
  startup instead of every `/ask`.
- **`</sources>` neutralized**: `<sources>`/`</sources>` in passage text
  are rewritten with full-width angle brackets before prompt assembly;
  test that the messages hold exactly one real closing tag.

## T5.3 — documentation (after the code)

- `README.md`: what it is, quick start, links; the ACL override
  semantics (SPEC §7.2 requires it in the README).
- `docs/howto/ops.md`: deploy with Kamal, volumes, env vars, the two
  health endpoints, timeouts, backups of `data/app.dtlv`, running eval.
  The deploy steps are marked "not yet verified on a real server" until
  the user has deployed once.
- `docs/howto/admin.md`: users/groups (`bb user:*`, `bb token:*`), ACL via
  `_collection.edn` and frontmatter `read_groups`, ingest from `/admin` or
  `bb ingest`, traces, backups — noting most admin work is CLI/files.
- `docs/howto/user.md`: login, asking, Debug panel, citations, document
  viewer. Skeleton: the 2026-09-25 walkthrough (sample corpus, alice and
  bob). No text from `no-commit/`.

## Task order

One commit per task, TDD throughout:

1. T5.1a `llm/http` error classification + configurable timeouts
2. T5.1b traces on failure, `trace_id` in 503
3. T5.1c health live/full + Kamal
4. T5.1d `vllm:check` long rerank probe + `bb eval` degraded warning
5. T5.2a session lifetime and revocation
6. T5.2b lookup split
7. T5.2c §18.3 suite
8. T5.x promoted backlog items, one commit each
9. T5.3 docs
10. Whole-range review by a fresh Opus reviewer, one fix pass, stop.

## Testing

- Unit: http error mapping (IOException subclass, non-JSON 200), health
  cache expiry, session predicate (missing/old/revoked/valid), citation
  regex with full-width digits, orphan `</think>`, `</sources>`
  neutralization, extra-body validation.
- Integration: health endpoints with stub servers up/down; 503 + trace
  for `/search` and `/ask` (API and web); session flows through the web
  client; the §18.3 suite.
- Real models (`:vllm` tag, skipped without env): `vllm:check` long probe.

## Out of scope

SSE streaming (T5.4), `/login` rate limiting, per-device session
revocation, SSO, HyDE/doc2query, multi-process ingest locking.
