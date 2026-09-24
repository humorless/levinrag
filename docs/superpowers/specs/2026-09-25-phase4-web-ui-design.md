# Phase 4 Web UI — design

Date: 2026-09-25. Status: approved in conversation; supplements SPEC.md
§11–§14 and §17 Phase 4 (those remain the authority; this file fixes what
they leave open).

## Goal

A browser UI where a logged-in user asks questions, reads the cited
sources in context, and — through the Debug panel — sees how retrieval
ranked every candidate (SPEC §12: "the most important learning interface
of this MVP"); plus an admin page to run ingestion and browse traces.

Phase 4 ACs (SPEC §17): no JS errors; not logged in → `/login`; Debug
panel values match the trace; a document the user cannot read → 404.

## Decisions

- **UI language**: Traditional Chinese. **Styling**: plain Tailwind v4
  (standalone CLI already in the repo), no DaisyUI, no npm in the build.
- **Server-rendered pages + HTMX**. Web routes authenticate with the
  session cookie (+ CSRF, SPEC §12) and call `answer/ask!` /
  `pipeline/search` directly with the session principal. The browser
  never holds an API token. `/api/v1/*` is unchanged and shares the same
  functions, not HTTP calls.
- **Session** stores only the username; the principal (groups, admin?) is
  re-read from `app.dtlv` on each request, so `bb user:groups` applies
  immediately. Cookie: `HttpOnly`, `SameSite=Lax` always; `Secure` only in
  the `:prod` profile (a Secure cookie is never sent back over
  `http://localhost`).
- **Not logged in**: page requests → 302 `/login`; HTMX requests
  (`HX-Request` header) → 200 with `HX-Redirect: /login`.
- **Hidden, not forbidden**: a document the user cannot read and every
  admin page for a non-admin return 404 (SPEC §11's "no existence leak").

## Pages and routes

| Route | Behavior |
|---|---|
| `GET /login`, `POST /login` | Username/password form; `users/authenticate`. Failure re-renders the form with a generic message (no hint which field was wrong). Success → redirect to `/` (or the page that sent the user to login, same-origin paths only). |
| `POST /logout` | Clears the session → `/login`. |
| `GET /` | Q&A page: query box, Debug switch, empty result area. |
| `POST /ask` (HTMX) | Returns the result fragment: answer with `[n]` rendered as links that scroll to source n; sources panel (title, section trail, excerpt, "開啟文件" → `/docs/<path>?chunk=<first chunk id>`); Debug panel when the switch is on; no-evidence and degraded notices. |
| `GET /docs/*path` | Document viewer (below). 404 when unreadable or unknown. |
| `GET /admin` | Admin only: "執行增量 ingest" button, current/last job status, last report summary, index lag, last 50 traces (time, user, kind, query, link). |
| `POST /admin/ingest` (HTMX) | Starts a job or shows "已有 ingest 在執行" (409 semantics). Status fragment polls `GET /admin/ingest/status` every 2 s while running. |
| `GET /admin/traces/:id` | Trace detail (stages as a readable table). |

API endpoints from SPEC §11 built in this phase, sharing the same code:
`POST /api/v1/ingest` (admin; 409 on conflict), `GET /api/v1/ingest/:job_id`
(admin), `GET /api/v1/traces/:id` (admin or trace owner, else 404),
`GET /api/v1/docs/*path` (metadata + ACL-visible chunk list; 404 when
unreadable).

## Mechanics

**Debug panel = the trace.** `POST /ask` writes the trace, then renders
the panel from the stored trace (`trace/fetch`) plus the response's full
candidate list. Columns (SPEC §12): chunk id, lexical rank, semantic rank,
RRF, graph mark, rerank score, selected; below it per-stage ms, generate
stats, flags and degraded. Ranks come from the trace's per-channel `:top`
lists (position + 1; "–" when absent). A test asserts every displayed
value equals the trace's.

**Document viewer.** Source is read from `CORPUS_DIR/<path>` (path
normalized; must stay inside the corpus dir). Rendered with commonmark,
raw HTML disabled. Each top-level block's source span (the
`commonmark-spans` mechanism already used by ingestion) is compared with
the char ranges of the doc's chunks: a block overlapping a chunk gets
`id="<chunk id>"`; blocks overlapping the chunk named by `?chunk=` get a
highlight background and the page scrolls there. If the file's sha256
differs from `:doc/hash`, a notice says the document changed since
indexing and highlights may be off. File missing → 404.

**Ingest job.** `hybridrag.ingest.runner`: one in-process job at a time
(atom + future), sharing the server's `index-conn`, same settings and
report file as `bb ingest`. Job map `{:id :status (:running :done
:failed) :started-at :finished-at :report :error}`; the last 20 jobs kept
in memory. UI triggers incremental ingest only; `reindex` stays CLI-only
(it deletes the index the server holds open).

**Waiting on `/ask`** (9–30 s locally): `hx-indicator` shows
「產生回答中（約 10–30 秒）…」 and `hx-disabled-elt` disables the button,
so a question cannot be sent twice. Chat/embedding failures render an
inline error box (the fragment's equivalent of the API's 503).

**Escaping.** All user- and model-derived text goes through Hiccup
escaping; the answer is plain text with `[n]` turned into links (no
Markdown rendering of model output in this phase).

## Testing

- Ring-level tests (handler called directly, like `api/*_test`): login
  success/failure, logout, redirect + `HX-Redirect` when logged out, CSRF
  rejection without token, `/` and `/ask` fragment content with a stub
  chat, Debug panel vs trace field-by-field, `/docs` 404 for unreadable /
  unknown / path traversal, highlight anchor present, changed-file
  notice, admin 404 for non-admin, ingest job lifecycle and 409, trace
  API owner/admin/other, §18.3 at the web level (unreadable docs never in
  the answer fragment, sources or debug panel).
- **Playwright** (`dev/browser/`, `package.json` with a devDependency
  only, local Chrome via `channel: "chrome"`, no browser download).
  `bb browser-check` starts a test server (sample corpus, stub embedder,
  stub chat, seeded users) and runs a script that logs in, asks, toggles
  Debug, clicks `[1]`, opens the document, opens admin and triggers an
  ingest, failing on any `console.error` or `pageerror`. Not part of
  `clojure -X:test`.

## Out of scope

SSE streaming (T5.4), Markdown rendering of answers, user management UI,
reindex from the UI, SSO (backlog).
