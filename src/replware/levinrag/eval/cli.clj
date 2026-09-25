(ns replware.levinrag.eval.cli
  "`bb eval [--variants lexical,semantic,...] [--questions <file>] [--users
   <file>]` over DATA_DIR/index.dtlv.
   Exits non-zero when any ACL leak is found (SPEC.md §15.2)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datalevin.core :as d]
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
  "{:questions [..] :principals {..}} from the files named in `opts`."
  [{:keys [questions users]}]
  {:questions (edn/read-string (slurp (existing-file questions "題目檔")))
   :principals (harness/load-principals (existing-file users "使用者檔"))})

(defn -main [& args]
  (let [{:keys [data-dir]} (config/corpus-config)
        embed-cfg (config/embed-config)
        conn (index-conn/open (str (io/file data-dir "index.dtlv")) (:dims embed-cfg))
        code (try
               (let [opts (parse-args args)
                     report (harness/run-eval
                              {:retriever (rd/retriever conn #(embed/embed-all! embed-cfg % 32))
                               :rerank-fn #(rerank-client/rerank! (config/rerank-config) %1 %2 %3)}
                              conn
                              (assoc (read-inputs opts) :variant-names (:variant-names opts)))
                     path (harness/write-results! "eval/results" report)]
                 (println (harness/table report))
                 (some-> (harness/degraded-warning report) println)
                 (println "results:" path)
                 (if (pos? (:acl-leaks report)) 1 0))
               (catch clojure.lang.ExceptionInfo e
                 (binding [*out* *err*] (println "錯誤：" (ex-message e)))
                 2)
               (finally (d/close conn)))]
    (shutdown-agents)
    (System/exit code)))
