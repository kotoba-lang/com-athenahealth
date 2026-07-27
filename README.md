# Athenahealth Clean Room Actor

This actor provides a clean-room, API-compatible implementation of the Athenahealth platform.

## Architecture
- **State:** Backed by Datomic for immutable, time-travel-capable record keeping.
- **Schema:** Defined in `schema/athenahealth.kotoba`.
- **Execution:** Runs in `Py Kotodama WASM`, intercepting inbound REST requests.

## Provenance

Relocated 2026-07-04 from `etzhayyim/root/20-actors/athenahealth-compat` to
`kotoba-lang/com-athenahealth` per the org-taxonomy library-placement rule (any
library/substrate code belongs in `kotoba-lang`, ADR-2606302300), following
the same relocation pattern as `kami-nv-compat` (ADR-2607020130). See
ADR-2607041500 for the full ~1,027-repo migration plan and naming convention.

## Maturity note (2026-07-08) -- EU: GDPR Art. 9(2) lawful basis (`Consent`)

As relocated, this repo was a `deepen_actors.py`-generated generic CRUD
scaffold with vendor-specific (real athenahealth API) field names and no
domain validation beyond `commit 8dd1193`'s Provider NPI check digit (45 CFR
162.410). Its `com-hl7-fhir` / `com-epic-fhir` / `com-eclinicalworks`
siblings had already reached feature parity on three entities -- `Claim`
(US billing identifiers), `Consent` (EU GDPR Art. 9 lawful basis) and
`PatientAccessRequest` (EU EHDS Art. 3 access rights) -- while this repo had
only the NPI check.

This pass closes part of that gap: a `Consent` entity recording, per
patient, which of the ten exceptions in **Art. 9(2)(a)-(j) of Regulation
(EU) 2016/679 (GDPR)** is relied on to lift the Art. 9(1) prohibition on
processing "data concerning health" -- every clinical record this actor
models (`Patient`/`Encounter`/`Claim`) is exactly that category of data.
Field names follow this actor's own existing vendor-style convention
(lowercase, abbreviated, no camelCase) rather than the FHIR-style names used
in the sibling repos:

- `patientid` (int, required) -- links to the same numeric identifier the
  `Patient` entity itself carries.
- `specialcategorydatayn` (boolean, required) -- explicit machine-readable
  flag that the associated record(s) are Art. 9(1) special-category data,
  following this actor's existing `...yn` boolean-flag naming convention
  (e.g. `homeboundyn`, `donotcallyn`).
- `lawfulbasisart9` (string, required) -- one of `"a"`..`"j"`
  (case-insensitive), validated against
  `athenahealth.validation/valid-gdpr-art9-lawful-basis?`. An unrecognized
  code (e.g. `"z"`, or the exception's full label instead of its
  point-letter) is rejected with 400 by `handle-create`/`handle-update`,
  the same `:validate` entity-spec wiring the `Provider` `npi` field uses.
- `status` (string, optional) -- generic record status, matching the plain
  `status` field already used by the `Encounter` entity.

**Provenance**: the Art. 9(2)(a)-(j) point-letter structure, the paraphrased
label for each point, and the validator/entity design are ported *by value*
(not a shared dependency -- each actor stays a standalone deploy unit) from
`kotoba-lang/com-hl7-fhir`'s `Consent` entity (ADR-2607083100), which checked
the text against EUR-Lex (CELEX:32016R0679) and gdpr-info.eu on 2026-07-08.
See `src/athenahealth/validation.cljc` (`gdpr-art9-2-lawful-bases` /
`valid-gdpr-art9-lawful-basis?`) and `test/athenahealth/main_test.cljc`'s
`consent-domain-validation` deftest for pass/fail coverage (all ten
point-letters accepted, an out-of-set code and the full exception label both
rejected, boolean coercion, and that `handle-update` enforces the same
check). `bb test`: 7 deftests / 169 assertions as of this pass.

**Not done in this pass** (left for a follow-up increment, one topic at a
time): **`PatientAccessRequest`** (EU EHDS, Regulation (EU) 2025/327 Article
3 primary-use access rights) -- the sibling repos' fourth entity -- was not
added here, so this repo is now at Claim(pre-existing scaffold, unvalidated)
+ Provider(NPI) + Consent(GDPR) while the three FHIR-named siblings are at
Claim(validated) + Consent(GDPR) + PatientAccessRequest(EHDS). Per the same
scope discipline the sibling repos follow: EHDS Article 14 (priority
categories list) and Article 15 (exchange-format schema) have **not** been
retrieved from a primary source anywhere in this repo family yet, so a
future `PatientAccessRequest` pass here would likewise model only Article
3(1)-(3) (a boolean `priorityCategory`-equivalent flag, an `accessMethod`
enum of `view`/`download`, and the restriction/reason cross-field rule) --
see `orgs/kotoba-lang/com-hl7-fhir/README.md`'s equivalent section for the
full citation trail. `manifest.json`/`openapi.json` were intentionally left
unchanged, same rationale as the sibling repos: they are paired with a
specific built `wasmCid`, and hand-editing their `entities`/`routes` lists
without rebuilding that WASM artifact would make the manifest less accurate,
not more.

## Maturity note (2026-07-08) -- EU: EHDS Article 3 (Regulation (EU) 2025/327), `PatientAccessRequest`

Follow-up closing the item the pass above explicitly deferred. Adds a
`PatientAccessRequest` entity modeling **Article 3 ("Right of natural
persons to access their personal electronic health data"), paragraphs
(1)-(3) only** -- the only EHDS text with a verified primary-source reading
to hand (EUR-Lex blocks automated fetches with an AWS WAF JS challenge; the
operative text was retrieved via a real-browser session on 2026-07-08 and
is archived, with full retrieval-method provenance, at
[`kotoba-lang/emr-claims-primary-sources`](https://github.com/kotoba-lang/emr-claims-primary-sources)'s
`eu-ehds/ehds-article3-excerpt.md`, CELEX:32025R0327). **Article 14 (the
priority-categories list) and Article 15 (the exchange-format schema) had
not been retrieved from a primary source and were explicitly out of scope
for this pass** -- inventing either would have been exactly the kind of
unverified-legal-content guess this codebase's working agreement forbids.
Both have since been retrieved; see the 2026-07-09 maturity note below.

Design and validator logic are ported *by value* (not a shared dependency)
from `kotoba-lang/com-hl7-fhir`'s `PatientAccessRequest` entity
(ADR-2607083200), with field names adapted to this actor's own vendor-style
convention -- lowercase, `...yn` boolean-flag suffix (as `Consent`'s
`specialcategorydatayn` already established) -- rather than the sibling
repos' FHIR-style camelCase:

- `patientid` (int, required) -- same convention as `Consent`/`Patient`.
- `prioritycategory` (enum, one of 6 Article 14(1)(a)-(f) values, see the
  2026-07-09 maturity note below -- was `prioritycategoryyn`, a bare boolean
  flag, at the time this section was originally written, before Article 14
  had been retrieved).
- `accessmethod` (enum `"view"` / `"download"`, case-insensitive, required)
  -- `"view"` is Art. 3(1) (immediate, free, easily-readable/consolidated
  access once data is registered in an EHR system); `"download"` is
  Art. 3(2) (a free electronic copy in the European electronic health
  record exchange format, Article 15 -- named only as an external citation
  here, not modeled as a data structure). Validated by
  `athenahealth.validation/valid-ehds-access-method?`; anything else is
  rejected with 400.
- `restrictionappliedyn` (boolean) + `restrictionreason` (optional string)
  -- Art. 3(3): a Member State may restrict/delay both rights "in
  accordance with Article 23" of GDPR (Regulation (EU) 2016/679), e.g. for
  patient-safety/ethical reasons. Enforced by a new **cross-field**
  validator, `athenahealth.validation/valid-ehds-restriction?`, wired
  through a new entity-spec key `:validate-record` (complementing the
  existing single-field `:validate`, and newly plumbed into this actor's
  `handle-create`/`handle-update` in this pass -- previously only
  single-field `:validate` existed here): `restrictionappliedyn=true` with
  a blank/absent `restrictionreason` is rejected with 400 on both create
  and update (update checks the *merged* record, so patching only
  `restrictionappliedyn` against an existing reason-less record is still
  caught).

See `src/athenahealth/validation.cljc` (`valid-ehds-access-method?` /
`valid-ehds-restriction?`, with the scope caveats inline) and
`test/athenahealth/main_test.cljc`'s `patient-access-request-domain-validation`
deftest for pass/fail coverage (both access methods and case-insensitivity
accepted, an out-of-set method rejected, a restriction without a reason
rejected on both create and merged update, a restriction with a reason
accepted). `bb test`: 8 deftests / 204 assertions as of this pass (up from
7/169).

**4-repo status**: this closes the gap noted above -- `com-hl7-fhir` /
`com-epic-fhir` / `com-eclinicalworks` / `com-athenahealth` now all model
the same three entities (`Claim`, `Consent`, `PatientAccessRequest`), each
with vendor-appropriate field naming (FHIR camelCase for the first three,
athenahealth's own lowercase/`...yn` convention here). `manifest.json` /
`openapi.json` were intentionally left unchanged, same rationale as above.

## Maturity note (2026-07-09) -- EU: EHDS Article 14 priority categories (Regulation (EU) 2025/327)

Follow-up closing the item the 2026-07-08 pass above explicitly deferred,
same pass as `com-hl7-fhir` / `com-epic-fhir` / `com-eclinicalworks`.
Article 14 ("Priority categories of personal electronic health data for
primary use") and the referenced part of Article 15 were retrieved via the
same real-browser EUR-Lex method and are archived at
[`kotoba-lang/emr-claims-primary-sources`](https://github.com/kotoba-lang/emr-claims-primary-sources)'s
`eu-ehds/ehds-article14-15-excerpt.md`.

- `prioritycategoryyn` is renamed `prioritycategory` and is now a closed
  6-value enum matching **Article 14(1)(a)-(f) verbatim**, glued lowercase
  per this actor's own field-naming convention (matching `accessmethod`'s
  `"view"`/`"download"` values, not the sibling repos' kebab-case):
  `patientsummary`, `electronicprescription`, `electronicdispensation`,
  `medicalimaging`, `medicaltestresults`, `dischargereport`. The `...yn`
  suffix is dropped because it is this actor's boolean-flag convention,
  which no longer fits a 6-value enum. Case-insensitive, validated by
  `athenahealth.validation/valid-ehds-priority-category?`; not required
  (unlike `accessmethod`), but any *present* value outside the six is
  rejected with 400. Annex I's per-category "main characteristics" and
  Article 14(1)'s allowance for Member States to add national categories are
  both **not** modeled (out of scope for this pass).
- **`accessmethod` / Article 15 stays a citation only.** Article 15 does
  *not* itself define the concrete technical schema of the exchange format
  -- it delegates that to future European Commission implementing acts, not
  yet published -- so `accessmethod` continues to only name the format by
  reference, with no exchange-format data structure added.

See `src/athenahealth/validation.cljc` (`ehds-priority-categories` /
`valid-ehds-priority-category?`) and
`test/athenahealth/main_test.cljc`'s `patient-access-request-domain-validation`
deftest for pass/fail coverage (all six categories and case-insensitivity
accepted, an out-of-set value rejected on both create and update). `bb
test`: 9 deftests / 358 assertions as of this pass (up from 8/204).
