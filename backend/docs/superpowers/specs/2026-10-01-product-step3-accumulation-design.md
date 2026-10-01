# Product step 3 — the accumulation engine (sub-project B)

**Status:** design agreed 2026-10-01, not built.
**Builds on:** step 0 (rolling billing, EXPIRED at term end, pinned version), step 1 (cash-value
scale, surrender, paid-up, loans), step 2 / sub-project A (the payout engine,
`plans/2026-10-01-product-step2-payout-engine.md`).

---

## 1. Where this sits

The guide's §21.4 asks for two reusable engines: a payout engine (the "tap") and an accumulation
engine (the "bucket"). Step 2 built the tap. This is the bucket.

| # | Sub-project | Unlocks |
|---|---|---|
| A | Payout engine — step 2 | endowment, money-back, return-of-premium term, guaranteed income, free-look |
| **B** | **Traditional accumulation, with interest crediting — this document** | **savings plan; the saving years of a deferred annuity and pension** |
| C | Bonuses | participating / with-profit |
| D | Annuities, including vesting from B | annuities, pensions |
| E | Unit-linked | ULIP, investment-linked pension |
| F | Riders as child coverages with their own premium | riders on all base products |

What the guide asks of this step: account value, charges, and interest credited at a declared rate
with a guaranteed minimum, plus statements (§21.4); contributions and transfers for pensions
(§12–13); flexible premiums (§21.1, currently unbuilt).

**Why it is needed now.** Nothing on the platform credits value except step 1's per-mille scale, so
every savings product that is not on a scale holds a cash value of zero. Loan limits and surrender
quotes both read that field, which is why loan origination is effectively unavailable on real data.

## 2. Decisions

| | Question | Answer |
|---|---|---|
| Q1 | How a product's value is defined | **Two value bases, chosen per product version.** `SCALE` is step 1's sum assured × per-mille. `ACCOUNT` is a ledger: contributions in, charges out, interest credited. Both write `PolicyAccount.cashValueAmount`. |
| Q2 | How the interest rate is set | **A guaranteed minimum on the version; a rate declared each period on top**, credited at `max(declared, guaranteed)`. A declaration needs a second person to approve it. |
| Q3 | How interest is calculated | **Accrued on the daily balance, posted monthly.** The declared rate is an effective annual rate, converted to a daily factor by compounding, so a full year lands on the declared rate exactly. |
| Q4 | Charges | **An allocation charge and a monthly policy fee**, both on the version, both varying by policy year. No cost-of-insurance charge. |
| Q5 | Death benefit | **The higher of the account value and a percentage of contributions paid** — step 2's `deathBenefitPremiumPercent`, guide §6. The ceiling is taken from the account, not the sum assured. |
| Q6 | Contributions | **A regular scheduled contribution plus top-ups at any time. A missed contribution does not lapse the policy**: the account keeps paying its own fee, and the policy lapses only when it cannot. |
| Q7 | Other movements | **Partial withdrawals** (two people, minimum balance) and **transfers in**. Transfers out deferred. |
| Q8 | Statements | **A console statement, a yearly SMS summary, and a generated PDF** filed against the policy. |
| Q9 | Categories that may use `ACCOUNT` | **`ENDOWMENT`, `WHOLE_LIFE`, `EDUCATION_SAVINGS`.** `ANNUITY` waits for D, because a pension must not be sellable before it can vest. |
| — | Architecture | **A new `accumulation` module, which owns the account balance and every transaction that changes it.** |
| — | Ledger integrity | **Entries are immutable.** A correction is a reversing or adjustment entry, never an edit or a delete. |
| — | Idempotency | **One source reference produces at most one posting**, however many times its event is delivered. |
| Q10 | General ledger | **Unchanged.** IFRS 17 owns classification and measurement later. See §8. |

## 3. Ownership

**`accumulation` owns the balance and every transaction that changes it.** Other modules ask;
accumulation posts; nothing else writes the balance.

- **The ledger is the truth.** An account's balance is defined by its entries.
- **`PolicyAccount.cashValueAmount` is a projection** of that balance, restated only by accumulation
  after each posting. Surrender quotes and loan limits keep reading it unchanged.
- **Step 1's `recalculateCashValue` skips `ACCOUNT` versions**, or it would overwrite the projection
  on every premium.
- **A policy loan is a lien, not a movement.** `loan_encumbrance_amount` stays where it is; the
  balance is unchanged, and what can be withdrawn or surrendered is the balance less the lien.
- **Paid-up on an `ACCOUNT` version** stops contributions and leaves the account paying its own fee —
  the Q6 behaviour. There is no sum-assured reduction, because the account is the value.
- **Rate declarations are per product.** Each account is credited at the declared rate for its
  product or the guarantee on its own pinned version, whichever is higher — so a later version with a
  lower guarantee never lowers an earlier customer's floor.

Dependencies run one way: `benefitpayout → accumulation → policy, product`.

## 4. Data model

### 4.1 Immutability

Enforced by the database, not by convention — the precedent is
`policy/V2__endorsement_append_only_and_money_checks.sql`.

- `app_role` is granted `INSERT` and `SELECT` on `posting` and `ledger_entry`, and nothing else.
- A trigger refuses `UPDATE` and `DELETE` on both, so an owner connection cannot edit history either.
- A correction is a `REVERSAL` (equal and opposite, `reverses_entry_id` set) followed by a new
  correct entry. A unique index on `reverses_entry_id` means an entry is reversed at most once.
- A manual `ADJUSTMENT` needs a second person.

### 4.2 `accumulation.posting`

The atomic unit. One header per source, its entries written in the same transaction.

**`UNIQUE (tenant_id, source_type, source_ref)`** — the idempotency guarantee is this index, not a
check-then-insert. Two concurrent deliveries cannot both commit: the loser hits the index, rolls back
whole, and is logged as a duplicate. A posting is immutable once committed; no entries are added to
it afterwards.

| Source | `source_ref` |
|---|---|
| Contribution, top-up | `invoice:<invoiceId>` |
| Transfer in | `transfer:<requestId>` |
| Month-end interest and fee | `month-end:<yyyy-mm>`, per policy |
| Withdrawal | `withdrawal:<requestId>` |
| Surrender | `surrender:<surrenderRequestId>` |
| Maturity | `instalment:<instalmentId>` |
| Death | `claim:<claimId>` |
| Free-look | `freelook:<cancellationId>` |
| Reversal | `reversal:<entryId>` |

`billing.PremiumCollected` already fires once per invoice, only on the edge into `PAID`, and carries
`invoiceId`; consumers already dedupe on it. The index makes that unbreakable.

### 4.3 `accumulation.ledger_entry`

| Field | Purpose |
|---|---|
| `posting_id` | the posting it belongs to |
| `seq` | per policy, contiguous; a gap is detectable |
| `entry_type` | `CONTRIBUTION`, `TRANSFER_IN`, `ALLOCATION_CHARGE`, `POLICY_FEE`, `INTEREST`, `WITHDRAWAL`, `SURRENDER`, `MATURITY`, `DEATH_CLAIM`, `FREE_LOOK_REFUND`, `ADJUSTMENT`, `REVERSAL` |
| `amount` | signed |
| `balance_after` | immutable with the entry; a check asserts it equals the previous `balance_after` plus `amount`. **`CHECK (balance_after >= 0)`** — no account goes negative. |
| `effective_date`, `posted_at` | interest follows the effective date |
| `reverses_entry_id`, `reason`, `created_by`, `approved_by` | the audit trail |

There is **no daily accrual table**. Unposted interest is not a financial transaction, so it is not
an entry. The month-end run derives the month's interest by replaying entries day by day.

### 4.4 Other tables

- **`rate_declaration`** — per product: `rate_percent` (effective annual), `effective_from`,
  `PROPOSED → APPROVED`, `proposed_by`, `approved_by`, with a two-person CHECK. Refused if it starts
  before the last interest already posted for any account on the product, because otherwise it would
  rewrite interest customers have already been told about.
- **`withdrawal_request`** — amount, payee, two-person, its own status machine.
- **`statement`** — policy, period, the last `seq` included, document reference.

### 4.5 Product side, per version

- `value_basis` — `SCALE` | `ACCOUNT`.
- `guaranteed_rate_percent`.
- `minimum_balance` — applies to **partial withdrawals only**: a withdrawal may not leave less than
  this. It is not a lapse threshold; an account lapses only when it reaches zero (§5.2).
- One charges table by policy-year range: allocation % for contributions, allocation % for transfers
  in, and the monthly policy fee.

## 5. How money moves

### 5.1 In

- **Contribution.** `billing.PremiumCollected` → one posting: `CONTRIBUTION +amount`, then
  `ALLOCATION_CHARGE` at the current policy year's rate.
- **Top-up.** Billing raises a one-off invoice on request; paying it produces the same
  `PremiumCollected`, marked as a top-up. One money-in path, and mobile-money collection is reused.
  Verify at plan time whether billing supports a one-off invoice; if not, that is a small addition to
  billing.
- **Transfer in.** Recorded by staff with its source and a document, at the transfer allocation rate.

### 5.2 The month-end run

One Java drain, exactly-once per `(policy, month)` through the posting index.

1. **`INTEREST`.** Replay the month's daily balances; each day earns at `max(declared, guarantee)`
   for that day, so a mid-month declaration splits correctly. Rounded once per account per month, to
   cents, half-even.
2. **`POLICY_FEE`** for the month.
3. **Exhaustion.** If the fee exceeds the balance, take what is there, close at zero, and lapse the
   policy through `policy::api`.

### 5.3 Out

| Event | Accumulation posts |
|---|---|
| Partial withdrawal, two people | `WITHDRAWAL`. Refused if it would leave less than the minimum balance net of any loan lien. A failed disbursement is a `REVERSAL`, never an edit. |
| Surrender | interest to date, then `SURRENDER` for the whole balance. The version's existing surrender-charge schedule (step 1) still applies: the customer is paid the balance less that charge, and the charge is retained by the insurer — it is not a second ledger entry, because the account is emptied either way. |
| Maturity | interest to date, then `MATURITY` for the balance; the payout instalment's amount is what that entry moved |
| Death | interest **to the date of death**, then `DEATH_CLAIM`. Contributions with an effective date after the death are reversed out of the account and returned to the payer through billing's existing premium-refund path (`billing.PremiumRefundDue`, already used by credit life); they count towards neither the balance at death nor the percentage-of-contributions floor. |
| Free-look | `FREE_LOOK_REFUND`, closing the account to zero; the refund itself stays step 2's premiums less itemised deductions |

### 5.4 The change to step 1's surrender

Today `policy` approves a surrender and also computes and requests the money. For an `ACCOUNT`
version it keeps the first half — the two signatures, cover stopping — and the payment becomes
accumulation's: valued at approval, including interest to that day, then requested. The quote at
request time becomes an estimate, and the approver sees the figure that will actually be paid.

Policy cannot call accumulation directly — that dependency would be a cycle — so accumulation reacts
to `policy.PolicySurrendered`, and policy publishes no `SurrenderPayoutRequested` for an `ACCOUNT`
version.

## 6. Statements

- **Console.** A *Statement* tab on the policy: pick a period; see opening balance, each posting
  grouped by type, closing balance. Computed from entries and asserted to reconcile:
  `opening + movements = closing = last balance_after`.
- **PDF.** Generated with Apache PDFBox from the same computed statement — no second calculation.
  Filed through `DocumentApi` under the policy as a new `ACCOUNT_STATEMENT` type and downloadable from
  the tab. An annual run generates one per account; staff can generate one on demand. There is still
  no automatic customer delivery route — the customer portal is deferred and email is Mailpit-only —
  so delivery is by staff.
- **SMS.** The annual run sends a one-line summary through a new communication template: the balance
  and the interest credited that year.
- **A statement records what it was built from** — its period and the last `seq` included — so a
  regenerated one is provably identical, and a later correction appears as a reversal in the next
  statement rather than silently changing an old one.

PDFBox is a new dependency: Apache-2.0, no native binaries.

## 7. Changes to existing modules

| Module | Change |
|---|---|
| **product** | `value_basis`, guaranteed rate, minimum balance and the charges table on the version. The validator allows `ACCOUNT` only on the three Q9 categories; refuses a version carrying both a per-mille scale and an `ACCOUNT` basis; permits a new `ACCOUNT_VALUE` payout amount basis only on an `ACCOUNT` version's MATURITY row. |
| **policy** | `recalculateCashValue` skips `ACCOUNT` versions. A new `restateCashValue` for accumulation's projection. Surrender on an `ACCOUNT` version stops at cover and the two signatures. Paid-up stops billing with no sum-assured cut. |
| **billing** | Ignores a `PaymentConfirmed` whose purpose is not a premium (§10.1). The lapse exception itself lives in policy (§10.2). |
| **benefitpayout** | Depends on `accumulation::api`. An `ACCOUNT_VALUE` MATURITY row is expanded at issue with **no amount**, exactly as step 2 treats a premium return, because the value is not known until the day it falls due. When it falls due, it closes the account and takes its amount from that closing entry. The death ceiling reads the account rather than the sum assured. |
| **claims** | Unchanged — it already asks benefitpayout for the death ceiling. |
| **payment** | A `WITHDRAWAL_PAYOUT` purpose. |
| **communication** | The annual statement SMS template. |
| **document** | The `ACCOUNT_STATEMENT` type. |
| **console** | Product form fields for the basis, guarantee and charges; the Statement tab with PDF download; withdrawal and transfer-in actions on the policy; a rate-declaration screen with its two-person approval. |

## 8. General ledger — deliberately unchanged (Q10)

The GL is left exactly as it is; IFRS 17 owns classification and measurement later.

The consequence, recorded so nobody mistakes it for an oversight: until IFRS 17 lands, a surrender,
withdrawal or maturity on a savings product books as **claims expense**, and contributions stay in
**Unearned Premium**. For a savings product both are a misclassification — a contribution is a deposit
the insurer owes back, and paying a balance out repays it — so reports will overstate claims and
understate liabilities by the savings flowing through them.

Accumulation still publishes an event for every posting. Audit consumes them, and a later GL change
can subscribe without touching accumulation.

## 9. Testing

Aimed at the rules this design rests on:

- A concurrent double delivery of one invoice produces exactly one posting.
- `UPDATE` and `DELETE` on entries and postings are refused, for `app_role` and for the owner.
- The month-end run executed twice posts once.
- A mid-month declaration splits a month's interest by day.
- A year of daily accrual at a declared 5% credits exactly 5%.
- `balance_after` equals the previous balance plus the amount, and never goes below zero.
- Exhaustion takes the remaining balance, closes at zero and lapses the policy.
- A missed contribution on an `ACCOUNT` policy is never lapsed by the arrears sweep.
- A withdrawal below the minimum balance net of a loan lien is refused.
- A failed withdrawal disbursement is reversed, not edited.
- A statement reconciles, and regenerated is identical.
- A rate declaration starting before already-posted interest is refused.
- A declaration's proposer cannot approve it.

## 10. Revisions made while planning (2026-10-01)

Each was found by reading the code the plan builds on. The plan
(`plans/2026-10-01-product-step3-accumulation.md`) follows these, not the earlier wording above.

1. **Top-ups are a payment collection, not a billing invoice.** `billing.premium_invoice` is
   partitioned, requires exactly one origin (a billing schedule or an enrolment file), and is
   scanned by a pg_cron sweep that would mark an unpaid top-up OVERDUE and recommend a lapse.
   Payment already collects money independently of invoices (`recordCollectionRequest` with a free
   `sourceRef`), so accumulation requests the collection itself. Payment V9 gives collections a
   `purpose` (`PREMIUM` by default, `ACCOUNT_TOP_UP`) carried on `PaymentConfirmed` and
   `PaymentFailed`; billing ignores any collection that is not a premium — today it parses every
   `sourceRef` as an invoice id.
2. **The lapse exception lives in policy, not billing.** Billing does not lapse anything; it
   publishes `billing.PolicyLapseRecommended` and a policy-side listener calls `lapsePolicy`. That
   listener declines for an `ACCOUNT` version. Billing keeps its reminders for a missed
   contribution, which remain useful.
3. **`recalculateCashValue` already skips `ACCOUNT` versions.** It returns early when the version has
   no cash-value scale, and the validator refuses a scale and an account basis together, so no code
   change is needed — only a test pinning it.
4. **Exhaustion needs its own lapse.** `Policy.canLapse()` allows only ACTIVE and SUSPENDED, so a
   REINSTATED or PAID_UP account could never lapse on exhaustion. A dedicated
   `lapseExhaustedAccount` accepts ACTIVE, REINSTATED, PAID_UP and SUSPENDED and publishes the same
   `policy.PolicyLapsed`.
5. **Contributions paid after a death are returned with the death benefit, not through billing.**
   `billing.PremiumRefundDue` is published by billing; accumulation cannot drive it without billing
   depending on accumulation. The payer's estate is the claimant, so the reversed amount is added to
   the death payout as its own component.
6. **On a death, every entry dated after it is reversed** — contributions, and month-end interest
   and fees for any month the death falls inside — and interest is then posted from the last posting
   to the date of death. Before this, the balance at death was ambiguous when a death was reported
   weeks after a month-end run.
7. **Interest compounds daily, including on interest not yet posted.** That is what makes "5% a
   year" honest when posting is monthly: with a constant balance, twelve monthly postings compound
   to the declared rate exactly, before rounding. Each posting rounds once, to the cent.
8. **A product's value basis and terms live in their own table**, `version_accumulation_terms`,
   not as a column on `product_version`. `ddl-auto` is `none`, so a mapped column missing from a test
   database breaks every query on that table, and roughly fifty test classes read
   `product_version`.
9. **The death ceiling gains the date of death.** `deathBenefitCeiling(policyNumber, ceiling)` keeps
   its behaviour; a new overload taking the date of death serves an `ACCOUNT` version, which must be
   valued as at the death, not on the day the claim is approved.
10. **A surrender on an `ACCOUNT` version** publishes `policy.AccountSurrenderApproved` instead of
    `policy.SurrenderPayoutRequested`. Accumulation posts the closing entry and requests the payment
    under the existing `SURRENDER_PAYOUT` purpose with the surrender request id as its source, so
    policy's existing `SurrenderPaymentListener` still marks the request PAID.
11. **Rate declarations are proposed by an ADMIN and approved by a different ADMIN or a
    FINANCE_OFFICER.** Proposing is a pricing decision, which on this platform is ADMIN's; approving
    is a second signature on money.

## 11. Out of scope, on the record

- Transfers out to another insurer's scheme — reuse the surrender flow later.
- Vesting a deferred annuity or pension into an annuity (D).
- Bonuses (C).
- A cost-of-insurance charge, and death cover above the account value.
- IFRS 17 classification and measurement (§8).
- Overpayment surplus: `PremiumCollected` carries the invoice's amount, not what was paid.
- Automatic delivery of the PDF statement to the customer.
