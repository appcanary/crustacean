(ns crustacean.migrations
  (:require [schema.core :as s]
            [datomic.api :as d]
            [clojure.java.io :refer [as-file]]
            [clojure.edn :as edn]
            [clojure.data :as data]
            [datomic-schema.schema :as datomic-schema]
            [clojure.pprint :refer [pprint]]
            [flatland.ordered.map :refer :all]

            [crustacean.schemas :refer [Entity]]
            [crustacean.generators :as g]))


(s/defn tx-markers
  "Transaction for an entity's txMarkers"
  [entity :- Entity]
  [ {:db/id (d/tempid :db.part/db)
     :db/ident (keyword (:namespace entity) "txCreated")
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/many
     :db/index true
     :db.install/_attribute :db.part/db}

    {:db/id (d/tempid :db.part/db)
     :db/ident (keyword (:namespace entity) "txUpdated")
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/many
     :db/index true
     :db.install/_attribute :db.part/db}

    {:db/id (d/tempid :db.part/db)
     :db/ident (keyword (:namespace entity) "txDeleted")
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/many
     :db/index true
     :db.install/_attribute :db.part/db}])

(s/defn db-functions
  "The db functions we need for an entity"
  [entity :- Entity]
  [{:db/id (d/tempid :db.part/user)
    :db/ident (keyword (:namespace entity) "malformed?")
    :db/fn (g/->malformed?* entity)}
   {:db/id (d/tempid :db.part/user)
    :db/ident (keyword (:namespace entity) "exists?")
    :db/fn (g/->exists?* entity)}
   {:db/id (d/tempid :db.part/user)
    :db/ident (keyword (:namespace entity) "create")
    :db/fn (g/->create* entity)}])

(s/defn initial-txes
  "The txes we need when an entity is first created"
  [entity :- Entity]
  (concat (tx-markers entity)
          (datomic-schema/generate-schema d/tempid [entity])
          (:extra-txes entity)
          (db-functions entity)))

(s/defn migration-txes
  "The txes needed to migrate old-entity to new-entity

   Currently, very primitive"
  [old-entity :- Entity
   new-entity :- Entity]
    (let [nm (:namespace new-entity)
        [added-fields deleted-fields common-fields] (data/diff (set (keys (:fields new-entity))) (set  (keys (:fields old-entity))))]
      (concat
       ;; Special case if we add and delete only one field and it's the same type --- we rename the field
       (if (and (= 1 (count added-fields))
                (= 1 (count deleted-fields))
                (= (get new-entity (first added-fields)) (get old-entity (first deleted-fields))))
         [{:db/id (keyword nm (first deleted-fields))
           :db/ident (keyword nm (first added-fields))}]

         (concat   ;; TODO: figure out if we modified any properties of existing fields
          ;; Add new fields
          (datomic-schema/generate-schema d/tempid [(assoc new-entity :fields (select-keys (:fields new-entity) added-fields))])

          ;; Delete fields
          (for [field deleted-fields]
            {:db/id (keyword nm (first deleted-fields))
             :db/ident (keyword "unused" (str nm "/" field))})))

       (db-functions new-entity)
       (:extra-txes new-entity))))

(defn- migrations->schema
  "Generate a schema from a vec of migrations"
  [migrations] ;; this is an ordered map of version => entity at a point in time
  (->>
   (conj
    ;; generate migration for each pair of entities (overlapping)
    (for [[[old-k old-entity] [new-k new-entity]] (partition 2 1 migrations)]
      [(keyword (str (:name new-entity) "-" new-k)) {:txes [(migration-txes old-entity new-entity)]}])
    ;; generate migration for first entity
    (let [[k entity] (first migrations)]
      [(keyword (str (:name entity) "-" k)) {:txes [(conj (initial-txes entity))]}]))
   (into (ordered-map))))

(s/defn sync-migrations
  "Syncs an entity's migrations to a file. Returns a schema used for the migration"
  ([entity :- Entity]
   (sync-migrations entity false))
  ([entity :- Entity
    save :- s/Bool]
   ;; Only sync migrations when we specify a migrations file
   (if-let [filename (:migration-file entity)]
     (if (.exists (as-file filename))
       ;; File exists so update it
       (let [migrations (read-string (slurp filename))
             [last-key last-entity] (last migrations)
             migrations (if (= (:migration-version entity) (:migration-version last-entity))
                          ;;If the hash hasn't chnaged, no need to update migrations
                          migrations
                          ;; otherwise computer the diff
                          (do
                            ;; TODO there's a bug here
                            #_(throw (Exception. (str  "Migrations for (:name entity)" " not saved. Save the migrations")))
                            (assoc migrations (:migration-version entity) entity)))]
         (when save
           (spit filename (pr-str migrations)))
         (migrations->schema migrations))
     ;; File doesn't exist,
     (let [migrations (ordered-map (:migration-version entity) entity)]
       (when save
         (spit filename (pr-str migrations)))
       (migrations->schema migrations)))
   ;; If we're not saving the migrations, just return a schema
     (migrations->schema (ordered-map  (:migration-version entity) entity)))))
