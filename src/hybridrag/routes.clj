(ns hybridrag.routes
  (:require [hybridrag.handlers :as handlers]
            [ring.util.response :as response]))

(def routes
  [["/" {:name ::home
         :get {:handler handlers/home-handler}
         :responses {200 {:body string?}}}]
   ["/health" {:name ::health-check
               :get {:handler (fn [_] (response/response "OK"))}}]])
