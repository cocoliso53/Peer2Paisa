(ns fsm.core-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [fsm.core :as core]))

(def username "username")
(def maker-user {:username "username"
                 :chatId   987654
                 :address  "0x987654"})
(def taker-summary {:username "counterpart"
                    :chatId   56789})
(def taker-user {:username "counterpart"
                 :chatId   56789
                 :address  "0x123456"})
(def s0 {:state   "s0"
         :status  "active"
         :orderId "orderId123"})
(def quote-event {:event "quoteLocked"
                  :data  {:marketRate "18.00"
                          :lockedRate "18.45"
                          :lockedFiatAmount "4621.72"
                          :lockedTokenAmount "250.500000"}})
(def escrow-created-event {:event "escrowCreated"
                           :data  {:escrow "0xescrow"
                                   :escrowUrl "https://basescan.org/address/0xescrow"
                                   :marketRate "18.00"
                                   :lockedRate "18.45"
                                   :lockedFiatAmount "4621.72"
                                   :lockedTokenAmount "250.500000"}})

(defn transit-events
  [init-state events]
  (reduce core/delta init-state events))

(defn order-state
  [side amount premium]
  (transit-events
   s0
   [{:event side
     :data  {:username username
             :messageId 1001}}
    {:event "setAmount"
     :data  {:text amount
             :messageId 1002}}
    {:event "setPremium"
     :data  {:text premium
             :messageId 1003}}
    {:event "setAddress"
     :data  {:user maker-user
             :messageId 1004}}
    {:event "setMessagesIds"
     :data  {:messageId 1005
             :orderMessageId 501}}]))

(defn taken-order-state
  [side amount premium]
  (transit-events
   (order-state side amount premium)
   [{:event "takeOrder"
     :data  {:counterpart taker-summary
             :takerChatId 56789}}]))

(defn taken-range-order-state
  [side amount premium selected-amount]
  (transit-events
   (order-state side amount premium)
   [{:event "takeOrderRange"
     :data  {:counterpart taker-summary
             :takerChatId 56789}}
    {:event "setAmountCounterpart"
     :data  {:text selected-amount}}]))

(defn counterpart-address-state
  [side amount premium]
  (transit-events
   (taken-order-state side amount premium)
   [{:event "setCounterpartAddress"
     :data  {:counterpart taker-user}}]))

(defn waiting-funding-state
  [side amount premium]
  (core/delta (counterpart-address-state side amount premium) escrow-created-event))

(defn waiting-release-state
  [side amount premium]
  (core/delta
   (waiting-funding-state side amount premium)
   {:event "escrowFunded"}))

(deftest s0-transitions
  (testing "buy and sell initialize the correct maker side"
    (let [sell-event {:event "sell"
                      :data  {:username username
                              :messageId 1234}}
          buy-event  {:event "buy"
                      :data  {:username username
                              :messageId 1234}}]
      (is (= {:state             "waitingNewOrderAmount"
              :status            "active"
              :orderId           "orderId123"
              :seller            {:username "username"}
              :participants      ["username"]
              :sell              true
              :asset             "USDC"
              :makerDenomination "USDC"
              :lastMessageId     1234}
             (core/delta s0 sell-event)))
      (is (= {:state             "waitingNewOrderAmount"
              :status            "active"
              :orderId           "orderId123"
              :buyer             {:username "username"}
              :participants      ["username"]
              :sell              false
              :asset             "USDC"
              :makerDenomination "MXN"
              :lastMessageId     1234}
             (core/delta s0 buy-event)))
      (is (= {:reply ["Enter exact USDC amount or range to sell (eg. 100 or 100-1000)"]}
             (core/omega s0 sell-event)))
      (is (= {:reply ["Enter exact MXN amount or range to buy with (eg. 1800 or 1800-18000)"]}
             (core/omega s0 buy-event)))))
  (testing "setLastMessageId and cancel are idempotent from s0"
    (is (= {:state         "s0"
            :status        "active"
            :orderId       "orderId123"
            :lastMessageId 2222}
           (core/delta s0 {:event "setLastMessageId"
                           :data  {:messageId 2222}})))
    (is (= {:no-effect true}
           (core/omega s0 {:event "setLastMessageId"
                           :data  {:messageId 2222}})))
    (is (= {:state   "canceled"
            :status  "canceled"
            :orderId "orderId123"}
           (core/delta s0 {:event "cancel"})))
    (is (= {:reply ["Order canceled succesfully"]}
           (core/omega s0 {:event "cancel"}))))
  (testing "invalid transitions surface explicit errors"
    (is (= {:state   "s0"
            :status  "active"
            :orderId "orderId123"
            :error   "Invalid transition"}
           (core/delta s0 {:event "invalid"})))
    (is (= {:error "Invalid transition, no effect"}
           (core/omega s0 {:event "invalid"})))))

(deftest waiting-new-order-amount-transitions
  (let [sell-state (core/delta s0 {:event "sell"
                                   :data  {:username username
                                           :messageId 1001}})
        valid-event {:event "setAmount"
                     :data  {:text "100.5"
                             :messageId 1002}}
        valid-text-input {:event "textInput"
                          :data  {:text "100-1000"}}
        invalid-text-input {:event "textInput"
                            :data  {:text "bad-input"}}
        set-last-message-event {:event "setLastMessageId"
                                :data  {:messageId 3333}}]
    (testing "direct amount setting moves to premium"
      (is (= {:state         "waitingPremium"
              :status        "active"
              :orderId       "orderId123"
              :seller        {:username "username"}
              :participants  ["username"]
              :sell          true
              :asset         "USDC"
              :makerDenomination "USDC"
              :amount        "100.5"
              :range         false
              :lastMessageId 1002}
             (core/delta sell-state valid-event)))
      (is (= {:deleteMessage [{:chat "current" :id 1001}]
              :reply ["Enter premium percentage over market (eg. 2.5 or -1)"]}
             (core/omega sell-state valid-event))))
    (testing "text input amount accepts ranges and rejects invalid values"
      (is (= "waitingPremium" (:state (core/delta sell-state valid-text-input))))
      (is (= "Invalid range or number"
             (:error (core/delta sell-state invalid-text-input))))
      (is (= {:reply ["Invalid range or number"]}
             (core/omega sell-state invalid-text-input))))
    (testing "auxiliary transitions work while collecting amount"
      (is (= {:state         "waitingNewOrderAmount"
              :status        "active"
              :orderId       "orderId123"
              :seller        {:username "username"}
              :participants  ["username"]
              :sell          true
              :asset         "USDC"
              :makerDenomination "USDC"
              :lastMessageId 3333}
             (core/delta sell-state set-last-message-event)))
      (is (= {:no-effect true}
             (core/omega sell-state set-last-message-event)))
      (is (= "canceled" (:state (core/delta sell-state {:event "cancel"}))))
      (is (= {:reply ["Order canceled succesfully"]}
             (core/omega sell-state {:event "cancel"}))))))

(deftest waiting-premium-transitions
  (let [sell-state (core/delta s0 {:event "sell"
                                   :data  {:username username
                                           :messageId 1001}})
        premium-state (core/delta sell-state {:event "setAmount"
                                              :data  {:text "100"
                                                      :messageId 1002}})
        direct-event {:event "setPremium"
                      :data  {:text "-1.5"
                              :messageId 1003}}
        text-event {:event "textInput"
                    :data  {:text "2.75"}}
        invalid-event {:event "textInput"
                       :data  {:text "premium"}}]
    (testing "premium can be set through direct events or text input"
      (is (= "waitingSetAddress" (:state (core/delta premium-state direct-event))))
      (is (= "waitingSetAddress" (:state (core/delta premium-state text-event))))
      (is (= {:deleteMessage [{:chat "current" :id 1002}]
              :reply ["Enter address for refund (if necessary)"]}
             (core/omega premium-state direct-event))))
    (testing "invalid premium stays in waitingPremium"
      (is (= "Invalid premium" (:error (core/delta premium-state invalid-event))))
      (is (= {:reply ["Invalid premium percentage, please try again"]}
             (core/omega premium-state invalid-event))))
    (testing "setLastMessageId and cancel are supported"
      (is (= "waitingPremium"
             (:state (core/delta premium-state {:event "setLastMessageId"
                                                :data  {:messageId 3333}}))))
      (is (= {:no-effect true}
             (core/omega premium-state {:event "setLastMessageId"
                                        :data  {:messageId 3333}})))
      (is (= "canceled" (:state (core/delta premium-state {:event "cancel"}))))
      (is (= {:reply ["Order canceled succesfully"]}
             (core/omega premium-state {:event "cancel"}))))))

(deftest waiting-set-address-transitions
  (let [sell-state (transit-events s0 [{:event "sell"
                                        :data  {:username username
                                                :messageId 1001}}
                                       {:event "setAmount"
                                        :data  {:text "100"
                                                :messageId 1002}}
                                       {:event "setPremium"
                                        :data  {:text "2.5"
                                                :messageId 1003}}])
        buy-state (transit-events s0 [{:event "buy"
                                       :data  {:username username
                                               :messageId 1001}}
                                      {:event "setAmount"
                                       :data  {:text "1800"
                                               :messageId 1002}}
                                      {:event "setPremium"
                                       :data  {:text "-1"
                                               :messageId 1003}}])
        direct-address {:event "setAddress"
                        :data  {:user maker-user
                                :messageId 1004}}
        valid-text-input {:event "textInput"
                          :data  {:text "0x987654"
                                  :user maker-user}}
        invalid-text-input {:event "textInput"
                            :data  {:text "invalid-address"
                                    :user maker-user}}]
    (testing "sell and buy listings are rendered with the correct denominations"
      (is (= "watingCounterpart" (:state (core/delta sell-state direct-address))))
      (is (= {:reply ["Order created: orderId123"]
              :deleteMessage [{:chat "current" :id 1003}]
              :orderBookMessage [{:text "Selling 100 USDC at +2.5% premium"
                                  :keyboard {:text "Take Order"
                                             :callback "take:orderId123"}}]}
             (core/omega sell-state direct-address)))
      (is (= {:reply ["Order created: orderId123"]
              :deleteMessage [{:chat "current" :id 1003}]
              :orderBookMessage [{:text "Buying USDC with 1800 MXN at -1% premium"
                                  :keyboard {:text "Take Order"
                                             :callback "take:orderId123"}}]}
             (core/omega buy-state direct-address))))
    (testing "text input accepts addresses and rejects invalid ones"
      (is (= "watingCounterpart" (:state (core/delta buy-state valid-text-input))))
      (is (= "Invalid address" (:error (core/delta sell-state invalid-text-input))))
      (is (= {:reply ["Invalid address, please try again"]}
             (core/omega sell-state invalid-text-input))))
    (testing "setLastMessageId and cancel are supported"
      (is (= "waitingSetAddress"
             (:state (core/delta sell-state {:event "setLastMessageId"
                                             :data  {:messageId 4444}}))))
      (is (= {:no-effect true}
             (core/omega sell-state {:event "setLastMessageId"
                                     :data  {:messageId 4444}})))
      (is (= "canceled" (:state (core/delta sell-state {:event "cancel"}))))
      (is (= {:reply ["Order canceled succesfully"]}
             (core/omega sell-state {:event "cancel"}))))))

(deftest waiting-counterpart-transitions
  (let [sell-state (order-state "sell" "100" "2.5")
        buy-state (order-state "buy" "1800" "-1")
        sell-range-state (order-state "sell" "100-1000" "2.5")
        buy-range-state (order-state "buy" "1800-18000" "-1")
        set-messages-event {:event "setMessagesIds"
                            :data  {:messageId 1111
                                    :orderMessageId 999}}
        take-event {:event "takeOrder"
                    :data  {:counterpart taker-summary
                            :takerChatId 56789}}
        take-range-event {:event "takeOrderRange"
                          :data  {:counterpart taker-summary
                                  :takerChatId 56789}}]
    (testing "setMessagesIds updates the orderbook reference"
      (is (= {:state             "watingCounterpart"
              :status            "active"
              :orderId           "orderId123"
              :seller            maker-user
              :participants      ["username"]
              :sell              true
              :asset             "USDC"
              :makerDenomination "USDC"
              :amount            "100"
              :premiumPct        "2.5"
              :range             false
              :lastMessageId     1111
              :orderMessageId    999}
             (core/delta sell-state set-messages-event)))
      (is (= {:no-effect true}
             (core/omega sell-state set-messages-event))))
    (testing "non-range take flow prompts the taker correctly for sell and buy orders"
      (is (= "watingCounterpartAddress" (:state (core/delta sell-state take-event))))
      (is (= {:deleteMessage [{:chat "ctx"}]
              :answerQuery true
              :sendMessage [{:chatId 987654
                             :text "Order #orderId123 has been taken. Waiting for the counterpart to enter details"}
                            {:chatId 56789
                             :text "Please enter the wallet where you want to receive the funds"}]}
             (core/omega sell-state take-event)))
      (is (= {:deleteMessage [{:chat "ctx"}]
              :answerQuery true
              :sendMessage [{:chatId 987654
                             :text "Order #orderId123 has been taken. Waiting for the counterpart to enter details"}
                            {:chatId 56789
                             :text "Enter address for refund (if necessary)"}]}
             (core/omega buy-state take-event))))
    (testing "range take flow asks for an amount in the maker denomination"
      (is (= "watingCounterpartSetAmount" (:state (core/delta sell-range-state take-range-event))))
      (is (= {:deleteMessage [{:chat "ctx"}]
              :answerQuery true
              :sendMessage [{:chatId 987654
                             :text "Order #orderId123 has been taken. Waiting for the counterpart to enter details"}
                            {:chatId 56789
                             :text "Select an amount between 100-1000 USDC"}]}
             (core/omega sell-range-state take-range-event)))
      (is (= {:deleteMessage [{:chat "ctx"}]
              :answerQuery true
              :sendMessage [{:chatId 987654
                             :text "Order #orderId123 has been taken. Waiting for the counterpart to enter details"}
                            {:chatId 56789
                             :text "Select an amount between 1800-18000 MXN"}]}
             (core/omega buy-range-state take-range-event))))
    (testing "open orders can still be canceled from the orderbook state"
      (is (= "canceled" (:state (core/delta sell-state {:event "cancel"}))))
      (is (= {:reply ["Order canceled succesfully"]
              :deleteMessage [{:chat "channel" :id 501}]}
             (core/omega sell-state {:event "cancel"}))))))

(deftest waiting-counterpart-set-amount-transitions
  (let [sell-state (core/delta (order-state "sell" "100-1000" "2.5")
                               {:event "takeOrderRange"
                                :data  {:counterpart taker-summary
                                        :takerChatId 56789}})
        buy-state (core/delta (order-state "buy" "1800-18000" "-1")
                              {:event "takeOrderRange"
                               :data  {:counterpart taker-summary
                                       :takerChatId 56789}})
        direct-event {:event "setAmountCounterpart"
                      :data  {:text "250.5"}}
        valid-text-input {:event "textInput"
                          :data  {:text "5000"}}
        invalid-text-input {:event "textInput"
                            :data  {:text "99999"}}]
    (testing "direct and text amount selection advance to counterpart address"
      (is (= "watingCounterpartAddress" (:state (core/delta sell-state direct-event))))
      (is (= {:reply ["Please enter the wallet where you want to receive the funds"]}
             (core/omega sell-state direct-event)))
      (is (= "watingCounterpartAddress" (:state (core/delta buy-state valid-text-input))))
      (is (= {:reply ["Enter address for refund (if necessary)"]}
             (core/omega buy-state valid-text-input))))
    (testing "invalid range amount is rejected"
      (is (= "Invalid amount" (:error (core/delta buy-state invalid-text-input))))
      (is (= {:reply ["Invalid amount, please try again"]}
             (core/omega buy-state invalid-text-input))))
    (testing "cancel notifies the maker once a taker exists"
      (is (= "canceled" (:state (core/delta sell-state {:event "cancel"
                                                        :data  {:username "counterpart"}}))))
      (is (= {:reply ["Order canceled succesfully"]
              :sendMessage [{:chatId 987654
                             :text "Buyer canceled the order"}]}
             (core/omega sell-state {:event "cancel"
                                     :data  {:username "counterpart"}}))))))

(deftest waiting-counterpart-address-transitions
  (let [sell-state (taken-order-state "sell" "100" "2.5")
        buy-state (taken-order-state "buy" "1800" "-1")
        range-state (taken-range-order-state "sell" "100-1000" "2.5" "250.5")
        direct-event {:event "setCounterpartAddress"
                      :data  {:counterpart taker-user}}
        text-event {:event "textInput"
                    :data  {:text "0x123456"
                            :counterpart taker-user}}
        invalid-text-event {:event "textInput"
                            :data  {:text "invalid-address"
                                    :counterpart taker-summary}}
        mismatch-event {:event "setCounterpartAddress"
                        :data  {:counterpart {:username "otherguy"
                                              :chatId   56789
                                              :address  "0x999999"}}}]
    (testing "valid counterpart addresses request a quote lock"
      (is (= "watingCounterpartAddress" (:state (core/delta sell-state direct-event))))
      (is (= {:lockQuote {:amount "100"
                          :premiumPct "2.5"
                          :sell true
                          :makerDenomination "USDC"
                          :asset "USDC"}}
             (core/omega sell-state direct-event)))
      (is (= "watingCounterpartAddress" (:state (core/delta buy-state text-event))))
      (is (= {:lockQuote {:amount "1800"
                          :premiumPct "-1"
                          :sell false
                          :makerDenomination "MXN"
                          :asset "USDC"}}
             (core/omega buy-state text-event)))
      (is (= {:lockQuote {:amount "250.5"
                          :premiumPct "2.5"
                          :sell true
                          :makerDenomination "USDC"
                          :asset "USDC"}}
             (core/omega range-state direct-event))))
    (testing "invalid addresses and counterpart mismatches are rejected"
      (is (= "Invalid address" (:error (core/delta buy-state invalid-text-event))))
      (is (= {:reply ["Invalid address, please try again"]}
             (core/omega buy-state invalid-text-event)))
      (is (= "counterpart does not match previous data"
             (:error (core/delta sell-state mismatch-event))))
      (is (= {:error "counterpart does not match previous data"}
             (core/omega sell-state mismatch-event))))
    (testing "cancel notifies the existing counterpart"
      (is (= "canceled" (:state (core/delta sell-state {:event "cancel"
                                                        :data  {:username username}}))))
      (is (= {:reply ["Order canceled succesfully"]
              :sendMessage [{:chatId 56789
                             :text "Seller canceled the order"}]}
             (core/omega sell-state {:event "cancel"
                                     :data  {:username username}}))))))

(deftest waiting-funding-transitions
  (let [pre-funding-sell-state (counterpart-address-state "sell" "100" "2.5")
        pre-funding-buy-state (counterpart-address-state "buy" "1800" "-1")
        with-escrow-state (waiting-funding-state "sell" "100" "2.5")
        buy-with-escrow-state (waiting-funding-state "buy" "1800" "-1")
        no-seller-chat-state (assoc with-escrow-state :seller {:username "username"
                                                               :address  "0x987654"})]
    (testing "quote and escrow failures stay before waitingFunding"
      (is (= "watingCounterpartAddress" (:state pre-funding-sell-state)))
      (is (= pre-funding-sell-state
             (core/delta pre-funding-sell-state {:event "quoteLockFailed"})))
      (is (= {:reply ["Unable to lock the quote right now. Please try again later."]}
             (core/omega pre-funding-sell-state {:event "quoteLockFailed"})))
      (is (= pre-funding-sell-state
             (core/delta pre-funding-sell-state {:event "escrowCreationFailed"})))
      (is (= {:reply ["Sorry, we couldn't create the escrow contract. Please contact support."]}
             (core/omega pre-funding-sell-state {:event "escrowCreationFailed"}))))
    (testing "escrow creation stores quote data and advances to waitingFunding"
      (is (= "waitingFunding" (:state with-escrow-state)))
      (is (= "0xescrow" (:escrowAddress with-escrow-state)))
      (is (= "18.45" (:lockedRate with-escrow-state)))
      (is (= "4621.72" (:lockedFiatAmount with-escrow-state)))
      (is (= "250.500000" (:lockedTokenAmount with-escrow-state)))
      (is (= {:sendMessage [{:chatId 56789
                             :parseMode "Markdown"
                             :text "Escrow created, click [here](https://basescan.org/address/0xescrow) to see the details. Locked rate: 18.45 MXN/USDC. Seller deposits 250.500000 USDC. Buyer pays 4621.72 MXN. Waiting for seller to fund escrow contract"}
                            {:chatId 987654
                             :parseMode "Markdown"
                             :text "Escrow created, click [here](https://basescan.org/address/0xescrow) to see the details. Locked rate: 18.45 MXN/USDC. Seller deposits 250.500000 USDC. Buyer pays 4621.72 MXN. Deposit 250.500000 USDC and then type /funded to continue"}
                            {:chatId 987654
                             :text "0xescrow"}]}
             (core/omega pre-funding-sell-state escrow-created-event))))
    (testing "escrow creation failures and funding failures reply appropriately"
      (is (= with-escrow-state
             (core/delta with-escrow-state {:event "escrowFundingFailed"})))
      (is (= {:sendMessage [{:chatId 987654
                             :text "Please confirm you deposited 250.500000 USDC in the escrow address 0xescrow"}]}
             (core/omega with-escrow-state {:event "escrowFundingFailed"})))
      (is (= {:reply ["Unable to verify the escrow funding transaction. Please try again."]}
             (core/omega no-seller-chat-state {:event "escrowFundingFailed"}))))
    (testing "escrow funded transitions to waitingRelease for both sides"
      (is (= {:state  "waitingRelease"
              :status "active"}
             (select-keys (core/delta with-escrow-state {:event "escrowFunded"}) [:state :status])))
      (is (= {:sendMessage [{:chatId 987654
                             :text "Escrow funded succesfully. Contact @counterpart to agree on fiat transfer details. Type /release ONLY after you have confirmed the reception of funds"}
                            {:chatId 56789
                             :text "Seller has funded the escrow account. Contact @username to agree on fiat transfer details"}]}
             (core/omega with-escrow-state {:event "escrowFunded"})))
      (is (= {:sendMessage [{:chatId 56789
                             :text "Escrow funded succesfully. Contact @username to agree on fiat transfer details. Type /release ONLY after you have confirmed the reception of funds"}
                            {:chatId 987654
                             :text "Seller has funded the escrow account. Contact @counterpart to agree on fiat transfer details"}]}
             (core/omega buy-with-escrow-state {:event "escrowFunded"}))))
    (testing "cancel while waiting funding requests an escrow refund when available"
      (let [state-with-escrow (assoc with-escrow-state :escrowAddress "0xescrow")]
        (is (= "canceled" (:state (core/delta state-with-escrow {:event "cancel"
                                                                 :data  {:username username}}))))
        (is (= {:reply ["Order canceled succesfully"]
                :sendMessage [{:chatId 56789
                               :text "Seller canceled the order"}]
                :cancelEscrow {:escrowAddress "0xescrow"
                               :markAsFundedFirst true}}
               (core/omega state-with-escrow {:event "cancel"
                                              :data  {:username username}})))))))

(deftest waiting-release-transitions
  (let [sell-release-state (waiting-release-state "sell" "100" "2.5")
        buy-release-state (waiting-release-state "buy" "1800" "-1")
        release-failed-event {:event "releaseFailed"}
        complete-event {:event "fundsReleased"
                        :data  {:explorerUrl "https://basescan.org/tx/0xtx"}}]
    (testing "release failures stay in waitingRelease and notify both parties"
      (is (= sell-release-state
             (core/delta sell-release-state release-failed-event)))
      (is (= {:sendMessage [{:chatId 987654
                             :text "There was an error releasing the funds. Please try again or contact support"}
                            {:chatId 56789
                             :text "There was an error releasing the funds. Wait for the seller to release again or contact support"}]}
             (core/omega sell-release-state release-failed-event))))
    (testing "successful release completes the order"
      (is (= {:state  "finished"
              :status "done"}
             (select-keys (core/delta sell-release-state complete-event) [:state :status])))
      (is (= {:sendMessage [{:chatId 56789
                             :text "Transaction [completed](https://basescan.org/tx/0xtx)! Thanks for using our bot!"
                             :parseMode "Markdown"}
                            {:chatId 987654
                             :text "Transaction [completed](https://basescan.org/tx/0xtx)! Thanks for using our bot!"
                             :parseMode "Markdown"}]}
             (core/omega sell-release-state complete-event))))
    (testing "cancel while waitingRelease requests a direct refund path"
      (is (= "canceled" (:state (core/delta buy-release-state {:event "cancel"
                                                               :data  {:username username}}))))
      (is (= {:reply ["Order canceled succesfully"]
              :sendMessage [{:chatId 56789
                             :text "Buyer canceled the order"}]
              :cancelEscrow {:escrowAddress "0xescrow"
                             :markAsFundedFirst false}}
             (core/omega buy-release-state {:event "cancel"
                                            :data  {:username username}}))))))
