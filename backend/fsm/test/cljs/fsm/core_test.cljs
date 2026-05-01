(ns fsm.core-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [fsm.core :as core]))

(def username "username")
(def last-message-id 1234)
(def base-event {:data {:username  username
                        :messageId last-message-id}})
(def s0 {:state   "s0"
         :status  "active"
         :orderId "orderId123"})
(def user {:username "username"
           :chatId   987654
           :address  "0x987654"})
(def counterpart {:username "counterpart"
                  :chatId   56789})
(def buy-state (core/delta s0 (assoc base-event :event "buy")))
(def sell-state (core/delta s0 (assoc base-event :event "sell")))
(def canceled-state (core/delta s0 {:event "cancel"}))

(defn transit-events
  [init-state events]
  (reduce core/delta init-state events))


(deftest transit-events-function
  
  (testing "correct behaviour helper function"
    (is (= {:amount        "100-1000"
            :participants  ["username"]
            :state         "waitingSetAddress"
            :sell          false
            :status        "active"
            :lastMessageId 1111
            :buyer         {:username "username"}
            :range         true
            :orderId       "orderId123"}
           (transit-events s0 [(assoc base-event :event "buy")
                               {:event "setAmount"
                                :data  {:text      "100-1000"
                                        :messageId 1111}}])))))

(deftest s0-transitions
  (testing "buy transition"
    (let [event (assoc base-event :event "buy")]
      (is (= {:state         "waitingNewOrderAmount"
              :status        "active"
              :orderId       "orderId123"
              :buyer         {:username "username"}
              :participants  ["username"]
              :sell          false
              :lastMessageId 1234}
             (core/delta s0 event)))
      (is (= {:reply ["Enter exact amount or range (eg. 100-1000) to buy"]}
             (core/omega s0 event)))))
  (testing "sell transition"
    (let [event (assoc base-event :event "sell")]
      (is (= {:state         "waitingNewOrderAmount"
              :status        "active"
              :orderId       "orderId123"
              :seller        {:username "username"}
              :participants  ["username"]
              :sell          true
              :lastMessageId 1234}
             (core/delta s0 event)))
      (is (= {:reply ["Enter exact amount or range (eg. 100-1000) to sell"]}
             (core/omega s0 event)))))
  (testing "cancel transition"
    (let [event {:event "cancel"}]
      (is (= {:state   "canceled"
              :status  "canceled"
              :orderId "orderId123"}
             (core/delta s0 event)))
      (is (= {:reply ["Order canceled succesfully"]}
             (core/omega s0 (assoc base-event :event "cancel"))))))
  (testing "invalid transition"
    (let [event {:event "invalid"}]
      (is (= {:state   "s0"
              :status  "active"
              :orderId "orderId123"
              :error   "Invalid transition"}
             (core/delta s0 event)))
      (is (= {:error "Invalid transition, no effect"}
             (core/omega s0 (assoc base-event :event "invalid")))))))

(deftest waiting-new-order-amount-transitions
  (let [buy-state (transit-events s0 [(assoc base-event :event "buy")
                                      {:event "setLastMessageId"
                                       :data  {:messageId 1111}}])
        sell-state (transit-events s0 [(assoc base-event :event "sell")
                                       {:event "setLastMessageId"
                                        :data  {:messageId 1111}}])
        range-event {:event "setAmount"
                     :data  {:text      "100-1000"
                             :messageId 1111}}
        single-event {:event "setAmount"
                      :data  {:text      "100"
                              :messageId 1111}}
        invalid-event {:event "setAmount"
                       :data  {:text      "not-valid"
                               :messageId 1111}}
        missing-text-event {:event "setAmount"
                            :data  {:messageId 1111}}
        cancel-event {:event "cancel"}]
    (testing "set amount with range for buyer"
      (let [next-state (core/delta buy-state range-event)]
        (is (= {:amount        "100-1000"
                :participants  ["username"]
                :state         "waitingSetAddress"
                :sell          false
                :status        "active"
                :lastMessageId 1111
                :buyer         {:username "username"}
                :range         true
                :orderId       "orderId123"}
               next-state))
        (is (= {:deleteMessage [{:chat "current"
                                 :id   1111}]
                :reply         ["Enter address to receive funds"]}
               (core/omega buy-state range-event)))))
    (testing "set amount with range for seller"
      (let [next-state (core/delta sell-state range-event)]
        (is (= {:amount        "100-1000"
                :participants  ["username"]
                :state         "waitingSetAddress"
                :sell          true
                :status        "active"
                :lastMessageId 1111
                :seller        {:username "username"}
                :range         true
                :orderId       "orderId123"}
               next-state))
        (is (= {:deleteMessage [{:chat "current"
                                 :id   1111}]
                :reply         ["Enter address for refund (if necessary)"]}
               (core/omega sell-state range-event)))))
    (testing "set amount with single value"
      (let [next-state (core/delta buy-state single-event)]
        (is (= {:amount        "100"
                :participants  ["username"]
                :state         "waitingSetAddress"
                :sell          false
                :status        "active"
                :lastMessageId 1111
                :buyer         {:username "username"}
                :range         false
                :orderId       "orderId123"}
               next-state))
        (is (= {:deleteMessage [{:chat "current"
                                 :id   1111}]
                :reply         ["Enter address to receive funds"]}
               (core/omega buy-state single-event)))))
    (testing "invalid amount input"
      (let [next-state (core/delta buy-state invalid-event)]
        (is (= {:state         "waitingNewOrderAmount"
                :status        "active"
                :orderId       "orderId123"
                :buyer         {:username "username"}
                :participants  ["username"]
                :sell          false
                :lastMessageId 1111
                :error         "Invalid range or number"}
               next-state))
        (is (= {:deleteMessage [{:chat "current"
                                 :id   1111}]
                :reply         ["Enter address to receive funds"]}
               (core/omega buy-state invalid-event)))))
    (testing "missing amount text"
      (let [next-state (core/delta buy-state missing-text-event)]
        (is (= {:state         "waitingNewOrderAmount"
                :status        "active"
                :orderId       "orderId123"
                :buyer         {:username "username"}
                :participants  ["username"]
                :sell          false
                :lastMessageId 1111
                :error         "Invalid range or number"}
               next-state))
        (is (= {:deleteMessage [{:chat "current"
                                 :id   1111}]
                :reply         ["Enter address to receive funds"]}
               (core/omega buy-state missing-text-event)))))
    (testing "recovery after invalid input"
      (let [error-state (core/delta buy-state invalid-event)
            next-state (core/delta error-state single-event)]
        (is (= {:amount        "100"
                :participants  ["username"]
                :state         "waitingSetAddress"
                :sell          false
                :status        "active"
                :lastMessageId 1111
                :buyer         {:username "username"}
                :range         false
                :orderId       "orderId123"}
               next-state))
        (is (= {:deleteMessage [{:chat "current"
                                 :id   1111}]
                :reply         ["Enter address to receive funds"]}
               (core/omega error-state single-event)))))
    (testing "cancel transition"
      (let [next-state (core/delta buy-state cancel-event)]
        (is (= {:state         "canceled"
                :status        "canceled"
                :orderId       "orderId123"
                :buyer         {:username "username"}
                :participants  ["username"]
                :sell          false
                :lastMessageId 1111}
               next-state))
        (is (= {:reply ["Order canceled succesfully"]}
               (core/omega buy-state (assoc base-event :event "cancel"))))))))

(deftest waiting-set-address-transitions
  (let [sell-state (transit-events s0 [(assoc base-event :event "sell")
                                       {:event "setAmount"
                                        :data  {:text      "100"
                                                :messageId 1111}}
                                       {:event "setLastMessageId"
                                        :data  {:messageId 1112}}])
        buy-state (transit-events s0 [(assoc base-event :event "buy")
                                      {:event "setAmount"
                                       :data  {:text      "100"
                                               :messageId 1111}}
                                      {:event "setLastMessageId"
                                       :data  {:messageId 1112}}])
        buy-state-no-last (transit-events s0 [(assoc base-event :event "buy")
                                              {:event "setAmount"
                                               :data  {:text      "100"
                                                       :messageId 1111}}])
        set-address-event {:event "setAddress"
                           :data  {:text      "0x1231231213"
                                   :user      user
                                   :messageId 1112}}
        missing-user-event {:event "setAddress"
                            :data  {:text      "0x1231231213"
                                    :messageId 1112}}
        cancel-event {:event "cancel"}]
    (testing "seller provides address"
      (let [next-state (core/delta sell-state set-address-event)]
        (is (= {:amount        "100"
                :participants  ["username"]
                :state         "watingCounterpart"
                :sell          true
                :status        "active"
                :lastMessageId 1112
                :range         false
                :seller        {:username "username"
                                :chatId   987654
                                :address  "0x987654"}
                :orderId       "orderId123"}
               next-state))
        (is (= {:reply            ["Order created: orderId123"]
                :deleteMessage    [{:chat "current"
                                    :id   1112}]
                :orderBookMessage [{:text     "Selling 100"
                                    :keyboard {:text     "Take Order"
                                               :callback "take:orderId123"}}]}
               (core/omega sell-state set-address-event)))))
    (testing "buyer provides address"
      (let [next-state (core/delta buy-state set-address-event)]
        (is (= {:amount        "100"
                :participants  ["username"]
                :state         "watingCounterpart"
                :sell          false
                :status        "active"
                :lastMessageId 1112
                :buyer         {:username "username"
                                :chatId   987654
                                :address  "0x987654"}
                :range         false
                :orderId       "orderId123"}
               next-state))
        (is (= {:reply            ["Order created: orderId123"]
                :deleteMessage    [{:chat "current"
                                    :id   1112}]
                :orderBookMessage [{:text     "Buying 100"
                                    :keyboard {:text     "Take Order"
                                               :callback "take:orderId123"}}]}
               (core/omega buy-state set-address-event)))))
    (testing "missing user data"
      (let [next-state (core/delta buy-state missing-user-event)]
        (is (= {:amount        "100"
                :participants  ["username"]
                :state         "waitingSetAddress"
                :sell          false
                :status        "active"
                :lastMessageId 1112
                :error         "No full user data"
                :buyer         {:username "username"}
                :range         false
                :orderId       "orderId123"}
               next-state))
        (is (= {:reply            ["Order created: orderId123"]
                :deleteMessage    [{:chat "current"
                                    :id   1112}]
                :orderBookMessage [{:text     "Buying 100"
                                    :keyboard {:text     "Take Order"
                                               :callback "take:orderId123"}}]}
               (core/omega buy-state missing-user-event)))))
    (testing "cancel transition"
      (let [next-state (core/delta buy-state-no-last cancel-event)]
        (is (= {:amount        "100"
                :participants  ["username"]
                :state         "canceled"
                :sell          false
                :status        "canceled"
                :lastMessageId 1111
                :buyer         {:username "username"}
                :range         false
                :orderId       "orderId123"}
               next-state))
        (is (= {:reply ["Order canceled succesfully"]}
               (core/omega buy-state-no-last (assoc base-event :event "cancel"))))))))

(deftest wating-counterpart-transitions
  (let [sell-state (transit-events s0 [(assoc base-event :event "sell")
                                       {:event "setAmount"
                                        :data  {:text      "100"
                                                :messageId 1111}}
                                       {:event "setAddress"
                                        :data  {:text      "0x1231231213"
                                                :user      user
                                                :messageId 1112}}
                                       {:event "setMessagesIds"
                                        :data  {:messageId      1113
                                                :orderMessageId 501}}])
        buy-state (transit-events s0 [(assoc base-event :event "buy")
                                      {:event "setAmount"
                                       :data  {:text      "100"
                                               :messageId 1111}}
                                      {:event "setAddress"
                                       :data  {:text      "0x1231231213"
                                               :user      user
                                               :messageId 1112}}
                                      {:event "setMessagesIds"
                                       :data  {:messageId      1113
                                               :orderMessageId 501}}])
        sell-range-state (transit-events s0 [(assoc base-event :event "sell")
                                             {:event "setAmount"
                                              :data  {:text      "100-1000"
                                                      :messageId 1111}}
                                             {:event "setAddress"
                                              :data  {:text      "0x1231231213"
                                                      :user      user
                                                      :messageId 1112}}
                                             {:event "setMessagesIds"
                                              :data  {:messageId      1113
                                                      :orderMessageId 501}}])
        buy-range-state (transit-events s0 [(assoc base-event :event "buy")
                                            {:event "setAmount"
                                             :data  {:text      "100-1000"
                                                     :messageId 1111}}
                                            {:event "setAddress"
                                             :data  {:text      "0x1231231213"
                                                     :user      user
                                                     :messageId 1112}}
                                            {:event "setMessagesIds"
                                             :data  {:messageId      1113
                                                     :orderMessageId 501}}])
        take-order-event {:event "takeOrder"
                          :data  {:counterpart counterpart
                                  :takerChatId 56789}}
        take-order-range-event {:event "takeOrderRange"
                                :data  {:counterpart counterpart
                                        :takerChatId 56789}}]
    (testing "take order for seller"
      (let [next-state (core/delta sell-state take-order-event)]
        (is (= {:amount         "100"
                :participants   ["username" "counterpart"]
                :state          "watingCounterpartAddress"
                :orderMessageId 501
                :sell           true
                :status         "active"
                :lastMessageId  1113
                :buyer          {:username "counterpart"
                                 :chatId   56789}
                :range          false
                :seller         {:username "username"
                                 :chatId   987654
                                 :address  "0x987654"}
                :orderId        "orderId123"}
               next-state))
        (is (= {:deleteMessage [{:chat "ctx"}]
                :answerCbQuery true
                :sendMessage   [{:chatId 987654
                                 :text   "Order #orderId123 has been taken. Waiting for the counterpart to enter details"}
                                {:chatId 56789
                                 :text   "Please enter the wallet where you want to receive the funds"}]}
               (core/omega sell-state take-order-event)))))
    (testing "take order for buyer"
      (let [next-state (core/delta buy-state take-order-event)]
        (is (= {:amount         "100"
                :participants   ["username" "counterpart"]
                :state          "watingCounterpartAddress"
                :orderMessageId 501
                :sell           false
                :status         "active"
                :lastMessageId  1113
                :buyer          {:username "username"
                                 :chatId   987654
                                 :address  "0x987654"}
                :range          false
                :seller         {:username "counterpart"
                                 :chatId   56789}
                :orderId        "orderId123"}
               next-state))
        (is (= {:deleteMessage [{:chat "ctx"}]
                :answerCbQuery true
                :sendMessage   [{:chatId 987654
                                 :text   "Order #orderId123 has been taken. Waiting for the counterpart to enter details"}
                                {:chatId 56789
                                 :text   "Enter address for refund (if necessary)"}]}
               (core/omega buy-state take-order-event)))))
    (testing "take order range for seller"
      (let [next-state (core/delta sell-range-state take-order-range-event)]
        (is (= {:amount         "100-1000"
                :participants   ["username" "counterpart"]
                :state          "watingCounterpartSetAmount"
                :orderMessageId 501
                :sell           true
                :status         "active"
                :lastMessageId  1113
                :buyer          {:username "counterpart"
                                 :chatId   56789}
                :range          true
                :seller         {:username "username"
                                 :chatId   987654
                                 :address  "0x987654"}
                :orderId        "orderId123"}
               next-state))
        (is (= {:deleteMessage [{:chat "ctx"}]
                :answerCbQuery true
                :sendMessage   [{:chatId 987654
                                 :text   "Order #orderId123 has been taken. Waiting for the counterpart to enter details"}
                                {:chatId 56789
                                 :text   "Select an amount between 100-1000"}]}
               (core/omega sell-range-state take-order-range-event)))))
    (testing "take order range for buyer"
      (let [next-state (core/delta buy-range-state take-order-range-event)]
        (is (= {:amount         "100-1000"
                :participants   ["username" "counterpart"]
                :state          "watingCounterpartSetAmount"
                :orderMessageId 501
                :sell           false
                :status         "active"
                :lastMessageId  1113
                :buyer          {:username "username"
                                 :chatId   987654
                                 :address  "0x987654"}
                :range          true
                :seller         {:username "counterpart"
                                 :chatId   56789}
                :orderId        "orderId123"}
               next-state))
        (is (= {:deleteMessage [{:chat "ctx"}]
                :answerCbQuery true
                :sendMessage   [{:chatId 987654
                                 :text   "Order #orderId123 has been taken. Waiting for the counterpart to enter details"}
                                {:chatId 56789
                                 :text   "Select an amount between 100-1000"}]}
               (core/omega buy-range-state take-order-range-event)))))))

(deftest wating-counterpart-address-transitions
  (let [sell-state (transit-events s0 [(assoc base-event :event "sell")
                                       {:event "setAmount"
                                        :data  {:text      "100"
                                                :messageId 1111}}
                                       {:event "setAddress"
                                        :data  {:text      "0x1231231213"
                                                :user      user
                                                :messageId 1112}}
                                       {:event "setMessagesIds"
                                        :data  {:messageId      1113
                                                :orderMessageId 501}}
                                       {:event "takeOrder"
                                        :data  {:counterpart counterpart}}])
        buy-state (transit-events s0 [(assoc base-event :event "buy")
                                      {:event "setAmount"
                                       :data  {:text      "100"
                                               :messageId 1111}}
                                      {:event "setAddress"
                                       :data  {:text      "0x1231231213"
                                               :user      user
                                               :messageId 1112}}
                                      {:event "setMessagesIds"
                                       :data  {:messageId      1113
                                               :orderMessageId 501}}
                                      {:event "takeOrder"
                                       :data  {:counterpart counterpart}}])
        sell-range-state (transit-events s0 [(assoc base-event :event "sell")
                                             {:event "setAmount"
                                              :data  {:text      "100-1000"
                                                      :messageId 1111}}
                                             {:event "setAddress"
                                              :data  {:text      "0x1231231213"
                                                      :user      user
                                                      :messageId 1112}}
                                             {:event "setMessagesIds"
                                              :data  {:messageId      1113
                                                      :orderMessageId 501}}
                                             {:event "takeOrderRange"
                                              :data  {:counterpart counterpart}}
                                             {:event "setAmountCounterpart"
                                              :data  {:text "500"}}])
        buy-range-state (transit-events s0 [(assoc base-event :event "buy")
                                            {:event "setAmount"
                                             :data  {:text      "100-1000"
                                                     :messageId 1111}}
                                            {:event "setAddress"
                                             :data  {:text      "0x1231231213"
                                                     :user      user
                                                     :messageId 1112}}
                                            {:event "setMessagesIds"
                                             :data  {:messageId      1113
                                                     :orderMessageId 501}}
                                            {:event "takeOrderRange"
                                             :data  {:counterpart counterpart}}
                                            {:event "setAmountCounterpart"
                                             :data  {:text "500"}}])
        counterpart-address-event {:event "setCounterpartAddress"
                                   :data  {:counterpart (assoc counterpart
                                                               :address "0x56789")}}
        mismatch-event {:event "setCounterpartAddress"
                        :data  {:counterpart {:username "otherguy"
                                              :chatId   9999
                                              :address  "0x9999"}}}]
    (testing "set counterpart address for seller"
      (let [next-state (core/delta sell-state counterpart-address-event)]
        (is (= {:amount         "100"
                :participants   ["username" "counterpart"]
                :state          "waitingFunding"
                :orderMessageId 501
                :sell           true
                :status         "active"
                :lastMessageId  1113
                :buyer          {:username "counterpart"
                                 :chatId   56789
                                 :address  "0x56789"}
                :range          false
                :seller         {:username "username"
                                 :chatId   987654
                                 :address  "0x987654"}
                :orderId        "orderId123"}
               next-state))
        (is (= {:createEscrow {:buyerAddress  "0x56789"
                               :sellerAddress "0x987654"
                               :amount        "100"}}
               (core/omega sell-state counterpart-address-event)))))
    (testing "set counterpart address for buyer"
      (let [next-state (core/delta buy-state counterpart-address-event)]
        (is (= {:amount         "100"
                :participants   ["username" "counterpart"]
                :state          "waitingFunding"
                :orderMessageId 501
                :sell           false
                :status         "active"
                :lastMessageId  1113
                :buyer          {:username "username"
                                 :chatId   987654
                                 :address  "0x987654"}
                :range          false
                :seller         {:username "counterpart"
                                 :chatId   56789
                                 :address  "0x56789"}
                :orderId        "orderId123"}
               next-state))
        (is (= {:createEscrow {:buyerAddress  "0x987654"
                               :sellerAddress "0x56789"
                               :amount        "100"}}
               (core/omega buy-state counterpart-address-event)))))
    (testing "set counterpart address after range amount for seller"
      (let [next-state (core/delta sell-range-state counterpart-address-event)]
        (is (= {:amount         "500"
                :participants   ["username" "counterpart"]
                :state          "waitingFunding"
                :orderMessageId 501
                :sell           true
                :status         "active"
                :lastMessageId  1113
                :buyer          {:username "counterpart"
                                 :chatId   56789
                                 :address  "0x56789"}
                :range          true
                :seller         {:username "username"
                                 :chatId   987654
                                 :address  "0x987654"}
                :orderId        "orderId123"}
               next-state))
        (is (= {:createEscrow {:buyerAddress  "0x56789"
                               :sellerAddress "0x987654"
                               :amount        "500"}}
               (core/omega sell-range-state counterpart-address-event)))))
    (testing "set counterpart address after range amount for buyer"
      (let [next-state (core/delta buy-range-state counterpart-address-event)]
        (is (= {:amount         "500"
                :participants   ["username" "counterpart"]
                :state          "waitingFunding"
                :orderMessageId 501
                :sell           false
                :status         "active"
                :lastMessageId  1113
                :buyer          {:username "username"
                                 :chatId   987654
                                 :address  "0x987654"}
                :range          true
                :seller         {:username "counterpart"
                                 :chatId   56789
                                 :address  "0x56789"}
                :orderId        "orderId123"}
               next-state))
        (is (= {:createEscrow {:buyerAddress  "0x987654"
                               :sellerAddress "0x56789"
                               :amount        "500"}}
               (core/omega buy-range-state counterpart-address-event)))))
    (testing "counterpart mismatch"
      (let [next-state (core/delta buy-state mismatch-event)]
        (is (= {:amount         "100"
                :participants   ["username" "counterpart"]
                :state          "watingCounterpartAddress"
                :orderMessageId 501
                :sell           false
                :status         "active"
                :lastMessageId  1113
                :error          "counterpart does not match previous data"
                :buyer          {:username "username"
                                 :chatId   987654
                                 :address  "0x987654"}
                :range          false
                :seller         {:username "counterpart"
                                 :chatId   56789}
                :orderId        "orderId123"}
               next-state))
        (is (= {:createEscrow {:buyerAddress  "0x987654"
                               :sellerAddress "0x9999"
                               :amount        "100"}}
               (core/omega buy-state mismatch-event)))))))

(deftest waiting-funding-transitions
  (let [sell-funding-state (transit-events s0 [(assoc base-event :event "sell")
                                               {:event "setAmount"
                                                :data  {:text      "100"
                                                        :messageId 1111}}
                                               {:event "setAddress"
                                                :data  {:text      "0x1231231213"
                                                        :user      user
                                                        :messageId 1112}}
                                               {:event "setMessagesIds"
                                                :data  {:messageId      1113
                                                        :orderMessageId 501}}
                                               {:event "takeOrder"
                                                :data  {:counterpart counterpart}}
                                               {:event "setCounterpartAddress"
                                                :data  {:counterpart (assoc counterpart
                                                                            :address "0x56789")}}])
        buy-funding-state (transit-events s0 [(assoc base-event :event "buy")
                                              {:event "setAmount"
                                               :data  {:text      "100"
                                                       :messageId 1111}}
                                              {:event "setAddress"
                                               :data  {:text      "0x1231231213"
                                                       :user      user
                                                       :messageId 1112}}
                                              {:event "setMessagesIds"
                                               :data  {:messageId      1113
                                                       :orderMessageId 501}}
                                              {:event "takeOrder"
                                               :data  {:counterpart counterpart}}
                                              {:event "setCounterpartAddress"
                                               :data  {:counterpart (assoc counterpart
                                                                           :address "0x56789")}}])
        set-escrow-event {:event "setEscrowAddress"
                          :data  {:escrow    "0xescrow123"
                                  :escrowUrl "escrowurl"}}
        escrow-created-event {:event "escrowCreated"
                              :data  {:escrow    "0xescrow123"
                                      :escrowUrl "escrowurl"}}
        escrow-creation-failed-event {:event "escrowCreationFailed"
                                      :data  {}}
        escrow-funded-event {:event "escrowFunded"
                             :data  {}}
        escrow-funding-failed-event {:event "escrowFundingFailed"
                                     :data  {}}
        sell-with-escrow (core/delta sell-funding-state set-escrow-event)
        buy-with-escrow (core/delta buy-funding-state set-escrow-event)]
    (testing "set escrow address transition"
      (is (= {:amount         "100"
              :escrowAddress  "0xescrow123"
              :participants   ["username" "counterpart"]
              :state          "waitingFunding"
              :orderMessageId 501
              :sell           true
              :status         "active"
              :lastMessageId  1113
              :buyer          {:username "counterpart"
                               :chatId   56789
                               :address  "0x56789"}
              :range          false
              :seller         {:username "username"
                               :chatId   987654
                               :address  "0x987654"}
              :orderId        "orderId123"}
             sell-with-escrow))
      (is (= {:sendMessage [{:chatId    56789
                             :parseMode "Markdown"
                             :text      "Escrow created, click [here](escrowurl) to see the details. Wating for seller to fund escrow contract"}
                            {:chatId    987654
                             :parseMode "Markdown"
                             :text      "Escrow created, click [here](escrowurl) to see the details. Deposit 100 and then type /funded to continue"}
                            {:chatId 987654
                             :text   "0xescrow123"}]}
             (core/omega sell-funding-state set-escrow-event))))
    (testing "escrow created transition alias"
      (is (= sell-with-escrow
             (core/delta sell-funding-state escrow-created-event)))
      (is (= (core/omega sell-funding-state set-escrow-event)
             (core/omega sell-funding-state escrow-created-event))))
    (testing "escrow creation failed keeps state and replies"
      (is (= sell-funding-state
             (core/delta sell-funding-state escrow-creation-failed-event)))
      (is (= {:reply ["Sorry, we couldn't create the escrow contract. Please contact support."]}
             (core/omega sell-funding-state escrow-creation-failed-event))))
    (testing "escrow funded for seller"
      (let [next-state (core/delta sell-with-escrow escrow-funded-event)]
        (is (= {:amount         "100"
                :escrowAddress  "0xescrow123"
                :participants   ["username" "counterpart"]
                :state          "waitingRelease"
                :orderMessageId 501
                :sell           true
                :status         "active"
                :lastMessageId  1113
                :buyer          {:username "counterpart"
                                 :chatId   56789
                                 :address  "0x56789"}
                :range          false
                :seller         {:username "username"
                                 :chatId   987654
                                 :address  "0x987654"}
                :orderId        "orderId123"}
               next-state))
        (is (= {:sendMessage [{:chatId 987654
                               :text   "Escrow funded succesfully. Contact @counterpart to agree on fiat transfer details. Type /release ONLY after you have confirmed the reception of funds"}
                              {:chatId 56789
                               :text   "Seller has funded the escrow account. Contact @username to agree on fiat transfer details"}]}
               (core/omega sell-with-escrow escrow-funded-event)))))
    (testing "escrow funded for buyer"
      (let [next-state (core/delta buy-with-escrow escrow-funded-event)]
        (is (= {:amount         "100"
                :escrowAddress  "0xescrow123"
                :participants   ["username" "counterpart"]
                :state          "waitingRelease"
                :orderMessageId 501
                :sell           false
                :status         "active"
                :lastMessageId  1113
                :buyer          {:username "username"
                                 :chatId   987654
                                 :address  "0x987654"}
                :range          false
                :seller         {:username "counterpart"
                                 :chatId   56789
                                 :address  "0x56789"}
                :orderId        "orderId123"}
               next-state))
        (is (= {:sendMessage [{:chatId 56789
                               :text   "Escrow funded succesfully. Contact @username to agree on fiat transfer details. Type /release ONLY after you have confirmed the reception of funds"}
                              {:chatId 987654
                               :text   "Seller has funded the escrow account. Contact @counterpart to agree on fiat transfer details"}]}
               (core/omega buy-with-escrow escrow-funded-event)))))
    (testing "escrow funding failed keeps state and asks seller to confirm deposit"
      (is (= sell-with-escrow
             (core/delta sell-with-escrow escrow-funding-failed-event)))
      (is (= {:sendMessage [{:chatId 987654
                             :text   "Please confirm you deposited 100 XOC in the escrow address 0xescrow123"}]}
             (core/omega sell-with-escrow escrow-funding-failed-event))))))

(deftest completed-order-transition
  (let [release-state (transit-events s0 [(assoc base-event :event "sell")
                                          {:event "setAmount"
                                           :data  {:text      "100"
                                                   :messageId 1111}}
                                          {:event "setAddress"
                                           :data  {:text      "0x1231231213"
                                                   :user      user
                                                   :messageId 1112}}
                                          {:event "setMessagesIds"
                                           :data  {:messageId      1113
                                                   :orderMessageId 501}}
                                          {:event "takeOrder"
                                           :data  {:counterpart counterpart}}
                                          {:event "setCounterpartAddress"
                                           :data  {:counterpart (assoc counterpart
                                                                   :address "0x56789")}}
                                          {:event "setEscrowAddress"
                                           :data  {:escrow    "0xescrow123"
                                                   :escrowUrl "escrowurl"}}
                                          {:event "escrowFunded"
                                           :data  {}}])
        funds-released-event {:event "fundsReleased"
                              :data  {:explorerUrl "explorerUrl"}}]
    (let [next-state (core/delta release-state funds-released-event)]
      (is (= {:amount         "100"
              :escrowAddress  "0xescrow123"
              :participants   ["username" "counterpart"]
              :state          "finished"
              :orderMessageId 501
              :sell           true
              :status         "done"
              :lastMessageId  1113
              :buyer          {:username "counterpart"
                               :chatId   56789
                               :address  "0x56789"}
              :range          false
              :seller         {:username "username"
                               :chatId   987654
                               :address  "0x987654"}
              :orderId        "orderId123"}
             next-state))
      (is (= {:sendMessage [{:chatId 56789
                             :text "Transaction [completed](explorerUrl)! Thanks for using our bot!"
                             :parseMode "Markdown"}
                            {:chatId 987654
                             :text "Transaction [completed](explorerUrl)! Thanks for using our bot!"
                             :parseMode "Markdown"}]}
             (core/omega release-state funds-released-event))))))

(deftest release-failed-transition
  (let [release-state (transit-events s0 [(assoc base-event :event "sell")
                                          {:event "setAmount"
                                           :data  {:text      "100"
                                                   :messageId 1111}}
                                          {:event "setAddress"
                                           :data  {:text      "0x1231231213"
                                                   :user      user
                                                   :messageId 1112}}
                                          {:event "setMessagesIds"
                                           :data  {:messageId      1113
                                                   :orderMessageId 501}}
                                          {:event "takeOrder"
                                           :data  {:counterpart counterpart}}
                                          {:event "setCounterpartAddress"
                                           :data  {:counterpart (assoc counterpart
                                                                   :address "0x56789")}}
                                          {:event "escrowCreated"
                                           :data  {:escrow    "0xescrow123"
                                                   :escrowUrl "escrowurl"}}
                                          {:event "escrowFunded"
                                           :data  {}}])
        release-failed-event {:event "releaseFailed"
                              :data  {}}]
    (is (= release-state
           (core/delta release-state release-failed-event)))
    (is (= {:sendMessage [{:chatId 987654
                           :text   "There was an error releasing the funds. Please try again or contact support"}
                          {:chatId 56789
                           :text   "There was an error releasing the funds. Wait for the seller to release again or contact support"}]}
           (core/omega release-state release-failed-event)))))

(deftest cancel-transitions-after-order-is-created
  (let [open-order-state (transit-events s0 [(assoc base-event :event "sell")
                                             {:event "setAmount"
                                              :data  {:text      "100"
                                                      :messageId 1111}}
                                             {:event "setAddress"
                                              :data  {:text      "0x1231231213"
                                                      :user      user
                                                      :messageId 1112}}
                                             {:event "setMessagesIds"
                                              :data  {:messageId      1113
                                                      :orderMessageId 501}}])
        taken-order-state (transit-events s0 [(assoc base-event :event "sell")
                                              {:event "setAmount"
                                               :data  {:text      "100"
                                                       :messageId 1111}}
                                              {:event "setAddress"
                                               :data  {:text      "0x1231231213"
                                                       :user      user
                                                       :messageId 1112}}
                                              {:event "setMessagesIds"
                                               :data  {:messageId      1113
                                                       :orderMessageId 501}}
                                              {:event "takeOrder"
                                               :data  {:counterpart counterpart
                                                       :takerChatId 56789}}])
        funding-state (transit-events s0 [(assoc base-event :event "sell")
                                          {:event "setAmount"
                                           :data  {:text      "100"
                                                   :messageId 1111}}
                                          {:event "setAddress"
                                           :data  {:text      "0x1231231213"
                                                   :user      user
                                                   :messageId 1112}}
                                          {:event "setMessagesIds"
                                           :data  {:messageId      1113
                                                   :orderMessageId 501}}
                                          {:event "takeOrder"
                                           :data  {:counterpart counterpart
                                                   :takerChatId 56789}}
                                          {:event "setCounterpartAddress"
                                           :data  {:counterpart (assoc counterpart
                                                                   :address "0x56789")}}
                                          {:event "setEscrowAddress"
                                           :data  {:escrow    "0xescrow123"
                                                   :escrowUrl "escrowurl"}}])
        release-state (transit-events funding-state [{:event "escrowFunded"}])
        seller-cancel-event {:event "cancel"
                             :data  {:username "username"}}
        buyer-cancel-event {:event "cancel"
                            :data  {:username "counterpart"}}]
    (testing "cancel open order removes orderbook message"
      (let [next-state (core/delta open-order-state seller-cancel-event)]
        (is (= "canceled" (:state next-state)))
        (is (= "canceled" (:status next-state)))
        (is (= {:reply         ["Order canceled succesfully"]
                :deleteMessage [{:chat "channel"
                                 :id   501}]}
               (core/omega open-order-state seller-cancel-event)))))
    (testing "cancel taken order notifies counterpart"
      (let [next-state (core/delta taken-order-state seller-cancel-event)]
        (is (= "canceled" (:state next-state)))
        (is (= "canceled" (:status next-state)))
        (is (= {:reply       ["Order canceled succesfully"]
                :sendMessage [{:chatId 56789
                               :text   "Seller canceled the order"}]}
               (core/omega taken-order-state seller-cancel-event)))))
    (testing "cancel funded-pending order requests escrow refund"
      (let [next-state (core/delta funding-state buyer-cancel-event)]
        (is (= "canceled" (:state next-state)))
        (is (= "canceled" (:status next-state)))
        (is (= {:reply        ["Order canceled succesfully"]
                :cancelEscrow {:escrowAddress      "0xescrow123"
                               :markAsFundedFirst true}
                :sendMessage  [{:chatId 987654
                                :text   "Buyer canceled the order"}]}
               (core/omega funding-state buyer-cancel-event)))))
    (testing "cancel released-pending order requests direct refund"
      (let [next-state (core/delta release-state seller-cancel-event)]
        (is (= "canceled" (:state next-state)))
        (is (= "canceled" (:status next-state)))
        (is (= {:reply        ["Order canceled succesfully"]
                :cancelEscrow {:escrowAddress      "0xescrow123"
                               :markAsFundedFirst false}
                :sendMessage  [{:chatId 56789
                                :text   "Seller canceled the order"}]}
               (core/omega release-state seller-cancel-event)))))))

(deftest set-last-message-id-transitions

  (testing "set-last-message-id should be idempotent on state"
    (is (= {:state         "waitingNewOrderAmount"
            :status        "active"
            :orderId       "orderId123"
            :buyer         {:username "username"}
            :participants  ["username"]
            :sell          false
            :lastMessageId 1111}
           (transit-events s0 [(assoc base-event :event "buy")
                               {:event "setLastMessageId"
                                :data  {:messageId 1111}}])))

    (is (= {:amount        "100-1000"
            :participants  ["username"]
            :state         "waitingSetAddress"
            :sell          false
            :status        "active"
            :lastMessageId nil
            :buyer         {:username "username"}
            :range         true
            :orderId       "orderId123"}
           (transit-events s0 [(assoc base-event :event "buy")
                               {:event "setLastMessageId"
                                :data  {:messageId 1111}}
                               {:event "setAmount"
                                :data  {:text "100-1000"}}])))

    (is (= {:amount        "100-1000"
            :participants  ["username"]
            :state         "waitingSetAddress"
            :sell          false
            :status        "active"
            :lastMessageId 1112
            :buyer         {:username "username"}
            :range         true
            :orderId       "orderId123"}
           (transit-events s0 [(assoc base-event :event "buy")
                               {:event "setLastMessageId"
                                :data  {:messageId 1111}}
                               {:event "setAmount"
                                :data  {:text "100-1000"}}
                               {:event "setLastMessageId"
                                :data  {:messageId 1112}}])))

    (is (= {:amount         "100-1000"
            :participants   ["username"]
            :state          "watingCounterpart"
            :orderMessageId 501
            :sell           false
            :status         "active"
            :lastMessageId  1113
            :buyer          {:username "username"
                             :chatId   987654
                             :address  "0x987654"}
            :range          true
            :orderId        "orderId123"}
           (transit-events s0 [(assoc base-event :event "buy")
                               {:event "setLastMessageId"
                                :data  {:messageId 1111}}
                               {:event "setAmount"
                                :data  {:text "100-1000"}}
                               {:event "setLastMessageId"
                                :data  {:messageId 1112}}
                               {:event "setAddress"
                                :data  {:text "0x1231231213"
                                        :user user}}
                               {:event "setMessagesIds"
                                :data  {:messageId      1113
                                        :orderMessageId 501}}])))))

(deftest text-input-transitions
  (let [buy-state (core/delta s0 (assoc base-event :event "buy"))
        amount-state (transit-events s0 [(assoc base-event :event "buy")
                                         {:event "setAmount"
                                          :data  {:text      "100"
                                                  :messageId 1111}}
                                         {:event "setLastMessageId"
                                          :data  {:messageId 1112}}])
        counterpart-state (transit-events s0 [(assoc base-event :event "sell")
                                              {:event "setAmount"
                                               :data  {:text      "100"
                                                       :messageId 1111}}
                                              {:event "setAddress"
                                               :data  {:text      "0x1231231213"
                                                       :user      user
                                                       :messageId 1112}}
                                              {:event "setMessagesIds"
                                               :data  {:messageId      1113
                                                       :orderMessageId 501}}
                                              {:event "takeOrder"
                                               :data  {:counterpart counterpart
                                                       :takerChatId 56789}}])
        amount-text-event {:event "textInput"
                           :data  {:text "100-1000"}}
        invalid-amount-text-event {:event "textInput"
                                   :data  {:text "not-valid"}}
        address-text-event {:event "textInput"
                            :data  {:text "0x987654"
                                    :user user}}
        invalid-address-text-event {:event "textInput"
                                    :data  {:text "invalid-address"
                                            :user user}}
        counterpart-address-event {:event "textInput"
                                   :data  {:text "0x56789"
                                           :counterpart (assoc counterpart :address "0x56789")}}]
    (testing "text input maps to amount during new order creation"
      (is (= {:amount        "100-1000"
              :participants  ["username"]
              :state         "waitingSetAddress"
              :sell          false
              :status        "active"
              :lastMessageId nil
              :buyer         {:username "username"}
              :range         true
              :orderId       "orderId123"}
             (core/delta buy-state amount-text-event)))
      (is (= {:reply ["Invalid range or number"]}
             (core/omega buy-state invalid-amount-text-event))))
    (testing "text input maps to address during owner setup"
      (is (= "watingCounterpart"
             (:state (core/delta amount-state address-text-event))))
      (is (= {:reply ["Invalid address, please try again"]}
             (core/omega amount-state invalid-address-text-event))))
    (testing "text input maps to counterpart address"
      (is (= "waitingFunding"
             (:state (core/delta counterpart-state counterpart-address-event))))
      (is (= {:reply ["Invalid address, please try again"]}
             (core/omega counterpart-state {:event "textInput"
                                            :data  {:text "invalid-address"
                                                    :counterpart counterpart}}))))))
