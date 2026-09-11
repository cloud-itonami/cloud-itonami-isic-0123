(ns citrusops.sim
  "Simple simulation/demo runner for the Citrus Orchard Operations
  Coordinator actor. Used to validate that the actor flow compiles and
  basic proposal flow works. Mirrors `orchardops.sim`
  (cloud-itonami-isic-0122)."
  (:require [citrusops.operation :as operation]
            [citrusops.store :as store]))

(defn demo
  "Run a simple demo scenario: register an orchard block, propose an
  orchard-record log, and check the disposition flow."
  []
  (let [;; Create store with a registered orchard block
        st (store/mem-store
            {:initial-orchards
             {"orchard-001"
              {:id "orchard-001"
               :name "Test Orchard Block"
               :fruit-class "orange"}}})

        ;; Build actor
        actor (operation/build st)

        ;; Create a request to log an orchard record
        request {:op :log-orchard-record
                 :orchard-id "orchard-001"
                 :record-type "harvest"
                 :count 500
                 :notes "healthy yield"}

        ;; Context with phase 0 (simulation)
        context {:actor-id "citrus-ops-01"
                 :role :orchard-operator
                 :phase :phase-0}]

    (println "=== Citrus Orchard Operations Coordinator Demo ===")
    (println "Demo orchard block: orchard-001")
    (println "Request: log-orchard-record")
    (println "Phase: phase-0 (simulation)")
    (println "Expected: escalate (phase-0 forces human review of all commits)")
    (println)
    (let [result (actor request context)]
      (println "Result disposition:" (:disposition result))
      result)))

(defn -main
  "clojure -M:run entrypoint."
  [& _args]
  (demo))

(comment
  ;; In a real REPL:
  (demo)
)
