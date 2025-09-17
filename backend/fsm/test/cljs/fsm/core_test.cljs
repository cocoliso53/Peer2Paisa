(ns fsm.core-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [fsm.core :as core]))

(def s0
  {:state   "s0"
   :status  "active"
   :orderId "orderId123"})

(def username "username")
(def last-message-id 1234)

(deftest delta-basic-flow

  (let [base-event     {:username  username
                        :messageId last-message-id}
        buy-state      (core/delta s0
                                   (assoc base-event :event "buy"))
        sell-state     (core/delta s0
                                   (assoc base-event :event "sell"))
        canceled-state (core/delta s0 {:event "cancel"})]
    
    (testing "s0 transitions"
      (is (= {:state         "waitingNewOrderAmount"
              :status        "active"
              :orderId       "orderId123"
              :buyer         {:username "username"}
              :participants  ["username"]
              :sell          false
              :lastMessageId 1234}
             buy-state))
      (is (= {:state         "waitingNewOrderAmount"
              :status        "active"
              :orderId       "orderId123"
              :seller        {:username "username"}
              :participants  ["username"]
              :sell          true
              :lastMessageId 1234}
             sell-state))
      (is (= {:state   "canceled"
              :status  "canceled"
              :orderId "orderId123"}
             (core/delta s0 {:event "cancel"})))
      (is (= {:state   "s0",
              :status  "active",
              :orderId "orderId123",
              :error   "Invalid transition"}
             (core/delta s0 {:event "invalid"}))))

    (testing "waitingNewOrderAmount transitions"
      (is (= {:amount        "100-1000",
              :participants  ["username"],
              :state         "waitingSetAddress",
              :sell          false,
              :status        "active",
              :lastMessageId 1111,
              :buyer         {:username "username"},
              :range         true,
              :orderId       "orderId123"}
             (core/delta buy-state
                         {:event     "setAmount"
                          :data      "100-1000"
                          :messageId 1111})))
      (is (= {:amount        "100",
              :participants  ["username"],
              :state         "waitingSetAddress",
              :sell          false,
              :status        "active",
              :lastMessageId 1111,
              :buyer         {:username "username"},
              :range         false,
              :orderId       "orderId123"}
             (core/delta buy-state
                         {:event     "setAmount"
                          :data      "100"
                          :messageId 1111})))
      (is (= {:state         "waitingNewOrderAmount"
              :status        "active"
              :orderId       "orderId123"
              :buyer         {:username "username"}
              :participants  ["username"]
              :sell          false
              :lastMessageId 1234
              :error         "Invalid range or number"}
             (core/delta buy-state
                         {:event     "setAmount"
                          :data      "not-valid"
                          :messageId 1111})))
      (is (= {:state         "waitingNewOrderAmount"
              :status        "active"
              :orderId       "orderId123"
              :buyer         {:username "username"}
              :participants  ["username"]
              :sell          false
              :lastMessageId 1234
              :error         "Invalid range or number"}
             (core/delta buy-state
                         {:event     "setAmount"
                          :data      nil
                          :messageId 1111})))
      (is (= {:amount        "100"
              :participants  ["username"]
              :state         "waitingSetAddress"
              :sell          false
              :status        "active"
              :lastMessageId 1111
              :buyer         {:username "username"}
              :range         false
              :orderId       "orderId123"}
             (core/delta {:state         "waitingNewOrderAmount"
                          :status        "active"
                          :orderId       "orderId123"
                          :buyer         {:username "username"}
                          :participants  ["username"]
                          :sell          false
                          :lastMessageId 1234
                          :error         "Invalid range or number"}
                         {:event     "setAmount"
                          :data      "100"
                          :messageId 1111})))
      (is (= {:state         "canceled",
              :status        "canceled",
              :orderId       "orderId123",
              :buyer         {:username "username"},
              :participants  ["username"],
              :sell          false,
              :lastMessageId 1234}
             (core/delta buy-state
                         {:event "cancel"}))))))

(deftest delta-cancel
  (testing "cancel from s0"
    (let [state {:state "s0"}
          event {:event "cancel"}
          new-state (core/delta state event)]
      (is (= "canceled" (:state new-state)))
      (is (= "canceled" (:status new-state))))))
