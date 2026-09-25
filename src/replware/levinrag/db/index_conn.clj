(ns replware.levinrag.db.index-conn
  "Integrant component for the embedded Datalevin connection that stores
   corpus-derived data (collections/docs/sections/chunks). Disposable and
   rebuildable from corpus/ at any time (SPEC.md D1)."
  (:require [clojure.tools.logging :as log]
            [datalevin.core :as d]
            [datalevin.udf :as udf]
            [integrant-extras.core :as ig-extras]
            [integrant.core :as ig]
            [replware.levinrag.config :as config]
            [replware.levinrag.db.schema :as schema]
            [replware.levinrag.search.analyzer :as analyzer]))

(defonce ^:private udf-registry
  (doto (udf/create-registry)
    (udf/register! analyzer/udf-descriptor analyzer/analyze)))

(defn- store-opts
  "index-opts plus the CJK analyzer (SPEC.md §8) on the :chunk/index-text
   search domain. The analyzer is runtime state: Datalevin refuses to open
   the store without the registry, so every open goes through here.
   Changing the analyzer requires `bb reindex`."
  [dims]
  (merge (schema/index-opts dims)
         {:runtime-opts {:udf-registry udf-registry}
          :search-domains {"chunk/index-text" {:analyzer analyzer/udf-descriptor}}}))

(defn open
  "Open (creating if needed) index.dtlv at `dir` with the index schema and
   store options. Used by the component and by the ingest CLI."
  ([dir] (open dir (:dims (config/embed-config))))
  ([dir dims]
   (d/get-conn dir schema/index-schema (store-opts dims))))

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
