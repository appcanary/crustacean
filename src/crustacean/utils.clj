(ns crustacean.utils)

(defn entity-exists?
  "Checks that an entity returned by d/pull actually exists"
  [entity]
  (when (:db/id entity)
    entity))

(defn normalize
  [k]
  (if (= (first (name k)) \_)
    (keyword (namespace k))
    (keyword (name k))))

(defn entity-map
  "If it's a Datomic entity map make sure we get the :db/id key

  Datomic doesn't include this in (keys entity) for the entity API for some reason"
  [x]
  (cond (map? x)
        x

        (instance? datomic.query.EntityMap x)
        (select-keys x (conj (keys x) :db/id))))

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
  "Writes a form to file regardless of its size"
  [filepath data]
  (with-open [w (clojure.java.io/writer filepath)]
    (binding [*print-length* false
              *out* w]
      (pr data))))
