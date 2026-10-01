# Product step 1 — cash value, surrender, paid-up and loans (with term-banded rates)

**Date:** 2026-09-30 (plan only — nothing built). **Builds on:** step 0
(`product-step0-foundations`). **Unlocks:** endowment, whole life and education savings as
products that genuinely behave as such, and makes the existing policy-loan module usable.

Every constraint below was checked against the code on 2026-09-30.

---

## 1. A correction to the roadmap, first

The roadmap called step 1 a "policy value **ledger**". For the products this step serves — traditional
endowment, whole life, education savings — that is the wrong model, and the guide says so itself
(§5, §6): *surrender value per policy year, usually from a table supplied by the actuaries; loan
limits as a % of surrender value; paid-up sum assured.* A traditional cash value is a **function of
the contract** — (policy year, term, entry age, sum assured, premiums paid) — read from an actuarial
table, not a running balance of premiums and charges.

A true account-value ledger (premium allocation, charges, interest, units) is what **unit-linked and
deferred-annuity accumulation** need. It belongs to steps 5–6 and is not built here. Building it now
would mean inventing allocation and charge rules no product on the platform has.

## 2. What exists, and the constraints it sets

| Fact (verified) | Consequence for this design |
|---|---|
| `policy.policy_account.cash_value_amount` exists; loans, forced lapse and the surrender quote all read it; nothing writes it (0 on all 183 dev accounts) | **Reuse it** as the materialised current cash value. No change to `policyloan` is needed for loans to start working. |
| `PolicyAccount.availableLoanValue` ignores `max_loan_to_value_percent` (flagged M3 simplification) | Apply the LTV cap here; without it a loan could equal the whole cash value. |
| `policy` may not depend on `billing` or `policyloan` (both depend on `policy`) | Policy cannot ask billing what is paid, nor policyloan what is owed. Both arrive **by event**. |
| `billing.PremiumCollected` carries no due date | Billing must add `paidToDate` — the latest due date up to which *every* invoice is paid. Billing owns invoices, so contiguity is its computation. |
| `PremiumCollectedEventListener` activates cover in its own REQUIRES_NEW transaction | The value update runs in a **separate** transaction, so a failure can never roll back activation. |
| Payment disburses only on module-specific request events (`claims.ClaimSettlementRequested`, `policyloan.LoanDisbursementRequested`, …) and answers with `payment.DisbursementCompleted/Failed` | Surrender adds `policy.SurrenderPayoutRequested` and a payment handler, and policy consumes the answer — the shape claims already uses. **No workflow engine is needed**; the 501's stated reason (Camunda) no longer applies. |
| Loan balance **with accrued interest** lives in `policyloan` | Netting a loan out of a surrender payout needs an event round-trip (decision **Q1**). |
| One event → one fixed debit/credit pair (`PostingRule`), whole amount | A surrender payout needs a posting rule and an account; no policyholder-reserve liability exists (premium sits in 2140 Unearned Premium and is never earned). |
| `surrender_charge_schedule` JSON exists and is applied by the quote | A cash-value table normally *is* the surrender value. Both at once would double-deduct — publish refuses a version carrying both. |
| No PAA/GMM validation exists at publish | **Left as is (Q5).** A comprehensive IFRS 17 model is coming separately; recorded in §8. |
| `publishVersion` is a telescoping set of 4 overloads | Add terms through a parameter object (`VersionTerms`) rather than a fifth overload. |
| **Test-list blast radius:** a new *column* on an existing entity forces its migration into every test class that builds that table (base_rate 54 classes, policy 47). A new *table* with its own entity costs nothing until something queries it. | Shapes the whole data design — §3. |
| Batch 3 left maker-checker out of product publishing (open audit finding) | Unchanged here, but noted: a cash-value table is exactly the kind of number that finding is about. |

## 3. Data design, shaped by the test cost

**One product migration (product V16), paid for once.** D4 already has to add columns to
`base_rate_table`, which forces V16 into all 54 product test classes. Everything product-side rides
that one edit:

- `base_rate_table.term_from_months / term_to_months` — D4 exactly as step 0's plan designed it.
- `product.cash_value_table` (new table): `(version, policy_year, [age_from..age_to], [term_from..term_to],
  cash_value_per_mille, paid_up_per_mille NULL)`. Bands optional, as base rates are; lookup the same
  shape; overlap refused per the base-rate rule.
- `product_version.cash_value_basis_reference / cash_value_basis_date` — **the actuarial sign-off**.
  Required whenever a cash-value table is present, refused without one — the `TiraFiling` pattern.
  This is how "build now, sell when the actuary signs" is enforced: engines and tests use a sample
  table with a test reference; a real product cannot publish values nobody signed.
- `product_version.paid_up_basis` (`PROPORTIONATE` | `TABLE`) and `min_years_for_value` (years before
  any surrender or paid-up value exists).

The migration-list edit is mechanical (insert V16 after the V15 line in 54 files; four shapes of
trailing comma). Done by a pattern-only script, verified by `clean test-compile` and the full suite —
the same path step 0's billing V8 edit took.

**One policy migration (policy V29), paid for by new tests only.** `policy.policy_value` (new table,
keyed by policy number): `paid_to_date`, `premium_months_paid`, `paid_up_sum_assured`, `computed_at`.
Written **only** for policies whose version carries a cash-value table, so none of the 47 existing
policy test classes ever queries it. The current cash value itself stays in the existing
`policy_account.cash_value_amount`.

## 4. Behaviour

**Cash value.** On `billing.PremiumCollected` for a cash-value policy: store `paidToDate`, derive
completed premium years, look up the table cell (policy year, entry age, term), set
`cash_value_amount = SA × per_mille / 1000`. Zero before `min_years_for_value`. Value only changes
when paid-to crosses an anniversary, which only happens on a payment — **no anniversary sweep needed**.

**Loans.** Unchanged code path; now finds a real cash value, capped by the version's LTV %.

**Surrender** (`POST /policies/{n}/surrender`, Idempotency-Key required, as the spec already says):
1. Gate: policy in force or lapsed-with-value, cash value > 0, past `min_years_for_value`, no
   in-flight reservation, and **no outstanding loan** (Q1 — refused, with the reason).
2. Record a `SurrenderRequest` (REQUESTED → APPROVED → PAID | FAILED | IN_DOUBT), quoting the value
   from the policy's **own** version (step 0 D3).
3. Approval by a **different** staff member (Q4, server-enforced). **Approval ends cover** (Q2): the
   policy → SURRENDERED with the approval date as its effective date, billing terminates the
   schedule, and `policy.SurrenderPayoutRequested` goes to payment.
4. On `payment.DisbursementCompleted`: request → PAID, posting `policy.SurrenderPaid` → Dr **5110
   Surrender Benefits** (new) / Cr 1120 Cash. On failure the request → FAILED and can be re-paid with
   a new idempotency key; cover is **not** restored. IN_DOUBT is held, never retried blindly
   (payment's existing rule).
5. `wasOnRiskOn` must know the surrender effective date, so a death the day after approval is
   refused while one the day before is not — stored on `policy_value`.

The 501 and the `processes/{id}` stub are replaced; OpenAPI first, types regenerated.

**Paid-up** (`POST /policies/{n}/paid-up`): the customer stops paying and keeps reduced cover.
`PROPORTIONATE`: paid-up SA = SA × premium months paid ÷ premium months payable — needs **no actuary
table**, and is the common East African basis. `TABLE`: SA × `paid_up_per_mille`. New status
**PAID_UP** — in force, no premium: `wasOnRiskOn` and `isInForce` treat it as on risk; billing
terminates the schedule; DEATH and MATURITY coverages are restated to the paid-up SA through an
endorsement row; distribution must **not** claw back (it is not a lapse). A lapsed policy with value
may also convert (the guide's non-forfeiture option).

**Term-banded rates (D4)** as designed in step 0's plan: both pricing paths take the term; a SINGLE
premium over 12 months stays refused.

## 5. Frontend — what the rules require

| Rule (PRODUCT.md / DESIGN.md / PLAN.md) | Consequence |
|---|---|
| Moving money lives on a full page, never a drawer | Surrender on `PolicyDetailPage` only; the drawer keeps no surrender control. |
| Confirm Act: the question, the consequence **with real values**, what happens if wrong; the verb is not "Confirm"; danger tint when money leaves | "Surrender POL-… and pay TZS 1,240,000.00 to +2557…" / cover ends and cannot be revived. Paid-up gets its own (not danger, recoverable-ish copy stated honestly: it cannot be undone either). |
| Gate panel before a mutating submit; gates assert only what the backend enforces | Surrender and paid-up each get a gate set mirroring the server's refusals, with the server's wording. |
| 501 renders disabled with a reason; now real | The disabled "Surrender (501)" button becomes a live action on eligible policies and a disabled-with-visible-reason one elsewhere. |
| Six buckets, literals from the spec | `PAID_UP` → **active** (in force). `SurrenderRequest` status: REQUESTED/APPROVED → pending, PAID → success, FAILED → danger, IN_DOUBT → warning. |
| Idempotency-Key minted once per submit in the Zustand action | Both new POSTs. |
| Render only what the platform proves | The "Always 0.00 until the platform credits cash value" note goes away only for cash-value policies; term policies keep an honest 0.00. |
| Forms mirror backend validation and wording | Product authoring gains the cash-value table editor, basis reference/date, paid-up basis and min years; base-rate rows gain term bands. |
| Visible disabled reasons, not `title` (the defect both a11y sweeps missed) | `LoansPanel` still hides its reason in `title`; fixed in passing. |
| E2E couples to accessible names | `staff-policies.spec` (surrender disabled) and `staff-policy-loans.spec` (origination disabled at 0.00) stay true for **term** policies and are extended, not rewritten; new specs cover a cash-value policy end to end. |

## 6. Business decisions — answered 2026-09-30

1. **Q1 — a loan outstanding at surrender: REFUSE.** Surrender is refused while any loan on the
   policy is outstanding; the staff member is told to have it repaid first. Automatic netting (the
   policy ↔ policyloan round-trip) is a later follow-up, not this step.
2. **Q2 — cover stops at APPROVAL.** The surrender's effective date is the approval date: from then
   the policy is off risk even though the payout may still be in flight. A payout that then fails
   leaves the request FAILED for a retry — it does **not** put cover back.
3. **Q3 — paid-up: PROPORTIONATE by default, TABLE optional.** Yes.
4. **Q4 — two-person rule: YES.** The requester of a surrender may not approve it, enforced
   server-side as claims enforce assessor ≠ decider.
5. **Q5 — IFRS 17: NOT enforced here.** A comprehensive IFRS 17 model is coming separately. This step
   adds **no** PAA/GMM rule for cash-value products; the gap is recorded (§8) so the IFRS 17 work
   picks it up.
6. **Q6 — minimum years: 2 to 3.** `min_years_for_value` is a **required** field on any version
   carrying a cash-value table, validated to 2 or 3 — no platform default, because the figure is the
   product's, and a silently defaulted number is how a product ends up paying value nobody priced.

## 7. Order of work

1. Product V16 + the 54-file list edit, alone, green. (D4 + tables + basis gate.)
2. Billing `paidToDate` on `PremiumCollected`; policy V29 `policy_value`; cash value computed; LTV
   cap. Loans now work.
3. Paid-up (status, restatement, billing stop).
4. Surrender request, approval, payout choreography, posting.
5. Frontend: authoring, policy page actions and gates, status maps, e2e.
6. Full suite, real-stack e2e with the dev DB migrated, final whole-branch review.

## 8. Out of scope, on the record

- Reserve accounting (building a policyholder liability as premiums arrive; releasing unearned
  premium) — an IFRS 17 valuation question, not a table lookup.
- Automatic maturity payout and money-back schedules — step 2.
- Endowment death benefit "higher of SA or % of premiums" — step 2, with the payout engine.
- Whole-life maturity at a fixed age (e.g. 100) — no product field holds a maturity age; whole life
  here is lifelong (null term).
- Reinsurance cessions do not shrink on paid-up or end on surrender (reinsurance only consumes
  activation today) — a gap in reinsurance, recorded, not widened.
- Maker-checker on publishing (existing open audit finding).
- **IFRS 17 (Q5):** no PAA/GMM rule is added for cash-value products. A savings contract measured
  under PAA would be wrong; the coming comprehensive IFRS 17 model must own that rule, along with
  reserve accounting above.
- **Loan netting at surrender (Q1):** refused for now; the automatic netting round-trip is a
  follow-up.
