# Crustacean
<img src="https://raw.githubusercontent.com/appcanary/crustacean/master/logo.png"
 alt="Crustacean logo" align="right" height="300px"/>
> The point is that lobsters are basically giant sea-insects. Like most arthropods, they date from the Jurassic period, biologically so much older than mammalia that they might as well be from another planet. And they are—particularly in their natural brown-green state, brandishing their claws like weapons and with thick antennae awhip—not nice to look at. And it’s true that they are garbagemen of the sea, eaters of dead stuff, although they’ll also eat some live shellfish, certain kinds of injured fish, and sometimes each other.

> - From [Consider the Lobster](http://www.gourmet.com/magazine/2000s/2004/08/consider_the_lobster) by David Foster Wallace

Crustacean provides a simplified syntax for defining models in Datomic. 

<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
# Table of contents

- [The idea](#the-idea)
- [Installation](#installation)
- [Defining a model](#defining-a-model)
  - [`:migration-dir`](#migration-dir)
  - [`:fields`](#fields)
    - [`:ref` fields](#ref-fields)
  - [`:backrefs`](#backrefs)
  - [`:defaults`](#defaults)
  - [`:validators`](#validators)
  - [`:computed-fields`](#computed-fields)
  - [`:db-functions`](#db-functions)
- [Migrating a model](#migrating-a-model)
  - [Generating migrations](#generating-migrations)
  - [Applying migrations](#applying-migrations)
- [Creating) an entity](#creating-an-entity)
- [Updating an entity](#updating-an-entity)
- [Querying data](#querying-data)
  - [`pull`](#pull)
  - [`find-by`](#find-by)
  - [`all-with`](#all-with)
- [The entity representation](#the-entity-representation)
  - [`:refs`](#refs)
  - [License](#license)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

# The idea

Datomic doesn't have a concept of "tables", any entity can have any attribute,
but it's good practice to namespace attributes, i.e. `:user/name` and
`:user/email` are attributes that belong to user entities, and `:product/sku`
and `:product/price` are attributes that belong to product entities. Nothing is
stopping you from having an entity with both `:user/name`, and `:product/sku`
attributes, but you probably won't want to do that.

Crustacean expands the idea of namespaces as groupings of keys for a model into
Clojure namespaces. You create a new Clojure namespace for each model, define it's
attributes and behavior with `defmodel`, and Crustacean will generate functions
to manipulate that model inside that namespace.

You get the following:

1) A simplified syntax for defining Datomic models.
2) Both transactor function and Clojure functions to `create` and `upsert` entities of each model.
3) Arbitrary data validations and default values for any attribute.
4) An ActiveRecord-inspired query DSL to cover the most common kinds of queries without having to use the Datomic query API.
5) Entities in your application code represented as lazy maps, complete with arbitraty computed fields. It's like the Datomic Entity API on steroids
6) Migrations

# Installation

Add `[appcanary/crustacean "0.1.10-SNAPSHOT"]` snapshot to your `project.clj`.

Crustacean depends on `datomic-free`. If you are using `datomic-pro`, you have
to exclude datomic free, like so:
`[appcanary/crustacean "0.1.10-SNAPSHOT" :exclusions [com.datomic/datomic-free]]`

Install Datomic as usual. Crustacean uses Prismatic's [Schema](https://github.com/plumatic/schema) to handle data
validations. The transactor needs to have access to the library, so you have to
put the jar in the Transactor's `lib` directory.

You can find the correct schema jar here: https://clojars.org/repo/prismatic/schema/1.0.4/schema-1.0.4.jar

# Defining a model

You define a model with `defmodel`. A basic example:

```clojure
(ns models.user
  (:require [crustacean.core :refer [defmodel]]))
  
(defmodel user
  (:migration-dir "migrations/user/")
  (:fields   [name       :string :indexed :assignment-permitted]
             [email      :string :unique-value :indexed :assignment-required]
             [articles   :ref    :many :indexed :component])
  (:validators [email #"@"]))
```

The above will create the following datomic attributes

- `:user/name` - attribute defined above
- `:user/email` - attribute defined above
- `:user/articles` - attribute defined above
- `:user/txCreated` - a marker for transctions to point to the user the create
- `:user/txUpdated` - a marker for transctions to point to the user the update

It will create the following transactor functions
- `:user/create` - create a new user
- `:user/upsert` - update or create a new user
- `:user/malformed?` - check if an input to `:user/create` or `:user/upsert` is malformed.
- `:user/exists?` - check if an input to `:user/create` or `:user/upsert` specifies a user that already exists.

It will create the following Clojure vars in the `models.user` namespace

- `user` - a map representing the model
- `create` - create a new user
- `upsert` - update or create a new uer
- `malformed?` - check if input to `create` or `upsert` is malformed
- `exists?` - check if input to `create` or `upsert` specifies a user that already exists
- `pull` - return a user based on the id
- `pull-many` - return a collection of users from a vector of ids
- `find-by` - find a single user using a Crustacean query
- `all-with` - find a collection of users using a Crustacean query
- `DBInputSchema` - 
- `APIInputSchema`
- `OutputSchema`

The above functions and attributes will be discussed in detail below, but first, here are all of the possible inputs to `defmodek`:

## `:migration-dir`

```clojure
(defmodel user
  ;; ...
  (:migration-dir "migrations/user"))
```

A string specifiying the directory to store migrations in. It will be a subdirectory of `PROJECTROOT/resources`, and will be created if it doesn't exist when you generate your first migration.

## `:fields`

```clojure
(defmodel user
  ;; ...
  (:fields [name       :string :indexed :assignment-permitted]
           [email      :string :unique-value :indexed :assignment-required]
           [articles   :ref    :many :indexed :component]))
```

A sequence of vectors for each field that the model has. The first value in each
vector is a symbol representing the name. The second is a keyword representing
the type (or a pair of :ref and model being referenced). The rest are keywords
rep representing options.

We use [datomic-schema](https://github.com/Yuppiechef/datomic-schema) on the backend, and the syntax is similar

```clojure
;; Types
:keyword :string :boolean :long :bigint :float :double :bigdec :ref [:ref some.model.namespace] :instant
:uuid :uri :bytes :enum

;; Options (Datomic)
:unique-value :unique-identity :indexed :many :fulltext :component
:nohistory "Some doc string" [:arbitrary "Enum" :values]

;; Options (Crustacean)
:assignment-permitted :assignment-required
```

The Datomic options directly correlate to schema options in Datomic. Note that
we default to `:db/cardinality :db.cardinality/one` if `:many` isn't specified.
We also resort to the following Datomic defaults:

```
:db/index <false>
:db/fulltext <false>
:db/noHistory <false>
:db/component <false>
:db/doc <"">
```

Crustacean allows you to optionally set one of `:assignment-required` or
`:assignment-permitted`. During `create` or `upsert`'s, you may only specify
attributes that are either `:assignment-required` or `:assignment-permitted`,
and you **must** specify all `:assignment-required` attributes during `create`.

Attributes with neither of these field should be set with regular Datomic
transactions.

### `:ref` fields

`:ref` fields have a special property in that they can be specified with the model referenced.

If there is an `article` model defined, in `models.article/article`, we can specify it as the reference for the `articles` field like so:

```clojure
(defmodel user
  ;; ...
  (:fields [name       :string :indexed :assignment-permitted]
           [email      :string :unique-value :indexed :assignment-required]
           [articles   [:ref models.article/article]  :many :indexed :component]))
```

Afterwards, when we query the `user` model, we will be get `artifact` results containing all the artifact fields. Otherwise we would just get a list of `artifact` ids.

## `:backrefs`

```clojure
(defmodel user
  ;; ...
  (:backrefs [:company/_users :assignment-required]
             [:clubs/_users]))
```

A sequence of vectors, each containing a namespaced keyword (with the name starting with an underscore), and optionally `:assignment-required` or `:assignment-permitted`

Backrefs allow you to build Crustacean queries that search via entities that reference a given entity, similar to Datomics backreference transaction syntax.

For instance `(user/all-with db :name "bob" :company/_users some-company-id)` will find all the users named bob, where `some-company-id` has that user as a value for the `:company/users` attribute.

Backrefs can be included in `create` or `upsert` calls if the `:assignment-permitted` or `:assignment-required` option is specified.

## `:defaults`

```clojure
(defmodel user
  ;; ...
  (:defaults [name "bob"]
             [email (fn [db user]
                      (str (:name user) "@example.com"))]))

```

A sequence of vectors consisting of a symbol corresponding to a field name and either a bare value or a function.

Defaults will be set when creating an entity if no value for the field is specified.

If the default is a function, it should take the db and a map representing the entity (this is the input to `create`).

It may be useful to define a field without `:assignment-permitted` or `:assignment-required`, but with a default, if you want it to be automatically set to a value, for instance for a UUID that's automatically generated.

Note also, that, if you're generating a UUID, you want to wrap it in a function:

```
;; This is NOT what you you want. (d/squuid) will be called once and all entities will share a UUID.
(:defaults [uuid (d/squuid)])

;; You want this
(:defaults [uuid (fn [_ _] (d/squuid))])
```

## `:validators`

```clojure
(defmodel user
  ;; ...
  (:validators [email #"@"]
             [name (fn [name] (> (count name) 2))]))
```

A sequence of vectors consisting of a symbol corresponding to a field name and either a regex pattern or a function.

Validators are called on every `create` and `upsert` to validate the contents of a field. If the validator is a regex pattern, it must match. If it's a function, it takes the potential field value. If it's `nil` or `false`, the value is invalid.

## `:computed-fields`

```clojure
(ns models.user
  (require [plumbing.core :refer [fnk]]))

(defmodel user
  ;; ...
  (:computed-fields [name-length (fnk [e]
                                 (count (:user/name e)))]))

```

A sequence of vectors consisting of a computed field name and a `fnk` defining it.

Computed field will be returned with the entity map as a result of queries. Note that like all keys in the entity map, they will be lazily evaluated.

We use `fnk` from Prismatic's [Plumbing](https://github.com/plumatic/plumbing). This means that depending on the argument's that `fnk` expects, you will get different values for the entity:

- `e` will be the Datomic [entity api](http://docs.datomic.com/entities.html) object for the entity
- `db` is the db
- `id` is the entity id
- arbitrrty fields (computed or not) can be referenced by their names.

The following are thus also valid ways of writing the `name-length` function above:

```clojure
(:computed-fields [name-length (fnk [name]
                                 (count name))])
                                 
(:computed-fields [name-length (fnk [name]
                                 (count name))])
                                 
(:computed-fields [name-length (fnk [db id]
                                  (count (:user/name (d/pull db [:user/name] id))))])
```

## `:db-functions`

```
(def update-email-fn
  (d/function {:lang :clojure
               :params ''[db id new-email]
               :code '[:db/add id :user/email new-email]}))
               

(defmodel user
  ;; ...
  (:db-functions [update-email update-email-fn]))
```

A sequence of vectors, the first element being the function name and the second a var containing the database function.

When the user model above is migrated, `:user/update-email` will be a transactor function as defined.

Crustacean provides a macro, `crustacean.utils/defn-db` to make defining database functions a little easier:

```clojure
(ns models.user
  (:require '[crustacean.utils :refer [defn-db]]))
  
(defn-db update-email-fn 
  [db id new-email] 
  [:db/add id :user/email new-email])
  
(defmodel user
  ;; ...
  (:db-functions [update-email update-email-fn]))
```

# Migrating a model

Crustacean allows us to generate and apply migrations for models it defines.

## Generating migrations

After we create the `user` model,

```clojure
(ns models.user
  (:require [crustacean.core :refer [defmodel]]))
  
(defmodel user
  (:migration-dir "migrations/user/")
  (:fields   [name       :string :indexed :assignment-permitted]
             [email      :string :unique-value :indexed :assignment-required]
             [articles   :ref    :many :indexed :component])
  (:validators [email #"@"]))
```

we have the var `models.user/user` which is a map representing the user model.

You can generate a migrations for the model with:

```clojure
(crustacean.migrations/new-migration user)
```

This will create a migration file at `PROJECTROOT/migrations/user/DATE.edn`

You can also create a named migration

```clojure
(crustacean.migrations/new-migration user "create-user-model")
```

This will create a migration file at `PROJECTROOT/migrations/user/DATE-create-user-model.edn`

The migration created looks like this:

```clojure
{:model {:name "user",
         :fields {"name" [:string #{:assignment-permitted :indexed}],
                  "email" [:string #{:indexed :assignment-required :unique-value}],
                  "articles" [:ref #{:indexed :component :many}]},
         :defaults nil,
         :validators {"email" #"@"}},
 :txes [({:db/id #db/id[:db.part/db -1000000],
          :db/ident :user/txCreated,
          :db/valueType :db.type/ref,
          :db/cardinality :db.cardinality/many,
          :db/index true,
          :db.install/_attribute :db.part/db}
         {:db/id #db/id[:db.part/db -1000001],
          :db/ident :user/txUpdated,
          :db/valueType :db.type/ref,
          :db/cardinality :db.cardinality/many,
          :db/index true,
          :db.install/_attribute :db.part/db}
         {:db/id #db/id[:db.part/db -1000002],
          :db/ident :user/txDeleted,
          :db/valueType :db.type/ref,
          :db/cardinality :db.cardinality/many,
          :db/index true,
          :db.install/_attribute :db.part/db}
         {:db/index true,
          :db/valueType :db.type/string,
          :db/noHistory false,
          :db/isComponent false,
          :db.install/_attribute :db.part/db,
          :db/fulltext false,
          :db/cardinality :db.cardinality/one,
          :db/doc "",
          :db/id #db/id[:db.part/db -1000003],
          :db/ident :user/name}
         {:db/index true,
          :db/unique :db.unique/value,
          :db/valueType :db.type/string,
          :db/noHistory false,
          :db/isComponent false,
          :db.install/_attribute :db.part/db,
          :db/fulltext false,
          :db/cardinality :db.cardinality/one,
          :db/doc "",
          :db/id #db/id[:db.part/db -1000004],
          :db/ident :user/email}
         {:db/index true,
          :db/valueType :db.type/ref,
          :db/noHistory false,
          :db/isComponent true,
          :db.install/_attribute :db.part/db,
          :db/fulltext false,
          :db/cardinality :db.cardinality/many,
          :db/doc "",
          :db/id #db/id[:db.part/db -1000005],
          :db/ident :user/articles}
         {:db/id #db/id[:db.part/user -1000006],
          :db/ident :user/exists?,
          :db/fn #db/fn{:lang :clojure, :imports [], :requires [], :params [db__30416__auto__ input__30417__auto__], :code "(clojure.core/let [unique-key-pairs__30418__auto__ (clojure.core/->> [\"email\"] (clojure.core/filter (fn* [p1__30413__30419__auto__] (clojure.core/contains? input__30417__auto__ (clojure.core/keyword p1__30413__30419__auto__)))) (clojure.core/map (clojure.core/juxt (fn* [p1__30414__30420__auto__] (clojure.core/keyword \"user\" p1__30414__30420__auto__)) (fn* [p1__30415__30421__auto__] (clojure.core/get input__30417__auto__ (clojure.core/keyword p1__30415__30421__auto__)))))) composite-key-pairs__30422__auto__ (clojure.core/->> nil (clojure.core/filter (clojure.core/fn [[a__30423__auto__ b__30424__auto__]] (clojure.core/and (clojure.core/contains? input__30417__auto__ a__30423__auto__) (clojure.core/contains? input__30417__auto__ b__30424__auto__)))) (clojure.core/map (clojure.core/fn [[a__30423__auto__ b__30424__auto__]] [(clojure.core/keyword \"user\" (clojure.core/name a__30423__auto__)) (clojure.core/get input__30417__auto__ a__30423__auto__) (clojure.core/keyword \"user\" (clojure.core/name b__30424__auto__)) (clojure.core/get input__30417__auto__ b__30424__auto__)])))] (clojure.core/seq (clojure.core/concat (datomic.api/q {:find (quote [[?e ...]]), :where (quote [[?e ?attr ?value]]), :in (quote [$ [[?attr ?value]]])} db__30416__auto__ unique-key-pairs__30418__auto__) (clojure.core/mapcat (clojure.core/fn [[attr1__30425__auto__ value1__30426__auto__ attr2__30427__auto__ value2__30428__auto__]] (datomic.api/q {:find (quote [[?e ...]]), :where (clojure.core/apply clojure.core/vector (clojure.core/seq (clojure.core/concat (clojure.core/list (clojure.core/apply clojure.core/vector (clojure.core/seq (clojure.core/concat (clojure.core/list (quote ?e)) (clojure.core/list attr1__30425__auto__) (clojure.core/list (quote ?value1)))))) (clojure.core/list (clojure.core/apply clojure.core/vector (clojure.core/seq (clojure.core/concat (clojure.core/list (quote ?e)) (clojure.core/list attr2__30427__auto__) (clojure.core/list (quote ?value2))))))))), :in (quote [$ ?value1 ?value2])} db__30416__auto__ value1__30426__auto__ value2__30428__auto__)) composite-key-pairs__30422__auto__))))"}}
         {:db/id #db/id[:db.part/user -1000007],
          :db/ident :user/malformed?,
          :db/fn #db/fn{:lang :clojure, :imports [], :requires [[schema.core]], :params [input__30430__auto__], :code "(clojure.core/let [checker__30431__auto__ (schema.core/checker (clojure.core/eval {(schema.core/optional-key :name) schema.core/Str, :email (schema.core/both schema.core/Str #\"@\")}))] (checker__30431__auto__ input__30430__auto__))"}}
         {:db/id #db/id[:db.part/user -1000008],
          :db/ident :user/create,
          :db/fn #db/fn{:lang :clojure, :imports [], :requires [], :params [db id input], :code "(clojure.core/if-let [malformed__30409__auto__ (datomic.api/invoke db (clojure.core/keyword \"user\" \"malformed?\") input)] (throw (java.lang.IllegalArgumentException. (clojure.core/str malformed__30409__auto__))) (if (datomic.api/invoke db (clojure.core/keyword \"user\" \"exists?\") db input) (throw (java.lang.IllegalStateException. \"entity already exists\")) (clojure.core/vector (clojure.core/into {:db/id id} (clojure.core/concat (clojure.core/for [[field__30401__auto__ [type__30402__auto__ opts__30403__auto__]] {\"name\" [:string #{:assignment-permitted :indexed}], \"email\" [:string #{:indexed :assignment-required :unique-value}], \"articles\" [:ref #{:indexed :component :many}]}] (clojure.core/let [namespaced-field__30404__auto__ (clojure.core/keyword \"user\" field__30401__auto__) defaults__30405__auto__ nil val__30406__auto__ (clojure.core/cond (opts__30403__auto__ :assignment-required) (clojure.core/get input (clojure.core/keyword field__30401__auto__)) (clojure.core/and (opts__30403__auto__ :assignment-permitted) (clojure.core/contains? input (clojure.core/keyword field__30401__auto__))) (clojure.core/get input (clojure.core/keyword field__30401__auto__)) (clojure.core/and true (clojure.core/contains? defaults__30405__auto__ field__30401__auto__)) (clojure.core/let [default__30407__auto__ (clojure.core/get defaults__30405__auto__ field__30401__auto__)] (if (clojure.core/fn? default__30407__auto__) (default__30407__auto__ db input) default__30407__auto__)))] (clojure.core/when (clojure.core/not (clojure.core/nil? val__30406__auto__)) [namespaced-field__30404__auto__ val__30406__auto__]))) (clojure.core/for [field__30401__auto__ (clojure.core/keys nil)] (clojure.core/when-let [val__30406__auto__ (clojure.core/get input field__30401__auto__)] [field__30401__auto__ val__30406__auto__])))) [:db/add (datomic.api/tempid :db.part/tx) :user/txCreated id])))"}}
         {:db/id #db/id[:db.part/user -1000009],
          :db/ident :user/upsert,
          :db/fn #db/fn{:lang :clojure, :imports [], :requires [], :params [db id input], :code "(clojure.core/if-let [malformed__30411__auto__ (datomic.api/invoke db (clojure.core/keyword \"user\" \"malformed?\") input)] (throw (java.lang.IllegalArgumentException. (clojure.core/str malformed__30411__auto__))) (if (datomic.api/invoke db (clojure.core/keyword \"user\" \"exists?\") db input) (clojure.core/vector (clojure.core/into {:db/id id} (clojure.core/concat (clojure.core/for [[field__30401__auto__ [type__30402__auto__ opts__30403__auto__]] {\"name\" [:string #{:assignment-permitted :indexed}], \"email\" [:string #{:indexed :assignment-required :unique-value}], \"articles\" [:ref #{:indexed :component :many}]}] (clojure.core/let [namespaced-field__30404__auto__ (clojure.core/keyword \"user\" field__30401__auto__) defaults__30405__auto__ nil val__30406__auto__ (clojure.core/cond (opts__30403__auto__ :assignment-required) (clojure.core/get input (clojure.core/keyword field__30401__auto__)) (clojure.core/and (opts__30403__auto__ :assignment-permitted) (clojure.core/contains? input (clojure.core/keyword field__30401__auto__))) (clojure.core/get input (clojure.core/keyword field__30401__auto__)) (clojure.core/and false (clojure.core/contains? defaults__30405__auto__ field__30401__auto__)) (clojure.core/let [default__30407__auto__ (clojure.core/get defaults__30405__auto__ field__30401__auto__)] (if (clojure.core/fn? default__30407__auto__) (default__30407__auto__ db input) default__30407__auto__)))] (clojure.core/when (clojure.core/not (clojure.core/nil? val__30406__auto__)) [namespaced-field__30404__auto__ val__30406__auto__]))) (clojure.core/for [field__30401__auto__ (clojure.core/keys nil)] (clojure.core/when-let [val__30406__auto__ (clojure.core/get input field__30401__auto__)] [field__30401__auto__ val__30406__auto__])))) [:db/add (datomic.api/tempid :db.part/tx) :user/txUpdated id]) (clojure.core/vector (clojure.core/into {:db/id id} (clojure.core/concat (clojure.core/for [[field__30401__auto__ [type__30402__auto__ opts__30403__auto__]] {\"name\" [:string #{:assignment-permitted :indexed}], \"email\" [:string #{:indexed :assignment-required :unique-value}], \"articles\" [:ref #{:indexed :component :many}]}] (clojure.core/let [namespaced-field__30404__auto__ (clojure.core/keyword \"user\" field__30401__auto__) defaults__30405__auto__ nil val__30406__auto__ (clojure.core/cond (opts__30403__auto__ :assignment-required) (clojure.core/get input (clojure.core/keyword field__30401__auto__)) (clojure.core/and (opts__30403__auto__ :assignment-permitted) (clojure.core/contains? input (clojure.core/keyword field__30401__auto__))) (clojure.core/get input (clojure.core/keyword field__30401__auto__)) (clojure.core/and true (clojure.core/contains? defaults__30405__auto__ field__30401__auto__)) (clojure.core/let [default__30407__auto__ (clojure.core/get defaults__30405__auto__ field__30401__auto__)] (if (clojure.core/fn? default__30407__auto__) (default__30407__auto__ db input) default__30407__auto__)))] (clojure.core/when (clojure.core/not (clojure.core/nil? val__30406__auto__)) [namespaced-field__30404__auto__ val__30406__auto__]))) (clojure.core/for [field__30401__auto__ (clojure.core/keys nil)] (clojure.core/when-let [val__30406__auto__ (clojure.core/get input field__30401__auto__)] [field__30401__auto__ val__30406__auto__])))) [:db/add (datomic.api/tempid :db.part/tx) :user/txCreated id])))"}})]}
```

Migrations are [edn](https://github.com/edn-format/edn) files that are maps with two keys, `:model` and `:txes`.

`:model` is a map containing a representation of the model. It's used to figure out if the latest migration we have is consistent with the arguments sent to `defmodel` in the code.

`:txes` is a list of transactions to be transacted by that migration. If you want to transact some arbitrary transaction items, you can just add it to the list.

Crustacean makes sure that migrations are only transacted once (we use [conformity](https://github.com/rkneufeld/conformity) on the backend to do this.)

We try to be smart about what we auto-generate in transactions. For instance, if you modify `user` to look like this:

```clojure
(defmodel user
  (:migration-dir "migrations/user/")
  (:fields   [name       :string :indexed :assignment-permitted]
             [last-name       :string :indexed :assignment-permitted]
             [email      :string :unique-value :indexed :assignment-required]
             [articles   :ref    :many :indexed :component])
  (:validators [email #"@"]))
```

and run `new-migration` again, you'll get the following migration

```clojure
{:model {:name "user",
         :fields {"name" [:string #{:assignment-permitted :indexed}],
                  "last-name" [:string #{:assignment-permitted :indexed}],
                  "email" [:string #{:indexed :assignment-required :unique-value}],
                  "articles" [:ref #{:indexed :component :many}]},
         :defaults nil,
         :validators {"email" #"@"}},
 :txes [({:db/index true,
          :db/valueType :db.type/string,
          :db/noHistory false,
          :db/isComponent false,
          :db.install/_attribute :db.part/db,
          :db/fulltext false,
          :db/cardinality :db.cardinality/one,
          :db/doc "",
          :db/id #db/id[:db.part/db -1000010],
          :db/ident :user/last-name}
         {:db/id #db/id[:db.part/user -1000011],
          :db/ident :user/exists?,
          :db/fn #db/fn{:lang :clojure, :imports [], :requires [], :params [db__30416__auto__ input__30417__auto__], :code "(clojure.core/let [unique-key-pairs__30418__auto__ (clojure.core/->> [\"email\"] (clojure.core/filter (fn* [p1__30413__30419__auto__] (clojure.core/contains? input__30417__auto__ (clojure.core/keyword p1__30413__30419__auto__)))) (clojure.core/map (clojure.core/juxt (fn* [p1__30414__30420__auto__] (clojure.core/keyword \"user\" p1__30414__30420__auto__)) (fn* [p1__30415__30421__auto__] (clojure.core/get input__30417__auto__ (clojure.core/keyword p1__30415__30421__auto__)))))) composite-key-pairs__30422__auto__ (clojure.core/->> nil (clojure.core/filter (clojure.core/fn [[a__30423__auto__ b__30424__auto__]] (clojure.core/and (clojure.core/contains? input__30417__auto__ a__30423__auto__) (clojure.core/contains? input__30417__auto__ b__30424__auto__)))) (clojure.core/map (clojure.core/fn [[a__30423__auto__ b__30424__auto__]] [(clojure.core/keyword \"user\" (clojure.core/name a__30423__auto__)) (clojure.core/get input__30417__auto__ a__30423__auto__) (clojure.core/keyword \"user\" (clojure.core/name b__30424__auto__)) (clojure.core/get input__30417__auto__ b__30424__auto__)])))] (clojure.core/seq (clojure.core/concat (datomic.api/q {:find (quote [[?e ...]]), :where (quote [[?e ?attr ?value]]), :in (quote [$ [[?attr ?value]]])} db__30416__auto__ unique-key-pairs__30418__auto__) (clojure.core/mapcat (clojure.core/fn [[attr1__30425__auto__ value1__30426__auto__ attr2__30427__auto__ value2__30428__auto__]] (datomic.api/q {:find (quote [[?e ...]]), :where (clojure.core/apply clojure.core/vector (clojure.core/seq (clojure.core/concat (clojure.core/list (clojure.core/apply clojure.core/vector (clojure.core/seq (clojure.core/concat (clojure.core/list (quote ?e)) (clojure.core/list attr1__30425__auto__) (clojure.core/list (quote ?value1)))))) (clojure.core/list (clojure.core/apply clojure.core/vector (clojure.core/seq (clojure.core/concat (clojure.core/list (quote ?e)) (clojure.core/list attr2__30427__auto__) (clojure.core/list (quote ?value2))))))))), :in (quote [$ ?value1 ?value2])} db__30416__auto__ value1__30426__auto__ value2__30428__auto__)) composite-key-pairs__30422__auto__))))"}}
         {:db/id #db/id[:db.part/user -1000012],
          :db/ident :user/malformed?,
          :db/fn #db/fn{:lang :clojure, :imports [], :requires [[schema.core]], :params [input__30430__auto__], :code "(clojure.core/let [checker__30431__auto__ (schema.core/checker (clojure.core/eval {(schema.core/optional-key :name) schema.core/Str, (schema.core/optional-key :last-name) schema.core/Str, :email (schema.core/both schema.core/Str #\"@\")}))] (checker__30431__auto__ input__30430__auto__))"}}
         {:db/id #db/id[:db.part/user -1000013],
          :db/ident :user/create,
          :db/fn #db/fn{:lang :clojure, :imports [], :requires [], :params [db id input], :code "(clojure.core/if-let [malformed__30409__auto__ (datomic.api/invoke db (clojure.core/keyword \"user\" \"malformed?\") input)] (throw (java.lang.IllegalArgumentException. (clojure.core/str malformed__30409__auto__))) (if (datomic.api/invoke db (clojure.core/keyword \"user\" \"exists?\") db input) (throw (java.lang.IllegalStateException. \"entity already exists\")) (clojure.core/vector (clojure.core/into {:db/id id} (clojure.core/concat (clojure.core/for [[field__30401__auto__ [type__30402__auto__ opts__30403__auto__]] {\"name\" [:string #{:assignment-permitted :indexed}], \"last-name\" [:string #{:assignment-permitted :indexed}], \"email\" [:string #{:indexed :assignment-required :unique-value}], \"articles\" [:ref #{:indexed :component :many}]}] (clojure.core/let [namespaced-field__30404__auto__ (clojure.core/keyword \"user\" field__30401__auto__) defaults__30405__auto__ nil val__30406__auto__ (clojure.core/cond (opts__30403__auto__ :assignment-required) (clojure.core/get input (clojure.core/keyword field__30401__auto__)) (clojure.core/and (opts__30403__auto__ :assignment-permitted) (clojure.core/contains? input (clojure.core/keyword field__30401__auto__))) (clojure.core/get input (clojure.core/keyword field__30401__auto__)) (clojure.core/and true (clojure.core/contains? defaults__30405__auto__ field__30401__auto__)) (clojure.core/let [default__30407__auto__ (clojure.core/get defaults__30405__auto__ field__30401__auto__)] (if (clojure.core/fn? default__30407__auto__) (default__30407__auto__ db input) default__30407__auto__)))] (clojure.core/when (clojure.core/not (clojure.core/nil? val__30406__auto__)) [namespaced-field__30404__auto__ val__30406__auto__]))) (clojure.core/for [field__30401__auto__ (clojure.core/keys nil)] (clojure.core/when-let [val__30406__auto__ (clojure.core/get input field__30401__auto__)] [field__30401__auto__ val__30406__auto__])))) [:db/add (datomic.api/tempid :db.part/tx) :user/txCreated id])))"}}
         {:db/id #db/id[:db.part/user -1000014],
          :db/ident :user/upsert,
          :db/fn #db/fn{:lang :clojure, :imports [], :requires [], :params [db id input], :code "(clojure.core/if-let [malformed__30411__auto__ (datomic.api/invoke db (clojure.core/keyword \"user\" \"malformed?\") input)] (throw (java.lang.IllegalArgumentException. (clojure.core/str malformed__30411__auto__))) (if (datomic.api/invoke db (clojure.core/keyword \"user\" \"exists?\") db input) (clojure.core/vector (clojure.core/into {:db/id id} (clojure.core/concat (clojure.core/for [[field__30401__auto__ [type__30402__auto__ opts__30403__auto__]] {\"name\" [:string #{:assignment-permitted :indexed}], \"last-name\" [:string #{:assignment-permitted :indexed}], \"email\" [:string #{:indexed :assignment-required :unique-value}], \"articles\" [:ref #{:indexed :component :many}]}] (clojure.core/let [namespaced-field__30404__auto__ (clojure.core/keyword \"user\" field__30401__auto__) defaults__30405__auto__ nil val__30406__auto__ (clojure.core/cond (opts__30403__auto__ :assignment-required) (clojure.core/get input (clojure.core/keyword field__30401__auto__)) (clojure.core/and (opts__30403__auto__ :assignment-permitted) (clojure.core/contains? input (clojure.core/keyword field__30401__auto__))) (clojure.core/get input (clojure.core/keyword field__30401__auto__)) (clojure.core/and false (clojure.core/contains? defaults__30405__auto__ field__30401__auto__)) (clojure.core/let [default__30407__auto__ (clojure.core/get defaults__30405__auto__ field__30401__auto__)] (if (clojure.core/fn? default__30407__auto__) (default__30407__auto__ db input) default__30407__auto__)))] (clojure.core/when (clojure.core/not (clojure.core/nil? val__30406__auto__)) [namespaced-field__30404__auto__ val__30406__auto__]))) (clojure.core/for [field__30401__auto__ (clojure.core/keys nil)] (clojure.core/when-let [val__30406__auto__ (clojure.core/get input field__30401__auto__)] [field__30401__auto__ val__30406__auto__])))) [:db/add (datomic.api/tempid :db.part/tx) :user/txUpdated id]) (clojure.core/vector (clojure.core/into {:db/id id} (clojure.core/concat (clojure.core/for [[field__30401__auto__ [type__30402__auto__ opts__30403__auto__]] {\"name\" [:string #{:assignment-permitted :indexed}], \"last-name\" [:string #{:assignment-permitted :indexed}], \"email\" [:string #{:indexed :assignment-required :unique-value}], \"articles\" [:ref #{:indexed :component :many}]}] (clojure.core/let [namespaced-field__30404__auto__ (clojure.core/keyword \"user\" field__30401__auto__) defaults__30405__auto__ nil val__30406__auto__ (clojure.core/cond (opts__30403__auto__ :assignment-required) (clojure.core/get input (clojure.core/keyword field__30401__auto__)) (clojure.core/and (opts__30403__auto__ :assignment-permitted) (clojure.core/contains? input (clojure.core/keyword field__30401__auto__))) (clojure.core/get input (clojure.core/keyword field__30401__auto__)) (clojure.core/and true (clojure.core/contains? defaults__30405__auto__ field__30401__auto__)) (clojure.core/let [default__30407__auto__ (clojure.core/get defaults__30405__auto__ field__30401__auto__)] (if (clojure.core/fn? default__30407__auto__) (default__30407__auto__ db input) default__30407__auto__)))] (clojure.core/when (clojure.core/not (clojure.core/nil? val__30406__auto__)) [namespaced-field__30404__auto__ val__30406__auto__]))) (clojure.core/for [field__30401__auto__ (clojure.core/keys nil)] (clojure.core/when-let [val__30406__auto__ (clojure.core/get input field__30401__auto__)] [field__30401__auto__ val__30406__auto__])))) [:db/add (datomic.api/tempid :db.part/tx) :user/txCreated id])))"}})]}
```

We only generated a schema element for the new field, and modified the database functions accordingly.

We can automatically handle the following schema alterations

- Creating new fields
- Deleting fields (they are renamed to `:unused/old-field/name`, as you can't [retract an attribute](https://groups.google.com/forum/#!msg/datomic/7-9lUE9Nm4k/fOhAvt-gyOIJ).)
- Renaming a field (if you run a migration with a single field name changed)

We don't handle:
- Altering schema attributes
- Adding values to an enum field

The above transactions need to be added to the `:txes` list of a migration by hand.

There is also a special case of `new-migration`, for when you want to regenerate the database functions of a model, even if it hasn't been changed. You can call it as `(new-migration entity "some-migration-name" true)` to do so. This is useful if some crustacean behavior has been updated, and you need to update your models accordingly.

## Applying migrations

Migrations for a model can be applied by calling `(crustacean.migrations/sync-model conn model)`.

Crustacean will keep a log of every model defined with `defmodel`, and you can sync all models by running `(crustacean.migrations/sync-all-models conn)`

# Creating) an entity

After we define a model, as above

```clojure
(ns models.user
  (:require [crustacean.core :refer [defmodel]]))
  
(defmodel user
  (:migration-dir "migrations/user/")
  (:fields   [name       :string :indexed :assignment-permitted]
             [email      :string :unique-value :indexed :assignment-required]
             [articles   :ref    :many :indexed :component])
  (:validators [email #"@"])))
```

we can create `user` entities.

The `models.user` namespace contains the `create` function.

```clojure
(models.user/create conn {:name "Sally"
                          :email "sallybowles@example.com"})
```

This will create a `user` entity, and return a representation of it.

We can also create an entity by calling the `:user/create` database function:

```clojure
(d/transact conn [:user/create (d/tempid :db.part/user) {:name "Sally"
                                                         :email "sallybowles@example.com"}])
```

Note that we expect a tempid as the argument. This is useful when you want to string together a series of `create` calls in one transaction that depend on eachother.

# Updating an entity

We can use datomics upsert behavior to update an entity by calling `upsert`:

```clojure
(models.user/upsert conn {:name "Sally's new name"
                          :email "sallybowles@example.com"})
```

As with `create`, we can calle the `:user/upsert` database function directly:
```clojure
(d/transact conn [:user/upsert (d/tempid :db.part/user) {:name "Sally's new name"
                                                         :email "sallybowles@example.com"}])
```
# Querying data

Crustacean generates three functions for querying data in each namespace `defmodel` is called in:

- `pull`
- `all-with`
- `find-by`

## `pull` 

`pull` is used to return a single entity from the db given it's id

```clojure
(user/pull db 17592186045425)
;; => {:created-at #inst "2016-03-28T01:58:42.766-00:00", :name "Sally", :articles [], :email "sallybowles@example.com", :id 17592186045425)
```

## `find-by`

`find-by` returns a single entity based on a query, consisting of a list of `:field`s and values:

```clojure
(user/find-by db :name "Sally")
;; => {:created-at #inst "2016-03-28T01:58:42.766-00:00", :name "Sally", :articles [], :email "sallybowles@example.com", :id 17592186045425}
```

## `all-with`

`all-with` returns a lazy-seq of entities based on a query, consisting of a list of `:field`s and values

```clojure
(user/all-with db :name "Sally")
;; =>
({:created-at #inst "2016-03-28T01:58:42.766-00:00",
  :name "Sally",
  :articles [17592186045435],
  :email "sallybowles@example.com",
  :id 17592186045425}
 {:created-at #inst "2016-03-28T02:13:33.689-00:00",
  :name "Sally",
  :articles [],
  :email "sallyfields@example.com",
  :id 17592186045437})
  
(user/all-with (d/db conn) :name "Sally" :email "sallybowles@example.com")
;; =>
({:created-at #inst "2016-03-28T01:58:42.766-00:00",
  :name "Sally",
  :articles [17592186045435],
  :email "sallybowles@example.com",
  :id 17592186045425})
```

You can also use `all-with` with only an attribute name to get all of the entities that have that attribute, regardless of value. This is useful for finding all of the entities that define a given model, just by using an attribute that they all have.

```clojure
(user/all-with (d/db conn) :email)
;; =>
({:created-at #inst "2016-03-28T01:58:42.766-00:00",
  :name "Sally",
  :articles [17592186045435],
  :email "sallybowles@example.com",
  :id 17592186045425}
 {:created-at #inst "2016-03-28T02:13:33.689-00:00",
  :name "Sally",
  :articles [],
  :email "sallyfields@example.com",
  :id 17592186045437})
```

# The entity representation

When ever we create, update, or query an entity, Crustacean returns the entity represented as a map.

For instance when creating a `user` as defined above, we get the following back:

```clojure
{:created-at #inst "2016-03-28T01:58:42.766-00:00",
 :name "Sally",
 :articles [],
 :email "sallybowles@example.com",
 :id 17592186045425}
```

The map contains all of the fields of the entity, along with two special fields, `:id` and `:created-at`

If the model contains `:computed-fields` those are returned here as well.

## `:refs`

References are returned as either ids or as maps, depending on whether the field type is defined as `:ref` or `[:ref some.model/name]`

For instance, if we have a second model, `article`,


```clojure
(ns models.article
  (:require [crustacean.core :refer [defmodel]]))
  
(defmodel article
  (:migration-dir "migrations/article/")
  (:fields   [title       :string :indexed :assignment-required])
  (:backrefs [:user/_articles :assignment-required]))
```

we can create `article`s, specifying the user they belong to now:

```clojure
;; assuming Sally is id 17592186045425
(create conn {:user/_articles 17592186045425 :title "My Article"})

(user/pull db 17592186045425)
;; =>
{:created-at #inst "2016-03-28T01:58:42.766-00:00",
 :name "Sally",
 :articles [17592186045435],
 :email "sallybowles@example.com",
 :id 17592186045425}
```

Suppose if `user` is instead defined as

```clojure
(defmodel user
  (:migration-dir "migrations/user/")
  (:fields   [name       :string :indexed :assignment-permitted]
             [email      :string :unique-value :indexed :assignment-required]
             [articles   [:ref models.article/article] :many :indexed :component])
  (:validators [email #"@"]))
```

We can now see a representation of the article when we query the user:

```
(user/pull db 17592186045425)
;; =>
{:created-at #inst "2016-03-28T01:58:42.766-00:00",
 :name "Sally",
 :articles
 [{:created-at #inst "2016-03-28T02:07:33.864-00:00",
   :title "My Article",
   :id 17592186045435}],
 :email "sallybowles@example.com",
 :id 17592186045425}
```




## License

Copyright © 2016 Canary Computer Corporation

Distributed under the Apache License version 2.0
