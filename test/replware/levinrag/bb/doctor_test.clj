(ns replware.levinrag.bb.doctor-test
  "`bb doctor` checks (SPEC.md §5): tools, settings, corpus, directory
   permissions files."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [replware.levinrag.bb.doctor :as doctor]
            [replware.levinrag.ingest.walker :as walker]
            [replware.levinrag.tmp :as tmp]))

(defn- levels [results] (into {} (map (juxt :item :level)) results))

(defn- with-corpus
  "Call (f dir) with a temp dir holding `files` (relative path → text)."
  [files f]
  (let [dir (tmp/dir "doctor")]
    (try
      (doseq [[p text] files]
        (io/make-parents (io/file dir p))
        (spit (io/file dir p) text))
      (f dir)
      (finally (tmp/delete-tree! dir)))))

(deftest test-java-major
  (is (= 21 (doctor/java-major "openjdk version \"21.0.2\" 2024-01-16 LTS\nOpenJDK Runtime")))
  (is (= 17 (doctor/java-major "openjdk version \"17\" 2021-09-14")))
  (is (= 8 (doctor/java-major "java version \"1.8.0_301\"")))
  (is (nil? (doctor/java-major "garbage"))))

(deftest test-check-tools
  (is (= {"java" :ok
          "clojure" :ok}
         (select-keys (levels (doctor/check-tools {:java-version "openjdk version \"21.0.2\""
                                                   :clojure? true}))
                      ["java" "clojure"])))
  (let [r (doctor/check-tools {:java-version "openjdk version \"17.0.1\""
                               :clojure? false})]
    (is (= {"java" :fail
            "clojure" :fail} (select-keys (levels r) ["java" "clojure"])))
    (is (every? #(str/includes? (:fix %) "mise install") r)))
  (is (= :fail (get (levels (doctor/check-tools {:java-version nil
                                                 :clojure? true})) "java")))
  (testing "the web UI's CSS: built, or buildable by bb serve; otherwise only a warning"
    (let [css (fn [m] (get (levels (doctor/check-tools (merge {:java-version "openjdk version \"21\""
                                                               :clojure? true} m)))
                           "css"))]
      (is (= :ok (css {:css? true
                       :tailwind? false})))
      (is (= :ok (css {:css? false
                       :tailwind? true})))
      (is (= :warn (css {:css? false
                         :tailwind? false}))))))

(deftest test-check-settings
  (testing "chat endpoint and model are required; the rest have defaults"
    (is (= {".env" :ok
            "VLLM_CHAT_BASE_URL" :ok
            "VLLM_CHAT_MODEL" :ok}
           (levels (doctor/check-settings {:dotenv? true
                                           :env {"VLLM_CHAT_BASE_URL" "http://x/v1"
                                                 "VLLM_CHAT_MODEL" "m"}}))))
    (let [r (doctor/check-settings {:dotenv? false
                                    :env {"VLLM_CHAT_MODEL" " "}})]
      (is (= {".env" :warn
              "VLLM_CHAT_BASE_URL" :fail
              "VLLM_CHAT_MODEL" :fail} (levels r)))
      (is (str/includes? (:fix (first r)) "cp .env.example .env")))))

(deftest test-check-corpus
  (testing "counts what ingest would read, with the walker's skip rules"
    (with-corpus {"a.md" "x"
                  "b/c.txt" "x"
                  "b/d.markdown" "x"
                  "_drafts/e.md" "x"
                  ".git/f.md" "x"}
      (fn [dir]
        (let [[r] (doctor/check-corpus dir)]
          (is (= :ok (:level r)))
          (is (str/includes? (:msg r) "3 個"))))))
  (testing "PDF / Office files are not ingested: a warning when there are also text files"
    (with-corpus {"a.md" "x"
                  "b.pdf" "x"
                  "c.docx" "x"}
      (fn [dir]
        (is (= [:ok :warn] (map :level (doctor/check-corpus dir))))
        (is (str/includes? (:msg (second (doctor/check-corpus dir))) "2 個")))))
  (testing "no text files is a failure that mentions converting PDFs"
    (with-corpus {"a.pdf" "x"}
      (fn [dir]
        (let [r (doctor/check-corpus dir)]
          (is (= :fail (:level (first r))))
          (is (some #(str/includes? (str (:fix %)) "Markdown") r))))))
  (testing "a missing directory"
    (is (= :fail (:level (first (doctor/check-corpus "/nonexistent/corpus")))))))

(deftest test-check-permissions
  (testing "root groups from _collection.edn or ROOT_READ_GROUPS; neither is a warning"
    (with-corpus {"_collection.edn" "{:name \"root\" :read-groups [\"all\"]}"
                  "a.md" "x"}
      (fn [dir] (is (= [:ok] (map :level (doctor/check-permissions dir ""))))))
    (with-corpus {"a.md" "x"}
      (fn [dir]
        (is (= [:ok] (map :level (doctor/check-permissions dir "all"))))
        (let [[r] (doctor/check-permissions dir "")]
          (is (= :warn (:level r)))
          (is (str/includes? (:msg r) "只有 admin"))))))
  (testing "a broken _collection.edn is a failure naming the file (ingest keeps
            the documents it governs out of the index)"
    (doseq [[label text] [["unreadable" "{:read-groups [\"hr]}"]
                          ["not a map" "[\"hr\"]"]
                          ["unknown key" "{:read-group [\"hr\"]}"]
                          ["groups not strings" "{:read-groups [hr]}"]
                          ["groups not a list" "{:read-groups \"hr\"}"]]]
      (with-corpus {"_collection.edn" "{:read-groups [\"all\"]}"
                    "hr/_collection.edn" text
                    "hr/a.md" "x"}
        (fn [dir]
          (let [bad (filter #(= :fail (:level %)) (doctor/check-permissions dir ""))]
            (is (= 1 (count bad)) label)
            (is (str/includes? (str (:msg (first bad))) "hr/_collection.edn") label))))))
  (testing "_collection.edn under a skipped directory is not checked"
    (with-corpus {"_collection.edn" "{:read-groups [\"all\"]}"
                  "_drafts/_collection.edn" (str (char 0x7b) "broken")}
      (fn [dir] (is (= [:ok] (map :level (doctor/check-permissions dir ""))))))))

(deftest test-report
  (let [results [{:level :ok
                  :item "java"
                  :msg "21"}
                 {:level :fail
                  :item "clojure"
                  :msg "找不到"
                  :fix "mise install"}]
        text (doctor/report results)]
    (is (str/includes? text "[OK]   java"))
    (is (str/includes? text "[FAIL] clojure"))
    (is (str/includes? text "mise install"))
    (is (doctor/failed? results))
    (is (not (doctor/failed? (take 1 results))))))

(deftest test-doctor-agrees-with-ingest-on-collection-files
  ;; doctor keeps its own copy of the _collection.edn checks (it runs in bb,
  ;; before Clojure is installed); both must call the same files broken
  (doseq [[n text] [["_collection.edn" "{:read-groups [\"hr\"]}"]
                    ["_collection.edn" "{:name \"only a name\"}"]
                    ["_collection.edn" "{:read-groups []}"]
                    ["_collection.edn" "{:read-groups [\"hr]}"]
                    ["_collection.edn" "[\"hr\"]"]
                    ["_collection.edn" "{:read-group [\"hr\"]}"]
                    ["_collection.edn" "{:read-groups [hr]}"]
                    ["_collection.edn" "{:read-groups \"hr\"}"]
                    ["_collection.edn" "{:read-groups #{\"hr\"}}"]
                    ["_collection.edn" "{:read-groups nil}"]
                    ["_collection.edn" ""]
                    ["_collection.edn" "{:name \"HR\"} {:read-groups [\"hr\"]}"]
                    ["_collection.edn" "#_{:read-groups [\"all\"]} {:read-groups [\"hr\"]}"]
                    ["_Collection.edn" "{:read-groups [\"hr\"]}"]
                    ["collection.edn" "{:read-groups [\"hr\"]}"]
                    ["_collections.edn" "{:read-groups [\"hr\"]}"]]]
    (with-corpus {"_collection.edn" "{:read-groups [\"all\"]}"
                  (str "hr/" n) text
                  "hr/a.md" "# a"}
      (fn [dir]
        (let [[f] (walker/walk-corpus dir nil ["all"] nil)]
          (is (= (boolean (:acl-error f)) (boolean (doctor/collection-file-problem n text)))
              (str n " " text)))))))
