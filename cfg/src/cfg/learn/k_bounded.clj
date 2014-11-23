(ns cfg.learn.k-bounded
  (:require [clojure.set :refer [union]]
            [clojure.string :refer [join]]
            [cfg.list-util :refer [queue]]
            [cfg.learn.util :refer :all]
            [cfg.lang :refer [parse-tree]]
            [cfg.cfg :refer [cfg add-rule remove-rule
                             cnf-leaf? cnf-branch?
                             prune show-cfg]]))

(defn- diagnose
  "Given a non-terminal membership predicate `member*`, and a parse-tree `t`,
  return a bad production used in the parse-tree."
  [member* t]
  (letfn [(consume-child [state [rule & children]]
            (if-let [bad-child (some (fn [[cr cy :as child]]
                                       (when-not (member* cr cy) child))
                                     children)]
              (update-in state [0] conj  bad-child)
              (update-in state [1] conj! rule)))]
    (loop [q         (queue t)
           bad-rules (transient #{})]
      (if (seq q)
        (let [[_ _ children] (peek q)
              [q* bad-rules*] (reduce consume-child
                                      [(pop q) bad-rules]
                                      children)]
          (recur q* bad-rules*))
        (persistent! bad-rules)))))

(defn- candidate
  "Given a multimap of non-terminals to terminals they cannot yield (the
  `blacklist`), a sequence of non-terminals, `nts` and a vector of tokens,
  `toks`, give a collection of rules that, when added to the grammar associated
  with `blacklist` and `nts`, will allow it to recognise `toks`."
  [nts blacklist toks]
  (for [t toks, nt nts,
        :let  [leaf [nt t]]
        :when (not (blacklist leaf))]
    leaf))

(defn- init-grammar
  "Given a sequence of non-terminals `nts`, create the CNF grammar containing
  all possible branches of those non-terminals (without any leaf nodes)."
  [nts]
  (reduce add-rule (cfg)
          (for [a nts b nts c nts]
            [a b c])))

(defn learn
  "Takes two functions:

   * `counter*` Takes a grammar, and, if it is the correct one, returns `nil`,
     otherwise, returns a vector of tokens that provides a counter-example
     showing a string that either should be in the language and is not, or
     shouldn't be in the language but is.
   * `member*` Takes a non-terminal, and a sequence of tokens and determines
     whether it is possible to yield the tokens from the non-terminal in
     the target grammar.

  As well as a list of non-terminals `nts`, and attempts to learn a grammar
  with non-terminals in `nts` and rules governed by the responses from queries
  to `counter*` and `member*`."
  [counter* member* nts]
  (let [member* (memoize member*)]
    (loop [g (init-grammar nts), blacklist #{}]
      (let [pg (prune g)]
        (if-let [c (counter* pg)]
          (if-let [t (parse-tree g c)]
            (let [bad-rules  (diagnose member* t)
                  bad-leaves (filter cnf-leaf? bad-rules)]
              (recur (reduce remove-rule g bad-rules)
                     (into blacklist bad-leaves)))

            (let [new-rules (candidate nts blacklist c)]
              (recur (reduce add-rule g new-rules)
                     blacklist)))
          pg)))))

(defn interactive-counter
  "A form of the `counter*` predicate used in the learning algorithm that
  asks for its response from the user."
  [g]
  (println "Correct?")
  (println (show-cfg g))
  (print   "Blank for yes, Counter-example for no: ")
  (flush)
  (let [input (read-line)]
    (println)
    (if-not (empty? input)
      (read-string (str \[ input \]))
      nil)))

(defn interactive-member
  "A form of the `member*` predicate used by the k-bounded learning algorithm
  that poses the question to the user."
  [nt yield]
  (print (str nt " =>* " (join \space yield) "? Y/N: "))
  (flush)
  (#{\y \Y} (first (read-line))))

(def interactive-learn
  ^{:doc "A specialisation of the learning algorithm that treats the user as
         the oracle"}
  (partial learn
           interactive-counter
           interactive-member))
