(ns mabel.cli
  (:require [clojure.tools.cli :as cli]
            [mebot.client :as mebot]
            [clojure.string :as str]
            [clojure.core.async :refer [>!! <!! chan]]
            [clojure.set :as set]
            [mabel.core :as mabel]
            [milquetoast.client :as mqtt]
            [slingshot.slingshot :refer [try+]]
            [mabel.core :as mabel])
  (:gen-class))

(def cli-opts
  [["-v" "--verbose" "Provide verbose output."]
   ["-h" "--help" "Print this message."]

   [nil "--mqtt-host HOSTNAME" "Hostname of MQTT server on which to listen for events."]
   [nil "--mqtt-port PORT" "Port on which to connect to the incoming MQTT server."
    :parse-fn #(Integer/parseInt %)]
   [nil "--mqtt-user USER" "User as which to connect to MQTT server."]
   [nil "--mqtt-password-file PASSWD_FILE" "File containing password for MQTT user."]

   [nil "--matrix-domain DOMAIN" "Domain of Matrix server."]
   [nil "--matrix-user USER" "User as which to connect to Matrix server."]
   [nil "--matrix-password-file PASSWD_FILE" "File containing Matrix user password."]
   [nil "--matrix-room ROOM" "Room in which to report events."]])

(defn- msg-quit [& {:keys [status message]
                    :or   {status 0}}]
  (println message)
  (System/exit status))

(defn- usage
  ([summary] (usage summary []))
  ([summary errors] (->> (concat errors
                                 ["usage: mabel [opts]"
                                  ""
                                  "Options:"
                                  summary])
                         (str/join \newline))))

(defn- parse-opts [args required cli-opts]
  (let [{:keys [options] :as result} (cli/parse-opts args cli-opts)
        missing (set/difference required (-> options (keys) (set)))
        missing-errors (map #(format "missing required parameter: %s" (name %))
                            missing)]
    (update result :errors concat missing-errors)))

(defn -main [& args]
  (let [required-args #{:mqtt-host
                        :mqtt-port
                        :mqtt-user
                        :mqtt-password-file
                        :matrix-domain
                        :matrix-user
                        :matrix-password-file
                        :matrix-room}
        {:keys [options _ errors summary]} (parse-opts args required-args cli-opts)]
    (when (:help options) (msg-quit :message (usage summary)))
    (when (seq errors) (msg-quit :status 1 :message (usage summary errors)))
    (let [{:keys [mqtt-host
                  mqtt-port
                  mqtt-user
                  mqtt-password-file
                  matrix-domain
                  matrix-user
                  matrix-password-file
                  matrix-room]} options
          shutdown-chan      (chan)
          milquetoast-quit   (chan)
          mabel-quit         (chan)
          milquetoast-client (mqtt/connect-json! :username mqtt-user
                                                 :password (-> mqtt-password-file
                                                               (slurp)
                                                               (str/trim))
                                                 :host     mqtt-host
                                                 :port     mqtt-port)
          jwt       (mebot/get-jwt-token! :domain   matrix-domain
                                          :username matrix-user
                                          :password (-> matrix-password-file
                                                        (slurp)
                                                        (str/trim)))
          mebot     (mebot/request-access! (mebot/make-client! matrix-domain) jwt)
          room      (try+
                     (mebot/join-public-room! mebot :alias matrix-room)
                     (catch [:type :mebot/forbidden] {:keys [room-alias]}
                       (msg-quit :status 2 :message (str "access to "
                                                         room-alias
                                                         " forbidden"))))
          evt-chan  (mabel/frigate-listen! milquetoast-client milquetoast-quit)]
      (mabel/notify! room evt-chan mabel-quit)
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn [] (>!! shutdown-chan true))))
      (<!! shutdown-chan)
      (println "stopping milquetoast client...")
      (>!! milquetoast-quit true)
      (println "stopping mabel client...")
      (>!! mabel-quit true))
    (msg-quit :message "stopping mabel server")))
