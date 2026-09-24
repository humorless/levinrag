(ns hybridrag.retrieval.system
  "Integrant component bundling what the search pipeline needs: the
   Retriever over index.dtlv, the rerank function and pipeline options."
  (:require [hybridrag.config :as config]
            [hybridrag.llm.embed :as embed]
            [hybridrag.llm.rerank-client :as rerank-client]
            [hybridrag.retrieval.datalevin :as rd]
            [integrant.core :as ig]))

(defmethod ig/init-key ::search
  [_ {:keys [index-conn opts]}]
  {:retriever (rd/retriever index-conn #(embed/embed-all! (config/embed-config) % 32))
   :rerank-fn #(rerank-client/rerank! (config/rerank-config) %1 %2 %3)
   :opts (or opts {})})
