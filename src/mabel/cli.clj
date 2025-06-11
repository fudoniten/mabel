(ns mabel.cli
  (:require [clojure.tools.cli :as cli]
            [mebot.client :as mebot]
            [mabel.logging :as log]
            [clojure.string :as str]
            [clojure.core.async :refer [>!! <!! chan]]
            [clojure.set :as set]
            [mabel.core :as mabel]
            [milquetoast.client :as mqtt])
  (:gen-class))

(def ^:private cli-opts
  "Command line options for the mabel CLI."
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

(defn- msg-quit
  "Logs a message and exits with the given status code."
  [& {:keys [status message]
      :or   {status 0}}]
  (log/log-event! (format "Exiting with status %d: %s" status message))
  (System/exit status))

(defn- usage
  "Generates a usage string from the given summary and errors."
  ([summary]
   (usage summary []))
  ([summary errors]
   (->> (concat errors
                                 ["usage: mabel [opts]"
                                  ""
                                  "Options:"
                                  summary])
                         (str/join \newline))))

(defn- parse-opts
  "Parses command line options, checking for required arguments."
  [args required cli-opts]
  (let [{:keys [options] :as result} (cli/parse-opts args cli-opts)
        missing (set/difference required (-> options (keys) (set)))
        missing-errors (map #(format "missing required parameter: %s" (name %))
                            missing)]
    (update result :errors concat missing-errors)))

(defn -main
  "Entry point for the mabel CLI.
  Parses options, initializes logging, connects to MQTT and Matrix,
  and starts the notification loop."
  [& args]
  (log/init!)
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
          jwt       (try 
                      (mebot/get-jwt-token! :domain   matrix-domain
                                            :username matrix-user
                                            :password (-> matrix-password-file
                                                          (slurp)
                                                          (str/trim)))
                      (catch Exception e
                        (log/log-error! e "Failed to get JWT token")
                        (msg-quit :status 1 :message "Failed to authenticate with Matrix server")))
          mebot     (mebot/request-access! (mebot/make-client! matrix-domain) jwt)
          room      (try
                      (mebot/join-public-room! mebot :alias matrix-room)
                      (catch clojure.lang.ExceptionInfo e
                        (if (= (:type (ex-data e)) :mebot/forbidden)
                          (let [{:keys [room-alias]} (ex-data e)]
                            (msg-quit :status 2 :message (str "access to "
                                                              room-alias
                                                              " forbidden")))
                          (throw e))))
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
