(ns untangled.client-db.core
  (:require [om.tempid :as omt]))

(def concatv (comp vec concat))

(defn make-schema [schema]
  (->> schema
    (group-by (comp first second))
    (mapv (fn [x] (update x 1 #(set (map first %)))))
    (into {}) (assoc {} :tables)
    (merge {:attrs schema})
    (atom)))

(defn make-index [state]
  (atom {}))

(defn unravel [{:keys [state index schema]} ident-key data & {:keys [validate!]
                                                              :or {validate! (constantly true)}}]
  (assert (contains? (:tables @schema) ident-key)
    (str ident-key " is not a valid ident-key in " (keys (:tables @schema))))
  (let [unraveled (atom {})
        path (atom [])]
    (letfn [(update-index [{:keys [db/id] :as data}]
              (let [kv-to-index (filter (comp (:attrs @schema) first) data)]
                (swap! index (fn [-index-]
                               (reduce
                                 (fn [acc [k ?links]]
                                   (reduce
                                     (fn [acc v]
                                       (update-in acc v conj [(lookup-in-schema k) id]))
                                     acc (cond-> ?links (not (every? vector? ?links)) (vector))))
                                 -index- kv-to-index)))))
            (lookup-in-schema [ident-key]
              (first (or (get (:attrs @schema) ident-key)
                         (assert false (str "failed to find " ident-key)))))
            (step [{:keys [db/id] :as data}]
              (let [ident-key (if (empty? @path) ident-key
                                (lookup-in-schema (peek @path)))
                    ident [ident-key id]]
                (swap! unraveled assoc-in ident data)
                (update-index data)
                ident))
            (outer [{:keys [db/id] :as data}]
              (let [new-data (cond-> data (map? data) (step))]
                (when (pair? data) (swap! path pop))
                new-data))
            (valid-value? [k v]
              (case (->> k (get (:attrs @schema)) second)
                :ref/one  (map? v)
                :ref/many (vector? v)
                :else     false))
            (inner [data]
              (when-let [[k v] (and (pair? data) data)]
                (validate! k v)
                (when (contains? (:attrs @schema) k)
                  (assert (valid-value? k v)
                    (str [k v] " is not valid according to: " (select-keys (:attrs @schema) [k]))))
                (swap! path conj k))
              data)
            (pair? [x] (-> x meta ::pair))
            (pair [x] (with-meta x {::pair true}))
            (my-walk
              [inner outer form]
              (cond
                (record? form) form
                (map? form) (outer (into (empty form) (map (comp inner pair) (for [[k v] form] [k v]))))
                (list? form) (outer (apply list (map inner form)))
                (seq? form) (outer (doall (map inner form)))
                (coll? form) (outer (into (empty form) (map inner form)))
                :else (outer form)))
            (walk [data]
              (my-walk walk outer (inner data)))]
      (walk data)
      @unraveled)))

(defn intertwine [{:keys [state]} data & {:keys [replace?]}]
  (letfn [(smart-merge [& xs]
            (cond
              (every? map? xs) #_:>> (apply merge-with smart-merge xs)
              (and (every? vector? xs)
                   (not replace?))
              #_:>> (apply concatv xs)
              :else (last xs)))]
    (swap! state smart-merge data)))

(defn create! [{:keys [state index schema] :as env} index-key data]
  (intertwine env (unravel env index-key data
                           :validate! #(when (= %1 :db/id)
                                         (assert (omt/tempid? %2))))))

(defn set! [{:keys [state index schema] :as env} index-key data]
  (intertwine env (unravel env index-key data)
              :replace? true))

(defn add! [{:keys [state index schema] :as env} index-key data]
  (intertwine env (unravel env index-key data)))

(defn delete! [{:keys [state index schema] :as env} index-key data]
  (assert false "NOT IMPL"))
