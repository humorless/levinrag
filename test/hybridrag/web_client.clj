(ns hybridrag.web-client
  "Cookie-keeping client over a Ring handler for web-route tests: follows
   nothing, remembers Set-Cookie and the last page's CSRF token."
  (:require [clojure.string :as str]
            [ring.util.codec :as codec]))

(defn client [handler] (atom {:handler handler
                              :cookies {}
                              :csrf nil}))

(defn- set-cookies [resp]
  (for [h (let [v (get-in resp [:headers "Set-Cookie"])] (if (string? v) [v] v))
        :let [[kv] (str/split h #";")
              [k v] (str/split kv #"=" 2)]]
    [k v]))

(defn request!
  "Send `method` `uri` (may carry ?query) through the handler; :form is a
   map of form fields, :headers extra (lower-case) request headers.
   Returns the response with :body as a string."
  [c method uri & {:keys [form headers]}]
  (let [{:keys [handler cookies]} @c
        [path query] (str/split uri #"\?" 2)
        body (when form (codec/form-encode form))
        resp (handler (cond-> {:request-method method
                               :uri path
                               :query-string query
                               :scheme :http
                               :server-name "localhost"
                               :server-port 80
                               :headers (merge {"accept" "text/html"}
                                               (when (seq cookies)
                                                 {"cookie" (str/join "; " (map (fn [[k v]] (str k "=" v)) cookies))})
                                               (when form {"content-type" "application/x-www-form-urlencoded"})
                                               headers)}
                        body (assoc :body (java.io.ByteArrayInputStream. (.getBytes ^String body "UTF-8")))))
        resp (update resp :body #(cond (string? %) % (nil? %) "" :else (slurp %)))]
    (swap! c update :cookies into (set-cookies resp))
    (when-let [t (some->> (re-find #"<meta[^>]*name=\"csrf-token\"[^>]*>" (:body resp))
                          (re-find #"content=\"([^\"]+)\"")
                          second)]
      (swap! c assoc :csrf t))
    resp))

(defn csrf [c] (:csrf @c))

(defn post!
  "POST a form with the current CSRF token."
  [c uri form & {:keys [headers]}]
  (request! c :post uri :form (assoc form "__anti-forgery-token" (csrf c)) :headers headers))

(defn login!
  "GET /login for a token, then POST the credentials (with `next`, if given)."
  ([c user password] (login! c user password nil))
  ([c user password next-path]
   (request! c :get "/login")
   (post! c "/login" (cond-> {"username" user
                              "password" password}
                       next-path (assoc "next" next-path)))))
