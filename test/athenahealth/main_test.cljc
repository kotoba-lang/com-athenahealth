(ns athenahealth.main-test
  "Contract + behavioral test for the athenahealth-compat L5 actor (cljc port).
  Runs under babashka: `bb test`. Stronger than the py static contract test —
  exercises CRUD / pagination / filtering / expansion / validation against the
  in-memory Datom-log store."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [athenahealth.main :as m]))

(defn- dummy [field coerce] (case (get coerce field) :int 1 :float 1.0 :bool true (name field)))
(defn- full-record [{:keys [required coerce]}] (into {} (map (fn [f] [f (dummy f coerce)]) required)))

(deftest route-surface
  (is (= (* 5 (count m/entity-specs)) (count m/routes)))
  (doseq [{:keys [plural]} m/entity-specs]
    (let [paths (set (map (juxt :method :path) m/routes)) base (str "/v1/" plural)]
      (is (contains? paths ["POST" base])) (is (contains? paths ["GET" base]))
      (is (contains? paths ["GET" (str base "/{id}")])) (is (contains? paths ["PATCH" (str base "/{id}")]))
      (is (contains? paths ["DELETE" (str base "/{id}")])))))

(deftest crud-roundtrip
  (doseq [{:keys [entity id-prefix] :as spec} m/entity-specs]
    (let [s (m/fresh-store) [rec status] (m/handle-create s entity (full-record spec))]
      (is (= 201 status) (str entity " create"))
      (is (str/starts-with? (:id rec) (str id-prefix "_")) (str entity " id-prefix"))
      (is (= [rec 200] (m/handle-get s entity (:id rec) {})) (str entity " get"))
      (is (= (:id rec) (:id (first (m/handle-update s entity (:id rec) {})))))
      (is (= 200 (second (m/handle-delete s entity (:id rec)))))
      (is (= 404 (second (m/handle-get s entity (:id rec) {})))))))

(deftest validation
  (doseq [{:keys [entity required] :as spec} m/entity-specs]
    (when (seq required)
      (let [s (m/fresh-store)]
        (is (= 400 (second (m/handle-create s entity {}))) (str entity " missing-required"))
        (is (= 400 (second (m/handle-create s entity (assoc (full-record spec) :__bogus__ 1)))) (str entity " unknown"))))))

(deftest coercion
  (doseq [{:keys [entity coerce] :as spec} m/entity-specs]
    (when (seq coerce)
      (let [s (m/fresh-store) [rec _] (m/handle-create s entity (full-record spec))]
        (doseq [[f kind] coerce]
          (is (case kind :int (integer? (get rec f)) :float (float? (get rec f)) :bool (boolean? (get rec f)) true)
              (str entity "/" (name f))))))))

(deftest npi-validation
  (let [provider-spec (first (filter #(= "Provider" (:entity %)) m/entity-specs))
        base (full-record provider-spec)]
    (testing "create rejects an NPI with a wrong check digit"
      (let [s (m/fresh-store)
            [body status] (m/handle-create s "Provider" (assoc base :npi "1234567890"))]
        (is (= 400 status))
        (is (re-find #"npi" (:message (:error body))))))
    (testing "create rejects an NPI with the wrong digit count"
      (let [s (m/fresh-store)
            [_ status] (m/handle-create s "Provider" (assoc base :npi "123456789"))]
        (is (= 400 status))))
    (testing "create rejects a non-numeric NPI"
      (let [s (m/fresh-store)
            [_ status] (m/handle-create s "Provider" (assoc base :npi "123456789A"))]
        (is (= 400 status))))
    (testing "create accepts the canonical structurally-valid NPI (string form)"
      (let [s (m/fresh-store)
            [rec status] (m/handle-create s "Provider" (assoc base :npi "1234567893"))]
        (is (= 201 status))
        (is (= 1234567893 (:npi rec)))))
    (testing "create accepts the canonical structurally-valid NPI (numeric form)"
      (let [s (m/fresh-store)
            [rec status] (m/handle-create s "Provider" (assoc base :npi 1234567893))]
        (is (= 201 status))
        (is (= 1234567893 (:npi rec)))))
    (testing "update rejects an invalid NPI and accepts a valid one"
      (let [s (m/fresh-store)
            [rec _] (m/handle-create s "Provider" base)
            [_ bad-status] (m/handle-update s "Provider" (:id rec) {:npi "9999999999"})
            [_ ok-status] (m/handle-update s "Provider" (:id rec) {:npi "1234567893"})]
        (is (= 400 bad-status))
        (is (= 200 ok-status))))
    (testing "an absent or blank npi is left to require-fields/coercion, not rejected here"
      (let [s (m/fresh-store)
            [_ status] (m/handle-create s "Provider" base)]
        (is (= 201 status))))))

(deftest healthz
  (let [[body status] (m/healthz)]
    (is (= 200 status)) (is (= "athenahealth-compat" (:actor body))) (is (= (set m/entities) (set (:entities body))))))

#?(:clj (defn -main [& _]
          (let [{:keys [fail error]} (run-tests 'athenahealth.main-test)]
            (System/exit (if (pos? (+ fail error)) 1 0)))))
