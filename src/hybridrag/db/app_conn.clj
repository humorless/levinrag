(ns hybridrag.db.app-conn
  "Integrant component for the embedded Datalevin connection that stores
   users, tokens and traces (SPEC.md D1). Never touched by `bb reindex`."
  (:require [clojure.tools.logging :as log]
            [datalevin.core :as d]
            [hybridrag.db.schema :as schema]
            [integrant-extras.core :as ig-extras]
            [integrant.core :as ig]))

(defmethod ig/assert-key ::app-conn
  [_ params]
  (ig-extras/validate-schema!
    {:component ::app-conn
     :data params
     :schema [:map [:dir string?]]}))

(defmethod ig/init-key ::app-conn
  [_ {:keys [dir]}]
  (log/info "[APP-CONN] Opening app.dtlv at" dir)
  (d/get-conn dir schema/app-schema))

(defmethod ig/halt-key! ::app-conn
  [_ conn]
  (log/info "[APP-CONN] Closing app.dtlv")
  (d/close conn))
