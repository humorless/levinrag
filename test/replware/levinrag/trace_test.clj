(ns replware.levinrag.trace-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [replware.levinrag.tmp :as tmp]
            [replware.levinrag.trace :as trace]))

(deftest test-recent-newest-first-and-capped
  (tmp/with-app-conn
    (fn [app]
      (let [ids (vec (for [i (range 5)]
                       (do (Thread/sleep 2)
                           (trace/write! app {:username "a"
                                              :kind :search
                                              :query (str i)
                                              :stages {}}))))]
        (is (= (reverse (subvec ids 2)) (map :trace/id (trace/recent (d/db app) 3))))
        (is (= #{:trace/id :trace/username :trace/kind :trace/query :trace/at}
               (set (keys (first (trace/recent (d/db app) 1))))))
        (testing "reads n traces by range scan, not every trace through a query"
          (with-redefs [d/q (fn [& _] (throw (ex-info "full query" {})))]
            (is (= 2 (count (trace/recent (d/db app) 2))))))))))

(deftest test-recent-ties-newest-entity-first
  (tmp/with-app-conn
    (fn [app]
      (let [t (java.util.Date. 1000)
            [a b] [(random-uuid) (random-uuid)]]
        (d/transact! app [{:trace/id a
                           :trace/at t}])
        (d/transact! app [{:trace/id b
                           :trace/at t}])
        (is (= [b a] (map :trace/id (trace/recent (d/db app) 2))))))))
