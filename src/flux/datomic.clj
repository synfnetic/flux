(ns flux.datomic
  (:require [clojure.walk :as walk]
            [datomic.api :as d]
            [om.tempid :as omt])
  (:import datomic.query.EntityMap))

(defn datomic-tempid? [x] (= (type x) datomic.db.DbId))

(defn resolve-ids [db ctid->stid stid->rid]
  (reduce (fn [acc [ctid stid]]
            (assoc acc ctid (d/resolve-tempid db stid->rid stid)))
    {} ctid->stid))

(defn transact!
  "Given a db connection and a vector of data formatted for a datomic transaction, replaces client temp-ids
  with datomic temp-ids, and returns what d/transact returns except with :tempids as a remapping of client temp-ids
  to the real datomic ids created after the transaction is run.
  CANNOT BE RUN on a transaction that includes datomic entities, only plain data!"
  ([database & txs]
   (let [conn (:connection database)
         om-tempids->datomic-tempids (atom {})
         transaction (walk/prewalk
                       (fn [id]
                         (when (instance? EntityMap id)
                           (throw (ex-info "Entities cannot appear in upserts!" {:offender id})))
                         (if (or (omt/tempid? id) (datomic-tempid? id))
                           (let [tid (or (get @om-tempids->datomic-tempids id nil) (d/tempid :db.part/user))]
                             (swap! om-tempids->datomic-tempids assoc id tid)
                             tid)
                           id))
                       (apply concat txs))
         resolved-ids->real-id (:tempids @(d/transact conn transaction))
         remaps (resolve-ids (d/db conn) @om-tempids->datomic-tempids resolved-ids->real-id)]
     {:tempids remaps})))

(defn cardinality-of [{:keys [schema]} k]
  (as-> (first (get schema k)) <>
    (when (= (:db/valueType <>) :db.type/ref)
      (some-> <> :db/cardinality name))))

(defn find-prev-value [find db id k]
  (d/q (merge
         {:find find}
         '{:in [$ ?id ?k]
           :where [[?id ?k ?v]]})
       db id k))

(defn scalar-cas [{:keys [db id]} k v]
  (let [prev (find-prev-value '[?v .] db id k)]
    [[:db.fn/cas id k prev v]]))

(defn ref-one-cas [{:keys [db id]} k v]
  (let [prev-id (find-prev-value '[?v .] db id k)]
    (cond
      (map? v);; set or add - v to k
      (let [new (:db/id v)
            refcas [:refcas id k prev-id new]]
        [v refcas])

      ;; enums
      (keyword? v)
      [[:db.fn/cas id k prev-id v]]

      :else
      (throw (ex-info "Inconceivable?" {:k k :v v})))))

(defn ref-many-cas [{:keys [db id tx-type]} k v]
  (let [prev-ids (find-prev-value '[[?v ...]] db id k)
        new-ids (fn [new]
                  (cond-> new
                    (= :action.type/add tx-type)
                    (concat prev-ids)))]
    (cond
      (map? v)
      [v [:refcas id k prev-ids (new-ids [(:db/id v)])]]

      (sequential? v)
      (conj (vec v)
            [:refcas id k prev-ids (new-ids (mapv :db/id v))])

      (set? v)
      [[:refcas id k prev-ids (new-ids v)]]

      :else
      (throw (ex-info "Inconceivable?" {:k k :v v})))))

(defn gen-tx-builder [ctx]
  (fn [tx [k v]]
    (let [build-tx (if-let [cardinality (cardinality-of ctx k)]
                     (case cardinality
                       "one" ref-one-cas
                       "many" ref-many-cas)
                     scalar-cas)]
      (concat tx
        (build-tx ctx k v)))))

(defn params->tx
  [database {:as params :keys [db/id]} tx-type]
  (let [db (d/db (:connection database))
        ctx {:db db :id id
             :schema (group-by :db/ident (:schema database))
             :tx-type tx-type}]
    (reduce (gen-tx-builder ctx)
            [] (dissoc params :db/id))))

(defn set-tx
  "Creates a transaction best suited for transact! but can/should work with d/transact
   by unraveling the params by key value pairs, where the keys must be in the schema.
   It must also have a top level :db/id that is the entity we will be modifying.
   Ex: (set-tx env {:db/id 123 :localized-string/value \"hello world\"})
   => [[:db.fn/cas 123 \"old string\" \"hello world\"]]"
  [env params]
  (params->tx env params :action.type/set))
(defn add-tx
  "Creates a transaction best suited for transact! but can/should work with d/transact
   by unraveling the params by key value pairs, where the keys must be in the schema.
   It must also have a top level :db/id that is the entity we will be modifying.
   Unlike set-tx, add-tx will conj/concat values if it encounters an attribute with cardinality many
   Ex: (add-tx env {:db/id 123 :artifact/display-title [{:db/id 345 :localized-string/value \"hello world\"}]})
   => [{:db/id 345 :localized-string/value \"hello world\"}, [:refcas 123 [111] [111 345]]"
  [env params]
  (params->tx env params :action.type/add))

(defn delete-entity-tx
  "Builds a transaction that :db.fn/retractEntity on :db/id of the params,
   see datomic's docs for more info..."
  [_ params]
  [[:db.fn/retractEntity (:db/id params)]])

(defn create-tx
  "Creates a transaction from params, where the :db/id must not be an existing entity id."
  [_ params]
  {:pre [(not (number? (:db/id params)))]}
  [params])

(letfn [(retract-tx
          ;"Only build a retract statement for existing values."
          [{:keys [db/id] :as old-data} k]
          (if-let [v (get old-data k)]
            (if (or (set? v) (sequential? v))
              (for [old-v v]
                [:db/retract id k (cond->> old-v (:db/id old-v) :db/id
                                    (or (set? old-v) (sequential? old-v)) (mapv :db/id))])
              [[:db/retract id k (cond-> v (:db/id v) :db/id)]])
            []))]
  (defn replace-tx
    "Builds a transaction that 'replaces' all the values an entity had with the ones supplied in params.
     In other words, retracts everything it had, and installs params as that new entity.
     Has the same semantics as set-tx for now.
     Eg: (replace-tx env {:db/id 111 :foo/bar :foo.bar/new})
     => [[:db/retract 111 :fizz/buzz 222], [:db/cas 111 :foo/bar :foo.bar/old :foo.bar/new]]"
    [database {:keys [db/id] :as params}]
    (let [conn (:connection database)
          db (d/db conn)
          old-data (d/touch (d/entity db id))
          new-data (dissoc params :db/id)]
      (->> (clojure.data/diff (set (keys old-data)) (set (keys new-data)))
        (zipmap [:old :new :both])
        (reduce
          (fn [tx [tx-type diffs]]
            (reduce (fn [tx k]
                      (case tx-type
                        :old (concat tx (retract-tx old-data k))
                        ;; HACKY/SMELLY
                        ((gen-tx-builder {:db db :id id :tx-type :action.type/set
                                          :schema (group-by :db/ident (:schema database))})
                         tx [k (new-data k)])))
                    tx diffs))
          [])))))
