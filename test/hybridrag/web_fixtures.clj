(ns hybridrag.web-fixtures
  "Web-route test setup: seeded users with passwords in a fresh app.dtlv
   and a Ring handler over the sample index with stub rerank and chat."
  (:require [datalevin.core :as d]
            [hybridrag.auth.users :as users]
            [hybridrag.db.index-conn :as index-conn]
            [hybridrag.fixtures :as fx]
            [hybridrag.ingest.runner :as runner]
            [hybridrag.retrieval.datalevin :as rd]
            [hybridrag.server :as server]
            [hybridrag.tmp :as tmp]
            [hybridrag.web-client :as wc]))

(def ^:dynamic *app* nil)

(def passwords
  "Seed users (fx/principals + nobody) → password \"<user>-pw\"."
  (into {} (for [u (keys (assoc fx/principals "nobody" fx/nobody))] [u (str u "-pw")])))

(defn with-app-users
  ":each fixture: fresh app.dtlv with every seed user and a password."
  [t]
  (tmp/with-app-conn
    (fn [app]
      (doseq [[u {:keys [groups admin?]}] (assoc fx/principals "nobody" fx/nobody)]
        (users/create-user! app u {:groups groups
                                   :admin? admin?})
        (users/set-password! app u (passwords u)))
      (binding [*app* app] (t)))))

(defn ok-rerank [_ docs _]
  (vec (map-indexed (fn [i _] {:index i
                               :relevance-score (- (double i))}) docs)))

(defn chat-reply [content]
  (fn [_ _] {:model "stub-chat"
             :choices [{:message {:content content}
                        :finish_reason "stop"}]
             :usage {:prompt_tokens 10
                     :completion_tokens 5}}))

(defn handler
  "Ring handler over fx/*index* and *app*. opts: :chat-fn :rerank-fn
   :options (merged into the server options) :context (merged into the
   handler context)."
  [& {:keys [chat-fn rerank-fn options context]
      :or {chat-fn (chat-reply "特休依年資計算[1]。")
           rerank-fn ok-rerank}}]
  (server/ring-handler (merge {:options (merge {:session-secret-key "test-secret-key"} options)
                               :corpus-dir "corpus-sample"
                               ;; idle runner (never started unless a test does)
                               :ingest (runner/make {:index-conn fx/*index*
                                                     :corpus-dir "corpus-sample"
                                                     :data-dir "target/no-ingest-reports"
                                                     :root-read-groups []
                                                     :embed-fn fx/hash-embed})
                               :index-conn fx/*index*
                               :app-conn *app*
                               :search {:retriever (rd/retriever fx/*index* fx/hash-embed)
                                        :rerank-fn rerank-fn
                                        :chat-fn chat-fn
                                        :opts {}}}
                              context)))

(defn logged-in
  "A web client logged in as `user` against (apply handler args)."
  [user & args]
  (let [c (wc/client (apply handler args))]
    (wc/login! c user (passwords user))
    ;; login replaces the session (and its CSRF token), as a browser
    ;; would see on landing at /
    (wc/request! c :get "/")
    c))

(defn with-blocking-runner
  "Call (f runner gate) with an ingest runner over a fresh, empty index
   whose embed-fn blocks until `gate` is delivered — so a started job is
   guaranteed to still be running. Cleans up afterwards."
  [f]
  (let [idx (tmp/dir "blocking-idx")
        data (tmp/dir "blocking-data")
        conn (index-conn/open idx fx/dims)
        gate (promise)
        r (runner/make {:index-conn conn
                        :corpus-dir "corpus-sample"
                        :data-dir data
                        :root-read-groups []
                        :embed-fn (fn [t] @gate (fx/hash-embed t))})]
    (try
      (f r gate)
      (finally
        (deliver gate true)
        ;; let a running job finish before its index closes
        (loop [i 0]
          (when (and (< i 300) (= :running (:status (runner/latest r))))
            (Thread/sleep 100)
            (recur (inc i))))
        (d/close conn)
        (tmp/delete-tree! idx)
        (tmp/delete-tree! data)))))
