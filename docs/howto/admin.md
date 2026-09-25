# Administrator guide

English | [繁體中文](admin.zh-TW.md)

Audience: the people who manage accounts, document permissions and ingests. For deployment and backups, see the [Operations guide](ops.md).

**Most admin work happens on the command line and in files, not in the web UI.** The web `/admin` page offers only three things: running an ingest, viewing the latest ingest report, and browsing traces. Accounts and groups are managed with `bb` tasks; document permissions are written in files in the corpus directory.

Run all the commands below from the project directory; they find `app.dtlv` / `index.dtlv` through `DATA_DIR`. There is no `bb` in the production container; use the `java … -m` form from the [Operations guide](ops.md#deployment-kamal) instead. You can run `bb user:*` and `bb token:*` directly while the server is running; to ingest the corpus while the server is running, use the button in the web UI instead (see [Ingesting the corpus](#ingesting-the-corpus)).

## Users and groups

```bash
bb user:create alice --groups all,hr          # Create a user (no password set yet)
bb user:create admin --admin                  # Admin: can read all documents and open /admin
bb user:passwd alice                          # Set or change the password (entered twice interactively, at least 8 characters)
bb user:groups alice all,hr,finance           # "Replace" the whole group set with the new list
```

- Groups are arbitrary strings and must match the names used in `read_groups` in the corpus exactly.
- Group changes **take effect immediately**: every request re-reads the user record, so there is no need to wait for the user to log in again.
- After a password change, that user's web logins on **all devices** are invalidated immediately. The same happens when users log out themselves.
- A web login is valid for 8 hours (`SESSION_MAX_AGE_HOURS`).

### Many users at once: `bb user:import`

```clojure
;; users.edn — same format as eval/users.edn
{"alice" {:groups #{"all" "hr"} :admin? false}
 "bob"   {:groups #{"all" "engineering"}}
 "admin" {:groups #{} :admin? true}}
```

```bash
bb user:import users.edn --dry-run   # show what would change, write nothing
bb user:import users.edn
```

- Users in the file that do not exist are created with a **random password printed once**; write it down (change it later with `bb user:passwd`).
- Existing users get the file's groups and admin flag; their passwords are not touched. Running it again with the same file changes nothing.
- Users **not** in the file are listed as "不在檔案中（未更動）" (not in the file, unchanged) and are never deleted.
- If anything in the file is wrong, nothing is written and every problem is listed by user name.
- Point `bb eval --users` at the same file so eval runs as the same people the server knows.

### API tokens (for scripts or CLIs)

```bash
bb token:create alice --label "報表腳本"   # Shown only once, save it now; also prints the prefix used to revoke it
bb token:revoke zHLGL9IT                    # Revoke by prefix
```

A token calls `/api/v1/*` with `Authorization: Bearer <token>` and has the same permissions as its user. Tokens do not expire, and neither logging out nor changing the password affects them; revoke a token when it is no longer used.

## Document permissions

Permissions are written in files in the corpus directory and are **computed at ingest time and written into the index**. After editing a permissions file, you must ingest again for the change to take effect.

### Directories: `_collection.edn`

```clojure
;; corpus/_collection.edn — the root
{:name "全公司" :read-groups ["all"]}

;; corpus/hr/_collection.edn
{:name "人資" :read-groups ["hr"]}

;; corpus/hr/investigations/_collection.edn — a narrower subfolder
{:name "申訴調查" :read-groups ["hr-lead"]}
```

- For each directory, the nearest `_collection.edn` with `:read-groups` going upward applies (including the directory's own). Subdirectories without a declaration inherit the setting from above.
- If the root directory has no declaration, `ROOT_READ_GROUPS` is used; it defaults to empty, meaning only admins can read.
- A subdirectory's declaration **replaces** the one above it. In the example above, only `hr-lead` can read `hr/investigations/`; the `hr` group cannot.

### Single documents: frontmatter `read_groups`

```markdown
---
title: 2025 尾牙活動公告
read_groups: [all]
---
```

- This document sits under `hr/announcements/`, but because it declares `read_groups`, **only that is used**. It is an **override, not a union**: the result is that `all` can read it, and the `hr` group is not added back by the directory setting (`hr` members who are also in `all` can read it).
- `read_groups: []` means no one except admins can read it.
- Write the list on one line: `read_groups: [hr, all]`, once, not indented, with a half-width colon and no quotes. The block must start on the first line of the file with `---` and end with a line that is only `---`. Anything else that looks like `read_groups` before the first heading — a YAML block list, a bare word (`read_groups: hr`), a misspelt key (`read_group`, `read-groups`), a blank line before `---`, a full-width colon `：` — is an ingest error for that document.
- Files or directories whose names start with `.` or `_` are ignored; you can use `_drafts/` for drafts that are not yet public.

**Check the result with `bb acl:report`** after an ingest:

```bash
bb acl:report                    # users from the server (app.dtlv)
bb acl:report --users users.edn  # or from a users file, e.g. the one for bb eval
bb acl:report --docs             # also one line per document: groups | readers
```

It lists, per group, how many documents use it and which users hold it, and per user how many documents they can read (computed with the same function retrieval filters with). Read the `[WARN]` lines: a group that documents use but no user holds is usually a typo (in the corpus or in the users file), and the documents that use it can then be read only by admins.

**Mistakes fail closed.** A `_collection.edn` that cannot be parsed, holds more than one map, is not a map, has a key other than `:name` / `:read-groups`, or whose `:read-groups` is not a list of strings, a settings file with a look-alike name (`_Collection.edn`, `collection.edn`), and a malformed frontmatter `read_groups`, keep the affected documents out of the index (a copy indexed earlier is removed). They are listed under `errors` in the ingest report with the reason; fix the file and ingest again. The server log also has one `WARN [INGEST] acl fail-closed: <path> …` line per such document. A document that fails to re-ingest for another reason (for example the embedding endpoint is down) is removed from the index as well, and comes back with the next successful ingest. `bb doctor` checks the `_collection.edn` files before you ingest.

When only permissions change and the content does not, a re-ingest does not recompute embeddings, so it is fast; the report counts such documents as `acl-updated`.

## Ingesting the corpus

There are two ways, with the same result; both are incremental ingests that process only added, modified or deleted files.

- Web: log in as admin → "管理" (Admin) at the top → "執行增量 ingest" (Run incremental ingest). While it runs, the status updates every 2 seconds; only one ingest can run at a time, and pressing the button again shows "已有 ingest 在執行" (An ingest is already running).
- Command line: `bb ingest`, **only when the server is stopped**: two processes writing to the index at the same time has not been verified, and while the server is running you should use the web button or `POST /api/v1/ingest` (admin token). For a full rebuild use `bb reindex`, which likewise requires stopping the server first (see the [Operations guide](ops.md#rebuilding-the-index)).

If `CORPUS_DIR` does not exist, both ways refuse to run and leave the index as it is (a missing directory would otherwise look like "every document was deleted"); `bb reindex` checks before deleting anything.

Report summary (the web page's "最近一次報告" (Latest report), or `DATA_DIR/ingest-reports/<timestamp>.edn`):

```
docs: 22 added, 0 updated, 0 acl-updated, 0 skipped, 0 deleted, 0 errors
chunks: 119 written this run, 119 in index, longest 378 est. tokens
unresolved links: 0, index lag: 0, elapsed: 7336 ms
```

- `errors`: files that could not be parsed, each listed with its path and reason; the other files are ingested as usual.
- `unresolved links`: a `[text](path.md)` or `[[page name]]` whose target document cannot be found. This is not an error, but it means one fewer edge in the link graph.
- `index lag`: the amount of indexing still being built in the background when the ingest finishes; it should drop to zero quickly.

## Traces (query logs)

Every `/search` and `/ask` writes one trace, stored in `app.dtlv`. Requests that returned 503 because a model endpoint failed also leave one (recording which endpoint failed); malformed input (400) or program errors (500) do not.

- Web: the "管理" (Admin) page lists the latest 50 (time, user, type, query); click one to see the content of each stage: the ranking from each channel, RRF, rerank scores, the size of the prompt used, and degraded-mode and error information.
- API: `GET /api/v1/traces/<id>`. Only admins or the trace's owner can see it; everyone else gets 404.
- Traces record only chunk ids and scores, not document text. The full text of the answer is stored in `:trace/answer`.
- When a user sees "問答服務暫時無法使用（chat）。 trace …" (The Q&A service is temporarily unavailable (chat). trace …), use that id to find which model endpoint failed.

## Backups

Accounts, groups, tokens and traces are all in `DATA_DIR/app.dtlv`. For how to back it up, see the [Operations guide](ops.md#backups).
