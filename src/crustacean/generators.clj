(ns crustacean.generators
  (:require [datomic-schema.schema :as ds :refer [defschema]]
            [schema.core :as s]
            [datomic.api :as d]
            [clojure.string :refer [capitalize]]

            [crustacean.schemas :refer :all]
            [crustacean.utils :refer [normalize-keys entity-exists?]]))

(s/defn fields-with
  "Returns names of fields in an entity with given option set"
  [entity :- Entity
   attr  :- FieldOpt]
  (->> (:fields entity)
       (filter (fn [[field [type opts]]]
                 (opts attr)))
       (map first)))

(s/defn unique-fields
  "Returns the name of fields set to unique "
  [entity :- Entity]
  (concat (fields-with entity :unique-value)
    (fields-with entity :unique-identity)))

(defn remove-nils
  "Remove keys with nil values"
  [input-map]
  (into {} (remove #(nil? (second %)) input-map)))



;; ## Entity Validation

(s/defn field-spec->schema
  "Convert a field spec to a prismatic schema"
  [[type opts] :- FieldSpec]
  (let [schema (case type
                 :keyword `s/Keyword
                 :string `s/Str
                 :boolean `s/Bool
                 :long `s/Int
                 :bigint `s/Int
                 :float `Float
                 :double `Double
                 :bigdec `s/Int
                 :ref `(s/either s/Int {s/Keyword s/Any})
                 :instant `s/Inst
                 :uuid `s/Uuid
                 :uri `java.net.URI
                 :bytes `bytes
                 :fn `s/Any ;todo can we narrow this down
                 :enum  `s/Any ;todo improve (apply s/enum (first (filter vector? opts)))
                 )]
    (if (contains? opts :many)
      `(s/either ~schema ~(vector schema))
      schema)))

(s/defn ->input-schema*
  "The syntactic representation of a prismatic schema for an input, generated from a given entity.
   We return don't evaluate, and return syntax here because these
   schemas are going to be inserted into datomic database functions,
   which are stored as unevaluated forms.

   To generate an actual schema, see `->input-schema`"
  [entity :- Entity]
  (let [assignment-required (fields-with entity :assignment-required)
        assignment-permitted (fields-with entity :assignment-permitted)]
    (into {}
          (concat (for [[field spec] (select-keys (:fields entity) (concat assignment-permitted assignment-required))]
                    (let [key (if (contains? (second spec) :assignment-required)
                                (keyword field)
                                `(s/optional-key ~(keyword field)))
                          schema (field-spec->schema spec)
                          val (if-let [validator (get (:validators entity) field)]
                                (if (list? validator)
                                  `(s/both ~schema (s/pred ~validator))
                                  `(s/both ~schema ~validator))
                                schema)]
                      [key val]))

                  (for [[field assignment] (:backrefs entity)]
                    (if (= :assignment-required assignment)
                      [field (field-spec->schema [:ref #{}])]
                      [`(s/optional-key ~field) (field-spec->schema [:ref #{}])]))))))

(s/defn ->input-schema
  "The input schema for a given entity"
  [entity :- Entity]
  (s/schema-with-name (eval (->input-schema* entity)) (str (capitalize (:name entity)) "In")))

(s/defn ->output-schema
  "The output schema for a given entity"
  [entity :- Entity]
  (-> (into {:id Long
             s/Keyword s/Any}
            (for [[field spec] (:fields entity)]
              [(s/optional-key (keyword field)) (if (= :ref spec) Ref (eval (field-spec->schema spec)))]))

      (s/schema-with-name (str (capitalize (:name entity)) "Out"))))

(s/defn ->malformed?*
  "The malformed? database function for a given entity"
  [entity :- Entity]
  (d/function
   `{:lang :clojure
     :requires [[schema.core]]
     :params [input#]
     :code (let [checker#  (schema.core/checker (eval ~(->input-schema* entity)))]
             (checker# input#))}))

(s/defn ->malformed?
  "The `malformed?` function for a given entity"
  [entity :- Entity]
  (s/checker (->input-schema entity)))

(s/defn ->exists?*
  "The `exists?` database function for a given entity"
  [entity :- Entity]
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
             (seq (concat
                   (d/q {:find '[[~'?e ...]] :in '[~'$ [[~'?attr ~'?value]]] :where '[[~'?e ~'?attr ~'?value]]} db# unique-key-pairs#)
                   (d/q {:find '[[~'?e ...]] :in '[~'$ [[~'?attr1 ~'?value1 ~'?attr2 ~'value2]]] :where '[[~'?e ~'?attr1 ~'?value1] [~'?e ~'?attr2 ~'?value2]]} db# composite-key-pairs#))))}))

(s/defn ->exists?
  "The `exits?` function for a given entity"
  [entity :- Entity]
  (fn [db input]
    (d/invoke db (keyword (:namespace entity) "exists?") db input)))

;; Entity Creation

(s/defn ->create*
  "The `create` database function for a given entity"
  [entity :- Entity]
  (let [fields (:fields entity)
        defaults (:defaults entity)
        backrefs (:backrefs entity)]
    (d/function
     `{:lang :clojure
       :params [db# id# input#]
       :code
       (if-let [malformed# (d/invoke db# (keyword ~(:namespace entity) "malformed?") input#)]
         (throw (IllegalArgumentException. (str malformed#)))
         (if (d/invoke db# (keyword ~(:namespace entity) "exists?") db# input#)
           (throw (IllegalStateException. "entity already exists"))
           (vector (into {:db/id id#}
                         (concat
                          (for [[field# [type# opts#]] ~fields]
                            (let [namespaced-field#  (keyword ~(:namespace entity) field#)

                                  defaults# ~(:defaults entity)

                                  val# (cond (opts# :assignment-required)
                                             (get input# (keyword field#))

                                             (and (opts# :assignment-permitted) (contains? input# (keyword field#)))
                                             (get input# (keyword field#))

                                             (contains? defaults# field#)
                                             (let [default# (get defaults# field#)]
                                               (if (fn? default#)
                                                 (default#)
                                                 default#)))]
                              (when (not (nil? val#))
                                [namespaced-field# val#])))
                          (for [field# (keys ~backrefs)]
                            (when-let [val# (get input# field#)]
                              [field# val#]))))
                   [:db/add (d/tempid :db.part/tx) ~(keyword (:namespace entity) "txCreated") id#] ;;annotate the transactionx
                   )))})))
(s/defn ->create
  "The `create!` function for a given entity"
  [entity :- Entity]
  (let [input-schema (->input-schema entity)
        create-fn (keyword (:namespace entity) "create")]
    (fn [conn dirty-input]
      (let [input (remove-nils dirty-input)]
        (s/validate input-schema input)
        (assert (not ((->exists? entity) (d/db conn) input)))
        (let [tempid (d/tempid :db.part/user -1)
              {:keys [tempids db-after]} @(d/transact conn [[create-fn tempid input]
                                                            ])
              id (d/resolve-tempid db-after tempids tempid)]
          (-> (d/pull db-after (if-let [view (get-in entity [:views :one])]
                                 view
                                 '[*]) id)
            entity-exists?
            normalize-keys))))))

;; ## Entity Access
(defn where-clauses
  "Generate where clauses for based on a list of attribute values or attributes"
  [entity arg-pairs]
  (vec (mapcat (fn [arg-pair]
                 (if (and (= 2 (count arg-pair)) (second arg-pair))
                   (let [[attr value] arg-pair
                         sym (gensym "?")]
                     (if (re-find #"^_" (name attr))
                                        ; if the attr is a backreference (:foo/_bar) look it up as a backreference
                       `[[~sym ~(keyword (namespace attr) (.substring (name attr) 1)) ~'?e]
                         [(~'ground ~value) ~sym]]
                       `[[~'?e ~(keyword (:namespace entity) (name attr)) ~sym]
                         [(~'ground ~value) ~sym]]))
                   `[[~'?e ~(keyword (:namespace entity) (name (first arg-pair)))]])
                 ) arg-pairs)))


(s/defn ->find-by
  "The `find-by` function for a given entity"
  [{fields :fields ns :namespace :as entity} :- Entity]
  (fn [db & arg-pairs]
    ;;find by should always be specified
    (assert (= 0 (rem (count arg-pairs) 2)))
    (assert (every? identity arg-pairs))
    (->>(d/q `{:find [~'?e]
               :where ~(where-clauses entity (partition-all 2 arg-pairs))}
             db)
        ffirst
        (d/pull db (if-let [view (get-in entity [:views :one])]
                     view
                     '[*]))
        entity-exists?
        normalize-keys)))

(s/defn ->pull
  "The `pull` function for a given entity"
  [{fields :fields ns :namespace :as entity} :- Entity]
  (fn [db entity-id]
    (->> entity-id
         (d/pull db (if-let [view (get-in entity [:views :one])]
                      view
                      '[*]) )
         entity-exists?
         normalize-keys)))

(s/defn ->all-with
  "The `all-with` function for a given entity"
  [entity :- Entity]
  (let [fields (:fields entity)
        field?  (set (keys fields))]
    (fn [db & arg-pairs]
      (->> (d/q `{:find [[~'?e ...]]
                  :where ~(where-clauses entity (partition-all 2 arg-pairs))}
                db)
           (map (partial d/pull db (if-let [view (get-in entity [:views :many])]
                                     view
                                     '[*])))
           (map normalize-keys)))))

;; TODO: this should be atomic
(s/defn ->find-or-create
  "Find an entity or create it if it doesn't exist"
  [entity :- Entity]
  (let [find-by (->find-by entity)
        create (->create entity)]
    (fn [conn input]
      (let [db (d/db conn)]
        (or (apply find-by (flatten (cons db (seq input)))) ;; (find db key val key2 val2 ...)
            (create conn input))))))
