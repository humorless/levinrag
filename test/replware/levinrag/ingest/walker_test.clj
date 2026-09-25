(ns replware.levinrag.ingest.walker-test
  "Walker unit tests: file collection, ACL resolution, frontmatter parsing.

   Tests:
   - collect-markdown-files: extensions, skipping dotfiles
   - find-collection-edns: discover _collection.edn from filesystem
   - walk-corpus: full pipeline with ACL resolution"
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [replware.levinrag.ingest.walker :as walker]))

;; --- Extension filtering ---

(deftest test-acceptable-extension-md
  (is (true? (walker/acceptable-extension? "readme.md")))
  (is (true? (walker/acceptable-extension? "doc.markdown")))
  (is (true? (walker/acceptable-extension? "notes.txt")))
  (is (false? (walker/acceptable-extension? "readme.txtx")))
  (is (false? (walker/acceptable-extension? "noextension")))
  (is (false? (walker/acceptable-extension? "file.pdf")))
  (is (false? (walker/acceptable-extension? "README")))
  (is (false? (walker/acceptable-extension? ".hidden.md"))))

;; --- File metadata ---

(deftest test-file-meta
  (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "test-walker-meta")]
    (.mkdirs (io/file tmp-dir "sub"))
    (let [f (io/file tmp-dir "sub" "test.md")]
      (.createNewFile f)
      (let [fm (walker/file-meta tmp-dir f)]
        (is (= "sub/test.md" (:rel-path fm)))
        (is (string? (:abs-path fm)))
        (is (number? (:size fm)))
        (is (number? (:mtime fm))))
      (.delete f))
    (.delete (io/file tmp-dir "sub"))
    (.delete tmp-dir)))

(deftest test-find-collection-edns-from-fs
  ;; Discover _collection.edn from a temporary directory structure.
  (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "test-collection-edns")]
    (try
      (.mkdirs tmp-dir)
      (.mkdirs (io/file tmp-dir "hr"))
      (.mkdirs (io/file tmp-dir "hr" "payroll"))
      (.mkdirs (io/file tmp-dir "eng"))

      ;; Write _collection.edn files
      (spit (io/file tmp-dir "_collection.edn") "{:name \"Root\" :read-groups [\"all\"]}")
      (spit (io/file tmp-dir "hr" "_collection.edn") "{:name \"HR\" :read-groups [\"hr\"]}")
      (spit (io/file tmp-dir "hr" "payroll" "_collection.edn")
            "{:name \"Payroll\" :read-groups [\"hr\" \"finance\"]}")
      (spit (io/file tmp-dir "eng" "_collection.edn") "{:name \"Eng\" :read-groups [\"eng\"]}")

      (let [edns (walker/find-collection-edns tmp-dir)]
        (is (= {:name "Root",
                :read-groups ["all"]}
               (get edns "")))
        (is (= {:name "HR",
                :read-groups ["hr"]}
               (get edns "hr")))
        (is (= {:name "Payroll",
                :read-groups ["hr" "finance"]}
               (get edns "hr/payroll")))
        (is (= {:name "Eng",
                :read-groups ["eng"]}
               (get edns "eng"))))
      (finally
        ;; Cleanup
        (doseq [f (reverse (file-seq tmp-dir))]
          (.delete f))))))

(deftest test-find-collection-edns-empty-dir
  (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "test-empty-edns")]
    (try
      (.mkdirs tmp-dir)
      (let [edns (walker/find-collection-edns tmp-dir)]
        (is (= {} edns)))
      (finally
        (.delete tmp-dir)))))

;; --- Full walk ---

(deftest test-walk-corpus-structure
  ;; Walk a temp corpus dir with ACL structure, verify all files found.
  (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "test-walk-corpus")]
    (try
      (.mkdirs tmp-dir)
      (.mkdirs (io/file tmp-dir "hr"))
      (.mkdirs (io/file tmp-dir "eng"))

      ;; Write _collection.edn files
      (spit (io/file tmp-dir "_collection.edn") "{:name \"Root\" :read-groups [\"all\"]}")
      (spit (io/file tmp-dir "hr" "_collection.edn") "{:name \"HR\" :read-groups [\"hr\"]}")
      (spit (io/file tmp-dir "eng" "_collection.edn") "{:name \"Eng\" :read-groups [\"eng\"]}")

      ;; Write markdown files
      (spit (io/file tmp-dir "README.md") "Welcome to the company!")
      (spit (io/file tmp-dir "hr/leave.md") "---\ntitle: Leave Policy\n---\nLeave days...")
      (spit (io/file tmp-dir "hr/bonus.md") "---\ntitle: Bonus Policy\nread_groups: [hr, finance]\n---\nBonus info...")
      (spit (io/file tmp-dir "eng/api.md") "---\ntitle: API Spec\n---\nAPI documentation...")

      (let [files (walker/walk-corpus tmp-dir)]
        (is (= 4 (count files)))

        ;; Check README.md: no frontmatter, collection groups = ["all"]
        (let [readme (first (filter #(= "README.md" (:rel-path %)) files))]
          (is (= ["all"] (:declared-groups readme)))
          (is (= ["all"] (:effective-groups readme)))
          (is (nil? (:frontmatter readme))))

        ;; Check hr/leave.md: collection groups = ["hr"], no frontmatter
        (let [leave (first (filter #(= "hr/leave.md" (:rel-path %)) files))]
          (is (= ["hr"] (:declared-groups leave)))
          (is (= ["hr"] (:effective-groups leave)))
          (is (= "Leave Policy" (get-in leave [:frontmatter :title]))))

        ;; Check hr/bonus.md: frontmatter overrides to ["hr" "finance"]
        (let [bonus (first (filter #(= "hr/bonus.md" (:rel-path %)) files))]
          (is (= ["hr"] (:declared-groups bonus)))
          (is (= ["hr" "finance"] (:effective-groups bonus)))
          (is (= "hr/bonus.md" (:rel-path bonus))))

        ;; Check eng/api.md: collection groups = ["eng"]
        (let [api (first (filter #(= "eng/api.md" (:rel-path %)) files))]
          (is (= ["eng"] (:declared-groups api)))
          (is (= ["eng"] (:effective-groups api)))))

      (finally
        ;; Cleanup
        (doseq [f (reverse (file-seq tmp-dir))]
          (.delete f))))))

(deftest test-walk-corpus-skips-dotfiles
  ;; Walker should skip .hidden files and dot-directories.
  (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "test-dotfiles")]
    (try
      (.mkdirs tmp-dir)
      (.mkdirs (io/file tmp-dir ".git"))

      (spit (io/file tmp-dir ".hidden.md") "Hidden file")
      (spit (io/file tmp-dir "visible.md") "Visible file")
      (spit (io/file tmp-dir ".git/config.md") "Should be skipped")

      (let [files (walker/walk-corpus tmp-dir)]
        (is (= 1 (count files)))
        (is (= "visible.md" (:rel-path (first files)))))

      (finally
        (doseq [f (reverse (file-seq tmp-dir))]
          (.delete f))))))

(deftest test-collect-markdown-files-only
  ;; collect-markdown-files returns only accepted extensions.
  (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "test-extensions")]
    (try
      (.mkdirs tmp-dir)

      (spit (io/file tmp-dir "doc.md") "md file")
      (spit (io/file tmp-dir "doc.markdown") "markdown file")
      (spit (io/file tmp-dir "doc.txt") "txt file")
      (spit (io/file tmp-dir "doc.pdf") "pdf file")
      (spit (io/file tmp-dir "noext") "no extension")

      (let [files (walker/collect-markdown-files tmp-dir)]
        (is (= 3 (count files)))
        (let [paths (map :rel-path files)]
          (is (contains? (set paths) "doc.md"))
          (is (contains? (set paths) "doc.markdown"))
          (is (contains? (set paths) "doc.txt"))))

      (finally
        (doseq [f (reverse (file-seq tmp-dir))]
          (.delete f))))))

