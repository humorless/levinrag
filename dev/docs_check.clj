(ns docs-check
  "`bb docs:check`: the bilingual docs stay in sync (CLAUDE.md, SPEC.md §0
   rule 9). For every English/zh-TW pair: same heading levels and section
   numbers, a language switch to the other version, and every relative
   link and #anchor resolves (GitHub heading-anchor rules)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def pairs
  [["README.md" "README.zh-TW.md"]
   ["SPEC.md" "SPEC.zh-TW.md"]
   ["VLLM_SETUP.md" "VLLM_SETUP.zh-TW.md"]
   ["docs/howto/ops.md" "docs/howto/ops.zh-TW.md"]
   ["docs/howto/admin.md" "docs/howto/admin.zh-TW.md"]
   ["docs/howto/user.md" "docs/howto/user.zh-TW.md"]
   ["docs/howto/quick-start.md" "docs/howto/quick-start.zh-TW.md"]
   ["docs/howto/dev.md" "docs/howto/dev.zh-TW.md"]
   ["docs/design/rationale.md" "docs/design/rationale.zh-TW.md"]])

;; single-language docs whose links are checked too
(def others ["CLAUDE.md" "docs/design/2026-09-22-initial-spec.md"])

(defn- without-fences [s] (str/replace s #"(?s)```.*?```" ""))

(defn- without-code [s] (str/replace (without-fences s) #"`[^`\n]*`" ""))

(defn headings
  "[level text] of every Markdown heading outside fenced code."
  [s]
  (->> (str/split-lines (without-fences s))
       (keep #(re-matches #"(#{1,6})\s+(.*)" %))
       (mapv (fn [[_ h t]] [(count h) (str/trim t)]))))

(defn slug
  "GitHub's anchor for a heading text."
  [t]
  (-> t
      str/lower-case
      (str/replace #"[`*_]" "")
      (str/replace #"[^\p{L}\p{N}\s-]" "")
      (str/replace #"\s" "-")))

(defn- section-number [[level t]]
  [level (some-> (re-find #"^(?:[0-9]+(?:\.[0-9]+)*|附錄 A|Appendix A)" t)
                 (str/replace "附錄 A" "Appendix A"))])

(defn link-problems
  "Relative links in file `f` whose target file or #anchor does not exist."
  [f]
  (let [dir (fs/parent (fs/absolutize f))]
    (for [[_ target anchor] (re-seq #"\x5d\x28([^\x29#\s]*)(?:#([^\x29\s]+))?\x29" (without-code (slurp f)))
          :when (not (re-find #"^[a-z]+:" target))
          :let [tf (if (str/blank? target) (fs/absolutize f) (fs/normalize (fs/path dir target)))
                problem (cond
                          (not (fs/exists? tf)) (str "missing file " target)
                          (and anchor
                               (str/ends-with? (str tf) ".md")
                               (not (contains? (set (map (comp slug second) (headings (slurp (str tf)))))
                                               anchor)))
                          (str "missing anchor " target "#" anchor))]
          :when problem]
      (str f " → " problem))))

(defn pair-problems [[en zh]]
  (let [missing (remove fs/exists? [en zh])]
    (if (seq missing)
      (map #(str "missing " %) missing)
      (let [he (headings (slurp en))
            hz (headings (slurp zh))]
        (concat
          (when (not= (map section-number he) (map section-number hz))
            [(str en " and " zh ": headings differ (" (count he) " vs " (count hz)
                  " headings, or different levels / section numbers)")])
          (when-not (str/includes? (slurp en) (str "[繁體中文](" (fs/file-name zh) ")"))
            [(str en ": no language switch to " (fs/file-name zh))])
          (when-not (str/includes? (slurp zh) (str "[English](" (fs/file-name en) ")"))
            [(str zh ": no language switch to " (fs/file-name en))])
          (link-problems en)
          (link-problems zh))))))

(defn problems []
  (concat (mapcat pair-problems pairs) (mapcat link-problems others)))

(defn -main [& _]
  (let [ps (problems)]
    (if (seq ps)
      (do (run! println ps)
          (println (count ps) "problem(s)")
          (System/exit 1))
      (println "docs check OK:" (count pairs) "language pairs,"
               (+ (* 2 (count pairs)) (count others)) "files"))))
