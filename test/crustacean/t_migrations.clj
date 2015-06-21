(ns crustacean.t_migrations
  (:require [midje.sweet :refer :all]
            [datomic.api :as d]
            [io.rkn.conformity :as c]
            [clojure.java.io :refer [delete-file]]
            [clojure.java.io :refer [as-file]]

            [crustacean.core :refer :all]
            [crustacean.migrations :refer :all]))


;; From https://github.com/clojure/core.incubator/blob/master/src/main/clojure/clojure/core/incubator.clj#L56
(defn dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))



;; This is hacky


(def db-name "datomic:mem://migration-test")
(def migration-file "resources/testdata/migrations.edn")



(defentity user 
  (:migration-file migration-file)
  (:migration-version "0")
  (:fields   [email      :string :unique-value :indexed :assignment-required]
             [servers    :ref     :many :indexed :component]
             [web-token   :string  :unique-value :indexed]
             [agent-token :string  :unique-value :indexed])
  (:defaults [web-token (fn [] (.toString (BigInteger. 256 (java.security.SecureRandom.)) 32))]
             [agent-token  (fn [] (.toString (BigInteger. 256 (java.security.SecureRandom.)) 32))])
  (:validators [email #"@"]))

(def schema (sync-migrations user))
(with-state-changes [(before :facts (do
                                      (d/delete-database db-name)
                                      (d/create-database db-name)
                                      (when (.exists (as-file migration-file))
                                        (delete-file migration-file))
                                      (c/ensure-conforms (d/connect db-name) (sync-migrations user true))))
                     (after :facts (when (.exists (as-file migration-file))
                                     (delete-file migration-file)))]

  (fact "lol")
  (facts "about sync-migrations"
    #_(fact "can add fields"
      (let [conn (d/connect db-name)
            updated-entity (-> (assoc-in user [:fields "name"] [:string #{:indexed :assignment-required}])
                               (assoc :migration-version "1"))]
        (d/pull  (d/db conn) '[:db/ident] :user/email) => {:db/ident :user/email}
        (d/pull  (d/db conn) '[:db/ident] :user/name) => nil

        (c/ensure-conforms conn (sync-migrations updated-entity true))
        (d/pull (d/db conn) '[:db/ident] :user/name) => {:db/ident :user/name}))
    
    (fact "can delete fields"
      (let [conn (d/connect db-name)
            updated-entity (-> (dissoc-in user [:fields "email"])
                               (assoc :migration-version "2"))]
          (c/ensure-conforms conn (sync-migrations updated-entity true))
          (d/pull  (d/db conn) '[:db/ident] :user/email) => {:db/ident :unused/user/email}))
    #_(fact "can rename a field"
      (let [conn (d/connect db-name)
            updated-entity (-> (dissoc-in user [:fields "email"])
                               (assoc-in [:fields "email2"] [:string #{:unique-value :indexed :assignment-required}])
                               (assoc :migration-version "3"))
            [_ userid] (first  (:tempids @(d/transact conn [{:db/id (d/tempid :db.part/user) :user/email "test@example.com"}])))]
        (c/ensure-conforms conn (sync-migrations updated-entity true))
        (d/pull  (d/db conn) '[:user/email2] userid) => {:user/email2 "test@example.com"}))
    (fact "it only saves when called with a second parameter"
        (sync-migrations user true)
        (let [migrations (slurp migration-file)]
          (sync-migrations (assoc-in user [:fields "name"] [:string #{:indexed :assignment-required}]))
          (slurp migration-file) => migrations
          (sync-migrations (assoc-in user [:fields "name"] [:string #{:indexed :assignment-required}]) true) =not=> migrations)))
  
  )
