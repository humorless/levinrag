(ns hybridrag.web.auth-test
  "Session login/logout and the web auth middleware (SPEC.md §12, §13;
   Phase 4 design): redirects, next handling, CSRF, cookie attributes and
   a session that follows the current user record."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [hybridrag.auth.cli :as cli]
            [hybridrag.auth.users :as users]
            [hybridrag.fixtures :as fx]
            [hybridrag.web-client :as wc]
            [hybridrag.web-fixtures :as wf]
            [hybridrag.web.auth :as web-auth]))

(use-fixtures :once fx/with-sample-index)
(use-fixtures :each wf/with-app-users)

(defn- location [resp] (get-in resp [:headers "Location"]))

(deftest test-redirects-when-logged-out
  (let [c (wc/client (wf/handler))]
    (is (= 302 (:status (wc/request! c :get "/"))))
    (is (= "/login?next=%2F" (location (wc/request! c :get "/"))))
    (is (= "/login?next=%2Fadmin%3Ftab%3Dx"
           (location (wc/request! c :get "/admin?tab=x"))))
    (testing "HTMX requests get HX-Redirect instead of a 302"
      (let [resp (wc/request! c :get "/" :headers {"hx-request" "true"})]
        (is (= 200 (:status resp)))
        (is (= "/login" (get-in resp [:headers "HX-Redirect"])))))))

(deftest test-login-success-and-logout
  (let [c (wc/client (wf/handler))
        resp (wc/login! c "alice" "alice-pw")]
    (is (= 302 (:status resp)))
    (is (= "/" (location resp)))
    (let [home (wc/request! c :get "/")]
      (is (= 200 (:status home)))
      (is (str/includes? (:body home) "alice")))
    (let [out (wc/post! c "/logout" {})]
      (is (= 302 (:status out)))
      (is (= "/login" (location out))))
    (is (= 302 (:status (wc/request! c :get "/"))))))

(deftest test-login-failure
  (doseq [[u p] [["alice" "wrong"] ["mallory" "x"] ["alice" ""]]]
    (let [c (wc/client (wf/handler))
          resp (wc/login! c u p)]
      (is (= 200 (:status resp)) u)
      (is (str/includes? (:body resp) "帳號或密碼錯誤"))
      (is (= 302 (:status (wc/request! c :get "/")))))))

(deftest test-login-next-is-same-site
  (let [c (wc/client (wf/handler))]
    (is (= "/docs/hr/leave.md?chunk=x" (location (wc/login! c "alice" "alice-pw" "/docs/hr/leave.md?chunk=x")))))
  (doseq [bad ["//evil.example" "/\\evil.example" "https://evil.example" "javascript:alert(1)" ""
               "/\t/evil.example" "/\n/evil.example" "/\r/evil.example" "/a\\b" "/ok\u0000x"]]
    (let [c (wc/client (wf/handler))]
      (is (= "/" (location (wc/login! c "alice" "alice-pw" bad))) bad)))
  (testing "the login page carries next into the form"
    (let [c (wc/client (wf/handler))]
      (is (str/includes? (:body (wc/request! c :get "/login?next=%2Fadmin")) "value=\"/admin\"")))))

(deftest test-csrf-required
  (let [c (wc/client (wf/handler))]
    (wc/request! c :get "/login")
    (is (= 403 (:status (wc/request! c :post "/login" :form {"username" "alice"
                                                           "password" "alice-pw"})))))
  (let [c (wf/logged-in "alice")]
    (is (= 403 (:status (wc/request! c :post "/logout" :form {}))))))

(deftest test-session-reflects-current-user
  (testing "a deleted user is logged out on the next request"
    (let [c (wf/logged-in "alice")]
      (is (= 200 (:status (wc/request! c :get "/"))))
      (d/transact! wf/*app* [[:db/retractEntity [:user/username "alice"]]])
      (is (= 302 (:status (wc/request! c :get "/"))))))
  (testing "a demoted admin loses /admin immediately"
    (let [c (wf/logged-in "admin")]
      (is (= 200 (:status (wc/request! c :get "/admin"))))
      (d/transact! wf/*app* [{:user/username "admin"
                              :user/admin? false}])
      (is (= 404 (:status (wc/request! c :get "/admin")))))))

(deftest test-admin-only
  (is (= 404 (:status (wc/request! (wf/logged-in "alice") :get "/admin"))))
  (is (= 200 (:status (wc/request! (wf/logged-in "admin") :get "/admin")))))

(deftest test-cookie-attributes
  (let [cookie (fn [opts]
                 (let [c (wc/client (wf/handler :options opts))
                       resp (wc/login! c "alice" "alice-pw")
                       v (get-in resp [:headers "Set-Cookie"])]
                   (str/join "\n" (if (string? v) [v] v))))
        dflt (cookie {})]
    (is (str/includes? dflt "HttpOnly"))
    (is (re-find #"(?i)SameSite=Lax" dflt))
    (is (not (str/includes? dflt "Secure")))
    (is (str/includes? (cookie {:secure-cookies? true}) "Secure"))))

(deftest test-session-valid?
  (let [h 3600000
        user {:user/username "alice"}]
    (is (web-auth/session-valid? {:username "alice"
                                  :issued-at 1000} user 2000 (* 8 h)))
    (testing "a cookie issued before sessions carried :issued-at"
      (is (not (web-auth/session-valid? {:username "alice"} user 2000 (* 8 h)))))
    (testing "older than the max age"
      (is (not (web-auth/session-valid? {:username "alice"
                                         :issued-at 0} user (* 8 h) (* 8 h)))))
    (testing "issued before the user's last revocation"
      (let [revoked (assoc user :user/sessions-valid-after (java.util.Date. 1000))]
        (is (not (web-auth/session-valid? {:username "alice"
                                           :issued-at 1000} revoked 2000 (* 8 h))))
        (is (web-auth/session-valid? {:username "alice"
                                      :issued-at 1001} revoked 2000 (* 8 h)))))))

(deftest test-logout-revokes-other-devices
  (let [a (wf/logged-in "alice")
        b (wf/logged-in "alice")]
    (is (= 200 (:status (wc/request! b :get "/"))))
    (Thread/sleep 5)
    (wc/post! a "/logout" {})
    (is (= 302 (:status (wc/request! b :get "/"))))))

(deftest test-new-password-revokes-sessions
  (let [c (wf/logged-in "alice")]
    (Thread/sleep 5)
    (users/set-password! wf/*app* "alice" "new-password")
    (users/revoke-sessions! wf/*app* "alice")
    (is (= 302 (:status (wc/request! c :get "/"))))
    (testing "a fresh login works"
      (Thread/sleep 5)
      (wc/login! c "alice" "new-password")
      (is (= 200 (:status (wc/request! c :get "/")))))))

(deftest test-session-expires
  (let [c (wf/logged-in "alice" :options {:session-max-age-ms 200})]
    (is (= 200 (:status (wc/request! c :get "/"))))
    (Thread/sleep 250)
    (is (= 302 (:status (wc/request! c :get "/"))))))

(deftest test-cli-passwd-ends-web-sessions
  ;; the bb user:passwd path end to end: CLI command → web session gone
  (let [c (wf/logged-in "alice")]
    (is (= 200 (:status (wc/request! c :get "/"))))
    (Thread/sleep 5)
    (cli/run wf/*app* "user:passwd" (cli/parse-args ["alice"])
             :password-fn (constantly "brand-new-pw"))
    (is (= 302 (:status (wc/request! c :get "/"))))))
