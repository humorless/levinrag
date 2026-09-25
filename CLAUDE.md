# LevinRAG — project notes for Claude Code

> **Evaluating LevinRAG (running it on a corpus, checking its claims), not developing it?** Follow `docs/howto/quick-start.md` (Traditional Chinese: `docs/howto/quick-start.zh-TW.md`) and use only the `bb` commands it lists; nothing below is needed.

## Starting the environment

Models and servers down (e.g. after a reboot)? Run `bb dev:models` (the three local llama.cpp model servers) and then `bb dev:up` (`bb serve` in tmux `levinrag`, the nREPL below in tmux `nrepl`, then `bb doctor`). Both start only what is not running. Do not assemble `llama-server` / tmux commands by hand (their flags are tuned in `bb dev:models`); settings come from `.env`. See `docs/howto/dev.md`.

## nREPL

Start with (overrides the generic command in `~/.claude/CLAUDE.md`):

```bash
tmux kill-session -t nrepl 2>/dev/null || true
echo 1667 > .nrepl-port
tmux new-session -d -s nrepl 'clojure -M:jvm-opts:test:dev:nrepl --port 1667'
```

- `:jvm-opts` — Datalevin 1.1.0 needs the `--add-opens` flags on every JVM (see `deps.edn`, `docs/decisions.md`).
- `:test` — puts `test/` on the classpath so test namespaces can be required and run in the REPL.
- `:dev` — `dev/` + integrant-repl.

## Tests

- Fast loop (nREPL): `(require 'ns 'ns-test :reload)` then `(binding [clojure.test/*test-out* *out*] (clojure.test/run-tests 'ns-test))`.
  Reload the source ns too; `remove-ns` a test ns after renaming/deleting deftests, then require it **with `:reload`** (the ns stays in `*loaded-libs*`, so a plain `require` silently does nothing).
- Before committing (clean JVM + coverage): `clojure -X:jvm-opts:test`
- Lint: `clj-kondo --lint src test bb`

## Docs in two languages

- README, SPEC, VLLM_SETUP, `docs/howto/*` (including quick-start), `docs/design/rationale` exist as `X.md` (English, authoritative) and `X.zh-TW.md` (Traditional Chinese translation).
- Change one, change the other in the same commit; keep headings identical (other docs link to their anchors). Run `bb docs:check` before committing doc changes.
- Single-language on purpose: `docs/decisions.md`, `docs/backlog.md`, spikes, plans, handoffs (English); `docs/design/2026-09-22-initial-spec.md` (Chinese, frozen).
