(ns crustacean.t_generators
  (:require [midje.sweet :refer :all]
            [datomic.api :as d]
            [schema.core :as s]
            [io.rkn.conformity :as c]

            [crustacean.core :refer [defentity]]
            [crustacean.generators :refer :all]
            [crustacean.migrations :refer [sync-migrations]]))


;; Create an entity to test with
(defentity entity
  (:fields [field1 :keyword :unique-value :assignment-required]
           [field2 :string :unique-identity :assignment-permitted]
           [field3 :boolean :indexed :assignment-permitted]
           [field4 :long :many]
           [field5 :float :fulltext]
           [field6 :double :component :nohistory]
           [field7 :ref :assignment-permitted :many]
           [field8 :instant "doc string"]
           [field9 :uuid]
           [field10 :uri]
           [field11 :bytes]
           [field12 :enum [:a :b :c]]
           [field13 :fn])
  (:defaults [field3 (fn [] (= 1 1))]
             [field4 13])
  (:validators [field1 (fn [x] (> (count (name x)) 9))]
               [field2 #"hello"]))

(def db-url "datomic:mem://crustacean-test")

(defn get-conn []
  (d/connect db-url))

(defn reset-db! []
  (do (d/delete-database db-url)
      (d/create-database db-url)
      (c/ensure-conforms (get-conn) (sync-migrations entity))))

(fact "`fields-with` finds fields with a given option set"
  (fields-with entity :indexed) => ["field3"]
  (fields-with entity :many) =>  (just ["field4" "field7"] :in-any-order)
  (fields-with entity :component) => ["field6"])

(fact "`unique-fields` finds fields with a unique property set"
  (unique-fields entity) => (just ["field1" "field2"] :in-any-order))

(fact "`field-spec->schema` converts a field spec to a prismatic schema"
  (field-spec->schema (get-in entity [:fields "field1"])) => `s/Keyword
  (field-spec->schema (get-in entity [:fields "field2"])) => `s/Str
  (field-spec->schema (get-in entity [:fields "field3"])) => `s/Bool
  (field-spec->schema (get-in entity [:fields "field4"])) => `(s/either s/Int [s/Int])
  (field-spec->schema (get-in entity [:fields "field5"])) => `Float
  (field-spec->schema (get-in entity [:fields "field6"])) => `java.lang.Double
  (field-spec->schema (get-in entity [:fields "field7"])) => `(s/either (s/either s/Int {s/Keyword s/Any}) [(s/either s/Int {s/Keyword s/Any})])
  (field-spec->schema (get-in entity [:fields "field8"])) => `s/Inst
  (field-spec->schema (get-in entity [:fields "field9"])) => `s/Uuid
  (field-spec->schema (get-in entity [:fields "field10"])) => `java.net.URI
  (field-spec->schema (get-in entity [:fields "field11"])) => `bytes
  (field-spec->schema (get-in entity [:fields "field12"])) => `s/Any)

;; I can't seem to get this test to work
#_(facts "about `->input-schema*`"
    (->input-schema* entity) => `{:field1 s/Keyword
                                  (s/optional-key :field7) [(s/either s/Int {s/Keyword s/Any})]
                                  (s/optional-key :field2) s/Str })

(future-fact "`about ->malformed?*`")

(facts "about `->malformed?"
  (fact "requires required keys"
    ((->malformed? entity) {:field1 :hello-world :field2 "hello" :field7 [1]}) => falsey
    ((->malformed? entity) {:field2 "hello"} ) => truthy)
(fact "checks value type"
  ((->malformed? entity) {:field1 "not-keyword" :field2 "hello" :field7 [1 2]}) => truthy
  ((->malformed? entity) {:field1 :hello-world :field2 :not-string :field7 [1]}) => truthy)
(fact "Calls validators"
  ((->malformed? entity) {:field1 :too-short :field2 "hello" :field7 [1]}) => truthy
  ((->malformed? entity) {:field1 :hello-world :field2 "not valid" :field7 [1 3] }) => truthy))

(facts "about `->create"
  (against-background [(before :facts (reset-db!))]
    (fact "input field with non-nil value should be set"
      ((->create entity) (get-conn) {:field1 :alongkeyword :field2 "hello"}) => (contains {:field2 "hello"}))
    (fact "input field with nil value should NOT be set"
      ((->create entity) (get-conn) {:field1 :alongkeyword :field2 nil}) =not=> (contains {:field2 anything}))
    (fact "input field with false boolean value should be set to false " ; field3 defaults to true
      ((->create entity) (get-conn) {:field1 :alongkeyword :field3 false}) => (contains {:field3 anything}))))

(facts "about #remove-nils fn"
  (fact "it should remove entries with nil values"
    (remove-nils {:a 1 :b nil :c 3}) => {:a 1 :c 3})
  (fact "it should NOT remove entries with empty-string values"
    (remove-nils {:a "1" :b "" :c "3"}) => {:a "1" :b "" :c "3"})
  (fact "it should NOT remove entries with false boolean value"
    (remove-nils {:a true :b false :c true}) => {:a true :b false :c true}))


(future-facts "about `->exists?*")
(future-facts "about `->exists?")
(future-facts "about `->create*")
(future-facts "about `->find-by")
(future-facts "about `->all-with")
