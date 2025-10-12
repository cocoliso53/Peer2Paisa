(ns fsm.core
  (:require ["fs" :as fs]
            ["path" :as path]))

;; State helpers

(defn set-new-state
  [m new-state]
  (if (:error m)
    m
    (assoc m :state new-state)))

(defn range-amount?
  [input]
  (when (string? input)
    (boolean (re-matches #"\d+-\d+" input))))

(defn number-or-range?
  [s]
  (when (string? s)
    (boolean (re-matches #"\d+(-\d+)?" s))))

(defn set-original-buyer-or-seller
  [state {{:keys [username messageId]} :data event :event}]
  (if username
    (-> state
        (assoc
         ({"buy" :buyer "sell" :seller} event) {:username username}
         :participants [username]
         :sell (= event "sell")
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
  [{sell :sell :as state} {{:keys [text user messageId]} :data}]
  ;; need to add address validation
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
  [{amount :amount :as state} {{text :text} :data}]
  ;; TODO: we would need to check if text is a valid amount
  ;; and if it is an amount within the defined amount range
  (assoc state :amount text))

(defn set-counterpart-address
  [{:keys [buyer seller sell] :as state} {{:keys [counterpart]} :data}]
  ;; would need to check if address is valid
  (let [state-counterpart (if sell buyer seller)]
    ;; extra check to confirm we are only adding the address
    (if (= state-counterpart
           (dissoc counterpart :address))
      (assoc state (if sell :buyer :seller) counterpart)
      (assoc state :error "counterpart does not match previous data"))))

(defn set-escrow-address
  [state {{escrow :escrow} :data}]
  (assoc state :escrowAddress escrow))

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
  (let [msg (str "Enter exact amount or range (eg. 100-1000) to " event)]
    {:reply [msg]}))

(defn effect-simple-cancel
  [_ _]
  {:reply ["Order canceled succesfully"]})

(defn effect-set-amount
  [{lastMessageId :lastMessageId sell :sell} _]
  (if lastMessageId
    {:deleteMessage [{:chat "current" :id lastMessageId}]
     :reply         [(if sell
                       "Enter address for refund (if necessary)"
                       "Enter address to receive funds")]}
    {:error "No lastMessageId"}))

(defn effect-set-address
  [{:keys [sell amount orderId lastMessageId]} {{user :user text :text} :data}]
  ;; we need to add a check for a valid address
  {:reply            [(str "Order created: " orderId)]
   :deleteMessage    [{:chat "current" :id lastMessageId}]
   :orderBookMessage [{:text     (str (if sell "Selling " "Buying ") amount)
                       :keyboard {:text     "Take Order"
                                  :callback (str "take:" orderId)}}]})

(defn effect-take-order
  [{:keys [sell range amount buyer seller orderId]} {{:keys [takerChatId]} :data}]
  (let [taker-message (if range
                        (str "Select an amount between " amount)
                        (if sell
                          "Please enter the wallet where you want to receive the funds"
                          "Enter address for refund (if necessary)"))]
    {:deleteMessage [{:chat "ctx"}]
     :answerCbQuery true
     :sendMessage [{:chatId (:chatId (if sell seller buyer))
                    :text (str "Order #" orderId
                               " has been taken. Waiting for the counter"
                               "part to enter details")}
                   {:chatId takerChatId
                    :text taker-message}]}))

(defn effect-set-counterpart-amount
  [{sell :sell :as state} _]
  {:reply [(if sell
             "Please enter the wallet where you want to the funds"
             "Enter address for refund (if necessary)")]})

(defn set-counterpart-address-effect
  [{:keys [buyer seller sell amount]} {{:keys [counterpart]} :data}]
  (let [[buyer-address
         seller-address] (if sell
                           [(:address counterpart) (:address seller)]
                           [(:address buyer) (:address counterpart)])]
    {:createEscrow {:buyerAddress  buyer-address
                    :sellerAddress seller-address
                    :amount        amount}}))

(defn set-escrow-address-effect
  [{:keys [buyer seller amount]} {{:keys [escrow escrowUrl]} :data}]
  (let [escrow-url-text (str "Escrow created, click [here]("
                             escrowUrl
                             ") to see the details. ")]
    {:sendMessage [{:chatId    (:chatId buyer)
                    :parseMode "Markdown"
                    :text      (str escrow-url-text
                                    "Wating for seller to fund escrow contract")}
                   {:chatId    (:chatId seller)
                    :parseMode "Markdown"
                    ;; TODO: set currency later on
                    :text      (str escrow-url-text
                                    "Deposit "
                                    amount
                                    " and then type /funded to continue")}
                   {:chatId (:chatId seller)
                    :text   escrow}]}))

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
                         :to         "waitingSetAddress"}
   ["waitingNewOrderAmount"
    "setLastMessageId"] {:transition set-last-message-id
                         :effects    no-effect
                         :to         "waitingNewOrderAmount"}
   
   ["waitingNewOrderAmount"
    "cancel"]           {:transition cancel-order
                         :effects    effect-simple-cancel
                         :to         "canceled"}
   ["waitingSetAddress"
    "setAddress"]       {:transition set-owner-user-data
                         :effects    effect-set-address
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
   
   ["watingCounterpartAddress"
    "setCounterpartAddress"] {:transition set-counterpart-address
                              :effects    set-counterpart-address-effect
                              :to         "waitingFunding"}
   
   ["waitingFunding"
    "setEscrowAddress"] {:transition set-escrow-address
                         :effects    set-escrow-address-effect
                         :to         "waitingFunding"}

   ["waitingFunding"
    "escrowFunded"]  {:transition identity
                      :effects    escrow-funded-effect
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
