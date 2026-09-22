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

All numbers below are from `-main`'s own printed output — every table in
this section is reproducible by running the command in "Reproducing"
below; none of it comes from an unlogged/one-off REPL session. Four
independent full runs of `dev/spikes/gen_synthetic_corpus.clj`'s default
parameters (fresh 10k/100k/50-group corpus regenerated each time, 50
timed runs/case):

| case          | run 1 p50 | run 1 p90 | run 2 p50 | run 2 p90 | run 3 p50 | run 3 p90 | run 4 p50 | run 4 p90 |
|---------------|----------:|----------:|----------:|----------:|----------:|----------:|----------:|----------:|
| 1 group       |   6.43 ms |  21.77 ms |   6.90 ms |  22.98 ms |   6.49 ms |  24.35 ms |   6.11 ms |  22.38 ms |
| 3 groups      |  27.09 ms |  31.82 ms |  26.44 ms |  30.08 ms |  26.53 ms |  32.00 ms |  26.49 ms |  30.84 ms |
| all 50 groups | 102.77 ms | 129.07 ms | 101.32 ms | 127.16 ms | 100.29 ms | 133.77 ms |  99.55 ms | 126.91 ms |

(An earlier, fifth run against a since-fixed corpus — see the
CJK-tokenization correction in Setup — measured 168.50 ms p50 for the
all-50-groups case. Not included in the table above since that corpus
predates the fix, but noted because it's consistent with everything
below: cost at 50 groups is high regardless of whether fulltext genuinely
matches anything, see Root cause.)

**AC (`p50 < 100 ms`): comfortably met for 1 and 3 groups. For the
all-50-groups case, p50 hovers right at the 100 ms line — 99.55-102.77 ms
across 4 runs, straddling both sides of the threshold — while p90
consistently and clearly exceeds it every single run (126.91-133.77 ms).**
This is not a dramatic blowout, but it is not a reliable pass either: a
metric whose median sits on the threshold and swings across it
run-to-run, with its p90 always over budget, does not satisfy "p50 <
100 ms" as a dependable guarantee at this scale. Per `SPEC.md` T0.5's own
AC fallback, this counts as a case needing the alternative documented
below and in `docs/decisions.md`.

## Root cause (exploratory — not committed, treat as a hypothesis)

**This section reports an exploratory REPL investigation that is not
committed as code and is not independently reproducible from the repo.**
It is kept here only as the working hypothesis for *why* the all-50-groups
case is expensive, and is not what the AC verdict or the T2.1 mandate
below rest on — those rest entirely on the committed, reproducible
correctness check and timing comparison in the next section.

During the investigation, rerunning the join-based query against an
earlier corpus whose fulltext queries matched **zero** chunks (the
pre-fix, unseparated-CJK-blob corpus — see Setup) still showed cost
rising sharply with group count (single-digit ms at 1 group, tens-to-90ms
range at 50 groups), even though the fulltext step contributed zero
candidates in every one of those queries. That's suggestive that
Datalevin 1.1.0's query planner does not scope evaluation of
`[?d :doc/effective-groups ?g]` + `:in [?g ...]` to the (≤200) fulltext
candidates, and that cost instead tracks the number of doc/group
membership edges matching the input group list — i.e. roughly the size of
the *accessible corpus* — rather than the size of the over-fetch window.
If true, this would defeat the over-fetch-then-join design intent of
`SPEC.md` §9.3 for any user with broad group membership. Flagging this as
a hypothesis worth confirming with committed benchmark code in a future
pass, not as a verified fact.

## Alternative (verified working via committed code, no `:doc-filter` dependency)

`SPEC.md` §9.3 itself proposes `:doc-filter` pre-filtering as the
fallback if the join is too slow, but `docs/spikes/fulltext.md` already
found Datalevin 1.1.0's `:doc-filter` broken in Datalog integration (cast
error on the inline predicate fn) — not currently usable. Instead:
precompute the user's **accessible doc-id set** with a small, cheap
Datalog query (no fulltext step at all), then filter the fulltext
candidates against that set with a plain `contains?` predicate instead of
joining through the group list. This is now committed code in
`dev/spikes/gen_synthetic_corpus.clj` — `accessible-doc-ids`,
`acl-query-doc-set`, `correctness-check`, and `bench-case-doc-set` — run
automatically by `-main` right after the headline benchmark above, so the
claims below are reproducible from the repo, not a one-off REPL result:

```clojure
(defn accessible-doc-ids [index-db user-groups]
  (set (d/q '[:find [?d ...]
              :in $ [?g ...]
              :where [?d :doc/effective-groups ?g]]
            index-db user-groups)))

(defn acl-query-doc-set [index-db query-text accessible-doc-set]
  (d/q '[:find ?cid ?score
         :in $ ?q ?doc-set
         :where
         [(fulltext $ :chunk/index-text ?q {:top 200 :display :refs+scores})
          [[?e _ _ ?score]]]
         [?e :chunk/doc ?d]
         [(contains? ?doc-set ?d)]
         [?e :chunk/id ?cid]]
       index-db query-text accessible-doc-set))
```

**Correctness, as printed by `-main` (2 runs, one query/group-set per
case, chosen the same way the timing benchmark chooses them)**:

| case          | run 1: join vs. doc-set counts | run 1 equal? | run 2: join vs. doc-set counts | run 2 equal? |
|---------------|:-------------------------------:|:---:|:-------------------------------:|:---:|
| 1 group       | 7 vs. 7     | true | 7 vs. 7     | true |
| 3 groups      | 31 vs. 31   | true | 31 vs. 31   | true |
| all 50 groups | 200 vs. 200 | true | 200 vs. 200 | true |

**Timings, as printed by `-main` (`bench-case-doc-set` — same 50
runs/case, 5-warmup, varying-query/varying-groups methodology as the
headline benchmark, with `accessible-doc-ids` recomputed *inside* every
timed run, i.e. no session-level caching credit taken)**:

| case          | run 1 p50 | run 1 p90 | run 2 p50 | run 2 p90 |
|---------------|----------:|----------:|----------:|----------:|
| 1 group       | 3.63 ms | 4.50 ms | 3.50 ms | 4.61 ms |
| 3 groups      | 5.29 ms | 6.44 ms | 5.49 ms | 6.56 ms |
| all 50 groups | 2.11 ms | 18.21 ms | 1.69 ms | 15.21 ms |

At 50 groups — the case that fails the AC above — the doc-set alternative
is **47-59x faster on p50** than the literal join query (99.55-102.77 ms
→ 1.69-2.11 ms), with correctness verified identical on every run
checked, and this holds even though the benchmark recomputes
`accessible-doc-ids` from scratch on every single query (a real
deployment could cache it per session/request, making this only faster).

## Decision

**The literal SPEC §9.3 query (over-fetch 200 + Datalog join through
`[?g ...]`) does not reliably meet the plan's own `p50 < 100 ms` AC** at
10k docs / 100k chunks / 50 groups: across 4 corrected-corpus runs, p50
for the all-50-groups case straddled the 100 ms line (99.55-102.77 ms)
and p90 exceeded it every time (126.91-133.77 ms). Per `SPEC.md` T0.5's
own AC fallback, a `docs/decisions.md` entry has been added proposing the
doc-id-set + `contains?` alternative above — verified correct and
47-59x faster at 50 groups via committed, rerunnable code — as the query
shape Phase 2's T2.1 must actually implement.

**Consequence for Phase 2 T2.1**: implement the lexical channel using the
doc-id-set + `contains?` pattern, not the literal §9.3 join-through-group-list
query. The `[?d :doc/effective-groups ?g]` / `:in [?g ...]` join is still
useful, but only as the definition of `accessible-doc-ids` — not inlined
into the same query as the fulltext scan. `accessible-doc-ids` is a
natural candidate to cache per session/request since group membership
changes far less often than queries are issued, which would make the ACL
filter cost effectively free beyond the fulltext scan itself; T2.1 should
decide whether to cache it or recompute it per query (recomputing per
query, as measured above, is already fast enough on its own).

The admin bypass path (`SPEC.md` §9.3: "admin 走不含 ACL clause 的查詢，
必須是獨立函式") is unaffected by this finding — it has no group filter
at all, so this spike's numbers don't apply to it either way.

## Reproducing

```bash
clojure -M:jvm-opts -e '(load-file "dev/spikes/gen_synthetic_corpus.clj")(spikes.gen-synthetic-corpus/-main)'
```

`-main` prints, in order: the headline join-based benchmark ("== Results
=="), the correctness check comparing `acl-query` vs. `acl-query-doc-set`
for each case ("== Correctness check =="), and the doc-set alternative's
own timing benchmark ("== Alternative: accessible-doc-ids + contains?
=="). Every number cited in this doc came from one of those three
sections' output.

See the script's docstring for why it's invoked via `load-file` rather
than `-m`/`:dev` extra-paths (auto-loading `dev/user.clj`'s full REPL dep
set would otherwise be required just to run a throwaway spike script).
