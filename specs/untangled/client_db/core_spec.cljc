(ns untangled.client-db.core-spec
  (:require [untangled-spec.core #?(:clj :refer :cljs :refer-macros)
             [specification component behavior assertions provided when-mocking]]
            [untangled.client-db.core :as src])
  #?(:clj (:import (clojure.lang ExceptionInfo))))

(specification "client db"
  (assertions
    (src/fixme) => "Hello World!"
    )
  )
