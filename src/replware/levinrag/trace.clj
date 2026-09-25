(ns replware.levinrag.trace
  "Query traces in app.dtlv (SPEC.md §14): one per /search or /ask, stage
   data as an EDN blob holding ids and scores only, never chunk text."
  (:require [clojure.tools.logging :as log]
            [datalevin.core :as d]))

(defn write!
  "Store a trace; returns its uuid."
  [app-conn {:keys [username kind query stages degraded answer]}]
  (let [id (random-uuid)]
    (d/transact! app-conn [(cond-> {:trace/id id
                                    :trace/username username
                                    :trace/kind kind
                                    :trace/query query
                                    :trace/at (java.util.Date.)
                                    :trace/stages stages}
                             (seq degraded) (assoc :trace/degraded (vec degraded))
                             answer (assoc :trace/answer answer))])
    id))

(defn write-failure!
  "Trace for a /search or /ask that failed on a dependency (SPEC.md §14:
   every request writes one). Returns the uuid, or nil when the write
   itself fails — the caller still answers 503."
  [app-conn {:keys [username kind query endpoint message]}]
  (try
    (write! app-conn {:username username
                      :kind kind
                      :query query
                      :stages {:error {:endpoint endpoint
                                       :message message}}
                      :degraded [:dependency-failed]})
    (catch Exception e
      (log/error e "[TRACE] failure trace not written")
      nil)))

(defn fetch
  "Trace map by uuid, or nil."
  [db id]
  (when-let [e (d/entid db [:trace/id id])]
    (d/pull db '[*] e)))

(defn recent
  "The newest `n` traces (no stages), newest first; entity id breaks
   ties between traces written in the same millisecond. A backwards scan
   of the :trace/at index that stops after n datoms, so /admin does not
   load every trace."
  [db n]
  (mapv #(d/pull db [:trace/id :trace/username :trace/kind :trace/query :trace/at] (:e %))
        (d/rseek-datoms db :ave :trace/at nil nil n)))
