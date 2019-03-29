(ns clj-rate-limiter.core-test
  (:require [clojure.test :refer :all]
            [taoensso.carmine :as car]
            [clj-rate-limiter.core :refer :all]))
(def redis-spec {:host "localhost" :port 6379 :timeout 5000})
(def redis {:spec redis-spec :pool {}})
(defmacro wcar* [& body] `(car/wcar redis ~@body))

(deftest test-redis-common
  (testing "测试redis连接"
    (is (= (car/wcar redis (car/set "haoe" 1222)) "OK"))
    (is (= (wcar* (car/get "haoe")) "1222"))
    ))
(defn clear-redis-fixture [f]
  (car/wcar redis
            (car/del "clj-rate-key1")
            (car/del "clj-rate-key2")
            (car/del "clj-rate-key3")
            (car/del "clj-rate-key4"))
  (f))

(use-fixtures :each clear-redis-fixture)

(defn- assert-rate-limiter [r]
  (is (=
       10
       (count (filter true? (repeatedly 100 #(allow? r "key1"))))))
  (is (allow? r "key2"))
  (is (=
       9
       (count (filter true? (repeatedly 100 #(allow? r "key2"))))))
  (is (=
       10
       (count (filter true? (map :result (repeatedly 100 #(permit? r "key3")))))))
  (Thread/sleep 1050)
  (is (=
       10
       (count (filter true? (repeatedly 100 #(allow? r "key1"))))))
  (is (=
       10
       (count (filter true? (repeatedly 100 #(allow? r "key2"))))))
  (let [results (repeatedly 100 #(permit? r "key3"))]
    (is (=
         10
         (count (filter true? (map :result results)))))))

(defn- assert-remove-permit [r]
  (let [{:keys [ts result]} (permit? r "key4")]
    (is (true? result))
    (let [{:keys [ts result]} (permit? r "key4")]
      (is (not result))
      (remove-permit r "key4" ts))
    (remove-permit r "key4" ts)
    (is (allow? r "key4"))))

(deftest test-memory-limiter
  (testing "Test memory-based rate limiter"
    (let [rf (rate-limiter-factory :memory
                                   :interval 1000
                                   :max-in-interval 10)
          r (create rf)]
      (assert-rate-limiter r))
     (let [rf (rate-limiter-factory :memory
                                   :interval 1000
                                   :max-in-interval 1)
          r (create rf)]
      (assert-remove-permit r))))

(deftest test-redis-limiter
  (testing "Test redis-based rate limiter"
    (let [rf (rate-limiter-factory :redis
                                   :redis redis
                                   :interval 1000
                                   :max-in-interval 10)
          r (create rf)]
      (assert-rate-limiter r)
      (is (=
           100
           (car/wcar redis
                     (car/zcard "clj-rate-key1")))))
    (let [rf (rate-limiter-factory :redis
                                   :redis redis
                                   :interval 1000
                                   :max-in-interval 1)
          r (create rf)]
      (assert-remove-permit r))))

(deftest test-flood-threshold
  (testing "test flood threshold."
    (let [rf (rate-limiter-factory :redis
                                   :redis {:spec redis-spec
                                           :pool {}
                                           }
                                   :interval 1000
                                   :flood-threshold 5
                                   :max-in-interval 10)
          r (create rf)]
      (assert-rate-limiter r)
      (is (= 51
             (car/wcar redis
                       (car/zcard "clj-rate-key1"))))
      (Thread/sleep 1500)
      (assert-rate-limiter r))))

(deftest test-min-difference
  (testing "test flood threshold."
    (let [rf (rate-limiter-factory :redis
                                   :redis {:spec redis-spec
                                           :pool {}}
                                   :interval 1000
                                   :min-difference 10
                                   :flood-threshold 5
                                   :max-in-interval 10)
          r (create rf)]
      (is (allow? r "key1"))
      (is (not (allow? r "key1")))
      (is (allow? r "key2"))
      (Thread/sleep 15)
      (is (allow? r "key1"))
      (is (not (allow? r "key1")))
      (is (allow? r "key2")))))
