(ns active.clojure.logger.metric-accumulator-test
  (:require [active.clojure.logger.metric-accumulator :as m]
            [clojure.test :as t]

            [clojure.spec.alpha :as s]

            [clojure.spec.test.alpha :as stest]
            [clojure.test.check.generators :as tgen])
  (:use [active.quickcheck]))

;; (t/use-fixtures :each (fn [f] (m/reset-global-raw-metric-store!) (f)))

(stest/instrument)

;; GENERATORS

(defn gen-distinct-metric-names
  [num-elems]
  (s/spec (s/coll-of ::m/metric-name :into [])
          :gen (fn []
                 (tgen/list-distinct (s/gen ::m/metric-name) {:num-elements num-elems}))))

;; TODO: Does not need to be distinct
(defn gen-metric-helps
  [num-elems]
   (s/spec (s/coll-of ::m/metric-help :into [])
           :gen (fn []
                  (tgen/list-distinct (s/gen ::m/metric-help) {:num-elements num-elems}))))

(defn gen-distinct-metric-labels
  [num-elems]
  (s/spec (s/coll-of ::m/metric-labels :into [])
          :gen (fn []
                 (tgen/list-distinct (s/gen ::m/metric-labels) {:num-elements num-elems}))))

;; TODO: Does not need to be distinct
(defn gen-metric-values
  [num-elems]
  (s/spec (s/coll-of ::m/metric-value :into [])
          :gen (fn []
                 (tgen/list-distinct (s/gen ::m/metric-value) {:num-elements num-elems}))))

(defn gen-filled-gauge-values
  [gauge-values labelss values]
  (if (empty? labelss)
    gauge-values
    (let [[label & rest-labelss] labelss
          [value & rest-values ] values]
      (gen-filled-gauge-values
       (m/update-gauge-values gauge-values label value)
       rest-labelss
       rest-values))))

(defn gen-filled-counter-values
  [counter-values labelss values]
  (if (empty? labelss)
    counter-values
    (let [[label & rest-labelss] labelss
          [value & rest-values ] values]
      (gen-filled-counter-values
       (m/update-counter-values counter-values label value)
       rest-labelss
       rest-values))))

(defn gen-filled-histogram-values
  [histogram-values labelss values]
  (if (empty? labelss)
    histogram-values
    (let [[label & rest-labelss] labelss
          [value & rest-values ] values]
      (gen-filled-histogram-values
       (m/update-histogram-values histogram-values label value)
       rest-labelss
       rest-values))))

;; fresh-metric-store-map
;; fresh-metric-store
;; set-global-metric-store!
;; reset-global-metric-store!

(t/deftest t-make-metric-value
  (t/testing "All fields of a metric-value are set correct."
    (t/is (quickcheck
           (property [value       (spec ::m/metric-value-value)
                      update-time (spec ::m/metric-value-last-update-time-ms)]
                     (let [example-metric-value (m/make-metric-value value update-time)]
                       (t/is                (m/metric-value?                    example-metric-value))
                       (t/is (= value       (m/metric-value-value               example-metric-value)))
                       (t/is (= update-time (m/metric-value-last-update-time-ms example-metric-value)))))))))

(t/deftest t-set-metric-value
  (t/testing "Setting a metric value works."
    (t/is (quickcheck
           (property [[labels & labelss]           (spec (gen-distinct-metric-labels 6))
                      [value  & values ]           (spec (gen-metric-values          6))]
                     (let [map-empty {}
                           map-prefilled (zipmap labelss values)
                           labels-to-change (nth labelss 3)]

                       (t/is (= {labels value} (m/set-metric-value map-empty labels value)))
                       (t/is (= {(nth labelss 0)   (nth values 0)
                                 (nth labelss 1)   (nth values 1)
                                 (nth labelss 2)   (nth values 2)
                                 labels-to-change  value
                                 (nth labelss 4)   (nth values 4)}
                                (m/set-metric-value map-prefilled labels-to-change value)))))))))

(t/deftest t-update-metric-value
  ;; TODO: More functions? Not only '+'?
  (t/testing "Basic update of metric-values works."
    (t/is (quickcheck
           (property [example-metric-value-1 (spec ::m/metric-value)
                      example-metric-value-2 (spec ::m/metric-value)]
                     ;; TODO: Can we add the nil to the specs? (s/or nil? (spec ::m/metric-value))
                     ;; If there is no base value, just take the second.
                     (t/is (= example-metric-value-2
                              (m/update-metric-value + nil example-metric-value-2)))
                     ;; Add value-1 and value-2. Take update-time from 2.
                     (let [value-1       (m/metric-value-value               example-metric-value-1)
                           value-2       (m/metric-value-value               example-metric-value-2)
                           update-time-2 (m/metric-value-last-update-time-ms example-metric-value-2)]
                     (t/is (= (m/make-metric-value (+ value-1 value-2) update-time-2)
                              (m/update-metric-value + example-metric-value-1 example-metric-value-2)))))))))

(t/deftest t-inc-metric-value
  (t/testing "Increment of metric-values in a labels-value-map works."
    (t/is (quickcheck
           (property [[labels & labelss] (spec (gen-distinct-metric-labels 6))
                      [value  & values ] (spec (gen-metric-values          6))]
                     (let [map-empty {}
                           map-prefilled (zipmap labelss values)
                           labels-to-change (nth labelss 3)
                           value-to-change  (nth values  3)]

                       (t/is (= {labels value}
                                (m/inc-metric-value map-empty labels value)))

                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)
                                 labels-to-change  (m/make-metric-value
                                                      (+ (m/metric-value-value value-to-change)
                                                         (m/metric-value-value value))
                                                      (m/metric-value-last-update-time-ms value))
                                 (nth labelss 4) (nth values 4)}
                                (m/inc-metric-value map-prefilled labels-to-change value)))))))))

(t/deftest t-prune-stale-metric-value
  (t/testing "Pruning stale metric values works."
    (t/is (quickcheck
           (property [labelss (spec (gen-distinct-metric-labels 6))]
                     ;; empty labels-value-map
                     (t/is (= { } (m/prune-stale-metric-value { } 1)))
                     ;; not older
                     (t/is (= {(nth labelss 0) (m/make-metric-value 300 1)}
                              (m/prune-stale-metric-value
                               {(nth labelss 0) (m/make-metric-value 300 1)} 0)))
                     ;; the same
                     (t/is (= {(nth labelss 0) (m/make-metric-value 300 1)}
                              (m/prune-stale-metric-value
                               {(nth labelss 0) (m/make-metric-value 300 1)} 1)))
                     ;; older
                     (t/is (= { } (m/prune-stale-metric-value
                                   {(nth labelss 0) (m/make-metric-value 300 1)} 2)))
                     ;; mixture and more labels
                     (t/is (= {(nth labelss 3) (m/make-metric-value 300 4)
                               (nth labelss 4) (m/make-metric-value 300 5)
                               (nth labelss 5) (m/make-metric-value 300 6)}
                              (m/prune-stale-metric-value
                               {(nth labelss 0) (m/make-metric-value 300 1)
                                (nth labelss 1) (m/make-metric-value 300 2)
                                (nth labelss 2) (m/make-metric-value 300 3)
                                (nth labelss 3) (m/make-metric-value 300 4)
                                (nth labelss 4) (m/make-metric-value 300 5)
                                (nth labelss 5) (m/make-metric-value 300 6)} 4))))))))

;; 1. Gauges

(t/deftest t-make-gauge-metric
  (t/testing "All fields of a gauge-metric are set correct."
    (t/is (quickcheck
           (property [metric-name       (spec ::m/metric-name)
                      help              (spec ::m/metric-help)]
                     (let [example-gauge-metric (m/make-gauge-metric metric-name help)]
                       (t/is                      (m/gauge-metric?                  example-gauge-metric))
                       (t/is (= metric-name       (m/gauge-metric-name              example-gauge-metric)))
                       (t/is (= help              (m/gauge-metric-help              example-gauge-metric)))))))))


(t/deftest t-make-gauge-values
  (t/testing "All fields of a metric-value are set correct."
    (let [example-gauge-values (m/make-gauge-values)]
      (t/is                       (m/gauge-values?    example-gauge-values))
      (t/is (= m/empty-values-map (m/gauge-values-map example-gauge-values))))))

(t/deftest t-update-gauge-values
  (t/testing "Updating gauge-values works."
    (t/is (quickcheck
           (property [labelss             (spec (gen-distinct-metric-labels 3))
                      [value-x & values]  (spec (gen-metric-values          4))]
                     (let [example-gauge-values (m/make-gauge-values)]
                       (t/is (= {} (m/gauge-values-map example-gauge-values)))
                       (t/is (= {(nth labelss 0) (nth values 0)}
                                (m/gauge-values-map
                                 (m/update-gauge-values example-gauge-values (nth labelss 0) (nth values 0)))))
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)}
                                (m/gauge-values-map
                                 (m/update-gauge-values
                                  (m/update-gauge-values example-gauge-values (nth labelss 0) (nth values 0))
                                  (nth labelss 1) (nth values 1)))))
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)}
                                (m/gauge-values-map
                                 (m/update-gauge-values
                                  (m/update-gauge-values
                                   (m/update-gauge-values example-gauge-values (nth labelss 0) (nth values 0))
                                   (nth labelss 1) (nth values 1))
                                  (nth labelss 2) (nth values  2)))))
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) value-x
                                 (nth labelss 2) (nth values 2)}
                                (m/gauge-values-map
                                 (m/update-gauge-values
                                  (m/update-gauge-values
                                   (m/update-gauge-values
                                    (m/update-gauge-values example-gauge-values (nth labelss 0) (nth values 0))
                                    (nth labelss 1) (nth values 1))
                                   (nth labelss 2) (nth values  2))
                                  (nth labelss 1) value-x))))))))))

(t/deftest t-gauge-values->metric-samples
  (t/testing "Creating metric-samples from gauge-values works."
    (t/is (quickcheck
           (property [name (spec ::m/metric-name)
                      [label-x & labelss] (spec (gen-distinct-metric-labels 4))
                      values              (spec (gen-metric-values          3))]
                     (let [empty-gauge-values (m/make-gauge-values)
                           filled-gauge-values (gen-filled-gauge-values empty-gauge-values labelss values)]
                       ;; empty gauge-values-map
                       (t/is (= nil
                                (m/gauge-values->metric-samples name empty-gauge-values label-x)))
                       ;; labels not in gauge-values-map
                       (t/is (= nil
                                (m/gauge-values->metric-samples name filled-gauge-values label-x)))
                       (t/is (= [(m/make-metric-sample name
                                                       (nth labelss 0)
                                                       (m/metric-value-value               (nth values  0))
                                                       (m/metric-value-last-update-time-ms (nth values  0)))]
                                (m/gauge-values->metric-samples name filled-gauge-values (nth labelss 0))))))))))

(t/deftest t-prune-stale-gauge-values
  (t/testing "Pruning stale gauge-values works."
    (t/is (quickcheck
           (property [labelss (spec (gen-distinct-metric-labels 6))]
                     (let [empty-gauge-values (m/make-gauge-values)
                           values [(m/make-metric-value 300 10)
                                   (m/make-metric-value 300 11)
                                   (m/make-metric-value 300 12)
                                   (m/make-metric-value 300 13)
                                   (m/make-metric-value 300 14)
                                   (m/make-metric-value 300 15)]
                           filled-gauge-values (gen-filled-gauge-values empty-gauge-values labelss values)]
                       ;; empty labels-value-map
                       (t/is (= { }
                                (m/gauge-values-map (m/prune-stale-gauge-values empty-gauge-values 1))))
                       ;; not older
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)
                                 (nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/gauge-values-map (m/prune-stale-gauge-values filled-gauge-values 9))))
                       ;; the same
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)
                                 (nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/gauge-values-map (m/prune-stale-gauge-values filled-gauge-values 10))))
                       ;; mixture
                       (t/is (= {(nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/gauge-values-map (m/prune-stale-gauge-values filled-gauge-values 13))))))))))

(t/deftest t-empty-gauge-values?
  (t/testing "Checking whether gauge-values-map is empty works."
    (t/is (quickcheck
           (property [labelss (spec (gen-distinct-metric-labels 6))
                      values  (spec (gen-metric-values          6))]
                     (let [empty-gauge-values (m/make-gauge-values)
                           filled-gauge-values (gen-filled-gauge-values empty-gauge-values labelss values)]
                       (t/is (= true  (m/empty-gauge-values? empty-gauge-values)))
                       (t/is (= false (m/empty-gauge-values? filled-gauge-values)))))))))

;; 2. Counters

(t/deftest t-make-counter-metric
  (t/testing "All fields of a counter-metric are set correct."
    (t/is (quickcheck
           (property [metric-name       (spec ::m/metric-name)
                      help              (spec ::m/metric-help)]
                     (let [example-counter-metric (m/make-counter-metric metric-name help)]
                       (t/is                      (m/counter-metric?                  example-counter-metric))
                       (t/is (= metric-name       (m/counter-metric-name              example-counter-metric)))
                       (t/is (= help              (m/counter-metric-help              example-counter-metric)))))))))


(t/deftest t-make-counter-values
  (t/testing "All fields of a metric-value are set correct."
    (let [example-counter-values (m/make-counter-values)]
      (t/is                       (m/counter-values?    example-counter-values))
      (t/is (= m/empty-values-map (m/counter-values-map example-counter-values))))))

(t/deftest t-update-counter-values
  (t/testing "Updating counter-values works."
    (t/is (quickcheck
           (property [labelss             (spec (gen-distinct-metric-labels 3))
                      [value-x & values]  (spec (gen-metric-values          4))]
                     (let [example-counter-values (m/make-counter-values)]
                       (t/is (= {} (m/counter-values-map example-counter-values)))
                       (t/is (= {(nth labelss 0) (nth values 0)}
                                (m/counter-values-map
                                 (m/update-counter-values example-counter-values (nth labelss 0) (nth values 0)))))
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)}
                                (m/counter-values-map
                                 (m/update-counter-values
                                  (m/update-counter-values example-counter-values (nth labelss 0) (nth values 0))
                                  (nth labelss 1) (nth values 1)))))
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)}
                                (m/counter-values-map
                                 (m/update-counter-values
                                  (m/update-counter-values
                                   (m/update-counter-values example-counter-values (nth labelss 0) (nth values 0))
                                   (nth labelss 1) (nth values 1))
                                  (nth labelss 2) (nth values  2)))))
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (m/make-metric-value (+ (m/metric-value-value (nth values 1))
                                                                         (m/metric-value-value value-x))
                                                                      (m/metric-value-last-update-time-ms value-x))
                                 (nth labelss 2) (nth values 2)}
                                (m/counter-values-map
                                 (m/update-counter-values
                                  (m/update-counter-values
                                   (m/update-counter-values
                                    (m/update-counter-values example-counter-values (nth labelss 0) (nth values 0))
                                    (nth labelss 1) (nth values 1))
                                   (nth labelss 2) (nth values  2))
                                  (nth labelss 1) value-x))))))))))

(t/deftest t-counter-values->metric-samples
  (t/testing "Creating metric-samples from counter-values works."
    (t/is (quickcheck
           (property [name (spec ::m/metric-name)
                      [label-x & labelss] (spec (gen-distinct-metric-labels 4))
                      values              (spec (gen-metric-values          3))]
                     (let [empty-counter-values (m/make-counter-values)
                           filled-counter-values (gen-filled-counter-values empty-counter-values labelss values)]
                       ;; empty counter-values-map
                       (t/is (empty?
                                (m/counter-values->metric-samples name empty-counter-values label-x)))
                       ;; labels not in counter-values-map
                       (t/is (empty?
                                (m/counter-values->metric-samples name filled-counter-values label-x)))
                       (t/is (= [(m/make-metric-sample name
                                                       (nth labelss 0)
                                                       (m/metric-value-value               (nth values  0))
                                                       (m/metric-value-last-update-time-ms (nth values  0)))]
                                (m/counter-values->metric-samples name filled-counter-values (nth labelss 0))))))))))

(t/deftest t-prune-stale-counter-values
  (t/testing "Pruning stale counter-values works."
    (t/is (quickcheck
           (property [labelss (spec (gen-distinct-metric-labels 6))]
                     (let [empty-counter-values (m/make-counter-values)
                           values [(m/make-metric-value 300 10)
                                   (m/make-metric-value 300 11)
                                   (m/make-metric-value 300 12)
                                   (m/make-metric-value 300 13)
                                   (m/make-metric-value 300 14)
                                   (m/make-metric-value 300 15)]
                           filled-counter-values (gen-filled-counter-values empty-counter-values labelss values)]
                       ;; empty labels-value-map
                       (t/is (= { }
                                (m/counter-values-map (m/prune-stale-counter-values empty-counter-values 1))))
                       ;; not older
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)
                                 (nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/counter-values-map (m/prune-stale-counter-values filled-counter-values 9))))
                       ;; the same
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)
                                 (nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/counter-values-map (m/prune-stale-counter-values filled-counter-values 10))))
                       ;; mixture
                       (t/is (= {(nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/counter-values-map (m/prune-stale-counter-values filled-counter-values 13))))))))))

(t/deftest t-empty-counter-values?
    (t/testing "Checking whether counter-values-map is empty works."
    (t/is (quickcheck
           (property [labelss (spec (gen-distinct-metric-labels 6))
                      values  (spec (gen-metric-values          6))]
                     (let [empty-counter-values (m/make-counter-values)
                           filled-counter-values (gen-filled-counter-values empty-counter-values labelss values)]
                       (t/is (= true  (m/empty-counter-values? empty-counter-values)))
                       (t/is (= false (m/empty-counter-values? filled-counter-values)))))))))

;; 3. Histograms

(t/deftest t-make-histogram-metric
  (t/testing "All fields of a histogram-metric are set correct."
    (t/is (quickcheck
           (property [metric-name       (spec ::m/metric-name)
                      help              (spec ::m/metric-help)
                      threshold         (spec ::m/metric-value-value)]
                     (let [example-histogram-metric (m/make-histogram-metric metric-name help threshold)]
                       (t/is                      (m/histogram-metric? example-histogram-metric))
                       (t/is (= metric-name       (m/histogram-metric-name                     example-histogram-metric)))
                       (t/is (= help              (m/histogram-metric-help                     example-histogram-metric)))
                       (t/is (= threshold         (m/histogram-metric-threshold                example-histogram-metric)))))))))

(t/deftest t-make-histogram-values
  (t/testing "All fields of a metric-value are set correct."
    (t/is (quickcheck
           (property [threshold (spec ::m/metric-value-value)]
                     (let [example-histogram-values (m/make-histogram-values threshold)]
                       (t/is                       (m/histogram-values?           example-histogram-values))
                       (t/is (= threshold          (m/histogram-values-threshold  example-histogram-values)))
                       (t/is (= m/empty-values-map (m/histogram-values-sum-map    example-histogram-values)))
                       (t/is (= m/empty-values-map (m/histogram-values-count-map  example-histogram-values)))
                       (t/is (= m/empty-values-map (m/histogram-values-bucket-map example-histogram-values)))))))))

;; TODO: with lets --- this can be shorter
;; TODO: we only test the maps, but we should also check, that the threshold stays the same.
(t/deftest t-update-histogram-values
  (t/testing "Updating histogram-values works."
    (t/is (quickcheck
           (property [labelss             (spec (gen-distinct-metric-labels 3))
                      [value-x & values]  (spec (gen-metric-values          4))
                      threshold           (spec ::m/metric-value-value)]
                     (let [example-histogram-values (m/make-histogram-values threshold)]
                       ;; testing sum-map
                       (t/is (= {} (m/histogram-values-sum-map example-histogram-values)))
                       (t/is (= {(nth labelss 0) (nth values 0)}
                                (m/histogram-values-sum-map
                                 (m/update-histogram-values example-histogram-values (nth labelss 0) (nth values 0)))))
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)}
                                (m/histogram-values-sum-map
                                 (m/update-histogram-values
                                  (m/update-histogram-values example-histogram-values (nth labelss 0) (nth values 0))
                                  (nth labelss 1) (nth values 1)))))
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)}
                                (m/histogram-values-sum-map
                                 (m/update-histogram-values
                                  (m/update-histogram-values
                                   (m/update-histogram-values example-histogram-values (nth labelss 0) (nth values 0))
                                   (nth labelss 1) (nth values 1))
                                  (nth labelss 2) (nth values  2)))))
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (m/make-metric-value (+ (m/metric-value-value (nth values 1))
                                                                         (m/metric-value-value value-x))
                                                                      (m/metric-value-last-update-time-ms value-x))
                                 (nth labelss 2) (nth values 2)}
                                (m/histogram-values-sum-map
                                 (m/update-histogram-values
                                  (m/update-histogram-values
                                   (m/update-histogram-values
                                    (m/update-histogram-values example-histogram-values (nth labelss 0) (nth values 0))
                                    (nth labelss 1) (nth values 1))
                                   (nth labelss 2) (nth values  2))
                                  (nth labelss 1) value-x))))
                       ;; testing count-map
                       (t/is (= {} (m/histogram-values-count-map example-histogram-values)))
                       (t/is (= {(nth labelss 0) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 0)))}
                                (m/histogram-values-count-map
                                 (m/update-histogram-values example-histogram-values (nth labelss 0) (nth values 0)))))
                       (t/is (= {(nth labelss 0) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 0)))
                                 (nth labelss 1) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 1)))}
                                (m/histogram-values-count-map
                                 (m/update-histogram-values
                                  (m/update-histogram-values example-histogram-values (nth labelss 0) (nth values 0))
                                  (nth labelss 1) (nth values 1)))))
                       (t/is (= {(nth labelss 0) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 0)))
                                 (nth labelss 1) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 1)))
                                 (nth labelss 2) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 2)))}
                                (m/histogram-values-count-map
                                 (m/update-histogram-values
                                  (m/update-histogram-values
                                   (m/update-histogram-values example-histogram-values (nth labelss 0) (nth values 0))
                                   (nth labelss 1) (nth values 1))
                                  (nth labelss 2) (nth values  2)))))
                       (t/is (= {(nth labelss 0) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 0)))
                                 (nth labelss 1) (m/make-metric-value 2 (m/metric-value-last-update-time-ms value-x))
                                 (nth labelss 2) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 2)))}
                                (m/histogram-values-count-map
                                 (m/update-histogram-values
                                  (m/update-histogram-values
                                   (m/update-histogram-values
                                    (m/update-histogram-values example-histogram-values (nth labelss 0) (nth values 0))
                                    (nth labelss 1) (nth values 1))
                                   (nth labelss 2) (nth values  2))
                                  (nth labelss 1) value-x))))
                       ;; testing bucket-map
                       (let [value-value-0 (if (<= (m/metric-value-value (nth values 0)) threshold) 1 0)
                             value-value-1 (if (<= (m/metric-value-value (nth values 1)) threshold) 1 0)
                             value-value-2 (if (<= (m/metric-value-value (nth values 2)) threshold) 1 0)
                             value-value-x (if (<= (m/metric-value-value value-x)        threshold) 1 0)]
                       (t/is (= {} (m/histogram-values-bucket-map example-histogram-values)))
                       (t/is (= {(nth labelss 0) (m/make-metric-value value-value-0 (m/metric-value-last-update-time-ms (nth values 0)))}
                                (m/histogram-values-bucket-map
                                 (m/update-histogram-values example-histogram-values (nth labelss 0) (nth values 0)))))
                       (t/is (= {(nth labelss 0) (m/make-metric-value value-value-0 (m/metric-value-last-update-time-ms (nth values 0)))
                                 (nth labelss 1) (m/make-metric-value value-value-1 (m/metric-value-last-update-time-ms (nth values 1)))}
                                (m/histogram-values-bucket-map
                                 (m/update-histogram-values
                                  (m/update-histogram-values example-histogram-values (nth labelss 0) (nth values 0))
                                  (nth labelss 1) (nth values 1)))))
                       (t/is (= {(nth labelss 0) (m/make-metric-value value-value-0 (m/metric-value-last-update-time-ms (nth values 0)))
                                 (nth labelss 1) (m/make-metric-value value-value-1 (m/metric-value-last-update-time-ms (nth values 1)))
                                 (nth labelss 2) (m/make-metric-value value-value-2 (m/metric-value-last-update-time-ms (nth values 2)))}
                                (m/histogram-values-bucket-map
                                 (m/update-histogram-values
                                  (m/update-histogram-values
                                   (m/update-histogram-values example-histogram-values (nth labelss 0) (nth values 0))
                                   (nth labelss 1) (nth values 1))
                                  (nth labelss 2) (nth values  2)))))
                       (t/is (= {(nth labelss 0) (m/make-metric-value value-value-0 (m/metric-value-last-update-time-ms (nth values 0)))
                                 (nth labelss 1) (m/make-metric-value (+ value-value-1
                                                                         value-value-x)
                                                                      (m/metric-value-last-update-time-ms value-x))
                                 (nth labelss 2) (m/make-metric-value value-value-2 (m/metric-value-last-update-time-ms (nth values 2)))}
                                (m/histogram-values-bucket-map
                                 (m/update-histogram-values
                                  (m/update-histogram-values
                                   (m/update-histogram-values
                                    (m/update-histogram-values example-histogram-values (nth labelss 0) (nth values 0))
                                    (nth labelss 1) (nth values 1))
                                   (nth labelss 2) (nth values  2))
                                  (nth labelss 1) value-x)))))))))))

(t/deftest t-histogram-values->metric-samples
  (t/testing "Creating metric-samples from histogram-values works."
    (t/is (quickcheck
           (property [basename            (spec ::m/metric-name)
                      threshold           (spec ::m/metric-value-value)
                      [label-x & labelss] (spec (gen-distinct-metric-labels 4))
                      values              (spec (gen-metric-values          3))]
                     (let [empty-histogram-values (m/make-histogram-values threshold)
                           filled-histogram-values (gen-filled-histogram-values empty-histogram-values labelss values)]
                       ;; empty histogram-values-map
                       (t/is (empty? (m/histogram-values->metric-samples basename empty-histogram-values label-x)))
                       ;; labels not in histogram-values-map
                       (t/is (empty? (m/histogram-values->metric-samples basename filled-histogram-values label-x)))
                       (t/is (= [(m/make-metric-sample (str basename "_sum")
                                                       (nth labelss 0)
                                                       (m/metric-value-value               (nth values 0))
                                                       (m/metric-value-last-update-time-ms (nth values 0)))
                                 (m/make-metric-sample (str basename "_count")
                                                       (nth labelss 0)
                                                       1
                                                       (m/metric-value-last-update-time-ms (nth values 0)))
                                 (m/make-metric-sample (str basename "_bucket")
                                                       (assoc (nth labelss 0) :le "+Inf")
                                                       1
                                                       (m/metric-value-last-update-time-ms (nth values 0)))
                                 (m/make-metric-sample (str basename "_bucket")
                                                       (assoc (nth labelss 0) :le (str threshold))
                                                       (if (<= (m/metric-value-value (nth values 0)) threshold) 1 0)
                                                       (m/metric-value-last-update-time-ms (nth values 0)))]
                                (m/histogram-values->metric-samples basename filled-histogram-values (nth labelss 0))))))))))

(t/deftest t-prune-stale-histogram-values
  (t/testing "Pruning stale histogram-values works."
    (t/is (quickcheck
           (property [threshold (spec ::m/metric-value-value)
                      labelss   (spec (gen-distinct-metric-labels 6))]
                     (let [empty-histogram-values (m/make-histogram-values threshold)
                           values [(m/make-metric-value 300 10)
                                   (m/make-metric-value 300 11)
                                   (m/make-metric-value 300 12)
                                   (m/make-metric-value 300 13)
                                   (m/make-metric-value 300 14)
                                   (m/make-metric-value 300 15)]
                           filled-histogram-values (gen-filled-histogram-values empty-histogram-values labelss values)]
                       ;; sum-map
                       ;; empty labels-value-map
                       (t/is (= { }
                                (m/histogram-values-sum-map (m/prune-stale-histogram-values empty-histogram-values 1))))
                       ;; not older
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)
                                 (nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/histogram-values-sum-map (m/prune-stale-histogram-values filled-histogram-values 9))))
                       ;; the same
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)
                                 (nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/histogram-values-sum-map (m/prune-stale-histogram-values filled-histogram-values 10))))
                       ;; mixture
                       (t/is (= {(nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/histogram-values-sum-map (m/prune-stale-histogram-values filled-histogram-values 13))))

                       ;; count-map
                       ;; empty labels-value-map
                       (t/is (= { }
                                (m/histogram-values-count-map (m/prune-stale-histogram-values empty-histogram-values 1))))
                       ;; not older
                       (t/is (= {(nth labelss 0) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 0)))
                                 (nth labelss 1) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 1)))
                                 (nth labelss 2) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 2)))
                                 (nth labelss 3) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 3)))
                                 (nth labelss 4) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 4)))
                                 (nth labelss 5) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 5)))}
                                (m/histogram-values-count-map (m/prune-stale-histogram-values filled-histogram-values 9))))
                       ;; the same
                       (t/is (= {(nth labelss 0) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 0)))
                                 (nth labelss 1) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 1)))
                                 (nth labelss 2) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 2)))
                                 (nth labelss 3) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 3)))
                                 (nth labelss 4) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 4)))
                                 (nth labelss 5) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 5)))}
                                (m/histogram-values-count-map (m/prune-stale-histogram-values filled-histogram-values 10))))
                       ;; mixture
                       (t/is (= {(nth labelss 3) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 3)))
                                 (nth labelss 4) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 4)))
                                 (nth labelss 5) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 5)))}
                                (m/histogram-values-count-map (m/prune-stale-histogram-values filled-histogram-values 13))))

                      ;; bucket-map
                       (let [value-value-0 (if (<= (m/metric-value-value (nth values 0)) threshold) 1 0)
                             value-value-1 (if (<= (m/metric-value-value (nth values 1)) threshold) 1 0)
                             value-value-2 (if (<= (m/metric-value-value (nth values 2)) threshold) 1 0)
                             value-value-3 (if (<= (m/metric-value-value (nth values 3)) threshold) 1 0)
                             value-value-4 (if (<= (m/metric-value-value (nth values 4)) threshold) 1 0)
                             value-value-5 (if (<= (m/metric-value-value (nth values 5)) threshold) 1 0)]

                       ;; empty labels-value-map
                       (t/is (= { }
                                (m/histogram-values-bucket-map (m/prune-stale-histogram-values empty-histogram-values 1))))
                       ;; not older
                       (t/is (= {(nth labelss 0) (m/make-metric-value value-value-0 (m/metric-value-last-update-time-ms (nth values 0)))
                                 (nth labelss 1) (m/make-metric-value value-value-1 (m/metric-value-last-update-time-ms (nth values 1)))
                                 (nth labelss 2) (m/make-metric-value value-value-2 (m/metric-value-last-update-time-ms (nth values 2)))
                                 (nth labelss 3) (m/make-metric-value value-value-3 (m/metric-value-last-update-time-ms (nth values 3)))
                                 (nth labelss 4) (m/make-metric-value value-value-4 (m/metric-value-last-update-time-ms (nth values 4)))
                                 (nth labelss 5) (m/make-metric-value value-value-5 (m/metric-value-last-update-time-ms (nth values 5)))}
                                (m/histogram-values-bucket-map (m/prune-stale-histogram-values filled-histogram-values 9))))
                       ;; the same
                       (t/is (= {(nth labelss 0) (m/make-metric-value value-value-0 (m/metric-value-last-update-time-ms (nth values 0)))
                                 (nth labelss 1) (m/make-metric-value value-value-1 (m/metric-value-last-update-time-ms (nth values 1)))
                                 (nth labelss 2) (m/make-metric-value value-value-2 (m/metric-value-last-update-time-ms (nth values 2)))
                                 (nth labelss 3) (m/make-metric-value value-value-3 (m/metric-value-last-update-time-ms (nth values 3)))
                                 (nth labelss 4) (m/make-metric-value value-value-4 (m/metric-value-last-update-time-ms (nth values 4)))
                                 (nth labelss 5) (m/make-metric-value value-value-5 (m/metric-value-last-update-time-ms (nth values 5)))}
                                (m/histogram-values-bucket-map (m/prune-stale-histogram-values filled-histogram-values 10))))
                       ;; mixture
                       (t/is (= {(nth labelss 3) (m/make-metric-value value-value-3 (m/metric-value-last-update-time-ms (nth values 3)))
                                 (nth labelss 4) (m/make-metric-value value-value-4 (m/metric-value-last-update-time-ms (nth values 4)))
                                 (nth labelss 5) (m/make-metric-value value-value-5 (m/metric-value-last-update-time-ms (nth values 5)))}
                                (m/histogram-values-bucket-map (m/prune-stale-histogram-values filled-histogram-values 13)))))))))))

(t/deftest t-empty-histogram-values?
  (t/testing "Checking whether histogram-values-map (sum-map) is empty works."
    (t/is (quickcheck
           (property [threshold (spec ::m/metric-value-value)
                      labelss   (spec (gen-distinct-metric-labels 6))
                      values    (spec (gen-metric-values          6))]
                     (let [empty-histogram-values (m/make-histogram-values threshold)
                           filled-histogram-values (gen-filled-histogram-values empty-histogram-values labelss values)]
                       (t/is (= true  (m/empty-histogram-values? empty-histogram-values)))
                       (t/is (= false (m/empty-histogram-values? filled-histogram-values)))))))))

;; Primitives on stored values

;; TODO: testing with more elaborated value-counts? That is, not only where each label has the value 1.
;; TODO: testing that threshold for histogram doesn't change.
(t/deftest t-update-stored-values
  (t/testing "Updating stored values works."
    (t/is (quickcheck
           (property [[labels-x & labelss] (spec (gen-distinct-metric-labels 7))
                      [value-x  & values ] (spec (gen-metric-values          7))
                      threshold            (spec ::m/metric-value-value)]
                     (let [filled-gauge-values     (gen-filled-gauge-values     (m/make-gauge-values)               labelss values)
                           filled-counter-values   (gen-filled-counter-values   (m/make-counter-values)             labelss values)
                           filled-histogram-values (gen-filled-histogram-values (m/make-histogram-values threshold) labelss values)]
                       ;; Gauge values
                       (t/is (= {labels-x value-x}
                                (m/gauge-values-map (m/update-stored-values (m/make-gauge-values) labels-x value-x))))
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)
                                 (nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)
                                 labels-x        value-x}
                                (m/gauge-values-map (m/update-stored-values filled-gauge-values labels-x value-x))))
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) value-x
                                 (nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/gauge-values-map (m/update-stored-values filled-gauge-values (nth labelss 2) value-x))))
                       ;; Counter values
                       (t/is (= {labels-x value-x}
                                (m/counter-values-map (m/update-stored-values (m/make-counter-values) labels-x value-x))))
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)
                                 (nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)
                                 labels-x        value-x}
                                (m/counter-values-map (m/update-stored-values filled-counter-values labels-x value-x))))
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (m/make-metric-value (+ (m/metric-value-value (nth values 2))
                                                                         (m/metric-value-value value-x))
                                                                      (m/metric-value-last-update-time-ms value-x))
                                 (nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/counter-values-map (m/update-stored-values filled-counter-values (nth labelss 2) value-x))))
                       ;; Histogram values
                       ;; sum
                       (t/is (= {labels-x value-x}
                                (m/histogram-values-sum-map (m/update-stored-values (m/make-histogram-values threshold) labels-x value-x))))
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)
                                 (nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)
                                 labels-x        value-x}
                                (m/histogram-values-sum-map (m/update-stored-values filled-histogram-values labels-x value-x))))
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (m/make-metric-value (+ (m/metric-value-value (nth values 2))
                                                                         (m/metric-value-value value-x))
                                                                      (m/metric-value-last-update-time-ms value-x))
                                 (nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/histogram-values-sum-map (m/update-stored-values filled-histogram-values (nth labelss 2) value-x))))
                       ;; count
                       (t/is (= {labels-x (m/make-metric-value 1 (m/metric-value-last-update-time-ms value-x))}
                                (m/histogram-values-count-map (m/update-stored-values (m/make-histogram-values threshold) labels-x value-x))))
                       (t/is (= {(nth labelss 0) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 0)))
                                 (nth labelss 1) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 1)))
                                 (nth labelss 2) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 2)))
                                 (nth labelss 3) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 3)))
                                 (nth labelss 4) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 4)))
                                 (nth labelss 5) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 5)))
                                 labels-x        (m/make-metric-value 1 (m/metric-value-last-update-time-ms value-x))}
                                (m/histogram-values-count-map (m/update-stored-values filled-histogram-values labels-x value-x))))
                       (t/is (= {(nth labelss 0) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 0)))
                                 (nth labelss 1) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 1)))
                                 (nth labelss 2) (m/make-metric-value 2 (m/metric-value-last-update-time-ms value-x))
                                 (nth labelss 3) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 3)))
                                 (nth labelss 4) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 4)))
                                 (nth labelss 5) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 5)))}
                                (m/histogram-values-count-map (m/update-stored-values filled-histogram-values (nth labelss 2) value-x))))
                       ;; bucket
                       (t/is (= {labels-x (m/make-metric-value (if (<= (m/metric-value-value value-x) threshold) 1 0)
                                                               (m/metric-value-last-update-time-ms value-x))}
                                (m/histogram-values-bucket-map (m/update-stored-values (m/make-histogram-values threshold) labels-x value-x))))
                       (t/is (= {(nth labelss 0) (m/make-metric-value (if (<= (m/metric-value-value (nth values 0)) threshold) 1 0)
                                                                      (m/metric-value-last-update-time-ms (nth values 0)))
                                 (nth labelss 1) (m/make-metric-value (if (<= (m/metric-value-value (nth values 1)) threshold) 1 0)
                                                                      (m/metric-value-last-update-time-ms (nth values 1)))
                                 (nth labelss 2) (m/make-metric-value (if (<= (m/metric-value-value (nth values 2)) threshold) 1 0)
                                                                      (m/metric-value-last-update-time-ms (nth values 2)))
                                 (nth labelss 3) (m/make-metric-value (if (<= (m/metric-value-value (nth values 3)) threshold) 1 0)
                                                                      (m/metric-value-last-update-time-ms (nth values 3)))
                                 (nth labelss 4) (m/make-metric-value (if (<= (m/metric-value-value (nth values 4)) threshold) 1 0)
                                                                      (m/metric-value-last-update-time-ms (nth values 4)))
                                 (nth labelss 5) (m/make-metric-value (if (<= (m/metric-value-value (nth values 5)) threshold) 1 0)
                                                                      (m/metric-value-last-update-time-ms (nth values 5)))
                                 labels-x        (m/make-metric-value (if (<= (m/metric-value-value value-x) threshold) 1 0)
                                                                      (m/metric-value-last-update-time-ms value-x))}
                                (m/histogram-values-bucket-map (m/update-stored-values filled-histogram-values labels-x value-x))))
                       (t/is (= {(nth labelss 0) (m/make-metric-value (if (<= (m/metric-value-value (nth values 0)) threshold) 1 0)
                                                                      (m/metric-value-last-update-time-ms (nth values 0)))
                                 (nth labelss 1) (m/make-metric-value (if (<= (m/metric-value-value (nth values 1)) threshold) 1 0)
                                                                      (m/metric-value-last-update-time-ms (nth values 1)))
                                 (nth labelss 2) (m/make-metric-value (cond
                                                                        (and (<= (m/metric-value-value (nth values 2)) threshold)
                                                                             (<= (m/metric-value-value value-x)        threshold)) 2
                                                                        (or  (<= (m/metric-value-value (nth values 2)) threshold)
                                                                             (<= (m/metric-value-value value-x)        threshold)) 1
                                                                        :else                                                      0)
                                                                      (m/metric-value-last-update-time-ms value-x))
                                 (nth labelss 3) (m/make-metric-value (if (<= (m/metric-value-value (nth values 3)) threshold) 1 0)
                                                                      (m/metric-value-last-update-time-ms (nth values 3)))
                                 (nth labelss 4) (m/make-metric-value (if (<= (m/metric-value-value (nth values 4)) threshold) 1 0)
                                                                      (m/metric-value-last-update-time-ms (nth values 4)))
                                 (nth labelss 5) (m/make-metric-value (if (<= (m/metric-value-value (nth values 5)) threshold) 1 0)
                                                                      (m/metric-value-last-update-time-ms (nth values 5)))}
                                (m/histogram-values-bucket-map (m/update-stored-values filled-histogram-values (nth labelss 2) value-x))))))))))

(t/deftest t-make-stored-values
  (t/testing "Making stored values works."
    (t/is (quickcheck
           (property [example-gauge-metric     (spec ::m/gauge-metric)
                      example-counter-metric   (spec ::m/counter-metric)
                      example-histogram-metric (spec ::m/histogram-metric)
                      labels                   (spec ::m/metric-labels)
                      value                    (spec ::m/metric-value)]
                     (let [gauge-stored-values     (m/make-stored-values example-gauge-metric     labels value)
                           counter-stored-values   (m/make-stored-values example-counter-metric   labels value)
                           histogram-stored-values (m/make-stored-values example-histogram-metric labels value)]
                       ;; Gauge
                       (t/is (m/gauge-values? gauge-stored-values))
                       (t/is (= {labels value}
                                (m/gauge-values-map gauge-stored-values)))

                       ;; Counter
                       (t/is (m/counter-values? counter-stored-values))
                       (t/is (= {labels value}
                                (m/counter-values-map counter-stored-values)))

                       ;; Histogram
                       (t/is (m/histogram-values? histogram-stored-values))
                       (t/is (= (m/histogram-metric-threshold example-histogram-metric)
                                (m/histogram-values-threshold histogram-stored-values)))
                       (t/is (= {labels value}
                                (m/histogram-values-sum-map histogram-stored-values)))
                       (t/is (= {labels (m/make-metric-value 1 (m/metric-value-last-update-time-ms value))}
                                (m/histogram-values-count-map histogram-stored-values)))
                       (t/is (= {labels (m/make-metric-value (if (<= (m/metric-value-value value)
                                                                     (m/histogram-metric-threshold example-histogram-metric))
                                                               1 0)
                                                             (m/metric-value-last-update-time-ms value))}
                                (m/histogram-values-bucket-map histogram-stored-values)))))))))

(t/deftest t-prune-stale-stored-values
  (t/testing "Pruning stale stored-values works."
    (t/is (quickcheck
           (property [labelss   (spec (gen-distinct-metric-labels 6))
                      threshold (spec ::m/metric-value-value)]
                     (let [empty-gauge-values (m/make-gauge-values)
                           empty-counter-values (m/make-counter-values)
                           empty-histogram-values (m/make-histogram-values threshold)
                           values [(m/make-metric-value 300 10)
                                   (m/make-metric-value 300 11)
                                   (m/make-metric-value 300 12)
                                   (m/make-metric-value 300 13)
                                   (m/make-metric-value 300 14)
                                   (m/make-metric-value 300 15)]
                           filled-gauge-values (gen-filled-gauge-values empty-gauge-values labelss values)
                           filled-counter-values (gen-filled-counter-values empty-counter-values labelss values)
                           filled-histogram-values (gen-filled-histogram-values empty-histogram-values labelss values)]
                       ;; Gauge
                       (t/is (= { }
                                (m/gauge-values-map (m/prune-stale-stored-values empty-gauge-values 1))))
                       ;; not older
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)
                                 (nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/gauge-values-map (m/prune-stale-stored-values filled-gauge-values 9))))
                       ;; the same
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)
                                 (nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/gauge-values-map (m/prune-stale-stored-values filled-gauge-values 10))))
                       ;; mixture
                       (t/is (= {(nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/gauge-values-map (m/prune-stale-stored-values filled-gauge-values 13))))
                       ;; Counter
                       ;; empty labels-value-map
                       (t/is (= { }
                                (m/counter-values-map (m/prune-stale-stored-values empty-counter-values 1))))
                       ;; not older
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)
                                 (nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/counter-values-map (m/prune-stale-stored-values filled-counter-values 9))))
                       ;; the same
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)
                                 (nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/counter-values-map (m/prune-stale-stored-values filled-counter-values 10))))
                       ;; mixture
                       (t/is (= {(nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/counter-values-map (m/prune-stale-stored-values filled-counter-values 13))))

                       ;; Histogram
                       ;; sum-map
                       ;; empty labels-value-map
                       (t/is (= { }
                                (m/histogram-values-sum-map (m/prune-stale-histogram-values empty-histogram-values 1))))
                       ;; not older
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)
                                 (nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/histogram-values-sum-map (m/prune-stale-histogram-values filled-histogram-values 9))))
                       ;; the same
                       (t/is (= {(nth labelss 0) (nth values 0)
                                 (nth labelss 1) (nth values 1)
                                 (nth labelss 2) (nth values 2)
                                 (nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/histogram-values-sum-map (m/prune-stale-histogram-values filled-histogram-values 10))))
                       ;; mixture
                       (t/is (= {(nth labelss 3) (nth values 3)
                                 (nth labelss 4) (nth values 4)
                                 (nth labelss 5) (nth values 5)}
                                (m/histogram-values-sum-map (m/prune-stale-histogram-values filled-histogram-values 13))))

                       ;; count-map
                       ;; empty labels-value-map
                       (t/is (= { }
                                (m/histogram-values-count-map (m/prune-stale-histogram-values empty-histogram-values 1))))
                       ;; not older
                       (t/is (= {(nth labelss 0) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 0)))
                                 (nth labelss 1) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 1)))
                                 (nth labelss 2) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 2)))
                                 (nth labelss 3) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 3)))
                                 (nth labelss 4) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 4)))
                                 (nth labelss 5) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 5)))}
                                (m/histogram-values-count-map (m/prune-stale-histogram-values filled-histogram-values 9))))
                       ;; the same
                       (t/is (= {(nth labelss 0) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 0)))
                                 (nth labelss 1) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 1)))
                                 (nth labelss 2) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 2)))
                                 (nth labelss 3) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 3)))
                                 (nth labelss 4) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 4)))
                                 (nth labelss 5) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 5)))}
                                (m/histogram-values-count-map (m/prune-stale-histogram-values filled-histogram-values 10))))
                       ;; mixture
                       (t/is (= {(nth labelss 3) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 3)))
                                 (nth labelss 4) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 4)))
                                 (nth labelss 5) (m/make-metric-value 1 (m/metric-value-last-update-time-ms (nth values 5)))}
                                (m/histogram-values-count-map (m/prune-stale-histogram-values filled-histogram-values 13))))

                       ;; bucket-map
                       (let [value-value-0 (if (<= (m/metric-value-value (nth values 0)) threshold) 1 0)
                             value-value-1 (if (<= (m/metric-value-value (nth values 1)) threshold) 1 0)
                             value-value-2 (if (<= (m/metric-value-value (nth values 2)) threshold) 1 0)
                             value-value-3 (if (<= (m/metric-value-value (nth values 3)) threshold) 1 0)
                             value-value-4 (if (<= (m/metric-value-value (nth values 4)) threshold) 1 0)
                             value-value-5 (if (<= (m/metric-value-value (nth values 5)) threshold) 1 0)]

                         ;; empty labels-value-map
                         (t/is (= { }
                                  (m/histogram-values-bucket-map (m/prune-stale-histogram-values empty-histogram-values 1))))
                         ;; not older
                         (t/is (= {(nth labelss 0) (m/make-metric-value value-value-0 (m/metric-value-last-update-time-ms (nth values 0)))
                                   (nth labelss 1) (m/make-metric-value value-value-1 (m/metric-value-last-update-time-ms (nth values 1)))
                                   (nth labelss 2) (m/make-metric-value value-value-2 (m/metric-value-last-update-time-ms (nth values 2)))
                                   (nth labelss 3) (m/make-metric-value value-value-3 (m/metric-value-last-update-time-ms (nth values 3)))
                                   (nth labelss 4) (m/make-metric-value value-value-4 (m/metric-value-last-update-time-ms (nth values 4)))
                                   (nth labelss 5) (m/make-metric-value value-value-5 (m/metric-value-last-update-time-ms (nth values 5)))}
                                  (m/histogram-values-bucket-map (m/prune-stale-histogram-values filled-histogram-values 9))))
                         ;; the same
                         (t/is (= {(nth labelss 0) (m/make-metric-value value-value-0 (m/metric-value-last-update-time-ms (nth values 0)))
                                   (nth labelss 1) (m/make-metric-value value-value-1 (m/metric-value-last-update-time-ms (nth values 1)))
                                   (nth labelss 2) (m/make-metric-value value-value-2 (m/metric-value-last-update-time-ms (nth values 2)))
                                   (nth labelss 3) (m/make-metric-value value-value-3 (m/metric-value-last-update-time-ms (nth values 3)))
                                   (nth labelss 4) (m/make-metric-value value-value-4 (m/metric-value-last-update-time-ms (nth values 4)))
                                   (nth labelss 5) (m/make-metric-value value-value-5 (m/metric-value-last-update-time-ms (nth values 5)))}
                                  (m/histogram-values-bucket-map (m/prune-stale-histogram-values filled-histogram-values 10))))
                         ;; mixture
                         (t/is (= {(nth labelss 3) (m/make-metric-value value-value-3 (m/metric-value-last-update-time-ms (nth values 3)))
                                   (nth labelss 4) (m/make-metric-value value-value-4 (m/metric-value-last-update-time-ms (nth values 4)))
                                   (nth labelss 5) (m/make-metric-value value-value-5 (m/metric-value-last-update-time-ms (nth values 5)))}
                                  (m/histogram-values-bucket-map (m/prune-stale-histogram-values filled-histogram-values 13)))))))))))

(t/deftest t-empty-stored-values?
  (t/testing "Checking whether a stored-values-map is empty works."
    (t/is (quickcheck
           (property [threshold (spec ::m/metric-value-value)
                      labelss   (spec (gen-distinct-metric-labels 6))
                      values    (spec (gen-metric-values          6))]
                     (let [empty-gauge-values (m/make-gauge-values)
                           filled-gauge-values (gen-filled-gauge-values empty-gauge-values labelss values)
                           empty-counter-values (m/make-counter-values)
                           filled-counter-values (gen-filled-counter-values empty-counter-values labelss values)
                           empty-histogram-values (m/make-histogram-values threshold)
                           filled-histogram-values (gen-filled-histogram-values empty-histogram-values labelss values)]

                       ;; Gauge
                       (t/is (= true  (m/empty-stored-values? empty-gauge-values)))
                       (t/is (= false (m/empty-stored-values? filled-gauge-values)))
                       ;; Counter
                       (t/is (= true  (m/empty-stored-values? empty-counter-values)))
                       (t/is (= false (m/empty-stored-values? filled-counter-values)))
                       ;; Histogram
                       (t/is (= true  (m/empty-stored-values? empty-histogram-values)))
                       (t/is (= false (m/empty-stored-values? filled-histogram-values)))))))))

;; Metric samples and sample sets

(t/deftest t-make-metric-sample
  (t/testing "All fields of a metric-sample are set correct."
    (t/is (quickcheck
           (property [metric-name (spec ::m/metric-name)
                      labels      (spec ::m/metric-labels)
                      value       (spec ::m/metric-value-value)
                      timestamp   (spec ::m/metric-value-last-update-time-ms)]
                     (let [example-metric-sample (m/make-metric-sample metric-name
                                                                       labels
                                                                       value
                                                                       timestamp)]
                       (t/is                (m/metric-sample?          example-metric-sample))
                       (t/is (= metric-name (m/metric-sample-name      example-metric-sample)))
                       (t/is (= labels      (m/metric-sample-labels    example-metric-sample)))
                       (t/is (= value       (m/metric-sample-value     example-metric-sample)))
                       (t/is (= timestamp   (m/metric-sample-timestamp example-metric-sample)))))))))

;; ------------------

;; TODO: More elaborated metric-stores
(t/deftest t-record-metric-1-get-metric-samples-1
  (t/testing "`record-metric-1` and `get-metric-samples-1` work."
    (t/is (quickcheck
           (property [names               (spec (gen-distinct-metric-names  3))
                      helps               (spec (gen-metric-helps           3))
                      threshold           (spec ::m/metric-value-value)
                      [label-x & labelss] (spec (gen-distinct-metric-labels 7))
                      [value-x & values ] (spec (gen-metric-values          7))]
                     (let [example-gauge-metric     (m/make-gauge-metric     (nth names 0) (nth helps 0))
                           example-counter-metric   (m/make-counter-metric   (nth names 1) (nth helps 1))
                           example-histogram-metric (m/make-histogram-metric (nth names 2) (nth helps 2) threshold)
                           metric-store (m/fresh-metric-store-map)]
                       ;; Gauge
                       ;; metric is not in the store
                       (t/is (= nil (m/get-metric-samples-1 metric-store example-gauge-metric label-x)))
                       ;; labels are not in this metric
                       (t/is (= nil (m/get-metric-samples-1
                                     (m/record-metric-1 metric-store example-gauge-metric (nth labelss 0) (nth values 0))
                                     example-gauge-metric label-x)))
                       ;; labels are within this metric
                       (t/is (= [(m/make-metric-sample (nth names 0)
                                                       (nth labelss 0)
                                                       (m/metric-value-value               (nth values 0))
                                                       (m/metric-value-last-update-time-ms (nth values 0)))]
                                (m/get-metric-samples-1
                                 (m/record-metric-1 metric-store example-gauge-metric (nth labelss 0) (nth values 0))
                                 example-gauge-metric (nth labelss 0))))
                       ;; labels are within this metric - map is larger
                       (t/is (= [(m/make-metric-sample (nth names 0)
                                                       (nth labelss 3)
                                                       (m/metric-value-value               (nth values 3))
                                                       (m/metric-value-last-update-time-ms (nth values 3)))]
                                (m/get-metric-samples-1
                                 (m/record-metric-1
                                  (m/record-metric-1
                                   (m/record-metric-1
                                    (m/record-metric-1
                                     (m/record-metric-1
                                      (m/record-metric-1 metric-store
                                                         example-gauge-metric (nth labelss 0) (nth values 0))
                                      example-gauge-metric (nth labelss 1) (nth values 1))
                                     example-gauge-metric (nth labelss 2) (nth values 2))
                                    example-gauge-metric (nth labelss 3) (nth values 3))
                                   example-gauge-metric (nth labelss 4) (nth values 4))
                                  example-gauge-metric (nth labelss 5) (nth values 5))
                                 example-gauge-metric (nth labelss 3))))

                       ;; Counter
                       ;; metric is not in the store
                       (t/is (= nil (m/get-metric-samples-1 metric-store example-counter-metric label-x)))
                       ;; labels are not in this metric
                       (t/is (= nil (m/get-metric-samples-1
                                     (m/record-metric-1 metric-store example-counter-metric (nth labelss 0) (nth values 0))
                                     example-counter-metric label-x)))
                       ;; labels are within this metric
                       (t/is (= [(m/make-metric-sample (nth names 1)
                                                       (nth labelss 0)
                                                       (m/metric-value-value               (nth values 0))
                                                       (m/metric-value-last-update-time-ms (nth values 0)))]
                                (m/get-metric-samples-1
                                 (m/record-metric-1 metric-store example-counter-metric (nth labelss 0) (nth values 0))
                                 example-counter-metric (nth labelss 0))))
                       ;; labels are within this metric - map is larger
                       (t/is (= [(m/make-metric-sample (nth names 1)
                                                       (nth labelss 3)
                                                       (m/metric-value-value               (nth values 3))
                                                       (m/metric-value-last-update-time-ms (nth values 3)))]
                                (m/get-metric-samples-1
                                 (m/record-metric-1
                                  (m/record-metric-1
                                   (m/record-metric-1
                                    (m/record-metric-1
                                     (m/record-metric-1
                                      (m/record-metric-1 metric-store
                                                         example-counter-metric (nth labelss 0) (nth values 0))
                                      example-counter-metric (nth labelss 1) (nth values 1))
                                     example-counter-metric (nth labelss 2) (nth values 2))
                                    example-counter-metric (nth labelss 3) (nth values 3))
                                   example-counter-metric (nth labelss 4) (nth values 4))
                                  example-counter-metric (nth labelss 5) (nth values 5))
                                 example-counter-metric (nth labelss 3))))

                       ;; Histogram
                       ;; metric is not in the store
                       (t/is (= nil (m/get-metric-samples-1 metric-store example-histogram-metric label-x)))
                       ;; labels are not in this metric
                       ;; TODO: this return value is different wrt. gauge and counter
                       (t/is (= [] (m/get-metric-samples-1
                                     (m/record-metric-1 metric-store example-histogram-metric (nth labelss 0) (nth values 0))
                                     example-histogram-metric label-x)))
                       ;; labels are within this metric
                       (t/is (= [(m/make-metric-sample (str (nth names 2) "_sum")
                                                       (nth labelss 0)
                                                       (m/metric-value-value               (nth values 0))
                                                       (m/metric-value-last-update-time-ms (nth values 0)))
                                 (m/make-metric-sample (str (nth names 2) "_count")
                                                       (nth labelss 0)
                                                       1
                                                       (m/metric-value-last-update-time-ms (nth values 0)))
                                 (m/make-metric-sample (str (nth names 2) "_bucket")
                                                       (assoc (nth labelss 0) :le "+Inf")
                                                       1
                                                       (m/metric-value-last-update-time-ms (nth values 0)))
                                 (m/make-metric-sample (str (nth names 2) "_bucket")
                                                       (assoc (nth labelss 0) :le (str threshold))
                                                       (if (<= (m/metric-value-value (nth values 0)) threshold) 1 0)
                                                       (m/metric-value-last-update-time-ms (nth values 0)))]
                                (m/get-metric-samples-1
                                 (m/record-metric-1 metric-store example-histogram-metric (nth labelss 0) (nth values 0))
                                 example-histogram-metric (nth labelss 0))))
                       ;; labels are within this metric - map is larger
                       (t/is (= [(m/make-metric-sample (str (nth names 2) "_sum")
                                                       (nth labelss 3)
                                                       (m/metric-value-value               (nth values 3))
                                                       (m/metric-value-last-update-time-ms (nth values 3)))
                                 (m/make-metric-sample (str (nth names 2) "_count")
                                                       (nth labelss 3)
                                                       1
                                                       (m/metric-value-last-update-time-ms (nth values 3)))
                                 (m/make-metric-sample (str (nth names 2) "_bucket")
                                                       (assoc (nth labelss 3) :le "+Inf")
                                                       1
                                                       (m/metric-value-last-update-time-ms (nth values 3)))
                                 (m/make-metric-sample (str (nth names 2) "_bucket")
                                                       (assoc (nth labelss 3) :le (str threshold))
                                                       (if (<= (m/metric-value-value (nth values 3)) threshold) 1 0)
                                                       (m/metric-value-last-update-time-ms (nth values 3)))]
                                (m/get-metric-samples-1
                                 (m/record-metric-1
                                  (m/record-metric-1
                                   (m/record-metric-1
                                    (m/record-metric-1
                                     (m/record-metric-1
                                      (m/record-metric-1 metric-store
                                                         example-histogram-metric (nth labelss 0) (nth values 0))
                                      example-histogram-metric (nth labelss 1) (nth values 1))
                                     example-histogram-metric (nth labelss 2) (nth values 2))
                                    example-histogram-metric (nth labelss 3) (nth values 3))
                                   example-histogram-metric (nth labelss 4) (nth values 4))
                                  example-histogram-metric (nth labelss 5) (nth values 5))
                                 example-histogram-metric (nth labelss 3))))))))))

;; stored-value->metric-samples
;; stored-value->all-metric-samples

;; make-metric-sample-set

(t/deftest t-metric-type-string
  (t/testing "Getting the metric type as string works."
    (t/is (quickcheck
           (property [example-gauge-metric     (spec ::m/gauge-metric)
                      example-counter-metric   (spec ::m/counter-metric)
                      example-histogram-metric (spec ::m/histogram-metric)]
                     (t/is (= "GAUGE"     (m/metric-type-string example-gauge-metric)))
                     (t/is (= "COUNTER"   (m/metric-type-string example-counter-metric)))
                     (t/is (= "HISTOGRAM" (m/metric-type-string example-histogram-metric))))))))

(t/deftest t-metric-name
  (t/testing "Getting the metric name works."
    (t/is (quickcheck
           (property [example-gauge-metric     (spec ::m/gauge-metric)
                      example-counter-metric   (spec ::m/counter-metric)
                      example-histogram-metric (spec ::m/histogram-metric)]
                     (t/is (= (m/gauge-metric-name example-gauge-metric)
                              (m/metric-name example-gauge-metric)))

                     (t/is (= (m/counter-metric-name example-counter-metric)
                              (m/metric-name example-counter-metric)))

                     (t/is (= (m/histogram-metric-name example-histogram-metric)
                              (m/metric-name example-histogram-metric))))))))

(t/deftest t-metric-help
  (t/testing "Getting the metric help works."
    (t/is (quickcheck
           (property [example-gauge-metric     (spec ::m/gauge-metric)
                      example-counter-metric   (spec ::m/counter-metric)
                      example-histogram-metric (spec ::m/histogram-metric)]
                     (t/is (= (m/gauge-metric-help example-gauge-metric)
                              (m/metric-help example-gauge-metric)))

                     (t/is (= (m/counter-metric-help example-counter-metric)
                              (m/metric-help example-counter-metric)))

                     (t/is (= (m/histogram-metric-help example-histogram-metric)
                              (m/metric-help example-histogram-metric))))))))

;; get-metric-sample-set
;; all-metric-sample-sets
;; get-all-metric-sample-sets!
;; prune-stale-metrics!
