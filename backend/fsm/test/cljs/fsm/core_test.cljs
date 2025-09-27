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

(deftest delta-s0-transitions
  
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
           (core/delta s0 {:event "invalid"})))))

(deftest delta-waitingNewOrderAmount-transitions
  
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
                       {:event "setAmount"
                        :data  {:text      "100-1000"
                                :messageId 1111}})))
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
                       {:event "setAmount"
                        :data  {:text      "100"
                                :messageId 1111}})))
    (is (= {:state         "waitingNewOrderAmount"
            :status        "active"
            :orderId       "orderId123"
            :buyer         {:username "username"}
            :participants  ["username"]
            :sell          false
            :lastMessageId 1234
            :error         "Invalid range or number"}
           (core/delta buy-state
                       {:event "setAmount"
                        :data  {:text      "not-valid"
                                :messageId 1111}})))
    (is (= {:state         "waitingNewOrderAmount"
            :status        "active"
            :orderId       "orderId123"
            :buyer         {:username "username"}
            :participants  ["username"]
            :sell          false
            :lastMessageId 1234
            :error         "Invalid range or number"}
           (core/delta buy-state
                       {:event "setAmount"
                        :data  {:messageId 1111}})))
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
                       {:event "setAmount"
                        :data  {:text      "100"
                                :messageId 1111}})))
    (is (= {:state         "canceled",
            :status        "canceled",
            :orderId       "orderId123",
            :buyer         {:username "username"},
            :participants  ["username"],
            :sell          false,
            :lastMessageId 1234}
           (core/delta buy-state
                       {:event "cancel"})))))

(deftest delta-waitingSetAddress-transitions

  (testing "waitingSetAddress transitions"
    
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
           (transit-events s0 [(assoc base-event :event "sell")
                               {:event "setAmount"
                                :data  {:text      "100"
                                        :messageId 1111}}
                               {:event "setAddress"
                                :data  {:text      "0x1231231213"
                                        :user      user
                                        :messageId 1112}}])))
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
           (transit-events s0 [(assoc base-event :event "buy")
                               {:event "setAmount"
                                :data  {:text      "100"
                                        :messageId 1111}}
                               {:event "setAddress"
                                :data  {:text      "0x1231231213"
                                        :user      user
                                        :messageId 1112}}])))
    (is (= {:amount        "100"
            :participants  ["username"]
            :state         "waitingSetAddress"
            :sell          false
            :status        "active"
            :lastMessageId 1111
            :error         "No full user data"
            :buyer         {:username "username"}
            :range         false
            :orderId       "orderId123"}
           (transit-events s0 [(assoc base-event :event "buy")
                               {:event "setAmount"
                                :data  {:text      "100"
                                        :messageId 1111}}
                               {:event "setAddress"
                                :data  {:text      "0x1231231213"
                                        :messageId 1112}}])))
    (is (= {:amount        "100"
            :participants  ["username"]
            :state         "canceled"
            :sell          false
            :status        "canceled"
            :lastMessageId 1111
            :buyer         {:username "username"}
            :range         false
            :orderId       "orderId123"}
           (transit-events s0 [(assoc base-event :event "buy")
                               {:event "setAmount"
                                :data  {:text      "100"
                                        :messageId 1111}}
                               {:event "cancel"
                                :data  {:text      "0x1231231213"
                                        :user      user
                                        :messageId 1112}}])))))

(deftest delta-watingCounterpart-transitions

  (testing "watingCounterpart transitions, take order"
    
    (is (= {:amount         "100"
            :participants   ["username"]
            :state          "watingCounterpartAddress"
            :orderMessageId 501
            :sell           true
            :status         "active"
            :lastMessageId  1113
            :buyer          {:username "counterpart"
                             :chatId   56789}
            :range          false
            :seller         {:username "username"
                             :chatId   987654,
                             :address  "0x987654"}
            :orderId        "orderId123"}
           (transit-events s0 [(assoc base-event :event "sell")
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
                                :data  {:counterpart counterpart}}])))
    (is (= {:amount         "100"
            :participants   ["username"]
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
           (transit-events s0 [(assoc base-event :event "buy")
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
                                :data  {:counterpart counterpart}}]))))

  (testing "watingCounterpart transitions, take order range"
    
    (is (= {:amount         "100-1000"
            :participants   ["username"]
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
           (transit-events s0 [(assoc base-event :event "sell")
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
                                :data  {:counterpart counterpart}}])))
    (is (= {:amount         "100-1000"
            :participants   ["username"]
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
           (transit-events s0 [(assoc base-event :event "buy")
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
                                :data  {:counterpart counterpart}}])))))

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

;;; Omega ;;;

(deftest omega-s0-effects

  (testing "s0 transition effects"
    (is (= {:reply ["Enter exact amount or range (eg. 100-1000) to sell"]}
           (core/omega s0
                       (assoc base-event :event "sell"))))

    (is (= {:reply ["Enter exact amount or range (eg. 100-1000) to buy"]}
           (core/omega s0
                       (assoc base-event :event "buy"))))
    (is (= {:reply ["Order canceled succesfully"]}
           (core/omega s0
                       (assoc base-event :event "cancel"))))
    (is (= {:error "Invalid transition, no effect"}
           (core/omega s0
                       (assoc base-event :event "invalid"))))))

(deftest omega-waitingNewOrderAmount-effects

  (testing "waitingNewOrderAmount transition effects"
    (is (= {:deleteMessage [{:chat "current"
                             :id   1111}]
            :reply         ["Enter address to receive funds"]}
           (core/omega (transit-events s0 [(assoc base-event :event "buy")
                                           {:event "setLastMessageId"
                                            :data  {:messageId 1111}}])
                       {:event "setAmount"
                        :data  {:text "100-1000"}})))
    (is (= {:deleteMessage [{:chat "current"
                             :id   1111}]
            :reply         ["Enter address for refund (if necessary)"]}
           (core/omega (transit-events s0 [(assoc base-event :event "sell")
                                           {:event "setLastMessageId"
                                            :data  {:messageId 1111}}])
                       {:event "setAmount"
                        :data  {:text "100-1000"}})))))

(deftest omega-waitingSetAddress-effects

  (testing "waitingSetAddress-effects transition effects"
    (is (= {:reply            ["Order created: orderId123"]
            :deleteMessage    [{:chat "current"
                                :id   1112}]
            :orderBookMessage [{:text     "Buying 100"
                                :keyboard {:text     "Take Order"
                                           :callback "take:orderId123"}}]}
           (core/omega (transit-events s0 [(assoc base-event :event "buy")
                                           {:event "setLastMessageId"
                                            :data  {:messageId 1111}}
                                           {:event "setAmount"
                                            :data  {:text      "100"
                                                    :messageId 1111}}
                                           {:event "setLastMessageId"
                                            :data  {:messageId 1112}}])
                       {:event "setAddress"
                        :data  {:text "0x1231231213"
                                :user user}})))
    (is (= {:reply            ["Order created: orderId123"]
            :deleteMessage    [{:chat "current"
                                :id   1112}]
            :orderBookMessage [{:text     "Selling 100"
                                :keyboard {:text     "Take Order"
                                           :callback "take:orderId123"}}]}
           (core/omega (transit-events s0 [(assoc base-event :event "sell")
                                           {:event "setLastMessageId"
                                            :data  {:messageId 1111}}
                                           {:event "setAmount"
                                            :data  {:text      "100"
                                                    :messageId 1111}}
                                           {:event "setLastMessageId"
                                            :data  {:messageId 1112}}])
                       {:event "setAddress"
                        :data  {:text "0x1231231213"
                                :user user}})))))

(deftest omega-watingCounterpart-effects

  (testing "watingCounterpart effects, take order"
    
    (is (= {:deleteMessage [{:chat "ctx"}]
            :answerCbQuery true
            :sendMessage   [{:chatId 987654
                             :text   "Order #orderId123 has been taken. Waiting for the counterpart to enter details"}
                            {:chatId 56789
                             :text   "Please enter the wallet where you want to receive the funds"}]}
           (core/omega (transit-events s0 [(assoc base-event :event "sell")
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
                       {:event "takeOrder"
                        :data  {:counterpart counterpart
                                :takerChatId 56789}})))
    
    (is (= {:deleteMessage [{:chat "ctx"}]
            :answerCbQuery true
            :sendMessage   [{:chatId 987654
                             :text   "Order #orderId123 has been taken. Waiting for the counterpart to enter details"}
                            {:chatId 56789
                             :text   "Enter address for refund (if necessary)"}]}
           (core/omega (transit-events s0 [(assoc base-event :event "buy")
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
                       {:event "takeOrder"
                        :data  {:counterpart counterpart
                                :takerChatId 56789}}))))

  (testing "watingCounterpart effects, take order range"
    
    (is (= {:deleteMessage [{:chat "ctx"}]
            :answerCbQuery true
            :sendMessage   [{:chatId 987654
                             :text   "Order #orderId123 has been taken. Waiting for the counterpart to enter details"}
                            {:chatId 56789
                             :text   "Select an amount between 100-1000"}]}
           (core/omega (transit-events s0 [(assoc base-event :event "sell")
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
                       {:event "takeOrderRange"
                        :data  {:counterpart counterpart
                                :takerChatId 56789}})))
    
    (is (= {:deleteMessage [{:chat "ctx"}]
            :answerCbQuery true
            :sendMessage   [{:chatId 987654
                             :text   "Order #orderId123 has been taken. Waiting for the counterpart to enter details"}
                            {:chatId 56789
                             :text   "Select an amount between 100-1000"}]}
           (core/omega (transit-events s0 [(assoc base-event :event "buy")
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
                       {:event "takeOrderRange"
                        :data  {:counterpart counterpart
                                :takerChatId 56789}})))))

(deftest delta-cancel
  (testing "cancel from s0"
    (let [state {:state "s0"}
          event {:event "cancel"}
          new-state (core/delta state event)]
      (is (= "canceled" (:state new-state)))
      (is (= "canceled" (:status new-state))))))
