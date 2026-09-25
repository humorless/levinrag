(ns replware.levinrag.web.docs
  "GET /docs/*path (SPEC.md §12): the source document rendered with
   commonmark, cited chunk highlighted. Unreadable, unknown or missing →
   404."
  (:require [datalevin.core :as d]
            [replware.levinrag.docs :as docs]
            [replware.levinrag.ingest.writer :as writer]
            [replware.levinrag.web.layout :as layout]))

(defn page
  [{:keys [context principal path-params query-params]
    :as request}]
  (let [path (:path path-params)
        db (d/db (:index-conn context))
        found (if (:admin? principal)
                (docs/lookup-admin db path)
                (docs/lookup-acl db principal path))
        file (when found (docs/source-file (:corpus-dir context) path))]
    (if-not file
      (layout/not-found request)
      (let [{:keys [doc chunks]} found
            raw (java.nio.file.Files/readAllBytes (.toPath file))
            changed? (not= (writer/sha256-hex raw) (:doc/hash doc))]
        (layout/render request (:doc/title doc)
                       [:header {:class ["mb-6"]}
                        [:h1 {:class ["text-2xl" "font-semibold"]} (:doc/title doc)]
                        [:p {:class ["mt-1" "font-mono" "text-xs" "text-slate-500"]} (:doc/path doc)]]
                       (when changed?
                         [:p {:class ["mb-4" "rounded" "bg-amber-50" "p-3" "text-sm" "text-amber-800"]}
                          "文件在建立索引後已變更，標示的位置可能不準確。"])
                       [:article {:class ["md-doc" "space-y-3" "rounded" "border" "border-slate-200" "bg-white" "p-6"]}
                        (docs/render-blocks (String. ^bytes raw "UTF-8") chunks (get query-params "chunk"))])))))
