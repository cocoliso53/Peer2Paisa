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
  [state {:keys [event username messageId]}]
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
  [state {:keys [data messageId]}]
  (if (number-or-range? data)
    (-> state
        (assoc 
         :range (range-amount? data) ;; Do we need this? 
         :amount data
         :lastMessageId messageId)
        (dissoc :error))
    (assoc state
           :error "Invalid range or number")))

(defn cancel-order
  [state _]
  (assoc state
         :status "canceled"))
  

;; Delta will be ð: SxE -> S
;; we will make ð_si: E -> S for each si in S
;; So ð(si,e) = ð_si(e)
;; if the state and event are not a valid combination we will just return nil
;; and will interpret it as the nil state

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
