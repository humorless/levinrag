# Spike: CJK analyzer — bigram vs HanLP vs hybrid

Date: 2026-09-24. HanLP `com.hankcs/hanlp portable-1.8.6` (latest on Maven
Central, 2024-12). Reproduce:

```bash
clojure -M:jvm-opts:test:dev:spike-hanlp -e "(require 'spikes.cjk-analyzer) (spikes.cjk-analyzer/-main) (spikes.cjk-analyzer/probe-main)"
```

Code: `dev/spikes/cjk_analyzer.clj`. HanLP is only on the `:spike-hanlp`
alias; nothing in `src/` depends on it.

## Why

SPEC §8 states "HanLP 1.x > Jieba" as the tokenizer priority, while
§8.1–§8.3 specify an overlapping-bigram analyzer. T2.1 shipped the bigram
analyzer; this spike measures the alternative before the user decides.

## Candidates

| id | CJK text | ASCII / identifiers |
|---|---|---|
| bigram | §8.1 overlapping bigrams (current) | §8.1 runs + connector fragments |
| hanlp | HanLP `TraditionalChineseTokenizer` words | §8.1 rules (kept) |
| hybrid | bigrams + HanLP words of length ≥ 2 | §8.1 rules |

## Finding 1 — plain HanLP cannot read Traditional Chinese

`HanLP.segment("員工請假規定")` → `員/工/請/假/規/定` (single characters):
the portable dictionary is Simplified. `TraditionalChineseTokenizer`
(convert internally, map words back to the original offsets) works:
`員工 / 請假 / 規定`. All numbers below use it.

## Finding 2 — HanLP breaks identifiers and this corpus's domain words

| text | HanLP (Traditional) | bigram |
|---|---|---|
| 特休天數 | 特 / 休 / 天數 | 特休 休天 天數 |
| 料號編碼規則 | 料 / 號 / 編碼 / 規則 | 料號 … |
| 交期、表單、統編 | 交/期、表/單、統/編 | intact bigrams |
| 識別證 | 識別 / 證 | 識別 別證 |
| 申訴人 | **申訴人** | 申訴 訴人 |
| SKU-B2210 | SKU-B / 2210 | sku-b2210, sku, b2210 |
| GA-03 | GA- / 03 | ga-03, ga, 03 |

HanLP must never handle ASCII identifiers; the spike keeps §8.1 for them in
every candidate.

## Finding 3 — retrieval on the sample corpus

**Eval questions (lexical channel only, 31 questions with expected docs):**
all three tie — top-1 doc 30/31, MRR@10 0.978 / 0.984 / 0.978, recall@5
1.0. The only difference is id-07 (`v2.7.3`: rank 3 / 2 / 3). The sample
corpus is too easy to separate them (same saturation as T2.6).

**Exact-term probe** (20 domain terms queried alone as admin; of the
top-10 lexical hits, the share whose text contains the term = P@10, and
the share of the containing chunks that were found = R@10):

| | bigram | hanlp | hybrid |
|---|---|---|---|
| mean P@10 | **0.87** | 0.84 | 0.87 |
| mean R@10 | **0.99** | 0.98 | 0.99 |

- HanLP better: 申訴人 1.00 vs 0.25, 試用期 1.00 vs 0.75 (3-char words
  that bigrams split into overlapping pairs).
- HanLP worse: 特休 0.80, 交期 0.60, 識別證 0.50, 補班 0.75, 表單 0.90
  (all 1.00 with bigrams) — words not in its dictionary, split into single
  characters that match everywhere.
- HanLP matches more chunks per question on average (36.4 vs 20.4 raw
  hits): single characters are low-precision terms.
- **hybrid = bigram** on every term: most HanLP words are two characters
  and coincide with a bigram at the same offset, so the union adds only
  3+ character words, which did not change the top 10 here.

## Cost

- Jar 8.0 MB (portable, dictionary included); first segmentation call
  ~230 ms (dictionary load). Sample-corpus ingest time showed no usable
  difference: it is dominated by JVM warm-up (whichever analyzer runs
  first is slowest — 1054 ms for bigram in one run, 257 ms for HanLP in
  another). Not benchmarked properly; irrelevant at this corpus size.
- A custom dictionary (`CustomDictionary.add`) could fix 特休/料號/交期,
  but it is per-corpus maintenance, and a missed word silently degrades to
  single characters.

## Conclusion (input for the user's decision — not decided here)

On this corpus HanLP does not beat the bigram analyzer: slightly lower
precision overall, better only on 3-character words, worse on unlisted
domain vocabulary, plus a dependency and a dictionary to maintain. Hybrid
adds nothing measurable. The evidence is limited by the small corpus; a
larger eval corpus (backlog) is needed for a conclusive comparison.
