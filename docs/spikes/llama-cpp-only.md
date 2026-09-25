# Spike: a llama.cpp-only Mac dev environment (no LM Studio)

Date: 2026-09-26. Machine: M1, 16 GB. Code: `dev/spikes/backend_bench.clj`
(chat and embedding benchmark against the `VLLM_*` endpoints),
`dev/spikes/ask_latency.clj` (`/ask` through the running server).
llama.cpp build 11146 (`llama-server --version`), LM Studio with its MLX
engine for Qwen3.

## Why

The documented local setup used two tools: LM Studio for embedding and
chat, llama.cpp `llama-server` for rerank. The user asked whether
llama.cpp alone would do, to make the development environment one
`brew install` and one kind of process — but only if performance holds.

Decision rule, set by the user before measuring: switch if **all** hold —
`/ask` p50 within 20 %, eval metrics unchanged, and the three models'
memory within 15 % of the LM Studio setup.

## What LM Studio was running

`lms ps --json`: the embedding model is a GGUF
(`ggml-org/bge-m3-Q8_0-GGUF`, i.e. llama.cpp's engine inside LM Studio),
the chat model `qwen/qwen3-8b` is **MLX** (safetensors, 4-bit), context
8192, 4 parallel slots.

## Method

- **Chat** (`backend_bench.clj`): one streamed request per run with the
  leave policy plus other sample paragraphs as context (2301 prompt
  tokens), asking for a list of the leave rules, `max_tokens` 256,
  temperature 0. Time to first token ≈ prefill; the rest ≈ generation.
  A warm-up, then 3 cold runs (a unique first line defeats the prefix
  cache), then 3 repeats of one prompt (prefix cache).
- **Embedding**: 206 paragraphs of the sample corpus up to 1200
  characters (chunk-sized; one 8k-token block is excluded because ingest
  never sends it whole), batches of 32 as ingest does; median of 3
  rounds. The first 20 vectors compared by cosine.
- **`/ask`** (`ask_latency.clj`): the first 10 non-ACL questions of
  `eval/questions.edn` through `POST /api/v1/ask` with an admin token.
- **Eval**: `bb eval` (5 variants) against the unchanged `./data` index.
- **Memory**: macOS `footprint` of each model process after the
  benchmark. MLX holds the weights in its footprint; llama.cpp mmaps the
  model file by default and those pages are not counted, so llama.cpp was
  measured with `--load-mode none` (weights loaded into memory).
- Only one backend loaded at a time (LM Studio unloaded and its server
  stopped before llama.cpp started): no swapping.

## Results

| | LM Studio (MLX chat) | llama.cpp |
|---|---|---|
| Chat prefill (2301 tokens) | 120 tok/s, first token 19.2 s | **210 tok/s**, first token 11.0 s |
| Chat generation | 22.7 tok/s | 22.1 tok/s |
| Cold request total | 30.3 s | **22.5 s** |
| Same prompt again: first token | 0.52 s | **0.06 s** |
| Embedding, 206 paragraphs | 3.88 s | 3.81 s |
| Embedding vectors (20 paragraphs) | — | cosine min 1.000000 vs LM Studio |
| **`/ask` p50** (10 questions) | 9.04 s | **6.43 s (−29 %)**; 6.19 s with Qwen's sampling; 6.40 s via `bb dev:models` |
| **Eval** (5 variants) | baseline | **identical**, down to every question's ranking |
| **Memory**, embed + chat + rerank | 4276 + 6567 (+ 54) + 1303 ≈ **12.2 GB** | 985 + 7490 + 1302 ≈ **9.8 GB (−20 %)** |
| Answers | correct, cited | same facts and citations; no thinking output |

All three conditions hold, so the dev setup switched to llama.cpp only
(`bb dev:models`, VLLM_SETUP.md, `.env.example`). The index needed no
rebuild: the embedding vectors are identical.

## Parameters (built into `bb dev:models`)

llama.cpp's defaults are generic, so each role gets its own flags. Sources:
the [llama-server README](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md),
the [Qwen3-8B-GGUF model card](https://huggingface.co/Qwen/Qwen3-8B-GGUF),
LM Studio's load settings, and the measurements above.

- **All**: `-np 1` (one developer; slots split `-c`, and LM Studio's 4
  slots are not needed); `-a <model name>`; `-ngl` / `-fa` left at `auto`
  (all layers on Metal); weights mmapped (the default: fast restarts; the
  resident memory is the same ~9.8 GB).
- **Embedding**: `--embedding`, model-default pooling (vectors equal LM
  Studio's), `-c 2048 -b 2048 -ub 2048`. The first try copied the
  reranker's 8192 and 4 automatic slots: **8.3 GB** for a 600 MB model.
  A non-causal model needs each input in one ubatch; the chunker caps
  chunks at ~500 estimated tokens, so 2048 leaves 4× headroom (985 MB).
- **Chat**: `-c 8192` (≈ 6000 tokens of passages + 1024 output);
  `--reasoning off` replaces `VLLM_CHAT_EXTRA_BODY` (no thinking in the
  output); Qwen's non-thinking sampling `--top-k 20 --top-p 0.8 --min-p 0`
  instead of llama-server's 40 / 0.95 / 0.05 — same answers, p50 6.19 s.
  Temperature stays the app's 0.2 (sent per request). Qwen recommends
  `presence_penalty` 1.5 for quantized models to curb repetition in long
  generations; not set here: answers are short and must repeat numbers
  and `[n]` citations — revisit if answers start looping.
- **Rerank**: unchanged (`--reranking -c/-b/-ub 8192`), validated earlier
  by `bb vllm:check`'s `rerank-long`. Its 1.3 GB could likely shrink with
  a smaller ubatch; not measured.

## Not done

- **Router mode** (`--models-preset`, one process for all three models):
  not tried. The README does not document the preset format, the three
  roles need different flags anyway, and three tmux sessions can be
  inspected and restarted one by one.
- Answer quality beyond the 10 `/ask` answers read by eye.
- Other Macs / Linux: only this M1 16 GB.
