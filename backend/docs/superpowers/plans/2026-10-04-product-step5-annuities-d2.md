# Product step 5 — deferred annuities and pension vesting (D2) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task (the user prefers inline execution with no subagents). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One policy that saves in a step 3 account until its vesting date and then — on its own, or by a staff instruction recorded inside the version's window — closes the account, pays an optional lump sum, and buys a D1 annuity at the rates in force that day.

**Architecture:** Product gains vesting terms riding on `AnnuityPlan` (§R1) and lifts its two refusals only for a version that carries them. Underwriting records a retirement age instead of a form. Policy issues an account policy with a premium-paying term to the target date and **no policy term** (§R2), and records the vesting so billing stops and nothing treats a vested pension as an account again. The `annuity` module owns the saving-phase contract, the instruction, the holds, the daily vesting sweep and the reminders; at vesting it calls `AccumulationApi.closeForVesting`, `BenefitPayoutApi.scheduleCommutation`, and D1's own lock. Benefitpayout gains `COMMUTATION`; communication gains two templates.

**Tech Stack:** Spring Boot 3 / Spring Modulith, JPA with `ddl-auto: none`, hand-written SQL migrations per module, Postgres 16 Testcontainers, React + Zustand + zod console, Playwright e2e.

**Spec:** `backend/docs/superpowers/specs/2026-10-03-product-step5-annuities-d2-design.md` (c3df86f1). Where this plan and the spec differ, the plan wins; every difference is listed in §R and is to be confirmed by the user.

**Depends on:** D1 (`product-step5-annuities`) merged to `main`. Branch `product-step5-d2` from `main` after that merge.

## Global Constraints

- The user's decisions in spec §2 bind, verbatim: **one policy, two phases**; **target date plus a window** (`minVestingAge`..`maxVestingAge`); lump sum **up to `maxCommutationPercent`**, paid as its own kind `COMMUTATION`; with no instruction the policy vests **on its target date into the version's default form and frequency with no lump sum**, and **the default form may not be joint**; a deferral **chooses whether contributions continue or stop**; death before vesting pays **the balance through the existing account death closing**, no annuity; **rates of the product's current version on the vesting date**; proof of age **re-confirmed at vesting only if date of birth or sex changed**; **`surrenderBeforeVesting` decides** surrender and withdrawals.
- Migrations, next free numbers (checked 2026-10-04): **product V23, underwriting V15, policy V34, accumulation V4, benefitpayout V5, payment V12, communication V11, annuity V2.** No finaccounting migration (§R4). Before writing any column on an existing table, grep that table's CHECKs.
- **Gate new-table reads on product first** (D1 R2): `resolveAnnuityPlan` reads `version_vesting_terms` only after the category says ANNUITY; policy reads `annuity_vesting` only after `resolveAnnuityPlan(v).deferred()`. No existing non-annuity test class needs a D2 migration.
- One shared migration list: `annuity/AnnuityTestMigrations.ALL` gains product V23, underwriting V15, policy V34, accumulation V4, benefitpayout V5, payment V12, communication V11 (only if the class sends SMS — see Task 7) and annuity V2. Every test class that publishes an ANNUITY version outside that list (`grep -rl "product/V22__annuity_terms" src/test/java`) gains product V23 directly after V22, with the anchored pattern-only node script (step 4 L5, print `updated N of N`).
- "Today" is the civil date: `LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"))` in Java, `todayIso()` in e2e. Never `LocalDate.now()` bare.
- `@Scheduled` drains carry `initialDelayString` equal to their interval (step 0), with a short `local` override in `application-local.yml`.
- Money `NUMERIC(19,2)`; percentages `NUMERIC(9,4)`; every rounding `HALF_EVEN` to cents, **once**.
- `-Dtest` takes commas. Run each task's own classes; `./mvnw -o clean test-compile` after any signature change. Stop the dev backend and its orphan JVMs (never the `redhat.java` ones) before `clean`. **Never run Maven while another Maven run (the D1 gate) is in progress.**
- Never run Prettier. Edit files with Write/Edit, not shell strings. Zustand selectors never return a fresh object or array.
- Every response field in OpenAPI and asserted by name in a contract test; every console validator message lands on a rendered path (step 4 L9, L10). No `default:` on an OpenAPI property.
- Commit at the end of each task. End commit messages with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

---

## File structure

```
backend/db-migrations/
  product/V23__vesting_terms.sql                 version_vesting_terms
  underwriting/V15__deferred_annuity_choice.sql  retirement age + confirmed DOB/sex at acceptance
  policy/V34__annuity_vesting.sql                annuity_vesting (policy_number, vested_on)
  accumulation/V4__vesting_entry.sql             'VESTING' entry type
  benefitpayout/V5__commutation_kind.sql         'COMMUTATION' instalment kind
  payment/V12__commutation_purpose.sql           'COMMUTATION_PAYOUT'
  communication/V11__vesting_reminder_template.sql
  annuity/V2__deferred_annuities.sql             ACCUMULATING, contract relaxations, vesting + instruction tables, vestings_due()

backend/src/main/java/tz/co/nlolo/lifeplatform/
  product/api/       VestingTerms; AnnuityPlan (+vesting component, deferred())
  product/domain/    VestingPlanValidator, VersionVestingTerms (+repo); AnnuityPlanValidator, AccumulationPlanValidator relaxed
  product/infrastructure/  AnnuityRequest.Vesting
  underwriting/      DeferredAnnuityChoice (api), DeferredAnnuityChoiceEntity + repo, decide branch, endpoints
  policy/            AnnuityVestingEntity + repo, recordVesting, extendPremiumPayingTerm, issue branch, guards
  billing/           AnnuityVested + PremiumPayingTermRestated handlers
  accumulation/      closeForVesting, EntryType.VESTING, locked-pension withdrawal refusal
  benefitpayout/     PayoutKind.COMMUTATION, scheduleCommutation
  annuity/
    api/             VestingView, VestingInstructionInput, ContractStatus.ACCUMULATING, AnnuityApi (+decidesDeath, vesting reads/writes)
    domain/          Vesting (entity), VestingInstruction (entity), CommutationSplit (pure), VestingRules (pure)
    application/     AnnuityVesting (the vesting of one policy), VestingSweep, VestingReminderSweep
    infrastructure/  VestingRepository, VestingInstructionRepository, VestingController (+requests/responses)
  communication/application/AnnuityEventListener

frontend/src/
  api/annuity.ts (+vesting calls), store/annuityStore.ts (+vesting)
  features/products/vestingSchema.ts (+test), AnnuityTermsSection.tsx (immediate | deferred, Vesting section)
  features/underwriting/ (retirement age on a deferred case)
  features/annuities/PolicyVestingPanel.tsx (+test), VestingInstructionForm.tsx, vestingForm.ts (+test)
  features/annuities/HeldVestingsPage.tsx
frontend/e2e/staff-deferred-annuity.spec.ts
```

---

### Task 1: Product — vesting terms on an ANNUITY version that carries an account

**Files:**
- Create: `db-migrations/product/V23__vesting_terms.sql`
- Create: `product/api/VestingTerms.java`, `product/domain/VestingPlanValidator.java`, `product/domain/VersionVestingTerms.java`, `product/infrastructure/VersionVestingTermsRepository.java`
- Modify: `product/api/AnnuityPlan.java` (10th component), `product/domain/AnnuityPlanValidator.java` (exclusions + coverage window), `product/domain/AccumulationPlanValidator.java` (ANNUITY allowed when deferred), `product/application/ProductApiImpl.java` (~line 364-375 validation, `persistAnnuityPlan`, `resolveAnnuityPlan`), `product/infrastructure/AnnuityRequest.java` (+`Vesting vesting`), the read behind `GET /products/{id}/versions/{v}/annuity` (+vesting), `api/openapi/openapi-product.yaml`
- Test: `product/VestingPlanValidatorTest.java` (new, pure), extend `AnnuityPlanValidatorTest`, `AccumulationPlanValidatorTest`, `ProductApiIntegrationTest`, `ProductContractTest`

**Interfaces — Produces:**
```java
/** A deferred annuity's vesting terms (product step 5 D2). Read from the version the policy was SOLD on. */
public record VestingTerms(int minVestingAge, int maxVestingAge, String defaultFormCode, String defaultFrequency,
                           BigDecimal maxCommutationPercent, Boolean surrenderBeforeVesting) {}

public record AnnuityPlan(boolean annuity, AnnuityTiming timing, int proofOfLifeIntervalMonths,
                          Integer jointAgeDifferenceMin, Integer jointAgeDifferenceMax,
                          String basisReference, LocalDate basisDate,
                          List<AnnuityForm> forms, List<AnnuityFrequencyFactor> frequencies,
                          VestingTerms vesting) {
    /** The nine-argument form every D1 caller uses: an immediate annuity. */
    public AnnuityPlan(boolean annuity, AnnuityTiming timing, int proofOfLifeIntervalMonths,
                       Integer jointAgeDifferenceMin, Integer jointAgeDifferenceMax, String basisReference,
                       LocalDate basisDate, List<AnnuityForm> forms, List<AnnuityFrequencyFactor> frequencies) {
        this(annuity, timing, proofOfLifeIntervalMonths, jointAgeDifferenceMin, jointAgeDifferenceMax,
             basisReference, basisDate, forms, frequencies, null);
    }
    public boolean deferred() { return annuity && vesting != null; }
}
```

- [ ] **Step 1: The migration**

```sql
-- db-migrations/product/V23__vesting_terms.sql
-- Product step 5 (D2): a deferred annuity's vesting terms -- the window, what it vests into when
-- nobody says otherwise, the lump-sum cap, and whether it is locked before it vests. Its own table,
-- read only after the product's category says ANNUITY (D1 R2), so no other test class needs it.
CREATE TABLE product.version_vesting_terms (
    product_version_id        UUID PRIMARY KEY REFERENCES product.product_version(product_version_id),
    tenant_id                 UUID NOT NULL,
    min_vesting_age           INTEGER NOT NULL CHECK (min_vesting_age BETWEEN 0 AND 120),
    max_vesting_age           INTEGER NOT NULL CHECK (max_vesting_age BETWEEN 0 AND 120),
    default_form_code         VARCHAR(30) NOT NULL,
    default_frequency         VARCHAR(12) NOT NULL
        CHECK (default_frequency IN ('MONTHLY','QUARTERLY','SEMI_ANNUAL','ANNUAL')),
    max_commutation_percent   NUMERIC(9,4) NOT NULL CHECK (max_commutation_percent BETWEEN 0 AND 100),
    -- No default, on purpose (spec Q9): a locked pension and an unlocked deferred annuity are both
    -- real products, and the version must say which it is.
    surrender_before_vesting  BOOLEAN NOT NULL,
    CHECK (min_vesting_age <= max_vesting_age)
);
ALTER TABLE product.version_vesting_terms ENABLE ROW LEVEL SECURITY;
CREATE POLICY version_vesting_terms_tenant_isolation ON product.version_vesting_terms
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON product.version_vesting_terms TO app_role;
```

- [ ] **Step 2: Failing validator tests** (`VestingPlanValidatorTest`, pure). Fixture: entry ages 18–55, an `ACCOUNT` accumulation plan (copy the one `AccumulationPlanValidatorTest` uses), a life-only UNISEX form `LIFE-0G` with rates for ages 55–70, a joint form `JOINT-50` with bands covering −10..15 for ages 55–70, frequencies MONTHLY 0.98 and ANNUAL 1, and

```java
    private static VestingTerms terms() {
        return new VestingTerms(55, 70, "LIFE-0G", "MONTHLY", new BigDecimal("25"), Boolean.FALSE);
    }

    private static void refused(Runnable r, String message) {
        assertThatThrownBy(r::run).isInstanceOf(InvalidProductVersionException.class).hasMessage(message);
    }

    @Test void aValidDeferredVersionPasses() {
        VestingPlanValidator.validate(ProductCategory.ANNUITY, plan(terms()), ACCOUNT, AGES_18_TO_55);
    }

    @Test void theDefaultFormMayNotBeJoint() {
        refused(() -> VestingPlanValidator.validate(ProductCategory.ANNUITY,
                plan(new VestingTerms(55, 70, "JOINT-50", "MONTHLY", BigDecimal.ZERO, Boolean.FALSE)), ACCOUNT, AGES_18_TO_55),
            "The default form JOINT-50 is joint-life; a pension that vests with no instruction vests on one life");
    }
```

`VestingPlanValidator.validate(ProductCategory category, AnnuityPlan plan, AccumulationPlan accumulation, EligibilityBounds bounds)`. One `@Test` per row, each asserting the exact text:

| Rule | Message |
|---|---|
| vesting terms on a non-ANNUITY category | `Vesting terms are only for an ANNUITY product` |
| vesting terms without an `ACCOUNT` accumulation plan | `Vesting terms are only for an annuity that saves in an account before it vests` |
| an ANNUITY version with an account and no vesting terms | `An annuity that saves in an account must state its vesting terms` |
| min > max, or either outside 0..120 | `The vesting window runs from a minimum to a maximum vesting age, each between 0 and 120` |
| default form not on the version | `The default form X is not one of this version's forms` |
| default form joint | `The default form X is joint-life; a pension that vests with no instruction vests on one life` |
| default frequency not on the version | `The default frequency F is not one of this version's frequencies` |
| cap null or outside 0..100 | `The lump-sum cap must be between 0% and 100% of the balance` |
| `surrenderBeforeVesting` null | `A deferred annuity must state whether it can be surrendered before it vests` |
| `maxEntryAge >= maxVestingAge` | `The maximum entry age must be below the maximum vesting age, so every customer can reach a vesting age` |

- [ ] **Step 3: Failing relaxation tests.**
  - `AnnuityPlanValidatorTest`: a deferred plan with an ACCOUNT accumulation plan passes; a deferred plan with a `DepositPlan`, a cash-value table or a bonus plan is still refused with D1's existing messages; an **immediate** plan with an account is still refused with `"An ANNUITY version cannot be valued by an account or as a deposit"` (unchanged text). Coverage on a deferred plan walks the **vesting window**: a form with rates 55–69 but `maxVestingAge` 70 fails `"Form LIFE-0G has no rate for age 70"`, while entry ages 18–55 need no rate at all.
  - `AccumulationPlanValidatorTest`: `validate(ANNUITY, account, none, true)` passes; `validate(ANNUITY, account, none, false)` refuses `"A ANNUITY product cannot use an account value basis"` (the existing message, unchanged).

- [ ] **Step 4: Run them to see them fail** — `./mvnw -o test -Dtest='VestingPlanValidatorTest,AnnuityPlanValidatorTest,AccumulationPlanValidatorTest'` → compilation failure.

- [ ] **Step 5: Implementation.**
  - `AnnuityPlan`: the 10th component and the 9-arg constructor shown above; `none()` unchanged (it uses the 9-arg form). `deferred()`.
  - `AccumulationPlanValidator.validate(category, plan, cashValue, boolean deferredAnnuity)`; the existing 3-arg method delegates with `false`. The category check becomes `if (!ACCOUNT_CATEGORIES.contains(category) && !(deferredAnnuity && category == ProductCategory.ANNUITY))`. Update the Q9 comment: "ANNUITY only as a deferred annuity, which can vest (D2)".
  - `AnnuityPlanValidator.validate(...)`: `checkExclusions` takes `boolean deferred` and lets `accumulation.isAccount()` through when deferred (a deposit never). The coverage loop becomes
    ```java
        int from = plan.deferred() ? plan.vesting().minVestingAge() : bounds.minEntryAge();
        int to = plan.deferred() ? plan.vesting().maxVestingAge() : bounds.maxEntryAge();
        for (AnnuityForm form : plan.forms()) {
            checkCoverage(plan, form, from, to);
        }
    ```
    The entry-age-bounds-required check stays for both kinds (they bound the sale).
  - `VestingPlanValidator`: the table's rules, in the table's order. The "must state its vesting terms" rule fires when `category == ANNUITY && accumulation.isAccount() && plan.vesting() == null`.
  - `ProductApiImpl` around line 364:
    ```java
        AnnuityPlan annuity = annuityPlan != null ? annuityPlan : AnnuityPlan.none();
        AccumulationPlanValidator.validate(category, effectiveAccumulation, cashValue, annuity.deferred());
        ...
        AnnuityPlanValidator.validate(category, annuity, bounds, cashValue, effectiveAccumulation, deposit, bonusPlan, payoutPlan);
        VestingPlanValidator.validate(category, annuity, effectiveAccumulation, bounds);
    ```
    Move the `AnnuityPlan annuity = ...` line above the accumulation validation (it is currently below it).
  - `persistAnnuityPlan`: after the frequencies, `if (plan.vesting() != null) versionVestingTermsRepository.save(new VersionVestingTerms(tenantId, productVersionId, plan.vesting()));`
  - `resolveAnnuityPlan`: after building the plan, attach `versionVestingTermsRepository.findById(productVersionId).map(VersionVestingTerms::toTerms).orElse(null)` as the 10th argument. Still only after the category gate.
  - `AnnuityRequest` gains `@Valid Vesting vesting` with `record Vesting(Integer minVestingAge, Integer maxVestingAge, String defaultFormCode, String defaultFrequency, BigDecimal maxCommutationPercent, Boolean surrenderBeforeVesting)`; `toPlan()` passes `vesting != null ? vesting.toTerms() : null`. Integer nulls become a validator refusal, not an NPE: `toTerms()` maps a null age to `-1` so the window rule fires.
  - The version's annuity read (`GET /products/{id}/versions/{v}/annuity`, D1 deviation) gains a `vesting` object or `null`. OpenAPI: `vesting` on the request block and on the read, typed `[object, "null"]`, every property listed.

- [ ] **Step 6: Integration and contract tests.** `ProductApiIntegrationTest`: publish a deferred ANNUITY version (ages 18–55, vesting 55–70) and resolve it — `deferred()` true and the terms round-trip; publish an immediate one and `vesting()` is null; publishing the deferred version **without** an account refuses `"Vesting terms are only for an annuity that saves in an account before it vests"`. `ProductContractTest`: a `POST /products/{id}/versions` with `annuity.vesting` → 201; the read returns `vesting` with every field by name. Both classes add product V23 after V22.

- [ ] **Step 7: Run and commit**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='VestingPlanValidatorTest,AnnuityPlanValidatorTest,AccumulationPlanValidatorTest,PayoutPlanValidatorTest,ProductApiIntegrationTest,ProductContractTest'
git add db-migrations/product/V23__vesting_terms.sql src/main/java/tz/co/nlolo/lifeplatform/product src/test/java/tz/co/nlolo/lifeplatform/product api/openapi/openapi-product.yaml
git commit -m "feat(product): a deferred annuity -- an ANNUITY version that saves in an account and states its vesting window, default and lump-sum cap"
```

`PayoutPlanValidatorTest` is in the run because an ANNUITY version now reaches `PayoutPlanValidator` with an account plan for the first time; if it refuses that combination, the refusal is wrong for D2 and the test that proves it is added here.

---

### Task 2: Underwriting and issue — a retirement age on the case, and an account policy to the target date

**Files:**
- Create: `db-migrations/underwriting/V15__deferred_annuity_choice.sql`
- Create: `underwriting/api/DeferredAnnuityChoice.java`, `underwriting/domain/DeferredAnnuityChoiceEntity.java`, `underwriting/infrastructure/DeferredAnnuityChoiceRepository.java`
- Modify: `underwriting/api/UnderwritingApi.java` (+2 methods), `underwriting/application/UnderwritingApiImpl.java` (`recordAnnuityChoice` refuses on deferred; `checkAnnuityDecision` branches at line ~603; `annuityChoice` unchanged), `underwriting/infrastructure/OpenCaseRequest.java` (+`deferredAnnuity`), `UnderwritingController.java` (+GET/PUT `/underwriting/cases/{caseId}/deferred-annuity-choice`), `api/openapi/openapi-underwriting.yaml`
- Modify: `policy/application/UnderwritingDecisionEventListener.java:366` (deferred branch first), `policy/application/PolicyApiImpl.java:1344` (`refuseUnlessAValidAnnuity` branches)
- Test: `annuity/DeferredAnnuityCaseIntegrationTest.java` (new, `AnnuityTestMigrations.ALL`), extend `UnderwritingContractTest`

**Interfaces — Produces:**
```java
/**
 * A deferred annuity applicant's choice (D2): only the retirement age -- the form is chosen near
 * vesting. At acceptance, who confirmed proof of age, and the date of birth and sex they confirmed.
 */
public record DeferredAnnuityChoice(int retirementAge, LocalDate targetDate,
                                    String ageEvidenceConfirmedBy, Instant ageEvidenceConfirmedAt,
                                    LocalDate confirmedDateOfBirth, String confirmedSex) {}
// UnderwritingApi
DeferredAnnuityChoice recordDeferredAnnuityChoice(UUID caseId, int retirementAge, String recordedBy);
java.util.Optional<DeferredAnnuityChoice> deferredAnnuityChoice(UUID caseId);   // empty, never a throw (D1's reason)
```

- [ ] **Step 1: The migration**

```sql
-- db-migrations/underwriting/V15__deferred_annuity_choice.sql
-- Product step 5 (D2): what a deferred annuity applicant chose -- a retirement age, which gives the
-- target vesting date -- and, at acceptance, the date of birth and sex whose proof was seen (spec
-- Q8: vesting re-confirms only if either has changed since). Its own table, as D1's annuity_choice.
CREATE TABLE underwriting.deferred_annuity_choice (
    case_id                    UUID PRIMARY KEY REFERENCES underwriting.underwriting_case(case_id),
    tenant_id                  UUID NOT NULL,
    retirement_age             INTEGER NOT NULL CHECK (retirement_age BETWEEN 0 AND 120),
    recorded_by                VARCHAR(100) NOT NULL,
    recorded_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    age_evidence_confirmed_by  VARCHAR(100),
    age_evidence_confirmed_at  TIMESTAMPTZ,
    confirmed_date_of_birth    DATE,
    confirmed_sex              VARCHAR(10) CHECK (confirmed_sex IN ('FEMALE','MALE')),
    CHECK ((age_evidence_confirmed_by IS NULL) = (age_evidence_confirmed_at IS NULL)),
    CHECK (age_evidence_confirmed_by IS NULL OR confirmed_date_of_birth IS NOT NULL)
);
ALTER TABLE underwriting.deferred_annuity_choice ENABLE ROW LEVEL SECURITY;
CREATE POLICY deferred_annuity_choice_tenant_isolation ON underwriting.deferred_annuity_choice
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON underwriting.deferred_annuity_choice TO app_role;
```

Check `party.api.Sex`'s constant names before relying on `'FEMALE','MALE'` (`grep -n "enum Sex" -A2 src/main/java/tz/co/nlolo/lifeplatform/party/api/Sex.java`); the CHECK must list exactly those.

- [ ] **Step 2: Failing tests** (`DeferredAnnuityCaseIntegrationTest`). Fixture: a deferred ANNUITY product (Task 1's shape: entry 18–55, vesting 55–70, LIFE-0G default MONTHLY, cap 25%, locked) published through `ProductApi`; an applicant born **1980-06-15**, FEMALE.

```java
    @Test void aDeferredCaseRecordsARetirementAgeAndItsTargetDate() {
        var c = openDeferredCase(60);
        var choice = asTenant(() -> underwritingApi.deferredAnnuityChoice(c.caseId())).orElseThrow();
        assertThat(choice.retirementAge()).isEqualTo(60);
        assertThat(choice.targetDate()).isEqualTo(LocalDate.of(2040, 6, 15));
    }

    @Test void aRetirementAgeOutsideTheWindowIsRefused() {
        // 422 "The retirement age must be within the vesting window, 55 to 70"
    }

    @Test void aDeferredCaseTakesNoFormChoice() {
        // recordAnnuityChoice on it → 422 "A deferred annuity records a retirement age; its form is chosen when it vests"
    }

    @Test void acceptanceStoresTheConfirmedDateOfBirthAndSex() {
        // decide ACCEPT with ageEvidenceConfirmed → choice.confirmedDateOfBirth() == 1980-06-15, confirmedSex "FEMALE"
    }

    @Test void acceptanceIssuesAnAccountPolicyPayingToTheTargetDate() {
        // case: sum assured 200,000.00 (the contribution), premiumFrequency MONTHLY, commencement 2026-11-01.
        // policy: premium 200,000.00 MONTHLY, policyTermMonths null, maturityDate null,
        // premiumPayingTermMonths 163 (2026-11-01 → 2040-06-01, whole months, floor), account OPEN.
    }

    @Test void withoutAgeEvidenceADeferredAnnuityCannotBeAccepted() {
        // 422 "An annuity is accepted only once proof of age is confirmed" (D1's words, shared)
    }
```

- [ ] **Step 3: Implementation.**
  - `recordDeferredAnnuityChoice`: refuse on a non-deferred version (`"Only a deferred annuity case records a retirement age"`), on a decided case (D1's exception), and when the life assured's date of birth is not recorded (`"The applicant's date of birth is not recorded, so there is no vesting date"`). The age must satisfy `vesting.minVestingAge() <= age <= vesting.maxVestingAge()` (`"The retirement age must be within the vesting window, " + min + " to " + max`) and the target date `dob.plusYears(age)` must be after today (`"A retirement age of N has already been reached"`). Upsert the row.
  - `deferredAnnuityChoice`: as D1's `annuityChoice` (empty for an unknown case or a non-deferred version); `targetDate` is derived on read from the life assured's **recorded** date of birth plus the age, or from `confirmed_date_of_birth` once confirmed.
  - `recordAnnuityChoice`, first rule after loading the plan: `if (plan.deferred()) throw new UnderwritingValidationException("A deferred annuity records a retirement age; its form is chosen when it vests");`
  - `checkAnnuityDecision`: after the ACCEPT/DECLINED check and the DECLINED return, `if (plan.deferred()) { checkDeferredAcceptance(underwritingCase, decision, recordedBy); return; }`. It requires the deferred choice (`"A deferred annuity case must record the retirement age before it is decided"`), `ageEvidenceConfirmed` (D1's message), the life assured's date of birth, and stores confirmer, now, and the party's current date of birth and sex on the row. **No pricing** at acceptance (spec Q7: it prices at vesting). Find where D1 stores its confirmer (`grep -n "ageEvidenceConfirmedBy\|confirmAgeEvidence" underwriting/`) and do the same in the same place.
  - `OpenCaseRequest` gains `@Valid DeferredAnnuityDto deferredAnnuity` (`record DeferredAnnuityDto(Integer retirementAge)`); the controller records it after `openCase`, as it does `annuityChoice`. `GET` → 200 or 404 (copy `AnnuityChoiceNotFoundException`); `PUT` body `{retirementAge}`.
  - `UnderwritingDecisionEventListener`, **before** the D1 annuity branch at line 366:
    ```java
                // A deferred annuity saves first (product step 5 D2): an account policy whose
                // contributions run to the target date, with no policy term -- after vesting it pays
                // for life, so it has no maturity (plan §R2). The case's sum assured is the
                // contribution per payment (plan §R3).
                AnnuityPlan annuityPlan = productApi.resolveAnnuityPlan(decidedCase.productVersionId());
                if (annuityPlan.deferred()) {
                    LocalDate commencement = decidedCase.proposedCommencementDate() != null
                        ? decidedCase.proposedCommencementDate() : LocalDate.now(CIVIL_ZONE);
                    LocalDate target = underwritingApi.deferredAnnuityChoice(caseId).orElseThrow().targetDate();
                    String frequency = decidedCase.premiumFrequency() != null ? decidedCase.premiumFrequency() : "MONTHLY";
                    Integer payingMonths = "SINGLE".equals(frequency) ? null
                        : (int) java.time.temporal.ChronoUnit.MONTHS.between(commencement, target);
                    policyApi.issuePolicy(caseId, new PolicyApi.IssueRequest(
                        decidedCase.applicantPartyId(), decidedCase.productId(), decidedCase.productVersionId(),
                        decidedCase.sumAssuredAmount(), decidedCase.sumAssuredCurrency(),
                        decidedCase.sumAssuredAmount(), decidedCase.sumAssuredCurrency(), frequency,
                        decidedCase.agentOfRecordId(), nominationsAsBeneficiaries(decidedCase),
                        "Automatic issuance on underwriting decision " + outcome,
                        commencement, null, payingMonths, decidedCase.lifeAssuredPartyId()),
                        "system:underwriting-decision-listener");
                    return;
                }
    ```
    then the D1 branch reuses `annuityPlan` instead of resolving again. The `IssueRequest` argument order is D1's (positions 12-13 are policy term, premium-paying term) — read lines 368-376 and match them. Add `CIVIL_ZONE` if the listener lacks one.
  - `PolicyApiImpl.refuseUnlessAValidAnnuity`: resolve the plan once; when `plan.deferred()`, require `premiumAmount == sumAssuredAmount` (`"A deferred annuity's sum assured is its contribution: the two must be equal"`), refuse a policy term (`"A deferred annuity has no policy term: it saves to its vesting date and then pays for life"`), and require a premium-paying term unless SINGLE (`"A deferred annuity pays contributions to its vesting date: state the premium-paying term"`); otherwise D1's rules unchanged.

- [ ] **Step 4: Run and commit**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='DeferredAnnuityCaseIntegrationTest,AnnuityCaseIntegrationTest,UnderwritingContractTest,UnderwritingApiIntegrationTest'
git commit -m "feat(underwriting,policy): a deferred annuity case records a retirement age, confirms the date of birth and sex it saw, and issues an account policy paying to the target date"
```

---

### Task 3: Accumulation, benefitpayout and payment — the vesting close and the lump sum

**Files:**
- Create: `db-migrations/accumulation/V4__vesting_entry.sql`, `db-migrations/benefitpayout/V5__commutation_kind.sql`, `db-migrations/payment/V12__commutation_purpose.sql`
- Modify: `accumulation/api/EntryType.java` (+`VESTING`), `accumulation/api/AccumulationApi.java` + impl (`closeForVesting`, withdrawal refusal), `accumulation/application/AccumulationApiImpl.java:94` (`requestWithdrawal`)
- Modify: `product/api/PayoutKind.java` (+`COMMUTATION`), `product/domain/PayoutPlanValidator.java` (refuse an authored COMMUTATION row), `benefitpayout/api/BenefitPayoutApi.java` + impl (`scheduleCommutation`, `purposeFor`, the premium hold), `benefitpayout/application/PayoutPaymentListener.java` (+`COMMUTATION_PAYOUT`)
- Test: `accumulation/VestingCloseIntegrationTest.java`, `benefitpayout/CommutationIntegrationTest.java` (both `AnnuityTestMigrations.ALL`), extend `PayoutPlanValidatorTest`

**Interfaces — Produces:**
```java
// AccumulationApi
/**
 * Closes the account on the vesting date with interest to that day and returns what the VESTING entry
 * moved. Idempotent on the policy: asked again, it returns the same figure and posts nothing.
 */
java.math.BigDecimal closeForVesting(String policyNumber, java.time.LocalDate vestingDate);
// BenefitPayoutApi
/** A pension's lump sum: one COMMUTATION instalment due on the vesting date, payee the policyholder. Idempotent per policy. */
UUID scheduleCommutation(String policyNumber, java.time.LocalDate dueDate, java.math.BigDecimal amount, String currency);
```

- [ ] **Step 1: Migrations.** Each copies its predecessor's DROP/ADD and adds one value; do not retype the lists by hand — read the latest statement and append.

```sql
-- db-migrations/accumulation/V4__vesting_entry.sql
-- Product step 5 (D2): the account closes when a pension vests. Its own entry type, not MATURITY,
-- because the money does not leave -- it buys the annuity on the same policy.
ALTER TABLE accumulation.ledger_entry DROP CONSTRAINT ledger_entry_entry_type_check;
ALTER TABLE accumulation.ledger_entry ADD CONSTRAINT ledger_entry_entry_type_check CHECK (entry_type IN (
    'CONTRIBUTION','TOP_UP','TRANSFER_IN','ALLOCATION_CHARGE','POLICY_FEE','INTEREST','WITHDRAWAL',
    'SURRENDER','MATURITY','DEATH_CLAIM','FREE_LOOK_REFUND','ADJUSTMENT','REVERSAL','VESTING'));
```

Confirm the constraint name first: the V1 CHECK is inline and unnamed, so Postgres named it `ledger_entry_entry_type_check` — verify with `grep -rn "ledger_entry_entry_type_check" db-migrations` (a later migration may already have replaced it) before relying on it.

`benefitpayout/V5__commutation_kind.sql`: V2's `payout_instalment_kind_check` DROP/ADD with `'COMMUTATION'` appended. `payment/V12__commutation_purpose.sql`: V11's `disbursement_instruction_purpose_check` DROP/ADD with `'COMMUTATION_PAYOUT'` appended, and a comment line "-- A pension's lump sum at vesting."

Adding an enum value used only by new rows needs no sweep: no entity mapping changes.

- [ ] **Step 2: Failing tests.**
  - `VestingCloseIntegrationTest`: an account policy of the Task 2 fixture with three contributions collected; `closeForVesting(policy, date)` returns balance + interest to the date, the ledger ends INTEREST then VESTING (negated), the account is CLOSED with reason `VESTED`; a second call returns the same figure and posts nothing; a withdrawal request on a **locked** pension (`surrenderBeforeVesting` false) refuses `"This pension cannot be surrendered or withdrawn from before it vests"`, on an **unlocked** one it is accepted.
  - `CommutationIntegrationTest`: `scheduleCommutation(policy, today, 1,250,000.00, TZS)` creates one SCHEDULED instalment, kind COMMUTATION, payee the policyholder's phone; a second call returns the same id; once the due drain runs it is DUE even though the policy has an unpaid contribution invoice (not held for premiums); review requires no proof of life; approval requests purpose `COMMUTATION_PAYOUT`; with an approved withholding rule naming `COMMUTATION` at 10%, net is 1,125,000.00; the rule's kinds may name `COMMUTATION` (Withholding derives its list from `PayoutKind`).
  - `PayoutPlanValidatorTest`: an authored row of kind COMMUTATION refuses `"COMMUTATION is a pension's lump sum at vesting, not an authored payout row"`.

- [ ] **Step 3: Implementation.**
  - `closeForVesting`: copy `closeForMaturity` (line 630) with source `new LedgerService.Source("vesting", "vesting:" + policyNumber)`, `EntryType.VESTING`, reason `"Vested"`, closed reason `"VESTED"`; the redelivery path filters `EntryType.VESTING`. A non-OPEN account returns `0.00` and posts nothing.
  - `requestWithdrawal` (both forms reach the unkeyed one): after `loadOpen`, `refuseIfLockedPension(account)`:
    ```java
    /** A locked pension (spec Q9): nothing leaves before it vests. Product first, as everywhere. */
    private void refuseIfLockedPension(Account account) {
        VestingTerms vesting = productApi.resolveAnnuityPlan(account.getProductVersionId()).vesting();
        if (vesting != null && !Boolean.TRUE.equals(vesting.surrenderBeforeVesting())) {
            throw new AccumulationStateException("This pension cannot be surrendered or withdrawn from before it vests");
        }
    }
    ```
    Use the account's own version id field (`grep -n "productVersionId" accumulation/domain/Account.java`).
  - `PayoutKind`: `COMMUTATION` with a javadoc line: "A pension's lump sum at vesting (D2). Never an authored row; the annuity module schedules it."
  - `scheduleCommutation`: idempotent on `(policy, kind COMMUTATION)` — return the existing instalment's id if one exists. `new PayoutInstalment(tenant, policy, PayoutKind.COMMUTATION, 0, null, dueDate, amount, currency)`; payee as the maturity path sets it (read how a MATURITY instalment gets its payee and do the same).
  - `purposeFor`: `case COMMUTATION -> "COMMUTATION_PAYOUT";`. The `fallDue` premium-hold line becomes `boolean upToDate = accountValue || i.kind() == PayoutKind.COMMUTATION || tally == null || tally.isPaidUpTo(i.getDueDate());` with a comment that the account it came from has already closed. `needsProofOfLife` is unchanged (COMMUTATION is not in it) — assert it in the test, don't edit it.
  - `PayoutPaymentListener`: add `COMMUTATION_PAYOUT` to the purposes it settles.

- [ ] **Step 4: Run and commit**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='VestingCloseIntegrationTest,CommutationIntegrationTest,PayoutPlanValidatorTest,WithholdingIntegrationTest,PayoutLifecycleIntegrationTest,AccumulationClosingIntegrationTest'
git commit -m "feat(accumulation,benefitpayout): an account closes for vesting, a locked pension refuses withdrawals, and a pension lump sum is its own payout kind"
```

(`AccumulationClosingIntegrationTest`: the class that tests `closeForMaturity` — `grep -rl "closeForMaturity" src/test/java` — so the copied close is proven not to have disturbed it.)

---

### Task 4: Policy and billing — the vesting on record, a deferral's billing, and the guards

**Files:**
- Create: `db-migrations/policy/V34__annuity_vesting.sql`; `policy/domain/AnnuityVestingEntity.java`, `policy/infrastructure/AnnuityVestingRepository.java`
- Modify: `policy/api/PolicyApi.java` + `PolicyApiImpl` (`recordVesting`, `extendPremiumPayingTerm`), `policy/domain/Policy.java` (`extendPremiumPayingTerm`), `PolicyApiImpl.closeAsSurrendered:1571`, `requestSurrender:1020`, `makePaidUp:890`
- Modify: `billing/application/PolicyEventListener.java` (+2 cases), `billing/application/BillingApiImpl.java` (+`endForVesting`, `restatePremiumPayingUntil`), `billing/domain/BillingSchedule.java` (+`restatePremiumPayingUntil`)
- Test: `annuity/PensionPolicyIntegrationTest.java` (new, `AnnuityTestMigrations.ALL`)

**Interfaces — Produces:**
```java
// PolicyApi
/** The pension vested on this date (D2): publishes policy.AnnuityVested. Idempotent. */
void recordVesting(String policyNumber, LocalDate vestedOn);
/** A deferral with contributions continuing: the premium-paying term runs to {@code until}. Publishes policy.PremiumPayingTermRestated. */
LocalDate extendPremiumPayingTerm(String policyNumber, LocalDate until);
```

- [ ] **Step 1: The migration**

```sql
-- db-migrations/policy/V34__annuity_vesting.sql
-- Product step 5 (D2): a deferred annuity's vesting, on the policy's own record. Policy cannot see
-- accumulation or annuity, and from this date the policy is an annuity in payment, not an account:
-- no surrender, no paid-up, and a settled death claim does not close it. Its own table, read only
-- after product says the version is deferred, so no policy test class needs it.
CREATE TABLE policy.annuity_vesting (
    policy_number  VARCHAR(20) PRIMARY KEY REFERENCES policy.policy(policy_number),
    tenant_id      UUID NOT NULL,
    vested_on      DATE NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE policy.annuity_vesting ENABLE ROW LEVEL SECURITY;
CREATE POLICY annuity_vesting_tenant_isolation ON policy.annuity_vesting
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT ON policy.annuity_vesting TO app_role;
```

Check `policy.policy`'s PK column type (`VARCHAR(20)` or wider) before copying.

- [ ] **Step 2: Failing tests** (`PensionPolicyIntegrationTest`, fixture from Task 2):
  - `recordVesting` publishes `policy.AnnuityVested` once; a second call publishes nothing. Billing's schedule is TERMINATED and every invoice not PAID with a due date on or after the vesting date is WAIVED with reason `"The pension vested on {date}; no further contributions are due"`; earlier unpaid invoices are WAIVED too, with the same reason (the account they would have credited has closed).
  - `extendPremiumPayingTerm(policy, 2045-06-15)` moves `premiumPayingTermMonths` and billing raises invoices past the old paying end on its next roll (call `BillingApiImpl.rollForward(scheduleId)` directly). Refused once today is after the current paying end: `"Contributions on policy P ended on D; a deferral recorded after that cannot restart them -- record it with contributions stopped"`.
  - After vesting: `requestSurrender` refuses `"Policy P is a pension in payment; it cannot be surrendered"`; `makePaidUp` refuses `"Policy P is a pension in payment; it has no contributions to stop"`.
  - Before vesting on a **locked** version: `requestSurrender` refuses `"This pension cannot be surrendered or withdrawn from before it vests"` (accumulation's words, the same rule). On an **unlocked** version it proceeds as any account policy.
  - A settled death claim **before** vesting closes the policy exactly as an account policy's does (`closeAsSurrendered` runs); **after** vesting it does not (D1's annuity rule).

- [ ] **Step 3: Implementation.**
  - `recordVesting`: `findPolicyOrThrow`; if the row exists, return; insert; publish `policy.AnnuityVested {policyNumber, vestedOn}`.
  - `Policy.extendPremiumPayingTerm(LocalDate until)`: refuses when closed, when `premiumFrequency` is SINGLE, and when `civil today` is after `premiumPayingUntil()` (message above); sets `premiumPayingTermMonths = (int) ChronoUnit.MONTHS.between(start, until)`; returns `premiumPayingUntil()`. `PolicyApiImpl.extendPremiumPayingTerm` saves and publishes `policy.PremiumPayingTermRestated {policyNumber, premiumPayingUntil}`.
  - A private `boolean vestedPension(Policy p)`: `productApi.resolveAnnuityPlan(p.getProductVersionId()).deferred() && annuityVestingRepository.existsById(p.getPolicyNumber())` — product first.
  - `closeAsSurrendered:1571` becomes `AnnuityPlan plan = ...; if (plan.annuity() && (!plan.deferred() || vestedPension(policy))) return;` with the comment extended: "A deferred annuity that has not vested is an account policy, and a death closes it like one (spec Q6)."
  - `requestSurrender`, after the status check: `if (vestedPension(policy))` → the after-vesting message; else `VestingTerms v = plan.vesting(); if (v != null && !Boolean.TRUE.equals(v.surrenderBeforeVesting()))` → the locked message.
  - `makePaidUp`, first: `if (vestedPension(policy))` → its message.
  - Billing: `case "policy.AnnuityVested" -> withTenant(envelope, this::handleAnnuityVested);` → `billingApiImpl.endForVesting(tenant, policyNumber, vestedOn)`: terminate the schedule as `terminateScheduleForExpiry` does, then `invoice.waive(reason)` on every invoice of the policy whose status is DUE, IN_GRACE, OVERDUE or PARTIALLY_PAID. `case "policy.PremiumPayingTermRestated"` → `restatePremiumPayingUntil(tenant, policyNumber, until)`: the ACTIVE schedule's `restatePremiumPayingUntil(until)`, saved; the roll-forward drain raises the rest.

- [ ] **Step 4: Run and commit**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='PensionPolicyIntegrationTest,PolicyApiIntegrationTest,BillingApiIntegrationTest,SurrenderIntegrationTest'
git commit -m "feat(policy,billing): a pension's vesting on the policy's record -- billing stops and waives, a deferral extends contributions, and surrender follows the version's lock"
```

(`SurrenderIntegrationTest`: whichever class covers `requestSurrender` — `grep -rl "requestSurrender" src/test/java`.)

---

### Task 5: The annuity module — the saving-phase contract and its seams with D1

**Files:**
- Create: `db-migrations/annuity/V2__deferred_annuities.sql`
- Create: `annuity/domain/Vesting.java`, `annuity/infrastructure/VestingRepository.java`, `annuity/api/VestingView.java`
- Modify: `annuity/api/ContractStatus.java` (+`ACCUMULATING`), `annuity/api/AnnuityApi.java` (+`decidesDeath`, `vesting`), `annuity/domain/AnnuityContract.java` (+`accumulating`, `endedBeforeVesting`, `cancelledBeforeVesting`, `deferred`, `vestedOn`), `AnnuityApiImpl` (`onIssued` branch, `onDeathApproved` branch, `onSurrendered`), `PolicyEventListener` (+`policy.PolicySurrendered`), `claims/application/ClaimsApiImpl.java:471,533` (`decidesDeath`)
- Test: `annuity/DeferredContractIntegrationTest.java`

**Interfaces — Produces:**
```java
public enum ContractStatus { ACCUMULATING, AWAITING_PAYMENT, IN_PAYMENT, SURVIVOR, GUARANTEE, ENDED, CANCELLED, LOCK_FAILED }
public record VestingView(String policyNumber, LocalDate targetDate, LocalDate earliestVestingDate,
                          LocalDate latestVestingDate, LocalDate vestingDate, String formCode, String frequency,
                          UUID jointLifePartyId, BigDecimal lumpSumPercent, BigDecimal maxCommutationPercent,
                          String holdReason, Instant heldAt, LocalDate vestedOn, BigDecimal vestedBalance,
                          BigDecimal lumpSum, String ageConfirmedBy, LocalDate confirmedDateOfBirth, String confirmedSex) {}
// AnnuityApi
/** Whether a death on this policy is the annuity's to value and settle: false while it is still saving, so claims values the account. */
boolean decidesDeath(String policyNumber);
Optional<VestingView> vesting(String policyNumber);
```

- [ ] **Step 1: The schema**

```sql
-- db-migrations/annuity/V2__deferred_annuities.sql
-- Product step 5 (D2): a deferred annuity's contract exists from issue, ACCUMULATING, with no form,
-- price or lock until it vests. The vesting row holds the target, the window, what was confirmed
-- at sale, any hold, and the reminders sent; the instruction table keeps every instruction ever
-- recorded, with one current.
ALTER TABLE annuity.contract DROP CONSTRAINT contract_status_check;
ALTER TABLE annuity.contract ADD CONSTRAINT contract_status_check CHECK (status IN
    ('ACCUMULATING','AWAITING_PAYMENT','IN_PAYMENT','SURVIVOR','GUARANTEE','ENDED','CANCELLED','LOCK_FAILED'));
ALTER TABLE annuity.contract
    ADD COLUMN deferred            BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN vested_on           DATE,
    ADD COLUMN priced_version_id   UUID,
    ADD COLUMN end_reason          VARCHAR(200),
    ALTER COLUMN purchase_price DROP NOT NULL;
-- Before vesting a deferred contract has no form and no price; ended or cancelled before vesting it
-- never will. Every other contract keeps D1's shape.
ALTER TABLE annuity.contract DROP CONSTRAINT contract_check;     -- the lock-shape CHECK
ALTER TABLE annuity.contract DROP CONSTRAINT contract_check1;    -- the form CHECK
ALTER TABLE annuity.contract ADD CONSTRAINT contract_lock_shape CHECK (
    status IN ('AWAITING_PAYMENT','CANCELLED','LOCK_FAILED','ACCUMULATING')
    OR (deferred AND vested_on IS NULL AND status = 'ENDED')
    OR (locked_on IS NOT NULL AND instalment > 0 AND first_due_date IS NOT NULL));
ALTER TABLE annuity.contract ADD CONSTRAINT contract_form_shape CHECK (
    status = 'LOCK_FAILED' OR form_code IS NOT NULL OR (deferred AND vested_on IS NULL));
ALTER TABLE annuity.contract ADD CONSTRAINT contract_price_shape CHECK (
    purchase_price > 0 OR (purchase_price IS NULL AND deferred AND vested_on IS NULL));

CREATE TABLE annuity.vesting (
    policy_number           VARCHAR(30) PRIMARY KEY REFERENCES annuity.contract(policy_number),
    tenant_id               UUID NOT NULL,
    -- The ages are kept so a re-confirmed date of birth can move all three dates (Task 6).
    retirement_age          INTEGER NOT NULL,
    min_vesting_age         INTEGER NOT NULL,
    max_vesting_age         INTEGER NOT NULL,
    target_date             DATE NOT NULL,
    earliest_vesting_date   DATE NOT NULL,
    latest_vesting_date     DATE NOT NULL,
    max_commutation_percent NUMERIC(9,4) NOT NULL,
    default_form_code       VARCHAR(30) NOT NULL,
    default_frequency       VARCHAR(12) NOT NULL,
    confirmed_date_of_birth DATE NOT NULL,
    confirmed_sex           VARCHAR(10),
    age_confirmed_by        VARCHAR(100) NOT NULL,
    age_confirmed_at        TIMESTAMPTZ NOT NULL,
    death_reported_claim_id UUID,
    hold_reason             VARCHAR(500),
    held_at                 TIMESTAMPTZ,
    reminded_90_for         DATE,
    reminded_30_for         DATE,
    vested_balance          NUMERIC(19,2),
    lump_sum                NUMERIC(19,2),
    version                 BIGINT NOT NULL DEFAULT 0,
    CHECK (earliest_vesting_date <= target_date AND target_date <= latest_vesting_date),
    CHECK ((hold_reason IS NULL) = (held_at IS NULL))
);

CREATE TABLE annuity.vesting_instruction (
    instruction_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL,
    policy_number        VARCHAR(30) NOT NULL REFERENCES annuity.vesting(policy_number),
    vesting_date         DATE NOT NULL,
    form_code            VARCHAR(30) NOT NULL,
    frequency            VARCHAR(12) NOT NULL,
    joint_life_party_id  UUID,
    lump_sum_percent     NUMERIC(9,4) NOT NULL CHECK (lump_sum_percent BETWEEN 0 AND 100),
    contributions        VARCHAR(10) CHECK (contributions IN ('CONTINUE','STOP')),
    recorded_by          VARCHAR(100) NOT NULL,
    recorded_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    current              BOOLEAN NOT NULL
);
CREATE UNIQUE INDEX ux_vesting_instruction_current ON annuity.vesting_instruction (policy_number) WHERE current;

-- Contracts due to vest on or before a civil date, across tenants, for the sweep (ids only; the
-- sweep sets the tenant per row and vests each under RLS). The vesting date is the current
-- instruction's, else the target.
CREATE OR REPLACE FUNCTION annuity.vestings_due(as_of DATE)
RETURNS TABLE (policy_number VARCHAR, tenant_id UUID) LANGUAGE sql SECURITY DEFINER SET search_path = annuity AS $$
    SELECT c.policy_number, c.tenant_id
      FROM annuity.contract c
      JOIN annuity.vesting v ON v.policy_number = c.policy_number
      LEFT JOIN annuity.vesting_instruction i ON i.policy_number = c.policy_number AND i.current
     WHERE c.status = 'ACCUMULATING' AND COALESCE(i.vesting_date, v.target_date) <= as_of
     ORDER BY COALESCE(i.vesting_date, v.target_date)
     LIMIT 500
$$;
REVOKE EXECUTE ON FUNCTION annuity.vestings_due(DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION annuity.vestings_due(DATE) TO app_role;

-- Contracts whose reminders may be due: target within 90 days, for the reminder pass.
CREATE OR REPLACE FUNCTION annuity.vesting_reminders_due(as_of DATE)
RETURNS TABLE (policy_number VARCHAR, tenant_id UUID) LANGUAGE sql SECURITY DEFINER SET search_path = annuity AS $$
    SELECT c.policy_number, c.tenant_id
      FROM annuity.contract c
      JOIN annuity.vesting v ON v.policy_number = c.policy_number
      LEFT JOIN annuity.vesting_instruction i ON i.policy_number = c.policy_number AND i.current
     WHERE c.status = 'ACCUMULATING' AND COALESCE(i.vesting_date, v.target_date) - as_of BETWEEN 0 AND 90
     LIMIT 500
$$;
REVOKE EXECUTE ON FUNCTION annuity.vesting_reminders_due(DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION annuity.vesting_reminders_due(DATE) TO app_role;

ALTER TABLE annuity.vesting ENABLE ROW LEVEL SECURITY;
CREATE POLICY vesting_tenant_isolation ON annuity.vesting
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE annuity.vesting_instruction ENABLE ROW LEVEL SECURITY;
CREATE POLICY vesting_instruction_tenant_isolation ON annuity.vesting_instruction
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON annuity.vesting, annuity.vesting_instruction TO app_role;
```

**Verify the auto-generated constraint names before writing the DROPs**: V1's status CHECK is inline (`contract_status_check`) and its two table CHECKs are unnamed (Postgres names them `contract_check`, `contract_check1` in declaration order). Confirm on a Testcontainers DB with `SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'annuity.contract'::regclass;` (a throwaway test or `psql` against the dev DB, which has V1) and use the real names. Copy `SECURITY DEFINER` exactly as `benefitpayout.annuity_streams_due_for_rollforward` declares it.

The trigger `contract_lock_is_final` is unchanged: before vesting `locked_on` is null, so the vesting's one write of the lock passes it, and every write after it is refused as in D1.

Add annuity V2 to `AnnuityTestMigrations.ALL` (every annuity class maps `AnnuityContract`, which gains columns) together with product V23, underwriting V15, policy V34, accumulation V4, benefitpayout V5, payment V12.

- [ ] **Step 2: Failing tests** (`DeferredContractIntegrationTest`, Task 2's fixture through acceptance):
  - Issue creates a contract `ACCUMULATING`, `deferred` true, no form, no price; `vesting(policy)` shows target 2040-06-15, earliest 2035-06-15 (age 55), latest 2050-06-15 (age 70), cap 25, default LIFE-0G/MONTHLY, the confirmed date of birth and sex, and who confirmed.
  - A contribution collected (`billing.PremiumCollected`) changes nothing on the contract (D1's lock looks only at `AWAITING_PAYMENT`).
  - `decidesDeath` is **false** while ACCUMULATING; a DEATH claim's ceiling on the policy is the **account at the death** (benefitpayout's account ceiling), not D1's zero. After approval: contract `ENDED`, `end_reason` "Died before vesting", no stream, and the policy closes as an account policy's does (Task 4).
  - A surrender approved on an unlocked deferred annuity: contract `CANCELLED`, `end_reason` "Surrendered before vesting".
  - Free-look: contract `CANCELLED` (D1's `onFreeLookCancelled`, unchanged — assert it tolerates no stream).
  - An immediate annuity is untouched: `AnnuityLockIntegrationTest` and `AnnuityDeathIntegrationTest` pass unchanged.

- [ ] **Step 3: Implementation.**
  - `AnnuityContract.accumulating(tenantId, policyNumber, soldVersionId, annuitantPartyId, currency)`: status ACCUMULATING, `deferred = true`, `purchasePrice = null`. `endedBeforeVesting(String reason)` and `cancelledBeforeVesting(String reason)` set status and `endReason`; both refuse unless ACCUMULATING.
  - `Vesting` entity on `annuity.vesting`, created with the contract. It stores the retirement age and the **sold** version's minimum and maximum vesting ages, and derives the dates from the **confirmed** date of birth: `earliest = dob.plusYears(min)`, `latest = dob.plusYears(max)`, `target = dob.plusYears(retirementAge)`. Cap and defaults are copied from the sold version too.
  - `AnnuityApiImpl.onIssued`, first thing after `plan.annuity()`: `if (plan.deferred()) { onDeferredIssued(policyNumber, productVersionId, underwritingCaseId, plan.vesting()); return; }`. It reads `underwritingApi.deferredAnnuityChoice(caseId)`; with no case or no confirmed choice, it records D1's `failedAtIssue` with reason `"A deferred annuity is issued from an underwriting case that records the retirement age and confirmed proof of age, and this policy has none"`.
  - `decidesDeath(policy)`: `versionIfAnnuity(policy).isPresent()` then `contracts.findById(policy).map(c -> !(c.isDeferred() && c.getVestedOn() == null)).orElse(false)`.
  - `ClaimsApiImpl` lines 471 and 533: `annuityApi.isAnnuity(...)` → `annuityApi.decidesDeath(...)`. Nothing else in claims changes.
  - `onDeathApproved`: before the IN_PAYMENT/SURVIVOR check, `if (c.status() == ContractStatus.ACCUMULATING) { c.endedBeforeVesting("Died before vesting"); contracts.save(c); return; }`. The account pays the balance through the existing account death closing; nothing here moves money.
  - `PolicyEventListener`: `policy.PolicySurrendered` → `api.onSurrendered(policy)`: an ACCUMULATING contract → `cancelledBeforeVesting("Surrendered before vesting")`; any other status, nothing.

- [ ] **Step 4: Run and commit**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='DeferredContractIntegrationTest,AnnuityLockIntegrationTest,AnnuityDeathIntegrationTest,AnnuityContractTest,ClaimsApiIntegrationTest,ModulithArchitectureTest'
git commit -m "feat(annuity): a deferred annuity's contract saves from issue -- its death and surrender before vesting are the account's, and claims asks whether the annuity decides a death"
```

---

### Task 6: The vesting — the instruction, the sweep, the holds

**Files:**
- Create: `annuity/domain/VestingInstruction.java`, `annuity/domain/CommutationSplit.java` (pure), `annuity/domain/VestingRules.java` (pure), `annuity/infrastructure/VestingInstructionRepository.java`, `annuity/application/AnnuityVesting.java`, `annuity/application/VestingSweep.java`, `annuity/infrastructure/VestingController.java`, `VestingInstructionRequest.java`, `VestingResponse.java`
- Modify: `annuity/api/AnnuityApi.java` (+`recordVestingInstruction`, `reconfirmAge`, `listHeldVestings`), `AnnuityApiImpl`, `annuity/application/ClaimEventListener.java` (+`claims.ClaimRegistered`, `claims.ClaimRejected`), `annuity/domain/AnnuityContract.java` (+`vest`), `annuity/package-info.java` (+`"accumulation::api"`), `api/openapi/openapi-annuity.yaml`, `src/main/resources/application-local.yml`
- Test: `annuity/CommutationSplitTest.java`, `annuity/VestingRulesTest.java` (pure), `annuity/VestingIntegrationTest.java`, extend `AnnuityContractTest`

**Interfaces — Produces:**
```java
public record VestingInstructionInput(LocalDate vestingDate, String formCode, String frequency, UUID jointLifePartyId,
                                      BigDecimal lumpSumPercent, String contributions) {}   // contributions: CONTINUE | STOP | null
// AnnuityApi
VestingView recordVestingInstruction(String policyNumber, VestingInstructionInput input, String recordedBy);
VestingView reconfirmAge(String policyNumber, String confirmedBy);
List<VestingView> listHeldVestings();
// CommutationSplit (pure)
record Split(BigDecimal lumpSum, BigDecimal purchasePrice) {}
static Split of(BigDecimal balance, BigDecimal lumpSumPercent);     // lump = balance × pct ÷ 100, HALF_EVEN, once
// AnnuityVesting
/** Vest one policy, or hold it with a reason. Never throws; its own transaction. Returns the outcome for the sweep's log. */
String vest(String policyNumber, LocalDate today);
```

- [ ] **Step 1: Failing pure tests.**

```java
    @Test void theLumpSumIsRoundedOnceAndThePriceIsTheRest() {
        var s = CommutationSplit.of(new BigDecimal("48123456.79"), new BigDecimal("25"));
        assertThat(s.lumpSum()).isEqualByComparingTo("12030864.20");      // 12,030,864.1975 → .20
        assertThat(s.purchasePrice()).isEqualByComparingTo("36092592.59");
        assertThat(s.lumpSum().add(s.purchasePrice())).isEqualByComparingTo("48123456.79");
    }

    @Test void noLumpSumBuysWithTheWholeBalance() {
        var s = CommutationSplit.of(new BigDecimal("1000.00"), BigDecimal.ZERO);
        assertThat(s.lumpSum()).isEqualByComparingTo("0.00");
        assertThat(s.purchasePrice()).isEqualByComparingTo("1000.00");
    }
```

`VestingRulesTest` — `VestingRules.check(VestingInstructionInput in, LocalDate today, LocalDate target, LocalDate earliest, LocalDate latest, BigDecimal cap)` returns nothing or throws `IllegalArgumentException` with:

| Rule | Message |
|---|---|
| date before today | `A vesting date cannot be in the past` |
| date before earliest | `The earliest this pension can vest is {earliest}, at the minimum vesting age` |
| date after latest | `The latest this pension can be deferred to is {latest}, at the maximum vesting age` |
| deferral without contributions | `A deferral must say whether contributions continue to the new date or stop at {target}` |
| contributions on a non-deferral | `Only a deferral says what happens to contributions` |
| percent < 0 or > cap | `The lump sum can be from 0% to {cap}% of the balance` |

- [ ] **Step 2: Failing integration tests** (`VestingIntegrationTest`). The fixture sells the Task 2 policy, collects contributions, and **moves the vesting date into reach** by recording an early instruction for "today" with an annuitant born 1971-01-10 (age 55 on 2026-10-04 or later — pick the DOB so the civil today is past the 55th birthday; compute it from `today` in the fixture, not a literal). The sweep is called directly: `annuityVesting.vest(policy, today)`.
  - **Default vesting on the target date**: with no instruction and the target = today, the contract is `IN_PAYMENT`, form LIFE-0G, MONTHLY, `purchasePrice` = the closed balance, `vestedOn` today, `pricedVersionId` = the active version; no COMMUTATION instalment; the account is CLOSED/VESTED; `policy.AnnuityVested` was published; a stream of ANNUITY instalments exists.
  - **Early vesting with 25% lump sum, priced by a newer version**: publish a second version of the product with higher LIFE-0G rates **after** the sale; vest with `lumpSumPercent` 25 → one COMMUTATION instalment of `CommutationSplit.of(balance, 25).lumpSum()`, the contract priced from the **second** version's rate (assert `annualRatePerMille`), `purchasePrice` = the rest.
  - **Deferral, contributions continue**: instruction for target + 2 years, CONTINUE → the policy's premium-paying term ends on the new date; the sweep on the old target does nothing.
  - **Deferral, contributions stop**: the premium-paying term unchanged; the sweep on the old target does nothing.
  - **Age hold**: change the party's date of birth after the sale; the sweep holds with `"Age re-confirmation needed: the date of birth or sex on record has changed since it was confirmed"`, account still OPEN, no instalment. `reconfirmAge` records the new pair; the next sweep vests.
  - **Pricing hold**: an instruction naming a form the current version does not offer (publish a new version without it after recording) → hold with the pricer's own words; account untouched; changing the instruction clears it and the next sweep vests.
  - **A death reported before vesting holds it**: register a DEATH claim → the sweep holds `"A death has been reported on this policy; it vests only if that claim is rejected"`; reject the claim → the next sweep vests.
  - **Atomicity**: make `scheduleCommutation` fail (a `@MockitoSpyBean` throwing on the next call) → nothing committed: account OPEN, contract ACCUMULATING with the failure recorded as a hold; the next sweep (spy reset) vests.
  - **Once only**: vesting twice changes nothing the second time.
  - **The vested pension is never lapsed as an exhausted account**: after vesting, run accumulation's month-end drain for the month — the policy stays ACTIVE.

- [ ] **Step 3: Implementation.**
  - `recordVestingInstruction`: the contract must be ACCUMULATING (`"Policy P has already vested"` / `"... is not a deferred annuity"`). Resolve the **current** version: `productApi.getActiveSnapshot(policy.productId(), today).productVersionId()` and its `resolveAnnuityPlan`; the form and frequency must be on it (D1 `recordAnnuityChoice`'s words: `"This version does not offer form X"`, `"This version does not offer F payments"`); the joint-life rules are D1's (`recordAnnuityChoice` lines 650-662 — move them to a shared static helper in `product.api`? No: copy the five checks into `VestingRules.checkJointLife`, since underwriting and annuity may not share a domain class). `VestingRules.check(...)`. A null `vestingDate` means the target. If `contributions == CONTINUE`, call `policyApi.extendPremiumPayingTerm(policy, vestingDate)` and let its refusal surface as a 422. Mark the previous instruction not current, insert the new one, clear any hold, and **re-arm reminders** (null both `reminded_*_for`) when the date changed.
  - `AnnuityVesting` has two public methods, each `@Transactional(propagation = REQUIRES_NEW)`: `vest(policy, today)` and `hold(policy, reason)`. The sweep calls `vest`; **steps 1–3 touch nothing, so they write the hold and return inside `vest`'s own transaction**; a `RuntimeException` from steps 4–6 propagates out of `vest`, its transaction rolls back whole, and the sweep then calls `hold(policy, "Vesting failed and was rolled back: " + message)` — a separate transaction, so the hold survives the rollback. In order:
    1. `death_reported_claim_id != null` → hold (message above).
    2. Age: the party's current DOB/sex vs the confirmed pair → hold (spec message). Then the vesting date (instruction's or target) against the window → a `VestingRules` refusal becomes the hold, in its own words (a re-confirmed date of birth can move the window under an instruction).
    3. Price: instruction or defaults; `currentVersion = getActiveSnapshot(productId, vestingDate).productVersionId()`; `productApi.priceAnnuity(currentVersion, input(..., provisional price = accumulationApi.findAccount(policy).balance, pricingDate = vestingDate))` — any `AnnuityPricingRefusedException` → hold with its message. Nothing touched yet.
    4. `B = accumulationApi.closeForVesting(policy, vestingDate)`.
    5. `split = CommutationSplit.of(B, pct)`; if `split.lumpSum() > 0` → `payouts.scheduleCommutation(policy, vestingDate, lumpSum, currency)`.
    6. Re-price with the real purchase price (same inputs, `split.purchasePrice()`), `contract.vest(form, plan, frequency, joint, purchasePrice, price, vestingDate, firstDue, guaranteeEnd, currentVersion)` → IN_PAYMENT; `payouts.openAnnuityStream(...)` exactly as D1's `onPremiumCollected` does after its lock (first due by the **current** version's timing); `policyApi.recordVesting(policy, vestingDate)`; record `vested_balance`, `lump_sum`; clear the hold.
  - `reconfirmAge(policy, by)`: only while the age hold stands (`"Policy P is not held for age re-confirmation"`); records the party's **current** date of birth and sex, confirmer and time, and recomputes `target`, `earliest` and `latest` from the stored ages and the new date of birth; clears the hold. Its test: a date of birth moved a year later moves all three dates a year later.
  - `AnnuityContract.vest(...)`: copies the form's six settings, timing, proof-of-life interval, frequency, joint life, `purchasePrice`, `pricedVersionId`, `vestedOn`, then calls the existing `lock(price, vestingDate, firstDue, guaranteeEnd)`.
  - `VestingSweep`: `@Scheduled(fixedDelayString = "${annuity.vesting-sweep-interval-ms:86400000}", initialDelayString = "${annuity.vesting-sweep-interval-ms:86400000}")`; reads `annuity.vestings_due(civil today)` through a native query on `VestingRepository`; per row sets the tenant, calls `vest`, catches and logs everything (copy `AnnuityRollForward.rollOne`). `application-local.yml`: `annuity.vesting-sweep-interval-ms: 60000`.
  - `ClaimEventListener`: `claims.ClaimRegistered` with `claimType` DEATH on an ACCUMULATING contract → set `death_reported_claim_id`; `claims.ClaimRejected` for that claim id → clear it. First check the payloads carry `claimType` and `claimId`: `grep -n "claims.ClaimRegistered\|claims.ClaimRejected" -A6 src/main/java/tz/co/nlolo/lifeplatform/claims/application/ClaimsApiImpl.java`; if `claimType` is missing from either, add it to that payload (additive).
  - `annuity/package-info.java`: add `"accumulation::api"` to `allowedDependencies` and a sentence to the javadoc: "accumulation::api to close the account at vesting (D2)".
  - REST (`VestingController`, `REALM_STAFF`, the roles that service policies — copy D1's `AnnuityController` guard): `GET /policies/{n}/annuity/vesting` (404 `NOT_A_DEFERRED_ANNUITY`), `PUT /policies/{n}/annuity/vesting/instruction`, `POST /policies/{n}/annuity/vesting/reconfirm-age`, `GET /annuity-vestings/held`. OpenAPI for all four; `AnnuityContractTest` asserts every `VestingResponse` field by name.

- [ ] **Step 4: Run and commit**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='CommutationSplitTest,VestingRulesTest,VestingIntegrationTest,DeferredContractIntegrationTest,AnnuityContractTest,AnnuityLockIntegrationTest,ModulithArchitectureTest'
git commit -m "feat(annuity): a pension vests -- on its target or as instructed, priced at the day's rates, the lump sum paid apart, held with a reason when it cannot, and never half-done"
```

---

### Task 7: Reminders — 90 and 30 days before the vesting date

**Files:**
- Create: `db-migrations/communication/V11__vesting_reminder_template.sql`, `annuity/application/VestingReminderSweep.java`, `communication/application/AnnuityEventListener.java`
- Modify: `annuity/domain/Vesting.java` (+`remind(int days, LocalDate forDate)`), `application-local.yml`
- Test: `annuity/VestingReminderIntegrationTest.java` (uses `AnnuityTestMigrations.ALL` + communication's migrations — copy the list `AccumulationStatementNotificationTest`, or whichever class asserts `ACCOUNT_STATEMENT` dispatches, uses: `grep -rl "ACCOUNT_STATEMENT" src/test/java`)

- [ ] **Step 1: The template** — copy V10's shape exactly; key `PENSION_VESTING_REMINDER`; placeholders `policyNumber`, `vestingDate`:
  - en SMS/EMAIL: `Policy {{policyNumber}}: your pension starts on {{vestingDate}}. Contact us to choose how it is paid.`
  - sw SMS/EMAIL: `Bima {{policyNumber}}: pensheni yako itaanza tarehe {{vestingDate}}. Wasiliana nasi kuchagua jinsi itakavyolipwa.`

- [ ] **Step 2: Failing tests.** A contract whose vesting date is 90 days away gets one `PENSION_VESTING_REMINDER` dispatch; running the pass again sends nothing; at 30 days, one more; a deferral re-arms (a new date 90 days away sends again); a contract already vested sends nothing.

- [ ] **Step 3: Implementation.** `VestingReminderSweep` (`annuity.vesting-reminder-interval-ms`, daily; local 60000) reads `annuity.vesting_reminders_due(today)`; per row, for `days` in (90, 30): if `vestingDate - today <= days` and `reminded_{days}_for` is not the vesting date → publish `annuity.VestingReminderDue {policyNumber, policyholderPartyId, vestingDate, daysBefore}` and set `reminded_{days}_for = vestingDate`, in one transaction (the AFTER_COMMIT listener sends only if the flag committed). Within 30 days and never reminded at 90 (a policy sold late), send the 30-day one only. `communication.AnnuityEventListener` copies `AccumulationEventListener`, filters `annuity.VestingReminderDue`, and calls `notificationApi.notify(envelope.eventId(), policyholderPartyId, policyNumber, "PENSION_VESTING_REMINDER", Map.of("policyNumber", ..., "vestingDate", dd/MM/yyyy))`.

- [ ] **Step 4: Run and commit**

```bash
./mvnw -o test -Dtest='VestingReminderIntegrationTest,ModulithArchitectureTest'
git commit -m "feat(annuity,communication): a pension's holder is told 90 and 30 days before it vests, once each, and again after a deferral"
```

---

### Task 8: The console

**Files:**
- Create: `src/features/products/vestingSchema.ts` (+test), `src/features/annuities/PolicyVestingPanel.tsx` (+test), `src/features/annuities/VestingInstructionForm.tsx`, `src/features/annuities/vestingForm.ts` (+test), `src/features/annuities/HeldVestingsPage.tsx`
- Modify: `src/api/types.ts`, `src/api/annuity.ts`, `src/store/annuityStore.ts`, `src/lib/status.ts` (`ACCUMULATING: 'info'` on `annuityContract`), `src/features/products/AnnuityTermsSection.tsx` + `annuitySchema.ts` + `publishVersionSchema.ts` + `PublishVersionForm.tsx`, `src/features/underwriting/openCaseForm.ts` + `OpenUnderwritingCasePage.tsx` + `UnderwritingCaseDetailPage.tsx`, `src/features/policies/PolicyDetailPage.tsx` (Annuity tab chooses saving or paying panel), `src/features/payouts/*` (label), `src/lazyPages.ts`, `src/screens.tsx` (Held vestings under Policies)

Copy D1's console files for shape and wording; invent nothing.

- [ ] **Step 1: Types.** `npm run generate:api`; aliases `VestingTermsSpec`, `VestingView`, `VestingInstructionInput`.
- [ ] **Step 2: api + store.** `getPolicyVesting` returns null on 404 (`isNotFound`). `recordVestingInstruction`, `reconfirmAge`, `listHeldVestings`. Store keyed by policy, built exactly like `annuityStore.ts`.
- [ ] **Step 3: The publish form, test first.** On ANNUITY a radio `Annuity kind`: `Immediate (bought with a single premium)` | `Deferred (saves in an account, then vests)`. Deferred shows the existing account section and a **Vesting** section: `Minimum vesting age`, `Maximum vesting age`, `Default form` (select of this form's own form codes), `Default frequency`, `Lump-sum cap (%)`, `Locked before vesting` (radio Yes/No, **no default**). `vestingSchema.ts` mirrors `VestingPlanValidator` message for message (Task 1's table); one schema test per message landing on a rendered path. The account section is hidden on an immediate annuity and shown on a deferred one; the payload sends `annuity.vesting` only when deferred.
- [ ] **Step 4: The case.** On a deferred version, `Retirement age` replaces D1's form/frequency/joint fields and the live quote; under it, `Vests on {date}` computed from the applicant's date of birth (or "Record the applicant's date of birth to see the vesting date"). The Sum assured field is relabelled `Contribution per payment`. The decision panel keeps D1's `Age evidence confirmed`.
- [ ] **Step 5: The Annuity tab, saving phase.** `PolicyVestingPanel` when the contract is ACCUMULATING (else D1's `PolicyAnnuityPanel`): headline `Vests on {date}`; fields — target date, window (`{earliest} to {latest}`), current balance (from the existing account read), what it will vest into (`{form in words} · {frequency} · lump sum {pct}%` or `the default: …, no lump sum`), confirmed date of birth and sex and who confirmed; an error panel with the hold reason when held. Actions: `Record vesting instruction` (opens `VestingInstructionForm`: `Vesting date`, `Form`, `Payment frequency`, `Joint life` (only on a joint form), `Lump sum (%)` with the cap in the hint, `Contributions` radio `Continue to the new date` | `Stop at {target}` shown only when the date is after the target); `Re-confirm age` only when the hold is the age hold. `vestingForm.ts` mirrors `VestingRules` message for message, tested.
- [ ] **Step 6: Held vestings.** `HeldVestingsPage` under Policies: a table `aria-label="Held vestings"` — policy, vesting date, hold reason, held since; empty state "No held vesting" / "Every pension due to vest has vested." Each row links to the policy's Annuity tab.
- [ ] **Step 7: Payouts.** A COMMUTATION instalment is labelled `Pension lump sum` wherever a kind is shown (`grep -rn "ANNUITY:" src/lib src/features/payouts` for the label map).
- [ ] **Step 8: Run and commit**

```bash
cd frontend
npm run typecheck ; npm run lint ; npx vitest run src/features/products src/features/annuities src/features/underwriting src/features/policies src/features/payouts src/lib
git add src
git commit -m "feat(console): publish a deferred annuity, sell it by retirement age, see it save, instruct or defer its vesting, and find the held ones"
```

---

### Task 9: Seed and e2e

**Files:**
- Modify: `backend/scripts/seed-dev-data.sh` (product `PEN-DEF-01`)
- Create: `frontend/e2e/staff-deferred-annuity.spec.ts`

- [ ] **Step 1: Seed** — after `ANN-LIFE-01`: "Nlolo Pensheni Akiba", ANNUITY, entry 18–55, contribution bounds 50,000–10,000,000, free-look 15, ACCOUNT basis (guaranteed 4%, minimum balance 0, one charge row from year 1), timing ARREARS, proof of life 12, one form `LIFE-10G` (10-year guarantee, UNISEX) with rates for ages 55–70 generated by the script's existing `awk` loop (`60 + (age − 55) × 1.5`, basis `DEMO-BASIS-NOT-ACTUARIAL`), MONTHLY 0.98 and ANNUAL 1; vesting 55–70, default LIFE-10G MONTHLY, cap 25%, **locked**. Apply product V23, underwriting V15, policy V34, accumulation V4, benefitpayout V5, payment V12, communication V11 and annuity V2 to the dev DB by hand in module order (`psql -v ON_ERROR_STOP=1 -1`), then restart the dev backend.
- [ ] **Step 2: The spec** — one test, `test.setTimeout(300_000)`, `staff-admin.json`, today by `todayIso()`:
  1. publish its own deferred product through the form (code `PEN-E2E-${Date.now()}`, entry 18–85 so a fixture client qualifies, vesting **18–85** so today can be inside the window, LIFE-0G constant rate 72, MONTHLY 0.98, cap 25%, unlocked);
  2. register a client born 30 years before today (the spec computes the date), open a case with `Retirement age` 60 and contribution 200,000 MONTHLY, see `Vests on …`, accept with `Age evidence confirmed`;
  3. collect the first contribution on the Billing tab through the mock rail (copy the deposit spec's block);
  4. on the Annuity tab see `Vests on`, record an instruction: vesting date **today**, LIFE-0G, MONTHLY, lump sum 25%;
  5. poll the Annuity tab (the local sweep runs every 60s) until the paying panel shows `In payment`;
  6. on Payouts, one `Pension lump sum` instalment of 25% of the vested balance is listed.

Before running, check every label against what Task 8 rendered.

- [ ] **Step 3: Run it alone, then commit**

```bash
cd frontend
npx playwright test e2e/staff-deferred-annuity.spec.ts
git add e2e ../backend/scripts/seed-dev-data.sh
git commit -m "test(e2e): a pension sold by retirement age, funded, instructed to vest today with a lump sum, and paying"
```

---

### Task 10: The gate

- [ ] Stop the dev backend and any orphan non-`redhat.java` JVM (by command line).
- [ ] `./mvnw -B clean test > log 2>&1; echo "mvn exit $?"` in the background — never piped through `head`. No edits while it runs. Expected: green, above D1's count.
- [ ] Restart the dev backend; full Playwright suite with setup deps on a quiet, awake machine; `--last-failed` with fresh auth for anything that fails late; every remaining failure diagnosed before merging.
- [ ] Whole-branch review of the seams:
  - every D1 path that asks `isAnnuity` was checked for an ACCUMULATING contract: the lock (status filter), death ceiling and zero approval (`decidesDeath`), death approval (ended before vesting), free-look, not-taken-up, `closeAsSurrendered` (vested only);
  - `resolveAnnuityPlan` reads V23 only after the category gate; policy reads V34 only after `deferred()`;
  - vesting steps 3–6 commit together or not at all, and a hold survives the rollback;
  - annuity terms at vesting come from the **current** version, vesting terms and the window from the **sold** one;
  - billing waives every unpaid invoice at vesting, and nothing can credit a closed account;
  - the lump sum is never held for premiums and never asks proof of life;
  - every date is civil; no selector returns a fresh object.
- [ ] Merge `--no-ff` into `main` with the gate figures, push `origin main`; update memory (`project_product_step5_d2_deferred.md`).
- [ ] Live walkthrough on the dev stack: publish, sell, contribute, instruct an early vesting with a lump sum, watch the sweep vest it, approve the lump sum net of any rule. Report the figures.

---

## R. Revisions to the spec made while planning (2026-10-04) — for the user to confirm

- **R1 — vesting terms ride on `AnnuityPlan`** as a 10th component (`vesting`, null for an immediate annuity), not as an 11th `publishVersion` overload or a separate `resolveVestingPlan`. A deferred version is "an annuity plan with vesting terms", and every caller already resolves the annuity plan. The 9-argument constructor stays, so no D1 caller changes.
- **R2 — a deferred annuity has no policy term and no maturity date.** Spec §4 made the target date the maturity date. But step 0's expiry drain expires any in-force policy on its maturity date that has no MATURITY coverage, so the policy would have raced the vesting sweep and expired on its vesting day. A deferral would also have had to move a stored maturity date (`policy_maturity_matches_term` pins it to the term). Instead the premium-paying term runs to the target date, which `applyTerm` and the V6 CHECKs already allow without a policy term. The vesting date lives on the annuity contract, where the sweep reads it.
- **R3 — the case's sum assured is the contribution per payment.** Spec §4 said "the proposal's premium and frequency", but a case carries no premium, only a sum assured and a frequency. As D1 relabelled sum assured "Purchase price", D2 relabels it "Contribution per payment", and the policy issues with premium = sum assured. The version's sum-assured bounds therefore bound the contribution.
- **R4 — no new posting rule at vesting.** Spec §6.4 asked for DR policyholder account liability / CR annuity premium / CR benefits payable, with the accounts to be confirmed against the chart. Confirmed: the chart has **no** policyholder-account liability. Contributions (an account policy's) and a D1 single premium both sit in 2140 Unearned Premium, and accumulation's interest is never booked (finaccounting does not listen to accumulation at all). So the purchase-price leg is 2140 → 2140, which posts nothing, and the lump sum books when it is paid as any payout does (DR 5100 / CR cash / CR 2230). Booking the lump sum to 2130 at vesting would leave 2130 uncleared, because `PayoutPaid` debits 5100, not 2130. A real account-liability treatment belongs to the IFRS 17 work (C1), like every 4xxx gap in `PostingRule`.
- **R5 — the vesting and reminder sweeps are Java `@Scheduled` drains**, like D1's `AnnuityRollForward`, not pg_cron jobs. They run whenever the application runs, so there is nothing for `configure-db.sh` to install and nothing for `ops.platform_readiness()` to check. Spec §9's "join the scheduled-jobs health check" therefore does not apply.
- **R6 — a death reported before vesting holds the vesting.** The spec covers an approved death (Q6) but not the gap between a death being reported and its claim being decided. In that gap a sweep would buy an annuity for someone who has died. Individual policies have no open-death-claim marker (V23's is for scheme members only), so annuity records the registered DEATH claim itself and holds until it is rejected. Approval ends the contract as Q6 says.
- **R7 — vesting waives every unpaid contribution invoice**, not only those after the vesting date. Billing raises invoices twelve months ahead and has no cancelled status. Any of them left DUE could later be paid against an account that has closed. WAIVED already exists, is terminal, and counts as settled.
- **R8 — a deferral with contributions continuing must be recorded before the original premium-paying term ends.** A held contract past its target date can still be deferred, but only with contributions stopped. Restarting a terminated billing schedule is not something billing does, and nothing in the spec asks for it.
