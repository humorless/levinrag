(ns replware.levinrag.api.search-test
  "POST /api/v1/search through the real Ring handler: auth, validation,
   response shape, trace, degradation, and SPEC.md §18.3 at the API level."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [jsonista.core :as json]
            [replware.levinrag.auth.token :as token]
            [replware.levinrag.auth.users :as users]
            [replware.levinrag.fixtures :as fx]
            [replware.levinrag.retrieval.datalevin :as rd]
            [replware.levinrag.server :as server]
            [replware.levinrag.tmp :as tmp]
            [replware.levinrag.trace :as trace]))

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

(defn- handler [rerank-fn embed-fn opts]
  (server/ring-handler {:options {:session-secret-key "test-secret-key"}
                        :index-conn fx/*index*
                        :app-conn *app*
                        :search {:retriever (rd/retriever fx/*index* embed-fn)
                                 :rerank-fn rerank-fn
                                 :opts opts}}))

(defn- ok-rerank [_ docs _]
  (vec (map-indexed (fn [i _] {:index i
                               :relevance-score (- (double i))}) docs)))

(defn- post
  ([body user] (post body user ok-rerank fx/hash-embed))
  ([body user rerank-fn embed-fn] (post body user rerank-fn embed-fn nil))
  ([body user rerank-fn embed-fn rerank-input]
   (let [resp ((handler rerank-fn embed-fn (if rerank-input {:rerank-input rerank-input} {}))
               {:request-method :post
                :uri "/api/v1/search"
                :scheme :http
                :server-name "localhost"
                :headers (cond-> {"content-type" "application/json"
                                  "accept" "application/json"}
                           user (assoc "authorization" (str "Bearer " (*tokens* user))))
                :body (java.io.ByteArrayInputStream. (.getBytes (json/write-value-as-string body) "UTF-8"))})]
     (update resp :body #(when % (json/read-value (if (string? %) % (slurp %)) json/keyword-keys-object-mapper))))))

(deftest test-auth-and-validation
  (is (= 401 (:status (post {:query "特休"} nil))))
  (is (= "unauthorized" (get-in (post {:query "特休"} nil) [:body :error :code])))
  (doseq [bad [{} {:query ""} {:query (apply str (repeat 1001 "字"))} {:query "x"
                                                                      :final_k 0}]]
    (let [resp (post bad "alice")]
      (is (= 400 (:status resp)) (pr-str bad))
      (is (= "invalid_request" (get-in resp [:body :error :code]))))))

(deftest test-search-response-and-trace
  (let [{:keys [status body]} (post {:query "特休天數怎麼計算？"} "alice")]
    (is (= 200 status))
    ;; the stub embedder is noise, so only lexical guarantees leave.md shows up
    (is (some #(= "hr/leave.md" (:doc_path %)) (:passages body)))
    (is (every? #(contains? % :section_trail) (:passages body)))
    (is (every? #(contains? % :chunk_id) (:candidates body)))
    (is (= [] (:degraded body)))
    (testing "trace is stored with stages but no chunk text"
      (let [t (trace/fetch (d/db *app*) (parse-uuid (:trace_id body)))]
        (is (= "alice" (:trace/username t)))
        (is (= :search (:trace/kind t)))
        (is (seq (get-in t [:trace/stages :lexical :top])))
        (is (not (str/includes? (pr-str (:trace/stages t)) (get-in body [:passages 0 :text]))))))))

(deftest test-options-and-degradation
  (is (= 2 (count (filter :selected (:candidates (:body (post {:query "特休"
                                                               :final_k 2} "alice")))))))
  (is (not-any? #(get-in % [:channels :graph])
                (:candidates (:body (post {:query "新人報到 帳號申請"
                                           :graph false} "alice")))))
  (is (some #(get-in % [:channels :graph])
            (:candidates (:body (post {:query "新人報到 帳號申請"} "alice" ok-rerank fx/hash-embed 3))))
      "graph on (with a small rerank-input so fused docs do not cover every link)")
  (testing "rerank down → 200 with degraded flag"
    (let [{:keys [status body]} (post {:query "特休"} "alice" (fn [& _] (throw (ex-info "x" {}))) fx/hash-embed)]
      (is (= 200 status))
      (is (= ["rerank_failed"] (:degraded body)))
      (is (seq (:passages body)))))
  (testing "embedding down → 503"
    (let [{:keys [status body]} (post {:query "特休"} "alice" ok-rerank
                                      (fn [_] (throw (ex-info "vLLM embed timed out" {:llm/endpoint :embed}))))]
      (is (= 503 status))
      (is (= "dependency_unavailable" (get-in body [:error :code])))
      (testing "and still writes a trace"
        (let [t (trace/fetch (d/db *app*) (parse-uuid (:trace_id body)))]
          (is (= "alice" (:trace/username t)))
          (is (= :search (:trace/kind t)))
          (is (= "特休" (:trace/query t)))
          (is (= {:endpoint :embed
                  :message "vLLM embed timed out"} (get-in t [:trace/stages :error])))
          (is (= [:dependency-failed] (vec (:trace/degraded t)))))))))

(deftest test-search-never-leaks
  ;; §18.3: for every restricted doc and every seed user who cannot read
  ;; it, neither passages nor candidates (any channel, graph on) show it
  (let [groups (fx/doc-groups fx/*index*)
        titles (into {} (d/q '[:find ?p ?t :where [?d :doc/path ?p] [?d :doc/title ?t]] (d/db fx/*index*)))]
    (doseq [[user principal] (assoc fx/principals "nobody" fx/nobody)
            [path title] titles
            :when (not (fx/readable? groups principal path))
            :let [body (:body (post {:query title} user))
                  seen (set (concat (map :doc_path (:passages body)) (map :doc_path (:candidates body))))]]
      (is (not (seen path)) (str user " saw " path)))
    (testing "the nobody user gets nothing at all"
      (let [body (:body (post {:query "員工手冊"} "nobody"))]
        (is (= [] (:passages body) (:candidates body)))))))
