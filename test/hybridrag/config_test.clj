(ns hybridrag.config-test
  (:require [clojure.test :refer [deftest is]]
            [hybridrag.config :as config]
            [integrant-extras.core :as ig-extras]))

(defn- with-env [m f]
  (with-redefs [config/env (fn [k] (get m k))] (f)))

(deftest test-chat-extra-body
  (with-env {"VLLM_CHAT_EXTRA_BODY" "{\"chat_template_kwargs\": {\"enable_thinking\": false}}"}
    #(is (= {:chat_template_kwargs {:enable_thinking false}} (:extra-body (config/chat-config)))))
  (with-env {}
    #(is (nil? (:extra-body (config/chat-config)))))
  (with-env {"VLLM_CHAT_EXTRA_BODY" "not json"}
    #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"VLLM_CHAT_EXTRA_BODY" (config/chat-config)))))

(deftest test-rerank-min-score-config
  ;; docs/spikes/rerank-threshold.md: -7.0 for llama.cpp raw logits,
  ;; overridable per backend with VLLM_RERANK_MIN_SCORE
  (let [cfg (ig-extras/get-config :default)]
    (is (= -7.0 (get-in cfg [:hybridrag.retrieval.system/search :opts :rerank-min-score])))))
