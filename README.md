# levinrag

單一 JVM、嵌入式 Datalevin 的企業 RAG MVP：Markdown／純文字語料 → 詞彙＋語意＋連結圖多路召回 → RRF 融合 → cross-encoder rerank → 脈絡擴展 → 帶引用 `[n]` 的回答。內建 ACL、每次查詢的 trace 與評估框架。模型（embedding、rerank、chat）一律透過 OpenAI 相容 API 呼叫。完整規格見 [SPEC.md](SPEC.md)，設計取捨見 [docs/decisions.md](docs/decisions.md)。

> 程式的 namespace 仍沿用產生器給的 `hybridrag.*`。

## 使用手冊

- [維運手冊](docs/howto/ops.md)：部署、環境變數、health、備份、評估。
- [管理者手冊](docs/howto/admin.md)：使用者與群組、文件權限、匯入、trace。
- [使用者手冊](docs/howto/user.md)：登入、提問、引用、文件檢視、Debug 面板。

## 快速開始（本機）

1. 啟動三個模型端點，做法見 [VLLM_SETUP.md](VLLM_SETUP.md)，然後確認：`bb vllm:check`（全部 `[OK]` 才繼續）。
2. 匯入語料：`CORPUS_DIR=corpus-sample bb ingest`。
3. 建立使用者：`bb user:create alice --groups all,hr`，再用 `bb user:passwd alice` 設定密碼（管理者加 `--admin`）。
4. 啟動 server（完整的環境變數見[維運手冊](docs/howto/ops.md#環境變數)），開啟 http://localhost:8000 登入。

## 文件權限規則（ACL）

權限在匯入時計算並寫入索引，查詢時只比對群組：

1. **目錄**：往上找最近一個有宣告 `:read-groups` 的 `_collection.edn`（含自己）；都沒有就用 `ROOT_READ_GROUPS`。
2. **文件**：frontmatter 有 `read_groups` 時**只用它——覆寫，不是聯集**。例如目錄是 `["hr"]`、文件寫 `read_groups: ["all"]`，結果是只有 `all` 可讀，`hr` 不會被加回去。
3. 群組為空 `[]` 表示除 admin 外沒有人可讀。
4. 看不到的文件一律回 404（不回 403），不透露是否存在。

設定方式見[管理者手冊](docs/howto/admin.md#文件權限)。

## 開發

- nREPL、測試迴圈：見 [CLAUDE.md](CLAUDE.md)。
- 完整測試（乾淨 JVM＋coverage）：`clojure -X:jvm-opts:test`；lint：`clj-kondo --lint src test`。
- 需要真實模型的測試標記 `:vllm`，未設定 `VLLM_*` 時自動略過。
- `bb tasks` 列出所有 Babashka 指令。

### Browser check (no JS errors)

`bb browser-check` builds the CSS, starts a throwaway server on port 8765
(`dev/browser_server.clj`: sample corpus with a stub embedder, stub
rerank and chat, users `alice`/`alice-pw` and `admin`/`admin-pw`) and
drives the UI in the locally installed Google Chrome with Playwright
(`dev/browser/check.mjs`): login, ask with Debug on, open a citation and
its document, run an ingest from the admin page, open a trace. Any
console error, uncaught page error or failed asset request fails the
run. Needs Node.js; `playwright-core` is installed on first run (it uses
the local Chrome and downloads no browser). Not part of `bb test`.

## Update assets

The idea is to vendor all js-files in the project repo eliminating build step for js part.

Once you want to update the version of AlpineJS, HTMX or add a new asset, edit version in bb.edn file at `fetch-assets` and run:

```shell
bb fetch-assets
```

Your assets will be updated in `resources/public` folder.

## 部署

見[維運手冊](docs/howto/ops.md#部署kamal)。
