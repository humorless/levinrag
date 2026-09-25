# 管理者手冊

對象：管理帳號、文件權限與匯入的人。部署與備份見[維運手冊](ops.md)。

**大部分管理工作在命令列與檔案上完成，不在網頁介面裡。** 網頁的 `/admin` 只提供三項功能：執行匯入、查看最近一次匯入報告、瀏覽 trace。帳號與群組用 `bb` 指令管理；文件權限則寫在語料目錄的檔案裡。

以下指令都在專案目錄下執行，並透過 `DATA_DIR` 找到 `app.dtlv`／`index.dtlv`。正式環境的容器裡沒有 `bb`，改用[維運手冊](ops.md#部署kamal)中的 `java … -m` 寫法。server 執行中也可以直接下這些指令。

## 使用者與群組

```bash
bb user:create alice --groups all,hr          # 建立使用者（尚未設定密碼）
bb user:create admin --admin                  # 管理者：可讀取所有文件、進入 /admin
bb user:passwd alice                          # 設定或變更密碼（互動輸入兩次，至少 8 字元）
bb user:groups alice all,hr,finance           # 以新的清單「取代」整組群組
```

- 群組是自訂的字串，必須和語料裡 `read_groups` 使用的名稱完全一致。
- 群組變更**立即生效**：每個請求都會重新讀取使用者資料，不必等對方重新登入。
- 變更密碼後，這位使用者在**所有裝置**上的網頁登入會立即失效。使用者自己登出時也一樣。
- 網頁登入的有效期限為 8 小時（`SESSION_MAX_AGE_HOURS`）。

### API token（給程式或 CLI 使用）

```bash
bb token:create alice --label "報表腳本"   # 只顯示一次，請立刻保存；另外會印出撤銷用的前綴
bb token:revoke zHLGL9IT                    # 用前綴撤銷
```

token 以 `Authorization: Bearer <token>` 呼叫 `/api/v1/*`，權限與該使用者相同。token 沒有期限，登出或改密碼都不會影響它，不再使用時請撤銷。

## 文件權限

權限寫在語料目錄的檔案裡，**匯入時計算並寫入索引**。改完權限檔後，必須重新匯入才會生效。

### 目錄：`_collection.edn`

```clojure
;; corpus/_collection.edn — the root
{:name "全公司" :read-groups ["all"]}

;; corpus/hr/_collection.edn
{:name "人資" :read-groups ["hr"]}

;; corpus/hr/investigations/_collection.edn — a narrower subfolder
{:name "申訴調查" :read-groups ["hr-lead"]}
```

- 每個目錄往上找最近一個有 `:read-groups` 的 `_collection.edn`（包含自己這一層）。沒有宣告的子目錄沿用上層的設定。
- 根目錄沒有宣告時，使用 `ROOT_READ_GROUPS`；它預設為空，也就是只有 admin 可讀。
- 子目錄的宣告會**取代**上層的設定。上例中，`hr/investigations/` 只有 `hr-lead` 可讀，`hr` 群組讀不到。

### 單一文件：frontmatter `read_groups`

```markdown
---
title: 2025 尾牙活動公告
read_groups: [all]
---
```

- 這份文件放在 `hr/announcements/` 下，但只要寫了 `read_groups`，就**只用它**。這是**覆寫，不是聯集**：結果是 `all` 可讀，`hr` 群組不會因為目錄設定而被加回來（`hr` 成員本身若也在 `all` 裡，就讀得到）。
- `read_groups: []` 表示除 admin 外沒有人可讀。
- 檔名或目錄名以 `.` 或 `_` 開頭的會被忽略；可以用 `_drafts/` 放尚未公開的草稿。

只改權限、不改內文時，重新匯入不會重新計算 embedding，速度很快；報告中這類文件計為 `acl-updated`。

## 匯入語料

兩種方式，結果相同，都是增量匯入：只處理新增、修改或刪除的檔案。

- 網頁：以 admin 登入 → 上方「管理」→「執行增量 ingest」。執行期間狀態每 2 秒更新一次；同一時間只能有一個匯入在跑，重複按會顯示「已有 ingest 在執行」。
- 命令列：`bb ingest`。要完整重建時用 `bb reindex`（見[維運手冊](ops.md#重建索引)）。

報告摘要（網頁的「最近一次報告」，或 `DATA_DIR/ingest-reports/<時間>.edn`）：

```
docs: 22 added, 0 updated, 0 acl-updated, 0 skipped, 0 deleted, 0 errors
chunks: 119 written this run, 119 in index, longest 378 est. tokens
unresolved links: 0, index lag: 0, elapsed: 7336 ms
```

- `errors`：無法解析的檔案，會逐一列出路徑與原因，其他檔案照常匯入。
- `unresolved links`：`[文字](路徑.md)` 或 `[[頁面名稱]]` 找不到對應的目標文件。這不是錯誤，但會少掉一條連結圖的關聯。
- `index lag`：匯入結束時仍在背景建立的索引量，應該很快歸零。

## Trace（查詢紀錄）

每一次 `/search` 和 `/ask`，包含失敗的請求，都會寫入一筆 trace，存放在 `app.dtlv`。

- 網頁：「管理」頁列出最近 50 筆（時間、使用者、類型、查詢），點進去可以看各階段的內容：各通道的排名、RRF、rerank 分數、使用的 prompt 規模，以及降級與錯誤資訊。
- API：`GET /api/v1/traces/<id>`。只有 admin 或該 trace 的擁有者看得到，其他人一律得到 404。
- trace 只記錄 chunk id 與分數，不存文件內文。回答的全文會存在 `:trace/answer`。
- 使用者看到「問答服務暫時無法使用（chat）。 trace …」時，用那個 id 就能查到是哪個模型端點失敗。

## 備份

帳號、群組、token 和 trace 都在 `DATA_DIR/app.dtlv`。備份方式見[維運手冊](ops.md#備份)。
