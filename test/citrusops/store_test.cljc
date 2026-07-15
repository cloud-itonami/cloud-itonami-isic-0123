(ns citrusops.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [citrusops.store :as store]))

(deftest mem-store-creation
  (testing "Create empty store"
    (let [st (store/mem-store)]
      (is (some? st))
      (is (satisfies? store/Store st))))

  (testing "Create store with initial orchards"
    (let [orchards {"orchard-001" {:id "orchard-001" :name "Test Orchard Block"}}
          st (store/mem-store {:initial-orchards orchards})]
      (is (some? st))
      (is (satisfies? store/Store st)))))

(deftest registered-orchard-retrieval
  (testing "Retrieve existing orchard"
    (let [orchard {:id "orchard-001" :name "Test Orchard Block"}
          st (store/mem-store {:initial-orchards {"orchard-001" orchard}})]
      (is (= orchard (store/registered-orchard st "orchard-001")))))

  (testing "Retrieve non-existent orchard"
    (let [st (store/mem-store)]
      (is (nil? (store/registered-orchard st "no-such-orchard")))))

  (testing "nil orchard-id returns nil (never falls through to a default)"
    (let [st (store/mem-store {:initial-orchards {"orchard-001" {:id "orchard-001"}}})]
      (is (nil? (store/registered-orchard st nil))))))

(deftest add-orchard-test
  (testing "Register a new orchard"
    (let [st (store/mem-store)
          orchard-data {:id "orchard-002" :name "New Orchard Block"}
          result (store/add-orchard st "orchard-002" orchard-data)]
      (is (= orchard-data result))
      (is (= orchard-data (store/registered-orchard st "orchard-002")))))

  (testing "Update an existing orchard"
    (let [st (store/mem-store {:initial-orchards {"orchard-001" {:id "orchard-001"}}})
          updated {:id "orchard-001" :name "Renamed Orchard Block"}
          result (store/add-orchard st "orchard-001" updated)]
      (is (= updated result))
      (is (= updated (store/registered-orchard st "orchard-001"))))))
