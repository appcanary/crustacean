(ns crustacean.t_core
  (:require [midje.sweet :refer :all]
            [datomic.api :as d]
            [schema.core :as s]
            [io.rkn.conformity :as c]

            [crustacean.core :refer [defentity]]
            [crustacean.core :refer :all]
            [crustacean.utils :refer [remove-nils]]
            [crustacean.migrations :refer [write-migrations sync-entity]]))


(def migration-file "resources/testdata/entity.edn")
;; Create an entity to test with
(defentity entity
  (:migration-file "resources/testdata/entity.edn")
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
      (when (.exists (clojure.java.io/as-file migration-file))
        (clojure.java.io/delete-file migration-file))
      (write-migrations entity)
      (sync-entity (get-conn) entity)))

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

(fact "`defentity*` convers forms to an entity definition"
  ;; ..name.. is quoted here because it's passed as a symbol (as this is called by a macro)
  (defentity* '..name.. '[(:migration-file ..file..)]) => (contains {:migration-file ..file..})
  (defentity* '..name.. '[(:migration-version ..version..)]) => (contains {:migration-version ..version..})

  ;; follow the convention of datomic-schema
  (defentity* '..name.. '[(:fields [:field ..type.. :opt :opt2])]) => (contains {:fields {"field" [..type.. #{:opt :opt2}]}})

  (defentity* '..name.. '[(:defaults [:field ..value..])]) => (contains {:defaults {"field" ..value..}})
  (defentity* '..name.. '[(:defaults [:field (fn [] ..result..)])]) => (contains {:defaults {"field" '(quote (fn [] ..result..))}})

  (defentity* '..name.. '[(:validators [:field ..regex..])]) =>  (contains {:validators {"field" ..regex..}})
  (defentity* '..name.. '[(:validators [:field '(fn [] ..result..)])]) => (contains {:validators {"field" ''(quote (fn [] ..result..))}})

  (defentity* '..name.. '[(:composite-keys [..key.. ..anotherkey..])]) => (contains {:composite-keys [[:..key.. :..anotherkey..]]})

  (defentity* '..name.. '[(:views :one ..view-for-one.. :many ..view-for-many..)]) => (contains {:views {:one ..view-for-one.. :many ..view-for-many..}})

  (defentity* '..name.. '[(:backrefs [..field.. ..opt..])]) => (contains {:backrefs {:..field.. ..opt..}})
  (defentity* '..name.. '[(:extra-txes ..tx..)]) => (contains {:extra-txes ..tx..})

  ;; these are needed for datomic-schema
  (defentity* '..name.. '()) => (contains {:name "..name.."})
  (defentity* '..name.. '()) => (contains {:basetype :..name..})
  (defentity* '..name.. '()) => (contains {:namespace "..name.."}))

(facts "about `->input-schema*`"
  (let [empty (defentity* 'entity '())
        unsettable-fields (defentity* 'entity '[(:fields [..field.. :string])])
        permitted-fields (defentity* 'entity '[(:fields [..field.. :string :assignment-permitted])])
        required-fields (defentity* 'entity '[(:fields [..field.. :string :assignment-required])])
        validators (defentity* 'entity '[(:fields  [..field.. :string :assignment-required]
                                                   [..field2.. :string :assignment-required])
                                         (:validators [..field.. ..regex..]
                                                      [..field2.. '(fn [] "function")])])
        backrefs (defentity* 'entity '[(:fields  [..field.. :string :assignment-required])
                                       (:backrefs [back/ref :assignment-required]
                                                  [back/ref2 :assignment-permitted])])]
    
    (->input-schema* empty) => {}
    (->input-schema* unsettable-fields) => {}
    (->input-schema* permitted-fields) => `{(s/optional-key :..field..) s/Str}
    (->input-schema* required-fields) => `{:..field.. s/Str}
    (->input-schema* validators) => `{:..field.. (s/both s/Str ..regex..)
                                      :..field2.. (s/both s/Str (s/pred ~''(quote (fn [] "function"))))}
    (->input-schema* backrefs) => `{:..field.. s/Str
                                    :back/ref (s/either s/Int {s/Keyword s/Any})
                                    (s/optional-key :back/ref2) (s/either s/Int {s/Keyword s/Any})}))


(facts "about `->ouput-schema`"
  (let [entity (defentity* 'entity '[(:fields  [..field.. :string]
                                               [..field2.. :ref])
                                     ])]
    (->output-schema entity) => (s/schema-with-name {:id Long
                                                     s/Keyword s/Any
                                                     (s/optional-key :..field..) s/Str
                                                     (s/optional-key :..field2..) s/Any} "EntityOut")))

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
