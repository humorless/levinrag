(ns hybridrag.api.docs
  "GET /api/v1/docs/{path} (SPEC.md §11): doc metadata and its chunk
   list; 404 (not 403) when unreadable, so existence is not leaked."
  (:require [datalevin.core :as d]
            [hybridrag.auth.middleware :as auth]
            [hybridrag.docs :as docs]))

(defn handler
  [{:keys [context principal path-params]}]
  (if-let [{:keys [doc chunks]} (let [db (d/db (:index-conn context))
                                        path (:path path-params)]
                                    (if (:admin? principal)
                                      (docs/lookup-admin db path)
                                      (docs/lookup-acl db principal path)))]
    {:status 200
     :body {:doc_path (:doc/path doc)
            :title (:doc/title doc)
            :tags (vec (:doc/tags doc))
            :chunks (mapv (fn [c] {:chunk_id (:chunk/id c)
                                   :ordinal (:chunk/ordinal c)
                                   :section_trail (:section/trail c)
                                   :char_range [(:chunk/char-start c) (:chunk/char-end c)]})
                          chunks)}}
    (auth/error-response 404 "not_found" "找不到文件。")))
