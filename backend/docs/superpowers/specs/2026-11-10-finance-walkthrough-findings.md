# Finance walkthrough — problems found (2026-11-10)

Recorded while walking the finance workflow with the user, as finance officer Rehema (`staff.finance`). Nothing here is
fixed yet unless marked. Order is the order found.

## Already fixed during the walkthrough

- Closed savings account facts wrapped mid-number; reason shown as a code ("DEATH") — merged 68cbbcfd.
- Header offered Deposit / Change beneficiaries / Withdraw / Loan on ended policies — merged 68cbbcfd.
- Paying out names (Payouts → Benefits due, All payments out, Maturities moved) — merged c9678f53.

## Open — needs a decision

| # | Problem | Decision needed |
|---|---|---|
| D1 | A policy whose life died and whose death claim was paid ends as **Surrendered** (no such end state exists). Reports count deaths as surrenders. | Name of the new end state ("Claim paid" suggested). |
| D2 | Dev `staff.finance` holds six `*.approve-own` permissions (granted 2026-11-08), so the payout two-person e2e fails. | Keep them (change the test) or remove them. |
| D3 | An unpaid offer becomes Not taken up after 30 days even when its first premium is recorded but not yet confirmed (agent cash, or a deposit awaiting its second signature). | Proposed: the expiry sweep skips offers with money in transit. |
| D4 | No cover while an agent holds the client's first premium. | Should cover date back to when the agent collected the cash, once confirmed? (business/underwriting) |
| D5 | Kindelema's two CLive Savings policies (POL-5705F7FB paid, POL-8154F1A5 offer) were set up as 2,000,000 **monthly** for 13 months via the agent forms. | Was a single opening deposit intended? If so, cancel and reissue. |
| D6 | Dunning ladder days (7/14/21/30) and the 30-day offer validity are placeholders pending Collections / Underwriting sign-off. | Confirm the figures. |

## Open — to fix

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
24. "Ask to pay" (mobile money) for agents **already exists** — the agent Billing tab's Deposit link. Not to rebuild.

### Other
25. Payouts e2e "reviewer is refused" fails on dev data (D2).
26. Payment refusals (4xx) are not logged, so a reference like c0f5b604… cannot be looked up.
27. Policy page: "Surrendered" beside "Closed — after a death claim" (D1).
