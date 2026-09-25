(ns replware.levinrag.eval.cli
  "`bb eval [--variants lexical,semantic,...]` over DATA_DIR/index.dtlv.
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

(defn- variant-names [args]
  (let [i (.indexOf ^java.util.List (vec args) "--variants")
        names (if (neg? i) (keys harness/variants) (str/split (nth args (inc i)) #","))]
    (doseq [n names]
      (when-not (contains? harness/variants n)
        (throw (ex-info (str "未知的變體：" n "（可用：" (str/join "," (keys harness/variants)) "）") {}))))
    (vec names)))

(defn -main [& args]
  (let [{:keys [data-dir]} (config/corpus-config)
        embed-cfg (config/embed-config)
        conn (index-conn/open (str (io/file data-dir "index.dtlv")) (:dims embed-cfg))
        code (try
               (let [report (harness/run-eval
                              {:retriever (rd/retriever conn #(embed/embed-all! embed-cfg % 32))
                               :rerank-fn #(rerank-client/rerank! (config/rerank-config) %1 %2 %3)}
                              conn
                              {:questions (edn/read-string (slurp "eval/questions.edn"))
                               :principals (harness/load-principals "eval/users.edn")
                               :variant-names (variant-names args)})
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
