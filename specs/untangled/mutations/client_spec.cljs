(ns untangled.mutations.client-spec
  (:require [untangled-spec.core :refer-macros
             [specification component behavior assertions provided when-mocking]]
            [untangled.mutations.client :as src]
            [om.tempid :as omt]))

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

(def assertion-error js/Error)

(specification "initialization"
  (assertions
    "make-schema"
    ((juxt :attrs :tables) @(src/make-schema schema))
    => [schema {:todo-items/by-id #{:todo/items :current/todo}}]
    "make-index"
    @(src/make-index dflt-state)
    => {}))

(specification "helpers"
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
                             [:todo-items/by-id 200]]}}}
      "it can also replace ref manys"
      (src/intertwine
        (make-env {:todo-items/by-id
                   {100 {:db/id 100
                         :todo/text "OLD"
                         :todo/items
                         [[:todo-items/by-id 666]]}}})
        {:todo-items/by-id
         {100 {:db/id 100
               :todo/text "NEW"
               :todo/items [[:todo-items/by-id 200]]}}}
        :replace? true)
      => {:todo-items/by-id
          {100 {:db/id 100
                :todo/text "NEW"
                :todo/items [[:todo-items/by-id 200]]}}})))

(specification "public API"
  (component "create!"
    (let [env   (make-env)
          omt-1 (omt/tempid)]
      (assertions "can only create entities with om tempids for db/ids"
        (src/create! env :todo-items/by-id
          {:db/id     100
           :todo/text "create 100"})
        =throws=> (assertion-error #"omt/tempid\?")
        (do (src/create! env :todo-items/by-id
              {:db/id     omt-1
               :todo/text "create 100"})
            @(:state env))
        => {:todo-items/by-id {omt-1 {:db/id omt-1 :todo/text "create 100"}}}
        @(:index env) => {})))
  (component "set!"
    (let [env (make-env {:todo-items/by-id
                         {100 {:db/id      100
                               :todo/items [[:todo-items/by-id 10]]}} })]
      (assertions
        (do (src/set! env :todo-items/by-id
              {:db/id      100
               :todo/items [{:db/id     200
                             :todo/text "set 200"}]})
            @(:state env))
        => {:todo-items/by-id
            {200 {:db/id 200 :todo/text "set 200"}
             100 {:db/id 100 :todo/items [[:todo-items/by-id 200]]}}})))
  ;;TODO: should set! & add! not allow om-tempids?
  ;; otherwise what's the point of create? just cause api?
  (component "add!"
    (let [env (make-env {:todo-items/by-id
                         {100 {:db/id      100
                               :todo/items [[:todo-items/by-id 10]]}} })]
      (assertions
        (do (src/add! env :todo-items/by-id
              {:db/id      100
               :todo/items [{:db/id     200
                             :todo/text "set 200"}]})
            @(:state env))
        => {:todo-items/by-id
            {200 {:db/id 200 :todo/text "set 200"}
             100 {:db/id 100 :todo/items [[:todo-items/by-id 10]
                                          [:todo-items/by-id 200]]}}})))
  (component "delete!"
    (let [env (make-env)
          t-1 (omt/tempid)
          t-2 (omt/tempid)]
      (do (src/create! env :todo-items/by-id
            {:db/id     t-1
             :todo/text "create 100"})
          (src/set! env :todo-items/by-id {:db/id      t-1
                                           :todo/items [{:db/id     t-2
                                                         :todo/text "set 200"}]}))
      (assertions
        "removes the entity."
        (do (src/delete! env :todo-items/by-id {:db/id t-2}) @(:state env))
        => {:todo-items/by-id {t-1 {:db/id     t-1
                                    :todo/text "create 100"}}}
        "clears the index for that entity."
        (get-in @(:index env) [:todo-items/by-id t-2])
        => nil))))
