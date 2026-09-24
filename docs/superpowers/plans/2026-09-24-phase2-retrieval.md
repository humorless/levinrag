# Phase 2: Retrieval & eval (T2.0–T2.6)

**Goal:** principal + query → ACL-filtered lexical/semantic recall → RRF →
graph → rerank → context packing → `POST /api/v1/search` with a trace, and
`bb eval` over five variants. SPEC.md §9, §11, §13, §14, §15, §18.3.

Local backends for verification (no vLLM here, see `VLLM_SETUP.md`):
embed = LM Studio bge-m3 `:1234`, rerank = llama.cpp `:8002`.

## Tasks

| Task | Output | AC (SPEC §17) |
|---|---|---|
| T2.0 | `auth/{users,token,middleware,cli}.clj`, `bb user:* token:*` | bearer auth on `/api/v1/*` (not `/health`) |
| T2.1 | `search/analyzer.clj`, `retrieval/{protocol,datalevin}.clj` | §8.3 vectors; §18.3 channel-level security tests |
| T2.2 | `retrieval/{fusion,graph}.clj` | RRF unit tests incl. ties; graph no dupes, ACL-bound |
| T2.3 | `retrieval/rerank.clj` | timeout / 500 / 200-with-error all degrade + flag |
| T2.4 | `retrieval/context.clj` | budget held; no duplicate sentences; contiguous numbering |
| T2.5 | `retrieval/pipeline.clj`, `trace.clj`, `api/*` | `POST /search` + trace |
| T2.6 | `eval/harness.clj`, `bb eval` | 5-variant report; ACL leaks = 0; results file |

## Design decisions (details → `docs/decisions.md` as they land)

- **Principal** `{:username :groups #{..} :admin? bool}` is the only identity
  shape past the auth adapters (decision 2026-09-24).
- **API routes skip CSRF**: anti-forgery moves from global middleware to the
  web (cookie) routes; `/api/v1/*` authenticates by bearer token only.
- **Token revoke by prefix**: `:token/prefix` (first 8 chars of the token)
  added to app-schema so `bb token:revoke <prefix>` can find it; only the
  sha256 of the full token is stored.
- **CJK analyzer = §8.1 bigram algorithm** (the §8.3 vectors are bigrams);
  HanLP/Jieba not needed for them → backlog. Registered as a UDF on every
  open of index.dtlv; changing it needs `bb reindex`.
- **ACL = accessible-doc-id set + `contains?`** (T0.5 decision), admin path
  is a separate function, empty groups → no DB query.
- **Channel results carry metadata**: `channel` returns
  `{:candidates [...] :extended [...] :raw-hits n :after-acl m :starved? b}`
  (`:extended` = full ACL-filtered over-fetch list, needed by §9.5 step 3).
- **Protocol gains `chunks`** (ACL-filtered fetch by chunk id) so rerank and
  context get text without leaving the Retriever for ACL.
- **Passages merge consecutive ordinals within one section** only, so each
  passage has one accurate `:section/trail`.
- **Eval principals** come from `eval/users.edn` (the §15.3 seed users), so
  `bb eval` does not depend on app.dtlv contents.
