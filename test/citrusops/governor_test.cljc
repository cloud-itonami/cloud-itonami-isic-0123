(ns citrusops.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [citrusops.governor :as gov]
            [citrusops.store :as store]))

(deftest hard-violations-no-orchard-id
  (testing "Hard violation: missing orchard-id"
    (let [req {}
          prop {:op :log-orchard-record :effect :propose}
          s (store/mem-store)
          verdict (gov/check req nil prop s)]
      (is (:hard? verdict))
      (is (seq (:violations verdict)))
      (is (some #(= :orchard-not-registered (:rule %)) (:violations verdict))))))

(deftest hard-violations-unregistered-orchard
  (testing "Hard violation: orchard-id present but not registered"
    (let [req {:orchard-id "orchard-001"}
          prop {:op :log-orchard-record :effect :propose}
          s (store/mem-store)
          verdict (gov/check req nil prop s)]
      (is (:hard? verdict))
      (is (some #(= :orchard-not-registered (:rule %)) (:violations verdict))))))

(deftest hard-violations-effect-not-propose
  (testing "Hard violation: effect is not :propose"
    (let [orchard {:id "orchard-001" :name "Test Orchard Block"}
          s (store/mem-store {:initial-orchards {"orchard-001" orchard}})
          req {:orchard-id "orchard-001"}
          prop {:op :log-orchard-record :effect :execute}
          verdict (gov/check req nil prop s)]
      (is (:hard? verdict))
      (is (some #(= :no-execution (:rule %)) (:violations verdict))))))

(deftest hard-violations-field-equipment-blocked
  (testing "Hard violation: direct field-equipment operation is permanently blocked"
    (let [orchard {:id "orchard-001" :name "Test Orchard Block"}
          s (store/mem-store {:initial-orchards {"orchard-001" orchard}})
          req {:orchard-id "orchard-001"}
          prop {:op :operate-field-equipment :effect :propose}
          verdict (gov/check req nil prop s)]
      (is (:hard? verdict))
      (is (some #(= :field-equipment-or-spray-blocked (:rule %)) (:violations verdict))))))

(deftest hard-violations-spray-application-blocked
  (testing "Hard violation: finalizing a spray-application decision is permanently blocked"
    (let [orchard {:id "orchard-001" :name "Test Orchard Block"}
          s (store/mem-store {:initial-orchards {"orchard-001" orchard}})
          req {:orchard-id "orchard-001"}
          prop {:op :finalize-spray-application :effect :propose}
          verdict (gov/check req nil prop s)]
      (is (:hard? verdict))
      (is (some #(= :field-equipment-or-spray-blocked (:rule %)) (:violations verdict))))))

(deftest hard-violations-op-not-allowed
  (testing "Hard violation: op outside the closed allowlist"
    (let [orchard {:id "orchard-001" :name "Test Orchard Block"}
          s (store/mem-store {:initial-orchards {"orchard-001" orchard}})
          req {:orchard-id "orchard-001"}
          prop {:op :dispatch-robot-arm :effect :propose}
          verdict (gov/check req nil prop s)]
      (is (:hard? verdict))
      (is (some #(= :op-not-allowed (:rule %)) (:violations verdict))))))

(deftest hard-violations-orchard-count-invalid
  (testing "Hard violation: non-positive logged orchard-record quantity"
    (let [orchard {:id "orchard-001" :name "Test Orchard Block"}
          s (store/mem-store {:initial-orchards {"orchard-001" orchard}})
          req {:orchard-id "orchard-001"}
          prop {:op :log-orchard-record :effect :propose :count 0 :confidence 0.9}
          verdict (gov/check req nil prop s)]
      (is (:hard? verdict))
      (is (some #(= :orchard-count-invalid (:rule %)) (:violations verdict))))))

(deftest ok-orchard-record-logging
  (testing "OK: valid orchard-record logging with a registered orchard block"
    (let [orchard {:id "orchard-001" :name "Test Orchard Block"}
          s (store/mem-store {:initial-orchards {"orchard-001" orchard}})
          req {:orchard-id "orchard-001"}
          prop {:op :log-orchard-record :effect :propose :count 500 :confidence 0.9}
          verdict (gov/check req nil prop s)]
      (is (:ok? verdict))
      (is (not (:hard? verdict)))
      (is (not (:escalate? verdict))))))

(deftest escalation-crop-health-concern
  (testing "Escalation: crop health concern ALWAYS escalates, even at high confidence"
    (let [orchard {:id "orchard-001" :name "Test Orchard Block"}
          s (store/mem-store {:initial-orchards {"orchard-001" orchard}})
          req {:orchard-id "orchard-001"}
          prop {:op :flag-crop-health-concern :effect :propose
                :concern "カンキツグリーニング病（HLB）の疑い" :confidence 0.95}
          verdict (gov/check req nil prop s)]
      (is (not (:hard? verdict)))
      (is (:escalate? verdict))
      (is (:high-stakes? verdict)))))

(deftest escalation-low-confidence
  (testing "Escalation: confidence below the floor"
    (let [orchard {:id "orchard-001" :name "Test Orchard Block"}
          s (store/mem-store {:initial-orchards {"orchard-001" orchard}})
          req {:orchard-id "orchard-001"}
          prop {:op :log-orchard-record :effect :propose :count 500 :confidence 0.5}
          verdict (gov/check req nil prop s)]
      (is (not (:hard? verdict)))
      (is (:escalate? verdict)))))

(deftest escalation-supply-order-high-cost
  (testing "Escalation: supply order over the (default) cost threshold"
    (let [orchard {:id "orchard-001" :name "Test Orchard Block"}
          s (store/mem-store {:initial-orchards {"orchard-001" orchard}})
          req {:orchard-id "orchard-001"}
          prop {:op :order-supplies :effect :propose :cost 1000 :confidence 0.9}
          verdict (gov/check req nil prop s)]
      (is (not (:hard? verdict)))
      (is (:escalate? verdict)))))

(deftest escalation-supply-order-category-specific-threshold
  (testing "Escalation: supply order over its category-specific threshold (equipment: 1000)"
    (let [orchard {:id "orchard-001" :name "Test Orchard Block"}
          s (store/mem-store {:initial-orchards {"orchard-001" orchard}})
          req {:orchard-id "orchard-001"}
          prop {:op :order-supplies :effect :propose :cost 1200 :confidence 0.9
                :value {:category "equipment"}}
          verdict (gov/check req nil prop s)]
      (is (:escalate? verdict))))

  (testing "OK: equipment order under its higher category threshold"
    (let [orchard {:id "orchard-001" :name "Test Orchard Block"}
          s (store/mem-store {:initial-orchards {"orchard-001" orchard}})
          req {:orchard-id "orchard-001"}
          prop {:op :order-supplies :effect :propose :cost 800 :confidence 0.9
                :value {:category "equipment"}}
          verdict (gov/check req nil prop s)]
      (is (:ok? verdict))
      (is (not (:escalate? verdict))))))

(deftest ok-supply-order-low-cost
  (testing "OK: supply order under the cost threshold"
    (let [orchard {:id "orchard-001" :name "Test Orchard Block"}
          s (store/mem-store {:initial-orchards {"orchard-001" orchard}})
          req {:orchard-id "orchard-001"}
          prop {:op :order-supplies :effect :propose :cost 100 :confidence 0.9}
          verdict (gov/check req nil prop s)]
      (is (:ok? verdict))
      (is (not (:escalate? verdict))))))

(deftest ok-schedule-field-operation
  (testing "OK: scheduling a field operation is a routine coordination op"
    (let [orchard {:id "orchard-001" :name "Test Orchard Block"}
          s (store/mem-store {:initial-orchards {"orchard-001" orchard}})
          req {:orchard-id "orchard-001"}
          prop {:op :schedule-field-operation :effect :propose :confidence 0.85}
          verdict (gov/check req nil prop s)]
      (is (:ok? verdict))
      (is (not (:escalate? verdict))))))
