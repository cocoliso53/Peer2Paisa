(ns fsm.core
  (:require ["fs" :as fs]
            ["path" :as path]))

;; Helpers

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

(defn cancel-order
  [state _]
  (assoc state
         :status "canceled"))

(defn set-owner-user-data
  [{sell :sell :as state} {{:keys [text user messageId]} :data}]
  ;; need to add address validation
  (if user
    (assoc state
           (if sell :seller :buyer) user
           :lastMessageId messageId)
    (assoc state
         :error "No full user data")))

;; Delta will be ð: SxE -> S
;; if the state and event are not a valid combination we will just return
;; the same state with the :error keyword, we should react to this in ts
;; the proper way (tbd)

(def transition-table
  {["s0" "buy"]    {:transition set-original-buyer-or-seller
                    :to         "waitingNewOrderAmount"}
   ["s0" "sell"]   {:transition set-original-buyer-or-seller
                    :to         "waitingNewOrderAmount"}
   ["s0" "cancel"] {:transition cancel-order
                    :to         "canceled"}
   ["waitingNewOrderAmount"
    "setAmount"]   {:transition set-amount-or-range
                    :to         "waitingSetAddress"}
   ["waitingNewOrderAmount"
    "cancel"]      {:transition cancel-order
                    :to         "canceled"}
   ["waitingSetAddress"
    "setAddress"]  {:transition set-owner-user-data
                    :to         "watingCounterpart"}
   ["waitingSetAddress"
    "cancel"]      {:transition cancel-order
                    :to         "canceled"}})

(defn delta
  [{state :state :as s} {event :event :as e}]
  (let [transition-map (get transition-table [state event])]
    (if transition-map
      (-> ((:transition transition-map) s e)
          (set-new-state (:to transition-map)))
      (assoc s :error "Invalid transition"))))

(defn ^:export delta-wrapped
  [js-state js-event]
  (let [state (js->clj js-state :keywordize-keys true)
        event (js->clj js-event :keywordize-keys true)]
    (clj->js (delta state event))))
