(ns mabel.logging
  (:require [clojure.tools.logging :as log]))

(defn init!
  "Initializes the logging system."
  []
  (log/info "Initializing logging"))

(defn log-event!
  "Logs an event at the info level."
  [event]
  (log/info event))

(defn log-error!
  "Logs an error with the given message."
  [e message]
  (log/error e message))

(defn log-warning!
  "Logs a warning message."
  [warning]
  (log/warn warning))
