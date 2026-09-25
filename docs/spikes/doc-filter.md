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
