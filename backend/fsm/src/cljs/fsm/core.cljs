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
  [{sell :sell :as state} {{:keys [counterpart]} :data}]
  (assoc state
         (if sell :buyer :seller) counterpart))
           
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

;; Delta will be ð: SxE -> S
;; if the state and event are not a valid combination we will just return
;; the same state with the :error keyword, we should react to this in ts
;; the proper way (tbd)

(def transition-table
  {["s0" "buy"]         {:transition set-original-buyer-or-seller
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
   ["watingCounterpart"
    "takeOrder"]        {:transition set-counterpat-username-chatId
                         :effects    effect-take-order
                         :to         "watingCounterpartAddress"}
   ["watingCounterpart"
    "takeOrderRange"]   {:transition set-counterpat-username-chatId
                         :effects    effect-take-order
                         :to         "watingCounterpartSetAmount"}
   ["watingCounterpart"
    "setMessagesIds"]   {:transition set-last-and-orderbook-message-id
                         :effects    no-effect
                         :to         "watingCounterpart"}
   ["watingCounterpart"
    "cancel"]           {:transition cancel-order
                         :effects    effect-simple-cancel
                         :to         "canceled"}})

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
