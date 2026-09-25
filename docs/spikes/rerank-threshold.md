# Spike: calibrating `:retrieve/rerank-min-score`

Date: 2026-09-25. Code: `dev/spikes/rerank_threshold.clj` (`collect`,
`sweep`, `best-answer-scores`). Models: bge-m3 (LM Studio), bge-reranker-
v2-m3 Q8_0 (llama.cpp, **raw logits**), SPEC §5 defaults otherwise
(`final-k` 8, `rerank-input` 40). Book-corpus rows are ids and scores
only; the corpus stays in `no-commit/` (see `cjk-analyzer.md`).

## Why

With no threshold the top 8 reranked chunks always go into the prompt.
The 2026-09-24 walkthrough showed three costs: an answerable question
carried 6 negatively scored chunks (longer prompt; prefill dominates
latency, see `VLLM_SETUP.md`), a question with no readable answer still
spent 13 s generating, and the model then produced a hedging answer with
a citation instead of the §10.3 no-evidence reply.

## Method

For every question, run the full pipeline and keep each candidate's
rerank score and whether it is an answer (an expected section / doc).

- Sample corpus: `eval/questions.edn` — 31 answerable, 7 ACL-negative
  (the user cannot read the answer doc: the no-evidence reply is right).
- Book corpus: 38 answerable (section labels) + q21, q27 (labeled
  unanswerable) as controls.
- For threshold t: *lost* = answerable questions that had an answer among
  the selected 8 without a threshold but not with t; *control empty* =
  control questions left with no passage (→ no-evidence reply without
  calling chat); *mean kept* = passages sent to the model.

## Results

Best answer-chunk score per answerable question:

| corpus | range | median |
|---|---|---|
| sample | 0.0 … 8.8 (all ≥ 0) | ≈ 3.4 |
| book | −8.0 … 4.0 | ≈ −3 |

Highest score among control questions: sample −10.1 … −5.0; book −7.6,
−0.4.

| t | sample lost | sample control empty (/7) | sample mean kept | book lost (/33) | book control empty (/2) | book mean kept |
|---|---|---|---|---|---|---|
| none | 0 | 0 | 8.0 | 0 | 0 | 8.0 |
| −8 | 0 | 3 | 4.8 | 0 | 0 | 7.9 |
| **−7** | **0** | **5** | **4.1** | **0** | **1** | **7.5** |
| −6 | 0 | 6 | 3.2 | 3 | 1 | 6.4 |
| −5 | 0 | 6 | 2.5 | 6 | 1 | 5.0 |
| −4 | 0 | 7 | 2.2 | 11 | 1 | 3.6 |
| −1 | 0 | 7 | 1.6 | 25 | 1 | 1.2 |
| 0 | 0 | 7 | 1.5 | 30 | 2 | 0.5 |
| 1 | 4 | 7 | 1.1 | 32 | 2 | 0.2 |

(book: 33 of 38 questions have an answer in the top 8 without a
threshold; *lost* counts against those. Degraded rerank calls: 0.)

## Findings

1. **The score scale depends on the question style.** Concrete questions
   (sample: "特休天數怎麼計算？") put their answers at ≥ 0; abstract,
   paraphrased reader questions (book) score correct passages around −3.
   No single threshold separates answers from noise on both.
2. On the sample corpus alone any t in −4 … 0 is perfect: every
   ACL-negative question gets the no-evidence reply and ≈ 2 passages
   remain. On the book corpus the same values lose 11–30 of 33 answers.
3. **−7.0 is the highest value that loses no answer on either corpus.**
   It still halves the sample prompt (8 → 4.1 passages) and turns 5 of 7
   ACL-negative questions into the no-evidence reply, but barely changes
   the book corpus (8 → 7.5).
4. The scale is the backend's: llama.cpp returns raw logits. A backend
   that returns 0–1 scores (sigmoid) makes −7 a no-op; recalibrate with
   this spike after changing the reranker backend.

## Decision

`:rerank-min-score` = **−7.0** in `resources/config.edn`, overridable
with `VLLM_RERANK_MIN_SCORE` (conservative: no lost answers on either
corpus). Revisit with real user questions (the 5–10 control questions
from the user): if they look like the sample corpus, a value around −4
removes most noise and triggers no-evidence reliably.
