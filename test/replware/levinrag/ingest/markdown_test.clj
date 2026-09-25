(ns replware.levinrag.ingest.markdown-test
  "Markdown → section list tests (SPEC.md §7.3, T1.2 AC):
   ATX/Setext mixed, no heading, frontmatter only, deep nesting."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

(deftest test-frontmatter-acl-problem
  (let [fm #(str "---\ntitle: x\n" % "\n---\n# x\nbody")]
    (testing "valid or absent read_groups"
      (doseq [ok ["read_groups: [hr]" "read_groups: [hr, all]" "read_groups: [\"hr\"]"
                  "read_groups: []" "tags: [a]"]]
        (is (nil? (md/frontmatter-acl-problem (fm ok))) ok))
      (is (nil? (md/frontmatter-acl-problem "# no frontmatter"))))
    (testing "anything that would not be applied as written is a problem, never a fallback
              to the directory's groups"
      (doseq [[bad expect] [["read_groups: hr" #"清單"]
                            ["read_groups: [hr, 1]" #"清單"]
                            ["read_groups:" #"沒有值"]
                            ["read_groups:\n  - hr" #"沒有值"]
                            ["read_group: [hr]" #"read_group"]
                            ["read-groups: [hr]" #"read-groups"]
                            ["Read_Groups: [hr]" #"Read_Groups"]
                            ["readgroups: [hr]" #"readgroups"]]]
        (is (re-find expect (str (md/frontmatter-acl-problem (fm bad)))) bad)))))

(deftest test-frontmatter-acl-problem-fails-closed-on-hidden-read-groups
  ;; review 2026-09-25 H1: each of these left read_groups unread, so the doc
  ;; silently took its directory's groups
  (let [bom "﻿"]
    (testing "still valid: the frontmatter is found and read_groups applied"
      (doseq [[label s] [["BOM before a good block" (str bom "---\nread_groups: [hr]\n---\n# x")]
                         ["--- inside a value" "---\ntitle: Q3---draft\nread_groups: [hr]\n---\n# x"]
                         ["... closes the block" "---\nread_groups: [hr]\n...\n# x"]
                         ["trailing space after ---" "--- \nread_groups: [hr]\n--- \n# x"]
                         ["a YAML comment line" "---\n# owner: hr\nread_groups: [hr]\n---\n# x"]
                         ["the body mentions read_groups after a heading"
                          "---\nread_groups: [hr]\n---\n# 權限\n\nread_groups: [all] 表示全公司"]
                         ["no frontmatter, body mentions it after a heading" "# 權限\n\nread_groups: [all]"]]]
        (is (nil? (md/frontmatter-acl-problem s)) label)
        (when (str/includes? s "read_groups: [hr]")
          (is (= ["hr"] (:read_groups (md/parse-frontmatter s))) label))))
    (testing "read_groups that would not be applied is a problem"
      (doseq [[label s] [["blank line before the block" "\n---\nread_groups: [hr]\n---\n# x"]
                         ["no closing line" "---\nread_groups: [hr]\n# x"]
                         ["full-width colon" "---\nread_groups：[hr]\n---\n# x"]
                         ["double-quoted key" "---\n\"read_groups\": [hr]\n---\n# x"]
                         ["single-quoted key" "---\n'read_groups': [hr]\n---\n# x"]
                         ["twice, last would win" "---\nread_groups: [hr]\nread_groups: [all]\n---\n# x"]
                         ["inside a block scalar" "---\nread_groups: [hr]\nnotes: |\n  read_groups: [all]\n---\n# x"]
                         ["indented" "---\nmeta:\n  read_groups: [hr]\n---\n# x"]
                         ["single-quoted items" "---\nread_groups: ['hr', 'all']\n---\n# x"]
                         ["plain text file" "read_groups: [hr]\n\n薪資表"]]]
        (is (string? (md/frontmatter-acl-problem s)) label)))))
