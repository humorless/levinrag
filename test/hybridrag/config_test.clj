(ns hybridrag.config-test
  (:require [clojure.test :refer [deftest is]]
            [hybridrag.config :as config]
            [hybridrag.retrieval.system]
            [integrant.core :as ig]
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

(deftest test-read-timeouts
  (with-env {}
    #(do (is (= 30000 (:read-timeout-ms (config/embed-config))))
         (is (= 10000 (:read-timeout-ms (config/rerank-config))))
         (is (= 120000 (:read-timeout-ms (config/chat-config))))))
  (with-env {"VLLM_EMBED_TIMEOUT_MS" "5000"
             "VLLM_RERANK_TIMEOUT_MS" "3000"
             "VLLM_CHAT_TIMEOUT_MS" "60000"}
    #(do (is (= 5000 (:read-timeout-ms (config/embed-config))))
         (is (= 3000 (:read-timeout-ms (config/rerank-config))))
         (is (= 60000 (:read-timeout-ms (config/chat-config)))))))

(deftest test-dirs-single-source
  ;; server, runner and both DBs take their paths from one config key
  (let [test-cfg (ig-extras/get-config :test)
        default-cfg (ig-extras/get-config :default)]
    (is (= "data-test" (get-in test-cfg [:hybridrag.ingest.runner/runner :data-dir])))
    (is (= "data-test/index.dtlv" (get-in test-cfg [:hybridrag.db.index-conn/index-conn :dir])))
    (is (= "data-test/app.dtlv" (get-in test-cfg [:hybridrag.db.app-conn/app-conn :dir])))
    (doseq [cfg [test-cfg default-cfg]]
      (is (= (get-in cfg [:hybridrag.ingest.runner/runner :corpus-dir])
             (get-in cfg [:hybridrag.server/server :corpus-dir])
             (get-in cfg [:hybridrag.config/paths :corpus-dir])))
      (is (= (str (get-in cfg [:hybridrag.config/paths :data-dir]) "/index.dtlv")
             (get-in cfg [:hybridrag.db.index-conn/index-conn :dir]))))))

(deftest test-split-groups
  (is (= [] (config/split-groups "")))
  (is (= [] (config/split-groups nil)))
  (is (= ["all" "hr"] (config/split-groups " all, hr ,"))))

(deftest test-extra-body-must-be-an-object
  (with-env {"VLLM_CHAT_EXTRA_BODY" "[1,2]"}
    #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"VLLM_CHAT_EXTRA_BODY" (config/chat-config)))))

(deftest test-search-init-fails-on-bad-extra-body
  ;; at startup, not on every /ask
  (with-env {"VLLM_CHAT_EXTRA_BODY" "not json"}
    #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"VLLM_CHAT_EXTRA_BODY"
                           (ig/init-key :hybridrag.retrieval.system/search {:index-conn nil
                                                                            :opts {}}))))
  (with-env {}
    #(is (fn? (:chat-fn (ig/init-key :hybridrag.retrieval.system/search {:index-conn nil
                                                                         :opts {}}))))))
