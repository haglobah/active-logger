(ns ^:no-doc active.clojure.logger.metric-accumulator
  "Metrics."
  (:require [active.clojure.record :refer [define-record-type]]
            [active.clojure.lens :as lens]
            [active.clojure.monad :as monad]

            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]))

(s/check-asserts true)

;; DATA: raw metrics

;; TODO: What is the type of the metric-store?
(s/def ::metric-store (partial instance? clojure.lang.Atom))

(s/fdef fresh-raw-metric-store
  :ret ::metric-store)
(defn ^:no-doc fresh-raw-metric-store
  []
  (atom {}))

(define-record-type ^{:doc "Metric key with it's `name` and `labels`, where
`name` must be a string and `labels` must be a map."}
  MetricKey
  ^:private really-make-metric-key
  metric-key?
  [name   metric-key-name
   labels metric-key-labels])

(s/def ::m-name   string?)
(s/def ::m-labels map?   )

(declare make-metric-key)  ; We want to refer to the specced
                           ; constructor in `::metric-key` but defined
                           ; it before `make-metric-key` for
                           ; readability.
(s/def ::metric-key
  (s/spec
   (partial instance? MetricKey)  ; We assume every instance of
                                  ; `MetricKey` is constructed via
                                  ; `make-metric-key` and therefore
                                  ; must be valid -- so we don't
                                  ; check the keys again.

          :gen (fn []  ; The generator for `::metric-key` just
                       ; generates a map of specced values (1), takes
                       ; the result of the generator and applies the
                       ; constructor (2) and returns it.
                 (sgen/fmap (fn [{:keys [m-name m-labels]}]
                              ;; (2)
                              (make-metric-key m-name m-labels))
                            ;; (1)
                            (s/gen (s/keys :req-un [::m-name ::m-labels]))))))

(s/fdef make-metric-key
  :args (s/cat :name   ::m-name
               :labels ::m-labels)
  :ret  ::metric-key)
(defn make-metric-key
  [name labels]
  ;; maybe do some error checking here if you need validations in
  ;; production runtime.  During testing,
  ;; `clojure.spec.test.alpha/instrument` the specced functions and
  ;; you'll get spec feedback (calling with wrong args, etc.).
  (really-make-metric-key name labels))

(define-record-type ^{:doc "Metric value with it's `value` and `timestamp`,
where `value` must be a number and ``timestamp` must be a number or nil."}
  MetricValue
  ^:private really-make-metric-value
  metric-value?
  [value     metric-value-value
   timestamp metric-value-timestamp])

(s/def ::m-value                number? )
(s/def ::m-timestamp (s/nilable number?))

(declare make-metric-value)
(s/def ::metric-value
  (s/spec
   (partial instance? MetricValue)
          :gen (fn []
                 (sgen/fmap (fn [{:keys [m-value m-timestamp]}]
                              (make-metric-value m-value m-timestamp))
                            (s/gen (s/keys :req-un [::m-value ::m-timestamp]))))))

(s/fdef make-metric-value
  :args (s/cat :value ::m-value :timestamp ::m-timestamp)
  :ret  ::metric-value)
(defn make-metric-value
  [value timestamp]
  (really-make-metric-value value timestamp))

(define-record-type ^{:doc "Metric sample with the sum of the fields of
`MetricKey` and `MetricValue` and the same constraints."}
  MetricSample
  ^:private really-make-metric-sample
  metric-sample?
  [name      metric-sample-name
   labels    metric-sample-labels
   value     metric-sample-value
   timestamp metric-sample-timestamp])

(declare make-metric-sample)
(s/def ::metric-sample
  (s/spec
   (partial instance? MetricSample)
   :gen (fn []
          (sgen/fmap (fn [{:keys [m-name m-labels m-value m-timestamp]}]
                       (make-metric-sample m-name m-labels m-value m-timestamp))
                     (s/gen (s/keys :req-un [::m-name ::m-labels ::m-value ::m-timestamp]))))))

(s/fdef make-metric-sample
  :args (s/cat :name      ::m-name
               :labels    ::m-labels
               :value     ::m-value
               :timestamp ::m-timestamp)
  :ret ::metric-sample)
(defn make-metric-sample
  [name labels value timestamp]
  (really-make-metric-sample name labels value timestamp))

;; TODO: Returns the value that was swapped in. - That is --- metric-value?
(s/fdef set-raw-metric!
  :args (s/cat :metric-store ::metric-store
               :metric-key   ::metric-key
               :metric-value ::metric-value))
(defn set-raw-metric!
  "Sets a `metric-value` (`MetricValue`) for the given `metric-key`
  (`MetricKey`) in `a-raw-metric-store` (`Map`). If `metric-key` is not in
  `a-raw-metric-store` key and value are added, otherwise the value of
  `metric-key` will be overwritten."
  [a-raw-metric-store metric-key metric-value]
  (swap! a-raw-metric-store assoc metric-key metric-value))

;; TODO: Can you really use twice the same name?
(s/fdef update-metric-value
  :args (s/cat
         :update-function (partial instance? clojure.lang.IFn)
         :metric-value    (s/nilable ::metric-value)
         :metric-value    ::metric-value)
  :ret ::metric-value)
;; Update a metric-value (`MetricValue`) by applying a function `f` to the
;; `value`s of `metric-value-1` (`MetricValue`) and `metric-value-2`
;; (`MetricValue`) and setting the `timestamp` to `metric-value-2`s timestamp.
;; If `metric-value-1` is `nil` take `metric-value-2`.
(defn update-metric-value
  [f metric-value-1 metric-value-2]
  (if metric-value-1
    (-> metric-value-1
        (lens/overhaul metric-value-value f (metric-value-value metric-value-2))
        (metric-value-timestamp (metric-value-timestamp metric-value-2)))
    metric-value-2))

(s/fdef sum-metric-value
  :args (s/cat
         :metric-value (s/nilable ::metric-value)
         :metric-value ::metric-value)
  :ret ::metric-value)
(def sum-metric-value (partial update-metric-value +))

;; TODO: Returns the value that was swapped in. - That is --- metric-value?
(s/fdef inc-raw-metric!
  :args (s/cat :metric-store ::metric-store
               :metric-key   ::metric-key
               :metric-value ::metric-value))
(defn inc-raw-metric!
  "Find a raw-metric with `metric-key` (`MetricKey`) in `a-raw-metric-store`
  (`Map`) and update this metric's value (`MetricValue`) by adding
  `metric-value` to the current metric's `value` and setting the `timestamp` of
  `metric-value`. If the metric is not in `a-raw-metric-store` it will be added
  as `metric-key` with `metric-value`."
  [a-raw-metric-store metric-key metric-value]
  (swap! a-raw-metric-store update metric-key sum-metric-value metric-value))

(s/fdef get-raw-metric-sample!
  :args (s/cat :metric-store ::metric-store
               :metric-key   ::metric-key)
  :ret ::metric-sample)
(defn get-raw-metric-sample!
  "Find a raw-metric with `metric-key` (`MetricKey`) in `a-raw-metric-store`
  (`Map`) and return it as a `MetricSample`."
  [a-raw-metric-store metric-key]
  (when-let [metric-value (get @a-raw-metric-store metric-key)]
    (make-metric-sample (metric-key-name metric-key)
                        (metric-key-labels metric-key)
                        (metric-value-value metric-value)
                        (metric-value-timestamp metric-value))))

(s/fdef get-raw-metric-samples!
  :args (s/cat :metric-store ::metric-store)
  :ret  [::metric-sample])
(defn get-raw-metric-samples!
  "Return all raw-metrics in `a-raw-metric-store` as `MetricSample`s."
  [a-raw-metric-store]
  (reduce-kv (fn [r metric-key metric-value]
               (concat r
                       [(make-metric-sample (metric-key-name metric-key)
                                            (metric-key-labels metric-key)
                                            (metric-value-value metric-value)
                                            (metric-value-timestamp metric-value))]))
             []
             @a-raw-metric-store))

;; COMMANDS on raw metrics

;; TODO: Why is it metric-key and value timestamp and not
;; - metric-key metric-value
;; - name labels value timestamp
(define-record-type ^{:doc "Monadic command for setting metrics."}
  SetRawMetric
  ^:private really-make-set-raw-metric
  set-raw-metric?
  [metric-key set-raw-metric-metric-key
   value      set-raw-metric-value
   timestamp  set-raw-metric-timestamp])

(s/def ::set-raw-metric
  (s/spec
   (partial instance? SetRawMetric)))

(s/fdef make-set-raw-metric
  :args (s/cat :metric-key  ::metric-key
               :m-value     ::m-value
               :m-timestamp ::m-timestamp)
  :ret ::set-raw-metric)
(defn make-set-raw-metric
  [metric-key m-value m-timestamp]
  (really-make-set-raw-metric metric-key m-value m-timestamp))

(define-record-type ^{:doc "Monadic command for incrementing metrics."}
  IncrementRawMetric
  ^:private really-make-inc-raw-metric
  inc-raw-metric?
  [metric-key inc-raw-metric-metric-key
   value inc-raw-metric-value
   timestamp inc-raw-metric-timestamp])

(s/def ::inc-raw-metric
  (s/spec
   (partial instance? IncrementRawMetric)))

(s/fdef make-inc-raw-metric
  :args (s/cat :metric-key  ::metric-key
               :m-value     ::m-value
               :m-timestamp ::m-timestamp)
  :ret ::inc-raw-metric)
(defn make-inc-raw-metric
  [metric-key m-value m-timestamp]
  (really-make-inc-raw-metric metric-key m-value m-timestamp))

(define-record-type ^{:doc "Monadic command for getting metrics."}
  GetRawMetricSample
  ^:private really-get-raw-metric-sample
  get-raw-metric-sample?
  [metric-key get-raw-metric-sample-metric-key])

(s/def ::get-raw-metric-sample
  (s/spec
   (partial instance? GetRawMetricSample)))

(s/fdef get-raw-metric-sample
  :args (s/cat :metric-key ::metric-key)
  :ret ::get-raw-metric-sample)
(defn get-raw-metric-sample
  [metric-key]
  (really-get-raw-metric-sample metric-key))

(defn with-maybe-timestamp
  [f metric-key m-value & [m-timestamp]]
  (monad/monadic
    ;; https://prometheus.io/docs/instrumenting/writing_exporters/
    ;; "You should not set timestamps on the metrics you expose, let Prometheus
    ;; take care of that."
    #_[m-timestamp (if m-timestamp
                     (monad/return m-timestamp)
                     (timeout/get-milli-time))]
    (f metric-key m-value m-timestamp)))

;; TODO: ret: monad-wrapped ::set-raw-metric
;; FIXME: this is odd - why `::metric-value`?
(s/fdef set-raw-metric
  :args (s/cat :metric-key   ::metric-key
               :metric-value ::metric-value))
(def set-raw-metric (partial with-maybe-timestamp make-set-raw-metric))


(def inc-raw-metric (partial with-maybe-timestamp make-inc-raw-metric))

(defn run-metrics
  [_run-any env state m]
  (let [raw-metric-store (::raw-metric-store env)]
    (cond
      (set-raw-metric? m)
      [(set-raw-metric! raw-metric-store
                        (set-raw-metric-metric-key m)
                        (set-raw-metric-value m))
       state]

      (inc-raw-metric? m)
      [(inc-raw-metric! raw-metric-store
                        (inc-raw-metric-metric-key m)
                        (inc-raw-metric-value m))
       state]

      (get-raw-metric-sample? m)
      [(get-raw-metric-sample! raw-metric-store
                               (get-raw-metric-sample-metric-key m))
       state]

      :else
      monad/unknown-command)))

;; METRICS
;; prometheus-style:
;; - counter
;; - gauge
;; - histogram

(define-record-type ^{:doc "Counter metric."}
  CounterMetric
  really-make-counter-metric
  counter-metric?
  [help counter-metric-help
   mkey counter-metric-key])

(defn make-counter-metric
  [name & [help labels]]
  (let [metric-key (make-metric-key name labels)]
    (really-make-counter-metric help metric-key)))

(define-record-type ^{:doc "Gauge metric."}
  GaugeMetric
  really-make-gauge-metric
  gauge-metric?
  [help gauge-metric-help
   mkey gauge-metric-key])

(defn make-gauge-metric
  [name & [help labels]]
  (let [metric-key (make-metric-key name labels)]
    (really-make-gauge-metric help metric-key)))

(define-record-type ^{:doc "Histogram metric."}
  HistogramMetric
  really-make-histogram-metric
  histogram-metric?
  [help histogram-metric-help
   threshold histogram-metric-threshold
   total-sum histogram-metric-total-sum
   bucket-le-threshold histogram-metric-bucket-le-threshold
   total-count histogram-metric-total-count
   bucket-le-inf histogram-metric-bucket-le-inf])

(defn make-histogram-metric
  [basename threshold & [help labels]]
  (let [total-sum           (make-counter-metric (str basename "_sum") nil labels)
        bucket-le-threshold (make-counter-metric (str basename "_bucket") nil (assoc labels :le (str threshold)))
        total-count         (make-counter-metric (str basename "_count") nil labels)
        bucket-le-inf       (make-counter-metric (str basename "_bucket") nil (assoc labels :le "+Inf"))]
    (really-make-histogram-metric help threshold total-sum bucket-le-threshold total-count bucket-le-inf)))


(defn record-metric!
  [raw-metric-store metric & [value timestamp]]
  (let [metric-value (make-metric-value value timestamp)]
    (cond
      (counter-metric? metric)
      (inc-raw-metric! raw-metric-store (counter-metric-key metric) metric-value)

      (gauge-metric? metric)
      (set-raw-metric! raw-metric-store (gauge-metric-key metric) metric-value)

      (histogram-metric? metric)
      (do
        (record-metric! raw-metric-store (histogram-metric-total-sum metric) value timestamp)
        (record-metric! raw-metric-store (histogram-metric-bucket-le-inf metric) 1 timestamp)
        (record-metric! raw-metric-store (histogram-metric-total-count metric) 1 timestamp)
        (if (<= value (histogram-metric-threshold metric))
          (record-metric! raw-metric-store (histogram-metric-bucket-le-threshold metric) 1 timestamp)
          (record-metric! raw-metric-store (histogram-metric-bucket-le-threshold metric) 0 timestamp))))))

(defn record-metric
  [metric & [value timestamp]]
  (let [metric-value (make-metric-value value timestamp)]
    (cond
      (counter-metric? metric)
      (inc-raw-metric (counter-metric-key metric) metric-value)

      (gauge-metric? metric)
      (set-raw-metric (gauge-metric-key metric) metric-value)

      (histogram-metric? metric)
      (monad/monadic
        (record-metric (histogram-metric-total-sum metric) value timestamp)
        (record-metric (histogram-metric-bucket-le-inf metric) 1 timestamp)
        (record-metric (histogram-metric-total-count metric) 1 timestamp)
        (if (<= value (histogram-metric-threshold metric))
          (record-metric (histogram-metric-bucket-le-threshold metric) 1 timestamp)
          (record-metric (histogram-metric-bucket-le-threshold metric) 0 timestamp))))))


(defn get-metrics!
  "Returns a collection of metric samples."
  [raw-metric-store metric]
  (cond
    (counter-metric? metric)
    [(get-raw-metric-sample! raw-metric-store (counter-metric-key metric))]

    (gauge-metric? metric)
    [(get-raw-metric-sample! raw-metric-store (gauge-metric-key metric))]

    (histogram-metric? metric)
    (mapcat (partial get-metrics! raw-metric-store)
            [(histogram-metric-total-sum metric)
             (histogram-metric-bucket-le-inf metric)
             (histogram-metric-total-count metric)
             (histogram-metric-bucket-le-threshold metric)])))

(defn get-metrics
  "Returns a collection of metric samples."
  [metric]
  (cond
    (counter-metric? metric)
    (monad/monadic
     [metric (get-raw-metric-sample (counter-metric-key metric))]
     (monad/return [metric]))

    (gauge-metric? metric)
    (monad/monadic
     [metric (get-raw-metric-sample (gauge-metric-key metric))]
     (monad/return [metric]))

    (histogram-metric? metric)
    (monad/monadic
     [metrics (monad/sequ
               (mapv get-metrics [(histogram-metric-total-sum metric)
                                  (histogram-metric-bucket-le-inf metric)
                                  (histogram-metric-total-count metric)
                                  (histogram-metric-bucket-le-threshold metric)]))]
     (monad/return (apply concat metrics)))))

(defn record-and-get!
  [metrics metric & [value timestamp]]
  (record-metric! metrics metric value timestamp)
  (get-metrics! metrics metric))

(defn record-and-get
  [metric & [value timestamp]]
  (monad/monadic
    (record-metric metric value timestamp)
    (get-metrics metric)))


(defn monad-command-config
  [& [metrics]]
  (monad/make-monad-command-config
    run-metrics
    {::raw-metric-store (or metrics (fresh-raw-metric-store))} {}))
