(ns spikes.retract-readd
  "Backlog item #5 (docs/spikes/retract-readd.md): why `retractEntity` plus
   re-adding the same unique id in ONE transaction fails in Datalevin
   1.1.0 with fulltext \"Document does not exist.\", and what it does
   without fulltext.

  THROWAWAY SCRIPT: disposable Datalevin dirs under the OS temp dir. Run
  from the repo root in a fresh JVM (see gen_synthetic_corpus.clj for why
  load-file):

    clojure -M:jvm-opts -e '(load-file \"dev/spikes/retract_readd.clj\")(spikes.retract-readd/-main)'"
  (:require [datalevin.core :as d]
            [datalevin.storage :as st]))

(defn- conn [schema]
  (d/create-conn (str (java.nio.file.Files/createTempDirectory
                        "retract-readd" (make-array java.nio.file.attribute.FileAttribute 0)))
                 schema))

(def ^:private id-attr
  {:chunk/id {:db/valueType :db.type/string
              :db/unique :db.unique/identity}})

(defn- replace-in-one-tx [c attr]
  (d/transact! c [{:chunk/id "c1" attr "old"}])
  [[:db/retractEntity [:chunk/id "c1"]] {:chunk/id "c1" attr "new"}])

(defn -main [& _]
  (println "1. fulltext attribute, retractEntity + re-add in one tx:")
  (let [c (conn (assoc id-attr :chunk/text {:db/valueType :db.type/string
                                            :db/fulltext true
                                            :db.fulltext/autoDomain true}))
        ops (atom [])
        tx (replace-in-one-tx c :chunk/text)
        orig st/fulltext-index]
    ;; record the fulltext ops Datalevin hands to the search engines
    (alter-var-root #'st/fulltext-index
                    (constantly (fn [engines ft-ds]
                                  (reset! ops (vec (for [res ft-ds
                                                         :let [[kind d] (peek res)]]
                                                     [(nth res 0) kind (vec (take 3 d))])))
                                  (orig engines ft-ds))))
    (println "   result:" (try (d/transact! c tx) :ok (catch Throwable t (ex-message t))))
    (alter-var-root #'st/fulltext-index (constantly orig))
    (println "   fulltext ops:")
    (doseq [op @ops] (println "    " op))
    (println "   entity c1 after:" (d/pull (d/db c) '[*] [:chunk/id "c1"]))
    (d/close c))

  (println "2. plain attribute, same tx:")
  (let [c (conn (assoc id-attr :chunk/plain {:db/valueType :db.type/string}))
        r (d/transact! c (replace-in-one-tx c :chunk/plain))]
    (println "   tx-data:" (mapv (juxt :e :a :v :added) (:tx-data r)))
    (println "   lookup [:chunk/id \"c1\"]:" (d/pull (d/db c) '[*] [:chunk/id "c1"]))
    (println "   entity 1:" (d/pull (d/db c) '[*] 1))
    (d/close c))

  (println "3. control, the same ops in two txs:")
  (let [c (conn (assoc id-attr :chunk/plain {:db/valueType :db.type/string}))
        [retract add] (replace-in-one-tx c :chunk/plain)]
    (d/transact! c [retract])
    (d/transact! c [add])
    (println "   lookup [:chunk/id \"c1\"]:" (d/pull (d/db c) '[*] [:chunk/id "c1"]))
    (d/close c))
  (shutdown-agents))
