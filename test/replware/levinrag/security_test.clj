(ns replware.levinrag.security-test
  "SPEC.md §18.3 end to end over the sample corpus. The matrix is derived
   from the index: every doc × every seeded non-admin user who may not
   read it. For each pair no API or web path reveals the doc — search
   (graph on and off), every eval variant, ask (citations, debug
   candidates and the prompt handed to the model), docs — and every
   response holds only docs the user may read (which covers context
   expansion neighbours). Each probe query is first run as admin and
   must find the doc, so a check cannot pass because the query misses."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [jsonista.core :as json]
            [replware.levinrag.acl-report :as acl-report]
            [replware.levinrag.auth.token :as token]
            [replware.levinrag.db.index-conn :as index-conn]
            [replware.levinrag.eval.harness :as harness]
            [replware.levinrag.fixtures :as fx]
            [replware.levinrag.ingest.job :as job]
            [replware.levinrag.retrieval.datalevin :as rd]
            [replware.levinrag.retrieval.pipeline :as pipeline]
            [replware.levinrag.tmp :as tmp]
            [replware.levinrag.web-client :as wc]
            [replware.levinrag.web-fixtures :as wf]))

(use-fixtures :once fx/with-sample-index)
(use-fixtures :each wf/with-app-users)

(def users ["alice" "bob" "carol" "nobody"])

(def principals (assoc fx/principals "nobody" fx/nobody))

(defn- chunk-texts
  "doc path → chunk texts in ordinal order."
  [db]
  (->> (d/q '[:find ?p ?o ?t :where [?d :doc/path ?p] [?c :chunk/doc ?d]
              [?c :chunk/ordinal ?o] [?c :chunk/text ?t]] db)
       (group-by first)
       (into {} (map (fn [[p rows]] [p (mapv #(nth % 2) (sort-by second rows))])))))

(defn- titles [db]
  (into {} (d/q '[:find ?p ?t :where [?d :doc/path ?p] [?d :doc/title ?t]] db)))

(defn- clip [s n] (subs s 0 (min n (count s))))

(defn- matrix
  "[{:path :user :queries}] for every doc and every user who cannot read it."
  []
  (let [db (d/db fx/*index*)
        groups (fx/doc-groups fx/*index*)
        texts (chunk-texts db)
        title (titles db)]
    (vec (for [path (sort (keys groups))
               u users
               :when (not (fx/readable? groups (principals u) path))]
           {:path path
            :user u
            :queries [(title path) (clip (first (texts path)) 1000)]}))))

(defn- readable-text
  "Everything a user may legitimately see in a prompt: titles and chunk
   text of the docs they can read. A restricted doc's path or title that
   also occurs here (e.g. a public doc linking to it) proves nothing when
   found in the prompt, so those checks are skipped."
  [user]
  (let [db (d/db fx/*index*)
        groups (fx/doc-groups fx/*index*)
        texts (chunk-texts db)
        title (titles db)]
    (str/join "\n" (for [[p ts] texts
                         :when (fx/readable? groups (principals user) p)]
                     (str (title p) "\n" (str/join "\n" ts))))))

(defn- prompt-secrets
  "Strings from the restricted doc `path` whose presence in `user`'s
   prompt would prove a leak: its path, title and the start of its first
   chunk, minus any that also occur in text the user may read (a readable
   doc can legitimately mention a restricted doc's title or link to its
   path — public/handbook.md links to hr/leave.md as 請假規定)."
  [visible-text title texts path]
  (remove #(str/includes? visible-text %)
          [path (title path) (clip (first (texts path)) 40)]))

(defn- api [handler token method uri body]
  (let [resp (handler (cond-> {:request-method method
                               :uri uri
                               :scheme :http
                               :server-name "localhost"
                               :headers {"content-type" "application/json"
                                         "accept" "application/json"
                                         "authorization" (str "Bearer " token)}}
                        body (assoc :body (java.io.ByteArrayInputStream.
                                            (.getBytes (json/write-value-as-string body) "UTF-8")))))]
    (update resp :body #(when (and % (not= "" %))
                          (json/read-value (if (string? %) % (slurp %)) json/keyword-keys-object-mapper)))))

(defn- chunk-doc [chunk-id] (first (str/split chunk-id #"::")))

(defn- doc-paths
  "Every doc a /search or /ask body mentions, including the docs of all
   chunk ids in passages (context-expansion neighbours)."
  [body]
  (set (concat (map :doc_path (:passages body))
               (map :doc_path (:candidates body))
               (map :doc_path (:citations body))
               (map chunk-doc (mapcat :chunk_ids (concat (:passages body) (:citations body))))
               (map (comp chunk-doc :chunk_id) (:candidates body)))))

(defn- tokens []
  (into {} (for [u (conj users "admin")]
             [u (:token (token/create-token! wf/*app* u "security"))])))

(deftest test-matrix-covers-every-user
  (let [m (matrix)]
    (is (< 20 (count m)))
    (is (= (set users) (set (map :user m))))))

(deftest test-api-never-reveals-unreadable-docs
  (let [seen (atom [])
        h (wf/handler :chat-fn (fn [msgs opts]
                                 (swap! seen conj msgs)
                                 ((wf/chat-reply "依資料[1]。") msgs opts)))
        tok (tokens)
        groups (fx/doc-groups fx/*index*)
        texts (chunk-texts (d/db fx/*index*))
        title (titles (d/db fx/*index*))
        visible (memoize readable-text)]
    (doseq [{:keys [path user queries]} (matrix)
            q queries
            :let [readable? #(fx/readable? groups (principals user) %)]]
      (testing (str user " × " path " × " (clip q 20))
        (testing "negative control: admin finds the doc with this query"
          (is (contains? (doc-paths (:body (api h (tok "admin") :post "/api/v1/search" {:query q
                                                                                        :final_k 50})))
                         path)))
        (doseq [graph [true false]]
          (let [paths (doc-paths (:body (api h (tok user) :post "/api/v1/search" {:query q
                                                                                  :final_k 50
                                                                                  :graph graph})))]
            (is (not (contains? paths path)) (str "search graph=" graph))
            (is (every? readable? paths) (str "search graph=" graph))))
        (reset! seen [])
        (let [paths (doc-paths (:body (api h (tok user) :post "/api/v1/ask" {:query q
                                                                             :debug true})))
              ;; the user's own question is echoed after </sources>; only
              ;; what retrieval put in front of the model counts
              prompt (str/join "\n" (for [m (apply concat @seen)]
                                      (if (= "user" (:role m))
                                        (first (str/split (:content m) #"</sources>"))
                                        (:content m))))]
          (is (not (contains? paths path)) "ask")
          (is (every? readable? paths) "ask")
          (let [secrets (prompt-secrets (visible user) title texts path)]
            (is (seq secrets) "no prompt check left for this pair (all publicly visible)")
            (doseq [secret secrets]
              (is (not (str/includes? prompt secret)) (str "prompt contains " secret)))))
        (is (= 404 (:status (api h (tok user) :get (str "/api/v1/docs/" path) nil))))))))

(deftest test-web-doc-viewer-hides-unreadable-docs
  (let [clients (into {} (for [u users] [u (wf/logged-in u)]))]
    (doseq [{:keys [path user]} (distinct (map #(select-keys % [:path :user]) (matrix)))]
      (is (= 404 (:status (wc/request! (clients user) :get (str "/docs/" path)))) (str user " " path)))
    (testing "negative control: admin opens them"
      (is (= 200 (:status (wc/request! (wf/logged-in "admin") :get "/docs/hr/leave.md")))))))

(deftest test-every-eval-variant-is-leak-free
  (let [questions (vec (map-indexed (fn [i {:keys [path user queries]}]
                                      {:id (str "sec-" i)
                                       :user user
                                       :query (first queries)
                                       :must-not-docs [path]})
                                    (matrix)))
        report (harness/run-eval {:retriever (rd/retriever fx/*index* fx/hash-embed)
                                  :rerank-fn wf/ok-rerank}
                                 fx/*index*
                                 {:questions questions
                                  :principals principals
                                  :variant-names (vec (keys harness/variants))})]
    (is (= 0 (:acl-leaks report)))
    (testing "negative control: the same questions as admin do leak"
      (let [admin-report (harness/run-eval {:retriever (rd/retriever fx/*index* fx/hash-embed)
                                            :rerank-fn wf/ok-rerank}
                                           fx/*index*
                                           {:questions (mapv #(assoc % :user "admin") questions)
                                            :principals principals
                                            :variant-names ["lexical"]})]
        (is (pos? (:acl-leaks admin-report)))))))

(deftest test-graph-does-not-follow-links-into-unreadable-docs
  ;; public/handbook.md links to hr/leave.md; lexical-only so hr/leave.md
  ;; can only arrive through the graph channel
  (let [deps {:retriever (rd/retriever fx/*index* fx/hash-embed)
              :rerank-fn wf/ok-rerank}
        via-graph (fn [user]
                    (->> (:candidates (pipeline/search deps (principals user) "員工手冊"
                                                       {:graph? true
                                                        :channels #{:lexical}
                                                        :final-k 50}))
                         (filter #(= "hr/leave.md" (:doc/path %)))
                         (filter #(get-in % [:channels :graph]))))]
    (testing "negative control: alice (hr) gets it via the link"
      (is (seq (via-graph "alice"))))
    (is (empty? (via-graph "bob")))
    (is (empty? (via-graph "carol")))))

(deftest test-no-group-user-gets-nothing
  (let [h (wf/handler)
        tok (tokens)]
    (doseq [q ["特休" "SKU-A1234" "員工手冊" "VPN"]]
      (let [search (:body (api h (tok "nobody") :post "/api/v1/search" {:query q}))
            ask (:body (api h (tok "nobody") :post "/api/v1/ask" {:query q
                                                                  :debug true}))]
        (is (empty? (:passages search)) q)
        (is (empty? (:candidates search)) q)
        (is (true? (:no_evidence ask)) q)
        (is (empty? (:candidates ask)) q)))))

(deftest test-traces-are-private
  (let [h (wf/handler)
        tok (tokens)
        id (get-in (api h (tok "alice") :post "/api/v1/search" {:query "特休"}) [:body :trace_id])
        uri (str "/api/v1/traces/" id)]
    (is (= 200 (:status (api h (tok "alice") :get uri nil))) "owner")
    (is (= 200 (:status (api h (tok "admin") :get uri nil))) "admin")
    (is (= 404 (:status (api h (tok "bob") :get uri nil))) "another user")
    (is (= 404 (:status (wc/request! (wf/logged-in "bob") :get (str "/admin/traces/" id)))) "web, non-admin")))

(deftest test-own-trace-hides-pre-acl-counts
  ;; a count from before the ACL filter would tell a user that documents
  ;; they cannot read contain the query terms (raw-hits > 0, after-acl 0)
  (let [h (wf/handler)
        tok (tokens)
        pre-acl (fn [body] (keep #(get-in body [:stages % :raw-hits]) [:lexical :semantic]))]
    (doseq [{:keys [path user queries]} (take-nth 5 (matrix))
            :let [q (first queries)
                  id (get-in (api h (tok user) :post "/api/v1/search" {:query q}) [:body :trace_id])
                  own (:body (api h (tok user) :get (str "/api/v1/traces/" id) nil))]]
      (testing (str user " × " path)
        (is (empty? (pre-acl own)) "no raw-hits in the user's own trace")
        (is (not-any? #{"acl-starvation"} (get-in own [:stages :flags])))
        (testing "negative control: the admin view of the same trace has the counts"
          (is (seq (pre-acl (:body (api h (tok "admin") :get (str "/api/v1/traces/" id) nil))))))))))

(deftest test-broken-acl-settings-fail-closed
  ;; SPEC.md §7.2 rule 5: a permissions setting that cannot be applied as
  ;; written keeps the doc out of the index instead of falling back to the
  ;; directory's (wider) groups; a doc already indexed is removed.
  (let [dir (tmp/dir "acl-fail-closed")
        idx (tmp/dir "acl-fail-closed-index")
        conn (index-conn/open idx fx/dims)
        put! (fn [p text] (io/make-parents (io/file dir p)) (spit (io/file dir p) text))
        ingest! #(job/ingest! conn {:corpus-dir dir
                                    :embed-fn fx/hash-embed})
        indexed #(set (keys (fx/doc-groups conn)))
        error-paths (fn [rep] (set (map :path (:errors rep))))]
    (try
      (put! "_collection.edn" "{:read-groups [\"all\"]}")
      (put! "public/a.md" "# 公告\n\n大家都能讀。")
      (put! "hr/_collection.edn" "{:read-groups [\"hr\"]}")
      (put! "hr/leave.md" "# 請假\n\n特休規則。")
      (put! "hr/sub/deep.md" "# 深層\n\n人資內部。")
      (put! "fm/ok.md" "---\nread_groups: [hr]\n---\n# ok\n\nx")
      (put! "fm/typo.md" "---\nread_group: [hr]\n---\n# typo\n\nx")
      (put! "fm/multiline.md" "---\nread_groups:\n  - hr\n---\n# multi\n\nx")
      (put! "fm/bare.md" "---\nread_groups: hr\n---\n# bare\n\nx")
      (testing "broken frontmatter: those docs are errors and are not indexed"
        (let [rep (ingest!)]
          (is (= #{"fm/typo.md" "fm/multiline.md" "fm/bare.md"} (error-paths rep)))
          (is (= #{"public/a.md" "hr/leave.md" "hr/sub/deep.md" "fm/ok.md"} (indexed)))
          (is (= #{"hr"} (get (fx/doc-groups conn) "hr/leave.md")))))
      (testing "breaking hr/_collection.edn removes its docs instead of widening them to `all`"
        (put! "hr/_collection.edn" "{:read-group [\"hr\"]}")
        (let [rep (ingest!)]
          (is (= #{"hr/leave.md" "hr/sub/deep.md" "fm/typo.md" "fm/multiline.md" "fm/bare.md"}
                 (error-paths rep)))
          (is (every? #(re-find #"hr/_collection\.edn" (:error %))
                      (filter #(str/starts-with? (:path %) "hr/") (:errors rep))))
          (is (= #{"public/a.md" "fm/ok.md"} (indexed)))
          (is (not-any? #(str/starts-with? % "hr/")
                        (map :doc/path (d/pull-many (d/db conn) [:doc/path]
                                                    (vec (rd/accessible-doc-ids (d/db conn) #{"all"})))))
              "an `all` user can reach no hr doc")))
      (testing "fixing it brings the docs back with the declared groups"
        (put! "hr/_collection.edn" "{:read-groups [\"hr\"]}")
        (ingest!)
        (is (= #{"hr"} (get (fx/doc-groups conn) "hr/leave.md")))
        (is (= #{"hr"} (get (fx/doc-groups conn) "hr/sub/deep.md"))))
      (finally (d/close conn) (tmp/delete-tree! idx) (tmp/delete-tree! dir)))))

(deftest test-hidden-frontmatter-read-groups-fail-closed
  ;; review 2026-09-25 H1: read_groups the parser would not see must keep the
  ;; doc out of the index, not give it the root's `all`
  (let [dir (tmp/dir "acl-hidden-fm")
        idx (tmp/dir "acl-hidden-fm-index")
        conn (index-conn/open idx fx/dims)
        docs {"pub/bom-blank.md" "﻿\n---\nread_groups: [hr]\n---\n# a\n\nx"
              "pub/unclosed.md" "---\nread_groups: [hr]\n# b\n\nx"
              "pub/fullwidth.md" "---\nread_groups：[hr]\n---\n# c\n\nx"
              "pub/quoted-key.md" "---\n\"read_groups\": [hr]\n---\n# d\n\nx"
              "pub/twice.md" "---\nread_groups: [hr]\nread_groups: [all]\n---\n# e\n\nx"
              "pub/block-scalar.md" "---\nread_groups: [hr]\nnotes: |\n  read_groups: [all]\n---\n# f\n\nx"
              "pub/plain.txt" "read_groups: [hr]\n\n薪資表"
              "pub/ok.md" "﻿---\ntitle: Q3---draft\nread_groups: [hr]\n---\n# ok\n\nx"}]
    (try
      (spit (io/file dir "_collection.edn") "{:read-groups [\"all\"]}")
      (doseq [[p text] docs] (io/make-parents (io/file dir p)) (spit (io/file dir p) text))
      (let [rep (job/ingest! conn {:corpus-dir dir
                                   :embed-fn fx/hash-embed})]
        (is (= (disj (set (keys docs)) "pub/ok.md") (set (map :path (:errors rep)))))
        (is (= {"pub/ok.md" #{"hr"}} (fx/doc-groups conn))))
      (finally (d/close conn) (tmp/delete-tree! idx) (tmp/delete-tree! dir)))))

(deftest test-failed-reingest-removes-the-old-copy
  ;; review 2026-09-25 H2: when re-ingesting an edited doc fails (e.g. the
  ;; embedding endpoint is down), the old copy must not stay searchable
  ;; under its old — possibly wider — groups
  (let [dir (tmp/dir "acl-failed-reingest")
        idx (tmp/dir "acl-failed-reingest-index")
        conn (index-conn/open idx fx/dims)
        embed-down? (atom false)
        embed (fn [texts] (if @embed-down? (throw (ex-info "embed endpoint down" {})) (fx/hash-embed texts)))
        ingest! #(job/ingest! conn {:corpus-dir dir
                                    :embed-fn embed})]
    (try
      (spit (io/file dir "_collection.edn") "{:read-groups [\"all\"]}")
      (io/make-parents (io/file dir "pub/memo.md"))
      (spit (io/file dir "pub/memo.md") "# 備忘\n\n午餐時間調整。")
      (spit (io/file dir "pub/other.md") "# 其他\n\n不變。")
      (ingest!)
      (is (= #{"all"} (get (fx/doc-groups conn) "pub/memo.md")))
      (spit (io/file dir "pub/memo.md") "---\nread_groups: [hr]\n---\n# 裁員名單\n\n機密。")
      (reset! embed-down? true)
      (let [rep (ingest!)
            [err] (:errors rep)]
        (is (= "pub/memo.md" (:path err)))
        (is (str/includes? (:error err) "已從索引移除"))
        (is (not (contains? (fx/doc-groups conn) "pub/memo.md")) "the old copy is gone")
        (is (contains? (fx/doc-groups conn) "pub/other.md") "unchanged docs are untouched"))
      (reset! embed-down? false)
      (ingest!)
      (is (= #{"hr"} (get (fx/doc-groups conn) "pub/memo.md")) "back, with the new groups, once ingest succeeds")
      (finally (d/close conn) (tmp/delete-tree! idx) (tmp/delete-tree! dir)))))

(deftest test-missing-corpus-dir-never-wipes-the-index
  ;; review 2026-09-25 L1: a wrong CORPUS_DIR looked like "every doc was
  ;; deleted"; the web runner refused it, `bb ingest` did not
  (let [idx (tmp/dir "missing-corpus-index")
        conn (index-conn/open idx fx/dims)]
    (try
      (job/ingest! conn {:corpus-dir "corpus-sample"
                         :embed-fn fx/hash-embed})
      (let [before (fx/doc-groups conn)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"CORPUS_DIR 不存在"
                              (job/ingest! conn {:corpus-dir "/nonexistent/corpus"
                                                 :embed-fn fx/hash-embed})))
        (is (= before (fx/doc-groups conn))))
      (finally (d/close conn) (tmp/delete-tree! idx)))))

(deftest test-broken-directory-collection-record-holds-no-groups
  ;; review 2026-09-25 L7: the collections table showed the parent's wider
  ;; groups for a directory whose own settings are broken
  (let [dir (tmp/dir "acl-broken-coll")
        idx (tmp/dir "acl-broken-coll-index")
        conn (index-conn/open idx fx/dims)
        groups #(set (:collection/effective-groups
                       (d/pull (d/db conn) [:collection/effective-groups] [:collection/path %])))]
    (try
      (spit (io/file dir "_collection.edn") "{:read-groups [\"all\"]}")
      (io/make-parents (io/file dir "hr/sub/a.md"))
      (spit (io/file dir "hr/_collection.edn") "{:read-group [\"hr\"]}")
      (spit (io/file dir "hr/sub/a.md") "# a\n\nx")
      (job/ingest! conn {:corpus-dir dir
                         :embed-fn fx/hash-embed})
      (is (= #{"all"} (groups "")))
      (is (= #{} (groups "hr")))
      (is (= #{} (groups "hr/sub")) "below the broken file too")
      (finally (d/close conn) (tmp/delete-tree! idx) (tmp/delete-tree! dir)))))

(deftest test-broken-root-collection-keeps-everything-it-governs-out
  ;; review 2026-09-25 test gap: a broken root _collection.edn governs every
  ;; doc except those under a directory with its own valid :read-groups
  (let [dir (tmp/dir "acl-broken-root")
        idx (tmp/dir "acl-broken-root-index")
        conn (index-conn/open idx fx/dims)
        put! (fn [p text] (io/make-parents (io/file dir p)) (spit (io/file dir p) text))]
    (try
      (put! "_collection.edn" "{:read-group [\"all\"]}")
      (put! "a.md" "# a\n\nx")
      (put! "pub/b.md" "# b\n\nx")
      (put! "hr/_collection.edn" "{:read-groups [\"hr\"]}")
      (put! "hr/c.md" "# c\n\nx")
      (let [rep (job/ingest! conn {:corpus-dir dir
                                   :root-read-groups ["all"]
                                   :embed-fn fx/hash-embed})]
        (is (= #{"a.md" "pub/b.md"} (set (map :path (:errors rep)))))
        (is (= {"hr/c.md" #{"hr"}} (fx/doc-groups conn))
            "ROOT_READ_GROUPS does not stand in for a broken root file"))
      (finally (d/close conn) (tmp/delete-tree! idx) (tmp/delete-tree! dir)))))

(deftest test-acl-report-prints-no-document-text
  (let [db (d/db fx/*index*)
        out (acl-report/text (acl-report/build db principals) {:docs? true
                                                               :ingest-errors 0})
        texts (mapcat val (chunk-texts db))]
    (is (seq texts))
    (doseq [t texts
            line (str/split-lines t)
            :let [line (str/trim line)]
            :when (>= (count line) 12)]
      (is (not (str/includes? out line)) line))))
