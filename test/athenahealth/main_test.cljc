(ns athenahealth.main-test
  "Contract + behavioral test for the athenahealth-compat L5 actor (cljc port).
  Runs under babashka: `bb test`. Stronger than the py static contract test —
  exercises CRUD / pagination / filtering / expansion / validation against the
  in-memory Datom-log store."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [athenahealth.main :as m]))

(defn- dummy [field coerce] (case (get coerce field) :int 1 :float 1.0 :bool true (name field)))
(defn- full-record
  "A record satisfying an entity-spec's :required fields. Entities with
  format `:validate`rs (e.g. Consent's GDPR Art. 9 lawful-basis check)
  can't be satisfied by the generic field-name-as-value heuristic, so they
  carry an explicit known-good `:sample` in the spec and this prefers it."
  [{:keys [required coerce sample]}]
  (or sample (into {} (map (fn [f] [f (dummy f coerce)]) required))))

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

;; ── FIXED (2026-07-08 health-check pass, modeled on the etzhayyim/iryo
;; capability-gate malformed-date fix) ── a request body that isn't a JSON
;; object at all (a bare string, number, or non-empty array) used to crash
;; handle-create/handle-update UNCAUGHT (ClassCastException via
;; reject-unknown's `(keys data)` on a string; IllegalArgumentException on a
;; number/`reduce-kv`), instead of degrading to a 400 like every other
;; malformed-input case in this namespace (missing/unknown/malformed-format
;; fields). `reject-non-map` now gates handle-create/handle-update on
;; `(map? data)` before any of that runs.
(deftest malformed-request-body-degrades-gracefully-not-a-crash
  (doseq [{:keys [entity] :as spec} m/entity-specs]
    (doseq [bad-body ["not-a-map" 123 [1 2 3] true]]
      (testing (str entity " create with body " (pr-str bad-body))
        (let [s (m/fresh-store)
              [body status] (m/handle-create s entity bad-body)]
          (is (= 400 status))
          (is (= "Request body must be a JSON object" (:message (:error body))))))
      (testing (str entity " update with body " (pr-str bad-body))
        (let [s (m/fresh-store)
              [rec _] (m/handle-create s entity (full-record spec))
              [body status] (m/handle-update s entity (:id rec) bad-body)]
          (is (= 400 status))
          (is (= "Request body must be a JSON object" (:message (:error body)))))))
    ;; regression guard: nil is exempted from this fix (it never crashed --
    ;; `(keys nil)`/`(get nil f)` are already nil-safe) and keeps degrading
    ;; via the pre-existing require-fields "missing required fields" path.
    (testing (str entity " create with nil body (pre-existing, unaffected)")
      (let [s (m/fresh-store)
            [body status] (m/handle-create s entity nil)]
        (is (= 400 status))
        (is (re-find #"Missing required fields" (:message (:error body))))))))

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

;; --- Consent: EU GDPR Art. 9(2) lawful-basis validation --------------------
;; Same rationale as npi-validation above: the generic `validation` deftest
;; only covers missing-required / unknown-field rejection, which every
;; entity shares. Consent is the second entity (after Provider) whose field
;; carries a real domain-format constraint (one of the ten Art. 9(2)(a)-(j)
;; point-letters), ported by value from kotoba-lang/com-hl7-fhir
;; (ADR-2607083100).
(def ^:private consent-sample
  (:sample (first (filter #(= "Consent" (:entity %)) m/entity-specs))))

(deftest consent-domain-validation
  (testing "a fully valid consent record is accepted"
    (let [s (m/fresh-store) [rec status] (m/handle-create s "Consent" consent-sample)]
      (is (= 201 status))
      (is (str/starts-with? (:id rec) "athenahe_cst_"))
      (is (true? (:specialcategorydatayn rec)))))
  (testing "each of the ten Art. 9(2) point-letters is independently accepted"
    (doseq [code ["a" "b" "c" "d" "e" "f" "g" "h" "i" "j"]]
      (let [s (m/fresh-store)
            [_ status] (m/handle-create s "Consent" (assoc consent-sample :lawfulbasisart9 code))]
        (is (= 201 status) code))))
  (testing "a lawful-basis code outside the ten-item set is rejected"
    (let [s (m/fresh-store)
          [body status] (m/handle-create s "Consent" (assoc consent-sample :lawfulbasisart9 "z"))]
      (is (= 400 status))
      (is (re-find #"lawfulbasisart9" (:message (:error body))))))
  (testing "the full exception label instead of the point-letter is rejected"
    (let [s (m/fresh-store)
          [_ status] (m/handle-create s "Consent" (assoc consent-sample :lawfulbasisart9 "explicit consent"))]
      (is (= 400 status))))
  (testing "specialcategorydatayn coerces truthy wire values to boolean"
    (let [s (m/fresh-store)
          [rec _] (m/handle-create s "Consent" (assoc consent-sample :specialcategorydatayn "true"))]
      (is (true? (:specialcategorydatayn rec)))))
  (testing "update also enforces format validation on a present field"
    (let [s (m/fresh-store)
          [rec _] (m/handle-create s "Consent" consent-sample)
          [body status] (m/handle-update s "Consent" (:id rec) {:lawfulbasisart9 "zz"})]
      (is (= 400 status))
      (is (re-find #"lawfulbasisart9" (:message (:error body)))))))

;; --- PatientAccessRequest: EU EHDS Art. 3 primary-use access rights -------
;; Same rationale as npi-validation / consent-domain-validation above: the
;; generic `validation` deftest only covers missing-required / unknown-field
;; rejection, which every entity shares. PatientAccessRequest is the third
;; entity with a real domain constraint, and the first with a *cross-field*
;; one (:validate-record, not just :validate) -- restrictionappliedyn=true
;; requires a non-blank restrictionreason (EHDS Art. 3(3), ported by value
;; from kotoba-lang/com-hl7-fhir, ADR-2607083200).
(def ^:private access-request-sample
  (:sample (first (filter #(= "PatientAccessRequest" (:entity %)) m/entity-specs))))

(deftest patient-access-request-domain-validation
  (testing "a fully valid view request is accepted"
    (let [s (m/fresh-store) [rec status] (m/handle-create s "PatientAccessRequest" access-request-sample)]
      (is (= 201 status))
      (is (str/starts-with? (:id rec) "athenahe_par_"))
      (is (= "patientsummary" (:prioritycategory rec)))
      (is (false? (:restrictionappliedyn rec)))))
  (testing "a download request is accepted"
    (let [s (m/fresh-store)
          [_ status] (m/handle-create s "PatientAccessRequest" (assoc access-request-sample :accessmethod "download"))]
      (is (= 201 status))))
  (testing "accessmethod is case-insensitive"
    (let [s (m/fresh-store)
          [_ status] (m/handle-create s "PatientAccessRequest" (assoc access-request-sample :accessmethod "VIEW"))]
      (is (= 201 status))))
  (testing "an accessmethod outside view/download is rejected"
    (let [s (m/fresh-store)
          [body status] (m/handle-create s "PatientAccessRequest" (assoc access-request-sample :accessmethod "print"))]
      (is (= 400 status))
      (is (re-find #"accessmethod" (:message (:error body))))))
  (testing "each of the six Article 14(1) priority categories is independently accepted"
    (doseq [category ["patientsummary" "electronicprescription" "electronicdispensation"
                       "medicalimaging" "medicaltestresults" "dischargereport"]]
      (let [s (m/fresh-store)
            [_ status] (m/handle-create s "PatientAccessRequest" (assoc access-request-sample :prioritycategory category))]
        (is (= 201 status) category))))
  (testing "restrictionappliedyn=true without restrictionreason is rejected"
    (let [s (m/fresh-store)
          [body status] (m/handle-create s "PatientAccessRequest"
                                          (assoc access-request-sample :restrictionappliedyn true))]
      (is (= 400 status))
      (is (re-find #"restrictionreason" (:message (:error body))))))
  (testing "restrictionappliedyn=true with a non-blank restrictionreason is accepted"
    (let [s (m/fresh-store)
          [rec status] (m/handle-create s "PatientAccessRequest"
                                         (assoc access-request-sample
                                                :restrictionappliedyn true
                                                :restrictionreason "Art. 23 GDPR patient-safety delay"))]
      (is (= 201 status))
      (is (true? (:restrictionappliedyn rec)))))
  (testing "prioritycategory is case-insensitive"
    (let [s (m/fresh-store)
          [rec status] (m/handle-create s "PatientAccessRequest" (assoc access-request-sample :prioritycategory "DISCHARGEREPORT"))]
      (is (= 201 status))
      (is (= "DISCHARGEREPORT" (:prioritycategory rec)))))
  (testing "a prioritycategory outside the Article 14(1) six-value enum is rejected"
    (let [s (m/fresh-store)
          [body status] (m/handle-create s "PatientAccessRequest" (assoc access-request-sample :prioritycategory "vitalsigns"))]
      (is (= 400 status))
      (is (re-find #"prioritycategory" (:message (:error body))))))
  (testing "update enforces the prioritycategory format check on a present field"
    (let [s (m/fresh-store)
          [rec _] (m/handle-create s "PatientAccessRequest" access-request-sample)
          [body status] (m/handle-update s "PatientAccessRequest" (:id rec) {:prioritycategory "vitalsigns"})]
      (is (= 400 status))
      (is (re-find #"prioritycategory" (:message (:error body))))))
  (testing "update enforces the accessmethod format check on a present field"
    (let [s (m/fresh-store)
          [rec _] (m/handle-create s "PatientAccessRequest" access-request-sample)
          [body status] (m/handle-update s "PatientAccessRequest" (:id rec) {:accessmethod "print"})]
      (is (= 400 status))
      (is (re-find #"accessmethod" (:message (:error body))))))
  (testing "update enforces the restriction cross-field rule against the merged record"
    (let [s (m/fresh-store)
          [rec _] (m/handle-create s "PatientAccessRequest" access-request-sample)
          ;; patch only restrictionappliedyn -- restrictionreason isn't in
          ;; this patch, but the *merged* record (existing reason-less
          ;; record + this patch) must still be checked, not just the raw
          ;; patch.
          [body status] (m/handle-update s "PatientAccessRequest" (:id rec) {:restrictionappliedyn true})]
      (is (= 400 status))
      (is (re-find #"restrictionreason" (:message (:error body))))))
  (testing "update accepts restrictionappliedyn=true when the patch also supplies a reason"
    (let [s (m/fresh-store)
          [rec _] (m/handle-create s "PatientAccessRequest" access-request-sample)
          [updated status] (m/handle-update s "PatientAccessRequest" (:id rec)
                                             {:restrictionappliedyn true
                                              :restrictionreason "Art. 23 GDPR patient-safety delay"})]
      (is (= 200 status))
      (is (true? (:restrictionappliedyn updated))))))

(deftest healthz
  (let [[body status] (m/healthz)]
    (is (= 200 status)) (is (= "athenahealth-compat" (:actor body))) (is (= (set m/entities) (set (:entities body))))))

#?(:clj (defn -main [& _]
          (let [{:keys [fail error]} (run-tests 'athenahealth.main-test)]
            (System/exit (if (pos? (+ fail error)) 1 0)))))
