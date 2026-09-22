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
