(ns hybridrag.trace
  "Query traces in app.dtlv (SPEC.md §14): one per /search or /ask, stage
   data as an EDN blob holding ids and scores only, never chunk text."
  (:require [datalevin.core :as d]))

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

(defn fetch
  "Trace map by uuid, or nil."
  [db id]
  (when-let [e (d/entid db [:trace/id id])]
    (d/pull db '[*] e)))
