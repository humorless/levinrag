(ns hybridrag.routes
  (:require [hybridrag.handlers :as handlers]))

(def routes
  [["/" {:name ::home
         :get {:handler handlers/home-handler}
         :responses {200 {:body string?}}}]
   ["/api/v1/health" {:name ::health-check
                      :get {:handler handlers/health-handler}}]])
