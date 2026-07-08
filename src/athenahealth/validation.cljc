(ns athenahealth.validation
  "Structural (format-only) validators for identifiers this actor's own
  entity-specs carry but do not yet check the shape of -- today just the
  Provider `npi` field, which `athenahealth.main`'s `:coerce` map only
  type-converts (string/number -> int) without checking that the digits
  form an actual NPI.

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
  see the hl7-fhir.validation namespace docstring for the same caveat.")

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
