(ns replware.levinrag.auth.cli
  "`bb user:*` / `bb token:*` (SPEC.md §13). Opens DATA_DIR/app.dtlv
   directly; no registration or user-management UI in the MVP."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datalevin.core :as d]
            [replware.levinrag.auth.token :as token]
            [replware.levinrag.auth.users :as users]
            [replware.levinrag.config :as config]
            [replware.levinrag.db.schema :as schema]))

(defn parse-args
  "Positional args plus --flag value / --switch options."
  [args]
  (loop [[a & more] args, pos [], opts {}]
    (cond
      (nil? a) {:args pos
                :opts opts}
      (= "--admin" a) (recur more pos (assoc opts :admin? true))
      (str/starts-with? a "--") (recur (rest more) pos (assoc opts (keyword (subs a 2)) (first more)))
      :else (recur more (conj pos a) opts))))

(defn- split-groups [s]
  (->> (str/split (or s "") #",") (map str/trim) (remove str/blank?) vec))

(defn- read-password [prompt]
  (if-let [console (System/console)]
    (String. (.readPassword console "%s" (into-array Object [prompt])))
    (do (print prompt) (flush) (read-line))))

(defn run
  "Execute one command against `conn`; returns the text to print."
  [conn cmd {:keys [args opts]} & {:keys [password-fn]
                                   :or {password-fn read-password}}]
  (let [[a1] args]
    (case cmd
      "user:create"
      (let [p (users/create-user! conn a1 {:groups (split-groups (:groups opts))
                                           :admin? (:admin? opts)})]
        (str "已建立使用者 " a1 "，群組 " (str/join "," (sort (:groups p)))
             (when (:admin? p) "（admin）")
             "。請用 bb user:passwd " a1 " 設定密碼。"))

      "user:groups"
      (let [p (users/set-groups! conn a1 (split-groups (second args)))]
        (str a1 " 的群組已改為 " (str/join "," (sort (:groups p))) "。"))

      "user:passwd"
      (let [pw (password-fn "新密碼：")
            again (password-fn "再輸入一次：")]
        (cond
          (not= pw again) (throw (ex-info "兩次輸入的密碼不一致。" {}))
          (< (count pw) 8) (throw (ex-info "密碼至少 8 個字元。" {}))
          :else (do (users/set-password! conn a1 pw)
                    (users/revoke-sessions! conn a1)
                    (str a1 " 的密碼已更新，既有的網頁登入已失效。"))))

      "token:create"
      (let [{t :token
             p :prefix} (token/create-token! conn a1 (:label opts))]
        (str "Token（只顯示這一次，請妥善保存）：\n" t "\n撤銷時使用前綴：" p))

      "token:revoke"
      (str "已撤銷 token " a1 "（" (token/revoke-token! conn a1) "）。")

      (throw (ex-info (str "未知的指令：" cmd) {})))))

(defn -main [cmd & args]
  (let [dir (str (io/file (:data-dir (config/corpus-config)) "app.dtlv"))
        conn (d/get-conn dir schema/app-schema)
        code (try
               (println (run conn cmd (parse-args args)))
               0
               (catch clojure.lang.ExceptionInfo e
                 (binding [*out* *err*] (println "錯誤：" (ex-message e)))
                 1)
               (finally (d/close conn)))]
    (shutdown-agents)
    (System/exit code)))
