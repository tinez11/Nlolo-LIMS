# Unit-Linked U1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** make UNIT_LINKED a real product: a shared fund register with two-person, forward-applied prices; premiums buy units; monthly charges sell units; death, surrender, maturity, lapse and free-look sell units forward and pay; and the ledger always equals units × price.

**Architecture:** a new Spring Modulith module `unitlinked` owns funds, prices, the append-only unit ledger, pending orders and every unit decision. Product carries the version's unit-linked terms. Underwriting carries the sale choice. Policy issues the policy and routes surrender and maturity to unitlinked by event. Claims asks unitlinked for the death value (as it asks annuity). benefitpayout gains one hook for a unit-linked free-look refund. Payment pays `unitlinked.PayoutRequested`. finaccounting posts the module's events.

**Tech Stack:** Java 21, Spring Boot 3 / Spring Modulith, JPA/Hibernate, hand-written Postgres migrations with RLS, Testcontainers, React + zod + react-hook-form console, Playwright.

**Spec:** `backend/docs/superpowers/specs/2026-10-05-product-step6-unit-linked-u1-design.md` (9c72b8dc). Section references below (§n) are to it.

## Global Constraints

- **Forward pricing everywhere.** An order received at instant `t` for fund F is bound to `D = civilDate(t)` if `civilTime(t) < F.cutOff`, else `D + 1`. `D` is stored once and never recomputed. A price for date `D` cannot be approved before `D` at the cut-off. A surrender binds at **approval**; a death binds at **registration**.
- **A missing price never becomes zero and never falls back to an older price.** An order bound to `D` is priced only by the first APPROVED price dated `≥ D`.
- **Two people for prices, corrections, surrenders and customer-owed adjustments.** The proposer can never approve their own price.
- **Append-only:** `unitlinked.unit_entry` rejects UPDATE and DELETE in the database. A correction is a new entry.
- **One source → one entry → one posting.** Redelivered events are no-ops (unique `(source_type, source_ref, entry_type, fund_id)`).
- **Free-look unwinds the actual entries. There is no formula.**
- **Rounding:** money 2 dp HALF_EVEN, rounded once; units 6 dp, TRUNCATED on a buy and CEIL on a money sell, capped at the holding; prices 6 dp.
- **Civil zone:** `Africa/Dar_es_Salaam` for every date and cut-off. Never `LocalDate.now()` without the zone (the UTC/civil-day bug).
- **Test connections own the tables, so RLS is bypassed:** every tenant-wide query filters `tenant_id` explicitly.
- **Never run Maven concurrently with Playwright or another Maven.** Stop the dev backend before `clean test`. After any record or DTO signature change, run `./mvnw clean test-compile`.
- **Never run Prettier.** Edit files with tools, not shell strings.
- **Commit messages** end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Plan refinements (decisions the spec left to the plan; confirm at the gate)

- **R1. FundDirectory SPI.** Product cannot depend on unitlinked (unitlinked depends on product::api), but publish must refuse a fund that is not OPEN. So `product::api` declares `interface FundDirectory { Optional<FundSummary> find(String fundCode); }`, unitlinked provides the bean, and product injects `ObjectProvider<FundDirectory>`. When none is present (a test context without unitlinked), a version naming funds is refused with "no fund register is available".
- **R2. Versions name funds by code**, not id. Codes are unique per tenant and never reused (CLOSED, never deleted), so a version's fund list reads in its own words.
- **R3. Issuance:** UnderwritingDecisionEventListener gets a UNIT_LINKED branch that issues with the choice's premium and sum assured verbatim (no `quotePremium`). unitlinked then reads the choice on `policy.PolicyIssued` and writes the allocation.
- **R4. Maturity is unitlinked's.** `expirePolicy` skips UNIT_LINKED, as it skips deposits. unitlinked's own sweep sells on the maturity date and calls `policyApi.markMatured` once paid.
- **R5. Surrender:** `requestSurrender` on a UNIT_LINKED policy skips the cash-value and policy_account checks, applies `minimumSurrenderYears`, and records an **indicative** quoted value (units × latest price), labelled indicative. `approveSurrender` publishes `policy.UnitLinkedSurrenderApproved`. unitlinked binds the sale at that event's `approvedAt`, and on pricing publishes `unitlinked.PayoutRequested` (purpose SURRENDER_PAYOUT, sourceRef = surrender request id), so step 1's SurrenderPaymentListener marks the request PAID unchanged.
- **R6. Free-look:** `requestFreeLook` on UNIT_LINKED refuses manual deductions and records `refund_pending = true`. `approveFreeLook` cancels the policy but publishes no payout. unitlinked unwinds and, once priced, calls `benefitPayoutApi.releaseUnitLinkedFreeLookRefund(cancellationId, amount)`, which sets the amount and publishes the same `benefitpayout.PayoutRequested` FREE_LOOK_REFUND the approval does today.
- **R7. Death:** claims gains `unitlinked::api`. `ceilingFor` asks `unitLinkedApi.deathValue(claimId)`, which throws `UnitsNotYetPricedException` (409 UNITS_NOT_YET_PRICED) until the sale is priced.
- **R8. Lapse exemption:** PolicyLapseRecommendedEventListener skips a UNIT_LINKED policy whose version says EXHAUSTION once `minimumPremiumYears` have passed since commencement.
- **R9.** A UNIT_LINKED policy has no `policy_account` cash value; paid-up and policy loans are refused for it in U1, with a 409 that says so.
- **R10. Reconciliation SPI.** The "2150 = units × price" staff report lives in finaccounting, which owns the balance. The units side arrives through `product::api`'s `UnitLinkedValuation` SPI, implemented by unitlinked, because finaccounting may depend only on product and refdata.
- **R11. A unit-linked surrender request carries no quoted value.** It is priced forward after approval; the console shows an indicative value from the units endpoint, labelled as such.

## Corrections from the pre-start check (2026-10-05, read-only, against the code; user said "proceed")

- **C1.** Do **not** remove `fundDefinitions` / `FundInput` from the publishVersion overloads: 149 call sites in 48 test files pass it. Keep every signature. `ProductApiImpl` refuses a **non-empty** list with `InvalidProductVersionException("fundDefinitions is replaced by unit-linked terms' fund codes")`, and stops persisting it. Drop the `product.fund_definition` table, the `FundDefinition` entity and `FundDefinitionRepository`. Task 2 Step 1's signature-change paragraph is superseded by this.
- **C2.** `PolicyApi.IssueRequest` has **16** components; the last is `IssuanceBasis issuanceBasis`. Task 3's snippet passes `null` for it as the 16th argument, and the 13th is `policyTermMonths`.
- **C3.** `PolicyView` already carries `underwritingCaseId`. unitlinked reads it via `policyApi.getPolicy(policyNumber)` on `policy.PolicyIssued`; the PolicyIssued payload is **not** changed.
- **C4.** `claims.ClaimRegistered` carries no `registeredAt`. Add it (an additive key, `Instant.now()` at publication inside the registering transaction) in `ClaimsApiImpl`, and in `asyncapi-events.yaml`, in Task 7.
- **C5.** `ReferenceDataApi.getValue` throws `NoSuchElementException` on a missing key. Seed `UL_PRICE_MOVE_ALERT_PERCENT` = `10` (jurisdiction TZ) in `db-migrations/refdata/V7__unit_linked_price_move_alert.sql` (copy V6's insert shape), and add it to `UnitLinkedTestMigrations`. The code does not default it.
- **C6.** There is no `java.time.Clock` bean. unitlinked declares `@Bean("unitLinkedClock") Clock unitLinkedClock() { return Clock.system(BindingRule.CIVIL_ZONE); }` in a `UnitLinkedConfiguration`. `FundRegister` and the sweeps inject `@Qualifier("unitLinkedClock") Clock`; tests override it with `@MockBean(name = "unitLinkedClock")` or a `@TestConfiguration` returning `Clock.fixed(...)`.
- **Branch:** `product-step6-unit-linked` was created from `product-family-funeral` at 93072731 (the user chose this over waiting for the funeral merge). Merge `main` in once funeral lands. Task 0 is done. No Maven runs while the funeral e2e suite is running.

---

## File map

**Create, backend module `unitlinked`** (`backend/src/main/java/tz/co/nlolo/lifeplatform/unitlinked/`):

| File | Responsibility |
|---|---|
| `package-info.java` | module, allowedDependencies |
| `api/UnitLinkedApi.java` | the module's public surface |
| `api/FundView.java`, `FundPriceView.java`, `HoldingView.java`, `PendingOrderView.java`, `UnitEntryView.java`, `DeathValueView.java`, `AdjustmentView.java`, `ReconciliationView.java` | views |
| `api/UnitsNotYetPricedException.java`, `UnitLinkedStateException.java`, `FundNotFoundException.java` | errors |
| `domain/BindingRule.java` | pure: cut-off → bound date; earliest approval instant |
| `domain/UnitArithmetic.java` | pure: allocation, split, buy/sell units, proceeds |
| `domain/CostOfInsurance.java` | pure: sum at risk + monthly COI |
| `domain/Fund.java`, `FundPrice.java`, `PendingOrder.java`, `UnitEntry.java`, `PolicyAllocation.java`, `PriceCorrectionAdjustment.java`, `FrozenPolicy.java` | entities |
| `infrastructure/*Repository.java` | one file per repository |
| `infrastructure/FundController.java`, `FundPriceController.java`, `UnitsController.java`, `UnitLinkedExceptionHandler.java`, request/response records | HTTP |
| `application/UnitLinkedApiImpl.java` | facade |
| `application/FundRegister.java` | funds + prices + maker-checker + CSV |
| `application/PricingRun.java` | prices WAITING orders when a price is approved; revaluation |
| `application/UnitLedger.java` | writes entries, holdings, idempotency |
| `application/Allocations.java` | premium → BUY orders |
| `application/ChargeRun.java`, `ChargeSweep.java` | monthly charges, exhaustion |
| `application/Exits.java` | death, surrender, maturity, lapse, free-look |
| `application/MaturitySweep.java` | maturity date → SELL ALL |
| `application/Corrections.java` | price corrections + adjustments |
| `application/FundDirectoryImpl.java` | R1 SPI bean |
| `application/BillingEventListener.java`, `PolicyEventListener.java`, `ClaimEventListener.java`, `UnitLinkedEnvelopeRunner.java` | event intake |

**Create, migrations:** `db-migrations/unitlinked/V1__create_unitlinked_schema.sql`, `db-migrations/unitlinked/V2__units.sql`, `db-migrations/product/V25__unit_linked_terms.sql`, `db-migrations/underwriting/V17__unit_linked_choice.sql`, `db-migrations/policy/V36__unit_linked_policy.sql`, `db-migrations/finaccounting/V9__unit_linked_accounts.sql`, `db-migrations/communication/V13__unit_linked_templates.sql`, `db-migrations/benefitpayout/V6__free_look_refund_pending.sql`.

**Modify (backend):** product (`ProductApi`, `ProductApiImpl`, `PublishVersionRequest`, `ProductController`, new `UnitLinkedTermsStore`, `UnitLinkedPlanValidator`, entities); underwriting (`UnderwritingApi`, new `UnitLinkedChoices`, `OpenCaseRequest`, controller); policy (`UnderwritingDecisionEventListener`, `PolicyApiImpl` surrender/expire/paid-up/loan guards, `PolicyLapseRecommendedEventListener`); claims (`package-info`, `ClaimsApiImpl.ceilingFor`, exception handler); benefitpayout (`BenefitPayoutApi`, `BenefitPayoutApiImpl` free-look); payment (`PaymentRequestListener`); finaccounting (`PostingRule`, `ChartOfAccountBlueprint`, new `UnitLinkedEventListener`, reconciliation); communication (new `UnitLinkedEventListener`); `docs/api/openapi-*.yaml`, `docs/api/asyncapi-events.yaml`; `scripts/seed-dev-data.sh`.

**Frontend:** `src/api/unitlinked.ts`, `src/api/types.ts`; `src/features/funds/` (FundsPage, FundPricesPanel, priceForm.ts, AdjustmentsPanel); `src/features/products/UnitLinkedTermsSection.tsx`, `unitLinkedSchema.ts`; `src/features/underwriting/UnitLinkedChoiceFields.tsx`; `src/features/policies/UnitsPanel.tsx`; routes and nav; `e2e/staff-unit-linked.spec.ts`.

---

### Task 0: Branch

- [ ] **Step 1:** after family funeral cover is merged and pushed, from the main checkout:

```bash
git worktree add .worktrees/product-step6-unit-linked -b product-step6-unit-linked main
cd .worktrees/product-step6-unit-linked/frontend && cmd //c mklink /J node_modules ..\\..\\..\\frontend\\node_modules
```

- [ ] **Step 2:** `cd ../backend && ./mvnw -o -q test-compile` (expect BUILD SUCCESS) and copy this plan in if it is not already on main.

---

### Task 1: Module skeleton, fund register and maker-checker prices

**Files:**
- Create: `db-migrations/unitlinked/V1__create_unitlinked_schema.sql`
- Create: `unitlinked/package-info.java`, `api/{UnitLinkedApi,FundView,FundPriceView,FundNotFoundException,UnitLinkedStateException}.java`, `domain/{BindingRule,Fund,FundPrice}.java`, `infrastructure/{FundRepository,FundPriceRepository,FundController,FundPriceController,UnitLinkedExceptionHandler,FundRequests}.java`, `application/{UnitLinkedApiImpl,FundRegister}.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/unitlinked/{BindingRuleTest,FundRegisterIntegrationTest,UnitLinkedTestMigrations,UnitLinkedTestFixtures}.java`

**Interfaces:**
- Produces:
  - `BindingRule.boundDate(Instant receivedAt, LocalTime cutOff) → LocalDate`
  - `BindingRule.earliestApproval(LocalDate valuationDate, LocalTime cutOff) → Instant`
  - `UnitLinkedApi.createFund(CreateFund, String by) → FundView`
  - `closeFund(String code, String by)`
  - `proposePrice(String fundCode, LocalDate date, BigDecimal price, String moveReason, String by) → FundPriceView`
  - `proposePrices(String csv, String by) → List<FundPriceView>`
  - `approvePrice(UUID priceId, String by) → FundPriceView`
  - `withdrawPrice(UUID priceId, String by)`
  - `listFunds()`, `listPrices(String fundCode, LocalDate from, LocalDate to)`
  - `latestApprovedPrice(UUID fundId, LocalDate before) → Optional<FundPrice>`
  - Event `unitlinked.PriceApproved {priceId, fundId, fundCode, valuationDate, price, approvedBy}`

- [ ] **Step 1: Write the migration.**

```sql
-- db-migrations/unitlinked/V1__create_unitlinked_schema.sql
-- Product step 6 (U1): the insurer's fund register and its prices. A price is entered by one person
-- and approved by a second, never before its valuation date's cut-off, and never edited once approved.
CREATE SCHEMA IF NOT EXISTS unitlinked;
GRANT USAGE ON SCHEMA unitlinked TO app_role;

CREATE TABLE unitlinked.fund (
    fund_id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                        UUID NOT NULL,
    code                             VARCHAR(20) NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{1,19}$'),
    name                             VARCHAR(120) NOT NULL,
    currency                         CHAR(3) NOT NULL,
    asset_class                      VARCHAR(15) NOT NULL CHECK (asset_class IN ('EQUITY','BOND','MONEY_MARKET','BALANCED')),
    annual_management_charge_percent NUMERIC(7,4) NOT NULL CHECK (annual_management_charge_percent >= 0 AND annual_management_charge_percent < 100),
    cut_off_time                     TIME NOT NULL,
    status                           VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
    created_by                       VARCHAR(100) NOT NULL,
    created_at                       TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_by                        VARCHAR(100),
    closed_at                        TIMESTAMPTZ,
    version                          BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, code)
);

CREATE TABLE unitlinked.fund_price (
    price_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    fund_id             UUID NOT NULL REFERENCES unitlinked.fund(fund_id),
    valuation_date      DATE NOT NULL,
    price               NUMERIC(19,6) NOT NULL CHECK (price > 0),
    status              VARCHAR(12) NOT NULL CHECK (status IN ('PROPOSED','APPROVED','SUPERSEDED','WITHDRAWN')),
    move_reason         VARCHAR(500),
    supersedes_price_id UUID REFERENCES unitlinked.fund_price(price_id),
    proposed_by         VARCHAR(100) NOT NULL,
    proposed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by         VARCHAR(100),
    approved_at         TIMESTAMPTZ,
    version             BIGINT NOT NULL DEFAULT 0,
    CHECK (approved_by IS NULL OR approved_by <> proposed_by),
    CHECK ((status IN ('APPROVED','SUPERSEDED')) = (approved_by IS NOT NULL AND approved_at IS NOT NULL))
);
-- One price in force per fund and date; one live proposal per fund and date.
CREATE UNIQUE INDEX ux_fund_price_approved ON unitlinked.fund_price (fund_id, valuation_date) WHERE status = 'APPROVED';
CREATE UNIQUE INDEX ux_fund_price_proposed ON unitlinked.fund_price (fund_id, valuation_date) WHERE status = 'PROPOSED';
CREATE INDEX idx_fund_price_lookup ON unitlinked.fund_price (fund_id, status, valuation_date);

-- An approved price's figures never change, even for the owner tests connect as.
CREATE OR REPLACE FUNCTION unitlinked.price_is_final() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('APPROVED','SUPERSEDED') AND (NEW.price IS DISTINCT FROM OLD.price
        OR NEW.valuation_date IS DISTINCT FROM OLD.valuation_date OR NEW.fund_id IS DISTINCT FROM OLD.fund_id
        OR (OLD.status = 'SUPERSEDED' AND NEW.status <> 'SUPERSEDED')
        OR (OLD.status = 'APPROVED' AND NEW.status NOT IN ('APPROVED','SUPERSEDED'))) THEN
        RAISE EXCEPTION 'An approved fund price never changes (price %); correct it with a superseding price', OLD.price_id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER fund_price_is_final BEFORE UPDATE ON unitlinked.fund_price
    FOR EACH ROW EXECUTE FUNCTION unitlinked.price_is_final();
CREATE TRIGGER fund_price_no_delete BEFORE DELETE ON unitlinked.fund_price
    FOR EACH ROW EXECUTE FUNCTION unitlinked.price_is_final();

ALTER TABLE unitlinked.fund ENABLE ROW LEVEL SECURITY;
CREATE POLICY fund_tenant_isolation ON unitlinked.fund
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE unitlinked.fund_price ENABLE ROW LEVEL SECURITY;
CREATE POLICY fund_price_tenant_isolation ON unitlinked.fund_price
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON unitlinked.fund, unitlinked.fund_price TO app_role;
```

Note: the DELETE trigger raises for every row (OLD.status is irrelevant to the message). Confirm the function returns before the status test for DELETE: add `IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'fund prices are never deleted (price %)', OLD.price_id; END IF;` as the function's first statement.

- [ ] **Step 2: Write the failing pure test** for the binding rule.

```java
package tz.co.nlolo.lifeplatform.unitlinked;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.unitlinked.domain.BindingRule;
import java.time.*;
import static org.assertj.core.api.Assertions.assertThat;

class BindingRuleTest {
    private static final LocalTime CUT_OFF = LocalTime.of(14, 0);
    private static Instant eat(String local) { return LocalDateTime.parse(local).atZone(ZoneId.of("Africa/Dar_es_Salaam")).toInstant(); }

    @Test void beforeTheCutOffBindsToToday() {
        assertThat(BindingRule.boundDate(eat("2026-11-02T13:59:59"), CUT_OFF)).isEqualTo(LocalDate.of(2026, 11, 2));
    }
    @Test void atOrAfterTheCutOffBindsToTomorrow() {
        assertThat(BindingRule.boundDate(eat("2026-11-02T14:00:00"), CUT_OFF)).isEqualTo(LocalDate.of(2026, 11, 3));
    }
    @Test void justAfterMidnightEatIsThatCivilDayNotYesterdayInUtc() {
        // 00:30 EAT is 21:30 UTC the day before -- the UTC/civil-day trap.
        assertThat(BindingRule.boundDate(eat("2026-11-02T00:30:00"), CUT_OFF)).isEqualTo(LocalDate.of(2026, 11, 2));
    }
    @Test void aPriceCannotBeApprovedBeforeItsDatesCutOff() {
        assertThat(BindingRule.earliestApproval(LocalDate.of(2026, 11, 2), CUT_OFF)).isEqualTo(eat("2026-11-02T14:00:00"));
    }
    @Test void noOrderReceivedBeforeApprovalCanBeBoundToADateAlreadyApprovable() {
        // The invariant: an order bound to D was received before D's cut-off, and D's price is approvable only after it.
        Instant received = eat("2026-11-02T13:00:00");
        LocalDate bound = BindingRule.boundDate(received, CUT_OFF);
        assertThat(BindingRule.earliestApproval(bound, CUT_OFF)).isAfter(received);
    }
}
```

- [ ] **Step 3: Run it** to see it fail. Run: `./mvnw -o -q test -Dtest=BindingRuleTest`. Expected: compilation failure, `BindingRule` missing.

- [ ] **Step 4: Implement `BindingRule`.**

```java
package tz.co.nlolo.lifeplatform.unitlinked.domain;

import java.time.*;

/**
 * The forward-pricing rule (spec §5), in one place. An order received before a fund's cut-off on civil day
 * D is bound to D; at or after it, to D + 1. A price for D can only be approved at D's cut-off or later,
 * so a bound order can never meet a price that existed when it was received.
 */
public final class BindingRule {
    public static final ZoneId CIVIL_ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private BindingRule() {}

    public static LocalDate boundDate(Instant receivedAt, LocalTime cutOff) {
        ZonedDateTime local = receivedAt.atZone(CIVIL_ZONE);
        return local.toLocalTime().isBefore(cutOff) ? local.toLocalDate() : local.toLocalDate().plusDays(1);
    }

    public static Instant earliestApproval(LocalDate valuationDate, LocalTime cutOff) {
        return valuationDate.atTime(cutOff).atZone(CIVIL_ZONE).toInstant();
    }
}
```

Run the test again. Expected: 5 passed.

- [ ] **Step 5: Entities and repositories.**
  - `Fund`: fields as in the migration. Methods: `close(String by)` (refuses if already CLOSED with `UnitLinkedStateException`), `isOpen()`.
  - `FundPrice`: static `propose(tenantId, fundId, date, price, moveReason, supersedes, by)`. `approve(String by, Instant now, LocalTime cutOff)`:
    - throws `UnitLinkedStateException("The price of <code> for <date> was proposed by <by>; a second person approves it")` when `by.equals(proposedBy)`;
    - throws `UnitLinkedStateException("<date>'s price cannot be approved before its <HH:mm> cut-off")` when `now.isBefore(BindingRule.earliestApproval(...))`;
    - throws unless PROPOSED.

    Also `supersede()`, `withdraw(by)` (proposer only, PROPOSED only).
  - `FundPriceRepository` queries (each filters `tenantId` explicitly):
    - `Optional<FundPrice> findApproved(UUID tenantId, UUID fundId, LocalDate date)`;
    - `Optional<FundPrice> findLatestApprovedBefore(UUID tenantId, UUID fundId, LocalDate before)` (`order by valuationDate desc limit 1`, status APPROVED, date `< :before`);
    - `Optional<FundPrice> findLatestApprovedOnOrBefore(...)`;
    - `List<FundPrice> findByTenantIdAndFundIdAndValuationDateBetweenOrderByValuationDateDesc(...)`.

- [ ] **Step 6: `FundRegister`** (`@Component`, `@Transactional` methods).
  - `createFund`: validates the code pattern, a non-blank name, currency `[A-Z]{3}`, charge 0 ≤ x < 100, a non-null cut-off. Saves.
  - `proposePrice`:
    - refuses a CLOSED fund ("Fund X is closed; ...") only for a date after `closed_at`'s civil date. A closed fund keeps pricing existing units, so pricing a closed fund is **allowed**.
    - Looks up the previous approved price before the date. If present and `|price/prev − 1| × 100 > tenant setting price_move_alert_percent` (refdata key `UL_PRICE_MOVE_ALERT_PERCENT`, default 10 when absent), a blank `moveReason` is refused: "A move of <x>% from <prev> on <date> needs a reason".
    - A second PROPOSED for the same fund+date is refused readably before the unique index fires.
  - `proposePrices(csv)`: header `fund_code,valuation_date,price`. Parse all rows first; any bad row refuses the whole file with "Row n: ...". Then propose each.
  - `approvePrice`: loads the fund for its cut-off, calls `price.approve(by, Instant.now(), fund.getCutOffTime())`, saves, then publishes `unitlinked.PriceApproved`. For a correction (`supersedesPriceId != null`) it first sets the old price SUPERSEDED in the same transaction; Task 5 handles the re-run.

- [ ] **Step 7: HTTP.**
  - `POST /funds` (ADMIN or FINANCE);
  - `POST /funds/{code}/closure` (ADMIN);
  - `GET /funds`;
  - `POST /fund-prices` (body `{fundCode, valuationDate, price, moveReason}`; FINANCE or ADMIN);
  - `POST /fund-prices/csv` (text/csv);
  - `POST /fund-prices/{id}/approval`;
  - `POST /fund-prices/{id}/withdrawal`;
  - `GET /funds/{code}/prices?from&to`.

  The exception handler maps `UnitLinkedStateException` → 409 `UNIT_LINKED_STATE`, `FundNotFoundException` → 404 `FUND_NOT_FOUND`, and IllegalArgumentException → 422. Use the staff realm and role annotations exactly as `VestingController` does.

- [ ] **Step 8: `package-info.java`.**

```java
/**
 * Unit-linked (product step 6, U1): the fund register, two-person prices, the unit ledger, and every
 * decision that moves units -- premiums bought, charges sold, deaths, surrenders, maturities, lapses and
 * free-look unwinds, each priced FORWARD. It pays nothing itself: payment pays its PayoutRequested;
 * benefitpayout releases a free-look refund; claims pays the death through its own settlement.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {
    "policy::api", "product::api", "underwriting::api", "party::api", "benefitpayout::api", "refdata::api" })
package tz.co.nlolo.lifeplatform.unitlinked;
```

- [ ] **Step 9: Test migrations and integration test.** `UnitLinkedTestMigrations.ALL` = `FuneralTestMigrations.ALL` (it already chains annuity, deposit and accumulation) + `"db-migrations/unitlinked/V1__create_unitlinked_schema.sql"`. Later tasks append to it. `FundRegisterIntegrationTest` (Testcontainers, the `@SpringBootTest` + `MigrationTestSupport` shape of `FuneralProductIntegrationTest`):
  - `aPriceProposedByOnePersonIsApprovedOnlyBySomeoneElse`: the same person → 409, the message contains "a second person";
  - `aPriceCannotBeApprovedBeforeItsCutOff`: propose for `today` with a cut-off of 23:59 → approve → 409 containing "cut-off";
  - `onlyOneApprovedPricePerFundAndDate`;
  - `anApprovedPriceCannotBeUpdatedEvenByTheOwner`: native `UPDATE unitlinked.fund_price SET price = 1 WHERE ...` → `DataIntegrityViolationException` or `PSQLException` containing "never changes";
  - `aBigMoveNeedsAReason`: prev 1.000000, then 1.200000 with no reason → 422 containing "needs a reason"; with a reason → saved;
  - `aCsvWithOneBadRowProposesNothing`;
  - `aClosedFundStillPrices`.

  To approve "after the cut-off" deterministically, create the fund with `cut_off_time = 00:00:01` and propose for yesterday's civil date.

- [ ] **Step 10:** Run `./mvnw -o -q test -Dtest='BindingRuleTest,FundRegisterIntegrationTest,ModularityTests'`. Expected: all pass, and ModularityTests stays green with the new module.

- [ ] **Step 11:** Commit: `feat(unitlinked): the fund register and two-person, never-early, never-edited fund prices`.

---

### Task 2: Unit-linked terms on the product version

**Files:**
- Create: `db-migrations/product/V25__unit_linked_terms.sql`
- Create: `product/api/{UnitLinkedPlan,UnitLinkedPlanInput,AllocationBand,MortalityRow,MortalityBasis,DeathRule,LapseRule,PremiumMinimum,FundDirectory,FundSummary}.java`
- Create: `product/domain/{UnitLinkedPlanValidator,UnitLinkedTermsEntity,UnitLinkedFundEntity,UnitLinkedAllocationBandEntity,UnitLinkedMortalityEntity,UnitLinkedPremiumMinimumEntity}.java`
- Create: `product/application/UnitLinkedTermsStore.java`, a repository per entity
- Modify: `ProductApi`, `ProductApiImpl`, `PublishVersionRequest`, `ProductController`, `ProductExceptionHandler`
- Test: `product/UnitLinkedPlanValidatorTest.java`, `product/UnitLinkedTermsIntegrationTest.java`; move the publishVersion overload count in `ProductApiIntegrationTest` (11 → 12)

**Interfaces:**
- Produces:
  - `ProductApi.resolveUnitLinkedPlan(UUID versionId) → UnitLinkedPlan` (`UnitLinkedPlan.none()` when absent);
  - the 12th `publishVersion` overload, taking `UnitLinkedPlanInput`;
  - `UnitLinkedPlan` record: `List<String> fundCodes, List<AllocationBand> allocationBands, BigDecimal monthlyPolicyFee, MortalityBasis mortalityBasis, List<MortalityRow> mortality, DeathRule deathRule, LapseRule lapseRule, Integer minimumPremiumYears, int minimumSurrenderYears, int lowFundWarningMonths, List<PremiumMinimum> premiumMinimums, BigDecimal sumAssuredMultipleMin, BigDecimal sumAssuredMultipleMax`;
  - methods `boolean unitLinked()`, `BigDecimal allocationPercent(int policyYear)`, `BigDecimal annualRatePerMille(int age, String sex)` (throws `IllegalStateException("BY_SEX table and no sex recorded")` when needed), `Optional<BigDecimal> minimumPremium(String frequency)`;
  - `AllocationBand(int fromYear, Integer toYear, BigDecimal percent)`, `MortalityRow(int ageFrom, Integer ageTo, String sex /* null on UNISEX */, BigDecimal annualRatePerMille)`, `PremiumMinimum(String frequency, BigDecimal amount)`;
  - enums `MortalityBasis {UNISEX, BY_SEX}`, `DeathRule {HIGHER_OF, SUM_ASSURED_PLUS_FUND}`, `LapseRule {EXHAUSTION, NON_PAYMENT}`;
  - SPI `FundDirectory { Optional<FundSummary> find(String code); }` and `FundSummary(String code, String currency, boolean open)`.

- [ ] **Step 1: Migration.**

```sql
-- db-migrations/product/V25__unit_linked_terms.sql
-- Product step 6 (U1): what a UNIT_LINKED version offers. Funds are named by their register code
-- (plan R2); every rule the unit engine applies is data on the version, never code.
CREATE TABLE product.unit_linked_terms (
    product_version_id        UUID PRIMARY KEY REFERENCES product.product_version(product_version_id),
    tenant_id                 UUID NOT NULL,
    monthly_policy_fee        NUMERIC(19,2) NOT NULL CHECK (monthly_policy_fee >= 0),
    mortality_basis           VARCHAR(8)  NOT NULL CHECK (mortality_basis IN ('UNISEX','BY_SEX')),
    death_rule                VARCHAR(25) NOT NULL CHECK (death_rule IN ('HIGHER_OF','SUM_ASSURED_PLUS_FUND')),
    lapse_rule                VARCHAR(12) NOT NULL DEFAULT 'EXHAUSTION' CHECK (lapse_rule IN ('EXHAUSTION','NON_PAYMENT')),
    minimum_premium_years     INTEGER CHECK (minimum_premium_years IS NULL OR minimum_premium_years BETWEEN 1 AND 50),
    minimum_surrender_years   INTEGER NOT NULL CHECK (minimum_surrender_years BETWEEN 0 AND 50),
    low_fund_warning_months   INTEGER NOT NULL CHECK (low_fund_warning_months BETWEEN 1 AND 60),
    sum_assured_multiple_min  NUMERIC(9,2) NOT NULL CHECK (sum_assured_multiple_min > 0),
    sum_assured_multiple_max  NUMERIC(9,2) NOT NULL,
    CHECK (sum_assured_multiple_max >= sum_assured_multiple_min)
);
CREATE TABLE product.unit_linked_fund (
    product_version_id UUID NOT NULL REFERENCES product.unit_linked_terms(product_version_id),
    tenant_id          UUID NOT NULL,
    fund_code          VARCHAR(20) NOT NULL,
    PRIMARY KEY (product_version_id, fund_code)
);
CREATE TABLE product.unit_linked_allocation_band (
    product_version_id UUID NOT NULL REFERENCES product.unit_linked_terms(product_version_id),
    tenant_id          UUID NOT NULL,
    from_year          INTEGER NOT NULL CHECK (from_year >= 1),
    to_year            INTEGER CHECK (to_year IS NULL OR to_year >= from_year),
    allocation_percent NUMERIC(7,4) NOT NULL CHECK (allocation_percent > 0 AND allocation_percent <= 100),
    PRIMARY KEY (product_version_id, from_year)
);
CREATE TABLE product.unit_linked_mortality (
    product_version_id    UUID NOT NULL REFERENCES product.unit_linked_terms(product_version_id),
    tenant_id             UUID NOT NULL,
    age_from              INTEGER NOT NULL CHECK (age_from BETWEEN 0 AND 120),
    age_to                INTEGER CHECK (age_to IS NULL OR age_to >= age_from),
    sex                   VARCHAR(6) CHECK (sex IS NULL OR sex IN ('FEMALE','MALE')),
    annual_rate_per_mille NUMERIC(9,4) NOT NULL CHECK (annual_rate_per_mille >= 0),
    UNIQUE (product_version_id, age_from, sex)
);
CREATE TABLE product.unit_linked_premium_minimum (
    product_version_id UUID NOT NULL REFERENCES product.unit_linked_terms(product_version_id),
    tenant_id          UUID NOT NULL,
    frequency          VARCHAR(12) NOT NULL CHECK (frequency IN ('MONTHLY','QUARTERLY','SEMI_ANNUAL','ANNUAL','SINGLE')),
    minimum_amount     NUMERIC(19,2) NOT NULL CHECK (minimum_amount > 0),
    PRIMARY KEY (product_version_id, frequency)
);
-- RLS (NULLIF predicate) + GRANT SELECT, INSERT to app_role on all five, as V24 does for funeral_*.
```

Write the five `ENABLE ROW LEVEL SECURITY` / `CREATE POLICY ..._tenant_isolation ... USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid)` / `GRANT SELECT, INSERT ON ... TO app_role` blocks out in full, copying V24's text with the table names changed. Then:

```sql
-- product.fund_definition is superseded by the register (spec §1). No UNIT_LINKED policy exists to depend on it.
DROP TABLE product.fund_definition;
```

**Superseded by C1:** keep every signature and refuse a non-empty list. ~~Removing `fund_definition` also removes `FundDefinition`, `FundDefinitionRepository`, `FundInput`, and the `fundDefinitions` parameter of every publishVersion overload.~~ **This is a signature change touching every caller:** grep `FundInput` and `fundDefinitions` across `src/main` and `src/test`, pass nothing (delete the argument) in each, and run `./mvnw -o clean test-compile` before moving on. Keep `PublishVersionRequest.fundDefinitions` deserialisable but rejected: a non-empty value returns 422 "fundDefinitions is replaced by unitLinked.fundCodes".

- [ ] **Step 2: Failing validator tests** (`UnitLinkedPlanValidatorTest`, pure). Each asserts the exact message from Step 3:
  - non-UNIT_LINKED category with terms → "Unit-linked terms are only valid on a UNIT_LINKED product";
  - UNIT_LINKED without terms → "A UNIT_LINKED version needs unit-linked terms";
  - no funds → "offers at least one fund";
  - a duplicate fund code;
  - a fund the directory does not know → "Fund ABC is not in the fund register";
  - a closed fund → "Fund ABC is closed";
  - a fund currency ≠ the product's default currency → "is priced in USD; this product is TZS";
  - no allocation bands;
  - bands not starting at year 1;
  - a gap or overlap → "Allocation bands must run on from year 1 without gaps";
  - last band closed → "The last allocation band must be open-ended";
  - mortality empty;
  - mortality not starting at the eligibility min entry age (or 0 when none);
  - a mortality gap;
  - last mortality band closed → "The last mortality band must be open-ended (a whole-of-life policy has no maximum age)";
  - BY_SEX missing one sex's rows → "A BY_SEX table needs FEMALE and MALE rows for every band";
  - UNISEX with a sex row;
  - no premium minimum;
  - a SINGLE minimum and a regular one on the same version (both are allowed, so the test asserts acceptance);
  - min multiple > max (DB also checks; the validator says it readably);
  - free-look missing (PayoutPlanValidator already refuses; one test asserts the UNIT_LINKED message reaches the caller).

- [ ] **Step 3: Implement `UnitLinkedPlanValidator.validate(ProductCategory, UnitLinkedPlanInput, String productCurrency, Integer minEntryAge, FundDirectory directory)`.** Throw `InvalidProductVersionException` with exactly the messages in Step 2. Band continuity:

```java
int expected = 1;
for (int i = 0; i < bands.size(); i++) {
    AllocationBand b = bands.get(i);
    if (b.fromYear() != expected) fail("Allocation bands must run on from year 1 without gaps; band " + (i + 1) + " starts at year " + b.fromYear());
    boolean last = i == bands.size() - 1;
    if (last && b.toYear() != null) fail("The last allocation band must be open-ended");
    if (!last && b.toYear() == null) fail("Only the last allocation band may be open-ended");
    if (!last) expected = b.toYear() + 1;
}
```

Use the same shape for mortality per sex (`UNISEX`: the one null-sex series; `BY_SEX`: the FEMALE series and the MALE series, each checked).

- [ ] **Step 4: `UnitLinkedTermsStore`** (persist + read, the `FuneralTermsStore` shape) and `ProductApiImpl` wiring:
  1. The 12th overload runs the validator early, as funeral's does.
  2. UNIT_LINKED versions skip the base-rate requirement and the AGE/SUM_ASSURED_BAND rating rule (as FUNERAL does).
  3. Persist after the version row.
  4. Add `resolveUnitLinkedPlan`.

  `FundDirectory` is injected as `ObjectProvider<FundDirectory>`; when `getIfAvailable()` is null and terms name funds, refuse "No fund register is available to check the funds against".

- [ ] **Step 5: HTTP.** `PublishVersionRequest` gains a `unitLinked` block:

  `{fundCodes[], allocationBands[{fromYear,toYear,percent}], monthlyPolicyFee, mortalityBasis, mortality[{ageFrom,ageTo,sex,annualRatePerMille}], deathRule, lapseRule, minimumPremiumYears, minimumSurrenderYears, lowFundWarningMonths, premiumMinimums[{frequency,amount}], sumAssuredMultipleMin, sumAssuredMultipleMax}`

  Add `GET /products/{productId}/versions/{versionId}/unit-linked` → the terms, or 404 `NOT_A_UNIT_LINKED_PRODUCT` (the `NotAFuneralProductException` shape).

- [ ] **Step 6: `UnitLinkedTermsIntegrationTest`:**
  - publish a full version through `ProductApi`, then `resolveUnitLinkedPlan` round-trips every field;
  - the HTTP GET returns them;
  - `allocationPercent(1)`, `(3)` and `(40)` read the right bands;
  - `annualRatePerMille(97, "MALE")` reads the open band;
  - BY_SEX with a null sex throws.

  The test registers a fund through `UnitLinkedApi.createFund` first (UnitLinkedTestMigrations + product V25).

- [ ] **Step 7:** Move `ProductApiIntegrationTest`'s overload guard from 11 to 12. Run `./mvnw -o clean test-compile`, then `./mvnw -o -q test -Dtest='UnitLinkedPlanValidatorTest,UnitLinkedTermsIntegrationTest,ProductApiIntegrationTest,ProductContractTest,FuneralProductIntegrationTest,ModularityTests'`. Expected: all pass.

- [ ] **Step 8:** Implement `FundDirectoryImpl` in unitlinked (`@Component` implementing `product.api.FundDirectory`, reading `FundRepository.findByTenantIdAndCode`). Commit: `feat(product): unit-linked terms on the version -- funds from the register, allocation bands, fee, mortality, death and lapse rules`.

---

### Task 3: The sale -- unit-linked choice, issuance, allocation

**Files:**
- Create: `db-migrations/underwriting/V17__unit_linked_choice.sql`, `underwriting/api/UnitLinkedChoice.java`, `underwriting/application/UnitLinkedChoices.java`, the entity, a repository per entity
- Modify: `UnderwritingApi` (+`recordUnitLinkedChoice`, `unitLinkedChoice`), `OpenCaseRequest` (+`unitLinked` block), `UnderwritingController` (GET/PUT `/underwriting/cases/{id}/unit-linked-choice`), `UnderwritingExceptionHandler`
- Modify: `policy/application/UnderwritingDecisionEventListener` (UNIT_LINKED branch), `PolicyController.manualIssue` (refuse UNIT_LINKED)
- Create: `db-migrations/unitlinked/V2__units.sql` (only its `policy_allocation` table is used in this task; the full file is written here and used by Tasks 4–7)
- Create: `unitlinked/domain/PolicyAllocation.java`, its repository, `unitlinked/application/PolicyEventListener.java` (PolicyIssued only, for now), `UnitLinkedEnvelopeRunner.java`
- Test: `unitlinked/UnitLinkedSaleIntegrationTest.java`, `UnderwritingContractTest` additions

**Interfaces:**
- Produces:
  - `UnitLinkedChoice(List<Split> split, BigDecimal premium, String frequency, BigDecimal sumAssured)` with `Split(String fundCode, int percent)`;
  - `UnderwritingApi.recordUnitLinkedChoice(UUID caseId, UnitLinkedChoice, String by) → UnitLinkedChoice`;
  - `Optional<UnitLinkedChoice> unitLinkedChoice(UUID caseId)`;
  - `UnitLinkedApi.allocationOf(String policyNumber) → List<Split>`.

- [ ] **Step 1: Underwriting migration.**

```sql
-- db-migrations/underwriting/V17__unit_linked_choice.sql
CREATE TABLE underwriting.unit_linked_choice (
    case_id           UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL,
    premium_amount    NUMERIC(19,2) NOT NULL CHECK (premium_amount > 0),
    premium_frequency VARCHAR(12) NOT NULL,
    sum_assured       NUMERIC(19,2) NOT NULL CHECK (sum_assured > 0),
    recorded_by       VARCHAR(100) NOT NULL,
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE underwriting.unit_linked_choice_split (
    case_id   UUID NOT NULL REFERENCES underwriting.unit_linked_choice(case_id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL,
    fund_code VARCHAR(20) NOT NULL,
    percent   INTEGER NOT NULL CHECK (percent BETWEEN 1 AND 100),
    PRIMARY KEY (case_id, fund_code)
);
-- RLS + grants (SELECT, INSERT, UPDATE, DELETE on the split for re-recording), V16's shape.
```

- [ ] **Step 2: `UnitLinkedChoices.record(caseId, choice, by)`.** Refuses each of the following with `ClaimValidationException`-style 422s (use underwriting's existing validation exception type, as `FuneralApplications` does):
  - a case whose version is not UNIT_LINKED;
  - an empty split; a fund not in `plan.fundCodes()`; a duplicate fund;
  - percents not totalling 100: "The fund split totals 90%; it must total 100%";
  - a frequency with no minimum: "This product does not take MONTHLY premiums";
  - a premium below the minimum: "The MONTHLY premium is at least 50,000.00 TZS";
  - a sum assured outside `[min, max] × annualPremium`, where `annualPremium = premium × periodsPerYear(frequency)` (SINGLE: the premium itself): "The sum assured must be between 3,000,000.00 and 12,000,000.00 TZS (5× to 20× the annual premium of 600,000.00)".

  Re-recording replaces the splits. It also sets the case's `sumAssuredAmount` to the choice's sum assured (via the case entity's existing setter path used by the funeral application, R3 of funeral) so medical underwriting sees the real cover.

- [ ] **Step 3: Issuance branch** in `UnderwritingDecisionEventListener`, placed directly after the FUNERAL branch:

```java
// UNIT-LINKED (product step 6): the premium is the customer's own choice and the sum assured is
// theirs too, inside the version's multiples -- never the per-mille formula below. The cost of
// insurance is taken monthly from units, so nothing here prices the life (plan R3).
if (category == ProductCategory.UNIT_LINKED) {
    tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice choice = underwritingApi.unitLinkedChoice(caseId)
        .orElseThrow(() -> new IllegalStateException("Unit-linked case " + caseId + " was accepted with no fund choice"));
    policyApi.issuePolicy(caseId, new PolicyApi.IssueRequest(
        decidedCase.applicantPartyId(), decidedCase.productId(), decidedCase.productVersionId(),
        choice.sumAssured(), decidedCase.sumAssuredCurrency(),
        choice.premium(), decidedCase.sumAssuredCurrency(), choice.frequency(),
        decidedCase.agentOfRecordId(), nominationsAsBeneficiaries(decidedCase),
        "Automatic issuance on underwriting decision " + outcome,
        decidedCase.proposedCommencementDate(), decidedCase.policyTermMonths(), null, decidedCase.lifeAssuredPartyId(),
        null /* issuanceBasis: an offer, PROPOSED until the first premium (C2) */),
        "system:underwriting-decision-listener");
    return;
}
```

Before writing, check the 13th and 14th IssueRequest parameters (term months, paying months) against `PolicyApi.IssueRequest`, and pass the case's term in the right slot. A null term means whole of life. Also refuse a LOADED decision on UNIT_LINKED, as funeral does (R2 of funeral): a loading has nothing to load, since the premium is chosen. Put the refusal in `UnitLinkedChoices.checkDecision`, called from the decide path the way `FuneralApplications.checkDecision` is.

- [ ] **Step 4: `V2__units.sql`** (written in full now; later tasks use the remaining tables).

```sql
-- db-migrations/unitlinked/V2__units.sql
-- Product step 6 (U1): each policy's fund split, the orders waiting for a forward price, the
-- append-only unit ledger, the policies frozen against charges, and price-correction adjustments.
CREATE TABLE unitlinked.policy_allocation (
    policy_number VARCHAR(30) NOT NULL,
    tenant_id     UUID NOT NULL,
    fund_id       UUID NOT NULL REFERENCES unitlinked.fund(fund_id),
    percent       INTEGER NOT NULL CHECK (percent BETWEEN 1 AND 100),
    PRIMARY KEY (policy_number, fund_id)
);

CREATE TABLE unitlinked.pending_order (
    order_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL,
    policy_number      VARCHAR(30) NOT NULL,
    fund_id            UUID NOT NULL REFERENCES unitlinked.fund(fund_id),
    side               VARCHAR(4) NOT NULL CHECK (side IN ('BUY','SELL')),
    amount             NUMERIC(19,2) CHECK (amount IS NULL OR amount > 0),
    sell_all           BOOLEAN NOT NULL DEFAULT false,
    purpose            VARCHAR(25) NOT NULL CHECK (purpose IN ('ALLOCATION','CHARGES','DEATH','SURRENDER','MATURITY',
                           'LAPSE','FREE_LOOK','REINVESTMENT')),
    -- A CHARGES order says which charge it sells for; nothing else carries one.
    entry_type         VARCHAR(20) CHECK (entry_type IN ('POLICY_FEE','COST_OF_INSURANCE')),
    CHECK ((purpose = 'CHARGES') = (entry_type IS NOT NULL)),
    received_at        TIMESTAMPTZ NOT NULL,
    bound_date         DATE NOT NULL,
    source_type        VARCHAR(30) NOT NULL,
    source_ref         VARCHAR(100) NOT NULL,
    status             VARCHAR(10) NOT NULL DEFAULT 'WAITING' CHECK (status IN ('WAITING','PRICED','CANCELLED')),
    priced_by_price_id UUID REFERENCES unitlinked.fund_price(price_id),
    priced_at          TIMESTAMPTZ,
    version            BIGINT NOT NULL DEFAULT 0,
    CHECK ((side = 'BUY' AND amount IS NOT NULL AND NOT sell_all) OR (side = 'SELL' AND (amount IS NOT NULL) <> sell_all)),
    UNIQUE (tenant_id, source_type, source_ref, fund_id)
);
CREATE INDEX idx_pending_waiting ON unitlinked.pending_order (tenant_id, fund_id, bound_date) WHERE status = 'WAITING';
CREATE INDEX idx_pending_policy ON unitlinked.pending_order (tenant_id, policy_number, status);

CREATE TABLE unitlinked.unit_entry (
    entry_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL,
    policy_number    VARCHAR(30) NOT NULL,
    fund_id          UUID REFERENCES unitlinked.fund(fund_id),
    entry_type       VARCHAR(20) NOT NULL CHECK (entry_type IN ('ALLOCATION','ALLOCATION_CHARGE','POLICY_FEE',
                         'COST_OF_INSURANCE','DEATH_SALE','SURRENDER_SALE','MATURITY_SALE','LAPSE_SALE','FREE_LOOK_SALE',
                         'CHARGE_REFUND','REINVESTMENT','PRICE_CORRECTION','WRITE_OFF')),
    units            NUMERIC(19,6) NOT NULL DEFAULT 0,
    price            NUMERIC(19,6),
    price_id         UUID REFERENCES unitlinked.fund_price(price_id),
    amount           NUMERIC(19,2) NOT NULL,
    valuation_date   DATE NOT NULL,
    bound_date       DATE,
    order_id         UUID REFERENCES unitlinked.pending_order(order_id),
    source_type      VARCHAR(30) NOT NULL,
    source_ref       VARCHAR(100) NOT NULL,
    reverses_entry_id UUID REFERENCES unitlinked.unit_entry(entry_id),
    created_by       VARCHAR(100) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Money-only entries carry no fund, units or price; unit entries carry all three.
    CHECK ((entry_type IN ('ALLOCATION_CHARGE','CHARGE_REFUND','WRITE_OFF')) = (fund_id IS NULL)),
    CHECK (fund_id IS NULL OR (price IS NOT NULL AND price_id IS NOT NULL)),
    CHECK (fund_id IS NOT NULL OR units = 0)
);
CREATE UNIQUE INDEX ux_unit_entry_source ON unitlinked.unit_entry
    (tenant_id, source_type, source_ref, entry_type, COALESCE(fund_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX idx_unit_entry_policy ON unitlinked.unit_entry (tenant_id, policy_number, fund_id);
CREATE INDEX idx_unit_entry_price ON unitlinked.unit_entry (price_id);

CREATE OR REPLACE FUNCTION unitlinked.refuse_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'unitlinked.% is append-only: correct it with a reversing entry, never an %', TG_TABLE_NAME, TG_OP;
END $$;
CREATE TRIGGER unit_entry_append_only BEFORE UPDATE OR DELETE ON unitlinked.unit_entry
    FOR EACH ROW EXECUTE FUNCTION unitlinked.refuse_mutation();

-- A holding can never go below zero: every SELL entry must leave the policy's units in that fund >= 0.
CREATE OR REPLACE FUNCTION unitlinked.holding_never_negative() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE held NUMERIC(19,6);
BEGIN
    IF NEW.fund_id IS NULL OR NEW.units >= 0 THEN RETURN NEW; END IF;
    SELECT COALESCE(SUM(units), 0) INTO held FROM unitlinked.unit_entry
     WHERE tenant_id = NEW.tenant_id AND policy_number = NEW.policy_number AND fund_id = NEW.fund_id;
    IF held + NEW.units < 0 THEN
        RAISE EXCEPTION 'policy % would hold % units of fund % (holding %, entry %)',
            NEW.policy_number, held + NEW.units, NEW.fund_id, held, NEW.units;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER unit_entry_holding_never_negative BEFORE INSERT ON unitlinked.unit_entry
    FOR EACH ROW EXECUTE FUNCTION unitlinked.holding_never_negative();

CREATE TABLE unitlinked.frozen_policy (
    policy_number VARCHAR(30) PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    reason        VARCHAR(15) NOT NULL CHECK (reason IN ('DEATH','SURRENDER','MATURITY','FREE_LOOK','LAPSE','EXHAUSTED')),
    source_ref    VARCHAR(100) NOT NULL,
    frozen_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE unitlinked.price_correction_adjustment (
    adjustment_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    policy_number   VARCHAR(30) NOT NULL,
    corrected_price_id UUID NOT NULL REFERENCES unitlinked.fund_price(price_id),
    amount          NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    direction       VARCHAR(17) NOT NULL CHECK (direction IN ('OWED_TO_CUSTOMER','OWED_BY_CUSTOMER')),
    status          VARCHAR(8) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','SETTLED','WAIVED')),
    proposed_by     VARCHAR(100),
    decided_by      VARCHAR(100),
    decided_at      TIMESTAMPTZ,
    version         BIGINT NOT NULL DEFAULT 0,
    CHECK (decided_by IS NULL OR proposed_by IS NULL OR decided_by <> proposed_by),
    UNIQUE (corrected_price_id, policy_number)
);
-- Each exit (death, surrender, maturity, lapse, free-look) and its totals: "are all its orders priced?"
CREATE TABLE unitlinked.exit_state (
    tenant_id      UUID NOT NULL,
    source_type    VARCHAR(30) NOT NULL,
    source_ref     VARCHAR(100) NOT NULL,
    policy_number  VARCHAR(30) NOT NULL,
    purpose        VARCHAR(12) NOT NULL CHECK (purpose IN ('DEATH','SURRENDER','MATURITY','LAPSE','FREE_LOOK')),
    returned_money NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (returned_money >= 0),
    proceeds       NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (proceeds >= 0),
    status         VARCHAR(14) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','PRICED','AWAITING_PAYEE','PAID','REVERSED')),
    payee_ref      VARCHAR(100),
    completed_at   TIMESTAMPTZ,
    version        BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, source_type, source_ref)
);

-- Notices sent at most once per month per policy (low fund).
CREATE TABLE unitlinked.notice_log (
    tenant_id     UUID NOT NULL,
    policy_number VARCHAR(30) NOT NULL,
    kind          VARCHAR(20) NOT NULL,
    month         CHAR(7) NOT NULL,
    PRIMARY KEY (tenant_id, policy_number, kind, month)
);
-- RLS (NULLIF predicate) + grants for all seven tables: SELECT, INSERT everywhere; UPDATE on pending_order,
-- price_correction_adjustment, policy_allocation and exit_state; UPDATE and DELETE on frozen_policy. Write each block out.
```

- [ ] **Step 5: `PolicyEventListener`** (`@Component("unitLinkedPolicyEventListener")`, AFTER_COMMIT, through `UnitLinkedEnvelopeRunner`, a copy of accumulation's `EnvelopeRunner` with its own bean name and `ux_unit_entry_source` as the dropped-duplicate index). On `policy.PolicyIssued`, when the policy's category is UNIT_LINKED:

```java
var choice = underwritingApi.unitLinkedChoice(caseId).orElseThrow(() -> new IllegalStateException(
    "Unit-linked policy " + policyNumber + " has no fund choice on case " + caseId));
if (!allocations.findByTenantIdAndPolicyNumber(tenantId, policyNumber).isEmpty()) return; // redelivered
for (var s : choice.split()) {
    Fund fund = funds.findByTenantIdAndCode(tenantId, s.fundCode()).orElseThrow(() -> new FundNotFoundException(s.fundCode()));
    allocations.save(new PolicyAllocation(tenantId, policyNumber, fund.getFundId(), s.percent()));
}
```

Read the case id from `policyApi.getPolicy(policyNumber).underwritingCaseId()` (C3); the PolicyIssued payload is unchanged.

- [ ] **Step 6: Tests.** `UnitLinkedSaleIntegrationTest`:
  - `aCaseRecordsTheSplitAndRefusesOneNotTotalling100`;
  - `aPremiumBelowTheMinimumIsRefused`;
  - `aSumAssuredOutsideTheMultiplesIsRefusedWithTheRange`;
  - `acceptingTheCaseIssuesAtTheChosenPremiumAndSumAssured`: the policy premium = the choice, not quoted;
  - `issuanceWritesTheAllocation`;
  - `aLoadedDecisionIsRefused`;
  - `manualIssueRefusesUnitLinked`.

  Fixtures in `UnitLinkedTestFixtures`:
  - `fund(code, cutOff)`;
  - `approvedPrice(fundCode, date, price)` (proposes as `staff.finance` and approves as `staff.admin`, with the fund's cut-off at `00:00:01` so yesterday's and today's dates are approvable);
  - `publishUl(...)` (two funds EQ1/BD1, bands y1–2 90% / y3+ 98%, fee 2,000, UNISEX mortality 0–39 1.2‰, 40–59 4.5‰, 60+ 25‰, HIGHER_OF, EXHAUSTION, min surrender 0, low-fund 3, MONTHLY ≥ 50,000, multiples 5–20, free-look 14);
  - `sell(partyId, split, premium, sa)` (opens the case, records the choice, decides ACCEPT as two people, and returns the policy number).

  Add the migrations (product V25, underwriting V17, unitlinked V2) to `UnitLinkedTestMigrations.ALL`.

- [ ] **Step 7:** Run `./mvnw -o clean test-compile && ./mvnw -o -q test -Dtest='UnitLinkedSaleIntegrationTest,UnderwritingContractTest,FuneralIssueIntegrationTest,ModularityTests'`. Expected: all pass.

- [ ] **Step 8:** Commit: `feat(unitlinked): sell a unit-linked policy -- the customer's split, premium and cover, issued verbatim`.

---

### Task 4: Pending orders, the pricing run, the unit ledger, premium allocation, revaluation

**Files:**
- Create: `unitlinked/domain/{UnitArithmetic,PendingOrder,UnitEntry,FrozenPolicy}.java`, the repositories
- Create: `unitlinked/application/{UnitLedger,PricingRun,Allocations,BillingEventListener}.java`, `api/{HoldingView,PendingOrderView,UnitEntryView}.java`, `infrastructure/UnitsController.java`
- Test: `UnitArithmeticTest.java`, `PricingRunIntegrationTest.java`, `ForwardPricingIntegrationTest.java`

**Interfaces:**
- Consumes: `BindingRule`, `FundPriceRepository`, `PolicyAllocation`, `ProductApi.resolveUnitLinkedPlan`, `PolicyApi.getPolicy`.
- Produces:
  - `UnitArithmetic.allocate(BigDecimal premium, BigDecimal percent) → Allocated(BigDecimal allocated, BigDecimal charge)`;
  - `UnitArithmetic.split(BigDecimal amount, List<Weighted> weights) → List<BigDecimal>`, where `Weighted(String key, BigDecimal weight)`;
  - `UnitArithmetic.unitsBought(BigDecimal money, BigDecimal price)`, `unitsToSell(BigDecimal money, BigDecimal price, BigDecimal held)`, `proceeds(BigDecimal units, BigDecimal price)`;
  - `UnitLedger.place(PendingOrder)` (idempotent), `UnitLedger.holdings(String policyNumber) → Map<UUID,BigDecimal>`, `UnitLedger.isFrozen(String)`, `freeze(String, reason, ref)`, `unfreeze(String)`;
  - `PricingRun.onApproved(FundPrice)`, which revalues and then prices the WAITING orders;
  - events `unitlinked.UnitsAllocated {policyNumber, sourceRef, premium, allocated, allocationCharge, currency, entries[...]}`, `unitlinked.UnitsSold {policyNumber, purpose, sourceRef, proceeds, currency}`, `unitlinked.FundRevalued {priceId, fundId, valuationDate, unitsInIssue, previousPrice, price, delta, currency}`;
  - `UnitLinkedApi.holdings(policyNumber) → List<HoldingView>`, `pendingOrders(policyNumber)`, `entries(policyNumber)`;
  - HTTP `GET /policies/{n}/units` returning `{holdings, pending, entries, totalValue, valuedAt}`.

- [ ] **Step 1: Failing `UnitArithmeticTest`.**

```java
@Test void allocationRoundsOnceHalfEvenAndTheChargeIsTheRest() {
    var a = UnitArithmetic.allocate(new BigDecimal("100000.00"), new BigDecimal("90"));
    assertThat(a.allocated()).isEqualByComparingTo("90000.00");
    assertThat(a.charge()).isEqualByComparingTo("10000.00");
    var b = UnitArithmetic.allocate(new BigDecimal("33333.33"), new BigDecimal("97.5"));
    assertThat(b.allocated().add(b.charge())).isEqualByComparingTo("33333.33");
    assertThat(b.allocated()).isEqualByComparingTo("32499.99"); // 32499.996750 -> HALF_EVEN 2dp
}
@Test void theSplitAlwaysSumsExactlyAndTheRemainderGoesToTheLargest() {
    var parts = UnitArithmetic.split(new BigDecimal("100.00"),
        List.of(new Weighted("BD1", new BigDecimal("33")), new Weighted("EQ1", new BigDecimal("67"))));
    assertThat(parts.get(0).add(parts.get(1))).isEqualByComparingTo("100.00");
    var thirds = UnitArithmetic.split(new BigDecimal("100.00"), List.of(
        new Weighted("A", BigDecimal.ONE), new Weighted("B", BigDecimal.ONE), new Weighted("C", BigDecimal.ONE)));
    assertThat(thirds).containsExactly(new BigDecimal("33.34"), new BigDecimal("33.33"), new BigDecimal("33.33")); // tie -> first by key
}
@Test void boughtUnitsTruncateSoTheCustomerNeverGetsMoreThanTheMoneyBuys() {
    assertThat(UnitArithmetic.unitsBought(new BigDecimal("100.00"), new BigDecimal("3.000000"))).isEqualByComparingTo("33.333333");
}
@Test void soldUnitsCeilToCoverTheMoneyButNeverExceedTheHolding() {
    assertThat(UnitArithmetic.unitsToSell(new BigDecimal("100.00"), new BigDecimal("3.000000"), new BigDecimal("1000"))).isEqualByComparingTo("33.333334");
    assertThat(UnitArithmetic.unitsToSell(new BigDecimal("100.00"), new BigDecimal("3.000000"), new BigDecimal("10"))).isEqualByComparingTo("10");
}
@Test void proceedsRoundHalfEven() {
    assertThat(UnitArithmetic.proceeds(new BigDecimal("33.333333"), new BigDecimal("3.000000"))).isEqualByComparingTo("100.00");
}
@Test void aZeroOrNegativePriceIsRefusedNeverDividedBy() {
    assertThatThrownBy(() -> UnitArithmetic.unitsBought(BigDecimal.TEN, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: Implement `UnitArithmetic`.**

```java
public final class UnitArithmetic {
    public record Allocated(BigDecimal allocated, BigDecimal charge) {}
    public record Weighted(String key, BigDecimal weight) {}
    private UnitArithmetic() {}

    public static Allocated allocate(BigDecimal premium, BigDecimal percent) {
        BigDecimal allocated = premium.multiply(percent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN);
        return new Allocated(allocated, premium.subtract(allocated));
    }

    /** Parts in the order given; the remainder on the largest weight, the first by key on a tie. */
    public static List<BigDecimal> split(BigDecimal amount, List<Weighted> weights) {
        BigDecimal total = weights.stream().map(Weighted::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) throw new IllegalArgumentException("A split needs positive weights");
        List<BigDecimal> parts = new ArrayList<>();
        for (Weighted w : weights) parts.add(amount.multiply(w.weight()).divide(total, 2, RoundingMode.HALF_EVEN));
        BigDecimal remainder = amount.subtract(parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        int largest = 0;
        for (int i = 1; i < weights.size(); i++) {
            int c = weights.get(i).weight().compareTo(weights.get(largest).weight());
            if (c > 0 || (c == 0 && weights.get(i).key().compareTo(weights.get(largest).key()) < 0)) largest = i;
        }
        parts.set(largest, parts.get(largest).add(remainder));
        return parts;
    }

    public static BigDecimal unitsBought(BigDecimal money, BigDecimal price) {
        requirePrice(price);
        return money.divide(price, 6, RoundingMode.DOWN);
    }

    public static BigDecimal unitsToSell(BigDecimal money, BigDecimal price, BigDecimal held) {
        requirePrice(price);
        return money.divide(price, 6, RoundingMode.CEILING).min(held);
    }

    public static BigDecimal proceeds(BigDecimal units, BigDecimal price) {
        requirePrice(price);
        return units.multiply(price).setScale(2, RoundingMode.HALF_EVEN);
    }

    private static void requirePrice(BigDecimal price) {
        if (price == null || price.signum() <= 0) throw new IllegalArgumentException("A unit price must be greater than zero, never defaulted");
    }
}
```

Note the thirds case: 33.33 × 3 = 99.99, remainder 0.01, all weights tie, so the first by key ("A") gets it. Run `UnitArithmeticTest`; expect it to pass.

- [ ] **Step 3: Entities.**
  - `PendingOrder`: static factories `buy(tenant, policy, fundId, amount, purpose, receivedAt, boundDate, sourceType, sourceRef)`, `sell(...)` and `sellAll(...)`. Methods: `markPriced(UUID priceId, Instant at)` (WAITING only) and `cancel()` (WAITING only).
  - `UnitEntry`: immutable, constructor only, no setters. Static factories per type, used by `UnitLedger`.
  - `FrozenPolicy`.

  Repositories:
  - `PendingOrderRepository.findWaiting(UUID tenantId, UUID fundId, LocalDate upTo)` (status WAITING, `boundDate <= :upTo`, order by receivedAt, orderId; `@Lock(PESSIMISTIC_WRITE)`);
  - `findByTenantIdAndPolicyNumberAndStatus(...)`;
  - `UnitEntryRepository.sumUnits(UUID tenantId, String policyNumber, UUID fundId)`;
  - `unitsInIssue(UUID tenantId, UUID fundId)`;
  - `findByTenantIdAndPolicyNumberOrderByCreatedAt(...)`;
  - `findByPriceId(UUID)`;
  - `existsBySource(...)`.

- [ ] **Step 4: `PricingRun.onApproved(FundPrice price)`**, called by `FundRegister.approvePrice` in the same transaction, **before** publishing PriceApproved:

```java
@Transactional
public void onApproved(FundPrice price) {
    UUID tenantId = TenantContext.get();
    Fund fund = funds.findById(price.getFundId()).orElseThrow();
    // 1. Revalue the units already in issue BEFORE this price prices anything (spec §8): units this
    //    price buys or sells carry no movement of their own.
    BigDecimal inIssue = entries.unitsInIssue(tenantId, fund.getFundId());
    prices.findLatestApprovedBefore(tenantId, fund.getFundId(), price.getValuationDate()).ifPresent(prev -> {
        BigDecimal delta = inIssue.multiply(price.getPrice().subtract(prev.getPrice())).setScale(2, RoundingMode.HALF_EVEN);
        if (delta.signum() != 0) {
            events.publishEvent(DomainEventEnvelope.of("unitlinked.FundRevalued", tenantId, Map.of(
                "priceId", price.getPriceId().toString(), "fundId", fund.getFundId().toString(),
                "fundCode", fund.getCode(), "valuationDate", price.getValuationDate().toString(),
                "unitsInIssue", inIssue.toPlainString(), "previousPrice", prev.getPrice().toPlainString(),
                "price", price.getPrice().toPlainString(),
                "delta", Map.of("amount", delta.toPlainString(), "currencyCode", fund.getCurrency()))));
        }
    });
    // 2. Price every order waiting on a date up to this one. An order bound to an unpriced earlier
    //    date (a holiday) is swept here -- never to an earlier price, never to zero.
    for (PendingOrder order : orders.findWaiting(tenantId, fund.getFundId(), price.getValuationDate())) {
        ledger.execute(order, price);
    }
}
```

**Revaluation baseline:** the first price a fund ever has has no previous price, so nothing revalues. Units are bought at that price, so the liability already equals units × price.

**Waiting-order guard:** a swept order bound to D < P is priced only if D's own price can no longer arrive with an earlier value. That holds because the order is priced by the first price dated ≥ D, and P is ≥ D. If D's price is approved later than P's (an out-of-order entry), the order is already priced at P. So `approvePrice` **refuses to approve a price for date D while any approved price for the same fund is dated after D**, unless it is a correction: "A price for <D> cannot be approved after <P>'s; orders waiting on <D> were already priced at <P>". Add this check and a test.

- [ ] **Step 5: `UnitLedger.execute(order, price)`.** BUY → `ALLOCATION` (or `REINVESTMENT` for purpose REINVESTMENT) with `units = unitsBought(amount, price)`. SELL with amount → the entry type by purpose:
  - CHARGES: the caller sets the type through a sub-split, so a CHARGES sell order carries **two** amounts. Model this instead as two orders, `source_ref` = `charge:<policy>:<date>:fee` and `...:coi`, entry types POLICY_FEE and COST_OF_INSURANCE;
  - DEATH → DEATH_SALE, SURRENDER → SURRENDER_SALE, MATURITY → MATURITY_SALE, LAPSE → LAPSE_SALE, FREE_LOOK → FREE_LOOK_SALE.

  SELL ALL sells the full holding at `proceeds`.

  Entries store `units` negative for a sale, `amount` = money (positive on a buy, negative on a sale), `price`, `price_id`, `valuation_date = price date`, `bound_date = order.boundDate`, `order_id`, and `source_*` from the order. After writing, `order.markPriced`. Then call `ExitsCallback.afterPriced(order)`, an interface implemented in Task 7 that is a no-op until then, so exits can react when the last of their orders is priced.

  Charges that can't be covered (the holding is worth less than the amount) are handled in Task 6. Here, `unitsToSell` caps at the holding and the shortfall is returned to the caller.

- [ ] **Step 6: `Allocations` + `BillingEventListener`** (`@Component("unitLinkedBillingEventListener")`). On `billing.PremiumCollected`:
  1. Skip unless `allocations.existsByTenantIdAndPolicyNumber` (a unit-linked policy). This is also the "purpose" filter: only premiums of a policy with an allocation arrive here, and billing's PremiumCollected is premium-purpose by construction. Confirm against the step 3 note ("payment purpose must be filtered by any new collection consumer"): if billing publishes PremiumCollected for top-ups too, filter `purpose` = PREMIUM when present.
  2. If frozen (death, surrender, free-look or exhausted), refuse: log an ERROR and publish `unitlinked.PremiumNotInvested {policyNumber, invoiceId, reason}` for staff. Never invest into a frozen policy.
  3. `policyYear` = whole years from commencement to the collection's civil date, plus 1.
  4. `allocate(P, plan.allocationPercent(y))`, then write the money-only `ALLOCATION_CHARGE` entry at once (source `premium:<invoiceId>`), dated the collection's civil date.
  5. `split(allocated, the policy's %)` → one BUY order per fund, `receivedAt = collectedAt`, `boundDate = BindingRule.boundDate(collectedAt, fund.cutOff)`, `source_ref = premium:<invoiceId>`.
  6. Publish `unitlinked.UnitsAllocated` when the **last** order of that premium is priced (via the callback), so finaccounting posts one balanced entry per premium: DR 2140 P / CR 2150 Σ allocations at their money amounts / CR 4310 the charge. Units bought for `amount` cost exactly `amount` in money terms, so the posting uses the order amounts, not proceeds.

- [ ] **Step 7: Integration tests.** `ForwardPricingIntegrationTest`, the invariant suite:
  - `aPremiumWaitsForTheNextPriceAndNeverUsesTodaysKnownPrice`: approve EQ1 for yesterday at 1.000000; collect a premium now (after today's cut-off of 00:00:01, so it binds to tomorrow); assert the order is WAITING bound to tomorrow, and that no entry exists even though yesterday's price is known. Then propose and approve tomorrow's price by running the approval with a `Clock` set to tomorrow 00:00:02 EAT (inject `java.time.Clock` into `FundRegister`; the default bean is `Clock.system(CIVIL_ZONE)` and tests override it with `@MockBean Clock` or a `@TestConfiguration`); assert the order is PRICED at tomorrow's price.
  - `aMissingDayIsSweptToTheNextApprovedPriceNeverAnEarlierOne`: bound to D with no price for D; approve D + 1 → priced at D + 1, and the entry's `bound_date = D`, `valuation_date = D + 1`.
  - `aPriceForAnEarlierDateCannotBeApprovedAfterALaterOne`.
  - `aRedeliveredPremiumBuysNothingTwice`.
  - `aPremiumOnAFrozenPolicyIsNotInvested`.
  - `theAllocationChargeAndUnitsSumToThePremium`.

  `PricingRunIntegrationTest`:
  - `aNewPriceRevaluesOnlyTheUnitsAlreadyInIssue`: two policies hold 100 and 50 units at 1.0, a third's order waits; approve 1.2, so FundRevalued delta = 30.00 (150 × 0.2), and the third's units are bought at 1.2 with no revaluation;
  - `holdingsNeverGoNegativeEvenByHand`: a native insert of a sale bigger than the holding → the trigger message.

- [ ] **Step 8:** Run `./mvnw -o clean test-compile && ./mvnw -o -q test -Dtest='UnitArithmeticTest,ForwardPricingIntegrationTest,PricingRunIntegrationTest,FundRegisterIntegrationTest,ModularityTests'`. Expected: all pass.

- [ ] **Step 9:** Commit: `feat(unitlinked): premiums buy units at the next forward price; the append-only unit ledger; revaluation per fund`.

---

### Task 5: Price corrections and adjustments

**Files:**
- Create: `unitlinked/application/Corrections.java`, `domain/PriceCorrectionAdjustment.java`, its repository, `api/AdjustmentView.java`
- Modify: `FundRegister` (`proposeCorrection`; `approvePrice` routes a correction to `Corrections`), `FundPriceController` (`POST /fund-prices/{id}/correction`, `GET /price-adjustments?status=OPEN`, `POST /price-adjustments/{id}/settlement`, `POST /price-adjustments/{id}/waiver`)
- Test: `PriceCorrectionIntegrationTest.java`

**Interfaces:**
- Produces:
  - `UnitLinkedApi.proposeCorrection(UUID approvedPriceId, BigDecimal price, String reason, String by) → FundPriceView`;
  - `listAdjustments(String status)`;
  - `settleAdjustment(UUID id, String payeeRef, String by)`, `waiveAdjustment(UUID id, String reason, String by)`;
  - event `unitlinked.PriceCorrected {oldPriceId, newPriceId, fundId, valuationDate, oldPrice, newPrice}`;
  - every reversal and re-entry publishes the same events as the original movement, with `sourceRef` suffixed `#corr:<newPriceId>` so finaccounting posts the reversal and the redo as their own references.

- [ ] **Step 1: Failing tests.**
  - `aCorrectionReversesAndReEntersEveryMovementAtTheNewPrice`: a premium of 90,000 bought at a wrong 1.500000 gives 60,000 units. Correct to 1.000000. Then: the original ALLOCATION has a PRICE_CORRECTION reversal of −60,000 units; a new ALLOCATION of 90,000 units exists; the holding = 90,000; the old price is SUPERSEDED; the new one is APPROVED.
  - `aCorrectionRevaluesFromTheCorrectedPrice`: FundRevalued is reversed and re-posted, and the 2150 = units × price check (Task 8 helper; until then, assert `Σ amounts`) holds.
  - `theCorrectorCannotApproveTheirOwnCorrection`.
  - `aCorrectedSaleWhosePayoutWasPaidBecomesAnAdjustmentNotAClawback`: a surrender paid at a wrong high price → correct downward → an OPEN adjustment OWED_BY_CUSTOMER for the difference; no entry changes the paid amount.
  - `waivingAnAdjustmentNeedsASecondPerson`.

  Write the surrender-paid case after Task 7 exists. Until then, mark it `@Disabled("needs Task 7 exits")`, and remove the `@Disabled` in Task 7 Step 9.

- [ ] **Step 2: `Corrections.apply(FundPrice oldPrice, FundPrice newPrice)`**, called from `approvePrice` when `supersedesPriceId != null`:

```java
@Transactional
void apply(FundPrice oldPrice, FundPrice newPrice) {
    UUID tenantId = TenantContext.get();
    oldPrice.supersede();
    prices.saveAndFlush(oldPrice);        // the partial unique index frees the date before the new one is APPROVED
    revaluation.reverse(oldPrice);        // publishes FundRevalued with the negated delta, ref "#corr:<newPriceId>"
    revaluation.revalue(newPrice);        // the same computation PricingRun.onApproved uses
    for (UnitEntry e : entries.findByPriceId(oldPrice.getPriceId())) {
        if (e.getReversesEntryId() != null) continue;                    // a reversal is never itself re-run
        ledger.reverse(e, newPrice.getPriceId());                        // PRICE_CORRECTION, units and amount negated
        UnitEntry redo = ledger.reEnter(e, newPrice);                   // same type, amount, source + "#corr:<id>"
        exits.paidDifference(e, redo).ifPresent(diff -> adjustments.save(PriceCorrectionAdjustment.of(
            tenantId, e.getPolicyNumber(), newPrice.getPriceId(), diff)));
    }
    events.publishEvent(DomainEventEnvelope.of("unitlinked.PriceCorrected", tenantId, Map.of(/* the keys above */)));
}
```

**Re-entry rules:**
- A BUY of money M re-buys `unitsBought(M, new)`.
- A SELL of money M re-sells `unitsToSell(M, new, heldNow)`.
- A SELL ALL re-sells the units the original sold, and the proceeds change.

`exits.paidDifference` returns the proceeds difference when the entry's exit has already been paid (a payout request marked PAID), and empty otherwise. For an exit priced but not yet paid, Task 7's exit simply reads the corrected proceeds when it requests the payout, so no adjustment is needed. Extract `PricingRun`'s revaluation into a `Revaluation` component with `revalue(FundPrice)` and `reverse(FundPrice)`; `PricingRun` calls `revaluation.revalue(price)`.

- [ ] **Step 3: Adjustments.**
  - `settleAdjustment` (OWED_TO_CUSTOMER): publish `unitlinked.PayoutRequested` (purpose `PRICE_CORRECTION_PAYOUT`, a new payment purpose in Task 7's payment migration, sourceRef = adjustment id), then status SETTLED once paid.
  - OWED_BY_CUSTOMER: settle records that staff collected it outside the platform, with a reference.
  - `waiveAdjustment`: needs a reason and `decidedBy ≠ proposedBy`. The proposer is whoever approved the correction.

- [ ] **Step 4:** Run `./mvnw -o -q test -Dtest='PriceCorrectionIntegrationTest,PricingRunIntegrationTest,ForwardPricingIntegrationTest'`. Expected: pass, with one test disabled until Task 7.

- [ ] **Step 5:** Commit: `feat(unitlinked): two-person price corrections re-run every movement; paid differences become adjustments`.

---

### Task 6: The monthly charge run, exhaustion, lapse rules, low-fund notice

**Files:**
- Create: `unitlinked/domain/CostOfInsurance.java`, `application/{ChargeRun,ChargeSweep}.java`
- Modify: `policy/application/PolicyLapseRecommendedEventListener.java` (R8), `policy/application/PolicyApiImpl` (paid-up and loan refusals, R9; `lapseExhaustedAccount` reused for unit-linked exhaustion)
- Create: `db-migrations/communication/V13__unit_linked_templates.sql`, `communication/application/UnitLinkedEventListener.java`
- Modify: `application-local.yml` (`unitlinked.charge-sweep-interval-ms: 60000`)
- Test: `CostOfInsuranceTest.java`, `ChargeRunIntegrationTest.java`, `UnitLinkedLapseIntegrationTest.java`, `UnitLinkedNoticeIntegrationTest.java`

**Interfaces:**
- Produces:
  - `CostOfInsurance.sumAtRisk(DeathRule, BigDecimal sumAssured, BigDecimal fundValue)`;
  - `CostOfInsurance.monthly(BigDecimal annualRatePerMille, BigDecimal sumAtRisk)`;
  - `ChargeRun.runFor(String policyNumber, LocalDate chargeDate)` (idempotent per policy and date);
  - `ChargeSweep.sweep()`;
  - events `unitlinked.ChargesTaken {policyNumber, chargeDate, policyFee, costOfInsurance, writtenOff, currency}`, `unitlinked.FundExhausted {policyNumber, on}`, `unitlinked.LowFund {policyNumber, fundValue, monthsCovered}`.

- [ ] **Step 1: Failing `CostOfInsuranceTest`.**

```java
@Test void higherOfChargesOnlyTheGap() {
    assertThat(CostOfInsurance.sumAtRisk(DeathRule.HIGHER_OF, new BigDecimal("10000000"), new BigDecimal("2500000"))).isEqualByComparingTo("7500000");
}
@Test void higherOfChargesNothingOnceTheFundExceedsTheCover() {
    assertThat(CostOfInsurance.sumAtRisk(DeathRule.HIGHER_OF, new BigDecimal("10000000"), new BigDecimal("12000000"))).isEqualByComparingTo("0");
}
@Test void sumAssuredPlusFundChargesTheWholeCover() {
    assertThat(CostOfInsurance.sumAtRisk(DeathRule.SUM_ASSURED_PLUS_FUND, new BigDecimal("10000000"), new BigDecimal("2500000"))).isEqualByComparingTo("10000000");
}
@Test void theMonthlyChargeIsTheAnnualRateOverTwelveRoundedOnce() {
    // 4.5 per mille a year on 7,500,000 = 33,750 a year = 2,812.50 a month
    assertThat(CostOfInsurance.monthly(new BigDecimal("4.5"), new BigDecimal("7500000"))).isEqualByComparingTo("2812.50");
}
```

Implement it: `sumAtRisk` = `max(0, SA − FV)` or `SA`. `monthly` = `rate × sar ÷ 1000 ÷ 12`, computed at scale 10 and rounded HALF_EVEN to 2 dp once.

- [ ] **Step 2: `ChargeRun.runFor(policyNumber, chargeDate)`.**

```java
@Transactional
public void runFor(String policyNumber, LocalDate chargeDate) {
    UUID tenantId = TenantContext.get();
    if (ledger.isFrozen(policyNumber)) return;
    String ref = "charge:" + policyNumber + ":" + chargeDate;
    if (orders.existsByTenantIdAndSourceTypeAndSourceRefStartingWith(tenantId, "charge", ref)) return; // ran already
    PolicyView policy = policyApi.getPolicy(policyNumber);
    if (policy.status() != PolicyStatus.ACTIVE && policy.status() != PolicyStatus.REINSTATED) return;
    UnitLinkedPlan plan = productApi.resolveUnitLinkedPlan(policy.productVersionId());
    Map<UUID, BigDecimal> held = ledger.holdings(policyNumber);
    // Sized at the latest price STRICTLY before the charge date -- the insurer's own calculation (spec §6);
    // the units are still sold forward, bound to the charge date.
    BigDecimal fundValue = BigDecimal.ZERO;
    Map<UUID, BigDecimal> values = new LinkedHashMap<>();
    for (var e : held.entrySet()) {
        if (e.getValue().signum() == 0) continue;
        BigDecimal p = prices.findLatestApprovedBefore(tenantId, e.getKey(), chargeDate)
            .map(FundPrice::getPrice).orElseThrow(() -> new IllegalStateException(
                "Fund " + e.getKey() + " holds units but has no approved price before " + chargeDate));
        BigDecimal v = UnitArithmetic.proceeds(e.getValue(), p);
        values.put(e.getKey(), v);
        fundValue = fundValue.add(v);
    }
    PartyDetailView life = partyApi.getPartyDetail(policy.lifeAssuredPartyId() != null ? policy.lifeAssuredPartyId() : policy.policyholderPartyId());
    int age = Period.between(life.dateOfBirth(), chargeDate).getYears();
    String sex = plan.mortalityBasis() == MortalityBasis.BY_SEX ? (life.sex() == null ? null : life.sex().name()) : null;
    if (plan.mortalityBasis() == MortalityBasis.BY_SEX && sex == null) {
        log.error("Unit-linked policy {}: no recorded sex for a BY_SEX mortality table; charge for {} not taken", policyNumber, chargeDate);
        events.publishEvent(DomainEventEnvelope.of("unitlinked.ChargeRefused", tenantId,
            Map.of("policyNumber", policyNumber, "chargeDate", chargeDate.toString(), "reason", "NO_RECORDED_SEX")));
        return;
    }
    BigDecimal coi = CostOfInsurance.monthly(plan.annualRatePerMille(age, sex),
        CostOfInsurance.sumAtRisk(plan.deathRule(), policy.sumAssuredAmount(), fundValue));
    BigDecimal fee = plan.monthlyPolicyFee();
    Instant at = BindingRule.earliestApproval(chargeDate, LocalTime.MIDNIGHT); // bound to chargeDate itself
    placeSplit(policyNumber, values, fee, "POLICY_FEE", ref + ":fee", at, chargeDate);
    placeSplit(policyNumber, values, coi, "COST_OF_INSURANCE", ref + ":coi", at, chargeDate);
}
```

- `placeSplit` splits the money across funds by `values` (UnitArithmetic.split) and places one SELL order per fund, `bound_date = chargeDate` (the run is before every cut-off), purpose CHARGES, with the entry type carried in the order's `entry_type` column (V2, Task 3).
- A zero amount places nothing.
- If the fund value is zero and charges are due, go straight to exhaustion (Step 3).

**Charge dates:** stepped monthly from the issue date with `InstalmentDates` semantics (31 Jan → 28 Feb → 28 Mar). The first charge date is the issue date's first monthly step, so no charge falls on the issue day itself, when no units exist yet. Make `policy.domain.InstalmentDates` usable here: it is in policy's domain, not its api. Copy its four-line stepping into `unitlinked.domain.ChargeDates` with a comment naming the original, rather than widening policy's api. Test it with the same five cases as `InstalmentDatesTest`.

- [ ] **Step 3: Exhaustion,** in `UnitLedger.execute` for a CHARGES sell. If `unitsToSell` was capped (the holding is worth less than the amount):
  1. Sell the whole holding of that fund (entry type as ordered).
  2. Record the uncovered money as a `WRITE_OFF` money-only entry (source = the order's ref + `:writeoff`).
  3. Once **all** of that charge date's orders are priced and the policy holds no units in any fund: freeze it with reason EXHAUSTED; if `plan.lapseRule() == EXHAUSTION` (or inside the minimum premium years), call `policyApi.lapseExhaustedAccount(policyNumber, chargeDate)` (it already publishes PolicyLapsed with a reason; pass reason `FUND_EXHAUSTED` by adding an overload `lapseExhaustedAccount(String, LocalDate, String reason)` that defaults to ACCOUNT_EXHAUSTED); publish `unitlinked.FundExhausted`.

  Under NON_PAYMENT, an exhausted fund with premiums still current cannot happen in practice. If it does, treat it the same way.

- [ ] **Step 4: Lapse rules (R8)** in `PolicyLapseRecommendedEventListener`, directly after the account exemption:

```java
UnitLinkedPlan ul = productApi.resolveUnitLinkedPlan(versionId);
if (ul.unitLinked() && ul.lapseRule() == LapseRule.EXHAUSTION) {
    PolicyView p = policyApi.getPolicy(policyNumber);
    LocalDate start = p.commencementDate() != null ? p.commencementDate() : p.issueDate();
    boolean pastMinimum = ul.minimumPremiumYears() == null
        || !LocalDate.now(CIVIL_ZONE).isBefore(start.plusYears(ul.minimumPremiumYears()));
    if (pastMinimum) {
        log.info("Not lapsing unit-linked policy {} on billing's recommendation: it lapses only when its fund is exhausted", policyNumber);
        return;
    }
}
policyApi.lapsePolicy(policyNumber, "system:billing-lapse-recommendation");
```

A non-payment lapse of a UNIT_LINKED policy publishes PolicyLapsed as today. unitlinked's PolicyEventListener reacts to `policy.PolicyLapsed` with **no** `reason` key (an arrears lapse), on a unit-linked policy that is not frozen, with `Exits.lapse(policyNumber, lapsedAt)` (Task 7): it freezes the policy and SELLs ALL with purpose LAPSE, bound by the cut-off at `lapsedAt`. A `reason = FUND_EXHAUSTED` lapse is its own and is ignored there.

- [ ] **Step 5: R9 guards** in `PolicyApiImpl.makePaidUp` and the policyloan eligibility. Refuse UNIT_LINKED first, with "A unit-linked policy has no paid-up or loan value in U1; its value is its units". Paid-up is in policy. For loans, find the eligibility check in `PolicyLoanApiImpl` (it reads `policy_account` cash value, which is zero for UL) and add the explicit category refusal so the message is readable.

- [ ] **Step 6: `ChargeSweep`.** Copy `CoveredLifeSweep`'s scheduling shape, with property `unitlinked.charge-sweep-interval-ms`, default 3600000, and 60000 in `application-local.yml`. It iterates tenants the way `CoveredLifeSweep` does and, for each policy with an allocation, computes the charge dates due up to today (civil) with no orders yet, then calls `chargeRun.runFor` for each in date order, catching per policy. It must depend on **interfaces or its own module's beans only**. This is the funeral lesson: a sweep that injects a concrete class from another module breaks every context that mocks it.

- [ ] **Step 7: Low-fund notice.** In the callback once a charge date's orders are all priced:
  1. FV now = holdings × the latest approved price on or before the charge date.
  2. Monthly charges = fee + coi.
  3. If `FV < lowFundWarningMonths × charges` and no LowFund was sent this calendar month (`unitlinked.notice_log`, V2), publish `unitlinked.LowFund`.

  `communication/V13__unit_linked_templates.sql` seeds four templates, UNIT_LINKED_ALLOCATED, UNIT_LINKED_LOW_FUND, UNIT_LINKED_LAPSED_EXHAUSTED and UNIT_LINKED_PROCEEDS, each with SMS and EMAIL in sw and en. Copy V12's INSERT shape and placeholders style. Write the actual sentences, for example en SMS LOW_FUND: "Nlolo: your policy {{policyNumber}} fund is {{fundValue}}, enough for about {{monthsCovered}} months of charges. Pay your premium to keep your cover." Swahili: "Nlolo: thamani ya mfuko wa sera yako {{policyNumber}} ni {{fundValue}}, inatosha ada za takriban miezi {{monthsCovered}}. Lipa ada yako ili kinga yako iendelee." `communication.UnitLinkedEventListener` maps UnitsAllocated → ALLOCATED (only for the first premium, to avoid a message per month), LowFund → LOW_FUND, FundExhausted → LAPSED_EXHAUSTED, and UnitsSold with purpose SURRENDER/MATURITY → PROCEEDS. It follows FuneralEventListener's recipient lookup.

- [ ] **Step 8: Tests.**
  - `ChargeRunIntegrationTest`:
    - `theFirstChargeFallsOneMonthAfterIssueNotOnIssueDay`;
    - `feeAndCostOfInsuranceSellUnitsAtTheChargeDatesForwardPrice`;
    - `costOfInsuranceIsSizedAtTheLatestPriceBeforeTheChargeDate`;
    - `aRunTwiceForOneDateChargesOnce`;
    - `aFrozenPolicyIsNotCharged`;
    - `aBySexTableWithNoRecordedSexRefusesAndAlerts`;
    - `chargesAreTakenAcrossFundsInProportionToValue`.
  - `UnitLinkedLapseIntegrationTest`:
    - `anExhaustedFundSellsEverythingWritesOffTheRestAndLapses`;
    - `billingDoesNotLapseAnExhaustionPolicyPastItsMinimumYears`;
    - `billingDoesLapseItInsideTheMinimumYears`;
    - `aNonPaymentVersionLapsesOnArrearsAndSellsTheUnitsForward` (asserts the LAPSE SELL ALL order is bound by the cut-off);
    - `paidUpAndLoansAreRefusedForUnitLinked`.
  - `UnitLinkedNoticeIntegrationTest`: low fund once per month; exhausted.

- [ ] **Step 9:** Run `./mvnw -o clean test-compile && ./mvnw -o -q test -Dtest='CostOfInsuranceTest,ChargeDatesTest,ChargeRunIntegrationTest,UnitLinkedLapseIntegrationTest,UnitLinkedNoticeIntegrationTest,PolicyLapseRecommendedEventListener*,ModularityTests'`. Expected: pass.

- [ ] **Step 10:** Commit: `feat(unitlinked): monthly fee and cost of insurance sold forward; exhaustion lapses, arrears lapse per the version's rule`.

---

### Task 7: Exits -- death, surrender, maturity, lapse, free-look

**Files:**
- Create: `unitlinked/application/{Exits,MaturitySweep,ClaimEventListener}.java`, `api/{DeathValueView,UnitsNotYetPricedException}.java`
- Modify: `claims/package-info.java` (+`"unitlinked::api"`), `claims/application/ClaimsApiImpl.ceilingFor` (R7), `claims/infrastructure/*ExceptionHandler` (409 UNITS_NOT_YET_PRICED)
- Modify: `policy/application/PolicyApiImpl` (`requestSurrender` / `approveSurrender` R5, `expirePolicy` R4)
- Modify: `benefitpayout/api/BenefitPayoutApi` (+`releaseUnitLinkedFreeLookRefund`), `BenefitPayoutApiImpl` (`requestFreeLook`, `approveFreeLook` R6), `db-migrations/benefitpayout/V6__free_look_refund_pending.sql`
- Modify: `payment/application/PaymentRequestListener` (`case "unitlinked.PayoutRequested" -> withTenant(envelope, this::handleAccountPayout)`), `db-migrations/payment/V13__unit_linked_purposes.sql` (adds PRICE_CORRECTION_PAYOUT and LAPSE_SURRENDER_PAYOUT to the purpose CHECK)
- Test: `UnitLinkedDeathIntegrationTest`, `UnitLinkedSurrenderIntegrationTest`, `UnitLinkedMaturityIntegrationTest`, `UnitLinkedFreeLookIntegrationTest`, `UnitLinkedClaimContractTest`

**Interfaces:**
- Consumes: `UnitLedger.freeze/unfreeze/holdings`, `PendingOrder.sellAll`, `ExitsCallback.afterPriced`.
- Produces:
  - `UnitLinkedApi.decidesDeath(String policyNumber) → boolean`;
  - `deathValue(UUID claimId) → DeathValueView(BigDecimal benefit, BigDecimal proceeds, BigDecimal costOfInsuranceRefund, BigDecimal sumAssured, DeathRule rule, String currency)`, which throws `UnitsNotYetPricedException(List<String> fundCodes, LocalDate boundDate)` while any DEATH order for the claim is WAITING;
  - `Exits.onDeathRegistered(claimId, policyNumber, dateOfDeath, registeredAt)`, `onDeathRejected(claimId)`, `onDeathApproved(claimId)`;
  - `Exits.onSurrenderApproved(surrenderRequestId, policyNumber, payeeRef, approvedAt)`;
  - `Exits.mature(policyNumber, maturityDate)`;
  - `Exits.lapse(policyNumber, lapsedAt)`;
  - `Exits.onFreeLookCancelled(policyNumber, cancelledAt)`;
  - `BenefitPayoutApi.releaseUnitLinkedFreeLookRefund(UUID cancellationId, BigDecimal amount)`;
  - event `unitlinked.PayoutRequested {purpose, sourceRef, idempotencyKey, policyNumber, payeeRef, amount}`.

- [ ] **Step 1: The shared exit.**

```java
/** Freeze, return any money not yet in units, and sell every unit forward (spec §7). Idempotent per source. */
private void exit(String policyNumber, String purpose, FrozenReason reason, String sourceType, String sourceRef, Instant at) {
    UUID tenantId = TenantContext.get();
    if (orders.existsByTenantIdAndSourceTypeAndSourceRef(tenantId, sourceType, sourceRef)) return;
    ledger.freeze(policyNumber, reason, sourceRef);
    BigDecimal returned = BigDecimal.ZERO;
    for (PendingOrder waiting : orders.findByTenantIdAndPolicyNumberAndStatus(tenantId, policyNumber, "WAITING")) {
        if (waiting.getSide() == Side.BUY) { waiting.cancel(); orders.save(waiting); returned = returned.add(waiting.getAmount()); }
        else if (waiting.getPurpose() == Purpose.CHARGES) { waiting.cancel(); orders.save(waiting); }
    }
    exitLedger.recordReturnedMoney(policyNumber, sourceRef, returned);   // unitlinked.exit_state row (below)
    for (var h : ledger.holdings(policyNumber).entrySet()) {
        if (h.getValue().signum() == 0) continue;
        Fund fund = funds.findById(h.getKey()).orElseThrow();
        orders.save(PendingOrder.sellAll(tenantId, policyNumber, fund.getFundId(), purpose, at,
            BindingRule.boundDate(at, fund.getCutOffTime()), sourceType, sourceRef));
    }
    exitLedger.checkComplete(sourceType, sourceRef);   // no units at all: the exit is complete at once
}
```

`unitlinked.exit_state` (V2, Task 3) answers "are all this exit's orders priced?" and holds the totals. `ExitsCallback.afterPriced(order)` adds the order's proceeds to its exit_state row and, when no WAITING order remains for that source, marks it PRICED and calls the purpose's completion.

- [ ] **Step 2: Death.** `unitlinked.ClaimEventListener` (`@Component("unitLinkedClaimEventListener")`, the annuity listener's shape):
  - `claims.ClaimRegistered` with claimType DEATH, on a policy with an allocation → `exit(policy, "DEATH", DEATH, "claim", claimId, registeredAt)`. If the payload has no `registeredAt`, add it to ClaimsApiImpl's payload (additive) and to asyncapi. Never use the time the listener runs: that would be later than registration and could pick up a price published in between.
  - `claims.ClaimRejected` → `onDeathRejected`: when the exit is PRICED, place a BUY REINVESTMENT order per fund for the proceeds by the policy's split (`source_ref = claim-rejected:<claimId>`), bound at the rejection instant; unfreeze; set the exit to REVERSED.
  - `claims.ClaimApproved` (or the settlement event the annuity listener uses) → mark the exit PAID; the policy closes through claims' existing discharge.

  `deathValue(claimId)`: the exit_state for `("claim", claimId)`. If it is not PRICED, throw `UnitsNotYetPricedException`. Otherwise:
  - `proceeds` = Σ the exit's sale amounts + returned money;
  - `coiRefund` = Σ COST_OF_INSURANCE entries with `valuation_date > dateOfDeath`, recorded as `CHARGE_REFUND` money-only entries at that moment, once (source `claim:<id>:coi-refund`);
  - `benefit` = HIGHER_OF: `max(SA, proceeds)` + coiRefund; PLUS_FUND: `SA + proceeds + coiRefund`.

  `decidesDeath(policy)` = whether an allocation exists.

  `ClaimsApiImpl.ceilingFor`, before the annuity branch:

```java
if (claim.getClaimType() == ClaimType.DEATH && unitLinkedApi.decidesDeath(claim.getPolicyNumber())) {
    return unitLinkedApi.deathValue(claim.getClaimId()).benefit();
}
```

The claimable-cover read (the settlement panel's ceiling) calls the same path, so the console shows the 409 text as "waiting for unit prices of <date> for <funds>". The exception handler maps it to 409 `UNITS_NOT_YET_PRICED` with that message.

- [ ] **Step 3: Surrender (R5),** in `PolicyApiImpl`:
  - `requestSurrender`: when the version is UNIT_LINKED, skip the cash-value, policy_account and bonus checks. Apply `minimumSurrenderYears` (completed years since commencement, civil date) with "has no surrender value until <n> years". Keep the loan checks.
  - **The request stores no quoted amount for UNIT_LINKED.** Policy cannot read units (unitlinked depends on policy::api, not the reverse), and a surrender is never valued at a known price. The console shows the indicative figure from `GET /policies/{n}/units` instead. `db-migrations/policy/V36__unit_linked_policy.sql` (replacing the V36 name in the file map):

```sql
-- db-migrations/policy/V36__unit_linked_policy.sql
-- Product step 6 (U1): a unit-linked surrender is priced FORWARD after approval, so its request carries no
-- quoted value; and policy.fund_holding, never written, is superseded by unitlinked's unit ledger.
ALTER TABLE policy.surrender_request ALTER COLUMN quoted_value_amount DROP NOT NULL;
DROP TABLE policy.fund_holding;
```

    Before writing it, read the `surrender_request` definition for the exact column name and any CHECK on it, and keep every existing CHECK semantics for non-null rows.
  - `SurrenderRequest` gains a second factory, `forUnitLinked(tenantId, policyNumber, currency, payeeRef, requestedBy)`, with a null amount. The existing constructor still refuses a null amount, so every other path is unchanged. `SurrenderRequestView.quotedValueAmount` becomes nullable: update the OpenAPI schema and the console's surrender panel, which shows "Priced at the next price after approval" when it is null.
  - `approveSurrender`: UNIT_LINKED publishes `policy.UnitLinkedSurrenderApproved {surrenderRequestId, policyNumber, payeeRef, approvedBy, approvedAt}` instead of either existing payout event. PolicySurrendered is still published first, as today, so billing stops.
  - unitlinked's PolicyEventListener → `exit(policy, "SURRENDER", SURRENDER, "surrender", requestId, approvedAt)`. On PRICED → publish `unitlinked.PayoutRequested {purpose SURRENDER_PAYOUT, sourceRef = requestId, idempotencyKey = requestId, amount = proceeds + returned money}`. Step 1's SurrenderPaymentListener marks it PAID on the payment outcome, unchanged.

- [ ] **Step 4: Maturity (R4).** `expirePolicy` skips UNIT_LINKED: add `|| productApi.resolveUnitLinkedPlan(versionId).unitLinked()` to the skip condition, with the comment "its units mature through unitlinked's own run". `MaturitySweep` (hourly, the same scheduling shape) finds unit-linked policies with an allocation, `maturityDate <= today` (civil), in force and not frozen. It calls `exit(policy, "MATURITY", MATURITY, "maturity", policyNumber, BindingRule.earliestApproval(maturityDate, LocalTime.MIDNIGHT))`, so the sale binds to the maturity date itself. On PRICED → PayoutRequested purpose MATURITY_PAYOUT (payee = the policyholder's payee reference from `PolicyView`; if absent, the exit waits as AWAITING_PAYEE and staff set it via `POST /policies/{n}/units/maturity-payee`). On the payment outcome (payment publishes `payment.DisbursementCompleted` with `purpose` + `sourceRef`; listen for MATURITY_PAYOUT on a unit-linked policy) → `policyApi.markMatured(policyNumber, "system:unitlinked")`.

- [ ] **Step 5: Lapse (non-payment).** `Exits.lapse` → `exit(policy, "LAPSE", LAPSE, "lapse", policyNumber + ":" + lapsedAt, lapsedAt)`. On PRICED, publish PayoutRequested with purpose LAPSE_SURRENDER_PAYOUT if there is a payee, else wait as AWAITING_PAYEE on the same staff path as maturity.

- [ ] **Step 6: Free-look (R6).**
  - `benefitpayout/V6__free_look_refund_pending.sql`: `ALTER TABLE benefitpayout.free_look_cancellation ADD COLUMN refund_pending BOOLEAN NOT NULL DEFAULT false;`. Relax the refund-amount CHECK if one forbids NULL. Read the table's definition first and keep the existing CHECK semantics for every non-pending row.
  - `requestFreeLook`: UNIT_LINKED refuses non-empty deductions with "A unit-linked free-look refund is the actual unwinding of the policy's charges and units, not deductions", and creates the cancellation with `refund_pending = true` and refund 0.00.
  - `approveFreeLook`: UNIT_LINKED cancels the policy (`policyApi.cancelForFreeLook`) and `cancelFuture` as today, but publishes **no** PayoutRequested.
  - unitlinked reacts to `policy.PolicyCancelledFreeLook` (payload: policyNumber, cancelledAt; add `cancelledAt` if absent) with `exit(policy, "FREE_LOOK", FREE_LOOK, "freelook", policyNumber, cancelledAt)`, **and first** writes one `CHARGE_REFUND` money-only entry per ALLOCATION_CHARGE, POLICY_FEE and COST_OF_INSURANCE entry the policy has, each with `reverses_entry_id` set and `amount` = the original charge's money, positive. Source `freelook:<policy>:refund:<entryId>`.
  - On PRICED: `refund = proceeds + Σ CHARGE_REFUND + returned money`. Read the cancellation via `benefitPayoutApi.findFreeLook(policyNumber)` and call `releaseUnitLinkedFreeLookRefund(cancellationId, refund)`, which sets the amount, clears `refund_pending`, and publishes the existing FREE_LOOK_REFUND PayoutRequested with idempotency key `free-look:<cancellationId>`.

- [ ] **Step 7: Payment.** In `PaymentRequestListener`: `case "unitlinked.PayoutRequested" -> withTenant(envelope, this::handleAccountPayout);`. The payload keys match accumulation's (purpose, sourceRef, idempotencyKey, policyNumber, payeeRef, amount). Add PRICE_CORRECTION_PAYOUT and LAPSE_SURRENDER_PAYOUT to `payment.payment.purpose`'s CHECK in `payment/V13__unit_linked_purposes.sql`. Copy the D2 COMMUTATION migration's ALTER shape.

- [ ] **Step 8: Tests.** All of these assert forward binding by checking the SELL orders' `bound_date` against the trigger instant and fund cut-off, and that a price approved **before** the trigger is never the one used.
  - `UnitLinkedDeathIntegrationTest`:
    - `registrationFreezesAndSellsAtTheFirstPriceAfterIt`;
    - `approvalIsRefusedUntilTheUnitsArePriced` (409 UNITS_NOT_YET_PRICED);
    - `higherOfPaysTheSumAssuredWhenTheFundIsSmaller`;
    - `higherOfPaysTheFundWhenItIsLarger`;
    - `sumAssuredPlusFundPaysBoth`;
    - `costOfInsuranceTakenAfterTheDeathIsRefundedIntoTheClaim`;
    - `aRejectedDeathClaimReinvestsAndChargesResume`;
    - `aPremiumWaitingAtTheDeathIsReturnedNotInvested`.
  - `UnitLinkedSurrenderIntegrationTest`:
    - `theSaleBindsAtApprovalNotAtTheRequest`: request before day D's cut-off, approve after it; bound to D + 1;
    - `refusedInsideTheMinimumYears`;
    - `refusedWithAnOutstandingLoan`;
    - `theApproverCannotBeTheRequester`;
    - `theProceedsArePaidAndTheRequestMarkedPaid` (`@MockBean PaymentGatewayPort` returning success, as funeral's tests do).
  - `UnitLinkedMaturityIntegrationTest`: the maturity date's own price pays out and the policy ends MATURED; expiry never closes it first.
  - `UnitLinkedFreeLookIntegrationTest`:
    - `theRefundIsTheUnwoundEntriesNotAFormula`: after a premium, one charge run and a price rise, assert refund = Σ of the actual CHARGE_REFUND amounts + the actual FREE_LOOK_SALE proceeds, and that it differs from `premium` by exactly the units' market movement;
    - `deductionsAreRefusedForUnitLinked`.
  - `UnitLinkedClaimContractTest`: the HTTP 409 body and code.
  - Remove the `@Disabled` from Task 5's paid-correction test, and make it pass.

- [ ] **Step 9:** Run `./mvnw -o clean test-compile && ./mvnw -o -q test -Dtest='UnitLinked*IntegrationTest,UnitLinkedClaimContractTest,PriceCorrectionIntegrationTest,ClaimsApiImpl*,FreeLook*,AccountSurrender*,ModularityTests'`. Expected: pass.

- [ ] **Step 10:** Commit: `feat(unitlinked): death, surrender, maturity, lapse and free-look each freeze and sell forward; free-look unwinds the real entries`.

---

### Task 8: Accounting

**Files:**
- Create: `db-migrations/finaccounting/V9__unit_linked_accounts.sql`, `finaccounting/application/UnitLinkedEventListener.java`
- Modify: `finaccounting/domain/PostingRule.java` (constants), `ChartOfAccountBlueprint.java` (2150, 4310, 5600)
- Create: `unitlinked/application/Reconciliation.java`, `api/ReconciliationView.java`, `GET /unit-linked/reconciliation`
- Test: `UnitLinkedAccountingIntegrationTest.java`; add `GlInvariant.assertLiabilityEqualsUnitsTimesPrice(...)` to `UnitLinkedTestFixtures`, called at the end of every Task 4–7 integration test (edit each)

**Interfaces:**
- Consumes: events `UnitsAllocated`, `ChargesTaken`, `FundRevalued`, `UnitsSold`, `ChargeRefunded`, `UnitsReinvested`, `PriceCorrected` (+ their `#corr` copies).
- Produces: `UnitLinkedApi.reconciliation() → ReconciliationView(List<FundLine(fundCode, unitsInIssue, price, valuationDate, value)>, BigDecimal totalValue, BigDecimal ledgerBalance2150, BigDecimal difference)`.

- [ ] **Step 1: Migration** (V8's shape, three inserts):

```sql
-- db-migrations/finaccounting/V9__unit_linked_accounts.sql
-- Product step 6 (U1): 2150 Unit-Linked Policyholder Liability (what is owed in units), 4310 Unit-Linked
-- Charges Income (allocation charge, policy fee, cost of insurance), 5600 Change in Unit-Linked Liability
-- (price movements). For every tenant whose chart is already seeded; idempotent.
INSERT INTO finaccounting.chart_of_account (tenant_id, account_code, name, account_type, normal_balance,
     parent_code, level, posting_allowed, status, currency, control_of, created_by)
SELECT DISTINCT c.tenant_id, '2150', 'Unit-Linked Policyholder Liability', 'LIABILITY', 'CR',
       '2100', 3::smallint, TRUE, 'ACTIVE', 'TZS', NULL, 'migration:finaccounting/V9'
FROM finaccounting.chart_of_account c WHERE c.account_code = '2100'
ON CONFLICT (tenant_id, account_code) DO NOTHING;
-- the same for '4310','Unit-Linked Charges Income','INCOME','CR','4300'
-- and '5600','Change in Unit-Linked Liability','EXPENSE','DR','5000', level 2
```

Write all three out in full. Check the `account_type` and `level` values against V5's seeded rows for 4300 children and 5000 children before writing.

- [ ] **Step 2: `UnitLinkedEventListener`** (finaccounting). One balanced `JournalEntry` per event, `postEntry` (idempotent on tenant + event + sourceRef):

| Event | Legs |
|---|---|
| `unitlinked.UnitsAllocated` (sourceRef = `premium:<invoiceId>`) | DR 2140 premium; CR 2150 allocated; CR 4310 allocationCharge |
| `unitlinked.ChargesTaken` (`charge:<policy>:<date>`) | DR 2150 / CR 4310, for the fee + cost of insurance actually **sold**. A written-off shortfall was never billed and posts nothing. |
| `unitlinked.FundRevalued` (priceId) | delta > 0: DR 5600 / CR 2150; delta < 0: DR 2150 / CR 5600 |
| `unitlinked.UnitsSold` (purpose DEATH/SURRENDER/MATURITY/LAPSE/FREE_LOOK; exit sourceRef) | DR 2150 / CR 5100 proceeds |
| `unitlinked.ChargeRefunded` (refund entry id) | DR 4310 / CR 5100 |
| `unitlinked.UnitsReinvested` (`claim-rejected:<id>`) | DR 5100 / CR 2150 |

`ChargesTaken` must carry only what was actually sold: the sale proceeds of the fee and COI entries, since a capped sale sold less. Publish it from the callback once that date's orders are priced, with `policyFee` and `costOfInsurance` = the Σ entry amounts (abs), and `writtenOff` for the record only.

- [ ] **Step 3: Reconciliation (R10).** The endpoint lives in **finaccounting** (`GET /finance/unit-linked-reconciliation`), because finaccounting owns the 2150 balance. finaccounting may depend only on product and refdata, so the units side comes through an SPI declared in **product::api** (both modules already depend on product): `interface UnitLinkedValuation { List<FundValuation> valuations(); }` with `FundValuation(String fundCode, BigDecimal unitsInIssue, BigDecimal price, LocalDate priceDate, BigDecimal value)`. unitlinked's `Reconciliation` component implements it. finaccounting injects `ObjectProvider<UnitLinkedValuation>` and returns `{funds[], totalValue, ledgerBalance2150, difference}`, with an empty fund list when no implementation is present.

- [ ] **Step 4: Tests.** `UnitLinkedAccountingIntegrationTest` runs one premium, one price rise, one charge run, a surrender and a free-look, and asserts each journal's legs and that 2150's balance == Σ units × price after every step. Add the `GlInvariant` helper and call it at the end of every earlier integration test. Expect some earlier tests to need the finaccounting migrations: they are already in `FuneralTestMigrations.ALL`, which `UnitLinkedTestMigrations` chains.

- [ ] **Step 5:** Run `./mvnw -o -q test -Dtest='UnitLinked*,PriceCorrection*,ForwardPricing*,PricingRun*,ChargeRun*,ModularityTests'`. Expected: pass.

- [ ] **Step 6:** Commit: `feat(finaccounting): the unit-linked liability equals units times price, revalued per fund, released on every exit`.

---

### Task 9: API documents and the console

**Files:**
- Modify: `docs/api/openapi-product.yaml`, `-underwriting`, `-policy`, `-claims`, `-benefitpayout`; create `docs/api/openapi-unitlinked.yaml`, registered wherever the other specs are (grep the funeral commit for how `openapi-*.yaml` are loaded and regenerated into `frontend/src/types/api/`); `docs/api/asyncapi-events.yaml` (every new event and the additive keys)
- Frontend create: `src/api/unitlinked.ts`; `src/features/funds/{FundsPage.tsx,FundPricesPanel.tsx,priceForm.ts,priceForm.test.ts,AdjustmentsPanel.tsx}`; `src/features/products/{UnitLinkedTermsSection.tsx,unitLinkedSchema.ts,unitLinkedSchema.test.ts}`; `src/features/underwriting/UnitLinkedChoiceFields.tsx`; `src/features/policies/{UnitsPanel.tsx,UnitsPanel.test.tsx}`
- Frontend modify: `src/api/types.ts` (aliases), `PublishVersionForm.tsx` + `publishVersionSchema.ts` (remove the fund-definition rows; mount the section for UNIT_LINKED; hide rating and base rates; `ratingTable: []` default), `openCaseForm`/`OpenUnderwritingCasePage`, `PolicyDetailPage` (a "Units" tab for UNIT_LINKED), the surrender panel copy, `ClaimSettlementPanel` (show the 409 message as an info line, not an error), routes and nav (Configuration → Funds), Ctrl+K entries

**Rules that bite here:**
- openapi-typescript makes a `default:` property REQUIRED in request types, so don't put `default:` on request schemas.
- Lint bans synchronous setState in an effect body: derive.
- Use the Button `pending` prop and never rename a button while its request is in flight (designGuards).
- Clear a pre-filled DatePicker with `fill('')` first in e2e.
- E2E couples to accessible names: pick labels once and keep them.

- [ ] **Step 1: `priceForm.ts` + test.** The zod schema for `{fundCode, valuationDate, price, moveReason}`: price `^\d+(\.\d{1,6})?$` and > 0. A CSV textarea parses client-side to rows for preview only; the server is the authority. Tests:
  - rejects 0;
  - rejects 7 dp;
  - previews 3 CSV rows;
  - a bad row shows "Row 2: …".

- [ ] **Step 2: `FundsPage`** (PageHeader "Funds"):
  - the register table (Code, Name, Asset class, Currency, Management charge %, Cut-off, Status), "Add fund" in a drawer, "Close fund" with ConfirmAct;
  - **FundPricesPanel** per selected fund: the last 30 prices (Date, Price, Status, Proposed by, Approved by, Move reason); "Propose price" and "Upload CSV";
  - for a PROPOSED price, "Approve" is disabled with a hint "You proposed this price; a second person approves it" when `proposedBy` is the current user, and the server refusal is shown for the cut-off;
  - "Correct price" on an APPROVED row opens the same form with a required reason;
  - a "Waiting for a price" count per fund and date (`GET /funds/{code}/waiting`; add it to Task 4's controller if missing);
  - **AdjustmentsPanel** lists OPEN adjustments with Settle and Waive (both two-person, reason required for Waive).

- [ ] **Step 3: `unitLinkedSchema.ts` + `UnitLinkedTermsSection`.** Fields and labels (keep these exact, e2e uses them):
  - "Offered funds": a multi-select of OPEN funds from `GET /funds`;
  - an "Allocation bands" table ("From year", "To year (blank = onwards)", "Allocation %");
  - "Monthly policy fee";
  - "Mortality basis" (Unisex / By sex);
  - "Mortality table (CSV: age_from,age_to,sex,rate_per_mille)": paste, parsed and previewed;
  - "Death benefit" (Higher of sum assured and fund value / Sum assured plus fund value);
  - "Lapse rule" (When the fund is exhausted / On non-payment), default exhausted;
  - "Minimum premium-paying years (optional)";
  - "Minimum years before surrender";
  - "Low-fund warning (months of charges)";
  - "Minimum premiums": a row per frequency;
  - "Sum assured multiple — minimum" and "— maximum".

  Schema tests mirror the backend validator's continuity rules, with the same messages where shown.

- [ ] **Step 4: `UnitLinkedChoiceFields`** in the open-case form for UNIT_LINKED:
  - one "Split %" input per offered fund, labelled with the fund name;
  - a live "Total" line that turns red unless it is 100;
  - "Premium", "Premium frequency", "Sum assured";
  - a hint "Between X and Y (A× to B× the annual premium)" computed from the terms.

  It PUTs the choice after the case opens, as funeral's application does.

- [ ] **Step 5: `UnitsPanel`** on the policy page's "Units" tab:
  - a "Holdings" table (Fund, Units, Price, Price date, Value) and the total;
  - a "Waiting for a price" table (Fund, Side, Amount, For, Bound to date);
  - an "Entries" table (Date, Fund, Type, Units, Price, Amount);
  - a "Download statement" action, if Task 9 Step 7 adds the statement endpoint (see below).

  The surrender panel for UNIT_LINKED shows "Indicative value TZS x at <date>'s prices. The surrender is priced at the next price after it is approved", and no quoted figure. Tests:
  - renders holdings;
  - shows the waiting order's bound date;
  - shows no total when nothing is held.

- [ ] **Step 6: The claim panel.** While the ceiling read returns 409 UNITS_NOT_YET_PRICED, show its message as an info line above "Approve claim", which stays disabled with a hint. That's derived state, not stored.

- [ ] **Step 7: Statement (spec §10).** The spec lists a statement PDF. Implement it with step 3's `StatementPdf` pattern only if it fits in this task; otherwise record it as deferred to U2 in the merge notes and tell the user. **Decide at this step, don't skip silently.**

- [ ] **Step 8:** Run in the frontend: `npm run typecheck; npm run lint; npx vitest run`, chained with `;` and banners in one call. Expected: all green. Backend: `./mvnw -o -q test -Dtest='OpenApi*,AsyncApi*,ModularityTests'` (whichever spec-load tests exist; grep `openapi` in src/test).

- [ ] **Step 9:** Commit: `feat(console): the fund register and two-person prices, unit-linked terms, the fund split at sale, and the policy's units`.

---

### Task 10: Seed and end-to-end

**Files:**
- Modify: `backend/scripts/seed-dev-data.sh` (after the funeral block)
- Create: `frontend/e2e/staff-unit-linked.spec.ts`

- [ ] **Step 1: Seed.**
  - Funds NLO-EQ (equity, 1.50%, cut-off 14:00) and NLO-BD (bond, 0.75%, 14:00).
  - Prices for the last 5 civil days, proposed by staff.finance and approved by staff.admin, through the real endpoints with real Keycloak tokens as the seeder already does.
  - Product UL-INV-01 "Nlolo Wekeza" with the Task 3 fixture terms and free-look 14.
  - Mirror the endpoint shapes exactly: the seeder rots silently.
  - Apply every new migration to the dev DB by hand with psql before running it (the dev backend and migrations are decoupled), restart the backend from the worktree, then run the seeder via a temporary copy, as with funeral.

- [ ] **Step 2: E2E spec** `staff-unit-linked.spec.ts`, with `test.setTimeout(420_000)` and its own fund code per run (`E2E-<timestamp>`):
  1. The admin creates the fund with cut-off 00:00 (so today's price is approvable at once).
  2. Finance proposes yesterday's price 1.000000; the admin approves it. Assert finance's own Approve button is disabled with the second-person hint.
  3. The admin publishes a UNIT_LINKED product offering that fund.
  4. The underwriter opens a case for the seeded Amina with 100% in the fund, premium 100,000 MONTHLY and sum assured 2,000,000. The senior accepts.
  5. Find the policy. The admin collects the first premium. The Units tab shows a waiting order "Bound to" tomorrow's date. It cannot be today: with the 00:00 cut-off, a premium today binds to tomorrow.
  6. Propose tomorrow's price? It **cannot be approved yet**. Instead, assert that "Approve" refuses with the cut-off message. This is the forward-pricing proof on the real stack.

  Full pricing and surrender are proven in the backend integration tests. The e2e proves the screens, the two-person price, the binding and the refusal. Say so in the spec's header comment.

- [ ] **Step 3:** Run the spec alone with deps, twice: `npx playwright test e2e/staff-unit-linked.spec.ts --reporter=line`. Expected: green both times.

- [ ] **Step 4:** Commit: `test(e2e): a fund priced by two people, a unit-linked policy sold, and a premium bound to the next price`.

---

### Task 11: The gate

- [ ] **Step 1:** Stop the dev backend and any orphan JVMs (identify by command line; never the `redhat.java` language server).
- [ ] **Step 2:** `./mvnw -o clean test` in the background (about 85 min). Never alongside Playwright. Expect the count to rise from the funeral merge's total by this plan's new tests, with 0 failures.
- [ ] **Step 3:** Frontend `npm run typecheck; npm run lint; npx vitest run`.
- [ ] **Step 4:** Restart the dev backend from the worktree, then run the full e2e with deps.
- [ ] **Step 5: Whole-branch self-review** against spec §2–§11 and R1–R10. Look specifically at:
  - every place a price is read: is it forward, or the documented COI-sizing exception?
  - every new sweep's dependencies (interfaces only);
  - every tenant-wide query filtering `tenant_id`;
  - every `LocalDate.now()` carrying the civil zone;
  - every redelivered event being a no-op;
  - 2150 = units × price after every scenario.

  Expect a real fix cycle.
- [ ] **Step 6:** Update memory (`project_product_step6_unit_linked.md`) with the commits, test counts and deviations.
- [ ] **Step 7:** Ask the user to sign off R1–R10 and any build deviations, then `git merge --no-ff` to main with a message file and `git push origin main`.

