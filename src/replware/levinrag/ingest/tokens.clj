(ns replware.levinrag.ingest.tokens
  "Token estimation without loading a tokenizer (SPEC.md §7.7).

   - every CJK code point (Han, Hiragana, Katakana, Hangul) counts 1;
   - every run of other letters/digits counts ceil(len / 4) + 1;
   - punctuation, symbols and whitespace count 0.

   The spec says \"ASCII alphanumeric run\"; non-ASCII letters/digits
   (full-width ＨＲ０７, accented Latin, ...) are counted as run characters
   too, which keeps the estimate conservative instead of silently free."
  (:import [java.lang Character$UnicodeScript]))

(defn- cjk? [^long cp]
  (let [script (Character$UnicodeScript/of (int cp))]
    (or (= script Character$UnicodeScript/HAN)
        (= script Character$UnicodeScript/HIRAGANA)
        (= script Character$UnicodeScript/KATAKANA)
        (= script Character$UnicodeScript/HANGUL))))

(defn- char-class
  "One of :cjk, :word or :sep for a code point."
  [^long cp]
  (cond
    (cjk? cp) :cjk
    (Character/isLetterOrDigit (int cp)) :word
    :else :sep))

(defn- run-tokens ^long [^long len]
  (if (zero? len) 0 (inc (long (Math/ceil (/ len 4.0))))))

(defn fit-end
  "Largest end index e in [from, to] such that (subs s from e) estimates to
   at most `budget` tokens, never splitting a surrogate pair. Returns
   `from` when not even the first code point fits."
  [^String s ^long from ^long to ^long budget]
  (loop [i from, done 0, run 0, best from]
    (if (>= i to)
      best
      (let [cp (.codePointAt s (int i))
            n (Character/charCount cp)
            [done run] (case (char-class cp)
                         :cjk [(+ done (run-tokens run) 1) 0]
                         :word [done (inc run)]
                         :sep [(+ done (run-tokens run)) 0])]
        (if (<= (+ done (run-tokens run)) budget)
          (recur (+ i n) done run (+ i n))
          best)))))

(defn estimate-tokens
  "Estimated token count of `s` (SPEC.md §7.7)."
  ^long [^String s]
  (loop [i 0, done 0, run 0]
    (if (>= i (count s))
      (+ done (run-tokens run))
      (let [cp (.codePointAt s (int i))
            n (Character/charCount cp)]
        (case (char-class cp)
          :cjk (recur (+ i n) (+ done (run-tokens run) 1) 0)
          :word (recur (+ i n) done (inc run))
          :sep (recur (+ i n) (+ done (run-tokens run)) 0))))))
