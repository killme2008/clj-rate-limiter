(defproject clj-rate-limiter "0.1.0-SNAPSHOT"
  :description "Rate limiter for clojure that supports a rolling window, either in-memory or backed by redis"
  :url ""
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [com.taoensso/carmine "2.4.4"]])
