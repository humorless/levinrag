# Cont-Prompt: T1.1 Continue

## Session Context

**Session ID**: 2026-09-23-session-state  
**Commit**: `f720091`  
**Test Status**: 76 assertions, 11 failures, 5 errors  

## Current State

### Files
- `walker.clj`: 161 lines, balanced, clj-kondo 0 errors
- `acl.clj`: 159 lines, balanced, clj-kondo 0 errors (1 warning)

### Known Test Failures

#### ACL tests (4 failures)
1. `test-apply-acl-overrides-deny-takes-precedence` - deny/allow same group: deny should win
2. `test-parent-dir` (L151, L152) - parent-dir logic error
3. `test-apply-acl-overrides-deny` - deny overrides issue
4. `test-nearest-collection-edn` (L147) - missing file should return nil

#### Walker tests (7 failures)
1. `test-parse-frontmatter-empty-frontmatter` (L40) - empty frontmatter should return `{}` not `nil`
2. `test-parse-frontmatter-read-groups` (L33) - tags are symbols not strings
3. `test-parse-frontmatter-extracts-title` (L24) - same as above
4. `test-parse-frontmatter-extra-fields` (L51, L52) - same as above
5. `test-acceptable-extension-md` (L65) - dotfile `.hidden.md` should return false
6. `test-find-collection-edns-from-fs` (L106, L108, L110, L112) - find-collection-edns can't find _collection.edn

## Fix Guidance

### 1. `apply-acl-overrides` - "deny takes precedence"
The key insight: when a group appears in both deny and allow sets, deny wins.

```clojure
(defn apply-acl-overrides
  ([groups overrides]
   (if (seq overrides)
     (let [denied (reduce ...)
           allowed (reduce ...)]
       (-> (set groups)
           (clojure.set/difference denied)
           (clojure.set/union (clojure.set/difference allowed denied))))
     groups)))
```

The fix is to remove groups that are in both `allowed` and `denied`:
```clojure
(clojure.set/union (clojure.set/difference allowed denied))
```

### 2. `parse-frontmatter` - Empty frontmatter
The issue: `---\n---\nContent` has no content between the `---` delimiters.

Fix the regex to handle empty body:
```clojure
(re-find #"(?s)^---\n(.*?)\n?---" md)
```

The `\n?` allows matching `---\n---` (no newline between).

### 3. `parse-frontmatter` - Symbols vs Strings
The test expects `["all" "hr"]` but gets `[all hr]` (symbols).

Add conversion after parsing:
```clojure
(-> parsed
    (update :tags #(when % (mapv str %)))
    (update :read_groups #(when % (mapv str %))))
```

### 4. `acceptable-extension?` - Dotfiles
Ensure `> ext 0` check:
```clojure
(defn acceptable-extension?
  [filename]
  (when (string? filename)
    (if-let [ext (str/last-index-of filename \.)]
      (and (> ext 0)  ; dotfiles start at index 0
           (contains? accepted-extensions (subs filename (inc ext))))
      false)))
```

### 5. `find-collection-edns` - Path relativize
Ensure proper Path conversion:
```clojure
(let [root-path (.toPath root)
      file-path (.toPath (io/file file-dir))
      rel (.relativize root-path file-path)]
  ...)
```

## Commands to Run

```bash
# Run tests
clojure -X:test

# Check lint
clj-kondo --lint src/hybridrag/ingest/walker.clj src/hybridrag/ingest/acl.clj

# If nREPL running (port 1667), you can test functions:
clojure -X:test
```

## Next Steps

1. Fix all 11 test failures and 5 errors
2. Verify `clj-kondo` has 0 errors
3. Commit: `T1.1: walker and acl materialization + unit tests`
4. Read `docs/superpowers/plans/2026-09-23-phase1-ingestion.md` to understand T1.2 (Chunking)
5. Proceed to T1.2 implementation

## Critical Context

- **nREPL port**: 1667 (start with: `tmux new-session -d -s nrepl 'clojure -M:nrepl --port 1667'`)
- **Session state**: `docs/superpowers/plans/2026-09-23-session-state.md`
- **Phase 1 plan**: `docs/superpowers/plans/2026-09-23-phase1-ingestion.md`