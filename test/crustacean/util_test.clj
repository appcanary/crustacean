;; (ns crustacean.util-test
;;   (:require [midje.sweet :refer :all]
;;             [crustacean.utils :refer :all]
;;             [crustacean.t_core :refer [entity]]))


;; (fact "`entity-exists` returns argument map when the map contains a :db/id key"
;;       (entity-exists? {:db/id 1 :foo "bar"}) => {:db/id 1 :foo "bar"}
;;       (entity-exists? {:db/id nil}) => falsey
;;       (entity-exists? {}) => falsey)

;; (fact "`fields-with` finds fields with a given option set"
;;   (fields-with entity :indexed) => ["field3"]
;;   (fields-with entity :many) =>  (just ["field4" "field7"] :in-any-order)
;;   (fields-with entity :component) => ["field6"])

;; (fact "`unique-fields` finds fields with a unique property set"
;;   (unique-fields entity) => (just ["field1" "field2"] :in-any-order))

;; (facts "about `defn-db`"
;;   (fact "it defines a database function"
;;     (defn-db fun
;;       [x y]
;;       (+ x y)) =expands-to=> (def fun (datomic.api/function (clojure.core/merge nil '{:params [x y] :lang :clojure :code (do (+ x y))}))))
;;   (fact "it handles docstrings"
;;     (let [fun (defn-db fun
;;                 "DOCSTRING"
;;                 [x]
;;                 (+ x 1))]
;;       (:doc (meta fun)) => "DOCSTRING"))
;;   (fact "it allows for extra arguments to `d/function`"
;;     (defn-db fun
;;       {:requires ['[clojure.data.json :as json]]}
;;       [x y]
;;       (+ x y)) =expands-to=> (def fun (datomic.api/function (clojure.core/merge {:requires ['[clojure.data.json :as json]]} '{:params [x y] :lang :clojure :code (do (+ x y))})))
;;       (let [fun (defn-db fun
;;                   "DOCSTRING"
;;                   {:requires ['[clojure.data.json :as json]]}
;;                   [x y]
;;                   (+ x y))]
;;         (:doc (meta fun)) => "DOCSTRING"))

;;   (fact "it makes sure parameters are vectors"
;;     (macroexpand '(defn-db fun 1 (+ 1 1)))  => (throws))

;;   (fact "it handles pre conditions"
;;     (defn-db fun
;;       [x y]
;;       {:pre [(= x 1)]}
;;       (+ x y)) =expands-to=> (def fun (datomic.api/function (clojure.core/merge nil '{:params [x y] :lang :clojure :code (do (clojure.core/assert (= x 1))(+ x y))}))))

;;   (fact "it handles post conditions"
;;     (defn-db fun
;;       [x y]
;;       {:post [(= % 1)]}
;;       (+ x y)) =expands-to=> (def fun (datomic.api/function (clojure.core/merge nil '{:params [x y] :lang :clojure :code (do (clojure.core/let [% (+ x y)] (clojure.core/assert (= % 1)) %))})))))
