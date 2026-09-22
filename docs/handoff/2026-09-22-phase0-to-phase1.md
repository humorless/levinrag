# Handoff: Phase 0 complete → Phase 1 (Ingestion)

> Written 2026-09-22 for a coding-agent handoff (Claude Code session → pi
> agent). If you are the agent picking this up: **read this whole file
> before touching code.** It's the fastest path to the same context the
> previous session had.

## Where things stand

- Repo: `/Users/laurencechen/ForceUnion/levinrag`. Branch: `main` (this
  *is* the trunk — the repo was `git init`'d directly on what became
  Phase 0's working branch, then renamed to `main` once Phase 0 finished;
  there was never a separate base branch).
- Working tree is clean. `bb fmt-check` / `bb lint` / `bb test` all pass
  (4 tests, 10 assertions, 0 failures, 0 errors) as of the last commit on
  this branch.
- **Phase 0 (SPEC.md §17, T0.1–T0.5) is fully complete**, including a
  final whole-branch review and one fix wave that resolved everything it
  found. Phase 1 (Ingestion, T1.1–T1.5) has **not been started** — no
  plan file exists for it yet.

## Read these, in this order, before writing a Phase 1 plan

1. **`SPEC.md`** — the project spec. Sections that matter most for
   Phase 1: §6 (schema), §7 (ACL model + walker rules), §8 (chunking +
   analyzer), §17 Phase 1 (T1.1–T1.5's one-line AC each). Note: §0.2/§0.3
   forbid using Datalevin API from memory — anything not already verified
   by the docs below must be checked against the pinned `1.1.0` source
   before you write it.
2. **`docs/decisions.md`** — the running log of every place Phase 0 found
   reality disagreeing with SPEC.md's draft text. This is the authority
   Phase 1 argues from, not SPEC.md's draft guesses where they conflict.
   Read the whole thing; it's not long. The two entries that most directly
   constrain Phase 1's design:
   - **Embedding path (Path B)** and the follow-up **`:db.vec/domains`
     write-path bug** entry — tells you the exact working schema shape
     for `:chunk/vec` (do NOT set `:db.vec/domains` on the attribute; rely
     on the auto-derived domain name, or supply a matching top-level
     `:vector-domains` entry).
   - **ACL query pattern (§9.3) is too slow as literally specified** —
     Phase 2's problem on the surface, but it changes what ACL-adjacent
     fields Phase 1's index-schema needs (`:doc/effective-groups` as a
     cardinality-many string attribute is still right; just don't design
     Phase 1 around SPEC §9.3's literal join-through-group-list query
     shape being fast).
3. **`docs/spikes/embedding.md`**, **`docs/spikes/fulltext.md`**,
   **`docs/spikes/acl-query-perf.md`** — the three Phase 0 spike reports.
   `embedding.md`'s "Consequence for later tasks" section and
   `fulltext.md`'s "Decision" + "Minimal executable example" sections are
   written specifically to be built from directly — they have working,
   verified code, not just narrative.
4. **`docs/datalevin_debug_notes.md`** — lower-level Datalevin internals
   notes (schema field naming is snake_case at the Java level, reflection
   techniques for inspecting a live `Store`, the debugging methodology
   used to root-cause the vec bug). Useful if you hit something that
   looks like a Datalevin quirk rather than a bug in your own code.
5. **`docs/superpowers/plans/2026-09-22-phase0-skeleton-and-spikes.md`**
   — the Phase 0 plan, for its **Global Constraints** section (still
   binding for Phase 1: one commit per task with message format
   `T1.N: <summary>`, code comments in English / user-facing text in
   Traditional Chinese, ambiguous requirements get the simplest
   reversible choice logged to `docs/decisions.md` rather than a stop-
   and-ask, no scope expansion beyond a task's AC — put ideas in
   `docs/backlog.md` instead). Its final "Phase 0 exit check" section and
   the note right after it explain why Phase 1 has no plan file yet: *"its
   file-level design... depends on what Tasks 3 and 4 actually find. Write
   Phase 1's plan as a fresh `docs/superpowers/plans/<date>-phase1-
   ingestion.md`... using the spike docs as its input instead of SPEC.md's
   draft guesses."* That's still the instruction — nobody has done it yet.
6. **`docs/backlog.md`** — a handful of concrete Phase 1-relevant follow-
   ups (accessible-doc-ids caching, the CJK-tokenizer-is-still-a-toy-
   analyzer gap, `/health`'s shallow liveness check). Worth a skim before
   you scope Phase 1's tasks so you don't accidentally re-surface these
   as if they were new discoveries.

## What Phase 1 actually needs to do

Per `SPEC.md` §17: T1.1 walker + ACL materialization (four rules from
§7.2 need unit tests, including override semantics), T1.2 Markdown →
section-tree parsing, T1.3 chunker + token estimation, T1.4 index writer
(`index-doc!`/`delete-doc!`, hash-based incremental reindex, ACL-only
changes must NOT trigger re-embedding), T1.5 CLI (`bb ingest`, `bb
reindex`) + sample corpus. This is where the **real** `index.dtlv` schema
finally gets written (it's been deferred since T0.1 — see
`src/hybridrag/db/schema.clj`'s comment, which now correctly describes
Path B's shape as a starting point, and the `:db.vec/domains` decision
above for the vec attribute specifically).

**Before writing the Phase 1 plan**, use the same process Phase 0 used
(if your harness has it): a `writing-plans`-style step against SPEC.md
§7/§8/§17 plus the docs above, producing
`docs/superpowers/plans/<date>-phase1-ingestion.md` with the same shape
as the Phase 0 plan file (Global Constraints, per-task Files/Interfaces/
Steps, exact code where the design is already settled by the spike docs).
If your harness doesn't have an equivalent skill, the Phase 0 plan file
is a good template to imitate directly.

## Known open items — not blocking, but you should know about them

- **`bb vllm:check` has never been run against a real vLLM instance** in
  this development environment (`docs/decisions.md`, last-but-one entry).
  No `VLLM_*` env vars are set anywhere in this environment. T0.2's stub-
  server tests cover client correctness, but Phase 1's ingestion
  (T1.4/T1.5) needs *real* embeddings to be genuinely tested end-to-end.
  If you don't have a real vLLM instance either, you can still build
  Phase 1 against the stub-tested client code, but flag anywhere your
  work is only stub-verified, the same way this repo has been doing.
- **Commit `4ac13e2`** (before this session) added a new `⚠️ VERIFY`
  design decision straight into `SPEC.md` §8 (HanLP 1.x prioritized over
  Jieba for the CJK tokenizer) without a corresponding `docs/decisions.md`
  entry or commit attribution trailer. The previous session flagged this
  as a judgment call for the human, not something to silently revert —
  it's still sitting there unresolved. Don't be surprised by it; don't
  fix it unilaterally either unless asked to.
- Six Minor findings from Phase 0's final review were deferred rather
  than fixed (all non-blocking): `docs/backlog.md` consolidation (now
  done, see above), the `:nrepl` alias addition riding in on an unrelated
  commit, two unused `test-utils` helper fns (`index-conn`/`app-conn` —
  they're plan-mandated for later phases, not dead code), and
  `README.md` still being the untouched project template (deferred to
  T5.3 by the spec itself).

## Quick-start commands

```bash
cd /Users/laurencechen/ForceUnion/levinrag
bb deps          # install everything
bb fmt-check     # cljfmt check
bb lint-init && bb lint   # clj-kondo
bb test          # eftest + cloverage coverage report
bb clj-repl       # clj -A:dev:test:jvm-opts — interactive REPL, needed for
                  # any Datalevin API you haven't already verified
bb vllm:check     # hits real embed/rerank/chat endpoints if VLLM_* env vars are set
```

Remember: **never write a `datalevin.core`/`datalevin.storage` call from
memory.** Every Datalevin API surface this project currently relies on
was verified live against the pinned `1.1.0` jar first — that discipline
is why Phase 0 caught a real upstream-adjacent bug instead of shipping
broken vector storage into Phase 1. Keep it up.
