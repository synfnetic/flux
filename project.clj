(defproject navis/untangled-mutations "0.1.0-SNAPSHOT"
  :description "A cljc (server and client) validation library"
  :url ""
  :license {:name "MIT Public License"
            :url  ""}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.8.51"]
                 [org.omcljs/om "1.0.0-alpha32" :scope "provided"]
                 [navis/untangled-spec "0.3.6" :scope "test"]
                 [lein-doo "0.1.6" :scope "test"]]

  :plugins [[com.jakemccrary/lein-test-refresh "0.14.0"]
            [lein-doo "0.1.6"]]

  :test-refresh {:report untangled-spec.reporters.terminal/untangled-report
                 :with-repl true
                 :changes-only true}

  :doo {:build "automated-tests"
        :paths {:karma "node_modules/karma/bin/karma"}}

  :source-paths ["src"]
  :test-paths ["specs"]
  :clean-targets ^{:protect false} ["resources/public/js/compiled" "resources/public/js/specs"
                                    "resources/private/js" "target"]

  :cljsbuild {:builds [{:id           "test"
                        :source-paths ["specs" "src"]
                        :figwheel     true
                        :compiler     {:main                 untangled.mutations.spec-main
                                       :output-to            "resources/public/js/specs/specs.js"
                                       :output-dir           "resources/public/js/compiled/specs"
                                       :asset-path           "js/compiled/specs"
                                       :optimizations        :none}}
                       {:id           "automated-tests"
                        :source-paths ["specs" "src"]
                        :compiler     {:output-to     "resources/private/js/unit-tests.js"
                                       :main          untangled.all-tests
                                       :output-dir    "resources/private/js/out"
                                       :asset-path    "js/out"
                                       :optimizations :none}}]}

  :profiles {:dev {:source-paths ["dev" "src"]
                   :dependencies [[binaryage/devtools "0.6.1"]
                                  [com.cemerick/piggieback "0.2.1"]
                                  [figwheel-sidecar "0.5.3-1" :exclusions [joda-time clj-time]]
                                  [org.clojure/tools.nrepl "0.2.12"]]
                   :repl-options {:init-ns user
                                  :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}})
