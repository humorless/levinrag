;; /ask end-to-end latency through the running server
;; (docs/spikes/llama-cpp-only.md). Needs `bb serve` on :8000 and a token:
;;
;;   bb dev/spikes/ask_latency.clj <token> <out.edn>
;;
;; Asks the first 10 non-ACL questions of eval/questions.edn once each (every
;; question is a new prompt, so there is no warm-up round) with an admin
;; token, so no question is cut short by the ACL, and records wall time,
;; the degraded flags and the start of the answer.
(ns ask-latency
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]))

(def client (http/client {:version :http1.1}))

(let [[token out] *command-line-args*
      qs (->> (edn/read-string (slurp "eval/questions.edn"))
              (filter :expected-docs)
              (take 10))
      rows (vec (for [{:keys [id query]} qs]
                  (let [t0 (System/nanoTime)
                        r (http/post "http://localhost:8000/api/v1/ask"
                                     {:client client
                                      :headers {"Authorization" (str "Bearer " token)
                                                "Content-Type" "application/json"}
                                      :body (json/generate-string {:query query})
                                      :timeout 600000})
                        s (/ (- (System/nanoTime) t0) 1e9)
                        b (json/parse-string (:body r) true)]
                    {:id id
                     :s s
                     :status (:status r)
                     :degraded (:degraded b)
                     :citations (count (:citations b))
                     :answer (subs (str (:answer b)) 0 (min 40 (count (str (:answer b)))))})))
      ss (sort (map :s rows))]
  (spit out (pr-str rows))
  (doseq [r rows] (println (format "%-12s %6.2fs cites=%d %s" (:id r) (:s r) (:citations r) (:answer r))))
  (println (format "p50 %.2fs  p90 %.2fs  (n=%d)" (nth ss (quot (count ss) 2)) (nth ss (int (* 0.9 (dec (count ss))))) (count ss))))
