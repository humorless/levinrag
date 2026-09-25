# `retractEntity` + re-adding the same unique id in one tx — Datalevin 1.1.0 (2026-09-25)

## Goal

Backlog item #5. T1.4 (`docs/decisions.md`) found that retracting an
entity and re-adding the same unique `:chunk/id` in one transaction fails
with fulltext "Document does not exist.", and worked around it with
in-place upserts (`replace-tx` in `ingest/writer.clj`). The backlog asked
whether fixing or avoiding the failure would let the writer drop
`replace-tx`.

Reproduce (fresh JVM, repo root):

```
clojure -M:jvm-opts -e '(load-file "dev/spikes/retract_readd.clj")(spikes.retract-readd/-main)'
```

## Findings

The transaction is `[[:db/retractEntity [:chunk/id "c1"]] {:chunk/id "c1"
attr "new"}]` against an existing `{:chunk/id "c1" attr "old"}`.

### 1. With a fulltext attribute: exception, clean rollback

```
result: Document does not exist.
fulltext ops:
  [[datalevin] :d [1 6 old]]  [[chunk/text] :d [1 6 old]]
  [[datalevin] :d [1 6 old]]  [[chunk/text] :d [1 6 old]]
  [[datalevin] :a [1 6 new]]  [[chunk/text] :a [1 6 new]]
entity c1 after: {:db/id 1, :chunk/id c1, :chunk/text old}
```

The old datom is deleted twice per search domain. `search.clj`
`transact-docs` (~1390-1396) records the first delete in `deleted-refs`
and raises on the second. Nothing is written.

### 2. Without fulltext: success, wrong data

```
tx-data: [[1 :chunk/id c1 false] [1 :chunk/plain old false]
          [1 :chunk/plain old false] [1 :chunk/plain new true]]
lookup [:chunk/id "c1"]: nil
entity 1: {:db/id 1, :chunk/plain new}
```

The transaction commits an entity that has lost the unique id it
explicitly asserted. The same duplicate retraction appears in `tx-data`.

### 3. Controls

- The same two ops in two transactions: `{:db/id 2, :chunk/id c1,
  :chunk/plain new}`, correct.
- DataScript 1.7.5, one transaction: `tx-data [[1 :chunk/id c1 false]
  [1 :chunk/plain old false] [2 :chunk/id c1 true] [2 :chunk/plain new
  true]]`, lookup finds entity 2. It applies ops in order, so the upsert
  sees that `c1` is gone and creates a new entity.

### Cause

Datalevin resolves the upsert against the database as it was before the
transaction: `{:chunk/id "c1" ..}` resolves to entity 1, and the
`:chunk/id "c1"` assertion is dropped as already present. `retractEntity`
then removes that id and the old value, and the cardinality-one
replacement retracts the old value a second time. This is inferred from
the tx-data and the controls; I did not trace it in `db.clj`.

## Decision

- This is a Datalevin 1.1.0 bug, not a usage error: the committed state
  (an entity without the unique id the transaction asserted) matches
  neither DataScript's in-order semantics nor Datomic-style rejection of
  conflicting datoms. Not checked against Datalevin's issue tracker or
  later versions.
- `replace-tx` stays. The retract-then-re-add form cannot replace it even
  if the fulltext exception were avoided: without fulltext it silently
  corrupts the entity. `replace-tx` never retracts a datom twice (it
  retracts only attributes the new version drops and leaves
  cardinality-one replacement to the upsert), so it does not hit this.
- The fulltext exception is what protects LevinRAG today: it turns the
  bad transaction into a rollback.
