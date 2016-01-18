(ns crustacean.db-funcs
  (:require [datomic.api :as d]
            [crustacean.utils :refer [normalize-keys entity-exists? fields-with unique-fields remove-nils]]))

(defn create-fn
  "The `create` database function for a given entity"
  [entity]
  (let [fields (:fields entity)
        defaults (read-string (:raw-defaults entity))
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
(defn exists-fn
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
                   (mapcat (fn [[attr1# value1# attr2# value2#]]
                             (d/q {:find '[[~'?e ...]] :in '[~'$ ~'?value1 ~'?value2] :where `[[~'~'?e ~attr1# ~'~'?value1] [~'~'?e  ~attr2# ~'~'?value2]]} db# value1# value2#))
                           composite-key-pairs#))))}))

(defn malformed-fn
  "The malformed? database function for a given entity"
  [{:keys [raw-input-schema] :as model}]
  (d/function
   `{:lang :clojure
     :requires [[schema.core]]
     :params [input#]
     :code (let [checker#  (schema.core/checker (eval ~(read-string raw-input-schema)))]
             (checker# input#))}))
