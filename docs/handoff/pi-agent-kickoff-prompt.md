繼續開發 hybridrag 專案（Datalevin-embedded RAG MVP）— Phase 1: Ingestion

## 工作目录

/Users/laurencechen/ForceUnion/levinrag

## 目前狀態

Phase 0（骨架 + 3 個 spike）已全部完成，包含最終 whole-branch review 跟一輪
fix wave，全部 clean。目前在 `main` branch，working tree clean，`bb test`／
`bb lint`／`bb fmt-check` 全過。Phase 1（Ingestion, T1.1–T1.5）完全還沒開始，
連 plan 文件都還沒寫。

## 請先讀這份交接文件，不要跳過

`docs/handoff/2026-09-22-phase0-to-phase1.md`

這份文件會告訴你：Phase 0 做了哪些決策（embedding 路徑、Datalevin vector
write-path 的一個真實 bug 跟修法、ACL 查詢效能的替代方案）、哪些文件要照
什麼順序讀（SPEC.md → docs/decisions.md → docs/spikes/*.md →
docs/datalevin_debug_notes.md → Phase 0 的 plan 文件）、Phase 1 實際要做
什麼、還有哪些沒解決的事項（例如：`bb vllm:check` 從沒對真實 vLLM 跑過、
一個沒被處理的 spec 修改需要人類決定）。

## 接下來要做的事

1. 讀完交接文件跟它指向的所有文件，建立完整上下文。
2. 依照 Phase 0 plan 文件（`docs/superpowers/plans/2026-09-22-phase0-
   skeleton-and-spikes.md`）留下的指示：Phase 1 的 plan **不要**照抄
   SPEC.md 的草稿（`⚠️ VERIFY` 標記的地方已經被 Phase 0 的 spike 推翻或
   修正過），要以三份 spike 文件 + decisions.md 的實際驗證結果為準。寫一份
   新的 `docs/superpowers/plans/<今天日期>-phase1-ingestion.md`，格式可以
   直接參考 Phase 0 那份 plan 文件（Global Constraints、每個 task 的
   Files/Interfaces/Steps）。
3. 這個專案有嚴格的規矩（都寫在交接文件跟 Phase 0 plan 的 Global
   Constraints 裡，這裡列重點）：
   - **絕對不能憑記憶猜 Datalevin API**——任何還沒被 Phase 0 文件驗證過的
     `datalevin.core`／`datalevin.storage` 呼叫，都要先用 `bb clj-repl`
     對 pin 死的 1.1.0 版本實測過才能寫進程式碼。這條紀律在 Phase 0 抓到
     一個真的 upstream bug（`:db.vec/domains` write-path），不要放鬆。
   - Code comment 用英文，使用者看得到的文字用繁體中文。
   - 一個 task 一個 commit，訊息格式 `T1.N: <summary>`。
   - 模糊的需求：挑最簡單、可逆的做法，記錄到 `docs/decisions.md`（原
     spec 文字 → 實際發現 → 做的選擇），不要停下來等人回答。
   - 超出當前 task AC 範圍的想法，寫進 `docs/backlog.md`，不要塞進程式碼。
4. 如果你的 harness 有裝 Superpowers 這類 skill（`writing-plans`、
   `subagent-driven-development`、`executing-plans`、`systematic-
   debugging` 等），照那套流程走；沒有的話，Phase 0 那份 plan 文件本身就是
   一個可以直接模仿的範本。

## 一個需要你留意、但不要自作主張處理的事

Commit `4ac13e2`（Phase 0 之前的一個 commit）直接改了 `SPEC.md` §8，加了
一個新的 `⚠️ VERIFY` 設計決策（CJK tokenizer 選 HanLP 1.x 優先於
Jieba），沒有對應的 `docs/decisions.md` 條目，commit message 也沒有掛
attribution trailer。上一個 session 認為這是需要人類（不是 coding agent）
決定要不要保留/修正的事，所以刻意沒有動它。你如果看到，不用驚訝，也不要
自己決定改掉，除非使用者明確要求。
