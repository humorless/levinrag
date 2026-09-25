# 快速上手：用自己的語料評估 LevinRAG

[English](quick-start.md) | 繁體中文
> 本文為英文版的翻譯；內容不一致時，以英文版為準。

對象：想用自己的文件跑 LevinRAG、檢驗它的主張，但不熟 Clojure 的人。每一步都列出指令和應該看到的結果。**如果你是照著本文操作的 AI agent：依序執行，把每一步的輸出和「預期」區塊比對，遇到第一個 `[FAIL]` 或 `錯誤：` 就停下來**——每一個都附有一行修正方法。

本文要檢驗的四項主張（論證見[設計理由](../design/rationale.zh-TW.md)）：

| 主張 | 一句話 | 章節 |
|---|---|---|
| ACL | 使用者絕不會拿到沒有權限讀的段落、引用或文件 | [8.1](#81-acl沒有權限的人拿不到任何東西) |
| 可重建 | 索引是衍生資料：從語料重建，結果相同 | [8.2](#82-可重建索引是衍生資料) |
| 可解釋 | 每一次查詢都看得到各通道找到什麼、rerank 怎麼改變排名 | [8.3](#83-可解釋為什麼是這一段) |
| 檢索品質 | hybrid + rerank 找得到正確文件；用量測說話，不是用宣稱 | [8.4](#84-檢索品質) |

## 0. 需要準備什麼

- macOS 或 Linux、`git`，以及 [mise](https://mise.jdx.dev)（它會依 `.mise.toml` 安裝 Java 21、Clojure、Babashka、Tailwind 的指定版本）。
- **三個模型端點**，OpenAI 相容 API：embedding（預設 bge-m3）、rerank（bge-reranker-v2-m3）、chat。[VLLM_SETUP.zh-TW.md](../../VLLM_SETUP.zh-TW.md) 說明 GPU 上的 vLLM，或筆電上的 LM Studio + llama.cpp。
- **語料要是 Markdown（`.md`、`.markdown`）或純文字（`.txt`）。** PDF 與 Office 檔不會被讀取，請先轉檔（例如用 `pandoc`、`marker` 或 `docling`）。請保留標題：chunk 不會跨章節，而章節路徑是每個 chunk 上下文的一部分。
- 20–50 個你知道答案出處的問題（第 8.4 節）。有了它，數字才有意義。

## 1. 安裝工具

```bash
git clone <repository URL> levinrag
cd levinrag
mise trust && mise install
```

如果這台機器第一次用 `mise`，先在 shell 裡啟用它（`eval "$(mise activate zsh)"`，或 `bash`），讓 `java`、`clojure`、`bb` 都來自 mise。第一個會啟動 Java 的 `bb` 指令會下載 Clojure 函式庫（幾百 MB，只有第一次）。

## 2. 設定

```bash
cp .env.example .env
```

編輯 `.env`。下面每個 `bb` 指令都會讀它；在 shell 裡 `export` 的變數優先於檔案。

- `CORPUS_DIR`：語料目錄。`DATA_DIR`：索引與帳號放的地方（`./data` 即可）。
- 三組 `VLLM_*_BASE_URL`／`VLLM_*_MODEL` 與 `VLLM_API_KEY`。用 LM Studio + llama.cpp 時，取消檔案最後那一段的註解。
- `ROOT_READ_GROUPS=all`：沒有其他權限設定的文件，`all` 群組讀得到。留空的話，只有 admin 讀得到。
- `VLLM_EMBED_DIMS`：只有在 embedding 模型不是 1024 維時才要改。

## 3. 準備語料與權限

權限以檔案形式寫在文件旁邊，在匯入時計算（完整規則見[管理者手冊](admin.zh-TW.md#文件權限)）。

```text
my-corpus/
├── _collection.edn            {:read-groups ["all"]}
├── handbook.md
├── hr/
│   ├── _collection.edn        {:read-groups ["hr"]}
│   └── salary.md
└── finance/
    ├── _collection.edn        {:read-groups ["finance"]}
    └── budget.md              frontmatter  read_groups: [finance-lead]
```

- 目錄採用往上找最近一個有 `:read-groups` 的 `_collection.edn`（含自己）。下層的宣告會**取代**上層，而不是加上去。
- 文件 frontmatter 的 `read_groups` 會完全取代目錄的群組。只寫一次、寫在同一行、用半形冒號、不加引號：`read_groups: [finance-lead]`；frontmatter 必須從檔案第一行的 `---` 開始。
- `[]` 表示只有 admin 讀得到。以 `.` 或 `_` 開頭的檔案與目錄會被略過（例如 `_drafts/`）。
- **寫錯時一律收緊。** `_collection.edn` 無法解析或有不認得的 key，或 `read_groups` 格式不對、拼錯（`read_group`、分成多行的 YAML 清單、`read_groups: hr`），受影響的文件都不會進索引，並列為錯誤；絕不退回較寬的設定。

## 4. 全面檢查

```bash
bb doctor
```

預期（路徑與數量依你的環境而定）：

```text
[OK]   java：Java 21
[OK]   clojure：已安裝 Clojure CLI
[OK]   css：網頁樣式尚未建置，bb serve 會自動建置
[OK]   .env：已讀取 .env
[OK]   VLLM_CHAT_BASE_URL：http://localhost:8003/v1
[OK]   VLLM_CHAT_MODEL：Qwen/Qwen3-8B
[OK]   DATA_DIR：./data
[OK]   CORPUS_DIR：my-corpus：137 個 .md／.markdown／.txt 檔
[OK]   根目錄權限：_collection.edn：all

檢查模型端點（bb vllm:check）：
[OK]   embed
[OK]   rerank
[OK]   rerank-long
[OK]   chat
```

`[FAIL]` 的下一行以 `→` 開頭，就是修正方法。`[FAIL] rerank-long` 表示 reranker 的 context 太小，放不下一整個 chunk；見 [VLLM_SETUP.zh-TW.md](../../VLLM_SETUP.zh-TW.md)。

## 5. 匯入

```bash
bb ingest
```

預期：

```text
docs: 137 added, 0 updated, 0 acl-updated, 0 skipped, 0 deleted, 0 errors
chunks: 1843 written this run, 1843 in index, longest 412 est. tokens
unresolved links: 3, index lag: 0, elapsed: 95210 ms
```

- 時間主要花在為每個 chunk 計算 embedding。再跑一次只會處理有變動的檔案。
- `errors`：每一筆都列出路徑與原因（無法解析的檔案，或第 3 步的權限錯誤）。其他檔案照常匯入；在錯誤修好之前，結束狀態碼是 1。先前已匯入、這次卻失敗的檔案（例如模型端點掛掉），會先從索引移除，直到某次匯入成功。
- `unresolved links`：連結目標不在語料裡的 Markdown 連結。不算錯誤。

## 6. 使用者

把要拿來測試的人寫進一個檔案（第 8 步的評估也用同一份）：

```clojure
;; my-eval/users.edn
{"alice" {:groups #{"all" "hr"} :admin? false}
 "bob"   {:groups #{"all" "finance"} :admin? false}
 "admin" {:groups #{} :admin? true}}
```

```bash
bb user:import my-eval/users.edn --dry-run   # 會做哪些變更
bb user:import my-eval/users.edn             # 每位新使用者的密碼只印這一次
bb acl:report --docs                         # 誰讀得到什麼
```

`bb acl:report` 列出每個群組的文件數與擁有者、每位使用者讀得到幾份文件，並以 `[WARN]` 標出沒有任何使用者擁有的群組（通常是打錯字）以及只有 admin 讀得到的文件。繼續之前先處理這些警告。

## 7. 在瀏覽器試用

```bash
bb serve
```

開啟 http://localhost:8000，以其中一位使用者登入並提問。回答會以 `[1]`、`[2]` 引用段落；點一下就會開啟文件並跳到那一段。用 Ctrl-C 停止 server。畫面說明見[使用者手冊](user.zh-TW.md)。

## 8. 檢驗主張

先寫題目。路徑相對於語料目錄：

```clojure
;; my-eval/questions.edn
[{:id "salary-01" :user "alice" :query "每個月幾號發薪水？"
  :expected-docs ["hr/salary.md"]}
 ;; 負向（ACL）題：bob 問一個只有 hr 能讀的主題
 {:id "acl-01" :user "bob" :query "薪資發放日"
  :must-not-docs ["hr/salary.md"]}]
```

- `:expected-docs`：正確的檢索應該排在前面的文件（用來算 recall 與 MRR）。
- `:must-not-docs`：這位使用者不能讀的文件；在任何變體的任何位置出現，都算一次洩漏。
- 一題可以同時有兩者。

```bash
bb eval --questions my-eval/questions.edn --users my-eval/users.edn
```

會印出表格、各階段延遲，以及結果檔的路徑（`eval/results/<時間>.edn`）。請保留這個檔案，裡面有每一題的排名。

### 8.1 ACL：沒有權限的人拿不到任何東西

1. `bb acl:report --users my-eval/users.edn --docs` 列出預期的權限矩陣：哪位使用者讀得到哪份文件。對照你原本的意圖。
2. eval 輸出中 `ACL leaks: 0`，結束狀態碼為 0。**只要有任何洩漏，`bb eval` 就以狀態碼 1 結束。** 每個有權限限制的目錄至少放一題 `:must-not-docs`，而且查詢要明顯對得上那份受限文件。
3. 在瀏覽器以沒有權限的使用者登入，開啟 `http://localhost:8000/docs/<受限文件的路徑>`：看起來必須和一份不存在的文件完全一樣（404，絕不是 403）。
4. 選做、更深入：`clojure -X:jvm-opts:test` 會執行端到端安全測試。它從索引推導出每一組「文件 × 不能讀它的使用者」，確認沒有任何 API 或網頁路徑會透露那份文件（搜尋、每個 eval 變體、回答、引用、交給模型的 prompt、文件檢視頁）。它使用內附的範例語料與 stub 模型，不需要模型端點。

`acl:report` 的讀者和檢索過濾用的是同一個函式計算；權限在匯入時就和每份文件一起寫入，過濾發生在檢索層內，不是在 UI。

### 8.2 可重建：索引是衍生資料

在另一個目錄從同一份語料建第二份索引，不碰原本那份，然後比較：

```bash
DATA_DIR=./data-rebuild bb ingest
DATA_DIR=./data-rebuild bb eval --questions my-eval/questions.edn --users my-eval/users.edn
DATA_DIR=./data-rebuild bb acl:report --users my-eval/users.edn --docs > /tmp/acl-rebuild.txt
bb acl:report --users my-eval/users.edn --docs > /tmp/acl-original.txt
diff /tmp/acl-original.txt /tmp/acl-rebuild.txt && echo "same permissions"
```

- 兩次 eval 的 recall／MRR／洩漏數應該完全相同（延遲會不同）。如果不同，代表模型不是確定性的（例如同一個 URL 背後換了模型），不是索引的問題。
- 權限必須完全相同：它只由語料檔案推導而來。
- 帳號不在索引裡：它們在 `DATA_DIR/app.dtlv`，所以上面的指令都用 `--users`。
- 如果要直接替換原本的索引：停止 server，執行 `bb reindex`。[維運手冊](ops.zh-TW.md#重建索引)有 server 不停機時的步驟。

### 8.3 可解釋：為什麼是這一段

- **瀏覽器**：勾選「送出」旁的 **Debug**。回答下方的表格列出每個候選段落：它在 lexical 與 semantic 通道的名次、融合（RRF）分數、是否由連結圖帶進來、rerank 分數、是否放進 prompt；接著是各階段耗時。
- **API**：建立一個 token（`bb token:create alice --label eval`，只顯示一次），然後：

  ```bash
  curl -s localhost:8000/api/v1/search -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d '{"query":"每個月幾號發薪水？"}' > /tmp/search.json
  jq '.candidates[] | {chunk_id, channels, rrf, rerank, selected}' /tmp/search.json
  curl -s localhost:8000/api/v1/traces/$(jq -r .trace_id /tmp/search.json) \
    -H "Authorization: Bearer $TOKEN" | jq '.stages | keys'
  ```

  每一次查詢都會留下 trace：各通道的前幾名、融合、連結圖加進來的段落、rerank 分數，以及交給模型的段落。admin 可以在網頁的「管理」看到所有 trace。用完後以 `bb token:revoke <前綴>` 撤銷 token。
- **eval 結果檔**：每一題、每個變體的文件排名都在裡面，「為什麼變體 X 漏掉這份」可以直接從檔案回答。

### 8.4 檢索品質

`bb eval` 用同一批題目為五種檢索變體打分數：

| 變體 | 用到什麼 |
|---|---|
| `lexical` | 只有全文檢索（CJK bigram） |
| `semantic` | 只有向量檢索 |
| `hybrid` | 兩者，以 RRF 融合 |
| `hybrid+rerank` | hybrid 之後，用 cross-encoder 重新排序候選 |
| `hybrid+rerank+graph` | 再加上沿著文件之間的連結擴展 |

- recall@5／recall@10：`:expected-docs` 出現在前 5／10 份文件的比例。MRR@10：第一份正確文件排得多前面。
- 預期 `hybrid+rerank` 會勝過兩個單一通道。內附的範例語料上每個變體都是 1.0，所以需要你自己的語料與題目。
- 出現 **degraded** 題數的警告，表示執行期間有模型端點失敗（例如 rerank）；先修好再比較數字。
- `VLLM_RERANK_MIN_SCORE`（預設 -7.0，單位是 llama.cpp 的原始 logit）會在放進 prompt 前丟掉分數過低的段落。它不影響 `bb eval` 的排名，但換了 rerank 後端就要重新校準（[rerank 門檻](../spikes/rerank-threshold.md)）。
- 已知限制：只有一個中文字的查詢，在 lexical 通道找不到任何東西（索引使用 bigram）。

## 出問題時

| 症狀 | 原因 | 處理 |
|---|---|---|
| `錯誤： .env 第 N 行不是 KEY=VALUE` | `.env` 有一行格式錯誤 | 修正第 N 行；含空白或 `#` 的值要加引號 |
| 所有文件都只有 admin 讀得到 | 語料根目錄沒有 `_collection.edn`，`ROOT_READ_GROUPS` 也是空的 | 在 `.env` 設 `ROOT_READ_GROUPS=all`，再 `bb ingest` |
| 匯入後少了文件 | 權限寫錯（一律收緊） | 匯入輸出的錯誤會指出檔案與原因 |
| `[WARN]` 沒有使用者擁有的群組 | 語料或使用者檔打錯字 | `bb acl:report --docs`，修正後 `bb ingest` 或 `bb user:import` |
| `問答服務暫時無法使用（chat）` | chat 端點失敗 | `bb vllm:check`；訊息裡的 trace id 會指出是哪個端點 |
| 剛啟動時 `/api/v1/health` 顯示 chat `down` | LM Studio 閒置後的第一個請求比較慢 | 等 30 秒再看一次 |
| 網頁沒有樣式 | CSS 尚未建置，也沒有 `tailwindcss` | `mise install tailwindcss`，再 `bb css-build` |

## 回報結果

值得回傳的：eval 結果檔與印出的表格、`bb acl:report` 的輸出、`git rev-parse --short HEAD`、模型名稱與執行的地方（GPU 或筆電），以及本文任何和你實際看到的不一致之處。請不要傳語料本身。
