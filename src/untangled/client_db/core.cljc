(ns untangled.client-db.core)

(defn pair? [x] (-> x meta ::pair))
(defn pair [x] (with-meta x {::pair true}))

(defn my-walk
  [inner outer form]
  (cond
    (map? form) (outer (into (empty form) (map (comp inner pair) (for [[k v] form] [k v]))))
    (list? form) (outer (apply list (map inner form)))
    (seq? form) (outer (doall (map inner form)))
    (coll? form) (outer (into (empty form) (map inner form)))
    :else (outer form)))

(defn make-schema [schema]
  (atom schema))

(defn make-index [state]
  (atom {}))

(defn unravel [{:keys [state index schema]} table data]
  ;;TODO table should be a valid 'entry' in the schema
  (let [ret (atom {})
        path (atom [])]
    (letfn [(lookup-in-schema [ident-key]
              (first (or (get @schema ident-key)
                         (assert false "BAD"))))
            (inner [data]
              (when (pair? data)
                ;;TODO validate [key val] in data w/ schema
                (swap! path conj (first data)))
              data)
            (outer [data]
              (let [new-data (cond-> data (map? data)
                               (#(let [ident-key (if (empty? @path) table
                                                   (lookup-in-schema (peek @path)))
                                       ident [ident-key (:db/id %)]]
                                   (swap! ret assoc-in ident %)
                                   ;;TODO if seq @path update index
                                   ident)))]
                (when (pair? data) (swap! path pop))
                new-data))
            (walk [data]
              (my-walk walk outer (inner data)))]
      (walk data)
      @ret)))

(defn smart-merge [& xs]
  ;;TODO how efficient/fast is this?
  (cond
    (every? map? xs)
    (apply merge-with smart-merge xs)

    (every? vector? xs)
    (apply concat xs)

    :else (last xs)))

(defn intertwine [{:keys [state]} data]
  (swap! state smart-merge data))
