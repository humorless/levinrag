(ns replware.levinrag.security-test
  "SPEC.md §18.3 end to end over the sample corpus. The matrix is derived
   from the index: every doc × every seeded non-admin user who may not
   read it. For each pair no API or web path reveals the doc — search
   (graph on and off), every eval variant, ask (citations, debug
   candidates and the prompt handed to the model), docs — and every
   response holds only docs the user may read (which covers context
   expansion neighbours). Each probe query is first run as admin and
   must find the doc, so a check cannot pass because the query misses."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [replware.levinrag.auth.token :as token]
            [replware.levinrag.eval.harness :as harness]
            [replware.levinrag.fixtures :as fx]
            [replware.levinrag.retrieval.datalevin :as rd]
            [replware.levinrag.retrieval.pipeline :as pipeline]
            [replware.levinrag.web-client :as wc]
            [replware.levinrag.web-fixtures :as wf]
            [jsonista.core :as json]))

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
