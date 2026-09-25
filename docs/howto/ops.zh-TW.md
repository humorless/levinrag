# 維運手冊

[English](ops.md) | 繁體中文
> 本文為英文版的翻譯；內容不一致時，以英文版為準。

對象：負責部署、監控與備份的人。管理使用者與文件權限見[管理者手冊](admin.zh-TW.md)。

## 系統組成

- **一個 JVM process**：Web UI＋`/api/v1`，內嵌兩個 Datalevin 資料庫：
  - `DATA_DIR/index.dtlv`：由語料衍生的索引，隨時可以用 `bb reindex` 砍掉重建。
  - `DATA_DIR/app.dtlv`：使用者、群組、API token 與 trace。**這是唯一需要備份的資料**。
- **語料目錄 `CORPUS_DIR`**：真正的資料來源（Markdown／純文字檔）。應用程式只會讀取，不會寫入。
- **三個模型端點（OpenAI 相容 API）**：embedding、rerank、chat。應用程式不負責啟動它們；本機的架設方式見 [VLLM_SETUP.zh-TW.md](../../VLLM_SETUP.zh-TW.md)。

需求：JDK 21。每一個會開啟 Datalevin 的 JVM，都必須帶這兩個參數：
`--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED`。
`clojure -M:jvm-opts`、`bb` 指令和 Docker 映像檔都已經加上了。

## 環境變數

| 變數 | 預設 | 說明 |
|---|---|---|
| `DATA_DIR` | `data` | 放置 `index.dtlv`、`app.dtlv` 與 `ingest-reports/`。server 與 `bb` 指令讀取同一個變數。 |
| `CORPUS_DIR` | `./corpus` | 語料目錄。 |
| `ROOT_READ_GROUPS` | 空 | 根目錄沒有 `_collection.edn` 時的預設讀取群組，以逗號分隔。空值表示只有 admin 可讀。 |
| `SESSION_SECRET_KEY` | — | 正式環境（`:prod` profile）**必填**，用來加密 session cookie。更換後，所有人都必須重新登入。 |
| `SESSION_MAX_AGE_HOURS` | `8` | 登入後經過這麼多小時就必須重新登入。 |
| `VLLM_API_KEY` | — | 三個端點共用的 key。也可以分別設定 `VLLM_EMBED_API_KEY`、`VLLM_RERANK_API_KEY`、`VLLM_CHAT_API_KEY`。 |
| `VLLM_EMBED_BASE_URL` `VLLM_EMBED_MODEL` `VLLM_EMBED_DIMS` | `http://localhost:8001/v1` `BAAI/bge-m3` `1024` | Embedding 端點。 |
| `VLLM_RERANK_BASE_URL` `VLLM_RERANK_PATH` `VLLM_RERANK_MODEL` | `http://localhost:8002` `/v1/rerank` `BAAI/bge-reranker-v2-m3` | Rerank 端點。 |
| `VLLM_CHAT_BASE_URL` `VLLM_CHAT_MODEL` | — | Chat 端點，**必填**。 |
| `VLLM_CHAT_EXTRA_BODY` | — | 一個 JSON 物件，會原樣合併進 chat 請求。例如 Qwen3 on LM Studio 用 `{"reasoning_effort":"none"}`。格式錯誤時 server **無法啟動**。 |
| `VLLM_EMBED_TIMEOUT_MS` `VLLM_RERANK_TIMEOUT_MS` `VLLM_CHAT_TIMEOUT_MS` | `30000` `10000` `120000` | 讀取逾時。連線逾時固定為 2 秒。 |
| `VLLM_RERANK_MIN_SCORE` | `-7.0` | rerank 分數低於此值的段落不會送進 prompt。單位是 llama.cpp 的原始 logit；**更換 reranker 後端後要重新校準**，見 [rerank-threshold](../spikes/rerank-threshold.md)。 |

API key 不會寫進 log、trace 或 health 回應。

## 啟動

本機（`:default` profile，port 8000）：

```bash
cp .env.example .env      # 只需一次；接著編輯 .env
bb serve
```

會執行應用程式的 `bb` 指令（`serve`、`ingest`、`reindex`、`eval`、`vllm:check`、`user:*`、`token:*`）會讀取專案目錄的 `.env`，把其中的變數傳給它們啟動的 JVM。shell 裡已設定的變數優先於 `.env`，所以 `DATA_DIR=/tmp/other bb reindex` 照樣可用。`.env` 有格式錯誤的行時，這些指令會停下來並指出行號。`.env` 已被 gitignore；`.env.example` 列出所有變數。server 本身不讀 `.env`：不用 `bb` 時，先 export 變數再執行

```bash
clojure -M:jvm-opts -e "(require '[integrant-extras.core :as ig-extras]) (ig-extras/run-system {:profile :default :config-path \"config.edn\"}) @(promise)"
```

正式環境：uberjar（`bb build` → `target/standalone.jar`）以 `:prod` profile 跑在 port 80，並開啟 `Secure` cookie。

啟動前先檢查模型：`bb vllm:check`。它會對三個端點各送一個請求，並且用一份 1500 字的文件探測 rerank。reranker 的 context 設得太小時，短字串測試會通過，但長文件會 `[FAIL] rerank-long`；不處理的話，每一次查詢都會在 `rerank_failed` 的降級狀態下執行。

## Health

| 端點 | 檢查內容 | 用途 |
|---|---|---|
| `GET /api/v1/health/live` | 兩個資料庫各做一次真實讀取 | 負載平衡器與 Kamal 使用。模型重啟不會讓整個服務被摘除。 |
| `GET /api/v1/health` | 資料庫、三個模型各一個最小請求、index lag | 監控與人工檢查。 |

任一項檢查失敗時回 `503`，body 會列出每一項的狀態：

```json
{"status":"ok","checks":{"index_db":"ok","app_db":"ok","embed":"ok","rerank":"ok","chat":"ok"},"index_lag":0}
```

- 三個模型同時探測，每個最多等 5 秒，超過就回報 `down`。結果快取 30 秒（從探測結束時起算）；探測進行中收到的請求會等待同一輪的結果，不會另外再發請求，所以頻繁輪詢不會壓垮模型。在 M1 本機實測：暖機後一輪約 3 秒；模型冷啟動較慢，第一輪可能回報 `down`，30 秒後再查即可。
- `index_lag` 是尚未完成的全文與向量索引工作量。匯入進行中大於 0 是正常的，所以它**不會**讓 health 回 503。

## 錯誤與降級

- 模型端點逾時、連不上，或回應格式錯誤時，`/search` 與 `/ask` 回 `503 dependency_unavailable`，body 帶 `trace_id`，網頁版會顯示 `trace <id>`。這筆失敗 trace 可以在 `/admin` 查看，內容包含哪個端點失敗以及錯誤訊息。
- 只有 rerank 失敗時請求**不會**失敗：結果改用 RRF 排序，並回傳 `degraded: ["rerank_failed"]`，網頁會顯示「重排序失敗，結果依 RRF 排序。」

## 部署（Kamal）

> ⚠️ 以下步驟**尚未在實機上驗證過**，第一次部署後請修正本節。

設定在 `.kamal/deploy.yml`（執行 `bb kamal <指令>` 時會帶入）：

- 映像檔推到 ghcr.io，並在伺服器上遠端建置（`builder.remote`）。
- proxy healthcheck 使用 `/api/v1/health/live`。
- Volume 對應：
  - `/root/levinrag/data` → `/app/data`（兩個 DB 與匯入報告）
  - `/root/levinrag/corpus` → `/app/corpus`（唯讀）
- 需要的環境變數列在 `.kamal/secrets`（只寫 `$VAR` 參照，不放真實值）：`SERVER_IP`、`REGISTRY_USERNAME`、`REGISTRY_PASSWORD`、`APP_DOMAIN`、`SESSION_SECRET_KEY`，以及 `VLLM_*`。

第一次部署：

```bash
# 在伺服器上準備語料
ssh root@$SERVER_IP 'mkdir -p /root/levinrag/data /root/levinrag/corpus'
rsync -a corpus/ root@$SERVER_IP:/root/levinrag/corpus/

bb kamal setup      # 之後的更新用 bb kamal deploy
```

容器內沒有 `bb`，管理指令改在容器裡用 `java` 執行 uberjar 中的 namespace。以下寫法已在本機的 uberjar 上驗證可行：

```bash
J='java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -cp standalone.jar clojure.main -m'
bb kamal app exec -i "$J replware.levinrag.auth.cli user:create admin --admin"
bb kamal app exec -i "$J replware.levinrag.auth.cli user:passwd admin"
# 匯入語料：server 執行中請用 /admin 的按鈕或 POST /api/v1/ingest，不要在容器裡另外執行 ingest
```

部署後檢查：

```bash
curl -s https://$APP_DOMAIN/api/v1/health | jq
```

## 備份

只需要備份 `DATA_DIR/app.dtlv`。index 可以從語料重建，語料則由你們自己的版本控制或備份負責。

線上熱備份：server 不需要停機，因為 Datalevin 允許多個 process 同時讀取。本機已驗證：備份期間服務持續回應，備份檔可以正常開啟。下面的指令會讀取 `DATA_DIR`，並把備份寫到 `DATA_DIR/backups/app-<日期>`。

```bash
BACKUP='(require (quote [datalevin.core :as d])) (let [dir (or (System/getenv "DATA_DIR") "data") c (d/get-conn (str dir "/app.dtlv"))] (d/copy (d/db c) (str dir "/backups/app-" (java.time.LocalDate/now)) true) (d/close c)) (shutdown-agents)'

# 本機（專案目錄）
clojure -M:jvm-opts -e "$BACKUP"

# 正式環境容器內（沒有 clojure，改用 uberjar；備份會落在 volume 上的 /root/levinrag/data/backups）
bb kamal app exec "java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -cp standalone.jar clojure.main -e '$BACKUP'"
```

備份目錄仍在同一台機器上，請再另外複製到別處。uberjar 版本的指令已在本機驗證；透過 `kamal app exec` 執行的部分，與其他 Kamal 步驟一樣尚未在實機驗證。

還原：停止 server，用備份目錄取代 `DATA_DIR/app.dtlv`，再啟動 server。

## 重建索引

以下情況需要 `bb reindex`（刪除 `index.dtlv` 後完整重新匯入；帳號不受影響）。直接對 server 正在用的 `DATA_DIR` 執行時，**要先停止 server**：server 開著舊的索引檔，重建期間與之後都不會看到新索引，直到重新啟動為止。

- 更換 embedding 模型或維度；
- 更換斷詞 analyzer；
- 懷疑索引損壞。

**幾乎不停機的做法**：新索引建在另一個目錄，server 照常服務，最後只需要重啟一次（本機實測停機約 14 秒；重建本身的時間主要花在計算 embedding，期間 server 不受影響）。

```bash
# 1. server 照常執行；用同一份語料與模型設定，在另一個目錄建新索引
DATA_DIR=/path/to/rebuild bb reindex
# 2. 停止 server，換上新索引（舊的先保留）
mv "$DATA_DIR/index.dtlv" "$DATA_DIR/index.dtlv.old"
mv /path/to/rebuild/index.dtlv "$DATA_DIR/index.dtlv"
# 3. 啟動 server；確認查詢正常後再刪除 index.dtlv.old
```

平常新增或修改語料，用增量匯入即可：server 執行中用 `/admin` 的按鈕或 `POST /api/v1/ingest`；server 停止時才用 `bb ingest`。

## 評估

```bash
bb eval                                    # 五種檢索變體
bb eval --variants lexical,hybrid+rerank
bb eval --questions my-eval/questions.edn --users my-eval/users.edn   # 自己的語料
```

評估會讀取 `eval/questions.edn` 與 `eval/users.edn`（或以 `--questions`／`--users` 指定的檔案），對 `DATA_DIR/index.dtlv` 以各題指定的使用者身分直接執行檢索，輸出 recall@5、recall@10、MRR@10、ACL 洩漏數、degraded 題數，以及各階段的 p50／p95 延遲。結果寫入 `eval/results/<時間>.edn`。

- **ACL 洩漏大於 0 時，指令以非零狀態碼結束**。
- 有題目在降級狀態下執行時，會印出警告；這時的數字不代表完整 pipeline，先處理模型問題再比較。
- 更換 reranker 之後，重新校準 `VLLM_RERANK_MIN_SCORE`。
