(ns crustacean.core
  "This generates CRU functions complete with input validation"
  (:require [schema.core :as s]
            [plumbing.core :refer [fnk]]
            [datomic.api :as d]
            [clojure.string :refer [capitalize]]

            [crustacean.migrations :as migrations]
            [crustacean.lazygraph :as lazygraph]
            [crustacean.utils :refer [entity-exists? fields-with unique-fields remove-nils]])
  (:gen-class))

;; TODO
;; - Documentation/readme
;; - Tests
;; - add support for backrefs in composite keys


(declare ->malformed? ->exists? ->create  ->find-by ->pull ->pull-many ->all-with ->find-or-create ->input-schema ->input-schema* ->output-schema ->graph)

;; ## The main macro

(defn defentity*
  "Takes an entity specification in a friendly syntax and creates the entity. Used in implementation of `defentity`"
  [nm forms]
  (let [model (into
               ;; The next three fields are required by yuppiechef's datomic-schema
               {:name (name nm)
                :basetype (keyword nm)
                :namespace (name nm)}
               (for [[k & values] forms]
                 (case k
                   :migration-dir
                   [:migration-dir (first values)]

                   ;; A field is a vector containing the field name, type, and options, i.e.
                   ;; [posts :ref :many :indexed]
                   ;; The type may itself be a vector, that indicates the model used in a reference type, i.e.
                   ;; [posts [:ref blog.post/post] :many :indexed]

                   ;; Fields are stored as a map, the keys are (string) field names, and the values are vectors of the field type,
                   ;; the set of options and (optionally) a string representing the referenced model
                   ;; TODO: convert keys to keywords
                   :fields
                   [:fields (->> (for [[nm tp & opts] values]
                                   [(name nm)
                                    (if (vector? tp)
                                      [(first tp) (set opts) (str (second tp))]
                                      [tp (set opts)])])
                                 (into {}))]

                   ;; Computed fields consist of a vector containing the field name and a fnk that computes it.
                   ;; Computed fields are stored as a map, the keys are (string) field names, and the values are the fnks
                   :computed-fields
                   [:computed-fields (->> (for [[nm computed-field] values]
                                            [(name nm) computed-field])
                                          (into {}))]

                   ;; Defaults consist of a vector containing the field name and a fn that computes it.
                   ;; Defaults are stored as a map, the keys are (string) field names, and the values are the fns
                   :defaults
                   [:defaults (->> (for [[nm default] values]
                                     [(name nm) default])
                                   (into {}))]

                   ;; Validators consist of a vector containing the field name and a fn or regular expression that computes it.
                   ;; Validators are stored as a map, the keys are (string) field names, and the values are the fns or regular expressions
                   :validators
                   [:validators (->> (for [[nm validator] values]
                                       [(name nm) validator])
                                     (into {}))]
                   ;; To be implemented
                   :composite-keys
                   [:composite-keys (mapv #(mapv keyword %) values)]


                   ;; TODO: get rid of this
                   :views
                   [:views (apply hash-map values)]

                   ;; Backrefs are a way of updating the parent when creating a
                   ;; child. If you specify a backreference on a model, the
                   ;; parent can be updated by a child. They
                   ;; support :assignment-required or :assignment-permitted as
                   ;; options.
                   :backrefs
                   [:backrefs (->> (for [[nm opt] values]
                                     [(keyword nm) opt])
                                   (into {}))]


                   ;; Db functions are functions that run in the transactor
                   :db-functions
                   [:db-functions (->> (for [[nm default] values]
                                         [(name nm) default])
                                       (into {}))])))
        input-schema* (->input-schema* model)]
    (assoc model
           ;; Input (prismatic) schema generated from the fields and validators on the model. We store it evaluated here
           :input-schema input-schema*

           ;; Values below are saved as strings so they don't get evaluated when this macro is expanded
           ;; Otherwise they're written as raw functions 'objects' at macro expansion time
           :raw-input-schema (pr-str input-schema*)
           :raw-defaults (pr-str (:defaults model))
           :raw-validators (pr-str (:validators model)))))

(defmacro defentity
  "Takes an entity specification in a friendly syntax and creates the entity, along with all of the requisite functions"
  [nm & forms]
  `(do (def ~nm ~(defentity* nm forms))
       (def ~'malformed? (->malformed? ~nm))
       (def ~'exists? (->exists? ~nm))
       (def ~'graph (->graph ~nm))
       (def ~'pull (->pull ~nm ~'graph))
       (def ~'create (->create ~nm ~'pull))
       (def ~'pull-many (->pull-many ~nm ~'pull))
       (def ~'find-by (->find-by ~nm ~'pull))
       (def ~'all-with (->all-with ~nm ~'pull-many))
       (def ~'find-or-create (->find-or-create ~nm ~'find-by ~'create))
       ;; TODO these names suck
       (def ~'DBInputSchema (->input-schema ~nm)) ;; this is what we validate the db against
       (def ~'APIInputSchema (apply dissoc ~'DBInputSchema (keys (:backrefs ~nm)))) ;;this is what we validate the api against -- we allow backrefs
       (def ~'OutputSchema (->output-schema ~nm))
       ;; Add the model to dynamic var so we know to migrate it when we run sync-all-models
       (when (:migration-dir ~nm)
         (alter-var-root #'migrations/*models* #(assoc % (str *ns* "/" ~(str nm)) ~nm)))))

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
  We return but don't evaluate, and return syntax here because these
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
  [{:keys [input-schema] :as model}]
  (s/schema-with-name input-schema (str (capitalize (:name model)) "In")))


;; TODO we can generate this from the graph
(defn ->output-schema
  "The output schema for a given entity"
  [entity]
  (-> (into {:id Long
             :created-at java.util.Date}
            (concat
             (for [[field spec] (:fields entity)]
               [(s/optional-key (keyword field)) (s/maybe (if (= :ref (first spec)) s/Any (eval (field-spec->schema spec))))])
             (for [[field func & [field-type]] (:computed-fields entity)]
               [(keyword field) (if field-type field-type s/Any)])))

      (s/schema-with-name (str (capitalize (:name entity)) "Out"))))


;; TODO: maybe call this "invalid"
(defn ->malformed?
  "The `malformed?` function for a given entity"
  [{:keys [input-schema] :as model}]
  (s/checker input-schema))

(defn ->exists?
  "The `exits?` function for a given entity"
  [entity]
  (fn [db input]
    (d/invoke db (keyword (:namespace entity) "exists?") db input)))

;; Entity Creation


(defn ->create
  "The `create!` function for a given entity"
  [entity pull]
  (let [create-fn (keyword (:namespace entity) "create")]
    (fn [conn dirty-input]
      (let [input (remove-nils dirty-input)]
        (s/validate (:input-schema entity) input)
        (assert (not ((->exists? entity) (d/db conn) input)))
        (let [tempid (d/tempid :db.part/user -1)
              {:keys [tempids db-after]} @(d/transact conn [[create-fn tempid input]])
              id (d/resolve-tempid db-after tempids tempid)]
          (pull db-after id))))))

;; ## Entity Access
(defn generate-query
  "Generates the datomic query given a model and arg-pairs, a vector of pairs of [:field value], where value can be nil"
  [entity arg-pairs]
  (if (and (= 1 (count arg-pairs)) (nil? (second (first arg-pairs))))
    ;; We have a query like (all-with db :name))
    `{:find [[~'?e ~'...]]
      :where [[~'?e ~(keyword (:namespace entity) (name (ffirst arg-pairs)))]]}

    ;; We have to generate the in and where clauses for the query
    (let [field-symbols (into {}
                              (for [[field value] arg-pairs]
                                ;; Sometimes we have nil as a value, in that case we use '_ as the sym since we aren't binding the result
                                [field (if (nil? value ) '_ (gensym "?"))]))

          ;; Insert the symbols that correspond to the fields we're searching for, ignoring _'s
          in-clauses (into ['$] (remove #(= '_ %) (map second field-symbols)))

          where-clauses (for [[field sym] field-symbols]
                          ;; if the attr is a backreference (:foo/_bar) look it up as a backreference
                          (if (re-find #"^_" (name field))
                            `[~sym ~(keyword (namespace field) (.substring (name field) 1)) ~'?e]

                            `[~'?e ~(keyword (:namespace entity) (name field)) ~sym]))]

      `{:find [[~'?e ...]]
        :in ~in-clauses
        :where ~where-clauses}
      )))

(defn ->graph
  "Given a model, compile a graph that given a db and a datomic entity outputs a lazy map representing the entity"
  [{fields :fields computed-fields :computed-fields ns :namespace :as model}]
  (lazygraph/lazy-compile
   (let [computed-fields-graph (reduce
                                (fn [result [field-name func]]
                                  (assoc result (keyword field-name) func))
                                ;; Start with the id field
                                {:id (fnk [e] (:db/id e))
                                 :created-at (fnk [e] (let [tx (first (get e (keyword ns "_txCreated")))]
                                                        (:db/txInstant tx)))}
                                computed-fields)]

     (reduce (fn [acc [field-name [field-type field-opts ref-model]]]
               (let [qualified-field (keyword ns field-name)
                     field-key (keyword field-name)]
                 (if (= :ref field-type)
                   ;; if we have a model, use its view
                   (if ref-model
                     (let [sub-graph (->graph (eval (symbol ref-model)))]
                       (if (contains? field-opts :many)
                         (assoc acc field-key (fnk [db e] (mapv #(sub-graph {:e % :db db}) (qualified-field e))))

                         (assoc acc field-key (fnk [db e]
                                                   (let [sub-entity (qualified-field e)]
                                                     (sub-graph {:e sub-entity :db db}))))))
                     ;; else we don't include it
                     acc)

                   ;; if it's not a ref act normally
                   (assoc acc field-key (fnk [e] (qualified-field e))))))

             computed-fields-graph

             fields))))

(defn ->pull
  "The `pull` function for a given entity"
  [model graph]
  (fn [db entity-id]
    (when entity-id
      (graph {:e (d/entity db entity-id) :db db}))))

(defn ->pull-many
  "The `pull-many` function for a given entity"
  [model pull]
  (fn [db entity-ids]
    (map #(pull db %) entity-ids)))

(defn ->find-by
  "The `find-by` function for a given entity"
  [{fields :fields ns :namespace :as entity} pull]
  (fn [db & args]
    ;;find by should always be specified
    (assert (= 0 (rem (count args) 2)))
    (assert (every? (comp not nil?) args))
    (let [arg-pairs (partition-all 2 args)]
      (->> (d/query {:query (generate-query entity arg-pairs)
                     :args (into [db] (remove nil? (map second arg-pairs)))})
           first
           (pull db)))))

(defn ->all-with
  "The `all-with` function for a given entity"
  [entity pull-many]
  (let [fields (:fields entity)
        field?  (set (keys fields))]
    (fn [db & args]
      (let [arg-pairs (partition-all 2 args)]
        (->> (d/query {:query (generate-query entity arg-pairs)
                       :args (into [db] (remove nil? (map second arg-pairs)))})
             (pull-many db))))))

;; TODO: this should be atomic
(defn ->find-or-create
  "Find an entity or create it if it doesn't exist"
  [entity find-by create]
  (fn [conn input]
    (let [db (d/db conn)]
      (or (apply find-by (flatten (cons db (seq input)))) ;; (find db key val key2 val2 ...)
          (create conn input)))))
