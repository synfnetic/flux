(ns untangled.client-db.core-spec
  (:require [untangled-spec.core #?(:clj :refer :cljs :refer-macros)
             [specification component behavior assertions provided when-mocking]]
            [untangled.client-db.core :as src]))

(def schema
  {:todo/items [:todo-items/by-id :ref/many]})

(def dflt-state
  {})

(defn make-env
  ([] (make-env dflt-state))
  ([st] {:state  (atom st)
         :schema (src/make-schema schema)
         :index  (src/make-index st)}))

(specification "client db"
  (component "unravel"
    (assertions
      (src/unravel (make-env) :todo-items/by-id
                   {:db/id 100
                    :todo/items [{:db/id 200
                                  :todo/text "do it!"
                                  :todo/items [{:db/id 300
                                                :todo/text "now!"}]}]})
      => {:todo-items/by-id {100 {:db/id 100
                                  :todo/items [[:todo-items/by-id 200]]}
                             200 {:db/id 200
                                  :todo/text "do it!"
                                  :todo/items [[:todo-items/by-id 300]]}
                             300 {:db/id 300
                                  :todo/text "now!"}}}))
  (component "intertwine"
    (assertions
      (src/intertwine
        (make-env {:todo-items/by-id
                   {100 {:db/id 100
                         :todo/items
                         [[:todo-items/by-id 666]]}}})
        {:todo-items/by-id
         {100 {:db/id 100
               :todo/items [[:todo-items/by-id 200]]}
          200 {:db/id 200
               :todo/text "do it!"
               :todo/items [[:todo-items/by-id 300]]}
          300 {:db/id 300
               :todo/text "now!"}}})
      => {:todo-items/by-id
          {100 {:db/id 100
                :todo/items [[:todo-items/by-id 666]
                             [:todo-items/by-id 200]]}
           200 {:db/id 200
                :todo/text "do it!"
                :todo/items [[:todo-items/by-id 300]]}
           300 {:db/id 300
                :todo/text "now!"}}})))
