(ns hybridrag.api.ask-test
  "POST /api/v1/ask through the real Ring handler with a stub chat:
   auth, validation, response shape, trace, no-evidence, dependency
   failures and SPEC.md §18.3 at the API level. test-ask-real-model
   (:vllm) uses the real models and is skipped unless
   VLLM_CHAT_BASE_URL is set."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [hybridrag.auth.token :as token]
            [hybridrag.auth.users :as users]
            [hybridrag.config :as config]
            [hybridrag.db.index-conn :as index-conn]
            [hybridrag.fixtures :as fx]
            [hybridrag.ingest.job :as job]
            [hybridrag.llm.answer :as answer]
            [hybridrag.llm.embed :as embed]
            [hybridrag.retrieval.datalevin :as rd]
            [hybridrag.retrieval.system :as system]
            [hybridrag.server :as server]
            [hybridrag.tmp :as tmp]
            [hybridrag.trace :as trace]
            [integrant.core :as ig]
            [jsonista.core :as json]))

(use-fixtures :once fx/with-sample-index)

(def ^:dynamic *app* nil)
(def ^:dynamic *tokens* nil)

(use-fixtures :each
  (fn [t]
    (tmp/with-app-conn
      (fn [app]
        (let [tokens (into {} (for [[u {:keys [groups admin?]}] (assoc fx/principals "nobody" fx/nobody)]
                                (do (users/create-user! app u {:groups groups
                                                               :admin? admin?})
                                    [u (:token (token/create-token! app u "test"))])))]
          (binding [*app* app *tokens* tokens] (t)))))))

(defn- ok-rerank [_ docs _]
  (vec (map-indexed (fn [i _] {:index i
                               :relevance-score (- (double i))}) docs)))

(defn- chat-reply [content]
  (fn [_ _] {:model "stub-chat"
             :choices [{:message {:content content}}]
             :usage {:prompt_tokens 10
                     :completion_tokens 5}}))

(defn- stub-search [chat-fn & {:keys [rerank-fn embed-fn]
                               :or {rerank-fn ok-rerank
                                    embed-fn fx/hash-embed}}]
  {:retriever (rd/retriever fx/*index* embed-fn)
   :rerank-fn rerank-fn
   :chat-fn chat-fn
   :opts {}})

(defn- post
  "POST body to /api/v1/ask as `user` (nil = no token) against `search`."
  [search body user]
  (let [resp ((server/ring-handler {:options {:session-secret-key "test-secret-key"}
                                    :index-conn fx/*index*
                                    :app-conn *app*
                                    :search search})
              {:request-method :post
               :uri "/api/v1/ask"
               :scheme :http
               :server-name "localhost"
               :headers (cond-> {"content-type" "application/json"
                                 "accept" "application/json"}
                          user (assoc "authorization" (str "Bearer " (*tokens* user))))
               :body (java.io.ByteArrayInputStream. (.getBytes (json/write-value-as-string body) "UTF-8"))})]
    (update resp :body #(when % (json/read-value (if (string? %) % (slurp %)) json/keyword-keys-object-mapper)))))

(def ^:private never-called (fn [& _] (throw (ex-info "chat must not be called" {}))))

(deftest test-auth-and-validation
  (let [s (stub-search never-called)]
    (is (= 401 (:status (post s {:query "特休"} nil))))
    (doseq [bad [{} {:query ""} {:query (apply str (repeat 1001 "字"))}
                 {:query "x"
                  :final_k 0} {:query "x"
                               :debug "yes"}]]
      (let [resp (post s bad "alice")]
        (is (= 400 (:status resp)) (pr-str bad))
        (is (= "invalid_request" (get-in resp [:body :error :code])))))))

(deftest test-ask-response-and-trace
  (let [{:keys [status body]} (post (stub-search (chat-reply "特休依年資計算[1]。")) {:query "特休天數怎麼計算？"} "alice")]
    (is (= 200 status))
    (is (= "特休依年資計算[1]。" (:answer body)))
    (is (= 1 (count (:citations body))))
    (is (= 1 (get-in body [:citations 0 :n])))
    (is (every? #(contains? (first (:citations body)) %) [:doc_path :doc_title :section_trail :text]))
    (is (false? (:no_evidence body)))
    (is (= [] (:degraded body)))
    (is (not (contains? body :candidates)))
    (testing "trace: kind, answer, generate stage, no passage text"
      (let [t (trace/fetch (d/db *app*) (parse-uuid (:trace_id body)))]
        (is (= :ask (:trace/kind t)))
        (is (= "alice" (:trace/username t)))
        (is (= "特休依年資計算[1]。" (:trace/answer t)))
        (is (= "stub-chat" (get-in t [:trace/stages :generate :model])))
        (is (not (str/includes? (pr-str (:trace/stages t)) (get-in body [:citations 0 :text])))))))
  (testing "debug adds candidates"
    (let [body (:body (post (stub-search (chat-reply "x[1]")) {:query "特休"
                                                              :debug true} "alice"))]
      (is (seq (:candidates body)))
      (is (every? #(contains? % :chunk_id) (:candidates body))))))

(deftest test-ask-no-evidence
  (let [{:keys [status body]} (post (stub-search never-called) {:query "員工手冊"} "nobody")]
    (is (= 200 status))
    (is (true? (:no_evidence body)))
    (is (= answer/no-evidence-message (:answer body)))
    (is (= [] (:citations body)))))

(deftest test-ask-errors
  (testing "chat 200 with an error payload → 503"
    (let [{:keys [status body]} (post (stub-search (fn [_ _] {:error "model not loaded"})) {:query "特休"} "alice")]
      (is (= 503 status))
      (is (= "dependency_unavailable" (get-in body [:error :code])))))
  (testing "chat timeout → 503"
    (is (= 503 (:status (post (stub-search (fn [_ _] (throw (ex-info "timed out" {:llm/endpoint :chat}))))
                              {:query "特休"} "alice")))))
  (testing "rerank down → 200, degraded, still answered"
    (let [{:keys [status body]} (post (stub-search (chat-reply "x[1]") :rerank-fn (fn [& _] (throw (ex-info "x" {}))))
                                      {:query "特休"} "alice")]
      (is (= 200 status))
      (is (= ["rerank_failed"] (:degraded body)))
      (is (= "x[1]" (:answer body))))))

(deftest test-ask-never-leaks
  ;; §18.3: a chat that cites every passage still only cites readable docs
  (let [groups (fx/doc-groups fx/*index*)
        titles (into {} (d/q '[:find ?p ?t :where [?d :doc/path ?p] [?d :doc/title ?t]] (d/db fx/*index*)))
        s (stub-search (chat-reply (apply str (map #(str "[" % "]") (range 1 21)))))]
    (doseq [[user principal] (assoc fx/principals "nobody" fx/nobody)
            [path title] titles
            :when (not (fx/readable? groups principal path))
            :let [body (:body (post s {:query title
                                       :debug true} user))
                  seen (set (concat (map :doc_path (:citations body)) (map :doc_path (:candidates body))))]]
      (is (not (seen path)) (str user " saw " path)))))

(deftest test-system-chat-fn-needs-base-url
  (with-redefs [config/chat-config (constantly {:base-url nil
                                                :model "m"})]
    (let [chat-fn (:chat-fn (ig/init-key ::system/search {:index-conn fx/*index*}))
          e (try (chat-fn [] answer/default-opts) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :chat (:llm/endpoint (ex-data e)))))))

(deftest ^:vllm test-ask-real-model
  (if-not (System/getenv "VLLM_CHAT_BASE_URL")
    (println "SKIP test-ask-real-model: VLLM_CHAT_BASE_URL not set")
    (let [dir (tmp/dir "ask-real")
          cfg (config/embed-config)
          conn (index-conn/open dir (:dims cfg))]
      (try
        (job/ingest! conn {:corpus-dir "corpus-sample"
                           :embed-fn #(embed/embed-all! cfg % 32)})
        (let [search (ig/init-key ::system/search {:index-conn conn})
              t0 (System/nanoTime)
              {:keys [status body]} (post search {:query "特休天數怎麼計算？"} "alice")]
          (println "real /ask:" (quot (- (System/nanoTime) t0) 1000000) "ms\n" (:answer body))
          (is (= 200 status))
          (is (false? (:no_evidence body)))
          (is (not (str/blank? (:answer body))))
          (is (not (str/includes? (:answer body) "<think>")))
          (is (some #(= "hr/leave.md" (:doc_path %)) (:citations body))))
        (finally (d/close conn) (tmp/delete-tree! dir))))))
