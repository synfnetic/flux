(ns user
  (:require
    [figwheel-sidecar.repl-api :as ra]

    ;; repl utils
    [clojure.pprint :refer [pprint]]
    [clojure.stacktrace :refer [print-stack-trace]]
    [clojure.java.io :as io]))

;; ==================== FIGWHEEL ====================

(def figwheel-config
  {:figwheel-options {:server-port 3057
                      :css-dirs ["resources/public/css"]}
   :build-ids        ["test"]
   :all-builds       (figwheel-sidecar.repl/get-project-cljs-builds)})

(defn start-figwheel
  "Start Figwheel on the given builds, or defaults to build-ids in `figwheel-config`."
  ([]
   (let [props (System/getProperties)
         all-builds (->> figwheel-config :all-builds (mapv :id))]
     (start-figwheel (keys (select-keys props all-builds)))))
  ([build-ids]
   (let [default-build-ids (:build-ids figwheel-config)
         build-ids (if (empty? build-ids) default-build-ids build-ids)]
     (println "STARTING FIGWHEEL ON BUILDS: " build-ids)
     (ra/start-figwheel! (assoc figwheel-config :build-ids build-ids))
     (ra/cljs-repl))))
