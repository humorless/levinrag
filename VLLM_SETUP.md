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

## Local alternative: LM Studio (embedding and chat)

Without vLLM, you can use LM Studio to serve embedding and chat locally (OpenAI-compatible API, port 1234):

Once `.env` is filled in as below, `bb dev:models` starts this whole local recipe (the LM Studio server, both models, and the reranker in the next section) and skips whatever is already running; see the [Developer guide](docs/howto/dev.md). By hand:

```bash
lms get https://huggingface.co/ggml-org/bge-m3-Q8_0-GGUF   # about 600 MB, once
lms load text-embedding-bge-m3
lms server start
```

```bash
# .env
VLLM_EMBED_BASE_URL=http://localhost:1234/v1
VLLM_EMBED_MODEL=text-embedding-bge-m3
VLLM_API_KEY=lm-studio        # LM Studio does not check the key, but the client needs a value
```

- bge-m3 GGUF outputs 1024 dimensions, matching the `VLLM_EMBED_DIMS` default.
- **LM Studio has no rerank endpoint.** For a path that does not exist it returns **HTTP 200 + `{"error": ...}`**,
  so the rerank client must check the response structure (SPEC §4.2); the system then degrades to RRF ordering.

### Chat: LM Studio (Qwen3-8B)

```bash
lms load qwen/qwen3-8b --context-length 8192   # about 4.6 GB; the context must hold about 6000 tokens of data + 1024 output
```

```bash
# .env
VLLM_CHAT_BASE_URL=http://localhost:1234/v1
VLLM_CHAT_MODEL=qwen/qwen3-8b
VLLM_CHAT_EXTRA_BODY='{"reasoning_effort":"none"}'   # turn off Qwen3 thinking mode
```

- `VLLM_CHAT_EXTRA_BODY` is a JSON object merged as is into the chat request body (`:chat/extra-body` in SPEC §4.2).
- **LM Studio ignores `chat_template_kwargs.enable_thinking`** (the form vLLM uses); use
  `reasoning_effort: "none"` instead. The reasoning goes into a separate `reasoning_content` field, not into
  `<think>` inside `content`; when reasoning uses up `max_tokens`, `content` is an empty string, and `/ask` returns a fixed message and marks `:empty-answer` in the trace.
- Measured on an M1 16 GB (sample corpus, `特休天數怎麼計算？`): `/ask` end to end takes about 29 s with thinking on and about 9.4 s with it off;
  both produce a correct answer with citations.
- For Qwen3 on vLLM, use `VLLM_CHAT_EXTRA_BODY='{"chat_template_kwargs":{"enable_thinking":false}}'` instead.
- **Most of the generate time is spent reading the prompt (prefill)**: on an M1 16 GB, Qwen3-8B prefills at about 125 token/s and generates at about
  25 token/s (measured 2026-09-25: a 1389-token prompt took 14.5 s the first time; sending the same prompt again took only 3.3 s thanks to the prefix cache).
  So `/ask` latency is roughly proportional to the number of passages sent in the prompt, and the search component's `:rerank-min-score` (env `VLLM_RERANK_MIN_SCORE`) filtering out irrelevant passages directly shortens answer time.
  The first request after a long idle period or under memory pressure (swap) is noticeably slower.
- **`VLLM_RERANK_MIN_SCORE`** (default `-7.0`) is calibrated on the raw logits returned by llama.cpp; if the reranker backend returns scores in 0–1,
  it must be recalibrated (`docs/spikes/rerank-threshold.md`).

### Rerank: llama.cpp `llama-server`

LM Studio has no rerank, so use llama.cpp instead (`brew install llama.cpp`, no sudo needed):

```bash
llama-server -hf gpustack/bge-reranker-v2-m3-GGUF:Q8_0 --reranking \
  --port 8002 --host 127.0.0.1 -ub 8192 -b 8192 -c 8192 -np 1
```

```bash
# .env
VLLM_RERANK_BASE_URL=http://localhost:8002
VLLM_RERANK_PATH=/v1/rerank
VLLM_RERANK_MODEL=bge-reranker-v2-m3
```

- The model is about 636 MB, downloaded to `~/.cache/huggingface` on first start; at runtime it uses about 1.1 GB of memory.
- The response format is the same as SPEC §4.2 (`results[i] = {index, relevance_score}`, sorted by score).
- **Scores are logits without sigmoid** (e.g. 4.6, −6.5), not 0–1. When setting the search component's `:rerank-min-score` (env `VLLM_RERANK_MIN_SCORE`), calibrate against the actual backend.
- Measured on an M1 16 GB: 40 chunks (about 6–7k estimated tokens) take about 2.1 s; 20 take about 0.9–1.0 s.
