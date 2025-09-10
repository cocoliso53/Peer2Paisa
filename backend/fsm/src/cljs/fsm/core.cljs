(ns fsm.core
  (:require ["fs" :as fs]
            ["path" :as path]))

(defn ^:export greet [name]
  (str "Hello from ClojureScript backend, " name "!"))

(defn ^:export process-data [data]
  (->> data
       (map #(* % 2))
       (filter #(> % 10))
       (into [])))

(defn ^:export fibonacci [n]
  (loop [a 0 b 1 count n]
    (if (zero? count)
      a
      (recur b (+ a b) (dec count)))))

;; Async example using Node.js fs
(defn ^:export read-file-async [file-path callback]
  (.readFile fs file-path "utf8" 
    (fn [err data]
      (if err
        (callback err nil)
        (callback nil (.toUpperCase data))))))

;; Working with JSON
(defn ^:export transform-json [json-str]
  (-> json-str
      js/JSON.parse
      (js->clj :keywordize-keys true)
      (update :count inc)
      (assoc :processed-by "clojurescript")
      clj->js
      js/JSON.stringify))