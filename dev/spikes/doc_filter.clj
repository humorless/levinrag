(ns spikes.doc-filter
  "Re-test of T0.4's \"`:doc-filter` is unusable from Datalog\" finding
  (docs/spikes/doc-filter.md). Answers three questions against Datalevin
  1.1.0:

  1. Does the T0.4 failure reproduce, and why? (inline `(fn ..)` inside a
     quoted query)
  2. Does `:doc-filter` work when the fn is passed as a query input, and
     what does the filter receive?
  3. Is the filter applied before or after the top-k? Same question for
     the vector `:vec-filter`.

  THROWAWAY SCRIPT: opens disposable Datalevin dirs under the OS temp dir.
  Run from the repo root in a fresh JVM (see gen_synthetic_corpus.clj for
  why load-file):

    clojure -M:jvm-opts -e '(load-file \"dev/spikes/doc_filter.clj\")(spikes.doc-filter/-main)'"
  (:require [datalevin.core :as d]))

(defn- temp-dir [prefix]
  (str (java.nio.file.Files/createTempDirectory
         prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(def ^:private schema
  {:chunk/id {:db/valueType :db.type/string
              :db/unique :db.unique/identity}
   :chunk/text {:db/valueType :db.type/string
                :db/fulltext true
                :db.fulltext/autoDomain true}
   :chunk/vec {:db/valueType :db.type/vec}})

(def ^:private ft-query
  '[:find ?id :in $ ?q ?opts
    :where [(fulltext $ :chunk/text ?q ?opts) [[?e _ _]]] [?e :chunk/id ?id]])

(def ^:private vec-query
  '[:find ?id :in $ ?v ?opts
    :where [(vec-neighbors $ :chunk/vec ?v ?opts) [[?e _ _]]] [?e :chunk/id ?id]])

(defn ref-eid
  "Entity id of a fulltext doc-ref: [e aid v] for a normal datom,
   [:g gid e aid] for a giant one (serialized value over ~497 bytes)."
  [r]
  (if (= :g (first r)) (nth r 2) (first r)))

(defn- experiment-1-2 []
  (let [conn (d/create-conn (temp-dir "docfilter-a") schema
                            {:vector-opts {:dimensions 2
                                           :metric-type :cosine}})]
    (d/transact! conn [{:chunk/id "c1" :chunk/text "quick brown fox"}
                       {:chunk/id "c2" :chunk/text "quick red fox quick"}
                       {:chunk/id "c3" :chunk/text "slow turtle"}
                       {:chunk/id "long" :chunk/text (apply str "quick " (repeat 200 "filler "))}])
    (let [db (d/db conn)
          seen (atom [])]
      (println "1. inline fn in a quoted query (the T0.4 form):")
      (println "  "
               (try (d/q '[:find ?id :in $ ?q
                           :where [(fulltext $ :chunk/text ?q {:doc-filter (fn [_] true)}) [[?e _ _]]]
                           [?e :chunk/id ?id]]
                         db "quick")
                    (catch Throwable t (str (.getName (class t)) ": " (ex-message t)))))
      (println "2. fn passed as a query input, filter excludes c2:")
      (println "   result:"
               (d/q ft-query db "quick"
                    {:doc-filter (fn [r]
                                   (swap! seen conj r)
                                   (not= (ref-eid r) (d/entid db [:chunk/id "c2"])))}))
      (println "   doc-refs received:"
               (mapv #(if (= :g (first %)) (vec (take 4 %)) (vec (take 2 %))) @seen))
      (d/close conn))))

(defn- experiment-3 []
  (let [conn (d/create-conn (temp-dir "docfilter-b") schema
                            {:vector-opts {:dimensions 2
                                           :metric-type :cosine}})]
    ;; chunk i: "alpha" (300 - i) times, so its lexical rank grows with i;
    ;; vector angle 0.005·i, so its distance to [1 0] grows with i
    (d/transact! conn (vec (for [i (range 300)]
                             {:chunk/id (format "c%03d" i)
                              :chunk/text (str (apply str (repeat (- 300 i) "alpha "))
                                               (apply str (repeat i "filler ")))
                              :chunk/vec (float-array [(Math/cos (* i 0.005)) (Math/sin (* i 0.005))])})))
    (let [db (d/db conn)
          allowed (set (for [i (range 290 300)] (d/entid db [:chunk/id (format "c%03d" i)])))
          df (fn [r] (contains? allowed (ref-eid r)))
          vf (fn [r] (contains? allowed (first r)))
          q (float-array [1 0])
          n #(count (apply d/q %&))]
      (println "3. 300 matching chunks; the filter admits only the 10 worst-ranked")
      (println "   fulltext top 50, no filter, any admitted? "
               (boolean (some #(>= (compare (first %) "c290") 0) (d/q ft-query db "alpha" {:top 50}))))
      (println "   fulltext :doc-filter  top 50   ->" (n ft-query db "alpha" {:top 50 :doc-filter df}))
      (println "   fulltext :doc-filter  limit 50 ->" (n ft-query db "alpha" {:limit 50 :doc-filter df}))
      (println "   fulltext :doc-filter  top 300  ->" (n ft-query db "alpha" {:top 300 :doc-filter df}))
      (println "   vector   :vec-filter  top 50   ->" (n vec-query db q {:top 50 :vec-filter vf}))
      (println "   vector   :vec-filter  top 300  ->" (n vec-query db q {:top 300 :vec-filter vf}))
      (println "   (a pre-top-k filter would return 10 in every row)")
      (d/close conn))))

(defn -main [& _]
  (experiment-1-2)
  (experiment-3)
  (shutdown-agents))
