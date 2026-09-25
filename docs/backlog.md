# Backlog

Ideas and out-of-scope improvements noticed while building levinrag.
Nothing here should be implemented without first promoting it to a
task in SPEC.md §17. See SPEC.md §20 for the spec author's own backlog.

## From Phase 0

- **`accessible-doc-ids` caching strategy (feeds T2.1).** The T0.5 spike's
  working ACL-filter alternative (`docs/decisions.md`, "ACL query
  pattern... join-based over-fetch too slow at scale") recomputes a
  user's accessible-doc-id set from scratch on every query in its
  benchmark. Group membership changes far less often than queries are
  issued, so it's a good caching candidate (per-session or short-TTL).
  T2.1 should decide cache-vs-recompute deliberately, not default to
  whichever is easiest to wire up.
- **T0.5's "root cause" is an unverified hypothesis, not a proven fact**
  (`docs/spikes/acl-query-perf.md`, "Root cause" section, explicitly
  labeled as such). The committed benchmark already proves the literal
  §9.3 query fails its AC and the doc-id-set alternative is faster and
  correct — that's settled. The *why* (query planner not scoping the ACL
  join to the fulltext over-fetch window) was only checked in a
  throwaway, non-committed REPL session. If anyone wants that confirmed
  rigorously (the 8-point scaling curve + zero-fulltext-hit isolation
  experiment described but not committed), that's optional follow-up
  work, not a blocker.
- **CJK tokenizer integration (HanLP 1.x priority over Jieba, per
  SPEC.md §8)** was added as a design decision in commit `4ac13e2` but
  never implemented or spiked against Datalevin's actual UDF-analyzer
  mechanism (T0.4's `docs/spikes/fulltext.md` only proved registration
  works with a toy whitespace-splitter analyzer, not real CJK content).
  This is Phase 1's `search/analyzer.clj` (SPEC §8) first job — start
  from `fulltext.md`'s confirmed registration mechanism, not from
  scratch.

## From design review (2026-09-24)

- **SSO via Keycloak (OIDC).** SPEC §1.3 lists SSO as a non-goal, but
  production use needs it: local passwords make offboarding a manual
  `bb user:groups` step, and a forgotten one is an ACL leak that §18.3
  cannot catch. Keycloak is the chosen IdP. Notes for when this is
  promoted to a task:
  - Keycloak can emit a `groups` claim with plain group names (group
    mapper, "full group path" off), so groups can map 1:1 to the
    `:read-groups` strings in `_collection.edn` — no GUID mapping table.
  - Two ways in: (a) in-app authorization code flow + PKCE, verifying the
    ID token against Keycloak's JWKS (`ring-oauth2` / `buddy-sign` /
    nimbus-jose-jwt); (b) oauth2-proxy in front passing
    `X-Forwarded-Email` / `X-Forwarded-Groups`. (b) needs no OIDC code
    but levinrag must then bind to localhost or verify the proxy, since
    those headers are otherwise forgeable. Needs its own §18.3 tests.
  - Key users by `iss` + `sub`, not email (emails change); reserve e.g.
    `:user/oidc-sub` in `app.dtlv`.
  - Groups are captured at login, so session max-age bounds how long a
    revoked group stays effective (e.g. 8h) — pick deliberately.
  - API tokens (§13) never expire and bypass the IdP; a user disabled in
    Keycloak keeps a working token. Add expiry or tie tokens to IdP
    status.
  - Cheap prep that keeps this additive: at T2.0, keep "identity source"
    separate from the principal. Password, OIDC, and proxy headers should
    all resolve to the same `{:username :groups :admin?}`, so Retriever
    and ACL code never know where identity came from.

- **End-user chat UX (question answering, citations, conversation
  history).** Evaluated LibreChat for this and rejected it as too heavy
  (Node + MongoDB + Meilisearch vs. §1.1's single JVM). The Phase 4 UI is
  single-shot Q&A plus a debug panel, aimed at the developer. For
  day-to-day use by colleagues, these are the missing pieces:
  - Conversation history (per-user list of past conversations; could
    reuse `app.dtlv` traces as the storage base).
  - Multi-turn follow-ups ("那主管呢？"). Only retrieving on the last
    message recalls poorly; this depends on query rewriting (SPEC §20),
    so the two should be promoted together.
  - Citation UX: clickable `[n]` jumping to a source panel and to the
    highlighted range in the doc viewer (T4.3 is the base).
  - Streaming output (SPEC T5.4) — expected by users of a chat UI.
    Tension with T3.1: citation validation runs on the complete answer
    and strips invalid `[n]`, but streamed text is already sent. Decide
    between a trailing correction, delayed client-side rendering, or
    buffering citations when streaming is promoted.
  - Markdown rendering of answers, copy button, 👍/👎 feedback (the
    feedback item is already in SPEC §20).
  Keep the debug panel as a developer view next to the chat view, not
  replaced by it.

## From Phase 2

- **A bigger / harder eval corpus.** On the 22-doc sample corpus every
  variant scores recall@5 = recall@10 = 1.0 (see decisions 2026-09-24,
  T2.6), so the eval cannot yet show whether semantic, rerank or graph
  help, nor calibrate `rerank-min-score`. Needs more docs per topic (near
  duplicates, distractors) and questions whose answer is not in the title.
- **Query-side exact-token matching for identifiers** (e.g. `v2.7.3` only
  ranks 7th lexically because of the `v2` fragment). Evaluate before
  deviating from §8.2.

## From Phase 3 review (2026-09-25)

Deferred minors (reviewer-confirmed, not fixed):

- **Citation ranges** (`[1-3]`) are not recognized (full-width digits are,
  since Phase 5).

Set aside by the reviewer, for later phases:

- **No-evidence only fires for users with nothing readable** while
  `:rerank-min-score` is nil (the semantic channel always returns
  neighbours) — calibrate the threshold with the book-corpus eval.

## From Phase 4 review (2026-09-25)

Deferred minors (reviewer findings, not fixed):

- **`/login` has no rate limiting or lockout** (spec silent).

Set aside by the reviewer:

- `bb ingest` from the CLI concurrently with a web-triggered job
  (multi-process locking).
- An existing but empty `CORPUS_DIR` still empties the index from the
  admin button (only a missing dir is refused).
- External images in rendered Markdown load from third-party hosts
  (privacy, not active content).

## From the book-corpus eval and threshold calibration (2026-09-25)

- **Abstract questions retrieve poorly; try HyDE or doc2query.** On the
  local book corpus (reader-style questions such as "why is X worth
  practising?", answers written as narrative or argument, little shared
  vocabulary) the semantic channel reaches recall@10 0.53 and
  hybrid+rerank MRR@10 0.58 (`docs/spikes/cjk-analyzer.md`); correct
  passages get a median rerank score of about -3, which is why
  `rerank-min-score` could only be set conservatively at -7
  (`docs/spikes/rerank-threshold.md`). Concrete questions on the sample
  corpus do not show this (answers score ≥ 0).
  - **HyDE** (query side): the chat model writes a short hypothetical
    answer; embed that (alone or with the question) for the semantic
    channel, so the match is answer-to-answer. Cost: one extra chat call
    per question — on the local M1 setup (prefill ~125 tok/s, decode
    ~25 tok/s, `VLLM_SETUP.md`) roughly +5–10 s per `/ask`, and it also
    slows `/search`. A hallucinated hypothetical answer can pull in wrong
    passages; the reranker still judges against the real question.
  - **doc2query** (index side): at ingest the chat model generates the
    questions each chunk answers; index them with the chunk (extra text
    or extra vectors), so the match is question-to-question. Cost moves
    to ingest (one chat call per chunk, re-run when a chunk changes), no
    query-time latency.
  - SPEC §1.3 lists query rewriting as an MVP non-goal, so either needs
    promoting to a task first. **Trigger:** the user's real control
    questions (5–10) look like the book questions and score poorly in
    eval. **Measure** with `spikes.cjk-analyzer/run-corpus-eval` plus a
    new variant (semantic channel on the HyDE text), and re-run the
    threshold sweep: a better semantic match should also raise answer
    rerank scores and allow a tighter `rerank-min-score`.

## After Phase 5 (2026-09-25)

The Phase 5 review's deferred minors were fixed afterwards. Still open
from the handoff:

- `/login` rate limiting (user decision: not in Phase 5).
- SSE streaming for `/ask` (T5.4, deferred).
- Multi-process ingest locking (the docs now say CLI ingest only with the
  server stopped).
- **Timing side channel on pre-ACL hits**: the lexical stage's `ms`
  (visible to the trace owner) grows with the number of hits before the
  ACL filter. Hard to exploit with over-fetch capped at 200; hide or
  bucket stage timings for non-admins if it ever matters.
- **Rebuild the index without downtime**: `bb reindex` deletes the
  `index.dtlv` the server has open, so it needs the server stopped.
  Build the new index in a fresh directory (same corpus, possibly a new
  embedding model or analyzer), then have the server switch its
  index-conn to it (e.g. an admin action) and drop the old one. Rebuild
  cost is dominated by re-embedding every chunk (SPEC §21.1 scale).
- **Web UI localization**: labels, messages and the prompt's default
  answer language are Traditional Chinese. Add an English UI (message
  catalogue, language choice per user or per `Accept-Language`); the
  English docs quote the Chinese labels with glosses until then.
