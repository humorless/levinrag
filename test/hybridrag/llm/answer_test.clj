(ns hybridrag.llm.answer-test
  "Answer generation (SPEC.md §10) with a stub chat and a canned search
   result: <think> stripping, citation validation, no-evidence path."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hybridrag.llm.answer :as answer]))

(deftest test-strip-think
  (is (= "答案" (answer/strip-think "<think>想一想\n很久</think>\n\n答案")))
  (is (= "A B" (answer/strip-think "A <think>x</think>B")))
  (is (= "" (answer/strip-think "<think>沒寫完的思考")) "unclosed block drops the rest")
  (is (= "無標籤" (answer/strip-think "無標籤"))))

(deftest test-parse-citations
  (testing "valid, grouped, comma lists, full-width brackets"
    (let [{:keys [text cited invalid]} (answer/parse-citations "甲[1]。乙[3][1]。丙[2, 3]。丁［2］、【1】" 3)]
      (is (= [1 2 3] cited))
      (is (= [] invalid))
      (is (= "甲[1]。乙[3][1]。丙[2][3]。丁[2]、[1]" text))))
  (testing "out-of-range numbers are removed and reported"
    (let [{:keys [text cited invalid]} (answer/parse-citations "甲[1][4]。乙[0]。丙[2024]" 2)]
      (is (= [1] cited))
      (is (= [0 4 2024] invalid))
      (is (= "甲[1]。乙。丙" text))))
  (testing "links and non-numeric brackets are untouched"
    (let [s "見[文件](hr/leave.md)與[註]、[a1]"]
      (is (= {:text s
              :cited []
              :invalid []} (answer/parse-citations s 3))))))

(def passages
  [{:n 1
    :doc/path "hr/leave.md"
    :doc/title "請假規定"
    :section/trail "請假規定 > 特休"
    :chunk-ids ["hr/leave.md::1"]
    :text "特休依年資計算。"}
   {:n 2
    :doc/path "hr/onboard.md"
    :doc/title "新人報到"
    :section/trail "新人報到"
    :chunk-ids ["hr/onboard.md::0"]
    :text "報到當天領取識別證。"}])

(deftest test-messages
  (let [[sys user] (answer/messages "SYSTEM" passages "特休怎麼算？")]
    (is (= {:role "system"
            :content "SYSTEM"} sys))
    (is (= "user" (:role user)))
    (is (str/includes? (:content user) "<sources>\n[1] 請假規定｜請假規定 > 特休\n特休依年資計算。"))
    (is (str/includes? (:content user) "[2] 新人報到｜新人報到\n報到當天領取識別證。"))
    (is (str/ends-with? (:content user) "</sources>\n\n問題：特休怎麼算？"))))

(deftest test-prompt-resource
  (is (str/includes? (answer/prompt) "[n]")))

(defn- chat-reply [content]
  (fn [_ _] {:model "stub"
             :choices [{:message {:content content}}]
             :usage {:prompt_tokens 100
                     :completion_tokens 20}}))

(def found
  {:passages passages
   :candidates []
   :degraded #{}
   :flags #{}
   :stages {:flags #{}}})

(defn- deps-with
  "ask! deps whose search returns `search-result` without an index."
  [search-result chat-fn]
  {:search-fn (fn [& _] search-result)
   :chat-fn chat-fn})

(deftest test-ask-answer-and-citations
  (let [calls (atom [])
        res (answer/ask! (deps-with found (fn [m o]
                                            (swap! calls conj [m o])
                                            ((chat-reply "<think>嗯</think>特休依年資[1]，另見[9]。") m o)))
                         {:username "alice"} "特休？" {})]
    (is (= "特休依年資[1]，另見。" (:answer res)))
    (is (= ["hr/leave.md"] (map :doc/path (:citations res))))
    (is (false? (:no-evidence? res)))
    (is (= {:temperature 0.2
            :max-tokens 1024
            :extra-body nil} (second (first @calls))))
    (is (= {:model "stub"
            :prompt-tokens 100
            :completion-tokens 20
            :finish-reason nil
            :invalid-citations [9]}
           (dissoc (get-in res [:stages :generate]) :ms)))
    (is (not (contains? (get-in res [:stages :flags]) :uncited-answer)))))

(deftest test-ask-uncited-and-not-found
  (is (contains? (get-in (answer/ask! (deps-with found (chat-reply "特休依年資計算。")) {} "q" {}) [:stages :flags])
                 :uncited-answer))
  (is (not (contains? (get-in (answer/ask! (deps-with found (chat-reply "資料中找不到加班費的規定。")) {} "q" {})
                              [:stages :flags])
                      :uncited-answer))))

(deftest test-ask-no-evidence
  (let [res (answer/ask! (deps-with (assoc found :passages []) (fn [& _] (throw (ex-info "chat called" {}))))
                         {} "q" {})]
    (is (true? (:no-evidence? res)))
    (is (= answer/no-evidence-message (:answer res)))
    (is (= [] (:citations res)))
    (is (nil? (get-in res [:stages :generate])))))

(deftest test-empty-after-think
  (let [res (answer/ask! (deps-with found (chat-reply "<think>想到一半")) {} "q" {})]
    (is (= answer/empty-answer-message (:answer res)))
    (is (contains? (get-in res [:stages :flags]) :empty-answer))
    (is (= [] (:citations res)))))

(deftest test-ask-chat-opts-override
  (let [seen (atom nil)]
    (answer/ask! (deps-with found (fn [m o] (reset! seen o) ((chat-reply "x[1]") m o)))
                 {} "q" {:chat/temperature 0.7
                         :chat/max-tokens 2048
                         :chat/extra-body {:a 1}
                         :final-k 3})
    (is (= {:temperature 0.7
            :max-tokens 2048
            :extra-body {:a 1}} @seen))))

(deftest test-pipeline-max-tokens-stays-out-of-chat
  ;; :max-tokens in opts is the context budget (SPEC §5), not max_tokens
  (let [seen (atom nil)
        searched (atom nil)]
    (answer/ask! {:search-fn (fn [_ _ _ opts] (reset! searched opts) found)
                  :chat-fn (fn [m o] (reset! seen o) ((chat-reply "x[1]") m o))}
                 {} "q" {:max-tokens 6000
                         :chat/max-tokens 512})
    (is (= 512 (:max-tokens @seen)))
    (is (= 6000 (:max-tokens @searched)))))

(deftest test-chat-malformed
  (doseq [bad [{:error "model not loaded"} {:choices []} {:choices [{:finish_reason "stop"}]}]]
    (let [e (try (answer/ask! (deps-with found (fn [_ _] bad)) {} "q" {})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :chat (:llm/endpoint (ex-data e))) (pr-str bad)))))

(deftest test-null-content-is-empty-answer
  ;; reasoning parsers return content null when thinking used up max_tokens
  (let [res (answer/ask! (deps-with found (fn [_ _] {:model "stub"
                                                     :choices [{:message {:content nil
                                                                          :reasoning_content "想到一半"}
                                                                :finish_reason "length"}]}))
                         {} "q" {})]
    (is (= answer/empty-answer-message (:answer res)))
    (is (contains? (get-in res [:stages :flags]) :empty-answer))
    (is (= "length" (get-in res [:stages :generate :finish-reason])))))

(deftest test-huge-bracket-number
  (let [{:keys [text cited invalid]} (answer/parse-citations "帳號[12345678901234567890]見[1]" 2)]
    (is (= [1] cited))
    (is (= 1 (count invalid)))
    (is (= "帳號見[1]" text))))
