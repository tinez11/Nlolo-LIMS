# Reports batch 2: claims variance

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build seven claims reports, each comparing with the period before or the same period last year, with figures, a chart and drill-down to the claims themselves. They are built on a new claims fact table that reporting fills from claims events.

**Architecture:**
- `reporting.report_claim` holds one row per claim. It is filled from the `claims.*` events by the same handlers that serve the live listener and the audit-journal rebuild, and made idempotent through `applied_event`.
- Reports read it joined to `report_policy` for the product, branch, channel, agent and activation date.
- Every report is MONTH_RANGE, `comparingPeriods()`, and the server computes every figure.

**Spec:** `backend/docs/superpowers/specs/2026-11-09-reports-redesign-design.md` §5.2.

## Global constraints

- **Definitions (stated on each report):**
  - **Claims incurred** = claims approved in the period, at their approved amount.
  - **Loss ratio** = claims incurred ÷ premium collected in the period.
  - **Open** = registered, and neither approved-and-paid nor declined.
- **The large-claim threshold** is 20,000,000 TZS. **The payment target** is 30 days from registration. Both are constants in `ClaimsReporting`, named and documented.
- **C7 (reinsurance recoveries) is deferred** to the Reinsurance batch: it needs the reinsurance projection.
- **No browser sums.** Every subtotal, effect and percentage comes from the server.

### Task 1: the claim projection

**Files:**
- `db-migrations/reporting/V2__claims.sql` (new)
- `ProjectionStore` (`upsertClaim*` methods)
- `ReportingEventHandlers` (the claims cases)
- `ReportingEventListener` (if it filters by prefix)
- the test migration lists
- `ReportingEventHandlersTest` / a new `ClaimProjectionTest`

**The table:** `report_claim`:
- `tenant_id`, `claim_id` (primary key), `policy_number`, `claim_type`, `date_of_event`;
- `registered_on`, `approved_on`, `approved_amount`, `rejected_on`, `reject_reason`;
- `payable_amount`, `settlement_requested_on`, `settled_on`, `paid_amount`, `currency`.

**The events it reads:**
- `ClaimRegistered`: insert, with `registered_on` from the envelope's `occurredAt`.
- `ClaimApproved`: the approved amount and date.
- `ClaimRejected`: the rejection date and reason.
- `ClaimSettlementRequested`: the payable amount after netting.
- `ClaimSettled`: the paid amount (falling back to the settled amount) and the date.
- If an event arrives before its `ClaimRegistered`, the row is created with what is known.

**Test:**
- replaying a registered → approved → settlement-requested → settled sequence leaves one row with every column set;
- replaying it twice changes nothing;
- a rejection sets `rejected_on` and the reason.

### Task 2: queries and the shared variance helper

**Files:**
- `ReportQueries.claims(...)`
- `ClaimsReporting.java` (new: definitions, constants, `Variance` helper)
- tests

**Interfaces:**
- `ReportQueries.claimFacts(tenantId, LocalDate from, LocalDate to, ReportFilter f)` returns the claims with any date in the span, each joined to its policy dimensions (product, product category, branch, channel, agent, activated_on).
- `ClaimsReporting.Variance.of(currentCount, currentAmount, priorCount, priorAmount)` returns:
  - the change;
  - the count effect = (count − prior count) × prior average;
  - the severity effect = count × (average − prior average);
  - and they sum exactly to the change, with any rounding difference placed on the severity effect.

**Test:**
- the effects sum to the change exactly;
- a prior of zero gives a zero prior average and puts all of the change in the count effect.

### Task 3: C1 Claims incurred variance

- **Figures band:** claims incurred, number, average.
- **Tiles:** one per claim type, plus large claims.
- **Bridge:** prior incurred → count effect → severity effect → large-claim difference → this period.
- **Table:** product → claim type → the claims. The tree's parts are product, then type, then claims, served from the same facts.

### Task 4: C2 Loss ratio, C3 Outcomes, C4 Approved vs paid

- **C2:** loss ratio by product, against the prior period. The tiles are claims, premium collected and the ratio.
- **C3:**
  - registered, approved, declined and open, by count and amount;
  - the decline rate;
  - a section of decline reasons.
- **C4:**
  - approved, payable, paid, and the netting (approved − payable);
  - the unpaid balance aged 0–30, 31–60, 61–90 and 90+ days from approval.

### Task 5: C5 Turnaround, C6 Early claims, C8 Outstanding movement

- **C5:**
  - the average days from registration to decision and from approval to payment, against the prior period;
  - a section listing claims that missed the 30-day target.
- **C6:**
  - claims whose event fell within 1 or 2 years of activation;
  - by agent and branch, against the prior period.
- **C8:**
  - opening open claims + registered − decided (approved-and-paid, declined) = closing open claims, by count;
  - the approved-but-unpaid amount at both ends.

### Task 6: the list, the frontend and checks

- The "Claims" area in `reportAreas.ts` lists the new keys, and `ReportCategory` gains CLAIMS.
- Tests: contract tests for each report against the spec, plus the frontend list test.
- Browser check against the claims table on dev: counts and amounts per type and month match `claims.claim`.
- Merge.
