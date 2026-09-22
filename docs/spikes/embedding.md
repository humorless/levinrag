# Spike: embedding path (T0.3)

Datalevin version: 1.1.0. Date: 2026-09-22.

## What was tested

- Path A: `:db/embedding` + `:openai-compatible` provider against vLLM
  (`BAAI/bge-m3`, `http://localhost:8001/v1`).
- Path B: application-side `hybridrag.llm.embed/embed-batch!` +
  `:db.type/vec` storage + vector neighbor query.

## Findings

### Path A — `:db/embedding` + `:openai-compatible`

**Schema syntax confirmed (from cljdoc 1.1.0 docs):**

```clojure
:chunk/text {:db/valueType            :db.type/string
             :db/embedding            true
             :db.embedding/domains    ["chunks"]
             :db.embedding/autoDomain true}
```

**Store options confirmed:**

```clojure
{:embedding-opts {:provider           :openai-compatible
                  :model              "BAAI/bge-m3"
                  :base-url           "http://localhost:8001/v1"
                  :api-key-env        "VLLM_EMBED_API_KEY"
                  :request-dimensions 1024
                  :metric-type        :cosine}}
```

**Connection lifecycle works** — `d/create-conn` with the above schema and opts
succeeds (verified in REPL). However:

- **Requires `VLLM_EMBED_API_KEY` at connection time** — `d/create-conn` throws
  `ExceptionInfo "OpenAI-compatible embedding provider API key env var is
  missing or blank"` if the env var is not set. This means you cannot even
  open a connection in test/dev environments where vLLM is not running.
- **Embedding is eager/async during `transact!`** — the spec mentions `:indexing-mode
  :async` as a valid option, but the actual behavior under `transact!` (blocking
  vs. fire-and-forget) was not tested due to lack of vLLM. Without vLLM,
  `transact!` fails when the embedding provider tries to call the API.
- **Query syntax** — `embedding-neighbors $ "query" {:top 2 :domains ["chunks"]}`
  is confirmed correct from the docs.
- **Batch transact timing** — not testable without vLLM. The spec wants a
  "500 short chunks" throughput number; this is deferred.

### Path B — `:db.type/vec` + application-side embedding

**⚠️ Re-verified 2026-09-22 in a fresh, non-REPL `clojure -M:jvm-opts -e '...'`
process (see `docs/decisions.md` "`:db.vec/domains` write-path bug") after
`docs/datalevin_debug_notes.md` §4 flagged this path as an unresolved
`transact!` crash. The crash is a real Datalevin 1.1.0 bug, root-caused to
source, and there is a confirmed working schema shape that avoids it — see
below. The findings in this section supersede the earlier version of this
doc, which incorrectly claimed `transact!` "succeeds" for a schema carrying a
custom `:db.vec/domains` value.**

**Schema syntax — confirmed working shape (do NOT set `:db.vec/domains`):**

```clojure
:chunk/vec {:db/valueType :db.type/vec}

;; Global vector index defaults (dimensions/metric apply to every vec domain):
{:vector-opts {:dimensions  1024
               :metric-type :cosine}}
```

Do **not** add `:db.vec/domains [...]` to the attribute — see "Known bug"
below. Leaving it unset makes Datalevin use the attribute's own
auto-derived domain name (`datalevin.vector/attr-domain`: `keyword->string`
with `/` replaced by `_`, e.g. `:chunk/vec` → `"chunk_vec"`), which is the
only configuration verified to actually transact.

To override per-domain options (e.g. a different `:dimensions`/`:metric-type`
than the global `:vector-opts` default) without touching `:db.vec/domains`,
key the top-level `:vector-domains` map by that same auto-derived name:

```clojure
{:vector-domains {"chunk_vec" {:dimensions  1024
                               :metric-type :cosine}}}
```

Both of the above (verbatim, minus dimension count) were run end-to-end in a
fresh JVM process and confirmed to transact and query correctly.

**Known bug — custom `:db.vec/domains` breaks `transact!` (confirmed, root-caused):**

Setting an explicit domain list on the attribute, e.g.
`:chunk/vec {:db/valueType :db.type/vec :db.vec/domains ["similarity"]}`,
reproduces the crash documented in `docs/datalevin_debug_notes.md` §4
(`IllegalArgumentException: No implementation of method: :add-vec of
protocol: #'datalevin.interface/IVectorIndex found for class: nil`) in a
genuinely fresh, non-`:reload`d JVM process — this is **not** a
classloader/REPL artifact, it is a real bug in Datalevin 1.1.0. Root cause,
read directly from the 1.1.0 jar's `datalevin/storage.clj`:

- At `create-conn`/`open` time, `init-vector-domains` (storage.clj ~3534)
  initializes **only** the domains explicitly listed in
  `:db.vec/domains` when that key is present — it does not also register
  the attribute's auto-derived domain name in that case.
- At `transact!` time, the write path (storage.clj ~2946, ~3053) always
  computes the target domain list as
  `(conjv (props :db.vec/domains) (v/attr-domain attr))` — i.e. it
  *always* also targets the attribute's auto-derived domain name, on top of
  whatever `:db.vec/domains` lists.
- Result: when `:db.vec/domains` is set, the write path tries to add the
  vector to a domain (the auto-derived one) that was never initialized in
  the `vector-indices` map → `(vector-indices domain)` is `nil` →
  `apply-vector-op!` crashes.

**Workaround (verified, not a hack — this is the shape to actually use):**
omit `:db.vec/domains` entirely. Then the write path's domain list and the
init path's domain list agree (both are just the attribute's auto-derived
name), and everything works. This is what the schema syntax above shows.

**Verified end-to-end in a fresh JVM (2026-09-22, `clojure -M:jvm-opts -e`,
disposable temp dir, no vLLM):**
- `d/create-conn` with the `:db.type/vec` schema above and `:vector-opts`
  succeeds **without** any vLLM dependency (no API key, no network call).
- `d/transact!` with pre-computed float vectors (`(float-array [...])`)
  succeeds.
- Query syntax `[(vec-neighbors $ :chunk/vec ?q-vec {:top n}) [[?e ?a ?v]]]`
  (attribute-keyword form, returns `[e a v]` triples, or `[e a v dist]` with
  `:display :refs+dists`) is confirmed correct — both from
  `datalevin.core/vec-neighbors`'s docstring and by running an actual query
  against the transacted vectors, which returned the two nearest neighbors
  correctly (identical + near-identical vectors ranked over an orthogonal
  one).
- The alternative form some other docs in this repo previously cited —
  `[(vec-neighbors $ ?qvec ?dims {:top n}) [[?e _ ?score]]]`, a "dimensions"
  positional argument — **is not a real signature**. `vec-neighbors` has no
  dimensions argument; the non-attribute form is
  `(vec-neighbors db query-vec {:domains [...] ...})`. This has been
  corrected in `docs/datalevin_debug_notes.md` and `docs/spikes/fulltext.md`
  as well.

**Application-side embedding flow:**
```clojure
(let [vecs (embed/embed-batch! ["text1" "text2" ...])
      datoms (mapv (fn [text vec] {:chunk/id text :chunk/vec vec})
                   texts vecs)]
  (d/transact! conn datoms))
```

## Decision

**Path B** (application-side embedding + `:db.type/vec`).

### Rationale

1. **No vLLM required for development/testing** — Path A's `:openai-compatible`
   provider requires `VLLM_EMBED_API_KEY` at connection time and attempts to call
   vLLM during `transact!`. Path B decouples embedding computation from DB
   operations entirely, so the full stack can be developed, tested, and CI'd
   without a running vLLM instance.

2. **Explicit control** — batch size, retry/backoff, caching, and error handling
   are all in application code (already implemented in `hybridrag.llm.embed` from
   Task 2), making them inspectable and testable rather than hidden inside
   Datalevin's embedding worker.

3. **No `:db.fulltext` / `:db.embedding` conflict** — Path B stores text in a
   `:db.type/string` attribute (with `:db/fulltext` for lexical search) and
   embeddings in a separate `:db.type/vec` attribute (with `:vector-domains` for
   semantic search). These are independent, confirmed by the separate docs pages
   for full-text search and vector/embedding indexing.

4. **Simpler spike execution** — Path A's full evaluation requires a running
   vLLM, async indexing wait logic, and timing measurements across hundreds of
   transacts. Path B's core behavior (connect, transact vectors, query) was
   verified in a few minutes with vLLM offline, and the remaining timing
   measurements are straightforward to add when vLLM is available.

## Consequence for later tasks

- **T1.1 index-schema** — `:chunk/index-text` will be `{:db/valueType :db.type/string
  :db/fulltext true :db.fulltext/autoDomain true}` (for lexical search).
  Embedding vector is stored in a separate `:chunk/vec` attribute with
  `{:db/valueType :db.type/vec}` — **deliberately with no `:db.vec/domains`
  key** — indexed in its auto-derived domain (`"chunk_vec"`) configured via
  `:vector-opts {:dimensions <dims> :metric-type :cosine}`. Do not add
  `:db.vec/domains` to this attribute: see "Known bug" above and
  `docs/decisions.md`'s `:db.vec/domains` write-path bug entry — it breaks
  `transact!` in Datalevin 1.1.0.
  
- **T2.1 semantic channel** — calls `hybridrag.llm.embed/embed-batch!` on the
  query text to get a float vector, then executes:
  ```clojure
  (d/q '[:find [?e ...]
         :in $ ?q-vec
         :where
         [(vec-neighbors $ :chunk/vec ?q-vec {:top n}) [[?e _ ?v]]]]
       (d/db conn)
       query-vec)
  ```

- **Retriever protocol** (SPEC.md §9.2) is unaffected — the `channel` method
  signature stays the same regardless of embedding path.

- **SPEC.md §6.1 draft store-options correction** — the spec's draft schema
  shows `:chunk/index-text` with both `:db/fulltext` and `:db/embedding` on the
  same attribute. This spike overrides that: `:db.fulltext` and embedding vectors
  live on separate attributes (`:chunk/index-text` for text, `:chunk/vec` for
  embeddings), because: (a) Path B is chosen; (b) the data is stored at
  application time, not in the schema.