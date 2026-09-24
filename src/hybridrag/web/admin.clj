(ns hybridrag.web.admin
  "Admin page (SPEC.md §12): run an incremental ingest, follow its
   status, see the latest report and index lag, browse recent traces."
  (:require [clojure.pprint :as pprint]
            [datalevin.core :as d]
            [hiccup2.core :as hiccup]
            [hybridrag.ingest.report :as report]
            [hybridrag.ingest.runner :as runner]
            [hybridrag.trace :as trace]
            [hybridrag.web.layout :as layout]))

(defn- fragment [content]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str (hiccup/html content))})

(defn- fmt-time [^java.util.Date d]
  (when d (.format (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss") d)))

(defn- status-view
  "Status of `job`; while it runs the element re-fetches itself every 2 s."
  [job]
  (case (:status job)
    nil [:div#ingest-status {:class ["text-sm" "text-slate-500"]} "尚未在此伺服器執行 ingest。"]
    :running [:div#ingest-status {:hx-get "/admin/ingest/status"
                                  :hx-trigger "every 2s"
                                  :hx-swap "outerHTML"
                                  :class ["text-sm" "text-sky-700"]}
              (str "執行中（開始於 " (fmt-time (:started-at job)) "）…")]
    :done [:div#ingest-status {:class ["text-sm"]}
           [:p {:class ["text-emerald-700"]} (str "完成（" (fmt-time (:finished-at job)) "）")]
           [:pre {:class ["mt-2" "overflow-x-auto" "rounded" "bg-slate-100" "p-2" "text-xs"]} (report/summary (:report job))]]
    :failed [:div#ingest-status {:class ["text-sm" "text-red-700"]}
             (str "失敗（" (fmt-time (:finished-at job)) "）：" (:error job))]))

(defn page [{:keys [context]
             :as request}]
  (let [r (:ingest context)
        rep (or (:report (last (filter :report (runner/history r)))) (runner/latest-report r))
        traces (trace/recent (d/db (:app-conn context)) 50)]
    (layout/render request "管理"
                   [:h1 {:class ["text-2xl" "font-semibold"]} "管理"]
                   [:section {:class ["mt-6" "rounded" "border" "border-slate-200" "bg-white" "p-4"]}
                    [:h2 {:class ["font-semibold"]} "Ingest"]
                    [:div {:class ["mt-3" "flex" "items-start" "gap-4"]}
                     [:button {:hx-post "/admin/ingest"
                               :hx-target "#ingest-status"
                               :hx-swap "outerHTML"
                               :class ["rounded" "bg-slate-900" "px-3" "py-1.5" "text-sm" "text-white" "hover:bg-slate-800"]}
                      "執行增量 ingest"]
                     (status-view (runner/latest r))]
                    [:h3 {:class ["mt-4" "text-sm" "font-semibold" "text-slate-500"]} "最近一次報告"]
                    (if rep
                      [:pre {:class ["mt-1" "overflow-x-auto" "rounded" "bg-slate-100" "p-2" "text-xs"]} (report/summary rep)]
                      [:p {:class ["mt-1" "text-sm" "text-slate-500"]} "尚無 ingest 紀錄"])]
                   [:section {:class ["mt-6" "rounded" "border" "border-slate-200" "bg-white" "p-4"]}
                    [:h2 {:class ["font-semibold"]} "最近 50 筆 trace"]
                    [:table {:class ["mt-3" "w-full" "text-sm"]}
                     [:thead [:tr (for [h ["時間" "使用者" "類型" "查詢"]]
                                    [:th {:class ["px-2" "py-1" "text-left" "font-medium" "text-slate-500"]} h])]]
                     [:tbody
                      (for [{:trace/keys [id username kind query at]} traces]
                        [:tr {:class ["border-t" "border-slate-100"]}
                         [:td {:class ["px-2" "py-1" "font-mono" "text-xs"]}
                          [:a {:href (str "/admin/traces/" id)
                               :class ["text-sky-700" "hover:underline"]} (fmt-time at)]]
                         [:td {:class ["px-2" "py-1"]} username]
                         [:td {:class ["px-2" "py-1"]} (name kind)]
                         [:td {:class ["px-2" "py-1"]} query]])]]])))

(defn start-ingest [{:keys [context]}]
  (let [{:keys [job conflict]} (runner/start! (:ingest context))]
    (fragment (if conflict
                [:div
                 [:p {:class ["text-sm" "text-amber-800"]} "已有 ingest 在執行"]
                 (status-view conflict)]
                (status-view job)))))

(defn ingest-status [{:keys [context]}]
  (fragment (status-view (runner/latest (:ingest context)))))

(defn trace-page [{:keys [context path-params]
                   :as request}]
  (if-let [t (some->> (parse-uuid (:id path-params)) (trace/fetch (d/db (:app-conn context))))]
    (layout/render request "Trace"
                   [:h1 {:class ["text-xl" "font-semibold"]} (:trace/query t)]
                   [:p {:class ["mt-1" "text-sm" "text-slate-500"]}
                    (str (fmt-time (:trace/at t)) " · " (:trace/username t) " · " (name (:trace/kind t))
                         (when (seq (:trace/degraded t)) (str " · degraded " (vec (:trace/degraded t)))))]
                   (when-let [a (:trace/answer t)]
                     [:pre {:class ["mt-4" "whitespace-pre-wrap" "rounded" "border" "border-slate-200" "bg-white" "p-3" "text-sm"]} a])
                   [:table {:class ["mt-4" "w-full" "text-xs"]}
                    [:tbody
                     (for [[stage v] (sort-by (comp name key) (:trace/stages t))]
                       [:tr {:class ["border-t" "border-slate-100" "align-top"]}
                        [:th {:class ["px-2" "py-1" "text-left" "font-medium"]} (name stage)]
                        [:td {:class ["px-2" "py-1"]}
                         [:pre {:class ["whitespace-pre-wrap" "font-mono"]} (with-out-str (pprint/pprint v))]]])]])
    (layout/not-found request)))
