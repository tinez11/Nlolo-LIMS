# M8 — Reinsurance & Cessions: Design

**Status:** approved 2026-08-17. Precedes the implementation plan.

**Goal:** Build the `reinsurance` module — treaty configuration, automatic cession calculation on new business, and claim recovery tracking — satisfying `docs/08-implementation-roadmap.md:174`'s criterion: "Cession calculation tested against a sample treaty; recovery tracking tested against a sample claim."

---

## 1. Context findings that shaped this design

Four facts established by reading the codebase, not assumed:

1. **`reinsurance` may call only `refdata` synchronously.** `docs/02-module-architecture.md:175` and the existing `reinsurance/package-info.java` both pin `allowedDependencies = { refdata::api }`. The roadmap says M8 depends on M2/M3/M6 (product, policy, claims), so every one of those relationships must be event-driven. This is the same constraint that forced `distribution`'s local-projection design in M7.

2. **`RecoveryTriggered` does not exist.** `docs/01-domain-map.md:131` names it as the claims→reinsurance signal. The events `claims` actually publishes are `ClaimRegistered`, `ClaimAssessed`, `ClaimApproved`, `ClaimRejected`, `ClaimSettlementRequested`, `ClaimSettled`. The document names an event that was never built.

3. **The cession algorithm is a documentation void.** `docs/03-aggregate-design.md:165` reads in full: "### 7.4 `reinsurance` — unchanged from Rev 1." Rev 1 is not in this repository. No document on this platform defines how QUOTA_SHARE, SURPLUS or XOL compute a ceded amount. This is the same situation M7 met with commission semantics, and gets the same treatment: an explicit, flagged placeholder, never a silent guess.

4. **`reinsurance/V1` carries the recurring V1 defect set** — the same class this project has now caught in five consecutive modules (`claims`, `payment`, `billing`, `policyloan`, `distribution`): RLS enabled on only 1 of its 3 tables, zero `GRANT` statements anywhere in the file, no `version` columns, no money CHECK constraints, no uniqueness on either financial record, and no reinsurer identity on a treaty at all.

---

## 2. Approved decisions

These five were adjudicated before design and are settled. Do not re-litigate during planning or review.

1. **Full parity with M6/M7.** Domain + event-driven cession/recovery + REST + OpenAPI + contract tests + guardrail coverage + a `V2` hardening migration. `docs/04-api-contracts.md:21` deferred the REST surface as "indistinguishable in shape from `product`'s authoring endpoints"; it is built here because nothing else can author a treaty, and without it the module is inert in a real deployment.

2. **Recovery triggers on an enriched `ClaimSettled`.** `claims.ClaimSettled` gains `policyNumber` and `settledAmount`. Rationale: a recoverable is a real receivable from the reinsurer, and booking one against money that never left the platform overstates assets. `ClaimApproved` fires before the rail runs and a settlement can still fail (`PAYOUT_FAILED`/`IN_DOUBT` exist precisely for that). This is the `billing.PremiumCollected` precedent from M7 applied to the same shape of gap. The change is purely additive and has **zero code consumers today** (verified: only `audit`'s generic envelope listener observes it), and it also serves `finaccounting` (M9), which needs the settled amount for its journal posting.

3. **QUOTA_SHARE and SURPLUS cede at issuance; XOL does not.** XOL (excess-of-loss) is a claim-level treaty and does not produce a per-policy cession at issuance at all, so "automatic cession calculation on new business" does not apply to it. A treaty of type XOL is **rejected at cession time** with a clear message rather than given an invented issuance interpretation, and instead recovers at claim time as the excess of the loss over retention (§6). Same shape as M7's `THRESHOLD_BONUS` decision: implement what is well-defined, refuse to invent the rest, flag it loudly.

4. **The treaty gains `reinsurer_name`; recovery confirmation is manual.** A treaty without a named counterparty is meaningless, and `party` models no reinsurer party type to reuse. Recovery confirmation is a staff-triggered REST call that stamps `confirmed_at` and publishes `RecoveryConfirmed`. **No `payment` integration**: recovery is money *inbound*, `payment`'s collection path is built around premium collection from a payer MSISDN rather than a reinsurer bank settlement, and `reinsurance` cannot call `payment` synchronously anyway. Recorded as a deliberate boundary, not an oversight.

5. **One treaty per policy, most-recent-effective wins.** Select exactly one ACTIVE treaty whose effective window covers the policy's issue date (`effective_from <= issueDate AND (effective_to IS NULL OR effective_to >= issueDate)`); where several match, the newest `effective_from` wins, and a tie on that is broken by `treaty_id` so selection is fully deterministic rather than dependent on row order. Mirrors how `distribution` resolves an applicable commission plan. The multi-treaty layering hinted at by V1's own cession comment is deferred (see §8).

---

## 3. Architecture

`reinsurance` is a thick module depending on `refdata::api` and nothing else. Both business triggers arrive as events:

```
policy.PolicyIssued  ──► PolicyEventListener ──► policy_projection + Cession ──► CessionRecorded
claims.ClaimSettled  ──► ClaimEventListener  ──► ClaimRecovery              ──► RecoveryCalculated
(staff REST confirm) ──► ReinsuranceApiImpl  ──► confirmed_at               ──► RecoveryConfirmed
```

**Why a local projection.** Cession needs the policy's sum assured; recovery needs to find the policy's cession from a claim. `PolicyApi` is unreachable from here. `reinsurance.policy_projection` (policyNumber → sumAssured, currency, productId, issueDate) is built solely from `policy.PolicyIssued` and is this module's own state, deliberately not reconciled against `policy`. A policy issued before M8 has no projection row, so it cedes nothing and recovers nothing — correct, since those policies predate reinsurance tracking. Fail-silent-and-log, never fail-loud.

`reinsurance/api/package-info.java` is currently **missing** `@NamedInterface("api")`, exactly as `distribution`'s was entering M7. It is added here; without it any future module declaring `reinsurance::api` fails Modulith verification.

---

## 4. Schema — `reinsurance/V2`

`V1` is immutable (it has been applied). `V2` adds:

**Hardening the V1 defect set**
- RLS + tenant-isolation policies on `cession` and `claim_recovery` (V1 has RLS only on `reinsurance_treaty`)
- `GRANT USAGE ON SCHEMA` + `GRANT SELECT, INSERT, UPDATE, DELETE` to `app_role`, plus `ALTER DEFAULT PRIVILEGES` (V1 grants nothing at all)
- `version BIGINT NOT NULL DEFAULT 0` on `reinsurance_treaty` and `claim_recovery` (the two aggregate roots with real concurrent-write exposure; `cession` is write-once)
- Missing `tenant_id` indexes required by `docs/06-database-schema.md:25`

**Money and domain guards**
- `ceded_amount > 0`, `recoverable_amount > 0`, `retention_limit_amount >= 0`
- `cession_percent` constrained to `> 0 AND <= 100`, and required (`NOT NULL`) exactly when `treaty_type = 'QUOTA_SHARE'`
- `ux_cession_once` on `(tenant_id, policy_number, treaty_id)` and `ux_recovery_once` on `(tenant_id, claim_id, treaty_id)` — V1 has no uniqueness on either, so a redelivered event would duplicate a financial record

**New columns**
- `reinsurer_name VARCHAR(200) NOT NULL` on the treaty (with a backfill for any existing row, though none exist in practice)
- `status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','EXPIRED'))` on the treaty
- Audit columns (`created_by`, `updated_at`, `updated_by`) per `docs/06`'s convention

**New table** — `reinsurance.policy_projection` (composite PK `(tenant_id, policy_number)`, RLS, grants), mirroring `distribution.policy_projection`.

> **Column widths are checked against their CHECK vocabularies in the same migration.** M7 shipped a `CHECK` admitting `PAYOUT_REQUESTED` (16 chars) into a `VARCHAR(15)` column, making the whole payout path unwritable. `status VARCHAR(20)` and `treaty_type VARCHAR(15)` (longest value `QUOTA_SHARE`, 11) are both verified to fit here.

---

## 5. Cession flow

`PolicyEventListener` — `@Component("reinsurancePolicyEventListener")` (three modules already declare a `PolicyEventListener`), `@TransactionalEventListener(AFTER_COMMIT)`, one reusable `PROPAGATION_REQUIRES_NEW` `TransactionTemplate`, `TenantContext` save/set/restore. One transaction per handler: nothing here calls another module.

1. Write the projection row (unconditionally, before any treaty logic — it is the only place this module ever learns the sum assured).
2. Resolve the applicable treaty (§2.5). None → log INFO, return.
3. `CessionCalculator.calculate(treaty, sumAssured, currency)`:
   - **QUOTA_SHARE** → `cession_percent / 100 × sumAssured`, `HALF_UP` to 2dp
   - **SURPLUS** → `max(0, sumAssured − retentionLimit)`; a sum assured at or below retention cedes **nothing**, which is the correct and expected outcome, not an error
   - **XOL** → returns empty with a logged reason; XOL participates only in recovery
   - Currency mismatch between treaty and policy → cede nothing rather than convert (no FX table exists anywhere on this platform; inventing a rate is worse than not ceding)
4. Persist the cession; a zero result persists nothing (the `> 0` CHECK would reject it anyway, and "ceded nothing" is not a financial record).
5. Publish `CessionRecorded` **only when a row was actually written** — a redelivery writes nothing and so publishes nothing, consistent with every idempotent listener on this platform.

---

## 6. Recovery flow

**Producer change in `claims`** — `ClaimSettled`'s payload gains `policyNumber` and `settledAmount` alongside the existing `claimId`/`settledAt`. One publish site (`claims/application/PaymentEventListener`), both values already in scope there. `api/asyncapi-events.yaml` and `docs/05-event-catalog.md` updated to match, naming `reinsurance` as a consumer.

**`ClaimEventListener`** in `reinsurance` — same mechanics as above, bean-named `reinsuranceClaimEventListener`.

1. Resolve the policy's projection. None → a pre-M8 policy: log INFO, return.
2. Compute the recoverable, by which treaty applies. **Two distinct paths, because the two treaty families recover differently** — this is what "XOL participates only in recovery" (§2.3) means concretely:
   - **A cession exists** (QUOTA_SHARE or SURPLUS): proportional recovery — `settledAmount × (cededAmount / sumAssured)`, `HALF_UP` to 2dp.
   - **No cession, but an ACTIVE XOL treaty covers the policy's issue date**: excess-of-loss recovery — `max(0, settledAmount − retentionLimit)`. This needs no cession by construction, which is precisely why XOL produces none at issuance.
   - **Neither**: nothing is recoverable. Log INFO, return.
3. A zero or negative result persists nothing (a loss below an XOL retention is the ordinary case, not an error).
4. Currency mismatch between the treaty and the settled amount → recover nothing rather than convert, the same rule and the same reason as cession (§5).
5. Persist `claim_recovery` (guarded by `ux_recovery_once`), publish `RecoveryCalculated` only when a row was actually written.

**Both formulas are invented and flagged** — see §8.

**Confirmation** — `POST /claims/{claimId}/recoveries/{recoveryId}/confirm` stamps `confirmed_at` and publishes `RecoveryConfirmed` **only on the genuine transition**. Capture "was already confirmed" *before* mutating, so a repeat call emits no second event — M6's I1 finding applied preemptively, since the declared consumer is `finaccounting` where a duplicate is a double journal entry.

---

## 7. REST surface and testing

**`api/openapi/openapi-reinsurance.yaml`** (new — none exists today). All endpoints staff-only, gated `hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))`: there is no reinsurance-specific staff role, and this is the same decision recorded in M7 for the same reason.

| Endpoint | Purpose |
|---|---|
| `POST /treaties` | Author a treaty (`Idempotency-Key` required) |
| `GET /treaties/{treatyId}` | Read one |
| `GET /treaties` | List, optionally filtered by `status` |
| `GET /policies/{policyNumber}/cessions` | Cessions for a policy |
| `GET /claims/{claimId}/recoveries` | Recoveries for a claim |
| `POST /claims/{claimId}/recoveries/{recoveryId}/confirm` | Confirm receipt (`Idempotency-Key` required) |

Money **and `cessionPercent`** are decimal strings on the wire, never JSON numbers (`docs/06-database-schema.md:32`; a percent multiplies money exactly as a commission rate does). Every nested path verifies the two ids actually belong together before acting — the IDOR class found in M7's final review.

**Tests**
- `CessionCalculatorTest` — pure, container-free: QS, SURPLUS above/at/below retention, XOL rejection at issuance, currency mismatch, rounding
- `RecoveryCalculatorTest` — pure: proportional recovery from a cession, XOL excess-over-retention with no cession, a loss below XOL retention recovering nothing, currency mismatch
- `TreatySelectionTest` — overlapping windows, expired treaties, no match
- `CessionEndToEndTest` — issues a real policy via `PolicyApi` against real Postgres as `app_role`, asserts real rows
- `RecoveryEndToEndTest` — drives a real claim through to settlement, asserts the recovery and its confirmation, including that a redelivery publishes no second event
- `ReinsuranceContractTest` — one test per reachable status, `openApi().isValid(...)` **paired with** `SpecTypeConformance.matchesDeclaredTypes(...)` on every decimal-carrying response
- `reinsurance` added to `AppRolePrivilegesIntegrationTest` (insert/read/**update**) and `RowLevelSecurityIntegrationTest` (cross-tenant invisibility on `cession` and `claim_recovery`)

---

## 8. Flagged for sign-off, and deliberate deferrals

**Invented — needs Actuarial/Reinsurance sign-off before production use.** Every item below carries a code comment saying so, exactly as M2 did for the underwriting rules engine and M7 for commission:
- The entire cession algorithm (both implemented tier types)
- The recovery share formula (`settledAmount × cededAmount / sumAssured`) — proportional recovery is the ordinary treaty convention, but no document on this platform states it
- Treaty selection (one treaty, newest effective window)
- Ceding on sum assured rather than premium — a real quota-share treaty typically cedes *premium* as well as *risk*, and no ceded-premium concept exists here at all

**Deferred deliberately, recorded so the next reader does not rediscover them:**
- **XOL cession at issuance** — conceptually wrong; XOL is claim-level, and it does recover at claim time (§6), so XOL is *partially* supported rather than absent
- **Multi-treaty layering** — V1's own comment implies cumulative cessions across treaties; needs a treaty priority/ordering column V1 lacks and an ordering rule no document defines
- **Product-scoped treaties** — invents a schema dimension no document mentions
- **Reinsurer as a `party`** — needs a party type that does not exist and a dependency edge the architecture withholds
- **Payment integration for recovery settlement** — inbound money is a different flow from anything `payment` models today
- **Ceded premium** — only ceded *risk* is modelled; `finaccounting` (M9) will likely need ceded premium for IFRS 17

---

## 9. Acceptance criterion mapping

`docs/08-implementation-roadmap.md:174` — "Cession calculation tested against a sample treaty; recovery tracking tested against a sample claim":
- **Cession calculation** → `CessionCalculatorTest` + `CessionEndToEndTest` (a real issued policy against a real QUOTA_SHARE and a real SURPLUS treaty)
- **Recovery tracking** → `RecoveryEndToEndTest` (a real claim driven to `SETTLED`, its recovery calculated, confirmed, and its events asserted)
