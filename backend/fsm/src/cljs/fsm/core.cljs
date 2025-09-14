(ns fsm.core
  (:require ["fs" :as fs]
            ["path" :as path]))

;; Delta will be ð: SxE -> S
;; we will make ð_si: E -> S for each si in S
;; So ð(si,e) = ð_si(e)
;; if the state and event are not a valid combination we will just return nil
;; and will interpret it as the nil state


(defn set-original-buyer-or-seller
  [state {:keys [event username]}]
  (when username
    (assoc state
           ({"buy" :buyer "sell" :seller} event) username
           :state "waitingNewOrderAmount")))
    

(defn delta-s0
  [state event]
  (let [state-name (:state state)
        event-name (:event event)]
    (when (= state-name "s0") ;; extra security check
      ;; We make explicit the valid states even tho it looks redundant for now
      (case event-name
        "cancel" (assoc state :state "canceled")
        "buy"    (set-original-buyer-or-seller state event)
        "sell"   (set-original-buyer-or-seller state event)
        nil))))

(defn delta
  [{:keys [state] :as s} event]
  (let [_ (js/console.log state)]
    (case state
      "s0" (delta-s0 s event)
       s)))

(defn ^:export delta-wrapped
  [js-state js-event]
  (let [state (js->clj js-state :keywordize-keys true)
        event (js->clj js-event :keywordize-keys true)
        _ (js/console.log js-state)
        _ (js/console.log js-event)
        _ (println state)]
    (clj->js (delta state event))))

;; vamos a hacer una prueba 
(defn ^:export prueba-data 
  [data]
  (let [clj-data (js->clj data :keywordize-keys true)
        new-data (merge clj-data
                        {:stuff "lol"})]
    (clj->js new-data)))

;; Working with JSON
(defn ^:export transform-json [json-str]
  (-> json-str
      js/JSON.parse
      (js->clj :keywordize-keys true)
      (update :count inc)
      (assoc :processed-by "clojurescript")
      clj->js
      js/JSON.stringify))
