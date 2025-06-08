(ns mabel.logging)
(ns mabel.logging
  (:require [clojure.tools.logging :as log]))

(defn init! []
  (log/info "Initializing logging"))

(defn log-event! [event]
  (log/info event))

(defn log-error! [e message]
  (log/error e message))
