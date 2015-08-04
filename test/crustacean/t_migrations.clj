(ns crustacean.t_migrations
  (:require [midje.sweet :refer :all]
            [datomic.api :as d]
            [io.rkn.conformity :as c]
            [flatland.ordered.map :refer [ordered-map]]

            [crustacean.core :refer :all]
            [crustacean.migrations :refer :all]))

(facts "about `migration-txes`"
  (let [entity1 (defentity* 'name '[(:fields [field1 :string])])
        entity2 (defentity* 'name '[(:fields [field1 :string]
                                             [field2 :string])])
        entity3 (defentity* 'name '[(:fields [field2 :string])])]

    (fact "it can add fields"
      (migration-txes entity1 entity2) => (just [(contains {:db/ident :name/field2
                                                            :db/valueType :db.type/string})])
      (provided (db-functions entity2) => nil))
    (fact "it can remove fields"
      (migration-txes entity2 entity1) => [{:db/id :name/field2
                                            :db/ident :unused/name/field2}]
      (provided (db-functions entity1) => nil))
    (fact "it can rename fields"
      (migration-txes entity1 entity3) => [{:db/id :name/field1
                                            :db/ident :name/field2}]
      (provided (db-functions entity3) => nil))))

(fact "`get-migrations` pulls migrations from a file"
  (get-migrations ..entity..) => ..migrations..
  (provided
    ..entity.. =contains=> {:migration-file ..somefile..}
    (clojure.java.io/resource ..somefile..) => ..some-resource..
    (read-string (slurp ..some-resource..)) => ..migrations..)

  (get-migrations ..no-migrations-file..) => (throws Exception))

(fact "`write-migrations` writes  migrations to a file"
  (let [migration-file "testdata/migrations.edn"
        entity {:migration-file migration-file}]
    (when (clojure.java.io/resource migration-file)
      (clojure.java.io/delete-file (str "resources/" migration-file))) ;; to get rid of emtpty file
    (do (write-migrations entity) => nil
      (provided
        (initial-txes entity) => "migrations"))
    (let [[date {txes :txes}] (first (read-string (slurp (clojure.java.io/resource migration-file))))]
      txes => ["migrations"])))

(facts "about `sync-entity`"
  (fact "it transacts an entity's norms to the database"
    (sync-entity ..conn.. ..entity..) => ..success..
    (provided
      (get-migrations ..entity..) => ..migrations..
      (c/ensure-conforms ..conn.. ..migrations..) => ..success..))
  (fact "it throws if the entity has fields not in the migrations"
    (sync-entity ..conn.. ..entity..) => (throws Exception)
    (provided
      ..entity.. =contains=> {:fields {:field1 [:string #{}]
                                       :field2 [:int #{}]}}
      (get-migrations ..entity..) => [:version {:fields {:field1 [:string #{}]}}])))
