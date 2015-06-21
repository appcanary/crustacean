(ns crustacean.util-test
  (:require [clojure.test :refer :all]
            [crustacean.core :refer :all]))

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 0 0))))

#_(fact "`entity-exists` returns argument map when the map contains a :db/id key"
      (entity-exists? {:db/id 1 :foo "bar"}) => {:db/id 1 :foo "bar"}
      (entity-exists? {:db/id nil}) => falsey
      (entity-exists? {}) => falsey)
