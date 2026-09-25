(ns replware.levinrag.bb.doctor
  "`bb doctor`: checks that a fresh checkout can run (SPEC.md §5) — tools,
   settings, the corpus and its directory permissions files — and says how
   to fix each problem. Runs in bb, so it works before Clojure is installed;
   the model endpoints are checked afterwards by `bb vllm:check`.
   Plain Clojure: the checks are unit-tested on the JVM."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def ^:private min-java 21)

(defn- result [level item msg & [fix]]
  (cond-> {:level level
           :item item
           :msg msg} fix (assoc :fix fix)))

;; --- tools ---

(defn java-major
  "Major version from `java -version` output (1.8 → 8), or nil."
  [s]
  (when-let [[_ a b] (re-find #"version \"(\d+)(?:\.(\d+))?" (or s ""))]
    (let [a (parse-long a)]
      (if (and (= 1 a) b) (parse-long b) a))))

(defn check-tools
  "`:java-version` is the `java -version` output (nil when java is missing);
   `:css?` whether the web UI's CSS is built, `:tailwind?` whether
   tailwindcss is on the PATH to build it (bb serve does, when missing)."
  [{:keys [java-version clojure? css? tailwind?]}]
  (let [v (java-major java-version)
        fix "在專案目錄執行 mise trust && mise install（安裝 .mise.toml 列的 Java、Clojure、Babashka 等；mise：https://mise.jdx.dev）"]
    [(cond
       (nil? java-version) (result :fail "java" "找不到 java" fix)
       (nil? v) (result :fail "java" (str "無法判斷 java 版本：" (first (str/split-lines java-version))) fix)
       (< v min-java) (result :fail "java" (str "Java " v "，需要 " min-java " 以上") fix)
       :else (result :ok "java" (str "Java " v)))
     (if clojure?
       (result :ok "clojure" "已安裝 Clojure CLI")
       (result :fail "clojure" "找不到 clojure 指令" fix))
     (cond
       css? (result :ok "css" "網頁樣式已建置")
       tailwind? (result :ok "css" "網頁樣式尚未建置，bb serve 會自動建置")
       :else (result :warn "css" "網頁樣式尚未建置，也找不到 tailwindcss：網頁會沒有樣式（API 與 bb 指令不受影響）"
                     "mise install tailwindcss，再執行 bb css-build"))]))

;; --- settings ---

(def ^:private required
  {"VLLM_CHAT_BASE_URL" "chat 端點，例如 http://localhost:8003/v1"
   "VLLM_CHAT_MODEL" "chat 模型名稱"})

(defn check-settings
  "`:env` is the shell environment with `.env` under it (the shell wins),
   `:dotenv?` whether `.env` exists."
  [{:keys [dotenv? env]}]
  (into [(if dotenv?
           (result :ok ".env" "已讀取 .env")
           (result :warn ".env" "沒有 .env，只使用 shell 裡 export 的變數"
                   "cp .env.example .env，再編輯 .env"))]
        (for [[k what] (sort required)]
          (if (str/blank? (get env k))
            (result :fail k (str "未設定（" what "）") "在 .env 填入這個變數")
            (result :ok k (get env k))))))

;; --- corpus ---

(def ^:private text-exts #{"md" "markdown" "txt"})
(def ^:private convert-exts #{"pdf" "doc" "docx" "ppt" "pptx" "xls" "xlsx" "odt" "rtf" "html" "htm" "epub"})

(defn- ext [^java.io.File f]
  (let [n (.getName f) i (str/last-index-of n ".")]
    (when (and i (pos? i)) (str/lower-case (subs n (inc i))))))

(defn- corpus-files
  "Files under `dir` outside the directories ingest skips (a path segment
   starting with \".\" or \"_\", SPEC.md §7.1), as [relative-path file]."
  [dir]
  (let [root (.toPath (io/file dir))]
    (for [^java.io.File f (file-seq (io/file dir))
          :when (.isFile f)
          :let [rel (str/replace (str (.relativize root (.toPath f))) java.io.File/separator "/")]
          :when (not-any? #(or (str/starts-with? % ".") (str/starts-with? % "_"))
                          (butlast (str/split rel #"/")))]
      [rel f])))

(defn check-corpus [dir]
  (let [d (io/file dir)]
    (if-not (.isDirectory d)
      [(result :fail "CORPUS_DIR" (str "目錄不存在：" dir) "在 .env 把 CORPUS_DIR 指向語料目錄")]
      (let [files (map second (corpus-files dir))
            n-text (count (filter #(and (text-exts (ext %))
                                        (not (str/starts-with? (.getName ^java.io.File %) "."))
                                        (not (str/starts-with? (.getName ^java.io.File %) "_")))
                                  files))
            n-conv (count (filter #(convert-exts (ext %)) files))
            convert-fix "PDF／Office 檔要先轉成 Markdown（.md）才會被匯入，例如用 pandoc、marker 或 docling"]
        (cond-> [(if (pos? n-text)
                   (result :ok "CORPUS_DIR" (str dir "：" n-text " 個 .md／.markdown／.txt 檔"))
                   (result :fail "CORPUS_DIR" (str dir "：沒有可匯入的 .md／.markdown／.txt 檔") convert-fix))]
          (pos? n-conv) (conj (result :warn "CORPUS_DIR" (str n-conv " 個 PDF／Office／HTML 檔不會被匯入") convert-fix)))))))

;; --- directory permissions (_collection.edn) ---

(def ^:private collection-keys #{:name :read-groups})

(defn- collection-problem
  "Why the parsed _collection.edn `v` would not be applied as written, or nil."
  [v]
  (cond
    (not (map? v)) "內容必須是一個 map，例如 {:read-groups [\"hr\"]}"
    (seq (remove collection-keys (keys v)))
    (str "不認得的 key：" (str/join " " (remove collection-keys (keys v)))
         "（可用：" (str/join " " (sort collection-keys)) "）")
    (and (contains? v :read-groups)
         (not (and (sequential? (:read-groups v)) (every? string? (:read-groups v)))))
    "`:read-groups` 必須是字串清單，例如 [\"hr\" \"all\"]"))

(defn- read-edn-values [^String s]
  (let [r (java.io.PushbackReader. (java.io.StringReader. s))]
    (loop [acc []]
      (let [v (edn/read {:eof ::eof} r)]
        (if (= ::eof v) acc (recur (conj acc v)))))))

(defn- collection-file? [^String n]
  (boolean (re-matches #"(?i)_?collections?\.edn" n)))

(defn collection-file-problem
  "Why the settings file named `n` with content `text` would not be applied
   as written, or nil. Must agree with ingest (walker/scan-collection-edns);
   a test checks that it does."
  [^String n ^String text]
  (if (not= "_collection.edn" n)
    (str "檔名 " n " 應為 _collection.edn")
    (let [[vs err] (try [(read-edn-values text)] (catch Exception e [nil (ex-message e)]))]
      (cond
        err (str "無法解析：" err)
        (next vs) "檔案裡有不只一個 EDN 值，只能有一個 map"
        :else (collection-problem (first vs))))))

(defn check-permissions
  "Every _collection.edn that ingest reads (and every look-alike name) must
   parse and mean what it says: ingest keeps the docs a broken one governs
   out of the index (SPEC.md §7.2 rule 5). Then the root's default groups."
  [dir root-read-groups]
  (let [files (for [[rel ^java.io.File f] (corpus-files dir)
                    :when (collection-file? (.getName f))]
                [rel (.getName f) (slurp f)])
        broken (for [[rel n text] files
                     :let [why (collection-file-problem n text)]
                     :when why]
                 (result :fail "_collection.edn" (str rel "：" why)
                         "修正這個檔案；在修好之前，它管轄的文件都不會匯入（已匯入的會被移除）"))
        root (some (fn [[rel n text]]
                     (when (and (= "_collection.edn" rel) (nil? (collection-file-problem n text)))
                       (:read-groups (first (read-edn-values text)))))
                   files)
        env-groups (remove str/blank? (map str/trim (str/split (or root-read-groups "") #",")))]
    (conj (vec broken)
          (cond
            root (result :ok "根目錄權限" (str "_collection.edn：" (str/join "," root)))
            (seq env-groups) (result :ok "根目錄權限" (str "ROOT_READ_GROUPS：" (str/join "," env-groups)))
            :else (result :warn "根目錄權限"
                          "語料根目錄沒有 _collection.edn，ROOT_READ_GROUPS 也沒設：沒有另外設定權限的文件只有 admin 讀得到"
                          "在 .env 設 ROOT_READ_GROUPS=all，或在語料根目錄放 _collection.edn：{:read-groups [\"all\"]}")))))

;; --- output ---

(defn report [results]
  (str/join "\n"
            (for [{:keys [level item msg fix]} results]
              (str (case level :ok "[OK]   " :warn "[WARN] " :fail "[FAIL] ")
                   item "：" msg
                   (when fix (str "\n       → " fix))))))

(defn failed? [results] (boolean (some #(= :fail (:level %)) results)))

;; --- gathering (I/O) ---

(defn- on-path? [cmd]
  (some #(.canExecute (io/file % cmd))
        (str/split (or (System/getenv "PATH") "") (re-pattern java.io.File/pathSeparator))))

(defn- java-version []
  (when (on-path? "java")
    (try (:err (sh/sh "java" "-version")) (catch Exception _ nil))))

(defn run-checks
  "All checks for `env` (the shell environment with `.env` under it)."
  [env dotenv?]
  (let [corpus (get env "CORPUS_DIR" "./corpus")]
    (concat (check-tools {:css? (.isFile (io/file "resources/public/css/output.css"))
                          :tailwind? (on-path? "tailwindcss")
                          :java-version (java-version)
                          :clojure? (on-path? "clojure")})
            (check-settings {:dotenv? dotenv?
                             :env env})
            [(result :ok "DATA_DIR" (get env "DATA_DIR" "data"))]
            (check-corpus corpus)
            (when (.isDirectory (io/file corpus))
              (check-permissions corpus (get env "ROOT_READ_GROUPS"))))))
