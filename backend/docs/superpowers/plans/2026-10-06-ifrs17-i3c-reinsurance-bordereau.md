# IFRS 17 I3c — Reinsurance on the Monthly Bordereau Implementation Plan

> Inline execution, no subagents (user preference). Steps use checkbox syntax.

**Goal:** Reinsurance held posts what the guide says (K-01, K-02, B-05) from a monthly bordereau per treaty instead of
posting the ceded sum assured at activation, and recoveries post at claim approval to 6120.

**Architecture:** `reinsurance` keeps its own record of when each ceded policy is in force and premium-paying
(policy events only — `policy` is not a dependency), and a month-end job builds one bordereau per treaty and month,
publishing `reinsurance.BordereauPosted`; `finaccounting` posts it by rule. Recoveries are calculated on
`claims.ClaimApproved` and published as `reinsurance.RecoveryCalculated`, which posts B-05.

**Tech stack:** Spring Boot / Modulith, JPA + JdbcTemplate, Postgres (RLS, SECURITY DEFINER drain function),
posting-rules.yaml, React console.

## Global constraints (user answers 2026-10-06, all the recommended option)

- Q1 ceded premium on ORIGINAL TERMS: cession share × the policy's own premium, as a monthly amount.
- Q2 a FULL month for every month cover is in force at any point; no day pro-rata; stops after the month cover
  ends; restarts on reinstatement.
- Q3 treaty field `commissionPercent` (not contingent on claims), required at creation, 0 allowed; existing treaties 0.
- Q4 recovery calculated and posted at claim APPROVAL: Dr 1420 / Cr 6120, with the policy's dimensions; no Confirm step.
- Q5 XOL treaty: optional `xolAnnualPremium`, 1/12 on each monthly bordereau.
- Q6 quarterly statement (R-01/R-03/R-04) is NOT in I3c — right after I4.
- Q7 the wrong dev K-01 journals are removed by a dev-only script.
- Group and credit-life schemes are never ceded nor recovered against (client 2026-09-22) — unchanged.

## Design decisions made while planning (derived from the answers; flag in the merge message)

- D1 **Premium-paying, not just in force.** On original terms the reinsurer's premium follows the policy's premium,
  so a month is charged only while premiums are payable: `policy.PolicyMadePaidUp` and `policy.PremiumsEnded` stop
  the charge from the following month (cover — and recovery — continue).
- D2 **SINGLE premium** policies are charged once, the share of the single premium, in the month cover first started.
- D3 **Frequency → monthly:** MONTHLY ×12/12, QUARTERLY ×4/12, ANNUALLY ×1/12 of the instalment.
- D4 **Premium changes** (`policy.PremiumRestated`) update the projection's premium; a bordereau uses the premium
  held when it is built (month end), not a dated premium history.
- D5 **Free-look cancellation voids cover from inception:** its cover periods are deleted, so unposted months are not
  charged. A month already posted is not reversed by the platform — it is agreed on the statement.
- D6 **Share of premium** = the share of risk ceded: quota share = its percent; surplus = ceded risk ÷ sum assured.
  Stored on the cession (`premium_share`), backfilled for existing rows.
- D7 **Recovery is on the insured part only:** approved amount less the investment component (IC is not
  reinsured). Quota share/surplus: insured × ceded ÷ sum assured, capped at the ceded amount; XOL: insured − retention.
- D8 **One journal per treaty and month**, dated the month's last day (so it lands in that period). K-01 and K-02 are
  one rule (one event → one rule): Dr 1436/Cr 1430 premium, Dr 1431/Cr 1436 commission. Recoveries listed on the
  bordereau are only matched (K-03: no posting).
- D9 A bordereau is final once posted. The job posts every closed month not yet posted from the treaty's
  effective-from; the console shows them and previews the current month (computed, not stored).
- D10 Lines whose premium currency differs from the treaty's are left off (no FX table — same rule as cession).

## File map

**Backend — reinsurance**
- Create `db-migrations/reinsurance/V5__bordereau.sql`: treaty `commission_percent` (NOT NULL, 0..100, existing 0),
  `xol_annual_premium` (XOL only, > 0); cession `premium_share`; projection `premium_frequency`,
  `premiums_end_on`; tables `cover_period`, `bordereau`, `bordereau_line` (RLS NULLIF, grants);
  `bordereaux_due(date)` SECURITY DEFINER; backfill from `policy.policy` when that table exists.
- Modify `domain/ReinsuranceTreaty`, `domain/Cession`, `domain/PolicyProjection`, `domain/CessionCalculator`
  (share), `domain/RecoveryCalculator` (insured part).
- Create `domain/BordereauCalculator` (pure: lines + totals from inputs), `domain/CoverPeriod`.
- Create `application/BordereauJob` (scheduled drain + `runFor(tenant, treaty, period)`), `application/Bordereaux`
  (JDBC store/reads).
- Modify `application/PolicyEventListener` (activation opens a cover period and records frequency; lapse,
  surrender, maturity, expiry, annuity end close it; reinstatement reopens; free-look voids; paid-up and premiums
  ended set `premiums_end_on`; premium restated updates premium).
- Modify `application/ClaimEventListener` (ClaimApproved instead of ClaimSettled; insured part).
- Modify `application/ReinsuranceApiImpl` + `api/ReinsuranceApi` (treaty fields; bordereau list/get/preview;
  `confirmRecovery` removed), `api/TreatyView`, `api/ClaimRecoveryView` (confirmedAt stays for history),
  new `api/BordereauView`, `api/BordereauNotFoundException`.
- Modify `infrastructure/TreatyController`, `CreateTreatyRequestDto`, `TreatyResponseDto`, `RecoveryController`
  (no confirmation endpoint); create `BordereauController`; exception handler.
- Modify `api/openapi/openapi-reinsurance.yaml`, `api/asyncapi-events.yaml`.

**Backend — finaccounting**
- `posting-rules.yaml` v3: K-01 on `reinsurance.BordereauPosted` (premium + commission lines, models [NONE]);
  B-05 on `reinsurance.RecoveryCalculated` (Dr 1420 / Cr 6120, models [ANY]); CessionRecorded and
  RecoveryConfirmed rules removed.
- `PostingFactsExtractor`: shapes and cases for the two events (bordereau dated its month end); old two removed.

**Dev**
- `scripts/dev/i3c-remove-interim-reinsurance-journals.sql` (decision 7) — also the RecoveryConfirmed journals that
  credited 5110.

**Console**
- Treaty form: commission %, XOL annual premium. Treaty detail: the two fields; a Bordereaux section (month,
  policies, premium, commission, recoveries matched, journal link) and the current month's preview; bordereau page
  with its lines. Recoveries panel: no Confirm; "Posted at approval".

**Tests**
- Unit: `BordereauCalculatorTest`, `CessionCalculatorTest` (share), `RecoveryCalculatorTest` (insured part).
- Integration: `BordereauIntegrationTest` (events → cover periods → job → bordereau + BordereauPosted, idempotent,
  XOL, paid-up, reinstatement, free-look, scheme excluded), `RecoveryEndToEndTest` (approval path, redelivery),
  `CessionEndToEndTest`, `ReinsuranceApiIntegrationTest`, `ReinsuranceContractTest`,
  `ReinsuranceAndLoanPostingEndToEndTest` (K-01 premium not sum assured; B-05 to 6120), `PostingRulesTest`,
  `PostingFactsExtractorTest`, ModularityTests; migration lists via `scripts/dev/append-test-migration.mjs`.
- e2e `staff-reinsurance.spec.ts`: commission field and the bordereaux section.

## Tasks

- [ ] T1 V5 migration + entities + calculators (+ unit tests)
- [ ] T2 Listeners: cover periods, premium share, recovery at approval (+ integration tests)
- [ ] T3 Bordereau job, store, API, controller, OpenAPI/AsyncAPI (+ integration/contract tests)
- [ ] T4 Posting rules v3 + extractor (+ PostingRulesTest, extractor test, posting end-to-end)
- [ ] T5 Console (api, store, treaty form/detail, bordereau page, recoveries panel) + vitest + e2e
- [ ] T6 Dev script; apply V5 to the dev DB; run the affected classes; full e2e; merge
