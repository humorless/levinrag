(ns replware.levinrag.views
  "Pages rendered outside a route's handler (router fallbacks)."
  (:require [replware.levinrag.web.layout :as layout]))

(defn error-page
  [text]
  (layout/page {} text
               [:div {:class ["mt-40" "text-center"]}
                [:h1 {:class ["text-3xl" "font-semibold"]} text]]))
