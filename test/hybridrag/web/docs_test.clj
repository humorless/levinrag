(ns hybridrag.web.docs-test
  "Document viewer (SPEC.md §12): ACL (404, never 403), chunk anchors and
   highlight, changed-file notice, escaping, traversal and missing files."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [hickory.core :as hickory]
            [hickory.select :as s]
            [hybridrag.db.index-conn :as index-conn]
            [hybridrag.docs :as docs]
            [hybridrag.fixtures :as fx]
            [hybridrag.ingest.job :as job]
            [hybridrag.retrieval.datalevin :as rd]
            [hybridrag.tmp :as tmp]
            [hybridrag.web-client :as wc]
            [hybridrag.web-fixtures :as wf]
            [ring.util.codec :as codec]))

(use-fixtures :once fx/with-sample-index)
(use-fixtures :each wf/with-app-users)

(defn- sel [selector html] (s/select selector (hickory/as-hickory (hickory/parse html))))

(defn- chunk-ids [conn path]
  (sort (d/q '[:find [?id ...] :in $ ?p :where [?d :doc/path ?p] [?c :chunk/doc ?d] [?c :chunk/id ?id]]
             (d/db conn) path)))

(deftest test-doc-page
  (let [c (wf/logged-in "alice")
        ids (chunk-ids fx/*index* "hr/leave.md")
        {:keys [status body]} (wc/request! c :get "/docs/hr/leave.md")]
    (is (= 200 status))
    (is (seq (sel (s/tag :h1) body)))
    (testing "one anchor per chunk"
      (is (= (set ids) (set (keep #(get-in % [:attrs :id]) (sel (s/attr :id (set ids)) body))))))
    (is (empty? (sel (s/attr :data-highlight some?) body)))
    (is (not (str/includes? body "文件在建立索引後已變更")) "unchanged file: no notice")
    (testing "?chunk= highlights and scrolls"
      (let [body (:body (wc/request! c :get (str "/docs/hr/leave.md?chunk=" (codec/url-encode (second ids)))))
            hl (sel (s/attr :data-highlight #(= % "true")) body)]
        (is (seq hl))
        (is (some #(get-in % [:attrs :x-init]) hl))))))

(deftest test-doc-acl
  (is (= 404 (:status (wc/request! (wf/logged-in "bob") :get "/docs/hr/leave.md"))))
  (is (= 404 (:status (wc/request! (wf/logged-in "alice") :get "/docs/hr/no-such.md"))))
  (is (= 200 (:status (wc/request! (wf/logged-in "admin") :get "/docs/hr/leave.md")))))

(deftest test-docs-traversal
  (let [c (wf/logged-in "admin")]
    (doseq [p ["/docs/../deps.edn" "/docs/%2e%2e/deps.edn" "/docs//etc/passwd" "/docs/hr/../../deps.edn"]]
      (let [{:keys [status body]} (wc/request! c :get p)]
        (is (= 404 status) p)
        (is (not (str/includes? body ":deps")) p)))))

(defn- with-tmp-corpus
  "Copy corpus-sample to a tmp dir, index it, run (edit! dir) and then
   (f handler-opts) with a handler context over that corpus and index."
  [edit! f]
  (let [corpus (tmp/dir "corpus")
        idx (tmp/dir "idx")
        src (io/file "corpus-sample")]
    (doseq [^java.io.File file (file-seq src)
            :when (.isFile file)
            :let [target (io/file corpus (str (.relativize (.toPath src) (.toPath file))))]]
      (io/make-parents target)
      (io/copy file target))
    (let [conn (index-conn/open idx fx/dims)]
      (try
        (job/ingest! conn {:corpus-dir corpus
                           :embed-fn fx/hash-embed})
        (edit! corpus)
        (f {:context {:corpus-dir corpus
                      :index-conn conn
                      :search {:retriever (rd/retriever conn fx/hash-embed)
                               :rerank-fn wf/ok-rerank
                               :chat-fn (wf/chat-reply "x")
                               :opts {}}}})
        (finally (d/close conn) (tmp/delete-tree! idx) (tmp/delete-tree! corpus))))))

(deftest test-doc-changed-notice
  (with-tmp-corpus
    #(spit (io/file % "hr/leave.md") "\n\n新增的一行。\n" :append true)
    (fn [opts]
      (let [{:keys [status body]} (wc/request! (apply wf/logged-in "alice" (mapcat identity opts)) :get "/docs/hr/leave.md")]
        (is (= 200 status))
        (is (str/includes? body "文件在建立索引後已變更"))))))

(deftest test-doc-file-missing
  (with-tmp-corpus
    #(io/delete-file (io/file % "hr/leave.md"))
    (fn [opts]
      (is (= 404 (:status (wc/request! (apply wf/logged-in "alice" (mapcat identity opts)) :get "/docs/hr/leave.md")))))))

(deftest test-doc-html-escaped
  (with-tmp-corpus
    (fn [dir]
      (spit (io/file dir "hr/leave.md")
            "\n\n<script>alert(1)</script>\n\n[壞連結](javascript:alert(1))\n" :append true))
    (fn [opts]
      (let [body (:body (wc/request! (apply wf/logged-in "alice" (mapcat identity opts)) :get "/docs/hr/leave.md"))]
        (is (not (str/includes? body "<script>alert")))
        (is (not (str/includes? body "href=\"javascript:")))))))

(deftest test-source-file-stays-inside
  (is (docs/source-file "corpus-sample" "hr/leave.md"))
  (doseq [p ["../deps.edn" "hr/../../deps.edn" "/etc/passwd" "" "." "hr"]]
    (is (nil? (docs/source-file "corpus-sample" p)) p)))
