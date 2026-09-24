(ns hybridrag.web.auth
  "Session login for the web UI (SPEC.md §13). The session holds only the
   username; the principal is re-read from app.dtlv on every request so
   group and admin changes apply at once."
  (:require [clojure.string :as str]
            [datalevin.core :as d]
            [hybridrag.auth.users :as users]
            [hybridrag.web.layout :as layout]
            [reitit-extras.core :as reitit-extras]
            [ring.util.codec :as codec]
            [ring.util.response :as response]))

(defn safe-next
  "`path` when it is a same-site path, else \"/\"."
  [path]
  (if (and (string? path)
           (str/starts-with? path "/")
           (not (str/starts-with? path "//"))
           (not (str/starts-with? path "/\\")))
    path
    "/"))

(defn- request-path [{:keys [uri query-string]}]
  (cond-> uri (seq query-string) (str "?" query-string)))

(defn wrap-session-auth
  "Adds :principal from the session user; otherwise redirects to /login
   (HTMX requests get HX-Redirect)."
  [handler]
  (fn [request]
    (let [app-conn (get-in request [:context :app-conn])
          principal (some->> (get-in request [:session :username])
                             (users/find-user (d/db app-conn))
                             users/principal)]
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
                [:h1 {:class ["mb-4" "text-xl" "font-semibold"]} "登入 levinrag"]
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
      (assoc (response/redirect (safe-next next-path)) :session {:username (:username p)})
      (reitit-extras/render-html (login-form request next-path "帳號或密碼錯誤")))))

(defn logout! [_]
  (assoc (response/redirect "/login") :session nil))
