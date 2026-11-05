# Money movement, step 2: the policy balance — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (the user prefers inline execution). Steps use checkbox (`- [ ]`) syntax.

**Goal:** Every confirmed premium deposit lands on its policy's balance and is posted to the ledger at once for what
arrived; billing then pays the policy's open invoices oldest first, and what is left stays as the balance and pays
the next premium when it is raised. Closes review findings F1 (part payments unposted), F2 (credited invoice
over-posted), F3 (late money lost) and the "more than owed" refusal.

**Architecture:** inside billing (D6): `billing.policy_balance` + add-only `billing.balance_entry`, and an allocator
that runs under a row lock when a deposit lands and after invoices are raised. The ledger stops posting cash off
`billing.PremiumCollected` (still the "invoice fully paid" business event for its 12 other listeners) and posts two
new events instead: `billing.DepositReceived` (DR RECEIPT / CR 2128) and `billing.PremiumPaidFromBalance`
(DR 2128 / CR 2122 or 2142, levy on the premium paid).

**Spec:** `backend/docs/superpowers/specs/2026-11-04-money-movement-design.md` §3, §4, §8, §9, §13 step 2; D1-D3, D6,
D12.

## Global constraints

- As step 1 (host `./mvnw`, `JAVA_HOME=~/.jdks/jdk-21.0.12.1+1`; Edit/Write tools, not shell; dev DB migration by hand
  then restart; never Prettier).
- **Test cadence (user, 2026-11-04):** inside a task run only the classes/folders it touches; the heavy runs
  (affected backend classes together, tsc+eslint+full vitest, affected e2e) only at the end of the step, ONE at a
  time. Do not edit Java while a Maven run compiles.
- Money is BigDecimal scale 2, currency per policy; a deposit in another currency is refused.
- No arithmetic in the browser; every figure the UI shows comes from the server.

## Decisions made in planning (within the spec)

1. **The deposit names a policy through its invoice for now.** `POST /receipts` and `POST /invoices/{id}/payment-request`
   keep their shape; the invoice only identifies the policy (and a gateway request still asks for that invoice's
   balance due). The deposit then pays the policy's OLDEST open invoice first (D2) — which may be an older one than
   the invoice named. Policy-level deposit screens are step 5.
2. **Savings-type policies (product AccumulationPlan basis `ACCOUNT`) keep today's road** in this step: no policy
   balance, receipts still capped at what is owed, `PremiumCollected` posts nothing as today (IFRS 9 `G-*` rules).
   Their surplus-to-savings path (D12) arrives with "For: Savings" in step 5.
3. **A loan surplus (`policyloan.LoanOverpaid`) becomes a DEPOSIT entry with no ledger event** — step 1 already posted
   it DR RECEIPT / CR 2128.
4. **`premium_receipt` rows are still written**, one per amount applied to an invoice, so the Payment schedule keeps
   working unchanged.
5. **Commission, messages, paid-to-date** stay on `PremiumCollected`, still published on the →PAID edge with the
   invoice's own amount.

## Tasks

### Task 1: Tables
- Create `backend/db-migrations/billing/V14__policy_balance.sql`:
  - `billing.policy_balance (tenant_id, policy_number, currency CHAR(3), version BIGINT, created_at; PK (tenant_id, policy_number))`
  - `billing.balance_entry (entry_id UUID PK, tenant_id, policy_number, entry_type CHECK IN ('DEPOSIT','PREMIUM_PAID'), amount NUMERIC(19,2) CHECK (amount > 0), currency, payment_id UUID NULL, invoice_id UUID NULL, method VARCHAR(20) NULL, reference VARCHAR(100) NULL, source VARCHAR(20) NOT NULL DEFAULT 'PAYMENT', created_at)`
  - `UNIQUE (tenant_id, payment_id) WHERE entry_type='DEPOSIT' AND payment_id IS NOT NULL` — a redelivered confirmation deposits once.
  - Index `(tenant_id, policy_number, created_at)`; RLS with the NULLIF predicate and grants exactly as V10 (`premium_receipt`); SELECT, INSERT on entries (add-only), SELECT, INSERT, UPDATE on the balance row.
  - Steps 3 widens `entry_type` (WITHDRAWAL, REVERSAL).
- Entities `billing.domain.PolicyBalance`, `billing.domain.BalanceEntry`; repositories with `findForUpdate` (`@Lock(PESSIMISTIC_WRITE)`) and `sumByPolicy` (DEPOSIT minus PREMIUM_PAID).
- Add V14 to the migration lists of the billing/finaccounting/policyloan tests that touch premiums (`grep -rl "billing/V13__credit_refund_payout" backend/src/test`).
- Apply to the dev DB with `docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform`.
- Commit.

### Task 2: The allocator
- `billing.application.PolicyBalances` (package-private bean):
  - `deposit(tenant, policy, amount, currency, paymentId, method, reference, source, by)` → idempotent on paymentId; inserts the balance row if absent (insert-on-conflict-do-nothing, then lock); refuses another currency; writes DEPOSIT; publishes `billing.DepositReceived` unless `source = LOAN_SURPLUS`; then `allocate`.
  - `allocate(tenant, policy)` → under the lock: balance = sum; open invoices (DUE, IN_GRACE, OVERDUE, PARTIALLY_PAID) by due date; for each, `applied = min(balance, invoice.balanceDue(credited))`; `invoice.applyPayment(applied, credited)`; PREMIUM_PAID entry; `premium_receipt` row (reference = the deposit's reference, or `balance` when paid from an earlier deposit); publish `billing.PremiumPaidFromBalance {entryId, invoiceId, policyNumber, amount}`; resolve the arrears case on PAID and publish `PremiumCollected` on the →PAID edge exactly as `applyConfirmedPayment` does today (move that block into a shared private method).
  - `balanceOf(policy)` → for tests and step 5.
- Tests first (`billing/PolicyBalanceIntegrationTest`, copying `BillingApiIntegrationTest`'s container setup): deposit 150,000 on one 50,000 invoice → invoice PAID, balance 100,000, one PremiumCollected; a part deposit 20,000 → PARTIALLY_PAID, balance 0; two open invoices → the older is paid first; a redelivered paymentId → one deposit; a credit-then-pay invoice (13,800 charged, 4,200 credited, 9,600 deposited) → PAID, balance 0; another currency → refused.
- Commit.

### Task 3: Money reaches the balance
- `BillingApiImpl.applyConfirmedPayment` (the PaymentConfirmed path for PREMIUM): find the invoice's policy; if savings-type (decision 2) keep today's body; otherwise `policyBalances.deposit(...)`. The PREMIUM waiting/over-owed refusals in `Receipts.record` apply only to savings-type policies (`billing.isSavingsType(policyNumber)` on BillingApi).
- After each of the three `PremiumInvoiceGenerated` publications (`BillingApiImpl` ~759, ~828, ~1026), call `policyBalances.allocate` for that policy (skipped for savings-type), so a balance pays a new premium.
- `billing.application.PaymentEventListener`: on `policyloan.LoanOverpaid`, `deposit(..., source LOAN_SURPLUS)`.
- `waive(...)`: outstanding = charged − paid − credited (`balanceDue`).
- Tests: extend `PolicyBalanceIntegrationTest` — late money on a PAID invoice becomes balance; a balance pays the next raised invoice; a waived part-paid invoice reverses only what is still owed; a loan surplus lands on the balance with no DepositReceived. Update `BillingApiIntegrationTest` / `PremiumPostingEndToEndTest` assertions that relied on the refusal or on a partial staying unposted.
- Commit.

### Task 4: Ledger rules
- `posting-rules.yaml`:
  - `billing.DepositReceived` models [GMM, VFA, PAA]: DR RECEIPT / CR 2128 (`movement: PRM_REN` on neither line — 2128 is not an engine policy account).
  - `billing.PremiumPaidFromBalance` GMM/VFA: DR 2128 / CR 2122 `PRM_REN`; DR 5220 / CR 2650 levy. PAA: DR 2128 / CR 2142 `PRM_REN`; levy same. IFRS9: `post: false`.
  - `billing.PremiumCollected` PLAT-A07 and I-02 → `post: false` with a description saying cash now posts from the deposit.
- `PostingFactsExtractor`: shapes for both new events (DepositReceived: amount, refType POLICY, receiptMethod attribute; PremiumPaidFromBalance: amount + levy, sourceRef = entryId); `PostingEngine` computes `levy` for `PremiumPaidFromBalance` instead of `PremiumCollected`.
- `JournalWords`: "Deposit received", "Premium paid from balance".
- Tests first: `PostingFactsExtractorTest` cases; `PremiumPostingEndToEndTest` — the books-match assertions for 150,000 on 50,000 (1120 +150,000; 2122 → 0; 2128 = 100,000; levy on 50,000), for a part payment (1120 +20,000 at once), and for the credit case (1120 +9,600, 2142 → 0, levy on 9,600). Update `PostingRulesTest`, `PostingEngineIntegrationTest`, `FinaccountingContractTest`, `AccountingPeriodAndPolicyRegisterIntegrationTest`, `FinaccountingApiIntegrationTest`, `CommissionAccrualEndToEndTest` where they expect PremiumCollected to post cash.
- Commit.

### Task 5: The deposit form no longer caps a premium
- `NewReceiptPage` and `InvoiceReceiptForm`: the hint becomes "Owed now {server figure}. Anything above it stays on the policy and pays the next premiums." for a non-savings policy; the invoice select label "Policy, through its invoice". Receipt confirmation copy: "is added to the policy's balance and pays what it owes, oldest first".
- `ReceiptPage` confirm consequence for PREMIUM: same words.
- Tests: the receipt and invoice-panel unit tests that asserted the cap wording.
- Commit.

### Task 6: End of step (heavy runs, one at a time)
1. Backend: all classes touched in Tasks 1-4 together, from `clean` (dev backend stopped first, restarted after).
2. Frontend: `tsc; eslint .; vitest run` in one call.
3. E2E, if time allows: `staff-receipts`, `staff-billing`, `staff-arrears` — one spec at a time.
4. Docs: event catalogue (`DepositReceived`, `PremiumPaidFromBalance`, PremiumCollected no longer posts cash).

## Not in this step
Withdrawals, reversals, policy-end withdrawal (step 3); balance and statement screens, policy-level deposit and
request, "For: Savings" (step 5); renaming (step 6); past-data correction (step 8).
