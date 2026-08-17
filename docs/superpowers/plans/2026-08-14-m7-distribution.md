# M7 — Distribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `distribution` module — agent onboarding and hierarchy, the `CommissionPlan`/`CommissionRule` aggregates from Di1, a four-tier commission calculation driven by policy and premium events, clawback on early lapse, and monthly statement close and payout through `payment`'s request/confirm loop.

**Architecture:** `distribution` is a thick Spring Modulith module depending on `party::api`, `product::api`, `refdata::api` — and **nothing else**. It is deliberately **not** allowed to depend on `policy`, `billing`, or `payment`; every interaction with those is by event. That constraint drives the module's single most important design decision: because `policy.PolicyLapsed` carries no agent and `PolicyApi` is unreachable, `distribution` maintains its **own local projection** of `policyNumber → agent/product/premium`, built from `policy.PolicyIssued`. Commission accrues per-event in Java; the cross-tenant monthly statement close runs as a pg_cron `SECURITY DEFINER` function, because a Java `@Scheduled` thread has no `TenantContext` and RLS shows it zero rows.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith, Postgres 16 (schema-per-module + RLS), pg_cron, Testcontainers, WireMock, Micrometer/Prometheus.

---

## Global Constraints

Every task's requirements implicitly include this section.

- **`distribution`'s `allowedDependencies` MUST stay exactly `{ "party::api", "product::api", "refdata::api" }`.** This matches `docs/02-module-architecture.md:173` and the existing `package-info.java`. Do **not** add `policy::api`, `billing::api`, or `payment::api` — `ModularityTests` enforces this and the whole projection design exists because of it.
- **`distribution/api/package-info.java` is currently MISSING `@NamedInterface("api")`** — every other module has it. Add it (Task 3). Without it, any future module declaring `distribution::api` fails Modulith verification.
- **Editing `payment`'s `PaymentRequestListener` is pre-authorized**, and only that one file in `payment`. Its own comment at `:115-118` reserves the `distribution.CommissionPayoutRequested` case.
- **Editing `billing` to add one event is pre-authorized** (Task 7, user decision 4). `BillingApiImpl.applyConfirmedPayment` currently records a premium as paid and publishes **nothing**; M7 adds `billing.PremiumCollected`.
- **Build commands.** Always, on the host, in the FOREGROUND (never backgrounded — you will not receive a notification; never inside Docker — it breaks Testcontainers networking):
  ```bash
  export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
  ./mvnw -B -o test -Dtest=SomeSpecificTest      # the normal case
  ./mvnw -B -o test                              # only when the rule below says so
  ```
- **Run the NARROWEST test set that could actually detect a regression from your change.** Measured on this branch: the full suite is 392 tests in ~7 minutes, fully serial, and **36 of its 50 classes each start their own Postgres container** — so most of that time is container and Spring-context setup unrelated to whatever you changed. Running it after every task wastes roughly an hour across this plan and slows the feedback that catches real bugs.

  **Run the FULL suite only when your task does at least one of these:**
  1. changes production code **outside** `distribution` (Task 7 touches `billing`, Task 8 touches `payment`);
  2. changes a **shared test class or test utility** (Task 10 edits `AppRolePrivilegesIntegrationTest` and `RowLevelSecurityIntegrationTest`, which every module's correctness leans on);
  3. changes **shared config** — `pom.xml`, `src/main/resources/application.yml`, `SecurityConfig`;
  4. adds or changes a **migration that some existing test's own migration list already applies** (a new `refdata` seed qualifies, because `ReferenceDataApiIntegrationTest` applies refdata's migrations);
  5. is the final verification task (Task 11), where a full `clean verify` is the entire point.

  **Otherwise run only your own new/changed tests plus any test that directly exercises the code you touched.** Adding new files inside `distribution` and their own tests does not require the other 17 modules to be re-verified.

  Two rules that are not negotiable either way: **never report coverage you did not execute** — if you ran a subset, say so plainly and say why, so the reviewer is not left inferring it; and if a targeted run surfaces anything you did not expect, escalate to the full suite immediately rather than assuming it is unrelated.
- **Baseline before M7: 392 tests, 0 failures, 0 errors.** Quote the count from whatever you actually ran, and label it (`full suite` vs `-Dtest=X`).
- **Never edit an already-applied migration.** `distribution/V1`, `billing/V1-V3`, `refdata/V1-V3` are immutable; add new numbered files.
- **Cross-module references are opaque columns, never FKs** (`docs/06-database-schema.md:29`). Intra-module FKs within `distribution` are fine and already exist.
- **Money on the wire is a decimal STRING, never a float** (`docs/06:31`, `openapi-common.yaml`'s `Money`). All five existing `MoneyDto` records use `String amount`. `openapi-distribution.yaml` currently declares `rate` as `type: number` — Task 9 fixes that.
- **Event payload money shape is always** `Map.of("amount", <BigDecimal>.toPlainString(), "currencyCode", <String>)`. Use `toPlainString()`, never `toString()`.
- **`Map.of` throws NPE on a null value.** Use `LinkedHashMap` where any field is nullable — `policy.PolicyIssued` does exactly this because `agentOfRecordId` is nullable.
- **An `AFTER_COMMIT` `@TransactionalEventListener` MUST use a `PROPAGATION_REQUIRES_NEW` `TransactionTemplate`.** A plain `@Transactional` (REQUIRED) call from an `AFTER_COMMIT` callback silently joins the already-committed producer transaction and never commits — empirically confirmed on this project with `TransactionRequiredException`. Copy `claims/application/PaymentEventListener.java`.
- **Bean names must be explicit where a class name already exists.** `policyloan`, `billing`, and `claims` each declare a `PaymentEventListener`; `billing` also declares a `PolicyEventListener`. Distribution's equivalents MUST be `@Component("distributionPaymentEventListener")` and `@Component("distributionPolicyEventListener")` or a context-startup bean-name collision fails the whole suite.
- **Every consumer of `payment.DisbursementCompleted`/`DisbursementFailed` MUST filter on `purpose`.** Distribution's value is exactly `"COMMISSION_PAYOUT"` — already allowed by `disbursement_instruction.purpose`'s CHECK (`payment/V1:54-55`, re-asserted `payment/V2:151-156`), so **no `payment` migration is needed**.
- **`DisbursementCompleted` and `DisbursementFailed` do NOT share a payload shape.** Completed carries `gatewayReference`, `amount`, `completedAt`; Failed carries **none of those**, only `reason`. Do not assume symmetry.
- **`payment`'s third outcome publishes NO event.** An indeterminate rail result records `IN_DOUBT` and emits nothing (`asyncapi-events.yaml`'s own note warns against inventing a `DisbursementInDoubt`). A statement can therefore rest at `PAYOUT_REQUESTED` indefinitely; document it as a known boundary, as `claims` does.
- **A blank idempotency key is worse than a missing one.** `PaymentRequestListener.requireKey` throws inside an `AFTER_COMMIT` listener, where the exception is swallowed and logged — it looks like a successful request that reached the rail zero times. Validate before publishing, at the distribution boundary.
- **New metrics need an alert rule AND classification.** `observability/alert-rules.yml` gets a rule for every new counter, and `AlertRuleMetricProducerTest`'s `PRODUCED_BY_THIS_APPLICATION` set must list it — that test fails otherwise. This is the M6/hardening lesson: a metric with no rule, or a rule with no producer, is silent.
- **Contract tests pair `openApi().isValid(...)` with `SpecTypeConformance.matchesDeclaredTypes(...)`.** `isValid` does **not** enforce primitive JSON types (measured); the new matcher covers exactly that gap. Use both on every response assertion carrying a decimal.
- **No `product` types in `distribution` bytecode beyond what `product::api` exposes.** `ProductApi` exposes nothing commission-related; do not reach for it.

## Pre-adjudicated decisions (user-approved before authoring — do not re-litigate)

1. **Four tiers, not five.** Implement `FIRST_YEAR`, `RENEWAL`, `OVERRIDE`, `SUPERVISOR_OVERRIDE`. **`THRESHOLD_BONUS` is deferred** — its entire input format (`commission_rule.threshold_condition JSONB`) is specified nowhere, so implementing it means inventing a schema with no business input. The column stays; nothing reads it; a rule of that tier type is rejected at authoring time with a clear message.
2. **Clawback IS implemented**, with the window as a flagged-placeholder `refdata` parameter. Without it an agent keeps first-year commission on a policy that lapses in month two.
3. **Event-driven accrual + pg_cron statement close.** Accrue per event in Java; the cross-tenant monthly close and payout trigger is a pg_cron `SECURITY DEFINER` function, mirroring `billing.sweep_billing_state()`.
4. **Add `billing.PremiumCollected`** (invoiceId, policyNumber, amount, collectedAt) rather than a two-hop projection in distribution.

## Design decisions made by this plan (flag every one for the final review)

**The commission algorithm is an explicit, flagged placeholder — not a silent guess.** Di1 (`docs/03-aggregate-design.md:148-154`) specifies aggregate *shape* only: the five tier names, `getApplicablePlan()`, and "walks the plan's rules against actual production". It defines no calculation. The word "clawback" appears in no doc or file on this platform. Everything below is invented and must carry a code comment saying so, exactly as M2 did for the underwriting decision engine (`docs/superpowers/plans/2026-08-07-m2-product-and-risk.md:24`) and M4 did for premium rates.

- **"Actual production" means premium, not sum assured.** `FIRST_YEAR` and `RENEWAL` are a percentage of the **premium amount** on the triggering event. Rationale: it is the only money figure both `PolicyIssued` and a premium-collection event carry, and commission-on-premium is the ordinary life-insurance convention. Flag for Actuarial.
- **Tier selection is by trigger, not by precedence.** `PolicyIssued` fires `FIRST_YEAR` (and the override tiers). A premium collection fires `RENEWAL` **only when the collected invoice is not the policy's first** — first-invoice collection would otherwise double-pay alongside `FIRST_YEAR`. Exactly one direct-agent rule fires per event; no rule stacking, no precedence table.
- **Hierarchy roll-up walks at most 2 levels and is depth-capped.** `OVERRIDE` credits the selling agent's immediate parent; `SUPERVISOR_OVERRIDE` credits the parent's parent. `agent_profile.hierarchy_parent_id` is a self-FK with **no cycle constraint**, so the walk MUST be depth-limited (constant `MAX_HIERARCHY_WALK_DEPTH = 2`) and MUST detect a repeated `agentId` and stop. A cycle in the data must never hang or infinitely accrue.
- **Each ancestor is paid from ITS OWN plan**, not the seller's. An ancestor with no plan, or whose plan has no rule of the relevant tier, simply earns nothing — silently and correctly, not as an error.
- **Clawback is full, not pro-rata**, and applies when a policy lapses within `TZ_COMMISSION_CLAWBACK_MONTHS` of issue. It reverses only `FIRST_YEAR`-tier accruals for that policy, and only those not yet paid out — a `PAID` statement is never retroactively altered (that would be rewriting a settled financial record). An accrual whose statement already closed produces a **negative accrual in the current open period** instead. Flag the whole rule set for business sign-off.
- **A statement's total may legitimately go negative** (clawback exceeding the period's accruals). The money CHECK is therefore `<> 0` on accrual amounts and **no** positivity constraint on `commission_statement.total_amount` — deliberately unlike every other money column on this platform. Say so in the migration.
- **Statement identity is `(tenant, agent, period, currency)`.** `commission_statement` has a single `total_currency`, so an agent selling in two currencies gets **two statements** for the period. Multi-currency is undefined in the docs; this is the minimal honest handling.
- **`agency_hierarchy` is NOT created.** V1's own header and two docs name such a table, but V1 instead models hierarchy as `agent_profile.hierarchy_parent_id` (a self-FK adjacency list), which is sufficient for a 2-level walk. Task 1 corrects V1's header comment rather than adding a redundant table. Flag as a deliberate doc correction.
- **`Broker`/bancassurance-partner modelling is out of scope.** `docs/01-domain-map.md:66-67` names them; `agent_profile` has no type discriminator and adding one is not required by any acceptance criterion. Record as deferred.
- **The projection is the module's own state, not a cache of `policy`'s.** `distribution.policy_projection` is written only from `policy.PolicyIssued` and read for clawback and renewal attribution. It is deliberately not reconciled against `policy` (which would need a forbidden dependency). A policy issued before M7 has no projection row, so no commission and no clawback — fail-silent-and-log, never fail-loud, since those policies genuinely predate commission tracking.

## File Structure

**New — `distribution` module:**
- `db-migrations/distribution/V2__grants_rls_money_checks_projection_and_statement_lifecycle.sql`
- `db-migrations/refdata/V4__seed_distribution_parameters.sql`
- `db-migrations/_post-migration/configure-commission-close.sql`
- `distribution/api/` — `DistributionApi`, `AgentView`, `CommissionPlanView`, `CommissionRuleView`, `CommissionStatementView`, `CommissionAccrualView`, `TierType`, `LicenseStatus`, `StatementStatus`, `AgentNotFoundException`, `CommissionPlanNotFoundException`, `DistributionValidationException`, `InvalidAgentStateException`
- `distribution/domain/` — `AgentProfile`, `CommissionPlan`, `CommissionRule`, `CommissionStatement`, `CommissionAccrual`, `PolicyProjection`, `CommissionCalculator`
- `distribution/infrastructure/` — 6 repositories, `AgentController`, `CommissionPlanController`, DTOs, `DistributionExceptionHandler`
- `distribution/application/` — `DistributionApiImpl`, `PolicyEventListener`, `PremiumEventListener`, `PaymentEventListener`
- `src/test/java/.../distribution/` — `CommissionCalculatorTest`, `AgentProfileTest`, `CommissionStatementStateMachineTest`, `DistributionApiIntegrationTest`, `CommissionAccrualEndToEndTest`, `ClawbackIntegrationTest`, `CommissionCloseSweepPsqlTest`, `DistributionContractTest`

**Modified — other modules (each minimal and justified):**
- `billing/api/BillingApi.java`, `billing/application/BillingApiImpl.java` — publish `billing.PremiumCollected`
- `payment/application/PaymentRequestListener.java` — the one reserved case
- `api/openapi/openapi-distribution.yaml`, `api/asyncapi-events.yaml`
- `observability/alert-rules.yml`, `src/test/java/.../AlertRuleMetricProducerTest.java`
- `src/test/java/.../AppRolePrivilegesIntegrationTest.java`, `RowLevelSecurityIntegrationTest.java`

---

### Task 1: `distribution/V2` — grants, RLS completion, money guards, projection, statement lifecycle

**Files:**
- Create: `db-migrations/distribution/V2__grants_rls_money_checks_projection_and_statement_lifecycle.sql`
- Modify: `db-migrations/distribution/V1__create_distribution_schema.sql` — **comment only**, see Step 2

**Interfaces:**
- Produces: every table and column Tasks 3-10 depend on.

`distribution/V1` ships **zero `GRANT` statements** — `app_role` cannot reach the schema at all, so the first real query fails with `permission denied for schema distribution`. It also enables RLS on only 1 of 4 tables. This is the fourth consecutive milestone with this exact defect (`billing/V2:1-3`, `payment/V2`, `claims/V2` each document it).

- [ ] **Step 1: Write the migration**

```sql
-- db-migrations/distribution/V2__grants_rls_money_checks_projection_and_statement_lifecycle.sql
-- Completes distribution/V1, which shipped with zero GRANTs and RLS on 1 of its 4 tables -- the
-- same bug class billing/V2, payment/V2 and claims/V2 each document. Migrations run as the
-- Postgres superuser (scripts/migrate.sh), which owns every table, so the gap is invisible to any
-- test whose DataSource connects as that same superuser. app_role is the app's real runtime
-- identity and currently has no access whatsoever.

-- =============================================================================
-- 1. GRANTs. Without these the module is unreachable at runtime.
-- =============================================================================
GRANT USAGE ON SCHEMA distribution TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA distribution TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA distribution GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;

-- =============================================================================
-- 2. RLS completion. V1 covered agent_profile only; the other three all carry
--    tenant_id NOT NULL and had no policy at all, so app_role could read every
--    tenant's commission plans, rules and statements.
-- =============================================================================
ALTER TABLE distribution.commission_plan ENABLE ROW LEVEL SECURITY;
CREATE POLICY commission_plan_tenant_isolation ON distribution.commission_plan
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE distribution.commission_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY commission_rule_tenant_isolation ON distribution.commission_rule
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE distribution.commission_statement ENABLE ROW LEVEL SECURITY;
CREATE POLICY commission_statement_tenant_isolation ON distribution.commission_statement
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- =============================================================================
-- 3. Missing tenant index (docs/06-database-schema.md:40 requires tenant_id
--    indexed on every tenant-scoped table; V1 gave commission_rule none).
-- =============================================================================
CREATE INDEX idx_commission_rule_tenant ON distribution.commission_rule (tenant_id);

-- =============================================================================
-- 4. Optimistic locking on the two aggregate roots Di1 promoted. V1 has version
--    only on agent_profile. docs/06:27's list must be amended to match (Task 10).
-- =============================================================================
ALTER TABLE distribution.commission_plan ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE distribution.commission_statement ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Audit columns, per docs/06:28 ("on every mutable table"). V1 gave these three none.
ALTER TABLE distribution.commission_plan ADD COLUMN created_by VARCHAR(100);
ALTER TABLE distribution.commission_plan ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE distribution.commission_plan ADD COLUMN updated_by VARCHAR(100);
ALTER TABLE distribution.commission_statement ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE distribution.commission_statement ADD COLUMN updated_by VARCHAR(100);

-- =============================================================================
-- 5. Money guards. V1 has NONE.
--
--    Note the deliberate asymmetry with every other schema on this platform: an
--    accrual amount is CHECK (<> 0) rather than (> 0), and commission_statement
--    .total_amount gets NO positivity constraint at all -- because a clawback
--    (M7 user decision 2) is a genuinely negative accrual, and a period whose
--    clawbacks exceed its accruals has a legitimately negative total. A (> 0)
--    guard here would reject correct data. Stated explicitly so a future
--    "consistency" fix does not add one.
-- =============================================================================
ALTER TABLE distribution.commission_rule
    ADD CONSTRAINT commission_rule_rate_positive
    CHECK (rate IS NULL OR rate > 0);
ALTER TABLE distribution.commission_rule
    ADD CONSTRAINT commission_rule_flat_amount_positive
    CHECK (flat_amount IS NULL OR flat_amount > 0);

-- V1 leaves both nullable with no XOR, so a rule with NEITHER a rate nor a flat
-- amount is insertable and would silently accrue nothing. Exactly one must be set.
ALTER TABLE distribution.commission_rule
    ADD CONSTRAINT commission_rule_rate_xor_flat
    CHECK ((rate IS NOT NULL AND flat_amount IS NULL)
        OR (rate IS NULL AND flat_amount IS NOT NULL));

-- flat_amount and flat_currency travel together or not at all.
ALTER TABLE distribution.commission_rule
    ADD CONSTRAINT commission_rule_flat_currency_paired
    CHECK ((flat_amount IS NULL) = (flat_currency IS NULL));

-- =============================================================================
-- 6. Statement lifecycle. V1's CHECK is ('PENDING','PAID') -- two states, which
--    cannot express the request/confirm pattern this platform mandates for every
--    money movement: after publishing CommissionPayoutRequested the statement is
--    neither PENDING nor PAID, and a DisbursementFailed has nowhere to land.
--    Direct analogue of claims' markSettlementRequested/markSettlementFailed.
-- =============================================================================
ALTER TABLE distribution.commission_statement
    DROP CONSTRAINT commission_statement_status_check;
ALTER TABLE distribution.commission_statement
    ADD CONSTRAINT commission_statement_status_check CHECK (status IN
        ('OPEN','CLOSED','PAYOUT_REQUESTED','PAID','PAYOUT_FAILED'));

-- V1 defaults status to 'PENDING', which is no longer an allowed value. Re-point
-- the default to OPEN and migrate any existing row (there are none in practice --
-- no code has ever written this table -- but a DEFAULT that violates its own
-- CHECK is a trap for the next writer).
UPDATE distribution.commission_statement SET status = 'OPEN' WHERE status = 'PENDING';
ALTER TABLE distribution.commission_statement ALTER COLUMN status SET DEFAULT 'OPEN';

-- The payout columns, mirroring claims/V2's precedent exactly: payeeRef cannot be
-- derived (party.PartyView exposes no MSISDN), the idempotency key is persisted so
-- a retry after PAYOUT_FAILED can deliberately use a NEW key while the original
-- stays auditable, and the failure reason lands somewhere visible.
ALTER TABLE distribution.commission_statement ADD COLUMN payee_ref VARCHAR(100);
ALTER TABLE distribution.commission_statement ADD COLUMN payout_idempotency_key VARCHAR(100);
ALTER TABLE distribution.commission_statement ADD COLUMN payout_failure_reason VARCHAR(500);
ALTER TABLE distribution.commission_statement ADD COLUMN closed_at TIMESTAMPTZ;
ALTER TABLE distribution.commission_statement ADD COLUMN paid_at TIMESTAMPTZ;

-- Tenant-scoped, matching payment/V2's registry fix: a globally-unique key would
-- let one tenant's key collide with another's.
CREATE UNIQUE INDEX idx_commission_statement_payout_key
    ON distribution.commission_statement (tenant_id, payout_idempotency_key)
    WHERE payout_idempotency_key IS NOT NULL;

-- A statement is identified by (tenant, agent, period, currency). One currency per
-- statement, so an agent selling in two currencies gets two statements for the
-- period -- the minimal honest handling of a case no doc defines.
CREATE UNIQUE INDEX ux_commission_statement_identity
    ON distribution.commission_statement (tenant_id, agent_id, period, total_currency);

-- =============================================================================
-- 7. commission_accrual -- the per-event line items a statement totals.
--    V1 has no such table: commission_statement.total_amount is a bare number
--    with nothing behind it, so nothing could explain or audit a total, and
--    clawback would have nothing to reverse.
-- =============================================================================
CREATE TABLE distribution.commission_accrual (
    accrual_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL,
    agent_id         UUID NOT NULL REFERENCES distribution.agent_profile(agent_id),
    statement_id     UUID REFERENCES distribution.commission_statement(statement_id),
    policy_number    VARCHAR(20) NOT NULL,   -- opaque ref into policy, never an FK
    tier_type        VARCHAR(20) NOT NULL CHECK (tier_type IN
        ('FIRST_YEAR','RENEWAL','OVERRIDE','SUPERVISOR_OVERRIDE','THRESHOLD_BONUS')),
    amount           NUMERIC(19,2) NOT NULL CHECK (amount <> 0),
    currency         CHAR(3) NOT NULL,
    period           VARCHAR(7) NOT NULL,    -- 'YYYY-MM', the period this accrual falls in
    -- The event that caused this accrual, for idempotent replay. For an issuance
    -- accrual this is the policy_number; for a renewal it is the invoice id; for a
    -- clawback it is the reversed accrual's own id.
    source_ref       VARCHAR(100) NOT NULL,
    reverses_accrual_id UUID REFERENCES distribution.commission_accrual(accrual_id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       VARCHAR(100)
);
CREATE INDEX idx_commission_accrual_tenant ON distribution.commission_accrual (tenant_id);
CREATE INDEX idx_commission_accrual_agent_period ON distribution.commission_accrual (agent_id, period);
CREATE INDEX idx_commission_accrual_statement ON distribution.commission_accrual (statement_id);
CREATE INDEX idx_commission_accrual_policy ON distribution.commission_accrual (policy_number);

-- Idempotent replay: the same (agent, tier, source_ref) can accrue only once, so a
-- redelivered PolicyIssued or PremiumCollected cannot double-pay. A clawback row is
-- excluded because it legitimately shares source_ref semantics with its target.
CREATE UNIQUE INDEX ux_commission_accrual_once
    ON distribution.commission_accrual (tenant_id, agent_id, tier_type, source_ref)
    WHERE reverses_accrual_id IS NULL;

-- A given accrual may be reversed at most once.
CREATE UNIQUE INDEX ux_commission_accrual_single_reversal
    ON distribution.commission_accrual (reverses_accrual_id)
    WHERE reverses_accrual_id IS NOT NULL;

ALTER TABLE distribution.commission_accrual ENABLE ROW LEVEL SECURITY;
CREATE POLICY commission_accrual_tenant_isolation ON distribution.commission_accrual
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON distribution.commission_accrual TO app_role;

-- =============================================================================
-- 8. policy_projection -- distribution's OWN state, not a cache of policy's.
--
--    Why this table has to exist: policy.PolicyLapsed carries only policyNumber
--    and lapsedAt (no agentOfRecordId, no productId), and `policy` is NOT in
--    distribution's allowedDependencies, so PolicyApi.getPolicy is unreachable
--    from here. Without a local projection, distribution cannot answer "whose
--    commission do I claw back?" at lapse time, nor "which agent and plan?" when
--    a renewal premium is collected. Built solely from policy.PolicyIssued.
--
--    Deliberately NOT reconciled against policy -- that would need the forbidden
--    dependency. A policy issued before M7 has no row here, so it accrues no
--    commission and is never clawed back; that is correct, since those policies
--    predate commission tracking entirely. Log and move on, never throw.
-- =============================================================================
CREATE TABLE distribution.policy_projection (
    policy_number      VARCHAR(20) NOT NULL,
    tenant_id          UUID NOT NULL,
    agent_id           UUID,   -- resolved from agentOfRecordId; NULL when a policy was sold direct
    product_id         UUID NOT NULL,
    premium_amount     NUMERIC(19,2) NOT NULL CHECK (premium_amount > 0),
    premium_currency   CHAR(3) NOT NULL,
    issue_date         DATE NOT NULL,
    first_invoice_id   UUID,   -- amended in Task 7: was first_invoice_collected BOOLEAN; see Task 7 Step 2
    lapsed_at          TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, policy_number)
);
CREATE INDEX idx_policy_projection_agent ON distribution.policy_projection (agent_id);

ALTER TABLE distribution.policy_projection ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_projection_tenant_isolation ON distribution.policy_projection
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON distribution.policy_projection TO app_role;
```

- [ ] **Step 2: Correct V1's header comment (comment only — the DDL is immutable)**

`db-migrations/distribution/V1__create_distribution_schema.sql:2` reads `-- Owns: agent_profile, agency_hierarchy, commission_plan, commission_rule, commission_statement`. No `agency_hierarchy` table is ever created; hierarchy is `agent_profile.hierarchy_parent_id`, a self-FK adjacency list. Change only that comment line to name the real tables and state that hierarchy is the self-FK — do not touch a single DDL statement in V1.

- [ ] **Step 3: Verify empirically against a live container**

Do NOT verify by inspection. Apply `audit/V1`, `distribution/V1`, `distribution/V2` in order against a disposable `postgres:16` (`docker exec`, prefixed `MSYS_NO_PATHCONV=1` on Git Bash), then:

```bash
docker exec <c> psql -U postgres -d lifeplatform -c "
SELECT c.relname, c.relrowsecurity AS rls,
       EXISTS(SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid) AS has_policy,
       has_table_privilege('app_role', c.oid, 'SELECT') AS sel,
       has_table_privilege('app_role', c.oid, 'UPDATE') AS upd
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'distribution' AND c.relkind = 'r' ORDER BY c.relname;"
```

Expected: **six rows** (`agent_profile`, `commission_accrual`, `commission_plan`, `commission_rule`, `commission_statement`, `policy_projection`), every one `rls=t has_policy=t sel=t upd=t`. Also confirm `has_schema_privilege('app_role','distribution','USAGE')` is true, and prove the XOR guard by attempting an insert with both `rate` and `flat_amount` set (must fail) and with neither (must fail). Report the real output.

- [ ] **Step 4: Commit**

```bash
git add db-migrations/distribution/
git commit -m "feat: complete the distribution schema with grants, RLS, accruals and a real statement lifecycle"
```

---

### Task 2: `refdata/V4` — the flagged commission placeholders

**Files:**
- Create: `db-migrations/refdata/V4__seed_distribution_parameters.sql`

**Interfaces:**
- Produces: the parameter keys `CommissionCalculator` (Task 4) and the clawback listener (Task 6) read.

- [ ] **Step 1: Write the seed**

Follow `refdata/V3__seed_billing_parameters.sql`'s style exactly — a comment naming the consumer, why the value is a placeholder, and a trailing `-- PLACEHOLDER, pending <owner> sign-off` on each row.

```sql
-- db-migrations/refdata/V4__seed_distribution_parameters.sql
-- M7 (distribution) additions. V1-V3 are taken by prior milestones; this file is additive, the
-- same convention V2's and V3's own headers established.
--
-- EVERY VALUE HERE IS INVENTED. Di1 (docs/03-aggregate-design.md:148-154) specifies the shape of
-- CommissionPlan/CommissionRule and names five tier types, but defines NO calculation semantics
-- whatsoever, and the word "clawback" appears in no Phase 0 document. These are externalized
-- rather than hardcoded so correcting them is a data change, following the same "flag it, don't
-- guess silently" convention as TZ_BASE_PREMIUM_RATE_PER_MILLE and DUNNING_ESCALATION_DAYS.

-- Consumed by distribution.application.PolicyEventListener's clawback path (via
-- CommissionCalculator): a policy that lapses within this many months of its issue date reverses
-- its FIRST_YEAR accrual. 12 months is the ordinary first-year commission-earning period in life
-- insurance, but no doc, statute or TIRA guidance in this repository states a figure.
INSERT INTO refdata.reference_code_set (code_set_key, code, label, value, jurisdiction) VALUES
    ('TZ_COMMISSION_CLAWBACK_MONTHS', 'DEFAULT', 'Months after issue within which a lapse reverses first-year commission', '12', 'TZ'); -- PLACEHOLDER, pending Legal/Compliance and Distribution sign-off

-- NOTE, deliberately only ONE parameter in this file. A license-expiry warning threshold was
-- considered and left out: nothing in M7 reads it, because the distribution.AgentLicenseExpiring
-- event is declared in api/asyncapi-events.yaml but has no producer in this plan (deferred, see
-- Self-Review Notes). Seeding a parameter no code consumes is the same "value that exists and is
-- reachable from nowhere" smell a prior milestone's review flagged for unreachable enum values --
-- so it lands with the sweep that needs it, not before. distribution/V1 already ships the
-- supporting partial index (idx_agent_license_expiry) for whoever builds it.
```

- [ ] **Step 2: Verify and commit**

Apply `refdata/V1-V4` against a disposable container and confirm the key resolves via the same query shape `ReferenceDataApi.getValue` uses. Then run the **full suite** — Global Constraint #4 requires it here: `ReferenceDataApiIntegrationTest` already applies refdata's migration list, so V4 joins every other test's baseline the moment Flyway picks it up from the classpath, not just this one test's:

```bash
./mvnw -B -o test
git add db-migrations/refdata/V4__seed_distribution_parameters.sql
git commit -m "feat: seed the flagged commission clawback placeholder"
```

---

### Task 3: `distribution` domain — agents, plans, rules, statements, accruals, projection

**Files:**
- Create: `distribution/api/TierType.java`, `LicenseStatus.java`, `StatementStatus.java`, `AgentNotFoundException.java`, `CommissionPlanNotFoundException.java`, `DistributionValidationException.java`, `InvalidAgentStateException.java`
- Create: `distribution/domain/AgentProfile.java`, `CommissionPlan.java`, `CommissionRule.java`, `CommissionStatement.java`, `CommissionAccrual.java`, `PolicyProjection.java`, `PolicyProjectionId.java`
- Create: `distribution/infrastructure/AgentProfileRepository.java`, `CommissionPlanRepository.java`, `CommissionRuleRepository.java`, `CommissionStatementRepository.java`, `CommissionAccrualRepository.java`, `PolicyProjectionRepository.java`
- Modify: `distribution/api/package-info.java` — add `@NamedInterface("api")`
- Test: `src/test/java/.../distribution/AgentProfileTest.java`, `CommissionStatementStateMachineTest.java`

**Interfaces:**
- Consumes: Task 1's tables.
- Produces: every type Tasks 4-10 use.

- [ ] **Step 1: Add the missing `@NamedInterface`**

`distribution/api/package-info.java` is a bare `package` declaration. Every other module's is annotated. Make it:

```java
@org.springframework.modulith.NamedInterface("api")
package tz.co.nlolo.lifeplatform.distribution.api;
```

- [ ] **Step 2: The enums**

Match V1's and V2's CHECK constraints exactly.

```java
public enum TierType { FIRST_YEAR, RENEWAL, OVERRIDE, SUPERVISOR_OVERRIDE, THRESHOLD_BONUS }
```
```java
public enum LicenseStatus { ACTIVE, EXPIRED, SUSPENDED }
```
```java
public enum StatementStatus { OPEN, CLOSED, PAYOUT_REQUESTED, PAID, PAYOUT_FAILED }
```

`THRESHOLD_BONUS` is present because the DB CHECK allows it and a rule row could carry it, but **nothing computes it** (user decision 1). `CommissionCalculator` must skip it explicitly with a comment, and Task 5's plan-authoring path must reject it.

- [ ] **Step 3: `CommissionStatement` and its state machine**

Every transition is a method on the entity, following the established idempotent-on-repeat / reject-on-conflict shape (read `claims/domain/Claim.java`'s transitions as the template — same shape, same reasoning).

```java
    /** Monthly close: OPEN -> CLOSED. After this, accruals for the period attach to the NEXT
     * statement, so a late clawback lands in the current open period rather than rewriting a
     * closed one.
     *
     * <p><b>Known duplication, deliberate — flag it, do not "clean it up".</b> The production
     * closer is the pg_cron SECURITY DEFINER function distribution.close_commission_statements()
     * (Task 9), because closing is a CROSS-TENANT sweep and a Java thread has no TenantContext
     * (RLS would show it zero rows). So this method has no production caller and the transition
     * rule effectively lives twice: as the SQL predicate, and here. It is kept because the domain
     * should still state its own rule, it is the only place the guard is unit-testable without a
     * database, and a future manual/staff close has somewhere correct to go. The psql test in
     * Task 9 is what keeps the two definitions honest with each other. */
    public void close(Instant closedAt) {
        if (status == StatementStatus.CLOSED) {
            return;
        }
        if (status != StatementStatus.OPEN) {
            throw new InvalidAgentStateException(
                "Statement " + statementId + " is " + status + ", cannot close");
        }
        this.status = StatementStatus.CLOSED;
        this.closedAt = closedAt;
    }

    /** CommissionPayoutRequested published. CLOSED or PAYOUT_FAILED -> PAYOUT_REQUESTED.
     * PAYOUT_FAILED is a valid source: the whole point of that state is that staff can retry,
     * and a retry MUST use a new idempotency key (payment dedupes on the old one). */
    public void markPayoutRequested(String idempotencyKey, String payeeRef) {
        if (status == StatementStatus.PAYOUT_REQUESTED) {
            return;
        }
        if (status != StatementStatus.CLOSED && status != StatementStatus.PAYOUT_FAILED) {
            throw new InvalidAgentStateException(
                "Statement " + statementId + " is " + status + ", cannot request payout");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new DistributionValidationException("A payout idempotency key is required");
        }
        if (payeeRef == null || payeeRef.isBlank()) {
            throw new DistributionValidationException("A payeeRef is required to pay a statement");
        }
        if (totalAmount == null || totalAmount.signum() <= 0) {
            // A zero or negative total is a real, correct outcome when clawbacks meet or exceed
            // accruals -- but there is nothing to pay, and the rail would reject it anyway.
            throw new InvalidAgentStateException(
                "Statement " + statementId + " totals " + totalAmount + "; nothing to pay out");
        }
        this.status = StatementStatus.PAYOUT_REQUESTED;
        this.payoutIdempotencyKey = idempotencyKey;
        this.payeeRef = payeeRef;
        this.payoutFailureReason = null; // a fresh attempt clears the previous failure
    }

    /** payment.DisbursementCompleted. PAYOUT_REQUESTED -> PAID. Terminal. */
    public void markPaid(Instant paidAt) {
        if (status == StatementStatus.PAID) {
            return;
        }
        if (status != StatementStatus.PAYOUT_REQUESTED) {
            throw new InvalidAgentStateException(
                "Statement " + statementId + " is " + status + ", cannot mark paid");
        }
        this.status = StatementStatus.PAID;
        this.paidAt = paidAt;
    }

    /** payment.DisbursementFailed. PAYOUT_REQUESTED -> PAYOUT_FAILED, reason preserved so the
     * statement lands on a staff retry worklist rather than being retried silently. */
    public void markPayoutFailed(String reason) {
        if (status != StatementStatus.PAYOUT_REQUESTED) {
            return; // a redelivered failure after a manual retry already moved it on
        }
        this.status = StatementStatus.PAYOUT_FAILED;
        this.payoutFailureReason = reason;
    }

    /** Recomputed from this statement's accruals whenever one is added. May legitimately be
     * negative or zero once clawbacks are involved -- see V2's own comment on why no positivity
     * CHECK exists on this column. */
    public void recomputeTotal(java.util.List<CommissionAccrual> accruals) {
        this.totalAmount = accruals.stream()
            .map(CommissionAccrual::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
```

- [ ] **Step 4: `AgentProfile`, `CommissionPlan`, `CommissionRule`, `CommissionAccrual`, `PolicyProjection`**

Map their tables 1:1. `AgentProfile` and `CommissionPlan`/`CommissionStatement` carry `@Version long version` (V1 + Task 1).

**`PolicyProjection` has a composite primary key** `(tenant_id, policy_number)` — unlike every other entity here, whose PK is a single generated UUID. It therefore needs an `@IdClass(PolicyProjectionId.class)` with a matching `PolicyProjectionId` record/class (`tenantId` + `policyNumber`, `Serializable`, with `equals`/`hashCode`). `payment`'s `DisbursementInstruction` already uses the `@IdClass` pattern on this platform — read it for the exact shape rather than inventing one, including how it handles the JPA no-arg-constructor requirement.

`AgentProfile` needs:

```java
    /** An agent must hold an ACTIVE license to accrue commission. An EXPIRED or SUSPENDED agent
     * is not an error -- the accrual is simply skipped and logged, because the sale itself was
     * still valid and the policy still exists. */
    public boolean canAccrueCommission() {
        return licenseStatus == LicenseStatus.ACTIVE;
    }
```

`CommissionRule` needs a guard rejecting `THRESHOLD_BONUS` at construction, with the user-decision reason in the message.

- [ ] **Step 5: Repositories — every method tenant-scoped**

RLS is the backstop, not the only guard; this project always threads `tenantId` explicitly.

```java
public interface CommissionAccrualRepository extends JpaRepository<CommissionAccrual, UUID> {
    List<CommissionAccrual> findByStatementIdAndTenantId(UUID statementId, UUID tenantId);
    List<CommissionAccrual> findByTenantIdAndAgentIdAndPeriod(UUID tenantId, UUID agentId, String period);
    /** Clawback target lookup: the unreversed FIRST_YEAR accrual for a policy. */
    List<CommissionAccrual> findByTenantIdAndPolicyNumberAndTierTypeAndReversesAccrualIdIsNull(
        UUID tenantId, String policyNumber, TierType tierType);
    boolean existsByTenantIdAndAgentIdAndTierTypeAndSourceRefAndReversesAccrualIdIsNull(
        UUID tenantId, UUID agentId, TierType tierType, String sourceRef);
}
```
```java
public interface CommissionStatementRepository extends JpaRepository<CommissionStatement, UUID> {
    Optional<CommissionStatement> findByStatementIdAndTenantId(UUID statementId, UUID tenantId);
    Optional<CommissionStatement> findByTenantIdAndAgentIdAndPeriodAndTotalCurrency(
        UUID tenantId, UUID agentId, String period, String totalCurrency);
    List<CommissionStatement> findByTenantIdAndAgentIdOrderByPeriodDesc(UUID tenantId, UUID agentId);
}
```
```java
public interface PolicyProjectionRepository extends JpaRepository<PolicyProjection, PolicyProjectionId> {
    Optional<PolicyProjection> findByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);
}
```

Plus `AgentProfileRepository` (`findByAgentIdAndTenantId`, `findByTenantIdAndPartyId`, `findByTenantIdAndHierarchyParentId`, and a license-expiry query for Task 8), `CommissionPlanRepository` (`findByCommissionPlanIdAndTenantId`, `findByTenantIdAndProductIdAndStatus`), `CommissionRuleRepository` (`findByCommissionPlanIdAndTenantId`).

- [ ] **Step 6: Write the two unit tests**

Both are plain JUnit — **no Spring, no containers** — so the state machine and the license rule are provable without infrastructure.

`CommissionStatementStateMachineTest` must cover every legal transition and a representative sample of illegal ones: the full happy path `OPEN → CLOSED → PAYOUT_REQUESTED → PAID`; the retry path `PAYOUT_REQUESTED → PAYOUT_FAILED → PAYOUT_REQUESTED` (with a **new** key) `→ PAID`; idempotency on each terminal transition; a blank key and a blank `payeeRef` both rejected; a zero total and a negative total both rejected at `markPayoutRequested`; `markPayoutFailed` a silent no-op when not `PAYOUT_REQUESTED`; and `recomputeTotal` producing a negative total from a clawback-heavy accrual list without throwing.

`AgentProfileTest` covers `canAccrueCommission` across all three license statuses, and `CommissionRule`'s `THRESHOLD_BONUS` rejection.

- [ ] **Step 7: Run and commit**

```bash
# Targeted only. This task adds new files inside `distribution` plus its own two unit tests, and
# touches nothing shared -- none of the full-suite triggers in Global Constraints apply.
./mvnw -B -o test -Dtest='AgentProfileTest,CommissionStatementStateMachineTest'
git add -A && git commit -m "feat: distribution domain, statement lifecycle, and the accrual/projection model"
```

---

### Task 4: `CommissionCalculator` — the four-tier algorithm, in one testable place

**Files:**
- Create: `distribution/domain/CommissionCalculator.java`
- Test: `src/test/java/.../distribution/CommissionCalculatorTest.java`

**Interfaces:**
- Consumes: Task 3's entities and enums.
- Produces: `CommissionCalculator.calculate(...)` returning the accruals a single triggering event should create. Tasks 6 and 7 call it.

This is the acceptance criterion's core ("commission calculation batch tested against multi-tier commission plans"). It is deliberately a **pure function over already-loaded data** — no repositories, no `TenantContext`, no Spring — so the whole multi-tier matrix is unit-testable without a database.

- [ ] **Step 1: Write the calculator**

```java
/**
 * THE ENTIRE ALGORITHM BELOW IS AN INVENTED PLACEHOLDER, flagged rather than guessed silently --
 * the same treatment M2 gave the underwriting decision engine and M4 gave premium rates.
 *
 * <p>Di1 (docs/03-aggregate-design.md:148-154) specifies CommissionPlan/CommissionRule's SHAPE
 * (five tier types, getApplicablePlan(), "walks the plan's rules against actual production") and
 * NO calculation semantics at all. No Phase 0 document defines tier precedence, what "actual
 * production" measures, how far a hierarchy override walks, or what happens on lapse -- the word
 * "clawback" appears in no doc and no file on this platform. Every rule encoded here is this
 * plan's own decision and needs Actuarial/Distribution sign-off:
 *
 * <ul>
 *   <li><b>"Actual production" = PREMIUM, not sum assured.</b> It is the only money figure both
 *       policy.PolicyIssued and a premium collection carry, and commission-on-premium is the
 *       ordinary life convention.</li>
 *   <li><b>Tier selection is by TRIGGER, not precedence.</b> Issuance fires FIRST_YEAR; a
 *       non-first premium collection fires RENEWAL. Exactly one direct-agent rule per event, no
 *       stacking. A first-invoice collection fires nothing, or it would double-pay against
 *       FIRST_YEAR.</li>
 *   <li><b>Hierarchy walks at most 2 levels, depth-capped and cycle-safe.</b> OVERRIDE credits
 *       the seller's parent, SUPERVISOR_OVERRIDE the parent's parent.
 *       agent_profile.hierarchy_parent_id is a self-FK with NO cycle constraint in the DDL, so a
 *       cycle in the data must terminate rather than hang or accrue forever.</li>
 *   <li><b>Each ancestor is paid from ITS OWN plan.</b> An ancestor with no plan, or no rule of
 *       the relevant tier, earns nothing -- silently and correctly.</li>
 *   <li><b>THRESHOLD_BONUS is not computed</b> (M7 user decision 1): commission_rule
 *       .threshold_condition is an untyped JSONB whose schema is defined nowhere, so evaluating it
 *       would mean inventing a format with no business input. Rules of that tier are skipped here
 *       and rejected at authoring time.</li>
 * </ul>
 */
public final class CommissionCalculator {

    /** Hard cap on the hierarchy walk. Two levels is all the tier vocabulary needs (OVERRIDE,
     * SUPERVISOR_OVERRIDE), and a cap is mandatory because the DDL permits a cycle. */
    static final int MAX_HIERARCHY_WALK_DEPTH = 2;

    private CommissionCalculator() {}

    /** One accrual the caller should persist. */
    public record Accrual(UUID agentId, TierType tierType, BigDecimal amount, String currency) {}

    /** An agent plus the plan and rules that apply to it, already loaded by the caller. */
    public record AgentWithPlan(AgentProfile agent, CommissionPlan plan, List<CommissionRule> rules) {}

    /**
     * @param seller        the agent of record, already loaded
     * @param ancestors     the seller's hierarchy chain, nearest first, already walked and
     *                      cycle-checked by the caller (see resolveAncestors)
     * @param directTier    FIRST_YEAR for an issuance, RENEWAL for a non-first premium collection
     * @param premium       the premium amount the event carried
     * @param currency      that premium's currency
     */
    public static List<Accrual> calculate(AgentWithPlan seller, List<AgentWithPlan> ancestors,
                                           TierType directTier, BigDecimal premium, String currency) {
        List<Accrual> accruals = new ArrayList<>();
        applicableAmount(seller, directTier, premium, currency)
            .ifPresent(amount -> accruals.add(new Accrual(seller.agent().getAgentId(), directTier, amount, currency)));

        // Override tiers apply only to an issuance-driven accrual. A renewal does not re-pay the
        // hierarchy -- an invented rule, and one of the most likely to be corrected.
        if (directTier == TierType.FIRST_YEAR) {
            TierType[] overrideTiers = { TierType.OVERRIDE, TierType.SUPERVISOR_OVERRIDE };
            for (int level = 0; level < ancestors.size() && level < MAX_HIERARCHY_WALK_DEPTH; level++) {
                AgentWithPlan ancestor = ancestors.get(level);
                TierType tier = overrideTiers[level];
                applicableAmount(ancestor, tier, premium, currency)
                    .ifPresent(amount -> accruals.add(
                        new Accrual(ancestor.agent().getAgentId(), tier, amount, currency)));
            }
        }
        return accruals;
    }

    /** Empty when the agent cannot accrue, has no plan, or the plan has no rule of this tier --
     * all three are normal, not errors. */
    private static Optional<BigDecimal> applicableAmount(AgentWithPlan candidate, TierType tier,
                                                          BigDecimal premium, String currency) {
        if (candidate == null || candidate.agent() == null || !candidate.agent().canAccrueCommission()) {
            return Optional.empty();
        }
        if (candidate.plan() == null || candidate.rules() == null) {
            return Optional.empty();
        }
        return candidate.rules().stream()
            .filter(r -> r.getTierType() == tier)
            .filter(r -> r.getTierType() != TierType.THRESHOLD_BONUS) // never computed -- see class javadoc
            .findFirst()
            .flatMap(rule -> amountFor(rule, premium, currency));
    }

    /** A rule is rate-based or flat, never both and never neither (V2's XOR CHECK). A flat rule in
     * a different currency is skipped rather than converted -- no FX table exists anywhere on this
     * platform, and inventing a rate would be worse than not paying. */
    private static Optional<BigDecimal> amountFor(CommissionRule rule, BigDecimal premium, String currency) {
        if (rule.getRate() != null) {
            return Optional.of(premium.multiply(rule.getRate())
                .setScale(2, java.math.RoundingMode.HALF_UP));
        }
        if (rule.getFlatAmount() != null && currency.equals(rule.getFlatCurrency())) {
            return Optional.of(rule.getFlatAmount());
        }
        return Optional.empty();
    }

    /** Walks hierarchy_parent_id, nearest ancestor first, stopping at MAX_HIERARCHY_WALK_DEPTH or
     * on a repeated agentId. The cycle check is not defensive padding: the DDL's self-FK has no
     * constraint preventing A -> B -> A, and without this the walk would not terminate. */
    public static List<UUID> resolveAncestorIds(UUID sellerId, java.util.function.Function<UUID, UUID> parentOf) {
        List<UUID> chain = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        seen.add(sellerId);
        UUID current = parentOf.apply(sellerId);
        while (current != null && chain.size() < MAX_HIERARCHY_WALK_DEPTH && seen.add(current)) {
            chain.add(current);
            current = parentOf.apply(current);
        }
        return chain;
    }
}
```

- [ ] **Step 2: Write `CommissionCalculatorTest` — this is the acceptance criterion's evidence**

A plain unit test (no Spring, no containers). It must cover, concretely:

- **A rate-based FIRST_YEAR accrual** computes `premium * rate` rounded HALF_UP to 2dp, asserted on an exact expected `BigDecimal` (use `isEqualByComparingTo`).
- **A flat-amount FIRST_YEAR accrual** pays the flat amount, and a flat rule in a **different currency** pays nothing rather than converting.
- **A multi-tier plan**: a seller with a parent and a grandparent, all three with their own plans and rules, produces exactly three accruals — FIRST_YEAR to the seller, OVERRIDE to the parent, SUPERVISOR_OVERRIDE to the grandparent — with the right amount and agent on each.
- **A RENEWAL trigger fires no override tiers** (only one accrual, to the seller).
- **An ancestor with no plan, and an ancestor whose plan lacks the relevant tier, each earn nothing** while the seller still accrues — the partial-hierarchy case.
- **A SUSPENDED or EXPIRED agent accrues nothing**, at both seller and ancestor position.
- **A `THRESHOLD_BONUS` rule is skipped** even when it is the only rule of the plan.
- **`resolveAncestorIds` terminates on a cycle** (A→B→A) and returns at most `MAX_HIERARCHY_WALK_DEPTH`, asserted for a 5-deep chain too.

- [ ] **Step 3: Run and commit**

```bash
# Targeted only -- a pure function plus its own unit test, nothing shared touched. Note this test
# needs no container at all, so it should run in seconds; if it takes minutes something is wrong
# with how it was written (it must not pull in a Spring context).
./mvnw -B -o test -Dtest=CommissionCalculatorTest
git add -A && git commit -m "feat: the four-tier commission calculator, as a pure testable function"
```

---

### Task 5: `DistributionApi` — onboarding, plan authoring, and reads

**Files:**
- Create: `distribution/api/DistributionApi.java`, `AgentView.java`, `CommissionPlanView.java`, `CommissionRuleView.java`, `CommissionStatementView.java`, `CommissionAccrualView.java`
- Create: `distribution/application/DistributionApiImpl.java`
- Test: `src/test/java/.../distribution/DistributionApiIntegrationTest.java`

**Interfaces:**
- Consumes: `PartyApi.getParty`, `ProductApi.getSnapshotByVersionId`/`getActiveSnapshot`, `ReferenceDataApi.getValue`, Task 3's repositories.
- Produces: the surface Task 9 exposes over HTTP and Task 10 contract-tests. `omnichannel` already declares a dependency on `distribution::api`.

- [ ] **Step 1: Define `DistributionApi`**

```java
public interface DistributionApi {

    record OnboardAgentRequest(UUID partyId, String licenseNumber, LocalDate licenseExpiryDate,
                                UUID hierarchyParentId) {}
    record CommissionRuleInput(TierType tierType, BigDecimal rate,
                                BigDecimal flatAmount, String flatCurrency) {}

    AgentView onboardAgent(OnboardAgentRequest request, String onboardedBy);
    AgentView getAgent(UUID agentId);

    CommissionPlanView createCommissionPlan(UUID productId, List<CommissionRuleInput> rules, String createdBy);
    CommissionPlanView getApplicablePlan(UUID agentId, UUID productId);

    List<CommissionStatementView> listStatements(UUID agentId, String period);
    List<CommissionAccrualView> listAccruals(UUID statementId);

    /** Staff-triggered payout for a CLOSED (or previously PAYOUT_FAILED) statement. Publishes
     * distribution.CommissionPayoutRequested; settlement completes asynchronously. */
    void requestStatementPayout(UUID statementId, String payeeRef, String idempotencyKey, String requestedBy);
}
```

- [ ] **Step 2: Implement `onboardAgent`**

Validation order matters — each failure has a distinct exception so Task 9 can map distinct statuses:

```java
        // 1. Party must exist AND be KYC VERIFIED. openapi-distribution.yaml declares a 422 for
        //    exactly this ("Referenced party does not have KYC status VERIFIED").
        //    PartyNotFoundException propagates as-is (404 at the boundary).
        PartyView party = partyApi.getParty(request.partyId());
        if (party.kycStatus() != KycStatus.VERIFIED) {
            throw new DistributionValidationException("Party " + request.partyId()
                + " has KYC status " + party.kycStatus() + "; an agent must be VERIFIED to onboard");
        }

        // 2. A hierarchy parent, if given, must exist in THIS tenant. Not optional rigour: an
        //    unchecked parent id would silently create an orphan branch that the override walk
        //    then cannot resolve, and the FK alone would not catch a cross-tenant id because RLS
        //    hides the row rather than rejecting the reference.
        if (request.hierarchyParentId() != null) {
            agentProfileRepository.findByAgentIdAndTenantId(request.hierarchyParentId(), tenantId)
                .orElseThrow(() -> new DistributionValidationException(
                    "Hierarchy parent " + request.hierarchyParentId() + " does not exist in this tenant"));
        }

        // 3. A licence expiring in the past is a data-entry error, not a valid onboarding.
        if (!request.licenseExpiryDate().isAfter(LocalDate.now())) {
            throw new DistributionValidationException(
                "License expiry " + request.licenseExpiryDate() + " is not in the future");
        }
```

Then save and publish `distribution.AgentOnboarded` matching `asyncapi-events.yaml`'s existing `AgentOnboardedPayload` field-for-field: `Map.of("agentId", ..., "partyId", ..., "licenseNumber", ...)`.

The unique index `ux_agent_license (tenant_id, license_number)` means a duplicate licence throws `DataIntegrityViolationException` — catch it and rethrow as `DistributionValidationException` so the boundary maps a 422, not a 500.

- [ ] **Step 3: Implement `createCommissionPlan` and `getApplicablePlan`**

`createCommissionPlan` validates: the product exists (`ProductApi.getActiveSnapshot`, letting `ProductNotFoundException` propagate); at least one rule; **no `THRESHOLD_BONUS` rule** (reject with the user-decision reason); and each rule is rate-XOR-flat with a currency paired to a flat amount, so the domain rejects before the DB CHECK does.

`getApplicablePlan(agentId, productId)` is Di1's named method. Resolve the agent, then its `commissionPlanId`; if the agent has no plan, fall back to the tenant's `ACTIVE` plan for that `productId`; if neither exists throw `CommissionPlanNotFoundException`. Document that precedence — it is invented.

- [ ] **Step 4: Implement `requestStatementPayout`**

Validates non-blank `payeeRef` and `idempotencyKey` **before** publishing (a blank key forwarded to `payment` is swallowed inside its `AFTER_COMMIT` listener and looks like a request that reached the rail zero times), calls `statement.markPayoutRequested(...)`, then publishes `distribution.CommissionPayoutRequested` in the same transaction:

```java
        eventPublisher.publishEvent(DomainEventEnvelope.of("distribution.CommissionPayoutRequested", tenantId,
            Map.of("statementId", statementId,
                   "payeeRef", payeeRef,
                   "amount", Map.of("amount", statement.getTotalAmount().toPlainString(),
                                    "currencyCode", statement.getTotalCurrency()),
                   "idempotencyKey", idempotencyKey)));
```

Field names match `asyncapi-events.yaml`'s existing `CommissionPayoutRequestedPayload` exactly. Note it carries `statementId`, not `sourceRef` — `payment` derives `sourceRef = statementId.toString()` itself (Task 8).

- [ ] **Step 5: Write `DistributionApiIntegrationTest`**

Testcontainers, running as **real `app_role`** (`NOSUPERUSER NOBYPASSRLS`) — copy the `@DynamicPropertySource` + `ALTER ROLE` setup from `claims/ClaimsApiIntegrationTest`, the freshest correct example. Apply migrations for `audit`, `refdata` (V1-V4), `party`, `product`, and `distribution` (V1+V2) — check each directory for the real current file list rather than assuming.

Cover: successful onboarding; rejection when KYC is not VERIFIED; rejection for a nonexistent/cross-tenant hierarchy parent; rejection for a past licence expiry; a duplicate licence number surfacing as a validation failure not a 500; plan creation rejecting `THRESHOLD_BONUS`; `getApplicablePlan` resolving the agent's own plan and falling back to the product's ACTIVE plan; and a cross-tenant `getAgent` returning not-found (proving tenant scoping under real RLS).

- [ ] **Step 6: Run and commit**

```bash
# Targeted only -- new application-layer code inside `distribution` plus its own integration test.
./mvnw -B -o test -Dtest=DistributionApiIntegrationTest
git add -A && git commit -m "feat: agent onboarding, commission-plan authoring, and the distribution read surface"
```

---

### Task 6: Accrue on issuance, and claw back on early lapse

**Files:**
- Create: `distribution/application/PolicyEventListener.java`
- Modify: `distribution/application/DistributionApiImpl.java` (an internal accrual method), `observability/alert-rules.yml`, `src/test/java/.../AlertRuleMetricProducerTest.java`
- Modify: `api/asyncapi-events.yaml`
- Test: `src/test/java/.../distribution/CommissionAccrualEndToEndTest.java`, `ClawbackIntegrationTest.java`

**Interfaces:**
- Consumes: `policy.PolicyIssued`, `policy.PolicyLapsed`, Task 4's calculator.
- Produces: `commission_accrual` rows, `policy_projection` rows, `distribution.CommissionAccrued`.

- [ ] **Step 1: Write the listener**

`@Component("distributionPolicyEventListener")` — `billing` already has a `PolicyEventListener`. Copy `claims/application/PaymentEventListener.java`'s exact mechanics: `@TransactionalEventListener(AFTER_COMMIT)`, one reusable `PROPAGATION_REQUIRES_NEW` `TransactionTemplate`, `TenantContext` save/set/restore.

`handlePolicyIssued` must:
1. **Write the projection row first**, unconditionally — even when `agentOfRecordId` is null. The projection is how clawback and renewal attribution work later, and a direct-sold policy still needs its premium and issue date recorded.
2. Read `agentOfRecordId` as a **nullable** `UUID`. `policy.PolicyIssued` uses a `LinkedHashMap` precisely because this field can be null; a null means the policy was sold direct, so **log at INFO and return** — no agent, no commission, not an error.
3. Resolve the seller's plan and rules, walk ancestors via `CommissionCalculator.resolveAncestorIds` (passing a `parentOf` lambda backed by the repository), load each ancestor's own plan, call `calculate(...)` with `FIRST_YEAR`.
4. Persist each accrual into the agent's **open** statement for the period and currency, creating the statement if absent, then `recomputeTotal`. Use `existsByTenantIdAndAgentIdAndTierTypeAndSourceRefAndReversesAccrualIdIsNull` to skip a redelivered event rather than double-accruing — and rely on `ux_commission_accrual_once` as the real backstop, catching `DataIntegrityViolationException` as the idempotent path.
5. Publish `distribution.CommissionAccrued` per accrual, matching the existing `CommissionAccruedPayload` (`statementId`, `agentId`, `policyNumber`, `amount`).

`handlePolicyLapsed` (the clawback path) must:
1. Look up the projection by `policyNumber`. **No row means a pre-M7 policy** — log at INFO and return, never throw.
2. Read `TZ_COMMISSION_CLAWBACK_MONTHS` via `ReferenceDataApi.getValue(...)`, parse it, and compare `lapsedAt` against `issueDate + months`. Outside the window: nothing to do.
3. Inside the window: find unreversed `FIRST_YEAR` accruals for that policy. For each whose statement is **not** `PAID`, insert a reversing accrual (`amount` negated, `reverses_accrual_id` set, `source_ref` = the reversed accrual's id) into the **currently open** period's statement, and `recomputeTotal` on both affected statements.
4. **Never alter a `PAID` statement.** A reversal against an already-paid accrual still books into the current open period — that is the whole reason reversals are separate rows rather than updates.
5. Increment `lifeplatform_distribution_clawback_total` and log at INFO with the policy, agent and amount.

Document the `IN_DOUBT` boundary in the class javadoc, as `claims` does.

- [ ] **Step 2: Alert rule + producer classification**

Add rules to `observability/alert-rules.yml` for `lifeplatform_distribution_clawback_total` (informational — a spike means many early lapses, which is a distribution-quality signal, not an error) and for any failure counter this task introduces. Follow the `PaymentOutcomeInDoubt` rule's style including its comment block. Then add every new metric name to `AlertRuleMetricProducerTest`'s `PRODUCED_BY_THIS_APPLICATION` — **that test fails otherwise**, by design.

- [ ] **Step 3: AsyncAPI — declare distribution as a consumer, and fix a real under-declaration**

`api/asyncapi-events.yaml` currently does **not** list `distribution` as a consumer of `policy.PolicyLapsed`. Add it to that channel's description. Also: `policy.PolicyIssued`'s code publishes `premium` and `premiumFrequency`, but `PolicyIssuedPayload` declares **neither** — and this task depends on `premium`. Add both fields (additive, no `schemaVersion` bump). This is the same drift class two prior milestones already corrected.

- [ ] **Step 4: Write the two integration tests**

Both Testcontainers, real `app_role`, real cross-module event chain (issue a policy through `PolicyApi` so the real `PolicyIssued` fires).

`CommissionAccrualEndToEndTest`: issuing a policy with an agent of record creates the projection row, the seller's `FIRST_YEAR` accrual and both ancestors' override accruals, with a real statement whose `total_amount` equals the sum — read back **from the database**, not from an event. Then assert a **redelivered** `PolicyIssued` creates no second accrual and does not change the total. Then assert a policy issued with a **null** `agentOfRecordId` creates a projection row and zero accruals.

`ClawbackIntegrationTest`: a policy lapsing **inside** the window reverses the first-year accrual into the current open period, leaving the original row intact; a policy lapsing **outside** the window changes nothing; a lapse against an accrual whose statement is already `PAID` books the reversal in the open period and leaves the `PAID` statement's total untouched; and a lapse for a policy with **no projection row** is a silent no-op.

- [ ] **Step 5: Run and commit**

```bash
# Targeted, PLUS AlertRuleMetricProducerTest -- this task adds metric names to that test's own
# PRODUCED_BY_THIS_APPLICATION set, and it fails by design if a new alert rule names an
# unclassified metric. It runs in milliseconds (no Spring, no container), so there is no reason
# to skip it. Still short of a full-suite trigger: the only shared file touched is that
# classification list, not production code outside `distribution`.
./mvnw -B -o test -Dtest='CommissionAccrualEndToEndTest,ClawbackIntegrationTest,AlertRuleMetricProducerTest'
git add -A && git commit -m "feat: accrue commission on issuance and claw it back on an early lapse"
```

---

### Task 7: `billing.PremiumCollected`, and RENEWAL accrual

**Files:**
- Modify: `billing/api/BillingApi.java`, `billing/application/BillingApiImpl.java`
- Create: `distribution/application/PremiumEventListener.java`
- Modify: `api/asyncapi-events.yaml`
- Test: `src/test/java/.../billing/BillingApiIntegrationTest.java` (extend), `distribution/CommissionAccrualEndToEndTest.java` (extend)

**Interfaces:**
- Consumes: `billing.PremiumCollected` (new).
- Produces: `RENEWAL`-tier accruals.

- [ ] **Step 1: Publish `billing.PremiumCollected`**

`BillingApiImpl.applyConfirmedPayment` (`:119-130`) records a premium as paid and publishes **nothing at all** — a standalone gap this task closes, and one `finaccounting` (M9) will need too. Add, inside the existing transaction, after `premiumInvoiceRepository.save(invoice)`:

```java
        eventPublisher.publishEvent(DomainEventEnvelope.of("billing.PremiumCollected", tenantId,
            Map.of("invoiceId", invoiceId,
                   "policyNumber", invoice.getPolicyNumber(),
                   "amount", Map.of("amount", amount.toPlainString(), "currencyCode", currency),
                   "collectedAt", Instant.now().toString())));
```

`policyNumber` is the field that makes this event useful — `payment.PaymentConfirmed` carries only `sourceRef = invoiceId`, which is why the two-hop alternative was rejected. Declare the channel and a `PremiumCollectedPayload` in `api/asyncapi-events.yaml`, naming `distribution` and `finaccounting` as consumers.

- [ ] **Step 2: Write `PremiumEventListener`**

`@Component("distributionPremiumEventListener")`. Same `AFTER_COMMIT` + `REQUIRES_NEW` mechanics.

The **first-invoice guard is the load-bearing logic** here. On the first `PremiumCollected` for a policy, record it and accrue **nothing** — the issuance already paid `FIRST_YEAR`, so accruing `RENEWAL` too would double-pay. On any subsequent collection, accrue `RENEWAL` for the seller only (no override tiers — Task 4's documented rule). No projection row means a pre-M7 policy: log at INFO, return.

Use `sourceRef = invoiceId.toString()` so `ux_commission_accrual_once` makes a redelivered collection idempotent.

**Amended during implementation — the guard stores `first_invoice_id UUID`, not a `first_invoice_collected` boolean.** As originally specified this task had a hole: once the boolean flipped, a *redelivered* `PremiumCollected` for that same first invoice would read as "not the first any more" and accrue exactly the RENEWAL the guard exists to prevent. `sourceRef` dedup cannot cover it, because the first collection deliberately writes no accrual row to collide with. Storing the identity makes the guard idempotent (null = none yet, equal = redelivery, otherwise a genuine renewal). `distribution/V2` is this branch's own unreleased migration and is not on the immutable list, so the column was amended in place. Proven with a negative control: reverting `isFirstCollection` to boolean semantics fails `aRedeliveredFirstCollectionStillAccruesNoRenewal` and only that test.

- [ ] **Step 3: Extend the tests**

In `BillingApiIntegrationTest`: `applyConfirmedPayment` publishes exactly one `billing.PremiumCollected` carrying the right `policyNumber` and amount.

In `CommissionAccrualEndToEndTest`: the **first** collected invoice for a policy accrues nothing and flips `first_invoice_collected`; the **second** accrues exactly one `RENEWAL` for the seller and **no** override accruals; a redelivered collection for the same invoice accrues nothing further.

- [ ] **Step 4: Run and commit**

```bash
# FULL SUITE REQUIRED (Global Constraints trigger 1): this task changes production code in
# `billing`, a module with its own listeners, sweep and contract tests. Publishing a new event
# from inside applyConfirmedPayment's existing transaction can affect anything that consumes
# billing's events or asserts on its published-event counts -- exactly what a targeted run would
# miss. Expect 392 + your new tests.
./mvnw -B -o test
git add -A && git commit -m "feat: publish billing.PremiumCollected and accrue renewal commission from it"
```

---

### Task 8: Close the payout loop — `payment`'s branch, and the confirmation listener

**Files:**
- Modify: `payment/application/PaymentRequestListener.java`
- Create: `distribution/application/PaymentEventListener.java`
- Modify: `api/asyncapi-events.yaml`, `observability/alert-rules.yml`, `AlertRuleMetricProducerTest.java`
- Test: `src/test/java/.../distribution/CommissionPayoutEndToEndTest.java`

**Interfaces:**
- Consumes: `distribution.CommissionPayoutRequested` (in `payment`), `payment.DisbursementCompleted`/`DisbursementFailed` (in `distribution`).
- Produces: a real `disbursement_instruction` with `purpose = 'COMMISSION_PAYOUT'`, and `distribution.CommissionPaid`.

- [ ] **Step 1: Add `payment`'s one reserved case**

`PaymentRequestListener.java`'s switch has a comment reserving this exact spot. Add:

```java
            case "distribution.CommissionPayoutRequested" -> withTenant(envelope, this::handleCommissionPayout);
```

and delete only the now-stale part of that comment. Then a handler that is nearly line-for-line `handleClaimSettlement` (read it first), with `statementId` in place of `claimId`, `"COMMISSION_PAYOUT"` as the purpose, and `statementId.toString()` as the `sourceRef`. Cast `statementId` as `(UUID) payload.get("statementId")` — payloads carry real `UUID` objects, matching the existing `claimId`/`loanId` pattern. No `payeeRef` null-guard is needed: Task 5 rejects a blank one before publishing.

**No `payment` migration** — `COMMISSION_PAYOUT` is already in `disbursement_instruction.purpose`'s CHECK.

- [ ] **Step 2: Write distribution's `PaymentEventListener`**

`@Component("distributionPaymentEventListener")` — three other modules already have this class name.

Filter on `purpose == "COMMISSION_PAYOUT"` in **both** handlers, or distribution will process claims' and loans' payouts as commissions.

`handleCompleted`: resolve the statement from `sourceRef`, capture `wasAlreadyPaid` **before** `statement.markPaid(...)`, and publish `distribution.CommissionPaid` (`statementId`, `paidAt`) **only when the transition actually happened** — a redelivered event must not emit a second event. This is M6's I1 finding applied preemptively.

`handleFailed`: `statement.markPayoutFailed(reason)`, increment `lifeplatform_distribution_payout_failed_total`, log at ERROR naming the statement. Read only fields `DisbursementFailed` actually carries — it has **no** `gatewayReference`, `amount` or timestamp.

Document in the class javadoc that an `IN_DOUBT` rail outcome publishes nothing, so a statement can rest at `PAYOUT_REQUESTED` indefinitely; `payment` already alerts on it.

- [ ] **Step 3: Alert rules + classification**

Add a rule for `lifeplatform_distribution_payout_failed_total` and register every new metric in `AlertRuleMetricProducerTest`'s produced set.

- [ ] **Step 4: Write `CommissionPayoutEndToEndTest`**

Testcontainers + real `app_role` + WireMock for the rail — copy `claims/ClaimSettlementEndToEndTest`'s harness. Assert:
1. Requesting payout for a `CLOSED` statement creates a real `payment.disbursement_instruction` with `purpose='COMMISSION_PAYOUT'` and `source_ref = statementId`, moves the statement to `PAYOUT_REQUESTED`, and calls the rail **exactly once**.
2. A successful rail response drives the statement to `PAID`, publishes exactly one `CommissionPaid`, and a **redelivered** `DisbursementCompleted` publishes no second event.
3. A rail decline leaves the statement `PAYOUT_FAILED` with the reason recorded, and a retry with a **new** idempotency key reaches the rail again and can reach `PAID`.
4. Re-publishing the same `CommissionPayoutRequested` with the **same** key reaches the rail **zero** additional times.
5. A `DisbursementCompleted` carrying `purpose='LOAN_DISBURSEMENT'` leaves every statement untouched — the cross-contamination guard M6's review found missing on the claims side.

- [ ] **Step 5: Run and commit**

```bash
# FULL SUITE REQUIRED (Global Constraints trigger 1): this task changes production code in
# `payment` -- specifically PaymentRequestListener, the single switch every money movement on this
# platform routes through. `policyloan`, `billing` and `claims` all depend on its behaviour, and
# its history on this project (5 Critical + 6 Important findings across 3 fix rounds in M5) is the
# reason a targeted run is not good enough here.
./mvnw -B -o test -Dtest=CommissionPayoutEndToEndTest
./mvnw -B -o test
git add -A && git commit -m "feat: wire payment's reserved commission branch and close the payout loop"
```

---

### Task 9: The monthly close sweep, REST layer, and the OpenAPI contract

**Files:**
- Create: `db-migrations/_post-migration/configure-commission-close.sql`
- Create: `distribution/infrastructure/AgentController.java`, `CommissionPlanController.java`, `DistributionExceptionHandler.java`, and request/response DTOs
- Modify: `api/openapi/openapi-distribution.yaml`
- Test: `src/test/java/.../distribution/CommissionCloseSweepPsqlTest.java`

**Interfaces:**
- Produces: the HTTP surface Task 10 contract-tests, and the pg_cron close.

- [ ] **Step 1: The pg_cron statement close**

Copy `db-migrations/_post-migration/configure-billing-sweep.sql`'s structure and its header comment's reasoning verbatim in form. A `SECURITY DEFINER` function `distribution.close_commission_statements()` that, **cross-tenant**, transitions every `OPEN` statement whose `period` is strictly before the current `YYYY-MM` to `CLOSED`, setting `closed_at`. Schedule it with `cron.schedule('commission-close', ...)` — daily is sufficient (a month boundary is crossed once), and running it repeatedly must be a no-op, which the `OPEN`-only predicate guarantees.

The header must state, as billing's does: why this is pg_cron and not `@Scheduled` (a Java thread has no `TenantContext`, so RLS shows it zero rows); that `app_role` is **never** granted `EXECUTE` on it; and that this is infrastructure automation, not a request-path privilege escalation.

**Do not** trigger payouts from SQL. Closing is a state transition; requesting payout needs a `payeeRef` that only a human can supply (`PartyView` exposes no MSISDN), so it stays the staff-triggered `requestStatementPayout`.

- [ ] **Step 2: Controllers with the documented role gates**

`docs/04-api-contracts.md:57`'s matrix row is `| distribution | — | read own commission/plan | onboard, administer | — |`, and there is **no** `AGENCY_MANAGER` role in the `staff` realm — the available roles are `UNDERWRITER`, `CLAIMS_ASSESSOR`, `CLAIMS_MANAGER`, `FINANCE_OFFICER`, `CUSTOMER_SERVICE_REP`, `ADMIN`. Use `FINANCE_OFFICER` or `ADMIN` for onboarding and plan authoring, and record that choice in a comment as a decision, since the spec's bare `staffAuth: []` constrains nothing.

| Endpoint | Gate |
|---|---|
| `POST /agents` | `REALM_STAFF` with `FINANCE_OFFICER` or `ADMIN` |
| `GET /agents/{agentId}` | `REALM_AGENTS` (own or within hierarchy) or `REALM_STAFF` |
| `GET /agents/{agentId}/commission-plan` | same |
| `GET /agents/{agentId}/commission-statements` | same |
| `POST /commission-plans` (new) | `REALM_STAFF` with `FINANCE_OFFICER` or `ADMIN` |
| `POST /agents/{agentId}/commission-statements/{statementId}/payout` (new) | `REALM_STAFF` with `FINANCE_OFFICER` or `ADMIN` |

**The agent object-level check is mandatory and must be real** (`docs/04-api-contracts.md:41-43`): an `agents`-realm token may read its own agent record, "or within their agency hierarchy for supervisors". Implement both: an agent whose `party_id` claim resolves to a different `agentId` gets 403 — unless the requested agent is a descendant and the caller holds the `supervisor` scope. Reuse `CommissionCalculator.resolveAncestorIds`' cycle-safe walk shape for the descendant check, depth-capped. A cross-tenant read must be 404, not 403 (leaking existence across tenants is its own issue) — the same distinction `PolicyContractTest` already pins for policy.

- [ ] **Step 3: Exception handler**

`@Order(Ordered.HIGHEST_PRECEDENCE)` — every `*ExceptionHandler` on this platform uses it, and two unordered advices shadowing each other by scan order was a real M1 bug. Include `traceId` in every `ProblemDetail` (`openapi-common.yaml`'s `ProblemDetails` marks it required; an M6 contract test caught a filter omitting it).

| Exception | Status | errorCode |
|---|---|---|
| `AgentNotFoundException` | 404 | `AGENT_NOT_FOUND` |
| `CommissionPlanNotFoundException` | 404 | `COMMISSION_PLAN_NOT_FOUND` |
| `DistributionValidationException` | 422 | `DISTRIBUTION_VALIDATION_FAILED` |
| `InvalidAgentStateException` | 409 | `DISTRIBUTION_INVALID_STATE` |

Verify what `PartyNotFoundException` and `ProductNotFoundException` already map to and report it; do **not** add a duplicate handler for them.

- [ ] **Step 4: Update the OpenAPI spec**

`openapi-distribution.yaml` is `0.1.0-draft` and materially incomplete. Add: `POST /commission-plans` and the payout endpoint; `Idempotency-Key` as a **required** header on both write endpoints that move money or create identity (payout certainly; onboarding for safe retry); missing status codes `400`/`401`/`403`/`404`/`409` where each applies (`GET /agents/{agentId}` currently declares only `200`); `required` lists on every schema; `CommissionAccrualView`; and a `period` filter on statements.

**Change `CommissionPlanView.rules[].rate` from `type: number` to a decimal string** with pattern, matching `Money.amount`'s convention — `docs/06:31` says never a float "anywhere in the stack, wire format or storage", and M6 fixed this exact class of bug for `impairmentPercent`. Serialize the Java side accordingly (`@JsonFormat(shape = STRING)` on a `BigDecimal`, or a `String` field).

**Quote every description containing a comma** — an unquoted flow-style YAML description broke an entire contract test's spec load in M4.

- [ ] **Step 5: Write `CommissionCloseSweepPsqlTest`**

Mirror `billing/BillingSweepPsqlTest`'s approach: apply the migrations plus the post-migration file against a real container, seed `OPEN` statements across **two tenants** and two periods, call `distribution.close_commission_statements()`, and assert only the past-period statements in **both** tenants moved to `CLOSED` (proving the function genuinely is cross-tenant, which is the whole reason it is SQL) and that the current period's stayed `OPEN`. Then call it a second time and assert nothing changes.

- [ ] **Step 6: Run and commit**

```bash
# Targeted. All new code is inside `distribution` (controllers, DTOs, exception handler) plus a new
# post-migration SQL file and the distribution OpenAPI spec -- nothing shared, no other module's
# production code. Run your own new tests and the psql sweep test:
./mvnw -B -o test -Dtest='CommissionCloseSweepPsqlTest,DistributionApiIntegrationTest'
git add -A && git commit -m "feat: the monthly close sweep, distribution REST layer, and contract completion"
```

---

### Task 10: Contract tests, guardrail coverage, and doc reconciliation

**Files:**
- Test: `src/test/java/.../distribution/DistributionContractTest.java`
- Modify: `src/test/java/.../AppRolePrivilegesIntegrationTest.java`, `RowLevelSecurityIntegrationTest.java`
- Modify: `docs/06-database-schema.md`

- [ ] **Step 1: Write `DistributionContractTest`**

Follow `ClaimsContractTest`'s structure (the freshest example): `@Testcontainers` + `@AutoConfigureMockMvc` + `@SpringBootTest` + `SecurityMockMvcRequestPostProcessors.jwt()` + `openApi().isValid("api/openapi/openapi-distribution.yaml")` **paired with** `SpecTypeConformance.matchesDeclaredTypes(...)` on every response carrying a decimal — `isValid` does not enforce primitive types.

One test per genuinely reachable status:
- `POST /agents`: 201; 422 for un-VERIFIED KYC; 422 for a duplicate licence; 403 for an agent token; 400 for a missing `Idempotency-Key`.
- `GET /agents/{agentId}`: 200 for staff; 200 for the agent itself; **403 for a different agent in the same tenant**; **200 for a supervisor reading a descendant**; **403 for a supervisor reading a non-descendant**; 404 for an unknown id; 404 cross-tenant.
- `GET /agents/{agentId}/commission-plan`: 200 with the rules array; 404 when no plan applies.
- `GET /agents/{agentId}/commission-statements`: 200, and the `period` filter genuinely narrowing.
- `POST /commission-plans`: 201; 422 for a `THRESHOLD_BONUS` rule; 422 for a rule with both rate and flat amount; 403 for a non-finance staff role.
- Payout endpoint: 202; 409 for an `OPEN` (not yet closed) statement; 400 for a missing `Idempotency-Key`.

Make every 403 non-vacuous by seeding a **real, valid** agent under the correct tenant, so a broken `@PreAuthorize` returns 200 rather than an incidental 404. Where a test sends a deliberately schema-invalid body, omit the `isValid` matcher and assert only status + `errorCode`, with a comment — `isValid` validates requests too.

- [ ] **Step 2: Close the two guardrail gaps**

`distribution` appears in **neither** `AppRolePrivilegesIntegrationTest` nor `RowLevelSecurityIntegrationTest`. Add it to both, exactly as M6 had to for `claims`:
- `AppRolePrivilegesIntegrationTest`: add `distribution/V1`+`V2` to the migration list and a test proving `app_role` can insert, read back and update a `distribution.commission_statement` row through the app's own DataSource (the UPDATE matters — the statement lifecycle mutates).
- `RowLevelSecurityIntegrationTest`: add distribution's migrations and assert cross-tenant invisibility on `commission_statement` and `commission_accrual`.

- [ ] **Step 3: Reconcile the docs**

`docs/06-database-schema.md:27`'s optimistic-locking list must gain `distribution.commission_plan` and `distribution.commission_statement` (Task 1 added `version` to both). Check whether the WORM list needs anything — it does **not**: no distribution table is append-only, and `commission_statement.status` is deliberately mutable, so **do not** add a `REVOKE`.

- [ ] **Step 4: Run and commit**

```bash
# FULL SUITE REQUIRED (Global Constraints trigger 2): this task edits
# AppRolePrivilegesIntegrationTest and RowLevelSecurityIntegrationTest, two shared guard classes
# that every module's tenant-isolation and runtime-privilege correctness leans on. Adding
# migrations to their lists changes what they assert for EVERY schema, not just distribution's --
# a targeted run cannot tell you whether you broke another module's coverage.
./mvnw -B -o test -Dtest=DistributionContractTest
./mvnw -B -o test
git add -A && git commit -m "test: distribution contract tests, app_role/RLS coverage, and doc reconciliation"
```

---

### Task 11: Full verification

**Files:** none — this task runs the whole suite and closes out drift across Tasks 1-10.

- [ ] **Step 1: Full clean build**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -q clean verify
```

Expected `BUILD SUCCESS`, including `ModularityTests` (confirming `distribution`'s `allowedDependencies` are still exactly `{ party::api, product::api, refdata::api }` — it publishes events `payment` consumes and consumes events `payment` publishes, which is precisely why the dependency must stay event-only), `NoCircularDependencyTest`, `NoCrossModuleJoinTest` (which already lists `distribution`), `AlertRuleMetricProducerTest`, and `ActuatorExposureTest`. Aggregate real counts rather than trusting the log tail:

```bash
grep -h "Tests run:" target/surefire-reports/*.txt | awk -F'[:,]' '{t+=$2; f+=$4; e+=$6} END {print "Total:", t, "Failures:", f, "Errors:", e}'
```

- [ ] **Step 2: Mirror the CI `db-migration-validation` job**

Apply every migration in `scripts/migrate.sh`'s order against a fresh disposable `postgres:16` (read that file for the real module ordering rather than assuming), then run `db-migrations/_post-migration/verify-partition-controls.sql` and confirm zero drift. Re-run Task 1's privilege query and confirm **six** distribution tables all report `rls=t has_policy=t sel=t upd=t`.

- [ ] **Step 3: Confirm the pg_cron close registers on the real image**

Using a container built from the real `infra/postgres/Dockerfile` (not bare `postgres:16`, which lacks `pg_cron`), apply the distribution migrations then `configure-commission-close.sql`, and confirm the `commission-close` job registers and the function is callable. This is the specific failure mode a prior milestone found had never been executed.

- [ ] **Step 4: Acceptance-criteria confirmation**

Confirm `docs/08-implementation-roadmap.md:168`'s criterion — "Commission calculation batch tested against multi-tier commission plans (the Di1 fix)" — naming the specific tests, not a blanket pass:
1. **Multi-tier calculation**: `CommissionCalculatorTest`'s three-level seller/parent/grandparent case plus the partial-hierarchy, wrong-currency, inactive-agent and cycle cases.
2. **Di1's structural half**: `CommissionPlan`/`CommissionRule` as separate aggregates with the five tier types, `getApplicablePlan()` implemented — `DistributionApiIntegrationTest`.
3. **"Batch"**: `CommissionCloseSweepPsqlTest` (cross-tenant monthly close via pg_cron) plus `CommissionPayoutEndToEndTest` (the payout half). **State explicitly** that accrual is event-driven per policy rather than a recomputation batch, per user decision 3 — that divergence from the roadmap's wording is deliberate and must not be glossed.

- [ ] **Step 5: Report and stop**

Do not merge. Report the aggregate test count, the acceptance-criteria mapping, every deviation, and every deferred item, so the final whole-branch review has an accurate baseline.

---

## Self-Review Notes

**Judgment calls flagged for the final review — each a place where this plan chose a side. The commission algorithm as a whole is invented; these are the specific choices most worth challenging.**

1. **"Actual production" is premium, not sum assured.** Every rate-based accrual multiplies the *premium*. If Actuarial intends sum-assured-based first-year commission, every rate value and the calculator's core line change together.
2. **Tier selection is by trigger, with no precedence table and no rule stacking.** Exactly one direct-agent rule fires per event. A plan with two `FIRST_YEAR` rules silently uses the first found — arguably it should be rejected at authoring time.
3. **A first-invoice collection accrues nothing**, to avoid double-paying against `FIRST_YEAR`. This makes `policy_projection.first_invoice_id` load-bearing state, and a *missed* `PremiumCollected` still shifts which invoice counts as "first" (a duplicate no longer does — see Task 7's amendment).
3a. **`billing.PremiumCollected` fires only on the edge INTO `PAID`** (user-approved during Task 7). A partial collection therefore earns no renewal commission at all until the invoice is topped up, and an invoice left permanently `PARTIALLY_PAID` earns none ever. The alternative — emitting per collection and accruing per collection — is arguably more faithful to "commission on premium actually collected", but needs an idempotency key other than `invoiceId` (the direct-call path can pass a null `paymentReference`). Worth challenging if the business expects commission to track part-payments.
3b. **The event carries the invoice's own amount and currency, not the tendered ones.** An overpayment therefore does not inflate commission, and the `amount`/`currency` arguments to `applyConfirmedPayment` remain as unused as they have always been. If a gateway can ever confirm in a different currency than the invoice, that mismatch is currently invisible here.
4. **Overrides fire only on issuance, never on renewal.** Plausible either way; a real plan might pay supervisors on renewal too.
5. **Each ancestor is paid from its own plan, and earns nothing silently if it has none.** The alternative (inherit the seller's plan) would pay more people by default.
6. **The hierarchy walk is capped at 2 levels.** That is all the tier vocabulary needs, but a real agency hierarchy is deeper, and a 3-level agency earns nothing above the grandparent.
7. **Clawback is full, not pro-rata, within a single flat window**, and reverses only `FIRST_YEAR`. Pro-rata by months elapsed is at least as common in practice.
8. **A reversal against an already-`PAID` statement books into the current open period** rather than adjusting the paid one. Correct for auditability, but it means an agent's negative balance can outlive the policy that caused it.
9. **A statement total may be negative and no CHECK prevents it** — deliberately inconsistent with every other money column on this platform. Called out in the migration so a future "consistency" fix does not add a positivity constraint that would reject correct data.
10. **`agency_hierarchy` is not created**; V1's header comment is corrected instead. Two docs name that table.
11. **`THRESHOLD_BONUS` is accepted by the DB CHECK but rejected at authoring and skipped by the calculator.** A tier type that exists in the schema and nowhere else is the "unreachable enum value" smell a prior review flagged — here it is deliberate and documented, but it is still a dead value.
12. **Statement identity includes currency**, so a multi-currency agent gets multiple statements per period. No doc defines multi-currency behaviour at all.
13. **`FINANCE_OFFICER`/`ADMIN` are chosen for onboarding and plan authoring** because no distribution-specific staff role exists. `docs/04`'s matrix says "onboard, administer" without naming a role.
14. **The projection is never reconciled against `policy`.** A policy issued before M7, or one whose `PolicyIssued` was lost, silently never accrues and is never clawed back.
15. **Two of distribution's five declared events get no producer in M7** — `distribution.AgentLicenseExpiring` and, indirectly, anything depending on it. `agent_profile.license_status` is set at onboarding and never transitions afterwards, so an agent whose licence lapses keeps accruing commission until someone edits the row by hand. `distribution/V1` already ships the partial index (`idx_agent_license_expiry`) for the sweep that would fix it. Deferred deliberately: it is a second pg_cron sweep with its own notification half, and it is orthogonal to the acceptance criterion. Called out because `canAccrueCommission()` reads a field nothing maintains, which makes the licence gate weaker than it looks.
15a. **Task 5's `requestStatementPayout` shipped with no test that reached the database, and it could never have worked.** Task 1's `distribution/V2` re-pointed `commission_statement.status`'s CHECK to the five-state lifecycle but left the column at V1's `VARCHAR(15)`, sized for its old two-state vocabulary — and `PAYOUT_REQUESTED` is 16 characters. The constraint therefore ADMITTED a value the column type could not physically store, and every payout request would have died at the write with `value too long for type character varying(15)`. Three task-level test runs and two full-suite runs stayed green over it, because nothing persisted a `PAYOUT_REQUESTED` row until Task 8's end-to-end test did. Fixed in V2 (widened to `VARCHAR(20)`, before the CHECK swap). **The generalisable lesson for the final review: a CHECK constraint and a column type are two independent declarations about the same column, and this project's migration convention of "drop and re-add the CHECK" does not prompt anyone to re-check the width.**

  Swept the rest of the platform for the same defect rather than leaving it as a recommendation. Every inline `VARCHAR(n) ... CHECK (col IN (...))` definition across all 18 modules' migrations fits, and so does every later-migration CHECK swap: `payment/V2`'s `disbursement_instruction.purpose` (longest 17, column 30), `payment/V4`'s two status swaps (longest 9, columns 15), and `policyloan/V4`'s `policy_loan.status` (longest `RESERVED_PENDING_ORIGINATION` at 28, column 30 — the narrowest margin on the platform). `distribution`'s was the only genuine mismatch. Still worth asking of every future milestone that widens an enum vocabulary in place, since two of these sit within 2 characters of their limit.

16. **`CommissionStatement.close()` has no production caller** — the pg_cron function is the real closer, so the OPEN→CLOSED rule exists both as SQL and as Java. Retained deliberately (see that method's own javadoc); listed here because "a method only its unit test calls" is exactly the kind of thing a reviewer should challenge rather than assume.
17. **`Broker`/bancassurance-partner modelling is absent.** `docs/01-domain-map.md:66-67` names `Agent`/`Broker` as key aggregates and bancassurance partner agreements as a responsibility; `agent_profile` has no type discriminator at all. No acceptance criterion requires it, so M7 treats every distributor as an agent. A later milestone adding brokers will need a discriminator column and probably different commission mechanics.
