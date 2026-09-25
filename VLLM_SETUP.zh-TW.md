# vLLM 設定指南

[English](VLLM_SETUP.md) | 繁體中文

> 本文為英文版的翻譯；內容不一致時，以英文版為準。

本專案需要三個 vLLM endpoints 才能運作：

## 環境變數設定

把設定寫在專案目錄的 `.env`（從 `.env.example` 複製）；每個會執行應用程式的 `bb` 指令都會讀它，而 shell 裡 export 的變數優先：

### 方式一：個別設定（推薦）

```bash
# Embedding service
VLLM_EMBED_BASE_URL="http://your-vllm-host/v1"
VLLM_EMBED_MODEL="your-embedding-model"
VLLM_EMBED_API_KEY="your-api-key-here"

# Rerank service
VLLM_RERANK_BASE_URL="http://your-vllm-host/v1"
VLLM_RERANK_PATH="/v1/rerank"  # 或 "/rerank"，取決於 vLLM 部署
VLLM_RERANK_MODEL="your-reranker-model"
VLLM_RERANK_API_KEY="your-api-key-here"

# Chat service（必填）
VLLM_CHAT_BASE_URL="http://your-vllm-host/v1"
VLLM_CHAT_MODEL="your-chat-model"
VLLM_CHAT_API_KEY="your-api-key-here"
```

### 方式二：使用單一 API key（fallback）

```bash
VLLM_API_KEY="your-api-key-here"
VLLM_EMBED_BASE_URL="http://your-vllm-host/v1"
VLLM_RERANK_BASE_URL="http://your-vllm-host/v1"
VLLM_CHAT_BASE_URL="http://your-vllm-host/v1"
```

## 預設模型對應

根據 SPEC.md 的設計，預設使用以下模型：

| 用途 | Model ID (預設) | 說明 |
|------|----------------|------|
| Embedding | `BAAI/bge-m3` | 1024 維，多語，中文佳 |
| Rerank | `BAAI/bge-reranker-v2-m3` | 多語 cross-encoder |
| Chat | (任一可用模型) | 無預設值，必須設定 |

## 測試 endpoints

### 測試 embedding

```bash
curl -X POST http://localhost:8001/v1/embeddings \
  -H "Authorization: Bearer $VLLM_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model": "your-model", "input": ["測試"]}'
```

### 測試 rerank

```bash
curl -X POST http://localhost:8002/v1/rerank \
  -H "Authorization: Bearer $VLLM_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model": "your-model", "query": "測試", "documents": ["候選一", "候選二"], "top_n": 2}'
```

### 測試 chat

```bash
curl -X POST http://localhost:8003/v1/chat/completions \
  -H "Authorization: Bearer $VLLM_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model": "your-model", "messages": [{"role": "user", "content": "你好"}], "temperature": 0.0, "max_tokens": 16}'
```

## 驗證設定

填好 `.env` 後，執行（或 `bb doctor`，它會先檢查其他項目再執行這一步）：

```bash
bb vllm:check
```

應該會看到：

```
[OK]   embed
[OK]   rerank
[OK]   rerank-long
[OK]   chat
```

`rerank-long` 以一份 1500 字元的文件探測 reranker（SPEC §4.3）。

## 注意事項

- **不要 commit API key 到 git**（包括這個文件）
- 如果 vLLM server 已經在運行，不需要額外設定
- 第一次使用會自動下載模型（約 2-16GB）
- 如果 `/v1/rerank` 路徑錯誤，嘗試改為 `/rerank`

## 本機替代：llama.cpp（embedding、chat 與 rerank）

沒有 vLLM 時，在 Mac 上用三個 llama.cpp `llama-server` 提供三個端點（`brew install llama.cpp`，不需要 sudo）。**`bb dev:models` 會啟動它們**（各自一個 tmux session：`embed`、`chat`、`rerank`），使用下面的參數，已在執行的部分會跳過；見[開發者指南](docs/howto/dev.zh-TW.md)。模型會在第一次啟動時下載到 `~/.cache/huggingface`（約 600 MB、5 GB、640 MB）。

```bash
# .env
VLLM_API_KEY=local            # llama-server 不檢查 key，但 client 需要一個值
VLLM_EMBED_BASE_URL=http://localhost:8001/v1
VLLM_EMBED_MODEL=bge-m3
VLLM_CHAT_BASE_URL=http://localhost:8003/v1
VLLM_CHAT_MODEL=qwen3-8b
VLLM_RERANK_BASE_URL=http://localhost:8002
VLLM_RERANK_PATH=/v1/rerank
VLLM_RERANK_MODEL=bge-reranker-v2-m3
```

`bb dev:models` 實際執行的指令，以及每個參數的理由（量測見 [docs/spikes/llama-cpp-only.md](docs/spikes/llama-cpp-only.md)）：

```bash
llama-server -hf ggml-org/bge-m3-Q8_0-GGUF --port 8001 --host 127.0.0.1 -a bge-m3 -np 1 \
  --embedding -c 2048 -b 2048 -ub 2048
llama-server -hf Qwen/Qwen3-8B-GGUF:Q4_K_M --port 8003 --host 127.0.0.1 -a qwen3-8b -np 1 \
  -c 8192 --reasoning off --top-k 20 --top-p 0.8 --min-p 0
llama-server -hf gpustack/bge-reranker-v2-m3-GGUF:Q8_0 --port 8002 --host 127.0.0.1 -a bge-reranker-v2-m3 -np 1 \
  --reranking -c 8192 -b 8192 -ub 8192
```

| 參數 | 理由 |
|---|---|
| `-np 1`（全部） | 一位開發者用一個 slot 就好；多個 slot 會平分 `-c` |
| `-a <名稱>`（全部） | 讓 server 回應 `.env` 裡的模型名稱 |
| 不設 `-ngl`、`-fa` | 預設的 `auto` 會把所有層放到 Metal GPU |
| embed `-c/-b/-ub 2048` | 非因果模型的每筆輸入必須放進一個 ubatch；chunk 上限約 500 個估計 token。用 8192 會白白多占 8 GB |
| embed 不設 pooling | 使用模型內建的 pooling；向量和 LM Studio 的完全相同（cosine 1.0） |
| chat `-c 8192` | 約 6000 token 的段落＋1024 的輸出 |
| chat `--reasoning off` | 在伺服器端關掉 Qwen3 的 thinking，所以不需要 `VLLM_CHAT_EXTRA_BODY` |
| chat `--top-k 20 --top-p 0.8 --min-p 0` | Qwen 對 non-thinking 模式的取樣建議；`temperature` 由程式自己送 0.2。不設 presence penalty：回答很短，而且要原樣重複數字與 `[n]` 引用 |
| rerank `-c/-b/-ub 8192` | 放得下一整個 chunk 加上查詢（`bb vllm:check` 的 `rerank-long`） |

要換模型，在 `.env` 設 `LOCAL_EMBED_HF`、`LOCAL_CHAT_HF`、`LOCAL_RERANK_HF`；chat 的 context 用 `LOCAL_CHAT_CONTEXT`。換 embedding 模型時，還要改 `VLLM_EMBED_DIMS` 並執行 `bb reindex`。

### Embedding：bge-m3

- GGUF 輸出 1024 維，與 `VLLM_EMBED_DIMS` 的預設值一致。
- M1 16 GB 上的吞吐量：206 段 chunk 大小的段落約 3.8 秒（和 ingest 一樣，每批 32 筆）。

### Chat：Qwen3-8B

- M1 16 GB 實測（2026-09-26）：prefill 約 210 token/s，生成約 22 token/s；前 10 題範例問題的 `/ask` p50 約 6.4 秒。
- **回答時間大部分花在讀 prompt（prefill）**，所以 `/ask` 延遲大致和送進去的段落數成正比；search 元件的 `:rerank-min-score`（env `VLLM_RERANK_MIN_SCORE`）濾掉不相關的段落，能直接縮短時間。同一個 prompt 再送一次，約 0.06 秒就開始回答（prompt 快取）。
- 在 vLLM 上跑 Qwen3 時，用 `VLLM_CHAT_EXTRA_BODY='{"chat_template_kwargs":{"enable_thinking":false}}'` 關掉 thinking；`VLLM_CHAT_EXTRA_BODY` 是一個 JSON 物件，會原樣合併進 chat 請求（SPEC §4.2 的 `:chat/extra-body`）。
- 閒置很久之後的第一個請求，或在記憶體吃緊（swap）時，會明顯比較慢。

### Rerank：bge-reranker-v2-m3

- 執行時約占 1.3 GB 記憶體。回應格式和 SPEC §4.2 相同（`results[i] = {index, relevance_score}`，依分數排序）。
- **分數是沒有經過 sigmoid 的 logit**（例如 4.6、−6.5），不是 0–1。**`VLLM_RERANK_MIN_SCORE`**（預設 `-7.0`）就是用這種分數校準的；回傳 0–1 分數的後端必須重新校準（`docs/spikes/rerank-threshold.md`）。
- M1 16 GB 實測：40 個 chunk（約 6–7k 估計 token）約 2.1 秒；20 個約 0.9–1.0 秒。

### 改用 LM Studio

LM Studio 也能提供 embedding 與 chat（OpenAI 相容，port 1234）。它是本專案最早的本機方案，檢索結果相同，但 `/ask` 比較慢（p50 約 9 秒：Qwen3 在 MLX 上的 prefill 約 120 token/s），記憶體也用得比較多。`bb dev:models` 不管理它。如果要用：

- **LM Studio 沒有 rerank 端點**（reranker 仍用 llama.cpp）。對不存在的路徑，它回 **HTTP 200 + `{"error": ...}`**，所以 rerank client 會檢查回應結構（SPEC §4.2）。
- 它只支援 HTTP/1.1。
- 用 `VLLM_CHAT_EXTRA_BODY='{"reasoning_effort":"none"}'` 關掉 Qwen3 的 thinking：**LM Studio 會忽略 `chat_template_kwargs.enable_thinking`**。它的推理內容放在另一個 `reasoning_content` 欄位；推理用光 `max_tokens` 時 `content` 會是空的，`/ask` 會回固定訊息並在 trace 標記 `:empty-answer`。
