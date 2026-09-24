# Decisions log

## 2026-09-22 — Project namespace root

Spec text (SPEC.md, throughout): shows `levinrag` as the namespace root
and directory layout (`src/levinrag/...`).
Actual: user explicitly asked for project name `hybridrag`.
Decision: every namespace, file path and generated artifact name uses
`hybridrag`; `levinrag` only survives as the containing directory name and
in the original `levinrag-spec.md` filename.

## 2026-09-22 — Datalevin version

Spec text (SPEC.md §3): "Datalevin，pin 最新 1.0.x".
Actual: Clojars' newest release is 1.1.0, one line ahead of the 1.0.x
series (latest 1.0.x is 1.0.2). The user explicitly instructed: use the
true latest release, ignore the spec's "1.0.x" line.
Decision: pinned `datalevin/datalevin {:mvn/version "1.1.0"}`. Verified
before pinning: `doc/install.md`'s required JVM `--add-opens` flags are
unchanged from 1.0.2, and `get-conn`/`close`/`db` have identical
signatures in both versions (1.1.0 only adds new optional WAL/HA params
this project doesn't use).

## 2026-09-22 — top-level `:jvm-opts` in `deps.edn` is not read by the Clojure CLI

Spec text: the plan originally specified a top-level `:jvm-opts ["--add-opens=..." ...]`
entry in `deps.edn` to supply the JDK module-opens flags Datalevin 1.1.0
needs on every JVM invocation.

Actual: the Clojure CLI/`deps.edn` reference confirms top-level `:jvm-opts`
in `deps.edn` is not read by the `clojure`/`clj` CLI outside of an alias —
only `:aliases {<alias> {:jvm-opts [...]}}` is honored, and the flags must
be pulled in by naming that alias on the command line (`-M:jvm-opts` etc).
`clj-kondo`'s own `deps.edn` lint independently confirms the same reading.

Decision: moved the required `--add-opens=java.base/java.nio=ALL-UNNAMED`
and `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED` flags into a dedicated
`:jvm-opts` alias in `deps.edn` (`{:aliases {:jvm-opts {:jvm-opts [...]}}}`),
which is then combined into every task/command that boots a JVM with
Datalevin on the classpath (`bb test`, `bb clj-repl`, `bb build`, the
standalone Dockerfile `CMD`, spike scripts, etc — see the alias's own
comment in `deps.edn` for the full list of callers).

## 2026-09-22 — index.dtlv schema deferred to Phase 1

Spec text (SPEC.md §6.1, §0.3): defines the full index-schema including
`:chunk/index-text` with `:db/fulltext`/`:db/embedding`/autoDomain
options, but flags the store-options for those features `⚠️ VERIFY` in
T0.3/T0.4, and §0.3 forbids using unverified Datalevin API from memory.
Decision: `index-conn` opens in T0.1 with an empty schema (`{}`), just to
prove the connection lifecycle works. The real index-schema is written in
Phase 1 (T1.1/T1.4) once the T0.3/T0.4 spikes (Tasks 3–4 of this plan)
confirm the correct 1.1.0 syntax.

## 2026-09-22 — Partial /api/v1/health in T0.1

Spec text (SPEC.md §11): `/health` should report "DB 狀態，三個 vLLM
endpoint 可達性，index lag；任一依賴失敗回 503".
Decision: T0.1's health handler only checks the two Datalevin connections
(that's this task's AC). vLLM reachability and index lag are added in
T5.1 once the vLLM clients (Task 2) and ingestion (Phase 1) exist.

## 2026-09-22 — Project generated via local `cljstack` alias

Generation command actually used: `cljstack hybridrag . --db sqlite
--overwrite`, i.e. `neil new io.github.abogoyavlensky/clojure-stack-lite
hybridrag . --db sqlite --overwrite` — target-dir `.` so the project
lands directly in the repo root instead of a nested `hybridrag/`
subdirectory. `:auth` left at its default (`false`).


## 2026-09-22 — Embedding path: Path B chosen

Spec text (SPEC.md §6.1 draft store-options): shows `:chunk/index-text` with
both `:db/fulltext` and `:db/embedding` on the same attribute.

Actual: Path A (`:db/embedding` + `:openai-compatible`) requires `VLLM_EMBED_
API_KEY` at connection time and attempts to call vLLM during `transact!`.
Path B (`:db.type/vec` + application-side embedding) has no vLLM dependency
for schema definition or data insertion.

Decision: **Path B** — `:chunk/index-text` is `:db.type/string` with
`:db/fulltext` for lexical search. Embedding vector stored separately in
`:chunk/vec` (`:db.type/vec`) computed at application time via
`hybridrag.llm.embed/embed-batch!`. This keeps the full stack testable
and buildable without a running vLLM instance.

See `docs/spikes/embedding.md` for full spike findings and query syntax.

## 2026-09-22 — ACL query pattern (SPEC §9.3): join-based over-fetch too slow at scale

Spec text (`SPEC.md` §9.3, T0.5 AC): the lexical channel's ACL filter is
over-fetch top-200 fulltext hits, then a Datalog join
`[?e :chunk/doc ?d] [?d :doc/effective-groups ?g]` against the user's
group list bound via `:in $ ?q [?g ...]`. AC: p50 < 100 ms at 10k docs /
100k chunks / 50 groups, else propose an alternative here (the spec text
itself suggests `:doc-filter` pre-filtering, "if it applies before
top-k").

Actual (T0.5 spike, `docs/spikes/acl-query-perf.md`): measured p50 for
the literal query at that scale, worst case (user in all 50 groups),
across 4 independent corrected-corpus runs of committed code
(`dev/spikes/gen_synthetic_corpus.clj -main`) — 99.55, 100.29, 101.32,
102.77 ms. p50 straddles the 100 ms line run-to-run and p90 exceeds it
every time (126.91-133.77 ms). **Does not reliably meet the plan's own
AC.** (An exploratory, non-committed REPL investigation suggested the
cost tracks the size of the user's accessible corpus rather than the
fulltext over-fetch window — see `docs/spikes/acl-query-perf.md`'s "Root
cause" section, explicitly marked there as a hypothesis, not a verified
fact.) `:doc-filter`, the spec's own suggested fallback, was already
found broken in Datalog integration by T0.4 (`docs/spikes/fulltext.md`)
— not usable as-is.

Decision: **Phase 2 T2.1 must implement the lexical channel's ACL filter
as doc-id-set + `contains?`, not the literal §9.3 join-through-group-list
query**:

```clojure
(defn accessible-doc-ids [index-db user-groups]
  (set (d/q '[:find [?d ...]
              :in $ [?g ...]
              :where [?d :doc/effective-groups ?g]]
            index-db user-groups)))

(d/q '[:find ?cid ?score
       :in $ ?q ?doc-set
       :where
       [(fulltext $ :chunk/index-text ?q {:top 200 :display :refs+scores})
        [[?e _ _ ?score]]]
       [?e :chunk/doc ?d]
       [(contains? ?doc-set ?d)]
       [?e :chunk/id ?cid]]
     index-db query-text (accessible-doc-ids index-db user-groups))
```

This alternative — `accessible-doc-ids`, `acl-query-doc-set`,
`correctness-check`, `bench-case-doc-set` — is committed in
`dev/spikes/gen_synthetic_corpus.clj` and run automatically by `-main`,
so the claims below are reproducible from the repo. Verified equal
result sets to the literal §9.3 query for the same query/groups on every
case checked (2 runs × {1, 3, 50} groups, all `equal? true`, e.g.
200/200 matching tuples at 50 groups both runs). Measured 47-59x faster
p50 at 50 groups (1.69-2.11 ms including the `accessible-doc-ids`
precompute done fresh inside every timed run, vs. 99.55-102.77 ms for the
join). `accessible-doc-ids` is a good candidate to cache per
session/request (group membership changes far less often than queries
are issued, and the benchmark above already recomputes it from scratch
every run); T2.1 should decide whether to cache it or recompute it per
query. The admin bypass path (no ACL clause, per §9.3's "must be an
independent function" requirement) is unaffected.

See `docs/spikes/acl-query-perf.md` for full numbers and reproduction
instructions.

## 2026-09-22 — `:db.vec/domains` write-path bug in Datalevin 1.1.0 (confirmed, root-caused)

Spec text: n/a — this corrects a wrong finding in this project's own earlier
spike docs, not a SPEC.md override. `docs/spikes/embedding.md` (T0.3)
originally claimed `d/transact!` with a `:db.type/vec` attribute + explicit
`:db.vec/domains` succeeds; `docs/datalevin_debug_notes.md` §4 separately
logged the same configuration as an "unresolved bug" with three unconfirmed
candidate causes, one being a REPL `:reload`/classloader artifact. The two
docs contradicted each other and neither was verified in a genuinely fresh
JVM process.

Actual (verified 2026-09-22, whole-branch-review fix wave): reran the
transact in a fresh, non-`:reload`d `clojure -M:jvm-opts -e '...'` process
(disposable temp dir, single process invocation — the exact discipline
`docs/datalevin_debug_notes.md` itself flagged as worth retesting with).
**The crash reproduces identically in a fresh process — it is a real
Datalevin 1.1.0 bug, not a classloader artifact.** Root-caused by reading
`datalevin/storage.clj` in the pinned 1.1.0 jar:

- `init-vector-domains` (~storage.clj:3534) initializes **only** the domains
  listed in a `:db.vec/domains` schema attribute prop, when that key is
  present — it does not also initialize the attribute's own auto-derived
  domain name (`datalevin.vector/attr-domain`: `keyword->string` with `/` →
  `_`, e.g. `:chunk/vec` → `"chunk_vec"`) in that case.
- The write path (`prepare-datoms-kv-plan`'s add/delete helpers,
  ~storage.clj:2946 and ~3053) unconditionally computes the transact target
  as `(conjv (props :db.vec/domains) (v/attr-domain attr))` — it *always*
  also targets the attribute's auto-derived domain, regardless of whether
  `:db.vec/domains` is set.
- When `:db.vec/domains` is set, this mismatch means the write path targets
  a domain (the auto-derived one) that was never registered in the
  `vector-indices` map → `nil` lookup → `IllegalArgumentException: No
  implementation of method: :add-vec ... found for class: nil`.

**Confirmed working configuration** (also verified end-to-end in the same
fresh-JVM session): omit `:db.vec/domains` from the schema attribute
entirely, e.g. `:chunk/vec {:db/valueType :db.type/vec}`. Both paths then
agree on the domain (the attribute's auto-derived name), and `transact!` +
`vec-neighbors` queries both work correctly. To override per-domain options,
key the top-level `:vector-domains` map by that same auto-derived name
(e.g. `{:vector-domains {"chunk_vec" {:dimensions 1024 :metric-type
:cosine}}}`) rather than setting `:db.vec/domains` on the schema.

Also corrected in the same investigation: `vec-neighbors`'s Datalog syntax.
The attribute-keyword form is `[(vec-neighbors $ :chunk/vec ?q-vec {:top n})
[[?e ?a ?v]]]` (returns `[e a v]` triples, or `[e a v dist]` with `:display
:refs+dists`) — confirmed both from `datalevin.core/vec-neighbors`'s
docstring and by running an actual query. A `?qvec ?dims` two-positional-
argument form previously cited in `docs/datalevin_debug_notes.md` and
`docs/spikes/fulltext.md` does not exist — `vec-neighbors` has no
"dimensions" positional argument.

Decision: **Phase 1 T1.1 (index-schema) and T1.4 (index writer) must define
`:chunk/vec` without `:db.vec/domains`** — `{:db/valueType :db.type/vec}`
only, relying on the auto-derived `"chunk_vec"` domain name, with
`:vector-opts {:dimensions <dims> :metric-type :cosine}` (or a
`:vector-domains {"chunk_vec" {...}}` override) supplied at `create-conn`
time. This is not a workaround to revisit later — it is the correct,
verified way to use `:db.type/vec` under Datalevin 1.1.0 until/unless a
future Datalevin release fixes the domain-list mismatch described above.

See `docs/spikes/embedding.md` ("Known bug" / "Verified end-to-end"
sections), `docs/datalevin_debug_notes.md` §1/§4, and
`docs/spikes/fulltext.md` for full detail and reproduction code.

## 2026-09-22 — T0.4 fulltext spike: three SPEC.md §6.1/§9.3 overrides

Spec text (`SPEC.md` §6.1 store-options draft, `⚠️ VERIFY` at T0.3/T0.4;
§9.3's own `⚠️ VERIFY (T0.4)` marker; §17 T0.4/T0.5 AC text): draft store
options show `:search-domains {"chunk_index-text" {:index-position? true
...}}` (phrase + proximity search enabled via `:index-position? true`);
§9.3 proposes `:doc-filter` pre-filtering as a fallback "if it applies
before top-k"; §9.3's own query pattern is written assuming these work as
documented. T0.4's spike (`docs/spikes/fulltext.md`) found three of these
literal spec assumptions don't hold as written, but this was never logged
here as its own override (an earlier exit-check judgment wrongly concluded
SPEC.md never mentions phrase/index-position/doc-filter at all — it does,
in the three places cited above).

Actual (`docs/spikes/fulltext.md` §4, §5, §6, "Known Issues" 1–2):

1. **Phrase search / `:index-position? true` does not work through the
   Datalog `fulltext` integration**, even though it works perfectly against
   a standalone `d/new-search-engine`. Configuring `{:search-domains
   {"chunk/index-text" {:index-position? true}}}` at connection time and
   confirming (by inspection) that the domain does carry `index-position?:
   true` still produces `Phrase search requires :index-position? true` when
   the same phrase query is run through Datalog. Root cause unconfirmed
   (possibly the query engine reading from a different domain/engine
   instance, or a real 1.1.0 bug); not resolved by this spike.

2. **`:doc-filter` is unusable from Datalog.** Standalone
   `(d/search engine "quick" {:doc-filter (fn [doc-ref] ...)})` works, but
   passing the same inline predicate function through the `fulltext`
   Datalog function's options map fails with a cast error. This directly
   kills SPEC §9.3's own suggested fallback ("`:doc-filter` 預先過濾") —
   already noted from the *consuming* side in the T0.5 ACL-query-perf
   decision entry above, but never logged here as T0.4's own finding.

3. **autoDomain's domain name is `keyword->string` of the attribute,
   slashes kept** — e.g. `:chunk/index-text` → domain name `"chunk/index-text"`
   (confirmed: `(u/keyword->string :chunk/text)` → `"chunk/text"`) — not the
   underscore form `"chunk_index-text"` SPEC §6.1's draft store-options
   example guessed. (Note this differs from the *vector*-domain
   auto-naming convention documented separately in this file's
   `:db.vec/domains` write-path bug entry, which does replace `/` with `_`
   — the two features use different naming helpers.)

Decision:
- **Phase 2 T2.1's lexical channel must not depend on phrase queries** (no
  `{:phrase "..."}` terms in its `fulltext` query construction) until/unless
  a future spike confirms `:index-position? true` actually works through
  Datalog — plain BM25 + boolean (`:and`/`:or`) queries are confirmed
  working and are what T2.1 should use.
- **`:doc-filter` must not be used as the ACL pre-filter mechanism.** This
  reinforces (does not duplicate) the T0.5 decision above: the doc-id-set +
  `contains?` pattern is the required alternative, precisely because
  `:doc-filter` — SPEC §9.3's own suggested fallback — doesn't work from
  Datalog.
- Any code (Phase 1 T1.1 schema, Phase 2 T2.1 queries) that needs to name a
  fulltext search domain by string must use the attribute's
  `keyword->string` form (`"chunk/index-text"`), not an underscored guess.

See `docs/spikes/fulltext.md` for full findings and reproduction.

## 2026-09-22 — `bb vllm:check` not run against real vLLM through Phase 0

Spec text (SPEC.md T0.2 AC): the three vLLM endpoints (embed/rerank/chat)
must "皆回傳合理結果" against a real vLLM instance.

Actual: no `VLLM_EMBED_BASE_URL`/`VLLM_RERANK_BASE_URL`/`VLLM_CHAT_BASE_URL`
(or matching `_MODEL`/`_API_KEY`) were ever set in this development
environment, and no local vLLM instance was reachable at any point during
Phase 0 (T0.1 through T0.5). `bb vllm:check` was never run against a real
endpoint; the client code's correctness is instead covered by the
stub-server tests in `test/hybridrag/llm/http_test.clj` (T0.2), which
verify request/response handling and the "API key never appears in
errors/logs" requirement (SPEC.md §4.3) without a real vLLM present.

Decision: defer the real-endpoint check rather than block Phase 0 on
infrastructure that doesn't exist yet. Phase 1 depends on
`hybridrag.llm.embed/embed-batch!` actually working end-to-end (ingestion
needs real embeddings), so `bb vllm:check` must be run — and pass all
three `[OK]` lines — against a real vLLM instance before Phase 1's
ingestion work (T1.4 index writer) can be considered embedding-tested, not
just unit-tested against a stub. This is a precondition on whoever starts
Phase 1, not a Phase 0 blocker in itself.


## 2026-09-23 — vLLM infrastructure now available

Spec text (SPEC.md T0.2 AC): the three vLLM endpoints (embed/rerank/chat)
must "皆回傳合理結果" against a real vLLM instance.

Previous (2026-09-22): no `VLLM_EMBED_BASE_URL`/`VLLM_RERANK_BASE_URL`/`VLLM_CHAT_BASE_URL`
(or matching `_MODEL`/`_API_KEY`) were ever set in this development
environment, and no local vLLM instance was reachable at any point during
Phase 0 (T0.1 through T0.5). `bb vllm:check` was never run against a real
endpoint; the client code's correctness is instead covered by the
stub-server tests in `test/hybridrag/llm/http_test.clj` (T0.2), which
verify request/response handling and the "API key never appears in
errors/logs" requirement (SPEC.md §4.3) without a real vLLM present.

Actual (2026-09-23): `VLLM_*` environment variables now configured via `.env`
and `VLLM_SETUP.md` documentation. The three vLLM endpoints are
accessible (same host or same subnet as spec §4.1).

Decision: Phase 0's deferral is now complete. Before starting Phase 1
(T1.4 index writer), run `bb vllm:check` against the real vLLM instance.
All three `[OK]` lines must pass before ingestion work can be considered
embedding-tested end-to-end, not just unit-tested against a stub.

See `VLLM_SETUP.md` for configuration instructions.

This is not a spec deviation - SPEC §4 was always complete. This entry
records that the infrastructure mentioned in §4.1 is now available.


## 2026-09-24 — Principal is decoupled from identity source (prep for Keycloak SSO)

Spec text (SPEC.md §13, T2.0): session cookie for the Web UI, bearer
tokens for the API, users and groups managed via `bb user:*`. SSO is a
non-goal (§1.3).

Context: production use will need SSO, and Keycloak is the chosen IdP
(see `docs/backlog.md`, "SSO via Keycloak (OIDC)"). SSO itself stays out
of the MVP, but T2.0 is where identity handling gets wired in. If T2.0
couples "bearer token → user lookup" directly into the handlers and
Retriever callers, adding OIDC later means reworking them.

Decision: T2.0 treats authentication entry points (password session, API
token, later OIDC or trusted proxy headers) as adapters whose only output
is a principal map `{:username .. :groups #{..} :admin? ..}`. Everything
downstream (pipeline, Retriever, ACL, trace) consumes only the principal
and never inspects how it was obtained. No OIDC code is written in the
MVP; this only fixes the shape of the seam.

This is compatible with the T0.5 ACL decision (accessible-doc-id set
computed from groups): that computation depends on `:groups`, not on
where the groups came from.

This is not a spec deviation — §13's mechanisms are implemented as
written. It constrains how T2.0 structures them.


## 2026-09-24 — T1.3 chunker: where SPEC §7.3/§7.7 was ambiguous

Spec text (SPEC.md §7.3 steps 1–6, §7.4, §7.7). Choices made where the
text leaves room, all in `hybridrag.ingest.chunker` / `.tokens`:

- **Line breaks are cut points in prose.** Step 3 splits an oversized
  block at `。！？；` / `. ! ?`+whitespace, then hard-cuts. A list or
  paragraph with no sentence punctuation would be hard-cut mid-item, so a
  line break is also a cut point before falling back to a hard cut. Code
  blocks and tables still cut at line breaks only.
- **Overlap uses the same cut points, and fits inside max.** The next
  chunk starts at the earliest cut point in the previous chunk whose
  suffix fits in `min(overlap-tokens, max-tokens − first-unit tokens)`.
  Result: every chunk, overlap included, is a contiguous span of the file
  (`char-start`/`char-end` restore it exactly) and no chunk exceeds max.
  Hard-cut pieces have no inner cut points, so they carry no overlap.
- **Step 5's min-tokens test counts only the tail's own content**, not
  the overlap it inherited. Otherwise the overlap (up to 60 tokens) alone
  keeps a tiny tail from ever being merged.
- **Token estimate counts non-ASCII letters/digits as run characters**
  (§7.7 names only ASCII). Full-width `ＨＲ０７` or accented Latin would
  otherwise count 0, which is not conservative.
- **Heading-only sections produce no chunks**; their heading still shows
  up in child sections' trails.
- **Contextual header omits the `章節：` line for level-0 content**
  (no heading trail).
- `.txt` files are parsed by `markdown/parse-text` into the same section
  shape (one level-0 section, blank-line-separated paragraphs as blocks),
  so the chunker has one input format.


## 2026-09-24 — T1.4 index writer: schema, in-place re-index, link pass

Spec text (SPEC.md §6.1 index-schema, §7.5 links, §7.6 incremental flow).

- **index-schema is defined in T1.4, not T1.0.** The Phase 1 plan's T1.0
  (vec-domain verification + schema) was never committed; `schema.clj`
  still said "index-conn opens with schema {}". T1.4 writes it: §6.1 with
  the Path B change (`:chunk/index-text` fulltext only, vector in
  `:chunk/vec {:db/valueType :db.type/vec}` with no `:db.vec/domains`,
  dims/metric via `:vector-opts`), plus two additions: `:chunk/hard-cut?`
  (T1.3's marker) and `:doc/raw-links` (below). Every Datalevin call used
  was checked at the REPL against 1.1.0 first.
- **Re-indexing a doc updates entities in place.** §7.6 says "in ONE
  transaction: retract old sections/chunks of this doc, upsert doc, add
  new ones". Doing it literally — `retractEntity` + re-adding the same
  unique `:chunk/id` in one tx — fails in Datalevin 1.1.0 with fulltext
  "Document does not exist." (the tx is rolled back, nothing is
  corrupted). Instead, still in one transaction: surviving ids are
  upserted with explicit retractions of attributes/values the new version
  drops, and ids that no longer exist are `retractEntity`'d. Verified that
  both the fulltext and vector indexes then reflect only the new text.
- **Links are re-resolved for all docs each run**, not only "docs touched
  in this run and docs linking to deleted docs". Raw link targets are
  stored per doc (`:doc/raw-links`), so the pass is DB-only and cheap, and
  it also fixes a case the spec's rule misses: an unchanged doc whose link
  target is added in a later run.
- **Embedding is per doc** (batches of 32 within a doc), not one batch
  across all files. Keeps per-file error isolation: a failed embed call
  leaves that doc unwritten and the rest of the run continues.
- **No CJK analyzer yet.** `:chunk/index-text` uses Datalevin's default
  analyzer (§8 says it is nearly useless for Chinese). §17 puts no
  analyzer task in Phase 1; the lexical channel (T2.1) needs it. Since the
  analyzer applies at write time, adding it requires `bb reindex`.
- **Index lag** is `:unfinished-count` from `d/wait-for-secondary-index`.
  Fulltext indexing is not configured `:async`, so it is 0 in practice.
- `bb ingest` / `bb reindex` exit 1 if any file failed.
- **Not yet run against real vLLM**: no `VLLM_*` config in this
  environment. End-to-end ingest/rerun/reindex was verified through the
  real HTTP client against a local stub `/v1/embeddings` returning
  1024-dim vectors.


## 2026-09-24 — T1.5 verified with LM Studio bge-m3 instead of vLLM

Spec text (SPEC.md §17 T1.5 AC): the sample corpus ingests completely,
the report shows no errors, index lag ends at 0 — with real embeddings
(the 2026-09-22/23 entries above require a real embedding endpoint
before ingestion counts as embedding-tested).

Actual: no vLLM is reachable in this environment. LM Studio serves the
same model family locally: `ggml-org/bge-m3-Q8_0-GGUF` (a Q8_0
quantization of BAAI/bge-m3, 1024 dims) behind an OpenAI-compatible
`/v1/embeddings`. `bb ingest` of `corpus-sample/` against it: 22 docs
added, 0 errors, 0 unresolved links, 119 chunks, longest 378 estimated
tokens, index lag 0, ~8 s; a rerun skips all 22; `bb reindex` rebuilds
the same result; exit code 0. Real-vector `vec-neighbors` queries put
the right doc first for the four spot-checked questions.

Found on the way: the JDK HttpClient's default h2c upgrade attempt hangs
against LM Studio (fixed in `hybridrag.llm.http`, forcing HTTP/1.1), and
LM Studio answers unknown paths such as `/v1/rerank` with HTTP 200 +
`{"error": ...}` — a live example of SPEC §4.2's "200 with error
payload" case for T2.3.

Decision: treat the T1.5 AC as met, with the caveat that the embedding
model is a Q8_0 quantization served by LM Studio, not full-precision
bge-m3 on vLLM. Retrieval quality numbers (T2.6) measured on this setup
may differ slightly from a vLLM deployment. `bb vllm:check` against real
vLLM remains open. LM Studio has no rerank endpoint, so rerank stays
unverified against a real model.


## 2026-09-24 — Local rerank via llama.cpp; scores are raw logits

Spec text (SPEC.md §4.1/§4.2): rerank is `BAAI/bge-reranker-v2-m3` on
vLLM, response `results[i] = {index, relevance_score}`;
`:retrieve/rerank-min-score` stays nil until eval calibrates it (§5).

Actual (no vLLM here): llama.cpp `llama-server --reranking` with
`gpustack/bge-reranker-v2-m3-GGUF:Q8_0` on port 8002. The existing
`hybridrag.llm.rerank-client/rerank!` works against it unchanged:
same response shape, best-first. Measured on an M1 16 GB: 40 real
sample-corpus chunks (~6–7k estimated tokens) in ~2.1 s, 20 in ~0.9–1.0 s;
~1.1 GB RSS. The same client pointed at LM Studio (HTTP 200 +
`{"error": ...}`) correctly throws "missing :results".

Two consequences for Phase 2:
- **`relevance_score` is a raw logit here** (e.g. 4.6 / −6.5 / −11.0),
  whereas vLLM's cross-encoder scoring normally returns sigmoid-scaled
  0–1 values. Any `rerank-min-score` threshold is backend-specific; T2.6
  must calibrate it on the backend actually deployed, or T2.3 should
  normalize scores (e.g. apply a sigmoid when values fall outside
  [0, 1]). Not decided yet — noted for T2.3.
- Local latency (~2 s for 40 docs) is fine for development and eval but
  says nothing about the §1.2 p50 < 800 ms target, which assumes a GPU
  vLLM deployment.


## 2026-09-24 — T2.0 auth: token prefix, CLI behavior

Spec text (SPEC.md §6.2 app-schema, §13): tokens are 32 random bytes shown
once, only the sha256 stored; `bb token:revoke <prefix>`.

- **`:token/prefix` added to app-schema** (first 8 chars of the base64url
  token). §6.2 stores only `:token/hash`, which leaves nothing a human can
  pass to `token:revoke`. 8 of 43 characters (48 of 256 bits) is kept;
  the remaining 208 bits keep the token unguessable from the prefix.
  Revoking an ambiguous prefix is refused.
- `user:create` sets no password (§13 lists `user:passwd` separately); a
  user without a password can still get API tokens. `user:passwd` prompts
  twice via the console (stdin when there is none) and requires ≥ 8 chars.
- Unauthenticated API calls get **401** `{"error": {"code":
  "unauthorized"}}`; the principal lookup re-reads the user's current
  groups on every request, so `user:groups` takes effect immediately for
  existing tokens.


## 2026-09-24 — T2.1: bigram analyzer, Retriever shape, lexical ranking note

Spec text (SPEC.md §8, §9.2, §9.3).

- **Analyzer = the §8.1 algorithm, no HanLP/Jieba.** §8 names HanLP 1.x as
  preferred, but §8.1–§8.3 specify an overlapping-bigram analyzer and its
  test vectors are bigrams; a word segmenter would fail them. Implemented
  §8.1 exactly (`hybridrag.search.analyzer`), registered as a Datalevin
  UDF in `index-conn/open`. HanLP/Jieba → backlog. Datalevin 1.1.0 uses
  the index analyzer for queries when `:query-analyzer` is omitted
  (`search.clj:1674`), which is what §8.2 asks for. Opening index.dtlv
  without the UDF registry is refused by Datalevin, so the analyzer
  cannot silently fall back to the default. **Existing index.dtlv files
  must be rebuilt: `bb reindex`.**
- **`channel` returns a map, not a bare list**:
  `{:candidates :extended :raw-hits :after-acl :starved?}`. §9.5 step 3
  needs the full ACL-filtered over-fetch list and §14 needs the counts;
  returning only candidates would force a second query.
- **Protocol gains `chunks`** (ACL-filtered fetch by id), so rerank input
  and context text come from the Retriever and the pipeline never reads
  the DB directly.
- ACL filtering is the T0.5 doc-id set checked against every raw hit, in
  `channel-for-user`; `channel-for-admin` is a separate function with no
  ACL step (§9.3). Non-admins without groups get empty results without a
  DB call (tested with a retriever whose conn is not a connection).
  `linked-docs` also filters the *source* docs, so a user cannot learn
  the links of a doc they cannot read.
- **Lexical ranking observation (for T2.6):** exact version strings rank
  below the top result on the sample corpus — `v2.7.3` puts its chunk at
  rank 7. The §8.1 fragment `v2` matches many short `api-v2.md` chunks,
  and BM25 length normalization penalizes the long, term-dense
  release-notes chunk. Not a correctness issue; recall@10 still finds it.
  Possible fix to evaluate in T2.6, not implemented: emit only whole ASCII
  tokens on the query side (deviates from §8.2).


## 2026-09-24 — T2.3: rerank scores stay raw; stricter response check

Spec text (SPEC.md §9.6): failures (timeout, non-2xx, 200 with a bad
structure) keep RRF order with graph candidates last, flag
`:rerank-failed`; `rerank-min-score` is off by default.

- **Scores are used as returned, not normalized.** The earlier entry noted
  llama.cpp returns logits while vLLM usually returns sigmoid values.
  Ordering is unaffected either way, and `rerank-min-score` stays nil, so
  normalizing now would be a guess. Revisit only when T2.6 calibrates a
  threshold on the deployed backend.
- "Structure error" is checked beyond a missing `results` key: every
  result needs an in-range, non-repeated integer `index` and a numeric
  score, otherwise the stage degrades.
- The rerank client's read timeout is now taken from its config map
  (default still 10 s, §4.3), so tests can exercise the timeout path.
