(defproject clj-rate-limiter "0.1.6-RC1"
  :description "Rate limiter for clojure that supports a rolling window, either in-memory or backed by redis"
  :url "https://github.com/killme2008/clj-rate-limiter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/core.cache "0.7.2"]
                 [com.taoensso/carmine "2.19.1"]
                 [eftest "0.5.7"]]
  :plugins [[lein-eftest "0.5.7"]]
  :profiles {:kaocha {:dependencies [[lambdaisland/kaocha "0.0-409"]]}}
  :aliases {"kaocha" ["with-profile" "+kaocha" "run" "-m" "kaocha.runner"]}
  )
