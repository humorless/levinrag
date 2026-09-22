(ns hybridrag.health-test
  (:require [clj-http.client :as http]
            [clojure.test :refer :all]
            [hybridrag.test-utils :as test-utils]
            [integrant-extras.tests :as ig-extras]
            [reitit-extras.tests :as reitit-extras]))

(use-fixtures :once
  (ig-extras/with-system))

(deftest test-health-reports-both-dbs-open
  (let [url (str (reitit-extras/get-server-url (test-utils/server) :host)
                 "/api/v1/health")
        response (http/get url {:as :json
                                :throw-exceptions false})]
    (is (= 200 (:status response)))
    (is (= "ok" (get-in response [:body :index_db])))
    (is (= "ok" (get-in response [:body :app_db])))))
