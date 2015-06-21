(ns crustacean.core
  "This generates CRU functions complete with input validation"
  (:require [datomic-schema.schema :as ds :refer [defschema]]
            [clojure.string :refer [capitalize]]

            [crustacean.migrations :as migrations]
            [crustacean.schemas :refer :all]
            [crustacean.generators :refer :all]
            [crustacean.utils :refer [normalize-keys entity-exists?]]))

;; note - bad idea to do :assignment-permitted on ref types because it's hard to limit user to only throwing in refs to their own things
;; TODO
;; - Documentation/readme
;; - Tests
;; - add support for backrefs in composite keys
;; - entity spec uses strings for field names, I generally expect
;; keywords. Theres a lot of hacky code converting them as needed -
;; fix this, possibly by changing keywords to strings before we use
;; datomic-schema which expects strings




;; ## The main macro

;; TODO - split macro into a function that makes the datastructure and the macro that does all the defs
(defmacro defentity
  "`defentity` takes an entity specification in a friendly syntax and creates the entity, along with all of the requisite functions"
  [nm & forms]
  (let [entity (reduce (fn [a [k & values]]
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
        entity (assoc entity :name (name nm)
                      :basetype (keyword nm)
                      :namespace (name nm))]
    `(do (def ~nm ~entity)
         (def ~'malformed? (->malformed? ~entity))
         (def ~'exists? (->exists? ~entity))
         (def ~'create (->create ~entity))
         (def ~'find-by (->find-by ~entity))
         (def ~'pull (->pull ~entity))
         (def ~'all-with (->all-with ~entity))
         (def ~'find-or-create (->find-or-create ~entity))
         ;; TODO these names suck
         (def ~'DBInputSchema (->input-schema ~entity))
         (def ~'APIInputSchema (apply dissoc (->input-schema ~entity) (keys (:backrefs ~entity))))
         (def ~'OutputSchema (->output-schema ~entity)))))

