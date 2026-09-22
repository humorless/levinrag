(ns hybridrag.test-utils
  (:require [hickory.core :as hickory]
            [hybridrag.db :as db]
            [hybridrag.server :as server]
            [integrant-extras.tests :as ig-extras]))

(def ^:const TEST-CSRF-TOKEN "test-csrf-token")
(def ^:const TEST-SECRET-KEY "test-secret-key")

(defn with-truncated-tables
  "Remove all data from all tables except migrations."
  [f]
  (let [db (::db/db ig-extras/*test-system*)]
    (doseq [table (->> {:select [:name]
                        :from [:sqlite_master]
                        :where [:= :type "table"]}
                       (db/exec! db)
                       (map (comp keyword :name)))
            :when (not= :ragtime_migrations table)]
      (db/exec! db {:delete-from table}))
    (f)))

(defn response->hickory
  "Convert a Ring response body to a Hickory document."
  [response]
  (-> response
      :body
      (hickory/parse)
      (hickory/as-hickory)))

(defn db
  "Get the database connection from the test system."
  []
  (::db/db ig-extras/*test-system*))

(defn server
  "Get the server instance from the test system."
  []
  (::server/server ig-extras/*test-system*))
