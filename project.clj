(defproject appcanary/crustacean "0.1.6-SNAPSHOT"
  :description "CRU operations & migrations for datomic"
  :url "http://example.com/FIXME"
  :license {:name "Apache License Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[prismatic/schema "1.0.4"]
                 [prismatic/plumbing "0.5.2"]

                 [org.flatland/ordered "1.5.3"]
                 
                 [com.datomic/datomic-free "0.9.5327"]
                 [io.rkn/conformity "0.3.3" :exclusions [com.datomic/datomic-free]]
                 [datomic-schema "1.2.2"]
                 [potemkin "0.4.3"]]
  :profiles {:repl {:main user}
             :1.7 {:dependencies [[org.clojure/clojure "1.7.0"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0-RC4"]]}
             :dev {:source-paths ["dev"]
                   :plugins [[lein-midje "3.1.3"]]
                   :dependencies [[midje "1.7.0-beta1"]]}})
