(ns clj-rate-limiter.core-test
  (:require [clojure.test :refer :all]
            [taoensso.carmine :as car]
            [clj-rate-limiter.core :refer :all]))

(def redis {:spec {:host "localhost" :port 6379 :timeout 5000}
            :pool {:max-active (* 3 (.availableProcessors (Runtime/getRuntime)))
                   :min-idle (.availableProcessors (Runtime/getRuntime))
                   :max-wait 5000}})

(defn clear-redis-fixture [f]
  (car/wcar redis
            (car/del "clj-rate-key1")
            (car/del "clj-rate-key2"))
  (f))

(use-fixtures :each clear-redis-fixture)

(defn- assert-rate-limiter [r]
  (=
   10
   (count (filter true? (repeatedly 100 #(allow? r "key1")))))
  (is (allow? r "key2"))
  (=
   9
   (count (filter true? (repeatedly 100 #(allow? r "key1")))))
  (Thread/sleep 1050)
  (=
   10
   (count (filter true? (repeatedly 100 #(allow? r "key1")))))
  (=
   10
   (count (filter true? (repeatedly 100 #(allow? r "key2"))))))

(deftest test-memory-limiter
  (testing "Test memory-based rate limiter"
    (let [rf (rate-limiter-factory :memory
                                   :interval 1000
                                   :max-in-interval 10)
          r (create rf)]
      (assert-rate-limiter r))))

(deftest test-redis-limiter
  (testing "Test redis-based rate limiter"
    (let [rf (rate-limiter-factory :redis
                                   :redis redis
                                   :interval 1000
                                   :max-in-interval 10)
          r (create rf)]
      (assert-rate-limiter r)
      (=
       100
       (car/wcar redis
                 (car/zcard "clj-rate-key1"))))))

(deftest test-flood-threshold
  (testing "test flood threshold."
    (let [rf (rate-limiter-factory :redis
                                   :redis {:spec {:host "localhost" :port 6379 :timeout 5000}
                                           :pool {:max-active (* 3 (.availableProcessors (Runtime/getRuntime)))
                                                  :min-idle (.availableProcessors (Runtime/getRuntime))
                                                  :max-wait 5000}}
                                   :interval 1000
                                   :flood-threshold 5
                                   :max-in-interval 10)
          r (create rf)]
      (assert-rate-limiter r)
      (= 51
         (car/wcar redis
                   (car/zcard "clj-rate-key1"))))))
