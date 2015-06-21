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
       (if (sequential? v)
         (assoc m (normalize k) (mapv normalize-keys v))
         (assoc m (normalize k) (normalize-keys v))))
     {} mp)
    mp))

