# Phase 1 進度摘要（Session State）
**生成時間**: 2026-09-23 session end
**當前任務**: T1.1 — Walker + ACL materialization + unit tests

---

## 已完成

### Phase 0 回顧 & Phase 1 計畫
- [x] `docs/superpowers/plans/2026-09-23-phase1-ingestion.md` 已建立
- [x] Phase 0 handoff notes reviewed: `docs/handoff/2026-09-22-phase0-to-phase1.md`

### T1.0 — Vec-domain API 驗證
- [x] `create-conn` 3-arg signature 確認: `(create-conn :chunk {:index-text {:db/fulltext true :db.fulltext/autoDomain true} :vec {:db.type :db.type/vec}})`
- [x] `vec-neighbors` Datalog form 確認 (3 forms: `?id`, `?vec`, `?dist`)
- [x] 驗證結果寫入 `docs/decisions.md`

### T1.1 — Source 實現
- [x] `src/hybridrag/ingest/acl.clj` — ACL rules engine
  - `parent-dir`, `find-nearest-read-groups`, `resolve-collection-effective-groups`
  - `apply-acl-overrides`, `resolve-collection-groups-with-overrides`
  - `resolve-file-groups`, `resolve-effective-groups`
  - `nearest-collection-edn`
- [x] `src/hybridrag/ingest/walker.clj` — Directory walker
  - `acceptable-extension?`, `find-collection-edns`, `file-meta`
  - `collect-markdown-files`, `parse-frontmatter` (fixed)
  - `resolve-acl-for-file`, `walk-corpus`
- [x] `test/hybridrag/ingest/acl_test.clj` — 12 deftests
- [x] `test/hybridrag/ingest/walker_test.clj` — 10 deftests

### Bug Fixes (walker.clj)

| Line | Bug | Fix |
|------|-----|-----|
| 111 | `when-let` 丟棄 assoc 結果 | `if-let` with `m` else branch |
| 116 | 6 closes (多1個) | 5 closes |
| 120 | `nil)))` (多1個) | `nil))` |
| 138 | `(:abs-path file-meta)` | `(:abs-path fm)` |
| 142 | 3 closes | 1 close |
| 161 | 缺1個 close | 補上 |

### nREPL 開發環境
- [x] `tmux new-session -d -s nrepl 'clojure -M:nrepl --port 1667'` 穩定可用
- [x] `AGENTS.md` 記錄完整 workflow

### 工具文件
- [x] `AGENTS.md` 更新: `clojure_paren_repair`, `clojure_eval`, `clojure_find_nrepl_port` 說明
- [x] `clojure_paren_repair` 已確認為 `pi-clojure` npm extension tool (非 pi core builtin)

---

## 目前狀態

### walker.clj — 已修復，clj-kondo 通過
- 總行數: 161
- Bracket depth: 終值 = 0 ✓
- clj-kondo: 0 errors, 2 warnings (inline def)
- `parse-frontmatter` 現在用 `if-let` + `m` else 正確回傳 map

### acl.clj — 完整，無需修改

### 測試文件
- `acl_test.clj` — 12 deftests ✓
- `walker_test.clj` — 10 deftests ✓

---

## 下一步（待執行的 next agent）

### 1. 執行測試
```bash
clojure -X:test
```

### 2. 修復 clj-kondo 警告
在 `.clj-kondo/config.edn` 添加 suppression rules:
```edn
{:config-paths [; ...]
 :lint-as {; ...}
 :reports {:config {:disabled? false}}}
; Suppress inline def warnings for walk-corpus etc.
```

或將 `walk-corpus` 移到獨立 namespace 消除 "inline def" warning。

### 3. 修復測試中的已知問題
- `test-parse-frontmatter-extracts-title`: `parse-frontmatter` 現在應正確回傳 map
- `acceptable-extension?`: dotfile `.hidden.md` 應回傳 false (確認 `str/last-index-of` 行為)
- `file-meta`: 測試中有 asserts 類型錯誤（`:abs-path` 檢查 "integer?"）
- `resolve-acl-for-file`: L138 `fm` 現在是正確的 map 參數

### 4. Commit
```
T1.1: walker and acl materialization + unit tests
```

### 5. 繼續 T1.2/T1.3
- T1.2: Chunking (heading-aware sentence split)
- T1.3: Token estimation heuristic

---

## 關鍵決策回顧
- **Embedding Path B**: batch embedding via `hybridrag.llm.embed/embed-all!`
- **Fulltext + Vector**: `:db.fulltext/autoDomain` + `:db.type/vec` 共存
- **ACL Query**: Doc-filter with int-set (100k chunks ≥100ms too slow)
- **Chunking**: Heading-aware sentence split, code blocks atomic
- **nREPL Port**: 1667, tmux detached session

## 已知問題 & 需要繼續追蹤

### walker_test.clj 測試中的已知錯誤
1. **L82**: `(is (= "integer?" (:abs-path meta)))` — 字串 "integer?" 顯然是錯誤的 assertion
2. **walk-corpus**: `resolve-acl-for-file` 之前用 `file-meta`（函數）而不是 `fm`（參數），現在已修正
3. **acceptable-extension?**: 對 `.hidden.md` 的處理需確認 `str/last-index-of` 行為

### parse-frontmatter 修正
- 之前: `when-let` → 匹配時 assoc 結果被丟棄，永遠回傳 `{}`
- 現在: `if-let` + `m` else branch → 正確回傳 parsed map

### 目錄結構
```
src/hybridrag/
├── config.clj
├── db/
│   ├── index_conn.clj
│   └── schema.clj
├── llm/
│   └── embed.clj
└── ingest/
    ├── acl.clj      ← 已完成
    └── walker.clj   ← 剛修復
test/hybridrag/ingest/
    ├── acl_test.clj
    └── walker_test.clj
```

### 重啟提示詞
見下方 `重启提示词` 部分。

---

## 重啟提示詞

將以下內容貼給新的 pi agent:

```
請根據以下 session state 繼續 T1.1 的實現。walker.clj 的 parse-frontmatter bracket issue 已修復，但還沒有執行測試。

1. 先執行 `clojure -X:test` 看測試結果
2. 修復任何失敗的測試
3. 確認 clj-kondo 沒有 errors（warnings 可以忽略或 suppress）
4. Commit: `T1.1: walker and acl materialization + unit tests`
5. 繼續 T1.2 (Chunking)

詳細狀態請讀: docs/superpowers/plans/2026-09-23-session-state.md
```