(ns untangled.mutations.datomic-spec
  (:require [untangled-spec.core :refer
             [specification component behavior assertions provided when-mocking]]
            [untangled.mutations.datomic :as impl]
            [untangled.datomic.core :as udc]
            [untangled.datomic.protocols :as udb]
            [untangled.datomic.test-helpers :as test-helpers]
            [datomic.api :as d]
            [om.tempid :as omt]))

(def test-seed-data
  [{:db/id :datomic.id/todo1
    :todo/text "todo1"
    :todo/items [:datomic.id/todo2 :datomic.id/todo3]}
   {:db/id :datomic.id/todo2
    :todo/text "todo2"}
   {:db/id :datomic.id/todo3
    :todo/text "todo3"
    :todo/items [:datomic.id/todo4]}
   {:db/id :datomic.id/todo4
    :todo/text "todo4"}])

(def seed-fn #(test-helpers/link-and-load-seed-data % test-seed-data))

(defmacro with-env [setup env & body]
  `(test-helpers/with-db-fixture db#
     (let [~env (~setup {:dbc db# :conn (udb/get-connection db#)})]
       ~@body)
     :migrations "untangled.mutations.migrations"
     :seed-fn ~seed-fn))

(defn fixture-setup [{:keys [dbc conn] :as env}]
  (-> env
    (assoc :new-tid #(omt/tempid (d/squuid)))
    (into (map #(update % 0 (comp keyword name)))
          (:seed-result dbc))))

(specification "set-tx [dbc params]"
  (behavior "edits (:db/id params) with each other k-v pair"
    (with-env fixture-setup
      {:as env :keys [dbc conn new-tid todo3 todo4]}
      (let [new-todo (new-tid)]
        (assertions
          (impl/set-tx dbc {:db/id todo3
                            :todo/text "new todo3 text"
                            :todo/items {:db/id new-todo
                                         :todo/text "new todo text"}})
          => [[:db.fn/cas todo3 :todo/text "todo3" "new todo3 text"]
              {:db/id new-todo :todo/text "new todo text"}
              [:refcas todo3 :todo/items [todo4] [new-todo]]])))))

(specification "add-tx [dbc params]"
  (behavior "same as set-tx except it will add to old values if its a ref many"
    (with-env fixture-setup
      {:as env :keys [dbc conn new-tid todo3 todo4]}
      (let [new-todo (new-tid)]
        (assertions
          (impl/add-tx dbc {:db/id todo3
                            :todo/items {:db/id new-todo
                                         :todo/text "add-tx works"}})
          => [{:db/id new-todo :todo/text "add-tx works"}
              [:refcas todo3 :todo/items
               [todo4] [new-todo todo4]]])))))

(specification "create-tx [dbc params]"
  (behavior "creates params as a new entity"
    (with-env fixture-setup
      {:as env :keys [dbc conn new-tid]}
      (let [new-uuid (omt/tempid (d/squuid))]
        (assertions
          (impl/create-tx dbc {:db/id new-uuid
                               :todo/text "CREATE!"})
          => [{:db/id new-uuid
               :todo/text "CREATE!"}])))))

(specification "delete-entity-tx [dbc params]"
  (behavior "retracts (:db/id params)"
    (with-env fixture-setup
      {:as env :keys [dbc conn todo1]}
      (assertions
        (impl/delete-entity-tx dbc {:db/id todo1})
        => [[:db.fn/retractEntity todo1]]))))

(specification "replace-tx [dbc params]"
  (behavior "makes (:db/id params) have only the attr-values in params"
    (with-env fixture-setup
      {:as env :keys [dbc conn todo1 todo2 todo3]}
      (assertions
        (impl/replace-tx dbc {:db/id todo1
                              :todo/text "only ME"})
        => [[:db/retract todo1 :todo/items todo3]
            [:db/retract todo1 :todo/items todo2]
            [:db.fn/cas todo1 :todo/text "todo1" "only ME"]]))))

(specification "transact! [dbc params]"
  (with-env fixture-setup
    {:as env :keys [dbc conn todo2 todo4]}
    (let [new-todo-uuid (omt/tempid (d/squuid))
          act-tx (atom :fail)]
      (when-mocking
        (d/tempid _) => :new-todo-uuid
        (d/transact _ tx) => (do (reset! act-tx tx)
                                 (future {:tempids []}))
        (assertions
          (do (impl/transact! dbc
                (impl/set-tx dbc {:db/id todo2
                                  :todo/items {:db/id new-todo-uuid
                                               :todo/text "new todo item"}})
                (impl/set-tx dbc {:db/id todo4
                                  :todo/text "new todo4 text"}))
              @act-tx)
          => [{:db/id :new-todo-uuid :todo/text "new todo item"}
              [:refcas todo2 :todo/items [] [:new-todo-uuid]]
              [:db.fn/cas todo4 :todo/text "todo4" "new todo4 text"]])))))
