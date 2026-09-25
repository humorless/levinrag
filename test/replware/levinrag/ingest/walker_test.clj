(ns replware.levinrag.ingest.walker-test
  "Walker unit tests: file collection, ACL resolution, frontmatter parsing.

   Tests:
   - collect-markdown-files: extensions, skipping dotfiles
   - find-collection-edns: discover _collection.edn from filesystem
   - walk-corpus: full pipeline with ACL resolution"
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [replware.levinrag.ingest.walker :as walker]
            [replware.levinrag.tmp :as tmp]))

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

;; --- broken permissions files (fail closed) ---

(defn- with-tree [files f]
  (let [dir (tmp/dir "walker-acl")]
    (try
      (doseq [[p text] files]
        (io/make-parents (io/file dir p))
        (spit (io/file dir p) text))
      (f dir)
      (finally (tmp/delete-tree! dir)))))

(deftest test-scan-collection-edns-reports-broken-files
  (doseq [[label text] [["unreadable" "{:read-groups [\"hr]}"]
                        ["not a map" "[\"hr\"]"]
                        ["unknown key" "{:read-group [\"hr\"]}"]
                        ["unsupported key (there are no deny/allow overrides)" "{:read-groups [\"hr\"] :acl-overrides []}"]
                        ["groups not strings" "{:read-groups [hr]}"]
                        ["groups not a list" "{:read-groups \"hr\"}"]]]
    (with-tree {"_collection.edn" "{:read-groups [\"all\"]}"
                "hr/_collection.edn" text}
      (fn [dir]
        (let [{:keys [edns broken]} (walker/scan-collection-edns dir)]
          (is (= {"" {:read-groups ["all"]}} edns) label)
          (is (= #{"hr"} (set (keys broken))) label)
          (is (string? (get broken "hr")) label)
          (is (= edns (walker/find-collection-edns dir)) "find-collection-edns returns only valid files"))))))

(deftest test-walk-corpus-marks-docs-under-a-broken-collection
  (with-tree {"_collection.edn" "{:read-groups [\"all\"]}"
              "a.md" "# a"
              "hr/_collection.edn" "{:read-group [\"hr\"]}"
              "hr/leave.md" "# leave"
              "hr/sub/_collection.edn" "{:name \"only a name\"}"
              "hr/sub/deep.md" "# deep"
              "hr/lead/_collection.edn" "{:read-groups [\"hr-lead\"]}"
              "hr/lead/x.md" "# x"
              "fm/typo.md" "---\nread_group: [hr]\n---\n# t"}
    (fn [dir]
      (let [{:keys [edns broken]} (walker/scan-collection-edns dir)
            by-path (into {} (map (juxt :rel-path identity)) (walker/walk-corpus dir edns ["all"] broken))]
        (is (nil? (:acl-error (by-path "a.md"))))
        (is (re-find #"hr/_collection\.edn" (str (:acl-error (by-path "hr/leave.md")))))
        (is (re-find #"hr/_collection\.edn" (str (:acl-error (by-path "hr/sub/deep.md"))))
            "a nearer file without :read-groups does not hide the broken one")
        (is (nil? (:acl-error (by-path "hr/lead/x.md")))
            "a nearer valid :read-groups decides; the broken parent does not matter")
        (is (= ["hr-lead"] (:effective-groups (by-path "hr/lead/x.md"))))
        (is (re-find #"read_group" (str (:acl-error (by-path "fm/typo.md")))))))))

(deftest test-more-broken-collection-shapes-fail-closed
  ;; review 2026-09-25 M2
  (testing "a second EDN value in the file is not silently dropped"
    (with-tree {"_collection.edn" "{:read-groups [\"all\"]}"
                "hr/_collection.edn" "{:name \"HR\"} {:read-groups [\"hr\"]}"
                "hr/a.md" "# a"}
      (fn [dir]
        (let [{:keys [broken]} (walker/scan-collection-edns dir)
              [f] (walker/walk-corpus dir nil ["all"] nil)]
          (is (re-find #"不只一個" (str (get broken "hr"))))
          (is (:acl-error f))))))
  (testing "a misnamed settings file breaks its directory instead of being ignored"
    (doseq [n ["_Collection.edn" "collection.edn" "_collections.edn" "_COLLECTION.EDN"]]
      (with-tree {"_collection.edn" "{:read-groups [\"all\"]}"
                  (str "hr/" n) "{:read-groups [\"hr\"]}"
                  "hr/a.md" "# a"}
        (fn [dir]
          (let [[f] (walker/walk-corpus dir nil ["all"] nil)]
            (is (re-find #"_collection\.edn" (str (:acl-error f))) n))))))
  (testing "directory names with leading or trailing spaces keep their own settings"
    (doseq [d [" hr" "hr "]]
      (with-tree {"_collection.edn" "{:read-groups [\"all\"]}"
                  (str d "/_collection.edn") "{:read-groups [\"hr\"]}"
                  (str d "/a.md") "# a"}
        (fn [dir]
          (let [[f] (walker/walk-corpus dir nil ["all"] nil)]
            (is (nil? (:acl-error f)) (pr-str d))
            (is (= ["hr"] (:effective-groups f)) (pr-str d))))))))

(deftest test-walk-corpus-always-knows-about-broken-files
  ;; review 2026-09-25 L6: passing only the valid edns (as
  ;; find-collection-edns returns them) must not bring back fail-open
  (with-tree {"_collection.edn" "{:read-groups [\"all\"]}"
              "hr/_collection.edn" "{:read-group [\"hr\"]}"
              "hr/a.md" "# a"}
    (fn [dir]
      (doseq [files [(walker/walk-corpus dir (walker/find-collection-edns dir))
                     (walker/walk-corpus dir (walker/find-collection-edns dir) ["all"])]]
        (is (:acl-error (first files)))))))
