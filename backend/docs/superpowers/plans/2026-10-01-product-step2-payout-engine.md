# Product Step 2 — Payout Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Scheduled maturity, money-back, return-of-premium, guaranteed-income and overlapping-income payouts, plus free-look cancellation, so endowment / money-back / ROP-term / income products behave as the client's guide (§4, §6, §7, §14, §16, §21) describes.

**Architecture:** A new Spring Modulith module `benefitpayout` owns dated payout instalments expanded from a per-product-version schedule at issue, moves them DUE → REVIEWED → APPROVED → PAID through a two-person review, groups recurring income into daily payment runs, and talks to `policy`, `billing`, `claims`, `payment` and `finaccounting` only through published APIs and event envelopes. Product authoring gains the schedule; policy gains a free-look status; the staff console gains a Payouts tab, a payout page, three Finance registers and a schedule editor.

**Tech Stack:** Java 21, Spring Boot 3 + Spring Modulith, Spring Data JPA (`ddl-auto: none`), Postgres 16 with RLS, Testcontainers, MockMvc + atlassian `OpenApiValidationMatchers`; React + TypeScript, Zustand, Vitest + RTL, Playwright.

**Spec:** `backend/docs/superpowers/specs/2026-10-01-product-step2-payout-engine-design.md` (read §2 Decisions and §14 Revisions before starting).

## Global Constraints

- **Branch:** `product-step2-payouts`, cut from `main` **after `product-step1-cash-value` has merged**. Never stack on an unmerged branch.
- **Test cadence (user rule, 2026-10-01):** every task writes its tests and commits them with the code. While building, run ONLY that task's test classes (`./mvnw -o test -Dtest=ClassA,ClassB`). The full suite and real-stack e2e run once, in Task 11.
- **Never** run Maven in Docker; **never** run Prettier; stop the dev backend before any `clean`. Run `./mvnw -o clean test-compile` after any record/constructor signature change — incremental compile hides broken tests.
- **Money** is `BigDecimal`, scale 2, `RoundingMode.HALF_EVEN`, carried on the wire as `{ "amount": "<decimal string>", "currencyCode": "TZS" }`. No client-side arithmetic in the frontend.
- **Two-person rule:** the approver of a payout or a free-look cancellation must differ from its reviewer/requester, enforced in the domain entity AND by a table CHECK.
- **Access:** review, approve, payment-run approval and free-look approval require `hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))`. Free-look request requires `hasRole('REALM_STAFF')`. Product authoring stays `hasRole('REALM_STAFF') and hasRole('ADMIN')`.
- **Refusals** are HTTP 422 with the server's own message; the frontend shows that wording verbatim.
- **RLS predicate** on every new table: `USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid)`.
- **Status literals** come from the Java enums below and are mapped onto the six existing buckets only. No seventh bucket.
- **Event payload keys** are exactly as written in each task; consumers read them by these strings.

---

## File Structure

```
backend/db-migrations/
  product/V18__payout_schedule.sql                 schedule rows + per-version payout terms
  policy/V31__free_look_status.sql                 CANCELLED_FREE_LOOK in the status CHECK
  payment/V8__benefit_payout_purposes.sql          four new disbursement purposes
  benefitpayout/V1__create_benefitpayout_schema.sql  every benefitpayout table + selectors

backend/src/main/java/tz/co/nlolo/lifeplatform/
  product/api/       PayoutKind, PayoutAmountBasis, PayoutFrequency, PayoutRowInput, PayoutTerms, PayoutPlan
  product/domain/    PayoutScheduleRow, VersionPayoutTerms, PayoutPlanValidator
  product/infrastructure/ PayoutScheduleRowRepository, VersionPayoutTermsRepository, PayoutRowRequest, PayoutTermsRequest
  benefitpayout/
    package-info.java
    api/             BenefitPayoutApi, InstalmentStatus, StreamStatus, ProofOfLifeMethod, PayoutInstalmentView,
                     PaymentRunView, FreeLookCancellationView, FreeLookDeductionInput, PayoutStateException,
                     PayoutNotFoundException
    domain/          PayoutInstalment, PayoutStream, PremiumTally, PaymentRun, FreeLookCancellation,
                     FreeLookDeduction, ScheduleExpander, PayoutArithmetic
    application/     BenefitPayoutApiImpl, PolicyEventListener, PremiumEventListener, ClaimEventListener,
                     PayoutPaymentListener, PayoutDueDrain, PaymentRunDrain
    infrastructure/  repositories, BenefitPayoutController, BenefitPayoutExceptionHandler, request/response DTOs
backend/api/openapi/openapi-benefitpayout.yaml

frontend/src/
  api/benefitPayouts.ts, store/benefitPayoutStore.ts, gates/payoutGates.ts
  features/payouts/  PayoutsPanel, PayoutPage, PayoutsQueuePage, PaymentRunsPage, PaymentRunPage,
                     MaturitiesPage, FreeLookPanel, payoutReviewForm, freeLookForm
  features/products/ PayoutScheduleEditor (inside PublishVersionForm)
```

---

### Task 1: Product payout plan (V18, authoring, validation)

**Files:**
- Create: `backend/db-migrations/product/V18__payout_schedule.sql`
- Create: `product/api/PayoutKind.java`, `PayoutAmountBasis.java`, `PayoutFrequency.java`, `PayoutRowInput.java`, `PayoutTerms.java`, `PayoutPlan.java`
- Create: `product/domain/PayoutScheduleRow.java`, `VersionPayoutTerms.java`, `PayoutPlanValidator.java`
- Create: `product/infrastructure/PayoutScheduleRowRepository.java`, `VersionPayoutTermsRepository.java`, `PayoutRowRequest.java`, `PayoutTermsRequest.java`
- Modify: `product/api/ProductApi.java`, `product/application/ProductApiImpl.java` (fullest `publishVersion`, line ~177), `product/infrastructure/PublishVersionRequest.java`, `product/infrastructure/ProductController.java:87-117`, `backend/api/openapi/openapi-product.yaml`
- Modify: every backend test class that applies product migrations (add V18), the 8 test classes that POST `/products/{id}/versions`, `backend/scripts/seed-dev-data.sh`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/product/PayoutPlanValidatorTest.java` (unit), `product/ProductApiIntegrationTest.java` (two new tests)

**Interfaces:**
- Produces (product.api): `enum PayoutKind { SURVIVAL, MATURITY, INCOME, RETURN_OF_PREMIUM }`, `enum PayoutAmountBasis { PERCENT_OF_SA, FIXED, PERCENT_OF_PREMIUMS }`, `enum PayoutFrequency { ANNUAL(1), SEMI_ANNUAL(2), QUARTERLY(4), MONTHLY(12); int perYear() }`, `record PayoutRowInput(PayoutKind kind, Integer fromPolicyYear, Integer toPolicyYear, PayoutAmountBasis amountBasis, BigDecimal amountValue, PayoutFrequency frequency)`, `record PayoutTerms(Integer freeLookDays, Integer proofOfLifeIntervalMonths, Boolean survivalBenefitsDeductedFromDeath, BigDecimal deathBenefitPremiumPercent)`, `record PayoutPlan(PayoutTerms terms, List<PayoutRowInput> rows, boolean authored)` with `none()`, `authored(terms, rows)`, `hasEndOfTermRow()`, `rowsOf(PayoutKind)`.
- Produces (ProductApi): `PayoutPlan resolvePayoutPlan(UUID productVersionId)` — `PayoutPlan.none()` when the version has no plan; and a new `publishVersion(..., FrequencyLoading, TiraFiling, PayoutPlan, String publishedBy)` overload.

- [ ] **Step 1: Write the migration**

```sql
-- db-migrations/product/V18__payout_schedule.sql
-- Product step 2: what a version pays while the life assured is alive (guide §6, §7, §14, §16).
-- Two new tables and no column on product_version, so no existing product test is disturbed
-- (ddl-auto is none; a new table only matters to code that queries it) -- V17's reasoning.

-- One row per authored payout. MATURITY and RETURN_OF_PREMIUM pay once, on the policy's maturity
-- date, so they carry no years and no frequency. SURVIVAL and INCOME pay across a range of policy
-- years at a frequency; amount_value is the amount PER POLICY YEAR, split across that year's
-- instalments.
CREATE TABLE product.payout_schedule_row (
    payout_row_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL,
    product_version_id UUID NOT NULL REFERENCES product.product_version(product_version_id),
    row_order          INTEGER NOT NULL CHECK (row_order >= 0),
    kind               VARCHAR(20) NOT NULL
        CHECK (kind IN ('SURVIVAL','MATURITY','INCOME','RETURN_OF_PREMIUM')),
    from_policy_year   INTEGER,
    to_policy_year     INTEGER,
    amount_basis       VARCHAR(20) NOT NULL
        CHECK (amount_basis IN ('PERCENT_OF_SA','FIXED','PERCENT_OF_PREMIUMS')),
    amount_value       NUMERIC(19,4) NOT NULL CHECK (amount_value > 0),
    frequency          VARCHAR(12) CHECK (frequency IN ('ANNUAL','SEMI_ANNUAL','QUARTERLY','MONTHLY')),
    CONSTRAINT payout_row_shape CHECK (
        (kind IN ('MATURITY','RETURN_OF_PREMIUM')
            AND from_policy_year IS NULL AND to_policy_year IS NULL AND frequency IS NULL)
        OR (kind IN ('SURVIVAL','INCOME')
            AND from_policy_year >= 1 AND to_policy_year >= from_policy_year AND frequency IS NOT NULL)),
    -- Only a premium return is valued off premiums, and a premium return is valued off nothing else.
    CONSTRAINT payout_row_basis CHECK ((kind = 'RETURN_OF_PREMIUM') = (amount_basis = 'PERCENT_OF_PREMIUMS'))
);
CREATE UNIQUE INDEX ux_payout_row_order ON product.payout_schedule_row (product_version_id, row_order);

-- Per-version servicing terms. free_look_days is nullable here because group and credit life have
-- none; PayoutPlanValidator requires it on an authored individual version.
CREATE TABLE product.version_payout_terms (
    product_version_id                    UUID PRIMARY KEY REFERENCES product.product_version(product_version_id),
    tenant_id                             UUID NOT NULL,
    free_look_days                        INTEGER CHECK (free_look_days BETWEEN 1 AND 365),
    proof_of_life_interval_months         INTEGER CHECK (proof_of_life_interval_months BETWEEN 1 AND 60),
    survival_benefits_deducted_from_death BOOLEAN,
    death_benefit_premium_percent         NUMERIC(7,4)
        CHECK (death_benefit_premium_percent > 0 AND death_benefit_premium_percent <= 1000)
);

ALTER TABLE product.payout_schedule_row ENABLE ROW LEVEL SECURITY;
CREATE POLICY payout_schedule_row_tenant_isolation ON product.payout_schedule_row
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.version_payout_terms ENABLE ROW LEVEL SECURITY;
CREATE POLICY version_payout_terms_tenant_isolation ON product.version_payout_terms
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON product.payout_schedule_row TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON product.version_payout_terms TO app_role;
```

- [ ] **Step 2: Add V18 to every test class that applies product migrations**

V17 is now present in every such class (commit `866946a4`), so insert V18 directly after it:

```bash
cd backend
grep -rl 'db-migrations/product/V17__cash_value.sql' src/test/java | while read f; do
  grep -q 'product/V18__payout_schedule.sql' "$f" || \
  sed -i 's#\( *\)"db-migrations/product/V17__cash_value.sql",#&\n\1"db-migrations/product/V18__payout_schedule.sql",#' "$f"
done
grep -rL 'product/V18__payout_schedule.sql' $(grep -rl 'product/V17__cash_value.sql' src/test/java)
```

Expected: the last command prints nothing. If a file ends its list on V17 with `);` instead of `,`, add the V18 line by hand with the Edit tool (the same trap `DomainEventAuditListenerIntegrationTest` hit for audit/V1).

- [ ] **Step 3: Write the product.api types**

```java
// product/api/PayoutKind.java
package tz.co.nlolo.lifeplatform.product.api;

/** What a payout schedule row pays for (guide §6, §7, §14). */
public enum PayoutKind { SURVIVAL, MATURITY, INCOME, RETURN_OF_PREMIUM }
```

```java
// product/api/PayoutAmountBasis.java
package tz.co.nlolo.lifeplatform.product.api;

/** How a row's amount_value is read: a % of sum assured, a fixed sum, or a % of premiums collected. */
public enum PayoutAmountBasis { PERCENT_OF_SA, FIXED, PERCENT_OF_PREMIUMS }
```

```java
// product/api/PayoutFrequency.java
package tz.co.nlolo.lifeplatform.product.api;

/** How many instalments one policy year's amount is split into. */
public enum PayoutFrequency {
    ANNUAL(1), SEMI_ANNUAL(2), QUARTERLY(4), MONTHLY(12);

    private final int perYear;

    PayoutFrequency(int perYear) { this.perYear = perYear; }

    public int perYear() { return perYear; }

    /** Months between instalments within a year: 12, 6, 3 or 1. */
    public int monthsApart() { return 12 / perYear; }
}
```

```java
// product/api/PayoutRowInput.java
package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/**
 * One authored payout. MATURITY and RETURN_OF_PREMIUM leave the years and frequency null: they pay
 * once, on the policy's own maturity date. amountValue is per policy year for SURVIVAL and INCOME.
 */
public record PayoutRowInput(PayoutKind kind, Integer fromPolicyYear, Integer toPolicyYear,
                             PayoutAmountBasis amountBasis, BigDecimal amountValue, PayoutFrequency frequency) {}
```

```java
// product/api/PayoutTerms.java
package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/** A version's servicing terms for payouts and free-look. Every field nullable; see PayoutPlanValidator. */
public record PayoutTerms(Integer freeLookDays, Integer proofOfLifeIntervalMonths,
                          Boolean survivalBenefitsDeductedFromDeath, BigDecimal deathBenefitPremiumPercent) {

    public static PayoutTerms none() { return new PayoutTerms(null, null, null, null); }
}
```

```java
// product/api/PayoutPlan.java
package tz.co.nlolo.lifeplatform.product.api;

import java.util.List;

/**
 * A version's payout schedule and terms. {@code authored} is true only when the plan came through
 * the publish endpoint; the legacy publishVersion overloads (40-odd test fixtures) pass
 * {@link #none()}, which is exempt from the authoring requirements -- free-look days on an
 * individual product, a maturity row on an endowment.
 */
public record PayoutPlan(PayoutTerms terms, List<PayoutRowInput> rows, boolean authored) {

    public PayoutPlan {
        terms = terms != null ? terms : PayoutTerms.none();
        rows = rows != null ? List.copyOf(rows) : List.of();
    }

    public static PayoutPlan none() { return new PayoutPlan(PayoutTerms.none(), List.of(), false); }

    public static PayoutPlan authored(PayoutTerms terms, List<PayoutRowInput> rows) {
        return new PayoutPlan(terms, rows, true);
    }

    public List<PayoutRowInput> rowsOf(PayoutKind kind) {
        return rows.stream().filter(r -> r.kind() == kind).toList();
    }

    /** True when the version pays at the end of the term, so its policies mature rather than expire. */
    public boolean hasEndOfTermRow() {
        return rows.stream().anyMatch(r -> r.kind() == PayoutKind.MATURITY || r.kind() == PayoutKind.RETURN_OF_PREMIUM);
    }
}
```

- [ ] **Step 4: Write the failing validator test**

```java
// src/test/java/tz/co/nlolo/lifeplatform/product/PayoutPlanValidatorTest.java
package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.PayoutPlanValidator;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutPlanValidatorTest {

    private static final PayoutTerms FREE_LOOK_15 = new PayoutTerms(15, null, null, null);
    private static final PayoutRowInput MATURITY_100 =
        new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null);
    private static final PayoutRowInput SURVIVAL_Y5 =
        new PayoutRowInput(PayoutKind.SURVIVAL, 5, 5, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("10"), PayoutFrequency.ANNUAL);
    private static final PayoutRowInput INCOME_6_15 =
        new PayoutRowInput(PayoutKind.INCOME, 6, 15, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("3"), PayoutFrequency.MONTHLY);
    private static final PayoutRowInput ROP_100 =
        new PayoutRowInput(PayoutKind.RETURN_OF_PREMIUM, null, null, PayoutAmountBasis.PERCENT_OF_PREMIUMS, new BigDecimal("100"), null);

    @Test
    void legacyPlanIsAlwaysAccepted() {
        assertThatCode(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT, PayoutPlan.none()))
            .doesNotThrowAnyException();
    }

    @Test
    void authoredIndividualProductNeedsFreeLookDays() {
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.TERM_LIFE,
                PayoutPlan.authored(PayoutTerms.none(), List.of())))
            .isInstanceOf(InvalidProductVersionException.class)
            .hasMessage("A free-look period in days is required on an individual product");
    }

    @Test
    void groupAndCreditLifeNeedNoFreeLookAndTakeNoRows() {
        assertThatCode(() -> PayoutPlanValidator.validate(ProductCategory.GROUP_LIFE,
            PayoutPlan.authored(PayoutTerms.none(), List.of()))).doesNotThrowAnyException();
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.CREDIT_LIFE,
                PayoutPlan.authored(PayoutTerms.none(), List.of(MATURITY_100))))
            .hasMessage("A CREDIT_LIFE product cannot carry a payout schedule");
    }

    @Test
    void endowmentNeedsExactlyOneMaturityRow() {
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(FREE_LOOK_15, List.of())))
            .hasMessage("An ENDOWMENT product must carry exactly one MATURITY row");
        assertThatCode(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
            PayoutPlan.authored(FREE_LOOK_15, List.of(MATURITY_100)))).doesNotThrowAnyException();
    }

    @Test
    void survivalRowsNeedTheDeductionSetting() {
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(FREE_LOOK_15, List.of(MATURITY_100, SURVIVAL_Y5))))
            .hasMessage("A product with SURVIVAL rows must say whether survival benefits paid are deducted from the death benefit");
        PayoutTerms withDeduction = new PayoutTerms(15, null, true, null);
        assertThatCode(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
            PayoutPlan.authored(withDeduction, List.of(MATURITY_100, SURVIVAL_Y5)))).doesNotThrowAnyException();
    }

    @Test
    void incomeRowsNeedAProofOfLifeInterval() {
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.EDUCATION_SAVINGS,
                PayoutPlan.authored(FREE_LOOK_15, List.of(MATURITY_100, INCOME_6_15))))
            .hasMessage("A product with INCOME rows needs a proof-of-life interval in months");
    }

    @Test
    void termLifeTakesOnlyOneReturnOfPremiumRow() {
        assertThatCode(() -> PayoutPlanValidator.validate(ProductCategory.TERM_LIFE,
            PayoutPlan.authored(FREE_LOOK_15, List.of(ROP_100)))).doesNotThrowAnyException();
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.TERM_LIFE,
                PayoutPlan.authored(FREE_LOOK_15, List.of(MATURITY_100))))
            .hasMessage("A TERM_LIFE product may carry only a single RETURN_OF_PREMIUM row");
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(FREE_LOOK_15, List.of(MATURITY_100, ROP_100))))
            .hasMessage("RETURN_OF_PREMIUM rows are only valid on TERM_LIFE products");
    }

    @Test
    void wholeLifeAnnuityAndUnitLinkedTakeNoRows() {
        for (ProductCategory c : List.of(ProductCategory.WHOLE_LIFE, ProductCategory.ANNUITY, ProductCategory.UNIT_LINKED)) {
            assertThatThrownBy(() -> PayoutPlanValidator.validate(c, PayoutPlan.authored(FREE_LOOK_15, List.of(MATURITY_100))))
                .hasMessage("A " + c + " product cannot carry a payout schedule");
        }
    }

    @Test
    void rowShapeIsChecked() {
        PayoutRowInput survivalNoYears = new PayoutRowInput(PayoutKind.SURVIVAL, null, null,
            PayoutAmountBasis.PERCENT_OF_SA, BigDecimal.TEN, PayoutFrequency.ANNUAL);
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(new PayoutTerms(15, null, false, null), List.of(MATURITY_100, survivalNoYears))))
            .hasMessage("A SURVIVAL row needs a from and to policy year (from >= 1, to >= from) and a frequency");
        PayoutRowInput maturityWithYears = new PayoutRowInput(PayoutKind.MATURITY, 20, 20,
            PayoutAmountBasis.PERCENT_OF_SA, BigDecimal.TEN, null);
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(FREE_LOOK_15, List.of(maturityWithYears))))
            .hasMessage("A MATURITY row pays on the policy's maturity date and takes no years or frequency");
        PayoutRowInput maturityOffPremiums = new PayoutRowInput(PayoutKind.MATURITY, null, null,
            PayoutAmountBasis.PERCENT_OF_PREMIUMS, BigDecimal.TEN, null);
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(FREE_LOOK_15, List.of(maturityOffPremiums))))
            .hasMessage("Only a RETURN_OF_PREMIUM row is valued as a percent of premiums");
        PayoutRowInput zero = new PayoutRowInput(PayoutKind.MATURITY, null, null,
            PayoutAmountBasis.PERCENT_OF_SA, BigDecimal.ZERO, null);
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(FREE_LOOK_15, List.of(zero))))
            .hasMessage("A payout row's amount must be greater than zero");
    }
}
```

- [ ] **Step 5: Run it to verify it fails**

Run: `cd backend && ./mvnw -o test -Dtest=PayoutPlanValidatorTest`
Expected: COMPILATION FAILURE — `PayoutPlanValidator` does not exist.

- [ ] **Step 6: Write the validator**

```java
// product/domain/PayoutPlanValidator.java
package tz.co.nlolo.lifeplatform.product.domain;

import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

/** The per-category rules for a payout schedule (spec §3.2). Pure, so it is unit-tested without Spring. */
public final class PayoutPlanValidator {

    private static final Set<ProductCategory> INDIVIDUAL = EnumSet.of(
        ProductCategory.TERM_LIFE, ProductCategory.ENDOWMENT, ProductCategory.WHOLE_LIFE, ProductCategory.EDUCATION_SAVINGS);
    private static final Set<ProductCategory> SCHEDULED = EnumSet.of(
        ProductCategory.ENDOWMENT, ProductCategory.EDUCATION_SAVINGS);

    private PayoutPlanValidator() {}

    public static void validate(ProductCategory category, PayoutPlan plan) {
        if (!plan.authored()) {
            return;
        }
        PayoutTerms terms = plan.terms();
        if (INDIVIDUAL.contains(category) && terms.freeLookDays() == null) {
            fail("A free-look period in days is required on an individual product");
        }
        if (terms.freeLookDays() != null && (terms.freeLookDays() < 1 || terms.freeLookDays() > 365)) {
            fail("A free-look period must be between 1 and 365 days");
        }
        if (plan.rows().isEmpty() && !SCHEDULED.contains(category)) {
            return;
        }
        if (category == ProductCategory.TERM_LIFE) {
            if (plan.rows().size() != 1 || plan.rows().get(0).kind() != PayoutKind.RETURN_OF_PREMIUM) {
                fail("A TERM_LIFE product may carry only a single RETURN_OF_PREMIUM row");
            }
        } else if (!SCHEDULED.contains(category)) {
            fail("A " + category + " product cannot carry a payout schedule");
        } else {
            if (plan.rowsOf(PayoutKind.MATURITY).size() != 1) {
                fail("An " + category + " product must carry exactly one MATURITY row");
            }
            if (!plan.rowsOf(PayoutKind.RETURN_OF_PREMIUM).isEmpty()) {
                fail("RETURN_OF_PREMIUM rows are only valid on TERM_LIFE products");
            }
        }
        for (PayoutRowInput row : plan.rows()) {
            checkShape(row);
        }
        if (!plan.rowsOf(PayoutKind.SURVIVAL).isEmpty() && terms.survivalBenefitsDeductedFromDeath() == null) {
            fail("A product with SURVIVAL rows must say whether survival benefits paid are deducted from the death benefit");
        }
        if (!plan.rowsOf(PayoutKind.INCOME).isEmpty()) {
            Integer interval = terms.proofOfLifeIntervalMonths();
            if (interval == null) {
                fail("A product with INCOME rows needs a proof-of-life interval in months");
            } else if (interval < 1 || interval > 60) {
                fail("A proof-of-life interval must be between 1 and 60 months");
            }
        }
        BigDecimal pct = terms.deathBenefitPremiumPercent();
        if (pct != null && (pct.signum() <= 0 || pct.compareTo(new BigDecimal("1000")) > 0)) {
            fail("A death-benefit premium percent must be greater than 0 and at most 1000");
        }
    }

    private static void checkShape(PayoutRowInput row) {
        if (row.amountValue() == null || row.amountValue().signum() <= 0) {
            fail("A payout row's amount must be greater than zero");
        }
        boolean endOfTerm = row.kind() == PayoutKind.MATURITY || row.kind() == PayoutKind.RETURN_OF_PREMIUM;
        if (endOfTerm) {
            if (row.fromPolicyYear() != null || row.toPolicyYear() != null || row.frequency() != null) {
                fail("A " + row.kind() + " row pays on the policy's maturity date and takes no years or frequency");
            }
        } else if (row.fromPolicyYear() == null || row.toPolicyYear() == null || row.frequency() == null
                || row.fromPolicyYear() < 1 || row.toPolicyYear() < row.fromPolicyYear()) {
            fail("A " + row.kind() + " row needs a from and to policy year (from >= 1, to >= from) and a frequency");
        }
        boolean offPremiums = row.amountBasis() == PayoutAmountBasis.PERCENT_OF_PREMIUMS;
        if (offPremiums != (row.kind() == PayoutKind.RETURN_OF_PREMIUM)) {
            fail(offPremiums ? "Only a RETURN_OF_PREMIUM row is valued as a percent of premiums"
                             : "A RETURN_OF_PREMIUM row is valued as a percent of premiums");
        }
    }

    private static void fail(String message) {
        throw new InvalidProductVersionException(message);
    }
}
```

- [ ] **Step 7: Run the validator test**

Run: `./mvnw -o test -Dtest=PayoutPlanValidatorTest`
Expected: PASS, 9 tests.

- [ ] **Step 8: Write the entities and repositories**

```java
// product/domain/PayoutScheduleRow.java
package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "payout_schedule_row", schema = "product")
public class PayoutScheduleRow {

    @Id @UuidGenerator
    @Column(name = "payout_row_id")
    private UUID payoutRowId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "row_order", nullable = false) private int rowOrder;
    @Column(nullable = false) private String kind;
    @Column(name = "from_policy_year") private Integer fromPolicyYear;
    @Column(name = "to_policy_year") private Integer toPolicyYear;
    @Column(name = "amount_basis", nullable = false) private String amountBasis;
    @Column(name = "amount_value", nullable = false) private BigDecimal amountValue;
    @Column private String frequency;

    protected PayoutScheduleRow() {}

    public PayoutScheduleRow(UUID tenantId, UUID productVersionId, int rowOrder, PayoutRowInput in) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.rowOrder = rowOrder;
        this.kind = in.kind().name();
        this.fromPolicyYear = in.fromPolicyYear();
        this.toPolicyYear = in.toPolicyYear();
        this.amountBasis = in.amountBasis().name();
        this.amountValue = in.amountValue();
        this.frequency = in.frequency() != null ? in.frequency().name() : null;
    }

    public int getRowOrder() { return rowOrder; }

    public PayoutRowInput toInput() {
        return new PayoutRowInput(PayoutKind.valueOf(kind), fromPolicyYear, toPolicyYear,
            PayoutAmountBasis.valueOf(amountBasis), amountValue,
            frequency != null ? PayoutFrequency.valueOf(frequency) : null);
    }
}
```

```java
// product/domain/VersionPayoutTerms.java
package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import tz.co.nlolo.lifeplatform.product.api.PayoutTerms;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "version_payout_terms", schema = "product")
public class VersionPayoutTerms {

    @Id
    @Column(name = "product_version_id")
    private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "free_look_days") private Integer freeLookDays;
    @Column(name = "proof_of_life_interval_months") private Integer proofOfLifeIntervalMonths;
    @Column(name = "survival_benefits_deducted_from_death") private Boolean survivalBenefitsDeductedFromDeath;
    @Column(name = "death_benefit_premium_percent") private BigDecimal deathBenefitPremiumPercent;

    protected VersionPayoutTerms() {}

    public VersionPayoutTerms(UUID tenantId, UUID productVersionId, PayoutTerms t) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.freeLookDays = t.freeLookDays();
        this.proofOfLifeIntervalMonths = t.proofOfLifeIntervalMonths();
        this.survivalBenefitsDeductedFromDeath = t.survivalBenefitsDeductedFromDeath();
        this.deathBenefitPremiumPercent = t.deathBenefitPremiumPercent();
    }

    public PayoutTerms toTerms() {
        return new PayoutTerms(freeLookDays, proofOfLifeIntervalMonths, survivalBenefitsDeductedFromDeath,
            deathBenefitPremiumPercent);
    }
}
```

```java
// product/infrastructure/PayoutScheduleRowRepository.java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.PayoutScheduleRow;

import java.util.List;
import java.util.UUID;

public interface PayoutScheduleRowRepository extends JpaRepository<PayoutScheduleRow, UUID> {
    List<PayoutScheduleRow> findByProductVersionIdOrderByRowOrder(UUID productVersionId);
}
```

```java
// product/infrastructure/VersionPayoutTermsRepository.java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.VersionPayoutTerms;

import java.util.UUID;

public interface VersionPayoutTermsRepository extends JpaRepository<VersionPayoutTerms, UUID> {}
```

- [ ] **Step 9: Extend ProductApi and ProductApiImpl**

Add to `ProductApi.java`, after the fullest `publishVersion` (the one ending `FrequencyLoading frequencyLoading, TiraFiling tiraFiling, String publishedBy);`):

```java
    /**
     * The fullest form: also what the version pays while the life assured is alive (step 2).
     * Every other overload delegates here with {@link PayoutPlan#none()}.
     */
    void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                         List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                         List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                         TiraFiling tiraFiling, PayoutPlan payoutPlan, String publishedBy);

    /**
     * What this version pays while the life assured is alive, and its free-look and proof-of-life
     * terms. {@link PayoutPlan#none()} for a version with neither. Internal-only, like
     * {@link #resolveBenefitSchedule}.
     */
    PayoutPlan resolvePayoutPlan(UUID productVersionId);
```

In `ProductApiImpl.java`:

1. Inject the two repositories (constructor parameters `PayoutScheduleRowRepository payoutScheduleRowRepository, VersionPayoutTermsRepository versionPayoutTermsRepository`, assigned to same-named final fields).
2. Change the signature of the existing fullest method at line ~177 to the new 13-argument form (insert `PayoutPlan payoutPlan,` before `String publishedBy`), keeping `@Override @Transactional`.
3. Add the old 12-argument signature back as a delegating method:

```java
    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                                List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                                TiraFiling tiraFiling, String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, baseRates, bounds, frequencyLoading, tiraFiling, PayoutPlan.none(), publishedBy);
    }
```

4. In the moved body, directly after the `for (BenefitInput input : benefitSchedule) {...}` loop, add:

```java
        PayoutPlan plan = payoutPlan != null ? payoutPlan : PayoutPlan.none();
        PayoutPlanValidator.validate(ProductCategory.valueOf(product.getCategory()), plan);
```

5. Directly after `productVersionRepository.save(version);`, add:

```java
        persistPayoutPlan(tenantId, version.getProductVersionId(), plan);
```

6. Add these methods to the class:

```java
    private void persistPayoutPlan(UUID tenantId, UUID productVersionId, PayoutPlan plan) {
        if (!plan.authored()) {
            return;
        }
        versionPayoutTermsRepository.save(new VersionPayoutTerms(tenantId, productVersionId, plan.terms()));
        for (int i = 0; i < plan.rows().size(); i++) {
            payoutScheduleRowRepository.save(new PayoutScheduleRow(tenantId, productVersionId, i, plan.rows().get(i)));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PayoutPlan resolvePayoutPlan(UUID productVersionId) {
        return versionPayoutTermsRepository.findById(productVersionId)
            .map(terms -> PayoutPlan.authored(terms.toTerms(),
                payoutScheduleRowRepository.findByProductVersionIdOrderByRowOrder(productVersionId).stream()
                    .map(PayoutScheduleRow::toInput).toList()))
            .orElse(PayoutPlan.none());
    }
```

RLS scopes both reads to the caller's tenant, so no explicit tenant filter is needed.

- [ ] **Step 10: Wire the HTTP request**

```java
// product/infrastructure/PayoutRowRequest.java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.constraints.NotNull;
import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;

public record PayoutRowRequest(@NotNull PayoutKind kind, Integer fromPolicyYear, Integer toPolicyYear,
                               @NotNull PayoutAmountBasis amountBasis, @NotNull BigDecimal amountValue,
                               PayoutFrequency frequency) {
    public PayoutRowInput toInput() {
        return new PayoutRowInput(kind, fromPolicyYear, toPolicyYear, amountBasis, amountValue, frequency);
    }
}
```

```java
// product/infrastructure/PayoutTermsRequest.java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.PayoutTerms;

import java.math.BigDecimal;

public record PayoutTermsRequest(Integer freeLookDays, Integer proofOfLifeIntervalMonths,
                                 Boolean survivalBenefitsDeductedFromDeath, BigDecimal deathBenefitPremiumPercent) {
    public PayoutTerms toTerms() {
        return new PayoutTerms(freeLookDays, proofOfLifeIntervalMonths, survivalBenefitsDeductedFromDeath,
            deathBenefitPremiumPercent);
    }
}
```

Append two components to `PublishVersionRequest` after `tiraFiling`:

```java
    @NotNull @Valid TiraFilingRequest tiraFiling,

    PayoutTermsRequest payoutTerms,

    @Valid List<PayoutRowRequest> payoutSchedule) {}
```

In `ProductController.publishVersion`, replace the final two arguments (`new TiraFiling(...), jwt.getSubject());`) with:

```java
            new TiraFiling(request.tiraFiling().reference(), request.tiraFiling().approvalDate()),
            // Always AUTHORED from the API, so an individual version without free-look days is refused
            // here even when the caller omits the whole block.
            PayoutPlan.authored(
                request.payoutTerms() != null ? request.payoutTerms().toTerms() : PayoutTerms.none(),
                request.payoutSchedule() != null
                    ? request.payoutSchedule().stream().map(PayoutRowRequest::toInput).toList()
                    : List.of()),
            jwt.getSubject());
```

Then find direct constructions of the widened record and add `null, null` for the two new components:

```bash
grep -rn "new PublishVersionRequest(" src/test/java src/main/java
```

- [ ] **Step 11: Extend openapi-product.yaml**

Under `components.schemas.ProductVersionSpec.properties` add:

```yaml
        payoutTerms:
          $ref: '#/components/schemas/PayoutTerms'
        payoutSchedule:
          type: array
          items:
            $ref: '#/components/schemas/PayoutScheduleRow'
```

Add under `components.schemas`:

```yaml
    PayoutTerms:
      type: object
      properties:
        freeLookDays: { type: [integer, "null"], minimum: 1, maximum: 365 }
        proofOfLifeIntervalMonths: { type: [integer, "null"], minimum: 1, maximum: 60 }
        survivalBenefitsDeductedFromDeath: { type: [boolean, "null"] }
        deathBenefitPremiumPercent: { type: [number, "null"] }
    PayoutScheduleRow:
      type: object
      required: [kind, amountBasis, amountValue]
      properties:
        kind: { type: string, enum: [SURVIVAL, MATURITY, INCOME, RETURN_OF_PREMIUM] }
        fromPolicyYear: { type: [integer, "null"], minimum: 1 }
        toPolicyYear: { type: [integer, "null"], minimum: 1 }
        amountBasis: { type: string, enum: [PERCENT_OF_SA, FIXED, PERCENT_OF_PREMIUMS] }
        amountValue: { type: number }
        frequency:
          type: [string, "null"]
          enum: [ANNUAL, SEMI_ANNUAL, QUARTERLY, MONTHLY, null]
```

OpenAPI 3.1 nullability (`type: [x, "null"]`), matching the rest of the file. No `default:` keys — openapi-typescript would make them required in generated request types.

- [ ] **Step 12: Mirror the free-look requirement into the HTTP fixtures and the seeder**

```bash
grep -rlE '/products/[^"]*/versions' src/test/java
grep -n 'versions' scripts/seed-dev-data.sh
```

In each test class listed whose product is `TERM_LIFE`, `ENDOWMENT`, `WHOLE_LIFE` or `EDUCATION_SAVINGS`, add `"payoutTerms":{"freeLookDays":15}` to the version JSON body, beside `"tiraFiling"`. In `seed-dev-data.sh` add the same key to the TERM_LIFE version body at line ~104; the credit-life body at line ~137 is exempt. (Memory: an endpoint tightened without its seeder breaks the fresh-bootstrap path silently.)

- [ ] **Step 13: Write the two integration tests**

Add to `ProductApiIntegrationTest` (it already applies V17; Step 2 added V18):

```java
    @Test
    void anAuthoredEndowmentRoundTripsItsPayoutPlan() {
        TenantContext.set(TENANT);
        try {
            UUID productId = createProduct("END-PAY-1", ProductCategory.ENDOWMENT);
            PayoutPlan plan = PayoutPlan.authored(new PayoutTerms(15, 12, true, new BigDecimal("105")), List.of(
                new PayoutRowInput(PayoutKind.SURVIVAL, 5, 5, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("10"), PayoutFrequency.ANNUAL),
                new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null)));
            publishWithPlan(productId, plan);

            UUID versionId = productApi.getActiveSnapshot(productId, LocalDate.now()).productVersionId();
            PayoutPlan read = productApi.resolvePayoutPlan(versionId);
            assertThat(read.authored()).isTrue();
            assertThat(read.terms().freeLookDays()).isEqualTo(15);
            assertThat(read.rows()).extracting(PayoutRowInput::kind).containsExactly(PayoutKind.SURVIVAL, PayoutKind.MATURITY);
            assertThat(read.hasEndOfTermRow()).isTrue();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aVersionPublishedWithoutAPlanResolvesToNone() {
        TenantContext.set(TENANT);
        try {
            UUID productId = createProduct("TERM-NOPLAN", ProductCategory.TERM_LIFE);
            publishLegacy(productId);
            UUID versionId = productApi.getActiveSnapshot(productId, LocalDate.now()).productVersionId();
            assertThat(productApi.resolvePayoutPlan(versionId)).isEqualTo(PayoutPlan.none());
        } finally {
            TenantContext.clear();
        }
    }
```

Add the helpers (same rating/benefit shape as `PolicyApiIntegrationTest.buildFixture`; if the class has no `TENANT` constant, add `private static final UUID TENANT = UUID.randomUUID();`):

```java
    private UUID createProduct(String code, ProductCategory category) {
        return productApi.createProduct(code, "Payout plan test", category, "TZS", "actuary").productId();
    }

    private static List<ProductApi.RatingFactorInput> rating() {
        return List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                       new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE));
    }

    private static List<ProductApi.BenefitInput> deathOnly() {
        return List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED));
    }

    private void publishLegacy(UUID productId) {
        productApi.publishVersion(productId, IfrsMeasurementModel.PAA, LocalDate.now(), null, rating(), deathOnly(),
            null, ANY_FILING, "actuary");
    }

    private void publishWithPlan(UUID productId, PayoutPlan plan) {
        productApi.publishVersion(productId, IfrsMeasurementModel.PAA, LocalDate.now(), null, rating(), deathOnly(),
            null, List.of(), EligibilityBounds.none(), FrequencyLoading.none(), ANY_FILING, plan, "actuary");
    }
```

- [ ] **Step 14: Compile everything and run this task's classes**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest=PayoutPlanValidatorTest,ProductApiIntegrationTest,ProductContractTest
```

Expected: BUILD SUCCESS; all green. A `ProductContractTest` failure naming `freeLookDays` means a fixture from Step 12 was missed.

- [ ] **Step 15: Commit**

```bash
git add db-migrations/product/V18__payout_schedule.sql src/main/java/tz/co/nlolo/lifeplatform/product api/openapi/openapi-product.yaml src/test/java scripts/seed-dev-data.sh
git commit -m "feat(product): payout schedule and free-look terms on a product version (step 2, task 1)"
```

---

### Task 2: The `benefitpayout` module, its schema, and expansion at issue

**Files:**
- Create: `backend/db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql`
- Create: `benefitpayout/package-info.java`, `benefitpayout/api/package-info.java`, `api/BenefitPayoutApi.java`, `api/InstalmentStatus.java`, `api/StreamStatus.java`, `api/ProofOfLifeMethod.java`, `api/PayoutInstalmentView.java`, `api/PayoutStateException.java`, `api/PayoutNotFoundException.java`
- Create: `benefitpayout/domain/PayoutInstalment.java`, `PayoutStream.java`, `PremiumTally.java`, `ScheduleExpander.java`, `PayoutArithmetic.java`
- Create: `benefitpayout/infrastructure/PayoutInstalmentRepository.java`, `PayoutStreamRepository.java`, `PremiumTallyRepository.java`
- Create: `benefitpayout/application/BenefitPayoutApiImpl.java`, `PolicyEventListener.java`, `PremiumEventListener.java`
- Modify: every test class that now applies `product/V18__payout_schedule.sql` (add benefitpayout/V1 after it)
- Test: `benefitpayout/ScheduleExpanderTest.java`, `benefitpayout/PayoutArithmeticTest.java` (unit), `benefitpayout/BenefitPayoutApiIntegrationTest.java`

**Interfaces:**
- Consumes: `ProductApi.resolvePayoutPlan(UUID)`, `PolicyApi.getPolicy(String) -> PolicyView`.
- Produces: `ScheduleExpander.expand(PayoutPlan plan, LocalDate start, LocalDate maturityDate, BigDecimal sumAssured) -> List<ScheduleExpander.Planned>` where `record Planned(PayoutKind kind, int rowOrder, LocalDate dueDate, BigDecimal amount)` (amount null for RETURN_OF_PREMIUM); `PayoutArithmetic.splitYear(BigDecimal yearAmount, int parts) -> List<BigDecimal>`, `restate(BigDecimal amount, BigDecimal paidUpSa, BigDecimal originalSa)`, `percentOf(BigDecimal base, BigDecimal pct)`; `BenefitPayoutApi.listForPolicy(String) -> List<PayoutInstalmentView>`; `BenefitPayoutApi.hasScheduledMaturity(String) -> boolean`; event-facing methods on the impl used by listeners: `expandForIssuedPolicy(String policyNumber, UUID productVersionId, LocalDate issueDate, LocalDate premiumPayingUntil, String premiumFrequency)`, `recordPremium(String policyNumber, BigDecimal amount, LocalDate paidToDate)`.

- [ ] **Step 1: Write the migration**

```sql
-- db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql
-- Product step 2: the payout engine (guide §21.4, "the tap"). One schema, no FK into another
-- module's tables -- policy numbers are carried as values, the platform's cross-module rule.
CREATE SCHEMA IF NOT EXISTS benefitpayout;
GRANT USAGE ON SCHEMA benefitpayout TO app_role;

-- Groups the instalments of one INCOME row (Q7). PENDING_ACTIVATION until its first instalment is
-- approved; SUSPENDED when proof of life is overdue.
CREATE TABLE benefitpayout.payout_stream (
    stream_id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 UUID NOT NULL,
    policy_number             VARCHAR(20) NOT NULL,
    row_order                 INTEGER NOT NULL,
    status                    VARCHAR(20) NOT NULL DEFAULT 'PENDING_ACTIVATION'
        CHECK (status IN ('PENDING_ACTIVATION','ACTIVE','SUSPENDED','ENDED')),
    proof_of_life_interval_months INTEGER NOT NULL CHECK (proof_of_life_interval_months BETWEEN 1 AND 60),
    proof_of_life_due_date    DATE,
    version                   BIGINT NOT NULL DEFAULT 0,
    UNIQUE (policy_number, row_order)
);

-- Who reviewed and who approved live on the instalment: one review per instalment, and the
-- two-person rule is then one CHECK on one row.
CREATE TABLE benefitpayout.payout_instalment (
    instalment_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL,
    policy_number        VARCHAR(20) NOT NULL,
    kind                 VARCHAR(20) NOT NULL CHECK (kind IN ('SURVIVAL','MATURITY','INCOME','RETURN_OF_PREMIUM')),
    row_order            INTEGER NOT NULL,
    stream_id            UUID REFERENCES benefitpayout.payout_stream(stream_id),
    due_date             DATE NOT NULL,
    -- Null only on a RETURN_OF_PREMIUM instalment until it falls due: it is valued off premiums
    -- actually collected, which are not known at issue.
    original_amount      NUMERIC(19,2) CHECK (original_amount IS NULL OR original_amount > 0),
    current_amount       NUMERIC(19,2) CHECK (current_amount IS NULL OR current_amount >= 0),
    currency             CHAR(3) NOT NULL DEFAULT 'TZS',
    restatement_reason   VARCHAR(200),
    status               VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED'
        CHECK (status IN ('SCHEDULED','DUE','ON_HOLD','REVIEWED','APPROVED','PAID','FAILED','IN_DOUBT','CANCELLED')),
    status_reason        VARCHAR(200),
    payee_ref            VARCHAR(200),
    proof_of_life_method VARCHAR(20) CHECK (proof_of_life_method IN ('IN_PERSON','PHONE_OR_VIDEO','LIFE_CERTIFICATE')),
    proof_of_life_document_id UUID,
    reviewed_by          VARCHAR(100),
    reviewed_at          TIMESTAMPTZ,
    approved_by          VARCHAR(100),
    approved_at          TIMESTAMPTZ,
    payment_run_id       UUID,
    disbursement_id      UUID,
    attempts             INTEGER NOT NULL DEFAULT 0,
    version              BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT payout_two_person CHECK (approved_by IS NULL OR approved_by <> reviewed_by),
    UNIQUE (policy_number, row_order, due_date)
);
CREATE INDEX idx_payout_instalment_policy ON benefitpayout.payout_instalment (policy_number, due_date);
CREATE INDEX idx_payout_instalment_due ON benefitpayout.payout_instalment (status, due_date);

-- What billing has reported, kept here because benefitpayout may not ask billing (spec §4).
CREATE TABLE benefitpayout.premium_tally (
    policy_number          VARCHAR(20) PRIMARY KEY,
    tenant_id              UUID NOT NULL,
    premium_frequency      VARCHAR(12) NOT NULL,
    issue_date             DATE NOT NULL,
    premium_paying_until   DATE,
    premiums_collected     NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (premiums_collected >= 0),
    currency               CHAR(3) NOT NULL DEFAULT 'TZS',
    paid_to_date           DATE,
    version                BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE benefitpayout.payment_run (
    payment_run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL,
    run_date       DATE NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'PREPARED' CHECK (status IN ('PREPARED','APPROVED')),
    approved_by    VARCHAR(100),
    approved_at    TIMESTAMPTZ,
    version        BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, run_date)
);

CREATE TABLE benefitpayout.free_look_cancellation (
    cancellation_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL,
    policy_number    VARCHAR(20) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED','APPROVED','PAID','FAILED','IN_DOUBT')),
    premiums_collected NUMERIC(19,2) NOT NULL CHECK (premiums_collected >= 0),
    refund_amount    NUMERIC(19,2) NOT NULL CHECK (refund_amount >= 0),
    currency         CHAR(3) NOT NULL DEFAULT 'TZS',
    payee_ref        VARCHAR(200) NOT NULL,
    requested_by     VARCHAR(100) NOT NULL,
    requested_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by      VARCHAR(100),
    approved_at      TIMESTAMPTZ,
    disbursement_id  UUID,
    version          BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT free_look_two_person CHECK (approved_by IS NULL OR approved_by <> requested_by)
);
CREATE UNIQUE INDEX ux_free_look_live ON benefitpayout.free_look_cancellation (policy_number)
    WHERE status IN ('REQUESTED','APPROVED');

CREATE TABLE benefitpayout.free_look_deduction (
    deduction_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    cancellation_id UUID NOT NULL REFERENCES benefitpayout.free_look_cancellation(cancellation_id),
    description     VARCHAR(200) NOT NULL,
    amount          NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    document_id     UUID
);

-- Selectors for the drains: cross-tenant, ids only, the policies_due_to_expire() shape. The drain
-- sets the tenant from each row and does everything else under RLS.
CREATE OR REPLACE FUNCTION benefitpayout.instalments_falling_due()
RETURNS TABLE (instalment_id UUID, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT i.instalment_id, i.tenant_id FROM benefitpayout.payout_instalment i
     WHERE i.status = 'SCHEDULED' AND i.due_date <= current_date
     ORDER BY i.due_date LIMIT 500;
$$;
CREATE OR REPLACE FUNCTION benefitpayout.tenants_with_stream_instalments_due()
RETURNS TABLE (tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT DISTINCT i.tenant_id FROM benefitpayout.payout_instalment i
      JOIN benefitpayout.payout_stream s ON s.stream_id = i.stream_id
     WHERE i.status = 'DUE' AND s.status = 'ACTIVE' AND i.payment_run_id IS NULL;
$$;
CREATE OR REPLACE FUNCTION benefitpayout.streams_due_for_proof_of_life()
RETURNS TABLE (stream_id UUID, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT s.stream_id, s.tenant_id FROM benefitpayout.payout_stream s
     WHERE s.status = 'ACTIVE' AND s.proof_of_life_due_date < current_date LIMIT 500;
$$;
REVOKE EXECUTE ON FUNCTION benefitpayout.instalments_falling_due() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION benefitpayout.tenants_with_stream_instalments_due() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION benefitpayout.streams_due_for_proof_of_life() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION benefitpayout.instalments_falling_due() TO app_role;
GRANT EXECUTE ON FUNCTION benefitpayout.tenants_with_stream_instalments_due() TO app_role;
GRANT EXECUTE ON FUNCTION benefitpayout.streams_due_for_proof_of_life() TO app_role;

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['payout_stream','payout_instalment','premium_tally','payment_run',
                             'free_look_cancellation','free_look_deduction'] LOOP
        EXECUTE format('ALTER TABLE benefitpayout.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON benefitpayout.%I USING (tenant_id = '
            'NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)', t || '_tenant_isolation', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON benefitpayout.%I TO app_role', t);
    END LOOP;
END $$;
```

No `DELETE` grant: a payout row is never deleted, only cancelled.

Also add the schema to the production migration path: open `scripts/migrate.sh` and `scripts/configure-db.sh` and add `benefitpayout` to whatever ordered module list each holds, after `payment`. (Read both first; if either globs `db-migrations/*`, no edit is needed — say so in the commit message.)

- [ ] **Step 2: Add benefitpayout/V1 after product/V18 in every test class**

```bash
grep -rl 'product/V18__payout_schedule.sql' src/test/java | while read f; do
  grep -q 'benefitpayout/V1__create_benefitpayout_schema.sql' "$f" || \
  sed -i 's#\( *\)"db-migrations/product/V18__payout_schedule.sql",#&\n\1"db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",#' "$f"
done
grep -rL 'benefitpayout/V1__create' $(grep -rl 'product/V18__payout_schedule.sql' src/test/java)
```

Expected: the last command prints nothing. Why everywhere: from Task 2 on, a `policy.PolicyIssued` in ANY Spring test reaches `benefitpayout.PolicyEventListener`, and from Task 4 claims calls `BenefitPayoutApi` synchronously. A test DB without the schema would turn those into errors in classes this step never touches.

- [ ] **Step 3: Write the module declaration and api types**

```java
// benefitpayout/package-info.java
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "policy::api", "product::api" })
package tz.co.nlolo.lifeplatform.benefitpayout;
```

```java
// benefitpayout/api/package-info.java
@org.springframework.modulith.NamedInterface("api")
package tz.co.nlolo.lifeplatform.benefitpayout.api;
```

```java
// benefitpayout/api/InstalmentStatus.java
package tz.co.nlolo.lifeplatform.benefitpayout.api;

public enum InstalmentStatus { SCHEDULED, DUE, ON_HOLD, REVIEWED, APPROVED, PAID, FAILED, IN_DOUBT, CANCELLED }
```

```java
// benefitpayout/api/StreamStatus.java
package tz.co.nlolo.lifeplatform.benefitpayout.api;

public enum StreamStatus { PENDING_ACTIVATION, ACTIVE, SUSPENDED, ENDED }
```

```java
// benefitpayout/api/ProofOfLifeMethod.java
package tz.co.nlolo.lifeplatform.benefitpayout.api;

/** How the reviewer confirmed the life assured is alive (Q2). */
public enum ProofOfLifeMethod { IN_PERSON, PHONE_OR_VIDEO, LIFE_CERTIFICATE }
```

```java
// benefitpayout/api/PayoutStateException.java
package tz.co.nlolo.lifeplatform.benefitpayout.api;

/** A refused payout transition. Mapped to 422 with this message. */
public class PayoutStateException extends RuntimeException {
    public PayoutStateException(String message) { super(message); }
}
```

```java
// benefitpayout/api/PayoutNotFoundException.java
package tz.co.nlolo.lifeplatform.benefitpayout.api;

import java.util.UUID;

public class PayoutNotFoundException extends RuntimeException {
    public PayoutNotFoundException(UUID id) { super("No payout record " + id); }
}
```

```java
// benefitpayout/api/PayoutInstalmentView.java
package tz.co.nlolo.lifeplatform.benefitpayout.api;

import tz.co.nlolo.lifeplatform.product.api.PayoutKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PayoutInstalmentView(UUID instalmentId, String policyNumber, PayoutKind kind, LocalDate dueDate,
                                   BigDecimal originalAmount, BigDecimal currentAmount, String currency,
                                   String restatementReason, InstalmentStatus status, String statusReason,
                                   UUID streamId, String payeeRef, ProofOfLifeMethod proofOfLifeMethod,
                                   String reviewedBy, String approvedBy, UUID paymentRunId, int attempts) {}
```

```java
// benefitpayout/api/BenefitPayoutApi.java
package tz.co.nlolo.lifeplatform.benefitpayout.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The payout engine's published face. Tenant comes from TenantContext throughout. */
public interface BenefitPayoutApi {

    List<PayoutInstalmentView> listForPolicy(String policyNumber);

    PayoutInstalmentView getInstalment(UUID instalmentId);

    /** Whether this policy's maturity is paid on schedule -- claims refuses a manual MATURITY claim then. */
    boolean hasScheduledMaturity(String policyNumber);
}
```

Tasks 3–7 add methods to this interface; each says exactly which.

- [ ] **Step 4: Write the failing arithmetic and expander tests**

```java
// src/test/java/tz/co/nlolo/lifeplatform/benefitpayout/PayoutArithmeticTest.java
package tz.co.nlolo.lifeplatform.benefitpayout;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.PayoutArithmetic;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutArithmeticTest {

    @Test
    void splitPutsTheRemainderOnTheLastPartSoTheYearTotalIsExact() {
        assertThat(PayoutArithmetic.splitYear(new BigDecimal("100.00"), 3))
            .containsExactly(new BigDecimal("33.33"), new BigDecimal("33.33"), new BigDecimal("33.34"));
        assertThat(PayoutArithmetic.splitYear(new BigDecimal("1200.00"), 12)).allMatch(p -> p.compareTo(new BigDecimal("100.00")) == 0);
    }

    @Test
    void percentOfRoundsHalfEvenToCents() {
        assertThat(PayoutArithmetic.percentOf(new BigDecimal("1000000.00"), new BigDecimal("3")))
            .isEqualByComparingTo("30000.00");
        assertThat(PayoutArithmetic.percentOf(new BigDecimal("333.33"), new BigDecimal("50")))
            .isEqualByComparingTo("166.66");
    }

    @Test
    void restateScalesByPaidUpOverOriginal() {
        assertThat(PayoutArithmetic.restate(new BigDecimal("100000.00"), new BigDecimal("400000.00"), new BigDecimal("1000000.00")))
            .isEqualByComparingTo("40000.00");
    }
}
```

```java
// src/test/java/tz/co/nlolo/lifeplatform/benefitpayout/ScheduleExpanderTest.java
package tz.co.nlolo.lifeplatform.benefitpayout;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.ScheduleExpander;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.ScheduleExpander.Planned;
import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleExpanderTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 15);
    private static final LocalDate MATURITY = LocalDate.of(2046, 1, 15);
    private static final BigDecimal SA = new BigDecimal("1000000.00");

    private static PayoutPlan plan(PayoutRowInput... rows) {
        return PayoutPlan.authored(new PayoutTerms(15, 12, false, null), List.of(rows));
    }

    @Test
    void anAnnualSurvivalRowPaysOnEachAnniversaryOfItsYears() {
        List<Planned> out = ScheduleExpander.expand(plan(
            new PayoutRowInput(PayoutKind.SURVIVAL, 5, 5, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("10"), PayoutFrequency.ANNUAL)),
            START, MATURITY, SA);
        assertThat(out).containsExactly(new Planned(PayoutKind.SURVIVAL, 0, LocalDate.of(2031, 1, 15), new BigDecimal("100000.00")));
    }

    @Test
    void aMonthlyIncomeRowPaysTwelveInstalmentsPerYearEndingOnTheAnniversary() {
        List<Planned> out = ScheduleExpander.expand(plan(
            new PayoutRowInput(PayoutKind.INCOME, 6, 7, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("3"), PayoutFrequency.MONTHLY)),
            START, MATURITY, SA);
        assertThat(out).hasSize(24);
        assertThat(out.get(0).dueDate()).isEqualTo(LocalDate.of(2031, 2, 15));
        assertThat(out.get(11).dueDate()).isEqualTo(LocalDate.of(2032, 1, 15));
        assertThat(out.get(0).amount()).isEqualByComparingTo("2500.00");
    }

    @Test
    void maturityPaysOnTheMaturityDateAndRopHasNoAmountYet() {
        List<Planned> out = ScheduleExpander.expand(plan(
            new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null),
            new PayoutRowInput(PayoutKind.RETURN_OF_PREMIUM, null, null, PayoutAmountBasis.PERCENT_OF_PREMIUMS, new BigDecimal("100"), null)),
            START, MATURITY, SA);
        assertThat(out).containsExactly(
            new Planned(PayoutKind.MATURITY, 0, MATURITY, new BigDecimal("1000000.00")),
            new Planned(PayoutKind.RETURN_OF_PREMIUM, 1, MATURITY, null));
    }

    @Test
    void instalmentsAfterTheMaturityDateAreDropped() {
        List<Planned> out = ScheduleExpander.expand(plan(
            new PayoutRowInput(PayoutKind.SURVIVAL, 18, 25, PayoutAmountBasis.FIXED, new BigDecimal("5000"), PayoutFrequency.ANNUAL)),
            START, MATURITY, SA);
        assertThat(out).extracting(Planned::dueDate)
            .containsExactly(LocalDate.of(2044, 1, 15), LocalDate.of(2045, 1, 15), LocalDate.of(2046, 1, 15));
    }
}
```

- [ ] **Step 5: Run them to verify they fail**

Run: `./mvnw -o test -Dtest=PayoutArithmeticTest,ScheduleExpanderTest`
Expected: COMPILATION FAILURE — `PayoutArithmetic`, `ScheduleExpander` missing.

- [ ] **Step 6: Write PayoutArithmetic and ScheduleExpander**

```java
// benefitpayout/domain/PayoutArithmetic.java
package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Every money figure the payout engine computes, in one place, HALF_EVEN to cents. */
public final class PayoutArithmetic {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private PayoutArithmetic() {}

    public static BigDecimal percentOf(BigDecimal base, BigDecimal percent) {
        return base.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
    }

    /** Split a year's amount into equal parts; the last part absorbs the rounding remainder. */
    public static List<BigDecimal> splitYear(BigDecimal yearAmount, int parts) {
        BigDecimal each = yearAmount.divide(BigDecimal.valueOf(parts), 2, RoundingMode.DOWN);
        List<BigDecimal> out = new ArrayList<>();
        for (int i = 0; i < parts - 1; i++) {
            out.add(each);
        }
        out.add(yearAmount.setScale(2, RoundingMode.HALF_EVEN).subtract(each.multiply(BigDecimal.valueOf(parts - 1))));
        return out;
    }

    /** Paid-up restatement (step 1's PROPORTIONATE basis): amount × paid-up SA ÷ original SA. */
    public static BigDecimal restate(BigDecimal amount, BigDecimal paidUpSumAssured, BigDecimal originalSumAssured) {
        return amount.multiply(paidUpSumAssured).divide(originalSumAssured, 2, RoundingMode.HALF_EVEN);
    }
}
```

```java
// benefitpayout/domain/ScheduleExpander.java
package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a version's payout rows into dated amounts for one policy (guide §16: "calculated and
 * stored on the day the policy is issued"). Policy year N runs from start+(N-1)y to start+Ny; its
 * k-th instalment falls monthsApart×k months into the year, so the last one lands on the
 * anniversary. Nothing is dated after the maturity date.
 */
public final class ScheduleExpander {

    public record Planned(PayoutKind kind, int rowOrder, LocalDate dueDate, BigDecimal amount) {}

    private ScheduleExpander() {}

    public static List<Planned> expand(PayoutPlan plan, LocalDate start, LocalDate maturityDate, BigDecimal sumAssured) {
        List<Planned> out = new ArrayList<>();
        for (int order = 0; order < plan.rows().size(); order++) {
            PayoutRowInput row = plan.rows().get(order);
            switch (row.kind()) {
                case MATURITY -> out.add(new Planned(row.kind(), order, maturityDate, amountFor(row, sumAssured)));
                case RETURN_OF_PREMIUM -> out.add(new Planned(row.kind(), order, maturityDate, null));
                case SURVIVAL, INCOME -> {
                    BigDecimal yearAmount = amountFor(row, sumAssured);
                    int parts = row.frequency().perYear();
                    List<BigDecimal> split = PayoutArithmetic.splitYear(yearAmount, parts);
                    for (int year = row.fromPolicyYear(); year <= row.toPolicyYear(); year++) {
                        LocalDate yearStart = start.plusYears(year - 1L);
                        for (int k = 1; k <= parts; k++) {
                            LocalDate due = yearStart.plusMonths((long) k * row.frequency().monthsApart());
                            if (maturityDate != null && due.isAfter(maturityDate)) {
                                continue;
                            }
                            out.add(new Planned(row.kind(), order, due, split.get(k - 1)));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static BigDecimal amountFor(PayoutRowInput row, BigDecimal sumAssured) {
        return row.amountBasis() == PayoutAmountBasis.FIXED
            ? row.amountValue().setScale(2, java.math.RoundingMode.HALF_EVEN)
            : PayoutArithmetic.percentOf(sumAssured, row.amountValue());
    }
}
```

- [ ] **Step 7: Run the unit tests**

Run: `./mvnw -o test -Dtest=PayoutArithmeticTest,ScheduleExpanderTest`
Expected: PASS, 7 tests.

- [ ] **Step 8: Write the entities**

```java
// benefitpayout/domain/PayoutInstalment.java
package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.benefitpayout.api.InstalmentStatus;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;
import tz.co.nlolo.lifeplatform.benefitpayout.api.ProofOfLifeMethod;
import tz.co.nlolo.lifeplatform.product.api.PayoutKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * One dated amount owed (spec §5.1). Every transition is a method here, so the state machine is
 * unit-testable and the two-person rule lives beside the CHECK that backs it.
 */
@Entity
@Table(name = "payout_instalment", schema = "benefitpayout")
public class PayoutInstalment {

    /** States in which nothing has left the company and the instalment may still be cancelled. */
    private static final Set<InstalmentStatus> CANCELLABLE = EnumSet.of(
        InstalmentStatus.SCHEDULED, InstalmentStatus.DUE, InstalmentStatus.ON_HOLD, InstalmentStatus.REVIEWED);

    @Id @UuidGenerator
    @Column(name = "instalment_id") private UUID instalmentId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private String kind;
    @Column(name = "row_order", nullable = false) private int rowOrder;
    @Column(name = "stream_id") private UUID streamId;
    @Column(name = "due_date", nullable = false) private LocalDate dueDate;
    @Column(name = "original_amount") private BigDecimal originalAmount;
    @Column(name = "current_amount") private BigDecimal currentAmount;
    @Column(nullable = false) private String currency = "TZS";
    @Column(name = "restatement_reason") private String restatementReason;
    @Column(nullable = false) private String status = InstalmentStatus.SCHEDULED.name();
    @Column(name = "status_reason") private String statusReason;
    @Column(name = "payee_ref") private String payeeRef;
    @Column(name = "proof_of_life_method") private String proofOfLifeMethod;
    @Column(name = "proof_of_life_document_id") private UUID proofOfLifeDocumentId;
    @Column(name = "reviewed_by") private String reviewedBy;
    @Column(name = "reviewed_at") private Instant reviewedAt;
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "payment_run_id") private UUID paymentRunId;
    @Column(name = "disbursement_id") private UUID disbursementId;
    @Column(nullable = false) private int attempts;
    @Version private long version;

    protected PayoutInstalment() {}

    public PayoutInstalment(UUID tenantId, String policyNumber, PayoutKind kind, int rowOrder, UUID streamId,
                            LocalDate dueDate, BigDecimal amount, String currency) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.kind = kind.name();
        this.rowOrder = rowOrder;
        this.streamId = streamId;
        this.dueDate = dueDate;
        this.originalAmount = amount;
        this.currentAmount = amount;
        this.currency = currency;
    }

    public InstalmentStatus status() { return InstalmentStatus.valueOf(status); }
    public PayoutKind kind() { return PayoutKind.valueOf(kind); }

    /** SCHEDULED -> DUE, or ON_HOLD when premiums are behind (Q3). ROP gets its amount here. */
    public void fallDue(boolean premiumsUpToDate, BigDecimal valuedAmount) {
        require(InstalmentStatus.SCHEDULED, "fall due");
        if (valuedAmount != null) {
            this.originalAmount = valuedAmount;
            this.currentAmount = valuedAmount;
        }
        if (premiumsUpToDate) {
            this.status = InstalmentStatus.DUE.name();
            this.statusReason = null;
        } else {
            hold("Premiums are not paid up to the due date");
        }
    }

    public void hold(String reason) {
        if (status() != InstalmentStatus.SCHEDULED && status() != InstalmentStatus.DUE) {
            throw new PayoutStateException("Payout " + instalmentId + " is " + status + " and cannot be held");
        }
        this.status = InstalmentStatus.ON_HOLD.name();
        this.statusReason = reason;
    }

    /** ON_HOLD -> DUE once the hold's cause has gone. */
    public void release() {
        require(InstalmentStatus.ON_HOLD, "be released");
        this.status = InstalmentStatus.DUE.name();
        this.statusReason = null;
    }

    /** DUE -> REVIEWED (Q2). Proof of life is required unless the payout is owed by date alone. */
    public void review(String reviewer, String payeeRef, ProofOfLifeMethod method, UUID documentId) {
        require(InstalmentStatus.DUE, "be reviewed");
        if (payeeRef == null || payeeRef.isBlank()) {
            throw new PayoutStateException("A payout needs a payee reference");
        }
        boolean needsProof = kind() == PayoutKind.SURVIVAL || kind() == PayoutKind.INCOME;
        if (needsProof && method == null) {
            throw new PayoutStateException("A " + kind + " payout needs proof that the life assured is alive");
        }
        this.payeeRef = payeeRef;
        this.proofOfLifeMethod = method != null ? method.name() : null;
        this.proofOfLifeDocumentId = documentId;
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
        this.status = InstalmentStatus.REVIEWED.name();
    }

    /** REVIEWED -> APPROVED, by someone other than the reviewer (Q1). */
    public void approve(String approver) {
        require(InstalmentStatus.REVIEWED, "be approved");
        if (approver == null || approver.equals(reviewedBy)) {
            throw new PayoutStateException("A payout must be approved by someone other than the person who reviewed it ("
                + reviewedBy + ")");
        }
        this.approvedBy = approver;
        this.approvedAt = Instant.now();
        this.status = InstalmentStatus.APPROVED.name();
        this.attempts++;
    }

    /** DUE -> APPROVED inside an approved payment run (Q7): the run's approver is the checker. */
    public void approveInRun(UUID paymentRunId, String approver, String payeeRef) {
        require(InstalmentStatus.DUE, "join a payment run");
        this.paymentRunId = paymentRunId;
        this.payeeRef = payeeRef;
        this.approvedBy = approver;
        this.approvedAt = Instant.now();
        this.status = InstalmentStatus.APPROVED.name();
        this.attempts++;
    }

    public void assignToRun(UUID paymentRunId) {
        require(InstalmentStatus.DUE, "join a payment run");
        this.paymentRunId = paymentRunId;
    }

    public void markPaid(UUID disbursementId) {
        require(InstalmentStatus.APPROVED, "be marked paid");
        this.disbursementId = disbursementId;
        this.status = InstalmentStatus.PAID.name();
    }

    public void markFailed() {
        require(InstalmentStatus.APPROVED, "be marked failed");
        this.status = InstalmentStatus.FAILED.name();
    }

    /** FAILED -> APPROVED for another attempt; the approval stands. */
    public void retry() {
        require(InstalmentStatus.FAILED, "be retried");
        this.status = InstalmentStatus.APPROVED.name();
        this.attempts++;
    }

    /** Cancel if nothing has left the company. Returns false (no-op) once approved or later. */
    public boolean cancel(String reason) {
        if (!CANCELLABLE.contains(status())) {
            return false;
        }
        this.status = InstalmentStatus.CANCELLED.name();
        this.statusReason = reason;
        return true;
    }

    /** Reinstatement: a lapse-cancelled future instalment comes back as SCHEDULED. */
    public void restore() {
        require(InstalmentStatus.CANCELLED, "be restored");
        this.status = InstalmentStatus.SCHEDULED.name();
        this.statusReason = null;
    }

    /** Paid-up: the new figure goes beside the original, never over it. */
    public void restate(BigDecimal newAmount, String reason) {
        if (!CANCELLABLE.contains(status())) {
            return;
        }
        this.currentAmount = newAmount;
        this.restatementReason = reason;
    }

    private void require(InstalmentStatus expected, String action) {
        if (status() != expected) {
            throw new PayoutStateException("Payout " + instalmentId + " is " + status + ", so it cannot " + action);
        }
    }

    public UUID getInstalmentId() { return instalmentId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public int getRowOrder() { return rowOrder; }
    public UUID getStreamId() { return streamId; }
    public LocalDate getDueDate() { return dueDate; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public BigDecimal getCurrentAmount() { return currentAmount; }
    public String getCurrency() { return currency; }
    public String getRestatementReason() { return restatementReason; }
    public String getStatusReason() { return statusReason; }
    public String getPayeeRef() { return payeeRef; }
    public String getProofOfLifeMethod() { return proofOfLifeMethod; }
    public String getReviewedBy() { return reviewedBy; }
    public String getApprovedBy() { return approvedBy; }
    public UUID getPaymentRunId() { return paymentRunId; }
    public int getAttempts() { return attempts; }
}
```

```java
// benefitpayout/domain/PayoutStream.java
package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;
import tz.co.nlolo.lifeplatform.benefitpayout.api.StreamStatus;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "payout_stream", schema = "benefitpayout")
public class PayoutStream {

    @Id @UuidGenerator
    @Column(name = "stream_id") private UUID streamId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "row_order", nullable = false) private int rowOrder;
    @Column(nullable = false) private String status = StreamStatus.PENDING_ACTIVATION.name();
    @Column(name = "proof_of_life_interval_months", nullable = false) private int proofOfLifeIntervalMonths;
    @Column(name = "proof_of_life_due_date") private LocalDate proofOfLifeDueDate;
    @Version private long version;

    protected PayoutStream() {}

    public PayoutStream(UUID tenantId, String policyNumber, int rowOrder, int proofOfLifeIntervalMonths) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.rowOrder = rowOrder;
        this.proofOfLifeIntervalMonths = proofOfLifeIntervalMonths;
    }

    public StreamStatus status() { return StreamStatus.valueOf(status); }

    /** The first instalment was approved with proof of life on {@code provenOn}. */
    public void activate(LocalDate provenOn) {
        if (status() != StreamStatus.PENDING_ACTIVATION) {
            throw new PayoutStateException("Stream " + streamId + " is " + status + ", not awaiting activation");
        }
        this.status = StreamStatus.ACTIVE.name();
        this.proofOfLifeDueDate = provenOn.plusMonths(proofOfLifeIntervalMonths);
    }

    public void suspendForProofOfLife() {
        if (status() == StreamStatus.ACTIVE) {
            this.status = StreamStatus.SUSPENDED.name();
        }
    }

    /** New proof of life: an ACTIVE or SUSPENDED stream runs again until the next interval. */
    public void recordProofOfLife(LocalDate provenOn) {
        if (status() != StreamStatus.ACTIVE && status() != StreamStatus.SUSPENDED) {
            throw new PayoutStateException("Stream " + streamId + " is " + status + "; proof of life cannot be recorded");
        }
        this.status = StreamStatus.ACTIVE.name();
        this.proofOfLifeDueDate = provenOn.plusMonths(proofOfLifeIntervalMonths);
    }

    public void end() { this.status = StreamStatus.ENDED.name(); }

    public UUID getStreamId() { return streamId; }
    public String getPolicyNumber() { return policyNumber; }
    public int getRowOrder() { return rowOrder; }
    public LocalDate getProofOfLifeDueDate() { return proofOfLifeDueDate; }
}
```

```java
// benefitpayout/domain/PremiumTally.java
package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import jakarta.persistence.*;
import tz.co.nlolo.lifeplatform.product.api.PremiumFrequency;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What billing has reported about one policy's premiums. {@link #isPaidUpTo} answers Q3's hold
 * question without asking billing: the premium last due on or before the payout's date (never
 * past the paying term) must be inside billing's contiguous paid-to date.
 */
@Entity
@Table(name = "premium_tally", schema = "benefitpayout")
public class PremiumTally {

    @Id
    @Column(name = "policy_number") private String policyNumber;
    @Column(name = "tenant_id", nullable = false) private java.util.UUID tenantId;
    @Column(name = "premium_frequency", nullable = false) private String premiumFrequency;
    @Column(name = "issue_date", nullable = false) private LocalDate issueDate;
    @Column(name = "premium_paying_until") private LocalDate premiumPayingUntil;
    @Column(name = "premiums_collected", nullable = false) private BigDecimal premiumsCollected = BigDecimal.ZERO;
    @Column(nullable = false) private String currency = "TZS";
    @Column(name = "paid_to_date") private LocalDate paidToDate;
    @Version private long version;

    protected PremiumTally() {}

    public PremiumTally(java.util.UUID tenantId, String policyNumber, String premiumFrequency, LocalDate issueDate,
                        LocalDate premiumPayingUntil, String currency) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.premiumFrequency = premiumFrequency;
        this.issueDate = issueDate;
        this.premiumPayingUntil = premiumPayingUntil;
        this.currency = currency;
    }

    public void record(BigDecimal collected, LocalDate newPaidToDate) {
        this.premiumsCollected = this.premiumsCollected.add(collected);
        if (newPaidToDate != null) {
            this.paidToDate = newPaidToDate;
        }
    }

    public boolean isPaidUpTo(LocalDate payoutDate) {
        PremiumFrequency frequency = PremiumFrequency.valueOf(premiumFrequency);
        if (frequency == PremiumFrequency.SINGLE) {
            return premiumsCollected.signum() > 0;
        }
        LocalDate lastRequired = lastPremiumDueOnOrBefore(frequency, payoutDate);
        return lastRequired == null || (paidToDate != null && !paidToDate.isBefore(lastRequired));
    }

    /** Billing's due dates run issue + k periods, k >= 1 (premium in arrears), up to the paying end. */
    private LocalDate lastPremiumDueOnOrBefore(PremiumFrequency frequency, LocalDate payoutDate) {
        LocalDate limit = premiumPayingUntil != null && premiumPayingUntil.isBefore(payoutDate) ? premiumPayingUntil : payoutDate;
        int months = 12 / frequency.instalmentsPerYear();
        LocalDate last = null;
        for (LocalDate d = issueDate.plusMonths(months); !d.isAfter(limit); d = d.plusMonths(months)) {
            last = d;
        }
        return last;
    }

    public BigDecimal getPremiumsCollected() { return premiumsCollected; }
    public String getCurrency() { return currency; }
    public LocalDate getPaidToDate() { return paidToDate; }
}
```

If `PremiumFrequency` exposes its per-year count under a different accessor name than `instalmentsPerYear()`, use the existing accessor (open `product/api/PremiumFrequency.java`).

- [ ] **Step 9: Write the repositories**

```java
// benefitpayout/infrastructure/PayoutInstalmentRepository.java
package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.PayoutInstalment;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PayoutInstalmentRepository extends JpaRepository<PayoutInstalment, UUID> {
    List<PayoutInstalment> findByPolicyNumberOrderByDueDateAscRowOrderAsc(String policyNumber);
    List<PayoutInstalment> findByPolicyNumberAndStatus(String policyNumber, String status);
    List<PayoutInstalment> findByPolicyNumberAndDueDateAfter(String policyNumber, LocalDate after);
    List<PayoutInstalment> findByStreamIdAndStatus(UUID streamId, String status);
    List<PayoutInstalment> findByPaymentRunId(UUID paymentRunId);
    Page<PayoutInstalment> findByStatusIn(Collection<String> statuses, Pageable pageable);
    boolean existsByPolicyNumberAndKindIn(String policyNumber, Collection<String> kinds);

    @Query(value = "SELECT instalment_id, tenant_id FROM benefitpayout.instalments_falling_due()", nativeQuery = true)
    List<Object[]> findFallingDueAcrossTenants();
}
```

```java
// benefitpayout/infrastructure/PayoutStreamRepository.java
package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.PayoutStream;

import java.util.List;
import java.util.UUID;

public interface PayoutStreamRepository extends JpaRepository<PayoutStream, UUID> {
    List<PayoutStream> findByPolicyNumber(String policyNumber);

    @Query(value = "SELECT stream_id, tenant_id FROM benefitpayout.streams_due_for_proof_of_life()", nativeQuery = true)
    List<Object[]> findDueForProofOfLifeAcrossTenants();
}
```

```java
// benefitpayout/infrastructure/PremiumTallyRepository.java
package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.PremiumTally;

public interface PremiumTallyRepository extends JpaRepository<PremiumTally, String> {}
```

- [ ] **Step 10: Write the failing integration test**

`BenefitPayoutApiIntegrationTest` is `@Testcontainers @SpringBootTest(classes = Application.class)`. Copy the datasource `@DynamicPropertySource` and the `@BeforeAll` migration list verbatim from `policyloan/LoanDisbursementEndToEndTest.java` (its list already ends with audit V1–V3 and, after Tasks 1–2, carries product V18 and benefitpayout V1), then append `"db-migrations/policy/V31__free_look_status.sql"` and `"db-migrations/payment/V8__benefit_payout_purposes.sql"` once those exist (Tasks 3 and 4 add them; leave them out now).

```java
    @Autowired ProductApi productApi;
    @Autowired PolicyApi policyApi;
    @Autowired BenefitPayoutApi benefitPayoutApi;
    @Autowired PayoutTestFixtures fixtures;   // see below

    @Test
    void issuingAMoneyBackEndowmentStoresItsWholeSchedule() {
        String policyNumber = fixtures.issueEndowment(TENANT, PayoutPlan.authored(new PayoutTerms(15, null, true, null), List.of(
            new PayoutRowInput(PayoutKind.SURVIVAL, 5, 5, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("10"), PayoutFrequency.ANNUAL),
            new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null))),
            new BigDecimal("1000000.00"), 240);

        TenantContext.set(TENANT);
        try {
            List<PayoutInstalmentView> schedule = benefitPayoutApi.listForPolicy(policyNumber);
            assertThat(schedule).extracting(PayoutInstalmentView::kind).containsExactly(PayoutKind.SURVIVAL, PayoutKind.MATURITY);
            assertThat(schedule).allMatch(v -> v.status() == InstalmentStatus.SCHEDULED);
            assertThat(schedule.get(0).currentAmount()).isEqualByComparingTo("100000.00");
            assertThat(benefitPayoutApi.hasScheduledMaturity(policyNumber)).isTrue();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aPolicyOnAVersionWithNoPlanGetsNoInstalments() {
        String policyNumber = fixtures.issueEndowment(TENANT, PayoutPlan.none(), new BigDecimal("500000.00"), 120);
        TenantContext.set(TENANT);
        try {
            assertThat(benefitPayoutApi.listForPolicy(policyNumber)).isEmpty();
            assertThat(benefitPayoutApi.hasScheduledMaturity(policyNumber)).isFalse();
        } finally {
            TenantContext.clear();
        }
    }
```

`TENANT` is `private static final UUID TENANT = UUID.randomUUID();`. The class carries `@Import(PayoutTestFixtures.class)`. Write the fixture — its calls mirror `PolicyApiIntegrationTest.buildFixture` / `issueDirectly` (lines 163-189) and that class's cash-value issuance (`new PolicyApi.IssueRequest(... commencement, 240, null, null, null)` then `activateOnFirstPremium`):

```java
// src/test/java/tz/co/nlolo/lifeplatform/benefitpayout/PayoutTestFixtures.java
package tz.co.nlolo.lifeplatform.benefitpayout;

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

@TestComponent
public class PayoutTestFixtures {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final PartyApi partyApi;
    private final ProductApi productApi;
    private final PolicyApi policyApi;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate tx;

    public PayoutTestFixtures(PartyApi partyApi, ProductApi productApi, PolicyApi policyApi,
                              ApplicationEventPublisher publisher, PlatformTransactionManager tm) {
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.policyApi = policyApi;
        this.publisher = publisher;
        this.tx = new TransactionTemplate(tm);
    }

    /** An ACTIVE endowment, commenced {@code commencement}, on a fresh version carrying {@code plan}. */
    public String issueEndowment(UUID tenant, PayoutPlan plan, BigDecimal sumAssured, int termMonths, LocalDate commencement) {
        return issue(tenant, ProductCategory.ENDOWMENT, plan, sumAssured, termMonths, commencement, "MONTHLY");
    }

    public String issueEndowment(UUID tenant, PayoutPlan plan, BigDecimal sumAssured, int termMonths) {
        return issueEndowment(tenant, plan, sumAssured, termMonths, LocalDate.now());
    }

    public String issue(UUID tenant, ProductCategory category, PayoutPlan plan, BigDecimal sumAssured, int termMonths,
                        LocalDate commencement, String premiumFrequency) {
        TenantContext.set(tenant);
        try {
            int n = SEQ.incrementAndGet();
            PartyView applicant = partyApi.registerIndividual("Payout Test Life " + n, LocalDate.of(1985, 1, 1),
                "+25571400" + String.format("%04d", n % 10000), null, "test-agent");
            ProductSummaryView product = productApi.createProduct("PAYOUT-" + n + "-" + tenant.toString().substring(0, 4),
                "Payout Test Product", category, "TZS", "actuary");
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-49", BigDecimal.ONE, 30, 49),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, List.of(), EligibilityBounds.none(), FrequencyLoading.none(), ANY_FILING, plan, "actuary");
            UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();
            PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(), versionId,
                sumAssured, "TZS", new BigDecimal("50000.00"), "TZS", premiumFrequency, null, List.of(),
                "payout test", commencement, termMonths, null, null, null);
            String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
            policyApi.activateOnFirstPremium(policyNumber);
            return policyNumber;
        } finally {
            TenantContext.clear();
        }
    }

    /** What billing publishes when a premium clears, so benefitpayout's AFTER_COMMIT listener fires. */
    public void collectPremium(UUID tenant, String policyNumber, BigDecimal amount, LocalDate paidToDate) {
        tx.executeWithoutResult(s -> publisher.publishEvent(DomainEventEnvelope.of("billing.PremiumCollected", tenant,
            Map.of("policyNumber", policyNumber,
                   "amount", Map.of("amount", amount.toPlainString(), "currencyCode", "TZS"),
                   "paidToDate", paidToDate.toString()))));
    }

    /** Publish any envelope inside a committed transaction (for policy / claims / payment events). */
    public void publish(UUID tenant, String eventType, Map<String, Object> payload) {
        tx.executeWithoutResult(s -> publisher.publishEvent(DomainEventEnvelope.of(eventType, tenant, payload)));
    }
}
```

If `IssueRequest`'s 16-argument form or `registerIndividual`'s 5-argument form has drifted, match the call in `PolicyApiIntegrationTest` at the line that issues with `commencement, 240` — that is the authoritative shape.

- [ ] **Step 11: Run it to verify it fails**

Run: `./mvnw -o test -Dtest=BenefitPayoutApiIntegrationTest`
Expected: FAIL — no `BenefitPayoutApi` bean.

- [ ] **Step 12: Write the impl and the two listeners**

```java
// benefitpayout/application/BenefitPayoutApiImpl.java
package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.benefitpayout.api.*;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.*;
import tz.co.nlolo.lifeplatform.benefitpayout.infrastructure.*;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BenefitPayoutApiImpl implements BenefitPayoutApi {

    private final PayoutInstalmentRepository instalments;
    private final PayoutStreamRepository streams;
    private final PremiumTallyRepository tallies;
    private final ProductApi productApi;
    private final PolicyApi policyApi;

    public BenefitPayoutApiImpl(PayoutInstalmentRepository instalments, PayoutStreamRepository streams,
                                PremiumTallyRepository tallies, ProductApi productApi, PolicyApi policyApi) {
        this.instalments = instalments;
        this.streams = streams;
        this.tallies = tallies;
        this.productApi = productApi;
        this.policyApi = policyApi;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayoutInstalmentView> listForPolicy(String policyNumber) {
        return instalments.findByPolicyNumberOrderByDueDateAscRowOrderAsc(policyNumber).stream().map(Views::of).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PayoutInstalmentView getInstalment(UUID instalmentId) {
        return Views.of(load(instalmentId));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasScheduledMaturity(String policyNumber) {
        return instalments.existsByPolicyNumberAndKindIn(policyNumber,
            List.of(PayoutKind.MATURITY.name(), PayoutKind.RETURN_OF_PREMIUM.name()));
    }

    /** policy.PolicyIssued: open the premium tally and store every instalment the plan implies. */
    @Transactional
    public void expandForIssuedPolicy(String policyNumber, UUID productVersionId, LocalDate issueDate,
                                      LocalDate premiumPayingUntil, String premiumFrequency) {
        UUID tenantId = TenantContext.get();
        if (tallies.existsById(policyNumber)) {
            return; // redelivery
        }
        PolicyView policy = policyApi.getPolicy(policyNumber);
        tallies.save(new PremiumTally(tenantId, policyNumber, premiumFrequency, issueDate, premiumPayingUntil,
            policy.sumAssuredCurrency()));
        PayoutPlan plan = productApi.resolvePayoutPlan(productVersionId);
        if (plan.rows().isEmpty()) {
            return;
        }
        LocalDate start = policy.commencementDate() != null ? policy.commencementDate() : issueDate;
        Map<Integer, UUID> streamByRow = new HashMap<>();
        for (int order = 0; order < plan.rows().size(); order++) {
            if (plan.rows().get(order).kind() == PayoutKind.INCOME) {
                PayoutStream stream = streams.save(new PayoutStream(tenantId, policyNumber, order,
                    plan.terms().proofOfLifeIntervalMonths()));
                streamByRow.put(order, stream.getStreamId());
            }
        }
        for (ScheduleExpander.Planned p : ScheduleExpander.expand(plan, start, policy.maturityDate(), policy.sumAssuredAmount())) {
            instalments.save(new PayoutInstalment(tenantId, policyNumber, p.kind(), p.rowOrder(),
                streamByRow.get(p.rowOrder()), p.dueDate(), p.amount(), policy.sumAssuredCurrency()));
        }
    }

    /** billing.PremiumCollected: add to the tally, and release any instalment the payment cured. */
    @Transactional
    public void recordPremium(String policyNumber, BigDecimal amount, LocalDate paidToDate) {
        tallies.findById(policyNumber).ifPresent(tally -> {
            tally.record(amount, paidToDate);
            tallies.save(tally);
            for (PayoutInstalment held : instalments.findByPolicyNumberAndStatus(policyNumber, InstalmentStatus.ON_HOLD.name())) {
                if (tally.isPaidUpTo(held.getDueDate()) && streamAllowsRelease(held)) {
                    held.release();
                    instalments.save(held);
                }
            }
        });
    }

    boolean streamAllowsRelease(PayoutInstalment instalment) {
        return instalment.getStreamId() == null
            || streams.findById(instalment.getStreamId()).map(s -> s.status() != StreamStatus.SUSPENDED).orElse(true);
    }

    PayoutInstalment load(UUID instalmentId) {
        return instalments.findById(instalmentId).orElseThrow(() -> new PayoutNotFoundException(instalmentId));
    }
}
```

```java
// benefitpayout/application/Views.java
package tz.co.nlolo.lifeplatform.benefitpayout.application;

import tz.co.nlolo.lifeplatform.benefitpayout.api.InstalmentStatus;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutInstalmentView;
import tz.co.nlolo.lifeplatform.benefitpayout.api.ProofOfLifeMethod;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.PayoutInstalment;

final class Views {
    private Views() {}

    static PayoutInstalmentView of(PayoutInstalment i) {
        return new PayoutInstalmentView(i.getInstalmentId(), i.getPolicyNumber(), i.kind(), i.getDueDate(),
            i.getOriginalAmount(), i.getCurrentAmount(), i.getCurrency(), i.getRestatementReason(), i.status(),
            i.getStatusReason(), i.getStreamId(), i.getPayeeRef(),
            i.getProofOfLifeMethod() != null ? ProofOfLifeMethod.valueOf(i.getProofOfLifeMethod()) : null,
            i.getReviewedBy(), i.getApprovedBy(), i.getPaymentRunId(), i.getAttempts());
    }
}
```

```java
// benefitpayout/application/PolicyEventListener.java
package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** policy's lifecycle, as it affects what is owed. Envelope-only: no compile dependency beyond policy::api. */
@Component
public class PolicyEventListener {

    private static final Logger log = LoggerFactory.getLogger(PolicyEventListener.class);

    private final BenefitPayoutApiImpl api;
    private final TransactionTemplate requiresNew;

    public PolicyEventListener(BenefitPayoutApiImpl api, PlatformTransactionManager tm) {
        this.api = api;
        this.requiresNew = new TransactionTemplate(tm);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "policy.PolicyIssued" -> withTenant(envelope, p -> api.expandForIssuedPolicy(
                (String) p.get("policyNumber"), (UUID) p.get("productVersionId"),
                LocalDate.parse((String) p.get("issueDate")),
                p.get("premiumPayingUntil") != null ? LocalDate.parse((String) p.get("premiumPayingUntil")) : null,
                (String) p.get("premiumFrequency")));
            default -> { /* Task 4 adds the lifecycle cases here */ }
        }
    }

    void withTenant(DomainEventEnvelope<?> envelope, Consumer<Map<String, Object>> handler) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            requiresNew.executeWithoutResult(s -> handler.accept(payload));
        } catch (Exception e) {
            log.error("benefitpayout failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
```

```java
// benefitpayout/application/PremiumEventListener.java
package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** billing.PremiumCollected, read by its payload keys `policyNumber`, `amount.amount`, `paidToDate`. */
@Component
public class PremiumEventListener {

    private final BenefitPayoutApiImpl api;
    private final PolicyEventListener tenantRunner;

    public PremiumEventListener(BenefitPayoutApiImpl api, PolicyEventListener tenantRunner) {
        this.api = api;
        this.tenantRunner = tenantRunner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"billing.PremiumCollected".equals(envelope.eventType())) {
            return;
        }
        tenantRunner.withTenant(envelope, p -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> amount = (Map<String, Object>) p.get("amount");
            api.recordPremium((String) p.get("policyNumber"), new BigDecimal((String) amount.get("amount")),
                p.get("paidToDate") != null ? LocalDate.parse((String) p.get("paidToDate")) : null);
        });
    }
}
```

- [ ] **Step 13: Run this task's classes, plus the module-structure test**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest=PayoutArithmeticTest,ScheduleExpanderTest,BenefitPayoutApiIntegrationTest,ModularityTests
```

If the repo's Modulith verification test has a different name, find it with `grep -rl "ApplicationModules.of" src/test/java` and run that class instead. Expected: all green.

- [ ] **Step 14: Commit**

```bash
git add db-migrations/benefitpayout src/main/java/tz/co/nlolo/lifeplatform/benefitpayout src/test/java scripts
git commit -m "feat(benefitpayout): module, schema, and schedule expansion at issue (step 2, task 2)"
```

---

### Task 3: Falling due, holds, review and approval, maturity and ROP

**Files:**
- Create: `backend/db-migrations/policy/V31__free_look_status.sql` (status CHECK only — added here so `Policy.mature()` and Task 7 share one migration)
- Create: `benefitpayout/application/PayoutDueDrain.java`, `benefitpayout/infrastructure/BenefitPayoutController.java`, `BenefitPayoutExceptionHandler.java`, `ReviewPayoutRequest.java`, `PayoutInstalmentResponse.java`, `backend/api/openapi/openapi-benefitpayout.yaml`
- Modify: `benefitpayout/api/BenefitPayoutApi.java`, `BenefitPayoutApiImpl.java`, `policy/domain/Policy.java:654-657` (`closeableBySettledClaim`), `policy/application/PolicyApiImpl.java:1026-1042` (`expirePolicy`), `policy/api/PolicyStatus.java`
- Test: `benefitpayout/PayoutInstalmentTest.java` (unit), `benefitpayout/PayoutDrainIntegrationTest.java`, `benefitpayout/BenefitPayoutContractTest.java`

**Interfaces:**
- Consumes: `PayoutInstalment.fallDue/review/approve`, `PremiumTally.isPaidUpTo`, `PolicyApi.markMatured(String, String)`.
- Produces: `BenefitPayoutApi.review(UUID instalmentId, String payeeRef, ProofOfLifeMethod method, UUID documentId, String reviewer) -> PayoutInstalmentView`, `BenefitPayoutApi.approve(UUID instalmentId, String approver) -> PayoutInstalmentView`, `BenefitPayoutApi.search(Collection<InstalmentStatus>, Pageable) -> Page<PayoutInstalmentView>`; impl method `fallDue(UUID instalmentId)` used by the drain; event `benefitpayout.PayoutRequested` with payload keys `instalmentId` (String), `idempotencyKey` (String: `<instalmentId>:<attempts>`), `policyNumber`, `payeeRef`, `purpose` (`MATURITY_PAYOUT` | `SURVIVAL_BENEFIT_PAYOUT` | `INCOME_PAYOUT` | `PREMIUM_RETURN_PAYOUT` | `FREE_LOOK_REFUND`), `amount: {amount, currencyCode}`.

- [ ] **Step 1: Write the policy migration and the enum literal**

```sql
-- db-migrations/policy/V31__free_look_status.sql
-- Product step 2: a policy cancelled inside its free-look window (guide §21.3, "cancelled in the
-- free-look period"). Terminal, and never on risk -- cover is void from inception.
ALTER TABLE policy.policy DROP CONSTRAINT IF EXISTS policy_status_check;
ALTER TABLE policy.policy ADD CONSTRAINT policy_status_check
    CHECK (status IN ('PROPOSED','ACTIVE','LAPSED','SUSPENDED','SURRENDERED','MATURED','REINSTATED',
                      'NOT_TAKEN_UP','EXPIRED','PAID_UP','CANCELLED_FREE_LOOK'));
```

Append `, CANCELLED_FREE_LOOK` to `policy/api/PolicyStatus.java`'s enum list. Add V31 after V30 in every test class:

```bash
grep -rl 'policy/V30__surrender.sql' src/test/java | while read f; do
  grep -q 'policy/V31__free_look_status.sql' "$f" || \
  sed -i 's#\( *\)"db-migrations/policy/V30__surrender.sql",#&\n\1"db-migrations/policy/V31__free_look_status.sql",#' "$f"
done
grep -rL 'policy/V31__free_look' $(grep -rl 'policy/V30__surrender.sql' src/test/java)
```

Expected: nothing printed (hand-fix any list that ends on V30 with `);`).

- [ ] **Step 2: Let a paid-up policy mature, and stop expiry racing a scheduled maturity**

In `policy/domain/Policy.java` replace `closeableBySettledClaim()` (line ~654):

```java
    private boolean closeableBySettledClaim() {
        // PAID_UP is in force (wasOnRiskOn treats it so), so it matures and closes on a settled
        // claim like any in-force policy. It was missing, which left a paid-up endowment unable
        // to mature at all (step 2).
        return "ACTIVE".equals(status) || "REINSTATED".equals(status) || "PAID_UP".equals(status)
            || "LAPSED".equals(status) || "SUSPENDED".equals(status);
    }
```

In `PolicyApiImpl.expirePolicy` (line ~1026), directly after the `if (policy.isClosed()) { return; }` block:

```java
        // A version that pays at the end of the term matures through benefitpayout's drain; expiring
        // it here would close cover on a policy that is owed its maturity (step 2). Skipped, not
        // thrown: the hourly selector will offer it again until benefitpayout matures it.
        if (productApi.resolvePayoutPlan(policy.getProductVersionId()).hasEndOfTermRow()) {
            return;
        }
```

In `PolicyApiImpl.issuePolicy`, directly before `Map<String, Object> payload = new LinkedHashMap<>();` (line ~297):

```java
        if (policy.getMaturityDate() == null
                && productApi.resolvePayoutPlan(request.productVersionId()).hasEndOfTermRow()) {
            throw new InvalidPolicyStateException("Product version " + request.productVersionId()
                + " pays at the end of the term, so the policy needs a policy term");
        }
```

- [ ] **Step 3: Write the failing state-machine test**

```java
// src/test/java/tz/co/nlolo/lifeplatform/benefitpayout/PayoutInstalmentTest.java
package tz.co.nlolo.lifeplatform.benefitpayout;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.benefitpayout.api.InstalmentStatus;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;
import tz.co.nlolo.lifeplatform.benefitpayout.api.ProofOfLifeMethod;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.PayoutInstalment;
import tz.co.nlolo.lifeplatform.product.api.PayoutKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutInstalmentTest {

    private static PayoutInstalment survival() {
        return new PayoutInstalment(UUID.randomUUID(), "POL-1", PayoutKind.SURVIVAL, 0, null,
            LocalDate.of(2031, 1, 15), new BigDecimal("100000.00"), "TZS");
    }

    private static PayoutInstalment maturity() {
        return new PayoutInstalment(UUID.randomUUID(), "POL-1", PayoutKind.MATURITY, 1, null,
            LocalDate.of(2046, 1, 15), new BigDecimal("1000000.00"), "TZS");
    }

    @Test
    void premiumsBehindHoldsInsteadOfFallingDue() {
        PayoutInstalment i = survival();
        i.fallDue(false, null);
        assertThat(i.status()).isEqualTo(InstalmentStatus.ON_HOLD);
        assertThat(i.getStatusReason()).isEqualTo("Premiums are not paid up to the due date");
        i.release();
        assertThat(i.status()).isEqualTo(InstalmentStatus.DUE);
    }

    @Test
    void aSurvivalPayoutNeedsProofOfLifeButAMaturityDoesNot() {
        PayoutInstalment s = survival();
        s.fallDue(true, null);
        assertThatThrownBy(() -> s.review("rev", "+255700000001", null, null))
            .isInstanceOf(PayoutStateException.class)
            .hasMessage("A SURVIVAL payout needs proof that the life assured is alive");
        PayoutInstalment m = maturity();
        m.fallDue(true, null);
        m.review("rev", "+255700000001", null, null);
        assertThat(m.status()).isEqualTo(InstalmentStatus.REVIEWED);
    }

    @Test
    void theReviewerCannotApprove() {
        PayoutInstalment i = survival();
        i.fallDue(true, null);
        i.review("rev", "+255700000001", ProofOfLifeMethod.IN_PERSON, null);
        assertThatThrownBy(() -> i.approve("rev"))
            .hasMessage("A payout must be approved by someone other than the person who reviewed it (rev)");
        i.approve("apr");
        assertThat(i.status()).isEqualTo(InstalmentStatus.APPROVED);
        assertThat(i.getAttempts()).isEqualTo(1);
    }

    @Test
    void anApprovedPayoutCannotBeCancelledAndAFailedOneRetries() {
        PayoutInstalment i = maturity();
        i.fallDue(true, null);
        i.review("rev", "+255700000001", null, null);
        i.approve("apr");
        assertThat(i.cancel("lapsed")).isFalse();
        i.markFailed();
        i.retry();
        assertThat(i.status()).isEqualTo(InstalmentStatus.APPROVED);
        assertThat(i.getAttempts()).isEqualTo(2);
    }

    @Test
    void restatementKeepsTheOriginalBesideTheNewFigure() {
        PayoutInstalment i = survival();
        i.restate(new BigDecimal("40000.00"), "Made paid-up");
        assertThat(i.getOriginalAmount()).isEqualByComparingTo("100000.00");
        assertThat(i.getCurrentAmount()).isEqualByComparingTo("40000.00");
        assertThat(i.getRestatementReason()).isEqualTo("Made paid-up");
    }

    @Test
    void reviewNeedsAPayee() {
        PayoutInstalment i = maturity();
        i.fallDue(true, null);
        assertThatThrownBy(() -> i.review("rev", " ", null, null)).hasMessage("A payout needs a payee reference");
    }
}
```

- [ ] **Step 4: Run it**

Run: `./mvnw -o test -Dtest=PayoutInstalmentTest`
Expected: PASS, 6 tests (the entity was written in Task 2; this pins its behaviour before the drain depends on it). If any fails, the entity is wrong — fix `PayoutInstalment`, not the test.

- [ ] **Step 5: Add fall-due, review, approve and search to the API**

Add to `BenefitPayoutApi`:

```java
    PayoutInstalmentView review(UUID instalmentId, String payeeRef, ProofOfLifeMethod method, UUID documentId, String reviewer);

    PayoutInstalmentView approve(UUID instalmentId, String approver);

    /** The Payouts register. Empty {@code statuses} means every status. Ordered due date, then id. */
    Page<PayoutInstalmentView> search(java.util.Collection<InstalmentStatus> statuses, Pageable pageable);
```

In `BenefitPayoutApiImpl`, add `ApplicationEventPublisher eventPublisher` as a constructor parameter and final field, then add:

```java
    private static final Set<String> PAYABLE_POLICY_STATUSES = Set.of("ACTIVE", "REINSTATED", "PAID_UP");

    /** The drain's per-row work: SCHEDULED -> DUE / ON_HOLD / CANCELLED, and maturity. */
    @Transactional
    public void fallDue(UUID instalmentId) {
        PayoutInstalment i = load(instalmentId);
        if (i.status() != InstalmentStatus.SCHEDULED) {
            return; // another drain instance won
        }
        PolicyView policy = policyApi.getPolicy(i.getPolicyNumber());
        String policyStatus = policy.status().name();
        if (!PAYABLE_POLICY_STATUSES.contains(policyStatus) && !"SUSPENDED".equals(policyStatus)) {
            i.cancel("Policy is " + policyStatus + " on the due date");
            instalments.save(i);
            return;
        }
        PremiumTally tally = tallies.findById(i.getPolicyNumber()).orElse(null);
        BigDecimal valued = null;
        if (i.kind() == PayoutKind.RETURN_OF_PREMIUM) {
            BigDecimal pct = productApi.resolvePayoutPlan(policy.productVersionId()).rows().get(i.getRowOrder()).amountValue();
            valued = PayoutArithmetic.percentOf(tally != null ? tally.getPremiumsCollected() : BigDecimal.ZERO, pct);
            if (valued.signum() == 0) {
                i.cancel("No premiums were collected, so there is nothing to return");
                instalments.save(i);
                matureIfEndOfTerm(i);
                return;
            }
        }
        boolean upToDate = tally == null || tally.isPaidUpTo(i.getDueDate());
        i.fallDue(upToDate, valued);
        if (i.status() == InstalmentStatus.DUE && "SUSPENDED".equals(policyStatus)) {
            i.hold("Policy is suspended");
        } else if (i.status() == InstalmentStatus.DUE && !streamAllowsRelease(i)) {
            i.hold("Proof of life is overdue");
        }
        instalments.save(i);
        matureIfEndOfTerm(i);
    }

    private void matureIfEndOfTerm(PayoutInstalment i) {
        if (i.kind() == PayoutKind.MATURITY || i.kind() == PayoutKind.RETURN_OF_PREMIUM) {
            policyApi.markMatured(i.getPolicyNumber(), "system:benefitpayout");
        }
    }

    @Override
    @Transactional
    public PayoutInstalmentView review(UUID instalmentId, String payeeRef, ProofOfLifeMethod method, UUID documentId,
                                       String reviewer) {
        PayoutInstalment i = load(instalmentId);
        i.review(reviewer, payeeRef, method, documentId);
        return Views.of(instalments.save(i));
    }

    @Override
    @Transactional
    public PayoutInstalmentView approve(UUID instalmentId, String approver) {
        PayoutInstalment i = load(instalmentId);
        i.approve(approver);
        instalments.save(i);
        if (i.getStreamId() != null) {
            streams.findById(i.getStreamId()).filter(s -> s.status() == StreamStatus.PENDING_ACTIVATION)
                .ifPresent(s -> { s.activate(LocalDate.now()); streams.save(s); });
        }
        publishPayoutRequested(i);
        return Views.of(i);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PayoutInstalmentView> search(Collection<InstalmentStatus> statuses, Pageable pageable) {
        Collection<InstalmentStatus> wanted = statuses == null || statuses.isEmpty()
            ? EnumSet.allOf(InstalmentStatus.class) : statuses;
        return instalments.findByStatusIn(wanted.stream().map(Enum::name).toList(), pageable).map(Views::of);
    }

    void publishPayoutRequested(PayoutInstalment i) {
        eventPublisher.publishEvent(DomainEventEnvelope.of("benefitpayout.PayoutRequested", TenantContext.get(),
            Map.of("instalmentId", i.getInstalmentId().toString(),
                   // A retry is a new request: payment dedupes on this key and would drop a resend.
                   "idempotencyKey", i.getInstalmentId() + ":" + i.getAttempts(),
                   "policyNumber", i.getPolicyNumber(),
                   "payeeRef", i.getPayeeRef(),
                   "purpose", purposeFor(i.kind()),
                   "amount", Map.of("amount", i.getCurrentAmount().toPlainString(), "currencyCode", i.getCurrency()))));
    }

    static String purposeFor(PayoutKind kind) {
        return switch (kind) {
            case MATURITY -> "MATURITY_PAYOUT";
            case SURVIVAL -> "SURVIVAL_BENEFIT_PAYOUT";
            case INCOME -> "INCOME_PAYOUT";
            case RETURN_OF_PREMIUM -> "PREMIUM_RETURN_PAYOUT";
        };
    }
```

Imports to add: `org.springframework.context.ApplicationEventPublisher`, `org.springframework.data.domain.Page`, `org.springframework.data.domain.Pageable`, `tz.co.nlolo.lifeplatform.DomainEventEnvelope`, `java.util.Collection`, `java.util.EnumSet`, `java.util.Set`.

- [ ] **Step 6: Write the drain**

```java
// benefitpayout/application/PayoutDueDrain.java
package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.benefitpayout.infrastructure.PayoutInstalmentRepository;

import java.util.UUID;

/**
 * SCHEDULED instalments whose date has come, across tenants, each processed under its own tenant
 * -- the CoverExpiryDrain shape. Exactly-once comes from the instalment's @Version and the
 * SCHEDULED guard in fallDue; one failing row never stops the queue.
 */
@Component
public class PayoutDueDrain {

    private static final Logger log = LoggerFactory.getLogger(PayoutDueDrain.class);

    private final PayoutInstalmentRepository instalments;
    private final BenefitPayoutApiImpl api;

    public PayoutDueDrain(PayoutInstalmentRepository instalments, BenefitPayoutApiImpl api) {
        this.instalments = instalments;
        this.api = api;
    }

    @Scheduled(fixedDelayString = "${benefitpayout.due-drain-interval-ms:3600000}",
        initialDelayString = "${benefitpayout.due-drain-interval-ms:3600000}")
    public void drain() {
        for (Object[] row : instalments.findFallingDueAcrossTenants()) {
            fallDueOne((UUID) row[0], (UUID) row[1]);
        }
    }

    void fallDueOne(UUID instalmentId, UUID tenantId) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            api.fallDue(instalmentId);
        } catch (Exception e) {
            log.error("Failed to process payout instalment {} in tenant {}", instalmentId, tenantId, e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
```

Register the drain with the existing scheduled-jobs health check: open `ScheduledJobsHealthIndicator.java` and add `PayoutDueDrain` the same way `CoverExpiryDrain` is listed there (if it lists by bean or by SQL function — follow whichever it does; memory: health goes DOWN if a sweep is missing, so a drain left out of it is invisible).

- [ ] **Step 7: Write the failing drain integration test**

`PayoutDrainIntegrationTest`: same class header as `BenefitPayoutApiIntegrationTest` (copy its annotations, container, datasource source and migration list, now including policy V31), `@Import(PayoutTestFixtures.class)`, autowire `PayoutDueDrain drain`, `BenefitPayoutApi api`, `PolicyApi policyApi`, `PayoutTestFixtures fixtures`, `JdbcTemplate jdbcTemplate`.

```java
    private static final UUID TENANT = UUID.randomUUID();
    private static final PayoutPlan MONEY_BACK = PayoutPlan.authored(new PayoutTerms(15, null, false, null), List.of(
        new PayoutRowInput(PayoutKind.SURVIVAL, 5, 5, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("10"), PayoutFrequency.ANNUAL),
        new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null)));

    /** Commenced 5 years and a day ago, so year 5's survival benefit fell due yesterday. */
    private String moneyBackDueYesterday() {
        return fixtures.issueEndowment(TENANT, MONEY_BACK, new BigDecimal("1000000.00"), 240,
            LocalDate.now().minusYears(5).minusDays(1));
    }

    private PayoutInstalmentView survival(String policyNumber) {
        TenantContext.set(TENANT);
        try {
            return api.listForPolicy(policyNumber).stream().filter(v -> v.kind() == PayoutKind.SURVIVAL).findFirst().orElseThrow();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void anUnpaidPolicyHoldsAndAPaymentReleasesIt() {
        String policyNumber = moneyBackDueYesterday();
        // The tally reckons premium due dates from the ISSUE date, which the API sets to today even
        // when cover commenced five years ago -- so no premium would be due before the payout and the
        // hold would never trigger (a vacuous pass). Backdate the tally's issue date so five years of
        // monthly premiums fell due, none of them paid. Owner connection, so RLS does not apply.
        jdbcTemplate.update("UPDATE benefitpayout.premium_tally SET issue_date = current_date - 1 - interval '5 years' "
            + "WHERE policy_number = ?", policyNumber);
        drain.drain();
        assertThat(survival(policyNumber).status()).isEqualTo(InstalmentStatus.ON_HOLD);

        fixtures.collectPremium(TENANT, policyNumber, new BigDecimal("3000000.00"), LocalDate.now().minusDays(1));
        assertThat(survival(policyNumber).status()).isEqualTo(InstalmentStatus.DUE);
    }

    @Test
    void reviewThenApproveByADifferentPersonRequestsThePayout() {
        String policyNumber = moneyBackDueYesterday();
        fixtures.collectPremium(TENANT, policyNumber, new BigDecimal("3000000.00"), LocalDate.now().minusDays(1));
        drain.drain();
        UUID id = survival(policyNumber).instalmentId();

        TenantContext.set(TENANT);
        try {
            api.review(id, "+255700000009", ProofOfLifeMethod.PHONE_OR_VIDEO, null, "reviewer-1");
            assertThatThrownBy(() -> api.approve(id, "reviewer-1")).isInstanceOf(PayoutStateException.class);
            PayoutInstalmentView approved = api.approve(id, "approver-2");
            assertThat(approved.status()).isEqualTo(InstalmentStatus.APPROVED);
            assertThat(approved.approvedBy()).isEqualTo("approver-2");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aMaturityDateArrivingMaturesThePolicyInsteadOfExpiringIt() {
        // Twelve-month term commenced a year and a day ago: the maturity date was yesterday.
        PayoutPlan endowment = PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of(
            new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null)));
        String policyNumber = fixtures.issueEndowment(TENANT, endowment, new BigDecimal("500000.00"), 12,
            LocalDate.now().minusYears(1).minusDays(1));
        TenantContext.set(TENANT);
        try {
            policyApi.expirePolicy(policyNumber);   // the expiry drain must leave it alone
            assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.ACTIVE);
        } finally {
            TenantContext.clear();
        }
        drain.drain();
        TenantContext.set(TENANT);
        try {
            assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.MATURED);
        } finally {
            TenantContext.clear();
        }
    }
```

- [ ] **Step 8: Run it**

Run: `./mvnw -o test -Dtest=PayoutDrainIntegrationTest,PayoutInstalmentTest`
Expected: PASS. A `MATURED` assertion failing with `ACTIVE` means `markMatured` was not reached — check `matureIfEndOfTerm`.

- [ ] **Step 9: Write the REST layer**

```java
// benefitpayout/infrastructure/ReviewPayoutRequest.java
package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tz.co.nlolo.lifeplatform.benefitpayout.api.ProofOfLifeMethod;

import java.util.UUID;

public record ReviewPayoutRequest(@NotBlank @Size(max = 200) String payeeRef, ProofOfLifeMethod proofOfLifeMethod,
                                  UUID proofOfLifeDocumentId) {}
```

```java
// benefitpayout/infrastructure/PayoutInstalmentResponse.java
package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.data.domain.Page;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutInstalmentView;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wire shape. Money as {amount, currencyCode} decimal strings; absent amounts are null. */
public record PayoutInstalmentResponse(UUID instalmentId, String policyNumber, String kind, String dueDate,
                                       Map<String, String> originalAmount, Map<String, String> currentAmount,
                                       String restatementReason, String status, String statusReason, UUID streamId,
                                       String payeeRef, String proofOfLifeMethod, String reviewedBy, String approvedBy,
                                       UUID paymentRunId, int attempts) {

    public static PayoutInstalmentResponse from(PayoutInstalmentView v) {
        return new PayoutInstalmentResponse(v.instalmentId(), v.policyNumber(), v.kind().name(), v.dueDate().toString(),
            money(v.originalAmount(), v.currency()), money(v.currentAmount(), v.currency()), v.restatementReason(),
            v.status().name(), v.statusReason(), v.streamId(), v.payeeRef(),
            v.proofOfLifeMethod() != null ? v.proofOfLifeMethod().name() : null,
            v.reviewedBy(), v.approvedBy(), v.paymentRunId(), v.attempts());
    }

    static Map<String, String> money(java.math.BigDecimal amount, String currency) {
        return amount == null ? null : Map.of("amount", amount.toPlainString(), "currencyCode", currency);
    }

    public record PageMetaDto(int page, int pageSize, int totalElements) {}

    public record PageResponse(List<PayoutInstalmentResponse> items, PageMetaDto page) {
        public static PageResponse from(Page<PayoutInstalmentView> p) {
            return new PageResponse(p.getContent().stream().map(PayoutInstalmentResponse::from).toList(),
                new PageMetaDto(p.getNumber(), p.getSize(), (int) p.getTotalElements()));
        }
    }
}
```

```java
// benefitpayout/infrastructure/BenefitPayoutController.java
package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi;
import tz.co.nlolo.lifeplatform.benefitpayout.api.InstalmentStatus;

import java.util.List;
import java.util.UUID;

@RestController
public class BenefitPayoutController {

    static final String FINANCE = "hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))";

    private final BenefitPayoutApi api;

    public BenefitPayoutController(BenefitPayoutApi api) { this.api = api; }

    @GetMapping("/policies/{policyNumber}/payouts")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<PayoutInstalmentResponse> listForPolicy(@PathVariable String policyNumber) {
        return api.listForPolicy(policyNumber).stream().map(PayoutInstalmentResponse::from).toList();
    }

    @GetMapping("/payouts/{instalmentId}")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public PayoutInstalmentResponse get(@PathVariable UUID instalmentId) {
        return PayoutInstalmentResponse.from(api.getInstalment(instalmentId));
    }

    /** The Payouts register. Ordered due date then id, so two pages never repeat or skip a row. */
    @GetMapping("/payouts")
    @PreAuthorize(FINANCE)
    public PayoutInstalmentResponse.PageResponse search(@RequestParam(required = false) List<InstalmentStatus> status,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int pageSize) {
        return PayoutInstalmentResponse.PageResponse.from(api.search(status,
            PageRequest.of(page, Math.min(pageSize, 100), Sort.by("dueDate", "instalmentId"))));
    }

    @PostMapping("/payouts/{instalmentId}/review")
    @PreAuthorize(FINANCE)
    public PayoutInstalmentResponse review(@PathVariable UUID instalmentId, @Valid @RequestBody ReviewPayoutRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        return PayoutInstalmentResponse.from(api.review(instalmentId, request.payeeRef(), request.proofOfLifeMethod(),
            request.proofOfLifeDocumentId(), jwt.getSubject()));
    }

    @PostMapping("/payouts/{instalmentId}/approve")
    @PreAuthorize(FINANCE)
    public ResponseEntity<PayoutInstalmentResponse> approve(@PathVariable UUID instalmentId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.accepted().body(PayoutInstalmentResponse.from(api.approve(instalmentId, jwt.getSubject())));
    }
}
```

```java
// benefitpayout/infrastructure/BenefitPayoutExceptionHandler.java
package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutNotFoundException;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BenefitPayoutExceptionHandler {

    @ExceptionHandler(PayoutStateException.class)
    public ProblemDetail refused(PayoutStateException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "PAYOUT_REFUSED");
    }

    @ExceptionHandler(PayoutNotFoundException.class)
    public ProblemDetail notFound(PayoutNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "PAYOUT_NOT_FOUND");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String code) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code);
        return pd;
    }
}
```

Before committing, open `party/infrastructure/PartyExceptionHandler.java` and compare its private `problem(...)` helper: if it sets more properties (a `traceId`, a `type` URI), copy its body exactly so every module's ProblemDetail has one shape.

The approve endpoint answers `202 Accepted` with the instalment: the money is requested, not yet paid.

- [ ] **Step 10: Write openapi-benefitpayout.yaml**

```yaml
openapi: 3.1.0
info:
  title: Benefit Payouts API
  version: 1.0.0
  description: Scheduled maturity, survival, income and premium-return payouts, payment runs and free-look (product step 2).
servers:
  - url: /
security:
  - staffAuth: []
paths:
  /policies/{policyNumber}/payouts:
    get:
      summary: Every payout instalment on one policy, in due-date order
      parameters:
        - name: policyNumber
          in: path
          required: true
          schema: { $ref: 'openapi-common.yaml#/components/schemas/PolicyNumberRef' }
      responses:
        '200':
          description: The policy's schedule (empty when its version has none)
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/PayoutInstalment' } }
        '403': { $ref: 'openapi-common.yaml#/components/responses/Forbidden' }
  /payouts:
    get:
      summary: The Payouts register
      parameters:
        - name: status
          in: query
          required: false
          schema: { type: array, items: { $ref: '#/components/schemas/InstalmentStatus' } }
          style: form
          explode: true
        - { name: page, in: query, required: false, schema: { type: integer, minimum: 0 } }
        - { name: pageSize, in: query, required: false, schema: { type: integer, minimum: 1, maximum: 100 } }
      responses:
        '200':
          description: One page
          content:
            application/json:
              schema:
                type: object
                required: [items, page]
                properties:
                  items: { type: array, items: { $ref: '#/components/schemas/PayoutInstalment' } }
                  page: { $ref: 'openapi-common.yaml#/components/schemas/PageMeta' }
        '403': { $ref: 'openapi-common.yaml#/components/responses/Forbidden' }
  /payouts/{instalmentId}:
    get:
      summary: One payout instalment
      parameters:
        - { name: instalmentId, in: path, required: true, schema: { type: string, format: uuid } }
      responses:
        '200':
          description: The instalment
          content:
            application/json:
              schema: { $ref: '#/components/schemas/PayoutInstalment' }
        '404':
          description: No such instalment in this tenant
          content:
            application/problem+json:
              schema: { $ref: 'openapi-common.yaml#/components/schemas/ProblemDetails' }
  /payouts/{instalmentId}/review:
    post:
      summary: Review a DUE payout — payee, and proof of life where it is required
      parameters:
        - { name: instalmentId, in: path, required: true, schema: { type: string, format: uuid } }
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [payeeRef]
              properties:
                payeeRef: { type: string, maxLength: 200 }
                proofOfLifeMethod: { oneOf: [ { $ref: '#/components/schemas/ProofOfLifeMethod' }, { type: 'null' } ] }
                proofOfLifeDocumentId: { type: [string, "null"], format: uuid }
      responses:
        '200':
          description: Reviewed
          content:
            application/json:
              schema: { $ref: '#/components/schemas/PayoutInstalment' }
        '422':
          description: Refused (wrong state, no payee, no proof of life)
          content:
            application/problem+json:
              schema: { $ref: 'openapi-common.yaml#/components/schemas/ProblemDetails' }
  /payouts/{instalmentId}/approve:
    post:
      summary: Approve a REVIEWED payout, by someone other than its reviewer; requests the disbursement
      parameters:
        - { name: instalmentId, in: path, required: true, schema: { type: string, format: uuid } }
      responses:
        '202':
          description: Approved and the payment requested — not yet paid
          content:
            application/json:
              schema: { $ref: '#/components/schemas/PayoutInstalment' }
        '422':
          description: Refused (wrong state, approver is the reviewer)
          content:
            application/problem+json:
              schema: { $ref: 'openapi-common.yaml#/components/schemas/ProblemDetails' }
components:
  securitySchemes:
    staffAuth: { $ref: 'openapi-common.yaml#/components/securitySchemes/staffAuth' }
  schemas:
    InstalmentStatus:
      type: string
      enum: [SCHEDULED, DUE, ON_HOLD, REVIEWED, APPROVED, PAID, FAILED, IN_DOUBT, CANCELLED]
    ProofOfLifeMethod:
      type: string
      enum: [IN_PERSON, PHONE_OR_VIDEO, LIFE_CERTIFICATE]
    PayoutInstalment:
      type: object
      required: [instalmentId, policyNumber, kind, dueDate, status, attempts]
      properties:
        instalmentId: { type: string, format: uuid }
        policyNumber: { type: string }
        kind: { type: string, enum: [SURVIVAL, MATURITY, INCOME, RETURN_OF_PREMIUM] }
        dueDate: { type: string, format: date }
        originalAmount: { oneOf: [ { $ref: 'openapi-common.yaml#/components/schemas/Money' }, { type: 'null' } ] }
        currentAmount: { oneOf: [ { $ref: 'openapi-common.yaml#/components/schemas/Money' }, { type: 'null' } ] }
        restatementReason: { type: [string, "null"] }
        status: { $ref: '#/components/schemas/InstalmentStatus' }
        statusReason: { type: [string, "null"] }
        streamId: { type: [string, "null"], format: uuid }
        payeeRef: { type: [string, "null"] }
        proofOfLifeMethod: { oneOf: [ { $ref: '#/components/schemas/ProofOfLifeMethod' }, { type: 'null' } ] }
        reviewedBy: { type: [string, "null"] }
        approvedBy: { type: [string, "null"] }
        paymentRunId: { type: [string, "null"], format: uuid }
        attempts: { type: integer }
```

Check two `$ref`s exist before relying on them — `grep -n "staffAuth:\|PolicyNumberRef:\|Money:\|ProblemDetails:" api/openapi/openapi-common.yaml`. If `staffAuth` is declared per-spec rather than in common, copy the `securitySchemes` block from `openapi-policyloan.yaml` instead. (Memory: one bad `$ref` fails a whole spec load, and the error names the spec, not the endpoint.)

- [ ] **Step 11: Write the contract test**

`BenefitPayoutContractTest`: `@Testcontainers @AutoConfigureMockMvc @SpringBootTest(classes = Application.class, webEnvironment = MOCK)`, `@Import(PayoutTestFixtures.class)`, the same migration list as `PayoutDrainIntegrationTest`, `SPEC_PATH = "api/openapi/openapi-benefitpayout.yaml"`, and the same `TENANT` and `MONEY_BACK` constants as `PayoutDrainIntegrationTest` (copy both declarations); autowire `MockMvc mockMvc`, `PayoutTestFixtures fixtures`, `PayoutDueDrain drain`. Tokens are built with `jwt()` exactly as `PolicyLoanContractTest` builds them (copy its `staff(...)` / role helper if it has one), with `tenant_id` claim = `TENANT`.

```java
    @Test
    void theScheduleMatchesTheContract() throws Exception {
        String policyNumber = fixtures.issueEndowment(TENANT, MONEY_BACK, new BigDecimal("1000000.00"), 240);
        mockMvc.perform(get("/policies/{n}/payouts", policyNumber).with(staff("FINANCE_OFFICER", "fin-1")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$[0].kind").value("SURVIVAL"))
            .andExpect(jsonPath("$[0].currentAmount.amount").value("100000.00"));
    }

    @Test
    void reviewAndApproveMatchTheContractAndTheReviewerIsRefusedAsApprover() throws Exception {
        String policyNumber = fixtures.issueEndowment(TENANT, MONEY_BACK, new BigDecimal("1000000.00"), 240,
            LocalDate.now().minusYears(5).minusDays(1));
        fixtures.collectPremium(TENANT, policyNumber, new BigDecimal("3000000.00"), LocalDate.now().minusDays(1));
        drain.drain();
        String id = JsonPath.read(mockMvc.perform(get("/policies/{n}/payouts", policyNumber)
                .with(staff("FINANCE_OFFICER", "fin-1"))).andReturn().getResponse().getContentAsString(), "$[0].instalmentId");

        mockMvc.perform(post("/payouts/{id}/review", id).with(staff("FINANCE_OFFICER", "fin-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payeeRef\":\"+255700000009\",\"proofOfLifeMethod\":\"IN_PERSON\"}"))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
        mockMvc.perform(post("/payouts/{id}/approve", id).with(staff("FINANCE_OFFICER", "fin-1")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("A payout must be approved by someone other than the person who reviewed it (fin-1)"));
        mockMvc.perform(post("/payouts/{id}/approve", id).with(staff("ADMIN", "admin-2")))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void aCustomerServiceRepCannotReviewAPayout() throws Exception {
        mockMvc.perform(post("/payouts/{id}/review", UUID.randomUUID()).with(staff("CUSTOMER_SERVICE_REP", "csr-1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"payeeRef\":\"+255700000009\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void theRegisterIsPagedAndFiltersByStatus() throws Exception {
        fixtures.issueEndowment(TENANT, MONEY_BACK, new BigDecimal("1000000.00"), 240);
        mockMvc.perform(get("/payouts").param("status", "SCHEDULED").param("pageSize", "1")
                .with(staff("FINANCE_OFFICER", "fin-1")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.page.pageSize").value(1));
    }
```

`staff(role, subject)` returns `jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_" + role)).jwt(b -> b.subject(subject).claim("tenant_id", TENANT.toString()))` — the shape `UnderwritingContractTest.registerApplicantAs` uses.

Note the 403 test's body is a VALID review body — memory: a 403 gate test with an invented body proves nothing, because a 400 from validation can mask a missing gate.

- [ ] **Step 12: Run this task's classes**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest=PayoutInstalmentTest,PayoutDrainIntegrationTest,BenefitPayoutContractTest,PolicyApiIntegrationTest
```

Expected: all green. `PolicyApiIntegrationTest` is in the list because Step 2 changed `expirePolicy`, `issuePolicy` and `Policy.closeableBySettledClaim`.

- [ ] **Step 13: Commit**

```bash
git add db-migrations/policy/V31__free_look_status.sql api/openapi/openapi-benefitpayout.yaml src/main/java src/test/java
git commit -m "feat(benefitpayout): instalments fall due, hold on arrears, two-person review/approve; scheduled maturity (step 2, task 3)"
```

---

### Task 4: Paying out — payment, outcomes, retry, general ledger

**Files:**
- Create: `backend/db-migrations/payment/V8__benefit_payout_purposes.sql`, `benefitpayout/application/PayoutPaymentListener.java`, `finaccounting/application/BenefitPayoutEventListener.java`
- Modify: `payment/application/PaymentRequestListener.java` (switch at line ~113, new handler), `finaccounting/domain/PostingRule.java` (RULES map), `BenefitPayoutApi.java`, `BenefitPayoutApiImpl.java`, `BenefitPayoutController.java`, `openapi-benefitpayout.yaml`, every test class with `payment/V7__q4_2026_partitions.sql` (add V8)
- Test: `benefitpayout/PayoutPaymentEndToEndTest.java`

**Interfaces:**
- Consumes: `benefitpayout.PayoutRequested` (Task 3 keys); `payment.DisbursementCompleted` / `payment.DisbursementFailed` payload keys `purpose`, `sourceRef`, `disbursementId` (as `SurrenderPaymentListener` reads them).
- Produces: `BenefitPayoutApi.retry(UUID instalmentId) -> PayoutInstalmentView`; impl `markPaid(UUID instalmentId, UUID disbursementId)`, `markFailed(UUID instalmentId)`; event `benefitpayout.PayoutPaid` keys `instalmentId` (String), `policyNumber`, `kind`, `paidAmount: {amount, currencyCode}`.

- [ ] **Step 1: Write the payment migration**

```bash
sed -n 150,160p db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql
```

Read the exact constraint name and full purpose list that V2 sets on `payment.disbursement_instruction.purpose`, then write V8 to drop and re-add it with four more values:

```sql
-- db-migrations/payment/V8__benefit_payout_purposes.sql
-- Product step 2: the payout engine's disbursement purposes. MATURITY_PAYOUT already existed and
-- had no publisher until now.
ALTER TABLE payment.disbursement_instruction DROP CONSTRAINT <name read from V2>;
ALTER TABLE payment.disbursement_instruction ADD CONSTRAINT <same name>
    CHECK (purpose IN (<every value V2 lists, unchanged>,
                       'SURVIVAL_BENEFIT_PAYOUT','INCOME_PAYOUT','PREMIUM_RETURN_PAYOUT','FREE_LOOK_REFUND'));
```

`disbursement_instruction` is partitioned (payment V7); a CHECK on the parent propagates to the partitions, which is why V2's own CHECK sits on the parent. Then:

```bash
grep -rl 'payment/V7__q4_2026_partitions.sql' src/test/java | while read f; do
  grep -q 'payment/V8__benefit_payout_purposes.sql' "$f" || \
  sed -i 's#\( *\)"db-migrations/payment/V7__q4_2026_partitions.sql",#&\n\1"db-migrations/payment/V8__benefit_payout_purposes.sql",#' "$f"
done
grep -rL 'payment/V8__benefit' $(grep -rl 'payment/V7__q4_2026_partitions.sql' src/test/java)
```

Expected: nothing printed (hand-fix `);`-terminated lists).

- [ ] **Step 2: Teach payment the new request**

In `PaymentRequestListener.onDomainEvent`'s switch add:

```java
            case "benefitpayout.PayoutRequested" -> withTenant(envelope, this::handleBenefitPayout);
```

and the handler, beside `handleSurrenderPayout`:

```java
    /** A scheduled benefit payout or a free-look refund (product step 2). The publisher names the
     *  purpose; the instalment or cancellation id is the source reference. */
    private void handleBenefitPayout(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String idempotencyKey = requireKey(payload);
        String sourceRef = payload.get("instalmentId") != null
            ? (String) payload.get("instalmentId") : (String) payload.get("cancellationId");
        String purpose = (String) payload.get("purpose");
        String payeeRef = (String) payload.get("payeeRef");
        Money money = money(payload);
        Optional<UUID> disbursementId = requiresNewTransactionTemplate.execute(status -> paymentApiImpl.recordDisbursementRequest(
            tenantId, idempotencyKey, payeeRef, money.amount(), money.currency(), purpose, sourceRef));
        disbursementId.ifPresentOrElse(
            id -> submitDisbursement(tenantId, id, payeeRef, money),
            () -> log.info("Dropping duplicate PayoutRequested for tenant {} key {}", tenantId, idempotencyKey));
    }
```

- [ ] **Step 3: Close the loop in benefitpayout**

```java
// benefitpayout/application/PayoutPaymentListener.java
package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Closes an instalment when its disbursement returns -- SurrenderPaymentListener's shape. An
 * IN_DOUBT disbursement publishes neither event, so the instalment stays APPROVED for
 * reconciliation: a blind retry could pay twice.
 */
@Component
public class PayoutPaymentListener {

    static final Set<String> INSTALMENT_PURPOSES =
        Set.of("MATURITY_PAYOUT", "SURVIVAL_BENEFIT_PAYOUT", "INCOME_PAYOUT", "PREMIUM_RETURN_PAYOUT");

    private final BenefitPayoutApiImpl api;
    private final PolicyEventListener tenantRunner;

    public PayoutPaymentListener(BenefitPayoutApiImpl api, PolicyEventListener tenantRunner) {
        this.api = api;
        this.tenantRunner = tenantRunner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        String type = envelope.eventType();
        boolean completed = "payment.DisbursementCompleted".equals(type);
        if (!completed && !"payment.DisbursementFailed".equals(type)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.payload();
        String purpose = (String) payload.get("purpose");
        boolean instalment = INSTALMENT_PURPOSES.contains(purpose);
        if (!instalment && !"FREE_LOOK_REFUND".equals(purpose)) {
            return;
        }
        UUID sourceId = UUID.fromString((String) payload.get("sourceRef"));
        tenantRunner.withTenant(envelope, p -> {
            Object idObj = p.get("disbursementId");
            UUID disbursementId = idObj == null ? null : idObj instanceof UUID u ? u : UUID.fromString((String) idObj);
            if (instalment) {
                if (completed) api.markPaid(sourceId, disbursementId); else api.markFailed(sourceId);
            } else {
                if (completed) api.markFreeLookRefunded(sourceId, disbursementId); else api.markFreeLookRefundFailed(sourceId);
            }
        });
    }
}
```

`markFreeLookRefunded` / `markFreeLookRefundFailed` are written in Task 7; until then add them to the impl as methods that throw `UnsupportedOperationException("free-look arrives in task 7")` so this compiles, and Task 7 replaces their bodies.

Add to `BenefitPayoutApi`:

```java
    /** FAILED -> APPROVED and a fresh payment request (a new idempotency key: attempts went up). */
    PayoutInstalmentView retry(UUID instalmentId);
```

Add to `BenefitPayoutApiImpl`:

```java
    @Transactional
    public void markPaid(UUID instalmentId, UUID disbursementId) {
        PayoutInstalment i = load(instalmentId);
        if (i.status() != InstalmentStatus.APPROVED) {
            return; // redelivery
        }
        i.markPaid(disbursementId);
        instalments.save(i);
        eventPublisher.publishEvent(DomainEventEnvelope.of("benefitpayout.PayoutPaid", TenantContext.get(),
            Map.of("instalmentId", instalmentId.toString(), "policyNumber", i.getPolicyNumber(), "kind", i.kind().name(),
                   "paidAmount", Map.of("amount", i.getCurrentAmount().toPlainString(), "currencyCode", i.getCurrency()))));
    }

    @Transactional
    public void markFailed(UUID instalmentId) {
        PayoutInstalment i = load(instalmentId);
        if (i.status() == InstalmentStatus.APPROVED) {
            i.markFailed();
            instalments.save(i);
        }
    }

    @Override
    @Transactional
    public PayoutInstalmentView retry(UUID instalmentId) {
        PayoutInstalment i = load(instalmentId);
        i.retry();
        instalments.save(i);
        publishPayoutRequested(i);
        return Views.of(i);
    }
```

Add to the controller:

```java
    @PostMapping("/payouts/{instalmentId}/retry")
    @PreAuthorize(FINANCE)
    public ResponseEntity<PayoutInstalmentResponse> retry(@PathVariable UUID instalmentId) {
        return ResponseEntity.accepted().body(PayoutInstalmentResponse.from(api.retry(instalmentId)));
    }
```

and to the spec a `/payouts/{instalmentId}/retry` POST shaped exactly like `/approve` (202 PayoutInstalment, 422 ProblemDetails), summary "Retry a FAILED payout with a fresh payment request".

- [ ] **Step 4: Post a paid payout to the ledger**

In `PostingRule.RULES` add (the comment states the placeholder status every rule in this file carries):

```java
        // Benefits paid while the life assured lives -- maturity, survival, income, premium return.
        // Posted against Claims Expense because the chart has no benefits-paid account; adding one
        // would reopen the V5 chart remap. A placeholder pending FINANCE sign-off, like every rule here.
        Map.entry("benefitpayout.PayoutPaid",        new AccountPair(CLAIMS_EXPENSE, CASH)),
```

```java
// finaccounting/application/BenefitPayoutEventListener.java
```

Write it as a copy of `finaccounting/application/ClaimsEventListener.java` with these exact differences, keeping its constructor, metrics counter, `withTenant` and logging unchanged: class name `BenefitPayoutEventListener`; the switch handles only `case "benefitpayout.PayoutPaid" -> withTenant(envelope, this::handlePayoutPaid);`; and the handler is:

```java
    private void handlePayoutPaid(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String instalmentId = (String) payload.get("instalmentId");
        String policyNumber = (String) payload.get("policyNumber");
        @SuppressWarnings("unchecked")
        Map<String, Object> paid = (Map<String, Object>) payload.get("paidAmount");
        BigDecimal amount = new BigDecimal((String) paid.get("amount"));
        String currency = (String) paid.get("currencyCode");

        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:benefitpayout.PayoutPaid");
        Optional<JournalEntry> maybeEntry = GlPostingCalculator.calculate(tenantId, "benefitpayout.PayoutPaid",
            instalmentId, policyNumber, amount, currency, YearMonth.now().toString(), "system:benefitpayout.PayoutPaid");
        if (maybeEntry.isEmpty()) {
            log.info("benefitpayout.PayoutPaid for instalment {} produced no journal entry", instalmentId);
            return;
        }
        finaccountingApiImpl.postEntry(maybeEntry.get());
    }
```

- [ ] **Step 5: Write the failing end-to-end test**

`PayoutPaymentEndToEndTest` follows `LoanDisbursementEndToEndTest` exactly: its WireMock gateway (`mobile-money.base-url`), its migration list plus the benefitpayout/V1, policy/V31, payment/V8 and finaccounting migrations (copy the finaccounting lines from any class that applies `finaccounting/V7__q4_2026_partitions.sql`). Stub the gateway as that class stubs a success and a failure (copy its `stubFor(...)` calls). Then:

```java
    @Test
    void anApprovedMaturityIsPaidAndPostedToTheLedger() {
        String policyNumber = maturityDueYesterday();          // 12-month endowment commenced a year and a day ago
        drain.drain();
        UUID id = onlyInstalment(policyNumber).instalmentId();
        stubGatewaySuccess();
        TenantContext.set(TENANT);
        try {
            api.review(id, "+255700000009", null, null, "rev-1");
            api.approve(id, "apr-2");                            // the whole AFTER_COMMIT chain completes before this returns
            assertThat(api.getInstalment(id).status()).isEqualTo(InstalmentStatus.PAID);
            assertThat(glPostingRepository.findAll()).anyMatch(p -> id.toString().equals(p.getSourceRef()));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aFailedPayoutRetriesWithAFreshRequest() {
        String policyNumber = maturityDueYesterday();
        drain.drain();
        UUID id = onlyInstalment(policyNumber).instalmentId();
        stubGatewayFailure();
        TenantContext.set(TENANT);
        try {
            api.review(id, "+255700000009", null, null, "rev-1");
            api.approve(id, "apr-2");
            assertThat(api.getInstalment(id).status()).isEqualTo(InstalmentStatus.FAILED);
            stubGatewaySuccess();
            assertThat(api.retry(id).attempts()).isEqualTo(2);
            assertThat(api.getInstalment(id).status()).isEqualTo(InstalmentStatus.PAID);
        } finally {
            TenantContext.clear();
        }
    }
```

`maturityDueYesterday()` issues via `fixtures.issueEndowment(TENANT, <one MATURITY row at 100% SA>, 500000.00, 12, LocalDate.now().minusYears(1).minusDays(1))` and then `fixtures.collectPremium(TENANT, n, new BigDecimal("600000.00"), LocalDate.now().minusDays(1))` so it is not held. `glPostingRepository` is finaccounting's posting repository; if its source-reference getter is named differently, open `finaccounting/domain/GlPosting.java` and use that getter.

- [ ] **Step 6: Run it, then the neighbours this task touched**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest=PayoutPaymentEndToEndTest,PaymentRequestListenerIntegrationTest,ClaimSettlementEndToEndTest,BenefitPayoutContractTest
```

Expected: all green. (`PaymentRequestListenerIntegrationTest` and `ClaimSettlementEndToEndTest` exist and own the payment switch and the posting path; if either name has drifted, `grep -rl "PaymentRequestListener\|claims.ClaimSettled" src/test/java`.)

- [ ] **Step 7: Commit**

```bash
git add db-migrations/payment/V8__benefit_payout_purposes.sql api/openapi src/main/java src/test/java
git commit -m "feat(benefitpayout): disburse approved payouts, settle outcomes, retry, post to the GL (step 2, task 4)"
```

---

### Task 5: Lifecycle reactions and the death valuation

**Files:**
- Create: `benefitpayout/application/ClaimEventListener.java`
- Modify: `benefitpayout/application/PolicyEventListener.java`, `BenefitPayoutApi.java`, `BenefitPayoutApiImpl.java`, `claims/package-info.java`, `claims/application/ClaimsApiImpl.java` (MATURITY branch at line ~159; approval at ~545)
- Test: `benefitpayout/PayoutLifecycleIntegrationTest.java`; extend `claims/ClaimsApiIntegrationTest` (or the claims class that tests approval — find it with `grep -rl "approveClaim\|decideSettlement" src/test/java/tz/co/nlolo/lifeplatform/claims`)

**Interfaces:**
- Consumes: `policy.PolicyLapsed {policyNumber, lapsedAt}`, `policy.PolicyReinstated {policyNumber}`, `policy.PolicyMadePaidUp {policyNumber, ...}`, `policy.PolicySurrendered {policyNumber}`, `claims.ClaimApproved {claimId, policyNumber, claimType, dateOfEvent, ...}`.
- Produces: `BenefitPayoutApi.deathBenefitCeiling(String policyNumber, BigDecimal sumAssuredCeiling) -> BigDecimal`; impl methods `cancelFuture(String policyNumber, LocalDate after, String reason)`, `restoreAfterReinstatement(String policyNumber, LocalDate reinstatedOn)`, `restateForPaidUp(String policyNumber, BigDecimal paidUpSa, BigDecimal originalSa)`.

- [ ] **Step 1: Check the PaidUp payload**

```bash
grep -n '"policy.PolicyMadePaidUp"' -A8 src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java
```

If the payload does not carry both the original and the paid-up sum assured, add them at that publish site as `"originalSumAssured"` and `"paidUpSumAssured"`, each `{amount, currencyCode}` (the aggregate holds both just before `makePaidUp` overwrites the amount — read the original into a local first). This is the only policy change in this task.

- [ ] **Step 2: Write the failing lifecycle test**

`PayoutLifecycleIntegrationTest` (header as `PayoutDrainIntegrationTest`):

```java
    private static final PayoutPlan MONEY_BACK = PayoutPlan.authored(new PayoutTerms(15, null, true, new BigDecimal("105")), List.of(
        new PayoutRowInput(PayoutKind.SURVIVAL, 5, 15, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("10"), PayoutFrequency.ANNUAL),
        new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null)));

    @Test
    void lapseCancelsTheFutureAndReinstatementRestoresOnlyWhatIsStillAhead() {
        String n = fixtures.issueEndowment(TENANT, MONEY_BACK, new BigDecimal("1000000.00"), 240);
        fixtures.publish(TENANT, "policy.PolicyLapsed", Map.of("policyNumber", n, "lapsedAt", Instant.now().toString()));
        assertThat(statuses(n)).containsOnly(InstalmentStatus.CANCELLED);

        fixtures.publish(TENANT, "policy.PolicyReinstated", Map.of("policyNumber", n, "reinstatedAt", Instant.now().toString()));
        assertThat(statuses(n)).containsOnly(InstalmentStatus.SCHEDULED);   // every date is still ahead
    }

    @Test
    void paidUpRestatesFutureInstalmentsProportionately() {
        String n = fixtures.issueEndowment(TENANT, MONEY_BACK, new BigDecimal("1000000.00"), 240);
        fixtures.publish(TENANT, "policy.PolicyMadePaidUp", Map.of("policyNumber", n,
            "originalSumAssured", Map.of("amount", "1000000.00", "currencyCode", "TZS"),
            "paidUpSumAssured", Map.of("amount", "400000.00", "currencyCode", "TZS")));
        PayoutInstalmentView first = schedule(n).get(0);
        assertThat(first.originalAmount()).isEqualByComparingTo("100000.00");
        assertThat(first.currentAmount()).isEqualByComparingTo("40000.00");
        assertThat(first.restatementReason()).isEqualTo("Made paid-up: 400000.00 of 1000000.00 sum assured");
    }

    @Test
    void surrenderCancelsEverything() {
        String n = fixtures.issueEndowment(TENANT, MONEY_BACK, new BigDecimal("1000000.00"), 240);
        fixtures.publish(TENANT, "policy.PolicySurrendered", Map.of("policyNumber", n, "surrenderedAt", Instant.now().toString()));
        assertThat(statuses(n)).containsOnly(InstalmentStatus.CANCELLED);
    }

    @Test
    void anApprovedDeathCancelsLaterInstalmentsAndTheCeilingFollowsTheProduct() {
        String n = fixtures.issueEndowment(TENANT, MONEY_BACK, new BigDecimal("1000000.00"), 240);
        fixtures.publish(TENANT, "claims.ClaimApproved", Map.of("claimId", UUID.randomUUID(), "policyNumber", n,
            "claimType", "DEATH", "dateOfEvent", LocalDate.now().toString(),
            "approvedAmount", Map.of("amount", "1000000.00", "currencyCode", "TZS")));
        assertThat(statuses(n)).containsOnly(InstalmentStatus.CANCELLED);

        TenantContext.set(TENANT);
        try {
            // No survival benefit paid yet, and no premiums: SA - 0 = 1,000,000; 105% of 0 premiums is less.
            assertThat(api.deathBenefitCeiling(n, new BigDecimal("1000000.00"))).isEqualByComparingTo("1000000.00");
        } finally {
            TenantContext.clear();
        }
        fixtures.collectPremium(TENANT, n, new BigDecimal("2000000.00"), LocalDate.now());
        TenantContext.set(TENANT);
        try {
            // 105% of 2,000,000 collected = 2,100,000 > SA, so the higher figure is the ceiling (§6).
            assertThat(api.deathBenefitCeiling(n, new BigDecimal("1000000.00"))).isEqualByComparingTo("2100000.00");
        } finally {
            TenantContext.clear();
        }
    }
```

with private helpers `schedule(n)` (= `api.listForPolicy(n)` under `TenantContext.set(TENANT)`) and `statuses(n)` (= the statuses of `schedule(n)`).

- [ ] **Step 3: Run it to verify it fails**

Run: `./mvnw -o test -Dtest=PayoutLifecycleIntegrationTest`
Expected: FAIL — statuses stay SCHEDULED; `deathBenefitCeiling` does not compile.

- [ ] **Step 4: Implement the reactions**

Add to `BenefitPayoutApi`:

```java
    /**
     * The death-claim ceiling for an individual policy, given the sum-assured ceiling policy computed.
     * Unchanged for a policy with no payout terms. Otherwise: minus survival benefits PAID when the
     * product deducts them (§7), then the higher of that and the product's % of premiums collected (§6).
     */
    BigDecimal deathBenefitCeiling(String policyNumber, BigDecimal sumAssuredCeiling);
```

Add to `BenefitPayoutApiImpl`:

```java
    @Transactional
    public void cancelFuture(String policyNumber, LocalDate after, String reason) {
        for (PayoutInstalment i : instalments.findByPolicyNumberAndDueDateAfter(policyNumber, after.minusDays(1))) {
            if (i.cancel(reason)) {
                instalments.save(i);
            }
        }
        // Held instalments dated before `after` are owed-but-unpaid; a lapse forfeits them too (Q3).
        if (reason.startsWith("Policy lapsed")) {
            for (PayoutInstalment held : instalments.findByPolicyNumberAndStatus(policyNumber, InstalmentStatus.ON_HOLD.name())) {
                held.cancel(reason);
                instalments.save(held);
            }
        }
        streams.findByPolicyNumber(policyNumber).forEach(s -> { s.end(); streams.save(s); });
    }

    /** Reinstatement: lapse-cancelled instalments dated after today come back; earlier ones stay forfeited. */
    @Transactional
    public void restoreAfterReinstatement(String policyNumber, LocalDate reinstatedOn) {
        for (PayoutInstalment i : instalments.findByPolicyNumberAndDueDateAfter(policyNumber, reinstatedOn)) {
            if (i.status() == InstalmentStatus.CANCELLED && i.getStatusReason() != null
                    && i.getStatusReason().startsWith("Policy lapsed")) {
                i.restore();
                instalments.save(i);
            }
        }
    }

    @Transactional
    public void restateForPaidUp(String policyNumber, BigDecimal paidUpSa, BigDecimal originalSa) {
        String reason = "Made paid-up: " + paidUpSa.setScale(2) + " of " + originalSa.setScale(2) + " sum assured";
        for (PayoutInstalment i : instalments.findByPolicyNumberOrderByDueDateAscRowOrderAsc(policyNumber)) {
            if (i.getCurrentAmount() != null) {
                i.restate(PayoutArithmetic.restate(i.getOriginalAmount(), paidUpSa, originalSa), reason);
                instalments.save(i);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal deathBenefitCeiling(String policyNumber, BigDecimal sumAssuredCeiling) {
        PolicyView policy = policyApi.getPolicy(policyNumber);
        PayoutPlan plan = productApi.resolvePayoutPlan(policy.productVersionId());
        if (!plan.authored()) {
            return sumAssuredCeiling;
        }
        BigDecimal ceiling = sumAssuredCeiling;
        if (Boolean.TRUE.equals(plan.terms().survivalBenefitsDeductedFromDeath())) {
            BigDecimal paid = instalments.findByPolicyNumberAndStatus(policyNumber, InstalmentStatus.PAID.name()).stream()
                .filter(i -> i.kind() == PayoutKind.SURVIVAL).map(PayoutInstalment::getCurrentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            ceiling = ceiling.subtract(paid).max(BigDecimal.ZERO);
        }
        BigDecimal pct = plan.terms().deathBenefitPremiumPercent();
        if (pct != null) {
            BigDecimal collected = tallies.findById(policyNumber).map(PremiumTally::getPremiumsCollected).orElse(BigDecimal.ZERO);
            ceiling = ceiling.max(PayoutArithmetic.percentOf(collected, pct));
        }
        return ceiling;
    }
```

In `benefitpayout/application/PolicyEventListener.onDomainEvent`, replace the `default` comment line with these cases ahead of it:

```java
            case "policy.PolicyLapsed" -> withTenant(envelope, p ->
                api.cancelFuture((String) p.get("policyNumber"), LocalDate.now(), "Policy lapsed"));
            case "policy.PolicySurrendered" -> withTenant(envelope, p ->
                api.cancelFuture((String) p.get("policyNumber"), BenefitPayoutApiImpl.BEGINNING, "Policy surrendered"));
            case "policy.PolicyReinstated" -> withTenant(envelope, p ->
                api.restoreAfterReinstatement((String) p.get("policyNumber"), LocalDate.now()));
            case "policy.PolicyMadePaidUp" -> withTenant(envelope, p -> {
                @SuppressWarnings("unchecked") Map<String, Object> paidUp = (Map<String, Object>) p.get("paidUpSumAssured");
                @SuppressWarnings("unchecked") Map<String, Object> original = (Map<String, Object>) p.get("originalSumAssured");
                api.restateForPaidUp((String) p.get("policyNumber"), new java.math.BigDecimal((String) paidUp.get("amount")),
                    new java.math.BigDecimal((String) original.get("amount")));
            });
```

Add `static final LocalDate BEGINNING = LocalDate.of(1900, 1, 1);` to `BenefitPayoutApiImpl`: passed as `after`, it covers every instalment. Not `LocalDate.MIN` — it is outside Postgres's date range and fails at bind time.

```java
// benefitpayout/application/ClaimEventListener.java
package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.time.LocalDate;

/** An approved DEATH claim: nothing dated after the date of death is owed (spec §6). */
@Component
public class ClaimEventListener {

    private final BenefitPayoutApiImpl api;
    private final PolicyEventListener tenantRunner;

    public ClaimEventListener(BenefitPayoutApiImpl api, PolicyEventListener tenantRunner) {
        this.api = api;
        this.tenantRunner = tenantRunner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"claims.ClaimApproved".equals(envelope.eventType())) {
            return;
        }
        tenantRunner.withTenant(envelope, p -> {
            if (!"DEATH".equals(p.get("claimType")) || p.get("dateOfEvent") == null) {
                return;
            }
            api.cancelFuture((String) p.get("policyNumber"), LocalDate.parse((String) p.get("dateOfEvent")).plusDays(1),
                "Life assured died on " + p.get("dateOfEvent"));
        });
    }
}
```

`plusDays(1)` because a survival benefit due ON the date of death was earned — the life assured was alive at its start (spec §6: "alive on the due date").

- [ ] **Step 5: Wire claims**

`claims/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "policy::api", "underwriting::api", "party::api", "document::api", "refdata::api", "benefitpayout::api" })
package tz.co.nlolo.lifeplatform.claims;
```

Inject `BenefitPayoutApi benefitPayoutApi` into `ClaimsApiImpl` (constructor + field). In the MATURITY branch (line ~159), first inside `if (request.claimType() == ClaimType.MATURITY) {`:

```java
                if (benefitPayoutApi.hasScheduledMaturity(request.policyNumber())) {
                    throw new ClaimValidationException("Policy " + request.policyNumber()
                        + " pays its maturity benefit on schedule; it is paid from the policy's Payouts, not claimed");
                }
```

At approval (line ~545) replace `claim.approve(approvedAmount, approvedCurrency, claimable.amount());` with:

```java
            BigDecimal ceiling = claimable.amount();
            // An individual death claim on a product with payout terms: survival benefits already paid
            // may come off, and a %-of-premiums floor may lift it (step 2; guide §6, §7).
            if (claim.getClaimType() == ClaimType.DEATH && claim.getPolicyMemberId() == null) {
                ceiling = benefitPayoutApi.deathBenefitCeiling(claim.getPolicyNumber(), ceiling);
            }
            claim.approve(approvedAmount, approvedCurrency, ceiling);
```

and widen the `claims.ClaimApproved` payload two lines below to carry what benefitpayout needs:

```java
            eventPublisher.publishEvent(DomainEventEnvelope.of("claims.ClaimApproved", tenantId,
                Map.of("claimId", claimId, "policyNumber", claim.getPolicyNumber(),
                       "claimType", claim.getClaimType().name(),
                       "dateOfEvent", claim.getDateOfEvent().toString(),
                       "approvedAmount", Map.of("amount", approvedAmount.toPlainString(),
                                                 "currencyCode", approvedCurrency))));
```

Every claims test context now needs the benefitpayout schema; Task 2 Step 2 already put it into every class that applies product V18. Check none were missed:

```bash
grep -rL 'benefitpayout/V1__create' $(grep -rl 'db-migrations/claims/' src/test/java)
```

Expected: nothing printed. Any class listed applies claims without product — add both `product/V18` and `benefitpayout/V1` by hand.

- [ ] **Step 6: Add the claims-side test**

In the claims class that tests MATURITY claims (find with `grep -rln "ClaimType.MATURITY" src/test/java/tz/co/nlolo/lifeplatform/claims`), add one test that issues a scheduled endowment through `PayoutTestFixtures` (add `@Import(PayoutTestFixtures.class)` to the class) and asserts registering a MATURITY claim on it throws `ClaimValidationException` with the message above. The existing MATURITY tests on unscheduled products must stay green unchanged — that is the "older products keep today's behaviour" guarantee.

- [ ] **Step 7: Run this task's classes**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='PayoutLifecycleIntegrationTest,Claims*Test,CreditLifeClaimEndToEndTest,ClaimSettlementEndToEndTest'
```

Expected: all green.

- [ ] **Step 8: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat(benefitpayout): lapse, reinstatement, paid-up, surrender and death reshape the schedule; claims death ceiling (step 2, task 5)"
```

---

### Task 6: Income streams, proof of life, payment runs

**Files:**
- Create: `benefitpayout/domain/PaymentRun.java`, `benefitpayout/infrastructure/PaymentRunRepository.java`, `benefitpayout/api/PaymentRunView.java`, `benefitpayout/application/PaymentRunDrain.java`, `benefitpayout/infrastructure/PaymentRunResponse.java`, `ProofOfLifeRequest.java`
- Modify: `BenefitPayoutApi.java`, `BenefitPayoutApiImpl.java`, `BenefitPayoutController.java`, `openapi-benefitpayout.yaml`, `PayoutInstalmentRepository.java`
- Test: `benefitpayout/PaymentRunIntegrationTest.java`

**Interfaces:**
- Produces: `record PaymentRunView(UUID paymentRunId, LocalDate runDate, String status, String approvedBy, int instalmentCount, BigDecimal total, String currency)`; `BenefitPayoutApi.listRuns() -> List<PaymentRunView>`, `getRun(UUID) -> PaymentRunView`, `runInstalments(UUID) -> List<PayoutInstalmentView>`, `approveRun(UUID runId, String approver) -> PaymentRunView`, `recordProofOfLife(UUID streamId, ProofOfLifeMethod method, UUID documentId, String recordedBy)`; impl `prepareRun(LocalDate runDate)`, `suspendStream(UUID streamId)`.

- [ ] **Step 1: Write the failing test**

`PaymentRunIntegrationTest` (header as `PayoutDrainIntegrationTest`, with `@Autowired PayoutDueDrain dueDrain` and `@Autowired PaymentRunDrain runDrain`):

```java
    private static final PayoutPlan INCOME = PayoutPlan.authored(new PayoutTerms(15, 12, null, null), List.of(
        new PayoutRowInput(PayoutKind.INCOME, 1, 2, PayoutAmountBasis.FIXED, new BigDecimal("12000"), PayoutFrequency.MONTHLY),
        new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null)));

    /** Commenced two months and a day ago: the first two monthly income instalments are due. */
    private String incomePolicyWithTwoDue() {
        String n = fixtures.issueEndowment(TENANT, INCOME, new BigDecimal("1000000.00"), 120,
            LocalDate.now().minusMonths(2).minusDays(1));
        fixtures.collectPremium(TENANT, n, new BigDecimal("200000.00"), LocalDate.now().minusDays(1));
        dueDrain.drain();
        return n;
    }

    @Test
    void theFirstInstalmentActivatesTheStreamAndTheRestGoThroughARun() {
        String n = incomePolicyWithTwoDue();
        TenantContext.set(TENANT);
        try {
            List<PayoutInstalmentView> due = dueOf(n);
            assertThat(due).hasSize(2);
            api.review(due.get(0).instalmentId(), "+255700000009", ProofOfLifeMethod.IN_PERSON, null, "rev-1");
            api.approve(due.get(0).instalmentId(), "apr-2");      // activates the stream
        } finally {
            TenantContext.clear();
        }
        runDrain.drain();
        TenantContext.set(TENANT);
        try {
            PaymentRunView run = api.listRuns().get(0);
            assertThat(run.instalmentCount()).isEqualTo(1);
            assertThat(run.total()).isEqualByComparingTo("1000.00");
            PaymentRunView approved = api.approveRun(run.paymentRunId(), "fin-3");
            assertThat(approved.status()).isEqualTo("APPROVED");
            assertThat(api.runInstalments(run.paymentRunId())).allMatch(v -> v.status() == InstalmentStatus.APPROVED);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void overdueProofOfLifeSuspendsTheStreamAndNewProofReleasesItsInstalments() {
        String n = incomePolicyWithTwoDue();
        UUID streamId;
        TenantContext.set(TENANT);
        try {
            PayoutInstalmentView first = dueOf(n).get(0);
            streamId = first.streamId();
            api.review(first.instalmentId(), "+255700000009", ProofOfLifeMethod.IN_PERSON, null, "rev-1");
            api.approve(first.instalmentId(), "apr-2");
            api.suspendStream(streamId);                         // what the drain does when the date passes
            assertThat(statusesOfStream(n, streamId)).contains(InstalmentStatus.ON_HOLD);
            api.recordProofOfLife(streamId, ProofOfLifeMethod.LIFE_CERTIFICATE, null, "rev-1");
            assertThat(statusesOfStream(n, streamId)).doesNotContain(InstalmentStatus.ON_HOLD);
        } finally {
            TenantContext.clear();
        }
    }
```

Helpers: `dueOf(n)` = `api.listForPolicy(n)` filtered to `status() == DUE` and `kind() == INCOME`; `statusesOfStream(n, id)` = statuses of `listForPolicy(n)` whose `streamId()` equals `id`. `suspendStream` is a public impl method; autowire `BenefitPayoutApiImpl api` in this class rather than the interface.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -o test -Dtest=PaymentRunIntegrationTest`
Expected: COMPILATION FAILURE — `PaymentRunView`, `listRuns`, `PaymentRunDrain` missing.

- [ ] **Step 3: Implement**

```java
// benefitpayout/domain/PaymentRun.java
package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One day's batch of income instalments for one tenant (Q7). The system prepares; one person approves. */
@Entity
@Table(name = "payment_run", schema = "benefitpayout")
public class PaymentRun {

    @Id @UuidGenerator
    @Column(name = "payment_run_id") private UUID paymentRunId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "run_date", nullable = false) private LocalDate runDate;
    @Column(nullable = false) private String status = "PREPARED";
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Version private long version;

    protected PaymentRun() {}

    public PaymentRun(UUID tenantId, LocalDate runDate) {
        this.tenantId = tenantId;
        this.runDate = runDate;
    }

    public void approve(String approver) {
        if (!"PREPARED".equals(status)) {
            throw new PayoutStateException("Payment run " + paymentRunId + " is already " + status);
        }
        this.status = "APPROVED";
        this.approvedBy = approver;
        this.approvedAt = Instant.now();
    }

    public UUID getPaymentRunId() { return paymentRunId; }
    public LocalDate getRunDate() { return runDate; }
    public String getStatus() { return status; }
    public String getApprovedBy() { return approvedBy; }
}
```

```java
// benefitpayout/infrastructure/PaymentRunRepository.java
package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.PaymentRun;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRunRepository extends JpaRepository<PaymentRun, UUID> {
    Optional<PaymentRun> findByRunDate(LocalDate runDate);
    List<PaymentRun> findAllByOrderByRunDateDesc();

    @Query(value = "SELECT tenant_id FROM benefitpayout.tenants_with_stream_instalments_due()", nativeQuery = true)
    List<UUID> findTenantsWithStreamInstalmentsDue();
}
```

```java
// benefitpayout/api/PaymentRunView.java
package tz.co.nlolo.lifeplatform.benefitpayout.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** {@code total} is computed by the server; the console never sums instalments itself. */
public record PaymentRunView(UUID paymentRunId, LocalDate runDate, String status, String approvedBy,
                             int instalmentCount, BigDecimal total, String currency) {}
```

Add to `PayoutInstalmentRepository`:

```java
    @Query("select i from PayoutInstalment i, PayoutStream s where s.streamId = i.streamId "
        + "and i.status = 'DUE' and s.status = 'ACTIVE' and i.paymentRunId is null")
    List<PayoutInstalment> findStreamInstalmentsReadyForARun();
```

Add to `BenefitPayoutApi`:

```java
    List<PaymentRunView> listRuns();
    PaymentRunView getRun(UUID paymentRunId);
    List<PayoutInstalmentView> runInstalments(UUID paymentRunId);
    PaymentRunView approveRun(UUID paymentRunId, String approver);
    void recordProofOfLife(UUID streamId, ProofOfLifeMethod method, UUID documentId, String recordedBy);
```

Add `PaymentRunRepository runs` to the impl's constructor and fields, then:

```java
    /** Gather today's ready stream instalments into this tenant's run for the date (idempotent per date). */
    @Transactional
    public void prepareRun(LocalDate runDate) {
        List<PayoutInstalment> ready = instalments.findStreamInstalmentsReadyForARun();
        if (ready.isEmpty()) {
            return;
        }
        PaymentRun run = runs.findByRunDate(runDate).orElseGet(() -> runs.save(new PaymentRun(TenantContext.get(), runDate)));
        if (!"PREPARED".equals(run.getStatus())) {
            return; // today's run already went; tomorrow's picks these up
        }
        for (PayoutInstalment i : ready) {
            i.assignToRun(run.getPaymentRunId());
            instalments.save(i);
        }
    }

    @Override
    @Transactional
    public PaymentRunView approveRun(UUID paymentRunId, String approver) {
        PaymentRun run = runs.findById(paymentRunId).orElseThrow(() -> new PayoutNotFoundException(paymentRunId));
        run.approve(approver);
        runs.save(run);
        for (PayoutInstalment i : instalments.findByPaymentRunId(paymentRunId)) {
            // The payee was confirmed when the stream's first instalment was reviewed.
            String payee = instalments.findByStreamIdAndStatus(i.getStreamId(), InstalmentStatus.PAID.name()).stream()
                .map(PayoutInstalment::getPayeeRef).findFirst()
                .orElseGet(() -> firstReviewedPayee(i.getStreamId()));
            i.approveInRun(paymentRunId, approver, payee);
            instalments.save(i);
            publishPayoutRequested(i);
        }
        return runView(run);
    }

    private String firstReviewedPayee(UUID streamId) {
        return instalments.findByStreamIdAndStatus(streamId, InstalmentStatus.APPROVED.name()).stream()
            .map(PayoutInstalment::getPayeeRef).filter(java.util.Objects::nonNull).findFirst()
            .orElseThrow(() -> new PayoutStateException("Stream " + streamId + " has no reviewed payee"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentRunView> listRuns() {
        return runs.findAllByOrderByRunDateDesc().stream().map(this::runView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentRunView getRun(UUID paymentRunId) {
        return runView(runs.findById(paymentRunId).orElseThrow(() -> new PayoutNotFoundException(paymentRunId)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayoutInstalmentView> runInstalments(UUID paymentRunId) {
        return instalments.findByPaymentRunId(paymentRunId).stream().map(Views::of).toList();
    }

    private PaymentRunView runView(PaymentRun run) {
        List<PayoutInstalment> members = instalments.findByPaymentRunId(run.getPaymentRunId());
        BigDecimal total = members.stream().map(PayoutInstalment::getCurrentAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String currency = members.isEmpty() ? "TZS" : members.get(0).getCurrency();
        return new PaymentRunView(run.getPaymentRunId(), run.getRunDate(), run.getStatus(), run.getApprovedBy(),
            members.size(), total, currency);
    }

    /** Proof of life is overdue: suspend, and hold everything DUE on the stream that has not gone to a run. */
    @Transactional
    public void suspendStream(UUID streamId) {
        PayoutStream stream = streams.findById(streamId).orElseThrow(() -> new PayoutNotFoundException(streamId));
        stream.suspendForProofOfLife();
        streams.save(stream);
        for (PayoutInstalment i : instalments.findByStreamIdAndStatus(streamId, InstalmentStatus.DUE.name())) {
            if (i.getPaymentRunId() == null) {
                i.hold("Proof of life is overdue");
                instalments.save(i);
            }
        }
    }

    @Override
    @Transactional
    public void recordProofOfLife(UUID streamId, ProofOfLifeMethod method, UUID documentId, String recordedBy) {
        if (method == null) {
            throw new PayoutStateException("Proof of life needs a method");
        }
        PayoutStream stream = streams.findById(streamId).orElseThrow(() -> new PayoutNotFoundException(streamId));
        stream.recordProofOfLife(LocalDate.now());
        streams.save(stream);
        PremiumTally tally = tallies.findById(stream.getPolicyNumber()).orElse(null);
        for (PayoutInstalment i : instalments.findByStreamIdAndStatus(streamId, InstalmentStatus.ON_HOLD.name())) {
            if (tally == null || tally.isPaidUpTo(i.getDueDate())) {
                i.release();
                instalments.save(i);
            }
        }
    }
```

Note on `approveRun` and the two-person rule: the stream's first instalment carried reviewer ≠ approver; later run instalments are approved by the run approver alone (Q7, "the system prepared it"). That is the agreed rule, not a gap.

```java
// benefitpayout/application/PaymentRunDrain.java
package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.benefitpayout.infrastructure.PaymentRunRepository;
import tz.co.nlolo.lifeplatform.benefitpayout.infrastructure.PayoutStreamRepository;

import java.time.LocalDate;
import java.util.UUID;

/** Daily: suspend streams past their proof-of-life date, then prepare each tenant's run (Q7). */
@Component
public class PaymentRunDrain {

    private static final Logger log = LoggerFactory.getLogger(PaymentRunDrain.class);

    private final PaymentRunRepository runs;
    private final PayoutStreamRepository streams;
    private final BenefitPayoutApiImpl api;

    public PaymentRunDrain(PaymentRunRepository runs, PayoutStreamRepository streams, BenefitPayoutApiImpl api) {
        this.runs = runs;
        this.streams = streams;
        this.api = api;
    }

    @Scheduled(fixedDelayString = "${benefitpayout.run-drain-interval-ms:3600000}",
        initialDelayString = "${benefitpayout.run-drain-interval-ms:3600000}")
    public void drain() {
        for (Object[] row : streams.findDueForProofOfLifeAcrossTenants()) {
            inTenant((UUID) row[1], () -> api.suspendStream((UUID) row[0]));
        }
        for (UUID tenantId : runs.findTenantsWithStreamInstalmentsDue()) {
            inTenant(tenantId, () -> api.prepareRun(LocalDate.now()));
        }
    }

    private void inTenant(UUID tenantId, Runnable work) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            work.run();
        } catch (Exception e) {
            log.error("Payment-run drain failed for tenant {}", tenantId, e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
```

Register it in `ScheduledJobsHealthIndicator` the same way as `PayoutDueDrain` (Task 3 Step 6).

- [ ] **Step 4: REST and spec**

```java
// benefitpayout/infrastructure/PaymentRunResponse.java
package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import tz.co.nlolo.lifeplatform.benefitpayout.api.PaymentRunView;

import java.util.Map;
import java.util.UUID;

public record PaymentRunResponse(UUID paymentRunId, String runDate, String status, String approvedBy,
                                 int instalmentCount, Map<String, String> total) {
    public static PaymentRunResponse from(PaymentRunView v) {
        return new PaymentRunResponse(v.paymentRunId(), v.runDate().toString(), v.status(), v.approvedBy(),
            v.instalmentCount(), Map.of("amount", v.total().setScale(2).toPlainString(), "currencyCode", v.currency()));
    }
}
```

```java
// benefitpayout/infrastructure/ProofOfLifeRequest.java
package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import jakarta.validation.constraints.NotNull;
import tz.co.nlolo.lifeplatform.benefitpayout.api.ProofOfLifeMethod;

import java.util.UUID;

public record ProofOfLifeRequest(@NotNull ProofOfLifeMethod proofOfLifeMethod, UUID proofOfLifeDocumentId) {}
```

Controller additions:

```java
    @GetMapping("/payment-runs")
    @PreAuthorize(FINANCE)
    public List<PaymentRunResponse> listRuns() {
        return api.listRuns().stream().map(PaymentRunResponse::from).toList();
    }

    @GetMapping("/payment-runs/{paymentRunId}")
    @PreAuthorize(FINANCE)
    public PaymentRunResponse getRun(@PathVariable UUID paymentRunId) {
        return PaymentRunResponse.from(api.getRun(paymentRunId));
    }

    @GetMapping("/payment-runs/{paymentRunId}/instalments")
    @PreAuthorize(FINANCE)
    public List<PayoutInstalmentResponse> runInstalments(@PathVariable UUID paymentRunId) {
        return api.runInstalments(paymentRunId).stream().map(PayoutInstalmentResponse::from).toList();
    }

    @PostMapping("/payment-runs/{paymentRunId}/approve")
    @PreAuthorize(FINANCE)
    public ResponseEntity<PaymentRunResponse> approveRun(@PathVariable UUID paymentRunId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.accepted().body(PaymentRunResponse.from(api.approveRun(paymentRunId, jwt.getSubject())));
    }

    @PostMapping("/payout-streams/{streamId}/proof-of-life")
    @PreAuthorize(FINANCE)
    public ResponseEntity<Void> recordProofOfLife(@PathVariable UUID streamId, @Valid @RequestBody ProofOfLifeRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        api.recordProofOfLife(streamId, request.proofOfLifeMethod(), request.proofOfLifeDocumentId(), jwt.getSubject());
        return ResponseEntity.noContent().build();
    }
```

Spec additions (paths and one schema):

```yaml
  /payment-runs:
    get:
      summary: Payment runs, newest first (a bare array — runs are few)
      responses:
        '200':
          description: Runs
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/PaymentRun' } }
  /payment-runs/{paymentRunId}:
    get:
      summary: One run, with its server-computed total
      parameters:
        - { name: paymentRunId, in: path, required: true, schema: { type: string, format: uuid } }
      responses:
        '200':
          description: The run
          content:
            application/json:
              schema: { $ref: '#/components/schemas/PaymentRun' }
  /payment-runs/{paymentRunId}/instalments:
    get:
      summary: The instalments in one run
      parameters:
        - { name: paymentRunId, in: path, required: true, schema: { type: string, format: uuid } }
      responses:
        '200':
          description: Instalments
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/PayoutInstalment' } }
  /payment-runs/{paymentRunId}/approve:
    post:
      summary: Approve a PREPARED run; requests every instalment's payment
      parameters:
        - { name: paymentRunId, in: path, required: true, schema: { type: string, format: uuid } }
      responses:
        '202':
          description: Approved and the payments requested — not yet paid
          content:
            application/json:
              schema: { $ref: '#/components/schemas/PaymentRun' }
        '422':
          description: Already approved
          content:
            application/problem+json:
              schema: { $ref: 'openapi-common.yaml#/components/schemas/ProblemDetails' }
  /payout-streams/{streamId}/proof-of-life:
    post:
      summary: Record proof of life on an income stream; releases what the overdue proof held
      parameters:
        - { name: streamId, in: path, required: true, schema: { type: string, format: uuid } }
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [proofOfLifeMethod]
              properties:
                proofOfLifeMethod: { $ref: '#/components/schemas/ProofOfLifeMethod' }
                proofOfLifeDocumentId: { type: [string, "null"], format: uuid }
      responses:
        '204': { description: Recorded }
        '422':
          description: Refused
          content:
            application/problem+json:
              schema: { $ref: 'openapi-common.yaml#/components/schemas/ProblemDetails' }
```

```yaml
    PaymentRun:
      type: object
      required: [paymentRunId, runDate, status, instalmentCount, total]
      properties:
        paymentRunId: { type: string, format: uuid }
        runDate: { type: string, format: date }
        status: { type: string, enum: [PREPARED, APPROVED] }
        approvedBy: { type: [string, "null"] }
        instalmentCount: { type: integer }
        total: { $ref: 'openapi-common.yaml#/components/schemas/Money' }
```

Add one contract test to `BenefitPayoutContractTest`: prepare a run the way `PaymentRunIntegrationTest` does, then `GET /payment-runs` and `POST /payment-runs/{id}/approve` validate against `SPEC_PATH`.

- [ ] **Step 5: Run this task's classes**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest=PaymentRunIntegrationTest,BenefitPayoutContractTest,PayoutDrainIntegrationTest
```

Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java api/openapi/openapi-benefitpayout.yaml
git commit -m "feat(benefitpayout): income streams, proof-of-life suspension, daily payment runs (step 2, task 6)"
```

---

### Task 7: Free-look cancellation

**Files:**
- Create: `benefitpayout/domain/FreeLookCancellation.java`, `FreeLookDeduction.java`, `benefitpayout/infrastructure/FreeLookCancellationRepository.java`, `FreeLookDeductionRepository.java`, `FreeLookRequest.java`, `FreeLookResponse.java`, `benefitpayout/api/FreeLookCancellationView.java`, `FreeLookDeductionInput.java`
- Modify: `policy/api/PolicyApi.java`, `policy/application/PolicyApiImpl.java`, `policy/domain/Policy.java`, `billing/application/PolicyEventListener.java:49-58`, `distribution/application/PolicyEventListener.java:122-128`, `distribution/infrastructure/CommissionAccrualRepository.java`, `BenefitPayoutApi.java`, `BenefitPayoutApiImpl.java`, `BenefitPayoutController.java`, `openapi-benefitpayout.yaml`
- Test: `benefitpayout/FreeLookIntegrationTest.java`, `benefitpayout/FreeLookCancellationTest.java` (unit)

**Interfaces:**
- Produces: `PolicyApi.cancelForFreeLook(String policyNumber, String cancelledBy)` publishing `policy.PolicyCancelledFreeLook {policyNumber, cancelledAt}`; `record FreeLookDeductionInput(String description, BigDecimal amount, UUID documentId)`; `record FreeLookCancellationView(UUID cancellationId, String policyNumber, String status, BigDecimal premiumsCollected, BigDecimal refundAmount, String currency, String payeeRef, String requestedBy, String approvedBy, List<FreeLookDeductionInput> deductions)`; `BenefitPayoutApi.requestFreeLook(String policyNumber, String payeeRef, List<FreeLookDeductionInput> deductions, String requestedBy)`, `approveFreeLook(UUID cancellationId, String approver)`, `findFreeLook(String policyNumber) -> Optional<FreeLookCancellationView>`; impl `markFreeLookRefunded(UUID, UUID)`, `markFreeLookRefundFailed(UUID)` (replacing Task 4's stubs).

- [ ] **Step 1: Policy side**

`Policy.java`, beside `surrender(...)`:

```java
    /** Free-look (step 2): only an ACTIVE policy, and cover is void from inception -- never on risk. */
    public void cancelForFreeLook() {
        if (!"ACTIVE".equals(status)) {
            throw new InvalidPolicyStateException("Policy " + policyNumber
                + " must be ACTIVE to be cancelled in its free-look period (current: " + status + ")");
        }
        this.status = "CANCELLED_FREE_LOOK";
    }
```

Add `"CANCELLED_FREE_LOOK"` to `alreadyClosed()`'s terminal list, and in `wasOnRiskOn`'s switch add `case "CANCELLED_FREE_LOOK" -> false;` ahead of the default branch (a death before cancellation is claimed, not cancelled; the gate in Step 3 refuses free-look on a policy with any claim).

`PolicyApi.java`:

```java
    /** Free-look cancellation, called by benefitpayout on approval. Publishes policy.PolicyCancelledFreeLook. */
    void cancelForFreeLook(String policyNumber, String cancelledBy);
```

`PolicyApiImpl.java`:

```java
    @Override
    @Transactional
    public void cancelForFreeLook(String policyNumber, String cancelledBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        policy.cancelForFreeLook();
        policyRepository.save(policy);
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyCancelledFreeLook", tenantId,
            Map.of("policyNumber", policyNumber, "cancelledAt", Instant.now().toString(), "cancelledBy", cancelledBy)));
    }
```

`billing/application/PolicyEventListener` switch — add beside `PolicySurrendered`:

```java
            case "policy.PolicyCancelledFreeLook" -> withTenant(envelope, this::handlePolicySurrendered);
```

(`handlePolicySurrendered` terminates the schedule, which is exactly what free-look needs.)

`distribution/application/PolicyEventListener` switch — add:

```java
            case "policy.PolicyCancelledFreeLook" -> withTenant(envelope, this::handleFreeLookCancelled);
```

and the handler beside `handlePolicyLapsed`:

```java
    /**
     * Free-look: the sale is undone from inception, so EVERY unreversed accrual on the policy goes back,
     * with no clawback window -- a lapse's window exists because cover ran for a while; here it never did.
     */
    private void handleFreeLookCancelled(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String policyNumber = (String) payload.get("policyNumber");
        String period = currentPeriod();
        for (CommissionAccrual original : commissionAccrualRepository
                .findByTenantIdAndPolicyNumberAndReversesAccrualIdIsNullAndAmountGreaterThan(tenantId, policyNumber, BigDecimal.ZERO)) {
            distributionApiImpl.persistAccrual(tenantId, original.getAgentId(), policyNumber, original.getTierType(),
                original.getAmount().negate(), original.getCurrency(), period,
                original.getAccrualId().toString(), original.getAccrualId(), "system:policy.PolicyCancelledFreeLook");
        }
    }
```

Add the finder to `CommissionAccrualRepository`:

```java
    List<CommissionAccrual> findByTenantIdAndPolicyNumberAndReversesAccrualIdIsNullAndAmountGreaterThan(
        UUID tenantId, String policyNumber, BigDecimal amount);
```

The idempotency key is the original accrual id, the lapse handler's own choice, so a lapse clawback and a free-look clawback of the same accrual cannot both land. If `CommissionAccrual`'s tier getter is not `getTierType()`, use its actual name (open `distribution/domain/CommissionAccrual.java`).

- [ ] **Step 2: Write the failing unit test for the cancellation aggregate**

```java
// src/test/java/tz/co/nlolo/lifeplatform/benefitpayout/FreeLookCancellationTest.java
package tz.co.nlolo.lifeplatform.benefitpayout;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.FreeLookCancellation;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FreeLookCancellationTest {

    @Test
    void theRefundIsPremiumsLessDeductions() {
        FreeLookCancellation c = FreeLookCancellation.request(UUID.randomUUID(), "POL-1",
            new BigDecimal("500000.00"), new BigDecimal("20000.00"), "TZS", "+255700000009", "req-1");
        assertThat(c.getRefundAmount()).isEqualByComparingTo("480000.00");
    }

    @Test
    void deductionsLargerThanThePremiumAreRefused() {
        assertThatThrownBy(() -> FreeLookCancellation.request(UUID.randomUUID(), "POL-1",
                new BigDecimal("10000.00"), new BigDecimal("20000.00"), "TZS", "+255700000009", "req-1"))
            .isInstanceOf(PayoutStateException.class)
            .hasMessage("Deductions of 20000.00 exceed the 10000.00 premiums collected");
    }

    @Test
    void theRequesterCannotApprove() {
        FreeLookCancellation c = FreeLookCancellation.request(UUID.randomUUID(), "POL-1",
            new BigDecimal("500000.00"), BigDecimal.ZERO, "TZS", "+255700000009", "req-1");
        assertThatThrownBy(() -> c.approve("req-1"))
            .hasMessage("A free-look cancellation must be approved by someone other than the person who requested it (req-1)");
        c.approve("apr-2");
        assertThat(c.getStatus()).isEqualTo("APPROVED");
    }
}
```

Run: `./mvnw -o test -Dtest=FreeLookCancellationTest` — Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement the aggregate, repositories, API and endpoints**

```java
// benefitpayout/domain/FreeLookCancellation.java
package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "free_look_cancellation", schema = "benefitpayout")
public class FreeLookCancellation {

    @Id @UuidGenerator
    @Column(name = "cancellation_id") private UUID cancellationId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private String status = "REQUESTED";
    @Column(name = "premiums_collected", nullable = false) private BigDecimal premiumsCollected;
    @Column(name = "refund_amount", nullable = false) private BigDecimal refundAmount;
    @Column(nullable = false) private String currency;
    @Column(name = "payee_ref", nullable = false) private String payeeRef;
    @Column(name = "requested_by", nullable = false) private String requestedBy;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt = Instant.now();
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "disbursement_id") private UUID disbursementId;
    @Version private long version;

    protected FreeLookCancellation() {}

    public static FreeLookCancellation request(UUID tenantId, String policyNumber, BigDecimal premiumsCollected,
                                               BigDecimal deductionsTotal, String currency, String payeeRef, String requestedBy) {
        BigDecimal premiums = premiumsCollected.setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal deductions = deductionsTotal.setScale(2, RoundingMode.HALF_EVEN);
        if (deductions.compareTo(premiums) > 0) {
            throw new PayoutStateException("Deductions of " + deductions + " exceed the " + premiums + " premiums collected");
        }
        if (payeeRef == null || payeeRef.isBlank()) {
            throw new PayoutStateException("A free-look refund needs a payee reference");
        }
        FreeLookCancellation c = new FreeLookCancellation();
        c.tenantId = tenantId;
        c.policyNumber = policyNumber;
        c.premiumsCollected = premiums;
        c.refundAmount = premiums.subtract(deductions);
        c.currency = currency;
        c.payeeRef = payeeRef;
        c.requestedBy = requestedBy;
        return c;
    }

    public void approve(String approver) {
        if (!"REQUESTED".equals(status)) {
            throw new PayoutStateException("Free-look cancellation " + cancellationId + " is " + status + ", not awaiting approval");
        }
        if (approver == null || approver.equals(requestedBy)) {
            throw new PayoutStateException("A free-look cancellation must be approved by someone other than the person who requested it ("
                + requestedBy + ")");
        }
        this.approvedBy = approver;
        this.approvedAt = Instant.now();
        this.status = "APPROVED";
    }

    public void markPaid(UUID disbursementId) {
        if ("APPROVED".equals(status)) { this.status = "PAID"; this.disbursementId = disbursementId; }
    }

    public void markFailed() {
        if ("APPROVED".equals(status)) { this.status = "FAILED"; }
    }

    public UUID getCancellationId() { return cancellationId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getStatus() { return status; }
    public BigDecimal getPremiumsCollected() { return premiumsCollected; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public String getCurrency() { return currency; }
    public String getPayeeRef() { return payeeRef; }
    public String getRequestedBy() { return requestedBy; }
    public String getApprovedBy() { return approvedBy; }
}
```

```java
// benefitpayout/domain/FreeLookDeduction.java
package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "free_look_deduction", schema = "benefitpayout")
public class FreeLookDeduction {

    @Id @UuidGenerator
    @Column(name = "deduction_id") private UUID deductionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "cancellation_id", nullable = false) private UUID cancellationId;
    @Column(nullable = false) private String description;
    @Column(nullable = false) private BigDecimal amount;
    @Column(name = "document_id") private UUID documentId;

    protected FreeLookDeduction() {}

    public FreeLookDeduction(UUID tenantId, UUID cancellationId, String description, BigDecimal amount, UUID documentId) {
        this.tenantId = tenantId;
        this.cancellationId = cancellationId;
        this.description = description;
        this.amount = amount;
        this.documentId = documentId;
    }

    public String getDescription() { return description; }
    public BigDecimal getAmount() { return amount; }
    public UUID getDocumentId() { return documentId; }
}
```

```java
// benefitpayout/infrastructure/FreeLookCancellationRepository.java
package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.FreeLookCancellation;

import java.util.Optional;
import java.util.UUID;

public interface FreeLookCancellationRepository extends JpaRepository<FreeLookCancellation, UUID> {
    Optional<FreeLookCancellation> findFirstByPolicyNumberOrderByRequestedAtDesc(String policyNumber);
}
```

```java
// benefitpayout/infrastructure/FreeLookDeductionRepository.java
package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.FreeLookDeduction;

import java.util.List;
import java.util.UUID;

public interface FreeLookDeductionRepository extends JpaRepository<FreeLookDeduction, UUID> {
    List<FreeLookDeduction> findByCancellationId(UUID cancellationId);
}
```

```java
// benefitpayout/api/FreeLookDeductionInput.java
package tz.co.nlolo.lifeplatform.benefitpayout.api;

import java.math.BigDecimal;
import java.util.UUID;

public record FreeLookDeductionInput(String description, BigDecimal amount, UUID documentId) {}
```

```java
// benefitpayout/api/FreeLookCancellationView.java
package tz.co.nlolo.lifeplatform.benefitpayout.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record FreeLookCancellationView(UUID cancellationId, String policyNumber, String status, BigDecimal premiumsCollected,
                                       BigDecimal refundAmount, String currency, String payeeRef, String requestedBy,
                                       String approvedBy, List<FreeLookDeductionInput> deductions) {}
```

Add to `BenefitPayoutApi`:

```java
    FreeLookCancellationView requestFreeLook(String policyNumber, String payeeRef, List<FreeLookDeductionInput> deductions,
                                             String requestedBy);
    FreeLookCancellationView approveFreeLook(UUID cancellationId, String approver);
    java.util.Optional<FreeLookCancellationView> findFreeLook(String policyNumber);
```

Add `FreeLookCancellationRepository cancellations, FreeLookDeductionRepository deductions` to the impl's constructor and fields, replace Task 4's two stubs, and add:

```java
    private static final Set<String> INDIVIDUAL_CATEGORIES = Set.of("TERM_LIFE", "ENDOWMENT", "WHOLE_LIFE", "EDUCATION_SAVINGS");

    @Override
    @Transactional
    public FreeLookCancellationView requestFreeLook(String policyNumber, String payeeRef,
                                                    List<FreeLookDeductionInput> deductionInputs, String requestedBy) {
        PolicyView policy = policyApi.getPolicy(policyNumber);
        if (!INDIVIDUAL_CATEGORIES.contains(policy.productCategory())) {
            throw new PayoutStateException("Free-look applies to individual policies; a " + policy.productCategory()
                + " scheme is cancelled under its contract");
        }
        if (policy.status() != tz.co.nlolo.lifeplatform.policy.api.PolicyStatus.ACTIVE) {
            throw new PayoutStateException("Policy " + policyNumber + " is " + policy.status() + "; only an ACTIVE policy can be cancelled in free-look");
        }
        Integer days = productApi.resolvePayoutPlan(policy.productVersionId()).terms().freeLookDays();
        if (days == null) {
            throw new PayoutStateException("Policy " + policyNumber + "'s product version has no free-look period");
        }
        LocalDate lastDay = policy.issueDate().plusDays(days);
        if (LocalDate.now().isAfter(lastDay)) {
            throw new PayoutStateException("Policy " + policyNumber + "'s free-look period ended on " + lastDay);
        }
        List<FreeLookDeductionInput> items = deductionInputs != null ? deductionInputs : List.of();
        for (FreeLookDeductionInput d : items) {
            if (d.description() == null || d.description().isBlank() || d.amount() == null || d.amount().signum() <= 0) {
                throw new PayoutStateException("Every deduction needs a description and an amount greater than zero");
            }
        }
        BigDecimal total = items.stream().map(FreeLookDeductionInput::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal collected = tallies.findById(policyNumber).map(PremiumTally::getPremiumsCollected).orElse(BigDecimal.ZERO);
        FreeLookCancellation c = cancellations.save(FreeLookCancellation.request(TenantContext.get(), policyNumber, collected,
            total, policy.premiumCurrency(), payeeRef, requestedBy));
        for (FreeLookDeductionInput d : items) {
            deductions.save(new FreeLookDeduction(TenantContext.get(), c.getCancellationId(), d.description(), d.amount(), d.documentId()));
        }
        return freeLookView(c);
    }

    @Override
    @Transactional
    public FreeLookCancellationView approveFreeLook(UUID cancellationId, String approver) {
        FreeLookCancellation c = cancellations.findById(cancellationId).orElseThrow(() -> new PayoutNotFoundException(cancellationId));
        c.approve(approver);
        cancellations.save(c);
        policyApi.cancelForFreeLook(c.getPolicyNumber(), approver);
        cancelFuture(c.getPolicyNumber(), BEGINNING, "Cancelled in free-look");
        if (c.getRefundAmount().signum() > 0) {
            eventPublisher.publishEvent(DomainEventEnvelope.of("benefitpayout.PayoutRequested", TenantContext.get(),
                Map.of("cancellationId", cancellationId.toString(), "idempotencyKey", "free-look:" + cancellationId,
                       "policyNumber", c.getPolicyNumber(), "payeeRef", c.getPayeeRef(), "purpose", "FREE_LOOK_REFUND",
                       "amount", Map.of("amount", c.getRefundAmount().toPlainString(), "currencyCode", c.getCurrency()))));
        }
        return freeLookView(c);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<FreeLookCancellationView> findFreeLook(String policyNumber) {
        return cancellations.findFirstByPolicyNumberOrderByRequestedAtDesc(policyNumber).map(this::freeLookView);
    }

    @Transactional
    public void markFreeLookRefunded(UUID cancellationId, UUID disbursementId) {
        cancellations.findById(cancellationId).ifPresent(c -> { c.markPaid(disbursementId); cancellations.save(c); });
    }

    @Transactional
    public void markFreeLookRefundFailed(UUID cancellationId) {
        cancellations.findById(cancellationId).ifPresent(c -> { c.markFailed(); cancellations.save(c); });
    }

    private FreeLookCancellationView freeLookView(FreeLookCancellation c) {
        return new FreeLookCancellationView(c.getCancellationId(), c.getPolicyNumber(), c.getStatus(), c.getPremiumsCollected(),
            c.getRefundAmount(), c.getCurrency(), c.getPayeeRef(), c.getRequestedBy(), c.getApprovedBy(),
            deductions.findByCancellationId(c.getCancellationId()).stream()
                .map(d -> new FreeLookDeductionInput(d.getDescription(), d.getAmount(), d.getDocumentId())).toList());
    }
```

Two notes. The window is counted from `issueDate` (spec §6). The open-claim check: there is no claims query `benefitpayout` may make, so a death during free-look is handled by claims first — `wasOnRiskOn` returns false only AFTER cancellation, and a claim filed before it is assessed on the ACTIVE policy. Record this in the commit message; it is the spec's "death during free-look is claimed, not cancelled".

`FreeLookRequest` / `FreeLookResponse` DTOs and the endpoints:

```java
// benefitpayout/infrastructure/FreeLookRequest.java
package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record FreeLookRequest(@NotBlank @Size(max = 200) String payeeRef, @Valid List<Deduction> deductions) {
    public record Deduction(@NotBlank @Size(max = 200) String description,
                            @NotNull @Pattern(regexp = "^\\d+(\\.\\d{1,2})?$") String amount, UUID documentId) {}
}
```

```java
// benefitpayout/infrastructure/FreeLookResponse.java
package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import tz.co.nlolo.lifeplatform.benefitpayout.api.FreeLookCancellationView;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FreeLookResponse(UUID cancellationId, String policyNumber, String status, Map<String, String> premiumsCollected,
                               Map<String, String> refundAmount, String payeeRef, String requestedBy, String approvedBy,
                               List<Deduction> deductions) {
    public record Deduction(String description, Map<String, String> amount, UUID documentId) {}

    public static FreeLookResponse from(FreeLookCancellationView v) {
        return new FreeLookResponse(v.cancellationId(), v.policyNumber(), v.status(),
            PayoutInstalmentResponse.money(v.premiumsCollected(), v.currency()),
            PayoutInstalmentResponse.money(v.refundAmount(), v.currency()), v.payeeRef(), v.requestedBy(), v.approvedBy(),
            v.deductions().stream().map(d -> new Deduction(d.description(),
                PayoutInstalmentResponse.money(d.amount(), v.currency()), d.documentId())).toList());
    }
}
```

Controller additions:

```java
    @PostMapping("/policies/{policyNumber}/free-look-cancellation")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<FreeLookResponse> requestFreeLook(@PathVariable String policyNumber,
                                                            @Valid @RequestBody FreeLookRequest request,
                                                            @AuthenticationPrincipal Jwt jwt) {
        List<FreeLookDeductionInput> items = request.deductions() == null ? List.of() : request.deductions().stream()
            .map(d -> new FreeLookDeductionInput(d.description(), new java.math.BigDecimal(d.amount()), d.documentId())).toList();
        return ResponseEntity.status(201).body(FreeLookResponse.from(
            api.requestFreeLook(policyNumber, request.payeeRef(), items, jwt.getSubject())));
    }

    @GetMapping("/policies/{policyNumber}/free-look-cancellation")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<FreeLookResponse> findFreeLook(@PathVariable String policyNumber) {
        return api.findFreeLook(policyNumber).map(FreeLookResponse::from).map(ResponseEntity::ok)
            .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/free-look-cancellations/{cancellationId}/approve")
    @PreAuthorize(FINANCE)
    public ResponseEntity<FreeLookResponse> approveFreeLook(@PathVariable UUID cancellationId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.accepted().body(FreeLookResponse.from(api.approveFreeLook(cancellationId, jwt.getSubject())));
    }
```

Spec: add the three paths (201 / 200-or-204 / 202 with `FreeLookCancellation`, 422 `ProblemDetails`) and the schema:

```yaml
    FreeLookCancellation:
      type: object
      required: [cancellationId, policyNumber, status, premiumsCollected, refundAmount, payeeRef, requestedBy, deductions]
      properties:
        cancellationId: { type: string, format: uuid }
        policyNumber: { type: string }
        status: { type: string, enum: [REQUESTED, APPROVED, PAID, FAILED, IN_DOUBT] }
        premiumsCollected: { $ref: 'openapi-common.yaml#/components/schemas/Money' }
        refundAmount: { $ref: 'openapi-common.yaml#/components/schemas/Money' }
        payeeRef: { type: string }
        requestedBy: { type: string }
        approvedBy: { type: [string, "null"] }
        deductions:
          type: array
          items:
            type: object
            required: [description, amount]
            properties:
              description: { type: string }
              amount: { $ref: 'openapi-common.yaml#/components/schemas/Money' }
              documentId: { type: [string, "null"], format: uuid }
```

The request body schema: `payeeRef` (string, required), `deductions` (array of `{description: string, amount: string matching ^\d+(\.\d{1,2})?$, documentId: uuid|null}`).

- [ ] **Step 4: Integration test**

`FreeLookIntegrationTest` (header as `PayoutPaymentEndToEndTest`, including WireMock and the distribution and billing migrations — copy those lines from `ClaimSettlementEndToEndTest`):

```java
    @Test
    void anApprovedFreeLookCancelsTheContractAndRefundsLessDeductions() {
        String n = fixtures.issueEndowment(TENANT, ENDOWMENT_FREE_LOOK_15, new BigDecimal("500000.00"), 120);
        fixtures.collectPremium(TENANT, n, new BigDecimal("50000.00"), LocalDate.now());
        stubGatewaySuccess();
        TenantContext.set(TENANT);
        try {
            FreeLookCancellationView requested = api.requestFreeLook(n, "+255700000009",
                List.of(new FreeLookDeductionInput("Medical examination", new BigDecimal("8000.00"), null)), "csr-1");
            assertThat(requested.refundAmount()).isEqualByComparingTo("42000.00");
            assertThatThrownBy(() -> api.approveFreeLook(requested.cancellationId(), "csr-1")).isInstanceOf(PayoutStateException.class);
            api.approveFreeLook(requested.cancellationId(), "fin-2");
            assertThat(policyApi.getPolicy(n).status()).isEqualTo(PolicyStatus.CANCELLED_FREE_LOOK);
            assertThat(api.findFreeLook(n).orElseThrow().status()).isEqualTo("PAID");
            assertThat(api.listForPolicy(n)).allMatch(v -> v.status() == InstalmentStatus.CANCELLED);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void outsideTheWindowIsRefused() {
        String n = fixtures.issueEndowment(TENANT, ENDOWMENT_FREE_LOOK_15, new BigDecimal("500000.00"), 120);
        jdbcTemplate.update("UPDATE policy.policy SET issue_date = current_date - 16 WHERE policy_number = ?", n);
        TenantContext.set(TENANT);
        try {
            assertThatThrownBy(() -> api.requestFreeLook(n, "+255700000009", List.of(), "csr-1"))
                .hasMessage("Policy " + n + "'s free-look period ended on " + LocalDate.now().minusDays(1));
        } finally {
            TenantContext.clear();
        }
    }
```

`ENDOWMENT_FREE_LOOK_15` = one MATURITY row at 100% with `new PayoutTerms(15, null, null, null)`. The `UPDATE` connects as the container's owner role, so RLS does not apply — the same reason `PolicyApiIntegrationTest.seedCashValue` gives.

- [ ] **Step 5: Run this task's classes and the two neighbours it touched**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='FreeLookCancellationTest,FreeLookIntegrationTest,BenefitPayoutContractTest,Distribution*Test,BillingApiIntegrationTest,PolicyApiIntegrationTest'
```

Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java api/openapi/openapi-benefitpayout.yaml
git commit -m "feat(benefitpayout): free-look cancellation -- itemised deductions, two-person approval, full clawback (step 2, task 7)"
```

---

### Task 8: Maturities register

**Files:**
- Modify: `policy/api/PolicyApi.java`, `policy/application/PolicyApiImpl.java`, `policy/infrastructure/PolicyRepository.java`, `policy/infrastructure/PolicyController.java`, `api/openapi/openapi-policy.yaml`
- Test: extend `policy/PolicyContractTest.java`

A policy-side read — "which policies mature between two dates" is a question about policies, and finance plans cash from it (guide §6, "Reports"). It lists; it does not total.

**Interfaces:**
- Produces: `PolicyApi.searchMaturing(LocalDate from, LocalDate to, Pageable) -> Page<PolicyView>`; `GET /policies/maturing?from=&to=&page=&pageSize=` → `{items: PolicyView[], page: PageMeta}`.

- [ ] **Step 1: Write the failing contract test**

In `PolicyContractTest` add (it already holds a staff-token helper and issuance fixtures — use its own names for both):

```java
    @Test
    void theMaturitiesRegisterListsPoliciesMaturingInTheWindowInDateOrder() throws Exception {
        // Two policies maturing inside the next 90 days, one outside it.
        String soon = issueWithTerm(LocalDate.now().minusMonths(11), 12);     // matures in ~1 month
        String later = issueWithTerm(LocalDate.now().minusMonths(10), 12);    // matures in ~2 months
        issueWithTerm(LocalDate.now(), 240);                                  // matures in 20 years
        mockMvc.perform(get("/policies/maturing")
                .param("from", LocalDate.now().toString()).param("to", LocalDate.now().plusDays(90).toString())
                .with(financeOfficer()))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.items[0].policyNumber").value(soon))
            .andExpect(jsonPath("$.items[1].policyNumber").value(later))
            .andExpect(jsonPath("$.page.totalElements").value(2));
    }
```

`issueWithTerm(commencement, termMonths)` issues and activates a policy through `PolicyApi` the way the class's existing issuance helper does, passing `commencement` and `termMonths` into the 16-argument `IssueRequest` (see `PayoutTestFixtures.issue`). `financeOfficer()` is the class's FINANCE_OFFICER token helper; add one shaped like `BenefitPayoutContractTest.staff("FINANCE_OFFICER", ...)` if it has none. Use a fresh tenant for this test so other tests' policies do not count.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -o test -Dtest=PolicyContractTest#theMaturitiesRegisterListsPoliciesMaturingInTheWindowInDateOrder`
Expected: FAIL — 404 (no route) or spec validation failure.

- [ ] **Step 3: Implement**

`PolicyRepository`:

```java
    Page<Policy> findByMaturityDateBetweenAndStatusIn(LocalDate from, LocalDate to, Collection<String> statuses, Pageable pageable);
```

`PolicyApi`:

```java
    /** Policies in force whose maturity date falls in [from, to], maturity date then policy number order. */
    Page<PolicyView> searchMaturing(LocalDate from, LocalDate to, Pageable pageable);
```

`PolicyApiImpl`:

```java
    @Override
    @Transactional(readOnly = true)
    public Page<PolicyView> searchMaturing(LocalDate from, LocalDate to, Pageable pageable) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("A maturities window needs a from date on or before its to date");
        }
        return policyRepository.findByMaturityDateBetweenAndStatusIn(from, to,
            List.of("ACTIVE", "REINSTATED", "PAID_UP", "SUSPENDED"), pageable).map(this::toView);
    }
```

`PolicyController` (declared BEFORE `@GetMapping("/policies/{policyNumber}")` for readability; Spring matches the literal segment first regardless):

```java
    @GetMapping("/policies/maturing")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<PolicyPageResponse> maturing(@RequestParam LocalDate from, @RequestParam LocalDate to,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int pageSize) {
        Page<PolicyView> result = policyApi.searchMaturing(from, to,
            PageRequest.of(page, Math.min(pageSize, 100), Sort.by("maturityDate", "policyNumber")));
        return ResponseEntity.ok(PolicyPageResponse.from(result));
    }
```

`PolicyPageResponse`: if `PolicyController` already returns a paged policy list (the Policies register does — find its response record with `grep -n "PageResponse\|PageMeta" src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/*.java`), reuse that record and its `from(...)`; do not write a second one. An `IllegalArgumentException` already maps to 400 through `GlobalExceptionHandler` — confirm with `grep -n "IllegalArgumentException" src/main/java/tz/co/nlolo/lifeplatform/GlobalExceptionHandler.java`; if it does not, throw the policy module's existing 422 exception instead.

`openapi-policy.yaml`: add `/policies/maturing` GET with `from`, `to` (required, `format: date`), `page`, `pageSize`, responding `{items: [$ref PolicyView], page: $ref openapi-common PageMeta}` — copy the response block of the existing paged `/policies` GET and change only the parameters and summary ("Policies maturing in a window — finance's cash planning list"). Also add `CANCELLED_FREE_LOOK` to the spec's PolicyStatus enum (it arrived in Task 3; the spec must say so or every contract test that returns one fails).

- [ ] **Step 4: Run**

Run: `./mvnw -o test -Dtest=PolicyContractTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java api/openapi/openapi-policy.yaml
git commit -m "feat(policy): maturities register for finance cash planning (step 2, task 8)"
```

---

### Task 9: Frontend — types, store, gates, the Payouts tab and the payout page

**Files:**
- Generate: `frontend/src/types/api/benefitpayout.ts` (via `npm run generate:api`)
- Create: `frontend/src/api/benefitPayouts.ts`, `frontend/src/store/benefitPayoutStore.ts`, `frontend/src/gates/payoutGates.ts`, `frontend/src/gates/payoutGates.test.ts`, `frontend/src/features/payouts/PayoutsPanel.tsx`, `PayoutPage.tsx`, `payoutReviewForm.ts`, `payoutReviewForm.test.ts`, `PayoutsQueuePage.tsx`, `index.ts`
- Modify: `frontend/src/lib/status.ts`, `frontend/src/lib/idempotency.ts` (REQUIRED list unchanged — these endpoints do not hard-require a key; see Step 3), `frontend/src/features/policies/PolicyDetailPage.tsx` (tabs at line ~295), `frontend/src/screens.tsx`, `frontend/src/navBadges.ts`, `frontend/src/api/types.ts`

- [ ] **Step 1: Generate types and export them**

```bash
cd frontend
npm run generate:api
```

Expected: `src/types/api/benefitpayout.ts` appears and `product.ts` / `policy.ts` gain the new schemas. Then in `src/api/types.ts`, beside the existing `components['schemas']` aliases, add aliases following that file's pattern exactly:

```ts
export type PayoutInstalmentView = BenefitPayoutSchemas['PayoutInstalment'];
export type InstalmentStatus = BenefitPayoutSchemas['InstalmentStatus'];
export type ProofOfLifeMethod = BenefitPayoutSchemas['ProofOfLifeMethod'];
export type PaymentRunView = BenefitPayoutSchemas['PaymentRun'];
export type FreeLookCancellationView = BenefitPayoutSchemas['FreeLookCancellation'];
export type PayoutScheduleRow = ProductSchemas['PayoutScheduleRow'];
export type PayoutTerms = ProductSchemas['PayoutTerms'];
```

(`BenefitPayoutSchemas` is the alias for `components['schemas']` imported from `@/types/api/benefitpayout`, declared the way `types.ts` declares the other modules' aliases.)

- [ ] **Step 2: Status buckets**

In `lib/status.ts`'s `STATUS_MAPS`, add `CANCELLED_FREE_LOOK` to `policy` and three new domains:

```ts
    // Free-look (step 2): the sale was undone from inception. Neutral, beside NOT_TAKEN_UP -- nobody
    // lost cover they were relying on, and it is not a failure.
    CANCELLED_FREE_LOOK: 'neutral',
```

```ts
  // benefitpayout/api/InstalmentStatus.java
  payoutInstalment: {
    SCHEDULED: 'neutral',
    DUE: 'pending',
    REVIEWED: 'pending',
    APPROVED: 'pending', // requested from payment, not yet paid
    PAID: 'success',
    ON_HOLD: 'warning',
    IN_DOUBT: 'warning', // money may or may not have moved -- never dressed as FAILED
    FAILED: 'danger',
    CANCELLED: 'neutral',
  },

  // benefitpayout/api/StreamStatus.java
  payoutStream: {
    PENDING_ACTIVATION: 'pending',
    ACTIVE: 'active',
    SUSPENDED: 'warning',
    ENDED: 'neutral',
  },

  // benefitpayout payment_run.status
  paymentRun: {
    PREPARED: 'pending',
    APPROVED: 'success',
  },

  // benefitpayout free_look_cancellation.status
  freeLookCancellation: {
    REQUESTED: 'pending',
    APPROVED: 'pending',
    PAID: 'success',
    FAILED: 'danger',
    IN_DOUBT: 'warning',
  },
```

Run `npx vitest run src/lib/status.test.ts src/components/StatusBadge.test.tsx` — if a test enumerates `STATUS_MAPS` keys against the generated enums, it will tell you which literal is missing.

- [ ] **Step 3: API client**

```ts
// src/api/benefitPayouts.ts
import { get, post } from '@/lib/http';
import type { MutationAttempt } from '@/lib/idempotency';
import type {
  FreeLookCancellationView,
  InstalmentStatus,
  Page,
  PaymentRunView,
  PayoutInstalmentView,
  ProofOfLifeMethod,
} from './types';

export function listPolicyPayouts(policyNumber: string): Promise<PayoutInstalmentView[]> {
  return get(`/policies/${encodeURIComponent(policyNumber)}/payouts`);
}

export function getPayout(instalmentId: string): Promise<PayoutInstalmentView> {
  return get(`/payouts/${encodeURIComponent(instalmentId)}`);
}

export interface PayoutSearchParams {
  status?: InstalmentStatus[];
  page?: number;
  pageSize?: number;
}

export function searchPayouts(params: PayoutSearchParams): Promise<Page<PayoutInstalmentView>> {
  return get('/payouts', { params, paramsSerializer: { indexes: null } });
}

export interface ReviewPayoutBody {
  payeeRef: string;
  proofOfLifeMethod: ProofOfLifeMethod | null;
  proofOfLifeDocumentId: string | null;
}

export function reviewPayout(instalmentId: string, body: ReviewPayoutBody, attempt: MutationAttempt): Promise<PayoutInstalmentView> {
  return post(`/payouts/${encodeURIComponent(instalmentId)}/review`, body, { headers: attempt.headers() });
}

export function approvePayout(instalmentId: string, attempt: MutationAttempt): Promise<PayoutInstalmentView> {
  return post(`/payouts/${encodeURIComponent(instalmentId)}/approve`, undefined, { headers: attempt.headers() });
}

export function retryPayout(instalmentId: string, attempt: MutationAttempt): Promise<PayoutInstalmentView> {
  return post(`/payouts/${encodeURIComponent(instalmentId)}/retry`, undefined, { headers: attempt.headers() });
}

export function listPaymentRuns(): Promise<PaymentRunView[]> {
  return get('/payment-runs');
}

export function getPaymentRun(id: string): Promise<PaymentRunView> {
  return get(`/payment-runs/${encodeURIComponent(id)}`);
}

export function listRunInstalments(id: string): Promise<PayoutInstalmentView[]> {
  return get(`/payment-runs/${encodeURIComponent(id)}/instalments`);
}

export function approvePaymentRun(id: string, attempt: MutationAttempt): Promise<PaymentRunView> {
  return post(`/payment-runs/${encodeURIComponent(id)}/approve`, undefined, { headers: attempt.headers() });
}

export function recordProofOfLife(
  streamId: string,
  body: { proofOfLifeMethod: ProofOfLifeMethod; proofOfLifeDocumentId: string | null },
  attempt: MutationAttempt,
): Promise<void> {
  return post(`/payout-streams/${encodeURIComponent(streamId)}/proof-of-life`, body, { headers: attempt.headers() });
}

export function findFreeLook(policyNumber: string): Promise<FreeLookCancellationView | ''> {
  // 204 when none exists -- the http helper returns an empty body, not an error.
  return get(`/policies/${encodeURIComponent(policyNumber)}/free-look-cancellation`);
}

export interface FreeLookBody {
  payeeRef: string;
  deductions: { description: string; amount: string; documentId: string | null }[];
}

export function requestFreeLook(policyNumber: string, body: FreeLookBody, attempt: MutationAttempt): Promise<FreeLookCancellationView> {
  return post(`/policies/${encodeURIComponent(policyNumber)}/free-look-cancellation`, body, { headers: attempt.headers() });
}

export function approveFreeLook(cancellationId: string, attempt: MutationAttempt): Promise<FreeLookCancellationView> {
  return post(`/free-look-cancellations/${encodeURIComponent(cancellationId)}/approve`, undefined, { headers: attempt.headers() });
}
```

The key is still minted once per submit and sent (the backend dedupes payments on its own derived key, so a duplicate POST is refused by state, not double-paid) — the store mints it, exactly as `distributionStore.requestPayout` does. If `lib/http.ts`'s `get`/`post` take a different config shape than axios's `AxiosRequestConfig`, match `api/distribution.ts`'s calls. Check how `paramsSerializer` is passed for array params elsewhere (`grep -rn "paramsSerializer" src/api`); use that form if one exists.

- [ ] **Step 4: Store**

```ts
// src/store/benefitPayoutStore.ts
import { create } from 'zustand';
import {
  approvePayout,
  getPayout,
  listPolicyPayouts,
  retryPayout,
  reviewPayout,
  searchPayouts,
  type PayoutSearchParams,
  type ReviewPayoutBody,
} from '@/api/benefitPayouts';
import type { Page, PayoutInstalmentView } from '@/api/types';
import type { MutationAttempt } from '@/lib/idempotency';
import { idle, track, type Resource } from './createResourceSlice';

type Keyed<T> = Record<string, Resource<T>>;

interface BenefitPayoutState {
  byPolicy: Keyed<PayoutInstalmentView[]>;
  instalment: Keyed<PayoutInstalmentView>;
  queue: Resource<Page<PayoutInstalmentView>>;
  acting: Keyed<PayoutInstalmentView>;
  loadForPolicy: (policyNumber: string) => Promise<void>;
  loadInstalment: (id: string) => Promise<void>;
  loadQueue: (params: PayoutSearchParams) => Promise<void>;
  review: (id: string, body: ReviewPayoutBody, attempt: MutationAttempt) => Promise<void>;
  approve: (id: string, attempt: MutationAttempt) => Promise<void>;
  retry: (id: string, attempt: MutationAttempt) => Promise<void>;
}

export const useBenefitPayoutStore = create<BenefitPayoutState>((set, getState) => {
  const act = (id: string, call: () => Promise<PayoutInstalmentView>) =>
    track(
      `benefitpayout.act.${id}`,
      getState().acting[id] ?? idle<PayoutInstalmentView>(),
      (next) => set((s) => ({ acting: { ...s.acting, [id]: next } })),
      async () => {
        const updated = await call();
        set((s) => ({ instalment: { ...s.instalment, [id]: { ...(s.instalment[id] ?? idle()), status: 'success', data: updated } } }));
        return updated;
      },
    );

  return {
    byPolicy: {},
    instalment: {},
    queue: idle(),
    acting: {},
    loadForPolicy: (n) =>
      track(`benefitpayout.policy.${n}`, getState().byPolicy[n] ?? idle(), (next) =>
        set((s) => ({ byPolicy: { ...s.byPolicy, [n]: next } })), () => listPolicyPayouts(n)),
    loadInstalment: (id) =>
      track(`benefitpayout.instalment.${id}`, getState().instalment[id] ?? idle(), (next) =>
        set((s) => ({ instalment: { ...s.instalment, [id]: next } })), () => getPayout(id)),
    loadQueue: (params) =>
      track('benefitpayout.queue', getState().queue, (next) => set({ queue: next }), () => searchPayouts(params)),
    review: (id, body, attempt) => act(id, () => reviewPayout(id, body, attempt)),
    approve: (id, attempt) => act(id, () => approvePayout(id, attempt)),
    retry: (id, attempt) => act(id, () => retryPayout(id, attempt)),
  };
});
```

The `Resource` success shape written inside `act` must match `createResourceSlice`'s `success(...)` helper — replace the object literal with `success(updated)` if that helper exists (it does in `distributionStore`'s imports).

- [ ] **Step 5: Gates (tests first)**

```ts
// src/gates/payoutGates.test.ts
import { describe, expect, it } from 'vitest';
import type { PayoutInstalmentView } from '@/api/types';
import { approveGates, reviewGates } from './payoutGates';

const base: PayoutInstalmentView = {
  instalmentId: 'i-1', policyNumber: 'POL-1', kind: 'SURVIVAL', dueDate: '2031-01-15',
  originalAmount: { amount: '100000.00', currencyCode: 'TZS' }, currentAmount: { amount: '100000.00', currencyCode: 'TZS' },
  restatementReason: null, status: 'DUE', statusReason: null, streamId: null, payeeRef: null,
  proofOfLifeMethod: null, reviewedBy: null, approvedBy: null, paymentRunId: null, attempts: 0,
};

describe('reviewGates', () => {
  it('passes a DUE survival payout and says proof of life is required', () => {
    const gates = reviewGates(base);
    expect(gates.every((g) => g.ok)).toBe(true);
    expect(gates.map((g) => g.title)).toContain('Proof of life is required');
  });

  it('fails hard on a held payout, with the server reason', () => {
    const gates = reviewGates({ ...base, status: 'ON_HOLD', statusReason: 'Premiums are not paid up to the due date' });
    const held = gates.find((g) => !g.ok)!;
    expect(held.hard).toBe(true);
    expect(held.detail).toBe('Premiums are not paid up to the due date');
  });

  it('asks no proof of life of a maturity', () => {
    expect(reviewGates({ ...base, kind: 'MATURITY' }).map((g) => g.title)).not.toContain('Proof of life is required');
  });
});

describe('approveGates', () => {
  it('refuses the reviewer, in the server wording', () => {
    const gates = approveGates({ ...base, status: 'REVIEWED', reviewedBy: 'fin-1' }, 'fin-1');
    const refused = gates.find((g) => !g.ok)!;
    expect(refused.hard).toBe(true);
    expect(refused.detail).toBe('A payout must be approved by someone other than the person who reviewed it (fin-1)');
  });

  it('passes a different approver', () => {
    expect(approveGates({ ...base, status: 'REVIEWED', reviewedBy: 'fin-1' }, 'admin-2').every((g) => g.ok)).toBe(true);
  });
});
```

```ts
// src/gates/payoutGates.ts
import type { PayoutInstalmentView } from '@/api/types';
import type { Gate } from './types';

/**
 * What the server will refuse before a payout review or approval -- and nothing it will not.
 * Wording is copied from the backend's PayoutStateException messages so the gate and the 422
 * a person would otherwise get say the same thing.
 */
export function reviewGates(p: PayoutInstalmentView): Gate[] {
  const gates: Gate[] = [];
  if (p.status === 'ON_HOLD') {
    gates.push({ ok: false, hard: true, title: 'This payout is on hold', detail: p.statusReason ?? 'On hold' });
  } else {
    gates.push({
      ok: p.status === 'DUE',
      hard: true,
      title: 'Awaiting review',
      detail: p.status === 'DUE' ? 'Due and not yet reviewed.' : `This payout is ${p.status}, so it cannot be reviewed.`,
    });
  }
  if (p.kind === 'SURVIVAL' || p.kind === 'INCOME') {
    gates.push({
      ok: true,
      hard: true,
      title: 'Proof of life is required',
      detail: `A ${p.kind} payout needs proof that the life assured is alive.`,
    });
  }
  return gates;
}

export function approveGates(p: PayoutInstalmentView, viewerSubject: string | undefined): Gate[] {
  const sameAsReviewer = viewerSubject !== undefined && viewerSubject === p.reviewedBy;
  return [
    {
      ok: p.status === 'REVIEWED',
      hard: true,
      title: 'Reviewed',
      detail: p.status === 'REVIEWED' ? `Reviewed by ${p.reviewedBy}.` : `This payout is ${p.status}, so it cannot be approved.`,
    },
    {
      ok: !sameAsReviewer,
      hard: true,
      title: 'A second person approves',
      detail: sameAsReviewer
        ? `A payout must be approved by someone other than the person who reviewed it (${p.reviewedBy})`
        : 'You did not review this payout.',
    },
  ];
}
```

Run: `npx vitest run src/gates/payoutGates.test.ts` — Expected: PASS, 5 tests.

- [ ] **Step 6: Review form schema (tests first)**

```ts
// src/features/payouts/payoutReviewForm.test.ts
import { describe, expect, it } from 'vitest';
import { payoutReviewSchema } from './payoutReviewForm';

describe('payoutReviewSchema', () => {
  it('requires a payee', () => {
    expect(payoutReviewSchema(false).safeParse({ payeeRef: '', proofOfLifeMethod: '' }).success).toBe(false);
  });
  it('requires a method when proof of life is needed', () => {
    const r = payoutReviewSchema(true).safeParse({ payeeRef: '+255700000009', proofOfLifeMethod: '' });
    expect(r.success).toBe(false);
  });
  it('accepts a maturity review with no method', () => {
    expect(payoutReviewSchema(false).safeParse({ payeeRef: '+255700000009', proofOfLifeMethod: '' }).success).toBe(true);
  });
});
```

```ts
// src/features/payouts/payoutReviewForm.ts
import { z } from 'zod';

export const PROOF_OF_LIFE_METHODS = [
  { value: 'IN_PERSON', label: 'Seen in person' },
  { value: 'PHONE_OR_VIDEO', label: 'Phone or video call' },
  { value: 'LIFE_CERTIFICATE', label: 'Life certificate received' },
] as const;

export function payoutReviewSchema(needsProofOfLife: boolean) {
  return z
    .object({
      payeeRef: z.string().trim().min(1, 'A payout needs a payee reference').max(200),
      proofOfLifeMethod: z.enum(['', 'IN_PERSON', 'PHONE_OR_VIDEO', 'LIFE_CERTIFICATE']),
    })
    .refine((v) => !needsProofOfLife || v.proofOfLifeMethod !== '', {
      path: ['proofOfLifeMethod'],
      message: 'Proof that the life assured is alive is required',
    });
}

export type PayoutReviewValues = z.infer<ReturnType<typeof payoutReviewSchema>>;
```

If the repo's form schemas are not zod (check `features/distribution/requestPayoutForm.ts`), write this in that file's library and shape instead. Run: `npx vitest run src/features/payouts/payoutReviewForm.test.ts` — Expected: PASS.

- [ ] **Step 7: The Payouts tab**

```tsx
// src/features/payouts/PayoutsPanel.tsx
import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { StatusBadge } from '@/components/StatusBadge';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { useBenefitPayoutStore } from '@/store/benefitPayoutStore';

const KIND_LABEL = { SURVIVAL: 'Survival benefit', MATURITY: 'Maturity', INCOME: 'Income', RETURN_OF_PREMIUM: 'Premium return' } as const;

/** Every instalment on one policy. A whole array, so a client-side table with no pager (DESIGN.md, Tables). */
export function PayoutsPanel({ policyNumber }: { policyNumber: string }) {
  const resource = useBenefitPayoutStore((s) => s.byPolicy[policyNumber]);
  const load = useBenefitPayoutStore((s) => s.loadForPolicy);

  useEffect(() => {
    void load(policyNumber);
  }, [policyNumber, load]);

  if (!resource || resource.status === 'loading' && !resource.data) return <LoadingBlock label="Loading payouts" />;
  if (resource.status === 'error' && resource.error) return <ErrorPanel error={resource.error} onRetry={() => void load(policyNumber)} />;
  const rows = resource.data ?? [];
  if (rows.length === 0) return <EmptyState title="No payouts scheduled" description="This policy's product version pays nothing while the life assured is alive." />;

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b">
            <th className="px-4 py-2.5 text-left text-xs font-medium text-muted-foreground">Due</th>
            <th className="px-4 py-2.5 text-left text-xs font-medium text-muted-foreground">Kind</th>
            <th className="px-4 py-2.5 text-right text-xs font-medium text-muted-foreground">Amount</th>
            <th className="px-4 py-2.5 text-left text-xs font-medium text-muted-foreground">Status</th>
            <th className="hidden px-4 py-2.5 text-left text-xs font-medium text-muted-foreground sm:table-cell">Reason</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((p) => (
            <tr key={p.instalmentId} className="h-11 border-b last:border-0 hover:bg-hover">
              <td className="px-4">
                <Link className="underline-offset-2 hover:underline" to={`/staff/payouts/${encodeURIComponent(p.instalmentId)}`}>
                  {formatDate(p.dueDate)}
                </Link>
              </td>
              <td className="px-4">{KIND_LABEL[p.kind]}</td>
              <td className="px-4 text-right">{p.currentAmount ? formatMoney(p.currentAmount) : <span className="text-subtle-foreground">—</span>}</td>
              <td className="px-4"><StatusBadge kind="payoutInstalment" value={p.status} /></td>
              <td className="hidden px-4 text-xs text-muted-foreground sm:table-cell">
                {p.statusReason ?? p.restatementReason ?? <span className="text-subtle-foreground">—</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
```

Before writing it, open the existing client-side table the console uses for bare arrays (`grep -rln "client-side\|ClientTable" src/components`) and the `InvoicesPanel`/`LoansPanel` table markup: if a shared component exists, render through it instead of this hand-written `<table>` (DESIGN.md: two table variants, one for bare arrays) and keep the columns, labels and empty copy above. Use the repo's real names for `EmptyState` / `LoadingBlock` / `ErrorPanel` and the Subtle Ink class (open `components/states.tsx` and `index.css`). The first cell must stay a real link/button (DESIGN.md, Activation).

In `PolicyDetailPage.tsx`, in `policyTabs()` after the `loans` tab and before `messages`, insert — staff only, and only when there is something to show (the Reinsurance tab's precedent):

```tsx
      ...(isStaff && hasPayouts
        ? [
            {
              value: 'payouts',
              label: 'Payouts',
              content: (
                <div className="pt-5">
                  <Panel title="Payouts" subtitle="What this policy pays while the life assured is alive">
                    <PayoutsPanel policyNumber={policyNumber} />
                  </Panel>
                </div>
              ),
            },
          ]
        : []),
```

where `hasPayouts` is derived, not stored (the console's lint bans synchronous setState in an effect — derive rendered state): at the top of the component, `const payouts = useBenefitPayoutStore((s) => s.byPolicy[policyNumber]);`, an effect that calls `loadForPolicy(policyNumber)` when `isStaff`, and `const hasPayouts = (payouts?.data?.length ?? 0) > 0;`. Because `tabs.push(...)` is used there, write the insertion as a conditional `tabs.push({...})` between the loans and messages pushes if the spread does not fit the existing call.

- [ ] **Step 8: The payout page**

```tsx
// src/features/payouts/PayoutPage.tsx
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { ConfirmAct } from '@/components/ConfirmAct';
import { DetailLayout } from '@/components/DetailLayout';
import { Field } from '@/components/Field';
import { FormField } from '@/components/FormField';
import { GatePanel } from '@/components/GatePanel';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { Receipt } from '@/components/Receipt';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { StatusBadge } from '@/components/StatusBadge';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { approveGates, reviewGates } from '@/gates/payoutGates';
import { readIdentity } from '@/auth/claims';
import { formatDate } from '@/lib/dates';
import { newMutationAttempt } from '@/lib/idempotency';
import { formatMoney } from '@/lib/money';
import { useBenefitPayoutStore } from '@/store/benefitPayoutStore';
import { PROOF_OF_LIFE_METHODS, payoutReviewSchema, type PayoutReviewValues } from './payoutReviewForm';

/**
 * One payout, and the act it exists for: review when DUE, approve when REVIEWED. A full page,
 * because money leaves the company (PRODUCT.md: preview is dismissable; acting is not).
 */
export function PayoutPage() {
  const { instalmentId = '' } = useParams();
  const auth = useAuth();
  const subject = readIdentity(auth.user?.access_token)?.subject;
  const resource = useBenefitPayoutStore((s) => s.instalment[instalmentId]);
  const acting = useBenefitPayoutStore((s) => s.acting[instalmentId]);
  const { loadInstalment, review, approve } = useBenefitPayoutStore();
  const [armed, setArmed] = useState<'review' | 'approve' | null>(null);
  const [pending, setPending] = useState<PayoutReviewValues | null>(null);

  useEffect(() => {
    void loadInstalment(instalmentId);
  }, [instalmentId, loadInstalment]);

  const p = resource?.data;
  const needsProof = p?.kind === 'SURVIVAL' || p?.kind === 'INCOME';
  const form = useForm<PayoutReviewValues>({
    resolver: zodResolver(payoutReviewSchema(needsProof)),
    defaultValues: { payeeRef: '', proofOfLifeMethod: '' },
  });
  const attempt = useMemo(() => newMutationAttempt(), [armed]);

  if (!resource || (!p && resource.status !== 'error')) return <LoadingBlock label="Loading payout" />;
  if (!p) return <ErrorPanel error={resource.error!} onRetry={() => void loadInstalment(instalmentId)} />;

  const amount = p.currentAmount ? formatMoney(p.currentAmount) : '—';
  const approvedNow = acting?.status === 'success' && acting.data?.status === 'APPROVED';

  return (
    <>
      <PageHeader
        breadcrumb={[{ label: 'Payouts', to: '/staff/payouts' }]}
        title={`${p.policyNumber} · ${formatDate(p.dueDate)}`}
        status={<StatusBadge kind="payoutInstalment" value={p.status} />}
      />
      <DetailLayout
        record={
          <Panel title="Payout">
            <dl>
              <Field label="Policy"><Link to={`/staff/policies/${encodeURIComponent(p.policyNumber)}?tab=payouts`}>{p.policyNumber}</Link></Field>
              <Field label="Kind">{p.kind}</Field>
              <Field label="Due">{formatDate(p.dueDate)}</Field>
              <Field label="Amount">{amount}</Field>
              {p.restatementReason && <Field label="Restated">{p.restatementReason}</Field>}
              <Field label="Reviewed by">{p.reviewedBy ?? '—'}</Field>
              <Field label="Approved by">{p.approvedBy ?? '—'}</Field>
            </dl>
          </Panel>
        }
      >
        {approvedNow ? (
          <Receipt
            heading="Payout requested"
            lines={[
              { label: 'Policy', value: p.policyNumber },
              { label: 'Amount', value: amount },
              { label: 'Payee', value: acting.data!.payeeRef },
              { label: 'Approved by', value: acting.data!.approvedBy },
            ]}
            note="The payment has been requested, not paid. This page shows PAID once the payment provider confirms it."
          />
        ) : p.status === 'DUE' || p.status === 'ON_HOLD' ? (
          <Panel title="Review" emphasis>
            <div className="space-y-4 p-4">
              <GatePanel gates={reviewGates(p)} />
              <form
                onSubmit={form.handleSubmit((v) => {
                  setPending(v);
                  setArmed('review');
                })}
                className="space-y-3"
              >
                <FormField label="Payee (mobile number or account)" error={form.formState.errors.payeeRef?.message}>
                  <Input {...form.register('payeeRef')} />
                </FormField>
                {needsProof && (
                  <FormField label="How was the life assured confirmed alive?" error={form.formState.errors.proofOfLifeMethod?.message}>
                    <Select {...form.register('proofOfLifeMethod')}>
                      <option value="">Choose…</option>
                      {PROOF_OF_LIFE_METHODS.map((m) => (
                        <option key={m.value} value={m.value}>{m.label}</option>
                      ))}
                    </Select>
                  </FormField>
                )}
                {armed !== 'review' && (
                  <Button type="submit" disabled={p.status !== 'DUE'}>Review payout</Button>
                )}
              </form>
              {armed === 'review' && pending && (
                <ConfirmAct
                  heading="Confirm this review"
                  consequence={`Record ${p.policyNumber}'s ${amount} payout to ${pending.payeeRef} as reviewed${needsProof ? ', life assured confirmed alive' : ''}.`}
                  reversal="A reviewed payout still needs a second person's approval before any money moves; until then nothing has left the company."
                  confirmLabel="Record review"
                  busy={acting?.status === 'loading'}
                  onCancel={() => setArmed(null)}
                  onConfirm={() =>
                    void review(
                      instalmentId,
                      {
                        payeeRef: pending.payeeRef,
                        proofOfLifeMethod: pending.proofOfLifeMethod === '' ? null : pending.proofOfLifeMethod,
                        proofOfLifeDocumentId: null,
                      },
                      attempt,
                    ).then(() => setArmed(null))
                  }
                />
              )}
              {acting?.status === 'error' && acting.error && <ErrorPanel error={acting.error} />}
            </div>
          </Panel>
        ) : p.status === 'REVIEWED' ? (
          <Panel title="Approve" emphasis>
            <div className="space-y-4 p-4">
              <GatePanel gates={approveGates(p, subject)} />
              {armed !== 'approve' ? (
                <Button onClick={() => setArmed('approve')} disabled={approveGates(p, subject).some((g) => !g.ok)}>
                  Approve payout
                </Button>
              ) : (
                <ConfirmAct
                  heading="Approve this payout"
                  tone="danger"
                  consequence={`Pay ${amount} to ${p.payeeRef} for ${p.policyNumber}.`}
                  reversal="Once the payment provider confirms it, the money has left and cannot be recalled here. If it fails, the payout can be retried from this page."
                  confirmLabel={`Pay ${amount}`}
                  busy={acting?.status === 'loading'}
                  onCancel={() => setArmed(null)}
                  onConfirm={() => void approve(instalmentId, attempt)}
                />
              )}
              {acting?.status === 'error' && acting.error && <ErrorPanel error={acting.error} />}
            </div>
          </Panel>
        ) : null}
      </DetailLayout>
    </>
  );
}
```

Match every import to the real module (open each component; `readIdentity`'s subject field, `newMutationAttempt`'s real name in `lib/idempotency.ts`, the form library `distribution/AgentDetailPage.tsx` uses for its payout form, and how `Field` renders a `<dt>/<dd>` pair). The FAILED-state retry action follows the same Approve-panel shape with `retry` and is shown only when `p.status === 'FAILED'`; add it with confirm label `Retry payment of ${amount}` and reversal "A retry sends a fresh payment request; the failed attempt is kept on record."

Proof-of-life document upload (Q2's optional certificate): leave it out of this step — the review body sends `proofOfLifeDocumentId: null`. Wire it in Task 10 Step 5 with the existing document upload component once the e2e for the basic path is green.

- [ ] **Step 9: The Payouts register and its nav badge**

```tsx
// src/features/payouts/PayoutsQueuePage.tsx
```

Build it as a copy of `features/finance/ArrearsPage.tsx` (the Finance queue with a server-side filter, `DataTable` paged variant and a `CountLine`), changing: the store to `useBenefitPayoutStore().loadQueue/queue`; the filter chips to the statuses `DUE`, `REVIEWED`, `ON_HOLD`, `APPROVED`, `FAILED`, `IN_DOUBT`, plus "All" (opens wide — PRODUCT.md: every queue opens wide unless an alert earns otherwise); columns Due (activation link to `/staff/payouts/:id`), Policy, Kind, Amount (right-aligned), Status (`StatusBadge kind="payoutInstalment"`); title "Payouts"; description "Scheduled payouts owed to living policyholders — review, then a second person approves."; `CountLine` label "payouts". Do not add a stat card.

`screens.tsx`, in the finance group after `field-receipts`:

```tsx
  // A work queue: money owed to living policyholders, waiting on a review or a second approval.
  // Earned its nav item the day GET /payouts existed (PRODUCT.md: only real list endpoints get one).
  {
    path: 'payouts',
    element: <PayoutsQueuePage />,
    reach: { group: 'finance', label: 'Payouts', icon: Banknote, badge: 'payouts-awaiting-review' },
  },
  { path: 'payouts/:instalmentId', element: <PayoutPage />, reach: 'drill-in' },
```

`navBadges.ts`: add `'payouts-awaiting-review'` to `BadgeKey` and:

```ts
  /* Payouts DUE and not yet reviewed: one status filter, like every badge. REVIEWED payouts awaiting
     a second person are the same people's work, but a badge counts one queue honestly or none. */
  'payouts-awaiting-review': {
    load: async () => (await searchPayouts({ status: ['DUE'], pageSize: 1 })).page.totalElements ?? 0,
    title: (n) => `${plural(n, 'payout', 'payouts')} due and awaiting review`,
  },
```

Gate the screen and badge to finance the same way the Arrears entry is gated (`canSeeFinance`).

- [ ] **Step 10: Run the frontend checks**

```bash
npm run typecheck ; npm run lint ; npx vitest run src/gates src/features/payouts src/lib src/components/StatusBadge.test.tsx
```

Expected: all pass. Do not run Prettier.

- [ ] **Step 11: Commit**

```bash
git add frontend/src
git commit -m "feat(console): payouts tab, payout review/approve page, payouts queue (step 2, task 9)"
```

---

### Task 10: Frontend — payment runs, maturities, free-look, schedule authoring, e2e

**Files:**
- Create: `frontend/src/features/payouts/PaymentRunsPage.tsx`, `PaymentRunPage.tsx`, `MaturitiesPage.tsx`, `FreeLookPanel.tsx`, `freeLookForm.ts`, `freeLookForm.test.ts`, `frontend/src/features/products/PayoutScheduleEditor.tsx`, `payoutScheduleSchema.ts`, `payoutScheduleSchema.test.ts`
- Create: `frontend/e2e/staff-payouts.spec.ts`, `frontend/e2e/staff-free-look.spec.ts`
- Modify: `frontend/src/features/policies/PolicyDetailPage.tsx` (Lifecycle panel), `frontend/src/features/products/PublishVersionForm.tsx` + `publishVersionSchema.ts`, `frontend/src/screens.tsx`, `frontend/src/api/policies.ts` (maturing search), `frontend/e2e/staff-products.spec.ts`, `backend/scripts/seed-dev-data.sh`

- [ ] **Step 1: Payment runs**

`PaymentRunsPage`: a client-side table with no pager over `listPaymentRuns()` (bare array). Columns Run date (link `/staff/payment-runs/:id`), Instalments (right), Total (`formatMoney(run.total)`, right — the server's total, never summed here), Status (`StatusBadge kind="paymentRun"`). `CountLine` "payment runs". Empty state: "No payment runs yet — a run is prepared each day income instalments fall due."

`PaymentRunPage`: `DetailLayout` with the rail = run date, instalment count, server total, status, approved by; one emphasised acting panel "Approve run" shown only while `PREPARED`, holding a `ConfirmAct` (`tone="danger"`): consequence `Pay ${count} income instalments totalling ${formatMoney(total)}.`, reversal "Each instalment is paid separately; one that fails can be retried from its own page without touching the rest.", confirm label `Pay ${formatMoney(total)}`. Under it, the run's instalments table (`listRunInstalments`), same columns as `PayoutsPanel` plus Policy. After approval, a `Receipt` stating "Payments requested, not yet paid."

`screens.tsx` (finance group, after `payouts`):

```tsx
  { path: 'payment-runs', element: <PaymentRunsPage />, reach: { group: 'finance', label: 'Payment runs', icon: Send } },
  { path: 'payment-runs/:paymentRunId', element: <PaymentRunPage />, reach: 'drill-in' },
```

- [ ] **Step 2: Maturities register**

Add to `api/policies.ts`:

```ts
export function searchMaturing(params: { from: string; to: string; page?: number; pageSize?: number }): Promise<Page<PolicyView>> {
  return get('/policies/maturing', { params });
}
```

`MaturitiesPage`: two `DatePicker`s (from defaults to today, to defaults to today + 3 months — computed by `lib/dates` helpers, not hand arithmetic), the paged `DataTable` over `searchMaturing`, columns Maturity date, Policy (link), Product (`ProductName`), Sum assured (right), Status. `CountLine` label "policies maturing" with hint "in this window". No total figure (the server returns none). Screen entry: `{ path: 'maturities', element: <MaturitiesPage />, reach: { group: 'finance', label: 'Maturities', icon: Landmark } }`, finance-gated.

- [ ] **Step 3: Free-look (tests first)**

```ts
// src/features/payouts/freeLookForm.test.ts
import { describe, expect, it } from 'vitest';
import { freeLookSchema } from './freeLookForm';

describe('freeLookSchema', () => {
  it('needs a payee', () => {
    expect(freeLookSchema.safeParse({ payeeRef: '', deductions: [] }).success).toBe(false);
  });
  it('accepts no deductions', () => {
    expect(freeLookSchema.safeParse({ payeeRef: '+255700000009', deductions: [] }).success).toBe(true);
  });
  it('refuses a deduction with no description or a malformed amount', () => {
    expect(freeLookSchema.safeParse({ payeeRef: '+255700000009', deductions: [{ description: '', amount: '10' }] }).success).toBe(false);
    expect(freeLookSchema.safeParse({ payeeRef: '+255700000009', deductions: [{ description: 'Medical', amount: '10.123' }] }).success).toBe(false);
  });
});
```

```ts
// src/features/payouts/freeLookForm.ts
import { z } from 'zod';

const MONEY = /^\d+(\.\d{1,2})?$/;

export const freeLookSchema = z.object({
  payeeRef: z.string().trim().min(1, 'A free-look refund needs a payee reference').max(200),
  deductions: z.array(
    z.object({
      description: z.string().trim().min(1, 'Every deduction needs a description').max(200),
      amount: z.string().regex(MONEY, 'Enter an amount like 8000.00'),
    }),
  ),
});

export type FreeLookValues = z.infer<typeof freeLookSchema>;
```

`FreeLookPanel` (rendered inside the Overview tab's `Lifecycle` panel in `PolicyDetailPage`, staff only, and only when `policy.status === 'ACTIVE'` or a cancellation exists):
- Loads `findFreeLook(policyNumber)`.
- **No cancellation:** a `GatePanel` with one hard gate "Inside the free-look window" whose detail states the window's last day — the window length comes from the policy's product version, which the console cannot read today, so the gate states only what `PolicyView` proves: "Requested within the product's free-look period of the issue date (`formatDate(issueDate)`); the server checks the exact day." It must not claim a day it cannot compute. Then the form (payee, a repeatable deductions list: description + amount), then on submit an inline `ConfirmAct` (`tone="danger"`): heading "Cancel in free-look", consequence `Cancel ${policyNumber} and refund premiums less ${n} deduction(s) to ${payee}.` — the refund FIGURE is shown only after the server returns it (no client arithmetic), reversal "Once approved, cover is void from inception and the policy cannot be revived; until a second person approves, nothing changes.", confirm label "Request cancellation".
- **REQUESTED:** the server's figures (premiums collected, each deduction, refund) as `Field` rows, then an approve act gated by a hard gate "A second person approves" with the server wording `A free-look cancellation must be approved by someone other than the person who requested it (${requestedBy})` when the viewer is the requester; the approve `ConfirmAct` consequence `Cancel ${policyNumber} and pay ${formatMoney(refundAmount)} to ${payeeRef}.`, confirm label `Refund ${formatMoney(refundAmount)}`; only rendered for finance viewers (`canSeeFinance`).
- **APPROVED / PAID / FAILED / IN_DOUBT:** a `StatusBadge kind="freeLookCancellation"` with the figures; after approval in this session, a `Receipt` "Refund requested, not yet paid".

Store actions for free-look go in `benefitPayoutStore` (`freeLook: Keyed<FreeLookCancellationView | null>`, `loadFreeLook`, `requestFreeLook`, `approveFreeLook`), minting the key once per submit as in Task 9.

Run: `npx vitest run src/features/payouts/freeLookForm.test.ts` — Expected: PASS.

- [ ] **Step 4: Schedule authoring (tests first)**

```ts
// src/features/products/payoutScheduleSchema.test.ts
import { describe, expect, it } from 'vitest';
import { payoutPlanSchema } from './payoutScheduleSchema';

const freeLook = { freeLookDays: '15', proofOfLifeIntervalMonths: '', survivalBenefitsDeductedFromDeath: '', deathBenefitPremiumPercent: '' };

describe('payoutPlanSchema', () => {
  it('requires free-look days on an individual product, in the server wording', () => {
    const r = payoutPlanSchema('TERM_LIFE').safeParse({ terms: { ...freeLook, freeLookDays: '' }, rows: [] });
    expect(r.success).toBe(false);
    expect(r.error!.issues[0]!.message).toBe('A free-look period in days is required on an individual product');
  });
  it('requires one maturity row on an endowment', () => {
    const r = payoutPlanSchema('ENDOWMENT').safeParse({ terms: freeLook, rows: [] });
    expect(r.error!.issues[0]!.message).toBe('An ENDOWMENT product must carry exactly one MATURITY row');
  });
  it('accepts an endowment with a maturity row', () => {
    const r = payoutPlanSchema('ENDOWMENT').safeParse({
      terms: freeLook,
      rows: [{ kind: 'MATURITY', fromPolicyYear: '', toPolicyYear: '', amountBasis: 'PERCENT_OF_SA', amountValue: '100', frequency: '' }],
    });
    expect(r.success).toBe(true);
  });
  it('needs no free-look on credit life', () => {
    expect(payoutPlanSchema('CREDIT_LIFE').safeParse({ terms: { ...freeLook, freeLookDays: '' }, rows: [] }).success).toBe(true);
  });
});
```

`payoutScheduleSchema.ts` mirrors `PayoutPlanValidator` rule for rule, with the identical messages (copy them from Task 1 Step 6 — the frontend must say exactly what the 422 would). `payoutPlanSchema(category)` returns a zod object `{ terms, rows }` with a `superRefine` that applies, in the validator's order: free-look required on `TERM_LIFE | ENDOWMENT | WHOLE_LIFE | EDUCATION_SAVINGS`; free-look 1–365; TERM_LIFE only one RETURN_OF_PREMIUM; WHOLE_LIFE / ANNUITY / UNIT_LINKED / GROUP_LIFE / CREDIT_LIFE no rows (`A ${category} product cannot carry a payout schedule`); ENDOWMENT / EDUCATION_SAVINGS exactly one MATURITY and no RETURN_OF_PREMIUM; per-row shape; SURVIVAL needs the deduction setting; INCOME needs the proof-of-life interval. A helper `toPayoutRequest(values)` converts the string form values into the `payoutTerms` / `payoutSchedule` request blocks (empty string → `null`, numbers via `Number(...)`, amounts kept as the user typed them — the backend parses them).

`PayoutScheduleEditor`: a section inside `PublishVersionForm`, after the existing benefit rows and using the same row-editor component step 1's cash-value table editor introduced (find it in `features/products/`; if step 1's editor is a local component in `PublishVersionForm.tsx`, extract nothing — reuse it in place). Fields: Free-look days; and, revealed only when the category allows rows, a rows table with Kind (select limited to the kinds the category allows), From year, To year (disabled for MATURITY / RETURN_OF_PREMIUM), Basis (select; RETURN_OF_PREMIUM fixes it to "Percent of premiums"), Value, Frequency (disabled for MATURITY / RETURN_OF_PREMIUM); then Proof-of-life interval (shown when an INCOME row exists) and "Survival benefits paid are deducted from the death benefit" (shown when a SURVIVAL row exists), and "Death benefit at least this % of premiums" (optional). Server 400/422 field errors bind onto these fields like the rest of the form.

Wire it into `publishVersionSchema.ts` (compose `payoutPlanSchema(category)` under a `payout` key) and into the submit mapping (`...toPayoutRequest(values.payout)`). Run:

```bash
npx vitest run src/features/products
```

Expected: PASS — including the existing `PublishVersionForm.test.tsx`, which must now fill free-look days for its TERM_LIFE fixture (add `freeLookDays: '15'` to its filled values; that is the same tightening the backend fixtures got in Task 1 Step 12).

- [ ] **Step 5: Proof-of-life document (Q2's optional certificate)**

On `PayoutPage`'s review form, when the method is `LIFE_CERTIFICATE`, show the existing document upload control the claim evidence panel uses (find it: `grep -rln "uploadDocument\|DocumentUpload" src/features`), with document type the platform's existing evidence type; pass its returned id as `proofOfLifeDocumentId`. If uploading requires a document type the backend's `DocumentType` does not have, do NOT add one here — leave the upload out, keep `proofOfLifeDocumentId: null`, and record "certificate upload needs a DocumentType" in the Task 11 review notes. (Q2 made the document optional.)

- [ ] **Step 6: Seed a scheduled product for the real stack**

In `backend/scripts/seed-dev-data.sh`, after the TERM_LIFE product block, add an ENDOWMENT product ("Nlolo Money-Back 20", code `END-MB-20`) published with `"payoutTerms":{"freeLookDays":15,"survivalBenefitsDeductedFromDeath":false}` and `"payoutSchedule":[{"kind":"SURVIVAL","fromPolicyYear":5,"toPolicyYear":5,"amountBasis":"PERCENT_OF_SA","amountValue":10,"frequency":"ANNUAL"},{"kind":"MATURITY","amountBasis":"PERCENT_OF_SA","amountValue":100}]`, following the TERM_LIFE block's curl/`VERSION_RESP` pattern exactly (same headers, same failure check). The e2e specs below issue against it.

- [ ] **Step 7: e2e specs**

Read `e2e/staff-policies.spec.ts` and `e2e/policies.ts` first and reuse their helpers (login state via the setup projects, issuing a policy, navigating by accessible name). Memory: e2e couples to accessible names — every name used below must match the labels written in Tasks 9–10 exactly.

`e2e/staff-payouts.spec.ts`:
1. **Authoring:** as admin, publish a version of a new ENDOWMENT product with one MATURITY row and free-look 15 through the product form; assert the version shows as published. Then submit an ENDOWMENT version with no MATURITY row and assert the form shows "An ENDOWMENT product must carry exactly one MATURITY row" before any request is sent.
2. **Review → approve → receipt:** issue a `END-MB-20` policy whose survival benefit is already due (issue with a commencement date 5 years and a day ago through the issuance helper; collect premiums through the existing payment helper so it is not held), wait for the due drain (set `BENEFITPAYOUT_DUE_DRAIN_INTERVAL_MS=5000` in the e2e backend environment — find where the e2e stack sets `POLICY_COVER_EXPIRY_DRAIN_INTERVAL_MS` or similar and add beside it), open the policy's **Payouts** tab, open the instalment, review as finance officer A ("Review payout" → "Record review"), then as finance officer B approve ("Approve payout" → "Pay TZS …"), and assert the receipt heading "Payout requested" and the note text.
3. **Two-person:** after A reviews, A opening the page sees the hard gate "A second person approves" failed and the "Approve payout" button disabled.
4. **Hold:** a policy with no premium collected shows its due instalment as "On hold" with the reason "Premiums are not paid up to the due date".

`e2e/staff-free-look.spec.ts`: as a staff user, request free-look on a fresh ACTIVE `END-MB-20` policy with one deduction; as a finance officer, approve; assert the policy's status badge reads "Cancelled free look" (the humanised literal `StatusBadge` produces — read `lib/status.ts`'s humaniser for the exact text) and the receipt "Refund requested, not yet paid".

Extend (never rewrite) `e2e/staff-products.spec.ts`: its existing TERM_LIFE publish now fills "Free-look days" with 15.

Run only these specs while building:

```bash
npx playwright test e2e/staff-payouts.spec.ts e2e/staff-free-look.spec.ts e2e/staff-products.spec.ts
```

(Memory: never with `--no-deps`, never concurrently with vitest or Maven; a failure mentioning an openid-connect auth navigation is stale auth, not a regression — re-run with deps.)

- [ ] **Step 8: Checks and commit**

```bash
npm run typecheck ; npm run lint ; npx vitest run
git add frontend backend/scripts/seed-dev-data.sh
git commit -m "feat(console): payment runs, maturities, free-look, payout schedule authoring, e2e (step 2, task 10)"
```

---

### Task 11: Gate, review, merge

- [ ] **Step 1: Stop the dev backend, then run the full backend suite once**

```bash
cd backend
./mvnw -o clean test > ../../step2-suite.log 2>&1 ; echo "MAVEN_EXIT=$?" >> ../../step2-suite.log
grep -E "Tests run: [0-9]+, Failures.*Skipped: [0-9]+$|<<< (FAILURE|ERROR)|BUILD|MAVEN_EXIT" ../../step2-suite.log | tail -30
```

Read `MAVEN_EXIT`, never a pipe's exit code. Expected: `BUILD SUCCESS`, `MAVEN_EXIT=0`. Any failure is diagnosed — "pre-existing" proves authorship, not harmlessness.

- [ ] **Step 2: Migrate the dev database and restart**

Apply, in order, against the dev database with the platform's normal psql route (`scripts/configure-db.sh` / the documented psql apply — memory: the dev backend and migrations are decoupled): `product/V18`, `benefitpayout/V1`, `policy/V31`, `payment/V8`. Restart the backend. Re-run `seed-dev-data.sh` only if the seed guard allows the new product to be added; otherwise publish `END-MB-20` through the console.

- [ ] **Step 3: Full real-stack e2e**

```bash
cd frontend
npx playwright test
```

Expected: all green. Failures disjoint across two runs are timing (memory); one test timed alone on both branches is the decisive check.

- [ ] **Step 4: Final whole-branch review**

Review `git diff main...product-step2-payouts` as a whole, looking specifically at the seams between tasks (memory: M5's systemic bugs all lived there): event payload keys published vs read (`PayoutRequested`, `PayoutPaid`, `ClaimApproved`, `PolicyMadePaidUp`, `PolicyCancelledFreeLook`); every new `@PreAuthorize`; every new status literal present in Java enum, SQL CHECK, OpenAPI enum and `lib/status.ts`; every refusal message identical in Java and in the frontend gates/schemas; the drains registered in `ScheduledJobsHealthIndicator`; the four new migrations present in `migrate.sh`/`configure-db.sh`'s order. Fix what it finds, re-run only the affected classes, then the suite once more if anything in shared infrastructure changed.

Also raise, for the user, the two step-1 findings recorded in the spec (§9 surrender approval gate; surrender payouts never reach the ledger) and the cash-value authoring gap — not fixed here.

- [ ] **Step 5: Merge**

The repo has no remote; "merge to main" is a local `--no-ff` merge:

```bash
git checkout main
git merge --no-ff product-step2-payouts -m "Merge product step 2: the payout engine"
git log --oneline -3
```

---

## Self-review notes (resolved while writing)

- **Spec coverage:** §3 → Task 1; §4 module/tables → Task 2; §5.1 → Tasks 3–4; §5.2 → Task 6; §6 maturity/ROP → Task 3, survival/death/paid-up/surrender/lapse → Task 5, free-look → Task 7, older-product MATURITY refusal → Task 5; §7 integration → Tasks 3–7; §8 refusals → every task's 422 tests; §9 access → controllers + contract 403 test; §10 frontend → Tasks 9–10; maturing report (§6 "Reports") → Task 8; §11 testing cadence → Global Constraints and every task's run step; §12 order → task order.
- **Deviations from the spec**, recorded in the spec's §14: review fields live on `payout_instalment` (no separate `payout_review` table); payouts post to `5100 Claims Expense` (no new account); free-look status change is published by policy as `policy.PolicyCancelledFreeLook` (benefitpayout does not publish `FreeLookCancelled`); MATURITY/ROP rows carry no years and pay on the policy's own maturity date; SURVIVAL/INCOME `amountValue` is per policy year; approve answers 202 with the instalment body; `party::api` is not a dependency (the reviewer enters the payee).
- **Type consistency checked:** `PayoutPlan`/`PayoutTerms`/`PayoutRowInput` names identical across Tasks 1–7 and the fixture; `ScheduleExpander.expand` is 4-argument everywhere; `BenefitPayoutApiImpl` method names used by listeners (`expandForIssuedPolicy`, `recordPremium`, `fallDue`, `markPaid`, `markFailed`, `cancelFuture`, `restoreAfterReinstatement`, `restateForPaidUp`, `prepareRun`, `suspendStream`, `markFreeLookRefunded`, `markFreeLookRefundFailed`) match their definitions; payload keys `instalmentId` / `cancellationId` / `idempotencyKey` / `purpose` / `amount` match between publisher (Tasks 3, 7) and payment's handler (Task 4).
