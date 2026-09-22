(ns hybridrag.db.index-conn
  "Integrant component for the embedded Datalevin connection that stores
   corpus-derived data (collections/docs/sections/chunks). Disposable and
   rebuildable from corpus/ at any time (SPEC.md D1)."
  (:require [clojure.tools.logging :as log]
            [datalevin.core :as d]
            [integrant-extras.core :as ig-extras]
            [integrant.core :as ig]))

(defmethod ig/assert-key ::index-conn
  [_ params]
  (ig-extras/validate-schema!
    {:component ::index-conn
     :data params
     :schema [:map [:dir string?]]}))

(defmethod ig/init-key ::index-conn
  [_ {:keys [dir]}]
  (log/info "[INDEX-CONN] Opening index.dtlv at" dir)
  (d/get-conn dir {}))

(defmethod ig/halt-key! ::index-conn
  [_ conn]
  (log/info "[INDEX-CONN] Closing index.dtlv")
  (d/close conn))
