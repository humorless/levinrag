(ns hybridrag.web.ask
  "Q&A page (SPEC.md §12)."
  (:require [hybridrag.web.layout :as layout]))

(defn page [request]
  (layout/render request "問答"
                 [:h1 {:class ["text-2xl" "font-semibold"]} "問答"]))
