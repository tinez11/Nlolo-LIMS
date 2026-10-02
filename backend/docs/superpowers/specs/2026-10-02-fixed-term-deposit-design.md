# Fixed-term deposit — a savings plan with a rate for its term

**Status:** design agreed 2026-10-02. Next: implementation plan. Built before step 4 (bonuses), whose
plan waits until this merges.
**Builds on:** step 3, the savings account (`accumulation`, merged at `5fc2c8f8`), and the
Idempotency-Key fix (`6dca71a8`).

## 1. The product, in the user's words

The client places one deposit for a term they choose: 3, 6 or 12 months. It earns a rate **for the
term**, so a 3-month deposit at 3% pays 3% once at the end, and then it is over. The rate depends on
the deposit band and the term:

| Deposit | 3 months | 6 months | 12 months |
|---|---|---|---|
| 500,000 – 5,999,999 | 3% | 4% | 5% |
| 6,000,000 – 10,999,999 | 4% | 5% | 6% |
| 11,000,000 – 20,999,999 | 5% | 6% | 7% |
| 21,000,000 and above | 6% | 7% | 8% |

Nothing may be added or taken out during the term. Terminated early, the deposit is returned with the
interest earned so far. At maturity the client chooses to reinvest or withdraw, under the terms the
product carries at that moment.

## 2. Decisions

| # | Question | Decision |
|---|---|---|
| D1 | Where it lives | **A third value basis, `DEPOSIT`, in `accumulation`.** Accumulation still owns the balance and every movement. The user's three ledger rules from step 3 apply unchanged. |
| D2 | What the rate is | **The total for the term**, not a yearly rate. 3% on a 3-month deposit is 3%, exactly. |
| D3 | Where the rates live, and the date range | **On the product version**, as a table of amount band × term → rate. The date range is the version's effective period, so new rates mean a new version filed with TIRA. A deposit keeps the rate fixed when its period started. |
| D4 | How interest builds | **Evenly by day:** deposit × rate × days held ÷ days in the term. It is posted ONCE: at maturity in full, or pro rata at an early exit. |
| D5 | Movements during the term | **None.** Top-ups, partial withdrawals and transfers in are refused. Adjustments stay: they are the two-person correction tool. |
| D6 | What is reinvested | **The deposit plus its interest.** |
| D7 | When the client chooses | **Any time before maturity, and changeable until then. With no instruction, the money is paid out.** |
| D8 | Term on reinvestment | **The client may choose a new term**, from those the version in force offers. |
| D9 | Bands | **Contiguous.** Each band runs up to the next one's start; the last is open-ended. |

## 3. Data model

### 3.1 Product (V21)

`product.deposit_rate_row`: `product_version_id`, `min_amount`, `max_amount` (null on the open-ended
band), `term_months`, `rate_percent`. Its presence makes the version `DEPOSIT`-basis. This mirrors V19's
reasoning: a separate table, so nothing that reads `product_version` needs a new migration.

`DepositPlanValidator` refuses:
- a category other than ENDOWMENT, WHOLE_LIFE or EDUCATION_SAVINGS (step 3's list);
- a version that is also SCALE or ACCOUNT, or also with-profits;
- bands with a gap or an overlap;
- a lowest band that does not start at the version's minimum sum assured, when the version sets one
  (with no minimum set, the lowest band IS the minimum: a smaller deposit finds no rate and is
  refused at issue);
- a band that does not offer every term the version lists, or a term missing from a band;
- a rate outside 0–100;
- any frequency loading, or any accumulation charge (a deposit has neither);
- any payout schedule row, since a deposit's maturity is accumulation's, not benefitpayout's.

`ProductApi.resolveDepositPlan(versionId)` returns the rows, plus `rateFor(amount, termMonths)`, which
returns empty when no row matches.

### 3.2 Accumulation (V3)

`accumulation.deposit_period`:
- `period_id`, `policy_number`, `seq` (1 for the first deposit, +1 for each reinvestment);
- `principal`, `term_months`, `rate_percent`, `rate_version_id` (the version the rate came from);
- `start_date`, `maturity_date`;
- `status`: RUNNING, MATURED, TERMINATED or CANCELLED;
- `interest_posted`, `closed_on`.

Unique `(policy_number, seq)`, and at most one RUNNING period per policy (a partial unique index).
The period is the rate's record: once written, its rate and dates never change, so a status change
is the only update.

`accumulation.maturity_instruction`:
- `policy_number`, `period_id`, `action` (REINVEST or PAY_OUT), `term_months` (REINVEST),
  `payee_ref` (PAY_OUT);
- `recorded_by`, `recorded_at`, `superseded_at`.

Append-only: a change writes a new row and marks the previous one superseded, so the history of what
the client asked for is kept.

`accumulation.account` gains `default_payee_ref` (§5.3).

## 4. How a deposit moves

### 4.1 Opening

The policy is issued as a **single premium**: the deposit is the premium and the sum assured, and the
term is one of the version's terms. The sum assured is what keeps the deposit inside the version's
bounds.

`PolicyIssued` opens the account, exactly as step 3 does. When `billing.PremiumCollected` credits the
deposit (CONTRIBUTION, no allocation charge), accumulation opens **period 1**:
- start = the credit date;
- rate = `rateFor(deposit, term)` on the policy's version, fixed on the period;
- maturity = start + term months.

It then calls `policyApi.restateMaturityDate(policyNumber, maturityDate)` (new). The single-premium
invoice may be paid days after issue, and the maturity must follow the money, not the issue date. A
deposit for which `rateFor` returns empty cannot be on the books: the period is refused, and the
failure is logged at ERROR and raised for operations, the way any AFTER_COMMIT failure is.

### 4.2 During the term

- The month-end run skips DEPOSIT accounts: no interest, no fee.
- Top-up, withdrawal and transfer-in requests on a DEPOSIT account are a 422: "This is a fixed-term
  deposit: nothing can be added or taken out until it matures on {date}".
- The closing quote and the console show interest earned so far: principal × rate × days held ÷ days
  in the term, unposted.

### 4.3 Maturity: the deposit maturity run

A drain (MonthEndDrain's shape, daily) picks up RUNNING periods with `maturity_date <= today` and
handles each in one transaction. It first posts `INTEREST = principal × rate` (one entry, source
`deposit-interest:<periodId>`), then follows the instruction:
- **REINVEST:** mark the period MATURED and open period n+1 for the whole balance. Its rate comes from
  the version **active for new business today** for the new amount and the instructed term (D3, D8),
  and `rate_version_id` records which version that was. Then restate policy's maturity date. If no
  row matches (the product stopped offering that term), fall back to PAY_OUT and say why on the
  statement.
- **PAY_OUT, or no instruction (D7):** mark it MATURED, post `MATURITY` for the balance (closing the
  account as step 3's maturity does), request the payout with purpose `DEPOSIT_MATURITY_PAYOUT` to the
  instruction's payee or else the default payee, and `policyApi.markMatured`.
- **No payee at all** (§5.3): post the interest and leave the balance on the account. The period is
  MATURED and the account is AWAITING_PAYEE. It earns nothing more, and the console shows it for staff
  to record a payee, which then pays it out.

### 4.4 Other exits

| Exit | What happens |
|---|---|
| Early termination | Step 1's surrender, two people. Value = balance + pro-rata interest to the approval date, less the version's surrender charge (the user's product sets none). Pro-rata interest is posted (source `deposit-interest:<periodId>`), then `SURRENDER`. Period TERMINATED. |
| Death | `valueAtDeath` = balance + pro-rata interest to the date of death. Period TERMINATED. |
| Free-look | The deposit back, no interest (step 3's FREE_LOOK_REFUND). Period CANCELLED. |

The `deposit-interest:<periodId>` source is shared by maturity and early exit on purpose. A period
is paid its interest once, whichever way it ends.

## 5. Changes to existing modules

1. **product:** V21, `DepositPlanValidator`, `resolveDepositPlan`, the publish request, and the
   console's publish form.
2. **policy:** `restateMaturityDate` (it moves the date the expiry sweep reads; for an ACCOUNT or
   DEPOSIT version nothing else keys off it). Surrender of a DEPOSIT version publishes
   `AccountSurrenderApproved`, as ACCOUNT does.
3. **The default payee (§5.3):** the number the deposit was collected from.
   - `payment.PaymentConfirmed` gains an additive `payerRef`.
   - Billing copies it onto `billing.PremiumCollected` as `payerRef`, absent for a field (cash)
     receipt.
   - Accumulation stores it as the account's `default_payee_ref` when the deposit is credited.
   - No consumer is required to read either key, and every existing one ignores unknown keys.
4. **payment:** `DEPOSIT_MATURITY_PAYOUT` in the purpose list.
5. **registries:** asyncapi for the new keys and `accumulation.DepositMatured` (published per maturity,
   with the period and what was done).

## 6. Console

- **Publish form:** a "Fixed-term deposit" value basis with a rate grid (bands × terms), validated
  as §3.1.
- **Account tab on a deposit:** the running period (principal, term, rate, start, maturity, interest
  so far) and the history of every period.
  - "At maturity" panel: Reinvest (with a term picker) or Pay out (with a payee). It shows the
    current instruction and who recorded it.
  - Top-up, withdrawal and transfer-in are not offered.
- **AWAITING_PAYEE accounts:** a list with "Record payee and pay", which needs finance.

## 7. Testing

- `DepositArithmeticTest`: 1,000,000 at 3% / 4% / 5% for 3 / 6 / 12 months is exactly 30,000 /
  40,000 / 50,000. Pro rata at 45 of 92 days. HALF_EVEN once.
- `DepositPlanValidatorTest`: every refusal in §3.1, plus the user's grid accepted.
- Band lookup at every boundary: 499,999 refused; 500,000, 5,999,999, 6,000,000, 20,999,999 and
  21,000,000 each land in their band.
- `DepositLifecycleIntegrationTest`:
  - the deposit is credited and the period opens at the right rate;
  - a top-up and a withdrawal are refused;
  - maturity with REINVEST and a new term opens period 2 at the **new version's** rate (a version
    published after period 1 started);
  - maturity with no instruction pays out to the collection number;
  - maturity with no number leaves the account AWAITING_PAYEE;
  - early termination pays pro rata, and interest is posted once.
- An isolation test for the two new tables, plus `LedgerImmutabilityTest`'s catalogue check.
- e2e: publish the grid, issue a deposit, see the rate, record REINVEST, bring it to maturity, and see
  period 2.

## 8. Out of scope, on the record

- Interest paid out monthly during the term (the user's product pays at the end).
- A penalty rate on early termination, beyond the version's surrender charge.
- Maker-checker on publishing a version: the open audit finding, unchanged by this.
- Partial reinvestment (D6 is the whole balance).
