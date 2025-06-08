(ns mabel.handlers)
(ns mabel.handlers
  (:require [mebot.client :as mebot]
            [milquetoast.client :as mqtt]
            [clojure.core.async :refer [go-loop <! >! chan pipeline alt!]]
            [clj-time.core :as t]
            [clj-commons.digest :as digest]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]])
  (:import java.util.UUID))

(defn- handle-event [evt detect-chan milquetoast-client]
  (let [{{{{:keys [label camera] :as evt} :after} :payload} :content} evt]
    (>! detect-chan
        (assoc evt :snapshot
               (:payload-bytes
                (mqtt/get-raw! milquetoast-client
                               (str "frigate/" camera "/" label "/snapshot")))))))

(defn- handle-quit []
  (println "Detection loop quitting..."))

(defmulti handle-update! (fn [update & _] (:type update)))

(defmethod handle-update! :detection
  [{{:keys [label camera snapshot]} :content} mebot-room context]
  (when (-> @context :silence-map (silenced? camera) not)
    (when (-> @context :recents (has-snapshot? snapshot) not)
      (mebot/room-message! mebot-room (str "There's a " label " at the " camera))
      (let [id (UUID/randomUUID)]
        (mebot/room-image! mebot-room snapshot (str id ".jpg")))
      (swap! context
             (->* (pthru)
                  (update :recents add-snapshot snapshot)
                  (update :silence-map add-silence camera))
             #_(->* (pthru)
                    (update :recents add-snapshot snapshot)
                    (update :silence-map add-silence camera)))))
  context)

(defmethod handle-update! :quit
  [_ mebot-room _]
  (mebot/room-message! mebot-room (str "Quitting!")))

(defmethod handle-update! :message
  [{{:keys [sender] {:keys [body]} :content} :content} room context]
  (let [msg    (-> body
                   (str/replace #"^[^:]+: " "")
                   (str/trim)
                   (str/lower-case)
                   (str/split #" "))
        reply! (fn [msg] (mebot/room-message! room (str sender ": " msg)))]
    (cond (contains? #{"unmute"
                       "unsilence"}
                     (first msg))   (remove-silence-from-context reply! context (rest msg))
          (contains? #{"silence"
                       "mute"
                       "quiet"}
                     (first msg))   (add-silence-to-context reply! context (rest msg))
          :else                     (println (str sender " sez: " body)))))

(defmethod handle-update! :default [update _ _]
  (println (str "Unexpected update type for update: " update))))
