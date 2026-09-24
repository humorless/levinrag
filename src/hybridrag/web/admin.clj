(ns hybridrag.web.admin
  "Admin page (SPEC.md §12)."
  (:require [hybridrag.web.layout :as layout]))

(defn page [request]
  (layout/render request "管理"
                 [:h1 {:class ["text-2xl" "font-semibold"]} "管理"]))
