(ns hybridrag.ingest.runner-test
  "In-process ingest jobs (Phase 4 design): lifecycle, one job at a time,
   failures release the lock, a missing corpus dir never wipes the index."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [hybridrag.db.index-conn :as index-conn]
            [hybridrag.fixtures :as fx]
            [hybridrag.ingest.runner :as runner]
            [hybridrag.tmp :as tmp]))

(defn wait-done
  "Poll until job `id` is no longer :running (≤ 30 s); returns the job."
  [r id]
  (loop [i 0]
    (let [j (runner/job r id)]
      (if (or (not= :running (:status j)) (> i 300))
        j
        (do (Thread/sleep 100) (recur (inc i)))))))

(defn- with-runner [opts f]
  (let [idx (tmp/dir "runner-idx")
        data (tmp/dir "runner-data")
        conn (index-conn/open idx fx/dims)]
    (try
      (f (runner/make (merge {:index-conn conn
                              :corpus-dir "corpus-sample"
                              :data-dir data
                              :root-read-groups []
                              :embed-fn fx/hash-embed}
                             opts))
         conn data)
      (finally (d/close conn) (tmp/delete-tree! idx) (tmp/delete-tree! data)))))

(deftest test-runner-lifecycle
  (with-runner {}
    (fn [r conn data]
      (let [{:keys [job]} (runner/start! r)]
        (is (= :running (:status job)))
        (let [done (wait-done r (:id job))]
          (is (= :done (:status done)))
          (is (pos? (get-in done [:report :added])))
          (is (.exists (io/file (:report-path done))))
          (is (.startsWith (.toPath (.getCanonicalFile (io/file (:report-path done))))
                           (.toPath (.getCanonicalFile (io/file data)))))
          (is (= (:id job) (:id (runner/latest r))))
          (is (pos? (count (d/q '[:find [?p ...] :where [_ :doc/path ?p]] (d/db conn))))))))))

(deftest test-runner-single-job
  (let [gate (promise)]
    (with-runner {:embed-fn (fn [texts] @gate (fx/hash-embed texts))}
      (fn [r _ _]
        (let [{:keys [job]} (runner/start! r)
              second-try (runner/start! r)]
          (is (= (:id job) (:id (:conflict second-try))))
          (deliver gate true)
          (is (= :done (:status (wait-done r (:id job)))))
          (let [{again :job} (runner/start! r)]
            (is again "a new job is accepted after the first finished")
            (wait-done r (:id again))))))))

(deftest test-runner-failure-releases
  (with-runner {}
    (fn [r conn data]
      (let [{:keys [job]} (runner/start! r)]
        (wait-done r (:id job)))
      (let [docs-before (d/q '[:find (count ?d) . :where [?d :doc/path]] (d/db conn))
            bad (runner/make {:index-conn conn
                              :corpus-dir "no-such-corpus-dir"
                              :data-dir data
                              :root-read-groups []
                              :embed-fn fx/hash-embed})
            {:keys [job]} (runner/start! bad)
            failed (wait-done bad (:id job))]
        (is (= :failed (:status failed)))
        (is (re-find #"no-such-corpus-dir" (:error failed)))
        (testing "a missing corpus dir does not wipe the index"
          (is (= docs-before (d/q '[:find (count ?d) . :where [?d :doc/path]] (d/db conn)))))
        (testing "the lock is released"
          (is (:job (runner/start! bad))))))))

(deftest test-history-bounded
  (with-runner {}
    (fn [r _ _]
      (dotimes [_ 22]
        (wait-done r (:id (:job (runner/start! r)))))
      (is (= 20 (count (runner/history r)))))))
