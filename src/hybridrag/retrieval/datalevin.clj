(ns hybridrag.retrieval.datalevin
  "Datalevin Retriever (SPEC.md §9.2, §9.3).

   ACL: a non-admin principal's readable doc set is computed from
   :doc/effective-groups and every hit is checked against it (the T0.5
   doc-id-set pattern, docs/decisions.md). Admins go through separate
   functions with no ACL step at all — never a flag on a shared query. A
   non-admin with no groups gets empty results without touching the DB."
  (:require [datalevin.core :as d]
            [hybridrag.ingest.writer :as writer]
            [hybridrag.retrieval.protocol :as p]))

(def default-channel-opts
  {:channel-k 50
   :overfetch 4})

(defn- no-access? [{:keys [admin? groups]}]
  (and (not admin?) (empty? groups)))

(defn accessible-doc-ids
  "Entity ids of the docs readable by any of `groups`."
  [db groups]
  (set (d/q '[:find [?d ...] :in $ [?g ...] :where [?d :doc/effective-groups ?g]]
            db (vec groups))))

;; --- raw recall (no ACL; callers decide) ---

(defn- lexical-raw
  "Fulltext hits best-first: [{:e :doc :score} ...]."
  [db query top]
  (->> (d/q '[:find ?e ?d ?score
              :in $ ?q ?opts
              :where
              [(fulltext $ :chunk/index-text ?q ?opts) [[?e _ _ ?score]]]
              [?e :chunk/doc ?d]]
            db query {:top top
                      :display :refs+scores})
       (map (fn [[e doc score]] {:e e
                                 :doc doc
                                 :score score}))
       (sort-by (juxt (comp - :score) :e))))

(defn- semantic-raw
  "Vector neighbors best-first: [{:e :doc :score} ...], score = 1 − cosine distance."
  [db qvec top]
  (->> (d/q '[:find ?e ?d ?dist
              :in $ ?v ?opts
              :where
              [(vec-neighbors $ :chunk/vec ?v ?opts) [[?e _ _ ?dist]]]
              [?e :chunk/doc ?d]]
            db qvec {:top top
                     :display :refs+dists})
       (map (fn [[e doc dist]] {:e e
                                :doc doc
                                :score (- 1.0 dist)}))
       (sort-by (juxt (comp - :score) :e))))

(defn- raw-hits [db embed-fn channel-kw query top]
  (case channel-kw
    :lexical (lexical-raw db query top)
    :semantic (semantic-raw db (float-array (first (embed-fn [query]))) top)))

(defn- ranked
  "Attach chunk ids / paths and 1-based ranks."
  [db hits]
  (let [ids (into {} (map (fn [[e cid path]] [e [cid path]]))
                  (d/q '[:find ?e ?cid ?path :in $ [?e ...]
                         :where [?e :chunk/id ?cid] [?e :chunk/doc ?d] [?d :doc/path ?path]]
                       db (mapv :e hits)))]
    (vec (map-indexed (fn [i {:keys [e score]}]
                        {:chunk/id (first (get ids e))
                         :doc/path (second (get ids e))
                         :rank (inc i)
                         :score score})
                      hits))))

(defn- channel-result [db raw filtered {:keys [channel-k overfetch]}]
  (let [extended (ranked db filtered)]
    {:candidates (vec (take channel-k extended))
     :extended extended
     :raw-hits (count raw)
     :after-acl (count filtered)
     :starved? (and (= (count raw) (* channel-k overfetch))
                    (< (count filtered) channel-k))}))

(defn channel-for-user
  "ACL-filtered channel for a non-admin principal."
  [db embed-fn {:keys [groups]} channel-kw query opts]
  (let [{:keys [channel-k overfetch]
         :as opts} (merge default-channel-opts opts)
        readable (accessible-doc-ids db groups)
        raw (raw-hits db embed-fn channel-kw query (* channel-k overfetch))]
    (channel-result db raw (filterv #(contains? readable (:doc %)) raw) opts)))

(defn channel-for-admin
  "Channel with no ACL step — admins only."
  [db embed-fn channel-kw query opts]
  (let [{:keys [channel-k overfetch]
         :as opts} (merge default-channel-opts opts)
        raw (vec (raw-hits db embed-fn channel-kw query (* channel-k overfetch)))]
    (channel-result db raw raw opts)))

;; --- chunk fetches ---

(def ^:private chunk-pull
  [:db/id :chunk/id :chunk/ordinal :chunk/text :chunk/index-text :chunk/tokens
   :chunk/char-start :chunk/char-end
   {:chunk/doc [:db/id :doc/path :doc/title]}
   {:chunk/section [:section/id :section/trail]}])

(defn- chunk-map [pulled]
  (let [{:keys [chunk/doc chunk/section]} pulled]
    (-> pulled
        (dissoc :db/id :chunk/doc :chunk/section)
        (assoc :doc/path (:doc/path doc)
               :doc/title (:doc/title doc)
               :doc-eid (:db/id doc)
               :section/id (:section/id section)
               :section/trail (:section/trail section)))))

(defn- pull-chunks [db chunk-ids]
  (keep #(some->> (d/entid db [:chunk/id %]) (d/pull db chunk-pull) chunk-map) chunk-ids))

(defn- readable-filter
  "Predicate on doc eids for `principal`; admin reads everything."
  [db {:keys [admin? groups]}]
  (if admin? (constantly true) (accessible-doc-ids db groups)))

(defn- strip [m] (dissoc m :doc-eid))

(defn- chunks* [db principal chunk-ids]
  (let [ok? (readable-filter db principal)]
    (->> (pull-chunks db chunk-ids)
         (filter #(ok? (:doc-eid %)))
         (mapv strip))))

(defn- neighbors* [db principal chunk-id {:keys [radius]
                                          :or {radius 1}}]
  (let [ok? (readable-filter db principal)]
    (if-let [c (first (pull-chunks db [chunk-id]))]
      (if-not (ok? (:doc-eid c))
        []
        (->> (d/q '[:find [?id ...] :in $ ?sid ?lo ?hi ?self
                    :where [?s :section/id ?sid] [?n :chunk/section ?s]
                    [?n :chunk/ordinal ?o] [(<= ?lo ?o)] [(<= ?o ?hi)]
                    [?n :chunk/id ?id] [(not= ?id ?self)]]
                  db (:section/id c) (- (:chunk/ordinal c) radius) (+ (:chunk/ordinal c) radius) chunk-id)
             (pull-chunks db)
             (filter #(ok? (:doc-eid %)))
             (sort-by :chunk/ordinal)
             (mapv strip)))
      [])))

(defn- linked-docs* [db principal doc-paths]
  (let [ok? (readable-filter db principal)
        sources (->> doc-paths
                     (keep #(d/entid db [:doc/path %]))
                     (filter ok?))
        linked (when (seq sources)
                 (d/q '[:find ?o ?path :in $ [?src ...]
                        :where (or [?src :doc/links-to ?o] [?o :doc/links-to ?src])
                        [?o :doc/path ?path]]
                      db (vec sources)))]
    (->> linked
         (filter (comp ok? first))
         (map second)
         (remove (set doc-paths))
         sort
         vec)))

(defrecord DatalevinRetriever [conn embed-fn]
  p/Retriever
  (index-doc! [_ parsed-doc] (writer/index-doc! conn parsed-doc embed-fn))
  (delete-doc! [_ doc-path] (writer/delete-doc! conn doc-path))
  (channel [_ principal channel-kw query opts]
    (cond
      (no-access? principal) {:candidates []
                              :extended []
                              :raw-hits 0
                              :after-acl 0
                              :starved? false}
      (:admin? principal) (channel-for-admin (d/db conn) embed-fn channel-kw query opts)
      :else (channel-for-user (d/db conn) embed-fn principal channel-kw query opts)))
  (neighbors [_ principal chunk-id opts]
    (if (no-access? principal) [] (neighbors* (d/db conn) principal chunk-id opts)))
  (linked-docs [_ principal doc-paths]
    (if (no-access? principal) [] (linked-docs* (d/db conn) principal doc-paths)))
  (chunks [_ principal chunk-ids]
    (if (no-access? principal) [] (chunks* (d/db conn) principal chunk-ids))))

(defn retriever
  "Retriever over index.dtlv `conn`; `embed-fn` embeds a vector of strings."
  [conn embed-fn]
  (->DatalevinRetriever conn embed-fn))
