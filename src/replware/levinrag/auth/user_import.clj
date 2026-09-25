(ns replware.levinrag.auth.user-import
  "`bb user:import <users.edn>` (SPEC.md §13): bring the users listed in
   a file (the eval/users.edn format) in line with it. Listed users are
   created or updated (groups, admin); users not in the file are only
   reported, never deleted. New users get a random password shown once;
   existing passwords are not touched. The file never holds passwords."
  (:require [buddy.hashers :as hashers]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [datalevin.core :as d]
            [replware.levinrag.auth.users :as users]))

(def ^:private user-keys #{:groups :admin?})

(defn- user-problems [u m]
  (let [who (pr-str u)]
    (cond
      (not (and (string? u) (re-matches #"\S+" u)))
      [(str who "：使用者名稱必須是不含空白的字串")]

      (not (map? m))
      [(str who "：設定必須是 map，例如 {:groups #{\"all\"} :admin? false}")]

      :else
      (cond-> []
        (seq (remove user-keys (keys m)))
        (conj (str who "：不認得的 key " (str/join " " (remove user-keys (keys m)))
                   "（可用：:groups :admin?）"))

        (not (and (coll? (:groups m)) (not (map? (:groups m))) (every? string? (:groups m))))
        (conj (str who "：:groups 必須是字串的集合，例如 #{\"all\" \"hr\"}"))

        (not (boolean? (:admin? m false)))
        (conj (str who "：:admin? 必須是 true 或 false"))))))

(defn parse
  "username → {:groups #{..} :admin? bool} from the text of a users file.
   Every problem is reported at once; nothing is written when any exists."
  [text]
  (let [v (try (edn/read-string text)
               (catch Exception e
                 (throw (ex-info (str "使用者檔無法解析：" (ex-message e)) {}))))]
    (when-not (map? v)
      (throw (ex-info "使用者檔必須是一個 map：{\"alice\" {:groups #{\"all\"} :admin? false} ...}" {})))
    (when-let [ps (seq (mapcat (fn [[u m]] (user-problems u m)) v))]
      (throw (ex-info (str "使用者檔有錯誤，未寫入任何資料：\n  " (str/join "\n  " ps)) {:problems (vec ps)})))
    (into {} (map (fn [[u m]] [u {:groups (set (:groups m))
                                  :admin? (boolean (:admin? m))}]))
          v)))

(defn- random-password []
  (let [b (byte-array 12)]
    (.nextBytes (java.security.SecureRandom.) b)
    (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) b)))

(defn- state [p] {:groups (vec (sort (:groups p)))
                  :admin? (:admin? p)})

(defn import!
  "Apply `wanted` (from parse) to app.dtlv in one transaction, or only plan
   it with :dry-run?. Returns {:users [{:username :action :before :after
   :password}] :not-in-file [..]}, :action one of :create :update
   :unchanged; :password only for users created by a real run."
  [conn wanted {:keys [dry-run?]}]
  (let [db (d/db conn)
        plan (vec (for [[u want] (sort-by key wanted)
                        :let [user (users/find-user db u)
                              before (some-> user users/principal state)
                              after (state want)]]
                    {:username u
                     :action (cond (nil? user) :create
                                   (= before after) :unchanged
                                   :else :update)
                     :before before
                     :after after
                     :eid (:db/id user)
                     :old-groups (:user/groups user)}))
        plan (if dry-run?
               plan
               (mapv #(cond-> % (= :create (:action %)) (assoc :password (random-password))) plan))
        tx (vec (mapcat (fn [{:keys [username action after eid old-groups password]}]
                          (case action
                            :create [(cond-> {:user/username username
                                              :user/display-name username
                                              :user/admin? (:admin? after)
                                              :user/password-hash (hashers/derive password)}
                                       (seq (:groups after)) (assoc :user/groups (:groups after)))]
                            :update (concat (for [g old-groups] [:db/retract eid :user/groups g])
                                            (for [g (:groups after)] [:db/add eid :user/groups g])
                                            [[:db/add eid :user/admin? (:admin? after)]])
                            :unchanged []))
                        (when-not dry-run? plan)))]
    (when (seq tx) (d/transact! conn tx))
    {:users (mapv #(dissoc % :eid :old-groups) plan)
     :not-in-file (vec (sort (remove (set (keys wanted))
                                     (d/q '[:find [?u ...] :where [_ :user/username ?u]] db))))}))

(defn- fmt [{:keys [groups admin?]}]
  (str "群組 " (if (seq groups) (str/join "," groups) "（無）") (when admin? "，admin")))

(defn summary
  "Text for the CLI."
  [{:keys [users not-in-file]} dry-run?]
  (str/join
    "\n"
    (concat
      (when dry-run? ["（試跑，未寫入任何資料）"])
      (for [{:keys [username action before after password]} users]
        (case action
          :create (str "新建 " username "：" (fmt after)
                       (cond password (str "；密碼：" password)
                             dry-run? "；將產生隨機密碼"))
          :update (str "更新 " username "：" (fmt before) " → " (fmt after))
          :unchanged (str "未變更 " username)))
      (when (seq not-in-file)
        [(str "不在檔案中（未更動）：" (str/join ", " not-in-file))])
      (when (some :password users)
        ["密碼只顯示這一次，請現在記下；之後可用 bb user:passwd <名稱> 更改。"]))))
