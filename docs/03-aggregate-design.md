# Deliverable 3 — Aggregate Design (Revision 2)
**Digital Life Insurance Core Platform — Tanzania (Phase 0, Artifact 3 of 8)**

> Supersedes Revision 1. Resolves your full 🔴/🟡/🟢/🟣 review. Blocking items B1–B4 are resolved as configurable, structurally-safe placeholders (same pattern as C1's `finaccounting` treatment) so Deliverable 4 isn't held hostage by values that Legal/Product/Actuarial haven't confirmed yet — each is flagged **[VERIFY]** with the specific parameter or field that will absorb the eventual real answer without a redesign.

---

## 0. Revision Log

| # | Item | Resolution |
|---|---|---|
| B1 | Reinstatement window | **Resolved as a `refdata` parameter** (`TZ_REINSTATEMENT_WINDOW_MONTHS`), same externalization pattern as contestability/grace period. Placeholder value, not a guess baked into code. |
| B2 | Interest rate floating vs. locked | **Resolved structurally per L1** — `interestRate` becomes a tracked value with `effectiveFrom`, supporting either policy without a later redesign. |
| B3 | Fixed schedule vs. ad hoc repayment | **Resolved structurally per L2** — `RepaymentSchedule` is now `optional`; ad hoc repayment is always supported via the existing `LoanTransaction` ledger regardless. |
| B4 | Suspended vs. Lapsed distinction | **Resolved** using your suggested definition, applied as the working state machine (flagged for final product sign-off, not re-opened as a blocker). |
| C1–C3 | External inputs | Unchanged — still tracked, not blocking. |
| P1, P2 | Product currency/version dates | Applied. |
| U1, U2 | Underwriting referral workflow, contestability externalization | Applied. |
| Po1–Po4 | Policy events, beneficiary typing, in-force check, lapsed→surrendered | Applied. |
| L1–L3 | Loan interest/schedule/status design | Applied. |
| Bi1, Bi2 | Dunning levels, next-due convenience query | Applied. |
| Cl1–Cl3 | Fraud flag, sealed `ClaimDetails`, maturity auto-approval documentation | Applied. |
| Pa1 | Group membership scale | **Applied now, not deferred** — this is a real aggregate-size problem, addressed below. |
| Di1 | Commission rule sets | Applied — `CommissionPlan` promoted to its own aggregate. |
| Pay1, Pay2 | Settlement batch, idempotency-key status lookup | Applied. |
| A1, A2 | Audit dependency clarity, DB-level revoke | A1 applied here; A2 logged for Deliverable 6 DDL. |
| IC1, IC2 | Indexing conventions | Added to §0.5. |
| IC3, IC4 | Camunda process variable contract, compensation handlers | Added as new §12. |
| IC5 | Transactional event publishing | Unchanged — already mandatory since Deliverable 2 Rev 2. |

---

## 0.5 Persistence & Indexing Conventions (additions to Rev 1's §0)

- **IC1:** every column holding a cross-module opaque ID (`party_id`, `policy_number`, `agent_of_record_id`, `product_id`, `product_version_id`, etc.) gets an explicit B-tree index — these are the join points application-layer code relies on in place of FKs, so an unindexed one is a silent performance cliff waiting to happen.
- **IC2:** `tenant_id` is indexed on every table, typically as the leading column of a composite index (`tenant_id, <natural lookup key>`) since every query filters by tenant first.

---

## 1. `product` — ProductDefinition Aggregate (updated: P1, P2)

- `ProductDefinition` gains `defaultCurrency` (TZS today; positions the platform for Kenya/Uganda expansion without a schema change later).
- `ProductVersion` gains `effectiveDate` and `retirementDate` — needed for audit and for `regreporting` to answer "which version was in force on date X" precisely, not just "which version is current."
- Everything else unchanged from Rev 1 (immutable versions, single active version per product, `ifrsMeasurementModel` mandatory before leaving `DRAFT`, full rating-factor coverage validated at publish, fund definitions restricted to `UNIT_LINKED`).

---

## 2. `underwriting` — UnderwritingCase Aggregate (updated: U1, U2)

- New field: `referralStatus` (`NONE` / `REFERRED_TO_SENIOR` / `REFERRAL_RESOLVED`) — tracked **separately** from `decision.outcome`, so a borderline case under senior review doesn't need a fake interim `outcome` value to represent "not decided yet, but not exactly open either."
- `checkContestability()` continues to source the contestability window from `ReferenceDataApi.getCodes("TZ_CONTESTABILITY_MONTHS")` (confirmed already in Deliverable 2 Rev 2 — cross-referenced here per U2, no change needed).
- Everything else unchanged from Rev 1.

---

## 3. `policy` — Policy Aggregate (updated: B1, B4, Po1–Po4 — the most substantial revision)

**State machine, fully specified (resolves B4):**

```
PROPOSED → ACTIVE
ACTIVE → LAPSED            (premium arrears exhaust grace period)
ACTIVE → SUSPENDED         (temporary hold: admin/investigation/group-scheme non-payment — coverage paused, billing paused, NOT an arrears event)
SUSPENDED → ACTIVE         (hold resolved / resumed)
SUSPENDED → LAPSED         (hold not resolved within [VERIFY: suspension-to-lapse window, a further refdata parameter])
LAPSED → REINSTATED → ACTIVE   (within TZ_REINSTATEMENT_WINDOW_MONTHS, a new refdata parameter — resolves B1)
LAPSED → SURRENDERED       (Po4 — allowed when PolicyAccount cash value net of outstanding arrears is positive; a lapsed policy with real remaining value shouldn't be forced into a pure write-off if the customer/agent elects to surrender instead)
ACTIVE → SURRENDERED / MATURED   (terminal, via the Deliverable 2 §3.4 loan-netting choreography)
```

`SUSPENDED` is distinct from `LAPSED` exactly as you framed it: `SUSPENDED` is a temporary, non-arrears hold (coverage paused, no premium billing generated during the hold) — typically applicable to group/SACCO schemes pending an administrative resolution; `LAPSED` is specifically the premium-arrears termination path. **[VERIFY with Product]** which product categories support `SUSPENDED` at all (my working assumption: group life / education savings group schemes; individual term/endowment likely go straight to `LAPSED`) — flagged rather than guessed.

**Events (Po1 — cross-referencing, no new event needed):** `PolicySuspended` was already added to the event catalog in Deliverable 2 Rev 2; this revision just confirms the state machine transitions that produce and consume it (`billing` pauses schedule generation on `PolicySuspended`, resumes on the reverse transition).

**Beneficiary typing (Po2):**
```java
public class Beneficiary {
    BeneficiaryType type; // PARTY | FREEFORM
    PartyId partyId;      // required iff type == PARTY, else null
    String freeformDesignee; // e.g. "estate"; required iff type == FREEFORM, else null
    BigDecimal sharePercent;
    boolean revocable;
}
```
Validated: exactly one of `partyId`/`freeformDesignee` populated per `type` — enforced at the aggregate boundary, not left to a nullable-pair convention callers have to remember. This distinction must be carried through into Deliverable 4's request/response schemas explicitly (flagging now so it isn't lost).

**New API method (Po3):**
```java
boolean isPolicyInForce(PolicyNumber policy, LocalDate asOf);
```
A lightweight convenience alongside `getCoverageStatus` — `claims`, `billing`, and `policyloan` were all going to reimplement some version of "is this policy currently `ACTIVE`" against `getPolicy()`'s full view; better as one canonical method than three slightly-different reimplementations.

**Persistence:** schema `policy`, tables unchanged from Rev 1 plus the state machine and beneficiary-typing changes above are column/enum-level, not new tables.

---

## 4. `policyloan` — PolicyLoan Aggregate (updated: B2, B3, L1–L3)

**Interest rate (resolves B2):**
```java
public class LoanInterestTerm {
    BigDecimal rate;
    LocalDate effectiveFrom;
}
```
`PolicyLoan` holds a list of `LoanInterestTerm` entries rather than a single immutable `interestRate` field. If your product/actuarial decision ends up "locked at origination," this collapses to exactly one entry for the loan's lifetime — no structural change needed later. If it ends up "floating," the mechanism to record rate changes over time already exists. This is precisely L1's intent: design once, support either outcome.

**Repayment schedule (resolves B3):** `RepaymentSchedule` is now `optional` on the aggregate — present when a product enforces fixed installments, absent when repayment is ad hoc against cash value. Either way, every repayment (scheduled or ad hoc) lands as a `LoanTransaction` ledger entry — the ledger, not the schedule, is what's authoritative for outstanding balance.

**Status (L3 — confirmed, no new status added):** no separate `PARTIALLY_REPAID` status. `REPAYING` plus the current outstanding balance (derivable from the `LoanTransaction` ledger) fully expresses partial repayment — adding a status here would just be a redundant, derivable flag that can drift from the ledger truth.

**Persistence:** schema `policyloan` — `policy_loan`, `loan_interest_term` (new child table), `repayment_schedule` (now nullable/optional relationship), `loan_transaction`.

---

## 5. `billing` — (updated: Bi1, Bi2)

**`ArrearsCase` gains `dunningLevel`** (1–5): `1` reminder SMS → `2` follow-up SMS → `3` call-center escalation → `4` final notice → `5` `PolicyLapseRecommended` fires. Each level transition is itself audit-relevant (feeds `audit`) and gives `communication` a clean trigger per level rather than one undifferentiated "overdue" signal.

**New convenience method:**
```java
InvoiceView getNextDueInvoice(PolicyNumber policy);
```
Purpose-built for the USSD "what do I owe?" flow (`omnichannel` calling into `billing`) — avoids pulling the full billing schedule for what is, for that channel, a single-line answer.

Everything else (two aggregate roots, immutable invoice amounts, grace period sourced from `ProductVersion`) unchanged from Rev 1.

---

## 6. `claims` — (updated: Cl1–Cl3)

- `ClaimAssessment` gains a `fraudIndicator` flag — deliberately **not** a rejection trigger by itself; it's a scrutiny/reporting signal (relevant to TIRA and internal risk review) separate from the assessment's substantive findings.
- `ClaimDetails` is modeled as a **sealed interface** (Java 17+/21) with permitted subtypes `DeathClaimDetails`, `DisabilityClaimDetails`, `CriticalIllnessClaimDetails`, `MaturityClaimDetails` — gives exhaustive, compiler-checked handling wherever claim-type-specific logic branches, instead of an open class hierarchy or a stringly-typed discriminator with runtime-only safety.
- Maturity auto-approval (`REGISTERED → APPROVED` without a manual `ClaimAssessment`) is carried forward exactly as documented in Rev 1 — flagging again per Cl3 that this must appear explicitly in Deliverable 4's API documentation, not just here, so it reads as an intentional design decision to anyone implementing against the contract later.

---

## 7. Supporting Modules — Updated

### 7.1 `party` — Group Membership Redesigned for Scale (Pa1, applied now)

Rev 1 implicitly modeled `Group`'s membership as a child collection of the `Party` aggregate. At the scale you flagged (10,000+ members for a large SACCO), that's a real DDD aggregate-size violation, not just a future performance concern — loading a 10,000-row collection to append one member breaks the "small aggregate, one transaction" principle the rest of this design leans on. Fix, applied now rather than deferred:

- `GroupMembership` becomes its **own** entity with its **own** repository (`party_id` of the group + `party_id` of the member + join date + status), queried and paginated independently — never loaded as part of the `Group` aggregate's collection.
- `Group` itself retains only a denormalized `memberCount` (updated transactionally alongside each `GroupMembership` write, within `party`'s own schema — still a single-module concern, just not a single-aggregate one).
- This is technically two aggregates cooperating within one module (`Group` and `GroupMembership`), which is consistent with how `billing` already has two aggregate roots (§5 of Rev 1) — not a new pattern, just applied here too.

### 7.2 `distribution` — Commission Plans Promoted to Their Own Aggregate (Di1)

Rev 1 treated commission structure as a flat percentage on `Agent`. That doesn't hold up once you need first-year vs. renewal vs. override vs. supervisor-override vs. threshold-based tiers — applying Di1:

- **New aggregate root: `CommissionPlan`** — `planId`, `productId` ref, `status`, child entities `CommissionRule` (tier type: `FIRST_YEAR`/`RENEWAL`/`OVERRIDE`/`SUPERVISOR_OVERRIDE`/`THRESHOLD_BONUS`, rate or flat amount, threshold conditions where applicable).
- `Agent` now references `commissionPlanId` rather than embedding a rate — `getApplicablePlan()` resolves the plan, and `CommissionStatement` generation walks the plan's rules against actual production.
- Persistence: schema `distribution` gains `commission_plan`, `commission_rule` tables alongside the existing `agent_profile`, `agency_hierarchy`, `commission_statement`.

### 7.3 `payment` — Settlement Batch and Idempotency-Key Lookup (Pay1, Pay2)

- **New aggregate root: `PayoutBatch`** (formalizing the `payout_batch` table that existed in Deliverable 2's DDL note but wasn't detailed as an aggregate in Rev 1) — `batchId`, `batchType` (`COMMISSION_RUN`/`MATURITY_BATCH`/`DIVIDEND_RUN`/etc.), `status`, and a collection of `DisbursementInstruction` **references** (by ID, not embedded — each disbursement remains its own aggregate root per Rev 1, avoiding the same large-aggregate trap just fixed in `party`). Batch-level status is derived (all-succeeded / partial-failure / in-progress) from the referenced instructions' individual statuses, not duplicated.
- **New API:**
```java
PaymentStatus getStatusByIdempotencyKey(String idempotencyKey);
```
Lets a caller that lost track of its own generated `PaymentRequestId`/`DisbursementId` (a real scenario after a USSD session drop) check status using the key it already holds, before deciding whether to retry.

### 7.4 `reinsurance` — unchanged from Rev 1.

---

## 8. `finaccounting` — unchanged, still gated on C1 (per Deliverable 2 Rev 2 §6 and Deliverable 3 Rev 1 §8).

---

## 9. `audit` — Dependency Placement Clarified (A1)

Confirming explicitly for Deliverable 4: **every** module publishes domain events that `audit` consumes — this is a fan-in-only relationship (no module calls `audit` synchronously, `audit` calls no other module). The Deliverable 2 dependency table's omission of `audit` reflected exactly this (it has no synchronous edges to show), not an oversight; this section exists so that fact is stated positively rather than left implicit. **A2 (DB-level `REVOKE UPDATE, DELETE ON audit.audit_log FROM app_role`)** is logged for Deliverable 6's DDL, where it belongs alongside the rest of the schema/grants work.

---

## 10. Thin/Generic Modules — unchanged from Rev 1 (`iam`, `document`, `refdata`, `omnichannel`).

---

## 11. Cross-Aggregate Consistency — unchanged principle from Rev 1: no transaction spans two aggregate roots; the `party`/`distribution`/`payment` redesigns above (§7.1–7.3) each explicitly split what could have become an oversized single aggregate into cooperating small ones within the same module, rather than reaching for a cross-module transaction.

---

## 12. Camunda Process Variable Contract & Compensation Handlers (IC3, IC4 — new)

Applies to the surrender/maturity loan-netting choreography designed in Deliverable 2 Rev 2 §3.4 — the one long-running, multi-step flow in this design that actually needs Camunda rather than a plain event listener.

**Process variables (the contract between the BPMN process and every module's API/events it touches):**

| Variable | Type | Set by | Read by |
|---|---|---|---|
| `policyNumber` | String | process start | all steps |
| `grossProceeds` | Money | `policy` (on `PolicySurrenderInitiated`/`PolicyMaturityInitiated`) | netting step |
| `loanSettlementAmount` | Money | `policyloan` (on `LoanSettledForPayout`) | netting step |
| `netPayout` | Money | process (computed: gross − settlement) | `payment` request step |
| `disbursementId` | String | `payment` (on request submission) | completion step |
| `status` | Enum (`IN_PROGRESS`/`COMPLETED`/`COMPENSATING`/`FAILED`) | process | monitoring, `audit` |
| `errorDetails` | String, nullable | any failing step | compensation step, staff worklist |

**Compensation handlers (IC4 — what happens when a step fails mid-flow):**

| Failure point | Compensating action |
|---|---|
| `policyloan` doesn't respond to `PolicySurrenderInitiated`/`PolicyMaturityInitiated` within a bounded timeout | Process escalates to a staff manual-resolution task (Camunda user task) — does **not** guess a zero settlement amount and proceed, since that could pay out gross when a loan actually exists. |
| `payment` disbursement fails (`DisbursementFailed`) after loan settlement already recorded | `policyloan` compensating action: reverse the loan settlement entry (re-open the loan balance via a new `LoanTransaction` reversal entry — never delete the original, ledger-style); `policy` reverts the tentative `Surrendered`/`Matured` state back to its prior status; staff worklist notified via `communication`. |
| Process crashes/times out mid-flow entirely | Camunda's own process-instance persistence recovers state on restart; a periodic reconciliation job cross-checks any process stuck `IN_PROGRESS` beyond an SLA against `audit`'s event trail and flags to staff — same 24-hour-style escalation pattern as the offline-receipt SLA (Deliverable 2 Rev 2 §2). |

---

## 13. Open Items Before Deliverable 4

1. **[VERIFY]** which product categories support `SUSPENDED` (policy state machine, §3).
2. **[VERIFY]** the suspension-to-lapse window (a further `refdata` parameter, not yet named/valued).
3. `finaccounting` remains placeholder pending C1.
4. B1–B4's actual statutory/product values are still pending Legal/Product/Actuarial — the design now absorbs whatever those turn out to be without restructuring, but Deliverable 6's seed data will need real values, not placeholders, before go-live.

*Holding here per your process — once reviewed, I'll proceed to Deliverable 4 (API Contract Specification).*
