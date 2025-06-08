(ns mabel.core-test
  (:require [mabel.core :as core]
            [clojure.test :refer [deftest is testing run-tests]]
            [clj-time.core :refer [minutes seconds now plus]]))

(deftest test-parallelism
  (testing "parallelism returns number of processors + 1"
    (is (= (+ 1 (.availableProcessors (Runtime/getRuntime)))
             (core/parallelism)))))

(deftest test-snapshot-cache
  (let [cache (core/snapshot-cache 2)]
    (testing "has-snapshot? returns false for empty cache"
      (is (not (core/has-snapshot? cache "foo"))))
    
    (testing "add-snapshot adds snapshot"
      (let [cache (core/add-snapshot cache "foo")]
        (is (core/has-snapshot? cache "foo"))))
    
    (testing "add-snapshot removes oldest when full"
      (let [cache (-> (core/snapshot-cache 2)
                      (core/add-snapshot "foo")
                      (core/add-snapshot "bar")
                      (core/add-snapshot "baz"))]
        (is (not (core/has-snapshot? cache "foo")))
        (is (core/has-snapshot? cache "bar"))
        (is (core/has-snapshot? cache "baz"))))))

(deftest test-silence-map
  (let [sm (core/silence-map 10)]
    (testing "silenced? is false for non-silenced camera"
      (is (not (core/silenced? sm "foo"))))
    
    (testing "add-silence silences camera"
      (let [sm (core/add-silence sm "foo")]
        (is (core/silenced? sm "foo"))))
    
    (testing "silence expires after pause-time"
      (let [sm (core/add-silence sm "foo" (now))]
        (is (not (core/silenced? sm "foo")))))))

(deftest test-parse-time
  (testing "parse-time handles single element"
    (is (= (seconds 42) (core/parse-time ["42s"])))))

(run-tests)
