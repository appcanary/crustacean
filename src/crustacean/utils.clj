(ns crustacean.utils
  (:require [puget.printer :as puget]))

(defn entity-exists?
  "Checks that an entity returned by d/pull actually exists"
  [entity]
  (when (:db/id entity)
    entity))

;; only used in norm-keys
(defn normalize
  [k]
  (if (= (first (name k)) \_)
    (keyword (namespace k))
    (keyword (name k))))

;; see below
(defn entity-map
  "If it's a Datomic entity map make sure we get the :db/id key

  Datomic doesn't include this in (keys entity) for the entity API for some reason"
  [x]
  (cond (map? x)
        x

        (instance? datomic.query.EntityMap x)
        (select-keys x (conj (keys x) :db/id))))

;; TODO: we use this in canary artifact, otherwise dead code
(defn normalize-keys
  [mp]
  (if-let [mp (entity-map mp)]
    (reduce
     (fn [m [k v]]
       (if (or (sequential? v) (set? v))
         (assoc m (normalize k) (mapv normalize-keys v))
         (assoc m (normalize k) (normalize-keys v))))
     {} mp)
    mp))

(defn remove-nils
  "Remove keys with nil values"
  [input-map]
  (into {} (remove #(nil? (second %)) input-map)))

(defn fields-with
  "Returns names of fields in an model with given option set"
  [model attr]
  (->> (:fields model)
       (filter (fn [[field [type opts]]]
                 (opts attr)))
       (map first)))

(defn unique-fields
  "Returns the name of fields set to unique"
  [model]
  (concat (fields-with model :unique-value)
          (fields-with model :unique-identity)))

(defn spit-edn
  "Writes a form to file. We pretty-print with special care to render datomic objects correctly"
  [filepath data]
  (with-open [w (clojure.java.io/writer filepath)]
    (binding [*print-length* false
              *out* w]
      (puget/pprint data {:print-handlers {datomic.db.DbId puget/pr-handler
                                           datomic.function.Function puget/pr-handler}
                          :sort-keys false
                          :width 100}))))


(defmacro defn-db
  "Defines a database function"
  [name & body]
  (let [doc (when (string? (first body))
              (first body))
        body (if doc (rest body) body)
        opts (when (map? (first body))
               (first body))
        body (if opts (rest body) body)
        params (first body)
        body (rest body)
        conds (when (and (next body) (map? (first body)))
                (first body))
        body (if conds (next body) body)
        pre (:pre conds)
        post (:post conds)
        body (if post
               `((let [~'% ~(if (< 1 (count body))
                              `(do ~@body)
                              (first body))]
                   ~@(map (fn* [c] `(assert ~c)) post)
                   ~'%))
               body)
        body (if pre
               (concat (map (fn* [c] `(assert ~c)) pre)
                       body)
               body)]
    (if (not (vector? params))
      (throw (IllegalArgumentException. "Parameter declaration should be a vector")))
    `(def
       ~(vary-meta name assoc :doc doc)
       (d/function (merge ~opts
                          '{:lang :clojure
                            :params ~params
                            :code (do ~@body)})))))
