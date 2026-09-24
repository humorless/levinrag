(ns hybridrag.config-test
  (:require [clojure.test :refer [deftest is]]
            [hybridrag.config :as config]))

(defn- with-env [m f]
  (with-redefs [config/env (fn [k] (get m k))] (f)))

(deftest test-chat-extra-body
  (with-env {"VLLM_CHAT_EXTRA_BODY" "{\"chat_template_kwargs\": {\"enable_thinking\": false}}"}
    #(is (= {:chat_template_kwargs {:enable_thinking false}} (:extra-body (config/chat-config)))))
  (with-env {}
    #(is (nil? (:extra-body (config/chat-config)))))
  (with-env {"VLLM_CHAT_EXTRA_BODY" "not json"}
    #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"VLLM_CHAT_EXTRA_BODY" (config/chat-config)))))
