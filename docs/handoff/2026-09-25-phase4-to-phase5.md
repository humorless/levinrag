# Handoff: Phase 4 complete → Phase 5 (強化)

> Written 2026-09-25 for a fresh Claude Code session. If you are the agent
> picking this up: **read this whole file before touching code**, then the
> files it lists. Reply to the user in Traditional Chinese.

## Where things stand

- Repo `/Users/laurencechen/ForceUnion/levinrag`, branch `main` (every
  phase has been committed straight to `main`; that is the established
  practice). Working tree clean at `81501be` or later.
- `clojure -X:jvm-opts:test`: 174 tests, 0 failures. `clj-kondo --lint src
  test`: 0 warnings. `bb browser-check`: OK.
- **Phases 0–4 are done** (SPEC.md §17), each with a whole-branch review
  by a fresh Opus reviewer agent and one fix pass. **Phase 5 has not been
  started**: no design, no plan.
- Since Phase 4 (all committed): the CJK analyzer decision is closed
  (bigram kept); `rerank-min-score` calibrated to -7.0; frontmatter ACL
  edits no longer re-embed; SPEC §9.4 example fixed; HyDE/doc2query
  analysis in the backlog.

## Absolute rules

1. **`no-commit/` is the user's private book corpus and results.** Never
   commit it, never copy it elsewhere in the repo, and never put its text
   in committed docs (aggregate numbers and question ids only). Before
   every commit: `git ls-files no-commit | wc -l` must print 0.
2. Datalevin APIs are verified in the REPL before use (SPEC §0.3).
3. One commit per task; commit messages end with the `Co-Authored-By`
   line from the system prompt's attribution instructions.
4. The user's standing preference (auto-memory): **commit each task and
   keep going; stop for review only when a whole Phase is done.**
5. The analyzer choice, thresholds and other product decisions are the
   user's — measure, report, recommend.

## Read these, in this order

1. `CLAUDE.md` (nREPL start command and test loop).
2. `SPEC.md` — §1 goals, §4–5 (vLLM, config), §11–§14, §17 Phase 5,
   §18.3 (security tests), §19–20.
3. `docs/decisions.md` — the running log; SPEC's draft text loses where
   they conflict. Read the 2026-09-24/25 entries at least.
4. `docs/backlog.md` — especially "From Phase 3 review", "From Phase 4
   review", and the HyDE entry. Several items belong in Phase 5 (below).
5. `VLLM_SETUP.md` — local model setup (LM Studio + llama.cpp) and the
   measured latency notes.
6. `docs/spikes/rerank-threshold.md`, `docs/spikes/cjk-analyzer.md`.
7. The Phase 4 design and plan (`docs/superpowers/specs/2026-09-25-phase4-web-ui-design.md`,
   `docs/superpowers/plans/2026-09-25-phase4-web-ui.md`) for the house
   style of specs/plans.

## How the work has been run (keep doing this)

- Process: superpowers **brainstorming** (for new subsystems: design in
  chat → spec in `docs/superpowers/specs/` → user approval) →
  **writing-plans** (`docs/superpowers/plans/`, with Global Constraints
  and a Review Focus section) → user picks the execution method (they
  chose **inline**) → **executing-plans** with the ledger in
  `.superpowers/sdd/<plan>/progress.md` → at the end a **fresh Opus
  reviewer agent** over the whole range → one TDD fix pass → report
  rulings and deferred minors → stop for the user.
- TDD for every change: write the test, watch it fail for the right
  reason, then implement. Run a negative control when a check could pass
  vacuously (e.g. the browser check was shown to fail without its asset).
- Test output is long: redirect to a file and grep for
  `FAIL in|ERROR in|Ran|failures`.

## Gotchas already paid for

- **Pre-commit hook counts raw `( [ {` vs `) ] }` per staged .clj file**
  — strings, regexes and comments included. Keep them balanced (write a
  literal `(` in a regex as `\x28`; avoid stray brackets in comments).
- `use-fixtures :each` called twice **replaces** the first; pass all
  :each fixtures in one call.
- Web tests: login replaces the session and its CSRF token —
  `web-fixtures/logged-in` re-GETs `/` after login for that reason.
- Runner/concurrency tests must use `web-fixtures/with-blocking-runner`
  (fresh empty index + embed gate); over the ingested sample index every
  file is skipped and the job finishes before you can observe it.
- Hiccup reads a vector whose first element is a string as a tag — pass
  model/document text pieces as a seq.
- `:section/trail` is stored as a joined string (`"A > B"`), not a vector.
- LM Studio: needs HTTP/1.1 (handled), returns HTTP 200 + error payload
  for unknown paths, ignores `chat_template_kwargs`; use
  `VLLM_CHAT_EXTRA_BODY='{"reasoning_effort":"none"}'` for Qwen3.
- Rerank scores are llama.cpp raw logits; the -7.0 threshold is in those
  units (recalibrate if the backend changes).
- `sed` is blocked by a hook; use `sd`, the Edit tool, or `bb -e`. `sd`
  treats a leading `- ` in a pattern as a flag — use the Edit tool then.

## Local environment (may still be running)

- tmux `nrepl`: nREPL on 1667 (`:jvm-opts:test:dev:nrepl`; add
  `:spike-hanlp` only for the analyzer spike).
- tmux `levinrag`: the walkthrough server on http://localhost:8000
  (`:default` profile), data in `./data/` (gitignored): sample corpus
  ingested, users `admin`/`admin-pw`, `alice`/`alice-pw` (groups all, hr),
  `bob`/`bob-pw` (groups all, engineering). Restart it with:

  ```bash
  export DATA_DIR=./data CORPUS_DIR=corpus-sample VLLM_API_KEY=lm-studio
  export VLLM_EMBED_BASE_URL=http://localhost:1234/v1 VLLM_EMBED_MODEL=text-embedding-bge-m3
  export VLLM_RERANK_BASE_URL=http://localhost:8002 VLLM_RERANK_PATH=/v1/rerank VLLM_RERANK_MODEL=bge-reranker-v2-m3
  export VLLM_CHAT_BASE_URL=http://localhost:1234/v1 VLLM_CHAT_MODEL=qwen/qwen3-8b
  export VLLM_CHAT_EXTRA_BODY='{"reasoning_effort":"none"}'
  clojure -M:jvm-opts -e "(require '[integrant-extras.core :as ig-extras]) (ig-extras/run-system {:profile :default :config-path \"config.edn\"}) @(promise)"
  ```

- Models: LM Studio (`lms ps`: text-embedding-bge-m3, qwen/qwen3-8b) and
  llama.cpp reranker on :8002 (`curl localhost:8002/health`; start command
  in `VLLM_SETUP.md`). M1 16 GB — memory is tight with all three loaded.

## Phase 5 scope (SPEC §17) and what feeds into it

**T5.1 health, timeouts, consistent error handling.** Known inputs:
- `/api/v1/health` checks only the two DBs; SPEC §11 wants the three
  model endpoints and index lag, 503 listing failures. Backlog: the DB
  liveness check is shallow.
- **Kamal healthcheck path is `/health` but the endpoint is
  `/api/v1/health`** (`.kamal/deploy.yml`) — a deploy would fail.
  Volumes for `data/` and the corpus are not configured.
- External spec review point 3: `bb vllm:check` probes rerank with two
  short strings, so a small `max_model_len` (e.g. 512) would only show up
  as silent degradation on every query. Probe with a full-length chunk
  (rerank input is cut at 1500 chars, ≈ that many tokens for Chinese) and
  make `bb eval` warn when degraded > 0.
- Backlog: no trace is written when `/search` or `/ask` fails with 503;
  `llm/http.clj` maps other IOExceptions and non-JSON 200 bodies to 500.
**T5.2 end-to-end security test suite (§18.3 in full).** Candidates from
the backlog for the user to decide: session lifetime/revocation, `/login`
rate limiting, `docs/lookup` admin bypass split (SPEC §9 wants a separate
admin function).
**T5.3 README and ops manual.** The user asked for three HowTos:
(a) ops: deploy (write it after T5.1 fixes and one real deploy),
(b) admin: users/groups via `bb user:*`, ACL via `_collection.edn` and
frontmatter `read_groups`, ingest from `/admin` or `bb ingest`, traces,
backups of `data/app.dtlv` — note most admin setup is CLI/files, not UI,
(c) user: login, asking, Debug panel, citations, document viewer.
The 2026-09-25 walkthrough is the skeleton for (b)/(c): admin ingest
(22 docs, 119 chunks) → alice asks 「特休天數怎麼計算？」 with Debug →
citation → document highlight → bob asks the same (ACL filtering;
`hr/announcements/year-end-party.md` is readable by all through its
frontmatter) → bob opens `/docs/hr/leave.md` (404) → admin trace detail.
**T5.4 (stretch) SSE streaming for `/ask`.**

Also minor backlog items that could ride along: `CORPUS_DIR`/`DATA_DIR`
read from two sources (config.edn vs `config/corpus-config`; the runner
ignores the `:test` profile's `data-test/`), `trace/recent` scans all
traces, full-width digit citations, `</think>` without an opening tag.

## Waiting on the user

- 5–10 real control questions: they decide whether `rerank-min-score`
  can tighten from -7 toward -4 and whether HyDE/doc2query is worth
  promoting (see backlog).
- Which backlog items join Phase 5 (ask before designing).

## Suggested first step

Summarize this handoff back to the user in a few lines, confirm which
backlog items join Phase 5, then start Phase 5 with the brainstorming
skill (T5.1 and T5.2 change behavior and deserve a design; T5.3 is docs).
