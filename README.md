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
