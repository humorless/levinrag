(ns replware.levinrag.ingest.report
  "Ingestion report output (SPEC.md §7.6): EDN file under
   <data-dir>/ingest-reports/ and a short console summary."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(defn write-report!
  "Write `report` as EDN to <data-dir>/ingest-reports/<timestamp>.edn and
   return the file path."
  [data-dir report]
  (let [ts (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss-SSS"))
        f (io/file data-dir "ingest-reports" (str ts ".edn"))]
    (io/make-parents f)
    (spit f (with-out-str (pprint/pprint report)))
    (str f)))

(defn summary
  "Human-readable multi-line summary of a report."
  [{:keys [added updated acl-updated skipped deleted errors total-chunks
           chunks-written max-chunk-tokens unresolved-links index-lag elapsed-ms]}]
  (str/join
    "\n"
    (concat
      [(format "docs: %d added, %d updated, %d acl-updated, %d skipped, %d deleted, %d errors"
               added updated acl-updated skipped deleted (count errors))
       (format "chunks: %d written this run, %d in index, longest %d est. tokens"
               chunks-written total-chunks max-chunk-tokens)
       (format "unresolved links: %d, index lag: %s, elapsed: %d ms"
               (count unresolved-links) index-lag elapsed-ms)]
      (for [{:keys [path error]} errors] (str "  ERROR " path ": " error))
      (for [{:keys [doc link]} unresolved-links] (str "  unresolved " doc " -> " link)))))
