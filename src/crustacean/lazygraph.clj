(ns crustacean.lazygraph
  "Plumbing uses https://bitbucket.org/kotarak/lazymap in order to implement the lazy graph.

  That implementation of LazyMap doesn't have all the map semantics we may want, specifically it doesn't implement equivalence and LazyMapEntries aren't Seqs.

  Let's just implement our own"
  (:use plumbing.core)
  (:require
   [plumbing.graph :as graph]
   [schema.core :as s]
   [plumbing.fnk.schema :as schema]
   [plumbing.fnk.pfnk :as pfnk]
   [plumbing.fnk.impl :as fnk-impl]
   [potemkin.collections :refer [def-map-type]])
  (:import
   clojure.lang.IFn))

(defn force-lazy-map
  "Returns a map with all the delays forced"
  [lm]
  (into {}
        (for [[k v] lm]
          [k (force v)])))

(def-map-type LazyMap [m mta]
  (get [_ k default-value]
       (if (contains? m k)
         (let [v (get m k)]
           (force v))
         default-value))
  (assoc [_ k v]
         (LazyMap. (assoc m k v) mta))
  (dissoc [_ k]
          (LazyMap. (dissoc m k) mta))
  (keys [_]
        (keys m))
  (meta [_]
        mta)
  (with-meta [_ mta]
    (LazyMap. m mta))
  ;; Like lazy-seqs, we check equivalence after forcing the map
  (equiv [this x]
         (and (map? x) (= x (force-lazy-map this))))
  (equals [this x]
          (or (identical? this x)
              (and
               (map? x)
               (= x (force-lazy-map m)))))
  (hashCode [_]
            (.hashCode (force-lazy-map m))))



(defn lazy-map
  "Create a lazy map from a map"
  [m]
  (LazyMap. m {}))

;; The functions below are from https://github.com/Prismatic/plumbing/blob/6f9f1b6453ed2c978a619dc99bb0317d8c053141/src/plumbing/graph/positional.clj
;; We need to modify Prismatic's graph library slightly to use our LazyMap implemetation to wrap the graph record

(defn def-graph-record
  "Define a record for the output of a graph. It is usable as a function to be
  as close to a map as possible. Return the typename."
  ([g] (def-graph-record g (gensym "graph-record")))
  ([g record-type-name]
     ;; NOTE: This eval is needed because we want to define a record based on
     ;; information (a graph) that's only available at runtime.
     (eval `(defrecord ~record-type-name ~(->> g
                                               pfnk/output-schema
                                               keys
                                               (mapv (comp symbol name)))
              IFn
              (invoke [this# k#]
                (get this# k#))
              (invoke [this# k# not-found#]
                (get this# k# not-found#))
              (applyTo [this# args#]
                (apply get this# args#))))
     record-type-name))

(defn graph-let-bindings
  "Compute the bindings for functions and intermediates needed to form the body
  of a positional graph, E.g.
    [`[[f-3 ~some-function]] `[[intermediate-3 (f-3 intermediate-1 intermediate-2)]]]"
  [g g-value-syms]

  (->> g
       (map (fn [[kw f]]
              (let [f-sym (-> kw name (str "-fn") gensym)
                    arg-forms (map-from-keys g-value-syms (pfnk/input-schema-keys f))
                    [f arg-forms] (fnk-impl/efficient-call-forms f arg-forms)]
                ;; Wrap original implementation with a delay & force
                [[f-sym f] [(g-value-syms kw) `(delay (~f-sym ~@(map #(list 'force %) arg-forms)))]])))
       (apply map vector)))

(defn eval-bound
  "Evaluate a form with some symbols bound to some values."
  [form bindings]
  ((eval `(fn [~(mapv first bindings)] ~form))
   (map second bindings)))

(defn graph-form
  "Construct [body-form bindings-needed-for-eval] for a positional graph."
  [g arg-keywords]
  (let [value-syms (->> g
                        pfnk/io-schemata
                        (mapcat schema/explicit-schema-key-map)
                        (map key)
                        (map-from-keys (comp gensym name)))
        [needed-bindings value-bindings] (graph-let-bindings g value-syms)
        record-type (def-graph-record g)]
     [`(fn
        positional-graph#  ;; Name it just for kicks.
        ~(mapv value-syms arg-keywords)
        (let ~(vec (apply concat value-bindings))
          (lazy-map (new ~record-type ~@(->> g pfnk/output-schema keys (mapv value-syms))))))
     needed-bindings]))

(defn positional-flat-compile
  "Positional compile for a flat (non-nested) graph."
  [g]
  (let [arg-ks (->> g pfnk/input-schema-keys)
        [positional-fn-form eval-bindings] (graph-form g arg-ks)
        input-schema (pfnk/input-schema g)
        pos-fn-sym (gensym "pos")
        input-schema-sym (gensym "input-schema")
        output-schema-sym (gensym "output-schema")]
    (vary-meta ;; workaround evaluation quirks
     (eval-bound
      `(let [~pos-fn-sym ~positional-fn-form]
         ~(fnk-impl/positional-fnk-form
           (fnk-impl/schema-override 'graph-positional output-schema-sym)
           input-schema-sym
           (vec (schema/explicit-schema-key-map input-schema))
           (into {} (for [k (keys (schema/explicit-schema-key-map input-schema))] [k (symbol (name k))]))
           (list `(~pos-fn-sym ~@(mapv (comp symbol name) arg-ks)))))
      (into eval-bindings
            [[input-schema-sym input-schema]
             [output-schema-sym (pfnk/output-schema g)]]))
     assoc :schema (let [[is os] (pfnk/io-schemata g)] (s/=> os is)))))

;; Ports of prismatic eager-compile & positional-eager-compile
(defn lazy-compile
  "Compile graph specification g to a corresponding fnk that is optimized for
   speed. Wherever possible, fnks are called positionally, to reduce the
   overhead of creating and destructuring maps, and the return value is a
   record, which is much faster to create and access than a map.  Compilation
   is relatively slow, however, due to internal calls to 'eval'."
  [g]
  (if (fn? g)
    g
    (let [g (for [[k sub-g] (graph/->graph g)]
              [k (lazy-compile sub-g)])]
      (positional-flat-compile (graph/->graph g)))))

(defn positional-lazy-compile
  "Like eager-compile, but produce a non-keyword function that can be called
   with args in the order provided by arg-ks, avoiding the overhead of creating
   and destructuring a top-level map.  This can yield a substantially faster
   fn for Graphs with very computationally inexpensive node fnks."
  [g arg-ks]
  (fnk-impl/positional-fn (lazy-compile g) arg-ks))
