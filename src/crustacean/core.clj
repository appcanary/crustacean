(ns crustacean.core
  "This generates CRU functions complete with input validation"
  (:require [schema.core :as s]
            [datomic.api :as d]
            [clojure.string :refer [capitalize]]

            [crustacean.schemas :refer :all]
            [crustacean.utils :refer [normalize-keys entity-exists? fields-with unique-fields remove-nils]]))

;; note - bad idea to do :assignment-permitted on ref types because it's hard to limit user to only throwing in refs to their own things
;; TODO
;; - Documentation/readme
;; - Tests
;; - add support for backrefs in composite keys
;; ## The main macro

(defn defentity*
  "Takes an entity specification in a friendly syntax and creates the entity. Used in implementation of `defentity`"
  [nm forms]
  (-> (reduce (fn [a [k & values]]
                (case k
                  :migration-file
                  (assoc a :migration-file (first values))

                  :migration-version
                  (assoc a :migration-version (first values))

                  :fields
                  (assoc a :fields 
                         (reduce (fn [a [nm tp & opts]]
                                   (assoc a (name nm) [tp (set opts)])) {} values))
                  :defaults
                  (assoc a :defaults
                         (reduce (fn [a [nm default]]
                                   (assoc a (name nm) (if (list? default)
                                                        `(quote ~default)
                                                        default))) {} values))
                  :validators
                  (assoc a :validators
                         (reduce (fn [a [nm validator]]
                                   (assoc a (name nm) (if (list? validator)
                                                        `(quote ~validator)
                                                        validator))) {} values))

                  :composite-keys
                  (assoc a :composite-keys
                         (mapv #(mapv keyword %) values))

                  :views
                  (assoc a :views (apply hash-map values))

                  :backrefs
                  (assoc a :backrefs
                         (reduce (fn [a [nm opt]]
                                   (assoc a (keyword nm) opt)) {} values))

                  :extra-txes
                  (assoc a :extra-txes (first values))))

              {}
              forms)
      (assoc :name (name nm)
             :basetype (keyword nm)
             :namespace (name nm))))


(declare ->malformed? ->exists? ->create ->find-by ->pull ->all-with ->find-or-create ->input-schema ->output-schema)

(defmacro defentity
  "Takes an entity specification in a friendly syntax and creates the entity, along with all of the requisite functions"
  [nm & forms]
  (let [entity (defentity* nm forms)]
    `(do (def ~nm ~entity)
         (def ~'malformed? (->malformed? ~entity))
         (def ~'exists? (->exists? ~entity))
         (def ~'create (->create ~entity))
         (def ~'find-by (->find-by ~entity))
         (def ~'pull (->pull ~entity))
         (def ~'pull-many (->pull-many ~entity))
         (def ~'all-with (->all-with ~entity))
         (def ~'find-or-create (->find-or-create ~entity))
         ;; TODO these names suck
         (def ~'DBInputSchema (->input-schema ~entity)) ;; this is what we validate the db against
         (def ~'APIInputSchema (apply dissoc (->input-schema ~entity) (keys (:backrefs ~entity)))) ;;this is what we validate the api against -- we allow backrefs
         (def ~'OutputSchema (->output-schema ~entity)))))

(defn field-spec->schema
  "Convert a field spec to a prismatic schema"
  [[type opts] ]
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
      `(s/either ~schema ~(vector schema) ~(set (vector schema)))
      schema)))


(defn ->input-schema*
  "The syntactic representation of a prismatic schema for an input, generated from a given entity.
  We return don't evaluate, and return syntax here because these
  schemas are going to be inserted into datomic database functions,
  which are stored as unevaluated forms.

  To generate an actual schema, see `->input-schema`"
  [entity]
  (let [assignment-required (fields-with entity :assignment-required)
        assignment-permitted (fields-with entity :assignment-permitted)]
    (into {}
          (concat (for [[field spec] (select-keys (:fields entity) (concat assignment-permitted assignment-required))]
                    (let [key (if (contains? (second spec) :assignment-required)
                                (keyword field)
                                `(s/optional-key ~(keyword field)))
                          schema (field-spec->schema spec)
                          val (if-let [validator (get (:validators entity) field)]
                                (if (seq? validator)
                                  `(s/both ~schema (s/pred ~validator))
                                  `(s/both ~schema ~validator))
                                schema)]
                      [key val]))

                  (for [[field assignment] (:backrefs entity)]
                    (cond (= :assignment-required assignment)
                          [field (field-spec->schema [:ref #{}])]

                          (= :assignment-permitted assignment)
                          [`(s/optional-key ~field) (field-spec->schema [:ref #{}])]))))))


(defn ->input-schema
  "The input schema for a given entity"
  [entity]
  (s/schema-with-name (eval (->input-schema* entity)) (str (capitalize (:name entity)) "In")))

(defn ->output-schema
  "The output schema for a given entity"
  [entity]
  (-> (into {:id Long
             s/Keyword s/Any}
            (for [[field spec] (:fields entity)]
              [(s/optional-key (keyword field)) (if (= :ref (first spec)) s/Any (eval (field-spec->schema spec)))]))

      (s/schema-with-name (str (capitalize (:name entity)) "Out"))))

(defn ->malformed?*
  "The malformed? database function for a given entity"
  [entity]
  (d/function
   `{:lang :clojure
     :requires [[schema.core]]
     :params [input#]
     :code (let [checker#  (schema.core/checker (eval ~(->input-schema* entity)))]
             (checker# input#))}))

(defn ->malformed?
  "The `malformed?` function for a given entity"
  [entity]
  (s/checker (->input-schema entity)))

(defn ->malformed?
  "The `malformed?` function for a given entity"
  [entity]
  (s/checker (->input-schema entity)))

(defn ->exists?*
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
             (seq (concat
                   (d/q {:find '[[~'?e ...]] :in '[~'$ [[~'?attr ~'?value]]] :where '[[~'?e ~'?attr ~'?value]]} db# unique-key-pairs#)
                   (d/q {:find '[[~'?e ...]] :in '[~'$ [[~'?attr1 ~'?value1 ~'?attr2 ~'value2]]] :where '[[~'?e ~'?attr1 ~'?value1] [~'?e ~'?attr2 ~'?value2]]} db# composite-key-pairs#))))}))

(defn ->exists?
  "The `exits?` function for a given entity"
  [entity]
  (fn [db input]
    (d/invoke db (keyword (:namespace entity) "exists?") db input)))

;; Entity Creation

(defn ->create*
  "The `create` database function for a given entity"
  [entity]
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
(defn ->create
  "The `create!` function for a given entity"
  [entity]
  (let [input-schema (->input-schema entity)
        create-fn (keyword (:namespace entity) "create")
        pull (->pull entity)]
    (fn [conn dirty-input]
      (let [input (remove-nils dirty-input)]
        (s/validate input-schema input)
        (assert (not ((->exists? entity) (d/db conn) input)))
        (let [tempid (d/tempid :db.part/user -1)
              {:keys [tempids db-after]} @(d/transact conn [[create-fn tempid input]
                                                            ])
              id (d/resolve-tempid db-after tempids tempid)]
          (pull db-after id))))))

;; ## Entity Access
(defn where-clauses
  "Generate where clauses for based on a list of attribute values or attributes"
  [entity arg-pairs]
  (vec (mapcat (fn [arg-pair]
                 (if (and (= 2 (count arg-pair)) (not (nil? (second arg-pair))))
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


(defn ->pull
  "The `pull` function for a given entity"
  [{fields :fields ns :namespace :as entity}]
  (fn [db entity-id]
    (let [view (or (get-in entity [:views :one])
                   #(select-keys % (conj (map (partial keyword ns) (keys fields)) :db/id )))]
      (some->> (d/entity db entity-id)
               entity-exists?
               view
               normalize-keys))))

(defn ->pull-many
  "The `pull-many` function for a given entity"
  [{fields :fields ns :namespace :as entity}]
  (fn [db entity-ids]
    (let [view (or (get-in entity [:views :many])
                   #(select-keys % (conj (map (partial keyword ns) (keys fields)) :db/id )))]
      (some->> entity-ids
               ;;TODO: this should be a transducer
               (map (comp
                     normalize-keys
                     entity-exists?
                     view
                     (partial d/entity db)))))))

(defn ->find-by
  "The `find-by` function for a given entity"
  [{fields :fields ns :namespace :as entity}]
  (fn [db & arg-pairs]
    ;;find by should always be specified
    (assert (= 0 (rem (count arg-pairs) 2)))
    (assert (every? (comp not nil?) arg-pairs))
    (let [pull (->pull entity)]
      (->> (d/q `{:find [~'?e]
                  :where ~(where-clauses entity (partition-all 2 arg-pairs))}
                db)
           ffirst
           (pull db)))))

(defn ->all-with
  "The `all-with` function for a given entity"
  [entity]
  (let [fields (:fields entity)
        field?  (set (keys fields))]
    (fn [db & arg-pairs]
      (let [pull-many (->pull-many entity)]
        (->> (d/q `{:find [[~'?e ...]]
                    :where ~(where-clauses entity (partition-all 2 arg-pairs))}
                  db)
             (pull-many db))))))

;; TODO: this should be atomic
(defn ->find-or-create
  "Find an entity or create it if it doesn't exist"
  [entity]
  (let [find-by (->find-by entity)
        create (->create entity)]
    (fn [conn input]
      (let [db (d/db conn)]
        (or (apply find-by (flatten (cons db (seq input)))) ;; (find db key val key2 val2 ...)
            (create conn input))))))
