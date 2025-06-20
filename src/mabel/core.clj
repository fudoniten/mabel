(ns mabel.core
  (:require [mebot.client :as mebot]
            [milquetoast.client :as mqtt]
            [clojure.core.async :refer [go-loop <! >! chan pipeline alt!]]
            [clj-time.core :as t]
            [clj-commons.digest :as digest]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [mabel.handlers :refer [handle-quit handle-event]]
            [mabel.logging :as log])
  (:import java.util.UUID))

(defn pthru
  "Prints the object and returns it. Useful for debugging.
  This function is often used in the middle of a threading macro
  to inspect the value being threaded."
  [o] 
  "Prints the object and returns it. Useful for debugging.
  This function is often used in the middle of a threading macro
  to inspect the value being threaded."
  (clojure.pprint/pprint o) 
  (flush) 
  o)

(defn create! [{:keys [domain username password]}]
  "Creates a new client with the given domain, username, and password."
  (let [client    (mebot/make-client! domain)
        jwt-token (mebot/get-jwt-token! domain username password)]
    {:client (mebot/request-access! client jwt-token)}))

(defmacro ->*
  "Threads the given value through the provided functions, returning a function that takes a value.
  This is similar to the -> macro, but returns a function instead of a value."
  [& fns]
  (let [v (gensym)]
    `(fn [~v] (-> ~v ~@fns))))

(defn parallelism
  "Returns the number of available processors plus one.
  This is used to determine the number of threads to use in the pipeline."
  []
  "Returns the number of available processors plus one.
  This is used to determine the number of threads to use in the pipeline."
  (-> (Runtime/getRuntime)
      (.availableProcessors)
      (+ 1)))

(defn pipe
  "Creates a pipeline with the given input and transformation function."
  [in xf]
  "Creates a pipeline with the given input and transformation function."
  (let [out (chan)]
    (pipeline (parallelism) out xf in)
    out))


(defn frigate-listen!
  "Listens for events from the given client and quit channel."
  [milquetoast-client quit-chan]
  (let [person? (fn [evt] (or (= "person" (-> evt :payload :before :label))
                             (= "person" (-> evt :payload :after  :label))))
        evt-chan (pipe (mqtt/subscribe! milquetoast-client "frigate/events"
                                        :buffer-size 5)
                       (filter person?))
        detect-chan (chan 5)]

    (go-loop [evt (alt! evt-chan  ([e] {:type :event :content e})
                        quit-chan ([_] {:type :quit}))]
      (if (= (:type evt) :quit)
        (handle-quit)
        (do
          (handle-event evt detect-chan milquetoast-client)
          (recur (alt! evt-chan  ([e] {:type :event :content e})
                       quit-chan ([_] {:type :quit}))))))
    detect-chan))

(defmulti handle-update! (fn [update & _] (:type update)))

(defn snapshot-cache
  "Creates a snapshot cache with the given size."
  [size]
  {:snapshots [] :size size})

(defn has-snapshot?
  "Checks if the given snapshot is in the cache."
  [{:keys [snapshots]} snapshot]
  (some #{(digest/sha-256 snapshot)} snapshots))

(defn add-snapshot
  "Adds a snapshot to the cache, removing the oldest if the cache is full."
  [{:keys [size snapshots] :as cache} snapshot]
  (assoc cache :snapshots (take size (distinct (conj snapshots (digest/sha-256 snapshot))))))

(defn silence-map
  "Creates a map to track silence times for different cameras.
  The map contains a global pause time, a map of individual camera pause times,
  and a timestamp indicating when all cameras were last silenced."
  [pause-time]
  "Creates a map to track silence times for different cameras.
  The map contains a global pause time, a map of individual camera pause times,
  and a timestamp indicating when all cameras were last silenced."
  {:pause-time pause-time
   :cameras    {}
   :all        (t/now)})

(defn add-silence
  "Adds a silence entry for the given camera to the silence map."
  ([sm camera]      (add-silence sm camera (t/plus (t/now) (t/seconds (:pause-time sm)))))
  ([sm camera time] (assoc-in sm [:cameras camera] time)))

(defn silence-all
  "Silences all cameras until the given time."
  [sm time]
  (assoc sm :all time))

(defn silenced?
  "Checks if the given camera is currently silenced."
  [sm camera]
  (if (t/before? (t/now) (:all sm))
    true
    (if-let [silence-end (get-in sm [:cameras camera])]
      (t/before? (t/now) silence-end)
      false)))

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

(defn remove-silence-from-context [reply! context cameras]
  (doseq [camera cameras]
    (if (= camera "all")
      (do (reply! "Okay, unsilencing all!")
          (swap! context update :silence-map (->* (assoc :all (t/now))
                                                  (assoc :cameras {}))))
      (if (get-in @context [:silence-map :camera camera])
        (do (reply! (str "Okay, unsilencing " camera))
            (swap! context (->* assoc-in [:silence-map :cameras camera] (t/now))))
        (reply! (str "Camera " camera " not found"))))))

(defn parse-time-element [el]
  "Parses a time element string into a map with count and duration keys.
  The string can be a number followed by a duration unit (e.g., '10s' for 10 seconds),
  or just a number (which is interpreted as a count).
  If the string doesn't match these formats, an exception is thrown."
  (if-some [[_ time duration] (re-matches #"^([0-9]+)([a-z]+)" el)]
    (merge (parse-time-element time) (parse-time-element duration))
    (cond (re-matches #"^[0-9]+$" el)                      {:count    (Integer/parseInt el)}
          (contains?  #{"s" "secs" "second" "seconds"} el) {:duration :second}
          (contains?  #{"m" "mins" "minute" "minutes"} el) {:duration :minute}
          (contains?  #{"h" "hrs" "hour" "hours"} el)      {:duration :hour}
          (contains?  #{"d" "day" "days"} el)              {:duration :day}
          :else (throw (ex-info (str "bad time element: " el) 
                                {:type ::bad-time})))))

(defmulti translate-time :duration)

(defmethod translate-time :second [{:keys [count]}]
  (t/seconds count))

(defmethod translate-time :minute [{:keys [count]}]
  (t/minutes count))

(defmethod translate-time :hour [{:keys [count]}]
  (t/hours count))

(defmethod translate-time :day [{:keys [count]}]
  (t/days count))

(defmethod translate-time :default [& _]
  (t/seconds 0))

(defn parse-time [time-els]
  (translate-time (reduce merge (map parse-time-element time-els))))

(defn add-silence-to-context [reply! context msg]
  (let [[camera & time-els] msg
        time                (t/plus (t/now) (parse-time time-els))]
    (if (= camera "all")
      (do (reply! "Okay, silencing all!")
          (swap! context assoc-in [:silence-map :all] time))
      (do (reply! (str "Okay, silencing " camera))
          (swap! context update :silence-map add-silence camera time)))))

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
  (log/log-warning! (format "Unexpected update type: %s" update)))

(defn make-context
  "Creates a new context with the given default pause time and cache size."
  [& {:keys [default-pause cache-size]
                       :or   {default-pause 10 cache-size 10}}]
  (atom {:silence-map (silence-map default-pause)
         :recents     (snapshot-cache cache-size)}))

(defn notify!
  "Starts a loop to handle updates from the detection channel and mentions, sending notifications to the given room."
  [mebot-room detect-chan quit-chan &
               {:keys [cache-size default-pause]
                :or   {cache-size    10
                       default-pause 10}}]
  (let [context   (make-context :default-pause default-pause
                                :cache-size    cache-size)
        mentions  (mebot/room-self-mention-channel! mebot-room)]
    (go-loop [update (alt! detect-chan ([d] {:type :detection :content d})
                           mentions    ([m] {:type :message   :content m})
                           quit-chan   ([_] {:type :quit}))]
      (when (-> update :type (= :quit) not)
        (try
          (handle-update! update mebot-room context)
          (catch Exception e
            (mebot/room-message! mebot-room (str "Encountered error: " (.getMessage e)))
            (pprint (.getStackTrace e))))
        (recur (alt! detect-chan ([d] {:type :detection :content d})
                     mentions    ([m] {:type :message   :content m})
                     quit-chan   ([_] {:type :quit})))))))
