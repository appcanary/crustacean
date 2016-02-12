(ns crustacean.migrations
  (:require [clojure.string :as string]
            [datomic.api :as d]
            [clojure.java.io :refer [as-file]]
            [clojure.data :as data]
            [datomic-schema.schema :as datomic-schema]
            [clojure.pprint :refer [pprint]]
            [io.rkn.conformity :as c]
            [clojure.java.io :as io]
            [cpath-clj.core :as cp]

            [crustacean.db-funcs :as db-funcs]
            [crustacean.utils :refer [spit-edn unique-fields]]))
;; USAGE
;; new-migration for when your model has changed
;; sync-model or sync-all-models to get your db up to speed

;; A dynamic var to hold all the models as they are defined
(defonce ^:dynamic *models* {})

(def date-format
  "The date format we use for migration filenames"
  (doto (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss")
    (.setTimeZone (java.util.TimeZone/getTimeZone "UTC"))))

(defn model->edn
  "Serialize the model when writing to a migration file"
  [model]
  (-> (select-keys model [:name :fields :db-functions])
      (assoc :defaults (read-string (:raw-defaults model))
             :validators (read-string (:raw-validators model)))))

;; We tag transactions that create/update/delete an entity
;; TODO txUpdated and txDeleted aren't fully implemented
;; you can use them yourself when transacting 'raw' datomic
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

(defn default-db-functions
  "The default db functions for a model: exists?, create, and malformed?"
  [model]
  [{:db/id (d/tempid :db.part/user)
    :db/ident (keyword (:namespace model) "exists?")
    :db/fn (db-funcs/exists-fn model)}
   {:db/id #db/id[:db.part/user]
    :db/ident (keyword (:namespace model) "malformed?")
    :db/fn (db-funcs/malformed-fn model)}
   {:db/id (d/tempid :db.part/user)
    :db/ident (keyword (:namespace model) "create")
    :db/fn (db-funcs/create-fn model)}
   {:db/id (d/tempid :db.part/user)
    :db/ident (keyword (:namespace model) "upsert")
    :db/fn (db-funcs/upsert-fn model)}])

(defn db-functions-txes
  "The db functions txes for a model"
  [{:keys[db-functions] :as model}]
  (for [[fn-name fn] db-functions]
    {:db/id #db/id[:db.part/user]
     :db/ident (keyword (:namespace model) fn-name)
     :db/fn fn}))

(defn initial-txes
  "The txes we need when an model is first created"
  [model]
  (concat (tx-markers model)
          (datomic-schema/generate-schema d/tempid [model])
          (db-functions-txes model)
          (default-db-functions model)))


(defn migration-txes
  "Generate the txes needed to go from the last migration to the new model"
  [last-migration model regenerate-dbfuncs?]
  (let [old-model (:model last-migration)
        new-model (model->edn model)
        nm (:name model)]

    ;; We compare the printed representation because validators can have regular expressions and
    ;; (= #"*" #"*") is false. Representing as a string circumvents that.
    (if (= (pr-str old-model) (pr-str new-model))
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
             (do
               (println "Renaming " (first deleted-fields) "to" (first added-fields))
               [{:db/id    (keyword nm (first deleted-fields))
                 :db/ident (keyword nm (first added-fields))}])

             ;; Otherwise generate created and deleted fields
             ;; If you change the property of an existing field such as cardinality or history
             ;; you have to write the schema alteration yourself http://docs.datomic.com/schema.html#Schema-Alteration
             (concat   ;; TODO: figure out if we modified any properties of existing fields
              ;; Add new fields
              (datomic-schema/generate-schema d/tempid [(assoc model :fields (select-keys (:fields new-model) added-fields))])

              ;; Delete fields
              ;; You can't delete fields in datomic, so we move them to the :unused namespace
              ;; TODO what happens when you delete something with the same name 2x?
              (for [field deleted-fields]
                {:db/id (keyword nm (first deleted-fields))
                 :db/ident (keyword "unused" (str nm "/" field))})))))

       ;; if db-funs have changed, regenerate them
       ;; Compare with pr-str because database functions are objects
       (when (not= (pr-str (:db-functions old-model)) (pr-str (:db-functions new-model)))
         (println "Generating migrations for modified database-functions")
         (db-functions-txes model))

       ;; if the fields, or validators, or default values have changed
       ;; regenerate the default-db-functions
       (when (or regenerate-dbfuncs?
                 (not= (:fields old-model) (:fields new-model))
                 (not= (pr-str (:validators old-model)) (pr-str (:validators new-model)))
                 (not= (pr-str (:defaults old-model)) (pr-str (:defaults new-model))))
         (println "Generating migrations for default database functions")
         (default-db-functions model))))))

(defn get-migrations
  "Retrieve an model's migrations, returns map {modelname-date {:model model :txes [...]}}"
  [{:keys [migration-dir] :as model}]

  (let [migrations (sort-by first (cp/resources migration-dir))]
    (for [[filename [uri]] migrations :when (re-matches #"^.*\.edn$" filename)]
      (let [[_ base-name] (re-matches #"/?(.*)\.edn" filename)  ;; Drop the starting / (if there) and the .edn extension
            k (str (:name model) "-" base-name)]
        [k (assoc (read-string  (slurp uri))
                  :date base-name)]))))

(defn migration-filename
  "Returns a filename using today's date"
  [migration-name]
  (let [date (.format date-format (java.util.Date.))]
    (if migration-name
      (str date ".edn")
      (str date "-" migration-name ".edn"))))

(defn new-migration
  "Write a new migration for a model"
  ([model]
   (new-migration nil false))
  ([model migration-name]
   (new-migration migration-name false))
  ([{:keys [migration-dir] :as model} migration-name regenerate-dbfuncs?]
   (when (nil? migration-dir)
     (throw (Exception. (str (:name model) " has no migration directory"))))

   (let [serialized-model (model->edn model) ; we only write a part of the model to the migrations
         migration-path (io/file "resources" migration-dir (migration-filename migration-name))
         migrations (get-migrations model)
         last-migration (second (last migrations))]

     ;; Make the migration dir in case it doesn't exist
     (println "Writing new migration to" (.getPath migration-path))
     (io/make-parents migration-path)

     ;; If we have a previous migration, compute the diff for the txes, otherwise just use the initial txes
     (if last-migration
       (spit-edn migration-path {:model serialized-model
                                 :txes [(migration-txes last-migration model regenerate-dbfuncs?)]})
       (spit-edn migration-path {:model serialized-model
                                 :txes [(initial-txes model)]})))))

(defn update-crustacean-migration
  "Generates a migration when crustacean dbfuncs need to be updates"
  [model]
  (new-migration model "update-crustacean" true))

(defn ensure-migrations-up-to-date
  "Check that an entity's migrations are up to date. Throws an error if it's not. Optionally specify the model var to throw a more detailed error message"
  [model migrations & [model-var]]
  (let [[_ {last-model :model}] (last migrations)]
    (when-not (= (:fields last-model) (:fields model))
      (throw (Exception. (str "Entity missing migration. Please run `lein migrate " (or model-var (:name model)) "`"))))))

(defn sync-model
  "Ensure that the database confirms to an entity's norms. Optionally set the var of the model (for better error messages) "
  [conn model & [model-var]]
  (let [migrations (get-migrations model)]
    (ensure-migrations-up-to-date model migrations)
    (doseq [[migration-name migration] migrations]
      (c/ensure-conforms conn {migration-name migration}))))


(defn sync-all-models
  "Syncs all the models we know about (in *models*)"
  [conn]
  (let [all-migrations (mapcat (fn [[model-var model]]
                                 (let [migrations (get-migrations model)]
                                   ;; Make sure that every model has fully up to date migrations
                                   (ensure-migrations-up-to-date model migrations model-var)
                                   migrations))
                               *models*)
        ;; On a fresh system we want to apply all migrations in date order
        ;; Sort all the migrations by date
        sorted-migrations (sort-by #(.parse date-format (:date (second %))) all-migrations)]
    (doseq [[migration-name migration] sorted-migrations]
      (c/ensure-conforms conn {migration-name migration}))))
