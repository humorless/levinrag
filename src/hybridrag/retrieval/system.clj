(ns hybridrag.retrieval.system
  "Integrant component bundling what search and answer need: the
   Retriever over index.dtlv, the rerank and chat functions and pipeline
   options."
  (:require [hybridrag.config :as config]
            [hybridrag.llm.chat :as chat]
            [hybridrag.llm.embed :as embed]
            [hybridrag.llm.rerank-client :as rerank-client]
            [hybridrag.retrieval.datalevin :as rd]
            [integrant.core :as ig]))

(defn- chat-fn
  "(fn [messages opts] response) over the configured chat endpoint;
   opts without :extra-body get VLLM_CHAT_EXTRA_BODY's."
  [messages opts]
  (let [{:keys [base-url extra-body]
         :as cfg} (config/chat-config)]
    (when-not base-url
      (throw (ex-info "VLLM_CHAT_BASE_URL is not set" {:llm/endpoint :chat
                                                        :http/status nil})))
    (chat/complete! cfg messages (update opts :extra-body #(or % extra-body)))))

(defmethod ig/init-key ::search
  [_ {:keys [index-conn opts]}]
  ;; fail at startup on a bad VLLM_CHAT_EXTRA_BODY instead of on every
  ;; /ask (chat-fn still reads the config per call)
  (config/chat-config)
  (let [embed-fn #(embed/embed-all! (config/embed-config) % 32)]
    {:retriever (rd/retriever index-conn embed-fn)
     :embed-fn embed-fn
     :rerank-fn #(rerank-client/rerank! (config/rerank-config) %1 %2 %3)
     :chat-fn chat-fn
     :opts (or opts {})}))
