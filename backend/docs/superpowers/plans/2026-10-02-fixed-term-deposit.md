# Fixed-term deposit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **This project executes inline, with no subagents** (user preference).

**Goal:** The user's fixed-term deposit works end to end. One deposit of 500,000 or more, for 3, 6 or 12 months, earns a rate for the term taken from a band grid on the product version. Nothing moves during the term. An early exit pays the deposit plus pro-rata interest. At maturity the deposit is reinvested or paid out.

**Architecture:** A deposit version is an ACCOUNT version (step 3) that also carries a deposit rate grid (`product.deposit_rate_row`). The server builds the version's account plan with zero charges, so every existing account seam keeps working: lapse exemption, surrender event, death valuation and the cash-value projection. Accumulation owns everything else:
- `deposit_period` records each term's rate and dates; once written, they never change;
- `maturity_instruction` is the client's append-only choice;
- a daily drain matures periods, then reinvests or pays out.

The payer's number reaches accumulation through an additive `payerRef` on `payment.PaymentConfirmed` and `billing.PremiumCollected`.

**Tech Stack:** Spring Boot 3, Spring Modulith, JPA/Hibernate (ddl-auto none), Postgres 16 with RLS, Testcontainers + WireMock, React + react-hook-form + zod + zustand, Vitest, Playwright.

**Spec:** `backend/docs/superpowers/specs/2026-10-02-fixed-term-deposit-design.md` (commit `1b99daeb`). The revisions in §R override it where they differ.

## Global Constraints

- The ledger rules from step 3 are the user's and are unchanged:
  - accumulation owns the balance and every transaction;
  - ledger entries are immutable (corrections are REVERSAL or ADJUSTMENT only);
  - one source reference creates at most one posting.
- The rate is the **total for the term**: 1,000,000 at 3% for 3 months pays exactly 30,000.00.
- Interest = principal × rate ÷ 100 × days held ÷ days in the term, where the term's days are `ChronoUnit.DAYS` from start to maturity. Rounded once, HALF_EVEN, 2 dp.
- User's grid (minimum 500,000), rates for 3 / 6 / 12 months:
  - from 500,000: 3% / 4% / 5%
  - from 6,000,000: 4% / 5% / 6%
  - from 11,000,000: 5% / 6% / 7%
  - from 21,000,000: 6% / 7% / 8%
- D6, reinvestment: the whole balance (deposit + interest). D7: the instruction can be given any time before maturity; with none, the money is paid out. D8: the client may pick a new term from those offered by the version **active for new business** at maturity.
- The refusal message for any movement: `This is a fixed-term deposit: nothing can be added or taken out until it matures on {date}`.
- ddl-auto is none. Any test class whose code path reaches a new table needs that migration in its hand-written list.
- Interface methods are never `default`, so `@Transactional` always applies. Every new `publishVersion` overload carries `@Transactional`.
- Listeners use explicit, module-prefixed bean names.
- Never run Prettier. Edit files with the Edit/Write tools, not shell strings. CRLF files make node string-replace fail.
- Tests per task, running only that task's classes: `./mvnw -B -o test -Dtest=A+B` from `backend/`. The full suite and the e2e run only at the gate (Task 9). Stop the dev backend before any `clean`.
- Run `./mvnw -B -o clean test-compile` after any record or signature change. Maven's incremental compile hides breaks in tests it does not recompile.

---

## §R — Revisions against the spec (agreed design, adjusted to the code)

| # | Spec said | This plan does | Why |
|---|---|---|---|
| R1 | A third value basis, `DEPOSIT` | An ACCOUNT version plus a deposit grid. The server builds the account plan: guarantee 0, minimum 0, one zero-charge row. | Every `isAccount()` seam works unchanged: arrears never lapse the policy, surrender publishes `AccountSurrenderApproved`, death is valued from the account, and the `PolicyAccount` projection is restated. A third enum value would need each seam found and widened. |
| R2 | `min_amount`, `max_amount` per row | Only `min_amount`. A band runs from its start up to the next band's start, excluded. The top band has no end. | D9 says bands are contiguous, so a gap or overlap cannot be stored at all. This also covers 5,999,999.50, which an inclusive `max` of 5,999,999 would have left between bands. |
| R3 | product V21 | product **V20**, the next free number | The bonuses plan's product V20 is renumbered to V21 when it is built. This plan adds no policy migration. |
| R4 | `account.default_payee_ref` | `deposit_period.default_payee_ref`, carried into each reinvested period | A new column on `account` would break every test class that reads accounts without the new migration. |
| R5 | Account "AWAITING_PAYEE" | Derived, not stored: account OPEN, no RUNNING period, balance > 0 | `account.status` keeps its two values and its CHECK. |
| R6 | Early-exit interest under `deposit-interest:<periodId>` | Posted inside the closing posting (`surrender:` / `claim:`), as step 3's `close()` already does. The period is ended in the same transaction, so a period earns interest once. `deposit-interest:<periodId>` is used at maturity. | One posting per closing (step 3's rule). Splitting would mean two postings for one event. |
| R7 | Not covered | The maturity payment's source ref is `deposit-maturity:<periodId>:<attempt>`. A failed payment is reversed, and the account reopens awaiting a payee (`Account.reopenAwaitingPayee`). | A failed payment must not lose the money, and a second attempt needs its own reference. |
| R8 | Not covered | The expiry sweep's Java guard skips deposit versions. `benefitpayout.hasScheduledMaturity` answers true for a deposit, so claims refuses a hand-filed maturity claim. | Otherwise a deposit past its date is EXPIRED, or claimed twice. |
| R9 | "issued as a single premium" | `issuePolicy` refuses a deposit unless: frequency SINGLE, premium = sum assured, term offered, and a rate exists. Automatic issuance sets the premium to the sum assured. | The rule is held in one place, not left to whoever keys the form. |
| R10 | Not covered | `makePaidUp` is refused on a deposit | A deposit has no premiums left to stop. |
| R11 | Validator refuses "also with-profits" | Deferred to the bonuses plan, which must add it | No with-profits flag exists yet. |
| R12 | "A list" of AWAITING_PAYEE accounts | A section on the existing payouts register (`PayoutsQueuePage`), linking to each policy. Payment is recorded on the policy's Account tab. | No new route or nav ("don't invent anything"). |
| R13 | Payout purpose `DEPOSIT_MATURITY_PAYOUT` | Kept (23 characters, fits VARCHAR(30)) | |
| R14 | Spec table `product.deposit_rate_row` | Kept, with the shape of R2 | |
| R16 (found in Task 2) | `restateMaturityDate` | `PolicyApi.restateDepositTerm(policy, commencement, termMonths)` returns the derived maturity date. Term 1: commencement = the day the money arrived, months = its term. A reinvestment: commencement unchanged, months += the new term, and the new period's maturity IS the policy's derived date. `DepositPeriod` takes its maturity explicitly. | `policy_maturity_matches_term` (V6) holds maturity = commencement + term months, and refused a bare date move. Moving commencement at each reinvestment would make earlier terms look off cover. |
| R15 | Validator refuses any payout schedule row | Kept. `PayoutPlanValidator` also stops requiring the endowment's MATURITY row for a deposit; free-look days are still required. | Otherwise every deposit endowment is refused. |

---

## File map

**Product**
- Create `db-migrations/product/V20__deposit_rate_grid.sql`
- Create `product/api/DepositRateRow.java`, `product/api/DepositPlan.java`
- Create `product/domain/DepositRate.java`, `product/domain/DepositPlanValidator.java`
- Create `product/infrastructure/DepositRateRepository.java`, `product/infrastructure/DepositRequest.java`
- Modify `product/api/AccumulationPlan.java` (add `forDeposit()`), `product/api/ProductApi.java`, `product/application/ProductApiImpl.java`, `product/domain/PayoutPlanValidator.java`, `product/infrastructure/PublishVersionRequest.java`, `product/infrastructure/ProductController.java`, `api/openapi/openapi-product.yaml`
- Test: create `product/DepositPlanTest.java` and `product/DepositPlanValidatorTest.java`; modify `product/PayoutPlanValidatorTest.java` and `product/ProductApiIntegrationTest.java`; add V20 to all 71 migration lists.

**Policy**
- Modify `policy/api/PolicyApi.java`, `policy/domain/Policy.java`, `policy/application/PolicyApiImpl.java`, `policy/application/UnderwritingDecisionEventListener.java`
- Test: create `accumulation/DepositIssuanceIntegrationTest.java`; modify `accumulation/AccumulationTestFixtures.java`

**Payment and billing**
- Create `db-migrations/payment/V10__deposit_maturity_purpose.sql`
- Modify `payment/application/PaymentApiImpl.java`, `billing/api/BillingApi.java`, `billing/application/BillingApiImpl.java`, `billing/application/PaymentEventListener.java`

**Accumulation**
- Create `db-migrations/accumulation/V3__deposit_periods.sql`
- Create:
  - `accumulation/api/` — `DepositPeriodStatus`, `MaturityAction`, `DepositPeriodView`, `MaturityInstructionView`, `DepositView`, `AwaitingPayeeView`
  - `accumulation/domain/` — `DepositPeriod`, `MaturityInstruction`
  - `accumulation/infrastructure/` — `DepositPeriodRepository`, `MaturityInstructionRepository`, `DepositResponse`, `DepositBodies`
  - `accumulation/application/` — `DepositInterest`, `Deposits`, `DepositMaturityDrain`
- Modify `accumulation/api/AccumulationApi.java`, `accumulation/domain/Account.java`, and in `accumulation/application/`: `AccumulationApiImpl.java`, `AccountValuer.java`, `BillingEventListener.java`, `PaymentEventListener.java`
- Modify `accumulation/infrastructure/AccumulationController.java`, `api/openapi/openapi-accumulation.yaml`, `api/asyncapi-events.yaml`, `src/main/resources/application-local.yml`
- Modify `benefitpayout/application/BenefitPayoutApiImpl.java`
- Test: create `accumulation/DepositInterestTest.java` and `accumulation/DepositLifecycleIntegrationTest.java`; modify `accumulation/AccumulationContractTest.java` and `accumulation/LedgerImmutabilityTest.java`

**Console**
- Modify `frontend/src/types/api/*` (regenerated), `frontend/src/api/types.ts`, `frontend/src/api/accumulation.ts`, `frontend/src/store/accumulationStore.ts`
- Modify `frontend/src/features/products/publishVersionSchema.ts`, `frontend/src/features/products/PublishVersionForm.tsx`
- Create `frontend/src/features/accounts/DepositSection.tsx`, `frontend/src/features/accounts/depositForms.ts`
- Modify `frontend/src/features/accounts/AccountPanel.tsx`, `frontend/src/features/payouts/PayoutsQueuePage.tsx`
- Test: modify `publishVersionSchema.test.ts` and `AccountPanel.test.tsx`; create `depositForms.test.ts`
- e2e: create `frontend/e2e/staff-fixed-term-deposit.spec.ts`

---

### Task 0: Branch

- [ ] **Step 1:** From `backend/`'s repo root:

```bash
git checkout main && git status --short && git checkout -b fixed-term-deposit
```

Expected: a clean tree, now on `fixed-term-deposit`.

---

### Task 1: Product — the deposit rate grid

**Files:** the Product block in the file map.

**Interfaces:**
- Produces:
  - `DepositRateRow(BigDecimal minAmount, int termMonths, BigDecimal ratePercent)`
  - `DepositPlan(List<DepositRateRow> rows)`, with:
    - `none()` and `isDeposit()`
    - `List<Integer> terms()` and `List<BigDecimal> bandStarts()`
    - `Optional<BigDecimal> rateFor(BigDecimal amount, int termMonths)`
  - `AccumulationPlan.forDeposit()`
  - `ProductApi.resolveDepositPlan(UUID productVersionId)`
  - the eighth `publishVersion` overload, taking a trailing `DepositPlan depositPlan` before `publishedBy`
  - the `deposit` block on the publish request

- [ ] **Step 1: Write the failing unit tests**

`backend/src/test/java/tz/co/nlolo/lifeplatform/product/DepositPlanTest.java`:

```java
package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.DepositPlan;
import tz.co.nlolo.lifeplatform.product.api.DepositRateRow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The user's grid, and every band boundary the spec names (§7). */
class DepositPlanTest {

    static DepositPlan userGrid() {
        String[][] bands = {{"500000", "3", "4", "5"}, {"6000000", "4", "5", "6"},
                            {"11000000", "5", "6", "7"}, {"21000000", "6", "7", "8"}};
        int[] terms = {3, 6, 12};
        List<DepositRateRow> rows = new ArrayList<>();
        for (String[] band : bands) {
            for (int t = 0; t < terms.length; t++) {
                rows.add(new DepositRateRow(new BigDecimal(band[0]), terms[t], new BigDecimal(band[t + 1])));
            }
        }
        return new DepositPlan(rows);
    }

    private static String rate(String amount, int term) {
        return userGrid().rateFor(new BigDecimal(amount), term).map(BigDecimal::toPlainString).orElse("none");
    }

    @Test
    void everyBoundaryLandsInItsBand() {
        assertThat(rate("499999.99", 3)).isEqualTo("none");
        assertThat(rate("500000", 3)).isEqualTo("3");
        assertThat(rate("5999999", 3)).isEqualTo("3");
        assertThat(rate("5999999.50", 3)).isEqualTo("3");
        assertThat(rate("6000000", 3)).isEqualTo("4");
        assertThat(rate("20999999", 12)).isEqualTo("7");
        assertThat(rate("21000000", 6)).isEqualTo("7");
        assertThat(rate("900000000", 12)).isEqualTo("8");
    }

    @Test
    void aTermTheGridDoesNotOfferHasNoRate() {
        assertThat(rate("1000000", 9)).isEqualTo("none");
    }

    @Test
    void termsAndBandStartsAreSortedAndDistinct() {
        assertThat(userGrid().terms()).containsExactly(3, 6, 12);
        assertThat(userGrid().bandStarts()).extracting(BigDecimal::toPlainString)
            .containsExactly("500000", "6000000", "11000000", "21000000");
    }

    @Test
    void noneIsNotADeposit() {
        assertThat(DepositPlan.none().isDeposit()).isFalse();
        assertThat(userGrid().isDeposit()).isTrue();
    }
}
```

`backend/src/test/java/tz/co/nlolo/lifeplatform/product/DepositPlanValidatorTest.java`:

```java
package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.DepositPlanValidator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DepositPlanValidatorTest {

    private static final PayoutPlan NO_ROWS = PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of());

    private static void check(ProductCategory category, DepositPlan plan) {
        DepositPlanValidator.validate(category, plan, AccumulationPlan.none(), CashValuePlan.none(),
            FrequencyLoading.none(), NO_ROWS, EligibilityBounds.none());
    }

    private static DepositPlan with(DepositRateRow... extra) {
        List<DepositRateRow> rows = new ArrayList<>(DepositPlanTest.userGrid().rows());
        rows.addAll(List.of(extra));
        return new DepositPlan(rows);
    }

    @Test
    void theUsersGridIsAccepted() {
        assertThatCode(() -> check(ProductCategory.ENDOWMENT, DepositPlanTest.userGrid())).doesNotThrowAnyException();
    }

    @Test
    void noneIsNotChecked() {
        assertThatCode(() -> check(ProductCategory.TERM_LIFE, DepositPlan.none())).doesNotThrowAnyException();
    }

    @Test
    void aProtectionCategoryCannotBeADeposit() {
        assertThatThrownBy(() -> check(ProductCategory.TERM_LIFE, DepositPlanTest.userGrid()))
            .hasMessage("A TERM_LIFE product cannot be a fixed-term deposit");
    }

    @Test
    void aSavingsAccountBlockAlongsideIsRefused() {
        AccumulationPlan account = new AccumulationPlan(ValueBasis.ACCOUNT, BigDecimal.ONE, BigDecimal.ZERO,
            List.of(new AccumulationChargeRow(1, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
        assertThatThrownBy(() -> DepositPlanValidator.validate(ProductCategory.ENDOWMENT, DepositPlanTest.userGrid(),
                account, CashValuePlan.none(), FrequencyLoading.none(), NO_ROWS, EligibilityBounds.none()))
            .hasMessage("A fixed-term deposit sets its own account terms; send no savings-account block with it");
    }

    @Test
    void aFrequencyLoadingIsRefused() {
        assertThatThrownBy(() -> DepositPlanValidator.validate(ProductCategory.ENDOWMENT, DepositPlanTest.userGrid(),
                AccumulationPlan.none(), CashValuePlan.none(), new FrequencyLoading(new BigDecimal("5"), BigDecimal.ZERO),
                NO_ROWS, EligibilityBounds.none()))
            .hasMessage("A fixed-term deposit is paid once; it takes no frequency loading");
    }

    @Test
    void aPayoutRowIsRefused() {
        PayoutPlan withRow = PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of(new PayoutRowInput(
            PayoutKind.MATURITY, null, null, PayoutAmountBasis.ACCOUNT_VALUE, new BigDecimal("100"), null)));
        assertThatThrownBy(() -> DepositPlanValidator.validate(ProductCategory.ENDOWMENT, DepositPlanTest.userGrid(),
                AccumulationPlan.none(), CashValuePlan.none(), FrequencyLoading.none(), withRow, EligibilityBounds.none()))
            .hasMessage("A fixed-term deposit matures through its account; it carries no payout schedule");
    }

    @Test
    void aDuplicateCellIsRefused() {
        assertThatThrownBy(() -> check(ProductCategory.ENDOWMENT,
                with(new DepositRateRow(new BigDecimal("500000"), 3, new BigDecimal("9")))))
            .hasMessage("The deposit rate grid has two rates for deposits from 500000 over 3 months");
    }

    @Test
    void aBandMissingATermIsRefused() {
        assertThatThrownBy(() -> check(ProductCategory.ENDOWMENT,
                with(new DepositRateRow(new BigDecimal("50000000"), 3, new BigDecimal("9")))))
            .hasMessage("The band from 50000000 does not offer a 6-month term");
    }

    @Test
    void aRateOutsideZeroToHundredIsRefused() {
        assertThatThrownBy(() -> check(ProductCategory.ENDOWMENT, new DepositPlan(List.of(
                new DepositRateRow(new BigDecimal("500000"), 3, new BigDecimal("101"))))))
            .hasMessage("A deposit rate must be between 0 and 100 percent");
    }

    @Test
    void aTermOutsideOneToHundredTwentyMonthsIsRefused() {
        assertThatThrownBy(() -> check(ProductCategory.ENDOWMENT, new DepositPlan(List.of(
                new DepositRateRow(new BigDecimal("500000"), 0, new BigDecimal("3"))))))
            .hasMessage("A deposit term must be between 1 and 120 months");
    }

    @Test
    void theLowestBandMustStartAtTheMinimumSumAssured() {
        assertThatThrownBy(() -> DepositPlanValidator.validate(ProductCategory.ENDOWMENT, DepositPlanTest.userGrid(),
                AccumulationPlan.none(), CashValuePlan.none(), FrequencyLoading.none(), NO_ROWS,
                new EligibilityBounds(null, null, null, null, new BigDecimal("1000000"), null)))
            .hasMessage("The lowest deposit band must start at the version's minimum sum assured (1000000)");
    }
}
```

Add to `backend/src/test/java/tz/co/nlolo/lifeplatform/product/PayoutPlanValidatorTest.java`, inside the class. Add imports for `AccumulationPlan` and `List` if the file does not already have them:

```java
    @Test
    void aDepositEndowmentCarriesNoScheduleButStillNeedsAFreeLookPeriod() {
        PayoutPlan noRows = PayoutPlan.authored(new PayoutTerms(15, null, null, null), java.util.List.of());
        org.assertj.core.api.Assertions.assertThatCode(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
            noRows, AccumulationPlan.forDeposit(), true)).doesNotThrowAnyException();
        PayoutPlan noFreeLook = PayoutPlan.authored(new PayoutTerms(null, null, null, null), java.util.List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
            noFreeLook, AccumulationPlan.forDeposit(), true))
            .hasMessage("A free-look period in days is required on an individual product");
    }
```

- [ ] **Step 2: Run them and see them fail to compile**

Run: `./mvnw -B -o test -Dtest=DepositPlanTest+DepositPlanValidatorTest+PayoutPlanValidatorTest`
Expected: COMPILATION ERROR, because `DepositPlan`, `DepositRateRow`, `DepositPlanValidator` and `AccumulationPlan.forDeposit` do not exist.

- [ ] **Step 3: The api records**

`backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/DepositRateRow.java`:

```java
package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/**
 * One cell of a fixed-term deposit's grid: deposits from {@code minAmount} (up to the next band's
 * start) over {@code termMonths} earn {@code ratePercent} FOR THE TERM -- not a yearly rate.
 */
public record DepositRateRow(BigDecimal minAmount, int termMonths, BigDecimal ratePercent) {}
```

`backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/DepositPlan.java`:

```java
package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A fixed-term deposit's rate grid. {@link #none()} for every other version.
 *
 * <p>A band is named by its start only and runs up to the next band's start, which it excludes.
 * The last band has no end. Bands so defined cannot leave a gap or overlap, which is the spec's
 * D9, and a deposit of 5,999,999.50 still finds its band.
 */
public record DepositPlan(List<DepositRateRow> rows) {

    public DepositPlan {
        rows = rows != null ? List.copyOf(rows) : List.of();
    }

    public static DepositPlan none() { return new DepositPlan(List.of()); }

    public boolean isDeposit() { return !rows.isEmpty(); }

    public List<Integer> terms() {
        return rows.stream().map(DepositRateRow::termMonths).distinct().sorted().toList();
    }

    /** Distinct by value, not scale: 500000 and 500000.00 are one band (a TreeSet compares, it does not equals). */
    public List<BigDecimal> bandStarts() {
        return List.copyOf(rows.stream().map(DepositRateRow::minAmount)
            .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)));
    }

    /** The band is the highest start at or below the amount; empty below the lowest band or for an unoffered term. */
    public Optional<BigDecimal> rateFor(BigDecimal amount, int termMonths) {
        Optional<BigDecimal> band = rows.stream().map(DepositRateRow::minAmount)
            .filter(start -> start.compareTo(amount) <= 0).max(Comparator.naturalOrder());
        return band.flatMap(start -> rows.stream()
            .filter(r -> r.minAmount().compareTo(start) == 0 && r.termMonths() == termMonths)
            .map(DepositRateRow::ratePercent).findFirst());
    }
}
```

In `product/api/AccumulationPlan.java`, add after `none()`:

```java
    /**
     * The account terms behind a fixed-term deposit. The deposit's rate comes from its grid, not
     * from here, so this is an account that charges nothing and guarantees nothing.
     */
    public static AccumulationPlan forDeposit() {
        return new AccumulationPlan(ValueBasis.ACCOUNT, BigDecimal.ZERO, BigDecimal.ZERO, List.of(
            new AccumulationChargeRow(1, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
    }
```

- [ ] **Step 4: The validator**

`backend/src/main/java/tz/co/nlolo/lifeplatform/product/domain/DepositPlanValidator.java`:

```java
package tz.co.nlolo.lifeplatform.product.domain;

import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything a CHECK cannot say about a fixed-term deposit (spec §3.1). Pure and static, like
 * {@link AccumulationPlanValidator}, and run BEFORE it: a deposit's account plan is built by the
 * server, so the author is told the deposit rule rather than an account rule broken as a result.
 */
public final class DepositPlanValidator {

    private static final Set<ProductCategory> DEPOSIT_CATEGORIES =
        EnumSet.of(ProductCategory.ENDOWMENT, ProductCategory.WHOLE_LIFE, ProductCategory.EDUCATION_SAVINGS);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private DepositPlanValidator() {}

    public static void validate(ProductCategory category, DepositPlan plan, AccumulationPlan accumulation,
                                CashValuePlan cashValue, FrequencyLoading loading, PayoutPlan payout,
                                EligibilityBounds bounds) {
        if (plan == null || !plan.isDeposit()) {
            return;
        }
        if (!DEPOSIT_CATEGORIES.contains(category)) {
            fail("A " + category + " product cannot be a fixed-term deposit");
        }
        if (accumulation != null && accumulation.isAccount()) {
            fail("A fixed-term deposit sets its own account terms; send no savings-account block with it");
        }
        if (cashValue != null && cashValue.isPresent()) {
            fail("A version is valued either by a cash-value scale or as a fixed-term deposit, not both");
        }
        if (loading != null && (positive(loading.monthlyPercent()) || positive(loading.quarterlyPercent()))) {
            fail("A fixed-term deposit is paid once; it takes no frequency loading");
        }
        if (payout != null && !payout.rows().isEmpty()) {
            fail("A fixed-term deposit matures through its account; it carries no payout schedule");
        }
        Set<String> cells = new HashSet<>();
        for (DepositRateRow row : plan.rows()) {
            if (row.minAmount() == null || row.minAmount().signum() <= 0) {
                fail("A deposit band must start above zero");
            }
            if (row.termMonths() < 1 || row.termMonths() > 120) {
                fail("A deposit term must be between 1 and 120 months");
            }
            if (row.ratePercent() == null || row.ratePercent().signum() < 0 || row.ratePercent().compareTo(HUNDRED) > 0) {
                fail("A deposit rate must be between 0 and 100 percent");
            }
            if (!cells.add(row.minAmount().stripTrailingZeros().toPlainString() + "/" + row.termMonths())) {
                fail("The deposit rate grid has two rates for deposits from " + row.minAmount().stripTrailingZeros().toPlainString()
                    + " over " + row.termMonths() + " months");
            }
        }
        List<Integer> terms = plan.terms();
        for (BigDecimal start : plan.bandStarts()) {
            for (int term : terms) {
                if (plan.rows().stream().noneMatch(r -> r.minAmount().compareTo(start) == 0 && r.termMonths() == term)) {
                    fail("The band from " + start.stripTrailingZeros().toPlainString() + " does not offer a " + term + "-month term");
                }
            }
        }
        BigDecimal minimum = bounds != null ? bounds.minSumAssured() : null;
        if (minimum != null && plan.bandStarts().get(0).compareTo(minimum) != 0) {
            fail("The lowest deposit band must start at the version's minimum sum assured ("
                + minimum.stripTrailingZeros().toPlainString() + ")");
        }
    }

    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }

    private static void fail(String message) { throw new InvalidProductVersionException(message); }
}
```

- [ ] **Step 5: The payout validator's deposit exception**

In `product/domain/PayoutPlanValidator.java`, replace the three-argument `validate` method head and its rows-empty branch:

```java
    public static void validate(ProductCategory category, PayoutPlan plan, AccumulationPlan accumulation) {
        validate(category, plan, accumulation, false);
    }

    /**
     * {@code deposit}: a fixed-term deposit matures through its account (accumulation's drain), so
     * an endowment deposit carries no MATURITY row. Free-look and term bounds still apply.
     */
    public static void validate(ProductCategory category, PayoutPlan plan, AccumulationPlan accumulation, boolean deposit) {
        if (plan == null || !plan.authored()) {
            return;
        }
        checkAccountRules(plan, accumulation);
        PayoutTerms terms = plan.terms();
        if (INDIVIDUAL.contains(category) && terms.freeLookDays() == null) {
            fail("A free-look period in days is required on an individual product");
        }
        if (terms.freeLookDays() != null && (terms.freeLookDays() < 1 || terms.freeLookDays() > 365)) {
            fail("A free-look period must be between 1 and 365 days");
        }
        // A product with no rows is a perfectly ordinary term or whole-life version; only the two
        // SCHEDULED categories must carry one, and they are checked below -- unless it is a deposit.
        if (plan.rows().isEmpty() && (!SCHEDULED.contains(category) || deposit)) {
            checkTermBounds(terms, plan);
            return;
        }
```

The rest of the method body (from `checkCategory(category, plan);`) is unchanged.

- [ ] **Step 6: Run the unit tests**

Run: `./mvnw -B -o test -Dtest=DepositPlanTest+DepositPlanValidatorTest+PayoutPlanValidatorTest`
Expected: PASS.

- [ ] **Step 7: The migration**

`backend/db-migrations/product/V20__deposit_rate_grid.sql`:

```sql
-- db-migrations/product/V20__deposit_rate_grid.sql
-- A fixed-term deposit's rates: deposit band x term -> rate FOR THE TERM (the user's savings plan,
-- spec 2026-10-02). Its presence makes the version a deposit; the version is also ACCOUNT-basis
-- (V19), with an account plan the server builds and that charges nothing.
--
-- A SEPARATE TABLE for V19's reason: ddl-auto is none, and a column on product_version that a test
-- database lacks would break every class that reads a version.
--
-- A band is named by its START only. It runs to the next band's start, so the grid can hold no gap
-- and no overlap -- the spec's D9, made a property of the shape instead of a rule to check.
CREATE TABLE product.deposit_rate_row (
    deposit_rate_row_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    product_version_id  UUID NOT NULL REFERENCES product.product_version(product_version_id),
    min_amount          NUMERIC(19,2) NOT NULL CHECK (min_amount > 0),
    term_months         INTEGER NOT NULL CHECK (term_months BETWEEN 1 AND 120),
    rate_percent        NUMERIC(7,4) NOT NULL CHECK (rate_percent BETWEEN 0 AND 100)
);
CREATE UNIQUE INDEX ux_deposit_rate_row_cell ON product.deposit_rate_row (product_version_id, min_amount, term_months);

ALTER TABLE product.deposit_rate_row ENABLE ROW LEVEL SECURITY;
CREATE POLICY deposit_rate_row_tenant_isolation ON product.deposit_rate_row
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON product.deposit_rate_row TO app_role;
```

- [ ] **Step 8: Entity and repository**

`backend/src/main/java/tz/co/nlolo/lifeplatform/product/domain/DepositRate.java`:

```java
package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.product.api.DepositRateRow;

import java.math.BigDecimal;
import java.util.UUID;

/** One cell of a deposit version's grid (V20). */
@Entity
@Table(name = "deposit_rate_row", schema = "product")
public class DepositRate {
    @Id @UuidGenerator @Column(name = "deposit_rate_row_id") private UUID depositRateRowId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "min_amount", nullable = false) private BigDecimal minAmount;
    @Column(name = "term_months", nullable = false) private int termMonths;
    @Column(name = "rate_percent", nullable = false) private BigDecimal ratePercent;

    protected DepositRate() {}

    public DepositRate(UUID tenantId, UUID productVersionId, DepositRateRow row) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.minAmount = row.minAmount();
        this.termMonths = row.termMonths();
        this.ratePercent = row.ratePercent();
    }

    public DepositRateRow toRow() { return new DepositRateRow(minAmount, termMonths, ratePercent); }
}
```

`backend/src/main/java/tz/co/nlolo/lifeplatform/product/infrastructure/DepositRateRepository.java`:

```java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.DepositRate;

import java.util.List;
import java.util.UUID;

public interface DepositRateRepository extends JpaRepository<DepositRate, UUID> {
    List<DepositRate> findByProductVersionIdOrderByMinAmountAscTermMonthsAsc(UUID productVersionId);
}
```

- [ ] **Step 9: ProductApi and its implementation**

In `product/api/ProductApi.java`, after the step-3 fullest `publishVersion`, add:

```java
    /**
     * The fullest form (fixed-term deposit): also the deposit's rate grid. Every other overload
     * delegates here with {@link DepositPlan#none()}. A deposit's account plan is built by the
     * server ({@link AccumulationPlan#forDeposit()}); passing one alongside is refused.
     */
    void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                         List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                         List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                         TiraFiling tiraFiling, CashValuePlan cashValue, PayoutPlan payoutPlan,
                         AccumulationPlan accumulationPlan, DepositPlan depositPlan, String publishedBy);

    /** A version's deposit grid; {@link DepositPlan#none()} for any other version. Internal-only. */
    DepositPlan resolveDepositPlan(UUID productVersionId);
```

In `product/application/ProductApiImpl.java`:

1. Add the field `private final DepositRateRepository depositRateRepository;`, add it as the constructor's LAST parameter, and assign it. Then run `grep -rn "new ProductApiImpl(" src` and expect no result (verified when this plan was written).
2. Turn the step-3 fullest overload into a delegate, and make its old body the new overload. The signature line `AccumulationPlan accumulationPlan, String publishedBy) {` becomes the delegate:

```java
                                AccumulationPlan accumulationPlan, String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, baseRates, bounds, frequencyLoading, tiraFiling, cashValue, payoutPlan, accumulationPlan,
            DepositPlan.none(), publishedBy);
    }

    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                                List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                                TiraFiling tiraFiling, CashValuePlan cashValue, PayoutPlan payoutPlan,
                                AccumulationPlan accumulationPlan, DepositPlan depositPlan, String publishedBy) {
```

Everything that was in the old body follows unchanged, except the three validator lines and the persist line:

```java
        ProductCategory category = ProductCategory.valueOf(product.getCategory());
        CashValuePlanValidator.validate(category, cashValue);
        DepositPlan deposit = depositPlan != null ? depositPlan : DepositPlan.none();
        DepositPlanValidator.validate(category, deposit, accumulationPlan, cashValue, frequencyLoading, payoutPlan, bounds);
        // A deposit's account plan is the server's: an account that charges and guarantees nothing,
        // so every ACCOUNT seam (lapse exemption, surrender event, death valuation) applies to it.
        AccumulationPlan effectiveAccumulation = deposit.isDeposit() ? AccumulationPlan.forDeposit() : accumulationPlan;
        AccumulationPlanValidator.validate(category, effectiveAccumulation, cashValue);
        PayoutPlanValidator.validate(category, payoutPlan, effectiveAccumulation, deposit.isDeposit());
```

```java
        persistAccumulationPlan(tenantId, version.getProductVersionId(), effectiveAccumulation);
        persistDepositPlan(tenantId, version.getProductVersionId(), deposit);
```

3. Next to `persistAccumulationPlan` and `resolveAccumulationPlan`, add:

```java
    /** A deposit version's grid (V20). Nothing for any other version. */
    private void persistDepositPlan(UUID tenantId, UUID productVersionId, DepositPlan plan) {
        for (DepositRateRow row : plan.rows()) {
            depositRateRepository.save(new DepositRate(tenantId, productVersionId, row));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public DepositPlan resolveDepositPlan(UUID productVersionId) {
        return new DepositPlan(depositRateRepository.findByProductVersionIdOrderByMinAmountAscTermMonthsAsc(productVersionId)
            .stream().map(DepositRate::toRow).toList());
    }
```

Imports: `DepositPlan` and `DepositRateRow` (product.api), `DepositRate` and `DepositPlanValidator` (product.domain), `DepositRateRepository`.

- [ ] **Step 10: The request block**

`backend/src/main/java/tz/co/nlolo/lifeplatform/product/infrastructure/DepositRequest.java`:

```java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import tz.co.nlolo.lifeplatform.product.api.DepositPlan;
import tz.co.nlolo.lifeplatform.product.api.DepositRateRow;

import java.math.BigDecimal;
import java.util.List;

/** A fixed-term deposit's grid on the wire. Absent means the version is not a deposit. */
public record DepositRequest(@NotEmpty @Valid List<Rate> rates) {

    public record Rate(@NotNull BigDecimal minAmount, @NotNull Integer termMonths, @NotNull BigDecimal ratePercent) {}

    public DepositPlan toPlan() {
        return new DepositPlan(rates.stream().map(r -> new DepositRateRow(r.minAmount(), r.termMonths(), r.ratePercent())).toList());
    }
}
```

In `PublishVersionRequest.java`, after `@Valid AccumulationRequest accumulation`:

```java
    @Valid AccumulationRequest accumulation,

    // Present only on a fixed-term deposit. The server builds the account plan behind it, so a
    // request carrying both blocks is refused.
    @Valid DepositRequest deposit) {}
```

(Nothing constructs `PublishVersionRequest` in tests; checked when this plan was written.)

In `ProductController.java`, the publish call's last two arguments become:

```java
            request.accumulation() != null ? request.accumulation().toPlan() : AccumulationPlan.none(),
            request.deposit() != null ? request.deposit().toPlan() : DepositPlan.none(),
            jwt.getSubject());
```

with `import tz.co.nlolo.lifeplatform.product.api.DepositPlan;`.

In `api/openapi/openapi-product.yaml`, add a `deposit` property next to `accumulation` in the publish request schema (`ProductVersionSpec`), plus a component schema:

```yaml
        deposit:
          description: >-
            Present only on a fixed-term deposit (2026-10-02). Rates by deposit band and term, each the
            total for the term. A band runs from its start up to the next band's start. The server
            builds a zero-charge account plan behind it, so this is refused alongside `accumulation`.
          $ref: '#/components/schemas/DepositGrid'
```

```yaml
    DepositGrid:
      type: object
      required: [rates]
      properties:
        rates:
          type: array
          minItems: 1
          items:
            type: object
            required: [minAmount, termMonths, ratePercent]
            properties:
              minAmount: { type: number, exclusiveMinimum: 0 }
              termMonths: { type: integer, minimum: 1, maximum: 120 }
              ratePercent: { type: number, minimum: 0, maximum: 100 }
```

Match the indentation of `accumulation:` (line ~409) and put `DepositGrid` beside the schema `accumulation` refers to.

- [ ] **Step 11: Add V20 to every migration list that has V19**

A scratchpad script, not a repo file. It only matches patterns: no prose passes through a shell string.

```js
// add-v20.mjs -- run from backend/: node <scratchpad>/add-v20.mjs
import { readFileSync, writeFileSync } from 'node:fs';
import { execSync } from 'node:child_process';
const files = execSync('git grep -l "product/V19__accumulation_terms.sql" -- src/test', { encoding: 'utf8' }).trim().split('\n');
let n = 0;
for (const f of files) {
  const text = readFileSync(f, 'utf8');
  if (text.includes('product/V20__deposit_rate_grid.sql')) continue;
  const next = text.replace(/^(\s*)"db-migrations\/product\/V19__accumulation_terms\.sql",(\r?\n)/m,
    (m, indent, eol) => `${m}${indent}"db-migrations/product/V20__deposit_rate_grid.sql",${eol}`);
  if (next === text) { console.log('NO MATCH (last in list?)', f); continue; }
  writeFileSync(f, next); n++;
}
console.log('updated', n, 'of', files.length);
```

Run it. Expected: `updated 71 of 71`. For any `NO MATCH` file, V19 is the list's last entry (followed by `);`). Fix it by hand with the Edit tool: add `,` after V19, then the V20 line.

- [ ] **Step 12: Round-trip test against Postgres**

In `ProductApiIntegrationTest.java`:
- change both `7`s in `noPublishVersionOverloadIsADefaultMethodAndEveryImplementationIsTransactional` to `8`, and add "and the deposit grid the eighth" to its comment;
- add this test:

```java
    @Test
    void aDepositVersionRoundTripsItsGridAndGetsAZeroChargeAccount() {
        ProductSummaryView product = productApi.createProduct("FTD-1", "Fixed deposit",
            ProductCategory.ENDOWMENT, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            payoutRatingTable(), payoutDeathOnly(), null, List.of(), EligibilityBounds.none(), FrequencyLoading.none(),
            ANY_FILING, CashValuePlan.none(), PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of()),
            AccumulationPlan.none(), DepositPlanTest.userGrid(), "actuary@nlolo.co.tz");

        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();
        DepositPlan read = productApi.resolveDepositPlan(versionId);
        assertThat(read.rows()).hasSize(12);
        assertThat(read.rateFor(new BigDecimal("6000000"), 6)).hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("5"));
        AccumulationPlan account = productApi.resolveAccumulationPlan(versionId);
        assertThat(account.isAccount()).isTrue();
        assertThat(account.chargesFor(1).monthlyPolicyFee()).isEqualByComparingTo("0");
        // And a version that is not a deposit says so.
        assertThat(productApi.resolveDepositPlan(UUID.randomUUID()).isDeposit()).isFalse();
    }
```

Imports, if missing: `tz.co.nlolo.lifeplatform.product.api.DepositPlan` and `AccumulationPlan`. `DepositPlanTest` is in the same package.

- [ ] **Step 13: Compile everything and run the task's tests**

Run: `./mvnw -B -o clean test-compile` (dev backend stopped), then
`./mvnw -B -o test -Dtest=DepositPlanTest+DepositPlanValidatorTest+PayoutPlanValidatorTest+ProductApiIntegrationTest+AccumulationPlanValidatorTest+ProductContractTest`
Expected: BUILD SUCCESS. `ProductContractTest` proves the YAML still loads.

- [ ] **Step 14: Commit**

```bash
git add db-migrations/product/V20__deposit_rate_grid.sql src/main/java/tz/co/nlolo/lifeplatform/product api/openapi/openapi-product.yaml src/test
git commit -m "feat(product): a fixed-term deposit's rate grid -- bands by start, a rate for the term, a zero-charge account behind it"
```

---

### Task 2: Policy — issuing a deposit, and its maturity date

**Files:** the Policy block in the file map.

**Interfaces:**
- Consumes: `ProductApi.resolveDepositPlan`, `DepositPlan.terms()`, `rateFor`, `bandStarts`.
- Produces:
  - `PolicyApi.restateMaturityDate(String policyNumber, LocalDate maturityDate)`
  - `AccumulationTestFixtures.USER_GRID`
  - `AccumulationTestFixtures.issueDeposit(UUID tenant, BigDecimal amount, int termMonths, LocalDate commencement)` returning `Issued`
  - `AccumulationTestFixtures.publishDepositVersion(UUID tenant, UUID productId, DepositPlan plan)` returning the new version id
  - `AccumulationTestFixtures.collectDeposit(UUID tenant, String policyNumber, UUID invoiceId, BigDecimal amount, LocalDate on, String payerRef)`

- [ ] **Step 1: Fixtures**

In `accumulation/AccumulationTestFixtures.java`, add:

```java
    /** The user's grid (spec §1), rates for the term. */
    public static final DepositPlan USER_GRID = grid(new String[][] {
        {"500000", "3", "4", "5"}, {"6000000", "4", "5", "6"}, {"11000000", "5", "6", "7"}, {"21000000", "6", "7", "8"}});

    public static DepositPlan grid(String[][] bands) {
        int[] terms = {3, 6, 12};
        java.util.List<DepositRateRow> rows = new java.util.ArrayList<>();
        for (String[] band : bands) {
            for (int t = 0; t < terms.length; t++) {
                rows.add(new DepositRateRow(new BigDecimal(band[0]), terms[t], new BigDecimal(band[t + 1])));
            }
        }
        return new DepositPlan(rows);
    }

    /** A fixed-term deposit product on the user's grid, and an in-force deposit on it. */
    public Issued issueDeposit(UUID tenant, BigDecimal amount, int termMonths, LocalDate commencement) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenant);
        try {
            int n = SEQ.incrementAndGet();
            PartyView applicant = partyApi.registerIndividual("Deposit Test Life " + n, LocalDate.of(1985, 1, 1),
                "+25571600" + String.format("%04d", n % 10000), null, "test-agent");
            ProductSummaryView product = productApi.createProduct(
                "FTD-" + n + "-" + tenant.toString().substring(0, 4), "Fixed deposit", ProductCategory.ENDOWMENT, "TZS", "actuary");
            UUID versionId = publishDepositVersion(tenant, product.productId(), USER_GRID);
            PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(),
                versionId, amount, "TZS", amount, "TZS", "SINGLE", null,
                List.of(), "deposit test", commencement, termMonths, null, null, null);
            String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
            policyApi.activateOnFirstPremium(policyNumber);
            return new Issued(policyNumber, product.productId(), versionId);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }

    /** Publishes (and so makes active for new business) a deposit version on an existing product. */
    public UUID publishDepositVersion(UUID tenant, UUID productId, DepositPlan plan) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenant);
        try {
            productApi.publishVersion(productId, IfrsMeasurementModel.GMM, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-49", BigDecimal.ONE, 30, 49),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, List.of(), EligibilityBounds.none(), FrequencyLoading.none(), ANY_FILING, CashValuePlan.none(),
                PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of()), AccumulationPlan.none(), plan, "actuary");
            return productApi.getActiveSnapshot(productId, LocalDate.now()).productVersionId();
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }

    /** billing.PremiumCollected for the deposit, with the number it was collected from (null for cash). */
    public void collectDeposit(UUID tenant, String policyNumber, UUID invoiceId, BigDecimal amount, LocalDate on, String payerRef) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("invoiceId", invoiceId);
        payload.put("policyNumber", policyNumber);
        payload.put("policyholderPartyId", UUID.randomUUID());
        payload.put("amount", Map.of("amount", amount.toPlainString(), "currencyCode", "TZS"));
        payload.put("collectedAt", on.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString());
        payload.put("paidToDate", on.toString());
        if (payerRef != null) payload.put("payerRef", payerRef);
        publish(tenant, "billing.PremiumCollected", payload);
    }
```

`collectedAt` is midnight UTC, which is 03:00 EAT on the same civil day, so `on` is the civil date accumulation reads.

- [ ] **Step 2: The failing test**

`backend/src/test/java/tz/co/nlolo/lifeplatform/accumulation/DepositIssuanceIntegrationTest.java`:
- Copy `TopUpAndTransferIntegrationTest`'s class annotations, `@Container`, `@DynamicPropertySource` and the whole migration list (lines 71-142).
- Add `"db-migrations/product/V20__deposit_rate_grid.sql"` after V19 if Task 1's script did not already.
- Then:

```java
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AccumulationTestFixtures.class)
class DepositIssuanceIntegrationTest {

    // @Container, @DynamicPropertySource and @BeforeAll applyMigrations exactly as in
    // TopUpAndTransferIntegrationTest, with product V20 in the list.

    private static final UUID TENANT = UUID.randomUUID();
    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private PolicyApi policyApi;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    private PolicyApi.IssueRequest request(AccumulationTestFixtures.Issued on, String sa, String premium, String freq, Integer term) {
        var policy = asTenant(() -> policyApi.getPolicy(on.policyNumber()));
        return new PolicyApi.IssueRequest(policy.policyholderPartyId(), on.productId(), on.productVersionId(),
            new BigDecimal(sa), "TZS", new BigDecimal(premium), "TZS", freq, null, List.of(), "deposit test",
            LocalDate.now(), term, null, null, null);
    }

    private void refused(PolicyApi.IssueRequest r, String message) {
        assertThatThrownBy(() -> asTenant(() -> policyApi.issuePolicy(UUID.randomUUID(), r, "test-staff")))
            .hasMessageContaining(message);
    }

    @Test
    void aDepositIsIssuedAsOnePaymentOfTheDepositForAnOfferedTerm() {
        var issued = fixtures.issueDeposit(TENANT, new BigDecimal("1000000.00"), 3, LocalDate.now());
        var policy = asTenant(() -> policyApi.getPolicy(issued.policyNumber()));
        assertThat(policy.premiumFrequency()).isEqualTo("SINGLE");
        assertThat(policy.maturityDate()).isEqualTo(LocalDate.now().plusMonths(3));

        refused(request(issued, "1000000.00", "1000000.00", "MONTHLY", 3), "must be SINGLE");
        refused(request(issued, "1000000.00", "50000.00", "SINGLE", 3), "must equal the sum assured");
        refused(request(issued, "1000000.00", "1000000.00", "SINGLE", 9), "offers terms of [3, 6, 12] months");
        refused(request(issued, "400000.00", "400000.00", "SINGLE", 3), "below the smallest band this product offers (500000)");
    }

    @Test
    void theMaturityDateCanBeRestatedButNotOnAClosedPolicy() {
        var issued = fixtures.issueDeposit(TENANT, new BigDecimal("1000000.00"), 3, LocalDate.now());
        LocalDate later = LocalDate.now().plusMonths(3).plusDays(4);
        asTenant(() -> { policyApi.restateMaturityDate(issued.policyNumber(), later); return null; });
        assertThat(asTenant(() -> policyApi.getPolicy(issued.policyNumber())).maturityDate()).isEqualTo(later);

        asTenant(() -> { policyApi.markMatured(issued.policyNumber(), "test"); return null; });
        assertThatThrownBy(() -> asTenant(() -> { policyApi.restateMaturityDate(issued.policyNumber(), later.plusDays(1)); return null; }))
            .hasMessageContaining("is closed");
    }

    @Test
    void theExpirySweepNeverExpiresADepositAndItCannotBeMadePaidUp() {
        // Commenced three months and a day ago on a three-month term: past its maturity date.
        var issued = fixtures.issueDeposit(TENANT, new BigDecimal("1000000.00"), 3, LocalDate.now().minusMonths(3).minusDays(1));
        asTenant(() -> { policyApi.expirePolicy(issued.policyNumber()); return null; });
        assertThat(asTenant(() -> policyApi.getPolicy(issued.policyNumber())).status()).isEqualTo(PolicyStatus.ACTIVE);
        assertThatThrownBy(() -> asTenant(() -> policyApi.makePaidUp(issued.policyNumber(), "staff-one")))
            .hasMessageContaining("fixed-term deposit");
    }
}
```

Imports: `Application`, `MigrationTestSupport`, `TenantContext`, `PolicyApi`, `PolicyStatus`, `BigDecimal`, `LocalDate`, `List`, `UUID`, the Spring test annotations and AssertJ, all as in the class it was copied from.

- [ ] **Step 3: Run it and see it fail**

Run: `./mvnw -B -o test -Dtest=DepositIssuanceIntegrationTest`
Expected: COMPILATION ERROR, because `restateMaturityDate` is undefined.

- [ ] **Step 4: Policy.restateMaturityDate**

In `policy/domain/Policy.java`, after `applyTerm`:

```java
    /**
     * A fixed-term deposit's maturity follows its MONEY, not its issue date: the single premium can
     * arrive days after issue, and a reinvestment starts a new term. Accumulation restates it then.
     * The term in months is unchanged; only the date the expiry sweep and on-risk reads move.
     */
    public void restateMaturityDate(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("A maturity date is required");
        }
        if (isClosed()) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " is closed; its maturity date cannot move");
        }
        this.maturityDate = date;
    }
```

- [ ] **Step 5: PolicyApi and PolicyApiImpl**

In `policy/api/PolicyApi.java`, next to `markMatured`:

```java
    /** Accumulation's, for a fixed-term deposit: when its money arrives, and on each reinvestment. */
    void restateMaturityDate(String policyNumber, java.time.LocalDate maturityDate);
```

In `PolicyApiImpl.java`:

```java
    @Override
    @Transactional
    public void restateMaturityDate(String policyNumber, LocalDate maturityDate) {
        Policy policy = findPolicyOrThrow(policyNumber, TenantContext.get());
        policy.restateMaturityDate(maturityDate);
        policyRepository.save(policy);
    }

    /** A fixed-term deposit is one payment, of the deposit itself, for a term its grid offers (§R9). */
    private void refuseUnlessAValidDeposit(IssueRequest request) {
        DepositPlan deposit = productApi.resolveDepositPlan(request.productVersionId());
        if (!deposit.isDeposit()) {
            return;
        }
        if (!"SINGLE".equals(request.premiumFrequency())) {
            throw new IllegalArgumentException("A fixed-term deposit is paid once: its premium frequency must be SINGLE, not "
                + request.premiumFrequency());
        }
        if (request.premiumAmount() == null || request.sumAssuredAmount() == null
                || request.premiumAmount().compareTo(request.sumAssuredAmount()) != 0) {
            throw new IllegalArgumentException("A fixed-term deposit's premium is the deposit itself, so it must equal the sum assured");
        }
        if (request.policyTermMonths() == null || !deposit.terms().contains(request.policyTermMonths())) {
            throw new IllegalArgumentException("This deposit offers terms of " + deposit.terms() + " months, not "
                + request.policyTermMonths());
        }
        if (deposit.rateFor(request.sumAssuredAmount(), request.policyTermMonths()).isEmpty()) {
            throw new IllegalArgumentException("A deposit of " + request.sumAssuredAmount().stripTrailingZeros().toPlainString()
                + " is below the smallest band this product offers ("
                + deposit.bandStarts().get(0).stripTrailingZeros().toPlainString() + ")");
        }
    }
```

In `issuePolicy`, right after `ProductSnapshotView snapshot = productApi.getActiveSnapshot(...)`:

```java
        refuseUnlessAValidDeposit(request);
```

In `expirePolicy`, widen the guard:

```java
        if (productApi.resolvePayoutPlan(policy.getProductVersionId()).hasEndOfTermRow()
                || productApi.resolveDepositPlan(policy.getProductVersionId()).isDeposit()) {
            // ...and a fixed-term deposit matures through accumulation's drain (pay out or
            // reinvest), never by expiry -- its money is still on the account.
            return;
        }
```

In `makePaidUp`, right after `Policy policy = findPolicyOrThrow(policyNumber, tenantId);`:

```java
        if (productApi.resolveDepositPlan(policy.getProductVersionId()).isDeposit()) {
            throw new InvalidPolicyStateException("Policy " + policyNumber
                + " is a fixed-term deposit: it was paid once and has no premiums to stop");
        }
```

Import `tz.co.nlolo.lifeplatform.product.api.DepositPlan`.

- [ ] **Step 6: Automatic issuance charges the deposit**

In `UnderwritingDecisionEventListener.java`, immediately before the `if (instalmentPremium.signum() <= 0)` check, change `BigDecimal instalmentPremium =` to a non-final local if it is final. Then add:

```java
                // A fixed-term deposit's premium is the deposit itself (§R9) -- the rating formula
                // prices risk, and a deposit is not priced. issuePolicy refuses anything but SINGLE.
                if (productApi.resolveDepositPlan(decidedCase.productVersionId()).isDeposit()) {
                    instalmentPremium = decidedCase.sumAssuredAmount();
                }
```

If `instalmentPremium` is used inside a lambda afterwards (the effectively-final rule), add `final BigDecimal charged = instalmentPremium;` and use `charged` in the `IssueRequest`. Compile to find out.

- [ ] **Step 7: Run the tests**

Run: `./mvnw -B -o test-compile`, then `./mvnw -B -o test -Dtest=DepositIssuanceIntegrationTest`
Expected: PASS. If `issueDeposit` fails inside `issuePolicy`, read the message: the fixture's request must satisfy every rule above.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/policy src/test/java/tz/co/nlolo/lifeplatform/accumulation
git commit -m "feat(policy): a deposit is issued as one payment of itself, never expires by sweep, and its maturity date follows its money"
```

---

### Task 3: The payer's number, and the deposit payout purpose

**Files:** the Payment and billing block in the file map.

**Interfaces:**
- Produces:
  - `payment.PaymentConfirmed` payload key `payerRef` (String, always present)
  - `billing.PremiumCollected` payload key `payerRef` (present only when the collection had one)
  - `BillingApi.applyConfirmedPayment(UUID, BigDecimal, String, String, String payerRef)`
  - payment purpose `DEPOSIT_MATURITY_PAYOUT`

- [ ] **Step 1: payment V10**

`backend/db-migrations/payment/V10__deposit_maturity_purpose.sql`:

```sql
-- db-migrations/payment/V10__deposit_maturity_purpose.sql
-- A fixed-term deposit paid out at maturity (2026-10-02). Its own purpose, not MATURITY_PAYOUT:
-- benefitpayout settles every MATURITY_PAYOUT against an instalment it owns, and a deposit has none.
ALTER TABLE payment.disbursement_instruction
    DROP CONSTRAINT disbursement_instruction_purpose_check;
ALTER TABLE payment.disbursement_instruction
    ADD CONSTRAINT disbursement_instruction_purpose_check CHECK (purpose IN
        ('LOAN_DISBURSEMENT','CLAIM_SETTLEMENT','COMMISSION_PAYOUT','SURRENDER_PAYOUT',
         'MATURITY_PAYOUT','DIVIDEND_PAYOUT',
         'SURVIVAL_BENEFIT_PAYOUT','INCOME_PAYOUT','PREMIUM_RETURN_PAYOUT','FREE_LOOK_REFUND',
         'WITHDRAWAL_PAYOUT',
         'DEPOSIT_MATURITY_PAYOUT'));
```

- [ ] **Step 2: payerRef on PaymentConfirmed**

In `PaymentApiImpl.confirmCollection`, add one entry to the `Map.of` (nine entries; `Map.of` takes up to ten):

```java
                   "purpose", transaction.getPurpose(),
                   // The number the money came from. A fixed-term deposit pays back to it by default.
                   "payerRef", transaction.getPayerRef())));
```

- [ ] **Step 3: Billing carries it onto PremiumCollected**

`BillingApi.java`, under the existing `applyConfirmedPayment`:

```java
    /** As above, with the number the money came from; carried onto billing.PremiumCollected as payerRef. */
    InvoiceView applyConfirmedPayment(UUID invoiceId, BigDecimal amount, String currency, String paymentReference, String payerRef);
```

`BillingApiImpl.java`:
1. The existing four-argument method keeps its `@Override @Transactional`. Its body becomes `return applyConfirmedPayment(invoiceId, amount, currency, paymentReference, null);`.
2. Add a five-argument `@Override @Transactional` method holding the old body.
3. In it, after `collected.put("paidToDate", ...)`:

```java
            // Who paid, as a number: a fixed-term deposit pays back to it. Absent for a field
            // receipt, which has no number -- consumers must treat a missing key as "none known".
            if (payerRef != null && !payerRef.isBlank()) {
                collected.put("payerRef", payerRef);
            }
```

In `billing/application/PaymentEventListener.handleConfirmed`:

```java
        billingApi.applyConfirmedPayment(invoiceId, paidAmount, currency, (String) payload.get("gatewayReference"),
            (String) payload.get("payerRef"));
```

- [ ] **Step 4: Compile and run billing's own tests**

Run: `./mvnw -B -o test-compile`, then `./mvnw -B -o test -Dtest=SinglePremiumIntegrationTest+BillingContractTest`
Expected: PASS. The end-to-end proof that the number reaches accumulation is Task 4's `aCollectionThroughBillingRecordsTheNumberItCameFrom`.

- [ ] **Step 5: Commit**

```bash
git add db-migrations/payment/V10__deposit_maturity_purpose.sql src/main/java/tz/co/nlolo/lifeplatform/payment src/main/java/tz/co/nlolo/lifeplatform/billing
git commit -m "feat(payment,billing): a confirmed collection names the number it came from; DEPOSIT_MATURITY_PAYOUT purpose"
```

---

### Task 4: Accumulation — deposit periods, interest, and every exit before maturity

**Files:** the Accumulation block (all except the drain, the controller and the specs, which are Tasks 5-6).

**Interfaces:**
- Consumes:
  - `ProductApi.resolveDepositPlan` and `DepositPlan.rateFor`
  - `PolicyApi.restateMaturityDate`
  - `PolicyView.policyTermMonths()`
  - the `payerRef` on `billing.PremiumCollected`
- Produces:
  - `DepositInterest.accrued(BigDecimal principal, BigDecimal ratePercent, LocalDate start, LocalDate maturity, LocalDate asOf)` and `DepositInterest.full(BigDecimal principal, BigDecimal ratePercent)`
  - entity `DepositPeriod`, with:
    - getters
    - `interestTo(LocalDate)` and `fullInterest()`
    - `end(DepositPeriodStatus, BigDecimal interest, LocalDate on)`
    - `int recordPayoutAttempt()`
  - `DepositPeriodRepository`: `findByPolicyNumberAndStatus`, `findByPolicyNumberOrderBySeq`, `findDueAcrossTenants`, `findMatured`
  - `Deposits` (`@Service("accumulationDeposits")`):
    - `isDepositVersion(UUID)`
    - `refuseMovement(Account)`
    - `credit(Account, UUID invoiceId, BigDecimal amount, LocalDate on, String payerRef)`
    - `endRunning(Account, DepositPeriodStatus, BigDecimal interest, LocalDate on)`
    - `interestTo(Account, LocalDate)`
  - `AccumulationApi.isDeposit(String)` and `findDeposit(String)`, returning `Optional<DepositView>`
  - the api records in the file map

- [ ] **Step 1: The arithmetic test**

`backend/src/test/java/tz/co/nlolo/lifeplatform/accumulation/DepositInterestTest.java`:

```java
package tz.co.nlolo.lifeplatform.accumulation;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.accumulation.application.DepositInterest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §7: a rate for the term is that rate, exactly, and pro rata is by day, rounded once. */
class DepositInterestTest {

    private static final BigDecimal MILLION = new BigDecimal("1000000.00");
    private static final LocalDate START = LocalDate.of(2026, 1, 15);

    @Test
    void aWholeTermPaysTheRateExactly() {
        assertThat(DepositInterest.accrued(MILLION, new BigDecimal("3"), START, START.plusMonths(3), START.plusMonths(3)))
            .isEqualByComparingTo("30000.00");
        assertThat(DepositInterest.accrued(MILLION, new BigDecimal("4"), START, START.plusMonths(6), START.plusMonths(6)))
            .isEqualByComparingTo("40000.00");
        assertThat(DepositInterest.accrued(MILLION, new BigDecimal("5"), START, START.plusMonths(12), START.plusMonths(12)))
            .isEqualByComparingTo("50000.00");
        assertThat(DepositInterest.full(MILLION, new BigDecimal("3"))).isEqualByComparingTo("30000.00");
    }

    @Test
    void anEarlyExitEarnsByTheDay() {
        // 15 Jan -> 15 Apr is 90 days; 45 held is half: 15,000.00.
        assertThat(DepositInterest.accrued(MILLION, new BigDecimal("3"), START, START.plusMonths(3), START.plusDays(45)))
            .isEqualByComparingTo("15000.00");
        // 45 of 92 days (1 Jul -> 1 Oct): 30,000 x 45 / 92 = 14,673.913... -> 14,673.91, once.
        LocalDate july = LocalDate.of(2026, 7, 1);
        assertThat(DepositInterest.accrued(MILLION, new BigDecimal("3"), july, july.plusMonths(3), july.plusDays(45)))
            .isEqualByComparingTo("14673.91");
    }

    @Test
    void neverBeforeTheStartAndNeverPastTheTerm() {
        assertThat(DepositInterest.accrued(MILLION, new BigDecimal("3"), START, START.plusMonths(3), START.minusDays(5)))
            .isEqualByComparingTo("0.00");
        assertThat(DepositInterest.accrued(MILLION, new BigDecimal("3"), START, START.plusMonths(3), START.plusYears(1)))
            .isEqualByComparingTo("30000.00");
    }
}
```

Run: `./mvnw -B -o test -Dtest=DepositInterestTest`. Expected: COMPILATION ERROR.

- [ ] **Step 2: DepositInterest**

`backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/application/DepositInterest.java`:

```java
package tz.co.nlolo.lifeplatform.accumulation.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * A fixed-term deposit's interest: the rate is FOR THE TERM (spec D2), earned evenly by day (D4).
 * Not step 3's InterestCalculator -- that compounds an annual rate daily, and a quarter of 89-92
 * days would never pay exactly 3%.
 */
public final class DepositInterest {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private DepositInterest() {}

    /** principal x rate / 100 x days held / days in the term; held is clamped to [0, term]. Rounded once. */
    public static BigDecimal accrued(BigDecimal principal, BigDecimal ratePercent, LocalDate start, LocalDate maturity,
                                     LocalDate asOf) {
        long term = ChronoUnit.DAYS.between(start, maturity);
        if (term <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        long held = Math.max(0, Math.min(term, ChronoUnit.DAYS.between(start, asOf)));
        return principal.multiply(ratePercent).multiply(BigDecimal.valueOf(held))
            .divide(HUNDRED.multiply(BigDecimal.valueOf(term)), 2, RoundingMode.HALF_EVEN);
    }

    public static BigDecimal full(BigDecimal principal, BigDecimal ratePercent) {
        return principal.multiply(ratePercent).divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
    }
}
```

Run: `./mvnw -B -o test -Dtest=DepositInterestTest`. Expected: PASS.

- [ ] **Step 3: accumulation V3**

`backend/db-migrations/accumulation/V3__deposit_periods.sql`:

```sql
-- db-migrations/accumulation/V3__deposit_periods.sql
-- A fixed-term deposit's terms (2026-10-02). The balance and every movement stay on V1's ledger;
-- these tables record what each term was agreed at, and what the client asked for at its end.

-- One row per term: period 1 when the deposit arrives, n+1 at each reinvestment. The period IS
-- the record of its rate, so its terms are fixed by trigger once written; only its status, the
-- interest it posted, its closing date and its payout attempts may change.
CREATE TABLE accumulation.deposit_period (
    period_id         UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL,
    policy_number     VARCHAR(20) NOT NULL REFERENCES accumulation.account(policy_number),
    seq               INTEGER NOT NULL CHECK (seq >= 1),
    principal         NUMERIC(19,2) NOT NULL CHECK (principal > 0),
    term_months       INTEGER NOT NULL CHECK (term_months BETWEEN 1 AND 120),
    rate_percent      NUMERIC(7,4) NOT NULL CHECK (rate_percent BETWEEN 0 AND 100),
    -- The version the rate was read from: the policy's own for period 1, the version active for
    -- new business at maturity for a reinvestment (D3, D8).
    rate_version_id   UUID NOT NULL,
    start_date        DATE NOT NULL,
    maturity_date     DATE NOT NULL CHECK (maturity_date > start_date),
    status            VARCHAR(12) NOT NULL DEFAULT 'RUNNING'
                      CHECK (status IN ('RUNNING','MATURED','TERMINATED','CANCELLED')),
    interest_posted   NUMERIC(19,2) CHECK (interest_posted IS NULL OR interest_posted >= 0),
    closed_on         DATE,
    -- The number the deposit was collected from; where the money goes when nobody says otherwise.
    -- Null for a cash receipt, and then a matured deposit waits for staff to record a payee.
    default_payee_ref VARCHAR(200),
    payout_attempts   INTEGER NOT NULL DEFAULT 0 CHECK (payout_attempts >= 0),
    version           BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT deposit_period_closed_shape CHECK ((status = 'RUNNING') = (closed_on IS NULL)),
    CONSTRAINT ux_deposit_period_seq UNIQUE (policy_number, seq)
);
-- At most one running term per deposit.
CREATE UNIQUE INDEX ux_deposit_period_running ON accumulation.deposit_period (policy_number) WHERE status = 'RUNNING';

CREATE OR REPLACE FUNCTION accumulation.deposit_period_terms_fixed() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.policy_number, NEW.seq, NEW.principal, NEW.term_months, NEW.rate_percent, NEW.rate_version_id,
        NEW.start_date, NEW.maturity_date, NEW.default_payee_ref)
       IS DISTINCT FROM
       (OLD.policy_number, OLD.seq, OLD.principal, OLD.term_months, OLD.rate_percent, OLD.rate_version_id,
        OLD.start_date, OLD.maturity_date, OLD.default_payee_ref) THEN
        RAISE EXCEPTION 'deposit period % is the record of its rate: its terms cannot change', OLD.period_id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER deposit_period_terms_fixed BEFORE UPDATE ON accumulation.deposit_period
    FOR EACH ROW EXECUTE FUNCTION accumulation.deposit_period_terms_fixed();

-- app_role has no DELETE grant; this is the owner-level guard, as V1's ledger has.
CREATE OR REPLACE FUNCTION accumulation.deposit_period_never_deleted() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'deposit period % is the record of its rate: it cannot be deleted', OLD.period_id;
END $$;
CREATE TRIGGER deposit_period_never_deleted BEFORE DELETE ON accumulation.deposit_period
    FOR EACH ROW EXECUTE FUNCTION accumulation.deposit_period_never_deleted();

-- What the client asked for at the end of a term (D7). Append-only: a change writes a new row and
-- marks the previous one superseded, so the history of what was asked for is kept.
CREATE TABLE accumulation.maturity_instruction (
    instruction_id UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL,
    policy_number  VARCHAR(20) NOT NULL,
    period_id      UUID NOT NULL REFERENCES accumulation.deposit_period(period_id),
    action         VARCHAR(10) NOT NULL CHECK (action IN ('REINVEST','PAY_OUT')),
    term_months    INTEGER CHECK (term_months BETWEEN 1 AND 120),
    payee_ref      VARCHAR(200),
    recorded_by    VARCHAR(100) NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL,
    superseded_at  TIMESTAMPTZ,
    version        BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT maturity_instruction_shape CHECK ((action = 'REINVEST') = (term_months IS NOT NULL))
);
CREATE UNIQUE INDEX ux_maturity_instruction_current ON accumulation.maturity_instruction (period_id)
    WHERE superseded_at IS NULL;

CREATE OR REPLACE FUNCTION accumulation.maturity_instruction_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'maturity instructions are append-only';
    END IF;
    IF OLD.superseded_at IS NOT NULL
       OR (NEW.instruction_id, NEW.policy_number, NEW.period_id, NEW.action, NEW.term_months, NEW.payee_ref,
           NEW.recorded_by, NEW.recorded_at)
          IS DISTINCT FROM
          (OLD.instruction_id, OLD.policy_number, OLD.period_id, OLD.action, OLD.term_months, OLD.payee_ref,
           OLD.recorded_by, OLD.recorded_at) THEN
        RAISE EXCEPTION 'maturity instructions are append-only: only superseding one is allowed';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER maturity_instruction_append_only BEFORE UPDATE OR DELETE ON accumulation.maturity_instruction
    FOR EACH ROW EXECUTE FUNCTION accumulation.maturity_instruction_append_only();

-- The deposit maturity run's selector: running terms whose maturity has come, across tenants. Ids
-- only, as accounts_due_month_end() is; the drain reads everything else under RLS.
CREATE OR REPLACE FUNCTION accumulation.deposits_due()
RETURNS TABLE (policy_number VARCHAR, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT p.policy_number, p.tenant_id FROM accumulation.deposit_period p
     WHERE p.status = 'RUNNING' AND p.maturity_date <= current_date
     ORDER BY p.maturity_date
     LIMIT 500;
$$;
REVOKE EXECUTE ON FUNCTION accumulation.deposits_due() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION accumulation.deposits_due() TO app_role;

ALTER TABLE accumulation.deposit_period ENABLE ROW LEVEL SECURITY;
CREATE POLICY deposit_period_tenant_isolation ON accumulation.deposit_period
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE accumulation.maturity_instruction ENABLE ROW LEVEL SECURITY;
CREATE POLICY maturity_instruction_tenant_isolation ON accumulation.maturity_instruction
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON accumulation.deposit_period, accumulation.maturity_instruction TO app_role;
```

- [ ] **Step 4: The api types**

In `backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/api/`, one file each:

```java
package tz.co.nlolo.lifeplatform.accumulation.api;

/** A deposit term's life: it runs, then matures, is terminated early, or is cancelled in the free look. */
public enum DepositPeriodStatus { RUNNING, MATURED, TERMINATED, CANCELLED }
```

```java
package tz.co.nlolo.lifeplatform.accumulation.api;

/** What the client asked for at the end of the term (spec D7). */
public enum MaturityAction { REINVEST, PAY_OUT }
```

```java
package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record DepositPeriodView(UUID periodId, String policyNumber, int seq, BigDecimal principal, int termMonths,
                                BigDecimal ratePercent, UUID rateVersionId, LocalDate startDate, LocalDate maturityDate,
                                DepositPeriodStatus status, BigDecimal interestPosted, LocalDate closedOn) {}
```

```java
package tz.co.nlolo.lifeplatform.accumulation.api;

import java.time.Instant;
import java.util.UUID;

public record MaturityInstructionView(UUID instructionId, UUID periodId, MaturityAction action, Integer termMonths,
                                      String payeeRef, String recordedBy, Instant recordedAt) {}
```

```java
package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * A fixed-term deposit as the console shows it. {@code periods} oldest first. {@code instruction}
 * is the current one for the running term, or null. {@code interestSoFar} is earned and not
 * posted. {@code termsOffered} are those of the version active for new business today: what a
 * reinvestment may choose.
 */
public record DepositView(String policyNumber, String currency, List<DepositPeriodView> periods,
                          MaturityInstructionView instruction, BigDecimal interestSoFar, String defaultPayeeRef,
                          boolean awaitingPayee, List<Integer> termsOffered) {}
```

```java
package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A matured deposit whose money is still on the account: no number to pay it to (§R5). */
public record AwaitingPayeeView(String policyNumber, BigDecimal balance, String currency, LocalDate maturedOn) {}
```

In `AccumulationApi.java`, add a section:

```java
    // ---- Fixed-term deposits (2026-10-02) ------------------------------------------------------

    /** True when the policy's account is on a deposit version. */
    boolean isDeposit(String policyNumber);

    /** Empty for any policy that is not a fixed-term deposit. */
    Optional<DepositView> findDeposit(String policyNumber);
```

(Task 5 adds the instruction, the payout and the awaiting list.)

- [ ] **Step 5: Entities and repositories**

`accumulation/domain/DepositPeriod.java`:

```java
package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationStateException;
import tz.co.nlolo.lifeplatform.accumulation.api.DepositPeriodStatus;
import tz.co.nlolo.lifeplatform.accumulation.application.DepositInterest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One term of a fixed-term deposit (V3). Its rate and dates are fixed once written. */
@Entity
@Table(name = "deposit_period", schema = "accumulation")
public class DepositPeriod {
    @Id @UuidGenerator @Column(name = "period_id") private UUID periodId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private int seq;
    @Column(nullable = false) private BigDecimal principal;
    @Column(name = "term_months", nullable = false) private int termMonths;
    @Column(name = "rate_percent", nullable = false) private BigDecimal ratePercent;
    @Column(name = "rate_version_id", nullable = false) private UUID rateVersionId;
    @Column(name = "start_date", nullable = false) private LocalDate startDate;
    @Column(name = "maturity_date", nullable = false) private LocalDate maturityDate;
    @Column(nullable = false) private String status = DepositPeriodStatus.RUNNING.name();
    @Column(name = "interest_posted") private BigDecimal interestPosted;
    @Column(name = "closed_on") private LocalDate closedOn;
    @Column(name = "default_payee_ref") private String defaultPayeeRef;
    @Column(name = "payout_attempts", nullable = false) private int payoutAttempts;
    @Version private long version;

    protected DepositPeriod() {}

    public DepositPeriod(UUID tenantId, String policyNumber, int seq, BigDecimal principal, int termMonths,
                         BigDecimal ratePercent, UUID rateVersionId, LocalDate startDate, String defaultPayeeRef) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.seq = seq;
        this.principal = principal;
        this.termMonths = termMonths;
        this.ratePercent = ratePercent;
        this.rateVersionId = rateVersionId;
        this.startDate = startDate;
        this.maturityDate = startDate.plusMonths(termMonths);
        this.defaultPayeeRef = defaultPayeeRef;
    }

    public DepositPeriodStatus status() { return DepositPeriodStatus.valueOf(status); }

    public BigDecimal interestTo(LocalDate day) {
        return DepositInterest.accrued(principal, ratePercent, startDate, maturityDate, day);
    }

    public BigDecimal fullInterest() { return DepositInterest.full(principal, ratePercent); }

    /** The only change a period may undergo: it ends, saying how, what interest it posted and when. */
    public void end(DepositPeriodStatus how, BigDecimal interest, LocalDate on) {
        if (status() != DepositPeriodStatus.RUNNING) {
            throw new AccumulationStateException("Deposit term " + seq + " on policy " + policyNumber + " has already ended ("
                + status + ")");
        }
        if (how == DepositPeriodStatus.RUNNING) {
            throw new IllegalArgumentException("A term ends as MATURED, TERMINATED or CANCELLED");
        }
        this.status = how.name();
        this.interestPosted = interest;
        this.closedOn = on;
    }

    /** Each attempt to pay the matured money gets its own source reference (§R7). Returns the attempt's number. */
    public int recordPayoutAttempt() { return ++payoutAttempts; }

    public UUID getPeriodId() { return periodId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public int getSeq() { return seq; }
    public BigDecimal getPrincipal() { return principal; }
    public int getTermMonths() { return termMonths; }
    public BigDecimal getRatePercent() { return ratePercent; }
    public UUID getRateVersionId() { return rateVersionId; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getMaturityDate() { return maturityDate; }
    public BigDecimal getInterestPosted() { return interestPosted; }
    public LocalDate getClosedOn() { return closedOn; }
    public String getDefaultPayeeRef() { return defaultPayeeRef; }
}
```

`accumulation/domain/MaturityInstruction.java`:

```java
package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.accumulation.api.MaturityAction;

import java.time.Instant;
import java.util.UUID;

/** What the client asked for at the end of a term (V3). Superseded, never edited. */
@Entity
@Table(name = "maturity_instruction", schema = "accumulation")
public class MaturityInstruction {
    @Id @UuidGenerator @Column(name = "instruction_id") private UUID instructionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "period_id", nullable = false) private UUID periodId;
    @Column(nullable = false) private String action;
    @Column(name = "term_months") private Integer termMonths;
    @Column(name = "payee_ref") private String payeeRef;
    @Column(name = "recorded_by", nullable = false) private String recordedBy;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt;
    @Column(name = "superseded_at") private Instant supersededAt;
    @Version private long version;

    protected MaturityInstruction() {}

    public MaturityInstruction(UUID tenantId, String policyNumber, UUID periodId, MaturityAction action, Integer termMonths,
                               String payeeRef, String recordedBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.periodId = periodId;
        this.action = action.name();
        this.termMonths = termMonths;
        this.payeeRef = payeeRef;
        this.recordedBy = recordedBy;
        this.recordedAt = Instant.now();
    }

    public void supersede() { this.supersededAt = Instant.now(); }

    public MaturityAction action() { return MaturityAction.valueOf(action); }
    public UUID getInstructionId() { return instructionId; }
    public UUID getPeriodId() { return periodId; }
    public Integer getTermMonths() { return termMonths; }
    public String getPayeeRef() { return payeeRef; }
    public String getRecordedBy() { return recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
}
```

`accumulation/infrastructure/DepositPeriodRepository.java`:

```java
package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tz.co.nlolo.lifeplatform.accumulation.domain.DepositPeriod;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepositPeriodRepository extends JpaRepository<DepositPeriod, UUID> {

    Optional<DepositPeriod> findByPolicyNumberAndStatus(String policyNumber, String status);

    List<DepositPeriod> findByPolicyNumberOrderBySeq(String policyNumber);

    @Query(value = "SELECT policy_number, tenant_id FROM accumulation.deposits_due()", nativeQuery = true)
    List<Object[]> findDueAcrossTenants();

    /** Every period that ended MATURED, latest per policy last -- the awaiting-payee list filters these. */
    List<DepositPeriod> findByStatusOrderByClosedOn(String status);
}
```

`accumulation/infrastructure/MaturityInstructionRepository.java`:

```java
package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.accumulation.domain.MaturityInstruction;

import java.util.Optional;
import java.util.UUID;

public interface MaturityInstructionRepository extends JpaRepository<MaturityInstruction, UUID> {
    Optional<MaturityInstruction> findByPeriodIdAndSupersededAtIsNull(UUID periodId);
}
```

In `accumulation/domain/Account.java`, after `reopen()`:

```java
    /** A matured deposit whose payment failed: the money is back, and waits for a payee again (§R7). */
    public void reopenAwaitingPayee() {
        if (!"MATURED".equals(closedReason)) {
            throw new AccumulationStateException("Policy " + policyNumber + "'s account closed on "
                + closedReason + " and cannot reopen for a payee");
        }
        this.status = AccountStatus.OPEN.name();
        this.closedReason = null;
        this.closedOn = null;
    }
```

- [ ] **Step 6: Deposits — the deposit rules in one place**

`accumulation/application/Deposits.java`:

```java
package tz.co.nlolo.lifeplatform.accumulation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.api.*;
import tz.co.nlolo.lifeplatform.accumulation.domain.Account;
import tz.co.nlolo.lifeplatform.accumulation.domain.DepositPeriod;
import tz.co.nlolo.lifeplatform.accumulation.domain.MaturityInstruction;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.*;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fixed-term deposits (2026-10-02): the terms, their interest, the client's instruction and the
 * maturity run. The ledger stays LedgerService's: every movement here is a posting with a source
 * reference, so step 3's three rules hold unchanged. Task 5 adds maturity.
 */
@Service("accumulationDeposits")
public class Deposits {

    private static final Logger log = LoggerFactory.getLogger(Deposits.class);

    final AccountRepository accounts;
    final DepositPeriodRepository periods;
    final MaturityInstructionRepository instructions;
    final PostingRepository postings;
    final LedgerEntryRepository entries;
    final LedgerService ledger;
    final PolicyApi policyApi;
    final ProductApi productApi;
    final ApplicationEventPublisher events;

    public Deposits(AccountRepository accounts, DepositPeriodRepository periods, MaturityInstructionRepository instructions,
                    PostingRepository postings, LedgerEntryRepository entries, LedgerService ledger, PolicyApi policyApi,
                    ProductApi productApi, ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.periods = periods;
        this.instructions = instructions;
        this.postings = postings;
        this.entries = entries;
        this.ledger = ledger;
        this.policyApi = policyApi;
        this.productApi = productApi;
        this.events = events;
    }

    /**
     * Asked by product, not by reading deposit_period, so an ordinary account's path never reaches
     * V3's tables. That keeps V3 out of every test class that only knows step 3.
     */
    boolean isDepositVersion(UUID productVersionId) {
        return productApi.resolveDepositPlan(productVersionId).isDeposit();
    }

    Optional<DepositPeriod> running(String policyNumber) {
        return periods.findByPolicyNumberAndStatus(policyNumber, DepositPeriodStatus.RUNNING.name());
    }

    /** D5: nothing in, nothing out during the term. Adjustments stay -- they are the two-person correction. */
    void refuseMovement(Account account) {
        if (!isDepositVersion(account.getProductVersionId())) {
            return;
        }
        String until = running(account.getPolicyNumber()).map(p -> p.getMaturityDate().toString()).orElse("the end of its term");
        throw new AccumulationStateException("This is a fixed-term deposit: nothing can be added or taken out until it matures on "
            + until);
    }

    /**
     * {@code billing.PremiumCollected} on a deposit. The deposit goes in as ONE posting keyed on the
     * invoice, with no allocation charge, and period 1 opens on the day it arrived, at the policy
     * version's rate for this amount and term. Then the policy's maturity moves to follow the money.
     */
    @Transactional
    public void credit(Account account, UUID invoiceId, BigDecimal amount, LocalDate on, String payerRef) {
        String policyNumber = account.getPolicyNumber();
        if (postings.findByTenantIdAndSourceTypeAndSourceRef(TenantContext.get(), "invoice", "invoice:" + invoiceId).isPresent()) {
            return; // a redelivery
        }
        if (!periods.findByPolicyNumberOrderBySeq(policyNumber).isEmpty()) {
            log.error("A second premium (invoice {}) arrived on fixed-term deposit {}; a deposit takes one payment. {} must be "
                + "refunded by hand", invoiceId, policyNumber, amount);
            return;
        }
        Integer term = policyApi.getPolicy(policyNumber).policyTermMonths();
        BigDecimal rate = (term == null ? Optional.<BigDecimal>empty()
                : productApi.resolveDepositPlan(account.getProductVersionId()).rateFor(amount, term))
            .orElseThrow(() -> new AccumulationStateException("Fixed-term deposit " + policyNumber + ": no rate for "
                + amount.toPlainString() + " over " + term + " months on its version; the deposit is NOT credited"));
        ledger.post(policyNumber, LedgerService.Source.invoice(invoiceId), List.of(LedgerService.Line.of(EntryType.CONTRIBUTION,
                amount, on, "Deposit for " + term + " months at " + rate.stripTrailingZeros().toPlainString() + "% for the term")),
            "system", null);
        DepositPeriod period = periods.save(new DepositPeriod(TenantContext.get(), policyNumber, 1, amount, term, rate,
            account.getProductVersionId(), on, blankToNull(payerRef)));
        policyApi.restateMaturityDate(policyNumber, period.getMaturityDate());
    }

    /** Unposted interest on the running term up to {@code day}; zero when no term runs (§R6). */
    BigDecimal interestTo(Account account, LocalDate day) {
        return running(account.getPolicyNumber()).map(p -> p.interestTo(day)).orElse(BigDecimal.ZERO.setScale(2));
    }

    /** An early exit: the closing posting carried the interest, and the term ends with it in the same transaction. */
    void endRunning(Account account, DepositPeriodStatus how, BigDecimal interest, LocalDate on) {
        if (!isDepositVersion(account.getProductVersionId())) {
            return;
        }
        running(account.getPolicyNumber()).ifPresent(p -> {
            p.end(how, interest, on);
            periods.save(p);
        });
    }

    @Transactional(readOnly = true)
    public Optional<DepositView> find(String policyNumber) {
        Optional<Account> account = accounts.findById(policyNumber);
        if (account.isEmpty() || !isDepositVersion(account.get().getProductVersionId())) {
            return Optional.empty();
        }
        Account a = account.get();
        List<DepositPeriod> all = periods.findByPolicyNumberOrderBySeq(policyNumber);
        Optional<DepositPeriod> run = all.stream().filter(p -> p.status() == DepositPeriodStatus.RUNNING).findFirst();
        MaturityInstructionView instruction = run.flatMap(p -> instructions.findByPeriodIdAndSupersededAtIsNull(p.getPeriodId()))
            .map(Deposits::view).orElse(null);
        String defaultPayee = all.isEmpty() ? null : all.get(all.size() - 1).getDefaultPayeeRef();
        return Optional.of(new DepositView(policyNumber, a.getCurrency(), all.stream().map(Deposits::view).toList(), instruction,
            run.map(p -> p.interestTo(LocalDate.now())).orElse(BigDecimal.ZERO.setScale(2)), defaultPayee,
            awaitingPayee(a, all), termsOfferedToday(a.getProductId())));
    }

    /** §R5: matured, nothing running, money still on an open account. */
    static boolean awaitingPayee(Account account, List<DepositPeriod> all) {
        return account.status() == AccountStatus.OPEN && account.getBalance().signum() > 0 && !all.isEmpty()
            && all.stream().noneMatch(p -> p.status() == DepositPeriodStatus.RUNNING)
            && all.get(all.size() - 1).status() == DepositPeriodStatus.MATURED;
    }

    /** The terms a reinvestment may choose: those of the version active for new business (D8). */
    List<Integer> termsOfferedToday(UUID productId) {
        try {
            UUID active = productApi.getActiveSnapshot(productId, LocalDate.now()).productVersionId();
            return productApi.resolveDepositPlan(active).terms();
        } catch (RuntimeException e) {
            return List.of(); // the product no longer sells: a reinvestment would fall back to paying out
        }
    }

    static DepositPeriodView view(DepositPeriod p) {
        return new DepositPeriodView(p.getPeriodId(), p.getPolicyNumber(), p.getSeq(), p.getPrincipal(), p.getTermMonths(),
            p.getRatePercent(), p.getRateVersionId(), p.getStartDate(), p.getMaturityDate(), p.status(), p.getInterestPosted(),
            p.getClosedOn());
    }

    static MaturityInstructionView view(MaturityInstruction i) {
        return new MaturityInstructionView(i.getInstructionId(), i.getPeriodId(), i.action(), i.getTermMonths(), i.getPayeeRef(),
            i.getRecordedBy(), i.getRecordedAt());
    }

    static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
```

`getActiveSnapshot` throws `NoActiveProductVersionException`, a RuntimeException, when nothing is active; the catch above relies on that.

- [ ] **Step 7: Wire Deposits into AccumulationApiImpl, the valuer and the listener**

`AccountValuer.java`:
1. Add `private final DepositPeriodRepository periods;` to the constructor (last parameter).
2. As the first lines of `interestBetween`:

```java
        if (productApi.resolveDepositPlan(account.getProductVersionId()).isDeposit()) {
            // A deposit's rate is for its term, by day (D4) -- whatever span the caller asked about,
            // the interest is the running term's to {@code to}. None once the term has ended (§R6).
            return periods.findByPolicyNumberAndStatus(account.getPolicyNumber(), DepositPeriodStatus.RUNNING.name())
                .map(p -> p.interestTo(to)).orElse(BigDecimal.ZERO.setScale(2));
        }
```

Imports: `DepositPeriodRepository` and `accumulation.api.DepositPeriodStatus`.

`AccumulationApiImpl.java`:
1. Add `final Deposits deposits;`, as the constructor's last parameter, and assign it.
2. In `requestWithdrawal`, `requestTopUp` and `recordTransferIn`, right after `Account account = loadOpen(policyNumber);`:

```java
        deposits.refuseMovement(account);
```

3. In `close(...)`, after `account.close(closedReason, on);`:

```java
        deposits.endRunning(account, DepositPeriodStatus.TERMINATED, interest, on);
```

4. In `closeForDeath`, after `account.close("DEATH", dateOfDeath);`:

```java
        deposits.endRunning(account, DepositPeriodStatus.TERMINATED, interest, dateOfDeath);
```

5. In `closeForFreeLook`, after `account.close("FREE_LOOK", LocalDate.now());`:

```java
        deposits.endRunning(account, DepositPeriodStatus.CANCELLED, BigDecimal.ZERO.setScale(2), LocalDate.now());
```

6. In `postMonthEnd`, as the first statement:

```java
        if (deposits.isDepositVersion(account.getProductVersionId())) {
            // A deposit earns for its term, posted once at the end (D4): no monthly interest, no fee.
            account.monthEndPostedThrough(monthEnd);
            accounts.save(account);
            return;
        }
```

7. Replace `creditContribution` with a four-argument delegate plus the five-argument method:

```java
    @Transactional
    public void creditContribution(String policyNumber, UUID invoiceId, BigDecimal amount, LocalDate collectedOn) {
        creditContribution(policyNumber, invoiceId, amount, collectedOn, null);
    }

    @Transactional
    public void creditContribution(String policyNumber, UUID invoiceId, BigDecimal amount, LocalDate collectedOn,
                                   String payerRef) {
```

The old body follows, with one insertion after `LocalDate effective = ...;`:

```java
        if (deposits.isDepositVersion(account.getProductVersionId())) {
            deposits.credit(account, invoiceId, amount, effective, payerRef);
            return;
        }
```

8. Implement the two new API methods:

```java
    @Override
    @Transactional(readOnly = true)
    public boolean isDeposit(String policyNumber) {
        return accounts.findById(policyNumber).map(a -> deposits.isDepositVersion(a.getProductVersionId())).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DepositView> findDeposit(String policyNumber) {
        return deposits.find(policyNumber);
    }
```

`BillingEventListener.java`, in the `api.creditContribution(...)` call: add a trailing argument `(String) p.get("payerRef")`.

- [ ] **Step 8: The lifecycle test (part 1)**

`backend/src/test/java/tz/co/nlolo/lifeplatform/accumulation/DepositLifecycleIntegrationTest.java`:
- Copy `ClosingIntegrationTest`'s annotations, WireMock setup, `@BeforeEach gatewayAccepts` and `forceTheGatewayToDecline`.
- Copy the migration list too, and add:
  - `"db-migrations/product/V20__deposit_rate_grid.sql"` (if missing)
  - `"db-migrations/accumulation/V2__request_keys.sql"`
  - `"db-migrations/accumulation/V3__deposit_periods.sql"`
  - `"db-migrations/payment/V10__deposit_maturity_purpose.sql"`
  - and the eight billing lines from `TopUpAndTransferIntegrationTest` (lines 135-142)
- Then:

```java
    private static final UUID TENANT = UUID.randomUUID();
    private static final BigDecimal MILLION = new BigDecimal("1000000.00");
    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private AccumulationApiImpl api;
    @Autowired private PolicyApi policyApi;
    @Autowired private BenefitPayoutApi benefitPayoutApi;
    @Autowired private BillingApi billingApi;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }
    private DepositView deposit(String p) { return asTenant(() -> api.findDeposit(p)).orElseThrow(); }
    private List<LedgerEntryView> entriesOf(String p) { return asTenant(() -> api.entries(p)); }

    /** A deposit commenced and paid {@code daysAgo} days ago. */
    private String paidDeposit(BigDecimal amount, int term, LocalDate paidOn, String payerRef) {
        var issued = fixtures.issueDeposit(TENANT, amount, term, paidOn);
        fixtures.collectDeposit(TENANT, issued.policyNumber(), UUID.randomUUID(), amount, paidOn, payerRef);
        return issued.policyNumber();
    }

    @Test
    void theDepositOpensItsFirstTermAtTheVersionsRateAndTheMaturityFollowsTheMoney() {
        LocalDate paid = LocalDate.now().minusDays(10);
        String policy = paidDeposit(new BigDecimal("6000000.00"), 6, paid, "+255700000777");
        DepositPeriodView first = deposit(policy).periods().get(0);
        assertThat(first.ratePercent()).isEqualByComparingTo("5"); // 6,000,000 band, 6 months
        assertThat(first.principal()).isEqualByComparingTo("6000000.00");
        assertThat(first.startDate()).isEqualTo(paid);
        assertThat(first.maturityDate()).isEqualTo(paid.plusMonths(6));
        assertThat(first.status()).isEqualTo(DepositPeriodStatus.RUNNING);
        assertThat(deposit(policy).defaultPayeeRef()).isEqualTo("+255700000777");
        assertThat(asTenant(() -> policyApi.getPolicy(policy)).maturityDate()).isEqualTo(paid.plusMonths(6));
        // One CONTRIBUTION, no allocation charge (a zero line is never written).
        assertThat(entriesOf(policy)).extracting(LedgerEntryView::type).containsExactly(EntryType.CONTRIBUTION);
        // Ten days of 5% on 6,000,000 over the term's days.
        long termDays = java.time.temporal.ChronoUnit.DAYS.between(paid, paid.plusMonths(6));
        assertThat(deposit(policy).interestSoFar()).isEqualByComparingTo(new BigDecimal("300000.00")
            .multiply(BigDecimal.TEN).divide(BigDecimal.valueOf(termDays), 2, java.math.RoundingMode.HALF_EVEN));
    }

    @Test
    void aCollectionThroughBillingRecordsTheNumberItCameFrom() {
        var issued = fixtures.issueDeposit(TENANT, MILLION, 3, LocalDate.now());
        UUID invoiceId = asTenant(() -> billingApi.listInvoices(issued.policyNumber(), null)).get(0).invoiceId();
        // What payment publishes when the rail confirms the collection.
        fixtures.publish(TENANT, "payment.PaymentConfirmed", Map.of(
            "paymentRequestId", UUID.randomUUID(), "idempotencyKey", "k-" + invoiceId, "sourceRef", invoiceId.toString(),
            "gatewayReference", "GW-1", "amount", Map.of("amount", "1000000.00", "currencyCode", "TZS"),
            "confirmedAt", java.time.Instant.now().toString(), "purpose", "PREMIUM", "payerRef", "+255700000555"));
        assertThat(deposit(issued.policyNumber()).defaultPayeeRef()).isEqualTo("+255700000555");
    }

    @Test
    void nothingCanBeAddedOrTakenOutDuringTheTerm() {
        String policy = paidDeposit(MILLION, 3, LocalDate.now(), "+255700000777");
        String expected = "This is a fixed-term deposit: nothing can be added or taken out until it matures on "
            + LocalDate.now().plusMonths(3);
        assertThatThrownBy(() -> asTenant(() -> api.requestTopUp(policy, new BigDecimal("1000.00"), "+2557", "staff-one")))
            .hasMessage(expected);
        assertThatThrownBy(() -> asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("1000.00"), "+2557", "staff-one")))
            .hasMessage(expected);
        assertThatThrownBy(() -> asTenant(() -> api.recordTransferIn(policy, new BigDecimal("1000.00"), "NSSF", null, "fin")))
            .hasMessage(expected);
    }

    @Test
    void theMonthEndRunPostsNothingOnADeposit() {
        LocalDate paid = LocalDate.now().withDayOfMonth(1).minusMonths(2);
        String policy = paidDeposit(MILLION, 12, paid, "+255700000777");
        asTenant(() -> { api.postMonthEnds(policy, LocalDate.now()); return null; });
        assertThat(entriesOf(policy)).extracting(LedgerEntryView::type).containsExactly(EntryType.CONTRIBUTION);
    }

    @Test
    void anEarlyTerminationPaysTheDepositAndInterestByTheDayOnce() {
        LocalDate paid = LocalDate.now().minusDays(45);
        String policy = paidDeposit(MILLION, 3, paid, "+255700000777");
        BigDecimal expected = DepositInterest.accrued(MILLION, new BigDecimal("3"), paid, paid.plusMonths(3), LocalDate.now());
        var request = asTenant(() -> policyApi.requestSurrender(policy, "+255700000003", "staff-one"));
        asTenant(() -> policyApi.approveSurrender(request.surrenderRequestId(), "finance-two"));

        List<LedgerEntryView> all = entriesOf(policy);
        assertThat(all).filteredOn(e -> e.type() == EntryType.INTEREST).singleElement()
            .satisfies(e -> assertThat(e.amount()).isEqualByComparingTo(expected));
        assertThat(all.get(all.size() - 1).type()).isEqualTo(EntryType.SURRENDER);
        assertThat(all.get(all.size() - 1).amount().negate()).isEqualByComparingTo(MILLION.add(expected));
        DepositPeriodView term = deposit(policy).periods().get(0);
        assertThat(term.status()).isEqualTo(DepositPeriodStatus.TERMINATED);
        assertThat(term.interestPosted()).isEqualByComparingTo(expected);
    }

    @Test
    void aDeathIsValuedWithInterestToTheDateOfDeath() {
        LocalDate paid = LocalDate.now().minusDays(30);
        String policy = paidDeposit(MILLION, 3, paid, "+255700000777");
        LocalDate death = paid.plusDays(20);
        assertThat(asTenant(() -> api.valueAtDeath(policy, death)).accountValue())
            .isEqualByComparingTo(MILLION.add(DepositInterest.accrued(MILLION, new BigDecimal("3"), paid, paid.plusMonths(3), death)));
    }
```

Imports, in addition to `ClosingIntegrationTest`'s: `BillingApi`, `DepositInterest`, and `accumulation.api.*` (already).

- [ ] **Step 9: Run Task 4's tests**

Run: `./mvnw -B -o clean test-compile`, then
`./mvnw -B -o test -Dtest=DepositInterestTest+DepositLifecycleIntegrationTest+ClosingIntegrationTest+MonthEndIntegrationTest+ContributionIntegrationTest`
Expected: PASS. The three step-3 classes prove that ordinary accounts are unchanged and never reach V3.

- [ ] **Step 10: Commit**

```bash
git add db-migrations/accumulation/V3__deposit_periods.sql src/main/java/tz/co/nlolo/lifeplatform/accumulation src/test/java/tz/co/nlolo/lifeplatform/accumulation
git commit -m "feat(accumulation): fixed-term deposit terms -- a rate for the term, nothing in or out, pro-rata interest on every early exit"
```

---

### Task 5: Accumulation — maturity: reinvest, pay out, or wait for a payee

**Interfaces:**
- Consumes: Task 4's `Deposits`, the repositories, `ProductApi.getActiveSnapshot` and `resolveDepositPlan`, `PolicyApi.restateMaturityDate` and `markMatured`.
- Produces:
  - `Deposits.instruct(String policyNumber, MaturityAction, Integer termMonths, String payeeRef, String by)`, returning `MaturityInstructionView`
  - `Deposits.mature(String policyNumber, LocalDate today)`
  - `Deposits.payOutAwaiting(String policyNumber, String payeeRef, String by)`, returning `DepositPeriodView`
  - `Deposits.settlePayout(String sourceRef, boolean paid)`
  - `Deposits.awaiting()`, returning `List<AwaitingPayeeView>`
  - `Deposits.instructionView(UUID)` and `Deposits.periodView(UUID)`
  - `AccumulationApi`:
    - `recordMaturityInstruction(String, MaturityAction, Integer, String payeeRef, String recordedBy, String idempotencyKey)`
    - `payOutMaturedDeposit(String, String payeeRef, String requestedBy, String idempotencyKey)`
    - `listAwaitingPayee()`
  - `DepositMaturityDrain`
  - event `accumulation.DepositMatured`, payload:
    - `policyNumber`, `periodId`, `outcome` (REINVESTED | PAID_OUT | AWAITING_PAYEE), `interest`
    - `nextPeriodId` (present only when reinvested)

- [ ] **Step 1: The failing tests (lifecycle part 2)**

Append to `DepositLifecycleIntegrationTest`:

```java
    @Autowired private Deposits deposits;

    /** Paid exactly three months and a day ago on a three-month term: matured yesterday. */
    private String maturedYesterday(String payerRef) {
        LocalDate paid = LocalDate.now().minusDays(1).minusMonths(3);
        return paidDeposit(MILLION, 3, paid, payerRef);
    }

    private void runMaturity(String policy) {
        asTenant(() -> { deposits.mature(policy, LocalDate.now()); return null; });
    }

    private Map<String, Object> disbursementFor(String policy) {
        return jdbc.queryForMap("SELECT purpose, payee_ref, amount, source_ref FROM payment.disbursement_instruction "
            + "WHERE source_ref LIKE ? ORDER BY source_ref DESC LIMIT 1", // ":2" sorts after ":1"
            deposit(policy).periods().get(0).periodId() + ":%");
    }

    @Test
    void withNoInstructionTheDepositIsPaidToTheNumberItCameFrom() {
        String policy = maturedYesterday("+255700000777");
        runMaturity(policy);

        List<LedgerEntryView> all = entriesOf(policy);
        assertThat(all).extracting(LedgerEntryView::type)
            .containsExactly(EntryType.CONTRIBUTION, EntryType.INTEREST, EntryType.MATURITY);
        assertThat(all.get(1).amount()).isEqualByComparingTo("30000.00");
        assertThat(all.get(1).sourceRef()).isEqualTo("deposit-interest:" + deposit(policy).periods().get(0).periodId());
        assertThat(all.get(2).amount()).isEqualByComparingTo("-1030000.00");
        assertThat(asTenant(() -> api.findAccount(policy)).orElseThrow().closedReason()).isEqualTo("MATURED");
        assertThat(asTenant(() -> policyApi.getPolicy(policy)).status()).isEqualTo(PolicyStatus.MATURED);
        Map<String, Object> paid = disbursementFor(policy);
        assertThat(paid.get("purpose")).isEqualTo("DEPOSIT_MATURITY_PAYOUT");
        assertThat(paid.get("payee_ref")).isEqualTo("+255700000777");
        assertThat((BigDecimal) paid.get("amount")).isEqualByComparingTo("1030000.00");
        // Running it again moves nothing: the term is no longer RUNNING.
        runMaturity(policy);
        assertThat(entriesOf(policy)).hasSize(3);
    }

    @Test
    void reinvestedForANewTermAtTheRateOfTheVersionInForceAtMaturity() {
        String policy = maturedYesterday("+255700000777");
        UUID productId = asTenant(() -> api.findAccount(policy)).orElseThrow().productId();
        asTenant(() -> api.recordMaturityInstruction(policy, MaturityAction.REINVEST, 6, null, "staff-one", key()));
        // New rates published AFTER the first term started; the reinvestment must use them.
        UUID newVersion = fixtures.publishDepositVersion(TENANT, productId, AccumulationTestFixtures.grid(new String[][] {
            {"500000", "9", "10", "11"}, {"6000000", "4", "5", "6"}, {"11000000", "5", "6", "7"}, {"21000000", "6", "7", "8"}}));
        runMaturity(policy);

        DepositView d = deposit(policy);
        assertThat(d.periods()).hasSize(2);
        assertThat(d.periods().get(0).status()).isEqualTo(DepositPeriodStatus.MATURED);
        DepositPeriodView second = d.periods().get(1);
        assertThat(second.principal()).isEqualByComparingTo("1030000.00"); // the deposit plus its interest (D6)
        assertThat(second.termMonths()).isEqualTo(6);
        assertThat(second.ratePercent()).isEqualByComparingTo("10");
        assertThat(second.rateVersionId()).isEqualTo(newVersion);
        assertThat(second.startDate()).isEqualTo(d.periods().get(0).maturityDate());
        assertThat(asTenant(() -> policyApi.getPolicy(policy)).maturityDate()).isEqualTo(second.maturityDate());
        assertThat(asTenant(() -> policyApi.getPolicy(policy)).status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void aReinvestmentForATermNoLongerOfferedIsPaidOutAndSaysWhy() {
        String policy = maturedYesterday("+255700000777");
        UUID productId = asTenant(() -> api.findAccount(policy)).orElseThrow().productId();
        asTenant(() -> api.recordMaturityInstruction(policy, MaturityAction.REINVEST, 12, null, "staff-one", key()));
        fixtures.publishDepositVersion(TENANT, productId, new DepositPlan(List.of(
            new DepositRateRow(new BigDecimal("500000"), 3, new BigDecimal("3")))));
        runMaturity(policy);
        List<LedgerEntryView> all = entriesOf(policy);
        assertThat(all.get(all.size() - 1).type()).isEqualTo(EntryType.MATURITY);
        assertThat(all.get(all.size() - 1).reason()).contains("no longer offered");
    }

    @Test
    void withNoNumberTheMoneyWaitsForStaffToRecordAPayee() {
        String policy = maturedYesterday(null);
        runMaturity(policy);
        DepositView waiting = deposit(policy);
        assertThat(waiting.awaitingPayee()).isTrue();
        assertThat(asTenant(() -> api.findAccount(policy)).orElseThrow().balance()).isEqualByComparingTo("1030000.00");
        assertThat(asTenant(() -> api.listAwaitingPayee())).extracting(AwaitingPayeeView::policyNumber).contains(policy);

        asTenant(() -> api.payOutMaturedDeposit(policy, "+255700000888", "finance-one", key()));
        assertThat(deposit(policy).awaitingPayee()).isFalse();
        assertThat(disbursementFor(policy).get("payee_ref")).isEqualTo("+255700000888");
        assertThat(asTenant(() -> policyApi.getPolicy(policy)).status()).isEqualTo(PolicyStatus.MATURED);
    }

    @Test
    void aFailedMaturityPaymentPutsTheMoneyBackAndWaitsForAPayee() {
        forceTheGatewayToDecline();
        String policy = maturedYesterday("+255700000777");
        runMaturity(policy);
        List<LedgerEntryView> all = entriesOf(policy);
        assertThat(all.get(all.size() - 1).type()).isEqualTo(EntryType.REVERSAL);
        assertThat(deposit(policy).awaitingPayee()).isTrue();
        // The second attempt has its own reference, so the ledger's once-only index lets it through.
        gatewayAccepts();
        asTenant(() -> api.payOutMaturedDeposit(policy, "+255700000999", "finance-one", key()));
        assertThat(disbursementFor(policy).get("source_ref")).asString().endsWith(":2");
    }

    @Test
    void anInstructionCanBeChangedUntilMaturityAndTheHistoryIsKept() {
        String policy = paidDeposit(MILLION, 3, LocalDate.now(), "+255700000777");
        asTenant(() -> api.recordMaturityInstruction(policy, MaturityAction.REINVEST, 6, null, "staff-one", key()));
        asTenant(() -> api.recordMaturityInstruction(policy, MaturityAction.PAY_OUT, null, "+255700000123", "staff-two", key()));
        assertThat(deposit(policy).instruction().action()).isEqualTo(MaturityAction.PAY_OUT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accumulation.maturity_instruction WHERE policy_number = ?",
            Integer.class, policy)).isEqualTo(2);
        assertThatThrownBy(() -> asTenant(() -> api.recordMaturityInstruction(policy, MaturityAction.REINVEST, 9, null, "s", key())))
            .hasMessageContaining("offers terms of [3, 6, 12] months");
    }

    @Test
    void aDepositHasAScheduledMaturitySoNoMaturityClaimCanBeFiled() {
        String policy = paidDeposit(MILLION, 3, LocalDate.now(), "+255700000777");
        assertThat(asTenant(() -> benefitPayoutApi.hasScheduledMaturity(policy))).isTrue();
    }

    private static String key() { return UUID.randomUUID().toString(); }
```

`gatewayAccepts()` is the copied `@BeforeEach` method; calling it directly re-stubs the gateway. Imports: `DepositPlan`, `DepositRateRow` (product.api), `Deposits`.

Run: `./mvnw -B -o test -Dtest=DepositLifecycleIntegrationTest`. Expected: COMPILATION ERROR (`mature`, `recordMaturityInstruction` and others are undefined).

- [ ] **Step 2: Maturity in Deposits**

Add to `Deposits.java`:

```java
    // ---- The client's instruction (D7) ---------------------------------------------------------

    /** Any time before maturity, and changeable until then: a change supersedes, never edits. */
    @Transactional
    public MaturityInstructionView instruct(String policyNumber, MaturityAction action, Integer termMonths, String payeeRef,
                                            String by) {
        Account account = accounts.findById(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber));
        if (!isDepositVersion(account.getProductVersionId())) {
            throw new AccumulationStateException("Policy " + policyNumber + " is not a fixed-term deposit");
        }
        DepositPeriod period = running(policyNumber).orElseThrow(() -> new AccumulationStateException(
            "Policy " + policyNumber + " has no running deposit term to instruct"));
        if (action == MaturityAction.REINVEST) {
            List<Integer> offered = termsOfferedToday(account.getProductId());
            if (termMonths == null || !offered.contains(termMonths)) {
                throw new AccumulationStateException("This product now offers terms of " + offered + " months, not " + termMonths);
            }
        } else if (termMonths != null) {
            throw new AccumulationStateException("A pay-out instruction takes no term");
        }
        instructions.findByPeriodIdAndSupersededAtIsNull(period.getPeriodId()).ifPresent(previous -> {
            previous.supersede();
            instructions.saveAndFlush(previous); // free ux_maturity_instruction_current before the new row
        });
        MaturityInstruction saved = instructions.save(new MaturityInstruction(TenantContext.get(), policyNumber,
            period.getPeriodId(), action, action == MaturityAction.REINVEST ? termMonths : null,
            action == MaturityAction.PAY_OUT ? blankToNull(payeeRef) : null, by));
        return view(saved);
    }

    @Transactional(readOnly = true)
    public MaturityInstructionView instructionView(UUID instructionId) {
        return view(instructions.findById(instructionId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public DepositPeriodView periodView(UUID periodId) {
        return view(periods.findById(periodId).orElseThrow());
    }

    // ---- Maturity (spec §4.3) ------------------------------------------------------------------

    /**
     * One deposit's maturity, in one transaction: the term's interest (once, keyed on the period),
     * then the instruction. Idempotent: a term that no longer RUNS is left alone.
     */
    @Transactional
    public void mature(String policyNumber, LocalDate today) {
        Account account = accounts.lockForPosting(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber));
        DepositPeriod period = running(policyNumber).orElse(null);
        if (period == null || period.getMaturityDate().isAfter(today) || account.status() != AccountStatus.OPEN) {
            return;
        }
        LocalDate on = period.getMaturityDate();
        BigDecimal interest = period.fullInterest();
        ledger.post(policyNumber, new LedgerService.Source("deposit-interest", "deposit-interest:" + period.getPeriodId()),
            List.of(LedgerService.Line.of(EntryType.INTEREST, interest, on,
                "Interest for the term, " + period.getRatePercent().stripTrailingZeros().toPlainString() + "%")),
            "system", null);
        period.end(DepositPeriodStatus.MATURED, interest, on);
        periods.saveAndFlush(period); // free ux_deposit_period_running before a reinvested term is written

        MaturityInstruction instruction = instructions.findByPeriodIdAndSupersededAtIsNull(period.getPeriodId()).orElse(null);
        String note = null;
        if (instruction != null && instruction.action() == MaturityAction.REINVEST) {
            Optional<DepositPeriod> next = reinvest(account, period, instruction.getTermMonths(), on, today);
            if (next.isPresent()) {
                matured(policyNumber, period, "REINVESTED", interest, next.get().getPeriodId());
                return;
            }
            note = "Reinvestment for " + instruction.getTermMonths() + " months is no longer offered; paid out instead";
        }
        String payee = instruction != null && instruction.getPayeeRef() != null ? instruction.getPayeeRef()
            : period.getDefaultPayeeRef();
        if (payee == null) {
            log.warn("Fixed-term deposit {} matured with no number to pay it to; {} waits on the account for a payee",
                policyNumber, account.getBalance());
            matured(policyNumber, period, "AWAITING_PAYEE", interest, null);
            return;
        }
        payOut(account, period, payee, note, on, "system");
        matured(policyNumber, period, "PAID_OUT", interest, null);
    }

    /** D6, D8: the whole balance, for the instructed term, at the version active for new business today. */
    private Optional<DepositPeriod> reinvest(Account account, DepositPeriod ended, int term, LocalDate on, LocalDate today) {
        UUID active;
        try {
            active = productApi.getActiveSnapshot(account.getProductId(), today).productVersionId();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        BigDecimal balance = account.getBalance();
        Optional<BigDecimal> rate = productApi.resolveDepositPlan(active).rateFor(balance, term);
        if (rate.isEmpty()) {
            return Optional.empty();
        }
        DepositPeriod next = periods.save(new DepositPeriod(TenantContext.get(), account.getPolicyNumber(), ended.getSeq() + 1,
            balance, term, rate.get(), active, on, ended.getDefaultPayeeRef()));
        policyApi.restateMaturityDate(account.getPolicyNumber(), next.getMaturityDate());
        return Optional.of(next);
    }

    /**
     * The whole balance out, closing the account, then the payment requested through payment. The
     * attempt number makes each try its own source reference (§R7).
     */
    private void payOut(Account account, DepositPeriod period, String payee, String note, LocalDate on, String by) {
        int attempt = period.recordPayoutAttempt();
        periods.save(period);
        String ref = period.getPeriodId() + ":" + attempt;
        BigDecimal value = account.getBalance();
        ledger.post(account.getPolicyNumber(), new LedgerService.Source("deposit-maturity", "deposit-maturity:" + ref),
            List.of(LedgerService.Line.of(EntryType.MATURITY, value.negate(), on,
                note != null ? note : "Matured; paid to " + payee)), by, null);
        account.close("MATURED", on);
        accounts.save(account);
        events.publishEvent(tz.co.nlolo.lifeplatform.DomainEventEnvelope.of("accumulation.PayoutRequested", TenantContext.get(),
            java.util.Map.of(
                "purpose", "DEPOSIT_MATURITY_PAYOUT",
                "sourceRef", ref,
                "idempotencyKey", "deposit-maturity:" + ref,
                "policyNumber", account.getPolicyNumber(),
                "payeeRef", payee,
                "amount", java.util.Map.of("amount", value.toPlainString(), "currencyCode", account.getCurrency()))));
        policyApi.markMatured(account.getPolicyNumber(), "system:accumulation");
    }

    private void matured(String policyNumber, DepositPeriod period, String outcome, BigDecimal interest, UUID nextPeriodId) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("policyNumber", policyNumber);
        payload.put("periodId", period.getPeriodId().toString());
        payload.put("outcome", outcome);
        payload.put("interest", interest.toPlainString());
        if (nextPeriodId != null) payload.put("nextPeriodId", nextPeriodId.toString());
        events.publishEvent(tz.co.nlolo.lifeplatform.DomainEventEnvelope.of("accumulation.DepositMatured", TenantContext.get(),
            payload));
    }

    /** Finance records the number a waiting deposit is paid to (§R5). */
    @Transactional
    public DepositPeriodView payOutAwaiting(String policyNumber, String payeeRef, String by) {
        if (payeeRef == null || payeeRef.isBlank()) {
            throw new AccumulationStateException("A payout needs a payee reference");
        }
        Account account = accounts.lockForPosting(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber));
        List<DepositPeriod> all = periods.findByPolicyNumberOrderBySeq(policyNumber);
        if (!awaitingPayee(account, all)) {
            throw new AccumulationStateException("Policy " + policyNumber + " has no matured deposit waiting for a payee");
        }
        DepositPeriod period = all.get(all.size() - 1);
        payOut(account, period, payeeRef, "Matured; paid to " + payeeRef + " (payee recorded by staff)", LocalDate.now(), by);
        return view(period);
    }

    /**
     * {@code payment.DisbursementFailed} for a deposit's maturity: the MATURITY entry is reversed,
     * never edited, and the account reopens to wait for a payee. A completed payment needs nothing:
     * the account closed and the policy matured when it was requested.
     */
    @Transactional
    public void settlePayout(String sourceRef, boolean paid) {
        if (paid) {
            return;
        }
        var posting = postings.findByTenantIdAndSourceTypeAndSourceRef(TenantContext.get(), "deposit-maturity",
            "deposit-maturity:" + sourceRef).orElse(null);
        if (posting == null) {
            return;
        }
        var original = entries.findByPostingIdOrderBySeq(posting.getPostingId()).get(0);
        if (postings.findByTenantIdAndSourceTypeAndSourceRef(TenantContext.get(), "reversal",
                "reversal:" + original.getEntryId()).isPresent()) {
            return; // a redelivery
        }
        Account account = accounts.lockForPosting(original.getPolicyNumber()).orElseThrow();
        ledger.post(account.getPolicyNumber(), new LedgerService.Source("reversal", "reversal:" + original.getEntryId()),
            List.of(new LedgerService.Line(EntryType.REVERSAL, original.getAmount().negate(), LocalDate.now(),
                "Maturity payment failed; waiting for a payee", original.getEntryId())), "system", null);
        account.reopenAwaitingPayee();
        accounts.save(account);
        log.warn("The maturity payment of deposit {} failed; the money is back on the account and waits for a payee",
            account.getPolicyNumber());
    }

    @Transactional(readOnly = true)
    public List<AwaitingPayeeView> awaiting() {
        return periods.findByStatusOrderByClosedOn(DepositPeriodStatus.MATURED.name()).stream()
            .map(DepositPeriod::getPolicyNumber).distinct()
            .map(n -> accounts.findById(n).orElse(null))
            .filter(a -> a != null && awaitingPayee(a, periods.findByPolicyNumberOrderBySeq(a.getPolicyNumber())))
            .map(a -> {
                List<DepositPeriod> all = periods.findByPolicyNumberOrderBySeq(a.getPolicyNumber());
                return new AwaitingPayeeView(a.getPolicyNumber(), a.getBalance(), a.getCurrency(),
                    all.get(all.size() - 1).getClosedOn());
            }).toList();
    }
```

- [ ] **Step 3: The API surface, the drain and the listener**

`AccumulationApi.java`, in the deposit section:

```java
    /** The client's choice for the end of the running term; superseding any earlier one. Once per key. */
    MaturityInstructionView recordMaturityInstruction(String policyNumber, MaturityAction action, Integer termMonths,
                                                      String payeeRef, String recordedBy, String idempotencyKey);

    /** Finance pays a matured deposit that had no number to go to. Once per key. */
    DepositPeriodView payOutMaturedDeposit(String policyNumber, String payeeRef, String requestedBy, String idempotencyKey);

    List<AwaitingPayeeView> listAwaitingPayee();
```

`AccumulationApiImpl.java`, next to the other keyed overloads:

```java
    @Override
    public MaturityInstructionView recordMaturityInstruction(String policyNumber, MaturityAction action, Integer termMonths,
                                                             String payeeRef, String recordedBy, String idempotencyKey) {
        return keyed.once(idempotencyKey, "MATURITY_INSTRUCTION", policyNumber, recordedBy,
            () -> deposits.instruct(policyNumber, action, termMonths, payeeRef, recordedBy),
            MaturityInstructionView::instructionId, deposits::instructionView);
    }

    @Override
    public DepositPeriodView payOutMaturedDeposit(String policyNumber, String payeeRef, String requestedBy,
                                                  String idempotencyKey) {
        return keyed.once(idempotencyKey, "DEPOSIT_PAYOUT", policyNumber, requestedBy,
            () -> deposits.payOutAwaiting(policyNumber, payeeRef, requestedBy),
            DepositPeriodView::periodId, deposits::periodView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AwaitingPayeeView> listAwaitingPayee() {
        return deposits.awaiting();
    }
```

`accumulation/application/DepositMaturityDrain.java`:

```java
package tz.co.nlolo.lifeplatform.accumulation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.DepositPeriodRepository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Matures every deposit term whose date has come, across tenants, each under its own -- MonthEndDrain's
 * shape and reasoning: maturity publishes events (the payout, the policy's matured), so it is Java,
 * and only the selection is SQL ({@code deposits_due()}). Hourly by default; the {@code local}
 * profile runs it every ten seconds for the e2e suite.
 */
@Component
public class DepositMaturityDrain {

    private static final Logger log = LoggerFactory.getLogger(DepositMaturityDrain.class);

    private final DepositPeriodRepository periods;
    private final Deposits deposits;

    public DepositMaturityDrain(DepositPeriodRepository periods, Deposits deposits) {
        this.periods = periods;
        this.deposits = deposits;
    }

    @Scheduled(fixedDelayString = "${accumulation.deposit-maturity-interval-ms:3600000}",
        initialDelayString = "${accumulation.deposit-maturity-interval-ms:3600000}")
    public void drain() {
        for (Object[] row : periods.findDueAcrossTenants()) {
            matureOne((String) row[0], (UUID) row[1]);
        }
    }

    void matureOne(String policyNumber, UUID tenantId) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            deposits.mature(policyNumber, LocalDate.now());
        } catch (Exception e) {
            log.error("Deposit maturity failed for policy {} in tenant {}", policyNumber, tenantId, e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
```

`src/main/resources/application-local.yml`, under `accumulation:` next to `month-end-interval-ms: 10000`:

```yaml
  deposit-maturity-interval-ms: 10000
```

`accumulation/application/PaymentEventListener.java`:
1. Inject `Deposits deposits` (constructor).
2. Widen the disbursement case:

```java
            case "payment.DisbursementCompleted", "payment.DisbursementFailed" -> runner.run(envelope, p -> {
                boolean paid = "payment.DisbursementCompleted".equals(envelope.eventType());
                if ("DEPOSIT_MATURITY_PAYOUT".equals(p.get("purpose"))) {
                    deposits.settlePayout((String) p.get("sourceRef"), paid);
                    return;
                }
                if (!"WITHDRAWAL_PAYOUT".equals(p.get("purpose"))) return;
                api.settleWithdrawal(UUID.fromString((String) p.get("sourceRef")), (UUID) p.get("disbursementId"), paid);
            });
```

`benefitpayout/application/BenefitPayoutApiImpl.hasScheduledMaturity`:

```java
    public boolean hasScheduledMaturity(String policyNumber) {
        // A fixed-term deposit matures through accumulation's own run, which pays or reinvests it --
        // so a hand-filed maturity claim must be refused for it too (§R8).
        return instalments.existsByPolicyNumberAndKindIn(policyNumber,
            List.of(PayoutKind.MATURITY.name(), PayoutKind.RETURN_OF_PREMIUM.name()))
            || accumulationApi.isDeposit(policyNumber);
    }
```

- [ ] **Step 4: Run Task 5's tests**

Run: `./mvnw -B -o test-compile`, then `./mvnw -B -o test -Dtest=DepositLifecycleIntegrationTest+WithdrawalIntegrationTest`
Expected: PASS. `WithdrawalIntegrationTest` proves the widened payment listener still settles withdrawals.

If `aFailedMaturityPaymentPutsTheMoneyBackAndWaitsForAPayee` sees no REVERSAL, payment may publish `DisbursementFailed` asynchronously. Wrap the assertions in `org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(...)` only if `ClosingIntegrationTest` already does so for a declined payment. Otherwise the failure is real: debug it before going further.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test
git commit -m "feat(accumulation): deposit maturity -- reinvest at the rate then in force, pay out to the collection number, or wait for a payee"
```

---

### Task 6: REST, the specs, and the contract

**Files:**
- Create `accumulation/infrastructure/DepositResponse.java` and `DepositBodies.java`
- Modify `AccumulationController.java`, `openapi-accumulation.yaml`, `asyncapi-events.yaml`
- Modify `AccumulationContractTest.java` and `LedgerImmutabilityTest.java`

**Interfaces:**
- Produces:
  - `GET /policies/{n}/account/deposit` (any staff member; 404 when the policy is not a deposit)
  - `POST /policies/{n}/account/deposit/maturity-instruction`, with body `{action, termMonths?, payeeRef?}` (any staff member, keyed, 201)
  - `GET /deposits/awaiting-payee` (FINANCE)
  - `POST /policies/{n}/account/deposit/payout`, with body `{payeeRef}` (FINANCE, keyed, 202)

- [ ] **Step 1: The failing contract tests**

In `AccumulationContractTest.java`, add these to the migration list:
- `"db-migrations/accumulation/V3__deposit_periods.sql"`
- `"db-migrations/payment/V10__deposit_maturity_purpose.sql"`, if payment migrations are listed
- product V20, if the script missed it

Then add:

```java
    private String depositPolicy() {
        var issued = fixtures.issueDeposit(TENANT, new BigDecimal("1000000.00"), 3, LocalDate.now());
        fixtures.collectDeposit(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("1000000.00"),
            LocalDate.now(), "+255700000777");
        return issued.policyNumber();
    }

    @Test
    void aDepositIsReadToSpec() throws Exception {
        String policy = depositPolicy();
        mockMvc.perform(get("/policies/" + policy + "/account/deposit").with(staff("UNDERWRITER", "uw")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.periods[0].termMonths").value(3))
            .andExpect(jsonPath("$.periods[0].principal.amount").value("1000000.00"))
            .andExpect(jsonPath("$.periods[0].status").value("RUNNING"))
            .andExpect(jsonPath("$.defaultPayeeRef").value("+255700000777"))
            .andExpect(jsonPath("$.termsOffered[2]").value(12))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void aSavingsAccountIsNotADeposit() throws Exception {
        String policy = fundedAccount();
        mockMvc.perform(get("/policies/" + policy + "/account/deposit").with(staff("UNDERWRITER", "uw")))
            .andExpect(status().isNotFound());
    }

    @Test
    void theSameInstructionSentTwiceIsRecordedOnce() throws Exception {
        String policy = depositPolicy();
        String key = key();
        String body = "{\"action\":\"REINVEST\",\"termMonths\":6}";
        String first = mockMvc.perform(post("/policies/" + policy + "/account/deposit/maturity-instruction")
                .header("Idempotency-Key", key).with(staff("UNDERWRITER", "uw")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/policies/" + policy + "/account/deposit/maturity-instruction")
                .header("Idempotency-Key", key).with(staff("UNDERWRITER", "uw")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(second, "$.instructionId")).isEqualTo(JsonPath.read(first, "$.instructionId"));
    }

    @Test
    void aTopUpOnADepositIsA422InTheServersWords() throws Exception {
        String policy = depositPolicy();
        mockMvc.perform(post("/policies/" + policy + "/account/top-ups").header("Idempotency-Key", key())
                .with(staff("UNDERWRITER", "uw")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"1000.00\",\"payerRef\":\"+255700000002\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("This is a fixed-term deposit")));
    }

    @Test
    void onlyFinanceSeesTheAwaitingListOrPaysIt() throws Exception {
        mockMvc.perform(get("/deposits/awaiting-payee").with(staff("UNDERWRITER", "uw"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/deposits/awaiting-payee").with(staff("FINANCE_OFFICER", "fin")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
        mockMvc.perform(post("/policies/POL-NOPE01/account/deposit/payout").header("Idempotency-Key", key())
                .with(staff("UNDERWRITER", "uw")).contentType(MediaType.APPLICATION_JSON).content("{\"payeeRef\":\"+2557\"}"))
            .andExpect(status().isForbidden());
    }
```

The `$.detail` path assumes the exception handler's ProblemDetail field. Check `aMalformedAmountIsA400ThatNamesTheField` or `theProposerApprovingIsA422InTheServersWords` in the same class for the path they read, and use it.

In `LedgerImmutabilityTest.migrate()`, add `"db-migrations/accumulation/V3__deposit_periods.sql"` after V2. Its catalogue test then checks both new tables for RLS, and the FK to `account` resolves because V1 is applied. Add:

```java
    @Test
    void aDepositTermsRateCannotBeRewrittenEvenByTheOwner() throws Exception {
        try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO accumulation.account (policy_number, tenant_id, product_id, product_version_id, "
                + "policyholder_party_id, opened_on) VALUES ('POL-DEP01', '" + TENANT + "', gen_random_uuid(), gen_random_uuid(), "
                + "gen_random_uuid(), current_date)");
            s.execute("INSERT INTO accumulation.deposit_period (period_id, tenant_id, policy_number, seq, principal, term_months, "
                + "rate_percent, rate_version_id, start_date, maturity_date) VALUES ('00000000-0000-0000-0000-0000000000d1', '"
                + TENANT + "', 'POL-DEP01', 1, 1000000, 3, 3, gen_random_uuid(), current_date, current_date + 90)");
        }
        assertThatThrownBy(() -> { try (Connection c = owner(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE accumulation.deposit_period SET rate_percent = 9 WHERE policy_number = 'POL-DEP01'"); } })
            .hasMessageContaining("is the record of its rate");
        assertThatCode(() -> { try (Connection c = owner(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE accumulation.deposit_period SET status = 'MATURED', closed_on = current_date, "
                + "interest_posted = 30000 WHERE policy_number = 'POL-DEP01'"); } })
            .doesNotThrowAnyException();
    }
```

Run: `./mvnw -B -o test -Dtest=AccumulationContractTest+LedgerImmutabilityTest`. Expected: FAIL. The deposit endpoints 404 (no handler), and the trigger test may already pass. Note in the commit which tests failed before the code existed: per memory, a guard test that is green before its code exists proves nothing.

- [ ] **Step 2: Bodies, responses, endpoints**

`accumulation/infrastructure/DepositBodies.java`:

```java
package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tz.co.nlolo.lifeplatform.accumulation.api.MaturityAction;

final class DepositBodies {
    private DepositBodies() {}

    record Instruction(@NotNull MaturityAction action, Integer termMonths, @Size(max = 200) String payeeRef) {}

    record Payout(@NotBlank @Size(max = 200) String payeeRef) {}
}
```

`accumulation/infrastructure/DepositResponse.java`:

```java
package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import tz.co.nlolo.lifeplatform.accumulation.api.*;

import java.util.List;

/** A fixed-term deposit, its terms oldest first, and what happens at the end of the running one. */
public record DepositResponse(String policyNumber, List<Period> periods, Instruction instruction, MoneyResponse interestSoFar,
                              String defaultPayeeRef, boolean awaitingPayee, List<Integer> termsOffered) {

    public record Period(String periodId, int seq, MoneyResponse principal, int termMonths, java.math.BigDecimal ratePercent,
                         String rateVersionId, String startDate, String maturityDate, String status,
                         MoneyResponse interestPosted, String closedOn) {
        static Period from(DepositPeriodView p, String currency) {
            // NUMERIC(7,4) reads back as 3.0000; the console shows "3% for the term", so trim the scale
            // (via toPlainString, so 10 does not become 1E+1).
            return new Period(p.periodId().toString(), p.seq(), MoneyResponse.of(p.principal(), currency), p.termMonths(),
                new java.math.BigDecimal(p.ratePercent().stripTrailingZeros().toPlainString()), p.rateVersionId().toString(), p.startDate().toString(), p.maturityDate().toString(),
                p.status().name(), p.interestPosted() != null ? MoneyResponse.of(p.interestPosted(), currency) : null,
                p.closedOn() != null ? p.closedOn().toString() : null);
        }
    }

    public record Instruction(String instructionId, String periodId, String action, Integer termMonths, String payeeRef,
                              String recordedBy, String recordedAt) {
        static Instruction from(MaturityInstructionView i) {
            return new Instruction(i.instructionId().toString(), i.periodId().toString(), i.action().name(), i.termMonths(),
                i.payeeRef(), i.recordedBy(), i.recordedAt().toString());
        }
    }

    public record Awaiting(String policyNumber, MoneyResponse balance, String maturedOn) {
        static Awaiting from(AwaitingPayeeView a) {
            return new Awaiting(a.policyNumber(), MoneyResponse.of(a.balance(), a.currency()), a.maturedOn().toString());
        }
    }

    static DepositResponse from(DepositView d) {
        return new DepositResponse(d.policyNumber(), d.periods().stream().map(p -> Period.from(p, d.currency())).toList(),
            d.instruction() != null ? Instruction.from(d.instruction()) : null, MoneyResponse.of(d.interestSoFar(), d.currency()),
            d.defaultPayeeRef(), d.awaitingPayee(), d.termsOffered());
    }
}
```

`AccumulationController.java`, a new section:

```java
    // ---- Fixed-term deposits (2026-10-02) ------------------------------------------------------

    @GetMapping("/policies/{policyNumber}/account/deposit")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public DepositResponse deposit(@PathVariable String policyNumber) {
        return DepositResponse.from(api.findDeposit(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber)));
    }

    /** The client's choice, taken by any staff member as a withdrawal request is. Changeable until maturity. */
    @PostMapping("/policies/{policyNumber}/account/deposit/maturity-instruction")
    @PreAuthorize("hasRole('REALM_STAFF')")
    @ResponseStatus(HttpStatus.CREATED)
    public DepositResponse.Instruction instruct(@PathVariable String policyNumber, @Valid @RequestBody DepositBodies.Instruction body,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                @AuthenticationPrincipal Jwt jwt) {
        return DepositResponse.Instruction.from(api.recordMaturityInstruction(policyNumber, body.action(), body.termMonths(),
            body.payeeRef(), jwt.getSubject(), idempotencyKey));
    }

    @GetMapping("/deposits/awaiting-payee")
    @PreAuthorize(FINANCE)
    public List<DepositResponse.Awaiting> awaitingPayee() {
        return api.listAwaitingPayee().stream().map(DepositResponse.Awaiting::from).toList();
    }

    /** 202: REQUESTED from the rail, not paid. */
    @PostMapping("/policies/{policyNumber}/account/deposit/payout")
    @PreAuthorize(FINANCE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DepositResponse.Period payOut(@PathVariable String policyNumber, @Valid @RequestBody DepositBodies.Payout body,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                         @AuthenticationPrincipal Jwt jwt) {
        DepositPeriodView paid = api.payOutMaturedDeposit(policyNumber, body.payeeRef(), jwt.getSubject(), idempotencyKey);
        return DepositResponse.Period.from(paid, api.findAccount(policyNumber).orElseThrow().currency());
    }
```

Import `tz.co.nlolo.lifeplatform.accumulation.api.DepositPeriodView`. If `AccountView` has no `currency()` accessor, use the existing accessor `AccountResponse.from` reads it with (`a.currency()` is used there).

- [ ] **Step 3: openapi-accumulation.yaml**

Paths, before `components:` (`staffAuth`, `PolicyNumberRef`, `Money`, the responses and the Idempotency-Key parameter all follow the existing withdrawal path exactly):

```yaml
  /policies/{policyNumber}/account/deposit:
    get:
      summary: A fixed-term deposit -- its terms, interest so far, and what happens at maturity
      security:
        - staffAuth: []
      parameters:
        - { name: policyNumber, in: path, required: true, schema: { $ref: 'openapi-common.yaml#/components/schemas/PolicyNumberRef' } }
      responses:
        '200':
          description: The deposit
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Deposit' }
        '403': { $ref: 'openapi-common.yaml#/components/responses/Forbidden' }
        '404': { $ref: 'openapi-common.yaml#/components/responses/NotFound' }
  /policies/{policyNumber}/account/deposit/maturity-instruction:
    post:
      summary: Record the client's choice for the end of the running term
      description: >
        Any staff member. REINVEST takes a term offered by the version active for new business; PAY_OUT
        takes an optional payee (blank pays to the number the deposit was collected from). A new
        instruction supersedes the last; with none, the deposit is paid out.
      security:
        - staffAuth: []
      parameters:
        - { name: policyNumber, in: path, required: true, schema: { $ref: 'openapi-common.yaml#/components/schemas/PolicyNumberRef' } }
        - { name: Idempotency-Key, in: header, required: true, schema: { type: string, maxLength: 200 }, description: "The same key is the same request: a retry is answered with what the first one created, and creates nothing. Reused for a different request, it is refused (422)." }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/MaturityInstructionRequest' }
      responses:
        '201':
          description: Recorded
          content:
            application/json:
              schema: { $ref: '#/components/schemas/MaturityInstruction' }
        '400': { $ref: 'openapi-common.yaml#/components/responses/BadRequest' }
        '403': { $ref: 'openapi-common.yaml#/components/responses/Forbidden' }
        '404': { $ref: 'openapi-common.yaml#/components/responses/NotFound' }
        '422':
          description: Refused -- no running term, or a term the product does not offer
          content:
            application/problem+json:
              schema: { $ref: 'openapi-common.yaml#/components/schemas/ProblemDetails' }
  /deposits/awaiting-payee:
    get:
      summary: Matured deposits whose money waits for a payee
      security:
        - staffAuth: []
      responses:
        '200':
          description: The waiting deposits, longest-waiting first
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/AwaitingPayee' } }
        '403': { $ref: 'openapi-common.yaml#/components/responses/Forbidden' }
  /policies/{policyNumber}/account/deposit/payout:
    post:
      summary: Pay a matured deposit to a payee finance records
      security:
        - staffAuth: []
      parameters:
        - { name: policyNumber, in: path, required: true, schema: { $ref: 'openapi-common.yaml#/components/schemas/PolicyNumberRef' } }
        - { name: Idempotency-Key, in: header, required: true, schema: { type: string, maxLength: 200 }, description: "The same key is the same request: a retry is answered with what the first one created, and creates nothing. Reused for a different request, it is refused (422)." }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/DepositPayoutRequest' }
      responses:
        '202':
          description: Requested from the payment rail, not yet paid
          content:
            application/json:
              schema: { $ref: '#/components/schemas/DepositPeriod' }
        '400': { $ref: 'openapi-common.yaml#/components/responses/BadRequest' }
        '403': { $ref: 'openapi-common.yaml#/components/responses/Forbidden' }
        '404': { $ref: 'openapi-common.yaml#/components/responses/NotFound' }
        '422':
          description: Refused -- nothing is waiting for a payee on this policy
          content:
            application/problem+json:
              schema: { $ref: 'openapi-common.yaml#/components/schemas/ProblemDetails' }
```

Schemas, under `components.schemas`:

```yaml
    DepositPeriod:
      type: object
      required: [periodId, seq, principal, termMonths, ratePercent, rateVersionId, startDate, maturityDate, status]
      properties:
        periodId: { type: string, format: uuid }
        seq: { type: integer }
        principal: { $ref: 'openapi-common.yaml#/components/schemas/Money' }
        termMonths: { type: integer }
        ratePercent: { type: number, description: "For the term, not a year" }
        rateVersionId: { type: string, format: uuid }
        startDate: { type: string, format: date }
        maturityDate: { type: string, format: date }
        status: { type: string, enum: [RUNNING, MATURED, TERMINATED, CANCELLED] }
        interestPosted: { oneOf: [ { $ref: 'openapi-common.yaml#/components/schemas/Money' }, { type: "null" } ] }
        closedOn: { type: [string, "null"], format: date }
    MaturityInstruction:
      type: object
      required: [instructionId, periodId, action, recordedBy, recordedAt]
      properties:
        instructionId: { type: string, format: uuid }
        periodId: { type: string, format: uuid }
        action: { type: string, enum: [REINVEST, PAY_OUT] }
        termMonths: { type: [integer, "null"] }
        payeeRef: { type: [string, "null"] }
        recordedBy: { type: string }
        recordedAt: { type: string, format: date-time }
    MaturityInstructionRequest:
      type: object
      required: [action]
      properties:
        action: { type: string, enum: [REINVEST, PAY_OUT] }
        termMonths: { type: integer, minimum: 1, maximum: 120 }
        payeeRef: { type: string, maxLength: 200 }
    DepositPayoutRequest:
      type: object
      required: [payeeRef]
      properties:
        payeeRef: { type: string, maxLength: 200 }
    Deposit:
      type: object
      required: [policyNumber, periods, interestSoFar, awaitingPayee, termsOffered]
      properties:
        policyNumber: { $ref: 'openapi-common.yaml#/components/schemas/PolicyNumberRef' }
        periods: { type: array, items: { $ref: '#/components/schemas/DepositPeriod' } }
        instruction: { oneOf: [ { $ref: '#/components/schemas/MaturityInstruction' }, { type: "null" } ] }
        interestSoFar: { $ref: 'openapi-common.yaml#/components/schemas/Money' }
        defaultPayeeRef: { type: [string, "null"] }
        awaitingPayee: { type: boolean }
        termsOffered: { type: array, items: { type: integer } }
    AwaitingPayee:
      type: object
      required: [policyNumber, balance, maturedOn]
      properties:
        policyNumber: { $ref: 'openapi-common.yaml#/components/schemas/PolicyNumberRef' }
        balance: { $ref: 'openapi-common.yaml#/components/schemas/Money' }
        maturedOn: { type: string, format: date }
```

If the existing schemas write nullable references another way (look at `Withdrawal.approvedAt` and any nullable `$ref`), follow that form instead of `oneOf`. One malformed `$ref` fails the whole spec load (vacuous-verification memory).

- [ ] **Step 4: asyncapi-events.yaml**

Find the `payment.PaymentConfirmed`, `billing.PremiumCollected` and `accumulation.PayoutRequested` messages:
1. Add `payerRef: { type: string, description: "The number the money came from." }` to PaymentConfirmed.
2. Add the same, with "Absent for a field (cash) receipt", to PremiumCollected. Neither key is required.
3. Add `DEPOSIT_MATURITY_PAYOUT` to PayoutRequested's purpose enum, if it has one.
4. Add an `accumulation.DepositMatured` message beside `accumulation.StatementIssued`, copying its channel/message shape:
   - required: `policyNumber`, `periodId`, `outcome` (enum REINVESTED, PAID_OUT, AWAITING_PAYEE), `interest` (string decimal)
   - optional: `nextPeriodId` (uuid)

- [ ] **Step 5: Run the task's tests**

Run: `./mvnw -B -o test -Dtest=AccumulationContractTest+LedgerImmutabilityTest`, then the asyncapi check if the repo has one: `grep -rln "asyncapi-events.yaml" src/test`, and run those classes.
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/accumulation api src/test
git commit -m "feat(accumulation): deposit endpoints -- read, instruct, the awaiting list and finance's payout, once per key"
```

---

### Task 7: Console — the rate grid on the publish form

**Files:** `publishVersionSchema.ts`, `PublishVersionForm.tsx`, `publishVersionSchema.test.ts`, and the regenerated types.

- [ ] **Step 1: Regenerate the API types**

Run from `frontend/`: `npm run generate:api`, then check with `git diff --stat src/types/api`.
Expected: `product.ts` gains `DepositGrid` and `deposit`; `accumulation.ts` gains the deposit schemas. Because of openapi-typescript's `default:` trap, check that no new request field became required unexpectedly.

- [ ] **Step 2: The failing schema tests**

In `publishVersionSchema.test.ts`, use the file's existing helper that builds valid ENDOWMENT form values (find the one the ACCOUNT tests use, e.g. a `validAccountValues()` or similar), and add:

```ts
describe('a fixed-term deposit', () => {
  const grid = {
    valueBasis: 'DEPOSIT',
    depositTerms: [{ months: '3' }, { months: '6' }, { months: '12' }],
    depositBands: [
      { minAmount: '500000', rates: ['3', '4', '5'] },
      { minAmount: '6000000', rates: ['4', '5', '6'] },
      { minAmount: '11000000', rates: ['5', '6', '7'] },
      { minAmount: '21000000', rates: ['6', '7', '8'] },
    ],
    payoutRows: [],
    freeLookDays: '15',
  };

  it("accepts the user's grid with no payout schedule and sends it as rates", () => {
    const values = { ...validEndowmentValues(), ...grid };
    const result = publishVersionSchema('ENDOWMENT').safeParse(values);
    expect(result.success).toBe(true);
    const body = toPublishVersionBody(result.data!);
    expect(body.deposit?.rates).toHaveLength(12);
    expect(body.deposit?.rates).toContainEqual({ minAmount: 6000000, termMonths: 6, ratePercent: 5 });
    expect(body.accumulation).toBeUndefined();
  });

  it('names an empty cell by its band and term', () => {
    const bands = grid.depositBands.map((b, i) => (i === 1 ? { ...b, rates: ['4', '', '6'] } : b));
    const result = publishVersionSchema('ENDOWMENT').safeParse({ ...validEndowmentValues(), ...grid, depositBands: bands });
    expect(result.success).toBe(false);
    expect(result.error!.issues.map((i) => i.message)).toContain('The band from 6000000 does not offer a 6-month term');
  });

  it('refuses a payout schedule on a deposit', () => {
    const row = { kind: 'MATURITY', fromPolicyYear: '', toPolicyYear: '', amountBasis: 'PERCENT_OF_SA', amountValue: '100', frequency: '' };
    const result = publishVersionSchema('ENDOWMENT').safeParse({ ...validEndowmentValues(), ...grid, payoutRows: [row] });
    expect(result.error!.issues.map((i) => i.message)).toContain(
      'A fixed-term deposit matures through its account; it carries no payout schedule',
    );
  });

  it('refuses a frequency loading', () => {
    const result = publishVersionSchema('ENDOWMENT').safeParse({ ...validEndowmentValues(), ...grid, monthlyLoadingPercent: '5' });
    expect(result.error!.issues.map((i) => i.message)).toContain('A fixed-term deposit is paid once; it takes no frequency loading');
  });
});
```

Rename `validEndowmentValues`, `publishVersionSchema` and `toPublishVersionBody` to the names this file actually exports and uses; open the existing ACCOUNT tests and copy their call shapes. Then run `npx vitest run src/features/products/publishVersionSchema.test.ts` and expect FAIL.

- [ ] **Step 3: The schema**

In `publishVersionSchema.ts`:

1. Next to `accountChargeRowSchema`:

```ts
const depositTermSchema = z.object({ months: z.string().trim() });
const depositBandSchema = z.object({ minAmount: z.string().trim(), rates: z.array(z.string().trim()) });
export type DepositBandValues = z.infer<typeof depositBandSchema>;

export function blankDepositBand(termCount: number): DepositBandValues {
  return { minAmount: '', rates: Array.from({ length: termCount }, () => '') };
}
```

2. In the object schema, after `accountCharges`:

```ts
    // A fixed-term deposit (2026-10-02): rates by band x term, each for the TERM.
    depositTerms: z.array(depositTermSchema),
    depositBands: z.array(depositBandSchema),
```

3. In the defaults, after `accountCharges: []`: `depositTerms: [], depositBands: [],`.

4. In `superRefine`, after `validateAccumulation(...)`: `validateDeposit(category, values, ctx);`.

5. Add `valueBasis?: string;` to `interface PayoutFields`. In `validatePayoutPlan`, change the rows-empty branch's condition to:

```ts
    if (!SCHEDULED_CATEGORIES.includes(category) || v.valueBasis === 'DEPOSIT') {
```

6. The function, mirroring `DepositPlanValidator`:

```ts
interface DepositFields extends CashValueFields {
  valueBasis: string;
  depositTerms: { months: string }[];
  depositBands: DepositBandValues[];
  minSumAssured: string;
  monthlyLoadingPercent: string;
  quarterlyLoadingPercent: string;
  payoutRows: PayoutRowValues[];
}

/** `DepositPlanValidator`, rule for rule and message for message. Only a DEPOSIT version is checked. */
function validateDeposit(category: ProductCategory, v: DepositFields, ctx: z.RefinementCtx) {
  if (v.valueBasis !== 'DEPOSIT') return;
  const issue = (path: (string | number)[], message: string) => ctx.addIssue({ code: 'custom', path, message });
  if (!ACCOUNT_CATEGORIES.includes(category)) {
    issue(['valueBasis'], `A ${category} product cannot be a fixed-term deposit`);
    return;
  }
  if (hasCashValue(v)) issue(['valueBasis'], 'A version is valued either by a cash-value scale or as a fixed-term deposit, not both');
  if (Number(v.monthlyLoadingPercent || 0) > 0 || Number(v.quarterlyLoadingPercent || 0) > 0) {
    issue(['monthlyLoadingPercent'], 'A fixed-term deposit is paid once; it takes no frequency loading');
  }
  if (v.payoutRows.length > 0) issue(['payoutRows'], 'A fixed-term deposit matures through its account; it carries no payout schedule');
  if (v.depositTerms.length === 0 || v.depositBands.length === 0) {
    issue(['depositBands'], 'A fixed-term deposit needs at least one term and one band');
    return;
  }
  const terms = v.depositTerms.map((t) => t.months);
  terms.forEach((t, j) => {
    const n = Number(t);
    if (!Number.isInteger(n) || n < 1 || n > 120) issue(['depositTerms', j, 'months'], 'A deposit term must be between 1 and 120 months');
  });
  if (new Set(terms).size !== terms.length) issue(['depositTerms'], 'Each term may appear once');
  const starts = new Set<string>();
  v.depositBands.forEach((band, i) => {
    const min = Number(band.minAmount);
    if (!isNumber(band.minAmount) || min <= 0) issue(['depositBands', i, 'minAmount'], 'A deposit band must start above zero');
    if (starts.has(band.minAmount)) issue(['depositBands', i, 'minAmount'], 'Each band may start only once');
    starts.add(band.minAmount);
    terms.forEach((t, j) => {
      const rate = band.rates[j] ?? '';
      if (rate === '') {
        issue(['depositBands', i, 'rates', j], `The band from ${band.minAmount} does not offer a ${t}-month term`);
      } else if (!isNumber(rate) || Number(rate) < 0 || Number(rate) > 100) {
        issue(['depositBands', i, 'rates', j], 'A deposit rate must be between 0 and 100 percent');
      }
    });
  });
  const lowest = Math.min(...v.depositBands.map((b) => Number(b.minAmount)));
  if (v.minSumAssured !== '' && Number(v.minSumAssured) !== lowest) {
    issue(['depositBands'], `The lowest deposit band must start at the version's minimum sum assured (${v.minSumAssured})`);
  }
}
```

7. In the body mapping, after the ACCOUNT spread:

```ts
    // A fixed-term deposit: the grid flattened to one row per cell. No accumulation block -- the
    // server builds the zero-charge account behind it.
    ...(values.valueBasis === 'DEPOSIT' && {
      deposit: {
        rates: values.depositBands.flatMap((band) =>
          values.depositTerms.map((term, j) => ({
            minAmount: Number(band.minAmount),
            termMonths: Number(term.months),
            ratePercent: Number(band.rates[j]),
          })),
        ),
      },
    }),
```

Run: `npx vitest run src/features/products/publishVersionSchema.test.ts`. Expected: PASS.

- [ ] **Step 4: The form**

In `PublishVersionForm.tsx`:

1. Next to `accountCharges`:

```tsx
  // A fixed-term deposit: terms are the grid's columns, bands its rows. Adding a column adds a cell
  // to every band, so a band can never be shorter than the terms it must offer.
  const depositTerms = useFieldArray({ control, name: 'depositTerms' });
  const depositBands = useFieldArray({ control, name: 'depositBands' });
  function addDepositTerm() {
    depositTerms.append({ months: '' });
    getValues('depositBands').forEach((band, i) => setValue(`depositBands.${i}.rates`, [...band.rates, '']));
  }
  function removeDepositTerm(j: number) {
    depositTerms.remove(j);
    getValues('depositBands').forEach((band, i) => setValue(`depositBands.${i}.rates`, band.rates.filter((_, k) => k !== j)));
  }
```

`getValues` and `setValue` come from the form's `useForm` return. Add them to the destructuring if absent.

2. Add a third option in the Value basis select: `<option value="DEPOSIT">Fixed-term deposit</option>`.

3. After the `{valueBasis === 'ACCOUNT' && (...)}` block, inside the same bordered box:

```tsx
          {valueBasis === 'DEPOSIT' && (
            <div className="mt-3 space-y-3">
              <p className="text-xs text-subtle-foreground">
                One deposit for a term the client chooses. Each rate is for the whole term, not a year. A band runs from its
                start up to the next band&apos;s start. Nothing can be added or taken out until maturity.
              </p>
              <div className="overflow-x-auto">
                <table className="text-sm" aria-label="Deposit rates">
                  <thead>
                    <tr>
                      <th className="px-1 py-1 text-left text-xs font-medium text-muted-foreground">Deposits from</th>
                      {depositTerms.fields.map((field, j) => (
                        <th key={field.id} className="px-1 py-1">
                          <div className="flex items-center gap-1">
                            <Input
                              type="number" min={1} inputSize="sm" className="w-20 text-right" placeholder="Months"
                              aria-label={`Term ${j + 1} in months`}
                              {...register(`depositTerms.${j}.months`)}
                            />
                            <Button type="button" size="icon" variant="ghost" aria-label={`Remove term ${j + 1}`}
                              onClick={() => removeDepositTerm(j)}>
                              <X className="size-4" />
                            </Button>
                          </div>
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {depositBands.fields.map((field, i) => (
                      <tr key={field.id}>
                        <td className="px-1 py-1">
                          <Input
                            inputSize="sm" inputMode="decimal" className="w-32 text-right" placeholder="500000"
                            aria-label={`Band ${i + 1} starts at`}
                            {...register(`depositBands.${i}.minAmount`)}
                          />
                        </td>
                        {depositTerms.fields.map((term, j) => (
                          <td key={term.id} className="px-1 py-1">
                            <Input
                              inputSize="sm" inputMode="decimal" className="w-20 text-right" placeholder="%"
                              aria-label={`Band ${i + 1} rate for term ${j + 1}`}
                              {...register(`depositBands.${i}.rates.${j}`)}
                            />
                          </td>
                        ))}
                        <td className="px-1 py-1">
                          <Button type="button" size="icon" variant="ghost" aria-label={`Remove band ${i + 1}`}
                            onClick={() => depositBands.remove(i)}>
                            <X className="size-4" />
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {[
                errors.depositBands?.message, errors.depositBands?.root?.message, errors.depositTerms?.message,
                ...(errors.depositBands ?? []).flatMap?.((b) => [b?.minAmount?.message, ...(b?.rates ?? []).map?.((r) => r?.message) ?? []]) ?? [],
                ...(errors.depositTerms ?? []).map?.((t) => t?.months?.message) ?? [],
              ].filter(Boolean).map((message) => (
                <p key={message} role="alert" className="text-xs text-status-danger-fg">{message}</p>
              ))}
              <div className="flex gap-2">
                <Button type="button" size="sm" variant="ghost" className="-ml-2" onClick={addDepositTerm}>
                  <Plus className="size-4" /> Add a term
                </Button>
                <Button type="button" size="sm" variant="ghost"
                  onClick={() => depositBands.append(blankDepositBand(depositTerms.fields.length))}>
                  <Plus className="size-4" /> Add a band
                </Button>
              </div>
            </div>
          )}
```

If TypeScript rejects the error-collecting expression (RHF's error arrays are sparse objects), replace it with a small helper above the component:

```ts
function gridMessages(errors: FieldErrors<PublishVersionFormInput>): string[] {
  const out: string[] = [];
  const push = (m?: string) => { if (m && !out.includes(m)) out.push(m); };
  push(errors.depositBands?.message); push(errors.depositBands?.root?.message); push(errors.depositTerms?.message);
  const bands = errors.depositBands as unknown as Record<number, { minAmount?: { message?: string }; rates?: Record<number, { message?: string }> }> | undefined;
  Object.values(bands ?? {}).forEach((b) => { push(b?.minAmount?.message); Object.values(b?.rates ?? {}).forEach((r) => push(r?.message)); });
  const terms = errors.depositTerms as unknown as Record<number, { months?: { message?: string } }> | undefined;
  Object.values(terms ?? {}).forEach((t) => push(t?.months?.message));
  return out;
}
```

and render `gridMessages(errors).map(...)`.

4. Change the cash-value block's condition from `valueBasis !== 'ACCOUNT'` to `valueBasis === 'SCALE'`.

5. Import `blankDepositBand` from the schema file.

- [ ] **Step 5: Run the checks**

Run: `npm run typecheck ; npm run lint ; npx vitest run src/features/products`
Expected: all clean, with the existing `PublishVersionForm.test.tsx` still green.

- [ ] **Step 6: Commit**

```bash
git add frontend/src
git commit -m "feat(console): publish a fixed-term deposit -- a rate grid of bands by terms, checked as the server checks it"
```

---

### Task 8: Console — the deposit on the Account tab, and the awaiting list

**Files:**
- Modify `api/types.ts`, `api/accumulation.ts`, `store/accumulationStore.ts`, `AccountPanel.tsx`, `AccountPanel.test.tsx`, `PayoutsQueuePage.tsx`
- Create `DepositSection.tsx`, `depositForms.ts`, `depositForms.test.ts`

- [ ] **Step 1: Types and the API client**

`api/types.ts`, after the accumulation block:

```ts
export type DepositView = AccumulationComponents['schemas']['Deposit'];
export type DepositPeriodView = AccumulationComponents['schemas']['DepositPeriod'];
export type MaturityInstructionView = AccumulationComponents['schemas']['MaturityInstruction'];
export type AwaitingPayeeView = AccumulationComponents['schemas']['AwaitingPayee'];
```

`api/accumulation.ts`: add the four types to the import, then:

```ts
// ---- Fixed-term deposits ---------------------------------------------------------------------

/** The deposit, or null for any account that is not one -- an answer, like getAccount's. */
export async function getDeposit(policyNumber: string): Promise<DepositView | null> {
  try {
    return await get<DepositView>(`${account(policyNumber)}/deposit`);
  } catch (error) {
    if (isNotFound(error)) return null;
    throw error;
  }
}

export interface MaturityInstructionBody {
  action: 'REINVEST' | 'PAY_OUT';
  termMonths?: number;
  payeeRef?: string;
}

export function recordMaturityInstruction(policyNumber: string, body: MaturityInstructionBody, attempt: MutationAttempt) {
  return post<MaturityInstructionView>(`${account(policyNumber)}/deposit/maturity-instruction`, body, { headers: attempt.headers() });
}

export function listAwaitingPayee(): Promise<AwaitingPayeeView[]> {
  return get<AwaitingPayeeView[]>('/deposits/awaiting-payee');
}

/** 202: requested from the rail, not paid. */
export function payOutDeposit(policyNumber: string, payeeRef: string, attempt: MutationAttempt) {
  return post<DepositPeriodView>(`${account(policyNumber)}/deposit/payout`, { payeeRef }, { headers: attempt.headers() });
}
```

- [ ] **Step 2: The store**

In `accumulationStore.ts`:
1. Import the four functions, `MaturityInstructionBody` and the types.
2. Add to the state interface:

```ts
  deposit: Keyed<DepositView | null>;
  awaiting: Resource<AwaitingPayeeView[]>;
  loadDeposit: (policyNumber: string) => Promise<void>;
  loadAwaiting: () => Promise<void>;
  instruct: (policyNumber: string, body: MaturityInstructionBody, attempt: MutationAttempt) => Promise<void>;
  payOutDeposit: (policyNumber: string, payeeRef: string, attempt: MutationAttempt) => Promise<void>;
```

3. In the initial state: `deposit: {}, awaiting: idle(),`.
4. The implementations:

```ts
    loadDeposit: (policyNumber) => keyed('deposit', policyNumber, 'deposit', () => getDeposit(policyNumber)),
    loadAwaiting: () =>
      track('accumulation.awaiting', getState().awaiting, (next) => set({ awaiting: next }), () => listAwaitingPayee()),

    instruct: (policyNumber, body, attempt) =>
      act(`instruct.${policyNumber}`, async () => {
        await recordMaturityInstruction(policyNumber, body, attempt);
        await getState().loadDeposit(policyNumber);
      }),

    payOutDeposit: (policyNumber, payeeRef, attempt) =>
      act(`depositPayout.${policyNumber}`, async () => {
        await payOutDeposit(policyNumber, payeeRef, attempt);
        await Promise.all([getState().loadDeposit(policyNumber), getState().loadAccount(policyNumber)]);
      }),
```

Check how `track` is typed in `createResourceSlice` (`keyed` above calls it with `(scope, current, set, load)`), and match that call shape for `loadAwaiting`.

- [ ] **Step 3: Form values, with tests first**

`features/accounts/depositForms.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { instructionSchema, toInstructionBody } from './depositForms';

describe('the maturity instruction form', () => {
  it('reinvesting needs a term', () => {
    expect(instructionSchema.safeParse({ action: 'REINVEST', termMonths: '', payeeRef: '' }).success).toBe(false);
    expect(toInstructionBody({ action: 'REINVEST', termMonths: '6', payeeRef: '' })).toEqual({ action: 'REINVEST', termMonths: 6 });
  });

  it('paying out sends the payee only when one is typed', () => {
    expect(toInstructionBody({ action: 'PAY_OUT', termMonths: '', payeeRef: '' })).toEqual({ action: 'PAY_OUT' });
    expect(toInstructionBody({ action: 'PAY_OUT', termMonths: '', payeeRef: '+255700000001' }))
      .toEqual({ action: 'PAY_OUT', payeeRef: '+255700000001' });
  });
});
```

`features/accounts/depositForms.ts`:

```ts
import { z } from 'zod';
import type { MaturityInstructionBody } from '@/api/accumulation';

export const instructionSchema = z
  .object({
    action: z.enum(['REINVEST', 'PAY_OUT']),
    termMonths: z.string().trim(),
    payeeRef: z.string().trim().max(200),
  })
  .superRefine((v, ctx) => {
    if (v.action === 'REINVEST' && v.termMonths === '') {
      ctx.addIssue({ code: 'custom', path: ['termMonths'], message: 'Choose the term to reinvest for' });
    }
  });
export type InstructionValues = z.infer<typeof instructionSchema>;

export function toInstructionBody(v: InstructionValues): MaturityInstructionBody {
  return v.action === 'REINVEST'
    ? { action: 'REINVEST', termMonths: Number(v.termMonths) }
    : { action: 'PAY_OUT', ...(v.payeeRef !== '' && { payeeRef: v.payeeRef }) };
}

export const payeeSchema = z.object({ payeeRef: z.string().trim().min(1, 'Who should be paid?').max(200) });
export type PayeeValues = z.infer<typeof payeeSchema>;
```

Run: `npx vitest run src/features/accounts/depositForms.test.ts`. Expected: PASS.

- [ ] **Step 4: DepositSection**

`features/accounts/DepositSection.tsx`. It reuses the components `AccountPanel` imports (`Field`, `FormField`, `Button`, `Input`, `StatusBadge`, `InlineError`, `startMutation`, `formatMoney`, `formatDate`):

```tsx
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import type { DepositView } from '@/api/types';
import { Field } from '@/components/Field';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select } from '@/components/ui/select';
import { formatDate } from '@/lib/dates';
import { startMutation } from '@/lib/idempotency';
import { formatMoney } from '@/lib/money';
import { useAccumulationStore } from '@/store/accumulationStore';
import { instructionSchema, payeeSchema, toInstructionBody, type InstructionValues, type PayeeValues } from './depositForms';

const STATUS_LABEL = { RUNNING: 'Running', MATURED: 'Matured', TERMINATED: 'Ended early', CANCELLED: 'Cancelled' } as const;

/**
 * A fixed-term deposit (2026-10-02): the running term and its rate FOR THE TERM, every term before
 * it, and what happens at maturity. Nothing can be added or taken out, so no movement is offered;
 * the panel says so instead of showing buttons that would be refused.
 */
export function DepositSection({ deposit, isFinance }: { deposit: DepositView; isFinance: boolean }) {
  const running = deposit.periods.find((p) => p.status === 'RUNNING');
  return (
    <div className="space-y-4">
      {running && (
        <dl className="divide-y divide-border rounded-md border border-border" aria-label="Running term">
          <Field label="Deposit" value={formatMoney(running.principal)} emphasis />
          <Field label="Term" value={`${running.termMonths} months, ${running.ratePercent}% for the term`} />
          <Field label="Started" value={formatDate(running.startDate)} />
          <Field label="Matures" value={formatDate(running.maturityDate)} />
          <Field label="Interest earned so far" value={formatMoney(deposit.interestSoFar)} />
        </dl>
      )}
      {running && (
        <p className="text-xs text-muted-foreground">
          This is a fixed-term deposit: nothing can be added or taken out until it matures on {formatDate(running.maturityDate)}.
        </p>
      )}
      {running && <MaturityPanel deposit={deposit} />}
      {deposit.awaitingPayee && isFinance && <PayeePanel policyNumber={deposit.policyNumber} />}
      {deposit.awaitingPayee && !isFinance && (
        <p className="text-sm">This deposit has matured and waits for finance to record who is paid.</p>
      )}
      <div>
        <p className="mb-1.5 text-xs font-medium">Terms</p>
        <div className="divide-y divide-border rounded-md border border-border" role="list" aria-label="Deposit terms">
          {deposit.periods.map((p) => (
            <div key={p.periodId} role="listitem" className="flex items-start justify-between gap-3 px-4 py-2.5">
              <div>
                <span className="text-sm font-medium">Term {p.seq} · {STATUS_LABEL[p.status]}</span>
                <p className="text-xs text-muted-foreground">
                  {formatDate(p.startDate)} to {formatDate(p.maturityDate)} · {p.termMonths} months at {p.ratePercent}%
                </p>
              </div>
              <span className="shrink-0 text-right text-sm">
                {formatMoney(p.principal)}
                {p.interestPosted && <span className="block text-xs text-muted-foreground">interest {formatMoney(p.interestPosted)}</span>}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function MaturityPanel({ deposit }: { deposit: DepositView }) {
  const instruct = useAccumulationStore((s) => s.instruct);
  const acting = useAccumulationStore((s) => s.acting[`instruct.${deposit.policyNumber}`]);
  const form = useForm<InstructionValues>({
    resolver: zodResolver(instructionSchema),
    defaultValues: { action: 'PAY_OUT', termMonths: '', payeeRef: '' },
  });
  const action = form.watch('action');
  const current = deposit.instruction;

  return (
    <form
      className="space-y-3 rounded-md border border-border p-3"
      aria-label="At maturity"
      onSubmit={form.handleSubmit(async (values) => {
        await instruct(deposit.policyNumber, toInstructionBody(values), startMutation());
      })}
    >
      <p className="text-xs font-medium text-muted-foreground">At maturity</p>
      <p className="text-sm">
        {current
          ? current.action === 'REINVEST'
            ? `Reinvest for ${current.termMonths} months (recorded by ${current.recordedBy})`
            : `Pay out to ${current.payeeRef ?? deposit.defaultPayeeRef ?? 'a payee finance records'} (recorded by ${current.recordedBy})`
          : `No instruction: it will be paid out to ${deposit.defaultPayeeRef ?? 'a payee finance records'}`}
      </p>
      <div className="grid grid-cols-2 gap-3">
        <FormField label="What should happen" error={form.formState.errors.action?.message}>
          <Select inputSize="sm" {...form.register('action')}>
            <option value="PAY_OUT">Pay out</option>
            <option value="REINVEST">Reinvest the deposit and its interest</option>
          </Select>
        </FormField>
        {action === 'REINVEST' ? (
          <FormField label="New term" error={form.formState.errors.termMonths?.message}>
            <Select inputSize="sm" {...form.register('termMonths')}>
              <option value="">Choose…</option>
              {deposit.termsOffered.map((t) => (
                <option key={t} value={String(t)}>{t} months</option>
              ))}
            </Select>
          </FormField>
        ) : (
          <FormField label="Pay to (blank: the number it came from)" error={form.formState.errors.payeeRef?.message}>
            <Input inputSize="sm" {...form.register('payeeRef')} />
          </FormField>
        )}
      </div>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>Record instruction</Button>
    </form>
  );
}

function PayeePanel({ policyNumber }: { policyNumber: string }) {
  const payOut = useAccumulationStore((s) => s.payOutDeposit);
  const acting = useAccumulationStore((s) => s.acting[`depositPayout.${policyNumber}`]);
  const form = useForm<PayeeValues>({ resolver: zodResolver(payeeSchema), defaultValues: { payeeRef: '' } });
  return (
    <form
      className="space-y-3 rounded-md border border-border p-3"
      aria-label="Record payee and pay"
      onSubmit={form.handleSubmit(async (values) => {
        await payOut(policyNumber, values.payeeRef, startMutation());
      })}
    >
      <p className="text-sm">This deposit has matured and there is no number to pay it to.</p>
      <FormField label="Pay to" error={form.formState.errors.payeeRef?.message}>
        <Input inputSize="sm" {...form.register('payeeRef')} />
      </FormField>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      {acting?.status === 'success' && <p className="text-sm">The payment has been requested from the provider.</p>}
      <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>Record payee and pay</Button>
    </form>
  );
}
```

Verify against `AccountPanel.tsx` before writing:
- `Select`'s import path; it is used in `PublishVersionForm`, so copy that import line;
- `InlineError`'s prop name;
- the `acting` resource's status values (`loading`/`success`/`error` in `createResourceSlice`);
- `startMutation()`'s signature, by copying the call shape `AccountPanel` uses.

Use the names the code has.

- [ ] **Step 5: AccountPanel shows it**

In `AccountPanel.tsx`:
1. Load the deposit with the account:

```tsx
  const deposit = useAccumulationStore((s) => s.deposit[policyNumber]);
  const loadDeposit = useAccumulationStore((s) => s.loadDeposit);

  useEffect(() => {
    void loadAccount(policyNumber);
    void loadMovements(policyNumber);
    void loadDeposit(policyNumber);
  }, [policyNumber, loadAccount, loadMovements, loadDeposit]);
```

2. The render: `isFinance` is computed inside `Movements`, so compute it here the same way (`readIdentity` + `canSeeFinance`), or pass it down.

```tsx
  const isDeposit = Boolean(deposit?.data);
  return (
    <div className="space-y-5">
      <Summary account={account.data} />
      {deposit?.data && <DepositSection deposit={deposit.data} isFinance={isFinance} />}
      <Ledger entries={account.data.entries} />
      <Movements account={account.data} deposit={isDeposit} />
    </div>
  );
```

3. In `Movements`, take `deposit: boolean` and render the withdrawal, top-up and transfer buttons only when `open && !deposit`. The adjustment stays (§D5).

Add to `AccountPanel.test.tsx`: add `deposit: {}` and `loadDeposit: async () => {}` to the `beforeEach` setState, then:

```tsx
  it('on a deposit shows the term and its rate, and offers no money movement but the adjustment', () => {
    useAccumulationStore.setState({
      deposit: {
        'POL-1': success({
          policyNumber: 'POL-1',
          periods: [{ periodId: 'p1', seq: 1, principal: money('1000000.00'), termMonths: 3, ratePercent: 3,
            rateVersionId: 'v1', startDate: '2026-10-01', maturityDate: '2027-01-01', status: 'RUNNING',
            interestPosted: null, closedOn: null }],
          instruction: null,
          interestSoFar: money('3260.87'),
          defaultPayeeRef: '+255700000777',
          awaitingPayee: false,
          termsOffered: [3, 6, 12],
        }),
      },
    });
    render(<AccountPanel policyNumber="POL-1" />);
    expect(screen.getByText('3 months, 3% for the term')).toBeInTheDocument();
    expect(screen.getByText(/it will be paid out to \+255700000777/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request withdrawal' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request top-up' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Propose adjustment' })).toBeInTheDocument();
  });
```

- [ ] **Step 6: The awaiting list on the payouts register**

At the bottom of `PayoutsQueuePage`'s returned JSX, add `<AwaitingDeposits />`, and define it in the same file:

```tsx
/**
 * Matured fixed-term deposits whose money waits for a payee: no number came with the deposit, or
 * the payment to it failed. Finance records the payee on the policy's Account tab.
 */
function AwaitingDeposits() {
  const awaiting = useAccumulationStore((s) => s.awaiting);
  const loadAwaiting = useAccumulationStore((s) => s.loadAwaiting);
  const navigate = useNavigate();
  useEffect(() => {
    void loadAwaiting();
  }, [loadAwaiting]);
  const rows = awaiting.data ?? [];
  if (rows.length === 0) return null;
  return (
    <section className="mt-6" aria-label="Matured deposits waiting for a payee">
      <h2 className="mb-2 text-sm font-medium">Matured deposits waiting for a payee</h2>
      <div className="divide-y divide-border rounded-md border border-border" role="list">
        {rows.map((row) => (
          <div key={row.policyNumber} role="listitem" className="flex items-center justify-between gap-3 px-4 py-2.5">
            <span className="text-sm">
              {row.policyNumber} · matured {formatDate(row.maturedOn)} · {formatMoney(row.balance)}
            </span>
            <Button size="sm" variant="outline" onClick={() => navigate(`/staff/policies/${row.policyNumber}`)}>
              Record payee
            </Button>
          </div>
        ))}
      </div>
    </section>
  );
}
```

Import `useAccumulationStore`. If the page's policy links use another path or a `<Link>`, match it: grep `PayoutsQueuePage` for `navigate(`.

- [ ] **Step 7: Run the checks**

Run: `npm run typecheck ; npm run lint ; npx vitest run src/features/accounts src/features/payouts src/store`
Expected: clean. The lint rule bans synchronous setState in an effect body, and `loadAwaiting` is async, so the effect is fine.

- [ ] **Step 8: Commit**

```bash
git add frontend/src
git commit -m "feat(console): a deposit's term, its rate and the maturity choice on the Account tab; matured deposits awaiting a payee on the payouts register"
```

---

### Task 9: e2e, then the gate

- [ ] **Step 1: Apply the migrations to the dev database and restart the backend**

The dev backend does not apply migrations, and a green Testcontainers run does not prove the dev database is in sync.
1. Stop the dev backend: TaskStop on its background shell.
2. Apply the migrations in order, from the repo root:

```bash
for f in backend/db-migrations/product/V20__deposit_rate_grid.sql backend/db-migrations/payment/V10__deposit_maturity_purpose.sql backend/db-migrations/accumulation/V3__deposit_periods.sql; do
  docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 < "$f" || break
done
```

3. Restart the backend in the background: `./mvnw -B -o spring-boot:run -Dspring-boot.run.profiles=local > "$TEMP/claude/devbackend6.log" 2>&1`.
4. Wait for `Started Application` in the log.

- [ ] **Step 2: The e2e spec**

`frontend/e2e/staff-fixed-term-deposit.spec.ts`. It mirrors `staff-savings-account.spec.ts`: as admin, publish a deposit product; issue a deposit by hand; record REINVEST; see the term. Bringing a real deposit to maturity in e2e would take 3 months, so maturity is proven by `DepositLifecycleIntegrationTest`. The e2e proves the console path.

The product steps follow `staff-products.spec.ts`'s create-and-publish flow. Before writing, open that spec and copy:
- its helper for creating an ENDOWMENT product and reaching the publish form;
- the field labels it fills: rating table, benefit, TIRA filing, free-look.

Then:

```ts
import { expect, test } from '@playwright/test';
import { asAdmin } from './admin';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';

/**
 * A fixed-term deposit through the real stack: the grid published as the user wrote it, a deposit
 * issued as one payment of itself, the rate it earns shown for the term, and the maturity choice
 * recorded. Maturity itself (three months away) is DepositLifecycleIntegrationTest's.
 */
test.describe('a fixed-term deposit', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });
  test.setTimeout(300_000);

  test('is published with its grid, issued, and told to reinvest at maturity', async ({ page, browser }) => {
    const productCode = `FTD-E2E-${Date.now().toString(36).toUpperCase()}`;
    await asAdmin(browser, async (admin) => {
      // [copy staff-products.spec.ts's steps to create an ENDOWMENT product named productCode and open
      //  its publish form, filling the rating table, a DEATH benefit, the TIRA filing and free-look 15]
      await admin.getByLabel('Value basis').selectOption('DEPOSIT');
      for (const months of ['3', '6', '12']) {
        await admin.getByRole('button', { name: 'Add a term' }).click();
        const index = ['3', '6', '12'].indexOf(months) + 1;
        await admin.getByLabel(`Term ${index} in months`).fill(months);
      }
      const bands = [['500000', '3', '4', '5'], ['6000000', '4', '5', '6'], ['11000000', '5', '6', '7'], ['21000000', '6', '7', '8']];
      for (const [i, band] of bands.entries()) {
        await admin.getByRole('button', { name: 'Add a band' }).click();
        await admin.getByLabel(`Band ${i + 1} starts at`).fill(band[0]!);
        for (let j = 1; j <= 3; j++) await admin.getByLabel(`Band ${i + 1} rate for term ${j}`).fill(band[j]!);
      }
      // [the publish button and success assertion exactly as staff-products.spec.ts has them]
    });

    const policyNumber = await asAdmin(browser, async (admin) => {
      const caseId = await caseAwaitingManualIssue(admin, '1000000.00', productCode);
      await admin.goto('/staff/policies/new');
      await selectUnderwritingCase(admin, caseId);
      await expect(admin.getByText('Resolving product version…')).not.toBeVisible();
      await admin.getByLabel('Sum assured').fill('1000000.00');
      await admin.getByLabel('Premium', { exact: true }).fill('1000000.00');
      await admin.getByLabel('Premium frequency').selectOption('SINGLE');
      await admin.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
      await admin.getByLabel('Reason for manual issue').fill('E2E fixture: fixed-term deposit');
      await admin.getByLabel('Policy term (months)').fill('3');
      await admin.getByRole('button', { name: 'Issue policy' }).click();
      await expect(admin).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 60_000 });
      return admin.url().split('/').pop() as string;
    });

    await page.goto(`/staff/policies/${policyNumber}`);
    await page.getByRole('tab', { name: 'Account' }).click();
    // The single premium is collected through the rail, as any first premium is; then period 1 opens.
    await expect(async () => {
      await page.reload();
      await page.getByRole('tab', { name: 'Account' }).click();
      await expect(page.getByText('3 months, 3% for the term')).toBeVisible();
    }).toPass({ timeout: 90_000 });
    await expect(page.getByRole('button', { name: 'Request top-up' })).toHaveCount(0);

    const atMaturity = page.getByRole('form', { name: 'At maturity' });
    await atMaturity.getByLabel('What should happen').selectOption('REINVEST');
    await atMaturity.getByLabel('New term').selectOption('6');
    await atMaturity.getByRole('button', { name: 'Record instruction' }).click();
    await expect(page.getByText(/Reinvest for 6 months \(recorded by/)).toBeVisible({ timeout: 20_000 });
  });
});
```

Replace the two bracketed comments with the real steps from `staff-products.spec.ts`. Replace `caseAwaitingManualIssue`'s product argument with whatever `SAVINGS_PRODUCT` is (a code or a name; check `e2e/underwriting.ts`). If the premium-frequency field has another label on the manual-issue form, use that label. If the first premium of a MIGRATION issue is not collected automatically in dev, check what `staff-savings-account.spec.ts` relies on: it funds by top-up, which a deposit refuses. Then collect the single premium through the Billing tab's existing "collect" action (`staff-billing.spec.ts` shows it).

Run: `cd frontend && npx playwright test e2e/staff-fixed-term-deposit.spec.ts` (with deps, so auth is fresh; never `--no-deps`). Do not run vitest at the same time.
Expected: PASS. Iterate on selectors until it passes. The rule from memory: e2e is coupled to accessible names, so a failure here is a real defect until shown otherwise.

- [ ] **Step 3: Commit the spec**

```bash
git add frontend/e2e/staff-fixed-term-deposit.spec.ts
git commit -m "test(e2e): a fixed-term deposit published, issued, and told to reinvest"
```

- [ ] **Step 4: The gate**

1. Stop the dev backend (TaskStop).
2. `cd backend && ./mvnw -B -o clean test`, in the background, about 80 minutes. Expected: BUILD SUCCESS, about 1,800 tests, 0 failures. Any failure is diagnosed, never waved through as pre-existing.
3. Restart the dev backend, then `cd frontend && npm run typecheck ; npm run lint ; npx vitest run`, then `npx playwright test`. That is the full e2e: the deposit spec plus the 143 existing ones.
4. A final seam review, solo, against this plan's §R and the spec. Read the diff `git diff main...fixed-term-deposit`, checking each seam:
   - every `isAccount()` caller;
   - every `PayoutRequested` consumer;
   - every place a policy's `maturityDate` is read;
   - every listener's purpose filter.
5. Fix what it finds, rerun the affected classes, then: `git checkout main && git merge --no-ff fixed-term-deposit -m "Merge fixed-term deposit: a rate for the term, by band, reinvest or pay out at maturity"`.
6. Update memory:
   - `project_fixed_term_deposit.md`: merged, hash, gate counts, what was proven live and what was not (maturity is never seen live until a deposit is 3 months old);
   - `MEMORY.md`'s line.
7. The bonuses plan (`f8d49504`) renumbers its product V20 to V21, and adds the §R11 refusal (a deposit may not also be with-profits).
