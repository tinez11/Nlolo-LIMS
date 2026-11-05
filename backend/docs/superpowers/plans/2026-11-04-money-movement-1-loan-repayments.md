# Money movement, step 1: loan repayments through deposits — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A loan repayment counts only when money is confirmed — a mobile-money request or a deposit recorded by one person and confirmed by a second — capped at what the loan owes, posted to the account the money reached.

**Architecture:** The payment module is the one road in. A deposit "For: Loan" is a `payment_transaction` with purpose `LOAN_REPAYMENT` and the loan id as its source. On approval (or gateway confirmation) payment publishes `payment.PaymentConfirmed`; policyloan's existing `PaymentEventListener` takes `LOAN_REPAYMENT` and applies it, capped at the outstanding balance; any surplus is published as `policyloan.LoanOverpaid` and posted to 2128 until step 2 gives it a policy balance. The direct `POST /loans/{loanId}/repayments` endpoint goes.

**Tech Stack:** Java 21, Spring Boot, Spring Modulith, JPA, PostgreSQL (Testcontainers in tests), Flyway-style SQL in `backend/db-migrations`, React + TypeScript + Zustand + react-hook-form + zod, openapi-typescript, Vitest, Playwright.

**Spec:** `backend/docs/superpowers/specs/2026-11-04-money-movement-design.md` (§3, §6 row "Every deposit", §8 row `LoanRepaid`, §13 step 1, decision D11).

## Global Constraints

- Money in uses `collections.manage` (record), `collections.approve` (second person), `collections.approve-own` (alone, logged) — D11. Not `loans.manage`.
- Every money value on the wire is a decimal string plus `currencyCode`; no arithmetic in the browser.
- Event listeners follow the existing shape: `@TransactionalEventListener(AFTER_COMMIT)`, `REQUIRES_NEW` via `TransactionTemplate`, TenantContext save/set/restore (copy `policyloan.application.PaymentEventListener`).
- Never run Maven in Docker; host `./mvnw` with `JAVA_HOME=~/.jdks/jdk-21.0.12.1+1`. Stop the dev backend before `clean test`; restart it after.
- Run affected test classes per task; the full backend suite only at the merge gate.
- Edit files with the Write/Edit tools, not shell heredocs or sed.
- A new migration is not applied to the dev database automatically: apply it with `psql` and restart the dev backend.
- E2E specs find controls by accessible name; update a spec in the same task that renames or removes its control.
- Never run Prettier.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## File map

| File | Responsibility | Task |
| --- | --- | --- |
| `backend/db-migrations/payment/V22__loan_repayment_purpose.sql` | allow purpose `LOAN_REPAYMENT` | 1 |
| `policyloan/api/PolicyLoanApi.java`, `policyloan/application/PolicyLoanApiImpl.java` | `applyConfirmedRepayment` (capped, method, surplus event); `requestRepayment` | 2, 4 |
| `policyloan/application/PaymentEventListener.java` | consume `PaymentConfirmed`/`PaymentFailed` with purpose `LOAN_REPAYMENT` | 3 |
| `policyloan/infrastructure/PolicyLoanController.java`, `RepaymentRequestDto.java`, `openapi-policyloan.yaml` | `POST /loans/{loanId}/payment-request`; drop `/repayments` | 4 |
| `payment/application/PaymentRequestListener.java` | `policyloan.RepaymentRequested` → collection with purpose `LOAN_REPAYMENT` | 5 |
| `payment/package-info.java`, `payment/application/Receipts.java`, `payment/domain/PaymentTransaction.java`, `payment/api/ReceiptView.java`, `payment/infrastructure/ReceiptController.java`, `openapi-payment.yaml` | a deposit can be for a loan | 6 |
| `finaccounting/application/PostingFactsExtractor.java`, `resources/finaccounting/posting-rules.yaml`, `finaccounting/infrastructure/JournalWords.java` | `LoanRepaid` by method; `LoanOverpaid` | 7 |
| `frontend/src/types/api/*`, `api/receipts.ts`, `api/policies.ts`, `features/receipts/*` | "For: Loan" on the deposit page; loan shown on the receipt page | 8 |
| `frontend/src/features/policies/LoansPanel.tsx`, `store/policyStore.ts`, delete `loanRepaymentForm.ts` | Loans tab: record deposit link, mobile-money request | 9 |
| `frontend/e2e/staff-policy-loans.spec.ts`, `backend/docs/05-event-catalog.md` | e2e and docs | 10 |

All Java paths are under `backend/src/main/java/tz/co/nlolo/lifeplatform/`; tests under `backend/src/test/java/tz/co/nlolo/lifeplatform/`.

---

### Task 1: Allow purpose LOAN_REPAYMENT

**Files:**
- Create: `backend/db-migrations/payment/V22__loan_repayment_purpose.sql`

**Interfaces:**
- Produces: `payment.payment_transaction.purpose` accepts `'LOAN_REPAYMENT'`.

- [ ] **Step 1: Write the migration**

```sql
-- db-migrations/payment/V22__loan_repayment_purpose.sql
-- Loan repayments through deposits (money movement step 1, 2026-11-04). A loan repayment is a collection like a
-- premium: requested from the payer's phone or recorded by hand and confirmed by a second person, under its own
-- purpose so billing (PREMIUM), accumulation (ACCOUNT_TOP_UP) and unitlinked (UL_TOP_UP) keep ignoring it and only
-- policyloan takes it. payment_transaction is partitioned; a CHECK on the parent reaches every partition.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE IF EXISTS payment.payment_transaction DROP CONSTRAINT IF EXISTS payment_transaction_purpose_check;
ALTER TABLE IF EXISTS payment.payment_transaction
    ADD CONSTRAINT payment_transaction_purpose_check
    CHECK (purpose IN ('PREMIUM','ACCOUNT_TOP_UP','UL_TOP_UP','LOAN_REPAYMENT'));
```

- [ ] **Step 2: Add it to the tests that will write that purpose**

In `finaccounting/ReinsuranceAndLoanPostingEndToEndTest.java` and `policyloan/LoanDisbursementEndToEndTest.java`, append `"db-migrations/payment/V22__loan_repayment_purpose.sql"` to the `MigrationTestSupport.applyMigration(...)` list immediately after `"db-migrations/payment/V21__acted_alone.sql"`. Other tests never write this purpose and need no change.

- [ ] **Step 3: Apply to the dev database**

Run: `psql "$DEV_DB_URL" -f backend/db-migrations/payment/V22__loan_repayment_purpose.sql` (the URL the other dev migrations used; see `backend/scripts/migrate.sh`).
Expected: `ALTER TABLE` twice.

- [ ] **Step 4: Commit**

```bash
git add backend/db-migrations/payment/V22__loan_repayment_purpose.sql backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/ReinsuranceAndLoanPostingEndToEndTest.java backend/src/test/java/tz/co/nlolo/lifeplatform/policyloan/LoanDisbursementEndToEndTest.java
git commit -m "feat(payment): a collection may be a loan repayment"
```

---

### Task 2: Apply a confirmed repayment, capped, by method, with any surplus announced

**Files:**
- Modify: `policyloan/api/PolicyLoanApi.java` (replace `recordRepayment`)
- Modify: `policyloan/application/PolicyLoanApiImpl.java:196-252` (`recordRepayment` body)
- Test: `policyloan/PolicyLoanApiIntegrationTest.java` (calls at lines 239, 243, 258, 314)
- Test: `finaccounting/ReinsuranceAndLoanPostingEndToEndTest.java` (calls at lines 629, 641)

**Interfaces:**
- Produces:
  ```java
  /** Money confirmed for this loan (a deposit For: Loan, approved; or a mobile-money request, confirmed). Applies at
   *  most what the loan owes, interest first; anything above that is published as policyloan.LoanOverpaid. */
  LoanView applyConfirmedRepayment(UUID loanId, BigDecimal amount, String currency, String paymentReference,
                                   String receiptMethod, UUID paymentId, String recordedBy);
  ```
- Produces event `policyloan.LoanRepaid` with an added key `receiptMethod` (String, e.g. `CASH`, `MOBILE_MONEY`).
- Produces event `policyloan.LoanOverpaid` payload: `paymentId` (UUID), `loanId` (UUID), `policyNumber` (String), `amount` ({amount, currencyCode}), `receiptMethod` (String), `paymentReference` (String).

- [ ] **Step 1: Write the failing tests**

Add to `PolicyLoanApiIntegrationTest` (same setup as the existing repayment test at line ~230, which originates and disburses a 500,000 loan as `originated`):

```java
@Test
void aRepaymentAboveWhatTheLoanOwesSettlesItAndAnnouncesOnlyTheSurplus() {
    LoanView originated = originateAndDisburse500k();   // the helper the existing repayment test uses; extract it if inline
    UUID paymentId = UUID.randomUUID();

    LoanView settled = policyLoanApi.applyConfirmedRepayment(originated.loanId(), new BigDecimal("650000"), "TZS",
        "SLIP-OVER-1", "CASH", paymentId, "finance-1");

    assertThat(settled.status()).isEqualTo(LoanStatus.SETTLED);
    assertThat(settled.outstandingBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    TenantContext.set(tenantId);
    BigDecimal repaid = loanTransactionRepository.findByLoanIdOrderByOccurredAt(originated.loanId()).stream()
        .filter(t -> "REPAYMENT".equals(t.getTransactionType())).map(LoanTransaction::getAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(repaid).as("only what was owed is booked against the loan").isEqualByComparingTo("500000");
    List<DomainEventEnvelope<?>> overpaidEvents = eventRecorder.ofType("policyloan.LoanOverpaid");
    assertThat(overpaidEvents).hasSize(1);
    Map<?, ?> overpaid = (Map<?, ?>) overpaidEvents.get(0).payload();
    assertThat(overpaid.get("paymentId")).isEqualTo(paymentId);
    assertThat(((Map<?, ?>) overpaid.get("amount")).get("amount")).isEqualTo("150000.00");
    assertThat(overpaid.get("receiptMethod")).isEqualTo("CASH");
}

@Test
void aRepaymentWithinTheBalanceAnnouncesNoSurplusAndCarriesItsMethod() {
    LoanView originated = originateAndDisburse500k();

    policyLoanApi.applyConfirmedRepayment(originated.loanId(), new BigDecimal("200000"), "TZS", "SLIP-1",
        "BANK_DEPOSIT", UUID.randomUUID(), "finance-1");

    assertThat(eventRecorder.ofType("policyloan.LoanOverpaid")).isEmpty();
    Map<?, ?> repaid = (Map<?, ?>) eventRecorder.ofType("policyloan.LoanRepaid").get(0).payload();
    assertThat(repaid.get("receiptMethod")).isEqualTo("BANK_DEPOSIT");
}

@Test
void aRepaymentOnASettledLoanIsRefusedRatherThanSilentlyKept() {
    LoanView originated = originateAndDisburse500k();
    policyLoanApi.applyConfirmedRepayment(originated.loanId(), new BigDecimal("500000"), "TZS", "S-1", "CASH",
        UUID.randomUUID(), "finance-1");

    assertThatThrownBy(() -> policyLoanApi.applyConfirmedRepayment(originated.loanId(), new BigDecimal("1000"), "TZS",
        "S-2", "CASH", UUID.randomUUID(), "finance-1"))
        .isInstanceOf(LoanNotEligibleException.class);
}
```

`PolicyLoanApiIntegrationTest` has no event recorder and makes `tenantId` per test. Copy the nested `EventRecorderConfiguration` and `EventRecorder` classes from `finaccounting/ReinsuranceAndLoanPostingEndToEndTest.java:300-320` into it (rename the bean method to `policyLoanApiTestEventRecorder`), add `@Import(PolicyLoanApiIntegrationTest.EventRecorderConfiguration.class)` and `@Autowired private EventRecorder eventRecorder;`, and call `eventRecorder.clear()` at the start of each new test. `originateAndDisburse500k()` sets a fresh `tenantId` field the way the existing repayment test does (`UUID tenantId = UUID.randomUUID(); issuePolicyWithCashValue(tenantId, …)`, then originate 500,000 and `markDisbursed`); promote that `tenantId` to a field so the assertions can read it, and `@Autowired LoanTransactionRepository loanTransactionRepository;` if the class lacks it.

Also replace every existing `policyLoanApi.recordRepayment(loanId, amount, currency, ref, by)` call in both test files with `policyLoanApi.applyConfirmedRepayment(loanId, amount, currency, ref, "MOBILE_MONEY", UUID.randomUUID(), by)`.

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=PolicyLoanApiIntegrationTest`
Expected: compilation failure, `applyConfirmedRepayment` not found.

- [ ] **Step 3: Implement**

In `PolicyLoanApi`, replace the `recordRepayment` line with the `applyConfirmedRepayment` signature above (with its javadoc).

In `PolicyLoanApiImpl`, replace `recordRepayment` with:

```java
@Override
@Transactional
public LoanView applyConfirmedRepayment(UUID loanId, BigDecimal amount, String currency, String paymentReference,
                                        String receiptMethod, UUID paymentId, String recordedBy) {
    UUID tenantId = TenantContext.get();
    PolicyLoan loan = findLoanOrThrow(loanId, tenantId);
    if (!"DISBURSED".equals(loan.getStatus()) && !"REPAYING".equals(loan.getStatus())) {
        throw new LoanNotEligibleException("Loan " + loanId + " must be DISBURSED or REPAYING to accept a repayment (current: " + loan.getStatus() + ")");
    }
    // Money movement step 1 (2026-11-04): only CONFIRMED money reaches here -- a deposit a second person approved, or a
    // mobile-money request the rail confirmed. The loan takes at most what it owes; the rest is the payer's, announced
    // as LoanOverpaid so it is posted (2128) and, from step 2, held on the policy balance. Before this, an overpayment
    // drove principal below zero and was never given back.
    BigDecimal owed = computeOutstandingBalance(loan).max(BigDecimal.ZERO);
    BigDecimal applied = amount.min(owed);
    BigDecimal surplus = amount.subtract(applied);
    String method = receiptMethod == null ? "MOBILE_MONEY" : receiptMethod;

    if (applied.signum() > 0) {
        BigDecimal interestPart = position(loan).interestPartOf(applied);
        LoanTransaction repayment = loanTransactionRepository.save(
            new LoanTransaction(tenantId, loanId, "REPAYMENT", applied, currency, paymentReference));
        loan.markRepaying();
        BigDecimal outstanding = computeOutstandingBalance(loan);
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
            loan.markSettled();
        }
        policyLoanRepository.save(loan);
        // loanTransactionId is the posting's idempotency key: unique per repayment, stable across a redelivery (M9).
        eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanRepaid", tenantId,
            Map.of("loanId", loanId, "loanTransactionId", repayment.getLoanTransactionId(),
                   "policyNumber", loan.getPolicyNumber(),
                   "interestAmount", interestPart.toPlainString(),
                   "principalAmount", applied.subtract(interestPart).toPlainString(),
                   "amount", Map.of("amount", applied.toPlainString(), "currencyCode", currency),
                   "repaidAt", Instant.now().toString(),
                   "receiptMethod", method,
                   "outstandingBalance", Map.of("amount", outstanding.max(BigDecimal.ZERO).toPlainString(),
                                                "currencyCode", loan.getPrincipalCurrency()))));
    }
    if (surplus.signum() > 0) {
        eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanOverpaid", tenantId,
            Map.of("paymentId", paymentId, "loanId", loanId, "policyNumber", loan.getPolicyNumber(),
                   "amount", Map.of("amount", surplus.setScale(2).toPlainString(), "currencyCode", currency),
                   "receiptMethod", method, "paymentReference", paymentReference)));
    }
    return toView(loan);
}
```

Delete the old `recordRepayment` body and its javadoc block (the "M3 simplification" and the M9 idempotency story now live in the shorter comments above). If `applied` is zero because the loan owes nothing, the loan was already settled and the status check above has thrown.

- [ ] **Step 4: Run to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=PolicyLoanApiIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policyloan backend/src/test/java/tz/co/nlolo/lifeplatform/policyloan backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/ReinsuranceAndLoanPostingEndToEndTest.java
git commit -m "feat(loans): a repayment applies only confirmed money, capped at what the loan owes"
```

---

### Task 3: Policyloan takes confirmed loan money

**Files:**
- Modify: `policyloan/application/PaymentEventListener.java`
- Test: `policyloan/LoanDisbursementEndToEndTest.java` (it already boots payment with the gateway wiremock)

**Interfaces:**
- Consumes: `payment.PaymentConfirmed` payload keys `purpose`, `sourceRef` (loan id), `amount`, `gatewayReference`, `method`, `paymentRequestId`, `approvedBy` (receipts only); `payment.PaymentFailed` keys `purpose`, `sourceRef`, `reason`.
- Consumes: `PolicyLoanApi.applyConfirmedRepayment` (Task 2).

- [ ] **Step 1: Write the failing test**

Add to `LoanDisbursementEndToEndTest`, after a loan is disbursed in the existing setup:

```java
@Test
void aConfirmedLoanRepaymentFromPaymentReducesTheLoan() {
    LoanView loan = disbursedLoan();   // the existing test's path to a DISBURSED loan; extract it if inline
    UUID paymentId = UUID.randomUUID();
    publishAfterCommit("payment.PaymentConfirmed", Map.of(
        "paymentRequestId", paymentId, "idempotencyKey", "k-" + paymentId, "sourceRef", loan.loanId().toString(),
        "gatewayReference", "SLIP-77", "amount", Map.of("amount", "100000.00", "currencyCode", "TZS"),
        "confirmedAt", Instant.now().toString(), "purpose", "LOAN_REPAYMENT", "method", "CASH",
        "approvedBy", "finance-2"));

    TenantContext.set(tenantId);
    assertThat(policyLoanApi.getLoan(loan.loanId()).status()).isEqualTo(LoanStatus.REPAYING);
}

@Test
void aPremiumConfirmationIsNotMistakenForALoanRepayment() {
    LoanView loan = disbursedLoan();
    publishAfterCommit("payment.PaymentConfirmed", Map.of(
        "paymentRequestId", UUID.randomUUID(), "idempotencyKey", "k-prem", "sourceRef", loan.loanId().toString(),
        "gatewayReference", "X", "amount", Map.of("amount", "100000.00", "currencyCode", "TZS"),
        "confirmedAt", Instant.now().toString(), "purpose", "PREMIUM"));

    TenantContext.set(tenantId);
    assertThat(policyLoanApi.getLoan(loan.loanId()).status()).isEqualTo(LoanStatus.DISBURSED);
}
```

`publishAfterCommit(type, payload)` publishes `DomainEventEnvelope.of(type, tenantId, payload)` inside a `TransactionTemplate.executeWithoutResult` so AFTER_COMMIT listeners fire (the pattern `ReinsuranceAndLoanPostingEndToEndTest` uses with its `eventPublisher` and `transactionManager`); add it to the class if missing.

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=LoanDisbursementEndToEndTest`
Expected: FAIL — status stays DISBURSED in the first test.

- [ ] **Step 3: Implement**

In `PaymentEventListener.onDomainEvent`, add two cases:

```java
case "payment.PaymentConfirmed" -> withTenant(envelope, this::handleRepaymentConfirmed);
case "payment.PaymentFailed" -> withTenant(envelope, this::handleRepaymentFailed);
```

and the handlers:

```java
/** Loan money confirmed (money movement step 1): a deposit For: Loan, approved, or a mobile-money request, paid. */
private void handleRepaymentConfirmed(Map<String, Object> payload) {
    if (!"LOAN_REPAYMENT".equals(payload.get("purpose"))) {
        return; // a premium or a top-up rode the same event type
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> amount = (Map<String, Object>) payload.get("amount");
    Object by = payload.get("approvedBy");
    policyLoanApi.applyConfirmedRepayment(UUID.fromString(String.valueOf(payload.get("sourceRef"))),
        new BigDecimal(String.valueOf(amount.get("amount"))), String.valueOf(amount.get("currencyCode")),
        String.valueOf(payload.get("gatewayReference")), (String) payload.get("method"),
        UUID.fromString(String.valueOf(payload.get("paymentRequestId"))), by == null ? "gateway" : by.toString());
}

/** A loan collection that never arrived: nothing was applied, so there is nothing to undo -- the trail is the log. */
private void handleRepaymentFailed(Map<String, Object> payload) {
    if (!"LOAN_REPAYMENT".equals(payload.get("purpose"))) {
        return;
    }
    log.warn("Loan repayment collection failed for loan {} (tenant {}): {}", payload.get("sourceRef"),
        TenantContext.get(), payload.get("reason"));
}
```

Add `import java.math.BigDecimal;`. Update the class javadoc's first paragraph to say it also consumes `PaymentConfirmed`/`PaymentFailed` for `LOAN_REPAYMENT`.

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=LoanDisbursementEndToEndTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policyloan/application/PaymentEventListener.java backend/src/test/java/tz/co/nlolo/lifeplatform/policyloan/LoanDisbursementEndToEndTest.java
git commit -m "feat(loans): confirmed loan money from payment repays the loan"
```

---

### Task 4: Ask for a loan repayment by mobile money; remove the direct repayment endpoint

**Files:**
- Modify: `policyloan/api/PolicyLoanApi.java`, `policyloan/application/PolicyLoanApiImpl.java`
- Modify: `policyloan/infrastructure/PolicyLoanController.java:110-121`
- Create: `policyloan/infrastructure/LoanPaymentRequestDto.java`
- Delete: `policyloan/infrastructure/RepaymentRequestDto.java` (check `grep -rn RepaymentRequestDto backend/src` first; delete only if nothing else uses it)
- Modify: `backend/api/openapi/openapi-policyloan.yaml` (`/loans/{loanId}/repayments` → `/loans/{loanId}/payment-request`)
- Test: `OptimisticLockingConflictContractTest.java:100-170`, `policyloan/PolicyLoanContractTest.java`, `policyloan/PolicyLoanControllerValidationContractTest.java`

**Interfaces:**
- Produces:
  ```java
  /** Ask the payer's phone for a repayment. Nothing is applied until payment confirms it (Task 3). */
  void requestRepayment(UUID loanId, String payerRef, BigDecimal amount, String currency, String idempotencyKey);
  ```
- Produces event `policyloan.RepaymentRequested` payload: `loanId` (UUID), `policyNumber`, `payerRef`, `amount` ({amount, currencyCode}), `idempotencyKey`.
- Produces HTTP `POST /loans/{loanId}/payment-request`, header `Idempotency-Key` (required), body `{"payerRef": string, "amount": Money}`, `202` with no body; `409` when the loan is not DISBURSED/REPAYING or the amount is above what it owes.

- [ ] **Step 1: Write the failing contract test**

Retarget the two tests in `OptimisticLockingConflictContractTest` (lines 100-170) from `/loans/{loanId}/repayments` to the new endpoint, stubbing `requestRepayment` instead of `recordRepayment`:

```java
doThrow(new ObjectOptimisticLockingFailureException("policyloan.domain.PolicyLoan", loanId))
    .when(policyLoanApi).requestRepayment(any(), anyString(), any(), anyString(), anyString());

mockMvc.perform(post("/loans/{loanId}/payment-request", loanId)
        .with(staff())
        .header("Idempotency-Key", "k-1")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"payerRef":"255754000001","amount":{"amount":"25000.00","currencyCode":"TZS"}}"""))
    .andExpect(status().isConflict())
    // ...the existing problem+json and OpenAPI assertions unchanged
```

(the control test the same way with `IllegalStateException`, expecting 500). In `PolicyLoanContractTest` and `PolicyLoanControllerValidationContractTest`, replace each `/repayments` case with the same case against `/payment-request`, and add:

```java
@Test
void theOldDirectRepaymentEndpointIsGone() throws Exception {
    mockMvc.perform(post("/loans/{loanId}/repayments", UUID.randomUUID()).with(staff())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":{\"amount\":\"1.00\",\"currencyCode\":\"TZS\"},\"paymentReference\":\"x\"}"))
        .andExpect(status().isNotFound());
}

@Test
void aCustomerMayNotAskForAnotherPolicysLoan() throws Exception { /* copy the existing customer-ownership case for
   the old endpoint, against /payment-request; expect 404 as it does */ }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=OptimisticLockingConflictContractTest,PolicyLoanContractTest,PolicyLoanControllerValidationContractTest`
Expected: FAIL (404 on `/payment-request`, `requestRepayment` missing).

- [ ] **Step 3: Implement**

`LoanPaymentRequestDto.java`:

```java
package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Ask the payer's phone for a loan repayment (money movement step 1): the number, and how much. */
public record LoanPaymentRequestDto(@NotBlank String payerRef, @NotNull @Valid MoneyDto amount) {}
```

(use the module's existing `MoneyDto`; if policyloan has none, use the one `RepaymentRequestDto` imports.)

`PolicyLoanApiImpl.requestRepayment`:

```java
@Override
@Transactional
public void requestRepayment(UUID loanId, String payerRef, BigDecimal amount, String currency, String idempotencyKey) {
    UUID tenantId = TenantContext.get();
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
        throw new IllegalArgumentException("Idempotency-Key is required: the same key is the same attempt");
    }
    PolicyLoan loan = findLoanOrThrow(loanId, tenantId);
    if (!"DISBURSED".equals(loan.getStatus()) && !"REPAYING".equals(loan.getStatus())) {
        throw new LoanNotEligibleException("Loan " + loanId + " owes nothing to repay (" + loan.getStatus() + ")");
    }
    BigDecimal owed = computeOutstandingBalance(loan).max(BigDecimal.ZERO);
    if (amount.signum() <= 0 || amount.compareTo(owed) > 0) {
        throw new LoanNotEligibleException("Ask for between 0.01 and " + owed.toPlainString() + " " + currency
            + ", what the loan owes now");
    }
    eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.RepaymentRequested", tenantId,
        Map.of("loanId", loanId, "policyNumber", loan.getPolicyNumber(), "payerRef", payerRef,
               "amount", Map.of("amount", amount.setScale(2).toPlainString(), "currencyCode", currency),
               "idempotencyKey", idempotencyKey)));
}
```

`LoanNotEligibleException` already maps to 409 (it is what the old endpoint threw); confirm in `PolicyLoanExceptionHandler`.

In `PolicyLoanController`, replace the `@PostMapping("/loans/{loanId}/repayments")` method with:

```java
@PostMapping("/loans/{loanId}/payment-request")
// Money movement step 1 (2026-11-04): a repayment is money in like a premium -- asked of the payer's phone here,
// recorded by hand as a deposit For: Loan. Neither counts until it is confirmed. Same callers as an invoice's request.
@PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or @access.canAny('policies.service', 'collections.manage')")
public ResponseEntity<Void> requestRepayment(@PathVariable UUID loanId, @Valid @RequestBody LoanPaymentRequestDto request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
    LoanView existing = policyLoanApi.getLoan(loanId);
    enforceCustomerOwnPolicyOnly(existing.policyNumber(), jwt, authentication);
    AgentBook.require(policyApi, existing.policyNumber(), jwt, authentication);
    policyLoanApi.requestRepayment(loanId, request.payerRef().trim(), new BigDecimal(request.amount().amount()),
        request.amount().currencyCode(), idempotencyKey);
    return ResponseEntity.accepted().build();
}
```

In `openapi-policyloan.yaml`, replace the `/loans/{loanId}/repayments` path item with:

```yaml
  /loans/{loanId}/payment-request:
    post:
      summary: Ask the payer's phone for a loan repayment
      description: >
        Money movement step 1 (2026-11-04). Nothing is applied until payment confirms the money. A repayment recorded by
        hand is a deposit For: Loan (POST /receipts with loanId).
      security:
        - customersAuth: [profile]
        - agentsAuth: [agent]
        - staffAuth: []
      parameters:
        - name: loanId
          in: path
          required: true
          schema: { type: string, format: uuid }
        - $ref: 'openapi-common.yaml#/components/parameters/IdempotencyKey'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              additionalProperties: false
              required: [payerRef, amount]
              properties:
                payerRef: { type: string, minLength: 1, description: The payer's mobile-money number. }
                amount: { $ref: 'openapi-common.yaml#/components/schemas/Money' }
      responses:
        '202': { description: Asked; nothing is applied until the money is confirmed }
        '403': { $ref: 'openapi-common.yaml#/components/responses/Forbidden' }
        '404': { $ref: 'openapi-common.yaml#/components/responses/NotFound' }
        '409': { $ref: 'openapi-common.yaml#/components/responses/Conflict' }
```

- [ ] **Step 4: Run to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=OptimisticLockingConflictContractTest,PolicyLoanContractTest,PolicyLoanControllerValidationContractTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policyloan backend/api/openapi/openapi-policyloan.yaml backend/src/test/java/tz/co/nlolo/lifeplatform
git commit -m "feat(loans): ask for a loan repayment by mobile money; nobody books one by typing it in"
```

---

### Task 5: Payment turns a loan repayment request into a collection

**Files:**
- Modify: `payment/application/PaymentRequestListener.java:117-132` (switch) and add a handler beside `handleTopUpCollection` (line 263)
- Test: `payment/PaymentRequestListenerIntegrationTest.java`

**Interfaces:**
- Consumes: `policyloan.RepaymentRequested` (Task 4).
- Produces: a `payment_transaction` with purpose `LOAN_REPAYMENT`, `source_ref` = loan id, `policy_number` from the payload; it awaits approval like every accepted mobile-money request.

- [ ] **Step 1: Write the failing test**

In `PaymentRequestListenerIntegrationTest`, copy the existing premium-collection test (the one publishing `billing.PaymentRequested`) and change it to:

```java
@Test
void aLoanRepaymentRequestBecomesALoanRepaymentCollection() {
    UUID loanId = UUID.randomUUID();
    publishAfterCommit("policyloan.RepaymentRequested", Map.of("loanId", loanId, "policyNumber", "POL-LOAN-1",
        "payerRef", "255754000001", "amount", Map.of("amount", "100000.00", "currencyCode", "TZS"),
        "idempotencyKey", "loan-req-1"));

    TenantContext.set(tenantId);
    PaymentTransaction t = transactions.findByTenantIdAndIdempotencyKey(tenantId, "loan-req-1").orElseThrow();
    assertThat(t.getPurpose()).isEqualTo("LOAN_REPAYMENT");
    assertThat(t.getSourceRef()).isEqualTo(loanId.toString());
    assertThat(t.getPolicyNumber()).isEqualTo("POL-LOAN-1");
}
```

Use whichever finder the premium test uses to read the row back (add `findByTenantIdAndIdempotencyKey` to `PaymentTransactionRepository` only if no equivalent exists).

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=PaymentRequestListenerIntegrationTest`
Expected: FAIL — no row.

- [ ] **Step 3: Implement**

In the switch, beside the top-up cases:

```java
// A loan repayment asked of the payer's phone (money movement step 1): a collection under its own purpose, so only
// policyloan takes the confirmation.
case "policyloan.RepaymentRequested" -> withTenant(envelope, this::handleLoanRepaymentCollection);
```

and the handler:

```java
/** A loan repayment: a collection under LOAN_REPAYMENT, its source the loan. Same two phases as a premium. */
private void handleLoanRepaymentCollection(Map<String, Object> payload) {
    UUID tenantId = TenantContext.get();
    String idempotencyKey = requireKey(payload);
    String loanId = String.valueOf(payload.get("loanId"));
    String payerRef = (String) payload.get("payerRef");
    Money money = money(payload);
    Optional<UUID> transactionId = requiresNewTransactionTemplate.execute(status -> paymentApiImpl.recordCollectionRequest(
        tenantId, idempotencyKey, payerRef, money.amount(), money.currency(), loanId, "LOAN_REPAYMENT"));
    transactionId.ifPresentOrElse(
        id -> submitCollection(tenantId, id, payerRef, money),
        () -> log.info("Dropping duplicate RepaymentRequested for tenant {} key {}", tenantId, idempotencyKey));
}
```

`withTenant` already sets `RequestPolicy` from the payload's `policyNumber` (line 143), so the row records the policy.

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=PaymentRequestListenerIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/payment/application/PaymentRequestListener.java backend/src/test/java/tz/co/nlolo/lifeplatform/payment/PaymentRequestListenerIntegrationTest.java
git commit -m "feat(payment): a loan repayment request is a collection for the loan"
```

---

### Task 6: A deposit can be for a loan

**Files:**
- Modify: `payment/package-info.java` (add `"policyloan::api"` to `allowedDependencies`)
- Modify: `payment/domain/PaymentTransaction.java:165-180` (factory for a loan)
- Modify: `payment/application/Receipts.java` (`NewReceipt`, `record`, `view`)
- Modify: `payment/api/ReceiptView.java`, `payment/infrastructure/ReceiptController.java`
- Modify: `backend/api/openapi/openapi-payment.yaml` (`RecordReceipt`, `Receipt`)
- Test: `payment/PaymentContractTest.java`, and a new `payment/LoanDepositIntegrationTest.java` (copy the Testcontainers header and migration list from `finaccounting/ReinsuranceAndLoanPostingEndToEndTest.java`, including V22)

**Interfaces:**
- Consumes: `PolicyLoanApi.getLoan(UUID)` → `LoanView(…, outstandingBalance, outstandingCurrency, …, status)`.
- Produces:
  ```java
  public record NewReceipt(UUID invoiceId, UUID loanId, ReceiptMethod method, String reference, BigDecimal amount,
                           LocalDate receivedOn, String payerRef, String note, UUID fieldReceiptId) {}
  public record ReceiptView(UUID receiptId, String purpose, String method, String reference, String payerRef,
                            BigDecimal amount, String currency, UUID invoiceId, UUID loanId, String policyNumber,
                            LocalDate receivedOn, String note, UUID fieldReceiptId, String status, String recordedBy,
                            Instant recordedAt, String decidedBy, Instant decidedAt, String decisionReason) {}
  public static PaymentTransaction manualLoanReceipt(UUID tenantId, String idempotencyKey, String method,
      String reference, String payerRef, BigDecimal amount, String currency, UUID loanId, String policyNumber,
      LocalDate receivedOn, String note, String recordedBy)
  ```
- Produces HTTP: `RecordReceipt` gains optional `loanId`; exactly one of `invoiceId`/`loanId`. `Receipt` gains `purpose` (`PREMIUM` | `LOAN_REPAYMENT`) and `loanId`; `invoiceId` becomes nullable.

- [ ] **Step 1: Write the failing tests**

`LoanDepositIntegrationTest`:

```java
@Test
void aCashDepositForALoanRepaysItOnlyOnceASecondPersonConfirmsIt() {
    LoanView loan = disbursed500kLoan();   // copy the origination + disbursement steps from ReinsuranceAndLoanPostingEndToEndTest
    TenantContext.set(tenantId);
    ReceiptView recorded = receipts.record(new Receipts.NewReceipt(null, loan.loanId(), ReceiptMethod.CASH, "CR-9001",
        new BigDecimal("100000"), LocalDate.now(), "Asha", null, null), "finance-1");

    assertThat(recorded.purpose()).isEqualTo("LOAN_REPAYMENT");
    assertThat(recorded.loanId()).isEqualTo(loan.loanId());
    assertThat(policyLoanApi.getLoan(loan.loanId()).status()).as("recorded is not paid").isEqualTo(LoanStatus.DISBURSED);

    TenantContext.set(tenantId);
    receipts.approve(recorded.receiptId(), "finance-2");

    TenantContext.set(tenantId);
    assertThat(policyLoanApi.getLoan(loan.loanId()).status()).isEqualTo(LoanStatus.REPAYING);
}

@Test
void aDepositForMoreThanTheLoanOwesIsRefused() {
    LoanView loan = disbursed500kLoan();
    TenantContext.set(tenantId);
    assertThatThrownBy(() -> receipts.record(new Receipts.NewReceipt(null, loan.loanId(), ReceiptMethod.CASH, "CR-9002",
        new BigDecimal("600000"), LocalDate.now(), null, null, null), "finance-1"))
        .isInstanceOf(ReceiptRefusedException.class)
        .hasMessageContaining("500000");
}

@Test
void aDepositNamingBothAnInvoiceAndALoanIsRefused() {
    TenantContext.set(tenantId);
    assertThatThrownBy(() -> receipts.record(new Receipts.NewReceipt(UUID.randomUUID(), UUID.randomUUID(),
        ReceiptMethod.CASH, "CR-9003", BigDecimal.TEN, LocalDate.now(), null, null, null), "finance-1"))
        .isInstanceOf(ReceiptRefusedException.class);
}
```

In `PaymentContractTest`, add a case posting `/receipts` with `loanId` and no `invoiceId`, stubbing `receipts.record` to return a `ReceiptView` with `purpose` `LOAN_REPAYMENT`, and asserting 201 and `OpenApiValidationMatchers.openApi().isValid(PAYMENT_SPEC)`. Update every existing `new ReceiptView(...)` and `new NewReceipt(...)` in tests to the new argument order (`grep -rn "new ReceiptView(\|new Receipts.NewReceipt(\|new NewReceipt(" backend/src/test`).

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=LoanDepositIntegrationTest,PaymentContractTest`
Expected: compilation failure on the new record shapes.

- [ ] **Step 3: Implement**

`payment/package-info.java`: `allowedDependencies = { "refdata::api", "approvals::api", "billing::api", "policyloan::api" }`. (policyloan depends only on policy and refdata, so there is no cycle.)

`PaymentTransaction`, beside `manualReceipt`:

```java
/** A deposit For: Loan (money movement step 1): recorded by hand against a loan, confirmed by a second person. */
public static PaymentTransaction manualLoanReceipt(UUID tenantId, String idempotencyKey, String method, String reference,
                                                   String payerRef, java.math.BigDecimal amount, String currency,
                                                   UUID loanId, String policyNumber, java.time.LocalDate receivedOn,
                                                   String note, String recordedBy) {
    PaymentTransaction t = new PaymentTransaction(tenantId, idempotencyKey, payerRef, amount, currency,
        loanId.toString(), "LOAN_REPAYMENT");
    t.method = method;
    t.gatewayReference = reference;
    t.policyNumber = policyNumber;
    t.receivedOn = receivedOn;
    t.note = note;
    t.recordedBy = recordedBy;
    t.status = "AWAITING_APPROVAL";
    return t;
}
```

`Receipts`:
- Inject `PolicyLoanApi loans` through the constructor beside `BillingApi billing`.
- `NewReceipt` as in Interfaces.
- In `record`, after the method/reference/amount/date checks and before reading the invoice:

```java
if ((in.invoiceId() == null) == (in.loanId() == null)) {
    throw new ReceiptRefusedException("Say what this pays: an invoice or a loan, one of the two");
}
if (in.loanId() != null) {
    return recordForLoan(in, reference, recordedBy, tenantId);
}
```

and:

```java
/** A deposit For: Loan: capped at what the loan owes, less what is already waiting on it. */
private ReceiptView recordForLoan(NewReceipt in, String reference, String recordedBy, UUID tenantId) {
    LoanView loan = loans.getLoan(in.loanId());
    if (loan.status() != LoanStatus.DISBURSED && loan.status() != LoanStatus.REPAYING) {
        throw new ReceiptRefusedException("This loan is " + loan.status().name().toLowerCase()
            + ": there is nothing to repay on it");
    }
    BigDecimal waiting = transactions.findByTenantIdAndSourceRefAndStatusOrderByCreatedAtAsc(tenantId,
            in.loanId().toString(), "AWAITING_APPROVAL").stream()
        .map(PaymentTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal room = loan.outstandingBalance().subtract(waiting);
    if (in.amount().compareTo(room) > 0) {
        throw new ReceiptRefusedException("The loan owes " + loan.outstandingBalance().toPlainString() + " "
            + loan.outstandingCurrency() + (waiting.signum() > 0 ? ", and " + waiting.toPlainString()
            + " is already waiting to be confirmed" : "") + ". Record at most " + room.max(BigDecimal.ZERO).toPlainString());
    }
    if (transactions.existsByTenantIdAndMethodAndGatewayReferenceAndStatusNot(tenantId, in.method().name(),
            reference, REJECTED)) {
        throw new ReceiptRefusedException("A receipt with this " + words(in.method()) + " reference (" + reference
            + ") is already recorded");
    }
    PaymentTransaction receipt = PaymentTransaction.manualLoanReceipt(tenantId, "receipt:" + UUID.randomUUID(),
        in.method().name(), reference, blankToNull(in.payerRef()) == null ? "—" : in.payerRef().trim(), in.amount(),
        loan.outstandingCurrency(), in.loanId(), loan.policyNumber(), in.receivedOn(), blankToNull(in.note()),
        recordedBy);
    transactions.save(receipt);
    return view(receipt);
}
```

- `approve` is unchanged: it already publishes `PaymentConfirmed` with `purpose`, `sourceRef`, `method` and `approvedBy`, which Task 3 consumes.
- `view(t)`:

```java
static ReceiptView view(PaymentTransaction t) {
    boolean loan = "LOAN_REPAYMENT".equals(t.getPurpose());
    UUID source = UUID.fromString(t.getSourceRef());
    return new ReceiptView(t.getPaymentTransactionId(), t.getPurpose(), t.getMethod(), t.getGatewayReference(),
        t.getPayerRef(), t.getAmount(), t.getCurrency(), loan ? null : source, loan ? source : null,
        t.getPolicyNumber(), t.getReceivedOn(), t.getNote(), t.getFieldReceiptId(), t.getStatus(), t.getRecordedBy(),
        t.getCreatedAt(), t.getDecidedBy(), t.getDecidedAt(), t.getDecisionReason());
}
```

`ReceiptView` as in Interfaces (update its javadoc: CONFIRMED "once it pays the invoice or the loan").

`ReceiptController`: `RecordReceiptRequest` gets `UUID invoiceId` (no `@NotNull`) and `UUID loanId`; pass both into `NewReceipt`. `ReceiptResponse` gains `String purpose` and `UUID loanId` (mapped from the view).

`openapi-payment.yaml`:
- `RecordReceipt.required`: `[method, reference, amount, receivedOn]`; properties gain `loanId: { type: [string, 'null'], format: uuid, description: The loan this repays; give this or invoiceId, not both. }`, and `invoiceId` becomes `{ type: [string, 'null'], format: uuid, description: The invoice this pays; give this or loanId, not both. }`.
- `Receipt.required`: add `purpose`, remove `invoiceId`; properties gain `purpose: { type: string, enum: [PREMIUM, LOAN_REPAYMENT] }` and `loanId: { type: [string, 'null'], format: uuid }`; `invoiceId` becomes `{ type: [string, 'null'], format: uuid }`.
- Do not add `default:` to any of these (openapi-typescript makes a defaulted field required).

- [ ] **Step 4: Run to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=LoanDepositIntegrationTest,PaymentContractTest,PolicyLoanApiIntegrationTest`
Expected: PASS. Then `./mvnw -q test -Dtest=ModularityTests` — expected PASS with the new dependency.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/payment backend/api/openapi/openapi-payment.yaml backend/src/test/java/tz/co/nlolo/lifeplatform/payment
git commit -m "feat(payment): a deposit can repay a loan, confirmed by a second person and capped at what it owes"
```

---

### Task 7: The ledger posts loan money to the account it reached, and holds a surplus

**Files:**
- Modify: `resources/finaccounting/posting-rules.yaml:527-535` (rule E-03) and add rule `E-03-SURPLUS`
- Modify: `finaccounting/application/PostingFactsExtractor.java:60` (shapes), `:161` (switch), `:279-287` (`loanRepaid`)
- Modify: `finaccounting/infrastructure/JournalWords.java:38`
- Test: `finaccounting/ReinsuranceAndLoanPostingEndToEndTest.java`, `finaccounting/application/PostingFactsExtractorTest.java`

**Interfaces:**
- Consumes: `policyloan.LoanRepaid.receiptMethod`, `policyloan.LoanOverpaid` (Task 2).
- Produces: E-03 debits `RECEIPT` (1120 for CASH, BANK_DEPOSIT, CHEQUE, TANQR; 1140 otherwise, via `PostingRuleSet.receiptAccount`). `E-03-SURPLUS`: Dr RECEIPT / Cr 2128, sourceRef `loan-surplus:{paymentId}`.

- [ ] **Step 1: Write the failing tests**

In `ReinsuranceAndLoanPostingEndToEndTest`, change the two repayment calls (now `applyConfirmedRepayment`, Task 2) to pass `"CASH"` as the method and update the leg assertions in the loop (lines ~675-683) from `"1140"` to `"1120"`:

```java
assertThat(repaidLegs).extracting(GlPosting::getAccountCode).containsExactlyInAnyOrder("1120", "2125");
assertThat(legFor(repaidLegs, "1120").getDirection()).isEqualTo(PostingDirection.DR);
```

Add after the settled assertion:

```java
// A further repayment on top of the settled loan's last one: only the surplus posts, to 2128, from the account it reached.
@Test
void aSurplusOnALoanRepaymentPostsToPremiumsReceivedInAdvanceFromTheAccountItReached() {
    LoanView loan = disbursedLoan(new BigDecimal("500000"));   // extract the origination + disbursement block above
    UUID paymentId = UUID.randomUUID();
    TenantContext.set(tenantId);
    policyLoanApi.applyConfirmedRepayment(loan.loanId(), new BigDecimal("650000"), CURRENCY, "SLIP-S", "BANK_DEPOSIT",
        paymentId, "finance-1");

    JournalEntry surplus = entriesFor(tenantId, "policyloan.LoanOverpaid").get(0);
    assertThat(surplus.getSourceRef()).isEqualTo("loan-surplus:" + paymentId);
    List<GlPosting> legs = legsFor(tenantId, surplus);
    assertThat(legs).extracting(GlPosting::getAccountCode).containsExactlyInAnyOrder("1120", "2128");
    assertThat(legFor(legs, "2128").getDirection()).isEqualTo(PostingDirection.CR);
    assertThat(legFor(legs, "2128").getAmount()).isEqualByComparingTo("150000");
}
```

(`entriesFor(tenantId, sourceEvent)` generalises the existing `repaidEntriesFor`; add it.)

In `PostingFactsExtractorTest`, add a case that a `policyloan.LoanOverpaid` payload yields facts with `sourceRef` `loan-surplus:{paymentId}`, amount 150000.00 and attribute `receiptMethod` `BANK_DEPOSIT`, and that `LoanRepaid` facts carry `receiptMethod`.

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=ReinsuranceAndLoanPostingEndToEndTest,PostingFactsExtractorTest`
Expected: FAIL — legs on 1140; no `LoanOverpaid` entry.

- [ ] **Step 3: Implement**

`posting-rules.yaml`, E-03 and a new rule after it:

```yaml
  - id: E-03
    event: policyloan.LoanRepaid
    models: [ANY]
    description: Loan repayment -- interest accrued first, then principal; to the account the money reached (step 1, 2026-11-04)
    lines:
      - {dr: "RECEIPT", amount: amount}
      - {cr: "2126", amount: interest}
      - {cr: "2125", amount: principal}

  - id: E-03-SURPLUS
    event: policyloan.LoanOverpaid
    models: [ANY]
    description: Money above what a loan owed -- the payer's, held as received in advance until it is applied or paid back
    lines:
      - {dr: "RECEIPT", amount: amount}
      - {cr: "2128", amount: amount}
```

`PostingFactsExtractor`:
- Shapes map: `Map.entry("policyloan.LoanRepaid", new EventShape(Set.of(AMOUNT, "interest", "principal"), Set.of("receiptMethod"))),` and `Map.entry("policyloan.LoanOverpaid", new EventShape(Set.of(AMOUNT), Set.of("receiptMethod"))),` (match how `PremiumCollected`'s optional attribute is declared; if `EventShape`'s second set means required attributes, declare `receiptMethod` as optional the way `billing.PremiumCollected` does).
- Switch: `case "policyloan.LoanOverpaid" -> List.of(loanSurplus(type, p, policy, today));`
- `loanRepaid`: build its attributes as `Map<String, String> attrs = new HashMap<>(Map.of("refType", "LOAN")); putIfPresent(attrs, "receiptMethod", p.get("receiptMethod"));` and pass `attrs` to `money(...)`.
- New:

```java
/** Money above what a loan owed (money movement step 1): keyed on the payment, so a redelivery posts once. */
private static PostingFacts loanSurplus(String type, Map<String, Object> p, String policy, LocalDate today) {
    Map<String, String> attrs = new HashMap<>(Map.of("refType", "LOAN"));
    putIfPresent(attrs, "receiptMethod", p.get("receiptMethod"));
    return money(type, "loan-surplus:" + string(p.get("paymentId")), policy, p.get("amount"), today, attrs);
}
```

`JournalWords`: add `Map.entry("policyloan.LoanOverpaid", "Loan overpaid -- held for the payer"),`.

- [ ] **Step 4: Run to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=ReinsuranceAndLoanPostingEndToEndTest,PostingFactsExtractorTest,PostingEngineIntegrationTest,FinaccountingContractTest`
Expected: PASS (the last two load `posting-rules.yaml` and fail on a rule the validator rejects).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/finaccounting/posting-rules.yaml backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting
git commit -m "feat(ledger): loan repayments post to the account the money reached; a surplus is held in 2128"
```

---

### Task 8: The deposit page can record money for a loan

**Files:**
- Regenerate: `frontend/src/types/api/payment.ts`, `frontend/src/types/api/policyloan.ts` (`cd frontend && npm run generate:api`)
- Modify: `frontend/src/features/receipts/receiptForm.ts`, `NewReceiptPage.tsx`, `ReceiptPage.tsx`
- Modify: `frontend/src/api/policies.ts` (remove `recordLoanRepayment`; add `requestLoanPayment`), `frontend/src/api/types.ts:202-205` (drop `LoanRepaymentRequest`)
- Test: `frontend/src/features/receipts/receiptForm.test.ts`, `ReceiptPage.test.tsx`, and a new `NewReceiptPage.test.tsx` case

**Interfaces:**
- Consumes: HTTP from Tasks 4 and 6.
- Produces: URL params on `/staff/receipts/new`: `for=LOAN&loan={loanId}&policy={n}&from=policy`. `receiptFormSchema` gains `paysFor: 'PREMIUM' | 'LOAN'` and `loanId: string`.
- Produces: `requestLoanPayment(loanId: string, body: { payerRef: string; amount: Money }, idempotencyKey: string): Promise<void>` in `api/policies.ts`.

UI rules for this task (DESIGN.md / PLAN.md): same `FormLayout` page, same section names; the "For" choice is a `Select` like "How the money came in"; the amount hint states the loan's server balance (`At most {formatMoney(loan.outstandingBalance)}, what the loan owes.`), never a browser-computed figure; Primary submit stays "Record receipt" in this step (step 6 renames every money word at once).

- [ ] **Step 1: Write the failing tests**

`receiptForm.test.ts`:

```ts
it('a loan deposit needs the loan, not an invoice', () => {
  const parsed = receiptFormSchema.safeParse({ ...blankReceiptForm({ policyNumber: 'POL-1', paysFor: 'LOAN' }),
    reference: 'CR-1', amount: '1000' });
  expect(parsed.success).toBe(false);
  expect(parsed.error?.issues.map((i) => i.path[0])).toContain('loanId');
});

it('a loan deposit goes out with loanId and no invoiceId', () => {
  const body = toApiRequest({ ...blankReceiptForm({ policyNumber: 'POL-1', paysFor: 'LOAN', loanId: 'L-1' }),
    reference: 'CR-1', amount: '1000' }, 'TZS');
  expect(body.loanId).toBe('L-1');
  expect(body.invoiceId).toBeNull();
});
```

`ReceiptPage.test.tsx`: a receipt with `purpose: 'LOAN_REPAYMENT'`, `loanId: 'L-1'`, `invoiceId: null` renders a `Loan` field and no `Invoice` field, and the confirm panel's subtitle reads "Only a confirmed receipt repays the loan."

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx vitest run src/features/receipts`
Expected: FAIL.

- [ ] **Step 3: Implement**

`receiptForm.ts`:
- Schema: add `paysFor: z.enum(['PREMIUM', 'LOAN'])`, `loanId: z.string()`, make `invoiceId: z.string()`, and add a `.superRefine((v, ctx) => { if (v.paysFor === 'PREMIUM' && !v.invoiceId) ctx.addIssue({ code: 'custom', path: ['invoiceId'], message: 'Choose the invoice this pays' }); if (v.paysFor === 'LOAN' && !v.loanId) ctx.addIssue({ code: 'custom', path: ['loanId'], message: 'Choose the loan this repays' }); })`.
- `blankReceiptForm`: defaults `paysFor: 'PREMIUM'`, `loanId: ''`.
- `toApiRequest`: `invoiceId: values.paysFor === 'PREMIUM' ? values.invoiceId : null`, `loanId: values.paysFor === 'LOAN' ? values.loanId : null`.
- Doc comment on the schema: "Mirrors Receipts.record: an invoice or a loan (one of the two), a method and its reference, …".

`NewReceiptPage.tsx`:
- Prefill reads `paysFor: params.get('for') === 'LOAN' ? 'LOAN' : 'PREMIUM'` and `loanId: params.get('loan') ?? ''`.
- In the "What it pays" section, after the policy number row, a `FormField label="For"` with a `Select {...register('paysFor')}` offering `Premium` and `Loan repayment`.
- When `paysFor === 'LOAN'`: read the policy's loans with `listLoans(policyNumber)` (the function `LoansPanel` uses), keep those with status `DISBURSED` or `REPAYING`, and render `FormField label="Loan it repays"` with options `{loanId.slice(0, 8)} · {formatMoney(outstandingBalance)} owed`; if none, the warning line "No loan on {policy} has anything to repay." When `paysFor === 'PREMIUM'`, the existing invoice lookup and select, unchanged.
- Amount label currency and hint follow the chosen loan (`outstandingCurrency`, `outstandingBalance`) when `paysFor === 'LOAN'`.
- Page `description`: "Premium or a loan repayment paid by cash, bank deposit, cheque, Lipa number or TANQR. It counts once a second finance person confirms it."

`ReceiptPage.tsx`: in "This receipt", show `Field label="Loan"` (the first 8 characters, linking to the policy's Loans tab `/staff/policies/{n}?tab=loans`) when `receipt.loanId`, else the existing invoice/policy fields; the confirm panel subtitle is "Only a confirmed receipt repays the loan." for a loan and the existing text otherwise.

`api/policies.ts`: delete `recordLoanRepayment` and its doc block; add

```ts
/** `POST /loans/{loanId}/payment-request` -- ask the payer's phone for a loan repayment (money movement step 1).
 *  202 with no body: nothing is applied until finance confirms the money. */
export function requestLoanPayment(loanId: string, body: { payerRef: string; amount: Money }, idempotencyKey: string) {
  return post<void>(`/loans/${encodeURIComponent(loanId)}/payment-request`, body, { idempotencyKey });
}
```

(match how `requestInvoicePayment` passes its Idempotency-Key in this file.)

- [ ] **Step 4: Run to verify they pass**

Run: `cd frontend && npx vitest run src/features/receipts && npx tsc --noEmit && npx eslint src/features/receipts src/api`
Expected: PASS, no type or lint errors (Task 9 removes the remaining `recordLoanRepayment` users; if `tsc` flags `LoansPanel`/`policyStore` here, do Task 9 before committing both).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/types/api frontend/src/api frontend/src/features/receipts
git commit -m "feat(console): record a deposit for a loan, confirmed by a second person"
```

---

### Task 9: The Loans tab asks for money instead of booking it

**Files:**
- Modify: `frontend/src/features/policies/LoansPanel.tsx:150-330` (`LoanRow`, delete `RepaymentForm`)
- Create: `frontend/src/features/policies/loanPaymentForm.ts`
- Delete: `frontend/src/features/policies/loanRepaymentForm.ts`
- Modify: `frontend/src/store/policyStore.ts` (replace `recordLoanRepayment`/`repayingLoan` with `requestLoanPayment`/`requestingLoanPayment`)
- Modify: `frontend/src/features/policies/PolicyDetailPage.tsx:523` (`canChange` for repayments)
- Test: `frontend/src/features/policies/LoansPanel.test.tsx`

**Interfaces:**
- Consumes: `requestLoanPayment` (Task 8); route `/staff/receipts/new?for=LOAN&loan=&policy=&from=policy` (Task 8).
- Produces: `LoansPanel` props `canRecordDeposit: boolean` (has `collections.manage`) and `canRequestPayment: boolean` (`collections.manage` or `policies.service`), replacing the repayment use of `canChange`; `canChange` keeps meaning "may take a loan" (`loans.manage`).

UI rules: inside the loan row, the two acts are compact ghost/outline buttons as the row's "Record repayment" is today (no Primary in a list row); "Record deposit" is a link to the full page (acting on a full page), the mobile-money request is the small inline form the invoice row already uses for "Request payment" (copy that markup and `requestPaymentForm.ts`), with a payer number and an amount prefilled with the loan's server `outstandingBalance.amount`.

- [ ] **Step 1: Write the failing tests**

`LoansPanel.test.tsx` (replace the repayment-form cases):

```tsx
it('offers a deposit and a mobile-money request on a repayable loan, never a typed repayment', () => {
  renderPanel({ loans: [repayableLoan], canRecordDeposit: true, canRequestPayment: true });
  expect(screen.queryByRole('button', { name: 'Record repayment' })).toBeNull();
  expect(screen.getByRole('link', { name: 'Record deposit' })).toHaveAttribute('href',
    `/staff/receipts/new?for=LOAN&loan=${repayableLoan.loanId}&policy=POL-1&from=policy`);
  expect(screen.getByRole('button', { name: 'Request mobile-money payment' })).toBeInTheDocument();
});

it('shows neither to someone without the collections permissions', () => {
  renderPanel({ loans: [repayableLoan], canRecordDeposit: false, canRequestPayment: false });
  expect(screen.queryByRole('link', { name: 'Record deposit' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Request mobile-money payment' })).toBeNull();
});

it('prefills the request with what the loan owes, as the server stated it', async () => {
  renderPanel({ loans: [repayableLoan], canRecordDeposit: true, canRequestPayment: true });
  await userEvent.click(screen.getByRole('button', { name: 'Request mobile-money payment' }));
  expect(screen.getByLabelText('Amount (TZS)')).toHaveValue(repayableLoan.outstandingBalance!.amount);
});
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx vitest run src/features/policies/LoansPanel.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implement**

`loanPaymentForm.ts`:

```ts
import { z } from 'zod';

/** Zod schema for `POST /loans/{loanId}/payment-request` (money movement step 1): the payer's number and how much.
 *  The amount defaults to what the loan owes as the server stated it; the server refuses more than that. */
export const loanPaymentFormSchema = z.object({
  payerRef: z.string().trim().min(1, 'The payer’s mobile-money number'),
  amount: z
    .string()
    .trim()
    .regex(/^\d+(\.\d{1,2})?$/, 'An amount like 120000 or 120000.50')
    .refine((v) => Number(v) > 0, 'More than zero'),
});

export type LoanPaymentFormValues = z.infer<typeof loanPaymentFormSchema>;

export function blankLoanPaymentForm(owed: string): LoanPaymentFormValues {
  return { payerRef: '', amount: owed };
}

export function toLoanPaymentRequest(values: LoanPaymentFormValues, currency: string) {
  return { payerRef: values.payerRef.trim(), amount: { amount: Number(values.amount).toFixed(2), currencyCode: currency } };
}
```

`policyStore.ts`: replace the `recordLoanRepayment` action and `repayingLoan` slot with `requestLoanPayment(policyNumber, loanId, values)` minting the Idempotency-Key once per submit inside the action (the pattern `requestPayment` for invoices uses in this store) and a keyed `requestingLoanPayment` slot; on success, reload the policy's loans as the old action did.

`LoansPanel.tsx` `LoanRow`: remove `repaying` state and the "Record repayment" button; when `repayable && loanId`, render:

```tsx
<div className="mt-1.5 flex flex-wrap gap-1.5">
  {canRecordDeposit && (
    <Button asChild size="sm" variant="ghost" className="-ml-2">
      <Link to={`/staff/receipts/new?for=LOAN&loan=${encodeURIComponent(loanId)}&policy=${encodeURIComponent(policyNumber)}&from=policy`}>
        Record deposit
      </Link>
    </Button>
  )}
  {canRequestPayment && !asking && (
    <Button size="sm" variant="ghost" onClick={() => setAsking(true)}>Request mobile-money payment</Button>
  )}
</div>
{asking && <LoanPaymentForm policyNumber={policyNumber} loan={loan} onDone={() => setAsking(false)} />}
```

`LoanPaymentForm`: the old `RepaymentForm` markup with fields `Payer's number` (`payerRef`) and `Amount ({loan.outstandingCurrency})` (`amount`, default `loan.outstandingBalance.amount`), the note "Nothing is repaid until the money is confirmed.", submit "Send request", cancel "Cancel". Delete `RepaymentForm` and the `loanRepaymentForm` imports.

`PolicyDetailPage.tsx:523`: pass `canRecordDeposit={isStaff && can('collections.manage')}` and `canRequestPayment={isStaff && (can('collections.manage') || can('policies.service'))}`; `canChange` stays `isStaff && can('loans.manage')` for taking a loan.

Delete `loanRepaymentForm.ts` and any test of it (`grep -rln loanRepaymentForm frontend/src`).

- [ ] **Step 4: Run to verify they pass**

Run: `cd frontend && npx vitest run src/features/policies && npx tsc --noEmit && npx eslint src/features/policies src/store`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A frontend/src/features/policies frontend/src/store/policyStore.ts
git commit -m "feat(console): the Loans tab asks for a repayment or records a deposit; nobody types one in"
```

---

### Task 10: E2E, docs, and the step's verification

**Files:**
- Modify: `frontend/e2e/staff-policy-loans.spec.ts:80-124`
- Modify: `backend/docs/05-event-catalog.md` (add `policyloan.RepaymentRequested`, `policyloan.LoanOverpaid`; note `receiptMethod` on `LoanRepaid`; `payment.PaymentConfirmed` purposes include `LOAN_REPAYMENT`)
- Modify: `backend/docs/04-api-contracts.md` (the `/loans/{loanId}/repayments` row → `/loans/{loanId}/payment-request`; `/receipts` takes `loanId`)

- [ ] **Step 1: Rewrite the repayment e2e cases**

Replace the tests at lines 80-124 with:

```ts
test('a repayable loan offers a deposit and a mobile-money request, never a typed repayment', async ({ page }) => {
  const panel = await openLoans(page);   // the helper this file already uses (lines 14-25)
  await expect(panel.getByRole('button', { name: 'Record repayment' })).toHaveCount(0);
  const repayable = panel.getByText(/^(DISBURSED|REPAYING)$/i);
  await expect(panel.getByRole('link', { name: 'Record deposit' })).toHaveCount(await repayable.count());
  await expect(panel.getByRole('button', { name: 'Request mobile-money payment' })).toHaveCount(await repayable.count());
});

test('recording a loan deposit opens the deposit page set to the loan', async ({ page }) => {
  const panel = await openLoans(page);
  const link = panel.getByRole('link', { name: 'Record deposit' }).first();
  test.skip((await link.count()) === 0, 'no repayable loan in this tenant');
  await link.click();
  await expect(page.getByRole('heading', { name: 'Record a receipt' })).toBeVisible();
  await expect(page.getByLabel('For')).toHaveValue('LOAN');
  await expect(page.getByLabel('Loan it repays')).toBeVisible();
});
```

Per the project's note on this spec, a skip on "no repayable loan in this tenant" is a data gap, not a pass: before the final run, seed one disbursed loan on the e2e policy with the existing dev seeding (`backend/scripts/seed-dev-data.sh`, loan section) and confirm the second test ran, not skipped.

- [ ] **Step 2: Run the e2e spec**

Run: `cd frontend && npx playwright test staff-policy-loans staff-receipts` (with deps, not `--no-deps`: a `--no-deps` run reuses stale auth).
Expected: all pass; the second loan test not skipped.

- [ ] **Step 3: Update docs**

Add the two events and the changed endpoint rows as listed under Files, in the existing table formats of each document.

- [ ] **Step 4: Verify the step**

Run, with the dev backend stopped first:

```bash
cd backend && ./mvnw -q clean test -Dtest='PolicyLoanApiIntegrationTest,LoanDisbursementEndToEndTest,LoanDepositIntegrationTest,PaymentRequestListenerIntegrationTest,PaymentContractTest,PolicyLoanContractTest,PolicyLoanControllerValidationContractTest,OptimisticLockingConflictContractTest,ReinsuranceAndLoanPostingEndToEndTest,PostingFactsExtractorTest,PostingEngineIntegrationTest,FinaccountingContractTest,ModularityTests'
cd ../frontend && npx vitest run && npx tsc --noEmit && npx eslint .
```

Expected: all green; then restart the dev backend. Compare the number of test classes Maven reports run against the thirteen named (a pattern that matched nothing passes vacuously).

- [ ] **Step 5: Commit**

```bash
git add frontend/e2e/staff-policy-loans.spec.ts backend/docs/05-event-catalog.md backend/docs/04-api-contracts.md
git commit -m "test(loans): e2e for repayments through deposits; docs for the new events and endpoint"
```

---

## Self-review against the spec

- §13 step 1 "Loan repayments through deposits; cap; RECEIPT by method; customer role off the direct endpoint": Tasks 2 (cap), 3 and 6 (confirmed money only), 7 (RECEIPT by method), 4 (direct endpoint removed; customer can only ask by mobile money for their own loan).
- D11 permissions: Task 6 (`/receipts` stays `collections.manage` / `collections.approve`, acting alone via `collections.approve-own` unchanged), Task 9 (UI gated on `collections.manage`).
- §4 "Loan owes 300,000; deposit 350,000": manual deposits are capped at record time (Task 6); a gateway or racing surplus is announced and posted to 2128 (Tasks 2, 7). Holding it on a policy balance is step 2's work; its opening-balance migration must include `policyloan.LoanOverpaid` amounts (carried into the step 2 plan).
- §8 `LoanRepaid` row: Task 7.
- Not in this step, by design: the policy balance, renaming money words (step 6; this step keeps "Record a receipt"), the new approvals for loan payouts (step 4).
