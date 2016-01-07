(ns crustacean.migrations
  (:require [schema.core :as s]
            [datomic.api :as d]
            [clojure.java.io :refer [as-file]]
            [clojure.edn :as edn]
            [clojure.data :as data]
            [datomic-schema.schema :as datomic-schema]
            [clojure.pprint :refer [pprint]]
            [flatland.ordered.map :refer :all]
            [io.rkn.conformity :as c]

            [crustacean.schemas :refer [Entity]]
            [crustacean.utils :refer [spit-edn]]))


(defn tx-markers
  "Transaction for an entity's txMarkers"
  [entity]
  [{:db/id (d/tempid :db.part/db)
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

(defn db-functions
  "The db functions we need for an entity"
  [model]
  [{:db/id (d/tempid :db.part/user)
    :db/ident (keyword (:namespace model) "malformed?")
    :db/fn (get-in model [:db-funds :malformed?*])}
   {:db/id (d/tempid :db.part/user)
    :db/ident (keyword (:namespace model) "exists?")
    :db/fn (get-in model [:db-funcs :exists?*])}
   {:db/id (d/tempid :db.part/user)
    :db/ident (keyword (:namespace model) "create")
    :db/fn (get-in model [:db-funcs :create*])}])

(defn initial-txes
  "The txes we need when an entity is first created"
  [entity]
  (concat (tx-markers entity)
          (datomic-schema/generate-schema d/tempid [entity])
          (:extra-txes entity)
          (db-functions entity)))

(defn migration-txes
  "The txes needed to migrate old-entity to new-entity"
  [old-entity new-entity]
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

(defn get-migrations
  "Retrieve an entity's migrations"
  [entity]
  (if-let [file (-> entity :migration-file clojure.java.io/resource)]
    (read-string (slurp file))
    (throw (Exception. (str "Migration has no file:" (:name entity))))))

(defn write-entity
  "The parts of an entity we want to save in migrations"
  [entity]
  (select-keys entity [:fields :name :basetype :namespace]))

(defn write-migrations
  "Write an entity's migrations to its migration file"
  [entity]
  (let [key-str (str (:name entity) "-" (.format (doto (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss")
                                                   (.setTimeZone (java.util.TimeZone/getTimeZone "UTC")))
                                                 (java.util.Date.)))]
    (if-let [file-path (:migration-file entity)]
      (if-let [file (clojure.java.io/resource file-path)]
        (let [migrations (read-string (slurp file))
              last-entity (:entity (last migrations))]
          (spit-edn (str "resources/" file-path) (assoc migrations
                                                        key-str
                                                        {:entity (write-entity entity)
                                                         :txes [(migration-txes last-entity entity)]})))
        (spit-edn (str "resources/" file-path) (ordered-map
                                                 key-str
                                                 {:entity (write-entity entity) :txes [(initial-txes entity)]})))
      (throw (Exception. (str "Migration has no file asdf sadf sadf :" (:name entity)))))))

;; This is a macro so we can resolve the namespace
(defn sync-entity
  "Ensure that the database confirms to an entity's norms"
  [conn entity]
  (let [migrations (get-migrations entity)
        [_ {last-entity :entity}] (last migrations)]
    (when-not (= (:fields last-entity) (:fields entity))
      (throw (Exception. (str "Entity missing migration. Please run `lein migrate " (:name entity) "`"))))
    (c/ensure-conforms conn migrations)))
