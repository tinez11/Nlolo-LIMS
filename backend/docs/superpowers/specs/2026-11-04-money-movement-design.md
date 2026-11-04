# Money movement: one road in, one road out

**Status:** designed 2026-11-04, not started. Branch `money-movement`.

**Why:** the finance review of 2026-11-04 (artifact "Money Movement Review") found that money in has six
separate roads (gateway, receipt, field receipt, loan repayment, top-up, lender refund), that a receipt is
tied to one invoice and capped at what it owes, and that the ledger posts cash only when an invoice becomes
PAID, at the invoice's amount. Four results are critical:

| # | Finding | Where |
| --- | --- | --- |
| F1 | A part payment never reaches the ledger; after a lapse it stays in 2122 for good | `BillingApiImpl.applyConfirmedPayment` publishes `PremiumCollected` only on the PAID edge |
| F2 | A credited invoice posts the full invoice as cash (13,800 posted, 9,600 received) | `BillingApiImpl` puts `invoice.getAmount()` on `PremiumCollected` |
| F3 | Money confirmed after an invoice is settled is kept as a receipt row and posted nowhere | `PremiumInvoice.applyPayment` returns early on PAID/WAIVED |
| F4 | A loan repayment is booked with no money confirmed, no cap, no second person; the customer role can call it | `PolicyLoanApiImpl.recordRepayment`, `PolicyLoanController` line 112 |

And six high ones: an overpayment cannot be recorded (`Receipts.record` refuses more than owed, then refuses the
slip's reference a second time), no reversal of a confirmed receipt, no home for advance money, loan repayments
always post to 1140, loan overpayment drives principal negative, and three money-out paths have no second person.

**The user's aim (2026-11-04):** money movement that is simple, direct and manageable.

## 1. Decisions (the user's, 2026-11-04)

| # | Question | Decision |
| --- | --- | --- |
| D1 | Whose balance does money land on? | **One balance per policy.** Money for policy A pays only policy A. |
| D2 | What does money pay first? | **Premiums, oldest first.** A loan is paid only when the deposit says "For: Loan", and that money goes only to the loan. |
| D3 | What happens to money left over? | **It stays as the policy balance** and pays the next premiums automatically. It goes back only when the customer asks, or when the policy ends. |
| D4 | Money with no policy reference | **Not recorded until someone knows the policy** (no bank or wallet feed exists). The month-end bank check is the control. An unidentified-money list can come with a feed. |
| D5 | Approvals | **One rule:** one person asks, a second approves; the existing `*-own` permissions let one person do both, logged against their name. Table in §6. |
| D6 | Build shape | **A policy balance inside billing** between the money and the invoices (approach 1 of 3). |
| D7 | Credit life: a borrower leaves after the lender paid | **Unchanged:** the lender is paid back by bank transfer (billing V13, 2026-10-30). |
| D8 | Names | **Deposit, Withdrawal, Payout, Refund**, the same everywhere; the whole app's wording follows §5. |
| D9 | Billing tab | **Keep the Payment schedule**; add a short Balance panel first and a Statement panel last (§7.1). |
| D10 | Portal Swahili | As in §5.2, approved. |
| D11 | Who records a loan repayment | **The deposit permissions** (`collections.*`), not `loans.manage`. |
| D12 | Extra money on savings-type policies | Savings account, term account, Fixed Deposit: **becomes a savings deposit**. Unit-linked: **policy balance**, unless the deposit says "For: Savings" (today's top-up rules apply). |
| D13 | Policy ends by death with a balance | **A separate withdrawal that waits for a payee**, the "matured deposits waiting for a payee" pattern. Not added to the claim. |
| D14 | Savings withdrawals' approval | **Stays on `accounts.approve`**. Nobody's access changes. |

## 2. The three steps

Every money movement is one of three steps, and only these.

1. **Deposit.** Money arrives, any amount, any method, recorded once against a policy and a purpose. Mobile money
   counts when the gateway confirms it; anything a person records counts when a second person confirms it (or the
   same person, by permission).
2. **Apply.** Billing pays what the policy owes from its balance, oldest invoice first. What is left stays as the
   balance. This runs when a deposit lands and again whenever an invoice is raised, so a balance pays the next
   premium by itself.
3. **Withdrawal or payout.** Money leaves only by request → approval → paid → confirmed.

## 3. Where it lives

**The payment module stays the one road in.** `Receipts` (renamed in the UI to deposits) records against a
**policy and a purpose**, not an invoice:

| Purpose (`payment_transaction.purpose`) | UI "For" | Goes to |
| --- | --- | --- |
| `PREMIUM` (default) | Premium | The policy balance (insurance policies) or the invoice then savings (savings-type, D12) |
| `LOAN_REPAYMENT` | Loan | The loan named, up to what it owes; the rest to the policy balance |
| `TOP_UP` | Savings | The savings account or unit-linked funds, as today |

What `Receipts.record` stops doing: requiring an invoice, refusing more than an invoice owes (except a loan, capped
at the loan's balance), and summing waiting receipts against one invoice. What it keeps: method, reference unique
per method, date not in the future, amount above zero, agent cash linked by `fieldReceiptId`, second person.

**The policy balance lives in billing.** Billing already owns what a policy owes (invoices) and what came back off
it (`PremiumCredit`). No new module, no new module dependency.

- `billing.policy_balance`: one row per insurance policy (tenant, policy number, currency, version).
- `billing.balance_entry`: add-only. One row per movement: `DEPOSIT`, `PREMIUM_PAID`, `WITHDRAWAL`, `REVERSAL`,
  each with amount, the payment transaction or invoice it concerns, and who and when. The balance is the sum;
  nothing is updated or deleted.
- Tenant isolation and grants as every other billing table.
- **The allocator** applies under a `PESSIMISTIC_WRITE` lock on the `policy_balance` row (the lock
  `InvoiceRollForward` already uses on a schedule), so two deposits at once cannot apply the same money twice.

**Modules talk by events, as now.** Billing never calls policyloan and policyloan never calls billing. Policyloan
listens for `payment.PaymentConfirmed` with purpose `LOAN_REPAYMENT`, as it already listens for loan payouts, and
publishes the surplus for billing to deposit.

## 4. Flows

| Case | What happens |
| --- | --- |
| Cash 150,000; one 50,000 invoice open | Deposit 150,000 → balance. Apply pays the invoice (`PREMIUM_PAID` 50,000; invoice PAID; commission and message as today). Balance 100,000. |
| Next invoice raised | Apply runs; the balance pays it if it can. The customer's message says "paid from your balance". |
| Part payment 20,000 of 50,000 | Deposit 20,000 is in the ledger at once and applied: invoice PARTIALLY_PAID, 30,000 still owed, arrears chase 30,000. |
| Paid twice (receipt, then a late mobile-money push) | The second is another deposit; nothing is owed, so it stays as balance. |
| Loan owes 300,000; deposit 350,000 For: Loan | Loan takes 300,000 (interest first, then principal) and settles; 50,000 becomes balance. |
| Cheque bounces | Reverse the deposit (second person, or alone by permission). A `REVERSAL` entry; whatever it paid is undone newest first, an invoice reopens and arrears restart. If the money was already withdrawn, the balance goes below zero and reads "Owes". This is the only way a balance goes negative. |
| Policy ends with a balance (lapse, surrender, maturity, free-look) | A withdrawal is raised to the policyholder's number on file, hooked into `endBillingForTermination`; staff may change the payee before approving. |
| Policy ends by death | The withdrawal waits for a payee (D13); finance enters the claim's payee or the estate; a second person approves. |
| Customer asks for their balance back | Staff raise a withdrawal; same approval. |
| Credit life, borrower leaves | Unchanged (D7). |
| Savings-type policy, extra premium | The invoice is paid; the rest is a savings deposit (`TOP_UP` road). A closed account or matured term account takes none, and then no premium is due either. |

**Mobile-money requests** ask for what the policy owes now, and staff or the customer may change the amount to pay
ahead. The deposit is the amount the aggregator reports; where it reports none, the amount requested.

## 5. Names

### 5.1 Console (English)

| Thing | Screen | New code |
| --- | --- | --- |
| A policy's own money | **Balance** ("Balance 100,000.00 TZS", or "Owes 30,000.00 TZS") | `PolicyBalance` |
| Money in | **Deposit**; "Record deposit" | `DEPOSIT` |
| Premium paid from the balance | **Premium paid** | `PREMIUM_PAID` |
| Money in for a loan | **Loan repayment** | purpose `LOAN_REPAYMENT` |
| Money back to the customer | **Withdrawal**; "Withdraw" | `WITHDRAWAL` |
| Undoing a deposit | **Reversal**; "Reverse" | `REVERSAL` |
| Agent's cash | **Agent cash** (was Field receipts) | unchanged (`FieldReceipt`) |
| Loan money out | **Loan paid out** (was disbursed) | unchanged |
| Savings money in | **Savings deposit** (was contribution, top-up) | unchanged |
| Savings money out | **Savings withdrawal** (was account withdrawal, partial withdrawal) | unchanged |
| Money owed on an event (claim, maturity, surrender, annuity, vesting) | **Payout** (was settlement, disbursement) | unchanged |
| Agents' commission | **Commission payout** | unchanged |
| Free-look or cancellation money back | **Refund** | unchanged |
| Fixed Deposit product, "Bank deposit" method | unchanged | unchanged |
| Finance registers | **Deposits**, **Payouts** (was Payments register) | unchanged |

Existing code, tables and URLs keep their names (`/receipts`, `FieldReceipt`, `DisbursementInstruction`); only what
people read changes, and new code uses the new names. Enum literals shown to people go through the existing label
maps; no enum is renamed.

### 5.2 Customer portal

| English | Swahili |
| --- | --- |
| Balance | Salio |
| Deposit (statement line) | Malipo |
| Premium paid (from balance) | Ada imelipwa |
| Withdrawal | Pesa zilizotolewa |
| Reversal | Malipo yamebatilishwa |
| Paid from your balance | Imelipwa kutoka salio lako |
| Covers premiums to {date} | Inalipia ada hadi {date} |
| Request a withdrawal | Omba kutoa pesa |
| Statement (panel title) | Taarifa ya malipo |
| Pay (button) | Lipa ada (unchanged) |

"Amana" stays the Fixed Deposit product's word and is not used for a policy deposit.

## 6. Approvals

One person asks; a second approves; the `*-own` permission lets one person do both, recorded against their name
(`approvals::api OwnWork`). No new permission.

| Movement | Asks | Approves | Alone |
| --- | --- | --- | --- |
| Every deposit (premium, loan, savings, agent cash) | `collections.manage` | `collections.approve` | `collections.approve-own` |
| Reversal of a deposit | `collections.manage` | `collections.approve` | `collections.approve-own` |
| Policy balance withdrawal | `payouts.manage` | `payouts.approve` | `payouts.approve-own` |
| Loan paid out (**new check**) | `loans.manage` | `payouts.approve` | `payouts.approve-own` |
| Commission payout (**new check**) | `commission.pay` | `payouts.approve` | `payouts.approve-own` |
| Lender premium refund (**new check**) | automatic | `payouts.approve` | `payouts.approve-own` |
| Savings withdrawal, adjustment | `accounts.transact` | `accounts.approve` | `accounts.approve-own` (D14) |
| Claims, surrender, benefit payouts, free-look | unchanged | unchanged | unchanged |
| Marking a bank transfer sent | `payouts.manage`, a fact after approval | — | — |

Consequences to tell the user's admins: savings deposits made by hand move from `accounts.transact` to
`collections.manage`; loan repayments move from `loans.manage` to `collections.manage` (D11); the three new checks
put a second person on loan, commission and lender-refund payouts. Each new approval joins the existing approvals
inbox through an `ApprovalSource`, as payouts and free-look refunds do. The `permissions.json`/role defaults are
read before the build so no role silently loses a working path; any role that does is listed in the plan.

## 7. Screens

Built from the nearest existing pattern; every rule here is from DESIGN.md, PRODUCT.md or `frontend/PLAN.md`.

- **No arithmetic in the browser.** Balance, "covers premiums to", the running balance on each statement line and
  every total come from the server (PRODUCT.md, "render only what the platform can prove").
- **Not on the figures band.** The band keeps its three figures; the balance is a panel figure.
- **Acting on full pages.** Record deposit, Withdraw and Reverse are full pages (`FormLayout`, as
  `NewReceiptPage`), never a slide-over or the record rail.
- **`ConfirmAct`** with the real values on Withdraw and Reverse; the reversal names the invoices it reopens.
- **Six status buckets.** Deposit statuses reuse the receipt statuses already mapped.
- **One Primary button per screen.**

### 7.1 Policy page, Billing tab (D9)

Order, bounded before unbounded (`PLAN.md` §14.8):

1. **Balance** (new, short): Balance, "Covers premiums to", and the buttons Record deposit (Primary), Request
   mobile-money payment, Withdraw. Not `emphasis`: the policy page deliberately has none.
2. **Policy schedule**: as today.
3. **Invoices**: as today, without the inline `InvoiceReceiptForm`; a paid invoice says "paid from balance" with the
   date. Waive stays here.
4. **Payment schedule**: as today, per premium, with its PDF and Excel downloads.
5. **Statement** (new, long): date, what, amount, balance after, from a paged endpoint (`DataTable`, since it is
   genuinely paged). A deposit row links to its own page, where Reverse lives.

Savings-type policies keep their Account tab; they have no Balance panel (D12).

### 7.2 Record deposit (was Record a receipt)

`/staff/receipts/new`, kept as the URL. Fields: Policy, **For** (Premium, Loan, Savings), How the money came in,
reference, Amount, Received on, Paid by, Note. "Invoice it pays" goes. For: Loan shows the loan's balance and caps
at it; For: Savings shows the top-up rules that apply. Agent cash arrives pre-filled from the queue, as today.

### 7.3 Deposit page (was Receipt)

`/staff/receipts/:id`. As today (facts, confirm or refuse), plus **Reverse** on a confirmed deposit, with a reason,
going through the same approval.

### 7.4 Elsewhere

- **Loans tab:** the typed repayment form goes; "Record deposit" opens with For: Loan, and "Request mobile-money
  payment" sends a request for the loan.
- **Agent cash queue** (was Field receipts): renamed; behaviour unchanged.
- **Payouts list:** gains policy balance withdrawals and the "waiting for a payee" filter (the matured-deposits
  pattern).
- **Approvals inbox:** deposits, reversals, withdrawals, loan payouts, commission payouts, lender refunds.
- **Registers:** Deposits and Payouts.
- **Agent console:** reads the Balance and Statement; no buttons.
- **Customer portal:** policy page shows Balance and Statement (§5.2); the Pay page asks for what is owed now, with
  the amount editable; "Request a withdrawal" sends a request staff approve.
- **Wording across the app** per §5.1, on every screen that says receipt, payout, settlement, disbursement,
  contribution, top-up or withdrawal.

## 8. Ledger

Every account below already exists in `ifrs17-chart.csv`; only posting rules change.

| Event | GMM / VFA | PAA |
| --- | --- | --- |
| `billing.DepositReceived` (new) | Dr RECEIPT (by method) / Cr 2128 | same |
| `billing.PremiumPaidFromBalance` (new) | Dr 2128 / Cr 2122 `PRM_REN`; levy Dr 5220 / Cr 2650 | Dr 2128 / Cr 2142 `PRM_REN`; levy same |
| `billing.PremiumCollected` | **no lines** (it stays the business event) | **no lines** |
| `billing.BalanceWithdrawalApproved` (new) | Dr 2128 / Cr 2430 | same |
| `billing.BalanceWithdrawalPaid` (new) | Dr 2430 / Cr BANK | same |
| `billing.DepositReversed` (new) | Dr 2128 / Cr RECEIPT | same |
| `billing.PremiumPaidReversed` (new) | Dr 2122 / Cr 2128; levy reversed | Dr 2142 / Cr 2128; levy reversed |
| `policyloan.LoanRepaid` | Dr **RECEIPT (by method)** / Cr 2126, 2125 (was always 1140) | same |
| `billing.InvoiceWaived` | amount = charged − paid − **credited** (was charged − paid) | same |
| IFRS 9 rules (`G-*`) | unchanged: savings-type money keeps its own road | |

The levy is booked on premium paid, not on the deposit. RECEIPT resolves by method exactly as today
(`PostingRuleSet.receiptAccount`: 1120 or 1140). Cash still posts to 1120 (no "banked" step).

**Open check before switch-on (auditor or actuary):** whether premiums received in advance (2128, under 2120 in the
chart) sit inside the insurance contract liability for IFRS 17 presentation. The engine's policy accounts
(`EngineResults.POLICY_ACCOUNTS`) do not include 2122 or 2128 today, so the extracts do not change; the question is
presentation, not mechanics.

## 9. Edge rules

| Case | Rule |
| --- | --- |
| Same confirmation delivered twice | One entry, keyed on the payment transaction id |
| Two deposits at once | Row lock on `policy_balance` |
| Currency | The policy's currency; another is refused |
| Lapse | Arrears are judged after the balance has paid what it can |
| Commission, thank-you message, paid-to-date | Unchanged: on the invoice becoming PAID |
| Unit-linked For: Savings | Today's `TopUps` refusals apply (product takes none, below minimum, not in force) |
| Loan For: Loan above the balance | Capped by the loan; surplus to the policy balance |
| Withdrawal above the balance | Refused |

## 10. Past data (once, before switch-on)

1. Open a balance for every insurance policy, writing its history from existing payments as entries that post
   nothing.
2. A dry-run correction report with totals: (a) part payments never posted (F1); (b) credited invoices posted high
   (F2); (c) money confirmed after settlement (F3), which becomes balance; (d) loan repayments booked without
   confirmed money (F4).
3. Finance reviews (a) to (c) and posts one correcting journal through the manual-journal approval (IFRS 17 I4).
   (d) goes to finance as a list to check against statements; it cannot be fixed automatically.
4. Switch on after that journal is approved.

## 11. What else is affected

| Area | Effect |
| --- | --- |
| `payment.Receipts`, `ReceiptView` | Source becomes a policy plus purpose; `ReceiptView` stops parsing `sourceRef` as an invoice UUID; migration rewrites existing rows |
| `billing.BillingApiImpl` | Allocator, deposits, withdrawals, reversals, policy-end withdrawal, waiver amount |
| `billing.PaymentEventListener` | Records deposits instead of applying to an invoice |
| `policyloan` | Listens for `LOAN_REPAYMENT`; `recordRepayment` endpoint removed from the customer role and from direct booking |
| `accumulation`, `unitlinked` | Extra premium on savings-type becomes a top-up; manual top-ups are deposits |
| `distribution` | Commission payout gains an approval |
| 13 `PremiumCollected` listeners | 12 unchanged; finaccounting's cash lines move (§8) |
| `reporting.PremiumCollectionsReport`, finance dashboard | Still premiums paid; labels per §5 |
| OpenAPI specs, generated types | Regenerated; `default:` fields checked (openapi-typescript makes them required) |
| `backend/docs` 04 API contracts, 05 event catalogue | Updated |
| Dev database | New migration applied by hand and the dev backend restarted |

## 12. Tests and demo data

- **Allocator unit tests:** oldest first, part, surplus, reversal newest first.
- **The 17 scenarios** of the review as integration tests, each asserting invoice, balance and ledger together.
- **A books-match check**, run after every money test and after seeding: 1120/1140 equal confirmed deposits less
  withdrawals by method; 2122/2142 equal what invoices still owe; 2128 equals the sum of policy balances; the trial
  balance balances; no open unposted events.
- **E2E:** new journeys (deposit above owed, next premium from balance, withdraw, reverse, loan deposit, agent cash,
  portal balance and Pay); every spec with a renamed control updated in the same change: `staff-receipts`,
  `staff-field-receipts`, `staff-policy-loans`, `staff-credit-life-scheme`, `staff-billing`, `staff-arrears`,
  `staff-approvals`, the helpers `payments.ts` and `creditLife.ts`, and the savings, payout and commission specs.
  Each spec creates its own policies.
- **Demo data:** `seed-dev-data.sh`, the Bumaco, Elimu, Hazina, Malengo and Ndoto demos, `dev-field-receipt-fixtures.sh`
  and `dev-arrears-fixtures.sql` (rewritten as an API script) put money in and out only through deposits and
  payouts, include one policy per case (paid, paid ahead, part paid, withdrawn, reversed, loan with surplus), and
  end by running the books-match check, failing loudly.
- Full vitest and the full backend suite and e2e at the end, before the merge.

## 13. Order of work

| Step | What | Closes |
| --- | --- | --- |
| 1 | Loan repayments through deposits; cap; RECEIPT by method; customer role off the direct endpoint | F4 |
| 2 | Policy balance, allocator, deposits, ledger rules, levy on premium paid, waiver amount | F1, F2, F3, overpayment |
| 3 | Withdrawals, reversals, policy-end withdrawal, waiting-for-a-payee | refunds, bounced cheques |
| 4 | New approvals: loan, commission, lender refund payouts | the three unchecked payouts |
| 5 | Screens: Balance, Statement, Record deposit, Deposit, Loans tab, portal | |
| 6 | Wording across the app, with every affected e2e spec | |
| 7 | Demo data on real roads plus the books-match check | |
| 8 | Past-data report and correcting journal; switch on | |

## 14. Not in this work

- A bank or wallet statement feed, an unidentified-money list, and automatic matching (D4).
- A "banked" step for branch cash.
- One balance across a customer's policies (D1).
- Copying a claim's payee onto the death withdrawal automatically (D13, can follow).
- Renaming code, tables or URLs.
