# Product step 2 — the payout engine ("the tap")

**Date:** 2026-10-01 (design only — nothing built). **Builds on:** step 0 (rolling billing, expiry,
pinned versions) and step 1 (cash value, paid-up, surrender, and its two-person payout
choreography). **Branch:** `product-step2-payouts`, cut from `main` **after step 1 merges**.

**Source of requirements:** *Understanding Life Insurance Products* (the client's guide, September
2026), §4, §6, §7, §14, §16, §21. Every decision below marked **Qn** was answered by the user on
2026-10-01.

---

## 1. Where this sits

The guide's §21.4 says to build two reusable engines: an accumulation engine (the "bucket") and a
payout engine (the "tap"). The full product roadmap, agreed 2026-10-01:

| # | Sub-project | Unlocks |
|---|---|---|
| **A** | **Payout engine — this document** | endowment, money-back, return-of-premium term, guaranteed income, overlapping income plans, free-look |
| B | Traditional accumulation, with interest crediting | savings plan, deferred-annuity and pension accumulation |
| C | Bonuses | participating / with-profit |
| D | Annuities (immediate, vesting from B, options, payment runs, proof of life) | annuities, pensions |
| E | Unit-linked | ULIP, investment-linked pension |
| F | Riders as child coverages with their own premium | riders on all base products |

## 2. Decisions

| | Question | Answer |
|---|---|---|
| Q1 | When a scheduled payout falls due | **Created, reviewed by one person, approved by a second, then paid.** |
| Q2 | Proof of life before a survival or income payout | **The reviewer records the method** — `IN_PERSON`, `PHONE_OR_VIDEO`, `LIFE_CERTIFICATE` — with an optional document. No payout reaches approval without it. |
| Q3 | A payout falls due while premiums are in arrears | **Held** until billing reports the policy paid to date. Lapse cancels held and future payouts. Paid-up restates future payouts proportionately. |
| Q4 | Free-look refund | **Premiums collected minus itemised, documented deductions**, never below zero. Deductions are entered on the cancellation itself (the platform records no underwriting costs today). The period is a required product field with no platform default. |
| Q5 | Return-of-premium amount | **A required return percentage × premiums collected.** "Collected" means the invoice amounts billing reports as paid; billing does not separate the frequency loading, so it cannot be excluded. Rider premiums are excluded once riders exist (F). |
| Q6 | Payout frequency | **Schedule rows carry a frequency** (annual, semi-annual, quarterly, monthly), expanded into dated instalments at issue. |
| Q7 | Approval for recurring instalments | **Approve the stream once, then approve each payment run as a batch.** One-off payouts keep two-person approval each. Proof of life renews on a fixed interval; overdue suspends the stream. |
| — | Architecture | **A new `benefitpayout` module** (not inside `policy`, not inside `claims`). |
| — | Reinstatement after lapse | Instalments that fell due **during** the lapse are forfeited; instalments dated after reinstatement are restored from the schedule. |

## 3. Product configuration

### 3.1 Schedule rows (product migration V18)

`product.payout_schedule_row`, owned by a product version:

| Column | Values |
|---|---|
| `kind` | `SURVIVAL`, `MATURITY`, `INCOME`, `RETURN_OF_PREMIUM` |
| `from_policy_year`, `to_policy_year` | inclusive; equal for a one-off |
| `amount_basis` | `PERCENT_OF_SA`, `FIXED`, `PERCENT_OF_PREMIUMS` (RETURN_OF_PREMIUM only) |
| `amount_value` | percentage or money, by basis |
| `frequency` | `ANNUAL`, `SEMI_ANNUAL`, `QUARTERLY`, `MONTHLY` |

New version fields:

| Field | Required when |
|---|---|
| `free_look_days` | every **individual** product version (term, endowment, education savings, whole life); not used on group or credit life, whose cancellation is the scheme contract's |
| `proof_of_life_interval_months` | the version has an `INCOME` row |
| `survival_benefits_deducted_from_death` | the version has a `SURVIVAL` row (guide §7: a product setting, not a universal rule) |
| `death_benefit_premium_percent` | optional; when set, the death benefit is the higher of the sum assured and this % of premiums collected (guide §6) |

### 3.2 Which rows each category may carry

Applied per product, only where the author adds rows. A version with no rows behaves exactly as
today.

| Category | Rows allowed | Result |
|---|---|---|
| `TERM_LIFE` | none, or exactly one `RETURN_OF_PREMIUM` | plain term, or ROP term |
| `ENDOWMENT`, `EDUCATION_SAVINGS` | exactly one `MATURITY`; optional `SURVIVAL` and/or `INCOME` | endowment; + SURVIVAL = money-back (§7); + INCOME = regular-income / overlapping (§14) |
| `WHOLE_LIFE` | none | lifelong cover, step 1's surrender value |
| `GROUP_LIFE`, `CREDIT_LIFE` | none | unchanged |
| `ANNUITY`, `UNIT_LINKED` | refused | arrive with D and E |

There is no money-back category: money-back is an endowment with survival rows (§22, "look at the
mechanism").

**Existing products are untouched.** A policy uses the schedule of the version it was sold under
(step 0, D3), so only policies issued on a version published after this ships get scheduled
payouts.

## 4. The `benefitpayout` module

Allowed dependencies: `policy::api`, `product::api`, `party::api`, `document::api`. Billing,
claims and payment events are consumed as `DomainEventEnvelope`s by event-type string, as
`CashValueEventListener` does, so no compile-time dependency on those modules exists. `claims`
gains `benefitpayout::api` as an allowed dependency for the death valuation; there is no cycle
because `benefitpayout` never imports `claims`.

### 4.1 Tables (new `benefitpayout` schema)

| Table | Holds |
|---|---|
| `payout_instalment` | one dated amount owed: policy, kind, due date, original amount, current amount, restatement reason, status, `@Version` |
| `payout_stream` | groups an INCOME row's instalments; status `PENDING_ACTIVATION` / `ACTIVE` / `SUSPENDED` / `ENDED`; next proof-of-life due date |
| `payout_review` | instalment, reviewer, payee reference, proof-of-life method and document id, approver, timestamps |
| `payment_run` | run date, status, approver; its instalments |
| `premium_tally` | per policy, the running total of premiums collected and the latest paid-to-date |
| `free_look_cancellation` | policy, requester, refund, approver, status |
| `free_look_deduction` | cancellation, description, amount, optional document id |

RLS on every table with the existing `NULLIF` tenant predicate; amount columns append-only
(restatement writes the new figure beside the original, never over it); partitioning is not
needed at this volume.

### 4.2 Policy state

- `policy.status` keeps meaning premium-and-cover state. **"In payout" is derived from the
  instalments and never stored in `status`**, which satisfies §14 without a second status column.
- New `PolicyStatus` literal `CANCELLED_FREE_LOOK` (policy migration).
- A policy with a MATURITY or RETURN_OF_PREMIUM row moves to `MATURED` on its maturity date,
  whether or not the payout has gone out — cover ends on the date, as step 1's surrender ends it
  at approval.

## 5. How a payout moves

### 5.1 One-off payouts (maturity, a survival benefit, ROP)

```
SCHEDULED ──due date──▶ DUE ──review──▶ REVIEWED ──second person──▶ APPROVED ──▶ PAID
              │            │                                           ├──▶ FAILED (retry → APPROVED)
              │            └─ arrears ─▶ ON_HOLD ─paid to date─▶ DUE   └──▶ IN_DOUBT (reconcile)
              └─ lapse / surrender / death / free-look ─▶ CANCELLED
```

- **Expansion.** On `policy.PolicyIssued`, the version's rows are expanded into dated instalments
  (frequency × year range, dated from the policy start date) and stored — guide §16, "calculated
  and stored on the day the policy is issued".
- **DUE.** `PayoutDueDrain`, a Spring `@Scheduled` job, selects due instalments through a
  `SECURITY DEFINER` function returning ids only and processes each under its own tenant — the
  `CoverExpiryDrain` / `policies_due_to_expire()` shape. Exactly-once comes from `@Version`; one
  failing row is logged with its policy and tenant and never stops the queue.
- **ON_HOLD (Q3).** An instalment is held when `premium_tally.paid_to_date` is before its due
  date. Every `billing.PremiumCollected` re-checks held instalments for that policy.
- **REVIEWED (Q2).** The reviewer confirms the payee reference (defaulting to the policyholder's
  registered mobile number, as surrender does). SURVIVAL and INCOME also require a proof-of-life
  method. MATURITY and ROP need none — they are owed by date.
- **APPROVED (Q1).** The approver must not be the reviewer, enforced in the domain. Approval
  publishes `benefitpayout.PayoutRequested`.
- **PAID / FAILED / IN_DOUBT** follow `SurrenderPaymentListener` exactly: matched on purpose and
  source reference; FAILED returns to APPROVED for a retry with every attempt kept; IN_DOUBT stays
  APPROVED for reconciliation and is never blindly retried.

### 5.2 Recurring income streams (Q7)

1. The stream's **first** instalment takes the full one-off path. Its approval activates the
   stream and sets the next proof-of-life date (`proof_of_life_interval_months`).
2. `PaymentRunDrain` gathers each day's DUE instalments of ACTIVE streams into one `payment_run`
   per tenant per day. One FINANCE_OFFICER/ADMIN approves the run — the system prepared it.
   Instalments are then disbursed and settled individually, so one failure never fails the run.
3. Proof of life overdue → the stream is `SUSPENDED`; its instalments go ON_HOLD instead of
   joining a run. A new proof-of-life record reactivates the stream and releases them.
4. Arrears hold stream instalments exactly as they hold one-off ones.

## 6. Product rules

| Event | Effect |
|---|---|
| **Maturity** | The MATURITY instalment becomes DUE; `benefitpayout` calls `policy::api` to mature the policy, which publishes the existing `policy.PolicyMatured` (billing stops, no clawback). Amount = the row's % of sum assured; bonuses are added in C. `policies_due_to_expire()` is changed to skip any policy with a scheduled end-of-term instalment, so these mature rather than expire. |
| **ROP term end** | The RETURN_OF_PREMIUM instalment = return % × `premium_tally` total; the policy goes `MATURED`, not `EXPIRED`. |
| **Survival benefit** | Owed only if the life assured is alive on the due date and the policy is in force or paid-up. Death **after** the due date but before payment leaves it owed, to the policyholder or the estate (payee chosen at review). |
| **Death** (`claims.ClaimApproved`, death) | Instalments dated after the date of death are cancelled. Claims reads the death benefit from `benefitpayout::api`: sum assured minus survival benefits paid if `survival_benefits_deducted_from_death`; the higher of sum assured and `death_benefit_premium_percent` × premiums collected if that is set. |
| **Paid-up** (`policy.PolicyMadePaidUp`) | Future instalments restated by step 1's proportion (paid-up SA ÷ original SA); the original amount is kept beside the restated one. |
| **Surrender** (`policy.PolicySurrendered`) | All future instalments cancelled. "Surrender value must account for payouts already made" (§7) is the actuarial table's job — the author loads values already net of payouts. |
| **Lapse / reinstatement** | Lapse cancels held and future instalments. Reinstatement forfeits those that fell due during the lapse and restores those dated after it. |
| **Free-look** | Requested within `free_look_days` of the issue date. Approval: policy → `CANCELLED_FREE_LOOK`, billing stops, distribution claws back **all** commission, payment refunds premiums collected minus deductions (`FREE_LOOK_REFUND`). |
| **Older products** | Filing a manual MATURITY claim is refused when the policy's version has a schedule; without a schedule, today's behaviour is unchanged. |

## 7. Integration

**Consumed:** `policy.PolicyIssued`, `policy.PolicyLapsed`, `policy.PolicyReinstated`,
`policy.PolicyMadePaidUp`, `policy.PolicySurrendered`, `billing.PremiumCollected` (its `amount`
and `paidToDate`), `claims.ClaimApproved` (death only), `payment.DisbursementCompleted`,
`payment.DisbursementFailed`.

**Published:**

| Event | Consumer |
|---|---|
| `benefitpayout.PayoutRequested` | `payment` disburses. Purposes: `MATURITY_PAYOUT` (already in the payment CHECK), plus new `SURVIVAL_BENEFIT_PAYOUT`, `INCOME_PAYOUT`, `PREMIUM_RETURN_PAYOUT`, `FREE_LOOK_REFUND` (payment migration) |
| `benefitpayout.PayoutPaid` | `finaccounting` posts it; new benefit-expense accounts in the chart of accounts |
| `benefitpayout.FreeLookCancelled` | `policy` sets the status; `billing` stops the schedule; `distribution` claws back |

**Synchronous:** `benefitpayout` → `policy::api` (mature, cancel for free-look); `claims` →
`benefitpayout::api` (death valuation).

**To verify while planning** (not assumed here): the `claims.ClaimApproved` payload carries claim
type and date of death; whether step 1's surrender payout reaches the general ledger at all
(`finaccounting` posts only `claims.ClaimSettled` and the EFT events today).

## 8. Errors and refusals

Refusals are `422` with the server's own wording, which the frontend mirrors: approver is the
reviewer; review without proof of life where one is required; review or approval of a held
instalment; free-look outside the window or on a non-individual product; deductions exceeding the
refund; a schedule row a category does not allow; a missing required version field.

## 9. Access

Money leaving the company is **FINANCE_OFFICER / ADMIN**, following the commission-payout
precedent (`PRODUCT.md`: "agent commission and payouts"): review, approve, payment runs, and
free-look approval. Free-look **request** is `REALM_STAFF`, as the other lifecycle requests are.
Product authoring stays ADMIN.

**Finding for step 1's final review, not changed here:** `POST /surrender-requests/{id}/approve`
is gated on `REALM_STAFF` alone (`PolicyController:414`), so a customer service rep can approve
money out. It should be raised there rather than altered silently by this step.

## 10. Frontend

Every choice below follows `PRODUCT.md`, `DESIGN.md` and the existing screens; none is new
pattern.

**Policy page.**
- A **`Payouts` tab** after Loans, present only when the policy's version has a schedule (the
  Reinsurance tab's conditional precedent). A client-side table, no pager (per-policy, whole
  array): due date, kind, amount, `StatusBadge`, held/cancelled reason. No tab count. **Staff
  only** — hidden in the agent console, as the member schedule is.
- **Free-look** in the Overview tab's `Lifecycle` panel beside suspend and reinstate — occasional
  act, so no emphasis. `GatePanel` (window open, not already cancelled), the itemised-deduction
  form, and an inline `ConfirmAct` with real values and the danger tint ("Cancel POL-… and refund
  TZS 480,000.00 to +2557…"; cover ends from inception and cannot be revived). Approval sits in the
  same panel; the gate tells a requester they cannot approve their own, in the server's wording.

**Payout page** (`reach: 'drill-in'`, from the tab and the queue). `DetailLayout`: the rail holds
facts only (policy, kind, due date, amount, status). One **emphasised** acting panel — the page
exists to perform the act: review (payee, proof-of-life method, document upload) when DUE,
approve when REVIEWED. `GatePanel` from `src/gates/payoutGates.ts`. `ConfirmAct` with real
values, then a persistent `Receipt` stating the money is requested, not paid (the commission
payout precedent; the POST answers `202`). `Idempotency-Key` minted once per submit in the Zustand
action.

**Finance nav group** (each backed by a real list endpoint):
- **Payouts** — a paged register with a server-side status filter and a `CountLine`; a nav badge
  counting instalments awaiting review (`DUE`), one status filter as every badge is.
- **Payment runs** — a client-side table; each run's page lists its instalments and holds the one
  approve act.
- **Maturities** — a paged register of policies maturing in a chosen window, with a `CountLine`.
  A list only; no total figure unless the server returns one.

**Product authoring** (ADMIN). `PublishVersionForm` gains a schedule-rows section and the new
fields, using the row-editor pattern step 1's cash-value table editor introduces; per-category
rules and wording mirror the server.

**Status buckets** (literals from the Java enums, never invented):

| Bucket | Literals |
|---|---|
| neutral | `SCHEDULED`, `CANCELLED`, `CANCELLED_FREE_LOOK`, stream `ENDED` |
| pending | `DUE`, `REVIEWED`, `APPROVED`, stream `PENDING_ACTIVATION` |
| active | stream `ACTIVE` |
| success | `PAID` |
| warning | `ON_HOLD`, `IN_DOUBT`, stream `SUSPENDED` |
| danger | `FAILED` |

## 11. Testing

The user's rule (2026-10-01): tests are written with every task and committed with it; only that
task's classes run while building; the full suite and the real-stack e2e run once, at the gate.

- **Unit, no container:** schedule expansion; paid-up restatement; ROP and endowment death-benefit
  arithmetic; every state transition; the two-person rule; the free-look deduction cap; the
  per-category row rules.
- **Container classes, four only:** `BenefitPayoutApiIntegrationTest`,
  `PayoutDrainIntegrationTest`, `FreeLookIntegrationTest`, `BenefitPayoutContractTest`. New
  migrations go only into the classes that need them.
- **Frontend:** gate and form unit tests; e2e specs for review → approve → receipt, the hold,
  free-look, and authoring. Existing policy specs are extended, never rewritten.
- **Gate:** the full backend suite, dev DB migrated, real-stack e2e, then a final whole-branch
  review.

## 12. Build order

1. Product V18 — schedule rows, version fields, per-category validation.
2. `benefitpayout` schema; expansion at issue; the premium tally.
3. Due drain, arrears hold, review and approve, payment and GL wiring; maturity and ROP → MATURED.
4. Lifecycle reactions (lapse, reinstatement, paid-up, surrender, death); claims valuation; the
   MATURITY-claim refusal.
5. Income streams, proof-of-life interval, payment runs.
6. Free-look.
7. Maturities register.
8. Frontend.
9. Gate, final whole-branch review, merge.

## 13. Out of scope, on the record

- Netting arrears against a payout (Q3 chose hold).
- Reinsurance cessions on maturity and free-look (the gap step 1 recorded).
- IFRS 17 measurement and reserves (the separate comprehensive model).
- Bonuses in maturity and death amounts (C).
- Tax withholding on payouts (D, with annuities, where the guide raises it).
- Whole-life cover ending at a fixed age — no product field holds a maturity age.
- Agent-console visibility of payouts (needs agent scoping server-side).
