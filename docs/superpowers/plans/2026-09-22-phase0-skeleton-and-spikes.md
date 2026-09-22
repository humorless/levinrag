# hybridrag Phase 0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the `hybridrag` JVM skeleton (Clojure Stack Lite, stripped of SQL, wired to two embedded Datalevin connections) and produce the three mandatory Phase-0 spike reports (embedding path, full-text/analyzer registration, ACL query latency) that every later phase depends on, per `SPEC.md` §17 Phase 0 (T0.1–T0.5).

**Architecture:** One JVM process generated from the Clojure Stack Lite template (Integrant + Reitit/Ring/Jetty + Babashka tasks). The SQL stack (next.jdbc/HoneySQL/Ragtime/sqlite-jdbc) is removed and replaced by two embedded Datalevin connections (`index.dtlv`, `app.dtlv`) wired as Integrant components (D1 in `SPEC.md` §2.1). vLLM is reached only through three thin HTTP clients (embed/rerank/chat) built on `hato`, sharing one error-normalizing wrapper. Three spikes are throwaway REPL/script experiments whose conclusions get written to `docs/spikes/*.md` and, where they contradict `SPEC.md`, to `docs/decisions.md` — Phase 1+ implements against the spike conclusions, not the spec's `⚠️ VERIFY` guesses.

**Tech Stack:** Clojure 1.12.1, Integrant 0.13.1, Reitit/Ring/Jetty + integrant-extras/reitit-extras (from clojure-stack-lite), Datalevin 1.1.0 (embedded), hato (HTTP client), jsonista (JSON), Babashka tasks, clj-kondo/cljfmt, eftest.

**Spec:** `SPEC.md` (repo root — copied from the user-supplied `levinrag-spec.md` in Task 1)

## Global Constraints

- Project/namespace root is `hybridrag`, not `levinrag`. The spec text and its code examples say `levinrag` throughout (it's a generic filler name in the spec) — the user explicitly named this project `hybridrag`. Substitute `hybridrag` in every namespace, file path, artifact name and directory the spec shows under `src/levinrag/...`. Logged as a decision in Task 1.
- Datalevin is pinned to `1.1.0` — the actual latest release on Clojars. `SPEC.md` §3 says to pin "最新 1.0.x" (the plan originally followed that literally and picked `1.0.2`), but the user explicitly overrode this: use the true latest release regardless of the spec's `1.0.x` line, so `1.1.0`. Logged as a decision in Task 1.
- Never use Datalevin API from memory — verify against the pinned-version cljdoc/source before writing any `datalevin.core` call (`SPEC.md` §0.2/§0.3). This plan only hardcodes Datalevin calls that were verified against `cljdoc.org/d/datalevin/datalevin/1.1.0` while writing this plan (`get-conn`, `close`, `db` — confirmed identical signatures between 1.0.2 and 1.1.0; 1.1.0 only adds new optional WAL/HA options this plan doesn't use); anything used in Tasks 3–5 that isn't already verified must be checked live, not guessed.
- Code comments in English; user-facing UI text and error messages in Traditional Chinese (`SPEC.md` §0.4).
- No scope expansion — anything that occurs to you beyond the current task's AC goes in `docs/backlog.md`, not into code (`SPEC.md` §0.5).
- Ambiguous requirements: pick the simplest reversible option, log it in `docs/decisions.md` with the original spec text, the actual behavior found, and the choice made, then keep going — do not stop and wait for input (`SPEC.md` §0.6).
- One git commit per task, message format `T0.N: <summary>` (`SPEC.md` §0.7).
- vLLM API keys must never appear in logs, trace data, or exception data (`SPEC.md` §4.3).
- `⚠️ VERIFY` markers in `SPEC.md` are the spec author's unverified guesses. A spike's actual findings override the spec text when they disagree; record the override in `docs/decisions.md` (`SPEC.md` §0.2).

---

### Task 1: Repo init, Clojure Stack Lite skeleton, Datalevin wiring (T0.1)

**Files:**
- Create: `SPEC.md` (copy of `levinrag-spec.md`), `docs/decisions.md`, `docs/backlog.md`
- Generate via `cljstack` (alias for `neil new io.github.abogoyavlensky/clojure-stack-lite`): `deps.edn`, `bb.edn`, `resources/config.edn`, `resources/config.dev.edn`, `resources/logback.xml`, `resources/public/*`, `src/hybridrag/{core,server,handlers,routes,views,db}.clj`, `test/hybridrag/{home_test,test_utils}.clj`, `dev/user.clj`, `Dockerfile`, `.gitignore`, `README.md`, `LICENSE`
- Delete: `src/hybridrag/db.clj`, `resources/migrations/` (whole dir)
- Create: `src/hybridrag/db/schema.clj`, `src/hybridrag/db/index_conn.clj`, `src/hybridrag/db/app_conn.clj`, `test/hybridrag/health_test.clj`
- Modify: `deps.edn`, `resources/config.edn`, `src/hybridrag/server.clj`, `src/hybridrag/handlers.clj`, `src/hybridrag/routes.clj`, `test/hybridrag/test_utils.clj`, `test/hybridrag/home_test.clj`, `.gitignore`

**Interfaces:**
- Produces: `hybridrag.db.schema/app-schema` (a plain map, consumed by `app_conn.clj` here and by Phase 2 auth tasks).
- Produces: Integrant keys `:hybridrag.db.index-conn/index-conn` and `:hybridrag.db.app-conn/app-conn`, each resolving to a Datalevin `conn`. Later tasks get one via `(test-utils/index-conn)` / `(test-utils/app-conn)` in tests, or via `#ig/ref` in `config.edn` for new components.
- Produces: `hybridrag.handlers/health-handler`, a standard 1-arity ring handler reading `:index-conn`/`:app-conn` off `(:context request)`.
- Consumes: nothing (first task).

- [ ] **Step 1: git init and bring the spec in**

```bash
cd /Users/laurencechen/ForceUnion/levinrag
git status   # sanity check: confirm still just levinrag-spec.md + docs/
git init
cp levinrag-spec.md SPEC.md
git add SPEC.md
git commit -m "chore: add project spec as SPEC.md"
```

Leave `levinrag-spec.md` in place too (it's the user's original file) — do not delete it.

- [ ] **Step 2: generate the Clojure Stack Lite skeleton**

```bash
cljstack hybridrag . --db sqlite --overwrite
```

(`--db sqlite` matches the already-default value but keeps the command self-documenting; `:auth` is left unset because its default is already `false`, matching `SPEC.md` §3's "不選 `:auth`".)

- [ ] **Step 3: verify the generated layout and baseline-test it before touching anything**

```bash
find . -path ./.git -prune -o -type f -print | sort
bb deps
bb fmt-check
bb lint-init && bb lint
bb test
```

Expected: generated `src/hybridrag/*.clj` all use the `hybridrag` namespace root (confirms `:name hybridrag` flowed through correctly); `bb test`/`bb lint` pass on the untouched skeleton. This is a sanity baseline, not the task's real deliverable — if it fails, stop and diagnose before layering more changes on top.

```bash
git add -A
git commit -m "chore: generate clojure-stack-lite skeleton (sqlite, no auth)"
```

- [ ] **Step 4: strip SQL deps, add Datalevin + its required JVM opts**

Edit `deps.edn` — remove these 5 deps: `hikari-cp/hikari-cp`, `org.xerial/sqlite-jdbc`, `com.github.seancorfield/next.jdbc`, `com.github.seancorfield/honeysql`, `dev.weavejester/ragtime`. Add `datalevin/datalevin {:mvn/version "1.1.0"}`. Add a top-level `:jvm-opts` key (applies to every `clojure`/`bb` invocation, not just one alias) — these two flags are Datalevin 1.1.0's documented requirement (`doc/install.md`, verified against the pinned version while writing this plan):

```clojure
{:deps {org.clojure/clojure {:mvn/version "1.12.1"}
        ; logging
        org.clojure/tools.logging {:mvn/version "1.3.0"}
        ch.qos.logback/logback-classic {:mvn/version "1.5.18"}
        ; system & config
        integrant/integrant {:mvn/version "0.13.1"}
        io.github.abogoyavlensky/integrant-extras {:mvn/version "0.1.2"}
        ; server
        metosin/reitit-ring {:mvn/version "0.9.1"}
        metosin/reitit-middleware {:mvn/version "0.9.1"}
        metosin/reitit-malli {:mvn/version "0.9.1"}
        io.github.abogoyavlensky/reitit-extras {:mvn/version "0.2.2"}
        ring/ring-jetty-adapter {:mvn/version "1.14.2"}
        io.github.abogoyavlensky/manifest-edn {:mvn/version "0.1.1"}
        ; db (embedded, no external db server — SPEC.md D1/D2)
        datalevin/datalevin {:mvn/version "1.1.0"}}

 :paths ["src" "resources"]

 ; Datalevin 1.1.0 requires these JDK module opens on every JVM run
 ; (doc/install.md; verified 2026-09-22 — see docs/decisions.md). If the
 ; JVM is ever bumped to 24+, also add "--enable-native-access=ALL-UNNAMED".
 :jvm-opts ["--add-opens=java.base/java.nio=ALL-UNNAMED"
            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"]

 :aliases {:dev {:extra-paths ["dev"]
                 :extra-deps {integrant/repl {:mvn/version "0.4.0"}
                              ring/ring-devel {:mvn/version "1.14.2"}}}

           :test {:extra-paths ["test"]
                  :extra-deps {eftest/eftest {:mvn/version "0.6.0"}
                               cloverage/cloverage {:mvn/version "1.2.4"}
                               clj-http/clj-http {:mvn/version "3.13.1"}
                               circleci/bond {:mvn/version "0.6.0"}
                               org.clj-commons/hickory {:mvn/version "0.7.7"}}
                  :exec-fn cloverage.coverage/run-project
                  :exec-args {:test-ns-path ["test"]
                              :src-ns-path ["src"]
                              :runner :eftest
                              :runner-opts {:multithread? false}}}

           :outdated {:extra-deps {com.github.liquidz/antq {:mvn/version "2.11.1276"}}
                      :main-opts ["-m" "antq.core" "--no-diff"]}

           :build {:deps {io.github.abogoyavlensky/slim {:mvn/version "0.3.2"}}
                   :ns-default slim.app
                   :exec-args {:main-ns hybridrag.core
                               :src-dirs ["src" "resources" "resources-hashed"]}}
           :neil {:project {:name hybridrag/hybridrag}}}}
```

- [ ] **Step 5: delete the SQL-era db code**

```bash
rm src/hybridrag/db.clj
rm -rf resources/migrations
```

- [ ] **Step 6: write the app.dtlv schema**

Create `src/hybridrag/db/schema.clj`:

```clojure
(ns hybridrag.db.schema
  "Datalevin schemas. See SPEC.md §6 for the authoritative definitions.")

; app.dtlv — users, tokens, traces (SPEC.md §6.2). Standard Datalevin
; scalar/ref/cardinality-many attributes only, so no spike dependency.
(def app-schema
  {:user/username      {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :user/display-name  {:db/valueType :db.type/string}
   :user/password-hash {:db/valueType :db.type/string}
   :user/groups        {:db/valueType :db.type/string :db/cardinality :db.cardinality/many}
   :user/admin?        {:db/valueType :db.type/boolean}

   :token/hash         {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :token/user         {:db/valueType :db.type/ref}
   :token/label        {:db/valueType :db.type/string}
   :token/created-at   {:db/valueType :db.type/instant}

   :trace/id           {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :trace/username     {:db/valueType :db.type/string}
   :trace/kind         {:db/valueType :db.type/keyword}
   :trace/query        {:db/valueType :db.type/string}
   :trace/at           {:db/valueType :db.type/instant}
   :trace/stages       {}
   :trace/answer       {:db/valueType :db.type/string}
   :trace/degraded     {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}})

; index.dtlv — collections/docs/sections/chunks (SPEC.md §6.1). NOT defined
; here: :chunk/index-text needs :db/fulltext + :db/embedding + autoDomain
; options whose exact 1.1.0 syntax Task 3/4 (T0.3/T0.4 spikes) confirm.
; Defined for real in Phase 1 (T1.1) once those spikes land — see
; docs/decisions.md. index-conn opens with schema {} until then.
```

- [ ] **Step 7: write the two Datalevin connection components**

Create `src/hybridrag/db/index_conn.clj`:

```clojure
(ns hybridrag.db.index-conn
  "Integrant component for the embedded Datalevin connection that stores
   corpus-derived data (collections/docs/sections/chunks). Disposable and
   rebuildable from corpus/ at any time (SPEC.md D1)."
  (:require [clojure.tools.logging :as log]
            [datalevin.core :as d]
            [integrant-extras.core :as ig-extras]
            [integrant.core :as ig]))

(defmethod ig/assert-key ::index-conn
  [_ params]
  (ig-extras/validate-schema!
    {:component ::index-conn
     :data params
     :schema [:map [:dir string?]]}))

(defmethod ig/init-key ::index-conn
  [_ {:keys [dir]}]
  (log/info "[INDEX-CONN] Opening index.dtlv at" dir)
  (d/get-conn dir {}))

(defmethod ig/halt-key! ::index-conn
  [_ conn]
  (log/info "[INDEX-CONN] Closing index.dtlv")
  (d/close conn))
```

Create `src/hybridrag/db/app_conn.clj`:

```clojure
(ns hybridrag.db.app-conn
  "Integrant component for the embedded Datalevin connection that stores
   users, tokens and traces (SPEC.md D1). Never touched by `bb reindex`."
  (:require [clojure.tools.logging :as log]
            [datalevin.core :as d]
            [hybridrag.db.schema :as schema]
            [integrant-extras.core :as ig-extras]
            [integrant.core :as ig]))

(defmethod ig/assert-key ::app-conn
  [_ params]
  (ig-extras/validate-schema!
    {:component ::app-conn
     :data params
     :schema [:map [:dir string?]]}))

(defmethod ig/init-key ::app-conn
  [_ {:keys [dir]}]
  (log/info "[APP-CONN] Opening app.dtlv at" dir)
  (d/get-conn dir schema/app-schema))

(defmethod ig/halt-key! ::app-conn
  [_ conn]
  (log/info "[APP-CONN] Closing app.dtlv")
  (d/close conn))
```

- [ ] **Step 8: wire config.edn to the new components**

Replace the `:hybridrag.db/db` entry and the server's `:db` ref in `resources/config.edn` with:

```clojure
{:hybridrag.db.index-conn/index-conn
 {:dir #profile {:default "data/index.dtlv"
                 :test "data-test/index.dtlv"}}

 :hybridrag.db.app-conn/app-conn
 {:dir #profile {:default "data/app.dtlv"
                 :test "data-test/app.dtlv"}}

 :hybridrag.server/server
 {:options {:port #profile {:default 8000
                            :prod 80
                            :test #free-port true}
            :session-secret-key #profile {:default "test-secret-key"
                                          :prod #env SESSION_SECRET_KEY}
            :auto-reload? #profile {:default false
                                    :dev true}
            :cache-assets? #profile {:default false
                                     :prod true}}
  :index-conn #ig/ref :hybridrag.db.index-conn/index-conn
  :app-conn #ig/ref :hybridrag.db.app-conn/app-conn}}
```

`resources/config.dev.edn` needs no change — it just `#merge`s a `css-watch` process entry on top of `config.edn`.

Add to `.gitignore` (read the file first; append only what's missing):

```
data/
data-test/
```

- [ ] **Step 9: update server.clj — drop the Hikari-specific check**

In `src/hybridrag/server.clj`:
1. Delete the line `(:import com.zaxxer.hikari.HikariDataSource)`.
2. In the `ig/assert-key ::server` schema, replace:
   ```clojure
   [:db [:fn
         {:error/message "Invalid datasource type"}
         #(instance? HikariDataSource %)]]
   ```
   with:
   ```clojure
   [:index-conn [:fn {:error/message "Missing index-conn"} some?]]
   [:app-conn [:fn {:error/message "Missing app-conn"} some?]]
   ```

No other change needed — `ring-handler` already threads the whole `context` map (whatever keys `config.edn` puts under `:hybridrag.server/server`) through `wrap-context`, and `wrap-context` attaches it to each request under `:context` (verified against `reitit-extras.core/wrap-context` source).

- [ ] **Step 10: add the health handler and route**

In `src/hybridrag/handlers.clj`, add:

```clojure
(ns hybridrag.handlers
  (:require [datalevin.core :as d]
            [reitit-extras.core :as reitit-extras]
            [ring.util.response :as response]
            [hybridrag.views :as views]))

;; ... existing default-handler, home-handler unchanged ...

(defn- conn-ok?
  "True if `conn` is a live, queryable Datalevin connection."
  [conn]
  (try
    (some? (d/db conn))
    (catch Exception _ false)))

(defn health-handler
  "GET /api/v1/health — SPEC.md §11. Only checks the two Datalevin
   connections for now; vLLM reachability and index lag are added in T5.1."
  [request]
  (let [{:keys [index-conn app-conn]} (:context request)
        index-ok? (conn-ok? index-conn)
        app-ok?   (conn-ok? app-conn)]
    (-> (response/response {:index_db (if index-ok? "ok" "down")
                            :app_db   (if app-ok? "ok" "down")})
        (response/status (if (and index-ok? app-ok?) 200 503)))))
```

In `src/hybridrag/routes.clj`, replace the trivial `/health` route with:

```clojure
(ns hybridrag.routes
  (:require [hybridrag.handlers :as handlers]))

(def routes
  [["/" {:name ::home
         :get {:handler handlers/home-handler}
         :responses {200 {:body string?}}}]
   ["/api/v1/health" {:name ::health-check
                       :get {:handler handlers/health-handler}}]])
```

(`ring.util.response` is no longer needed directly in `routes.clj` once the inline `/health` fn is gone — drop that require too if `clj-kondo` flags it as unused.)

- [ ] **Step 11: update test helpers and existing test for the SQL removal**

Replace `test/hybridrag/test_utils.clj`:

```clojure
(ns hybridrag.test-utils
  (:require [hickory.core :as hickory]
            [integrant-extras.tests :as ig-extras]
            [hybridrag.db.app-conn :as app-conn]
            [hybridrag.db.index-conn :as index-conn]
            [hybridrag.server :as server]))

(def ^:const TEST-CSRF-TOKEN "test-csrf-token")
(def ^:const TEST-SECRET-KEY "test-secret-key")

(defn response->hickory
  "Convert a Ring response body to a Hickory document."
  [response]
  (-> response :body hickory/parse hickory/as-hickory))

(defn index-conn
  "Get the index.dtlv connection from the test system."
  []
  (::index-conn/index-conn ig-extras/*test-system*))

(defn app-conn
  "Get the app.dtlv connection from the test system."
  []
  (::app-conn/app-conn ig-extras/*test-system*))

(defn server
  "Get the server instance from the test system."
  []
  (::server/server ig-extras/*test-system*))
```

In `test/hybridrag/home_test.clj`, delete the `(use-fixtures :each test-utils/with-truncated-tables)` block — there are no SQL tables left to truncate. Keep the `:once with-system` fixture and the existing assertion as-is.

- [ ] **Step 12: write the health check test**

Create `test/hybridrag/health_test.clj`:

```clojure
(ns hybridrag.health-test
  (:require [clj-http.client :as http]
            [clojure.test :refer :all]
            [integrant-extras.tests :as ig-extras]
            [reitit-extras.tests :as reitit-extras]
            [hybridrag.test-utils :as test-utils]))

(use-fixtures :once
  (ig-extras/with-system))

(deftest test-health-reports-both-dbs-open
  (let [url (str (reitit-extras/get-server-url (test-utils/server) :host)
                 "/api/v1/health")
        response (http/get url {:as :json :throw-exceptions false})]
    (is (= 200 (:status response)))
    (is (= "ok" (get-in response [:body :index_db])))
    (is (= "ok" (get-in response [:body :app_db])))))
```

- [ ] **Step 13: run the full T0.1 AC**

```bash
bb fmt-check
bb lint-init && bb lint
bb test
```

Expected: all three pass, including `test-health-reports-both-dbs-open`. This is `SPEC.md` T0.1's AC in full ("`bb test`、`bb lint` 通過；`/api/v1/health` 回報兩個 DB 已開啟").

- [ ] **Step 14: log the decisions this task made**

Append to `docs/decisions.md` (create with a one-line header if it doesn't exist yet: `# Decisions log`):

```markdown
## 2026-09-22 — Project namespace root

Spec text (SPEC.md, throughout): shows `levinrag` as the namespace root
and directory layout (`src/levinrag/...`).
Actual: user explicitly asked for project name `hybridrag`.
Decision: every namespace, file path and generated artifact name uses
`hybridrag`; `levinrag` only survives as the containing directory name and
in the original `levinrag-spec.md` filename.

## 2026-09-22 — Datalevin version

Spec text (SPEC.md §3): "Datalevin，pin 最新 1.0.x".
Actual: Clojars' newest release is 1.1.0, one line ahead of the 1.0.x
series (latest 1.0.x is 1.0.2). The user explicitly instructed: use the
true latest release, ignore the spec's "1.0.x" line.
Decision: pinned `datalevin/datalevin {:mvn/version "1.1.0"}`. Verified
before pinning: `doc/install.md`'s required JVM `--add-opens` flags are
unchanged from 1.0.2, and `get-conn`/`close`/`db` have identical
signatures in both versions (1.1.0 only adds new optional WAL/HA params
this project doesn't use).

## 2026-09-22 — index.dtlv schema deferred to Phase 1

Spec text (SPEC.md §6.1, §0.3): defines the full index-schema including
`:chunk/index-text` with `:db/fulltext`/`:db/embedding`/autoDomain
options, but flags the store-options for those features `⚠️ VERIFY` in
T0.3/T0.4, and §0.3 forbids using unverified Datalevin API from memory.
Decision: `index-conn` opens in T0.1 with an empty schema (`{}`), just to
prove the connection lifecycle works. The real index-schema is written in
Phase 1 (T1.1/T1.4) once the T0.3/T0.4 spikes (Tasks 3–4 of this plan)
confirm the correct 1.1.0 syntax.

## 2026-09-22 — Partial /api/v1/health in T0.1

Spec text (SPEC.md §11): `/health` should report "DB 狀態，三個 vLLM
endpoint 可達性，index lag；任一依賴失敗回 503".
Decision: T0.1's health handler only checks the two Datalevin connections
(that's this task's AC). vLLM reachability and index lag are added in
T5.1 once the vLLM clients (Task 2) and ingestion (Phase 1) exist.

## 2026-09-22 — Project generated via local `cljstack` alias

Generation command actually used: `cljstack hybridrag . --db sqlite
--overwrite`, i.e. `neil new io.github.abogoyavlensky/clojure-stack-lite
hybridrag . --db sqlite --overwrite` — target-dir `.` so the project
lands directly in the repo root instead of a nested `hybridrag/`
subdirectory. `:auth` left at its default (`false`).
```

Create `docs/backlog.md` with just a header for now:

```markdown
# Backlog

Ideas and out-of-scope improvements noticed while building hybridrag.
Nothing here should be implemented without first promoting it to a
task in SPEC.md §17. See SPEC.md §20 for the spec author's own backlog.
```

- [ ] **Step 15: commit**

```bash
git add -A
git commit -m "T0.1: strip SQL, wire Datalevin index/app conns, /api/v1/health"
```

---

### Task 2: vLLM HTTP clients + `bb vllm:check` (T0.2)

**Files:**
- Create: `src/hybridrag/config.clj`, `src/hybridrag/llm/http.clj`, `src/hybridrag/llm/embed.clj`, `src/hybridrag/llm/rerank_client.clj`, `src/hybridrag/llm/chat.clj`, `src/hybridrag/llm/check.clj`
- Create: `test/hybridrag/llm/http_test.clj` (stub-server based, no real vLLM needed)
- Modify: `deps.edn` (add `hato`, `jsonista`, a `:vllm-check` exec alias), `bb.edn` (add `vllm:check` task)

**Interfaces:**
- Consumes: nothing from Task 1 (independent of the DB wiring).
- Produces: `hybridrag.config/{embed-config,rerank-config,chat-config}` (0-arg fns returning `{:base-url :model :api-key ...}` maps read from env, per `SPEC.md` §5) — consumed by Phase 1/2/3 tasks (ingestion embedding calls, rerank in retrieval, answer generation).
- Produces: `hybridrag.llm.embed/{embed-batch!,embed-all!}`, `hybridrag.llm.rerank-client/rerank!`, `hybridrag.llm.chat/complete!` — exact signatures below, consumed by Phase 1 (embedding path spike + real ingestion), Phase 2 (rerank), Phase 3 (answer generation).
- Produces: `hybridrag.llm.http/post-json!` — the single place that normalizes vLLM HTTP errors into `ex-info` with `:llm/endpoint`/`:http/status`/`:llm/body-excerpt` (`SPEC.md` §4.3), reused by all three clients above.

- [ ] **Step 1: add deps and the config namespace**

Add to `deps.edn` `:deps`:

```clojure
hato/hato {:mvn/version "1.0.0"}
metosin/jsonista {:mvn/version "0.3.13"}
```

(Check Clojars for the actual current stable versions before pinning — `hato` and `jsonista` aren't Datalevin, so this isn't a `⚠️ VERIFY` item, but don't hardcode a version number you haven't confirmed exists.)

Create `src/hybridrag/config.clj`:

```clojure
(ns hybridrag.config
  "Plain env-var config for standalone CLI tools (bb tasks) that must not
   boot the full Integrant/Ring system. See SPEC.md §5 for the full table.")

(defn- env [k] (System/getenv k))
(defn- env-or [k default] (or (env k) default))

(defn embed-config []
  {:base-url (env-or "VLLM_EMBED_BASE_URL" "http://localhost:8001/v1")
   :model    (env-or "VLLM_EMBED_MODEL" "BAAI/bge-m3")
   :dims     (parse-long (env-or "VLLM_EMBED_DIMS" "1024"))
   :api-key  (or (env "VLLM_EMBED_API_KEY") (env "VLLM_API_KEY"))})

(defn rerank-config []
  {:base-url (env-or "VLLM_RERANK_BASE_URL" "http://localhost:8002")
   :path     (env-or "VLLM_RERANK_PATH" "/v1/rerank")
   :model    (env-or "VLLM_RERANK_MODEL" "BAAI/bge-reranker-v2-m3")
   :api-key  (or (env "VLLM_RERANK_API_KEY") (env "VLLM_API_KEY"))})

(defn chat-config []
  {:base-url (env "VLLM_CHAT_BASE_URL")
   :model    (env "VLLM_CHAT_MODEL")
   :api-key  (or (env "VLLM_CHAT_API_KEY") (env "VLLM_API_KEY"))})
```

- [ ] **Step 2: write the shared HTTP wrapper**

Create `src/hybridrag/llm/http.clj`:

```clojure
(ns hybridrag.llm.http
  "Shared HTTP plumbing for the three vLLM OpenAI-compatible endpoints.
   Errors are normalized to ex-info with :llm/endpoint, :http/status and
   :llm/body-excerpt so callers can branch without parsing messages.
   API keys are never included in the exception or logged (SPEC.md §4.3)."
  (:require [hato.client :as hc]
            [jsonista.core :as json])
  (:import [java.net ConnectException]
           [java.net.http HttpTimeoutException]))

(def ^:private object-mapper (json/object-mapper {:decode-key-fn keyword}))

(defn- excerpt [s] (when s (subs s 0 (min 500 (count s)))))

(defn post-json!
  "POST `body` (a Clojure map) as JSON to `url` with bearer `api-key`.
   `endpoint-kw` (e.g. :embed) is only used for error reporting."
  [{:keys [url api-key body connect-timeout-ms read-timeout-ms endpoint-kw]}]
  (let [client (hc/build-http-client {:connect-timeout connect-timeout-ms})]
    (try
      (let [response (hc/post url
                        {:http-client       client
                         :timeout           read-timeout-ms
                         :oauth-token       api-key
                         :content-type      "application/json"
                         :body              (json/write-value-as-string body)
                         :throw-exceptions? false})
            status (:status response)
            raw    (:body response)]
        (if (<= 200 status 299)
          (json/read-value raw object-mapper)
          (throw (ex-info (str "vLLM " (name endpoint-kw) " returned HTTP " status)
                           {:llm/endpoint     endpoint-kw
                            :http/status      status
                            :llm/body-excerpt (excerpt raw)}))))
      (catch HttpTimeoutException e
        (throw (ex-info (str "vLLM " (name endpoint-kw) " timed out")
                         {:llm/endpoint endpoint-kw :http/status nil} e)))
      (catch ConnectException e
        (throw (ex-info (str "vLLM " (name endpoint-kw) " connection refused")
                         {:llm/endpoint endpoint-kw :http/status nil} e))))))
```

- [ ] **Step 3: write the embed client**

Create `src/hybridrag/llm/embed.clj`:

```clojure
(ns hybridrag.llm.embed
  (:require [hybridrag.config :as config]
            [hybridrag.llm.http :as http]))

(def ^:private connect-timeout-ms 2000)
(def ^:private read-timeout-ms 30000)
(def ^:private default-batch-size 32)

(defn embed-batch!
  "Embed up to `default-batch-size` strings in one HTTP call. Returns a
   vector of float vectors in the same order as `texts`."
  ([texts] (embed-batch! (config/embed-config) texts))
  ([{:keys [base-url model api-key]} texts]
   (let [response (http/post-json!
                    {:url                (str base-url "/embeddings")
                     :api-key            api-key
                     :body               {:model model :input (vec texts)}
                     :connect-timeout-ms connect-timeout-ms
                     :read-timeout-ms    read-timeout-ms
                     :endpoint-kw        :embed})]
     (mapv :embedding (:data response)))))

(defn embed-all!
  "Embed any number of strings, batching at `batch-size`."
  ([texts] (embed-all! (config/embed-config) texts default-batch-size))
  ([cfg texts batch-size]
   (vec (mapcat #(embed-batch! cfg %) (partition-all batch-size texts)))))
```

- [ ] **Step 4: write the rerank client**

Create `src/hybridrag/llm/rerank_client.clj`:

```clojure
(ns hybridrag.llm.rerank-client
  (:require [hybridrag.config :as config]
            [hybridrag.llm.http :as http]))

(def ^:private connect-timeout-ms 2000)
(def ^:private read-timeout-ms 10000)

(defn rerank!
  "Rerank `documents` against `query`. Returns a vector of
   {:index i :relevance-score s}, best-first. Throws ex-info if the vLLM
   response is missing :results — SPEC.md §4.2 notes vLLM can return
   HTTP 200 with an error payload, so that shape has to be checked
   explicitly rather than trusted from the status code alone."
  ([query documents top-n] (rerank! (config/rerank-config) query documents top-n))
  ([{:keys [base-url path model api-key]} query documents top-n]
   (let [response (http/post-json!
                    {:url                (str base-url path)
                     :api-key            api-key
                     :body               {:model model :query query
                                           :documents (vec documents) :top_n top-n}
                     :connect-timeout-ms connect-timeout-ms
                     :read-timeout-ms    read-timeout-ms
                     :endpoint-kw        :rerank})]
     (if-let [results (:results response)]
       (mapv (fn [{:keys [index relevance_score]}]
               {:index index :relevance-score relevance_score})
             results)
       (throw (ex-info "vLLM rerank response missing :results"
                        {:llm/endpoint     :rerank
                         :http/status      200
                         :llm/body-excerpt (subs (str response) 0 (min 500 (count (str response))))}))))))
```

- [ ] **Step 5: write the chat client**

Create `src/hybridrag/llm/chat.clj`:

```clojure
(ns hybridrag.llm.chat
  "Raw chat/completions client. Extracting citations and stripping
   <think> blocks is hybridrag.llm.answer's job — added in Phase 3."
  (:require [hybridrag.config :as config]
            [hybridrag.llm.http :as http]))

(def ^:private connect-timeout-ms 2000)
(def ^:private read-timeout-ms 120000)

(defn complete!
  "Call chat/completions with `messages` ([{:role .. :content ..} ...]).
   Returns the raw parsed response map."
  ([messages opts] (complete! (config/chat-config) messages opts))
  ([{:keys [base-url model api-key]} messages {:keys [temperature max-tokens extra-body]}]
   (http/post-json!
    {:url                (str base-url "/chat/completions")
     :api-key            api-key
     :body               (merge {:model model :messages messages
                                  :temperature temperature :max_tokens max-tokens}
                                 extra-body)
     :connect-timeout-ms connect-timeout-ms
     :read-timeout-ms    read-timeout-ms
     :endpoint-kw        :chat})))
```

- [ ] **Step 6: write `bb vllm:check`**

Create `src/hybridrag/llm/check.clj`:

```clojure
(ns hybridrag.llm.check
  "Standalone CLI check for `bb vllm:check` (SPEC.md T0.2 AC). Calls all
   three real vLLM endpoints — requires them to actually be running."
  (:require [hybridrag.llm.chat :as chat]
            [hybridrag.llm.embed :as embed]
            [hybridrag.llm.rerank-client :as rerank]))

(defn- report [endpoint-kw f]
  (try
    (f)
    (println (format "[OK]   %s" (name endpoint-kw)))
    true
    (catch clojure.lang.ExceptionInfo e
      (println (format "[FAIL] %s: %s (status=%s)"
                        (name endpoint-kw) (ex-message e) (:http/status (ex-data e))))
      false)))

(defn run!
  [_]
  (let [ok? [(report :embed  #(embed/embed-batch! ["測試 embedding"]))
             (report :rerank #(rerank/rerank! "測試" ["候選一" "候選二"] 2))
             (report :chat   #(chat/complete! [{:role "user" :content "你好"}]
                                               {:temperature 0.0 :max-tokens 16}))]]
    (when-not (every? true? ok?)
      (System/exit 1))))
```

Add to `deps.edn` `:aliases`:

```clojure
:vllm-check {:exec-fn hybridrag.llm.check/run!}
```

Add to `bb.edn` `:tasks`:

```clojure
vllm:check {:doc "Check embed/rerank/chat vLLM endpoints are reachable"
            :task (clojure "-X:vllm-check")}
```

- [ ] **Step 7: stub-server test for the error-handling contract**

This is the automatable half of T0.2's AC — the "reasonable result from real vLLM" half needs Step 8. Create `test/hybridrag/llm/http_test.clj` using a minimal in-process `com.sun.net.httpserver.HttpServer` stub (no extra dependency needed, it's in the JDK):

```clojure
(ns hybridrag.llm.http-test
  (:require [clojure.test :refer :all]
            [jsonista.core :as json]
            [hybridrag.llm.http :as http])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]))

(defn- start-stub-server!
  "Start a JDK HttpServer on a random free port. `handler-fn` takes the
   HttpExchange and must call .sendResponseHeaders + write + .close."
  [handler-fn]
  (let [server (HttpServer/create (InetSocketAddress. "localhost" 0) 0)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange] (handler-fn exchange))))
    (.start server)
    server))

(defn- respond! [^HttpExchange exchange status body-str]
  (let [bytes (.getBytes body-str "UTF-8")]
    (.sendResponseHeaders exchange status (count bytes))
    (doto (.getBody exchange)
      (.write bytes)
      (.close))))

(defn- stub-url [^HttpServer server] (str "http://localhost:" (.getPort (.getAddress server))))

(deftest test-post-json-success
  (let [server (start-stub-server! #(respond! % 200 (json/write-value-as-string {:ok true})))]
    (try
      (is (= {:ok true}
             (http/post-json! {:url (stub-url server) :api-key "secret-key-xyz"
                                :body {:q "hi"} :connect-timeout-ms 2000
                                :read-timeout-ms 2000 :endpoint-kw :embed})))
      (finally (.stop server 0)))))

(deftest test-post-json-error-status-includes-endpoint-and-excerpt-not-key
  (let [server (start-stub-server! #(respond! % 401 "{\"error\": \"invalid api key\"}"))]
    (try
      (let [ex (is (thrown? clojure.lang.ExceptionInfo
                             (http/post-json! {:url (stub-url server) :api-key "secret-key-xyz"
                                                :body {} :connect-timeout-ms 2000
                                                :read-timeout-ms 2000 :endpoint-kw :rerank})))]
        nil)
      (catch clojure.lang.ExceptionInfo e
        (is (= :rerank (:llm/endpoint (ex-data e))))
        (is (= 401 (:http/status (ex-data e))))
        (is (not (re-find #"secret-key-xyz" (pr-str (ex-data e)))))
        (is (not (re-find #"secret-key-xyz" (ex-message e)))))
      (finally (.stop server 0)))))
```

Run: `bb test` — expect both new tests to pass.

- [ ] **Step 8: manual real-vLLM verification (do this if vLLM is running locally; otherwise log and move on)**

```bash
bb vllm:check
```

If `VLLM_EMBED_BASE_URL`/`VLLM_RERANK_BASE_URL`/`VLLM_CHAT_BASE_URL` (and matching `_MODEL`/`_API_KEY`) aren't set and no local vLLM is up, this will fail with connection-refused — that's expected and fine. Do not treat a connection failure here as a Task 2 blocker; log it:

Append to `docs/decisions.md`:

```markdown
## 2026-09-22 — bb vllm:check not run against real vLLM in Task 2

SPEC.md T0.2 AC requires all three endpoints "皆回傳合理結果" against
real vLLM. No vLLM instances were reachable while building this task.
`bb vllm:check` and the stub-server tests in
test/hybridrag/llm/http_test.clj prove the client code is correct; the
real-endpoint check is deferred until vLLM is actually running — re-run
`bb vllm:check` then and update this entry.
```

(If vLLM *is* reachable, instead confirm three `[OK]` lines, and grep the terminal output plus `logs/` for the literal API key string to confirm §4.3's "log 中搜尋不到 key 字串" — then skip the decisions.md entry above.)

- [ ] **Step 9: commit**

```bash
git add -A
git commit -m "T0.2: vLLM embed/rerank/chat clients + bb vllm:check"
```

---

### Task 3: Spike — embedding path (T0.3)

**Files:**
- Create: `docs/spikes/embedding.md`
- Scratch work happens at the REPL (`bb clj-repl`) or in a throwaway script under the scratchpad — nothing here is meant to survive as shipped code except the doc.

**Interfaces:**
- Consumes: `hybridrag.llm.embed/embed-batch!` (Task 2) if Path B is chosen and needs a reference implementation to compare against.
- Produces: a written decision — "Path A" (Datalevin's built-in `:db/embedding` + `:openai-compatible` provider) or "Path B" (application-side batch calls to `/v1/embeddings`, storing `:db.type/vec`, querying via `vec-neighbors`) — that Phase 1's `retrieval/datalevin.clj` (T2.1) and the real `index-schema` (T1.1) are built against. This task does not change `hybridrag.db.schema/app-schema` or add the real index-schema; it only produces the doc that Task in Phase 1 will implement from.

`SPEC.md` §0.2/§0.3 forbid guessing Datalevin API from memory here — this task is explicitly the place where that gets resolved by reading the pinned-version docs and running real code, not by reasoning from training data.

- [ ] **Step 1: read the pinned-version docs for `:db/embedding` and vector search**

Fetch and read (don't guess): `cljdoc.org/d/datalevin/datalevin/1.1.0` — specifically whatever covers vector/embedding indexing (search for "embedding", "vector", "autoDomain", "openai-compatible" in the namespace docs and any embedding-specific guide page linked from the cljdoc index). Also check `github.com/juji-io/datalevin` `CHANGELOG.md` around the `1.0.x` tags for embedding-related notes, since this feature is newer than the core Datalog engine.

Record findings inline in `docs/spikes/embedding.md` as you go — what attribute-level options exist, what a `:db/embedding` schema entry actually looks like in 1.1.0, what `:embedding-opts`/provider configuration looks like (the spec's draft in §6.1 store-options is a guess — confirm or correct it).

- [ ] **Step 2: try Path A — `:db/embedding` with an `:openai-compatible` provider pointed at vLLM**

At a `bb clj-repl` (after `(reset)`), open a throwaway Datalevin connection with a minimal one-attribute schema using whatever `:db/embedding` + provider options Step 1 turned up, pointed at the real embed endpoint (`hybridrag.config/embed-config`). Transact a handful of Traditional-Chinese test strings, and check:
- Does the connection succeed with credentials read from `VLLM_EMBED_API_KEY`/`VLLM_API_KEY`?
- Is `:request-dimensions` (or whatever the real option is called) sent as vLLM's expected `dimensions` request param, or does vLLM reject it? Try with and without the dims option set.
- With indexing mode `:async` (if that's a real option), how do you detect "not yet indexed" vs "indexed" for a given chunk — is there a `wait-for-secondary-index`-shaped function, and does it actually block until embeddings are computed?
- Batch-transact ~500 short Traditional-Chinese chunks and time it — this is the throughput number the AC wants.

- [ ] **Step 3: try Path B — application-side embedding + `:db.type/vec`**

Same throwaway connection approach, but: schema attribute is `:db.type/vec` (confirm the exact valueType keyword in the docs, don't guess), values come from calling `hybridrag.llm.embed/embed-batch!` before transacting, and querying uses whatever the neighbor-search function is actually called in 1.1.0 (spec guesses `vec-neighbors` — confirm the real name and arglist). Time the same ~500-chunk batch for comparison with Path A.

- [ ] **Step 4: write the decision doc**

Create `docs/spikes/embedding.md`:

```markdown
# Spike: embedding path (T0.3)

Datalevin version: 1.1.0. Date: <fill in>.

## What was tested

- Path A: `:db/embedding` + `:openai-compatible` provider against vLLM
  (`BAAI/bge-m3`, http://localhost:8001/v1).
- Path B: application-side `hybridrag.llm.embed/embed-batch!` +
  `:db.type/vec` storage + vector neighbor query.

## Findings

<Fill in per Step 2/3: does auth work, is :request-dimensions sent as
`dimensions` and accepted/rejected, what does async indexing +
wait-for-secondary-index actually do, throughput for 500 chunks on each
path, and anything from SPEC.md §6.1's draft store-options that turned
out to be wrong.>

## Decision

<Path A or Path B. One paragraph why.>

## Consequence for later tasks

- T1.1 index-schema's `:chunk/index-text` attribute definition: <what it
  should actually look like, corrected from SPEC.md §6.1's draft>.
- T2.1 semantic channel implementation: <Path A: pure Datalog query
  using the embedding attribute. Path B: call embed-batch! on the query
  text, then call <real fn name>.>
- Retriever protocol (SPEC.md §9.2) is unaffected either way — the
  `channel` method signature stays the same.
```

If the findings contradict `SPEC.md`'s draft store-options in §6.1, also add a `docs/decisions.md` entry summarizing the override (spec text → actual behavior → what Phase 1 implements).

- [ ] **Step 5: commit**

```bash
git add docs/spikes/embedding.md docs/decisions.md
git commit -m "T0.3: spike embedding path, decide Path A vs Path B"
```

---

### Task 4: Spike — full-text search / CJK analyzer registration (T0.4)

**Files:**
- Create: `docs/spikes/fulltext.md`, plus a runnable minimal example — either inline in the doc as a fenced code block that was actually executed at the REPL, or as a small `dev/spikes/fulltext_example.clj` scratch file (your call; the AC just requires "含可執行的最小範例").

**Interfaces:**
- Produces: confirmed syntax for registering a custom analyzer on a Datalog search domain in 1.1.0, confirmed shape of `:display :refs+scores` fulltext query results, and a confirmed answer on `:doc-filter` timing — all consumed by Phase 1's `search/analyzer.clj` (§8) and Phase 2's lexical channel (T2.1, `SPEC.md` §9.3).

- [ ] **Step 1: read the pinned-version docs for search-domain analyzers**

Fetch `cljdoc.org/d/datalevin/datalevin/1.1.0` for `datalevin.core`'s fulltext-search functions and any dedicated full-text-search guide. Look specifically for: how a custom analyzer function is registered against a search domain (a function passed directly in `:search-domains` store options, vs. some kind of registry `def`/`register!` call), whether the analyzer is store-level config or a runtime argument to `fulltext`/`search`, and what shape `[term position offset]` tuples need to be in.

- [ ] **Step 2: minimal executable example — register + query**

At `bb clj-repl`, open a throwaway connection with a single fulltext-enabled string attribute, and:
- Register a trivial custom analyzer (doesn't need to be the real CJK one yet — e.g. a whitespace/char-bigram toy analyzer is enough to prove the *registration mechanism* works) using whatever Step 1 found.
- Transact a few Traditional-Chinese strings.
- Run a `fulltext` query with `:display :refs+scores` and print the raw result shape — confirm it's really `[[?e attr value ?score] ...]`-ish as `SPEC.md` §9.3's example query assumes, or correct it.
- Test `:index-position? true` and confirm phrase/proximity queries actually work with it on.
- Test whether the same attribute can carry both `:db/fulltext true` and (from Task 3's Path A, if chosen) `:db/embedding true` simultaneously without schema conflict — `SPEC.md` D6 assumes this works.
- Test `:doc-filter`: run one query with a doc-filter predicate and inspect (e.g. via the DB's own hit-count reporting, or by comparing result counts against a manually pre-filtered corpus) whether the filter is applied before or after the internal top-k truncation.

- [ ] **Step 3: write the decision doc**

Create `docs/spikes/fulltext.md` with sections for: analyzer registration mechanism (with the actual working code from Step 2), confirmed result tuple shape for `:display :refs+scores`, confirmed behavior of `:index-position?`, confirmed co-existence of fulltext+embedding on one attribute (or the workaround if it doesn't work), and the `:doc-filter` timing finding (this directly feeds T0.5's decision on over-fetch vs. pre-filter, and resolves the `⚠️ VERIFY` in `SPEC.md` §9.3).

- [ ] **Step 4: commit**

```bash
git add docs/spikes/fulltext.md docs/decisions.md
git commit -m "T0.4: spike CJK analyzer registration + fulltext query shape"
```

---

### Task 5: Spike — ACL query latency at scale (T0.5)

**Files:**
- Create: `docs/spikes/acl-query-perf.md`, a throwaway synthetic-data generator script (e.g. `dev/spikes/gen_synthetic_corpus.clj` or inline at the REPL — doesn't need to be production code).

**Interfaces:**
- Consumes: `hybridrag.db.schema` (Task 1) as the schema shape for the synthetic index.dtlv, and whatever `:chunk/index-text` definition Tasks 3–4 landed on.
- Produces: a p50 latency number for the ACL-filtered lexical query pattern in `SPEC.md` §9.3, and either a confirmation that over-fetch+join is fast enough or a `docs/decisions.md` entry proposing the `:doc-filter` alternative — consumed by Phase 2's lexical/semantic channel implementation (T2.1).

- [ ] **Step 1: generate synthetic data**

At the REPL or a scratch script, open a throwaway `index.dtlv` with the real (or best-current-guess-from-Task-3/4) schema, and generate: 10,000 synthetic docs, 100,000 synthetic chunks (~10 chunks/doc, short Traditional-Chinese filler text is fine — content doesn't matter, only volume and attribute shape), 50 synthetic group names, and effective-groups assigned so each doc has 1-3 of the 50 groups (roughly uniform random is fine — this doesn't need to model realistic org structure).

- [ ] **Step 2: measure the query pattern from `SPEC.md` §9.3**

Run (with timing, e.g. `criterium` if already on the classpath, or a manual loop of `System/nanoTime` over ~50 runs with different query strings/group sets to avoid JIT-warmup skew on a single query) the exact query shape:

```clojure
(d/q '[:find ?cid ?score
       :in $ ?q [?g ...]
       :where
       [(fulltext $ :chunk/index-text ?q {:top 200 :display :refs+scores})
        [[?e _ _ ?score]]]
       [?e :chunk/doc ?d]
       [?d :doc/effective-groups ?g]
       [?e :chunk/id ?cid]]
     index-db query-text user-groups)
```

(Adjust the destructuring to whatever Task 4 found the real `:refs+scores` shape to be.) Vary the number of groups a synthetic user has (1 group vs. 3 vs. all 50) since that changes join fan-out. Record p50 across runs for each case.

- [ ] **Step 3: write the decision doc**

Create `docs/spikes/acl-query-perf.md` with the measured p50 numbers, the machine/JVM specs it was measured on, and one of two outcomes:
- **p50 < 100ms**: note it and move on — `SPEC.md`'s over-fetch + Datalog join approach (§9.3) is confirmed fast enough, no change needed.
- **p50 ≥ 100ms**: per `SPEC.md` T0.5's AC, this needs a `docs/decisions.md` entry proposing an alternative (e.g. pre-filtering via `:doc-filter` if Task 4 confirmed it applies before top-k truncation) — write that entry, and note in this doc that Phase 2's T2.1 must implement the alternative, not the spec's original query shape.

- [ ] **Step 4: commit**

```bash
git add docs/spikes/acl-query-perf.md docs/decisions.md
git commit -m "T0.5: spike ACL-filtered query latency at 100k chunks/50 groups"
```

---

## Phase 0 exit check

Before moving to Phase 1 (`SPEC.md` §17), confirm every AC in the spec's own Phase 0 section is actually true, not just "the tasks above were followed":

- [ ] `bb test` and `bb lint` both pass on the full Phase 0 state (all 5 tasks' changes together, not just per-task).
- [ ] `docs/spikes/embedding.md`, `docs/spikes/fulltext.md`, `docs/spikes/acl-query-perf.md` all exist and state a clear decision, not just raw notes.
- [ ] `docs/decisions.md` has an entry for every place a spike or this plan overrode `SPEC.md`'s draft text.
- [ ] `bb vllm:check` has been run at least once against real vLLM endpoints (even if that happened after Task 2, before Phase 1 starts) — if it still hasn't, that blocks Phase 1's ingestion work from being embedding-testable, so resolve it now.

Phase 1 (`SPEC.md` T1.1–T1.5) is **not** planned in this document — its file-level design (especially the real `index-schema` and the CJK analyzer implementation in `search/analyzer.clj`) depends on what Tasks 3 and 4 above actually find. Write Phase 1's plan as a fresh `docs/superpowers/plans/<date>-phase1-ingestion.md` once this Phase 0 plan is fully executed, using the spike docs as its input instead of `SPEC.md`'s draft guesses.
