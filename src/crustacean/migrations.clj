(ns crustacean.migrations
  (:require [schema.core :as s]
            [clojure.string :as string]
            [datomic.api :as d]
            [clojure.java.io :refer [as-file]]
            [clojure.edn :as edn]
            [clojure.data :as data]
            [datomic-schema.schema :as datomic-schema]
            [clojure.pprint :refer [pprint]]
            [flatland.ordered.map :refer :all]
            [io.rkn.conformity :as c]
            [clojure.java.io :as io]
            [puget.printer :as puget]

            [crustacean.db-funcs :as db-funcs]
            [crustacean.utils :refer [spit-edn unique-fields]]))


;; A dynamic var to hold all the models as they are defined
(def ^:dynamic *models* {})

(defn serialize-model
  "Serialize the model when writing to a migration file"
  [model]
  (-> (select-keys model [:name :fields :db-functions])
      (assoc :defaults (read-string (:raw-defaults model))
             :validators (read-string (:raw-validators model)))))

(defn tx-markers
  "Transaction for an model's txMarkers"
  [model]
  [{:db/id #db/id[:db.part/db]
    :db/ident (keyword (:namespace model) "txCreated")
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/index true
    :db.install/_attribute :db.part/db}

   {:db/id #db/id[:db.part/db]
    :db/ident (keyword (:namespace model) "txUpdated")
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/index true
    :db.install/_attribute :db.part/db}

   {:db/id #db/id[:db.part/db]
    :db/ident (keyword (:namespace model) "txDeleted")
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/index true
    :db.install/_attribute :db.part/db}])

(defn automated-db-functions
  "The automatically generated db functions for a model, exists?, create, and malformed?"
  [model]
  [{:db/id (d/tempid :db.part/user)
    :db/ident (keyword (:namespace model) "exists?")
    :db/fn (db-funcs/exists-fn model)}
   {:db/id #db/id[:db.part/user]
    :db/ident (keyword (:namespace model) "malformed?")
    :db/fn (db-funcs/malformed-fn model)}
   {:db/id (d/tempid :db.part/user)
    :db/ident (keyword (:namespace model) "create")
    :db/fn (db-funcs/create-fn model)}])

(defn db-functions-txes
  "The db functions txes for a model"
  [{:keys[db-functions] :as model}]
  (for [[fn-name fn] db-functions]
    {:db/id #db/id[:db.part/db]
     :db/ident (keyword (:namespace model) fn-name)
     :db/fn fn}))

(defn initial-txes
  "The txes we need when an model is first created"
  [model]
  (concat (tx-markers model)
          (datomic-schema/generate-schema d/tempid [model])
          (db-functions-txes model)
          (automated-db-functions model)))


(defn migration-txes
  "Generate the txes needed to go from the last migration to the new model"
  [last-migration model]
  (let [old-model (:model last-migration)
        new-model (serialize-model model)
        nm (:name model)]
    (if
      ;; We compare the printed representation because validators can have regular expressions and
      ;; (= #"*" #"*") is false. Representing as a string circumvents that.
      (= (pr-str old-model) (pr-str new-model))
      (do (println "Nothing to migrate, generating empty migration")
          [])

      ;; Otherwise we generate migrations we need
      (concat
       ;; If the fields are different we have to create or delete fields
       (when (not= (:fields old-model) (:fields new-model))
         (let [[added-fields deleted-fields common-fields] (data/diff (set (keys (:fields new-model))) (set  (keys (:fields old-model))))]
           (println "Generating migrations for modified fields")
           ;; Special case if we add and delete only one field and it's the same type --- we rename the field
           (if (and (= 1 (count added-fields))
                    (= 1 (count deleted-fields))
                    (= (get new-model (first added-fields)) (get old-model (first deleted-fields))))
             [{:db/id (keyword nm (first deleted-fields))
               :db/ident (keyword nm (first added-fields))}]

             ;; Otherwise generate created and deleted fields
             (concat   ;; TODO: figure out if we modified any properties of existing fields
              ;; Add new fields
              (datomic-schema/generate-schema d/tempid [(assoc model :fields (select-keys (:fields new-model) added-fields))])

              ;; Delete fields
              (for [field deleted-fields]
                {:db/id (keyword nm (first deleted-fields))
                 :db/ident (keyword "unused" (str nm "/" field))})))))

       ;; Compare with pr-str because database functions are objects
       (when (not= (pr-str (:db-functions old-model)) (pr-str (:db-functions new-model)))
         (println "Generating migrations for modified database-functions")
         (db-functions-txes model))

       (when (or (not= (:fields old-model) (:fields new-model))
                 (not= (pr-str (:validators old-model)) (pr-str (:validators new-model)))
                 (not= (pr-str (:defaults old-model)) (pr-str (:defaults new-model))))
         (println "Generating migrations for automatically generated database functions")
         (automated-db-functions model))))))

(defn get-migrations
  "Retrieve an model's migrations"
  [{:keys [migration-dir] :as model}]

  (let [migrations (some-> migration-dir io/resource io/file file-seq)
        ;; The first value in migrations is the parent directory itself because
        ;; of how `file-seq` works
        migrations (drop 1 migrations)
        ]
    (into {}
          (for [migration-file migrations]
            (let [base-name (get (string/split (.getName migration-file) #"\.") 0) ;; Drop the .edn extension
                  k (str (:name model) "-" base-name)]
              [k (read-string  (slurp migration-file))])))))

(defn migration-filename
  "Returns a filename using today's date"
  []
  (-> ;; Format date (in UTC)
   (doto (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss")
     (.setTimeZone (java.util.TimeZone/getTimeZone "UTC")))
   (.format (java.util.Date.))
   (str  ".edn")))

(defn new-migration
  "Write a new migration for a model"
  [{:keys [migration-dir] :as model}]
  (when (nil? migration-dir)
    (throw (Exception. (str (:name model) " has no migration directory"))))
  (let [serialized-model (serialize-model model) ; we only write a part of the model to the migrations
        migration-path (io/file "resources" migration-dir (migration-filename))
        migrations (get-migrations model)
        ;; Maps don't maintain insertion order, so make sure we get the last migration.
        last-migration (get migrations (last (sort (keys migrations))))]
    ;; Make the migration dir in case it doesn't exist
    (println "Writing new migration to" (.getPath migration-path))
    (io/make-parents migration-path)
    ;; If we have a previous migration, compute the diff for the txes, otherwise just use the initial txes
    (if last-migration
      (spit-edn migration-path {:model serialized-model
                                :txes [(migration-txes last-migration model)]})
      (spit-edn migration-path {:model serialized-model
                                :txes [(initial-txes model)]}))))

(defn sync-model
  "Ensure that the database confirms to an entity's norms. Optionally set the var of the model (for better error messages) "
  [conn model & [model-var]]
  (let [migrations (get-migrations model)
        [_ {last-model :model}] (last migrations)]
    (when-not (= (:fields last-model) (:fields model))
      (throw (Exception. (str "Entity missing migration. Please run `lein migrate " (or model-var (:name model)) "`"))))
    (c/ensure-conforms conn migrations)))

(defn sync-all-models
  "Syncs all the models we know about (in *models*)"
  [conn]
  (doseq [[model-var model] *models*]
    (sync-model conn model model-var)))
