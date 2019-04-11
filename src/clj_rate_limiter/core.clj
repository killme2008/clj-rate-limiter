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
                               (let [new-set (into {} (->> key
                                                           (get @storage)
                                                           (filter #(-> % first (> before)))))]
                                 (swap! storage assoc key new-set)
                                 new-set))
                    total (count user-set)
                    user-set (filter #(-> % second not) user-set)
                    current (count user-set)
                    too-many-in-interval? (>= current max-in-interval)
                    flood-req? (and
                                flood-threshold
                                too-many-in-interval?
                                (>= current
                                    (* flood-threshold max-in-interval)))
                    time-since-last-req (when (and min-difference
                                                   (first (last user-set)))
                                          (- now (first (last user-set))))]
                (when flood-req?
                  (swap! flood-cache
                         assoc id true))
                (let [ret (calc-result now
                                       (ffirst user-set)
                                       too-many-in-interval?
                                       time-since-last-req
                                       min-difference interval)]
                  (swap! storage update-in [key] (fn [s] (assoc s
                                                                now false)))
                  (swap! timeouts assoc key (set-timeout (fn []
                                                           (swap! storage dissoc key)) (:interval opts)))
                  (let [ret ((complement pos?) ret)]
                    (if ret
                      {:result ret :ts now :current current :total total}
                      {:result ret :ts now :current current :total total})))))))
        (remove-permit [_ id ts]
          (let [id (or id "")
                key (format "%s-%s" namespace id)]
            (when ts
              (swap! storage update-in [key]
                     (fn [s] (assoc s ts true))))))))))


(defn- release-key [key]
  (format "%s-rs" key))

(defn- exec-batch [redis pool key stamp before now expire-secs min-difference]
  (car/wcar {:spec redis
             :pool pool}
            (car/multi)
            (car/zremrangebyscore key 0 before)
            (car/zcard key)
            (car/zcard (release-key key))
            (car/zrangebyscore key "-inf" "+inf"
                               "LIMIT" 0 1)
            (when min-difference
              (car/zrevrangebyscore key "+inf" "-inf"
                                    "LIMIT" 0 1))
            (car/zadd key now stamp)
            (car/expire key expire-secs)
            (car/exec)))

(defn- match-exec-ret [ret min-difference]
  (if min-difference
    (let [[_ total rs-total
           [first-req] [last-req] _ _] (last ret)]
      [total rs-total first-req last-req])
    (let [[_ total rs-total [first-req] _ _] (last ret)]
      [total rs-total first-req])))

(defn- calc-result-in-millis [now first-req too-many-in-interval? time-since-last-req min-difference interval]
  (if (or too-many-in-interval?
          (when (and min-difference time-since-last-req)
            (< time-since-last-req min-difference)))
    (long (Math/floor
            (min
              (+ (- first-req now) interval)
              (if min-difference
                (- min-difference time-since-last-req)
                (Double/MAX_VALUE)))))
    0))

(deftype RedisRateLimiterFactory [opts]
  RateLimiterFactory
  (create [this]
    (let [{:keys [interval min-difference max-in-interval namespace redis
                  flood-threshold
                  pool]
           :or {namespace "clj-rate"}} opts
          flood-cache (ttl-cache interval)
          expire-secs (long (Math/ceil (/ interval 1000)))]
      (reify RateLimiter
        (allow? [this id]
          (:result (permit? this id)))
        (permit? [_ id]
          (when-not (and flood-threshold
                         (cache/lookup @flood-cache (or id "")))
            (let [id (or id "")
                  stamp (System/nanoTime)
                  now (System/currentTimeMillis)
                  key (format "%s-%s" namespace id)
                  before (- now interval)]
              (let [exec-ret (exec-batch redis
                                         pool
                                         key
                                         stamp
                                         before
                                         now
                                         expire-secs
                                         min-difference)
                    [total rs-total first-req last-req] (match-exec-ret exec-ret min-difference)
                    too-many-in-interval? (>= total max-in-interval)
                    flood-req? (and flood-threshold
                                    too-many-in-interval?
                                    (>= total
                                        (* flood-threshold max-in-interval)))
                    time-since-last-req (when (and min-difference last-req)
                                          (- now (Long/valueOf ^String last-req)))]
                (when flood-req?
                  (swap! flood-cache
                         assoc id true))
                (let [ret ((complement pos?)
                            (calc-result-in-millis now
                                                   (when first-req
                                                     (Long/valueOf ^String first-req))
                                                   too-many-in-interval?
                                                   time-since-last-req
                                                   min-difference interval))]
                  (if ret
                    {:result ret :ts stamp
                     :current total :total (+ total rs-total)}
                    {:result ret :ts stamp
                     :current total :total (+ total rs-total)}))))))
        (remove-permit [_ id ts]
          (let [id (or id "")
                key (format "%s-%s" namespace id)
                before (- (System/currentTimeMillis) interval)]
            (when (and ts (pos? ts))
              (car/wcar {:spec redis
                         :pool pool}
                        (car/multi)
                        (car/zrem key ts)
                        (car/zremrangebyscore (release-key key) 0 before)
                        (car/zadd (release-key key) ts ts)
                        (car/expire (release-key key)
                                    expire-secs)
                        (car/exec)))))))))

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
                                  :max-in-interval 100000)
         r (create rf)
         cost (atom {:total 0
                     :current 0
                     :max_total 0
                     :max_concurrent 0
                     :times 0})
         ts 100
         cl (java.util.concurrent.CountDownLatch. ts)]
     (time
      (do
        (dotimes [n ts]
          (->
           (fn []
             (dotimes [m 10000]
               (let [{:keys [ts result total current]} (permit? r (mod m 1))]
                 (when result
                   (swap! cost (fn [{:keys [total current
                                            times max_total max_concurrent]} t c]
                                 {:total (+ total t)
                                  :current (+ current c)
                                  :max_total (if (< max_total t)
                                               t
                                               max_total)
                                  :max_concurrent (if (< max_concurrent c)
                                                    c
                                                    max_concurrent)
                                  :times (inc times)})
                          total
                          current))
                 (remove-permit r (mod m 1) ts)))
             (.countDown cl))
           (Thread.)
           (.start)))
        (.await cl)
        (println @cost))))))
