# IFRS 17 I3b — The guide's entries for money out, commission, loans and IFRS 9 (DRAFT, awaiting confirmation)

> Inline execution, no subagents. Branch `ifrs17-i3b` from main after I3a merges.

**Goal:** Replace I3a's interim PLAT-* journals with the guide's entries:
- claims B-01..B-04 and C-04;
- surrenders C-05/C-06;
- maturities C-01..C-03;
- survival and income D-01/D-02/D-05;
- annuities H-02/H-03;
- commission A-04/A-05/A-09/A-15;
- loans E-01..E-04/E-06 and C-02;
- IFRS 9 contracts G-01..G-08;
- levy A-19.

Every split is computed by the module that emits the event (user decision 4). Withholding and levy rates come from effective-dated configuration with no rate until Finance sets one (decisions 6 and 7).

## What exists today (researched 2026-10-06)

| Area | Events today | Gap for the guide |
|---|---|---|
| **Claims** | ClaimRegistered (no amount), ClaimAssessed, ClaimApproved (approvedAmount), ClaimRejected, ClaimSettled | No investment component on approval. No estimate field. |
| **Commission** | CommissionAccrued (statement, agent, policy, amount), CommissionPaid (statement, amount) | No FIRST_YEAR/RENEWAL type on the event. Clawback reversals publish nothing. Paid has no withholding. |
| **Policy loans** | LoanOriginated, LoanDisbursed, LoanRepaid (amount, outstandingBalance), LoanForcedLapseTriggered | Interest accrues in pg_cron SQL and publishes nothing. Repayment has no interest/principal split. Foreclosure carries no amounts. |
| **Payouts** | benefitpayout PayoutRequested (purpose, amount) and PayoutPaid (gross/paid/withheld) | No investment component share. No due-date LIC posting. |
| **Surrender** | SurrenderValueCalculated, SurrenderPaid | Loan offset not on the event. |
| **Accumulation** | PostingRecorded (entries with type and amount) | Not posted to the GL at all today. |
| **Unit-linked IFRS 9** | the same events as VFA | I3a's ANY rules post them to 2131/2132, which are VFA accounts. |

The chart already has every account needed, all in AUTO mode:
- payables: 1130, 1370, 2126, 2210, 2212, 2214, 2215, 2430, 2510/2520/2530, 2650;
- IFRS 9: 2310, 2320, 2330, 2340;
- expense and income: 5115, 5120, 5220, 6120, 7310, 7320.

## Proposed tasks

1. **Configuration.** `COMMISSION_WITHHOLDING_RATE` and `PREMIUM_LEVY_RATE` become accounting-policy-register keys.
   - Each is effective-dated and maker-checker approved.
   - Baseline is `NONE`, meaning not configured: nothing is withheld or levied.
   - The console shows "not configured".
   - The accounts come from the rules file: 2610 for withholding; 5220 and 2650 for the levy.
2. **Commission.**
   - CommissionAccrued gains `accrualType` (FIRST_YEAR, RENEWAL, OVERRIDE) and posts by channel:
     - agent: Dr 2123 / Cr 2510 (A-04/A-09);
     - broker: 2520;
     - bancassurance: 2530.
   - Movement type is IACF_COM / IACF_BRK / IACF_BANC / IACF_OVR.
   - A clawback publishes CommissionClawedBack: Dr 1370 / Cr 2123, IACF_CLAW (A-15).
   - CommissionPaid becomes Dr 2510 / Cr bank, plus Cr 2610 for tax withheld (A-05). Distribution withholds at payment (Q2).
3. **Claims.** On approval, claims states the claim's investment component:
   - the surrender value or fund value at the date of event;
   - the rule comes from the register's INVESTMENT_COMPONENT_RULE, read through `finaccounting::api`.

   Postings:
   - approval: Dr 5110 (rest) and Dr 2124 (IC) / Cr 2211 (B-01+B-02, C-04);
   - settlement: Dr 2211 / Cr bank (B-04);
   - rejection: nothing, because nothing was posted at registration (decision 5, Q1).
4. **Surrender and loans.**
   - Surrender approval states the surrender value, loan principal and loan interest: Dr 2124 / Cr 2125, 2126, 2213 (C-05).
   - Surrender paid: Dr 2213 / Cr bank (C-06).
   - Policyloan publishes `LoanInterestAccrued` monthly from a Java drain beside the SQL accrual: Dr 2126 / Cr 2121, LN_INT (E-02).
   - Repayment states its interest/principal split (E-03).
   - A disbursement fee, if the product has one, posts FEE_LOAN (E-01).
   - Foreclosure states its amounts (E-06).
5. **Benefit payouts.**
   - A due payout posts by purpose:
     - maturity: Dr 2124 / Cr 2212 (C-01);
     - survival or income: Dr 2124 (IC share from a new product-version field) and Dr 5115 / Cr 2214 (D-01/D-05);
     - annuity: Dr 5120 / Cr 2215 (H-02).
   - Paid: Dr 22xx / Cr bank, with Cr 2615 for withholding (C-03/D-02/H-03).
   - A loan outstanding at maturity is offset (C-02).
6. **IFRS 9 contracts.**
   - Accumulation's PostingRecorded posts G-05/G-06/G-07: 2320, 7310, 7320; withdrawals to 2340.
   - Unit-linked IFRS 9 contracts get G-01..G-04 (2310, 7310/2330, 7320, 2340). The I3a unit-linked ANY rules narrow to GMM/VFA/PAA.
   - Vesting posts G-08: 2320 to 2340 and 2121.
7. **Levy A-19.** Dr 5220 / Cr 2650 at the rate in force; nothing when NONE (Q4 decides when it posts).
8. **Console.**
   - Register keys for the two rates.
   - The posting-rules page flags an unconfigured rate.
   - The claim, payout and surrender pages show the investment component they were booked with.
9. **Gate.** Rules version 2; affected classes, full e2e, merge.

Size: about I3a's. Tasks 2-6 each touch an emitting module plus the rules. They could split into I3b-1 (tasks 1-5) and I3b-2 (tasks 6-7) if wanted.

## Design check against the code (2026-10-06, awaiting confirmation)

- **D1 — Money out is two steps, as the guide has it.**
  - The business event books the payable:
    - claim approved: Dr 5110 + 2124 / Cr 2211;
    - surrender approved (SurrenderPayoutRequested): Dr 2124 / Cr 2213;
    - payout due (PayoutRequested): 2212 maturity, 2214 survival/income, 2215 annuity.
  - Payment clears the payable to the bank.
  - I3a's EFT accrual pair (5110 to 2211 when an EFT is queued, reversed at execution) is removed, because approval already books 2211.
- **D2 — The rail (Q3).** The paid events don't say which rail paid them.
  - Payment adds `method` (MOBILE_MONEY / EFT) to `payment.DisbursementCompleted`.
  - Each paying module passes it on as `paymentMethod`: ClaimSettled, SurrenderPaid, PayoutPaid, CommissionPaid, LoanDisbursed, unitlinked.PayoutPaid.
  - Rules: EFT pays from 1130, otherwise 1140.
- **D3 — Investment component for claims and surrenders.**
  - Claims reads the register's INVESTMENT_COMPONENT_RULE for the policy's portfolio through `finaccounting::api` (a new allowed dependency).
  - Under SURRENDER_VALUE it uses policy's cash value at the date of event; under NONE, 0.
  - A surrender's whole value is the investment component (C-05).
- **D4 — Commission withholding.** Distribution reads COMMISSION_WITHHOLDING_RATE through `finaccounting::api` (a new dependency).
  - The statement stores the rate and the amount withheld, and the payout request pays the net.
  - CommissionPaid carries gross, withheld and paid.
- **D5 — A clawback is a negative accrual netted on the agent's next statement, not a debt.** It posts Dr 2510 / Cr 2123 IACF_CLAW, the reversal in the guide's A-14 note. 1370 is used only if a statement ever nets negative, which is out of scope. Distribution publishes CommissionAccrued for reversals too, with tierType and the agent's channel.
- **D6 — Policy loans are not offset at surrender, maturity or death today.** This is a business gap: policyloan never hears of those exits. I3b posts what actually happens, so there is no C-02/C-05 offset; the loan offset at exit is flagged as its own build.
- **D7 — Loan interest.** Interest accrues as INTEREST_ACCRUAL rows from SQL.
  - Policyloan gets a month-end Java drain that publishes `LoanInterestAccrued` per loan and month: Dr 2126 / Cr 2121 LN_INT.
  - LoanRepaid allocates interest first and states the split (E-03).
  - Foreclosure states principal and interest (E-06).
  - E-01's disbursement fee is skipped: no product charges one.
- **D8 — The levy is computed by finaccounting at collection** from PREMIUM_LEVY_RATE. It is the insurer's own charge, not a split of a customer cash flow. Billing doesn't change.
- **D9 — IFRS 9.**
  - Accumulation PostingRecorded entries post:
    - CONTRIBUTION, TOP_UP, TRANSFER_IN: Dr 1140 / Cr 2320;
    - ALLOCATION_CHARGE, POLICY_FEE: Dr 2320 / Cr 7310 (no 2330 deferral yet);
    - INTEREST: Dr 7320 / Cr 2320;
    - WITHDRAWAL, SURRENDER, MATURITY, DEATH_CLAIM, FREE_LOOK_REFUND: Dr 2320 / Cr 2340;
    - VESTING: Dr 2320 / Cr 2121 (G-08);
    - REVERSAL and ADJUSTMENT: by sign.
  - The IFRS 9 contract's own billing events need an explicit "posts nothing" rule. Without one they would queue as UNMAPPED. The rules file gains `post: false` rules, and the validator allows them.
  - Unit-linked IFRS 9 contracts get G-01..G-04 rules (2310/7310/7320/2340). The ANY unit-linked rules narrow to GMM/VFA/PAA.
- **Out of I3b:** unit-linked VFA exit splits F-08/F-09 stay as I3a posts them. Reinsurance stays for I3c.

## User answers (2026-10-06)

- **Q1 — (a).** Nothing posts at registration. Approval books the whole LIC, and rejection posts nothing.
- **Q2 — yes.** Distribution's payment run withholds at the register's rate (NONE means no tax) and pays the net. The ledger posts what distribution states.
- **Q3 — (a).** Mobile money payouts go through 1140; EFT and bank payouts go through 1130. This follows the payout's rail.
- **Q4 — (a).** The levy is recognised at collection.
- **Q5 — (a).** IFRS 9 invoices post nothing; accumulation's ledger posts the money (G-05).

## Questions as asked

- **Q1 — Claims LIC timing (decision 5).**
  - (a) Post nothing until approval; approval books the whole LIC (recommended, simplest, never an invented amount).
  - (b) Also add a claims-handler estimate at registration: B-01 to 2210 when entered; approval moves 2210 to 2211 and posts the difference.
- **Q2 — Commission withholding: where the tax is withheld.** It only makes sense where the agent is paid. Distribution's payment run computes net = gross − rate × gross (rate from the register; NONE means no tax) and pays the net; the ledger posts what distribution states. Confirm.
- **Q3 — Which bank account money out uses.** The guide pays claims and benefits from 1130 (claims and benefits payment bank account). Today everything leaves through 1140 (mobile money).
  - (a) 1140 for mobile money payouts and 1130 for EFT/bank;
  - (b) all payouts via 1130.
- **Q4 — When the levy is recognised.** "Premium recognised":
  - (a) at collection (cash);
  - (b) at invoice (written).
- **Q5 — IFRS 9 deposit and pension contracts' billing.**
  - (a) Invoices post nothing for IFRS 9; the money posts from accumulation's ledger (G-05) — recommended, one source of truth for 2320;
  - (b) keep the I3a premium posting and move it to 2320.
