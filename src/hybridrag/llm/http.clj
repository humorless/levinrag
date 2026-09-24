(ns hybridrag.llm.http
  "Shared HTTP plumbing for the three vLLM OpenAI-compatible endpoints.
   Errors are normalized to ex-info with :llm/endpoint, :http/status and
   :llm/body-excerpt so callers can branch without parsing messages.
   API keys are never included in the exception or logged (SPEC.md §4.3)."
  (:require [hato.client :as hc]
            [jsonista.core :as json])
  (:import [java.net ConnectException]
           [java.net.http HttpTimeoutException]))

(def ^:private object-mapper (json/object-mapper {:decode-key-fn keyword}))

(defn- excerpt [s] (when s (subs s 0 (min 500 (count s)))))

(defn post-json!
  "POST `body` (a Clojure map) as JSON to `url` with bearer `api-key`.
   `endpoint-kw` (e.g. :embed) is only used for error reporting."
  [{:keys [url api-key body connect-timeout-ms read-timeout-ms endpoint-kw]}]
  ;; HTTP/1.1 only: the JDK client otherwise attempts an h2c upgrade on
  ;; http:// URLs, which some OpenAI-compatible servers (LM Studio) never
  ;; answer, so each call waits out the full read timeout.
  (let [client (hc/build-http-client {:connect-timeout connect-timeout-ms
                                      :version :http-1.1})]
    (try
      (let [response (hc/post url
                              {:http-client client
                               :timeout read-timeout-ms
                               :oauth-token api-key
                               :content-type "application/json"
                               :body (json/write-value-as-string body)
                               :throw-exceptions? false})
            status (:status response)
            raw (:body response)]
        (if (<= 200 status 299)
          (json/read-value raw object-mapper)
          (throw (ex-info (str "vLLM " (name endpoint-kw) " returned HTTP " status)
                          {:llm/endpoint endpoint-kw
                           :http/status status
                           :llm/body-excerpt (excerpt raw)}))))
      (catch HttpTimeoutException e
        (throw (ex-info (str "vLLM " (name endpoint-kw) " timed out")
                        {:llm/endpoint endpoint-kw
                         :http/status nil} e)))
      (catch ConnectException e
        (throw (ex-info (str "vLLM " (name endpoint-kw) " connection refused")
                        {:llm/endpoint endpoint-kw
                         :http/status nil} e))))))