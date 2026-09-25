# `:doc-filter` re-test — Datalevin 1.1.0 (2026-09-25)

## Goal

T0.4 (`docs/spikes/fulltext.md` §5) recorded `:doc-filter` as unusable
from Datalog `fulltext` after one attempt failed with a cast error. This
spike re-tests it, finds the cause, and answers the question that matters
for ACL: is the filter applied before or after the top-k? The vector
`:vec-filter` is checked the same way.

Reproduce (fresh JVM, repo root):

```
clojure -M:jvm-opts -e '(load-file "dev/spikes/doc_filter.clj")(spikes.doc-filter/-main)'
```

## Findings

### 1. The T0.4 failure was the query form, not a Datalevin bug

T0.4 wrote the filter inside the quoted query:

```clojure
'[... [(fulltext $ :chunk/text ?q {:doc-filter (fn [_] true)}) [[?e _ _]]] ...]
```

In a quoted query `(fn ..)` is a list, never evaluated, so the search
engine calls a `PersistentList`:

```
java.lang.ClassCastException: class clojure.lang.PersistentList cannot be cast to class clojure.lang.IFn
```

Datalevin passes the options map through unchanged
(`built_ins.clj` `fulltext-request` → `search`), so the fix is to pass the
map, fn included, as a query input:

```clojure
(d/q '[:find ?id :in $ ?q ?opts
       :where [(fulltext $ :chunk/text ?q ?opts) [[?e _ _]]] [?e :chunk/id ?id]]
     db "quick" {:doc-filter (fn [doc-ref] ...)})
```

This works: the filtered doc is excluded.

### 2. The filter receives a datom ref, in two shapes

The argument is not an entity id:

- `[e aid v]` for a normal datom;
- `[:g gid e aid]` for a giant datom (serialized value over about 497
  bytes, `constants.clj` `+val-bytes-wo-hdr+`).

LevinRAG chunks (~350 tokens, mostly 3-byte CJK) are nearly always giant,
so a filter must handle both (`ref-eid` in the script). T0.4's
`(fn [e] (not= e 2))` would have compared a vector to a number even if it
had been called.

`:vec-filter` receives `[e aid vec]`.

### 3. Both filters run after the top-k

300 chunks match; the filter admits only the 10 worst-ranked.

| Call | Results | A pre-top-k filter would give |
|---|---|---|
| fulltext `:doc-filter`, `:top 50` | 0 | 10 |
| fulltext `:doc-filter`, `:limit 50` | 0 | 10 |
| fulltext `:doc-filter`, `:top 300` | 10 | 10 |
| vector `:vec-filter`, `:top 50` | 0 | 10 |
| vector `:vec-filter`, `:top 300` | 10 | 10 |

The source says the same. In `search.clj` `search` (~830-864) the engine
scores `top` candidates (`cached-search-results`), takes the page, and
only then applies `doc-filter` in `display-xf`. In `vector.clj`
`search-vec` (~855-868) HNSW returns `top` keys, then `vec-filter` drops
some of them.

### 4. Latency: no gain over the current post-filter

Experiment 4 (`-main "bench"`): T0.5 scale (10,000 docs, 100,000 chunks,
50 groups, 1–3 groups per doc), chunks of 80 terms (~700 bytes, giant
like real chunks), `:top 200`, 100 timed runs per row after 20 warm-up
runs, the same seeded query/group sequence for every variant. All three
returned identical hits.

- **A** (current `retrieval/datalevin.clj`): top 200, join to the doc,
  keep hits whose doc is in the readable doc set.
- **B1**: `:doc-filter` looks up each hit's doc (`d/datoms :eav e
  :chunk/doc`) and checks the readable doc set.
- **B2**: `:doc-filter` checks a precomputed readable *chunk* set.

| User's groups | A p50 / p90 (ms) | B1 p50 / p90 | B2 p50 / p90 | Hits kept |
|---|---|---|---|---|
| 1 | 3.57 / 5.99 | 2.81 / 3.49 | 2.11 / 5.58 | 8.2 |
| 3 | 3.06 / 3.82 | 2.68 / 3.32 | 10.44 / 11.59 | 23.9 |
| 50 | 8.06 / 8.66 | 8.97 / 9.76 | 99.11 / 118.52 | 200 |

A and B1 are within about 1 ms of each other either way, which is noise
at this size (one run on an M1 laptop). B2 gets slow as the user's reach
grows, because building the chunk set costs more than the search.
`:doc-filter` saves only the tuple emission for rejected hits, and the
join it skips is cheap next to the fulltext scoring both variants do.

## Decision

- `:doc-filter` is usable from Datalog, correcting T0.4. It is not a
  pre-filter: it cannot return an admitted document that falls outside the
  top-k, so it does not help recall for users with few permissions.
- LevinRAG keeps the T0.5 pattern: over-fetch, then check each hit against
  the accessible-doc-id set. Moving that check into `:doc-filter` would
  give the same results with the same starvation. The only gain would be
  skipping tuple emission for rejected hits, which is not worth the
  giant-ref special case.
- A real pre-filter would need an index-level feature Datalevin 1.1.0 does
  not have (e.g. a per-group search domain, or filtering inside the
  scorer/HNSW walk). Not pursued.
