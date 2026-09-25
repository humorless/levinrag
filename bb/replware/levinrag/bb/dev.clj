(ns replware.levinrag.bb.dev
  "`bb dev:models`, `bb dev:up`, `bb dev:down` (docs/howto/dev.md): start
   the local development environment, e.g. after a reboot. Each step checks
   first and starts only what is not running, so running a task twice is
   harmless. bb-only (processes, sockets, HTTP); the decisions are in
   replware.levinrag.bb.dev-plan."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [replware.levinrag.bb.dev-plan :as plan]))

(def serve-port 8000) ; the :default profile's port (resources/config.edn)
(def nrepl-port 1667) ; CLAUDE.md

;; --- small helpers ---

(defn- fail! [msg] (throw (ex-info msg {::fail true})))

(defn- port-open? [port]
  (try (with-open [s (java.net.Socket.)]
         (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 500)
         true)
       (catch Exception _ false)))

;; LM Studio speaks HTTP/1.1 only: the default client tries HTTP/2 first and
;; times out against it
(def ^:private client (delay (http/client {:version :http1.1})))

(defn- http-ok? [url]
  (try (= 200 (:status (http/get url {:throw false
                                      :timeout 3000
                                      :client @client})))
       (catch Exception _ false)))

(defn- wait-until
  "Poll `ok?` every 2 s for up to `secs`; true once it holds."
  [ok? secs]
  (loop [waited 0]
    (cond
      (ok?) (do (when (pos? waited) (println)) true)
      (>= waited secs) (do (println) false)
      :else (do (print ".") (flush) (Thread/sleep 2000) (recur (+ waited 2))))))

(defn- sh
  "Run argv, output to the terminal; true on exit 0."
  [& argv]
  (zero? (:exit (apply p/shell {:continue true} argv))))

(defn- tmux-session? [s]
  (zero? (:exit (p/shell {:continue true
                          :out :string
                          :err :string} "tmux" "has-session" "-t" s))))

(defn- tmux-start!
  "New detached tmux session `s` running `cmd` in the project directory."
  [s cmd]
  (sh "tmux" "new-session" "-d" "-s" s "-c" (str (fs/cwd)) cmd))

(defn- require-tool! [cmd fix]
  (or (fs/which cmd) (fail! (str "找不到 " cmd "：" fix))))

(defn- lms-path []
  (or (some-> (fs/which "lms") str)
      (let [p (fs/path (fs/home) ".lmstudio" "bin" "lms")]
        (when (fs/executable? p) (str p)))
      (fail! "找不到 lms：安裝 LM Studio（https://lmstudio.ai）並開啟一次，它會裝好 ~/.lmstudio/bin/lms")))

(defn run-task
  "Run `f`; a ::fail (or any ExceptionInfo) prints its message and exits 1."
  [f]
  (try (when-not (f) (System/exit 1))
       (catch clojure.lang.ExceptionInfo e
         (binding [*out* *err*] (println "錯誤：" (ex-message e)))
         (System/exit 1))))

;; --- dev:models ---

(defn- lmstudio-up! [{:keys [port]
                      :as lms}]
  (let [lms-bin (lms-path)
        models-url (str "http://localhost:" port "/v1/models")]
    (if (http-ok? models-url)
      (println (str "[OK]   LM Studio server 已在 :" port " 執行"))
      (do (println (str "[..]   啟動 LM Studio server（:" port "）"))
          (sh lms-bin "server" "start" "--port" (str port))
          (when-not (wait-until #(http-ok? models-url) 60)
            (fail! (str "LM Studio server 沒有在 :" port " 回應；開啟 LM Studio app 看看")))))
    (let [loaded (json/parse-string (:out (p/shell {:out :string} lms-bin "ps" "--json")))]
      (doseq [{:keys [model context]} (plan/to-load lms loaded)]
        (println (str "[..]   載入 " model (when context (str "（context " context "）"))))
        (when-not (apply sh lms-bin "load" model "-y"
                         (when context ["--context-length" (str context)]))
          (fail! (str "lms load " model " 失敗；模型名稱是否和 LM Studio 裡的一致？（lms ls）"))))
      (doseq [{:keys [model]} (:models lms)]
        (println (str "[OK]   " model " 已載入"))))))

(defn- llama-up! [{:keys [port]
                   :as llama}]
  (let [health (str "http://localhost:" port "/health")]
    (if (http-ok? health)
      (println (str "[OK]   reranker（llama-server）已在 :" port " 執行"))
      (do
        (require-tool! "llama-server" "brew install llama.cpp")
        (if (tmux-session? "rerank")
          (println "[..]   tmux session rerank 已存在，等它就緒")
          (do (println (str "[..]   在 tmux session rerank 啟動 llama-server（:" port "）；"
                            "第一次會下載約 636 MB 的模型"))
              (tmux-start! "rerank" (str/join " " (plan/llama-command llama)))))
        (if (wait-until #(http-ok? health) 600)
          (println (str "[OK]   reranker 已在 :" port " 執行"))
          (fail! "reranker 沒有回應；用 tmux attach -t rerank 看它的輸出"))))))

(defn models!
  "Start the local model servers of the VLLM_SETUP.md Mac recipe that are not
   running. `env` is the shell environment with .env under it."
  [env]
  (let [{:keys [lmstudio llama]} (plan/models-plan env)]
    (if-not (or lmstudio llama)
      (do (println "模型端點都不在本機（見 .env 的 VLLM_*_BASE_URL），這個指令不適用；用 bb doctor 檢查連線。")
          true)
      (do (when llama (require-tool! "tmux" "brew install tmux"))
          ;; embed and chat first: on a 16 GB Mac, loading them after the
          ;; reranker can run out of memory
          (some-> lmstudio lmstudio-up!)
          (some-> llama llama-up!)
          (println "模型都已就緒。下一步：bb dev:up")
          true))))

;; --- dev:up / dev:down ---

(defn up!
  "Start the web server and the nREPL in tmux if they are not running, then
   run bb doctor. Returns doctor's success."
  []
  (require-tool! "tmux" "brew install tmux")
  (if (port-open? serve-port)
    (println (str "[OK]   server 已在 :" serve-port " 執行"))
    (do (if (tmux-session? "levinrag")
          (println "[..]   tmux session levinrag 已存在，等它就緒")
          (do (println (str "[..]   在 tmux session levinrag 啟動 bb serve（:" serve-port "）"))
              (tmux-start! "levinrag" "bb serve")))
        (if (wait-until #(http-ok? (str "http://localhost:" serve-port "/api/v1/health/live")) 180)
          (println (str "[OK]   server：http://localhost:" serve-port))
          (fail! "server 沒有回應；用 tmux attach -t levinrag 看它的輸出"))))
  (if (port-open? nrepl-port)
    (println (str "[OK]   nREPL 已在 :" nrepl-port " 執行"))
    (do (spit ".nrepl-port" (str nrepl-port))
        (if (tmux-session? "nrepl")
          (println "[..]   tmux session nrepl 已存在，等它就緒")
          (do (println (str "[..]   在 tmux session nrepl 啟動 nREPL（:" nrepl-port "）"))
              (tmux-start! "nrepl" (str "clojure -M:jvm-opts:test:dev:nrepl --port " nrepl-port))))
        (if (wait-until #(port-open? nrepl-port) 180)
          (println (str "[OK]   nREPL：port " nrepl-port))
          (fail! "nREPL 沒有啟動；用 tmux attach -t nrepl 看它的輸出"))))
  (println "\n檢查整體設定（bb doctor）：")
  (let [ok? (sh "bb" "doctor")]
    (println "\ntmux session：levinrag（server）、nrepl、rerank；用 tmux attach -t <名稱> 查看，Ctrl-b d 離開")
    ok?))

(defn down!
  "Stop the tmux sessions the dev tasks start. LM Studio is left running
   (quit it from its menu, or `lms server stop` / `lms unload --all`)."
  []
  (require-tool! "tmux" "brew install tmux")
  (doseq [s ["levinrag" "nrepl" "rerank"]]
    (if (tmux-session? s)
      (do (sh "tmux" "kill-session" "-t" s) (println (str "[OK]   已停止 tmux session " s)))
      (println (str "[--]   tmux session " s " 沒有在執行"))))
  (println "LM Studio 沒有動；要停止它：lms server stop、lms unload --all")
  true)
