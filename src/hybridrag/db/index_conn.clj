(ns hybridrag.db.index-conn
  "Integrant component for the embedded Datalevin connection that stores
   corpus-derived data (collections/docs/sections/chunks). Disposable and
   rebuildable from corpus/ at any time (SPEC.md D1)."
  (:require [clojure.tools.logging :as log]
            [datalevin.core :as d]
            [hybridrag.config :as config]
            [hybridrag.db.schema :as schema]
            [integrant-extras.core :as ig-extras]
            [integrant.core :as ig]))

(defn open
  "Open (creating if needed) index.dtlv at `dir` with the index schema and
   store options. Used by the component and by the ingest CLI."
  ([dir] (open dir (:dims (config/embed-config))))
  ([dir dims]
   (d/get-conn dir schema/index-schema (schema/index-opts dims))))

(defmethod ig/assert-key ::index-conn
  [_ params]
  (ig-extras/validate-schema!
    {:component ::index-conn
     :data params
     :schema [:map [:dir string?]]}))

(defmethod ig/init-key ::index-conn
  [_ {:keys [dir]}]
  (log/info "[INDEX-CONN] Opening index.dtlv at" dir)
  (open dir))

(defmethod ig/halt-key! ::index-conn
  [_ conn]
  (log/info "[INDEX-CONN] Closing index.dtlv")
  (d/close conn))
