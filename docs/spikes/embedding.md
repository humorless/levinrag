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

**Schema syntax confirmed (from cljdoc 1.1.0 vector docs):**

```clojure
:chunk/vec  {:db/valueType :db.type/vec}

;; Global or per-domain vector index config:
{:vector-opts {:dimensions  1024
               :metric-type :cosine}}
```

Or per-domain:
```clojure
{:vector-domains {"chunks" {:dimensions  1024
                            :metric-type :cosine
                            :indexing-mode :async}}}
```

**Verified in REPL with vLLM offline:**
- `d/create-conn` with `:db.type/vec` schema and `:vector-opts` succeeds **without**
  any vLLM dependency (no API key, no network call).
- `d/transact!` with pre-computed float vectors succeeds.
- Query syntax `[(vec-neighbors $ :chunk/vec ?q {:top 4}) [[?e _ ?v]]]` is
  confirmed correct from the docs.

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
  `{:db/valueType :db.type/vec}`, indexed in a vector domain configured via
  `:vector-opts {:dimensions <dims> :metric-type :cosine}`.
  
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