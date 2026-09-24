(ns hybridrag.auth.auth-test
  "Users, API tokens, bearer middleware and the user/token CLI (SPEC.md §13)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [hybridrag.auth.cli :as cli]
            [hybridrag.auth.middleware :as mw]
            [hybridrag.auth.token :as token]
            [hybridrag.auth.users :as users]
            [hybridrag.tmp :as tmp]))

(deftest test-users
  (tmp/with-app-conn
    (fn [conn]
      (is (= {:username "alice"
              :groups #{"all" "hr"}
              :admin? false}
             (users/create-user! conn "alice" {:groups ["all" "hr"]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"已存在"
                            (users/create-user! conn "alice" {})))
      (testing "groups are replaced, not merged"
        (is (= #{"all" "finance"} (:groups (users/set-groups! conn "alice" ["all" "finance"])))))
      (testing "passwords: hashed, verified"
        (is (nil? (users/authenticate (d/db conn) "alice" "whatever")) "no password yet")
        (users/set-password! conn "alice" "correct horse")
        (is (not= "correct horse" (:user/password-hash (users/find-user (d/db conn) "alice"))))
        (is (= "alice" (:username (users/authenticate (d/db conn) "alice" "correct horse"))))
        (is (nil? (users/authenticate (d/db conn) "alice" "wrong")))
        (is (nil? (users/authenticate (d/db conn) "nobody" "correct horse"))))
      (is (true? (:admin? (users/create-user! conn "root" {:admin? true}))))
      (is (= #{} (:groups (users/create-user! conn "nogroups" {})))))))

(deftest test-tokens
  (tmp/with-app-conn
    (fn [conn]
      (users/create-user! conn "bob" {:groups ["all" "engineering"]})
      (let [{t :token
             p :prefix} (token/create-token! conn "bob" "cli")]
        (is (<= 43 (count t)) "32 random bytes, base64url")
        (is (= p (subs t 0 8)))
        (testing "only the hash is stored"
          (is (empty? (d/q '[:find ?e :in $ ?t :where [?e _ ?t]] (d/db conn) t))))
        (is (= {:username "bob"
                :groups #{"all" "engineering"}
                :admin? false}
               (token/principal-for-token (d/db conn) t)))
        (testing "principal follows current groups"
          (users/set-groups! conn "bob" ["all"])
          (is (= #{"all"} (:groups (token/principal-for-token (d/db conn) t)))))
        (is (nil? (token/principal-for-token (d/db conn) "not-a-token")))
        (is (nil? (token/principal-for-token (d/db conn) "")))
        (is (= "cli" (token/revoke-token! conn p)))
        (is (nil? (token/principal-for-token (d/db conn) t)) "revoked")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"找不到"
                              (token/revoke-token! conn p)))))))

(deftest test-bearer-middleware
  (tmp/with-app-conn
    (fn [conn]
      (users/create-user! conn "carol" {:groups ["all" "finance"]})
      (let [{t :token} (token/create-token! conn "carol" nil)
            handler (mw/wrap-bearer-auth (fn [req] {:status 200
                                                    :body (:principal req)}))
            call (fn [headers] (handler {:context {:app-conn conn}
                                         :headers headers}))]
        (is (= {:status 200
                :body {:username "carol"
                       :groups #{"all" "finance"}
                       :admin? false}}
               (call {"authorization" (str "Bearer " t)})))
        (is (= 200 (:status (call {"authorization" (str "bearer " t)}))) "scheme is case-insensitive")
        (doseq [h [{} {"authorization" "Bearer "} {"authorization" "Bearer nope"}
                   {"authorization" (str "Basic " t)} {"authorization" t}]]
          (let [resp (call h)]
            (is (= 401 (:status resp)) (pr-str h))
            (is (= "unauthorized" (get-in resp [:body :error :code])))))))))

(deftest test-cli
  (tmp/with-app-conn
    (fn [conn]
      (let [run (fn [& args] (cli/run conn (first args) (cli/parse-args (rest args))
                                      :password-fn (let [pws (atom ["s3cret-pass" "s3cret-pass"])]
                                                     (fn [_] (let [p (first @pws)] (swap! pws rest) p)))))]
        (is (str/includes? (run "user:create" "alice" "--groups" "all, hr") "all,hr"))
        (is (str/includes? (run "user:create" "admin" "--admin") "admin"))
        (is (:admin? (users/principal (users/find-user (d/db conn) "admin"))))
        (is (str/includes? (run "user:groups" "alice" "all,hr,finance") "all,finance,hr"))
        (is (str/includes? (run "user:passwd" "alice") "已更新"))
        (is (some? (users/authenticate (d/db conn) "alice" "s3cret-pass")))
        (let [out (run "token:create" "alice" "--label" "cli")
              t (second (str/split-lines out))
              prefix (subs t 0 8)]
          (is (= "alice" (:username (token/principal-for-token (d/db conn) t))))
          (is (str/includes? out prefix))
          (is (str/includes? (run "token:revoke" prefix) "cli")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"未知的指令" (run "user:delete" "x")))
        (testing "mismatched passwords are rejected"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"不一致"
                                (cli/run conn "user:passwd" (cli/parse-args ["alice"])
                                         :password-fn (let [n (atom 0)] (fn [_] (str "password-" (swap! n inc))))))))))))
