(ns ^:no-doc active.clojure.logger.metric-accumulator
  "Metrics."
  (:require [active.clojure.record :refer [define-record-type]]
            [active.clojure.lens :as lens]

            [active.clojure.logger.time :as time]

            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]))

(s/check-asserts true)

;; ::metric-store-maps stores contains all known metrics and their stored values

(s/def ::metric-store-map (s/map-of ::metric ::stored-values))

(s/fdef fresh-metric-store-map
  :ret ::metric-store-map)
(defn ^:no-doc fresh-metric-store-map
  []
  {})

;; TODO: Can we improve the type of the metric-store?
(s/def ::metric-store (partial instance? clojure.lang.Atom))

(s/fdef fresh-metric-store
  :ret ::metric-store)
(defn ^:no-doc fresh-metric-store
  []
  (atom (fresh-metric-store-map)))

(defonce metric-store (fresh-metric-store))

(defn set-global-metric-store!
  [fresh-metric-store-map]
  (reset! metric-store fresh-metric-store-map))

(defn reset-global-metric-store!
  []
  (reset! metric-store (fresh-metric-store-map)))

(declare make-metric-sample)

;; Maps from labels to value hold the current state of the metric
;; These maps are stored in ::stored-values

(s/def ::labels-value-map (s/map-of ::metric-labels ::metric-value))

(def ^:const empty-values-map {})

(s/def ::metric-labels (s/map-of keyword? any?))

(define-record-type ^{:doc "Metric value is a combination of the `value` itself
  and the `last-update-time-ms` of the value."}
  MetricValue
  ^:private really-make-metric-value
  metric-value?
  [value               metric-value-value
   last-update-time-ms metric-value-last-update-time-ms])

;; TODO: maybe better counter-metric-value and gauge-metric-value?
(s/def ::metric-value-value number?)
;; https://prometheus.io/docs/instrumenting/writing_exporters/
;; "You should not set timestamps on the metrics you expose, let Prometheus
;; take care of that."
(s/def ::metric-value-last-update-time-ms  number?)

(declare make-metric-value)
(s/def ::metric-value
  (s/spec
   (partial instance? MetricValue)
   :gen (fn []
          (sgen/fmap (fn [{:keys [metric-value-value metric-value-last-update-time-ms]}]
                       (make-metric-value metric-value-value metric-value-last-update-time-ms))
                     (s/gen (s/keys :req-un [::metric-value-value ::metric-value-last-update-time-ms]))))))

(s/fdef make-metric-value
  :args (s/cat :value       ::metric-value-value
               :update-time ::metric-value-last-update-time-ms)
  :ret  ::metric-value)

(defn make-metric-value
  [value update-time]
  (really-make-metric-value value update-time))

;; Primitives on `labels-value-map`s

(s/fdef set-metric-value
  :args (s/cat :labels-value-map ::labels-value-map
               :metric-labels            ::metric-labels
               :metric-value             ::metric-value)
  :ret ::labels-value-map)
(defn set-metric-value
  "Set a metric-value within a labels-value-map."
  [labels-value-map metric-labels metric-value]
  (assoc labels-value-map metric-labels metric-value))

(s/def ::update-function
  (s/fspec
   :args (s/cat :metric-value-value-1 ::metric-value-value
                :metric-value-value-2 ::metric-value-value)
   :ret ::metric-value-value))

(s/fdef update-metric-value
  :args (s/cat
         :f              ::update-function
         :metric-value-1 (s/nilable ::metric-value)
         :metric-value-2 ::metric-value)
  :ret ::metric-value)
(defn update-metric-value
  "Update a `MetricValue` by applying a function `f` to the `value`s of the old
  and the new `MetricValue` and setting the `timestamp` to the new timestamp. If
  the old-metric-value` is `nil` take the new-metric-value."
  [f metric-value-1 metric-value-2]
  (if metric-value-1
    (-> metric-value-1
        (lens/overhaul metric-value-value f (metric-value-value metric-value-2))
        (metric-value-last-update-time-ms (metric-value-last-update-time-ms metric-value-2)))
    metric-value-2))

(s/fdef inc-metric-value
  :args (s/cat :labels-value-map ::labels-value-map
               :metric-labels            ::metric-labels
               :metric-value             ::metric-value)
  :ret ::labels-value-map)
(defn inc-metric-value
  "Increment a metric-value within a labels-value-map."
  [labels-value-map metric-labels metric-value-2]
  (update labels-value-map metric-labels
          (fn [metric-value-1]
            (update-metric-value + metric-value-1 metric-value-2))))


(s/def ::metric (s/or :gauge-metric     ::gauge-metric
                      :counter-metric   ::counter-metric
                      :histogram-metric ::histogram-metric))

(s/def ::stored-values (s/or :gauge     ::gauge-values
                             :counter   ::counter-values
                             :histogram ::histogram-values))

(s/def ::metric-name string?)
(s/def ::metric-help string?)


;; 1. Gauges

(define-record-type ^{:doc "Gauge metric"}
  GaugeMetric
  ^:private really-make-gauge-metric
  gauge-metric?
  [name gauge-metric-name
   help gauge-metric-help])

(declare make-gauge-metric)
(s/def ::gauge-metric
  (s/spec
   (partial instance? GaugeMetric)
   :gen (fn []
          (sgen/fmap (fn [{:keys [metric-name metric-help]}]
                       (make-gauge-metric metric-name metric-help))
                     (s/gen (s/keys :req-un [::metric-name ::metric-help]))))))

(s/fdef make-gauge-metric
  :args (s/cat :metric-name ::metric-name
               :metric-help ::metric-help)
  :ret ::gauge-metric)
(defn make-gauge-metric
  [metric-name metric-help]
  (really-make-gauge-metric metric-name metric-help))

(define-record-type ^{:doc "Stored Gauge values, i.e. a map from labels to
  metric-values."}
  GaugeValues
  ^:private really-make-gauge-values
  gauge-values?
  [map gauge-values-map])

(s/def ::gauge-values (s/spec (partial instance? GaugeValues)))

(s/fdef make-gauge-values
  :args (s/cat)
  :ret ::gauge-values)
(defn make-gauge-values
  []
  (really-make-gauge-values empty-values-map))

(s/fdef update-gauge-values
  :args (s/cat :gauge-values  ::gauge-values
               :metric-labels ::metric-labels
               :metric-value  ::metric-value)
  :ret ::gauge-values)
(defn update-gauge-values
  "Updates a `GaugeValues`"
  [gauge-values metric-labels metric-value]
  (lens/overhaul gauge-values
                 gauge-values-map
                 (fn [labels-values-map]
                   (set-metric-value labels-values-map
                                     metric-labels
                                     metric-value))))

(s/fdef gauge-values->metric-samples
  :args (s/cat :name          ::metric-name
               :gauge-values  ::gauge-values
               :metric-labels ::metric-labels)
  :ret (s/coll-of ::metric-sample))
(defn gauge-values->metric-samples
  [name gauge-values metric-labels]
  (if-let [metric-value (get (gauge-values-map gauge-values) metric-labels)]
    [(make-metric-sample name
                         metric-labels
                         (metric-value-value               metric-value)
                         (metric-value-last-update-time-ms metric-value))]))


;; 2. Counters

(define-record-type ^{:doc "Counter metric"}
  CounterMetric
  ^:private really-make-counter-metric
  counter-metric?
  [name counter-metric-name
   help counter-metric-help])

(declare make-counter-metric)
(s/def ::counter-metric
  (s/spec
   (partial instance? CounterMetric)
   :gen (fn []
          (sgen/fmap (fn [{:keys [metric-name metric-help]}]
                       (make-counter-metric metric-name metric-help))
                     (s/gen (s/keys :req-un [::metric-name ::metric-help]))))))

(s/fdef make-counter-metric
  :args (s/cat :metric-name ::metric-name
               :metric-help ::metric-help)
  :ret ::counter-metric)
(defn make-counter-metric
  [metric-name metric-help]
  (really-make-counter-metric metric-name metric-help))

(define-record-type ^{:doc "Stored Counter values, i.e. a map from labels to
  metric-values."}
  CounterValues
  ^:private really-make-counter-values
  counter-values?
  [map counter-values-map])

(s/def ::counter-values (s/spec (partial instance? CounterValues)))

(s/fdef make-counter-values
  :args (s/cat)
  :ret ::counter-values)
(defn make-counter-values
  []
  (really-make-counter-values empty-values-map))

(s/fdef update-counter-values
  :args (s/cat :counter-values ::counter-values
               :metric-labels  ::metric-labels
               :metric-value   ::metric-value)
  :ret ::counter-values)
(defn update-counter-values
  "Updates a `CounterMetric`."
  [counter-values metric-labels metric-value]
  (lens/overhaul counter-values
                 counter-values-map
                 (fn [labels-values-map]
                   (inc-metric-value labels-values-map
                                     metric-labels
                                     metric-value))))

(s/fdef counter-values->metric-samples
  :args (s/cat :name           ::metric-name
               :counter-values ::counter-values
               :metric-labels  ::metric-labels)
  :ret (s/coll-of ::metric-sample))
(defn counter-values->metric-samples
  [name counter-values metric-labels]
  (if-let [metric-value (get (counter-values-map counter-values) metric-labels)]
    [(make-metric-sample name
                         metric-labels
                         (metric-value-value               metric-value)
                         (metric-value-last-update-time-ms metric-value))]))

;; 3. Histograms

(define-record-type ^{:doc "Histogram metric"}
  HistogramMetric
  ^:private really-make-histogram-metric
  histogram-metric?
  [name      histogram-metric-name
   help      histogram-metric-help
   threshold histogram-metric-threshold])

(s/def ::histogram-metric (s/spec (partial instance? HistogramMetric)))

(s/fdef make-histogram-metric
  :args (s/cat :metric-name ::metric-name
               :metric-help ::metric-help
               :threshold   ::metric-value-value)
  :ret ::histogram-metric)
(defn make-histogram-metric
  [metric-name metric-help threshold]
  (really-make-histogram-metric metric-name metric-help threshold))

(define-record-type ^{:doc "Stored Histogram values, i.e. a threshold and three
  maps (sum, count, bucket) from labels to metric-values."}
  HistogramValues
  ^:private really-make-histogram-values
  histogram-values?
  [threshold histogram-values-threshold
   sum-map histogram-values-sum-map
   count-map histogram-values-count-map
   bucket-map histogram-values-bucket-map])

(s/def ::histogram-values (s/spec (partial instance? HistogramValues)))

(s/fdef make-histogram-values
  :args (s/cat :threshold ::metric-value-value)
  :ret ::histogram-values)
(defn make-histogram-values
  [threshold]
  (really-make-histogram-values threshold empty-values-map empty-values-map empty-values-map))


(s/fdef update-histogram-values
  :args (s/cat :histogram-values ::histogram-values
               :metric-labels    ::metric-labels
               :metric-value     ::metric-value)
  :ret ::histogram-values)
(defn update-histogram-values
  "Updates a `HistogramMetric`."
  [histogram-values metric-labels metric-value]
  (let [last-update         (metric-value-last-update-time-ms metric-value)
        value-value         (metric-value-value               metric-value)
        threshold           (histogram-values-threshold histogram-values)
        metric-value-0      (make-metric-value 0 last-update)
        metric-value-1      (make-metric-value 1 last-update)
        metric-value-bucket (if (<= value-value threshold)
                              metric-value-1
                              metric-value-0)]
    (-> histogram-values
        (lens/overhaul histogram-values-sum-map
                       inc-metric-value metric-labels metric-value)
        (lens/overhaul histogram-values-count-map
                       inc-metric-value metric-labels metric-value-1)
        (lens/overhaul histogram-values-bucket-map
                       inc-metric-value metric-labels metric-value-bucket))))

(s/fdef histogram-values->metric-samples
  :args (s/cat :basename ::metric-name
               :histogram-values ::histogram-values
               :metric-labels ::metric-labels)
  :ret (s/coll-of ::metric-sample))
(defn histogram-values->metric-samples
  "Return all metric-samples with the given labels within this histogram-metric."
  [basename histogram-values metric-labels]
  (let [threshold  (histogram-values-threshold histogram-values)
        sum-map   (histogram-values-sum-map histogram-values)
        count-map (histogram-values-count-map histogram-values)
        bucket-map (histogram-values-bucket-map histogram-values)]
      ;; TODO: do we trust that it is always in all three maps?
    (let [metric-value-sum    (get sum-map metric-labels)
          metric-value-count  (get count-map metric-labels)
          metric-value-bucket (get bucket-map metric-labels)]
      [(make-metric-sample (str basename "_sum")
                           metric-labels
                           (metric-value-value               metric-value-sum)
                           (metric-value-last-update-time-ms metric-value-sum))
       (make-metric-sample (str basename "_count")
                           metric-labels
                           (metric-value-value               metric-value-count)
                           (metric-value-last-update-time-ms metric-value-count))
       (make-metric-sample (str basename "_bucket")
                           (assoc metric-labels :le "+Inf")
                           (metric-value-value               metric-value-count)
                           (metric-value-last-update-time-ms metric-value-count))
       (make-metric-sample (str basename "_bucket")
                           (assoc metric-labels :le (str threshold))
                           (metric-value-value               metric-value-bucket)
                           (metric-value-last-update-time-ms metric-value-bucket))])))


;; Primitives on stored values

(s/fdef update-stored-values
  :args (s/cat :stored-values ::stored-values
               :metric-labels ::metric-labels
               :metric-value  ::metric-value)
  :ret ::stored-values)
(defn update-stored-values
  [stored-values metric-labels metric-value]
  (cond
    (gauge-values? stored-values)
    (update-gauge-values stored-values metric-labels metric-value)

    (counter-values? stored-values)
    (update-counter-values stored-values metric-labels metric-value)

    (histogram-values? stored-values)
    (update-histogram-values stored-values metric-labels metric-value)))

(s/fdef make-stored-values
  :args (s/cat :metric        ::metric
               :metric-labels ::metric-labels
               :metric-value  ::metric-value)
  :ret ::stored-values)
(defn make-stored-values
  [metric metric-labels metric-value]
  (update-stored-values
   (cond
     (gauge-metric?     metric) (make-gauge-values)
     (counter-metric?   metric) (make-counter-values)
     (histogram-metric? metric) (make-histogram-values (histogram-metric-threshold metric)))
   metric-labels metric-value))


;; Metrics samples and sample sets

(define-record-type ^{:doc "Metric sample."}
  MetricSample
  ^:private really-make-metric-sample
  metric-sample?
  [name      metric-sample-name
   labels    metric-sample-labels
   value     metric-sample-value
   timestamp metric-sample-timestamp])

(s/def ::metric-sample (s/spec (partial instance? MetricSample)))

(s/fdef make-metric-sample
  :args (s/cat :name      ::metric-name
               :labels    ::metric-labels
               :value     ::metric-value-value
               :timestamp ::metric-value-last-update-time-ms)
  :ret ::metric-sample)
(defn make-metric-sample
  [name labels value timestamp]
  (really-make-metric-sample name labels value timestamp))

;; -----------------------------------------------------------------


(s/fdef record-metric
  :args (s/cat :metric-store ::metric-store-map
               :metric       ::metric
               :labels       ::metric-labels
               :value        ::metric-value)
  :ret ::metric-store-map)
(defn record-metric
  [metric-store metric metric-labels metric-value]
  (update metric-store metric #(if (some? %)
                                 update-stored-values
                                 make-stored-values)
          metric-labels metric-value))

(s/fdef record-metric!
  :args (s/cat :a-metric-store ::metric-store
               :metric         ::metric
               :labels         ::metric-labels
               :value-value    ::metric-value-value
               :optional       (s/? (s/cat :last-update ::metric-value-last-update-time-ms)))
  :ret ::metric-store)
(defn record-metric!
  "Record a metric."
  [a-metric-store metric labels value-value & [last-update]]
  (let [last-update (or last-update (time/get-milli-time!))
        metric-value (make-metric-value value-value last-update)]
    (swap! a-metric-store record-metric metric labels metric-value)))




(s/fdef stored-value->metric-samples
  :args (s/cat :metric ::metric
               :stored-value ::stored-values
               :metric-labels ::metric-labels)
  :ret (s/coll-of ::metric-sample))
(defn stored-value->metric-samples
  [metric stored-value metric-labels]
  (cond
    ;; INVARIANT: type of stored-value is expected to match type of metric
    (gauge-values? stored-value)
    (gauge-values->metric-samples (gauge-metric-name metric) stored-value metric-labels)
    (counter-values? stored-value)
    (counter-values->metric-samples (counter-metric-name metric) stored-value metric-labels)
    (histogram-values? stored-value)
    (histogram-values->metric-samples (histogram-metric-name metric) stored-value metric-labels)))

(defn stored-value->all-metric-samples
  [metric stored-value]
  (cond
    ;; INVARIANT: type of stored-value is expected to match type of metric
    (gauge-values? stored-value)
    (mapcat (fn [metric-labels] (gauge-values->metric-samples (gauge-metric-name metric) stored-value metric-labels))
            (keys (gauge-values-map stored-value)))
    (counter-values? stored-value)
    (mapcat (fn [metric-labels] (counter-values->metric-samples (counter-metric-name metric) stored-value metric-labels))
            (keys (counter-values-map stored-value)))
    (histogram-values? stored-value)
    (mapcat (fn [metric-labels] (histogram-values->metric-samples (histogram-metric-name metric) stored-value metric-labels))
            (keys (histogram-values-sum-map stored-value)))))

(s/fdef get-metric-samples
  :args (s/cat :metric-store ::metric-store-map
               :metric       ::metric
               :metric-labels ::metric-labels)
  :ret (s/coll-of ::metric-sample))
(defn get-metric-samples
  [metric-store metric metric-labels]
  (let [stored-value (get metric-store metric)]
    (stored-value->metric-samples metric stored-value metric-labels)))

(s/fdef get-metric-samples!
  :args (s/cat :a-metric-store ::metric-store
               :metric         ::metric
               :labels         ::metric-labels)
  :ret (s/coll-of ::metric-sample))
(defn get-metric-samples!
  "Return all metric-samples for a given metric within the given
  metric-store with the given labels."
  [a-metric-store metric labels]
  (get-metric-samples @a-metric-store metric labels))

(define-record-type ^{:doc "Metric sample set."}
  MetricSampleSet
  ^:private really-make-metric-sample-set
  metric-sample-set?
  [name metric-sample-set-name
   help metric-sample-set-help
   type-string metric-sample-set-type-string
   samples metric-sample-set-sampels])

(s/def ::metric-sample-set (s/spec (partial instance? MetricSampleSet)))

(s/fdef make-metric-sample-set
  :args (s/cat :name      ::metric-name
               :help    ::metric-help
               :type-string ::metric-type-string
               :samples (s/coll-of ::metric-sample))
  :ret ::metric-sample-set)
(defn make-metric-sample-set
  [name help type-string samples]
  (really-make-metric-sample-set name help type-string samples))


(defn metric-type-string
  [metric]
  (cond
    (gauge-metric? metric) "GAUGE"
    (counter-metric? metric) "COUNTER"
    (histogram-metric? metric) "HISTOGRAM"))

(defn metric-name
  [metric]
  (cond
    (gauge-metric? metric) (gauge-metric-name metric)
    (counter-metric? metric) (counter-metric-name metric)
    (histogram-metric? metric) (histogram-metric-name metric)))

(defn metric-help
  [metric]
  (cond
    (gauge-metric? metric) (gauge-metric-help metric)
    (counter-metric? metric) (counter-metric-help metric)
    (histogram-metric? metric) (histogram-metric-help metric)))

(defn get-metric-sample-set
  [metric-store metric]
  (let [stored-value (get metric-store metric)]
    (make-metric-sample-set (metric-name metric)
                            (metric-help metric)
                            (metric-type-string metric)
                            (stored-value->all-metric-samples metric stored-value))))

(s/fdef get-all-metric-sample-sets
  :args (s/cat :metric-store ::metric-store-map)
  :ret (s/coll-of (s/coll-of ::metric-sample-set)))
(defn get-all-metric-sample-sets
  "Return all metric-samples-sets within the given metric-store."
  [metric-store]
  (map (fn [metric] (get-metric-sample-set metric-store metric))
       (keys metric-store)))

(s/fdef get-all-metric-sample-sets!
  :args (s/cat :a-metric-store ::metric-store)
  :ret (s/coll-of (s/coll-of ::metric-sample-set)))
(defn get-all-metric-sample-sets!
  "Return all metric-samples-sets within the given metric-store."
  [a-metric-store]
  (get-all-metric-sample-sets @a-metric-store))
