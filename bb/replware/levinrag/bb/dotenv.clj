(ns replware.levinrag.bb.dotenv
  "`.env` for the bb tasks (SPEC.md §5). Plain Clojure: runs in bb and in
   the JVM tests. The server itself never reads `.env`; bb passes these
   variables to the JVMs it starts."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- bad-line [n line why]
  (throw (ex-info (str ".env 第 " n " 行" why "：" line) {:line n})))

(defn- value
  "The value after `=`: quoted (taken literally up to the closing quote,
   which must end the value) or bare (up to a ` #` comment; a bare value
   that starts with `#` is only a comment, so it is empty)."
  [n line s]
  (let [q (first s)]
    (if (#{\' \"} q)
      (let [end (str/index-of s q 1)]
        (when (or (nil? end) (not (re-matches #"\s*(#.*)?" (subs s (inc end)))))
          (bad-line n line "的引號沒有正確結束"))
        (subs s 1 end))
      (str/trim (str/replace s #"(?:^|\s+)#.*$" "")))))

(defn parse
  "Map of variable name → value from `.env` text. Blank lines and `#`
   comments are skipped; `export ` is allowed; the last assignment wins.
   Anything else is an error, so a typo does not silently drop a setting."
  [text]
  (reduce (fn [m [n line]]
            (let [t (str/trim line)]
              (if (or (str/blank? t) (str/starts-with? t "#"))
                m
                (let [[_ k v] (re-matches #"(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)" t)]
                  (when-not k (bad-line n line "不是 KEY=VALUE"))
                  (assoc m k (value n line (str/trim v)))))))
          {}
          (map vector (iterate inc 1) (str/split-lines (str/replace-first text #"^\uFEFF" "")))))

(defn read-file
  "parse of the file at `path`, or {} when it does not exist."
  [path]
  (let [f (io/file path)]
    (if (.isFile f) (parse (slurp f)) {})))

(defn extra-env
  "The `.env` variables to pass to a child process: those not already set
   in `shell-env`, so an `export` in the shell always wins."
  [file-env shell-env]
  (into {} (remove (fn [[k]] (contains? shell-env k))) file-env))
