(ns mabel.utils-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [mabel.utils :as utils]
            [clj-time.core :as t]))

(deftest test-parse-time-element
  (testing "parses number + unit"
    (is (= {:count 42 :duration :second}
           (utils/parse-time-element "42s")))
    (is (= {:count 123 :duration :minute} 
           (utils/parse-time-element "123m"))))
  
  (testing "parses bare number as count"
    (is (= {:count 99}
           (utils/parse-time-element "99"))))
           
  (testing "throws on invalid input"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Bad time element"
                          (utils/parse-time-element "bogus")))))

(deftest test-translate-time
  (is (= (t/seconds 1) (utils/translate-time {:duration :second :count 1})))
  (is (= (t/minutes 2) (utils/translate-time {:duration :minute :count 2}))) 
  (is (= (t/hours 3)   (utils/translate-time {:duration :hour   :count 3})))
  (is (= (t/days 4)    (utils/translate-time {:duration :day    :count 4})))
  (is (= (t/seconds 0) (utils/translate-time {:duration :foo}))))

(deftest test-parse-time
  (is (= (t/seconds 30)
         (utils/parse-time ["30" "seconds"])))
  (is (= (t/seconds 19)
         (utils/parse-time ["19" "second"])))
  (is (= (t/seconds 15)
         (utils/parse-time ["15" "secs"])))
  (is (= (t/seconds 55)
         (utils/parse-time ["55" "s"])))
  (is (= (t/minutes 2)
         (utils/parse-time ["2" "minutes"])))
  (is (= (t/minutes 7)
         (utils/parse-time ["7" "minute"])))
  (is (= (t/minutes 55)
         (utils/parse-time ["55" "mins"])))
  (is (= (t/minutes 15)
         (utils/parse-time ["15" "m"])))
  (is (= (t/hours 2)
         (utils/parse-time ["2" "hours"])))
  (is (= (t/hours 3)
         (utils/parse-time ["3" "hour"])))
  (is (= (t/hours 4)
         (utils/parse-time ["4" "hrs"])))
  (is (= (t/hours 5)
         (utils/parse-time ["5" "h"])))
  (is (= (t/days 3)
         (utils/parse-time ["3" "days"])))
  (is (= (t/days 4)
         (utils/parse-time ["4" "day"])))
  (is (= (t/days 5)
         (utils/parse-time ["5" "d"]))))

(run-tests)
