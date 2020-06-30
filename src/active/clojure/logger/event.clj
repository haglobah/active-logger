(ns active.clojure.logger.event
  (:require [active.clojure.logger.internal :as internal]
            [active.clojure.monad :as monad]
            [active.clojure.record :refer [define-record-type]]
            [active.clojure.logger.core :as logger-core]
            [taoensso.timbre :as timbre]))

(define-record-type ^:no-doc LogEvent
  (^{:doc "Log an event.

  `level` must be one of `[:trace :debug :info :warn :error :fatal :report]`."}
   make-log-event level origin vargs-delay map)
  log-event?
  [level log-event-level
   origin log-event-origin
   ^{:doc "Delayed seq of arguments to log; throwable may be first."}
   vargs-delay log-event-vargs-delay
   ^{:doc "Map with more data or `nil`, see [[log-context-keys]]."}
   map log-event-map])

(define-record-type ^:no-doc LogExceptionEvent
  (^{:doc "Log an exception event.

  `level` must be one of `[:trace :debug :info :warn :error :fatal :report]`."}
   make-log-exception-event level origin vargs-delay exception map)
  log-exception-event?
  [level log-exception-event-level
   origin log-exception-event-origin
   ^{:doc "Delayed seq of arguments to log; throwable may be first."}
   vargs-delay log-exception-event-vargs-delay
   exception log-exception-event-exception
   ^{:doc "Map with more data or `nil`, see [[log-context-keys]]."}
   map log-exception-event-map])

(define-record-type ^{:doc "Execute a command within 
  a log context.  The context is a map that is merged
  with the log context that's already active, if present."}
  WithLogContext
  (with-log-context context body)
  with-log-context?
  [context with-log-context-context
   body with-log-context-body])

(defmacro log-msg
  "Convenience macro for assembling a log message from objects."
  [& ?args]
  `(encore/spaced-str-with-nils [~@?args]))

(defmacro log-event
  "Log an event, described by a level and the arguments (monadic version).

  Uses current namespace as origin."
  [?level ?msg]
  `(make-log-event ~?level ~(str *ns*) (delay [~?msg]) nil))

(defmacro log-event*
  "Log an event with more data, described by a level and the arguments (monadic version).

  Uses current namespace as origin."
  [?level ?msg ?map]
  `(make-log-event ~?level ~(str *ns*) (delay [~?msg]) ~?map))

(defmacro log-exception-event
  "Log an exception event, described by a level and the arguments (monadic version).

  Uses current namespace as origin."
  [?level ?msg ?throwable]
  `(make-log-exception-event ~?level ~(str *ns*) (delay [~?msg (str ~?throwable)]) ~?throwable nil))

(defmacro log-exception-event*
  "Log an exception event, described by a level and the arguments (monadic version).

  Uses current namespace as origin."
  [?level ?msg ?throwable ?map]
  `(make-log-exception-event ~?level ~(str *ns*) (delay [~?msg (str ~?throwable)]) ~?throwable ~?map))

(defn run-log-events
  [run-any env mstate m]
  (cond
    (log-event? m)
    (do
      (internal/log-event!-internal "event"
                                    (log-event-origin m) (log-event-level m) (internal/sanitize-context (log-event-map m))
                                    (log-event-vargs-delay m))
      [nil mstate])

    (log-exception-event? m)
    (do
      (internal/log-exception-event!-internal "event"
                                              (log-exception-event-origin m) (log-exception-event-level m)
                                              (internal/sanitize-context (log-exception-event-map m))
                                              (log-exception-event-vargs-delay m)
                                              (log-exception-event-exception m))
      [nil mstate])

    (with-log-context? m)
    (timbre/with-context (merge timbre/*context*
                                (internal/sanitize-context (with-log-context-context m)))
      (run-any env mstate (with-log-context-body m)))

    :else
    monad/unknown-command))

(def log-events-command-config
  (monad/make-monad-command-config run-log-events {} {}))
