(ns hybridrag.auth.middleware
  "Bearer-token authentication for /api/v1 (SPEC.md §11, §13)."
  (:require [datalevin.core :as d]
            [hybridrag.auth.token :as token]))

(defn error-response
  "SPEC §11 error body."
  [status code message]
  {:status status
   :body {:error {:code code
                  :message message}}})

(defn- bearer [request]
  (some->> (get-in request [:headers "authorization"])
           (re-find #"(?i)^Bearer\s+(\S+)$")
           second))

(defn wrap-bearer-auth
  "Resolve `Authorization: Bearer <token>` to a principal (:principal on the
   request). Missing or unknown token → 401."
  [handler]
  (fn [request]
    (let [app-conn (get-in request [:context :app-conn])
          principal (some->> (bearer request) (token/principal-for-token (d/db app-conn)))]
      (if principal
        (handler (assoc request :principal principal))
        (error-response 401 "unauthorized" "需要有效的 API token。")))))
