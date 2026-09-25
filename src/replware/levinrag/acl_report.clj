(ns replware.levinrag.acl-report
  "`bb acl:report [--users <file>] [--docs]`: who can read which indexed
   doc. Readers come from rd/accessible-doc-ids, the function retrieval
   filters with, so the report shows the rule as enforced, not a second
   implementation of it. Paths and group names only, never doc text."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datalevin.core :as d]
            [replware.levinrag.auth.user-import :as user-import]
            [replware.levinrag.auth.users :as users]
            [replware.levinrag.config :as config]
            [replware.levinrag.db.index-conn :as index-conn]
            [replware.levinrag.db.schema :as schema]
            [replware.levinrag.retrieval.datalevin :as rd]))

(defn app-principals
  "username → principal for every user in app.dtlv."
  [app-db]
  (into {}
        (map (fn [u] [u (users/principal (users/find-user app-db u))]))
        (d/q '[:find [?u ...] :where [_ :user/username ?u]] app-db)))

(defn build
  "Report data for the index `db` and `principals` (username → principal)."
  [db principals]
  (let [docs (->> (d/q '[:find ?d ?p :where [?d :doc/path ?p]] db)
                  (map (fn [[e p]] {:eid e
                                    :path p
                                    :groups (vec (sort (:doc/effective-groups (d/pull db [:doc/effective-groups] e))))}))
                  (sort-by :path))
        readers (into {}
                      (for [[u p] principals
                            :when (and (not (:admin? p)) (seq (:groups p)))]
                        [u (rd/accessible-doc-ids db (:groups p))]))
        readers-of (fn [eid] (vec (sort (for [[u ids] readers :when (contains? ids eid)] u))))
        docs (mapv #(-> % (assoc :readers (readers-of (:eid %))) (dissoc :eid)) docs)
        doc-groups (set (mapcat :groups docs))
        non-admins (remove :admin? (vals principals))
        held (set (mapcat :groups non-admins))]
    {:docs docs
     :groups (vec (for [g (sort doc-groups)]
                    {:group g
                     :docs (count (filter #(some #{g} (:groups %)) docs))
                     :users (vec (sort (map :username (filter #(contains? (:groups %) g) non-admins))))}))
     :users (vec (for [[u p] (sort-by key principals)]
                   {:username u
                    :admin? (:admin? p)
                    :groups (vec (sort (:groups p)))
                    :readable (if (:admin? p) (count docs) (count (get readers u)))}))
     :warnings {:unheld-groups (vec (sort (remove held doc-groups)))
                :admin-only-docs (vec (for [d docs :when (empty? (:readers d))] (select-keys d [:path :groups])))
                :users-without-groups (vec (sort (map :username (filter #(empty? (:groups %)) non-admins))))
                :unused-user-groups (vec (sort (remove doc-groups held)))}}))

(defn- join [xs] (if (seq xs) (str/join "," xs) "（無）"))

(defn text
  "The report as text. opts: :docs? (one line per doc), :ingest-errors
   (error count of the latest ingest report, or nil)."
  [{:keys [docs groups users warnings]} {:keys [docs? ingest-errors]}]
  (let [{:keys [unheld-groups admin-only-docs users-without-groups unused-user-groups]} warnings]
    (str/join
      "\n"
      (concat
        [(str "已匯入 " (count docs) " 份文件。") "" "群組：文件數｜擁有此群組的使用者"]
        (for [{:keys [group users]
               n :docs} groups]
          (str "  " group "：" n " 份｜" (join users)))
        ["" "使用者：群組 → 讀得到的文件數"]
        (for [{:keys [username admin? groups readable]} users]
          (str "  " username "：" (join groups) (when admin? "（admin）") " → " readable " 份"))
        (when docs?
          (concat ["" "文件：群組｜讀得到的使用者（admin 另計，全部讀得到）"]
                  (for [{:keys [path groups readers]} docs]
                    (str "  " path "：" (join groups) "｜" (join readers)))))
        [""]
        (cond-> []
          (seq unheld-groups)
          (conj (str "[WARN] 文件用到、但沒有任何使用者擁有的群組：" (str/join ", " unheld-groups)
                     "（可能是打錯字，或還沒建立這些使用者）"))
          (seq admin-only-docs)
          (into (cons (str "[WARN] 只有 admin 讀得到的文件（" (count admin-only-docs) " 份）：")
                      (for [{:keys [path groups]} admin-only-docs]
                        (str "         " path "：" (if (seq groups) (join groups) "[]")))))
          (seq users-without-groups)
          (conj (str "[WARN] 沒有任何群組的使用者（什麼都讀不到）：" (str/join ", " users-without-groups)))
          (seq unused-user-groups)
          (conj (str "[WARN] 使用者有、但沒有任何文件使用的群組：" (str/join ", " unused-user-groups)
                     "（可能是使用者檔打錯字）"))
          (pos? (or ingest-errors 0))
          (conj (str "[WARN] 最近一次匯入有 " ingest-errors " 個錯誤，那些文件不在索引裡（見 ingest 報告）"))
          (every? empty? [unheld-groups admin-only-docs users-without-groups unused-user-groups])
          (conj "沒有警告。"))))))

(defn- latest-ingest-errors [data-dir]
  (let [files (sort (filter #(str/ends-with? (.getName ^java.io.File %) ".edn")
                            (.listFiles (io/file data-dir "ingest-reports"))))]
    (some-> (last files) slurp edn/read-string :errors count)))

(defn- parse-args [args]
  (loop [[a & more] args, opts {}]
    (cond
      (nil? a) opts
      (= "--docs" a) (recur more (assoc opts :docs? true))
      (and (= "--users" a) (first more)) (recur (rest more) (assoc opts :users (first more)))
      :else (throw (ex-info (str "用法：bb acl:report [--users <users.edn>] [--docs]（不認得：" a "）") {})))))

(defn -main [& args]
  (let [code (try
               (let [{:keys [users docs?]} (parse-args args)
                     data-dir (:data-dir (config/corpus-config))
                     index (io/file data-dir "index.dtlv")
                     app (io/file data-dir "app.dtlv")
                     _ (when-not (.exists index)
                         (throw (ex-info (str "找不到 " index "：先執行 bb ingest") {})))
                     principals (if users
                                  (do (when-not (.isFile (io/file users))
                                        (throw (ex-info (str "找不到使用者檔：" users) {})))
                                      (into {} (map (fn [[u m]] [u (assoc m :username u)]))
                                            (user-import/parse (slurp users))))
                                  (if (.exists app)
                                    (let [c (d/get-conn (str app) schema/app-schema)]
                                      (try (app-principals (d/db c)) (finally (d/close c))))
                                    (throw (ex-info "還沒有使用者：先執行 bb user:import <檔案>，或用 --users <檔案>" {}))))
                     conn (index-conn/open (str index) (:dims (config/embed-config)))]
                 (try
                   (println (text (build (d/db conn) principals)
                                  {:docs? docs?
                                   :ingest-errors (latest-ingest-errors data-dir)}))
                   0
                   (finally (d/close conn))))
               (catch clojure.lang.ExceptionInfo e
                 (binding [*out* *err*] (println "錯誤：" (ex-message e)))
                 1))]
    (shutdown-agents)
    (System/exit code)))
