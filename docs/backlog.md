# Backlog

Ideas and out-of-scope improvements noticed while building hybridrag.
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
- **`/api/v1/health`'s DB liveness check is shallow.**
  `hybridrag.handlers/conn-ok?` only calls `(d/db conn)`, which likely
  doesn't throw on a closed-but-still-referenced store. Worth confirming
  during T5.1 whether a damaged/closed store can still report `"ok"` —
  if so, a trivial query or datom-count would be a real liveness probe
  instead.

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

- **Full-width digits in citations** (`［１］`): Java `\d` is ASCII-only, so
  they are not recognized and the answer gets a false `:uncited-answer`.
  Cheap fix: `(?U)` on `citation-re` and the `numbers` regex. Ranges
  (`[1-3]`) likewise unrecognized.
- **`</think>` without an opening tag** (models whose template pre-fills
  `<think>`, served without a reasoning parser) is not stripped: the user
  sees the reasoning. Drop everything up to a leftover `</think>`.
- **Invalid or non-object `VLLM_CHAT_EXTRA_BODY`** → 500 on every `/ask`.
  Validate `map?` and fail at `ig/init-key` instead.
- **Passage text inserted verbatim into `<sources>`**: a document
  containing `</sources>` can re-frame the prompt (indirect prompt
  injection by document authors; no ACL impact). Neutralize the tags.
- **§18.3 test checks output, not model input**: also assert restricted
  titles/paths never appear in the `messages` handed to the chat stub.

Set aside by the reviewer, for later phases:

- **No trace when `/search` or `/ask` fails with 503** (§14 says every
  request writes one) → T5.1.
- **`llm/http.clj`**: other `IOException`s and non-JSON 200 bodies give
  500 instead of 503 → T5.1.
- **No-evidence only fires for users with nothing readable** while
  `:rerank-min-score` is nil (the semantic channel always returns
  neighbours) — calibrate the threshold with the book-corpus eval.

## From Phase 4 review (2026-09-25)

Deferred minors (reviewer findings, not fixed):

- **`docs/lookup` has the admin bypass inside a shared function**
  (`(or (:admin? principal) ...)`); SPEC §9 asks for a separate admin
  function, not a parameter switch. Split into `lookup-admin` /
  `lookup-acl`, or record the exception.
- **Two sources for `CORPUS_DIR`/`DATA_DIR`**: the server reads
  `config.edn` (`#or [#env CORPUS_DIR "./corpus"]`), the runner reads
  `config/corpus-config`; they can drift (e.g. `CORPUS_DIR=""`), and the
  runner ignores the `:test` profile's `data-test/`. Pass both from
  `config.edn`.
- **`trace/recent` pulls and sorts every trace** on each `/admin` load;
  use a range scan on `:trace/at` or cap it.
- **No session lifetime**: cookie-store sessions never expire and logout
  cannot revoke a copied cookie. Store issued-at and reject old sessions
  in `wrap-session-auth`.
- **`/login` has no rate limiting or lockout** (spec silent).

Set aside by the reviewer:

- `bb ingest` from the CLI concurrently with a web-triggered job
  (multi-process locking).
- An existing but empty `CORPUS_DIR` still empties the index from the
  admin button (only a missing dir is refused).
- External images in rendered Markdown load from third-party hosts
  (privacy, not active content).
