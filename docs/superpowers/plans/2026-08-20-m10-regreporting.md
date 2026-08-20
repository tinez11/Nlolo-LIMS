# M10 — Regulatory Reporting (TIRA) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `regreporting`'s event-driven reporting read model and a generic line-item return generator, per `docs/superpowers/specs/2026-08-20-m10-regreporting-design.md`. TIRA's real return catalog is C2-blocked and deliberately not invented.

**Architecture:** `regreporting` calls no other module — `allowedDependencies` stays exactly `{ refdata::api }`. Four event listeners maintain a small star schema: two dimension tables (`policy_dimension`, `claim_dimension`) supply the attributes that later events don't carry, and four fact tables store **gross per-cause movements** per period. A stock metric is a cumulative sum over movements `period <= P`, so every historical period stays correct with no scheduled job. Generating a return resolves a seeded `return_definition`'s lines through a named `MetricReader` registry and writes `return_line` rows, synchronously.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith, Postgres 16 (schema-per-module + RLS), Testcontainers, Micrometer/Prometheus.

---

## Global Constraints

Every task's requirements implicitly include this section.

- **`regreporting`'s `allowedDependencies` MUST stay exactly `{ "refdata::api" }`.** Verified in `src/main/java/tz/co/nlolo/lifeplatform/regreporting/package-info.java`; matches `docs/02-module-architecture.md:177`. Do **not** add `policy::api`, `claims::api`, `billing::api`, `reinsurance::api`, `finaccounting::api`, or `document::api`. `ModularityTests` enforces this. Every business fact arrives by event.
- **`regreporting/api/package-info.java` is currently MISSING `@NamedInterface("api")`** — verified: it contains only a bare `package` declaration. Add it (Task 2), exactly as M7/M8/M9 each did for their modules.
- **NO scheduler, and no pg_cron.** The spec's §11 establishes this is a platform constraint, not a preference: there is no tenant-directory table, so no background thread can enumerate tenants under fail-closed RLS (stated in `Application.java`'s header and again at `PolicyApiImpl:264`). Do not add a `@Scheduled` method, a pg_cron job, or a `_post-migration` configure script. Generation is caller-driven only.
- **NO writes to `regulatory_return.document_ref`.** Rendering a submission artifact needs TIRA's file format, which is exactly what C2 has not supplied. The column stays nullable and unwritten.
- **Gross measures, never a net delta.** Every fact-table measure is a non-negative per-cause figure. `policy_movement` has five separate count columns; the net movement is *derived* (`issued + reinstated − lapsed − matured − claim_terminated`), never stored. Storing the net loses the gross irrecoverably.
- **Only `PolicyIssued` carries `productId`; only `ClaimRegistered` carries `claimType`.** Verified against `api/asyncapi-events.yaml`. That is why the two dimension tables exist. A movement whose dimension row is missing must be attributed to the `UNKNOWN` sentinel, **never dropped** — a silently-vanishing financial figure is strictly worse than a visibly-unattributed one.
- **`policy.PolicySurrendered` fires for a settled claim, not a policyholder surrender** (its own schema description says so; the surrender choreography was never implemented). Count it as `policies_claim_terminated`. Never label it a surrender.
- **An `AFTER_COMMIT` `@TransactionalEventListener` MUST use a `PROPAGATION_REQUIRES_NEW` `TransactionTemplate`.** A plain `@Transactional` called from an AFTER_COMMIT callback silently joins the already-committed producer transaction and never commits — empirically confirmed on this project. Copy `reinsurance/application/PolicyEventListener.java`.
- **`TenantContext` save/set/restore, never an unconditional `clear()`.** Use `TenantContext.getOrNull()` to save, `set(...)`, then restore the previous value in a `finally` (clearing only when there was none). Same-thread nesting hazard.
- **Explicit `@Component("regreporting<X>EventListener")` bean names.** `PolicyEventListener`, `ClaimEventListener`, and `PaymentEventListener` are each already declared by more than one module; this platform lost a whole task to a Spring bean-name collision. **Also run a collision check on every new class's simple name before creating it** (`find src/main/java -name "<NewClass>.java"`) — Spring Data derives repository bean names and JPA derives entity names from the simple class name.
- **Money is `NUMERIC(19,2)` + `CHAR(3)` in storage, a decimal STRING on the wire** (`docs/06-database-schema.md:32`) — never a float, never a JSON number. Internally `BigDecimal`; compare with `compareTo`/`isEqualByComparingTo`, never `equals` (scale-sensitive).
- **Event payload money shape is always** `Map.of("amount", <BigDecimal>.toPlainString(), "currencyCode", <String>)`. Use `.toPlainString()`, never `.toString()`.
- **Cross-module references are opaque columns, never FKs** (`docs/06-database-schema.md:29`). `policy_dimension.policy_number`, `claim_dimension.claim_id`, and every fact table's `product_id` have no FK. Intra-module FKs (`return_line.return_id` → `regulatory_return`) are correct and expected.
- **Every tenant-scoped table needs `tenant_id` indexed and an RLS policy of the exact shape** `USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)` (`docs/06-database-schema.md:25`).
- **Column widths must be checked against their CHECK vocabularies in the same migration.** M7 shipped a CHECK admitting a 16-character value into a `VARCHAR(15)`, making a whole payout path unwritable while every test stayed green. Measure the longest literal, don't estimate.
- **Never edit an already-applied migration.** `regreporting/V1` is immutable — `scripts/migrate.sh` applies every `V*.sql` per module in version order, so it has been applied to real deployments even though no test covers it. Add `V2`.
- **Contract tests pair `openApi().isValid(...)` with `SpecTypeConformance.matchesDeclaredTypes(specPath, schemaName)`.** `isValid` does **not** enforce primitive JSON types (measured on this platform).
- **Build commands.** Always on the host, in the FOREGROUND, never inside Docker (it breaks Testcontainers networking):
  ```bash
  export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
  ./mvnw -B -o test -Dtest=SomeSpecificTest      # the normal case
  ./mvnw -B -o test                              # only when the rule below says so
  ```
- **Run the NARROWEST test set that could detect a regression from your change.** The full suite is 598 tests in ~10 minutes, mostly container and Spring-context setup unrelated to your change.

  **Run the FULL suite only when your task does at least one of these:**
  1. changes production code **outside** `regreporting`;
  2. changes a **shared test class or utility** (Task 9 edits `AppRolePrivilegesIntegrationTest` and `RowLevelSecurityIntegrationTest`, which every module's correctness leans on);
  3. changes **shared config** — `pom.xml`, `application.yml`, `SecurityConfig`, `Application.java`;
  4. changes a migration that some existing test's own migration list applies. **For `regreporting` specifically, no such test exists yet** — verified with `grep -rn "regreporting" src/test`, which returns only a prose comment in `ClaimSettlementEndToEndTest:553` and an ArchUnit module-name string in `NoCrossModuleJoinTest:16`; neither applies the migration. That is precisely why V1's missing RLS and grants went unnoticed. The schema comes under automated test for the first time in Tasks 5 and 9;
  5. is the final verification task (Task 10).
- **Never report coverage you did not execute.** If you ran a subset, say so and say why.
- **Baseline before M10: 598 tests, 0 failures, 0 errors** (`main` at `93302a8`). Quote the count from whatever you actually ran and label it (`full suite` vs `-Dtest=X`).

---

## File Structure

**New — `regreporting` module:**
- `db-migrations/regreporting/V2__grants_rls_dimensions_movements_and_return_lines.sql`
- `regreporting/api/` — `RegreportingApi`, `RegulatoryReturnView`, `ReturnLineView`, `MetricKind`, `ReturnNotFoundException`, `RegreportingValidationException`, `package-info.java` (add `@NamedInterface`)
- `regreporting/domain/` — `PolicyDimension`, `PolicyDimensionId`, `ClaimDimension`, `ClaimDimensionId`, `PolicyMovement`, `PolicyMovementId`, `ClaimsMovement`, `ClaimsMovementId`, `PremiumMovement`, `PremiumMovementId`, `ReinsuranceMovement`, `ReinsuranceMovementId`, `RegulatoryReturn`, `ReturnLine`, `ReturnDefinition`, `ReturnDefinitionId`, `ReturnDefinitionLine`, `ReturnDefinitionLineId`, `MetricName`
- `regreporting/infrastructure/` — 8 repositories, `MetricReaderRegistry`, `RegreportingExceptionHandler`, `RegulatoryReturnController`, DTOs
- `regreporting/application/` — `RegreportingApiImpl`, `ReturnGenerator`, and 4 listeners (`PolicyEventListener`, `ClaimsEventListener`, `BillingEventListener`, `ReinsuranceEventListener`)
- `api/openapi/openapi-regreporting.yaml` (extracted)
- Tests — `CumulativeMetricTest`, `ReturnGeneratorTest`, `RegreportingApiIntegrationTest`, `ProjectionEndToEndTest`, `HistoricalPeriodReturnTest`, `MissingDimensionTest`, `RegreportingContractTest`, `RegreportingSpecParsesTest`

**Modified:**
- `api/openapi/openapi-regreporting-document-refdata.yaml` (regreporting block removed)
- `observability/alert-rules.yml`, `src/test/java/.../AlertRuleMetricProducerTest.java`
- `src/test/java/.../AppRolePrivilegesIntegrationTest.java`, `RowLevelSecurityIntegrationTest.java`
- `docs/06-database-schema.md`, `docs/05-event-catalog.md`

---

### Task 1: `regreporting/V2` — grants, RLS, dimensions, movements, return lines

**Files:**
- Create: `db-migrations/regreporting/V2__grants_rls_dimensions_movements_and_return_lines.sql`

**Interfaces:**
- Produces: every table Tasks 3-9 map entities onto. `policy_dimension`, `claim_dimension`, `policy_movement` (renamed from `policy_in_force_summary`), `claims_movement`, `premium_movement`, `reinsurance_movement`, `return_definition`, `return_definition_line`, `return_line`; plus grants/RLS on all of them and on V1's `regulatory_return`.

- [ ] **Step 1: Read V1 first**

Read `db-migrations/regreporting/V1__create_regreporting_schema.sql` in full. Note: it creates `regulatory_return` and `policy_in_force_summary`; it enables RLS on neither; it grants `app_role` nothing; `regulatory_return.status` is `VARCHAR(15) CHECK (status IN ('GENERATING','READY'))`; `policy_in_force_summary` has `product_id UUID NOT NULL` and a unique index on `(tenant_id, period, product_id)`; and its line 1 cites "Deliverable 3 §10 (CQRS read-model only)".

- [ ] **Step 2: Write the migration**

```sql
-- Module: regreporting V2 -- M10 Task 1.
--
-- CITATION CORRECTION. V1's line 1 reads "Deliverable 3 §10 (CQRS read-model only)".
-- That section does not say this: docs/03-aggregate-design.md:179's §10 is "Thin/Generic
-- Modules -- unchanged from Rev 1 (iam, document, refdata, omnichannel)", which does not
-- mention regreporting at all, and no CQRS read-model section for regreporting exists
-- anywhere in that document. The DESIGN INTENT V1's comment describes is sound and this
-- migration implements it; only the citation was wrong. Recorded because this project has
-- repeatedly found false doc citations used as justification for a design decision.
--
-- V1 also carries the recurring V1 defect set -- the SEVENTH consecutive module: no RLS on
-- either table, no GRANT to app_role anywhere in the schema, no audit columns. Both V1
-- tables carry tenant_id NOT NULL, so app_role could read every tenant's reporting data.
-- No test has ever applied regreporting/V1 (verified: grep -rn "regreporting" src/test
-- returns one prose comment and one ArchUnit string list), which is why none of it showed up.
-- =============================================================================
-- 1. Grants. V1 grants app_role nothing anywhere in this schema.
-- =============================================================================
GRANT USAGE ON SCHEMA regreporting TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA regreporting TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA regreporting GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;

-- =============================================================================
-- 2. RLS on V1's two tables. Neither had it.
-- =============================================================================
ALTER TABLE regreporting.regulatory_return ENABLE ROW LEVEL SECURITY;
CREATE POLICY regulatory_return_tenant_isolation ON regreporting.regulatory_return
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- policy_in_force_summary's RLS is deliberately deferred to section 4, AFTER its rename:
-- ALTER TABLE ... RENAME TO does NOT rename the table's policies, so creating the policy here
-- would leave policy_movement carrying a policy called policy_in_force_summary_tenant_isolation
-- forever -- functional, but a permanently misleading artifact in pg_policy.

-- =============================================================================
-- 3. Audit columns and the regeneration key on regulatory_return.
--
--    generateReturn is idempotent per (tenant, return_type, period): regenerating REPLACES
--    the prior lines rather than accumulating duplicates, so that triple must be unique.
--    A return is a DERIVED artifact -- re-deriving it must be safe.
-- =============================================================================
ALTER TABLE regreporting.regulatory_return ADD COLUMN generated_by VARCHAR(100);
ALTER TABLE regreporting.regulatory_return ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX ux_regulatory_return_once
    ON regreporting.regulatory_return (tenant_id, return_type, period);

-- status stays VARCHAR(15) CHECK IN ('GENERATING','READY') exactly as V1 declared it.
-- Under synchronous generation (design spec §7) GENERATING is UNREACHABLE -- a return is
-- complete when it is created -- and READY is the only value ever written. The CHECK is left
-- alone because dropping an unreachable enum value is not worth a migration.
-- NOTE FOR WHOEVER ADDS SUBMISSION STATES AFTER C2: 'SUBMISSION_FAILED' is 17 characters and
-- would need this column widened in the SAME migration that adds it to the CHECK. That exact
-- oversight (a CHECK admitting a value the column cannot store) made a whole payout path
-- unwritable in M7 while every test stayed green -- see docs/06-database-schema.md:30.
COMMENT ON COLUMN regreporting.regulatory_return.status IS
    'Always READY. GENERATING is unreachable under synchronous generation; retained because V1 is immutable.';
COMMENT ON COLUMN regreporting.regulatory_return.document_ref IS
    'C2-BLOCKED -- never written. Rendering a submission artifact requires TIRA''s file format, which C2 has not supplied.';

-- =============================================================================
-- 4. policy_in_force_summary -> policy_movement, with GROSS per-cause measures.
--
--    THE RENAME IS NOT COSMETIC. Under the movement model this table stores per-period
--    movements, not a snapshot of what is in force; a reader trusting the old name would
--    compute in-force figures wrongly. Its existing unique index on
--    (tenant_id, period, product_id) is already the correct grain and is retained.
--
--    GROSS, NOT NET: a single signed delta cannot distinguish "10 issued, 3 lapsed" from
--    "7 issued, 0 lapsed" -- both net +7 -- and every prudential return needs gross new
--    business and gross terminations as separate lines. Storing the net loses the gross
--    irrecoverably; storing the gross derives the net by arithmetic.
-- =============================================================================
ALTER TABLE regreporting.policy_in_force_summary RENAME TO policy_movement;
ALTER INDEX regreporting.ux_policy_in_force_summary RENAME TO ux_policy_movement;

-- Promote the composite business key to the PRIMARY KEY and drop V1's surrogate summary_id.
--
-- WHY THIS IS NECESSARY, not tidying: V1 made summary_id the PK and left
-- (tenant_id, period, product_id) as a mere UNIQUE INDEX. Its three sibling fact tables in
-- section 6 all use the composite AS the primary key, so without this the domain model would
-- have to map policy_movement on a surrogate id while the other three use @IdClass -- an
-- asymmetry with no upside, and the plan's Task 3 maps all four the same way.
--
-- WHY IT IS SAFE REGARDLESS OF EXISTING DATA -- which matters, because "it works because the
-- table happens to be empty today" is exactly the reasoning that breaks on the first deployment
-- that has rows: the columns being promoted are already NOT NULL, and ux_policy_movement is
-- already a UNIQUE index over exactly this tuple, so the promotion cannot fail on either null
-- or duplicate data. The uniqueness it depends on is already enforced.
ALTER TABLE regreporting.policy_movement DROP CONSTRAINT policy_in_force_summary_pkey;
ALTER TABLE regreporting.policy_movement DROP COLUMN summary_id;
ALTER TABLE regreporting.policy_movement ADD PRIMARY KEY (tenant_id, period, product_id);
-- ux_policy_movement is now redundant with the PK's own implicit unique index. Dropped rather
-- than left as a second identical index that every write has to maintain.
DROP INDEX regreporting.ux_policy_movement;

ALTER TABLE regreporting.policy_movement RENAME COLUMN policy_count TO policies_issued;
ALTER TABLE regreporting.policy_movement RENAME COLUMN total_sum_assured_amount TO sum_assured_issued;
ALTER TABLE regreporting.policy_movement RENAME COLUMN total_sum_assured_currency TO currency;

ALTER TABLE regreporting.policy_movement ADD COLUMN policies_reinstated INTEGER NOT NULL DEFAULT 0;
ALTER TABLE regreporting.policy_movement ADD COLUMN policies_lapsed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE regreporting.policy_movement ADD COLUMN policies_matured INTEGER NOT NULL DEFAULT 0;
ALTER TABLE regreporting.policy_movement ADD COLUMN policies_claim_terminated INTEGER NOT NULL DEFAULT 0;
ALTER TABLE regreporting.policy_movement ADD COLUMN sum_assured_terminated NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE regreporting.policy_movement ADD COLUMN updated_at TIMESTAMPTZ;

-- Every measure is a GROSS non-negative figure, so >= 0 is the right guard.
-- Deliberately >= 0 and not > 0: an upsert creates the row with zeros and then increments ONE
-- column, so zero is a normal value for every other cause in that period. This is the opposite
-- of finaccounting.gl_posting.amount's strict > 0, where a separate direction column carries the
-- sign and a zero-amount posting is meaningless. Do not "fix" this into > 0.
ALTER TABLE regreporting.policy_movement
    ADD CONSTRAINT policy_movement_non_negative CHECK (
        policies_issued >= 0 AND policies_reinstated >= 0 AND policies_lapsed >= 0
        AND policies_matured >= 0 AND policies_claim_terminated >= 0
        AND sum_assured_issued >= 0 AND sum_assured_terminated >= 0);

CREATE INDEX idx_policy_movement_tenant ON regreporting.policy_movement (tenant_id);

-- RLS created here, after the rename, so the policy carries the table's real name (see section 2).
ALTER TABLE regreporting.policy_movement ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_movement_tenant_isolation ON regreporting.policy_movement
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- =============================================================================
-- 5. The two dimension tables.
--
--    These exist because the attributes needed to ATTRIBUTE a movement arrive on a different
--    event from the movement itself. Verified against api/asyncapi-events.yaml: PolicyIssued
--    (:421) carries productId and sumAssured, but PolicyLapsed/Reinstated/Matured/Surrendered
--    carry only a policy number and a timestamp; ClaimRegistered carries claimType, but
--    ClaimApproved/Rejected/Settled do not. Same pattern distribution (M7) and reinsurance (M8)
--    each established with their own policy_projection, for the identical allowedDependencies
--    constraint. policy_number and claim_id are OPAQUE refs -- no FKs (docs/06-database-schema.md:29).
-- =============================================================================
CREATE TABLE regreporting.policy_dimension (
    tenant_id             UUID NOT NULL,
    policy_number          VARCHAR(20) NOT NULL,
    product_id              UUID NOT NULL,
    sum_assured_amount       NUMERIC(19,2) NOT NULL,
    sum_assured_currency      CHAR(3) NOT NULL DEFAULT 'TZS',
    issue_date                 DATE NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, policy_number),
    CONSTRAINT policy_dimension_sum_assured_positive CHECK (sum_assured_amount > 0)
);
CREATE INDEX idx_policy_dimension_tenant ON regreporting.policy_dimension (tenant_id);
ALTER TABLE regreporting.policy_dimension ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_dimension_tenant_isolation ON regreporting.policy_dimension
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

CREATE TABLE regreporting.claim_dimension (
    tenant_id           UUID NOT NULL,
    claim_id             UUID NOT NULL,
    claim_type            VARCHAR(20) NOT NULL
        CHECK (claim_type IN ('DEATH','DISABILITY','CRITICAL_ILLNESS','MATURITY')),
    policy_number          VARCHAR(20) NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, claim_id)
);
CREATE INDEX idx_claim_dimension_tenant ON regreporting.claim_dimension (tenant_id);
ALTER TABLE regreporting.claim_dimension ENABLE ROW LEVEL SECURITY;
CREATE POLICY claim_dimension_tenant_isolation ON regreporting.claim_dimension
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- claim_type VARCHAR(20) against a longest value of 'CRITICAL_ILLNESS' (16). MEASURED, not
-- estimated, per docs/06-database-schema.md:30's own warning.

-- =============================================================================
-- 6. The remaining three fact tables. All measures gross and non-negative.
-- =============================================================================
CREATE TABLE regreporting.claims_movement (
    tenant_id          UUID NOT NULL,
    period              VARCHAR(10) NOT NULL,
    claim_type           VARCHAR(20) NOT NULL,
    registered_count      INTEGER NOT NULL DEFAULT 0,
    approved_count         INTEGER NOT NULL DEFAULT 0,
    rejected_count          INTEGER NOT NULL DEFAULT 0,
    settled_count            INTEGER NOT NULL DEFAULT 0,
    approved_amount           NUMERIC(19,2) NOT NULL DEFAULT 0,
    settled_amount             NUMERIC(19,2) NOT NULL DEFAULT 0,
    currency                    CHAR(3) NOT NULL DEFAULT 'TZS',
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, period, claim_type),
    CONSTRAINT claims_movement_non_negative CHECK (
        registered_count >= 0 AND approved_count >= 0 AND rejected_count >= 0
        AND settled_count >= 0 AND approved_amount >= 0 AND settled_amount >= 0)
);
CREATE INDEX idx_claims_movement_tenant ON regreporting.claims_movement (tenant_id);
ALTER TABLE regreporting.claims_movement ENABLE ROW LEVEL SECURITY;
CREATE POLICY claims_movement_tenant_isolation ON regreporting.claims_movement
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

CREATE TABLE regreporting.premium_movement (
    tenant_id         UUID NOT NULL,
    period             VARCHAR(10) NOT NULL,
    product_id          UUID NOT NULL,
    collected_amount     NUMERIC(19,2) NOT NULL DEFAULT 0,
    currency              CHAR(3) NOT NULL DEFAULT 'TZS',
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, period, product_id),
    CONSTRAINT premium_movement_non_negative CHECK (collected_amount >= 0)
);
CREATE INDEX idx_premium_movement_tenant ON regreporting.premium_movement (tenant_id);
ALTER TABLE regreporting.premium_movement ENABLE ROW LEVEL SECURITY;
CREATE POLICY premium_movement_tenant_isolation ON regreporting.premium_movement
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- reinsurance_movement is deliberately NOT attributed by product. reinsurance.CessionRecorded
-- is published from reinsurance's own transaction reacting to PolicyIssued, so its arrival is
-- NOT ordered against regreporting's own PolicyIssued listener (docs/05-event-catalog.md:62
-- documents that ordering across fan-in consumers is not guaranteed). Keying it by product
-- would create a race where a cession can arrive before the dimension row it needs.
CREATE TABLE regreporting.reinsurance_movement (
    tenant_id            UUID NOT NULL,
    period                VARCHAR(10) NOT NULL,
    ceded_risk_amount      NUMERIC(19,2) NOT NULL DEFAULT 0,
    ceded_premium_amount    NUMERIC(19,2) NOT NULL DEFAULT 0,
    currency                 CHAR(3) NOT NULL DEFAULT 'TZS',
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, period),
    CONSTRAINT reinsurance_movement_non_negative CHECK (
        ceded_risk_amount >= 0 AND ceded_premium_amount >= 0)
);
CREATE INDEX idx_reinsurance_movement_tenant ON regreporting.reinsurance_movement (tenant_id);
ALTER TABLE regreporting.reinsurance_movement ENABLE ROW LEVEL SECURITY;
CREATE POLICY reinsurance_movement_tenant_isolation ON regreporting.reinsurance_movement
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- =============================================================================
-- 7. Return definitions -- THE DATA HALF of the return format (design spec §5).
--
--    Line COMPOSITION is data (these two tables). Metric COMPUTATION is code (the
--    MetricReaderRegistry). The precise consequence, which no comment here or anywhere else
--    may overstate: re-shaping a return out of the seventeen metrics that already exist,
--    optionally filtered by product or claim type, is a SEED change. Asking for a figure
--    nobody computes yet is a JAVA change. M9's final review caught its own spec claiming
--    account codes were "data" when they were compiled constants -- do not repeat that.
--
--    period_kind exists so a return type is pinned to one period format. Without it an annual
--    and a quarterly period could be cumulative-summed together, which would be silently wrong.
-- =============================================================================
CREATE TABLE regreporting.return_definition (
    tenant_id        UUID NOT NULL,
    return_type       VARCHAR(30) NOT NULL,
    label              VARCHAR(200) NOT NULL,
    description         VARCHAR(500),
    period_kind          VARCHAR(10) NOT NULL CHECK (period_kind IN ('QUARTERLY','ANNUAL')),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, return_type)
);
CREATE INDEX idx_return_definition_tenant ON regreporting.return_definition (tenant_id);
ALTER TABLE regreporting.return_definition ENABLE ROW LEVEL SECURITY;
CREATE POLICY return_definition_tenant_isolation ON regreporting.return_definition
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

CREATE TABLE regreporting.return_definition_line (
    tenant_id       UUID NOT NULL,
    return_type      VARCHAR(30) NOT NULL,
    line_no           INTEGER NOT NULL,
    line_code          VARCHAR(30) NOT NULL,
    label               VARCHAR(200) NOT NULL,
    metric_name          VARCHAR(40) NOT NULL,
    dimension_filter      VARCHAR(40),   -- a product_id, or a claim_type; null = unfiltered
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, return_type, line_no)
);
CREATE INDEX idx_return_definition_line_tenant ON regreporting.return_definition_line (tenant_id);
ALTER TABLE regreporting.return_definition_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY return_definition_line_tenant_isolation ON regreporting.return_definition_line
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- metric_name VARCHAR(40) against the longest of the seventeen registry names,
-- 'POLICIES_CLAIM_TERMINATED' (25). MEASURED. dimension_filter VARCHAR(40) holds either a
-- 36-character UUID string or a claim type (longest 'CRITICAL_ILLNESS', 16).

-- =============================================================================
-- 8. return_line -- a generated return's actual content.
--
--    return_id IS an intra-module FK and SHOULD be: a line without its header is orphaned
--    data. ON DELETE CASCADE because regeneration replaces a return's lines wholesale.
-- =============================================================================
CREATE TABLE regreporting.return_line (
    return_line_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    return_id          UUID NOT NULL REFERENCES regreporting.regulatory_return (return_id) ON DELETE CASCADE,
    tenant_id           UUID NOT NULL,
    line_no              INTEGER NOT NULL,
    line_code             VARCHAR(30) NOT NULL,
    label                  VARCHAR(200) NOT NULL,
    metric_name             VARCHAR(40) NOT NULL,
    numeric_value            NUMERIC(19,2) NOT NULL,
    currency                  CHAR(3),   -- null for a count; set for a money figure
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_return_line_no ON regreporting.return_line (return_id, line_no);
CREATE INDEX idx_return_line_tenant ON regreporting.return_line (tenant_id);
ALTER TABLE regreporting.return_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY return_line_tenant_isolation ON regreporting.return_line
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- numeric_value carries no non-negativity CHECK, deliberately and unlike every fact table
-- above: a legitimate DERIVED figure can be negative. POLICIES_IN_FORCE is a cumulative sum of
-- (issued + reinstated - lapsed - matured - claim_terminated), and a period whose terminations
-- exceed its issuances is a real business outcome, not corrupt data.

-- =============================================================================
-- 9. Seed the ONE placeholder return definition.
--
--    EVERY LINE CODE AND LABEL BELOW IS AN INVENTED PLACEHOLDER pending the TIRA circular
--    (C2, docs/02-module-architecture.md:198). Ten lines chosen to exercise all four fact
--    tables end to end -- NOT because TIRA asks for these figures, which nobody yet knows.
--
--    Seeded for the well-known dev/test tenant only; a real deployment seeds per tenant during
--    onboarding, and replacing this catalog with TIRA's real one is a seed change to the extent
--    it asks for figures the registry already computes (design spec §5).
-- =============================================================================
INSERT INTO regreporting.return_definition (tenant_id, return_type, label, description, period_kind)
VALUES ('11111111-1111-1111-1111-111111111111', 'QUARTERLY_PRUDENTIAL',
        'Quarterly Prudential Return (PLACEHOLDER)',
        'PLACEHOLDER pending the TIRA return catalog (C2). Line codes and labels are invented.',
        'QUARTERLY');

INSERT INTO regreporting.return_definition_line
    (tenant_id, return_type, line_no, line_code, label, metric_name, dimension_filter)
VALUES
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',1,'PL-01','Policies in force at period end','POLICIES_IN_FORCE',NULL),
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',2,'PL-02','Sum assured in force at period end','SUM_ASSURED_IN_FORCE',NULL),
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',3,'PL-03','New policies issued in period','POLICIES_ISSUED',NULL),
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',4,'PL-04','New business sum assured in period','NEW_BUSINESS_SUM_ASSURED',NULL),
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',5,'PL-05','Policies lapsed in period','POLICIES_LAPSED',NULL),
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',6,'CL-01','Claims registered in period','CLAIMS_REGISTERED',NULL),
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',7,'CL-02','Claims settled in period','CLAIMS_SETTLED',NULL),
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',8,'CL-03','Death claims settled amount in period','CLAIMS_SETTLED_AMOUNT','DEATH'),
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',9,'PR-01','Premium collected in period','PREMIUM_COLLECTED',NULL),
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',10,'RI-01','Reinsurance premium ceded in period','REINSURANCE_CEDED_PREMIUM',NULL);
```

- [ ] **Step 3: Apply against a disposable Postgres and verify privileges/RLS — do NOT verify by inspection**

Note `docker cp` mangles Windows paths; pipe via stdin instead.

```bash
R=$(pwd)
docker rm -f m10verify >/dev/null 2>&1
docker run -d --name m10verify -e POSTGRES_PASSWORD=pw postgres:16 >/dev/null
until docker exec m10verify pg_isready -U postgres -q 2>/dev/null; do sleep 1; done
docker exec m10verify psql -U postgres -q -c "CREATE ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'p';"
for f in "$R/db-migrations/regreporting/V1__create_regreporting_schema.sql" \
         "$R/db-migrations/regreporting/V2__grants_rls_dimensions_movements_and_return_lines.sql"; do
  echo "--- applying $(basename $f) ---"
  cat "$f" | docker exec -i m10verify psql -U postgres -v ON_ERROR_STOP=1 -q 2>&1 | tail -5
done
docker exec m10verify psql -U postgres -c "
SELECT c.relname, c.relrowsecurity AS rls,
       EXISTS(SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid) AS has_policy,
       has_table_privilege('app_role', c.oid, 'SELECT') AS sel,
       has_table_privilege('app_role', c.oid, 'INSERT') AS ins
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'regreporting' AND c.relkind = 'r'
ORDER BY c.relname;"
```

Expected: ten tables — `claim_dimension`, `claims_movement`, `policy_dimension`, `policy_movement`, `premium_movement`, `regulatory_return`, `reinsurance_movement`, `return_definition`, `return_definition_line`, `return_line` — every one `rls=t has_policy=t sel=t ins=t`. **`policy_in_force_summary` must NOT appear** (it was renamed). Paste the real table.

Also confirm the PK promotion landed, since Task 3's `@IdClass` mapping depends on it:

```bash
docker exec m10verify psql -U postgres -c "
SELECT a.attname, i.indisprimary
FROM pg_index i
JOIN pg_class c ON c.oid = i.indrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(i.indkey)
WHERE n.nspname='regreporting' AND c.relname='policy_movement' AND i.indisprimary
ORDER BY a.attname;"
```

Expected exactly three rows — `period`, `product_id`, `tenant_id`, all `indisprimary = t` — and **no `summary_id` column anywhere on the table**.

- [ ] **Step 4: Prove the constraints and the seed actually work**

```bash
T=11111111-1111-1111-1111-111111111111
echo "--- the placeholder definition seeded 10 lines ---"
docker exec m10verify psql -U postgres -c "
SET ROLE app_role; SET app.current_tenant_id = '$T';
SELECT count(*) AS definition_lines FROM regreporting.return_definition_line;"
echo "--- a negative gross measure must FAIL ---"
docker exec m10verify psql -U postgres -c "
SET ROLE app_role; SET app.current_tenant_id = '$T';
INSERT INTO regreporting.policy_movement (tenant_id, period, product_id, policies_issued)
VALUES ('$T','2026-Q3', gen_random_uuid(), -1);"
echo "--- zero IS allowed (an upsert creates the row then increments one column) ---"
docker exec m10verify psql -U postgres -c "
SET ROLE app_role; SET app.current_tenant_id = '$T';
INSERT INTO regreporting.policy_movement (tenant_id, period, product_id, policies_issued)
VALUES ('$T','2026-Q3', gen_random_uuid(), 0);"
echo "--- a bad claim_type must FAIL ---"
docker exec m10verify psql -U postgres -c "
SET ROLE app_role; SET app.current_tenant_id = '$T';
INSERT INTO regreporting.claim_dimension (tenant_id, claim_id, claim_type, policy_number)
VALUES ('$T', gen_random_uuid(), 'SPONTANEOUS', 'POL-1');"
echo "--- a duplicate (tenant, return_type, period) must FAIL on ux_regulatory_return_once ---"
docker exec m10verify psql -U postgres -q -c "
SET ROLE app_role; SET app.current_tenant_id = '$T';
INSERT INTO regreporting.regulatory_return (tenant_id, return_type, period, status)
VALUES ('$T','QUARTERLY_PRUDENTIAL','2026-Q3','READY');"
docker exec m10verify psql -U postgres -c "
SET ROLE app_role; SET app.current_tenant_id = '$T';
INSERT INTO regreporting.regulatory_return (tenant_id, return_type, period, status)
VALUES ('$T','QUARTERLY_PRUDENTIAL','2026-Q3','READY');"
echo "--- deleting a return CASCADEs its lines ---"
docker exec m10verify psql -U postgres -c "
SET ROLE app_role; SET app.current_tenant_id = '$T';
INSERT INTO regreporting.return_line (return_id, tenant_id, line_no, line_code, label, metric_name, numeric_value)
SELECT return_id, '$T', 1, 'PL-01', 'x', 'POLICIES_IN_FORCE', 5 FROM regreporting.regulatory_return LIMIT 1;
DELETE FROM regreporting.regulatory_return WHERE tenant_id = '$T';
SELECT count(*) AS orphaned_lines FROM regreporting.return_line;"
docker rm -f m10verify >/dev/null 2>&1
```

Expected in order: `definition_lines = 10`; `violates check constraint "policy_movement_non_negative"`; `INSERT 0 1` (zero allowed); `violates check constraint "claim_dimension_claim_type_check"`; first return inserts, second fails on `ux_regulatory_return_once`; `orphaned_lines = 0`.

- [ ] **Step 5: Commit**

```bash
git add db-migrations/regreporting/
git commit -m "feat: regreporting V2 -- grants, RLS, dimension and movement tables, return definitions and lines"
```

**No Maven run for this task, and that is considered rather than skipped.** This task changes no Java and no shared config, and no existing test applies `regreporting/V1` (verified — see Global Constraints trigger 4), so there is no test whose behaviour could change. Steps 3-4's real-Postgres verification IS this task's verification; paste its output rather than summarising. Report that no Maven run was performed and why.

---

### Task 2: The `regreporting` API surface

**Files:**
- Create: `regreporting/api/MetricKind.java`, `ReturnLineView.java`, `RegulatoryReturnView.java`, `ReturnNotFoundException.java`, `RegreportingValidationException.java`, `RegreportingApi.java`
- Modify: `regreporting/api/package-info.java`

**Interfaces:**
- Produces: every type Tasks 3-9 depend on.

- [ ] **Step 1: Add the missing named interface**

`regreporting/api/package-info.java` currently contains only `package tz.co.nlolo.lifeplatform.regreporting.api;`. Replace with:

```java
@org.springframework.modulith.NamedInterface("api")
package tz.co.nlolo.lifeplatform.regreporting.api;
```

Same gap `distribution` had entering M7, `reinsurance` entering M8, `finaccounting` entering M9.

- [ ] **Step 2: Write `MetricKind`**

```java
package tz.co.nlolo.lifeplatform.regreporting.api;

/**
 * Whether a metric is a point-in-time balance or a within-period total.
 *
 * <p>This distinction is the whole reason the fact tables store movements rather than snapshots:
 * a {@link #STOCK} metric for period P is the cumulative sum of movements where {@code period <= P},
 * so a return generated in Q4 for Q3 reports Q3's real figure. A {@link #FLOW} metric is that
 * period's own row and nothing else.
 */
public enum MetricKind { STOCK, FLOW }
```

- [ ] **Step 3: Write the two views**

```java
package tz.co.nlolo.lifeplatform.regreporting.api;

import java.math.BigDecimal;
import java.util.UUID;

/** One line of a generated return. {@code currency} is null for a count and set for a money figure. */
public record ReturnLineView(UUID returnLineId, int lineNo, String lineCode, String label,
                              String metricName, BigDecimal numericValue, String currency) {}
```

```java
package tz.co.nlolo.lifeplatform.regreporting.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A generated return and its lines.
 *
 * <p>{@code documentRef} is always null: rendering a submission artifact requires TIRA's file
 * format, which is C2-blocked. It is exposed so an API consumer sees the field exists and is
 * unpopulated, rather than discovering later that it was silently omitted.
 */
public record RegulatoryReturnView(UUID returnId, String returnType, String period, String status,
                                    String documentRef, Instant generatedAt, String generatedBy,
                                    List<ReturnLineView> lines) {}
```

- [ ] **Step 4: Write the two exceptions**

Follow `reinsurance/api`'s exact shape (single `String message` constructor):

```java
package tz.co.nlolo.lifeplatform.regreporting.api;

/** 404 at the REST boundary (RegreportingExceptionHandler, Task 8). */
public class ReturnNotFoundException extends RuntimeException {
    public ReturnNotFoundException(String message) { super(message); }
}
```

```java
package tz.co.nlolo.lifeplatform.regreporting.api;

/** 422 at the REST boundary: an unknown return type, or a period whose format does not match
 * the return type's declared {@code period_kind}. */
public class RegreportingValidationException extends RuntimeException {
    public RegreportingValidationException(String message) { super(message); }
}
```

- [ ] **Step 5: Write the API interface**

```java
package tz.co.nlolo.lifeplatform.regreporting.api;

import java.util.List;
import java.util.UUID;

/**
 * The `regreporting` module's public surface.
 *
 * <p>Everything this module reports on arrives as a domain event -- {@code allowedDependencies} is
 * exactly {@code { refdata::api }} and no other module is ever called. There is deliberately no
 * method to write a projection: the read model is derived, and the only way to change it is to
 * publish the business event that caused the change.
 *
 * <p>TIRA's return catalog is C2-blocked. One placeholder definition ships (seeded by
 * {@code regreporting/V2}); every line code and label in it is invented and flagged.
 */
public interface RegreportingApi {

    /**
     * Generates (or REGENERATES) the return for {@code returnType} and {@code period}, synchronously.
     *
     * <p>Idempotent per {@code (tenant, returnType, period)}: regenerating replaces the prior lines
     * rather than accumulating duplicates, because a return is a derived artifact and re-deriving it
     * must be safe.
     *
     * @throws RegreportingValidationException if no definition exists for {@code returnType}, or if
     *         {@code period}'s format does not match that definition's {@code periodKind}
     */
    RegulatoryReturnView generateReturn(String returnType, String period, String generatedBy);

    /** @param period optional filter; null returns every return for the tenant */
    List<RegulatoryReturnView> listReturns(String period);

    /** @throws ReturnNotFoundException if no such return exists FOR THIS TENANT */
    RegulatoryReturnView getReturn(UUID returnId);
}
```

- [ ] **Step 6: Compile and commit**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -o -q compile
git add src/main/java/tz/co/nlolo/lifeplatform/regreporting/api/
git commit -m "feat: regreporting API surface -- views, exceptions and the named interface"
```

Expected: `compile` exits 0. No tests — this task adds only types.

---

### Task 3: Domain entities and repositories

**Files:**
- Create: 10 entities + 8 `@IdClass` id types + `MetricName` in `regreporting/domain/`, and 8 repositories in `regreporting/infrastructure/`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/regreporting/MetricNameTest.java`

**Interfaces:**
- Consumes: `MetricKind` (Task 2).
- Produces: `PolicyDimension`, `ClaimDimension`, `PolicyMovement`, `ClaimsMovement`, `PremiumMovement`, `ReinsuranceMovement`, `RegulatoryReturn`, `ReturnLine`, `ReturnDefinition`, `ReturnDefinitionLine`, `MetricName`, and their repositories.

- [ ] **Step 1: Check every new class name for a collision FIRST**

M8 lost a whole task to exactly this. Before creating any file:

```bash
for c in PolicyDimension PolicyDimensionId ClaimDimension ClaimDimensionId \
         PolicyMovement PolicyMovementId ClaimsMovement ClaimsMovementId \
         PremiumMovement PremiumMovementId ReinsuranceMovement ReinsuranceMovementId \
         RegulatoryReturn ReturnLine ReturnDefinition ReturnDefinitionId \
         ReturnDefinitionLine ReturnDefinitionLineId MetricName \
         PolicyDimensionRepository ClaimDimensionRepository PolicyMovementRepository \
         ClaimsMovementRepository PremiumMovementRepository ReinsuranceMovementRepository \
         RegulatoryReturnRepository ReturnLineRepository ReturnDefinitionRepository \
         ReturnDefinitionLineRepository; do
  echo "$c: $(find src/main/java -name "$c.java" | wc -l) existing"
done
```

Expected: all `0`. If any is non-zero, STOP and report — Spring Data derives repository bean names, and JPA derives entity names, from the simple class name.

- [ ] **Step 2: Write the failing `MetricName` test**

`MetricName` is the enum that keeps the seventeen registry names, the DB's `metric_name` strings, and Task 4's readers from drifting apart.

```java
package tz.co.nlolo.lifeplatform.regreporting;

import tz.co.nlolo.lifeplatform.regreporting.api.MetricKind;
import tz.co.nlolo.lifeplatform.regreporting.domain.MetricName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricNameTest {

    /** The design spec's §5 enumerates exactly seventeen metrics. If this number changes, the
     * spec's swappability claim ("any TIRA line that is one of the seventeen...") changes with
     * it and must be updated in the same commit -- which is the point of asserting it. */
    @Test
    void thereAreExactlySeventeenMetrics() {
        assertThat(MetricName.values()).hasSize(17);
    }

    @Test
    void onlyTheTwoInForceMetricsAreStock() {
        assertThat(java.util.Arrays.stream(MetricName.values())
                .filter(m -> m.kind() == MetricKind.STOCK).map(Enum::name).toList())
            .containsExactlyInAnyOrder("POLICIES_IN_FORCE", "SUM_ASSURED_IN_FORCE");
    }

    /** Every name must fit return_definition_line.metric_name / return_line.metric_name,
     * VARCHAR(40) in regreporting/V2. M7 shipped a CHECK admitting a value the column could not
     * store; this is the cheap guard against the same class of defect. */
    @Test
    void everyMetricNameFitsItsColumn() {
        for (MetricName m : MetricName.values()) {
            assertThat(m.name().length()).as("%s must fit VARCHAR(40)", m).isLessThanOrEqualTo(40);
        }
    }

    /** A money metric's value carries a currency; a count's does not. The REST layer and
     * return_line.currency both depend on this being decidable from the metric alone. */
    @Test
    void moneyMetricsAreDistinguishableFromCounts() {
        assertThat(MetricName.SUM_ASSURED_IN_FORCE.isMonetary()).isTrue();
        assertThat(MetricName.CLAIMS_SETTLED_AMOUNT.isMonetary()).isTrue();
        assertThat(MetricName.PREMIUM_COLLECTED.isMonetary()).isTrue();
        assertThat(MetricName.POLICIES_IN_FORCE.isMonetary()).isFalse();
        assertThat(MetricName.CLAIMS_SETTLED.isMonetary()).isFalse();
    }
}
```

- [ ] **Step 3: Run it and confirm it fails**

```bash
./mvnw -B -o test -Dtest=MetricNameTest
```
Expected: FAIL to compile — `MetricName` does not exist yet.

- [ ] **Step 4: Write `MetricName`**

```java
package tz.co.nlolo.lifeplatform.regreporting.domain;

import tz.co.nlolo.lifeplatform.regreporting.api.MetricKind;

/**
 * The seventeen metrics this module computes -- the complete, enumerated answer to "what can a
 * return line ask for?" (design spec §5).
 *
 * <p>This enum is the single point where the registry, the {@code metric_name} strings seeded into
 * {@code return_definition_line}, and Task 4's readers are held together. A definition naming
 * something absent from this enum must fail loudly at generation time, never emit a null line.
 *
 * <p>Deliberately NOT data. Making metric definitions a table (fact table + column + aggregation,
 * with SQL assembled from those values) was considered and rejected: over four fact tables with
 * these measures already exposed, it buys only "sum a column nobody asked for yet" without a
 * deploy, and it costs dynamically-assembled SQL and an injection surface on the one module a
 * REGULATOR can read, plus the loss of compile-time safety on every metric. See the spec's §5.
 */
public enum MetricName {

    POLICIES_IN_FORCE(MetricKind.STOCK, false),
    SUM_ASSURED_IN_FORCE(MetricKind.STOCK, true),

    POLICIES_ISSUED(MetricKind.FLOW, false),
    POLICIES_REINSTATED(MetricKind.FLOW, false),
    POLICIES_LAPSED(MetricKind.FLOW, false),
    POLICIES_MATURED(MetricKind.FLOW, false),
    POLICIES_CLAIM_TERMINATED(MetricKind.FLOW, false),
    NEW_BUSINESS_SUM_ASSURED(MetricKind.FLOW, true),

    CLAIMS_REGISTERED(MetricKind.FLOW, false),
    CLAIMS_APPROVED(MetricKind.FLOW, false),
    CLAIMS_REJECTED(MetricKind.FLOW, false),
    CLAIMS_SETTLED(MetricKind.FLOW, false),
    CLAIMS_APPROVED_AMOUNT(MetricKind.FLOW, true),
    CLAIMS_SETTLED_AMOUNT(MetricKind.FLOW, true),

    PREMIUM_COLLECTED(MetricKind.FLOW, true),

    REINSURANCE_CEDED_RISK(MetricKind.FLOW, true),
    REINSURANCE_CEDED_PREMIUM(MetricKind.FLOW, true);

    private final MetricKind kind;
    private final boolean monetary;

    MetricName(MetricKind kind, boolean monetary) {
        this.kind = kind;
        this.monetary = monetary;
    }

    public MetricKind kind() { return kind; }

    /** True when the value is money and therefore carries a currency; false for a count. */
    public boolean isMonetary() { return monetary; }
}
```

- [ ] **Step 5: Run the test and confirm it passes**

```bash
./mvnw -B -o test -Dtest=MetricNameTest
```
Expected: PASS, 4 tests.

- [ ] **Step 6: Write the `@IdClass` id types**

Read `reinsurance/domain/PolicyProjectionId.java` first for the exact shape this codebase uses (protected no-arg constructor, public all-args constructor, `equals`/`hashCode` over every field, `Serializable`). Then write eight, each matching its table's primary key from Task 1 exactly:

| Id class | Fields, in PK order |
|---|---|
| `PolicyDimensionId` | `UUID tenantId`, `String policyNumber` |
| `ClaimDimensionId` | `UUID tenantId`, `UUID claimId` |
| `PolicyMovementId` | `UUID tenantId`, `String period`, `UUID productId` |
| `ClaimsMovementId` | `UUID tenantId`, `String period`, `String claimType` |
| `PremiumMovementId` | `UUID tenantId`, `String period`, `UUID productId` |
| `ReinsuranceMovementId` | `UUID tenantId`, `String period` |
| `ReturnDefinitionId` | `UUID tenantId`, `String returnType` |
| `ReturnDefinitionLineId` | `UUID tenantId`, `String returnType`, `int lineNo` |

- [ ] **Step 7: Write the ten entities**

All in `regreporting/domain/`, `@Table(..., schema = "regreporting")`, mapping exactly the columns Task 1 created. Notes that matter:

- `PolicyMovement` gets the five gross count fields and both sum-assured fields, all defaulting to `0`, plus one mutator per cause. **These exact signatures are depended on by `CumulativeMetricTest` (Task 4) and all four listeners (Task 6):**
  ```java
  public PolicyMovement(UUID tenantId, String period, UUID productId, String currency);  // creates with all measures at 0
  public void applyIssued(BigDecimal sumAssured);           // policies_issued++, sum_assured_issued += sumAssured
  public void applyReinstated();                            // policies_reinstated++
  public void applyLapsed(BigDecimal sumAssured);           // policies_lapsed++, sum_assured_terminated += sumAssured
  public void applyMatured(BigDecimal sumAssured);          // policies_matured++, sum_assured_terminated += sumAssured
  public void applyClaimTerminated(BigDecimal sumAssured);  // policies_claim_terminated++, sum_assured_terminated += sumAssured
  ```
  Its javadoc must state that the net movement is **derived, never stored**, and why: a single signed delta cannot distinguish "10 issued, 3 lapsed" from "7 issued, 0 lapsed", and every prudential return needs the gross figures as separate lines.
- `ClaimsMovement` gets `applyRegistered()`, `applyApproved(BigDecimal)`, `applyRejected()`, `applySettled(BigDecimal)`.
- `PremiumMovement` gets `applyCollected(BigDecimal)`. `ReinsuranceMovement` gets `applyCeded(BigDecimal cededRisk, BigDecimal cededPremium)`.
- `RegulatoryReturn` uses `@Id @GeneratedValue UUID returnId` (this codebase's convention — see `reinsurance.Cession`), and `@Version Long version`. Its `status` field is a `String` always set to `"READY"`; javadoc records that `GENERATING` is unreachable under synchronous generation.
- `ReturnLine` uses `@Id @GeneratedValue UUID returnLineId` and a plain `UUID returnId` column (not a JPA relationship — the parent is written first, then its lines, the same shape `finaccounting.JournalEntry`/`GlPosting` uses for the same reason).
- Entity names: prefix none of them, but **verify** none collides with an existing `@Entity` name via the Step 1 check. `PolicyDimension` and `PolicyMovement` are new names platform-wide.

- [ ] **Step 8: Write the eight repositories**

```java
package tz.co.nlolo.lifeplatform.regreporting.infrastructure;

import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyDimension;
import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyDimensionId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PolicyDimensionRepository extends JpaRepository<PolicyDimension, PolicyDimensionId> {
    Optional<PolicyDimension> findByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);
}
```

The rest follow the same shape. Beyond the obvious finders, these specific queries are needed by Task 4 and must exist:

```java
// PolicyMovementRepository
Optional<PolicyMovement> findByTenantIdAndPeriodAndProductId(UUID tenantId, String period, UUID productId);
List<PolicyMovement> findByTenantIdAndPeriodLessThanEqual(UUID tenantId, String period);
List<PolicyMovement> findByTenantIdAndPeriod(UUID tenantId, String period);

// ClaimsMovementRepository
Optional<ClaimsMovement> findByTenantIdAndPeriodAndClaimType(UUID tenantId, String period, String claimType);
List<ClaimsMovement> findByTenantIdAndPeriod(UUID tenantId, String period);

// PremiumMovementRepository
Optional<PremiumMovement> findByTenantIdAndPeriodAndProductId(UUID tenantId, String period, UUID productId);
List<PremiumMovement> findByTenantIdAndPeriod(UUID tenantId, String period);

// ReinsuranceMovementRepository
Optional<ReinsuranceMovement> findByTenantIdAndPeriod(UUID tenantId, String period);

// RegulatoryReturnRepository
Optional<RegulatoryReturn> findByReturnIdAndTenantId(UUID returnId, UUID tenantId);
Optional<RegulatoryReturn> findByTenantIdAndReturnTypeAndPeriod(UUID tenantId, String returnType, String period);
List<RegulatoryReturn> findByTenantIdOrderByGeneratedAtDesc(UUID tenantId);
List<RegulatoryReturn> findByTenantIdAndPeriodOrderByGeneratedAtDesc(UUID tenantId, String period);

// ReturnLineRepository
List<ReturnLine> findByTenantIdAndReturnIdOrderByLineNoAsc(UUID tenantId, UUID returnId);
List<ReturnLine> findByTenantIdAndReturnIdInOrderByReturnIdAscLineNoAsc(UUID tenantId, Collection<UUID> returnIds);
void deleteByTenantIdAndReturnId(UUID tenantId, UUID returnId);

// ReturnDefinitionRepository
Optional<ReturnDefinition> findByTenantIdAndReturnType(UUID tenantId, String returnType);

// ReturnDefinitionLineRepository
List<ReturnDefinitionLine> findByTenantIdAndReturnTypeOrderByLineNoAsc(UUID tenantId, String returnType);
```

`findByTenantIdAndReturnIdIn...` exists so Task 7's `listReturns` batch-loads every return's lines in one query rather than N+1 — the exact defect M9's final review found in `finaccounting`'s equivalent list endpoint.

- [ ] **Step 9: Compile, re-run the test, commit**

```bash
./mvnw -B -o -q compile && ./mvnw -B -o test -Dtest=MetricNameTest
git add src/main/java/tz/co/nlolo/lifeplatform/regreporting/ src/test/java/tz/co/nlolo/lifeplatform/regreporting/
git commit -m "feat: regreporting domain -- dimension and gross-movement entities, return definitions, and the seventeen-metric enum"
```

---

### Task 4: `MetricReaderRegistry` — the cumulative-sum engine

**Files:**
- Create: `regreporting/infrastructure/MetricReaderRegistry.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/regreporting/CumulativeMetricTest.java`

**Interfaces:**
- Consumes: `MetricName`, `MetricKind`, the four movement repositories (Task 3).
- Produces: `MetricReaderRegistry.read(UUID tenantId, MetricName metric, String period, String dimensionFilter)` returning `BigDecimal`. Task 5's `ReturnGenerator` calls exactly this.

- [ ] **Step 1: Write the failing test**

This is the milestone's most important unit test: it is what proves the movements-plus-cumulative-sum decision actually works.

```java
package tz.co.nlolo.lifeplatform.regreporting;

import tz.co.nlolo.lifeplatform.regreporting.domain.MetricName;
import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyMovement;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.MetricReaderRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure unit tests over hand-built movement rows -- no Spring, no container. The cumulative-sum
 * semantics are the correctness core of this whole module, so they are tested where the arithmetic
 * is visible rather than only through a database.
 */
class CumulativeMetricTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    private static PolicyMovement movement(String period, int issued, int lapsed, String sumIssued, String sumTerminated) {
        PolicyMovement m = new PolicyMovement(TENANT, period, PRODUCT, "TZS");
        for (int i = 0; i < issued; i++) m.applyIssued(new BigDecimal(sumIssued));
        for (int i = 0; i < lapsed; i++) m.applyLapsed(new BigDecimal(sumTerminated));
        return m;
    }

    /** THE test for decision 4. Three periods of movements; a STOCK metric for the EARLIEST must
     * report that period's figure, not the latest. A regression to a running counter passes every
     * other test in this suite and fails this one. */
    @Test
    void aStockMetricForAnEarlierPeriodIgnoresLaterMovements() {
        List<PolicyMovement> all = List.of(
            movement("2026-Q1", 10, 0, "100.00", "0.00"),
            movement("2026-Q2", 5, 2, "100.00", "100.00"),
            movement("2026-Q3", 0, 3, "0.00", "100.00"));

        assertThat(MetricReaderRegistry.cumulativePolicyCount(all, "2026-Q1")).isEqualTo(10L);
        assertThat(MetricReaderRegistry.cumulativePolicyCount(all, "2026-Q2")).isEqualTo(13L);
        assertThat(MetricReaderRegistry.cumulativePolicyCount(all, "2026-Q3")).isEqualTo(10L);
    }

    /** A period with more terminations than issuances is a real business outcome, and the
     * cumulative figure must be allowed to fall. This is why return_line.numeric_value carries no
     * non-negativity CHECK even though every fact-table measure does. */
    @Test
    void aCumulativeFigureCanFallAndEvenGoNegative() {
        List<PolicyMovement> all = List.of(
            movement("2026-Q1", 2, 0, "100.00", "0.00"),
            movement("2026-Q2", 0, 5, "0.00", "100.00"));
        assertThat(MetricReaderRegistry.cumulativePolicyCount(all, "2026-Q2")).isEqualTo(-3L);
    }

    /** A FLOW metric is that period's own row and nothing else -- no accumulation. */
    @Test
    void aFlowMetricUsesOnlyItsOwnPeriod() {
        List<PolicyMovement> q2 = List.of(movement("2026-Q2", 5, 2, "100.00", "100.00"));
        assertThat(MetricReaderRegistry.sumIssued(q2)).isEqualTo(5L);
    }

    /** Sum assured accumulates as issued minus terminated, the money analogue of the count. */
    @Test
    void cumulativeSumAssuredNetsIssuedAgainstTerminated() {
        List<PolicyMovement> all = List.of(
            movement("2026-Q1", 2, 0, "1000.00", "0.00"),
            movement("2026-Q2", 1, 1, "1000.00", "1000.00"));
        assertThat(MetricReaderRegistry.cumulativeSumAssured(all, "2026-Q2"))
            .isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    /** Lexical comparison is what makes cumulative sums work over VARCHAR periods, and it is only
     * sound WITHIN one period kind -- which is why return_definition pins period_kind per return
     * type. Asserted so the assumption is visible rather than implicit. */
    @Test
    void quarterlyPeriodsSortLexically() {
        assertThat("2026-Q1".compareTo("2026-Q2")).isNegative();
        assertThat("2026-Q3".compareTo("2026-Q10")).isNegative();  // no Q10 exists; documents the limit
        assertThat("2025-Q4".compareTo("2026-Q1")).isNegative();
    }

    /** An empty projection is zero, not an exception -- a tenant with no activity in a period
     * legitimately reports zero rather than failing to generate a return at all. */
    @Test
    void anEmptyProjectionReadsAsZero() {
        assertThat(MetricReaderRegistry.cumulativePolicyCount(List.of(), "2026-Q1")).isEqualTo(0L);
        assertThat(MetricReaderRegistry.sumIssued(List.of())).isEqualTo(0L);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./mvnw -B -o test -Dtest=CumulativeMetricTest
```
Expected: FAIL to compile — `MetricReaderRegistry` does not exist.

- [ ] **Step 3: Write `MetricReaderRegistry`**

A `@Component` constructor-injecting the four movement repositories. Requirements:

- The static helpers the test calls must be **`public static`, and must operate on a supplied list** rather than fetching one, so the arithmetic is unit-testable without a database. Exact signatures, which `CumulativeMetricTest` depends on:
  ```java
  public static long cumulativePolicyCount(List<PolicyMovement> movements, String asOfPeriod);
  public static BigDecimal cumulativeSumAssured(List<PolicyMovement> movements, String asOfPeriod);
  public static long sumIssued(List<PolicyMovement> movements);
  ```
  **`public`, not package-private:** `CumulativeMetricTest` lives in `tz.co.nlolo.lifeplatform.regreporting` while this class lives in `tz.co.nlolo.lifeplatform.regreporting.infrastructure`, so package-private helpers would be inaccessible and the test would not compile. These are pure functions over their arguments with no state and no I/O, so exposing them costs nothing — the alternative (relocating the test into the `infrastructure` package purely for visibility, as M9 had to do for a genuinely package-private `postEntry`) would be contorting the test to fit an accidental modifier.

  The instance method `read(...)` fetches the movement list from the repositories and delegates to these.
- `read(UUID tenantId, MetricName metric, String period, String dimensionFilter)`:
  - For a `STOCK` metric, load `findByTenantIdAndPeriodLessThanEqual(tenantId, period)`; for `FLOW`, load `findByTenantIdAndPeriod(tenantId, period)`.
  - Apply `dimensionFilter` when non-null: a product id for policy/premium metrics, a claim type for claims metrics. An unparseable product-id filter is a `RegreportingValidationException`, not a crash.
  - `switch` exhaustively over all seventeen `MetricName` values. **No `default` branch** — an exhaustive switch over an enum means adding an eighteenth metric is a compile error here rather than a silent null at generation time. That is the point.
  - `POLICIES_IN_FORCE` = cumulative `(issued + reinstated − lapsed − matured − claimTerminated)`; `SUM_ASSURED_IN_FORCE` = cumulative `(sumAssuredIssued − sumAssuredTerminated)`.
  - Every return is a `BigDecimal` (counts included) so `ReturnLine.numericValue` has one type.

- [ ] **Step 4: Run the test and commit**

```bash
./mvnw -B -o test -Dtest='CumulativeMetricTest,MetricNameTest'
git add -A
git commit -m "feat: the metric reader registry -- cumulative sums for stock, period rows for flow"
```
Expected: 10 tests pass.

---

### Task 5: `ReturnGenerator` and `RegreportingApiImpl`

**Files:**
- Create: `regreporting/application/ReturnGenerator.java`, `RegreportingApiImpl.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/regreporting/ReturnGeneratorTest.java`, `src/test/java/tz/co/nlolo/lifeplatform/regreporting/application/RegreportingApiIntegrationTest.java`

**Interfaces:**
- Consumes: everything from Tasks 2-4.
- Produces: `RegreportingApi`'s implementation, plus `ReturnGenerator.generate(UUID tenantId, String returnType, String period, String generatedBy)` returning the persisted `RegulatoryReturn`.

- [ ] **Step 1: Write the failing `ReturnGeneratorTest` (pure unit, mocked registry)**

Assert: a definition's lines are resolved **in `line_no` order**; each line's `metric_name`, `line_code`, and `label` are copied onto the generated line; `currency` is set for a monetary metric and null for a count; and a definition naming a metric absent from `MetricName` throws `RegreportingValidationException` rather than emitting a null-valued line.

- [ ] **Step 2: Write the failing `RegreportingApiIntegrationTest`**

Real Postgres as `app_role`. Migration list: `audit/V1`, `refdata/V1`, `regreporting/V1`, `regreporting/V2`, applied via `MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), ...)`. Bootstrap `app_role` with `ALTER ROLE ... NOSUPERUSER NOBYPASSRLS` exactly as `ReinsuranceApiIntegrationTest` does — read that file for the harness.

**Note the migration list is short.** Unlike M9, `regreporting` has no partitioned table, so `policyloan/V2`'s partition-control trigger is irrelevant here. Do not copy M9's `policyloan` inclusion.

Assert:
1. `generateReturn("QUARTERLY_PRUDENTIAL", "2026-Q3", "tester")` writes one `regulatory_return` and ten `return_line` rows, readable back via `getReturn` in `line_no` order.
2. **Regenerating replaces rather than duplicates** — call it twice, assert still exactly one return and exactly ten lines, and that `generated_at`/`generated_by` were updated.
3. An unknown `returnType` throws `RegreportingValidationException`; the seeded `QUARTERLY_PRUDENTIAL` with an **annual** period (`"2026"`) also throws, because the definition's `period_kind` is `QUARTERLY`.
4. `getReturn` throws `ReturnNotFoundException` for an unknown id **and for a real return belonging to another tenant** (invisible under real RLS, not merely absent).
5. `listReturns(null)` returns every return; `listReturns("2026-Q3")` genuinely narrows — include a non-matching period and assert it is excluded.
6. Every generated line's `numeric_value` is `0` when no projections exist, and the return still generates successfully.

- [ ] **Step 3: Run both and confirm they fail**

```bash
./mvnw -B -o test -Dtest='ReturnGeneratorTest,RegreportingApiIntegrationTest'
```
Expected: FAIL — neither class exists.

- [ ] **Step 4: Write `ReturnGenerator`**

`@Component`, `@Transactional`. Requirements:

- Load the `ReturnDefinition`; absent → `RegreportingValidationException`.
- **Validate `period` against `periodKind`** before anything else: `QUARTERLY` requires `^\d{4}-Q[1-4]$`, `ANNUAL` requires `^\d{4}$`. A mismatch is `RegreportingValidationException`. This is what stops an annual and a quarterly period being cumulative-summed together.
- Upsert the `regulatory_return` row for `(tenant, returnType, period)`: reuse the existing one if present (updating `generatedAt`/`generatedBy`) and **delete its existing lines** via `deleteByTenantIdAndReturnId` before writing new ones. Regeneration must replace, never accumulate.
- For each `ReturnDefinitionLine` in `line_no` order: resolve `metric_name` to `MetricName` (`IllegalArgumentException` from `valueOf` → rethrow as `RegreportingValidationException` naming the bad metric), call `MetricReaderRegistry.read(...)`, and write a `ReturnLine` carrying the definition's `line_code`/`label`/`metric_name`, the value, and a currency only when `metric.isMonetary()`.
- Set `status` to `"READY"`. Never write `documentRef`.

- [ ] **Step 5: Write `RegreportingApiImpl`**

`@Service implements RegreportingApi`. `generateReturn` delegates to `ReturnGenerator` then maps to a view. All three methods are tenant-scoped via `TenantContext.get()`. `listReturns` **batch-loads every return's lines in one query** using `findByTenantIdAndReturnIdInOrderByReturnIdAscLineNoAsc` and groups by `returnId` — not one query per return, which is the N+1 defect M9's final review found in `finaccounting`'s equivalent endpoint.

- [ ] **Step 6: Run and commit**

```bash
./mvnw -B -o test -Dtest='ReturnGeneratorTest,RegreportingApiIntegrationTest,CumulativeMetricTest,MetricNameTest'
git add -A
git commit -m "feat: synchronous return generation -- definitions resolved through the metric registry, regeneration replaces"
```

---

### Task 6: The four projection listeners

**Files:**
- Create: `regreporting/application/PolicyEventListener.java`, `ClaimsEventListener.java`, `BillingEventListener.java`, `ReinsuranceEventListener.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/regreporting/MissingDimensionTest.java`

**Interfaces:**
- Consumes: the dimension and movement repositories (Task 3).
- Produces: the populated read model every metric reads.

- [ ] **Step 1: Check bean-name collisions, then write the listeners**

```bash
for c in PolicyEventListener ClaimsEventListener BillingEventListener ReinsuranceEventListener; do
  echo "$c: $(find src/main/java -name "$c.java" | wc -l) existing"
done
```

`PolicyEventListener` will be **non-zero** — `distribution`, `reinsurance`, and `finaccounting` each already declare one. That is expected and is exactly why every listener here takes an explicit bean name. Use exactly these four:

```java
@Component("regreportingPolicyEventListener")
@Component("regreportingClaimsEventListener")
@Component("regreportingBillingEventListener")
@Component("regreportingReinsuranceEventListener")
```

Read `reinsurance/application/PolicyEventListener.java` in full first and copy its mechanics exactly: `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`, one reusable `PROPAGATION_REQUIRES_NEW` `TransactionTemplate` built from an injected `PlatformTransactionManager`, a `withTenant` helper doing `TenantContext.getOrNull()` → `set` → `try` → `finally` restore-or-clear, and a catch-all incrementing `lifeplatform_regreporting_event_processing_failed_total` (Task 6 Step 5) and logging at ERROR.

Event handling, with the period derived as `YearMonth`-equivalent quarter (`"2026-Q3"`) from the event's own business timestamp where it carries one, else `LocalDate.now()`:

| Listener | Events | Effect |
|---|---|---|
| `regreportingPolicyEventListener` | `policy.PolicyIssued` | upsert `policy_dimension`; `policy_movement.applyIssued(sumAssured)` |
| | `policy.PolicyLapsed` | look up dimension; `applyLapsed(sumAssured)` |
| | `policy.PolicyMatured` | look up dimension; `applyMatured(sumAssured)` |
| | `policy.PolicySurrendered` | look up dimension; **`applyClaimTerminated(sumAssured)`** — this event fires for a settled claim, not a policyholder surrender |
| | `policy.PolicyReinstated` | look up dimension; `applyReinstated()` |
| `regreportingClaimsEventListener` | `claims.ClaimRegistered` | upsert `claim_dimension`; `claims_movement.applyRegistered()` |
| | `claims.ClaimApproved` | look up dimension for `claimType`; `applyApproved(approvedAmount)` |
| | `claims.ClaimRejected` | look up dimension; `applyRejected()` |
| | `claims.ClaimSettled` | look up dimension; `applySettled(settledAmount)` |
| `regreportingBillingEventListener` | `billing.PremiumCollected` | resolve `policyNumber` → `productId` via `policy_dimension`; `premium_movement.applyCollected(amount)` |
| `regreportingReinsuranceEventListener` | `reinsurance.CessionRecorded` | `reinsurance_movement.applyCeded(cededAmount, cededPremium)` — `cededPremium` is nullable in the payload, treat null as zero |

**`policy.PolicySuspended` is deliberately handled by nothing.** A suspended policy is still in force. Record this in `regreportingPolicyEventListener`'s javadoc so the omission reads as a decision.

**The `UNKNOWN` sentinel.** When a dimension lookup misses, attribute the movement to `UUID(0,0)` for a product or the string `"UNKNOWN"` for a claim type, and log at WARN. **Never skip the movement** — a silently-vanishing financial figure is strictly worse than a visibly-unattributed one. `claim_dimension.claim_type`'s CHECK does not admit `"UNKNOWN"`, so a claims movement with a missing dimension writes `claims_movement.claim_type = 'UNKNOWN'` (that table has no CHECK on the column, deliberately — verify against Task 1's DDL) while writing no dimension row.

- [ ] **Step 2: Write `MissingDimensionTest`**

Real Postgres, same harness as Task 5's integration test. Publish a `policy.PolicyLapsed` envelope for a policy number that was **never issued** (so no dimension row exists), and assert: a `policy_movement` row exists for the `UUID(0,0)` sentinel product with `policies_lapsed = 1`, and the total lapse count is not zero. Then do the same for `claims.ClaimSettled` with an unknown claim id and assert a `claims_movement` row under `claim_type = 'UNKNOWN'`. The point is that the figure survives.

- [ ] **Step 3: Run and commit**

```bash
./mvnw -B -o test -Dtest='MissingDimensionTest,RegreportingApiIntegrationTest'
git add -A
git commit -m "feat: four regreporting projection listeners, with a sentinel rather than a dropped movement"
```

- [ ] **Step 4: Write `ProjectionEndToEndTest` against the REAL producer chains**

Migration list must cover every module whose events are driven: `audit/V1`, `refdata/V1`-`V4`, `party/V1`, `product/V1`, `underwriting/V1`, `policy/V1`-`V4`, `billing/V1`-`V3`, `claims/V1`-`V3`, `payment/V1`-`V4`, `reinsurance/V1`-`V2`, `regreporting/V1`-`V2`. Copy `ClaimSettlementEndToEndTest`'s WireMock harness for the claim settlement path (settlement goes through `payment`'s request/confirm loop).

Drive real APIs, not hand-published envelopes: issue a real policy via `PolicyApi`, collect a real premium via `BillingApi.applyConfirmedPayment`, register and settle a real claim via `ClaimsApi`, and let `reinsurance` record its own cession off the real `PolicyIssued`. Then assert the `policy_dimension`, `claim_dimension`, `policy_movement`, `claims_movement`, `premium_movement`, and `reinsurance_movement` rows all exist with the right values, and that generating a return produces non-zero lines for `POLICIES_IN_FORCE`, `PREMIUM_COLLECTED`, and `CLAIMS_SETTLED`.

- [ ] **Step 5: Write `HistoricalPeriodReturnTest`**

The end-to-end counterpart to `CumulativeMetricTest`'s unit proof. Seed `policy_movement` rows directly for three consecutive quarters (`2026-Q1`, `2026-Q2`, `2026-Q3`), generate a return for **`2026-Q1`**, and assert its `POLICIES_IN_FORCE` line reports Q1's figure — not Q3's. Then generate for `2026-Q3` and assert the cumulative figure differs. Without this test, the movements-versus-snapshot decision is unverified against a real database.

- [ ] **Step 6: Add the metric, its alert rule, and its producer registration**

`lifeplatform_regreporting_event_processing_failed_total`, incremented in every listener's catch block. Add a rule to `observability/alert-rules.yml` copying the shape and severity-comment style of `FinaccountingEventProcessingFailed` (M9's equivalent), **and** add the metric name to `AlertRuleMetricProducerTest`'s `PRODUCED_BY_THIS_APPLICATION` map — that test fails otherwise, deliberately, because a metric with no rule or a rule with no producer is silent.

The rule's description must say what makes this one consequential *and* what makes it different from `finaccounting`'s: a swallowed exception here means the read model has silently drifted from the business facts, so a return generated afterwards will be quietly wrong rather than obviously broken — and because projections are derived, the remedy is to replay or rebuild, not to hand-correct a ledger.

```bash
./mvnw -B -o test -Dtest='AlertRuleMetricProducerTest,ProjectionEndToEndTest,HistoricalPeriodReturnTest'
git add -A
git commit -m "feat: alertable counter for a regreporting projection that fails to apply"
```

---

### Task 7: REST layer, OpenAPI extraction and exception handler

**Files:**
- Create: `regreporting/infrastructure/RegreportingExceptionHandler.java`, `MoneyDto.java`, `ReturnLineResponseDto.java`, `RegulatoryReturnResponseDto.java`, `RegulatoryReturnController.java`
- Create: `api/openapi/openapi-regreporting.yaml`
- Modify: `api/openapi/openapi-regreporting-document-refdata.yaml`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/regreporting/RegreportingSpecParsesTest.java`

- [ ] **Step 1: Extract the OpenAPI document**

`api/openapi/openapi-regreporting-document-refdata.yaml` holds three `---`-separated OpenAPI documents and its own header says to split them before tooling use. Move the **first** block (`Regulatory Reporting (TIRA) API`) into `api/openapi/openapi-regreporting.yaml` as a standalone document, and delete that block plus its trailing `---` from the old file, leaving `document` and `refdata` intact. Update the old file's header comment to say it now holds two documents, not three.

Then bring the extracted spec in line with what actually ships: `POST /regulatory-returns` returns **`201`** with the generated return (not `202`), `GET /regulatory-returns` is added, and every response schema gets a `required` list, `$ref`s into `openapi-common.yaml` for `Money`/`ProblemDetails`, and 401/403/404/422 where reachable. Keep the placeholder warning in `info.description` and state that `documentRef` is always null pending C2.

**Quote every description containing a comma** — an unquoted flow-style description broke a whole contract test's spec load in M4.

- [ ] **Step 2: Write `RegreportingSpecParsesTest`**

Copy `reinsurance/ReinsuranceSpecParsesTest.java` exactly, changing the spec path to `api/openapi/openapi-regreporting.yaml` and asserting `containsKeys("/regulatory-returns", "/regulatory-returns/{returnId}")`. Container-free, `ParseOptions.setResolve(true)`.

- [ ] **Step 3: Run it and confirm it fails, then passes**

```bash
./mvnw -B -o test -Dtest=RegreportingSpecParsesTest
```
Expected first: FAIL (file absent, or messages non-empty if the extraction left a stray `---`). Then PASS after Step 1 is correct.

- [ ] **Step 4: Write the exception handler**

Copy `reinsurance/infrastructure/ReinsuranceExceptionHandler.java`'s shape — `@RestControllerAdvice` + `@Order(Ordered.HIGHEST_PRECEDENCE)`, a private `problem(...)` helper setting both `errorCode` and `traceId` (`openapi-common.yaml` marks `traceId` required). Map exactly two:

| Exception | Status | errorCode |
|---|---|---|
| `ReturnNotFoundException` | 404 | `RETURN_NOT_FOUND` |
| `RegreportingValidationException` | 422 | `REGREPORTING_VALIDATION_FAILED` |

Do **not** map `IllegalArgumentException` or `AccessDeniedException` — `GlobalExceptionHandler` already maps them to 400 and 403, and a duplicate advice is the shadowing bug `@Order` exists to prevent.

- [ ] **Step 5: Write `MoneyDto` and the response DTOs**

`MoneyDto` — copy `reinsurance/infrastructure/MoneyDto.java` verbatim (pattern `^-?\\d+(\\.\\d{1,2})?$`; note the leading `-?` matters here, because a cumulative in-force figure can legitimately be negative).

`ReturnLineResponseDto` and `RegulatoryReturnResponseDto`, each with a static `from(view)`. A line's value serialises as a `MoneyDto` when the metric is monetary and as a plain integer-valued decimal string otherwise — never a raw `BigDecimal` and never a JSON number.

- [ ] **Step 6: Write `RegulatoryReturnController`**

Check for URL collisions first and report the output:

```bash
grep -rn '"/regulatory-returns' --include=*.java src/main/java | grep -v regreporting/infrastructure
```
Expected: no output.

Three endpoints, with these exact gates:

```java
// POST /regulatory-returns
@PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")

// GET /regulatory-returns          (optional ?period=)
// GET /regulatory-returns/{returnId}
@PreAuthorize("(hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))) or hasRole('REALM_REGULATORS')")
```

The class javadoc must record three things: that `FINANCE_OFFICER`/`ADMIN` is a **decision, not a spec quote** (no `COMPLIANCE_OFFICER` role exists — `docs/04-api-contracts.md:44`), the same call M7/M8/M9 each made; that this is **`REALM_REGULATORS`' first use anywhere on the platform**; and that regulators are necessarily **tenant-scoped** because `TenantContextFilter` 403s any token lacking a `tenant_id` claim, so cross-tenant regulatory access is out of scope by design rather than by omission.

- [ ] **Step 7: Run and commit**

```bash
./mvnw -B -o test -Dtest='RegreportingSpecParsesTest,RegreportingApiIntegrationTest'
git add -A
git commit -m "feat: the regreporting REST layer, its extracted OpenAPI contract, and the first REALM_REGULATORS endpoint"
```

---

### Task 8: Contract tests

**Files:**
- Test: `src/test/java/tz/co/nlolo/lifeplatform/regreporting/RegreportingContractTest.java`

- [ ] **Step 1: Write the contract test**

Follow `FinaccountingContractTest`'s structure exactly: `@Testcontainers` + `@AutoConfigureMockMvc` + `@SpringBootTest(classes = Application.class, webEnvironment = MOCK)`, `MigrationTestSupport.applyMigration(...)` with `audit/V1`, `refdata/V1`, `regreporting/V1`-`V2`, and `openApi().isValid(SPEC_PATH)` **paired with** `SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "<Schema>")` on every decimal-carrying response.

Token shapes — copy `financeStaffOf`/`underwriterStaffOf`/`agentOf` verbatim from `FinaccountingContractTest:120-138`, and add the one that does not exist anywhere on this platform yet:

```java
/** REALM_REGULATORS' first use in any test. Regulators carry NO fine-grained role claims --
 * SecurityConfig only synthesises ROLE_REALM_<REALM> for them, because docs/04-api-contracts.md
 * defines role names for staff alone. The tenant_id claim is mandatory even here:
 * TenantContextFilter 403s any token without one, which is exactly what makes a regulator
 * tenant-scoped rather than cross-tenant. */
private static RequestPostProcessor regulatorOf(UUID tenantId) {
    return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_REGULATORS"))
        .jwt(builder -> builder.subject("tira-regulator").claim("tenant_id", tenantId.toString()));
}
```

One test per reachable status:
- `POST /regulatory-returns`: 201 with ten lines for `financeStaffOf`; 422 for an unknown return type; 422 for a period whose format contradicts the definition's `period_kind`; 403 for `underwriterStaffOf`; 403 for `agentOf`; **403 for `regulatorOf`** — a regulator must never be able to generate a return.
- `GET /regulatory-returns`: 200 for `financeStaffOf`; **200 for `regulatorOf`**; the `period` filter genuinely narrowing (assert a non-matching value returns empty); 403 for `agentOf`.
- `GET /regulatory-returns/{returnId}`: 200 with lines in `line_no` order for both `financeStaffOf` and `regulatorOf`; 404 for an unknown id; **404 (not 403) for a cross-tenant id** — a 403 would confirm the id exists elsewhere.

Seed returns by calling `RegreportingApi.generateReturn` directly (the endpoint under test is the read path), so every 403 has a real target and a broken `@PreAuthorize` returns 200 rather than an incidental 404.

- [ ] **Step 2: Run and commit**

```bash
./mvnw -B -o test -Dtest=RegreportingContractTest
git add -A
git commit -m "test: regreporting contract tests, including the platform's first regulator-token coverage"
```

---

### Task 9: Guardrail coverage and doc reconciliation

**Files:**
- Modify: `src/test/java/tz/co/nlolo/lifeplatform/AppRolePrivilegesIntegrationTest.java`, `RowLevelSecurityIntegrationTest.java`
- Modify: `docs/06-database-schema.md`, `docs/05-event-catalog.md`

- [ ] **Step 1: Add `regreporting` to `AppRolePrivilegesIntegrationTest`**

Read the file in full first; **append only**, changing no existing test, migration entry, or assertion. Add `regreporting/V1` and `V2` to its migration list (after `finaccounting/V2`, matching `scripts/migrate.sh`'s module order).

Then add a test proving, through the app's own `DataSource` (which connects as `app_role`, not the migration superuser): `app_role` can INSERT and SELECT a `policy_movement` row, a `return_definition_line` row, and a `return_line` row. Unlike `finaccounting`, **nothing in `regreporting` is append-only** — projections are upserted and returns are regenerated — so this test asserts UPDATE and DELETE **succeed**, which is the opposite of the append-only assertions M9 added. State that contrast in the test's javadoc so a future reader does not "fix" it into a REVOKE.

- [ ] **Step 2: Add `regreporting` to `RowLevelSecurityIntegrationTest`**

Append the same two migrations, then add a test at the next unused `@Order` number (read the file to find it — do not assume). Seed a `policy_dimension` + `policy_movement` + `regulatory_return` + `return_line` row in each of two tenants as the superuser; assert both tenants' rows are visible without RLS (the negative control proving the seed worked); then read through a genuinely restricted `app_role` connection (`SET ROLE app_role; SET app.current_tenant_id = '<tenantA>'`) and assert only tenant A's rows are visible on **all four** tables.

- [ ] **Step 3: Reconcile the docs**

`docs/06-database-schema.md`:
- Add `regreporting.regulatory_return` to the optimistic-locking list (V2 added `version`), noting the projection tables are excluded because they are derived and idempotently upserted, not concurrently-edited aggregates.
- Add a line recording that `regreporting`'s schema is now real: the read model and return generation ship in M10, while the return **catalog** remains C2-blocked. Note that `policy_in_force_summary` was renamed to `policy_movement` in V2 and why (it stores movements, not a snapshot).

`docs/05-event-catalog.md`:
- Update the consumer annotations for `policy.PolicyIssued`/`Lapsed`/`Matured`/`Surrendered`/`Reinstated`, `claims.ClaimRegistered`/`Approved`/`Rejected`/`Settled`, `billing.PremiumCollected`, and `reinsurance.CessionRecorded` to record that `regreporting` now genuinely consumes them.
- Record that `regreporting` does **not** consume `finaccounting.GlPostingRecorded` despite `:30` listing it as a consumer, and why: the event carries no account codes, so only a coarse control total is derivable from it. Deferred, not forgotten.

- [ ] **Step 4: Run the FULL suite and commit**

```bash
# FULL SUITE REQUIRED (Global Constraints trigger 2): this task changes
# AppRolePrivilegesIntegrationTest and RowLevelSecurityIntegrationTest, which every module leans on.
./mvnw -B -o test
git add -A
git commit -m "test: regreporting guardrail coverage, and doc reconciliation for the new read model"
```

---

### Task 10: Full verification

- [ ] **Step 1: Full clean verify**

```bash
./mvnw -B -o clean verify
```

Expected `BUILD SUCCESS`, including `ModularityTests` (confirming `regreporting`'s `allowedDependencies` is still exactly `{ refdata::api }` while it consumes events from four modules it must never call), `NoCircularDependencyTest`, `NoCrossModuleJoinTest`, `AlertRuleMetricProducerTest`, `ActuatorExposureTest`. Confirm those five classes actually ran by grepping the surefire reports for their names, rather than assuming `clean verify` covered them.

Aggregate the real counts rather than trusting the log tail:

```bash
grep -h "Tests run:" target/surefire-reports/*.txt | awk -F'[:,]' '{t+=$2; f+=$4; e+=$6} END {print "Total:", t, "Failures:", f, "Errors:", e}'
```

- [ ] **Step 2: Mirror the CI `db-migration-validation` job**

Apply every module's migrations in `scripts/migrate.sh`'s order against a fresh `postgres:16` (read the file; cross-check against `.github/workflows/ci-cd.yml`'s own job rather than assuming they agree, and report it as a finding if they have diverged), then run `db-migrations/_post-migration/verify-partition-controls.sql` and confirm zero drift. Re-run Task 1's privilege query and confirm all ten `regreporting` tables still report `rls=t has_policy=t sel=t ins=t`.

- [ ] **Step 3: Confirm the scope boundaries held**

```bash
echo "=== regreporting must not depend on any transactional module ==="
grep -rn "import tz.co.nlolo.lifeplatform.\(policy\|claims\|billing\|reinsurance\|finaccounting\|policyloan\|payment\|distribution\|document\)\." \
  src/main/java/tz/co/nlolo/lifeplatform/regreporting/ || echo "clean"
echo "=== no scheduler was introduced ==="
grep -rn "@Scheduled" src/main/java/tz/co/nlolo/lifeplatform/regreporting/ || echo "clean"
ls db-migrations/_post-migration/ | grep -i regreporting || echo "no regreporting cron config -- correct"
echo "=== document_ref is never written ==="
grep -rn "documentRef\|document_ref" src/main/java/tz/co/nlolo/lifeplatform/regreporting/ | grep -vi "null\|C2\|never\|javadoc\|\*"
```

Expected: `clean` for the imports and `@Scheduled`; no cron config; and no assignment to `documentRef` beyond the view field and its comments. Any hit is a scope-boundary breach and must be reported prominently, not quietly accepted.

- [ ] **Step 4: Acceptance-criteria confirmation**

Confirm against `docs/08-implementation-roadmap.md:183`, naming the specific tests and real counts (read `target/surefire-reports/*.txt`, do not estimate):
1. **Reporting read-model infrastructure** → two dimension tables and four movement fact tables, four listeners, proven against real producer chains by `ProjectionEndToEndTest`
2. **Generic report-generation mechanism** → `return_definition`/`return_definition_line` as data plus `MetricName`/`MetricReaderRegistry` as code, with the boundary exactly as the spec's §5 states it
3. **Placeholder return format that's easy to swap** → one seeded `QUARTERLY_PRUDENTIAL` definition, ten flagged placeholder lines
4. **Correct for historical periods** → `CumulativeMetricTest` (unit) and `HistoricalPeriodReturnTest` (real database)
5. **"Scheduling" specifically** → state plainly that the generation mechanism a scheduler would drive is delivered and the scheduler is **not**, because this platform has no tenant-directory table and cannot enumerate tenants from a background thread under fail-closed RLS (spec §11). This is a platform constraint, not a scoping choice — do not report it as simply "out of scope".
6. **Explicitly NOT claimed:** a TIRA-compliant return. Every line code and label is invented, no submission path exists, and `document_ref` is unwritten.

- [ ] **Step 5: Report and stop**

Do not merge. Report the aggregate count, the acceptance mapping, every deviation, and every deferred item, so the final whole-branch review has an accurate baseline.

---

## Self-Review Notes

**Judgment calls flagged for the final review — each a place where this plan chose a side.**

1. **Every return line code and label is invented** (V2's seed), pending C2. Ten lines chosen to exercise all four fact tables, not because TIRA asks for them.
2. **`policy_in_force_summary` is RENAMED, not left alongside a new table.** This reshapes a table V1 already shipped. Justified because the old name actively misdescribes movement data and its `product_id NOT NULL` was unfillable from events — but it is the one place M10 mutates existing shipped schema rather than adding.
3. **`regulatory_return.status` keeps an unreachable `GENERATING` value.** V1 is immutable and dropping a CHECK value isn't worth a migration. The consequence is a small permanent lie in the schema, documented via `COMMENT ON COLUMN`.
4. **Only `POLICIES_IN_FORCE` and `SUM_ASSURED_IN_FORCE` are stock metrics.** Everything else is a period flow. If a real TIRA return wants, say, cumulative claims paid since inception, that's a new metric (code), not a definition change.
5. **`reinsurance_movement` is not attributed by product**, to avoid an event-ordering race. It is the one fact table with a coarser grain than its neighbours, and a TIRA line wanting ceded premium *by product* would need the ordering problem solved first.
6. **The `UNKNOWN` sentinel uses `UUID(0,0)` for products and the literal `"UNKNOWN"` for claim types.** Both are magic values. Chosen over dropping the movement or failing the listener, but they will appear in returns as an unattributed bucket, and nothing yet alerts on that bucket being non-empty.
7. **Period is a string compared lexically.** Sound within one period kind, which `period_kind` enforces — but `CumulativeMetricTest` documents that a hypothetical `2026-Q10` would sort wrongly. Not reachable with four quarters.
8. **`MetricReaderRegistry.read` switches exhaustively with no `default`.** Deliberate: an eighteenth metric becomes a compile error rather than a silent null. Costs a slightly noisier switch.
9. **No period locking.** A movement can land in a period whose return was already generated; the remedy is regeneration, and nothing detects the drift automatically.
10. **The metric-definitions-as-data alternative was rejected** (see `MetricName`'s javadoc and spec §5) — injection surface on a regulator-readable module, and loss of compile-time safety, for a narrow gain. Recorded so it reads as considered rather than overlooked.
11. **Guardrail assertions are INVERTED relative to M9's.** `regreporting` is not append-only, so Task 9 asserts UPDATE/DELETE *succeed*. A reviewer pattern-matching on M9's append-only tests will think this is wrong; the javadoc says why it isn't.
