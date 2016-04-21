(ns crustacean.db-funcs
  (:require [datomic.api :as d]
            [crustacean.utils :refer [entity-exists? fields-with unique-fields remove-nils]]))


(defn generate-tx-map
  "Generates a tx map for a crustacean model. Assumes symbols 'db 'id and 'input are available. Optionally specify whether to include default values or not"
  ([model]
   (generate-tx-map model true))
  ([model include-defaults?]
   `(into {:db/id ~'id}
          (concat
           (for [[field# [type# opts#]] ~(:fields model)]
             (let [namespaced-field#  (keyword ~(:namespace model) field#)

                   defaults# ~(read-string (:raw-defaults model))

                   val# (cond (opts# :assignment-required)
                              (get ~'input (keyword field#))

                              (and (opts# :assignment-permitted) (contains? ~'input (keyword field#)))
                              (get ~'input (keyword field#))

                              (and ~include-defaults? (contains? defaults# field#))
                              (let [default# (get defaults# field#)]
                                (if (fn? default#)
                                  (default# ~'db ~'input)
                                  default#)))]
               (when (not (nil? val#))
                 [namespaced-field# val#])))
           (for [field# (keys ~(:backrefs model))]
             (when-let [val# (get ~'input field#)]
               [field# val#]))))))

(defn create-fn
  "The `create` database function for a given entity"
  [entity]
  (d/function
   `{:lang :clojure
     :params [~'db ~'id ~'input]
     :code
     (if-let [malformed# (d/invoke ~'db (keyword ~(:namespace entity) "malformed?") ~'input)]
       (throw (IllegalArgumentException. (str malformed#)))
       (if (d/invoke ~'db (keyword ~(:namespace entity) "exists?") ~'db ~'input)
         (throw (IllegalStateException. "entity already exists"))

         (vector
          ~(generate-tx-map entity)
          [:db/add (d/tempid :db.part/tx) ~(keyword (:namespace entity) "txCreated") ~'id] ;;annotate the transactionx
          )))}))

(defn upsert-fn
  "The `upsert` database function for the model"
  [model]
  (d/function
   `{:lang :clojure
     :params [~'db ~'id ~'input]
     :code
     (if-let [malformed# false #_(d/invoke ~'db (keyword ~(:namespace model) "malformed?") ~'input)]
       ;; Temporarily getting rid of this malformed? check because we may want to update entities in situations without specifying required keys (or specifying keys that aren't permitted)
       ;; I need to rewrite how malformed is handled for upserts/updates
       (throw (IllegalArgumentException. (str malformed#)))

       (if-let [entity-id# (if (= Long (class ~'id))
                                        ; If the id is a long it's not a tempid so it's an update
                                        ; tempids are datomic.db.DbId
                             ~'id
                             ;; Maybe the entity exists
                             (d/invoke ~'db (keyword ~(:namespace model) "exists?") ~'db ~'input))]
         (let [entity# (d/entity ~'db entity-id#)
               txes# (->> (for [[k# v#] ~'input]
                           (let [namespaced-key# (keyword ~(:namespace model) (name k#))
                                 [type# opts#] (get ~(:fields model) (name k#))
                                 current# (cond->> (get entity# namespaced-key#)

                                            (= :ref type#)
                                            (map :db/id))]
                             (cond (and (nil? v#) current#)
                                   [[:db/retract ~'id namespaced-key# v#]]

                                   (contains? opts# :many)
                                   (let [[to-add# to-retract#] (clojure.data/diff (set v#) current#)]
                                     (concat
                                      (for [x# to-add#]
                                        [:db/add ~'id namespaced-key# x#])
                                      (for [x# to-retract#]
                                        [:db/retract ~'id namespaced-key# x#])))

                                   (not= v# current#)
                                   (do (println v# current#)
                                       [[:db/add ~'id namespaced-key# v#]]))))
                         (apply concat)
                         (remove nil?))]
           (when (not-empty txes#)
             (conj txes#
                   [:db/add (d/tempid :db.part/tx) ~(keyword (:namespace model) "txUpdated") ~'id])))
         (vector
          ~(generate-tx-map model)
          [:db/add (d/tempid :db.part/tx) ~(keyword (:namespace model) "txCreated") ~'id] ;;annotate the transaction
          )))}))

(defn exists-fn
  "The `exists?` database function for a given entity"
  [entity]
  (d/function
   `{:lang :clojure
     :params [db# input#]
     :code (let [unique-key-pairs# (->> [~@(unique-fields entity)]
                                        (filter #(contains? input# (keyword %)))
                                        (map (juxt #(keyword ~(:namespace entity) %) #(get input# (keyword %)))))

                 composite-key-pairs# (->> ~(:composite-keys entity)
                                           (filter (fn [[a# b#]] (and (contains? input# a#) (contains? input# b#))))
                                           (map (fn [[a# b#]]
                                                  [(keyword ~(:namespace entity) (name a#)) (get input# a#)
                                                   (keyword ~(:namespace entity) (name b#)) (get input# b#)])))]
             (or
              (d/q {:find '[~'?e .] :in '[~'$ [[~'?attr ~'?value]]] :where '[[~'?e ~'?attr ~'?value]]} db# unique-key-pairs#)
              (some identity
                    (mapcat (fn [[attr1# value1# attr2# value2#]]
                              (d/q {:find '[~'?e .] :in '[~'$ ~'?value1 ~'?value2] :where `[[~'~'?e ~attr1# ~'~'?value1] [~'~'?e  ~attr2# ~'~'?value2]]} db# value1# value2#))
                            composite-key-pairs#))))}))

(defn malformed-fn
  "The malformed? database function for a given entity"
  [{:keys [raw-input-schema] :as model}]
  (d/function
   `{:lang :clojure
     :requires [[schema.core]]
     :params [input#]
     :code (let [checker#  (schema.core/checker (eval ~(read-string raw-input-schema)))]
             (checker# input#))}))
