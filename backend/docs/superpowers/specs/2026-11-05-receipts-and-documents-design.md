# Receipts and policyholder documents — design

2026-11-05. Asked by the user after a check of the platform's documents against the TIRA Insurance Regulations 2009 and
usual life-insurance practice. Follows DESIGN.md and the platform's conventions; on screen, the fewest words that do
the job.

## Why

The rules, quoted from the Regulations (TIRA's own PDF):

- **Reg. 35(a)–(b):** a policy is invalid from inception if the full premium is not paid within seven days, and "all
  insurance policies must disclose this requirement in bold print on each cover note and each policy".
- **Reg. 35(d):** every policy or cover note "shall show the full premium charged on the face of the document".
- **Code B.5(b):** an intermediary shall "acknowledge receipt … of all monies received in connection with an insurance
  policy and shall distinguish the premium from any other payment included in the money".
- **Code C.3–C.4:** the policyholder is given a copy of the completed proposal form, and an insurer "shall not raise an
  issue under the proposal form, unless the policyholder is provided with a copy of the completed form".
- **Code C.7:** a life insurer "shall be obliged to give written information to the assured as to whether or not there
  are rights to surrender values in the contract".
- **Code D.4:** "Standard documents required by an insurer to authenticate a claim shall be listed and given to the
  insured at the inception of a policy".

What the platform does today, and the gap:

| Today | Gap |
|---|---|
| A "Premium receipt" exists only per premium **paid** (billing `premium_receipt`, written when the balance pays an invoice) | Money **received** gets no receipt: a part payment or money paid ahead has none until a premium falls due; agent cash has none at all |
| The only money message to a customer is "premium paid in full" | Nothing on money received, withdrawn, refunded or paid out |
| Receipt "number" is the payment reference or an internal id | No sequential receipt number |
| Policy schedule shows the full premium (35d ✓) | No Reg. 35 bold statement, no C.7 surrender statement, no D.4 claim documents list |
| — | No copy of the completed proposal; no advice for money paid out; no claim settlement letter |

## The idea in one line

**Every amount of money received gets one numbered receipt, and every receipt can be found from anywhere it touched.**

## Step 1 — a receipt for every amount received

**What is a receipt.** One per confirmed incoming payment (`payment.payment_transaction`, status CONFIRMED), whatever
its purpose: premium by mobile money, a deposit by cash/bank/cheque/Lipa/TANQR, agent cash once matched, a savings or
unit-linked top-up, a loan repayment. Payment is the one module all money in passes through, so the receipt belongs
there — not on billing's balance, which savings-type policies never use.

**Its number.** `RC-2026-000123`: prefix, the year it was confirmed, a six-digit sequence per tenant per year, given in
the same transaction that confirms the payment (a per-tenant-per-year counter row, locked). Never reused, never
renumbered; a reversed deposit keeps its number and shows as reversed. One number shown everywhere.

**What it says (B.5(b)).** Received from, how (method and reference), when, the amount — then **what the money was for**,
split: each premium it paid with the cover period, any amount held on the policy's balance for the next premiums, a
savings deposit, a loan repayment. The receipt PDF is the document; the customer portal lists them.

**The customer is told.** An SMS when the receipt is issued: amount, policy, receipt number. Agent cash is acknowledged
at capture with an SMS naming the agent and the amount ("receipt follows when it reaches us"), so the customer holds
something from the moment the cash leaves their hand.

**Staff trace it (the user's ask: simple to find and follow).**

- **Go to… (Ctrl K)** finds a receipt by its number; the Deposits register searches by it and shows it as a column.
- **One receipt page** for every incoming payment — the page recorded deposits have today, extended to all. It reads
  top to bottom as the money's trail: received → recorded by → confirmed by → what it paid (each premium linked) →
  held on balance → reversed, if it was. Download PDF from the page.
- **Everything links back to it:** the policy Statement's deposit line, the invoice a premium paid, the approvals row,
  the payments register row, the matched agent-cash row.

Out of scope: TRA fiscal receipts (premiums appear VAT-exempt; to be confirmed by the tax adviser, not built here).

## Step 2 — the policy schedule says what the Regulations require

Added to the schedule PDF (and the portal's policy page), in these words unless compliance gives others:

- **Reg. 35 (bold):** "The full premium must be paid within 7 days of the policy start date. If it is not, this policy
  is invalid from its start date."
- **C.7, from the product:** a product with surrender values: "This policy has a surrender value once it has run long
  enough; ask us for a quotation." Otherwise: "This policy has no surrender value."
- **D.4:** "To claim, send us:" and the list for each claim type the product covers — **content from the insurer**
  (there is no standard list in the platform today; only hint text). Kept as reference data per claim type, edited by
  staff, printed on the schedule.

## Step 3 — a copy of the completed proposal

A PDF of the underwriting case as the applicant answered it — who, cover asked for, disclosures and answers,
beneficiaries, date and who took it — downloadable from the case and the policy, and in the portal. It carries the C.1
line: "A specimen policy and its terms are available on request."

## Step 4 — money paid out is advised too

A numbered **payment advice** (`PA-2026-000045`) for every completed payout — balance withdrawal, refund, claim
settlement, maturity, surrender, loan paid out — with an SMS. For a claim the advice is the **settlement letter**:
the claim, what was decided, what is paid and to whom. Same trace rules as receipts.

## Order and checks

Steps 1→4, one branch each, merged in turn. Each step: unit tests and the affected classes as it goes; at its end the
full vitest and the e2e specs it touches; the full backend suite before the last merge.

## Open for the user

1. The receipt number format `RC-2026-000123` (and `PA-` for payouts).
2. The D.4 claim documents list per claim type — from claims or compliance.
3. The Reg. 35 and C.7 wording above — as written, or compliance's own.
