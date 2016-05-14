(ns untangled.client-db.core-spec
  (:require [untangled-spec.core #?(:clj :refer :cljs :refer-macros)
             [specification component behavior assertions provided when-mocking]]
            [untangled.client-db.core :as src])
  #?(:clj (:import (clojure.lang ExceptionInfo))))

(def schema
  {:todo/items [:todo-items/by-id :ref/many]
   :current/todo [:todo-items/by-id :ref/one]})

(def dflt-state
  {})

(defn make-env
  ([] (make-env dflt-state))
  ([st] {:state  (atom st)
         :schema (src/make-schema schema)
         :index  (src/make-index st)}))

(def assertion-error #?(:clj AssertionError :cljs js/Error))

(specification "initialization"
  (assertions
    "make-schema"
    ((juxt :attrs :tables) @(src/make-schema schema))
    => [schema {:todo-items/by-id #{:todo/items :current/todo}}]
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
               300 [[:todo-items/by-id 200]]}}))
      (let [env (make-env)]
        (assertions
          (src/unravel env :todo-items/by-id
                       {:db/id 111
                        :current/todo {:db/id 222
                                       :todo/text "asdf"}})
          => {:todo-items/by-id
              {222 {:db/id 222 :todo/text "asdf"}
               111 {:db/id 111 :current/todo [:todo-items/by-id 222]}}}
          @(:index env)
          => {:todo-items/by-id {222 [[:todo-items/by-id 111]]}})))

    (let [env (make-env)]
      (assertions "throws an error if the ident-key is not in the schema"
        (src/unravel env :invalid/ident-key nil)
        =throws=> (assertion-error #"is not a valid ident-key"))
      (behavior "attributes pointing to a vector or a map"
        (assertions
          "should be the correct :ref type"
          (src/unravel env :todo-items/by-id
                       {:db/id 1 :todo/items {}})
          =throws=> (assertion-error #"is not valid")
          (src/unravel env :todo-items/by-id
                       {:db/id 1 :current/todo []})
          =throws=> (assertion-error #"is not valid")))))

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
