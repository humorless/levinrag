(ns replware.levinrag.test-utils
  (:require [hickory.core :as hickory]
            [integrant-extras.tests :as ig-extras]
            [replware.levinrag.db.app-conn :as app-conn]
            [replware.levinrag.db.index-conn :as index-conn]
            [replware.levinrag.server :as server]))

(def ^:const TEST-CSRF-TOKEN "test-csrf-token")
(def ^:const TEST-SECRET-KEY "test-secret-key")

(defn response->hickory
  "Convert a Ring response body to a Hickory document."
  [response]
  (-> response :body hickory/parse hickory/as-hickory))

(defn index-conn
  "Get the index.dtlv connection from the test system."
  []
  (::index-conn/index-conn ig-extras/*test-system*))

(defn app-conn
  "Get the app.dtlv connection from the test system."
  []
  (::app-conn/app-conn ig-extras/*test-system*))

(defn server
  "Get the server instance from the test system."
  []
  (::server/server ig-extras/*test-system*))
