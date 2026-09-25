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

(def ^:private stored
  {:trace/id #uuid "00000000-0000-0000-0000-000000000001"
   :trace/username "bob"
   :trace/stages {:lexical {:ms 3
                            :raw-hits 12
                            :after-acl 0
                            :top []}
                  :semantic {:ms 5
                             :raw-hits 200
                             :after-acl 40
                             :top [["public/handbook.md::0" 0.4]]}
                  :fusion {:ms 1
                           :top []}
                  :flags #{:acl-starvation :uncited-answer}}})

(deftest test-view-for-hides-pre-acl-counts-from-non-admins
  (let [v (trace/view-for {:username "bob"
                           :groups #{"all"}
                           :admin? false} stored)]
    (testing "no count from before the ACL filter"
      (is (not-any? #(contains? % :raw-hits) (vals (select-keys (:trace/stages v) [:lexical :semantic])))))
    (testing "acl-starvation reveals unreadable matches, other flags stay"
      (is (= #{:uncited-answer} (get-in v [:trace/stages :flags]))))
    (testing "what the user may see is kept for their own debugging"
      (is (= 40 (get-in v [:trace/stages :semantic :after-acl])))
      (is (= 3 (get-in v [:trace/stages :lexical :ms])))
      (is (= [["public/handbook.md::0" 0.4]] (get-in v [:trace/stages :semantic :top]))))))

(deftest test-view-for-admin-sees-everything
  (is (= stored (trace/view-for {:username "admin"
                                 :groups #{}
                                 :admin? true} stored))))

(deftest test-view-for-failure-trace
  ;; dependency-failure traces have only :error in their stages
  (let [t {:trace/stages {:error {:endpoint :embed
                                  :message "x"}}}]
    (is (= t (trace/view-for {:admin? false} t)))))
