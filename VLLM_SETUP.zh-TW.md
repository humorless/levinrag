# vLLM 設定指南

本專案需要三個 vLLM endpoints 才能運作：

## 環境變數設定

在啟動應用程式前，設定以下環境變數：

### 方式一：個別設定（推薦）

```bash
# Embedding service
export VLLM_EMBED_BASE_URL="http://your-vllm-host/v1"
export VLLM_EMBED_MODEL="your-embedding-model"
export VLLM_EMBED_API_KEY="your-api-key-here"

# Rerank service
export VLLM_RERANK_BASE_URL="http://your-vllm-host/v1"
export VLLM_RERANK_PATH="/v1/rerank"  # 或 "/rerank"，取決於 vLLM 部署
export VLLM_RERANK_MODEL="your-reranker-model"
export VLLM_RERANK_API_KEY="your-api-key-here"

# Chat service（必填）
export VLLM_CHAT_BASE_URL="http://your-vllm-host/v1"
export VLLM_CHAT_MODEL="your-chat-model"
export VLLM_CHAT_API_KEY="your-api-key-here"
```

### 方式二：使用單一 API key（fallback）

```bash
export VLLM_API_KEY="your-api-key-here"
export VLLM_EMBED_BASE_URL="http://your-vllm-host/v1"
export VLLM_RERANK_BASE_URL="http://your-vllm-host/v1"
export VLLM_CHAT_BASE_URL="http://your-vllm-host/v1"
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

設定好環境變數後，執行：

```bash
bb vllm:check
```

應該會看到：

```
[OK]   embed
[OK]   rerank
[OK]   chat
```

## 注意事項

- **不要 commit API key 到 git**（包括這個文件）
- 如果 vLLM server 已經在運行，不需要額外設定
- 第一次使用會自動下載模型（約 2-16GB）
- 如果 `/v1/rerank` 路徑錯誤，嘗試改為 `/rerank`
- 如果需要更詳細的 vLLM 啟動指令，請參考 `docs/vllm.md`（如果有的話）
## 本機替代：LM Studio（embedding 與 chat）

沒有 vLLM 時，可以用 LM Studio 在本機提供 embedding 與 chat（OpenAI 相容 API，port 1234）：

```bash
lms get https://huggingface.co/ggml-org/bge-m3-Q8_0-GGUF   # 約 600 MB
lms load text-embedding-bge-m3
lms server start

export VLLM_EMBED_BASE_URL=http://localhost:1234/v1
export VLLM_EMBED_MODEL=text-embedding-bge-m3
export VLLM_API_KEY=lm-studio        # LM Studio 不檢查 key，但 client 需要一個值
```

- bge-m3 GGUF 輸出 1024 維，與 `VLLM_EMBED_DIMS` 預設一致。
- **LM Studio 沒有 rerank endpoint。** 對不存在的路徑它會回 **HTTP 200 + `{"error": ...}`**，
  rerank client 必須檢查回應結構（SPEC §4.2），系統會降級為 RRF 排序。

### Chat：LM Studio（Qwen3-8B）

```bash
lms load qwen/qwen3-8b --context-length 8192   # 約 4.6 GB；context 需容納約 6000 token 的資料 + 1024 輸出

export VLLM_CHAT_BASE_URL=http://localhost:1234/v1
export VLLM_CHAT_MODEL=qwen/qwen3-8b
export VLLM_CHAT_EXTRA_BODY='{"reasoning_effort":"none"}'   # 關閉 Qwen3 的思考模式
```

- `VLLM_CHAT_EXTRA_BODY` 是 JSON 物件，原樣合併進 chat request body（SPEC §4.2 的 `:chat/extra-body`）。
- **LM Studio 不理會 `chat_template_kwargs.enable_thinking`**（vLLM 用的寫法）；要用
  `reasoning_effort: "none"`。思考內容放在獨立的 `reasoning_content` 欄位，不在 `content` 裡的
  `<think>`；思考吃光 `max_tokens` 時 `content` 為空字串，`/ask` 會回固定訊息並在 trace 標 `:empty-answer`。
- M1 16 GB 實測（樣本語料、`特休天數怎麼計算？`）：`/ask` 全程思考開啟約 29 s，關閉約 9.4 s，
  兩者都得到帶引用的正確回答。
- vLLM 上的 Qwen3 則用 `VLLM_CHAT_EXTRA_BODY='{"chat_template_kwargs":{"enable_thinking":false}}'`。
- **generate 的時間主要花在讀 prompt（prefill）**：M1 16 GB 上 Qwen3-8B prefill 約 125 token/s、生成約
  25 token/s（2026-09-25 實測：1389 token 的 prompt 首次 14.5 s，同一 prompt 再送一次因前綴快取只要 3.3 s）。
  所以 `/ask` 的延遲大致與送進 prompt 的段落數成正比，`:retrieve/rerank-min-score` 濾掉不相關段落可直接縮短回答時間。
- **`VLLM_RERANK_MIN_SCORE`**（預設 `-7.0`）是依 llama.cpp 回傳的原始 logit 校準的；若 reranker 後端回傳 0–1 的分數，
  需重新校準（`docs/spikes/rerank-threshold.md`）。
  長時間閒置或記憶體吃緊（swap）後的第一次請求會明顯更慢。

### Rerank：llama.cpp `llama-server`

LM Studio 沒有 rerank，改用 llama.cpp（`brew install llama.cpp`，不需要 sudo）：

```bash
llama-server -hf gpustack/bge-reranker-v2-m3-GGUF:Q8_0 --reranking \
  --port 8002 --host 127.0.0.1 -ub 8192 -b 8192 -c 8192 -np 1

export VLLM_RERANK_BASE_URL=http://localhost:8002
export VLLM_RERANK_PATH=/v1/rerank
export VLLM_RERANK_MODEL=bge-reranker-v2-m3
```

- 模型約 636 MB，首次啟動時下載到 `~/.cache/huggingface`；執行時約佔 1.1 GB 記憶體。
- 回應格式與 SPEC §4.2 相同（`results[i] = {index, relevance_score}`，依分數排序）。
- **分數是未經 sigmoid 的 logit**（例如 4.6、−6.5），不是 0–1。設定 `:retrieve/rerank-min-score` 時要以實際後端校準。
- M1 16 GB 實測：40 個 chunk（約 6–7k 估算 tokens）約 2.1 秒；20 個約 0.9–1.0 秒。
