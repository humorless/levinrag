(ns replware.levinrag.web.ask
  "Q&A page (SPEC.md §12): the form posts to /ask with HTMX, which
   returns the answer, the sources panel and — when Debug is on — the
   Debug panel rendered from the stored trace."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datalevin.core :as d]
            [hiccup2.core :as hiccup]
            [replware.levinrag.api.ask :as api-ask]
            [replware.levinrag.trace :as trace]
            [replware.levinrag.web.layout :as layout]
            [ring.util.codec :as codec]))

(def ^:private max-query 1000)

(defn page [request]
  (layout/render request "問答"
                 [:form {:hx-post "/ask"
                         :hx-target "#result"
                         :hx-indicator "#busy"
                         :hx-disabled-elt "find button"
                         :class ["space-y-3"]}
                  [:textarea {:name "query"
                              :rows 3
                              :maxlength max-query
                              :required true
                              :placeholder "輸入問題，例如：特休天數怎麼計算？"
                              :class ["w-full" "rounded" "border" "border-slate-300" "bg-white" "p-3"]}]
                  [:div {:class ["flex" "items-center" "gap-4"]}
                   [:button {:type "submit"
                             :class ["rounded" "bg-slate-900" "px-4" "py-2" "text-white" "hover:bg-slate-800" "disabled:opacity-50"]} "送出"]
                   [:label {:class ["flex" "items-center" "gap-2" "text-sm" "text-slate-600"]}
                    [:input {:type "checkbox"
                             :name "debug"}] "Debug"]
                   [:span#busy {:class ["htmx-indicator" "text-sm" "text-slate-500"]} "產生回答中（約 10–30 秒）…"]]]
                 [:div#result {:class ["mt-8"]}]))

(defn doc-href
  "Viewer link for `path`, scrolled to `chunk-id` when given."
  [path chunk-id]
  (cond-> (str "/docs/" (str/join "/" (map codec/url-encode (str/split path #"/"))))
    chunk-id (str "?chunk=" (codec/url-encode chunk-id))))

(defn- notice [kind msg]
  [:div {:class ["rounded" "p-3" "text-sm"
                 (case kind
                   :error "bg-red-50 text-red-700"
                   :warn "bg-amber-50 text-amber-800"
                   "bg-slate-100 text-slate-700")]}
   msg])

(defn- answer-view
  "Answer text with each [n] turned into a link to source n."
  [text]
  (let [m (re-matcher #"\[(\d+)\]" text)]
    (loop [pos 0
           out []]
      (if (.find m)
        (recur (.end m)
               (conj out
                     (subs text pos (.start m))
                     [:a {:href (str "#src-" (.group m 1))
                          :class ["text-sky-700" "hover:underline"]}
                      (.group m 0)]))
        ;; a seq, not a vector: hiccup would read a vector whose first
        ;; element is a string as a tag named by that (model) text
        [:div {:class ["whitespace-pre-line" "leading-relaxed"]} (seq (conj out (subs text pos)))]))))

(defn- sources-view [citations]
  (when (seq citations)
    [:section {:class ["mt-6"]}
     [:h2 {:class ["mb-2" "text-sm" "font-semibold" "text-slate-500"]} "來源"]
     [:ol {:class ["space-y-3"]}
      (for [{:keys [n text chunk-ids]
             :as p} citations]
        [:li {:id (str "src-" n)
              :class ["rounded" "border" "border-slate-200" "bg-white" "p-3" "target:ring-2" "target:ring-sky-400"]}
         [:div {:class ["text-sm" "font-medium"]}
          (str "[" n "] " (:doc/title p))
          [:span {:class ["ml-2" "text-slate-500"]} (:section/trail p)]]
         [:p {:class ["mt-1" "text-sm" "text-slate-600"]}
          (let [t (str/trim text)] (if (> (count t) 200) (str (subs t 0 200) "…") t))]
         [:a {:href (doc-href (:doc/path p) (first chunk-ids))
              :class ["mt-1" "inline-block" "text-sm" "text-sky-700" "hover:underline"]} "開啟文件"]])]]))

(defn- rank-map [top] (into {} (map-indexed (fn [i [id _]] [id (inc i)]) top)))

(defn- fmt [pattern x] (if (number? x) (format pattern (double x)) "–"))

(defn- debug-view
  "Every candidate with its per-stage numbers. Ranks and rerank scores
   are read from the stored trace (its :top lists keep 20 per channel;
   below that the candidate's own channel rank is shown)."
  [candidates {:trace/keys [id stages degraded]}]
  (let [lex (rank-map (get-in stages [:lexical :top]))
        sem (rank-map (get-in stages [:semantic :top]))
        rerank (into {} (get-in stages [:rerank :scores]))
        rrf (into {} (get-in stages [:fusion :top]))
        rank (fn [m ch c] (or (m (:chunk/id c)) (get-in c [:channels ch :rank]) "–"))
        th (fn [label] [:th {:class ["px-2" "py-1" "text-left" "font-medium"]} label])
        td (fn [col v] [:td {:data-col col
                             :class ["px-2" "py-1" "font-mono"]} v])
        {:keys [generate flags]} stages]
    [:details {:open true
               :data-trace-id (str id)
               :class ["mt-8" "rounded" "border" "border-slate-200" "bg-white" "p-3"]}
     [:summary {:class ["cursor-pointer" "text-sm" "font-semibold"]} "Debug"]
     [:div {:class ["mt-3" "overflow-x-auto"]}
      [:table {:class ["w-full" "text-xs"]}
       [:thead [:tr (map th ["chunk id" "lexical" "semantic" "RRF" "graph" "rerank" "選中"])]]
       [:tbody
        (for [c candidates]
          [:tr {:data-chunk-id (:chunk/id c)
                :class [(when (:selected? c) "bg-emerald-50")]}
           (td "chunk" (:chunk/id c))
           (td "lexical" (str (rank lex :lexical c)))
           (td "semantic" (str (rank sem :semantic c)))
           (td "rrf" (fmt "%.4f" (or (rrf (:chunk/id c)) (:rrf c))))
           (td "graph" (if (get-in c [:channels :graph]) "✓" ""))
           (td "rerank" (fmt "%.3f" (or (rerank (:chunk/id c)) (:rerank c))))
           (td "selected" (if (:selected? c) "✓" ""))])]]]
     [:dl {:class ["mt-3" "grid" "grid-cols-2" "gap-x-4" "text-xs" "sm:grid-cols-4"]}
      (for [st [:lexical :semantic :fusion :graph :rerank :context :generate]]
        (list [:dt {:class ["text-slate-500"]} (str (name st) " ms")]
              [:dd {:data-stage (name st)
                    :class ["font-mono"]} (str (get-in stages [st :ms] "–"))]))]
     (when generate
       [:p {:class ["mt-2" "text-xs" "text-slate-500"]}
        (str "model " (:model generate) " · prompt " (:prompt-tokens generate)
             " · completion " (:completion-tokens generate)
             (when (seq (:invalid-citations generate)) (str " · 無效引用 " (:invalid-citations generate))))])
     (when (seq flags)
       [:p {:class ["mt-1" "text-xs" "text-amber-700"]} (str "flags " (str/join " " (map name flags)))])
     (when (seq degraded)
       [:p {:class ["mt-1" "text-xs" "text-red-700"]} "degraded "
        [:span {:data-degraded "true"} (str/join " " (map name degraded))]])]))

(defn- result-view [res trace debug?]
  [:div
   (when (contains? (set (:degraded res)) :rerank-failed)
     (notice :warn "重排序失敗，結果依 RRF 排序。"))
   (if (:no-evidence? res)
     (notice :info (:answer res))
     [:article {:class ["rounded" "border" "border-slate-200" "bg-white" "p-4"]}
      (answer-view (:answer res))])
   (sources-view (:citations res))
   (when debug? (debug-view (:candidates res) trace))])

(defn- fragment [content]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str (hiccup/html content))})

(defn- unexpected
  "Any other failure: logged, and shown as a notice (HTMX does not swap a
   500, so the user would otherwise see nothing)."
  [e]
  (log/error e "[ASK] unexpected failure")
  (fragment (notice :error "發生錯誤，請稍後再試。")))

(defn ask
  "POST /ask (HTMX): the result fragment for the #result target."
  [{:keys [form-params context principal]}]
  (let [query (str/trim (get form-params "query" ""))
        debug? (some? (get form-params "debug"))]
    (cond
      (str/blank? query) (fragment (notice :info "請輸入問題。"))
      (> (count query) max-query) (fragment (notice :info (str "問題最長 " max-query " 字。")))
      :else
      (try
        (let [{:keys [res trace-id]} (api-ask/answer-and-trace! context principal query (get-in context [:search :opts]))]
          (fragment (result-view res (when debug? (some->> (trace/fetch (d/db (:app-conn context)) trace-id)
                                                           (trace/view-for principal)))
                                 debug?)))
        (catch clojure.lang.ExceptionInfo e
          (if-let [endpoint (:llm/endpoint (ex-data e))]
            (let [id (trace/write-failure! (:app-conn context) {:username (:username principal)
                                                                :kind :ask
                                                                :query query
                                                                :endpoint endpoint
                                                                :message (ex-message e)})]
              (log/warn "[ASK] dependency failed:" endpoint (ex-message e))
              (fragment (notice :error (str "問答服務暫時無法使用（" (name endpoint) "）。"
                                            (when id (str " trace " id))))))
            (unexpected e)))
        (catch Exception e
          (unexpected e))))))
