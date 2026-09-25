(ns replware.levinrag.eval.cli
  "`bb eval [--variants lexical,semantic,...] [--questions <file>] [--users
   <file>]` over DATA_DIR/index.dtlv.
   Exits non-zero when any ACL leak is found (SPEC.md §15.2)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datalevin.core :as d]
            [replware.levinrag.auth.user-import :as user-import]
            [replware.levinrag.config :as config]
            [replware.levinrag.db.index-conn :as index-conn]
            [replware.levinrag.eval.harness :as harness]
            [replware.levinrag.llm.embed :as embed]
            [replware.levinrag.llm.rerank-client :as rerank-client]
            [replware.levinrag.retrieval.datalevin :as rd]))

(defn- variant-names [s]
  (let [names (if s (str/split s #",") (keys harness/variants))]
    (doseq [n names]
      (when-not (contains? harness/variants n)
        (throw (ex-info (str "未知的變體：" n "（可用：" (str/join "," (keys harness/variants)) "）") {}))))
    (vec names)))

(def ^:private flags #{"--variants" "--questions" "--users"})

(defn parse-args
  "{:variant-names :questions :users} from `--variants a,b --questions
   <file> --users <file>`; each flag is optional."
  [args]
  (loop [[a v & more :as args] args, opts {}]
    (cond
      (empty? args) {:variant-names (variant-names (get opts "--variants"))
                     :questions (get opts "--questions" "eval/questions.edn")
                     :users (get opts "--users" "eval/users.edn")}
      (not (flags a)) (throw (ex-info (str "未知的參數：" a "（可用：" (str/join " " (sort flags)) "）") {}))
      (nil? v) (throw (ex-info (str a " 缺少值") {}))
      :else (recur more (assoc opts a v)))))

(defn- existing-file [path what]
  (if (.isFile (io/file path))
    path
    (throw (ex-info (str "找不到" what "：" path) {:path path}))))

(defn read-inputs
  "{:questions [..] :principals {..}} from the files named in `opts`. The
   users file gets the same checks as `bb user:import`: a user whose
   `:groups` is misspelt would read nothing and make the leak check pass
   vacuously."
  [{:keys [questions users]}]
  {:questions (edn/read-string (slurp (existing-file questions "題目檔")))
   :principals (into {}
                     (map (fn [[u m]] [u (assoc m :username u)]))
                     (user-import/parse (slurp (existing-file users "使用者檔"))))})

(defn missing-docs
  "[{:id :key :path}] for every :expected-docs / :must-not-docs path that is
   not in the index: such a question measures nothing (a :must-not-docs
   typo, or a doc kept out by SPEC.md §7.2 rule 5, can never leak)."
  [questions indexed-paths]
  (vec (for [q questions
             k [:expected-docs :must-not-docs]
             p (get q k)
             :when (not (contains? indexed-paths p))]
         {:id (:id q)
          :key k
          :path p})))

(defn -main [& args]
  (let [{:keys [data-dir]} (config/corpus-config)
        embed-cfg (config/embed-config)
        index (io/file data-dir "index.dtlv")
        code (if-not (.exists index)
               (do (binding [*out* *err*] (println "錯誤： 找不到" (str index) "：先執行 bb ingest，或檢查 DATA_DIR"))
                   2)
               (let [conn (index-conn/open (str index) (:dims embed-cfg))]
                 (try
                   (let [opts (parse-args args)
                         inputs (read-inputs opts)
                         missing (missing-docs (:questions inputs)
                                               (set (d/q '[:find [?p ...] :where [_ :doc/path ?p]] (d/db conn))))
                         report (harness/run-eval
                                  {:retriever (rd/retriever conn #(embed/embed-all! embed-cfg % 32))
                                   :rerank-fn #(rerank-client/rerank! (config/rerank-config) %1 %2 %3)}
                                  conn
                                  (assoc inputs :variant-names (:variant-names opts)))
                         path (harness/write-results! "eval/results" report)]
                     (println (harness/table report))
                     (some-> (harness/degraded-warning report) println)
                     (when (seq missing)
                       (println (str "[WARN] 題目引用了索引裡沒有的文件（" (count missing)
                                     " 處；路徑打錯，或文件因權限設定錯誤未匯入）："))
                       (doseq [{:keys [id path]
                                k :key} missing]
                         (println (str "         " id " " (name k) " " path))))
                     (println "results:" path)
                     (if (pos? (:acl-leaks report)) 1 0))
                   (catch clojure.lang.ExceptionInfo e
                     (binding [*out* *err*] (println "錯誤：" (ex-message e)))
                     2)
                   (finally (d/close conn)))))]
    (shutdown-agents)
    (System/exit code)))
