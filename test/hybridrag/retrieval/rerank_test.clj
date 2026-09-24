(ns hybridrag.retrieval.rerank-test
  "Rerank + degradation (SPEC.md §9.6, T2.3 AC: timeout, HTTP 500 and
   HTTP 200 with an error payload all degrade and are flagged). Failure
   cases go through the real rerank client against a stub server."
  (:require [clojure.test :refer [deftest is testing]]
            [hybridrag.llm.rerank-client :as rr]
            [hybridrag.retrieval.rerank :as rerank]
            [jsonista.core :as json]
            [ring.adapter.jetty :as jetty]))

(def ^:private cands
  [{:chunk/id "a::0"
    :chunk/index-text "alpha"}
   {:chunk/id "b::0"
    :chunk/index-text "bravo"}
   {:chunk/id "g::0"
    :chunk/index-text "graph"
    :channels {:graph {}}}])

(defn- with-stub [handler f]
  (let [server (jetty/run-jetty handler {:port 0
                                         :join? false})
        port (.getLocalPort (aget (.getConnectors server) 0))]
    (try (f (fn [q docs n]
              (rr/rerank! {:base-url (str "http://localhost:" port)
                           :path "/v1/rerank"
                           :model "m"
                           :api-key "k"
                           :read-timeout-ms 300}
                          q docs n)))
         (finally (.stop server)))))

(defn- json-resp [status body]
  (fn [_] {:status status
           :headers {"Content-Type" "application/json"}
           :body (json/write-value-as-string body)}))

(deftest test-success-reorders
  (with-stub (json-resp 200 {:results [{:index 2
                                        :relevance_score 3.5}
                                       {:index 0
                                        :relevance_score 1.0}
                                       {:index 1
                                        :relevance_score -2.0}]})
    (fn [rerank-fn]
      (let [{:keys [ranked failed?]} (rerank/rerank rerank-fn "q" cands {})]
        (is (not failed?))
        (is (= ["g::0" "a::0" "b::0"] (map :chunk/id ranked)))
        (is (= [3.5 1.0 -2.0] (map :rerank ranked)))))))

(deftest test-failures-degrade-to-incoming-order
  (doseq [[label handler] {"timeout" (fn [_] (Thread/sleep 1500) {:status 200
                                                                  :body "{}"})
                           "HTTP 500" (json-resp 500 {:error "boom"})
                           "HTTP 200 + error payload" (json-resp 200 {:error "Unexpected endpoint or method. (POST /v1/rerank)"})
                           "results out of range" (json-resp 200 {:results [{:index 7
                                                                             :relevance_score 1.0}]})
                           "score not a number" (json-resp 200 {:results [{:index 0
                                                                           :relevance_score "high"}]})}]
    (with-stub handler
      (fn [rerank-fn]
        (let [{:keys [ranked failed? error]} (rerank/rerank rerank-fn "q" cands {})]
          (is failed? label)
          (is (string? error) label)
          (is (= ["a::0" "b::0" "g::0"] (map :chunk/id ranked)) (str label ": graph stays last"))
          (is (every? nil? (map :rerank ranked)) label))))))

(deftest test-partial-results-and-truncation
  (let [seen (atom nil)
        rerank-fn (fn [_ docs n] (reset! seen [docs n]) [{:index 1
                                                          :relevance-score 0.9}])
        long-text (apply str (repeat 2000 "字"))
        {:keys [ranked failed?]} (rerank/rerank rerank-fn "q" (assoc-in cands [0 :chunk/index-text] long-text) {})]
    (is (not failed?))
    (is (= ["b::0" "a::0" "g::0"] (map :chunk/id ranked)) "unscored keep their order after scored")
    (is (= [0.9 nil nil] (map :rerank ranked)))
    (is (= 1500 (count (first (first @seen)))) ":rerank/max-chars")
    (is (= 3 (second @seen)) "top_n = all candidates")))

(deftest test-empty-input-skips-the-call
  (is (= {:ranked []
          :failed? false
          :ms 0}
         (rerank/rerank (fn [& _] (throw (ex-info "called" {}))) "q" [] {}))))
