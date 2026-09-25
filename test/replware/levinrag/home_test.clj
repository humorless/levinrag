(ns replware.levinrag.home-test
  "The running system (test profile): / sends a logged-out visitor to
   /login, which renders the login form."
  (:require [clj-http.client :as http]
            [clojure.test :refer [deftest is use-fixtures]]
            [hickory.select :as select]
            [replware.levinrag.test-utils :as test-utils]
            [integrant-extras.tests :as ig-extras]
            [reitit-extras.tests :as reitit-extras]))

(use-fixtures :once
  (ig-extras/with-system))

(deftest test-home-redirects-to-login
  (let [url (reitit-extras/get-server-url (test-utils/server) :host)
        resp (http/get url {:redirect-strategy :none})]
    (is (= 302 (:status resp)))
    (is (= "/login?next=%2F" (get-in resp [:headers "Location"])))))

(deftest test-login-page-renders
  (let [url (reitit-extras/get-server-url (test-utils/server) :host)
        body (test-utils/response->hickory (http/get (str url "/login")))]
    (is (= 1 (count (select/select (select/attr :name #(= % "username")) body))))
    (is (= 1 (count (select/select (select/attr :name #(= % "password")) body))))))
