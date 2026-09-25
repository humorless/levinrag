(ns replware.levinrag.auth.users
  "User records in app.dtlv (SPEC.md §13) and the principal they map to.

   The principal {:username :groups #{..} :admin? bool} is the only
   identity shape the rest of the system sees; every authentication path
   (password, API token, later OIDC) resolves to it."
  (:require [buddy.hashers :as hashers]
            [datalevin.core :as d]))

(def ^:private user-pull
  [:db/id :user/username :user/display-name :user/password-hash :user/groups :user/admin?
   :user/sessions-valid-after])

(defn find-user
  "User entity map for `username`, or nil."
  [db username]
  (when-let [eid (d/entid db [:user/username username])]
    (d/pull db user-pull eid)))

(defn principal
  "Principal for a user entity map (as returned by find-user)."
  [user]
  {:username (:user/username user)
   :groups (set (:user/groups user))
   :admin? (boolean (:user/admin? user))})

(defn create-user!
  "Create a user with no password. Throws if the username exists."
  [conn username {:keys [groups admin? display-name]}]
  (when (find-user (d/db conn) username)
    (throw (ex-info (str "使用者已存在：" username) {:username username})))
  (d/transact! conn [(cond-> {:user/username username
                              :user/display-name (or display-name username)
                              :user/admin? (boolean admin?)}
                       (seq groups) (assoc :user/groups (vec groups)))])
  (principal (find-user (d/db conn) username)))

(defn- existing-user! [db username]
  (or (find-user db username)
      (throw (ex-info (str "找不到使用者：" username) {:username username}))))

(defn set-groups!
  "Replace the user's groups."
  [conn username groups]
  (let [user (existing-user! (d/db conn) username)
        id (:db/id user)]
    (d/transact! conn (vec (concat (for [g (:user/groups user)] [:db/retract id :user/groups g])
                                   (for [g (distinct groups)] [:db/add id :user/groups g]))))
    (principal (find-user (d/db conn) username))))

(defn set-password!
  "Hash and store a new password."
  [conn username password]
  (let [{:keys [db/id]} (existing-user! (d/db conn) username)]
    (d/transact! conn [[:db/add id :user/password-hash (hashers/derive password)]])
    nil))

(defn revoke-sessions!
  "End every web session of `username` issued up to now (all devices)."
  [conn username]
  (let [{:keys [db/id]} (existing-user! (d/db conn) username)]
    (d/transact! conn [[:db/add id :user/sessions-valid-after (java.util.Date.)]])
    nil))

(defn authenticate
  "Principal when `password` matches the user's stored hash, else nil."
  [db username password]
  (when-let [user (find-user db username)]
    (when-let [h (:user/password-hash user)]
      (when (:valid (hashers/verify password h))
        (principal user)))))
