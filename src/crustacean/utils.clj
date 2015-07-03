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

(defn normalize-keys
  [mp]
  (if (map? mp)
    (reduce
     (fn [m [k v]]
       (if (or (seq? v) (set? v))
         (assoc m (normalize k) (mapv normalize-keys v))
         (assoc m (normalize k) (normalize-keys v))))
     {} mp)
    mp))

(defn remove-nils
  "Remove keys with nil values"
  [input-map]
  (into {} (remove #(nil? (second %)) input-map)))

(defn fields-with
  "Returns names of fields in an entity with given option set"
  [entity attr]
  (->> (:fields entity)
       (filter (fn [[field [type opts]]]
                 (opts attr)))
       (map first)))

(defn unique-fields
  "Returns the name of fields set to unique "
  [entity]
  (concat (fields-with entity :unique-value)
          (fields-with entity :unique-identity)))
