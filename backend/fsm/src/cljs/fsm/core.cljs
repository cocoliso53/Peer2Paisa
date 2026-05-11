(ns fsm.core)

;; State helpers

(defn set-new-state
  [m new-state]
  (if (:error m)
    m
    (assoc m :state new-state)))

(def decimal-pattern-string "\\d+(?:\\.\\d+)?")
(def decimal-pattern (re-pattern decimal-pattern-string))

(defn range-amount?
  [input]
  (when (string? input)
    (boolean (re-matches (re-pattern (str decimal-pattern-string "-" decimal-pattern-string)) input))))

(defn decimal-number?
  [s]
  (when (string? s)
    (boolean (re-matches decimal-pattern s))))

(defn number-or-range?
  [s]
  (when (string? s)
    (or (decimal-number? s)
        (range-amount? s))))

(defn signed-decimal?
  [s]
  (when (string? s)
    (boolean (re-matches #"[+-]?\d+(?:\.\d+)?" s))))

(defn address-like?
  [s]
  (when (string? s)
    (boolean (re-matches #"0x[a-fA-F0-9]+" s))))

(defn parse-number
  [value]
  (when (decimal-number? value)
    (js/parseFloat value)))

(defn parse-range
  [value]
  (when (range-amount? value)
    (let [[from to] (.split value "-")]
      [(parse-number from) (parse-number to)])))

(defn amount-in-range?
  [range-input amount-input]
  (let [[from to] (parse-range range-input)
        amount    (parse-number amount-input)]
    (and from to amount (<= from amount to))))

(defn format-premium
  [premium]
  (if (and (string? premium) (not (.startsWith premium "-")) (not (.startsWith premium "+")))
    (str "+" premium)
    premium))

(defn amount-unit-label
  [{:keys [makerDenomination]}]
  (or makerDenomination "USDC"))

(defn order-book-text
  [{:keys [sell amount makerDenomination premiumPct asset]}]
  (if sell
    (str "Selling " amount " " makerDenomination " at " (format-premium premiumPct) "% premium")
    (str "Buying " asset " with " amount " " makerDenomination " at " (format-premium premiumPct) "% premium")))

(defn locked-trade-summary
  [{:keys [lockedRate lockedFiatAmount lockedTokenAmount asset]}]
  (str "Locked rate: " lockedRate " MXN/" asset ". "
       "Seller deposits " lockedTokenAmount " " asset ". "
       "Buyer pays " lockedFiatAmount " MXN. "))

(defn set-original-buyer-or-seller
  [state {{:keys [username messageId]} :data event :event}]
  (if username
    (-> state
        (assoc
         ({"buy" :buyer "sell" :seller} event) {:username username}
         :participants [username]
         :sell (= event "sell")
         :asset "USDC"
         :makerDenomination (if (= event "sell") "USDC" "MXN")
         :lastMessageId messageId)
        (dissoc :error))
    (assoc state
           :error "No username")))

(defn set-amount-or-range
  [state {{:keys [text messageId]} :data}]
  (if (number-or-range? text)
    (-> state
        (assoc 
         :range (range-amount? text) ;; Do we need this? 
         :amount text
         :lastMessageId messageId)
        (dissoc :error))
    (assoc state
           :error "Invalid range or number")))

(defn set-owner-user-data
  [{sell :sell :as state} {{:keys [user messageId]} :data}]
  (if user
    (assoc state
           (if sell :seller :buyer) user
           :lastMessageId messageId)
    (assoc state
           :error "No full user data")))

(defn set-last-message-id
  [state {{:keys [messageId]} :data}]
  (apply assoc state
         (if messageId
           [:lastMessageId messageId]
           [:error "No last message"]))) ;; do we want an error where?

(defn set-last-and-orderbook-message-id
  [state {{:keys [messageId orderMessageId]} :data}]
  (apply assoc state
         (if orderMessageId
           [:lastMessageId messageId :orderMessageId orderMessageId]
           [:error "No last message"])))

(defn set-counterpat-username-chatId
  [{:keys [sell participants] :as state} {{:keys [counterpart]} :data}]
  (assoc state
         (if sell :buyer :seller) counterpart
         :participants (conj participants (:username counterpart))))

(defn set-counterpart-amount
  [state {{text :text} :data}]
  (assoc state :amount text))

(defn set-counterpart-amount-from-text
  [{range-input :amount :as state} {{:keys [text]} :data :as event}]
  (if (and (decimal-number? text)
           (amount-in-range? range-input text))
    (set-counterpart-amount state event)
    (assoc state :error "Invalid amount")))

(defn set-premium
  [state {{:keys [text messageId]} :data}]
  (if (signed-decimal? text)
    (-> state
        (assoc :premiumPct text
               :lastMessageId messageId)
        (dissoc :error))
    (assoc state :error "Invalid premium")))

(defn set-owner-user-data-from-text
  [state {{:keys [text]} :data :as event}]
  (if (address-like? text)
    (set-owner-user-data state event)
    (assoc state :error "Invalid address")))

(defn set-counterpart-address
  [{:keys [buyer seller sell] :as state} {{:keys [counterpart]} :data}]
  ;; would need to check if address is valid
  (let [state-counterpart (if sell buyer seller)]
    ;; extra check to confirm we are only adding the address
    (if (= state-counterpart
           (dissoc counterpart :address))
      (assoc state (if sell :buyer :seller) counterpart)
      (assoc state :error "counterpart does not match previous data"))))

(defn set-counterpart-address-from-text
  [state {{:keys [text]} :data :as event}]
  (if (address-like? text)
    (set-counterpart-address state event)
    (assoc state :error "Invalid address")))

(defn set-escrow-address
  [state {{escrow :escrow} :data}]
  (assoc state :escrowAddress escrow))

(defn set-locked-quote
  [state {{:keys [marketRate lockedRate lockedFiatAmount lockedTokenAmount]} :data}]
  (-> state
      (assoc :marketRate marketRate
             :lockedRate lockedRate
             :lockedFiatAmount lockedFiatAmount
             :lockedTokenAmount lockedTokenAmount)
      (dissoc :error)))

(defn complete-order
  [state _]
  (assoc state
         :status "done"))

(defn cancel-order
  [state _]
  (assoc state
         :status "canceled"))
  

;;; Effects helpers ;;; 

(defn no-effect
  [_ _]
  {:no-effect true})

(defn effect-original-buyer-or-seller
  [_ {event :event}]
  (let [msg (if (= event "sell")
              "Enter exact USDC amount or range to sell (eg. 100 or 100-1000)"
              "Enter exact MXN amount or range to buy with (eg. 1800 or 1800-18000)")]
    {:reply [msg]}))

(defn effect-simple-cancel
  [{orderMessageId :orderMessageId} _]
  (merge {:reply ["Order canceled succesfully"]}
         (when orderMessageId
           {:deleteMessage [{:chat "channel" :id orderMessageId}]})))

(defn effect-counterpart-cancel
  [{:keys [buyer seller escrowAddress state]} {{:keys [username]} :data}]
  (let [seller-username (:username seller)
        buyer-username  (:username buyer)
        seller-cancel?  (= username seller-username)
        cancel-label    (if seller-cancel? "Seller" "Buyer")
        counterpart     (if seller-cancel? buyer seller)
        base-effects    {:reply ["Order canceled succesfully"]}
        notify-effects  (when (:chatId counterpart)
                          {:sendMessage [{:chatId (:chatId counterpart)
                                          :text   (str cancel-label " canceled the order")}]} )
        escrow-effects  (when escrowAddress
                          {:cancelEscrow {:escrowAddress      escrowAddress
                                          :markAsFundedFirst (= state "waitingFunding")}})]
    (merge base-effects notify-effects escrow-effects)))

(defn effect-set-amount
  [{lastMessageId :lastMessageId} _]
  (if lastMessageId
    {:deleteMessage [{:chat "current" :id lastMessageId}]
     :reply         ["Enter premium percentage over market (eg. 2.5 or -1)"]}
    {:error "No lastMessageId"}))

(defn effect-text-set-amount
  [state {{:keys [text]} :data :as event}]
  (if (number-or-range? text)
    (effect-set-amount state event)
    {:reply ["Invalid range or number"]}))

(defn effect-set-address
  [{:keys [orderId lastMessageId] :as state} _]
  {:reply            [(str "Order created: " orderId)]
   :deleteMessage    [{:chat "current" :id lastMessageId}]
   :orderBookMessage [{:text     (order-book-text state)
                       :keyboard {:text     "Take Order"
                                  :callback (str "take:" orderId)}}]})

(defn effect-text-set-address
  [state {{:keys [text]} :data :as event}]
  (if (address-like? text)
    (effect-set-address state event)
    {:reply ["Invalid address, please try again"]}))

(defn effect-set-premium
  [{lastMessageId :lastMessageId sell :sell} _]
  (if lastMessageId
    {:deleteMessage [{:chat "current" :id lastMessageId}]
     :reply         [(if sell
                       "Enter address for refund (if necessary)"
                       "Enter address to receive funds")]}
    {:error "No lastMessageId"}))

(defn effect-text-set-premium
  [state {{:keys [text]} :data :as event}]
  (if (signed-decimal? text)
    (effect-set-premium state event)
    {:reply ["Invalid premium percentage, please try again"]}))

(defn effect-take-order
  [{:keys [sell range amount buyer seller orderId] :as state} {{:keys [takerChatId]} :data}]
  (let [taker-message (if range
                        (str "Select an amount between " amount " " (amount-unit-label state))
                        (if sell
                          "Please enter the wallet where you want to receive the funds"
                          "Enter address for refund (if necessary)"))]
    {:deleteMessage [{:chat "ctx"}]
     :answerQuery true
     :sendMessage [{:chatId (:chatId (if sell seller buyer))
                    :text (str "Order #" orderId
                               " has been taken. Waiting for the counter"
                               "part to enter details")}
                   {:chatId takerChatId
                    :text taker-message}]}))

(defn effect-set-counterpart-amount
  [{sell :sell :as state} _]
  {:reply [(if sell
             "Please enter the wallet where you want to receive the funds"
             "Enter address for refund (if necessary)")]})

(defn effect-text-set-counterpart-amount
  [{range-input :amount :as state} {{:keys [text]} :data :as event}]
  (if (and (decimal-number? text)
           (amount-in-range? range-input text))
    (effect-set-counterpart-amount state event)
    {:reply ["Invalid amount, please try again"]}))

(defn set-counterpart-address-effect
  [state event]
  (let [{:keys [amount premiumPct sell makerDenomination asset] :as next-state}
        (set-counterpart-address state event)]
    (if (:error next-state)
      {:error (:error next-state)}
      {:lockQuote {:amount             amount
                   :premiumPct         premiumPct
                   :sell               sell
                   :makerDenomination  makerDenomination
                   :asset              asset}})))

(defn effect-text-set-counterpart-address
  [state {{:keys [text]} :data :as event}]
  (if (address-like? text)
    (set-counterpart-address-effect state event)
    {:reply ["Invalid address, please try again"]}))

(defn set-escrow-address-effect
  [{:keys [buyer seller asset lockedTokenAmount] :as state} {{:keys [escrow escrowUrl]} :data}]
  (let [escrow-url-text (str "Escrow created, click [here]("
                             escrowUrl
                             ") to see the details. ")]
    {:sendMessage [{:chatId    (:chatId buyer)
                    :parseMode "Markdown"
                    :text      (str escrow-url-text
                                    (locked-trade-summary state)
                                    "Waiting for seller to fund escrow contract")}
                   {:chatId    (:chatId seller)
                    :parseMode "Markdown"
                    :text      (str escrow-url-text
                                    (locked-trade-summary state)
                                    "Deposit "
                                    lockedTokenAmount
                                    " "
                                    asset
                                    " and then type /funded to continue")}
                   {:chatId (:chatId seller)
                   :text   escrow}]}))

(defn quote-locked-effect
  [{:keys [buyer seller sell]} {{:keys [lockedTokenAmount]} :data}]
  (let [[buyer-address seller-address] (if sell
                                         [(:address buyer) (:address seller)]
                                         [(:address buyer) (:address seller)])]
    {:createEscrow {:buyerAddress  buyer-address
                    :sellerAddress seller-address
                    :amount        lockedTokenAmount}}))

(defn quote-lock-failed-effect
  [_ _]
  {:reply ["Unable to lock the quote right now. Please try again later."]})

(defn escrow-creation-failed-effect
  [_ _]
  {:reply ["Sorry, we couldn't create the escrow contract. Please contact support."]})

(defn escrow-funded-effect
  [{:keys [buyer seller]} _]
  {:sendMessage [{:chatId (:chatId seller)
                  :text (str "Escrow funded succesfully. Contact @" (:username buyer)
                             " to agree on fiat transfer details. Type /release "
                             "ONLY after you have confirmed the reception of funds")}
                 {:chatId (:chatId buyer)
                  :text (str "Seller has funded the escrow account. Contact @"
                             (:username seller) " to agree on fiat transfer"
                             " details")}]})

(defn escrow-funding-failed-effect
  [{:keys [seller lockedTokenAmount asset escrowAddress]} _]
  (if (:chatId seller)
    {:sendMessage [{:chatId (:chatId seller)
                    :text   (str "Please confirm you deposited "
                                 (or lockedTokenAmount "the agreed")
                                 " "
                                 (or asset "USDC")
                                 " in the escrow address "
                                 escrowAddress)}]}
    {:reply ["Unable to verify the escrow funding transaction. Please try again."]}))

(defn release-failed-effect
  [{buyer :buyer seller :seller} _]
  {:sendMessage (vec (concat
                      (when (:chatId seller)
                        [{:chatId (:chatId seller)
                          :text   "There was an error releasing the funds. Please try again or contact support"}])
                      (when (:chatId buyer)
                        [{:chatId (:chatId buyer)
                          :text   "There was an error releasing the funds. Wait for the seller to release again or contact support"}])))} )

(defn complete-order-effect
  [{buyer :buyer seller :seller} {{explorerUrl :explorerUrl} :data}]
  (let [successText (str "Transaction [completed](" explorerUrl ")! "
                         "Thanks for using our bot!")]
    {:sendMessage [{:chatId    (:chatId buyer)
                    :text      successText
                    :parseMode "Markdown"}
                   {:chatId    (:chatId seller)
                    :text      successText
                    :parseMode "Markdown"}]}))

;; Delta will be ð: SxE -> S
;; if the state and event are not a valid combination we will just return
;; the same state with the :error keyword, we should react to this in ts
;; the proper way (tbd)

(def transition-table
  {["s0" "buy"] {:transition set-original-buyer-or-seller
                 :effects    effect-original-buyer-or-seller
                 :to         "waitingNewOrderAmount"}
   
   ["s0" "sell"]        {:transition set-original-buyer-or-seller
                         :effects    effect-original-buyer-or-seller
                         :to         "waitingNewOrderAmount"}
   ["s0"
    "setLastMessageId"] {:transition set-last-message-id
                         :effects    no-effect
                         :to         "s0"} ;; not sure if we need this one
   ["s0" "cancel"]      {:transition cancel-order
                         :effects    effect-simple-cancel
                         :to         "canceled"}
   ["waitingNewOrderAmount"
    "setAmount"]        {:transition set-amount-or-range
                         :effects    effect-set-amount
                         :to         "waitingPremium"}
   ["waitingNewOrderAmount"
    "textInput"]        {:transition set-amount-or-range
                         :effects    effect-text-set-amount
                         :to         "waitingPremium"}
   ["waitingNewOrderAmount"
    "setLastMessageId"] {:transition set-last-message-id
                         :effects    no-effect
                         :to         "waitingNewOrderAmount"}
   ["waitingNewOrderAmount"
    "cancel"]           {:transition cancel-order
                         :effects    effect-simple-cancel
                         :to         "canceled"}
   ["waitingPremium"
    "setPremium"]       {:transition set-premium
                         :effects    effect-set-premium
                         :to         "waitingSetAddress"}
   ["waitingPremium"
    "textInput"]        {:transition set-premium
                         :effects    effect-text-set-premium
                         :to         "waitingSetAddress"}
   ["waitingPremium"
    "setLastMessageId"] {:transition set-last-message-id
                         :effects    no-effect
                         :to         "waitingPremium"}
   ["waitingPremium"
    "cancel"]           {:transition cancel-order
                         :effects    effect-simple-cancel
                         :to         "canceled"}
   ["waitingSetAddress"
    "setAddress"]       {:transition set-owner-user-data
                         :effects    effect-set-address
                         :to         "watingCounterpart"}
   ["waitingSetAddress"
    "textInput"]        {:transition set-owner-user-data-from-text
                         :effects    effect-text-set-address
                         :to         "watingCounterpart"}
   ["waitingSetAddress"
    "setLastMessageId"] {:transition set-last-message-id
                         :effects    no-effect
                         :to         "waitingSetAddress"}
   ["waitingSetAddress"
    "cancel"]           {:transition cancel-order
                         :effects    effect-simple-cancel
                         :to         "canceled"}
   ;; We might need to come up with a better way to do this transition
   ;; next state should be determined on backend level, not front.

   ;; missing cancel order logic here while from here on
   
   ["watingCounterpart"
    "setMessagesIds"] {:transition set-last-and-orderbook-message-id
                       :effects    no-effect
                       :to         "watingCounterpart"}
   ["watingCounterpart"
    "cancel"]         {:transition cancel-order
                       :effects    effect-simple-cancel
                       :to         "canceled"}
   ["watingCounterpart"
    "takeOrder"]      {:transition set-counterpat-username-chatId
                       :effects    effect-take-order
                       :to         "watingCounterpartAddress"}
   ["watingCounterpart"
    "takeOrderRange"] {:transition set-counterpat-username-chatId
                       :effects    effect-take-order
                       :to         "watingCounterpartSetAmount"}

   ;; This should go first in case it's needed

   ["watingCounterpartSetAmount"
    "setAmountCounterpart"] {:transition set-counterpart-amount
                             :effects    effect-set-counterpart-amount
                             :to         "watingCounterpartAddress"}
   ["watingCounterpartSetAmount"
    "textInput"]            {:transition set-counterpart-amount-from-text
                             :effects    effect-text-set-counterpart-amount
                             :to         "watingCounterpartAddress"}
   ["watingCounterpartSetAmount"
    "cancel"]              {:transition cancel-order
                            :effects    effect-counterpart-cancel
                            :to         "canceled"}
   
   ["watingCounterpartAddress"
    "setCounterpartAddress"] {:transition set-counterpart-address
                              :effects    set-counterpart-address-effect
                              :to         "waitingFunding"}
   ["watingCounterpartAddress"
    "textInput"]             {:transition set-counterpart-address-from-text
                              :effects    effect-text-set-counterpart-address
                              :to         "waitingFunding"}
   ["watingCounterpartAddress"
    "cancel"]                {:transition cancel-order
                              :effects    effect-counterpart-cancel
                              :to         "canceled"}
   
   ["waitingFunding"
    "quoteLocked"]      {:transition set-locked-quote
                         :effects    quote-locked-effect
                         :to         "waitingFunding"}
   ["waitingFunding"
    "quoteLockFailed"]  {:transition identity
                         :effects    quote-lock-failed-effect
                         :to         "waitingFunding"}
   ["waitingFunding"
    "setEscrowAddress"] {:transition set-escrow-address
                         :effects    set-escrow-address-effect
                         :to         "waitingFunding"}
   ["waitingFunding"
    "escrowCreated"]    {:transition set-escrow-address
                         :effects    set-escrow-address-effect
                         :to         "waitingFunding"}
   ["waitingFunding"
    "escrowCreationFailed"] {:transition identity
                             :effects    escrow-creation-failed-effect
                             :to         "waitingFunding"}
   ["waitingFunding"
    "cancel"]           {:transition cancel-order
                         :effects    effect-counterpart-cancel
                         :to         "canceled"}

   ["waitingFunding"
    "escrowFunded"]  {:transition identity
                      :effects    escrow-funded-effect
                      :to         "waitingRelease"}
   ["waitingFunding"
    "escrowFundingFailed"] {:transition identity
                            :effects    escrow-funding-failed-effect
                            :to         "waitingFunding"}
   ["waitingRelease"
    "cancel"]        {:transition cancel-order
                      :effects    effect-counterpart-cancel
                      :to         "canceled"}
   ["waitingRelease"
    "releaseFailed"] {:transition identity
                      :effects    release-failed-effect
                      :to         "waitingRelease"}
   ["waitingRelease"
    "fundsReleased"] {:transition complete-order
                      :effects    complete-order-effect
                      :to         "finished"}})

(defn delta
  [{state :state :as s} {event :event :as e}]
  (let [transition-map (get transition-table [state event])]
    (if transition-map
      (-> ((:transition transition-map) s e)
          (set-new-state (:to transition-map)))
      (assoc s :error "Invalid transition"))))

;; Omega will be ω: SxE -> T
;; where T are effects or instructions
;; to be performed by the bot (eg sending messages, replying, etc)

(defn omega
  [{state :state :as s} {event :event :as e}]
  (let [transition-map (get transition-table [state event])]
    (if transition-map
      ((:effects transition-map) s e)
      {:error "Invalid transition, no effect"}))) ;; confirm this is the effect we want


(defn ^:export delta-wrapped
  [js-state js-event]
  (let [state      (js->clj js-state :keywordize-keys true)
        event      (js->clj js-event :keywordize-keys true)
        next-state (delta state event)
        effect     (omega state event)]
    (clj->js {:state   next-state
              :effects effect})))
