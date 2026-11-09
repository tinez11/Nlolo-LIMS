---
target: the staff Approvals page
total_score: 22
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 3
timestamp: 2026-11-09T14-54-12Z
slug: frontend-src-features-approvals-approvalspage-tsx
---
# Approvals page critique (2026-11-09)

Heuristics: 1 Visibility 3 · 2 Real world 2 · 3 Control 3 · 4 Consistency 2 · 5 Error prevention 2 · 6 Recognition 1 · 7 Flexibility 1 · 8 Minimalist 3 · 9 Recovery 3 · 10 Help 2 = 22/40.

Priority issues:
- [P1] Rows don't say whose money or why: no client name, product, payee/method, reason; summary is a raw reference ("mobile money MM-5nugwxeu7z8f"). Fix: per-row client + reason + payee line from each ApprovalSource; readable method words.
- [P1] Raw user ids and broken copy: "Asked by 30710ea6-…" when the asker is not a listed staff user; "Reverse a deposit: nothing" (BillingApprovalSources uses the reason verbatim). Fix: fallback "Someone no longer on the staff list"; reason-less summary.
- [P1] Only 2 of ~36 kinds can be reviewed in place; every other row leaves the page and loses the queue. Fix: a review drawer for the money kinds (payouts, deposits, reversals, agent cash, withdrawals, refunds) showing the request, policy facts, payee and history, with the page's own act.
- [P2] Groups made only of your own requests lead the page ("Deposits to confirm, 0 for you"). Fix: actionable groups first; yours collapsed under "Waiting on someone else".
- [P2] No way to narrow or prioritise: no filter by kind/amount, no search by policy/client, no age ("waiting 5 days") or overdue flag. Fix: age in days with an overdue tint past the kind's SLA; a kind filter and search.
- [P3] Phone: asker and date hidden; references break mid-token.

Receipts: money IN has receipts (staff: Collections > Deposits row > receipt page > Download PDF; policy Billing tab invoice > receipt; Go to… RC-number; customer: Documents > Receipts). Money OUT (payouts, withdrawals, surrenders, claims, refunds, commission) has NO receipt or payout advice: receipts step 4 (PA-… payout advice, claim settlement letter) was never built.
