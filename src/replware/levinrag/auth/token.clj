(ns replware.levinrag.auth.token
  "API tokens (SPEC.md §13): 32 random bytes shown once; app.dtlv keeps
   only the sha256 and an 8-char prefix for revocation."
  (:require [datalevin.core :as d]
            [replware.levinrag.auth.users :as users])
  (:import [java.security MessageDigest SecureRandom]
           [java.util Base64]))

(def ^:private prefix-len 8)

(defn sha256-hex [^String s]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256")
                                          (.getBytes s "UTF-8")))))

(defn- random-token []
  (let [bs (byte-array 32)]
    (.nextBytes (SecureRandom.) bs)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs)))

(defn create-token!
  "New token for `username`. Returns {:token <plaintext> :prefix ..}; the
   plaintext is not stored anywhere."
  [conn username label]
  (let [user (or (users/find-user (d/db conn) username)
                 (throw (ex-info (str "找不到使用者：" username) {:username username})))
        token (random-token)
        prefix (subs token 0 prefix-len)]
    (d/transact! conn [{:token/hash (sha256-hex token)
                        :token/prefix prefix
                        :token/user (:db/id user)
                        :token/label (or label "")
                        :token/created-at (java.util.Date.)}])
    {:token token
     :prefix prefix}))

(defn revoke-token!
  "Delete the token whose prefix is `prefix`. Throws when none or more
   than one token matches. Returns the revoked token's label."
  [conn prefix]
  (let [hits (d/q '[:find ?t ?label :in $ ?p
                    :where [?t :token/prefix ?p] [?t :token/label ?label]]
                  (d/db conn) prefix)]
    (case (count hits)
      0 (throw (ex-info (str "找不到 token：" prefix) {:prefix prefix}))
      1 (let [[[t label]] (seq hits)]
          (d/transact! conn [[:db/retractEntity t]])
          label)
      (throw (ex-info (str "有多個 token 符合：" prefix) {:prefix prefix})))))

(defn principal-for-token
  "Principal of the token's user, or nil for an unknown token."
  [db token]
  (when (seq token)
    (when-let [t (d/entid db [:token/hash (sha256-hex token)])]
      (some->> (:token/user (d/pull db [{:token/user [:db/id]}] t))
               :db/id
               (d/pull db [:user/username :user/groups :user/admin?])
               users/principal))))
