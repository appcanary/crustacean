(ns crustacean.util-test
  (:require [midje.sweet :refer :all]
            [crustacean.utils :refer :all]
            [crustacean.t_core :refer [entity]]))


(fact "`entity-exists` returns argument map when the map contains a :db/id key"
      (entity-exists? {:db/id 1 :foo "bar"}) => {:db/id 1 :foo "bar"}
      (entity-exists? {:db/id nil}) => falsey
      (entity-exists? {}) => falsey)

(fact "`fields-with` finds fields with a given option set"
  (fields-with entity :indexed) => ["field3"]
  (fields-with entity :many) =>  (just ["field4" "field7"] :in-any-order)
  (fields-with entity :component) => ["field6"])

(fact "`unique-fields` finds fields with a unique property set"
  (unique-fields entity) => (just ["field1" "field2"] :in-any-order))
