# Fulltext Search Spike — Datalevin 1.1.0

## Goal

Spike Datalevin's `fulltext` search capabilities: BM25 scoring, phrase queries, `:doc-filter`, display modes, and domain configuration with `autoDomain`.

## Findings

### 1. BM25 Basic Search — ✅ Works

```clojure
(d/q '[:find ?id ?score
       :in $ ?q
       :where
       [(fulltext $ ?q {:display :refs+scores}) [[?e _ ?v ?score]]]
       [?e :chunk/id ?id]]
     db "quick")
;; => #{[d2 0.1300540707607465] [d1 0.058697086351893746]}
```

- BM25 scoring works correctly: higher frequency → higher score.
- Result shape: `#{[id score] ...}` (unordered set).

### 2. Result Shape Variants

| Query form | Result shape |
|------------|-------------|
| `[(fulltext $ ?q) [[?e _ ?v]]]` | `#{[id] ...}` |
| `[(fulltext $ ?q {:display :refs+scores}) [[?e _ ?v ?score]]]` | `#{[id score] ...}` |
| `[(fulltext $ ?q {:display :texts}) [[?e _ ?v ?text]]]` | `#{[id text] ...}` |
| `[(fulltext $ ?q {:display :offsets}) [[?id ?text ?offsets]]]` | `#{[id offsets] ...}` |
| `[(fulltext $ ?q {:display :texts+offsets}) [[?e _ ?v ?text ?offsets]]]` | `#{[id text offsets] ...}` |

### 3. Boolean Query Syntax — ✅ Works

```clojure
;; AND (all terms must appear, not necessarily adjacent)
(d/q '[:find ?id ?score ...] db [:and "quick" "fox"])

;; OR
(d/q '[:find ?id ?score ...] db [:or "quick" "slow"])

;; Nested
(d/q '[:find ?id ...] db [:or [:and {:phrase "quick brown"} "fox"]
                          [:and {:phrase "slow turtle"}]])
```

### 4. Phrase Query — ⚠️ Partially works

**Standalone search engine**: Phrase search works perfectly.

```clojure
(def engine (d/new-search-engine lmdb {:index-position? true}))
(d/search engine [:and {:phrase "quick brown"}])
;; => (1)  ;; doc-1 matched
```

**Datalog integration**: Phrase query with `[:and {:phrase "..."}` fails with:
```
Phrase search requires :index-position? true
```

Even after configuring `{:search-domains {"chunk/text" {:index-position? true}}}` at connection time. Debug shows the domain *does* have `index-position?: true`, but the query engine apparently reads from a different engine or the flag isn't propagated correctly.

**Status**: BM25 scoring and boolean queries work. Phrase query requires further investigation — may be a Datalevin 1.1.0 bug or a configuration issue with how `autoDomain` interacts with `index-position?`.

### 5. :doc-filter — ⚠️ Works in standalone, needs more testing

**Standalone**:
```clojure
(d/search engine "quick" {:doc-filter (fn [doc-ref] (not= doc-ref 2))})
;; => (1)  ;; doc-2 excluded
```

**Datalog**: Passing `(fn [e] (not= e 2))` as inline predicate in the query failed with a cast error. Need to investigate the correct way to pass the filter function to `fulltext` in Datalog queries.

### 6. Schema Configuration

```clojure
{:chunk/id     {:db/valueType :db.type/string
                :db/unique    :db.unique/identity}
 :chunk/text   {:db/valueType :db.type/string
                :db/fulltext  true
                :db.fulltext/autoDomain true}}  ;; Required for fulltext queries
```

Domain name for autoDomain attributes is `keyword->string` of the attribute name:
```clojure
(u/keyword->string :chunk/text) ;; => "chunk/text"
```

### 7. Chinese Text (CJK)

Default analyzer uses standard whitespace + punctuation splitting. Chinese text has no word separators, so **CJK fulltext requires a custom analyzer UDF**.

This is the same approach as Path B for embeddings — we'd register a custom tokenizer via `:udf-registry` and configure it in `:search-domains`:

```clojure
{:search-domains 
 {"chunk/text" {:index-position? true
                :analyzer {:udf/lang :java
                           :udf/kind :analyzer
                           :udf/id :cn-tokenize}}}}
```

The custom analyzer should tokenize Chinese text into characters or words (e.g., using Jieba or a unigram approach). This is deferred to implementation phase.

### 8. :index-position? on Attribute

Attempted setting `:db.fulltext/indexPosition? true` directly on the attribute definition. This did not resolve the phrase query issue. The `index-position?` must be configured at the domain level via `:search-domains`.

## Known Issues

1. **Phrase query in Datalog integration**: Even with `:search-domains` containing `{:index-position? true}` for the correct domain, phrase queries fail. Standalone search engine works perfectly with the same configuration. Possible root causes:
   - Query without explicit domain arg (`[(fulltext $ ?q ...)`) defaults to `datalevin` domain (index-position?: false) instead of scanning all autoDomain-enabled domains
   - Potential Datalevin 1.1.0 bug in how `index-position?` propagates from config to engine during Datalog query execution

2. **:doc-filter in Datalog**: Inline function predicate in the fulltext options map doesn't work as expected. Needs a different approach (perhaps a separate query arg rather than inline).


3. **Vector search integration unverified**: `vec-neighbors` query syntax `[(vec-neighbors $ ?qvec ?dims {:top n}) [[?e _ ?score]]]` confirmed from source code, but not yet tested with real Datalog + vec attribute transact. Standalone `d/new-vector-index` + `d/search-vec` works. Known blockers:
   - `:vector-domains` must be specified at `create-conn` time with `:dimensions` and `:metric-type`
   - Standalone vector index `d/new-vector-index` works independently; Datalog integration path untested
   - See `src/sf18.clj` for attempt; errors: "dimensions is required" (domain config), "IVectorIndex not found" (conn not attached)
## References

- Datalevin search docs: https://github.com/datalevin/datalevin/blob/master/doc/search.md
- Custom analyzer UDF example (Java): examples/java/README.md
- Custom analyzer UDF example (Python): bindings/python/README.md
- `new-search-engine*` implementation: datalevin/search.clj:1647
- `init-search-domains` implementation: datalevin/storage.clj:3492
- `parse-query` → `handle-maps` → phrase validation: datalevin/search.clj:977-997