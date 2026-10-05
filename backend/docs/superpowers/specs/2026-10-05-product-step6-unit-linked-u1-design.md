# Product step 6 / U1: unit-linked core — design

**Status:** design agreed section by section with the user on 2026-10-05; awaiting review of this written spec.
**Scope:** U1 only, the core engine. U2 (switches, partial withdrawals, top-ups, surrender charges, changing the split) is a later spec.
**Guide reference:** Life_Insurance_Products_Guide.pdf §9 (ULIP) and §21.4 (product rules as data; separate the bucket from the tap; date-driven batch jobs; full audit trail).

## 1. What exists today, and what this replaces

- `product.fund_definition`: a fund code and a "current NAV" per product version. It is used by nothing except the publish form. **Replaced** by links from a version to funds in the new register (§3). Existing rows are migrated to register funds where a code matches, and are otherwise dropped with the table. The dev catalogue holds no unit-linked policies.
- `policy.fund_holding`: units and book value per policy and fund. Nothing writes it. **Dropped**; holdings are derived from the unit ledger (§5).
- Every UNIT_LINKED version today behaves like term life. After U1, a UNIT_LINKED version is sold, charged, valued and paid only through the `unitlinked` module.

## 2. Decisions (the user's answers; do not drift)

| # | Decision |
|---|---|
| Q1 | Build U1 (the core) first, then U2. |
| Q2 | **Single price** per fund per valuation date (no bid/offer). The allocation charge carries the margin. |
| Q3 | **Shared fund register** per insurer. A version lists the funds it offers. The management charge belongs to the fund. |
| Q4 | **Maker-checker prices** (two people). Approved prices are never edited; corrections re-run the affected movements. **A missing price never becomes zero, and never falls back to an older price.** Money waits for the next approved price. |
| Q5 | Death rule **configurable per version**: HIGHER_OF (sum assured, fund value) or SUM_ASSURED_PLUS_FUND. Cost of insurance is charged on the sum at risk the rule implies. |
| Q6 | Lapse rule **configurable per version**: EXHAUSTION (**the default**) or NON_PAYMENT. Optional minimum premium-paying years, inside which non-payment lapses under either rule. |
| Q7 | Fund management charge is **built into the price**. It is recorded for disclosure only; no units move for it. |
| Q8 | **Revalue the liability on each approved price**: one posting per fund. |
| Q9 | Surrender is allowed after a version's minimum years and is two-person. **Forward pricing is mandatory:** a surrender is never valued at a price that already exists when it is requested or approved. |
| Q10 | Forward pricing works by valuation date plus a **daily cut-off per fund** (Dar es Salaam time). |
| Q11 | Death: units freeze at claim **registration** and sell at the first price after it. Cost-of-insurance charges dated after the date of death are refunded into the claim. |
| Q12 | Free-look returns the customer to the free-look position by **unwinding the actual transactions**: each charge entry is reversed and each unit actually held is sold forward. It is **not** a hard-coded formula, and the genuine investment gain or loss is preserved as it happened. |

## 3. Fund register and prices

**`unitlinked.fund`** (per tenant): `fund_id`, `code` (unique per tenant), `name`, `currency`, `asset_class` (EQUITY | BOND | MONEY_MARKET | BALANCED), `annual_management_charge_percent` (disclosure only), `cut_off_time` (local time, Africa/Dar_es_Salaam), `status` (OPEN | CLOSED). A CLOSED fund accepts no new BUY orders but keeps pricing, valuing and selling existing units.

**`unitlinked.fund_price`**: `fund_id`, `valuation_date`, `price` NUMERIC(19,6) > 0, `status` (PROPOSED | APPROVED | SUPERSEDED | WITHDRAWN), `proposed_by`, `proposed_at`, `approved_by`, `approved_at`, `move_reason`, `supersedes_price_id`.
- Proposed singly or by CSV (`fund_code,valuation_date,price`, all-or-nothing per file).
- **Approval:** `approved_by` must differ from `proposed_by` (enforced in the service and by a CHECK). Approval is refused before `valuation_date` at the fund's `cut_off_time` has passed.
- At most one APPROVED price per (fund, valuation_date), enforced by a partial unique index.
- A move of more than **`price_move_alert_percent`** (tenant setting, default 10) from the previous APPROVED price needs a non-blank `move_reason` at proposal. It is a prompt, not a block.
- An APPROVED price is immutable. A PROPOSED price may be WITHDRAWN by its proposer.
- **No defaulting, ever.** Nothing in the module computes a value from a zero price, a missing price, or a carried-forward price.

**Corrections:** a correcting price is proposed for the same (fund, date) with `supersedes_price_id`, and approved by a second person. On approval:
1. The old price becomes SUPERSEDED.
2. Every unit entry priced by it is reversed with a PRICE_CORRECTION entry and re-entered at the new price. All entries are appended; nothing is updated.
3. That (fund, date)'s revaluation posting is reversed and re-posted.

Entries on later dates keep their own prices; holdings follow from the corrected units.

If a corrected movement funded a payout already PAID (surrender, death, maturity, lapse or free-look), the money difference becomes a **price-correction adjustment** (`unitlinked.price_correction_adjustment`: policy, amount ±, direction OWED_TO_CUSTOMER | OWED_BY_CUSTOMER, status OPEN | SETTLED | WAIVED) on a staff queue. It is never absorbed silently. A difference owed to the customer is settled through benefitpayout; one owed by the customer is settled or waived by two people.

## 4. Product version terms and sale

**Unit-linked terms on the version** (product module, a new `unit_linked_terms` table plus child tables; publish overload count moves with it):
- offered funds: at least one, all OPEN at publish;
- `allocation_bands`: policy-year bands with allocation %, contiguous from year 1, the last band open-ended, each 0 < % ≤ 100 (the same shape as step 3's account charges);
- `monthly_policy_fee` ≥ 0;
- `mortality_basis`: UNISEX | BY_SEX, plus a mortality table (annual rate per 1,000 of risk by age band, and by sex when BY_SEX) with contiguous age bands from the minimum entry age, the **last band open-ended** (a whole-of-life policy has no maximum attained age), else publish refuses;
- `death_rule`: HIGHER_OF | SUM_ASSURED_PLUS_FUND;
- `lapse_rule`: EXHAUSTION (default) | NON_PAYMENT, and `minimum_premium_years` (nullable = none);
- `minimum_surrender_years` ≥ 0;
- `low_fund_warning_months` ≥ 1;
- `minimum_premium` per allowed frequency, with SINGLE allowed only if listed;
- `sum_assured_multiple_min` and `sum_assured_multiple_max` of the annual premium (a single premium counts as its own amount);
- free-look days are already required for UNIT_LINKED in PayoutPlanValidator.

**The base-rate pipeline does not apply.** The premium is the customer's choice within the version's minimums; the cost of insurance is taken from units monthly (§6). UNIT_LINKED versions carry no base-rate table and are not sent through `quotePremium`.

**Sale (underwriting case):** the case records a **unit-linked choice**: fund split (whole %, each > 0, across offered funds, totalling exactly 100), premium amount and frequency, and sum assured. These are checked live against the version terms. A new `underwriting.unit_linked_choice` table follows the annuity_choice / funeral_application precedent, so no case column is added. Medical underwriting runs as normal. ACCEPT issues the policy with the chosen premium and sum assured, and writes the split to `unitlinked.policy_allocation` (fixed in U1). Manual issue refuses UNIT_LINKED, as it does FUNERAL.

## 5. Unit ledger, pending orders and forward pricing

**Binding rule (the forward-pricing invariant):** an order received at instant `t` for fund F is bound to valuation date `D = local_date(t)` if `local_time(t) < F.cut_off_time`, else to the next calendar day. `D` is computed once at creation and stored, never recomputed. An order bound to `D` is priced only by the first APPROVED price for F with `valuation_date ≥ D`. Because no price for `D` can be approved before `D`'s cut-off, **no order is ever priced at a price that existed when it was received.**

**`unitlinked.pending_order`**: policy, fund, `side` (BUY | SELL), `amount` (money; null for SELL ALL), `sell_all` flag, `received_at`, `bound_date`, `source_type` + `source_ref` (unique together: one source produces one order per fund), `status` (WAITING | PRICED | CANCELLED), `priced_by_price_id`, `priced_at`.

**Pricing:** approving a price for (F, P) prices every WAITING order for F with `bound_date ≤ P`. An order whose own date had no price is swept to P, and its entry records both `bound_date` and the `valuation_date` actually used. The run is idempotent per (order, price).

**`unitlinked.unit_entry`**: append-only. A trigger rejects UPDATE and DELETE (the step 3 precedent). Columns: policy, fund (nullable for money-only entries), `entry_type`, `units` NUMERIC(19,6) signed, `price` and `price_id` (null for money-only), `amount` NUMERIC(19,2) signed, `valuation_date`, `source_type` + `source_ref` (unique with `entry_type` and fund), `reverses_entry_id`, `created_by`, `created_at`.

Entry types: ALLOCATION, ALLOCATION_CHARGE (money only), POLICY_FEE, COST_OF_INSURANCE, DEATH_SALE, SURRENDER_SALE, MATURITY_SALE, LAPSE_SALE, FREE_LOOK_SALE, CHARGE_REFUND (money only, free-look and post-death COI), REINVESTMENT, PRICE_CORRECTION, WRITE_OFF (money only, uncovered charges at exhaustion).

**Allocation arithmetic** for a premium `P` in policy year `y`:
1. `allocated = round_half_even(P × allocation%(y), 2)`; `allocation_charge = P − allocated`.
2. Split `allocated` across the funds by the policy's %s, each part rounded HALF_EVEN to 2 dp, with the remainder placed on the largest-% fund (first by fund code on a tie), so the parts sum exactly to `allocated`.
3. `units = truncate(part ÷ price, 6)`. The customer never receives more units than the money buys; the sub-unit residue is recorded on the entry.

**Holdings** = Σ units per (policy, fund). **Fund value (display)** = holdings × the latest APPROVED price. Fund value is never posted.

**Sell arithmetic:** a SELL of money amount `A` cancels `units = ceil(A ÷ price, 6)`, capped at the holding. SELL ALL cancels the whole holding, and its proceeds are `round_half_even(units × price, 2)`.

## 6. Monthly charges and lapse

**Charge run:** a nightly sweep, the same scheduling and tenant-iteration pattern as the existing sweeps. On each policy's monthly charge date (stepped from the issue date the way billing steps due dates; `InstalmentDates` semantics), for every policy that is in force and not frozen:
- `policy_fee` = the version's monthly fee;
- `sum_at_risk` = HIGHER_OF: `max(0, SA − FV)`; SUM_ASSURED_PLUS_FUND: `SA`. Here `FV` = the holdings × the **latest APPROVED price strictly before the charge date**. Using a known price is acceptable because this sizes the insurer's charge and is not a customer transaction; the units sold are still forward-priced;
- `cost_of_insurance` = `round_half_even(rate(attained_age, sex) ÷ 12 × sum_at_risk ÷ 1000, 2)`. An unrecorded sex on a BY_SEX table refuses that policy's charge and raises a staff alert, never guessing;
- one SELL order per fund for (fee + COI) in proportion to fund values, with the remainder on the largest, bound to the charge date.

**Frozen policies** take no charges: a registered death claim, a surrender awaiting approval or pricing, a free-look cancellation in progress, or a policy in exhaustion.

**Exhaustion:** if a charge SELL cannot be covered when priced, the whole holding is sold (LAPSE_SALE), the uncovered part is recorded as WRITE_OFF (never billed), and:
- under **EXHAUSTION**, the policy lapses (policy.PolicyLapsed with reason FUND_EXHAUSTED) and cover ends;
- billing's arrears sweep **does not lapse** an EXHAUSTION policy for non-payment once `minimum_premium_years` have passed. This is the same exception step 3 account policies have. Unpaid invoices remain owed and payable.

**NON_PAYMENT**, or non-payment inside `minimum_premium_years`: billing's normal grace and lapse apply. On lapse, every unit sells forward (LAPSE_SALE) and the proceeds are paid to the policyholder as a **lapse surrender value** through benefitpayout (awaiting payee as usual). Charges stop.

**Low-fund warning:** after a policy's charge run is priced, if FV < `low_fund_warning_months` × that month's charges, a UNIT_LINKED_LOW_FUND notice is sent (SMS + email, sw/en), at most once per calendar month per policy.

## 7. Death, surrender, maturity, free-look

All four:
1. freeze the policy;
2. cancel WAITING BUY orders and return their money;
3. create SELL ALL orders bound by the cut-off at the triggering instant;
4. pay through benefitpayout (withholding, payee review, payment rail).

- **Death claim:** registration is the trigger. The claim's claimable cover is **pending** until every SELL ALL is priced. Approval before then is refused with "waiting for unit prices of <date> for <funds>". Benefit = HIGHER_OF: `max(SA, proceeds)`; PLUS_FUND: `SA + proceeds`; plus CHARGE_REFUND of every COST_OF_INSURANCE entry whose charge date is after the date of death. **Rejection** reinvests the proceeds (REINVESTMENT BUY orders at the next forward price), unfreezes the policy, and resumes charges.
- **Surrender:** requested by one person, approved by a second. It is refused inside `minimum_surrender_years`, while a policy loan is outstanding, or while frozen. The SELL ALL is bound at the **approval** instant, not the request: binding at the request would let the approver see the price before deciding. Proceeds are paid to the policyholder; there is no surrender charge in U1. The policy closes SURRENDERED once paid.
- **Maturity:** a version with a term. The expiry sweep creates SELL ALL bound to the maturity date (it runs before the cut-off); proceeds are paid, and the policy closes MATURED. A version without a term is whole of life.
- **Free-look:** through the existing free-look cancellation flow. Every ALLOCATION_CHARGE, POLICY_FEE and COST_OF_INSURANCE entry the policy has is reversed by its own CHARGE_REFUND entry; every unit sells forward (FREE_LOOK_SALE); WAITING BUY orders return their money. Refund = proceeds + refunded charges + returned money. Every part is traceable to real entries. **There is no formula.** The policy is cancelled once the refund is paid.

## 8. Accounting

New accounts (finaccounting migration): **2150** Unit-Linked Policyholder Liability (under 2100), **4310** Unit-Linked Charges Income (under 4300), **5600** Change in Unit-Linked Liability (under 5000).

| Event | Posting |
|---|---|
| ALLOCATION + ALLOCATION_CHARGE for one premium | DR 2140 `P` / CR 2150 `allocated`, CR 4310 `allocation_charge` |
| POLICY_FEE, COST_OF_INSURANCE sold | DR 2150 / CR 4310 |
| Price approved for fund F: Δ = units in issue **before** the orders this price prices × (P_new − P_prev). Units the price itself buys or sells carry no movement, so the revaluation posts first and the orders are priced after it. | Δ > 0: DR 5600 / CR 2150; Δ < 0: the reverse |
| DEATH / SURRENDER / MATURITY / LAPSE / FREE_LOOK sale | DR 2150 / CR 5100 |
| Payout paid (existing rule, unchanged) | DR 5100 / CR cash |
| CHARGE_REFUND | DR 4310 / CR 5100 |
| REINVESTMENT (rejected death claim) | DR 5100 / CR 2150 |
| WRITE_OFF | no posting (never billed) |
| PRICE_CORRECTION | reverses and re-posts the affected rows above; an already-paid difference: DR/CR 5600 against 2130 |

Each posting is keyed on its source entry or price id: one reference, one posting.

**Invariant:** the 2150 balance = Σ over funds (units in issue × current APPROVED price). It is asserted after every integration scenario and shown on a staff reconciliation report. 5100 nets to the insurer's own cost (SA − FV on a HIGHER_OF death; zero on a surrender). The investments backing the funds stay off-platform; 5600 is the line IFRS 17 work will match against them.

## 9. Architecture

A new Spring Modulith module **`unitlinked`**, the way `annuity` was added in D1.
- It owns: fund, fund_price, price_correction_adjustment, policy_allocation, pending_order, unit_entry; the charge-run, pricing and expiry hooks; and valuations.
- It depends on `product::api` (terms), `policy::api` (policy state, freeze), `party::api` (sex, DOB), and `benefitpayout::api` (payouts).
- It listens to: billing.PremiumCollected (filtered to UNIT_LINKED and premium purpose), claims.ClaimRegistered / ClaimRejected / ClaimApproved, policy expiry and lapse, and free-look cancellation.
- It publishes: unitlinked.UnitsAllocated, ChargesTaken, FundRevalued, UnitsSold, PriceApproved, PriceCorrected, plus finaccounting rules for them.
- It reuses step 3 **patterns** (immutable entries, `IdempotentRequests`, one reference → one posting, the statement-PDF builder) but not its money ledger.

## 10. Console

- **Configuration → Funds**, a new screen: the register (add, close); prices by date (propose single or CSV, approve as a second person, the move-reason prompt, corrections); "waiting for price" counts per fund and date; the price-correction adjustment queue.
- **Publish version:** a Unit-linked terms section replaces the fund-code/NAV rows: offered funds, allocation bands, fee, mortality basis and table (CSV paste), death and lapse rules, minimum premium and surrender years, premium minimums, sum-assured multiples, low-fund months. The rating and base-rate panels are hidden for UNIT_LINKED.
- **Underwriting case:** the fund split (must total 100%), premium, frequency and sum assured, validated live.
- **Policy page → Units tab:** holdings per fund (units, price and its date, value), pending orders with their bound dates, ledger entries, and a statement PDF.
- **Surrender:** two-person, stating "priced at the next price after approval".
- **Claim:** "payable amount waiting for unit prices of …" until priced.
- **Notices** (SMS + email, sw/en): UNIT_LINKED_ALLOCATED, UNIT_LINKED_LOW_FUND, UNIT_LINKED_LAPSED_EXHAUSTED, UNIT_LINKED_PROCEEDS.

## 11. Testing

- **Pure (milliseconds):**
  - the binding rule around the cut-off, including midnight and the 00:00–03:00 UTC/EAT trap;
  - allocation rounding and remainder; unit truncation and sell rounding;
  - cost of insurance under both death rules and both mortality bases;
  - the holiday sweep;
  - the price-move threshold; the terms validator messages.
- **Integration (Testcontainers):**
  - forward pricing can never use a known price, for a premium, a surrender (bound at approval) and a death (bound at registration);
  - maker-checker: same-person approval refused, approval before the cut-off refused, only one APPROVED per date;
  - a correction re-runs entries, and an already-paid correction creates an adjustment;
  - exhaustion lapse vs non-payment lapse, and billing never lapses an EXHAUSTION policy past its minimum years;
  - free-look unwinds the actual entries, with the refund equal to the sum of real entries;
  - a rejected death claim reinvests;
  - a redelivered event is a no-op;
  - **2150 = units × price** after every scenario;
  - ModularityTests.
- **E2E:** two people price a fund; a policy is sold with a 60/40 split; a premium buys at the next day's price; a monthly charge run; a surrender approved and paid at a later price.

## 12. Out of scope

U2: fund switches and switching charges, partial withdrawals, top-up premiums, surrender charges, changing the split for future premiums.

Also not in U1: bid/offer pricing; IFRS 17 measurement (the next phase); agent-console sale screens; automated price feeds from fund managers (prices are entered or uploaded by staff).
