(ns clj-rate-limiter.core
  (:import (java.util TimerTask Timer))
  (:require [taoensso.carmine :as car]))


(defprotocol RateLimiter
  "Rate limiter for clojure."
  (allow? [this id] "Return true if the request can be allowd by rate limiter."))

(defprotocol RateLimiterFactory
  "A factory to create RateLimiter"
  (create [this] "Return an RateLimiter instance."))

(defn- set-timeout [f interval]
  (let [task (proxy [TimerTask] []
               (run [] (f)))
        timer (new Timer)]
    (.schedule timer task (long interval))
    timer))

(defn- clear-timeout [^Timer timer]
  (try
    (.cancel timer)
    (catch Throwable _)))

(defn- calc-result [now user-set too-many-in-interval? time-since-last-req min-difference interval]
  (if (or too-many-in-interval?
          (when min-difference
            (< time-since-last-req min-difference)))
    (long (Math/floor
           (min
            (+ (- (first user-set) now) interval)
            (if min-difference
              (- min-difference time-since-last-req)
              (Double/MAX_VALUE)))))
    0))

(deftype MemoryRateLimiterFactory [opts]
  RateLimiterFactory
  (create [this]
    (let [timeouts (atom {})
          {:keys [interval min-difference max-in-interval namespace]} opts
          lock (Object.)
          storage (atom {})]
      (reify RateLimiter
        (allow? [_ id]
          (let [id (or id "")
                now (System/currentTimeMillis)
                key (format "%s-%s" namespace id)
                before (- now interval)]
            (when-let [t (get @timeouts id)]
              (clear-timeout t))
            (let [user-set (locking lock
                             (let [new-set (filter #(> % before) (get @storage id))]
                               (swap! storage assoc id new-set)
                               new-set))
                  too-many-in-interval? (> (count user-set) max-in-interval)
                  time-since-last-req (when min-difference
                                        (- now (last user-set)))]
              (let [ret (calc-result now
                                     user-set
                                     too-many-in-interval?
                                     time-since-last-req
                                     min-difference interval)]
                (swap! storage update-in [id] (fn [s] (conj (or s []) now)))
                (swap! timeouts assoc id (set-timeout (fn []
                                                        (swap! storage dissoc id)) (:interval opts)))
                (= ret 0)))))))))

(deftype RedisRateLimiterFactory [opts]
  RateLimiterFactory
  (create [this]
    (let [{:keys [interval min-difference max-in-interval namespace redis
                  pool]} opts]
      (reify RateLimiter
        (allow? [_ id]
          (let [id (or id "")
                now (System/currentTimeMillis)
                key (format "%s-%s" namespace id)
                before (- now interval)]
            (let [[_ _ _ _ _ [_ user-set _ _]] (car/wcar {:spec redis
                                                          :pool pool}
                                                         (car/multi)
                                                         (car/zremrangebyscore key 0 before)
                                                         (car/zrange key 0 -1)
                                                         (car/zadd key now now)
                                                         (car/expire key (long (Math/ceil (/ interval 1000))))
                                                         (car/exec))
                  user-set (map #(Long/valueOf ^String %) user-set)
                  too-many-in-interval? (> (count user-set) max-in-interval)
                  time-since-last-req (when min-difference
                                        (- now (last user-set)))]
              (zero?
                 (calc-result now
                              user-set
                              too-many-in-interval?
                              time-since-last-req
                              min-difference interval)))))))))


(defn rate-limiter-factory [type & {:as opts}]
  (case type
    :memory (MemoryRateLimiterFactory. opts)
    :redis (RedisRateLimiterFactory. opts)
    (throw (ex-info (format "Unknow rate limiter type:%s" type) {:type type}))))
