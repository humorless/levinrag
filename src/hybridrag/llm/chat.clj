(ns hybridrag.llm.chat
  "Raw chat/completions client. Stripping <think> blocks and
   validating citations is hybridrag.llm.answer's job."
  (:require [hybridrag.config :as config]
            [hybridrag.llm.http :as http]))

(def ^:private connect-timeout-ms 2000)

(defn complete!
  "Call chat/completions with `messages` ([{:role .. :content ..} ...]).
   Returns the raw parsed response map."
  ([messages opts] (complete! (config/chat-config) messages opts))
  ([{:keys [base-url model api-key read-timeout-ms]
     :or {read-timeout-ms 120000}} messages {:keys [temperature max-tokens extra-body]}]
   (http/post-json!
     {:url (str base-url "/chat/completions")
      :api-key api-key
      :body (merge {:model model
                    :messages messages
                    :temperature temperature
                    :max_tokens max-tokens}
                   extra-body)
      :connect-timeout-ms connect-timeout-ms
      :read-timeout-ms read-timeout-ms
      :endpoint-kw :chat})))