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
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bad time element"
                          (utils/parse-time-element "bogus")))))

(deftest test-translate-time
  (is (= (t/seconds 1) (utils/translate-time {:duration :second :count 1})))
  (is (= (t/minutes 2) (utils/translate-time {:duration :minute :count 2}))) 
  (is (= (t/hours 3)   (utils/translate-time {:duration :hour   :count 3})))
  (is (= (t/days 4)    (utils/translate-time {:duration :day    :count 4})))
  (is (= (t/seconds 0) (utils/translate-time {:duration :foo}))))

(deftest test-parse-time
  (is (= (t/seconds 90) 
         (utils/parse-time ["1m" "30s"])))
  (is (= (t/minutes 2)
         (utils/parse-time ["2" "minutes"]))))

(run-tests)
