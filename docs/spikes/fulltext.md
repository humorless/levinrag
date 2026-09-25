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

> **Corrected 2026-09-25** (`docs/spikes/doc-filter.md`): the Datalog cast
> error below came from writing `(fn ..)` inside the quoted query, where it
> is an unevaluated list. Passed as a query input, `:doc-filter` works. It
> filters after the top-k, so it is still not an ACL pre-filter.

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

The UDF registration mechanism itself is now confirmed working end-to-end
(see "Minimal executable example" below — a real, whitespace-splitter toy
analyzer was registered and queried, not just sketched). The *content* of a
real CJK analyzer (Jieba/HanLP-backed tokenization) is still deferred to the
implementation phase; this spike only proves the registration mechanism.

Registration shape confirmed by the minimal example:

```clojure
{:runtime-opts   {:udf-registry registry}   ;; an atom from (udf/create-registry)
 :search-domains {"chunk/text" {:index-position? true
                                :analyzer {:udf/lang :clojure
                                           :udf/kind :analyzer
                                           :udf/id :cn-tokenize}}}}
```

The analyzer function itself must be registered separately, before
`create-conn`, via `(udf/register! registry {:udf/lang :clojure :udf/kind
:analyzer :udf/id :cn-tokenize} analyzer-fn)`, and must return a seq of
`[term position offset]` triples per call (same shape as Datalevin's builtin
`datalevin.analyzer/en-analyzer`). `:udf/lang` can be any keyword you like
as long as it's consistent between `register!` and the `:analyzer`
reference — it does not need to be `:java`; a plain Clojure function
registered under `:udf/lang :clojure` works with no external resolver.

### 8. :index-position? on Attribute

Attempted setting `:db.fulltext/indexPosition? true` directly on the attribute definition. This did not resolve the phrase query issue. The `index-position?` must be configured at the domain level via `:search-domains`.

## Known Issues

1. **Phrase query in Datalog integration**: Even with `:search-domains` containing `{:index-position? true}` for the correct domain, phrase queries fail. Standalone search engine works perfectly with the same configuration. Possible root causes:
   - Query without explicit domain arg (`[(fulltext $ ?q ...)`) defaults to `datalevin` domain (index-position?: false) instead of scanning all autoDomain-enabled domains
   - Potential Datalevin 1.1.0 bug in how `index-position?` propagates from config to engine during Datalog query execution

2. **:doc-filter in Datalog**: Inline function predicate in the fulltext options map doesn't work as expected. Needs a different approach (perhaps a separate query arg rather than inline).


3. **Vector search integration — now verified working (with a caveat)**: this
   was resolved during the final whole-branch review (2026-09-22), see
   `docs/spikes/embedding.md`'s "Known bug"/"Verified end-to-end" sections
   and `docs/datalevin_debug_notes.md` §4 for the full investigation.
   Summary: `d/transact!` with a `:db.type/vec` attribute succeeds, and the
   correct `vec-neighbors` Datalog syntax is the attribute-keyword form
   `[(vec-neighbors $ :chunk/vec ?qvec {:top n}) [[?e ?a ?v]]]` (returns
   `[e a v]` triples) — **not** the `?qvec ?dims` two-argument form this
   section previously claimed; `vec-neighbors` has no positional
   "dimensions" argument. The caveat: the schema attribute must **not** set
   `:db.vec/domains` (a confirmed Datalevin 1.1.0 write-path bug otherwise
   crashes `transact!` — see the links above for the root cause and the
   working configuration).
## Minimal executable example

T0.4's lead AC item ("自訂 analyzer 在 Datalog search domain 的註冊方式...含
可執行的最小範例") was not actually satisfied by the rest of this doc — §7
above described the registration shape in future/conditional voice without
ever running it, and cited a `src/sf18.clj` experiment file that does not
exist anywhere in this repo (dangling reference, removed). This section
fixes that: a trivial toy analyzer (whitespace splitter + lower-case, **not**
real CJK tokenization — that's still deferred, see §7) was actually
registered against a Datalog fulltext search domain and queried, in a fresh
`clojure -M:jvm-opts -e '...'` process, 2026-09-22.

```clojure
(require '[datalevin.core :as d]
         '[datalevin.udf :as udf]
         '[clojure.string :as str])

;; Toy analyzer: whitespace splitter, lower-cases. Must return a seq of
;; [term position offset] — same shape as datalevin.analyzer/en-analyzer.
(defn toy-analyzer [^String text]
  (loop [words (str/split text #"\s+") pos 0 offset 0 acc []]
    (if (empty? words)
      acc
      (let [w (first words) lw (str/lower-case w)]
        (recur (rest words) (inc pos) (+ offset (count w) 1)
               (conj acc [lw pos offset]))))))

(def registry (udf/create-registry))

(udf/register! registry
               {:udf/lang :clojure :udf/kind :analyzer :udf/id :toy-analyzer}
               toy-analyzer)

(def schema
  {:chunk/id   {:db/valueType :db.type/string
                :db/unique    :db.unique/identity}
   :chunk/text {:db/valueType           :db.type/string
                :db/fulltext            true
                :db.fulltext/autoDomain true}})

(def conn
  (d/create-conn dir schema
                  {:runtime-opts   {:udf-registry registry}
                   :search-domains {"chunk/text"
                                     {:analyzer {:udf/lang :clojure
                                                :udf/kind :analyzer
                                                :udf/id   :toy-analyzer}}}}))

(d/transact! conn [{:chunk/id "d1" :chunk/text "hello world quick fox"}
                    {:chunk/id "d2" :chunk/text "another quick brown fox"}])

(d/q '[:find ?id ?score
       :in $ ?q
       :where
       [(fulltext $ ?q {:display :refs+scores}) [[?e _ ?v ?score]]]
       [?e :chunk/id ?id]]
     (d/db conn) "quick")
```

**Actual output, this exact run:**
```
conn opened OK
TRANSACT OK
QUERY OK: #{["d1" 0.0] ["d2" 0.0]}
```

Both documents matched (both contain "quick"); the 0.0 BM25 score is
expected, not a bug — with the term present in every document in this
2-document toy corpus, IDF collapses to `log(N/n) = log(2/2) = 0`.

## Decision

**Confirmed working**: the UDF registration mechanism for custom fulltext
analyzers against a Datalog `:db.fulltext/autoDomain` search domain. The
shape is: `(udf/create-registry)` → `(udf/register! registry {:udf/lang _
:udf/kind :analyzer :udf/id _} analyzer-fn)` → pass `{:runtime-opts
{:udf-registry registry} :search-domains {"<domain>" {:analyzer
{:udf/lang _ :udf/kind :analyzer :udf/id _}}}}` to `d/create-conn`. The
analyzer function's contract: `(fn [^String text]) -> seq of [term position
offset]`, identical to the builtin `datalevin.analyzer/en-analyzer`'s return
shape.

**Confirmed result-tuple shape**: `(fulltext $ ?q {:display :refs+scores})`
against `[[?e _ ?v ?score]]` returns `#{[id score] ...}` — matches §1's
finding for the builtin analyzer; the custom analyzer doesn't change the
Datalog integration's result shape, only tokenization.

**Explicitly NOT resolved here, deferred to Phase 1**:
- Real CJK tokenization content (HanLP/Jieba integration, or a
  unigram/bigram fallback) — this spike only proves the registration
  mechanism with a trivial non-CJK toy analyzer.
- Phrase search (`:index-position? true`) through the Datalog integration —
  still broken per "Known Issues" #1 above, independent of which analyzer is
  registered.
- `:doc-filter` in Datalog — still broken per "Known Issues" #2, independent
  of the analyzer question.

See `docs/decisions.md`'s T0.4 entry for the SPEC.md-override consequences
(phrase queries and `:doc-filter` unusable from Datalog; autoDomain domain
naming) that Phase 2 T2.1 must account for.

## References

- Datalevin search docs: https://github.com/datalevin/datalevin/blob/master/doc/search.md
- Custom analyzer UDF example (Java): examples/java/README.md
- Custom analyzer UDF example (Python): bindings/python/README.md
- `new-search-engine*` implementation: datalevin/search.clj:1647
- `init-search-domains` implementation: datalevin/storage.clj:3492
- `parse-query` → `handle-maps` → phrase validation: datalevin/search.clj:977-997