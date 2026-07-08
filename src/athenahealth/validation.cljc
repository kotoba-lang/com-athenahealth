(ns athenahealth.validation
  "Structural (format-only) validators for identifiers this actor's own
  entity-specs carry but do not yet check the shape of -- today the
  Provider `npi` field, which `athenahealth.main`'s `:coerce` map only
  type-converts (string/number -> int) without checking that the digits
  form an actual NPI, and the Consent `lawfulbasisart9` field, which names
  one of the ten EU GDPR Art. 9(2) special-category-data lawful-basis
  exceptions.

  `valid-npi?` is a straight algorithmic port (not a runtime dependency)
  of `hl7-fhir.validation/valid-npi?` in kotoba-lang/com-hl7-fhir, which
  implements the National Provider Identifier check-digit rule from 45 CFR
  162.410 (the CMS NPI Final Rule): 10 digits, last digit is the ISO/IEC
  7812-1 Luhn check digit computed over the fixed issuer prefix \"80840\"
  followed by the first 9 digits. Ported by value (each actor stays a
  standalone deploy unit) rather than by adding a cross-repo dependency,
  so both actors agree on what counts as a structurally valid NPI.

  This is a *format* check, not an NPPES authority check: a structurally
  valid NPI is not guaranteed to be an NPI actually assigned by NPPES --
  see the hl7-fhir.validation namespace docstring for the same caveat.

  `gdpr-art9-2-lawful-bases` / `valid-gdpr-art9-lawful-basis?` are likewise
  ported by value from `hl7-fhir.validation` (kotoba-lang/com-hl7-fhir,
  ADR-2607083100): the ten Art. 9(2)(a)-(j) exceptions of Regulation (EU)
  2016/679 that lift the Art. 9(1) prohibition on processing \"data
  concerning health\", checked against EUR-Lex (CELEX:32016R0679) and
  gdpr-info.eu on 2026-07-08. This checks only that a *code* naming one of
  the ten exceptions is well-formed, not that the cited exception is
  factually true for a given record -- same authority-vs-format caveat as
  `valid-npi?` above."
  (:require [clojure.string :as str]))

(defn- digit-char? [c] (contains? #{\0 \1 \2 \3 \4 \5 \6 \7 \8 \9} c))

(defn- char->digit [c] (- (int c) (int \0)))

(defn- luhn-check-digit
  "ISO/IEC 7812-1 Luhn check digit for a numeric-string payload (the
  payload does not include the check digit itself)."
  [numeric-str]
  (let [ds (reverse (map char->digit numeric-str))
        total (reduce + (map-indexed
                          (fn [i d]
                            (if (even? i)
                              (let [d2 (* d 2)] (if (> d2 9) (- d2 9) d2))
                              d))
                          ds))]
    (mod (- 10 (mod total 10)) 10)))

(defn valid-npi?
  "true if s is exactly 10 digits whose final digit is the correct Luhn
  check digit over (\"80840\" ++ first 9 digits). 1234567893 is the
  canonical structurally-valid NPI used across the industry for tests.

  Unlike hl7-fhir.validation/valid-npi? (which only accepts strings), this
  version also accepts integers: this actor's own `:coerce {:npi :int}`
  already treats a request's `npi` field as string-or-number
  interchangeably (see `athenahealth.main/as-int`), and this validator
  runs on that same pre-coercion field, so it accepts whatever shape that
  field is willing to coerce rather than introducing a stricter rule the
  rest of the actor doesn't otherwise enforce."
  [s]
  (let [digits (cond (string? s) s (integer? s) (str s) :else nil)]
    (boolean
     (when (and digits (= 10 (count digits)) (every? digit-char? digits))
       (let [first9 (subs digits 0 9)
             check-digit (char->digit (nth digits 9))]
         (= check-digit (luhn-check-digit (str "80840" first9))))))))

;; --- GDPR Art. 9(2) special-category-data lawful basis ----------------------
;;
;; Ported by value from hl7-fhir.validation (kotoba-lang/com-hl7-fhir,
;; ADR-2607083100) -- see that namespace's docstring for the full
;; provenance/accuracy note. Every Patient/Encounter/Claim record this
;; actor models carries "data concerning health" within the meaning of
;; Art. 9(1) of Regulation (EU) 2016/679 (GDPR, OJ L 119, 4.5.2016, p. 1),
;; which prohibits processing such data unless one of the ten exceptions in
;; Art. 9(2)(a)-(j) applies. This actor's `Consent` entity records, per
;; patient, which of those ten exceptions is relied on.

(def gdpr-art9-2-lawful-bases
  "The ten Art. 9(2) exceptions that lift the Art. 9(1) prohibition on
  processing special categories of personal data, keyed by the same
  point-letter the Regulation itself uses. Map values are short paraphrased
  labels for lookup/display, not the operative legal text -- see Article 9
  of Regulation (EU) 2016/679 for the authoritative wording."
  {"a" "explicit consent to processing for one or more specified purposes"
   "b" "obligations/rights in employment, social security or social protection law"
   "c" "vital interests, where the data subject is physically or legally incapable of consenting"
   "d" "not-for-profit body (political/philosophical/religious/trade-union aim), members/regular contacts only, with safeguards"
   "e" "personal data manifestly made public by the data subject"
   "f" "establishment, exercise or defence of legal claims, or courts acting in a judicial capacity"
   "g" "substantial public interest under proportionate Union or Member State law"
   "h" "preventive or occupational medicine, medical diagnosis, health/social care or treatment, or management of health/social-care systems, under law or a contract with a health professional"
   "i" "public health (e.g. serious cross-border health threats, quality/safety of health care, medicinal products or medical devices)"
   "j" "archiving in the public interest, scientific/historical research, or statistical purposes per Art. 89(1)"})

(defn valid-gdpr-art9-lawful-basis?
  "true if s (case-insensitive) is one of the ten Art. 9(2) point-letters
  (\"a\".. \"j\") that can lift the Art. 9(1) special-category-data
  prohibition. Only the code's membership in that fixed ten-item set is
  checked -- see the namespace-level caveat above about what this does and
  doesn't guarantee."
  [s]
  (boolean (and (string? s) (contains? gdpr-art9-2-lawful-bases (str/lower-case s)))))
