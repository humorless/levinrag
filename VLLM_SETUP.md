# vLLM setup guide

English | [繁體中文](VLLM_SETUP.zh-TW.md)

This project needs three vLLM endpoints to run:

## Environment variables

Put the settings in `.env` in the project directory (copy `.env.example`); every `bb` task that runs the app reads it, and a variable exported in the shell takes precedence:

### Option 1: set each one separately (recommended)

```bash
# Embedding service
VLLM_EMBED_BASE_URL="http://your-vllm-host/v1"
VLLM_EMBED_MODEL="your-embedding-model"
VLLM_EMBED_API_KEY="your-api-key-here"

# Rerank service
VLLM_RERANK_BASE_URL="http://your-vllm-host/v1"
VLLM_RERANK_PATH="/v1/rerank"  # or "/rerank", depending on the vLLM deployment
VLLM_RERANK_MODEL="your-reranker-model"
VLLM_RERANK_API_KEY="your-api-key-here"

# Chat service (required)
VLLM_CHAT_BASE_URL="http://your-vllm-host/v1"
VLLM_CHAT_MODEL="your-chat-model"
VLLM_CHAT_API_KEY="your-api-key-here"
```

### Option 2: a single API key (fallback)

```bash
VLLM_API_KEY="your-api-key-here"
VLLM_EMBED_BASE_URL="http://your-vllm-host/v1"
VLLM_RERANK_BASE_URL="http://your-vllm-host/v1"
VLLM_CHAT_BASE_URL="http://your-vllm-host/v1"
```

## Default models

Following the design in SPEC.md, these models are used by default:

| Purpose | Model ID (default) | Notes |
|------|----------------|------|
| Embedding | `BAAI/bge-m3` | 1024 dimensions, multilingual, good at Chinese |
| Rerank | `BAAI/bge-reranker-v2-m3` | Multilingual cross-encoder |
| Chat | (any available model) | No default; must be set |

## Testing the endpoints

### Test embedding

```bash
curl -X POST http://localhost:8001/v1/embeddings \
  -H "Authorization: Bearer $VLLM_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model": "your-model", "input": ["測試"]}'
```

### Test rerank

```bash
curl -X POST http://localhost:8002/v1/rerank \
  -H "Authorization: Bearer $VLLM_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model": "your-model", "query": "測試", "documents": ["候選一", "候選二"], "top_n": 2}'
```

### Test chat

```bash
curl -X POST http://localhost:8003/v1/chat/completions \
  -H "Authorization: Bearer $VLLM_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model": "your-model", "messages": [{"role": "user", "content": "你好"}], "temperature": 0.0, "max_tokens": 16}'
```

## Verifying the setup

After filling in `.env`, run (or `bb doctor`, which runs it after checking everything else):

```bash
bb vllm:check
```

You should see:

```
[OK]   embed
[OK]   rerank
[OK]   rerank-long
[OK]   chat
```

`rerank-long` probes the reranker with a 1500-character document (SPEC §4.3).

## Notes

- **Do not commit API keys to git** (including in this file)
- If the vLLM server is already running, no extra setup is needed
- The first run downloads the models automatically (about 2-16GB)
- If the `/v1/rerank` path is wrong, try `/rerank` instead

## Local alternative: llama.cpp (embedding, chat and rerank)

Without vLLM, three llama.cpp `llama-server` processes serve the three endpoints on a Mac (`brew install llama.cpp`, no sudo needed). **`bb dev:models` starts them** (one tmux session each: `embed`, `chat`, `rerank`), with the flags below, and skips whatever is already running; see the [Developer guide](docs/howto/dev.md). The models download into `~/.cache/huggingface` on first start (about 600 MB, 5 GB and 640 MB).

```bash
# .env
VLLM_API_KEY=local            # llama-server does not check the key, but the client needs a value
VLLM_EMBED_BASE_URL=http://localhost:8001/v1
VLLM_EMBED_MODEL=bge-m3
VLLM_CHAT_BASE_URL=http://localhost:8003/v1
VLLM_CHAT_MODEL=qwen3-8b
VLLM_RERANK_BASE_URL=http://localhost:8002
VLLM_RERANK_PATH=/v1/rerank
VLLM_RERANK_MODEL=bge-reranker-v2-m3
```

What `bb dev:models` runs, and why each flag (measured in [docs/spikes/llama-cpp-only.md](docs/spikes/llama-cpp-only.md)):

```bash
llama-server -hf ggml-org/bge-m3-Q8_0-GGUF --port 8001 --host 127.0.0.1 -a bge-m3 -np 1 \
  --embedding -c 2048 -b 2048 -ub 2048
llama-server -hf Qwen/Qwen3-8B-GGUF:Q4_K_M --port 8003 --host 127.0.0.1 -a qwen3-8b -np 1 \
  -c 8192 --reasoning off --top-k 20 --top-p 0.8 --min-p 0
llama-server -hf gpustack/bge-reranker-v2-m3-GGUF:Q8_0 --port 8002 --host 127.0.0.1 -a bge-reranker-v2-m3 -np 1 \
  --reranking -c 8192 -b 8192 -ub 8192
```

| Flag | Why |
|---|---|
| `-np 1` (all) | One slot for one developer; more slots split `-c` between them |
| `-a <name>` (all) | The server answers to the model name in `.env` |
| `-ngl`, `-fa` not set | Their default `auto` puts every layer on the Metal GPU |
| embed `-c/-b/-ub 2048` | A non-causal model needs each input in one ubatch; chunks are capped at about 500 estimated tokens. 8192 cost 8 GB for nothing |
| embed pooling not set | The model's own pooling; the vectors equal LM Studio's (cosine 1.0) |
| chat `-c 8192` | About 6000 tokens of passages + 1024 output |
| chat `--reasoning off` | Turns off Qwen3's thinking server-side, so no `VLLM_CHAT_EXTRA_BODY` is needed |
| chat `--top-k 20 --top-p 0.8 --min-p 0` | Qwen's non-thinking sampling; the app sends `temperature` 0.2 itself. No presence penalty: answers are short and must repeat numbers and `[n]` citations |
| rerank `-c/-b/-ub 8192` | A full chunk plus the query fits (`bb vllm:check`'s `rerank-long`) |

Change a model with `LOCAL_EMBED_HF`, `LOCAL_CHAT_HF`, `LOCAL_RERANK_HF` and the chat context with `LOCAL_CHAT_CONTEXT` in `.env`; a new embedding model also needs `VLLM_EMBED_DIMS` and `bb reindex`.

### Embedding: bge-m3

- The GGUF outputs 1024 dimensions, matching the `VLLM_EMBED_DIMS` default.
- Throughput on an M1 16 GB: 206 chunk-sized paragraphs in about 3.8 s (batches of 32, as ingest sends them).

### Chat: Qwen3-8B

- Measured on an M1 16 GB (2026-09-26): prefill about 210 token/s, generation about 22 token/s; `/ask` p50 about 6.4 s on the first 10 sample questions.
- **Most of the answer time is spent reading the prompt (prefill)**, so `/ask` latency is roughly proportional to the number of passages sent, and the search component's `:rerank-min-score` (env `VLLM_RERANK_MIN_SCORE`) filtering out irrelevant passages directly shortens it. Sending the same prompt again starts answering in about 0.06 s (prompt cache).
- For Qwen3 on vLLM, turn thinking off with `VLLM_CHAT_EXTRA_BODY='{"chat_template_kwargs":{"enable_thinking":false}}'`; `VLLM_CHAT_EXTRA_BODY` is a JSON object merged as is into the chat request body (`:chat/extra-body` in SPEC §4.2).
- The first request after a long idle period or under memory pressure (swap) is noticeably slower.

### Rerank: bge-reranker-v2-m3

- At runtime it uses about 1.3 GB of memory. The response format is the same as SPEC §4.2 (`results[i] = {index, relevance_score}`, sorted by score).
- **Scores are logits without sigmoid** (e.g. 4.6, −6.5), not 0–1. **`VLLM_RERANK_MIN_SCORE`** (default `-7.0`) is calibrated on these; a backend returning 0–1 scores must be recalibrated (`docs/spikes/rerank-threshold.md`).
- Measured on an M1 16 GB: 40 chunks (about 6–7k estimated tokens) take about 2.1 s; 20 take about 0.9–1.0 s.

### Using LM Studio instead

LM Studio also serves embedding and chat (OpenAI-compatible, port 1234); it was this project's first local setup and gave the same retrieval results, but a slower `/ask` (p50 about 9 s: Qwen3 on MLX prefills at about 120 token/s) and more memory. `bb dev:models` does not manage it. Notes if you use it:

- **LM Studio has no rerank endpoint** (keep the llama.cpp reranker). For a path that does not exist it returns **HTTP 200 + `{"error": ...}`**, so the rerank client checks the response structure (SPEC §4.2).
- It speaks HTTP/1.1 only.
- Turn Qwen3 thinking off with `VLLM_CHAT_EXTRA_BODY='{"reasoning_effort":"none"}'`: **LM Studio ignores `chat_template_kwargs.enable_thinking`**. Its reasoning goes into a separate `reasoning_content` field; when reasoning uses up `max_tokens`, `content` is empty, and `/ask` returns a fixed message and marks `:empty-answer` in the trace.
