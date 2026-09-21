# Credit Life, Plan 1 — The Contract

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A credit-life scheme can be issued, its members carry their loan's parameters, and `claimableCover` returns a correctly decreasing sum assured on any date — with no CSV, no portal and no premium anywhere in this plan.

**Architecture:** Credit life is a `CREDIT_LIFE`-category master policy reusing the Build 5 group tables. A fourth `BenefitBasis` (`AMORTISING_LOAN`) says "this member's benefit comes from a loan, not from the scheme". Loan parameters live on `policy_member`; the balance is never materialised, it is computed on demand by a pure `AmortisationCalculator` and capped by the member's stored `covered_amount`. The interest method is not configured — it is inferred by fitting both formulas against the bank's own stated instalment.

**Tech Stack:** Java 21, Spring Boot / Spring Modulith, JPA, Flyway, Postgres 16 with RLS, JUnit 5, Testcontainers.

**Spec:** `backend/docs/superpowers/specs/2026-09-21-credit-life-design.md`. Sections cited per task.

## Revision 2026-09-21 — after reading the real client files

Two real schedules arrived after this plan was written (`sample data/`): LOLC → BUMACO Life
for June 2026 (~345 loans) and a BUMACO August template. They refute three things:

- **No `instalment_amount`, and no interest rate, in either file.** The method-inference
  mechanism (spec §2.5) cannot run against real data. **`inferMethod` is removed from
  Task 2**, along with `LoanTerms.instalmentAmount`, which existed only to feed it.
- **No loan account number.** Both lenders have been asked to add one. The template is ours
  and strictly so, so it stays a required column and a file without it is rejected.
- **Neither file carries an outstanding balance.** Both insure the disbursed amount. How
  cover declines — straight-line to zero, reducing-balance, or not at all — **is now an open
  client question**, so **Task 7 is BLOCKED and must not be started.**

What the files did settle: premium is a percent per annum on the disbursed amount
(LOLC 0.5%, BUMACO 0.6% — two lenders, two rates, confirming it belongs on the scheme),
and both carry gender and date of birth.

Tasks 1–6 and 8 are unaffected in substance. `outstandingPrincipalAt` keeps both methods
because whichever way A is answered, one of them is the answer: `FLAT_RATE` computes
`principal × (n−k)/n`, which **is** straight-line decline.

## Global Constraints

- **Money is `NUMERIC(19,2)` in SQL and `BigDecimal` in Java.** Never `double`. Compare with `compareTo`, never `equals`.
- **Every new table carries `tenant_id UUID NOT NULL`**, an RLS policy using the `NULLIF` idiom, and explicit `app_role` grants. This plan adds no new tables, only columns — but any new index must still be tenant-leading.
- **No `@Scheduled`, no background threads.** A thread with no `TenantContext` sees zero rows through RLS. Nothing in this plan needs one.
- **`allowedDependencies` uses the `module::api` form.** This plan adds no cross-module dependency.
- **Rounding is `HALF_UP` to 2 decimal places**, applied once at the end of a calculation, matching `GroupBenefitCalculator.benefitFor`.
- **Run Maven on the host** (`./mvnw`), never inside Docker — it breaks Testcontainers networking.
- **Stop the dev backend before any `clean test`.** `clean` deletes `target/` under the live JVM and produces ~20 bogus `NoClassDefFoundError` failures in untouched modules.
- **After any record or method signature change, run `./mvnw clean test-compile`.** Incremental compilation silently skips unchanged tests that no longer compile.
- **Tests that need Postgres use Testcontainers.** Pure-function tests use neither Spring nor a database — see `GroupBenefitCalculatorTest` as the model.

---

### Task 1: The `CREDIT_LIFE` product category

Spec §2 preamble, §4 item 14. Without this no credit-life product can be authored, so everything else is untestable.

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/ProductCategory.java`
- Create: `backend/db-migrations/product/V14__credit_life_category.sql`
- Modify: `frontend/src/types/api/policy.ts` (the mirrored wire enum)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/product/ProductCategoryMigrationTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ProductCategory.CREDIT_LIFE`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/tz/co/nlolo/lifeplatform/product/ProductCategoryMigrationTest.java`:

```java
package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The category enum and the DB CHECK constraint are two copies of one list. This asserts
 * the Java half; the migration's own test asserts the SQL half accepts the new value.
 */
class ProductCategoryMigrationTest {

    @Test
    void creditLifeIsAProductCategory() {
        assertEquals("CREDIT_LIFE", ProductCategory.valueOf("CREDIT_LIFE").name());
    }

    @Test
    void theCategoryListHasNotGrownUnexpectedly() {
        assertEquals(8, ProductCategory.values().length,
            "A new category must also be added to product.product_definition's CHECK "
            + "constraint and to frontend/src/types/api/policy.ts");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ProductCategoryMigrationTest`
Expected: FAIL — `IllegalArgumentException: No enum constant ... CREDIT_LIFE`

- [ ] **Step 3: Add the enum value**

`ProductCategory.java` becomes:

```java
package tz.co.nlolo.lifeplatform.product.api;

public enum ProductCategory {
    TERM_LIFE, ENDOWMENT, WHOLE_LIFE, ANNUITY, UNIT_LINKED, GROUP_LIFE, EDUCATION_SAVINGS,
    /**
     * Lender-driven cover on a borrower's life, paying the outstanding loan balance. The
     * only category whose sum assured decreases over the term -- see
     * docs/superpowers/specs/2026-09-21-credit-life-design.md.
     */
    CREDIT_LIFE
}
```

- [ ] **Step 4: Write the migration**

Create `backend/db-migrations/product/V14__credit_life_category.sql`:

```sql
-- Credit life: §4 of the client's underwriting requirements table.
--
-- The category list exists twice on purpose -- as product.api.ProductCategory and as this
-- CHECK -- because a migration cannot call Java. ProductCategoryMigrationTest asserts the
-- Java half has not grown without this file growing with it.

ALTER TABLE product.product_definition
    DROP CONSTRAINT IF EXISTS product_definition_category_check;

ALTER TABLE product.product_definition
    ADD CONSTRAINT product_definition_category_check
    CHECK (category IN ('TERM_LIFE','ENDOWMENT','WHOLE_LIFE','ANNUITY','UNIT_LINKED',
                        'GROUP_LIFE','EDUCATION_SAVINGS','CREDIT_LIFE'));
```

Note: confirm the existing constraint's exact name first with
`\d product.product_definition` against a Testcontainers instance or the dev DB; `V1__create_product_schema.sql:11-12` declares it inline, so Postgres named it automatically. If the name differs, use the real one — `DROP CONSTRAINT IF EXISTS` silently does nothing on a wrong name and would leave the old constraint in place.

- [ ] **Step 5: Mirror the wire enum**

In `frontend/src/types/api/policy.ts`, add `"CREDIT_LIFE"` to the `productCategory` union. Do **not** hand-edit generated sections — if the union is generated from `backend/api/openapi/openapi-product.yaml`, add the value to that spec's enum and regenerate.

- [ ] **Step 6: Run tests**

Run: `./mvnw clean test-compile && ./mvnw test -Dtest=ProductCategoryMigrationTest`
Then: `./mvnw test -Dtest=ProductApiIntegrationTest`
Expected: PASS. The integration test proves the migration applies cleanly against real Postgres.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/ProductCategory.java \
        backend/db-migrations/product/V14__credit_life_category.sql \
        backend/src/test/java/tz/co/nlolo/lifeplatform/product/ProductCategoryMigrationTest.java \
        frontend/src/types/api/policy.ts
git commit -m "feat(product): credit life is a product category"
```

---

### Task 2: The amortisation calculator

Spec §2.5 and §2.4. A pure function over plain values, like `GroupBenefitCalculator` — no database, no Spring. This is the mathematical heart of the product and it is fully testable in isolation, so it comes before any persistence.

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/InterestMethod.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/RepaymentFrequency.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/LoanTerms.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/domain/AmortisationCalculator.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/AmortisationCalculatorTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum InterestMethod { REDUCING_BALANCE, FLAT_RATE }`
  - `enum RepaymentFrequency { MONTHLY, QUARTERLY }` with `int periodsPerYear()` and `int monthsPerPeriod()`
  - `record LoanTerms(BigDecimal principalAmount, BigDecimal annualInterestRatePercent, int termMonths, RepaymentFrequency repaymentFrequency, LocalDate disbursementDate, LocalDate firstRepaymentDate)`
  - `AmortisationCalculator.instalmentFor(LoanTerms, InterestMethod) -> BigDecimal`
  - `AmortisationCalculator.outstandingPrincipalAt(LoanTerms, InterestMethod, LocalDate) -> BigDecimal`

**No `inferMethod` and no `instalmentAmount`** — see the revision note. Neither real client
file carries an instalment, so inference has nothing to fit against. Both methods stay
because `FLAT_RATE` is straight-line decline, which is the likely answer to question A.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/AmortisationCalculatorTest.java`:

```java
package tz.co.nlolo.lifeplatform.policy;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.policy.api.InterestMethod;
import tz.co.nlolo.lifeplatform.policy.api.LoanTerms;
import tz.co.nlolo.lifeplatform.policy.api.RepaymentFrequency;
import tz.co.nlolo.lifeplatform.policy.domain.AmortisationCalculator;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one place a credit-life balance is decided, so it is tested as a pure function with
 * no database and no Spring context -- the same reasoning as GroupBenefitCalculatorTest.
 *
 * <p>Expected instalments were computed independently of the implementation from the
 * standard annuity and flat-rate formulas; they are fixtures, not outputs.
 */
class AmortisationCalculatorTest {

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }

    /** 8,500,000 TZS at 18.5% over 48 monthly instalments, disbursed 2026-08-03. */
    private static LoanTerms loan() {
        return new LoanTerms(money("8500000.00"), money("18.50"), 48,
            RepaymentFrequency.MONTHLY,
            LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 3));
    }

    /** LOLC's real shape: 10,400,000 over 18 months, disbursed 2026-06-30, no rate given. */
    private static LoanTerms lolcLoan() {
        return new LoanTerms(money("10400000.00"), BigDecimal.ZERO, 18,
            RepaymentFrequency.MONTHLY,
            LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 30));
    }

    // ---- instalmentFor ------------------------------------------------------

    @Test
    void reducingBalanceInstalmentMatchesTheAnnuityFormula() {
        BigDecimal actual = AmortisationCalculator.instalmentFor(
            loan(), InterestMethod.REDUCING_BALANCE);
        assertEquals(0, money("251913.79").compareTo(actual), "was " + actual);
    }

    @Test
    void flatRateInstalmentSpreadsPrincipalPlusTotalInterestEvenly() {
        BigDecimal actual = AmortisationCalculator.instalmentFor(
            loan(), InterestMethod.FLAT_RATE);
        assertEquals(0, money("308125.00").compareTo(actual), "was " + actual);
    }

    @Test
    void theTwoMethodsAreNeverCloseEnoughToConfuse() {
        // Kept from the withdrawn inference design because the property still matters: if
        // these ever converge, any future attempt to tell them apart from a lender's own
        // instalment is unsafe. 251913.79 vs 308125.00 is a 22% gap.
        BigDecimal reducing = AmortisationCalculator.instalmentFor(loan(), InterestMethod.REDUCING_BALANCE);
        BigDecimal flat = AmortisationCalculator.instalmentFor(loan(), InterestMethod.FLAT_RATE);
        BigDecimal gap = flat.subtract(reducing).abs()
            .divide(reducing, 4, java.math.RoundingMode.HALF_UP);
        assertTrue(gap.compareTo(new BigDecimal("0.10")) > 0, "gap was " + gap);
    }

    // ---- outstandingPrincipalAt ---------------------------------------------

    @Test
    void balanceBeforeTheFirstRepaymentIsTheFullPrincipal() {
        BigDecimal actual = AmortisationCalculator.outstandingPrincipalAt(
            loan(), InterestMethod.REDUCING_BALANCE, LocalDate.of(2026, 8, 20));
        assertEquals(0, money("8500000.00").compareTo(actual), "was " + actual);
    }

    @Test
    void aMoratoriumHoldsTheBalanceAtTheFullPrincipal() {
        // Disbursed 2026-08-07, first repayment 2026-11-07: a three-month holiday.
        LoanTerms moratorium = new LoanTerms(money("45000000.00"), money("15.50"), 120,
            RepaymentFrequency.MONTHLY,
            LocalDate.of(2026, 8, 7), LocalDate.of(2026, 11, 7));
        BigDecimal actual = AmortisationCalculator.outstandingPrincipalAt(
            moratorium, InterestMethod.REDUCING_BALANCE, LocalDate.of(2026, 10, 31));
        assertEquals(0, money("45000000.00").compareTo(actual), "was " + actual);
    }

    @Test
    void flatRateRepaysPrincipalInEqualSlices() {
        // 48 instalments, 24 paid by 2028-08-03: exactly half the principal left.
        // This IS straight-line decline, and is therefore the likely answer to the open
        // question of how credit-life cover falls.
        BigDecimal actual = AmortisationCalculator.outstandingPrincipalAt(
            loan(), InterestMethod.FLAT_RATE, LocalDate.of(2028, 8, 3));
        assertEquals(0, money("4250000.00").compareTo(actual), "was " + actual);
    }

    @Test
    void straightLineDeclineNeedsNoInterestRateAtAll() {
        // LOLC's file gives no rate. FLAT_RATE's principal decline does not read one, so a
        // zero rate still produces a correct straight line: 10,400,000 over 18 months,
        // 9 paid by 2027-04-30, exactly half left.
        BigDecimal actual = AmortisationCalculator.outstandingPrincipalAt(
            lolcLoan(), InterestMethod.FLAT_RATE, LocalDate.of(2027, 3, 30));
        assertEquals(0, money("5200000.00").compareTo(actual), "was " + actual);
    }

    @Test
    void reducingBalanceRepaysPrincipalSlowlyAtFirst() {
        // After 24 of 48 payments a reducing-balance loan still owes MORE than half.
        BigDecimal actual = AmortisationCalculator.outstandingPrincipalAt(
            loan(), InterestMethod.REDUCING_BALANCE, LocalDate.of(2028, 8, 3));
        assertTrue(actual.compareTo(money("4250000.00")) > 0,
            "reducing balance should exceed the flat-rate midpoint, was " + actual);
        assertTrue(actual.compareTo(money("5000000.00")) < 0, "was " + actual);
    }

    @Test
    void balanceAtTheEndOfTheTermIsZeroAndNeverNegative() {
        for (InterestMethod method : InterestMethod.values()) {
            BigDecimal atMaturity = AmortisationCalculator.outstandingPrincipalAt(
                loan(), method, LocalDate.of(2030, 9, 3));
            assertEquals(0, BigDecimal.ZERO.compareTo(atMaturity), method + " was " + atMaturity);

            BigDecimal wellAfter = AmortisationCalculator.outstandingPrincipalAt(
                loan(), method, LocalDate.of(2040, 1, 1));
            assertEquals(0, BigDecimal.ZERO.compareTo(wellAfter), method + " was " + wellAfter);
        }
    }

    @Test
    void quarterlyRepaymentsCountQuarters() {
        LoanTerms quarterly = new LoanTerms(money("12750000.00"), money("19.00"), 36,
            RepaymentFrequency.QUARTERLY,
            LocalDate.of(2026, 8, 10), LocalDate.of(2026, 11, 10));
        BigDecimal afterOne = AmortisationCalculator.outstandingPrincipalAt(
            quarterly, InterestMethod.REDUCING_BALANCE, LocalDate.of(2026, 11, 10));
        assertTrue(afterOne.compareTo(money("12750000.00")) < 0, "was " + afterOne);
        BigDecimal afterFour = AmortisationCalculator.outstandingPrincipalAt(
            quarterly, InterestMethod.FLAT_RATE, LocalDate.of(2027, 8, 10));
        assertEquals(0, money("8500000.00").compareTo(afterFour), "was " + afterFour);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AmortisationCalculatorTest`
Expected: FAIL — compilation error, `LoanTerms` and `AmortisationCalculator` do not exist.

- [ ] **Step 3: Write the value types**

`backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/InterestMethod.java`:

```java
package tz.co.nlolo.lifeplatform.policy.api;

/**
 * How a lender charges interest, which decides how fast the principal actually falls.
 *
 * <p>Never configured by a caller. It is inferred from the instalment the bank itself
 * states -- see {@code AmortisationCalculator.inferMethod} and spec §2.5.
 */
public enum InterestMethod {
    /** Interest on the outstanding balance. The standard annuity. */
    REDUCING_BALANCE,
    /** Interest on the ORIGINAL principal for the whole term, common in Tanzanian lending. */
    FLAT_RATE
}
```

`RepaymentFrequency.java`:

```java
package tz.co.nlolo.lifeplatform.policy.api;

public enum RepaymentFrequency {
    MONTHLY(12, 1),
    QUARTERLY(4, 3);

    private final int periodsPerYear;
    private final int monthsPerPeriod;

    RepaymentFrequency(int periodsPerYear, int monthsPerPeriod) {
        this.periodsPerYear = periodsPerYear;
        this.monthsPerPeriod = monthsPerPeriod;
    }

    public int periodsPerYear() { return periodsPerYear; }
    public int monthsPerPeriod() { return monthsPerPeriod; }
}
```

`LoanTerms.java`:

```java
package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Everything about one loan that the insurer needs, and nothing it does not.
 *
 * <p>There is no interest-method field: the method belongs to the lender, not the loan, so
 * it is configured once on the scheme. There is no instalment field either -- neither real
 * client schedule carries one, see the revision note on this plan.
 *
 * @param annualInterestRatePercent may be zero. Both real lenders' files omit the rate
 *     entirely, and straight-line decline does not read it.
 * @param firstRepaymentDate later than one period after disbursement means a moratorium.
 *     No separate field expresses one.
 */
public record LoanTerms(BigDecimal principalAmount, BigDecimal annualInterestRatePercent,
                         int termMonths, RepaymentFrequency repaymentFrequency,
                         LocalDate disbursementDate, LocalDate firstRepaymentDate) {

    public LoanTerms {
        if (principalAmount == null || principalAmount.signum() <= 0) {
            throw new IllegalArgumentException("A loan must have a positive principal");
        }
        if (annualInterestRatePercent == null || annualInterestRatePercent.signum() < 0) {
            throw new IllegalArgumentException("A loan's interest rate may not be negative");
        }
        if (termMonths <= 0) {
            throw new IllegalArgumentException("A loan must have a positive term");
        }
        if (repaymentFrequency == null) {
            throw new IllegalArgumentException("A loan must state its repayment frequency");
        }
        if (termMonths % repaymentFrequency.monthsPerPeriod() != 0) {
            throw new IllegalArgumentException(
                "A " + termMonths + "-month term does not divide into "
                + repaymentFrequency + " periods");
        }
        if (disbursementDate == null || firstRepaymentDate == null) {
            throw new IllegalArgumentException("A loan must carry both of its dates");
        }
        if (firstRepaymentDate.isBefore(disbursementDate)) {
            throw new IllegalArgumentException("A loan cannot be repaid before it is disbursed");
        }
    }

    /** How many instalments the schedule contains. */
    public int numberOfInstalments() {
        return termMonths / repaymentFrequency.monthsPerPeriod();
    }
}
```

- [ ] **Step 4: Write the calculator**

`backend/src/main/java/tz/co/nlolo/lifeplatform/policy/domain/AmortisationCalculator.java`:

```java
package tz.co.nlolo.lifeplatform.policy.domain;

import tz.co.nlolo.lifeplatform.policy.api.InterestMethod;
import tz.co.nlolo.lifeplatform.policy.api.LoanTerms;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The one place a credit-life sum assured is decided.
 *
 * <p>A pure function over plain values, deliberately -- the same reasoning as
 * {@link GroupBenefitCalculator}: this decides what a borrower's estate is worth to a
 * lender, and it must be exercisable against fixtures with no database and no Spring.
 *
 * <p><b>Nothing here is stored.</b> Spec §2.4: materialising one row per repayment date
 * would be 24,000 rows for a 400-borrower file of 60-month loans, on a table built for
 * occasional restatement. The computed figure IS snapshotted onto a claim at registration,
 * but that is the claim's job, not this class's.
 */
public final class AmortisationCalculator {

    /** Working precision for the intermediate powers; results are rounded to 2dp. */
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private AmortisationCalculator() {}

    /**
     * The instalment this loan would carry under a given method.
     *
     * <p>Reducing balance is the standard annuity, {@code P*r / (1 - (1+r)^-n)}. Flat rate
     * spreads principal plus total interest evenly, where total interest is charged on the
     * ORIGINAL principal for the whole term.
     */
    public static BigDecimal instalmentFor(LoanTerms terms, InterestMethod method) {
        int n = terms.numberOfInstalments();
        BigDecimal principal = terms.principalAmount();

        return switch (method) {
            case FLAT_RATE -> {
                BigDecimal years = BigDecimal.valueOf(terms.termMonths())
                    .divide(BigDecimal.valueOf(12), MC);
                BigDecimal interest = principal
                    .multiply(terms.annualInterestRatePercent().divide(HUNDRED, MC), MC)
                    .multiply(years, MC);
                yield principal.add(interest)
                    .divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
            }
            case REDUCING_BALANCE -> {
                BigDecimal r = periodicRate(terms);
                if (r.signum() == 0) {
                    yield principal.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
                }
                BigDecimal growth = onePlus(r).pow(n, MC);
                // P*r / (1 - (1+r)^-n)  ==  P*r*growth / (growth - 1)
                yield principal.multiply(r, MC).multiply(growth, MC)
                    .divide(growth.subtract(BigDecimal.ONE), 2, RoundingMode.HALF_UP);
            }
        };
    }

    /**
     * The principal still owed on a given date -- which is what the lender loses, and
     * therefore what credit life insures.
     *
     * <p>Before the first repayment the full principal is outstanding, so a moratorium
     * needs no special case. After the last, zero; the result is never negative.
     */
    public static BigDecimal outstandingPrincipalAt(LoanTerms terms, InterestMethod method,
                                                     LocalDate asOf) {
        int n = terms.numberOfInstalments();
        int paid = instalmentsPaidBy(terms, asOf);
        if (paid <= 0) return terms.principalAmount().setScale(2, RoundingMode.HALF_UP);
        if (paid >= n) return BigDecimal.ZERO.setScale(2);

        BigDecimal principal = terms.principalAmount();
        BigDecimal balance = switch (method) {
            // Flat rate repays principal in n equal slices; the interest is a separate,
            // fixed charge that does not change what is owed on the capital.
            case FLAT_RATE -> principal
                .multiply(BigDecimal.valueOf(n - (long) paid), MC)
                .divide(BigDecimal.valueOf(n), MC);
            // B_k = P*(1+r)^k - I*((1+r)^k - 1)/r
            case REDUCING_BALANCE -> {
                BigDecimal r = periodicRate(terms);
                if (r.signum() == 0) {
                    yield principal.multiply(BigDecimal.valueOf(n - (long) paid), MC)
                        .divide(BigDecimal.valueOf(n), MC);
                }
                BigDecimal instalment = instalmentFor(terms, InterestMethod.REDUCING_BALANCE);
                BigDecimal growth = onePlus(r).pow(paid, MC);
                yield principal.multiply(growth, MC)
                    .subtract(instalment.multiply(growth.subtract(BigDecimal.ONE), MC)
                        .divide(r, MC), MC);
            }
        };

        BigDecimal rounded = balance.setScale(2, RoundingMode.HALF_UP);
        return rounded.signum() < 0 ? BigDecimal.ZERO.setScale(2) : rounded;
    }

    /** How many scheduled repayment dates fall on or before {@code asOf}. */
    private static int instalmentsPaidBy(LoanTerms terms, LocalDate asOf) {
        if (asOf.isBefore(terms.firstRepaymentDate())) return 0;
        long monthsSinceFirst = ChronoUnit.MONTHS.between(terms.firstRepaymentDate(), asOf);
        int perPeriod = terms.repaymentFrequency().monthsPerPeriod();
        long paid = monthsSinceFirst / perPeriod + 1;
        return (int) Math.min(paid, terms.numberOfInstalments());
    }

    private static BigDecimal periodicRate(LoanTerms terms) {
        return terms.annualInterestRatePercent()
            .divide(HUNDRED, MC)
            .divide(BigDecimal.valueOf(terms.repaymentFrequency().periodsPerYear()), MC);
    }

    private static BigDecimal onePlus(BigDecimal r) {
        return BigDecimal.ONE.add(r);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw test -Dtest=AmortisationCalculatorTest`
Expected: PASS, all 9 tests.

If `reducingBalanceInstalmentMatchesTheAnnuityFormula` is off by a cent or two, **do not adjust the fixture** — the fixtures were derived from the formula independently. Widen `MC` precision instead and re-run; a rounding difference at 20 significant figures means the implementation is accumulating error.

- [ ] **Step 6: Negative control**

Temporarily change `FLAT_RATE`'s branch in `outstandingPrincipalAt` to return `principal` unchanged. Re-run. Expected: `flatRateRepaysPrincipalInEqualSlices` and `straightLineDeclineNeedsNoInterestRateAtAll` both fail, and `balanceAtTheEndOfTheTermIsZeroAndNeverNegative` fails for `FLAT_RATE`. Restore and confirm green. This proves the straight-line decline is actually computed rather than falling out of a rounding coincidence — and straight-line is the branch most likely to become the product's real basis.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/InterestMethod.java \
        backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/RepaymentFrequency.java \
        backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/LoanTerms.java \
        backend/src/main/java/tz/co/nlolo/lifeplatform/policy/domain/AmortisationCalculator.java \
        backend/src/test/java/tz/co/nlolo/lifeplatform/policy/AmortisationCalculatorTest.java
git commit -m "feat(policy): a loan's outstanding principal on any date"
```

---

### Task 3: Freeform members

Spec §2.2. Specified in `plans/2026-09-10-group-scheme-substitution-and-notices.md:256` and never built. Four hundred registered parties per file would mean four hundred `kyc_status='PENDING'` rows in the staff Clients queue.

**Files:**
- Create: `backend/db-migrations/policy/V13__freeform_members.sql`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/MemberType.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/domain/PolicyMember.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/PolicyApi.java:128` (`MemberInput`)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/PolicyMemberView.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java` (member construction and `listMembers` name search)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/FreeformMemberIntegrationTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `enum MemberType { PARTY, FREEFORM }`
  - `MemberInput` becomes `record MemberInput(MemberType memberType, UUID memberPartyId, String memberName, LocalDate memberDateOfBirth, String gradeCode, BigDecimal salaryAmount, LocalDate joinedOn)`
  - `PolicyMember` gains `getMemberType()`, `getMemberName()`, `getMemberDateOfBirth()`, and a second constructor taking a freeform designation.

**This is a breaking signature change.** Every existing caller of `MemberInput` — `issueGroupScheme`, `addMember`, `IssueGroupSchemePage.tsx`, every group test fixture — must be updated. Run `./mvnw clean test-compile` after Step 3, not just `test`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/FreeformMemberIntegrationTest.java`. Model it on the existing group integration test (`GroupClaimIntegrationTest` is the nearest) for Testcontainers and tenant setup. The behaviours to assert:

```java
@Test
void aFreeformMemberIsCoveredWithoutBeingRegisteredAsAParty() {
    // issue a FLAT GROUP_LIFE scheme, then:
    PolicyMemberView member = policyApi.addMember(policyNumber,
        new PolicyApi.MemberInput(MemberType.FREEFORM, null, "Amina Hassan Mwinyi",
            LocalDate.of(1988, 3, 14), null, null, LocalDate.of(2026, 8, 3)),
        "staff.underwriter");

    assertEquals(MemberType.FREEFORM, member.memberType());
    assertEquals("Amina Hassan Mwinyi", member.memberName());
    assertNull(member.memberPartyId());
}

@Test
void aPartyMemberStillWorksExactlyAsBefore() {
    PolicyMemberView member = policyApi.addMember(policyNumber,
        new PolicyApi.MemberInput(MemberType.PARTY, realPartyId, null, null,
            null, null, LocalDate.of(2026, 8, 3)),
        "staff.underwriter");

    assertEquals(MemberType.PARTY, member.memberPartyId() != null
        ? MemberType.PARTY : MemberType.FREEFORM);
    assertEquals(realPartyId, member.memberPartyId());
    assertNull(member.memberName());
}

@Test
void aMemberNamingBothAPartyAndAFreeformNameIsRefused() {
    assertThrows(InvalidPolicyStateException.class, () -> policyApi.addMember(policyNumber,
        new PolicyApi.MemberInput(MemberType.PARTY, realPartyId, "Amina Hassan Mwinyi",
            LocalDate.of(1988, 3, 14), null, null, LocalDate.of(2026, 8, 3)),
        "staff.underwriter"));
}

@Test
void aFreeformMemberWithNoNameIsRefused() {
    assertThrows(InvalidPolicyStateException.class, () -> policyApi.addMember(policyNumber,
        new PolicyApi.MemberInput(MemberType.FREEFORM, null, "  ", null,
            null, null, LocalDate.of(2026, 8, 3)),
        "staff.underwriter"));
}

@Test
void nameSearchFindsFreeformMembersAsWellAsRegisteredOnes() {
    // add one of each named "Amina", then:
    Page<PolicyMemberView> found = policyApi.listMembers(
        policyNumber, null, "Amina", PageRequest.of(0, 20));
    assertEquals(2, found.getTotalElements());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=FreeformMemberIntegrationTest`
Expected: FAIL — compilation error, `MemberType` does not exist and `MemberInput` has four components.

- [ ] **Step 3: Write the migration**

Create `backend/db-migrations/policy/V13__freeform_members.sql`:

```sql
-- A member may be named without being registered as a party.
--
-- Specified in plans/2026-09-10-group-scheme-substitution-and-notices.md and built here
-- because credit life needs it first: 400 borrowers per file would otherwise be 400
-- kyc_status='PENDING' rows in the staff Clients queue, for people the insurer has no
-- reason to identify until somebody dies. See spec §2.2.
--
-- Mirrors chk_beneficiary_exactly_one_designation: a member is one thing or the other,
-- never both and never neither.

ALTER TABLE policy.policy_member
    ADD COLUMN member_type           VARCHAR(10) NOT NULL DEFAULT 'PARTY'
        CHECK (member_type IN ('PARTY','FREEFORM')),
    ADD COLUMN member_name           VARCHAR(200),
    ADD COLUMN member_date_of_birth  DATE;

-- Every existing row is a PARTY member; the default above has already said so. Drop it so
-- new rows must state their type rather than inheriting a guess.
ALTER TABLE policy.policy_member ALTER COLUMN member_type DROP DEFAULT;

ALTER TABLE policy.policy_member ALTER COLUMN member_party_id DROP NOT NULL;

ALTER TABLE policy.policy_member
    ADD CONSTRAINT chk_policy_member_exactly_one_designation CHECK (
        (member_type = 'PARTY'    AND member_party_id IS NOT NULL
                                  AND member_name IS NULL AND member_date_of_birth IS NULL)
     OR (member_type = 'FREEFORM' AND member_party_id IS NULL
                                  AND member_name IS NOT NULL)
    );

-- ux_policy_member_active keyed on member_party_id, which is now nullable -- and in
-- Postgres a NULL never collides, so 400 freeform members would all "pass" a uniqueness
-- check that is no longer checking anything. Replace it with two partial indexes, each
-- guarding the designation it can actually see.
DROP INDEX IF EXISTS policy.ux_policy_member_active;

CREATE UNIQUE INDEX ux_policy_member_active_party
    ON policy.policy_member (policy_number, member_party_id)
    WHERE status = 'ACTIVE' AND member_party_id IS NOT NULL;

-- Freeform members have no stable identity of their own. Credit life gives them one in
-- V14 (the loan account number) and indexes it there; until then two people with the same
-- name on one scheme is legitimate -- a scheme may cover a father and son alike.
CREATE INDEX idx_policy_member_freeform_name
    ON policy.policy_member (tenant_id, policy_number, member_name)
    WHERE status = 'ACTIVE' AND member_name IS NOT NULL;
```

- [ ] **Step 4: Write the Java**

`MemberType.java`:

```java
package tz.co.nlolo.lifeplatform.policy.api;

/**
 * Whether a member is a registered party or just a name on a schedule.
 *
 * <p>FREEFORM exists because a group premium is not individually rated: a name plus the
 * scheme's basis values a member completely. Registering 500 employees -- or 400 borrowers
 * -- as parties would fill the KYC queue with people nobody needs to identify unless they
 * die. See spec §2.2.
 */
public enum MemberType { PARTY, FREEFORM }
```

In `PolicyApi.java`, replace the `MemberInput` record at line 128:

```java
    /**
     * One life, on the opening schedule or joining later.
     *
     * @param memberType PARTY names a registered party; FREEFORM names a person who is not
     *     one. Exactly one designation may be supplied -- see
     *     {@code chk_policy_member_exactly_one_designation}.
     * @param memberName required on FREEFORM, rejected on PARTY.
     * @param memberDateOfBirth optional even on FREEFORM at the group level; credit life
     *     requires it separately, because it tests entry age against the product's bounds.
     * @param gradeCode required on a GRADED scheme, and rejected on any other -- a grade
     *     on a flat scheme is a caller who believes something about the contract that is
     *     not true.
     * @param salaryAmount required on a SALARY_MULTIPLE scheme, and rejected on any other.
     * @param joinedOn when cover starts for this member. Null means the scheme's
     *     commencement date, which is what an opening-schedule row means.
     */
    record MemberInput(MemberType memberType, UUID memberPartyId, String memberName,
                        LocalDate memberDateOfBirth, String gradeCode,
                        BigDecimal salaryAmount, LocalDate joinedOn) {}
```

In `PolicyMember.java`, add the three fields, a `MemberType` enum mapping, and a static factory per designation:

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "member_type", nullable = false)
    private MemberType memberType;

    @Column(name = "member_name")
    private String memberName;

    @Column(name = "member_date_of_birth")
    private LocalDate memberDateOfBirth;
```

Change `member_party_id` to `@Column(name = "member_party_id")` (drop `nullable = false`). Keep the existing constructor delegating to a new private one with `MemberType.PARTY`, and add:

```java
    /** A member who is a registered party. */
    public static PolicyMember ofParty(UUID tenantId, String policyNumber, UUID memberPartyId,
                                        String gradeCode, LocalDate joinedOn,
                                        MemberUnderwritingStatus underwritingStatus,
                                        String createdBy) {
        if (memberPartyId == null) {
            throw new IllegalArgumentException("A PARTY member must name a party");
        }
        PolicyMember member = new PolicyMember(tenantId, policyNumber, memberPartyId, gradeCode,
            joinedOn, underwritingStatus, createdBy);
        member.memberType = MemberType.PARTY;
        return member;
    }

    /** A member named on a schedule who is not a registered party. */
    public static PolicyMember ofFreeform(UUID tenantId, String policyNumber, String memberName,
                                           LocalDate memberDateOfBirth, String gradeCode,
                                           LocalDate joinedOn,
                                           MemberUnderwritingStatus underwritingStatus,
                                           String createdBy) {
        if (memberName == null || memberName.isBlank()) {
            throw new IllegalArgumentException("A FREEFORM member must have a name");
        }
        PolicyMember member = new PolicyMember(tenantId, policyNumber, null, gradeCode,
            joinedOn, underwritingStatus, createdBy);
        member.memberType = MemberType.FREEFORM;
        member.memberName = memberName.trim();
        member.memberDateOfBirth = memberDateOfBirth;
        return member;
    }

    public MemberType getMemberType() { return memberType; }
    public String getMemberName() { return memberName; }
    public LocalDate getMemberDateOfBirth() { return memberDateOfBirth; }
```

Add `memberType`, `memberName` and `memberDateOfBirth` to `PolicyMemberView`.

In `PolicyApiImpl`, the member-construction site chooses the factory on `input.memberType()`, and `listMembers`'s name search ORs the party-id filter with a `member_name ILIKE` predicate — `PolicyApi.java:206-209` explains why the party-id resolution exists; freeform members need the other half of that OR.

- [ ] **Step 5: Fix every broken caller**

Run: `./mvnw clean test-compile`
Expected: FAIL initially, with a list of every `new MemberInput(...)` call site. Update each — existing group callers become `new MemberInput(MemberType.PARTY, partyId, null, null, gradeCode, salary, joinedOn)`. Also update `frontend/src/features/policies/IssueGroupSchemePage.tsx` and any generated request type.

Re-run until it compiles clean.

- [ ] **Step 6: Run the tests**

Run: `./mvnw test -Dtest=FreeformMemberIntegrationTest`
Then the whole group surface, which this change could break invisibly:
Run: `./mvnw test -Dtest='*Group*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/db-migrations/policy/V13__freeform_members.sql \
        backend/src/main/java/tz/co/nlolo/lifeplatform/policy/ \
        backend/src/test/java/tz/co/nlolo/lifeplatform/policy/FreeformMemberIntegrationTest.java \
        frontend/src/
git commit -m "feat(policy): a member can be a name instead of a registered party"
```

---

### Task 4: The credit-life scheme and its loan parameters

Spec §2.1 and §2.5. Adds the fourth benefit basis, the scheme's inferred interest method, and the loan columns on the member.

**Files:**
- Create: `backend/db-migrations/policy/V14__credit_life_scheme.sql`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/BenefitBasis.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/domain/GroupBenefitCalculator.java:44-56`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/domain/GroupScheme.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/domain/PolicyMember.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/GroupBenefitCalculatorTest.java` (extend)

**Interfaces:**
- Consumes: `LoanTerms`, `InterestMethod` (Task 2); `MemberType` (Task 3).
- Produces: `BenefitBasis.AMORTISING_LOAN`; `GroupScheme.getInterestMethod()`; `PolicyMember.getLoanTerms()` returning `LoanTerms` or null, and `PolicyMember.getLoanAccountNumber()`.

- [ ] **Step 1: Write the failing test**

Append to `GroupBenefitCalculatorTest`:

```java
    @Test
    void anAmortisingLoanMemberIsWorthTheirPrincipalAtInception() {
        BigDecimal benefit = GroupBenefitCalculator.benefitFor(
            BenefitBasis.AMORTISING_LOAN, null, null, null, null, money("8500000.00"));
        assertEquals(0, money("8500000.00").compareTo(benefit));
    }

    @Test
    void anAmortisingLoanSchemeWithNoPrincipalCannotValueAnybody() {
        assertThrows(IllegalArgumentException.class, () -> GroupBenefitCalculator.benefitFor(
            BenefitBasis.AMORTISING_LOAN, null, null, null, null, null));
    }

    @Test
    void aLoanAboveTheFreeCoverLimitIsCappedAtIt() {
        // Spec §2.7: 30m loan against a 25m FCL is 25m of real cover, not zero and not 30m.
        var valuation = GroupBenefitCalculator.evaluate(money("30000000.00"), money("25000000.00"));
        assertEquals(0, money("30000000.00").compareTo(valuation.benefitAmount()));
        assertEquals(0, money("25000000.00").compareTo(valuation.coveredAmount()));
        assertEquals(MemberUnderwritingStatus.EVIDENCE_REQUIRED, valuation.underwritingStatus());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=GroupBenefitCalculatorTest`
Expected: FAIL — `AMORTISING_LOAN` does not exist and `benefitFor` takes five arguments.

- [ ] **Step 3: Write the migration**

Create `backend/db-migrations/policy/V14__credit_life_scheme.sql`:

```sql
-- Credit life: a scheme whose members are LOANS, and whose cover falls as they are repaid.
-- Spec §2.1, §2.5. Requires V13 (freeform members) -- a borrower is a name, not a party.

-- A fourth basis. AMORTISING_LOAN carries no scheme-level parameter, exactly as GRADED
-- does not: the amount comes from the member's own loan.
ALTER TABLE policy.group_scheme DROP CONSTRAINT group_scheme_basis_parameter_present;

ALTER TABLE policy.group_scheme DROP CONSTRAINT group_scheme_benefit_basis_check;
ALTER TABLE policy.group_scheme
    ADD CONSTRAINT group_scheme_benefit_basis_check
    CHECK (benefit_basis IN ('FLAT','SALARY_MULTIPLE','GRADED','AMORTISING_LOAN'));

ALTER TABLE policy.group_scheme
    ADD CONSTRAINT group_scheme_basis_parameter_present CHECK (
        (benefit_basis = 'FLAT'            AND flat_benefit_amount IS NOT NULL AND salary_multiple IS NULL)
     OR (benefit_basis = 'SALARY_MULTIPLE' AND salary_multiple     IS NOT NULL AND flat_benefit_amount IS NULL)
     OR (benefit_basis = 'GRADED'          AND flat_benefit_amount IS NULL     AND salary_multiple IS NULL)
     OR (benefit_basis = 'AMORTISING_LOAN' AND flat_benefit_amount IS NULL     AND salary_multiple IS NULL)
    );

-- How this lender's loans repay principal, configured once per scheme from the agreement.
--
-- Originally this was to be INFERRED from each member's own instalment. Neither real
-- client schedule carries an instalment or even an interest rate, so there is nothing to
-- infer from and it is stated instead -- see this plan's revision note. FLAT_RATE's
-- principal decline is a straight line, which needs no rate at all.
--
-- Nullable only until question A is answered; make it NOT NULL in the migration that
-- resolves Task 7, once it is known whether cover declines at all.
ALTER TABLE policy.group_scheme
    ADD COLUMN interest_method VARCHAR(20)
        CHECK (interest_method IS NULL OR interest_method IN ('REDUCING_BALANCE','FLAT_RATE'));

-- A member of a credit-life scheme is a LOAN. Every column below is null on an ordinary
-- group member and required together on a credit-life one.
ALTER TABLE policy.policy_member
    ADD COLUMN loan_account_number          VARCHAR(50),
    ADD COLUMN loan_principal_amount        NUMERIC(19,2)
        CHECK (loan_principal_amount IS NULL OR loan_principal_amount > 0),
    ADD COLUMN loan_annual_rate_percent     NUMERIC(6,3)
        CHECK (loan_annual_rate_percent IS NULL OR loan_annual_rate_percent >= 0),
    ADD COLUMN loan_term_months             INTEGER
        CHECK (loan_term_months IS NULL OR loan_term_months > 0),
    ADD COLUMN loan_repayment_frequency     VARCHAR(20)
        CHECK (loan_repayment_frequency IS NULL
               OR loan_repayment_frequency IN ('MONTHLY','QUARTERLY')),
    ADD COLUMN loan_instalment_amount       NUMERIC(19,2)
        CHECK (loan_instalment_amount IS NULL OR loan_instalment_amount > 0),
    ADD COLUMN loan_disbursement_date       DATE,
    ADD COLUMN loan_first_repayment_date    DATE;

-- All or nothing. A member with a principal but no term would produce a schedule the
-- application would have to guess at, and guessing puts a fabricated death benefit on a
-- real contract -- the same reasoning as group_scheme_basis_parameter_present.
ALTER TABLE policy.policy_member
    ADD CONSTRAINT chk_policy_member_loan_complete CHECK (
        (loan_account_number IS NULL AND loan_principal_amount IS NULL
            AND loan_annual_rate_percent IS NULL AND loan_term_months IS NULL
            AND loan_repayment_frequency IS NULL AND loan_instalment_amount IS NULL
            AND loan_disbursement_date IS NULL AND loan_first_repayment_date IS NULL)
     OR (loan_account_number IS NOT NULL AND loan_principal_amount IS NOT NULL
            AND loan_annual_rate_percent IS NOT NULL AND loan_term_months IS NOT NULL
            AND loan_repayment_frequency IS NOT NULL AND loan_instalment_amount IS NOT NULL
            AND loan_disbursement_date IS NOT NULL AND loan_first_repayment_date IS NOT NULL)
    );

ALTER TABLE policy.policy_member
    ADD CONSTRAINT chk_policy_member_repaid_after_disbursed CHECK (
        loan_first_repayment_date IS NULL
        OR loan_first_repayment_date >= loan_disbursement_date
    );

-- The member key (§2.1). This is what makes a resubmitted file idempotent, and it is the
-- identity freeform members otherwise lack. Scoped to ACTIVE so a settled-then-refinanced
-- loan under the same account number can be re-enrolled (§2.12).
CREATE UNIQUE INDEX ux_policy_member_active_loan
    ON policy.policy_member (policy_number, loan_account_number)
    WHERE status = 'ACTIVE' AND loan_account_number IS NOT NULL;
```

**Before writing this, confirm the two constraint names** (`group_scheme_benefit_basis_check`, `group_scheme_basis_parameter_present`) against a real database. `V9:27` declares the basis check inline, so Postgres named it; the parameter check is named explicitly at `V9:54`.

- [ ] **Step 4: Widen the benefit basis**

`BenefitBasis.java` gains:

```java
    /**
     * The member's benefit is the outstanding principal of their own loan, falling as it
     * is repaid. Carries no scheme-level parameter -- the amount is on the member.
     */
    AMORTISING_LOAN
```

`GroupBenefitCalculator.benefitFor` gains a sixth parameter and a fourth case. The switch is exhaustive with no `default`, so adding the enum value breaks compilation here on purpose:

```java
    public static BigDecimal benefitFor(BenefitBasis basis, BigDecimal flatBenefitAmount,
                                         BigDecimal salaryMultiple, BigDecimal memberSalary,
                                         BigDecimal gradeBenefitAmount,
                                         BigDecimal loanPrincipalAmount) {
        return switch (basis) {
            case FLAT -> require(flatBenefitAmount, "This scheme is flat-benefit but carries no amount");
            case SALARY_MULTIPLE -> { /* unchanged */ }
            case GRADED -> require(gradeBenefitAmount,
                "This member's grade is not on the scheme's grade table");
            // Cover at INCEPTION. The decrease is not stored -- AmortisationCalculator
            // recomputes it as at the date of event, capped by this figure. See §2.4.
            case AMORTISING_LOAN -> require(loanPrincipalAmount,
                "A credit-life member needs the principal of their own loan");
        };
    }
```

Update the three existing call sites to pass `null` for the new parameter.

- [ ] **Step 5: Map the columns**

Add `interestMethod` to `GroupScheme` as a constructor argument with a getter — it is set
once at issuance from the scheme agreement, not derived and not mutable. A lender who
changes how their book repays is a new scheme, not an edit to this one.

`IssueGroupSchemeRequest` gains `InterestMethod interestMethod` as its final component,
required when `benefitBasis == AMORTISING_LOAN` and rejected otherwise (Task 5 enforces
this alongside the category check).

Add the eight loan columns to `PolicyMember`, plus:

```java
    /** This member's loan, or null on an ordinary group member. */
    public LoanTerms getLoanTerms() {
        if (loanAccountNumber == null) return null;
        return new LoanTerms(loanPrincipalAmount, loanAnnualRatePercent, loanTermMonths,
            RepaymentFrequency.valueOf(loanRepaymentFrequency), loanInstalmentAmount,
            loanDisbursementDate, loanFirstRepaymentDate);
    }

    public String getLoanAccountNumber() { return loanAccountNumber; }
```

and a `withLoan` used only by the credit-life construction path, **returning `this`** so it chains off the factories:

```java
    /** Attach the loan this member's cover is measured against. Returns this. */
    public PolicyMember withLoan(String loanAccountNumber, LoanTerms terms) {
        if (loanAccountNumber == null || loanAccountNumber.isBlank() || terms == null) {
            throw new IllegalArgumentException("A credit-life member needs an account number and terms");
        }
        this.loanAccountNumber = loanAccountNumber.trim();
        this.loanPrincipalAmount = terms.principalAmount();
        this.loanAnnualRatePercent = terms.annualInterestRatePercent();
        this.loanTermMonths = terms.termMonths();
        this.loanRepaymentFrequency = terms.repaymentFrequency().name();
        this.loanInstalmentAmount = terms.instalmentAmount();
        this.loanDisbursementDate = terms.disbursementDate();
        this.loanFirstRepaymentDate = terms.firstRepaymentDate();
        return this;
    }
```

**Verify two getters exist before writing Task 7**, which assumes them: `GroupScheme.getBenefitBasis()` and `PolicyMemberBenefit.getCoveredAmount()`. If either is named differently, use the real name — do not add a duplicate accessor.

- [ ] **Step 6: Run tests**

Run: `./mvnw clean test-compile`
Then: `./mvnw test -Dtest=GroupBenefitCalculatorTest`
Then: `./mvnw test -Dtest='*Group*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/db-migrations/policy/V14__credit_life_scheme.sql \
        backend/src/main/java/tz/co/nlolo/lifeplatform/policy/ \
        backend/src/test/java/tz/co/nlolo/lifeplatform/policy/GroupBenefitCalculatorTest.java
git commit -m "feat(policy): a scheme whose members are loans"
```

---

### Task 5: Issue a credit-life scheme

Spec §2.1, §2.11. `issueGroupScheme` currently refuses any product that is not `GROUP_LIFE` (`PolicyApi.java:176`).

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java` (the `issueGroupScheme` category guard)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/PolicyApi.java` (javadoc on `issueGroupScheme`)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/CreditLifeSchemeIntegrationTest.java`

**Interfaces:**
- Consumes: `ProductCategory.CREDIT_LIFE` (Task 1), `BenefitBasis.AMORTISING_LOAN` (Task 4).
- Produces: a credit-life scheme reachable by policy number, with `benefitBasis = AMORTISING_LOAN`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void aCreditLifeSchemeIsIssuedAgainstACreditLifeProduct() {
    GroupSchemeView scheme = policyApi.issueGroupScheme(creditLifeRequest(), "staff.underwriter");
    assertEquals(BenefitBasis.AMORTISING_LOAN, scheme.benefitBasis());
    assertNotNull(scheme.policyNumber());
}

@Test
void anAmortisingLoanSchemeOnAGroupLifeProductIsRefused() {
    // The basis and the category must agree: AMORTISING_LOAN on an employer product would
    // put loan cover on a scheme nobody priced as loan cover.
    assertThrows(InvalidPolicyStateException.class,
        () -> policyApi.issueGroupScheme(groupLifeProductWithLoanBasis(), "staff.underwriter"));
}

@Test
void aCreditLifeProductWithAnEmployerBasisIsRefused() {
    assertThrows(InvalidPolicyStateException.class,
        () -> policyApi.issueGroupScheme(creditLifeProductWithFlatBasis(), "staff.underwriter"));
}

@Test
void aCreditLifeSchemeHasNoHeadcountCap() {
    // Spec C4 / §2.11. Nothing in this slice imposes one; this test is the guard that
    // stops the substitution plan reintroducing one without noticing.
    String policyNumber = policyApi.issueGroupScheme(creditLifeRequest(), "staff.underwriter")
        .policyNumber();
    for (int i = 0; i < 25; i++) {
        policyApi.addMember(policyNumber, borrower("LN-TEST-" + i), "staff.underwriter");
    }
    assertEquals(26, policyApi.listMembers(policyNumber, MemberStatus.ACTIVE, null,
        PageRequest.of(0, 50)).getTotalElements());
}
```

**The fixtures below are used by Tasks 5, 6 and 7. Define them once, in this class:**

```java
    private static final BigDecimal FCL = new BigDecimal("25000000.00");

    /** An 8,500,000 TZS loan over 48 months, disbursed 2026-08-03. */
    private PolicyApi.MemberInput borrower(String loanAccountNumber) {
        return new PolicyApi.MemberInput(MemberType.FREEFORM, null, "Amina Hassan Mwinyi",
            LocalDate.of(1988, 3, 14), null, null, null, loanAccountNumber,
            new LoanTerms(new BigDecimal("8500000.00"), new BigDecimal("18.50"), 48,
                RepaymentFrequency.MONTHLY,
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 3)));
    }

    /** 30,000,000 TZS over 72 months — above the 25,000,000 FCL. */
    private PolicyApi.MemberInput largeBorrower(String loanAccountNumber) {
        return new PolicyApi.MemberInput(MemberType.FREEFORM, null, "Peter Massawe",
            LocalDate.of(1980, 7, 19), null, null, null, loanAccountNumber,
            new LoanTerms(new BigDecimal("30000000.00"), new BigDecimal("17.00"), 72,
                RepaymentFrequency.MONTHLY,
                LocalDate.of(2026, 8, 13), LocalDate.of(2026, 9, 13)));
    }

    /** A CREDIT_LIFE product on the AMORTISING_LOAN basis — the valid pairing. */
    private PolicyApi.IssueGroupSchemeRequest creditLifeRequest() {
        return schemeRequest(creditLifeProductVersionId, BenefitBasis.AMORTISING_LOAN, null);
    }

    /** AMORTISING_LOAN on a GROUP_LIFE product — invalid, the basis has no product. */
    private PolicyApi.IssueGroupSchemeRequest groupLifeProductWithLoanBasis() {
        return schemeRequest(groupLifeProductVersionId, BenefitBasis.AMORTISING_LOAN, null);
    }

    /** FLAT on a CREDIT_LIFE product — invalid, the product has no loan basis. */
    private PolicyApi.IssueGroupSchemeRequest creditLifeProductWithFlatBasis() {
        return schemeRequest(creditLifeProductVersionId, BenefitBasis.FLAT,
            new BigDecimal("5000000.00"));
    }
```

`schemeRequest(...)` assembles an `IssueGroupSchemeRequest` with `fclAmount = FCL`,
`currency = "TZS"`, an empty grade list, a single-member opening schedule, and
`commencementDate` in the past — the request record's full shape is at `PolicyApi.java:149`.
Copy the equivalent helper from the existing group integration test rather than inventing
field values; a scheme issued with an empty opening schedule has a sum assured of zero and
is refused by `policy_sum_assured_positive`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=CreditLifeSchemeIntegrationTest`
Expected: FAIL — `InvalidPolicyStateException: product is not GROUP_LIFE`.

- [ ] **Step 3: Widen the guard**

In `PolicyApiImpl.issueGroupScheme`, replace the `GROUP_LIFE`-only check with a category-and-basis agreement check:

```java
        ProductCategory category = snapshot.category();
        boolean loanBasis = request.benefitBasis() == BenefitBasis.AMORTISING_LOAN;

        if (category == ProductCategory.CREDIT_LIFE && !loanBasis) {
            throw new InvalidPolicyStateException(
                "A credit-life scheme must use the AMORTISING_LOAN basis -- its members "
                + "are loans, and any other basis would value them from the scheme instead");
        }
        if (category == ProductCategory.GROUP_LIFE && loanBasis) {
            throw new InvalidPolicyStateException(
                "The AMORTISING_LOAN basis needs a CREDIT_LIFE product -- loan cover on an "
                + "employer product is cover nobody priced");
        }
        if (category != ProductCategory.GROUP_LIFE && category != ProductCategory.CREDIT_LIFE) {
            throw new InvalidPolicyStateException(
                "A scheme needs a GROUP_LIFE or CREDIT_LIFE product, not " + category);
        }
```

Update the `issueGroupScheme` javadoc at `PolicyApi.java:176` — it currently says "The product must be a GROUP_LIFE product", which is now false.

- [ ] **Step 4: Run tests**

Run: `./mvnw test -Dtest=CreditLifeSchemeIntegrationTest`
Then: `./mvnw test -Dtest='*Group*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy/ \
        backend/src/test/java/tz/co/nlolo/lifeplatform/policy/CreditLifeSchemeIntegrationTest.java
git commit -m "feat(policy): a credit-life product can be issued as a scheme"
```

---

### Task 6: Add a borrower, carrying their loan

Spec §2.1, §2.5, §2.7. This is where inference, the FCL cap and the member key all land together.

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/PolicyApi.java` (`MemberInput` gains the loan)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java` (`addMember`)
- Test: extend `CreditLifeSchemeIntegrationTest`

**Interfaces:**
- Consumes: everything from Tasks 2–5.
- Produces: `MemberInput` gains `String loanAccountNumber` and `LoanTerms loanTerms` as its final two components. Final shape:
  `record MemberInput(MemberType memberType, UUID memberPartyId, String memberName, LocalDate memberDateOfBirth, String gradeCode, BigDecimal salaryAmount, LocalDate joinedOn, String loanAccountNumber, LoanTerms loanTerms)`

- [ ] **Step 1: Write the failing test**

```java
@Test
void aBorrowerIsCoveredForTheirPrincipalAtInception() {
    PolicyMemberView member = policyApi.addMember(creditLifeScheme,
        borrower("LN-2026-00417"), "staff.underwriter");

    assertEquals(0, new BigDecimal("8500000.00").compareTo(member.coveredAmount()));
    assertEquals("LN-2026-00417", member.loanAccountNumber());
}

@Test
void aMemberWithLoanTermsButNoAccountNumberIsRefused() {
    // The account number is the member key (§2.1) and neither real client file supplies
    // one yet -- both lenders have been asked to add it. Our template requires it, so a
    // row without one is rejected rather than silently given no identity.
    PolicyApi.MemberInput unkeyed = new PolicyApi.MemberInput(MemberType.FREEFORM, null,
        "Amina Hassan Mwinyi", LocalDate.of(1988, 3, 14), null, null, null, null,
        borrower("ignored").loanTerms());
    assertThrows(InvalidPolicyStateException.class,
        () -> policyApi.addMember(creditLifeScheme, unkeyed, "staff.underwriter"));
}

@Test
void theSameLoanAccountNumberCannotBeEnrolledTwiceWhileActive() {
    policyApi.addMember(creditLifeScheme, borrower("LN-2026-00417"), "staff.underwriter");
    assertThrows(InvalidPolicyStateException.class, () -> policyApi.addMember(
        creditLifeScheme, borrower("LN-2026-00417"), "staff.underwriter"));
}

@Test
void aLoanAboveTheFreeCoverLimitIsEnrolledCappedAndReferred() {
    // Scheme FCL 25,000,000. Loan 30,000,000.
    PolicyMemberView member = policyApi.addMember(creditLifeScheme,
        largeBorrower("LN-2026-00424"), "staff.underwriter");

    assertEquals(0, new BigDecimal("30000000.00").compareTo(member.benefitAmount()));
    assertEquals(0, new BigDecimal("25000000.00").compareTo(member.coveredAmount()));
    assertEquals(MemberUnderwritingStatus.EVIDENCE_REQUIRED, member.underwritingStatus());
}

@Test
void anOrdinaryGroupMemberStillNeedsNoLoan() {
    PolicyMemberView member = policyApi.addMember(groupLifeScheme,
        new PolicyApi.MemberInput(MemberType.PARTY, realPartyId, null, null, null, null,
            LocalDate.of(2026, 8, 3), null, null),
        "staff.underwriter");
    assertNull(member.loanAccountNumber());
}

@Test
void coverStartsOnTheDayTheLoanWasDisbursed() {
    // Spec §2.6. The disbursement date is the cover start -- it is the only choice with
    // no uninsured gap between the loan and the cover, which is the window a bank will
    // argue about after a death. joinedOn is DERIVED, not supplied.
    PolicyMemberView member = policyApi.addMember(creditLifeScheme,
        borrower("LN-2026-00417"), "staff.underwriter");
    assertEquals(LocalDate.of(2026, 8, 3), member.joinedOn());
}

@Test
void aJoinedOnThatContradictsTheDisbursementDateIsRefused() {
    PolicyApi.MemberInput contradictory = new PolicyApi.MemberInput(
        MemberType.FREEFORM, null, "Amina Hassan Mwinyi", LocalDate.of(1988, 3, 14),
        null, null, LocalDate.of(2026, 9, 1), "LN-2026-00417",
        borrower("LN-2026-00417").loanTerms());
    assertThrows(InvalidPolicyStateException.class,
        () -> policyApi.addMember(creditLifeScheme, contradictory, "staff.underwriter"));
}

@Test
void aLoanDisbursedInTheFutureIsRefused() {
    // A scheme's commencement may not be in the future (PolicyApi.java:179), and cover
    // incepting after today would be a member contributing to a total they do not yet
    // belong to. Plan 2 surfaces this as DISBURSEMENT_DATE_IN_FUTURE on the file.
    PolicyApi.MemberInput future = new PolicyApi.MemberInput(
        MemberType.FREEFORM, null, "Zainabu Ally", LocalDate.of(1987, 10, 30),
        null, null, null, "LN-2026-00428",
        new LoanTerms(new BigDecimal("9000000.00"), new BigDecimal("18.00"), 36,
            RepaymentFrequency.MONTHLY, new BigDecimal("325371.56"),
            LocalDate.now().plusDays(7), LocalDate.now().plusMonths(1).plusDays(7)));
    assertThrows(InvalidPolicyStateException.class,
        () -> policyApi.addMember(creditLifeScheme, future, "staff.underwriter"));
}
```

**Note for the implementer: this task changes `MemberInput` a second time.** Task 3 left it
at seven components; this task takes it to nine. Every test written in Task 3 —
`FreeformMemberIntegrationTest` — will stop compiling and must be updated in this task's
Step 3, not left for a later one. `./mvnw clean test-compile` is the only thing that will
tell you; an incremental build will not.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=CreditLifeSchemeIntegrationTest`
Expected: FAIL — `MemberInput` has seven components.

- [ ] **Step 3: Widen `MemberInput` and `addMember`**

Add the two components to `MemberInput` with javadoc:

```java
     * @param loanAccountNumber the member key on an AMORTISING_LOAN scheme, and the only
     *     stable identity a freeform borrower has. Rejected on any other basis.
     * @param loanTerms required on an AMORTISING_LOAN scheme, rejected on any other.
```

In `PolicyApiImpl.addMember`, before valuing the member:

```java
        if (scheme.getBenefitBasis() == BenefitBasis.AMORTISING_LOAN) {
            if (input.loanTerms() == null || input.loanAccountNumber() == null
                    || input.loanAccountNumber().isBlank()) {
                throw new InvalidPolicyStateException(
                    "A credit-life member must carry a loan account number and its terms");
            }
        } else if (input.loanTerms() != null || input.loanAccountNumber() != null) {
            throw new InvalidPolicyStateException(
                "Loan terms belong only on an AMORTISING_LOAN scheme");
        }
```

Then derive the cover start rather than trusting the caller (§2.6):

```java
        LocalDate joinedOn;
        if (scheme.getBenefitBasis() == BenefitBasis.AMORTISING_LOAN) {
            LocalDate disbursed = input.loanTerms().disbursementDate();
            if (disbursed.isAfter(LocalDate.now())) {
                throw new InvalidPolicyStateException(
                    "disbursement_date " + disbursed + " is in the future; cover cannot "
                    + "commence before the loan exists");
            }
            if (input.joinedOn() != null && !input.joinedOn().equals(disbursed)) {
                throw new InvalidPolicyStateException(
                    "A credit-life member's cover starts on the disbursement date "
                    + disbursed + ", not " + input.joinedOn());
            }
            joinedOn = disbursed;
        } else {
            joinedOn = input.joinedOn() != null ? input.joinedOn() : scheme.getCommencementDate();
        }
```

Refusing a contradictory `joinedOn` rather than silently overriding it matters: a caller
who supplies one believes something about this contract, and quietly ignoring them is how
a bank ends up thinking cover started a month later than it did.

Then pass `input.loanTerms() == null ? null : input.loanTerms().principalAmount()` as the new sixth argument to `GroupBenefitCalculator.benefitFor`, build the member with `PolicyMember.ofFreeform(...).withLoan(...)`, and catch `DataIntegrityViolationException` on the `ux_policy_member_active_loan` index to rethrow as `InvalidPolicyStateException` — the index is the guarantee, the pre-check is only a nicer error, exactly as `PartyApiImpl:98-107` does for identity documents.

Update every `new MemberInput(...)` call site again (`./mvnw clean test-compile` lists them).

- [ ] **Step 4: Run tests**

Run: `./mvnw clean test-compile && ./mvnw test -Dtest=CreditLifeSchemeIntegrationTest`
Then: `./mvnw test -Dtest='*Group*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy/ \
        backend/src/test/java/tz/co/nlolo/lifeplatform/policy/CreditLifeSchemeIntegrationTest.java
git commit -m "feat(policy): enrol a borrower against the loan that insures them"
```

---

### Task 7: `claimableCover` amortises — ⛔ BLOCKED, DO NOT START

**Blocked on client question A (revision above): does cover decline straight-line to zero,
follow a reducing-balance schedule, or stay flat at the disbursed amount?** Neither real
client file carries an outstanding balance, and both charge premium on the disbursed
amount, so their present practice may not decline at all. Writing this task now would encode
a guess into the one number a death claim is valued against.

Everything below is retained because the *shape* does not change with the answer — only
which branch of `outstandingPrincipalAt` is called, or whether it is called at all. Resume
when A is answered.


Spec §2.3, §2.4. The payoff of the whole plan: the number a claim will be valued against.

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java:756-826` (`claimableCover`)
- Test: extend `CreditLifeSchemeIntegrationTest`

**Interfaces:**
- Consumes: `AmortisationCalculator` (Task 2), `GroupScheme.getInterestMethod()` and `PolicyMember.getLoanTerms()` (Task 4).
- Produces: no signature change. `claimableCover(policyNumber, policyMemberId, asOf, benefitType)` returns a decreasing amount on an `AMORTISING_LOAN` scheme.

- [ ] **Step 1: Write the failing test**

```java
@Test
void coverOnTheDayOfDisbursementIsTheWholeLoan() {
    ClaimableCoverView cover = policyApi.claimableCover(creditLifeScheme, memberId,
        LocalDate.of(2026, 8, 3), BenefitType.DEATH);
    assertEquals(0, new BigDecimal("8500000.00").compareTo(cover.amount()));
}

@Test
void coverHalfwayThroughTheTermIsLessThanTheWholeLoan() {
    ClaimableCoverView cover = policyApi.claimableCover(creditLifeScheme, memberId,
        LocalDate.of(2028, 8, 3), BenefitType.DEATH);
    assertTrue(cover.amount().compareTo(new BigDecimal("8500000.00")) < 0, "was " + cover.amount());
    assertTrue(cover.amount().signum() > 0, "was " + cover.amount());
}

@Test
void coverAfterTheLoanMaturesIsZero() {
    ClaimableCoverView cover = policyApi.claimableCover(creditLifeScheme, memberId,
        LocalDate.of(2031, 1, 1), BenefitType.DEATH);
    assertEquals(0, BigDecimal.ZERO.compareTo(cover.amount()));
}

@Test
void anAboveFclLoanIsCappedBeforeItIsAmortisedAndAfter() {
    // 30m loan, 25m FCL. At inception cover is the cap, not the loan.
    ClaimableCoverView atStart = policyApi.claimableCover(creditLifeScheme, largeMemberId,
        LocalDate.of(2026, 8, 13), BenefitType.DEATH);
    assertEquals(0, new BigDecimal("25000000.00").compareTo(atStart.amount()));

    // Once the balance falls below the cap, the balance governs.
    ClaimableCoverView later = policyApi.claimableCover(creditLifeScheme, largeMemberId,
        LocalDate.of(2030, 8, 13), BenefitType.DEATH);
    assertTrue(later.amount().compareTo(new BigDecimal("25000000.00")) < 0, "was " + later.amount());
}

@Test
void anOrdinaryGroupSchemeIsUnaffected() {
    ClaimableCoverView cover = policyApi.claimableCover(groupLifeScheme, employeeMemberId,
        LocalDate.of(2027, 1, 1), BenefitType.DEATH);
    assertEquals(0, new BigDecimal("5000000.00").compareTo(cover.amount()));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=CreditLifeSchemeIntegrationTest`
Expected: FAIL — cover is a flat 8,500,000 on every date, because the stored `covered_amount` is returned unchanged.

- [ ] **Step 3: Amortise in `claimableCover`**

After the existing lookup resolves the member's in-force `policy_member_benefit` row, add:

```java
        BigDecimal covered = inForce.getCoveredAmount();

        // A credit-life member's cover falls as their loan is repaid. The stored figure is
        // cover AT INCEPTION (already capped at the FCL by GroupBenefitCalculator); the
        // schedule is recomputed as at the date of event rather than stored, because
        // materialising it would be tens of thousands of rows per file -- see §2.4.
        if (scheme.getBenefitBasis() == BenefitBasis.AMORTISING_LOAN) {
            LoanTerms terms = member.getLoanTerms();
            if (terms == null || scheme.getInterestMethod() == null) {
                throw new IllegalStateException(
                    "Member " + member.getPolicyMemberId() + " is on an AMORTISING_LOAN "
                    + "scheme with no loan terms -- chk_policy_member_loan_complete should "
                    + "have made this impossible");
            }
            BigDecimal outstanding = AmortisationCalculator.outstandingPrincipalAt(
                terms, scheme.getInterestMethod(), asOf);
            covered = outstanding.min(covered);
        }

        return new ClaimableCoverView(covered, scheme.getCurrency(), member.getPolicyMemberId());
```

`min` rather than a branch is the whole of §2.3's first half: the schedule is the contractual maximum and the FCL cap is the other maximum, and cover is whichever binds. The bank's declared figure is the third cap and is applied at claim registration, in Plan 4 — not here.

- [ ] **Step 4: Run tests**

Run: `./mvnw test -Dtest=CreditLifeSchemeIntegrationTest`
Then: `./mvnw test -Dtest=GroupClaimIntegrationTest`
Expected: PASS. The second is the guard that decreasing cover has not changed what an employer scheme pays.

- [ ] **Step 5: Negative control**

Temporarily replace `covered = outstanding.min(covered)` with `covered = outstanding`. Re-run. Expected: `anAboveFclLoanIsCappedBeforeItIsAmortisedAndAfter` fails on its first assertion (cover becomes 30m, above the limit). Restore and confirm green. This proves the FCL cap is load-bearing rather than incidentally satisfied.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java \
        backend/src/test/java/tz/co/nlolo/lifeplatform/policy/CreditLifeSchemeIntegrationTest.java
git commit -m "feat(policy): credit-life cover falls with the loan it insures"
```

---

### Task 8: `referForEvidence` gets its first caller

Spec §2.7, §4 item 16. `PolicyMember.referForEvidence(UUID)` has existed since Build 5 with **no caller anywhere** — no underwriting case has ever been opened for an above-FCL member. Credit life is the first product where that matters, because a bank writes large loans routinely.

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java` (`addMember`)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/package-info.java` (`allowedDependencies` may need `underwriting::api`)
- Test: extend `CreditLifeSchemeIntegrationTest`

**Interfaces:**
- Consumes: `MemberUnderwritingStatus.EVIDENCE_REQUIRED` from Task 6's valuation.
- Produces: nothing new. `PolicyMemberView.underwritingCaseId()` becomes non-null for an above-FCL member.

- [ ] **Step 1: Write the failing test**

```java
@Test
void anAboveFclBorrowerHasAnUnderwritingCaseOpenedForTheExcess() {
    PolicyMemberView member = policyApi.addMember(creditLifeScheme,
        largeBorrower("LN-2026-00424"), "staff.underwriter");

    assertEquals(MemberUnderwritingStatus.EVIDENCE_REQUIRED, member.underwritingStatus());
    assertNotNull(member.underwritingCaseId(),
        "an above-FCL member with no case is a referral nobody will ever action");
}

@Test
void aWithinFclBorrowerOpensNoCase() {
    PolicyMemberView member = policyApi.addMember(creditLifeScheme,
        borrower("LN-2026-00417", "251913.79"), "staff.underwriter");

    assertEquals(MemberUnderwritingStatus.WITHIN_FCL, member.underwritingStatus());
    assertNull(member.underwritingCaseId());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=CreditLifeSchemeIntegrationTest`
Expected: FAIL — `underwritingCaseId` is null; the status is set but nothing opens a case.

- [ ] **Step 3: Check the module boundary before writing any code**

`policy` calling `underwriting` may not be an allowed dependency, and `underwriting` already depends on `policy` in the other direction via `UnderwritingDecisionMade`. **If adding `underwriting::api` to `policy`'s `allowedDependencies` makes `ModularityTest` fail with a cycle, stop and escalate** — do not break the cycle by inventing a shared module or by moving code. The fallback design is that `policy` publishes a `policy.MemberEvidenceRequired` event and `underwriting` consumes it to open the case, which is how every other cross-module flow on this platform works.

Run: `./mvnw test -Dtest=ModularityTest` after adding the dependency, before writing the call.

- [ ] **Step 4: Open the case**

On the allowed-dependency path, in `addMember` after valuation:

```java
        if (valuation.underwritingStatus() == MemberUnderwritingStatus.EVIDENCE_REQUIRED) {
            UUID caseId = underwritingApi.openCaseForGroupMemberExcess(
                member.getPolicyMemberId(), valuation.benefitAmount(),
                valuation.coveredAmount(), addedBy);
            member.referForEvidence(caseId);
        }
```

On the event path, publish `new MemberEvidenceRequired(policyNumber, memberId, benefitAmount, coveredAmount)` after commit and let `underwriting` call back. **Whichever path is taken, `referForEvidence` must be the only way `underwriting_status` reaches `EVIDENCE_REQUIRED` with a case attached** — a status set without a case is the bug this task exists to close.

- [ ] **Step 5: Run tests**

Run: `./mvnw test -Dtest=CreditLifeSchemeIntegrationTest`
Then: `./mvnw test -Dtest=ModularityTest`
Then, with the dev backend stopped: `./mvnw clean test`
Expected: PASS, full suite green. Record the count — it was 1180 before this plan.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/ \
        backend/src/test/java/tz/co/nlolo/lifeplatform/policy/CreditLifeSchemeIntegrationTest.java
git commit -m "feat(policy): an above-FCL member is actually referred for evidence"
```

---

## Done when

- A `CREDIT_LIFE` product can be authored and published.
- A credit-life scheme can be issued, and refuses a mismatched basis in both directions.
- Borrowers enrol as freeform members carrying their loan, covered for the principal at inception.
- A member with loan terms but no account number is refused.
- The same loan account number cannot be active twice on one scheme.
- Cover starts on the disbursement date, backdating is accepted, a future disbursement is refused, and a contradictory `joinedOn` is refused rather than overridden.
- An above-FCL borrower has a real underwriting case.
- ⛔ **Not in scope until question A is answered:** `claimableCover` returning a falling amount (Task 7).
- `./mvnw clean test` green with the dev backend stopped.

## Explicitly NOT in this plan

CSV intake, submissions and propose→accept (Plan 2) · premium, invoicing, refunds, commission, exits (Plan 3) · claim registration, the bank's declared balance cap, EFT payout (Plan 4) · `regreporting` and notices (Plan 5) · console (Plan 6) · portal (Plan 7).

**One dependency outside this plan:** `plans/2026-09-10-group-scheme-substitution-and-notices.md` §4 removes `addMember`, which Task 6 extends. That plan must be amended so fixed headcount is a per-scheme property **before it lands** — spec §2.11. Task 5's `aCreditLifeSchemeHasNoHeadcountCap` is the regression guard, but a guard in this plan cannot stop a change made in that one.
