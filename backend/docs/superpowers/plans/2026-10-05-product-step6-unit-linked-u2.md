# Unit-linked U2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add fund switches, partial withdrawals, top-ups, surrender charges, premium redirection and unit statements to the U1 unit-linked engine, per spec `2026-10-05-product-step6-unit-linked-u2-design.md` (commit 24e57c51).

**Architecture:** Everything lives in the existing `unitlinked` module and reuses U1's machinery — `BindingRule`, `PendingOrder`, `UnitLedger.execute`, `PricingRun`, the append-only `unit_entry`, the `fund_liability` true-up, `Exits`, and `unitlinked.PayoutRequested`/`PayoutPaid`. Product terms for U2 sit in their own tables (`product.unit_linked_options`, `product.unit_linked_surrender_charge`), carried on `UnitLinkedPlan` as one new `options` component. One table per request type (switch, withdrawal, top-up), redirection as a split history, statements as records over filed PDFs.

**Tech Stack:** Java 21, Spring Boot, Spring Modulith, JPA/Hibernate, PostgreSQL 16 (Testcontainers), PDFBox, React + TypeScript + zod + react-hook-form, Playwright.

## Global Constraints

- **Forward pricing everywhere:** nothing is bought or sold at a price that exists when it is asked for. A missing price never becomes zero and never falls back to an older price.
- **A switch prices both legs on the same valuation date:** the latest of the involved funds' bound dates; executes at the first date ≥ that on which every involved fund has an APPROVED price.
- **Switch charge:** `free_switches_per_year` free per policy year, then `switch_fee` per switch.
- **Withdrawals:** named funds, else pro rata by value at the latest approved prices. Gross amount; the customer receives gross − surrender charge. One requests, a second (FINANCE_OFFICER or ADMIN) approves; the sale binds at the approval instant.
- **`withdrawal_reduces_sum_assured`:** per version, default false.
- **Top-ups:** their own `top_up_allocation_percent` and `minimum_top_up`; the Idempotency-Key is read server-side.
- **Surrender charge:** on full surrenders, partial withdrawals and NON-PAYMENT lapses with value; NEVER on an exhausted-fund lapse, death, maturity or free-look.
- **Redirection and switches:** one staff member, audited. Withdrawals: two people.
- **Statements:** calendar-year for every policy (annual, with SMS) PLUS on-demand for any period (no SMS). On-demand adds; it never replaces.
- **Published terms are immutable in the database** (triggers refuse UPDATE/DELETE). Every read resolves terms from the policy's `product_version_id`.
- Decimals on the unit-linked wire are strings (`@JsonFormat(shape = STRING)`), as in U1.
- Edit files with the Write/Edit tools, never shell strings. Never run Prettier. Run test classes by **explicit name** with `-Dsurefire.failIfNoSpecifiedTests=true`. `./mvnw -o clean test-compile` after any record or signature change.
- Every collection consumer filters `payment.PaymentConfirmed` by `purpose`.

## Plan corrections against the spec (decided while reading the code)

- **R1 — U2 terms in their own tables, not columns on `unit_linked_terms`.** `unit_linked_terms` is a 1:1 row per version with U1 rules; adding nullable U2 columns would need a backfill UPDATE of published rows. A separate `product.unit_linked_options` (1:1, optional) and `product.unit_linked_surrender_charge` mean a U1 version simply has no options row = every U2 feature off, with **no backfill at all** — the spec's "one honest exception" disappears.
- **R2 — `UnitLinkedPlan` gains one component, `options`, defaulted.** `new UnitLinkedPlan(` has 2 callers (inside the record); `UnitLinkedPlan.of(...)` (13 args) keeps its signature and delegates with `UnitLinkedOptions.none()`; a `withOptions(UnitLinkedOptions)` wither carries U2 terms. No publishVersion overload is added.
- **R3 — Switches are NOT pending orders.** A pending order prices when its own fund prices; a switch must wait for every involved fund. Switches have their own `switch_request`/`switch_leg` tables and their own executor, `Switches.onPriceApproved`, called at the end of `PricingRun.onApproved`.
- **R4 — Withdrawals ARE pending orders:** a new `Purpose.WITHDRAWAL` amount-sell, which `UnitLedger.execute` already caps at the holding. `Exits.afterPriced` must ignore it (it currently throws "has no exit").
- **R5 — Reducing cover needs a new policy path.** `Policy.restateSumAssured` is scheme-only and `applyEndorsement` changes nothing. A new `PolicyApi.reduceUnitLinkedSumAssured` restates `policy.sum_assured_amount` AND the DEATH `policy.coverage` row, and records an endorsement.
- **R6 — Statements file as `DocumentType.ACCOUNT_STATEMENT`** (the existing statement type; no document migration).

## Pre-start check against the code (2026-10-05, before any code was written)

Confirmed: the U1 constraint names (`unit_entry_check`, `unit_entry_entry_type_check`, `pending_order_purpose_check`,
`payment_transaction_purpose_check`); WITHDRAWAL_PAYOUT and PREMIUM_RETURN_PAYOUT already allowed and
`unitlinked.PayoutRequested` already routed; `FundPriceRepository.findApproved`, `UnitEntryRepository.holdings/sumUnits`,
`Coverage.restateSumAssured`, `unitlinked.unit_linked_policies()`; `payment.PaymentFailed` carries `purpose`.
Corrections:

- **D1** WAITING switches are NOT shown as pending orders (that list's `side` is BUY|SELL in the API): `PolicyUnitsView`
  gains its own `switches` list (`SwitchView`), built in `UnitLinkedApiImpl.units`, and the OpenAPI schema with it.
- **D2** The statement template is `UNIT_LINKED_STATEMENT`, four rows (SMS and EMAIL, `sw` and `en`), in
  `communication.notification_template (tenant_id, template_key, channel, language, body_template)` like U1's V13.
- **D3** No ascending "approved on or after" query exists (`findApprovedAfter` is strictly after, newest first):
  add `FundPriceRepository.findApprovedFrom(tenantId, fundId, from)` ordered ascending.
- **D4** `findLatestApprovedBefore` is STRICTLY before: a statement's "latest approved price on or before D" calls it
  with `D.plusDays(1)`.
- **D5** `FundLiability.adjust(BigDecimal)` and `UnitEntryRepository.sumUnitsAsOf(...)` are new.
- **D6** unitlinked's `PaymentEventListener` handles only `DisbursementCompleted`: it gains `PaymentConfirmed` and
  `PaymentFailed`, both filtered to `purpose = UL_TOP_UP` before anything is read.
- **D7** Cutting cover reuses `Coverage.restateSumAssured` on the active DEATH coverage rows.
- **Branch:** `product-step6-u2` is cut from `product-step6-unit-linked` at 4416ba1d (U1's loan-race and test-cache
  fixes are not on main yet); main is merged in once U1 lands.

---

## File structure

**Backend — product**
- Create `db-migrations/product/V26__unit_linked_options.sql` — options, surrender-charge bands, immutability triggers on all seven unit-linked terms tables.
- Create `product/api/UnitLinkedOptions.java` — the U2 terms record.
- Modify `product/api/UnitLinkedPlan.java` — `options` component, `withOptions`, `surrenderChargePercent(int policyYear)`.
- Create `product/domain/UnitLinkedOptionsEntity.java`, `product/domain/UnitLinkedSurrenderChargeEntity.java`; repositories in `product/infrastructure/`.
- Modify `product/application/UnitLinkedTermsStore.java` (persist/read options), `product/domain/UnitLinkedPlanValidator.java` (validate options), `product/infrastructure/UnitLinkedRequest.java` (an `options` block).

**Backend — unitlinked**
- Create `db-migrations/unitlinked/V3__u2.sql` — `premium_split`, `switch_request`, `switch_leg`, `withdrawal_request`, `withdrawal_fund`, `top_up`, `request_key`, `unit_statement`; new entry types and order purposes; `exit_state.surrender_charge`.
- Create domain: `PremiumSplit`, `SwitchRequest`, `SwitchLeg`, `WithdrawalRequest`, `WithdrawalFund`, `TopUp`, `UnitStatement`, `RequestKey`; repositories.
- Create application: `PremiumSplits`, `Switches`, `Withdrawals`, `SurrenderCharges`, `TopUps`, `IdempotentRequests` (unitlinked's own), `Statements`, `StatementPdf`, `StatementDrain`.
- Modify `UnitEntry` (types), `PendingOrder` (purpose, `sellAmount`), `UnitLedger.saleType`, `PricingRun.onApproved` (call `Switches`), `Allocations` (split history, top-ups), `Exits` (charge on SURRENDER and LAPSE, cancel waiting switches/withdrawals on freeze, ignore WITHDRAWAL in `afterPriced`), `UnitLinkedApi`/`UnitLinkedApiImpl`, `FundController`, `PaymentEventListener`, `UnitsView` building.

**Backend — elsewhere**
- `db-migrations/payment/V14__unit_linked_top_up.sql` — `UL_TOP_UP` collection purpose.
- `payment/application/PaymentRequestListener.java` — `unitlinked.TopUpRequested`.
- `policy/api/PolicyApi.java`, `policy/application/PolicyApiImpl.java`, `policy/domain/Policy.java` — `reduceUnitLinkedSumAssured`.
- `finaccounting/application/UnitLinkedEventListener.java` — the new postings.
- `db-migrations/communication/V14__unit_linked_statement_template.sql`, `communication/application/UnitLinkedEventListener.java` — `UL_STATEMENT`.
- `api/openapi/openapi-unitlinked.yaml`, `openapi-product.yaml`, `api/asyncapi-events.yaml`.

**Frontend**
- `src/features/products/unitLinkedSchema.ts`, `UnitLinkedTermsSection.tsx` — options fields.
- `src/api/unitlinked.ts`, `src/store/unitLinkedStore.ts`, `src/api/types.ts`.
- Create `src/features/unitlinked/SwitchForm.tsx`, `WithdrawalPanel.tsx`, `TopUpForm.tsx`, `PremiumSplitPanel.tsx`, `StatementsPanel.tsx`, `u2Forms.ts` (+ tests); `src/gates/unitLinkedGates.ts` (withdrawal approval).
- Modify `src/features/unitlinked/PolicyUnitsPanel.tsx`.
- `e2e/staff-unit-linked-u2.spec.ts`; `backend/scripts/seed-dev-data.sh` (UL-INV-01 options).

---

### Task 0: Branch

- [ ] **Step 1:** After U1 (`product-step6-unit-linked`) is merged to main and pushed:

```bash
cd /c/Users/USER/Desktop/digital-life-insurance-platform
git worktree add .worktrees/product-step6-u2 -b product-step6-u2 main
cmd //c mklink /J .worktrees\\product-step6-u2\\frontend\\node_modules frontend\\node_modules
```

- [ ] **Step 2:** `cd .worktrees/product-step6-u2/backend && ./mvnw -o -q clean test-compile` — expect exit 0.

---

### Task 1: U2 version terms, and published terms made immutable

**Files:**
- Create: `backend/db-migrations/product/V26__unit_linked_options.sql`
- Create: `product/api/UnitLinkedOptions.java`, `product/domain/UnitLinkedOptionsEntity.java`, `product/domain/UnitLinkedSurrenderChargeEntity.java`, `product/infrastructure/UnitLinkedOptionsRepository.java`, `product/infrastructure/UnitLinkedSurrenderChargeRepository.java`
- Modify: `product/api/UnitLinkedPlan.java`, `product/application/UnitLinkedTermsStore.java`, `product/domain/UnitLinkedPlanValidator.java`, `product/infrastructure/UnitLinkedRequest.java`
- Modify: `src/test/java/.../unitlinked/UnitLinkedTestMigrations.java` (add V26)
- Test: `product/UnitLinkedPlanValidatorTest.java`, `unitlinked/UnitLinkedOptionsIntegrationTest.java`

**Interfaces — Produces:**
- `record UnitLinkedOptions(Integer freeSwitchesPerYear, BigDecimal switchFee, BigDecimal minimumWithdrawal, BigDecimal minimumRemainingValue, boolean withdrawalReducesSumAssured, BigDecimal topUpAllocationPercent, BigDecimal minimumTopUp, List<SurrenderChargeBand> surrenderCharges)` with `record SurrenderChargeBand(int fromYear, Integer toYear, BigDecimal percent)`, `static UnitLinkedOptions none()`, `boolean switchingOffered()`, `boolean withdrawalsOffered()`, `boolean topUpsOffered()`, `BigDecimal surrenderChargePercent(int policyYear)` (ZERO when no band covers it).
- `UnitLinkedPlan.options()`, `UnitLinkedPlan withOptions(UnitLinkedOptions)`.

- [ ] **Step 1: Write the migration.**

```sql
-- db-migrations/product/V26__unit_linked_options.sql
-- Product step 6 (U2): what a UNIT_LINKED version offers beyond U1. Optional and 1:1 -- a version with no row
-- offers no switching, no withdrawals, no top-ups and no surrender charge (plan R1), so the versions published
-- under U1 keep exactly the contract they were sold with and nothing is backfilled. Each feature is off while
-- its own columns are null.
CREATE TABLE product.unit_linked_options (
    product_version_id              UUID PRIMARY KEY REFERENCES product.unit_linked_terms(product_version_id),
    tenant_id                       UUID NOT NULL,
    free_switches_per_year          INTEGER CHECK (free_switches_per_year IS NULL OR free_switches_per_year >= 0),
    switch_fee                      NUMERIC(19,2) CHECK (switch_fee IS NULL OR switch_fee >= 0),
    minimum_withdrawal              NUMERIC(19,2) CHECK (minimum_withdrawal IS NULL OR minimum_withdrawal > 0),
    minimum_remaining_value         NUMERIC(19,2) CHECK (minimum_remaining_value IS NULL OR minimum_remaining_value >= 0),
    withdrawal_reduces_sum_assured  BOOLEAN NOT NULL DEFAULT FALSE,
    top_up_allocation_percent       NUMERIC(7,4) CHECK (top_up_allocation_percent IS NULL
                                        OR (top_up_allocation_percent > 0 AND top_up_allocation_percent <= 100)),
    minimum_top_up                  NUMERIC(19,2) CHECK (minimum_top_up IS NULL OR minimum_top_up > 0),
    CHECK ((free_switches_per_year IS NULL) = (switch_fee IS NULL)),
    CHECK ((minimum_withdrawal IS NULL) = (minimum_remaining_value IS NULL)),
    CHECK ((top_up_allocation_percent IS NULL) = (minimum_top_up IS NULL))
);

CREATE TABLE product.unit_linked_surrender_charge (
    unit_linked_surrender_charge_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_version_id              UUID NOT NULL REFERENCES product.unit_linked_terms(product_version_id),
    tenant_id                       UUID NOT NULL,
    from_year                       INTEGER NOT NULL CHECK (from_year >= 1),
    to_year                         INTEGER CHECK (to_year IS NULL OR to_year >= from_year),
    charge_percent                  NUMERIC(7,4) NOT NULL CHECK (charge_percent >= 0 AND charge_percent <= 100),
    UNIQUE (product_version_id, from_year)
);

ALTER TABLE product.unit_linked_options ENABLE ROW LEVEL SECURITY;
CREATE POLICY unit_linked_options_tenant_isolation ON product.unit_linked_options
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.unit_linked_surrender_charge ENABLE ROW LEVEL SECURITY;
CREATE POLICY unit_linked_surrender_charge_tenant_isolation ON product.unit_linked_surrender_charge
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT ON product.unit_linked_options, product.unit_linked_surrender_charge TO app_role;

-- A version's terms are its contract with every policy sold on it (spec A2): once written, never changed.
-- A version is only ever written when it is published, so every row here is a published one.
CREATE OR REPLACE FUNCTION product.refuse_unit_linked_terms_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'A published unit-linked version''s terms are never changed (% on %); publish a new version',
        TG_OP, TG_TABLE_NAME;
END $$;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['unit_linked_terms','unit_linked_fund','unit_linked_allocation_band',
                             'unit_linked_mortality','unit_linked_premium_minimum',
                             'unit_linked_options','unit_linked_surrender_charge'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON product.%I
                        FOR EACH ROW EXECUTE FUNCTION product.refuse_unit_linked_terms_change()',
                       t || '_immutable', t);
    END LOOP;
END $$;
```

- [ ] **Step 2: Write `UnitLinkedOptions`.**

```java
package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * A UNIT_LINKED version's U2 terms (product V26, plan R1): switching, withdrawals, top-ups and the surrender charge.
 * Each feature is offered only when its own terms are present; {@link #none()} -- every version published before
 * U2 -- offers none of them and charges nothing.
 */
public record UnitLinkedOptions(Integer freeSwitchesPerYear, BigDecimal switchFee, BigDecimal minimumWithdrawal,
                                BigDecimal minimumRemainingValue, boolean withdrawalReducesSumAssured,
                                BigDecimal topUpAllocationPercent, BigDecimal minimumTopUp,
                                List<SurrenderChargeBand> surrenderCharges) {

    /** Policy years {@code fromYear} to {@code toYear} (null = onwards) charge {@code percent} of the value sold. */
    public record SurrenderChargeBand(int fromYear, Integer toYear, BigDecimal percent) {}

    public UnitLinkedOptions {
        surrenderCharges = surrenderCharges == null ? List.of() : List.copyOf(surrenderCharges);
    }

    public static UnitLinkedOptions none() {
        return new UnitLinkedOptions(null, null, null, null, false, null, null, List.of());
    }

    public boolean switchingOffered() { return freeSwitchesPerYear != null; }
    public boolean withdrawalsOffered() { return minimumWithdrawal != null; }
    public boolean topUpsOffered() { return topUpAllocationPercent != null; }

    /** The charge for a sale in policy year {@code policyYear} (1 = the first); zero when no band covers it. */
    public BigDecimal surrenderChargePercent(int policyYear) {
        return surrenderCharges.stream()
            .filter(b -> policyYear >= b.fromYear() && (b.toYear() == null || policyYear <= b.toYear()))
            .map(SurrenderChargeBand::percent).findFirst().orElse(BigDecimal.ZERO);
    }
}
```

- [ ] **Step 3: Add `options` to `UnitLinkedPlan` (R2).** Append the component `UnitLinkedOptions options` after `sumAssuredMultipleMax`; in the compact constructor `options = options == null ? UnitLinkedOptions.none() : options;`; `of(...)` passes `UnitLinkedOptions.none()`; `none()` passes `UnitLinkedOptions.none()`; add:

```java
    /** The same terms with U2's options (plan R2): of(...) keeps its U1 signature for every existing caller. */
    public UnitLinkedPlan withOptions(UnitLinkedOptions options) {
        return new UnitLinkedPlan(unitLinked, fundCodes, allocationBands, monthlyPolicyFee, mortalityBasis, mortality,
            deathRule, lapseRule, minimumPremiumYears, minimumSurrenderYears, lowFundWarningMonths, premiumMinimums,
            sumAssuredMultipleMin, sumAssuredMultipleMax, options);
    }
```

- [ ] **Step 4: Entities and repositories.** `UnitLinkedOptionsEntity` (`@Table(name="unit_linked_options", schema="product")`, `@Id product_version_id`, the eight columns, a constructor `(UUID tenantId, UUID productVersionId, UnitLinkedOptions o)` and `UnitLinkedOptions toOptions(List<SurrenderChargeBand> bands)`). `UnitLinkedSurrenderChargeEntity` mirrors `UnitLinkedAllocationBandEntity` (`from_year`, `to_year`, `charge_percent`, `toBand()`). Repositories: `UnitLinkedOptionsRepository extends JpaRepository<UnitLinkedOptionsEntity, UUID>`; `UnitLinkedSurrenderChargeRepository` with `List<UnitLinkedSurrenderChargeEntity> findByProductVersionIdOrderByFromYear(UUID)`. One file per repository (nested JpaRepository interfaces are not scanned here).

- [ ] **Step 5: Persist and read in `UnitLinkedTermsStore`.** Add the two repositories to its constructor. In `persist`, after the minimums:

```java
        UnitLinkedOptions options = plan.options();
        if (options.switchingOffered() || options.withdrawalsOffered() || options.topUpsOffered()
                || !options.surrenderCharges().isEmpty()) {
            optionsRepository.save(new UnitLinkedOptionsEntity(tenantId, productVersionId, options));
            for (UnitLinkedOptions.SurrenderChargeBand band : options.surrenderCharges()) {
                surrenderCharges.save(new UnitLinkedSurrenderChargeEntity(tenantId, productVersionId, band));
            }
        }
```

In `read`, wrap the result: `.map(plan -> plan.withOptions(optionsRepository.findById(productVersionId).map(o -> o.toOptions(surrenderCharges.findByProductVersionIdOrderByFromYear(productVersionId).stream().map(UnitLinkedSurrenderChargeEntity::toBand).toList())).orElse(UnitLinkedOptions.none())))`. Surrender bands with no options row: store them under an options row whose features are null (the persist condition above already writes one).

- [ ] **Step 6: Validate in `UnitLinkedPlanValidator`.** Add `checkOptions(plan.options())` after `checkMinimums`:

```java
    private static void checkOptions(UnitLinkedOptions o) {
        if ((o.freeSwitchesPerYear() == null) != (o.switchFee() == null)) {
            fail("Switching needs both the free switches per year and the fee for each switch after them");
        }
        if (o.freeSwitchesPerYear() != null && (o.freeSwitchesPerYear() < 0 || o.switchFee().signum() < 0)) {
            fail("Free switches and the switch fee are zero or more");
        }
        if ((o.minimumWithdrawal() == null) != (o.minimumRemainingValue() == null)) {
            fail("Withdrawals need both the minimum withdrawal and the minimum value left in the policy");
        }
        if (o.minimumWithdrawal() != null && (o.minimumWithdrawal().signum() <= 0 || o.minimumRemainingValue().signum() < 0)) {
            fail("A minimum withdrawal is greater than zero and the minimum value left is zero or more");
        }
        if ((o.topUpAllocationPercent() == null) != (o.minimumTopUp() == null)) {
            fail("Top-ups need both their allocation percent and the minimum top-up");
        }
        if (o.topUpAllocationPercent() != null && (o.topUpAllocationPercent().signum() <= 0
                || o.topUpAllocationPercent().compareTo(BigDecimal.valueOf(100)) > 0 || o.minimumTopUp().signum() <= 0)) {
            fail("A top-up allocation percent is greater than 0 and at most 100, and the minimum top-up greater than zero");
        }
        List<UnitLinkedOptions.SurrenderChargeBand> bands = o.surrenderCharges();
        for (int i = 0; i < bands.size(); i++) {
            UnitLinkedOptions.SurrenderChargeBand b = bands.get(i);
            int expected = i == 0 ? 1 : bands.get(i - 1).toYear() == null ? -1 : bands.get(i - 1).toYear() + 1;
            if (b.fromYear() != expected) {
                fail("Surrender charge bands must run on from year 1 without gaps; band " + (i + 1) + " starts at year " + b.fromYear());
            }
            if (b.percent() == null || b.percent().signum() < 0 || b.percent().compareTo(BigDecimal.valueOf(100)) > 0) {
                fail("A surrender charge is between 0% and 100%");
            }
            if (b.toYear() != null && b.toYear() < b.fromYear()) {
                fail("Surrender charge band " + (i + 1) + " ends before it starts");
            }
            if (b.toYear() == null && i != bands.size() - 1) {
                fail("Only the last surrender charge band may be open-ended");
            }
        }
        if (!bands.isEmpty() && bands.get(bands.size() - 1).toYear() != null) {
            fail("The last surrender charge band must be open-ended");
        }
    }
```

- [ ] **Step 7: The request block.** In `UnitLinkedRequest` add a nullable `Options options` component (`record Options(Integer freeSwitchesPerYear, BigDecimal switchFee, BigDecimal minimumWithdrawal, BigDecimal minimumRemainingValue, Boolean withdrawalReducesSumAssured, BigDecimal topUpAllocationPercent, BigDecimal minimumTopUp, List<Band> surrenderCharges)` reusing `Band`), and in `toPlan()` end with `.withOptions(options == null ? UnitLinkedOptions.none() : options.toOptions())`.

- [ ] **Step 8: Tests.** In `UnitLinkedPlanValidatorTest` add, each asserting the exact message: a fee without a free allowance; a top-up percent of 0 and of 101; surrender bands starting at year 2; a closed last band; a valid set (switching 2/5000, withdrawals 100000/500000, top-ups 98/50000, bands `1-1 10%`, `2-5 5%`, `6- 0%`) passes. New `UnitLinkedOptionsIntegrationTest` (Testcontainers, `UnitLinkedTestMigrations.ALL` with V26 appended):

```java
    @Test
    void optionsRoundTripAndAU1VersionOffersNothing() {
        UUID tenant = UUID.randomUUID();
        var u1 = fixtures.publishStandard(tenant);
        assertThat(asTenant(tenant, () -> productApi.resolveUnitLinkedPlan(u1.versionId())).options())
            .isEqualTo(UnitLinkedOptions.none());
        var u2 = fixtures.publish(tenant, UnitLinkedTestFixtures.standardTerms(List.of("EQ1", "BD1"))
            .withOptions(UnitLinkedTestFixtures.standardOptions()));
        UnitLinkedOptions read = asTenant(tenant, () -> productApi.resolveUnitLinkedPlan(u2.versionId())).options();
        assertThat(read.freeSwitchesPerYear()).isEqualTo(2);
        assertThat(read.surrenderChargePercent(1)).isEqualByComparingTo("10");
        assertThat(read.surrenderChargePercent(4)).isEqualByComparingTo("5");
        assertThat(read.surrenderChargePercent(20)).isEqualByComparingTo("0");
    }

    @Test
    void aPublishedVersionsTermsCannotBeChanged() {
        UUID tenant = UUID.randomUUID();
        var p = fixtures.publish(tenant, UnitLinkedTestFixtures.standardTerms(List.of("EQ1", "BD1"))
            .withOptions(UnitLinkedTestFixtures.standardOptions()));
        for (String sql : List.of(
                "UPDATE product.unit_linked_terms SET monthly_policy_fee = 0 WHERE product_version_id = ?",
                "UPDATE product.unit_linked_options SET switch_fee = 0 WHERE product_version_id = ?",
                "DELETE FROM product.unit_linked_surrender_charge WHERE product_version_id = ?",
                "DELETE FROM product.unit_linked_fund WHERE product_version_id = ?")) {
            assertThatThrownBy(() -> jdbc.update(sql, p.versionId()))
                .hasMessageContaining("terms are never changed");
        }
    }
```

Add to `UnitLinkedTestFixtures`:

```java
    /** U2 terms: 2 free switches then 5,000; withdrawals from 100,000 leaving 500,000; top-ups at 98% from 50,000;
     *  surrender charge 10% in year 1, 5% in years 2-5, nothing after. Withdrawals leave cover unchanged. */
    public static UnitLinkedOptions standardOptions() {
        return new UnitLinkedOptions(2, new BigDecimal("5000.00"), new BigDecimal("100000.00"), new BigDecimal("500000.00"),
            false, new BigDecimal("98"), new BigDecimal("50000.00"), List.of(
                new UnitLinkedOptions.SurrenderChargeBand(1, 1, new BigDecimal("10")),
                new UnitLinkedOptions.SurrenderChargeBand(2, 5, new BigDecimal("5")),
                new UnitLinkedOptions.SurrenderChargeBand(6, null, BigDecimal.ZERO)));
    }
```

- [ ] **Step 9: Run.** `./mvnw -o clean test -Dsurefire.failIfNoSpecifiedTests=true -Dtest='UnitLinkedPlanValidatorTest,UnitLinkedOptionsIntegrationTest,UnitLinkedTermsIntegrationTest,ProductApiIntegrationTest'` — expect all green.

- [ ] **Step 10: Commit** — `feat(product): unit-linked U2 terms (switching, withdrawals, top-ups, surrender charge), and published terms the database refuses to change`.

---

### Task 2: The U2 schema in unitlinked, payment and communication

**Files:**
- Create: `backend/db-migrations/unitlinked/V3__u2.sql`, `backend/db-migrations/payment/V14__unit_linked_top_up.sql`, `backend/db-migrations/communication/V14__unit_linked_statement_template.sql`
- Modify: `UnitLinkedTestMigrations.java` (append the three), `unitlinked/domain/UnitEntry.java`, `unitlinked/domain/PendingOrder.java`, `unitlinked/application/UnitLedger.java`
- Test: `unitlinked/UnitLinkedSchemaIntegrationTest.java`

**Interfaces — Produces:**
- `UnitEntry.Type` gains `SWITCH_OUT, SWITCH_IN, SWITCH_FEE, WITHDRAWAL_SALE, SURRENDER_CHARGE`; `moneyOnly()` adds `SWITCH_FEE, SURRENDER_CHARGE`.
- `PendingOrder.Purpose` gains `WITHDRAWAL`; `static PendingOrder sellAmount(UUID tenantId, String policyNumber, UUID fundId, BigDecimal amount, Purpose purpose, Instant receivedAt, LocalTime cutOff, String sourceType, String sourceRef)`.
- `UnitLedger.saleType`: `case WITHDRAWAL -> UnitEntry.Type.WITHDRAWAL_SALE`.

- [ ] **Step 1: `V3__u2.sql`.**

```sql
-- db-migrations/unitlinked/V3__u2.sql -- product step 6, U2.

-- The new movements. unit_entry's CHECKs are replaced whole.
ALTER TABLE unitlinked.unit_entry DROP CONSTRAINT unit_entry_entry_type_check;
ALTER TABLE unitlinked.unit_entry ADD CONSTRAINT unit_entry_entry_type_check CHECK (entry_type IN
    ('ALLOCATION','ALLOCATION_CHARGE','POLICY_FEE','COST_OF_INSURANCE','DEATH_SALE','SURRENDER_SALE','MATURITY_SALE',
     'LAPSE_SALE','FREE_LOOK_SALE','CHARGE_REFUND','REINVESTMENT','PRICE_CORRECTION','WRITE_OFF',
     'SWITCH_OUT','SWITCH_IN','SWITCH_FEE','WITHDRAWAL_SALE','SURRENDER_CHARGE'));
ALTER TABLE unitlinked.unit_entry DROP CONSTRAINT unit_entry_check;  -- money-only entries have no fund
ALTER TABLE unitlinked.unit_entry ADD CONSTRAINT unit_entry_money_only_check CHECK (
    (entry_type IN ('ALLOCATION_CHARGE','CHARGE_REFUND','WRITE_OFF','SWITCH_FEE','SURRENDER_CHARGE')) = (fund_id IS NULL));
ALTER TABLE unitlinked.pending_order DROP CONSTRAINT pending_order_purpose_check;
ALTER TABLE unitlinked.pending_order ADD CONSTRAINT pending_order_purpose_check CHECK (purpose IN
    ('ALLOCATION','CHARGES','DEATH','SURRENDER','MATURITY','LAPSE','FREE_LOOK','REINVESTMENT','WITHDRAWAL'));
-- The surrender charge an exit (surrender, non-payment lapse) took from its proceeds.
ALTER TABLE unitlinked.exit_state ADD COLUMN surrender_charge NUMERIC(19,2) NOT NULL DEFAULT 0
    CHECK (surrender_charge >= 0);

-- Premium redirection: the split as a history (spec §4). U1's current split becomes each policy's first row.
CREATE TABLE unitlinked.premium_split (
    split_id       UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL,
    policy_number  VARCHAR(20) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    recorded_by    VARCHAR(100) NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL
);
CREATE TABLE unitlinked.premium_split_fund (
    split_id UUID NOT NULL REFERENCES unitlinked.premium_split(split_id),
    fund_id  UUID NOT NULL REFERENCES unitlinked.fund(fund_id),
    percent  INTEGER NOT NULL CHECK (percent BETWEEN 1 AND 100),
    PRIMARY KEY (split_id, fund_id)
);
CREATE INDEX idx_premium_split_policy ON unitlinked.premium_split (tenant_id, policy_number, effective_from);
INSERT INTO unitlinked.premium_split (split_id, tenant_id, policy_number, effective_from, recorded_by, recorded_at)
    SELECT gen_random_uuid(), a.tenant_id, a.policy_number, '-infinity', 'migration:V3', now()
    FROM (SELECT DISTINCT tenant_id, policy_number FROM unitlinked.policy_allocation) a;
INSERT INTO unitlinked.premium_split_fund (split_id, fund_id, percent)
    SELECT s.split_id, a.fund_id, a.percent FROM unitlinked.policy_allocation a
    JOIN unitlinked.premium_split s ON s.tenant_id = a.tenant_id AND s.policy_number = a.policy_number;

-- Switches (spec §2): one row per request, one leg per fund moved out or bought into.
CREATE TABLE unitlinked.switch_request (
    switch_id      UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL,
    policy_number  VARCHAR(20) NOT NULL,
    requested_at   TIMESTAMPTZ NOT NULL,
    requested_by   VARCHAR(100) NOT NULL,
    bound_date     DATE NOT NULL,
    status         VARCHAR(10) NOT NULL DEFAULT 'WAITING' CHECK (status IN ('WAITING','EXECUTED','CANCELLED')),
    executed_on    DATE,
    fee            NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (fee >= 0),
    version        BIGINT NOT NULL DEFAULT 0,
    CHECK ((status = 'EXECUTED') = (executed_on IS NOT NULL))
);
CREATE UNIQUE INDEX ux_switch_waiting ON unitlinked.switch_request (tenant_id, policy_number) WHERE status = 'WAITING';
CREATE TABLE unitlinked.switch_leg (
    switch_id UUID NOT NULL REFERENCES unitlinked.switch_request(switch_id),
    fund_id   UUID NOT NULL REFERENCES unitlinked.fund(fund_id),
    side      VARCHAR(3) NOT NULL CHECK (side IN ('OUT','IN')),
    percent   INTEGER NOT NULL CHECK (percent BETWEEN 1 AND 100),
    PRIMARY KEY (switch_id, fund_id, side)
);

-- Partial withdrawals (spec §3).
CREATE TABLE unitlinked.withdrawal_request (
    withdrawal_id    UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    policy_number    VARCHAR(20) NOT NULL,
    gross_amount     NUMERIC(19,2) NOT NULL CHECK (gross_amount > 0),
    payee_ref        VARCHAR(100) NOT NULL,
    status           VARCHAR(10) NOT NULL DEFAULT 'REQUESTED'
                         CHECK (status IN ('REQUESTED','APPROVED','PRICED','PAID','CANCELLED')),
    requested_by     VARCHAR(100) NOT NULL,
    requested_at     TIMESTAMPTZ NOT NULL,
    approved_by      VARCHAR(100),
    approved_at      TIMESTAMPTZ,
    proceeds         NUMERIC(19,2) NOT NULL DEFAULT 0,
    surrender_charge NUMERIC(19,2) NOT NULL DEFAULT 0,
    shortfall        NUMERIC(19,2) NOT NULL DEFAULT 0,
    version          BIGINT NOT NULL DEFAULT 0,
    CHECK (approved_by IS NULL OR approved_by <> requested_by)
);
CREATE UNIQUE INDEX ux_withdrawal_live ON unitlinked.withdrawal_request (tenant_id, policy_number)
    WHERE status IN ('REQUESTED','APPROVED');
-- Named funds only; none = pro rata at approval.
CREATE TABLE unitlinked.withdrawal_fund (
    withdrawal_id UUID NOT NULL REFERENCES unitlinked.withdrawal_request(withdrawal_id),
    fund_id       UUID NOT NULL REFERENCES unitlinked.fund(fund_id),
    amount        NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    PRIMARY KEY (withdrawal_id, fund_id)
);

-- Top-ups (spec §4).
CREATE TABLE unitlinked.top_up (
    top_up_id     UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    policy_number VARCHAR(20) NOT NULL,
    amount        NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency      CHAR(3) NOT NULL,
    payer_ref     VARCHAR(100) NOT NULL,
    status        VARCHAR(10) NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','RECEIVED','REFUNDED','FAILED')),
    requested_by  VARCHAR(100) NOT NULL,
    requested_at  TIMESTAMPTZ NOT NULL,
    received_at   TIMESTAMPTZ,
    version       BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE unitlinked.top_up_fund (
    top_up_id UUID NOT NULL REFERENCES unitlinked.top_up(top_up_id),
    fund_id   UUID NOT NULL REFERENCES unitlinked.fund(fund_id),
    percent   INTEGER NOT NULL CHECK (percent BETWEEN 1 AND 100),
    PRIMARY KEY (top_up_id, fund_id)
);

-- One row per Idempotency-Key on a request that creates something (accumulation V2's reason).
CREATE TABLE unitlinked.request_key (
    tenant_id       UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    operation       VARCHAR(30) NOT NULL,
    target          VARCHAR(40) NOT NULL,
    created_id      UUID NOT NULL,
    created_by      VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, idempotency_key)
);

-- Statements (spec §5).
CREATE TABLE unitlinked.unit_statement (
    statement_id  UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    policy_number VARCHAR(20) NOT NULL,
    period_from   DATE NOT NULL,
    period_to     DATE NOT NULL CHECK (period_to >= period_from),
    kind          VARCHAR(9) NOT NULL CHECK (kind IN ('ANNUAL','ON_DEMAND')),
    document_ref  VARCHAR(200) NOT NULL,
    generated_by  VARCHAR(100) NOT NULL,
    generated_at  TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX ux_unit_statement_annual ON unitlinked.unit_statement (tenant_id, policy_number, period_to)
    WHERE kind = 'ANNUAL';

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['premium_split','switch_request','withdrawal_request','top_up','request_key','unit_statement'] LOOP
        EXECUTE format('ALTER TABLE unitlinked.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON unitlinked.%I USING (tenant_id = NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)',
                       t || '_tenant_isolation', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE ON unitlinked.switch_request, unitlinked.withdrawal_request, unitlinked.top_up TO app_role;
GRANT SELECT, INSERT ON unitlinked.premium_split, unitlinked.premium_split_fund, unitlinked.switch_leg,
    unitlinked.withdrawal_fund, unitlinked.top_up_fund, unitlinked.request_key, unitlinked.unit_statement TO app_role;
```

Before writing it, confirm the U1 constraint names: `docker exec infra-postgres-1 psql -U postgres -d lifeplatform -c "\d unitlinked.unit_entry"` (and `pending_order`), and use the names printed. `policy_allocation` is NOT dropped (spec: replaced as the source of truth; drop it in a later cleanup once nothing reads it).

- [ ] **Step 2: `payment/V14__unit_linked_top_up.sql`** — replace `payment_transaction_purpose_check` with `CHECK (purpose IN ('PREMIUM','ACCOUNT_TOP_UP','UL_TOP_UP'))`, copying V9's statement form. **`communication/V14`** — insert template `UL_STATEMENT` (SMS, body: `Your Nlolo unit-linked statement for {{year}} is ready: policy {{policyNumber}}, value {{closingValue}} {{currency}} at {{priceDate}}.`), copying the column list from `communication/V13__unit_linked_templates.sql`.

- [ ] **Step 3: Java types.** Extend `UnitEntry.Type` and `moneyOnly()`; `PendingOrder.Purpose.WITHDRAWAL` and:

```java
    /** Money sold from one fund for a withdrawal: as many units as cover it, never more than held. */
    public static PendingOrder sellAmount(UUID tenantId, String policyNumber, UUID fundId, BigDecimal amount, Purpose purpose,
                                          Instant receivedAt, LocalTime cutOff, String sourceType, String sourceRef) {
        requirePositive(amount);
        return new PendingOrder(tenantId, policyNumber, fundId, Side.SELL, amount, false, purpose, null, receivedAt, cutOff,
            sourceType, sourceRef);
    }
```

`UnitLedger.saleType`: add `case WITHDRAWAL -> UnitEntry.Type.WITHDRAWAL_SALE;`.

- [ ] **Step 4: Test** `UnitLinkedSchemaIntegrationTest`: the U1 split of a sold policy is readable as one `premium_split` row (sell with `standardChoice()`, apply V3, assert one split with EQ1 60 / BD1 40); an insert of `SWITCH_FEE` with a fund id is refused; a second WAITING switch on one policy violates `ux_switch_waiting`. Run it with `UnitLinkedSaleIntegrationTest` and `ForwardPricingIntegrationTest`.

- [ ] **Step 5: Commit** — `feat(unitlinked): the U2 schema -- switches, withdrawals, top-ups, the split history, statements and the new movements`.

---

### Task 3: Premium redirection (the split history)

**Files:**
- Create: `unitlinked/domain/PremiumSplit.java` (+ `PremiumSplitFund` embeddable rows), `unitlinked/infrastructure/PremiumSplitRepository.java`, `unitlinked/application/PremiumSplits.java`, `unitlinked/api/PremiumSplitView.java`
- Modify: `Allocations.java` (`recordAtIssue` writes a split row; `onPremiumCollected` reads the split in force), `UnitLinkedApi`/`Impl`, `FundController`
- Test: `unitlinked/PremiumRedirectionIntegrationTest.java`

**Interfaces — Produces:** `PremiumSplits.splitAt(String policyNumber, Instant receivedAt) -> List<PolicyAllocation-like (UUID fundId, int percent)>` as `List<UnitArithmetic.Weighted>`-ready `record Share(UUID fundId, int percent)`; `PremiumSplits.record(String policyNumber, List<UnitLinkedChoice.Split> split, String by) -> PremiumSplitView`; `UnitLinkedApi.redirect(...)`, `UnitLinkedApi.splitHistory(String policyNumber) -> List<PremiumSplitView>`.

- [ ] **Step 1: Failing test.**

```java
    @Test
    void aRedirectionAppliesToPremiumsReceivedAfterItNotToOneAlreadyWaiting() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandardBindingTomorrow(tenant, UnitLinkedTestFixtures.standardTerms(List.of("EQ1", "BD1")));
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        Instant before = now.get();
        fixtures.collectAt(tenant, policy, "100000.00", before, UUID.randomUUID());          // 60/40, waiting
        now.set(before.plusSeconds(60));
        asTenant(tenant, () -> api.redirect(policy, List.of(new UnitLinkedChoice.Split("EQ1", 30),
            new UnitLinkedChoice.Split("BD1", 70)), "staff-one"));
        fixtures.collectAt(tenant, policy, "100000.00", now.get().plusSeconds(60), UUID.randomUUID());  // 30/70

        var waiting = asTenant(tenant, () -> api.units(policy)).pending();
        // 90,000 allocated each time (90% in year 1): the first 54,000/36,000, the second 27,000/63,000.
        assertThat(waiting).extracting(o -> o.fundCode() + ":" + o.amount().toPlainString())
            .containsExactlyInAnyOrder("EQ1:54000.00", "BD1:36000.00", "EQ1:27000.00", "BD1:63000.00");
        assertThat(asTenant(tenant, () -> api.splitHistory(policy))).hasSize(2);
    }

    @Test
    void aRedirectionNamesOnlyOfferedOpenFundsAndTotals100() {
        // ... sell as above, then:
        assertThatThrownBy(() -> asTenant(tenant, () -> api.redirect(policy,
            List.of(new UnitLinkedChoice.Split("EQ1", 50), new UnitLinkedChoice.Split("BD1", 40)), "staff-one")))
            .hasMessageContaining("The fund split totals 90%; it must total 100%");
    }
```

- [ ] **Step 2:** Run — FAIL (`redirect` does not exist).

- [ ] **Step 3: Implement.** `PremiumSplit` entity (`split_id`, tenant, policy, `effective_from`, `recorded_by`, `recorded_at`, `@ElementCollection` of `(fund_id, percent)` into `premium_split_fund`). `PremiumSplitRepository.findFirstByTenantIdAndPolicyNumberAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(UUID, String, Instant)` and `findByTenantIdAndPolicyNumberOrderByEffectiveFromDesc`. `PremiumSplits`:

```java
@Component
class PremiumSplits {
    record Share(UUID fundId, int percent) {}

    private final PremiumSplitRepository splits;
    private final FundRepository funds;
    private final PolicyApi policyApi;
    private final ProductApi productApi;
    private final UnitLedger ledger;
    private final Clock clock;
    // constructor: @Qualifier("unitLinkedClock") Clock

    /** The split in force when the money was received; a premium already waiting was split when it arrived. */
    List<Share> splitAt(String policyNumber, Instant receivedAt) {
        return splits.findFirstByTenantIdAndPolicyNumberAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                TenantContext.get(), policyNumber, receivedAt)
            .orElseThrow(() -> new IllegalStateException("Unit-linked policy " + policyNumber + " has no premium split"))
            .shares().stream().sorted(Comparator.comparing(s -> s.fundId().toString())).toList();
    }

    @Transactional
    PremiumSplitView record(String policyNumber, List<UnitLinkedChoice.Split> split, String by) {
        PolicyView policy = policyApi.getPolicy(policyNumber);
        if (!List.of("ACTIVE", "REINSTATED").contains(policy.status().name()) || ledger.isFrozen(policyNumber)) {
            throw new UnitLinkedStateException("Policy " + policyNumber + " is not in force; its split cannot change");
        }
        UnitLinkedPlan plan = productApi.resolveUnitLinkedPlan(policy.productVersionId());
        List<Share> shares = SplitRules.resolve(split, plan, funds);   // the U1 UnitLinkedChoices words: offered, OPEN, whole %, total 100
        PremiumSplit saved = splits.save(new PremiumSplit(TenantContext.get(), policyNumber, clock.instant(), by, clock.instant(), shares));
        return PremiumSplitView.of(saved, funds);
    }
}
```

Extract the split checks into `unitlinked/application/SplitRules.java` (`static List<Share> resolve(...)`), throwing `IllegalArgumentException` with U1's exact messages ("Fund X is not offered by this product; it offers …", "Fund X appears twice in the split", "Each fund's share is a whole percent from 1 to 100", "The fund split totals N%; it must total 100%", and "Fund X is closed and takes no new money"). In `Allocations.onPremiumCollected`, replace the `allocations.findByTenantIdAndPolicyNumber(...)` read with `premiumSplits.splitAt(policyNumber, collectedAt)` (map `Share` to `UnitArithmetic.Weighted(fundId.toString(), percent)`). In `recordAtIssue`, also save the first `PremiumSplit` with `effective_from = '-infinity'` (use `Instant.MIN`-safe value `Instant.parse("1970-01-01T00:00:00Z")`). Controller: `PUT /policies/{policyNumber}/premium-split` (STAFF; body `{ "split": [{fundCode, percent}] }`) and `GET` (history).

- [ ] **Step 4:** Run `PremiumRedirectionIntegrationTest,UnitLinkedSaleIntegrationTest,ForwardPricingIntegrationTest,UnitLinkedDeathIntegrationTest` — PASS.
- [ ] **Step 5: Commit** — `feat(unitlinked): premium redirection -- the split as a history, each premium split as it was when received`.

---

### Task 4: Fund switches

**Files:**
- Create: `unitlinked/domain/SwitchRequest.java` (+ `SwitchLeg` element collection), `unitlinked/infrastructure/SwitchRequestRepository.java`, `unitlinked/application/Switches.java`, `unitlinked/api/SwitchView.java`, `unitlinked/api/SwitchInput.java`
- Modify: `PricingRun.onApproved` (call `switches.getObject().onPriceApproved(fund, price)` last), `Exits.exit` (cancel a WAITING switch on freeze), `Corrections` (redo `SWITCH_OUT`/`SWITCH_IN` like sales/buys), `UnitLinkedApi`/`Impl`, `FundController`, `PolicyUnitsView` building (WAITING switches in `pending` with `purpose = SWITCH`)
- Test: `unitlinked/SwitchIntegrationTest.java`

**Interfaces — Consumes:** `BindingRule.boundDate(Instant, LocalTime)`, `UnitArithmetic.proceeds(units, price)`, `UnitArithmetic.unitsBought(amount, price)`, `UnitArithmetic.split(amount, weights)`, `PremiumSplits.Share`, `SplitRules.resolve`. **Produces:** `record SwitchInput(List<Out> out, List<UnitLinkedChoice.Split> into)` with `record Out(String fundCode, int percent)`; `UnitLinkedApi.requestSwitch(String policyNumber, SwitchInput input, String by) -> SwitchView`; `listSwitches(String) -> List<SwitchView>`; event `unitlinked.SwitchExecuted {switchId, policyNumber, fee, currencyCode}`.

- [ ] **Step 1: Failing tests** in `SwitchIntegrationTest` (U1 fixture style: `@MockBean(name="unitLinkedClock") Clock`, funds cutting off at 00:00:01, the policy holding 54,000 EQ1 and 36,000 BD1 units at 1.000000):

```java
    @Test
    void bothLegsPriceOnOneDateAndWaitForTheSlowerFund() {
        Sold s = invested();                                         // 54,000 EQ1 + 36,000 BD1
        SwitchView sw = asTenant(s.tenant(), () -> api.requestSwitch(s.policyNumber(),
            new SwitchInput(List.of(new SwitchInput.Out("EQ1", 100)), List.of(new UnitLinkedChoice.Split("BD1", 100))),
            "staff-one"));
        assertThat(sw.boundDate()).isEqualTo(TODAY.plusDays(1));
        approve(s.tenant(), "EQ1", TODAY.plusDays(1), "1.200000");   // only one fund priced: nothing moves
        assertThat(units(s).holdings()).filteredOn(h -> h.fundCode().equals("EQ1")).first()
            .satisfies(h -> assertThat(h.units()).isEqualByComparingTo("54000"));
        approve(s.tenant(), "BD1", TODAY.plusDays(1), "1.000000");   // both priced: the switch executes at both
        assertThat(units(s).holdings()).filteredOn(h -> h.fundCode().equals("BD1")).first()
            .satisfies(h -> assertThat(h.units()).isEqualByComparingTo("100800"));  // 36,000 + 64,800
        assertThat(sw(s).status()).isEqualTo("EXECUTED");
    }

    @Test
    void theThirdSwitchInAPolicyYearPaysTheFee() {
        Sold s = invested();
        switchAndPrice(s, 1); switchAndPrice(s, 2);                 // free
        SwitchView third = switchAndPrice(s, 3);
        assertThat(third.fee()).isEqualByComparingTo("5000.00");
        assertThat(sumOf(s, "SWITCH_FEE")).isEqualByComparingTo("-5000.00");
    }

    @Test
    void aSwitchWaitingWhenThePolicyFreezesIsCancelled() { /* request, then register a death: status CANCELLED, no legs */ }

    @Test
    void aSwitchIsRefusedWhileAnotherWaitsOrWhenTheVersionOffersNone() { /* messages: "A switch is already waiting on policy …", "This product does not offer fund switches" */ }
```

- [ ] **Step 2:** Run — FAIL.

- [ ] **Step 3: Implement `Switches`.**

```java
@Component
class Switches {
    // repos: SwitchRequestRepository switches, FundRepository funds, FundPriceRepository prices,
    // UnitEntryRepository entries, FundLiabilityRepository liabilities, UnitLedger ledger, PolicyApi policyApi,
    // ProductApi productApi, ApplicationEventPublisher events, @Qualifier("unitLinkedClock") Clock clock

    @Transactional
    SwitchView request(String policyNumber, SwitchInput input, String by) {
        UUID tenantId = TenantContext.get();
        PolicyView policy = policyApi.getPolicy(policyNumber);
        UnitLinkedPlan plan = productApi.resolveUnitLinkedPlan(policy.productVersionId());
        if (!plan.options().switchingOffered()) throw new UnitLinkedStateException("This product does not offer fund switches");
        if (!List.of("ACTIVE", "REINSTATED").contains(policy.status().name()) || ledger.isFrozen(policyNumber)) {
            throw new UnitLinkedStateException("Policy " + policyNumber + " is not in force; nothing can be switched");
        }
        if (switches.existsByTenantIdAndPolicyNumberAndStatus(tenantId, policyNumber, "WAITING")) {
            throw new UnitLinkedStateException("A switch is already waiting on policy " + policyNumber);
        }
        Map<UUID, BigDecimal> held = ledger.holdings(policyNumber);
        List<SwitchLeg> legs = new ArrayList<>();
        for (SwitchInput.Out out : input.out()) {
            Fund fund = funds.findByTenantIdAndCode(tenantId, out.fundCode().trim().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Fund " + out.fundCode() + " is not in the register"));
            if (held.getOrDefault(fund.getFundId(), BigDecimal.ZERO).signum() <= 0) {
                throw new IllegalArgumentException("Policy " + policyNumber + " holds no units of " + fund.getCode());
            }
            if (out.percent() < 1 || out.percent() > 100) throw new IllegalArgumentException("Each fund's share is a whole percent from 1 to 100");
            legs.add(new SwitchLeg(fund.getFundId(), "OUT", out.percent()));
        }
        for (PremiumSplits.Share share : SplitRules.resolve(input.into(), plan, funds)) {
            legs.add(new SwitchLeg(share.fundId(), "IN", share.percent()));
        }
        Instant at = clock.instant();
        // Both legs on one date: the LATEST of every involved fund's binding, so no leg is priced on a date the
        // customer could already see (spec §2).
        LocalDate bound = legs.stream().map(l -> funds.findByTenantIdAndFundId(tenantId, l.fundId()).orElseThrow())
            .map(f -> BindingRule.boundDate(at, f.getCutOffTime())).max(Comparator.naturalOrder()).orElseThrow();
        SwitchRequest saved = switches.save(new SwitchRequest(tenantId, policyNumber, at, by, bound, legs));
        return SwitchView.of(saved, funds);
    }

    /** Called last in every pricing run: execute each waiting switch whose every fund now has a common priced date. */
    void onPriceApproved(Fund fund, FundPrice price) {
        UUID tenantId = TenantContext.get();
        for (SwitchRequest sw : switches.findWaitingInvolving(tenantId, fund.getFundId(), price.getValuationDate())) {
            commonPricedDate(sw).ifPresent(date -> execute(sw, date));
        }
    }

    /** The first date >= the binding on which every involved fund has an APPROVED price; empty until there is one. */
    private Optional<LocalDate> commonPricedDate(SwitchRequest sw) {
        List<UUID> involved = sw.legs().stream().map(SwitchLeg::fundId).distinct().toList();
        return prices.approvedDatesFrom(sw.getTenantId(), involved.get(0), sw.getBoundDate()).stream()
            .filter(d -> involved.stream().allMatch(f -> prices.findApproved(sw.getTenantId(), f, d).isPresent()))
            .findFirst();
    }

    private void execute(SwitchRequest sw, LocalDate date) {
        UUID tenantId = sw.getTenantId();
        BigDecimal proceeds = BigDecimal.ZERO;
        for (SwitchLeg out : sw.legs().stream().filter(l -> l.side().equals("OUT")).toList()) {
            FundPrice p = prices.findApproved(tenantId, out.fundId(), date).orElseThrow();
            BigDecimal held = entries.sumUnits(tenantId, sw.getPolicyNumber(), out.fundId());
            BigDecimal units = out.percent() == 100 ? held
                : held.multiply(BigDecimal.valueOf(out.percent())).divide(BigDecimal.valueOf(100), 6, RoundingMode.DOWN);
            BigDecimal money = UnitArithmetic.proceeds(units, p.getPrice());
            entries.save(UnitEntry.switched(tenantId, sw.getPolicyNumber(), out.fundId(), UnitEntry.Type.SWITCH_OUT,
                units.negate(), p, money.negate(), "switch", sw.getSwitchId().toString(), clock.instant()));
            moveLiability(tenantId, out.fundId(), money.negate());
            proceeds = proceeds.add(money);
        }
        BigDecimal fee = feeFor(sw, date);
        if (fee.signum() > 0) {
            entries.save(UnitEntry.money(tenantId, sw.getPolicyNumber(), UnitEntry.Type.SWITCH_FEE, fee.negate(), date,
                "switch", sw.getSwitchId().toString(), null, UnitLedger.SYSTEM, clock.instant()));
        }
        BigDecimal net = proceeds.subtract(fee);
        List<SwitchLeg> into = sw.legs().stream().filter(l -> l.side().equals("IN"))
            .sorted(Comparator.comparing(l -> l.fundId().toString())).toList();
        List<BigDecimal> parts = UnitArithmetic.split(net, into.stream()
            .map(l -> new UnitArithmetic.Weighted(l.fundId().toString(), BigDecimal.valueOf(l.percent()))).toList());
        for (int i = 0; i < into.size(); i++) {
            FundPrice p = prices.findApproved(tenantId, into.get(i).fundId(), date).orElseThrow();
            BigDecimal units = UnitArithmetic.unitsBought(parts.get(i), p.getPrice());
            entries.save(UnitEntry.switched(tenantId, sw.getPolicyNumber(), into.get(i).fundId(), UnitEntry.Type.SWITCH_IN,
                units, p, parts.get(i), "switch", sw.getSwitchId().toString(), clock.instant()));
            moveLiability(tenantId, into.get(i).fundId(), parts.get(i));
        }
        sw.executed(date, fee);
        switches.save(sw);
        events.publishEvent(DomainEventEnvelope.of("unitlinked.SwitchExecuted", tenantId, Map.of(
            "switchId", sw.getSwitchId().toString(), "policyNumber", sw.getPolicyNumber(),
            "sourceRef", "switch:" + sw.getSwitchId(), "fee", fee.toPlainString(),
            "currencyCode", policyApi.getPolicy(sw.getPolicyNumber()).premiumCurrency())));
    }

    /** Beyond the version's free switches in the policy year of {@code date}: the version's fee, else nothing. */
    private BigDecimal feeFor(SwitchRequest sw, LocalDate date) {
        PolicyView policy = policyApi.getPolicy(sw.getPolicyNumber());
        UnitLinkedOptions o = productApi.resolveUnitLinkedPlan(policy.productVersionId()).options();
        LocalDate start = policy.commencementDate() != null ? policy.commencementDate() : policy.issueDate();
        int yearsDone = Period.between(start, date).getYears();
        LocalDate yearStart = start.plusYears(yearsDone);
        long executedThisYear = switches.countExecutedBetween(sw.getTenantId(), sw.getPolicyNumber(), yearStart, date);
        return executedThisYear >= o.freeSwitchesPerYear() ? o.switchFee() : BigDecimal.ZERO;
    }

    /** A switch moves value between funds inside 2150: each fund's carried liability moves with it (plan D1). */
    private void moveLiability(UUID tenantId, UUID fundId, BigDecimal by) {
        FundLiability l = liabilities.lockFor(tenantId, fundId).orElseGet(() -> new FundLiability(tenantId, fundId));
        l.adjust(by);
        liabilities.save(l);
    }

    /** An exit freezes the policy: a switch still waiting is cancelled -- the exit sells everything. */
    void cancelWaiting(String policyNumber) {
        switches.findByTenantIdAndPolicyNumberAndStatus(TenantContext.get(), policyNumber, "WAITING")
            .ifPresent(sw -> { sw.cancel(); switches.save(sw); });
    }
}
```

Add `UnitEntry.switched(UUID tenantId, String policyNumber, UUID fundId, Type type, BigDecimal units, FundPrice price, BigDecimal amount, String sourceType, String sourceRef, Instant now)` (orderId null, bound date = valuation date), `FundLiability.adjust(BigDecimal)` (carried += by), and repository methods: `SwitchRequestRepository.findWaitingInvolving(UUID tenantId, UUID fundId, LocalDate upTo)` (JPQL: `select s from SwitchRequest s join s.legs l where s.tenantId = ?1 and s.status = 'WAITING' and l.fundId = ?2 and s.boundDate <= ?3`), `countExecutedBetween` (`status = 'EXECUTED' and executedOn between ?3 and ?4`), `FundPriceRepository.approvedDatesFrom(UUID tenantId, UUID fundId, LocalDate from)` (`select p.valuationDate ... status = 'APPROVED' and valuationDate >= ?3 order by valuationDate`) and `findApproved(UUID, UUID, LocalDate)`. `PricingRun.onApproved` takes `ObjectProvider<Switches>` and calls `onPriceApproved` after the true-up. **The true-up stays correct**: a switch's legs move `fund_liability` themselves, and the next true-up of each fund measures from that carried figure.

In `Exits.exit`, right after `ledger.freeze(...)`: `switchesProvider.getObject().cancelWaiting(policyNumber);`.

- [ ] **Step 4:** Run `SwitchIntegrationTest,PricingRunIntegrationTest,PriceCorrectionIntegrationTest,UnitLinkedAccountingIntegrationTest` — PASS.
- [ ] **Step 5: Commit** — `feat(unitlinked): fund switches priced on one date for both legs, free then charged`.

---

### Task 5: Partial withdrawals

**Files:**
- Create: `unitlinked/domain/WithdrawalRequest.java`, `unitlinked/infrastructure/WithdrawalRequestRepository.java`, `unitlinked/application/Withdrawals.java`, `unitlinked/api/WithdrawalView.java`, `unitlinked/api/WithdrawalInput.java`
- Modify: `Exits.afterPriced` (return early for `Purpose.WITHDRAWAL`), `Exits.exit` (cancel a REQUESTED/APPROVED withdrawal: its waiting orders are cancelled with the rest), `PaymentEventListener` (route `WITHDRAWAL_PAYOUT` with a `unit-linked:withdrawal:` key to `Withdrawals.onPaid`), `UnitLinkedApi`/`Impl`, `FundController`
- Modify: `policy/api/PolicyApi.java`, `policy/application/PolicyApiImpl.java`, `policy/domain/Policy.java` (R5)
- Test: `unitlinked/WithdrawalIntegrationTest.java`

**Interfaces — Produces:** `record WithdrawalInput(BigDecimal grossAmount, List<Named> funds, String payeeRef)` with `record Named(String fundCode, BigDecimal amount)`; `UnitLinkedApi.requestWithdrawal(String, WithdrawalInput, String) -> WithdrawalView`; `approveWithdrawal(UUID, String) -> WithdrawalView`; `listWithdrawals(String)`; `WithdrawalView(withdrawalId, policyNumber, grossAmount, estimatedCharge, estimatedNet, estimatedRemaining, newSumAssured, status, proceeds, surrenderCharge, shortfall, payeeRef, requestedBy, approvedBy)`; `PolicyApi.reduceUnitLinkedSumAssured(String policyNumber, BigDecimal by, String reason, String appliedBy) -> PolicyView`; `SurrenderCharges.percentFor(PolicyView policy, LocalDate valuationDate) -> BigDecimal` (Task 6 creates it; Task 5 adds it first if Task 6 has not run).

- [ ] **Step 1: Failing tests** (`standardOptions()`; the policy is issued 3 years and 10 days ago, so the minimum surrender years have passed and the charge is policy year 4's 5%; one premium of 800,000 is collected and priced at 1.000000, so 90% = 720,000 buys 432,000 EQ1 and 288,000 BD1 units):

```java
    @Test
    void aWithdrawalSellsProRataAtTheFirstPriceAfterApprovalLessTheSurrenderCharge() {
        Sold s = invested(TODAY.minusYears(3).minusDays(10), "800000.00");   // 432,000 EQ1 + 288,000 BD1 at 1.00
        var req = asTenant(s.tenant(), () -> api.requestWithdrawal(s.policyNumber(),
            new WithdrawalInput(new BigDecimal("150000.00"), List.of(), "+255700000600"), "staff-one"));
        // 720,000 - 150,000 = 570,000 left, above the 500,000 minimum
        assertThatThrownBy(() -> asTenant(s.tenant(), () -> api.approveWithdrawal(req.withdrawalId(), "staff-one")))
            .hasMessageContaining("a second person approves");
        asTenant(s.tenant(), () -> api.approveWithdrawal(req.withdrawalId(), FINANCE));
        priceBoth(s.tenant(), TODAY.plusDays(1), "1.000000", "1.000000");
        WithdrawalView done = withdrawal(s, req.withdrawalId());
        assertThat(done.proceeds()).isEqualByComparingTo("150000.00");
        assertThat(done.surrenderCharge()).isEqualByComparingTo("7500.00");          // 5% in policy year 4
        assertThat(paid("unit-linked:withdrawal:" + req.withdrawalId())).isEqualByComparingTo("142500.00");
        assertThat(done.status()).isEqualTo("PAID");
    }

    @Test
    void aNamedFundSellsOnlyThatFundAndAPriceFallCapsItAtTheHolding() {
        Sold s = invested(TODAY.minusYears(3).minusDays(10), "800000.00");
        var req = asTenant(s.tenant(), () -> api.requestWithdrawal(s.policyNumber(), new WithdrawalInput(
            new BigDecimal("200000.00"), List.of(new WithdrawalInput.Named("BD1", new BigDecimal("200000.00"))),
            "+255700000600"), "staff-one"));                      // 520,000 left at today's prices
        asTenant(s.tenant(), () -> api.approveWithdrawal(req.withdrawalId(), FINANCE));
        priceBoth(s.tenant(), TODAY.plusDays(1), "1.000000", "0.600000");   // BD1 falls to 0.60
        WithdrawalView done = withdrawal(s, req.withdrawalId());
        assertThat(done.proceeds()).isEqualByComparingTo("172800.00");      // all 288,000 BD1 units x 0.60
        assertThat(done.shortfall()).isEqualByComparingTo("27200.00");
        assertThat(units(s).holdings()).filteredOn(h -> h.fundCode().equals("EQ1")).first()
            .satisfies(h -> assertThat(h.units()).isEqualByComparingTo("432000"));   // EQ1 untouched
    }

    @Test
    void aWithdrawalBelowTheMinimumOrLeavingTooLittleIsRefused() {
        // "A withdrawal is at least 100,000.00 TZS", "This withdrawal would leave about N TZS; at least 500,000.00 TZS must stay"
    }

    @Test
    void aWithdrawalCutsCoverOnlyWhenTheVersionSaysSo() { /* options with withdrawalReducesSumAssured=true → SA 6,000,000 → 5,850,000 on execution, and the DEATH coverage row with it */ }
```

- [ ] **Step 2:** Run — FAIL.

- [ ] **Step 3: Implement `Withdrawals`.** `request`: resolve plan/options (`withdrawalsOffered()` else "This product does not offer partial withdrawals"); in force, not frozen; `Period.between(start, today).getYears() >= minimumSurrenderYears` else "Policy N has no withdrawal value until M years"; no live withdrawal (index) and no live surrender (`policyApi.findLatestSurrenderRequest` status REQUESTED/APPROVED) and no WAITING switch; `grossAmount >= minimumWithdrawal`; value at latest prices (`valueAtLatest`, summing `holding × latest approved price` per fund — a fund with no approved price counts zero and the request is refused "Fund X has no price yet; nothing can be withdrawn from it") minus gross ≥ `minimumRemainingValue`; named funds sum to gross and each ≤ its value; when `withdrawalReducesSumAssured`, `sumAssured - gross >= multipleMin × annualPremium` else "Cutting the cover by this withdrawal would leave N TZS, below the M TZS this product's minimum allows". `approve(UUID id, String by)`: `by != requestedBy` else "A withdrawal must be approved by someone other than the person who requested it (X); a second person approves"; re-run the request checks; then create the orders — named funds, or pro rata:

```java
        Instant at = clock.instant();
        Map<UUID, BigDecimal> amounts = req.namedFunds().isEmpty()
            ? proRata(req.getPolicyNumber(), req.getGrossAmount())   // by value at the latest prices, UnitArithmetic.split, largest-remainder
            : req.namedFunds();
        for (var e : amounts.entrySet()) {
            Fund fund = funds.findByTenantIdAndFundId(tenantId, e.getKey()).orElseThrow();
            orders.save(PendingOrder.sellAmount(tenantId, req.getPolicyNumber(), fund.getFundId(), e.getValue(),
                PendingOrder.Purpose.WITHDRAWAL, at, fund.getCutOffTime(), "withdrawal", req.getWithdrawalId().toString()));
        }
        req.approve(by, at);
```

Implement `UnitsPricedListener.afterPriced` in `Withdrawals`: for `Purpose.WITHDRAWAL` add `-entry.amount` to `proceeds` and `order.amount - (-entry.amount)` to `shortfall`; when `orders.countWaiting(...) == 0`: `charge = proceeds × SurrenderCharges.percentFor(policy, valuationDate) / 100` (HALF_EVEN, 2dp); write `SURRENDER_CHARGE` money entry (`-charge`); mark PRICED; publish `unitlinked.WithdrawalPriced {withdrawalId, policyNumber, sourceRef "withdrawal:<id>", proceeds, surrenderCharge, currencyCode}`; publish `unitlinked.PayoutRequested {purpose WITHDRAWAL_PAYOUT, sourceRef id, idempotencyKey "unit-linked:withdrawal:<id>", policyNumber, payeeRef, amount {proceeds - charge}}`; if `withdrawalReducesSumAssured`, `policyApi.reduceUnitLinkedSumAssured(policyNumber, gross, "Partial withdrawal " + id, UnitLedger.SYSTEM)`. `onPaid(PaymentEventListener.Paid)`: mark PAID once and publish `unitlinked.PayoutPaid` (the U1 payload).

`Policy.reduceSumAssuredForWithdrawal(BigDecimal newAmount)`: UNIT_LINKED only, `newAmount > 0`, sets `sumAssuredAmount`. `PolicyApiImpl.reduceUnitLinkedSumAssured`: lock the policy, compute `newAmount = sumAssured - by`, call it, set every `coverage` row of benefit DEATH to `newAmount`, save an `Endorsement(tenantId, policyNumber, "UNIT_LINKED_WITHDRAWAL", today, Map.of("from", old, "to", new, "reason", reason), appliedBy)`, publish `policy.PolicyEndorsed`.

- [ ] **Step 4:** Run `WithdrawalIntegrationTest,UnitLinkedExitsIntegrationTest,UnitLinkedAccountingIntegrationTest` — PASS.
- [ ] **Step 5: Commit** — `feat(unitlinked,policy): partial withdrawals -- named or pro rata, priced after a second person approves, charged, and cutting cover only when the product says so`.

---

### Task 6: The surrender charge on surrenders and non-payment lapses

**Files:**
- Create: `unitlinked/application/SurrenderCharges.java`
- Modify: `Exits.complete` (SURRENDER and LAPSE take the charge before paying), `ExitState` (`surrender_charge` column, `chargeSurrender(BigDecimal)`), `Corrections.settleWithItsExit` (an adjustment on a charged exit is the difference less the same percent)
- Test: `unitlinked/SurrenderChargeIntegrationTest.java`

**Interfaces — Produces:** `SurrenderCharges.percentFor(PolicyView policy, LocalDate valuationDate) -> BigDecimal` (0 when the version has no bands); `SurrenderCharges.charge(BigDecimal proceeds, BigDecimal percent) -> BigDecimal` (HALF_EVEN, 2 dp); event `unitlinked.SurrenderCharged {policyNumber, sourceRef, amount, currencyCode}`.

- [ ] **Step 1: Failing tests.**

```java
    @Test
    void aSurrenderInPolicyYearOnePaysTenPercentAndADeathPaysNothing() {
        Sold s = invested(TODAY.minusDays(70));                       // year 1, 90,000 of units
        surrenderAndPrice(s, "1.000000");
        assertThat(paid("unit-linked:surrender:" + s.surrenderId())).isEqualByComparingTo("81000.00");  // 90,000 less 10%
        Sold d = invested(TODAY.minusDays(70));
        registerDeathAndPrice(d);
        assertThat(sumOf(d, "SURRENDER_CHARGE")).isEqualByComparingTo("0");
    }

    @Test
    void aNonPaymentLapseWithValueIsChargedAndAnExhaustedLapseIsNot() { /* NON_PAYMENT version: lapse → charge; exhaustion → no SURRENDER_CHARGE entry */ }

    @Test
    void aVersionWithoutBandsChargesNothing() { /* U1 standardTerms only: surrender pays the full proceeds */ }
```

- [ ] **Step 2:** Run — FAIL.
- [ ] **Step 3: Implement.** In `Exits.complete(state)`, before `payOut`:

```java
        if ("SURRENDER".equals(state.getPurpose()) || "LAPSE".equals(state.getPurpose())) {
            // Spec Q6/Q7: a surrender and a non-payment lapse are charged; an exhausted fund has nothing left to charge,
            // and death, maturity and free-look never are. The policy year is the valuation date's.
            PolicyView policy = policyApi.getPolicy(state.getPolicyNumber());
            LocalDate on = lastSaleDate(state);
            BigDecimal charge = SurrenderCharges.charge(state.getProceeds(), surrenderCharges.percentFor(policy, on));
            if (charge.signum() > 0) {
                entries.save(UnitEntry.money(TenantContext.get(), state.getPolicyNumber(), UnitEntry.Type.SURRENDER_CHARGE,
                    charge.negate(), on, state.getSourceType(), state.getSourceRef() + ":surrender-charge", null,
                    UnitLedger.SYSTEM, clock.instant()));
                state.chargeSurrender(charge);
                events.publishEvent(DomainEventEnvelope.of("unitlinked.SurrenderCharged", TenantContext.get(), Map.of(
                    "policyNumber", state.getPolicyNumber(), "sourceRef", state.getSourceType() + ":" + state.getSourceRef(),
                    "amount", charge.toPlainString(), "currencyCode", policy.premiumCurrency())));
            }
        }
```

`payOut` pays `proceeds + returnedMoney - surrenderCharge`. The returned (never-invested) money is never charged. `lastSaleDate(state)` = the max `valuation_date` of the exit's sale entries, today's civil date if none. In `Corrections.settleWithItsExit`, when the exit carries a charge, the adjustment amount is `difference × (100 − percent) / 100`. An exhausted-fund lapse already has zero proceeds, so it charges nothing.

- [ ] **Step 4:** Run `SurrenderChargeIntegrationTest,UnitLinkedExitsIntegrationTest,UnitLinkedLapseIntegrationTest,UnitLinkedDeathIntegrationTest,PriceCorrectionIntegrationTest` — PASS.
- [ ] **Step 5: Commit** — `feat(unitlinked): the surrender charge on surrenders and non-payment lapses, never on an exhausted fund`.

---

### Task 7: Top-ups

**Files:**
- Create: `unitlinked/domain/TopUp.java`, `unitlinked/domain/RequestKey.java`, repositories, `unitlinked/application/TopUps.java`, `unitlinked/application/IdempotentRequests.java` (bean name `unitLinkedIdempotentRequests`, a copy of accumulation's with `unitlinked.request_key`), `unitlinked/api/TopUpView.java`, `unitlinked/api/TopUpInput.java`
- Modify: `payment/application/PaymentRequestListener.java` (`case "unitlinked.TopUpRequested" -> handleUnitLinkedTopUp` recording a collection with purpose `UL_TOP_UP`), `unitlinked/application/PaymentEventListener.java` (`payment.PaymentConfirmed` / `payment.PaymentFailed` with `purpose == UL_TOP_UP`), `Allocations` (allocate a received top-up at the version's top-up percent), `FundController` (`POST /policies/{n}/top-ups` reads `Idempotency-Key`)
- Test: `unitlinked/TopUpIntegrationTest.java`

**Interfaces — Produces:** `record TopUpInput(BigDecimal amount, String payerRef, List<UnitLinkedChoice.Split> split)`; `UnitLinkedApi.requestTopUp(String policyNumber, TopUpInput input, String by, String idempotencyKey) -> TopUpView`; `listTopUps(String)`; events `unitlinked.TopUpRequested {topUpId, idempotencyKey "ul-topup:<id>", policyNumber, payerRef, amount {amount, currencyCode}}`, `unitlinked.TopUpReceived {topUpId, policyNumber, sourceRef "top-up:<id>", amount, currencyCode}`; `Allocations.onTopUpReceived(TopUp topUp, Instant receivedAt)`.

- [ ] **Step 1: Failing tests** (`@MockBean PaymentGatewayPort` confirming collections at once, as U1's tests do for disbursements):

```java
    @Test
    void aTopUpBuysUnitsAtItsOwnRateAndARetryCollectsOnce() {
        Sold s = invested();
        String key = UUID.randomUUID().toString();
        var first = asTenant(s.tenant(), () -> api.requestTopUp(s.policyNumber(),
            new TopUpInput(new BigDecimal("200000.00"), "+255700000700", List.of()), "staff-one", key));
        var again = asTenant(s.tenant(), () -> api.requestTopUp(s.policyNumber(),
            new TopUpInput(new BigDecimal("200000.00"), "+255700000700", List.of()), "staff-one", key));
        assertThat(again.topUpId()).isEqualTo(first.topUpId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payment_transaction WHERE purpose = 'UL_TOP_UP' AND source_ref = ?",
            Integer.class, first.topUpId().toString())).isEqualTo(1);
        // 98% of 200,000 = 196,000 buys units by the current split (60/40), at tomorrow's price
        assertThat(units(s).pending()).extracting(o -> o.fundCode() + ":" + o.amount().toPlainString())
            .contains("EQ1:117600.00", "BD1:78400.00");
        assertThat(sumOf(s, "ALLOCATION_CHARGE", "top-up:" + first.topUpId())).isEqualByComparingTo("-4000.00");
    }

    @Test
    void aTopUpOnAFrozenPolicyIsRefundedWhole() { /* freeze via death after the request, then confirm: status REFUNDED, PayoutRequested PREMIUM_RETURN_PAYOUT 200,000 */ }

    @Test
    void aTopUpBelowTheMinimumOrOnAVersionWithoutTopUpsIsRefused() { /* "A top-up is at least 50,000.00 TZS", "This product does not take top-ups" */ }
```

- [ ] **Step 2:** Run — FAIL.
- [ ] **Step 3: Implement.** `TopUps.request` (inside `IdempotentRequests.once(key, "TOP_UP", policyNumber, by, ...)`): offered, in force, not frozen, `amount >= minimumTopUp`; the split resolved by `SplitRules` or the split in force; save; publish `TopUpRequested`. On `PaymentConfirmed` (purpose `UL_TOP_UP`, `sourceRef` = top-up id): if frozen, an open exit takes it (`exits.returnMoney(policyNumber, amount)` adding to `returnedMoney`), else publish `unitlinked.PayoutRequested` purpose `PREMIUM_RETURN_PAYOUT` for the whole amount, mark REFUNDED; otherwise mark RECEIVED, publish `TopUpReceived`, call `Allocations.onTopUpReceived` — the version's `topUpAllocationPercent` via `UnitArithmetic.allocate`, an `ALLOCATION_CHARGE` money entry, and BUY orders `Purpose.ALLOCATION` with `sourceType "top-up"`, `sourceRef "top-up:<id>"`, bound by the confirmation instant. `Allocations.afterPriced` already publishes `UnitsAllocated` per source; it must read `premium` as `toUnits + charge` for a top-up too (no change needed). On `PaymentFailed`: mark FAILED.

- [ ] **Step 4:** Run `TopUpIntegrationTest,UnitLinkedSaleIntegrationTest,BillingApiIntegrationTest` (billing must ignore `UL_TOP_UP`) — PASS.
- [ ] **Step 5: Commit** — `feat(unitlinked,payment): top-ups at their own allocation, collected once per Idempotency-Key, refunded whole to a policy that is leaving`.

---

### Task 8: The ledger for U2

**Files:**
- Modify: `finaccounting/application/UnitLinkedEventListener.java`
- Test: extend `unitlinked/UnitLinkedAccountingIntegrationTest.java`

Postings (append to the javadoc table):

```
SwitchExecuted       DR 2150 / CR 4310            the fee (the value moved stays in 2150)
WithdrawalPriced     DR 2150 / CR 5100 proceeds; DR 5100 / CR 4310 the surrender charge
SurrenderCharged     DR 5100 / CR 4310            (surrender, non-payment lapse)
TopUpReceived        DR cash / CR 2140            (UnitsAllocated then moves it to 2150 and 4310, as U1)
PayoutPaid           unchanged (WITHDRAWAL_PAYOUT and PREMIUM_RETURN_PAYOUT book DR 5100 / CR cash)
```

`PREMIUM_RETURN_PAYOUT` for a refunded top-up: its money never left 2140, so `PayoutPaid` with that purpose posts DR 2140 / CR cash instead — add the branch beside `PRICE_CORRECTION_PAYOUT`.

- [ ] **Step 1: Failing test** — after a switch with a fee, a withdrawal with a charge, a top-up, and a surrender with a charge: `credit(2150) == carried` (units × price), `credit(5100) == 0`, `credit(4310)` = allocation charges + fees + COI + switch fee + surrender charges, `credit(2140)` back to its invoiced-uncollected figure.
- [ ] **Step 2:** Run — FAIL. **Step 3:** add the four `case`s with `pair(...)`. **Step 4:** Run `UnitLinkedAccountingIntegrationTest` — PASS. **Step 5: Commit** — `feat(finaccounting): post switches, withdrawals, top-ups and surrender charges`.

---

### Task 9: Statements

**Files:**
- Create: `unitlinked/domain/UnitStatement.java`, `unitlinked/infrastructure/UnitStatementRepository.java`, `unitlinked/application/Statements.java`, `unitlinked/application/StatementPdf.java`, `unitlinked/application/StatementDrain.java`, `unitlinked/api/UnitStatementView.java`, `unitlinked/api/StatementData.java`
- Modify: `UnitLinkedApi`/`Impl`, `FundController` (`POST /policies/{n}/statements {from, to}`, `GET /policies/{n}/statements`), `communication/application/UnitLinkedEventListener.java` (`unitlinked.StatementIssued` with `annual = true` → SMS `UL_STATEMENT`), `application-local.yml` (`unitlinked.statement-interval-ms: 60000`)
- Test: `unitlinked/StatementIntegrationTest.java`, `unitlinked/StatementPdfTest.java`

**Interfaces — Produces:** `record StatementData(String policyNumber, LocalDate from, LocalDate to, List<Position> opening, List<Position> closing, List<Line> lines, BigDecimal paidIn, Map<String, BigDecimal> chargesByType, BigDecimal paidOut, String currency)` with `record Position(String fundCode, BigDecimal units, BigDecimal price, LocalDate priceDate /* null = no price yet */, BigDecimal value /* null when no price */)`, `record Line(LocalDate date, String type, String fundCode, BigDecimal units, BigDecimal price, BigDecimal amount)`; `Statements.build(String, LocalDate, LocalDate) -> StatementData`; `Statements.file(String, LocalDate, LocalDate, String kind, String by) -> UnitStatementView`; `StatementPdf.render(StatementData, String insurerName) -> byte[]`.

- [ ] **Step 1: Failing tests.**

```java
    @Test
    void aPositionWithNoPriceYetSaysSoAndIsNeverZero() {
        // a fund created on day 2 with no approved price: its opening position has price null, value null
        StatementData d = asTenant(t, () -> statements.build(policy, from, to));
        assertThat(d.opening()).filteredOn(p -> p.fundCode().equals("NEW1")).first()
            .satisfies(p -> { assertThat(p.price()).isNull(); assertThat(p.value()).isNull(); });
    }

    @Test
    void theAnnualStatementIsFiledOncePerPolicyAndYearAndOnDemandAddsToIt() {
        drain.sweepOne(policy, tenant, LocalDate.of(2027, 1, 5));
        drain.sweepOne(policy, tenant, LocalDate.of(2027, 1, 6));                 // a rerun: nothing new
        api.fileStatement(policy, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 9, 30), "staff-one");
        assertThat(api.statements(policy)).extracting(UnitStatementView::kind).containsExactlyInAnyOrder("ANNUAL", "ON_DEMAND");
        assertThat(eventsOfType("unitlinked.StatementIssued")).hasSize(2)
            .filteredOn(e -> Boolean.TRUE.equals(payload(e).get("annual"))).hasSize(1);
    }

    @Test
    void aStatementCannotEndInTheFuture() { /* "A statement period ends today at the latest" */ }
```

`StatementPdfTest`: render a `StatementData` with two funds and five lines; `PDDocument.load(bytes)` text contains the policy number, "No price yet" for a null-priced position, and each fund code.

- [ ] **Step 2:** Run — FAIL.
- [ ] **Step 3: Implement.** `Statements.build`: positions from `entries.sumUnitsAsOf(tenant, policy, fund, date)` (new query: `sum(units) where valuation_date <= ?`), priced by `prices.latestApprovedOnOrBefore(tenant, fund, date)` — **absent ⇒ price/value null** (spec: never zero, never a passed-off older price — the latest approved price ON OR BEFORE the date is the date's price, and it is shown with its own date); lines from `entries` with `valuation_date between from and to` ordered by date then creation. `file`: `to` ≤ today else 422 "A statement period ends today at the latest"; `from` ≤ `to`; render; `documentApi.upload("policy:" + policyNumber, DocumentType.ACCOUNT_STATEMENT, by, ...)` (R6); save `UnitStatement`; publish `unitlinked.StatementIssued {statementId, policyNumber, policyholderPartyId, annual, year, closingValue, currency, priceDate}`. `StatementPdf` follows `accumulation/application/StatementPdf.java`'s layout (PDFBox, one page per 40 lines, header, positions table, movements, totals). `StatementDrain` (`@Scheduled(fixedDelayString = "${unitlinked.statement-interval-ms:86400000}")`): only in January; each tenant's unit-linked policies (U1's SECURITY DEFINER `unitlinked.unit_linked_policies()`) that held units at any time last year and have no ANNUAL statement with `period_to` = last 31 December; `file(..., "ANNUAL", "system:unitlinked")`, each in its own REQUIRES_NEW transaction, an ERROR logged and skipped on failure. `sweepOne(policy, tenant, today)` is the test seam.

- [ ] **Step 4:** Run `StatementIntegrationTest,StatementPdfTest,UnitLinkedNoticeIntegrationTest` — PASS.
- [ ] **Step 5: Commit** — `feat(unitlinked): unit statements -- calendar-year for every policy with an SMS, and on demand for any period`.

---

### Task 10: API documents and the console

**Files:**
- Modify: `backend/api/openapi/openapi-unitlinked.yaml`, `openapi-product.yaml` (`unitLinked.options` request block; `UnitLinkedTerms.options`), `backend/api/asyncapi-events.yaml` (SwitchExecuted, WithdrawalPriced, SurrenderCharged, TopUpRequested, TopUpReceived, StatementIssued)
- Run: `cd frontend && npm run generate:api`
- Modify: `src/api/types.ts`, `src/api/unitlinked.ts`, `src/store/unitLinkedStore.ts`, `src/features/products/unitLinkedSchema.ts`, `src/features/products/UnitLinkedTermsSection.tsx`, `src/features/unitlinked/PolicyUnitsPanel.tsx`, `src/gates/unitLinkedGates.ts`
- Create: `src/features/unitlinked/u2Forms.ts` (+ `u2Forms.test.ts`), `SwitchForm.tsx`, `WithdrawalPanel.tsx`, `TopUpForm.tsx`, `PremiumSplitPanel.tsx`, `StatementsPanel.tsx`

**API (all decimals strings):** `POST|GET /policies/{n}/switches` (`SwitchInput` / `SwitchView` with `boundDate`, `status`, `fee`); `POST /policies/{n}/withdrawals`, `POST /withdrawals/{id}/approval` (finance/admin), `GET /policies/{n}/withdrawals`; `POST /policies/{n}/top-ups` (header `Idempotency-Key` required), `GET /policies/{n}/top-ups`; `PUT|GET /policies/{n}/premium-split`; `POST|GET /policies/{n}/statements`, `GET /unit-statements/{id}/file` (the PDF).

- [ ] **Step 1: Publish-form options.** In `unitLinkedSchema.ts` add `ulFreeSwitches`, `ulSwitchFee`, `ulMinWithdrawal`, `ulMinRemaining`, `ulWithdrawalCutsCover` (boolean), `ulTopUpPercent`, `ulMinTopUp`, `ulSurrenderText` (`fromYear,toYear,percent`, parsed like the allocation bands) to the shape and `blankUnitLinkedFields()` (all blank / false); `validateUnitLinked` mirrors Task 1's messages (pairs both-or-neither; percent bounds; bands from year 1, contiguous, last open); `toUnitLinkedRequest` sends `options` only when any is set. Tests in `unitLinkedSchema.test.ts`: a fee without an allowance; a top-up of 0%; bands from year 2; options omitted when blank. `UnitLinkedTermsSection.tsx` gains a fieldset "Switches, withdrawals, top-ups and the surrender charge" with these fields (labels: "Free switches per policy year", "Fee per extra switch", "Minimum withdrawal", "Minimum value left after a withdrawal", "A withdrawal reduces the sum assured", "Top-up allocation (%)", "Minimum top-up", "Surrender charge bands").
- [ ] **Step 2: `u2Forms.ts`** — zod schemas, each mirroring the server's words: `switchSchema` (out rows `{fundCode, percent}` 1–100 at least one; into split totals 100), `withdrawalSchema(minimum)` (gross ≥ minimum; named rows sum to gross), `topUpSchema(minimum)`, `splitSchema` (totals 100). Tests in `u2Forms.test.ts` for each message.
- [ ] **Step 3: Panels.** On the Units tab, below the ledger: `SwitchForm` (one row per held fund with a percent; target split per offered fund; shows "Free switch N of M this policy year" or "Fee TZS 5,000.00" and "Priced on {boundDate}" after submit); `WithdrawalPanel` (amount, optional per-fund amounts, payee; before submit it shows the estimated charge, net, value left and — when the version cuts cover — the new sum assured, each labelled "estimate at the latest prices"; a REQUESTED withdrawal shows `approveWithdrawalGates` (`proposed`, `second person`) and an Approve button; list of past withdrawals); `TopUpForm` (amount, payer, optional split; sends a fresh `startMutation()` Idempotency-Key per attempt, reused on retry); `PremiumSplitPanel` (current split and history; a change form); `StatementsPanel` (list with download links; on-demand form with from/to). Each panel renders only when its feature is offered (`terms.options`).
- [ ] **Step 4:** `npx tsc -b; npx eslint src; npx vitest run` — all green.
- [ ] **Step 5: Commit** — `feat(console): switches, withdrawals with second-person approval, top-ups, redirection and statements on the Units tab`.

---

### Task 11: Seed, dev database and e2e

- [ ] **Step 1: Seed.** In `backend/scripts/seed-dev-data.sh`, the `UL-INV-01` version body gains `"options":{"freeSwitchesPerYear":2,"switchFee":5000,"minimumWithdrawal":100000,"minimumRemainingValue":500000,"withdrawalReducesSumAssured":false,"topUpAllocationPercent":98,"minimumTopUp":50000,"surrenderCharges":[{"fromYear":1,"toYear":1,"percent":10},{"fromYear":2,"toYear":5,"percent":5},{"fromYear":6,"toYear":null,"percent":0}]}`. (A published version cannot be changed: on dev, publish it as a new version of UL-INV-01.)
- [ ] **Step 2: Dev DB.** Apply `product/V26`, `unitlinked/V3`, `payment/V14`, `communication/V14` with `docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 -q -1 < <file>`; restart the dev backend from this worktree (stop the java.exe owning port 8080 first).
- [ ] **Step 3: e2e `staff-unit-linked-u2.spec.ts`** — forward pricing means nothing a test does today can be priced today (tomorrow's price cannot be approved until tomorrow's cut-off), so the e2e proves every request reaches its correct WAITING state, exactly as U1's e2e does. A product authored with options; a policy sold and its first premium collected; then: a switch requested shows "Priced on {tomorrow}"; a withdrawal requested by the underwriter is refused approval to the same person (gate visible) and approved by finance, showing the estimated charge; a top-up requested twice with one Idempotency-Key creates one row; a redirection lands in the history; an on-demand statement for the last 30 days is listed and downloads a PDF.
- [ ] **Step 4:** `npx playwright test e2e/staff-unit-linked-u2.spec.ts --reporter=line` (with deps) — PASS. Confirm in the database what the run left (switch WAITING with both legs; withdrawal APPROVED with orders; one `UL_TOP_UP` transaction; two split rows; one ON_DEMAND statement).
- [ ] **Step 5: Commit** — `test(e2e): unit-linked U2 through the console`.

---

### Task 12: Gate

- [ ] Full backend `./mvnw -o clean test` (stop the dev backend first) — 0 failures.
- [ ] Frontend `npx tsc -b; npx eslint src; npx vitest run` — green.
- [ ] Full e2e `npx playwright test --reporter=line` (nothing else running; Vite started by Playwright).
- [ ] Whole-branch self-review against the spec and this plan (Global Constraints first).
- [ ] Ask the user to sign off R1–R6 and every deviation; then `git merge --no-ff` to main and `git push origin main`.
