(ns clj-rate-limiter.core
  (:import (java.util TimerTask Timer))
  (:require [taoensso.carmine :as car]
            [clojure.core.cache :as cache]))

(defn- ttl-cache [interval]
  (atom (cache/ttl-cache-factory {} :ttl interval)))

(defprotocol RateLimiter
  "Rate limiter for clojure."
  (allow? [this id]
          "Return true if the request can be allowd by rate limiter.")
  (permit? [this id]
           "Return {:result true} if the request
            can be permited by rate limiter,
            Otherwise returns {:result false :current requests}.")
  (remove-permit [this id ts]
                 "Remove the permit by id and permit timestamp."))

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

(definline mills->nanos [m]
  `(* 1000000 ~m))

(definline nanos->mills [n]
  `(long (/ ~n 1000000)))

(defn- calc-result [now first-req too-many-in-interval? time-since-last-req min-difference interval]
  (if (or too-many-in-interval?
          (when (and min-difference time-since-last-req)
            (< time-since-last-req (mills->nanos min-difference))))
    (long (Math/floor
           (min
            (nanos->mills
             (+ (- first-req now) (mills->nanos interval)))
            (if min-difference
              (- min-difference (nanos->mills time-since-last-req))
              (Double/MAX_VALUE)))))
    0))

(deftype MemoryRateLimiterFactory [opts]
  RateLimiterFactory
  (create [this]
    (let [timeouts (atom {})
          {:keys [interval min-difference max-in-interval
                  namespace flood-threshold]
           :or {namespace "clj-rate"}} opts
          flood-cache (ttl-cache interval)
          lock (Object.)
          storage (atom {})]
      (reify RateLimiter
        (allow? [this id]
          (:result (permit? this id)))
        (permit? [_ id]
          ;;It must not be in flood cache
          (when-not (and
                     flood-threshold
                     (cache/lookup @flood-cache (or id "")))
            (let [id (or id "")
                  now (System/nanoTime)
                  key (format "%s-%s" namespace id)
                  before (- now (mills->nanos interval))]
              (when-let [t (get @timeouts key)]
                (clear-timeout t))
              (let [user-set (locking lock
                               (let [new-set (apply sorted-set
                                                    (when-let [s (get @storage key)]
                                                      (subseq s > before)))]
                                 (swap! storage assoc key new-set)
                                 new-set))
                    too-many-in-interval? (>= (count user-set) max-in-interval)
                    flood-req? (and
                                flood-threshold
                                too-many-in-interval?
                                (>= (count user-set)
                                    (* flood-threshold max-in-interval)))
                    time-since-last-req (when (and min-difference (last user-set))
                                          (- now (last user-set)))]
                (when flood-req?
                  (swap! flood-cache
                         assoc id true))
                (let [ret (calc-result now
                                       (first user-set)
                                       too-many-in-interval?
                                       time-since-last-req
                                       min-difference interval)]
                  (swap! storage update-in [key] (fn [s] (conj (or s (sorted-set)) now)))
                  (swap! timeouts assoc key (set-timeout (fn []
                                                           (swap! storage dissoc key)) (:interval opts)))
                  (let [ret ((complement pos?) ret)]
                    (if ret
                      {:result ret :ts now}
                      {:result ret :ts now :current (count user-set)})))))))
        (remove-permit [_ id ts]
          (let [id (or id "")
                key (format "%s-%s" namespace id)]
            (when ts
              (swap! storage update-in [key]
                     (fn [s] (disj (or s (sorted-set)) ts))))))))))

(defn- exec-batch [redis pool key before now interval]
  (car/wcar {:spec redis
             :pool pool}
            (car/multi)
            (car/zremrangebyscore key 0 before)
            (car/zcard key)
            (car/zrangebyscore key "-inf" "+inf"
                               "LIMIT" 0 1)
            (car/zrevrangebyscore key "+inf" "-inf"
                                  "LIMIT" 0 1)
            (car/zadd key now now)
            (car/expire key (long (Math/ceil (/ interval 1000))))
            (car/exec)))

(deftype RedisRateLimiterFactory [opts]
  RateLimiterFactory
  (create [this]
    (let [{:keys [interval min-difference max-in-interval namespace redis
                  flood-threshold
                  pool]
           :or {namespace "clj-rate"}} opts
          flood-cache (ttl-cache interval)]
      (reify RateLimiter
        (allow? [this id]
          (:result (permit? this id)))
        (permit? [_ id]
          (when-not (and flood-threshold
                         (cache/lookup @flood-cache (or id "")))
            (let [id (or id "")
                  now (System/nanoTime)
                  key (format "%s-%s" namespace id)
                  before (- now (mills->nanos interval))]
              (let [[_ _ _ _ _ _ _
                     [_ total [first-req] [last-req] _ _]] (exec-batch redis
                                                                       pool
                                                                       key
                                                                       before
                                                                       now
                                                                       interval)
                    too-many-in-interval? (>= total max-in-interval)
                    flood-req? (and flood-threshold
                                    too-many-in-interval?
                                    (>= total
                                        (* flood-threshold max-in-interval)))
                    time-since-last-req (when (and min-difference last-req)
                                          (- now (Long/valueOf last-req)))]
                (when flood-req?
                  (swap! flood-cache
                         assoc id true))
                (let [ret ((complement pos?)
                           (calc-result now
                                        (when first-req
                                          (Long/valueOf first-req))
                                        too-many-in-interval?
                                        time-since-last-req
                                        min-difference interval))]
                  (if ret
                    {:result ret :ts now}
                    {:result ret :ts now :current total}))))))
        (remove-permit [_ id ts]
          (let [id (or id "")
                key (format "%s-%s" namespace id)]
            (when ts
              (car/wcar {:spec redis
                         :pool pool}
                        (car/zrem key ts)))))))))

(defn rate-limiter-factory
  "Returns a rate limiter factory by type and options.
   Valid type includes :memory and :redis, for example:
      ;;Max 100 requests in 1 seconds.
      (def rt (rate-limiter-factory :memory
                                    :interval 1000
                                    :max-in-interval 100))
  "
  [type & {:as opts}]
  (case type
    :memory (MemoryRateLimiterFactory. opts)
    :redis (RedisRateLimiterFactory. opts)
    (throw (ex-info (format "Unknow rate limiter type:%s" type) {:type type}))))


(comment
  (defn- benchmark []
   (let [rf (rate-limiter-factory :redis
                                  :redis {:spec {:host "localhost" :port 6379 :timeout 5000}
                                          :pool {:max-active (* 3 (.availableProcessors (Runtime/getRuntime)))
                                                 :min-idle (.availableProcessors (Runtime/getRuntime))
                                                 :max-wait 5000}}
                                  :flood-threshold 10
                                  :interval 1000
                                  :max-in-interval 1000)
         r (create rf)
         cl (java.util.concurrent.CountDownLatch. 100)]
     (time
      (do
        (dotimes [n 150]
          (->
           (fn []
             (dotimes [m 10000]
               (let [{:keys [ts result]} (allow? r (mod m 20))]
                 (remove-permit r (mod m 20) ts)))
             (.countDown cl))
           (Thread.)
           (.start)))
        (.await cl))))))
