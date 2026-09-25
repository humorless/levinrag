(ns hybridrag.health-test
  "GET /api/v1/health/live (DBs only) and GET /api/v1/health (DBs, model
   probes, index lag; SPEC.md §11), plus the probe cache and the DB
   liveness check."
  (:require [clj-http.client :as http]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [hybridrag.fixtures :as fx]
            [hybridrag.health :as health]
            [hybridrag.test-utils :as test-utils]
            [hybridrag.tmp :as tmp]
            [hybridrag.web-fixtures :as wf]
            [integrant-extras.tests :as ig-extras]
            [jsonista.core :as json]
            [reitit-extras.tests :as reitit-extras]))

(use-fixtures :once fx/with-sample-index)
(use-fixtures :each wf/with-app-users)

(defn- get-json [handler uri]
  (let [resp (handler {:request-method :get
                       :uri uri
                       :scheme :http
                       :server-name "localhost"
                       :headers {"accept" "application/json"}})]
    (update resp :body #(json/read-value (if (string? %) % (slurp %)) json/keyword-keys-object-mapper))))

(def ok-models {:embed-fn fx/hash-embed
                :rerank-fn wf/ok-rerank
                :chat-fn (wf/chat-reply "ok")})

(defn- boom [& _] (throw (ex-info "down" {:llm/endpoint :x})))

(deftest test-live-checks-only-dbs
  (let [h (wf/handler :context {:search {:embed-fn boom
                                         :rerank-fn boom
                                         :chat-fn boom}})
        {:keys [status body]} (get-json h "/api/v1/health/live")]
    (is (= 200 status))
    (is (= {:status "ok"
            :checks {:index_db "ok"
                     :app_db "ok"}} body))))

(deftest test-full-health
  (testing "all up"
    (let [{:keys [status body]} (get-json (wf/handler :context {:search ok-models}) "/api/v1/health")]
      (is (= 200 status))
      (is (= "ok" (:status body)))
      (is (= #{"ok"} (set (vals (:checks body)))))
      (is (= #{:index_db :app_db :embed :rerank :chat} (set (keys (:checks body)))))
      (is (= 0 (:index_lag body)))))
  (testing "rerank down → 503 naming it"
    (let [{:keys [status body]} (get-json (wf/handler :context {:search (assoc ok-models :rerank-fn boom)})
                                          "/api/v1/health")]
      (is (= 503 status))
      (is (= "degraded" (:status body)))
      (is (= "down" (get-in body [:checks :rerank])))
      (is (= "ok" (get-in body [:checks :embed]))))))

(deftest test-db-ok-detects-closed-conn
  (let [p (tmp/dir "closed")
        c (d/get-conn p {})]
    (is (health/db-ok? c))
    (d/close c)
    (is (not (health/db-ok? c)))
    (is (nil? (health/index-lag c)))
    (is (not (health/db-ok? nil)))
    (tmp/delete-tree! p)))

(deftest test-probe-cache
  (let [cache (atom nil)
        calls (atom 0)
        probe #(do (swap! calls inc) {:embed "ok"})]
    (is (= {:embed "ok"} (health/cached-probe cache 0 30000 probe)))
    (health/cached-probe cache 29999 30000 probe)
    (is (= 1 @calls))
    (health/cached-probe cache 30000 30000 probe)
    (is (= 2 @calls))))

(deftest test-live-through-system
  ((ig-extras/with-system)
   (fn []
     (let [url (str (reitit-extras/get-server-url (test-utils/server) :host) "/api/v1/health/live")]
       (is (= 200 (:status (http/get url {:throw-exceptions false}))))))))
