(ns untangled.client-db.core)

(def concatv (comp vec concat))

(defn make-schema [schema]
  (->> schema
    (group-by (comp first second))
    (mapv (fn [x] (update x 1 #(set (map first %)))))
    (into {})
    (merge schema)
    (atom)))

(defn make-index [state]
  (atom {}))

(defn unravel [{:keys [state index schema]} table data]
  ;;TODO table should be a valid 'entry' in the schema
  (let [unraveled (atom {})
        path (atom [])]
    (letfn [(update-index [{:keys [db/id] :as data}]
              (let [kv-to-index (filter (comp @schema first) data)]
                (swap! index #(reduce
                                (fn [acc [k links]]
                                  (reduce
                                    (fn [acc v]
                                      (update-in acc v conj [(lookup-in-schema k) id]))
                                    acc links))
                                % kv-to-index))))
            (lookup-in-schema [ident-key]
              (first (or (get @schema ident-key)
                         (assert false "BAD"))))
            (step [{:keys [db/id] :as data}]
              (let [ident-key (if (empty? @path) table
                                (lookup-in-schema (peek @path)))
                    ident [ident-key id]]
                (swap! unraveled assoc-in ident data)
                (update-index data)
                ident))
            (outer [{:keys [db/id] :as data}]
              (let [new-data (cond-> data (map? data) (step))]
                (when (pair? data) (swap! path pop))
                new-data))
            (inner [data]
              (when (pair? data)
                ;;TODO validate [key val] in data w/ schema
                ;;eg: should crash if {} or a [] is not in schema
                ;; or its a {} but in schema its a :ref/many
                (swap! path conj (first data)))
              data)
            (pair? [x] (-> x meta ::pair))
            (pair [x] (with-meta x {::pair true}))
            (my-walk
              [inner outer form]
              (cond
                (map? form) (outer (into (empty form) (map (comp inner pair) (for [[k v] form] [k v]))))
                (list? form) (outer (apply list (map inner form)))
                (seq? form) (outer (doall (map inner form)))
                (coll? form) (outer (into (empty form) (map inner form)))
                :else (outer form)))
            (walk [data]
              (my-walk walk outer (inner data)))]
      (walk data)
      @unraveled)))

(defn smart-merge [& xs]
  ;;TODO how efficient/fast is this?
  (cond
    (every? map? xs)
    (apply merge-with smart-merge xs)

    (every? vector? xs)
    (apply concatv xs)

    :else (last xs)))

(defn intertwine [{:keys [state]} data]
  (swap! state smart-merge data))
