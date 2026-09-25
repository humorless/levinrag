(ns replware.levinrag.web.auth
  "Session login for the web UI (SPEC.md §13). The session holds only the
   username; the principal is re-read from app.dtlv on every request so
   group and admin changes apply at once."
  (:require [datalevin.core :as d]
            [replware.levinrag.auth.users :as users]
            [replware.levinrag.web.layout :as layout]
            [reitit-extras.core :as reitit-extras]
            [ring.util.codec :as codec]
            [ring.util.response :as response]))

(defn safe-next
  "`path` when it is a same-site path, else \"/\". Browsers drop tab/CR/LF
   inside URLs and treat backslash as slash, so any control character or
   backslash is rejected, not only a `//` or `/\\` prefix."
  [path]
  (if (and (string? path)
           (re-matches #"/(?![/\\])[^\x00-\x1f\x7f\\]*" path))
    path
    "/"))

(defn- request-path [{:keys [uri query-string]}]
  (cond-> uri (seq query-string) (str "?" query-string)))

(defn session-valid?
  "The session was issued less than `max-age-ms` before `now-ms` and
   after the user's last revocation (:user/sessions-valid-after).
   Sessions without :issued-at predate expiry and are rejected."
  [{:keys [issued-at]} user now-ms max-age-ms]
  (boolean
    (and (int? issued-at)
         (< (- now-ms issued-at) max-age-ms)
         (if-let [^java.util.Date after (:user/sessions-valid-after user)]
           (> issued-at (.getTime after))
           true))))

(defn- max-age-ms [options]
  (or (:session-max-age-ms options)
      (* 3600000 (:session-max-age-hours options 8))))

(defn wrap-session-auth
  "Adds :principal from the session user when the session is still
   valid (session-valid?); otherwise redirects to /login (HTMX requests
   get HX-Redirect)."
  [handler]
  (fn [request]
    (let [app-conn (get-in request [:context :app-conn])
          session (:session request)
          user (some->> (:username session) (users/find-user (d/db app-conn)))
          principal (when (and user
                               (session-valid? session user (System/currentTimeMillis)
                                               (max-age-ms (get-in request [:context :options]))))
                      (users/principal user))]
      (cond
        principal (handler (assoc request :principal principal))
        (get-in request [:headers "hx-request"]) {:status 200
                                                  :headers {"HX-Redirect" "/login"}
                                                  :body ""}
        :else (response/redirect (str "/login?next=" (codec/url-encode (request-path request))))))))

(defn wrap-admin
  "404 for anyone but an admin."
  [handler]
  (fn [request]
    (if (get-in request [:principal :admin?])
      (handler request)
      (layout/not-found request))))

(defn- login-form [request next-path error]
  (layout/page request "登入"
               [:div {:class ["mx-auto" "mt-16" "max-w-sm" "rounded-lg" "border" "border-slate-200" "bg-white" "p-6" "shadow-sm"]}
                [:h1 {:class ["mb-4" "text-xl" "font-semibold"]} "登入 LevinRAG"]
                (when error
                  [:p {:class ["mb-4" "rounded" "bg-red-50" "p-2" "text-sm" "text-red-700"]} error])
                [:form {:method "post"
                        :action "/login"
                        :class ["space-y-4"]}
                 (layout/csrf-field)
                 [:input {:type "hidden"
                          :name "next"
                          :value (safe-next next-path)}]
                 [:label {:class ["block" "text-sm"]} "帳號"
                  [:input {:name "username"
                           :autocomplete "username"
                           :required true
                           :class ["mt-1" "w-full" "rounded" "border" "border-slate-300" "px-3" "py-2"]}]]
                 [:label {:class ["block" "text-sm"]} "密碼"
                  [:input {:name "password"
                           :type "password"
                           :autocomplete "current-password"
                           :required true
                           :class ["mt-1" "w-full" "rounded" "border" "border-slate-300" "px-3" "py-2"]}]]
                 [:button {:type "submit"
                           :class ["w-full" "rounded" "bg-slate-900" "py-2" "text-white" "hover:bg-slate-800"]} "登入"]]]))

(defn login-page [request]
  (reitit-extras/render-html (login-form request (get-in request [:query-params "next"]) nil)))

(defn login! [{:keys [form-params context]
               :as request}]
  (let [{:strs [username password]
         next-path "next"} form-params]
    (if-let [p (and (seq username) (seq password)
                    (users/authenticate (d/db (:app-conn context)) username password))]
      (assoc (response/redirect (safe-next next-path)) :session {:username (:username p)
                                                                 :issued-at (System/currentTimeMillis)})
      (reitit-extras/render-html (login-form request next-path "帳號或密碼錯誤")))))

(defn logout!
  "Clear this session and revoke the user's other sessions too: the
   cookie is the whole session store, so a copied cookie can only be
   ended per user."
  [{:keys [session context]}]
  (when-let [u (:username session)]
    (when (users/find-user (d/db (:app-conn context)) u)
      (users/revoke-sessions! (:app-conn context) u)))
  (assoc (response/redirect "/login") :session nil))
