# Finance walkthrough — problems found (2026-11-10 → 2026-11-11)

Recorded while walking the finance workflow with the user, as finance officer Rehema (`staff.finance`), page by page in
sidebar order. Nothing here is fixed unless listed under "Already fixed".

**How to read this document**

- Sections follow the staff sidebar: Collections → Paying out → Ledger → Valuation & close → Reinsurance & returns →
  Distribution. **Reports has not been walked yet** (the user will do it later).
- Each page section starts with a short "What it is for" where one was written during the walkthrough, then its problems.
- Item numbers are **stable IDs** in the order they were found, so cross-references ("see 61", "D14") keep working;
  within a section they are sorted. Numbers 93 (merged into 61) and 95 (withdrawn — it was wrong) are not used.
- Decisions the user owes are D1–D17 (one table, below). Test data seen on the dev system is collected at the end, not
  mixed into the problems.

## Status at a glance

| Area | Items | Decisions |
|---|---|---|
| Collections (Deposits, Arrears, Agent cash, Agent console) | 1–24 | D3, D4, D5, D6 |
| Paying out | 28–43 | D2, D7 |
| Ledger | 46–121 (less 93, 95) | D9, D10, D11, D12 |
| Valuation & close | 122–164 | D13, D14, D15 |
| Reinsurance & returns | 165–186 | D16 |
| Distribution | 44, 187–195 | D8 |
| Across the console | 25–27, 45 | D1 |

## Fix first — money, figures or controls at risk

These are the items where real money can move wrongly, published figures are wrong, or a control is missing. The rest are
readability and convenience.

| Item | Why it comes first |
|---|---|
| 187 / D8 | A 5% commission stored as 500%: Juma Senior shows TZS 20M due and **Request payout** is offered. |
| 36, 37 | A pension lump sum was paid to the payee "nothing", reviewed and approved by one person 4 seconds apart. |
| 146 | One person can redirect a maturity payout to any number, at once, with no SMS to the client. |
| 148, 158 / D14 | Months and the year lock with no IFRS 17 engine run: GMM revenue is never recognised, so 2026 shows a false loss. |
| 90 | Start closing is offered on the current and future months; it then refuses every automatic posting. |
| 89 | Postings take the system date, not the business date: month-end will not match the bank statement. |
| 55, 56 | Bank accounts below zero and no bank reconciliation. |
| 57 | An account in use can be retired; the next posting falls into Unposted events and blocks month-end. |
| 74, 116 | One person can dismiss an unposted event, removing an amount from the books for good, with no warning. |
| 122, 128 / D13 | Real unit-linked funds unpriced since 22 Oct (premiums bought no units); the management charge is never deducted. |
| 166, 165 | Reinsurance cedes the savings part of unit-linked and endowment premiums; the ceding rules are an unapproved placeholder. |
| 179, 182 / D16 | The only regulatory return is an invented placeholder and its figures do not agree with the ledger. |
| 99, 66 | The accounting-policy baseline is "approved" by the system; no election has been signed off. |
| 101 / D11 | Commission withholding and premium levy are silently absent on a new company. |
| 126, 170, 190 | A fund (and its charge), a treaty and a commission plan each go live on one person's say. |

## Already fixed during the walkthrough

- Closed savings account facts wrapped mid-number; reason shown as a code ("DEATH") — merged 68cbbcfd.
- Header offered Deposit / Change beneficiaries / Withdraw / Loan on ended policies — merged 68cbbcfd.
- Paying out names (Payouts → Benefits due, All payments out, Maturities moved) — merged c9678f53.
- 41 and 42 (withholding rules: an ended rule read Approved; the form offered annuity income only) — merged 0dc89eea
  (branch withholding-kinds).

## Decisions needed from the user

| # | Problem | Decision needed | Items |
|---|---|---|---|
| D1 | A policy whose life died and whose death claim was paid ends as **Surrendered** (no such end state exists). Reports count deaths as surrenders. | Name of the new end state ("Claim paid" suggested). | 27 |
| D2 | Dev `staff.finance` holds six `*.approve-own` permissions (granted 2026-11-08), so the payout two-person e2e fails. | Keep them (change the test) or remove them. | 25, 37, 151 |
| D3 | An unpaid offer becomes Not taken up after 30 days even when its first premium is recorded but not yet confirmed (agent cash, or a deposit awaiting its second signature). | Proposed: the expiry sweep skips offers with money in transit. | |
| D4 | No cover while an agent holds the client's first premium. | Should cover date back to when the agent collected the cash, once confirmed? (business/underwriting) | |
| D5 | Kindelema's two CLive Savings policies (POL-5705F7FB paid, POL-8154F1A5 offer) were set up as 2,000,000 **monthly** for 13 months via the agent forms. | Was a single opening deposit intended? If so, cancel and reissue. | 17, 18 |
| D6 | Dunning ladder days (7/14/21/30) and the 30-day offer validity are placeholders pending Collections / Underwriting sign-off. | Confirm the figures. | 8 |
| D7 | Which payouts Tanzanian law requires tax withheld from (annuity income only, or also pension lump sums, maturities, ...). | Tax/compliance answer; decides what the withholding form offers. | 42 |
| D8 | CLive Savings commission plan rate is 5.0000 = **500%**: Juma Senior was credited TZS 10,000,000 first-year commission on each of Kindelema's 2,000,000 savings policies (TZS 20M total, 2026-11-10). Savings products pay commission on the whole deposit. | Correct the plan rate (0.05?) and reverse/re-state the two accruals. Do savings/deposit products pay agents commission at all — and if so at what rate and on what base (deposit, charges, risk premium)? (D17 merged here.) | 44, 187, 188 |
| D9 | Commission withholding rate set to **18%** on 2026-11-10 (sign-off "MArtin"). | Confirm the legal rate (tax). | 68 |
| D10 | Posting rules are a code file (read-only to finance): every missing rule needs a developer and a release before the month can lock. | Keep developer-only, or let finance propose rule changes for a second person / actuary to approve? Recommended (2026-11-10): **A — keep read-only**, make the page readable (104–110) and add "Report a missing rule" from Unposted events; revisit an editable, two-person, test-before-approve editor when a second company with a different chart uses the platform. | 70, 104–110 |
| D11 | Premium levy is not configured — nothing booked. | Does a levy apply to life premiums, and at what rate? | 71, 101 |
| D12 | Credit life is measured GMM per policy, PAA for a lender's scheme (baseline, unsigned). | Does the actuary confirm GMM for single credit-life policies? | 97 |
| D13 | Unit-linked prices are typed by hand; the real funds have had no price since 22 Oct. | **Decided 2026-11-10: Option A first** — the fund manager supplies the NAV (import the daily file: NAV/unit, units, sale and repurchase price → the existing propose/approve step); Option B (NAV calculated here from holdings) later, only for funds the company runs itself; per-fund setting "supplied by the manager" / "calculated here". Still open: which manager(s) run the funds, and is their published NAV already net of their management charge? | 122–130 |
| D14 | A month locks without any IFRS 17 engine run (the lock checks a run only if one was posted). | Should the lock require a posted engine run for a month with GMM/VFA business? Who runs the engine (outside actuary?) and how often (monthly, quarterly)? | 148, 156, 158 |
| D15 | The year closes with no corporate income tax charge. | Is tax booked by manual journal before the close, or should the year-end close remind about (or require) a tax entry? | 164 |
| D16 | The only regulatory return is an invented placeholder. | Which TIRA returns must be filed (quarterly/annual life returns, forms, line definitions), and can the user supply TIRA's templates? | 179–186 |
| D17 | — merged into D8. | | |

## Across the console

These show up on many pages; one shared fix each.

- **Only the first column of a row opens it** — 45 (also seen as 32 and 140).
- **"in this tenant" jargon** — 3, 35, 40.
- **No client name where money or a person is concerned** — 2, 6, 10, 28, 38, 133.
- **Raw ids and developer words on screen** — 21, 43, 48, 53, 72, 78, 83, 105, 111, 119, 157, 161, 193.
- **One person can do what moves money or changes the books** — 37, 74, 126, 146, 170, 190.
- **Nothing reminds anyone** (no bell item, no age, no due date) — 46, 92, 115, 122, 137, 156, 186, 195.

45. **Only the first column of a row opens it** (DataTable makes column 1 a button; clicking elsewhere on the row only highlights it). Same on ~40 lists; one shared fix — make the whole row activate. (Explains 32.)
25. Payouts e2e "reviewer is refused" fails on dev data (D2).
26. Payment refusals (4xx) are not logged, so a reference like c0f5b604… cannot be looked up.
27. Policy page: "Surrendered" beside "Closed — after a death claim" (D1).

---

## Collections

### Deposits
1. **"From" accepts anything**: a mobile-money deposit recorded "500" and "2000000" (an amount) as the phone; cash recorded "me". Mobile money must require a phone number; cash must name who handed it over.
2. **No client name** on the register — policy number only.
3. **"in this tenant"** jargon in the header (also on ~15 other registers).
4. **Badge 0 while deposits wait** for a second person: the badge counts only stuck/failed ones; nothing links the waiting ones to Approvals.

### Arrears
5. **Badge 0 while 4 cases are open**: it counts only level 5 (about to lapse).
6. **No client name or phone** — collections exists to call people.
7. **"Dunning" and "Chased to"** show the same thing, in jargon.
8. **No "days overdue"**: the ladder counts from when the case opened, not the due date, and nothing says how late a premium is.
9. **No action on the row** (send a payment request, record a promise to pay, note a call).

### Agent cash (staff page and Approvals)
10. Approvals names the **client**, Agent cash names only the **agent** — show both on both.
11. One action, two names: **Match** (Approvals) vs **Record as received** (Agent cash).
12. Jargon: "past the reconciliation SLA", "Pending reconciliation".
13. Cramped table: policy numbers and dates break over 2–3 lines.
14. **No total per agent** ("Juma Senior holds TZS 545,000, oldest 10 days").

### Agent console and consistency with staff
15. **Billing tab: Payment schedule and Payments made say "Access Denied"** for agents (document endpoints exclude agents). Fix is on the parked branch `agent-cash-wip`.
16. **Old Invoices list** shows every month a year ahead as "Due"; the Payment schedule already lists the same months as Due / Upcoming / Paid. Replace Invoices with the schedule in both consoles; move Deposit onto it.
17. **Agent quotation calculator and agent proposal form** never got the 2026-10-29 savings fix: still "Sum assured or amount" / "Premium frequency", and require Sex for a savings account. Typing 2,000,000 with Monthly bills 2,000,000 a month (D5).
18. Before submitting a regular savings case, say in words what will be billed ("2,000,000 every month for 13 months = 26,000,000").
19. "Not yet on cover — first **premium**" on a savings policy; the policy schedule description says "its premium".
20. An offer shows **Loans** and **Payouts** tabs to the agent but not to staff.
21. **Agent of record** (and policyholder for agent-registered-elsewhere clients) shown as a raw id to agents. Fix is on `agent-cash-wip`.

### Agent cash — the agent side (parked on branch `agent-cash-wip`, d8d05c0c)
22. **No agent screen to record cash** (backend and SMS exist; dev data came from `scripts/dev-field-receipt-fixtures.sh`). Agreed: online only.
23. Agent list of cash still held, with **"I've banked this"** + slip reference (backend on the branch, untested).
24. Note, not a problem: "Ask to pay" (mobile money) for agents **already exists** — the agent Billing tab's Deposit link. Not to rebuild.

---

## Paying out

### Benefits due
28. **No client name** on the list or the payout page: the payee is typed without the screen saying whose money it is.
29. **Payee reference is free text** — no method choice (mobile money / bank), nothing pre-filled from the client's number on record. Same weakness as Deposits "From", on money going out.
30. **Opens on All** (118 rows, mostly months away); should open on what needs action (Due, Reviewed, Failed, In doubt).
31. **A late payout looks like one due today** (pension lump sum 5 days late, no warning).
32. **Clicking the row did not open it**; only clicking the policy number did (see 45).

### Payment runs
33. **Proof of life can only be recorded after a stream is suspended** (the form is on a held instalment only). No list of pensioners whose proof is due soon, no way to record it ahead, so every pensioner is suspended first each year and paid late.
34. **No reminder to the pensioner** to confirm they are alive before the due date.
35. "in this tenant" on Payment runs too (see 3).

### Payments to make, All payments out
36. **Money paid to "nothing"**: the POL-A8BFAD09 pension lump sum (TZS 54,955.90) was reviewed with payee "nothing" and shows Completed by mobile money (2026-11-10 12:33). Validate payee numbers; default to the client's number on record.
37. **One person reviewed and approved it 4 seconds apart** (staff.finance, payouts.approve-own). Ties to D2: drop the permission, or make acting alone show the payee and ask for a deliberate confirmation.
38. **"To" shows the typed text** ("nothing", "MArtin") and never the client's name.
39. **No proof of life asked on a pension lump sum** (survival benefits ask; the lump sum went out with none recorded).
40. "in this tenant" on All payments out (see 3).

### Withholding rules
41. ~~FIXED (withholding-kinds)~~ **An ended rule still reads Approved** and still offers Last day / End rule (the 5–9 Nov e2e rule); should read "Ended 9 Nov 2026" with no form.
42. ~~FIXED (withholding-kinds)~~ **The form offered only Annuity income**; the backend accepts every payout kind and the list already labels them.
43. **Proposed / approved by shown as raw ids.**

---

## Ledger

### GL postings
50. **Account names truncated with …** in GL postings Debit/Credit columns.
51. **GL entry detail links nowhere**: the policy number is plain text and Source ref is a raw id — no jump to the deposit / receipt / claim / payout that caused it, and no person shown.
52. **"What" too vague**: a deposit and its fee both read "Savings account movement"; say "Savings deposit", "Account fee", "Interest".
53. **Developer words on the entry**: "accumulation.PostingRecorded", "Policy register version 44", unexplained tags (SAV-IFRS9-2026-NSR · IFRS9 · SAV · DIRECT · DSM).
54. **Only two filters (period, policy)**: no filter by account, by kind or by date range, and no totals.

### Chart of accounts
55. **Bank accounts below zero**: 1130 Claims and benefits payments bank −3,895,203.64, 1110 −1,600 — payouts booked out of banks no money was ever booked into (premiums land in 1140 mobile money); no way to record a transfer between the company's own accounts except a manual journal.
56. **No bank reconciliation**: nothing compares account balances to bank / mobile-money statements.
57. **Retire has no safety check**: an account holding money, or one the posting rules book to (e.g. 1140), can be retired; the next automatic posting then fails into Unposted events and blocks month-end. (Delete is guarded.)
58. **New account help is wrong**: says first digit 1–5 sets the type; the chart uses 6, 7, 8 (income/expense) and 9 (clearing).
59. **New account does not ask the posting mode** (automatic / manual / both) though the list filters by it.
60. **Account names truncated** in the tree (as 50).

### Accounting periods
49. **A manual journal was posted into period 1997-12** (e2e data): posting to a period before the books begin should be refused.
61. **The lock checks are invisible until Lock is pressed** (earlier months locked, no unposted events, clearing accounts at zero, IFRS 17 runs done, year-end for December): show them as a checklist per month. (93 merged here.)
62. **Test periods 1997-12, 1998-01 (open), 1999-11, 2000-11 exist**; no "books begin on" date refuses periods before the company existed (see 49).
63. **Every month without a stored row reads Open**: "open and in use" is indistinguishable from "never used".
64. **Closing in order is laborious**: locking 2026-10 needs 2026-05…09 each closed and locked first; offer "close the earlier months" or list the blockers.
89. **Postings take the system date, not the business date**: an automatic posting's month is the day the platform recorded the event (Africa/Dar_es_Salaam), and a retried unposted event posts in the month of the retry. Money received 31 Oct but confirmed 1 Nov lands in November; month-end figures will not match that month's bank statement. Should use the value / payment / settlement date where the event has one.
90. **Start closing is offered on the current month and future months**: no date check (AccountingPeriod.startClosing). Closing November on 10 Nov refuses every automatic posting for the rest of the month into Unposted events, and retrying cannot help (it posts in today's month, still closing). Allow closing only after the month has ended.
91. **The Start-closing confirmation is wrong about manual journals**: it says a closing month takes "no event or manual journal", but ManualJournalRules accepts a closing month (month-end adjustments depend on it).
92. **Nothing prompts the close**: no close date, reminder or dashboard nudge; months stay open until someone remembers (May–Sep 2026 still open). (Lock refusing with only the first blocker — see 61.)

### Accounting policies
65. **All codes, no words**: GMM/PAA/VFA, portfolio codes (DANN, DEP, END, FUN, GRPL, IANN, MB, PAR, PEN, SAV, TERM, ULIP, WL, CRL), values (SPREAD, EXPENSE_WHEN_INCURRED).
66. **No election is signed off**: every baseline reads "pending actuary and auditor sign-off".
67. **Test election approved for 2033**: "OCI option ON from 2033-11-27", memo "Test memo 1793854686973" — will change accounting when it arrives.
68. **Sign-off memo accepts anything** (18% commission withholding signed off "MArtin"); should cite its basis.
69. **Pension interest at vesting booked after re-grouping**: POL-A8BFAD09 was reclassified PEN-IFRS9 → IANN-GMM at vesting; the interest-to-vesting posting then classified as GMM, which has no INTEREST rule → Unposted. Book the final accumulation interest under the pension group before the switch.
94. **The register is unreadable without IFRS 17 training**: Scope and Value are bare codes ("CRL GMM", "DANN IFRS9"); show "Credit life — full method (GMM)" with a one-line meaning, the code small for the actuary. "Version" is the row's position in the register (CRL 2nd, DEP 11th, DANN 13th seeded), meaningless to the reader — drop it or make it a History link.
96. **The Sign-off column shows a developer document reference** ("Baseline per IFRS 17 spec 2026-10-05 §3 -- pending …") as though it were a sign-off; it should read "Not signed off" until a real actuary / auditor reference exists (see 66).
97. **Credit life on the full method (GMM), a lender's scheme on PAA** — deliberate in the baseline, but decides how all credit-life profit is reported; needs the actuary's confirmation (D12).
98. **Elections start 1 Jan 2020**, before the books exist (see 62, 73).
99. **The baseline is approved by "the system", by no person** (proposer system:baseline-proposer, approver system:baseline): 44 elections written APPROVED the first time anything touches accounting (opening the page, first issue, first posting). Bypasses the two-person rule; needs a setup step where finance and the actuary review, accept and sign off the defaults before the first policy is issued.
100. **Baseline effective date is fixed in code (2020-01-01)**, not the company's books-begin date.
101. **The two tax rates are silently absent on a new company**: commission withholding and premium levy are not seeded, so commission is paid with no WHT and no levy booked, with no warning. A setup checklist should flag each "not set".
102. **The rationale notes are not prominent**: "confirm with the actuary and auditors" on savings, deposit, pension, deferred annuity and policy loans is the most important text on the page.
103. **The defaults live in program code (PolicyRegisterBaseline)**: a company with a different product mix cannot start from its own set — each row needs a new two-person election.

### Posting rules
70. **Finance cannot add or fix a rule** (D10): a missing rule (e.g. 69) waits on a developer release.
71. **Premium levy not configured** (D11).
72. **Developer language**: event names (billing.PremiumInvoiceGenerated), movement codes (PRM_REN), "LRC (PAA)", no plain description per rule.
73. **Rules dated "from Jan 1, 2000"** (see 62).
104. **Grouped by technical event name**: 55 sections titled "billing.PremiumInvoiceGenerated", "benefitpayout.PayoutRequested"…; group by Premiums / Claims / Payouts / Commission / Loans / Savings / Reinsurance and name each event in plain words ("A premium falls due").
105. **Amount column shows internal field names** ("amount", "gross", "withheld", "investmentComponent", "difference", "delta"); say what each amount is.
106. **"RECEIPT" / "BANK" shown in place of an account** with no explanation that the payment method picks it (1140 mobile money, 1120 premium bank, 1130 claims bank).
107. **No link between a rule and its journals**: cannot list what A-06 posted this month, nor jump from a GL entry to its rule.
108. **Gaps found only after money lands in Unposted events**: nothing lists event × measurement model combinations with no rule (e.g. 69); the page should flag them.
109. **"Posts nothing" rules unexplained** (G-BILLED etc.): why a savings invoice books nothing is not said.
110. **The page does not say what it is for or how to read it**: needs a plain opening — "Every automatic entry comes from these rules; you can read them, not change them; a missing rule shows up in Unposted events" — and a key for a rule (when it applies, which method, which account, which amount). The UI and wording must be clear to a finance officer without IFRS 17 or developer knowledge.

### Unposted events
46. **Unposted events has no menu count**, yet one waiting event blocks the month-end lock.
47. (root cause in 69) **No posting rule for pension interest** (accumulation.PostingRecorded, model GMM, flow INTEREST): TZS 23.62 on POL-A8BFAD09 waiting since 5 Nov; every pension's monthly interest will land here.
48. **Unposted events speaks developer** ("No rule in posting-rules v6 posts accumulation.PostingRecorded for model GMM with {flow=INTEREST}").
74. **One person can dismiss alone** (ledger.manage): dismissing removes an amount from the books for good; it should need a second person like a manual journal.
75. **History is thin** (corrected 2026-11-10: a "Resolved" section does show Posted / Dismissed, by whom, when, why): it is hidden until something resolves and grows forever, with no date filter or search.
76. **No guidance on Retry vs Dismiss** per reason (UNMAPPED / REFUSED / ERROR); real money can be dismissed just to unblock a month.
111. **Developer language on every row**: event name ("accumulation.PostingRecorded"), raw source id, amount keys ("interest", "investmentComponent"), technical detail ("No rule in v6 posts … for model GMM"). Say instead: "Interest on POL-A8BFAD09 (TZS 12,345) could not be booked: there is no accounting rule for interest on an annuity."
112. **Retry posts in today's month but the row shows the original month**, with no warning that a September event retried in November lands in November (see 89).
113. **No link to the policy or the source** (receipt, claim, payout) to check the facts before deciding.
114. **No "what to do" per reason**: no rule → report a missing rule, don't dismiss; month closing → wait or post when it reopens; account retired → reactivate, then retry.
115. **No owner, no alert, no age**: a new unposted event is not in the action bell and nothing flags one waiting 30 days; they sit until month-end blocks.
116. **Dismiss is not confirmed**: a reason box and a button, with no "this amount will never be booked" warning.

### Manual journals
77. **Account typed as a bare code**: no search by name, no account name shown before saving; a typo posts to the wrong account.
78. **Jargon**: "manual (MAN) or mixed (BOTH) accounts", "Reason code required when a line is on a mixed (BOTH) account".
79. **Mixed date formats**: "Reverse automatically on" is mm/dd/yyyy; the rest of the console is dd/mm/yyyy.
80. **Period typed by hand** (YYYY-MM) instead of chosen from open months; a locked or 1997 period is found only on submit.
117. **A template on a mixed (BOTH) account does not set or ask for a reason code**: M-13 Statutory deposit always credits 1110 (BOTH), yet the need is found only on Submit (reference 2672924b…, draft 122ca0a5).
118. **The reason codes do not fit everyday transactions**: the list is Correction, Reclassification, Accrual, Write-off, Reconciliation adjustment, Migration, Other. A statutory deposit, payroll, rent, dividends, T-bills, coupons, reinsurance receipts all go through 1110 (BOTH), so nearly every manual journal needs a code — and the user picked "Reclassification" for a bank payment, wrong for the audit trail. Add codes such as Bank payment / receipt (non-insurance), Investment, Capital / dividends, Operating expense — or do not require a code for a bank account.
119. **The message is developer language and names no line**: "A line on a BOTH account (mostly automatic) needs a reason code" → "Line 2 — 1110 Main operating bank account — is also posted by the system. Choose a reason code (why you are posting to it by hand)." Highlight the field when the line is entered, not on Submit.
120. **The refusal reference cannot be looked up** (refusals are not logged — see 26); the cause was found by reading the draft in the database.

### Journal templates
The page is view-only: the user "can't do anything" on it — no create, use, edit or delete.

81. **Finance cannot save their own templates**: the backend stores one (POST /finance/journal-templates) and the page has a "Saved templates" section, but no button creates one; saved ones cannot be edited or deleted. A monthly payroll journal is rebuilt each month.
82. **Three transactions cannot be booked at all**: Q-07 withholding tax paid to TRA, Q-08 unrecoverable clawback written off, O-10 old unallocated receipts to unclaimed monies — marked "Posted by the system: A later build …", but that build does not exist and their accounts are AUTO, so finance cannot post them by hand either.
83. **Developer wording**: "A later build", "built after IFRS 17 I4", "AUTO account", "the guide's manual entries".
84. **The guide's worked-example figures read like instructions**: "Face TZS 100,000,000; cost TZS 92,000,000", "issued at TZS 1,500", "WHT 10% illustrative".
85. **No "Use this template" on the row**: the user must go to New manual journal and find it again in a long drop-down.
86. **One long table, no group headings, no search**: M / N / R / Q / O never named (Equity, Investments, Reinsurance, Intermediaries, Operating); codes like M-01 mean nothing to finance.
87. **One-off and unusable entries mixed with everyday ones**: O-11 go-live opening balances and ~8 "Posted by the system" rows.
88. **Templates are written into program code** (GuideTemplates.java): a change of account (e.g. payroll moves) needs a developer.
121. **Templates do not carry the reason code their bank line needs**: M-13, M-04, M-06, N-05, N-07, N-11, R-02 and others use 1110 (BOTH), so each fails on Submit until a code is picked (see 117–118); the template should bring a fitting reason code.

---

## Valuation & close

### Funds (unit-linked)
122. **The real funds have not been priced for almost 3 weeks**: Hazina Equity / Balanced / Money Market last priced 22 Oct 2026; POL-5E1B4800's premiums (81,000 + 54,000 on 5 Nov, 64,680 + 43,120 on 10 Nov) bought no units. Nothing alerts anyone (no action-bell item, no "price missing N days"); the policy shows an old value. (D13)
123. **Prices typed by hand, one fund and one day at a time**: no import of the manager's daily price file, no "fill the missed dates"; a 3-week gap is 15+ proposals per fund, each needing a second person. (D13 Option A)
124. **8 test funds ("E2E EQ480614" …) open beside the real ones**, with 10 test orders waiting; they clutter the page and could be offered on a product.
125. **"Funds" is ambiguous beside the ledger**: ledger 1275 "funds placed with the fund manager" is the company's own investments; this page is unit-linked client funds — call it "Unit-linked funds". No link between units in issue here and the money placed with the manager in the ledger.
126. **One person adds a fund**, with any management charge, live at once; prices need two people, the fund and its charge need one.
127. **A waiting order does not say whose or why**: only a count; finance cannot see POL-5E1B4800's TZS 81,000 waiting since 5 Nov.
128. **The management charge is stored but never deducted or booked**: clients are charged nothing and the company's fee income is missing from the ledger (check with D13 Q2 whether the manager's NAV is already net of it).
129. **No separate sale and repurchase price per fund** (UTT-style spread, ~1–2% below NAV).
130. **No units reconciliation against the manager's statement**: the reconciliation panel counts only the platform's own units.

### Maturities
Checked in the browser (staff.finance) and the database, 2026-11-10: next 12 months = 6 fixed deposits (5 e2e test deposits of TZS 1M, 5–7 Feb 2027; 1 real, Ndoto Fixed Deposit POL-AC191588, TZS 6,000,000 at 6% for 12 months, matures 5 Nov 2027, no instruction → pays out to +255747577461). Fixed deposits mature automatically (DepositMaturityDrain, hourly: pay out or reinvest); scheduled maturities appear in Benefits due; an endowment/savings with no schedule needs a hand-filed maturity claim.

131. **A cash planning list with no money on it**: for deposits "Sum assured" reads "— No cover above the account value"; show the amount due (principal + interest, ~TZS 6.36M for POL-AC191588) and a total per currency.
132. **Does not show whether the money leaves or stays**: a deposit's instruction (reinvest = no cash; pay out; none yet → pays out) should be a column.
133. **No client name, payout method or number**.
134. **Fixed deposits are stored as product category ENDOWMENT**: reports by product line count deposits as endowments; they are IFRS 9 deposits.
135. **Product column runs name and code together, code twice**: "E2E Fixed Deposit FTD-E2E-1793853818746FTD-E2E-1793853818746".
136. **Only upcoming maturities**: nothing for matured-but-unpaid (failed payout attempts) and no history of what was paid or reinvested.
137. **Unscheduled endowment/savings maturities rely on memory**: no "matures in 30 days, no payment set up" flag on the page or in the action bell.
138. **Test deposits mixed with real ones** (see 124).
139. **The click lands on the wrong place**: a Maturities row opens the policy at Overview, which shows only "Matures Nov 5, 2027"; the amount (Paid at maturity TZS 6,360,000) and the instruction are on the Account tab. Open at the Account tab's "At maturity" (or Payouts for scheduled maturities) — better, show amount and instruction on the row (131/132).
140. **Only the date is clickable**: the policy number looks like a link but does nothing (DataTable activates the first column only — see 45).
141. **Payouts tab says a fixed deposit "pays nothing while the life assured is alive"**: false — it pays principal + interest at maturity; show that payment there or point to the Account tab.
142. **Wrong actions on a fixed deposit**: Overview offers Make paid-up (check text "A savings product pays no value until its first two or three full years are paid"); header offers Withdraw and Loan while the Account tab says "No top-ups or part withdrawals".
143. **The page probes features the product lacks**: bonuses, annuity, account/term-account each 404 on a deposit (6 console errors) — noise that hides real errors.
144. **Record instruction gives no visible confirmation**: the POST succeeds but the form stays filled and unchanged; the only sign is the line above it changing from "No instruction: it will be paid out to +255747577461" to "Pay out to +255747577461 (recorded by Rehema Finance)" — the user reported "the form doesn't respond". Confirm ("Instruction recorded: pay out TZS 6,360,000 to … on 5 Nov 2027"), collapse the form, offer "Change instruction". (Tested 2026-11-10 on POL-AC191588: recorded Pay out to its own number, same as the default.)
145. **Mobile number marked required (*) but the hint says "Leave it blank"**: it is optional; blank = the number the deposit came from.
146. **One person can redirect a maturity payout to any number, effective at once**: no second person, no SMS/OTP to the policyholder. Needs approval or at least an SMS to the client's number on file.
147. **No instruction history**: a new instruction replaces the old; nothing shows what changed, by whom, or why.

### Month-end close
Browser 2026-11-10, October 2026: steps 1–3 Done (closing started by Rehema 1:16 PM), step 4 expense allocation prepared by Asha Admin 2:31 PM awaiting a finance approver, steps 5–7 To do, step 8 "Not yet: Period 2026-06 must be locked first".

148. **Steps 5–7 look required but the lock does not check them**: EngineLockGate only checks differences of a POSTED run, so a month locks with no engine run at all — the IFRS 17 liability of GMM/VFA policies (endowment, term, annuity, unit-linked) is never measured. (D14)
149. **Only the first earlier month is named as a blocker**: "Period 2026-06 must be locked first" hides that June, July, August and September each need their own close and lock (see 64).
150. **Zero manual journals counts as Done**: "None this month" is ticked, though every real month has payroll, rent, depreciation; it should be a warning.
151. **Step 4 waits with no way forward**: does not say who may approve (D2), and "Open IFRS 17 engine" does not land on the allocation.
152. **Step names are jargon with no explanation**: "Send the engine its figures", "extract", "Reconcile with the engine" — never says the engine is the actuary's IFRS 17 calculation, who runs it, or what comes back.

### IFRS 17 engine
What it is for: the hand-over desk between finance and the actuary for long-term business (GMM/VFA). The platform holds the actual data; the forecast (future premiums, claims, expenses, discounting, risk adjustment, CSM) is made by the actuary's own model outside the platform. Three parts: expense allocation (two-person) → extract (period CLOSING, nothing unposted) → upload of the actuary's results template, approved by a second FINANCE_APPROVER, posted, then reconciled (difference explained by one, accepted by another). PAA (funeral, group, lender schemes) and IFRS 9 (savings, deposits, pension before vesting) are handled by the platform itself.

153. **Step numbers disagree with Month-end close**: the two pages number and name the same steps differently.
154. **Expense pool is 0.00**: no running costs (salaries, rent) have ever been booked, so the allocation splits nothing and the actuary gets no expense figure; the page does not warn.
155. **October allocation was prepared by Asha Admin**, not a finance officer — preparing an allocation should be a finance permission.
156. **The page never says the engine is outside the platform**: no actuary named, no due date, no reminder or hand-off tracking ("extract sent on…, results expected by…"); a first-time user expects the platform to calculate it.
157. **Jargon throughout**: LRC, LIC, CSM, ARC, AIC, RI CSM, P-01..P-17, FINANCE_APPROVER shown with no plain words (e.g. "owed for future cover", "owed for claims", "profit not yet earned").

### Year-end close
What it is for: once December is CLOSING and nothing else blocks its lock, finance prepares the close of the year; a second FINANCE_APPROVER approves; one SYSTEM journal in December zeroes classes 4–8 to 3310 and moves the result to retained earnings 3210 (and closes dividends 3320). December locks only with a posted, non-stale close; any later class 4–8 posting makes it stale and a new close reverses the old journal. Seen 2026-11-11: 2026 shows a loss of 7,766,689.25; Prepare close answers "Period 2026-12 is OPEN".

158. **The year's result is wrong without engine runs**: TZS 67M of GMM premiums sit in 2121 (LRC) and only an engine run moves them to revenue — none ever posted — while claims hit expense (7.5M death). 2026 shows insurance revenue 91k (PAA only) against 8.4M claims, i.e. a false loss. The close does not check that the year's engine runs are posted (ties to D14).
159. **Prepare close looks ready when it is not**: the button is enabled and only after clicking says December is open. Show a checklist first (December closing, Jan–Nov locked, engine runs posted, nothing unposted) and enable the button only when it is all met.
160. **Signs and words for accountants only**: "net Dr − Cr" shows revenue as a minus (−91,199.19); "retained earnings move by −7,766,689.25". Plain: Income / Costs / Loss for the year.
161. **Header is jargon**: "Classes 4–8 closed to 3310 … (M-07) … (M-11)" — never says "moves this year's profit or loss into retained earnings".
162. **Closing journal shows account codes without names** (Dr 4160 …), unlike the table above it.
163. **Year is a free text box**, and the default (last year, 2025) shows "Nothing to close". Use a year picker of years with postings, preselecting the year to close.
164. **No tax step**: no corporate income tax charge/provision before the close (class 8 holds only salaries). Decision needed (D15): booked by manual journal, or should the close remind/require it?

---

## Reinsurance & returns

### Treaties
What it is for: the company's own insurance — a share of each policy's risk and premium passes to a reinsurer, who pays that share of claims. Types: quota share (fixed %), surplus (above retention), XOL (claim-level only). Each new policy is ceded to the latest treaty in force; a bordereau is written monthly and a statement settles each quarter. Seen 2026-11-11: only two e2e test treaties; 81 policies (41 term, 36 endowment, 4 unit-linked) ceded 50% to "E2E Re 1793855655139"; recoveries 500,000; no bordereau yet.

165. **The ceding rules are an unapproved placeholder**: CessionCalculator says "THE ENTIRE ALGORITHM BELOW IS AN INVENTED PLACEHOLDER… needs Actuarial/Reinsurance sign-off".
166. **The savings part is ceded**: quota share cedes the % of the whole premium and whole sum assured — unit-linked POL-FF97DBDB (100,000/month) passes 50,000/month to the reinsurer though most buys units; endowments the same. Life reinsurance normally takes the sum at risk (sum assured − fund/reserve) and its risk premium.
167. **Every product is ceded, with no choice**: a treaty has no product/class list; the latest in force takes every new policy. Real treaties name the business covered (individual life vs group/credit).
168. **One treaty per policy**: only the latest in force takes new policies; no co-reinsurers (60/40) and no surplus layered over a quota share.
169. **Form asks for what does not apply**: quota share requires an unused retention limit; XOL annual premium not clearly asked; type shown as "QUOTA SHARE" with no explanation of types.
170. **One person creates a treaty, no approval**: it moves premium to an outside company monthly; should be propose → approve.
171. **Policy counts disagree**: list shows 81 policies, treaty page "Nov 2026 so far" shows 79, unexplained.
172. **No treaty reference or documents**: no treaty number, signed-slip upload, reinsurer contact or payment details.

### Reinsurance statements
What it is for: each quarter the company and the reinsurer settle up — ceded premium owed against commission and claim recoveries owed back — netted into one balance. Chain: monthly bordereau (automatic, after the month ends) → quarterly statement prepared from the treaty page (adds the reinsurer's funds withheld and profit commission), submitted, approved by a second person, posted → settlement (received / paid by EFT / profit commission / withheld released), recorded by one and confirmed by another. Seen 2026-11-11: no bordereaux, statements or settlements; the first bordereau (November) is written early December, the first statement (Q4 2026) in January 2027; the 500,000 recovery waits for it.

173. **No way to start a statement here**: the page says "prepared from its treaty's page" but has no link to treaties and no list of quarters due.
174. **Default filter hides everything**: opens on "Awaiting approval", so it looks as if none exist; never says when the next statement is due (e.g. "Q4 2026: ready once December's bordereau is written, early January").
175. **No reconciliation with the reinsurer's statement**: only funds withheld and profit commission are typed in; premium, commission and claims come from the platform alone, with no place to record or explain a difference.
176. **Nothing to send to the reinsurer**: no Excel/PDF download of the bordereau or statement.
177. **No cash call**: a large recovery (500,000) waits for the quarterly statement; treaties normally allow an immediate cash call for big claims.
178. **Wording**: "One person prepares, a finance approver posts" is the only explanation — never says a statement settles premiums against claims and commission.

### Regulatory returns
What it is for: building the reports TIRA requires (quarterly/annual) from the platform's data — choose a return type and period, Generate, the lines fill in. Seen 2026-11-11: one return type, QUARTERLY_PRUDENTIAL, labelled in the database "(PLACEHOLDER) pending the TIRA return catalog (C2). Line codes and labels are invented."; 10 lines. Generated 2026-Q4 as a test: in force 150, sum assured 501,255,900, issued 156, lapsed 0, claims registered 16 / settled 7, death claims settled 8,395,203.64, premium collected 79,869,250, reinsurance premium ceded 322,450. 2026-Q1 is all zeros.

179. **The return is invented and the page hides it**: the definition is a PLACEHOLDER pending TIRA's catalogue, but the page shows only "QUARTERLY_PRUDENTIAL", no warning — it could be filed as real.
180. **Far too little for a TIRA return**: no split by class of business (individual, group, credit life, funeral, annuity, savings); no balance sheet, investments, solvency/capital adequacy, outstanding claims or expenses.
181. **A return can be generated for a quarter not yet ended**: 2026-Q4 generated on 11 Nov reads Ready with no warning.
182. **Figures do not agree with the ledger**: the return reads its own projections, not the accounts — death claims settled 8,395,203.64 vs ledger 5110 (death) 7,500,000.00 for the year; no check against the books; unclear whether "Premium collected" includes savings deposits and unit-linked investment.
183. **No approval, sign-off or filing record**: status is always "Ready"; no prepare → approve → "Submitted to TIRA on…"; regenerating silently replaces a return, even one already filed.
184. **Nothing to send**: no download in TIRA format, Excel or PDF.
185. **Typed-in fields**: return type is free text (must type QUARTERLY_PRUDENTIAL exactly), period free text ("2026-Q4"); should be pickers, and the code shown as a plain name.
186. **No calendar or reminders**: nothing shows which returns are due when, or overdue; nothing on the dashboard.

---

## Distribution

### Agents and commission
What it is for: the sales force — tied agents, brokers, bancassurance — their licence, hierarchy (agent → supervisor), login, commission plan (per product: first year / renewal / override / supervisor override, rate or flat; an agent may have an own-plan override) and commission (accruals into a monthly statement; after the month closes finance requests a payout, a second person approves, paid net of withholding). Seen 2026-11-11: 19 agents (17 e2e); Juma Senior (LIC-SENIOR-001) shows TZS 20,000,160 for November — 10,000,000 each on two CLive Savings policies (POL-5705F7FB, POL-8154F1A5) of 2,000,000 premium, because the CLive Savings plan's FIRST_YEAR rate is stored 5.0000 (= 500%); "Request payout" is offered.

44. **Commission rate has no sanity limit and reads as a fraction**: the form asks for "0.1000" with no upper bound, so 5 (meant as 5%) was saved as 500% (D8). Ask for a percentage and refuse above 100%. (Found first under Ledger; same problem as 187.)
187. **A 5% commission stored as 500%**: the rate box wants a fraction ("0.1000 (10%)"); typing 5 gives 500% and nothing caps it above 100%. Enter as a percent with a sane maximum and show an example amount. The CLive Savings plan and the two 10M accruals need correcting.
188. **Commission on a savings deposit**: CLive Savings pays commission on the whole deposit. Decision needed (D8): do savings/deposit products pay commission, and on what base?
189. **Commission plans live on the agent page**: a plan belongs to a product, but it is seen/changed only from an agent's page via a product picker — and the change applies to every agent. No "Commission plans" list of products and rates.
190. **Changing a plan needs one person**: rate changes move money to agents; should be propose → approve.
191. **Distribution has only Agents**: no all-agent view of commission owed, payouts waiting, or month totals in this section (only per agent or in Reports).
192. **Agents list hard to read**: licence number is the main column, no agent-name column; "Party" repeats the same name; search finds by licence number only.
193. **Raw technical text on the agent page**: agent UUID in the header; "Own plan override — Null means the product's active plan applies instead"; tier names raw, rate shown as "0.1000 (rate)".
194. **Plan picker hides which products have a plan**: category chips with counts; each product must be opened to find out.
195. **No licence-expiry warning**: nothing warns before an agent's licence expires, and sales by an expired agent are not flagged.

---

## Not yet walked
- **Reports** — the user will walk it later.

## Dev data seen during the walkthrough (not product problems)

Test records on the dev system that cluttered pages or produced odd figures. Clean up before any demo; none of these is a
code fix on its own (the code gaps they exposed are items 49, 62, 67, 124, 138).

- Accounting periods 1997-12, 1998-01 (open), 1999-11, 2000-11 and 2025-12 from tests; a manual journal in 1997-12.
- A test accounting election "OCI option ON from 2033-11-27" (item 67).
- 8 test unit-linked funds ("E2E EQ480614" …) with 10 waiting orders; 5 test fixed deposits maturing Feb 2027.
- Only two reinsurance treaties exist, both e2e ("E2E Re …"); real policies are ceded 50% to "E2E Re 1793855655139".
- A 2026-Q4 regulatory return generated on 2026-11-11 as a test.
- 17 of the 19 agents are e2e test agents; 5 of the 6 commission plans are on e2e test products.
- **Still wrong on dev, left as found (user not yet asked to correct):** the CLive Savings commission plan at 5.0000
  (500%) and Juma Senior's two TZS 10,000,000 accruals (November statement TZS 20,000,160, open, payout requestable).
