# Family funeral cover (FUNERAL) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task (the user prefers inline execution with no subagents). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sell one policy that covers a main member and their family — each life with its own benefit from the chosen plan and its own premium from its role and age band — and pay a death claim on any covered life while the policy carries on.

**Architecture:** Product gains a `FUNERAL` category and a `FuneralPlan` riding on the publish path (an eleventh overload, the annuity pattern), plus one pure quoter that every caller prices through. Underwriting records a funeral application (plan + dependants) beside the case, as D2 records a deferred annuity choice. Policy issues an ordinary individual policy whose premium is the quote's instalment and writes one `covered_life` row per person; it owns endorsements, the nightly age-out/anniversary sweep and the main-member-death rules. Billing gains a premium restatement driven by a new `policy.PremiumRestated` event. Claims names a covered life, enforces the waiting period through the existing exclusion-window gate, and discharges one life rather than the policy.

**Tech Stack:** Spring Boot 3 / Spring Modulith, JPA with `ddl-auto: none`, hand-written SQL migrations per module, Postgres 16 Testcontainers, React + Zustand + zod console, Playwright e2e.

**Spec:** `backend/docs/superpowers/specs/2026-10-04-family-funeral-cover-design.md` (64eae93e). Where this plan and the spec differ, the plan wins; every difference is in §R and is to be confirmed by the user before Task 1 starts.

**Branch:** `product-family-funeral` (already holds the spec). Work in a worktree: `git worktree add .worktrees/product-family-funeral product-family-funeral`, then junction the worktree's `frontend/node_modules` to the main checkout's (lockfiles identical), as D2 did.

## Global Constraints

- The user's decisions bind, verbatim (spec §1 table): **the customer picks a plan; each life's premium also depends on its own age band**; **one spouse**; **every rule configured per product version by staff, nothing hard-coded**; **covered lives on an ordinary individual policy**; **a fixed yearly premium per life**; **every life re-priced to its new age band at each policy anniversary, benefits unchanged**; **staff may add and remove lives at any time, effective from the next premium date**.
- Migrations, next free numbers (checked 2026-10-04): **product V24, underwriting V16, policy V35, claims V10, communication V12.** No billing, finaccounting or regreporting migration. Before writing a column on an existing table, grep that table's CHECKs.
- **Gate new-table reads on product first** (D1 R2): `resolveFuneralPlan` reads the funeral tables only after the category says FUNERAL; underwriting reads `funeral_application` and policy reads `covered_life` only after `resolveFuneralPlan(v).funeral()`. No existing test class needs a funeral migration.
- One shared migration list: `funeral/FuneralTestMigrations.ALL` = `AnnuityTestMigrations.ALL` (it already carries claims V1-V9 and the payment rail) + product V24, underwriting V16, policy V35, claims V10, communication V12.
- "Today" is the civil date: `LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"))` in Java, `todayIso()` in e2e. Never `LocalDate.now()` bare.
- `@Scheduled` drains carry `initialDelayString` equal to their interval, with a short `local` override in `application-local.yml`.
- Money `NUMERIC(19,2)`; every rounding `HALF_EVEN` to cents, **once**, on the instalment total — never per life (a family of five rounding five times disagrees with the quote by up to 2.5 cents).
- `-Dtest` takes commas. Run each task's own classes; `./mvnw -o clean test-compile` after any signature change (records are widened here). Stop the dev backend and its orphan JVMs (never the `redhat.java` ones) before `clean`. Never run Maven concurrently with another Maven or with Playwright.
- Never run Prettier. Edit files with Write/Edit, not shell strings. Zustand selectors never return a fresh object or array.
- Every response field in OpenAPI and asserted by name in a contract test; every console validator message lands on a rendered path. No `default:` on an OpenAPI property.
- Commit at the end of each task. End commit messages with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## §R — Where this plan refines the spec (confirm before starting)

- **R1 — No rating table on a FUNERAL version.** The publish rule "an unpriced version's rating table must cover AGE and SUM_ASSURED_BAND" is lifted for FUNERAL, and a FUNERAL version may carry **no base rates and no rating factors**: the premium table is its whole price, and a multiplier on top would double-count age. Underwriting's assessment then resolves every multiplier to the neutral 1.0, as it already does for an unmatched band.
- **R2 — A funeral case is accepted, declined or postponed, never LOADED.** Its premium is the table's; a loading would be a price the product never filed. An assessment is still required (the ordinary path, not the annuity light path).
- **R3 — The case's sum assured is the main member's plan benefit.** Recording the application refuses a case whose sum assured differs, naming the right figure; the console fills it from the quote so staff never type it.
- **R4 — "The next premium date"** is the first instalment date strictly after the effective day, counted from the policy's **issue date** by the policy's frequency — the dates billing already raises (`nextPeriodStart` from issue). A restatement re-raises only invoices still `DUE`; an instalment already paid in advance keeps its old amount and the new amount starts at the first untouched one.
- **R5 — Free cover runs to the next premium date after the death** (the end of the period the last instalment bought), using R4's rule.
- **R6 — Regulatory reporting is unchanged:** a funeral policy is reported as one policy at the main member's sum assured. Group schemes are not reported per life either; a lives-covered metric is its own change.
- **R7 — The waiting period is enforced at the decision, through the existing exclusion gate.** A new decline reason `WITHIN_WAITING_PERIOD`: declining for it is allowed only while the life's window was open on the date of death, and **approving** a natural death inside an open window is refused (409). The claim records `accidental` (ticked at registration or assessment); with `accidentWaivesWaiting` an accidental death has no window.
- **R8 — Spouse takeover is a staff step.** On settlement of the main member's claim under `SPOUSE_TAKES_OVER`, the main member's life ends and the policy stays in force **awaiting takeover**; staff complete it with the spouse's identity document (promoting the spouse to a party). The spouse's row becomes `MAIN_MEMBER` and is re-priced from the next premium date. With no active spouse the rule falls back to `POLICY_ENDS` (with free cover if switched on).
- **R9 — Payee checks at registration**, credit life's pattern: a dependant's claim must be filed by the policyholder (`MAIN_MEMBER`) or by a party beneficiary of the policy (`MAIN_MEMBER_BENEFICIARY`). A main member's claim is unchanged from today.
- **R10 — Promotion at claim is a staff step, not a gate**, exactly as credit life: `POST /policies/{n}/covered-lives/{id}/promotion` with an identity document.

---

## File structure

```
backend/db-migrations/
  product/V24__funeral_terms.sql              FUNERAL category; funeral_terms, funeral_plan, funeral_plan_benefit,
                                              funeral_premium, funeral_role_rule
  underwriting/V16__funeral_application.sql   funeral_application, funeral_application_life
  policy/V35__covered_life.sql                covered_life; policy.awaiting_takeover_life_id
  claims/V10__funeral_claims.sql              covered_life_id, accidental, WITHIN_WAITING_PERIOD
  communication/V12__funeral_templates.sql    FUNERAL_LIFE_ADDED, FUNERAL_LIFE_ENDED, FUNERAL_PREMIUM_CHANGED

backend/src/main/java/tz/co/nlolo/lifeplatform/
  product/api/       FuneralRole, FuneralRoleRule, FuneralPlanOption, FuneralPlanBenefit, FuneralPremiumRow,
                     DependantClaimPayee, MainMemberDeathRule, FuneralPlan, FuneralLifeInput, FuneralQuoteInput,
                     FuneralQuoteLine, FuneralQuote, FuneralQuoteRefusedException; ProductCategory.FUNERAL
  product/domain/    FuneralPlanValidator (pure), FuneralQuoter (pure), FuneralTermsEntity + 4 row entities
  product/infrastructure/  FuneralRequest, FuneralTermsResponse, FuneralQuoteRequest, repositories
  underwriting/      FuneralApplication (api), entities + repos, recordFuneralApplication, decide branch
  policy/domain/     CoveredLife, InstalmentDates (pure)
  policy/application/ CoveredLives (endorsements, takeover, discharge), CoveredLifeSweep
  billing/           BillingApiImpl.restatePremium, endBillingAfter; PolicyEventListener branches
  claims/            Claim.coveredLifeId/accidental, ClaimDeclineReason.WITHIN_WAITING_PERIOD,
                     ExclusionPeriods.waitingMonths, ExclusionWindows, approval guard, payee check
  communication/application/FuneralEventListener

frontend/src/
  api/funeral.ts, store/funeralStore.ts
  features/products/funeralSchema.ts (+test), FuneralTermsSection.tsx
  features/underwriting/funeralApplicationForm.ts (+test), FuneralLivesFields.tsx
  features/policies/CoveredLivesPanel.tsx (+test), coveredLifeForm.ts (+test), TakeoverPanel.tsx
  features/claims/claimRegisterForm.ts (+coveredLifeId, accidental)
frontend/e2e/staff-family-funeral.spec.ts
backend/scripts/seed-dev-data.sh               FUN-FAM-01 "Nlolo Familia"
```

---

### Task 1: Product — the FUNERAL category and its terms

**Files:**
- Create: `db-migrations/product/V24__funeral_terms.sql`
- Create: `product/api/{FuneralRole,FuneralRoleRule,FuneralPlanOption,FuneralPlanBenefit,FuneralPremiumRow,DependantClaimPayee,MainMemberDeathRule,FuneralPlan}.java`
- Create: `product/domain/FuneralPlanValidator.java`, `product/domain/{FuneralTermsEntity,FuneralPlanEntity,FuneralPlanBenefitEntity,FuneralPremiumEntity,FuneralRoleRuleEntity}.java`, the five repositories in `product/infrastructure/`
- Create: `product/infrastructure/FuneralRequest.java`, `product/infrastructure/FuneralTermsResponse.java`, `product/infrastructure/NotAFuneralProductException.java`
- Modify: `product/api/ProductCategory.java` (+`FUNERAL`), `product/api/ProductApi.java` (11th `publishVersion` overload + `resolveFuneralPlan`), `product/application/ProductApiImpl.java` (the annuity overload delegates with `FuneralPlan.none()`; rating-table rule lifted per R1; `persistFuneralPlan`; `resolveFuneralPlan`), `product/infrastructure/PublishVersionRequest.java` (+`@Valid FuneralRequest funeral`), `ProductController.java` (publish passes it; `GET /products/{id}/versions/{v}/funeral`), `ProductExceptionHandler.java` (404 `NOT_A_FUNERAL_PRODUCT`), `api/openapi/openapi-product.yaml`
- Test: `product/FuneralPlanValidatorTest.java` (pure), `product/FuneralProductIntegrationTest.java`, extend `ProductContractTest`

**Interfaces — Produces:**
```java
public enum FuneralRole { MAIN_MEMBER, SPOUSE, CHILD, PARENT, EXTENDED }

/** coverStopAge null = never stops for age; studentStopAge only on CHILD, null = no student extension. */
public record FuneralRoleRule(FuneralRole role, int maxLives, int minEntryAge, int maxEntryAge,
                              Integer coverStopAge, Integer studentStopAge) {}
public record FuneralPlanOption(String planCode, String name) {}
public record FuneralPlanBenefit(String planCode, FuneralRole role, BigDecimal benefit) {}
public record FuneralPremiumRow(String planCode, FuneralRole role, int ageFrom, int ageTo, BigDecimal yearlyPremium) {}
public enum DependantClaimPayee { MAIN_MEMBER, MAIN_MEMBER_BENEFICIARY }
public enum MainMemberDeathRule { POLICY_ENDS, SPOUSE_TAKES_OVER }

public record FuneralPlan(boolean funeral, List<FuneralPlanOption> plans, List<FuneralPlanBenefit> benefits,
                          List<FuneralPremiumRow> premiums, List<FuneralRoleRule> roles, int maxPricedAge,
                          Integer waitingPeriodMonths, boolean accidentWaivesWaiting,
                          DependantClaimPayee dependantClaimPayee, MainMemberDeathRule onMainMemberDeath,
                          boolean freeCoverToPaidDate) {
    public FuneralPlan { /* List.copyOf each, nulls to List.of() */ }
    public static FuneralPlan none() { return new FuneralPlan(false, List.of(), List.of(), List.of(), List.of(), 0,
        null, false, null, null, false); }
    public Optional<BigDecimal> benefit(String planCode, FuneralRole role) { /* exact match */ }
    public Optional<FuneralRoleRule> rule(FuneralRole role) { /* exact match */ }
    public Optional<BigDecimal> yearlyPremium(String planCode, FuneralRole role, int age) {
        /* the one row with ageFrom <= age <= ageTo */ }
    /** The age cover stops for this life, or null when it never does. */
    public Integer stopAge(FuneralRole role, boolean student) { /* student && studentStopAge != null ? studentStopAge : coverStopAge */ }
}

// ProductApi
void publishVersion(..., AnnuityPlan annuityPlan, FuneralPlan funeralPlan, String publishedBy); // 11th, the fullest
FuneralPlan resolveFuneralPlan(UUID productVersionId);   // none() for every other version
```

**Migration (`V24__funeral_terms.sql`):**
```sql
-- Family funeral cover (spec 2026-10-04): a FUNERAL version's plans, benefits per role, premium table,
-- role rules and claim rules. Absent on every other version -- its absence IS that.
ALTER TABLE product.product_definition DROP CONSTRAINT IF EXISTS product_definition_category_check;
ALTER TABLE product.product_definition ADD CONSTRAINT product_definition_category_check
    CHECK (category IN ('TERM_LIFE','ENDOWMENT','WHOLE_LIFE','ANNUITY','UNIT_LINKED',
                        'GROUP_LIFE','EDUCATION_SAVINGS','CREDIT_LIFE','FUNERAL'));

CREATE TABLE product.funeral_terms (
    product_version_id      UUID PRIMARY KEY REFERENCES product.product_version(product_version_id),
    tenant_id               UUID NOT NULL,
    max_priced_age          INTEGER NOT NULL CHECK (max_priced_age BETWEEN 1 AND 120),
    waiting_period_months   INTEGER CHECK (waiting_period_months IS NULL OR waiting_period_months > 0),
    accident_waives_waiting BOOLEAN NOT NULL,
    dependant_claim_payee   VARCHAR(30) NOT NULL CHECK (dependant_claim_payee IN ('MAIN_MEMBER','MAIN_MEMBER_BENEFICIARY')),
    on_main_member_death    VARCHAR(30) NOT NULL CHECK (on_main_member_death IN ('POLICY_ENDS','SPOUSE_TAKES_OVER')),
    free_cover_to_paid_date BOOLEAN NOT NULL
);
CREATE TABLE product.funeral_plan (
    product_version_id UUID NOT NULL REFERENCES product.funeral_terms(product_version_id),
    tenant_id          UUID NOT NULL,
    plan_code          VARCHAR(20) NOT NULL,
    name               VARCHAR(100) NOT NULL,
    PRIMARY KEY (product_version_id, plan_code)
);
CREATE TABLE product.funeral_plan_benefit (
    product_version_id UUID NOT NULL,
    tenant_id          UUID NOT NULL,
    plan_code          VARCHAR(20) NOT NULL,
    role               VARCHAR(20) NOT NULL CHECK (role IN ('MAIN_MEMBER','SPOUSE','CHILD','PARENT','EXTENDED')),
    benefit            NUMERIC(19,2) NOT NULL CHECK (benefit > 0),
    PRIMARY KEY (product_version_id, plan_code, role),
    FOREIGN KEY (product_version_id, plan_code) REFERENCES product.funeral_plan(product_version_id, plan_code)
);
CREATE TABLE product.funeral_premium (
    funeral_premium_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_version_id UUID NOT NULL,
    tenant_id          UUID NOT NULL,
    plan_code          VARCHAR(20) NOT NULL,
    role               VARCHAR(20) NOT NULL CHECK (role IN ('MAIN_MEMBER','SPOUSE','CHILD','PARENT','EXTENDED')),
    age_from           INTEGER NOT NULL CHECK (age_from >= 0),
    age_to             INTEGER NOT NULL,
    yearly_premium     NUMERIC(19,2) NOT NULL CHECK (yearly_premium > 0),
    CHECK (age_to >= age_from),
    FOREIGN KEY (product_version_id, plan_code) REFERENCES product.funeral_plan(product_version_id, plan_code)
);
CREATE INDEX idx_funeral_premium_lookup ON product.funeral_premium (product_version_id, plan_code, role, age_from);
CREATE TABLE product.funeral_role_rule (
    product_version_id UUID NOT NULL REFERENCES product.funeral_terms(product_version_id),
    tenant_id          UUID NOT NULL,
    role               VARCHAR(20) NOT NULL CHECK (role IN ('MAIN_MEMBER','SPOUSE','CHILD','PARENT','EXTENDED')),
    max_lives          INTEGER NOT NULL CHECK (max_lives > 0),
    min_entry_age      INTEGER NOT NULL CHECK (min_entry_age >= 0),
    max_entry_age      INTEGER NOT NULL,
    cover_stop_age     INTEGER,
    student_stop_age   INTEGER,
    PRIMARY KEY (product_version_id, role),
    CHECK (max_entry_age >= min_entry_age),
    CHECK (cover_stop_age IS NULL OR cover_stop_age > max_entry_age),
    CHECK (student_stop_age IS NULL OR (role = 'CHILD' AND cover_stop_age IS NOT NULL AND student_stop_age > cover_stop_age)),
    CHECK (role <> 'MAIN_MEMBER' OR max_lives = 1),
    CHECK (role <> 'SPOUSE' OR max_lives = 1)
);
-- RLS (NULLIF form, as every policy since 2026-09-10) and grants for all five tables:
ALTER TABLE product.funeral_terms ENABLE ROW LEVEL SECURITY;
CREATE POLICY funeral_terms_tenant_isolation ON product.funeral_terms
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
-- ... repeat ENABLE + POLICY for funeral_plan, funeral_plan_benefit, funeral_premium, funeral_role_rule ...
GRANT SELECT, INSERT, UPDATE, DELETE ON product.funeral_terms, product.funeral_plan, product.funeral_plan_benefit,
    product.funeral_premium, product.funeral_role_rule TO app_role;
```
(Copy the exact NULLIF predicate from the newest product migration, `V23__vesting_terms.sql`, rather than from this sketch.)

**Validator rules (`FuneralPlanValidator.validate(ProductCategory category, FuneralPlan plan, List<BaseRateInput> baseRates, List<RatingFactorInput> ratingTable)`)**, each an `InvalidProductVersionException` in these words:
1. `!category.FUNERAL && plan.funeral()` → "Funeral terms are only valid on a FUNERAL product".
2. `category == FUNERAL && !plan.funeral()` → "A FUNERAL version must carry its plans, premium table and role rules".
3. FUNERAL with base rates or rating factors → "A FUNERAL version is priced by its premium table alone; remove the base rates and rating factors" (R1).
4. No plans; a duplicate plan code; a MAIN_MEMBER role rule missing; a duplicate role rule; any role rule with SPOUSE or MAIN_MEMBER `maxLives != 1` → named messages ("A FUNERAL version needs at least one plan", "Plan code A appears twice", "The main member's role rule is required", "Role SPOUSE is configured twice", "One spouse per policy: SPOUSE maxLives must be 1").
5. Every plan carries a MAIN_MEMBER benefit → "Plan B does not cover the main member".
6. A benefit for a role with no role rule → "Plan A covers PARENT, but PARENT has no role rule".
7. For every (plan, role with a benefit): premium rows don't overlap → "Plan A, CHILD: ages 0-10 and 5-20 overlap"; and every age from `minEntryAge` to `(coverStopAge != null ? max(coverStopAge, studentStopAge ?: 0) - 1 : maxPricedAge)` has a row → "Plan A, CHILD: no premium for age 21" (first gap only).
8. Premium rows for a (plan, role) with no benefit → "Plan A prices EXTENDED but does not cover it".
9. `waitingPeriodMonths` ≤ 0 → refused; `dependantClaimPayee`/`onMainMemberDeath` null → "Choose who is paid when a dependant dies" / "Choose what happens when the main member dies".

`ProductApiImpl`: after `VestingPlanValidator.validate(...)` call `FuneralPlanValidator.validate(category, funeral, baseRates, ratingTable)`, and skip the "Rating table must cover at least AGE and SUM_ASSURED_BAND" branch when `category == FUNERAL` (R1). `persistFuneralPlan` writes terms, plans, benefits, premiums, rules after `persistAnnuityPlan`. `resolveFuneralPlan` mirrors `resolveAnnuityPlan`: category first, then the tables.

- [ ] **Step 1: Write `FuneralPlanValidatorTest`** — with a shared test helper `product/FuneralPlans.java` (every later task reuses it) whose `familia()` returns a valid two-plan FuneralPlan (plans A and B; roles MAIN 18-65 never stops, SPOUSE 18-65, CHILD 0-20 stop 21 student 25, PARENT up to 75 never, EXTENDED up to 65 never; maxPricedAge 100; premiums in bands covering exactly those ages; waiting 6, accident waives, MAIN_MEMBER payee, POLICY_ENDS, free cover on). One test per rule 1-9 asserting the exact message, plus `aValidFamilyPlanPasses`.
- [ ] **Step 2: Run** `./mvnw -o test -Dtest=FuneralPlanValidatorTest` — fails to compile.
- [ ] **Step 3: Implement** the api records, the validator, the migration, entities, repositories, `ProductApiImpl` changes, `FuneralRequest.toPlan()` (nothing `@NotNull`; missing ints become -1 so the validator refuses in its own words — AnnuityRequest's reason), `FuneralTermsResponse.from(plan)`, the GET endpoint, OpenAPI.
- [ ] **Step 4: Write `FuneralProductIntegrationTest`** (Testcontainers, migrations = `FuneralTestMigrations.ALL` — create `funeral/FuneralTestMigrations.java` now with product V24 only; later tasks append): `publishesAFamilyPlanAndReadsItBack`; `anAgeGapIsRefusedAndNothingIsWritten` (count rows in `product.funeral_terms` for the tenant = 0); `aNonFuneralVersionResolvesToNone`; `anOrdinaryProductNeverReadsTheFuneralTables` (publish WHOLE_LIFE in a class WITHOUT V24 applied — put this one in an existing product test class that lacks V24, e.g. extend `ProductApiIntegrationTest` with `resolveFuneralPlanOnAWholeLifeVersionIsNone`).
- [ ] **Step 5: Extend `ProductContractTest`**: `GET .../funeral` returns `plans`, `benefits`, `premiums`, `roles`, `maxPricedAge`, `waitingPeriodMonths`, `accidentWaivesWaiting`, `dependantClaimPayee`, `onMainMemberDeath`, `freeCoverToPaidDate` by name; 404 `NOT_A_FUNERAL_PRODUCT` on an ordinary version.
- [ ] **Step 6: Run** `./mvnw -o clean test-compile; ./mvnw -o test -Dtest=FuneralPlanValidatorTest,FuneralProductIntegrationTest,ProductApiIntegrationTest,ProductContractTest` — all green.
- [ ] **Step 7: Commit** `feat(product): a FUNERAL category with plans, per-role benefits, an age-banded premium table and claim rules`.

---

### Task 2: Product — the one funeral quote

**Files:**
- Create: `product/api/{FuneralLifeInput,FuneralQuoteInput,FuneralQuoteLine,FuneralQuote,FuneralQuoteRefusedException}.java`, `product/domain/FuneralQuoter.java`, `product/infrastructure/FuneralQuoteRequest.java`
- Modify: `ProductApi` (+`quoteFuneral`), `ProductApiImpl`, `ProductController` (`POST /products/{id}/versions/{v}/funeral-quote`), `ProductExceptionHandler` (422 `FUNERAL_QUOTE_REFUSED`), OpenAPI
- Test: `product/FuneralQuoterTest.java` (pure), extend `FuneralProductIntegrationTest`, `ProductContractTest`

**Interfaces — Produces:**
```java
public record FuneralLifeInput(FuneralRole role, String name, LocalDate dateOfBirth, boolean student) {}
public record FuneralQuoteInput(String planCode, PremiumFrequency frequency, LocalDate asOf, List<FuneralLifeInput> lives) {}
public record FuneralQuoteLine(FuneralRole role, String name, int age, BigDecimal benefit, BigDecimal yearlyPremium) {}
public record FuneralQuote(String planCode, PremiumFrequency frequency, List<FuneralQuoteLine> lines,
                           BigDecimal totalYearlyPremium, BigDecimal instalment, BigDecimal mainMemberBenefit) {}
public class FuneralQuoteRefusedException extends RuntimeException { public FuneralQuoteRefusedException(String m) { super(m); } }

// ProductApi -- @Transactional(readOnly = true, noRollbackFor = FuneralQuoteRefusedException.class)
FuneralQuote quoteFuneral(UUID productVersionId, FuneralQuoteInput input);
```

**`FuneralQuoter.quote(FuneralPlan plan, FrequencyLoading loading, FuneralQuoteInput in)`** — static, pure:
```java
public static FuneralQuote quote(FuneralPlan plan, FrequencyLoading loading, FuneralQuoteInput in) {
    if (!plan.funeral()) throw new FuneralQuoteRefusedException("This product is not a funeral plan");
    if (plan.plans().stream().noneMatch(p -> p.planCode().equals(in.planCode())))
        throw new FuneralQuoteRefusedException("There is no plan " + in.planCode() + " on this product");
    if (in.frequency() == null || in.frequency() == PremiumFrequency.SINGLE)
        throw new FuneralQuoteRefusedException("A funeral plan is paid monthly, quarterly or annually");
    long mains = in.lives().stream().filter(l -> l.role() == FuneralRole.MAIN_MEMBER).count();
    if (mains != 1) throw new FuneralQuoteRefusedException("A funeral plan covers exactly one main member");
    Map<FuneralRole, Long> counts = in.lives().stream().collect(groupingBy(FuneralLifeInput::role, counting()));
    List<FuneralQuoteLine> lines = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (FuneralLifeInput life : in.lives()) {
        FuneralRoleRule rule = plan.rule(life.role()).orElseThrow(() ->
            new FuneralQuoteRefusedException("This product does not cover " + label(life.role())));
        if (counts.get(life.role()) > rule.maxLives())
            throw new FuneralQuoteRefusedException("At most " + rule.maxLives() + " " + plural(life.role()) + " may be covered");
        BigDecimal benefit = plan.benefit(in.planCode(), life.role()).orElseThrow(() ->
            new FuneralQuoteRefusedException("Plan " + in.planCode() + " does not cover " + plural(life.role())));
        if (life.dateOfBirth() == null) throw new FuneralQuoteRefusedException(life.name() + ": the date of birth is required");
        int age = Period.between(life.dateOfBirth(), in.asOf()).getYears();
        if (age < rule.minEntryAge() || age > rule.maxEntryAge())
            throw new FuneralQuoteRefusedException(life.name() + ": a " + label(life.role()) + " must be "
                + rule.minEntryAge() + " to " + rule.maxEntryAge() + " at entry, not " + age);
        if (life.student() && life.role() != FuneralRole.CHILD)
            throw new FuneralQuoteRefusedException(life.name() + ": only a child can be marked as a student");
        BigDecimal yearly = plan.yearlyPremium(in.planCode(), life.role(), age).orElseThrow(() ->
            new FuneralQuoteRefusedException("Plan " + in.planCode() + " has no premium for a " + label(life.role()) + " aged " + age));
        lines.add(new FuneralQuoteLine(life.role(), life.name(), age, benefit, yearly));
        total = total.add(yearly);
    }
    BigDecimal loaded = loading.applyTo(total, in.frequency());
    BigDecimal instalment = loaded.divide(BigDecimal.valueOf(in.frequency().instalmentsPerYear()), 2, RoundingMode.HALF_EVEN);
    return new FuneralQuote(in.planCode(), in.frequency(), List.copyOf(lines), total, instalment,
        plan.benefit(in.planCode(), FuneralRole.MAIN_MEMBER).orElseThrow());
}
```
`label`: MAIN_MEMBER "main member", SPOUSE "spouse", CHILD "child", PARENT "parent", EXTENDED "extended family member"; `plural` adds the right plural ("children", "spouses", "parents", "extended family members", "main members").

Also a second entry point the policy sweep and endorsements use, pricing ONE life at a given age without entry-age checks (an existing life ageing past its entry window is still priced):
```java
public static BigDecimal yearlyPremiumAt(FuneralPlan plan, String planCode, FuneralRole role, int age) // refuses with the same "has no premium" message
// ProductApi:
BigDecimal funeralYearlyPremium(UUID productVersionId, String planCode, FuneralRole role, int age);
```

- [ ] **Step 1: Write `FuneralQuoterTest`** with `FuneralPlans.familia()` from Task 1: `aFamilyOfFiveSumsEachLifesPremium` (main 40 + spouse 38 + children 10, 7, 3 on plan B → exact total and MONTHLY instalment with a 5% monthly loading: `total × 1.05 / 12` HALF_EVEN), `roundsOnceOnTheTotal` (three lives whose per-life monthly figures would each round up: assert instalment = round(sum), not sum(round)), and one test per refusal message above.
- [ ] **Step 2: Run** it — fails to compile.
- [ ] **Step 3: Implement** the quoter, the api types, `quoteFuneral` and `funeralYearlyPremium` in `ProductApiImpl` (resolve plan + `resolveFrequencyLoading`), the endpoint (request: `planCode`, `frequency`, `asOf` optional → civil today, `lives[]`), OpenAPI.
- [ ] **Step 4: Extend tests:** `FuneralProductIntegrationTest.quotesThroughTheApiAsThePureQuoterDoes`; `ProductContractTest`: 200 body names `planCode`, `frequency`, `lines[].role/name/age/benefit/yearlyPremium`, `totalYearlyPremium`, `instalment`, `mainMemberBenefit`; 422 `FUNERAL_QUOTE_REFUSED` with the message.
- [ ] **Step 5: Run** `./mvnw -o test -Dtest=FuneralQuoterTest,FuneralPlanValidatorTest,FuneralProductIntegrationTest,ProductContractTest`.
- [ ] **Step 6: Commit** `feat(product): one funeral quote -- a line per life, the family total, and the instalment rounded once`.

---

### Task 3: Underwriting — the funeral application on the case

**Files:**
- Create: `db-migrations/underwriting/V16__funeral_application.sql`
- Create: `underwriting/api/FuneralApplication.java`, `underwriting/domain/{FuneralApplicationEntity,FuneralApplicationLifeEntity}.java`, `underwriting/infrastructure/{FuneralApplicationRepository,FuneralApplicationLifeRepository,FuneralApplicationNotFoundException,FuneralApplicationRequest}.java`
- Modify: `UnderwritingApi` (+`recordFuneralApplication`, `funeralApplication`), `UnderwritingApiImpl` (record; `decide` branch per R2 and a re-quote at acceptance), `OpenCaseRequest.java` (+`@Valid FuneralApplicationRequest funeral`), `UnderwritingController` (record after open, like `deferredAnnuity`; `GET/PUT /underwriting/cases/{id}/funeral-application`), exception handler (404 `FUNERAL_APPLICATION_NOT_FOUND`), `api/openapi/openapi-underwriting.yaml`
- Test: `funeral/FuneralCaseIntegrationTest.java`, extend `UnderwritingContractTest`

**Interfaces — Produces:**
```java
/** A funeral applicant's plan and the dependants to cover (the main member is the life assured on the case). */
public record FuneralApplication(String planCode, List<Life> dependants, FuneralQuote quote) {
    public record Life(FuneralRole role, String fullName, LocalDate dateOfBirth, String sex,
                       String idNumber, boolean student) {}
}
// UnderwritingApi
FuneralApplication recordFuneralApplication(UUID caseId, String planCode, List<FuneralApplication.Life> dependants, String recordedBy);
Optional<FuneralApplication> funeralApplication(UUID caseId);   // empty for any non-funeral case; never throws
```
`quote` is re-derived on every read through `productApi.quoteFuneral` (never stored) with the main member taken from the case's life assured party (name, DOB) and `asOf` = the case's proposed commencement date, else civil today; frequency = the case's premium frequency, else MONTHLY.

**Migration:**
```sql
CREATE TABLE underwriting.funeral_application (
    case_id     UUID PRIMARY KEY REFERENCES underwriting.underwriting_case(case_id),
    tenant_id   UUID NOT NULL,
    plan_code   VARCHAR(20) NOT NULL,
    recorded_by VARCHAR(100) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE underwriting.funeral_application_life (
    funeral_application_life_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id       UUID NOT NULL REFERENCES underwriting.funeral_application(case_id) ON DELETE CASCADE,
    tenant_id     UUID NOT NULL,
    role          VARCHAR(20) NOT NULL CHECK (role IN ('SPOUSE','CHILD','PARENT','EXTENDED')),
    full_name     VARCHAR(200) NOT NULL,
    date_of_birth DATE NOT NULL,
    sex           VARCHAR(10) CHECK (sex IS NULL OR sex IN ('FEMALE','MALE')),
    id_number     VARCHAR(50),
    student       BOOLEAN NOT NULL DEFAULT false,
    position      INTEGER NOT NULL,
    UNIQUE (case_id, position)
);
-- RLS (NULLIF) + grants for both, copied from underwriting/V15.
```
The main member is never a row here: it is the case's life assured, a registered party.

**Rules in `recordFuneralApplication`** (each `UnderwritingValidationException`):
- `!productApi.resolveFuneralPlan(v).funeral()` → "Only a funeral plan case records a funeral application".
- decided → `UnderwritingCaseAlreadyDecidedException`.
- the life assured has no date of birth → "The main member's date of birth is not recorded".
- re-quote all lives (main member + dependants); `FuneralQuoteRefusedException` → its message.
- the case's sum assured ≠ `quote.mainMemberBenefit()` → "The sum assured on a funeral case is the main member's benefit on plan B: 2,000,000.00 TZS, not 1,500,000.00" (R3).
- Replaces any previous application (delete lives, rewrite) — the PUT is idempotent.

**`decide`**: after the annuity branch, `FuneralPlan funeral = productApi.resolveFuneralPlan(v)`; if `funeral.funeral()`: LOADED → "A funeral plan is priced by its premium table; accept, decline or postpone it" (R2); ACCEPT with no application → "A funeral case must record its plan and lives before it is accepted"; ACCEPT re-quotes (a life may have aged out of a band or of entry since opening) and refuses with the quote's message. The ordinary "has no assessment" rule still applies.

- [ ] **Step 1: Write `FuneralCaseIntegrationTest`** (fixtures: create `funeral/FuneralTestFixtures.java` with `publishFamilia(tenant)`, `person(tenant, age, sex)`, `openFuneralCase(tenant, product, mainMember, sumAssured, frequency)`, `assessAndAccept(tenant, caseId)`, `asTenant` re-exported from `AnnuityTestFixtures`): `recordsThePlanAndDependantsAndQuotesTheFamily`; `aSumAssuredThatIsNotTheMainMembersBenefitIsRefusedNamingTheRightFigure`; `aChildTooOldForEntryIsRefused`; `aSecondSpouseIsRefused`; `aLoadedDecisionIsRefused`; `acceptanceWithoutAnApplicationIsRefused`; `anOrdinaryCaseHasNoFuneralApplication` (returns empty).
- [ ] **Step 2: Run** — fails.
- [ ] **Step 3: Implement** migration, entities, repos, api, impl, controller, OpenAPI; append underwriting V16 to `FuneralTestMigrations`.
- [ ] **Step 4: Extend `UnderwritingContractTest`**: PUT returns `planCode`, `dependants[]` (`role`, `fullName`, `dateOfBirth`, `sex`, `idNumber`, `student`), `quote` (Task 2's fields); GET 404 `FUNERAL_APPLICATION_NOT_FOUND`; a `funeral` block on `POST /underwriting/cases` is recorded.
- [ ] **Step 5: Run** `./mvnw -o clean test-compile; ./mvnw -o test -Dtest=FuneralCaseIntegrationTest,UnderwritingContractTest,DeferredAnnuityCaseIntegrationTest`.
- [ ] **Step 6: Commit** `feat(underwriting): a funeral case records its plan and dependants, priced by the one funeral quote`.

---

### Task 4: Policy — issue with covered lives

**Files:**
- Create: `db-migrations/policy/V35__covered_life.sql`
- Create: `policy/domain/CoveredLife.java`, `policy/domain/InstalmentDates.java` (pure), `policy/infrastructure/CoveredLifeRepository.java`, `policy/api/{CoveredLifeView,CoveredLifeStatus}.java`
- Modify: `UnderwritingDecisionEventListener` (FUNERAL branch before the deferred-annuity branch), `PolicyApi` (+`coveredLives`), `PolicyApiImpl` (write lives after `issuePolicy` in the same transaction — a new method `recordCoveredLives(policyNumber, caseId)` called from the listener inside its REQUIRES_NEW block), `PolicyController` (`GET /policies/{n}/covered-lives`), `api/openapi/openapi-policy.yaml`
- Test: `policy/InstalmentDatesTest.java` (pure), `funeral/FuneralIssueIntegrationTest.java`, extend `PolicyContractTest`

**Interfaces — Produces:**
```java
public enum CoveredLifeStatus { ACTIVE, ENDED }
public record CoveredLifeView(UUID coveredLifeId, FuneralRole role, String fullName, LocalDate dateOfBirth, String sex,
                              boolean student, UUID partyId, BigDecimal benefit, BigDecimal yearlyPremium, int pricedAtAge,
                              LocalDate coverStart, LocalDate waitingPeriodEnds, LocalDate coverEnd,
                              CoveredLifeStatus status, String endReason, LocalDate endedOn) {}
// PolicyApi
List<CoveredLifeView> coveredLives(String policyNumber);   // empty for any non-funeral policy

public final class InstalmentDates {
    /** The first instalment date strictly after {@code day}: anchor + k periods, k >= 1 (R4). */
    public static LocalDate nextAfter(LocalDate anchor, String frequency, LocalDate day) { ... }
    private static Period step(String frequency) { MONTHLY 1m, QUARTERLY 3m, ANNUALLY 1y; else IllegalArgumentException }
}
```
`nextAfter` steps `anchor.plus(step.multipliedBy(k))` — always from the anchor, never by repeated addition (Jan 31 + 1 month + 1 month ≠ Jan 31 + 2 months) — which is how billing's own `nextPeriodStart` loop behaves; the test proves both agree on a month-end anchor by asserting against the invoices billing actually raised.

**Migration:**
```sql
CREATE TABLE policy.covered_life (
    covered_life_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    policy_number     VARCHAR(20) NOT NULL REFERENCES policy.policy(policy_number),
    role              VARCHAR(20) NOT NULL CHECK (role IN ('MAIN_MEMBER','SPOUSE','CHILD','PARENT','EXTENDED')),
    full_name         VARCHAR(200) NOT NULL,
    date_of_birth     DATE NOT NULL,
    sex               VARCHAR(10) CHECK (sex IS NULL OR sex IN ('FEMALE','MALE')),
    id_number         VARCHAR(50),
    student           BOOLEAN NOT NULL DEFAULT false,
    party_id          UUID,                       -- the main member always; a dependant once promoted
    benefit           NUMERIC(19,2) NOT NULL CHECK (benefit > 0),
    yearly_premium    NUMERIC(19,2) NOT NULL CHECK (yearly_premium > 0),
    priced_at_age     INTEGER NOT NULL CHECK (priced_at_age >= 0),
    cover_start       DATE NOT NULL,
    -- A scheduled end (removal, R5's free cover) and why: the life stays ACTIVE and covered until the
    -- sweep reaches cover_end, then ENDS with pending_end_reason.
    cover_end         DATE,
    pending_end_reason VARCHAR(30) CHECK (pending_end_reason IS NULL OR pending_end_reason IN ('REMOVED','FREE_COVER_ENDED')),
    status            VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ENDED')),
    end_reason        VARCHAR(30) CHECK (end_reason IS NULL OR end_reason IN
                          ('DECEASED','REMOVED','AGED_OUT','MAIN_MEMBER_DIED','FREE_COVER_ENDED','POLICY_ENDED')),
    ended_on          DATE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        VARCHAR(100) NOT NULL,
    CHECK ((status = 'ACTIVE' AND end_reason IS NULL AND ended_on IS NULL)
        OR (status = 'ENDED' AND end_reason IS NOT NULL AND ended_on IS NOT NULL)),
    CHECK ((cover_end IS NULL) = (pending_end_reason IS NULL)),
    CHECK (role <> 'MAIN_MEMBER' OR party_id IS NOT NULL)
);
-- One active main member and one active spouse per policy.
CREATE UNIQUE INDEX ux_covered_life_one_main ON policy.covered_life (policy_number) WHERE status = 'ACTIVE' AND role = 'MAIN_MEMBER';
CREATE UNIQUE INDEX ux_covered_life_one_spouse ON policy.covered_life (policy_number) WHERE status = 'ACTIVE' AND role = 'SPOUSE';
CREATE INDEX idx_covered_life_policy ON policy.covered_life (tenant_id, policy_number, status);
-- R8: the spouse life a takeover is waiting on; the plan the family bought (the anniversary re-prices
-- against it). Both null on every other policy.
ALTER TABLE policy.policy
    ADD COLUMN awaiting_takeover_life_id UUID REFERENCES policy.covered_life(covered_life_id),
    ADD COLUMN funeral_plan_code VARCHAR(20);
-- RLS (NULLIF) + grants, copied from policy/V34.
```
`recordCoveredLives` writes `funeral_plan_code` from the application.

**Issue branch (listener), before the deferred annuity one:**
```java
FuneralPlan funeral = productApi.resolveFuneralPlan(decidedCase.productVersionId());
if (funeral.funeral()) {
    FuneralApplication application = underwritingApi.funeralApplication(caseId).orElseThrow(() ->
        new IllegalStateException("Funeral case " + caseId + " was accepted with no application"));
    FuneralQuote quote = application.quote();
    policyApi.issuePolicy(caseId, new PolicyApi.IssueRequest(
        decidedCase.applicantPartyId(), decidedCase.productId(), decidedCase.productVersionId(),
        quote.mainMemberBenefit(), decidedCase.sumAssuredCurrency(),
        quote.instalment(), decidedCase.sumAssuredCurrency(), quote.frequency().name(),
        decidedCase.agentOfRecordId(), nominationsAsBeneficiaries(decidedCase),
        "Automatic issuance on underwriting decision " + outcome,
        decidedCase.proposedCommencementDate(), null, null, decidedCase.lifeAssuredPartyId()),
        "system:underwriting-decision-listener");
    policyApi.recordCoveredLives(caseId, "system:underwriting-decision-listener");
    return;
}
```
`recordCoveredLives(caseId, by)` (PolicyApi, internal): finds the policy issued on `caseId`, writes the MAIN_MEMBER row (party = life assured, name/DOB/sex from the party) and one row per dependant from the quote's lines in order, each `cover_start` = the policy's commencement (else issue date), `priced_at_age` = the line's age. Refuses if the policy already has lives (idempotent re-issue is the listener's problem, not a second family).

`waitingPeriodEnds` in the view = `coverStart.plusMonths(waitingPeriodMonths)` or null.

- [ ] **Step 1: Write `InstalmentDatesTest`**: monthly from 2026-01-31 → 2026-02-28, 2026-03-31; quarterly; annually; `day` equal to an instalment date returns the next one; SINGLE throws.
- [ ] **Step 2: Write `FuneralIssueIntegrationTest`**: `acceptanceIssuesOnePolicyWithAPremiumOfTheWholeFamily` (policy premium = quote instalment; sum assured = main member benefit; frequency MONTHLY); `everyLifeIsRecordedWithItsBenefitPremiumAndCoverStart` (5 rows, MAIN_MEMBER has the party id, dependants none); `billingRaisesTheFamilyInstalment` (first invoice amount = instalment; its due date = `InstalmentDates.nextAfter(issueDate, "MONTHLY", issueDate)`); `anOrdinaryPolicyHasNoCoveredLives`.
- [ ] **Step 3: Run** — fails. **Step 4: Implement**; append policy V35. **Step 5:** `PolicyContractTest`: `GET /policies/{n}/covered-lives` names every `CoveredLifeView` field.
- [ ] **Step 6: Run** `./mvnw -o clean test-compile; ./mvnw -o test -Dtest=InstalmentDatesTest,FuneralIssueIntegrationTest,FuneralCaseIntegrationTest,PolicyContractTest,UnderwritingDecisionEventListenerIntegrationTest` (the last is the existing issuance class; confirm its exact name with `ls src/test/java/tz/co/nlolo/lifeplatform/policy | grep -i decision`).
- [ ] **Step 7: Commit** `feat(policy): a funeral case issues one policy carrying every covered life, billed at the family's instalment`.

---

### Task 5: Policy + billing — add and remove lives; the premium restatement

**Files:**
- Create: `policy/application/CoveredLives.java` (the funeral life operations; keeps `PolicyApiImpl` from growing past 3,300 lines), `policy/infrastructure/{AddCoveredLifeRequest,RemoveCoveredLifeRequest}.java`
- Modify: `PolicyApi` (+`addCoveredLife`, `removeCoveredLife`), `PolicyApiImpl` (delegates), `PolicyController` (`POST /policies/{n}/covered-lives`, `POST /policies/{n}/covered-lives/{id}/removal`), `billing/application/BillingApiImpl.java` (+`restatePremium`), `billing/application/PolicyEventListener.java` (`policy.PremiumRestated`), `billing/domain/PremiumInvoice.java` (only if `waive` is not already public), `api/asyncapi-events.yaml` (`policy.PremiumRestated`, `policy.CoveredLifeAdded`, `policy.CoveredLifeEnded`), OpenAPI
- Test: `funeral/FuneralEndorsementIntegrationTest.java`, `billing/PremiumRestatementIntegrationTest.java`, extend `PolicyContractTest`

**Interfaces — Produces:**
```java
// PolicyApi
CoveredLifeView addCoveredLife(String policyNumber, FuneralApplication.Life life, String addedBy);
CoveredLifeView removeCoveredLife(String policyNumber, UUID coveredLifeId, String reason, String removedBy);

// events (payload keys exactly)
"policy.PremiumRestated"  : policyNumber, premiumAmount {amount, currencyCode}, effectiveFrom (ISO date), reason
"policy.CoveredLifeAdded" : policyNumber, coveredLifeId, policyholderPartyId, fullName, role, coverStart
"policy.CoveredLifeEnded" : policyNumber, coveredLifeId, policyholderPartyId, fullName, role, endReason, endedOn
```

**`CoveredLives.add`**: policy must be IN_FORCE and FUNERAL; `effective = InstalmentDates.nextAfter(issueDate, frequency, today)`; re-quote the WHOLE family with the new life through `productApi.quoteFuneral(versionId, input with asOf = effective)` so role counts and entry ages are checked by the one quoter (refusal → `InvalidPolicyStateException` with the quote's message; the quote's `instalment` is NOT used — see below); write the row with `cover_start = effective`, `yearly_premium` / `priced_at_age` from its quote line; then `restate(policy, effective, "A life was added")`.

**`CoveredLives.remove`**: not MAIN_MEMBER ("The main member cannot be removed; end the policy instead"); ACTIVE with no `cover_end` yet; sets `cover_end = effective`, `pending_end_reason = REMOVED`, and the row stays ACTIVE until the sweep ends it on that date (Task 6) — the life is covered to the period it paid for; `restate(...)` from `effective`.

**`restate(policy, effective, reason)`** — the ONE place a funeral premium is computed after issue:
```java
BigDecimal yearly = lives that are ACTIVE and (coverEnd == null || coverEnd.isAfter(effective))
                    .map(CoveredLife::getYearlyPremium).reduce(ZERO, add);
BigDecimal instalment = productApi.resolveFrequencyLoading(versionId)
    .applyTo(yearly, PremiumFrequency.valueOf(policy.getPremiumFrequency()))
    .divide(BigDecimal.valueOf(PremiumFrequency.valueOf(freq).instalmentsPerYear()), 2, RoundingMode.HALF_EVEN);
policy.restatePremium(instalment);   // new Policy method: premium_amount only
publish "policy.PremiumRestated" { policyNumber, premiumAmount, effectiveFrom = effective, reason }
```
Same arithmetic as `FuneralQuoter` (sum of yearly → loading → divide once). Assert they agree in the test.

**Billing `restatePremium(tenantId, policyNumber, amount, effectiveFrom, reason)`**:
```java
BillingSchedule active = repo.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "ACTIVE").orElse(null);
if (active == null) return;                         // suspended / ended: the resume path re-raises from the policy's premium
List<PremiumInvoice> toReplace = invoices with dueDate >= effectiveFrom and status "DUE";   // R4: untouched only
LocalDate firstDue = toReplace.stream().map(PremiumInvoice::getDueDate).min(naturalOrder()).orElse(active.getNextDueDate());
toReplace.forEach(i -> { i.waive("Premium restated from " + effectiveFrom + ": " + reason); invoiceRepo.save(i); });
active.terminate(); scheduleRepo.saveAndFlush(active);
BillingSchedule next = new BillingSchedule(tenantId, policyNumber, active.getPremiumFrequency(), amount,
    active.getPremiumCurrency(), firstDue, active.getPremiumPayingUntil());
scheduleRepo.save(next);
generateInvoicesAhead(tenantId, next, productVersionIdOf(policyNumber), firstDue.minusDays(1));
```
`productVersionIdOf` — billing already resolves the version for `resumeScheduleAfterSuspension`; reuse that lookup (read it before writing). Waive must not post to the GL differently from `endForVesting`'s waive — read `PremiumInvoice.waive` and the finaccounting listener for `billing.PremiumInvoiceWaived` (if any) before relying on it.

- [ ] **Step 1: Write `PremiumRestatementIntegrationTest`** (billing, no funeral needed — publish `policy.PremiumRestated` by hand on an ordinary monthly policy): `untouchedFutureInvoicesAreReRaisedAtTheNewAmount`; `anInstalmentPaidInAdvanceKeepsItsAmountAndTheNewOneStartsAfterIt`; `invoicesBeforeTheEffectiveDateAreUntouched`; `aSuspendedScheduleIsLeftAlone`.
- [ ] **Step 2: Write `FuneralEndorsementIntegrationTest`**: `addingABabyCoversItFromTheNextPremiumDateAndRaisesThePremium` (cover_start, waiting period ends, policy premium = restated instalment = what `quoteFuneral` gives for the six lives); `aSeventhChildIsRefusedWithTheQuotesWords`; `removingTheSpouseKeepsCoverToThePaidPeriodAndLowersThePremium` (cover_end = next premium date, still ACTIVE today); `theMainMemberCannotBeRemoved`; `anOrdinaryPolicyRefusesCoveredLives`.
- [ ] **Step 3: Run** — fails. **Step 4: Implement.** **Step 5:** contract test for both endpoints (201 body = `CoveredLifeView`; 409 `INVALID_POLICY_STATE` with the message).
- [ ] **Step 6: Run** `./mvnw -o clean test-compile; ./mvnw -o test -Dtest=PremiumRestatementIntegrationTest,FuneralEndorsementIntegrationTest,FuneralIssueIntegrationTest,PolicyContractTest,BillingApiIntegrationTest`.
- [ ] **Step 7: Commit** `feat(policy,billing): add and remove covered lives from the next premium date, restating the family premium`.

---

### Task 6: Policy — the nightly covered-life sweep (age out, scheduled ends, anniversary re-pricing)

**Files:**
- Create: `policy/application/CoveredLifeSweep.java`
- Modify: `CoveredLives.java` (+`sweep(String policyNumber, LocalDate today)`), `CoveredLifeRepository` (+policies with ACTIVE lives), `application.yml` (`funeral.sweep.interval`), `application-local.yml` (short override)
- Test: `funeral/CoveredLifeSweepIntegrationTest.java`

**What one policy's sweep does, in this order, all in one REQUIRES_NEW transaction per policy (a failure on one policy is logged and the next is swept):**
1. **Scheduled ends:** every ACTIVE life with `cover_end <= today` → ENDED with its `pending_end_reason` (`REMOVED` or `FREE_COVER_ENDED`), `ended_on = cover_end`, both scheduling columns cleared; publish `policy.CoveredLifeEnded`.
2. **Age out:** every ACTIVE dependant whose `stopAge(role, student)` is not null and `dateOfBirth.plusYears(stopAge) <= today` → ENDED `AGED_OUT`, `ended_on` = that birthday; publish; then `restate(policy, nextAfter(issueDate, freq, today), "A life aged out")`.
3. **Anniversary:** if `today` is a policy anniversary (`issueDate.plusYears(n).equals(today)`, n ≥ 1 — and Feb 29 issues use Feb 28 in non-leap years, `plusYears`' own rule), re-price every ACTIVE life at its age today through `productApi.funeralYearlyPremium(versionId, policy.funeralPlanCode, role, age)`, write `yearly_premium` and `priced_at_age`, and `restate(policy, nextAfter(issueDate, freq, today.minusDays(1)), "Anniversary re-pricing")` — the instalment due ON the anniversary is the first at the new rate. A pricing refusal (a life past the table's oldest age) holds that life at its old premium and logs `WARN` naming the policy, life and age; the publish check (Task 1 rule 7) makes this unreachable for a valid version, so the log is the alarm.
4. **Nothing left:** no ACTIVE life remains → close the policy through the existing `closeAsSurrendered(policy, null, tenantId)` (expose it package-private to `CoveredLives` or move the call into `PolicyApiImpl.closeFuneralPolicy`).

Steps 2 and 3 on the same day restate once (compute both, then one `restate`).

`CoveredLifeSweep`: `@Scheduled(fixedDelayString = "${funeral.sweep.interval:PT24H}", initialDelayString = "${funeral.sweep.interval:PT24H}")`, runs per tenant the way `VestingSweep` does (read it and copy its tenant loop), `today` = civil today.

- [ ] **Step 1: Write `CoveredLifeSweepIntegrationTest`** (call `coveredLives.sweep(policyNumber, day)` directly with chosen days, never the scheduler): `aChildEndsOnTheirTwentyFirstBirthdayAndThePremiumFalls`; `aStudentChildIsCoveredToTwentyFive`; `aRemovedSpouseEndsOnTheScheduledDate`; `theAnniversaryRepricesEveryLifeAtItsNewAgeBandButKeepsTheBenefits` (main member crossing 35→36 moves band; benefit unchanged; restated from the anniversary); `aMidYearBirthdayDoesNotMoveABand`; `agingOutAndAnniversaryOnOneDayRestateOnce` (count `policy.PremiumRestated` events = 1); `thePolicyClosesWhenNoLifeRemains`.
- [ ] **Step 2: Run** — fails. **Step 3: Implement** (and the two V35 columns).
- [ ] **Step 4: Run** `./mvnw -o test -Dtest=CoveredLifeSweepIntegrationTest,FuneralEndorsementIntegrationTest,FuneralIssueIntegrationTest`.
- [ ] **Step 5: Commit** `feat(policy): the nightly funeral sweep -- children age out, scheduled ends take effect, and every life is re-priced at the anniversary`.

---

### Task 7: Claims — a claim on one covered life

**Files:**
- Create: `db-migrations/claims/V10__funeral_claims.sql`
- Modify: `claims/api/ClaimsApi.java` (`RegisterClaimRequest` + `coveredLifeId`, `accidental`; keep a 6-arg constructor), `claims/api/ClaimView.java` (+`coveredLifeId`, `accidental`), `claims/api/ClaimDeclineReason.java` (+`WITHIN_WAITING_PERIOD`), `claims/domain/Claim.java`, `claims/domain/ExclusionPeriods.java` (+`waitingMonths`; 2-arg constructor kept), `claims/domain/ExclusionWindows.java`, `claims/application/ClaimsApiImpl.java` (register: payee rule R9, second-death guard keyed on the covered life; decide: waiting gate R7; discharge passes the covered life), `claims/application/PaymentEventListener.java` (pass `coveredLifeId`), `claims/infrastructure` request/response DTOs, `api/openapi/openapi-claims.yaml`
- Modify (policy side): `PolicyApi.claimableCover` overload (+`coveredLifeId`), `exclusionPeriodsFor` overload (+`coveredLifeId`), `ExclusionPeriodsView` (+`waitingMonths`, `accidentWaivesWaiting`), `PolicyApi.funeralClaimFacts(policyNumber, coveredLifeId)` → `Optional<FuneralClaimFacts>` (empty off funeral), a new `policy.api` record `FuneralClaimFacts(String role, UUID policyholderPartyId, String dependantClaimPayee, List<UUID> beneficiaryPartyIds)` — **Strings, not `FuneralRole`/`DependantClaimPayee`: claims may not reference a product type** (ModularityTests; `claimableCover` passes the benefit type as a name for the same reason), `dischargeForSettledClaim` overload (+`coveredLifeId`) with a FUNERAL branch in `CoveredLives.discharge`, `PolicyApi.takeOver(policyNumber, identity, by)`, `PolicyApi.promoteCoveredLife(policyNumber, coveredLifeId, PromoteMemberRequest, by)`, `PolicyController` (`POST .../covered-lives/{id}/promotion`, `POST /policies/{n}/takeover`)
- Test: `claims/ExclusionWindowsTest.java` (extend), `funeral/FuneralClaimIntegrationTest.java`, `funeral/MainMemberDeathIntegrationTest.java`, extend `ClaimsContractTest`, `PolicyContractTest`

**Migration (`claims/V10__funeral_claims.sql`):**
```sql
ALTER TABLE claims.claim ADD COLUMN covered_life_id UUID, ADD COLUMN accidental BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE claims.claim DROP CONSTRAINT chk_claim_decline_reason_known;
ALTER TABLE claims.claim ADD CONSTRAINT chk_claim_decline_reason_known CHECK (
    decline_reason IS NULL OR decline_reason IN
        ('SUICIDE_WITHIN_EXCLUSION', 'PRE_EXISTING_WITHIN_EXCLUSION', 'WITHIN_WAITING_PERIOD'));
-- A claim names a scheme member or a covered life, never both.
ALTER TABLE claims.claim ADD CONSTRAINT chk_claim_one_life_reference
    CHECK (policy_member_id IS NULL OR covered_life_id IS NULL);
CREATE INDEX idx_claim_covered_life ON claims.claim (tenant_id, policy_number, covered_life_id) WHERE covered_life_id IS NOT NULL;
```

**`ExclusionWindows.openAt`** gains the waiting window: `if (!(accidental && periods.accidentWaivesWaiting()) && insideWindow(coverStart, dateOfEvent, periods.waitingMonths())) open.add(WITHIN_WAITING_PERIOD);` — signature becomes `openAt(LocalDate coverStart, LocalDate dateOfEvent, ExclusionPeriods periods, boolean accidental)`, the old 3-arg form delegating with `false`. `ExclusionPeriods(Integer suicideMonths, Integer preExistingMonths, Integer waitingMonths, boolean accidentWaivesWaiting)`.

**Policy side:**
- `claimableCover(policyNumber, policyMemberId, coveredLifeId, asOf, benefitType)`: on a FUNERAL policy `coveredLifeId` is required ("A claim on a funeral plan names the covered life who died") and must be on this policy; the life must have been covered on `asOf` (`coverStart <= asOf` and (ACTIVE and (`coverEnd` null or `asOf < coverEnd`)) or (ENDED and `asOf < endedOn`)) else `InvalidPolicyStateException` "… was not covered on …"; returns the life's `benefit`. Off a FUNERAL policy a `coveredLifeId` is refused.
- `exclusionPeriodsFor(policyNumber, policyMemberId, coveredLifeId)`: `coverStart` = the covered life's own `cover_start`; adds the funeral terms' waiting months and waiver; 0/false off funeral.
- `CoveredLives.discharge(policy, coveredLifeId, dateOfEvent, claimId)`:
  - **Dependant:** life ENDED `DECEASED` on `dateOfEvent`; publish `CoveredLifeEnded`; `restate(policy, nextAfter(issueDate, freq, today), "A covered life died")`.
  - **Main member, POLICY_ENDS:** main life ENDED `DECEASED`. Free cover off → every other ACTIVE life ENDED `MAIN_MEMBER_DIED` on `dateOfEvent`, then `closeAsSurrendered(policy, claimId, tenantId)`. Free cover on → every other ACTIVE life gets `cover_end = nextAfter(issueDate, freq, dateOfEvent)` with pending reason `FREE_COVER_ENDED`; publish `policy.PremiumsEnded {policyNumber, after = dateOfEvent, reason}` (billing: new handler → `endBillingAfter`, which terminates the schedule and waives unsettled invoices due after `after` — generalise `endForVesting` into it and keep `endForVesting` delegating); the sweep closes the policy when the last life ends (Task 6 step 4).
  - **Main member, SPOUSE_TAKES_OVER:** main life ENDED `DECEASED`; an ACTIVE spouse with no `cover_end` → `policy.awaiting_takeover_life_id = spouse`, policy stays IN_FORCE, billing continues (R8). No such spouse → the POLICY_ENDS path above.
- `takeOver(policyNumber, PromoteMemberRequest identity, by)`: requires `awaiting_takeover_life_id`; promotes the spouse (reuse `promoteMember`'s REUSE-before-register logic — extract it to a private `partyFor(name, dob, identity, by)`), `Policy.changePolicyholder(partyId)` (new) + `recordLifeAssured(partyId)`, spouse row → role `MAIN_MEMBER`, priced at the MAIN_MEMBER rate for its age, clears the flag, `restate(...)` from the next premium date, publishes `policy.PolicyholderChanged {policyNumber, previousPartyId, policyholderPartyId}`.

**Claims side (`registerClaim`)**: pass `request.coveredLifeId()` to `claimableCover`; second-death guard: when `coveredLifeId != null`, search `findBy…CoveredLifeId…` (new repository method) instead of the policy-wide one; **payee (R9):** when the policy answers `funeralClaimFacts` and the role is not MAIN_MEMBER, claimant must equal `policyholderPartyId` (MAIN_MEMBER) or be in `beneficiaryPartyIds` (MAIN_MEMBER_BENEFICIARY), else `ClaimValidationException` naming who may file. Store `coveredLifeId`, `accidental`.

**`decideSettlement`:**
- **Approval guard (R7):** for a DEATH claim with a `coveredLifeId`, compute `openAt(...)` with the claim's `accidental`; if it contains `WITHIN_WAITING_PERIOD` → `InvalidClaimStateException` "Claim … cannot be approved: the death on … was inside the waiting period, which runs to …. Decline it with WITHIN_WAITING_PERIOD, or record the death as accidental if it was and the product waives accidents."
- **Decline:** the existing gate already refuses a reason whose window was closed; `monthsOf` gains the WITHIN_WAITING_PERIOD case (`windows.waitingMonths()`).
- `PaymentEventListener` passes `facts.coveredLifeId()` to `dischargeForSettledClaim`.
- `accidental` is also settable on `submitAssessment` (an optional `Boolean accidental` on the assessment request; null leaves it) — the assessor is the one who learns the cause.

- [ ] **Step 1: Extend `ExclusionWindowsTest`**: waiting open inside 6 months; closed on the 6-month anniversary; accidental + waiver → not open; accidental without waiver → open; null waiting months → never open.
- [ ] **Step 2: Write `FuneralClaimIntegrationTest`** (issue a family, mark the policy IN_FORCE by collecting the first premium as `AnnuityTestFixtures.collect` does, date-shift `cover_start` back in SQL by tenant where a test needs an elapsed waiting period): `aChildsDeathAfterTheWaitingPeriodPaysTheChildsBenefitAndThePolicyCarriesOn` (approved amount = child benefit; after settlement the child is ENDED DECEASED, the policy IN_FORCE, premium restated lower); `aNaturalDeathInsideTheWaitingPeriodCannotBeApprovedAndIsDeclinedForIt`; `anAccidentalDeathInsideTheWaitingPeriodIsPaidWhenTheProductWaivesIt`; `aClaimOnAFuneralPlanMustNameTheLife`; `aSecondClaimOnTheSameChildIsRefusedButOnASiblingIsNot`; `aDependantsClaimFiledBySomeoneElseIsRefused`; `aDeclinedClaimLeavesTheLifeActive`; `promotingADependantRegistersThemOnce`.
- [ ] **Step 3: Write `MainMemberDeathIntegrationTest`** (publish three versions: POLICY_ENDS no free cover, POLICY_ENDS with free cover, SPOUSE_TAKES_OVER): `policyEndsWithoutFreeCoverEndsEveryLifeAndClosesThePolicy`; `freeCoverKeepsTheFamilyCoveredToTheNextPremiumDateWithNoFurtherInvoices` (no DUE invoice after the death; a child's death claim before `cover_end` is payable; the sweep on `cover_end` closes the policy); `theSpouseTakesOverAndBecomesTheMainMemberAtTheMainMemberRate`; `noSpouseFallsBackToPolicyEnds`.
- [ ] **Step 4: Run** — fails. **Step 5: Implement**; append claims V10.
- [ ] **Step 6: Contract tests:** `ClaimsContractTest` — register accepts `coveredLifeId`, `accidental`; view returns both; 409 approval message. `PolicyContractTest` — promotion and takeover endpoints.
- [ ] **Step 7: Run** `./mvnw -o clean test-compile; ./mvnw -o test -Dtest=ExclusionWindowsTest,FuneralClaimIntegrationTest,MainMemberDeathIntegrationTest,ClaimsContractTest,PolicyContractTest,ClaimsApiIntegrationTest,CreditLifeClaimIntegrationTest` (confirm the last two class names with `ls src/test/java/tz/co/nlolo/lifeplatform/claims`).
- [ ] **Step 8: Commit** `feat(claims,policy): a death claim on one covered life -- waiting period, payee, and the main member's death rules`.

---

### Task 8: Communication — the three notices

**Files:**
- Create: `db-migrations/communication/V12__funeral_templates.sql`, `communication/application/FuneralEventListener.java`
- Test: `funeral/FuneralNoticeIntegrationTest.java`

Templates (`sw` and `en`, SMS and EMAIL, same shape as V11's four rows each):
- `FUNERAL_LIFE_ADDED` — en: "Policy {{policyNumber}}: {{fullName}} is covered from {{coverStart}}." / sw: "Bima {{policyNumber}}: {{fullName}} amelindwa kuanzia tarehe {{coverStart}}."
- `FUNERAL_LIFE_ENDED` — en: "Policy {{policyNumber}}: cover for {{fullName}} ended on {{endedOn}}." / sw: "Bima {{policyNumber}}: kinga ya {{fullName}} imeisha tarehe {{endedOn}}."
- `FUNERAL_PREMIUM_CHANGED` — en: "Policy {{policyNumber}}: your premium is {{amount}} {{currency}} from {{effectiveFrom}}." / sw: "Bima {{policyNumber}}: ada yako ni {{amount}} {{currency}} kuanzia tarehe {{effectiveFrom}}."

`FuneralEventListener` mirrors `AnnuityEventListener` exactly (AFTER_COMMIT, tenant save/restore, REQUIRES_NEW, catch-and-log) for `policy.CoveredLifeAdded`, `policy.CoveredLifeEnded`, `policy.PremiumRestated`; dates `dd/MM/yyyy`; the recipient is `policyholderPartyId` (PremiumRestated's payload gains `policyholderPartyId` — add it in Task 5's publisher now). `DECEASED` ends send nothing: the family is already in a claim, and a "cover ended" SMS about a death is cruel.

- [ ] **Step 1:** test `addingALifeSendsTheNotice`, `aRestatementSendsTheNewPremium`, `aDeathSendsNoCoverEndedNotice` (read `communication.notification_dispatch` by tenant). **Step 2:** run — fails. **Step 3:** implement; append communication V12. **Step 4:** run `./mvnw -o test -Dtest=FuneralNoticeIntegrationTest,VestingReminderIntegrationTest`. **Step 5:** commit `feat(communication): tell the main member when a life is added or ends and when the premium changes`.

---

### Task 9: Console

**Files:**
- Create: `frontend/src/api/funeral.ts`, `store/funeralStore.ts`, `features/products/funeralSchema.ts` (+`.test.ts`), `features/products/FuneralTermsSection.tsx`, `features/underwriting/funeralApplicationForm.ts` (+test), `features/underwriting/FuneralLivesFields.tsx`, `features/policies/CoveredLivesPanel.tsx` (+test), `features/policies/coveredLifeForm.ts` (+test), `features/policies/TakeoverPanel.tsx`
- Modify: `features/products/publishVersionSchema.ts` + `PublishVersionForm.tsx` (FUNERAL shows the funeral section and hides rating factors and base rates — R1), `features/underwriting/openCaseForm.ts` + `OpenUnderwritingCasePage.tsx` (plan picker, lives, live quote; sum assured read-only = main member benefit — R3), `UnderwritingCaseDetailPage.tsx` (Funeral panel: plan + lives + quote), `features/policies/PolicyDetailPage.tsx` (Covered lives tab, Takeover banner when awaiting), `features/claims/claimRegisterForm.ts` + `RegisterClaimPage.tsx` ("Who died?" select of ACTIVE lives; "Accidental death" checkbox), `features/claims/ClaimAssessmentPanel.tsx` (accidental), `lib/status.ts` (covered-life statuses), `api/types.ts` aliases; regenerate types with `node scripts/generate-api-types.mjs`
- Agent console: `features/agents/` onboarding path reuses `OpenUnderwritingCasePage` — confirm with `grep -rn "OpenUnderwritingCasePage" src` and add nothing if it is shared.

**Shapes:**
- `funeralSchema.ts`: zod for `FuneralRequest`, messages matching Task 1's server words for the rules the console can check (plan codes unique; MAIN_MEMBER rule present; every plan covers the main member; per-role age coverage reported as the first gap: "Plan A, CHILD: no premium for age 21"). Each message on a rendered path (`plans.0.planCode`, `premiums`, `roles.CHILD.maxEntryAge`).
- `FuneralTermsSection.tsx`: four sub-sections — Plans (code, name, a benefit input per role), Premium table (rows plan/role/from/to/yearly; "Paste CSV" textarea parsed as `plan,role,ageFrom,ageTo,yearlyPremium` with a header row), Role rules (five rows with an Allowed switch), Claim rules (waiting months, accident waiver, payee select, main-member-death select, free-cover switch, highest priced age default 100).
- `funeralApplicationForm.ts`: `{ planCode, frequency, dependants: [{ role, fullName, dateOfBirth, sex, idNumber, student }] }`; the live quote is the server's (`POST .../funeral-quote` debounced 400ms) — never a client-side price; quote refusal shown under the lives list.
- `CoveredLivesPanel.tsx`: table (Role, Name, Age, Benefit, Yearly premium, Cover start, Waiting period ends, Status/End reason), "Add life" (inline form → `POST /policies/{n}/covered-lives`), "Remove" per non-main row with a reason (confirmation built into the panel — no `confirm()`), "Promote" for a name-only life with an identity document form.
- Accessible names (e2e couples to them — memory): buttons "Add life", "Remove life", "Promote to client", "Complete takeover"; table caption "Covered lives"; select label "Who died?"; checkbox label "Accidental death".

- [ ] **Step 1:** write the three schema/form tests (one per message and one valid case each) and `CoveredLivesPanel.test.tsx` (renders lives, hides Remove on the main member, shows the waiting-period date). **Step 2:** run `npx vitest run src/features/products/funeralSchema.test.ts src/features/underwriting/funeralApplicationForm.test.ts src/features/policies` — fails. **Step 3:** implement. **Step 4:** `npm run typecheck; npm run lint; npx vitest run` — all green (record the count). **Step 5:** commit `feat(console): publish a funeral plan, sell it to a family with a live quote, manage covered lives, and claim on one of them`.

---

### Task 10: Seed and end-to-end

**Files:**
- Modify: `backend/scripts/seed-dev-data.sh` (FUN-FAM-01 "Nlolo Familia": plans A and B with the spec's example benefits, premiums banded 0-20 / 21-35 / 36-50 / 51-65 / 66-75 / 76-100 per role, waiting 6, accident waives, MAIN_MEMBER payee, POLICY_ENDS, free cover on, 5% monthly loading)
- Create: `frontend/e2e/staff-family-funeral.spec.ts`

Apply V24, V16, V35, V10, V12 to the dev DB by hand (psql, as D2 did — memory: dev backend and migrations are decoupled), restart the dev backend, run the seed block from `scripts/` (it `cd`s relative to itself).

**Spec flow:** publish a fresh funeral product through the console (unique code per run); open a case for a seeded VERIFIED client aged ~40 on plan B with spouse 38 and children 10, 7, 3 — assert the quote lines and total; assess and accept (second underwriter); open the policy → Covered lives tab shows five rows; Add life (baby, born 2 months ago) → six rows, premium restated; collect a premium (the existing staff payment step the D2 spec uses); register a claim "Who died?" = the 10-year-old with the policy's `cover_start` shifted back 7 months via the seed's psql helper used by earlier specs (or a product with waiting 0 — prefer a second product with no waiting period over SQL shifting if no helper exists), assess, approve, settle → child ENDED, policy IN_FORCE; register a claim on the 7-year-old on the first product (waiting 6, death today) → Approve is refused with the waiting-period message, decline with WITHIN_WAITING_PERIOD succeeds.

DatePicker trap (memory): `fill('')` before filling a pre-filled date field. Timeouts: decision headings 60s, as D2.

- [ ] **Step 1:** seed + apply migrations; `curl` the funeral quote for FUN-FAM-01 with a real Keycloak token (201/200, figures as seeded). **Step 2:** write the spec. **Step 3:** run it alone twice with deps: `npx playwright test e2e/staff-family-funeral.spec.ts` — green both times. **Step 4:** commit `test(e2e): a family sold plan B, a baby added, a child's death paid and a death inside the waiting period declined`.

---

### Task 11: Gate, review, merge

- [ ] **Step 1:** stop the dev backend and orphan JVMs; `./mvnw clean test` (background, ~80 min) — record totals; zero failures/errors. Never alongside Playwright.
- [ ] **Step 2:** frontend `npm run typecheck; npm run lint; npx vitest run`.
- [ ] **Step 3:** full e2e `npx playwright test` with deps (fresh auth) — record passed/failed/skipped; any failure diagnosed, never waved off as pre-existing (memory).
- [ ] **Step 4:** whole-branch self-review against the spec and §R, hunting the seams between tasks (memory: the final review finds systemic bugs): the premium arithmetic agrees in quoter / restate / billing; every path that ends a life restates or closes; a FUNERAL policy never reaches the single-life `closeAsSurrendered` on a dependant's death; the second-death guard keys on the covered life; nothing reads `covered_life` without the category gate.
- [ ] **Step 5:** fix cycle, re-run the affected classes, update the memory file, ask the user to sign off §R and any deviations, then `git merge --no-ff` into `main` with a message file and `git push origin main`.
