# Batch 2b Benefits Drive Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop valuing a critical-illness claim at the full death sum assured, by making a product's authored benefits produce the policy's coverage and making the claim resolve the coverage for its own benefit.

**Architecture:** `calculation_method` becomes a three-value enum with the amount fields each method needs, `BenefitDefinition.amountFor` becomes the one place a benefit's amount is computed, issuance writes one `Coverage` per authored benefit, and `claimableCover` takes the benefit type and resolves the matching row. Versions with no benefits keep today's single `DEATH` coverage through an explicit grandfathering branch whose population can only shrink.

**Tech Stack:** Java 21, Spring Boot 3 / Spring Modulith, Hibernate (`ddl-auto: none`), PostgreSQL 16, numbered SQL applied via `MigrationTestSupport`, JUnit 5 + Testcontainers + AssertJ, React 19 + Zod + React Hook Form, Vitest, Playwright.

## Global Constraints

- Design spec: `backend/docs/superpowers/specs/2026-09-15-batch2b-benefits-drive-coverage-design.md`.
- Branch: `batch2b-benefits-drive-coverage`, already created off `main` at `db1fef2`.
- Run `./mvnw` on the **host**. Never run Maven inside Docker.
- **Stop the dev backend before any `clean` target.**
- **Run one suite at a time.** Never vitest during Playwright, never two Maven builds, never vitest beside a full Maven run.
- Never run Prettier.
- Round **once**, at the end, `HALF_UP` to 2dp.
- Commit messages end with: `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`
- Baseline on `main`: backend **1165** green, frontend **79** product-scope green, 34 e2e green.
- Counts as of 2026-09-15: **146 product versions, 3 with benefit rows, 143 without**. 393 policy accounts, all with one `DEATH` coverage.
- **A required field is invisible to the compiler wherever a test builds JSON by hand.** After changing a request DTO, grep the whole test tree for the endpoint's JSON body — Batch 3 lost 99 tests to this.

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `product/api/BenefitCalculationMethod.java` | The three-value vocabulary | 1 |
| `product/api/BenefitDefinition.java` | A benefit's shape invariant and `amountFor` | 1 |
| `db-migrations/product/V13__benefit_calculation_method.sql` | Normalise, add amount columns, constrain | 2 |
| `product/domain/BenefitScheduleEntry.java` | Stores the method and its amount | 2 |
| `product/api/ProductApi.java` | `BenefitInput` widens; `resolveBenefitSchedule` | 2 |
| `product/application/ProductApiImpl.java` | Refuses an empty schedule; resolves definitions | 2 |
| `policy/application/PolicyApiImpl.java` | One coverage per benefit; `claimableCover` by type | 3, 4 |
| `claims/application/ClaimsApiImpl.java` | Passes the claim type | 4 |
| `frontend/.../publishVersionSchema.ts` + form + detail page | Authoring and display | 5 |
| 46 backend test classes | V13 in their migration lists | 2 |

---

## Task 1: a benefit knows its own amount

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/BenefitCalculationMethod.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/BenefitDefinition.java`
- Create: `backend/src/test/java/tz/co/nlolo/lifeplatform/product/application/BenefitDefinitionTest.java`

**Interfaces:**
- Produces: `BenefitCalculationMethod` = `{SUM_ASSURED, PERCENTAGE_OF_SUM_ASSURED, FLAT_AMOUNT}`. `BenefitDefinition(BenefitType, BenefitCalculationMethod, BigDecimal percent, BigDecimal flatAmount)` whose compact constructor enforces the shape, plus `BigDecimal amountFor(BigDecimal policySumAssured)`.

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.product.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;
import tz.co.nlolo.lifeplatform.product.api.BenefitDefinition;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a benefit actually pays, without a Spring context.
 *
 * <p>This is the number a claim is valued at, and until now there was no such number: every claim
 * type resolved to the policy's full sum assured, so a critical-illness claim paid a death benefit.
 */
class BenefitDefinitionTest {

    private static final BigDecimal COVER = new BigDecimal("10000000");

    @Test
    void sumAssuredPaysTheWholeCover() {
        BenefitDefinition death = new BenefitDefinition(
            BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED, null, null);
        assertThat(death.amountFor(COVER)).isEqualByComparingTo(COVER);
    }

    @Test
    void aPercentageBenefitPaysItsFraction() {
        BenefitDefinition ci = new BenefitDefinition(BenefitType.CRITICAL_ILLNESS,
            BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED, new BigDecimal("25"), null);
        assertThat(ci.amountFor(COVER)).isEqualByComparingTo(new BigDecimal("2500000.00"));
    }

    @Test
    void aFlatBenefitIgnoresTheCover() {
        BenefitDefinition funeral = new BenefitDefinition(BenefitType.DEATH,
            BenefitCalculationMethod.FLAT_AMOUNT, null, new BigDecimal("500000"));
        assertThat(funeral.amountFor(COVER)).isEqualByComparingTo(new BigDecimal("500000"));
        assertThat(funeral.amountFor(new BigDecimal("1"))).isEqualByComparingTo(new BigDecimal("500000"));
    }

    @Test
    void aPercentageRoundsOnceToTwoDecimals() {
        BenefitDefinition third = new BenefitDefinition(BenefitType.DISABILITY,
            BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED, new BigDecimal("33.33"), null);
        // 10,000,000 x 33.33% = 3,333,000.00 exactly; a 3-unit cover proves the rounding.
        assertThat(third.amountFor(new BigDecimal("3"))).isEqualByComparingTo(new BigDecimal("1.00"));
    }

    @Test
    void aPercentageBenefitNeedsAPercentAndNothingElse() {
        assertThatThrownBy(() -> new BenefitDefinition(BenefitType.CRITICAL_ILLNESS,
            BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED, null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("percentage");
        assertThatThrownBy(() -> new BenefitDefinition(BenefitType.CRITICAL_ILLNESS,
            BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED, new BigDecimal("25"), new BigDecimal("1")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("flat amount");
        assertThatThrownBy(() -> new BenefitDefinition(BenefitType.CRITICAL_ILLNESS,
            BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED, new BigDecimal("101"), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100");
    }

    @Test
    void aFlatBenefitNeedsAnAmountAndNothingElse() {
        assertThatThrownBy(() -> new BenefitDefinition(BenefitType.DEATH,
            BenefitCalculationMethod.FLAT_AMOUNT, null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("flat amount");
        assertThatThrownBy(() -> new BenefitDefinition(BenefitType.DEATH,
            BenefitCalculationMethod.FLAT_AMOUNT, null, BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("greater than zero");
    }

    @Test
    void sumAssuredCarriesNeitherAmount() {
        assertThatThrownBy(() -> new BenefitDefinition(BenefitType.DEATH,
            BenefitCalculationMethod.SUM_ASSURED, new BigDecimal("25"), null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```
cd backend && ./mvnw test -Dtest=BenefitDefinitionTest
```

Expected: compilation failure — neither type exists.

- [ ] **Step 3: Write the enum**

```java
package tz.co.nlolo.lifeplatform.product.api;

/**
 * How a benefit's payable amount is derived from the policy's sum assured.
 *
 * <p>Replaces an unconstrained {@code VARCHAR(50)} that held, across 146 versions, three rows
 * carrying {@code SUM_ASSURED} and the typed prose {@code untill death}. The column was displayed
 * on one screen and read by no computation, which is why the typo survived.
 *
 * <p>{@code SUM_ASSURED} keeps its name deliberately: it is already correct in 105 Java call
 * sites, 12 JSON bodies and the real database rows, so renaming it would be churn for no reader.
 *
 * <p>There is no {@code SUM_ASSURED_PLUS_BONUS}. It appears in one contract test and nowhere in
 * real data, and this platform has no bonus mechanism to compute it — no reversionary bonus and no
 * cash value. Admitting a method nothing can calculate would recreate the defect being removed.
 */
public enum BenefitCalculationMethod {
    SUM_ASSURED,
    PERCENTAGE_OF_SUM_ASSURED,
    FLAT_AMOUNT
}
```

- [ ] **Step 4: Write the record**

```java
package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One benefit a product version covers, and what it pays.
 *
 * <p><b>The number a claim is valued at.</b> Until this existed there was none: {@code
 * claimableCover} took no claim type and returned the policy's full sum assured for every claim,
 * so a critical-illness claim paid a death benefit — a survivable condition valued at the whole
 * cover, on a rider nobody had costed.
 *
 * <p>The compact constructor enforces the shape so an impossible benefit cannot be constructed
 * anywhere, the same arrangement as {@link EligibilityBounds}, {@link FrequencyLoading} and
 * {@link TiraFiling}. {@code benefit_schedule_amount_shape} enforces the same at the database.
 */
public record BenefitDefinition(BenefitType benefitType, BenefitCalculationMethod calculationMethod,
                                 BigDecimal percent, BigDecimal flatAmount) {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public BenefitDefinition {
        if (benefitType == null || calculationMethod == null) {
            throw new IllegalArgumentException("A benefit needs a type and a calculation method");
        }
        switch (calculationMethod) {
            case SUM_ASSURED -> {
                if (percent != null || flatAmount != null) {
                    throw new IllegalArgumentException("A SUM_ASSURED benefit pays the whole cover,"
                        + " so it carries neither a percentage nor a flat amount");
                }
            }
            case PERCENTAGE_OF_SUM_ASSURED -> {
                if (percent == null) {
                    throw new IllegalArgumentException(
                        "A PERCENTAGE_OF_SUM_ASSURED benefit needs a percentage");
                }
                if (flatAmount != null) {
                    throw new IllegalArgumentException(
                        "A PERCENTAGE_OF_SUM_ASSURED benefit carries no flat amount");
                }
                if (percent.signum() <= 0 || percent.compareTo(HUNDRED) > 0) {
                    throw new IllegalArgumentException("A benefit percentage must be greater than"
                        + " zero and no more than 100, was " + percent.toPlainString());
                }
            }
            case FLAT_AMOUNT -> {
                if (flatAmount == null) {
                    throw new IllegalArgumentException("A FLAT_AMOUNT benefit needs a flat amount");
                }
                if (percent != null) {
                    throw new IllegalArgumentException(
                        "A FLAT_AMOUNT benefit carries no percentage");
                }
                if (flatAmount.signum() <= 0) {
                    throw new IllegalArgumentException("A flat benefit amount must be greater than"
                        + " zero -- a benefit that pays nothing is not a benefit");
                }
            }
        }
    }

    /** The two-argument form for the common case: pays the whole cover. */
    public BenefitDefinition(BenefitType benefitType) {
        this(benefitType, BenefitCalculationMethod.SUM_ASSURED, null, null);
    }

    /**
     * What this benefit pays on a policy insuring {@code policySumAssured}.
     *
     * <p>The ONE place this arithmetic lives, exactly as {@code FrequencyLoading.applyTo} is.
     * Rounds once, at the end, HALF_UP to 2dp.
     */
    public BigDecimal amountFor(BigDecimal policySumAssured) {
        return switch (calculationMethod) {
            case SUM_ASSURED -> policySumAssured;
            case FLAT_AMOUNT -> flatAmount;
            case PERCENTAGE_OF_SUM_ASSURED ->
                policySumAssured.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        };
    }
}
```

- [ ] **Step 5: Run the test**

```
cd backend && ./mvnw test -Dtest=BenefitDefinitionTest
```

Expected: 7 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/BenefitCalculationMethod.java backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/BenefitDefinition.java backend/src/test/java/tz/co/nlolo/lifeplatform/product/application/BenefitDefinitionTest.java
git commit -m "feat(product): a benefit knows what it pays" -m "The number a claim is valued at, which until now did not exist: claimableCover took no claim type and returned the policy's full sum assured for every claim, so a critical-illness claim paid a death benefit. amountFor is the one place the arithmetic lives, and the compact constructor makes an impossible benefit unconstructable." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: the vocabulary reaches the database and the API

**Indivisible**: widening `BenefitInput` breaks ~105 call sites, so the tree does not compile between the API change and the last fix.

**Files:**
- Create: `backend/db-migrations/product/V13__benefit_calculation_method.sql`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/domain/BenefitScheduleEntry.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/ProductApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/infrastructure/BenefitRequest.java`
- Modify: ~35 backend test classes and all 46 migration lists

**Interfaces:**
- Consumes: `BenefitCalculationMethod`, `BenefitDefinition` from Task 1.
- Produces: `BenefitInput(BenefitType, BenefitCalculationMethod, BigDecimal percent, BigDecimal flatAmount)` with a two-argument convenience form `BenefitInput(BenefitType, BenefitCalculationMethod)`. `ProductApi.resolveBenefitSchedule(UUID productVersionId)` → `List<BenefitDefinition>`.

- [ ] **Step 1: Write the migration**

```sql
-- benefit_schedule.calculation_method becomes a real vocabulary, and a benefit can say what it pays.
--
-- It was an unconstrained VARCHAR(50). Across 146 versions it held three rows: two carrying the
-- typed prose 'untill death' and one carrying 'SUM_ASSURED'. Nothing noticed, because NO
-- COMPUTATION HAS EVER READ THIS COLUMN -- it is displayed on one product screen and used nowhere
-- else. Issuance wrote a hardcoded DEATH coverage whatever a product declared.
--
-- NORMALISE, don't grandfather -- and that is a deliberate departure from V5, V8 and V9. Those
-- used NOT VALID because the columns they constrained drove real money (a rating multiplier, a
-- rate per mille), so rewriting history would have altered priced contracts. This column has
-- never decided anything, so mapping a typo to the value it plainly meant changes no contract and
-- no premium -- and a fully VALIDATED check becomes achievable where V5 had to settle for less.
--
-- Anything outside the three values maps to SUM_ASSURED. With three rows in this database that is
-- a rounding error, and the alternative -- failing the migration on unknown data -- would block a
-- deployment over a label that has never decided anything.

UPDATE product.benefit_schedule
   SET calculation_method = 'SUM_ASSURED'
 WHERE calculation_method NOT IN ('SUM_ASSURED','PERCENTAGE_OF_SUM_ASSURED','FLAT_AMOUNT');

ALTER TABLE product.benefit_schedule
    ADD COLUMN benefit_percent NUMERIC(5,2),
    ADD COLUMN flat_amount     NUMERIC(19,2);

ALTER TABLE product.benefit_schedule
    ADD CONSTRAINT benefit_schedule_calculation_method_known
        CHECK (calculation_method IN ('SUM_ASSURED','PERCENTAGE_OF_SUM_ASSURED','FLAT_AMOUNT'));

-- Exactly the amount its method needs, and no other. Same shape-constraint arrangement as
-- rating_table_age_bounds_shape.
ALTER TABLE product.benefit_schedule
    ADD CONSTRAINT benefit_schedule_amount_shape CHECK (
        (calculation_method = 'SUM_ASSURED'
            AND benefit_percent IS NULL AND flat_amount IS NULL)
     OR (calculation_method = 'PERCENTAGE_OF_SUM_ASSURED'
            AND benefit_percent IS NOT NULL AND benefit_percent > 0 AND benefit_percent <= 100
            AND flat_amount IS NULL)
     OR (calculation_method = 'FLAT_AMOUNT'
            AND flat_amount IS NOT NULL AND flat_amount > 0
            AND benefit_percent IS NULL));

COMMENT ON COLUMN product.benefit_schedule.benefit_percent IS
    'Set for PERCENTAGE_OF_SUM_ASSURED and null otherwise. A critical-illness rider at 25 pays a '
    'quarter of the policy sum assured; before this column every claim type was valued at the '
    'whole of it.';
```

- [ ] **Step 2: Store the new fields**

In `BenefitScheduleEntry`, add beside `calculationMethod`:

```java
    // What this benefit pays (V13). Exactly one is set, or neither for SUM_ASSURED --
    // benefit_schedule_amount_shape enforces that at the database.
    @Column(name = "benefit_percent")
    private BigDecimal benefitPercent;

    @Column(name = "flat_amount")
    private BigDecimal flatAmount;
```

Widen the constructor to `(UUID tenantId, UUID productVersionId, String benefitType, String calculationMethod, BigDecimal benefitPercent, BigDecimal flatAmount)` and add `getBenefitPercent()` / `getFlatAmount()`. Add `import java.math.BigDecimal;`.

- [ ] **Step 3: Widen `BenefitInput` and add the resolver**

In `ProductApi`, replace the record and add the resolver:

```java
    /**
     * One benefit at authoring time.
     *
     * <p>{@code calculationMethod} is an ENUM, not a String, and there is deliberately no String
     * overload: a stringly-typed door here is how {@code calculation_method} came to hold
     * {@code untill death}.
     */
    record BenefitInput(BenefitType benefitType, BenefitCalculationMethod calculationMethod,
                        BigDecimal percent, BigDecimal flatAmount) {
        public BenefitInput(BenefitType benefitType, BenefitCalculationMethod calculationMethod) {
            this(benefitType, calculationMethod, null, null);
        }
    }

    /**
     * What this version covers, and what each benefit pays.
     *
     * <p>Internal-only, not part of {@code openapi-product.yaml} — the same convention as
     * {@link #isPriced} and {@link #resolveFrequencyLoading}. Consumed by policy at issuance so
     * a contract's coverage is what its product actually authored.
     *
     * <p>Empty for the 143 versions published before an authored benefit was required. Callers
     * must treat empty as "this version predates the rule", not as "this product covers nothing".
     */
    List<BenefitDefinition> resolveBenefitSchedule(UUID productVersionId);
```

In `ProductApiImpl`, persist the new fields in `publishVersion`'s benefit loop, add the resolver, and add the empty-schedule refusal beside the other publish rules:

```java
        // A product that covers nothing is not a product. Refused going forward; the 143 versions
        // that already have no benefits are grandfathered at ISSUANCE, not here -- see
        // PolicyApiImpl. Making this a publish rule is what closes that population: it can only
        // shrink from here.
        if (benefitSchedule == null || benefitSchedule.isEmpty()) {
            throw new InvalidProductVersionException(
                "A product version must cover at least one benefit -- a version that covers"
                    + " nothing cannot be sold, and nothing downstream could value a claim on it");
        }
```

```java
    @Override
    public List<BenefitDefinition> resolveBenefitSchedule(UUID productVersionId) {
        return benefitScheduleEntryRepository.findByProductVersionId(productVersionId).stream()
            .map(b -> new BenefitDefinition(BenefitType.valueOf(b.getBenefitType()),
                BenefitCalculationMethod.valueOf(b.getCalculationMethod()),
                b.getBenefitPercent(), b.getFlatAmount()))
            .toList();
    }
```

`getVersionRating` maps the same way, so the rating read carries the method and amount.

- [ ] **Step 4: Widen the wire DTO**

`BenefitRequest` becomes:

```java
public record BenefitRequest(BenefitType benefitType, BenefitCalculationMethod calculationMethod,
                              BigDecimal percent, BigDecimal flatAmount) {}
```

and `ProductController` maps all four fields. Add to `openapi-product.yaml`'s benefit schema: `calculationMethod` as an enum of the three values, plus optional `percent` and `flatAmount`, and mirror the same on `VersionRatingView`'s benefit items.

- [ ] **Step 5: Add V13 to all 46 migration lists**

```bash
cd backend && node -e "
const fs=require('fs'),path=require('path');
const V12='db-migrations/product/V12__tira_filing.sql';
const V13='db-migrations/product/V13__benefit_calculation_method.sql';
function walk(d,out=[]){for(const e of fs.readdirSync(d,{withFileTypes:true})){const p=path.join(d,e.name);if(e.isDirectory())walk(p,out);else if(e.name.endsWith('.java'))out.push(p);}return out;}
let changed=0;
for(const f of walk('src/test')){
  const raw=fs.readFileSync(f,'utf8');
  if(!raw.includes(V12)||raw.includes(V13))continue;
  const eol=raw.includes('\r\n')?'\r\n':'\n';
  const out=[];
  for(const line of raw.split(/\r?\n/)){
    if(line.includes(V12)){
      const indent=line.match(/^\s*/)[0];
      if(line.trimEnd().endsWith(');')){
        out.push(line.replace(/\"\);\s*\$/,'\",').replace(/\"\);\$/,'\",'));
        out.push(indent+'\"'+V13+'\");');
      } else { out.push(line); out.push(indent+'\"'+V13+'\",'); }
    } else out.push(line);
  }
  fs.writeFileSync(f,out.join(eol)); changed++;
}
console.log('changed',changed);
"
grep -rl "V13__benefit_calculation_method.sql" src/test --include="*.java" | wc -l
```

Expected: 46.

- [ ] **Step 6: Update the ~105 `BenefitInput` call sites**

Every one is the identical literal, so this is a safe exact replace — unlike a bare trailing argument, the pattern includes `BenefitInput(` and cannot collide:

```bash
cd backend && grep -rl 'BenefitInput(BenefitType.DEATH, "SUM_ASSURED")' src/test --include="*.java" | while read f; do
  perl -pi -e 's/BenefitInput\(BenefitType\.DEATH, "SUM_ASSURED"\)/BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)/g' "$f"
done
grep -rc 'BenefitInput(BenefitType.DEATH, "SUM_ASSURED")' src/test --include="*.java" | grep -v ':0' | head
```

Expected: the second command prints nothing — no literal remains. Then add the import where missing; most classes already wildcard-import `tz.co.nlolo.lifeplatform.product.api.*`, and the compile in the next step names any that do not.

**Also fix the one `SUM_ASSURED_PLUS_BONUS` occurrence** in `PolicyContractTest`'s JSON body — it becomes `SUM_ASSURED`, because no bonus mechanism exists to compute it.

- [ ] **Step 7: Stop the dev backend, then compile**

```
cd backend && ./mvnw clean test-compile
```

Expected eventually BUILD SUCCESS. **The compiler is the worklist** — fix each named site and re-run. Do not weaken the API to silence an error.

- [ ] **Step 8: Write the publish-rule test**

```java
    @Test
    void publishVersionRefusesAVersionThatCoversNothing() {
        ProductSummaryView product = productApi.createProduct("TERM-B2B-NOBEN", "Covers nothing",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        InvalidProductVersionException thrown = assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(),
                null, ANY_FILING, "actuary@nlolo.co.tz"));

        assertThat(thrown.getMessage()).contains("at least one benefit");
    }

    @Test
    void aPercentageBenefitRoundTripsOntoTheRatingRead() {
        ProductSummaryView product = productApi.createProduct("TERM-B2B-PCT", "With a CI rider",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED),
                    new ProductApi.BenefitInput(BenefitType.CRITICAL_ILLNESS,
                        BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED, new BigDecimal("25.00"), null)),
            null, ANY_FILING, "actuary@nlolo.co.tz");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        assertThat(productApi.resolveBenefitSchedule(versionId))
            .filteredOn(b -> b.benefitType() == BenefitType.CRITICAL_ILLNESS)
            .singleElement()
            .satisfies(ci -> {
                assertThat(ci.calculationMethod()).isEqualTo(BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED);
                assertThat(ci.amountFor(new BigDecimal("10000000")))
                    .isEqualByComparingTo(new BigDecimal("2500000.00"));
            });
    }
```

- [ ] **Step 9: Run the product classes**

```
cd backend && ./mvnw test -Dtest=ProductApiIntegrationTest,ProductContractTest,BenefitDefinitionTest
```

Expected: PASS. Fixtures publishing an empty `List.of()` benefit schedule now fail the new rule — give each one a `DEATH` benefit, which is what those products mean.

- [ ] **Step 10: Commit**

```bash
git add backend/db-migrations/product/V13__benefit_calculation_method.sql backend/src backend/api/openapi/openapi-product.yaml
git commit -m "feat(product): a benefit schedule says what it covers and what it pays" -m "calculation_method becomes a three-value enum with the amount fields each method needs. The migration NORMALISES rather than grandfathering -- a departure from V5, V8 and V9, and deliberate: those columns drove real money, this one has never been read by any computation, so mapping the typed prose 'untill death' to the value it plainly meant changes no contract and lets the CHECK be validated rather than NOT VALID." -m "Publishing a version that covers nothing is refused. That rule is what closes the grandfathered population at issuance: it can only shrink from here." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: issuance writes the coverage a product authored

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyApiIntegrationTest.java`

**Interfaces:**
- Consumes: `ProductApi.resolveBenefitSchedule(UUID)` and `BenefitDefinition.amountFor(BigDecimal)`.
- Produces: no new API. `policy.coverage` now holds one row per authored benefit.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void issuanceWritesOneCoveragePerAuthoredBenefit() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        ProductSummaryView product = productApi.createProduct("POLICY-B2B-01", "Death plus CI",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED),
                    new ProductApi.BenefitInput(BenefitType.CRITICAL_ILLNESS,
                        BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED, new BigDecimal("25.00"), null)),
            null, ANY_FILING, "actuary");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        String policyNumber = issueDirectly(tenantId,
            new Fixture(pricedLife(tenantId, 35, "7001"), product.productId(), versionId), List.of());

        // 1,000,000 of cover: DEATH pays all of it, CRITICAL_ILLNESS a quarter.
        CoverageStatusView status = policyApi.getCoverageStatus(policyNumber, LocalDate.now());
        assertThat(status.activeCoverages()).hasSize(2);
        assertThat(status.activeCoverages())
            .filteredOn(c -> c.benefitType() == BenefitType.CRITICAL_ILLNESS)
            .singleElement()
            .satisfies(ci -> assertThat(ci.sumAssuredAmount()).isEqualByComparingTo(new BigDecimal("250000.00")));
        assertThat(status.activeCoverages())
            .filteredOn(c -> c.benefitType() == BenefitType.DEATH)
            .singleElement()
            .satisfies(d -> assertThat(d.sumAssuredAmount()).isEqualByComparingTo(new BigDecimal("1000000")));
    }

    /**
     * THE GRANDFATHERING ASSERTION, and the one that proves nothing in force changed.
     *
     * <p>143 of 146 versions have no benefit rows, because the console allowed an empty schedule
     * until now. Refusing to issue on them would have made almost the whole catalogue unsellable,
     * and they cannot simply be republished -- that now needs entry-age bounds, rate-table
     * coverage and a TIRA filing. So they keep exactly today's behaviour: one DEATH coverage at
     * the policy's own sum assured, which is the number claimableCover already returns.
     */
    @Test
    void aVersionWithNoAuthoredBenefitsStillIssuesWithASingleDeathCoverage() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-B2B-LEGACY");

        // buildFixture publishes with a DEATH benefit, so strip the rows to reproduce a pre-V13
        // version -- publishVersion can no longer create one.
        jdbcTemplate.update("DELETE FROM product.benefit_schedule WHERE product_version_id = ?",
            fixture.productVersionId());

        String policyNumber = issueDirectly(tenantId, fixture, List.of());

        CoverageStatusView status = policyApi.getCoverageStatus(policyNumber, LocalDate.now());
        assertThat(status.activeCoverages()).singleElement()
            .satisfies(c -> {
                assertThat(c.benefitType()).isEqualTo(BenefitType.DEATH);
                assertThat(c.sumAssuredAmount()).isEqualByComparingTo(new BigDecimal("1000000"));
            });
    }
```

- [ ] **Step 2: Run to verify they fail**

```
cd backend && ./mvnw test -Dtest='PolicyApiIntegrationTest#issuanceWritesOneCoveragePerAuthoredBenefit+aVersionWithNoAuthoredBenefitsStillIssuesWithASingleDeathCoverage'
```

Expected: the first FAILS with one coverage instead of two; the second already passes.

- [ ] **Step 3: Write one coverage per benefit**

Replace the hardcoded write in `issuePolicy` — and delete the stale comment above it, which says a per-benefit row "isn't buildable without a further ProductApi change this plan does not make". That change is now made:

```java
        // One Coverage per authored benefit, at the amount that benefit pays. Until now this was a
        // single hardcoded DEATH row whatever the product declared, so benefit_schedule was
        // authored and read by nothing.
        List<BenefitDefinition> benefits = productApi.resolveBenefitSchedule(request.productVersionId());
        if (benefits.isEmpty()) {
            // GRANDFATHERING, and it expires on its own. 143 versions predate the rule that a
            // version must cover at least one benefit; publishVersion now refuses an empty
            // schedule, so this population can only SHRINK. These policies keep exactly the cover
            // they have today -- the policy's own sum assured, which is the number claimableCover
            // already returns for them. Nothing is invented here.
            coverageRepository.save(new Coverage(tenantId, policyNumber, BenefitType.DEATH.name(),
                request.sumAssuredAmount(), request.sumAssuredCurrency()));
        } else {
            for (BenefitDefinition benefit : benefits) {
                coverageRepository.save(new Coverage(tenantId, policyNumber, benefit.benefitType().name(),
                    benefit.amountFor(request.sumAssuredAmount()), request.sumAssuredCurrency()));
            }
        }
```

**`issueGroupScheme` is deliberately unchanged.** A scheme's claimable cover is derived from its member schedule, never from coverage rows — `claimableCover` branches on the scheme before it looks at coverage at all — so per-benefit rows there would be decorative. Add a one-line comment above its existing write saying so.

- [ ] **Step 4: Run the class**

```
cd backend && ./mvnw test -Dtest=PolicyApiIntegrationTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyApiIntegrationTest.java
git commit -m "feat(policy): a contract covers what its product authored" -m "Issuance wrote a single hardcoded DEATH coverage whatever the product declared -- the code even carried a comment explaining that a per-benefit row was not buildable without a ProductApi change. That change now exists." -m "A version with no authored benefits keeps exactly today's behaviour through an explicit branch: one DEATH coverage at the policy's own sum assured, which is the number claimableCover already returns. The branch expires on its own, because publishing a benefit-less version is now refused." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: the claim is valued against its own benefit

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/PolicyApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/claims/application/ClaimsApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/claims/ClaimsApiIntegrationTest.java`

**Interfaces:**
- Consumes: the per-benefit coverage rows from Task 3.
- Produces: `ClaimableCoverView claimableCover(String policyNumber, UUID policyMemberId, LocalDate asOf, String benefitType)`.

- [ ] **Step 1: Write the failing tests**

Two tests, in the class that owns each side.

**In `PolicyApiIntegrationTest`** — the amount itself, which is `PolicyApi`'s contract:

```java
    /**
     * A critical-illness claim was valued at the FULL DEATH SUM ASSURED, because claimableCover
     * took no claim type and returned the policy's single sum assured for everything. A survivable
     * condition paid the whole cover, on a rider nobody had costed.
     */
    @Test
    void claimableCoverValuesEachBenefitAtItsOwnAmount() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        ProductSummaryView product = productApi.createProduct("POLICY-B2B-CLAIM", "Death plus CI",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED),
                    new ProductApi.BenefitInput(BenefitType.CRITICAL_ILLNESS,
                        BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED, new BigDecimal("25.00"), null)),
            null, ANY_FILING, "actuary");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        String policyNumber = issueDirectly(tenantId,
            new Fixture(pricedLife(tenantId, 35, "7002"), product.productId(), versionId), List.of());

        // issueDirectly insures 1,000,000.
        assertThat(policyApi.claimableCover(policyNumber, null, LocalDate.now(), "DEATH").amount())
            .isEqualByComparingTo(new BigDecimal("1000000"));
        assertThat(policyApi.claimableCover(policyNumber, null, LocalDate.now(), "CRITICAL_ILLNESS").amount())
            .as("a quarter of the cover, not all of it")
            .isEqualByComparingTo(new BigDecimal("250000.00"));
    }

    @Test
    void claimableCoverRefusesABenefitTheProductDoesNotCover() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-B2B-DEATHONLY");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());

        assertThatThrownBy(() ->
            policyApi.claimableCover(policyNumber, null, LocalDate.now(), "CRITICAL_ILLNESS"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("CRITICAL_ILLNESS");
    }
```

**In `ClaimsApiIntegrationTest`** — that the refusal reaches a real claim registration. `buildFixture` there publishes a `DEATH`-only product, so a CI claim has nothing to value:

```java
    /**
     * Registering a critical-illness claim on a death-only product is refused rather than valued
     * at the death benefit. Paying a benefit nobody authored is how a product pays for cover it
     * never priced.
     */
    @Test
    void refusesACriticalIllnessClaimOnAProductThatOnlyCoversDeath() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-CI-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        LocalDate dateOfEvent = LocalDate.now().minusDays(1);

        ClaimsApi.RegisterClaimRequest request = new ClaimsApi.RegisterClaimRequest(
            policyNumber, null, fixture.applicantId(), ClaimType.CRITICAL_ILLNESS, dateOfEvent,
            new CriticalIllnessClaimDetails("Myocardial infarction", dateOfEvent, "I21"));

        assertThatThrownBy(() -> claimsApi.registerClaim(request, "reg-idem-ci-01", "claims-staff"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("CRITICAL_ILLNESS");
    }
```

Add `import tz.co.nlolo.lifeplatform.claims.api.CriticalIllnessClaimDetails;` and, if absent,
`import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;` — `ClaimsApiImpl` lets
that exception propagate as-is, which the class's own comment at line 170 records.

- [ ] **Step 2: Run to verify they fail**

```
cd backend && ./mvnw test -Dtest=ClaimsApiIntegrationTest
```

Expected: FAIL — compilation, once `claimableCover` gains its parameter, or the amount assertion.

- [ ] **Step 3: Widen `claimableCover`**

In `PolicyApi`:

```java
    /**
     * The cover a claim of this benefit type can be valued at.
     *
     * <p>{@code benefitType} is a {@code String} because claims may not reference
     * {@code product.api.BenefitType} without failing {@code ModularityTests} — the same reason
     * {@code PolicyView.productCategory} is one, and the reason this view exists at all. Callers
     * pass {@code claimType.name()}; the four {@code ClaimType} values map one-to-one onto benefit
     * types.
     *
     * <p>Before this parameter existed the method returned the policy's single sum assured for
     * every claim, so a critical-illness claim was valued at the full death benefit.
     *
     * @throws InvalidPolicyStateException if the policy has no active coverage for that benefit
     */
    ClaimableCoverView claimableCover(String policyNumber, UUID policyMemberId, LocalDate asOf,
                                       String benefitType);
```

In `PolicyApiImpl`, the non-scheme branch resolves the coverage instead of returning the policy sum assured:

```java
        if (scheme.isEmpty()) {
            if (policyMemberId != null) {
                throw new InvalidPolicyStateException("Policy " + policyNumber
                    + " is not a group scheme, so a claim on it cannot name a member");
            }
            return coverageRepository.findByPolicyNumberAndActiveTrue(policyNumber).stream()
                .filter(c -> c.getBenefitType().equals(benefitType))
                .findFirst()
                .map(c -> new ClaimableCoverView(c.getSumAssuredAmount(), c.getSumAssuredCurrency(), null))
                .orElseThrow(() -> new InvalidPolicyStateException("Policy " + policyNumber
                    + " has no active " + benefitType + " cover to claim against. The product this"
                    + " policy was issued on does not cover it, so there is no amount to pay --"
                    + " paying one anyway is how a product pays for cover it never priced."));
        }
```

The scheme branch is unchanged: a scheme's cover comes from its member schedule. Add a comment recording that a non-DEATH claim on a scheme is therefore valued at the member's cover, which is a known limitation rather than a considered design.

- [ ] **Step 4: Pass the type from claims**

Both `ClaimsApiImpl` call sites gain `request.claimType().name()` / `claim.getClaimType().name()` as the fourth argument. Read each call site to use the right expression — line 173 is on a registration request, line 371 on a persisted claim.

- [ ] **Step 5: Run claims and policy**

```
cd backend && ./mvnw test -Dtest=ClaimsApiIntegrationTest,PolicyApiIntegrationTest,ClaimsContractTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy backend/src/main/java/tz/co/nlolo/lifeplatform/claims backend/src/test
git commit -m "fix(policy,claims): a claim is valued against its own benefit" -m "claimableCover took no claim type and returned the policy's single sum assured for every claim, so a critical-illness claim was valued at the FULL DEATH SUM ASSURED -- a survivable condition paying the whole cover on a rider nobody had costed." -m "It now resolves the coverage for the benefit claimed, and refuses when the product does not cover it. The type crosses as a String because claims may not reference product.api.BenefitType without failing ModularityTests, the same reason PolicyView.productCategory is one." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: the console authors and shows the benefit amounts

**Files:**
- Modify: `frontend/src/features/products/publishVersionSchema.ts`
- Modify: `frontend/src/features/products/PublishVersionForm.tsx`
- Modify: `frontend/src/features/products/ProductDetailPage.tsx`
- Test: `frontend/src/features/products/publishVersionSchema.test.ts`

- [ ] **Step 1: Regenerate the API types**

```
cd frontend && npm run generate:api
```

- [ ] **Step 2: Write the failing schema tests**

```ts
  describe('benefit amounts', () => {
    it('requires at least one benefit', () => {
      const result = termLife.safeParse({ ...valid(), benefitSchedule: [] });
      expect(result.success).toBe(false);
      expect(JSON.stringify(result.error?.issues)).toContain('at least one benefit');
    });

    it('requires a percentage on a PERCENTAGE_OF_SUM_ASSURED benefit', () => {
      const result = termLife.safeParse({
        ...valid(),
        benefitSchedule: [
          { benefitType: 'CRITICAL_ILLNESS', calculationMethod: 'PERCENTAGE_OF_SUM_ASSURED', percent: '', flatAmount: '' },
        ],
      });
      expect(result.success).toBe(false);
    });

    it('sends a percentage benefit as a number', () => {
      const result = termLife.safeParse({
        ...valid(),
        benefitSchedule: [
          { benefitType: 'CRITICAL_ILLNESS', calculationMethod: 'PERCENTAGE_OF_SUM_ASSURED', percent: '25', flatAmount: '' },
        ],
      });
      expect(result.success).toBe(true);
      expect(toApiRequest(result.data!).benefitSchedule[0]).toEqual({
        benefitType: 'CRITICAL_ILLNESS',
        calculationMethod: 'PERCENTAGE_OF_SUM_ASSURED',
        percent: 25,
      });
    });
  });
```

Extend the shared `valid()` helper with a `DEATH`/`SUM_ASSURED` benefit, since an empty schedule no longer parses.

- [ ] **Step 3: Run to verify they fail**

```
cd frontend && npx vitest run src/features/products/publishVersionSchema.test.ts
```

- [ ] **Step 4: Update the schema**

`benefitRowSchema` becomes a select plus two conditional string fields, with a `superRefine` mirroring the server shape rule — a percentage required and no flat amount for `PERCENTAGE_OF_SUM_ASSURED`, the reverse for `FLAT_AMOUNT`, neither for `SUM_ASSURED`. The array gains `.min(1, 'A product must cover at least one benefit')`, **replacing the `// no minimum coverage required` comment**, which is the rule this batch reverses. `toApiRequest` emits `percent`/`flatAmount` only for the method that uses them.

- [ ] **Step 5: Update the form**

Each benefit row gains a calculation-method `<select>` and one conditional input, following the rating-table rows' existing pattern for a per-row select — read `PublishVersionForm.tsx`'s rating factor rows and match their structure and classes.

- [ ] **Step 6: Show the amounts**

`RatingBasis`'s benefit rows show the method and its amount — `25% of sum assured`, `TZS 500,000 flat`, or `Sum assured` — rather than the raw string.

- [ ] **Step 7: Run the frontend gates**

```
cd frontend && npx vitest run src/features/products src/store/productStore.test.ts ; echo "--- TYPECHECK ---" ; npm run typecheck ; echo "--- LINT ---" ; npm run lint
```

- [ ] **Step 8: Commit**

```bash
git add frontend/src
git commit -m "feat(console): a benefit says what it pays" -m "A calculation method per benefit row with the amount its method needs, mirroring the server shape rule. The array now requires at least one benefit, replacing the 'no minimum coverage required' comment -- a product that covers nothing cannot be sold." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: full verification

- [ ] **Step 1: Grep for hand-written JSON that publishes a version**

Batch 3 lost 99 tests to exactly this. `benefitSchedule` is in every publish body, and the shape of its items has changed:

```
cd backend && grep -rln '"benefitSchedule"' src/test --include="*.java"
cd .. && grep -rln 'benefitSchedule' frontend/e2e
```

Every hit needs its benefit items checked against the new schema. The existing bodies use `{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}`, which stays valid — but `PolicyContractTest`'s `SUM_ASSURED_PLUS_BONUS` does not.

- [ ] **Step 2: Stop the dev backend, then clean-compile**

```
cd backend && ./mvnw clean test-compile
```

- [ ] **Step 3: Full backend suite**

```
cd backend && ./mvnw test
```

Expected: BUILD SUCCESS, roughly 1165 + 13. Nothing else running.

- [ ] **Step 4: Apply V13 to the dev database and restart**

```
cd backend && docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 < db-migrations/product/V13__benefit_calculation_method.sql
```

Confirm the normalisation ran:

```
docker exec infra-postgres-1 psql -U postgres -d lifeplatform -c "SELECT calculation_method, count(*) FROM product.benefit_schedule GROUP BY 1;"
```

Expected: only `SUM_ASSURED`, three rows — the two `untill death` rows normalised.

Then restart the backend and wait for port 8080.

- [ ] **Step 5: Update the seeder and e2e, then run the specs**

The seeder's publish body already sends `{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}`, which stays valid — confirm rather than assume. Every e2e spec that publishes a version must now add at least one benefit row through the form; find them with `grep -rln "Publish version" e2e/` and add the benefit before the publish click in each.

```
cd frontend && npx playwright test e2e/staff-products.spec.ts e2e/staff-distribution.spec.ts e2e/staff-group-schemes.spec.ts e2e/staff-policy-lifecycle.spec.ts e2e/staff-issue-policy.spec.ts e2e/staff-underwriting.spec.ts e2e/staff-claims.spec.ts
```

`staff-claims` is added because claim valuation changed.

- [ ] **Step 6: Commit**

```bash
git add backend/scripts frontend/e2e backend/src/test
git commit -m "test(product): every publish path authors a benefit" -m "Publishing a version that covers nothing is now refused, so the seeder and every e2e publish journey author one. Claims specs are in the run because claim valuation changed." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

## Done when

- [ ] Full backend suite green after `clean test-compile` then `clean test`.
- [ ] Frontend product scope green, plus typecheck and lint.
- [ ] The seven e2e specs green with V13 applied.
- [ ] A CI claim on a product authoring CI at 25% is valued at 25% — asserted.
- [ ] A CI claim on a death-only product is refused — asserted.
- [ ] A version with no benefits still issues one DEATH coverage — asserted, proving nothing in force changed.

## Deliberately not done

- **`getCoverageStatus` still ignores its `asOf`.** `policy.coverage` has no effective window, so coverage-at-a-date remains unanswerable. M13 called this the platform's most consequential open question in claims; it is untouched.
- **Group schemes keep one DEATH coverage.** Their claimable cover derives from the member schedule and never reads coverage rows, so per-benefit rows there would be decorative.
- **No with-profits.** `SUM_ASSURED_PLUS_BONUS` is absent until a bonus mechanism exists.
- **No endorsement of benefits onto in-force policies.** A grandfathered policy keeps the coverage it has.
