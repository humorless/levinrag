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

    clojure -M:jvm-opts -e '(load-file \"dev/spikes/doc_filter.clj\")(spikes.doc-filter/-main)'

  Add \"bench\" for the latency comparison (experiment 4, ~100k chunks):

    clojure -M:jvm-opts -e '(load-file \"dev/spikes/doc_filter.clj\")(spikes.doc-filter/-main \"bench\")'"
  (:require [clojure.string :as str]
            [datalevin.core :as d]))

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

;; --- experiment 4: cost of :doc-filter vs the current post-filter ---

(def ^:private bench-schema
  {:doc/path {:db/valueType :db.type/string
              :db/unique :db.unique/identity}
   :doc/effective-groups {:db/valueType :db.type/string
                          :db/cardinality :db.cardinality/many}
   :chunk/id {:db/valueType :db.type/string
              :db/unique :db.unique/identity}
   :chunk/doc {:db/valueType :db.type/ref}
   :chunk/index-text {:db/valueType :db.type/string
                      :db/fulltext true
                      :db.fulltext/autoDomain true}})

(def ^:private terms
  ["合約" "條款" "甲方" "乙方" "違約" "賠償" "保密" "終止" "續約" "付款"
   "交付" "驗收" "智慧財產權" "授權" "不可抗力" "爭議" "仲裁" "管轄" "生效" "解除"])

(defn- generate!
  "T0.5 shape (1–3 of n-groups per doc), but chunks of `n-terms`
   space-joined terms: 80 terms is ~700 bytes, a giant datom like a real
   ~350-token chunk."
  [conn {:keys [n-docs chunks-per-doc n-groups n-terms]}]
  (let [rng (java.util.Random. 42)
        pick #(nth % (.nextInt rng (count %)))
        groups (mapv #(str "group-" %) (range n-groups))]
    (doseq [start (range 0 n-docs 250)]
      (d/transact! conn
                   (vec (mapcat (fn [i]
                                  (let [tid (- -1 i)]
                                    (cons {:db/id tid
                                           :doc/path (str "doc-" i ".md")
                                           :doc/effective-groups (vec (distinct (repeatedly (inc (.nextInt rng 3)) #(pick groups))))}
                                          (for [c (range chunks-per-doc)]
                                            {:chunk/id (str "doc-" i ".md::" c)
                                             :chunk/doc tid
                                             :chunk/index-text (str/join " " (repeatedly n-terms #(pick terms)))}))))
                                (range start (min n-docs (+ start 250)))))))
    groups))

(defn- readable-docs [db groups]
  (set (d/q '[:find [?d ...] :in $ [?g ...] :where [?d :doc/effective-groups ?g]] db groups)))

(defn variant-a
  "Current LevinRAG (retrieval/datalevin.clj): top-k, join to the doc,
   filter by the readable doc set in Clojure."
  [db q groups top]
  (let [readable (readable-docs db groups)]
    (->> (d/q '[:find ?e ?d ?s :in $ ?q ?opts
                :where [(fulltext $ :chunk/index-text ?q ?opts) [[?e _ _ ?s]]] [?e :chunk/doc ?d]]
              db q {:top top
                    :display :refs+scores})
         (filterv (fn [[_ d]] (contains? readable d))))))

(defn variant-b1
  ":doc-filter that looks up each hit's doc."
  [db q groups top]
  (let [readable (readable-docs db groups)
        doc-of (fn [e] (:v (first (d/datoms db :eav e :chunk/doc))))]
    (vec (d/q '[:find ?e ?d ?s :in $ ?q ?opts
                :where [(fulltext $ :chunk/index-text ?q ?opts) [[?e _ _ ?s]]] [?e :chunk/doc ?d]]
              db q {:top top
                    :display :refs+scores
                    :doc-filter #(contains? readable (doc-of (ref-eid %)))}))))

(defn variant-b2
  ":doc-filter over a precomputed readable chunk set."
  [db q groups top]
  (let [chunks (set (d/q '[:find [?c ...] :in $ [?g ...]
                           :where [?d :doc/effective-groups ?g] [?c :chunk/doc ?d]]
                         db groups))]
    (vec (d/q '[:find ?e ?d ?s :in $ ?q ?opts
                :where [(fulltext $ :chunk/index-text ?q ?opts) [[?e _ _ ?s]]] [?e :chunk/doc ?d]]
              db q {:top top
                    :display :refs+scores
                    :doc-filter #(contains? chunks (ref-eid %))}))))

(defn- p [sorted q] (nth sorted (int (Math/floor (* q (dec (count sorted)))))))

(defn- bench [f db groups n-user-groups runs]
  (let [rng (java.util.Random. 7)
        run (fn []
              (let [gs (let [l (java.util.ArrayList. ^java.util.Collection groups)]
                         (java.util.Collections/shuffle l rng)
                         (vec (take n-user-groups l)))
                    q (nth terms (.nextInt rng (count terms)))
                    t0 (System/nanoTime)
                    r (f db q gs 200)]
                [(/ (- (System/nanoTime) t0) 1e6) (count r)]))
        _ (dotimes [_ 20] (run))
        res (vec (repeatedly runs run))
        ms (sort (map first res))]
    {:p50 (p ms 0.5)
     :p90 (p ms 0.9)
     :hits-avg (double (/ (reduce + (map second res)) runs))}))

(defn experiment-4
  "Latency of A, B1, B2 at T0.5 scale. Also checks the three return the
   same hits."
  ([] (experiment-4 {:n-docs 10000
                     :chunks-per-doc 10
                     :n-groups 50
                     :n-terms 80
                     :runs 100}))
  ([{:keys [runs]
     :as opts}]
   (let [conn (d/create-conn (temp-dir "docfilter-bench") bench-schema)
         t0 (System/nanoTime)
         groups (generate! conn opts)
         _ (println (format "4. generated %d docs × %d chunks in %.0f s"
                            (:n-docs opts) (:chunks-per-doc opts) (/ (- (System/nanoTime) t0) 1e9)))
         db (d/db conn)
         key-set (fn [r] (set (map (juxt first second) r)))]
     (doseq [n [1 3 50]]
       (let [gs (vec (take n groups))]
         (println (format "   same hits (%d groups, 合約): %s" n
                          (= (key-set (variant-a db "合約" gs 200))
                             (key-set (variant-b1 db "合約" gs 200))
                             (key-set (variant-b2 db "合約" gs 200))))))
       (doseq [[label f] [["A  current" variant-a] ["B1 doc-filter+lookup" variant-b1] ["B2 doc-filter+chunk set" variant-b2]]]
         (let [{:keys [p50 p90 hits-avg]} (bench f db groups n runs)]
           (println (format "   %2d groups  %-24s p50 %7.2f ms  p90 %7.2f ms  hits %.1f"
                            n label p50 p90 hits-avg)))))
     (d/close conn))))

(defn -main [& args]
  (experiment-1-2)
  (experiment-3)
  (when (= "bench" (first args))
    (experiment-4))
  (shutdown-agents))
