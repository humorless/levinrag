(ns replware.levinrag.web.ask-test
  "Q&A page and the /ask fragment (SPEC.md §12): answer with clickable
   [n], sources panel, Debug panel equal to the trace, errors, escaping
   and §18.3 at the web level."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [hickory.core :as hickory]
            [hickory.select :as s]
            [replware.levinrag.fixtures :as fx]
            [replware.levinrag.llm.answer :as answer]
            [replware.levinrag.trace :as trace]
            [replware.levinrag.web-client :as wc]
            [replware.levinrag.web-fixtures :as wf]
            [replware.levinrag.web.ask :as web-ask]))

(use-fixtures :once fx/with-sample-index)
(use-fixtures :each wf/with-app-users)

(defn- ask! [c query & {:keys [debug?]}]
  (wc/post! c "/ask" (cond-> {"query" query} debug? (assoc "debug" "on"))
            :headers {"hx-request" "true"}))

(defn- dom [html] (hickory/as-hickory (hickory/parse html)))

(defn- text [node]
  (cond (string? node) node
        (map? node) (apply str (map text (:content node)))
        :else ""))

(defn- sel [selector html] (s/select selector (dom html)))

(deftest test-page
  (let [body (:body (wc/request! (wf/logged-in "alice") :get "/"))]
    (is (= 1 (count (sel (s/attr :hx-post #(= % "/ask")) body))))
    (is (= 1 (count (sel (s/and (s/tag :textarea) (s/attr :name #(= % "query"))) body))))
    (is (= 1 (count (sel (s/attr :name #(= % "debug")) body))))
    (is (= 1 (count (sel (s/id "busy") body))))
    (is (= 1 (count (sel (s/id "result") body))))))

(deftest test-ask-fragment
  (let [{:keys [status body]} (ask! (wf/logged-in "alice") "特休天數怎麼計算？")]
    (is (= 200 status))
    (is (not (str/includes? body "<html")))
    (is (seq (sel (s/attr :href #(= % "#src-1")) body)))
    (let [[src] (sel (s/id "src-1") body)]
      (is src)
      (is (re-find #"\S" (text src)))
      (is (seq (s/select (s/attr :href #(re-find #"^/docs/.+\?chunk=.+" %)) src))))
    (is (empty? (sel (s/attr :data-trace-id some?) body)) "no Debug panel unless asked")))

(defn- rank-map [top] (into {} (map-indexed (fn [i [id _]] [id (inc i)]) top)))

(deftest test-debug-panel-matches-trace
  (let [body (:body (ask! (wf/logged-in "alice") "特休天數怎麼計算？" :debug? true))
        [panel] (sel (s/attr :data-trace-id some?) body)
        t (trace/fetch (d/db wf/*app*) (parse-uuid (get-in panel [:attrs :data-trace-id])))
        stages (:trace/stages t)
        lex (rank-map (get-in stages [:lexical :top]))
        sem (rank-map (get-in stages [:semantic :top]))
        rerank (into {} (get-in stages [:rerank :scores]))
        rows (s/select (s/attr :data-chunk-id some?) panel)
        cell (fn [row col] (str/trim (text (first (s/select (s/attr :data-col #(= % col)) row)))))]
    (is (seq rows))
    (doseq [row rows
            :let [id (get-in row [:attrs :data-chunk-id])]]
      (is (= id (cell row "chunk")))
      (when-let [r (lex id)] (is (= (str r) (cell row "lexical")) id))
      (when-let [r (sem id)] (is (= (str r) (cell row "semantic")) id))
      (when-let [sc (rerank id)] (is (= (format "%.3f" (double sc)) (cell row "rerank")) id)))
    (testing "every trace rank is shown"
      (is (every? (set (map #(get-in % [:attrs :data-chunk-id]) rows)) (keys lex))))
    (testing "stage timings are the trace's"
      (doseq [st [:lexical :semantic :fusion :graph :rerank :context :generate]
              :let [[el] (s/select (s/attr :data-stage #(= % (name st))) panel)]]
        (is el (str st))
        (is (= (str (get-in stages [st :ms])) (str/trim (text el))) (str st))))))

(deftest test-no-evidence-and-errors
  (is (str/includes? (:body (ask! (wf/logged-in "nobody") "特休")) answer/no-evidence-message))
  (is (str/includes? (:body (ask! (wf/logged-in "alice" :chat-fn (fn [_ _] (throw (ex-info "down" {:llm/endpoint :chat})))) "特休"))
                     "問答服務暫時無法使用（chat）"))
  (testing "the notice names the failure trace"
    (let [body (:body (ask! (wf/logged-in "alice" :chat-fn (fn [_ _] (throw (ex-info "down" {:llm/endpoint :chat})))) "特休"))
          id (second (re-find #"trace ([0-9a-f-]{36})" body))]
      (is (some? id))
      (is (= :chat (get-in (trace/fetch (d/db wf/*app*) (parse-uuid id)) [:trace/stages :error :endpoint]))))) 
  (let [{:keys [status body]} (ask! (wf/logged-in "alice" :rerank-fn (fn [& _] (throw (ex-info "x" {})))) "特休")]
    (is (= 200 status))
    (is (str/includes? body "重排序失敗"))))

(deftest test-answer-escaped
  (let [body (:body (ask! (wf/logged-in "alice" :chat-fn (wf/chat-reply "<script>alert(1)</script>[1]")) "特休"))]
    (is (str/includes? body "&lt;script&gt;"))
    (is (not (str/includes? body "<script>alert")))))

(deftest test-empty-query
  (let [called (atom false)
        body (:body (ask! (wf/logged-in "alice" :chat-fn (fn [& _] (reset! called true))) "   "))]
    (is (str/includes? body "請輸入問題。"))
    (is (false? @called))))

(deftest test-web-never-leaks
  ;; §18.3: no Debug row and no source link for an unreadable doc. (Plain
  ;; substring search is too strict: a readable doc may link to a
  ;; restricted one, and its text then contains that path.)
  (let [groups (fx/doc-groups fx/*index*)
        titles (into {} (d/q '[:find ?p ?t :where [?d :doc/path ?p] [?d :doc/title ?t]] (d/db fx/*index*)))
        cite-all (wf/chat-reply (apply str (map #(str "[" % "]") (range 1 21))))]
    (doseq [[user principal] (assoc fx/principals "nobody" fx/nobody)
            :let [c (wf/logged-in user :chat-fn cite-all)]
            [path title] titles
            :when (not (fx/readable? groups principal path))
            :let [body (:body (ask! c title :debug? true))
                  rows (map #(get-in % [:attrs :data-chunk-id]) (sel (s/attr :data-chunk-id some?) body))
                  links (map #(get-in % [:attrs :href]) (sel (s/attr :href #(str/starts-with? % "/docs/")) body))]]
      (is (not-any? #(str/starts-with? % (str path "::")) rows) (str user " saw " path " in Debug"))
      (is (not-any? #(str/starts-with? % (str (web-ask/doc-href path nil) "?")) links) (str user " saw " path " in sources")))))

(deftest test-unexpected-error-is-a-notice
  ;; HTMX does not swap 5xx: an unexpected failure must still come back
  ;; as a 200 fragment the user can see
  (let [{:keys [status body]} (ask! (wf/logged-in "alice" :chat-fn (fn [_ _] (throw (RuntimeException. "boom")))) "特休")]
    (is (= 200 status))
    (is (str/includes? body "發生錯誤"))))

(deftest test-debug-panel-degraded-and-rrf-from-trace
  (let [body (:body (ask! (wf/logged-in "alice" :rerank-fn (fn [& _] (throw (ex-info "x" {})))) "特休" :debug? true))
        [panel] (sel (s/attr :data-trace-id some?) body)
        t (trace/fetch (d/db wf/*app*) (parse-uuid (get-in panel [:attrs :data-trace-id])))
        rrf (into {} (get-in t [:trace/stages :fusion :top]))
        [deg] (s/select (s/attr :data-degraded some?) panel)]
    (is (= "rerank-failed" (str/trim (text deg))))
    (doseq [row (s/select (s/attr :data-chunk-id some?) panel)
            :let [id (get-in row [:attrs :data-chunk-id])]
            :when (rrf id)]
      (is (= (format "%.4f" (double (rrf id)))
             (str/trim (text (first (s/select (s/attr :data-col #(= % "rrf")) row)))))
          id))))

(deftest test-layout-loads-error-handler
  (is (str/includes? (:body (wc/request! (wf/logged-in "alice") :get "/")) "/assets/js/app.js")))

(deftest test-debug-panel-hides-acl-starvation-from-non-admins
  ;; the flag says documents the user cannot read matched; admins keep it
  (let [fetch trace/fetch]
    (with-redefs [trace/fetch (fn [db id] (update-in (fetch db id) [:trace/stages :flags] (fnil conj #{}) :acl-starvation))]
      (is (not (str/includes? (:body (ask! (wf/logged-in "alice") "特休" :debug? true)) "acl-starvation")))
      (is (str/includes? (:body (ask! (wf/logged-in "admin") "特休" :debug? true)) "acl-starvation")))))
