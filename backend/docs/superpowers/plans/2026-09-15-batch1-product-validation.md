# Batch 1 Product Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop `publishVersion` silently rewriting every in-force contract's IFRS measurement model, refuse a priced product that cannot price a life it says it accepts, and make `SmokerStatus.UNKNOWN` reachable from the issuance path.

**Architecture:** Two phases, split by whether a database migration is involved. Phase A (Tasks 1–4) is application logic only — no schema change, no entity change — so it is verified against five backend test classes and the product unit tests. Phase B (Tasks 5–6) moves `ifrs_measurement_model` from `product_definition` to `product_version`, which changes the migration list every integration test class loads and therefore requires the full backend suite.

**Tech Stack:** Java 21, Spring Boot 3 / Spring Modulith, Hibernate (`ddl-auto: none`), PostgreSQL 16, Flyway-style numbered SQL applied via `MigrationTestSupport` in tests, JUnit 5 + Testcontainers + AssertJ, React 19 + Zod + React Hook Form, Vitest, Playwright.

## Global Constraints

- Design spec: `backend/docs/superpowers/specs/2026-09-15-batch1-product-validation-design.md`. Every rule below is stated there; this plan does not introduce new requirements.
- All work happens on branch `underwriting-decision-step`, which is the working mainline (75 commits ahead of `main`).
- Run `./mvnw` on the **host**. Never run Maven inside Docker — it breaks Testcontainers networking.
- **Stop the dev backend before any `clean` target.** `clean` deletes `target/` under the live JVM and produces ~20 bogus failures across untouched modules. The tell is `NoClassDefFoundError` plus byte-buddy "class redefinition failed", never assertion failures.
- Never run two Maven builds concurrently. Never run `vitest` while Playwright is running.
- Never run Prettier. It is not a dependency and there is no config; it reformats the repo to its own defaults.
- `age_to`, `sum_assured_to` and every bound on this platform are **inclusive**.
- A published rating table is a priced contract term. Correcting one is a republish, never an `UPDATE` in a migration — the position `V8` already took.
- Existing rows are grandfathered. All validation added here runs at publish time only.
- Commit messages end with: `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`
- Baseline before any change, all green: 138 backend tests across the five scoped classes, 84 frontend product unit tests across 7 files.

## File Structure

| File | Responsibility | Phase |
|---|---|---|
| `backend/.../product/application/ProductApiImpl.java` | Gains two private static validators; priced branch calls them | A |
| `backend/.../policy/application/UnderwritingDecisionEventListener.java` | Maps unrecorded smoker status to `UNKNOWN` | A |
| `backend/.../product/api/ProductApi.java` | Javadoc correction on `resolveBaseRatePerMille` | A |
| `backend/.../product/api/SmokerStatus.java` | Javadoc correction on `UNKNOWN` | A |
| `frontend/src/features/products/publishVersionSchema.ts` | Mirrors R1 and R2 | A |
| `backend/db-migrations/product/V10__ifrs_measurement_model_on_version.sql` | Moves the column | B |
| `backend/.../product/domain/ProductVersion.java` | Carries the model | B |
| `backend/.../product/domain/ProductDefinition.java` | `activate()` replaces `activateWithMeasurementModel(String)` | B |
| 46 backend test classes | Add V10 to their migration lists | B |

---

# Phase A — application logic, narrow verification

## Task 1: R1 — a priced version must declare its entry-age range

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/product/ProductApiIntegrationTest.java`

**Interfaces:**
- Consumes: `EligibilityBounds` (existing record), `InvalidProductVersionException` (existing).
- Produces: `rejectPricedVersionWithoutEntryAgeBounds(EligibilityBounds)` — private static, throws `InvalidProductVersionException`. Task 2 relies on it having already run, so it may assume both bounds are non-null.

- [ ] **Step 1: Write the failing test**

Add to `ProductApiIntegrationTest`, after the existing M13 base-rate tests:

```java
    // ---- Batch 1: a priced version must be able to price what it accepts ----

    @Test
    void publishVersionRejectsAPricedVersionThatDoesNotSayWhatAgesItSellsTo() {
        ProductSummaryView product = productApi.createProduct("TERM-B1-R1", "Priced but unbounded",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        InvalidProductVersionException thrown = assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null,
                List.of(new ProductApi.BaseRateInput(18, 65, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("1.5000")),
                        new ProductApi.BaseRateInput(18, 65, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("2.0000"))),
                "actuary@nlolo.co.tz"));

        assertThat(thrown.getMessage()).contains("entry age");
    }

    @Test
    void publishVersionStillAcceptsAnUnpricedVersionWithNoBoundsAtAll() {
        ProductSummaryView product = productApi.createProduct("TERM-B1-R1-OK", "Unpriced, unbounded",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null,
            "actuary@nlolo.co.tz");

        assertThat(productApi.listActiveProducts(ProductCategory.TERM_LIFE))
            .anyMatch(p -> p.productCode().equals("TERM-B1-R1-OK"));
    }
```

- [ ] **Step 2: Run the tests to verify the first fails and the second passes**

```
cd backend && ./mvnw test -Dtest='ProductApiIntegrationTest#publishVersionRejectsAPricedVersionThatDoesNotSayWhatAgesItSellsTo+publishVersionStillAcceptsAnUnpricedVersionWithNoBoundsAtAll'
```

Expected: the first fails (the publish succeeds, so `assertThrows` reports nothing was thrown); the second passes. If the first *errors* for any other reason, stop and read it — it means the fixture is wrong, not the rule.

- [ ] **Step 3: Write the validator**

Add to `ProductApiImpl`, immediately above `rejectOverlappingAgeBands`:

```java
    /**
     * A priced version must say what ages it sells to.
     *
     * <p>Without bounds there is no declared range for {@link #rejectUncoveredEntryAges} to
     * check a rate table against, so the table's own span silently becomes the product's
     * selling range. That is not hypothetical: a real published version declares it accepts
     * entry ages 18-78 while pricing no woman under 56, and nothing could tell the difference
     * between "we price 18-30 deliberately" and "we forgot the rest".
     *
     * <p>Only priced versions. An unpriced version has no rate table to be incomplete against,
     * and bounds stay optional there exactly as {@link EligibilityBounds} describes.
     */
    private static void rejectPricedVersionWithoutEntryAgeBounds(EligibilityBounds bounds) {
        if (bounds == null || bounds.minEntryAge() == null || bounds.maxEntryAge() == null) {
            throw new InvalidProductVersionException(
                "A version priced from a base rate table must declare its minimum and maximum"
                    + " entry age. Without them the rate table's own span silently becomes the"
                    + " product's selling range, and nothing can tell a deliberate range from an"
                    + " incomplete one.");
        }
    }
```

- [ ] **Step 4: Call it from the priced branch**

In `publishVersion`, inside `if (priced) { ... }`, replace the line `rejectOverlappingAgeBands(baseRates);` with:

```java
            rejectOverlappingAgeBands(baseRates);
            rejectPricedVersionWithoutEntryAgeBounds(bounds);
```

Ordering matters: overlap is checked first so an overlapping table reports the overlap, which is the more specific fault.

- [ ] **Step 5: Run the two tests again**

```
cd backend && ./mvnw test -Dtest='ProductApiIntegrationTest#publishVersionRejectsAPricedVersionThatDoesNotSayWhatAgesItSellsTo+publishVersionStillAcceptsAnUnpricedVersionWithNoBoundsAtAll'
```

Expected: both PASS.

- [ ] **Step 6: Fix the existing fixtures this breaks**

Run the whole class:

```
cd backend && ./mvnw test -Dtest=ProductApiIntegrationTest
```

Every existing test that publishes a **priced** version and expects success now fails on R1. For each one, add bounds that match the rates it supplies by switching to the fullest overload and passing a real `EligibilityBounds`. Example shape — read each failing test and use the age span its own `BaseRateInput` rows cover:

```java
                new EligibilityBounds(30, 39, null, null, null, null),
```

Do **not** widen a test's rates to fit invented bounds; narrow the bounds to the rates already there. Tests that expect a rejection need no change — they already assert `InvalidProductVersionException`.

- [ ] **Step 7: Run the class until green**

```
cd backend && ./mvnw test -Dtest=ProductApiIntegrationTest
```

Expected: PASS, with two more tests than the 52 in the baseline.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java backend/src/test/java/tz/co/nlolo/lifeplatform/product/ProductApiIntegrationTest.java
git commit -m "feat(product): a priced version must say what ages it sells to" -m "Without entry-age bounds the rate table's own span silently becomes the product's selling range, and nothing distinguishes a deliberate range from an incomplete one. Unpriced versions are unaffected -- they have no table to be incomplete against." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: R2 — the table must cover every life the version accepts

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/product/ProductApiIntegrationTest.java`

**Interfaces:**
- Consumes: `rejectPricedVersionWithoutEntryAgeBounds` from Task 1 has already run, so `bounds.minEntryAge()` and `bounds.maxEntryAge()` are non-null.
- Produces: `rejectUncoveredEntryAges(List<BaseRateInput>, EligibilityBounds)` — private static, throws `InvalidProductVersionException` naming the combination and the uncovered ages.

- [ ] **Step 1: Write the failing tests**

```java
    /**
     * The shape of a real published version: women priced only from 56, men only to 56, on a
     * product declaring it accepts 18-78. Six cells, six distinct (sex, smoker) combinations and
     * a span of 18 to 78 in aggregate -- it looks complete and prices nobody in half its range.
     */
    @Test
    void publishVersionRejectsABaseRateTableWithAHoleInsideTheAgesItAccepts() {
        ProductSummaryView product = productApi.createProduct("TERM-B1-R2", "Coverage hole",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        InvalidProductVersionException thrown = assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null,
                List.of(new ProductApi.BaseRateInput(56, 78, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("2.1000")),
                        new ProductApi.BaseRateInput(18, 78, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("1.7000"))),
                new EligibilityBounds(18, 78, null, null, null, null),
                "actuary@nlolo.co.tz"));

        assertThat(thrown.getMessage())
            .contains("FEMALE/NON_SMOKER")
            .contains("18-55")
            .contains("18-78");
    }

    /** A combination absent entirely covers nothing, which is the asymmetric-table case. */
    @Test
    void publishVersionRejectsASmokerStatusPricedForOneSexOnly() {
        ProductSummaryView product = productApi.createProduct("TERM-B1-R2B", "Asymmetric table",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        InvalidProductVersionException thrown = assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null,
                List.of(new ProductApi.BaseRateInput(18, 65, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("1.5000")),
                        new ProductApi.BaseRateInput(18, 65, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("2.0000")),
                        new ProductApi.BaseRateInput(18, 65, Sex.FEMALE, SmokerStatus.SMOKER, new BigDecimal("3.0000"))),
                new EligibilityBounds(18, 65, null, null, null, null),
                "actuary@nlolo.co.tz"));

        assertThat(thrown.getMessage()).contains("MALE/SMOKER").contains("18-65");
    }

    /** Bands may run past the declared range; only holes inside it are faults. */
    @Test
    void publishVersionAcceptsATableThatCoversTheWholeDeclaredRange() {
        ProductSummaryView product = productApi.createProduct("TERM-B1-R2C", "Complete table",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null,
            List.of(new ProductApi.BaseRateInput(18, 45, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("1.6000")),
                    new ProductApi.BaseRateInput(46, 79, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("2.4000")),
                    new ProductApi.BaseRateInput(18, 79, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("2.5000"))),
            new EligibilityBounds(18, 40, null, null, null, null),
            "actuary@nlolo.co.tz");

        assertThat(productApi.listActiveProducts(ProductCategory.TERM_LIFE))
            .anyMatch(p -> p.productCode().equals("TERM-B1-R2C"));
    }
```

- [ ] **Step 2: Run them to verify all three fail the right way**

```
cd backend && ./mvnw test -Dtest='ProductApiIntegrationTest#publishVersionRejectsABaseRateTableWithAHoleInsideTheAgesItAccepts+publishVersionRejectsASmokerStatusPricedForOneSexOnly+publishVersionAcceptsATableThatCoversTheWholeDeclaredRange'
```

Expected: the first two FAIL because nothing is thrown; the third already PASSES.

- [ ] **Step 3: Write the validator**

Add to `ProductApiImpl`, directly below `rejectPricedVersionWithoutEntryAgeBounds`:

```java
    /**
     * A priced version must be able to price every life it says it will accept.
     *
     * <p>Ranges over the cross-product of sex and smoker status, not over age alone, because
     * age alone does not catch the defect. A real version's bands span its full declared 18-78
     * in aggregate while pricing women only from 56 and men only to 56 — an age-only rule reads
     * that table as complete.
     *
     * <p>Both sexes are required. The smoker statuses required are exactly those the table
     * prices somewhere: a product may decline to price {@link SmokerStatus#UNKNOWN} and demand a
     * declaration, which is a real underwriting stance, but pricing {@code SMOKER} for women and
     * not for men is an asymmetry with no product meaning. A combination absent entirely covers
     * nothing and fails here.
     *
     * <p>Application-level only. The SQL equivalent needs {@code EXCLUDE ... USING gist} over an
     * {@code int4range} and therefore {@code btree_gist} on every environment — the same
     * reasoning recorded on {@link #rejectOverlappingAgeBands}.
     *
     * <p>Package-private (not private) solely so {@code ProductCoverageRuleTest} can exercise this
     * premium-affecting range walk directly, without a Spring context — the same arrangement, for
     * the same reason, as {@code PolicyApiImpl.resolveSurrenderChargePercent}.
     */
    static void rejectUncoveredEntryAges(List<BaseRateInput> baseRates, EligibilityBounds bounds) {
        int min = bounds.minEntryAge();
        int max = bounds.maxEntryAge();

        java.util.Set<SmokerStatus> pricedSmokerStatuses = baseRates.stream()
            .map(BaseRateInput::smokerStatus)
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        for (Sex sex : Sex.values()) {
            for (SmokerStatus smokerStatus : pricedSmokerStatuses) {
                List<BaseRateInput> cells = baseRates.stream()
                    .filter(r -> r.sex() == sex && r.smokerStatus() == smokerStatus)
                    .sorted(java.util.Comparator.comparingInt(BaseRateInput::ageFrom))
                    .toList();

                // Walk the sorted bands, extending coverage through any band that starts at or
                // before the first still-uncovered age. Tolerates overlaps and bands running
                // past the declared range; stops at the first gap.
                int covered = min - 1;
                for (BaseRateInput cell : cells) {
                    if (cell.ageFrom() > covered + 1) {
                        break;
                    }
                    covered = Math.max(covered, cell.ageTo());
                }
                if (covered >= max) {
                    continue;
                }

                int gapFrom = covered + 1;
                int gapTo = cells.stream()
                    .filter(c -> c.ageFrom() > gapFrom)
                    .mapToInt(c -> c.ageFrom() - 1)
                    .min()
                    .orElse(max);
                throw new InvalidProductVersionException("Base rate table does not price "
                    + sex + "/" + smokerStatus + " for ages " + gapFrom + "-" + Math.min(gapTo, max)
                    + ", but this version accepts entry ages " + min + "-" + max
                    + ". A priced version must be able to price every life it says it will accept.");
            }
        }
    }
```

- [ ] **Step 4: Call it, after R1**

In `publishVersion`'s priced branch, the three calls now read:

```java
            rejectOverlappingAgeBands(baseRates);
            rejectPricedVersionWithoutEntryAgeBounds(bounds);
            rejectUncoveredEntryAges(baseRates, bounds);
```

- [ ] **Step 5: Run the three tests**

```
cd backend && ./mvnw test -Dtest='ProductApiIntegrationTest#publishVersionRejectsABaseRateTableWithAHoleInsideTheAgesItAccepts+publishVersionRejectsASmokerStatusPricedForOneSexOnly+publishVersionAcceptsATableThatCoversTheWholeDeclaredRange'
```

Expected: all three PASS.

- [ ] **Step 6: Unit-test the range walk without a Spring context**

The integration tests above prove the rule fires. They do not cheaply cover the walk's edges, and
this is arithmetic that decides whether a contract can be priced. Create
`backend/src/test/java/tz/co/nlolo/lifeplatform/product/ProductCoverageRuleTest.java`:

```java
package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.application.ProductApiImpl;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The range walk behind {@code rejectUncoveredEntryAges}, exercised directly.
 *
 * <p>No Spring context: this is pure arithmetic that decides whether a real contract can be
 * priced, and it deserves cheap edge coverage. Same precedent as
 * {@code PolicyApiImplSurrenderChargeTest}.
 */
class ProductCoverageRuleTest {

    private static ProductApi.BaseRateInput cell(int from, int to, Sex sex, SmokerStatus smoker) {
        return new ProductApi.BaseRateInput(from, to, sex, smoker, new BigDecimal("1.0000"));
    }

    private static EligibilityBounds ages(int min, int max) {
        return new EligibilityBounds(min, max, null, null, null, null);
    }

    @Test
    void oneBandCoveringTheWholeRangeIsEnough() {
        assertThatCode(() -> ProductApiImpl.rejectUncoveredEntryAges(
            List.of(cell(18, 65, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(18, 65, Sex.MALE, SmokerStatus.NON_SMOKER)),
            ages(18, 65))).doesNotThrowAnyException();
    }

    @Test
    void adjacentBandsJoinUpBecauseAgeToIsInclusive() {
        assertThatCode(() -> ProductApiImpl.rejectUncoveredEntryAges(
            List.of(cell(18, 45, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(46, 65, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(18, 65, Sex.MALE, SmokerStatus.NON_SMOKER)),
            ages(18, 65))).doesNotThrowAnyException();
    }

    @Test
    void overlappingBandsStillCover() {
        assertThatCode(() -> ProductApiImpl.rejectUncoveredEntryAges(
            List.of(cell(18, 50, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(40, 65, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(18, 65, Sex.MALE, SmokerStatus.NON_SMOKER)),
            ages(18, 65))).doesNotThrowAnyException();
    }

    @Test
    void bandsMayRunPastTheDeclaredRange() {
        assertThatCode(() -> ProductApiImpl.rejectUncoveredEntryAges(
            List.of(cell(0, 120, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(0, 120, Sex.MALE, SmokerStatus.NON_SMOKER)),
            ages(18, 65))).doesNotThrowAnyException();
    }

    @Test
    void aGapInTheMiddleIsNamedByItsOwnEdges() {
        assertThatThrownBy(() -> ProductApiImpl.rejectUncoveredEntryAges(
            List.of(cell(18, 30, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(41, 65, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(18, 65, Sex.MALE, SmokerStatus.NON_SMOKER)),
            ages(18, 65)))
            .isInstanceOf(InvalidProductVersionException.class)
            .hasMessageContaining("FEMALE/NON_SMOKER")
            .hasMessageContaining("31-40");
    }

    @Test
    void aBandStartingAboveTheMinimumLeavesTheBottomUncovered() {
        assertThatThrownBy(() -> ProductApiImpl.rejectUncoveredEntryAges(
            List.of(cell(56, 78, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(18, 78, Sex.MALE, SmokerStatus.NON_SMOKER)),
            ages(18, 78)))
            .isInstanceOf(InvalidProductVersionException.class)
            .hasMessageContaining("18-55");
    }

    @Test
    void aCombinationThatIsAbsentEntirelyCoversNothing() {
        assertThatThrownBy(() -> ProductApiImpl.rejectUncoveredEntryAges(
            List.of(cell(18, 65, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(18, 65, Sex.MALE, SmokerStatus.NON_SMOKER),
                    cell(18, 65, Sex.FEMALE, SmokerStatus.SMOKER)),
            ages(18, 65)))
            .isInstanceOf(InvalidProductVersionException.class)
            .hasMessageContaining("MALE/SMOKER")
            .hasMessageContaining("18-65");
    }

    @Test
    void aSmokerStatusTheTableNeverPricesIsNotDemanded() {
        // UNKNOWN is absent from the table entirely, which is a product declining to price the
        // undeclared case -- a real stance, not a hole.
        assertThatCode(() -> ProductApiImpl.rejectUncoveredEntryAges(
            List.of(cell(18, 65, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(18, 65, Sex.MALE, SmokerStatus.NON_SMOKER)),
            ages(18, 65))).doesNotThrowAnyException();
    }
}
```

Run it:

```
cd backend && ./mvnw test -Dtest=ProductCoverageRuleTest
```

Expected: 8 tests PASS. If `aGapInTheMiddleIsNamedByItsOwnEdges` reports `31-65` instead of
`31-40`, the gap-end calculation is taking `max` rather than the next band's start — re-read
Step 3's `gapTo` computation.

- [ ] **Step 7: Run both affected integration classes**

```
cd backend && ./mvnw test -Dtest=ProductApiIntegrationTest,PolicyApiIntegrationTest
```

Fix any priced fixture that now has a coverage hole by the same rule as Task 1 Step 6 — narrow the bounds to the rates present, never invent rates.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java backend/src/test/java/tz/co/nlolo/lifeplatform/product/ProductApiIntegrationTest.java backend/src/test/java/tz/co/nlolo/lifeplatform/product/ProductCoverageRuleTest.java backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyApiIntegrationTest.java
git commit -m "feat(product): a priced table must cover every life the version accepts" -m "Checked across the cross-product of sex and smoker status, not age alone: a real version's bands span its declared 18-78 in aggregate while pricing women only from 56 and men only to 56, and an age-only rule reads that table as complete. A combination the table never prices covers nothing and fails." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: the console refuses the same two shapes

**Files:**
- Modify: `frontend/src/features/products/publishVersionSchema.ts`
- Test: `frontend/src/features/products/publishVersionSchema.test.ts`

**Interfaces:**
- Consumes: the existing `publishVersionFormSchema(category)` factory and its object-level `superRefine`, which already computes `const priced = values.baseRates.some((r) => pricedCells(r).length > 0);`.
- Produces: no new exports. Two new issues raised inside the existing refinement.

- [ ] **Step 1: Write the failing tests**

Add to `publishVersionSchema.test.ts`, following the file's existing helper style for building a valid form value:

```ts
  it('requires entry age bounds once any base rate is priced', () => {
    const result = publishVersionFormSchema('TERM_LIFE').safeParse({
      ...validPricedForm(),
      minEntryAge: '',
      maxEntryAge: '',
    });
    expect(result.success).toBe(false);
    expect(JSON.stringify(result.error?.issues)).toContain('entry age');
  });

  it('rejects a priced table with a hole inside the ages it accepts', () => {
    const result = publishVersionFormSchema('TERM_LIFE').safeParse({
      ...validPricedForm(),
      minEntryAge: '18',
      maxEntryAge: '78',
      baseRates: [
        { ageFrom: '56', ageTo: '78', sex: 'FEMALE', nonSmoker: '2.1', smoker: '', unknown: '' },
        { ageFrom: '18', ageTo: '78', sex: 'MALE', nonSmoker: '1.7', smoker: '', unknown: '' },
      ],
    });
    expect(result.success).toBe(false);
    expect(JSON.stringify(result.error?.issues)).toContain('18-55');
  });
```

If `validPricedForm()` does not already exist in the file, add it beside the existing helpers:

```ts
  const validPricedForm = () => ({
    ...blankPublishVersionForm(),
    effectiveDate: '2026-01-01',
    minEntryAge: '18',
    maxEntryAge: '65',
    ratingTable: [{ factorType: 'SUM_ASSURED_BAND', band: 'LOW', multiplier: 1, ageFrom: '', ageTo: '', sumAssuredFrom: '', sumAssuredTo: '' }],
    baseRates: [
      { ageFrom: '18', ageTo: '65', sex: 'FEMALE', nonSmoker: '1.5', smoker: '', unknown: '' },
      { ageFrom: '18', ageTo: '65', sex: 'MALE', nonSmoker: '2.0', smoker: '', unknown: '' },
    ],
  });
```

- [ ] **Step 2: Run them to verify they fail**

```
cd frontend && npx vitest run src/features/products/publishVersionSchema.test.ts
```

Expected: both new cases FAIL — the schema currently accepts both shapes.

- [ ] **Step 3: Add the two rules to the object-level refinement**

Inside the existing `.superRefine((values, ctx) => { ... })`, within the `if (priced) { ... }` branch and after the existing double-count loop, add:

```ts
      // R1. Mirrors ProductApiImpl.rejectPricedVersionWithoutEntryAgeBounds. Without a declared
      // range the rate table's own span silently becomes the product's selling range.
      if (!values.minEntryAge || !values.maxEntryAge) {
        ctx.addIssue({
          code: 'custom',
          path: [values.minEntryAge ? 'maxEntryAge' : 'minEntryAge'],
          message:
            'A priced product must say what entry age it sells to — otherwise the rate table’s own span becomes the answer by accident',
        });
      } else {
        // R2. Mirrors ProductApiImpl.rejectUncoveredEntryAges: every sex, and every smoker
        // status priced anywhere, must cover the declared range. Checked per combination
        // because a table can span the whole range in aggregate and still price nobody in
        // half of it.
        const min = Number(values.minEntryAge);
        const max = Number(values.maxEntryAge);
        const cells = values.baseRates.flatMap((row) =>
          pricedCells(row).map((c) => ({
            sex: row.sex,
            smokerStatus: c.smokerStatus,
            from: Number(row.ageFrom),
            to: Number(row.ageTo),
          })),
        );
        const statuses = [...new Set(cells.map((c) => c.smokerStatus))];

        for (const sex of ['FEMALE', 'MALE'] as const) {
          for (const status of statuses) {
            const series = cells
              .filter((c) => c.sex === sex && c.smokerStatus === status)
              .sort((a, b) => a.from - b.from);

            let covered = min - 1;
            for (const c of series) {
              if (c.from > covered + 1) break;
              covered = Math.max(covered, c.to);
            }
            if (covered >= max) continue;

            const gapFrom = covered + 1;
            const laterStarts = series.filter((c) => c.from > gapFrom).map((c) => c.from - 1);
            const gapTo = Math.min(laterStarts.length ? Math.min(...laterStarts) : max, max);
            ctx.addIssue({
              code: 'custom',
              path: ['baseRates'],
              message: `Base rates do not price ${sex}/${status} for ages ${gapFrom}-${gapTo}, but this version accepts entry ages ${min}-${max}`,
            });
          }
        }
      }
```

- [ ] **Step 4: Run the file**

```
cd frontend && npx vitest run src/features/products/publishVersionSchema.test.ts
```

Expected: PASS.

- [ ] **Step 5: Run the full product unit scope and typecheck**

```
cd frontend && npx vitest run src/features/products src/store/productStore.test.ts src/components/ProductName.test.tsx ; echo "--- TYPECHECK ---" ; npm run typecheck ; echo "--- LINT ---" ; npm run lint
```

Expected: all green. Baseline was 84 tests; expect 86.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/products/publishVersionSchema.ts frontend/src/features/products/publishVersionSchema.test.ts
git commit -m "feat(console): refuse a priced version that cannot price what it accepts" -m "Mirrors ProductApiImpl's two new rules, as this schema already mirrors every other publish rule. The console would otherwise accept the exact table shape that prices no woman under 56 without a word, and the form is the only place an actuary can still fix it." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: an unrecorded smoker status becomes `UNKNOWN` at issuance

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/UnderwritingDecisionEventListener.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/ProductApi.java` (javadoc only)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/SmokerStatus.java` (javadoc only)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyApiIntegrationTest.java`

**Interfaces:**
- Consumes: `ProductApi.resolveBaseRatePerMille(UUID, int, Sex, SmokerStatus)` — unchanged signature, unchanged null-guard.
- Produces: no new API. Behaviour change only.

- [ ] **Step 1: Write the failing test**

Add to `PolicyApiIntegrationTest`, in the automatic-issuance area. Follow the class's existing fixture helpers for creating a KYC-verified party and a decided underwriting case; the assertion is what matters:

This follows `endToEndAutoIssuanceFiresFromARealUnderwritingDecision`'s structure exactly — the
same real producer→consumer path and the same bounded retry loop, because the `AFTER_COMMIT`
listener fires asynchronously in timing relative to `decide`'s return. It does **not** use
`buildFixture`, because that helper publishes an unpriced version and registers a party through
the legacy five-argument overload, which builds `IndividualRegistration.minimal` with a null sex.

`product.api.Sex` and `party.api.Sex` are two enums for one concept — neither module may depend
on the other — so both are named in full here, as `UnderwritingDecisionEventListener` already
does at the same boundary.

```java
    /**
     * SmokerStatus.UNKNOWN is documented as "a real, ratable value rather than a null stand-in:
     * a product may price undeclared smoker status deliberately". It was unreachable: issuance
     * passed null for an unrecorded status, and resolveBaseRatePerMille returns empty for a null,
     * so a product could author that cell, show it on the product screen, and price nothing.
     */
    @Test
    void automaticIssuancePricesAnUnrecordedSmokerStatusFromTheProductsUnknownCell() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        // A life with a recorded sex and date of birth but NO recorded smoker status. Sex must be
        // present or resolveBaseRatePerMille refuses on sex before smoker status is consulted --
        // which is the whole reason this mapping change does not, on its own, unblock the 34
        // clients in dev who have neither.
        PartyView applicant = partyApi.registerIndividual(
            new IndividualRegistration("Unknown Smoker", LocalDate.of(1990, 1, 1),
                "+255713009001", null,
                tz.co.nlolo.lifeplatform.party.api.Sex.FEMALE, null,
                null, null, null, null, null, null),
            "test-agent");

        // A product that prices the undeclared case deliberately, across the range it accepts.
        ProductSummaryView product = productApi.createProduct("POLICY-UNK-01", "Prices the undeclared case",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null,
            List.of(new ProductApi.BaseRateInput(18, 65, tz.co.nlolo.lifeplatform.product.api.Sex.FEMALE,
                        tz.co.nlolo.lifeplatform.product.api.SmokerStatus.UNKNOWN, new BigDecimal("1.0000")),
                    new ProductApi.BaseRateInput(18, 65, tz.co.nlolo.lifeplatform.product.api.Sex.MALE,
                        tz.co.nlolo.lifeplatform.product.api.SmokerStatus.UNKNOWN, new BigDecimal("1.0000"))),
            new EligibilityBounds(18, 65, null, null, null, null),
            "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());

        UnderwritingCaseView opened = underwritingApi.openCase(applicant.partyId(), product.productId(),
            snapshot.productVersionId(), new BigDecimal("1000000"), "TZS", null, "agent1");
        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Normal findings",
            new BigDecimal("10"), "underwriter1");
        underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Standard risk"), "underwriter1", false);

        List<PolicyView> found = List.of();
        for (int attempt = 0; attempt < 50; attempt++) {
            TenantContext.set(tenantId);
            found = policyApi.searchPolicies(applicant.partyId(), null, null, null, null, PageRequest.of(0, 10)).getContent();
            if (!found.isEmpty()) {
                break;
            }
            Thread.sleep(100);
        }

        // That a policy exists at all is the falsifiable part: before this change issuance threw,
        // recorded the failure on the case, and created nothing.
        assertThat(found).hasSize(1);
        // And it was priced from the UNKNOWN cell, not from anything else. The version carries a
        // base rate table so the flat TZ_BASE_PREMIUM_RATE_PER_MILLE path is not taken; age and
        // smoker multipliers are forbidden on a priced version; the SUM_ASSURED_BAND row carries
        // no amount bounds so it resolves neutral; and the decision carries no loading. So the
        // whole premium is 1,000,000 / 1000 * 1.0000 = 1,000 a year, billed monthly.
        assertThat(found.get(0).premiumAmount()).isEqualByComparingTo(new BigDecimal("83.33"));
    }
```

Add the imports this needs if the class lacks them:
`tz.co.nlolo.lifeplatform.party.api.IndividualRegistration` and
`tz.co.nlolo.lifeplatform.product.api.EligibilityBounds`.

- [ ] **Step 2: Run it to verify it fails**

```
cd backend && ./mvnw test -Dtest='PolicyApiIntegrationTest#automaticIssuancePricesAnUnrecordedSmokerStatusFromTheProductsUnknownCell'
```

Expected: FAIL — issuance refuses, because the unrecorded status is passed as `null`.

- [ ] **Step 3: Change the mapping**

In `UnderwritingDecisionEventListener.baseRatePerMilleFor`, replace the `resolveBaseRatePerMille` call's smoker argument. The sex argument is deliberately left alone:

```java
        return productApi.resolveBaseRatePerMille(decidedCase.productVersionId(), ageAtEntry,
                life.sex() != null
                    ? tz.co.nlolo.lifeplatform.product.api.Sex.valueOf(life.sex().name()) : null,
                // An unrecorded smoker status is UNKNOWN, not absent. SmokerStatus.UNKNOWN exists
                // precisely so a product can price the undeclared case deliberately, and passing
                // null made that cell unreachable from the only path that issues a contract.
                // Sex has no such value on purpose -- a third value there would be a unisex rate,
                // a different actuarial object -- so an unrecorded sex still refuses below.
                life.smokerStatus() != null
                    ? tz.co.nlolo.lifeplatform.product.api.SmokerStatus.valueOf(life.smokerStatus().name())
                    : tz.co.nlolo.lifeplatform.product.api.SmokerStatus.UNKNOWN)
            .orElseThrow(() -> new IllegalStateException("Product version "
                + decidedCase.productVersionId() + " has no base rate for age " + ageAtEntry
                + ", sex " + life.sex() + ", smoker status "
                + (life.smokerStatus() != null ? life.smokerStatus().name() : "UNKNOWN (never recorded)")
                + ". The rate table does not cover this life, so there is no price to charge —"
                + " either the table has a gap, the client's sex was never recorded, or this"
                + " product does not price the undeclared smoker case. All three are fixable;"
                + " guessing a rate is not."));
```

- [ ] **Step 4: Correct the two javadocs that now contradict this**

In `ProductApi.resolveBaseRatePerMille`, replace the two `@param` lines:

```java
     * @param sex null when unrecorded, which matches no cell — a priced product needs the fact,
     *            and there is deliberately no neutral value to fall back to.
     * @param smokerStatus never null from the issuance path, which maps an unrecorded status to
     *                     {@link SmokerStatus#UNKNOWN} so a product that priced the undeclared
     *                     case is actually used. Null still matches no cell, for callers that
     *                     genuinely have nothing to assert.
```

In `SmokerStatus`, replace the final paragraph of the `UNKNOWN` javadoc:

```java
 * <p>Reachable from BOTH paths. A quote asserts it, and automatic issuance maps a life whose
 * smoker status was never recorded onto it — so a product that deliberately priced the
 * undeclared case gets used, and one that did not still refuses, naming the reason. It was
 * unreachable from issuance until then: an unrecorded status was passed as null, so the cell
 * could be authored, shown on the product screen, and price nothing.
```

- [ ] **Step 5: Run the test and the class**

```
cd backend && ./mvnw test -Dtest=PolicyApiIntegrationTest
```

Expected: PASS, one test more than the 45 in the baseline.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/UnderwritingDecisionEventListener.java backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/ProductApi.java backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/SmokerStatus.java backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyApiIntegrationTest.java
git commit -m "fix(policy,product): an unrecorded smoker status is UNKNOWN, not absent" -m "SmokerStatus.UNKNOWN is documented as a real ratable value a product may price deliberately, and it has never been reachable from the path that issues a contract: issuance passed null, and resolveBaseRatePerMille returns empty for a null. An actuary could author the cell, see it on the product screen, and watch it price nothing -- the same shape V5 and V9 removed for AGE and SUM_ASSURED_BAND." -m "Sex keeps refusing when unrecorded. A neutral value there would be a unisex rate, which is a different actuarial object and is not invented here." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 7: Verify Phase A against the narrow scope**

```
cd backend && ./mvnw test -Dtest=ProductApiIntegrationTest,ProductContractTest,ProductCoverageRuleTest,PolicyApiIntegrationTest,AppRolePrivilegesIntegrationTest,RowLevelSecurityIntegrationTest -DfailIfNoSpecifiedTests=false
```

Expected: BUILD SUCCESS, 152 tests — the 138 baseline, plus 6 new integration tests and the 8 in `ProductCoverageRuleTest`.

Phase A touches no schema and no entity, so this scope is the whole of its blast radius. Phase B's is not, and Task 6 says why.

---

# Phase B — the migration, wide verification

## Task 5: `ifrs_measurement_model` moves to `product_version`

**Files:**
- Create: `backend/db-migrations/product/V10__ifrs_measurement_model_on_version.sql`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/domain/ProductVersion.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/domain/ProductDefinition.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/product/ProductApiIntegrationTest.java`

**Interfaces:**
- Consumes: nothing from Phase A.
- Produces: `ProductVersion(UUID tenantId, UUID productId, LocalDate effectiveDate, LocalDate retirementDate, int gracePeriodDays, BigDecimal maxLoanToValuePercent, String ifrsMeasurementModel, String createdBy)` — the model is inserted as the seventh parameter, before `createdBy`. `ProductVersion.getIfrsMeasurementModel()` returns `String`. `ProductDefinition.activate()` replaces `activateWithMeasurementModel(String)`. `ProductDefinition.getIfrsMeasurementModel()` is removed.

- [ ] **Step 1: Write the migration**

Create `backend/db-migrations/product/V10__ifrs_measurement_model_on_version.sql`:

```sql
-- The IFRS 17 measurement model belongs to the VERSION, not the product.
--
-- WHAT HAPPENED. publishVersion ends with an unconditional
-- product.activateWithMeasurementModel(...), and the column lives on product_definition. So
-- republishing a product with a different model silently rewrote the measurement basis of every
-- in-force contract ever issued under that product, retroactively. There is no test for it and
-- nothing reads the field, which is why it has been invisible.
--
-- WHY MOVE IT RATHER THAN GUARD IT. PAA eligibility turns on the coverage period, and
-- min_term_months / max_term_months already live on product_version. The fact that decides the
-- model sat on the version while the model itself sat on the product, so a product whose second
-- version sells a thirty-year term instead of a twelve-month one had no way to say its model
-- changed except by rewriting the first version's. Moving the column puts the decision next to
-- its evidence, and makes in-force contracts immune by construction: a policy pins a
-- product_version_id, so it keeps its own basis forever. A legitimate change becomes a new
-- version, which is what it always should have been.
--
-- BACKFILL IS SAFE, verified against the dev database before writing this: all 128 existing
-- versions join to a product carrying a non-null model, and no DRAFT product has a version. A
-- DRAFT product has a null model and no versions, because a version only exists after a publish
-- and every publish supplies one.
--
-- Written as ONE migration rather than the two-phase expand/contract sequence, because the
-- column has no readers outside the product module and there is no rolling-deploy window to
-- protect. If this platform ever adopts rolling deploys, this is the migration to split.

ALTER TABLE product.product_version
    ADD COLUMN ifrs_measurement_model VARCHAR(10)
    CHECK (ifrs_measurement_model IN ('GMM','PAA'));

UPDATE product.product_version pv
   SET ifrs_measurement_model = pd.ifrs_measurement_model
  FROM product.product_definition pd
 WHERE pd.product_id = pv.product_id;

ALTER TABLE product.product_version
    ALTER COLUMN ifrs_measurement_model SET NOT NULL;

ALTER TABLE product.product_definition
    DROP COLUMN ifrs_measurement_model;

COMMENT ON COLUMN product.product_version.ifrs_measurement_model IS
    'GMM or PAA, decided per version. A policy pins a product_version_id, so its measurement '
    'basis cannot be changed by a later republish -- which is exactly what happened while this '
    'column lived on product_definition.';
```

- [ ] **Step 2: Write the failing regression test**

```java
    /**
     * The defect this migration removes: publishVersion called
     * activateWithMeasurementModel unconditionally, so a republish rewrote the measurement basis
     * of every contract already issued under the product.
     */
    @Test
    void republishingWithADifferentMeasurementModelLeavesTheEarlierVersionAlone() {
        ProductSummaryView product = productApi.createProduct("TERM-B1-IFRS", "Two models",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA,
            LocalDate.now().minusYears(2), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");
        UUID firstVersionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        productApi.publishVersion(product.productId(), IfrsMeasurementModel.GMM,
            LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");

        assertThat(productApi.getSnapshotByVersionId(firstVersionId).ifrsMeasurementModel())
            .isEqualTo(IfrsMeasurementModel.PAA);
        assertThat(productApi.getActiveSnapshot(product.productId(), LocalDate.now()).ifrsMeasurementModel())
            .isEqualTo(IfrsMeasurementModel.GMM);
    }
```

- [ ] **Step 3: Add V10 to `ProductApiIntegrationTest`'s migration list, then run it to verify it fails**

Add `"db-migrations/product/V10__ifrs_measurement_model_on_version.sql",` to the `applyMigration(...)` call in `ProductApiIntegrationTest`.

```
cd backend && ./mvnw test -Dtest='ProductApiIntegrationTest#republishingWithADifferentMeasurementModelLeavesTheEarlierVersionAlone'
```

Expected: FAIL. Either the assertion reports `GMM` where `PAA` was expected (the old behaviour), or Hibernate errors because `ProductDefinition` still maps the dropped column — both confirm the test is exercising the right thing.

- [ ] **Step 4: Move the field onto `ProductVersion`**

Add the column mapping beside `gracePeriodDays`:

```java
    @Column(name = "ifrs_measurement_model", nullable = false)
    private String ifrsMeasurementModel;
```

Add the accessor beside `getGracePeriodDays()`:

```java
    public String getIfrsMeasurementModel() { return ifrsMeasurementModel; }
```

Change the constructor — the model goes in before `createdBy`:

```java
    public ProductVersion(UUID tenantId, UUID productId, LocalDate effectiveDate, LocalDate retirementDate,
                           int gracePeriodDays, BigDecimal maxLoanToValuePercent,
                           String ifrsMeasurementModel, String createdBy) {
        this.tenantId = tenantId;
        this.productId = productId;
        this.effectiveDate = effectiveDate;
        this.retirementDate = retirementDate;
        this.gracePeriodDays = gracePeriodDays;
        this.maxLoanToValuePercent = maxLoanToValuePercent;
        this.ifrsMeasurementModel = ifrsMeasurementModel;
        this.createdBy = createdBy;
    }
```

- [ ] **Step 5: Reduce `ProductDefinition` to activation only**

Delete the `ifrsMeasurementModel` field, its `@Column`, and `getIfrsMeasurementModel()`. Replace `activateWithMeasurementModel` with:

```java
    /**
     * A product becomes ACTIVE by having a version published against it, and never any other way.
     *
     * <p>The "a measurement model is required before leaving DRAFT" invariant used to be asserted
     * here. It is now structural: the model lives on {@code product_version}, which is NOT NULL,
     * so a version cannot exist without one and a product cannot be activated without a version.
     */
    public void activate() {
        this.status = "ACTIVE";
    }
```

- [ ] **Step 6: Update `ProductApiImpl`**

In `publishVersion`, pass the model into the version and activate plainly:

```java
        ProductVersion version = new ProductVersion(tenantId, productId, effectiveDate, retirementDate,
            gracePeriodDays, null, ifrsMeasurementModel.name(), publishedBy);
```

and at the end of the method:

```java
        product.activate();
        productDefinitionRepository.save(product);
```

In `getActiveSnapshot` and `getSnapshotByVersionId`, read the model from the version rather than the definition — in both, replace `IfrsMeasurementModel.valueOf(definition.getIfrsMeasurementModel())` with:

```java
            IfrsMeasurementModel.valueOf(version.getIfrsMeasurementModel()),
```

In `getActiveSnapshot` the `definition` local is still needed for `category`, so keep it. In `getSnapshotByVersionId` the `definition` local is likewise still needed for `category`.

- [ ] **Step 7: Run the regression test, then the class**

```
cd backend && ./mvnw test -Dtest=ProductApiIntegrationTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/db-migrations/product/V10__ifrs_measurement_model_on_version.sql backend/src/main/java/tz/co/nlolo/lifeplatform/product/domain/ProductVersion.java backend/src/main/java/tz/co/nlolo/lifeplatform/product/domain/ProductDefinition.java backend/src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java backend/src/test/java/tz/co/nlolo/lifeplatform/product/ProductApiIntegrationTest.java
git commit -m "fix(product): the IFRS measurement model belongs to the version, not the product" -m "publishVersion ended with an unconditional activateWithMeasurementModel, so republishing with a different model silently rewrote the measurement basis of every in-force contract ever issued under that product. Nothing read the field and no test covered it, which is why it was invisible." -m "Moved rather than guarded: PAA eligibility turns on the coverage period, and the term bounds that decide it already live on the version. A policy pins a product_version_id, so contracts are now immune by construction and a legitimate change is a new version." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: every integration test class loads V10, and the full suite proves it

**Files:**
- Modify: the 45 remaining backend test classes listing `db-migrations/product/V1__create_product_schema.sql` (46 total, minus `ProductApiIntegrationTest` done in Task 5)

**Interfaces:**
- Consumes: `V10__ifrs_measurement_model_on_version.sql` from Task 5.
- Produces: nothing. This task is what makes Phase B's schema change actually load everywhere.

- [ ] **Step 1: Confirm the full list**

```
cd backend && grep -rl "db-migrations/product/V9__rating_table_sum_assured_bounds.sql" src/test --include="*.java"
```

Expected: 46 files. Every one lists V9 today, so every one is a site for V10.

- [ ] **Step 2: Add V10 to all of them**

`ifrs_measurement_model` is `NOT NULL` on `product_version` and Hibernate includes it in every INSERT and SELECT, and `ddl-auto` is `none` so nothing catches the mismatch at boot — a class missing V10 fails at the first query touching a product version, with `column "ifrs_measurement_model" does not exist`.

Insert the line immediately after each V9 line, preserving indentation:

```java
            "db-migrations/product/V10__ifrs_measurement_model_on_version.sql",
```

A scripted edit is acceptable here because the change is a literal line insertion after a fixed anchor. Keep it pattern-only, and verify the count afterwards:

```
cd backend && grep -rl "V10__ifrs_measurement_model_on_version.sql" src/test --include="*.java" | wc -l
```

Expected: 46.

- [ ] **Step 3: Stop the dev backend before touching a `clean` target**

The dev backend started earlier in this session holds `target/`. `clean` under a live JVM produces ~20 failures across untouched modules with `NoClassDefFoundError` and byte-buddy "class redefinition failed".

Find and stop the `spring-boot:run` process before continuing.

- [ ] **Step 4: Compile everything, because a constructor signature changed**

```
cd backend && ./mvnw clean test-compile
```

Expected: BUILD SUCCESS. Maven's incremental compile would otherwise leave unchanged test classes uncompiled and hide the `ProductVersion` constructor break; this is the step that surfaces it.

- [ ] **Step 5: Run the full backend suite**

```
cd backend && ./mvnw test
```

Expected: BUILD SUCCESS. The baseline for the whole suite is ~945 tests; this branch adds 7.

Do not run anything else while this runs — no second Maven build, no Playwright.

- [ ] **Step 6: Apply V10 to the dev database and restart the backend**

Migrations and the dev backend are decoupled: green Testcontainers runs do not prove the dev database is in sync.

```
cd backend/infra && docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform < ../db-migrations/product/V10__ifrs_measurement_model_on_version.sql
```

Then restart the backend and confirm it serves:

```
cd backend && SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

- [ ] **Step 7: Run the three end-to-end specs**

Backend on 8080 and frontend on 5173 must both be up, and nothing else may be running.

```
cd frontend && npx playwright test e2e/staff-products.spec.ts e2e/staff-issue-policy.spec.ts e2e/staff-underwriting.spec.ts
```

Expected: PASS. A failure here is a real bug — including one that predates this branch. Bisecting proves authorship, not harmlessness.

- [ ] **Step 8: Commit**

```bash
git add backend/src/test
git commit -m "test(product): every integration test class loads V10" -m "ifrs_measurement_model is NOT NULL on product_version and Hibernate includes it in every insert and select, while ddl-auto is none -- so a class missing the migration fails at its first product-version query rather than at boot. All 46 classes that load the product schema list it." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Done when

- [ ] Full backend suite green after `clean test-compile` then `clean test`, with the dev backend stopped.
- [ ] Frontend product unit scope green, plus `npm run typecheck` and `npm run lint`.
- [ ] The three e2e specs green against the real stack with V10 applied to the dev database.
- [ ] `git log` shows six commits, each with its own passing test.

## Deliberately not done

- Cash value accrual, the premium formula, product governance — Batches 2–4.
- Capturing `sex` and `smoker_status` at client registration. **34 of 50 individuals remain unissuable on any priced product** because an unrecorded sex still refuses. This batch does not move that number, and nothing in it should be read as having fixed the priced-issuance path for real clients.
- Repairing the eight existing priced versions that could no longer be published as they stand. They are grandfathered as data; correcting one is a republish and an actuarial decision, not a migration.
