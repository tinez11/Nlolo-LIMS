# Product step 4 — with-profits bonuses — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **This plan will be executed inline** (the user prefers direct implementation), so read it with that in mind.

**Goal:** A product version may be with-profits. Bonuses are declared per product with two-person approval, attached to eligible policies by a drain, and paid into maturity, the death limit and the surrender value. A new `bonus` module owns all of it, with an immutable attachment ledger and once-only attachments.

**Architecture:** `bonus` keeps its own status record for every participating policy, built from policy events, because policy keeps no status history. A drain judges eligibility on that record as at each declaration's valuation date. It writes one outcome per declaration and policy, and one ledger entry where a bonus attaches. `benefitpayout` reads `bonus::api` for maturity and the death limit. Policy holds a projection of the attached total, which the bonus module restates and the surrender quote reads.

**Tech Stack:** Spring Boot 3, Spring Modulith (`module::api`), Postgres 16 with RLS, Testcontainers, OpenAPI 3.1, React + Zustand + Vitest + Playwright.

**Spec:** `backend/docs/superpowers/specs/2026-10-02-product-step4-bonuses-design.md`. **§11 below lists the revisions made while planning; this plan follows them where they differ from the spec.**

## Global Constraints

- Cut `product-step4-bonuses` from `main` (step 3 merged at `5fc2c8f8`, spec at `cee74db8`).
- Money is `{amount: "<decimal string>", currencyCode}` on every wire and event payload — never a JSON number.
- Every cross-module reaction is an AFTER_COMMIT `@TransactionalEventListener` on `DomainEventEnvelope`, in its own `REQUIRES_NEW` transaction, under the envelope's tenant, never rethrowing. Listener and runner beans get explicit, module-prefixed names (`@Component("bonusPolicyEventListener")`): several modules already declare `PolicyEventListener`, `ClaimEventListener` and `EnvelopeRunner`.
- `bonus` depends on `policy::api` and `product::api` only. `benefitpayout` gains `bonus::api`. `bonus` never depends on `benefitpayout` or `claims`; it reads `claims.ClaimApproved` by envelope.
- The attachment ledger, the outcomes, the status record and the settlements are **append-only**: `app_role` holds `SELECT, INSERT` only, and a trigger refuses `UPDATE`/`DELETE` for every role. Corrections are `REVERSAL` entries.
- One `(tenant_id, source_type, source_ref)` → at most one ledger entry, and one `(declaration_id, policy_number)` → at most one outcome. Both are **unique indexes**, not check-then-insert.
- Eligibility (Q3) is its own rule and NEVER reads `isInForce`. Status on a date D = the latest status row with `effective_at` before the start of D+1 in **Africa/Dar_es_Salaam**.
- Rounding: each figure once, to 2 dp, `HALF_EVEN`.
- RLS on every new table: `USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid)`.
- Cross-tenant sweeps read **ids only** through `SECURITY DEFINER` functions with `EXECUTE` revoked from `PUBLIC` and granted to `app_role`.
- Refusals that are a rule are `BonusStateException` → **422** `BONUS_REFUSED`, with the message shown verbatim by the console.
- `ddl-auto` is `none`: **any test class that reaches code reading a new table must apply the new migration.** Every task that adds a migration has a grep step to find those classes.
- Run only that task's test classes while building. The full suite and e2e run only at the gate (Task 10). Never run Maven, vitest and Playwright concurrently. Never edit sources while a full Maven run is in progress. Stop the dev backend before any `clean`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

## File structure

**New module `backend/src/main/java/tz/co/nlolo/lifeplatform/bonus/`**

| File | Responsibility |
|---|---|
| `package-info.java` | `@ApplicationModule(allowedDependencies = { "policy::api", "product::api" })` |
| `api/package-info.java` | `@NamedInterface("api")` |
| `api/BonusApi.java` | the published face |
| `api/BonusValuation.java`, `BonusEntryView.java`, `BonusOutcomeView.java`, `BonusSettlementView.java`, `PolicyBonusView.java`, `BonusDeclarationView.java` | read models |
| `api/BonusEntryType.java`, `DeclarationStatus.java`, `OutcomeKind.java`, `ExitType.java` | enums |
| `api/BonusStateException.java` | refusals |
| `domain/Participant.java` | one row per participating policy, and the ledger's running head (`last_seq`, `attached_total`, `@Version`) |
| `domain/StatusEvent.java`, `AttachmentEntry.java`, `DeclarationOutcome.java`, `Settlement.java` | insert-only rows |
| `domain/Declaration.java` | the declaration aggregate |
| `domain/Eligibility.java` | pure: the Q3 rule on a status record |
| `domain/BonusArithmetic.java` | pure: reversionary, interim, terminal |
| `application/AttachmentLedger.java` | the ONE place an entry is written, and where policy's projection is restated |
| `application/BonusApiImpl.java` | everything else |
| `application/BonusEnvelopeRunner.java` | tenant + REQUIRES_NEW + never-rethrow |
| `application/PolicyEventListener.java`, `ClaimEventListener.java` | reactions |
| `application/DeclarationDrain.java` | the scheduled attachment run |
| `infrastructure/*Repository.java`, `BonusController.java`, `BonusExceptionHandler.java`, request/response records | REST |

**Migrations:** `db-migrations/product/V20__bonus_terms.sql`, `db-migrations/bonus/V1__create_bonus_schema.sql`, `db-migrations/policy/V32__attached_bonus_projection.sql`.

**Specs:** `api/openapi/openapi-bonus.yaml` (new), `api/openapi/openapi-product.yaml`, `api/openapi/openapi-policy.yaml`, `api/asyncapi-events.yaml`.

**Frontend:** `src/api/bonus.ts`, `src/store/bonusStore.ts`, `src/gates/bonusGates.ts`, `src/features/bonuses/` (`BonusDeclarationsPanel.tsx`, `bonusDeclarationForm.ts`, `PolicyBonusesPanel.tsx`), plus changes to `publishVersionSchema.ts`, `PublishVersionForm.tsx`, `ProductDetailPage.tsx`, `PolicyDetailPage.tsx`, `ValueActions.tsx`, `lib/status`, `api/types.ts`.

---

### Task 1: Product — with-profits terms on a version

**Files:**
- Create: `db-migrations/product/V20__bonus_terms.sql`
- Create: `product/api/BonusMethod.java`, `BonusSurrenderBasis.java`, `BonusSurrenderRow.java`, `BonusPlan.java`
- Create: `product/domain/BonusPlanValidator.java`, `VersionBonusTerms.java`, `BonusSurrenderEntry.java`
- Create: `product/infrastructure/VersionBonusTermsRepository.java`, `BonusSurrenderEntryRepository.java`, `BonusRequest.java`
- Modify: `product/api/ProductApi.java`, `product/application/ProductApiImpl.java`, `product/infrastructure/PublishVersionRequest.java`, `ProductController.java`, `api/openapi/openapi-product.yaml`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/product/BonusPlanValidatorTest.java`, extend `ProductContractTest`, raise the overload guard in `ProductApiIntegrationTest`

**Interfaces:**
- Produces: `enum BonusMethod { SIMPLE, COMPOUND }`; `enum BonusSurrenderBasis { NONE, SUM_ASSURED_SCALE, OWN_SCALE }`; `record BonusSurrenderRow(int fromCompletedYears, BigDecimal perMille)`; `record BonusPlan(boolean participating, BonusMethod method, boolean paidUpParticipates, BonusSurrenderBasis surrenderBasis, List<BonusSurrenderRow> surrenderRows)` with `static BonusPlan none()` and `BigDecimal ownScalePerMille(int completedYears)`; `ProductApi.resolveBonusPlan(UUID productVersionId) -> BonusPlan`; an 8th `publishVersion` overload ending `AccumulationPlan accumulationPlan, BonusPlan bonusPlan, String publishedBy`.

- [ ] **Step 1: Write the failing validator test**

```java
// src/test/java/tz/co/nlolo/lifeplatform/product/BonusPlanValidatorTest.java
package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.BonusPlanValidator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BonusPlanValidator, rule by rule, in the exact words the console mirrors. */
class BonusPlanValidatorTest {

    private static final CashValuePlan SCALE = new CashValuePlan("ACT/1", LocalDate.of(2026, 1, 1), "PROPORTIONATE", 2,
        List.of(new CashValueRowInput(2, null, null, new BigDecimal("200"), null)));
    private static final PayoutPlan MATURITY = PayoutPlan.authored(new PayoutTerms(15, null, null, null),
        List.of(new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null)));
    private static final AccumulationPlan ACCOUNT = new AccumulationPlan(ValueBasis.ACCOUNT, BigDecimal.ONE, BigDecimal.ZERO,
        List.of(new AccumulationChargeRow(1, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));

    private static BonusPlan plan(BonusSurrenderBasis basis, List<BonusSurrenderRow> rows) {
        return new BonusPlan(true, BonusMethod.COMPOUND, false, basis, rows);
    }

    private static void ok(ProductCategory c, BonusPlan p, CashValuePlan cv, PayoutPlan payout) {
        assertThatCode(() -> BonusPlanValidator.validate(c, p, cv, AccumulationPlan.none(), payout)).doesNotThrowAnyException();
    }

    private static void refused(ProductCategory c, BonusPlan p, CashValuePlan cv, AccumulationPlan acc, PayoutPlan payout, String message) {
        assertThatThrownBy(() -> BonusPlanValidator.validate(c, p, cv, acc, payout)).hasMessage(message);
    }

    @Test
    void aNonParticipatingVersionIsNotChecked() {
        ok(ProductCategory.TERM_LIFE, BonusPlan.none(), CashValuePlan.none(), PayoutPlan.none());
    }

    @Test
    void acceptsEachSurrenderBasisWhenItsInputsArePresent() {
        ok(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.NONE, List.of()), CashValuePlan.none(), MATURITY);
        ok(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.SUM_ASSURED_SCALE, List.of()), SCALE, MATURITY);
        ok(ProductCategory.WHOLE_LIFE, plan(BonusSurrenderBasis.OWN_SCALE,
            List.of(new BonusSurrenderRow(2, new BigDecimal("300")))), CashValuePlan.none(), PayoutPlan.none());
    }

    @Test
    void onlyEndowmentAndWholeLifeMayBeWithProfits() {
        refused(ProductCategory.TERM_LIFE, plan(BonusSurrenderBasis.NONE, List.of()), CashValuePlan.none(),
            AccumulationPlan.none(), PayoutPlan.none(), "A TERM_LIFE product cannot be with-profits");
    }

    @Test
    void aVersionIsNotBothAnAccountAndWithProfits() {
        refused(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.NONE, List.of()), CashValuePlan.none(), ACCOUNT, MATURITY,
            "A version is valued either by an account or with profits, not both");
    }

    @Test
    void theMethodAndTheSurrenderBasisMustBeStated() {
        refused(ProductCategory.ENDOWMENT, new BonusPlan(true, null, false, BonusSurrenderBasis.NONE, List.of()),
            CashValuePlan.none(), AccumulationPlan.none(), MATURITY,
            "A with-profits version must state its bonus method (SIMPLE or COMPOUND)");
        refused(ProductCategory.ENDOWMENT, new BonusPlan(true, BonusMethod.SIMPLE, false, null, List.of()),
            CashValuePlan.none(), AccumulationPlan.none(), MATURITY,
            "A with-profits version must state how attached bonuses count toward surrender (NONE, SUM_ASSURED_SCALE or OWN_SCALE)");
    }

    @Test
    void eachSurrenderBasisNeedsItsInputs() {
        refused(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.SUM_ASSURED_SCALE, List.of()), CashValuePlan.none(),
            AccumulationPlan.none(), MATURITY, "SUM_ASSURED_SCALE needs the version's own cash-value scale");
        refused(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.OWN_SCALE, List.of()), CashValuePlan.none(),
            AccumulationPlan.none(), MATURITY, "OWN_SCALE needs at least one row of bonus surrender values");
        refused(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.OWN_SCALE, List.of(
                new BonusSurrenderRow(2, new BigDecimal("300")), new BonusSurrenderRow(2, new BigDecimal("400")))),
            CashValuePlan.none(), AccumulationPlan.none(), MATURITY, "Bonus surrender rows must each start at a different completed year");
        refused(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.OWN_SCALE, List.of(
                new BonusSurrenderRow(2, new BigDecimal("1001")))),
            CashValuePlan.none(), AccumulationPlan.none(), MATURITY, "A bonus surrender value must be between 0 and 1000 per mille");
        refused(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.NONE, List.of(new BonusSurrenderRow(2, BigDecimal.TEN))),
            CashValuePlan.none(), AccumulationPlan.none(), MATURITY, "Bonus surrender rows are only for OWN_SCALE");
    }

    @Test
    void aWithProfitsEndowmentMustPayAMaturity() {
        refused(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.NONE, List.of()), CashValuePlan.none(),
            AccumulationPlan.none(), PayoutPlan.none(),
            "A with-profits ENDOWMENT needs a MATURITY payout, or its bonuses could never be paid at term end");
    }

    @Test
    void theOwnScaleIsReadByTheLatestRowStartedAndIsZeroBeforeTheFirst() {
        BonusPlan p = plan(BonusSurrenderBasis.OWN_SCALE, List.of(
            new BonusSurrenderRow(2, new BigDecimal("300")), new BonusSurrenderRow(5, new BigDecimal("600"))));
        org.assertj.core.api.Assertions.assertThat(p.ownScalePerMille(1)).isEqualByComparingTo("0");
        org.assertj.core.api.Assertions.assertThat(p.ownScalePerMille(2)).isEqualByComparingTo("300");
        org.assertj.core.api.Assertions.assertThat(p.ownScalePerMille(4)).isEqualByComparingTo("300");
        org.assertj.core.api.Assertions.assertThat(p.ownScalePerMille(9)).isEqualByComparingTo("600");
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -o test -Dtest=BonusPlanValidatorTest`
Expected: COMPILATION FAILURE — `BonusPlan`, `BonusPlanValidator` do not exist.

- [ ] **Step 3: The API records**

```java
// product/api/BonusMethod.java
package tz.co.nlolo.lifeplatform.product.api;

/**
 * How a reversionary bonus is computed (product step 4, Q1). SIMPLE: rate × sum assured. COMPOUND:
 * rate × (sum assured + bonuses already attached) -- so earlier bonuses earn bonus too.
 */
public enum BonusMethod { SIMPLE, COMPOUND }
```

```java
// product/api/BonusSurrenderBasis.java
package tz.co.nlolo.lifeplatform.product.api;

/**
 * What attached bonuses add to a surrender (product step 4, Q6). There is NO default: the user's
 * rule is that the sum assured's cash-value factor is never assumed to apply to bonuses unless the
 * version says so. NONE adds nothing; SUM_ASSURED_SCALE applies step 1's factor for the completed
 * years, explicitly chosen; OWN_SCALE reads the version's own per-mille rows.
 */
public enum BonusSurrenderBasis { NONE, SUM_ASSURED_SCALE, OWN_SCALE }
```

```java
// product/api/BonusSurrenderRow.java
package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/** Per 1,000 of attached bonus, from this many completed policy years until the next row starts. */
public record BonusSurrenderRow(int fromCompletedYears, BigDecimal perMille) {}
```

```java
// product/api/BonusPlan.java
package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * A version's with-profits terms (product step 4). {@link #none()} is a non-participating version,
 * which every version published before this step is.
 *
 * @param paidUpParticipates the contract rule (Q3): whether a policy paid-up on a valuation date
 *     still receives that declaration. False unless the version says so.
 */
public record BonusPlan(boolean participating, BonusMethod method, boolean paidUpParticipates,
                        BonusSurrenderBasis surrenderBasis, List<BonusSurrenderRow> surrenderRows) {

    public BonusPlan {
        surrenderRows = surrenderRows != null ? List.copyOf(surrenderRows) : List.of();
    }

    public static BonusPlan none() {
        return new BonusPlan(false, null, false, null, List.of());
    }

    /** OWN_SCALE: the row started most recently at or before these years; zero before the first. */
    public BigDecimal ownScalePerMille(int completedYears) {
        return surrenderRows.stream()
            .filter(r -> r.fromCompletedYears() <= completedYears)
            .max(Comparator.comparingInt(BonusSurrenderRow::fromCompletedYears))
            .map(BonusSurrenderRow::perMille)
            .orElse(BigDecimal.ZERO);
    }
}
```

- [ ] **Step 4: The validator**

```java
// product/domain/BonusPlanValidator.java
package tz.co.nlolo.lifeplatform.product.domain;

import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Everything a CHECK cannot say about a with-profits version (product step 4, Q6 and Q9). Pure and
 * static, like AccumulationPlanValidator.
 */
public final class BonusPlanValidator {

    private static final Set<ProductCategory> CATEGORIES = EnumSet.of(ProductCategory.ENDOWMENT, ProductCategory.WHOLE_LIFE);
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private BonusPlanValidator() {}

    public static void validate(ProductCategory category, BonusPlan plan, CashValuePlan cashValue,
                                AccumulationPlan accumulation, PayoutPlan payout) {
        if (plan == null || !plan.participating()) {
            return;
        }
        if (!CATEGORIES.contains(category)) {
            fail("A " + category + " product cannot be with-profits");
        }
        if (accumulation != null && accumulation.isAccount()) {
            fail("A version is valued either by an account or with profits, not both");
        }
        if (plan.method() == null) {
            fail("A with-profits version must state its bonus method (SIMPLE or COMPOUND)");
        }
        if (plan.surrenderBasis() == null) {
            fail("A with-profits version must state how attached bonuses count toward surrender (NONE, SUM_ASSURED_SCALE or OWN_SCALE)");
        }
        switch (plan.surrenderBasis()) {
            case NONE, SUM_ASSURED_SCALE -> {
                if (!plan.surrenderRows().isEmpty()) {
                    fail("Bonus surrender rows are only for OWN_SCALE");
                }
                if (plan.surrenderBasis() == BonusSurrenderBasis.SUM_ASSURED_SCALE
                        && (cashValue == null || !cashValue.isPresent())) {
                    fail("SUM_ASSURED_SCALE needs the version's own cash-value scale");
                }
            }
            case OWN_SCALE -> checkOwnScale(plan);
        }
        boolean paysMaturity = payout != null && payout.rows().stream().anyMatch(r -> r.kind() == PayoutKind.MATURITY);
        if (category == ProductCategory.ENDOWMENT && !paysMaturity) {
            fail("A with-profits ENDOWMENT needs a MATURITY payout, or its bonuses could never be paid at term end");
        }
    }

    private static void checkOwnScale(BonusPlan plan) {
        if (plan.surrenderRows().isEmpty()) {
            fail("OWN_SCALE needs at least one row of bonus surrender values");
        }
        Set<Integer> starts = new HashSet<>();
        for (BonusSurrenderRow row : plan.surrenderRows()) {
            if (row.fromCompletedYears() < 0) {
                fail("A bonus surrender row cannot start before year 0");
            }
            if (!starts.add(row.fromCompletedYears())) {
                fail("Bonus surrender rows must each start at a different completed year");
            }
            if (row.perMille() == null || row.perMille().signum() < 0 || row.perMille().compareTo(THOUSAND) > 0) {
                fail("A bonus surrender value must be between 0 and 1000 per mille");
            }
        }
    }

    private static void fail(String message) {
        throw new InvalidProductVersionException(message);
    }
}
```

Run: `./mvnw -o test -Dtest=BonusPlanValidatorTest` — Expected: PASS, 8 tests.

- [ ] **Step 5: The migration**

```sql
-- db-migrations/product/V20__bonus_terms.sql
-- Product step 4: a version may be with-profits.
--
-- SEPARATE TABLES, for V19's reason: a column the entity maps but a test database lacks breaks every
-- query on product_version. A row here exists only for a participating version; its absence IS
-- non-participating, which every version published before this step is.
CREATE TABLE product.version_bonus_terms (
    product_version_id   UUID PRIMARY KEY REFERENCES product.product_version(product_version_id),
    tenant_id            UUID NOT NULL,
    bonus_method         VARCHAR(10) NOT NULL CHECK (bonus_method IN ('SIMPLE','COMPOUND')),
    paid_up_participates BOOLEAN NOT NULL DEFAULT false,
    -- No default, on purpose (Q6): what bonuses add to a surrender is a contract term to be stated.
    surrender_basis      VARCHAR(20) NOT NULL CHECK (surrender_basis IN ('NONE','SUM_ASSURED_SCALE','OWN_SCALE'))
);

CREATE TABLE product.bonus_surrender_row (
    bonus_surrender_row_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL,
    product_version_id     UUID NOT NULL REFERENCES product.product_version(product_version_id),
    from_completed_years   INTEGER NOT NULL CHECK (from_completed_years >= 0),
    per_mille              NUMERIC(9,4) NOT NULL CHECK (per_mille BETWEEN 0 AND 1000)
);
CREATE UNIQUE INDEX ux_bonus_surrender_row_from ON product.bonus_surrender_row (product_version_id, from_completed_years);

ALTER TABLE product.version_bonus_terms ENABLE ROW LEVEL SECURITY;
CREATE POLICY version_bonus_terms_tenant_isolation ON product.version_bonus_terms
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.bonus_surrender_row ENABLE ROW LEVEL SECURITY;
CREATE POLICY bonus_surrender_row_tenant_isolation ON product.bonus_surrender_row
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON product.version_bonus_terms TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON product.bonus_surrender_row TO app_role;
```

- [ ] **Step 6: Entities, repositories, persistence and resolution**

```java
// product/domain/VersionBonusTerms.java
package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "version_bonus_terms", schema = "product")
public class VersionBonusTerms {
    @Id @Column(name = "product_version_id") private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "bonus_method", nullable = false) private String bonusMethod;
    @Column(name = "paid_up_participates", nullable = false) private boolean paidUpParticipates;
    @Column(name = "surrender_basis", nullable = false) private String surrenderBasis;

    protected VersionBonusTerms() {}

    public VersionBonusTerms(UUID tenantId, UUID productVersionId, String bonusMethod, boolean paidUpParticipates,
                             String surrenderBasis) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.bonusMethod = bonusMethod;
        this.paidUpParticipates = paidUpParticipates;
        this.surrenderBasis = surrenderBasis;
    }

    public String getBonusMethod() { return bonusMethod; }
    public boolean isPaidUpParticipates() { return paidUpParticipates; }
    public String getSurrenderBasis() { return surrenderBasis; }
}
```

```java
// product/domain/BonusSurrenderEntry.java
package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.product.api.BonusSurrenderRow;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "bonus_surrender_row", schema = "product")
public class BonusSurrenderEntry {
    @Id @UuidGenerator @Column(name = "bonus_surrender_row_id") private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "from_completed_years", nullable = false) private int fromCompletedYears;
    @Column(name = "per_mille", nullable = false) private BigDecimal perMille;

    protected BonusSurrenderEntry() {}

    public BonusSurrenderEntry(UUID tenantId, UUID productVersionId, BonusSurrenderRow row) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.fromCompletedYears = row.fromCompletedYears();
        this.perMille = row.perMille();
    }

    public BonusSurrenderRow toRow() { return new BonusSurrenderRow(fromCompletedYears, perMille); }
}
```

```java
// product/infrastructure/VersionBonusTermsRepository.java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.VersionBonusTerms;
import java.util.UUID;

public interface VersionBonusTermsRepository extends JpaRepository<VersionBonusTerms, UUID> {}
```

```java
// product/infrastructure/BonusSurrenderEntryRepository.java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.BonusSurrenderEntry;
import java.util.List;
import java.util.UUID;

public interface BonusSurrenderEntryRepository extends JpaRepository<BonusSurrenderEntry, UUID> {
    List<BonusSurrenderEntry> findByProductVersionIdOrderByFromCompletedYears(UUID productVersionId);
}
```

In `ProductApi`, add the 8th overload and the resolver:

```java
    /**
     * The fullest form (product step 4): also whether the version is WITH-PROFITS. Every other
     * overload delegates here with {@link BonusPlan#none()}.
     */
    void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                         List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                         List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                         TiraFiling tiraFiling, CashValuePlan cashValue, PayoutPlan payoutPlan,
                         AccumulationPlan accumulationPlan, BonusPlan bonusPlan, String publishedBy);

    /** A version's with-profits terms; {@link BonusPlan#none()} for a non-participating one. */
    BonusPlan resolveBonusPlan(UUID productVersionId);
```

In `ProductApiImpl`, the current 7th overload's body moves to the 8th. The 7th becomes a delegate:

```java
    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                                List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                                TiraFiling tiraFiling, CashValuePlan cashValue, PayoutPlan payoutPlan,
                                AccumulationPlan accumulationPlan, String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, baseRates, bounds, frequencyLoading, tiraFiling, cashValue, payoutPlan,
            accumulationPlan, BonusPlan.none(), publishedBy);
    }
```

In the moved body, after `PayoutPlanValidator.validate(category, payoutPlan, accumulationPlan);` add:

```java
        BonusPlanValidator.validate(category, bonusPlan, cashValue, accumulationPlan, payoutPlan);
```

and after `persistAccumulationPlan(...)`:

```java
        persistBonusPlan(tenantId, version.getProductVersionId(), bonusPlan);
```

```java
    private void persistBonusPlan(UUID tenantId, UUID productVersionId, BonusPlan plan) {
        if (plan == null || !plan.participating()) {
            return; // non-participating writes nothing -- its absence here IS that
        }
        versionBonusTermsRepository.save(new VersionBonusTerms(tenantId, productVersionId, plan.method().name(),
            plan.paidUpParticipates(), plan.surrenderBasis().name()));
        for (BonusSurrenderRow row : plan.surrenderRows()) {
            bonusSurrenderEntryRepository.save(new BonusSurrenderEntry(tenantId, productVersionId, row));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public BonusPlan resolveBonusPlan(UUID productVersionId) {
        return versionBonusTermsRepository.findById(productVersionId)
            .map(t -> new BonusPlan(true, BonusMethod.valueOf(t.getBonusMethod()), t.isPaidUpParticipates(),
                BonusSurrenderBasis.valueOf(t.getSurrenderBasis()),
                bonusSurrenderEntryRepository.findByProductVersionIdOrderByFromCompletedYears(productVersionId).stream()
                    .map(BonusSurrenderEntry::toRow).toList()))
            .orElse(BonusPlan.none());
    }
```

Inject both repositories through the constructor. In `ProductApiIntegrationTest`, the guard that counts `publishVersion` overloads goes from 7 to 8.

- [ ] **Step 7: REST and spec**

```java
// product/infrastructure/BonusRequest.java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.Valid;
import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * A with-profits version's terms on the wire. Absent means non-participating. method and
 * surrenderBasis are deliberately not @NotNull: the validator refuses their absence in the words the
 * console mirrors, and a bean-validation 400 would say something else.
 */
public record BonusRequest(BonusMethod method, boolean paidUpParticipates, BonusSurrenderBasis surrenderBasis,
                           @Valid List<SurrenderRow> surrenderRows) {

    public record SurrenderRow(int fromCompletedYears, BigDecimal perMille) {}

    public BonusPlan toPlan() {
        return new BonusPlan(true, method, paidUpParticipates, surrenderBasis,
            surrenderRows == null ? List.of() : surrenderRows.stream()
                .map(r -> new BonusSurrenderRow(r.fromCompletedYears(), r.perMille())).toList());
    }
}
```

Add `@Valid BonusRequest bonus` as the last component of `PublishVersionRequest`. In `ProductController`, after the accumulation argument, pass:

```java
            request.bonus() != null ? request.bonus().toPlan() : BonusPlan.none(),
```

In `openapi-product.yaml`, beside `accumulation` in the publish body:

```yaml
        bonus:
          description: >
            Present only on a with-profits version (product step 4). Refused on an account-valued
            version and on any category but ENDOWMENT and WHOLE_LIFE. surrenderBasis has no default:
            what attached bonuses add to a surrender is a contract term the version must state.
          type: [object, "null"]
          properties:
            method: { type: [string, "null"], enum: [SIMPLE, COMPOUND, null] }
            paidUpParticipates: { type: boolean, description: "Whether a policy paid-up on a valuation date still receives that declaration" }
            surrenderBasis: { type: [string, "null"], enum: [NONE, SUM_ASSURED_SCALE, OWN_SCALE, null] }
            surrenderRows:
              type: array
              items:
                type: object
                required: [fromCompletedYears, perMille]
                properties:
                  fromCompletedYears: { type: integer, minimum: 0 }
                  perMille: { type: number, minimum: 0, maximum: 1000 }
```

In `ProductContractTest`, add one test publishing an ENDOWMENT with `bonus` (`COMPOUND`, `NONE`) and a PERCENT_OF_SA MATURITY row (201). Add one with `bonus` on a `TERM_LIFE` product (422, `"A TERM_LIFE product cannot be with-profits"`). Copy the request-building helper the class already uses for its accumulation test.

- [ ] **Step 8: Test classes that now need V20**

```bash
grep -rl 'db-migrations/product/V19__accumulation_terms.sql' src/test/java | sort
```

For THIS task, add `V20` right after `V19` only in `ProductApiIntegrationTest`, `ProductContractTest` and `ProductVersionContractTest` (whichever the grep lists). Tasks 3, 5 and 6 extend it for the paths they reach.

- [ ] **Step 9: Run and commit**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='BonusPlanValidatorTest,ProductApiIntegrationTest,ProductContractTest,AccumulationPlanValidatorTest'
```

Expected: all green.

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/product src/test/java/tz/co/nlolo/lifeplatform/product db-migrations/product/V20__bonus_terms.sql api/openapi/openapi-product.yaml
git commit -m "feat(product): a version may be with-profits -- method, paid-up rule, an explicit bonus surrender basis"
```

---

### Task 2: The `bonus` module, its schema, and an append-only ledger

**Files:**
- Create: `db-migrations/bonus/V1__create_bonus_schema.sql`
- Create: `bonus/package-info.java`, `bonus/api/package-info.java`, `bonus/api/BonusEntryType.java`, `DeclarationStatus.java`, `OutcomeKind.java`, `ExitType.java`, `BonusStateException.java`
- Modify: `scripts/migrate.sh` (MODULES), `../.github/workflows/ci-cd.yml` (the `for mod in` loop)
- Test: `src/test/java/tz/co/nlolo/lifeplatform/bonus/BonusLedgerImmutabilityTest.java`

**Interfaces:**
- Produces: the schema in Step 1, the enums, and `BonusStateException extends RuntimeException`.

- [ ] **Step 1: The schema**

```sql
-- db-migrations/bonus/V1__create_bonus_schema.sql
-- Product step 4: with-profits bonuses. This module owns declarations, every attached bonus and
-- every movement in that balance, and its own record of each participating policy's status --
-- policy keeps none, and eligibility (Q3) is judged on the status at a PAST valuation date.
-- No foreign key into another module's tables: a policy number is carried as a value.
CREATE SCHEMA IF NOT EXISTS bonus;
GRANT USAGE ON SCHEMA bonus TO app_role;

CREATE TABLE bonus.declaration (
    declaration_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 UUID NOT NULL,
    product_id                UUID NOT NULL,
    valuation_date            DATE NOT NULL,
    reversionary_rate_percent NUMERIC(7,4) NOT NULL CHECK (reversionary_rate_percent BETWEEN 0 AND 100),
    -- A % OF ATTACHED BONUSES, so above 100 is a real declaration (a terminal bonus of 150%).
    terminal_rate_percent     NUMERIC(7,4) NOT NULL CHECK (terminal_rate_percent BETWEEN 0 AND 1000),
    status                    VARCHAR(10) NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED','APPROVED','WITHDRAWN')),
    proposed_by               VARCHAR(100) NOT NULL,
    proposed_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by               VARCHAR(100),
    approved_at               TIMESTAMPTZ,
    -- Set by the drain when every participating policy on the product has an outcome.
    completed_at              TIMESTAMPTZ,
    version                   BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT declaration_two_person CHECK (approved_by IS NULL OR approved_by <> proposed_by),
    CONSTRAINT declaration_approved_shape CHECK ((status = 'APPROVED') = (approved_by IS NOT NULL))
);
CREATE UNIQUE INDEX ux_declaration_valuation ON bonus.declaration (product_id, valuation_date) WHERE status = 'APPROVED';

-- One per PARTICIPATING policy, written on PolicyIssued. last_seq and attached_total are the
-- ledger's RUNNING HEAD, accumulation.account's shape: a copy kept in the same transaction as each
-- entry, which the follows trigger below stops from drifting.
CREATE TABLE bonus.participant (
    policy_number      VARCHAR(20) PRIMARY KEY,
    tenant_id          UUID NOT NULL,
    product_id         UUID NOT NULL,
    product_version_id UUID NOT NULL,
    currency           CHAR(3) NOT NULL DEFAULT 'TZS',
    issued_on          DATE NOT NULL,
    last_seq           INTEGER NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
    attached_total     NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (attached_total >= 0),
    version            BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_participant_product ON bonus.participant (product_id);

-- The status record. sum_assured is set only where the event states it (issue, paid-up); the sum
-- assured on a date is the latest non-null one at or before it. event_id makes a redelivery a no-op.
CREATE TABLE bonus.status_event (
    status_event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    event_id        UUID NOT NULL,
    policy_number   VARCHAR(20) NOT NULL,
    status          VARCHAR(25) NOT NULL,
    sum_assured     NUMERIC(19,2) CHECK (sum_assured IS NULL OR sum_assured >= 0),
    effective_at    TIMESTAMPTZ NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_status_event_event ON bonus.status_event (tenant_id, event_id);
CREATE INDEX ix_status_event_policy ON bonus.status_event (policy_number, effective_at);

-- One per (declaration, policy): attached, not eligible (with why), or nothing due. The unique
-- index is what lets the drain resume after a crash without attaching twice, and the reason is
-- what the Bonuses tab shows a person who asks why a policy got nothing.
CREATE TABLE bonus.declaration_outcome (
    outcome_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL,
    declaration_id UUID NOT NULL REFERENCES bonus.declaration(declaration_id),
    policy_number  VARCHAR(20) NOT NULL,
    outcome        VARCHAR(15) NOT NULL CHECK (outcome IN ('ATTACHED','NOT_ELIGIBLE','NOTHING_DUE')),
    reason         VARCHAR(300),
    decided_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT outcome_reason_shape CHECK ((outcome = 'NOT_ELIGIBLE') = (reason IS NOT NULL))
);
CREATE UNIQUE INDEX ux_declaration_outcome ON bonus.declaration_outcome (declaration_id, policy_number);

CREATE TABLE bonus.attachment_entry (
    entry_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    policy_number     VARCHAR(20) NOT NULL,
    seq               INTEGER NOT NULL CHECK (seq >= 1),
    entry_type        VARCHAR(15) NOT NULL CHECK (entry_type IN ('REVERSIONARY','REVERSAL')),
    amount            NUMERIC(19,2) NOT NULL,
    total_after       NUMERIC(19,2) NOT NULL CHECK (total_after >= 0),
    -- The valuation date of the declaration it came from; a reversal's own date.
    effective_date    DATE NOT NULL,
    declaration_id    UUID REFERENCES bonus.declaration(declaration_id),
    basis_amount      NUMERIC(19,2),
    rate_percent      NUMERIC(7,4),
    source_type       VARCHAR(20) NOT NULL,
    source_ref        VARCHAR(100) NOT NULL,
    reverses_entry_id UUID REFERENCES bonus.attachment_entry(entry_id),
    reason            VARCHAR(300),
    created_by        VARCHAR(100) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT attachment_reversal_shape CHECK ((entry_type = 'REVERSAL') = (reverses_entry_id IS NOT NULL)),
    CONSTRAINT attachment_reversionary_shape CHECK (entry_type <> 'REVERSIONARY'
        OR (declaration_id IS NOT NULL AND basis_amount IS NOT NULL AND rate_percent IS NOT NULL AND amount > 0))
);
-- ux_attachment_source IS the once-only guarantee (the user's third rule).
CREATE UNIQUE INDEX ux_attachment_source ON bonus.attachment_entry (tenant_id, source_type, source_ref);
CREATE UNIQUE INDEX ux_attachment_seq ON bonus.attachment_entry (policy_number, seq);
CREATE UNIQUE INDEX ux_attachment_reversed_once ON bonus.attachment_entry (reverses_entry_id) WHERE reverses_entry_id IS NOT NULL;
CREATE INDEX ix_attachment_policy_date ON bonus.attachment_entry (policy_number, effective_date);

CREATE TABLE bonus.settlement (
    settlement_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    policy_number         VARCHAR(20) NOT NULL,
    exit_type             VARCHAR(10) NOT NULL CHECK (exit_type IN ('MATURITY','DEATH')),
    exit_ref              VARCHAR(100) NOT NULL,
    exit_date             DATE NOT NULL,
    attached_amount       NUMERIC(19,2) NOT NULL CHECK (attached_amount >= 0),
    interim_amount        NUMERIC(19,2) NOT NULL CHECK (interim_amount >= 0),
    terminal_amount       NUMERIC(19,2) NOT NULL CHECK (terminal_amount >= 0),
    interim_rate_percent  NUMERIC(7,4),
    terminal_rate_percent NUMERIC(7,4),
    declaration_id        UUID REFERENCES bonus.declaration(declaration_id),
    recorded_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_settlement_exit ON bonus.settlement (tenant_id, exit_type, exit_ref);

-- IMMUTABILITY, twice, accumulation's arrangement: grants bind app_role, this trigger binds the
-- owner every migration and test connects as.
CREATE OR REPLACE FUNCTION bonus.refuse_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'bonus.% is append-only: correct it with a reversing entry, never an %', TG_TABLE_NAME, TG_OP;
END $$;
CREATE TRIGGER attachment_entry_append_only BEFORE UPDATE OR DELETE ON bonus.attachment_entry
    FOR EACH ROW EXECUTE FUNCTION bonus.refuse_mutation();
CREATE TRIGGER declaration_outcome_append_only BEFORE UPDATE OR DELETE ON bonus.declaration_outcome
    FOR EACH ROW EXECUTE FUNCTION bonus.refuse_mutation();
CREATE TRIGGER status_event_append_only BEFORE UPDATE OR DELETE ON bonus.status_event
    FOR EACH ROW EXECUTE FUNCTION bonus.refuse_mutation();
CREATE TRIGGER settlement_append_only BEFORE UPDATE OR DELETE ON bonus.settlement
    FOR EACH ROW EXECUTE FUNCTION bonus.refuse_mutation();

CREATE OR REPLACE FUNCTION bonus.check_entry_follows() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous NUMERIC(19,2);
BEGIN
    IF NEW.seq = 1 THEN
        previous := 0;
    ELSE
        SELECT total_after INTO previous FROM bonus.attachment_entry
         WHERE policy_number = NEW.policy_number AND seq = NEW.seq - 1;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'bonus entry % for policy % has no entry % before it', NEW.seq, NEW.policy_number, NEW.seq - 1;
        END IF;
    END IF;
    IF NEW.total_after <> previous + NEW.amount THEN
        RAISE EXCEPTION 'bonus entry % for policy %: total_after % is not % + %',
            NEW.seq, NEW.policy_number, NEW.total_after, previous, NEW.amount;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER attachment_entry_follows BEFORE INSERT ON bonus.attachment_entry
    FOR EACH ROW EXECUTE FUNCTION bonus.check_entry_follows();

-- The drain's cross-tenant selector: ids only.
CREATE OR REPLACE FUNCTION bonus.declarations_due()
RETURNS TABLE (declaration_id UUID, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT d.declaration_id, d.tenant_id FROM bonus.declaration d
     WHERE d.status = 'APPROVED' AND d.completed_at IS NULL AND d.valuation_date <= current_date
     ORDER BY d.valuation_date
     LIMIT 50;
$$;
REVOKE EXECUTE ON FUNCTION bonus.declarations_due() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION bonus.declarations_due() TO app_role;

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['declaration','participant','status_event','declaration_outcome',
                             'attachment_entry','settlement'] LOOP
        EXECUTE format('ALTER TABLE bonus.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON bonus.%I USING (tenant_id = '
            'NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)', t || '_tenant_isolation', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE ON bonus.declaration, bonus.participant TO app_role;
-- The record itself: read and append, nothing else.
GRANT SELECT, INSERT ON bonus.status_event, bonus.declaration_outcome, bonus.attachment_entry, bonus.settlement TO app_role;
```

- [ ] **Step 2: Register the module everywhere a module is listed**

`MigrationScriptCoverageTest` fails the moment `db-migrations/bonus` exists and isn't in both lists, so this belongs to this task:
- In `scripts/migrate.sh`, `MODULES` ends `... refdata benefitpayout accumulation bonus`.
- In `.github/workflows/ci-cd.yml`, the `for mod in ...; do` loop ends the same way.

- [ ] **Step 3: The package and the enums**

```java
// bonus/package-info.java
/**
 * With-profits bonuses (product step 4): declarations, the attached-bonus ledger, exit settlements,
 * and this module's own status record for every participating policy.
 *
 * <p>policy::api for the projection it restates and the policy it reads; product::api for the
 * version's terms. Claims is read by envelope only, never depended on.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "policy::api", "product::api" })
package tz.co.nlolo.lifeplatform.bonus;
```

```java
// bonus/api/package-info.java
@org.springframework.modulith.NamedInterface("api")
package tz.co.nlolo.lifeplatform.bonus.api;
```

```java
// bonus/api/BonusEntryType.java
package tz.co.nlolo.lifeplatform.bonus.api;
public enum BonusEntryType { REVERSIONARY, REVERSAL }
```

```java
// bonus/api/DeclarationStatus.java
package tz.co.nlolo.lifeplatform.bonus.api;
public enum DeclarationStatus { PROPOSED, APPROVED, WITHDRAWN }
```

```java
// bonus/api/OutcomeKind.java
package tz.co.nlolo.lifeplatform.bonus.api;
public enum OutcomeKind { ATTACHED, NOT_ELIGIBLE, NOTHING_DUE }
```

```java
// bonus/api/ExitType.java
package tz.co.nlolo.lifeplatform.bonus.api;
public enum ExitType { MATURITY, DEATH }
```

```java
// bonus/api/BonusStateException.java
package tz.co.nlolo.lifeplatform.bonus.api;

/** A rule refused the request. 422 BONUS_REFUSED, shown verbatim by the console. */
public class BonusStateException extends RuntimeException {
    public BonusStateException(String message) { super(message); }
}
```

- [ ] **Step 4: The database's guarantees, tested with plain JDBC**

This copies `accumulation/LedgerImmutabilityTest`'s shape exactly: same container, same `app_role` login, same `owner()`/`appRole()` helpers.

```java
// src/test/java/tz/co/nlolo/lifeplatform/bonus/BonusLedgerImmutabilityTest.java
package tz.co.nlolo.lifeplatform.bonus;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * The bonus record cannot be edited or deleted, cannot be written in a shape that does not add up,
 * and cannot attach one declaration twice -- all the database's guarantee, so no Java is involved.
 */
@Testcontainers
class BonusLedgerImmutabilityTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final String APP_PASSWORD = "bonus_immutability_password";
    private static final UUID TENANT = UUID.randomUUID();
    private static final String DECL = "00000000-0000-0000-0000-0000000000d1";

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/bonus/V1__create_bonus_schema.sql");
        try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + APP_PASSWORD + "'");
            s.execute("INSERT INTO bonus.declaration (declaration_id, tenant_id, product_id, valuation_date, "
                + "reversionary_rate_percent, terminal_rate_percent, status, proposed_by, approved_by) VALUES ('" + DECL
                + "', '" + TENANT + "', gen_random_uuid(), current_date, 3, 50, 'APPROVED', 'a', 'b')");
            s.execute(entry("POL-B1", 1, "REVERSIONARY", "30000.00", "30000.00", "declaration:" + DECL + ":POL-B1", null));
            // A second policy of its own for the reversal test, so no test changes another's seq 2.
            s.execute(entry("POL-B9", 1, "REVERSIONARY", "30000.00", "30000.00", "declaration:" + DECL + ":POL-B9", null));
            s.execute("INSERT INTO bonus.declaration_outcome (tenant_id, declaration_id, policy_number, outcome) "
                + "VALUES ('" + TENANT + "', '" + DECL + "', 'POL-B1', 'ATTACHED')");
        }
    }

    private static String entry(String policy, int seq, String type, String amount, String totalAfter, String ref,
                                String reversesSeq) {
        boolean reversionary = type.equals("REVERSIONARY");
        return "INSERT INTO bonus.attachment_entry (tenant_id, policy_number, seq, entry_type, amount, total_after, "
            + "effective_date, declaration_id, basis_amount, rate_percent, source_type, source_ref, reverses_entry_id, created_by) "
            + "VALUES ('" + TENANT + "', '" + policy + "', " + seq + ", '" + type + "', " + amount + ", " + totalAfter
            + ", current_date, " + (reversionary ? "'" + DECL + "', 1000000, 3" : "NULL, NULL, NULL") + ", '"
            + (reversionary ? "declaration" : "reversal") + "', '" + ref + "', "
            + (reversesSeq == null ? "NULL" : "(SELECT entry_id FROM bonus.attachment_entry WHERE policy_number = '"
                + policy + "' AND seq = " + reversesSeq + ")")
            + ", 'test')";
    }

    private static Connection owner() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection appRole(UUID tenant) throws Exception {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_role", APP_PASSWORD);
        try (Statement s = c.createStatement()) {
            s.execute("SET app.current_tenant_id = '" + tenant + "'");
        }
        return c;
    }

    private static void ownerRun(String sql) throws Exception {
        try (Connection c = owner(); Statement s = c.createStatement()) { s.execute(sql); }
    }

    @Test
    void appRoleCannotUpdateOrDeleteAnEntryOrAnOutcome() {
        assertThatThrownBy(() -> { try (Connection c = appRole(TENANT); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE bonus.attachment_entry SET amount = 1"); } }).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> { try (Connection c = appRole(TENANT); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM bonus.declaration_outcome"); } }).hasMessageContaining("permission denied");
    }

    @Test
    void evenTheOwnerCannotUpdateOrDeleteTheRecord() {
        for (String sql : List.of("UPDATE bonus.attachment_entry SET amount = 1", "DELETE FROM bonus.declaration_outcome",
                "DELETE FROM bonus.status_event", "UPDATE bonus.settlement SET terminal_amount = 0")) {
            assertThatThrownBy(() -> ownerRun(sql)).as(sql).hasMessageContaining("append-only");
        }
    }

    @Test
    void anEntryWhoseTotalDoesNotFollowIsRefused() {
        assertThatThrownBy(() -> ownerRun(entry("POL-B1", 2, "REVERSIONARY", "1000.00", "999.00", "declaration:x:POL-B1", null)))
            .hasMessageContaining("is not 30000.00 + 1000.00");
    }

    @Test
    void aGapInTheSequenceIsRefused() {
        assertThatThrownBy(() -> ownerRun(entry("POL-B1", 5, "REVERSIONARY", "1000.00", "31000.00", "declaration:y:POL-B1", null)))
            .hasMessageContaining("has no entry 4 before it");
    }

    @Test
    void aDeclarationAttachesToAPolicyOnce() {
        assertThatThrownBy(() -> ownerRun(entry("POL-B1", 2, "REVERSIONARY", "1000.00", "31000.00", "declaration:" + DECL + ":POL-B1", null)))
            .hasMessageContaining("ux_attachment_source");
        assertThatThrownBy(() -> ownerRun("INSERT INTO bonus.declaration_outcome (tenant_id, declaration_id, policy_number, outcome) "
            + "VALUES ('" + TENANT + "', '" + DECL + "', 'POL-B1', 'NOTHING_DUE')")).hasMessageContaining("ux_declaration_outcome");
    }

    @Test
    void aNotEligibleOutcomeMustSayWhy() {
        assertThatThrownBy(() -> ownerRun("INSERT INTO bonus.declaration_outcome (tenant_id, declaration_id, policy_number, outcome) "
            + "VALUES ('" + TENANT + "', '" + DECL + "', 'POL-B2', 'NOT_ELIGIBLE')")).hasMessageContaining("outcome_reason_shape");
    }

    @Test
    void theProposerCannotApprove() {
        assertThatThrownBy(() -> ownerRun("INSERT INTO bonus.declaration (tenant_id, product_id, valuation_date, "
            + "reversionary_rate_percent, terminal_rate_percent, status, proposed_by, approved_by) VALUES ('" + TENANT
            + "', gen_random_uuid(), current_date, 3, 0, 'APPROVED', 'same', 'same')")).hasMessageContaining("declaration_two_person");
    }

    @Test
    void everyBonusTableHasRowLevelSecurity() throws Exception {
        try (Connection c = owner(); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                 + "WHERE n.nspname = 'bonus' AND c.relkind = 'r' AND NOT c.relrowsecurity")) {
            List<String> unprotected = new ArrayList<>();
            while (rs.next()) unprotected.add(rs.getString(1));
            assertThat(unprotected).as("bonus tables without RLS").isEmpty();
        }
    }

    @Test
    void anotherTenantCannotSeeTheLedger() throws Exception {
        try (Connection c = appRole(UUID.randomUUID()); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT count(*) FROM bonus.attachment_entry")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
        // The control: the owning tenant does see it, so the zero is isolation, not an empty table.
        try (Connection c = appRole(TENANT); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT count(*) FROM bonus.attachment_entry")) {
            rs.next();
            assertThat(rs.getInt(1)).isPositive();
        }
    }

    @Test
    void aReversalThatFollowsIsAccepted() {
        assertThatCode(() -> ownerRun(entry("POL-B9", 2, "REVERSAL", "-30000.00", "0.00", "reversal:POL-B9:1", "1")))
            .doesNotThrowAnyException();
    }
}
```

No test writes a second POL-B1 entry that succeeds, so POL-B1's seq 2 stays free whatever order JUnit runs the methods in. Every refusal is therefore the one its test names.

- [ ] **Step 5: Run and commit**

```bash
./mvnw -o test -Dtest='BonusLedgerImmutabilityTest,MigrationScriptCoverageTest'
```

Expected: 10 + 2 green.

```bash
git add db-migrations/bonus src/main/java/tz/co/nlolo/lifeplatform/bonus src/test/java/tz/co/nlolo/lifeplatform/bonus scripts/migrate.sh ../.github/workflows/ci-cd.yml
git commit -m "feat(bonus): the bonus schema -- an append-only, once-only attachment ledger, enforced by the database"
```

---

### Task 3: The status record and the eligibility rule

**Files:**
- Create: `bonus/domain/Participant.java`, `StatusEvent.java`, `Eligibility.java`
- Create: `bonus/infrastructure/ParticipantRepository.java`, `StatusEventRepository.java`
- Create: `bonus/application/BonusEnvelopeRunner.java`, `PolicyEventListener.java`, `StatusRecorder.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/bonus/EligibilityTest.java`, `StatusRecordIntegrationTest.java`, `BonusTestFixtures.java`

**Interfaces:**
- Consumes: `ProductApi.resolveBonusPlan` (Task 1).
- Produces: `Eligibility.refusal(List<StatusRow> rows, LocalDate date, boolean paidUpParticipates) -> Optional<String>` (empty = eligible); `Eligibility.sumAssuredOn(List<StatusRow>, LocalDate) -> BigDecimal`; `record Eligibility.StatusRow(String status, BigDecimal sumAssured, Instant effectiveAt)`; `Eligibility.CIVIL_ZONE`; `StatusRecorder.record(DomainEventEnvelope<?>)`; `ParticipantRepository.lockForPosting(String)`; `StatusEventRepository.findByPolicyNumberOrderByEffectiveAtAsc(String)`.

- [ ] **Step 1: The rule, test first**

```java
// src/test/java/tz/co/nlolo/lifeplatform/bonus/EligibilityTest.java
package tz.co.nlolo.lifeplatform.bonus;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.bonus.domain.Eligibility;
import tz.co.nlolo.lifeplatform.bonus.domain.Eligibility.StatusRow;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Q3 and Q7, on a status record: never "in force", always the status on the valuation date. */
class EligibilityTest {

    private static final LocalDate VALUATION = LocalDate.of(2026, 12, 31);

    /** A moment on a civil day in Dar es Salaam. */
    private static Instant at(LocalDate day, int hour) {
        return day.atTime(LocalTime.of(hour, 0)).atZone(Eligibility.CIVIL_ZONE).toInstant();
    }

    private static StatusRow row(String status, LocalDate day) {
        return new StatusRow(status, null, at(day, 12));
    }

    private static final StatusRow ISSUED = new StatusRow("ACTIVE", new BigDecimal("1000000"), at(LocalDate.of(2026, 1, 10), 9));

    @Test
    void activeOrReinstatedOrSuspendedOnTheValuationDateIsEligible() {
        assertThat(Eligibility.refusal(List.of(ISSUED), VALUATION, false)).isEmpty();
        assertThat(Eligibility.refusal(List.of(ISSUED, row("SUSPENDED", LocalDate.of(2026, 6, 1))), VALUATION, false)).isEmpty();
    }

    @Test
    void notYetIssuedIsNotEligible() {
        assertThat(Eligibility.refusal(List.of(new StatusRow("ACTIVE", BigDecimal.TEN, at(LocalDate.of(2027, 1, 1), 9))),
            VALUATION, false)).contains("The policy was not yet issued on 2026-12-31");
    }

    @Test
    void lapsedOnTheValuationDateGetsNothingNewButAnEarlierLapseCuredIsFine() {
        assertThat(Eligibility.refusal(List.of(ISSUED, row("LAPSED", LocalDate.of(2026, 11, 1))), VALUATION, false))
            .contains("The policy was LAPSED on 2026-12-31");
        assertThat(Eligibility.refusal(List.of(ISSUED, row("LAPSED", LocalDate.of(2026, 3, 1)),
            row("REINSTATED", LocalDate.of(2026, 5, 1))), VALUATION, false)).isEmpty();
    }

    @Test
    void reinstatedIsEligibleFromTheFirstValuationDateOnOrAfterReinstatement() {
        List<StatusRow> rows = List.of(ISSUED, row("LAPSED", LocalDate.of(2026, 11, 1)), row("REINSTATED", VALUATION));
        // Reinstated ON the valuation date: the status at the END of that day is REINSTATED.
        assertThat(Eligibility.refusal(rows, VALUATION, false)).isEmpty();
        // The day before, it was still lapsed.
        assertThat(Eligibility.refusal(rows, VALUATION.minusDays(1), false)).contains("The policy was LAPSED on 2026-12-30");
    }

    @Test
    void paidUpFollowsTheVersionsContractRule() {
        List<StatusRow> rows = List.of(ISSUED, new StatusRow("PAID_UP", new BigDecimal("400000"), at(LocalDate.of(2026, 8, 1), 12)));
        assertThat(Eligibility.refusal(rows, VALUATION, false))
            .contains("The policy was paid-up on 2026-12-31, and this version's paid-up policies receive no new bonuses");
        assertThat(Eligibility.refusal(rows, VALUATION, true)).isEmpty();
        assertThat(Eligibility.sumAssuredOn(rows, VALUATION)).isEqualByComparingTo("400000");
        assertThat(Eligibility.sumAssuredOn(rows, LocalDate.of(2026, 7, 31))).isEqualByComparingTo("1000000");
    }

    @Test
    void proposedOrClosedIsNotEligible() {
        assertThat(Eligibility.refusal(List.of(new StatusRow("PROPOSED", BigDecimal.TEN, at(LocalDate.of(2026, 1, 10), 9))),
            VALUATION, false)).contains("The policy was PROPOSED on 2026-12-31");
        assertThat(Eligibility.refusal(List.of(ISSUED, row("SURRENDERED", LocalDate.of(2026, 10, 1))), VALUATION, false))
            .contains("The policy was SURRENDERED on 2026-12-31");
    }

    @Test
    void theCivilDayDecidesNotUtc() {
        // 01:00 on 1 January in Dar es Salaam is still 31 December in UTC. A lapse then is NOT on the
        // 31st in civil time, so the policy was still active at the end of the valuation date.
        StatusRow earlyLapse = new StatusRow("LAPSED", null, at(VALUATION.plusDays(1), 1));
        assertThat(Eligibility.refusal(List.of(ISSUED, earlyLapse), VALUATION, false)).isEmpty();
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -o test -Dtest=EligibilityTest` — Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement the rule**

```java
// bonus/domain/Eligibility.java
package tz.co.nlolo.lifeplatform.bonus.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Whether a policy receives a declaration (product step 4, Q3 and Q7). Pure: given the status record
 * and a date, it never asks policy anything.
 *
 * <p>Deliberately NOT "in force". The user's rule: eligibility is its own rule, and whether a paid-up
 * policy still receives bonuses is the version's contract term. A suspended policy is still on the
 * books and still participates; a lapsed one keeps what it has and gets nothing new.
 */
public final class Eligibility {

    /** The civil day every valuation date is written in. A UTC day misdates 00:00-03:00 local. */
    public static final ZoneId CIVIL_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private static final Set<String> PARTICIPATING = Set.of("ACTIVE", "REINSTATED", "SUSPENDED");

    public record StatusRow(String status, BigDecimal sumAssured, Instant effectiveAt) {}

    private Eligibility() {}

    /** Why this policy gets nothing on that date -- or empty, which means it is eligible. */
    public static Optional<String> refusal(List<StatusRow> rows, LocalDate date, boolean paidUpParticipates) {
        List<StatusRow> before = asOf(rows, date);
        if (before.isEmpty()) {
            return Optional.of("The policy was not yet issued on " + date);
        }
        String status = before.get(before.size() - 1).status();
        if (PARTICIPATING.contains(status)) {
            return Optional.empty();
        }
        if ("PAID_UP".equals(status)) {
            return paidUpParticipates ? Optional.empty()
                : Optional.of("The policy was paid-up on " + date + ", and this version's paid-up policies receive no new bonuses");
        }
        return Optional.of("The policy was " + status + " on " + date);
    }

    /** The sum assured in force on that date: the latest stated at or before the end of it. */
    public static BigDecimal sumAssuredOn(List<StatusRow> rows, LocalDate date) {
        return asOf(rows, date).stream().map(StatusRow::sumAssured).filter(java.util.Objects::nonNull)
            .reduce((first, second) -> second)
            .orElseThrow(() -> new IllegalStateException("No sum assured is recorded on or before " + date));
    }

    /** Every row effective before the start of the NEXT civil day, oldest first. */
    private static List<StatusRow> asOf(List<StatusRow> rows, LocalDate date) {
        Instant cutoff = date.plusDays(1).atStartOfDay(CIVIL_ZONE).toInstant();
        return rows.stream().filter(r -> r.effectiveAt().isBefore(cutoff))
            .sorted(Comparator.comparing(StatusRow::effectiveAt)).toList();
    }
}
```

Run: `./mvnw -o test -Dtest=EligibilityTest` — Expected: PASS, 7 tests.

- [ ] **Step 4: Entities and repositories**

```java
// bonus/domain/Participant.java
package tz.co.nlolo.lifeplatform.bonus.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One participating policy, and the RUNNING HEAD of its ledger. @Version serialises entries. */
@Entity
@Table(name = "participant", schema = "bonus")
public class Participant {
    @Id @Column(name = "policy_number") private String policyNumber;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(nullable = false) private String currency;
    @Column(name = "issued_on", nullable = false) private LocalDate issuedOn;
    @Column(name = "last_seq", nullable = false) private int lastSeq;
    @Column(name = "attached_total", nullable = false) private BigDecimal attachedTotal = BigDecimal.ZERO;
    @Version private long version;

    protected Participant() {}

    public Participant(UUID tenantId, String policyNumber, UUID productId, UUID productVersionId, String currency,
                       LocalDate issuedOn) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.productId = productId;
        this.productVersionId = productVersionId;
        this.currency = currency;
        this.issuedOn = issuedOn;
    }

    /** Move the head by one entry; returns that entry's seq. The trigger checks the arithmetic. */
    public int advance(BigDecimal amount) {
        this.attachedTotal = attachedTotal.add(amount);
        return ++lastSeq;
    }

    public String getPolicyNumber() { return policyNumber; }
    public UUID getTenantId() { return tenantId; }
    public UUID getProductId() { return productId; }
    public UUID getProductVersionId() { return productVersionId; }
    public String getCurrency() { return currency; }
    public LocalDate getIssuedOn() { return issuedOn; }
    public BigDecimal getAttachedTotal() { return attachedTotal; }
}
```

```java
// bonus/domain/StatusEvent.java
package tz.co.nlolo.lifeplatform.bonus.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "status_event", schema = "bonus")
public class StatusEvent {
    @Id @UuidGenerator @Column(name = "status_event_id") private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "event_id", nullable = false) private UUID eventId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private String status;
    @Column(name = "sum_assured") private BigDecimal sumAssured;
    @Column(name = "effective_at", nullable = false) private Instant effectiveAt;

    protected StatusEvent() {}

    public StatusEvent(UUID tenantId, UUID eventId, String policyNumber, String status, BigDecimal sumAssured, Instant effectiveAt) {
        this.tenantId = tenantId;
        this.eventId = eventId;
        this.policyNumber = policyNumber;
        this.status = status;
        this.sumAssured = sumAssured;
        this.effectiveAt = effectiveAt;
    }

    public Eligibility.StatusRow toRow() { return new Eligibility.StatusRow(status, sumAssured, effectiveAt); }
    public String getStatus() { return status; }
    public Instant getEffectiveAt() { return effectiveAt; }
}
```

```java
// bonus/infrastructure/ParticipantRepository.java
package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import tz.co.nlolo.lifeplatform.bonus.domain.Participant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParticipantRepository extends JpaRepository<Participant, String> {

    /** The head, locked for the entry about to be written -- accumulation's lockForPosting. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Participant p where p.policyNumber = :policyNumber")
    Optional<Participant> lockForPosting(String policyNumber);

    /** The drain's next batch: participants on the product with no outcome for this declaration yet. */
    @Query(value = "SELECT p.policy_number FROM bonus.participant p WHERE p.product_id = :productId "
        + "AND NOT EXISTS (SELECT 1 FROM bonus.declaration_outcome o WHERE o.declaration_id = :declarationId "
        + "AND o.policy_number = p.policy_number) ORDER BY p.policy_number LIMIT 200", nativeQuery = true)
    List<String> awaitingOutcome(UUID productId, UUID declarationId);
}
```

```java
// bonus/infrastructure/StatusEventRepository.java
package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.bonus.domain.StatusEvent;
import java.util.List;
import java.util.UUID;

public interface StatusEventRepository extends JpaRepository<StatusEvent, UUID> {
    boolean existsByTenantIdAndEventId(UUID tenantId, UUID eventId);
    List<StatusEvent> findByPolicyNumberOrderByEffectiveAtAsc(String policyNumber);
}
```

- [ ] **Step 5: The runner, the recorder and the listener**

```java
// bonus/application/BonusEnvelopeRunner.java
package tz.co.nlolo.lifeplatform.bonus.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Under the envelope's tenant, in its own transaction, never rethrowing -- accumulation's shape. A
 * lost race on a once-only index is the guarantee working, so it is INFO, not ERROR.
 */
@Component("bonusEnvelopeRunner")
class BonusEnvelopeRunner {

    private static final Logger log = LoggerFactory.getLogger(BonusEnvelopeRunner.class);

    private final TransactionTemplate requiresNew;

    BonusEnvelopeRunner(PlatformTransactionManager transactionManager) {
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    void run(DomainEventEnvelope<?> envelope, Consumer<DomainEventEnvelope<?>> handler) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            requiresNew.executeWithoutResult(status -> handler.accept(envelope));
        } catch (DataIntegrityViolationException e) {
            String cause = String.valueOf(e.getMostSpecificCause().getMessage());
            if (cause.contains("ux_status_event_event") || cause.contains("ux_attachment_source") || cause.contains("ux_settlement_exit")) {
                log.info("bonus dropped a concurrent duplicate of {} for tenant {}", envelope.eventType(), envelope.tenantId());
            } else {
                log.error("bonus failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
            }
        } catch (Exception e) {
            log.error("bonus failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
```

```java
// bonus/application/StatusRecorder.java
package tz.co.nlolo.lifeplatform.bonus.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.bonus.domain.Participant;
import tz.co.nlolo.lifeplatform.bonus.domain.StatusEvent;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.ParticipantRepository;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.StatusEventRepository;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Turns policy's lifecycle events into this module's status record -- for participating policies
 * only. A policy on a non-participating version has no participant row, and every later event for
 * it is ignored, so the record never grows with policies that can never receive a bonus.
 *
 * <p>effective_at is the envelope's occurredAt. Every event is published in the transaction that
 * made the change, so that IS when the status changed.
 */
@Service
public class StatusRecorder {

    /** Event type -> the status the policy is in afterwards (policy.domain.Policy's own strings). */
    static final Map<String, String> STATUS_AFTER = Map.ofEntries(
        Map.entry("policy.PolicyActivated", "ACTIVE"),
        Map.entry("policy.PolicySuspended", "SUSPENDED"),
        Map.entry("policy.PolicyResumed", "ACTIVE"),
        Map.entry("policy.PolicyLapsed", "LAPSED"),
        Map.entry("policy.PolicyReinstated", "REINSTATED"),
        Map.entry("policy.PolicyMadePaidUp", "PAID_UP"),
        Map.entry("policy.PolicySurrendered", "SURRENDERED"),
        Map.entry("policy.PolicyMatured", "MATURED"),
        Map.entry("policy.PolicyExpired", "EXPIRED"),
        Map.entry("policy.PolicyCancelledFreeLook", "CANCELLED_FREE_LOOK"));

    private final ParticipantRepository participants;
    private final StatusEventRepository events;
    private final ProductApi productApi;

    public StatusRecorder(ParticipantRepository participants, StatusEventRepository events, ProductApi productApi) {
        this.participants = participants;
        this.events = events;
        this.productApi = productApi;
    }

    @Transactional
    public void record(DomainEventEnvelope<?> envelope) {
        UUID tenantId = TenantContext.get();
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) envelope.payload();
        String policyNumber = (String) p.get("policyNumber");
        if (events.existsByTenantIdAndEventId(tenantId, envelope.eventId())) {
            return; // a redelivery; ux_status_event_event is the guarantee behind this fast path
        }
        if ("policy.PolicyIssued".equals(envelope.eventType())) {
            UUID versionId = (UUID) p.get("productVersionId");
            if (!productApi.resolveBonusPlan(versionId).participating() || participants.existsById(policyNumber)) {
                return;
            }
            Map<String, Object> sa = money(p, "sumAssured");
            participants.save(new Participant(tenantId, policyNumber, (UUID) p.get("productId"), versionId,
                (String) sa.get("currencyCode"), LocalDate.parse((String) p.get("issueDate"))));
            events.save(new StatusEvent(tenantId, envelope.eventId(), policyNumber, (String) p.get("status"),
                new BigDecimal((String) sa.get("amount")), envelope.occurredAt()));
            return;
        }
        String status = STATUS_AFTER.get(envelope.eventType());
        if (status == null || !participants.existsById(policyNumber)) {
            return;
        }
        BigDecimal sumAssured = "PAID_UP".equals(status)
            ? new BigDecimal((String) money(p, "paidUpSumAssured").get("amount")) : null;
        events.save(new StatusEvent(tenantId, envelope.eventId(), policyNumber, status, sumAssured, envelope.occurredAt()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> money(Map<String, Object> payload, String key) {
        return (Map<String, Object>) payload.get(key);
    }
}
```

```java
// bonus/application/PolicyEventListener.java
package tz.co.nlolo.lifeplatform.bonus.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

/** Policy's lifecycle, into the status record. Task 6 adds the free-look reversal. */
@Component("bonusPolicyEventListener")
public class PolicyEventListener {

    private final StatusRecorder recorder;
    private final BonusEnvelopeRunner runner;

    public PolicyEventListener(StatusRecorder recorder, BonusEnvelopeRunner runner) {
        this.recorder = recorder;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        String type = envelope.eventType();
        if ("policy.PolicyIssued".equals(type) || StatusRecorder.STATUS_AFTER.containsKey(type)) {
            runner.run(envelope, recorder::record);
        }
    }
}
```

**Before writing `StatusRecorder`, confirm:** `PolicyIssued`'s `productVersionId` and `productId` are `UUID` objects in the in-process payload. accumulation's listener casts `(UUID) p.get("productVersionId")`, which says yes. And the `status` key is `PROPOSED` or `ACTIVE`; PolicyApiImpl:322 says yes.

- [ ] **Step 6: Fixtures and the wiring test**

```java
// src/test/java/tz/co/nlolo/lifeplatform/bonus/BonusTestFixtures.java
package tz.co.nlolo.lifeplatform.bonus;

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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

/** A real with-profits endowment and a real in-force policy on it -- AccumulationTestFixtures' shape. */
@TestComponent
public class BonusTestFixtures {

    public static final BonusPlan COMPOUND_NONE = new BonusPlan(true, BonusMethod.COMPOUND, false, BonusSurrenderBasis.NONE, List.of());
    public static final BonusPlan SIMPLE_NONE = new BonusPlan(true, BonusMethod.SIMPLE, false, BonusSurrenderBasis.NONE, List.of());

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final PartyApi partyApi;
    private final ProductApi productApi;
    private final PolicyApi policyApi;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate tx;

    public BonusTestFixtures(PartyApi partyApi, ProductApi productApi, PolicyApi policyApi,
                             ApplicationEventPublisher publisher, PlatformTransactionManager transactionManager) {
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.policyApi = policyApi;
        this.publisher = publisher;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record Issued(String policyNumber, UUID productId, UUID productVersionId) {}

    /** A 1,000,000 with-profits endowment, ACTIVE, issued today. cashValue may be CashValuePlan.none(). */
    public Issued issue(UUID tenant, BonusPlan plan, CashValuePlan cashValue) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenant);
        try {
            int n = SEQ.incrementAndGet();
            PartyView applicant = partyApi.registerIndividual("Bonus Test Life " + n, LocalDate.of(1985, 1, 1),
                "+25571600" + String.format("%04d", n % 10000), null, "test-agent");
            ProductSummaryView product = productApi.createProduct("WP-" + n + "-" + tenant.toString().substring(0, 4),
                "With-Profits Test", ProductCategory.ENDOWMENT, "TZS", "actuary");
            PayoutPlan payout = PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of(new PayoutRowInput(
                PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null)));
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.GMM, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-49", BigDecimal.ONE, 30, 49),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, List.of(), EligibilityBounds.none(), FrequencyLoading.none(), ANY_FILING,
                cashValue, payout, AccumulationPlan.none(), plan, "actuary");
            UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();
            PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(),
                versionId, new BigDecimal("1000000.00"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY", null,
                List.of(), "bonus test", LocalDate.now(), 180, null, null, null);
            String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
            policyApi.activateOnFirstPremium(policyNumber);
            return new Issued(policyNumber, product.productId(), versionId);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }

    /** A lifecycle event dated in the PAST -- how a test puts a status on an earlier valuation date. */
    public void publishAt(UUID tenant, String eventType, Instant occurredAt, Map<String, Object> payload) {
        tx.executeWithoutResult(status -> publisher.publishEvent(
            new DomainEventEnvelope<>(UUID.randomUUID(), eventType, 1, tenant, occurredAt, null, payload)));
    }

    public void publish(UUID tenant, String eventType, Map<String, Object> payload) {
        tx.executeWithoutResult(status -> publisher.publishEvent(DomainEventEnvelope.of(eventType, tenant, payload)));
    }
}
```

The integration test's migration list is `RateDeclarationIntegrationTest`'s list with `product/V20__bonus_terms.sql` added after V19, and `bonus/V1__create_bonus_schema.sql` added after the accumulation migration. Copy the class header, the container and `@DynamicPropertySource` from it.

```java
// src/test/java/tz/co/nlolo/lifeplatform/bonus/StatusRecordIntegrationTest.java  (body after the shared header)
    private static final UUID TENANT = UUID.randomUUID();
    @Autowired private BonusTestFixtures fixtures;
    @Autowired private StatusEventRepository statusEvents;
    @Autowired private ParticipantRepository participants;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    @Test
    void aParticipatingPolicyIsRecordedFromIssueAndEachChangeAfter() {
        var issued = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        // issuePolicy's PolicyIssued (PROPOSED) then activateOnFirstPremium's PolicyActivated (ACTIVE).
        var rows = asTenant(() -> statusEvents.findByPolicyNumberOrderByEffectiveAtAsc(issued.policyNumber()));
        assertThat(rows).extracting(StatusEvent::getStatus).containsExactly("PROPOSED", "ACTIVE");
        assertThat(asTenant(() -> participants.findById(issued.policyNumber())).orElseThrow().getIssuedOn())
            .isEqualTo(LocalDate.now());
    }

    @Test
    void aNonParticipatingPolicyIsNotRecordedAtAll() {
        var issued = fixtures.issue(TENANT, BonusPlan.none(), CashValuePlan.none());
        assertThat(asTenant(() -> participants.existsById(issued.policyNumber()))).isFalse();
        assertThat(asTenant(() -> statusEvents.findByPolicyNumberOrderByEffectiveAtAsc(issued.policyNumber()))).isEmpty();
    }

    @Test
    void aRedeliveredEventIsRecordedOnce() {
        var issued = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        var envelope = new DomainEventEnvelope<>(UUID.randomUUID(), "policy.PolicyLapsed", 1, TENANT, java.time.Instant.now(),
            null, Map.<String, Object>of("policyNumber", issued.policyNumber(), "lapsedAt", java.time.Instant.now().toString()));
        fixtures.publishEnvelopeTwice(envelope);
        assertThat(asTenant(() -> statusEvents.findByPolicyNumberOrderByEffectiveAtAsc(issued.policyNumber())))
            .extracting(StatusEvent::getStatus).containsExactly("PROPOSED", "ACTIVE", "LAPSED");
    }
```

For `aRedeliveredEventIsRecordedOnce`, add to `BonusTestFixtures`:

```java
    public void publishEnvelopeTwice(DomainEventEnvelope<?> envelope) {
        tx.executeWithoutResult(status -> publisher.publishEvent(envelope));
        tx.executeWithoutResult(status -> publisher.publishEvent(envelope));
    }
```

- [ ] **Step 7: Test classes that reach the recorder**

Every test that issues a policy now fires `bonusPolicyEventListener`. It calls `resolveBonusPlan`, which reads `product.version_bonus_terms`. **This is the ddl-auto trap at its widest: a class that issues policies without product V20 logs an ERROR from the listener on every issuance.** The listener never rethrows, so those tests stay green, but the log fills with failures that look real. Two fixes; do both:
1. `StatusRecorder` catches nothing new. Instead, add V20 to every class that applies `product/V19__accumulation_terms.sql` AND issues a policy:
   ```bash
   grep -rl 'product/V19__accumulation_terms.sql' src/test/java | xargs grep -l 'issuePolicy\|issueSavingsPlan\|issueRealPolicy' | sort
   ```
2. The `bonus` schema itself is only reached once `resolveBonusPlan` says participating, which no existing fixture does, so `bonus/V1` is NOT needed in those classes.

Classes that apply product migrations only up to V18 never reach `resolveAccumulationPlan` either, so they already avoid this path. Leave them alone.

- [ ] **Step 8: Run and commit**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='EligibilityTest,StatusRecordIntegrationTest,ContributionIntegrationTest'
```

`ContributionIntegrationTest` is the control: it issues ACCOUNT policies and must stay green, with no `bonus failed to process` ERROR in its output (`grep -c "bonus failed" target/surefire-reports/*ContributionIntegrationTest-output.txt` → 0).

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/bonus src/test/java
git commit -m "feat(bonus): a status record for every participating policy, and eligibility judged on it -- never 'in force'"
```

---

### Task 4: Declarations — propose, approve, withdraw

**Files:**
- Create: `bonus/domain/Declaration.java`, `bonus/infrastructure/DeclarationRepository.java`
- Create: `bonus/api/BonusApi.java`, `BonusDeclarationView.java`
- Create: `bonus/application/BonusApiImpl.java` (declarations only in this task)
- Create: `bonus/infrastructure/BonusController.java`, `BonusExceptionHandler.java`, `DeclarationRequest.java`, `DeclarationResponse.java`, `MoneyResponse.java`
- Create: `api/openapi/openapi-bonus.yaml`
- Test: `DeclarationIntegrationTest.java`, `BonusContractTest.java`

**Interfaces:**
- Produces: `BonusApi.proposeDeclaration(UUID productId, LocalDate valuationDate, BigDecimal reversionaryRatePercent, BigDecimal terminalRatePercent, String proposedBy) -> BonusDeclarationView`; `approveDeclaration(UUID declarationId, String approvedBy)`; `withdrawDeclaration(UUID declarationId, String by)`; `listDeclarations(UUID productId) -> List<BonusDeclarationView>`; `record BonusDeclarationView(UUID declarationId, UUID productId, LocalDate valuationDate, BigDecimal reversionaryRatePercent, BigDecimal terminalRatePercent, DeclarationStatus status, String proposedBy, Instant proposedAt, String approvedBy, Instant approvedAt, Instant completedAt)`.

- [ ] **Step 1: Write the failing integration test**

The header and migration list are `StatusRecordIntegrationTest`'s.

```java
    @Test
    void theProposerCannotApproveAndASecondPersonCan() {
        UUID product = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none()).productId();
        var proposed = asTenant(() -> api.proposeDeclaration(product, LocalDate.now(), new BigDecimal("3.5"),
            new BigDecimal("40"), "admin-one"));
        assertThatThrownBy(() -> asTenant(() -> api.approveDeclaration(proposed.declarationId(), "admin-one")))
            .isInstanceOf(BonusStateException.class)
            .hasMessage("A bonus declaration must be approved by someone other than the person who proposed it");
        assertThat(asTenant(() -> api.approveDeclaration(proposed.declarationId(), "finance-two")).status())
            .isEqualTo(DeclarationStatus.APPROVED);
    }

    @Test
    void oneApprovedDeclarationPerProductAndValuationDate() {
        UUID product = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none()).productId();
        LocalDate date = LocalDate.now().plusMonths(2);
        var first = asTenant(() -> api.proposeDeclaration(product, date, BigDecimal.ONE, BigDecimal.ZERO, "admin-one"));
        var second = asTenant(() -> api.proposeDeclaration(product, date, BigDecimal.TWO, BigDecimal.ZERO, "admin-one"));
        asTenant(() -> api.approveDeclaration(first.declarationId(), "finance-two"));
        assertThatThrownBy(() -> asTenant(() -> api.approveDeclaration(second.declarationId(), "finance-two")))
            .isInstanceOf(BonusStateException.class)
            .hasMessage("A bonus is already declared for this product as at " + date + ". An approved declaration cannot be withdrawn.");
    }

    @Test
    void onlyAProposalCanBeWithdrawn() {
        UUID product = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none()).productId();
        var d = asTenant(() -> api.proposeDeclaration(product, LocalDate.now().plusMonths(3), BigDecimal.ONE, BigDecimal.ZERO, "admin-one"));
        asTenant(() -> api.approveDeclaration(d.declarationId(), "finance-two"));
        assertThatThrownBy(() -> asTenant(() -> api.withdrawDeclaration(d.declarationId(), "admin-one")))
            .hasMessage("This bonus declaration is approved, not awaiting approval");
    }

    @Test
    void ratesOutsideTheirRangesAreRefused() {
        UUID product = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none()).productId();
        assertThatThrownBy(() -> asTenant(() -> api.proposeDeclaration(product, LocalDate.now(), new BigDecimal("101"),
            BigDecimal.ZERO, "admin-one"))).hasMessage("A reversionary bonus rate must be between 0% and 100%");
        assertThatThrownBy(() -> asTenant(() -> api.proposeDeclaration(product, LocalDate.now(), BigDecimal.ONE,
            new BigDecimal("1001"), "admin-one"))).hasMessage("A terminal bonus rate must be between 0% and 1000% of attached bonuses");
    }

    @Test
    void aProductWithNoWithProfitsVersionCannotDeclare() {
        UUID product = fixtures.issue(TENANT, BonusPlan.none(), CashValuePlan.none()).productId();
        assertThatThrownBy(() -> asTenant(() -> api.proposeDeclaration(product, LocalDate.now(), BigDecimal.ONE,
            BigDecimal.ZERO, "admin-one"))).hasMessage("This product has no with-profits policies to declare a bonus on");
    }
```

`aProductWithNoWithProfitsVersionCannotDeclare` checks for any participant on the product: a declaration on a product that has never sold a with-profits policy attaches to nobody and is almost certainly a mistake.

- [ ] **Step 2: Run it to see it fail** — `./mvnw -o test -Dtest=DeclarationIntegrationTest` → COMPILATION FAILURE.

- [ ] **Step 3: The aggregate**

```java
// bonus/domain/Declaration.java
package tz.co.nlolo.lifeplatform.bonus.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.bonus.api.BonusStateException;
import tz.co.nlolo.lifeplatform.bonus.api.DeclarationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A bonus declared on a product as at a valuation date. Two people, like every price. */
@Entity
@Table(name = "declaration", schema = "bonus")
public class Declaration {
    @Id @UuidGenerator @Column(name = "declaration_id") private UUID declarationId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(name = "valuation_date", nullable = false) private LocalDate valuationDate;
    @Column(name = "reversionary_rate_percent", nullable = false) private BigDecimal reversionaryRatePercent;
    @Column(name = "terminal_rate_percent", nullable = false) private BigDecimal terminalRatePercent;
    @Column(nullable = false) private String status = DeclarationStatus.PROPOSED.name();
    @Column(name = "proposed_by", nullable = false) private String proposedBy;
    @Column(name = "proposed_at", nullable = false) private Instant proposedAt = Instant.now();
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Version private long version;

    protected Declaration() {}

    public Declaration(UUID tenantId, UUID productId, LocalDate valuationDate, BigDecimal reversionaryRatePercent,
                       BigDecimal terminalRatePercent, String proposedBy) {
        this.tenantId = tenantId;
        this.productId = productId;
        this.valuationDate = valuationDate;
        this.reversionaryRatePercent = reversionaryRatePercent;
        this.terminalRatePercent = terminalRatePercent;
        this.proposedBy = proposedBy;
    }

    public DeclarationStatus status() { return DeclarationStatus.valueOf(status); }

    public void approve(String by) {
        requireProposed();
        if (by.equals(proposedBy)) {
            throw new BonusStateException("A bonus declaration must be approved by someone other than the person who proposed it");
        }
        this.status = DeclarationStatus.APPROVED.name();
        this.approvedBy = by;
        this.approvedAt = Instant.now();
    }

    /** Only a proposal: an approved declaration may already have attached to a policy. */
    public void withdraw() {
        requireProposed();
        this.status = DeclarationStatus.WITHDRAWN.name();
    }

    public void complete() { this.completedAt = Instant.now(); }

    private void requireProposed() {
        if (status() != DeclarationStatus.PROPOSED) {
            throw new BonusStateException("This bonus declaration is " + status().name().toLowerCase() + ", not awaiting approval");
        }
    }

    public UUID getDeclarationId() { return declarationId; }
    public UUID getProductId() { return productId; }
    public LocalDate getValuationDate() { return valuationDate; }
    public BigDecimal getReversionaryRatePercent() { return reversionaryRatePercent; }
    public BigDecimal getTerminalRatePercent() { return terminalRatePercent; }
    public String getProposedBy() { return proposedBy; }
    public Instant getProposedAt() { return proposedAt; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
```

```java
// bonus/infrastructure/DeclarationRepository.java
package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tz.co.nlolo.lifeplatform.bonus.domain.Declaration;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeclarationRepository extends JpaRepository<Declaration, UUID> {
    List<Declaration> findByProductIdOrderByValuationDateDescProposedAtDesc(UUID productId);

    /** The latest APPROVED declaration on or before a date -- the one an exit is valued against. */
    @Query(value = "SELECT * FROM bonus.declaration WHERE product_id = :productId AND status = 'APPROVED' "
        + "AND valuation_date <= :date ORDER BY valuation_date DESC LIMIT 1", nativeQuery = true)
    Optional<Declaration> latestApprovedOnOrBefore(UUID productId, LocalDate date);

    @Query(value = "SELECT declaration_id, tenant_id FROM bonus.declarations_due()", nativeQuery = true)
    List<Object[]> findDueAcrossTenants();
}
```

- [ ] **Step 4: The API, the implementation, REST**

```java
// bonus/api/BonusDeclarationView.java
package tz.co.nlolo.lifeplatform.bonus.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BonusDeclarationView(UUID declarationId, UUID productId, LocalDate valuationDate,
                                   BigDecimal reversionaryRatePercent, BigDecimal terminalRatePercent,
                                   DeclarationStatus status, String proposedBy, Instant proposedAt,
                                   String approvedBy, Instant approvedAt, Instant completedAt) {}
```

```java
// bonus/api/BonusApi.java  (Tasks 5 and 6 add to it)
package tz.co.nlolo.lifeplatform.bonus.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** With-profits bonuses (product step 4). */
public interface BonusApi {
    BonusDeclarationView proposeDeclaration(UUID productId, LocalDate valuationDate, BigDecimal reversionaryRatePercent,
                                            BigDecimal terminalRatePercent, String proposedBy);
    BonusDeclarationView approveDeclaration(UUID declarationId, String approvedBy);
    BonusDeclarationView withdrawDeclaration(UUID declarationId, String withdrawnBy);
    List<BonusDeclarationView> listDeclarations(UUID productId);
}
```

```java
// bonus/application/BonusApiImpl.java  (declarations; Tasks 5-7 add the rest)
package tz.co.nlolo.lifeplatform.bonus.application;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.bonus.api.*;
import tz.co.nlolo.lifeplatform.bonus.domain.Declaration;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.DeclarationRepository;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.ParticipantRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class BonusApiImpl implements BonusApi {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private final DeclarationRepository declarations;
    private final ParticipantRepository participants;

    public BonusApiImpl(DeclarationRepository declarations, ParticipantRepository participants) {
        this.declarations = declarations;
        this.participants = participants;
    }

    @Override
    @Transactional
    public BonusDeclarationView proposeDeclaration(UUID productId, LocalDate valuationDate, BigDecimal reversionaryRatePercent,
                                                   BigDecimal terminalRatePercent, String proposedBy) {
        if (reversionaryRatePercent.signum() < 0 || reversionaryRatePercent.compareTo(HUNDRED) > 0) {
            throw new BonusStateException("A reversionary bonus rate must be between 0% and 100%");
        }
        if (terminalRatePercent.signum() < 0 || terminalRatePercent.compareTo(THOUSAND) > 0) {
            throw new BonusStateException("A terminal bonus rate must be between 0% and 1000% of attached bonuses");
        }
        if (!participants.existsByProductId(productId)) {
            throw new BonusStateException("This product has no with-profits policies to declare a bonus on");
        }
        return toView(declarations.save(new Declaration(TenantContext.get(), productId, valuationDate,
            reversionaryRatePercent, terminalRatePercent, proposedBy)));
    }

    @Override
    @Transactional
    public BonusDeclarationView approveDeclaration(UUID declarationId, String approvedBy) {
        Declaration d = load(declarationId);
        d.approve(approvedBy);
        try {
            // saveAndFlush so ux_declaration_valuation fires inside the catch, not at commit as a 500.
            return toView(declarations.saveAndFlush(d));
        } catch (DataIntegrityViolationException e) {
            throw new BonusStateException("A bonus is already declared for this product as at " + d.getValuationDate()
                + ". An approved declaration cannot be withdrawn.");
        }
    }

    @Override
    @Transactional
    public BonusDeclarationView withdrawDeclaration(UUID declarationId, String withdrawnBy) {
        Declaration d = load(declarationId);
        d.withdraw();
        return toView(declarations.save(d));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BonusDeclarationView> listDeclarations(UUID productId) {
        return declarations.findByProductIdOrderByValuationDateDescProposedAtDesc(productId).stream().map(this::toView).toList();
    }

    Declaration load(UUID declarationId) {
        return declarations.findById(declarationId)
            .orElseThrow(() -> new BonusStateException("No bonus declaration " + declarationId));
    }

    BonusDeclarationView toView(Declaration d) {
        return new BonusDeclarationView(d.getDeclarationId(), d.getProductId(), d.getValuationDate(),
            d.getReversionaryRatePercent(), d.getTerminalRatePercent(), d.status(), d.getProposedBy(), d.getProposedAt(),
            d.getApprovedBy(), d.getApprovedAt(), d.getCompletedAt());
    }
}
```

Add `boolean existsByProductId(UUID productId);` to `ParticipantRepository`.

```java
// bonus/infrastructure/DeclarationRequest.java
package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record DeclarationRequest(@NotNull LocalDate valuationDate, @NotNull BigDecimal reversionaryRatePercent,
                                 @NotNull BigDecimal terminalRatePercent) {}
```

```java
// bonus/infrastructure/DeclarationResponse.java
package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import tz.co.nlolo.lifeplatform.bonus.api.BonusDeclarationView;

/**
 * Rates are sent as plain strings, without trailing zeros: NUMERIC(7,4) comes back as 3.5000, and
 * the console would otherwise show "3.5000%".
 */
public record DeclarationResponse(String declarationId, String productId, String valuationDate,
                                  String reversionaryRatePercent, String terminalRatePercent, String status,
                                  String proposedBy, String proposedAt, String approvedBy, String approvedAt,
                                  String completedAt) {
    public static DeclarationResponse from(BonusDeclarationView v) {
        return new DeclarationResponse(v.declarationId().toString(), v.productId().toString(), v.valuationDate().toString(),
            v.reversionaryRatePercent().stripTrailingZeros().toPlainString(),
            v.terminalRatePercent().stripTrailingZeros().toPlainString(), v.status().name(), v.proposedBy(),
            v.proposedAt().toString(), v.approvedBy(), v.approvedAt() != null ? v.approvedAt().toString() : null,
            v.completedAt() != null ? v.completedAt().toString() : null);
    }
}
```

**Before writing `DeclarationResponse`, read `accumulation/infrastructure/RateDeclarationResponse.java`** and match its rate representation exactly (string vs number). The console's `ratePercent` rendering and the step 3 "trailing zeros" fix both depend on it. If it sends a number, send numbers here too, and adjust the OpenAPI below to match.

```java
// bonus/infrastructure/BonusController.java  (Task 7 adds the policy reads)
package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tz.co.nlolo.lifeplatform.bonus.api.BonusApi;

import java.util.List;
import java.util.UUID;

@RestController
public class BonusController {

    /** Declaring a bonus is a price: ADMIN's, as publishing a version is. */
    static final String PRICING = "hasRole('REALM_STAFF') and hasRole('ADMIN')";
    /** The second signature: another ADMIN, or finance. */
    static final String FINANCE = "hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))";

    private final BonusApi api;

    public BonusController(BonusApi api) {
        this.api = api;
    }

    @GetMapping("/products/{productId}/bonus-declarations")
    @PreAuthorize(FINANCE)
    public List<DeclarationResponse> listDeclarations(@PathVariable UUID productId) {
        return api.listDeclarations(productId).stream().map(DeclarationResponse::from).toList();
    }

    @PostMapping("/products/{productId}/bonus-declarations")
    @PreAuthorize(PRICING)
    @ResponseStatus(HttpStatus.CREATED)
    public DeclarationResponse propose(@PathVariable UUID productId, @Valid @RequestBody DeclarationRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        return DeclarationResponse.from(api.proposeDeclaration(productId, request.valuationDate(),
            request.reversionaryRatePercent(), request.terminalRatePercent(), jwt.getSubject()));
    }

    @PostMapping("/bonus-declarations/{declarationId}/approve")
    @PreAuthorize(FINANCE)
    public DeclarationResponse approve(@PathVariable UUID declarationId, @AuthenticationPrincipal Jwt jwt) {
        return DeclarationResponse.from(api.approveDeclaration(declarationId, jwt.getSubject()));
    }

    @PostMapping("/bonus-declarations/{declarationId}/withdraw")
    @PreAuthorize(PRICING)
    public DeclarationResponse withdraw(@PathVariable UUID declarationId, @AuthenticationPrincipal Jwt jwt) {
        return DeclarationResponse.from(api.withdrawDeclaration(declarationId, jwt.getSubject()));
    }
}
```

```java
// bonus/infrastructure/BonusExceptionHandler.java
package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tz.co.nlolo.lifeplatform.bonus.api.BonusStateException;

import java.util.UUID;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BonusExceptionHandler {

    @ExceptionHandler(BonusStateException.class)
    public ProblemDetail handleRefused(BonusStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setProperty("errorCode", "BONUS_REFUSED");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
```

`openapi-bonus.yaml`: copy `openapi-accumulation.yaml`'s header, the `servers` block, `securitySchemes` and the `Problem` and `Money` refs. Define the four declaration paths above, with `DeclarationRequest` and `Declaration` schemas whose fields match the records exactly. Task 7 adds the policy paths. Every response lists `401`, `403` and `422` the way the accumulation spec does.

- [ ] **Step 5: The contract test**

`BonusContractTest` copies `AccumulationContractTest`'s harness: MockMvc, the OpenAPI response validator and its JWT helpers. Its tests:
- `POST /products/{id}/bonus-declarations` as ADMIN → 201, and the body validates against `openapi-bonus.yaml`.
- the same as UNDERWRITER → 403.
- approve as the proposer's own subject → 422 `BONUS_REFUSED`, with detail `"A bonus declaration must be approved by someone other than the person who proposed it"`.
- `GET /products/{id}/bonus-declarations` as FINANCE_OFFICER → 200, validated.

- [ ] **Step 6: Run and commit**

```bash
./mvnw -o test -Dtest='DeclarationIntegrationTest,BonusContractTest'
git add src/main/java/tz/co/nlolo/lifeplatform/bonus src/test/java/tz/co/nlolo/lifeplatform/bonus api/openapi/openapi-bonus.yaml
git commit -m "feat(bonus): declarations -- per product and valuation date, proposed by one person and approved by another"
```

---

### Task 5: Attaching — the arithmetic, the ledger, the drain, and policy's projection

**Files:**
- Create: `bonus/domain/BonusArithmetic.java`, `AttachmentEntry.java`, `DeclarationOutcome.java`
- Create: `bonus/infrastructure/AttachmentEntryRepository.java`, `DeclarationOutcomeRepository.java`
- Create: `bonus/application/AttachmentLedger.java`, `DeclarationDrain.java`
- Create: `db-migrations/policy/V32__attached_bonus_projection.sql`, `policy/domain/PolicyBonus.java`, `policy/infrastructure/PolicyBonusRepository.java`
- Modify: `policy/api/PolicyApi.java`, `policy/application/PolicyApiImpl.java` (`restateAttachedBonus`), `bonus/application/BonusApiImpl.java` (`attachOne`, `drainDeclaration`), `src/main/resources/application-local.yml`
- Test: `BonusArithmeticTest.java`, `AttachmentIntegrationTest.java`

**Interfaces:**
- Produces: `BonusArithmetic.reversionary(BonusMethod, BigDecimal sumAssured, BigDecimal attachedBefore, BigDecimal ratePercent)`, `interim(BonusMethod, BigDecimal sumAssured, BigDecimal attached, BigDecimal ratePercent, long wholeMonths)`, `terminal(BigDecimal attached, BigDecimal ratePercent)`, `wholeMonths(LocalDate from, LocalDate to)`; `AttachmentLedger.attach(Participant, Declaration, BigDecimal basis, BigDecimal amount, String createdBy)` and `reverse(String policyNumber, AttachmentEntry entry, LocalDate on, String reason, String createdBy)`; `BonusApiImpl.drainDeclaration(UUID declarationId) -> int` (outcomes written); `PolicyApi.restateAttachedBonus(String policyNumber, BigDecimal total)`; `AttachmentEntryRepository.sumBefore(String policyNumber, LocalDate date)` and `sumThrough(...)`.

- [ ] **Step 1: The arithmetic, test first**

```java
// src/test/java/tz/co/nlolo/lifeplatform/bonus/BonusArithmeticTest.java
package tz.co.nlolo.lifeplatform.bonus;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.bonus.domain.BonusArithmetic;
import tz.co.nlolo.lifeplatform.product.api.BonusMethod;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BonusArithmeticTest {

    private static final BigDecimal SA = new BigDecimal("1000000.00");

    @Test
    void simpleIsAlwaysOnTheSumAssured() {
        assertThat(BonusArithmetic.reversionary(BonusMethod.SIMPLE, SA, new BigDecimal("60000"), new BigDecimal("3")))
            .isEqualByComparingTo("30000.00");
    }

    @Test
    void compoundEarnsOnEarlierBonusesOverThreeDeclarations() {
        BigDecimal attached = BigDecimal.ZERO;
        for (int year = 0; year < 3; year++) {
            attached = attached.add(BonusArithmetic.reversionary(BonusMethod.COMPOUND, SA, attached, new BigDecimal("3")));
        }
        // 30,000 then 30,900 then 31,827.00 -- 92,727.00, against simple's 90,000.
        assertThat(attached).isEqualByComparingTo("92727.00");
    }

    @Test
    void roundsOnceHalfEven() {
        // 1,000,000.50 x 2.5% = 25,000.0125 -> 25,000.01
        assertThat(BonusArithmetic.reversionary(BonusMethod.SIMPLE, new BigDecimal("1000000.50"), BigDecimal.ZERO,
            new BigDecimal("2.5"))).isEqualByComparingTo("25000.01");
        // 0.125 -> 0.12 (half-even), not 0.13
        assertThat(BonusArithmetic.terminal(new BigDecimal("0.25"), new BigDecimal("50"))).isEqualByComparingTo("0.12");
    }

    @Test
    void interimIsTheRateForTheWholeMonthsOnTheMethodsBase() {
        // compound: (1,000,000 + 60,000) x 3% x 7/12 = 18,550.00
        assertThat(BonusArithmetic.interim(BonusMethod.COMPOUND, SA, new BigDecimal("60000"), new BigDecimal("3"), 7))
            .isEqualByComparingTo("18550.00");
        assertThat(BonusArithmetic.interim(BonusMethod.SIMPLE, SA, new BigDecimal("60000"), new BigDecimal("3"), 0))
            .isEqualByComparingTo("0.00");
    }

    @Test
    void terminalIsAPercentOfAttachedBonuses() {
        assertThat(BonusArithmetic.terminal(new BigDecimal("92727.00"), new BigDecimal("150"))).isEqualByComparingTo("139090.50");
    }

    @Test
    void wholeMonthsCountsOnlyCompletedMonthsAndNeverGoesNegative() {
        assertThat(BonusArithmetic.wholeMonths(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 7, 30))).isEqualTo(6);
        assertThat(BonusArithmetic.wholeMonths(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 7, 31))).isEqualTo(7);
        assertThat(BonusArithmetic.wholeMonths(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 12, 31))).isZero();
    }
}
```

- [ ] **Step 2: Implement it**

```java
// bonus/domain/BonusArithmetic.java
package tz.co.nlolo.lifeplatform.bonus.domain;

import tz.co.nlolo.lifeplatform.product.api.BonusMethod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Pure. Every figure rounds ONCE, to 2 dp, HALF_EVEN -- the ledger's rounding. */
public final class BonusArithmetic {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWELVE = new BigDecimal("12");

    private BonusArithmetic() {}

    /** Q1. COMPOUND's base includes everything attached BEFORE this declaration's valuation date. */
    public static BigDecimal reversionary(BonusMethod method, BigDecimal sumAssured, BigDecimal attachedBefore, BigDecimal ratePercent) {
        return base(method, sumAssured, attachedBefore).multiply(ratePercent).divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
    }

    /** Q4: the last declared rate, for the whole months since, on the same base. */
    public static BigDecimal interim(BonusMethod method, BigDecimal sumAssured, BigDecimal attached, BigDecimal ratePercent,
                                     long wholeMonths) {
        if (wholeMonths <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return base(method, sumAssured, attached).multiply(ratePercent).multiply(BigDecimal.valueOf(wholeMonths))
            .divide(HUNDRED.multiply(TWELVE), 2, RoundingMode.HALF_EVEN);
    }

    /** Q5: a percentage of attached reversionary bonuses. */
    public static BigDecimal terminal(BigDecimal attached, BigDecimal ratePercent) {
        return attached.multiply(ratePercent).divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
    }

    public static long wholeMonths(LocalDate from, LocalDate to) {
        return Math.max(0, ChronoUnit.MONTHS.between(from, to));
    }

    private static BigDecimal base(BonusMethod method, BigDecimal sumAssured, BigDecimal attached) {
        return method == BonusMethod.COMPOUND ? sumAssured.add(attached) : sumAssured;
    }
}
```

Run: `./mvnw -o test -Dtest=BonusArithmeticTest` — Expected: PASS, 6 tests.

- [ ] **Step 3: Policy's projection**

```sql
-- db-migrations/policy/V32__attached_bonus_projection.sql
-- Product step 4: the attached-bonus total, a PROJECTION the bonus module restates after every
-- entry -- so the surrender quote can read it without policy depending on bonus (a cycle).
--
-- A separate table, not a column on policy_account: that entity is read by nearly every test class
-- that issues a policy, and ddl-auto none would break all of them on a column they lack. A row
-- exists only once a bonus has attached; its absence is zero.
CREATE TABLE policy.policy_bonus (
    policy_number          VARCHAR(20) PRIMARY KEY,
    tenant_id              UUID NOT NULL,
    attached_bonus_amount  NUMERIC(19,2) NOT NULL CHECK (attached_bonus_amount >= 0),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE policy.policy_bonus ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_bonus_tenant_isolation ON policy.policy_bonus
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON policy.policy_bonus TO app_role;
```

```java
// policy/domain/PolicyBonus.java
package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The attached-bonus projection (product step 4). Written only by restateAttachedBonus. */
@Entity
@Table(name = "policy_bonus", schema = "policy")
public class PolicyBonus {
    @Id @Column(name = "policy_number") private String policyNumber;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "attached_bonus_amount", nullable = false) private BigDecimal attachedBonusAmount;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected PolicyBonus() {}

    public PolicyBonus(String policyNumber, UUID tenantId) {
        this.policyNumber = policyNumber;
        this.tenantId = tenantId;
        this.attachedBonusAmount = BigDecimal.ZERO;
    }

    public void restate(BigDecimal amount) {
        this.attachedBonusAmount = amount;
        this.updatedAt = Instant.now();
    }

    public BigDecimal getAttachedBonusAmount() { return attachedBonusAmount; }
}
```

```java
// policy/infrastructure/PolicyBonusRepository.java
package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyBonus;

public interface PolicyBonusRepository extends JpaRepository<PolicyBonus, String> {}
```

In `PolicyApi`, beside `restateAccountValue`:

```java
    /**
     * Restate a with-profits policy's attached-bonus total (product step 4). Called ONLY by bonus,
     * after every entry: the bonus ledger is the truth and this is its projection, read by the
     * surrender quote.
     */
    void restateAttachedBonus(String policyNumber, java.math.BigDecimal total);
```

In `PolicyApiImpl` (inject `PolicyBonusRepository`):

```java
    @Override
    @Transactional
    public void restateAttachedBonus(String policyNumber, BigDecimal total) {
        UUID tenantId = TenantContext.get();
        findPolicyOrThrow(policyNumber, tenantId);
        PolicyBonus projection = policyBonusRepository.findById(policyNumber)
            .orElseGet(() -> new PolicyBonus(policyNumber, tenantId));
        projection.restate(total);
        policyBonusRepository.save(projection);
    }
```

- [ ] **Step 4: The ledger rows and the ONE writer**

```java
// bonus/domain/AttachmentEntry.java
package tz.co.nlolo.lifeplatform.bonus.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.bonus.api.BonusEntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Insert-only. No setters: the database refuses an update anyway. */
@Entity
@Table(name = "attachment_entry", schema = "bonus")
public class AttachmentEntry {
    @Id @UuidGenerator @Column(name = "entry_id") private UUID entryId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private int seq;
    @Column(name = "entry_type", nullable = false) private String entryType;
    @Column(nullable = false) private BigDecimal amount;
    @Column(name = "total_after", nullable = false) private BigDecimal totalAfter;
    @Column(name = "effective_date", nullable = false) private LocalDate effectiveDate;
    @Column(name = "declaration_id") private UUID declarationId;
    @Column(name = "basis_amount") private BigDecimal basisAmount;
    @Column(name = "rate_percent") private BigDecimal ratePercent;
    @Column(name = "source_type", nullable = false) private String sourceType;
    @Column(name = "source_ref", nullable = false) private String sourceRef;
    @Column(name = "reverses_entry_id") private UUID reversesEntryId;
    @Column private String reason;
    @Column(name = "created_by", nullable = false) private String createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected AttachmentEntry() {}

    public AttachmentEntry(UUID tenantId, String policyNumber, int seq, BonusEntryType type, BigDecimal amount,
                           BigDecimal totalAfter, LocalDate effectiveDate, UUID declarationId, BigDecimal basisAmount,
                           BigDecimal ratePercent, String sourceType, String sourceRef, UUID reversesEntryId,
                           String reason, String createdBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.seq = seq;
        this.entryType = type.name();
        this.amount = amount;
        this.totalAfter = totalAfter;
        this.effectiveDate = effectiveDate;
        this.declarationId = declarationId;
        this.basisAmount = basisAmount;
        this.ratePercent = ratePercent;
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
        this.reversesEntryId = reversesEntryId;
        this.reason = reason;
        this.createdBy = createdBy;
    }

    public UUID getEntryId() { return entryId; }
    public String getPolicyNumber() { return policyNumber; }
    public int getSeq() { return seq; }
    public BonusEntryType type() { return BonusEntryType.valueOf(entryType); }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getTotalAfter() { return totalAfter; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public UUID getDeclarationId() { return declarationId; }
    public BigDecimal getBasisAmount() { return basisAmount; }
    public BigDecimal getRatePercent() { return ratePercent; }
    public UUID getReversesEntryId() { return reversesEntryId; }
    public String getReason() { return reason; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
```

```java
// bonus/domain/DeclarationOutcome.java
package tz.co.nlolo.lifeplatform.bonus.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.bonus.api.OutcomeKind;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "declaration_outcome", schema = "bonus")
public class DeclarationOutcome {
    @Id @UuidGenerator @Column(name = "outcome_id") private UUID outcomeId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "declaration_id", nullable = false) private UUID declarationId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private String outcome;
    @Column private String reason;
    @Column(name = "decided_at", nullable = false) private Instant decidedAt = Instant.now();

    protected DeclarationOutcome() {}

    public DeclarationOutcome(UUID tenantId, UUID declarationId, String policyNumber, OutcomeKind outcome, String reason) {
        this.tenantId = tenantId;
        this.declarationId = declarationId;
        this.policyNumber = policyNumber;
        this.outcome = outcome.name();
        this.reason = reason;
    }

    public UUID getDeclarationId() { return declarationId; }
    public OutcomeKind outcome() { return OutcomeKind.valueOf(outcome); }
    public String getReason() { return reason; }
    public Instant getDecidedAt() { return decidedAt; }
}
```

```java
// bonus/infrastructure/AttachmentEntryRepository.java
package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tz.co.nlolo.lifeplatform.bonus.domain.AttachmentEntry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AttachmentEntryRepository extends JpaRepository<AttachmentEntry, UUID> {
    List<AttachmentEntry> findByPolicyNumberOrderBySeqAsc(String policyNumber);

    /** COMPOUND's base: what was attached strictly BEFORE a valuation date (declarations may land out of order). */
    @Query("select coalesce(sum(e.amount), 0) from AttachmentEntry e where e.policyNumber = :policyNumber and e.effectiveDate < :date")
    BigDecimal sumBefore(String policyNumber, LocalDate date);

    /** Attached as at an exit date, inclusive. */
    @Query("select coalesce(sum(e.amount), 0) from AttachmentEntry e where e.policyNumber = :policyNumber and e.effectiveDate <= :date")
    BigDecimal sumThrough(String policyNumber, LocalDate date);

    @Query("select e from AttachmentEntry e where e.policyNumber = :policyNumber and e.entryType = 'REVERSIONARY' "
        + "and not exists (select r from AttachmentEntry r where r.reversesEntryId = e.entryId) order by e.seq")
    List<AttachmentEntry> unreversed(String policyNumber);
}
```

```java
// bonus/infrastructure/DeclarationOutcomeRepository.java
package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.bonus.domain.DeclarationOutcome;
import java.util.List;
import java.util.UUID;

public interface DeclarationOutcomeRepository extends JpaRepository<DeclarationOutcome, UUID> {
    List<DeclarationOutcome> findByPolicyNumberOrderByDecidedAtDesc(String policyNumber);
}
```

```java
// bonus/application/AttachmentLedger.java
package tz.co.nlolo.lifeplatform.bonus.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.bonus.api.BonusEntryType;
import tz.co.nlolo.lifeplatform.bonus.api.BonusStateException;
import tz.co.nlolo.lifeplatform.bonus.domain.AttachmentEntry;
import tz.co.nlolo.lifeplatform.bonus.domain.Declaration;
import tz.co.nlolo.lifeplatform.bonus.domain.Participant;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.AttachmentEntryRepository;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.ParticipantRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * The ONE place an attachment entry is written -- accumulation's LedgerService, for the same reason.
 * The head is locked, the entry is flushed so the once-only index fires HERE, and policy's projection
 * follows in the same transaction.
 */
@Service
public class AttachmentLedger {

    private final ParticipantRepository participants;
    private final AttachmentEntryRepository entries;
    private final PolicyApi policyApi;
    private final ApplicationEventPublisher events;

    public AttachmentLedger(ParticipantRepository participants, AttachmentEntryRepository entries, PolicyApi policyApi,
                            ApplicationEventPublisher events) {
        this.participants = participants;
        this.entries = entries;
        this.policyApi = policyApi;
        this.events = events;
    }

    @Transactional
    public AttachmentEntry attach(String policyNumber, Declaration d, BigDecimal basis, BigDecimal amount, String createdBy) {
        return write(policyNumber, BonusEntryType.REVERSIONARY, amount, d.getValuationDate(), d.getDeclarationId(), basis,
            d.getReversionaryRatePercent(), "declaration", "declaration:" + d.getDeclarationId() + ":" + policyNumber,
            null, null, createdBy);
    }

    @Transactional
    public AttachmentEntry reverse(String policyNumber, AttachmentEntry original, LocalDate on, String reason, String createdBy) {
        return write(policyNumber, BonusEntryType.REVERSAL, original.getAmount().negate(), on, null, null, null,
            "reversal", "reversal:" + original.getEntryId(), original.getEntryId(), reason, createdBy);
    }

    private AttachmentEntry write(String policyNumber, BonusEntryType type, BigDecimal amount, LocalDate effectiveDate,
                                  UUID declarationId, BigDecimal basis, BigDecimal rate, String sourceType, String sourceRef,
                                  UUID reverses, String reason, String createdBy) {
        UUID tenantId = TenantContext.get();
        Participant head = participants.lockForPosting(policyNumber)
            .orElseThrow(() -> new BonusStateException("Policy " + policyNumber + " is not with-profits"));
        int seq = head.advance(amount);
        AttachmentEntry entry = entries.saveAndFlush(new AttachmentEntry(tenantId, policyNumber, seq, type, amount,
            head.getAttachedTotal(), effectiveDate, declarationId, basis, rate, sourceType, sourceRef, reverses, reason, createdBy));
        participants.save(head);
        policyApi.restateAttachedBonus(policyNumber, head.getAttachedTotal());
        // One event per entry: audit records every domain event, so this is the bonus audit trail.
        events.publishEvent(DomainEventEnvelope.of("bonus.BonusEntryRecorded", tenantId, Map.of(
            "policyNumber", policyNumber, "seq", seq, "type", type.name(),
            "amount", Map.of("amount", amount.toPlainString(), "currencyCode", head.getCurrency()),
            "totalAfter", Map.of("amount", head.getAttachedTotal().toPlainString(), "currencyCode", head.getCurrency()),
            "effectiveDate", effectiveDate.toString(), "sourceRef", sourceRef)));
        return entry;
    }
}
```

- [ ] **Step 5: Deciding one policy, and the drain**

Add to `BonusApiImpl`. Its constructor gains `ParticipantRepository`, `StatusEventRepository`, `AttachmentEntryRepository`, `DeclarationOutcomeRepository`, `AttachmentLedger`, `ProductApi`, and `@Lazy BonusApiImpl self`: the drain must call `attachOne` through the proxy, so each policy gets its own transaction. This is the self-invocation trap from the `default`-methods memory.

```java
    /**
     * One declaration, one policy: an outcome always, an entry only when a bonus attaches. Its own
     * transaction (REQUIRES_NEW through the proxy), so one policy's failure costs only that policy.
     * ux_declaration_outcome makes a re-run after a crash harmless.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void attachOne(UUID declarationId, String policyNumber) {
        UUID tenantId = TenantContext.get();
        Declaration d = load(declarationId);
        Participant p = participants.findById(policyNumber).orElseThrow();
        BonusPlan plan = productApi.resolveBonusPlan(p.getProductVersionId());
        List<Eligibility.StatusRow> rows = statusEvents.findByPolicyNumberOrderByEffectiveAtAsc(policyNumber).stream()
            .map(StatusEvent::toRow).toList();
        Optional<String> refusal = Eligibility.refusal(rows, d.getValuationDate(), plan.paidUpParticipates());
        if (refusal.isPresent()) {
            outcomes.save(new DeclarationOutcome(tenantId, declarationId, policyNumber, OutcomeKind.NOT_ELIGIBLE, refusal.get()));
            return;
        }
        BigDecimal sumAssured = Eligibility.sumAssuredOn(rows, d.getValuationDate());
        BigDecimal attachedBefore = entries.sumBefore(policyNumber, d.getValuationDate());
        BigDecimal amount = BonusArithmetic.reversionary(plan.method(), sumAssured, attachedBefore, d.getReversionaryRatePercent());
        if (amount.signum() == 0) {
            outcomes.save(new DeclarationOutcome(tenantId, declarationId, policyNumber, OutcomeKind.NOTHING_DUE, null));
            return;
        }
        BigDecimal basis = plan.method() == BonusMethod.COMPOUND ? sumAssured.add(attachedBefore) : sumAssured;
        outcomes.saveAndFlush(new DeclarationOutcome(tenantId, declarationId, policyNumber, OutcomeKind.ATTACHED, null));
        ledger.attach(policyNumber, d, basis, amount, "system:bonus-declaration");
    }

    /**
     * One batch of one declaration. Returns how many policies it DECIDED -- not how many it tried: a
     * policy that fails gets no outcome, so counting attempts would let one broken policy keep the
     * drain's loop spinning on it forever. Zero with an empty batch means complete.
     */
    public int drainDeclaration(UUID declarationId) {
        Declaration d = load(declarationId);
        List<String> batch = participants.awaitingOutcome(d.getProductId(), declarationId);
        if (batch.isEmpty()) {
            self.complete(declarationId);
            return 0;
        }
        int decided = 0;
        for (String policyNumber : batch) {
            try {
                self.attachOne(declarationId, policyNumber);
                decided++;
            } catch (Exception e) {
                log.error("Bonus declaration {} failed for policy {}", declarationId, policyNumber, e);
            }
        }
        return decided;
    }

    @Transactional
    public void complete(UUID declarationId) {
        Declaration d = load(declarationId);
        d.complete();
        declarations.save(d);
    }
```

A policy that fails in `attachOne` gets no outcome, so the declaration stays open and the next scheduled run retries it, logging it each time. `DeclarationDrain` stops looping as soon as a batch decides nothing, so a broken policy costs one log line per run, not a spin. Add `private static final Logger log` to `BonusApiImpl`.

```java
// bonus/application/DeclarationDrain.java
package tz.co.nlolo.lifeplatform.bonus.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.DeclarationRepository;

import java.util.UUID;

/**
 * Attaches every approved declaration whose valuation date has arrived, across tenants, each under
 * its own. MonthEndDrain's shape: SQL selects ids (declarations_due()), Java decides. A declaration
 * approved AFTER its valuation date is picked up on the next run, judged on the status record as at
 * that date -- which is why the record exists.
 */
@Component
public class DeclarationDrain {

    private static final Logger log = LoggerFactory.getLogger(DeclarationDrain.class);

    private final DeclarationRepository declarations;
    private final BonusApiImpl api;

    public DeclarationDrain(DeclarationRepository declarations, BonusApiImpl api) {
        this.declarations = declarations;
        this.api = api;
    }

    @Scheduled(fixedDelayString = "${bonus.declaration-interval-ms:3600000}",
        initialDelayString = "${bonus.declaration-interval-ms:3600000}")
    public void drain() {
        for (Object[] row : declarations.findDueAcrossTenants()) {
            drainOne((UUID) row[0], (UUID) row[1]);
        }
    }

    void drainOne(UUID declarationId, UUID tenantId) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            // Batches until a batch decides nothing new: then either it completed, or every
            // remaining policy is failing and is logged, and the next run tries again.
            while (api.drainDeclaration(declarationId) > 0) { /* next batch */ }
        } catch (Exception e) {
            log.error("Bonus declaration {} failed in tenant {}", declarationId, tenantId, e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
```

In `application-local.yml`, beside `accumulation.month-end-interval-ms`, add `bonus.declaration-interval-ms: 10000`.

- [ ] **Step 6: The integration test**

Header and migration list: `DeclarationIntegrationTest`'s, plus `db-migrations/policy/V32__attached_bonus_projection.sql` after V31. Autowire `DeclarationDrain drain`, `BonusApiImpl api`, `AttachmentEntryRepository entries`, `PolicyApi policyApi` and the fixtures.

```java
    private UUID approvedDeclaration(UUID product, LocalDate valuation, String rate) {
        var d = asTenant(() -> api.proposeDeclaration(product, valuation, new BigDecimal(rate), new BigDecimal("50"), "admin-one"));
        asTenant(() -> api.approveDeclaration(d.declarationId(), "finance-two"));
        return d.declarationId();
    }

    @Test
    void anApprovedDeclarationAttachesOnceAndTheProjectionFollows() {
        var issued = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        UUID declaration = approvedDeclaration(issued.productId(), LocalDate.now(), "3");
        drain.drainOne(declaration, TENANT);
        drain.drainOne(declaration, TENANT); // a re-run attaches nothing more
        var ledger = asTenant(() -> entries.findByPolicyNumberOrderBySeqAsc(issued.policyNumber()));
        assertThat(ledger).singleElement().satisfies(e -> {
            assertThat(e.getAmount()).isEqualByComparingTo("30000.00");
            assertThat(e.getBasisAmount()).isEqualByComparingTo("1000000.00");
        });
        assertThat(asTenant(() -> policyBonusAmount(issued.policyNumber()))).isEqualByComparingTo("30000.00");
        assertThat(asTenant(() -> api.listDeclarations(issued.productId())).get(0).completedAt()).isNotNull();
    }

    @Test
    void aDeclarationWithAFutureValuationDateWaits() {
        var issued = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        UUID declaration = approvedDeclaration(issued.productId(), LocalDate.now().plusDays(10), "3");
        drain.drain(); // declarations_due() does not select it
        assertThat(asTenant(() -> entries.findByPolicyNumberOrderBySeqAsc(issued.policyNumber()))).isEmpty();
    }

    @Test
    void aPolicyLapsedOnTheValuationDateGetsAnOutcomeThatSaysWhyAndNoEntry() {
        var issued = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        fixtures.publish(TENANT, "policy.PolicyLapsed", Map.of("policyNumber", issued.policyNumber(),
            "lapsedAt", java.time.Instant.now().toString()));
        UUID declaration = approvedDeclaration(issued.productId(), LocalDate.now(), "3");
        drain.drainOne(declaration, TENANT);
        assertThat(asTenant(() -> entries.findByPolicyNumberOrderBySeqAsc(issued.policyNumber()))).isEmpty();
        assertThat(asTenant(() -> outcomes.findByPolicyNumberOrderByDecidedAtDesc(issued.policyNumber())))
            .singleElement().satisfies(o -> assertThat(o.getReason()).isEqualTo("The policy was LAPSED on " + LocalDate.now()));
    }

    @Test
    void aPaidUpPolicyFollowsItsVersionsRule() {
        var off = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        var on = fixtures.issue(TENANT, new BonusPlan(true, BonusMethod.SIMPLE, true, BonusSurrenderBasis.NONE, List.of()),
            CashValuePlan.none());
        for (var p : List.of(off, on)) {
            fixtures.publish(TENANT, "policy.PolicyMadePaidUp", Map.of("policyNumber", p.policyNumber(),
                "paidUpSumAssured", Map.of("amount", "400000.00", "currencyCode", "TZS"),
                "originalSumAssured", Map.of("amount", "1000000.00", "currencyCode", "TZS"),
                "madePaidUpAt", java.time.Instant.now().toString()));
        }
        drain.drainOne(approvedDeclaration(off.productId(), LocalDate.now(), "3"), TENANT);
        drain.drainOne(approvedDeclaration(on.productId(), LocalDate.now(), "3"), TENANT);
        assertThat(asTenant(() -> entries.findByPolicyNumberOrderBySeqAsc(off.policyNumber()))).isEmpty();
        // On the REDUCED sum assured: 400,000 x 3%.
        assertThat(asTenant(() -> entries.findByPolicyNumberOrderBySeqAsc(on.policyNumber()))).singleElement()
            .satisfies(e -> assertThat(e.getAmount()).isEqualByComparingTo("12000.00"));
    }
```

`policyBonusAmount` reads the projection through an autowired `PolicyBonusRepository`. `outcomes` is the autowired `DeclarationOutcomeRepository`. The "reinstated before and after" and "issued after" cases are covered with past timestamps in `EligibilityTest`, because a drain test can't publish an issuance in the past: `issuePolicy` dates its own event now.

- [ ] **Step 7: Test classes that reach `restateAttachedBonus`**

Only the bonus tests do, and they apply V32. No other class changes in this task.

- [ ] **Step 8: Run and commit**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='BonusArithmeticTest,AttachmentIntegrationTest,EligibilityTest,BonusLedgerImmutabilityTest'
git add src/main db-migrations/policy/V32__attached_bonus_projection.sql src/test/java/tz/co/nlolo/lifeplatform/bonus src/main/resources/application-local.yml
git commit -m "feat(bonus): declarations attach to every eligible policy, once, and policy's projection follows the ledger"
```

---

### Task 6: Exits — maturity, the death limit, surrender, free-look; and the claim screen's figure

**Files:**
- Create: `bonus/api/BonusValuation.java`, `bonus/domain/Settlement.java`, `bonus/infrastructure/SettlementRepository.java`, `bonus/application/ClaimEventListener.java`
- Modify: `bonus/api/BonusApi.java`, `bonus/application/BonusApiImpl.java`, `bonus/application/PolicyEventListener.java`
- Modify: `benefitpayout/package-info.java`, `benefitpayout/application/BenefitPayoutApiImpl.java`
- Modify: `policy/api/SurrenderQuoteView.java`, `policy/application/PolicyApiImpl.java` (quote and request), `policy/infrastructure/PolicyController.java`, `api/openapi/openapi-policy.yaml`
- Modify: `claims/application/ClaimsApiImpl.java` (`claimableCover` uses the death limit too)
- Test: `BonusExitIntegrationTest.java`; extend `ClaimsApiIntegrationTest` (or whichever class tests `claimableCover`; find it with `grep -rl "claimableCover(" src/test/java`)

**Interfaces:**
- Produces: `record BonusValuation(BigDecimal attached, BigDecimal interim, BigDecimal terminal, BigDecimal interimRatePercent, BigDecimal terminalRatePercent, UUID declarationId)` with `BigDecimal total()` and `static BonusValuation none()`; `BonusApi.isParticipating(String policyNumber)`, `valueAt(String policyNumber, LocalDate date) -> BonusValuation`, `settle(String policyNumber, ExitType type, String exitRef, LocalDate exitDate) -> BonusValuation` (idempotent per exit ref); `SurrenderQuoteView` gains `BigDecimal bonusSurrenderValueAmount` (last component).

- [ ] **Step 1: Write the failing exit test**

Header, migrations and helpers: `AttachmentIntegrationTest`'s, plus `benefitpayout/V1` (already in the list) and the claims migrations from any claims integration test's list. Autowire `BenefitPayoutApi benefitPayoutApi`.

```java
    /** A policy with 30,000 attached as at today, on a product whose last declaration also set a 50% terminal rate. */
    private BonusTestFixtures.Issued withOneBonus(BonusPlan plan, CashValuePlan cashValue) {
        var issued = fixtures.issue(TENANT, plan, cashValue);
        drain.drainOne(approvedDeclaration(issued.productId(), LocalDate.now(), "3"), TENANT);
        return issued;
    }

    @Test
    void theDeathLimitAddsAttachedInterimAndTerminalAsAtTheDeath() {
        var issued = withOneBonus(BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        LocalDate death = LocalDate.now().plusMonths(5);
        // attached 30,000; interim (1,000,000 + 30,000) x 3% x 5/12 = 12,875.00; terminal 50% x 30,000 = 15,000.
        var v = asTenant(() -> bonusApi.valueAt(issued.policyNumber(), death));
        assertThat(v.attached()).isEqualByComparingTo("30000.00");
        assertThat(v.interim()).isEqualByComparingTo("12875.00");
        assertThat(v.terminal()).isEqualByComparingTo("15000.00");
        assertThat(asTenant(() -> benefitPayoutApi.deathBenefitCeiling(issued.policyNumber(), new BigDecimal("1000000.00"), death)))
            .isEqualByComparingTo("1057875.00");
    }

    @Test
    void aLapsedPolicyKeepsItsBonusesButEarnsNoInterim() {
        var issued = withOneBonus(BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        fixtures.publish(TENANT, "policy.PolicyLapsed", Map.of("policyNumber", issued.policyNumber(),
            "lapsedAt", java.time.Instant.now().toString()));
        var v = asTenant(() -> bonusApi.valueAt(issued.policyNumber(), LocalDate.now().plusMonths(5)));
        assertThat(v.attached()).isEqualByComparingTo("30000.00");
        assertThat(v.interim()).isEqualByComparingTo("0.00");
        assertThat(v.terminal()).isEqualByComparingTo("15000.00");
    }

    @Test
    void aSettlementIsRecordedOncePerExit() {
        var issued = withOneBonus(BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        UUID claimId = UUID.randomUUID();
        fixtures.publish(TENANT, "claims.ClaimApproved", Map.of("claimId", claimId, "policyNumber", issued.policyNumber(),
            "claimType", "DEATH", "dateOfEvent", LocalDate.now().toString(),
            "approvedAmount", Map.of("amount", "1045000.00", "currencyCode", "TZS")));
        fixtures.publish(TENANT, "claims.ClaimApproved", Map.of("claimId", claimId, "policyNumber", issued.policyNumber(),
            "claimType", "DEATH", "dateOfEvent", LocalDate.now().toString(),
            "approvedAmount", Map.of("amount", "1045000.00", "currencyCode", "TZS")));
        assertThat(asTenant(() -> settlements.findByPolicyNumberOrderByRecordedAtDesc(issued.policyNumber()))).hasSize(1);
    }

    @Test
    void theSurrenderQuoteAddsTheVersionsStatedBasisAndNothingElse() {
        // NONE: nothing added.
        var none = withOneBonus(BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        assertThat(asTenant(() -> policyApi.quoteSurrenderValue(none.policyNumber())).bonusSurrenderValueAmount())
            .isEqualByComparingTo("0");
        // OWN_SCALE from year 0 at 400 per mille: 30,000 x 0.4 = 12,000.
        var own = withOneBonus(new BonusPlan(true, BonusMethod.COMPOUND, false, BonusSurrenderBasis.OWN_SCALE,
            List.of(new BonusSurrenderRow(0, new BigDecimal("400")))), CashValuePlan.none());
        assertThat(asTenant(() -> policyApi.quoteSurrenderValue(own.policyNumber())).bonusSurrenderValueAmount())
            .isEqualByComparingTo("12000.00");
    }

    @Test
    void aFreeLookCancellationReversesEveryAttachment() {
        var issued = withOneBonus(BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        fixtures.publish(TENANT, "policy.PolicyCancelledFreeLook", Map.of("policyNumber", issued.policyNumber(),
            "cancelledAt", java.time.Instant.now().toString(), "cancelledBy", "staff-one"));
        var ledger = asTenant(() -> entries.findByPolicyNumberOrderBySeqAsc(issued.policyNumber()));
        assertThat(ledger).extracting(e -> e.type().name()).containsExactly("REVERSIONARY", "REVERSAL");
        assertThat(ledger.get(1).getTotalAfter()).isEqualByComparingTo("0.00");
        assertThat(asTenant(() -> policyBonusAmount(issued.policyNumber()))).isEqualByComparingTo("0.00");
    }

    @Test
    void aMaturityPaysTheRowAmountPlusTheBonus() {
        var issued = withOneBonus(BonusTestFixtures.SIMPLE_NONE, CashValuePlan.none());
        UUID instalment = asTenant(() -> maturityInstalmentId(issued.policyNumber()));
        // Bring it due today: the test helper the step 2 payout tests use to move a due date (see below).
        payoutFixtures.makeDueToday(TENANT, instalment);
        asTenant(() -> { benefitPayoutApiImpl.fallDue(instalment); return null; });
        // 1,000,000 row + 30,000 attached + 0 interim (0 whole months) + 15,000 terminal.
        assertThat(asTenant(() -> instalments.findById(instalment)).orElseThrow().getCurrentAmount())
            .isEqualByComparingTo("1045000.00");
    }
```

**Before writing `aMaturityPaysTheRowAmountPlusTheBonus`, find how step 2's tests bring a maturity instalment due.** Run `grep -rn "fallDue(" src/test/java/tz/co/nlolo/lifeplatform/benefitpayout`. Use that class's mechanism (a direct `UPDATE benefitpayout.payout_instalment SET due_date = current_date` through `JdbcTemplate` as the owner, or a fixture), and name it here in place of `payoutFixtures.makeDueToday`. `maturityInstalmentId` reads the MATURITY row through `PayoutInstalmentRepository.findByPolicyNumberOrderByDueDateAscRowOrderAsc`.

- [ ] **Step 2: Valuation and settlement in `bonus`**

```java
// bonus/api/BonusValuation.java
package tz.co.nlolo.lifeplatform.bonus.api;

import java.math.BigDecimal;
import java.util.UUID;

/** What a with-profits policy's bonuses are worth at an exit date (Q4, Q5). */
public record BonusValuation(BigDecimal attached, BigDecimal interim, BigDecimal terminal,
                             BigDecimal interimRatePercent, BigDecimal terminalRatePercent, UUID declarationId) {
    public static BonusValuation none() {
        return new BonusValuation(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null);
    }
    public BigDecimal total() { return attached.add(interim).add(terminal); }
}
```

```java
// bonus/domain/Settlement.java
package tz.co.nlolo.lifeplatform.bonus.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.bonus.api.BonusValuation;
import tz.co.nlolo.lifeplatform.bonus.api.ExitType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "settlement", schema = "bonus")
public class Settlement {
    @Id @UuidGenerator @Column(name = "settlement_id") private UUID settlementId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "exit_type", nullable = false) private String exitType;
    @Column(name = "exit_ref", nullable = false) private String exitRef;
    @Column(name = "exit_date", nullable = false) private LocalDate exitDate;
    @Column(name = "attached_amount", nullable = false) private BigDecimal attachedAmount;
    @Column(name = "interim_amount", nullable = false) private BigDecimal interimAmount;
    @Column(name = "terminal_amount", nullable = false) private BigDecimal terminalAmount;
    @Column(name = "interim_rate_percent") private BigDecimal interimRatePercent;
    @Column(name = "terminal_rate_percent") private BigDecimal terminalRatePercent;
    @Column(name = "declaration_id") private UUID declarationId;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();

    protected Settlement() {}

    public Settlement(UUID tenantId, String policyNumber, ExitType type, String exitRef, LocalDate exitDate, BonusValuation v) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.exitType = type.name();
        this.exitRef = exitRef;
        this.exitDate = exitDate;
        this.attachedAmount = v.attached();
        this.interimAmount = v.interim();
        this.terminalAmount = v.terminal();
        this.interimRatePercent = v.interimRatePercent();
        this.terminalRatePercent = v.terminalRatePercent();
        this.declarationId = v.declarationId();
    }

    public BonusValuation toValuation() {
        return new BonusValuation(attachedAmount, interimAmount, terminalAmount, interimRatePercent, terminalRatePercent, declarationId);
    }
    public ExitType type() { return ExitType.valueOf(exitType); }
    public String getExitRef() { return exitRef; }
    public LocalDate getExitDate() { return exitDate; }
    public Instant getRecordedAt() { return recordedAt; }
}
```

```java
// bonus/infrastructure/SettlementRepository.java
package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.bonus.domain.Settlement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementRepository extends JpaRepository<Settlement, UUID> {
    Optional<Settlement> findByExitTypeAndExitRef(String exitType, String exitRef);
    List<Settlement> findByPolicyNumberOrderByRecordedAtDesc(String policyNumber);
}
```

Add to `BonusApi`:

```java
    /** Whether this policy is on a with-profits version -- the cheap gate every caller asks first. */
    boolean isParticipating(String policyNumber);

    /** Attached as at the date, plus interim (if eligible on it), plus terminal. Reads only. */
    BonusValuation valueAt(String policyNumber, LocalDate date);

    /** valueAt, recorded once for this exit. A second call for the same exit returns the record. */
    BonusValuation settle(String policyNumber, ExitType type, String exitRef, LocalDate exitDate);
```

Add to `BonusApiImpl`:

```java
    @Override
    @Transactional(readOnly = true)
    public boolean isParticipating(String policyNumber) {
        return participants.existsById(policyNumber);
    }

    @Override
    @Transactional(readOnly = true)
    public BonusValuation valueAt(String policyNumber, LocalDate date) {
        Participant p = participants.findById(policyNumber).orElse(null);
        if (p == null) {
            return BonusValuation.none();
        }
        BigDecimal attached = entries.sumThrough(policyNumber, date).setScale(2);
        Optional<Declaration> last = declarations.latestApprovedOnOrBefore(p.getProductId(), date);
        if (last.isEmpty()) {
            return new BonusValuation(attached, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), null, null, null);
        }
        Declaration d = last.get();
        BonusPlan plan = productApi.resolveBonusPlan(p.getProductVersionId());
        List<Eligibility.StatusRow> rows = statusEvents.findByPolicyNumberOrderByEffectiveAtAsc(policyNumber).stream()
            .map(StatusEvent::toRow).toList();
        BigDecimal interim = BigDecimal.ZERO.setScale(2);
        if (Eligibility.refusal(rows, date, plan.paidUpParticipates()).isEmpty()) {
            // Since the LATER of the last valuation and the issue date (plan revision R2): a policy
            // issued after the last declaration earns interim only for the time it existed.
            LocalDate from = d.getValuationDate().isAfter(p.getIssuedOn()) ? d.getValuationDate() : p.getIssuedOn();
            interim = BonusArithmetic.interim(plan.method(), Eligibility.sumAssuredOn(rows, date), attached,
                d.getReversionaryRatePercent(), BonusArithmetic.wholeMonths(from, date));
        }
        BigDecimal terminal = BonusArithmetic.terminal(attached, d.getTerminalRatePercent());
        return new BonusValuation(attached, interim, terminal, d.getReversionaryRatePercent(), d.getTerminalRatePercent(),
            d.getDeclarationId());
    }

    @Override
    @Transactional
    public BonusValuation settle(String policyNumber, ExitType type, String exitRef, LocalDate exitDate) {
        return settlements.findByExitTypeAndExitRef(type.name(), exitRef).map(Settlement::toValuation).orElseGet(() -> {
            BonusValuation v = valueAt(policyNumber, exitDate);
            if (participants.existsById(policyNumber)) {
                settlements.saveAndFlush(new Settlement(TenantContext.get(), policyNumber, type, exitRef, exitDate, v));
            }
            return v;
        });
    }

    /** Free-look (Q of the spec's §5.3): the contract never was, so neither were its bonuses. */
    @Transactional
    public void reverseAllForFreeLook(String policyNumber, String cancelledBy) {
        if (!participants.existsById(policyNumber)) {
            return;
        }
        for (AttachmentEntry e : entries.unreversed(policyNumber)) {
            ledger.reverse(policyNumber, e, LocalDate.now(Eligibility.CIVIL_ZONE),
                "Cancelled in the free-look period", cancelledBy);
        }
    }
```

In `bonus/application/PolicyEventListener`, add a case ahead of the recorder:

```java
        if ("policy.PolicyCancelledFreeLook".equals(type)) {
            runner.run(envelope, e -> {
                recorder.record(e);
                @SuppressWarnings("unchecked") var p = (java.util.Map<String, Object>) e.payload();
                api.reverseAllForFreeLook((String) p.get("policyNumber"), (String) p.get("cancelledBy"));
            });
            return;
        }
```

(inject `BonusApiImpl api` through the constructor).

```java
// bonus/application/ClaimEventListener.java
package tz.co.nlolo.lifeplatform.bonus.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.bonus.api.ExitType;

import java.time.LocalDate;
import java.util.Map;

/** An approved DEATH claim records the bonus it was valued with. By envelope: bonus may not depend on claims. */
@Component("bonusClaimEventListener")
public class ClaimEventListener {

    private final BonusApiImpl api;
    private final BonusEnvelopeRunner runner;

    public ClaimEventListener(BonusApiImpl api, BonusEnvelopeRunner runner) {
        this.api = api;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"claims.ClaimApproved".equals(envelope.eventType())) {
            return;
        }
        runner.run(envelope, e -> {
            @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) e.payload();
            if (!"DEATH".equals(p.get("claimType"))) {
                return;
            }
            String policyNumber = (String) p.get("policyNumber");
            if (api.isParticipating(policyNumber)) {
                api.settle(policyNumber, ExitType.DEATH, String.valueOf(p.get("claimId")),
                    LocalDate.parse((String) p.get("dateOfEvent")));
            }
        });
    }
}
```

- [ ] **Step 3: The payout engine adds the bonus**

`benefitpayout/package-info.java`: `allowedDependencies = { "policy::api", "product::api", "accumulation::api", "bonus::api" }`. Inject `BonusApi bonusApi` into `BenefitPayoutApiImpl` through its constructor, after `accumulationApi`.

In `fallDue`, after the `accountValue` block and before `boolean upToDate = ...`:

```java
        if (i.kind() == PayoutKind.MATURITY && !accountValue && bonusApi.isParticipating(i.getPolicyNumber())) {
            // With-profits (product step 4): the maturity pays the row's figure PLUS the bonus --
            // attached, interim and terminal -- valued on the due date and recorded once against
            // this instalment.
            valued = i.getCurrentAmount().add(
                bonusApi.settle(i.getPolicyNumber(), ExitType.MATURITY, i.getInstalmentId().toString(), i.getDueDate()).total());
        }
```

Replace the 3-arg `deathBenefitCeiling`'s first branch:

```java
        if (!accumulationApi.isAccount(policyNumber)) {
            BigDecimal ceiling = deathBenefitCeiling(policyNumber, sumAssuredCeiling);
            // With-profits (product step 4): the bonus as at the death sits on top of the sum assured.
            return bonusApi.isParticipating(policyNumber)
                ? ceiling.add(bonusApi.valueAt(policyNumber, dateOfDeath).total()) : ceiling;
        }
```

Benefitpayout's own tests construct `BenefitPayoutApiImpl` through Spring, so the extra constructor argument needs nothing more there. Find any `new BenefitPayoutApiImpl(` in tests with grep and pass a mocked `BonusApi` whose `isParticipating` returns false.

- [ ] **Step 4: The claim screen shows what approval will allow**

This is plan revision R1: a pre-existing defect that bonuses would make worse. `ClaimsApiImpl.claimableCover(claimId)` returns the sum assured. But since step 2, `decideSettlement` caps a DEATH claim with `deathBenefitCeiling`. Its own comment says that if the two disagree, showing the figure is worse than showing nothing, and they now disagree. Extract the ceiling into one private method used by both:

```java
    /** The most this claim can pay -- the SAME figure decideSettlement enforces and claimableCover shows. */
    private BigDecimal ceilingFor(Claim claim, ClaimableCoverView claimable) {
        return claim.getClaimType() == ClaimType.DEATH
            ? benefitPayoutApi.deathBenefitCeiling(claim.getPolicyNumber(), claimable.amount(), claim.getDateOfEvent())
            : claimable.amount();
    }
```

In `decideSettlement`, `BigDecimal ceiling = ceilingFor(claim, claimable);` replaces the inline ternary, keeping its comment block above the call. In `claimableCover(UUID)`:

```java
        return new ClaimCoverView(ceilingFor(claim, cover), cover.currencyCode());
```

Test: in the class that already tests `claimableCover`, add a case where a death claim on a policy whose version authors `deathBenefitPremiumPercent` above the sum assured shows the floor, not the sum assured. Use step 2's existing fixture for that version (`grep -rn "deathBenefitPremiumPercent" src/test/java`).

- [ ] **Step 5: Surrender adds the stated basis**

`SurrenderQuoteView` gains a last component, `BigDecimal bonusSurrenderValueAmount`. In `PolicyApiImpl` (inject `PolicyBonusRepository`, already done in Task 5):

```java
    /**
     * What attached bonuses add to a surrender (product step 4, Q6): exactly the version's stated
     * basis, never assumed. The surrender CHARGE is not applied to it (plan revision R3): the bonus
     * scale is already the product's statement of what bonuses are worth on surrender.
     */
    private BigDecimal bonusSurrenderValue(Policy policy) {
        BonusPlan plan = productApi.resolveBonusPlan(policy.getProductVersionId());
        if (!plan.participating() || plan.surrenderBasis() == BonusSurrenderBasis.NONE) {
            return BigDecimal.ZERO;
        }
        BigDecimal attached = policyBonusRepository.findById(policy.getPolicyNumber())
            .map(PolicyBonus::getAttachedBonusAmount).orElse(BigDecimal.ZERO);
        if (attached.signum() == 0) {
            return BigDecimal.ZERO;
        }
        LocalDate start = policy.getCommencementDate() != null ? policy.getCommencementDate() : policy.getIssueDate();
        LocalDate paidToDate = policyValueRepository.findById(policy.getPolicyNumber()).map(PolicyValue::getPaidToDate).orElse(null);
        int completedYears = (paidToDate == null || start == null || paidToDate.isBefore(start))
            ? 0 : Period.between(start, paidToDate).getYears();
        BigDecimal perMille = switch (plan.surrenderBasis()) {
            case OWN_SCALE -> plan.ownScalePerMille(completedYears);
            case SUM_ASSURED_SCALE -> {
                var config = productApi.getCashValueConfig(policy.getProductVersionId());
                yield config.isPresent() && completedYears >= config.get().minYearsForValue()
                    ? productApi.resolveCashValuePerMille(policy.getProductVersionId(), completedYears,
                        ageAtEntryFor(policy, start)).orElse(BigDecimal.ZERO)
                    : BigDecimal.ZERO;
            }
            case NONE -> BigDecimal.ZERO;
        };
        return attached.multiply(perMille).divide(BigDecimal.valueOf(1000), 2, java.math.RoundingMode.HALF_EVEN);
    }
```

In `quoteSurrenderValue`: `BigDecimal bonusValue = bonusSurrenderValue(policy);` then `quotedValue = account.getCashValueAmount().subtract(charge).add(bonusValue)`, and pass `bonusValue` as the new last argument of `SurrenderQuoteView`. In `requestSurrender`: `BigDecimal quoted = account.getCashValueAmount().subtract(charge).add(bonusSurrenderValue(policy));`. Its "no cash value" check becomes `if (account.getCashValueAmount().signum() <= 0 && bonusSurrenderValue(policy).signum() <= 0)`, because a policy whose only surrender value is its bonuses still has one.

In `PolicyController.getSurrenderValue`, add `body.put("bonusSurrenderValue", Map.of("amount", quote.bonusSurrenderValueAmount().toPlainString(), "currencyCode", quote.quotedValueCurrency()));`. In `openapi-policy.yaml`'s `surrender-value` 200 schema, add `bonusSurrenderValue: { $ref: 'openapi-common.yaml#/components/schemas/Money', description: "Already included in quotedValue; shown on its own so the console can say what bonuses add" }`.

- [ ] **Step 6: Test classes that now reach `resolveBonusPlan` from policy or benefitpayout**

`quoteSurrenderValue`, `requestSurrender`, `fallDue` (through `isParticipating`, which reads `bonus.participant`) and the death ceiling now read new tables.

```bash
grep -rlE 'quoteSurrenderValue|requestSurrender|fallDue|deathBenefitCeiling|surrender-value|decideSettlement|claimable-cover' src/test/java | sort
```

Every listed class needs `product/V20`, `bonus/V1` and `policy/V32` in its migration list, in module order. This is the long sweep; it is what `ddl-auto: none` costs. Run `./mvnw -o clean test-compile` afterwards: an import or constructor that changed is caught there, not by a green scoped run.

- [ ] **Step 7: Run and commit**

```bash
./mvnw -o clean test-compile
./mvnw -o test -Dtest='BonusExitIntegrationTest,AttachmentIntegrationTest,ClosingIntegrationTest,ScheduleExpanderTest'
```

Then run each class the Step 6 grep listed, in batches of four, since cost is per class.

```bash
git add src/main src/test api/openapi/openapi-policy.yaml
git commit -m "feat(bonus): bonuses reach maturity, the death limit and the surrender quote -- and the claim screen shows the limit approval enforces"
```

---

### Task 7: Reading a policy's bonuses

**Files:**
- Create: `bonus/api/PolicyBonusView.java`, `BonusEntryView.java`, `BonusOutcomeView.java`, `BonusSettlementView.java`
- Create: `bonus/infrastructure/PolicyBonusResponse.java`, `BonusValuationResponse.java`
- Modify: `BonusApi`, `BonusApiImpl`, `BonusController`, `openapi-bonus.yaml`, `api/asyncapi-events.yaml`
- Test: extend `BonusContractTest`

**Interfaces:**
- Produces: `BonusApi.policyBonuses(String policyNumber) -> Optional<PolicyBonusView>` (empty when not participating); `record PolicyBonusView(String policyNumber, String currency, BigDecimal attachedTotal, List<BonusEntryView> entries, List<BonusOutcomeView> outcomes, List<BonusSettlementView> settlements)`; `GET /policies/{policyNumber}/bonuses` → 200 or 404 `NOT_WITH_PROFITS`; `GET /policies/{policyNumber}/bonuses/value?asOf=YYYY-MM-DD` → 200 `{attached, interim, terminal, total}` as Money.

- [ ] **Step 1: Contract tests first** (extend `BonusContractTest`):
- `GET /policies/{n}/bonuses` for a participating policy with one attachment → 200, validated, `entries[0].type == "REVERSIONARY"`, and `outcomes[0].outcome == "ATTACHED"`.
- the same for a non-participating policy → 404 with `errorCode` `NOT_WITH_PROFITS`.
- `GET /policies/{n}/bonuses/value?asOf=<today+5 months>` → 200, `total.amount` is the sum of the three parts.

- [ ] **Step 2: The views**

```java
// bonus/api/BonusEntryView.java
package tz.co.nlolo.lifeplatform.bonus.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BonusEntryView(UUID entryId, int seq, BonusEntryType type, BigDecimal amount, BigDecimal totalAfter,
                             LocalDate effectiveDate, UUID declarationId, BigDecimal basisAmount, BigDecimal ratePercent,
                             UUID reversesEntryId, String reason, String createdBy, Instant createdAt) {}
```

```java
// bonus/api/BonusOutcomeView.java
package tz.co.nlolo.lifeplatform.bonus.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Why a declaration did or did not attach -- the answer to "why did this policy get nothing?" */
public record BonusOutcomeView(UUID declarationId, LocalDate valuationDate, OutcomeKind outcome, String reason, Instant decidedAt) {}
```

```java
// bonus/api/BonusSettlementView.java
package tz.co.nlolo.lifeplatform.bonus.api;

import java.time.Instant;
import java.time.LocalDate;

public record BonusSettlementView(ExitType exitType, String exitRef, LocalDate exitDate, BonusValuation valuation, Instant recordedAt) {}
```

```java
// bonus/api/PolicyBonusView.java
package tz.co.nlolo.lifeplatform.bonus.api;

import java.math.BigDecimal;
import java.util.List;

public record PolicyBonusView(String policyNumber, String currency, BigDecimal attachedTotal, List<BonusEntryView> entries,
                              List<BonusOutcomeView> outcomes, List<BonusSettlementView> settlements) {}
```

`BonusApiImpl.policyBonuses` reads the participant, `entries.findByPolicyNumberOrderBySeqAsc`, `outcomes.findByPolicyNumberOrderByDecidedAtDesc` (joining each outcome to its declaration's valuation date through `declarations.findById`) and `settlements.findByPolicyNumberOrderByRecordedAtDesc`, and maps them field for field.

`BonusController` gains both GETs under `@PreAuthorize("hasRole('REALM_STAFF')")`. Any staff member may read, as with accumulation's account. `NOT_WITH_PROFITS` is a 404 from `ResponseEntity.notFound()` with a ProblemDetail body built the way `BonusExceptionHandler` builds one. The console's api layer turns it into `null` the way `getAccount` does. Money fields use a `MoneyResponse(String amount, String currencyCode)` record, as accumulation's does.

In `asyncapi-events.yaml`, register `bonus.BonusEntryRecorded` with the payload keys `AttachmentLedger` publishes.

- [ ] **Step 3: Run and commit**

```bash
./mvnw -o test -Dtest='BonusContractTest'
git add src/main/java/tz/co/nlolo/lifeplatform/bonus src/test/java/tz/co/nlolo/lifeplatform/bonus api/openapi/openapi-bonus.yaml api/asyncapi-events.yaml
git commit -m "feat(bonus): a policy's bonus history -- every entry, every declaration's outcome and why, every settlement"
```

---

### Task 8: The console

**Files:**
- Create: `frontend/src/api/bonus.ts`, `src/store/bonusStore.ts`, `src/gates/bonusGates.ts` (+ `.test.ts`)
- Create: `src/features/bonuses/BonusDeclarationsPanel.tsx`, `bonusDeclarationForm.ts`, `PolicyBonusesPanel.tsx` (+ tests)
- Modify: `src/api/types.ts`, `src/lib/status` (two kinds), `src/features/products/publishVersionSchema.ts` (+ test), `PublishVersionForm.tsx`, `ProductDetailPage.tsx`, `src/features/policies/PolicyDetailPage.tsx`, `ValueActions.tsx`

**Interfaces:**
- Consumes: the REST of Tasks 4 and 7; `bonusSurrenderValue` on the surrender quote (Task 6).

The rules for this task are the console's own. Copy `RateDeclarationsPanel`, `accumulationStore` and `accumulationGates` for shape, wording and behaviour, and invent nothing.

- [ ] **Step 1: Types.** Run `npm run generate:api`, which picks up `openapi-bonus.yaml` automatically. In `api/types.ts`, beside the accumulation aliases:

```ts
import type { components as BonusComponents } from '@/types/api/bonus';
export type BonusDeclarationView = BonusComponents['schemas']['Declaration'];
export type PolicyBonusView = BonusComponents['schemas']['PolicyBonuses'];
export type BonusValuationView = BonusComponents['schemas']['BonusValuation'];
```

Use the schema names Task 4 and Task 7 actually gave the OpenAPI components.

- [ ] **Step 2: The api layer**

```ts
// frontend/src/api/bonus.ts
import { get, post } from '@/lib/http';
import type { ApiError } from '@/lib/apiError';
import type { MutationAttempt } from '@/lib/idempotency';
import type { BonusDeclarationView, BonusValuationView, PolicyBonusView } from './types';

/**
 * With-profits bonuses (product step 4). Every mutation sends the store-minted Idempotency-Key, as
 * api/accumulation.ts explains.
 */

const declarations = (productId: string) => `/products/${encodeURIComponent(productId)}/bonus-declarations`;

export function listDeclarations(productId: string): Promise<BonusDeclarationView[]> {
  return get<BonusDeclarationView[]>(declarations(productId));
}

export interface DeclarationBody {
  valuationDate: string;
  reversionaryRatePercent: number;
  terminalRatePercent: number;
}

export function proposeDeclaration(productId: string, body: DeclarationBody, attempt: MutationAttempt): Promise<BonusDeclarationView> {
  return post<BonusDeclarationView>(declarations(productId), body, { headers: attempt.headers() });
}

export function approveDeclaration(declarationId: string, attempt: MutationAttempt): Promise<BonusDeclarationView> {
  return post<BonusDeclarationView>(`/bonus-declarations/${encodeURIComponent(declarationId)}/approve`, undefined, {
    headers: attempt.headers(),
  });
}

export function withdrawDeclaration(declarationId: string, attempt: MutationAttempt): Promise<BonusDeclarationView> {
  return post<BonusDeclarationView>(`/bonus-declarations/${encodeURIComponent(declarationId)}/withdraw`, undefined, {
    headers: attempt.headers(),
  });
}

function isNotFound(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as ApiError).kind === 'notFound';
}

/** The policy's bonuses, or null for a policy that is not with-profits -- an answer, not an error. */
export async function getPolicyBonuses(policyNumber: string): Promise<PolicyBonusView | null> {
  try {
    return await get<PolicyBonusView>(`/policies/${encodeURIComponent(policyNumber)}/bonuses`);
  } catch (error) {
    if (isNotFound(error)) return null;
    throw error;
  }
}

export function getBonusValue(policyNumber: string, asOf: string): Promise<BonusValuationView> {
  return get<BonusValuationView>(`/policies/${encodeURIComponent(policyNumber)}/bonuses/value`, { params: { asOf } });
}
```

- [ ] **Step 3: The store** — `bonusStore.ts`, built exactly as `accumulationStore.ts` with only the slots it needs: `declarations: Keyed<BonusDeclarationView[]>`, `policy: Keyed<PolicyBonusView | null>`, `acting: Keyed<unknown>`, plus `loadDeclarations`, `loadPolicy`, `proposeDeclaration`, `approveDeclaration` and `withdrawDeclaration`. Copy its `keyed` and `act` helpers verbatim, including the comment that `track()` never rethrows. **The Zustand rule from step 3's blank page applies:** no selector may return a fresh object or array (`s.policy[n]?.data ?? []` inside a selector re-renders forever). Select the resource, then derive outside the selector.

- [ ] **Step 4: Gates, test first**

```ts
// frontend/src/gates/bonusGates.test.ts
import { describe, expect, it } from 'vitest';
import type { BonusDeclarationView } from '@/api/types';
import { approveDeclarationGates } from './bonusGates';

const proposed = { declarationId: 'd1', status: 'PROPOSED', proposedBy: 'admin-one' } as BonusDeclarationView;

describe('approveDeclarationGates', () => {
  it('refuses the proposer in the server words', () => {
    const gates = approveDeclarationGates(proposed, 'admin-one');
    expect(gates.find((g) => !g.ok)?.detail).toBe(
      'A bonus declaration must be approved by someone other than the person who proposed it',
    );
  });
  it('lets a second person approve a proposal', () => {
    expect(approveDeclarationGates(proposed, 'finance-two').every((g) => g.ok)).toBe(true);
  });
  it('never treats an unknown viewer as the proposer', () => {
    expect(approveDeclarationGates(proposed, undefined).every((g) => g.ok)).toBe(true);
  });
  it('refuses anything but a proposal', () => {
    const gates = approveDeclarationGates({ ...proposed, status: 'APPROVED' }, 'finance-two');
    expect(gates.find((g) => !g.ok)?.detail).toBe('This bonus declaration is approved, not awaiting approval');
  });
});
```

`bonusGates.ts` is `approveRateGates` with the wording swapped for `Declaration`'s messages.

- [ ] **Step 5: The declarations panel.** `BonusDeclarationsPanel.tsx` is `RateDeclarationsPanel.tsx` with these differences, and no others:
- Each row shows `{reversionaryRatePercent}% reversionary · {terminalRatePercent}% terminal`, `As at {formatDate(valuationDate)}`, and, once `completedAt` is set, `· attached {formatDate(completedAt)}`.
- The list is `aria-label="Bonus declarations"`; its empty state says "No bonus declared" / "Policies on this product receive bonuses only when a declaration is approved and its valuation date arrives."
- The form fields are "Valuation date" (DatePicker), "Reversionary rate (%)" and "Terminal rate (% of attached bonuses)", and the button is "Propose declaration". Approve is "Approve declaration".
- `bonusDeclarationForm.ts` is `rateDeclarationForm.ts` with the two rate fields, using the ranges and messages of `proposeDeclaration`.

Add `bonusDeclaration` to `lib/status` with the same three states and tones as `rateDeclaration`. Check there's no existing key of that name first; a duplicate key was a step 3 bug. On `ProductDetailPage`, show a "Bonus declarations" panel wherever the page shows "Declared rates", gated by the same role check, and only when some version of the product is with-profits. Use the field the product detail exposes for a version's bonus terms; Task 1's resolve is not on the wire yet, so add `bonus` to the version's response in Task 1's Step 7 if the page has no other way to know.

- [ ] **Step 6: The policy's Bonuses tab.** `PolicyBonusesPanel.tsx`:
- The attached total as the headline figure.
- The ledger in sequence: `role="list" aria-label="Bonus history"`. Each entry shows its type label ("Reversionary bonus" / "Reversal"), amount, `total {formatMoney(totalAfter)}`, `as at {formatDate(effectiveDate)}`, and for a reversal `reverses #{seq}`.
- A "Declarations" list (`aria-label="Declaration outcomes"`) with each outcome. For NOT_ELIGIBLE it shows the server's reason verbatim.
- A "Paid out" section listing settlements (attached / interim / terminal lines).

On `PolicyDetailPage`, load it with the account and show the tab only when the read is non-null, exactly as the Account tab does (`...(policyBonuses?.data ? [{ value: 'bonuses', label: 'Bonuses', ... }] : [])`).

- [ ] **Step 7: Surrender shows what bonuses add.** In `ValueActions.tsx`, where the surrender amount is shown, add one line when `quote.bonusSurrenderValue` is non-zero: `Includes {formatMoney(quote.bonusSurrenderValue)} for attached bonuses`. The claim screen needs nothing: Task 6 made its "Covered for" figure the death limit, bonus included.

- [ ] **Step 8: The publish form.** In `publishVersionSchema.ts`, add a with-profits section that mirrors `BonusPlanValidator` message for message, as step 3's `validateAccumulation` mirrors `AccumulationPlanValidator`. Fields: `withProfits` (checkbox), `bonusMethod`, `paidUpParticipates`, `bonusSurrenderBasis` (no default — an empty select), and `bonusSurrenderRows`. Write the `publishVersionSchema.test.ts` cases first, one per validator message. `PublishVersionForm.tsx` gets the section beside the value-basis section, and the payload builder sends `bonus` only when `withProfits` is ticked. **openapi-typescript makes a property with `default:` REQUIRED in the generated request type**, so the OpenAPI in Task 1 deliberately has none.

- [ ] **Step 9: Run and commit**

```bash
cd frontend
npm run typecheck ; npm run lint ; npx vitest run src/gates/bonusGates.test.ts src/features/bonuses src/features/products/publishVersionSchema.test.ts src/features/policies
git add src
git commit -m "feat(console): bonus declarations on the product, a Bonuses tab on the policy, and what bonuses add to a surrender"
```

Never run Prettier.

---

### Task 9: Seed and e2e

**Files:**
- Modify: `backend/scripts/seed-dev-data.sh` (product `WP-ENDOW-01`)
- Create: `frontend/e2e/staff-with-profits.spec.ts`; add `WITH_PROFITS_PRODUCT` beside `SAVINGS_PRODUCT` in `e2e/underwriting.ts`

- [ ] **Step 1: Seed.** Copy `SAVE-PLAN-01`'s block in `seed-dev-data.sh`. It becomes `WP-ENDOW-01`, an ENDOWMENT with a PERCENT_OF_SA MATURITY row at 100, `bonus: {method: "COMPOUND", paidUpParticipates: false, surrenderBasis: "OWN_SCALE", surrenderRows: [{fromCompletedYears: 0, perMille: 400}]}`, and the same free-look days and filing. Keep the "already seeded" guard's shape. Apply product V20, bonus V1 and policy V32 to the dev database by hand (`psql -f`), then restart the dev backend: the dev stack doesn't run migrations, and a new endpoint is invisible until it does both.

- [ ] **Step 2: The spec**

```ts
// frontend/e2e/staff-with-profits.spec.ts
import { expect, test } from '@playwright/test';
import { asAdmin } from './admin';
import { dmy } from './dates';
import { caseAwaitingManualIssue, selectUnderwritingCase, WITH_PROFITS_PRODUCT } from './underwriting';

/**
 * A with-profits endowment through the real stack (product step 4): a declaration proposed by one
 * person and approved by another, attached by the drain (every ten seconds under `local`), and seen
 * on the policy's Bonuses tab and in its surrender quote.
 */
test.describe('a with-profits policy', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });
  test.setTimeout(240_000);

  test('receives an approved bonus and shows it on the policy', async ({ page, browser }) => {
    const today = new Date().toISOString().slice(0, 10);
    const policyNumber = await asAdmin(browser, async (admin) => {
      const caseId = await caseAwaitingManualIssue(admin, '1000000.00', WITH_PROFITS_PRODUCT);
      await admin.goto('/staff/policies/new');
      await selectUnderwritingCase(admin, caseId);
      await expect(admin.getByText('Resolving product version…')).not.toBeVisible();
      await admin.getByLabel('Sum assured').fill('1000000.00');
      await admin.getByLabel('Premium', { exact: true }).fill('50000.00');
      await admin.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
      await admin.getByLabel('Reason for manual issue').fill('E2E fixture: with-profits');
      await admin.getByLabel('Commencement date').fill(dmy(today));
      await admin.getByLabel('Policy term (months)').fill('180');
      await admin.getByRole('button', { name: 'Issue policy' }).click();
      await expect(admin).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 60_000 });
      const number = admin.url().split('/').pop() as string;
      // The admin proposes; the finance officer (this test's own identity) approves below.
      await admin.goto(`/staff/products`);
      await admin.getByRole('link', { name: new RegExp(WITH_PROFITS_PRODUCT) }).first().click();
      await admin.getByLabel('Valuation date').fill(dmy(today));
      await admin.getByLabel('Reversionary rate (%)').fill('3');
      await admin.getByLabel('Terminal rate (% of attached bonuses)').fill('50');
      await admin.getByRole('button', { name: 'Propose declaration' }).click();
      return number;
    });

    // Approve as a second person -- and the proposer's own refusal is the gate's, in the server's words.
    await page.goto('/staff/products');
    await page.getByRole('link', { name: new RegExp(WITH_PROFITS_PRODUCT) }).first().click();
    const declarations = page.getByRole('list', { name: 'Bonus declarations' });
    await declarations.getByRole('button', { name: 'Approve declaration' }).first().click();

    // Attached by the drain -- a poll, not an assumption about timing.
    await page.goto(`/staff/policies/${policyNumber}`);
    await expect(async () => {
      await page.reload();
      await page.getByRole('tab', { name: 'Bonuses' }).click();
      await expect(page.getByRole('list', { name: 'Bonus history' }).getByText('Reversionary bonus')).toBeVisible();
    }).toPass({ timeout: 90_000 });
    // 1,000,000 x 3%.
    await expect(page.getByText('TZS 30,000.00').first()).toBeVisible();
  });
});
```

**Before running, check:**
- Every label and accessible name above against what Task 8 actually rendered. Every console rewrite so far has broken e2e through a name.
- That the product list links by code. Copy `staff-savings-account.spec.ts` and the staff-products spec for how they open a product. If declarations from earlier runs pile up on `WP-ENDOW-01` (one approved per valuation date, so a second run on the same day is refused), the spec must create its own product the way `createRealActiveProduct` does, rather than reuse the seed.

- [ ] **Step 3: Run it alone, then commit**

```bash
cd frontend
npx playwright test e2e/staff-with-profits.spec.ts
git add e2e src ../backend/scripts/seed-dev-data.sh
git commit -m "test(e2e): a with-profits policy receives an approved bonus, seen on its Bonuses tab"
```

---

### Task 10: The gate

- [ ] Stop the dev backend (non-redhat `java` processes only, identified by command line).
- [ ] `./mvnw -B -o clean test` in the background (~85 minutes). No edits while it runs. Expected: green, with the count above step 3's 1,771.
- [ ] Restart the dev backend. Run the full Playwright suite on a quiet machine, with the setup project (no `--no-deps`). Every failure gets diagnosed before merging; a "pre-existing" one is still a bug.
- [ ] Whole-branch review of the seams:
  - every `bonus` listener filters on its event type, and the status record never sees a non-participating policy;
  - every new-table reader's test class applies its migration;
  - `claimableCover` and `decideSettlement` share `ceilingFor`;
  - both surrender paths call `bonusSurrenderValue`;
  - maturity adds the bonus once and `settle` is once-only;
  - no selector returns a fresh object.
- [ ] Merge `product-step4-bonuses` into `main` with `--no-ff` and a message carrying the gate figures.
- [ ] Update memory: step 4 status, and anything the gate found.

---

## 11. Revisions made while planning (2026-10-02)

- **R1 — the claim screen's figure.** `ClaimsApiImpl.claimableCover` shows the sum assured, but `decideSettlement` has capped DEATH claims with `deathBenefitCeiling` since step 2 (the premium floor) and step 3 (the account). The screen and the approval already disagree. Bonuses would widen that gap, so Task 6 makes both read one `ceilingFor`. This fixes the spec's "claims shows the assessor the breakdown" by making the shown figure the true limit, rather than adding a second figure beside a wrong one.
- **R2 — interim from the later of the valuation date and the issue date.** The spec says "since the last valuation date". Read literally, a policy issued two months before a death would earn interim for the eleven months since a valuation that predates it. The plan counts from the later of the two.
- **R3 — the surrender charge applies to the cash value only.** The version's bonus surrender basis (Q6) is already the product's statement of what bonuses are worth on surrender. Charging on top would discount them twice. Flagged to the user rather than buried.
- **R4 — a `declaration_outcome` table** the spec did not name. Without a record of "decided, not eligible", the drain would revisit every ineligible policy forever and could never complete a declaration. It also gives the Bonuses tab its answer to "why did this policy get nothing?"
- **R5 — the status record holds participating policies only**, keyed off a `participant` row written at issue. The spec's table would have recorded every policy on the platform.
- **R6 — policy's projection is its own table (`policy.policy_bonus`)**, not the spec's column on `policy_account`. `ddl-auto: none` would break every test class that issues a policy on a column it lacks. This is the same reason step 3 kept its terms off `product_version`.
- **Loans are unchanged.** A loan's limit still reads the cash value only, and attached bonuses do not raise it. The spec doesn't ask for this; if wanted, it is one line in `availableLoanValue` and a decision for the user.
