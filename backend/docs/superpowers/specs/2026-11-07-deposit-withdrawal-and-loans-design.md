# Deposit and Withdrawal, and loans made clear

**Status:** designed 2026-11-07, building. Branch `deposit-withdrawal` (part A), then parts B and C.

Follows `2026-11-04-money-movement-design.md` (D1-D14); this finishes what it left open and records the user's
decisions of 2026-11-07.

## Decisions (the user's, 2026-11-07)

| # | Decision |
| --- | --- |
| E1 | Money in is **Deposit**, money out is **Withdrawal**. One Deposit page holds every method; the method chosen decides what happens behind: mobile money asks the payer's phone, any other method records money already received. "Request payment", "Request savings deposit" and "Request mobile-money payment" go as separate actions. |
| E2 | **Record transfer in is removed** from the screens. Past TRANSFER_IN entries still show on statements. |
| E3 | **Propose adjustment stays**, finance only, as the correction tool. |
| E4 | **Every deposit recorded by hand needs a second person**, savings deposits included (they were one person since 2026-10-28), unless the recorder holds `collections.approve-own`. Mobile money needs no second person: the network confirms it. |
| E5 | **Maximum loan (% of value)** is set on each product version, and a product can switch loans off. |
| E6 | **Fixed-term deposits may lend.** |
| E7 | **A death claim, maturity or surrender pays what is left after the loan**: the unpaid loan is taken off and shown as a line on that payout. |
| E8 | The loan limit counts **interest owed**, not only the amount borrowed. |
| E9 | The Loans page shows everything a loan needs (part C below). |

## Part A: Deposit and Withdrawal

**Deposit page** (`/staff/receipts/new`, title "Deposit"). Fields, in order: Policy; **For** (Premium, Loan
repayment, Savings, offered only where the policy has them); **How is it being paid?** (Mobile money first, then
Cash, Bank deposit, Cheque, Lipa number, TANQR).

| Method | Fields | What happens | Who may |
| --- | --- | --- | --- |
| Mobile money | Phone number; Amount (Premium: what the oldest premium owes, stated, not typed) | A request to the phone; counts when the customer approves | `collections.manage` or `policies.service`; Savings also `accounts.transact` |
| Anything else | Reference, Amount, Received on, Paid by, Note | Recorded, waiting for a second person under Approvals | `collections.manage` |

A person who may only ask (customer service) sees only Mobile money; one who may only record sees only the others.
After a mobile-money request, or a savings deposit recorded by hand, the page closes into a receipt
(`Receipt` component) saying what was sent or recorded, with a way back to the policy. A premium or loan deposit
recorded by hand opens its deposit page, as today.

**Where Deposit and Withdrawal sit:**

- Billing tab, insurance policies: the Balance panel's **Deposit** (Primary) and **Withdraw**, as today, renamed.
- Account tab, savings policies: **Deposit** and **Withdraw** replace "Request savings deposit" and "Request
  savings withdrawal"; the Deposit opens the page with For: Savings.
- Loans tab: **Repay** on each loan opens the page with For: Loan and that loan.
- Invoices: the per-invoice "Request payment" goes for staff. The agent console keeps it until the agent console
  gets the Deposit page (out of scope).

**Behind, savings deposits by hand:** `PaymentTransaction.receivedTopUp` is created AWAITING_APPROVAL rather than
CONFIRMED; `Receipts.approve` confirms it (publishing `PaymentConfirmed`, purpose `ACCOUNT_TOP_UP`, which credits the
account as a mobile-money top-up does); `Receipts.reject` publishes `PaymentFailed` for it, so the top-up fails and
its reference may be used again. The approvals inbox names it "savings deposit".

## Part B: loan rules

- Product version: **Maximum loan (% of value)** (blank = loans not offered; 1-100), set in the product form on
  products with a value (Endowment, Whole life, Education savings, savings account, term account, fixed deposit).
  The loan interest rate stays company-wide (`TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE`) in this work.
- Available to borrow = value × max% − (borrowed + interest owed) − reserved.
- Death claim, maturity and surrender take the unpaid loan off what they pay; the loan settles from it, shown as a
  line "Loan repaid from this payout". E7 replaces the earlier decision Q1 ("repay the loan before surrendering").

**How the netting is built (docs 05 §6 "loan netting").** A port, `policy.api.LoanNetting`, declared where every
payout module already looks and implemented by policyloan (which already depends on policy), so claims,
benefitpayout, accumulation and policy itself net a loan with no new module dependency.
`settleFromPayout(policy, gross, currency, payout, payoutRef, by)` repays the policy's loans oldest first, interest then
principal, writes a SETTLEMENT transaction keyed `PAYOUT:ref` (asked twice, it answers what it took the first time),
releases the principal's pledge, and publishes the usual `policyloan.LoanRepaid` with `receiptMethod` `CLAIM_OFFSET`,
`SURRENDER_OFFSET`, `MATURITY_OFFSET` or `ACCOUNT_OFFSET`. The ledger clears that payout's payable instead of cash:

| Payout | Payable cleared (E-03-…) | Paid event |
| --- | --- | --- |
| Death claim | 2211 (IFRS 9: 2340) | `ClaimSettled.paidAmount` = what the rail paid; `settledAmount` stays gross for reinsurance and returns |
| Surrender | 2213 (IFRS 9: 2340) | `SurrenderPayoutRequested.grossAmount` books the payable; `SurrenderPaid` carries what the rail paid; an account's surrender sends `loanRepaidAmount` so it is not taken for charge |
| Maturity | 2212 | `PayoutPaid.paidAmount` is what the rail paid, so the ledger's gross (paid + withheld) leaves the loan's part to E-03-MATURITY |
| Account payout (fixed deposit, term account) | 2340 | `accumulation.PayoutPaid` carries what the rail paid; netting keyed on the deposit period or the term, never the attempt, so a retry pays only the rest |

Only a policy with a value asks (cash value or a pledge above zero), so a protection policy never touches policyloan;
"has a loan now" would be wrong, since a retried settlement must be answered with what was already taken.

Built 2026-11-07: claims, surrender (scale and account), benefitpayout maturity, fixed deposit and term account
maturities. Payment asks the rail for nothing when nothing is left. Next: the screens' "Loan repaid from this payout" line.
- Loans larger than the policy's value get a finance screen (the existing forced-lapse review queue).

## Part C: Loans page

Summary first: policy value, maximum loan %, already owed, **can borrow now**. Each loan: taken on, by whom, paid to;
borrowed, interest so far, owed now, rate; a plain status; a warning past 90% of the value; **Repay**; a **History**
(payout, interest, repayments, with dates). "Take a loan" states the most that can be borrowed.
