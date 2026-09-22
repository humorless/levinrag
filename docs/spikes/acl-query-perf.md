# Spike: ACL-filtered query latency at scale (T0.5)

Datalevin version: 1.1.0. Date: 2026-09-22.

## Goal

Measure p50 latency of the ACL-filtered lexical query pattern from
`SPEC.md` §9.3 (over-fetch top-200 fulltext hits, then Datalog-join
through `:chunk/doc` → `:doc/effective-groups` → the user's group list) at
the plan's stated scale: 10,000 docs / 100,000 chunks (10 chunks/doc) / 50
groups.

## Setup

- Synthetic-data generator: `dev/spikes/gen_synthetic_corpus.clj`
  (throwaway script, runnable, not covered by CI lint/tests — see its
  docstring for exact invocation). Seeded RNG (seed 42) for
  reproducibility.
- Schema (mirrors `docs/spikes/embedding.md`'s Path B decision — no
  `:db/embedding` / `:db.type/vec` attribute in this spike at all, so the
  known `:db.type/vec` transact! bug in
  `docs/datalevin_debug_notes.md` §4 does not apply here):

  ```clojure
  {:doc/path              {:db/valueType :db.type/string
                            :db/unique    :db.unique/identity}
   :doc/effective-groups  {:db/valueType   :db.type/string
                            :db/cardinality :db.cardinality/many}
   :chunk/id              {:db/valueType :db.type/string
                            :db/unique    :db.unique/identity}
   :chunk/doc             {:db/valueType :db.type/ref}
   :chunk/index-text      {:db/valueType           :db.type/string
                            :db/fulltext            true
                            :db.fulltext/autoDomain true}}
  ```

- Corpus: 10,000 docs × 10 chunks = 100,000 chunks. Each doc gets 1-3 of
  50 synthetic groups (`group-0` .. `group-49`), sampled uniformly at
  random. Chunk text: 12 space-joined draws from a 20-term Traditional
  Chinese contract vocabulary (合約/條款/甲方/違約/賠償/... — see the
  script). **Important correction made mid-spike**: the first draft
  concatenated terms with no separator; since Datalevin's default
  analyzer only splits on whitespace/punctuation (confirmed in
  `docs/spikes/fulltext.md` §7 — CJK fulltext needs a custom analyzer UDF
  for real prose, deferred to Phase 1), an unseparated blob indexes as
  one opaque token per chunk and single-term queries never hit anything.
  Space-joining the same CJK vocabulary sidesteps that (each term is
  indexed individually) while still using real CJK text.
- Query text per run: a single random vocabulary term. Sanity-checked: a
  common term (e.g. `合約`) hits well over 200 of the 100,000 chunks, so
  every run genuinely exercises the `:top 200` over-fetch cap rather than
  measuring an all-miss path.
- Query pattern used verbatim from the brief/`SPEC.md` §9.3 — **no
  destructuring adjustment needed**. Checked against Datalevin 1.1.0
  source (`datalevin.built-ins/fulltext-request`,
  `datalevin.query.access.fulltext`): when `fulltext` is called with an
  explicit attribute arg (`:chunk/index-text`), it returns 4-tuples
  `[e a v score]`, so `[[?e _ _ ?score]]]` in the brief's query is
  already correct:

  ```clojure
  (d/q '[:find ?cid ?score
         :in $ ?q [?g ...]
         :where
         [(fulltext $ :chunk/index-text ?q {:top 200 :display :refs+scores})
          [[?e _ _ ?score]]]
         [?e :chunk/doc ?d]
         [?d :doc/effective-groups ?g]
         [?e :chunk/id ?cid]]
       index-db query-text user-groups)
  ```

- Timing: no `criterium` on the classpath (confirmed absent from
  `deps.edn`). Manual loop, 5 warm-up runs discarded then 50 (or 30 for
  the finer-grained scaling curve) timed runs per case, `System/nanoTime`
  around the `d/q` call only. Query text and, where the group count is
  below 50, which groups are sampled both vary every run (per the plan's
  own guidance, to avoid JIT-warmup skew from hammering one exact query).
  Cases: user belongs to 1 group / 3 groups / all 50 groups.

## Machine / JVM

- Apple M1 Pro, 8 cores, 16 GB RAM, macOS 26.5.1 (Tahoe) aarch64.
- Temurin OpenJDK 21.0.2 (`java -version`).
- Clojure 1.12.1, Datalevin 1.1.0.
- JVM run with the project's required `:jvm-opts` alias
  (`--add-opens=java.base/java.nio=ALL-UNNAMED`,
  `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED`).

## Results — headline (SPEC §9.3 query, as-written)

Two independent full runs of `dev/spikes/gen_synthetic_corpus.clj`'s
default parameters (fresh 10k/100k/50-group corpus regenerated each
time, 50 timed runs/case):

| case          | run 1 p50 | run 1 p90 | run 2 p50 | run 2 p90 |
|---------------|----------:|----------:|----------:|----------:|
| 1 group       |   6.43 ms |  21.77 ms |   6.90 ms |  22.98 ms |
| 3 groups      |  27.09 ms |  31.82 ms |  26.44 ms |  30.08 ms |
| all 50 groups | 102.77 ms | 129.07 ms | 101.32 ms | 127.16 ms |

**AC (`p50 < 100 ms`): met for 1 and 3 groups, FAILS for the all-50-groups
case** — p50 landed at ~101-103 ms in both corrected-corpus runs, and at
168.50 ms in an earlier pre-fix run against a corpus whose queries
matched nothing (see root cause below — the failure is not specific to
real fulltext hits).

## Scaling curve and root cause

A finer-grained sweep (single JVM process, 30 runs/case after 5 warm-up,
same corrected corpus) shows cost does not stay near the 1-group baseline
as group count grows, though the exact shape is noisy (GC/JIT jitter on
a single dev machine — not a claimed precise functional form):

| n user-groups | accessible docs | p50 (join-based) |
|---:|---:|---:|
| 1  |   ~400 | 9.93 ms |
| 3  |  ~1100 | 28.47 ms |
| 5  |  ~1800 | 34.91 ms |
| 10 |  ~3500 | 62.78 ms |
| 20 |  ~6100 | **107.26 ms** |
| 30 |  ~7900 | 39.48 ms |
| 40 |  ~9200 | 45.79 ms |
| 50 | 10,000 | 56.07 ms |

**Root-cause isolation experiment**: run against the earlier
tokenizer-broken corpus (fulltext step matches exactly zero chunks — see
Setup), the join-based query showed the *same* cost-scales-with-group-count
pattern (5.76 ms @ 1 group → 88.10 ms @ 50 groups) even though the
fulltext step contributed zero candidates. This means the cost is **not**
coming from the fulltext top-k step at all — it means Datalevin 1.1.0's
query planner does not scope evaluation of
`[?d :doc/effective-groups ?g]` + `:in [?g ...]` to the (≤200) fulltext
candidates. Cost instead scales with the number of doc/group membership
edges matching the input group list, i.e. roughly the size of the
*accessible corpus*, not the size of the over-fetch window. This defeats
the over-fetch-then-join design intent of `SPEC.md` §9.3: a user with
broad group membership (common for e.g. an "all-staff" group) pays a cost
proportional to their entire accessible corpus on every lexical query,
regardless of how selective the search term is.

## Alternative (verified working, no `:doc-filter` dependency)

`SPEC.md` §9.3 itself proposes `:doc-filter` pre-filtering as the
fallback if the join is too slow, but `docs/spikes/fulltext.md` already
found Datalevin 1.1.0's `:doc-filter` broken in Datalog integration (cast
error on the inline predicate fn) — not currently usable. Instead:
precompute the user's **accessible doc-id set** with a small, cheap
Datalog query (no fulltext step at all), then filter the fulltext
candidates against that set with a plain `contains?` predicate instead of
joining through the group list:

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

**Correctness verified**: for the same query text and group set, this
returns an identical result set to the SPEC §9.3 join-based query
(checked directly, `equal? true`, 20/20 matching tuples for a
representative 3-group case with a real hit-producing term).

**Timings** (corrected corpus, doc-set precomputed once per case outside
the timed loop — i.e. this models a per-query recompute, not even a
cached-per-session one; 30 runs/case after 5 warm-up):

| n user-groups | accessible docs | p50 | p90 |
|---:|---:|---:|---:|
| 1  |    402 | 0.07 ms | 3.23 ms |
| 3  |  1,134 | 0.07 ms | 4.48 ms |
| 5  |  1,840 | 0.10 ms | 7.56 ms |
| 10 |  3,525 | 0.10 ms | 9.83 ms |
| 20 |  6,141 | 0.10 ms | 13.87 ms |
| 30 |  7,923 | 0.10 ms | 14.71 ms |
| 40 |  9,215 | 0.10 ms | 17.46 ms |
| 50 | 10,000 (entire corpus) | 0.10 ms | 18.76 ms |

Plus a one-time `accessible-doc-ids` precompute cost, measured separately
at the worst case (all 50 groups, entire corpus reachable): **p50 =
0.86 ms**. Even summing worst-case precompute + query (~1 ms p50), the
doc-set approach is roughly **100x faster** than the join-based approach
at 50 groups, and — unlike the join — stays flat as group count grows.

## Decision

**The literal SPEC §9.3 query (over-fetch 200 + Datalog join through
`[?g ...]`) does not reliably meet the plan's own `p50 < 100 ms` AC** at
10k docs / 100k chunks / 50 groups: it failed in every one of the three
full runs performed (168.50 ms, 102.77 ms, 101.32 ms), and a finer sweep
shows the failure region starts well below 50 groups (crossed 100 ms
already at 20 groups in one run). Per `SPEC.md` T0.5's own AC fallback, a
`docs/decisions.md` entry has been added proposing the doc-id-set +
`contains?` alternative above as the query shape Phase 2's T2.1 must
actually implement.

**Consequence for Phase 2 T2.1**: implement the lexical channel using the
doc-id-set + `contains?` pattern, not the literal §9.3 join-through-group-list
query. The `[?d :doc/effective-groups ?g]` / `:in [?g ...]` join is still
useful, but only as the (cheap, ~1 ms even at max fan-out) definition of
`accessible-doc-ids` — not inlined into the same query as the fulltext
scan. `accessible-doc-ids` is a natural candidate to cache per
session/request since group membership changes far less often than
queries are issued, which would make the ACL filter cost effectively
free beyond the fulltext scan itself; T2.1 should decide whether to cache
it or recompute it per query (recomputing per query, as measured above,
is already fast enough on its own).

The admin bypass path (`SPEC.md` §9.3: "admin 走不含 ACL clause 的查詢，
必須是獨立函式") is unaffected by this finding — it has no group filter
at all, so this spike's numbers don't apply to it either way.

## Reproducing

```bash
clojure -M:jvm-opts -e '(load-file "dev/spikes/gen_synthetic_corpus.clj")(spikes.gen-synthetic-corpus/-main)'
```

See the script's docstring for why it's invoked via `load-file` rather
than `-m`/`:dev` extra-paths (auto-loading `dev/user.clj`'s full REPL dep
set would otherwise be required just to run a throwaway spike script).
