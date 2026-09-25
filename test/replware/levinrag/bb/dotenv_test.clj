(ns replware.levinrag.bb.dotenv-test
  "`.env` for the bb tasks (SPEC.md §5): parsing, and variables already set
   in the shell win over the file."
  (:require [clojure.test :refer [deftest is testing]]
            [replware.levinrag.bb.dotenv :as dotenv]
            [replware.levinrag.tmp :as tmp]))

(deftest test-parse
  (testing "the forms .env.example uses"
    (is (= {"DATA_DIR" "./data"
            "VLLM_CHAT_EXTRA_BODY" "{\"reasoning_effort\":\"none\"}"
            "VLLM_CHAT_MODEL" "qwen/qwen3-8b"
            "ROOT_READ_GROUPS" "all,hr"
            "EMPTY" ""
            "SPACED" "a b # not a comment"}
           (dotenv/parse (str "# comment\n"
                              "\n"
                              "DATA_DIR=./data\n"
                              "VLLM_CHAT_EXTRA_BODY='{\"reasoning_effort\":\"none\"}'\n"
                              "  export VLLM_CHAT_MODEL=qwen/qwen3-8b   # trailing comment\n"
                              "ROOT_READ_GROUPS=\"all,hr\"\n"
                              "EMPTY=\n"
                              "SPACED=\"a b # not a comment\"\n")))))
  (testing "the last assignment wins; CRLF line endings"
    (is (= {"A" "2"} (dotenv/parse "A=1\r\nA=2\r\n"))))
  (testing "a malformed line is an error naming its line, not skipped"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\.env 第 2 行"
          (dotenv/parse "A=1\nVLLM_CHAT_MODEL qwen\n")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\.env 第 1 行"
          (dotenv/parse "A='unterminated\n")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\.env 第 1 行"
          (dotenv/parse "1A=x\n")))))

(deftest test-extra-env
  (testing "variables set in the shell are not overridden"
    (is (= {"B" "file"}
           (dotenv/extra-env {"A" "file" "B" "file"} {"A" "shell" "PATH" "/bin"}))))
  (testing "a missing .env gives no variables"
    (is (= {} (dotenv/read-file "/nonexistent/.env"))))
  (testing "read-file parses an existing file"
    (let [dir (tmp/dir "dotenv")]
      (try
        (spit (str dir "/.env") "A=1\n")
        (is (= {"A" "1"} (dotenv/read-file (str dir "/.env"))))
        (finally (tmp/delete-tree! dir))))))
