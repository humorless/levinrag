# User guide

English | [繁體中文](user.zh-TW.md)

LevinRAG answers questions using only the company documents **you have permission to read**, and marks the source `[n]` after every sentence. When the answer is not in the data, it tells you so directly instead of guessing.

The web UI is in Traditional Chinese, so this guide quotes its labels and messages in Chinese, with an English gloss.

The examples below use the sample corpus. alice belongs to the `all` and `hr` groups; bob belongs to the `all` and `engineering` groups.

## Logging in

Open the site and enter the username and password your administrator gave you.

- A login is valid for 8 hours; after that you need to log in again.
- When you press "登出" (Log out) at the top of the page, your logins on **all devices** are invalidated together. The same happens when you change your password.
- If you mistype the username or password, the screen only shows "帳號或密碼錯誤" (Incorrect username or password); it does not tell you which one was wrong.

## Asking a question

On the "問答" (Q&A) page, type your question in the input box (at most 1000 characters) and press "送出" (Submit). An answer usually takes 10–30 seconds; meanwhile the page shows "產生回答中…" (Generating answer…).

Example: alice asks "特休天數怎麼計算？" (How are annual leave days calculated?)

The answer (UI output, shown as is) says that annual leave depends on seniority — 3 days after 6 months, 7 days after 1 year — with citations, followed by the sources list:

> 特休天數依照年資計算，具體規則如下：
> - 到職滿 6 個月未滿 1 年：3 天 [1]
> - 滿 1 年未滿 2 年：7 天 [1]
> - …
>
> 來源
> [1] 請假規定　請假規定 > 特休 > 天數計算
> 　　（摘錄）… 開啟文件

- The `[1]` in the answer is clickable: it scrolls to item 1 under "來源" (Sources) below and draws a border around it.
- Each source lists the document title, the section trail and an excerpt; press "開啟文件" (Open document) to see the original.
- Only sources the answer actually cites are listed.

## Viewing the original

"開啟文件" (Open document) opens the document viewer and scrolls to the cited passage, which is highlighted.

- If the document was modified after it was indexed, the page shows only "文件已更新，重新匯入後才能檢視。" (The document has been updated; it can be viewed after the next ingest.) Ask an administrator to run an ingest. Administrators see the current file with the notice "文件在建立索引後已變更，標示的位置可能不準確。" (The document has changed since it was indexed; the highlighted position may be inaccurate.)
- For a document you do not have permission to read, whether you open it directly by URL or click through from elsewhere, you only see "找不到頁面" (Page not found). The system does not reveal whether that document exists.

## Permissions: same question, different people see different things

When bob asks the same "特休天數怎麼計算？" (How are annual leave days calculated?), the documents under `hr/` do not appear in his sources and are not sent to the model. This is not poor answer quality; it is the permissions design:

- bob may get a shorter answer based on public documents (for example the employee handbook), or "在你有權限存取的資料中找不到相關內容。" (No relevant content was found in the data you have access to.)
- Even if a public document contains a link to `hr/leave.md`, bob only sees "找不到頁面" (Page not found) when he clicks it.
- `hr/announcements/year-end-party.md` (the year-end party announcement) sits under `hr/`, but it is open to the `all` group itself, so bob can see it.

If you think you should be able to see certain documents, ask your administrator to check which groups you belong to.

## Special messages

| On-screen message | Meaning |
|---|---|
| 在你有權限存取的資料中找不到相關內容。 (No relevant content was found in the data you have access to.) | No sufficiently relevant passages were found, so the system did not call the model. Rephrase or add keywords (form numbers, part numbers) and try again. |
| 重排序失敗，結果依 RRF 排序。 (Reranking failed; results are ordered by RRF.) | The ranking model is temporarily unavailable. An answer was still produced, but the ordering of the sources may be worse. |
| 問答服務暫時無法使用（chat）。 trace … (The Q&A service is temporarily unavailable (chat). trace …) | The model service is temporarily unavailable. Give the trace number on the screen to your administrator so they can look up the cause. |
| 模型沒有產生回答，請稍後再試。 (The model produced no answer; please try again later.) | The model returned empty content. |

## Debug panel (advanced)

Check **Debug** next to "送出" (Submit) and then submit; a table appears below the answer listing every candidate passage from this retrieval. Use it to understand "why these passages":

| Column | Meaning |
|---|---|
| chunk id | `文件路徑::段落序號` (document path::passage number) |
| lexical | Rank in keyword matching ("–" means this channel did not find it) |
| semantic | Rank by semantic similarity |
| RRF | Score after fusing the two channels' ranks; higher comes first |
| graph | ✓ means it was brought in by following links between documents |
| rerank | Score from the ranking model (raw logit, may be negative; higher is more relevant) |
| 選中 (Selected) | ✓ (green background) means this passage was put into the material given to the model |

Below the table are the time taken by each stage (ms), the models used and the token counts; `flags` or `degraded` are shown too when present. For example, `acl-starvation` means you can read too few documents, so little is left of the retrieval results after permission filtering. Every question leaves a record (trace), and the numbers in the Debug panel match that record.
