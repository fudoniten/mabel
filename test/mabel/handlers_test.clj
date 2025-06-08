(ns mabel.handlers-test
  (:require [mabel.handlers :as sut]
            [clojure.test :as t]))
(ns mabel.handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [mabel.handlers :as handlers]
            [clojure.core.async :refer [chan >! <! go]]
            [clj-time.core :as t]))

(deftest test-handle-event
  (let [detect-chan (chan)
        mqtt-client (reify milquetoast.client/Client)
        evt         {:content {:payload {:after {:label "person" :camera "foo"}}}}]
    (testing "puts event on detect-chan with snapshot"
      (handlers/handle-event evt detect-chan mqtt-client)
      (is (= (assoc evt :snapshot "snapshot-bytes") 
             (<! detect-chan))))))

(deftest test-handle-update!
  (testing "detection"
    (let [room    (reify mebot.client/Room)
          context (atom {:silence-map (handlers/silence-map 10)
                         :recents     (handlers/snapshot-cache 10)})
          update  {:type    :detection
                   :content {:label    "person"
                             :camera   "foo" 
                             :snapshot "bytes"}}]
      (testing "when not silenced"
        (handlers/handle-update! update room context)
        (is (handlers/has-snapshot? (:recents @context) "bytes"))
        (is (handlers/silenced? (:silence-map @context) "foo")))
      
      (testing "when silenced"
        (swap! context assoc-in [:silence-map :all] (t/plus (t/now) (t/minutes 1)))
        (handlers/handle-update! update room context)
        (is (= 1 (count (:snapshots (:recents @context))))))))

  (testing "quit"
    (let [room (reify mebot.client/Room)]
      (handlers/handle-update! {:type :quit} room (atom nil))
      ; Just check it doesn't throw for now
      (is true)))

  (testing "message"
    (let [room    (reify mebot.client/Room)
          context (atom {:silence-map (handlers/silence-map 10)})
          update  {:type    :message
                   :content {:sender  "foo" 
                             :content {:body "mabel: silence bar 30s"}}}]
      (handlers/handle-update! update room context)
      (is (handlers/silenced? (:silence-map @context) "bar")))))
