(ns hybridrag.fixtures
  "Shared test fixtures: a deterministic stub embedder (SPEC.md §18.2) and
   the sample corpus ingested into a temporary index.dtlv."
  (:require [clojure.edn :as edn]
            [datalevin.core :as d]
            [hybridrag.db.index-conn :as index-conn]
            [hybridrag.ingest.job :as job]
            [hybridrag.tmp :as tmp]))

(def dims 16)

(defn hash-embed
  "Deterministic unit vector per text (SPEC.md §18.2)."
  [texts]
  (mapv (fn [t]
          (let [r (java.util.Random. (hash t))
                v (vec (repeatedly dims #(.nextGaussian r)))
                n (Math/sqrt (reduce + (map * v v)))]
            (mapv #(/ % n) v)))
        texts))

(def principals
  "Seed users from eval/users.edn as principals."
  (into {}
        (map (fn [[u m]] [u (assoc m :username u)]))
        (edn/read-string (slurp "eval/users.edn"))))

(def nobody {:username "nobody"
             :groups #{}
             :admin? false})

(def ^:dynamic *index* nil)

(defn with-sample-index
  "clojure.test :once fixture binding *index* to a conn over the ingested
   sample corpus."
  [t]
  (let [dir (tmp/dir "sample-index")
        conn (index-conn/open dir dims)]
    (try
      (let [rep (job/ingest! conn {:corpus-dir "corpus-sample"
                                   :embed-fn hash-embed})]
        (assert (empty? (:errors rep)) (pr-str (:errors rep))))
      (binding [*index* conn] (t))
      (finally (d/close conn) (tmp/delete-tree! dir)))))
