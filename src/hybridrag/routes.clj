(ns hybridrag.routes
  (:require [hybridrag.api.search :as search]
            [hybridrag.auth.middleware :as auth]
            [hybridrag.handlers :as handlers]
            [ring.middleware.anti-forgery :as anti-forgery]))

;; CSRF protection applies to the cookie-authenticated web routes only.
;; /api/v1 authenticates with bearer tokens, which browsers never attach
;; on their own, so CSRF does not apply there (docs/decisions.md).
(def routes
  [["" {:middleware [anti-forgery/wrap-anti-forgery]}
    ["/" {:name ::home
          :get {:handler handlers/home-handler}
          :responses {200 {:body string?}}}]]
   ["/api/v1"
    ["/health" {:name ::health-check
                :get {:handler handlers/health-handler}}]
    ["" {:middleware [auth/wrap-bearer-auth]}
     ["/search" {:name ::search
                 :post {:handler search/handler
                        :parameters {:body search/request-schema}}}]]]])
