(ns crustacean.schemas
  (:require [datomic-schema.schema :as ds :refer [defschema]]
            [schema.core :as s] 
            [datomic.api :as d]
            [clojure.string :refer [capitalize]]))


;; ## Core Schemas

(def FieldName
  "A field can be called any name except for the reserved names used by crustacean"
  (s/both s/Str (s/pred  (comp not  #{"create" "malformed?" "exists?"}))))


(def FieldType
  "Allowed types for datomic fields"
  (s/enum :keyword :string :boolean :long :bigint :float :double :bigdec :ref :instant
          :uuid :uri :bytes :enum :fn))

(def FieldOpt
  "Allowed options for datomic attributes. Uses the format of
   yuppiechef/datomic-schema along with :assignment-required
   and :assignment-permitted for data validation"
  (s/either (s/enum :unique-value :unique-identity :indexed :many :fulltext :component :nohistory :assignment-required :assignment-permitted) s/Str [s/Any]))

(def FieldSpec
  "A FieldSpec is a type followed by a set of attributes"
  (s/pair s/Keyword "type" #{FieldOpt} "opts"))

(def Entity
  "Entities are collections of fields that describe Datomic entities, along with
   support for defaults, validators, composite keys, and views. The format is an
   extension of the format used by yuppiechef/datomic-schema"
  {:fields {FieldName FieldSpec}
   (s/optional-key :defaults)   {s/Str s/Any}
   (s/optional-key :validators) {s/Str (s/either clojure.lang.PersistentList java.util.regex.Pattern)}
   (s/optional-key :composite-keys) [(s/pair s/Keyword "first key" s/Keyword "second key")]
   (s/optional-key :views) {:one s/Any :many s/Any}
   (s/optional-key :backrefs) {s/Keyword s/Keyword}
   (s/optional-key :extra-txes) [{s/Keyword s/Any}]
   :name s/Str
   :basetype s/Keyword
   :namespace s/Str
   :migration-file s/Str
   :migration-version s/Str})

;; Schema for a datomic ref
(defschema Ref
  {:id Long})
