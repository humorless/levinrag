(ns hybridrag.home-test
  (:require [clj-http.client :as http]
            [clojure.test :refer :all]
            [hickory.select :as select]
            [hybridrag.server :as-alias server]
            [hybridrag.test-utils :as test-utils]
            [integrant-extras.tests :as ig-extras]
            [reitit-extras.tests :as reitit-extras]))

(use-fixtures :once
  (ig-extras/with-system))

(deftest test-home-page-is-loaded-correctly
  (let [url (reitit-extras/get-server-url (test-utils/server) :host)
        body (test-utils/response->hickory (http/get url))]
    (is (= "Clojure Stack Lite"
           (->> body
                (select/select (select/tag :span))
                (first)
                :content
                (first))))))
