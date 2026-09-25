(ns replware.levinrag.ingest.markdown-test
  "Markdown → section list tests (SPEC.md §7.3, T1.2 AC):
   ATX/Setext mixed, no heading, frontmatter only, deep nesting."
  (:require [clojure.test :refer [deftest is testing]]
            [replware.levinrag.ingest.markdown :as md]))

(defn- span-text [s {:keys [char-start char-end]}]
  (subs s char-start char-end))

(deftest test-atx-and-setext-mixed
  (let [s "# Top\nintro\n\nSetext Two\n----------\nbody\n\n### Three\ndeep\n\nOther\n=====\nlast"
        secs (:sections (md/parse-markdown s))]
    (is (= [[1 "Top"] [2 "Setext Two"] [3 "Three"] [1 "Other"]]
           (mapv (juxt :level :heading) secs)))
    (testing "trail is the chain of ancestor headings, including self"
      (is (= [["Top"] ["Top" "Setext Two"] ["Top" "Setext Two" "Three"] ["Other"]]
             (mapv :trail secs))))))

(deftest test-trail-pops-to-sibling-level
  (let [secs (:sections (md/parse-markdown "# A\n## B\n### C\n## D\nx"))]
    (is (= ["A" "D"] (:trail (last secs))))))

(deftest test-no-heading-is-single-level-0-section
  (let [s "Just a paragraph.\n\nAnother one."
        secs (:sections (md/parse-markdown s))]
    (is (= 1 (count secs)))
    (is (= 0 (:level (first secs))))
    (is (= [] (:trail (first secs))))
    (is (= [:paragraph :paragraph] (mapv :type (:blocks (first secs)))))
    (is (= s (span-text s (first secs))))))

(deftest test-content-before-first-heading-goes-to-level-0
  (let [secs (:sections (md/parse-markdown "preface\n\n# One\nbody"))]
    (is (= [0 1] (mapv :level secs)))
    (is (= "preface" (let [s "preface\n\n# One\nbody"]
                       (span-text s (first (:blocks (first secs)))))))))

(deftest test-only-frontmatter-yields-no-sections
  (let [res (md/parse-markdown "---\ntitle: Only FM\n---\n")]
    (is (= [] (:sections res)))
    (is (= "Only FM" (get-in res [:frontmatter :title])))))

(deftest test-frontmatter-is-not-a-setext-heading
  (let [s "---\ntitle: X\n---\n# Real\nbody"
        secs (:sections (md/parse-markdown s))]
    (is (= [[1 "Real"]] (mapv (juxt :level :heading) secs)))
    (is (= "# Real\nbody" (span-text s (first secs))))))

(deftest test-deep-nesting-beyond-six-is-body-text
  ;; CommonMark has no level 7+: "#######" is a paragraph inside the H6 section.
  (let [s "# 1\n## 2\n### 3\n#### 4\n##### 5\n###### 6\n####### 7\n######## 8"
        secs (:sections (md/parse-markdown s))]
    (is (= [1 2 3 4 5 6] (mapv :level secs)))
    (is (= ["1" "2" "3" "4" "5" "6"] (:trail (last secs))))
    (is (= "####### 7\n######## 8" (span-text s (first (:blocks (last secs))))))))

(deftest test-block-types-and-offsets
  (let [s "# T\npara 中文\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```clj\n(x)\n```\n\n- i1\n- i2\n\n> q\n\n---\n\nend"
        sec (first (:sections (md/parse-markdown s)))]
    (is (= [:paragraph :table :code :list :blockquote :paragraph]
           (mapv :type (:blocks sec)))
        "thematic break is dropped")
    (is (= ["para 中文" "| a | b |\n|---|---|\n| 1 | 2 |" "```clj\n(x)\n```"
            "- i1\n- i2" "> q" "end"]
           (mapv #(span-text s %) (:blocks sec))))
    (is (= s (span-text s sec)) "section spans heading through last block")))

(deftest test-heading-plain-text
  (let [secs (:sections (md/parse-markdown "## Use `foo` and **bar**\nx"))]
    (is (= "Use foo and bar" (:heading (first secs))))))

(deftest test-heading-without-body
  (let [secs (:sections (md/parse-markdown "# A\n# B\nb"))]
    (is (= [] (:blocks (first secs))))
    (is (= ["A" "B"] (mapv :heading secs)))))

(deftest test-doc-title-precedence
  ;; frontmatter title → first H1 → filename (SPEC.md §7.3)
  (is (= "FM" (md/doc-title (md/parse-markdown "---\ntitle: FM\n---\n# H\n") "a/f.md")))
  (is (= "H" (md/doc-title (md/parse-markdown "## sub\n# H\n") "a/f.md")))
  (is (= "f" (md/doc-title (md/parse-markdown "## sub only\n") "a/f.md"))))

;; --- Frontmatter parsing ---

(deftest test-parse-frontmatter-extracts-title
  (let [md "---
title: \"Employee Handbook\"
tags: [hr, policy]
---
Content here."
        fm (md/parse-frontmatter md)]
    (is (= "Employee Handbook" (:title fm)))
    (is (= ["hr" "policy"] (:tags fm)))))

(deftest test-parse-frontmatter-read-groups
  (let [md "---
title: Leave Policy
read_groups: [all, hr]
---
Leave days are calculated..."
        fm (md/parse-frontmatter md)]
    (is (= ["all" "hr"] (:read_groups fm)))))

(deftest test-parse-frontmatter-no-frontmatter
  (is (nil? (md/parse-frontmatter "No frontmatter here.")))
  (is (nil? (md/parse-frontmatter "# Title\n\nContent"))))

(deftest test-parse-frontmatter-empty-frontmatter
  (is (= {} (md/parse-frontmatter "---\n---\nContent"))))

(deftest test-parse-frontmatter-extra-fields
  ;; Non-standard fields should be stored as-is in the frontmatter map.
  (let [md "---
title: API Spec
custom_key: custom_value
version: 2.5
---
Content"
        fm (md/parse-frontmatter md)]
    (is (= "API Spec" (:title fm)))
    (is (= "custom_value" (:custom_key fm)))
    (is (= 2.5 (:version fm)))))

(deftest test-links
  (let [s "# T\nsee [a](../hr/leave.md#x) and [[請假規定]] and [[Page|alias]].\n\n`[[in-code]]` [web](https://x.y)\n\n```\n[[fenced]] [c](c.md)\n```\n\n| a |\n|---|\n| [[Cell]] [t](t.md) |\n"]
    (is (= {:paths ["../hr/leave.md#x" "https://x.y" "t.md"]
            :wiki ["請假規定" "Page" "Cell"]}
           (:links (md/parse-markdown s))))))
