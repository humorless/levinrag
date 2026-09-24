(ns hybridrag.routes
  (:require [hybridrag.api.ask :as ask]
            [hybridrag.api.docs :as api-docs]
            [hybridrag.api.search :as search]
            [hybridrag.auth.middleware :as auth]
            [hybridrag.handlers :as handlers]
            [hybridrag.web.admin :as web-admin]
            [hybridrag.web.ask :as web-ask]
            [hybridrag.web.auth :as web-auth]
            [hybridrag.web.docs :as web-docs]
            [ring.middleware.anti-forgery :as anti-forgery]))

;; CSRF protection applies to the cookie-authenticated web routes only.
;; /api/v1 authenticates with bearer tokens, which browsers never attach
;; on their own, so CSRF does not apply there (docs/decisions.md).
(def routes
  [["" {:middleware [anti-forgery/wrap-anti-forgery]}
    ["/login" {:name ::login
               :get {:handler web-auth/login-page}
               :post {:handler web-auth/login!}}]
    ["" {:middleware [web-auth/wrap-session-auth]}
     ["/" {:name ::home
           :get {:handler web-ask/page}}]
     ["/ask" {:name ::web-ask
              :post {:handler web-ask/ask}}]
     ["/docs/*path" {:name ::web-docs
                     :get {:handler web-docs/page}}]
     ["/logout" {:name ::logout
                 :post {:handler web-auth/logout!}}]
     ["/admin" {:middleware [web-auth/wrap-admin]}
      ["" {:name ::admin
           :get {:handler web-admin/page}}]]]]
   ["/api/v1"
    ["/health" {:name ::health-check
                :get {:handler handlers/health-handler}}]
    ["" {:middleware [auth/wrap-bearer-auth]}
     ["/search" {:name ::search
                 :post {:handler search/handler
                        :parameters {:body search/request-schema}}}]
     ["/ask" {:name ::ask
              :post {:handler ask/handler
                     :parameters {:body ask/request-schema}}}]
     ["/docs/*path" {:name ::api-docs
                     :get {:handler api-docs/handler}}]]]])
