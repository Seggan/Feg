(ns fig.impl
  (:require [clojure.edn :as edn]
            [clojure.math.numeric-tower :as math]
            [clojure.string :as str]
            [fig.chars :as chars]
            [fig.compression :as compression])
  (:use [fig.helpers])
  (:import (ch.obermuhlner.math.big DefaultBigDecimalMath)))

; Stuff needed by the interpreter

(def ^:private lastReturnValue (atom 0))

(def ^:private register (atom 0))

(def ^:private input (atom nil))

(def ^:private currentFunction (atom nil))

(declare interpret)

; Implementations of operators

(defn add [a b] (vectorise add a b (if (and (number? a) (number? b)) (+' a b) (str a b))))

(defn addToEnd [a b] (cond
                       (and (sequential? a) (sequential? b)) (lazy-cat a b)
                       (sequential? a) (append a b)
                       (sequential? b) (cons a b)
                       :else (str a b)))

(defn all [x] (matchp x
                      sequential? (if (every? bool x) 1 0)
                      number? (math/abs x)
                      string? (str/upper-case x)
                      x))

(defn any [x] (matchp x
                      sequential? (elvis (some bool x) 0)
                      number? (range 1 (inc x))
                      string? (str/lower-case x)
                      x))

(defn binaryIf [a b] (format "'%s'" (let [condResult (interpret a)] (if (bool condResult) (interpret b) condResult))))

(defn butlastF [x] (if (string? x) (subs x 0 (dec (count x))) (elvis (butlast x) (list))))

(defn chrOrd [x]
  (vectorise chrOrd x
             (matchp x
                     number? (str (char x))
                     string? (if (= 1 (count x))
                               (int (first x))
                               (map int x))
                     fn? (fn [& inp] (if (bool (apply x inp)) 0 1)))))

(defn compress [x] (let [compressed (compression/compress x)] (if (< (count compressed) (count x)) compressed x)))

(defn divide [a b]
  (vectorise divide a b
             (cond
               (and (number? a) (number? b)) (with-precision 128 (/ b a))
               (string? b) (str/split b (re-pattern (str/re-quote-replacement (str a))))
               :else a)))

(defn dropF [a b]
  (let [[f arg] (sortTypes fn? identity a b)
        arity (:figArity (meta f))]
    (if (some? arg)
      (let [coll (listify arg)
            result (lazy-cat (apply f (take arity coll)) (drop arity coll))]
        (matchp arg
                string? (str/join result)
                number? (edn/read-string (str/join result))
                result))
      (cond
        (number? b) (if (string? a) (subs a b) (drop b a))
        (and (string? a) (string? b)) (= (str/lower-case a) (str/lower-case b))
        :else a))))

(defn even [x] (matchp x
                       sequential? (map-indexed vector x)
                       number? (if (= 0 (mod x 2)) 1 0)
                       string? (map-indexed #(vector %1 (str %2)) x)
                       x))

(defn everyNth [a b c]
  (let [[f n arg] (sortTypes fn? number? identity a b c)
        mapper (fn [a] (map-indexed #(if (zero? (mod n (inc %1))) (f %2) %2) a))]
    (matchp arg
            sequential? (mapper arg)
            string? (str/join (mapper (listify arg)))
            arg)))

(defn equals [a b]
  (let [[_ x] (sortTypes sequential? identity a b)]
    (if-not (sequential? x)
      (vectorise equals a b (if (equal a b) 1 0))
      (if (equal a b) 1 0))))

(defn filterF [a b]
  (let [[f coll] (sortTypes fn? identity a b)]
    (matchp coll
            sequential? (filter f coll)
            string? (str/join (filter f (listify coll)))
            (let [check (complement (set (listify a)))]
              (matchp b
                      string? (str/join (filter check (listify b)))
                      sequential? (filter check b)
                      a)))))

(defn floor [x]
  (vectorise floor x
             (matchp x
                     number? (math/floor x)
                     string? (edn/read-string x)
                     x)))

(defn generate [a b]
  (let [[f i] (sortTypes fn? identity a b)]
    (if (some? f)
      (map first (iterate #(rest (append % (apply f %))) (take
                                                           (elvis (:figArity (meta f)) 1)
                                                           (cycle (if (seqable? i) i (list i))))))
      (max-key cmp a b))))

(defn halve [x]
  (vectorise halve x
             (if (number? x)
               (/ x 2M)
               (let [s (str x)
                     len (count s)
                     half (/ len 2)]
                 (list (subs s 0 half) (subs s half len))))))

(defn index [a b]
  (let [[f arg] (sortTypes fn? identity a b)]
    (cond
      (some? arg) (if (equal (f arg) arg) 1 0)
      (number? a) (get (listify b) a)
      :else (get (listify a) b))))

(defn interleaveF [a b]
  (cond
    (sequential? a) (interleave a (listify b))
    (sequential? b) (interleave (listify a) b)
    (and (string? a) (string? b)) (str/join (interleave a b))
    :else a))

(defn locate [a b] (matchp b
                           sequential? (ffirst (filter #(equal (second %) a) (map-indexed vector b)))
                           string? (str/index-of b a)
                           a))

(defn loopForever [& x] (loop [] (run! interpret x) (recur)))

(defn mapF [a b]
  (let [[f coll] (sortTypes fn? identity a b)]
    (matchp coll
            sequential? (map f coll)
            string? (strmapstr f coll)
            number? (applyOnParts #(f (digits %)) coll)
            fn? (comp a b)
            (let [replacer (fn [_] a)]
              (matchp b
                      string? (strmapstr replacer b)
                      sequential? (map replacer b)
                      number? (applyOnParts #(repeat (count %) a) b)
                      fn? (fn [& _] a)
                      a)))))

(defn modulo [a b]
  (cond
    (and (number? a) (number? b)) (mod b a)
    (string? a) (if (sequential? b)
                  (let [replacing (atom a)
                        coll (.iterator (cycle b))]
                    (while (str/includes? @replacing "%")
                      (swap! replacing str/replace-first "%" (str/re-quote-replacement (str (.next coll)))))
                    (deref replacing))
                  (str/replace a "%" (str/re-quote-replacement (str b))))
    :else (vectorise modulo a b a)))

(defn multiply [a b]
  (vectorise multiply a b
             (if (and (number? a) (number? b))
               (*' a b)
               (let [[times x] (sortTypes number? string? a b)]
                 (if (some? times)
                   (.repeat x times)
                   a)))))

(defn negate [x]
  (vectorise negate x
             (if (number? x)
               (-' x)
               (strmap #(if (chars/isUpperCase %)
                          (chars/toLowerCase %)
                          (chars/toUpperCase %))
                       x))))

(defn odd [x] (matchp x
                      sequential? (str/join (flatten x))
                      number? (mod x 2)
                      x))

(defn prefixes [x]
  (matchp x
          sequential? (take-while identity (iterate butlast x))
          string? (map str/join (take-while identity (iterate butlast x)))
          number? (filter #(zero? (mod x %)) (range 1 x))
          x))

(defn reduceF [a b]
  (if (and (number? a) (number? b))
    (range a b)
    (let [[f coll] (sortTypes fn? identity a b)]
      (if (seq coll)
        (matchp coll
                sequential? (reduce f coll)
                string? (reduce f (listify coll))
                number? (reduce f (digits coll)))
        0))))

(defn removeF [a b]
  (let [[f arg] (sortTypes fn? identity a b)]
    (if (some? arg)
      (f arg)
      (cond
        (sequential? b) (remove #(equal a %) b)
        (and (string? a) (string? b)) (str a (subs b (count a)))
        :else a))))

(defn reverseF [x] (matchp x
                           sequential? (reverse x)
                           string? (str/reverse x)
                           number? (applyOnParts #(str/reverse %) x)
                           x))

(defn sortBy [a b]
  (let [[f arg] (sortTypes fn? identity a b)
        func (comp numify f)]
    (matchp arg
            sequential? (sort-by func arg)
            string? (str/join (sort-by func (listify arg)))
            number? (applyOnParts #(sort-by f %) arg)
            (if (and (number? a) (number? b))
              (DefaultBigDecimalMath/pow (bigdec a) (bigdec b))
              (if (some sequential? (list a b))
                (vectorise sortBy a b a)
                a)))))

(defn sortIncreasing [x] (matchp x
                                 sequential? (sort x)
                                 string? (str/join (sort x))
                                 number? (applyOnParts sortIncreasing x)
                                 x))

(defn subtract [a b]
  (vectorise subtract a b
             (cond
               (and (number? a) (number? b)) (-' b a)
               (and (string? a) (string? b)) (str/replace b a "")
               :else a)))

(defn sum [x] (if (string? x) (reduce + (map int x)) (reduceF add x)))

(defn takeF [a b]
  (if (number? b)
    (matchp a
            sequential? (take b a)
            string? (subs a 0 b)
            number? (applyOnParts #(take b %) a)
            a)
    (let [[f arg] (sortTypes fn? identity a b)
          func (comp bool f)]
      (matchp arg
              sequential? (take-while func arg)
              string? (str/join (take-while func (listify arg)))
              number? (applyOnParts #(take-while func (listify %)) arg)
              a))))

(defn ternaryIf [a b c] (if (bool (interpret a)) (interpret b) (interpret c)))

(defn transliterate [a b c]
  (if (and (string? a) (string? b) (string? c))
    (let [m (zipmap a b)] (strmap #(if (contains? m %) (get m %) %) c))
    (let [[f indexes coll] (sortTypes fn? sequential? identity a b c)]
      (if (some? f)
        (matchp coll
                sequential? (map-indexed #(if (collContains indexes %1) (f %2) %2) coll)
                string? (str/join (map-indexed #(if (collContains indexes %1) (f %2) %2) (listify coll))))
        (let [[f index coll] (sortTypes fn? number? identity a b c)]
          (matchp coll
                  sequential? (map-indexed #(if (= index %1) (f %2) %2) coll)
                  string? (str/join (map-indexed #(if (= index %1) (f %2) %2) (listify coll)))))))))

(defn truthyIndexes [x] (matchp x
                                sequential? (keep-indexed #(if (bool %2) %1) x)
                                fn? (with-meta x {:figArity 3})
                                x))

(defn unhalve [x] (vectorise unhalve x (if (number? x) (*' x 2) (str x x))))

(defn uniquify [x] (matchp x
                           sequential? (distinct x)
                           string? (str/join (distinct x))
                           number? (applyOnParts distinct x)
                           x))

(defn uninterleave [x]
  (let [res (partition 2 x)
        one (map first res)
        two (map second res)]
    (if (string? x) (list (str/join one) (str/join two)) (list one two))))

(defn vectoriseOn [x]
  (let [[op args] x]
    (mapF (interpret (last args)) (interpret (list :functionRef (list op (append (butlast args) [:input])))))))

(defn zipmapF [a b c]
  (let [[f arg1 arg2] (sortTypes fn? identity identity a b c)]
    (if (some? f)
      (matchp arg1
              sequential? (map f arg1 arg2)
              string? (str/join (map f (listify arg1) (listify arg2))))
      a)))

; Operators

(def operators {
                :ternaryIf       {:symbol "!" :arity 3 :impl ternaryIf :macro true}
                :reverse         {:symbol "$" :arity 1 :impl reverseF}
                :modulo          {:symbol "%" :arity 2 :impl modulo}
                :bitAnd          {:symbol "&" :arity 2 :impl bit-and}
                :loopForever     {:symbol "(" :arity -1 :impl loopForever :macro true}
                :multiply        {:symbol "*" :arity 2 :impl multiply}
                :add             {:symbol "+" :arity 2 :impl add}
                :println         {:symbol "," :arity 1 :impl printF}
                :subtract        {:symbol "-" :arity 2 :impl subtract}
                :pair            {:symbol ":" :arity 1 :impl #(vector % %)}
                :print           {:symbol ";" :arity 1 :impl #(printF % nil)}
                :lessThan        {:symbol "<" :arity 2 :impl #(if (< (cmp %1 %2) 0) 1 0)}
                :equals          {:symbol "=" :arity 2 :impl equals}
                :greaterThan     {:symbol ">" :arity 2 :impl #(if (> (cmp %1 %2) 0) 1 0)}
                :binaryIf        {:symbol "?" :arity 2 :impl binaryIf :macro true}
                :all             {:symbol "A" :arity 1 :impl all}
                :fromBinary      {:symbol "B" :arity 1 :impl #(fromBase % 2)}
                :chrOrd          {:symbol "C" :arity 1 :impl chrOrd}
                :compress        {:symbol "#D" :arity 1 :impl compress}
                :even            {:symbol "E" :arity 1 :impl even}
                :filter          {:symbol "F" :arity 2 :impl filterF}
                :generate        {:symbol "G" :arity 2 :impl generate}
                :halve           {:symbol "H" :arity 1 :impl halve}
                :count           {:symbol "I" :arity 1 :impl (fn [a b] (count (filter #(equal b %) (listify a))))}
                :addToEnd        {:symbol "J" :arity 2 :impl addToEnd}
                :sortIncreasing  {:symbol "K" :arity 1 :impl sortIncreasing}
                :length          {:symbol "L" :arity 1 :impl #(if (number? %) (count (str (bigint %))) (count %))}
                :map             {:symbol "M" :arity 2 :impl mapF}
                :negate          {:symbol "N" :arity 1 :impl negate}
                :odd             {:symbol "O" :arity 1 :impl odd}
                :transliterate   {:symbol "P" :arity 3 :impl transliterate}
                :lastReturnValue {:symbol "Q" :arity 0 :impl (const (deref lastReturnValue))}
                :reduce          {:symbol "R" :arity 2 :impl reduceF}
                :sum             {:symbol "S" :arity 1 :impl sum}
                :truthyIndexes   {:symbol "T" :arity 1 :impl truthyIndexes}
                :uniquify        {:symbol "U" :arity 1 :impl uniquify}
                :setRegister     {:symbol "V" :arity 1 :impl #(reset! register %)}
                :wrapOne         {:symbol "W" :arity 1 :impl vector}
                :currentFunction {:symbol "X" :arity 0 :impl (const (deref currentFunction))}
                :interleave      {:symbol "Y" :arity 2 :impl interleaveF}
                :zip             {:symbol "Z" :arity 2 :impl #(map vector (listify %1) (listify %2))}
                :first           {:symbol "[" :arity 1 :impl #(first (listify %))}
                :divide          {:symbol "\\" :arity 2 :impl divide}
                :last            {:symbol "]" :arity 1 :impl #(last (listify %))}
                :sortBy          {:symbol "^" :arity 2 :impl sortBy}
                :floor           {:symbol "_" :arity 1 :impl floor}
                :list            {:symbol "`" :arity -1 :impl vector}
                :any             {:symbol "a" :arity 1 :impl any}
                :toBinary        {:symbol "b" :arity 1 :impl #(toBase % 2)}
                :vectoriseOn     {:symbol "e" :arity 1 :impl vectoriseOn :macro true}
                :flatten         {:symbol "f" :arity 1 :impl #(if (sequential? %) (flatten %) (listify %))}
                :min             {:symbol "g" :arity 2 :impl #(min-key cmp %1 %2)}
                :unhalve         {:symbol "h" :arity 1 :impl unhalve}
                :index           {:symbol "i" :arity 2 :impl index}
                :joinOn          {:symbol "j" :arity 2 :impl #(str/join (str %1) (flatten (listify %2)))}
                :prefixes        {:symbol "k" :arity 1 :impl prefixes}
                :locate          {:symbol "l" :arity 2 :impl locate}
                :isList          {:symbol "#l" :arity 1 :impl #(if (sequential? %) 1 0)}
                :everyNth        {:symbol "n" :arity 3 :impl everyNth}
                :isNumber        {:symbol "#n" :arity 1 :impl #(if (number? %) 1 0)}
                :remove          {:symbol "o" :arity 2 :impl removeF}
                :rest            {:symbol "p" :arity 1 :impl #(if (string? %) (subs % 1) (rest (listify %)))}
                :butlast         {:symbol "q" :arity 1 :impl butlastF}
                :range           {:symbol "r" :arity 2 :impl #(if (sequential? %) (if (seq %) (reduceF % multiply) 1) (range %))}
                :drop            {:symbol "s" :arity 2 :impl dropF}
                :isString        {:symbol "#s" :arity 1 :impl #(if (string? %) 1 0)}
                :take            {:symbol "t" :arity 2 :impl takeF}
                :getRegister     {:symbol "v" :arity 0 :impl (const (deref register))}
                :wrapTwo         {:symbol "w" :arity 2 :impl vector}
                :input           {:symbol "x" :arity 0}     ; input is implemented in the interpret function itself
                :uninterleave    {:symbol "y" :arity 1 :impl uninterleave}
                :zipmap          {:symbol "z" :arity 2 :impl zipmapF}
                :decrement       {:symbol "{" :arity 1 :impl dec'}
                :bitOr           {:symbol "|" :arity 2 :impl bit-or}
                :increment       {:symbol "}" :arity 1 :impl inc'}
                :bitNot          {:symbol "~" :arity 1 :impl bit-not}

                :uAlphabet       {:symbol "cA" :arity 0 :impl (const "ABCDEFGHIJKLMNOPQRSTUVWXYZ")}
                :lAlphabet       {:symbol "ca" :arity 0 :impl (const "abcdefghijklmnopqrstuvwxyz")}
                :bothAlphabets   {:symbol "cB" :arity 0 :impl (const "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")}
                :bothConsonants  {:symbol "cb" :arity 0 :impl (const "BCDFGHJKLMNPQRSTVWXYZbcdfghjklmnpqrstvwxyz")}
                :uConsonants     {:symbol "cC" :arity 0 :impl (const "BCDFGHJKLMNPQRSTVWXYZ")}
                :lConsonants     {:symbol "cc" :arity 0 :impl (const "bcdfghjklmnpqrstvwxyz")}
                :digits          {:symbol "cd" :arity 0 :impl (const "0123456789")}
                :alphaAndDigits  {:symbol "cD" :arity 0 :impl (const "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")}
                :newline         {:symbol "cn" :arity 0 :impl (const "\n")}
                :bothVowels      {:symbol "cO" :arity 0 :impl (const "AEIOUaeiou")}
                :bothVowelsWithY {:symbol "cY" :arity 0 :impl (const "AEIOUYaeiouy")}
                :uVowels         {:symbol "cV" :arity 0 :impl (const "AEIOU")}
                :lVowels         {:symbol "cv" :arity 0 :impl (const "aeiou")}
                :uVowelsWithY    {:symbol "cY" :arity 0 :impl (const "AEIOUY")}
                :lVowelsWithY    {:symbol "cy" :arity 0 :impl (const "aeiouy")}
                :uReversedAlpha  {:symbol "cZ" :arity 0 :impl (const "ZYXWVUTSRQPONMLKJIHGFEDCBA")}
                :lReversedAlpha  {:symbol "cz" :arity 0 :impl (const "zyxwvutsrqponmlkjihgfedcba")}

                :eulersNumber    {:symbol "mE" :arity 0 :impl (const 2.71828182845904523536028747135266249775724709369995M)}
                :phi             {:symbol "mG" :arity 0 :impl (const 1.61803398874989484820458683436563811772030917980576M)}
                :pi              {:symbol "mP" :arity 0 :impl (const 3.14159265358979323846264338327950288419716939937510M)}
                :countingNumbers {:symbol "mC" :arity 0 :impl (const (iterate inc' 1))}
                :naturalNumbers  {:symbol "mN" :arity 0 :impl (const (iterate inc' 0))}})

(defn attr [op attribute] (if (contains? operators op)
                            (get-in operators [op attribute])
                            (throw (IllegalArgumentException. "Unknown operator"))))

; Interpreter

(defn interpret [node]
  (reset! lastReturnValue
          (let [[type args] node]
            (cond
              (= type :constant) args
              (= type :input) (.next @input)
              (= type :functionRef) (let [arity {:figArity (count (filter #{:input} (flatten args)))}]
                                      (with-meta
                                        (fn figLambda [& inp] (tempAtomValue
                                                                input
                                                                (.iterator (cycle inp))
                                                                (tempAtomValue
                                                                  currentFunction
                                                                  (with-meta figLambda arity)
                                                                  (interpret args)))) arity))
              :else (apply (attr type :impl) (if (attr type :macro) args (map interpret args)))))))

(defn interpretProgram [ast programInput] (tempAtomValue
                                            input
                                            (.iterator (cycle programInput))
                                            (tempAtomValue
                                              currentFunction
                                              (with-meta
                                                (fn [& inp] (apply interpret (cons ast inp)))
                                                {:figArity (count (filter #{:input} (flatten ast)))})
                                              (elvis (last (map interpret (filter coll? ast))) 0))))
