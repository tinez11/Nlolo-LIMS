# Product step 5 — immediate annuities (D1) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task (the user prefers inline execution with no subagents). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A single premium buys an income for life on an ANNUITY version, priced from the version's rate table as configurable forms, paid by benefitpayout with tax withheld by finance-approved rules, ended or continued correctly at death, cancellable in free-look and never surrenderable.

**Architecture:** Product owns the forms, grids, frequency factors and a **pure pricer** (§R1). A new `annuity` module owns the contract and its decisions: create on issue, lock at collection, death outcomes, free-look, the policy's terminal status. Benefitpayout stays the only place instalments are scheduled, approved and paid; it gains an open-ended rolling stream and gross/withheld/net. Claims routes an annuity death's ceiling to `annuity::api`. Finaccounting books the withholding line.

**Tech Stack:** Spring Boot 3 / Spring Modulith, JPA with `ddl-auto: none`, hand-written SQL migrations per module, Postgres 16 Testcontainers, React + Zustand + zod console, Playwright e2e.

**Spec:** `backend/docs/superpowers/specs/2026-10-03-product-step5-annuities-d1-design.md` (0a8e1b84). Where this plan and the spec differ, the plan wins; differences are listed in §R.

## Global Constraints

- The user's ten answers in spec §2 bind, verbatim. In particular: **all six options as configurable combinations, never hard-coded products**; timing `ARREARS`/`ADVANCE` **with no default**; the income **locks on the collection date**; guarantee remainder is **continued instalments**; withholding rules are **data, two-person**; **no surrender, loan, cash value or paid-up on ANNUITY**; GL gets a withholding line and **no reserve**.
- Migrations, next free numbers (checked 2026-10-03): **product V22, underwriting V14, policy V33, benefitpayout V2 and V3, payment V11, finaccounting V8, annuity V1** (new schema). Before writing any column on an existing table, grep that table's CHECKs (step 4 L8).
- **Gate new-table reads on product category first** (step 4 L6, tightened): `ProductApi.resolveAnnuityPlan` looks at the product's category before touching any annuity table, so no existing test class needs product V22 in its list. Annuity tables are read only after a yes. Every listener in `annuity` filters on its event type, then asks product.
- One shared migration list for every new test class: `annuity/AnnuityTestMigrations.ALL` = `DepositTestMigrations.ALL` + product V22, underwriting V14, policy V33, benefitpayout V2, V3, payment V11, finaccounting V8, annuity V1, plus the claims list.
- "Today" is the civil date: `LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"))` in Java, `todayIso()` in e2e. Never `LocalDate.now()` bare, never `toISOString().slice(0,10)` (UTC-vs-civil bug; it broke step 4's e2e at 01:20).
- `@Scheduled` drains carry `initialDelayString` equal to their interval (step 0).
- Money is `NUMERIC(19,2)`; rates and factors `NUMERIC(9,4)`; every rounding is `HALF_EVEN` to cents, **once**.
- `-Dtest` takes commas. Run each task's own classes; `./mvnw -o clean test-compile` after any signature change (incremental compile hides test breaks). Stop the dev backend and its orphan JVMs (never the `redhat.java` ones) before `clean`.
- Never run Prettier. Edit files with Write/Edit, not shell strings. Zustand selectors never return a fresh object or array.
- Every response field in OpenAPI and asserted by name in a contract test (step 4 L9). Every console form field renders its own error, and a schema test asserts every validator message reaches a rendered path (L10).
- Commit at the end of each task. End commit messages with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

---

## File structure

```
backend/db-migrations/
  product/V22__annuity_terms.sql                 forms, grids, frequencies, terms
  underwriting/V14__annuity_choice.sql           the case's chosen form, frequency, joint life; age evidence on decision
  policy/V33__annuity_ended_status.sql           ANNUITY_ENDED terminal status
  benefitpayout/V2__annuity_streams.sql          ANNUITY kind, open-ended stream columns
  benefitpayout/V3__withholding.sql              withholding_rule, instalment gross/withheld/net
  payment/V11__annuity_purpose.sql               ANNUITY_PAYOUT purpose
  finaccounting/V8__withholding_tax_account.sql  2230 Withholding Tax Payable for every seeded tenant
  annuity/V1__create_annuity_schema.sql          contract, request_key

backend/src/main/java/tz/co/nlolo/lifeplatform/
  product/api/       AnnuityPlan, AnnuityForm, AnnuityRateRow, AnnuityFrequencyFactor, AnnuityTiming,
                     AnnuityRateBasis, AnnuityPricingInput, AnnuityPrice, AnnuityPricingRefusedException
  product/domain/    AnnuityPlanValidator, AnnuityPricer, VersionAnnuityTerms, AnnuityFormEntity,
                     AnnuityRateEntry, AnnuityFrequencyEntry
  product/infrastructure/  AnnuityRequest (+ repositories)
  underwriting/...   AnnuityChoice (api), AnnuityChoiceEntity + repo, decide light path
  annuity/           NEW MODULE
    api/             AnnuityApi, AnnuityContractView, ContractStatus, DeathValue
    domain/          AnnuityContract, ContractStatus transitions, DeathOutcome (pure)
    application/     AnnuityApiImpl, PolicyEventListener, BillingEventListener, ClaimEventListener,
                     AnnuityEnvelopeRunner
    infrastructure/  AnnuityContractRepository, AnnuityController, AnnuityExceptionHandler, responses
  benefitpayout/...  openAnnuityStream, AnnuityRollForward, end/reduce/redirect, WithholdingRule(+service)
  claims/...         deceasedPartyId on DeathClaimDetails, ceilingFor → annuity, zero ceiling for ANNUITY
  finaccounting/...  PayoutPaid split posting, blueprint account 2230

frontend/src/
  api/annuity.ts, api/withholding.ts, store/annuityStore.ts, store/withholdingStore.ts
  features/products/annuityPlanSchema.ts (+test), PublishVersionForm.tsx (Annuity section)
  features/underwriting/ (case form: annuity choice + live quote; decision: age evidence)
  features/annuities/PolicyAnnuityPanel.tsx (+test)
  features/finance/WithholdingRulesPanel.tsx, gates/withholdingGates.ts (+test)
frontend/e2e/staff-annuity.spec.ts
```

---

### Task 1: Product — annuity terms on a version, and the pricer

**Files:**
- Create: `db-migrations/product/V22__annuity_terms.sql`
- Create: `product/api/AnnuityPlan.java`, `AnnuityForm.java`, `AnnuityRateRow.java`, `AnnuityFrequencyFactor.java`, `AnnuityTiming.java`, `AnnuityRateBasis.java`, `AnnuityPricingInput.java`, `AnnuityPrice.java`, `AnnuityPricingRefusedException.java`
- Create: `product/domain/AnnuityPlanValidator.java`, `product/domain/AnnuityPricer.java`, entities `VersionAnnuityTerms`, `AnnuityFormEntity`, `AnnuityRateEntry`, `AnnuityFrequencyEntry`; repositories in `product/infrastructure`
- Create: `product/infrastructure/AnnuityRequest.java`
- Modify: `product/api/ProductApi.java` (10th `publishVersion`, `resolveAnnuityPlan`, `priceAnnuity`), `product/application/ProductApiImpl.java`, `product/infrastructure/PublishVersionRequest.java` (`@Valid AnnuityRequest annuity`), `ProductController.java:132`, `product/domain/PayoutPlanValidator.java:19-21` (ANNUITY joins `INDIVIDUAL`), `api/openapi/openapi-product.yaml`
- Test: `product/AnnuityPlanValidatorTest.java`, `product/AnnuityPricerTest.java`, extend `ProductApiIntegrationTest` (overload guard 9 → 10; publish + resolve + price), extend `ProductContractTest`

**Interfaces — Produces:**
```java
public enum AnnuityTiming { ARREARS, ADVANCE }
public enum AnnuityRateBasis { UNISEX, BY_SEX }
public record AnnuityForm(String formCode, int guaranteeYears, boolean joint, BigDecimal survivorPercent,
                          BigDecimal escalationPercent, boolean capitalProtected, AnnuityRateBasis rateBasis,
                          List<AnnuityRateRow> rates) {}
/** sex null on UNISEX; ageDifferenceFrom/To null on a single-life form; difference = annuitant age − joint age. */
public record AnnuityRateRow(String sex, int age, Integer ageDifferenceFrom, Integer ageDifferenceTo,
                             BigDecimal annualRatePerMille) {}
public record AnnuityFrequencyFactor(String frequency, BigDecimal factor) {}
public record AnnuityPlan(boolean annuity, AnnuityTiming timing, int proofOfLifeIntervalMonths,
                          Integer jointAgeDifferenceMin, Integer jointAgeDifferenceMax,
                          String basisReference, LocalDate basisDate,
                          List<AnnuityForm> forms, List<AnnuityFrequencyFactor> frequencies) {
    public static AnnuityPlan none();
    public Optional<AnnuityForm> form(String formCode);
}
public record AnnuityPricingInput(String formCode, String frequency, BigDecimal purchasePrice,
                                  LocalDate annuitantDateOfBirth, String annuitantSex,
                                  LocalDate jointDateOfBirth, String jointSex, LocalDate pricingDate) {}
public record AnnuityPrice(String formCode, String frequency, int annuitantAge, Integer jointAge,
                           Integer ageDifference, String rateSex, BigDecimal annualRatePerMille,
                           BigDecimal factor, BigDecimal annualIncome, BigDecimal instalment,
                           int paymentsPerYear, AnnuityTiming timing) {}
// ProductApi
AnnuityPlan resolveAnnuityPlan(UUID productVersionId);                  // none() unless category ANNUITY
AnnuityPrice priceAnnuity(UUID productVersionId, AnnuityPricingInput in); // throws AnnuityPricingRefusedException
```

- [ ] **Step 1: The migration** (`V22__annuity_terms.sql`)

```sql
-- db-migrations/product/V22__annuity_terms.sql
-- Product step 5 (D1): an ANNUITY version's forms, rate grids and frequency factors. Separate
-- tables, for V19's and V21's reason, and read only after the product's category says ANNUITY,
-- so no other version and no existing test class reads them.
CREATE TABLE product.version_annuity_terms (
    product_version_id           UUID PRIMARY KEY REFERENCES product.product_version(product_version_id),
    tenant_id                    UUID NOT NULL,
    -- No default, on purpose (spec Q5): whether the first payment is a period after purchase or at it
    -- is a contract term the version states.
    timing                       VARCHAR(10) NOT NULL CHECK (timing IN ('ARREARS','ADVANCE')),
    proof_of_life_interval_months INTEGER NOT NULL CHECK (proof_of_life_interval_months BETWEEN 1 AND 24),
    joint_age_difference_min     INTEGER,
    joint_age_difference_max     INTEGER,
    basis_reference              VARCHAR(100) NOT NULL,
    basis_date                   DATE NOT NULL,
    CHECK ((joint_age_difference_min IS NULL) = (joint_age_difference_max IS NULL)),
    CHECK (joint_age_difference_min IS NULL OR joint_age_difference_min <= joint_age_difference_max)
);

CREATE TABLE product.annuity_form (
    annuity_form_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    product_version_id  UUID NOT NULL REFERENCES product.product_version(product_version_id),
    form_code           VARCHAR(30) NOT NULL,
    guarantee_years     INTEGER NOT NULL CHECK (guarantee_years BETWEEN 0 AND 30),
    joint               BOOLEAN NOT NULL,
    survivor_percent    NUMERIC(9,4),
    escalation_percent  NUMERIC(9,4) NOT NULL CHECK (escalation_percent BETWEEN 0 AND 10),
    capital_protected   BOOLEAN NOT NULL,
    rate_basis          VARCHAR(10) NOT NULL CHECK (rate_basis IN ('UNISEX','BY_SEX')),
    CHECK ((joint AND survivor_percent BETWEEN 1 AND 100) OR (NOT joint AND survivor_percent IS NULL))
);
CREATE UNIQUE INDEX ux_annuity_form_code ON product.annuity_form (product_version_id, form_code);

CREATE TABLE product.annuity_rate_row (
    annuity_rate_row_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL,
    annuity_form_id        UUID NOT NULL REFERENCES product.annuity_form(annuity_form_id),
    sex                    VARCHAR(10) CHECK (sex IN ('FEMALE','MALE')),
    age                    INTEGER NOT NULL CHECK (age BETWEEN 0 AND 120),
    age_difference_from    INTEGER,
    age_difference_to      INTEGER,
    annual_rate_per_mille  NUMERIC(9,4) NOT NULL CHECK (annual_rate_per_mille > 0),
    CHECK ((age_difference_from IS NULL) = (age_difference_to IS NULL)),
    CHECK (age_difference_from IS NULL OR age_difference_from <= age_difference_to)
);
CREATE INDEX ix_annuity_rate_row_form ON product.annuity_rate_row (annuity_form_id, age);

CREATE TABLE product.annuity_frequency (
    product_version_id UUID NOT NULL REFERENCES product.product_version(product_version_id),
    tenant_id          UUID NOT NULL,
    frequency          VARCHAR(12) NOT NULL CHECK (frequency IN ('MONTHLY','QUARTERLY','SEMI_ANNUAL','ANNUAL')),
    factor             NUMERIC(9,4) NOT NULL CHECK (factor > 0 AND factor <= 1),
    PRIMARY KEY (product_version_id, frequency),
    CHECK (frequency <> 'ANNUAL' OR factor = 1)
);

ALTER TABLE product.version_annuity_terms ENABLE ROW LEVEL SECURITY;
CREATE POLICY version_annuity_terms_tenant_isolation ON product.version_annuity_terms
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.annuity_form ENABLE ROW LEVEL SECURITY;
CREATE POLICY annuity_form_tenant_isolation ON product.annuity_form
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.annuity_rate_row ENABLE ROW LEVEL SECURITY;
CREATE POLICY annuity_rate_row_tenant_isolation ON product.annuity_rate_row
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.annuity_frequency ENABLE ROW LEVEL SECURITY;
CREATE POLICY annuity_frequency_tenant_isolation ON product.annuity_frequency
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON product.version_annuity_terms, product.annuity_form,
    product.annuity_rate_row, product.annuity_frequency TO app_role;
```

- [ ] **Step 2: Write the failing validator tests** (`AnnuityPlanValidatorTest`, pure, no Spring). One test per message, each asserting the exact text. The fixture:

```java
    private static final EligibilityBounds AGES_60_TO_62 = new EligibilityBounds(60, 62, null, null, null, null);

    private static AnnuityRateRow unisex(int age, String rate) {
        return new AnnuityRateRow(null, age, null, null, new BigDecimal(rate));
    }

    private static AnnuityForm lifeOnly() {
        return new AnnuityForm("LIFE-0G", 0, false, null, BigDecimal.ZERO, false, AnnuityRateBasis.UNISEX,
            List.of(unisex(60, "72.0000"), unisex(61, "74.0000"), unisex(62, "76.0000")));
    }

    private static AnnuityPlan plan(List<AnnuityForm> forms) {
        return new AnnuityPlan(true, AnnuityTiming.ARREARS, 12, null, null, "ACT/ANN/2026", LocalDate.of(2026, 1, 1),
            forms, List.of(new AnnuityFrequencyFactor("MONTHLY", new BigDecimal("0.9800")),
                           new AnnuityFrequencyFactor("ANNUAL", BigDecimal.ONE)));
    }

    private static void refused(Runnable r, String message) {
        assertThatThrownBy(r::run).isInstanceOf(InvalidProductVersionException.class).hasMessage(message);
    }

    @Test void aValidLifeOnlyPlanPasses() {
        AnnuityPlanValidator.validate(ProductCategory.ANNUITY, plan(List.of(lifeOnly())), AGES_60_TO_62,
            CashValuePlan.none(), AccumulationPlan.none(), DepositPlan.none(), BonusPlan.none(), PayoutPlan.none());
    }

    @Test void annuityTermsOnlyOnAnnuity() {
        refused(() -> AnnuityPlanValidator.validate(ProductCategory.ENDOWMENT, plan(List.of(lifeOnly())), AGES_60_TO_62,
            CashValuePlan.none(), AccumulationPlan.none(), DepositPlan.none(), BonusPlan.none(), PayoutPlan.none()),
            "Annuity terms are only for an ANNUITY product");
    }

    @Test void anAnnuityVersionMustHaveTerms() {
        refused(() -> AnnuityPlanValidator.validate(ProductCategory.ANNUITY, AnnuityPlan.none(), AGES_60_TO_62,
            CashValuePlan.none(), AccumulationPlan.none(), DepositPlan.none(), BonusPlan.none(), PayoutPlan.none()),
            "An ANNUITY version must state its annuity terms: forms, rates and frequencies");
    }

    @Test void aGapInTheGridIsRefusedNamingTheCell() {
        var gappy = new AnnuityForm("LIFE-0G", 0, false, null, BigDecimal.ZERO, false, AnnuityRateBasis.UNISEX,
            List.of(unisex(60, "72"), unisex(62, "76")));
        refused(() -> AnnuityPlanValidator.validate(ProductCategory.ANNUITY, plan(List.of(gappy)), AGES_60_TO_62,
            CashValuePlan.none(), AccumulationPlan.none(), DepositPlan.none(), BonusPlan.none(), PayoutPlan.none()),
            "Form LIFE-0G has no rate for age 61");
    }
```

The full message list the test class must cover, each with its own `@Test` (copy the shape above):

| Rule | Message |
|---|---|
| terms on a non-ANNUITY category | `Annuity terms are only for an ANNUITY product` |
| ANNUITY without terms | `An ANNUITY version must state its annuity terms: forms, rates and frequencies` |
| no form | `An annuity version needs at least one annuity form` |
| no frequency | `An annuity version needs at least one payment frequency` |
| timing null | `An annuity version must state whether income is paid in ARREARS or in ADVANCE` |
| basis blank | `An annuity rate table needs the actuarial basis it was issued under` |
| cash value present | `An ANNUITY version cannot carry a cash-value table` |
| account or deposit | `An ANNUITY version cannot be valued by an account or as a deposit` |
| bonus | `An ANNUITY version cannot be with-profits` |
| payout rows | `An ANNUITY version carries no payout schedule; its income is set by its forms` |
| guarantee out of range | `Form X: a guaranteed period must be between 0 and 30 years` |
| survivor on single life | `Form X: a survivor percentage is only for a joint-life form` |
| joint without survivor / out of range | `Form X: a joint-life form needs a survivor percentage between 1 and 100` |
| escalation out of range | `Form X: escalation must be between 0% and 10% a year` |
| duplicate code | `Form code X appears more than once` |
| duplicate settings | `Forms X and Y have the same settings` |
| entry-age bounds missing | `An annuity version needs minimum and maximum entry ages, so its grids can be checked for gaps` |
| joint form without range | `A joint-life form needs the version's range of age differences` |
| gap, single life | `Form X has no rate for age N` |
| gap, by sex | `Form X has no rate for a FEMALE aged N` (and MALE) |
| gap, joint band | `Form X has no rate for age N with an age difference of D` |
| overlapping joint bands | `Form X has overlapping age-difference bands at age N` |
| sex on a UNISEX row / missing on BY_SEX | `Form X: a UNISEX form's rates carry no sex` / `Form X: a BY_SEX form's rates each name a sex` |
| frequency factor | `A frequency factor must be greater than 0 and at most 1` |
| annual factor | `The ANNUAL frequency factor is 1` |
| duplicate frequency | `Frequency F appears more than once` |
| non-positive rate | `Form X: every rate must be greater than zero` |

- [ ] **Step 3: Run it to see it fail** — `./mvnw -o test -Dtest=AnnuityPlanValidatorTest` → compilation failure (classes missing).

- [ ] **Step 4: The records and the validator.** `AnnuityPlan.none()` returns `new AnnuityPlan(false, null, 0, null, null, null, null, List.of(), List.of())`; the canonical constructor copies both lists with `List.copyOf` (null → empty). `form(code)` streams `forms`.

`AnnuityPlanValidator.validate(ProductCategory category, AnnuityPlan plan, EligibilityBounds bounds, CashValuePlan cashValue, AccumulationPlan accumulation, DepositPlan deposit, BonusPlan bonus, PayoutPlan payout)`. A version carries no premium frequency, so "single premium only" is enforced at issuance (Task 2), not here. Order: category rules, exclusions, terms-level, per-form settings, duplicates, coverage, frequencies. The coverage check, which is the only non-obvious code:

```java
    private static void checkCoverage(AnnuityPlan plan, AnnuityForm form, int minAge, int maxAge) {
        List<String> sexes = form.rateBasis() == AnnuityRateBasis.BY_SEX ? List.of("FEMALE", "MALE") : java.util.Collections.singletonList(null);
        for (AnnuityRateRow r : form.rates()) {
            if (form.rateBasis() == AnnuityRateBasis.UNISEX && r.sex() != null) {
                fail("Form " + form.formCode() + ": a UNISEX form's rates carry no sex");
            }
            if (form.rateBasis() == AnnuityRateBasis.BY_SEX && r.sex() == null) {
                fail("Form " + form.formCode() + ": a BY_SEX form's rates each name a sex");
            }
            if (r.annualRatePerMille() == null || r.annualRatePerMille().signum() <= 0) {
                fail("Form " + form.formCode() + ": every rate must be greater than zero");
            }
        }
        for (String sex : sexes) {
            for (int age = minAge; age <= maxAge; age++) {
                final int a = age;
                List<AnnuityRateRow> atAge = form.rates().stream()
                    .filter(r -> r.age() == a && java.util.Objects.equals(r.sex(), sex)).toList();
                if (!form.joint()) {
                    if (atAge.isEmpty()) {
                        fail(sex == null ? "Form " + form.formCode() + " has no rate for age " + a
                            : "Form " + form.formCode() + " has no rate for a " + sex + " aged " + a);
                    }
                    continue;
                }
                for (int d = plan.jointAgeDifferenceMin(); d <= plan.jointAgeDifferenceMax(); d++) {
                    final int diff = d;
                    long hits = atAge.stream().filter(r -> r.ageDifferenceFrom() <= diff && diff <= r.ageDifferenceTo()).count();
                    if (hits == 0) {
                        fail("Form " + form.formCode() + " has no rate for age " + a + " with an age difference of " + diff);
                    }
                    if (hits > 1) {
                        fail("Form " + form.formCode() + " has overlapping age-difference bands at age " + a);
                    }
                }
            }
        }
    }
```

Validation is a full sweep of integer ages, which is fine: entry-age bounds are at most 0–120.

- [ ] **Step 5: Write the failing pricer tests** (`AnnuityPricerTest`, pure). `AnnuityPricer.price(AnnuityPlan plan, AnnuityPricingInput in)`:

```java
    @Test void lifeOnlyMonthlyInArrears() {
        // 50,000,000 at age 60, 72 per mille a year = 3,600,000 a year; monthly factor 0.98 → 294,000.00 a month.
        var p = AnnuityPricer.price(plan(List.of(lifeOnly())), new AnnuityPricingInput("LIFE-0G", "MONTHLY",
            new BigDecimal("50000000.00"), LocalDate.of(1966, 3, 1), "FEMALE", null, null, LocalDate.of(2026, 10, 3)));
        assertThat(p.annuitantAge()).isEqualTo(60);
        assertThat(p.annualIncome()).isEqualByComparingTo("3600000.00");
        assertThat(p.instalment()).isEqualByComparingTo("294000.00");
        assertThat(p.paymentsPerYear()).isEqualTo(12);
    }

    @Test void ageIsLastBirthdayOnThePricingDate() {
        // Born 1966-10-04: still 59 on 2026-10-03.
        assertThatThrownBy(() -> AnnuityPricer.price(plan(List.of(lifeOnly())), new AnnuityPricingInput("LIFE-0G", "MONTHLY",
            new BigDecimal("1000000.00"), LocalDate.of(1966, 10, 4), null, null, null, LocalDate.of(2026, 10, 3))))
            .isInstanceOf(AnnuityPricingRefusedException.class).hasMessage("No rate for age 59 on form LIFE-0G");
    }

    @Test void aBySexFormRefusesAnUnrecordedSex() {
        assertThatThrownBy(() -> AnnuityPricer.price(plan(List.of(bySex())), input("LIFE-BS", null)))
            .isInstanceOf(AnnuityPricingRefusedException.class)
            .hasMessage("Form LIFE-BS is priced by sex and the annuitant's sex is not recorded");
    }

    @Test void jointUsesTheBandOfTheDifference() {
        // annuitant 62, joint 57 → difference 5 → band 5..9 at age 62.
    }

    @Test void anUnofferedFormOrFrequencyIsRefused() {
        // "This version does not offer form X" / "This version does not offer FREQ payments"
    }
```

Rules: `paymentsPerYear` MONTHLY 12, QUARTERLY 4, SEMI_ANNUAL 2, ANNUAL 1. `annual = price × rate ÷ 1000`, scale 2 HALF_EVEN. `instalment = price × rate × factor ÷ (1000 × paymentsPerYear)` computed at full precision and rounded HALF_EVEN to 2 **once** (not from the rounded annual). Age = `Period.between(dob, pricingDate).getYears()`. Difference = annuitant age − joint age. Joint forms require a joint DOB (`"Form X is joint-life and no joint life was given"`), and on BY_SEX a joint sex (`"... and the joint life's sex is not recorded"`). Purchase price must be > 0.

- [ ] **Step 6: The pricer** — a final class with one static `price` method implementing exactly the rules above. `AnnuityPricingRefusedException extends RuntimeException` in `product.api`.

- [ ] **Step 7: Publish, persist, resolve, price**

  - `ProductApi`: the 10th overload, ending `..., BonusPlan bonusPlan, AnnuityPlan annuityPlan, String publishedBy`. The bonus overload in `ProductApiImpl` (line ~250) becomes a one-line delegate with `AnnuityPlan.none()`, exactly as the deposit overload delegates at line 245. The guard in `ProductApiIntegrationTest` that counts overloads goes **9 → 10**.
  - In the fullest overload, after `BonusPlanValidator.validate(...)` (line ~348):
    ```java
        AnnuityPlanValidator.validate(category, annuityPlan != null ? annuityPlan : AnnuityPlan.none(), bounds,
            cashValue, effectiveAccumulation, deposit, bonusPlan, payoutPlan);
    ```
    and after `persistBonusPlan(...)`: `persistAnnuityPlan(tenantId, version.getProductVersionId(), annuityPlan);` which writes nothing for `none()`.
  - `resolveAnnuityPlan(versionId)`: **load the version, then its product; if the category is not ANNUITY return `AnnuityPlan.none()` without touching an annuity table.** Only then read the four tables. This is what keeps V22 out of every existing test class.
  - `priceAnnuity(versionId, in)`: `AnnuityPricer.price(resolveAnnuityPlan(versionId), in)`; refuses `"Product version V is not an annuity"` when `!plan.annuity()`.
  - `PayoutPlanValidator.INDIVIDUAL` gains `ProductCategory.ANNUITY` (free-look required on an annuity).
  - `AnnuityRequest` (the REST body): `timing`, `proofOfLifeIntervalMonths`, `jointAgeDifferenceMin/Max`, `basisReference`, `basisDate`, `forms[{formCode, guaranteeYears, joint, survivorPercent, escalationPercent, capitalProtected, rateBasis, rates[{sex, age, ageDifferenceFrom, ageDifferenceTo, annualRatePerMille}]}]`, `frequencies[{frequency, factor}]`; `toPlan()`. **No `default:` on any property in the OpenAPI** (openapi-typescript makes a defaulted property required). `ProductController` line 132 gains `request.annuity() != null ? request.annuity().toPlan() : AnnuityPlan.none()`.
  - OpenAPI: `annuity` on `ProductVersionSpec`, typed `[object, "null"]`, every enum including `null` where the field is nullable.
  - A read for the case form: `GET /product-versions/{versionId}/annuity` (REALM_STAFF) → the plan's timing, frequencies with factors, and forms with their four settings and rate basis — **not the rate rows**, which only the pricer needs. 404 `NOT_AN_ANNUITY` for any other version. Contract-tested by field name in `ProductContractTest`.

- [ ] **Step 8: Integration and contract tests** — in `ProductApiIntegrationTest`: publish an ANNUITY product with one life-only form and MONTHLY/ANNUAL, resolve it, price it (expect 294,000.00 as in Step 5); and `resolveAnnuityPlan` on an ENDOWMENT version returns `none()`. In `ProductContractTest`: `POST /products/{id}/versions` with an `annuity` block → 201 and strict validation; with a cash-value table alongside → 422 `"An ANNUITY version cannot carry a cash-value table"`. Both classes need product V22 in their migration lists (they publish one).

- [ ] **Step 9: Run and commit**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='AnnuityPlanValidatorTest,AnnuityPricerTest,ProductApiIntegrationTest,ProductContractTest,PayoutPlanValidatorTest'
git add db-migrations/product/V22__annuity_terms.sql src/main/java/tz/co/nlolo/lifeplatform/product src/test/java/tz/co/nlolo/lifeplatform/product api/openapi/openapi-product.yaml
git commit -m "feat(product): an ANNUITY version publishes its forms, rate grids and frequency factors -- and prices a purchase"
```

`PayoutPlanValidatorTest` is in the run because ANNUITY joined the free-look set; a test asserting ANNUITY needs no free-look would now fail and must change to say it does.

---

### Task 2: Underwriting — the annuity choice, the light path, and auto-issue at the purchase price

**Files:**
- Create: `db-migrations/underwriting/V14__annuity_choice.sql`
- Create: `underwriting/api/AnnuityChoice.java`; `underwriting/domain/AnnuityChoiceEntity.java`; `underwriting/infrastructure/AnnuityChoiceRepository.java`
- Modify: `underwriting/api/UnderwritingApi.java` (`recordAnnuityChoice`, `DecisionInput` gains `ageEvidenceConfirmed`), `underwriting/application/UnderwritingApiImpl.java` (decide light path + re-price), `UnderwritingCaseView` (gains `annuityChoice`), the case controller and request DTO, `api/openapi/openapi-underwriting.yaml`
- Modify: `policy/application/UnderwritingDecisionEventListener.java:355-436` (ANNUITY branch before the rating formula)
- Test: `underwriting/AnnuityCaseIntegrationTest.java` (new, uses `AnnuityTestMigrations.ALL`), extend `UnderwritingContractTest`

**Interfaces — Consumes:** `ProductApi.resolveAnnuityPlan`, `ProductApi.priceAnnuity`, `PartyApi.getPartyDetail(partyId).dateOfBirth()/.sex()`.
**Produces:**
```java
public record AnnuityChoice(String formCode, String frequency, UUID jointLifePartyId) {}
// UnderwritingApi
UnderwritingCaseView recordAnnuityChoice(UUID caseId, AnnuityChoice choice, String recordedBy);
record DecisionInput(DecisionOutcome outcome, BigDecimal loadingPercent, String reason, boolean ageEvidenceConfirmed) {
    public DecisionInput(DecisionOutcome outcome, BigDecimal loadingPercent, String reason) { this(outcome, loadingPercent, reason, false); }
}
// UnderwritingCaseView gains, LAST component: AnnuityChoice annuityChoice  (null for every other product)
```

`DecisionInput` keeps its three-argument constructor so no existing caller changes (widening a record breaks callers Maven may not recompile — `clean test-compile` proves it).

- [ ] **Step 1: The migration**

```sql
-- db-migrations/underwriting/V14__annuity_choice.sql
-- Product step 5 (D1): what an annuity applicant chose. Its own table, read only for an ANNUITY
-- product, so no other case reads it.
CREATE TABLE underwriting.annuity_choice (
    underwriting_case_id UUID PRIMARY KEY REFERENCES underwriting.underwriting_case(underwriting_case_id),
    tenant_id            UUID NOT NULL,
    form_code            VARCHAR(30) NOT NULL,
    frequency            VARCHAR(12) NOT NULL CHECK (frequency IN ('MONTHLY','QUARTERLY','SEMI_ANNUAL','ANNUAL')),
    joint_life_party_id  UUID,
    recorded_by          VARCHAR(100) NOT NULL,
    recorded_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE underwriting.annuity_choice ENABLE ROW LEVEL SECURITY;
CREATE POLICY annuity_choice_tenant_isolation ON underwriting.annuity_choice
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON underwriting.annuity_choice TO app_role;

-- The light path's one piece of evidence: the decider confirmed proof of age (spec §4.2).
ALTER TABLE underwriting.underwriting_case ADD COLUMN age_evidence_confirmed BOOLEAN NOT NULL DEFAULT false;
```

Check first: `grep -n "underwriting_case_id\|CREATE TABLE underwriting.underwriting_case" db-migrations/underwriting/V1*.sql` for the real PK column name, and every CHECK on `underwriting_case` (L8). A column with a DEFAULT on the existing table is safe for every test class only because the entity maps it — so **map it** on the case entity, and add V14 to every underwriting test class's list with the anchored, pattern-only node script from step 4 L5 (after V13, no blank line, print `updated N of N`).

- [ ] **Step 2: Failing tests** (`AnnuityCaseIntegrationTest`):

```java
    @Test void anAnnuityCaseIsDecidedWithAgeEvidenceAndNoAssessment() {
        var c = openAnnuityCase("LIFE-0G", "MONTHLY", null);           // fixture: ANNUITY product, ages 60-62
        var decided = asTenant(() -> underwritingApi.decide(c.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Age proven by passport", true),
            "senior-two", true));
        assertThat(decided.status()).isEqualTo(UnderwritingCaseStatus.DECIDED);
    }

    @Test void withoutAgeEvidenceAnAnnuityCannotBeAccepted() {
        // 422: "An annuity is accepted only once proof of age is confirmed"
    }

    @Test void acceptanceRepricesAndRefusesWhatCannotBePriced() {
        // a BY_SEX form for an applicant with no recorded sex:
        // 422: "Form LIFE-BS is priced by sex and the annuitant's sex is not recorded"
    }

    @Test void anAnnuityCaseWithoutAChoiceCannotBeAccepted() {
        // 422: "An annuity case must record the chosen form and frequency before it is decided"
    }

    @Test void acceptAutoIssuesAtThePurchasePriceSingle() {
        // after decide: policy PROPOSED, premiumFrequency SINGLE, premium == case sum assured (the price),
        // no maturity date.
    }

    @Test void anOrdinaryCaseStillNeedsAnAssessment() {
        // a DEMO term product case decided with no assessment → the existing refusal, unchanged.
    }
```

- [ ] **Step 3: Implementation**

  - `recordAnnuityChoice`: refuses on a non-ANNUITY product (`"Only an annuity case records an annuity choice"`), on a decided case, and on a form or frequency the version does not offer (ask `resolveAnnuityPlan`). Upserts the row. Requires the joint life on a joint form and forbids it otherwise.
  - `decide`, before the existing "at least one assessment" rule: if `productApi.resolveAnnuityPlan(versionId).annuity()`, then (a) the choice must exist, (b) on ACCEPT `ageEvidenceConfirmed` must be true, (c) the existing assessment requirement is **skipped**, (d) on ACCEPT run `productApi.priceAnnuity(versionId, input)` with the civil date and let `AnnuityPricingRefusedException` surface as `UnderwritingValidationException(e.getMessage())`. Persist `age_evidence_confirmed`. Only ACCEPT and DECLINED are allowed on an annuity (`"An annuity is accepted or declined; it is not loaded or postponed"`): there is no loading to price.
  - The case controller: `POST /underwriting/cases` accepts an optional `annuityChoice` and records it in the same request after `openCase`; `PUT /underwriting/cases/{id}/annuity-choice` changes it before decision. The decision body gains optional `ageEvidenceConfirmed`.
  - `UnderwritingDecisionEventListener`, immediately after the `schemeProduct` backstop (line ~362):
    ```java
                // An annuity is bought, not rated: its premium is the purchase price, paid once,
                // and it has no term. The income is priced by the annuity module when the money
                // arrives (product step 5).
                if (productApi.resolveAnnuityPlan(decidedCase.productVersionId()).annuity()) {
                    policyApi.issuePolicy(caseId, new PolicyApi.IssueRequest(
                        decidedCase.applicantPartyId(), decidedCase.productId(), decidedCase.productVersionId(),
                        decidedCase.sumAssuredAmount(), decidedCase.sumAssuredCurrency(),
                        decidedCase.sumAssuredAmount(), decidedCase.sumAssuredCurrency(), "SINGLE",
                        decidedCase.agentOfRecordId(), nominationsAsBeneficiaries(decidedCase),
                        "Automatic issuance on underwriting decision " + outcome,
                        decidedCase.proposedCommencementDate(), null, null, decidedCase.lifeAssuredPartyId()),
                        "system:underwriting-decision-listener");
                    return;
                }
    ```
    Match the real `IssueRequest` argument order at lines 488-500 (read it; the block above follows it). This `return` sits inside the same lambda as the existing issuance, so `clearIssuanceFailure` still runs after it.
  - `PolicyApiImpl.issuePolicy` (both the automatic and the manual path), beside the deposit guard at line ~1327: on an ANNUITY version refuse anything but `SINGLE` with premium equal to the sum assured — `"An annuity is bought with a single premium equal to its purchase price"` — and refuse a policy term (`"An annuity has no term; it pays for life"`). Test both in `AnnuityCaseIntegrationTest` through `POST /policies/manual-issue`.

- [ ] **Step 4: Run and commit**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='AnnuityCaseIntegrationTest,UnderwritingContractTest,UnderwritingApiIntegrationTest'
git commit -m "feat(underwriting): an annuity case records its form and frequency, is accepted on proof of age, re-prices at acceptance, and issues at the purchase price"
```

---

### Task 3: Benefitpayout — an open-ended annuity stream, and the calls that change it

**Files:**
- Create: `db-migrations/benefitpayout/V2__annuity_streams.sql`, `db-migrations/payment/V11__annuity_purpose.sql`
- Create: `benefitpayout/application/AnnuityRollForward.java`, `benefitpayout/domain/AnnuitySchedule.java` (pure)
- Modify: `product/api/PayoutKind.java` (+`ANNUITY`), `benefitpayout/api/BenefitPayoutApi.java`, `BenefitPayoutApiImpl.java` (`purposeFor` + four new methods), `PayoutStream.java`, `PayoutPaymentListener.java:25` (+`ANNUITY_PAYOUT`), `product/domain/PayoutPlanValidator.java` (an authored row of kind ANNUITY is refused: `"ANNUITY payouts are set by an annuity's forms, not authored as rows"`)
- Test: `benefitpayout/AnnuityScheduleTest.java` (pure), `benefitpayout/AnnuityStreamIntegrationTest.java`

**Interfaces — Produces:**
```java
// BenefitPayoutApi
UUID openAnnuityStream(String policyNumber, LocalDate firstDue, String frequency, BigDecimal baseAmount,
                       String currency, BigDecimal escalationPercent, int proofOfLifeIntervalMonths);
void endAnnuityStream(String policyNumber, LocalDate afterDate, String reason);
void reduceAnnuityStream(String policyNumber, LocalDate fromDate, BigDecimal percent);
void redirectAnnuityStream(String policyNumber, LocalDate fromDate, LocalDate untilDate, String payeeRef);
BigDecimal annuityPaidGross(String policyNumber);                                 // PAID instalments, gross
BigDecimal annuityPaidGrossDueAfter(String policyNumber, LocalDate date);          // overpayment input
BigDecimal annuityScheduledGrossBetween(String policyNumber, LocalDate fromExclusive, LocalDate toInclusive);
// AnnuitySchedule (pure)
static LocalDate dueDate(LocalDate firstDue, String frequency, int n);              // n-th instalment, 0-based
static BigDecimal amount(BigDecimal base, BigDecimal escalationPercent, LocalDate firstDue, LocalDate due, BigDecimal multiplier);
```

- [ ] **Step 1: Migrations.** Read V1's stream and instalment CHECKs first (lines 22, 36, 50).

```sql
-- db-migrations/benefitpayout/V2__annuity_streams.sql
-- Product step 5 (D1): an annuity's income is a stream with no end date, expanded a horizon at a
-- time. The base amount and escalation are the contract's locked figures; the multiplier is 1
-- until a joint life's first death sets the survivor percentage.
ALTER TABLE benefitpayout.payout_instalment DROP CONSTRAINT IF EXISTS payout_instalment_kind_check;
ALTER TABLE benefitpayout.payout_instalment ADD CONSTRAINT payout_instalment_kind_check
    CHECK (kind IN ('SURVIVAL','MATURITY','INCOME','RETURN_OF_PREMIUM','ANNUITY'));

ALTER TABLE benefitpayout.payout_stream
    ADD COLUMN open_ended          BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN frequency           VARCHAR(12),
    ADD COLUMN first_due_date      DATE,
    ADD COLUMN base_amount         NUMERIC(19,2),
    ADD COLUMN currency            VARCHAR(3),
    ADD COLUMN escalation_percent  NUMERIC(9,4),
    ADD COLUMN amount_multiplier   NUMERIC(9,4) NOT NULL DEFAULT 1,
    ADD COLUMN expanded_through    DATE,
    ADD COLUMN redirect_payee_ref  VARCHAR(100),
    ADD COLUMN redirect_until      DATE,
    ADD COLUMN proof_of_life_stopped BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT payout_stream_open_ended_shape CHECK (NOT open_ended OR
        (frequency IS NOT NULL AND first_due_date IS NOT NULL AND base_amount > 0 AND currency IS NOT NULL
         AND escalation_percent IS NOT NULL AND expanded_through IS NOT NULL));

CREATE UNIQUE INDEX ux_instalment_stream_due ON benefitpayout.payout_instalment (stream_id, due_date)
    WHERE stream_id IS NOT NULL AND kind = 'ANNUITY';

CREATE OR REPLACE FUNCTION benefitpayout.annuity_streams_due_for_rollforward(horizon DATE)
RETURNS TABLE (stream_id UUID, tenant_id UUID) LANGUAGE sql SECURITY DEFINER SET search_path = benefitpayout AS $$
    SELECT s.stream_id, s.tenant_id FROM benefitpayout.payout_stream s
     WHERE s.open_ended AND s.status IN ('PENDING_ACTIVATION','ACTIVE','SUSPENDED') AND s.expanded_through < horizon
$$;
GRANT EXECUTE ON FUNCTION benefitpayout.annuity_streams_due_for_rollforward(DATE) TO app_role;
```

Confirm the real constraint names with `\d benefitpayout.payout_instalment` on a Testcontainers DB or by reading V1 (an unnamed inline CHECK is named `<table>_<column>_check`). `MigrationTestSupport` runs as the owner, so `SECURITY DEFINER` matches how `declarations_due()` (bonus V1) and `schedules_due_for_invoicing()` (billing V9) are declared — copy theirs exactly.

```sql
-- db-migrations/payment/V11__annuity_purpose.sql
-- Copy V10's DROP/ADD of the purpose CHECK verbatim and add 'ANNUITY_PAYOUT' to the list.
```

Read `payment/V10__deposit_maturity_purpose.sql` and reproduce its statement with the one value added; do not hand-retype the existing list.

- [ ] **Step 2: Failing tests.** `AnnuityScheduleTest`:

```java
    @Test void monthlyDueDatesStepByCalendarMonth() {
        assertThat(AnnuitySchedule.dueDate(LocalDate.of(2026, 1, 31), "MONTHLY", 1)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(AnnuitySchedule.dueDate(LocalDate.of(2026, 1, 31), "MONTHLY", 2)).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test void escalationCompoundsFromTheBaseOnEachAnniversaryAndRoundsOnce() {
        LocalDate first = LocalDate.of(2026, 11, 3);
        // year 0: 294,000.00; year 1: 302,820.00; year 20: 294,000 x 1.03^20 = 530,996.70 (exact, computed)
        assertThat(AnnuitySchedule.amount(new BigDecimal("294000.00"), new BigDecimal("3"), first, first, BigDecimal.ONE))
            .isEqualByComparingTo("294000.00");
        assertThat(AnnuitySchedule.amount(new BigDecimal("294000.00"), new BigDecimal("3"), first, first.plusYears(1), BigDecimal.ONE))
            .isEqualByComparingTo("302820.00");
        assertThat(AnnuitySchedule.amount(new BigDecimal("294000.00"), new BigDecimal("3"), first, first.plusYears(20), BigDecimal.ONE))
            .isEqualByComparingTo("530996.70");
    }

    @Test void theSurvivorMultiplierAppliesAfterEscalation() {
        // 50% of year-1's 302,820.00 = 151,410.00
    }
```

The year-20 figure was computed exactly (integer arithmetic, 294,000 × 103²⁰ ÷ 100²⁰ = 530,996.70, remainder below half a cent), so it is the HALF_EVEN result.

`AnnuityStreamIntegrationTest` (uses `AnnuityTestMigrations.ALL` minus annuity V1, or all of it): open a stream for a fixture ACTIVE policy and assert 12 MONTHLY instalments, kind ANNUITY, PENDING first; run `rollForward(horizon)` twice → +N once (unique index holds); `reduceAnnuityStream` halves instalments due after the date and leaves earlier ones; `redirectAnnuityStream` sets payee on later instalments until the date and cancels those after it; `endAnnuityStream` cancels everything after the date and ends the stream; an approved instalment requests the purpose `ANNUITY_PAYOUT`.

- [ ] **Step 3: Implementation**

  - `AnnuitySchedule.dueDate`: `firstDue.plusMonths(n × monthsPer(frequency))` — `plusMonths` clamps to month end, which is the behaviour asserted above. `amount`: `years = Period.between(firstDue, due).getYears()`; `base × (1 + e/100)^years × multiplier`, `setScale(2, HALF_EVEN)` once.
  - `openAnnuityStream`: creates a `PayoutStream` with `open_ended = true` and the columns above, `expanded_through = firstDue - 1 day`, then `expand(stream, civilToday().plusMonths(12))`. The stream starts `PENDING_ACTIVATION`, as today; its first approval starts the proof-of-life clock (existing code at `approve`, line ~286).
  - `expand(stream, horizon)`: loop n from the count of existing instalments; for each due date ≤ horizon create `new PayoutInstalment(tenant, policy, PayoutKind.ANNUITY, 0, streamId, due, amount, currency)`; set `expanded_through = horizon`. Instalments whose due date is after `redirect_until` are not created; those within a redirect carry `redirect_payee_ref` as payee once reviewed.
  - `AnnuityRollForward`: `@Scheduled(fixedDelayString = "${benefitpayout.annuity-rollforward-interval-ms:86400000}", initialDelayString = "${benefitpayout.annuity-rollforward-interval-ms:86400000}")`; reads the function with horizon = civil today + 12 months; per row, sets the tenant and calls `BenefitPayoutApiImpl.rollForward(streamId, horizon)` in its own transaction with the stream row locked (`@Lock(PESSIMISTIC_WRITE)` finder, as billing's `rollForward`). Add `benefitpayout.annuity-rollforward-interval-ms: 60000` to `application-local.yml`.
  - `endAnnuityStream`: cancel (`i.cancel(reason)`) every ANNUITY instalment with due date > afterDate not PAID/IN_DOUBT; stream → ENDED.
  - `reduceAnnuityStream`: set `amount_multiplier = percent / 100`; re-amount every not-yet-APPROVED instalment due ≥ fromDate (`i.restateAmount(...)` — add it to `PayoutInstalment`, refusing once APPROVED).
  - `redirectAnnuityStream`: set `redirect_payee_ref`, `redirect_until`, `proof_of_life_stopped = true`; cancel instalments due after `untilDate`; later expansion stops at `untilDate`; once the last one is PAID the stream ends (in `markPaid`).
  - Proof of life: the existing suspend drain skips a stream with `proof_of_life_stopped`.
  - The three sum queries are plain `@Query` sums over ANNUITY instalments by status.

- [ ] **Step 4: Run and commit**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='AnnuityScheduleTest,AnnuityStreamIntegrationTest,PayoutLifecycleIntegrationTest,PayoutInstalmentTest,ScheduleExpanderTest,PayoutPlanValidatorTest'
git commit -m "feat(benefitpayout): an open-ended annuity stream, rolled forward a year at a time, that a death can end, reduce or redirect"
```

---

### Task 4: Withholding — rules as data, applied at approval, booked in the ledger

**Files:**
- Create: `db-migrations/benefitpayout/V3__withholding.sql`, `db-migrations/finaccounting/V8__withholding_tax_account.sql`
- Create: `benefitpayout/domain/WithholdingRule.java`, `benefitpayout/infrastructure/WithholdingRuleRepository.java`, `benefitpayout/application/Withholding.java`, `benefitpayout/infrastructure/WithholdingController.java`
- Modify: `PayoutInstalment.java` (gross/withheld/net, `applyWithholding`), `BenefitPayoutApiImpl.java` (approve, approveRun, `publishPayoutRequested` sends net, `markPaid` publishes gross/withheld/net), `finaccounting/domain/ChartOfAccountBlueprint.java` (+2230), `finaccounting/domain/PostingRule.java` (+`WITHHOLDING_TAX_PAYABLE`), `finaccounting/application/BenefitPayoutEventListener.java` (split posting), `api/openapi/openapi-benefitpayout.yaml`
- Test: `benefitpayout/WithholdingIntegrationTest.java`, extend the benefitpayout contract test, `finaccounting/BenefitPayoutPostingTest` (or the class that tests `PayoutPaid` posting: `grep -rl "benefitpayout.PayoutPaid" src/test/java`)

**Interfaces — Produces:**
```java
public record WithholdingRuleView(UUID ruleId, List<String> payoutKinds, BigDecimal ratePercent, LocalDate effectiveFrom,
                                  LocalDate effectiveTo, String legalReference, String status, String proposedBy,
                                  Instant proposedAt, String approvedBy, Instant approvedAt) {}
// BenefitPayoutApi
WithholdingRuleView proposeWithholdingRule(List<String> kinds, BigDecimal ratePercent, LocalDate from, LocalDate to,
                                           String legalReference, String proposedBy, String idempotencyKey);
WithholdingRuleView approveWithholdingRule(UUID ruleId, String approvedBy);
WithholdingRuleView withdrawWithholdingRule(UUID ruleId, String withdrawnBy);
List<WithholdingRuleView> listWithholdingRules();
```

- [ ] **Step 1: Migrations**

```sql
-- db-migrations/benefitpayout/V3__withholding.sql
-- Product step 5 (D1): tax withheld from a payout, by rules finance proposes and a second person
-- approves. The rule is the law's, not the product's, so it is platform data, never a version field.
CREATE TABLE benefitpayout.withholding_rule (
    rule_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL,
    payout_kinds     VARCHAR(20)[] NOT NULL CHECK (cardinality(payout_kinds) > 0),
    rate_percent     NUMERIC(9,4) NOT NULL CHECK (rate_percent > 0 AND rate_percent < 100),
    effective_from   DATE NOT NULL,
    effective_to     DATE,
    legal_reference  VARCHAR(200) NOT NULL,
    status           VARCHAR(10) NOT NULL CHECK (status IN ('PROPOSED','APPROVED','WITHDRAWN')),
    proposed_by      VARCHAR(100) NOT NULL,
    proposed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by      VARCHAR(100),
    approved_at      TIMESTAMPTZ,
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CHECK (approved_by IS NULL OR approved_by <> proposed_by)
);
ALTER TABLE benefitpayout.withholding_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY withholding_rule_tenant_isolation ON benefitpayout.withholding_rule
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON benefitpayout.withholding_rule TO app_role;

-- What an instalment paid, gross and net. Null on every instalment approved before this step:
-- nothing was withheld from them, and the gross IS current_amount.
ALTER TABLE benefitpayout.payout_instalment
    ADD COLUMN gross_amount        NUMERIC(19,2),
    ADD COLUMN withheld_amount     NUMERIC(19,2),
    ADD COLUMN net_amount          NUMERIC(19,2),
    ADD COLUMN withholding_rule_id UUID REFERENCES benefitpayout.withholding_rule(rule_id),
    ADD COLUMN withholding_checked BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT payout_instalment_withholding_sums CHECK (gross_amount IS NULL
        OR (withheld_amount >= 0 AND net_amount = gross_amount - withheld_amount));

-- Plus a request_key table for proposeWithholdingRule's Idempotency-Key, copied from bonus V1's.
```

Adding columns to `payout_instalment` that `PayoutInstalment` maps means **every class that touches benefitpayout needs V3**. That is the sweep this task costs: `grep -rl "benefitpayout/V1" src/test/java` and add V2 and V3 after V1 with the anchored node script (L5), printing `updated N of N`. There is no category gate that avoids it, because withholding is checked on every approval.

```sql
-- db-migrations/finaccounting/V8__withholding_tax_account.sql
-- 2230 Withholding Tax Payable, under 2200 Payables, for every tenant whose chart is already
-- seeded (the blueprint seeds new tenants). Copy V5's INSERT shape for one leaf exactly:
-- (code, name, parent, level 3, postable TRUE, module NULL), ON CONFLICT DO NOTHING.
```

Read V5 lines 140-160 and copy its insert for `2220` with the code, name and module changed.

- [ ] **Step 2: Failing tests** (`WithholdingIntegrationTest`):

```java
    @Test void noRuleWithholdsNothingAndSaysItChecked() {
        // approve an ANNUITY instalment of 294,000.00 with no rule: gross 294,000.00, withheld 0.00,
        // net 294,000.00, withholding_checked true; PayoutRequested amount 294,000.00
    }

    @Test void anApprovedRuleWithholdsItsRateAndTheRailIsAskedForTheNet() {
        // rule ANNUITY 10% from today, approved by a second person:
        // gross 294,000.00, withheld 29,400.00, net 264,600.00; PayoutRequested amount 264,600.00
    }

    @Test void aRuleNamesItsKinds() {
        // the same rule does not touch a MATURITY instalment
    }

    @Test void theProposerCannotApprove() {
        // 422 "A withholding rule must be approved by someone other than the person who proposed it"
    }

    @Test void twoApprovedRulesForOneKindAndDateAreRefused() {
        // 422 "An approved withholding rule already applies to ANNUITY on 2026-11-01"
    }

    @Test void aRuleStartingMidStreamAppliesFromItsDateOnly() { }
```

Posting test: a `PayoutPaid` with gross 294,000.00 / withheld 29,400.00 / net 264,600.00 posts DR 5xxx (the existing PayoutPaid debit account) 294,000.00, CR 1000 Cash 264,600.00, CR 2230 29,400.00, balanced. A legacy `PayoutPaid` with only `paidAmount` posts exactly as before.

- [ ] **Step 3: Implementation**
  - `Withholding.ruleFor(kind, dueDate)`: the one APPROVED rule whose kinds contain the kind and whose range contains the due date; overlap is refused at approval.
  - `PayoutInstalment.applyWithholding(BigDecimal ratePercent, UUID ruleId)`: `gross = currentAmount`; `withheld = gross × rate / 100` HALF_EVEN 2; `net = gross − withheld`; `checked = true`. With no rule, rate 0 and ruleId null.
  - Called in `approve` (line ~282) and in `approveRun`'s loop (line ~528) **before** `publishPayoutRequested`; `publishPayoutRequested` sends `netAmount` when present, else `currentAmount`.
  - `markPaid` publishes `paidAmount` (= net, unchanged meaning: what left the bank) plus `grossAmount`, `withheldAmount`.
  - Finaccounting: when `withheldAmount` is present and positive, build the three-leg entry directly with `new JournalEntry(...)` and `addLeg` ×3 (the calculator only does two legs); otherwise the existing two-leg path. Source ref unchanged, so a redelivery cannot post twice.
  - `WithholdingController`: `GET/POST /withholding-rules`, `POST /withholding-rules/{id}/approve|withdraw`, finance roles as in `BonusController.FINANCE`; propose needs `Idempotency-Key` (400 without), copying `BonusIdempotentRequests`.

- [ ] **Step 4: Run and commit**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='WithholdingIntegrationTest,AnnuityStreamIntegrationTest,PayoutLifecycleIntegrationTest,BenefitPayoutContractTest'
# then every class the V3 sweep touched, in batches of four
git commit -m "feat(benefitpayout): tax withheld by rules finance approves -- gross, withheld and net on every approved payout, booked to 2230"
```

---

### Task 5: The `annuity` module — the contract, created at issue and locked at payment

**Files:**
- Create: `db-migrations/annuity/V1__create_annuity_schema.sql`; add `annuity` to `scripts/migrate.sh` MODULES **and** the CI loop (step 2's lesson; `MigrationScriptCoverageTest` guards both)
- Create the module: `annuity/package-info.java`, `api/AnnuityApi.java`, `api/AnnuityContractView.java`, `api/ContractStatus.java`, `api/DeathValue.java`, `domain/AnnuityContract.java`, `application/AnnuityApiImpl.java`, `application/AnnuityEnvelopeRunner.java` (copy `BonusEnvelopeRunner`), `application/PolicyEventListener.java`, `application/BillingEventListener.java`, `infrastructure/AnnuityContractRepository.java`, `infrastructure/AnnuityController.java`, `infrastructure/AnnuityExceptionHandler.java`, `infrastructure/AnnuityContractResponse.java`, `infrastructure/AnnuityQuoteRequest.java`, `infrastructure/AnnuityQuoteResponse.java`; `api/openapi/openapi-annuity.yaml`
- Create: `src/test/java/.../annuity/AnnuityTestMigrations.java`, `AnnuityTestFixtures.java`, `AnnuityLockIntegrationTest.java`, `AnnuityContractTest.java`

**Module:** `@ApplicationModule(allowedDependencies = { "policy::api", "product::api", "underwriting::api", "party::api", "benefitpayout::api" })`.

**Interfaces — Produces:**
```java
public enum ContractStatus { AWAITING_PAYMENT, IN_PAYMENT, SURVIVOR, GUARANTEE, ENDED, CANCELLED, LOCK_FAILED }
public record AnnuityContractView(String policyNumber, ContractStatus status, String formCode, int guaranteeYears,
    boolean joint, BigDecimal survivorPercent, BigDecimal escalationPercent, boolean capitalProtected,
    UUID annuitantPartyId, UUID jointLifePartyId, BigDecimal purchasePrice, String currency, String frequency,
    LocalDate lockedOn, Integer annuitantAge, Integer jointAge, BigDecimal annualRatePerMille, BigDecimal factor,
    BigDecimal annualIncome, BigDecimal instalment, LocalDate firstDueDate, LocalDate guaranteeEndDate,
    UUID annuitantDeceasedPartyId, LocalDate firstDeathDate, LocalDate lastDeathDate,
    BigDecimal overpaymentOwed, String lockFailureReason) {}
public record DeathValue(BigDecimal capitalRefund, String currency) {}
// AnnuityApi
boolean isAnnuity(String policyNumber);                         // product first, then the contract table
Optional<AnnuityContractView> contract(String policyNumber);
AnnuityPrice quote(UUID productVersionId, String formCode, String frequency, BigDecimal price, UUID annuitantPartyId, UUID jointLifePartyId);
DeathValue deathValue(String policyNumber, UUID deceasedPartyId, LocalDate dateOfDeath);   // Task 6
```

- [ ] **Step 1: The schema**

```sql
-- db-migrations/annuity/V1__create_annuity_schema.sql
CREATE SCHEMA IF NOT EXISTS annuity;
GRANT USAGE ON SCHEMA annuity TO app_role;

CREATE TABLE annuity.contract (
    policy_number            VARCHAR(30) PRIMARY KEY,
    tenant_id                UUID NOT NULL,
    product_version_id       UUID NOT NULL,
    status                   VARCHAR(20) NOT NULL CHECK (status IN
        ('AWAITING_PAYMENT','IN_PAYMENT','SURVIVOR','GUARANTEE','ENDED','CANCELLED','LOCK_FAILED')),
    form_code                VARCHAR(30) NOT NULL,
    guarantee_years          INTEGER NOT NULL,
    joint                    BOOLEAN NOT NULL,
    survivor_percent         NUMERIC(9,4),
    escalation_percent       NUMERIC(9,4) NOT NULL,
    capital_protected        BOOLEAN NOT NULL,
    rate_basis               VARCHAR(10) NOT NULL,
    timing                   VARCHAR(10) NOT NULL,
    proof_of_life_interval_months INTEGER NOT NULL,
    frequency                VARCHAR(12) NOT NULL,
    annuitant_party_id       UUID NOT NULL,
    joint_life_party_id      UUID,
    purchase_price           NUMERIC(19,2) NOT NULL CHECK (purchase_price > 0),
    currency                 VARCHAR(3) NOT NULL,
    -- The lock (spec §4.4): every input and output, set once.
    locked_on                DATE,
    annuitant_age            INTEGER,
    joint_age                INTEGER,
    rate_sex                 VARCHAR(10),
    annual_rate_per_mille    NUMERIC(9,4),
    factor                   NUMERIC(9,4),
    annual_income            NUMERIC(19,2),
    instalment               NUMERIC(19,2),
    first_due_date           DATE,
    guarantee_end_date       DATE,
    -- Deaths (Task 6).
    first_death_party_id     UUID,
    first_death_date         DATE,
    last_death_date          DATE,
    overpayment_owed         NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (overpayment_owed >= 0),
    lock_failure_reason      VARCHAR(500),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                  BIGINT NOT NULL DEFAULT 0,
    CHECK ((joint AND joint_life_party_id IS NOT NULL) OR (NOT joint AND joint_life_party_id IS NULL)),
    CHECK (status IN ('AWAITING_PAYMENT','CANCELLED','LOCK_FAILED') OR
           (locked_on IS NOT NULL AND instalment > 0 AND first_due_date IS NOT NULL))
);
-- The lock is once-only: a locked contract's figures cannot change, even by the owner.
CREATE OR REPLACE FUNCTION annuity.contract_lock_is_final() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.locked_on IS NOT NULL AND (NEW.instalment IS DISTINCT FROM OLD.instalment
        OR NEW.annual_rate_per_mille IS DISTINCT FROM OLD.annual_rate_per_mille
        OR NEW.first_due_date IS DISTINCT FROM OLD.first_due_date
        OR NEW.locked_on IS DISTINCT FROM OLD.locked_on) THEN
        RAISE EXCEPTION 'An annuity''s locked figures never change (policy %)', OLD.policy_number;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER contract_lock_is_final BEFORE UPDATE ON annuity.contract
    FOR EACH ROW EXECUTE FUNCTION annuity.contract_lock_is_final();

ALTER TABLE annuity.contract ENABLE ROW LEVEL SECURITY;
CREATE POLICY contract_tenant_isolation ON annuity.contract
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON annuity.contract TO app_role;
```

- [ ] **Step 2: Failing tests** (`AnnuityLockIntegrationTest`, `AnnuityTestMigrations.ALL`; fixtures publish an ANNUITY product via `ProductApi` and register parties with DOB and sex):

```java
    @Test void issueCreatesAContractAwaitingPayment() {
        var issued = fixtures.issueAnnuity(TENANT, "LIFE-0G", "MONTHLY", "50000000.00");
        assertThat(asTenant(() -> annuityApi.contract(issued.policyNumber())).orElseThrow().status())
            .isEqualTo(ContractStatus.AWAITING_PAYMENT);
    }

    @Test void collectionLocksTheIncomeAndOpensTheStream() {
        var issued = fixtures.issueAnnuity(TENANT, "LIFE-0G", "MONTHLY", "50000000.00");
        fixtures.collectSinglePremium(TENANT, issued.policyNumber());
        var c = asTenant(() -> annuityApi.contract(issued.policyNumber())).orElseThrow();
        assertThat(c.status()).isEqualTo(ContractStatus.IN_PAYMENT);
        assertThat(c.instalment()).isEqualByComparingTo("294000.00");
        assertThat(c.firstDueDate()).isEqualTo(TODAY.plusMonths(1));          // ARREARS
        assertThat(asTenant(() -> benefitPayoutApi.listForPolicy(issued.policyNumber())))
            .hasSize(12).allSatisfy(i -> assertThat(i.kind()).isEqualTo(PayoutKind.ANNUITY));
    }

    @Test void inAdvanceTheFirstPaymentIsDueOnCollection() { }
    @Test void aTenYearGuaranteeEndsTenYearsAfterTheFirstPayment() { }
    @Test void aSecondCollectionEventLocksNothingTwice() { }   // publish PremiumCollected twice → one stream, figures unchanged
    @Test void anOrdinaryPolicyNeverTouchesAnAnnuityTable() { } // DEMO endowment issue + collect → no contract row, no error log
    @Test void aPricingFailureAtTheLockIsVisibleAndOpensNothing() {
        // publish a by-sex version, issue via manual-issue for a party whose sex is then cleared:
        // status LOCK_FAILED, lockFailureReason in the pricer's words, zero instalments.
    }
```

`fixtures.collectSinglePremium` drives billing's real collection path (copy how `DepositLifecycleIntegrationTest` collects a single premium) so `billing.PremiumCollected` is the real event.

- [ ] **Step 3: Implementation**
  - `PolicyEventListener` (`@Component("annuityPolicyEventListener")`, AFTER_COMMIT, through `AnnuityEnvelopeRunner`): on `policy.PolicyIssued` only, read `productVersionId`; `productApi.resolveAnnuityPlan(v).annuity()` or return. Read the choice: `underwritingApi.getCase(underwritingCaseId).annuityChoice()`; a manual issue with no case refuses into the log and onto nothing — record the contract with status `LOCK_FAILED` and reason `"An annuity is issued from an underwriting case that records its form"`. Copy form settings from the plan's form. Idempotent on policy number (`existsById`).
  - `BillingEventListener` (`@Component("annuityBillingEventListener")`): on `billing.PremiumCollected` only, `isAnnuity(policyNumber)` or return; contract must be `AWAITING_PAYMENT` or return (once-only). Build `AnnuityPricingInput` from `partyApi.getPartyDetail(...)` (map `party.api.Sex` to its name) with `pricingDate = civilToday()`; `productApi.priceAnnuity(...)`. On refusal → `markLockFailed(reason)`. On success → `contract.lock(price, firstDue, guaranteeEnd)`; then `benefitPayoutApi.openAnnuityStream(policy, firstDue, frequency, instalment, currency, escalation, proofInterval)`.
    - first due: `ARREARS` → `AnnuitySchedule.dueDate(collectionDate, frequency, 1)`; `ADVANCE` → collection date. Use `AnnuitySchedule.dueDate` from benefitpayout? It is `domain` there, not `api` — **put the month-step helper in `product.api` as `AnnuityFrequencies.monthsPer(frequency)`** and compute here with `collectionDate.plusMonths(monthsPer)`, so annuity does not reach into benefitpayout's internals.
    - guarantee end: `firstDue.plusYears(guaranteeYears)` when > 0, else null.
  - `isAnnuity(policyNumber)`: `policyApi.getPolicy(n).productVersionId()` → `resolveAnnuityPlan(...).annuity()` (product first) — only then `contracts.existsById`.
  - `AnnuityController`: `POST /annuity-quotes` (REALM_STAFF) → `AnnuityQuoteResponse` (money as `MoneyResponse` strings, rates stripped of trailing zeros); `GET /policies/{n}/annuity` → 200 or 404 `NOT_AN_ANNUITY` (copy bonus Task 7's handler and the console's null-for-404).
  - OpenAPI `openapi-annuity.yaml` with both paths; `AnnuityContractTest` asserts each field by name under strict validation.

- [ ] **Step 4: Run and commit**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='AnnuityLockIntegrationTest,AnnuityContractTest,MigrationScriptCoverageTest,ModulithArchitectureTest'
git commit -m "feat(annuity): the contract -- created from the case's choice at issue, its income locked once when the single premium arrives, and the stream opened"
```

`ModulithArchitectureTest` (or the class that runs `ApplicationModules.verify()`: `grep -rl "ApplicationModules.of" src/test/java`) proves the new module's dependencies and that nothing depends on `annuity` except `claims` (Task 6).

---

### Task 6: Death — the claim's ceiling, and what each death does

**Files:**
- Modify: `claims/api/DeathClaimDetails.java` (+`UUID deceasedPartyId`, keeping a 4-arg constructor), `claims/application/ClaimsApiImpl.java` (`ceilingFor` routes ANNUITY; registration validates the deceased on a joint form), `claims/domain/Claim.java:195` (zero allowed when the claim is an annuity's), `claims/package-info.java` (+`"annuity::api"`), the claims registration request DTO and `openapi-claims.yaml` (`deceasedPartyId`, optional)
- Create: `annuity/application/ClaimEventListener.java`, `annuity/domain/DeathArithmetic.java` (pure)
- Modify: `AnnuityApiImpl` (`deathValue`, `onDeathApproved`), `AnnuityContract` (death transitions)
- Test: `annuity/DeathArithmeticTest.java`, `annuity/AnnuityDeathIntegrationTest.java`

**Interfaces — Produces:**
```java
// DeathArithmetic (pure)
static BigDecimal capitalRefund(BigDecimal price, BigDecimal grossPaid, BigDecimal guaranteedStillToCome); // max(0, …)
static BigDecimal overpayment(BigDecimal paidForDuesAfterDeath, BigDecimal guaranteedStillToCome, BigDecimal refund);
// Claim.approve gains a fourth parameter: boolean zeroAllowed
public void approve(BigDecimal approvedAmount, String approvedCurrency, BigDecimal ceiling, boolean zeroAllowed)
// keep the three-arg form delegating with false
```

- [ ] **Step 1: Failing pure tests** (`DeathArithmeticTest`):

```java
    @Test void refundIsPriceLessPaidLessGuaranteedStillToCome() {
        assertThat(DeathArithmetic.capitalRefund(new BigDecimal("50000000.00"), new BigDecimal("10584000.00"),
            new BigDecimal("24696000.00"))).isEqualByComparingTo("14720000.00");
    }
    @Test void refundNeverNegative() {
        assertThat(DeathArithmetic.capitalRefund(new BigDecimal("1000.00"), new BigDecimal("2000.00"), BigDecimal.ZERO))
            .isEqualByComparingTo("0.00");
    }
    @Test void overpaymentIsOffsetAgainstGuaranteeThenRefund() {
        // paid after death 588,000; guaranteed still to come 1,000,000 → owed 0 (offset fully against the guarantee)
        // paid after death 588,000; nothing guaranteed; refund 300,000 → owed 288,000
    }
```

- [ ] **Step 2: Failing integration tests** (`AnnuityDeathIntegrationTest`), each through a real DEATH claim (register → assess → decide, as `PayoutLifecycleIntegrationTest.anApprovedDeathClaimEndsTheLivingBenefits` does):

  - life only: ceiling 0; approval with 0 succeeds; stream ended after the death; contract ENDED; policy `ANNUITY_ENDED` (Task 7 adds the status; until then assert the contract only, and add the policy assertion in Task 7).
  - 10-year guarantee, death in year 3: ceiling 0; instalments after the death continue to the beneficiary's payee until the guarantee end, none after; contract GUARANTEE.
  - 10-year guarantee, death in year 12: as life only.
  - joint 50%, annuitant dies first: claim names the annuitant; later instalments halve; contract SURVIVOR; no lump sum. Then the joint life dies: stream ends; contract ENDED.
  - capital protected, life only, death after 3 instalments of 294,000: ceiling `50,000,000 − 882,000 = 49,118,000.00`; claim approved at that pays it through claims settlement.
  - late notification: two instalments PAID after the date of death on a life-only, non-protected form → `overpaymentOwed` 588,000.00.
  - a joint claim naming neither life → 422 `"The deceased named on this claim is not a life on annuity POL-…"`.
  - zero approval on a NON-annuity death claim is still refused with `"Approved amount must be positive"`.

- [ ] **Step 3: Implementation**
  - `ClaimsApiImpl.ceilingFor`: first line — `if (claim.getClaimType() == ClaimType.DEATH && annuityApi.isAnnuity(claim.getPolicyNumber())) return annuityApi.deathValue(policy, deceased, dateOfEvent).capitalRefund();` where `deceased` is the details' `deceasedPartyId`, or the policy's life assured when null. `decideSettlement` passes `zeroAllowed = annuityApi.isAnnuity(...)` to `claim.approve`. `claimableCover` shows the same figure (step 4 R1: one function).
  - `deathValue`: on the **last** death only (single-life, or joint with the other life already dead), and only when `capital_protected`: `grossPaid = benefitPayoutApi.annuityPaidGross(policy)`, `guaranteed = benefitPayoutApi.annuityScheduledGrossBetween(policy, dateOfDeath, guaranteeEndDate)` (0 when no guarantee or already past), `capitalRefund(price, grossPaid, guaranteed)` — then minus any overpayment computed for that death. Otherwise zero.
  - `annuity/ClaimEventListener` (`@Component("annuityClaimEventListener")`, by envelope): `claims.ClaimApproved` with `claimType == DEATH` and `isAnnuity` → `onDeathApproved(policy, deceasedPartyId, dateOfEvent)`; once per claim id (store it on the contract's death fields; a second approval of the same claim is a no-op).
    - first of two lives: `reduceAnnuityStream(policy, nextDueAfter(date), survivorPercent)`; status SURVIVOR; record `first_death_*`.
    - last life, `date < guaranteeEndDate`: `redirectAnnuityStream(policy, nextDueAfter(date), guaranteeEndDate, beneficiaryPayee)`; status GUARANTEE. The beneficiary payee is the first primary beneficiary's phone from `policyApi`/`partyApi`; if none is recorded, redirect with a null payee so each instalment waits in review for staff to enter it (the register already requires a payee at review).
    - last life otherwise: `endAnnuityStream(policy, date, "The annuitant died on " + date)`; status ENDED; `policyApi.endAnnuity(policy)` (Task 7).
    - overpayment: `paidAfter = annuityPaidGrossDueAfter(policy, date)`; `owed = overpayment(paidAfter, guaranteedStillToCome, refund)`; stored.
  - The claims ClaimApproved payload already carries `claimId`, `policyNumber`, `claimType`, `dateOfEvent`; add `deceasedPartyId` (additive, absent = the life assured).

- [ ] **Step 4: Run and commit**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='DeathArithmeticTest,AnnuityDeathIntegrationTest,ClaimsApiIntegrationTest,ClaimsContractTest,ClaimSettlementEndToEndTest,PayoutLifecycleIntegrationTest,BonusExitIntegrationTest'
git commit -m "feat(annuity): death through the death claim -- survivor, guarantee or end, the capital refund paid by claims once, late notification recorded as owed"
```

---

### Task 7: Free-look, and the policy's terminal status

**Files:**
- Create: `db-migrations/policy/V33__annuity_ended_status.sql`
- Modify: `policy/api/PolicyStatus.java` (+`ANNUITY_ENDED`), `policy/domain/Policy.java` (`endAnnuity`), `policy/api/PolicyApi.java` + impl (`endAnnuity(String policyNumber)`), the expiry drain's function (skip ANNUITY — or confirm `policies_due_to_expire()` already skips policies with no maturity date and say so in a test), `api/openapi/openapi-policy.yaml` (enum), `benefitpayout` free-look request (server deduction line), `annuity/application/PolicyEventListener.java` (+`policy.PolicyCancelledFreeLook`)
- Modify: `frontend/src/lib/status.ts` (policy `ANNUITY_ENDED: 'neutral'`)
- Test: extend `AnnuityDeathIntegrationTest` (policy status), `annuity/AnnuityFreeLookIntegrationTest.java`

- [ ] **Step 1: Migration** — copy V31's DROP/ADD of `policy_status_check` and add `'ANNUITY_ENDED'`; update its COMMENT to list it as terminal. Adding an enum value used only by annuities needs no sweep: no entity column changes.

- [ ] **Step 2: Failing tests**
  - free-look before collection: contract CANCELLED, no stream, refund = 0 deductions beyond the existing ones.
  - free-look after collection with no instalment paid: stream ended, contract CANCELLED, the cancellation's server deduction `"Annuity income already paid"` is absent (0).
  - free-look after one paid instalment of 294,000.00 (ADVANCE timing, so one is paid at once): deduction line `"Annuity income already paid" 294,000.00` added by the server, not removable by the request.
  - after the last death on a life-only form: policy status `ANNUITY_ENDED`.
  - an ANNUITY policy is never `EXPIRED` by the drain.

- [ ] **Step 3: Implementation**
  - `Policy.endAnnuity()`: from ACTIVE only → `ANNUITY_ENDED`; publishes `policy.AnnuityEnded`; billing terminates nothing (single premium, already done).
  - benefitpayout `requestFreeLook`: if any ANNUITY instalment is PAID, append a server deduction `FreeLookDeductionInput("Annuity income already paid", annuityPaidGross(policy), null)`.
  - annuity on `policy.PolicyCancelledFreeLook`: `endAnnuityStream(policy, epoch, "Cancelled in the free-look period")` if a stream exists; status CANCELLED.

- [ ] **Step 4: Run and commit**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='AnnuityFreeLookIntegrationTest,AnnuityDeathIntegrationTest,PolicyApiIntegrationTest,PolicyContractTest'
git commit -m "feat(annuity): free-look returns the price less income paid, and an annuity that has paid its last ends as ANNUITY_ENDED, never EXPIRED"
```

---

### Task 8: The console

**Files:**
- Create: `src/api/annuity.ts`, `src/api/withholding.ts`, `src/store/annuityStore.ts`, `src/store/withholdingStore.ts`, `src/gates/withholdingGates.ts` (+test), `src/features/products/annuityPlanSchema.ts` (+test), `src/features/annuities/PolicyAnnuityPanel.tsx` (+test), `src/features/finance/WithholdingRulesPanel.tsx`, `withholdingRuleForm.ts` (+test)
- Modify: `src/api/types.ts`, `src/lib/status.ts` (`annuityContract`, `withholdingRule`), `PublishVersionForm.tsx` + `publishVersionSchema.ts` (embed the annuity section), the underwriting case form and decision panel, `PolicyDetailPage.tsx` (Annuity tab), the payouts register row (gross/withheld/net), the finance navigation (Withholding rules)

Copy the step 4 console files for shape and wording; invent nothing.

- [ ] **Step 1: Types.** `npm run generate:api`; aliases in `types.ts` beside the bonus ones: `AnnuityContractView`, `AnnuityQuoteView`, `WithholdingRuleView`, `AnnuitySpec` (the version's request block).

- [ ] **Step 2: api + stores.** `getPolicyAnnuity` returns null on 404 (`isNotFound`, as `getPolicyBonuses`). `quoteAnnuity(body)` posts `/annuity-quotes`. Withholding: list/propose (Idempotency-Key)/approve/withdraw. Stores built exactly like `bonusStore.ts`: keyed resources, `act` helper with its comment, selectors returning stored references only.

- [ ] **Step 3: Gates, test first.** `withholdingGates.approveRuleGates(rule, viewer)`: the proposer is refused with `"A withholding rule must be approved by someone other than the person who proposed it"`; a non-PROPOSED rule with `"This withholding rule is approved, not awaiting approval"`; an unknown viewer is never the proposer. Copy `bonusGates.test.ts`'s four cases with these words.

- [ ] **Step 4: The publish form.** `annuityPlanSchema.ts` mirrors `AnnuityPlanValidator` message for message (Task 1's table). Fields: `timing` (empty select, no default), `proofOfLifeIntervalMonths`, `jointAgeDifferenceMin/Max`, `annuityBasisReference`, `annuityBasisDate`, `annuityForms[]` (code, guarantee, joint, survivor %, escalation %, capital protected, rate basis, `rates[]`), `annuityFrequencies[]`. Shown only when the category is ANNUITY; the cash-value, value-basis, with-profits and payout sections are hidden on ANNUITY (the server refuses them). The rate grid editor: one row per (sex?, age, band?) with `aria-label`s `Form {n} rate row {m} age`, `... sex`, `... difference from`, `... difference to`, `... rate per mille`. A "Fill ages from entry bounds" button appends one empty row per age in the version's entry-age bounds (and per sex on BY_SEX) so an actuary types only rates. Schema tests: one per message, each landing on a rendered path (L10). The payload sends `annuity` only on ANNUITY.

- [ ] **Step 5: The case form.** On an ANNUITY product: `Annuity form` (a select of the version's forms from Task 1's `GET /product-versions/{versionId}/annuity`, each option labelled in words, e.g. "LIFE-10G — 10-year guarantee"), `Payment frequency` (the version's frequencies), `Joint life` (party picker, only on a joint form), purchase price = the existing Sum assured field relabelled `Purchase price`. A live `Income` line under the fields: debounce 400ms, call `quoteAnnuity`, show `"{formatMoney(instalment)} {frequency label}, {formatMoney(annualIncome)} a year — rate {rate} per 1,000 at age {age}"`, or the server's refusal verbatim. The decision panel shows a required `Age evidence confirmed` checkbox on an annuity case and hides loading/postpone outcomes.

- [ ] **Step 6: The Annuity tab.** `PolicyAnnuityPanel.tsx`: headline instalment and frequency; status badge (`annuityContract` kind); a fields list — form code and its settings in words ("10-year guarantee · joint, 50% to the survivor · 3% a year · capital protected"), purchase price, rate cell, locked on, first payment, next escalation (`firstDue + whole years`), guarantee end, capital-protection balance remaining (`price − gross paid`, shown only when protected), overpayment owed (only when > 0), lock failure reason (only on LOCK_FAILED, as an error panel). Present only when the read is non-null, exactly as the Account and Bonuses tabs.

- [ ] **Step 7: The register and finance.** Payout rows show `Gross / Withheld / Net` when `withheldAmount` is present. `WithholdingRulesPanel` (copy `BonusDeclarationsPanel`): list `aria-label="Withholding rules"`, empty state "No withholding rule" / "Nothing is withheld from any payout until a rule is approved."; propose form `Payout kinds` (checkbox `Annuity income`), `Rate (%)`, `Effective from`, `Effective to (optional)`, `Legal reference`; buttons `Propose rule`, `Approve rule`, `Withdraw`.

- [ ] **Step 8: Run and commit**

```bash
cd frontend
npm run typecheck ; npm run lint ; npx vitest run src/gates/withholdingGates.test.ts src/features/products src/features/annuities src/features/finance src/features/underwriting src/features/policies
git add src
git commit -m "feat(console): publish an annuity's forms and grids, quote and buy one on the case, see it on the policy, and run withholding rules"
```

---

### Task 9: Seed and e2e

**Files:**
- Modify: `backend/scripts/seed-dev-data.sh` (product `ANN-LIFE-01`)
- Create: `frontend/e2e/staff-annuity.spec.ts`

- [ ] **Step 1: Seed** — after `WP-ENDOW-01`: an ANNUITY product "Nlolo Pensheni Annuity", entry ages 55–85, sum-assured bounds 5,000,000–1,000,000,000, free-look 15, timing ARREARS, proof of life 12, joint range −10..15, two forms — `LIFE-10G` (10-year guarantee, UNISEX) and `JOINT-50` (joint, 50%, UNISEX, bands −10..−1, 0..4, 5..15) — with sample rates generated in the script by a small `awk` loop (`rate = 60 + (age − 55) × 1.5` for LIFE-10G, 5 lower for JOINT-50; **marked in the basis reference as `DEMO-BASIS-NOT-ACTUARIAL`**), MONTHLY 0.98 and ANNUAL 1. No withholding rule seeded (finance proposes and approves it on screen). Apply product V22, underwriting V14, policy V33, benefitpayout V2 and V3, payment V11, finaccounting V8 and annuity V1 to the dev DB by hand in module order (`psql -v ON_ERROR_STOP=1 -1`), then restart the dev backend.

- [ ] **Step 2: The spec** — one test, `test.setTimeout(300_000)`, storage `staff-admin.json`, today by `todayIso()`:
  1. publish its own ANNUITY product through the form (code `ANN-E2E-${Date.now()}`, one life-only form, ages 55–85 filled by the button with a constant rate 72, MONTHLY 0.98), the way `staff-with-profits.spec.ts` publishes its endowment;
  2. as finance (a second context) propose a withholding rule `Annuity income` 10% from today, and approve it as admin (the other person);
  3. open a case for Amina Owner (born 1990 — **too young for 55–85**: register a fixture client born 1966 in the spec, or widen the e2e product's ages to 18–85) on the product, choose the form and MONTHLY, type 50,000,000 and see the live income line;
  4. decide ACCEPT with `Age evidence confirmed` as the senior underwriter — auto-issue;
  5. collect the single premium on the Billing tab through the mock rail (copy the deposit spec's block, 60s);
  6. poll the Annuity tab until `IN_PAYMENT`, and assert the instalment `TZS 294,000.00`;
  7. on the Payouts tab the first instalment is listed; review and approve it as two people and assert `Withheld TZS 29,400.00` and `Net TZS 264,600.00`.

Before running, check every label against what Task 8 rendered (e2e couples to accessible names).

- [ ] **Step 3: Run it alone, then commit**

```bash
cd frontend
npx playwright test e2e/staff-annuity.spec.ts
git add e2e ../backend/scripts/seed-dev-data.sh
git commit -m "test(e2e): an annuity bought on a case, locked at payment, paid net of an approved withholding rule"
```

---

### Task 10: The gate

- [ ] Stop the dev backend and any orphan non-`redhat.java` JVM (by command line).
- [ ] `./mvnw -B -o clean test` in the background. No edits while it runs. Expected: green, above step 4's count.
- [ ] Restart the dev backend; full Playwright suite with setup deps on a quiet, awake machine; `--last-failed` with fresh auth for anything that fails late (L12); every remaining failure diagnosed before merging.
- [ ] Whole-branch review of the seams:
  - `resolveAnnuityPlan` touches no annuity table for a non-ANNUITY product (proved by an existing class passing without V22);
  - every `annuity` listener filters on event type, then asks product;
  - the lock is once-only, in Java and by the trigger;
  - `ceilingFor` is the one figure for claim screen and approval, ANNUITY included;
  - the capital refund is paid only by claims; benefitpayout never pays a lump sum on death;
  - withholding is applied on **both** approval paths (single and run) before the payout is requested, and finaccounting posts three legs only when something was withheld;
  - the free-look deduction is the server's;
  - no selector returns a fresh object; every date is civil.
- [ ] Merge `--no-ff` into `main` with the gate figures; update memory (`project_product_step5_annuities.md`).
- [ ] Live walkthrough on the dev stack (L14): publish, quote, case, accept, collect, lock, approve the first instalment net of tax, register a death on the guarantee form and see the redirect. Report the figures.

---

## R. Revisions to the spec made while planning (2026-10-03)

- **R1 — the pricer lives in product, not annuity.** Underwriting must re-run the quote at acceptance (spec §4.2), and underwriting cannot depend on `annuity` (annuity depends on underwriting for the choice; that would be a cycle). Product already owns the rates and is a dependency of both, so `ProductApi.priceAnnuity` is the one pricer; `annuity` builds its inputs from party and calls it; the quote endpoint stays in `annuity` (spec §4.1's URL unchanged).
- **R2 — `resolveAnnuityPlan` gates on the product's category before reading any annuity table**, a tighter form of step 4's L6, so product V22 needs no sweep across existing test classes. V21's sweep (71 files) is the cost this avoids.
- **R3 — withholding's columns do cost a sweep.** They sit on `payout_instalment`, which every benefitpayout test class maps, and withholding is checked on every approval. Benefitpayout V2 and V3 go into every class that applies benefitpayout V1 (Task 4 Step 1).
- **R4 — annuity decisions allow ACCEPT and DECLINED only.** The spec's light path implies it; LOADED has no meaning for a product whose premium is the purchase price, and POSTPONED would leave a priced quote dangling.
- **R5 — a manual issue of an annuity without a case lands in `LOCK_FAILED`**, because the contract's form and frequency come from the case (spec §4.3). Manual issue stays possible for the exception path only when it carries the case.
- **R6 — the case form needs the version's form list**, which no existing read carries. Task 1 adds a read-only `GET /product-versions/{versionId}/annuity` in product (forms and frequencies, no rate rows).
- **R7 — e2e age.** The seeded Amina Owner (born 1990) is younger than any realistic annuity entry age; the e2e spec widens its own product's ages or registers its own client (Task 9).
