# Spike: commonmark-java source spans (T1.2 Step 1)

Verified at REPL 2026-09-24 against `org.commonmark/commonmark 0.24.0`.

## Version

datalevin 1.1.0 already pulls commonmark **0.24.0** transitively
(via `io.github.nextjournal/markdown 0.7.225`). We pin all three
artifacts (`commonmark`, `-ext-gfm-tables`, `-ext-yaml-front-matter`)
to 0.24.0 so a top-level declaration does not silently upgrade
datalevin's copy. Latest on Maven Central at the time: 0.30.0.

## API shape

The plan's snippet (`org.commonmark` Clojure ns, "source-spans extension")
does not exist. Source spans are a built-in parser option:

```clojure
(-> (Parser/builder)
    (.extensions [(YamlFrontMatterExtension/create) (TablesExtension/create)])
    (.includeSourceSpans IncludeSourceSpans/BLOCKS)
    .build)
```

`Node.getSourceSpans` → list of `SourceSpan`, one per source line:
`getLineIndex`, `getColumnIndex`, `getInputIndex`, `getLength`.

- `getInputIndex` is a **Java string (UTF-16) index** into the parser input,
  so `(subs md start end)` works directly (`中文` = 2 units).
- Block extent = first span's `inputIndex` → last span's `inputIndex + length`.

Observed on `"---\ntitle: T\n---\nintro 中文\n\n# H1\npara\n\nSetext\n------\n..."`:

| Node | spans `[input len line]` |
|------|--------------------------|
| YamlFrontMatterBlock | `[0 3 0] [4 8 1] [13 3 2]` |
| Paragraph | `[17 8 3]` |
| Heading 1 | `[27 4 5]` |
| Heading 2 (Setext) | `[38 6 8] [45 6 9]` |
| TableBlock | one span per row |
| FencedCodeBlock | one span per line incl. fences |

## Consequences

- Without `YamlFrontMatterExtension`, `title: x\n---` parses as a Setext H2.
- `#######` (7+) is a Paragraph — CommonMark has no level 7, so the plan's
  "clamp > 6" case cannot occur; it is body text of the enclosing section.
