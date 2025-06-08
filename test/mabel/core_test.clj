(ns mabel.core-test
  (:require [mabel.core :as sut]
            [clojure.test :as t]
            [clj-time.core :as t]))

(t/deftest test-pthru
  (t/testing "pthru prints and returns its argument"
    (let [obj {:foo "bar"}]
      (t/is (= obj (with-out-str (sut/pthru obj)))))))

(t/deftest test-parallelism
  (t/testing "parallelism returns number of processors + 1"
    (t/is (= (+ 1 (.availableProcessors (Runtime/getRuntime)))
             (sut/parallelism)))))

(t/deftest test-snapshot-cache
  (let [cache (sut/snapshot-cache 2)]
    (t/testing "has-snapshot? returns false for empty cache"
      (t/is (not (sut/has-snapshot? cache "foo"))))
    
    (t/testing "add-snapshot adds snapshot"
      (let [cache (sut/add-snapshot cache "foo")]
        (t/is (sut/has-snapshot? cache "foo"))))
    
    (t/testing "add-snapshot removes oldest when full"
      (let [cache (-> (sut/snapshot-cache 2) 
                      (sut/add-snapshot "foo")
                      (sut/add-snapshot "bar") 
                      (sut/add-snapshot "baz"))]
        (t/is (not (sut/has-snapshot? cache "foo")))
        (t/is (sut/has-snapshot? cache "bar"))
        (t/is (sut/has-snapshot? cache "baz"))))))

(t/deftest test-silence-map
  (let [sm (sut/silence-map 10)]
    (t/testing "silenced? is false for non-silenced camera"
      (t/is (not (sut/silenced? sm "foo"))))
    
    (t/testing "add-silence silences camera"
      (let [sm (sut/add-silence sm "foo")]
        (t/is (sut/silenced? sm "foo"))))
    
    (t/testing "silence expires after pause-time"
      (let [sm (sut/add-silence sm "foo" (t/now))]
        (t/is (not (sut/silenced? sm "foo")))))))

(t/deftest test-parse-time
  (t/testing "parse-time handles single element"
    (t/is (= (t/seconds 42) (sut/parse-time ["42s"]))))

  (t/testing "parse-time handles multiple elements"
    (t/is (= (t/plus (t/minutes 1) (t/seconds 30))
             (sut/parse-time ["1m" "30s"])))))
