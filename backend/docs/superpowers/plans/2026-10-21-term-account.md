# Term Account Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fourth way a product's value can be defined — contributions arrive regularly like a
savings account, but nothing is ever paid out until a fixed maturity date, at a rate locked per
policy at issuance from a band the product declares.

**Architecture:** No new value-basis enum and no new accumulation engine. `ValueBasis` stays
`SCALE`/`ACCOUNT` — a term account is internally an `ACCOUNT`-basis version (so it gets the real
`accumulation_charge` table, unlike the fixed-term deposit's synthesized zero-charge plan) with an
extra `TermAccountPlan` (a minimum and maximum rate) attached to its `version_accumulation_terms` row.
Its presence is what distinguishes a term account from a plain savings account, the exact way a
non-empty `deposit_rate_row` set is what distinguishes a fixed-term deposit from either — same
discriminator pattern, reused. `accumulation` gains one new table (`term_account`, one row per policy:
the locked rate, the maturity date, status) and a new drain that matures it, both closely mirroring
the fixed-term deposit's existing `deposit_period`/`DepositMaturityDrain`.

**Tech Stack:** Spring Boot / Java backend (Flyway migrations, JPA, Spring `@Scheduled`), React +
TypeScript + Zod + react-hook-form frontend, OpenAPI-generated types, Playwright e2e, JUnit.

## Global Constraints

- Every money figure is a decimal string on the wire, matching `^-?\d+(\.\d{1,2})?$` — never a JSON
  number (PRODUCT.md).
- No client-side arithmetic anywhere in the frontend — every figure the console shows comes from the
  backend.
- Ledger entries are immutable; a correction is a reversal or adjustment, never an edit (step 3's
  rule, unchanged here).
- One source reference produces at most one posting, however many times its event is delivered
  (`UNIQUE (tenant_id, source_type, source_ref)` on `accumulation.posting`).
- A missed contribution never lapses a term-account policy — only exhaustion of the balance does
  (same rule as `ACCOUNT`, D5 of the spec).
- Money in (contributions, top-ups, transfers in) is never refused by the lock; only money out
  (withdrawal, transfer-out, surrender) is, until maturity (D4).
- Every new backend enum-like literal (status values, event kinds) must be a real, intentional
  choice, documented in the migration or class Javadoc it is declared in — never invented ad hoc at a
  call site.
- Follow this codebase's existing patterns exactly where one already exists for the thing being
  built (a validator, a drain, a console form section) — several tasks below start with "read file X
  first" for precisely this reason: do not improvise a shape the codebase has already settled.

**Corrections to the design spec, found while planning** (same discipline the fixed-term-deposit
spec's own §10 used — read before starting):

1. **No new `ValueBasis` enum value.** The enum is only `SCALE`/`ACCOUNT` today; `DEPOSIT` in the
   console's publish form is a UI-only convenience that maps to `ACCOUNT` plus a synthesized
   zero-charge plan. A term account is `ACCOUNT`-basis with a *real* accumulation-charges block (per
   spec D8) and a `TermAccountPlan` sitting alongside it — mirrors how a deposit is `ACCOUNT`-basis
   with a *synthesized* plan and a `DepositPlan` sitting alongside it.
2. **The rate band lives on `version_accumulation_terms`, not a new table.** That table already
   holds the ACCOUNT-basis version's per-version terms (`guaranteed_rate_percent`,
   `minimum_balance`); the new `min_rate_percent`/`max_rate_percent` are two more nullable columns on
   it, not a sibling table — a term-account version already has a real row there (by D8/correction 1),
   so there's nothing to join.
3. **The default payee lives on the new `term_account` row itself**, not on `accumulation.account` —
   the fixed-term deposit's own `default_payee_ref` turned out to live on `deposit_period` during
   implementation, not `account` as that spec originally said. Match reality, not the old spec text.
4. **Payment purposes are plain string literals**, matched with `.equals()` in
   `PaymentEventListener`, not a Java enum. The new one is the literal `"TERM_ACCOUNT_MATURITY_PAYOUT"`.
5. **The lock must be its own, narrower check** — refused at withdrawal, transfer-out and surrender
   only, never at top-up or transfer-in (the deposit's existing `refuseMovement` refuses all three,
   which is correct for a deposit but wrong here).

---

## Task 1: Product — the rate band on the version

**Files:**
- Create: `backend/db-migrations/product/V33__term_account_rate_band.sql`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/AccumulationPlan.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/product/AccumulationPlanTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `AccumulationPlan` gains two new accessors, `minRatePercent()` and `maxRatePercent()`
  (both `BigDecimal`, nullable), read by Task 2's validator and Task 3's publish mapping.

- [ ] **Step 1: Read the existing migration and entity first**

  Open `backend/db-migrations/product/V32__account_charges.sql` and whatever migration first created
  `product.version_accumulation_terms` (search `grep -rn "version_accumulation_terms" backend/db-migrations/product/`)
  to confirm the exact column list and naming convention before adding to it.

- [ ] **Step 2: Write the migration**

```sql
-- V33__term_account_rate_band.sql
ALTER TABLE product.version_accumulation_terms
    ADD COLUMN min_rate_percent NUMERIC(7,4),
    ADD COLUMN max_rate_percent NUMERIC(7,4);

ALTER TABLE product.version_accumulation_terms
    ADD CONSTRAINT version_accumulation_terms_rate_band_check
    CHECK (min_rate_percent IS NULL OR max_rate_percent IS NULL OR max_rate_percent >= min_rate_percent);

ALTER TABLE product.version_accumulation_terms
    ADD CONSTRAINT version_accumulation_terms_min_rate_percent_check
    CHECK (min_rate_percent IS NULL OR (min_rate_percent >= 0 AND min_rate_percent <= 100));

ALTER TABLE product.version_accumulation_terms
    ADD CONSTRAINT version_accumulation_terms_max_rate_percent_check
    CHECK (max_rate_percent IS NULL OR (max_rate_percent >= 0 AND max_rate_percent <= 100));

COMMENT ON COLUMN product.version_accumulation_terms.min_rate_percent IS
    'A term account''s rate band floor. Null on a plain savings account. The band a case''s own locked rate (underwriting.underwriting_case.proposed_rate_percent) must fall inside.';
COMMENT ON COLUMN product.version_accumulation_terms.max_rate_percent IS
    'A term account''s rate band ceiling. Null on a plain savings account.';
```

- [ ] **Step 3: Run the migration against the dev database**

  Run: `./mvnw -pl backend flyway:migrate` (or whatever this project's existing migrate script is —
  confirm with `cat backend/scripts/*.sh | grep -i flyway` if unsure) and verify with:
  `docker exec infra-postgres-1 psql -U postgres -d lifeplatform -c "\d product.version_accumulation_terms"`
  — both new columns and all three constraints must appear.

- [ ] **Step 4: Add the two accessors to `AccumulationPlan`**

  Read the full current file first — it is a small record (`basis`, `guaranteedRatePercent`,
  `minimumBalance`, `charges`, plus `none()`/`forDeposit()`/`isAccount()`). Add the two new
  components:

```java
public record AccumulationPlan(
    ValueBasis basis,
    BigDecimal guaranteedRatePercent,
    BigDecimal minimumBalance,
    BigDecimal minRatePercent,
    BigDecimal maxRatePercent,
    List<AccumulationChargeRow> charges
) {
    public static AccumulationPlan none() {
        return new AccumulationPlan(null, null, null, null, null, List.of());
    }

    /** A fixed-term deposit's synthesized zero-charge plan. No rate band -- a deposit's rate comes
        from its own rate table, never this plan. */
    public static AccumulationPlan forDeposit() {
        return new AccumulationPlan(ValueBasis.ACCOUNT, null, null, null, null, List.of());
    }

    public boolean isAccount() {
        return basis == ValueBasis.ACCOUNT;
    }

    /** A term account: a real accumulation plan that also declares a rate band. */
    public boolean isTermAccount() {
        return isAccount() && minRatePercent != null && maxRatePercent != null;
    }
}
```

  Update every other constructor call site this change breaks (the compiler will find them — run
  `./mvnw -pl backend compile` and fix each one by passing `null, null` for the two new positions,
  except the real publish-mapping call site Task 3 touches on purpose).

- [ ] **Step 5: Write the failing test**

```java
class AccumulationPlanTest {
    @Test
    void isTermAccountOnlyWhenBothBoundsPresent() {
        AccumulationPlan plain = new AccumulationPlan(ValueBasis.ACCOUNT, new BigDecimal("5.0"),
            BigDecimal.ZERO, null, null, List.of());
        assertThat(plain.isTermAccount()).isFalse();

        AccumulationPlan termAccount = new AccumulationPlan(ValueBasis.ACCOUNT, new BigDecimal("5.0"),
            BigDecimal.ZERO, new BigDecimal("3.0"), new BigDecimal("7.0"), List.of());
        assertThat(termAccount.isTermAccount()).isTrue();
    }

    @Test
    void forDepositHasNoRateBand() {
        assertThat(AccumulationPlan.forDeposit().isTermAccount()).isFalse();
    }
}
```

- [ ] **Step 6: Run it, confirm it passes**

  Run: `./mvnw -pl backend test -Dtest=AccumulationPlanTest`
  Expected: both tests PASS (the record change in Step 4 already makes them pass — this step is
  verification, not red-green, since a record's accessors need no separate implementation).

- [ ] **Step 7: Commit**

```bash
git add backend/db-migrations/product/V33__term_account_rate_band.sql \
  backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/AccumulationPlan.java \
  backend/src/test/java/tz/co/nlolo/lifeplatform/product/AccumulationPlanTest.java
git commit -m "feat(product): a term account's rate band lives on the accumulation plan"
```

---

## Task 2: Product — `TermAccountPlanValidator`

**Files:**
- Create: `backend/src/test/java/tz/co/nlolo/lifeplatform/product/TermAccountPlanValidatorTest.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/domain/DepositPlanValidator.java` (read only, for the pattern)
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/domain/TermAccountPlanValidator.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java` (the publish-version method, where `DepositPlanValidator.validate(...)` is already called)

**Interfaces:**
- Consumes: `AccumulationPlan.isTermAccount()` from Task 1; `ProductCategory`; `DepositPlan`;
  `PayoutPlan`; `EligibilityBounds` (all pre-existing).
- Produces: `TermAccountPlanValidator.validate(ProductCategory category, AccumulationPlan accumulation, DepositPlan deposit, PayoutPlan payout, EligibilityBounds bounds)`,
  called by Task 3's publish flow. Throws `InvalidProductVersionException` (the existing exception
  type every sibling validator throws).

- [ ] **Step 1: Write the failing tests**

```java
class TermAccountPlanValidatorTest {
    private AccumulationPlan termAccount(BigDecimal min, BigDecimal max) {
        return new AccumulationPlan(ValueBasis.ACCOUNT, new BigDecimal("5.0"), BigDecimal.ZERO,
            min, max, List.of());
    }

    @Test
    void acceptsAnEndowmentWithAnOrderedBandAndBoundedTerm() {
        AccumulationPlan plan = termAccount(new BigDecimal("3.0"), new BigDecimal("7.0"));
        EligibilityBounds bounds = new EligibilityBounds(null, null, 12, 60, null, null);
        assertThatCode(() -> TermAccountPlanValidator.validate(
            ProductCategory.ENDOWMENT, plan, DepositPlan.none(), PayoutPlan.none(), bounds))
            .doesNotThrowAnyException();
    }

    @Test
    void refusesACategoryOutsideTheThree() {
        AccumulationPlan plan = termAccount(new BigDecimal("3.0"), new BigDecimal("7.0"));
        EligibilityBounds bounds = new EligibilityBounds(null, null, 12, 60, null, null);
        assertThatThrownBy(() -> TermAccountPlanValidator.validate(
            ProductCategory.TERM_LIFE, plan, DepositPlan.none(), PayoutPlan.none(), bounds))
            .isInstanceOf(InvalidProductVersionException.class)
            .hasMessageContaining("TERM_LIFE");
    }

    @Test
    void refusesAMissingTermBound() {
        AccumulationPlan plan = termAccount(new BigDecimal("3.0"), new BigDecimal("7.0"));
        EligibilityBounds noTerm = new EligibilityBounds(null, null, null, null, null, null);
        assertThatThrownBy(() -> TermAccountPlanValidator.validate(
            ProductCategory.ENDOWMENT, plan, DepositPlan.none(), PayoutPlan.none(), noTerm))
            .isInstanceOf(InvalidProductVersionException.class)
            .hasMessageContaining("term");
    }

    @Test
    void refusesAMaxRateBelowMin() {
        // Construction itself refuses it -- AccumulationPlan carries no ordering check of its own
        // (unlike EligibilityBounds), so the validator is where this is actually caught.
        AccumulationPlan plan = termAccount(new BigDecimal("7.0"), new BigDecimal("3.0"));
        EligibilityBounds bounds = new EligibilityBounds(null, null, 12, 60, null, null);
        assertThatThrownBy(() -> TermAccountPlanValidator.validate(
            ProductCategory.ENDOWMENT, plan, DepositPlan.none(), PayoutPlan.none(), bounds))
            .isInstanceOf(InvalidProductVersionException.class)
            .hasMessageContaining("maximum");
    }

    @Test
    void refusesAlongsideADepositPlan() {
        AccumulationPlan plan = termAccount(new BigDecimal("3.0"), new BigDecimal("7.0"));
        EligibilityBounds bounds = new EligibilityBounds(null, null, 12, 60, null, null);
        DepositPlan deposit = new DepositPlan(List.of(new DepositRateRow(new BigDecimal("20000"), 12, new BigDecimal("5.0"))));
        assertThatThrownBy(() -> TermAccountPlanValidator.validate(
            ProductCategory.ENDOWMENT, plan, deposit, PayoutPlan.none(), bounds))
            .isInstanceOf(InvalidProductVersionException.class);
    }

    @Test
    void refusesAPayoutSchedule() {
        AccumulationPlan plan = termAccount(new BigDecimal("3.0"), new BigDecimal("7.0"));
        EligibilityBounds bounds = new EligibilityBounds(null, null, 12, 60, null, null);
        PayoutPlan payout = PayoutPlan.of(List.of(/* one real PayoutRow, built the same way
            PayoutPlanValidatorTest already builds one -- copy that helper rather than
            re-deriving its constructor shape here */));
        assertThatThrownBy(() -> TermAccountPlanValidator.validate(
            ProductCategory.ENDOWMENT, plan, DepositPlan.none(), payout, bounds))
            .isInstanceOf(InvalidProductVersionException.class)
            .hasMessageContaining("payout");
    }

    @Test
    void doesNothingWhenThereIsNoRateBand() {
        AccumulationPlan plain = new AccumulationPlan(ValueBasis.ACCOUNT, new BigDecimal("5.0"),
            BigDecimal.ZERO, null, null, List.of());
        assertThatCode(() -> TermAccountPlanValidator.validate(
            ProductCategory.TERM_LIFE, plain, DepositPlan.none(), PayoutPlan.none(), EligibilityBounds.none()))
            .doesNotThrowAnyException();
    }
}
```

  Before finishing this step, open `PayoutPlanValidatorTest` to copy its existing helper for
  constructing one real `PayoutRow`/`PayoutPlan` with a row in it, and use that helper verbatim in
  `refusesAPayoutSchedule` instead of the placeholder comment above.

- [ ] **Step 2: Run it to verify it fails**

  Run: `./mvnw -pl backend test -Dtest=TermAccountPlanValidatorTest`
  Expected: compile failure — `TermAccountPlanValidator` does not exist yet.

- [ ] **Step 3: Write `TermAccountPlanValidator`**, modeled directly on `DepositPlanValidator`
  (read that file fully first — it is quoted in this plan's Global Constraints research and should
  match):

```java
package tz.co.nlolo.lifeplatform.product.domain;

import java.util.EnumSet;
import java.util.Set;
import tz.co.nlolo.lifeplatform.product.api.AccumulationPlan;
import tz.co.nlolo.lifeplatform.product.api.DepositPlan;
import tz.co.nlolo.lifeplatform.product.api.EligibilityBounds;
import tz.co.nlolo.lifeplatform.product.api.PayoutPlan;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;

/**
 * A term account is an ACCOUNT-basis version that also declares a rate band
 * ({@link AccumulationPlan#isTermAccount()}) -- the same "ACCOUNT plus a sibling plan" shape a
 * fixed-term deposit already uses, so this validator runs the same three checks
 * {@link DepositPlanValidator} does, adapted to this plan's own rules.
 */
public final class TermAccountPlanValidator {

    private static final Set<ProductCategory> TERM_ACCOUNT_CATEGORIES =
        EnumSet.of(ProductCategory.ENDOWMENT, ProductCategory.WHOLE_LIFE, ProductCategory.EDUCATION_SAVINGS);

    private TermAccountPlanValidator() {}

    public static void validate(ProductCategory category, AccumulationPlan accumulation, DepositPlan deposit,
                                PayoutPlan payout, EligibilityBounds bounds) {
        if (accumulation == null || !accumulation.isTermAccount()) {
            return;
        }
        if (!TERM_ACCOUNT_CATEGORIES.contains(category)) {
            fail("A " + category + " product cannot be a term account");
        }
        if (deposit != null && deposit.isDeposit()) {
            fail("A version is priced either as a fixed-term deposit or a term account, not both");
        }
        if (bounds == null || bounds.minTermMonths() == null || bounds.maxTermMonths() == null) {
            fail("A term account needs both a minimum and a maximum term -- there is nothing to compute a maturity date from otherwise");
        }
        if (accumulation.maxRatePercent().compareTo(accumulation.minRatePercent()) < 0) {
            fail("A term account's maximum rate cannot be below its minimum");
        }
        if (payout != null && !payout.rows().isEmpty()) {
            fail("A term account matures through its own account; it carries no payout schedule");
        }
    }

    private static void fail(String message) {
        throw new InvalidProductVersionException(message);
    }
}
```

- [ ] **Step 4: Run the tests, confirm they pass**

  Run: `./mvnw -pl backend test -Dtest=TermAccountPlanValidatorTest`
  Expected: all 7 PASS.

- [ ] **Step 5: Wire it into the publish-version flow**

  In `ProductApiImpl.java`, find the exact block already quoted in this plan's research (around the
  `DepositPlanValidator.validate(...)` call). Add immediately after the existing
  `AccumulationPlanValidator.validate(...)` call:

```java
TermAccountPlanValidator.validate(category, effectiveAccumulation, deposit, payoutPlan, bounds);
```

- [ ] **Step 6: Run the full product module's existing test suite**

  Run: `./mvnw -pl backend test -Dtest=tz.co.nlolo.lifeplatform.product.**`
  Expected: PASS, including every pre-existing publish-flow test (confirms the new call does not
  misfire on a plain ACCOUNT or DEPOSIT version).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/product/domain/TermAccountPlanValidator.java \
  backend/src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java \
  backend/src/test/java/tz/co/nlolo/lifeplatform/product/TermAccountPlanValidatorTest.java
git commit -m "feat(product): validate a term account's rate band at publish time"
```

---

## Task 3: Product — publish request, `resolveTermAccountPlan`, and the OpenAPI spec

**Files:**
- Read first: `backend/api/openapi/openapi-product.yaml` (the `ACCOUNT`/`DEPOSIT` publish-request
  schema), and the Java DTO class the publish controller deserializes into (search
  `grep -rn "guaranteedRatePercent" backend/src/main/java/tz/co/nlolo/lifeplatform/product/infrastructure/`
  to find its exact name and path).
- Modify: that request DTO class
- Modify: `backend/api/openapi/openapi-product.yaml`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/ProductApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java`
- Test: extend whatever existing integration test class already covers publishing an `ACCOUNT`
  version (search `grep -rln "guaranteedRatePercent" backend/src/test/`)

**Interfaces:**
- Consumes: `AccumulationPlan` (Task 1), `TermAccountPlanValidator` (Task 2).
- Produces: `ProductApi.resolveTermAccountPlan(UUID productVersionId)` returning the version's
  `AccumulationPlan` (reuse the same type — there is no separate `TermAccountPlan` type; Task 1's
  `isTermAccount()`/`minRatePercent()`/`maxRatePercent()` on `AccumulationPlan` are the whole
  interface). Consumed by Task 5 (underwriting's rate-band check) and Task 8 (accumulation's
  discriminator).

- [ ] **Step 1: Read the publish request DTO and the OpenAPI schema fully**, to confirm the exact
  field names (`guaranteedRatePercent`, `minimumBalance`) and JSON casing already in use.

- [ ] **Step 2: Add the two new optional fields to the request DTO**, named identically to the wire
  field names the frontend will send (decide these now so Task 13's frontend mapping matches
  exactly): `minRatePercent`, `maxRatePercent`, both `String` (decimal-as-string, per the Global
  Constraints money rule), nullable.

- [ ] **Step 3: Update the OpenAPI spec** to add the same two optional string fields to the publish
  request schema, with the pattern `^\d{1,3}(\.\d{1,4})?$` (matching the existing
  `guaranteedRatePercent` field's own pattern — copy it exactly rather than inventing a new one).

- [ ] **Step 4: Update `ProductApiImpl`'s publish-version mapping** to read the two new request
  fields into the `AccumulationPlan` constructed for Task 1, parsed with
  `new BigDecimal(value)` guarded by the same blank-to-null helper every other optional decimal
  field on this request already uses (find it — likely a private `parseDecimal(String)` or similar
  in the same class, used for `guaranteedRatePercent` today).

- [ ] **Step 5: Add `resolveTermAccountPlan` to `ProductApi` and implement it**

```java
// ProductApi.java, directly after resolveDepositPlan
AccumulationPlan resolveTermAccountPlan(UUID productVersionId);
```

```java
// ProductApiImpl.java
@Override
public AccumulationPlan resolveTermAccountPlan(UUID productVersionId) {
    AccumulationPlan plan = resolveAccumulationPlan(productVersionId); // the existing accessor
                                                                        // DepositPlan's own
                                                                        // resolver sits beside --
                                                                        // confirm its exact name
                                                                        // by reading this class
    return plan != null && plan.isTermAccount() ? plan : AccumulationPlan.none();
}
```

  If `resolveAccumulationPlan` is not the existing method's real name, use whatever the actual
  accessor for the current version's `AccumulationPlan` is called — read `ProductApiImpl.java` for
  it before writing this method; do not guess a second time if the first guess is wrong.

- [ ] **Step 6: Write the failing test** in the existing publish-integration test class found in
  Step 1's search — one case publishing a version with `minRatePercent`/`maxRatePercent` set and
  asserting `productApi.resolveTermAccountPlan(versionId).isTermAccount()` is `true`, and one
  publishing a plain `ACCOUNT` version (no rate band) asserting it is `false`.

- [ ] **Step 7: Run it to verify it fails, then implement until it passes**

  Run: `./mvnw -pl backend test -Dtest=<thatTestClass>`

- [ ] **Step 8: Regenerate the frontend's OpenAPI types** so Task 13 has real types to build against

  Run: `cd frontend && npm run generate:api`
  Expected: `frontend/src/types/api/` changes to include the two new optional fields on the publish
  request type. Do not hand-edit generated files.

- [ ] **Step 9: Commit**

```bash
git add backend/api/openapi/openapi-product.yaml \
  backend/src/main/java/tz/co/nlolo/lifeplatform/product/ \
  frontend/src/types/api/ \
  backend/src/test/
git commit -m "feat(product): publish a term account's rate band through the API"
```

---

## Task 4: Underwriting — the case's own locked rate

**Files:**
- Create: `backend/db-migrations/underwriting/V24__term_account_rate.sql`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/domain/UnderwritingCase.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/api/ProposalDetails.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/infrastructure/UnderwritingController.java` (around line 153)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/infrastructure/AgentQuotationController.java` (around line 138)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/application/CustomerProducts.java` (around line 107)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/application/ProposalAmendments.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting/UnderwritingCaseTest.java` (or
  wherever `amendTerms` is already unit-tested — search for it)

**Interfaces:**
- Consumes: nothing new yet (Task 5 adds the validation; this task only adds the field, end to end,
  unvalidated).
- Produces: `ProposalDetails.ratePercent()` (new 9th component); `UnderwritingCase.getRatePercent()`;
  `UnderwritingCase.amendTerms(...)` gains a `BigDecimal ratePercent` parameter. Both consumed by
  Task 5's rule and Task 14's frontend form via the generated request/response types.

- [ ] **Step 1: Write the migration**

```sql
-- V24__term_account_rate.sql
ALTER TABLE underwriting.underwriting_case
    ADD COLUMN proposed_rate_percent NUMERIC(7,4);

ALTER TABLE underwriting.underwriting_case
    ADD CONSTRAINT underwriting_case_proposed_rate_percent_check
    CHECK (proposed_rate_percent IS NULL OR (proposed_rate_percent >= 0 AND proposed_rate_percent <= 100));

COMMENT ON COLUMN underwriting.underwriting_case.proposed_rate_percent IS
    'The rate this case names for a term-account product, locked onto the policy unchanged at issuance. Null for every other product.';
```

- [ ] **Step 2: Run the migration**

  Run: `./mvnw -pl backend flyway:migrate`, then
  `docker exec infra-postgres-1 psql -U postgres -d lifeplatform -c "\d underwriting.underwriting_case"`
  to confirm the column and constraint.

- [ ] **Step 3: Add the field to `ProposalDetails`**

  Read the full current record first (8 components, confirmed by this plan's research). Add a 9th:

```java
public record ProposalDetails(
    UUID lifeAssuredPartyId, String branch, String sourceOfBusiness,
    LocalDate proposedCommencementDate, Integer requestedTermMonths, Integer premiumPayingTermMonths,
    String premiumFrequency, List<ProposalBeneficiary> beneficiaries, BigDecimal ratePercent
) {
    public static ProposalDetails selfInsured() {
        return new ProposalDetails(null, null, null, null, null, null, null, List.of(), null);
    }
    // the existing 4-arg convenience constructor gains one more null, matching the pattern
}
```

  Fix every constructor call site the compiler now flags — the four already named above
  (`UnderwritingController.java:153`, `AgentQuotationController.java:138`,
  `CustomerProducts.java:107`, `ProposalAmendments.java:163`) each read a `ratePercent` value from
  their own request DTO (which does not have that field yet — pass `null` at each of these four
  sites for this task; Task 6 is what actually threads a real value through from the wire).

- [ ] **Step 4: Add the field and column mapping to `UnderwritingCase`**

```java
@Column(name = "proposed_rate_percent")
private BigDecimal ratePercent;

// in the constructor that currently sets requestedTermMonths/premiumPayingTermMonths/premiumFrequency:
this.ratePercent = details.ratePercent();

public BigDecimal getRatePercent() { return ratePercent; }
```

  Extend `amendTerms`:

```java
public void amendTerms(BigDecimal sumAssuredAmount, Integer requestedTermMonths, Integer premiumPayingTermMonths,
                       String premiumFrequency, LocalDate proposedCommencementDate, BigDecimal ratePercent) {
    this.sumAssuredAmount = sumAssuredAmount;
    this.requestedTermMonths = requestedTermMonths;
    this.premiumPayingTermMonths = premiumPayingTermMonths;
    this.premiumFrequency = premiumFrequency;
    this.proposedCommencementDate = proposedCommencementDate;
    this.ratePercent = ratePercent;
}
```

  Fix `ProposalAmendments.java`'s one call to `c.amendTerms(...)` to pass `a.ratePercent()` (once
  Task 6 gives `ProposalAmendments.AmendmentRequest`-equivalent a `ratePercent` field — for this
  task, pass `c.getRatePercent()` unchanged, i.e. the amendment does not yet change it).

- [ ] **Step 5: Write the failing test**

```java
@Test
void amendTermsUpdatesTheLockedRate() {
    UnderwritingCase c = /* however the existing amendTerms test already builds one */;
    c.amendTerms(new BigDecimal("2000000"), 24, 24, "MONTHLY", LocalDate.of(2026, 11, 1), new BigDecimal("4.5"));
    assertThat(c.getRatePercent()).isEqualByComparingTo("4.5");
}

@Test
void selfInsuredProposalHasNoRate() {
    assertThat(ProposalDetails.selfInsured().ratePercent()).isNull();
}
```

- [ ] **Step 6: Run it, confirm it passes**

  Run: `./mvnw -pl backend test -Dtest=UnderwritingCaseTest`

- [ ] **Step 7: Run the full underwriting module's existing suite**, since four call sites changed

  Run: `./mvnw -pl backend test -Dtest=tz.co.nlolo.lifeplatform.underwriting.**`
  Expected: PASS. A compile error at this point means one of the four call sites in Step 3 was
  missed.

- [ ] **Step 8: Commit**

```bash
git add backend/db-migrations/underwriting/V24__term_account_rate.sql \
  backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/ \
  backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting/
git commit -m "feat(underwriting): a case can name the rate it proposes for a term account"
```

---

## Task 5: Underwriting — refuse a rate outside the band

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/application/UnderwritingApiImpl.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/application/ProposalAmendments.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting/UnderwritingApiImplTest.java` (or
  wherever `refuseAnUnissuableDeposit` is already tested — search for it and add beside it)

**Interfaces:**
- Consumes: `ProductApi.resolveTermAccountPlan(UUID)` (Task 3); `ProposalDetails.ratePercent()`
  (Task 4); the existing `rejectTermOutsideProductBounds(UUID, Integer)`.
- Produces: `refuseRateOutsideTermAccountBand(UUID productVersionId, BigDecimal ratePercent)`,
  called from the same two places `refuseAnUnissuableDeposit` already runs.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void refusesARateBelowTheProductsMinimum() {
    UUID versionId = /* a term-account version published in the test's fixture, min 3.0 max 7.0 */;
    assertThatThrownBy(() -> underwritingApiImpl.refuseRateOutsideTermAccountBand(versionId, new BigDecimal("2.5")))
        .isInstanceOf(UnderwritingValidationException.class)
        .hasMessageContaining("3.0");
}

@Test
void refusesARateAboveTheProductsMaximum() {
    UUID versionId = /* same fixture */;
    assertThatThrownBy(() -> underwritingApiImpl.refuseRateOutsideTermAccountBand(versionId, new BigDecimal("7.5")))
        .isInstanceOf(UnderwritingValidationException.class)
        .hasMessageContaining("7.0");
}

@Test
void acceptsARateInsideTheBand() {
    UUID versionId = /* same fixture */;
    assertThatCode(() -> underwritingApiImpl.refuseRateOutsideTermAccountBand(versionId, new BigDecimal("5.0")))
        .doesNotThrowAnyException();
}

@Test
void refusesAMissingRateOnATermAccountProduct() {
    UUID versionId = /* same fixture */;
    assertThatThrownBy(() -> underwritingApiImpl.refuseRateOutsideTermAccountBand(versionId, null))
        .isInstanceOf(UnderwritingValidationException.class)
        .hasMessageContaining("rate");
}

@Test
void doesNothingOnAProductThatIsNotATermAccount() {
    UUID versionId = /* a plain TERM_LIFE version from an existing fixture */;
    assertThatCode(() -> underwritingApiImpl.refuseRateOutsideTermAccountBand(versionId, null))
        .doesNotThrowAnyException();
}
```

- [ ] **Step 2: Run them, confirm they fail** (method does not exist)

- [ ] **Step 3: Write the rule**, directly beside `refuseAnUnissuableDeposit` in
  `UnderwritingApiImpl.java`:

```java
/**
 * A term account's case is refused at once when issuance would refuse it, the same reasoning
 * {@link #refuseAnUnissuableDeposit} already applies to a fixed-term deposit: a rate or term
 * accepted here that the product cannot actually honour is a case that is decided and can then
 * never issue.
 */
void refuseRateOutsideTermAccountBand(UUID productVersionId, BigDecimal ratePercent) {
    AccumulationPlan termAccount = productApi.resolveTermAccountPlan(productVersionId);
    if (!termAccount.isTermAccount()) {
        return;
    }
    if (ratePercent == null) {
        throw new UnderwritingValidationException("A term account needs a rate to propose");
    }
    if (ratePercent.compareTo(termAccount.minRatePercent()) < 0 || ratePercent.compareTo(termAccount.maxRatePercent()) > 0) {
        throw new UnderwritingValidationException("This product offers a rate between "
            + termAccount.minRatePercent() + "% and " + termAccount.maxRatePercent() + "%, not " + ratePercent + "%");
    }
}
```

- [ ] **Step 4: Call it from `openIndividualCase`**, directly after the existing
  `refuseAnUnissuableDeposit(productVersionId, sumAssuredAmount, details);` line:

```java
refuseRateOutsideTermAccountBand(productVersionId, details.ratePercent());
```

- [ ] **Step 5: Call it from `ProposalAmendments.amend`**, directly after the existing
  `rules.refuseAnUnissuableDeposit(c.getProductVersionId(), a.sumAssured(), asked);` line:

```java
rules.refuseRateOutsideTermAccountBand(c.getProductVersionId(), a.ratePercent());
```

  This requires `ProposalAmendments`'s own amendment-request type (whatever `a` is typed as) to
  carry a `ratePercent()` accessor — read that type now and add it, following the same pattern its
  existing `requestedTermMonths()`/`premiumFrequency()` accessors already use, then update
  `ProposalAmendments.java:181`'s call to `c.amendTerms(...)` to pass `a.ratePercent()` as the new
  sixth argument Task 4 added.

- [ ] **Step 6: Run the tests, confirm they pass**

  Run: `./mvnw -pl backend test -Dtest=UnderwritingApiImplTest`

- [ ] **Step 7: Run the full underwriting suite**

  Run: `./mvnw -pl backend test -Dtest=tz.co.nlolo.lifeplatform.underwriting.**`

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/ \
  backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting/
git commit -m "feat(underwriting): refuse a term-account case whose rate falls outside the product's band"
```

---

## Task 6: Underwriting — request DTOs and OpenAPI

**Files:**
- Read first: `backend/api/openapi/openapi-underwriting.yaml` (the case-open and amend request
  schemas), and the Java request DTOs `UnderwritingController` and `ProposalAmendments` deserialize
  into.
- Modify: those request DTOs
- Modify: `backend/api/openapi/openapi-underwriting.yaml`
- Modify: `UnderwritingController.java`, `AgentQuotationController.java`, `CustomerProducts.java`,
  `ProposalAmendments.java` (now threading a real value instead of Task 4's placeholder `null`)

**Interfaces:**
- Consumes: `ProposalDetails` (Task 4), `refuseRateOutsideTermAccountBand` (Task 5).
- Produces: the public case-open and amend-case API request bodies gain an optional `ratePercent`
  string field. Consumed by Task 14's frontend form.

- [ ] **Step 1: Read every request DTO and the OpenAPI schema fully** to confirm field names and the
  existing decimal-string parsing helper each controller already uses for `sumAssuredAmount`.

- [ ] **Step 2: Add `ratePercent` (nullable string) to each of the four request DTOs** found in Task
  4's Step 3, and update each of the four call sites to parse it with the same helper
  `sumAssuredAmount` already uses, passing the parsed `BigDecimal` into `ProposalDetails`'s new 9th
  position instead of Task 4's placeholder `null`.

- [ ] **Step 3: Add the same field to the amend-case request DTO**, and update
  `ProposalAmendments`'s amendment-request type (Task 5, Step 5) to read it.

- [ ] **Step 4: Update `openapi-underwriting.yaml`** — add `ratePercent` (optional string, pattern
  `^\d{1,3}(\.\d{1,4})?$`, matching `sumAssuredAmount`'s own pattern) to both the case-open and
  amend-case request schemas.

- [ ] **Step 5: Write an integration test** exercising the real HTTP path: open a case for a
  term-account product with a rate inside the band (expect 201/200), one with a rate outside it
  (expect 422 with the message from Task 5).

- [ ] **Step 6: Run it, confirm it passes**

- [ ] **Step 7: Regenerate the frontend's OpenAPI types**

  Run: `cd frontend && npm run generate:api`

- [ ] **Step 8: Commit**

```bash
git add backend/api/openapi/openapi-underwriting.yaml \
  backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/ \
  frontend/src/types/api/ \
  backend/src/test/
git commit -m "feat(underwriting): accept a term account's rate on the open-case and amend-case APIs"
```

---

## Task 7: Accumulation — the `term_account` table

**Files:**
- Read first: `backend/db-migrations/accumulation/V3__deposit_period.sql` (or whatever migration
  created `deposit_period` — confirm the exact filename with
  `grep -rl "deposit_period" backend/db-migrations/accumulation/`) for the column/constraint/RLS
  pattern to mirror.
- Create: `backend/db-migrations/accumulation/V5__term_account.sql`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/domain/TermAccount.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `accumulation.term_account` table and the `TermAccount` JPA entity, consumed by Task 8's
  service.

- [ ] **Step 1: Read the `deposit_period` migration and entity fully**, to copy its exact
  tenant-isolation RLS policy and status-check shape.

- [ ] **Step 2: Write the migration**

```sql
-- V5__term_account.sql
CREATE TABLE accumulation.term_account (
    policy_number       VARCHAR(20) PRIMARY KEY REFERENCES accumulation.account(policy_number),
    tenant_id           UUID NOT NULL,
    rate_percent        NUMERIC(7,4) NOT NULL,
    maturity_date       DATE NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'RUNNING'
                            CHECK (status IN ('RUNNING', 'MATURED', 'AWAITING_PAYEE')),
    default_payee_ref   VARCHAR(100),
    closed_on           DATE,
    CHECK (rate_percent >= 0 AND rate_percent <= 100),
    CHECK ((status = 'MATURED' OR status = 'AWAITING_PAYEE') = (closed_on IS NOT NULL) OR status = 'RUNNING')
);

ALTER TABLE accumulation.term_account ENABLE ROW LEVEL SECURITY;

CREATE POLICY term_account_tenant_isolation ON accumulation.term_account
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

COMMENT ON TABLE accumulation.term_account IS
    'One row per term-account policy. Its presence is what marks an accumulation.account as a term account, exactly the way a non-empty deposit_period set marks one a fixed-term deposit. rate_percent is locked at open and never updated.';
```

  Fix the `CHECK` on `closed_on` if the `deposit_period`/`account` precedent uses a cleaner
  equivalent shape — read that precedent first per Step 1 and match it rather than inventing a new
  one if it conflicts.

- [ ] **Step 3: Run the migration**

  Run: `./mvnw -pl backend flyway:migrate`, confirm with
  `docker exec infra-postgres-1 psql -U postgres -d lifeplatform -c "\d accumulation.term_account"`.

- [ ] **Step 4: Write the JPA entity**

```java
package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "term_account", schema = "accumulation")
public class TermAccount {

    @Id
    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "rate_percent", nullable = false)
    private BigDecimal ratePercent;

    @Column(name = "maturity_date", nullable = false)
    private LocalDate maturityDate;

    @Column(name = "status", nullable = false)
    private String status = "RUNNING";

    @Column(name = "default_payee_ref")
    private String defaultPayeeRef;

    @Column(name = "closed_on")
    private LocalDate closedOn;

    protected TermAccount() {}

    public TermAccount(String policyNumber, UUID tenantId, BigDecimal ratePercent, LocalDate maturityDate,
                       String defaultPayeeRef) {
        this.policyNumber = policyNumber;
        this.tenantId = tenantId;
        this.ratePercent = ratePercent;
        this.maturityDate = maturityDate;
        this.defaultPayeeRef = defaultPayeeRef;
    }

    public void mature(String newStatus, LocalDate on) {
        this.status = newStatus;
        this.closedOn = on;
    }

    public void recordPayee(String payeeRef) {
        this.defaultPayeeRef = payeeRef;
    }

    public String getPolicyNumber() { return policyNumber; }
    public BigDecimal getRatePercent() { return ratePercent; }
    public LocalDate getMaturityDate() { return maturityDate; }
    public String getStatus() { return status; }
    public String getDefaultPayeeRef() { return defaultPayeeRef; }
    public LocalDate getClosedOn() { return closedOn; }
}
```

- [ ] **Step 5: Write the repository interface**

```java
package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.accumulation.domain.TermAccount;

public interface TermAccountRepository extends JpaRepository<TermAccount, String> {
    Optional<TermAccount> findByPolicyNumber(String policyNumber);
}
```

- [ ] **Step 6: Write an entity-mapping smoke test**

```java
@DataJpaTest
class TermAccountRepositoryTest {
    @Autowired TermAccountRepository repository;
    @Autowired TestEntityManager entityManager;

    @Test
    void persistsAndReadsBack() {
        TermAccount account = new TermAccount("POL-TEST001", UUID.randomUUID(),
            new BigDecimal("5.0"), LocalDate.of(2027, 10, 21), "+255712345678");
        entityManager.persist(/* the matching accumulation.account row this FK needs first --
            follow whatever existing DepositPeriodRepositoryTest does to satisfy the same FK */);
        repository.save(account);
        entityManager.flush();
        entityManager.clear();

        TermAccount reloaded = repository.findByPolicyNumber("POL-TEST001").orElseThrow();
        assertThat(reloaded.getRatePercent()).isEqualByComparingTo("5.0");
        assertThat(reloaded.getStatus()).isEqualTo("RUNNING");
    }
}
```

  Read `DepositPeriodRepositoryTest` (or equivalent) first to copy its exact fixture setup for the
  `accumulation.account` parent row this test's FK requires.

- [ ] **Step 7: Run it, confirm it passes**

  Run: `./mvnw -pl backend test -Dtest=TermAccountRepositoryTest`

- [ ] **Step 8: Commit**

```bash
git add backend/db-migrations/accumulation/V5__term_account.sql \
  backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/domain/TermAccount.java \
  backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/infrastructure/TermAccountRepository.java \
  backend/src/test/
git commit -m "feat(accumulation): a term_account row per locked policy"
```

---

## Task 8: Accumulation — `TermAccounts` service and the withdrawal lock

**Files:**
- Read first: `backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/application/Deposits.java`
  (the `refuseMovement` and `refuseIfLockedPension` methods, around lines 81-110) and the three call
  sites in `AccumulationApiImpl.java` (lines 105, 239, 308).
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/application/TermAccounts.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/application/AccumulationApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/accumulation/TermAccountsTest.java`

**Interfaces:**
- Consumes: `ProductApi.resolveTermAccountPlan` (Task 3); `TermAccountRepository` (Task 7); the
  existing `Accounts`/`Account` domain type `AccumulationApiImpl` already uses for `requestWithdrawal`
  etc.
- Produces: `TermAccounts.isTermAccountVersion(UUID productVersionId)`;
  `TermAccounts.refuseIfLocked(Account account)` — called ONLY at the withdrawal, transfer-out and
  surrender sites, never at top-up or transfer-in, per this plan's Global Constraints. Consumed by
  Task 9 (month-end rate lookup) and Task 10 (the maturity drain).

- [ ] **Step 1: Read `Deposits.java`'s `refuseMovement` and `refuseIfLockedPension` methods in
  full**, and every call site of `deposits.refuseMovement(account)` in `AccumulationApiImpl.java`,
  to confirm exactly which of the three (`requestWithdrawal`, `requestTopUp`, `recordTransferIn`)
  exist and which methods handle surrender and transfer-out (search for `surrender` and
  `transferOut` in the same class if they are not among the three already named).

- [ ] **Step 2: Write the failing tests**

```java
class TermAccountsTest {
    @Test
    void isTermAccountVersionReflectsTheProductsPlan() {
        UUID termAccountVersion = /* fixture: productApi stub returning AccumulationPlan.isTermAccount() == true */;
        UUID plainAccountVersion = /* fixture: a plain ACCOUNT version */;
        assertThat(termAccounts.isTermAccountVersion(termAccountVersion)).isTrue();
        assertThat(termAccounts.isTermAccountVersion(plainAccountVersion)).isFalse();
    }

    @Test
    void refusesAWithdrawalBeforeMaturity() {
        Account account = /* a term-account-kind Account fixture, maturity 2027-10-21 */;
        assertThatThrownBy(() -> termAccounts.refuseIfLocked(account))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessageContaining("2027-10-21");
    }

    @Test
    void doesNothingForAPlainAccount() {
        Account account = /* a plain ACCOUNT-kind Account fixture, no term_account row */;
        assertThatCode(() -> termAccounts.refuseIfLocked(account)).doesNotThrowAnyException();
    }

    @Test
    void doesNothingOnceMatured() {
        Account account = /* a term-account-kind Account fixture whose term_account row has status MATURED */;
        assertThatCode(() -> termAccounts.refuseIfLocked(account)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 3: Run them, confirm they fail**

- [ ] **Step 4: Write `TermAccounts`**, mirroring `Deposits`'s shape:

```java
package tz.co.nlolo.lifeplatform.accumulation.application;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.accumulation.domain.Account;
import tz.co.nlolo.lifeplatform.accumulation.domain.TermAccount;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.TermAccountRepository;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

@Service
public class TermAccounts {

    private final TermAccountRepository termAccounts;
    private final ProductApi productApi;

    public TermAccounts(TermAccountRepository termAccounts, ProductApi productApi) {
        this.termAccounts = termAccounts;
        this.productApi = productApi;
    }

    boolean isTermAccountVersion(UUID productVersionId) {
        return productApi.resolveTermAccountPlan(productVersionId).isTermAccount();
    }

    Optional<TermAccount> running(String policyNumber) {
        return termAccounts.findByPolicyNumber(policyNumber).filter(t -> "RUNNING".equals(t.getStatus()));
    }

    /**
     * Refuses a withdrawal, transfer-out or surrender before maturity. Never call this from a
     * top-up or transfer-in path -- money in is unrestricted on a term account, only money out is
     * locked (unlike a fixed-term deposit, which refuses all three).
     */
    void refuseIfLocked(Account account) {
        running(account.getPolicyNumber()).ifPresent(t -> {
            throw new AccumulationStateException(
                "This account is locked until it matures on " + t.getMaturityDate());
        });
    }
}
```

- [ ] **Step 5: Call `refuseIfLocked` from the withdrawal, transfer-out and surrender paths only**

  In `AccumulationApiImpl.java`, add `termAccounts.refuseIfLocked(account);` immediately after each
  existing `deposits.refuseMovement(account);` call in `requestWithdrawal` — **but not** in
  `requestTopUp` or `recordTransferIn`. Find the surrender-approval path (search `surrender` in the
  same class, or in `policy`'s `AccountSurrenderApproved` listener if surrender is handled outside
  `accumulation` entirely) and add the same call there.

  Inject `TermAccounts` into `AccumulationApiImpl`'s constructor alongside the existing `Deposits`
  dependency.

- [ ] **Step 6: Run the tests, confirm they pass**

  Run: `./mvnw -pl backend test -Dtest=TermAccountsTest`

- [ ] **Step 7: Run the full accumulation suite**

  Run: `./mvnw -pl backend test -Dtest=tz.co.nlolo.lifeplatform.accumulation.**`
  Expected: PASS, including every pre-existing deposit/account withdrawal/top-up/transfer test
  (confirms the new call does not fire on a plain `ACCOUNT` or `DEPOSIT` account).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/ \
  backend/src/test/java/tz/co/nlolo/lifeplatform/accumulation/
git commit -m "feat(accumulation): refuse a withdrawal, transfer-out or surrender on a term account before maturity"
```

---

## Task 9: Accumulation — month-end interest at the locked rate

**Files:**
- Read first: `backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/application/AccumulationApiImpl.java`,
  the `postMonthEnds` method starting around line 825, in full — this plan's research did not
  capture its body, so read it before writing this task's code.
- Modify: `AccumulationApiImpl.java` (`postMonthEnds`)
- Modify: `TermAccounts.java` (Task 8)
- Test: whichever existing test class already covers `postMonthEnds` for an `ACCOUNT` (search
  `grep -rln "postMonthEnds" backend/src/test/`)

**Interfaces:**
- Consumes: `TermAccounts.running(String)` (Task 8), `TermAccounts.isTermAccountVersion` (Task 8).
- Produces: month-end interest on a term-account policy is credited at its own `TermAccount.ratePercent()`,
  never the product's `rate_declaration`.

- [ ] **Step 1: Read `postMonthEnds` in full.** Identify exactly where it currently branches on
  `deposits.isDepositVersion(productVersionId)` to skip interest crediting for a deposit (per this
  plan's research, deposit accounts are skipped by the month-end run entirely — "The month-end run
  skips DEPOSIT accounts"), and exactly where it looks up the product's declared rate
  (`max(declared, guaranteed)`) for a plain `ACCOUNT`.

- [ ] **Step 2: Write the failing test**

```java
@Test
void creditsATermAccountAtItsOwnLockedRateNotTheProductsDeclaration() {
    // Arrange: a term-account policy opened at a locked rate of 4.0%, on a product whose
    // CURRENT declared rate (via rate_declaration) is 8.0% -- if the bug this test guards
    // against ever regresses, the account would be credited at 8.0% instead.
    String policyNumber = /* fixture: term-account account with balance 1,000,000, locked rate 4.0 */;
    accumulationApiImpl.postMonthEnds(policyNumber, LocalDate.of(2026, 11, 30));
    LedgerEntry interest = /* read back the posted INTEREST entry for this policy/month */;
    // One month at an effective annual 4.0%, compounded daily over 30 days -- assert the exact
    // figure the existing ACCOUNT interest test already asserts for its own 5% example, scaled
    // to 4.0%, using the same calculation this plan's interest-arithmetic test (Task 12) proves.
    assertThat(interest.getAmount()).isEqualByComparingTo(/* the computed figure */);
}
```

  Compute the exact expected figure using the same daily-compounding formula the existing ACCOUNT
  interest test already asserts (read that test's own arithmetic before writing the assertion here
  — do not invent a different rounding convention).

- [ ] **Step 3: Run it, confirm it fails** (it currently credits at the product's declared rate, not
  4.0%).

- [ ] **Step 4: Branch the rate lookup** in `postMonthEnds`: where it currently computes
  `max(declaredRate, guaranteedRate)` for the account's product version, add a check first:

```java
BigDecimal rate = termAccounts.running(policyNumber)
    .map(TermAccount::getRatePercent)
    .orElseGet(() -> /* the existing max(declared, guaranteed) expression, unchanged */);
```

  Inject `TermAccounts` into whichever class `postMonthEnds` lives on, if not already injected by
  Task 8.

- [ ] **Step 5: Run the test, confirm it passes**

- [ ] **Step 6: Run the full accumulation suite**

  Run: `./mvnw -pl backend test -Dtest=tz.co.nlolo.lifeplatform.accumulation.**`

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/ \
  backend/src/test/java/tz/co/nlolo/lifeplatform/accumulation/
git commit -m "feat(accumulation): credit month-end interest at a term account's own locked rate"
```

---

## Task 10: Accumulation — the maturity drain

**Files:**
- Read first: `backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/application/Deposits.java`,
  the full `mature(String policyNumber, LocalDate today)` method (lines 239-275) and
  `payOutAwaiting` (lines 336-349), and
  `backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/application/DepositMaturityDrain.java`
  in full.
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/application/TermAccountMaturityDrain.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/application/TermAccounts.java`
  (add `mature` and `payOutAwaiting`)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/infrastructure/PaymentEventListener.java`
  (add the new purpose string, if it enumerates the known purposes anywhere — read it to confirm
  whether it needs a change or simply matches any value opaquely)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/accumulation/TermAccountMaturityTest.java`

**Interfaces:**
- Consumes: `TermAccountRepository` (Task 7), the existing `Ledger`/posting API `Deposits.mature`
  already uses, the existing `events.publishEvent` / `policyApi.markMatured` APIs.
- Produces: `TermAccounts.mature(String policyNumber, LocalDate today)`;
  `TermAccounts.payOutAwaiting(String policyNumber, String payeeRef, String by)`. Consumed by Task 11
  (the console-facing "record payee and pay" action) and Task 15 (the frontend Account tab).

- [ ] **Step 1: Read `Deposits.mature` and `payOutAwaiting` in full**, exactly as they exist today
  (this plan's research already quotes their shape and the key branches — confirm the exact ledger
  posting calls, the `DomainEventEnvelope.of(...)` construction, and `policyApi.markMatured`'s exact
  signature by reading the real file, since this task's code must call the same APIs the same way).

- [ ] **Step 2: Write the failing tests**

```java
class TermAccountMaturityTest {
    @Test
    void maturityPostsFinalInterestThenClosesAndPaysOut() {
        // Arrange: a RUNNING term_account, maturity today, a known balance and locked rate, a
        // known default_payee_ref.
        termAccounts.mature(policyNumber, LocalDate.of(2027, 10, 21));

        TermAccount reloaded = termAccountRepository.findByPolicyNumber(policyNumber).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("MATURED");
        assertThat(reloaded.getClosedOn()).isEqualTo(LocalDate.of(2027, 10, 21));
        // Assert the ledger carries exactly one INTEREST entry (source "term-account-interest:<policyNumber>")
        // and one MATURITY entry (closing the balance to zero), and that a payout was requested
        // with purpose "TERM_ACCOUNT_MATURITY_PAYOUT".
    }

    @Test
    void maturityWithNoKnownPayeeLeavesTheAccountAwaitingOne() {
        // Arrange: same as above, but default_payee_ref is null.
        termAccounts.mature(policyNumber, LocalDate.of(2027, 10, 21));

        TermAccount reloaded = termAccountRepository.findByPolicyNumber(policyNumber).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("AWAITING_PAYEE");
        // Assert interest was still posted, but no payout was requested.
    }

    @Test
    void payOutAwaitingRecordsThePayeeAndPaysOut() {
        // Arrange: an AWAITING_PAYEE term_account from the previous test's shape.
        termAccounts.payOutAwaiting(policyNumber, "+255712345678", "staff:finance1");

        TermAccount reloaded = termAccountRepository.findByPolicyNumber(policyNumber).orElseThrow();
        assertThat(reloaded.getDefaultPayeeRef()).isEqualTo("+255712345678");
        // Assert a payout was now requested with that payee.
    }

    @Test
    void maturingTwiceForTheSamePolicyPostsInterestOnlyOnce() {
        // The posting index's own idempotency guarantee -- call mature() twice for the same
        // policy and day, assert only one INTEREST entry exists.
    }
}
```

- [ ] **Step 3: Run them, confirm they fail**

- [ ] **Step 4: Add `mature` and `payOutAwaiting` to `TermAccounts`**, mirroring
  `Deposits.mature`/`payOutAwaiting` exactly (reuse the same `ledger.post(...)` call shape, the same
  `DomainEventEnvelope.of("accumulation.PayoutRequested", ...)` shape with
  `"purpose", "TERM_ACCOUNT_MATURITY_PAYOUT"` and `"sourceRef", "term-account-maturity:" + policyNumber`,
  and the same `policyApi.markMatured(...)` call) — copy the deposit version's real method bodies
  from Step 1's read and adapt the source-ref prefix and purpose string; do not restructure its
  control flow.

- [ ] **Step 5: Write `TermAccountMaturityDrain`**, copying `DepositMaturityDrain`'s shape exactly:

```java
package tz.co.nlolo.lifeplatform.accumulation.application;

import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.common.TenantContext;

@Component
public class TermAccountMaturityDrain {

    private static final Logger log = LoggerFactory.getLogger(TermAccountMaturityDrain.class);

    private final TermAccountRepository termAccounts;
    private final TermAccounts service;

    public TermAccountMaturityDrain(TermAccountRepository termAccounts, TermAccounts service) {
        this.termAccounts = termAccounts;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${accumulation.term-account-maturity-interval-ms:3600000}",
        initialDelayString = "${accumulation.term-account-maturity-interval-ms:3600000}")
    public void drain() {
        for (Object[] row : termAccounts.findDueAcrossTenants()) {
            matureOne((String) row[0], (UUID) row[1]);
        }
    }

    void matureOne(String policyNumber, UUID tenantId) {
        TenantContext.set(tenantId);
        try {
            service.mature(policyNumber, LocalDate.now());
        } catch (Exception e) {
            log.error("Term account {} failed to mature", policyNumber, e);
        } finally {
            TenantContext.clear();
        }
    }
}
```

  Add the matching `findDueAcrossTenants()` query to `TermAccountRepository` — read
  `DepositPeriodRepository`'s own equivalent query (the one `DepositMaturityDrain.drain()` calls) and
  write the identical shape against `term_account` (status `RUNNING`, `maturity_date <= CURRENT_DATE`,
  joined to `accumulation.account` for the `tenant_id`, exactly as the deposit's own query already
  does — copy it, not reinvent it).

- [ ] **Step 6: Confirm `PaymentEventListener` needs no structural change** — per this plan's
  research it matches purposes with bare string `.equals()`, so a new purpose string needs no new
  branch unless the listener does something purpose-specific beyond generic payout confirmation.
  Read it to confirm; if it does need a branch, add it following the existing
  `"DEPOSIT_MATURITY_PAYOUT".equals(...)` pattern.

- [ ] **Step 7: Run the tests, confirm they pass**

  Run: `./mvnw -pl backend test -Dtest=TermAccountMaturityTest`

- [ ] **Step 8: Run the full accumulation suite**

  Run: `./mvnw -pl backend test -Dtest=tz.co.nlolo.lifeplatform.accumulation.**`

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/ \
  backend/src/test/java/tz/co/nlolo/lifeplatform/accumulation/
git commit -m "feat(accumulation): mature a term account automatically and pay out the balance"
```

---

## Task 11: Accumulation — the console-facing API and OpenAPI

**Files:**
- Read first: `backend/api/openapi/openapi-accumulation.yaml` (the `GET .../account/deposit` and
  `POST .../account/deposit/payout` paths) and `AccumulationController.java`'s matching endpoints.
- Create: a `GET /policies/{policyNumber}/account/term-account` endpoint in
  `AccumulationController.java` (mirroring the deposit's `GET .../account/deposit`)
- Create: a `POST /policies/{policyNumber}/account/term-account/payout` endpoint (mirroring the
  deposit's payout endpoint, calling `TermAccounts.payOutAwaiting`)
- Modify: `backend/api/openapi/openapi-accumulation.yaml`
- Create: `TermAccountResponse.java` (the response DTO, mirroring `DepositResponse.java`)
- Test: a controller-level test mirroring whichever existing test covers the deposit's equivalent
  endpoints

**Interfaces:**
- Consumes: `TermAccounts` (Tasks 8-10).
- Produces: the public `GET .../account/term-account` response (policy number, rate, maturity date,
  status, balance, default payee), consumed by Task 15's frontend.

- [ ] **Step 1: Read the deposit's `GET .../account/deposit` controller method and its response DTO
  in full.**

- [ ] **Step 2: Write `TermAccountResponse`**, mirroring `DepositResponse`'s shape — fields:
  `policyNumber` (String), `ratePercent` (String, decimal), `maturityDate` (String, ISO date),
  `status` (String), `defaultPayeeRef` (String, nullable), with a `from(TermAccount, Account)`
  static factory the same way `DepositResponse.from(...)` already combines a period with its
  account.

- [ ] **Step 3: Add the two controller endpoints**, `@PreAuthorize("hasRole('REALM_STAFF')")` exactly
  as the deposit's are, one `@GetMapping` returning 404 via `.orElseThrow()` the same way
  `AccumulationController.deposit(...)` already does, one `@PostMapping` calling
  `termAccounts.payOutAwaiting(...)`.

- [ ] **Step 4: Update `openapi-accumulation.yaml`** with both new paths, copying the deposit's own
  path definitions and substituting `term-account` for `deposit` and `TermAccountResponse` for
  `DepositResponse`.

- [ ] **Step 5: Write the failing test**, mirroring the deposit's own controller test: a 404 for a
  policy with no `term_account` row; a 200 with the expected fields for one that has one.

- [ ] **Step 6: Run it, confirm it fails, then implement until it passes**

- [ ] **Step 7: Regenerate the frontend's OpenAPI types**

  Run: `cd frontend && npm run generate:api`

- [ ] **Step 8: Commit**

```bash
git add backend/api/openapi/openapi-accumulation.yaml \
  backend/src/main/java/tz/co/nlolo/lifeplatform/accumulation/ \
  frontend/src/types/api/ \
  backend/src/test/
git commit -m "feat(accumulation): expose a term account's rate, maturity and status to the console"
```

---

## Task 12: Backend — the full lifecycle integration test

**Files:**
- Create: `backend/src/test/java/tz/co/nlolo/lifeplatform/accumulation/TermAccountLifecycleIntegrationTest.java`

**Interfaces:**
- Consumes: every backend piece from Tasks 1-11.
- Produces: nothing new — this is the proof the whole chain actually works end to end, the same role
  `DepositLifecycleIntegrationTest` plays for the fixed-term deposit.

- [ ] **Step 1: Read `DepositLifecycleIntegrationTest` in full** for its fixture-building pattern
  (how it publishes a product version, opens a case, issues a policy, and drives the HTTP layer or
  the application services directly — match whichever this existing test actually does).

- [ ] **Step 2: Write the failing test**

```java
@SpringBootTest
@AutoConfigureMockMvc
class TermAccountLifecycleIntegrationTest {

    @Test
    void aTermAccountPolicyAccruesInterestAtItsLockedRateAndMaturesAutomatically() {
        // 1. Publish an ENDOWMENT version: ACCOUNT basis, real accumulation charges (0% allocation,
        //    0 monthly fee to keep the arithmetic simple), min_rate_percent 3.0, max_rate_percent 7.0,
        //    min_term_months 12, max_term_months 60.
        // 2. Open a case naming rate 5.0, term 12, premium frequency MONTHLY -- assert 201.
        // 3. Attempt a case naming rate 9.0 -- assert 422 with the band message from Task 5.
        // 4. Decide and issue the first case. Assert the policy's accumulation.term_account row
        //    exists with rate_percent 5.0 and the right maturity_date (commencement + 12 months).
        // 5. Collect the first month's premium (PremiumCollected). Assert a CONTRIBUTION entry
        //    posts.
        // 6. Attempt a withdrawal request -- assert 422 with "locked until it matures on <date>".
        // 7. Attempt a top-up -- assert it SUCCEEDS (money in is never refused).
        // 8. Run postMonthEnds -- assert INTEREST posts at 5.0%, not whatever the product's own
        //    rate_declaration says (publish one at 8.0% first, specifically to prove it has no
        //    effect).
        // 9. Advance to the maturity date and run the maturity drain. Assert the account closes,
        //    a MATURITY entry posts for the whole balance, and a payout is requested with purpose
        //    "TERM_ACCOUNT_MATURITY_PAYOUT".
    }

    @Test
    void aMissedContributionDoesNotLapseATermAccountPolicy() {
        // Spec D5: unchanged from ACCOUNT. Open and issue a term-account policy as above, skip a
        // scheduled contribution entirely, run whatever existing arrears/lapse sweep test already
        // proves an ACCOUNT policy survives a missed contribution (copy that test's own sweep
        // invocation), and assert the policy status is still ACTIVE afterward -- proving the
        // ACCOUNT-basis lapse exception in `policy` applies here too, with no new code written for
        // it specifically.
    }

    @Test
    void theDeathBenefitIsTheHigherOfAccountValueOrAPercentOfContributions() {
        // Spec D9: unchanged from ACCOUNT. Open and issue a term-account policy, post two
        // contributions, then call whatever existing method computes the ACCOUNT death ceiling
        // (search `deathBenefitCeiling` in `benefitpayout`) and assert it returns the same figure
        // an equivalent plain-ACCOUNT policy would -- proving the term account's lock does not
        // accidentally exclude it from the existing death-benefit calculation.
    }
}
```

  Fill in each numbered step with the real MockMvc calls / service calls
  `DepositLifecycleIntegrationTest` already demonstrates the pattern for — do not leave any of the
  nine steps as a comment; every one becomes real assertions once Step 1's read is done.

- [ ] **Step 3: Run it, confirm it fails** at whichever step the current code does not yet support
  (likely none, if Tasks 1-11 are already merged — this step exists to catch an integration gap no
  unit test would).

- [ ] **Step 4: Fix any integration gap found, then re-run until it passes**

  Run: `./mvnw -pl backend test -Dtest=TermAccountLifecycleIntegrationTest`

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/java/tz/co/nlolo/lifeplatform/accumulation/TermAccountLifecycleIntegrationTest.java
git commit -m "test(accumulation): a term account's full lifecycle, published through maturity"
```

---

## Task 13: Frontend — the publish form's rate band

**Files:**
- Read first: `frontend/src/features/products/PublishVersionForm.tsx` lines 946-967 (the
  `valueBasis` select and the full `ACCOUNT` block), and
  `frontend/src/features/products/publishVersionSchema.ts` lines 1383-1423 (the `toApiRequest`
  branches for `ACCOUNT` and `DEPOSIT`).
- Modify: `frontend/src/features/products/publishVersionSchema.ts`
- Modify: `frontend/src/features/products/PublishVersionForm.tsx`
- Test: `frontend/src/features/products/PublishVersionForm.test.tsx` (or wherever the ACCOUNT block
  is already covered)

**Interfaces:**
- Consumes: the regenerated API types from Task 3 (`minRatePercent`/`maxRatePercent` on the publish
  request).
- Produces: a `'TERM_ACCOUNT'` option in the console's `valueBasis` select, visually and
  behaviourally distinct from `'ACCOUNT'`/`'DEPOSIT'`, consumed by nobody downstream (this is a leaf
  UI change) but must round-trip correctly through `toApiRequest`.

- [ ] **Step 1: Read both files in full** at the cited line ranges.

- [ ] **Step 2: Add the two new form fields to the schema**

  In `publishVersionSchema.ts`, add `minRatePercent: z.string().trim()` and
  `maxRatePercent: z.string().trim()` to the input shape (same pattern as `guaranteedRatePercent`),
  defaulted to `''` in `defaultValues`. Add a `superRefine` rule: when `valueBasis === 'TERM_ACCOUNT'`,
  both are required and `Number(maxRatePercent) >= Number(minRatePercent)`, with the message "A term
  account's maximum rate cannot be below its minimum" — mirroring this plan's backend validator's
  own wording exactly, so a console error and a server error never disagree.

- [ ] **Step 3: Add `'TERM_ACCOUNT'` to the `valueBasis` select's options**, labelled "Term account",
  positioned after `'DEPOSIT'`'s "Fixed-term deposit" option.

- [ ] **Step 4: Add the new form section**, directly after the existing
  `{valueBasis === 'ACCOUNT' && (...)}` block:

```tsx
{valueBasis === 'TERM_ACCOUNT' && (
  <div className="mt-3 space-y-3">
    <p className="text-xs text-subtle-foreground">
      Contributions arrive like a savings account. Nothing is paid out until the term ends, at a
      rate fixed for the policy when it is issued, from the band below.
    </p>
    <div className="grid grid-cols-2 gap-3">
      <FormField label="Minimum rate (% a year)" error={errors.minRatePercent?.message}>
        <Input inputMode="decimal" {...register('minRatePercent')} />
      </FormField>
      <FormField label="Maximum rate (% a year)" error={errors.maxRatePercent?.message}>
        <Input inputMode="decimal" {...register('maxRatePercent')} />
      </FormField>
    </div>
    {/* Reuse the SAME charge-row grid the ACCOUNT block already renders -- a term account
        has real accumulation charges (D8), unlike a fixed-term deposit. Copy the exact
        accountCharges.fields.map(...) block from the ACCOUNT section verbatim here, not a
        second implementation of it. */}
  </div>
)}
```

  Copy the ACCOUNT block's existing charge-row grid JSX verbatim into this new block rather than
  writing a second implementation, per Step 4's own comment — this is a real step, not a
  placeholder: open the ACCOUNT block, select its charge-grid JSX, paste it here unchanged.

- [ ] **Step 5: Update `toApiRequest`**: when `valueBasis === 'TERM_ACCOUNT'`, send the real
  `accumulation: { guaranteedRatePercent: ..., minimumBalance: ..., charges: [...], minRatePercent, maxRatePercent }`
  block (reusing the exact same mapping the `'ACCOUNT'` branch already builds, with the two new
  fields added to it) — **not** a separate top-level block, since Task 3's DTO put the two new
  fields directly on the existing accumulation request shape, not a sibling one.

- [ ] **Step 6: Write the failing test**

```tsx
test('publishing a TERM_ACCOUNT version sends the rate band on the accumulation block', async () => {
  // Render the form, select valueBasis TERM_ACCOUNT, fill minRatePercent 3.0 / maxRatePercent 7.0
  // and the required eligibility/term fields, submit, and assert the mocked publishVersion call
  // received accumulation.minRatePercent === '3.0' and accumulation.maxRatePercent === '7.0'.
});

test('a max rate below the minimum is refused inline, before submit', async () => {
  // Fill minRatePercent 7.0, maxRatePercent 3.0, blur, assert the error message renders and
  // submit is not called.
});
```

  Follow whatever existing test in this file already renders the form and fills the `ACCOUNT`
  block's fields for its own setup/submit pattern — copy that pattern, don't write a new render
  harness.

- [ ] **Step 7: Run it, confirm it fails, then implement until it passes**

  Run: `cd frontend && npx vitest run PublishVersionForm`

- [ ] **Step 8: Typecheck and lint**

  Run: `cd frontend && npx tsc --noEmit && npx eslint src/features/products/`

- [ ] **Step 9: Commit**

```bash
git add frontend/src/features/products/publishVersionSchema.ts \
  frontend/src/features/products/PublishVersionForm.tsx \
  frontend/src/features/products/PublishVersionForm.test.tsx
git commit -m "feat(products): publish a term account's rate band from the console"
```

---

## Task 14: Frontend — the underwriting case's Rate field

**Files:**
- Read first: `frontend/src/features/underwriting/openCaseForm.ts` in full (its Zod schema and
  however it validates a bound against the selected product's own min/max — this plan's research did
  not pin down the exact mechanism, so read it before writing this task's code), and
  `frontend/src/features/underwriting/OpenUnderwritingCasePage.tsx`'s `isDeposit` pattern (line 165,
  already quoted in this plan's Global Constraints research) for how a product-kind flag gates a
  field.
- Modify: `frontend/src/features/underwriting/openCaseForm.ts`
- Modify: `frontend/src/features/underwriting/OpenUnderwritingCasePage.tsx`
- Test: whichever existing test file already covers `isDeposit`'s premium-frequency gating (search
  `grep -rln "isDeposit" frontend/src/features/underwriting/*.test.tsx`)

**Interfaces:**
- Consumes: the regenerated API types from Task 6 (`ratePercent` on the case-open request), and
  whatever field the selected product's snapshot already exposes for its rate band (confirm the
  exact field name by reading the regenerated type for the product snapshot the case form already
  reads `selectedProduct` from).
- Produces: a **Rate (%)** field on the case-opening form, shown and required only for a term-account
  product, bounded by that product's min/max, following the exact pattern `isDeposit` already
  establishes for `premiumFrequency`.

- [ ] **Step 1: Read `openCaseForm.ts` in full**, and confirm the regenerated product-snapshot type's
  field name for the rate band (likely `minRatePercent`/`maxRatePercent` on the same object
  `portfolioCode` already lives on, per Task 3's wire shape).

- [ ] **Step 2: Add `ratePercent: z.string().trim()` to the schema**, defaulted to `''`, with a
  `superRefine` rule mirroring `isDeposit`'s existing conditional requirement for `premiumFrequency`:
  required and within `[selectedProduct.minRatePercent, selectedProduct.maxRatePercent]` only when
  the selected product carries a rate band; otherwise must stay blank. Word the inline error exactly
  like the backend's own message from Task 5, so a console error and a server error never disagree:
  `` `This product offers a rate between ${min}% and ${max}%, not ${entered}%` ``.

- [ ] **Step 3: Add the field to `OpenUnderwritingCasePage.tsx`**, following the exact structure the
  existing "Premium frequency" `FormField` already uses (same file, around line 565-583, already
  quoted in this plan's Global Constraints research):

```tsx
const isTermAccount = !!selectedProduct?.minRatePercent && !!selectedProduct?.maxRatePercent;

// ... directly after the existing Premium frequency FormField:
{isTermAccount && (
  <FormField
    label="Rate (%)"
    error={errors.ratePercent?.message}
    hint={`This product offers a rate between ${selectedProduct.minRatePercent}% and ${selectedProduct.maxRatePercent}%.`}
  >
    <Input inputMode="decimal" {...register('ratePercent')} />
  </FormField>
)}
```

  Use whatever the real field name from Step 1's read turns out to be if it differs from
  `minRatePercent`/`maxRatePercent` — do not guess a second time if the first guess does not match
  the generated type.

- [ ] **Step 4: Write the failing tests**

```tsx
test('a term-account product shows the Rate field, bounded by the product band', async () => {
  // Select a product fixture with minRatePercent '3.0' / maxRatePercent '7.0'. Assert the Rate
  // field renders with the hint text naming both bounds.
});

test('entering a rate outside the band shows the inline error and blocks submit', async () => {
  // Fill ratePercent '9.0' on the same fixture, blur, assert the error message and that
  // onSubmit is not called.
});

test('a non-term-account product never shows the Rate field', async () => {
  // Select a plain TERM_LIFE product fixture. Assert the Rate field does not render.
});
```

  Follow whatever existing test in this suite already selects a product fixture and asserts a
  conditional field's presence (the `isDeposit`/premium-frequency tests found in Step 1's search) —
  copy that harness.

- [ ] **Step 5: Run them, confirm they fail, then implement until they pass**

  Run: `cd frontend && npx vitest run OpenUnderwritingCasePage`

- [ ] **Step 6: Typecheck and lint**

  Run: `cd frontend && npx tsc --noEmit && npx eslint src/features/underwriting/`

- [ ] **Step 7: Commit**

```bash
git add frontend/src/features/underwriting/openCaseForm.ts \
  frontend/src/features/underwriting/OpenUnderwritingCasePage.tsx \
  frontend/src/features/underwriting/*.test.tsx
git commit -m "feat(underwriting): propose a term account's rate, bounded by the product's band"
```

---

## Task 15: Frontend — the policy's Account tab

**Files:**
- Read first: `frontend/src/features/accounts/AccountPanel.tsx` and
  `frontend/src/features/accounts/DepositSection.tsx` in full (already read earlier this session —
  re-confirm the exact `deposit?.data` presence-check pattern and the store hook shape,
  `useAccumulationStore((s) => s.deposit[policyNumber])` / `loadDeposit`).
- Create: `frontend/src/features/accounts/TermAccountSection.tsx`
- Modify: `frontend/src/features/accounts/AccountPanel.tsx`
- Modify: `frontend/src/store/accumulationStore.ts` (add the `termAccount` resource slice, mirroring
  the existing `deposit` one)
- Test: `frontend/src/features/accounts/TermAccountSection.test.tsx`

**Interfaces:**
- Consumes: the regenerated `TermAccountResponse` type (Task 11); the existing
  `createResourceSlice` pattern `accumulationStore.ts` already uses for `deposit`.
- Produces: `TermAccountSection`, rendered inside `AccountPanel` exactly where `DepositSection` is,
  guarded by presence of `termAccount?.data` instead of `deposit?.data`.

- [ ] **Step 1: Read both files and the store in full.**

- [ ] **Step 2: Add the `termAccount` resource slice to `accumulationStore.ts`**, copying the
  `deposit` slice's exact shape (state key, `loadTermAccount(policyNumber)` action calling the Task
  11 `GET` endpoint, and a `payOutTermAccountAwaiting(policyNumber, payeeRef)` action calling the
  Task 11 `POST` endpoint) — same pattern, new resource name throughout.

- [ ] **Step 3: Write `TermAccountSection`**, following `DepositSection`'s shape but simpler (no
  running-term `EarlyClosingTable`, no reinvest `MaturityPanel` — per spec D6, there is no reinvest
  choice):

```tsx
import { Field } from '@/components/Field';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { formatDate } from '@/lib/dates';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import type { TermAccountView } from '@/api/types';
import { useAccumulationStore } from '@/store/accumulationStore';

/**
 * A term account: contributions build a balance like a savings account, but nothing is paid out
 * until the maturity date, at the rate locked onto the policy at issue. No reinvest choice --
 * unlike a fixed-term deposit, maturity here is always an automatic, full payout.
 */
export function TermAccountSection({ termAccount, isFinance }: { termAccount: TermAccountView; isFinance: boolean }) {
  return (
    <div className="space-y-4">
      <dl className="grid grid-cols-1 gap-x-8 gap-y-1 rounded-md border border-border px-4 py-2 sm:grid-cols-2">
        <Field label="Status" value={<StatusBadge kind="termAccount" value={termAccount.status} />} />
        <Field label="Locked rate" value={`${termAccount.ratePercent}% a year`} />
        <Field label="Matures" value={formatDate(termAccount.maturityDate)} />
        {termAccount.defaultPayeeRef && <Field label="Pays out to" value={termAccount.defaultPayeeRef} />}
      </dl>
      <p className="text-xs text-muted-foreground">
        Nothing can be withdrawn or transferred out until it matures on {formatDate(termAccount.maturityDate)}, when
        the whole balance is paid out automatically.
      </p>
      {termAccount.status === 'AWAITING_PAYEE' && isFinance && (
        <PayeePanel policyNumber={termAccount.policyNumber} />
      )}
      {termAccount.status === 'AWAITING_PAYEE' && !isFinance && (
        <p className="text-sm">This account has matured and waits for finance to record who is paid.</p>
      )}
    </div>
  );
}

function PayeePanel({ policyNumber }: { policyNumber: string }) {
  const payOut = useAccumulationStore((s) => s.payOutTermAccountAwaiting);
  const acting = useAccumulationStore((s) => s.acting[`termAccountPayout.${policyNumber}`]);
  const form = useForm<{ payeeRef: string }>({ defaultValues: { payeeRef: '' } });

  return (
    <form
      className="space-y-3 rounded-md border border-border p-3"
      aria-label="Record payee and pay"
      onSubmit={form.handleSubmit((values) => void payOut(policyNumber, values.payeeRef))}
    >
      <p className="text-sm">This account has matured and there is no number to pay it to.</p>
      <Input inputSize="sm" {...form.register('payeeRef')} aria-label="Pay to" />
      <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
        Record payee and pay
      </Button>
    </form>
  );
}
```

  Use the real `TermAccountView` field names from the regenerated type (Task 11) if they differ from
  the names guessed above — the generated type is ground truth, this sketch is not.

  Add a `termAccount` `StatusBadge` kind to `statusBadge.ts` (three buckets: `RUNNING` → active,
  `MATURED` → success, `AWAITING_PAYEE` → warning), following the exact mapping pattern every other
  `kind` entry in that file already uses.

- [ ] **Step 4: Wire it into `AccountPanel`**, directly beside the existing
  `{deposit?.data && <DepositSection .../>}` line:

```tsx
const termAccount = useAccumulationStore((s) => s.termAccount[policyNumber]);
const loadTermAccount = useAccumulationStore((s) => s.loadTermAccount);

useEffect(() => {
  void loadTermAccount(policyNumber);
}, [policyNumber, loadTermAccount]);

// ... in the render:
{termAccount?.data && <TermAccountSection termAccount={termAccount.data} isFinance={isFinance} />}
```

  A policy account is never both a deposit and a term account (Task 2's validator refuses that
  combination at the product level), so the two sections never render together in practice — no
  extra guard needed beyond the independent presence checks already used for every other kind.

- [ ] **Step 5: Write the failing tests**

```tsx
test('shows the locked rate and maturity date', () => {
  render(<TermAccountSection termAccount={{ policyNumber: 'POL-1', ratePercent: '5.0', maturityDate: '2027-10-21', status: 'RUNNING', defaultPayeeRef: null }} isFinance={false} />);
  expect(screen.getByText('5.0% a year')).toBeInTheDocument();
  expect(screen.getByText(/matures on/i)).toBeInTheDocument();
});

test('offers no top-up or withdrawal action', () => {
  render(<TermAccountSection termAccount={{ policyNumber: 'POL-1', ratePercent: '5.0', maturityDate: '2027-10-21', status: 'RUNNING', defaultPayeeRef: null }} isFinance={true} />);
  expect(screen.queryByRole('button', { name: /withdraw|top.?up/i })).not.toBeInTheDocument();
});

test('a finance viewer sees the payee form when awaiting one', () => {
  render(<TermAccountSection termAccount={{ policyNumber: 'POL-1', ratePercent: '5.0', maturityDate: '2027-10-21', status: 'AWAITING_PAYEE', defaultPayeeRef: null }} isFinance={true} />);
  expect(screen.getByRole('button', { name: /record payee and pay/i })).toBeInTheDocument();
});

test('a non-finance viewer sees only a waiting message', () => {
  render(<TermAccountSection termAccount={{ policyNumber: 'POL-1', ratePercent: '5.0', maturityDate: '2027-10-21', status: 'AWAITING_PAYEE', defaultPayeeRef: null }} isFinance={false} />);
  expect(screen.queryByRole('button', { name: /record payee and pay/i })).not.toBeInTheDocument();
  expect(screen.getByText(/waits for finance/i)).toBeInTheDocument();
});
```

- [ ] **Step 6: Run them, confirm they fail, then implement until they pass**

  Run: `cd frontend && npx vitest run TermAccountSection`

- [ ] **Step 7: Typecheck and lint**

  Run: `cd frontend && npx tsc --noEmit && npx eslint src/features/accounts/ src/store/accumulationStore.ts`

- [ ] **Step 8: Commit**

```bash
git add frontend/src/features/accounts/TermAccountSection.tsx \
  frontend/src/features/accounts/TermAccountSection.test.tsx \
  frontend/src/features/accounts/AccountPanel.tsx \
  frontend/src/store/accumulationStore.ts \
  frontend/src/lib/statusBadge.ts
git commit -m "feat(accounts): show a term account's locked rate, maturity and payout status"
```

---

## Task 16: Frontend — end-to-end lifecycle spec

**Files:**
- Read first: `frontend/e2e/staff-fixed-term-deposit.spec.ts` in full, for the publish → open-case →
  issue → verify pattern to mirror.
- Create: `frontend/e2e/staff-term-account.spec.ts`

**Interfaces:**
- Consumes: the whole feature, backend and frontend, running against the real dev stack.
- Produces: nothing downstream — this is the final proof.

- [ ] **Step 1: Read `staff-fixed-term-deposit.spec.ts` in full.**

- [ ] **Step 2: Write the spec**

```ts
import { test, expect } from '@playwright/test';
import { adminSession } from './adminSession';

test.describe('a term account', () => {
  test('is published with a rate band, issued at a rate inside it, and shows the locked rate on the Account tab', async ({ page }) => {
    const admin = await adminSession(page);

    // 1. Publish a new ENDOWMENT product version: Value basis "Term account", minimum rate 3.0,
    //    maximum rate 7.0, minimum term 12 months, maximum term 60 months, following the exact
    //    navigation staff-fixed-term-deposit.spec.ts already demonstrates for publishing a
    //    deposit version, substituting the new field labels from Task 13.
    // 2. Open an underwriting case for a fresh applicant on that product, Rate 5.0, Term 24,
    //    Premium frequency Monthly. Assert the case opens (not refused).
    // 3. Attempt a second case with Rate 9.0 on the same product. Assert it is refused with the
    //    band message.
    // 4. Decide and issue the first case, following whatever existing e2e helper
    //    (e2e/underwriting.ts) already drives an UNDERWRITING_OVERRIDE / MIGRATION issue path.
    // 5. Navigate to the issued policy's Account tab. Assert "5.0% a year" and the maturity date
    //    are both visible, and that no "Request withdrawal" or "Request top-up" button is
    //    offered.
  });
});
```

  Fill in each of the five numbered steps with real Playwright calls, copying
  `staff-fixed-term-deposit.spec.ts`'s own helpers (`selectUnderwritingCase`, the publish-form
  navigation, the issue-policy flow) rather than re-deriving them.

- [ ] **Step 3: Run it against the real dev stack**

  Confirm the backend and frontend dev servers and the Docker infra stack (Postgres, Keycloak) are
  running first (per this project's own established e2e preconditions), then run:
  `cd frontend && npx playwright test e2e/staff-term-account.spec.ts --reporter=line`
  Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e/staff-term-account.spec.ts
git commit -m "test(e2e): a term account is published, issued at a rate inside its band, and locked on the Account tab"
```

---

## Self-review notes (for whoever executes this plan)

- Tasks 1-12 are backend-only and can be executed and merged independently of 13-16; the frontend
  tasks depend on the OpenAPI/type-generation steps inside Tasks 3, 6 and 11, so they cannot start
  until those three have at least reached their "regenerate types" step.
- Three tasks (5's `ProposalAmendments` amendment-request type, 8's exact withdrawal/transfer-out/
  surrender call sites, 9's `postMonthEnds` body, 13's `openCaseForm.ts` dynamic-bound mechanism)
  depend on reading real code this plan's own research pass did not fully capture verbatim — each of
  those tasks opens with an explicit read-first step for exactly that reason. Do not skip those
  reads; do not invent the missing signatures.
- Every new user-facing refusal message was written to match its sibling (the deposit's lock
  message, the deposit's band-refusal message) so the console never shows a different shape of error
  for the same kind of mistake on two different products.
