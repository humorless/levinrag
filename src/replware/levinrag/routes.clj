(ns replware.levinrag.routes
  (:require [replware.levinrag.api.ask :as ask]
            [replware.levinrag.api.docs :as api-docs]
            [replware.levinrag.api.ingest :as api-ingest]
            [replware.levinrag.api.search :as search]
            [replware.levinrag.api.traces :as api-traces]
            [replware.levinrag.auth.middleware :as auth]
            [replware.levinrag.handlers :as handlers]
            [replware.levinrag.web.admin :as web-admin]
            [replware.levinrag.web.ask :as web-ask]
            [replware.levinrag.web.auth :as web-auth]
            [replware.levinrag.web.docs :as web-docs]
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
           :get {:handler web-admin/page}}]
      ["/ingest" {:name ::admin-ingest
                  :post {:handler web-admin/start-ingest}}]
      ["/ingest/status" {:name ::admin-ingest-status
                         :get {:handler web-admin/ingest-status}}]
      ["/traces/:id" {:name ::admin-trace
                      :get {:handler web-admin/trace-page}}]]]]
   ["/api/v1"
    ["/health" {:name ::health-check
                :get {:handler handlers/health-handler}}]
    ["/health/live" {:name ::health-live
                     :get {:handler handlers/live-handler}}]
    ["" {:middleware [auth/wrap-bearer-auth]}
     ["/search" {:name ::search
                 :post {:handler search/handler
                        :parameters {:body search/request-schema}}}]
     ["/ask" {:name ::ask
              :post {:handler ask/handler
                     :parameters {:body ask/request-schema}}}]
     ["/docs/*path" {:name ::api-docs
                     :get {:handler api-docs/handler}}]
     ["/ingest" {:name ::api-ingest
                 :post {:handler api-ingest/start}}]
     ["/ingest/:job_id" {:name ::api-ingest-status
                         :get {:handler api-ingest/status}}]
     ["/traces/:id" {:name ::api-trace
                     :get {:handler api-traces/handler}}]]]])
