# Product step 0 — the foundations every product stands on

**Date:** 2026-09-30. **Branch:** `product-step0-foundations`, cut from
`console-redesign-5-clients` (which carries the unmerged single-premium,
premium-basis and KYC work this builds on).

**Why this exists.** The gap analysis against *Understanding Life Insurance
Products* (the client's guide, September 2026) found that only term, group and
credit life work end to end, and four defects in products already sold. Steps
1–6 (savings ledger, payouts, riders, bonuses, annuities, unit-linked) are all
built on top of these, so they are fixed first and on their own.

Every claim below was confirmed against the code and, where it says so, the dev
database on 2026-09-30.

---

## The four defects

### D1 — billing stops after the first year

`BillingApiImpl.generateInvoicesAhead` raises invoices twelve months ahead. It
has exactly two callers: `generateScheduleForNewPolicy` (at issue) and
`resumeScheduleAfterSuspension`. `billing.sweep_billing_state()` only moves
existing invoices through DUE → IN_GRACE → OVERDUE; it never inserts one. So a
regular-premium policy gets twelve months of invoices and then none, forever.

The same loop ignores the end of the contract. It bounds on the horizon alone,
so a 6-month policy is billed for 12 months and a limited-pay policy is billed
past its premium-paying term. (Dev has 0 invoices past a maturity date today,
only because every termed dev policy runs 12 or 120 months.)

### D2 — cover never ends at term end

`policy.maturity_date` is derived at issue by `Policy.applyTerm` and stored
"because a maturity sweep needs an indexable column". No such sweep exists.
Nothing reads the column except `toView`.

Claims gate on `PolicyApi.isPolicyInForce(policyNumber, dateOfEvent)`, which
ignores its date and reads today's status. That is wrong in **both** directions,
and `ClaimsApiImpl` already records the second as a known gap:

- a death three years after a one-year term expired is **accepted** — the policy
  still reads ACTIVE;
- a death that happened **before** a lapse, reported after it, is **refused** —
  the policy reads LAPSED today. Dunning lapses a deceased life's policy
  automatically, so this is the commonest late-reported death claim there is.

A MATURITY claim is also admitted on any date, including before maturity, and a
MATURITY claim can be approved without an assessment — so an endowment can be
paid out early.

### D3 — existing policies read the product's current terms

The guide's rule (§21.4): *existing policies keep the old terms; store which
version each policy was sold under.* The platform stores it — and two reads
ignore it:

- `PolicyApiImpl.quoteSurrenderValue` reads `getActiveSnapshot(productId, today)`
  for the surrender-charge schedule;
- `EnrolmentApiImpl.Judge` reads `getActiveSnapshot(productId, today)` for the
  eligibility bounds a lender's file is judged against.

Both switch to `getSnapshotByVersionId(policy.getProductVersionId())`.

### D4 — the rate table has no term dimension

`product.base_rate_table` is keyed on (age band, sex, smoker status). A 5-year
and a 20-year term price identically per mille. For an endowment, where the term
is most of the price, that is not usable. Both pricing paths read the table:
`ProductApiImpl.quotePremium` (the quote endpoint) and
`resolveBaseRatePerMille` (automatic issuance, via
`UnderwritingDecisionEventListener`).

---

## Design

### D1 — rolling billing

**The end of billing is a date the policy computes, and billing stores.**

- `Policy.premiumPayingUntil()`: start = `commencementDate`, else `issueDate`;
  months = `premiumPayingTermMonths`, else `policyTermMonths`; null when there is
  no term (whole life, annually renewable schemes — billed indefinitely, which is
  correct).
- `policy.PolicyIssued` carries it as `premiumPayingUntil` (ISO date or absent),
  at both publish sites (`issuePolicy`, `issueGroupScheme`).
- billing V8 adds `billing_schedule.premium_paying_until DATE`, backfilled for
  existing schedules from `policy.policy` (guarded by `to_regclass`, so test
  classes that apply billing alone still migrate).
- `generateInvoicesAhead` stops at the horizon **or** the paying end, whichever
  comes first. A due date equal to the paying end is billed: billing's
  existing convention puts the first invoice one period after issue (premium in
  arrears), so a 12-month monthly policy is due at issue+1 … issue+12 — twelve
  invoices, and the bound is inclusive to keep it twelve.

**Rolling forward: SQL chooses, Java raises.** Invoices must be raised in Java,
because `billing.PremiumInvoiceGenerated` is what posts the premium receivable
to the ledger (`finaccounting.PostingRule`); a SQL insert would bill the
customer and leave the books silent. So this follows the
`communication.pending_reminders()` / `OfferReminderDispatcher` shape exactly:

- `billing.schedules_due_for_invoicing(horizon_months int)` — SECURITY DEFINER,
  returns `(billing_schedule_id, tenant_id)` only: ACTIVE schedules whose
  `next_due_date` is within the horizon and not past `premium_paying_until`.
  REVOKE from PUBLIC, GRANT to app_role.
- `billing.application.InvoiceRollForward` — `@Scheduled` hourly, iterates the
  pairs, sets the tenant from each row, and calls
  `BillingApiImpl.rollForward(scheduleId)` inside a `TransactionTemplate`.
- `rollForward` locks the schedule row (`PESSIMISTIC_WRITE`) and generates from
  its own `next_due_date`. Two instances reading the same row: the second waits,
  then finds the date already advanced and raises nothing. That is the
  exactly-once guarantee, and it is why a Java drain is acceptable here where the
  platform otherwise wants pg_cron: the transition is claimed by a lock, not by
  running in one place.
- The version for the grace period comes from `policyApi.getPolicy(n)
  .productVersionId()`; billing already depends on `policy::api`.

No new schedule status: a finished schedule simply stops being selected.

### D2 — cover ends, and claims ask the right question

**`Policy.wasOnRiskOn(LocalDate day)`** — the one date-bounded answer:

| Status | On risk on `day` when |
|---|---|
| PROPOSED, NOT_TAKEN_UP | never — no contract was on risk |
| any | `day` ≥ start (`commencementDate`, else `issueDate`) **and**, if there is a maturity date, `day` < `maturityDate` |
| ACTIVE, REINSTATED, EXPIRED | the window above |
| LAPSED | the window, and `day` before the lapse date |
| SUSPENDED | the window, and `day` before the suspension date |
| MATURED, SURRENDERED | never — closed (unchanged) |

Instants convert to dates in `Africa/Dar_es_Salaam`, the zone
`OfferReminderDispatcher` already names, because a lapse at 01:00 EAT is still
the previous UTC date.

`isPolicyInForce(n, asOf)` becomes `wasOnRiskOn(asOf)`. For its other caller,
`policyloan` (`asOf` = today), the answer is unchanged for every live policy,
and newly false for a termed policy past its maturity date — correct: no loan
against cover that has ended.

**Known limit, stated rather than solved:** a REINSTATED policy keeps its
`lapsedAt` and records no reinstatement date, so a death during the lapse gap of
a later-reinstated policy is still treated as on risk (today's behaviour).

**EXPIRED** joins `PolicyStatus` (Java enum, `policy_status_check`,
`openapi-policy.yaml`). `Policy.expire()` accepts ACTIVE, REINSTATED and
SUSPENDED once `maturityDate` has passed; EXPIRED is terminal (only LAPSED can be
reinstated). A settled claim on an EXPIRED policy leaves it EXPIRED — closure's
purpose, stopping invoices, is already met — so the closure methods treat it as
closed rather than throwing inside the settlement listener.

**The expiry drain** follows the same shape as D1:
`policy.policies_due_to_expire()` (SECURITY DEFINER, ids and tenants only) picks
in-force policies past their maturity date **that carry no active MATURITY
coverage**; `policy.application.CoverExpiryDrain` calls
`PolicyApiImpl.expirePolicy`, which publishes `policy.PolicyExpired`. Java rather
than a pg_cron UPDATE because the transition has consumers — billing terminates
the schedule, and audit records it — and `policy.sweep_expired_offers` is the
precedent for what a silent SQL transition costs. Exactly-once comes from the
aggregate's `@Version` plus the guard: the second attempt finds EXPIRED and
returns.

Policies **with** a MATURITY benefit are left alone: they mature, they do not
expire. Automatic maturity is step 2; until then a person files the maturity
claim, and billing (D1) and claims (below) already respect the date.

**Reinstatement** refuses once the maturity date has passed — a lapsed policy
cannot be revived into a term that has ended.

**Claims:**
- DEATH / DISABILITY / CRITICAL_ILLNESS: `isPolicyInForce(dateOfEvent)`, now
  real. The KNOWN GAP comment is replaced.
- MATURITY: refused unless the policy has a maturity date and `dateOfEvent` is
  on or after it; the in-force question is asked of the day before maturity
  (cover must have run to the end).

**Consumers of `policy.PolicyExpired`:** billing (terminate the schedule),
audit (generic). Deliberately not distribution — expiry is not a lapse, and a
policy that ran its term must not claw back its agent's commission.

### D3 — pinned version

Two call sites, as above. Nothing else reads the active version for an existing
policy (`issuePolicy` and `issueGroupScheme` read it for new business, which is
correct; `createCommissionPlan` uses it as an existence check).

### D4 — term-banded rates — DEFERRED to step 1

**Decision (2026-09-30):** deferred to the start of step 1 (endowment), not built here. D4 is the
only one of the four that is not an active production bug — it blocks endowment *pricing*, which is
step 1's concern, whereas D1–D3 are live defects on policies already sold. Building it means adding
mapped `term_from_months`/`term_to_months` columns to the `BaseRate` entity, which forces the new
migration into all 54 test classes that build the base-rate table — a large, mechanical, error-prone
edit best done in the change that actually consumes term-banded rates. The design below stands as
written for step 1.



- product V16: `base_rate_table.term_from_months`, `term_to_months`, both null
  (the rate applies to any term — every row that exists today) or both set with
  1 ≤ from ≤ to. The unique cell becomes (version, age_from, sex, smoker,
  term_from) via a unique index on `COALESCE(term_from_months, 0)`.
- Overlap validation in `ProductApiImpl` (the backend's own rule, since a true
  constraint needs `EXCLUDE USING gist`) pairs per (sex, smoker) and refuses two
  rows whose age ranges **and** term ranges overlap; an unbanded row overlaps
  every term.
- `findApplicable` gains the term: an unbanded row matches any term; a banded row
  matches when the term falls in it. A policy with **no** term on a
  term-banded version finds no cell and is refused, naming why.
- `PremiumQuoteInput` and `PremiumQuoteRequest` gain optional
  `policyTermMonths`; `resolveBaseRatePerMille` gains `termMonths`, and
  automatic issuance passes `decidedCase.requestedTermMonths()`.
- **A SINGLE premium for more than twelve months of cover is refused on both
  paths.** `rate_per_mille` is an annual rate (V2's column comment), and
  `quotePremium`'s own comment already says a longer single premium "is NOT this
  number and must not be quoted here". Today that is only a comment. Single
  premiums over longer terms need a declared rate basis, which is a later step.

---

## Frontend — what the rules require

From `PRODUCT.md`, `DESIGN.md` and `frontend/PLAN.md`, the constraints that bind
this work, and what each one means here:

| Rule | Consequence |
|---|---|
| Enum literals come from the OpenAPI spec and Java enum, never invented | `EXPIRED` is added to `PolicyStatus` and `openapi-policy.yaml` first; the types are regenerated with `npm run generate:api` |
| Six status buckets, never a seventh | `EXPIRED` → **neutral** ("closed without incident"), beside SURRENDERED and NOT_TAKEN_UP. MATURED stays success. |
| Gates assert only what the platform can prove | `claimGates` is rewritten. Its premise — *the platform records no lapse date and ignores the date of event* — stops being true. The soft "needs a person" status gate is replaced by a **hard** gate whose answer is the backend's own `GET /policies/{n}/in-force?asOf=<dateOfEvent>`, not a client-side reimplementation. MATURITY claims get a hard "maturity date reached" gate from `PolicyView.maturityDate`. |
| Forms mirror the backend's validation and its wording | the base-rate row gains optional term from/to (both or neither, whole months ≥ 1, to ≥ from) and the overlap check pairs age **and** term, with the backend's messages |
| Render only what the platform can prove | the rating table shows a term column only when a version is term-banded; nothing is shown for billing's paying end that the API does not return |
| No client-side money arithmetic | unchanged — no premium is computed in the browser |
| Documentation is the design record | `PRODUCT.md`'s sentence that the backend ignores `asOf` is corrected precisely: **coverage-status still ignores it** (coverage rows have no history), **in-force now honours it**. `frontend/PLAN.md` gains a §14 record of this change. |

`PolicyDetailPage` is checked for status branches so EXPIRED offers no lifecycle
action (reinstate is LAPSED-only already).

---

## Tests

- Unit: `Policy.wasOnRiskOn` across every status and boundary; `Policy.expire`;
  `premiumPayingUntil`; `BillingSchedule` bound; base-rate overlap with terms.
- Integration (Testcontainers):
  - a 6-month monthly policy raises six invoices, not twelve;
  - a policy whose horizon has run down is rolled forward by `rollForward`, which
    publishes `PremiumInvoiceGenerated` per invoice, and a second call raises
    nothing;
  - the expiry drain moves a matured term policy to EXPIRED, publishes
    `PolicyExpired`, terminates the schedule, and leaves an endowment alone;
  - a death claim before the lapse date on a LAPSED policy registers; one after
    it is refused; one after maturity on an EXPIRED policy is refused; one before
    maturity reported after expiry registers;
  - a MATURITY claim before the maturity date is refused;
  - surrender reads the pinned version;
  - a term-banded rate prices by term on both the quote and the issuance path; a
    SINGLE quote for 24 months is refused.
- Every test class whose migration list touches `billing_schedule` or
  `base_rate_table` gains the new migration (the entities map the new columns).
- Frontend: status mapping, `claimGates`, the base-rate schema, the form.
- Then the full backend suite (`clean test`, dev backend stopped first), the
  frontend suite, and the affected e2e specs against the real stack after the
  migrations are applied to the dev database.

## Out of scope, on the record

- Billing anchors on the issue date, so a backdated commencement's earlier months
  are never billed. Pre-existing; not changed here.
- Invoices are raised a year ahead and post their receivable when raised. That
  accounting choice is unchanged.
- `issuePolicy` validates against the *active* version but stores the version the
  request names; nothing checks they are the same.
- No expiry notice to the customer: there is no template, and adding one is a
  communication decision.
