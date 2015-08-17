# clj-rate-limiter

Rate limiter in clojure supports a rolling window,either in-memory or backed by redis.

It's transformed from [rolling-rate-limiter](https://github.com/classdojo/rolling-rate-limiter) in node.js, but does some performance tuning for redis-based implementation and adds a mechanism to prevent request flooding.

## Usage

Leiningen:

```clj
[clj-rate-limiter "0.1.0"]
```

### Basic

Create an in-memory rate limiter with max 100 requsts in 1 seconds:

```clj
(require '[clj-rate-limiter.core :as r])
(def limiter (r/create
	           (r/rate-limiter-factory :memory
	                                   :interval 1000 	                                   :max-in-interval 100)))
	                                   
(println (allow? limiter "key1"))	 
(println (allow? limiter "key2"))	                                  
```

The `:interval` sets the time window unit in millseconds, and `:max-in-interval` sets the maximum requests in a time window, and `:memory` sets the rate limiter store in memory.
After the limiter was created, you can use `(allow? limiter key)` to test if the request can be passed.We use the string key to present the type of the request.

In a cluster, you may want to created a redis-backed limiter:

```clj
;;redis spec and pool as decribed in 
;;https://github.com/ptaoussanis/carmine
(def redis {:spec {:host "localhost" :port 6379 :timeout 5000}
            :pool {:max-active (* 3 (.availableProcessors (Runtime/getRuntime)))
                   :min-idle (.availableProcessors (Runtime/getRuntime))
                   :max-wait 5000}})
                   
(def limiter (r/create
	           (r/rate-limiter-factory :redis
	           							:redis redis
	           							:namespace "APIs"
	                                   :interval 1000 	                                   :max-in-interval 100)))
```

You have to provide a redis spec and pool, and you can set the namespace prefix of the request keys.


### min-difference

You can set the minimum time in millseconds between requests by ":min-difference" option,default is zero.

```clj
(def limiter (r/create
	           (r/rate-limiter-factory :redis
	           							:redis redis
	           							:namespace "APIs"
	                                   :interval 1000
	                                   :min-difference 1 
	                                   :max-in-interval 100)))
```

## Protection for flooding requests

The rate limiter will still adds the request to backend storage even if the requests are too many. It may consume too much memory,so i provide a option `:flood-threshold` for such situation.

If you set `:flood-threshold` value(a long value),when crreunt requests number in storage is greater or equalt to `(* flood-threshold max-in-interval)`, then the limiter will not adds the new request to backend storage until next time window.

```clj
(def limiter (r/create
	           (r/rate-limiter-factory :redis
	           							:redis redis
	           							:namespace "APIs"
	                                   :interval 1000
	                                   ::flood-threshold 5
	                                   :max-in-interval 100)))
```


## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
