# Handoff: after Phase 5 → next work

> Written 2026-09-25 for a fresh Claude Code session. If you are the agent
> picking this up: **read this whole file before touching code**, then the
> files it lists. Reply to the user in Traditional Chinese.

## Where things stand

- Repo `/Users/laurencechen/ForceUnion/levinrag`, branch `main` (every
  phase is committed straight to `main`; that is the established
  practice). Working tree clean at `7b10ce7` or later.
- **Phases 0–5 of the initial spec are done.** The only unfinished task of
  the initial spec is T5.4 (SSE streaming for `/ask`), deferred by the user.
- `clojure -X:jvm-opts:test`: 219 tests, 0 failures. `clj-kondo --lint src
  test dev`: 0 warnings. `bb browser-check`: OK. `bb docs:check`: OK.
- Display name is **LevinRAG**; identifiers stay lowercase (repo,
  namespace root `replware.levinrag`, Kamal service, paths).

### What happened since the Phase 4 → 5 handoff (32 commits)

1. **Phase 5** (spec `docs/superpowers/specs/2026-09-25-phase5-hardening-design.md`,
   plan `docs/superpowers/plans/2026-09-25-phase5-hardening.md`): health
   `/api/v1/health/live` + full `/api/v1/health` (parallel probes, 5 s each,
   30 s single-flight cache), dependency failures → 503 + a failure trace,
   configurable timeouts, long-document rerank probe in `bb vllm:check`,
   sessions (8 h, per-user revocation), `lookup-admin`/`lookup-acl`, the
   §18.3 end-to-end security suite, backlog minors, README + HowTos.
   Reviewed by a fresh Opus reviewer; one fix pass.
2. **Rename** `hybridrag.*` → `replware.levinrag.*` (verified: identical
   deftest inventory, coverage, uberjar, real-model smoke test).
3. **Spec restructure**: `SPEC.md` is the *current* spec (same section
   numbers as the initial one, plus §21 "Remaining work and next steps"
   and Appendix A "differences from the initial spec"). The frozen initial
   spec is `docs/design/2026-09-22-initial-spec.md` (Chinese, no English
   summary — user's choice).
4. **Security fix**: a non-admin's own trace no longer shows pre-ACL
   counts (`raw-hits`, `acl-starvation`); admins still see everything
   (`trace/view-for`). User requirement: never make admin/ops debugging
   harder for the sake of security.
5. **Design rationale**: short version in README ("Design rationale"),
   full argument in `docs/design/rationale.md`. Checked against the code;
   an external critique (ChatGPT) was partly wrong — Datalevin has **no**
   history/as-of (it is not Datomic); ACL is materialized, not Datalog
   rules. Keep those claims out.
6. **Bilingual docs**: English is the default and **authoritative**;
   `*.zh-TW.md` are translations. Pairs: README, SPEC, VLLM_SETUP,
   `docs/howto/{ops,admin,user}`, `docs/design/rationale`. Change both in
   the same commit; `bb docs:check` enforces headings/links/anchors/language
   switches (part of `bb check`).
7. **Rebuild without stopping**: verified that `DATA_DIR=<other dir> bb
   reindex` runs while the server serves, then stop/swap/start = ~14 s
   downtime. Documented in ops (en + zh-TW); a hot switch without restart
   is in SPEC §21.2.

## Absolute rules

1. **`no-commit/` is the user's private book corpus and results.** Never
   commit it, copy it, or quote its text in committed files. Before every
   commit: `git ls-files no-commit | wc -l` must print 0.
2. **`haystack-design-issue.md`** (repo root, gitignored) analyses another,
   private repo. Never commit it or mention that repo in committed docs.
3. Datalevin APIs are verified in the REPL before use (SPEC §0 rule 2).
4. One commit per task; messages end with the `Co-Authored-By` line from
   the system prompt's attribution instructions.
5. Docs: English authoritative, zh-TW translation, both in the same commit,
   `bb docs:check` before committing (CLAUDE.md, SPEC §0 rule 9).
6. Product decisions (thresholds, analyzer, what to build next, wording of
   the rationale) are the user's — measure, report, recommend.
7. After any change that could break the running system, verify beyond
   unit tests (user's explicit request): full clean-JVM suite, `bb
   browser-check`, restart the local server and hit it with real models,
   and for build-affecting changes build and boot the uberjar with `:prod`.

## How the user likes to work

- **Discuss before changing behaviour or security.** Present a short
  design in chat, wait for "ok". For multi-item requests the user asked to
  **stop after each item and confirm** before the next.
- Auto-memory says: within an approved *phase plan*, commit each task and
  keep going; stop for review when the phase is done.
- Negative controls are expected: show that a new test fails when the
  behaviour is removed (done in the REPL, not committed).
- Subagents are welcome for parallel, independent work (translations were
  done by four parallel subagents with one brief + a glossary); the lead
  verifies. A fresh Opus reviewer over the whole range ends each phase.
- Report in Traditional Chinese: what was done, how it was verified, the
  rulings made on the user's behalf, and the open decisions.

## Read these, in this order

1. `CLAUDE.md` (nREPL start command, test loop, bilingual rule).
2. `SPEC.md` — especially §0, §1.2 (success criteria and their status),
   §21 (remaining work), Appendix A.
3. `docs/decisions.md` — the 2026-09-25 entries at least.
4. `docs/backlog.md` — "After Phase 5" and the entries below it.
5. `docs/design/rationale.md` and README "Design rationale".
6. `docs/howto/ops.md` (env vars, health, backups, rebuild procedure).

## What is next (SPEC §21) — ask the user which to start

The user was about to choose from §21.2 when this handoff was written.

- **§21.1 needs the user's environment**: a real Kamal deploy; latency on a
  GPU vLLM (§1.2 target p50 < 800 ms, local M1 rerank alone is 1.3–3 s);
  scale test near 100k chunks (`dev/spikes/gen_synthetic_corpus.clj` can
  generate a synthetic corpus); 5–10 real questions to decide whether
  `rerank-min-score` can tighten from -7 toward -4.
- **§21.2 can be done independently**: T5.4 SSE streaming (conflicts with
  citation validation — needs a design doc first); `/login` rate limiting
  (user declined it for Phase 5); citation ranges `[1-3]`; multi-process
  ingest locking; single-character CJK queries (unigrams + reindex);
  hot index switch without restart.
- **§21.3 needs a user decision first**: HyDE/doc2query, conversation
  history + multi-turn, Keycloak SSO, a larger/harder eval corpus.
- **§21.4 before publishing on GitHub**: confirm `no-commit/` never
  entered git history (`git log --all -- no-commit` and a content scan).
- Backlog also has: web UI localization (UI is Traditional Chinese),
  timing side channel on pre-ACL hits (low priority).

## Gotchas already paid for

- **Pre-commit hook counts raw `( [ {` vs `) ] }` per staged .clj file**,
  strings/regexes/comments included. In regexes write `\x28` `\x29` `\x5b`
  `\x5d` for literal brackets.
- **zsh does not word-split unquoted variables**: `sd a b $FILES` passes one
  argument; use `| xargs`. Don't use `echo =====` (zsh `=` expansion).
- `sed` is blocked by a hook: use `sd`, the Edit tool or a small `bb`
  script. Text edits to `.json` are blocked too; `resources/public/manifest.json`
  has comments (not valid JSON for jq) — use the Edit tool.
- `rg` here behaves case-insensitively for lowercase patterns; `rg`
  respects `.gitignore` (manifest.json is tracked but ignored by rg —
  use `git ls-files | xargs rg` for "every tracked file").
- Multi-line edits with quotes: write a bb script to the scratchpad that
  does exact string replacements (fail if the old string is missing).
- Closed Datalevin conns throw `AssertionError`, not `Exception`; an
  unbounded `d/datoms` is an eager vector of the whole DB (use
  `d/seek-datoms … n`); `d/rseek-datoms db :ave attr nil nil n` for
  "newest n".
- `use-fixtures :each` twice replaces the first; web login replaces the
  session and CSRF token (`web-fixtures/logged-in` re-GETs `/`).
- Test output is long: redirect to a file and grep for
  `FAIL in|ERROR in|Ran|assertions`.
- LM Studio: HTTP/1.1 only (handled), HTTP 200 + error payload for unknown
  paths, ignores `chat_template_kwargs`; use
  `VLLM_CHAT_EXTRA_BODY='{"reasoning_effort":"none"}'` for Qwen3. The
  first chat probe after idle can exceed the 5 s health probe timeout
  (reported `down`, recovers on the next 30 s round).
- Rerank scores are llama.cpp raw logits; the -7.0 threshold is in those
  units.

## Local environment (may still be running)

- tmux `nrepl`: nREPL on 1667 (`clojure -M:jvm-opts:test:dev:nrepl`).
  After renames or deleted namespaces, restart it for a clean state.
- tmux `levinrag`: the walkthrough server on http://localhost:8000
  (`:default` profile), data in `./data/` (gitignored): sample corpus
  ingested, users `admin`/`admin-pw`, `alice`/`alice-pw` (all, hr),
  `bob`/`bob-pw` (all, engineering). Start it with:

  ```bash
  export DATA_DIR=./data CORPUS_DIR=corpus-sample VLLM_API_KEY=lm-studio
  export VLLM_EMBED_BASE_URL=http://localhost:1234/v1 VLLM_EMBED_MODEL=text-embedding-bge-m3
  export VLLM_RERANK_BASE_URL=http://localhost:8002 VLLM_RERANK_PATH=/v1/rerank VLLM_RERANK_MODEL=bge-reranker-v2-m3
  export VLLM_CHAT_BASE_URL=http://localhost:1234/v1 VLLM_CHAT_MODEL=qwen/qwen3-8b
  export VLLM_CHAT_EXTRA_BODY='{"reasoning_effort":"none"}'
  clojure -M:jvm-opts -e "(require '[integrant-extras.core :as ig-extras]) (ig-extras/run-system {:profile :default :config-path \"config.edn\"}) @(promise)"
  ```

- Models: LM Studio (`lms ps`: text-embedding-bge-m3, qwen/qwen3-8b) and
  the llama.cpp reranker on :8002 (`curl localhost:8002/health`; start
  command in `VLLM_SETUP.md`). M1 16 GB — memory is tight with all three.
- Smoke test with a throwaway token: `bb token:create alice --label x`,
  `curl localhost:8000/api/v1/ask -H "Authorization: Bearer <token>" …`,
  then `bb token:revoke <prefix>`.

## Suggested first step

Summarize this handoff back to the user in a few lines (Traditional
Chinese), then ask which item of SPEC §21 to start. Anything that changes
behaviour gets a short design in chat first; a new subsystem (e.g. SSE)
gets the full brainstorming → spec → plan flow.
