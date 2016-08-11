(ns flux.all-tests
  (:require flux.tests-to-run [doo.runner :refer-macros [doo-all-tests]]))

(doo-all-tests #".*-spec")
