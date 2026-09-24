# Phase 4: Web UI (T4.1–T4.4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A logged-in browser UI for asking questions (answer, sources,
Debug panel), reading cited documents with highlights, and an admin page
that runs ingestion and lists traces — plus the remaining SPEC §11 API
endpoints those pages share code with.

**Architecture:** Server-rendered Hiccup pages under `hybridrag.web.*`,
HTMX fragments for `/ask` and admin actions, session-cookie auth
(`hybridrag.web.auth`) resolving to the same principal shape as the API.
Pages call the same functions as `/api/v1/*` (`answer/ask!`, trace, ACL
via the Retriever / `rd/accessible-doc-ids`). A new Integrant component
`hybridrag.ingest.runner/runner` owns in-process ingest jobs.

**Tech Stack:** Clojure 1.12, reitit, hiccup2 (via reitit-extras
`render-html`), HTMX 2 + Alpine (already vendored), Tailwind v4
standalone CLI, commonmark-java 0.24, ring-anti-forgery, Datalevin 1.1.0;
Playwright (`playwright-core`, local Chrome) for the browser check only.

**Spec:** `docs/superpowers/specs/2026-09-25-phase4-web-ui-design.md`
(approved), which supplements `SPEC.md` §11–§14, §17 Phase 4, §18.3.

## Global Constraints

- UI text Traditional Chinese; plain Tailwind classes, no DaisyUI, no npm
  in the build (npm only under `dev/browser/`).
- Web routes: session cookie + CSRF (`ring-anti-forgery`, token sent by
  HTMX as `X-CSRF-Token` via `hx-headers` on `<body>`); `/api/v1/*`:
  bearer token, no CSRF (unchanged).
- Session holds only `:username`; principal re-read from `app.dtlv` per
  request. Cookie `HttpOnly`, `SameSite=Lax`; `Secure` only in `:prod`.
- Not logged in → 302 `/login?next=<path>`; HTMX request → 200 +
  `HX-Redirect: /login`. `next` accepted only if it starts with `/` and
  not `//` or `/\`.
- Unreadable doc, unknown doc, non-admin on admin pages → 404 (never 403).
- All model- and document-derived text escaped by hiccup2; Markdown
  rendered with commonmark `escapeHtml(true)` + `sanitizeUrls(true)`.
- Debug panel values must equal the stored trace's.
- `reindex` never from the UI.
- Tests: nREPL fast loop per `CLAUDE.md`; before each commit
  `clojure -X:jvm-opts:test` and `clj-kondo --lint src test`. The repo
  pre-commit hook counts raw `( [ {` vs `) ] }` per file — keep string
  and regex literals balanced (see decisions 2026-09-24, `\x28`).

## Review Focus

1. **Open redirect through `?next=`** (`//evil.example`, `/\evil`,
   `https://evil`) → always lands on a same-site path (`/` fallback). →
   Task 1 `test-login-next-is-same-site`.
2. **Path traversal in `/docs/*path`** (`../`, `%2e%2e/`, absolute) → 404,
   never a file outside the corpus. → Task 3 `test-docs-traversal`.
3. **Active content in model output or documents** (`<script>` in the
   answer, raw HTML and `javascript:` links in Markdown) → shown inert. →
   Task 2 `test-answer-escaped`, Task 3 `test-doc-html-escaped`.
4. **A user demoted or deleted after logging in** → the next request
   reflects it (demoted admin gets 404 on `/admin`; deleted user is sent
   to `/login`). → Task 1 `test-session-reflects-current-user`.
5. **Double-clicked ingest, or an ingest that fails** → exactly one job
   runs; the second click sees "已有 ingest 在執行"; a failed job shows
   `:failed` with its error and releases the lock. → Task 4
   `test-runner-single-job` and `test-runner-failure-releases`.

---

### Task 1 (T4.1): session auth, layout, login/logout

**Files:**
- Create: `src/hybridrag/web/auth.clj`, `src/hybridrag/web/layout.clj`
- Modify: `src/hybridrag/views.clj` (error page uses the new layout; drop
  the Stack Lite home page), `src/hybridrag/handlers.clj` (drop
  `home-handler`), `src/hybridrag/routes.clj`, `src/hybridrag/server.clj`
  (session cookie attrs from `:secure-cookies?` option),
  `resources/config.edn` (`:secure-cookies? #profile {:default false :prod true}`)
- Create test helper: `test/hybridrag/web_client.clj`
- Test: `test/hybridrag/web/auth_test.clj`; rewrite
  `test/hybridrag/home_test.clj` (real system: `/` redirects to `/login`,
  `/login` renders)

**Interfaces:**
- Produces:
  - `(web-auth/wrap-session-auth handler)` → adds `:principal`.
  - `(web-auth/wrap-admin handler)` → 404 page unless `(:admin? principal)`.
  - `(layout/page request title & body)` → hiccup `[:html ...]` with
    `<meta name="csrf-token">`, `hx-headers` on `<body>`, nav (username,
    「管理」 link for admins, logout form), assets.
  - `(layout/not-found request)` → 404 ring response (HTML).
  - Test helper `hybridrag.web-client`: `(client handler)`,
    `(request! c method uri & {:keys [form headers]})` → ring response
    with `:body` as string; keeps cookies; `(csrf c)` → token scraped
    from the last HTML page's meta tag; `(login! c user password)`.

- [ ] **Step 1: Web test client** (test helper, no production code):

```clojure
(ns hybridrag.web-client
  "Cookie-keeping client over a Ring handler for web-route tests."
  (:require [clojure.string :as str]
            [ring.util.codec :as codec]))

(defn client [handler] (atom {:handler handler :cookies {} :csrf nil}))

(defn- set-cookies [resp]
  (for [h (let [v (get-in resp [:headers "Set-Cookie"])] (if (string? v) [v] v))
        :let [[kv] (str/split h #";")
              [k v] (str/split kv #"=" 2)]]
    [k v]))

(defn request!
  [c method uri & {:keys [form headers]}]
  (let [{:keys [handler cookies]} @c
        [path query] (str/split uri #"\?" 2)
        body (when form (codec/form-encode form))
        resp (handler (cond-> {:request-method method :uri path :query-string query
                               :scheme :http :server-name "localhost" :server-port 80
                               :headers (merge {"accept" "text/html"}
                                               (when (seq cookies)
                                                 {"cookie" (str/join "; " (map (fn [[k v]] (str k "=" v)) cookies))})
                                               (when form {"content-type" "application/x-www-form-urlencoded"})
                                               headers)}
                        body (assoc :body (java.io.ByteArrayInputStream. (.getBytes ^String body "UTF-8")))))
        resp (update resp :body #(cond (string? %) % (nil? %) "" :else (slurp %)))]
    (swap! c update :cookies into (set-cookies resp))
    (when-let [t (second (re-find #"name=\"csrf-token\" content=\"([^\"]+)\"" (:body resp)))]
      (swap! c assoc :csrf t))
    resp))

(defn csrf [c] (:csrf @c))

(defn login! [c user password]
  (request! c :get "/login")
  (request! c :post "/login" :form {"username" user "password" password
                                    "__anti-forgery-token" (csrf c)}))
```

- [ ] **Step 2: Failing tests** — `test/hybridrag/web/auth_test.clj`.
  Fixtures: `fx/with-sample-index` (:once); per test a tmp app conn with
  users `alice` (groups all,hr; password "alice-pw"), `admin` (admin;
  "admin-pw"), created with `users/create-user!` + `users/set-password!`.
  Handler: `server/ring-handler` with `{:options {:session-secret-key "test-secret-key"} :index-conn fx/*index* :app-conn app :search {...stub...}}`.
  Tests:
  - `test-redirects-when-logged-out`: `GET /` → 302, `Location` =
    `/login?next=%2F`; `GET /docs/hr/leave.md` → 302 with that next;
    `GET /` with header `hx-request: true` → 200 and `HX-Redirect` `/login`.
  - `test-login-success-and-logout`: `login!` alice → 302 `Location "/"`;
    `GET /` → 200, body contains "alice"; `POST /logout` with the CSRF
    token → 302 `/login`; `GET /` → 302 again.
  - `test-login-failure`: wrong password and unknown user → 200, the
    same message 「帳號或密碼錯誤」, still logged out.
  - `test-login-next-is-same-site`: next `"/docs/hr/leave.md"` →
    redirect there; next in `["//evil.example" "/\\evil" "https://evil.example" "javascript:alert(1)"]`
    → redirect `/`.
  - `test-csrf-required`: `POST /login` and `POST /logout` without
    token → 403.
  - `test-session-reflects-current-user`: alice logged in → `set-groups!`
    to `[]` has no effect on login but `users` deleted
    (`d/transact! [[:db/retractEntity [:user/username "alice"]]]`) →
    next `GET /` → 302 `/login`. Admin logged in → `GET /admin` 200;
    transact `:user/admin? false` → `GET /admin` 404. (`/admin` exists as
    a placeholder page in this task; Task 4 fills it.)
  - `test-cookie-attributes`: Set-Cookie of the session has `HttpOnly`
    and `SameSite=Lax`, no `Secure` by default; with option
    `:secure-cookies? true` it has `Secure`.

- [ ] **Step 3: Run → FAIL** (routes missing / namespace missing).

- [ ] **Step 4: Implement.**
  - `web/auth.clj`: `wrap-session-auth` (session `:username` →
    `users/find-user` → `users/principal`; else redirect as in Global
    Constraints, `next` = uri + `?query`), `wrap-admin`, `safe-next`,
    `login-page` (GET; form with `__anti-forgery-token` hidden field and
    `next` hidden field), `login!` (POST; on success response
    `(-> (redirect (safe-next next)) (assoc :session {:username u}))`;
    on failure re-render with 「帳號或密碼錯誤」), `logout!`
    (`(assoc (redirect "/login") :session nil)`).
  - `layout.clj`: `page`, `not-found`; token from
    `ring.middleware.anti-forgery/*anti-forgery-token*`; body
    `{:hx-headers (str "{\"X-CSRF-Token\": \"" token "\"}")}` (JSON built
    with jsonista to keep brackets balanced in source is fine either way).
  - `server.clj`: session `:cookie-attrs {:http-only true :same-site :lax :secure (boolean (:secure-cookies? options))}`;
    schema gains `[:secure-cookies? {:optional true} boolean?]`,
    `[:ingest {:optional true} any?]` (Task 4).
  - `routes.clj`:

```clojure
(def routes
  [["" {:middleware [anti-forgery/wrap-anti-forgery]}
    ["/login" {:name ::login :get {:handler web-auth/login-page} :post {:handler web-auth/login!}}]
    ["" {:middleware [web-auth/wrap-session-auth]}
     ["/" {:name ::home :get {:handler web-ask/page}}]          ; Task 2 (placeholder page here)
     ["/logout" {:name ::logout :post {:handler web-auth/logout!}}]
     ["/admin" {:middleware [web-auth/wrap-admin]}
      ["" {:name ::admin :get {:handler web-admin/page}}]]]]  ; Task 4 (placeholder here)
   ["/api/v1" ...unchanged...]])
```

  - `home_test.clj`: with the real system, `GET /` (clj-http,
    `:redirect-strategy :none`) → 302 to `/login`; `GET /login` → 200
    and a `form` with `input[name=username]`.

- [ ] **Step 5: Run → PASS**; full suite; lint.
- [ ] **Step 6: Commit** `T4.1: session login/logout, layout, web auth middleware`.

---

### Task 2 (T4.2): Q&A page, answer fragment, sources, Debug panel

**Files:**
- Create: `src/hybridrag/web/ask.clj`
- Modify: `src/hybridrag/api/ask.clj` (extract `answer-and-trace!`)
- Test: `test/hybridrag/web/ask_test.clj`

**Interfaces:**
- Consumes: `answer/ask!`; `trace/fetch`; Task 1 `layout/page`,
  `web-client`.
- Produces: `(api.ask/answer-and-trace! context principal query opts)` →
  `{:res <answer/ask! result> :trace-id uuid}` (writes the trace and the
  `[ASK]` log line; used by API and web). `web.ask/page`,
  `web.ask/ask` (POST, fragment).

- [ ] **Step 1: Failing tests** (`web/ask_test.clj`, same fixtures as
  auth_test, stub chat configurable per test):
  - `test-page`: logged-in `GET /` → form `hx-post="/ask"`, textarea
    `name=query`, checkbox `name=debug`, indicator element.
  - `test-ask-fragment`: stub chat `"特休依年資計算[1]。"`; `POST /ask`
    (form query + CSRF, header `hx-request`) → 200, not a full page
    (no `<html`), contains the answer with `<a href="#src-1"`, a source
    item `id="src-1"` with the doc title, section trail and a link
    `/docs/hr/leave.md?chunk=`, no Debug table.
  - `test-debug-panel-matches-trace`: with `debug=on`, parse the
    fragment (hickory); read the trace id from `data-trace-id`; fetch the
    trace. For every row: chunk id cell; lexical/semantic rank cells equal
    `(inc index)` of that chunk in the trace's `[:lexical :top]` /
    `[:semantic :top]` when present there; rerank cell equals
    `(format "%.3f" score)` from `[:rerank :scores]`; selected mark equals
    candidate `:selected?`. Stage ms cells equal the trace's `:ms` for
    `:lexical :semantic :fusion :graph :rerank :context :generate`.
  - `test-no-evidence-and-errors`: user with no groups → notice
    「在你有權限存取的資料中找不到相關內容。」; chat throwing
    `{:llm/endpoint :chat}` → 200 fragment with an error box
    「問答服務暫時無法使用（chat）」 (HTMX swaps 2xx only); rerank down →
    degraded notice 「重排序失敗」.
  - `test-answer-escaped`: stub chat `"<script>alert(1)</script>[1]"` →
    fragment contains `&lt;script&gt;` and no `<script>alert`.
  - `test-web-never-leaks`: for each restricted doc × user who cannot
    read it (fx/doc-groups), asking with the doc title, `debug=on`, chat
    citing `[1]`…`[20]` → the doc path appears nowhere in the fragment.
  - `test-empty-query`: blank query → fragment 「請輸入問題。」, chat not called.

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement.**
  - `api/ask.clj`: move the ask!+trace+log part of `handler` into
    `answer-and-trace!`; handler keeps validation, JSON, 503 mapping.
  - `web/ask.clj`:
    - `page`: `layout/page` with the form:
      `[:form {:hx-post "/ask" :hx-target "#result" :hx-indicator "#busy" :hx-disabled-elt "find button"} ...]`,
      `[:div#busy.htmx-indicator "產生回答中（約 10–30 秒）…"]`, `[:div#result]`.
    - `ask`: query blank → notice; else `answer-and-trace!` with opts
      from `(:opts search)`; catch ex-info with `:llm/endpoint` → error
      box; render `(result-fragment res trace)` where `trace` =
      `(trace/fetch db trace-id)`.
    - `answer-view`: split answer on `#"\[(\d+)\]"`; numbers become
      `[:a {:href (str "#src-" n)} (str "[" n "]")]`; wrapper class
      `whitespace-pre-line`.
    - `sources-view`: per citation `[:li {:id (str "src-" n)} title " · " (str/join " > " trail) excerpt(≤200 chars) [:a {:href (str "/docs/" path "?chunk=" (first chunk-ids))} "開啟文件"]]`.
    - `debug-view [candidates trace]`: table columns
      `chunk id | lexical | semantic | RRF | graph | rerank | 選中`;
      rank from the trace top lists (`rank-map` = chunk id → inc index),
      falling back to the candidate's `[:channels ch :rank]` for chunks
      below the trace's top 20, "–" when the channel did not return it;
      RRF `%.4f`, rerank `%.3f`, graph `✓` when `(get-in c [:channels :graph])`;
      below: stage ms list, generate model/tokens, flags, degraded;
      root element carries `data-trace-id`.
    - Every row/cell gets a `data-col` attribute (`"chunk"`, `"lexical"`, …)
      so tests select by attribute, not position.
  - Remove the Task 1 placeholder for `/`.

- [ ] **Step 4: Run → PASS**; full suite; lint.
- [ ] **Step 5: Commit** `T4.2: Q&A page with sources and Debug panel`.

---

### Task 3 (T4.3): document viewer + `GET /api/v1/docs/*path`

**Files:**
- Create: `src/hybridrag/web/docs.clj`, `src/hybridrag/docs.clj`
  (lookup + ACL + file read, shared by web and API),
  `src/hybridrag/api/docs.clj`
- Modify: `src/hybridrag/ingest/markdown.clj` (make `parser` public),
  `src/hybridrag/routes.clj`, `src/hybridrag/server.clj` context gets
  `:corpus-dir` (from the runner component in Task 4; until then an
  option `:corpus-dir` in the handler context, default
  `(:corpus-dir (config/corpus-config))`)
- Test: `test/hybridrag/web/docs_test.clj`, `test/hybridrag/api/docs_test.clj`

**Interfaces:**
- Produces:
  - `(docs/lookup db principal path)` → `{:doc {:doc/path :doc/title :doc/hash ...} :chunks [{:chunk/id :chunk/ordinal :chunk/char-start :chunk/char-end :section/trail}]}`
    or nil when unknown or unreadable (uses `rd/accessible-doc-ids`;
    admins read all).
  - `(docs/source-file corpus-dir path)` → `java.io.File` inside
    `corpus-dir` (canonical-path prefix check) or nil.
  - `(docs/render-blocks md chunks highlight-id)` → hiccup seq: one
    `[:div {:class ..} (raw html)]` per top-level block (front matter
    skipped), preceded by `[:span {:id chunk-id}]` anchors for each chunk
    whose first overlapping block this is; blocks overlapping the chunk
    `highlight-id` get `bg-amber-100` and `data-highlight "true"`.

- [ ] **Step 1: Failing tests.**
  - `docs_test` (web): alice `GET /docs/hr/leave.md` → 200, title in
    `<h1>`, one anchor per chunk of that doc; `?chunk=<id>` → at least
    one `data-highlight="true"` block and a script-free
    `hx-on`/Alpine `x-init` scroll (assert the target id attribute is
    present); bob (no hr) → 404; unknown path → 404; admin reads any doc.
  - `test-docs-traversal`: `GET /docs/../deps.edn`,
    `/docs/%2e%2e/deps.edn`, `/docs//etc/passwd` → 404.
  - `test-doc-changed-notice`: copy `corpus-sample` to a tmp dir, ingest
    it into a tmp index, then append a line to `hr/leave.md` on disk →
    page shows 「文件在建立索引後已變更」.
  - `test-doc-html-escaped`: tmp corpus file containing
    `<script>alert(1)</script>` as a raw HTML block and
    `[x](javascript:alert(1))` → page contains no `<script>alert` and no
    `href="javascript:`.
  - `test-doc-file-missing`: indexed doc whose file was deleted → 404.
  - `api/docs_test`: bearer alice `GET /api/v1/docs/hr/leave.md` → 200
    `{doc_path, title, tags, chunks: [{chunk_id, ordinal, section_trail, char_range}]}`;
    bob → 404 `not_found`; no token → 401.

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement.** `docs/render-blocks`:

```clojure
(def ^:private renderer
  (-> (HtmlRenderer/builder)
      (.extensions [(TablesExtension/create)])
      (.escapeHtml true)
      (.sanitizeUrls true)
      .build))

(defn render-blocks [^String md chunks highlight-id]
  (let [root (.parse md/parser md)
        blocks (->> (md/top-level-blocks root)            ; public helper: children + span
                    (remove #(instance? YamlFrontMatterBlock (:node %))))
        overlaps? (fn [{:keys [char-start char-end]} c]
                    (and (< char-start (:chunk/char-end c)) (< (:chunk/char-start c) char-end)))
        first-block (into {} (for [c chunks
                                   :let [b (first (filter #(overlaps? % c) blocks))]
                                   :when b]
                               [(:chunk/id c) b]))
        hl (some #(when (= highlight-id (:chunk/id %)) %) chunks)]
    (for [b blocks
          :let [anchors (for [[id fb] first-block :when (identical? fb b)] [:span {:id id}])
                on? (and hl (overlaps? b hl))]]
      (list anchors
            [:div (cond-> {:class ["md-block" (when on? "bg-amber-100")]}
                    on? (assoc :data-highlight "true"))
             (hiccup/raw (.render renderer ^Node (:node b)))]))))
```

  `md/top-level-blocks` returns `[{:node n :char-start :char-end}]` using
  the existing private `children`/`span`. Chunk ids contain `::` and `/`,
  which are valid in HTML `id` values; links use
  `(codec/url-encode id)` in the query string. Scrolling: the page adds
  `x-init="$el.scrollIntoView()"` (Alpine) on the first highlighted block.
  `docs/lookup` checks readability with the doc eid ∈
  `rd/accessible-doc-ids` (non-admin). The web handler reads the file,
  compares `writer/sha256-hex` of its bytes with `:doc/hash`.

- [ ] **Step 4: Run → PASS**; full suite; lint.
- [ ] **Step 5: Commit** `T4.3: document viewer with chunk highlights, /api/v1/docs`.

---

### Task 4 (T4.4): ingest runner, admin page, ingest + traces API

**Files:**
- Create: `src/hybridrag/ingest/runner.clj` (Integrant component),
  `src/hybridrag/web/admin.clj`, `src/hybridrag/api/ingest.clj`,
  `src/hybridrag/api/traces.clj`
- Modify: `resources/config.edn` (runner component + `:ingest` ref into
  the server; `:corpus-dir` for the docs viewer comes from it),
  `src/hybridrag/routes.clj`, `src/hybridrag/trace.clj` (`recent`)
- Test: `test/hybridrag/ingest/runner_test.clj`,
  `test/hybridrag/web/admin_test.clj`, `test/hybridrag/api/ingest_test.clj`,
  `test/hybridrag/api/traces_test.clj`

**Interfaces:**
- Produces:
  - runner value `{:index-conn :corpus-dir :data-dir :root-read-groups :embed-fn :jobs (atom {:current nil :history []})}`;
    `(runner/start! runner)` → `{:job job}` or `{:conflict job}`;
    `(runner/job runner id)`, `(runner/latest runner)`; job map
    `{:id str :status #{:running :done :failed} :started-at :finished-at :report :report-path :error}`;
    history keeps the last 20.
  - `(trace/recent db n)` → newest-first trace maps (no stages).

- [ ] **Step 1: Failing tests.**
  - `runner_test`: runner over a tmp index + copy of corpus-sample +
    `fx/hash-embed`. `test-runner-lifecycle`: start → `:running`, wait
    (poll ≤ 30 s) → `:done`, report `:added` 22-ish (assert `pos?`),
    report file exists under data-dir. `test-runner-single-job`: an
    embed-fn that blocks on a promise; second `start!` → `:conflict`;
    deliver → done; a new start is accepted. `test-runner-failure-releases`:
    corpus dir missing (or embed-fn throwing for every file so
    `ingest!` itself throws — use a nonexistent corpus dir) → `:failed`
    with `:error`, next `start!` accepted.
  - `admin_test` (web): admin `GET /admin` → button `hx-post="/admin/ingest"`,
    trace list with the newest traces (create 3 via `trace/write!`), each
    linking `/admin/traces/<id>`; `POST /admin/ingest` → fragment
    「執行中」 then polling `GET /admin/ingest/status` eventually
    「完成」 with report summary numbers; while running a second POST →
    「已有 ingest 在執行」; `GET /admin/traces/<id>` shows query and stage
    table; alice → 404 on all of them.
  - `api/ingest_test`: admin token `POST /api/v1/ingest` → 202
    `{job_id}`; again while running → 409 `conflict`; `GET /api/v1/ingest/:id`
    → `{status, report?}`; alice → 404; unknown id → 404.
  - `api/traces_test`: owner and admin `GET /api/v1/traces/:id` → 200
    with stages; other user → 404; bad uuid → 404.

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement.** Runner:

```clojure
(defn start! [{:keys [jobs] :as runner}]
  (let [job {:id (str (random-uuid)) :status :running :started-at (java.util.Date.)}
        [old _] (swap-vals! jobs (fn [s] (if (= :running (get-in s [:current :status])) s (assoc s :current job))))]
    (if (= :running (get-in old [:current :status]))
      {:conflict (:current old)}
      (do (future (run-job! runner job)) {:job job}))))
```

  `run-job!` calls `job/ingest!` with the runner's settings, writes the
  report with `report/write-report!`, and in `finally` moves the job to
  `:done`/`:failed` and appends to history (last 20). Init-key params:
  `{:index-conn ref}` plus optional overrides; defaults from
  `config/corpus-config` and `embed/embed-all!` over `config/embed-config`.
  Admin page shows index lag from the latest job's report (`:index-lag`)
  or 「尚無 ingest 紀錄」.

- [ ] **Step 4: Run → PASS**; full suite; lint.
- [ ] **Step 5: Commit** `T4.4: admin page, in-process ingest runner, /api/v1/ingest and /traces`.

---

### Task 5: browser check (Playwright) — the "no JS errors" AC

**Files:**
- Create: `dev/browser/package.json` (devDependency `playwright-core`),
  `dev/browser/check.mjs`, `dev/browser_server.clj` (ns
  `browser-server`: tmp index of corpus-sample with `fx/hash-embed`,
  stub rerank, stub chat `"特休依年資計算[1]。"`, tmp app.dtlv with users
  `alice/alice-pw`, `admin/admin-pw`, Jetty on port 8765)
- Modify: `bb.edn` (`browser-check` task: `css-build`, start
  `clojure -M:jvm-opts:test:dev -m browser-server` in background, wait for
  `/api/v1/health`, `npm --prefix dev/browser ci` if needed, `node
  dev/browser/check.mjs`, stop the server), `.gitignore`
  (`dev/browser/node_modules/`), `README.md` (how to run it)

- [ ] **Step 1: Script** `check.mjs`: `chromium.launch({channel: "chrome"})`;
  collect `page.on("console", m => m.type() === "error" && errors.push(...))`
  and `page.on("pageerror", ...)`; steps: login alice → ask 「特休天數怎麼計算？」
  with Debug on → wait for `[data-trace-id]` → click `a[href="#src-1"]` →
  click 「開啟文件」 → expect `[data-highlight="true"]` → logout → login
  admin → `/admin` → click ingest → wait for 「完成」; exit 1 listing
  every error, else print "browser check OK".
- [ ] **Step 2: Run** `bb browser-check`. Expected: "browser check OK".
  Any console error (e.g. missing asset) is fixed at its cause.
- [ ] **Step 3: Commit** `T4.5: Playwright browser check (no JS errors)`; add a
  `docs/decisions.md` entry "Phase 4 web UI details" (session/cookie,
  redirects, Debug panel source, doc highlighting granularity, runner).

---

## Self-review notes

- Spec coverage: login/logout/session (T1); `/` + `/ask` + sources +
  Debug (T2); doc viewer + highlight + changed-file notice + API docs
  (T3); admin + runner + ingest/traces APIs (T4); Playwright (T5); all
  four Phase 4 ACs: JS errors (T5), `/login` redirect (T1), Debug = trace
  (T2 test), unreadable doc 404 (T3).
- Deviation from the design text: Debug ranks come from the trace's
  `:top` lists as designed; chunks ranked below the trace's top 20 show
  the candidate's own `:channels` rank (the trace keeps only 20 per
  stage, §14). The test compares every rank the trace has.
