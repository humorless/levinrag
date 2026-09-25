(ns browser-server
  "Test server for `bb browser-check`: the sample corpus in a temp index
   (stub embedder), a temp app.dtlv with alice/admin, stub rerank and
   chat. Needs the :test alias (fixtures). Prints READY when listening.

   clojure -M:jvm-opts:test:dev -m browser-server [port]"
  (:require [datalevin.core :as d]
            [replware.levinrag.auth.users :as users]
            [replware.levinrag.db.index-conn :as index-conn]
            [replware.levinrag.db.schema :as schema]
            [replware.levinrag.fixtures :as fx]
            [replware.levinrag.ingest.job :as job]
            [replware.levinrag.ingest.runner :as runner]
            [replware.levinrag.retrieval.datalevin :as rd]
            [replware.levinrag.server :as server]
            [replware.levinrag.tmp :as tmp]
            [ring.adapter.jetty :as jetty]))

(defn -main [& [port]]
  (let [port (parse-long (or port "8765"))
        idx (tmp/dir "browser-idx")
        app-dir (tmp/dir "browser-app")
        data (tmp/dir "browser-data")
        index (index-conn/open idx fx/dims)
        app (d/get-conn app-dir schema/app-schema)]
    (job/ingest! index {:corpus-dir "corpus-sample"
                        :embed-fn fx/hash-embed})
    (doseq [[u opts] {"alice" {:groups ["all" "hr"]}
                      "admin" {:admin? true}}]
      (users/create-user! app u opts)
      (users/set-password! app u (str u "-pw")))
    (jetty/run-jetty
      (server/ring-handler
        {:options {:session-secret-key "browser-check-key"}
         :corpus-dir "corpus-sample"
         :index-conn index
         :app-conn app
         :ingest (runner/make {:index-conn index
                               :corpus-dir "corpus-sample"
                               :data-dir data
                               :root-read-groups []
                               :embed-fn fx/hash-embed})
         :search {:retriever (rd/retriever index fx/hash-embed)
                  :rerank-fn (fn [_ docs _] (vec (map-indexed (fn [i _] {:index i
                                                                         :relevance-score (- (double i))}) docs)))
                  :chat-fn (fn [_ _] {:model "stub-chat"
                                      :choices [{:message {:content "特休依年資計算[1]。"}
                                                 :finish_reason "stop"}]})
                  :opts {}}})
      {:port port
       :host "127.0.0.1"
       :join? false})
    (println "READY" port)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(do (d/close index) (d/close app)
                                    (doseq [dir [idx app-dir data]] (tmp/delete-tree! dir)))))))
