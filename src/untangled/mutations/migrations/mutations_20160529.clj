(ns untangled.mutations.migrations.mutations-20160529
  (:require [datomic.api :as d]
            [untangled.datomic.schema :as s]))

(defn transactions []
  [(s/generate-schema (s/dbfns->datomic s/refcas))
   (s/generate-schema
     [(s/schema todo
        (s/fields
          [text :string]
          [items :ref :many]
          ))
      ])])
