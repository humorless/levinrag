# Phase 1: Ingestion (T1.1–T1.5)

**Goal:** 從 `corpus/` 目錄完整 ingest Markdown 文件，經 ACL 物化 → 標題解析 → 分塊 → embed → 寫入 `index.dtlv`，並提供 `bb ingest` 與 `bb reindex` CLI。

**Prerequisite:** Phase 0 plan fully executed — `docs/spikes/embedding.md`, `docs/spikes/fulltext.md`, `docs/spikes/acl-query-perf.md` exist; `docs/decisions.md` has all spike entries; working tree clean.

> **⚠️ VERIFY ALL DATALEVIN 1.1.0 VEC-DOMAIN API CALLS AT REPL** — never guess against the docs.
> Any API call involving `:db.vec/` must first be tested at the REPL with a throwaway database.

---

## Scope (SPEC.md §17, Phase 1 only)

| Task | Summary | Key output |
|------|---------|------------|
| **T1.1** | Walker 與 ACL 物化 + index schema | `src/hybridrag/ingest/{walker,acl,markdown,chunker}.clj` + index schema + `bb ingest` |
| **T1.2** | Markdown → section tree | `src/hybridrag/ingest/markdown.clj` |
| **T1.3** | Chunker + token estimation | `src/hybridrag/ingest/chunker.clj`, `src/hybridrag/ingest/tokens.clj` |
| **T1.4** | Index writer (hash-based delta) | `src/hybridrag/ingest/writer.clj` |
| **T1.5** | CLI (`bb ingest`/`bb reindex`) + sample corpus | `bb` tasks + `corpus-sample/` + `eval/questions.edn` |

> **Note on task ordering:** Phase 0 plan had T0.1–T0.5; this plan maps to SPEC.md §17 Phase 1 T1.1–T1.5. However, before T1.1's walker can write to `index.dtlv`, the **index schema must be defined and tested** (blocked on vec-domain API verification). I'm reorganizing the phases slightly:
>
> - **T1.0: API verification** — verify vec-domain write path, define index schema (this is a pre-task, not counted in T1.1–T1.5)
> - **T1.1–T1.3:** walker, markdown parser, chunker (pure functions, no DB)
> - **T1.4:** index writer + `bb ingest`/`bb reindex`
> - **T1.5:** sample corpus + eval harness

---

## File inventory (new + modified)

### New files

| File | Purpose |
|------|---------|
| `src/hybridrag/ingest/walker.clj` | Dir walker — collect files, resolve ACL, compute relative paths |
| `src/hybridrag/ingest/acl.clj` | ACL rules engine — resolve §7.2 four rules, override semantics |
| `src/hybridrag/ingest/markdown.clj` | CommonMark parser → section tree (ATX/Setext), frontmatter extraction |
| `src/hybridrag/ingest/chunker.clj` | Section-aware chunker with heading overlap, code-block/table boundary awareness |
| `src/hybridrag/ingest/tokens.clj` | Token estimation (char-based heuristic; later swappable for real tokenizer) |
| `src/hybridrag/ingest/writer.clj` | Batch index writer — `index-doc!`, `delete-doc!`, hash-based delta, embed batch calls |
| `src/hybridrag/ingest/report.clj` | Ingestion report — summary stats, per-file status, errors |
| `src/hybridrag/ingest/job.clj` | Orchestration — `ingest-corpus!` that ties walker → parser → chunker → writer |
| `bb.edn tasks` | `bb ingest`, `bb reindex` (modify existing bb.edn) |
| `corpus-sample/` | Sample corpus (~20 files, §15.3) |
| `eval/questions.edn` | Eval question bank (≥30 questions, §15.1) |
| `test/hybridrag/ingest/walker_test.clj` | ACL rules unit tests |
| `test/hybridrag/ingest/acl_test.clj` | Override semantics, hierarchy tests |
| `test/hybridrag/ingest/markdown_test.clj` | ATX/Setext/mixed/nested/empty tests |
| `test/hybridrag/ingest/chunker_test.clj` | No-cross-section, max-token, code-block/table, overlap tests |
| `test/hybridrag/ingest/tokens_test.clj` | Token estimation edge cases |
| `test/hybridrag/ingest/writer_test.clj` | Hash delta, delete, ACL-only change detection |
| `docs/spikes/vec-domain-verify.md` | (T1.0) Vec-domain API verification results |

### Modified files

| File | Change |
|------|--------|
| `src/hybridrag/db/schema.clj` | Define `index-schema` with `:chunk/index-text` (:db/fulltext + :db.fulltext/autoDomain) and `:chunk/vec` (:db.type/vec + :db.vec/transform or manual vec write) |
| `src/hybridrag/db/index_conn.clj` | Pass `index-schema` to `d/get-conn` (currently `{}`) |

---

## Preconditions

1. Phase 0 exit check passed (§19 of Phase 0 plan).
2. `docs/spikes/embedding.md` has confirmed **Path B** (embedding vector in separate `:chunk/vec` attribute, computed app-side).
3. `docs/spikes/fulltext.md` has confirmed **analyzer registration** pattern: `(.. (d/open-conn ...) (addSearchDomain "chunk/index-text" (.. (FullTextAnalyzer/create) (addFullText (name :chunk/index-text))))`.
4. `docs/spikes/acl-query-perf.md` has confirmed **doc-filter approach** (pre-filter doc IDs, then join) since over-fetch join was ≥ 100ms.
5. `bb test`, `bb lint`, `bb fmt-check` all pass on current working tree.

---

## Risk register

| Risk | Impact | Mitigation |
|------|--------|------------|
| **Vec-domain API is wrong** — `:db.vec/domains` or `:db.vec/transform` syntax differs from docs | Schema won't persist vectors | T1.0 pre-task: verify at REPL with throwaway DB before any ingestion code |
| **Vec-domain write-path bug** — the bug found in `datalevin_debug_notes.md` affects writing to vec attributes | Data written but not searchable | If confirmed, log in `decisions.md` and use workaround (empty `:db.vec/domains` tx + lazy init on first read) |
| **`:db.embed/transform` not supported** — cannot auto-compute vectors in Datalevin | Must keep app-side embedding | Already expected from Path B decision; no blocker |
| **CJK tokenizer mismatch** — HanLP 1.x vs Jieba decision still pending (§8 of SPEC.md) | Tokenizer may not segment Traditional Chinese well | T1.3 uses char-count heuristic for token estimation only; real tokenizer deferred to Phase 2. Log in `decisions.md` |
| **CommonMark `SourceSpans` API changes** — version mismatch | Section text extraction fails | Pin `org.commonmark/commonmark` version; verify at REPL in T1.2 |
| **Embedding service unavailable** during development | Ingestion can't complete end-to-end | T1.4 writer accepts a stub embedder; `bb ingest --dry-run` skips embedding |

---

## T1.0: Vec-domain API verification (pre-task)

**Files:**
- Create: `docs/spikes/vec-domain-verify.md`
- Modify: `src/hybridrag/db/schema.clj`
- Modify: `src/hybridrag/db/index_conn.clj`

**Interfaces:**
- Consumes: `docs/spikes/embedding.md` (Path B decision), `docs/spikes/fulltext.md` (fulltext config), `docs/datalevin_debug_notes.md` (known vec-domain bugs).
- Produces: definitive `index-schema` EDN, a confirmed vec-write test, and `docs/spikes/vec-domain-verify.md` with working code examples.

- [ ] **Step 1: verify `:db.embed/transform` support**

  Open a throwaway Datalevin DB (`:memory` or `/tmp/test-vec.dtlv`), define `:chunk/vec` with `:db.type/vec` + `:db.embed/transform` pointing to a function. Attempt to write an entity with `:chunk/vec [1.0 2.0 ...]` (a float vector). If `:db.embed/transform` throws "function not found" or similar, the API is not supported — revert to Path B (store raw vec directly, compute app-side).

  ```clojure
  ;; REPL test (do NOT commit this code)
  (require '[datalevin.core :as d])
  (d/delete-database "/tmp/test-vec.dtlv")
  (let [conn (d/get-conn "/tmp/test-vec.dtlv"
             {:schema {:chunk/vec {:db/valueType :db.type/vec
                                   ;; Try with transform first
                                   ;; :db.embed/transform (fn [_ v] v)}}})]
    ;; If transform field is accepted:
    (d/transact conn [{:db/id -1
                       :chunk/vec (into [] (repeatedly 1024 rand))}])
    ;; Check if vec persisted:
    (d/q '[:find ?v :in $ ?id :where [?id :chunk/vec ?v]]
         (d/db conn) -1))
  ```

  Log result in `vec-domain-verify.md`.

- [ ] **Step 2: verify vec-domain registration for `vec-neighbors` queries**

  Once Step 1 confirms vec attribute storage works, verify that `vec-neighbors` queries work on the vec attribute. The critical question is: does `vec-neighbors` work without explicit domain registration, or do we need `:db.vec/domains`?

  ```clojure
  ;; REPL test
  (d/q '[:find ?e ?sim :in $ ?vec $ :db.vec/domains :db.fn/call ?fn
         :where [?e :chunk/vec ?v]
                [(vec-neighbors $ :chunk/vec ?vec {:top 5}) [[?e2 ?sim]]]]
       (d/db conn) target-vector)
  ```

  If this fails with "vec domain not registered", try:
  ```clojure
  ;; Alternative: register vec domain explicitly
  (d/transact conn [{:db/id (d/tempid :db.part/user :vec-domains)
                     :db.vec/domains [[:chunk/vec {:dimensions 1024}]]}])
  ```

  Log which pattern works.

- [ ] **Step 3: verify `:db.fulltext/autoDomain` + `:chunk/vec` coexist on one attribute**

  Test that `:chunk/index-text` can have both `:db/fulltext` + `:db.fulltext/autoDomain` AND `:chunk/vec` can be stored alongside without interference. Write an entity with both attributes, then query via both fulltext and vec-neighbors.

  ```clojure
  ;; REPL test
  (d/transact conn [{:db/id -1
                     :chunk/id "test-1"
                     :chunk/index-text "測試文件內容"
                     :chunk/vec (into [] (repeatedly 1024 rand))
                     :chunk/doc -2}])
  ;; Fulltext query
  (d/q '[:find ?e :in $ ?q :where
         [(fulltext $ :chunk/index-text ?q {:top 10 :display :refs}) [[?e _]]]]
       (d/db conn) "測試")
  ```

  Log result.

- [ ] **Step 4: write the final `index-schema`**

  Based on verification results, write the definitive `index-schema` in `src/hybridrag/db/schema.clj`:
  - All attributes from SPEC.md §6.1
  - `:chunk/index-text` with `:db/fulltext` + `:db.fulltext/autoDomain`
  - `:chunk/vec` with `:db.type/vec` + (dimensions, vec-domain config if required)
  - `:chunk/parent-section` and `:chunk/doc` as ref attributes

- [ ] **Step 5: update `index-conn` to pass schema**

  Change `index-conn`'s `d/get-conn` from `(d/get-conn dir {})` to `(d/get-conn dir {:schema hybridrag.db.schema/index-schema})`.

- [ ] **Step 6: commit**

  ```bash
  git add src/hybridrag/db/schema.clj src/hybridrag/db/index_conn.clj docs/spikes/vec-domain-verify.md
  git commit -m "T1.0: verify vec-domain API + define index schema"
  ```

---

## T1.1: Walker + ACL materialization

**Files:**
- Create: `src/hybridrag/ingest/walker.clj`, `src/hybridrag/ingest/acl.clj`
- Create: `test/hybridrag/ingest/walker_test.clj`, `test/hybridrag/ingest/acl_test.clj`

**Interfaces:**
- Consumes: `index-schema` (T1.0), SPEC.md §7 (ACL rules), SPEC.md §16 (project structure).
- Produces: for each `.md` file under `corpus/`, a map with `{:rel-path, :title, :frontmatter, :declared-groups, :effective-groups, :collection}`.

- [ ] **Step 1: implement `acl.clj`**

  Resolve §7.2 four ACL rules. Pure functions, no DB.

  ```clojure
  (ns hybridrag.ingest.acl
    "ACL rules engine — resolves §7.2 four rules and override semantics.")

  ;; Rule 1: collection _collection.edn declared-groups
  ;; Rule 2: parent effective-groups inherited
  ;; Rule 3: user groups from app.dtlv
  ;; Rule 4: override via frontmatter :acl or _collection.edn overrides

  (defn resolve-declared-groups
    "Given a file's relative path and the _collection.edn at each ancestor,
     return the declared-groups from the nearest _collection.edn."
    [rel-path collection-edns])

  (defn resolve-inherited-groups
    "Walk up from file's collection path to root, collecting effective-groups
     from parent collections. Return set of group strings."
    [collection-path parent-lookup])

  (defn resolve-override-groups
    "Apply frontmatter :acl override or _collection.edn override.
     Override semantics: :acl 'deny' removes groups, :acl 'allow' adds groups."
    [effective-groups frontmatter])

  (defn resolve-effective-groups
    "Full ACL resolution pipeline: declared → inherited → override."
    [rel-path collection-edns parent-groups frontmatter])
  ```

  Unit tests per SPEC.md §17 T1.1 AC:
  - Rule 1: `_collection.edn` in `/hr/policies/` gives `[hr policies]`
  - Rule 2: child inherits parent groups
  - Rule 3: frontmatter override adds/removes groups
  - Override semantics: deny takes precedence, multiple overrides compose LIFO

- [ ] **Step 2: implement `walker.clj`**

  Dir walker — collect `.md` files, compute relative paths, resolve ACL for each.

  ```clojure
  (ns hybridrag.ingest.walker
    "Walk corpus/ directory, resolve ACL for each file.")

  (defn- find-collection-edns
    "Find all _collection.edn files under corpus/."
    [corpus-dir])

  (defn collect-markdown-files
    "Recursively collect .md files under corpus/, returning [rel-path meta].
     meta includes {:title (from frontmatter or filename),
                    :mtime, :size, :size-bytes}."
    [corpus-dir collection-edns])

  (defn resolve-acl-for-file
    "Given a file's rel-path and resolved collection data, return the full
     ingest-ready map."
    [rel-path meta collection-edns group-lookup])
  ```

- [ ] **Step 3: write unit tests**

  `test/hybridrag/ingest/walker_test.clj` — dir walking, rel-path computation, file metadata.
  `test/hybridrag/ingest/acl_test.clj` — all four rules, override semantics.

- [ ] **Step 4: commit**

  ```bash
  git add src/hybridrag/ingest/walker.clj src/hybridrag/ingest/acl.clj
       test/hybridrag/ingest/walker_test.clj test/hybridrag/ingest/acl_test.clj
  git commit -m "T1.1: walker + ACL materialization"
  ```

---

## T1.2: Markdown → section tree

**Files:**
- Create: `src/hybridrag/ingest/markdown.clj`
- Create: `test/hybridrag/ingest/markdown_test.clj`

**Interfaces:**
- Consumes: string content (raw Markdown file bytes decoded as UTF-8).
- Produces: a vector of section maps, each with `{:heading, :level, :text, :source-spans, :children}`.

- [ ] **Step 1: verify CommonMark `SourceSpans` API**

  Before writing code, verify at REPL that `commonmark-source-spans` extension provides the `:source-spans` node attribute with start/end offsets needed for chunk text extraction.

  ```clojure
  ;; REPL test
  (require '[org.commonmark :as cm]
           '[org.commonmark.ext.source-spans :as ss])
  (let [parser (cm/parser-builder (.addPlugin (cm/parser-builder) (ss/source-spans-extension)))]
    (let [node (.parse parser "# Hello\nWorld")]
      ;; Check that block-level nodes have :source-spans attribute
      ))
  ```

  Log the exact API shape in `vec-domain-verify.md` or a new `spikes/commonmark-spans.md`.

- [ ] **Step 2: implement section-tree parser**

  ```clojure
  (ns hybridrag.ingest.markdown
    "Parse Markdown → section tree with ATX/Setext headings, frontmatter.")

  (defn- parse-frontmatter
    "Extract YAML frontmatter from Markdown string. Returns {:data ...} or nil."
    [^String md])

  (defn- parse-sections
    "Parse CommonMark AST into a flat vector of section maps.
     Each section: {:level (1-6), :heading (string), :text (raw markdown),
                    :source-offset (int), :source-length (int)}."
    [^String md frontmatter])

  (defn parse-markdown
    "Full parsing: frontmatter + section tree."
    [^String md]
    {:frontmatter (parse-frontmatter md)
     :sections (parse-sections md (parse-frontmatter md))})
  ```

  - Use `commonmark` + `commonmark-ext-gfm-tables` + `commonmark-ext-yaml-front-matter` + `commonmark-ext-source-spans`.
  - Source spans are critical: each section must track its byte offset/length in the original file so chunker can extract raw text without re-parsing.
  - Handle edge cases per SPEC.md T1.2 AC:
    - Mixed ATX/Setext headings → consistent tree
    - File with no headings → single root section
    - File with only frontmatter → empty sections vector
    - Deeply nested headings (> 6 levels) → clamp to level 6

- [ ] **Step 3: write unit tests**

  Test cases per SPEC.md T1.2 AC:
  - ATX + Setext mixed: `# H1\n## H2\n=== H2-setext`
  - No heading: entire file is one section
  - Only frontmatter: 0 sections
  - Deep nesting: 8 levels deep → levels 1-6 normal, 7+ clamped to 6

- [ ] **Step 4: commit**

  ```bash
  git add src/hybridrag/ingest/markdown.clj test/hybridrag/ingest/markdown_test.clj
  git commit -m "T1.2: Markdown → section tree parser"
  ```

---

## T1.3: Chunker + token estimation

**Files:**
- Create: `src/hybridrag/ingest/chunker.clj`, `src/hybridrag/ingest/tokens.clj`
- Create: `test/hybridrag/ingest/chunker_test.clj`, `test/hybridrag/ingest/tokens_test.clj`

**Interfaces:**
- Consumes: section tree from T1.2.
- Produces: vector of chunk maps ready for indexing: `{:id, :section-text, :char-range, :est-tokens, :hash}`.

- [ ] **Step 1: implement `tokens.clj`**

  Token estimation using character-count heuristic (CJK-aware). Real tokenizer (Jieba/HanLP) is deferred to Phase 2.

  ```clojure
  (ns hybridrag.ingest.tokens
    "Token estimation for chunk size limits.")

  ;; Heuristic: ~1.5 Chinese chars/token, ~4 English chars/token
  ;; For MVP, use a simple multiplier; Phase 2 swaps in real tokenizer

  (defn estimate-tokens
    "Estimate token count for a string. Approximate but fast."
    [^String text])

  (defn- chinese-char-count
    "Count CJK Unified Ideographs + CJK symbols/punctuation."
    [^String text])

  (defn- latin-char-count
    "Count Latin-1 + whitespace chars."
    [^String text])
  ```

- [ ] **Step 2: implement `chunker.clj`**

  ```clojure
  (ns hybridrag.ingest.chunker
    "Section-aware chunker with heading overlap, code-block/table boundary awareness.")

  (def default-config
    {:target-tokens 350
     :max-tokens 500
     :min-tokens 60
     :overlap-tokens 60})

  (defn chunk-section
    "Split a section into chunks respecting:
     - No cross-section boundaries
     - No chunk exceeds max-tokens (hard cut, mark with :truncated?)
     - Code blocks and tables are not split mid-element
     - Overlap: last N tokens of chunk N become first N of chunk N+1
     - char-range: [start-offset end-offset] for each chunk → can restore original text"
    [section {:keys [target-tokens max-tokens min-tokens overlap-tokens]}])
  ```

  Key algorithm decisions:
  - **Chunking strategy:** sentence-level split (using `。`, `！`, `？`, `；` for CJK; `.`, `!`, `?`, `;` for Latin) → accumulate tokens until target → if approaching max, try to split at next sentence boundary.
  - **Code blocks:** if a code block starts within a chunk, include the entire code block even if it pushes over max-tokens.
  - **Tables:** similarly, include entire GFM tables.
  - **Truncation:** if a single sentence exceeds max-tokens, hard-cut at max-tokens and set `:truncated? true`.
  - **Overlap:** last `overlap-tokens` characters (not tokens) of each chunk are prepended to the next chunk's text.

- [ ] **Step 3: write unit tests**

  Per SPEC.md T1.3 AC:
  - Chunks never cross section boundaries
  - No chunk exceeds max-tokens (unless `:truncated?`)
  - Code block not split mid-element
  - Table not split mid-element
  - Overlap amount is correct (verify first/last N tokens match)
  - `char-range` can restore original text (round-trip test: `(subs original start end)`)

- [ ] **Step 4: commit**

  ```bash
  git add src/hybridrag/ingest/chunker.clj src/hybridrag/ingest/tokens.clj
       test/hybridrag/ingest/chunker_test.clj test/hybridrag/ingest/tokens_test.clj
  git commit -m "T1.3: chunker with sentence-level overlap + token estimation"
  ```

---

## T1.4: Index writer + batch ingestion pipeline

**Files:**
- Create: `src/hybridrag/ingest/writer.clj`, `src/hybridrag/ingest/report.clj`, `src/hybridrag/ingest/job.clj`
- Create: `test/hybridrag/ingest/writer_test.clj`
- Modify: `bb.edn` (add `ingest`, `reindex` tasks)

**Interfaces:**
- Consumes: file maps from walker, section trees from parser, chunks from chunker, embedding API from `hybridrag.llm.embed`.
- Produces: entries in `index.dtlv`.

- [ ] **Step 1: implement `writer.clj`**

  ```clojure
  (ns hybridrag.ingest.writer
    "Batch index writer — index-doc!, delete-doc!, hash-based delta.")

  (defn- compute-file-hash
    "SHA-256 of raw file bytes."
    [^String abs-path])

  (defn- chunks->entities
    "Convert chunk maps to Datalevin entity maps for transact."
    [doc-id chunks collection-id])

  (defn index-doc!
    "Index a single document. Returns :indexed | :skipped (hash unchanged) | :acl-only."
    [conn file-map chunked-sections embed-all-fn]
    (let [current-hash (compute-file-hash (:abs-path file-map))
          existing (d/q '[:find ?h :in $ ?path :where
                          [?e :doc/path ?path] [?e :doc/hash ?h]]
                        (d/db conn) (:rel-path file-map))]
      (cond
        (empty? existing)
        ;; New file: chunk → embed → write
        (write-new-doc! conn file-map chunked-sections embed-all-fn)

        (= (first (first existing)) current-hash)
        :skipped ;; No change

        :else
        ;; Changed: delete old chunks, write new
        (write-changed-doc! conn file-map chunked-sections embed-all-fn))))

  (defn delete-doc!
    "Delete a document and all its chunks."
    [conn rel-path])
  ```

  - **Hash-based delta:** compare `:doc/hash` (SHA-256 of raw bytes). If unchanged, skip. If changed, delete old chunks and write new.
  - **ACL-only change detection:** if `:doc/hash` is unchanged but `:effective-groups` changed (from `_collection.edn` changes), update `:doc/effective-groups` only (no re-embedding).
  - **Embed batching:** collect all chunks from all files being ingested, batch them via `hybridrag.llm.embed/embed-all!`, then write with vectors.
  - **Error handling:** per-file error trapping — if one file fails (e.g., embed API error), log it but continue with other files.

- [ ] **Step 2: implement `job.clj`**

  Orchestration pipeline:

  ```clojure
  (ns hybridrag.ingest.job
    "Ingestion pipeline: walker → parser → chunker → writer.")

  (defn ingest-corpus!
    "Full ingestion: walk corpus/, parse, chunk, embed, write.
     Returns report map."
    [conn corpus-dir embed-all-fn])

  (defn reindex-corpus!
    "Full reindex: delete all index.dtlv corpus data, then ingest."
    [conn corpus-dir embed-all-fn])
  ```

- [ ] **Step 3: implement `report.clj`**

  Summary report: files ingested, skipped, errors, total chunks, total tokens, embed latency.

- [ ] **Step 4: add `bb` tasks**

  Modify `bb.edn` to add:
  ```yaml
  ingest:
    doc: "Ingest corpus/ into index.dtlv"
    task: >
      clojure -A:dev:jvm-opts
             -e "(require '[hybridrag.ingest.job])
                 (hybridrag.ingest.job/ingest-corpus! ...)"
  reindex:
    doc: "Reindex corpus/ (clear index first)"
    task: >
      clojure -A:dev:jvm-opts
             -e "(require '[hybridrag.ingest.job])
                 (hybridrag.ingest.job/reindex-corpus! ...)"
  ```

  Use Babashka `sci` eval via `clojure -X` exec function or `shell` + inline EDN.

- [ ] **Step 5: write unit tests**

  `test/hybridrag/ingest/writer_test.clj` — hash delta logic, delete correctness (chunk count after delete = 0), ACL-only change detection.

- [ ] **Step 6: commit**

  ```bash
  git add src/hybridrag/ingest/writer.clj src/hybridrag/ingest/job.clj
       src/hybridrag/ingest/report.clj bb.edn
       test/hybridrag/ingest/writer_test.clj
  git commit -m "T1.4: index writer + hash delta + ingestion pipeline"
  ```

---

## T1.5: CLI, sample corpus, eval questions

**Files:**
- Create: `corpus-sample/` (~20 files, §15.3)
- Create: `eval/questions.edn` (≥30 questions)
- Modify: `bb.edn` (finalize CLI)

**Interfaces:**
- Consumes: everything from T1.1–T1.4.
- Produces: runnable `bb ingest` that fully ingests `corpus-sample/`, `bb reindex` that clears and re-ingests.

- [ ] **Step 1: create `corpus-sample/`**

  Per SPEC.md §15.3:
  - ~20 files, Traditional Chinese primary with scattered English
  - Directory structure matching §7.1 (e.g., `hr/policies/`, `eng/specs/`, `finance/reports/`)
  - At least one `_collection.edn` with ACL override
  - At least one file with frontmatter ACL override
  - Files that cross-reference each other (links)
  - Files containing part numbers and form IDs (e.g., `SKU-A1234`, `HR-07`)
  - One very long section (> 10k chars, for chunk boundary stress test)
  - One file with tables and code blocks (for chunker boundary tests)

  Seed directories:
  ```
  corpus-sample/
  ├── _collection.edn              ; root: [all]
  ├── hr/
  │   ├── _collection.edn          ; declared: [hr]
  │   ├── leave.md                 ; 特休、年假規定
  │   ├── payroll/
  │   │   ├── _collection.edn      ; declared: [hr finance]
  │   │   ├── bonus.md             ; 年終獎金發放標準
  │   │   └── salary.md            ; 薪資結構
  │   └── onboarding.md            ; 新人報到流程
  ├── eng/
  │   ├── _collection.edn          ; declared: [eng]
  │   ├── specs/
  │   │   ├── api-v2.md            ; API 規格書
  │   │   └── sku-catalog.md       ; 料號編碼規則 (含 SKU-xxx)
  │   └── deploy-guide.md          ; 部署指南 (含 code blocks)
  ├── finance/
  │   ├── _collection.edn          ; declared: [finance]
  │   ├── budget-2025.md           ; 預算報告 (含表格)
  │   └── invoice-guide.md         ; 發票開立流程
  └── it/
      ├── _collection.edn          ; declared: [it]
      └── maintenance.md           ; 設備維護手冊 (HR-xxx 表單編號)
  ```

- [ ] **Step 2: create `eval/questions.edn`**

  Per SPEC.md §15.1, ≥30 questions:
  - ≥6 ACL negative cases (e.g., "bob queries bonus.md content → must-not-docs: [hr/payroll/bonus.md]")
  - ≥5 questions requiring exact identifier matching (e.g., "SKU-A1234 的規格？")
  - ≥5 questions requiring cross-document inference

- [ ] **Step 3: finalize `bb` tasks**

  Ensure `bb ingest` and `bb reindex` work end-to-end with sample corpus. Both should:
  - Load `index.dtlv` via Integrant or direct Datalevin conn
  - Print progress (files processed, total chunks, errors)
  - Exit with non-zero status on any unhandled errors

- [ ] **Step 4: commit**

  ```bash
  git add corpus-sample/ eval/questions.edn bb.edn
  git commit -m "T1.5: CLI ingest/reindex + sample corpus + eval questions"
  ```

---

## Phase 1 exit check

Before moving to Phase 2, confirm:

- [ ] `bb test`, `bb lint`, `bb fmt-check` all pass.
- [ ] `bb ingest` successfully ingests `corpus-sample/` end-to-end (no errors, report shows expected chunk count).
- [ ] `bb reindex` clears and re-ingests correctly.
- [ ] `docs/spikes/vec-domain-verify.md` exists with clear conclusions.
- [ ] `docs/decisions.md` updated with any Phase 1 decisions (e.g., CJK tokenizer status, vec-domain workaround if any).
- [ ] All SPEC.md §17 T1.1–T1.5 ACs verified against the actual implementation.

Phase 2 (`SPEC.md` §18) is **not** planned in this document — its query-channel implementation depends on the real index schema and vec-domain behavior confirmed in T1.0.