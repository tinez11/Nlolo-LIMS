# Product step 3 — the accumulation engine — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **This plan will be executed inline** (the user prefers direct implementation), so read it with that in mind.

**Goal:** A product version may be valued by an account ledger — contributions in, charges out, interest credited at `max(declared, guaranteed)` — owned by a new `accumulation` module whose entries are immutable and whose postings are exactly-once per source.

**Architecture:** A new `accumulation` module owns the account balance and every transaction that changes it. Other modules ask (by event or through `accumulation::api`); accumulation posts. `PolicyAccount.cashValueAmount` becomes a projection that only accumulation restates, so surrender quotes and loan limits work unchanged. Interest is derived by replaying entries day by day, compounding daily, posted monthly.

**Tech Stack:** Spring Boot 3, Spring Modulith (`module::api` named interfaces), Postgres 16 with RLS, Testcontainers, OpenAPI 3.1 with the atlassian validator, Apache PDFBox (new), React + Zustand + Vitest + Playwright.

**Spec:** `backend/docs/superpowers/specs/2026-10-01-product-step3-accumulation-design.md`. Its **§10 lists eleven revisions made while planning; this plan follows §10 where it differs from §1–9.**

## Global Constraints

- Do not start: step 2 (`product-step2-payouts`) must be merged to `main` first. Cut `product-step3-accumulation` from `main`.
- Money is `{amount: "<decimal string>", currencyCode}` on every wire and every event payload — never a JSON number.
- Every cross-module reaction is an AFTER_COMMIT `@TransactionalEventListener` on `DomainEventEnvelope`, in its own `REQUIRES_NEW` transaction, under the envelope's tenant, never rethrowing. Listener beans get an explicit, module-prefixed name (`@Component("accumulationPolicyEventListener")`) — several modules already declare a `PolicyEventListener`, and a duplicate default bean name fails startup.
- `accumulation` depends on `policy::api` and `product::api` only. `benefitpayout` gains `accumulation::api`. Nothing may depend on `benefitpayout` or `claims` from `accumulation` — those are consumed by envelope.
- Ledger entries and postings are **immutable**: `app_role` holds `SELECT, INSERT` only on both, and a trigger refuses `UPDATE`/`DELETE` for every role. Corrections are `REVERSAL` or `ADJUSTMENT` entries.
- One `(tenant_id, source_type, source_ref)` → at most one posting, enforced by a **unique index**, not by check-then-insert.
- `balance_after >= 0` is a CHECK. No account goes negative.
- Interest: effective annual rate; daily factor `(1 + r/100)^(1/daysInYear) − 1` using the actual days in that calendar year; compounds daily on principal **plus accrued-but-unposted interest**; one `INTEREST` entry per account per month, rounded once to 2 dp `HALF_EVEN`.
- RLS on every new table: `USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid)`.
- Cross-tenant sweeps read **ids only** through `SECURITY DEFINER` functions with `EXECUTE` revoked from `PUBLIC` and granted to `app_role`.
- Refusals that are a rule, not a malformed request, are `AccumulationStateException` → **422** `ACCUMULATION_REFUSED`, with the message shown verbatim by the console.
- `ddl-auto` is `none`: **any test class that applies product migrations and reaches code reading a new table must apply the new migration too.** Every task that adds a migration has a step that finds and updates those classes with a grep.
- Run only that task's test classes while building. Full suite and e2e only at the gate (Task 10). Never run Maven, vitest and Playwright concurrently, and never edit source files while a full Maven run is in progress.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

## File structure

**New module `backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/`**

| File | Responsibility |
|---|---|
| `package-info.java` | `@ApplicationModule(allowedDependencies = { "policy::api", "product::api" })` |
| `api/package-info.java` | `@NamedInterface("api")` |
| `api/AccumulationApi.java` | the published face |
| `api/AccountView.java`, `LedgerEntryView.java`, `StatementView.java`, `RateDeclarationView.java`, `WithdrawalRequestView.java` | read models |
| `api/EntryType.java`, `AccountStatus.java` | enums |
| `api/AccumulationStateException.java`, `AccountNotFoundException.java` | refusals |
| `domain/Account.java` | one account per policy; the running head (`last_seq`, `balance`) and `@Version` that serialises postings |
| `domain/Posting.java`, `domain/LedgerEntry.java` | insert-only rows |
| `domain/InterestCalculator.java` | pure: daily-compounding replay |
| `domain/RateDeclaration.java`, `WithdrawalRequest.java`, `TopUpRequest.java`, `Statement.java` | the other aggregates |
| `application/LedgerService.java` | the ONE place a posting is written |
| `application/AccumulationApiImpl.java` | everything else |
| `application/EnvelopeRunner.java` | tenant + REQUIRES_NEW + never-rethrow wrapper |
| `application/PolicyEventListener.java`, `ClaimEventListener.java`, `BillingEventListener.java`, `PaymentEventListener.java` | reactions |
| `application/MonthEndRun.java`, `AnnualStatementRun.java` | scheduled drains |
| `application/StatementPdf.java` | PDFBox rendering |
| `infrastructure/*Repository.java` | Spring Data |
| `infrastructure/AccumulationController.java`, `AccumulationExceptionHandler.java`, request/response records | REST |

**Migrations:** `db-migrations/product/V19__accumulation_terms.sql`, `db-migrations/accumulation/V1__create_accumulation_schema.sql`, `db-migrations/payment/V9__collection_purpose_and_withdrawals.sql`, `db-migrations/communication/V10__account_statement_template.sql`.

**Spec:** `api/openapi/openapi-accumulation.yaml` (new), `api/openapi/openapi-product.yaml`, `api/asyncapi-events.yaml`.

---

### Task 1: Product — the value basis, the guarantee and the charges table

**Files:**
- Create: `db-migrations/product/V19__accumulation_terms.sql`
- Create: `product/api/ValueBasis.java`, `product/api/AccumulationChargeRow.java`, `product/api/AccumulationPlan.java`
- Create: `product/domain/AccumulationPlanValidator.java`, `product/domain/VersionAccumulationTerms.java`, `product/domain/AccumulationCharge.java`
- Create: `product/infrastructure/VersionAccumulationTermsRepository.java`, `AccumulationChargeRepository.java`, `AccumulationRequest.java`
- Modify: `product/api/PayoutAmountBasis.java` (add `ACCOUNT_VALUE`), `product/domain/PayoutPlanValidator.java`, `product/api/ProductApi.java`, `product/application/ProductApiImpl.java`, `product/infrastructure/PublishVersionRequest.java`, `product/infrastructure/ProductController.java`, `api/openapi/openapi-product.yaml`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/product/AccumulationPlanValidatorTest.java`, extend `PayoutPlanValidatorTest`, extend `ProductContractTest`

**Interfaces:**
- Produces: `enum ValueBasis { SCALE, ACCOUNT }`; `record AccumulationChargeRow(int fromPolicyYear, Integer toPolicyYear, BigDecimal contributionAllocationPercent, BigDecimal transferAllocationPercent, BigDecimal monthlyPolicyFee)`; `record AccumulationPlan(ValueBasis basis, BigDecimal guaranteedRatePercent, BigDecimal minimumBalance, List<AccumulationChargeRow> charges)` with `static AccumulationPlan none()`, `boolean isAccount()`, `AccumulationChargeRow chargesFor(int policyYear)`; `ProductApi.resolveAccumulationPlan(UUID productVersionId) -> AccumulationPlan`; a 7th `publishVersion` overload ending `PayoutPlan payoutPlan, AccumulationPlan accumulationPlan, String publishedBy`; `PayoutAmountBasis.ACCOUNT_VALUE`.

- [ ] **Step 1: Write the failing validator test**

```java
// src/test/java/tz/co/nlolo/lifeplatform/product/AccumulationPlanValidatorTest.java
package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.AccumulationPlanValidator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AccumulationPlanValidator, rule by rule, in the exact words the console mirrors. */
class AccumulationPlanValidatorTest {

    private static AccumulationChargeRow row(int from, Integer to) {
        return new AccumulationChargeRow(from, to, new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("1000"));
    }

    private static AccumulationPlan account(List<AccumulationChargeRow> charges) {
        return new AccumulationPlan(ValueBasis.ACCOUNT, new BigDecimal("3"), new BigDecimal("50000"), charges);
    }

    private static final CashValuePlan SCALE = new CashValuePlan("ACT/1", LocalDate.of(2026, 1, 1), "PROPORTIONATE", 2,
        List.of(new CashValueRowInput(2, null, null, new BigDecimal("200"), null)));

    @Test
    void aScaleVersionIsNotChecked() {
        assertThatCode(() -> AccumulationPlanValidator.validate(ProductCategory.TERM_LIFE, AccumulationPlan.none(),
            CashValuePlan.none())).doesNotThrowAnyException();
    }

    @Test
    void acceptsAWellFormedAccountVersion() {
        assertThatCode(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
            account(List.of(row(1, 2), row(3, null))), CashValuePlan.none())).doesNotThrowAnyException();
    }

    @Test
    void refusesAnAccountBasisOnACategoryThatCannotCarryOne() {
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ANNUITY,
                account(List.of(row(1, null))), CashValuePlan.none()))
            .hasMessage("A ANNUITY product cannot use an account value basis");
    }

    @Test
    void refusesAScaleAndAnAccountTogether() {
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
                account(List.of(row(1, null))), SCALE))
            .hasMessage("A version is valued either by a cash-value scale or by an account, not both");
    }

    @Test
    void refusesAMissingOrOutOfRangeGuarantee() {
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
                new AccumulationPlan(ValueBasis.ACCOUNT, null, BigDecimal.ZERO, List.of(row(1, null))), CashValuePlan.none()))
            .hasMessage("An account-based version needs a guaranteed interest rate between 0 and 100 percent");
    }

    @Test
    void refusesAMissingMinimumBalance() {
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
                new AccumulationPlan(ValueBasis.ACCOUNT, BigDecimal.ONE, null, List.of(row(1, null))), CashValuePlan.none()))
            .hasMessage("An account-based version needs a minimum balance for withdrawals, zero or more");
    }

    @Test
    void chargesMustStartAtYearOneAndCoverEveryYearAfter() {
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
                account(List.of(row(2, null))), CashValuePlan.none()))
            .hasMessage("Account charges must start at policy year 1");
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
                account(List.of(row(1, 2), row(4, null))), CashValuePlan.none()))
            .hasMessage("Account charges leave policy year 3 uncovered");
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
                account(List.of(row(1, 3), row(2, null))), CashValuePlan.none()))
            .hasMessage("Account charges for policy years 1-3 and 2 onwards overlap");
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
                account(List.of(row(1, 2))), CashValuePlan.none()))
            .hasMessage("The last account charge row must be open-ended, so every policy year has a charge");
    }

    @Test
    void refusesAPercentOutsideZeroToAHundredOrANegativeFee() {
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
                account(List.of(new AccumulationChargeRow(1, null, new BigDecimal("101"), BigDecimal.ZERO, BigDecimal.ZERO))),
                CashValuePlan.none()))
            .hasMessage("An allocation charge must be between 0 and 100 percent");
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
                account(List.of(new AccumulationChargeRow(1, null, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-1")))),
                CashValuePlan.none()))
            .hasMessage("A monthly policy fee cannot be negative");
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -o test -Dtest=AccumulationPlanValidatorTest`
Expected: COMPILATION FAILURE — `ValueBasis`, `AccumulationPlan`, `AccumulationPlanValidator` do not exist.

- [ ] **Step 3: The API records**

```java
// product/api/ValueBasis.java
package tz.co.nlolo.lifeplatform.product.api;

/**
 * How a version's value is defined (product step 3, decision Q1).
 *
 * <p>{@code SCALE} is step 1's: sum assured × a per-mille scale at completed policy years — a
 * traditional endowment's guaranteed surrender value. {@code ACCOUNT} is a ledger owned by the
 * {@code accumulation} module: contributions in, charges out, interest credited. Both end up in
 * {@code PolicyAccount.cashValueAmount}, which is why surrender and loans need no change.
 */
public enum ValueBasis { SCALE, ACCOUNT }
```

```java
// product/api/AccumulationChargeRow.java
package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/**
 * The charges for a run of policy years (decision Q4). {@code toPolicyYear} null means "and every
 * year after", which only the last row may be.
 *
 * @param contributionAllocationPercent held back from each regular contribution and top-up
 * @param transferAllocationPercent held back from a transfer in -- usually 0, because no commission
 *     is paid on transferred money
 * @param monthlyPolicyFee deducted at each month-end run
 */
public record AccumulationChargeRow(int fromPolicyYear, Integer toPolicyYear,
                                    BigDecimal contributionAllocationPercent,
                                    BigDecimal transferAllocationPercent,
                                    BigDecimal monthlyPolicyFee) {

    public boolean covers(int policyYear) {
        return policyYear >= fromPolicyYear && (toPolicyYear == null || policyYear <= toPolicyYear);
    }
}
```

```java
// product/api/AccumulationPlan.java
package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * A version's account terms (product step 3). {@link #none()} is a SCALE version: the step 1
 * behaviour, and what every version published before this step is.
 *
 * @param guaranteedRatePercent the effective annual floor; a declared rate never credits below it
 * @param minimumBalance a partial withdrawal may not leave less than this. NOT a lapse threshold --
 *     an account lapses only when its charges reach zero.
 */
public record AccumulationPlan(ValueBasis basis, BigDecimal guaranteedRatePercent, BigDecimal minimumBalance,
                               List<AccumulationChargeRow> charges) {

    public AccumulationPlan {
        basis = basis != null ? basis : ValueBasis.SCALE;
        charges = charges != null ? List.copyOf(charges) : List.of();
    }

    public static AccumulationPlan none() {
        return new AccumulationPlan(ValueBasis.SCALE, null, null, List.of());
    }

    public boolean isAccount() { return basis == ValueBasis.ACCOUNT; }

    /** The charge row for a policy year. The validator guarantees one exists for every year >= 1. */
    public AccumulationChargeRow chargesFor(int policyYear) {
        return charges.stream().filter(r -> r.covers(policyYear)).findFirst()
            .orElseThrow(() -> new IllegalStateException("No account charge row covers policy year " + policyYear));
    }
}
```

Add `ACCOUNT_VALUE` to `product/api/PayoutAmountBasis.java`, with this javadoc on the constant:

```java
    /**
     * The whole account, valued on the day the instalment falls due (product step 3). Only on the
     * MATURITY row of an ACCOUNT version; {@code amountValue} must be 100. Expanded at issue with no
     * amount, exactly as a premium return is.
     */
    ACCOUNT_VALUE
```

- [ ] **Step 4: The validator**

```java
// product/domain/AccumulationPlanValidator.java
package tz.co.nlolo.lifeplatform.product.domain;

import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Everything a CHECK cannot say about an account version: which categories may use one (decision
 * Q9), that it is not also a scale, and that its charges cover every policy year exactly once.
 * Pure and static, like {@link PayoutPlanValidator}, so the whole rule set runs in milliseconds.
 */
public final class AccumulationPlanValidator {

    /** Q9. ANNUITY waits for D: a pension must not be sellable before it can vest. */
    private static final Set<ProductCategory> ACCOUNT_CATEGORIES =
        EnumSet.of(ProductCategory.ENDOWMENT, ProductCategory.WHOLE_LIFE, ProductCategory.EDUCATION_SAVINGS);

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private AccumulationPlanValidator() {}

    public static void validate(ProductCategory category, AccumulationPlan plan, CashValuePlan cashValue) {
        if (plan == null || !plan.isAccount()) {
            return;
        }
        if (!ACCOUNT_CATEGORIES.contains(category)) {
            fail("A " + category + " product cannot use an account value basis");
        }
        if (cashValue != null && cashValue.isPresent()) {
            fail("A version is valued either by a cash-value scale or by an account, not both");
        }
        BigDecimal rate = plan.guaranteedRatePercent();
        if (rate == null || rate.signum() < 0 || rate.compareTo(HUNDRED) > 0) {
            fail("An account-based version needs a guaranteed interest rate between 0 and 100 percent");
        }
        if (plan.minimumBalance() == null || plan.minimumBalance().signum() < 0) {
            fail("An account-based version needs a minimum balance for withdrawals, zero or more");
        }
        checkCharges(plan.charges());
    }

    private static void checkCharges(List<AccumulationChargeRow> rows) {
        if (rows.isEmpty()) {
            fail("An account-based version needs at least one row of charges");
        }
        List<AccumulationChargeRow> sorted = rows.stream()
            .sorted(Comparator.comparingInt(AccumulationChargeRow::fromPolicyYear)).toList();
        if (sorted.get(0).fromPolicyYear() != 1) {
            fail("Account charges must start at policy year 1");
        }
        for (int i = 0; i < sorted.size(); i++) {
            AccumulationChargeRow row = sorted.get(i);
            checkRow(row);
            boolean last = i == sorted.size() - 1;
            if (row.toPolicyYear() == null && !last) {
                fail("Only the last account charge row may be open-ended");
            }
            if (last && row.toPolicyYear() != null) {
                fail("The last account charge row must be open-ended, so every policy year has a charge");
            }
            if (!last) {
                AccumulationChargeRow next = sorted.get(i + 1);
                int nextExpected = row.toPolicyYear() + 1;
                if (next.fromPolicyYear() < nextExpected) {
                    fail("Account charges for policy years " + describe(row) + " and " + describe(next) + " overlap");
                }
                if (next.fromPolicyYear() > nextExpected) {
                    fail("Account charges leave policy year " + nextExpected + " uncovered");
                }
            }
        }
    }

    private static void checkRow(AccumulationChargeRow row) {
        if (row.toPolicyYear() != null && row.toPolicyYear() < row.fromPolicyYear()) {
            fail("An account charge row ends before it begins (" + describe(row) + ")");
        }
        for (BigDecimal pct : List.of(row.contributionAllocationPercent(), row.transferAllocationPercent())) {
            if (pct == null || pct.signum() < 0 || pct.compareTo(HUNDRED) > 0) {
                fail("An allocation charge must be between 0 and 100 percent");
            }
        }
        if (row.monthlyPolicyFee() == null || row.monthlyPolicyFee().signum() < 0) {
            fail("A monthly policy fee cannot be negative");
        }
    }

    private static String describe(AccumulationChargeRow row) {
        return row.toPolicyYear() == null ? row.fromPolicyYear() + " onwards" : row.fromPolicyYear() + "-" + row.toPolicyYear();
    }

    private static void fail(String message) {
        throw new InvalidProductVersionException(message);
    }
}
```

Run: `./mvnw -o test -Dtest=AccumulationPlanValidatorTest` — Expected: PASS, 8 tests.

- [ ] **Step 5: Payout rules for an account version — test first**

Add to `PayoutPlanValidatorTest`:

```java
    private static final AccumulationPlan ACCOUNT = new AccumulationPlan(ValueBasis.ACCOUNT, new BigDecimal("3"),
        BigDecimal.ZERO, List.of(new AccumulationChargeRow(1, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));

    private static PayoutRowInput accountMaturity(String pct) {
        return new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.ACCOUNT_VALUE, new BigDecimal(pct), null);
    }

    @Test
    void anAccountVersionsMaturityPaysTheWholeAccount() {
        assertThatCode(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
            PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of(accountMaturity("100"))), ACCOUNT))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of(accountMaturity("50"))), ACCOUNT))
            .hasMessage("An account-value maturity pays the whole account (100)");
    }

    @Test
    void anAccountVersionMustNotPayItsMaturityOffTheSumAssured() {
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of(new PayoutRowInput(PayoutKind.MATURITY,
                    null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null))), ACCOUNT))
            .hasMessage("An account-based version's maturity pays the account value");
    }

    @Test
    void onlyAnAccountVersionMayPayTheAccountValue() {
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of(accountMaturity("100"))),
                AccumulationPlan.none()))
            .hasMessage("Only an account-based version can pay the account value");
    }

    @Test
    void anAccountVersionOffersNoSurvivalOrIncomePayouts() {
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(new PayoutTerms(15, 12, false, null), List.of(accountMaturity("100"),
                    new PayoutRowInput(PayoutKind.SURVIVAL, 5, 5, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("10"),
                        PayoutFrequency.ANNUAL))), ACCOUNT))
            .hasMessage("An account-based version pays only its account value; survival and income payouts are not offered");
    }
```

- [ ] **Step 6: Implement the payout rules**

In `PayoutPlanValidator`, keep `validate(ProductCategory, PayoutPlan)` and make it delegate:

```java
    public static void validate(ProductCategory category, PayoutPlan plan) {
        validate(category, plan, AccumulationPlan.none());
    }

    public static void validate(ProductCategory category, PayoutPlan plan, AccumulationPlan accumulation) {
        // ... the existing body, unchanged, followed by:
        checkAccountRules(plan, accumulation);
    }

    /**
     * An account version's money lives in the account, so it pays out the account and nothing that
     * would be drawn from the sum assured instead -- a survival benefit or a %-of-SA maturity on a
     * ledger product would pay from nowhere while the customer's balance sat untouched.
     */
    private static void checkAccountRules(PayoutPlan plan, AccumulationPlan accumulation) {
        boolean account = accumulation != null && accumulation.isAccount();
        for (PayoutRowInput row : plan.rows()) {
            boolean paysAccount = row.amountBasis() == PayoutAmountBasis.ACCOUNT_VALUE;
            if (paysAccount && row.kind() != PayoutKind.MATURITY) {
                fail("Only a MATURITY row may pay the account value");
            }
            if (paysAccount && !account) {
                fail("Only an account-based version can pay the account value");
            }
            if (paysAccount && row.amountValue().compareTo(new BigDecimal("100")) != 0) {
                fail("An account-value maturity pays the whole account (100)");
            }
            if (account && row.kind() == PayoutKind.MATURITY && !paysAccount) {
                fail("An account-based version's maturity pays the account value");
            }
            if (account && (row.kind() == PayoutKind.SURVIVAL || row.kind() == PayoutKind.INCOME)) {
                fail("An account-based version pays only its account value; survival and income payouts are not offered");
            }
        }
    }
```

The existing `checkShape` refuses any non-ROP row valued off premiums; `ACCOUNT_VALUE` is not `PERCENT_OF_PREMIUMS`, so it passes that rule unchanged. Run `./mvnw -o test -Dtest=PayoutPlanValidatorTest` — Expected: PASS.

- [ ] **Step 7: The migration**

```sql
-- db-migrations/product/V19__accumulation_terms.sql
-- Product step 3: a version may be valued by an ACCOUNT rather than step 1's SCALE.
--
-- A SEPARATE TABLE, NOT A COLUMN ON product_version, and that is deliberate. ddl-auto is none, so
-- a column the entity maps but a test database lacks breaks EVERY query on product_version -- and
-- some fifty test classes read it. A row here exists only for an ACCOUNT version; its absence IS
-- the SCALE basis, which is what every version published before this step is.
CREATE TABLE product.version_accumulation_terms (
    product_version_id      UUID PRIMARY KEY REFERENCES product.product_version(product_version_id),
    tenant_id               UUID NOT NULL,
    value_basis             VARCHAR(10) NOT NULL CHECK (value_basis = 'ACCOUNT'),
    guaranteed_rate_percent NUMERIC(7,4) NOT NULL CHECK (guaranteed_rate_percent BETWEEN 0 AND 100),
    minimum_balance         NUMERIC(19,2) NOT NULL CHECK (minimum_balance >= 0)
);

-- One row per run of policy years. to_policy_year NULL = "and every year after"; the validator
-- requires exactly one such row, the last. A CHECK cannot see the other rows, so contiguity and
-- coverage are the validator's -- these are the per-row facts.
CREATE TABLE product.accumulation_charge (
    accumulation_charge_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                       UUID NOT NULL,
    product_version_id              UUID NOT NULL REFERENCES product.product_version(product_version_id),
    from_policy_year                INTEGER NOT NULL CHECK (from_policy_year >= 1),
    to_policy_year                  INTEGER CHECK (to_policy_year IS NULL OR to_policy_year >= from_policy_year),
    contribution_allocation_percent NUMERIC(7,4) NOT NULL CHECK (contribution_allocation_percent BETWEEN 0 AND 100),
    transfer_allocation_percent     NUMERIC(7,4) NOT NULL CHECK (transfer_allocation_percent BETWEEN 0 AND 100),
    monthly_policy_fee              NUMERIC(19,2) NOT NULL CHECK (monthly_policy_fee >= 0)
);
CREATE UNIQUE INDEX ux_accumulation_charge_from ON product.accumulation_charge (product_version_id, from_policy_year);

-- The account-value maturity (step 3). The allow-list widens; the ROP <-> PERCENT_OF_PREMIUMS rule
-- is unchanged, and a new rule keeps ACCOUNT_VALUE on MATURITY rows only.
ALTER TABLE product.payout_schedule_row DROP CONSTRAINT payout_schedule_row_amount_basis_check;
ALTER TABLE product.payout_schedule_row ADD CONSTRAINT payout_schedule_row_amount_basis_check
    CHECK (amount_basis IN ('PERCENT_OF_SA','FIXED','PERCENT_OF_PREMIUMS','ACCOUNT_VALUE'));
ALTER TABLE product.payout_schedule_row ADD CONSTRAINT payout_row_account_value_on_maturity
    CHECK (amount_basis <> 'ACCOUNT_VALUE' OR kind = 'MATURITY');

ALTER TABLE product.version_accumulation_terms ENABLE ROW LEVEL SECURITY;
CREATE POLICY version_accumulation_terms_tenant_isolation ON product.version_accumulation_terms
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.accumulation_charge ENABLE ROW LEVEL SECURITY;
CREATE POLICY accumulation_charge_tenant_isolation ON product.accumulation_charge
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON product.version_accumulation_terms TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON product.accumulation_charge TO app_role;
```

Before writing it, confirm the CHECK's real name — an inline column CHECK is auto-named, and dropping the wrong name fails the migration:

```bash
docker exec infra-postgres-1 psql -U postgres -d lifeplatform -tAc \
  "select conname from pg_constraint where conrelid='product.payout_schedule_row'::regclass and contype='c';"
```

Use the name it prints in place of `payout_schedule_row_amount_basis_check` if it differs.

- [ ] **Step 8: Entities, repositories, persistence and resolution**

```java
// product/domain/VersionAccumulationTerms.java
package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "version_accumulation_terms", schema = "product")
public class VersionAccumulationTerms {
    @Id @Column(name = "product_version_id") private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "value_basis", nullable = false) private String valueBasis = "ACCOUNT";
    @Column(name = "guaranteed_rate_percent", nullable = false) private BigDecimal guaranteedRatePercent;
    @Column(name = "minimum_balance", nullable = false) private BigDecimal minimumBalance;

    protected VersionAccumulationTerms() {}

    public VersionAccumulationTerms(UUID tenantId, UUID productVersionId, BigDecimal guaranteedRatePercent,
                                    BigDecimal minimumBalance) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.guaranteedRatePercent = guaranteedRatePercent;
        this.minimumBalance = minimumBalance;
    }

    public BigDecimal getGuaranteedRatePercent() { return guaranteedRatePercent; }
    public BigDecimal getMinimumBalance() { return minimumBalance; }
}
```

```java
// product/domain/AccumulationCharge.java
package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.product.api.AccumulationChargeRow;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "accumulation_charge", schema = "product")
public class AccumulationCharge {
    @Id @UuidGenerator @Column(name = "accumulation_charge_id") private UUID accumulationChargeId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "from_policy_year", nullable = false) private int fromPolicyYear;
    @Column(name = "to_policy_year") private Integer toPolicyYear;
    @Column(name = "contribution_allocation_percent", nullable = false) private BigDecimal contributionAllocationPercent;
    @Column(name = "transfer_allocation_percent", nullable = false) private BigDecimal transferAllocationPercent;
    @Column(name = "monthly_policy_fee", nullable = false) private BigDecimal monthlyPolicyFee;

    protected AccumulationCharge() {}

    public AccumulationCharge(UUID tenantId, UUID productVersionId, AccumulationChargeRow row) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.fromPolicyYear = row.fromPolicyYear();
        this.toPolicyYear = row.toPolicyYear();
        this.contributionAllocationPercent = row.contributionAllocationPercent();
        this.transferAllocationPercent = row.transferAllocationPercent();
        this.monthlyPolicyFee = row.monthlyPolicyFee();
    }

    public AccumulationChargeRow toRow() {
        return new AccumulationChargeRow(fromPolicyYear, toPolicyYear, contributionAllocationPercent,
            transferAllocationPercent, monthlyPolicyFee);
    }
}
```

```java
// product/infrastructure/VersionAccumulationTermsRepository.java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.VersionAccumulationTerms;
import java.util.UUID;

public interface VersionAccumulationTermsRepository extends JpaRepository<VersionAccumulationTerms, UUID> {}
```

```java
// product/infrastructure/AccumulationChargeRepository.java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.AccumulationCharge;
import java.util.List;
import java.util.UUID;

public interface AccumulationChargeRepository extends JpaRepository<AccumulationCharge, UUID> {
    List<AccumulationCharge> findByProductVersionIdOrderByFromPolicyYear(UUID productVersionId);
}
```

In `ProductApi` add the 7th overload and the resolver, and make the 6th delegate:

```java
    /**
     * The fullest form (product step 3): also how the version is VALUED. Every other overload
     * delegates here with {@link AccumulationPlan#none()} -- a SCALE version, which is what every
     * version before this step is.
     */
    void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                         List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                         List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                         TiraFiling tiraFiling, CashValuePlan cashValue, PayoutPlan payoutPlan,
                         AccumulationPlan accumulationPlan, String publishedBy);

    /** A version's account terms; {@link AccumulationPlan#none()} for a SCALE version. */
    AccumulationPlan resolveAccumulationPlan(UUID productVersionId);
```

In `ProductApiImpl`: the existing 6-argument-tail body moves to the new overload; the old one becomes:

```java
    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                                List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                                TiraFiling tiraFiling, CashValuePlan cashValue, PayoutPlan payoutPlan, String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, baseRates, bounds, frequencyLoading, tiraFiling, cashValue, payoutPlan,
            AccumulationPlan.none(), publishedBy);
    }
```

In the moved body, replace the two validator calls with:

```java
        ProductCategory category = ProductCategory.valueOf(product.getCategory());
        CashValuePlanValidator.validate(category, cashValue);
        AccumulationPlanValidator.validate(category, accumulationPlan, cashValue);
        PayoutPlanValidator.validate(category, payoutPlan, accumulationPlan);
```

and after `persistPayoutPlan(...)`:

```java
        persistAccumulationPlan(tenantId, version.getProductVersionId(), accumulationPlan);
```

```java
    private void persistAccumulationPlan(UUID tenantId, UUID productVersionId, AccumulationPlan plan) {
        if (plan == null || !plan.isAccount()) {
            return; // a SCALE version writes nothing -- its absence here IS the scale basis
        }
        versionAccumulationTermsRepository.save(new VersionAccumulationTerms(tenantId, productVersionId,
            plan.guaranteedRatePercent(), plan.minimumBalance()));
        for (AccumulationChargeRow row : plan.charges()) {
            accumulationChargeRepository.save(new AccumulationCharge(tenantId, productVersionId, row));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AccumulationPlan resolveAccumulationPlan(UUID productVersionId) {
        // RLS scopes both reads to the caller's tenant -- the arrangement resolvePayoutPlan uses.
        return versionAccumulationTermsRepository.findById(productVersionId)
            .map(terms -> new AccumulationPlan(ValueBasis.ACCOUNT, terms.getGuaranteedRatePercent(),
                terms.getMinimumBalance(),
                accumulationChargeRepository.findByProductVersionIdOrderByFromPolicyYear(productVersionId).stream()
                    .map(AccumulationCharge::toRow).toList()))
            .orElse(AccumulationPlan.none());
    }
```

Inject both repositories through the constructor.

- [ ] **Step 9: REST and spec**

```java
// product/infrastructure/AccumulationRequest.java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import tz.co.nlolo.lifeplatform.product.api.AccumulationChargeRow;
import tz.co.nlolo.lifeplatform.product.api.AccumulationPlan;
import tz.co.nlolo.lifeplatform.product.api.ValueBasis;

import java.math.BigDecimal;
import java.util.List;

/** An ACCOUNT version's terms on the wire. Absent on the request means a SCALE version. */
public record AccumulationRequest(@NotNull BigDecimal guaranteedRatePercent, @NotNull BigDecimal minimumBalance,
                                  @NotNull @Valid List<ChargeRow> charges) {

    public record ChargeRow(int fromPolicyYear, Integer toPolicyYear, @NotNull BigDecimal contributionAllocationPercent,
                            @NotNull BigDecimal transferAllocationPercent, @NotNull BigDecimal monthlyPolicyFee) {}

    public AccumulationPlan toPlan() {
        return new AccumulationPlan(ValueBasis.ACCOUNT, guaranteedRatePercent, minimumBalance,
            charges.stream().map(c -> new AccumulationChargeRow(c.fromPolicyYear(), c.toPolicyYear(),
                c.contributionAllocationPercent(), c.transferAllocationPercent(), c.monthlyPolicyFee())).toList());
    }
}
```

Add `@Valid AccumulationRequest accumulation` as the last component of `PublishVersionRequest`, and in `ProductController` pass, after the `PayoutPlan.authored(...)` argument:

```java
            request.accumulation() != null ? request.accumulation().toPlan() : AccumulationPlan.none(),
```

In `openapi-product.yaml`, add to the publish request body's properties (beside `payoutSchedule`):

```yaml
        accumulation:
          description: >
            Present only on an ACCOUNT version (product step 3) -- a ledger of contributions, charges
            and interest instead of step 1's per-mille scale. Refused on a version that also carries
            a cashValue scale, and on any category but ENDOWMENT, WHOLE_LIFE and EDUCATION_SAVINGS.
          type: [object, "null"]
          required: [guaranteedRatePercent, minimumBalance, charges]
          properties:
            guaranteedRatePercent: { type: number, minimum: 0, maximum: 100, description: "Effective annual floor" }
            minimumBalance: { type: number, minimum: 0, description: "A partial withdrawal may not leave less" }
            charges:
              type: array
              minItems: 1
              items:
                type: object
                required: [fromPolicyYear, contributionAllocationPercent, transferAllocationPercent, monthlyPolicyFee]
                properties:
                  fromPolicyYear: { type: integer, minimum: 1 }
                  toPolicyYear: { type: [integer, "null"], minimum: 1, description: "Null on the last row: every year after" }
                  contributionAllocationPercent: { type: number, minimum: 0, maximum: 100 }
                  transferAllocationPercent: { type: number, minimum: 0, maximum: 100 }
                  monthlyPolicyFee: { type: number, minimum: 0 }
```

and `ACCOUNT_VALUE` to the payout row's `amountBasis` enum.

In `ProductContractTest`, add one test publishing an ENDOWMENT with `accumulation` and an `ACCOUNT_VALUE` maturity row (201), and one with `accumulation` on a `TERM_LIFE` product (422, `"A TERM_LIFE product cannot use an account value basis"`). Copy the request-building helper the class already uses for its payout-schedule test.

- [ ] **Step 10: Test classes that now need V19**

```bash
grep -rl 'db-migrations/product/V18__payout_schedule.sql' src/test/java | sort
```

`resolveAccumulationPlan` is only reached by code Tasks 2–8 add, so for THIS task add `V19` only to `ProductApiIntegrationTest`, `ProductContractTest` and `ProductVersionContractTest` (whichever of these the grep lists). Later tasks extend the list for the paths they add.

- [ ] **Step 11: Run and commit**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='AccumulationPlanValidatorTest,PayoutPlanValidatorTest,ProductApiIntegrationTest,ProductContractTest'
```

Expected: all green.

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/product src/test/java/tz/co/nlolo/lifeplatform/product db-migrations/product/V19__accumulation_terms.sql api/openapi/openapi-product.yaml
git commit -m "feat(product): a version may be valued by an account -- guarantee, minimum balance, charges by policy year"
```

---

### Task 2: The `accumulation` module, an immutable ledger, and exactly-once postings

**Files:**
- Create: `db-migrations/accumulation/V1__create_accumulation_schema.sql`
- Create: `accumulation/package-info.java`, `accumulation/api/package-info.java`, `api/AccumulationApi.java`, `api/AccountView.java`, `api/LedgerEntryView.java`, `api/EntryType.java`, `api/AccountStatus.java`, `api/AccumulationStateException.java`, `api/AccountNotFoundException.java`
- Create: `domain/Account.java`, `domain/Posting.java`, `domain/LedgerEntry.java`
- Create: `application/LedgerService.java`, `application/AccumulationApiImpl.java`, `application/EnvelopeRunner.java`, `application/PolicyEventListener.java`
- Create: `infrastructure/AccountRepository.java`, `PostingRepository.java`, `LedgerEntryRepository.java`, `AccumulationExceptionHandler.java`
- Modify: `policy/api/PolicyApi.java`, `policy/application/PolicyApiImpl.java` (`restateAccountValue`)
- Test: `src/test/java/tz/co/nlolo/lifeplatform/accumulation/AccumulationTestFixtures.java`, `LedgerImmutabilityTest.java`, `LedgerServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `ProductApi.resolveAccumulationPlan(UUID)`, the 7th `publishVersion` overload (Task 1).
- Produces:
  - `enum EntryType { CONTRIBUTION, TOP_UP, TRANSFER_IN, ALLOCATION_CHARGE, POLICY_FEE, INTEREST, WITHDRAWAL, SURRENDER, MATURITY, DEATH_CLAIM, FREE_LOOK_REFUND, ADJUSTMENT, REVERSAL }`
  - `enum AccountStatus { OPEN, CLOSED }`
  - `record AccountView(String policyNumber, UUID productId, UUID productVersionId, AccountStatus status, BigDecimal balance, String currency, LocalDate openedOn, String closedReason, LocalDate closedOn)`
  - `record LedgerEntryView(UUID entryId, UUID postingId, int seq, EntryType type, BigDecimal amount, BigDecimal balanceAfter, LocalDate effectiveDate, Instant postedAt, String sourceType, String sourceRef, UUID reversesEntryId, String reason, String createdBy, String approvedBy)`
  - `AccumulationApi.findAccount(String policyNumber) -> Optional<AccountView>`, `isAccount(String) -> boolean`, `entries(String policyNumber) -> List<LedgerEntryView>`
  - `LedgerService.post(String policyNumber, LedgerService.Source source, List<LedgerService.Line> lines, String createdBy, String approvedBy) -> Optional<List<LedgerEntryView>>` — empty when the source was already posted
  - `record LedgerService.Source(String type, String ref)`; `record LedgerService.Line(EntryType type, BigDecimal amount, LocalDate effectiveDate, String reason, UUID reversesEntryId)` with `static Line of(EntryType, BigDecimal, LocalDate, String)`
  - `PolicyApi.restateAccountValue(String policyNumber, BigDecimal value)`

- [ ] **Step 1: The schema**

```sql
-- db-migrations/accumulation/V1__create_accumulation_schema.sql
-- Product step 3: the accumulation engine -- the guide's "bucket" (S21.4). It owns an account's
-- balance and EVERY transaction that changes it. No foreign key into another module's tables: a
-- policy number is carried as a value, this platform's cross-module rule.
--
-- Not partitioned, for the reason benefitpayout gives: a partitioned table must be registered with
-- pg_partman and listed in ops.platform_readiness(), and at these volumes a plain table is right.
CREATE SCHEMA IF NOT EXISTS accumulation;
GRANT USAGE ON SCHEMA accumulation TO app_role;

-- One per ACCOUNT-basis policy. balance and last_seq are the RUNNING HEAD of the ledger, kept in
-- the same transaction as each posting so a new entry knows its seq and its balance_after without
-- summing history. They are a copy, not the truth: the insert trigger below refuses any entry whose
-- balance_after does not follow from the entry before it, so the head cannot drift from the ledger
-- without the next posting failing.
CREATE TABLE accumulation.account (
    policy_number         VARCHAR(20) PRIMARY KEY,
    tenant_id             UUID NOT NULL,
    product_id            UUID NOT NULL,
    product_version_id    UUID NOT NULL,
    policyholder_party_id UUID NOT NULL,
    currency              CHAR(3) NOT NULL DEFAULT 'TZS',
    opened_on             DATE NOT NULL,
    status                VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
    closed_reason         VARCHAR(30),
    closed_on             DATE,
    last_seq              INTEGER NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
    balance               NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    -- The last month whose interest and fee are posted; the month-end run catches up from here.
    last_month_end        DATE,
    version               BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT account_closed_shape CHECK ((status = 'CLOSED') = (closed_on IS NOT NULL))
);

-- The atomic unit: one header per SOURCE, its entries written in the same transaction.
-- ux_posting_source IS the exactly-once guarantee -- not a check-then-insert. Two concurrent
-- deliveries of the same invoice cannot both commit: the loser hits this index and rolls back whole.
CREATE TABLE accumulation.posting (
    posting_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    policy_number VARCHAR(20) NOT NULL,
    source_type   VARCHAR(30) NOT NULL,
    source_ref    VARCHAR(100) NOT NULL,
    posted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    VARCHAR(100) NOT NULL,
    approved_by   VARCHAR(100)
);
CREATE UNIQUE INDEX ux_posting_source ON accumulation.posting (tenant_id, source_type, source_ref);

CREATE TABLE accumulation.ledger_entry (
    entry_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    posting_id        UUID NOT NULL REFERENCES accumulation.posting(posting_id),
    policy_number     VARCHAR(20) NOT NULL,
    seq               INTEGER NOT NULL CHECK (seq >= 1),
    entry_type        VARCHAR(20) NOT NULL CHECK (entry_type IN ('CONTRIBUTION','TOP_UP','TRANSFER_IN',
                          'ALLOCATION_CHARGE','POLICY_FEE','INTEREST','WITHDRAWAL','SURRENDER','MATURITY',
                          'DEATH_CLAIM','FREE_LOOK_REFUND','ADJUSTMENT','REVERSAL')),
    amount            NUMERIC(19,2) NOT NULL,
    balance_after     NUMERIC(19,2) NOT NULL CHECK (balance_after >= 0),
    effective_date    DATE NOT NULL,
    posted_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    reverses_entry_id UUID REFERENCES accumulation.ledger_entry(entry_id),
    reason            VARCHAR(500),
    created_by        VARCHAR(100) NOT NULL,
    approved_by       VARCHAR(100),
    -- A reversal says what it reverses; nothing else may.
    CONSTRAINT ledger_entry_reversal_shape CHECK ((entry_type = 'REVERSAL') = (reverses_entry_id IS NOT NULL)),
    -- A correction a person typed needs a second person.
    CONSTRAINT ledger_entry_adjustment_approved CHECK (entry_type <> 'ADJUSTMENT'
        OR (approved_by IS NOT NULL AND approved_by <> created_by))
);
CREATE UNIQUE INDEX ux_ledger_entry_seq ON accumulation.ledger_entry (policy_number, seq);
-- An entry is reversed at most once.
CREATE UNIQUE INDEX ux_ledger_entry_reversed_once ON accumulation.ledger_entry (reverses_entry_id)
    WHERE reverses_entry_id IS NOT NULL;
CREATE INDEX ix_ledger_entry_policy_date ON accumulation.ledger_entry (policy_number, effective_date);

-- IMMUTABILITY, enforced twice. The grants below give app_role SELECT and INSERT only on these two
-- tables -- the policy/V2 endorsement precedent. But grants do not bind the table OWNER, which every
-- migration and every integration test connects as, so this trigger refuses UPDATE and DELETE for
-- every role. A correction is a REVERSAL or an ADJUSTMENT, never an edit.
CREATE OR REPLACE FUNCTION accumulation.refuse_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'accumulation.% is append-only: correct it with a reversing or adjustment entry, never an %',
        TG_TABLE_NAME, TG_OP;
END $$;
CREATE TRIGGER ledger_entry_append_only BEFORE UPDATE OR DELETE ON accumulation.ledger_entry
    FOR EACH ROW EXECUTE FUNCTION accumulation.refuse_mutation();
CREATE TRIGGER posting_append_only BEFORE UPDATE OR DELETE ON accumulation.posting
    FOR EACH ROW EXECUTE FUNCTION accumulation.refuse_mutation();

-- balance_after cannot drift: each entry must follow from the one before it, and seq must be
-- contiguous. A database rule rather than a Java one, so no code path -- including a hand-written
-- INSERT -- can write a ledger that does not add up.
CREATE OR REPLACE FUNCTION accumulation.check_entry_follows() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous NUMERIC(19,2);
BEGIN
    IF NEW.seq = 1 THEN
        previous := 0;
    ELSE
        SELECT balance_after INTO previous FROM accumulation.ledger_entry
         WHERE policy_number = NEW.policy_number AND seq = NEW.seq - 1;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'ledger entry % for policy % has no entry % before it', NEW.seq, NEW.policy_number, NEW.seq - 1;
        END IF;
    END IF;
    IF NEW.balance_after <> previous + NEW.amount THEN
        RAISE EXCEPTION 'ledger entry % for policy %: balance_after % is not % + %',
            NEW.seq, NEW.policy_number, NEW.balance_after, previous, NEW.amount;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ledger_entry_follows BEFORE INSERT ON accumulation.ledger_entry
    FOR EACH ROW EXECUTE FUNCTION accumulation.check_entry_follows();

-- A declared rate on top of each version's guarantee (decision Q2). Per PRODUCT: every account on
-- the product earns max(declared, its own version's guarantee).
CREATE TABLE accumulation.rate_declaration (
    declaration_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL,
    product_id     UUID NOT NULL,
    rate_percent   NUMERIC(7,4) NOT NULL CHECK (rate_percent BETWEEN 0 AND 100),
    effective_from DATE NOT NULL,
    status         VARCHAR(10) NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED','APPROVED','WITHDRAWN')),
    proposed_by    VARCHAR(100) NOT NULL,
    proposed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by    VARCHAR(100),
    approved_at    TIMESTAMPTZ,
    version        BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT rate_declaration_two_person CHECK (approved_by IS NULL OR approved_by <> proposed_by),
    CONSTRAINT rate_declaration_approved_shape CHECK ((status = 'APPROVED') = (approved_by IS NOT NULL))
);
-- One approved rate per product per day it takes effect.
CREATE UNIQUE INDEX ux_rate_declaration_effective ON accumulation.rate_declaration (product_id, effective_from)
    WHERE status = 'APPROVED';

CREATE TABLE accumulation.withdrawal_request (
    withdrawal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    policy_number VARCHAR(20) NOT NULL,
    amount        NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency      CHAR(3) NOT NULL DEFAULT 'TZS',
    payee_ref     VARCHAR(200) NOT NULL,
    status        VARCHAR(10) NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','APPROVED','PAID','FAILED')),
    requested_by  VARCHAR(100) NOT NULL,
    requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by   VARCHAR(100),
    approved_at   TIMESTAMPTZ,
    disbursement_id UUID,
    version       BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT withdrawal_two_person CHECK (approved_by IS NULL OR approved_by <> requested_by)
);
-- One withdrawal in flight per account, the shape ux_surrender_request_live uses.
CREATE UNIQUE INDEX ux_withdrawal_live ON accumulation.withdrawal_request (policy_number)
    WHERE status IN ('REQUESTED','APPROVED');

CREATE TABLE accumulation.top_up_request (
    top_up_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    policy_number VARCHAR(20) NOT NULL,
    amount        NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency      CHAR(3) NOT NULL DEFAULT 'TZS',
    payer_ref     VARCHAR(200) NOT NULL,
    status        VARCHAR(10) NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','COLLECTED','FAILED')),
    requested_by  VARCHAR(100) NOT NULL,
    requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE accumulation.transfer_in (
    transfer_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    policy_number VARCHAR(20) NOT NULL,
    amount        NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency      CHAR(3) NOT NULL DEFAULT 'TZS',
    source_scheme VARCHAR(200) NOT NULL,
    document_ref  VARCHAR(200),
    recorded_by   VARCHAR(100) NOT NULL,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE accumulation.statement (
    statement_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    policy_number VARCHAR(20) NOT NULL,
    period_from   DATE NOT NULL,
    period_to     DATE NOT NULL CHECK (period_to >= period_from),
    last_seq      INTEGER NOT NULL CHECK (last_seq >= 0),
    document_ref  VARCHAR(200) NOT NULL,
    generated_by  VARCHAR(100) NOT NULL,
    generated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Cross-tenant selectors for the drains: ids only, never a balance (benefitpayout's shape).
CREATE OR REPLACE FUNCTION accumulation.accounts_due_month_end()
RETURNS TABLE (policy_number VARCHAR, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT a.policy_number, a.tenant_id FROM accumulation.account a
     WHERE a.status = 'OPEN'
       AND coalesce(a.last_month_end, (date_trunc('month', a.opened_on) - interval '1 day')::date)
           < (date_trunc('month', current_date) - interval '1 day')::date
     LIMIT 500;
$$;
CREATE OR REPLACE FUNCTION accumulation.accounts_due_annual_statement()
RETURNS TABLE (policy_number VARCHAR, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT a.policy_number, a.tenant_id FROM accumulation.account a
     WHERE a.status = 'OPEN'
       AND NOT EXISTS (SELECT 1 FROM accumulation.statement s
                        WHERE s.policy_number = a.policy_number
                          AND s.period_to = (date_trunc('year', current_date) - interval '1 day')::date)
       AND a.opened_on <= (date_trunc('year', current_date) - interval '1 day')::date
     LIMIT 500;
$$;
REVOKE EXECUTE ON FUNCTION accumulation.accounts_due_month_end() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION accumulation.accounts_due_annual_statement() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION accumulation.accounts_due_month_end() TO app_role;
GRANT EXECUTE ON FUNCTION accumulation.accounts_due_annual_statement() TO app_role;

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['account','posting','ledger_entry','rate_declaration','withdrawal_request',
                             'top_up_request','transfer_in','statement'] LOOP
        EXECUTE format('ALTER TABLE accumulation.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON accumulation.%I USING (tenant_id = '
            'NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)', t || '_tenant_isolation', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE ON accumulation.account, accumulation.rate_declaration,
    accumulation.withdrawal_request, accumulation.top_up_request, accumulation.statement TO app_role;
-- The ledger itself: read and append, nothing else.
GRANT SELECT, INSERT ON accumulation.posting, accumulation.ledger_entry, accumulation.transfer_in TO app_role;
```

- [ ] **Step 2: Write the failing immutability test**

```java
// src/test/java/tz/co/nlolo/lifeplatform/accumulation/LedgerImmutabilityTest.java
package tz.co.nlolo.lifeplatform.accumulation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The ledger cannot be edited or deleted -- by app_role (grants) or by the owner (trigger) -- and
 * cannot be written in a shape that does not add up (the follows trigger). Plain JDBC, no Spring:
 * this is the database's guarantee, and a Java service is exactly what it must not depend on.
 */
@Testcontainers
class LedgerImmutabilityTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final String APP_PASSWORD = "ledger_immutability_password";
    private static final UUID TENANT = UUID.randomUUID();

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/accumulation/V1__create_accumulation_schema.sql");
        try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + APP_PASSWORD + "'");
            s.execute("INSERT INTO accumulation.posting (posting_id, tenant_id, policy_number, source_type, source_ref, created_by) "
                + "VALUES ('00000000-0000-0000-0000-000000000001', '" + TENANT + "', 'POL-IMMUT01', 'invoice', 'invoice:1', 'test')");
            s.execute("INSERT INTO accumulation.ledger_entry (entry_id, tenant_id, posting_id, policy_number, seq, entry_type, "
                + "amount, balance_after, effective_date, created_by) VALUES ('00000000-0000-0000-0000-000000000011', '"
                + TENANT + "', '00000000-0000-0000-0000-000000000001', 'POL-IMMUT01', 1, 'CONTRIBUTION', 1000.00, 1000.00, "
                + "current_date, 'test')");
        }
    }

    private static Connection owner() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection appRole() throws Exception {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_role", APP_PASSWORD);
        try (Statement s = c.createStatement()) {
            s.execute("SET app.current_tenant_id = '" + TENANT + "'");
        }
        return c;
    }

    @Test
    void appRoleCannotUpdateOrDeleteAnEntry() {
        assertThatThrownBy(() -> { try (Connection c = appRole(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE accumulation.ledger_entry SET amount = 1 WHERE seq = 1"); } })
            .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> { try (Connection c = appRole(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM accumulation.ledger_entry WHERE seq = 1"); } })
            .hasMessageContaining("permission denied");
    }

    @Test
    void evenTheOwnerCannotUpdateOrDeleteAnEntryOrAPosting() {
        assertThatThrownBy(() -> { try (Connection c = owner(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE accumulation.ledger_entry SET amount = 1 WHERE seq = 1"); } })
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> { try (Connection c = owner(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM accumulation.posting"); } })
            .hasMessageContaining("append-only");
    }

    @Test
    void anEntryWhoseBalanceDoesNotFollowIsRefused() {
        assertThatThrownBy(() -> { try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO accumulation.ledger_entry (tenant_id, posting_id, policy_number, seq, entry_type, amount, "
                + "balance_after, effective_date, created_by) VALUES ('" + TENANT + "', '00000000-0000-0000-0000-000000000001', "
                + "'POL-IMMUT01', 2, 'POLICY_FEE', -100.00, 950.00, current_date, 'test')"); } })
            .hasMessageContaining("is not 1000.00 + -100.00");
    }

    @Test
    void aGapInTheSequenceIsRefused() {
        assertThatThrownBy(() -> { try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO accumulation.ledger_entry (tenant_id, posting_id, policy_number, seq, entry_type, amount, "
                + "balance_after, effective_date, created_by) VALUES ('" + TENANT + "', '00000000-0000-0000-0000-000000000001', "
                + "'POL-IMMUT01', 5, 'POLICY_FEE', -100.00, 900.00, current_date, 'test')"); } })
            .hasMessageContaining("has no entry 4 before it");
    }

    @Test
    void aBalanceMayNotGoNegative() {
        assertThatThrownBy(() -> { try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO accumulation.ledger_entry (tenant_id, posting_id, policy_number, seq, entry_type, amount, "
                + "balance_after, effective_date, created_by) VALUES ('" + TENANT + "', '00000000-0000-0000-0000-000000000001', "
                + "'POL-IMMUT01', 2, 'POLICY_FEE', -1500.00, -500.00, current_date, 'test')"); } })
            .hasMessageContaining("balance_after");
    }

    @Test
    void theSameSourceCannotPostTwice() {
        assertThatThrownBy(() -> { try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO accumulation.posting (tenant_id, policy_number, source_type, source_ref, created_by) "
                + "VALUES ('" + TENANT + "', 'POL-IMMUT01', 'invoice', 'invoice:1', 'test')"); } })
            .hasMessageContaining("ux_posting_source");
    }

    @Test
    void aCorrectEntryThatFollowsIsAccepted() {
        assertThatCode(() -> { try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO accumulation.ledger_entry (tenant_id, posting_id, policy_number, seq, entry_type, amount, "
                + "balance_after, effective_date, created_by, reverses_entry_id) VALUES ('" + TENANT
                + "', '00000000-0000-0000-0000-000000000001', 'POL-IMMUT01', 2, 'REVERSAL', -1000.00, 0.00, current_date, 'test', "
                + "'00000000-0000-0000-0000-000000000011')"); } })
            .doesNotThrowAnyException();
    }
}
```

`aCorrectEntryThatFollowsIsAccepted` must be the only test that inserts seq 2 successfully. JUnit 5 runs methods in a deterministic but unspecified order; the refusal tests insert seq 2 and seq 5 but are rejected, so order does not matter — the accepted one simply has to be correct whenever it runs.

Run: `./mvnw -o test -Dtest=LedgerImmutabilityTest` — Expected: PASS, 7 tests. (It needs only Step 1's migration, so it is green before any Java exists — which is the point of keeping it database-only.)

- [ ] **Step 3: The API types**

```java
// accumulation/package-info.java
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "policy::api", "product::api" })
package tz.co.nlolo.lifeplatform.accumulation;
```

```java
// accumulation/api/package-info.java
@org.springframework.modulith.NamedInterface("api")
package tz.co.nlolo.lifeplatform.accumulation.api;
```

```java
// accumulation/api/EntryType.java
package tz.co.nlolo.lifeplatform.accumulation.api;

/** Every kind of financial transaction an account can carry. The database CHECK mirrors this list. */
public enum EntryType {
    CONTRIBUTION, TOP_UP, TRANSFER_IN, ALLOCATION_CHARGE, POLICY_FEE, INTEREST, WITHDRAWAL,
    SURRENDER, MATURITY, DEATH_CLAIM, FREE_LOOK_REFUND, ADJUSTMENT, REVERSAL
}
```

```java
// accumulation/api/AccountStatus.java
package tz.co.nlolo.lifeplatform.accumulation.api;

public enum AccountStatus { OPEN, CLOSED }
```

```java
// accumulation/api/AccountView.java
package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AccountView(String policyNumber, UUID productId, UUID productVersionId, AccountStatus status,
                          BigDecimal balance, String currency, LocalDate openedOn, String closedReason,
                          LocalDate closedOn) {}
```

```java
// accumulation/api/LedgerEntryView.java
package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LedgerEntryView(UUID entryId, UUID postingId, int seq, EntryType type, BigDecimal amount,
                              BigDecimal balanceAfter, LocalDate effectiveDate, Instant postedAt,
                              String sourceType, String sourceRef, UUID reversesEntryId, String reason,
                              String createdBy, String approvedBy) {}
```

```java
// accumulation/api/AccumulationStateException.java
package tz.co.nlolo.lifeplatform.accumulation.api;

/** Understood and refused on a rule, not malformed. 422 ACCUMULATION_REFUSED, message shown verbatim. */
public class AccumulationStateException extends RuntimeException {
    public AccumulationStateException(String message) { super(message); }
}
```

```java
// accumulation/api/AccountNotFoundException.java
package tz.co.nlolo.lifeplatform.accumulation.api;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String policyNumber) {
        super("Policy " + policyNumber + " has no savings account");
    }
}
```

```java
// accumulation/api/AccumulationApi.java
package tz.co.nlolo.lifeplatform.accumulation.api;

import java.util.List;
import java.util.Optional;

/**
 * The accumulation engine's published face (product step 3). It OWNS an account's balance and every
 * transaction that changes it: other modules ask through here or by event, and nothing else writes
 * the balance. Tenant from {@code TenantContext} throughout. Tasks 3-8 add the acting methods.
 */
public interface AccumulationApi {

    /** Empty for a policy on a SCALE version -- every policy sold before this step. */
    Optional<AccountView> findAccount(String policyNumber);

    boolean isAccount(String policyNumber);

    /** Every entry, in sequence order. Immutable history: corrections appear as REVERSAL entries. */
    List<LedgerEntryView> entries(String policyNumber);
}
```

- [ ] **Step 4: The aggregates**

```java
// accumulation/domain/Account.java
package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import tz.co.nlolo.lifeplatform.accumulation.api.AccountStatus;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationStateException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One savings account per ACCOUNT-basis policy, and the RUNNING HEAD of its ledger.
 *
 * <p>{@code lastSeq} and {@code balance} let a new entry know its sequence and balance without
 * summing history. They are a copy of the last entry, kept in the same transaction -- and the
 * database's follows-trigger refuses any entry that does not add up from the one before, so the head
 * cannot silently drift from the ledger.
 */
@Entity
@Table(name = "account", schema = "accumulation")
public class Account {

    @Id @Column(name = "policy_number") private String policyNumber;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "policyholder_party_id", nullable = false) private UUID policyholderPartyId;
    @Column(nullable = false) private String currency;
    @Column(name = "opened_on", nullable = false) private LocalDate openedOn;
    @Column(nullable = false) private String status = AccountStatus.OPEN.name();
    @Column(name = "closed_reason") private String closedReason;
    @Column(name = "closed_on") private LocalDate closedOn;
    @Column(name = "last_seq", nullable = false) private int lastSeq;
    @Column(nullable = false) private BigDecimal balance = BigDecimal.ZERO;
    @Column(name = "last_month_end") private LocalDate lastMonthEnd;
    @Version private long version;

    protected Account() {}

    public Account(UUID tenantId, String policyNumber, UUID productId, UUID productVersionId,
                   UUID policyholderPartyId, String currency, LocalDate openedOn) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.productId = productId;
        this.productVersionId = productVersionId;
        this.policyholderPartyId = policyholderPartyId;
        this.currency = currency;
        this.openedOn = openedOn;
    }

    public AccountStatus status() { return AccountStatus.valueOf(status); }

    /** Advance the head past one entry. Returns the entry's seq. */
    public int advance(BigDecimal amount) {
        BigDecimal next = balance.add(amount);
        if (next.signum() < 0) {
            throw new AccumulationStateException("Policy " + policyNumber + "'s account holds " + balance
                + " and cannot move by " + amount);
        }
        this.balance = next;
        return ++lastSeq;
    }

    public void requireOpen() {
        if (status() != AccountStatus.OPEN) {
            throw new AccumulationStateException("Policy " + policyNumber + "'s account is closed ("
                + closedReason + ")");
        }
    }

    public void close(String reason, LocalDate on) {
        this.status = AccountStatus.CLOSED.name();
        this.closedReason = reason;
        this.closedOn = on;
    }

    public void monthEndPostedThrough(LocalDate monthEnd) { this.lastMonthEnd = monthEnd; }

    public String getPolicyNumber() { return policyNumber; }
    public UUID getTenantId() { return tenantId; }
    public UUID getProductId() { return productId; }
    public UUID getProductVersionId() { return productVersionId; }
    public UUID getPolicyholderPartyId() { return policyholderPartyId; }
    public String getCurrency() { return currency; }
    public LocalDate getOpenedOn() { return openedOn; }
    public String getClosedReason() { return closedReason; }
    public LocalDate getClosedOn() { return closedOn; }
    public int getLastSeq() { return lastSeq; }
    public BigDecimal getBalance() { return balance; }
    public LocalDate getLastMonthEnd() { return lastMonthEnd; }
}
```

```java
// accumulation/domain/Posting.java
package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** One source's postings, written once. {@code @Immutable}: Hibernate never issues an UPDATE for it. */
@Entity
@Immutable
@Table(name = "posting", schema = "accumulation")
public class Posting {
    @Id @UuidGenerator @Column(name = "posting_id") private UUID postingId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "source_type", nullable = false) private String sourceType;
    @Column(name = "source_ref", nullable = false) private String sourceRef;
    @Column(name = "posted_at", nullable = false) private Instant postedAt = Instant.now();
    @Column(name = "created_by", nullable = false) private String createdBy;
    @Column(name = "approved_by") private String approvedBy;

    protected Posting() {}

    public Posting(UUID tenantId, String policyNumber, String sourceType, String sourceRef, String createdBy,
                   String approvedBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
        this.createdBy = createdBy;
        this.approvedBy = approvedBy;
    }

    public UUID getPostingId() { return postingId; }
    public String getSourceType() { return sourceType; }
    public String getSourceRef() { return sourceRef; }
}
```

```java
// accumulation/domain/LedgerEntry.java
package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.accumulation.api.EntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One financial transaction. No setters, {@code @Immutable}, and the database refuses edits besides. */
@Entity
@Immutable
@Table(name = "ledger_entry", schema = "accumulation")
public class LedgerEntry {
    @Id @UuidGenerator @Column(name = "entry_id") private UUID entryId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "posting_id", nullable = false) private UUID postingId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private int seq;
    @Column(name = "entry_type", nullable = false) private String entryType;
    @Column(nullable = false) private BigDecimal amount;
    @Column(name = "balance_after", nullable = false) private BigDecimal balanceAfter;
    @Column(name = "effective_date", nullable = false) private LocalDate effectiveDate;
    @Column(name = "posted_at", nullable = false) private Instant postedAt = Instant.now();
    @Column(name = "reverses_entry_id") private UUID reversesEntryId;
    @Column private String reason;
    @Column(name = "created_by", nullable = false) private String createdBy;
    @Column(name = "approved_by") private String approvedBy;

    protected LedgerEntry() {}

    public LedgerEntry(UUID tenantId, UUID postingId, String policyNumber, int seq, EntryType type,
                       BigDecimal amount, BigDecimal balanceAfter, LocalDate effectiveDate,
                       UUID reversesEntryId, String reason, String createdBy, String approvedBy) {
        this.tenantId = tenantId;
        this.postingId = postingId;
        this.policyNumber = policyNumber;
        this.seq = seq;
        this.entryType = type.name();
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.effectiveDate = effectiveDate;
        this.reversesEntryId = reversesEntryId;
        this.reason = reason;
        this.createdBy = createdBy;
        this.approvedBy = approvedBy;
    }

    public UUID getEntryId() { return entryId; }
    public UUID getPostingId() { return postingId; }
    public String getPolicyNumber() { return policyNumber; }
    public int getSeq() { return seq; }
    public EntryType type() { return EntryType.valueOf(entryType); }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public Instant getPostedAt() { return postedAt; }
    public UUID getReversesEntryId() { return reversesEntryId; }
    public String getReason() { return reason; }
    public String getCreatedBy() { return createdBy; }
    public String getApprovedBy() { return approvedBy; }
}
```

- [ ] **Step 5: Repositories**

```java
// accumulation/infrastructure/AccountRepository.java
package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.accumulation.domain.Account;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String> {

    /**
     * SELECT ... FOR UPDATE. Two postings to one account -- a contribution arriving during the
     * month-end run -- must be SERIALISED, not raced: each needs the other's balance as its starting
     * point. A row lock makes the second wait instead of failing an optimistic version check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.policyNumber = :policyNumber")
    Optional<Account> lockForPosting(@Param("policyNumber") String policyNumber);

    @Query(value = "SELECT policy_number, tenant_id FROM accumulation.accounts_due_month_end()", nativeQuery = true)
    List<Object[]> findDueMonthEndAcrossTenants();

    @Query(value = "SELECT policy_number, tenant_id FROM accumulation.accounts_due_annual_statement()", nativeQuery = true)
    List<Object[]> findDueAnnualStatementAcrossTenants();
}
```

```java
// accumulation/infrastructure/PostingRepository.java
package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.accumulation.domain.Posting;

import java.util.Optional;
import java.util.UUID;

public interface PostingRepository extends JpaRepository<Posting, UUID> {
    boolean existsByTenantIdAndSourceTypeAndSourceRef(UUID tenantId, String sourceType, String sourceRef);
    Optional<Posting> findByTenantIdAndSourceTypeAndSourceRef(UUID tenantId, String sourceType, String sourceRef);
}
```

```java
// accumulation/infrastructure/LedgerEntryRepository.java
package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.accumulation.domain.LedgerEntry;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    List<LedgerEntry> findByPolicyNumberOrderBySeq(String policyNumber);
    List<LedgerEntry> findByPostingIdOrderBySeq(UUID postingId);
    boolean existsByReversesEntryId(UUID entryId);
}
```

- [ ] **Step 6: The policy-side projection**

In `PolicyApi`:

```java
    /**
     * Restate an ACCOUNT-basis policy's cash value from its ledger (product step 3). Called ONLY by
     * accumulation, after every posting: the ledger is the truth and this field is its projection,
     * which is why surrender quotes and loan limits -- both of which read it -- need no change.
     */
    void restateAccountValue(String policyNumber, java.math.BigDecimal value);
```

In `PolicyApiImpl`:

```java
    @Override
    @Transactional
    public void restateAccountValue(String policyNumber, BigDecimal value) {
        PolicyAccount account = policyAccountRepository.findById(policyNumber)
            .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
        account.restateCashValue(value);
        policyAccountRepository.save(account);
    }
```

- [ ] **Step 7: The ledger service — the ONE place a posting is written**

```java
// accumulation/application/LedgerService.java
package tz.co.nlolo.lifeplatform.accumulation.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.api.AccountNotFoundException;
import tz.co.nlolo.lifeplatform.accumulation.api.EntryType;
import tz.co.nlolo.lifeplatform.accumulation.api.LedgerEntryView;
import tz.co.nlolo.lifeplatform.accumulation.domain.Account;
import tz.co.nlolo.lifeplatform.accumulation.domain.LedgerEntry;
import tz.co.nlolo.lifeplatform.accumulation.domain.Posting;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.AccountRepository;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.LedgerEntryRepository;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.PostingRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The ONE place a posting is written. Everything that changes a balance comes through {@link #post}.
 *
 * <p>Exactly-once has two layers. A redelivery -- the common case -- finds its posting already there
 * and returns empty without touching anything. A RACE -- two deliveries at once -- is stopped by
 * {@code ux_posting_source}: the loser's insert fails, its whole transaction rolls back, and nothing
 * half-posted survives. The check is the fast path; the index is the guarantee.
 */
@Service
public class LedgerService {

    /** Where a posting came from. {@code ref} is unique per {@code type} within a tenant. */
    public record Source(String type, String ref) {
        public static Source invoice(UUID invoiceId) { return new Source("invoice", "invoice:" + invoiceId); }
    }

    /** One entry to write. The service assigns seq and balance_after; the caller never does. */
    public record Line(EntryType type, BigDecimal amount, LocalDate effectiveDate, String reason, UUID reversesEntryId) {
        public static Line of(EntryType type, BigDecimal amount, LocalDate effectiveDate, String reason) {
            return new Line(type, amount, effectiveDate, reason, null);
        }
    }

    private final AccountRepository accounts;
    private final PostingRepository postings;
    private final LedgerEntryRepository entries;
    private final PolicyApi policyApi;

    public LedgerService(AccountRepository accounts, PostingRepository postings, LedgerEntryRepository entries,
                         PolicyApi policyApi) {
        this.accounts = accounts;
        this.postings = postings;
        this.entries = entries;
        this.policyApi = policyApi;
    }

    /**
     * Write one source's entries, in order, or nothing if that source is already posted.
     *
     * <p>Zero-amount lines are dropped rather than written: an allocation charge of 0% is not a
     * transaction, and an entry that moves nothing only clutters the statement.
     */
    @Transactional
    public Optional<List<LedgerEntryView>> post(String policyNumber, Source source, List<Line> lines,
                                                String createdBy, String approvedBy) {
        UUID tenantId = TenantContext.get();
        if (postings.existsByTenantIdAndSourceTypeAndSourceRef(tenantId, source.type(), source.ref())) {
            return Optional.empty();
        }
        Account account = accounts.lockForPosting(policyNumber)
            .orElseThrow(() -> new AccountNotFoundException(policyNumber));

        // saveAndFlush, not save: the unique index must fire HERE, before any entry is written, so a
        // lost race fails on the posting rather than half-way through its lines.
        Posting posting = postings.saveAndFlush(new Posting(tenantId, policyNumber, source.type(), source.ref(),
            createdBy, approvedBy));

        List<LedgerEntryView> written = new ArrayList<>();
        for (Line line : lines) {
            if (line.amount().signum() == 0) {
                continue;
            }
            int seq = account.advance(line.amount());
            LedgerEntry entry = entries.saveAndFlush(new LedgerEntry(tenantId, posting.getPostingId(), policyNumber,
                seq, line.type(), line.amount(), account.getBalance(), line.effectiveDate(),
                line.reversesEntryId(), line.reason(), createdBy, approvedBy));
            written.add(Views.of(entry, posting));
        }
        accounts.save(account);
        // The projection follows the ledger in the same transaction, so a reader of the policy
        // never sees a cash value the ledger does not hold.
        policyApi.restateAccountValue(policyNumber, account.getBalance());
        // One event per posting (spec §8). Audit records every domain event generically, so this is
        // the ledger's audit trail; and a later GL change can subscribe without touching this module.
        events.publishEvent(DomainEventEnvelope.of("accumulation.PostingRecorded", tenantId, Map.of(
            "postingId", posting.getPostingId().toString(),
            "policyNumber", policyNumber,
            "sourceType", source.type(),
            "sourceRef", source.ref(),
            "createdBy", createdBy,
            // Map.of refuses a null; "" is "nobody", and every reader treats it so.
            "approvedBy", approvedBy != null ? approvedBy : "",
            "entries", written.stream().map(e -> Map.of(
                "seq", e.seq(), "type", e.type().name(), "amount", e.amount().toPlainString(),
                "balanceAfter", e.balanceAfter().toPlainString(), "effectiveDate", e.effectiveDate().toString())).toList())));
        return Optional.of(written);
    }
}
```

`LedgerService` also takes an `ApplicationEventPublisher events` in its constructor. Add the imports `tz.co.nlolo.lifeplatform.DomainEventEnvelope` and `java.util.Map`. Register `accumulation.PostingRecorded` in `api/asyncapi-events.yaml` with these seven keys. This step's tests assert it lands in `audit.audit_log`; see Step 9.

```java
// accumulation/application/Views.java
package tz.co.nlolo.lifeplatform.accumulation.application;

import tz.co.nlolo.lifeplatform.accumulation.api.AccountView;
import tz.co.nlolo.lifeplatform.accumulation.api.LedgerEntryView;
import tz.co.nlolo.lifeplatform.accumulation.domain.Account;
import tz.co.nlolo.lifeplatform.accumulation.domain.LedgerEntry;
import tz.co.nlolo.lifeplatform.accumulation.domain.Posting;

final class Views {
    private Views() {}

    static AccountView of(Account a) {
        return new AccountView(a.getPolicyNumber(), a.getProductId(), a.getProductVersionId(), a.status(),
            a.getBalance(), a.getCurrency(), a.getOpenedOn(), a.getClosedReason(), a.getClosedOn());
    }

    static LedgerEntryView of(LedgerEntry e, Posting p) {
        return new LedgerEntryView(e.getEntryId(), e.getPostingId(), e.getSeq(), e.type(), e.getAmount(),
            e.getBalanceAfter(), e.getEffectiveDate(), e.getPostedAt(), p.getSourceType(), p.getSourceRef(),
            e.getReversesEntryId(), e.getReason(), e.getCreatedBy(), e.getApprovedBy());
    }
}
```

- [ ] **Step 8: Opening an account, and the read API**

```java
// accumulation/application/EnvelopeRunner.java
package tz.co.nlolo.lifeplatform.accumulation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Under the envelope's tenant, in its own transaction, never rethrowing -- benefitpayout's shape.
 *
 * <p>One addition: a lost posting race is reported for what it is. ux_posting_source rejecting a
 * concurrent duplicate is the exactly-once guarantee WORKING, so it is logged at INFO as a dropped
 * duplicate rather than at ERROR as a failure somebody would chase.
 */
@Component
class EnvelopeRunner {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeRunner.class);

    private final TransactionTemplate requiresNew;

    EnvelopeRunner(PlatformTransactionManager transactionManager) {
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    void run(DomainEventEnvelope<?> envelope, Consumer<Map<String, Object>> handler) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            requiresNew.executeWithoutResult(status -> handler.accept(payload));
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("ux_posting_source")) {
                log.info("accumulation dropped a concurrent duplicate of {} for tenant {} -- already posted",
                    envelope.eventType(), envelope.tenantId());
            } else {
                log.error("accumulation failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
            }
        } catch (Exception e) {
            log.error("accumulation failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
```

```java
// accumulation/application/AccumulationApiImpl.java
package tz.co.nlolo.lifeplatform.accumulation.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.api.*;
import tz.co.nlolo.lifeplatform.accumulation.domain.Account;
import tz.co.nlolo.lifeplatform.accumulation.domain.Posting;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.AccountRepository;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.LedgerEntryRepository;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.PostingRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AccumulationApiImpl implements AccumulationApi {

    final AccountRepository accounts;
    final PostingRepository postings;
    final LedgerEntryRepository entries;
    final LedgerService ledger;
    final PolicyApi policyApi;
    final ProductApi productApi;

    public AccumulationApiImpl(AccountRepository accounts, PostingRepository postings, LedgerEntryRepository entries,
                               LedgerService ledger, PolicyApi policyApi, ProductApi productApi) {
        this.accounts = accounts;
        this.postings = postings;
        this.entries = entries;
        this.ledger = ledger;
        this.policyApi = policyApi;
        this.productApi = productApi;
    }

    /**
     * {@code policy.PolicyIssued}: open an account if, and only if, the version is ACCOUNT-basis.
     * Idempotent on the account's presence -- an event may be redelivered.
     */
    @Transactional
    public void openIfAccountVersion(String policyNumber, UUID productVersionId, LocalDate issueDate) {
        if (accounts.existsById(policyNumber) || !productApi.resolveAccumulationPlan(productVersionId).isAccount()) {
            return;
        }
        PolicyView policy = policyApi.getPolicy(policyNumber);
        // Cover's start, not the issue date: a policy year -- and so the charge row -- counts from
        // when cover began, the arrangement benefitpayout's schedule already uses.
        LocalDate opened = policy.commencementDate() != null ? policy.commencementDate() : issueDate;
        accounts.save(new Account(TenantContext.get(), policyNumber, policy.productId(), productVersionId,
            policy.policyholderPartyId(), policy.premiumCurrency(), opened));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountView> findAccount(String policyNumber) {
        return accounts.findById(policyNumber).map(Views::of);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAccount(String policyNumber) {
        return accounts.existsById(policyNumber);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerEntryView> entries(String policyNumber) {
        var list = entries.findByPolicyNumberOrderBySeq(policyNumber);
        Map<UUID, Posting> byId = postings.findAllById(list.stream().map(e -> e.getPostingId()).distinct().toList())
            .stream().collect(Collectors.toMap(Posting::getPostingId, Function.identity()));
        return list.stream().map(e -> Views.of(e, byId.get(e.getPostingId()))).toList();
    }

    Account loadOpen(String policyNumber) {
        Account account = accounts.findById(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber));
        account.requireOpen();
        return account;
    }
}
```

```java
// accumulation/application/PolicyEventListener.java
package tz.co.nlolo.lifeplatform.accumulation.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.time.LocalDate;
import java.util.UUID;

/** The policy lifecycle, as it affects an account. Task 7 adds the closing events. */
// Explicit bean name: several modules declare a PolicyEventListener, and a duplicate default name
// fails application startup.
@Component("accumulationPolicyEventListener")
public class PolicyEventListener {

    private final AccumulationApiImpl api;
    private final EnvelopeRunner runner;

    public PolicyEventListener(AccumulationApiImpl api, EnvelopeRunner runner) {
        this.api = api;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "policy.PolicyIssued" -> runner.run(envelope, p -> api.openIfAccountVersion(
                (String) p.get("policyNumber"), (UUID) p.get("productVersionId"),
                LocalDate.parse((String) p.get("issueDate"))));
            default -> { /* not ours */ }
        }
    }
}
```

```java
// accumulation/infrastructure/AccumulationExceptionHandler.java
package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tz.co.nlolo.lifeplatform.accumulation.api.AccountNotFoundException;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationStateException;

import java.util.UUID;

/** Same ProblemDetail shape as every other module: an errorCode and a copyable traceId. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccumulationExceptionHandler {

    @ExceptionHandler(AccumulationStateException.class)
    public ProblemDetail handleRefused(AccumulationStateException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "ACCUMULATION_REFUSED");
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleNotFound(AccountNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "ACCOUNT_NOT_FOUND");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
```

- [ ] **Step 9: Test fixtures and the ledger service test**

```java
// src/test/java/tz/co/nlolo/lifeplatform/accumulation/AccumulationTestFixtures.java
package tz.co.nlolo.lifeplatform.accumulation;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

/**
 * A real ACCOUNT-basis product and a real in-force policy on it, through the real APIs -- the
 * PayoutTestFixtures shape, for the same reason: a fixture copied into every test class is that many
 * places to miss the next rule a publish or an issuance starts demanding.
 */
@TestComponent
public class AccumulationTestFixtures {

    /** 3% guaranteed, 50,000 minimum for withdrawals; 5% allocation in year 1, 1% after, 1,000 a month. */
    public static final AccumulationPlan SAVINGS = new AccumulationPlan(ValueBasis.ACCOUNT, new BigDecimal("3"),
        new BigDecimal("50000.00"), List.of(
            new AccumulationChargeRow(1, 1, new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("1000.00")),
            new AccumulationChargeRow(2, null, new BigDecimal("1"), BigDecimal.ZERO, new BigDecimal("1000.00"))));

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final PartyApi partyApi;
    private final ProductApi productApi;
    private final PolicyApi policyApi;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate tx;

    public AccumulationTestFixtures(PartyApi partyApi, ProductApi productApi, PolicyApi policyApi,
                                    ApplicationEventPublisher publisher, PlatformTransactionManager transactionManager) {
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.policyApi = policyApi;
        this.publisher = publisher;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record Issued(String policyNumber, UUID productId, UUID productVersionId) {}

    public Issued issueSavingsPlan(UUID tenant, AccumulationPlan plan, LocalDate commencement) {
        return issue(tenant, plan, commencement, 240);
    }

    public Issued issue(UUID tenant, AccumulationPlan plan, LocalDate commencement, int termMonths) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenant);
        try {
            int n = SEQ.incrementAndGet();
            PartyView applicant = partyApi.registerIndividual("Savings Test Life " + n, LocalDate.of(1985, 1, 1),
                "+25571500" + String.format("%04d", n % 10000), null, "test-agent");
            ProductSummaryView product = productApi.createProduct(
                "SAVE-" + n + "-" + tenant.toString().substring(0, 4), "Savings Test Product",
                ProductCategory.ENDOWMENT, "TZS", "actuary");
            // Both shapes are ENDOWMENTs, and step 2's validator makes an endowment carry a schedule
            // and every individual product a free-look period -- so the SCALE control is a plain
            // sum-assured endowment, not a version with no payout plan, which would be refused.
            PayoutPlan payout = PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of(new PayoutRowInput(
                PayoutKind.MATURITY, null, null,
                plan.isAccount() ? PayoutAmountBasis.ACCOUNT_VALUE : PayoutAmountBasis.PERCENT_OF_SA,
                new BigDecimal("100"), null)));
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.GMM, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-49", BigDecimal.ONE, 30, 49),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, List.of(), EligibilityBounds.none(), FrequencyLoading.none(), ANY_FILING,
                CashValuePlan.none(), payout, plan, "actuary");
            UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();
            PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(),
                versionId, new BigDecimal("1000000.00"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY", null,
                List.of(), "savings test", commencement, termMonths, null, null, null);
            String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
            policyApi.activateOnFirstPremium(policyNumber);
            return new Issued(policyNumber, product.productId(), versionId);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }

    /** What billing publishes when an invoice reaches PAID -- the real payload keys. */
    public void collectPremium(UUID tenant, String policyNumber, UUID invoiceId, BigDecimal amount, LocalDate collectedOn) {
        publish(tenant, "billing.PremiumCollected", Map.of(
            "invoiceId", invoiceId,
            "policyNumber", policyNumber,
            "policyholderPartyId", UUID.randomUUID(),
            "amount", Map.of("amount", amount.toPlainString(), "currencyCode", "TZS"),
            "collectedAt", collectedOn.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString(),
            "paidToDate", collectedOn.toString()));
    }

    public void publish(UUID tenant, String eventType, Map<String, Object> payload) {
        tx.executeWithoutResult(status -> publisher.publishEvent(DomainEventEnvelope.of(eventType, tenant, payload)));
    }
}
```

```java
// src/test/java/tz/co/nlolo/lifeplatform/accumulation/LedgerServiceIntegrationTest.java
package tz.co.nlolo.lifeplatform.accumulation;

// Header exactly as PayoutPaymentEndToEndTest: @Testcontainers, @SpringBootTest(classes = Application.class),
// @Import(AccumulationTestFixtures.class), a static postgres:16 container, the datasource
// @DynamicPropertySource, and an @BeforeAll applying PayoutPaymentEndToEndTest's migration list PLUS
//   "db-migrations/product/V19__accumulation_terms.sql",
//   "db-migrations/accumulation/V1__create_accumulation_schema.sql".

    private static final UUID TENANT = UUID.randomUUID();

    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private AccumulationApi api;
    @Autowired private LedgerService ledger;
    @Autowired private PolicyApi policyApi;
    @Autowired private PostingRepository postings;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    @Test
    void anAccountVersionOpensAnAccountAtIssueAndAScaleVersionDoesNot() {
        var account = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        var scale = fixtures.issueSavingsPlan(TENANT, AccumulationPlan.none(), LocalDate.now());
        assertThat(asTenant(() -> api.isAccount(account.policyNumber()))).isTrue();
        // Every policy sold before this step is a scale policy, and must be left exactly as it was.
        assertThat(asTenant(() -> api.isAccount(scale.policyNumber()))).isFalse();
    }

    @Test
    void aPostingWritesContiguousEntriesAndTheProjectionFollows() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        asTenant(() -> ledger.post(issued.policyNumber(), new LedgerService.Source("test", "test:1"), List.of(
            LedgerService.Line.of(EntryType.CONTRIBUTION, new BigDecimal("100000.00"), LocalDate.now(), "Contribution"),
            LedgerService.Line.of(EntryType.ALLOCATION_CHARGE, new BigDecimal("-5000.00"), LocalDate.now(), "5%")),
            "test", null));

        List<LedgerEntryView> entries = asTenant(() -> api.entries(issued.policyNumber()));
        assertThat(entries).extracting(LedgerEntryView::seq).containsExactly(1, 2);
        assertThat(entries).extracting(LedgerEntryView::balanceAfter)
            .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .containsExactly(new BigDecimal("100000.00"), new BigDecimal("95000.00"));
        // Surrender quotes and loan limits read this field. It must equal the ledger, to the cent.
        assertThat(asTenant(() -> policyApi.getCashValue(issued.policyNumber())).cashValueAmount())
            .isEqualByComparingTo("95000.00");
    }

    @Test
    void everyPostingReachesTheAuditLog() throws Exception {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        // Through a real transaction boundary, so AFTER_COMMIT fires: TransactionTemplate, not a
        // bare call from the test thread, which has no transaction to commit.
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(s ->
            asTenant(() -> ledger.post(issued.policyNumber(), new LedgerService.Source("test", "test:audited"), List.of(
                LedgerService.Line.of(EntryType.CONTRIBUTION, new BigDecimal("1000.00"), LocalDate.now(), "x")), "test", null)));
        try (var c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var st = c.prepareStatement("SELECT count(*) FROM audit.audit_log WHERE event_type = 'accumulation.PostingRecorded' "
                 + "AND payload::text LIKE ?")) {
            st.setString(1, "%test:audited%");
            var rs = st.executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void theSameSourcePostsOnceHoweverOftenItArrives() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        var source = new LedgerService.Source("test", "test:again");
        var line = List.of(LedgerService.Line.of(EntryType.CONTRIBUTION, new BigDecimal("1000.00"), LocalDate.now(), "x"));
        assertThat(asTenant(() -> ledger.post(issued.policyNumber(), source, line, "test", null))).isPresent();
        assertThat(asTenant(() -> ledger.post(issued.policyNumber(), source, line, "test", null))).isEmpty();
        assertThat(asTenant(() -> api.entries(issued.policyNumber()))).hasSize(1);
    }

    @Test
    void twoConcurrentDeliveriesOfOneSourceProduceExactlyOnePosting() throws Exception {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        var source = new LedgerService.Source("test", "test:race");
        var line = List.of(LedgerService.Line.of(EntryType.CONTRIBUTION, new BigDecimal("1000.00"), LocalDate.now(), "x"));
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<?>> both = List.of(
                pool.submit(() -> { start.await(); return asTenant(() -> attempt(issued.policyNumber(), source, line)); }),
                pool.submit(() -> { start.await(); return asTenant(() -> attempt(issued.policyNumber(), source, line)); }));
            start.countDown();
            for (var f : both) f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        // The index, not the check, is the guarantee -- one of the two threads lost the race.
        assertThat(asTenant(() -> api.entries(issued.policyNumber()))).hasSize(1);
        assertThat(asTenant(() -> postings.findByTenantIdAndSourceTypeAndSourceRef(TENANT, "test", "test:race")))
            .isPresent();
    }

    /** A lost race throws inside its own transaction; swallowed here, as EnvelopeRunner would. */
    private Object attempt(String policyNumber, LedgerService.Source source, List<LedgerService.Line> lines) {
        try {
            return ledger.post(policyNumber, source, lines, "test", null);
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            return null;
        }
    }

    @Test
    void aMoveThatWouldTakeTheBalanceNegativeIsRefusedAndWritesNothing() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        assertThatThrownBy(() -> asTenant(() -> ledger.post(issued.policyNumber(), new LedgerService.Source("test", "neg"),
                List.of(LedgerService.Line.of(EntryType.POLICY_FEE, new BigDecimal("-1.00"), LocalDate.now(), "fee")),
                "test", null)))
            .isInstanceOf(AccumulationStateException.class);
        assertThat(asTenant(() -> api.entries(issued.policyNumber()))).isEmpty();
    }
```

`audit.audit_log` is partitioned by month, so the migration list must include every audit migration, including the ones that add the current quarter's partitions. Without them, the audit insert fails, the listener logs it, and `everyPostingReachesTheAuditLog` fails for a reason that has nothing to do with accumulation.

Run: `./mvnw -o test -Dtest='LedgerImmutabilityTest,LedgerServiceIntegrationTest'` — Expected: PASS, 13 tests.

- [ ] **Step 10: Modularity**

Run: `./mvnw -o test -Dtest=ModularityTests` — Expected: PASS. A failure naming `accumulation` means a type outside `api` was used from another module, or a dependency is missing from `allowedDependencies`; fix the import, do not widen the list.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/accumulation src/main/java/tz/co/nlolo/lifeplatform/policy src/test/java/tz/co/nlolo/lifeplatform/accumulation db-migrations/accumulation
git commit -m "feat(accumulation): an immutable account ledger, exactly-once per source, projected onto cash value"
```

---

### Task 3: Contributions — a collected premium enters the account, once

**Files:**
- Create: `accumulation/application/PolicyYears.java`, `accumulation/application/BillingEventListener.java`
- Modify: `accumulation/application/AccumulationApiImpl.java` (`creditContribution`), `accumulation/package-info.java` (no change in dependencies: events need none)
- Test: `src/test/java/tz/co/nlolo/lifeplatform/accumulation/PolicyYearsTest.java`, `ContributionIntegrationTest.java`

**Interfaces:**
- Consumes: `LedgerService.post`, `Source.invoice(UUID)`, `ProductApi.resolveAccumulationPlan(UUID)`, `AccumulationPlan.chargesFor(int)` (Task 1)
- Produces:
  - `PolicyYears.of(LocalDate commencement, LocalDate on) -> int` — 1 on the commencement date through the day before the first anniversary
  - `AccumulationApiImpl.creditContribution(String policyNumber, UUID invoiceId, BigDecimal amount, LocalDate collectedOn)`
  - Two entries per contribution: `CONTRIBUTION +gross`, then `ALLOCATION_CHARGE −round(gross × pct / 100, 2, HALF_EVEN)` (dropped when zero)

Billing's payload, verified in `BillingApiImpl.java:363-382`: `invoiceId` (a `UUID` in process), `policyNumber`, `policyholderPartyId`, `amount {amount, currencyCode}` — the invoice's OWN amount, never an overpayment surplus — `collectedAt` (an ISO instant string), `paidToDate`. It fires only on the edge into PAID, so a partly-paid invoice never posts a part; the unique source `invoice:<invoiceId>` makes a redelivery a no-op.

- [ ] **Step 1: Write the failing policy-year test**

```java
// src/test/java/tz/co/nlolo/lifeplatform/accumulation/PolicyYearsTest.java
package tz.co.nlolo.lifeplatform.accumulation;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.accumulation.application.PolicyYears;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyYearsTest {

    private static final LocalDate START = LocalDate.of(2024, 3, 15);

    @Test
    void theFirstYearRunsToTheDayBeforeTheFirstAnniversary() {
        assertThat(PolicyYears.of(START, START)).isEqualTo(1);
        assertThat(PolicyYears.of(START, LocalDate.of(2025, 3, 14))).isEqualTo(1);
        assertThat(PolicyYears.of(START, LocalDate.of(2025, 3, 15))).isEqualTo(2);
    }

    @Test
    void aLeapDayCommencementHasItsAnniversaryOnTheLastDayOfFebruary() {
        // Period.between's convention, and the one benefitpayout's schedule uses: 29 Feb + 1 year
        // = 28 Feb. Two modules counting policy years two ways would charge a year-1 fee on a
        // year-2 date.
        LocalDate leap = LocalDate.of(2024, 2, 29);
        assertThat(PolicyYears.of(leap, LocalDate.of(2025, 2, 27))).isEqualTo(1);
        assertThat(PolicyYears.of(leap, LocalDate.of(2025, 2, 28))).isEqualTo(2);
    }

    @Test
    void aDateBeforeCoverIsRefused() {
        assertThatThrownBy(() -> PolicyYears.of(START, START.minusDays(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("2024-03-14 is before cover began on 2024-03-15");
    }
}
```

Run: `./mvnw -o test -Dtest=PolicyYearsTest` — Expected: FAIL to compile (`PolicyYears` does not exist).

- [ ] **Step 2: Implement it**

Before writing this, open `benefitpayout/application/ScheduleExpander.java` and confirm it dates anniversaries with `commencement.plusYears(n)`; if it uses another rule, use THAT rule here and adjust the leap-day test to match, so both modules agree.

```java
// accumulation/application/PolicyYears.java
package tz.co.nlolo.lifeplatform.accumulation.application;

import java.time.LocalDate;
import java.time.Period;

/** Which policy year a date falls in -- the key every charge row is looked up by. */
public final class PolicyYears {
    private PolicyYears() {}

    public static int of(LocalDate commencement, LocalDate on) {
        if (on.isBefore(commencement)) {
            throw new IllegalArgumentException(on + " is before cover began on " + commencement);
        }
        return Period.between(commencement, on).getYears() + 1;
    }
}
```

Run: `./mvnw -o test -Dtest=PolicyYearsTest` — Expected: PASS, 3 tests.

- [ ] **Step 3: Write the failing contribution test**

Header exactly as `LedgerServiceIntegrationTest` (Task 2), with the same migration list. Then:

```java
    private static final UUID TENANT = UUID.randomUUID();

    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private AccumulationApi api;
    @Autowired private PolicyApi policyApi;

    private List<LedgerEntryView> entriesOf(String policyNumber) {
        TenantContext.set(TENANT);
        try { return api.entries(policyNumber); } finally { TenantContext.clear(); }
    }

    @Test
    void aCollectedPremiumIsCreditedLessTheYearOneAllocationCharge() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        UUID invoice = UUID.randomUUID();
        fixtures.collectPremium(TENANT, issued.policyNumber(), invoice, new BigDecimal("50000.00"), LocalDate.now());

        assertThat(entriesOf(issued.policyNumber()))
            .extracting(LedgerEntryView::type, e -> e.amount().toPlainString(), e -> e.balanceAfter().toPlainString())
            .containsExactly(
                tuple(EntryType.CONTRIBUTION, "50000.00", "50000.00"),
                // 5% in policy year 1.
                tuple(EntryType.ALLOCATION_CHARGE, "-2500.00", "47500.00"));
        assertThat(entriesOf(issued.policyNumber())).allSatisfy(e ->
            assertThat(e.sourceRef()).isEqualTo("invoice:" + invoice));
    }

    @Test
    void theYearIsTheYearTheMoneyArrivedIn() {
        // Commenced 13 months ago: this premium is collected in policy year 2, where the charge is 1%.
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now().minusMonths(13));
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("50000.00"), LocalDate.now());
        assertThat(entriesOf(issued.policyNumber())).extracting(e -> e.amount().toPlainString())
            .containsExactly("50000.00", "-500.00");
    }

    @Test
    void aRedeliveredPremiumPostsNothingTheSecondTime() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        UUID invoice = UUID.randomUUID();
        fixtures.collectPremium(TENANT, issued.policyNumber(), invoice, new BigDecimal("50000.00"), LocalDate.now());
        fixtures.collectPremium(TENANT, issued.policyNumber(), invoice, new BigDecimal("50000.00"), LocalDate.now());
        fixtures.collectPremium(TENANT, issued.policyNumber(), invoice, new BigDecimal("50000.00"), LocalDate.now());
        assertThat(entriesOf(issued.policyNumber())).hasSize(2);
        TenantContext.set(TENANT);
        try {
            assertThat(policyApi.getCashValue(issued.policyNumber()).cashValueAmount()).isEqualByComparingTo("47500.00");
        } finally { TenantContext.clear(); }
    }

    @Test
    void aPremiumOnAScalePolicyIsIgnored() {
        var scale = fixtures.issueSavingsPlan(TENANT, AccumulationPlan.none(), LocalDate.now());
        fixtures.collectPremium(TENANT, scale.policyNumber(), UUID.randomUUID(), new BigDecimal("50000.00"), LocalDate.now());
        assertThat(entriesOf(scale.policyNumber())).isEmpty();
    }

    @Test
    void aPremiumPaidBeforeAFutureCommencementIsDatedToTheDayCoverStarts() {
        LocalDate starts = LocalDate.now().plusDays(10);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, starts);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("50000.00"), LocalDate.now());
        assertThat(entriesOf(issued.policyNumber())).extracting(LedgerEntryView::effectiveDate).containsOnly(starts);
    }

    @Test
    void aZeroPercentYearWritesNoChargeEntry() {
        var free = new AccumulationPlan(ValueBasis.ACCOUNT, new BigDecimal("3"), new BigDecimal("50000.00"),
            List.of(new AccumulationChargeRow(1, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
        var issued = fixtures.issueSavingsPlan(TENANT, free, LocalDate.now());
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("50000.00"), LocalDate.now());
        assertThat(entriesOf(issued.policyNumber())).extracting(LedgerEntryView::type).containsExactly(EntryType.CONTRIBUTION);
    }

    @Test
    void theChargeIsRoundedOnceHalfEven() {
        var odd = new AccumulationPlan(ValueBasis.ACCOUNT, new BigDecimal("3"), new BigDecimal("50000.00"),
            List.of(new AccumulationChargeRow(1, null, new BigDecimal("2.5"), BigDecimal.ZERO, BigDecimal.ZERO)));
        var issued = fixtures.issueSavingsPlan(TENANT, odd, LocalDate.now());
        // 2.5% of 1,001.00 = 25.025 -> 25.02 (half-even), not 25.03.
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("1001.00"), LocalDate.now());
        assertThat(entriesOf(issued.policyNumber())).extracting(e -> e.amount().toPlainString())
            .containsExactly("1001.00", "-25.02");
    }
```

Run: `./mvnw -o test -Dtest=ContributionIntegrationTest` — Expected: the first three and last two FAIL with empty entries (nobody listens yet); `aPremiumOnAScalePolicyIsIgnored` passes vacuously. That one passing before the code exists is EXPECTED (see the vacuous-guard note in Global Constraints); it is kept because once the listener exists it is the test that stops the listener opening ledgers on scale policies.

- [ ] **Step 4: Credit the contribution**

Add to `AccumulationApiImpl`:

```java
    /**
     * {@code billing.PremiumCollected}: the gross premium in, then the year's allocation charge out,
     * as ONE posting keyed on the invoice -- so a redelivery, however many, posts nothing more.
     *
     * <p>The policy year is the year the money ARRIVED in, not the year the invoice was due: a
     * premium paid late in year 2 for a year-1 instalment is charged at year 2's rate. That is what
     * the customer is told on the charge schedule ("charges taken from each payment, by policy
     * year"), and it needs no knowledge of which period an invoice covered.
     */
    @Transactional
    public void creditContribution(String policyNumber, UUID invoiceId, BigDecimal amount, LocalDate collectedOn) {
        Optional<Account> found = accounts.findById(policyNumber);
        if (found.isEmpty()) {
            return; // a scale policy -- not ours
        }
        Account account = found.get();
        // A contribution after a death is handled by Task 7's closing (it is returned with the death
        // benefit), never silently credited to a closed account.
        account.requireOpen();
        // A first premium can arrive BEFORE a future-dated commencement. It is dated to the day
        // cover starts: it cannot earn interest before the account exists, and PolicyYears refuses
        // a date before cover -- which inside this listener would drop the customer's money with
        // nothing but a log line.
        LocalDate effective = collectedOn.isBefore(account.getOpenedOn()) ? account.getOpenedOn() : collectedOn;
        int policyYear = PolicyYears.of(account.getOpenedOn(), effective);
        AccumulationPlan plan = productApi.resolveAccumulationPlan(account.getProductVersionId());
        AccumulationChargeRow charges = plan.chargesFor(policyYear);
        BigDecimal charge = amount.multiply(charges.contributionAllocationPercent())
            .divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
        ledger.post(policyNumber, LedgerService.Source.invoice(invoiceId), List.of(
            LedgerService.Line.of(EntryType.CONTRIBUTION, amount, effective, "Premium collected"),
            LedgerService.Line.of(EntryType.ALLOCATION_CHARGE, charge.negate(), effective,
                "Allocation charge " + charges.contributionAllocationPercent().stripTrailingZeros().toPlainString()
                    + "% (policy year " + policyYear + ")")),
            "system", null);
    }

    private static final BigDecimal HUNDRED = new BigDecimal("100");
```

Imports to add: `java.math.BigDecimal`, `java.math.RoundingMode`, `tz.co.nlolo.lifeplatform.product.api.AccumulationPlan`, `tz.co.nlolo.lifeplatform.product.api.AccumulationChargeRow`.

`chargesFor` returns a zero row when no charge row covers the year (Task 1's contract), so a year beyond the last authored row is free rather than a failure.

- [ ] **Step 5: The listener**

```java
// accumulation/application/BillingEventListener.java
package tz.co.nlolo.lifeplatform.accumulation.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

@Component("accumulationBillingEventListener")
public class BillingEventListener {

    /** The platform's business day, as billing dates invoices. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final AccumulationApiImpl api;
    private final EnvelopeRunner runner;

    public BillingEventListener(AccumulationApiImpl api, EnvelopeRunner runner) {
        this.api = api;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"billing.PremiumCollected".equals(envelope.eventType())) {
            return;
        }
        runner.run(envelope, p -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> amount = (Map<String, Object>) p.get("amount");
            api.creditContribution(
                (String) p.get("policyNumber"),
                // A UUID in process, a String after any serialising hop -- accept both.
                UUID.fromString(String.valueOf(p.get("invoiceId"))),
                new BigDecimal((String) amount.get("amount")),
                LocalDate.ofInstant(Instant.parse((String) p.get("collectedAt")), BUSINESS_ZONE));
        });
    }
}
```

Before committing to `Africa/Dar_es_Salaam`, grep for an existing business-zone constant (`grep -rn "Dar_es_Salaam" src/main/java`) and use it if one exists; a second definition is a second place to change.

Run: `./mvnw -o test -Dtest='PolicyYearsTest,ContributionIntegrationTest'` — Expected: PASS, 10 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/accumulation src/test/java/tz/co/nlolo/lifeplatform/accumulation
git commit -m "feat(accumulation): a collected premium is credited once, less its policy year's allocation charge"
```

---

### Task 4: Interest, the monthly fee, rate declarations, and an account that runs dry

**Files:**
- Create: `accumulation/application/InterestCalculator.java`, `RateSchedule.java`, `AccountValuer.java`, `MonthEndDrain.java`
- Create: `accumulation/domain/RateDeclaration.java`, `accumulation/infrastructure/RateDeclarationRepository.java`
- Create: `accumulation/api/RateDeclarationView.java`, `accumulation/api/RateDeclarationStatus.java`
- Create: `accumulation/infrastructure/AccumulationController.java`, `RateDeclarationRequest.java`, `RateDeclarationResponse.java`
- Create: `api/openapi/openapi-accumulation.yaml`
- Modify: `AccumulationApi.java`, `AccumulationApiImpl.java`, `AccountRepository.java`, `accumulation/application/PolicyEventListener.java`, `Account.java` (`reopen`)
- Modify: `policy/api/PolicyApi.java`, `policy/application/PolicyApiImpl.java`, `policy/domain/Policy.java` (`lapseExhaustedAccount`)
- Modify: `src/main/resources/application-local.yml` (`accumulation.month-end-interval-ms: 10000`)
- Test: `InterestCalculatorTest.java` (unit), `MonthEndIntegrationTest.java`, `RateDeclarationIntegrationTest.java`, `AccumulationContractTest.java`

**Interfaces:**
- Consumes: `LedgerService.post`, `PolicyYears.of`, `AccumulationPlan.guaranteedRatePercent()` / `chargesFor(int).monthlyPolicyFee()` (Task 1)
- Produces:
  - `InterestCalculator.interest(BigDecimal opening, List<Movement> movements, LocalDate from, LocalDate to, Function<LocalDate, BigDecimal> annualRatePercent) -> BigDecimal` (UNROUNDED); `record Movement(LocalDate effectiveDate, BigDecimal amount)`; `dailyFactor(BigDecimal annualRatePercent, int daysInYear) -> BigDecimal`
  - `AccountValuer.interestBetween(Account, LocalDate fromInclusive, LocalDate toInclusive) -> BigDecimal` — rounded once, 2 dp HALF_EVEN. Task 7 uses it for interest to a closing date.
  - `AccumulationApiImpl.postMonthEnds(String policyNumber, LocalDate today)`
  - `AccumulationApi.proposeRate(UUID productId, BigDecimal ratePercent, LocalDate effectiveFrom, String proposedBy) -> RateDeclarationView`, `approveRate(UUID declarationId, String approvedBy)`, `withdrawRate(UUID declarationId, String withdrawnBy)`, `listRates(UUID productId) -> List<RateDeclarationView>`
  - `record RateDeclarationView(UUID declarationId, UUID productId, BigDecimal ratePercent, LocalDate effectiveFrom, RateDeclarationStatus status, String proposedBy, Instant proposedAt, String approvedBy, Instant approvedAt)`
  - `PolicyApi.lapseExhaustedAccount(String policyNumber, LocalDate exhaustedOn) -> boolean` — false (and no write) when the policy is in no state that can lapse
  - Closing reasons used from here on: `EXHAUSTED`, and in Task 7 `SURRENDERED`, `MATURED`, `DEATH`, `FREE_LOOK`

**The rules this task pins, in one place:**
1. A movement effective on day *d* is in day *d*'s balance and earns that day. Symmetric: a withdrawal on *d* earns nothing on *d*.
2. Each day earns `(balance + interest accrued so far this period) × dailyFactor(rate(d), d.lengthOfYear())`. Compounding on the unposted accrual is what makes twelve monthly postings land on the declared annual rate (spec §10.7).
3. `rate(d) = max(latest APPROVED declaration for the product with effective_from ≤ d, the version's guarantee)`.
4. Interest is rounded ONCE per posting, 2 dp HALF_EVEN.
5. The fee for a month is charged only when the account was open for the whole month (opened on or before its 1st) and the policy is ACTIVE, REINSTATED, PAID_UP or SUSPENDED. No cover, no fee. A part first month is free.
6. A month-end on an account that has never received money posts nothing but advances `last_month_end`. A policy whose first premium never arrives is not-taken-up's business, not exhaustion's.
7. When the fee takes the balance to zero, the fee is capped at what is there, the account closes `EXHAUSTED`, and the policy lapses through `lapseExhaustedAccount`. A reinstatement reopens an `EXHAUSTED` account at zero.
8. The source ref is `month-end:<policyNumber>:<yyyy-MM>`. The policy number is in the ref because `ux_posting_source` is unique per TENANT, not per policy: without it, the second policy's September would be refused as a duplicate of the first's.
9. A declaration may not take effect on or before the latest `last_month_end` of any account on its product. It is checked at proposal AND at approval, because the month-end run can pass the date in between.

- [ ] **Step 1: Write the failing calculator test**

```java
// src/test/java/tz/co/nlolo/lifeplatform/accumulation/InterestCalculatorTest.java
package tz.co.nlolo.lifeplatform.accumulation;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.accumulation.application.InterestCalculator;
import tz.co.nlolo.lifeplatform.accumulation.application.InterestCalculator.Movement;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class InterestCalculatorTest {

    private static final BigDecimal FIVE = new BigDecimal("5");

    @Test
    void aYearOfDailyFactorsCompoundsToTheDeclaredRateExactly() {
        // Before any rounding: (1 + f)^365 must be 1.05 to far better than a cent on any balance.
        BigDecimal f = InterestCalculator.dailyFactor(FIVE, 365);
        BigDecimal year = BigDecimal.ONE.add(f).pow(365, MathContext.DECIMAL128);
        assertThat(year.subtract(new BigDecimal("1.05")).abs()).isLessThan(new BigDecimal("1E-12"));
    }

    @Test
    void twelveMonthlyPostingsOnAConstantBalanceCreditTheDeclaredRate() {
        // The spec's test: a year at 5% credits 5%. Each month's interest is rounded to the cent and
        // then compounds, so the year can differ from 50,000.00 by the rounding alone -- at most a
        // cent a month, and in practice a cent or two.
        BigDecimal balance = new BigDecimal("1000000.00");
        BigDecimal credited = BigDecimal.ZERO;
        for (int m = 1; m <= 12; m++) {
            YearMonth month = YearMonth.of(2025, m);
            BigDecimal interest = InterestCalculator.interest(balance, List.of(), month.atDay(1), month.atEndOfMonth(),
                d -> FIVE).setScale(2, RoundingMode.HALF_EVEN);
            balance = balance.add(interest);
            credited = credited.add(interest);
        }
        assertThat(credited.doubleValue()).isCloseTo(50_000.00, within(0.05));
    }

    @Test
    void aDeclarationFromTheSixteenthSplitsTheMonthByDay() {
        LocalDate from = LocalDate.of(2025, 9, 1);
        LocalDate to = LocalDate.of(2025, 9, 30);
        LocalDate change = LocalDate.of(2025, 9, 16);
        BigDecimal opening = new BigDecimal("1000000.00");

        BigDecimal split = InterestCalculator.interest(opening, List.of(), from, to,
            d -> d.isBefore(change) ? new BigDecimal("3") : new BigDecimal("6"));

        // Fifteen days at 3% then fifteen at 6%, compounding through the change.
        BigDecimal f3 = InterestCalculator.dailyFactor(new BigDecimal("3"), 365);
        BigDecimal f6 = InterestCalculator.dailyFactor(new BigDecimal("6"), 365);
        BigDecimal expected = opening.multiply(BigDecimal.ONE.add(f3).pow(15, MathContext.DECIMAL128))
            .multiply(BigDecimal.ONE.add(f6).pow(15, MathContext.DECIMAL128)).subtract(opening);
        assertThat(split.subtract(expected).abs()).isLessThan(new BigDecimal("0.0001"));
    }

    @Test
    void aMovementEarnsFromTheDayItIsEffective() {
        LocalDate from = LocalDate.of(2025, 9, 1);
        LocalDate to = LocalDate.of(2025, 9, 30);
        // 100,000 arriving on the 30th earns exactly one day.
        BigDecimal interest = InterestCalculator.interest(BigDecimal.ZERO,
            List.of(new Movement(to, new BigDecimal("100000.00"))), from, to, d -> FIVE);
        assertThat(interest.subtract(new BigDecimal("100000.00").multiply(InterestCalculator.dailyFactor(FIVE, 365))).abs())
            .isLessThan(new BigDecimal("1E-10"));
    }

    @Test
    void aLeapYearDayUsesThreeHundredAndSixtySixDays() {
        LocalDate leapDay = LocalDate.of(2028, 2, 29);
        BigDecimal interest = InterestCalculator.interest(new BigDecimal("1000000.00"), List.of(), leapDay, leapDay, d -> FIVE);
        assertThat(interest.subtract(new BigDecimal("1000000.00").multiply(InterestCalculator.dailyFactor(FIVE, 366))).abs())
            .isLessThan(new BigDecimal("1E-10"));
    }

    @Test
    void aZeroBalanceEarnsNothing() {
        assertThat(InterestCalculator.interest(BigDecimal.ZERO, List.of(), LocalDate.of(2025, 1, 1),
            LocalDate.of(2025, 1, 31), d -> FIVE)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
```

Run: `./mvnw -o test -Dtest=InterestCalculatorTest` — Expected: FAIL to compile.

- [ ] **Step 2: The calculator**

```java
// accumulation/application/InterestCalculator.java
package tz.co.nlolo.lifeplatform.accumulation.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/**
 * Interest on a daily balance, compounding daily -- including on interest accrued but not yet
 * posted (spec §10.7). Pure: no repository, no clock, no rounding. The caller rounds ONCE.
 *
 * <p>The declared rate is an EFFECTIVE annual rate, so the daily factor is {@code (1+r)^(1/n) - 1}
 * with {@code n} the length of the day's own year. Computed as {@code expm1(log1p(r)/n)}, which
 * keeps full double precision for a factor near zero -- {@code Math.pow(1+r, 1/n) - 1} would lose
 * its leading digits to the subtraction.
 */
public final class InterestCalculator {
    private InterestCalculator() {}

    private static final MathContext MC = MathContext.DECIMAL128;

    public record Movement(LocalDate effectiveDate, BigDecimal amount) {}

    public static BigDecimal dailyFactor(BigDecimal annualRatePercent, int daysInYear) {
        double r = annualRatePercent.doubleValue() / 100.0;
        return new BigDecimal(Math.expm1(Math.log1p(r) / daysInYear), MC);
    }

    /**
     * @param opening    the balance at the start of {@code from} -- every entry effective before it
     * @param movements  entries effective within {@code [from, to]}; any order
     * @return interest earned over {@code [from, to]} inclusive, unrounded
     */
    public static BigDecimal interest(BigDecimal opening, List<Movement> movements, LocalDate from, LocalDate to,
                                      Function<LocalDate, BigDecimal> annualRatePercent) {
        BigDecimal balance = opening;
        BigDecimal accrued = BigDecimal.ZERO;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            for (Movement m : movements) {
                if (m.effectiveDate().equals(d)) {
                    balance = balance.add(m.amount());
                }
            }
            BigDecimal factor = dailyFactor(annualRatePercent.apply(d), d.lengthOfYear());
            accrued = accrued.add(balance.add(accrued).multiply(factor, MC), MC);
        }
        return accrued;
    }
}
```

The inner loop is O(days × movements). A month has 31 days and an account a handful of movements, so this is a few hundred multiplications per account; do not "optimise" it into a map unless a profile says so.

Run: `./mvnw -o test -Dtest=InterestCalculatorTest` — Expected: PASS, 6 tests.

- [ ] **Step 3: Rate declarations — aggregate, repository, view**

```java
// accumulation/api/RateDeclarationStatus.java
package tz.co.nlolo.lifeplatform.accumulation.api;

public enum RateDeclarationStatus { PROPOSED, APPROVED, WITHDRAWN }
```

```java
// accumulation/api/RateDeclarationView.java
package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RateDeclarationView(UUID declarationId, UUID productId, BigDecimal ratePercent, LocalDate effectiveFrom,
                                  RateDeclarationStatus status, String proposedBy, Instant proposedAt,
                                  String approvedBy, Instant approvedAt) {}
```

```java
// accumulation/domain/RateDeclaration.java
package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationStateException;
import tz.co.nlolo.lifeplatform.accumulation.api.RateDeclarationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A declared rate for a product, on top of each version's guarantee. Two people, like every price. */
@Entity
@Table(name = "rate_declaration", schema = "accumulation")
public class RateDeclaration {
    @Id @UuidGenerator @Column(name = "declaration_id") private UUID declarationId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(name = "rate_percent", nullable = false) private BigDecimal ratePercent;
    @Column(name = "effective_from", nullable = false) private LocalDate effectiveFrom;
    @Column(nullable = false) private String status = RateDeclarationStatus.PROPOSED.name();
    @Column(name = "proposed_by", nullable = false) private String proposedBy;
    @Column(name = "proposed_at", nullable = false) private Instant proposedAt = Instant.now();
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Version private long version;

    protected RateDeclaration() {}

    public RateDeclaration(UUID tenantId, UUID productId, BigDecimal ratePercent, LocalDate effectiveFrom, String proposedBy) {
        this.tenantId = tenantId;
        this.productId = productId;
        this.ratePercent = ratePercent;
        this.effectiveFrom = effectiveFrom;
        this.proposedBy = proposedBy;
    }

    public RateDeclarationStatus status() { return RateDeclarationStatus.valueOf(status); }

    public void approve(String by) {
        requireProposed();
        if (by.equals(proposedBy)) {
            throw new AccumulationStateException(
                "A declared rate must be approved by someone other than the person who proposed it");
        }
        this.status = RateDeclarationStatus.APPROVED.name();
        this.approvedBy = by;
        this.approvedAt = Instant.now();
    }

    /** Only a proposal can be withdrawn: an approved rate may already have earned interest. */
    public void withdraw() {
        requireProposed();
        this.status = RateDeclarationStatus.WITHDRAWN.name();
    }

    private void requireProposed() {
        if (status() != RateDeclarationStatus.PROPOSED) {
            throw new AccumulationStateException("This rate declaration is " + status().name().toLowerCase()
                + ", not awaiting approval");
        }
    }

    public UUID getDeclarationId() { return declarationId; }
    public UUID getProductId() { return productId; }
    public BigDecimal getRatePercent() { return ratePercent; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public String getProposedBy() { return proposedBy; }
    public Instant getProposedAt() { return proposedAt; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
}
```

```java
// accumulation/infrastructure/RateDeclarationRepository.java
package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.accumulation.domain.RateDeclaration;

import java.util.List;
import java.util.UUID;

public interface RateDeclarationRepository extends JpaRepository<RateDeclaration, UUID> {
    List<RateDeclaration> findByProductIdOrderByEffectiveFromDescProposedAtDesc(UUID productId);
    List<RateDeclaration> findByProductIdAndStatusOrderByEffectiveFrom(UUID productId, String status);
}
```

Add to `AccountRepository`:

```java
    /** The furthest any account on this product has had interest posted -- a rate may not reach back past it. */
    @Query("select max(a.lastMonthEnd) from Account a where a.productId = :productId")
    LocalDate latestMonthEndForProduct(@Param("productId") UUID productId);
```

- [ ] **Step 4: The rate schedule and the valuer**

```java
// accumulation/application/RateSchedule.java
package tz.co.nlolo.lifeplatform.accumulation.application;

import tz.co.nlolo.lifeplatform.accumulation.domain.RateDeclaration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/** {@code max(latest approved declaration effective by d, the version's guarantee)}, per day. */
final class RateSchedule implements Function<LocalDate, BigDecimal> {

    private final List<RateDeclaration> approvedAscending;
    private final BigDecimal guarantee;

    RateSchedule(List<RateDeclaration> approvedAscending, BigDecimal guarantee) {
        this.approvedAscending = approvedAscending;
        this.guarantee = guarantee;
    }

    @Override
    public BigDecimal apply(LocalDate d) {
        BigDecimal declared = null;
        for (RateDeclaration r : approvedAscending) {
            if (r.getEffectiveFrom().isAfter(d)) {
                break;
            }
            declared = r.getRatePercent();
        }
        // A later version with a lower guarantee never lowers an earlier customer's floor: the
        // guarantee here is the account's OWN pinned version's.
        return declared == null ? guarantee : declared.max(guarantee);
    }
}
```

```java
// accumulation/application/AccountValuer.java
package tz.co.nlolo.lifeplatform.accumulation.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.accumulation.api.RateDeclarationStatus;
import tz.co.nlolo.lifeplatform.accumulation.domain.Account;
import tz.co.nlolo.lifeplatform.accumulation.domain.LedgerEntry;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.LedgerEntryRepository;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.RateDeclarationRepository;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Interest an account has earned over a span, replayed from its entries -- there is no accrual
 * table, because unposted interest is not a transaction (spec §4.3).
 */
@Component
class AccountValuer {

    private final LedgerEntryRepository entries;
    private final RateDeclarationRepository rates;
    private final ProductApi productApi;

    AccountValuer(LedgerEntryRepository entries, RateDeclarationRepository rates, ProductApi productApi) {
        this.entries = entries;
        this.rates = rates;
        this.productApi = productApi;
    }

    /** Rounded once, 2 dp half-even. Zero when {@code to} is before {@code from}. */
    BigDecimal interestBetween(Account account, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return BigDecimal.ZERO.setScale(2);
        }
        List<LedgerEntry> all = entries.findByPolicyNumberOrderBySeq(account.getPolicyNumber());
        BigDecimal opening = all.stream().filter(e -> e.getEffectiveDate().isBefore(from))
            .map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<InterestCalculator.Movement> within = all.stream()
            .filter(e -> !e.getEffectiveDate().isBefore(from) && !e.getEffectiveDate().isAfter(to))
            .map(e -> new InterestCalculator.Movement(e.getEffectiveDate(), e.getAmount())).toList();
        RateSchedule schedule = new RateSchedule(
            rates.findByProductIdAndStatusOrderByEffectiveFrom(account.getProductId(), RateDeclarationStatus.APPROVED.name()),
            productApi.resolveAccumulationPlan(account.getProductVersionId()).guaranteedRatePercent());
        return InterestCalculator.interest(opening, within, from, to, schedule).setScale(2, RoundingMode.HALF_EVEN);
    }
}
```

- [ ] **Step 5: The exhaustion lapse on the policy side**

In `Policy.java`, below `lapse()`:

```java
    /**
     * Whether an exhausted savings account may lapse this policy (spec §10.4). Wider than
     * {@link #canLapse()} on purpose: a REINSTATED or PAID_UP account policy still pays its own
     * fee, so it can still run dry -- and canLapse admits neither.
     */
    public boolean canLapseOnExhaustion() {
        return "ACTIVE".equals(status) || "REINSTATED".equals(status) || "PAID_UP".equals(status)
            || "SUSPENDED".equals(status);
    }

    public void lapseOnExhaustion() {
        if (!canLapseOnExhaustion()) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " cannot lapse on an exhausted account (current: " + status + ")");
        }
        this.status = "LAPSED";
        this.lapsedAt = Instant.now();
    }
```

In `PolicyApi`:

```java
    /**
     * The savings account behind this policy can no longer pay its own fee (product step 3).
     * Publishes the same {@code policy.PolicyLapsed} as an arrears lapse, so every consumer reacts
     * exactly as it already does. Returns false, writing nothing, when the policy is in no state to
     * lapse -- asked rather than thrown, for the rollback-only reason {@code Policy.canLapse} gives.
     */
    boolean lapseExhaustedAccount(String policyNumber, java.time.LocalDate exhaustedOn);
```

In `PolicyApiImpl`:

```java
    @Override
    @Transactional
    public boolean lapseExhaustedAccount(String policyNumber, LocalDate exhaustedOn) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        if (!policy.canLapseOnExhaustion()) {
            return false;
        }
        policy.lapseOnExhaustion();
        policyRepository.save(policy);
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyLapsed", tenantId,
            Map.of("policyNumber", policyNumber, "lapsedAt", policy.getLapsedAt().toString(),
                "reason", "ACCOUNT_EXHAUSTED", "exhaustedOn", exhaustedOn.toString())));
        return true;
    }
```

The two extra keys are additive; no current consumer of `PolicyLapsed` reads a key it does not expect. Add both to `policy.PolicyLapsed` in `api/asyncapi-events.yaml` as optional.

- [ ] **Step 6: Write the failing month-end test**

`MonthEndIntegrationTest`, header as `LedgerServiceIntegrationTest`. The fixture's `issue(...)` takes a commencement in the past, so a test can run several month-ends by passing a `today`.

```java
    private static final UUID TENANT = UUID.randomUUID();

    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private AccumulationApiImpl api;
    @Autowired private PolicyApi policyApi;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }
    private void runMonthEnds(String policyNumber, LocalDate today) {
        asTenant(() -> { api.postMonthEnds(policyNumber, today); return null; });
    }
    private List<LedgerEntryView> entriesOf(String p) { return asTenant(() -> api.entries(p)); }

    /** Commenced on the 1st of the month three months ago, 100,000 paid on day one. */
    private String funded() {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(3);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, start);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("100000.00"), start);
        return issued.policyNumber();
    }

    @Test
    void eachCompletedMonthPostsInterestThenTheFee() {
        String policy = funded();
        runMonthEnds(policy, LocalDate.now());
        List<LedgerEntryView> monthEnds = entriesOf(policy).stream()
            .filter(e -> e.sourceType().equals("month-end")).toList();
        // Three completed months, two entries each.
        assertThat(monthEnds).extracting(LedgerEntryView::type).containsExactly(
            EntryType.INTEREST, EntryType.POLICY_FEE, EntryType.INTEREST, EntryType.POLICY_FEE,
            EntryType.INTEREST, EntryType.POLICY_FEE);
        assertThat(monthEnds).filteredOn(e -> e.type() == EntryType.POLICY_FEE)
            .allSatisfy(e -> assertThat(e.amount()).isEqualByComparingTo("-1000.00"));
        assertThat(monthEnds).filteredOn(e -> e.type() == EntryType.INTEREST)
            .allSatisfy(e -> assertThat(e.amount()).isPositive());
        // Each dated to its own month end.
        assertThat(monthEnds).allSatisfy(e ->
            assertThat(e.effectiveDate()).isEqualTo(e.effectiveDate().withDayOfMonth(e.effectiveDate().lengthOfMonth())));
    }

    @Test
    void runningTheMonthEndTwicePostsOnce() {
        String policy = funded();
        runMonthEnds(policy, LocalDate.now());
        int after = entriesOf(policy).size();
        runMonthEnds(policy, LocalDate.now());
        assertThat(entriesOf(policy)).hasSize(after);
    }

    @Test
    void twoPoliciesInTheSameMonthBothPost() {
        // The source ref names the policy; without it the second account's month would be
        // refused as a duplicate of the first's.
        String a = funded();
        String b = funded();
        runMonthEnds(a, LocalDate.now());
        runMonthEnds(b, LocalDate.now());
        assertThat(entriesOf(b)).anyMatch(e -> e.type() == EntryType.INTEREST);
    }

    @Test
    void aPartFirstMonthChargesNoFee() {
        LocalDate midMonth = LocalDate.now().withDayOfMonth(1).minusMonths(1).withDayOfMonth(15);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, midMonth);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("100000.00"), midMonth);
        runMonthEnds(issued.policyNumber(), LocalDate.now());
        assertThat(entriesOf(issued.policyNumber())).noneMatch(e -> e.type() == EntryType.POLICY_FEE);
    }

    @Test
    void anAccountNeverPaidIntoPostsNothingAndIsNotLapsed() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS,
            LocalDate.now().withDayOfMonth(1).minusMonths(2));
        runMonthEnds(issued.policyNumber(), LocalDate.now());
        assertThat(entriesOf(issued.policyNumber())).isEmpty();
        assertThat(asTenant(() -> policyApi.getPolicy(issued.policyNumber())).status()).isNotEqualTo(PolicyStatus.LAPSED);
    }

    @Test
    void aFeeLargerThanTheBalanceTakesWhatIsThereClosesAndLapses() {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(3);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, start);
        // 1,500 in, 5% allocation: 1,425. One fee of 1,000 leaves ~425 plus interest; the next takes the rest.
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("1500.00"), start);
        runMonthEnds(issued.policyNumber(), LocalDate.now());

        List<LedgerEntryView> entries = entriesOf(issued.policyNumber());
        LedgerEntryView last = entries.get(entries.size() - 1);
        assertThat(last.type()).isEqualTo(EntryType.POLICY_FEE);
        assertThat(last.balanceAfter()).isEqualByComparingTo("0.00");
        // A fee smaller than 1,000 -- it took only what was there.
        assertThat(last.amount().negate()).isLessThan(new BigDecimal("1000.00"));
        // And nothing after it: the closed account is not charged for the third month.
        assertThat(entries.stream().filter(e -> e.type() == EntryType.POLICY_FEE)).hasSize(2);
        assertThat(asTenant(() -> api.findAccount(issued.policyNumber())).orElseThrow().closedReason()).isEqualTo("EXHAUSTED");
        assertThat(asTenant(() -> policyApi.getPolicy(issued.policyNumber())).status()).isEqualTo(PolicyStatus.LAPSED);
    }

    @Test
    void aDeclaredRateAboveTheGuaranteeEarnsMoreThanTheGuarantee() {
        String guaranteed = funded();
        String declared = funded();
        // Approve 8% on the second product, effective long before its first month-end.
        UUID productId = asTenant(() -> api.findAccount(declared)).orElseThrow().productId();
        LocalDate from = LocalDate.now().withDayOfMonth(1).minusMonths(3);
        var proposal = asTenant(() -> api.proposeRate(productId, new BigDecimal("8"), from, "admin-one"));
        asTenant(() -> api.approveRate(proposal.declarationId(), "finance-two"));
        runMonthEnds(guaranteed, LocalDate.now());
        runMonthEnds(declared, LocalDate.now());
        assertThat(interestOf(declared)).isGreaterThan(interestOf(guaranteed));
    }

    private BigDecimal interestOf(String p) {
        return entriesOf(p).stream().filter(e -> e.type() == EntryType.INTEREST)
            .map(LedgerEntryView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void reinstatementReopensAnExhaustedAccount() {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(3);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, start);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("1500.00"), start);
        runMonthEnds(issued.policyNumber(), LocalDate.now());
        fixtures.publish(TENANT, "policy.PolicyReinstated", Map.of("policyNumber", issued.policyNumber(),
            "reinstatedAt", java.time.Instant.now().toString()));
        assertThat(asTenant(() -> api.findAccount(issued.policyNumber())).orElseThrow().status()).isEqualTo(AccountStatus.OPEN);
    }
```

Before writing `reinstatementReopensAnExhaustedAccount`, grep `"policy.PolicyReinstated"` in `PolicyApiImpl` and copy its real payload keys into the fixture call.

`aDeclaredRateAboveTheGuaranteeEarnsMoreThanTheGuarantee` proposes a rate effective before the account's first month-end. That is legal only because no month-end has run yet on that product. Rule 9 is tested on its own in Step 9.

Run: `./mvnw -o test -Dtest=MonthEndIntegrationTest` — Expected: FAIL to compile (`postMonthEnds`, `proposeRate`).

- [ ] **Step 7: The month-end posting**

Add to `AccumulationApiImpl` (inject `AccountValuer valuer` and `RateDeclarationRepository rates` through the constructor):

```java
    /** Policy states that are on cover, and so owe the month's fee (rule 5). */
    private static final Set<PolicyStatus> ON_COVER =
        EnumSet.of(PolicyStatus.ACTIVE, PolicyStatus.REINSTATED, PolicyStatus.PAID_UP, PolicyStatus.SUSPENDED);

    /**
     * Every month that has COMPLETED since the account's last month-end, oldest first, each its own
     * posting -- so a drain that was down for a quarter catches up exactly, and a drain that runs
     * twice posts nothing the second time (the source ref per policy and month).
     */
    @Transactional
    public void postMonthEnds(String policyNumber, LocalDate today) {
        Account account = accounts.lockForPosting(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber));
        LocalDate lastCompleted = today.withDayOfMonth(1).minusDays(1);
        LocalDate previous = account.getLastMonthEnd() != null ? account.getLastMonthEnd()
            : account.getOpenedOn().withDayOfMonth(1).minusDays(1);
        for (LocalDate monthEnd = endOfMonthAfter(previous); !monthEnd.isAfter(lastCompleted);
             monthEnd = endOfMonthAfter(monthEnd)) {
            if (account.status() != AccountStatus.OPEN) {
                return;
            }
            postMonthEnd(account, previous.plusDays(1), monthEnd);
            previous = monthEnd;
        }
    }

    private static LocalDate endOfMonthAfter(LocalDate monthEnd) {
        LocalDate next = monthEnd.plusDays(1);
        return next.withDayOfMonth(next.lengthOfMonth());
    }

    private void postMonthEnd(Account account, LocalDate from, LocalDate monthEnd) {
        String policyNumber = account.getPolicyNumber();
        if (account.getLastSeq() == 0) {
            // Rule 6: never paid into. Nothing to credit, nothing to charge, and nothing to lapse.
            account.monthEndPostedThrough(monthEnd);
            return;
        }
        BigDecimal interest = valuer.interestBetween(account, from, monthEnd);
        PolicyStatus status = policyApi.getPolicy(policyNumber).status();
        boolean wholeMonth = !account.getOpenedOn().isAfter(monthEnd.withDayOfMonth(1));
        BigDecimal fee = wholeMonth && ON_COVER.contains(status)
            ? productApi.resolveAccumulationPlan(account.getProductVersionId())
                .chargesFor(PolicyYears.of(account.getOpenedOn(), monthEnd)).monthlyPolicyFee()
            : BigDecimal.ZERO;
        BigDecimal available = account.getBalance().add(interest);
        BigDecimal taken = fee.min(available);
        boolean exhausted = fee.signum() > 0 && taken.compareTo(available) == 0;

        ledger.post(policyNumber,
            new LedgerService.Source("month-end", "month-end:" + policyNumber + ":" + YearMonth.from(monthEnd)),
            List.of(
                LedgerService.Line.of(EntryType.INTEREST, interest, monthEnd, "Interest " + YearMonth.from(monthEnd)),
                LedgerService.Line.of(EntryType.POLICY_FEE, taken.negate(), monthEnd,
                    exhausted && taken.compareTo(fee) < 0
                        ? "Policy fee " + YearMonth.from(monthEnd) + " (" + taken + " of " + fee + " -- the account is exhausted)"
                        : "Policy fee " + YearMonth.from(monthEnd))),
            "system", null);
        account.monthEndPostedThrough(monthEnd);
        if (exhausted) {
            account.close("EXHAUSTED", monthEnd);
            // Rule 7. False means the policy was already off cover by another route; the account
            // closes either way, because it holds nothing.
            policyApi.lapseExhaustedAccount(policyNumber, monthEnd);
        }
        accounts.save(account);
    }
```

`account` is the managed entity `lockForPosting` returned; `LedgerService.post` locks the same row again in the same transaction and gets the same instance, so the head it advances is the one this method reads next.

Imports to add: `java.time.YearMonth`, `java.util.EnumSet`, `java.util.Set`, `tz.co.nlolo.lifeplatform.policy.api.PolicyStatus`, `tz.co.nlolo.lifeplatform.accumulation.api.AccountStatus`.

Add `reopen()` to `Account`:

```java
    /** A reinstated policy brings an EXHAUSTED account back, at the zero it closed on. */
    public void reopen() {
        if (!"EXHAUSTED".equals(closedReason)) {
            throw new AccumulationStateException("Policy " + policyNumber + "'s account closed on "
                + closedReason + " and cannot reopen");
        }
        this.status = AccountStatus.OPEN.name();
        this.closedReason = null;
        this.closedOn = null;
    }
```

Add to `AccumulationApiImpl`:

```java
    @Transactional
    public void reopenOnReinstatement(String policyNumber) {
        accounts.findById(policyNumber)
            .filter(a -> a.status() == AccountStatus.CLOSED && "EXHAUSTED".equals(a.getClosedReason()))
            .ifPresent(a -> { a.reopen(); accounts.save(a); });
    }
```

Add the case to `accumulation/application/PolicyEventListener`:

```java
            case "policy.PolicyReinstated" -> runner.run(envelope, p ->
                api.reopenOnReinstatement((String) p.get("policyNumber")));
```

- [ ] **Step 8: The drain**

```java
// accumulation/application/MonthEndDrain.java
package tz.co.nlolo.lifeplatform.accumulation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.AccountRepository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Posts each completed month's interest and fee, across every tenant, each under its own.
 *
 * <p>A Spring {@code @Scheduled} rather than pg_cron for {@code PayoutDueDrain}'s reason: exhaustion
 * lapses a policy, and only a published event reaches billing, benefitpayout and audit. Selection is
 * SQL ({@code accounts_due_month_end()}), the posting is Java. Exactly-once comes from the posting
 * index, so two instances draining the same list post each month once. Hourly by default; the
 * {@code local} profile runs it every ten seconds for the e2e suite.
 */
@Component
public class MonthEndDrain {

    private static final Logger log = LoggerFactory.getLogger(MonthEndDrain.class);

    private final AccountRepository accounts;
    private final AccumulationApiImpl api;

    public MonthEndDrain(AccountRepository accounts, AccumulationApiImpl api) {
        this.accounts = accounts;
        this.api = api;
    }

    @Scheduled(fixedDelayString = "${accumulation.month-end-interval-ms:3600000}",
        initialDelayString = "${accumulation.month-end-interval-ms:3600000}")
    public void drain() {
        for (Object[] row : accounts.findDueMonthEndAcrossTenants()) {
            postOne((String) row[0], (UUID) row[1]);
        }
    }

    void postOne(String policyNumber, UUID tenantId) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            api.postMonthEnds(policyNumber, LocalDate.now());
        } catch (Exception e) {
            // One account's failure must not cost every other its interest.
            log.error("Month-end posting failed for policy {} in tenant {}", policyNumber, tenantId, e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
```

Add to `application-local.yml`, beside the benefitpayout intervals:

```yaml
accumulation:
  month-end-interval-ms: 10000
```

Run: `./mvnw -o test -Dtest=MonthEndIntegrationTest` — Expected: FAIL only on `aDeclaredRateAboveTheGuaranteeEarnsMoreThanTheGuarantee` (no `proposeRate` behaviour yet). If the compile fails instead, stub nothing: go on to Step 9 and run both together.

- [ ] **Step 9: Rate declarations — behaviour and its test**

Add to `AccumulationApi`:

```java
    /** ADMIN proposes; a DIFFERENT ADMIN or a FINANCE_OFFICER approves (spec §10.11). */
    RateDeclarationView proposeRate(java.util.UUID productId, java.math.BigDecimal ratePercent,
                                    java.time.LocalDate effectiveFrom, String proposedBy);

    RateDeclarationView approveRate(java.util.UUID declarationId, String approvedBy);

    RateDeclarationView withdrawRate(java.util.UUID declarationId, String withdrawnBy);

    /** Newest effective date first. */
    List<RateDeclarationView> listRates(java.util.UUID productId);
```

Implement in `AccumulationApiImpl`:

```java
    @Override
    @Transactional
    public RateDeclarationView proposeRate(UUID productId, BigDecimal ratePercent, LocalDate effectiveFrom, String proposedBy) {
        if (ratePercent.signum() < 0 || ratePercent.compareTo(HUNDRED) > 0) {
            throw new AccumulationStateException("A declared rate must be between 0% and 100%");
        }
        refuseIfReachingBack(productId, effectiveFrom);
        return toView(rates.save(new RateDeclaration(TenantContext.get(), productId, ratePercent, effectiveFrom, proposedBy)));
    }

    @Override
    @Transactional
    public RateDeclarationView approveRate(UUID declarationId, String approvedBy) {
        RateDeclaration declaration = rates.findById(declarationId)
            .orElseThrow(() -> new AccumulationStateException("No rate declaration " + declarationId));
        // Again at approval: the month-end run may have passed the date since it was proposed.
        refuseIfReachingBack(declaration.getProductId(), declaration.getEffectiveFrom());
        declaration.approve(approvedBy);
        try {
            return toView(rates.saveAndFlush(declaration));
        } catch (DataIntegrityViolationException e) {
            throw new AccumulationStateException("A rate is already approved for this product from "
                + declaration.getEffectiveFrom() + ". Withdrawing an approved rate is not possible; declare a new one "
                + "from a later date.");
        }
    }

    @Override
    @Transactional
    public RateDeclarationView withdrawRate(UUID declarationId, String withdrawnBy) {
        RateDeclaration declaration = rates.findById(declarationId)
            .orElseThrow(() -> new AccumulationStateException("No rate declaration " + declarationId));
        declaration.withdraw();
        return toView(rates.save(declaration));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RateDeclarationView> listRates(UUID productId) {
        return rates.findByProductIdOrderByEffectiveFromDescProposedAtDesc(productId).stream().map(this::toView).toList();
    }

    /** Rule 9: a rate may not rewrite interest a customer has already been credited. */
    private void refuseIfReachingBack(UUID productId, LocalDate effectiveFrom) {
        LocalDate postedThrough = accounts.latestMonthEndForProduct(productId);
        if (postedThrough != null && !effectiveFrom.isAfter(postedThrough)) {
            throw new AccumulationStateException("Interest on this product is already credited up to "
                + postedThrough + ". A rate effective from " + effectiveFrom + " would rewrite it; declare it from "
                + postedThrough.plusDays(1) + " or later.");
        }
    }

    private RateDeclarationView toView(RateDeclaration r) {
        return new RateDeclarationView(r.getDeclarationId(), r.getProductId(), r.getRatePercent(), r.getEffectiveFrom(),
            r.status(), r.getProposedBy(), r.getProposedAt(), r.getApprovedBy(), r.getApprovedAt());
    }
```

`saveAndFlush` in `approveRate` is there so `ux_rate_declaration_effective` fires inside the try block. A plain `save` would flush at commit, outside the catch, and the caller would get a 500.

`RateDeclarationIntegrationTest`, with the same header:

```java
    private static final UUID TENANT = UUID.randomUUID();
    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private AccumulationApiImpl api;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    private UUID productWithMonthEndsThrough(LocalDate start) {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, start);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("100000.00"), start);
        asTenant(() -> { api.postMonthEnds(issued.policyNumber(), LocalDate.now()); return null; });
        return issued.productId();
    }

    @Test
    void theProposerCannotApprove() {
        UUID product = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now()).productId();
        var proposal = asTenant(() -> api.proposeRate(product, new BigDecimal("6"), LocalDate.now().plusMonths(1), "admin-one"));
        assertThatThrownBy(() -> asTenant(() -> api.approveRate(proposal.declarationId(), "admin-one")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessage("A declared rate must be approved by someone other than the person who proposed it");
        assertThat(asTenant(() -> api.approveRate(proposal.declarationId(), "finance-two")).status())
            .isEqualTo(RateDeclarationStatus.APPROVED);
    }

    @Test
    void aRateReachingBackPastPostedInterestIsRefused() {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(2);
        UUID product = productWithMonthEndsThrough(start);
        LocalDate lastPosted = LocalDate.now().withDayOfMonth(1).minusDays(1);
        assertThatThrownBy(() -> asTenant(() -> api.proposeRate(product, new BigDecimal("6"), lastPosted, "admin-one")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessageContaining("already credited up to " + lastPosted);
        assertThat(asTenant(() -> api.proposeRate(product, new BigDecimal("6"), lastPosted.plusDays(1), "admin-one")))
            .isNotNull();
    }

    @Test
    void aRateProposedInTimeButApprovedTooLateIsRefusedAtApproval() {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(2);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, start);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("100000.00"), start);
        // Proposed while nothing is posted...
        var proposal = asTenant(() -> api.proposeRate(issued.productId(), new BigDecimal("6"), start.plusDays(1), "admin-one"));
        // ...then the month-end runs before anyone approves it.
        asTenant(() -> { api.postMonthEnds(issued.policyNumber(), LocalDate.now()); return null; });
        assertThatThrownBy(() -> asTenant(() -> api.approveRate(proposal.declarationId(), "finance-two")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessageContaining("would rewrite it");
    }

    @Test
    void anApprovedRateCannotBeWithdrawn() {
        UUID product = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now()).productId();
        var proposal = asTenant(() -> api.proposeRate(product, new BigDecimal("6"), LocalDate.now().plusMonths(1), "admin-one"));
        asTenant(() -> api.approveRate(proposal.declarationId(), "finance-two"));
        assertThatThrownBy(() -> asTenant(() -> api.withdrawRate(proposal.declarationId(), "admin-one")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessage("This rate declaration is approved, not awaiting approval");
    }

    @Test
    void twoApprovedRatesOnOneDayAreRefusedByName() {
        UUID product = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now()).productId();
        LocalDate day = LocalDate.now().plusMonths(1);
        var first = asTenant(() -> api.proposeRate(product, new BigDecimal("6"), day, "admin-one"));
        var second = asTenant(() -> api.proposeRate(product, new BigDecimal("7"), day, "admin-one"));
        asTenant(() -> api.approveRate(first.declarationId(), "finance-two"));
        assertThatThrownBy(() -> asTenant(() -> api.approveRate(second.declarationId(), "finance-two")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessageContaining("already approved for this product from " + day);
    }
```

Run: `./mvnw -o test -Dtest='MonthEndIntegrationTest,RateDeclarationIntegrationTest'` — Expected: PASS, 13 tests.

- [ ] **Step 10: The REST surface and its spec**

```java
// accumulation/infrastructure/RateDeclarationRequest.java
package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RateDeclarationRequest(
    @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal ratePercent,
    @NotNull LocalDate effectiveFrom) {}
```

```java
// accumulation/infrastructure/RateDeclarationResponse.java
package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import tz.co.nlolo.lifeplatform.accumulation.api.RateDeclarationView;

public record RateDeclarationResponse(String declarationId, String productId, String ratePercent, String effectiveFrom,
                                      String status, String proposedBy, String proposedAt, String approvedBy,
                                      String approvedAt) {
    static RateDeclarationResponse from(RateDeclarationView v) {
        return new RateDeclarationResponse(v.declarationId().toString(), v.productId().toString(),
            v.ratePercent().toPlainString(), v.effectiveFrom().toString(), v.status().name(), v.proposedBy(),
            v.proposedAt().toString(), v.approvedBy(), v.approvedAt() != null ? v.approvedAt().toString() : null);
    }
}
```

```java
// accumulation/infrastructure/AccumulationController.java
package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationApi;

import java.util.List;
import java.util.UUID;

@RestController
public class AccumulationController {

    /** Setting a price is ADMIN's, as publishing a product version is. */
    static final String PRICING = "hasRole('REALM_STAFF') and hasRole('ADMIN')";
    /** The second signature on money: another ADMIN, or finance. */
    static final String FINANCE = "hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))";

    private final AccumulationApi api;

    public AccumulationController(AccumulationApi api) {
        this.api = api;
    }

    @GetMapping("/products/{productId}/rate-declarations")
    @PreAuthorize(FINANCE)
    public List<RateDeclarationResponse> listRates(@PathVariable UUID productId) {
        return api.listRates(productId).stream().map(RateDeclarationResponse::from).toList();
    }

    @PostMapping("/products/{productId}/rate-declarations")
    @PreAuthorize(PRICING)
    @ResponseStatus(HttpStatus.CREATED)
    public RateDeclarationResponse proposeRate(@PathVariable UUID productId, @Valid @RequestBody RateDeclarationRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        return RateDeclarationResponse.from(api.proposeRate(productId, request.ratePercent(), request.effectiveFrom(),
            jwt.getSubject()));
    }

    @PostMapping("/rate-declarations/{declarationId}/approve")
    @PreAuthorize(FINANCE)
    public RateDeclarationResponse approveRate(@PathVariable UUID declarationId, @AuthenticationPrincipal Jwt jwt) {
        return RateDeclarationResponse.from(api.approveRate(declarationId, jwt.getSubject()));
    }

    @PostMapping("/rate-declarations/{declarationId}/withdraw")
    @PreAuthorize(PRICING)
    public RateDeclarationResponse withdrawRate(@PathVariable UUID declarationId, @AuthenticationPrincipal Jwt jwt) {
        return RateDeclarationResponse.from(api.withdrawRate(declarationId, jwt.getSubject()));
    }
}
```

Before writing `PRICING`, check which role `ProductController`'s publish endpoint requires and use the same expression. If it is not ADMIN-only, follow the code and note the difference against spec §10.11 in the commit message.

`api/openapi/openapi-accumulation.yaml`: copy the header block, `servers`, and the `staffAuth` security scheme reference from `openapi-benefitpayout.yaml`. Then add:
- the four paths above, with `'201'`/`'200'`, `'403'` and `'422'` (`$ref: 'openapi-common.yaml#/components/responses/...'`, matching whatever names benefitpayout's spec uses for Forbidden and the 422 problem)
- `components.schemas.RateDeclaration`: every field of `RateDeclarationResponse`. `ratePercent` is a decimal string. `status` is an enum of the three values. `approvedBy` and `approvedAt` are `type: [string, 'null']`.
- `RateDeclarationRequest`: `ratePercent` is a `number`, 0–100. `effectiveFrom` is `format: date`. Both are required.

`AccumulationContractTest`: header copied from `BenefitPayoutContractTest`, with `SPEC_PATH = "api/openapi/openapi-accumulation.yaml"`, `@Import(AccumulationTestFixtures.class)`, the accumulation migration list, and its `staff(...)`/`jwt` request post-processor helpers. Tests:

```java
    @Test
    void aRateIsProposedApprovedAndListedToSpec() throws Exception {
        UUID product = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now()).productId();
        String body = "{\"ratePercent\": 6.5, \"effectiveFrom\": \"" + LocalDate.now().plusMonths(1) + "\"}";
        String created = mockMvc.perform(post("/products/" + product + "/rate-declarations")
                .with(staff("admin-one", "ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.declarationId");

        mockMvc.perform(post("/rate-declarations/" + id + "/approve").with(staff("finance-two", "FINANCE_OFFICER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        mockMvc.perform(get("/products/" + product + "/rate-declarations").with(staff("finance-two", "FINANCE_OFFICER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].ratePercent").value("6.5"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void theProposerApprovingIsA422InTheServersWords() throws Exception {
        UUID product = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now()).productId();
        String body = "{\"ratePercent\": 6, \"effectiveFrom\": \"" + LocalDate.now().plusMonths(1) + "\"}";
        String id = JsonPath.read(mockMvc.perform(post("/products/" + product + "/rate-declarations")
                .with(staff("admin-one", "ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getContentAsString(), "$.declarationId");
        mockMvc.perform(post("/rate-declarations/" + id + "/approve").with(staff("admin-one", "ADMIN")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("ACCUMULATION_REFUSED"))
            .andExpect(jsonPath("$.detail").value("A declared rate must be approved by someone other than the person who proposed it"));
    }

    @Test
    void anUnderwriterCannotProposeOrApprove() throws Exception {
        UUID product = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now()).productId();
        // A body that WOULD succeed for an admin, so the 403 is the role gate and nothing else.
        String body = "{\"ratePercent\": 6, \"effectiveFrom\": \"" + LocalDate.now().plusMonths(1) + "\"}";
        mockMvc.perform(post("/products/" + product + "/rate-declarations")
                .with(staff("uw", "UNDERWRITER")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
    }
```

`anUnderwriterCannotProposeOrApprove` uses a body valid for an admin on purpose: a 403 test with an invalid body proves nothing (see the vacuous-verification note).

Run: `./mvnw -o test -Dtest='AccumulationContractTest,ModularityTests'` — Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/accumulation src/main/java/tz/co/nlolo/lifeplatform/policy src/test/java/tz/co/nlolo/lifeplatform/accumulation api/openapi/openapi-accumulation.yaml api/asyncapi-events.yaml src/main/resources/application-local.yml
git commit -m "feat(accumulation): daily-compounded interest posted monthly, the policy fee, two-person rate declarations, and exhaustion"
```

---

### Task 5: A missed contribution does not lapse an account policy; paid-up keeps the account

**Files:**
- Modify: `policy/application/PolicyLapseRecommendedEventListener.java`
- Modify: `policy/application/PolicyApiImpl.java` (`makePaidUp`, a new private `makeAccountPaidUp`)
- Modify: `benefitpayout/application/BenefitPayoutApiImpl.java` (`restateForPaidUp`)
- Test: `src/test/java/tz/co/nlolo/lifeplatform/accumulation/AccountPolicyLifecycleIntegrationTest.java`

**Interfaces:**
- Consumes: `ProductApi.resolveAccumulationPlan(UUID).isAccount()` (Task 1), the account and contributions (Tasks 2–3)
- Produces: no new signatures. Behaviour:
  - `billing.PolicyLapseRecommended` on an ACCOUNT version → logged at INFO, policy unchanged (spec Q6 and §10.2)
  - `makePaidUp` on an ACCOUNT version: allowed from ACTIVE or REINSTATED only; no sum-assured cut; an endorsement with `basis: "ACCOUNT"`; `policy.PolicyMadePaidUp` with `paidUpSumAssured == originalSumAssured`
  - `restateForPaidUp` is a no-op when the two figures are equal

Why only ACTIVE or REINSTATED, when step 1 also admits LAPSED: an ACCOUNT policy lapses only on exhaustion, and its account is then closed at zero. Making it paid-up would put cover back on with nothing to pay its fee. The way back for an exhausted account is reinstatement, which reopens it (Task 4).

- [ ] **Step 1: Write the failing tests**

`AccountPolicyLifecycleIntegrationTest`. The header is the same as `LedgerServiceIntegrationTest`, and the migration list also needs the benefitpayout migrations. Copy them from `PayoutPaymentEndToEndTest`, which the list is already based on.

```java
    private static final UUID TENANT = UUID.randomUUID();
    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private PolicyApi policyApi;
    @Autowired private AccumulationApi api;
    @Autowired private BenefitPayoutApi benefitPayoutApi;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    @Test
    void billingsLapseRecommendationDoesNotLapseAnAccountPolicy() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now().minusMonths(4));
        fixtures.publish(TENANT, "billing.PolicyLapseRecommended", Map.of("policyNumber", issued.policyNumber()));
        // The account pays its own fee; the policy lapses only when it cannot (Task 4's exhaustion).
        assertThat(asTenant(() -> policyApi.getPolicy(issued.policyNumber())).status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void billingsLapseRecommendationStillLapsesAScalePolicy() {
        // The control: without it, the test above passes just as well if the listener never ran.
        var scale = fixtures.issueSavingsPlan(TENANT, AccumulationPlan.none(), LocalDate.now().minusMonths(4));
        fixtures.publish(TENANT, "billing.PolicyLapseRecommended", Map.of("policyNumber", scale.policyNumber()));
        assertThat(asTenant(() -> policyApi.getPolicy(scale.policyNumber())).status()).isEqualTo(PolicyStatus.LAPSED);
    }

    @Test
    void aCollectedPremiumLeavesTheAccountProjectionAlone() {
        // Step 1's recalculateCashValue returns early for a version with no cash-value scale, and
        // an ACCOUNT version can never carry one (Task 1's validator). Pinned here so a later
        // change to that early return cannot silently overwrite the ledger's projection.
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("50000.00"), LocalDate.now());
        assertThat(asTenant(() -> policyApi.getCashValue(issued.policyNumber())).cashValueAmount())
            .isEqualByComparingTo("47500.00");
    }

    @Test
    void paidUpOnAnAccountPolicyKeepsTheSumAssuredAndTheAccount() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now().minusYears(3));
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("50000.00"), LocalDate.now());
        BigDecimal before = asTenant(() -> policyApi.getPolicy(issued.policyNumber())).sumAssuredAmount();

        PolicyView paidUp = asTenant(() -> policyApi.makePaidUp(issued.policyNumber(), "staff-one"));

        assertThat(paidUp.status()).isEqualTo(PolicyStatus.PAID_UP);
        assertThat(paidUp.sumAssuredAmount()).isEqualByComparingTo(before);
        assertThat(asTenant(() -> api.findAccount(issued.policyNumber())).orElseThrow().status()).isEqualTo(AccountStatus.OPEN);
    }

    @Test
    void paidUpOnAnAccountPolicyDoesNotRestateItsMaturityPayout() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now().minusYears(3));
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("50000.00"), LocalDate.now());
        asTenant(() -> policyApi.makePaidUp(issued.policyNumber(), "staff-one"));
        // A restatement at a ratio of one would write "Made paid-up: X of X" onto every row and
        // tell finance a payout was changed when it was not.
        assertThat(asTenant(() -> benefitPayoutApi.listForPolicy(issued.policyNumber())))
            .allSatisfy(i -> assertThat(i.restatementReason()).isNull());
    }

    @Test
    void aLapsedAccountPolicyCannotBeMadePaidUp() {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(3);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, start);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("1500.00"), start);
        asTenant(() -> { ((AccumulationApiImpl) api).postMonthEnds(issued.policyNumber(), LocalDate.now()); return null; });
        assertThatThrownBy(() -> asTenant(() -> policyApi.makePaidUp(issued.policyNumber(), "staff-one")))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessage("Policy " + issued.policyNumber() + " is valued by its account; it can be made paid-up only "
                + "while ACTIVE or REINSTATED. An exhausted account comes back by reinstatement.");
    }
```

Check `PayoutInstalmentView`'s accessor name for the restatement reason before writing `paidUpOnAnAccountPolicyDoesNotRestateItsMaturityPayout`. If the view does not expose it, assert `amount()` is null instead: an `ACCOUNT_VALUE` row has no amount until it falls due (Task 7). Note that this test can only fail once Task 7 expands the row. Until then the schedule is empty and `allSatisfy` passes vacuously. Re-run it at the end of Task 7.

`AccumulationApi` is the interface. Casting it to `AccumulationApiImpl` works only because the bean is not proxied through an interface-based JDK proxy. If the cast fails, autowire `AccumulationApiImpl` directly, as `MonthEndIntegrationTest` does.

Run: `./mvnw -o test -Dtest=AccountPolicyLifecycleIntegrationTest`. Expected: `billingsLapseRecommendationDoesNotLapseAnAccountPolicy`, `paidUpOnAnAccountPolicyKeepsTheSumAssuredAndTheAccount` and `aLapsedAccountPolicyCannotBeMadePaidUp` FAIL. The pin and the control pass already; both are guards, not new behaviour.

- [ ] **Step 2: The lapse exception**

In `PolicyLapseRecommendedEventListener`, inject `ProductApi productApi` beside `PolicyApi`. Then, inside the `REQUIRES_NEW` block, before `lapsePolicy`:

```java
            requiresNewTransactionTemplate.executeWithoutResult(status -> {
                // An ACCOUNT-valued policy is not lapsed for a missed contribution (product step 3,
                // decision Q6): its account keeps paying its own fee, and it lapses only when it
                // cannot -- accumulation's exhaustion, through lapseExhaustedAccount. Billing still
                // raises the invoice and still reminds the customer; it is only the lapse it
                // recommends that does not apply.
                UUID versionId = policyApi.getPolicy(policyNumber).productVersionId();
                if (productApi.resolveAccumulationPlan(versionId).isAccount()) {
                    log.info("Not lapsing policy {} on billing's recommendation: it is valued by its account", policyNumber);
                    return;
                }
                policyApi.lapsePolicy(policyNumber, "system:billing-lapse-recommendation");
            });
```

Update the class javadoc. It currently says the listener "does not re-derive eligibility". Add one sentence: it does not, except that an account-valued policy is never lapsed by arrears.

- [ ] **Step 3: Paid-up on an ACCOUNT version**

At the top of `makePaidUp`, after `findPolicyOrThrow` and before the cash-value config check:

```java
        if (productApi.resolveAccumulationPlan(policy.getProductVersionId()).isAccount()) {
            return makeAccountPaidUp(policy, madePaidUpBy, tenantId);
        }
```

```java
    /**
     * Paid-up on an account-valued version (product step 3): premiums stop and the account keeps
     * paying its own fee. There is NO sum-assured reduction -- the account is the value, and it is
     * not reduced by stopping contributions to it.
     *
     * <p>{@code PolicyMadePaidUp} is published with both figures EQUAL, which every consumer already
     * reads correctly: billing stops raising invoices, and benefitpayout's restatement is a ratio of
     * one, which it skips.
     */
    private PolicyView makeAccountPaidUp(Policy policy, String madePaidUpBy, UUID tenantId) {
        String policyNumber = policy.getPolicyNumber();
        PolicyStatus status = PolicyStatus.valueOf(policy.getStatus());
        if (status != PolicyStatus.ACTIVE && status != PolicyStatus.REINSTATED) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " is valued by its account; it can be "
                + "made paid-up only while ACTIVE or REINSTATED. An exhausted account comes back by reinstatement.");
        }
        BigDecimal sumAssured = policy.getSumAssuredAmount();
        endorsementRepository.save(new Endorsement(tenantId, policyNumber, "PAID_UP", LocalDate.now(),
            Map.of("originalSumAssured", sumAssured.toPlainString(),
                   "paidUpSumAssured", sumAssured.toPlainString(),
                   "basis", "ACCOUNT"),
            madePaidUpBy));
        policy.makePaidUp(sumAssured);
        policyRepository.save(policy);
        Map<String, Object> money = Map.of("amount", sumAssured.toPlainString(), "currencyCode", policy.getSumAssuredCurrency());
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyMadePaidUp", tenantId,
            Map.of("policyNumber", policyNumber,
                   "paidUpSumAssured", money,
                   "originalSumAssured", money,
                   "madePaidUpAt", Instant.now().toString())));
        return toView(policy);
    }
```

Before writing this, read `Policy.makePaidUp(BigDecimal)`. If it does more than set the status and the sum assured (for example, if it checks the sum went down), follow it. If it refuses an unchanged figure, add a `makePaidUpKeepingSumAssured()` beside it rather than weakening that check.

- [ ] **Step 4: The restatement guard**

At the top of `BenefitPayoutApiImpl.restateForPaidUp`:

```java
        if (paidUpSa.compareTo(originalSa) == 0) {
            // Nothing was reduced -- an account-valued policy made paid-up (product step 3). A
            // restatement at a ratio of one would record a change that never happened.
            return;
        }
```

Run: `./mvnw -o test -Dtest='AccountPolicyLifecycleIntegrationTest,ModularityTests'` — Expected: PASS. Then run the existing paid-up tests, to show the scale path is unchanged: `./mvnw -o test -Dtest='*PaidUp*'`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/policy src/main/java/tz/co/nlolo/lifeplatform/benefitpayout src/test/java/tz/co/nlolo/lifeplatform/accumulation
git commit -m "feat(policy): an account-valued policy is not lapsed for arrears, and paid-up keeps its account"
```

---

### Task 6: Withdrawals, transfers in, and top-ups

**Files:**
- Create: `db-migrations/payment/V9__account_purposes.sql`
- Create: `accumulation/domain/WithdrawalRequest.java`, `TopUpRequest.java`, `TransferIn.java` with repositories in `infrastructure/`
- Create: `accumulation/api/WithdrawalView.java`, `TopUpView.java`, `TransferInView.java`
- Create: `accumulation/application/PaymentEventListener.java`
- Create: `accumulation/infrastructure/WithdrawalRequestBody.java`, `TopUpRequestBody.java`, `TransferInRequestBody.java` and their `*Response` records
- Modify: `AccumulationApi.java`, `AccumulationApiImpl.java`, `AccumulationController.java`, `openapi-accumulation.yaml`, `AccumulationContractTest.java`
- Modify: `payment/application/PaymentRequestListener.java` (two cases), `payment/application/PaymentApiImpl.java` (`recordCollectionRequest` purpose overload; `purpose` on `PaymentConfirmed` and `PaymentFailed`), `payment/domain/PaymentTransaction.java` (`purpose`)
- Modify: `billing/application/PaymentEventListener.java` (ignore non-premium collections)
- Modify: `api/asyncapi-events.yaml`
- Test: `WithdrawalIntegrationTest.java`, `TopUpAndTransferIntegrationTest.java`

**Interfaces:**
- Consumes: `LedgerService.post`, `Account.requireOpen`, `AccumulationPlan.minimumBalance()` / `chargesFor(int).transferAllocationPercent()`, `PolicyApi.getCashValue(..).loanEncumbranceAmount()`
- Produces:
  - `AccumulationApi.requestWithdrawal(String policyNumber, BigDecimal amount, String payeeRef, String requestedBy) -> WithdrawalView`, `approveWithdrawal(UUID withdrawalId, String approvedBy) -> WithdrawalView`, `listWithdrawals(String policyNumber)`
  - `AccumulationApi.requestTopUp(String policyNumber, BigDecimal amount, String payerRef, String requestedBy) -> TopUpView`, `listTopUps(String policyNumber)`
  - `AccumulationApi.recordTransferIn(String policyNumber, BigDecimal amount, String sourceScheme, String documentRef, String recordedBy) -> TransferInView`
  - Event `accumulation.PayoutRequested {purpose, sourceRef, idempotencyKey, policyNumber, payeeRef, amount{amount,currencyCode}}`. Payment handles it exactly as `benefitpayout.PayoutRequested`, with the source ref taken from `sourceRef`. Task 7 reuses it for a surrender.
  - Event `accumulation.TopUpRequested {topUpId, idempotencyKey, policyNumber, payerRef, amount}`. Payment records a collection with purpose `ACCOUNT_TOP_UP` and the source ref `topUpId`.
  - `payment.PaymentConfirmed` and `payment.PaymentFailed` gain `purpose`
  - Source refs: `withdrawal:<id>`, `withdrawal-failed:<id>` (the reversal), `topup:<id>`, `transfer:<id>`

**The rules:**
1. A withdrawal is valued at APPROVAL, not at request. `available = balance − loan lien`, and the withdrawal must leave `available − amount ≥ minimumBalance`. Both are checked again at approval, because a month-end fee can land in between.
2. The money leaves the account at approval: the `WITHDRAWAL` entry is posted then. A failed disbursement posts a `REVERSAL` of that entry, dated the day it failed. The original entry is never edited (spec §9). Posting at approval rather than on payment is what stops two withdrawals, or a withdrawal and a surrender, from both spending the same balance.
3. A transfer in is credited at the TRANSFER allocation rate. A top-up is credited at the CONTRIBUTION rate, the same as a premium. Both are dated the day the money is confirmed.
4. One withdrawal in flight per account (`ux_withdrawal_live`), and two people (the CHECK and the aggregate).

- [ ] **Step 1: Payment V9**

```sql
-- db-migrations/payment/V9__account_purposes.sql
-- Product step 3: money moving in and out of a savings account.
--
-- A collection has had no purpose until now, because every collection was a premium and billing
-- parsed every confirmed sourceRef as an invoice id. A top-up is a collection that is NOT a
-- premium, so collections gain the purpose disbursements have always had. Defaulted to PREMIUM:
-- every existing row, and every collection billing requests, keeps its meaning.
--
-- payment_transaction is partitioned; a column with a constant default added on the parent
-- propagates to every partition, as V2's CHECKs do.
ALTER TABLE payment.payment_transaction
    ADD COLUMN purpose VARCHAR(20) NOT NULL DEFAULT 'PREMIUM'
        CONSTRAINT payment_transaction_purpose_check CHECK (purpose IN ('PREMIUM','ACCOUNT_TOP_UP'));

ALTER TABLE payment.disbursement_instruction
    DROP CONSTRAINT disbursement_instruction_purpose_check;
ALTER TABLE payment.disbursement_instruction
    ADD CONSTRAINT disbursement_instruction_purpose_check CHECK (purpose IN
        ('LOAN_DISBURSEMENT','CLAIM_SETTLEMENT','COMMISSION_PAYOUT','SURRENDER_PAYOUT',
         'MATURITY_PAYOUT','DIVIDEND_PAYOUT',
         'SURVIVAL_BENEFIT_PAYOUT','INCOME_PAYOUT','PREMIUM_RETURN_PAYOUT','FREE_LOOK_REFUND',
         -- Step 3: a partial withdrawal from a savings account.
         'WITHDRAWAL_PAYOUT'));
```

Before applying it, check that the partitioned parent accepts `ADD COLUMN ... DEFAULT ... CHECK` in one statement. Run it in `psql` against a scratch copy of the dev schema, or just run Step 9's tests. If Postgres refuses, split it into `ADD COLUMN` and then `ADD CONSTRAINT`.

Add `db-migrations/payment/V9__account_purposes.sql` to the migration lists of every test class that already lists `payment/V8`. Find them with `grep -rl "payment/V8__" src/test/java`. Hibernate maps `PaymentTransaction.purpose` with `ddl-auto: none`, so a test database without the column fails on every payment query, not just the new ones.

- [ ] **Step 2: Payment carries the purpose**

`PaymentTransaction`: add `@Column(nullable = false) private String purpose = "PREMIUM";`, a getter, and a constructor overload taking `purpose` (the existing constructor delegates with `"PREMIUM"`).

`PaymentApiImpl`: overload `recordCollectionRequest(..., String sourceRef, String purpose)`; the existing signature delegates with `"PREMIUM"`. `confirmCollection` and `failCollection` (the two methods publishing at lines 371 and 390) add `"purpose", transaction.getPurpose()` to their payloads. `Map.of` takes ten pairs at most; `PaymentConfirmed` goes from six to seven, so it still fits.

`PaymentRequestListener`, two new cases:

```java
            case "accumulation.PayoutRequested" -> withTenant(envelope, this::handleAccountPayout);
            case "accumulation.TopUpRequested" -> withTenant(envelope, this::handleTopUpCollection);
```

```java
    /**
     * Money out of a savings account (product step 3): a partial withdrawal, or a surrender valued
     * by the account. The twin of {@link #handleBenefitPayout} -- the publisher names the purpose
     * and the source reference, so a surrender's disbursement still carries the surrender request id
     * policy's SurrenderPaymentListener marks PAID.
     */
    private void handleAccountPayout(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String idempotencyKey = requireKey(payload);
        String purpose = (String) payload.get("purpose");
        String sourceRef = (String) payload.get("sourceRef");
        String payeeRef = (String) payload.get("payeeRef");
        Money money = money(payload);
        Optional<UUID> disbursementId = requiresNewTransactionTemplate.execute(status -> paymentApiImpl.recordDisbursementRequest(
            tenantId, idempotencyKey, payeeRef, money.amount(), money.currency(), purpose, sourceRef));
        disbursementId.ifPresentOrElse(
            id -> submitDisbursement(tenantId, id, payeeRef, money),
            () -> log.info("Dropping duplicate accumulation.PayoutRequested for tenant {} key {}", tenantId, idempotencyKey));
    }

    /** A top-up: a collection that is not a premium, so billing will ignore its confirmation. */
    private void handleTopUpCollection(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String idempotencyKey = requireKey(payload);
        String topUpId = String.valueOf(payload.get("topUpId"));
        String payerRef = (String) payload.get("payerRef");
        Money money = money(payload);
        Optional<UUID> transactionId = requiresNewTransactionTemplate.execute(status -> paymentApiImpl.recordCollectionRequest(
            tenantId, idempotencyKey, payerRef, money.amount(), money.currency(), topUpId, "ACCOUNT_TOP_UP"));
        transactionId.ifPresentOrElse(
            id -> submitCollection(tenantId, id, payerRef, money),
            () -> log.info("Dropping duplicate accumulation.TopUpRequested for tenant {} key {}", tenantId, idempotencyKey));
    }
```

- [ ] **Step 3: Billing ignores a collection that is not a premium**

In `billing/application/PaymentEventListener`, at the top of `handleConfirmed` and of the failed handler:

```java
        // A top-up is a collection too (product step 3), and its sourceRef is a top-up id, not an
        // invoice. Absent means PREMIUM: every confirmation published before payment V9.
        Object purpose = payload.get("purpose");
        if (purpose != null && !"PREMIUM".equals(purpose)) {
            return;
        }
```

Read both handlers first. If either already wraps `UUID.fromString(sourceRef)` in a try/catch that logs "not an invoice", the guard still belongs above it: the confirmation is not unparseable, it is simply not billing's.

- [ ] **Step 4: Write the failing withdrawal test**

`WithdrawalIntegrationTest`. The header is the same as `ContributionIntegrationTest`, plus `payment/V9`. The mock gateway accepts by default; check `PayoutPaymentEndToEndTest` for how it forces a decline and do the same in `aFailedDisbursementIsReversedNotEdited`.

```java
    private static final UUID TENANT = UUID.randomUUID();
    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private AccumulationApi api;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    /** 200,000 in, 5% allocation: 190,000 in the account. Minimum balance 50,000. */
    private String funded() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("200000.00"), LocalDate.now());
        return issued.policyNumber();
    }

    @Test
    void aWithdrawalNeedsTwoPeopleAndLeavesTheAccountOnApproval() {
        String policy = funded();
        var requested = asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("40000.00"), "+255700000001", "staff-one"));
        assertThat(asTenant(() -> api.findAccount(policy)).orElseThrow().balance()).isEqualByComparingTo("190000.00");

        assertThatThrownBy(() -> asTenant(() -> api.approveWithdrawal(requested.withdrawalId(), "staff-one")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessage("A withdrawal must be approved by someone other than the person who requested it");

        asTenant(() -> api.approveWithdrawal(requested.withdrawalId(), "finance-two"));
        assertThat(asTenant(() -> api.findAccount(policy)).orElseThrow().balance()).isEqualByComparingTo("150000.00");
        assertThat(asTenant(() -> api.entries(policy))).last()
            .satisfies(e -> {
                assertThat(e.type()).isEqualTo(EntryType.WITHDRAWAL);
                assertThat(e.approvedBy()).isEqualTo("finance-two");
            });
    }

    @Test
    void aWithdrawalBelowTheMinimumBalanceIsRefusedByName() {
        String policy = funded();
        assertThatThrownBy(() -> asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("140000.01"), "+255700000001", "staff-one")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessage("A withdrawal of 140000.01 would leave 49999.99, below this product's minimum balance of 50000.00. "
                + "The most that can be withdrawn is 140000.00.");
        assertThat(asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("140000.00"), "+255700000001", "staff-one")))
            .isNotNull();
    }

    @Test
    void aLoanLienReducesWhatCanBeWithdrawn() {
        String policy = funded();
        // Through the real policyloan path, not a hand-set encumbrance: copy how
        // PolicyLoanIntegrationTest originates a loan, for 30,000 against this policy.
        originateLoan(policy, new BigDecimal("30000.00"));
        assertThatThrownBy(() -> asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("110000.01"), "+255700000001", "staff-one")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessageContaining("The most that can be withdrawn is 110000.00");
    }

    @Test
    void aFailedDisbursementIsReversedNotEdited() {
        String policy = funded();
        forceTheGatewayToDecline();
        var requested = asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("40000.00"), "+255700000001", "staff-one"));
        asTenant(() -> api.approveWithdrawal(requested.withdrawalId(), "finance-two"));

        List<LedgerEntryView> entries = asTenant(() -> api.entries(policy));
        LedgerEntryView withdrawal = entries.stream().filter(e -> e.type() == EntryType.WITHDRAWAL).findFirst().orElseThrow();
        LedgerEntryView reversal = entries.get(entries.size() - 1);
        assertThat(reversal.type()).isEqualTo(EntryType.REVERSAL);
        assertThat(reversal.reversesEntryId()).isEqualTo(withdrawal.entryId());
        assertThat(reversal.amount()).isEqualByComparingTo("40000.00");
        // The original is still there, unchanged.
        assertThat(withdrawal.amount()).isEqualByComparingTo("-40000.00");
        assertThat(asTenant(() -> api.findAccount(policy)).orElseThrow().balance()).isEqualByComparingTo("190000.00");
        assertThat(asTenant(() -> api.listWithdrawals(policy))).first()
            .extracting(WithdrawalView::status).isEqualTo("FAILED");
    }

    @Test
    void aSecondWithdrawalWhileOneIsInFlightIsRefused() {
        String policy = funded();
        asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("10000.00"), "+255700000001", "staff-one"));
        assertThatThrownBy(() -> asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("10000.00"), "+255700000001", "staff-one")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessage("A withdrawal is already in flight on policy " + policy);
    }

    @Test
    void aSuccessfulDisbursementMarksTheWithdrawalPaid() {
        String policy = funded();
        var requested = asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("40000.00"), "+255700000001", "staff-one"));
        asTenant(() -> api.approveWithdrawal(requested.withdrawalId(), "finance-two"));
        assertThat(asTenant(() -> api.listWithdrawals(policy))).first()
            .extracting(WithdrawalView::status).isEqualTo("PAID");
    }
```

Write `originateLoan` and `forceTheGatewayToDecline` as private helpers. Copy their bodies from the existing tests named in the comments. Do not hand-set `loan_encumbrance_amount` through JDBC: that is the synthetic-state trap, where a test proves the code reads a column without proving anything ever writes it.

Run: `./mvnw -o test -Dtest=WithdrawalIntegrationTest` — Expected: FAIL to compile.

- [ ] **Step 5: The withdrawal aggregate and its behaviour**

```java
// accumulation/domain/WithdrawalRequest.java
package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationStateException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** REQUESTED -> APPROVED -> PAID | FAILED. The money leaves the account at APPROVED (rule 2). */
@Entity
@Table(name = "withdrawal_request", schema = "accumulation")
public class WithdrawalRequest {
    @Id @UuidGenerator @Column(name = "withdrawal_id") private UUID withdrawalId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private BigDecimal amount;
    @Column(nullable = false) private String currency;
    @Column(name = "payee_ref", nullable = false) private String payeeRef;
    @Column(nullable = false) private String status = "REQUESTED";
    @Column(name = "requested_by", nullable = false) private String requestedBy;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt = Instant.now();
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "disbursement_id") private UUID disbursementId;
    @Version private long version;

    protected WithdrawalRequest() {}

    public WithdrawalRequest(UUID tenantId, String policyNumber, BigDecimal amount, String currency, String payeeRef,
                             String requestedBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.amount = amount;
        this.currency = currency;
        this.payeeRef = payeeRef;
        this.requestedBy = requestedBy;
    }

    public void approve(String by) {
        requireStatus("REQUESTED", "approved");
        if (by.equals(requestedBy)) {
            throw new AccumulationStateException("A withdrawal must be approved by someone other than the person who requested it");
        }
        this.status = "APPROVED";
        this.approvedBy = by;
        this.approvedAt = Instant.now();
    }

    /** False when already settled -- a redelivered disbursement outcome changes nothing. */
    public boolean markPaid(UUID disbursementId) {
        if (!"APPROVED".equals(status)) return false;
        this.status = "PAID";
        this.disbursementId = disbursementId;
        return true;
    }

    public boolean markFailed(UUID disbursementId) {
        if (!"APPROVED".equals(status)) return false;
        this.status = "FAILED";
        this.disbursementId = disbursementId;
        return true;
    }

    private void requireStatus(String expected, String verb) {
        if (!expected.equals(status)) {
            throw new AccumulationStateException("This withdrawal is " + status.toLowerCase() + " and cannot be " + verb);
        }
    }

    public UUID getWithdrawalId() { return withdrawalId; }
    public String getPolicyNumber() { return policyNumber; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getPayeeRef() { return payeeRef; }
    public String getStatus() { return status; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
}
```

```java
// accumulation/infrastructure/WithdrawalRequestRepository.java
package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.accumulation.domain.WithdrawalRequest;

import java.util.List;
import java.util.UUID;

public interface WithdrawalRequestRepository extends JpaRepository<WithdrawalRequest, UUID> {
    List<WithdrawalRequest> findByPolicyNumberOrderByRequestedAtDesc(String policyNumber);
    boolean existsByPolicyNumberAndStatusIn(String policyNumber, List<String> statuses);
}
```

```java
// accumulation/api/WithdrawalView.java
package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WithdrawalView(UUID withdrawalId, String policyNumber, BigDecimal amount, String currency, String payeeRef,
                             String status, String requestedBy, Instant requestedAt, String approvedBy, Instant approvedAt) {}
```

Add to `AccumulationApi`:

```java
    /** Valued now and again at approval (rule 1). Refused below the minimum balance net of any loan lien. */
    WithdrawalView requestWithdrawal(String policyNumber, java.math.BigDecimal amount, String payeeRef, String requestedBy);

    /** A second person. Posts the WITHDRAWAL entry and requests the payment (rule 2). */
    WithdrawalView approveWithdrawal(java.util.UUID withdrawalId, String approvedBy);

    List<WithdrawalView> listWithdrawals(String policyNumber);
```

Implement in `AccumulationApiImpl`. Inject `WithdrawalRequestRepository withdrawals` and `ApplicationEventPublisher events`.

```java
    @Override
    @Transactional
    public WithdrawalView requestWithdrawal(String policyNumber, BigDecimal amount, String payeeRef, String requestedBy) {
        if (payeeRef == null || payeeRef.isBlank()) {
            throw new AccumulationStateException("A withdrawal needs a payee reference");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new AccumulationStateException("A withdrawal must be for more than zero");
        }
        Account account = loadOpen(policyNumber);
        if (withdrawals.existsByPolicyNumberAndStatusIn(policyNumber, List.of("REQUESTED", "APPROVED"))) {
            throw new AccumulationStateException("A withdrawal is already in flight on policy " + policyNumber);
        }
        refuseBelowMinimum(account, amount);
        WithdrawalRequest request = withdrawals.save(new WithdrawalRequest(TenantContext.get(), policyNumber,
            amount.setScale(2, RoundingMode.UNNECESSARY), account.getCurrency(), payeeRef, requestedBy));
        return toView(request);
    }

    @Override
    @Transactional
    public WithdrawalView approveWithdrawal(UUID withdrawalId, String approvedBy) {
        WithdrawalRequest request = withdrawals.findById(withdrawalId)
            .orElseThrow(() -> new AccumulationStateException("No withdrawal " + withdrawalId));
        Account account = loadOpen(request.getPolicyNumber());
        request.approve(approvedBy);
        // Again at approval: a month-end fee may have landed since the request.
        refuseBelowMinimum(account, request.getAmount());
        ledger.post(request.getPolicyNumber(), new LedgerService.Source("withdrawal", "withdrawal:" + withdrawalId),
            List.of(LedgerService.Line.of(EntryType.WITHDRAWAL, request.getAmount().negate(), LocalDate.now(),
                "Partial withdrawal to " + request.getPayeeRef())),
            request.getRequestedBy(), approvedBy);
        withdrawals.save(request);
        events.publishEvent(DomainEventEnvelope.of("accumulation.PayoutRequested", TenantContext.get(), Map.of(
            "purpose", "WITHDRAWAL_PAYOUT",
            "sourceRef", withdrawalId.toString(),
            "idempotencyKey", "withdrawal:" + withdrawalId,
            "policyNumber", request.getPolicyNumber(),
            "payeeRef", request.getPayeeRef(),
            "amount", Map.of("amount", request.getAmount().toPlainString(), "currencyCode", request.getCurrency()))));
        return toView(request);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WithdrawalView> listWithdrawals(String policyNumber) {
        return withdrawals.findByPolicyNumberOrderByRequestedAtDesc(policyNumber).stream().map(this::toView).toList();
    }

    /** Rule 1: what may leave is the balance less the loan lien, down to the minimum balance. */
    private void refuseBelowMinimum(Account account, BigDecimal amount) {
        BigDecimal lien = policyApi.getCashValue(account.getPolicyNumber()).loanEncumbranceAmount();
        BigDecimal minimum = productApi.resolveAccumulationPlan(account.getProductVersionId()).minimumBalance();
        BigDecimal available = account.getBalance().subtract(lien);
        BigDecimal left = available.subtract(amount);
        if (left.compareTo(minimum) < 0) {
            BigDecimal most = available.subtract(minimum).max(BigDecimal.ZERO).setScale(2);
            throw new AccumulationStateException("A withdrawal of " + amount.setScale(2) + " would leave "
                + left.setScale(2) + ", below this product's minimum balance of " + minimum.setScale(2)
                + ". The most that can be withdrawn is " + most + ".");
        }
    }

    /**
     * {@code payment.DisbursementCompleted} / {@code DisbursementFailed} with purpose
     * WITHDRAWAL_PAYOUT. A failure puts the money back by REVERSING the withdrawal entry -- the
     * original stays, unedited, and the statement shows both (spec §9).
     */
    @Transactional
    public void settleWithdrawal(UUID withdrawalId, UUID disbursementId, boolean paid) {
        WithdrawalRequest request = withdrawals.findById(withdrawalId).orElse(null);
        if (request == null) {
            return;
        }
        if (paid) {
            if (request.markPaid(disbursementId)) withdrawals.save(request);
            return;
        }
        if (!request.markFailed(disbursementId)) {
            return; // already settled: a redelivery
        }
        withdrawals.save(request);
        Posting posting = postings.findByTenantIdAndSourceTypeAndSourceRef(TenantContext.get(), "withdrawal",
            "withdrawal:" + withdrawalId).orElseThrow();
        LedgerEntry original = entries.findByPostingIdOrderBySeq(posting.getPostingId()).get(0);
        ledger.post(request.getPolicyNumber(), new LedgerService.Source("reversal", "reversal:" + original.getEntryId()),
            List.of(new LedgerService.Line(EntryType.REVERSAL, original.getAmount().negate(), LocalDate.now(),
                "Withdrawal payment failed", original.getEntryId())),
            "system", null);
    }

    private WithdrawalView toView(WithdrawalRequest w) {
        return new WithdrawalView(w.getWithdrawalId(), w.getPolicyNumber(), w.getAmount(), w.getCurrency(), w.getPayeeRef(),
            w.getStatus(), w.getRequestedBy(), w.getRequestedAt(), w.getApprovedBy(), w.getApprovedAt());
    }
```

The reversal's source is `reversal:<entryId>`, as spec §4.2 lists it. `ux_ledger_entry_reversed_once` is the second guard against a doubled reversal, behind the `markFailed` edge.

An account can close while an APPROVED withdrawal is still being paid. A maturity falls due on its own date, and a surrender is approved in policy, which cannot see accumulation. The withdrawal's money already left at approval, so the closing balance is right either way. If that payment then FAILS, the reversal still posts: `LedgerService.post` deliberately has no `requireOpen`, because the money is real and the ledger must say where it is. The account then holds a balance while CLOSED. `settleWithdrawal` logs that at ERROR, naming the policy and the amount, for finance to refund by hand. It is rare, it is visible, and nothing is lost.

Add this at the end of `settleWithdrawal`'s failure branch:

```java
        accounts.findById(request.getPolicyNumber()).filter(a -> a.status() == AccountStatus.CLOSED).ifPresent(a ->
            log.error("Withdrawal {} on policy {} failed after its account closed ({}); {} is back on the closed account "
                + "and must be refunded by hand", withdrawalId, a.getPolicyNumber(), a.getClosedReason(), request.getAmount()));
```

```java
// accumulation/application/PaymentEventListener.java
package tz.co.nlolo.lifeplatform.accumulation.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/** Money that has actually moved: a withdrawal paid or failed, a top-up collected or failed. */
@Component("accumulationPaymentEventListener")
public class PaymentEventListener {

    private final AccumulationApiImpl api;
    private final EnvelopeRunner runner;

    public PaymentEventListener(AccumulationApiImpl api, EnvelopeRunner runner) {
        this.api = api;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "payment.DisbursementCompleted", "payment.DisbursementFailed" -> runner.run(envelope, p -> {
                if (!"WITHDRAWAL_PAYOUT".equals(p.get("purpose"))) return;
                api.settleWithdrawal(UUID.fromString((String) p.get("sourceRef")), (UUID) p.get("disbursementId"),
                    "payment.DisbursementCompleted".equals(envelope.eventType()));
            });
            case "payment.PaymentConfirmed" -> runner.run(envelope, p -> {
                if (!"ACCOUNT_TOP_UP".equals(p.get("purpose"))) return;
                @SuppressWarnings("unchecked")
                Map<String, Object> amount = (Map<String, Object>) p.get("amount");
                api.creditTopUp(UUID.fromString((String) p.get("sourceRef")),
                    new java.math.BigDecimal((String) amount.get("amount")),
                    LocalDate.ofInstant(Instant.parse((String) p.get("confirmedAt")), BillingEventListener.BUSINESS_ZONE));
            });
            case "payment.PaymentFailed" -> runner.run(envelope, p -> {
                if (!"ACCOUNT_TOP_UP".equals(p.get("purpose"))) return;
                api.failTopUp(UUID.fromString((String) p.get("sourceRef")));
            });
            default -> { /* not ours */ }
        }
    }
}
```

Make `BillingEventListener.BUSINESS_ZONE` package-private (drop `private`), or move it to a `BusinessDay` constant if Task 3's grep found an existing one.

Run: `./mvnw -o test -Dtest=WithdrawalIntegrationTest` — Expected: PASS, 6 tests.

- [ ] **Step 6: Top-ups and transfers in — test**

`TopUpAndTransferIntegrationTest`, with the same header:

```java
    private static final UUID TENANT = UUID.randomUUID();
    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private AccumulationApi api;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    @Test
    void aTopUpIsCollectedThroughPaymentAndCreditedAtTheContributionRate() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        var topUp = asTenant(() -> api.requestTopUp(issued.policyNumber(), new BigDecimal("20000.00"), "+255700000002", "staff-one"));
        // The mock rail confirms synchronously; the confirmation reaches accumulation, not billing.
        assertThat(asTenant(() -> api.entries(issued.policyNumber())))
            .extracting(LedgerEntryView::type, e -> e.amount().toPlainString())
            .containsExactly(tuple(EntryType.TOP_UP, "20000.00"), tuple(EntryType.ALLOCATION_CHARGE, "-1000.00"));
        assertThat(asTenant(() -> api.listTopUps(issued.policyNumber()))).first()
            .extracting(TopUpView::status).isEqualTo("COLLECTED");
        assertThat(asTenant(() -> api.entries(issued.policyNumber()))).allSatisfy(e ->
            assertThat(e.sourceRef()).isEqualTo("topup:" + topUp.topUpId()));
    }

    @Test
    void aTopUpConfirmationIsNotTreatedAsAnInvoicePayment() {
        // Before payment V9, billing parsed EVERY confirmed sourceRef as an invoice id. A top-up's
        // is a top-up id; billing must leave it alone rather than log a failure or, worse, match it.
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        asTenant(() -> api.requestTopUp(issued.policyNumber(), new BigDecimal("20000.00"), "+255700000002", "staff-one"));
        assertThat(billingInvoicesPaidFor(issued.policyNumber())).isZero();
    }

    @Test
    void aTransferInIsCreditedAtTheTransferRate() {
        var plan = new AccumulationPlan(ValueBasis.ACCOUNT, new BigDecimal("3"), new BigDecimal("50000.00"),
            List.of(new AccumulationChargeRow(1, null, new BigDecimal("5"), new BigDecimal("2"), new BigDecimal("1000.00"))));
        var issued = fixtures.issueSavingsPlan(TENANT, plan, LocalDate.now());
        asTenant(() -> api.recordTransferIn(issued.policyNumber(), new BigDecimal("300000.00"), "NSSF member 1234",
            "DOC-1", "staff-one"));
        assertThat(asTenant(() -> api.entries(issued.policyNumber())))
            .extracting(LedgerEntryView::type, e -> e.amount().toPlainString())
            .containsExactly(tuple(EntryType.TRANSFER_IN, "300000.00"), tuple(EntryType.ALLOCATION_CHARGE, "-6000.00"));
    }

    @Test
    void aTopUpOnAClosedAccountIsRefused() {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(3);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, start);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("1500.00"), start);
        asTenant(() -> { ((AccumulationApiImpl) api).postMonthEnds(issued.policyNumber(), LocalDate.now()); return null; });
        assertThatThrownBy(() -> asTenant(() -> api.requestTopUp(issued.policyNumber(), new BigDecimal("1.00"), "+255700000002", "s")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessageContaining("account is closed");
    }
```

`billingInvoicesPaidFor` counts rows in `billing.premium_invoice` with status PAID for the policy, over the owner JDBC connection. A count is enough: the fixture policy has no invoices of its own that are paid.

Check how the mock gateway behaves in tests before relying on "confirms synchronously". `PayoutPaymentEndToEndTest` shows whether a disbursement completes inside the publishing thread. If collections confirm only through a callback, drive the confirmation the way the billing payment tests do.

- [ ] **Step 7: Top-ups and transfers in — code**

```java
// accumulation/domain/TopUpRequest.java
package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** REQUESTED -> COLLECTED | FAILED. Credited only on COLLECTED: money is in when payment says so. */
@Entity
@Table(name = "top_up_request", schema = "accumulation")
public class TopUpRequest {
    @Id @UuidGenerator @Column(name = "top_up_id") private UUID topUpId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private BigDecimal amount;
    @Column(nullable = false) private String currency;
    @Column(name = "payer_ref", nullable = false) private String payerRef;
    @Column(nullable = false) private String status = "REQUESTED";
    @Column(name = "requested_by", nullable = false) private String requestedBy;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt = Instant.now();
    @Version private long version;

    protected TopUpRequest() {}

    public TopUpRequest(UUID tenantId, String policyNumber, BigDecimal amount, String currency, String payerRef, String requestedBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.amount = amount;
        this.currency = currency;
        this.payerRef = payerRef;
        this.requestedBy = requestedBy;
    }

    public boolean markCollected() {
        if (!"REQUESTED".equals(status)) return false;
        this.status = "COLLECTED";
        return true;
    }

    public boolean markFailed() {
        if (!"REQUESTED".equals(status)) return false;
        this.status = "FAILED";
        return true;
    }

    public UUID getTopUpId() { return topUpId; }
    public String getPolicyNumber() { return policyNumber; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getPayerRef() { return payerRef; }
    public String getStatus() { return status; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
}
```

```java
// accumulation/domain/TransferIn.java
package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The record of money arriving from another scheme, with what it came from. Written once. */
@Entity
@Immutable
@Table(name = "transfer_in", schema = "accumulation")
public class TransferIn {
    @Id @UuidGenerator @Column(name = "transfer_id") private UUID transferId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private BigDecimal amount;
    @Column(nullable = false) private String currency;
    @Column(name = "source_scheme", nullable = false) private String sourceScheme;
    @Column(name = "document_ref") private String documentRef;
    @Column(name = "recorded_by", nullable = false) private String recordedBy;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();

    protected TransferIn() {}

    public TransferIn(UUID tenantId, String policyNumber, BigDecimal amount, String currency, String sourceScheme,
                      String documentRef, String recordedBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.amount = amount;
        this.currency = currency;
        this.sourceScheme = sourceScheme;
        this.documentRef = documentRef;
        this.recordedBy = recordedBy;
    }

    public UUID getTransferId() { return transferId; }
    public String getPolicyNumber() { return policyNumber; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getSourceScheme() { return sourceScheme; }
    public String getDocumentRef() { return documentRef; }
    public String getRecordedBy() { return recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
}
```

Repositories: `TopUpRequestRepository extends JpaRepository<TopUpRequest, UUID>` with `findByPolicyNumberOrderByRequestedAtDesc(String)`, and `TransferInRepository extends JpaRepository<TransferIn, UUID>` with `findByPolicyNumberOrderByRecordedAtDesc(String)`.

Views:

```java
public record TopUpView(UUID topUpId, String policyNumber, BigDecimal amount, String currency, String payerRef,
                        String status, String requestedBy, Instant requestedAt) {}
public record TransferInView(UUID transferId, String policyNumber, BigDecimal amount, String currency,
                             String sourceScheme, String documentRef, String recordedBy, Instant recordedAt) {}
```

(each in its own file in `accumulation/api`, with the usual imports).

`AccumulationApi`:

```java
    /** Requests the collection from payment; credited when payment confirms it. */
    TopUpView requestTopUp(String policyNumber, java.math.BigDecimal amount, String payerRef, String requestedBy);

    List<TopUpView> listTopUps(String policyNumber);

    /** Money already received from another scheme, recorded with its source and a document. */
    TransferInView recordTransferIn(String policyNumber, java.math.BigDecimal amount, String sourceScheme,
                                    String documentRef, String recordedBy);

    List<TransferInView> listTransfersIn(String policyNumber);
```

`AccumulationApiImpl`. Inject both repositories, then add:

```java
    @Override
    @Transactional
    public TopUpView requestTopUp(String policyNumber, BigDecimal amount, String payerRef, String requestedBy) {
        if (payerRef == null || payerRef.isBlank()) {
            throw new AccumulationStateException("A top-up needs a payer reference");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new AccumulationStateException("A top-up must be for more than zero");
        }
        Account account = loadOpen(policyNumber);
        TopUpRequest request = topUps.save(new TopUpRequest(TenantContext.get(), policyNumber,
            amount.setScale(2, RoundingMode.UNNECESSARY), account.getCurrency(), payerRef, requestedBy));
        events.publishEvent(DomainEventEnvelope.of("accumulation.TopUpRequested", TenantContext.get(), Map.of(
            "topUpId", request.getTopUpId().toString(),
            "idempotencyKey", "topup:" + request.getTopUpId(),
            "policyNumber", policyNumber,
            "payerRef", payerRef,
            "amount", Map.of("amount", request.getAmount().toPlainString(), "currencyCode", request.getCurrency()))));
        return toView(request);
    }

    /** The money is in. Credited at the CONTRIBUTION rate: a top-up is a contribution the customer chose. */
    @Transactional
    public void creditTopUp(UUID topUpId, BigDecimal amount, LocalDate confirmedOn) {
        TopUpRequest request = topUps.findById(topUpId).orElse(null);
        if (request == null || !request.markCollected()) {
            return;
        }
        topUps.save(request);
        Account account = loadOpen(request.getPolicyNumber());
        int year = PolicyYears.of(account.getOpenedOn(), confirmedOn);
        BigDecimal pct = productApi.resolveAccumulationPlan(account.getProductVersionId()).chargesFor(year)
            .contributionAllocationPercent();
        BigDecimal charge = amount.multiply(pct).divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
        ledger.post(request.getPolicyNumber(), new LedgerService.Source("topup", "topup:" + topUpId), List.of(
            LedgerService.Line.of(EntryType.TOP_UP, amount, confirmedOn, "Top-up from " + request.getPayerRef()),
            LedgerService.Line.of(EntryType.ALLOCATION_CHARGE, charge.negate(), confirmedOn,
                "Allocation charge " + pct.stripTrailingZeros().toPlainString() + "% (policy year " + year + ")")),
            request.getRequestedBy(), null);
    }

    @Transactional
    public void failTopUp(UUID topUpId) {
        topUps.findById(topUpId).filter(TopUpRequest::markFailed).ifPresent(topUps::save);
    }

    @Override
    @Transactional
    public TransferInView recordTransferIn(String policyNumber, BigDecimal amount, String sourceScheme,
                                           String documentRef, String recordedBy) {
        if (sourceScheme == null || sourceScheme.isBlank()) {
            throw new AccumulationStateException("A transfer in must name the scheme it came from");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new AccumulationStateException("A transfer in must be for more than zero");
        }
        Account account = loadOpen(policyNumber);
        TransferIn transfer = transfers.save(new TransferIn(TenantContext.get(), policyNumber,
            amount.setScale(2, RoundingMode.UNNECESSARY), account.getCurrency(), sourceScheme, documentRef, recordedBy));
        LocalDate today = LocalDate.now();
        int year = PolicyYears.of(account.getOpenedOn(), today.isBefore(account.getOpenedOn()) ? account.getOpenedOn() : today);
        BigDecimal pct = productApi.resolveAccumulationPlan(account.getProductVersionId()).chargesFor(year)
            .transferAllocationPercent();
        BigDecimal charge = transfer.getAmount().multiply(pct).divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
        ledger.post(policyNumber, new LedgerService.Source("transfer", "transfer:" + transfer.getTransferId()), List.of(
            LedgerService.Line.of(EntryType.TRANSFER_IN, transfer.getAmount(), today, "Transfer in from " + sourceScheme),
            LedgerService.Line.of(EntryType.ALLOCATION_CHARGE, charge.negate(), today,
                "Transfer allocation charge " + pct.stripTrailingZeros().toPlainString() + "% (policy year " + year + ")")),
            recordedBy, null);
        return toView(transfer);
    }
```

Add `listTopUps`, `listTransfersIn` and the two `toView` overloads in the same style as `listWithdrawals`.

`amount.setScale(2, RoundingMode.UNNECESSARY)` throws `ArithmeticException` for an amount with more than two decimals. The REST layer's pattern stops that first (Step 8). Through the API it is a programming error and is allowed to fail loudly.

A transfer in is the one movement recorded by a single person. It records money that has already arrived, with its document, and moves nothing out. If the user wants it two-person later, it follows the withdrawal's shape.

Run: `./mvnw -o test -Dtest='TopUpAndTransferIntegrationTest,WithdrawalIntegrationTest'` — Expected: PASS, 10 tests. Then run billing's payment tests, to show premiums are untouched: `./mvnw -o test -Dtest='*Billing*Payment*'`. Expected: PASS.

- [ ] **Step 7b: Manual adjustments, two people (spec §4.1)**

This is the one way a PERSON corrects a ledger. A reversal (`REVERSAL`) is the system undoing its own entry. An adjustment (`ADJUSTMENT`) is a signed amount a person proposes and a second person approves, with a reason, and it is posted only on approval. The `ledger_entry_adjustment_approved` CHECK from Task 2 is the database's half of the rule; this step is the half a person can use.

Add to Task 2's `V1` (still unmerged, so edit it in place):

```sql
CREATE TABLE accumulation.adjustment_request (
    adjustment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    policy_number VARCHAR(20) NOT NULL,
    amount        NUMERIC(19,2) NOT NULL CHECK (amount <> 0),
    reason        VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    status        VARCHAR(10) NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED','APPROVED','REJECTED')),
    proposed_by   VARCHAR(100) NOT NULL,
    proposed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by    VARCHAR(100),
    decided_at    TIMESTAMPTZ,
    version       BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT adjustment_two_person CHECK (decided_by IS NULL OR decided_by <> proposed_by)
);
```

Add `'adjustment_request'` to the RLS loop's table array and to the `SELECT, INSERT, UPDATE` grant.

`AdjustmentRequest` follows `WithdrawalRequest`'s shape: `approve(by)` and `reject(by)` from PROPOSED only, and the same-person refusal "An adjustment must be decided by someone other than the person who proposed it". Add an `AdjustmentRequestRepository` with `findByPolicyNumberOrderByProposedAtDesc`. `AdjustmentView` mirrors the row.

`AccumulationApi`:

```java
    AdjustmentView proposeAdjustment(String policyNumber, java.math.BigDecimal amount, String reason, String proposedBy);
    AdjustmentView approveAdjustment(java.util.UUID adjustmentId, String approvedBy);
    AdjustmentView rejectAdjustment(java.util.UUID adjustmentId, String rejectedBy);
    List<AdjustmentView> listAdjustments(String policyNumber);
```

`approveAdjustment` posts, with source `adjustment:<id>`:

```java
        ledger.post(request.getPolicyNumber(), new LedgerService.Source("adjustment", "adjustment:" + adjustmentId),
            List.of(LedgerService.Line.of(EntryType.ADJUSTMENT, request.getAmount(), LocalDate.now(), request.getReason())),
            request.getProposedBy(), approvedBy);
```

`created_by` is the proposer and `approved_by` the approver. That is exactly the pair the CHECK compares, so a bug that passed the same name twice would be refused by the database, not just by the aggregate. A negative adjustment larger than the balance fails `Account.advance` with its own message, and nothing is posted.

Tests, in `WithdrawalIntegrationTest` (same fixtures):

```java
    @Test
    void anAdjustmentNeedsASecondPersonAndPostsOnlyOnApproval() {
        String policy = funded();
        var proposed = asTenant(() -> api.proposeAdjustment(policy, new BigDecimal("-250.00"), "Fee charged twice in error", "staff-one"));
        assertThat(asTenant(() -> api.entries(policy))).noneMatch(e -> e.type() == EntryType.ADJUSTMENT);
        assertThatThrownBy(() -> asTenant(() -> api.approveAdjustment(proposed.adjustmentId(), "staff-one")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessage("An adjustment must be decided by someone other than the person who proposed it");
        asTenant(() -> api.approveAdjustment(proposed.adjustmentId(), "finance-two"));
        assertThat(asTenant(() -> api.entries(policy))).last().satisfies(e -> {
            assertThat(e.type()).isEqualTo(EntryType.ADJUSTMENT);
            assertThat(e.createdBy()).isEqualTo("staff-one");
            assertThat(e.approvedBy()).isEqualTo("finance-two");
        });
    }

    @Test
    void aRejectedAdjustmentPostsNothing() {
        String policy = funded();
        var proposed = asTenant(() -> api.proposeAdjustment(policy, new BigDecimal("500.00"), "Goodwill", "staff-one"));
        asTenant(() -> api.rejectAdjustment(proposed.adjustmentId(), "finance-two"));
        assertThat(asTenant(() -> api.entries(policy))).noneMatch(e -> e.type() == EntryType.ADJUSTMENT);
    }
```

REST (in Step 8): `POST /policies/{n}/account/adjustments` (FINANCE) with body `{amount, reason}`. The amount pattern allows a leading minus, `^-?\d+(\.\d{1,2})?$`. Then `POST /account-adjustments/{id}/approve` and `/reject` (FINANCE), and `GET /policies/{n}/account/adjustments` (any staff). The console (Task 9) shows proposed adjustments under the ledger, with Approve and Reject gated by the same second-person gate as a withdrawal.

- [ ] **Step 8: REST and spec**

Add to `AccumulationController`:

```java
    /** Any staff member may read an account: it is the customer's own money and its history. */
    @GetMapping("/policies/{policyNumber}/account")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public AccountResponse account(@PathVariable String policyNumber) {
        return AccountResponse.from(api.findAccount(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber)),
            api.entries(policyNumber));
    }

    @PostMapping("/policies/{policyNumber}/account/withdrawals")
    @PreAuthorize("hasRole('REALM_STAFF')")
    @ResponseStatus(HttpStatus.CREATED)
    public WithdrawalResponse requestWithdrawal(@PathVariable String policyNumber, @Valid @RequestBody WithdrawalRequestBody body,
                                                @AuthenticationPrincipal Jwt jwt) {
        return WithdrawalResponse.from(api.requestWithdrawal(policyNumber, new BigDecimal(body.amount()), body.payeeRef(),
            jwt.getSubject()));
    }

    /** 202: approved and REQUESTED from the rail, not yet paid -- as a payout approval. */
    @PostMapping("/account-withdrawals/{withdrawalId}/approve")
    @PreAuthorize(FINANCE)
    public ResponseEntity<WithdrawalResponse> approveWithdrawal(@PathVariable UUID withdrawalId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.accepted().body(WithdrawalResponse.from(api.approveWithdrawal(withdrawalId, jwt.getSubject())));
    }

    @GetMapping("/policies/{policyNumber}/account/withdrawals")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<WithdrawalResponse> listWithdrawals(@PathVariable String policyNumber) {
        return api.listWithdrawals(policyNumber).stream().map(WithdrawalResponse::from).toList();
    }

    @PostMapping("/policies/{policyNumber}/account/top-ups")
    @PreAuthorize("hasRole('REALM_STAFF')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TopUpResponse requestTopUp(@PathVariable String policyNumber, @Valid @RequestBody TopUpRequestBody body,
                                      @AuthenticationPrincipal Jwt jwt) {
        return TopUpResponse.from(api.requestTopUp(policyNumber, new BigDecimal(body.amount()), body.payerRef(), jwt.getSubject()));
    }

    @GetMapping("/policies/{policyNumber}/account/top-ups")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<TopUpResponse> listTopUps(@PathVariable String policyNumber) {
        return api.listTopUps(policyNumber).stream().map(TopUpResponse::from).toList();
    }

    @PostMapping("/policies/{policyNumber}/account/transfers-in")
    @PreAuthorize(FINANCE)
    @ResponseStatus(HttpStatus.CREATED)
    public TransferInResponse recordTransferIn(@PathVariable String policyNumber, @Valid @RequestBody TransferInRequestBody body,
                                               @AuthenticationPrincipal Jwt jwt) {
        return TransferInResponse.from(api.recordTransferIn(policyNumber, new BigDecimal(body.amount()), body.sourceScheme(),
            body.documentRef(), jwt.getSubject()));
    }

    @GetMapping("/policies/{policyNumber}/account/transfers-in")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<TransferInResponse> listTransfersIn(@PathVariable String policyNumber) {
        return api.listTransfersIn(policyNumber).stream().map(TransferInResponse::from).toList();
    }
```

Who may REQUEST a withdrawal is any staff member, as a surrender request is. Approving moves money, so it is FINANCE's. Recording a transfer in is FINANCE's because it puts money on an account. Check `PolicyController`'s surrender request and approval guards, and match them if they differ.

Request bodies take money as a decimal STRING with the server's pattern, as every money body on this platform does:

```java
public record WithdrawalRequestBody(
    @NotBlank @Pattern(regexp = "^\\d+(\\.\\d{1,2})?$", message = "must be an amount with at most two decimals") String amount,
    @NotBlank String payeeRef) {}
public record TopUpRequestBody(
    @NotBlank @Pattern(regexp = "^\\d+(\\.\\d{1,2})?$", message = "must be an amount with at most two decimals") String amount,
    @NotBlank String payerRef) {}
public record TransferInRequestBody(
    @NotBlank @Pattern(regexp = "^\\d+(\\.\\d{1,2})?$", message = "must be an amount with at most two decimals") String amount,
    @NotBlank @Size(max = 200) String sourceScheme,
    @Size(max = 200) String documentRef) {}
```

Responses carry money as `{amount, currencyCode}`, the platform's `Money` shape:

```java
public record AccountResponse(String policyNumber, String status, Money balance, String openedOn, String closedReason,
                              String closedOn, List<EntryResponse> entries) {
    public record Money(String amount, String currencyCode) {}
    public record EntryResponse(String entryId, int seq, String type, Money amount, Money balanceAfter, String effectiveDate,
                                String postedAt, String sourceType, String sourceRef, String reversesEntryId, String reason,
                                String createdBy, String approvedBy) {}
    static Money money(java.math.BigDecimal amount, String currency) {
        return new Money(amount.toPlainString(), currency);
    }

    static AccountResponse from(AccountView a, List<LedgerEntryView> entries) {
        return new AccountResponse(a.policyNumber(), a.status().name(), money(a.balance(), a.currency()),
            a.openedOn().toString(), a.closedReason(), a.closedOn() != null ? a.closedOn().toString() : null,
            entries.stream().map(e -> EntryResponse.from(e, a.currency())).toList());
    }
}
```

with, inside `AccountResponse`:

```java
    public record EntryResponse(String entryId, int seq, String type, Money amount, Money balanceAfter, String effectiveDate,
                                String postedAt, String sourceType, String sourceRef, String reversesEntryId, String reason,
                                String createdBy, String approvedBy) {
        static EntryResponse from(LedgerEntryView e, String currency) {
            return new EntryResponse(e.entryId().toString(), e.seq(), e.type().name(), money(e.amount(), currency),
                money(e.balanceAfter(), currency), e.effectiveDate().toString(), e.postedAt().toString(), e.sourceType(),
                e.sourceRef(), e.reversesEntryId() != null ? e.reversesEntryId().toString() : null, e.reason(),
                e.createdBy(), e.approvedBy());
        }
    }
```

(This replaces the one-line `EntryResponse` declaration above.) `WithdrawalResponse`, `TopUpResponse` and `TransferInResponse` are built the same way from their views: every id as a string, every amount through `money(...)`, and every nullable field passed through as `null`.

Check `openapi-common.yaml` for an existing `Money` schema and `$ref` it rather than defining a second one. If the platform has a shared Java `MoneyDto`, use that type in place of the nested `Money` record.

`openapi-accumulation.yaml` gains:
- the seven paths
- schemas for `Account`, `LedgerEntry` (with `type` an enum of the thirteen entry types), `Withdrawal`, `TopUp`, `TransferIn`, and the three request bodies
- `'404'` on the account GET.

Extend `AccumulationContractTest` with three tests:
- the account is read to spec after one contribution
- a withdrawal is requested (201) and approved (202) to spec
- a malformed amount (`"12.345"`) is a 400 that names the field.

Run: `./mvnw -o test -Dtest='AccumulationContractTest'` — Expected: PASS.

- [ ] **Step 9: AsyncAPI**

Register `accumulation.PayoutRequested` and `accumulation.TopUpRequested` with the exact payload keys above. Add `purpose` (enum `PREMIUM`, `ACCOUNT_TOP_UP`; optional, absent means PREMIUM) to `payment.PaymentConfirmed` and `payment.PaymentFailed`. Add `WITHDRAWAL_PAYOUT` wherever the file enumerates disbursement purposes.

- [ ] **Step 10: Commit**

```bash
git add db-migrations/payment/V9__account_purposes.sql src/main/java/tz/co/nlolo/lifeplatform/accumulation src/main/java/tz/co/nlolo/lifeplatform/payment src/main/java/tz/co/nlolo/lifeplatform/billing src/test/java api
git commit -m "feat(accumulation): two-person withdrawals reversed on failure, top-ups collected through payment, transfers in"
```

---

### Task 7: Closing an account — surrender, maturity, death, free-look

**Files:**
- Create: `accumulation/api/DeathValuation.java`, `accumulation/api/ClosingQuote.java`
- Create: `accumulation/application/ClaimEventListener.java`
- Modify: `AccumulationApi.java`, `AccumulationApiImpl.java`, `accumulation/application/PolicyEventListener.java`, `AccumulationController.java`, `openapi-accumulation.yaml`
- Modify: `policy/application/PolicyApiImpl.java` (`approveSurrender`, `requestSurrender`)
- Modify: `benefitpayout/package-info.java` (adds `"accumulation::api"`), `benefitpayout/api/BenefitPayoutApi.java`, `benefitpayout/application/BenefitPayoutApiImpl.java` (`fallDue`, the ceiling overload), `benefitpayout/domain/ScheduleExpander.java`
- Modify: `claims/application/ClaimsApiImpl.java` (calls the overload)
- Modify: `api/asyncapi-events.yaml`
- Test: `ClosingIntegrationTest.java`, `ScheduleExpanderTest.java` (one case added), `AccumulationContractTest.java` (the quote)

**Interfaces:**
- Consumes: `AccountValuer.interestBetween` (Task 4), `LedgerService.post`, `accumulation.PayoutRequested` (Task 6), `PayoutAmountBasis.ACCOUNT_VALUE` (Task 1)
- Produces:
  - `record ClosingQuote(String policyNumber, LocalDate asOf, BigDecimal balance, BigDecimal interestToDate, BigDecimal value, String currency)`. `value = balance + interestToDate`. It is what a closing on `asOf` would move.
  - `AccumulationApi.quoteClosing(String policyNumber, LocalDate asOf) -> ClosingQuote`
  - `record DeathValuation(BigDecimal accountValue, BigDecimal premiumsBeforeDeath, BigDecimal contributionsAfterDeath, String currency)`
  - `AccumulationApi.valueAtDeath(String policyNumber, LocalDate dateOfDeath) -> DeathValuation` (read-only)
  - `AccumulationApi.closeForMaturity(String policyNumber, UUID instalmentId, LocalDate dueDate) -> BigDecimal` (the amount moved; idempotent on the instalment)
  - `BenefitPayoutApi.deathBenefitCeiling(String policyNumber, BigDecimal sumAssuredCeiling, LocalDate dateOfDeath)`
  - Event `policy.AccountSurrenderApproved {surrenderRequestId, policyNumber, payeeRef, surrenderChargePercent, approvedBy, approvedAt}`
  - Closing reasons: `SURRENDERED`, `MATURED`, `DEATH`, `FREE_LOOK`

**The rules:**
1. Every closing is ONE posting: interest from the day after the last month-end to the closing date, then the closing entry for the whole balance. The account closes in the same transaction.
2. **Surrender** (spec §5.4, §10.10). Policy keeps the two signatures and stops cover. For an ACCOUNT version it publishes `policy.AccountSurrenderApproved` INSTEAD of `policy.SurrenderPayoutRequested`, and carries the surrender-charge percent it already resolves. Accumulation closes the account on the approval date and pays `round(value × (100 − charge%) / 100, 2, HALF_EVEN)` through `accumulation.PayoutRequested`. The purpose is `SURRENDER_PAYOUT` and the source ref is the surrender request id, so `SurrenderPaymentListener` marks the request PAID unchanged. The charge stays with the insurer and is NOT a ledger entry: the account is emptied either way (spec §5.3). A failed surrender payment leaves the SURRENDER entry standing: cover has already stopped, and finance retries the payment on step 1's existing path.
3. **Maturity.** An `ACCOUNT_VALUE` MATURITY row is expanded with NO amount, as a premium return is. When it falls due, benefitpayout asks accumulation to close the account on the due date, and the instalment's amount is what the MATURITY entry moved. Zero means nothing to pay: the instalment is cancelled and the policy still matures, as a zero premium return is handled.
4. **Death** (spec §5.3, §10.5, §10.6, §10.9). On `claims.ClaimApproved` with `claimType = DEATH`, every entry effective AFTER the date of death is reversed, interest is posted from the last month-end on or before the death up to the death, and `DEATH_CLAIM` takes the balance. One exception to the reversals: a `REVERSAL` whose original is effective on or before the death stays, because it restores money the life assured owned when they died. Contributions reversed this way are owed to the estate. The death ceiling adds them to the claim, so they reach the claimant with the death benefit.
5. **The death ceiling** on an ACCOUNT version is `max(account value at death, pct% × premiums before death) + contributions after death`. Here `pct` is the version's `deathBenefitPremiumPercent`, and "premiums" means `CONTRIBUTION` and `TOP_UP` entries; a transfer in is not a premium. The sum assured is not part of it (spec Q5). Any part of the ceiling above the account is the insurer's money, not the account's.
6. **Free-look.** On `policy.PolicyCancelledFreeLook`, `FREE_LOOK_REFUND` empties the account. The refund the customer receives is still step 2's premiums less itemised deductions. The ledger only records that the account no longer holds anything. The source ref is `freelook:<policyNumber>`, not a cancellation id, because the event carries none and a policy is cancelled for free-look at most once.
7. A premium collected after the account has closed is not credited. `creditContribution` logs it at ERROR, naming the policy and the invoice, for a refund by hand, and returns without throwing; throwing inside a listener only adds a stack trace. Billing stops raising invoices on surrender, maturity and free-look already, so this is rare.

- [ ] **Step 1: Write the failing closing test**

`ClosingIntegrationTest`. The header is the same as `AccountPolicyLifecycleIntegrationTest`, and the migration list also needs the claims migrations (copy them from `ClaimsApiImpl`'s integration test).

```java
    private static final UUID TENANT = UUID.randomUUID();
    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private AccumulationApiImpl api;
    @Autowired private PolicyApi policyApi;
    @Autowired private BenefitPayoutApi benefitPayoutApi;
    @Autowired private PayoutDueDrain dueDrain;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }
    private List<LedgerEntryView> entriesOf(String p) { return asTenant(() -> api.entries(p)); }

    /** 100,000 in on the 1st of the month two months ago, both month-ends posted. */
    private String fundedWithMonthEnds() {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(2);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, start);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("100000.00"), start);
        asTenant(() -> { api.postMonthEnds(issued.policyNumber(), LocalDate.now()); return null; });
        return issued.policyNumber();
    }

    @Test
    void aSurrenderClosesTheAccountWithInterestToTheDayAndPaysItLessTheCharge() {
        String policy = fundedWithMonthEnds();
        ClosingQuote quote = asTenant(() -> api.quoteClosing(policy, LocalDate.now()));
        var request = asTenant(() -> policyApi.requestSurrender(policy, "+255700000003", "staff-one"));
        asTenant(() -> policyApi.approveSurrender(request.surrenderRequestId(), "finance-two"));

        List<LedgerEntryView> entries = entriesOf(policy);
        LedgerEntryView closing = entries.get(entries.size() - 1);
        assertThat(closing.type()).isEqualTo(EntryType.SURRENDER);
        assertThat(closing.balanceAfter()).isEqualByComparingTo("0.00");
        // The figure the approver was shown is the figure that moved.
        assertThat(closing.amount().negate()).isEqualByComparingTo(quote.value());
        assertThat(asTenant(() -> api.findAccount(policy)).orElseThrow().closedReason()).isEqualTo("SURRENDERED");
        // The fixture version authors no surrender charge, so the whole value is paid -- and the
        // request is marked PAID through step 1's own listener, by the source ref.
        assertThat(asTenant(() -> policyApi.getSurrenderRequest(request.surrenderRequestId())).status()).isEqualTo("PAID");
    }

    @Test
    void aMaturityPaysTheWholeAccountOnTheDueDate() {
        // Commenced 15 years and two days ago, on a 180-month term: the maturity fell due yesterday.
        LocalDate start = LocalDate.now().minusYears(15).minusDays(2);
        var issued = fixtures.issue(TENANT, AccumulationTestFixtures.SAVINGS, start, 180);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("100000.00"), start);
        dueDrain.drain();

        var maturity = asTenant(() -> benefitPayoutApi.listForPolicy(issued.policyNumber())).stream()
            .filter(i -> i.kind() == PayoutKind.MATURITY).findFirst().orElseThrow();
        List<LedgerEntryView> entries = entriesOf(issued.policyNumber());
        LedgerEntryView closing = entries.get(entries.size() - 1);
        assertThat(closing.type()).isEqualTo(EntryType.MATURITY);
        assertThat(maturity.amount()).isEqualByComparingTo(closing.amount().negate());
        assertThat(closing.sourceRef()).isEqualTo("instalment:" + maturity.instalmentId());
        assertThat(asTenant(() -> policyApi.getPolicy(issued.policyNumber())).status()).isEqualTo(PolicyStatus.MATURED);
    }

    @Test
    void aDeathReversesWhatCameAfterItAndPaysTheBalanceAtDeath() {
        String policy = fundedWithMonthEnds();
        LocalDate death = LocalDate.now().withDayOfMonth(1).minusMonths(1).withDayOfMonth(10);
        // A premium collected after the death, and the month-end that ran after it.
        UUID late = UUID.randomUUID();
        fixtures.collectPremium(TENANT, policy, late, new BigDecimal("20000.00"), LocalDate.now());

        DeathValuation valuation = asTenant(() -> api.valueAtDeath(policy, death));
        assertThat(valuation.contributionsAfterDeath()).isEqualByComparingTo("20000.00");
        assertThat(valuation.premiumsBeforeDeath()).isEqualByComparingTo("100000.00");

        UUID claimId = UUID.randomUUID();
        fixtures.publish(TENANT, "claims.ClaimApproved", Map.of("claimId", claimId, "policyNumber", policy,
            "claimType", "DEATH", "dateOfEvent", death.toString(),
            "approvedAmount", Map.of("amount", valuation.accountValue().toPlainString(), "currencyCode", "TZS")));

        List<LedgerEntryView> entries = entriesOf(policy);
        LedgerEntryView closing = entries.get(entries.size() - 1);
        assertThat(closing.type()).isEqualTo(EntryType.DEATH_CLAIM);
        assertThat(closing.effectiveDate()).isEqualTo(death);
        assertThat(closing.amount().negate()).isEqualByComparingTo(valuation.accountValue());
        // Everything effective after the death -- the late premium, its charge, and the month-end
        // of the month the death fell in -- reversed, never edited.
        assertThat(entries).filteredOn(e -> e.type() == EntryType.REVERSAL).isNotEmpty()
            .allSatisfy(r -> assertThat(entries.stream().filter(e -> e.entryId().equals(r.reversesEntryId()))
                .findFirst().orElseThrow().effectiveDate()).isAfter(death));
        assertThat(entries).filteredOn(e -> e.effectiveDate().isAfter(death) && e.type() != EntryType.REVERSAL)
            .allSatisfy(e -> assertThat(entries).anyMatch(r -> e.entryId().equals(r.reversesEntryId())));
        assertThat(asTenant(() -> api.findAccount(policy)).orElseThrow().closedReason()).isEqualTo("DEATH");
    }

    @Test
    void theDeathCeilingIsTheAccountAtDeathPlusWhatWasPaidAfterIt() {
        String policy = fundedWithMonthEnds();
        LocalDate death = LocalDate.now().minusDays(1);
        fixtures.collectPremium(TENANT, policy, UUID.randomUUID(), new BigDecimal("20000.00"), LocalDate.now());
        DeathValuation v = asTenant(() -> api.valueAtDeath(policy, death));
        // The fixture authors no premium-percent floor, so the account is the base.
        assertThat(asTenant(() -> benefitPayoutApi.deathBenefitCeiling(policy, new BigDecimal("1000000.00"), death)))
            .isEqualByComparingTo(v.accountValue().add(new BigDecimal("20000.00")));
    }

    @Test
    void aScalePolicysCeilingIsUnchangedByTheDateOfDeath() {
        var scale = fixtures.issueSavingsPlan(TENANT, AccumulationPlan.none(), LocalDate.now());
        assertThat(asTenant(() -> benefitPayoutApi.deathBenefitCeiling(scale.policyNumber(), new BigDecimal("1000000.00"),
            LocalDate.now()))).isEqualByComparingTo("1000000.00");
    }

    @Test
    void aFreeLookCancellationEmptiesTheAccount() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("50000.00"), LocalDate.now());
        fixtures.publish(TENANT, "policy.PolicyCancelledFreeLook", Map.of("policyNumber", issued.policyNumber(),
            "cancelledAt", java.time.Instant.now().toString(), "cancelledBy", "finance-two"));
        List<LedgerEntryView> entries = entriesOf(issued.policyNumber());
        assertThat(entries.get(entries.size() - 1).type()).isEqualTo(EntryType.FREE_LOOK_REFUND);
        assertThat(entries.get(entries.size() - 1).balanceAfter()).isEqualByComparingTo("0.00");
        assertThat(asTenant(() -> api.findAccount(issued.policyNumber())).orElseThrow().closedReason()).isEqualTo("FREE_LOOK");
    }

    @Test
    void aPremiumAfterClosingIsNotCredited() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("50000.00"), LocalDate.now());
        fixtures.publish(TENANT, "policy.PolicyCancelledFreeLook", Map.of("policyNumber", issued.policyNumber(),
            "cancelledAt", java.time.Instant.now().toString(), "cancelledBy", "finance-two"));
        int closed = entriesOf(issued.policyNumber()).size();
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("50000.00"), LocalDate.now());
        assertThat(entriesOf(issued.policyNumber())).hasSize(closed);
    }
```

Before writing these, confirm three names:
- `policyApi.getSurrenderRequest`, and the `SurrenderRequestView` status accessor. If there is no single-request getter, read the request through whatever `PolicyController` uses to show one.
- `PayoutInstalmentView`'s `kind()` / `amount()` / `instalmentId()`.
- `PolicyStatus.MATURED`.

Use the real names; do not add a getter only for the test unless no read path exists at all.

`aMaturityPaysTheWholeAccountOnTheDueDate` commences 15 years back, so its month-ends never ran. The closing interest spans from the opening to the due date. That is correct, if slow: about 5,500 days of replay. It is the honest catch-up for an account whose drain never ran.

Run: `./mvnw -o test -Dtest=ClosingIntegrationTest` — Expected: FAIL to compile.

- [ ] **Step 2: The quote and the shared closing**

```java
// accumulation/api/ClosingQuote.java
package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** What closing on {@code asOf} would move: the balance plus interest not yet posted. */
public record ClosingQuote(String policyNumber, LocalDate asOf, BigDecimal balance, BigDecimal interestToDate,
                           BigDecimal value, String currency) {}
```

```java
// accumulation/api/DeathValuation.java
package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;

/**
 * An account as at a death (spec §10.6). {@code premiumsBeforeDeath} is the base for a
 * percentage-of-premiums floor; {@code contributionsAfterDeath} is owed to the estate on top.
 */
public record DeathValuation(BigDecimal accountValue, BigDecimal premiumsBeforeDeath,
                             BigDecimal contributionsAfterDeath, String currency) {}
```

Add to `AccumulationApi`:

```java
    ClosingQuote quoteClosing(String policyNumber, java.time.LocalDate asOf);

    /** Read-only. What the account held at the death, before anything dated after it. */
    DeathValuation valueAtDeath(String policyNumber, java.time.LocalDate dateOfDeath);

    /** Closes the account on the due date and returns what the MATURITY entry moved. Idempotent. */
    java.math.BigDecimal closeForMaturity(String policyNumber, java.util.UUID instalmentId, java.time.LocalDate dueDate);
```

In `AccumulationApiImpl`:

```java
    /** The day after the last month-end on or before {@code date}, or the opening day. */
    private LocalDate interestFrom(Account account, LocalDate date) {
        return entries.findByPolicyNumberOrderBySeq(account.getPolicyNumber()).stream()
            .filter(e -> e.type() == EntryType.INTEREST && !e.getEffectiveDate().isAfter(date)
                && e.getEffectiveDate().equals(e.getEffectiveDate().withDayOfMonth(e.getEffectiveDate().lengthOfMonth())))
            .map(LedgerEntry::getEffectiveDate).max(LocalDate::compareTo)
            .map(d -> d.plusDays(1)).orElse(account.getOpenedOn());
    }
```

A closing's own INTEREST entry is dated on the closing day, which may fall on a month end. That is harmless: the account is closed after it, so nothing computes from it again.

```java
    @Override
    @Transactional(readOnly = true)
    public ClosingQuote quoteClosing(String policyNumber, LocalDate asOf) {
        Account account = loadOpen(policyNumber);
        BigDecimal interest = valuer.interestBetween(account, interestFrom(account, asOf), asOf);
        return new ClosingQuote(policyNumber, asOf, account.getBalance(), interest, account.getBalance().add(interest),
            account.getCurrency());
    }

    /** Rule 1: interest to the day, then the whole balance out, in one posting; closes the account. */
    private BigDecimal close(Account account, LedgerService.Source source, EntryType type, LocalDate on, String reason,
                             String closedReason, String createdBy, String approvedBy) {
        BigDecimal interest = valuer.interestBetween(account, interestFrom(account, on), on);
        BigDecimal value = account.getBalance().add(interest);
        ledger.post(account.getPolicyNumber(), source, List.of(
            LedgerService.Line.of(EntryType.INTEREST, interest, on, "Interest to " + on),
            LedgerService.Line.of(type, value.negate(), on, reason)), createdBy, approvedBy);
        account.close(closedReason, on);
        accounts.save(account);
        return value;
    }
```

Before `close` calls `interestBetween`, the caller must hold the row lock, or a contribution landing concurrently would be missed by the interest and then refused by the follows trigger. Every caller below therefore starts with `accounts.lockForPosting(...)` rather than `loadOpen`.

- [ ] **Step 3: Surrender**

In `PolicyApiImpl.approveSurrender`, after `policyRepository.save(policy)` and the `PolicySurrendered` event, replace the unconditional `SurrenderPayoutRequested` with:

```java
        if (productApi.resolveAccumulationPlan(policy.getProductVersionId()).isAccount()) {
            // Product step 3 (spec §5.4): the account is valued at approval, with interest to the
            // day, and the payment is accumulation's. Policy cannot call accumulation -- that would
            // be a cycle -- so it says what it approved, with the charge it already resolves.
            ProductSnapshotView snapshot = productApi.getSnapshotByVersionId(policy.getProductVersionId());
            eventPublisher.publishEvent(DomainEventEnvelope.of("policy.AccountSurrenderApproved", tenantId,
                Map.of("surrenderRequestId", surrenderRequestId.toString(),
                       "policyNumber", request.getPolicyNumber(),
                       "payeeRef", request.getPayeeRef(),
                       "surrenderChargePercent", resolveSurrenderChargePercent(snapshot.surrenderChargeScheduleJson(),
                           policy.getIssueDate()).toPlainString(),
                       "approvedBy", approvedBy,
                       "approvedAt", Instant.now().toString())));
        } else {
            // Unchanged: every scale-valued surrender, which is every one before this step.
            eventPublisher.publishEvent(DomainEventEnvelope.of("policy.SurrenderPayoutRequested", tenantId,
                Map.of("surrenderRequestId", surrenderRequestId.toString(),
                       "idempotencyKey", surrenderRequestId.toString(),
                       "policyNumber", request.getPolicyNumber(),
                       "payeeRef", request.getPayeeRef(),
                       "amount", Map.of("amount", request.getQuotedValueAmount().toPlainString(),
                            "currencyCode", request.getQuotedValueCurrency()))));
        }
```

The `else` payload is today's, character for character (`PolicyApiImpl.java:991-997`). Moving it inside the branch must not change a key.

`requestSurrender`'s quote for an ACCOUNT version is the projection: the balance at the last posting, less the charge. That is an estimate. Leave it as is, and say so on the request. The approval screen shows the live figure from `GET /policies/{n}/account/quote` (Step 7), which is what will actually be paid.

Accumulation's side, in `AccumulationApiImpl`:

```java
    /** {@code policy.AccountSurrenderApproved}: close on the approval day, pay value less the charge. */
    @Transactional
    public void closeForSurrender(String policyNumber, UUID surrenderRequestId, String payeeRef,
                                  BigDecimal chargePercent, String approvedBy, LocalDate on) {
        Account account = accounts.lockForPosting(policyNumber).orElse(null);
        if (account == null || account.status() != AccountStatus.OPEN) {
            return; // redelivered after the close, or not an account policy
        }
        BigDecimal value = close(account, new LedgerService.Source("surrender", "surrender:" + surrenderRequestId),
            EntryType.SURRENDER, on, "Surrendered", "SURRENDERED", "system", approvedBy);
        BigDecimal paid = value.multiply(HUNDRED.subtract(chargePercent)).divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
        if (paid.signum() <= 0) {
            log.error("Surrender {} of policy {} values to nothing after a {}% charge; no payment requested",
                surrenderRequestId, policyNumber, chargePercent);
            return;
        }
        events.publishEvent(DomainEventEnvelope.of("accumulation.PayoutRequested", TenantContext.get(), Map.of(
            "purpose", "SURRENDER_PAYOUT",
            "sourceRef", surrenderRequestId.toString(),
            "idempotencyKey", surrenderRequestId.toString(),
            "policyNumber", policyNumber,
            "payeeRef", payeeRef,
            "amount", Map.of("amount", paid.toPlainString(), "currencyCode", account.getCurrency()))));
    }
```

The idempotency key is the bare surrender request id, the same key step 1 uses. So even if both paths somehow fired for one request, payment's key registry would pay it once.

`AccumulationApiImpl` needs a logger: `private static final Logger log = LoggerFactory.getLogger(AccumulationApiImpl.class);`.

Add to `accumulation/application/PolicyEventListener`:

```java
            case "policy.AccountSurrenderApproved" -> runner.run(envelope, p -> api.closeForSurrender(
                (String) p.get("policyNumber"), UUID.fromString((String) p.get("surrenderRequestId")),
                (String) p.get("payeeRef"), new BigDecimal((String) p.get("surrenderChargePercent")),
                (String) p.get("approvedBy"),
                LocalDate.ofInstant(Instant.parse((String) p.get("approvedAt")), BillingEventListener.BUSINESS_ZONE)));
            case "policy.PolicyCancelledFreeLook" -> runner.run(envelope, p ->
                api.closeForFreeLook((String) p.get("policyNumber"), (String) p.get("cancelledBy")));
```

- [ ] **Step 4: Free-look and the closed-account premium**

```java
    /** {@code policy.PolicyCancelledFreeLook}: rule 6. */
    @Transactional
    public void closeForFreeLook(String policyNumber, String cancelledBy) {
        Account account = accounts.lockForPosting(policyNumber).orElse(null);
        if (account == null || account.status() != AccountStatus.OPEN) {
            return;
        }
        // No interest: a free-look cancels the contract from inception, so nothing was earned.
        ledger.post(policyNumber, new LedgerService.Source("freelook", "freelook:" + policyNumber), List.of(
            LedgerService.Line.of(EntryType.FREE_LOOK_REFUND, account.getBalance().negate(), LocalDate.now(),
                "Cancelled in the free-look period; refunded as premiums less deductions")),
            "system", cancelledBy);
        account.close("FREE_LOOK", LocalDate.now());
        accounts.save(account);
    }
```

No interest is credited on a free-look, and none earlier either: the policy is cancelled from inception. A month-end that already ran inside the free-look window has credited some, and the refund empties that too. The customer is refunded step 2's figure either way.

In `creditContribution`, replace `account.requireOpen();` with:

```java
        if (account.status() != AccountStatus.OPEN) {
            // Rule 7. Throwing here would only add a stack trace inside a listener; the money is
            // real and somebody must give it back.
            log.error("Premium for invoice {} on policy {} arrived after its account closed ({}); {} must be refunded "
                + "by hand", invoiceId, policyNumber, account.getClosedReason(), amount);
            return;
        }
```

Update Task 3's comment above it, which says Task 7 handles a post-death contribution, to point at rule 7 instead.

- [ ] **Step 5: Maturity**

`ScheduleExpander.expand`. In the `MATURITY` case, an `ACCOUNT_VALUE` row has no amount at issue:

```java
                case MATURITY -> out.add(new Planned(row.kind(), order, maturityDate,
                    // An account's value is not known until the day it falls due (product step 3),
                    // exactly as a premium return's is not.
                    row.amountBasis() == PayoutAmountBasis.ACCOUNT_VALUE ? null : amountFor(row, sumAssured)));
```

Add to `ScheduleExpanderTest`:

```java
    @Test
    void anAccountValueMaturityIsExpandedWithNoAmount() {
        PayoutPlan plan = PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of(new PayoutRowInput(
            PayoutKind.MATURITY, null, null, PayoutAmountBasis.ACCOUNT_VALUE, new BigDecimal("100"), null)));
        var planned = ScheduleExpander.expand(plan, LocalDate.of(2026, 1, 1), LocalDate.of(2041, 1, 1), new BigDecimal("1000000"));
        assertThat(planned).singleElement().satisfies(p -> assertThat(p.amount()).isNull());
    }
```

Match the `Planned` accessor name and the `PayoutRowInput` constructor to the existing tests in that file.

`BenefitPayoutApiImpl.fallDue`: inject `AccumulationApi accumulationApi`. After the `RETURN_OF_PREMIUM` block:

```java
        if (i.kind() == PayoutKind.MATURITY && i.getOriginalAmount() == null) {
            // An account-value maturity (product step 3): accumulation closes the account on the due
            // date and this instalment pays what the MATURITY entry moved -- one figure, not two.
            valued = accumulationApi.closeForMaturity(i.getPolicyNumber(), i.getInstalmentId(), i.getDueDate());
            if (valued.signum() == 0) {
                i.cancel("The account held nothing on the maturity date");
                instalments.save(i);
                matureIfEndOfTerm(i);
                return;
            }
        }
```

Read `PayoutInstalment.fallDue(boolean, BigDecimal)` first. It must set the amount from `valued` when one is passed, as it does for a premium return. If it only does that for `RETURN_OF_PREMIUM`, widen its condition to "the original amount is null" rather than to the kind.

`closeForMaturity` in `AccumulationApiImpl`:

```java
    @Override
    @Transactional
    public BigDecimal closeForMaturity(String policyNumber, UUID instalmentId, LocalDate dueDate) {
        LedgerService.Source source = new LedgerService.Source("instalment", "instalment:" + instalmentId);
        // Idempotent: a fallDue that retries after this committed gets the same figure back.
        Optional<Posting> done = postings.findByTenantIdAndSourceTypeAndSourceRef(TenantContext.get(), source.type(), source.ref());
        if (done.isPresent()) {
            return entries.findByPostingIdOrderBySeq(done.get().getPostingId()).stream()
                .filter(e -> e.type() == EntryType.MATURITY).map(e -> e.getAmount().negate())
                .findFirst().orElse(BigDecimal.ZERO.setScale(2));
        }
        Account account = accounts.lockForPosting(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber));
        if (account.status() != AccountStatus.OPEN) {
            return BigDecimal.ZERO.setScale(2);
        }
        return close(account, source, EntryType.MATURITY, dueDate, "Matured", "MATURED", "system", null);
    }
```

The due drain runs `fallDue` inside benefitpayout's transaction. `closeForMaturity` joins it, so the closing entry and the DUE instalment commit together or not at all.

`benefitpayout/package-info.java`: add `"accumulation::api"` to `allowedDependencies`.

- [ ] **Step 6: Death**

```java
    @Override
    @Transactional(readOnly = true)
    public DeathValuation valueAtDeath(String policyNumber, LocalDate dateOfDeath) {
        Account account = accounts.findById(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber));
        List<LedgerEntry> all = entries.findByPolicyNumberOrderBySeq(policyNumber);
        BigDecimal atDeath = afterDeathRemoved(all, dateOfDeath).stream()
            .map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal interest = valuer.interestBetween(account, interestFrom(account, dateOfDeath), dateOfDeath);
        BigDecimal premiumsBefore = all.stream()
            .filter(e -> (e.type() == EntryType.CONTRIBUTION || e.type() == EntryType.TOP_UP)
                && !e.getEffectiveDate().isAfter(dateOfDeath))
            .map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal contributionsAfter = all.stream()
            .filter(e -> (e.type() == EntryType.CONTRIBUTION || e.type() == EntryType.TOP_UP)
                && e.getEffectiveDate().isAfter(dateOfDeath))
            .map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new DeathValuation(atDeath.add(interest), premiumsBefore, contributionsAfter, account.getCurrency());
    }

    /** Rule 4: every entry the death removes -- dated after it, except a REVERSAL restoring money held before it. */
    private static List<LedgerEntry> afterDeathRemoved(List<LedgerEntry> all, LocalDate dateOfDeath) {
        Map<UUID, LedgerEntry> byId = all.stream().collect(Collectors.toMap(LedgerEntry::getEntryId, e -> e));
        return all.stream().filter(e -> !e.getEffectiveDate().isAfter(dateOfDeath)
            || (e.type() == EntryType.REVERSAL && byId.containsKey(e.getReversesEntryId())
                && !byId.get(e.getReversesEntryId()).getEffectiveDate().isAfter(dateOfDeath))).toList();
    }
```

`interestBetween` only reads entries effective on or before the date it is given, so interest to the death already ignores everything after it. The opening balance it sums uses `isBefore(from)`; entries after the death are never before a date on or before it.

Entries after the death that are already reversed must not be reversed again: `ux_ledger_entry_reversed_once` would refuse it, and the whole posting with it. Filter them out:

```java
    /** {@code claims.ClaimApproved} with claimType DEATH: rule 4. */
    @Transactional
    public void closeForDeath(String policyNumber, UUID claimId, LocalDate dateOfDeath, String approvedBy) {
        Account account = accounts.lockForPosting(policyNumber).orElse(null);
        if (account == null || account.status() != AccountStatus.OPEN) {
            return;
        }
        List<LedgerEntry> all = entries.findByPolicyNumberOrderBySeq(policyNumber);
        Set<UUID> kept = afterDeathRemoved(all, dateOfDeath).stream().map(LedgerEntry::getEntryId).collect(Collectors.toSet());
        Set<UUID> alreadyReversed = all.stream().map(LedgerEntry::getReversesEntryId).filter(Objects::nonNull)
            .collect(Collectors.toSet());
        List<LedgerService.Line> lines = new ArrayList<>();
        // Newest first, so the running balance never dips below zero part-way: a later fee is
        // undone before the earlier contribution that paid for it.
        for (LedgerEntry e : all.reversed()) {
            if (!kept.contains(e.getEntryId()) && e.type() != EntryType.REVERSAL && !alreadyReversed.contains(e.getEntryId())) {
                lines.add(new LedgerService.Line(EntryType.REVERSAL, e.getAmount().negate(), dateOfDeath,
                    "Dated after the death on " + dateOfDeath, e.getEntryId()));
            }
        }
        BigDecimal reversedTotal = lines.stream().map(LedgerService.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal interest = valuer.interestBetween(account, interestFrom(account, dateOfDeath), dateOfDeath);
        BigDecimal value = account.getBalance().add(reversedTotal).add(interest);
        lines.add(LedgerService.Line.of(EntryType.INTEREST, interest, dateOfDeath, "Interest to the date of death"));
        lines.add(LedgerService.Line.of(EntryType.DEATH_CLAIM, value.negate(), dateOfDeath, "Death claim " + claimId));
        ledger.post(policyNumber, new LedgerService.Source("claim", "claim:" + claimId), lines, "system", approvedBy);
        account.close("DEATH", dateOfDeath);
        accounts.save(account);
    }
```

`List.reversed()` needs Java 21. Check `<java.version>` in the pom; on 17, use `new ArrayList<>(all)` and `Collections.reverse`.

Reversals are posted newest first, so the balance passes back through states that already existed and never goes below zero. The one exception is a reversed withdrawal that was later restored by a reversal, which is excluded. The `balance_after >= 0` CHECK is the backstop: if it ever fires, the whole posting rolls back and nothing half-closes.

`value` computed above must equal `valueAtDeath(...).accountValue()`. The test asserts it, and that is the reason the two methods share `afterDeathRemoved`.

```java
// accumulation/application/ClaimEventListener.java
package tz.co.nlolo.lifeplatform.accumulation.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.time.LocalDate;
import java.util.UUID;

@Component("accumulationClaimEventListener")
public class ClaimEventListener {

    private final AccumulationApiImpl api;
    private final EnvelopeRunner runner;

    public ClaimEventListener(AccumulationApiImpl api, EnvelopeRunner runner) {
        this.api = api;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"claims.ClaimApproved".equals(envelope.eventType())) {
            return;
        }
        runner.run(envelope, p -> {
            if (!"DEATH".equals(p.get("claimType"))) {
                return;
            }
            api.closeForDeath((String) p.get("policyNumber"), UUID.fromString(String.valueOf(p.get("claimId"))),
                LocalDate.parse((String) p.get("dateOfEvent")), "system:claims");
        });
    }
}
```

The ceiling overload, in `BenefitPayoutApi`:

```java
    /**
     * The death ceiling as at the date of death (product step 3). For an account-valued version it is
     * the account at the death, or the premium floor if higher, plus contributions paid after it
     * (spec §10.5). Every other version: exactly {@link #deathBenefitCeiling(String, java.math.BigDecimal)}.
     */
    java.math.BigDecimal deathBenefitCeiling(String policyNumber, java.math.BigDecimal sumAssuredCeiling,
                                             java.time.LocalDate dateOfDeath);
```

In `BenefitPayoutApiImpl`:

```java
    @Override
    @Transactional(readOnly = true)
    public BigDecimal deathBenefitCeiling(String policyNumber, BigDecimal sumAssuredCeiling, LocalDate dateOfDeath) {
        if (!accumulationApi.isAccount(policyNumber)) {
            return deathBenefitCeiling(policyNumber, sumAssuredCeiling);
        }
        DeathValuation v = accumulationApi.valueAtDeath(policyNumber, dateOfDeath);
        BigDecimal base = v.accountValue();
        PayoutPlan plan = productApi.resolvePayoutPlan(policyApi.getPolicy(policyNumber).productVersionId());
        BigDecimal pct = plan.authored() ? plan.terms().deathBenefitPremiumPercent() : null;
        if (pct != null) {
            base = base.max(PayoutArithmetic.percentOf(v.premiumsBeforeDeath(), pct));
        }
        return base.add(v.contributionsAfterDeath());
    }
```

`ClaimsApiImpl`, at line 568: call the three-argument overload with `claim.getDateOfEvent()`. Run the claims tests that mock `BenefitPayoutApi` and update their stubs to the new arity: `grep -rln "deathBenefitCeiling" src/test/java`.

- [ ] **Step 7: The quote over REST**

```java
    /** What a closing today would pay, before any surrender charge. The surrender approval screen shows it. */
    @GetMapping("/policies/{policyNumber}/account/quote")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ClosingQuoteResponse quote(@PathVariable String policyNumber) {
        return ClosingQuoteResponse.from(api.quoteClosing(policyNumber, LocalDate.now()));
    }
```

`ClosingQuoteResponse(String policyNumber, String asOf, Money balance, Money interestToDate, Money value)`. Use the same `Money` type as `AccountResponse`. Add the path and the schema to `openapi-accumulation.yaml`, and one contract test that reads it to spec after one contribution.

- [ ] **Step 8: Run**

Run: `./mvnw -o test -Dtest='ClosingIntegrationTest,ScheduleExpanderTest,AccumulationContractTest,AccountPolicyLifecycleIntegrationTest,ModularityTests'` — Expected: PASS. `AccountPolicyLifecycleIntegrationTest.paidUpOnAnAccountPolicyDoesNotRestateItsMaturityPayout` can bite now (Task 5's note).

Then run the suites of every module this task touched on the existing paths, to show scale behaviour is unchanged:

`./mvnw -o test -Dtest='*Surrender*,*Claims*,*BenefitPayout*,*Payout*'` — Expected: PASS.

`AsyncAPI`: register `policy.AccountSurrenderApproved` with its six keys. Note on `policy.SurrenderPayoutRequested` that it is not published for an account-valued version.

- [ ] **Step 9: Commit**

```bash
git add src/main/java src/test/java api
git commit -m "feat(accumulation): surrender, maturity, death and free-look close the account, each in one posting"
```

---

### Task 8: Statements — the console view, the PDF, the annual run, the SMS

**Files:**
- Modify: `pom.xml` (PDFBox)
- Create: `db-migrations/document/V6__account_statement_document_type.sql`, `db-migrations/communication/V10__account_statement_template.sql`
- Modify: `document/api/DocumentType.java` (`ACCOUNT_STATEMENT`)
- Create: `accumulation/api/StatementView.java`, `StatementRecordView.java`
- Create: `accumulation/application/StatementBuilder.java`, `StatementPdf.java`, `StatementDrain.java`
- Create: `accumulation/domain/Statement.java`, `accumulation/infrastructure/StatementRepository.java`
- Create: `communication/application/AccumulationEventListener.java`
- Modify: `accumulation/package-info.java` (adds `"document::api"`), `AccumulationApi.java`, `AccumulationApiImpl.java`, `LedgerEntryRepository.java`, `AccumulationController.java`, `openapi-accumulation.yaml`, `api/asyncapi-events.yaml`, `application-local.yml`
- Test: `StatementBuilderTest.java` (unit), `StatementIntegrationTest.java` (with MinIO), `AccumulationContractTest.java`

**Interfaces:**
- Consumes: `LedgerEntryView`, `AccumulationApi.entries` (Task 2), `DocumentApi.upload(ownerContext, type, uploadedBy, stream, length, contentType, fileName) -> String`, `DocumentApi.download(String) -> byte[]`
- Produces:
  - `record StatementView(String policyNumber, LocalDate periodFrom, LocalDate periodTo, int lastSeq, BigDecimal openingBalance, List<StatementView.Group> groups, BigDecimal closingBalance, String currency)`, with `record Group(EntryType type, BigDecimal total, List<LedgerEntryView> entries)`
  - `record StatementRecordView(UUID statementId, String policyNumber, LocalDate periodFrom, LocalDate periodTo, int lastSeq, String documentRef, String generatedBy, Instant generatedAt)`
  - `StatementBuilder.build(String policyNumber, String currency, List<LedgerEntryView> entries, LocalDate from, LocalDate to, int lastSeq) -> StatementView`
  - `AccumulationApi.statement(String policyNumber, LocalDate from, LocalDate to) -> StatementView`, `generateStatement(String policyNumber, LocalDate from, LocalDate to, String generatedBy) -> StatementRecordView`, `listStatements(String policyNumber)`, `statementPdf(UUID statementId) -> byte[]`
  - Event `accumulation.StatementIssued {statementId, policyNumber, policyholderPartyId, periodFrom, periodTo, closingBalance{amount,currencyCode}, interestCredited{amount,currencyCode}, annual}`

**The rules:**
1. A statement selects entries by EFFECTIVE date within `[from, to]` and with `seq ≤ lastSeq`. The opening balance is every entry effective before `from` with `seq ≤ lastSeq`. `lastSeq` is the account's last sequence number when the statement is made.
2. Reconciliation (spec §6) is checked by a separate SQL sum, not by re-adding the same list: `opening + Σ groups` must equal `SUM(amount) WHERE effective_date <= to AND seq <= lastSeq`. That catches a grouping or rendering bug which drops or doubles a line. A mismatch throws `IllegalStateException` and no statement is filed. A statement that does not add up must never reach a customer.
3. A statement is filed once per `(policy, from, to, lastSeq)`. Asking again with nothing new posted returns the existing record, which makes regeneration provably identical. A correction after it is a new entry with a higher seq, so it appears in the NEXT statement and never changes an old one.
4. The annual run covers the last full calendar year, from `max(1 January, the opening day)`. Only the annual run sends the SMS (`annual: true`). An on-demand statement is staff's, and staff deliver it (spec §6).
5. The PDF is built from the same `StatementView` the console shows. There is no second calculation.

- [ ] **Step 1: PDFBox**

Add to `pom.xml` beside the other runtime dependencies:

```xml
        <!-- Product step 3: savings account statements. Apache-2.0, pure Java, no native binaries. -->
        <dependency>
            <groupId>org.apache.pdfbox</groupId>
            <artifactId>pdfbox</artifactId>
            <version>3.0.3</version>
        </dependency>
```

This build cannot be `-o` (offline) the first time. Run `./mvnw dependency:resolve -q` once with network, then go back to `-o`. Check Maven Central for the latest 3.0.x and use that version; the API used below is stable across 3.0.

- [ ] **Step 2: Write the failing builder test**

```java
// src/test/java/tz/co/nlolo/lifeplatform/accumulation/StatementBuilderTest.java
package tz.co.nlolo.lifeplatform.accumulation;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.accumulation.api.EntryType;
import tz.co.nlolo.lifeplatform.accumulation.api.LedgerEntryView;
import tz.co.nlolo.lifeplatform.accumulation.api.StatementView;
import tz.co.nlolo.lifeplatform.accumulation.application.StatementBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StatementBuilderTest {

    private static final LocalDate FROM = LocalDate.of(2025, 1, 1);
    private static final LocalDate TO = LocalDate.of(2025, 12, 31);

    private final List<LedgerEntryView> entries = new ArrayList<>();
    private BigDecimal running = BigDecimal.ZERO;

    private void entry(EntryType type, String amount, LocalDate on) {
        running = running.add(new BigDecimal(amount));
        entries.add(new LedgerEntryView(UUID.randomUUID(), UUID.randomUUID(), entries.size() + 1, type,
            new BigDecimal(amount), running, on, Instant.now(), "test", "test:" + entries.size(), null, null, "t", null));
    }

    @Test
    void openingIsEverythingBeforeThePeriodAndClosingAddsTheGroups() {
        entry(EntryType.CONTRIBUTION, "100000.00", LocalDate.of(2024, 12, 1));
        entry(EntryType.INTEREST, "250.00", LocalDate.of(2024, 12, 31));
        entry(EntryType.CONTRIBUTION, "50000.00", LocalDate.of(2025, 3, 1));
        entry(EntryType.ALLOCATION_CHARGE, "-500.00", LocalDate.of(2025, 3, 1));
        entry(EntryType.INTEREST, "400.00", LocalDate.of(2025, 3, 31));
        entry(EntryType.POLICY_FEE, "-1000.00", LocalDate.of(2025, 3, 31));

        StatementView s = StatementBuilder.build("POL-X", "TZS", entries, FROM, TO, entries.size());

        assertThat(s.openingBalance()).isEqualByComparingTo("100250.00");
        assertThat(s.closingBalance()).isEqualByComparingTo("149150.00");
        assertThat(s.groups()).extracting(StatementView.Group::type)
            .containsExactly(EntryType.CONTRIBUTION, EntryType.ALLOCATION_CHARGE, EntryType.INTEREST, EntryType.POLICY_FEE);
        assertThat(s.groups()).filteredOn(g -> g.type() == EntryType.INTEREST).singleElement()
            .satisfies(g -> assertThat(g.total()).isEqualByComparingTo("400.00"));
    }

    @Test
    void entriesPastTheLastSeqAreLeftForTheNextStatement() {
        entry(EntryType.CONTRIBUTION, "100000.00", LocalDate.of(2025, 2, 1));
        entry(EntryType.CONTRIBUTION, "50000.00", LocalDate.of(2025, 3, 1));
        StatementView s = StatementBuilder.build("POL-X", "TZS", entries, FROM, TO, 1);
        assertThat(s.closingBalance()).isEqualByComparingTo("100000.00");
        assertThat(s.lastSeq()).isEqualTo(1);
    }

    @Test
    void groupsFollowTheOrderMoneyMoves() {
        // In, then charges, then interest, then out: the order a customer reads a bank statement in,
        // not the enum's declaration order.
        entry(EntryType.WITHDRAWAL, "-1.00", LocalDate.of(2025, 5, 1));
        entry(EntryType.INTEREST, "1.00", LocalDate.of(2025, 4, 30));
        entry(EntryType.TOP_UP, "1.00", LocalDate.of(2025, 4, 1));
        StatementView s = StatementBuilder.build("POL-X", "TZS", entries, FROM, TO, entries.size());
        assertThat(s.groups()).extracting(StatementView.Group::type)
            .containsExactly(EntryType.TOP_UP, EntryType.INTEREST, EntryType.WITHDRAWAL);
    }
}
```

`groupsFollowTheOrderMoneyMoves` builds a ledger whose balance dips below zero on its first entry. That is fine for a pure builder test, because the database is not involved; the builder must not assume a non-negative running balance.

Run: `./mvnw -o test -Dtest=StatementBuilderTest` — Expected: FAIL to compile.

- [ ] **Step 3: The views and the builder**

```java
// accumulation/api/StatementView.java
package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record StatementView(String policyNumber, LocalDate periodFrom, LocalDate periodTo, int lastSeq,
                            BigDecimal openingBalance, List<Group> groups, BigDecimal closingBalance, String currency) {

    public record Group(EntryType type, BigDecimal total, List<LedgerEntryView> entries) {}

    /** INTEREST's total, the figure the annual SMS reports. Zero when none was credited. */
    public BigDecimal interestCredited() {
        return groups.stream().filter(g -> g.type() == EntryType.INTEREST).map(Group::total)
            .findFirst().orElse(BigDecimal.ZERO.setScale(2));
    }
}
```

```java
// accumulation/api/StatementRecordView.java
package tz.co.nlolo.lifeplatform.accumulation.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StatementRecordView(UUID statementId, String policyNumber, LocalDate periodFrom, LocalDate periodTo,
                                  int lastSeq, String documentRef, String generatedBy, Instant generatedAt) {}
```

```java
// accumulation/application/StatementBuilder.java
package tz.co.nlolo.lifeplatform.accumulation.application;

import tz.co.nlolo.lifeplatform.accumulation.api.EntryType;
import tz.co.nlolo.lifeplatform.accumulation.api.LedgerEntryView;
import tz.co.nlolo.lifeplatform.accumulation.api.StatementView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A period of an account, from its entries alone. Pure, so the console and the PDF cannot disagree. */
public final class StatementBuilder {
    private StatementBuilder() {}

    /** The order a customer reads a statement in: money in, charges, interest, money out, corrections. */
    private static final List<EntryType> ORDER = List.of(
        EntryType.CONTRIBUTION, EntryType.TOP_UP, EntryType.TRANSFER_IN,
        EntryType.ALLOCATION_CHARGE, EntryType.POLICY_FEE, EntryType.INTEREST,
        EntryType.WITHDRAWAL, EntryType.SURRENDER, EntryType.MATURITY, EntryType.DEATH_CLAIM, EntryType.FREE_LOOK_REFUND,
        EntryType.ADJUSTMENT, EntryType.REVERSAL);

    public static StatementView build(String policyNumber, String currency, List<LedgerEntryView> entries,
                                      LocalDate from, LocalDate to, int lastSeq) {
        List<LedgerEntryView> upTo = entries.stream().filter(e -> e.seq() <= lastSeq).toList();
        BigDecimal opening = upTo.stream().filter(e -> e.effectiveDate().isBefore(from))
            .map(LedgerEntryView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<LedgerEntryView> within = upTo.stream()
            .filter(e -> !e.effectiveDate().isBefore(from) && !e.effectiveDate().isAfter(to))
            .sorted(Comparator.comparing(LedgerEntryView::effectiveDate).thenComparingInt(LedgerEntryView::seq))
            .toList();
        List<StatementView.Group> groups = new ArrayList<>();
        BigDecimal closing = opening;
        for (EntryType type : ORDER) {
            List<LedgerEntryView> ofType = within.stream().filter(e -> e.type() == type).toList();
            if (ofType.isEmpty()) continue;
            BigDecimal total = ofType.stream().map(LedgerEntryView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            groups.add(new StatementView.Group(type, total, ofType));
            closing = closing.add(total);
        }
        return new StatementView(policyNumber, from, to, lastSeq, opening.setScale(2), List.copyOf(groups),
            closing.setScale(2), currency);
    }
}
```

`ORDER` must list all thirteen entry types. If an enum value is ever missing from it, its entries vanish from the statement, and rule 2's reconciliation is what catches that. That is exactly why the reconciliation does not reuse this list.

Run: `./mvnw -o test -Dtest=StatementBuilderTest` — Expected: PASS, 3 tests.

- [ ] **Step 4: Migrations and the document type**

```sql
-- db-migrations/document/V6__account_statement_document_type.sql
-- Product step 3: a savings account's statement, filed against its policy.
--
-- Its own type rather than POLICY_DOCUMENT: a statement is a record of money at a date, regenerated
-- each year, and one filed as "the policy document" is the wrong evidence in a dispute about either.
-- The list exists twice, as document.api.DocumentType and as this CHECK. The constraint name is the
-- one V4 created and V5 replaced -- a DROP with a wrong name would be a silent no-op.
ALTER TABLE document.document_record
    DROP CONSTRAINT IF EXISTS document_record_document_type_check;

ALTER TABLE document.document_record
    ADD CONSTRAINT document_record_document_type_check
    CHECK (document_type IN ('KYC_EVIDENCE','POLICY_DOCUMENT','CLAIM_EVIDENCE','SIGNED_FORM',
                             'UNDERWRITING_EVIDENCE','ENROLMENT_SCHEDULE','EXITS_FILE','ACCOUNT_STATEMENT'));
```

Add `ACCOUNT_STATEMENT` to `DocumentType`, with a javadoc in the style of its neighbours. It routes to `MinioDocumentStorage.bucketFor`'s default arm (`policy-documents`), for the reason `ENROLMENT_SCHEDULE`'s javadoc gives.

```sql
-- db-migrations/communication/V10__account_statement_template.sql
-- Product step 3: the yearly savings summary (spec §6).
--
-- One line, two figures: the balance at the year end and the interest credited during it. Both come
-- off the statement accumulation filed, which reconciled before it was filed -- so nothing here is a
-- figure the customer could not find again on the PDF.
--
-- Placeholders: policyNumber, year, balance, interest.
INSERT INTO communication.notification_template (tenant_id, template_key, channel, language, body_template) VALUES

('00000000-0000-0000-0000-000000000000', 'ACCOUNT_STATEMENT', 'SMS', 'sw',
 'Bima {{policyNumber}}: salio la akiba tarehe 31/12/{{year}} ni {{balance}}. Riba iliyoongezwa {{year}}: {{interest}}.'),
('00000000-0000-0000-0000-000000000000', 'ACCOUNT_STATEMENT', 'SMS', 'en',
 'Policy {{policyNumber}}: your savings balance on 31/12/{{year}} is {{balance}}. Interest credited in {{year}}: {{interest}}.'),
('00000000-0000-0000-0000-000000000000', 'ACCOUNT_STATEMENT', 'EMAIL', 'sw',
 'Bima {{policyNumber}}: salio la akiba tarehe 31/12/{{year}} ni {{balance}}. Riba iliyoongezwa {{year}}: {{interest}}. Taarifa kamili inapatikana kwa ombi.'),
('00000000-0000-0000-0000-000000000000', 'ACCOUNT_STATEMENT', 'EMAIL', 'en',
 'Policy {{policyNumber}}: your savings balance on 31/12/{{year}} is {{balance}}. Interest credited in {{year}}: {{interest}}. A full statement is available on request.');
```

Have the Swahili wording checked by someone who reads it before release. The `sw` lines follow V9's register, but this message is new.

Add both migrations to the test lists that already include `document/V5` or `communication/V9`: `grep -rl "document/V5__\|communication/V9__" src/test/java`.

- [ ] **Step 5: The statement aggregate and the reconciling sum**

```java
// accumulation/domain/Statement.java
package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A filed statement and exactly what it was built from (spec §6). */
@Entity
@Table(name = "statement", schema = "accumulation")
public class Statement {
    @Id @UuidGenerator @Column(name = "statement_id") private UUID statementId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "period_from", nullable = false) private LocalDate periodFrom;
    @Column(name = "period_to", nullable = false) private LocalDate periodTo;
    @Column(name = "last_seq", nullable = false) private int lastSeq;
    @Column(name = "document_ref", nullable = false) private String documentRef;
    @Column(name = "generated_by", nullable = false) private String generatedBy;
    @Column(name = "generated_at", nullable = false) private Instant generatedAt = Instant.now();

    protected Statement() {}

    public Statement(UUID tenantId, String policyNumber, LocalDate periodFrom, LocalDate periodTo, int lastSeq,
                     String documentRef, String generatedBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.lastSeq = lastSeq;
        this.documentRef = documentRef;
        this.generatedBy = generatedBy;
    }

    public UUID getStatementId() { return statementId; }
    public String getPolicyNumber() { return policyNumber; }
    public LocalDate getPeriodFrom() { return periodFrom; }
    public LocalDate getPeriodTo() { return periodTo; }
    public int getLastSeq() { return lastSeq; }
    public String getDocumentRef() { return documentRef; }
    public String getGeneratedBy() { return generatedBy; }
    public Instant getGeneratedAt() { return generatedAt; }
}
```

```java
// accumulation/infrastructure/StatementRepository.java
package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.accumulation.domain.Statement;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatementRepository extends JpaRepository<Statement, UUID> {
    List<Statement> findByPolicyNumberOrderByPeriodToDescGeneratedAtDesc(String policyNumber);
    Optional<Statement> findByPolicyNumberAndPeriodFromAndPeriodToAndLastSeq(String policyNumber, LocalDate from,
                                                                            LocalDate to, int lastSeq);
}
```

Add a unique index for rule 3 to the accumulation schema. This is still Task 2's `V1`, unmerged, so edit it in place rather than adding a V2:

```sql
CREATE UNIQUE INDEX ux_statement_identity ON accumulation.statement (policy_number, period_from, period_to, last_seq);
```

Add to `LedgerEntryRepository`, for rule 2:

```java
    @Query("select coalesce(sum(e.amount), 0) from LedgerEntry e where e.policyNumber = :policyNumber "
        + "and e.effectiveDate <= :to and e.seq <= :lastSeq")
    BigDecimal sumThrough(@Param("policyNumber") String policyNumber, @Param("to") LocalDate to,
                          @Param("lastSeq") int lastSeq);
```

- [ ] **Step 6: The PDF**

```java
// accumulation/application/StatementPdf.java
package tz.co.nlolo.lifeplatform.accumulation.application;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import tz.co.nlolo.lifeplatform.accumulation.api.LedgerEntryView;
import tz.co.nlolo.lifeplatform.accumulation.api.StatementView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The statement as a PDF, laid out from the SAME {@link StatementView} the console shows (rule 5).
 *
 * <p>Standard-14 Helvetica only: no font file to ship, and every glyph a statement needs (digits,
 * Latin letters, the comma and full stop) is in it. A long statement continues onto further pages.
 */
final class StatementPdf {
    private StatementPdf() {}

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final float MARGIN = 50f;
    private static final float LINE = 14f;

    static byte[] render(StatementView s, String insurerName) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Writer w = new Writer(doc);
            w.line(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14, insurerName + " -- savings account statement");
            w.text("Policy " + s.policyNumber() + "    Period " + DMY.format(s.periodFrom()) + " to " + DMY.format(s.periodTo()));
            w.gap();
            w.text("Opening balance " + DMY.format(s.periodFrom()) + ":  " + money(s.openingBalance(), s.currency()));
            w.gap();
            for (StatementView.Group g : s.groups()) {
                w.line(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 11,
                    StatementLabels.of(g.type()) + "  (" + money(g.total(), s.currency()) + ")");
                for (LedgerEntryView e : g.entries()) {
                    w.text("   " + DMY.format(e.effectiveDate()) + "   " + money(e.amount(), s.currency())
                        + (e.reason() != null ? "   " + e.reason() : ""));
                }
                w.gap();
            }
            w.line(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 12,
                "Closing balance " + DMY.format(s.periodTo()) + ":  " + money(s.closingBalance(), s.currency()));
            w.text("Includes every entry up to number " + s.lastSeq() + ". A later correction appears on the next statement.");
            w.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render the statement for " + s.policyNumber(), e);
        }
    }

    private static String money(BigDecimal amount, String currency) {
        return currency + " " + new DecimalFormat("#,##0.00;-#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(amount);
    }

    /** Writes top to bottom, starting a new page when one fills. */
    private static final class Writer {
        private final PDDocument doc;
        private PDPageContentStream stream;
        private float y;

        Writer(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        private void newPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            stream = new PDPageContentStream(doc, page);
            y = page.getMediaBox().getHeight() - MARGIN;
        }

        void text(String s) throws IOException {
            line(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10, s);
        }

        void line(PDType1Font font, float size, String s) throws IOException {
            if (y < MARGIN) newPage();
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(MARGIN, y);
            // Standard-14 fonts encode WinAnsi only; anything else (a reason a person typed) is
            // replaced rather than allowed to fail the whole statement.
            stream.showText(s.replaceAll("[^\\x20-\\x7E]", "?"));
            stream.endText();
            y -= LINE;
        }

        void gap() { y -= LINE / 2; }

        void close() throws IOException { stream.close(); }
    }
}
```

```java
// accumulation/application/StatementLabels.java
package tz.co.nlolo.lifeplatform.accumulation.application;

import tz.co.nlolo.lifeplatform.accumulation.api.EntryType;

/** A clerk's words for each entry type, shared by the PDF. The console keeps its own copy in its status maps. */
final class StatementLabels {
    private StatementLabels() {}

    static String of(EntryType type) {
        return switch (type) {
            case CONTRIBUTION -> "Premiums";
            case TOP_UP -> "Top-ups";
            case TRANSFER_IN -> "Transfers in";
            case ALLOCATION_CHARGE -> "Allocation charges";
            case POLICY_FEE -> "Policy fees";
            case INTEREST -> "Interest";
            case WITHDRAWAL -> "Withdrawals";
            case SURRENDER -> "Surrender";
            case MATURITY -> "Maturity";
            case DEATH_CLAIM -> "Death claim";
            case FREE_LOOK_REFUND -> "Free-look cancellation";
            case ADJUSTMENT -> "Adjustments";
            case REVERSAL -> "Reversals";
        };
    }
}
```

The `switch` has no `default`, so a fourteenth entry type will not compile until it has a label. That is the point.

The insurer name: check whether a tenant display name is reachable from accumulation's allowed dependencies. If it is not, pass the literal `"Nlolo Life"` and leave a note on the method. Do not add a module dependency just for a name.

- [ ] **Step 7: Generating, filing, listing**

Add to `AccumulationApi`:

```java
    /** Computed, not filed. The console's Statement tab. */
    StatementView statement(String policyNumber, java.time.LocalDate from, java.time.LocalDate to);

    /** Built, reconciled, rendered, filed -- or the existing record when nothing new was posted (rule 3). */
    StatementRecordView generateStatement(String policyNumber, java.time.LocalDate from, java.time.LocalDate to,
                                          String generatedBy);

    List<StatementRecordView> listStatements(String policyNumber);

    byte[] statementPdf(java.util.UUID statementId);
```

In `AccumulationApiImpl`, inject `StatementRepository statementRepository` and `DocumentApi documentApi`:

```java
    @Override
    @Transactional(readOnly = true)
    public StatementView statement(String policyNumber, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new AccumulationStateException("A statement's period must end on or after it starts");
        }
        Account account = accounts.findById(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber));
        StatementView view = StatementBuilder.build(policyNumber, account.getCurrency(), entries(policyNumber), from, to,
            account.getLastSeq());
        reconcile(view);
        return view;
    }

    /** Rule 2: the statement against an independent sum, not against itself. */
    private void reconcile(StatementView view) {
        BigDecimal ledger = entries.sumThrough(view.policyNumber(), view.periodTo(), view.lastSeq());
        if (view.closingBalance().compareTo(ledger) != 0) {
            throw new IllegalStateException("Statement for " + view.policyNumber() + " to " + view.periodTo()
                + " closes at " + view.closingBalance() + " but the ledger holds " + ledger + " -- not filed");
        }
    }

    @Override
    @Transactional
    public StatementRecordView generateStatement(String policyNumber, LocalDate from, LocalDate to, String generatedBy) {
        return toView(fileStatement(policyNumber, from, to, generatedBy, false));
    }

    /** Shared by on-demand and the annual run; only the annual run sets {@code annual}. */
    Statement fileStatement(String policyNumber, LocalDate from, LocalDate to, String generatedBy, boolean annual) {
        StatementView view = statement(policyNumber, from, to);
        Optional<Statement> existing = statementRepository.findByPolicyNumberAndPeriodFromAndPeriodToAndLastSeq(
            policyNumber, from, to, view.lastSeq());
        if (existing.isPresent()) {
            return existing.get();
        }
        byte[] pdf = StatementPdf.render(view, "Nlolo Life");
        String ref = documentApi.upload("policy:" + policyNumber, DocumentType.ACCOUNT_STATEMENT, generatedBy,
            new ByteArrayInputStream(pdf), pdf.length, "application/pdf",
            "statement-" + policyNumber + "-" + from + "-" + to + ".pdf");
        Statement saved = statementRepository.save(new Statement(TenantContext.get(), policyNumber, from, to,
            view.lastSeq(), ref, generatedBy));
        Account account = accounts.findById(policyNumber).orElseThrow();
        events.publishEvent(DomainEventEnvelope.of("accumulation.StatementIssued", TenantContext.get(), Map.of(
            "statementId", saved.getStatementId().toString(),
            "policyNumber", policyNumber,
            "policyholderPartyId", account.getPolicyholderPartyId(),
            "periodFrom", from.toString(),
            "periodTo", to.toString(),
            "closingBalance", Map.of("amount", view.closingBalance().toPlainString(), "currencyCode", view.currency()),
            "interestCredited", Map.of("amount", view.interestCredited().toPlainString(), "currencyCode", view.currency()),
            "annual", annual)));
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StatementRecordView> listStatements(String policyNumber) {
        return statementRepository.findByPolicyNumberOrderByPeriodToDescGeneratedAtDesc(policyNumber).stream()
            .map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] statementPdf(UUID statementId) {
        Statement s = statementRepository.findById(statementId)
            .orElseThrow(() -> new AccumulationStateException("No statement " + statementId));
        return documentApi.download(s.getDocumentRef());
    }

    private StatementRecordView toView(Statement s) {
        return new StatementRecordView(s.getStatementId(), s.getPolicyNumber(), s.getPeriodFrom(), s.getPeriodTo(),
            s.getLastSeq(), s.getDocumentRef(), s.getGeneratedBy(), s.getGeneratedAt());
    }
```

The upload is the one side effect that a rollback cannot undo. If the statement row then fails to save, an orphan object is left in MinIO and no record points to it. That is the same trade every other `DocumentApi.upload` caller on the platform makes; follow it rather than inventing a compensation here.

`accumulation/package-info.java`: `allowedDependencies = { "policy::api", "product::api", "document::api" }`.

- [ ] **Step 8: The annual drain and the SMS**

```java
// accumulation/application/StatementDrain.java
package tz.co.nlolo.lifeplatform.accumulation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.domain.Account;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.AccountRepository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Last year's statement for every open account, once (rule 4). Selection is SQL
 * ({@code accounts_due_annual_statement()}, which skips an account already holding a statement to
 * 31 December); filing is Java, because it uploads a document and publishes the SMS event.
 */
@Component
public class StatementDrain {

    private static final Logger log = LoggerFactory.getLogger(StatementDrain.class);

    private final AccountRepository accounts;
    private final AccumulationApiImpl api;
    private final TransactionTemplate requiresNew;

    public StatementDrain(AccountRepository accounts, AccumulationApiImpl api, PlatformTransactionManager tm) {
        this.accounts = accounts;
        this.api = api;
        this.requiresNew = new TransactionTemplate(tm);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${accumulation.statement-interval-ms:86400000}",
        initialDelayString = "${accumulation.statement-interval-ms:86400000}")
    public void drain() {
        for (Object[] row : accounts.findDueAnnualStatementAcrossTenants()) {
            fileOne((String) row[0], (UUID) row[1]);
        }
    }

    void fileOne(String policyNumber, UUID tenantId) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            requiresNew.executeWithoutResult(status -> {
                // December's interest first -- see the note below.
                api.postMonthEnds(policyNumber, LocalDate.now());
                Account account = accounts.findById(policyNumber).orElseThrow();
                LocalDate yearEnd = LocalDate.now().withDayOfYear(1).minusDays(1);
                LocalDate yearStart = yearEnd.withDayOfYear(1);
                LocalDate from = account.getOpenedOn().isAfter(yearStart) ? account.getOpenedOn() : yearStart;
                api.fileStatement(policyNumber, from, yearEnd, "system:annual-statement", true);
            });
        } catch (Exception e) {
            log.error("Annual statement failed for policy {} in tenant {}", policyNumber, tenantId, e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
```

The annual statement covers the year to 31 December, but December's month-end may not have posted yet when the drain first runs on 1 January. That is why `fileOne` calls `postMonthEnds` before filing, in the same `requiresNew` block. A statement that omitted December's interest would be filed, sent and frozen, and rule 3 would never let it be corrected. `postMonthEnds` is idempotent, so the month-end drain running too does no harm. An account that the catch-up exhausts closes, and its statement still files, ending on its zero.

Add `accumulation.statement-interval-ms: 30000` to `application-local.yml`.

```java
// communication/application/AccumulationEventListener.java
```

Copy `communication/application/BillingEventListener.java` whole, then change four things:
- the bean name, to `communicationAccumulationEventListener`
- the event type, to `accumulation.StatementIssued`
- an early return when `!Boolean.TRUE.equals(payload.get("annual"))`
- the call, to `notificationApi.notify(envelope.eventId(), (UUID) payload.get("policyholderPartyId"), (String) payload.get("policyNumber"), "ACCOUNT_STATEMENT", Map.of("policyNumber", ..., "year", String.valueOf(LocalDate.parse((String) payload.get("periodTo")).getYear()), "balance", money(payload.get("closingBalance")), "interest", money(payload.get("interestCredited"))))`.

Keep its `money` formatter and its never-rethrow posture.

- [ ] **Step 9: Write the integration test**

`StatementIntegrationTest`. The header is `ContributionIntegrationTest`'s, plus its own `MinIOContainer` and the bucket setup, copied from `SinglePremiumIntegrationTest` (lines 70–85 and its bucket creation). Create the bucket that `MinioDocumentStorage.bucketFor(ACCOUNT_STATEMENT)` returns. Add document V6 and communication V10 to the migration list.

```java
    private static final UUID TENANT = UUID.randomUUID();
    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private AccumulationApiImpl api;
    @Autowired private StatementDrain drain;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    private String fundedLastYear() {
        LocalDate start = LocalDate.now().withDayOfYear(1).minusMonths(6);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, start);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("100000.00"), start);
        asTenant(() -> { api.postMonthEnds(issued.policyNumber(), LocalDate.now()); return null; });
        return issued.policyNumber();
    }

    @Test
    void aStatementReconcilesAndItsPdfCarriesTheClosingBalance() throws Exception {
        String policy = fundedLastYear();
        LocalDate from = LocalDate.now().withDayOfYear(1).minusYears(1);
        LocalDate to = LocalDate.now().withDayOfYear(1).minusDays(1);
        StatementView view = asTenant(() -> api.statement(policy, from, to));
        StatementRecordView record = asTenant(() -> api.generateStatement(policy, from, to, "staff-one"));

        byte[] pdf = asTenant(() -> api.statementPdf(record.statementId()));
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            assertThat(text).contains(policy);
            assertThat(text).contains(new java.text.DecimalFormat("#,##0.00",
                java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ROOT)).format(view.closingBalance()));
        }
    }

    @Test
    void regeneratingWithNothingNewReturnsTheSameStatement() {
        String policy = fundedLastYear();
        LocalDate from = LocalDate.now().withDayOfYear(1).minusYears(1);
        LocalDate to = LocalDate.now().withDayOfYear(1).minusDays(1);
        var first = asTenant(() -> api.generateStatement(policy, from, to, "staff-one"));
        var second = asTenant(() -> api.generateStatement(policy, from, to, "staff-two"));
        assertThat(second.statementId()).isEqualTo(first.statementId());
        assertThat(asTenant(() -> api.listStatements(policy))).hasSize(1);
    }

    @Test
    void aLaterEntryMakesANewStatementAndLeavesTheOldOneAlone() {
        String policy = fundedLastYear();
        LocalDate from = LocalDate.now().withDayOfYear(1).minusYears(1);
        LocalDate to = LocalDate.now();
        var first = asTenant(() -> api.generateStatement(policy, from, to, "staff-one"));
        fixtures.collectPremium(TENANT, policy, UUID.randomUUID(), new BigDecimal("10000.00"), LocalDate.now());
        var second = asTenant(() -> api.generateStatement(policy, from, to, "staff-one"));
        assertThat(second.statementId()).isNotEqualTo(first.statementId());
        assertThat(second.lastSeq()).isGreaterThan(first.lastSeq());
    }

    @Test
    void theAnnualRunFilesLastYearOnceAndAsksForTheSms() {
        String policy = fundedLastYear();
        drain.drain();
        drain.drain();
        assertThat(asTenant(() -> api.listStatements(policy))).singleElement()
            .satisfies(s -> assertThat(s.periodTo()).isEqualTo(LocalDate.now().withDayOfYear(1).minusDays(1)));
        // communication's dispatch row for ACCOUNT_STATEMENT, read over the owner JDBC connection.
        assertThat(dispatchCount(policy, "ACCOUNT_STATEMENT")).isEqualTo(1);
    }
```

`dispatchCount` counts `communication.notification_dispatch` rows for the policy and template key, over the owner connection. Check the real table and column names in communication V1/V4 first.

`fundedLastYear` starts six months before 1 January, so the annual selector finds the account (`opened_on <= last 31 December`). Run in January, that is still last July, which is fine.

Run: `./mvnw -o test -Dtest='StatementBuilderTest,StatementIntegrationTest'` — Expected: PASS, 7 tests.

- [ ] **Step 10: REST**

```java
    @GetMapping("/policies/{policyNumber}/account/statement")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public StatementResponse statement(@PathVariable String policyNumber,
                                       @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return StatementResponse.from(api.statement(policyNumber, from, to));
    }

    @PostMapping("/policies/{policyNumber}/account/statements")
    @PreAuthorize("hasRole('REALM_STAFF')")
    @ResponseStatus(HttpStatus.CREATED)
    public StatementRecordResponse generate(@PathVariable String policyNumber, @Valid @RequestBody StatementPeriodBody body,
                                            @AuthenticationPrincipal Jwt jwt) {
        return StatementRecordResponse.from(api.generateStatement(policyNumber, body.from(), body.to(), jwt.getSubject()));
    }

    @GetMapping("/policies/{policyNumber}/account/statements")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<StatementRecordResponse> statements(@PathVariable String policyNumber) {
        return api.listStatements(policyNumber).stream().map(StatementRecordResponse::from).toList();
    }

    @GetMapping(value = "/account-statements/{statementId}/pdf", produces = "application/pdf")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID statementId) {
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"statement-" + statementId + ".pdf\"")
            .body(api.statementPdf(statementId));
    }
```

`StatementPeriodBody(@NotNull LocalDate from, @NotNull LocalDate to)`. `StatementResponse` mirrors `StatementView`, with money as `{amount, currencyCode}` and the groups' entries in `AccountResponse.EntryResponse`'s shape; reuse that record. `StatementRecordResponse` mirrors `StatementRecordView` but leaves out `documentRef`: an internal storage key is not the console's business, and it downloads by statement id.

`/account-statements/{id}/pdf` loads by id under RLS, so a statement from another tenant is a 422 "No statement". Whether any staff member may download any policy's statement is the same question as the account GET, and gets the same answer.

`openapi-accumulation.yaml` gets the four paths, `Statement`, `StatementGroup`, `StatementRecord` and `StatementPeriod`. The PDF path's 200 is `application/pdf` with `type: string, format: binary`. `AccumulationContractTest` gets the computed statement, the 201 generate, and the list, all to spec. The PDF endpoint is covered by `StatementIntegrationTest`'s byte check; the validator cannot read a binary body.

AsyncAPI: register `accumulation.StatementIssued` with its eight keys.

Run: `./mvnw -o test -Dtest='AccumulationContractTest,ModularityTests'` — Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add pom.xml db-migrations/document/V6__account_statement_document_type.sql db-migrations/communication/V10__account_statement_template.sql db-migrations/accumulation src/main/java src/test/java api src/main/resources/application-local.yml
git commit -m "feat(accumulation): reconciled statements, filed as PDFs, with a yearly SMS summary"
```

---

### Task 9: The console

The frontend rules for this platform apply here: use only existing components and patterns, and **invent nothing**. Every screen below copies a step 2 screen that already does the same job. Where this task names one, open it and follow it: its states, its gates, its idempotency, and its wording register.

**Files:**
- Generated: `frontend/src/types/api/accumulation.ts` (`npm run generate:api`; the script reads every `backend/api/openapi/*.yaml`, so no script change is needed)
- Modify: `frontend/src/api/types.ts` (aliases), `frontend/src/lib/status.ts` / the status-badge maps (`accountStatus`, `entryType`, `withdrawalStatus`, `topUpStatus`, `rateDeclarationStatus`)
- Create: `frontend/src/api/accumulation.ts`, `frontend/src/store/accumulationStore.ts`, `frontend/src/gates/accumulationGates.ts` (+ `.test.ts`)
- Create: `frontend/src/features/accounts/AccountPanel.tsx`, `StatementSection.tsx`, `WithdrawalAction.tsx`, `TopUpAction.tsx`, `TransferInAction.tsx`, `withdrawalForm.ts` (+ `.test.ts`), `AccountPanel.test.tsx`
- Create: `frontend/src/features/products/RateDeclarationsPanel.tsx`, `rateDeclarationForm.ts` (+ `.test.ts`)
- Modify: `frontend/src/features/policies/PolicyDetailPage.tsx` (an Account tab), `ValueActions.tsx` (the live surrender figure)
- Modify: `frontend/src/features/products/PublishVersionForm.tsx`, `publishVersionSchema.ts` (+ tests), `ProductDetailPage.tsx` (the rates panel)
- Create: `frontend/e2e/staff-savings-account.spec.ts`; Modify: `frontend/e2e/underwriting.ts` (`SAVINGS_PRODUCT`)
- Modify: `backend/scripts/seed-dev-data.sh` (a `SAVE-PLAN-01` ACCOUNT product)

**Interfaces:**
- Consumes: every REST path in `openapi-accumulation.yaml` (Tasks 4, 6, 7, 8), the product publish body's `accumulation` (Task 1)
- Produces: the screens below. No backend change.

- [ ] **Step 1: Types and the API module**

Run `npm run generate:api`. Then in `api/types.ts`:

```ts
import type { components as AccumulationComponents } from '@/types/api/accumulation';

/** A savings account and its whole ledger (product step 3). */
export type AccountView = AccumulationComponents['schemas']['Account'];
export type LedgerEntryView = AccumulationComponents['schemas']['LedgerEntry'];
export type EntryType = LedgerEntryView['type'];
export type WithdrawalView = AccumulationComponents['schemas']['Withdrawal'];
export type TopUpView = AccumulationComponents['schemas']['TopUp'];
export type TransferInView = AccumulationComponents['schemas']['TransferIn'];
export type ClosingQuoteView = AccumulationComponents['schemas']['ClosingQuote'];
export type StatementView = AccumulationComponents['schemas']['Statement'];
export type StatementRecordView = AccumulationComponents['schemas']['StatementRecord'];
export type RateDeclarationView = AccumulationComponents['schemas']['RateDeclaration'];
```

`api/accumulation.ts`: one function per path, shaped exactly like `api/benefitPayouts.ts`. Every mutation takes a `MutationAttempt` and sends its `Idempotency-Key`, as that file explains:

```ts
import { get, post, getBlob } from '@/lib/http';
import type { MutationAttempt } from '@/lib/idempotency';
import type {
  AccountView, ClosingQuoteView, RateDeclarationView, StatementRecordView, StatementView,
  TopUpView, TransferInView, WithdrawalView,
} from './types';

const p = (n: string) => `/policies/${encodeURIComponent(n)}/account`;

export const getAccount = (n: string) => get<AccountView>(p(n));
export const getClosingQuote = (n: string) => get<ClosingQuoteView>(`${p(n)}/quote`);
export const listWithdrawals = (n: string) => get<WithdrawalView[]>(`${p(n)}/withdrawals`);
export const listTopUps = (n: string) => get<TopUpView[]>(`${p(n)}/top-ups`);
export const listTransfersIn = (n: string) => get<TransferInView[]>(`${p(n)}/transfers-in`);
export const getStatement = (n: string, from: string, to: string) =>
  get<StatementView>(`${p(n)}/statement`, { params: { from, to } });
export const listStatements = (n: string) => get<StatementRecordView[]>(`${p(n)}/statements`);
export const statementPdf = (statementId: string) =>
  getBlob(`/account-statements/${encodeURIComponent(statementId)}/pdf`);

export const requestWithdrawal = (n: string, body: { amount: string; payeeRef: string }, attempt: MutationAttempt) =>
  post<WithdrawalView>(`${p(n)}/withdrawals`, body, attempt);
export const approveWithdrawal = (withdrawalId: string, attempt: MutationAttempt) =>
  post<WithdrawalView>(`/account-withdrawals/${encodeURIComponent(withdrawalId)}/approve`, undefined, attempt);
export const requestTopUp = (n: string, body: { amount: string; payerRef: string }, attempt: MutationAttempt) =>
  post<TopUpView>(`${p(n)}/top-ups`, body, attempt);
export const recordTransferIn = (
  n: string, body: { amount: string; sourceScheme: string; documentRef?: string }, attempt: MutationAttempt,
) => post<TransferInView>(`${p(n)}/transfers-in`, body, attempt);
export const generateStatement = (n: string, body: { from: string; to: string }, attempt: MutationAttempt) =>
  post<StatementRecordView>(`${p(n)}/statements`, body, attempt);

const r = (productId: string) => `/products/${encodeURIComponent(productId)}/rate-declarations`;
export const listRates = (productId: string) => get<RateDeclarationView[]>(r(productId));
export const proposeRate = (
  productId: string, body: { ratePercent: number; effectiveFrom: string }, attempt: MutationAttempt,
) => post<RateDeclarationView>(r(productId), body, attempt);
export const approveRate = (id: string, attempt: MutationAttempt) =>
  post<RateDeclarationView>(`/rate-declarations/${encodeURIComponent(id)}/approve`, undefined, attempt);
export const withdrawRate = (id: string, attempt: MutationAttempt) =>
  post<RateDeclarationView>(`/rate-declarations/${encodeURIComponent(id)}/withdraw`, undefined, attempt);
```

Match the real signatures of `get`, `post` and the attempt argument in `lib/http.ts`; the shapes above are a guide to the paths, not to that module. If `lib/http.ts` has no binary GET, see how a KYC document or claim evidence is downloaded (`ClaimEvidencePanel`, `PartyDocuments`), and use that path rather than adding one.

- [ ] **Step 2: The store**

`store/accumulationStore.ts`: copy `store/benefitPayoutStore.ts`'s shape. That means `createResourceSlice` per policy for the account, the withdrawals, the top-ups and the statements; a mutation helper that mints the attempt once per intent; and a reload of the account after every successful mutation. The account read is a 404 for every scale policy. Store that as a distinct `absent` result, not an error, so the policy page can decide not to show the tab (Step 4). Check how `createResourceSlice` represents a 404 elsewhere (`LoansPanel`'s cash value, for example) and use that representation.

- [ ] **Step 3: Gates, in the server's words**

```ts
// gates/accumulationGates.ts
import type { AccountView, WithdrawalView } from '@/api/types';
import type { Gate } from './types';

/**
 * What the server will refuse before a withdrawal is requested or approved, and nothing it cannot
 * prove from what is in hand. The wording is copied from AccumulationApiImpl's messages, so a gate
 * and the 422 say the same thing. The minimum-balance rule is NOT a gate: it needs the loan lien,
 * which this page does not hold, and a second opinion that disagreed with the server would be worse
 * than the server's own refusal shown in its own words.
 */
export function requestWithdrawalGates(account: AccountView, withdrawals: WithdrawalView[]): Gate[] {
  const open = account.status === 'OPEN';
  const inFlight = withdrawals.some((w) => w.status === 'REQUESTED' || w.status === 'APPROVED');
  return [
    {
      ok: open,
      hard: true,
      title: 'The account is open',
      detail: open ? 'Open.' : `The account is closed (${account.closedReason ?? 'closed'}).`,
    },
    {
      ok: !inFlight,
      hard: true,
      title: 'No other withdrawal in flight',
      detail: inFlight ? `A withdrawal is already in flight on policy ${account.policyNumber}` : 'None in flight.',
    },
  ];
}

export function approveWithdrawalGates(withdrawal: WithdrawalView, me: string): Gate[] {
  const requested = withdrawal.status === 'REQUESTED';
  const samePerson = withdrawal.requestedBy === me;
  return [
    {
      ok: requested,
      hard: true,
      title: 'Awaiting approval',
      detail: requested ? 'Requested, not yet approved.' : `This withdrawal is ${withdrawal.status.toLowerCase()}.`,
    },
    {
      ok: !samePerson,
      hard: true,
      title: 'A second person',
      detail: samePerson
        ? 'A withdrawal must be approved by someone other than the person who requested it'
        : 'Requested by someone else.',
    },
  ];
}
```

Write `rateApprovalGates(declaration, me)` the same way. Its two gates are "awaiting approval" (status PROPOSED) and "a second person" (`proposedBy !== me`, with the server's exact sentence). The "who am I" value is whatever `payoutGates`' approve gate compares `reviewedBy` against. Use the same source.

`gates/accumulationGates.test.ts`: one test per gate outcome, each asserting the exact `detail` sentence, in `payoutGates.test.ts`'s style.

- [ ] **Step 4: The Account tab**

In `PolicyDetailPage.tsx`, the account is loaded with the policy. The tab is pushed after `payouts` only when the account exists:

```tsx
      ...(account?.data
        ? [{
            value: 'account',
            label: 'Account',
            content: (
              <div className="pt-5 space-y-5">
                <Panel title="Account" subtitle="The savings account behind this policy, and every movement on it">
                  <AccountPanel policyNumber={policyNumber} />
                </Panel>
                <Panel title="Statement" subtitle="A period of the account, reconciled, and filed as a PDF on request">
                  <StatementSection policyNumber={policyNumber} />
                </Panel>
              </div>
            ),
          }]
        : []),
```

Fold that into the existing `tabs.push(...)` style rather than spreading it into the literal, if the surrounding code does not spread.

`AccountPanel`:
- the balance, the status and the opening date, as a definition list in the rail style the policy page already uses
- the ledger as a no-pager table, as `InvoicesPanel` lays one out. The columns are seq, effective date, type (badge), amount, balance after and reason. A REVERSAL row names the seq it reverses, worked out from `reversesEntryId` within the list.
- three actions under the table: Request withdrawal, Request top-up, and (finance only) Record transfer in. Each is a dialog or inline form of the kind `ValueActions.tsx` uses for surrender, with the gates rendered by the same gate list component.
- adjustments (Task 6 Step 7b): finance proposes one with a signed amount and a reason. Proposed ones are listed under the ledger with Approve and Reject, gated by a `decideAdjustmentGates` written like `approveWithdrawalGates`, with the server's sentence "An adjustment must be decided by someone other than the person who proposed it".
- the withdrawals list, where a REQUESTED row carries Approve for finance, gated by `approveWithdrawalGates`. Approval shows "The payment has been requested from the provider." on 202, exactly as a payout approval does. It never says "paid".

Money is always displayed with `formatMoney({amount, currencyCode})` from `lib/money` and entered as a string with the server's pattern. `withdrawalForm.ts` is a zod schema copied from `freeLookForm.ts`: the amount uses `/^\d+(\.\d{1,2})?$/` with no minus, and the payee reference is required.

`StatementSection`:
- From and To date inputs, defaulting to the last full calendar year
- the computed statement: opening, each group with its total and entries, closing
- "Generate PDF", which files it and adds it to the list below
- the list of filed statements, each with a Download link.

A regenerate that returns the same record shows it as already filed rather than as new. Compare the returned `statementId` with the list.

Labels for entry types live in the status maps, in a clerk's words, and must be the same words as the backend's `StatementLabels`.

- [ ] **Step 5: The live surrender figure**

In `ValueActions.tsx`, when the policy has an account, the surrender approval step shows the live `getClosingQuote` value, labelled "Valued today, with interest to date". The request's own quoted figure is shown beside it, labelled "Estimated when requested". Spec §5.4: the approver sees what will actually be paid. Do not compute the surrender charge in the console. The quote is before the charge, so say exactly that: "before any surrender charge".

- [ ] **Step 6: The product form**

`publishVersionSchema.ts` gains these fields: `valueBasis: 'SCALE' | 'ACCOUNT'` (default `'SCALE'`), `guaranteedRatePercent`, `minimumBalance`, and `accountCharges: { fromPolicyYear, toPolicyYear, contributionAllocationPercent, transferAllocationPercent, monthlyPolicyFee }[]`, all strings.

Add a `validateAccumulation(category, values, ctx)` beside `validateCashValue`. It is `AccumulationPlanValidator` rule for rule and message for message, as `validateCashValue` mirrors `CashValuePlanValidator`. Copy every message from Task 1's validator verbatim. Add one more rule from `checkAccountRules`: an ACCOUNT version's payout rows may only be a MATURITY row on the `ACCOUNT_VALUE` basis at 100, in the validator's words. Call it from the same `superRefine` at line ~747.

The request mapping gains, beside `cashValue`:

```ts
    ...(values.valueBasis === 'ACCOUNT' && {
      accumulation: {
        guaranteedRatePercent: Number(values.guaranteedRatePercent),
        minimumBalance: Number(values.minimumBalance),
        charges: values.accountCharges.map((c) => ({
          fromPolicyYear: Number(c.fromPolicyYear),
          ...(c.toPolicyYear !== '' && { toPolicyYear: Number(c.toPolicyYear) }),
          contributionAllocationPercent: Number(c.contributionAllocationPercent),
          transferAllocationPercent: Number(c.transferAllocationPercent),
          monthlyPolicyFee: Number(c.monthlyPolicyFee),
        })),
      },
    }),
```

The generated request type makes any property with a `default:` in the spec REQUIRED (see the frontend toolchain note). If Task 1's schema gave a field a default, the mapping must send it.

`PublishVersionForm.tsx` gains a "How the policy's value is defined" section, shown only for the three `CASH_VALUE_CATEGORIES`. It has a value-basis `Select` (from `@/components/ui/input`; a hand-rolled `<select>` fails lint), and when ACCOUNT is chosen, the guarantee, the minimum balance and a charge-row editor built with `useFieldArray` exactly as the payout rows are. Choosing ACCOUNT hides the cash-value table section, because the two cannot coexist. It adds `ACCOUNT_VALUE` ("The whole account value") to the payout row's basis options.

Tests: in `publishVersionSchema.test.ts`, one test per new message, plus one mapping test asserting the `accumulation` body. In `PublishVersionForm.test.tsx`, one test that choosing ACCOUNT reveals the account fields and hides the cash-value table.

- [ ] **Step 7: Declared rates**

`RateDeclarationsPanel` on `ProductDetailPage`, shown for the three categories. It lists every declaration, newest first: rate, effective from, a status badge, proposed by and approved by. Propose (ADMIN) is an inline form with the rate and the effective date. Approve (ADMIN or FINANCE_OFFICER) and Withdraw (ADMIN, PROPOSED only) are row actions, gated by `rateApprovalGates`. The server's refusal to reach back past credited interest is shown verbatim from the 422. It is not a gate: the console cannot see the latest month-end.

`rateDeclarationForm.ts`: the rate is 0–100 with at most four decimals (the column is `NUMERIC(7,4)`), and the effective date is required.

- [ ] **Step 8: Unit tests and the lint**

Run, in one call (see the batching rule):

```bash
cd frontend; echo "== typecheck"; npm run typecheck; echo "== lint"; npm run lint; echo "== unit"; npx vitest run
```

Expected: all green. Do not run vitest while a Playwright run is going (vacuous-verification trap).

- [ ] **Step 9: The dev seed and the e2e**

`seed-dev-data.sh`: add `SAVE-PLAN-01` "Nlolo Akiba Plan", category ENDOWMENT. Give it the ACCOUNT basis, a 3% guarantee, a 50,000 minimum balance, charges of 5%/2%/1,000 in year 1 and 1%/1%/1,000 from year 2, a MATURITY `ACCOUNT_VALUE` 100 payout row, and a 15-day free-look. Publish it the same way `END-MB-20` is published. Then publish it once by hand to the running dev database, as `END-MB-20` was, because the seeder's "already seeded" guard skips it on an existing database (the seeder-rot note).

`underwriting.ts`: `export const SAVINGS_PRODUCT = 'Nlolo Akiba Plan';`

`staff-savings-account.spec.ts`, finance identity, `test.setTimeout(240_000)` with the reason in a comment as `staff-payouts.spec.ts` gives it:

1. Issue on `SAVINGS_PRODUCT` by MIGRATION, commenced on the 1st of the month three months ago, with a 180-month term. Use the same 60s navigation budget as the payout fixtures.
2. The policy page shows an Account tab, and the account reads OPEN with a zero balance.
3. Request a top-up of 100,000.00. The mock rail confirms it, so poll (`toPass`, 45s) until the ledger shows a TOP_UP and an ALLOCATION_CHARGE. The month-end drain runs every ten seconds under `local`, so INTEREST and POLICY_FEE rows appear too. Assert they do, and that every row's balance after is non-negative.
4. Request a withdrawal of 10,000.00. The Approve action is DISABLED for the requester, with the server's sentence. A second person approves it as admin (`asAdmin`), and the page says the payment was requested.
5. Generate a statement for the current year. It is listed, and its Download link responds with `application/pdf` (`page.waitForEvent('download')`).

Add one more test: an underwriter sees the Account tab but no Record transfer in action.

Before running it: apply the step 3 migrations to the dev database by hand and restart the dev backend (the decoupled-migrations note). The migrations are product V19, accumulation V1, payment V9, document V6 and communication V10. Then run the spec alone with its setup project, never `--no-deps` (the stale-auth note).

- [ ] **Step 10: Commit**

```bash
git add frontend backend/scripts/seed-dev-data.sh
git commit -m "feat(console): the savings account -- ledger, withdrawals, top-ups, transfers, statements, declared rates"
```

---

### Task 10: The gate

Nothing new is built here. Each step is a check, and a failure sends the work back to the task that owns it.

- [ ] **Step 1: Registries.** Add `accumulation` to `MODULES` in `scripts/migrate.sh` and to the CI loop in `.github/workflows/ci-cd.yml`. `MigrationScriptCoverageTest` fails until both list it, which is its job. Add accumulation's tables to `AppRolePrivilegesIntegrationTest` and `RowLevelSecurityIntegrationTest`, wherever those enumerate each schema's tables. Read how they list benefitpayout's and do the same. Check `ops.platform_readiness()` and `configure-db.sh` for anything that enumerates schemas.

- [ ] **Step 2: Clean backend suite.** Stop the dev backend first, or `clean` deletes `target/` under a live JVM (the bogus-failure note). Then run `./mvnw clean test` in the background and do not edit sources while it runs (the IDE-race note). It takes about 80 minutes. Expected: zero failures and errors. Any `NoClassDefFoundError` means the run is void; re-run rather than diagnose.

- [ ] **Step 3: Frontend.** Run typecheck, lint and vitest in one call. Expected: all green.

- [ ] **Step 4: Dev database and full e2e.** Apply the five migrations to the dev database by hand, restart the backend, and publish `SAVE-PLAN-01` if it is absent. Then run the full Playwright suite, with nothing else running and the machine kept awake. A failure set that differs between two runs is timing, not code (the e2e-budget note); confirm it by running the test alone on both branches before calling it either.

- [ ] **Step 5: Whole-branch final review.** Read the entire diff against `main` once, looking for the seams between tasks rather than within them. In particular:
  - every `policy.*` event accumulation consumes is published with the keys it reads
  - every closing path refuses a closed account
  - the death value and the death ceiling agree to the cent
  - no AFTER_COMMIT listener can throw past its runner
  - every migration is in every list.
  
  Expect a real fix cycle (the final-review note).

- [ ] **Step 6: Merge.** `git checkout main && git merge --no-ff product-step3-accumulation`. The repo has no remote; a merge is local. Update the project memory with what was built and what was deferred.
