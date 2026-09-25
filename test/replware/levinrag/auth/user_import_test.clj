(ns replware.levinrag.auth.user-import-test
  "`bb user:import` (SPEC.md §13): the file is the source of truth for the
   users it lists; users it omits are reported, never deleted."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [replware.levinrag.auth.cli :as cli]
            [replware.levinrag.auth.user-import :as ui]
            [replware.levinrag.auth.users :as users]
            [replware.levinrag.tmp :as tmp]))

(def users-edn
  "{\"alice\" {:groups #{\"all\" \"hr\"} :admin? false}
    \"bob\"   {:groups [\"all\" \"engineering\"]}
    \"admin\" {:groups #{} :admin? true}}")

(defn- principal [conn u] (some-> (users/find-user (d/db conn) u) users/principal))

(deftest test-parse
  (is (= {"alice" {:groups #{"all" "hr"}
                   :admin? false}
          "bob" {:groups #{"all" "engineering"}
                 :admin? false}
          "admin" {:groups #{}
                   :admin? true}}
         (ui/parse users-edn)))
  (testing "eval/users.edn is a valid import file (one file for eval and the server)"
    (is (= 4 (count (ui/parse (slurp "eval/users.edn"))))))
  (testing "every problem is reported at once, by user name; nothing is guessed"
    (let [e (try (ui/parse "{\"alice\" {:groups \"hr\"}
                            \"bob\" {:group [\"all\"]}
                            \"carol\" {:groups [\"all\" 1]}
                            \"dave\" {:admin? \"yes\"}
                            \"\" {:groups []}
                            \"eve x\" {:groups []}
                            :frank {:groups []}}")
                 (catch clojure.lang.ExceptionInfo e (ex-message e)))]
      (doseq [s ["alice" "bob" ":group" "carol" "dave" ":admin?" "eve x" ":frank"]]
        (is (str/includes? e s) s))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"map" (ui/parse "[\"alice\"]")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"無法解析" (ui/parse (str (char 0x7b) "\"alice\"")))))

(deftest test-import
  (tmp/with-app-conn
    (fn [conn]
      (users/create-user! conn "bob" {:groups ["all"]})
      (users/set-password! conn "bob" "bob-password")
      (users/create-user! conn "zoe" {:groups ["all"]})
      (testing "dry run writes nothing and shows no passwords"
        (let [r (ui/import! conn (ui/parse users-edn) {:dry-run? true})]
          (is (= {"alice" :create
                  "admin" :create
                  "bob" :update} (into {} (map (juxt :username :action)) (:users r))))
          (is (= ["zoe"] (:not-in-file r)))
          (is (every? nil? (map :password (:users r)))))
        (is (nil? (principal conn "alice"))))
      (testing "import: create with a one-time random password, update groups and admin, keep passwords"
        (let [r (ui/import! conn (ui/parse users-edn) {})
              by (into {} (map (juxt :username identity)) (:users r))]
          (is (= {:username "alice"
                  :groups #{"all" "hr"}
                  :admin? false} (principal conn "alice")))
          (is (= {:username "admin"
                  :groups #{}
                  :admin? true} (principal conn "admin")))
          (is (= #{"all" "engineering"} (:groups (principal conn "bob"))))
          (is (<= 16 (count (:password (by "alice")))))
          (is (not= (:password (by "alice")) (:password (by "admin"))))
          (is (= "alice" (:username (users/authenticate (d/db conn) "alice" (:password (by "alice"))))))
          (is (nil? (:password (by "bob"))) "existing users keep their password")
          (is (= "bob" (:username (users/authenticate (d/db conn) "bob" "bob-password"))))
          (is (= {:groups ["all"]
                  :admin? false} (:before (by "bob"))))
          (is (= ["zoe"] (:not-in-file r)))
          (is (some? (principal conn "zoe")) "users not in the file are never deleted")))
      (testing "running it again changes nothing"
        (is (= #{:unchanged} (set (map :action (:users (ui/import! conn (ui/parse users-edn) {})))))))
      (testing "demotion and group removal"
        (ui/import! conn (ui/parse "{\"admin\" {:groups [\"ops\"]} \"alice\" {:groups []}}") {})
        (is (= {:username "admin"
                :groups #{"ops"}
                :admin? false} (principal conn "admin")))
        (is (= #{} (:groups (principal conn "alice"))))))))

(deftest test-cli
  (tmp/with-app-conn
    (fn [conn]
      (let [dir (tmp/dir "user-import")
            f (str dir "/users.edn")
            run #(cli/run conn "user:import" (cli/parse-args %))]
        (try
          (spit f users-edn)
          (let [out (run [f "--dry-run"])]
            (is (str/includes? out "試跑"))
            (is (str/includes? out "新建 alice")))
          (is (nil? (principal conn "alice")) "--dry-run is a switch: it does not swallow the file argument")
          (let [out (run [f])]
            (is (str/includes? out "新建 alice"))
            (is (str/includes? out "只顯示這一次")))
          (is (str/includes? (run [f]) "未變更 alice"))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"找不到" (run ["/nonexistent.edn"])))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"用法" (run [])))
          (finally (tmp/delete-tree! dir)))))))
