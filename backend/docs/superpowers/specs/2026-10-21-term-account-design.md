# Term account — recurring contributions, locked until maturity

**Status:** built and merged 45c6ba38 (2026-10-21); follow-ups 4c85864c, 7c14bf89, 0bd516be, 4d1f5235, 7dbf8f3d.

**As built (differs from this document):**

- Rate band: two columns, `min_rate_percent` / `max_rate_percent`, on `product.version_accumulation_terms` (product V33) — not a new table.
- Case rate: `underwriting_case.proposed_rate_percent` (underwriting V24).
- `accumulation.term_account` has its own UUID `term_account_id` (V5), with a trigger fixing the rate and the maturity.
- The locked rate is applied in `AccountValuer`, not in `postMonthEnds`.
- `policy` needed changes: issuance refuses a term account without its case's rate in band; the expiry sweep skips it; surrender is refused in `requestSurrender`.
- payment V15 adds the `TERM_ACCOUNT_MATURITY_PAYOUT` purpose.
- `PayoutPlanValidator` exempts term accounts from the MATURITY row.
- On a term account the case amount is the contribution and is billed as the premium (`UnderwritingDecisionEventListener`); the savings quote has a `TERM_ACCOUNT` kind with a projection (`TermAccountProjection`) that solves the contribution for a target at maturity.
- The publish form fills the SAV portfolio on a draft.
- Since 4d1f5235 every charge is optional (an empty charge table charges nothing).
**Builds on:** step 3, the savings account (`accumulation`, merged at `5fc2c8f8`) — this reuses its
ledger, posting, month-end interest and statement machinery wholesale. Also borrows the maturity-drain
and default-payee pattern from the fixed-term deposit
(`2026-10-02-fixed-term-deposit-design.md`, merged), without taking on that product's single-lump-sum
shape.

## 1. The product, in the user's words

The client contributes regularly — monthly, quarterly or annually — toward a fixed term they choose,
at a rate fixed when the policy is issued, from within a band the product declares. Nothing may be
withdrawn, transferred out or surrendered during the term, however small a shortfall the account is
carrying — the whole point is that the client cannot touch it before the end. At maturity the balance
is paid out in full, automatically. Everything else — contributions, top-ups, charges, interest
crediting, statements, what a missed contribution does — is exactly the existing savings account.

## 2. Decisions

| # | Question | Decision |
|---|---|---|
| D1 | Where it lives | **A fourth value basis, `TERM_ACCOUNT`, in `accumulation`.** Reuses the SAME ledger, posting, month-end interest run, charges and statement machinery as `ACCOUNT` — the difference is a lock and a maturity date, not a new engine. (Name open to change at review.) |
| D2 | The rate | **Fixed per policy, at issuance — not a product-wide declaration.** The product version sets a minimum and maximum; the case names a figure within that band, the same way sum assured and term already are; once the policy is issued it never changes, even if the product's own band is later republished, or the product runs a rate declaration (which has no effect on an already-open `TERM_ACCOUNT` policy at all). |
| D3 | Term | **Reuses the product's existing min/max term-months eligibility bounds.** No new offered-terms table, unlike the deposit — the case names a term within those bounds, the same way sum assured and premium already are. |
| D4 | Movements during the term | **Money in is unrestricted; money out is refused outright.** Contributions, top-ups and transfers in post exactly as on a savings account — more money in does not break the lock. Withdrawals, transfers out and surrender are refused with the deposit's existing wording: "This account is locked until it matures on {date}." |
| D5 | A missed contribution | **Unchanged from the savings account.** The policy does not lapse: the account pays its own monthly fee until exhausted, exactly as today. The client cannot fix a shortfall by paying in early access to the money, but that is true of every locked product and is not a reason to invent a different rule here. |
| D6 | At maturity | **Automatic, full payout — no reinvest choice.** A daily drain (the deposit's maturity-drain shape, reused) posts final interest, closes the account, and pays the whole balance to the default payee (the number contributions were collected from), or leaves it `AWAITING_PAYEE` if none is known — identical fallback to the deposit's. Not asked for, so not built: a reinvest option, or a payee instruction recorded ahead of maturity. |
| D7 | Interest | **Unchanged arithmetic from `ACCOUNT`** — daily compounding, posted monthly — credited at the policy's own locked rate. Simpler than `ACCOUNT` in one respect: there is no floating declared rate to take a `max(declared, guaranteed)` against, because the rate was fixed at entry. |
| D8 | Charges | **The same `accumulation_charge` table `ACCOUNT` already uses, reused unchanged** (it is keyed only by `product_version_id`, not by value basis). |
| D9 | Death benefit | **Unchanged from `ACCOUNT`:** the higher of the account value and a declared percentage of contributions paid, valued as at the date of death. |
| D10 | Categories | **The same three as `ACCOUNT` today** — `ENDOWMENT`, `WHOLE_LIFE`, `EDUCATION_SAVINGS`. `ANNUITY` still waits on the vesting work, same reason `ACCOUNT` excludes it. |

## 3. Data model

### 3.1 Product

New table `product.version_term_account_terms`: `product_version_id` (PK/FK), `min_rate_percent`,
`max_rate_percent` (both `numeric(7,4)`, each checked 0–100, `max >= min`). Its presence makes the
version `TERM_ACCOUNT`-basis, the same way `version_accumulation_terms`'s presence makes one
`ACCOUNT`-basis.

`TermAccountPlanValidator` (mirrors `DepositPlanValidator`) refuses:

- a category other than `ENDOWMENT`, `WHOLE_LIFE` or `EDUCATION_SAVINGS`;
- a version that is also `SCALE`, `ACCOUNT` or `DEPOSIT`;
- a version with no minimum or maximum term set — unlike other bases, where both are optional, a
  `TERM_ACCOUNT` version has nothing to compute a maturity date from otherwise;
- `max_rate_percent < min_rate_percent`;
- any payout schedule row — maturity is accumulation's, the same reasoning `DepositPlanValidator`
  already applies.

`product.accumulation_charge` (the existing by-policy-year charges table) is reused unchanged —
nothing about it is `ACCOUNT`-specific today.

### 3.2 Underwriting

The case (and its proposal/amendment path) gains `proposedRatePercent`.

A new rule, `refuseRateOutsideTermAccountBand` (mirrors `refuseAnUnissuableDeposit`'s shape), refuses:

- a `TERM_ACCOUNT` case whose named rate is missing, or outside `[minRatePercent, maxRatePercent]`;
- a requested term outside the version's min/max term bounds — reuses
  `rejectTermOutsideProductBounds` unchanged, since term bounds are the same generic field every
  category already carries.

Enforced in both of the same two places the deposit's equivalent rule already runs —
`UnderwritingApiImpl` at case-opening, and `ProposalAmendments` at amendment — for the same reason: a
change accepted after a decision that would now fail this rule is a case that can never issue.

### 3.3 Accumulation

New table `accumulation.term_account`: `policy_number` (PK/FK to `account`), `rate_percent` (locked at
open, never updated after), `maturity_date`, `status` (`RUNNING`, `MATURED`, `AWAITING_PAYEE`),
`closed_on`. Its presence is what marks an account `TERM_ACCOUNT`-kind — the same way
`deposit_period`'s presence marks `DEPOSIT`-kind. `accumulation.account` itself gains no new kind
column.

`accumulation.account.default_payee_ref` (already added for the deposit) is reused unchanged for the
maturity payout.

## 4. How money moves

### 4.1 Opening

Same as `ACCOUNT`: `PolicyIssued` opens the account. The first `PremiumCollected` opens the
`term_account` row: `rate_percent` = the case's locked rate, carried onto the policy at issuance
exactly as sum assured and term already are; `maturity_date` = commencement date + term months
(`Policy.applyTerm`, unchanged).

### 4.2 During the term

Contributions and top-ups post exactly as `ACCOUNT`'s existing §5.1. Month-end interest and the policy
fee post exactly as `ACCOUNT`'s existing §5.2, except the rate used is the `term_account` row's own
locked `rate_percent`, never a product-wide declaration — a `rate_declaration` proposed for this
product has no effect on an already-open `TERM_ACCOUNT` policy at all (a named test, not just an
assumption). A missed contribution behaves per D5, unchanged. Withdrawal, transfer-out and surrender
requests are refused with a 422: "This account is locked until it matures on {date}" (the deposit's
existing wording, reused).

### 4.3 At maturity

A daily drain (the deposit's maturity-drain shape, reused): finds `term_account` rows `RUNNING` with
`maturity_date <= today`, posts final interest to that date, posts `MATURITY` for the whole balance
(closing the account, exactly as `ACCOUNT`'s own maturity path already does), requests the payout to
`default_payee_ref`, or marks `AWAITING_PAYEE` if none is known — identical fallback to the deposit's.

## 5. Changes to existing modules

| Module | Change |
|---|---|
| **product** | `version_term_account_terms`, `TermAccountPlanValidator`, the publish request, and the console's publish form. |
| **underwriting** | `proposedRatePercent` on the case and the amendment path; `refuseRateOutsideTermAccountBand`, enforced at open and amend. |
| **policy** | None beyond what `ACCOUNT` already needed — `applyTerm` already computes a maturity date from term and commencement. |
| **accumulation** | `term_account` table; the maturity drain; the withdrawal/transfer-out/surrender refusal; month-end interest reads the policy's locked rate instead of a product declaration. |
| **console** | Publish form: a `TERM_ACCOUNT` value basis with min/max rate fields, instead of a single guaranteed rate. Underwriting case form: a **Rate (%)** field next to Sum assured/Term/Premium, bounded by the product's band, following the same pattern every other bounded field on that form already uses. Policy Account tab: shows the locked rate and maturity date; no top-up or withdrawal buttons offered beyond what the lock allows — matching the deposit's existing "not offered" treatment — plus an `AWAITING_PAYEE` list entry alongside the deposit's. |

## 6. Testing

- Arithmetic: daily compounding at a locked rate matches a worked example (same method as `ACCOUNT`'s
  existing interest test, different input).
- `TermAccountPlanValidatorTest`: every refusal in §3.1.
- A rate outside `[min, max]` is refused at case-opening and at amendment; one inside is accepted.
- A term outside the version's bounds is refused the same way the deposit's already is.
- A `rate_declaration` proposed for a `TERM_ACCOUNT` product does not change the crediting rate on an
  already-open policy.
- A missed contribution does not lapse the policy (reuses `ACCOUNT`'s existing assertion, pinned for
  this basis too).
- A withdrawal, transfer-out and surrender request during the term are each refused with the lock
  message.
- A top-up and a transfer-in during the term both succeed.
- Maturity drain: pays out to the default payee; leaves `AWAITING_PAYEE` when none is known; posts
  final interest exactly once.
- e2e: publish a version with a rate band, open a case with a rate inside it (accepted) and one
  outside it (refused), issue, contribute, bring the account to maturity, see the payout.

## 7. Out of scope, on the record

- Reinvestment at maturity (the deposit's feature, not asked for here).
- A payee instruction recorded ahead of maturity — the deposit lets the client choose any time before
  maturity; this product pays out automatically, so there is no instruction panel, only the
  default-payee/`AWAITING_PAYEE` fallback.
- `ANNUITY` as a category (waits on the vesting work, same as `ACCOUNT`).
- Changing the locked rate after issuance, for any reason.
- A penalty or different treatment for a missed contribution beyond what `ACCOUNT` already does.
