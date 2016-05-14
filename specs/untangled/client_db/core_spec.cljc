(ns untangled.client-db.core-spec
  (:require [untangled-spec.core #?(:clj :refer :cljs :refer-macros)
             [specification component behavior assertions provided when-mocking]]
            [untangled.client-db.core :as src])
  #?(:clj (:import (clojure.lang ExceptionInfo))))

(def schema
  {:todo/items [:todo-items/by-id :ref/many]})

(def dflt-state
  {})

(defn make-env
  ([] (make-env dflt-state))
  ([st] {:state  (atom st)
         :schema (src/make-schema schema)
         :index  (src/make-index st)}))

(specification "initialization"
  (assertions
    "make-schema"
    @(src/make-schema schema)
    => {:attrs  {:todo/items [:todo-items/by-id :ref/many] }
        :tables {:todo-items/by-id #{:todo/items}}}
    "make-index"
    @(src/make-index dflt-state)
    => {}))

(specification "client db"
  (component "unravel"
    (behavior "unravels nested data into a normalizable format"
      (let [env (make-env)]
        (assertions
          (src/unravel env :todo-items/by-id
                       {:db/id 100
                        :todo/items [{:db/id 200
                                      :todo/text "do it!"
                                      :todo/items [{:db/id 300
                                                    :todo/text "now!"}]}]})
          => {:todo-items/by-id
              {100 {:db/id 100
                    :todo/items [[:todo-items/by-id 200]]}
               200 {:db/id 200
                    :todo/text "do it!"
                    :todo/items [[:todo-items/by-id 300]]}
               300 {:db/id 300
                    :todo/text "now!"}}}
          "& the index is updated on who points to it"
          @(:index env)
          => {:todo-items/by-id
              {200 [[:todo-items/by-id 100]]
               300 [[:todo-items/by-id 200]]}})))

    (let [env (make-env)]
      (assertions "throws an error if the ident-key is not in the schema"
        (src/unravel env :invalid/ident-key nil)
        =throws=> (AssertionError #"is not a valid ident-key")
        )))

  (component "intertwine"
    (assertions
      (src/intertwine
        (make-env {:todo-items/by-id
                   {100 {:db/id 100
                         :todo/text "OLD"
                         :todo/items
                         [[:todo-items/by-id 666]]}}})
        {:todo-items/by-id
         {100 {:db/id 100
               :todo/text "NEW"
               :todo/items [[:todo-items/by-id 200]]}}})
      => {:todo-items/by-id
          {100 {:db/id 100
                :todo/text "NEW"
                :todo/items [[:todo-items/by-id 666]
                             [:todo-items/by-id 200]]}}})))
