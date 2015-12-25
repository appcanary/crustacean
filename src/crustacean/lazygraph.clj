(ns crustacean.lazygraph
  "Plumbing uses https://bitbucket.org/kotarak/lazymap in order to implement the lazy graph.

  That implementation of LazyMap doesn't have all the map semantics we may want, specifically it doesn't implement equivalence and LazyMapEntries aren't Seqs.

  Let's just implement our own"
  (:require [plumbing.graph :as graph]
            [potemkin.collections :refer [def-map-type]]))

;; TODO make this performant. It's about twice as slow as kotarak's lazymap. We should make the graph compile into a custom type that behaves like a lazy map, ala the eager graph

(defn force-lazy-map
  "Returns a map with all the delays forced"
  [lm]
  (into {}
        (for [[k v] lm]
          [k
           (if (instance? clojure.lang.Delay v)
             @v
             v)])))

(def-map-type LazyMap [m mta]
  (get [_ k default-value]
       (if (contains? m k)
         (let [v (get m k)]
           (if (instance? clojure.lang.Delay v)
             @v
             v))
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


(defn lazy-compile
  "Compile graph specification g to a corresponding fnk that returns a
   lazymap of the node result fns on a given input.  This fnk returns
   the lazymap immediately, and node values are computed and cached as needed
   as values are extracted from the lazymap.  Besides this lazy behavior,
   the lazymap can be used interchangeably with an ordinary Clojure map.
   Required inputs to the graph are checked lazily, so you can omit input
   keys not required by unneeded output keys."
  [g]
  (graph/simple-hierarchical-compile
   g
   false
   (fn [m] (reduce-kv assoc (lazy-map {}) m)) ;; into is extremely slow on lazymaps.
   (fn [m k f] (assoc m k (delay (graph/restricted-call f m))))))
