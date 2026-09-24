(ns hybridrag.tmp
  "Temporary directories and Datalevin connections for tests
   (SPEC.md §18.2: a fresh DB per test, deleted afterwards)."
  (:require [clojure.java.io :as io]
            [datalevin.core :as d]
            [hybridrag.db.schema :as schema]))

(defn dir [prefix]
  (str (java.nio.file.Files/createTempDirectory
         prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn delete-tree! [path]
  (doseq [f (reverse (file-seq (io/file path)))] (io/delete-file f true)))

(defn with-app-conn
  "Call (f conn) with a fresh app.dtlv, closed and deleted afterwards."
  [f]
  (let [p (dir "app")
        conn (d/get-conn p schema/app-schema)]
    (try (f conn)
         (finally (d/close conn) (delete-tree! p)))))
