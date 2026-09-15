# Batch 2a Frequency Loading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Charge a monthly payer more than an annual payer, as every real life insurer does, and make an illustration and the policy it becomes come from one formula.

**Architecture:** A loading percent per frequency stored on `product_version` and carried across the API by a `FrequencyLoading` record, applied at exactly one point — after the risk arithmetic, before the annual premium is divided into instalments — by a single `applyTo` method both premium paths call. Separately, `quotePremium` stops asking callers for a sum-assured band string it cannot validate and resolves the multiplier from the amount, which is what issuance already does.

**Tech Stack:** Java 21, Spring Boot 3 / Spring Modulith, Hibernate (`ddl-auto: none`), PostgreSQL 16, numbered SQL applied via `MigrationTestSupport` in tests, JUnit 5 + Testcontainers + AssertJ, React 19 + Zod + React Hook Form, Vitest, Playwright.

## Global Constraints

- Design spec: `backend/docs/superpowers/specs/2026-09-15-batch2a-frequency-loading-design.md`. Every rule below is stated there.
- Branch: `batch2a-frequency-loading`, already created off `main` at `16779b9`.
- Run `./mvnw` on the **host**. Never run Maven inside Docker — it breaks Testcontainers networking.
- **Stop the dev backend before any `clean` target.** `clean` deletes `target/` under the live JVM and produces ~20 bogus failures across untouched modules, showing as `NoClassDefFoundError` plus byte-buddy "class redefinition failed", never assertion failures.
- **Run one suite at a time.** Never vitest during Playwright, never two Maven builds, and never vitest beside a full Maven run — that last one truncates the vitest run and still prints "passed", with only the file and test counts to give it away.
- Never run Prettier. It is not a dependency and there is no config.
- Round **once**, at the end, `HALF_UP` to 2dp. Intermediate values keep full precision.
- `DEFAULT 0` means this migration changes no premium anywhere. Any test that shows an existing version pricing differently is a bug in this work, not an expected change.
- Commit messages end with: `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`
- Baseline on `main` before this work: backend 1141 tests green, frontend 819 green.

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `product/api/FrequencyLoading.java` | The two percentages, their bounds, and the one arithmetic point | 1 |
| `db-migrations/product/V11__frequency_loading.sql` | Two columns, defaulted to zero | 1 |
| `product/domain/ProductVersion.java` | Stores them; `applyFrequencyLoading` / `getFrequencyLoading` | 1 |
| `product/api/ProductApi.java` | `publishVersion` overload, `resolveFrequencyLoading`, `PremiumQuoteInput`/`View` shape | 1, 2, 3 |
| `product/application/ProductApiImpl.java` | Publish plumbing; range resolution shared by quote and internal resolver | 1, 2, 3 |
| `product/infrastructure/PublishVersionRequest.java` + `FrequencyLoadingRequest.java` | Wire shape for authoring | 1 |
| `product/infrastructure/PremiumQuoteRequest.java`, `ProductController.java` | Drops `sumAssuredBand` | 2 |
| `api/openapi/openapi-product.yaml` | Contract for both changes | 2, 3 |
| `policy/application/UnderwritingDecisionEventListener.java` | Applies the loading at issuance | 4 |
| `frontend/src/features/products/publishVersionSchema.ts` + form + `RatingBasis` | Authoring and display | 5 |
| 46 backend test classes | V11 in their migration lists | 6 |

---

## Task 1: `FrequencyLoading`, the column, and the authoring path

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/FrequencyLoading.java`
- Create: `backend/db-migrations/product/V11__frequency_loading.sql`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/infrastructure/FrequencyLoadingRequest.java`
- Create: `backend/src/test/java/tz/co/nlolo/lifeplatform/product/application/FrequencyLoadingTest.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/domain/ProductVersion.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/ProductApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/infrastructure/PublishVersionRequest.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/infrastructure/ProductController.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/product/ProductApiIntegrationTest.java`

**Interfaces:**
- Produces:
  - `FrequencyLoading(BigDecimal monthlyPercent, BigDecimal quarterlyPercent)` — compact constructor normalises null to `BigDecimal.ZERO` and throws `IllegalArgumentException` outside 0–100.
  - `FrequencyLoading.none()` → both zero.
  - `FrequencyLoading.percentFor(PremiumFrequency)` → `BigDecimal`, always zero for `ANNUALLY`.
  - `FrequencyLoading.applyTo(BigDecimal annualPremium, PremiumFrequency)` → `BigDecimal`, the **only** place the loading arithmetic lives.
  - `ProductVersion.applyFrequencyLoading(FrequencyLoading)` / `ProductVersion.getFrequencyLoading()`.
  - `ProductApi.publishVersion(..., EligibilityBounds bounds, FrequencyLoading frequencyLoading, String publishedBy)` — the fullest overload, `frequencyLoading` inserted **after** `bounds`.
  - `ProductApi.resolveFrequencyLoading(UUID productVersionId)` → `FrequencyLoading`.

- [ ] **Step 1: Write the failing arithmetic test**

Create `backend/src/test/java/tz/co/nlolo/lifeplatform/product/application/FrequencyLoadingTest.java`:

```java
package tz.co.nlolo.lifeplatform.product.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.FrequencyLoading;
import tz.co.nlolo.lifeplatform.product.api.PremiumFrequency;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The frequency loading arithmetic, exercised without a Spring context.
 *
 * <p>This is the number a monthly payer is actually charged, so it gets cheap edge coverage of
 * its own rather than only being observed through a quote. Same arrangement as
 * {@code ProductCoverageRuleTest}.
 */
class FrequencyLoadingTest {

    private static final BigDecimal ANNUAL = new BigDecimal("12000");

    @Test
    void anUnloadedVersionChargesTheAnnualPremiumWhateverTheFrequency() {
        FrequencyLoading none = FrequencyLoading.none();
        for (PremiumFrequency f : PremiumFrequency.values()) {
            assertThat(none.applyTo(ANNUAL, f)).isEqualByComparingTo(ANNUAL);
        }
    }

    @Test
    void anEightPercentMonthlyLoadingRaisesTheAnnualPremiumByEightPercent() {
        FrequencyLoading loading = new FrequencyLoading(new BigDecimal("8"), new BigDecimal("3"));
        assertThat(loading.applyTo(ANNUAL, PremiumFrequency.MONTHLY))
            .isEqualByComparingTo(new BigDecimal("12960"));
    }

    @Test
    void quarterlyUsesItsOwnPercent() {
        FrequencyLoading loading = new FrequencyLoading(new BigDecimal("8"), new BigDecimal("3"));
        assertThat(loading.applyTo(ANNUAL, PremiumFrequency.QUARTERLY))
            .isEqualByComparingTo(new BigDecimal("12360"));
    }

    @Test
    void annualIsNeverLoaded() {
        FrequencyLoading loading = new FrequencyLoading(new BigDecimal("8"), new BigDecimal("3"));
        assertThat(loading.percentFor(PremiumFrequency.ANNUALLY)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(loading.applyTo(ANNUAL, PremiumFrequency.ANNUALLY)).isEqualByComparingTo(ANNUAL);
    }

    @Test
    void nullMeansUnloaded() {
        FrequencyLoading loading = new FrequencyLoading(null, null);
        assertThat(loading.monthlyPercent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(loading.quarterlyPercent()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aLoadingOutsideZeroToOneHundredIsRefused() {
        assertThatThrownBy(() -> new FrequencyLoading(new BigDecimal("-1"), BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Monthly");
        assertThatThrownBy(() -> new FrequencyLoading(BigDecimal.ZERO, new BigDecimal("101")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Quarterly");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```
cd backend && ./mvnw test -Dtest=FrequencyLoadingTest
```

Expected: compilation failure — `FrequencyLoading` does not exist.

- [ ] **Step 3: Write the record**

Create `backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/FrequencyLoading.java`:

```java
package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How much more a version charges for paying in instalments rather than once a year.
 *
 * <p>A percentage added to the ANNUAL premium before it is divided, not a modal factor. Both
 * express the same thing; only this one can be shown to a policyholder as a reason. {@code 0.0875}
 * does not announce that it is a five percent uplift, and {@link ProductApi.PremiumQuoteView}
 * exists precisely so an illustration can be explained.
 *
 * <p>Why a product charges more for monthly at all: the annual payer's whole premium is available
 * to invest on day one, twelve collections cost more than one, monthly business lapses more often
 * and lapses part-paid, and commission has already been accrued against premium that will not
 * arrive. Market convention is monthly five to nine percent above annual, quarterly two to four.
 *
 * <p>Deliberately NOT a {@code rating_table} factor type, which would have reused the authoring
 * form and the publish-time rules. That table is RISK rating — it feeds {@code RiskProfile} and so
 * an underwriting decision — and a payment frequency is not a risk fact. One table meaning two
 * things is the ambiguity that produced this platform's band-string defects.
 *
 * <p>A record rather than two more parameters because {@code publishVersion} already takes ten;
 * {@code IndividualRegistration} records the same reasoning for the same reason.
 */
public record FrequencyLoading(BigDecimal monthlyPercent, BigDecimal quarterlyPercent) {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public FrequencyLoading {
        monthlyPercent = normalise("Monthly", monthlyPercent);
        quarterlyPercent = normalise("Quarterly", quarterlyPercent);
    }

    private static BigDecimal normalise(String label, BigDecimal percent) {
        if (percent == null) {
            return BigDecimal.ZERO;
        }
        if (percent.signum() < 0 || percent.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException(label + " frequency loading must be between 0 and 100"
                + " percent, was " + percent.toPlainString());
        }
        return percent;
    }

    /** No loading: every frequency costs the same over a year. A real pricing decision. */
    public static FrequencyLoading none() {
        return new FrequencyLoading(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** Always zero for {@code ANNUALLY} — it is the baseline the others load against. */
    public BigDecimal percentFor(PremiumFrequency frequency) {
        return switch (frequency) {
            case MONTHLY -> monthlyPercent;
            case QUARTERLY -> quarterlyPercent;
            case ANNUALLY -> BigDecimal.ZERO;
        };
    }

    /**
     * The annual premium this frequency is actually divided from.
     *
     * <p>The ONE place this arithmetic lives. Both the quote path and the issuance path call it,
     * which is the point of this change: an illustration and the first invoice must come from one
     * formula. Full precision is kept — the caller rounds once, at the end.
     */
    public BigDecimal applyTo(BigDecimal annualPremium, PremiumFrequency frequency) {
        BigDecimal percent = percentFor(frequency);
        if (percent.signum() == 0) {
            return annualPremium;
        }
        return annualPremium.multiply(BigDecimal.ONE.add(percent.divide(HUNDRED, 6, RoundingMode.HALF_UP)));
    }
}
```

- [ ] **Step 4: Run the unit test**

```
cd backend && ./mvnw test -Dtest=FrequencyLoadingTest
```

Expected: 6 tests PASS.

- [ ] **Step 5: Write the migration**

Create `backend/db-migrations/product/V11__frequency_loading.sql`:

```sql
-- What a version charges for paying in instalments rather than once a year.
--
-- PremiumFrequency divides the annual premium by 12, 4 or 1 EXACTLY, so a monthly payer and an
-- annual payer are charged the same total. No real life insurer does that. The annual payer's
-- whole premium is available to invest on day one; twelve collections cost more than one; monthly
-- business lapses more often and lapses part-paid; and distribution has already accrued commission
-- against premium that will not arrive. Market convention is monthly 5-9% above annual.
--
-- NOT a rating_table factor type, which would have reused the authoring form and the publish-time
-- rules. That table is RISK rating -- it feeds RiskProfile and therefore an underwriting decision
-- -- and a payment frequency is not a risk fact. One table meaning two things is the ambiguity
-- that produced this platform's band-string defects (V5, V9, and the quote path).
--
-- No column for ANNUALLY: it is the baseline the others load against, and a column for it could
-- only ever hold 0 or contradict itself.
--
-- NOT NULL DEFAULT 0, not nullable. "No loading" is a real pricing decision -- charge the same
-- whatever the frequency -- rather than an absence. Every one of the 128 existing versions
-- therefore gets 0 and prices EXACTLY as it does today: this migration changes no premium
-- anywhere, and a test showing otherwise is a defect in the change, not an expected difference.

ALTER TABLE product.product_version
    ADD COLUMN monthly_loading_percent   NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN quarterly_loading_percent NUMERIC(5,2) NOT NULL DEFAULT 0;

ALTER TABLE product.product_version
    ADD CONSTRAINT product_version_frequency_loading_sane
        CHECK (monthly_loading_percent   >= 0 AND monthly_loading_percent   <= 100
           AND quarterly_loading_percent >= 0 AND quarterly_loading_percent <= 100);

COMMENT ON COLUMN product.product_version.monthly_loading_percent IS
    'Percent added to the ANNUAL premium before it is divided into monthly instalments. 0 means '
    'a monthly payer is charged the same total as an annual payer.';
```

- [ ] **Step 6: Store it on the version**

In `ProductVersion`, add beside the eligibility bound columns:

```java
    // What this version charges for paying in instalments (V11). Defaulted to ZERO in the field
    // initialiser as well as the column default, so a version built in memory is unloaded rather
    // than null -- the entity is constructed before Hibernate ever sees the DEFAULT.
    @Column(name = "monthly_loading_percent", nullable = false)
    private BigDecimal monthlyLoadingPercent = BigDecimal.ZERO;

    @Column(name = "quarterly_loading_percent", nullable = false)
    private BigDecimal quarterlyLoadingPercent = BigDecimal.ZERO;
```

and beside `applyEligibilityBounds` / `getEligibilityBounds`:

```java
    /**
     * Record what this version charges for instalment payment.
     *
     * <p>Bounds (0-100) are validated by {@link FrequencyLoading} itself, so a caller cannot
     * construct an out-of-range pair to pass here; {@code product_version_frequency_loading_sane}
     * enforces the same at the database.
     */
    public void applyFrequencyLoading(FrequencyLoading loading) {
        this.monthlyLoadingPercent = loading.monthlyPercent();
        this.quarterlyLoadingPercent = loading.quarterlyPercent();
    }

    public FrequencyLoading getFrequencyLoading() {
        return new FrequencyLoading(monthlyLoadingPercent, quarterlyLoadingPercent);
    }
```

Add `import tz.co.nlolo.lifeplatform.product.api.FrequencyLoading;` at the top.

- [ ] **Step 7: Widen the API and plumb the publish path**

In `ProductApi`, change the fullest overload's signature and add the resolver:

```java
    /**
     * Publish a version that states what it will accept and what instalment payment costs.
     *
     * <p>The fullest form; every other overload delegates here. {@code bounds} may be
     * {@link EligibilityBounds#none()} and {@code frequencyLoading} may be
     * {@link FrequencyLoading#none()} — an unbounded, unloaded version is a real product design.
     */
    void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                         List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                         List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading, String publishedBy);

    /**
     * What this version charges for instalment payment.
     *
     * <p>Internal-only, not part of {@code openapi-product.yaml} — the same convention as
     * {@link #isPriced} and {@link #resolveBaseRatePerMille}. Consumed by policy's issuance
     * listener so an issued premium and a quoted one are loaded identically.
     *
     * @throws ProductNotFoundException if no such version exists for this tenant
     */
    FrequencyLoading resolveFrequencyLoading(UUID productVersionId);
```

In `ProductApiImpl`, update the two convenience overloads to pass `FrequencyLoading.none()`, change the fullest overload's signature to match, call `version.applyFrequencyLoading(frequencyLoading != null ? frequencyLoading : FrequencyLoading.none());` immediately after `version.applyEligibilityBounds(...)`, and add:

```java
    @Override
    public FrequencyLoading resolveFrequencyLoading(UUID productVersionId) {
        return productVersionRepository.findById(productVersionId)
            .filter(v -> v.getTenantId().equals(TenantContext.get()))
            .map(ProductVersion::getFrequencyLoading)
            .orElseThrow(() -> new ProductNotFoundException(productVersionId));
    }
```

**Also update the existing nine-argument overload that Batch 1 left as the fullest**: it now delegates to the ten-argument one passing `FrequencyLoading.none()`, so the ~67 existing call sites stay unchanged.

- [ ] **Step 8: Wire the request DTO**

Create `backend/src/main/java/tz/co/nlolo/lifeplatform/product/infrastructure/FrequencyLoadingRequest.java`:

```java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/**
 * Mirrors {@code openapi-product.yaml}'s {@code FrequencyLoading}. Both fields optional: an absent
 * block, and a block of nulls, both mean an unloaded version.
 */
public record FrequencyLoadingRequest(
    @DecimalMin("0") @DecimalMax("100") BigDecimal monthlyPercent,
    @DecimalMin("0") @DecimalMax("100") BigDecimal quarterlyPercent) {}
```

Add to `PublishVersionRequest`, after `eligibility`:

```java
    /**
     * What this version charges for instalment payment. Optional — an unloaded version charges a
     * monthly payer the same total as an annual one, which is a real pricing decision.
     */
    @Valid FrequencyLoadingRequest frequencyLoading) {}
```

In `ProductController.publishVersion`, map it, passing `FrequencyLoading.none()` when the block is absent:

```java
            request.frequencyLoading() != null
                ? new FrequencyLoading(request.frequencyLoading().monthlyPercent(),
                                        request.frequencyLoading().quarterlyPercent())
                : FrequencyLoading.none(),
```

- [ ] **Step 9: Add V11 to `ProductApiIntegrationTest`'s migration list and write the round-trip test**

Add after the V10 line:

```java
            "db-migrations/product/V11__frequency_loading.sql");
```

(changing the V10 line's terminator from `);` to `",`).

Then add the test:

```java
    @Test
    void aPublishedVersionCarriesItsFrequencyLoadingAndDefaultsToUnloaded() {
        ProductSummaryView loaded = productApi.createProduct("TERM-B2A-LOAD", "Loaded",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(loaded.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, List.of(), EligibilityBounds.none(),
            new FrequencyLoading(new BigDecimal("8.00"), new BigDecimal("3.00")), "actuary@nlolo.co.tz");
        UUID loadedVersionId = productApi.getActiveSnapshot(loaded.productId(), LocalDate.now()).productVersionId();

        FrequencyLoading readBack = productApi.resolveFrequencyLoading(loadedVersionId);
        assertThat(readBack.monthlyPercent()).isEqualByComparingTo(new BigDecimal("8.00"));
        assertThat(readBack.quarterlyPercent()).isEqualByComparingTo(new BigDecimal("3.00"));

        // A version published through any older overload is unloaded, which is what keeps every
        // existing product pricing exactly as it does today.
        ProductSummaryView plain = productApi.createProduct("TERM-B2A-PLAIN", "Unloaded",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(plain.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");
        UUID plainVersionId = productApi.getActiveSnapshot(plain.productId(), LocalDate.now()).productVersionId();

        assertThat(productApi.resolveFrequencyLoading(plainVersionId).monthlyPercent())
            .isEqualByComparingTo(BigDecimal.ZERO);
    }
```

- [ ] **Step 10: Run the class**

```
cd backend && ./mvnw test -Dtest=ProductApiIntegrationTest,FrequencyLoadingTest
```

Expected: PASS, 59 + 6 tests.

- [ ] **Step 11: Commit**

```bash
git add backend/db-migrations/product/V11__frequency_loading.sql backend/src/main/java/tz/co/nlolo/lifeplatform/product backend/src/test/java/tz/co/nlolo/lifeplatform/product
git commit -m "feat(product): a version can charge more for paying in instalments" -m "PremiumFrequency divided the annual premium exactly, so a monthly payer and an annual payer were charged the same total -- which no real life insurer does, because the annual payer's premium is investable on day one, twelve collections cost more than one, monthly business lapses part-paid, and commission has already accrued against premium that will not arrive." -m "Stored as a percent per frequency on product_version, not as a rating_table factor type: that table is risk rating and feeds an underwriting decision, and one table meaning two things is what produced the band-string defects. NOT NULL DEFAULT 0, so all 128 existing versions price exactly as before." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: the quote resolves the sum assured by range

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/ProductApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/infrastructure/PremiumQuoteRequest.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/infrastructure/ProductController.java`
- Modify: `backend/api/openapi/openapi-product.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/product/ProductApiIntegrationTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `PremiumQuoteInput(UUID productId, BigDecimal sumAssuredAmount, String sumAssuredCurrency, LocalDate dateOfBirth, Sex sex, SmokerStatus smokerStatus, String occupationClass, PremiumFrequency frequency, LocalDate asOf)` — `sumAssuredBand` **removed**, nine components. Private `Optional<RatingFactor> findSumAssuredBand(UUID, BigDecimal)` in `ProductApiImpl`, shared by `resolveSumAssuredMultiplier` and the quote.

- [ ] **Step 1: Write the failing test**

```java
    /**
     * The quote resolved the sum-assured factor by matching a caller-asserted band STRING --
     * V9's defect, still live on the illustration path after it was removed from issuance. A
     * product author's band '5000000' could never match what a caller typed, so an illustration
     * and the policy it became were priced by two different mechanisms.
     */
    @Test
    void quotePremiumResolvesTheSumAssuredBandByRangeNotByItsLabel() {
        ProductSummaryView product = productApi.createProduct("TERM-B2A-RANGE", "Range-resolved",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now().minusDays(1), null,
            // A band whose LABEL is nothing a caller would type, carrying a real multiplier.
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "5000000",
                        new BigDecimal("1.5000"), null, null,
                        new BigDecimal("0"), new BigDecimal("20000000")),
                    new ProductApi.RatingFactorInput(FactorType.OCCUPATION_CLASS, "CLASS_1", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null,
            List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("10.0000")),
                    new ProductApi.BaseRateInput(18, 25, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("12.0000"))),
            new EligibilityBounds(18, 25, null, null, null, null), "actuary@nlolo.co.tz");

        // 10,000,000 / 1000 * 10.0 = 100,000 annual, x 1.5 sum-assured band = 150,000, / 12.
        ProductApi.PremiumQuoteView quote = productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            product.productId(), new BigDecimal("10000000.00"), "TZS", LocalDate.now().minusYears(20),
            Sex.FEMALE, SmokerStatus.NON_SMOKER, "CLASS_1", PremiumFrequency.MONTHLY, LocalDate.now()));

        assertThat(quote.instalmentAmount()).isEqualByComparingTo(new BigDecimal("12500.00"));
        assertThat(quote.appliedFactors())
            .anySatisfy(f -> {
                assertThat(f.factorType()).isEqualTo(FactorType.SUM_ASSURED_BAND);
                assertThat(f.band()).isEqualTo("5000000");
                assertThat(f.multiplier()).isEqualByComparingTo(new BigDecimal("1.5000"));
            });
    }

    /** An amount no band covers is neutral, matching issuance -- above retention is a soft flag. */
    @Test
    void quotePremiumPricesAnAmountNoBandCoversAtTheNeutralMultiplier() {
        UUID productId = pricedProduct("TERM-B2A-UNCOVERED", new BigDecimal("15.2000"));

        ProductApi.PremiumQuoteView quote = productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            productId, new BigDecimal("10000000.00"), "TZS", LocalDate.now().minusYears(20),
            Sex.FEMALE, SmokerStatus.NON_SMOKER, "CLASS_1", PremiumFrequency.ANNUALLY, LocalDate.now()));

        // pricedProduct's SUM_ASSURED_BAND row carries no amount bounds, so it covers nothing and
        // resolves neutral. 10,000,000 / 1000 * 15.2 = 152,000, unchanged.
        assertThat(quote.instalmentAmount()).isEqualByComparingTo(new BigDecimal("152000.00"));
        assertThat(quote.appliedFactors())
            .noneMatch(f -> f.factorType() == FactorType.SUM_ASSURED_BAND);
    }
```

Also update the `quoteFor` helper — drop the `"LOW"` argument:

```java
    private ProductApi.PremiumQuoteInput quoteFor(UUID productId, LocalDate dateOfBirth, PremiumFrequency frequency) {
        return new ProductApi.PremiumQuoteInput(productId, new BigDecimal("10000000.00"), "TZS",
            dateOfBirth, Sex.FEMALE, SmokerStatus.NON_SMOKER, "CLASS_1", frequency, LocalDate.now());
    }
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && ./mvnw test -Dtest=ProductApiIntegrationTest
```

Expected: compilation failure — `PremiumQuoteInput` still has ten components. That failure is the signal to proceed.

- [ ] **Step 3: Narrow `PremiumQuoteInput`**

In `ProductApi`, remove `String sumAssuredBand,` from the record and replace its javadoc paragraph with:

```java
    /**
     * What a premium is quoted for. Money arrives as amount + currency rather than a Money type,
     * matching {@code PolicyApi}'s convention at this layer.
     *
     * Takes {@code dateOfBirth}, never a precomputed age: entry age is the input a mispriced
     * policy turns on, and it is derived where the rate table lives.
     *
     * <p><b>There is deliberately no {@code sumAssuredBand}.</b> It used to be asserted by the
     * caller and matched by string equality against {@code rating_table.band} — V9's defect,
     * removed from the issuance path and left here because this endpoint had no frontend caller.
     * A band string a caller invents cannot be validated against anything, and
     * {@code sumAssuredAmount} determines the band by definition, so asking for both only invited
     * them to disagree.
     *
     * <p>{@code occupationClass} and {@code smokerStatus} are still ASSERTED: no party record is
     * read on this path.
     */
    record PremiumQuoteInput(UUID productId, BigDecimal sumAssuredAmount, String sumAssuredCurrency,
                             LocalDate dateOfBirth, Sex sex, SmokerStatus smokerStatus,
                             String occupationClass,
                             PremiumFrequency frequency, LocalDate asOf) {}
```

- [ ] **Step 4: Share one range resolution between both callers**

In `ProductApiImpl`, extract the lookup that `resolveSumAssuredMultiplier` performs so the quote uses the identical rule rather than a second copy — two implementations drifting is the defect this task removes:

```java
    /**
     * The SUM_ASSURED_BAND row covering this amount, if any.
     *
     * <p>Shared by {@link #resolveSumAssuredMultiplier} and {@code quotePremium} so the underwriting
     * path and the illustration path cannot resolve the same amount differently. They did: the quote
     * matched a caller-asserted band string while issuance matched by range.
     *
     * <p>Returns a list rather than taking {@code findFirst()} because publish-time validation
     * refuses overlapping bands but a version published before that rule could still hold them, and
     * silently taking one of two is the scan-order mispricing this platform has found five times.
     */
    private Optional<RatingFactor> findSumAssuredBand(UUID productVersionId, BigDecimal sumAssuredAmount) {
        if (sumAssuredAmount == null) {
            return Optional.empty();
        }
        List<RatingFactor> covering = ratingFactorRepository
            .findByProductVersionIdAndFactorType(productVersionId, FactorType.SUM_ASSURED_BAND.name())
            .stream()
            .filter(f -> f.getSumAssuredFrom() != null && f.getSumAssuredTo() != null)
            .filter(f -> f.getSumAssuredFrom().compareTo(sumAssuredAmount) <= 0
                && f.getSumAssuredTo().compareTo(sumAssuredAmount) >= 0)
            .toList();
        if (covering.size() > 1) {
            throw new InvalidProductVersionException("Product version " + productVersionId
                + " has " + covering.size() + " SUM_ASSURED_BAND rows covering "
                + sumAssuredAmount.toPlainString()
                + " -- which multiplier applies would depend on row order");
        }
        return covering.stream().findFirst();
    }
```

and rewrite `resolveSumAssuredMultiplier`'s body to:

```java
        return findSumAssuredBand(productVersionId, sumAssuredAmount)
            .map(RatingFactor::getMultiplier)
            .orElse(BigDecimal.ONE);
```

- [ ] **Step 5: Use it in `quotePremium`**

Replace the `strictMultiplier(... SUM_ASSURED_BAND ...)` line with:

```java
        // By RANGE, and the same call the issuance path makes. A neutral 1.0 when no band covers
        // the amount is correct here and matches issuance: a sum assured above every band is
        // already treated platform-wide as a SOFT flag, because above-retention business is what
        // the reinsurance treaties exist to absorb. Nothing is recorded in the breakdown when no
        // band applied, because no factor did.
        BigDecimal sumAssuredMultiplier = findSumAssuredBand(versionId, input.sumAssuredAmount())
            .map(row -> {
                applied.add(new AppliedFactor(FactorType.SUM_ASSURED_BAND, row.getBand(), row.getMultiplier()));
                return row.getMultiplier();
            })
            .orElse(BigDecimal.ONE);
        annual = annual.multiply(sumAssuredMultiplier);
```

`strictMultiplier` stays, still used for `OCCUPATION_CLASS`. Add to its javadoc:

```java
     * <p>Only OCCUPATION_CLASS now. The sum-assured factor resolves by range instead, because the
     * amount determines the band; an occupation class is a code the same organisation chose on both
     * sides of the match, so a caller's typo should fail loudly rather than price at 1.0.
```

- [ ] **Step 6: Drop it from the wire**

In `PremiumQuoteRequest` remove `@NotNull String sumAssuredBand,`. In `ProductController.quotePremium` remove `request.sumAssuredBand(),` from the constructor call.

In `openapi-product.yaml`, remove `sumAssuredBand` from `PremiumQuoteRequest`'s `required` list (line 387) and from its `properties` (line 395), and extend the schema description:

```yaml
        There is deliberately no sumAssuredBand: it was asserted by the caller and
        matched by string equality against rating_table.band, which is the defect
        V9 removed from the issuance path. sumAssuredAmount determines the band.
```

- [ ] **Step 7: Run the class**

```
cd backend && ./mvnw test -Dtest=ProductApiIntegrationTest
```

Expected: PASS. `quotePremiumRefusesRatherThanFallingBackOnAnyMissingDimension` will fail on its "sum-assured band with no multiplier" assertion, which asserted a 422 for a band string that no longer exists as an input. **Delete that one assertion block** and leave a comment in its place:

```java
        // The "sum-assured band with no multiplier" case is gone: the band is no longer an input.
        // An amount no band covers now resolves neutral, which is what issuance does and what
        // quotePremiumPricesAnAmountNoBandCoversAtTheNeutralMultiplier asserts.
```

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/product backend/api/openapi/openapi-product.yaml backend/src/test/java/tz/co/nlolo/lifeplatform/product/ProductApiIntegrationTest.java
git commit -m "fix(product): the quote resolves the sum assured by range, not by a band label" -m "quotePremium matched a caller-asserted band string against rating_table.band -- V9's defect, removed from issuance and left live on the illustration path because this endpoint has no frontend caller. So an illustration and the policy it became were priced by two different mechanisms, and the illustration used the broken one." -m "The field is removed rather than corrected: a band string a caller invents cannot be validated against anything, and the amount determines the band. Both paths now share one findSumAssuredBand, so they cannot drift again." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: the quote applies the loading and shows it

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/ProductApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java`
- Modify: `backend/api/openapi/openapi-product.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/product/ProductApiIntegrationTest.java`

**Interfaces:**
- Consumes: `FrequencyLoading.applyTo` and `ProductVersion.getFrequencyLoading()` from Task 1; the nine-component `PremiumQuoteInput` from Task 2.
- Produces: `PremiumQuoteView` gains `BigDecimal frequencyLoadingPercent` and `BigDecimal annualAfterFrequencyLoading`, both immediately after `annualAfterFactors`.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void quotePremiumAppliesTheVersionsFrequencyLoadingAndShowsIt() {
        ProductSummaryView product = productApi.createProduct("TERM-B2A-QLOAD", "Loaded quote",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now().minusDays(1), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.OCCUPATION_CLASS, "CLASS_1", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null,
            List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("1.2000")),
                    new ProductApi.BaseRateInput(18, 25, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("1.4000"))),
            new EligibilityBounds(18, 25, null, null, null, null),
            new FrequencyLoading(new BigDecimal("8"), new BigDecimal("3")), "actuary@nlolo.co.tz");

        // 10,000,000 / 1000 * 1.2 = 12,000 annual. Monthly: x 1.08 = 12,960, / 12 = 1,080.00.
        ProductApi.PremiumQuoteView monthly =
            productApi.quotePremium(quoteFor(product.productId(), LocalDate.now().minusYears(20), PremiumFrequency.MONTHLY));
        assertThat(monthly.annualAfterFactors()).isEqualByComparingTo(new BigDecimal("12000.00"));
        assertThat(monthly.frequencyLoadingPercent()).isEqualByComparingTo(new BigDecimal("8"));
        assertThat(monthly.annualAfterFrequencyLoading()).isEqualByComparingTo(new BigDecimal("12960.00"));
        assertThat(monthly.instalmentAmount()).isEqualByComparingTo(new BigDecimal("1080.00"));

        // Annual is never loaded, and pays less over the year than the monthly payer -- which is
        // the entire point of the change.
        ProductApi.PremiumQuoteView annually =
            productApi.quotePremium(quoteFor(product.productId(), LocalDate.now().minusYears(20), PremiumFrequency.ANNUALLY));
        assertThat(annually.frequencyLoadingPercent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(annually.instalmentAmount()).isEqualByComparingTo(new BigDecimal("12000.00"));
    }

    @Test
    void anUnloadedVersionQuotesExactlyWhatItDidBefore() {
        UUID productId = pricedProduct("TERM-B2A-NOLOAD", new BigDecimal("15.2000"));
        ProductApi.PremiumQuoteView quote =
            productApi.quotePremium(quoteFor(productId, LocalDate.now().minusYears(20), PremiumFrequency.MONTHLY));

        // 10,000,000 / 1000 * 15.2 = 152,000 / 12 = 12,666.67 -- the figure this product has
        // always quoted. The migration defaults to zero precisely so this cannot move.
        assertThat(quote.frequencyLoadingPercent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(quote.annualAfterFrequencyLoading()).isEqualByComparingTo(new BigDecimal("152000.00"));
        assertThat(quote.instalmentAmount()).isEqualByComparingTo(new BigDecimal("12666.67"));
    }
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && ./mvnw test -Dtest=ProductApiIntegrationTest
```

Expected: compilation failure — `frequencyLoadingPercent()` does not exist on `PremiumQuoteView`.

- [ ] **Step 3: Widen the view**

In `ProductApi`, add the two components after `annualAfterFactors` and extend the javadoc:

```java
    record PremiumQuoteView(UUID productVersionId, String currency,
                            int ageAtEntry, int ageFrom, int ageTo, BigDecimal ratePerMille,
                            BigDecimal annualBase, List<AppliedFactor> appliedFactors,
                            BigDecimal annualAfterFactors,
                            /**
                             * What paying at this frequency costs, and the annual figure the
                             * instalment is actually divided from. Both present even when zero:
                             * without them the view reports an annual premium that does not divide
                             * into the instalment beside it, which is worse than showing nothing.
                             */
                            BigDecimal frequencyLoadingPercent, BigDecimal annualAfterFrequencyLoading,
                            PremiumFrequency frequency, int instalmentsPerYear,
                            BigDecimal instalmentAmount) {}
```

- [ ] **Step 4: Apply it in `quotePremium`**

Replace the instalment calculation and the return with:

```java
        // The payment term, applied after all the risk arithmetic. Multiplication commutes, so its
        // position does not change the number -- it changes whether a reader can follow the
        // breakdown, which is the whole reason this view returns one.
        FrequencyLoading loading = version.getFrequencyLoading();
        BigDecimal loadedAnnual = loading.applyTo(annual, input.frequency());

        int instalments = input.frequency().instalmentsPerYear();
        BigDecimal instalment = loadedAnnual
            .divide(BigDecimal.valueOf(instalments), 2, java.math.RoundingMode.HALF_UP);

        return new PremiumQuoteView(versionId, input.sumAssuredCurrency(),
            ageAtEntry, cell.getAgeFrom(), cell.getAgeTo(), cell.getRatePerMille(),
            annualBase.setScale(2, java.math.RoundingMode.HALF_UP), List.copyOf(applied),
            annual.setScale(2, java.math.RoundingMode.HALF_UP),
            loading.percentFor(input.frequency()),
            loadedAnnual.setScale(2, java.math.RoundingMode.HALF_UP),
            input.frequency(), instalments, instalment);
```

Update the numbered order-of-operations javadoc above `quotePremium`: step 6 becomes "Apply the version's frequency loading to the annual premium", step 7 "Divide by the frequency's instalments per year", step 8 "Round ONCE, at the end".

- [ ] **Step 5: Carry the loading on the rating read too**

`VersionRatingView` is what an actuary reviews a version's rating basis on, and what instalment
payment costs is part of that basis. Without it the console has nothing to display.

In `ProductApi`, add one component:

```java
    record VersionRatingView(UUID productId, UUID productVersionId, LocalDate effectiveDate,
                             List<BaseRateInput> baseRates, List<RatingFactorInput> ratingFactors,
                             List<BenefitInput> benefitSchedule,
                             /** What paying in instalments costs. {@link FrequencyLoading#none()} when nothing is loaded. */
                             FrequencyLoading frequencyLoading) {}
```

and in `ProductApiImpl.getVersionRating`, pass `version.getFrequencyLoading()` as the last argument.

- [ ] **Step 6: Extend the contract**

In `openapi-product.yaml`'s `PremiumQuoteView` properties, after `annualAfterFactors`:

```yaml
        frequencyLoadingPercent: { type: number }
        annualAfterFrequencyLoading: { type: number }
```

and in `VersionRatingView`'s properties:

```yaml
        frequencyLoading:
          type: object
          properties:
            monthlyPercent: { type: number }
            quarterlyPercent: { type: number }
```

Neither schema has a `required` list, so both are additive.

- [ ] **Step 7: Run the class**

```
cd backend && ./mvnw test -Dtest=ProductApiIntegrationTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/product backend/api/openapi/openapi-product.yaml backend/src/test/java/tz/co/nlolo/lifeplatform/product/ProductApiIntegrationTest.java
git commit -m "feat(product): a quote applies the version's frequency loading and shows it" -m "The breakdown carries the loading percent and the annual figure the instalment is divided from, because a view that reported an annual premium not dividing into its own instalment would be worse than one showing nothing. An unloaded version quotes exactly what it always did." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: issuance applies the same loading, and the two paths are proven equal

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/UnderwritingDecisionEventListener.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyApiIntegrationTest.java`

**Interfaces:**
- Consumes: `ProductApi.resolveFrequencyLoading(UUID)` and `FrequencyLoading.applyTo` from Task 1.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test — the one this batch exists for**

Add to `PolicyApiIntegrationTest`, and add V11 to its migration list after the V10 line:

```java
    /**
     * ONE PRICE FOR ONE LIFE. The same product, the same life, the same frequency: the illustration
     * a customer is shown and the premium they are actually billed must be the same number.
     *
     * <p>They were not. The quote resolved the sum-assured factor by matching a band string while
     * issuance matched by range, and neither applied a frequency loading at all.
     */
    @Test
    void aQuoteAndTheIssuedPolicyChargeTheSameInstalment() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        ProductSummaryView product = productApi.createProduct("POLICY-AGREE-01", "One price",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "0-20m",
                        new BigDecimal("1.2000"), null, null,
                        new BigDecimal("0"), new BigDecimal("20000000"))),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null,
            List.of(new ProductApi.BaseRateInput(18, 78, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("12.0000")),
                    new ProductApi.BaseRateInput(18, 78, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("12.0000"))),
            new EligibilityBounds(18, 78, null, null, null, null),
            new FrequencyLoading(new BigDecimal("8"), new BigDecimal("3")), "actuary");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        // The illustration.
        ProductApi.PremiumQuoteView quote = productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            product.productId(), new BigDecimal("1000000"), "TZS",
            LocalDate.now().minusYears(40).minusDays(1),
            Sex.MALE, SmokerStatus.NON_SMOKER, "CLASS_1", PremiumFrequency.MONTHLY, LocalDate.now()));

        // The contract, for the same life, through the real decision-to-issuance path. The
        // applicant has no OCCUPATION_CLASS recorded, which resolves neutral there and is asserted
        // as CLASS_1 above -- the product carries no occupation multiplier, so both are 1.0.
        PolicyView issued = issueFromProposal(tenantId,
            new Fixture(pricedLife(tenantId, 40, "6001"), product.productId(), versionId),
            new ProposalDetails(null, null, null, null, null, null, "MONTHLY", List.of()));

        assertThat(issued.premiumAmount())
            .as("the illustration and the first invoice must be one number")
            .isEqualByComparingTo(quote.instalmentAmount());
        // 1,000,000 / 1000 * 12.0 = 12,000, x 1.2 band = 14,400, x 1.08 monthly = 15,552, / 12.
        assertThat(issued.premiumAmount()).isEqualByComparingTo(new BigDecimal("1296.00"));
    }
```

- [ ] **Step 2: Run to verify it fails**

```
cd backend && ./mvnw test -Dtest='PolicyApiIntegrationTest#aQuoteAndTheIssuedPolicyChargeTheSameInstalment'
```

Expected: FAIL — the issued premium is 1,200.00 (unloaded) against the quote's 1,296.00.

- [ ] **Step 3: Apply the loading at issuance**

In `UnderwritingDecisionEventListener`, after `annualPremium` is computed and before the instalment division, insert:

```java
                // The payment term, from the product, applied exactly as quotePremium applies it --
                // through FrequencyLoading.applyTo, which is the one place this arithmetic lives.
                // An illustration and the first invoice for the same life must be one number, and
                // they were not: neither path loaded, and they resolved the sum-assured factor by
                // two different mechanisms.
                FrequencyLoading frequencyLoading =
                    productApi.resolveFrequencyLoading(decidedCase.productVersionId());
                String premiumFrequency = decidedCase.premiumFrequency() != null
                    ? decidedCase.premiumFrequency() : "MONTHLY";
                BigDecimal loadedAnnualPremium = frequencyLoading.applyTo(
                    annualPremium, PremiumFrequency.valueOf(premiumFrequency));
```

then change the instalment line to divide `loadedAnnualPremium`, and **delete the now-duplicated** `String premiumFrequency = ...` declaration further down. Add the import for `tz.co.nlolo.lifeplatform.product.api.FrequencyLoading` and `tz.co.nlolo.lifeplatform.product.api.PremiumFrequency`.

Note `instalmentsPerYear(premiumFrequency)` already exists in this class and stays as-is — it and `PremiumFrequency.instalmentsPerYear()` agree, and unifying them is not this task's job.

- [ ] **Step 4: Run the test, then the class**

```
cd backend && ./mvnw test -Dtest=PolicyApiIntegrationTest
```

Expected: PASS, 48 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/UnderwritingDecisionEventListener.java backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyApiIntegrationTest.java
git commit -m "feat(policy): an issued premium carries the product's frequency loading" -m "Issuance divides by the frequency exactly, so a monthly payer was billed the annual premium in twelve parts. It now applies the version's loading through FrequencyLoading.applyTo, the same call quotePremium makes." -m "The new test is the one this batch exists for: the same product, the same life, the same frequency, and the illustration must equal the first invoice. It would have caught the sum-assured divergence." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: the console authors and shows the loading

**Files:**
- Modify: `frontend/src/features/products/publishVersionSchema.ts`
- Modify: `frontend/src/features/products/PublishVersionForm.tsx`
- Modify: `frontend/src/features/products/RatingBasis.tsx`
- Modify: `frontend/src/types/api/product.ts` (regenerated)
- Test: `frontend/src/features/products/publishVersionSchema.test.ts`

**Interfaces:**
- Consumes: the `frequencyLoading` request block from Task 1 and the OpenAPI changes from Tasks 2–3.
- Produces: `monthlyLoadingPercent` / `quarterlyLoadingPercent` on `PublishVersionFormInput`, emitted by `toApiRequest` as a nested `frequencyLoading` object, omitted entirely when both are blank or zero.

- [ ] **Step 1: Regenerate the API types**

```
cd frontend && npm run generate:api
```

This picks up the removed `sumAssuredBand` and the two new view properties. Review the diff before continuing — a `default:` property becoming REQUIRED in a generated request type is a known quirk of `openapi-typescript` on this project.

- [ ] **Step 2: Write the failing schema tests**

```ts
    it('sends no frequencyLoading block when both loadings are blank', () => {
      const result = termLife.safeParse(pricedValid());
      expect(result.success).toBe(true);
      expect(toApiRequest(result.data!)).not.toHaveProperty('frequencyLoading');
    });

    it('sends the loadings an actuary typed', () => {
      const result = termLife.safeParse(
        pricedValid({ monthlyLoadingPercent: '8', quarterlyLoadingPercent: '3' }),
      );
      expect(result.success).toBe(true);
      expect(toApiRequest(result.data!).frequencyLoading).toEqual({
        monthlyPercent: 8,
        quarterlyPercent: 3,
      });
    });

    it('refuses a loading outside 0 to 100', () => {
      expect(termLife.safeParse(pricedValid({ monthlyLoadingPercent: '-1' })).success).toBe(false);
      expect(termLife.safeParse(pricedValid({ monthlyLoadingPercent: '101' })).success).toBe(false);
    });
```

- [ ] **Step 3: Run to verify they fail**

```
cd frontend && npx vitest run src/features/products/publishVersionSchema.test.ts
```

Expected: the first passes trivially, the other two FAIL.

- [ ] **Step 4: Add the fields to the schema**

Beside the eligibility bounds in `publishVersionFormSchema`, using the same string-not-number handling the file already explains:

```ts
    // Kept as strings so blank stays blank rather than coercing to 0 -- which for a loading is a
    // real value ("charge the same whatever the frequency"), not an absence.
    monthlyLoadingPercent: percentZeroToHundred('Monthly loading'),
    quarterlyLoadingPercent: percentZeroToHundred('Quarterly loading'),
```

with this helper beside `optionalAmount`:

```ts
/** An optional percentage, 0-100, kept as a string. Mirrors product_version_frequency_loading_sane. */
const percentZeroToHundred = (label: string) =>
  z
    .string()
    .trim()
    .refine((v) => v === '' || /^\d+(\.\d{1,2})?$/.test(v), `${label} must be a percentage`)
    .refine((v) => v === '' || Number(v) >= 0, `${label} cannot be negative`)
    .refine((v) => v === '' || Number(v) <= 100, `${label} cannot exceed 100`);
```

Add both keys to `blankPublishVersionForm()` as `''`, and in `toApiRequest`:

```ts
    // Omitted entirely when nothing is loaded, rather than sent as a block of zeroes. An absent
    // block and a block of zeroes mean the same thing to the backend; the absent one says
    // "this version does not load instalment payment" without asking a reader to check two fields.
    ...((values.monthlyLoadingPercent || values.quarterlyLoadingPercent) && {
      frequencyLoading: {
        ...(values.monthlyLoadingPercent && { monthlyPercent: Number(values.monthlyLoadingPercent) }),
        ...(values.quarterlyLoadingPercent && { quarterlyPercent: Number(values.quarterlyLoadingPercent) }),
      },
    }),
```

- [ ] **Step 5: Add the form fields**

In `PublishVersionForm.tsx`, beside the entry-age fields:

```tsx
          <FormField label="Monthly loading %" error={errors.monthlyLoadingPercent?.message}>
            <input
              type="text"
              inputMode="decimal"
              className={inputClass}
              {...register('monthlyLoadingPercent')}
            />
          </FormField>
          <FormField label="Quarterly loading %" error={errors.quarterlyLoadingPercent?.message}>
            <input
              type="text"
              inputMode="decimal"
              className={inputClass}
              {...register('quarterlyLoadingPercent')}
            />
          </FormField>
```

Match the surrounding `FormField`/`inputClass` usage exactly rather than inventing markup — read the entry-age block immediately above and copy its shape.

Add both keys to the `defaultValues` object as `''`.

- [ ] **Step 6: Show it on the detail page**

In `RatingBasis.tsx`, render a line only when at least one loading is non-zero:

```tsx
      {(Number(rating.frequencyLoading?.monthlyPercent ?? 0) > 0 ||
        Number(rating.frequencyLoading?.quarterlyPercent ?? 0) > 0) && (
        <p className="text-sm text-slate-600">
          Paying in instalments costs more: monthly +{rating.frequencyLoading?.monthlyPercent ?? 0}%,
          quarterly +{rating.frequencyLoading?.quarterlyPercent ?? 0}%.
        </p>
      )}
```

`VersionRatingView` carries `frequencyLoading` as of Task 3 Step 5, so the data is already on the
wire. Read the surrounding markup in this component and match its classes rather than inventing
new ones — and note the shape is `rating.frequencyLoading.monthlyPercent`, not a flat field.

- [ ] **Step 7: Run the frontend gates**

```
cd frontend && npx vitest run src/features/products src/store/productStore.test.ts ; echo "--- TYPECHECK ---" ; npm run typecheck ; echo "--- LINT ---" ; npm run lint
```

Expected: all green.

- [ ] **Step 8: Commit**

```bash
git add frontend/src
git commit -m "feat(console): an actuary can author what instalment payment costs" -m "Two percentage fields beside the eligibility bounds, kept as strings so blank stays blank rather than coercing to zero -- which for a loading is a real decision, not an absence. The block is omitted entirely when nothing is loaded." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: every test class loads V11, and the full suite proves it

**Files:**
- Modify: the 44 remaining backend test classes listing the product migrations (46 total, minus `ProductApiIntegrationTest` from Task 1 and `PolicyApiIntegrationTest` from Task 4)
- Modify: `frontend/e2e/staff-products.spec.ts`

- [ ] **Step 1: Confirm the list**

```
cd backend && grep -rl "db-migrations/product/V10__ifrs_measurement_model_on_version.sql" src/test --include="*.java" | wc -l
```

Expected: 46.

- [ ] **Step 2: Add V11 everywhere it is missing**

The columns are `NOT NULL` and Hibernate includes them in every insert and select, and `ddl-auto` is `none` — so a class missing V11 fails at its first product-version query with `column "monthly_loading_percent" does not exist`, not at boot.

Insert after each V10 line, preserving each line's terminator (`,` mid-list, `);` when V10 is last):

```java
            "db-migrations/product/V11__frequency_loading.sql",
```

Verify:

```
cd backend && grep -rl "V11__frequency_loading.sql" src/test --include="*.java" | wc -l
```

Expected: 46.

- [ ] **Step 3: Stop the dev backend**

Find the process listening on 8080 and stop it. `clean` under a live JVM produces ~20 failures across untouched modules.

- [ ] **Step 4: Clean compile**

```
cd backend && ./mvnw clean test-compile
```

Expected: BUILD SUCCESS. `PremiumQuoteInput` lost a component and `PremiumQuoteView` gained two, so this is the step that surfaces any call site Maven's incremental compile would have skipped.

- [ ] **Step 5: Full backend suite**

```
cd backend && ./mvnw test
```

Expected: BUILD SUCCESS. Baseline is 1141; this branch adds roughly 13.

Nothing else may run during this — no vitest, no Playwright.

- [ ] **Step 6: Apply V11 to the dev database and restart**

```
cd backend && docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 < db-migrations/product/V11__frequency_loading.sql
```

Then restart the backend and wait for port 8080 to answer.

- [ ] **Step 7: Add the fields to the e2e publish journey and run it**

In `staff-products.spec.ts`'s priced-publish test, after the entry-age fields:

```ts
    await page.getByLabel('Monthly loading %').fill('8');
    await page.getByLabel('Quarterly loading %').fill('3');
```

Then, with backend and frontend both up and nothing else running:

```
cd frontend && npx playwright test e2e/staff-products.spec.ts e2e/staff-issue-policy.spec.ts e2e/staff-underwriting.spec.ts
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/test frontend/e2e/staff-products.spec.ts
git commit -m "test(product): every integration test class loads V11" -m "The loading columns are NOT NULL and Hibernate includes them in every insert and select, while ddl-auto is none -- so a class missing the migration fails at its first product-version query rather than at boot. All 46 classes that load the product schema list it." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Done when

- [ ] Full backend suite green after `clean test-compile` then `clean test`, dev backend stopped.
- [ ] Frontend product scope green, plus `npm run typecheck` and `npm run lint`.
- [ ] The three e2e specs green against the real stack with V11 applied to the dev database.
- [ ] A quote and an issued policy for the same life, product and frequency produce the same instalment — asserted, not assumed.
- [ ] No existing product's premium moved: the unloaded-version tests in Tasks 3 and 4 assert the pre-change figures.

## Deliberately not done

- **Batch 2b** — benefits driving coverage creation, which first needs `calculation_method` to become a real vocabulary. Across 128 versions that column holds three rows carrying `SUM_ASSURED` and the typed prose `untill death`.
- **The `occupationClass` divergence.** The quote refuses a class it cannot resolve; issuance prices it neutrally. That is "won't guess" against "won't reject a decided case" — two answers to two questions, unlike the sum-assured bug where both paths produced a price and the prices disagreed.
- **No policy fee**, by decision. It stays out of the calculation rather than becoming a constant in code, which closes an M13 open item.
