(ns crustacean.core-test
  (:require [datomic.api :as d]
            [schema.core :as s]

            [crustacean.core :refer :all]
            [clojure.test :refer :all])
  (:import [datomic.db Function]))


;;A model we use for a reference
(defentity model2
  (:fields [field1 :long]))


(deftest test-defentity*
  (let [model (defentity* 'model
                '((:migration-file "testdata/entity.edn")
                  (:fields [field1 :keyword :unique-value :assignment-required]
                           [field2 :string :unique-identity :assignment-permitted]
                           [field3 :boolean :indexed :assignment-permitted]
                           [field4 [:ref model2] :nohistory])
                  (:computed-fields [computed-field1 (fn [field1] (name field1))])
                  (:defaults [field3 (fn [] (= 1 1))]
                             [field1 :hello])
                  (:validators [field1 (fn [x] (> (count (name x)) 9))]
                               [field2 #"hello"])))]
    (is (= (:migration-file model) "testdata/entity.edn"))
    (testing "fields"
      (let [fields (:fields model)]
        (is (= [:keyword #{:unique-value :assignment-required}] (fields "field1")))
        (is (= [:string #{:unique-identity :assignment-permitted}] (fields "field2")))
        (is (= [:boolean #{:indexed :assignment-permitted}] (fields "field3")))
        (is (= [:ref #{:nohistory} "model2"] (fields "field4")))))
    (testing "computed-fields"
      (is (= {"computed-field1" '(fn [field1] (name field1))}
             (:computed-fields model))))
    (testing "defaults"
      (is (= {"field3" '(fn [] (= 1 1))
              "field1" :hello}
             (:defaults model))))
    (testing "validators"
      (is (= '(fn [x] (> (count (name x)) 9))
             ((:validators model) "field1"))))
    (testing "input-schema"
      ;; Can't test this directly because it has compiled function objects for validators
      ;; no field4 since it's not assignment-permitted or assignment-required
      (is (= #{:field1 `(s/optional-key :field2) `(s/optional-key :field3)}
             (set (keys (:input-schema model))))))
    (testing "db-funcs"
      ;; Can't test this directly becuse they contain compiled db-funcs as strings
      (let [db-funcs (:db-funcs model)]
        (is (instance? String (:create* db-funcs)))
        (is (instance? String (:malformed?* db-funcs)))
        (is (instance? String (:exists?* db-funcs)))))))


(deftest test-field-spec->schema
  (is (= `s/Keyword (field-spec->schema [:keyword #{}])))
  (is (= `(s/either s/Keyword [s/Keyword] #{s/Keyword}) (field-spec->schema [:keyword #{:many}])))

  (is (= `s/Str (field-spec->schema [:string #{}])))
  (is (= `(s/either s/Str [s/Str] #{s/Str}) (field-spec->schema [:string #{:many}])))

  (is (= `s/Bool (field-spec->schema [:boolean #{}])))
  (is (= `(s/either s/Bool [s/Bool] #{s/Bool}) (field-spec->schema [:boolean #{:many}])))

  (is (= `s/Int (field-spec->schema [:long #{}])))
  (is (= `(s/either s/Int [s/Int] #{s/Int}) (field-spec->schema [:long #{:many}])))

  (is (= `s/Int (field-spec->schema [:bigint #{}])))
  (is (= `(s/either s/Int [s/Int] #{s/Int}) (field-spec->schema [:bigint #{:many}])))

  (is (= `Float (field-spec->schema [:float #{}])))
  (is (= `(s/either Float [Float] #{Float}) (field-spec->schema [:float #{:many}])))

  (is (= `Double (field-spec->schema [:double #{}])))
  (is (= `(s/either Double [Double] #{Double})  (field-spec->schema [:double #{:many}])))

  (is (= `s/Int (field-spec->schema [:bigdec #{}])))
  (is (= `(s/either s/Int [s/Int] #{s/Int})  (field-spec->schema [:bigdec #{:many}])))

  (is (= `(s/either s/Int {s/Keyword s/Any}) (field-spec->schema [:ref #{}])))
  (is (= `(s/either (s/either s/Int {s/Keyword s/Any}) [(s/either s/Int {s/Keyword s/Any})] #{(s/either s/Int {s/Keyword s/Any})}) (field-spec->schema [:ref #{:many}])))

  (is (= `s/Inst (field-spec->schema [:instant #{}])))
  (is (= `(s/either s/Inst [s/Inst] #{s/Inst}) (field-spec->schema [:instant #{:many}])))

  (is (= `s/Uuid (field-spec->schema [:uuid #{}])))
  (is (= `(s/either s/Uuid [s/Uuid] #{s/Uuid}) (field-spec->schema [:uuid #{:many}])))

  (is (= `java.net.URI (field-spec->schema [:uri #{}])))
  (is (= `(s/either java.net.URI [java.net.URI] #{java.net.URI}) (field-spec->schema [:uri #{:many}])))

  (is (= `bytes (field-spec->schema [:bytes #{}])))
  (is (= `(s/either bytes [bytes] #{bytes}) (field-spec->schema [:bytes #{:many}])))

  (is (= `s/Any (field-spec->schema [:fn #{}])))
  (is (= `(s/either s/Any [s/Any] #{s/Any}) (field-spec->schema [:fn #{:many}])))

  (is (= `s/Any (field-spec->schema [:enum #{}])))
  (is (= `(s/either s/Any [s/Any] #{s/Any}) (field-spec->schema [:enum #{:many}]))))

(deftest test-->input-schema
  ;;Assignment-required means a field has to be present
  (is (= `{:field s/Str} (->input-schema* {:fields {"field" [:string #{:assignment-required}]}})))
  ;;Assignment-permitted makes it an optional key
  (is (= `{(s/optional-key :field) s/Str} (->input-schema* {:fields {"field" [:string #{:assignment-permitted}]}})))

  ;;Fields with neither are not allowed in the input schema
  (is (= `{} (->input-schema* {:fields {"field" [:string #{}]}})))

  ;;Backrefs follow a similar pattern
  (is (= `{:field (s/either s/Int {s/Keyword s/Any})} (->input-schema* {:backrefs {:field :assignment-required}})))

  (is (= `{(s/optional-key :field) (s/either s/Int {s/Keyword s/Any})} (->input-schema* {:backrefs {:field :assignment-permitted}})))

  (is (= {} (->input-schema* {:backrefs {:field :not-a-thing}}))))

(deftest test-generate-query
  (let [model (defentity* 'model
    '((:fields [str-field :string]
               [bool-field :boolean])))]
    ;; Handle queries with one param, no value
    (let [query (generate-query model [[:str-field]])]
      (is (= {:find [['?e '...]] :where [['?e :model/str-field]]} query)))
    ;; Handle queries with one param, one value
    (let [query (generate-query model [[:str-field "hi"]])
          sym (second (:in query))]
      (is (= {:find [['?e '...]] :in ['$ sym] :where [['?e :model/str-field sym]]} query)))

    ;; Handle queries with multiple params
    (let [query (generate-query model [[:str-field "hi"] [:bool-field true]])
          sym1 (second (:in query))
          sym2 (nth (:in query) 2)]
      (is (= {:find [['?e '...]] :in ['$ sym1 sym2] :where [['?e :model/str-field sym1] ['?e :model/bool-field sym2]]} query)))

    ;; Handle false in bool fields
    (let [query (generate-query model [[:str-field "hi"] [:bool-field false]])
          sym1 (second (:in query))
          sym2 (nth (:in query) 2)]
      (is (= {:find [['?e '...]] :in ['$ sym1 sym2] :where [['?e :model/str-field sym1] ['?e :model/bool-field sym2]]} query)))

    ;; Handle backrefs
    (let [query (generate-query model [[:str-field "hi"] [:other-model/_backref 123]])
          sym1 (second (:in query))
          sym2 (nth (:in query) 2)]
      (is (= {:find [['?e '...]] :in ['$ sym1 sym2] :where [['?e :model/str-field sym1] [sym2 :other-model/backref '?e]]} query)))))

;; TODO test ->output-schema
;; TODO test ->malformed?*
;; TODO test ->malformed?
;; TODO test ->exists?*
;; TODO test ->create*
;; TODO test ->create
;; TODO test where-clauses
;; TODO test ->graph
;; TODO test ->pull
;; TODO test ->pull-many
;; TODO test ->find-by
;; TODO test ->all-with
;; TODO test ->find-or-create
