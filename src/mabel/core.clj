(ns mabel.core
  (:require [mebot.client :as mebot]
            [milquetoast.client :as mqtt]
            [clojure.java.io :as io]
            [clojure.core.async :refer [go-loop <! >! chan pipeline alt!]]
            [clj-time.core :as t]
            [clj-commons.digest :as digest]
            [clojure.string :as str]
            [slingshot.slingshot :refer [throw+ try+]]
            [clojure.pprint :refer [pprint]])
  (:import org.apache.tika.Tika
           java.util.UUID))

(defn- pthru [o] (clojure.pprint/pprint o) (flush) o)

(defn create! [{:keys [domain username password]}]
  (let [client    (mebot/make-client! domain)
        jwt-token (mebot/get-jwt-token! domain username password)]
    {:client (mebot/request-access! client jwt-token)}))

(defn- detect-mime-type [is]
  (let [tika (Tika.)]
    (.detect tika is)))

(defmacro ->* [& fns]
  (let [v (gensym)]
    `(fn [~v] (-> ~v ~@fns))))

(defn- parallelism []
  (-> (Runtime/getRuntime)
      (.availableProcessors)
      (+ 1)))

(defn pipe [in xf]
  (let [out (chan)]
    (pipeline (parallelism) out xf in)
    out))

(defn frigate-listen! [milquetoast-client quit-chan]
  (let [person? (fn [evt] (or (= "person" (-> evt :payload :before :label))
                             (= "person" (-> evt :payload :after  :label))))
        evt-chan (pipe (mqtt/subscribe! milquetoast-client "frigate/events"
                                        :buffer-size 5)
                       (filter person?))
        detect-chan (chan 5)]
    (go-loop [evt (alt! evt-chan  ([e] {:type :event :content e})
                        quit-chan ([_] {:type :quit}))]
      (if (= (:type evt) :quit)
        (println "Detection loop quitting...")
        (let [{{{{:keys [label camera] :as evt} :before} :payload} :content} evt]
          (>! detect-chan
              (assoc evt :snapshot
                     (:payload-bytes
                      (mqtt/get-raw! milquetoast-client
                                     (str "frigate/" camera "/" label "/snapshot")))))
          (recur (alt! evt-chan  ([e] {:type :event :content e})
                       quit-chan ([_] {:type :quit}))))))
    detect-chan))

(defn- current-timestamp []
  (quot (System/currentTimeMillis) 1000))

(defmulti handle-update! (fn [update & _] (:type update)))

(defn- snapshot-cache [size]
  {:snapshots [] :size size})

(defn- has-snapshot? [{:keys [snapshots]} snapshot]
  (some #{(digest/sha-256 snapshot)} snapshots))

(defn- add-snapshot [{:keys [size snapshots] :as cache} snapshot]
  (assoc cache :snapshots (take size (conj snapshots (digest/sha-256 snapshot)))))

(defn- silence-map [pause-time]
  {:pause-time pause-time
   :cameras    {}
   :all        (t/now)})

(defn- add-silence
  ([sm camera]      (add-silence sm camera (t/plus (t/now) (t/seconds (:pause-time sm)))))
  ([sm camera time] (assoc-in sm [:cameras camera] time)))

(defn- silence-all [sm time]
  (assoc sm :all time))

(defn- silenced? [sm camera]
  (if (t/before? (t/now) (:all sm))
    true
    (if-let [silence-end (get-in sm [:cameras camera])]
      (t/before? (t/now) silence-end)
      false)))

(defmethod handle-update! :detection
  [{{:keys [label camera snapshot] :as update} :content} mebot-room context]
  (when (not (silenced? (:silence-map @context) camera))
    (when (not (has-snapshot? (:recents @context) snapshot))
      (mebot/room-message! mebot-room (str "There's a " label " at the " camera))
      (let [id (UUID/randomUUID)]
        (mebot/room-image! mebot-room snapshot (str id ".jpg")))
      (swap! context
             (->* (pthru)
                  (update :recents add-snapshot snapshot)
                  (update :silence-map add-silence camera))))
    context))

(defmethod handle-update! :quit
  [_ mebot-room _]
  (mebot/room-message! mebot-room (str "Quitting!")))

(defn- remove-silence-from-context [reply! context cameras]
  (doseq [camera cameras]
    (if (= camera "all")
      (do (reply! "Okay, unsilencing all!")
          (swap! context update :silence-map (->* (assoc :all (t/now))
                                                  (assoc :cameras {}))))
      (if (get-in @context [:silence-map :camera camera])
        (do (reply! (str "Okay, unsilencing " camera))
            (swap! context assoc-in :silence-map [:cameras camera] (t/now)))
        (reply! (str "Camera " camera " not found"))))))

(defn- parse-time-element [el]
  (if-some [[_ time duration] (re-matches #"^([0-9]+)([a-z]+)" el)]
    (merge (parse-time-element time) (parse-time-element duration))
    (cond (re-matches #"^[0-9]+$" el)                      {:count    (Integer/parseInt el)}
          (contains?  #{"s" "secs" "second" "seconds"} el) {:duration :second}
          (contains?  #{"m" "mins" "minute" "minutes"} el) {:duration :minute}
          (contains?  #{"h" "hrs" "hour" "hours"} el)      {:duration :hour}
          (contains?  #{"d" "day" "days"} el)              {:duration :day}
          :else (throw (throw+
                        {:type    ::bad-time
                         :message (str "bad time element: " el)})))))

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

(defn- parse-time [time-els]
  (translate-time (reduce merge (map parse-time-element time-els))))

(defn- add-silence-to-context [reply! context msg]
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
  (println (str "Unexpected update type for update: " update)))

(defn make-context [& {:keys [default-pause cache-size]
                       :or   {default-pause 10 cache-size 10}}]
  (atom {:silence-map (silence-map default-pause)
         :recents     (snapshot-cache cache-size)}))

(defn notify! [mebot-room detect-chan quit-chan &
               {:keys [cache-size default-pause]
                :or   {cache-size    10
                       default-pause 10}}]
  (let [context   (make-context :default-pause default-pause
                                :cache-size    cache-size)
        mentions  (mebot/room-self-mention-channel! mebot-room)]
    (go-loop [update (alt! detect-chan ([d] {:type :detection :content d})
                           mentions    ([m] {:type :message   :content m})
                           quit-chan   ([_] {:type :quit}))]
      (when (not (= (:type update) :quit))
        (try+
         (handle-update! update mebot-room context)
         (catch Exception e
           (mebot/room-message! mebot-room "Encountered error")
           (pprint e)))
        (recur (alt! detect-chan ([d] {:type :detection :content d})
                     mentions    ([m] {:type :message   :content m})
                     quit-chan   ([_] {:type :quit})))))))
