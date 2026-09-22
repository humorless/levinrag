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
the literal query at that scale, worst case (user in all 50 groups) —
101-103 ms across two corrected-corpus runs, 168.50 ms in an earlier run.
**Fails the plan's own AC.** Root-cause isolation (rerunning against a
corpus where fulltext matched zero chunks) showed the same
cost-scales-with-group-count pattern even with zero fulltext candidates,
meaning Datalevin 1.1.0's query planner does not scope the ACL join to
the (≤200) over-fetch window — cost instead scales with the size of the
user's accessible corpus. `:doc-filter`, the spec's own suggested
fallback, was already found broken in Datalog integration by T0.4
(`docs/spikes/fulltext.md`) — not usable as-is.

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

Verified equal result sets to the literal §9.3 query for the same
query/groups. Measured ~100x faster at 50 groups (p50 ≈ 0.1-1 ms
including precompute, vs. ≈101 ms for the join), and flat as group count
grows, unlike the join. `accessible-doc-ids` is a good candidate to cache
per session/request (group membership changes far less often than
queries are issued); T2.1 should decide whether to cache it or recompute
it per query. The admin bypass path (no ACL clause, per §9.3's
"must be an independent function" requirement) is unaffected.

See `docs/spikes/acl-query-perf.md` for full numbers, the scaling curve,
and the root-cause experiment.
