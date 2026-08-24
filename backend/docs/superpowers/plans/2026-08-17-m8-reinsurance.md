# M8 — Reinsurance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `reinsurance` module — treaty configuration, automatic cession (risk and premium) on new business, and claim recovery tracking — per `docs/superpowers/specs/2026-08-17-m8-reinsurance-design.md`.

**Architecture:** `reinsurance` is a thick Spring Modulith module depending on `refdata::api` and **nothing else**. Both business triggers arrive as events: `policy.PolicyIssued` drives cession, an enriched `claims.ClaimSettled` drives recovery. Because `PolicyApi` is unreachable, the module keeps its own `policy_projection` (policyNumber → sumAssured, premium, issueDate) built solely from `PolicyIssued` — the same design `distribution` uses for the identical constraint.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith, Postgres 16 (schema-per-module + RLS), Testcontainers, WireMock, Micrometer/Prometheus.

---

## Global Constraints

Every task's requirements implicitly include this section.

- **`reinsurance`'s `allowedDependencies` MUST stay exactly `{ "refdata::api" }`** (`docs/02-module-architecture.md:175`, and the existing `package-info.java`). Do **not** add `policy::api`, `claims::api`, or `product::api` — `ModularityTests` enforces this and the whole projection design exists because of it.
- **`reinsurance/api/package-info.java` is currently MISSING `@NamedInterface("api")`** — verified: the file contains only a bare `package` declaration. Add it (Task 3), exactly as M7 had to for `distribution`.
- **Editing `claims` to enrich ONE event is pre-authorized** (Task 6, spec decision 2). Scope: `claims/application/PaymentEventListener.java`'s single `claims.ClaimSettled` publish site only.
- **Build commands.** Always on the host, in the FOREGROUND, never inside Docker (it breaks Testcontainers networking):
  ```bash
  export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
  ./mvnw -B -o test -Dtest=SomeSpecificTest      # the normal case
  ./mvnw -B -o test                              # only when the rule below says so
  ```
- **Run the NARROWEST test set that could detect a regression from your change.** The full suite is 500 tests in ~10 minutes and most of that is container/Spring-context setup unrelated to your change.

  **Run the FULL suite only when your task does at least one of these:**
  1. changes production code **outside** `reinsurance` (Task 6 touches `claims`);
  2. changes a **shared test class or utility** (Task 9 edits `AppRolePrivilegesIntegrationTest` and `RowLevelSecurityIntegrationTest`, which every module's correctness leans on);
  3. changes **shared config** — `pom.xml`, `application.yml`, `SecurityConfig`;
  4. adds a migration some existing test's own migration list already applies;
  5. is the final verification task (Task 10).

  **Never report coverage you did not execute.** If you ran a subset, say so plainly and say why. If a targeted run surfaces anything unexpected, escalate to the full suite immediately rather than assuming it is unrelated.
- **Baseline before M8: 500 tests, 0 failures, 0 errors.** Quote the count from whatever you actually ran and label it (`full suite` vs `-Dtest=X`).
- **Never edit an already-applied migration.** `reinsurance/V1` and every other module's existing `V*.sql` are immutable; add new numbered files.
- **Cross-module references are opaque columns, never FKs** (`docs/06-database-schema.md:37`). `cession.policy_number` and `claim_recovery.claim_id` have no FK. Intra-module FKs (both tables → `reinsurance_treaty`) are fine and already exist.
- **Money on the wire is a decimal STRING, never a float** (`docs/06-database-schema.md:32`). This applies to `cessionPercent` too — a percent multiplies money exactly as a commission rate does, and M6 fixed this exact class of bug for `impairmentPercent`.
- **Event payload money shape is always** `Map.of("amount", <BigDecimal>.toPlainString(), "currencyCode", <String>)`. Use `toPlainString()`, never `toString()`.
- **`Map.of` throws NPE on a null value.** Use `LinkedHashMap` where any field is nullable.
- **An `AFTER_COMMIT` `@TransactionalEventListener` MUST use a `PROPAGATION_REQUIRES_NEW` `TransactionTemplate`.** A plain `@Transactional` (REQUIRED) call from an `AFTER_COMMIT` callback silently joins the already-committed producer transaction and never commits — empirically confirmed on this project with `TransactionRequiredException`. Copy `distribution/application/PolicyEventListener.java`.
- **Bean names must be explicit.** Four modules already declare a `PolicyEventListener` (`billing`, `distribution`, plus this one). Use `@Component("reinsurancePolicyEventListener")` and `@Component("reinsuranceClaimEventListener")` or a context-startup bean-name collision fails the whole suite.
- **Column widths must be checked against their CHECK vocabularies in the same migration.** M7 shipped a CHECK admitting `PAYOUT_REQUESTED` (16 chars) into a `VARCHAR(15)` column, making the entire payout path unwritable while every test stayed green. Verify every new enum column.
- **Contract tests pair `openApi().isValid(...)` with `SpecTypeConformance.matchesDeclaredTypes(specPath, schemaName)`.** `isValid` does **not** enforce primitive JSON types (measured). Use both on every response carrying a decimal.
- **Every nested-resource path must verify its ids belong together.** M7's final review found a same-tenant IDOR where `GET /agents/{agentId}/.../{statementId}/accruals` checked only the caller's access to `agentId` and trusted `statementId`. Any endpoint here with two ids in the path has the same obligation.

---

## File Structure

**New — `reinsurance` module:**
- `db-migrations/reinsurance/V2__grants_rls_money_checks_reinsurer_and_projection.sql`
- `reinsurance/api/` — `ReinsuranceApi`, `TreatyView`, `CessionView`, `ClaimRecoveryView`, `TreatyType`, `TreatyStatus`, `TreatyNotFoundException`, `RecoveryNotFoundException`, `ReinsuranceValidationException`, `InvalidRecoveryStateException`, `package-info.java` (add `@NamedInterface`)
- `reinsurance/domain/` — `ReinsuranceTreaty`, `Cession`, `ClaimRecovery`, `PolicyProjection`, `PolicyProjectionId`, `CessionCalculator`, `RecoveryCalculator`
- `reinsurance/infrastructure/` — 4 repositories, `TreatyController`, `RecoveryController`, DTOs, `ReinsuranceExceptionHandler`
- `reinsurance/application/` — `ReinsuranceApiImpl`, `PolicyEventListener`, `ClaimEventListener`
- `api/openapi/openapi-reinsurance.yaml` (new)
- Tests — `CessionCalculatorTest`, `RecoveryCalculatorTest`, `TreatySelectionTest`, `ReinsuranceApiIntegrationTest`, `CessionEndToEndTest`, `RecoveryEndToEndTest`, `ReinsuranceContractTest`, `ReinsuranceSpecParsesTest`

**Modified — other modules (each minimal and justified):**
- `claims/application/PaymentEventListener.java` — enrich `ClaimSettled` (one publish site)
- `api/asyncapi-events.yaml`, `docs/05-event-catalog.md`, `docs/06-database-schema.md`
- `src/test/java/.../AppRolePrivilegesIntegrationTest.java`, `RowLevelSecurityIntegrationTest.java`

---

### Task 1: `reinsurance/V2` — grants, RLS, money guards, reinsurer, projection

**Files:**
- Create: `db-migrations/reinsurance/V2__grants_rls_money_checks_reinsurer_and_projection.sql`

**Interfaces:**
- Produces: the `reinsurance` schema every later task maps entities onto — tables `reinsurance_treaty` (+ `reinsurer_name`, `status`, `version`, audit columns), `cession` (+ `ceded_premium_amount`, `ceded_premium_currency`), `claim_recovery` (+ `version`), and new `policy_projection`.

- [ ] **Step 1: Write the migration**

Create the file with exactly this content:

```sql
-- Module: reinsurance V2 -- M8 Task 1.
--
-- V1 shipped the same defect set this project has now caught in five consecutive
-- modules (claims, payment, billing, policyloan, distribution): RLS enabled on
-- only ONE of its three tables, ZERO grants to app_role anywhere in the file, no
-- optimistic locking, no money guards, and no uniqueness on either financial
-- record -- so a redelivered event would silently duplicate a cession.
-- =============================================================================
-- 1. Grants. V1 grants app_role nothing at all, so the application's real
--    runtime role could not read or write this schema. Every other module's V1
--    had this same hole; AppRolePrivilegesIntegrationTest (Task 9) is the guard.
-- =============================================================================
GRANT USAGE ON SCHEMA reinsurance TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA reinsurance TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA reinsurance GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;

-- =============================================================================
-- 2. RLS on the two tables V1 left unprotected. V1 enabled it on
--    reinsurance_treaty only -- cession and claim_recovery both carry tenant_id
--    NOT NULL and had no policy at all, so app_role could read every tenant's
--    ceded amounts and recoveries.
-- =============================================================================
ALTER TABLE reinsurance.cession ENABLE ROW LEVEL SECURITY;
CREATE POLICY cession_tenant_isolation ON reinsurance.cession
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE reinsurance.claim_recovery ENABLE ROW LEVEL SECURITY;
CREATE POLICY claim_recovery_tenant_isolation ON reinsurance.claim_recovery
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- =============================================================================
-- 3. Missing tenant indexes (docs/06-database-schema.md:25 requires tenant_id
--    indexed on every tenant-scoped table; V1 indexed only the treaty's).
-- =============================================================================
CREATE INDEX idx_cession_tenant ON reinsurance.cession (tenant_id);
CREATE INDEX idx_claim_recovery_tenant ON reinsurance.claim_recovery (tenant_id);

-- =============================================================================
-- 4. Optimistic locking on the two aggregate roots with real concurrent-write
--    exposure. `cession` is deliberately excluded: it is written exactly once
--    per (policy, treaty) and never mutated, so a version column would be dead
--    weight. docs/06's own list is amended to match in Task 9.
-- =============================================================================
ALTER TABLE reinsurance.reinsurance_treaty ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE reinsurance.claim_recovery ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- =============================================================================
-- 5. Audit columns (docs/06's convention: created_by/updated_at/updated_by on
--    every mutable table). V1 has created_at only.
-- =============================================================================
ALTER TABLE reinsurance.reinsurance_treaty ADD COLUMN created_by VARCHAR(100);
ALTER TABLE reinsurance.reinsurance_treaty ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE reinsurance.reinsurance_treaty ADD COLUMN updated_by VARCHAR(100);
ALTER TABLE reinsurance.claim_recovery ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE reinsurance.claim_recovery ADD COLUMN updated_by VARCHAR(100);

-- =============================================================================
-- 6. The reinsurer. V1 models a treaty with NO counterparty whatsoever -- no
--    name, no reference -- which makes a treaty row meaningless as a contract.
--    A plain name, not a party FK: `party` models no reinsurer party type, and
--    `reinsurance` cannot call `party` synchronously (Global Constraints).
--    200 chars comfortably fits a legal entity name; VARCHAR(100) is this
--    platform's convention for person-scale names and would be tight here.
--
--    NOT NULL with a DEFAULT then dropped: no rows exist in practice (nothing
--    has ever written this table), but a bare NOT NULL add would fail if one
--    did, and silently succeeding on an empty table is exactly the kind of
--    thing that breaks on the first real deployment that has data.
-- =============================================================================
ALTER TABLE reinsurance.reinsurance_treaty ADD COLUMN reinsurer_name VARCHAR(200) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE reinsurance.reinsurance_treaty ALTER COLUMN reinsurer_name DROP DEFAULT;

-- =============================================================================
-- 7. Treaty status. Selection (Task 4) must consider only ACTIVE treaties, and
--    V1 has no way to retire one without deleting the row -- which would orphan
--    every cession that referenced it via FK.
--
--    VARCHAR(20) against a longest value of 'EXPIRED' (7). Deliberately checked
--    rather than assumed: M7 shipped a CHECK admitting a 16-character value
--    into a VARCHAR(15) column, making a whole payout path unwritable while
--    every test stayed green. V1's own treaty_type VARCHAR(15) is also verified
--    here -- longest value 'QUOTA_SHARE' is 11 -- and needs no widening.
-- =============================================================================
ALTER TABLE reinsurance.reinsurance_treaty
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE','EXPIRED'));

-- =============================================================================
-- 8. Money guards. V1 has none, so a treaty could carry a negative retention
--    and a cession a zero or negative ceded amount.
--
--    Note the asymmetry, which is deliberate: retention_limit_amount >= 0
--    (a zero retention means "cede everything", a legitimate quota-share
--    arrangement), but ceded and recoverable amounts must be strictly > 0
--    (a zero-value financial record is not a record -- the calculators return
--    empty rather than a zero row, see Tasks 4 and 5).
-- =============================================================================
ALTER TABLE reinsurance.reinsurance_treaty
    ADD CONSTRAINT treaty_retention_non_negative CHECK (retention_limit_amount >= 0);
ALTER TABLE reinsurance.cession
    ADD CONSTRAINT cession_ceded_amount_positive CHECK (ceded_amount > 0);
ALTER TABLE reinsurance.claim_recovery
    ADD CONSTRAINT recovery_amount_positive CHECK (recoverable_amount > 0);

-- cession_percent is meaningful only for QUOTA_SHARE, and must be a real
-- percentage when present. NUMERIC(5,2) holds 100.00 fine (max 999.99).
ALTER TABLE reinsurance.reinsurance_treaty
    ADD CONSTRAINT treaty_cession_percent_range
    CHECK (cession_percent IS NULL OR (cession_percent > 0 AND cession_percent <= 100));

-- QUOTA_SHARE cannot compute a cession without a percent; the other two types
-- must not carry one (SURPLUS cedes by retention, XOL does not cede at issuance).
ALTER TABLE reinsurance.reinsurance_treaty
    ADD CONSTRAINT treaty_cession_percent_required_for_quota_share
    CHECK ((treaty_type = 'QUOTA_SHARE' AND cession_percent IS NOT NULL)
        OR (treaty_type <> 'QUOTA_SHARE' AND cession_percent IS NULL));

-- =============================================================================
-- 9. Ceded premium (spec decision, added at review). A quota-share treaty cedes
--    PREMIUM as well as risk, and finaccounting (M9) needs it for IFRS 17
--    ceded-business entries. policy.PolicyIssued already carries `premium`, so
--    the input exists today and retrofitting later would mean re-deriving every
--    cession already written.
--
--    Nullable and PAIRED: ceded risk is the primary fact, and amount+currency
--    travel together or not at all -- the same paired-nullability CHECK
--    distribution.commission_rule uses for its flat amount.
-- =============================================================================
ALTER TABLE reinsurance.cession ADD COLUMN ceded_premium_amount NUMERIC(19,2);
ALTER TABLE reinsurance.cession ADD COLUMN ceded_premium_currency CHAR(3);
ALTER TABLE reinsurance.cession
    ADD CONSTRAINT cession_ceded_premium_positive
    CHECK (ceded_premium_amount IS NULL OR ceded_premium_amount > 0);
ALTER TABLE reinsurance.cession
    ADD CONSTRAINT cession_ceded_premium_paired
    CHECK ((ceded_premium_amount IS NULL) = (ceded_premium_currency IS NULL));

-- =============================================================================
-- 10. Idempotency. V1 has NO uniqueness on either financial table, so a
--     redelivered PolicyIssued or ClaimSettled -- at-least-once delivery is the
--     platform's stated contract -- would write a SECOND cession or recovery for
--     the same facts, double-counting ceded risk and recoverables. These indexes
--     are the real backstop; the application-layer checks in Tasks 4 and 5 are a
--     convenience early-return, not the source of truth.
-- =============================================================================
CREATE UNIQUE INDEX ux_cession_once ON reinsurance.cession (tenant_id, policy_number, treaty_id);
CREATE UNIQUE INDEX ux_recovery_once ON reinsurance.claim_recovery (tenant_id, claim_id, treaty_id);

-- =============================================================================
-- 11. policy_projection -- reinsurance's OWN state, not a cache of policy's.
--
--     Why it must exist: `reinsurance` may call only `refdata` synchronously
--     (docs/02:175), so PolicyApi is unreachable. Cession needs the policy's sum
--     assured AND premium; recovery needs to resolve a claim's policy to its
--     cession. Built solely from policy.PolicyIssued.
--
--     Deliberately NOT reconciled against `policy` -- that would need the
--     forbidden dependency. A policy issued before M8 has no row here, so it
--     cedes nothing and recovers nothing; that is correct, since those policies
--     predate reinsurance tracking entirely. Log and move on, never throw.
-- =============================================================================
CREATE TABLE reinsurance.policy_projection (
    policy_number       VARCHAR(20) NOT NULL,
    tenant_id            UUID NOT NULL,
    product_id            UUID NOT NULL,
    sum_assured_amount     NUMERIC(19,2) NOT NULL CHECK (sum_assured_amount > 0),
    sum_assured_currency    CHAR(3) NOT NULL,
    premium_amount           NUMERIC(19,2) NOT NULL CHECK (premium_amount > 0),
    premium_currency          CHAR(3) NOT NULL,
    issue_date                 DATE NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, policy_number)
);
CREATE INDEX idx_reinsurance_projection_tenant ON reinsurance.policy_projection (tenant_id);

ALTER TABLE reinsurance.policy_projection ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_projection_tenant_isolation ON reinsurance.policy_projection
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON reinsurance.policy_projection TO app_role;
```

- [ ] **Step 2: Apply it against a disposable Postgres and verify — do NOT verify by inspection**

```bash
docker rm -f m8verify >/dev/null 2>&1
docker run -d --name m8verify -e POSTGRES_PASSWORD=pw postgres:16 >/dev/null
until docker exec m8verify pg_isready -U postgres -q; do sleep 1; done
docker exec m8verify psql -U postgres -q -c "CREATE ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'p';"
export MSYS_NO_PATHCONV=1
for f in db-migrations/reinsurance/V1__create_reinsurance_schema.sql \
         db-migrations/reinsurance/V2__grants_rls_money_checks_reinsurer_and_projection.sql; do
  docker cp "$f" m8verify:/tmp/m.sql >/dev/null
  docker exec m8verify psql -U postgres -v ON_ERROR_STOP=1 -f /tmp/m.sql -q || echo "FAILED $f"
done
docker exec m8verify psql -U postgres -c "
SELECT c.relname, c.relrowsecurity AS rls,
       EXISTS(SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid) AS has_policy,
       has_table_privilege('app_role', c.oid, 'SELECT') AS sel,
       has_table_privilege('app_role', c.oid, 'UPDATE') AS upd
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'reinsurance' AND c.relkind = 'r' ORDER BY c.relname;"
```

Expected: **four rows** (`cession`, `claim_recovery`, `policy_projection`, `reinsurance_treaty`), every one `rls=t has_policy=t sel=t upd=t`.

- [ ] **Step 3: Prove the new CHECKs actually reject bad data**

Each of these must FAIL. Run them and paste the real errors into your report:

```bash
# A QUOTA_SHARE treaty with no percent
docker exec m8verify psql -U postgres -c "
INSERT INTO reinsurance.reinsurance_treaty (tenant_id, treaty_type, retention_limit_amount, effective_from, reinsurer_name)
VALUES (gen_random_uuid(), 'QUOTA_SHARE', 1000.00, CURRENT_DATE, 'Test Re');"
# A SURPLUS treaty WITH a percent
docker exec m8verify psql -U postgres -c "
INSERT INTO reinsurance.reinsurance_treaty (tenant_id, treaty_type, retention_limit_amount, cession_percent, effective_from, reinsurer_name)
VALUES (gen_random_uuid(), 'SURPLUS', 1000.00, 30.00, CURRENT_DATE, 'Test Re');"
# A percent above 100
docker exec m8verify psql -U postgres -c "
INSERT INTO reinsurance.reinsurance_treaty (tenant_id, treaty_type, retention_limit_amount, cession_percent, effective_from, reinsurer_name)
VALUES (gen_random_uuid(), 'QUOTA_SHARE', 1000.00, 150.00, CURRENT_DATE, 'Test Re');"
```

Expected: `treaty_cession_percent_required_for_quota_share`, the same constraint again, then `treaty_cession_percent_range`.

- [ ] **Step 4: Clean up and commit**

```bash
docker rm -f m8verify
git add db-migrations/reinsurance/
git commit -m "feat: complete the reinsurance schema with grants, RLS, money guards, a reinsurer and a projection"
```

---

### Task 2: The `reinsurance` API surface — enums, views, exceptions

**Files:**
- Create: `src/main/java/tz/co/nlolo/lifeplatform/reinsurance/api/TreatyType.java`, `TreatyStatus.java`, `TreatyView.java`, `CessionView.java`, `ClaimRecoveryView.java`, `TreatyNotFoundException.java`, `RecoveryNotFoundException.java`, `ReinsuranceValidationException.java`, `InvalidRecoveryStateException.java`, `ReinsuranceApi.java`
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/reinsurance/api/package-info.java`

**Interfaces:**
- Produces: every type later tasks depend on. Exact signatures below — Tasks 4-8 reference these names verbatim.

- [ ] **Step 1: Add the missing named interface**

`reinsurance/api/package-info.java` currently contains only `package tz.co.nlolo.lifeplatform.reinsurance.api;`. Replace with:

```java
@org.springframework.modulith.NamedInterface("api")
package tz.co.nlolo.lifeplatform.reinsurance.api;
```

Without this, any future module declaring `reinsurance::api` fails Modulith verification confusingly — the same gap `distribution` had entering M7.

- [ ] **Step 2: Write the enums**

`TreatyType.java`:
```java
package tz.co.nlolo.lifeplatform.reinsurance.api;

/**
 * Matches {@code reinsurance_treaty.treaty_type}'s CHECK (reinsurance/V1).
 *
 * <p><b>XOL is accepted by the schema and by treaty authoring, but cedes NOTHING at
 * issuance</b> -- excess-of-loss is a claim-level treaty, so "automatic cession calculation on
 * new business" does not apply to it. It participates only in recovery, where it recovers the
 * excess of a loss over retention. See {@code CessionCalculator} and {@code RecoveryCalculator}.
 */
public enum TreatyType { QUOTA_SHARE, SURPLUS, XOL }
```

`TreatyStatus.java`:
```java
package tz.co.nlolo.lifeplatform.reinsurance.api;

/** Matches {@code reinsurance_treaty.status}'s CHECK (reinsurance/V2 section 7). Only ACTIVE
 * treaties are eligible for cession selection. */
public enum TreatyStatus { ACTIVE, EXPIRED }
```

- [ ] **Step 3: Write the views**

`TreatyView.java`:
```java
package tz.co.nlolo.lifeplatform.reinsurance.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Read view of {@code reinsurance.domain.ReinsuranceTreaty}. {@code cessionPercent} is non-null
 * exactly for QUOTA_SHARE (V2's {@code treaty_cession_percent_required_for_quota_share}). */
public record TreatyView(UUID treatyId, String reinsurerName, TreatyType treatyType, TreatyStatus status,
                          BigDecimal retentionLimitAmount, String retentionLimitCurrency,
                          BigDecimal cessionPercent, LocalDate effectiveFrom, LocalDate effectiveTo) {}
```

`CessionView.java`:
```java
package tz.co.nlolo.lifeplatform.reinsurance.api;

import java.math.BigDecimal;
import java.util.UUID;

/** Read view of {@code reinsurance.domain.Cession}. {@code cededPremiumAmount}/{@code
 * cededPremiumCurrency} are non-null together or null together (V2's {@code
 * cession_ceded_premium_paired}); both are null for a treaty type that cedes risk without a
 * modelled premium share. */
public record CessionView(UUID cessionId, String policyNumber, UUID treatyId,
                           BigDecimal cededAmount, String cededCurrency,
                           BigDecimal cededPremiumAmount, String cededPremiumCurrency) {}
```

`ClaimRecoveryView.java`:
```java
package tz.co.nlolo.lifeplatform.reinsurance.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read view of {@code reinsurance.domain.ClaimRecovery}. {@code confirmedAt} is null until staff
 * confirm the reinsurer actually paid. */
public record ClaimRecoveryView(UUID recoveryId, UUID claimId, UUID treatyId,
                                 BigDecimal recoverableAmount, String recoverableCurrency,
                                 Instant confirmedAt) {}
```

- [ ] **Step 4: Write the exceptions**

Four files, each following `distribution/api`'s exact shape (a single `String message` constructor):

```java
package tz.co.nlolo.lifeplatform.reinsurance.api;

/** 404 at the REST boundary (ReinsuranceExceptionHandler, Task 7). */
public class TreatyNotFoundException extends RuntimeException {
    public TreatyNotFoundException(String message) { super(message); }
}
```

Repeat verbatim for `RecoveryNotFoundException` (404), `ReinsuranceValidationException` (422), and `InvalidRecoveryStateException` (409), changing only the class name and the javadoc's status code.

- [ ] **Step 5: Write the API interface**

```java
package tz.co.nlolo.lifeplatform.reinsurance.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The `reinsurance` module's public surface: treaty authoring (staff/finance-only) plus the read
 * paths and the one imperative action -- confirming a recovery -- that back-office staff need.
 *
 * <p>Cession and recovery CALCULATION is not here: both are event-driven
 * ({@code PolicyEventListener}, {@code ClaimEventListener}), because `reinsurance` may call only
 * `refdata` synchronously and therefore learns about policies and claims exclusively by event.
 */
public interface ReinsuranceApi {

    record CreateTreatyRequest(String reinsurerName, TreatyType treatyType,
                                BigDecimal retentionLimitAmount, String retentionLimitCurrency,
                                BigDecimal cessionPercent, LocalDate effectiveFrom, LocalDate effectiveTo) {}

    TreatyView createTreaty(CreateTreatyRequest request, String createdBy);
    TreatyView getTreaty(UUID treatyId);
    List<TreatyView> listTreaties(TreatyStatus status);

    List<CessionView> listCessionsForPolicy(String policyNumber);
    List<ClaimRecoveryView> listRecoveriesForClaim(UUID claimId);

    /** Stamps {@code confirmed_at} and publishes {@code reinsurance.RecoveryConfirmed} -- but only
     * on the genuine transition, so a repeated call emits no second event. */
    ClaimRecoveryView confirmRecovery(UUID recoveryId, String confirmedBy);
}
```

- [ ] **Step 6: Compile and commit**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -o -q compile
git add src/main/java/tz/co/nlolo/lifeplatform/reinsurance/api/
git commit -m "feat: reinsurance API surface -- views, enums, exceptions and the named interface"
```

Expected: `compile` exits 0. No tests yet — this task adds no behaviour, only types the next tasks implement against.

---

### Task 3: Domain entities and repositories

**Files:**
- Create: `reinsurance/domain/ReinsuranceTreaty.java`, `Cession.java`, `ClaimRecovery.java`, `PolicyProjection.java`, `PolicyProjectionId.java`
- Create: `reinsurance/infrastructure/ReinsuranceTreatyRepository.java`, `CessionRepository.java`, `ClaimRecoveryRepository.java`, `PolicyProjectionRepository.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/reinsurance/ClaimRecoveryStateMachineTest.java`

**Interfaces:**
- Consumes: `TreatyType`, `TreatyStatus` (Task 2).
- Produces: `ReinsuranceTreaty` (getters for every column, `isActiveOn(LocalDate)`, `expire()`), `Cession`, `ClaimRecovery` (`confirm(Instant, String)`, `isConfirmed()`), `PolicyProjection`, and the four repositories with the exact query methods Tasks 4-8 call.

- [ ] **Step 1: Write the failing state-machine test**

`ClaimRecoveryStateMachineTest.java` — pure unit test, no Spring, no container:

```java
package tz.co.nlolo.lifeplatform.reinsurance;

import tz.co.nlolo.lifeplatform.reinsurance.api.InvalidRecoveryStateException;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ClaimRecovery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClaimRecoveryStateMachineTest {

    private static ClaimRecovery newRecovery() {
        return new ClaimRecovery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            new BigDecimal("5000.00"), "TZS", "system:test");
    }

    @Test
    void aFreshRecoveryIsUnconfirmed() {
        ClaimRecovery recovery = newRecovery();
        assertThat(recovery.isConfirmed()).isFalse();
        assertThat(recovery.getConfirmedAt()).isNull();
    }

    @Test
    void confirmingStampsTheTimestampAndActor() {
        ClaimRecovery recovery = newRecovery();
        Instant when = Instant.parse("2026-03-01T10:00:00Z");
        recovery.confirm(when, "finance-officer");
        assertThat(recovery.isConfirmed()).isTrue();
        assertThat(recovery.getConfirmedAt()).isEqualTo(when);
        assertThat(recovery.getUpdatedBy()).isEqualTo("finance-officer");
    }

    /** The caller (ReinsuranceApiImpl, Task 7) must be able to tell a real transition from a
     * repeat, because RecoveryConfirmed may be published only on the former -- M6's I1 finding.
     * Throwing rather than silently returning makes a double-confirm a 409, not a phantom success. */
    @Test
    void confirmingTwiceIsRejected() {
        ClaimRecovery recovery = newRecovery();
        recovery.confirm(Instant.now(), "finance-officer");
        assertThrows(InvalidRecoveryStateException.class,
            () -> recovery.confirm(Instant.now(), "finance-officer-2"));
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./mvnw -B -o test -Dtest=ClaimRecoveryStateMachineTest
```
Expected: FAIL to compile — `ClaimRecovery` does not exist yet.

- [ ] **Step 3: Write `ReinsuranceTreaty`**

```java
package tz.co.nlolo.lifeplatform.reinsurance.domain;

import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyStatus;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Maps {@code reinsurance.reinsurance_treaty} (V1 + V2). */
@Entity
@Table(name = "reinsurance_treaty", schema = "reinsurance")
public class ReinsuranceTreaty {

    @Id
    @GeneratedValue
    @Column(name = "treaty_id")
    private UUID treatyId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "reinsurer_name", nullable = false)
    private String reinsurerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "treaty_type", nullable = false)
    private TreatyType treatyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TreatyStatus status = TreatyStatus.ACTIVE;

    @Column(name = "retention_limit_amount", nullable = false)
    private BigDecimal retentionLimitAmount;

    @Column(name = "retention_limit_currency", nullable = false)
    private String retentionLimitCurrency;

    /** Non-null exactly for QUOTA_SHARE -- V2's treaty_cession_percent_required_for_quota_share. */
    @Column(name = "cession_percent")
    private BigDecimal cessionPercent;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected ReinsuranceTreaty() {}

    public ReinsuranceTreaty(UUID tenantId, String reinsurerName, TreatyType treatyType,
                              BigDecimal retentionLimitAmount, String retentionLimitCurrency,
                              BigDecimal cessionPercent, LocalDate effectiveFrom, LocalDate effectiveTo,
                              String createdBy) {
        this.tenantId = tenantId;
        this.reinsurerName = reinsurerName;
        this.treatyType = treatyType;
        this.retentionLimitAmount = retentionLimitAmount;
        this.retentionLimitCurrency = retentionLimitCurrency;
        this.cessionPercent = cessionPercent;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.createdBy = createdBy;
    }

    /** ACTIVE and its effective window covers {@code date}. An open-ended treaty (null
     * effectiveTo) never expires by date. Used by Task 4's selection. */
    public boolean isActiveOn(LocalDate date) {
        return status == TreatyStatus.ACTIVE
            && !effectiveFrom.isAfter(date)
            && (effectiveTo == null || !effectiveTo.isBefore(date));
    }

    public void expire(String expiredBy) {
        this.status = TreatyStatus.EXPIRED;
        this.updatedAt = Instant.now();
        this.updatedBy = expiredBy;
    }

    public UUID getTreatyId() { return treatyId; }
    public UUID getTenantId() { return tenantId; }
    public String getReinsurerName() { return reinsurerName; }
    public TreatyType getTreatyType() { return treatyType; }
    public TreatyStatus getStatus() { return status; }
    public BigDecimal getRetentionLimitAmount() { return retentionLimitAmount; }
    public String getRetentionLimitCurrency() { return retentionLimitCurrency; }
    public BigDecimal getCessionPercent() { return cessionPercent; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
```

- [ ] **Step 4: Write `Cession`**

```java
package tz.co.nlolo.lifeplatform.reinsurance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code reinsurance.cession}. Write-once: a cession records what was ceded at issuance and
 * is never mutated afterwards, which is why V2 deliberately gives it no {@code version} column.
 * {@code ux_cession_once} on (tenant, policy, treaty) is what makes a redelivered PolicyIssued a
 * no-op rather than a double-count.
 */
@Entity
@Table(name = "cession", schema = "reinsurance")
public class Cession {

    @Id
    @GeneratedValue
    @Column(name = "cession_id")
    private UUID cessionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Opaque ref into `policy` -- never an FK (docs/06:37). */
    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "treaty_id", nullable = false)
    private UUID treatyId;

    @Column(name = "ceded_amount", nullable = false)
    private BigDecimal cededAmount;

    @Column(name = "ceded_currency", nullable = false)
    private String cededCurrency;

    /** Null together with its currency when a treaty cedes risk without a modelled premium
     * share -- V2's cession_ceded_premium_paired. */
    @Column(name = "ceded_premium_amount")
    private BigDecimal cededPremiumAmount;

    @Column(name = "ceded_premium_currency")
    private String cededPremiumCurrency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Cession() {}

    public Cession(UUID tenantId, String policyNumber, UUID treatyId, BigDecimal cededAmount,
                    String cededCurrency, BigDecimal cededPremiumAmount, String cededPremiumCurrency) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.treatyId = treatyId;
        this.cededAmount = cededAmount;
        this.cededCurrency = cededCurrency;
        this.cededPremiumAmount = cededPremiumAmount;
        this.cededPremiumCurrency = cededPremiumCurrency;
    }

    public UUID getCessionId() { return cessionId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getTreatyId() { return treatyId; }
    public BigDecimal getCededAmount() { return cededAmount; }
    public String getCededCurrency() { return cededCurrency; }
    public BigDecimal getCededPremiumAmount() { return cededPremiumAmount; }
    public String getCededPremiumCurrency() { return cededPremiumCurrency; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 5: Write `ClaimRecovery`**

```java
package tz.co.nlolo.lifeplatform.reinsurance.domain;

import tz.co.nlolo.lifeplatform.reinsurance.api.InvalidRecoveryStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Maps {@code reinsurance.claim_recovery}. Mutable exactly once, via {@link #confirm}. */
@Entity
@Table(name = "claim_recovery", schema = "reinsurance")
public class ClaimRecovery {

    @Id
    @GeneratedValue
    @Column(name = "recovery_id")
    private UUID recoveryId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Opaque ref into `claims` -- never an FK. */
    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Column(name = "treaty_id", nullable = false)
    private UUID treatyId;

    @Column(name = "recoverable_amount", nullable = false)
    private BigDecimal recoverableAmount;

    @Column(name = "recoverable_currency", nullable = false)
    private String recoverableCurrency;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected ClaimRecovery() {}

    public ClaimRecovery(UUID tenantId, UUID claimId, UUID treatyId, BigDecimal recoverableAmount,
                          String recoverableCurrency, String createdBy) {
        this.tenantId = tenantId;
        this.claimId = claimId;
        this.treatyId = treatyId;
        this.recoverableAmount = recoverableAmount;
        this.recoverableCurrency = recoverableCurrency;
        this.updatedBy = createdBy;
    }

    /**
     * Throws rather than returning quietly on a repeat, deliberately. The caller must be able to
     * distinguish a real transition from a double-confirm, because {@code
     * reinsurance.RecoveryConfirmed} may be published ONLY on the former -- M6's I1 finding, where
     * an unconditional publish after an idempotent transition emitted a duplicate event to
     * finaccounting, and a duplicate there is a double journal entry. A 409 is also the honest
     * answer to a human clicking confirm twice.
     */
    public void confirm(Instant when, String confirmedBy) {
        if (confirmedAt != null) {
            throw new InvalidRecoveryStateException(
                "Recovery " + recoveryId + " was already confirmed at " + confirmedAt);
        }
        this.confirmedAt = when;
        this.updatedAt = Instant.now();
        this.updatedBy = confirmedBy;
    }

    public boolean isConfirmed() { return confirmedAt != null; }

    public UUID getRecoveryId() { return recoveryId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getClaimId() { return claimId; }
    public UUID getTreatyId() { return treatyId; }
    public BigDecimal getRecoverableAmount() { return recoverableAmount; }
    public String getRecoverableCurrency() { return recoverableCurrency; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
```

- [ ] **Step 6: Write `PolicyProjection` and `PolicyProjectionId`**

`PolicyProjectionId.java` — copy `distribution/domain/PolicyProjectionId.java` verbatim, changing only the package to `tz.co.nlolo.lifeplatform.reinsurance.domain` and the javadoc's migration reference to `reinsurance/V2 section 11`.

`PolicyProjection.java`:
```java
package tz.co.nlolo.lifeplatform.reinsurance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps {@code reinsurance.policy_projection} (V2 section 11) -- reinsurance's OWN state, not a
 * cache of policy's. `policy` is not in this module's allowedDependencies, so this projection
 * (built solely from {@code policy.PolicyIssued}) is how reinsurance learns a policy's sum assured
 * and premium without ever calling {@code PolicyApi}.
 *
 * <p>Composite primary key {@code (tenant_id, policy_number)} -- see {@link PolicyProjectionId}.
 */
@Entity
@Table(name = "policy_projection", schema = "reinsurance")
@IdClass(PolicyProjectionId.class)
public class PolicyProjection {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "sum_assured_amount", nullable = false)
    private BigDecimal sumAssuredAmount;

    @Column(name = "sum_assured_currency", nullable = false)
    private String sumAssuredCurrency;

    @Column(name = "premium_amount", nullable = false)
    private BigDecimal premiumAmount;

    @Column(name = "premium_currency", nullable = false)
    private String premiumCurrency;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PolicyProjection() {}

    public PolicyProjection(UUID tenantId, String policyNumber, UUID productId,
                             BigDecimal sumAssuredAmount, String sumAssuredCurrency,
                             BigDecimal premiumAmount, String premiumCurrency, LocalDate issueDate) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.productId = productId;
        this.sumAssuredAmount = sumAssuredAmount;
        this.sumAssuredCurrency = sumAssuredCurrency;
        this.premiumAmount = premiumAmount;
        this.premiumCurrency = premiumCurrency;
        this.issueDate = issueDate;
    }

    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getProductId() { return productId; }
    public BigDecimal getSumAssuredAmount() { return sumAssuredAmount; }
    public String getSumAssuredCurrency() { return sumAssuredCurrency; }
    public BigDecimal getPremiumAmount() { return premiumAmount; }
    public String getPremiumCurrency() { return premiumCurrency; }
    public LocalDate getIssueDate() { return issueDate; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 7: Write the four repositories**

Every method is tenant-scoped — RLS is the backstop, not the only guard, per this project's convention.

```java
package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyStatus;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReinsuranceTreatyRepository extends JpaRepository<ReinsuranceTreaty, UUID> {
    Optional<ReinsuranceTreaty> findByTreatyIdAndTenantId(UUID treatyId, UUID tenantId);
    List<ReinsuranceTreaty> findByTenantIdOrderByEffectiveFromDesc(UUID tenantId);
    List<ReinsuranceTreaty> findByTenantIdAndStatusOrderByEffectiveFromDesc(UUID tenantId, TreatyStatus status);
}
```

```java
package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.domain.Cession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CessionRepository extends JpaRepository<Cession, UUID> {
    List<Cession> findByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);
    boolean existsByTenantIdAndPolicyNumberAndTreatyId(UUID tenantId, String policyNumber, UUID treatyId);
}
```

```java
package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.domain.ClaimRecovery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimRecoveryRepository extends JpaRepository<ClaimRecovery, UUID> {
    Optional<ClaimRecovery> findByRecoveryIdAndTenantId(UUID recoveryId, UUID tenantId);
    List<ClaimRecovery> findByTenantIdAndClaimId(UUID tenantId, UUID claimId);
    boolean existsByTenantIdAndClaimIdAndTreatyId(UUID tenantId, UUID claimId, UUID treatyId);
}
```

```java
package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.domain.PolicyProjection;
import tz.co.nlolo.lifeplatform.reinsurance.domain.PolicyProjectionId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PolicyProjectionRepository extends JpaRepository<PolicyProjection, PolicyProjectionId> {
    Optional<PolicyProjection> findByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);
}
```

- [ ] **Step 8: Run the test and confirm it passes**

```bash
./mvnw -B -o test -Dtest=ClaimRecoveryStateMachineTest
```
Expected: PASS, 3 tests.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/reinsurance/ src/test/java/tz/co/nlolo/lifeplatform/reinsurance/
git commit -m "feat: reinsurance domain entities, repositories and the recovery state machine"
```

---

### Task 4: `CessionCalculator` — the cession algorithm, as a pure function

**Files:**
- Create: `reinsurance/domain/CessionCalculator.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/reinsurance/CessionCalculatorTest.java`

**Interfaces:**
- Consumes: `ReinsuranceTreaty`, `TreatyType` (Tasks 2-3).
- Produces: `CessionCalculator.calculate(ReinsuranceTreaty, BigDecimal sumAssured, String sumAssuredCurrency, BigDecimal premium, String premiumCurrency)` returning `Optional<CessionCalculator.CededAmounts>`, where `CededAmounts` is `record CededAmounts(BigDecimal cededRisk, String riskCurrency, BigDecimal cededPremium, String premiumCurrency)`. Task 6's listener calls exactly this.

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.reinsurance;

import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.domain.CessionCalculator;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CessionCalculatorTest {

    private static ReinsuranceTreaty treaty(TreatyType type, String retention, String percent) {
        return new ReinsuranceTreaty(UUID.randomUUID(), "Test Re", type,
            new BigDecimal(retention), "TZS",
            percent == null ? null : new BigDecimal(percent),
            LocalDate.now().minusYears(1), null, "actuary");
    }

    @Test
    void quotaShareCedesTheSamePercentOfRiskAndPremium() {
        // 30% of a 2,000,000 sum assured and of a 100,000 premium.
        Optional<CessionCalculator.CededAmounts> result = CessionCalculator.calculate(
            treaty(TreatyType.QUOTA_SHARE, "0", "30.00"),
            new BigDecimal("2000000.00"), "TZS", new BigDecimal("100000.00"), "TZS");

        assertThat(result).isPresent();
        assertThat(result.get().cededRisk()).isEqualByComparingTo("600000.00");
        assertThat(result.get().cededPremium()).isEqualByComparingTo("30000.00");
        assertThat(result.get().riskCurrency()).isEqualTo("TZS");
        assertThat(result.get().premiumCurrency()).isEqualTo("TZS");
    }

    @Test
    void surplusCedesOnlyTheExcessOverRetentionAndPremiumInProportion() {
        // Retention 500,000 against a 2,000,000 sum assured -> cede 1,500,000, i.e. 75% of the
        // risk, so 75% of the 100,000 premium follows it.
        Optional<CessionCalculator.CededAmounts> result = CessionCalculator.calculate(
            treaty(TreatyType.SURPLUS, "500000.00", null),
            new BigDecimal("2000000.00"), "TZS", new BigDecimal("100000.00"), "TZS");

        assertThat(result).isPresent();
        assertThat(result.get().cededRisk()).isEqualByComparingTo("1500000.00");
        assertThat(result.get().cededPremium()).isEqualByComparingTo("75000.00");
    }

    @Test
    void surplusCedesNothingWhenTheSumAssuredIsAtOrBelowRetention() {
        // The ordinary case for a small policy under a surplus treaty -- not an error.
        assertThat(CessionCalculator.calculate(treaty(TreatyType.SURPLUS, "2000000.00", null),
            new BigDecimal("2000000.00"), "TZS", new BigDecimal("100000.00"), "TZS")).isEmpty();
        assertThat(CessionCalculator.calculate(treaty(TreatyType.SURPLUS, "2000000.00", null),
            new BigDecimal("500000.00"), "TZS", new BigDecimal("100000.00"), "TZS")).isEmpty();
    }

    /** XOL is a CLAIM-level treaty: it cedes nothing at issuance and instead recovers the excess
     * of a loss over retention (RecoveryCalculator, Task 5). Giving it an issuance interpretation
     * would encode an actuarially wrong model. */
    @Test
    void excessOfLossCedesNothingAtIssuance() {
        assertThat(CessionCalculator.calculate(treaty(TreatyType.XOL, "1000000.00", null),
            new BigDecimal("2000000.00"), "TZS", new BigDecimal("100000.00"), "TZS")).isEmpty();
    }

    /** No FX table exists anywhere on this platform, so converting would mean inventing a rate.
     * Ceding nothing is the honest outcome. */
    @Test
    void aCurrencyMismatchBetweenTreatyAndPolicyCedesNothing() {
        ReinsuranceTreaty tzsTreaty = treaty(TreatyType.QUOTA_SHARE, "0", "30.00");
        assertThat(CessionCalculator.calculate(tzsTreaty,
            new BigDecimal("2000000.00"), "USD", new BigDecimal("100000.00"), "USD")).isEmpty();
    }

    /** A premium in a different currency from the sum assured is legitimate (PolicyIssued carries
     * the two independently), and only the RISK currency must match the treaty. The ceded premium
     * keeps the policy's own premium currency. */
    @Test
    void thePremiumKeepsItsOwnCurrency() {
        Optional<CessionCalculator.CededAmounts> result = CessionCalculator.calculate(
            treaty(TreatyType.QUOTA_SHARE, "0", "50.00"),
            new BigDecimal("1000000.00"), "TZS", new BigDecimal("400.00"), "USD");

        assertThat(result).isPresent();
        assertThat(result.get().riskCurrency()).isEqualTo("TZS");
        assertThat(result.get().premiumCurrency()).isEqualTo("USD");
        assertThat(result.get().cededPremium()).isEqualByComparingTo("200.00");
    }

    @Test
    void amountsAreRoundedHalfUpToTwoDecimalPlaces() {
        // 33.33% of 1,000.00 = 333.30; of a 10.00 premium = 3.333 -> 3.33
        Optional<CessionCalculator.CededAmounts> result = CessionCalculator.calculate(
            treaty(TreatyType.QUOTA_SHARE, "0", "33.33"),
            new BigDecimal("1000.00"), "TZS", new BigDecimal("10.00"), "TZS");

        assertThat(result).isPresent();
        assertThat(result.get().cededRisk()).isEqualByComparingTo("333.30");
        assertThat(result.get().cededPremium()).isEqualByComparingTo("3.33");
        assertThat(result.get().cededPremium().scale()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./mvnw -B -o test -Dtest=CessionCalculatorTest
```
Expected: FAIL to compile — `CessionCalculator` does not exist.

- [ ] **Step 3: Write `CessionCalculator`**

```java
package tz.co.nlolo.lifeplatform.reinsurance.domain;

import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * THE ENTIRE ALGORITHM BELOW IS AN INVENTED PLACEHOLDER, flagged rather than guessed silently --
 * the same treatment M2 gave the underwriting decision engine and M7 gave commission.
 *
 * <p>No document on this platform defines how a treaty computes a cession.
 * {@code docs/03-aggregate-design.md:165} reads, in full, "### 7.4 `reinsurance` -- unchanged from
 * Rev 1", and Rev 1 is not in this repository. Every rule encoded here is M8's own decision and
 * needs Actuarial/Reinsurance sign-off:
 *
 * <ul>
 *   <li><b>QUOTA_SHARE cedes the same percentage of risk AND premium.</b> That is what a quota
 *       share means -- a fixed share of the business, premium travelling with the risk.</li>
 *   <li><b>SURPLUS cedes the excess over retention, and premium in proportion to the risk
 *       actually ceded.</b> A sum assured at or below retention cedes NOTHING, which is the
 *       correct and expected outcome for a small policy, not an error.</li>
 *   <li><b>XOL cedes nothing at issuance.</b> Excess-of-loss is a claim-level treaty. It
 *       participates only in recovery ({@link RecoveryCalculator}), where it recovers the excess
 *       of a loss over retention. Giving it an issuance interpretation would encode an
 *       actuarially wrong model, which for a reinsurance treaty surfaces as a financial
 *       misstatement rather than a bug.</li>
 *   <li><b>A currency mismatch cedes nothing rather than converting.</b> No FX table exists
 *       anywhere on this platform; inventing a rate is worse than not ceding. Only the RISK
 *       currency must match the treaty -- the ceded premium keeps the policy's own premium
 *       currency, since {@code policy.PolicyIssued} carries the two independently.</li>
 * </ul>
 */
public final class CessionCalculator {

    private CessionCalculator() {}

    /** What the caller should persist. Both premium fields are non-null together (V2's
     * {@code cession_ceded_premium_paired}); this calculator always populates them for the two
     * ceding treaty types. */
    public record CededAmounts(BigDecimal cededRisk, String riskCurrency,
                                BigDecimal cededPremium, String premiumCurrency) {}

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    /**
     * @return the amounts to cede, or empty when this treaty cedes nothing for this policy --
     *         which is a normal outcome (XOL, a sub-retention surplus risk, a currency mismatch),
     *         never an error. A zero result is deliberately empty rather than a zero row: V2's
     *         {@code ceded_amount > 0} CHECK would reject it, and "ceded nothing" is not a
     *         financial record.
     */
    public static Optional<CededAmounts> calculate(ReinsuranceTreaty treaty,
                                                    BigDecimal sumAssured, String sumAssuredCurrency,
                                                    BigDecimal premium, String premiumCurrency) {
        if (!treaty.getRetentionLimitCurrency().equals(sumAssuredCurrency)) {
            return Optional.empty();
        }
        BigDecimal cededRisk = switch (treaty.getTreatyType()) {
            case QUOTA_SHARE -> share(treaty.getCessionPercent(), sumAssured);
            case SURPLUS -> sumAssured.subtract(treaty.getRetentionLimitAmount()).max(BigDecimal.ZERO);
            case XOL -> BigDecimal.ZERO;
        };
        cededRisk = cededRisk.setScale(2, RoundingMode.HALF_UP);
        if (cededRisk.signum() <= 0) {
            return Optional.empty();
        }

        // Premium follows the risk. For a quota share that is the treaty's own percentage; for a
        // surplus it is the fraction of the sum assured actually ceded, which for a quota share
        // would give the identical answer -- expressed separately only because the quota-share
        // percentage is the treaty's stated term and should be applied as written.
        BigDecimal cededPremium = switch (treaty.getTreatyType()) {
            case QUOTA_SHARE -> share(treaty.getCessionPercent(), premium);
            case SURPLUS -> premium.multiply(cededRisk).divide(sumAssured, 2, RoundingMode.HALF_UP);
            case XOL -> BigDecimal.ZERO;   // unreachable: XOL returned empty above
        };
        cededPremium = cededPremium.setScale(2, RoundingMode.HALF_UP);

        // A ceded premium that rounds to zero must not be persisted -- V2's
        // cession_ceded_premium_positive would reject it -- but the ceded RISK is still real, so
        // record the cession with no premium share rather than dropping it entirely.
        BigDecimal premiumToRecord = cededPremium.signum() > 0 ? cededPremium : null;
        String premiumCurrencyToRecord = premiumToRecord == null ? null : premiumCurrency;

        return Optional.of(new CededAmounts(cededRisk, sumAssuredCurrency,
            premiumToRecord, premiumCurrencyToRecord));
    }

    private static BigDecimal share(BigDecimal percent, BigDecimal amount) {
        return amount.multiply(percent).divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
./mvnw -B -o test -Dtest=CessionCalculatorTest
```
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/reinsurance/domain/CessionCalculator.java src/test/java/tz/co/nlolo/lifeplatform/reinsurance/CessionCalculatorTest.java
git commit -m "feat: the cession calculator -- quota share and surplus, with XOL deliberately ceding nothing"
```

---

### Task 5: `RecoveryCalculator` — proportional and excess-of-loss recovery

**Files:**
- Create: `reinsurance/domain/RecoveryCalculator.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/reinsurance/RecoveryCalculatorTest.java`

**Interfaces:**
- Consumes: `ReinsuranceTreaty`, `Cession` (Task 3).
- Produces: `RecoveryCalculator.proportional(Cession, BigDecimal sumAssured, BigDecimal settledAmount, String settledCurrency)` and `RecoveryCalculator.excessOfLoss(ReinsuranceTreaty, BigDecimal settledAmount, String settledCurrency)`, both returning `Optional<BigDecimal>`. Task 6's `ClaimEventListener` calls exactly these.

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.reinsurance;

import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.domain.Cession;
import tz.co.nlolo.lifeplatform.reinsurance.domain.RecoveryCalculator;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryCalculatorTest {

    private static Cession cession(String cededRisk, String currency) {
        return new Cession(UUID.randomUUID(), "POL-REC-01", UUID.randomUUID(),
            new BigDecimal(cededRisk), currency, new BigDecimal("1000.00"), currency);
    }

    private static ReinsuranceTreaty xolTreaty(String retention) {
        return new ReinsuranceTreaty(UUID.randomUUID(), "Test Re", TreatyType.XOL,
            new BigDecimal(retention), "TZS", null, LocalDate.now().minusYears(1), null, "actuary");
    }

    @Test
    void proportionalRecoveryFollowsTheCededFractionOfSumAssured() {
        // 1,500,000 of a 2,000,000 sum assured was ceded = 75%, so 75% of a 2,000,000 settlement.
        assertThat(RecoveryCalculator.proportional(cession("1500000.00", "TZS"),
            new BigDecimal("2000000.00"), new BigDecimal("2000000.00"), "TZS"))
            .contains(new BigDecimal("1500000.00"));
    }

    @Test
    void proportionalRecoveryRoundsHalfUpToTwoDecimalPlaces() {
        // 1/3 ceded of 1,000.00 sum assured, settled at 100.00 -> 33.333... -> 33.33
        assertThat(RecoveryCalculator.proportional(cession("333.33", "TZS"),
            new BigDecimal("1000.00"), new BigDecimal("100.00"), "TZS"))
            .contains(new BigDecimal("33.33"));
    }

    @Test
    void proportionalRecoveryIsEmptyOnACurrencyMismatch() {
        assertThat(RecoveryCalculator.proportional(cession("1500000.00", "TZS"),
            new BigDecimal("2000000.00"), new BigDecimal("2000000.00"), "USD")).isEmpty();
    }

    @Test
    void excessOfLossRecoversOnlyTheAmountAboveRetention() {
        // A 2,000,000 loss against a 500,000 retention -> the reinsurer covers 1,500,000.
        assertThat(RecoveryCalculator.excessOfLoss(xolTreaty("500000.00"),
            new BigDecimal("2000000.00"), "TZS")).contains(new BigDecimal("1500000.00"));
    }

    /** The ordinary case for an XOL treaty: most losses fall entirely within retention and the
     * reinsurer owes nothing. Not an error. */
    @Test
    void excessOfLossRecoversNothingForALossAtOrBelowRetention() {
        assertThat(RecoveryCalculator.excessOfLoss(xolTreaty("500000.00"),
            new BigDecimal("500000.00"), "TZS")).isEmpty();
        assertThat(RecoveryCalculator.excessOfLoss(xolTreaty("500000.00"),
            new BigDecimal("100000.00"), "TZS")).isEmpty();
    }

    @Test
    void excessOfLossIsEmptyOnACurrencyMismatch() {
        assertThat(RecoveryCalculator.excessOfLoss(xolTreaty("500000.00"),
            new BigDecimal("2000000.00"), "USD")).isEmpty();
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./mvnw -B -o test -Dtest=RecoveryCalculatorTest
```
Expected: FAIL to compile — `RecoveryCalculator` does not exist.

- [ ] **Step 3: Write `RecoveryCalculator`**

```java
package tz.co.nlolo.lifeplatform.reinsurance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * BOTH FORMULAS BELOW ARE INVENTED PLACEHOLDERS, flagged rather than guessed silently -- see
 * {@link CessionCalculator}'s javadoc for why (the reinsurance aggregate design is a documentation
 * void on this platform). Needs Actuarial/Reinsurance sign-off.
 *
 * <p><b>Two distinct paths, because the two treaty families recover differently.</b> This is what
 * "XOL participates only in recovery" means concretely:
 * <ul>
 *   <li><b>Proportional</b> ({@link #proportional}) -- for QUOTA_SHARE and SURPLUS, which ceded
 *       risk at issuance. The reinsurer's share of a settled claim is the same fraction of the
 *       loss as it took of the sum assured. Requires a {@link Cession} to exist.</li>
 *   <li><b>Excess-of-loss</b> ({@link #excessOfLoss}) -- for XOL, which ceded nothing at issuance
 *       and therefore has NO cession to be proportional to. The reinsurer covers the part of the
 *       loss above the treaty's retention. Requires no cession by construction, which is exactly
 *       why XOL produces none.</li>
 * </ul>
 *
 * <p>Both return empty on a currency mismatch rather than converting -- no FX table exists on this
 * platform -- and both return empty for a zero result, because V2's {@code recoverable_amount > 0}
 * CHECK would reject a zero row and "recovered nothing" is not a financial record.
 */
public final class RecoveryCalculator {

    private RecoveryCalculator() {}

    /**
     * {@code settledAmount x (cededAmount / sumAssured)}. Proportional recovery is the ordinary
     * treaty convention, but no document on this platform states it -- flagged.
     *
     * @return the reinsurer's share, or empty on a currency mismatch or a zero result
     */
    public static Optional<BigDecimal> proportional(Cession cession, BigDecimal sumAssured,
                                                     BigDecimal settledAmount, String settledCurrency) {
        if (!cession.getCededCurrency().equals(settledCurrency)) {
            return Optional.empty();
        }
        if (sumAssured == null || sumAssured.signum() <= 0) {
            return Optional.empty();   // guards the divide; a non-positive sum assured cannot occur
        }                              // per V2's CHECK, but dividing by it would be unrecoverable
        BigDecimal recoverable = settledAmount
            .multiply(cession.getCededAmount())
            .divide(sumAssured, 2, RoundingMode.HALF_UP);
        return recoverable.signum() > 0 ? Optional.of(recoverable) : Optional.empty();
    }

    /**
     * {@code max(0, settledAmount - retentionLimit)}.
     *
     * @return the excess over retention, or empty when the loss falls entirely within retention
     *         (the ordinary case) or the currency does not match
     */
    public static Optional<BigDecimal> excessOfLoss(ReinsuranceTreaty treaty,
                                                     BigDecimal settledAmount, String settledCurrency) {
        if (!treaty.getRetentionLimitCurrency().equals(settledCurrency)) {
            return Optional.empty();
        }
        BigDecimal recoverable = settledAmount
            .subtract(treaty.getRetentionLimitAmount())
            .max(BigDecimal.ZERO)
            .setScale(2, RoundingMode.HALF_UP);
        return recoverable.signum() > 0 ? Optional.of(recoverable) : Optional.empty();
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
./mvnw -B -o test -Dtest=RecoveryCalculatorTest
```
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/reinsurance/domain/RecoveryCalculator.java src/test/java/tz/co/nlolo/lifeplatform/reinsurance/RecoveryCalculatorTest.java
git commit -m "feat: the recovery calculator -- proportional for ceded treaties, excess-of-loss for XOL"
```

---

### Task 6: `ReinsuranceApiImpl` and treaty selection

**Files:**
- Create: `reinsurance/application/ReinsuranceApiImpl.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/reinsurance/ReinsuranceApiIntegrationTest.java`, `TreatySelectionTest.java`

**Interfaces:**
- Consumes: everything from Tasks 2-5.
- Produces: `ReinsuranceApi`'s implementation, plus two package-private methods the listeners in Task 7 call directly (the same shape `distribution`'s listeners use against `DistributionApiImpl`):
  - `Optional<ReinsuranceTreaty> selectApplicableTreaty(UUID tenantId, LocalDate issueDate)`
  - `Optional<Cession> persistCession(UUID tenantId, String policyNumber, ReinsuranceTreaty treaty, CessionCalculator.CededAmounts amounts)`
  - `Optional<ClaimRecovery> persistRecovery(UUID tenantId, UUID claimId, UUID treatyId, BigDecimal amount, String currency, String createdBy)`

- [ ] **Step 1: Write the failing treaty-selection test**

```java
package tz.co.nlolo.lifeplatform.reinsurance;

import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ReinsuranceTreaty#isActiveOn} in isolation -- the predicate treaty selection rests on.
 * The selection ORDERING (newest effective_from, ties by treatyId) is exercised against a real
 * database in {@code ReinsuranceApiIntegrationTest}, since it is a query concern. */
class TreatySelectionTest {

    private static ReinsuranceTreaty treaty(LocalDate from, LocalDate to) {
        return new ReinsuranceTreaty(UUID.randomUUID(), "Test Re", TreatyType.QUOTA_SHARE,
            BigDecimal.ZERO, "TZS", new BigDecimal("30.00"), from, to, "actuary");
    }

    @Test
    void aTreatyIsActiveInsideItsWindow() {
        ReinsuranceTreaty t = treaty(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(t.isActiveOn(LocalDate.of(2026, 6, 1))).isTrue();
    }

    @Test
    void windowBoundariesAreInclusiveOnBothEnds() {
        ReinsuranceTreaty t = treaty(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(t.isActiveOn(LocalDate.of(2026, 1, 1))).isTrue();
        assertThat(t.isActiveOn(LocalDate.of(2026, 12, 31))).isTrue();
    }

    @Test
    void aTreatyIsInactiveOutsideItsWindow() {
        ReinsuranceTreaty t = treaty(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(t.isActiveOn(LocalDate.of(2025, 12, 31))).isFalse();
        assertThat(t.isActiveOn(LocalDate.of(2027, 1, 1))).isFalse();
    }

    @Test
    void anOpenEndedTreatyNeverExpiresByDate() {
        ReinsuranceTreaty t = treaty(LocalDate.of(2026, 1, 1), null);
        assertThat(t.isActiveOn(LocalDate.of(2099, 1, 1))).isTrue();
    }

    @Test
    void anExpiredTreatyIsNeverActiveEvenInsideItsWindow() {
        ReinsuranceTreaty t = treaty(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        t.expire("staff-1");
        assertThat(t.isActiveOn(LocalDate.of(2026, 6, 1))).isFalse();
    }
}
```

- [ ] **Step 2: Run it and confirm it passes already**

```bash
./mvnw -B -o test -Dtest=TreatySelectionTest
```
Expected: PASS, 5 tests — `isActiveOn` was written in Task 3. This test exists to pin that predicate before Task 6's selection query depends on it; if it fails, fix `isActiveOn` before continuing.

- [ ] **Step 3: Write the failing API integration test**

```java
package tz.co.nlolo.lifeplatform.reinsurance;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceValidationException;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyNotFoundException;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyStatus;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest(classes = Application.class)
class ReinsuranceApiIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "reinsurance_it_password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_role");
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
    }

    @BeforeAll
    static void applyMigrationsAndBootstrapAppRole() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/reinsurance/V1__create_reinsurance_schema.sql",
            "db-migrations/reinsurance/V2__grants_rls_money_checks_reinsurer_and_projection.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @Autowired private ReinsuranceApi reinsuranceApi;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private static ReinsuranceApi.CreateTreatyRequest quotaShare(String percent) {
        return new ReinsuranceApi.CreateTreatyRequest("Africa Re", TreatyType.QUOTA_SHARE,
            new BigDecimal("0.00"), "TZS", new BigDecimal(percent), LocalDate.now().minusMonths(1), null);
    }

    @Test
    void createsATreatyAndItRoundTripsThroughGetTreaty() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        TreatyView created = reinsuranceApi.createTreaty(quotaShare("30.00"), "finance-officer");
        assertThat(created.treatyId()).isNotNull();
        assertThat(created.reinsurerName()).isEqualTo("Africa Re");
        assertThat(created.status()).isEqualTo(TreatyStatus.ACTIVE);
        assertThat(created.cessionPercent()).isEqualByComparingTo("30.00");

        TenantContext.set(tenantId);
        TreatyView reloaded = reinsuranceApi.getTreaty(created.treatyId());
        assertThat(reloaded.treatyId()).isEqualTo(created.treatyId());
        assertThat(reloaded.reinsurerName()).isEqualTo("Africa Re");
    }

    @Test
    void rejectsAQuotaShareTreatyWithNoCessionPercent() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        ReinsuranceApi.CreateTreatyRequest request = new ReinsuranceApi.CreateTreatyRequest(
            "Africa Re", TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), "TZS",
            null, LocalDate.now(), null);
        assertThrows(ReinsuranceValidationException.class,
            () -> reinsuranceApi.createTreaty(request, "finance-officer"));
    }

    @Test
    void rejectsANonQuotaShareTreatyThatCarriesACessionPercent() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        ReinsuranceApi.CreateTreatyRequest request = new ReinsuranceApi.CreateTreatyRequest(
            "Africa Re", TreatyType.SURPLUS, new BigDecimal("500000.00"), "TZS",
            new BigDecimal("30.00"), LocalDate.now(), null);
        assertThrows(ReinsuranceValidationException.class,
            () -> reinsuranceApi.createTreaty(request, "finance-officer"));
    }

    @Test
    void rejectsABlankReinsurerName() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        ReinsuranceApi.CreateTreatyRequest request = new ReinsuranceApi.CreateTreatyRequest(
            "  ", TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), "TZS",
            new BigDecimal("30.00"), LocalDate.now(), null);
        assertThrows(ReinsuranceValidationException.class,
            () -> reinsuranceApi.createTreaty(request, "finance-officer"));
    }

    @Test
    void rejectsAnEffectiveToBeforeEffectiveFrom() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        ReinsuranceApi.CreateTreatyRequest request = new ReinsuranceApi.CreateTreatyRequest(
            "Africa Re", TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), "TZS",
            new BigDecimal("30.00"), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1));
        assertThrows(ReinsuranceValidationException.class,
            () -> reinsuranceApi.createTreaty(request, "finance-officer"));
    }

    @Test
    void getTreatyThrowsForAnUnknownId() {
        TenantContext.set(UUID.randomUUID());
        UUID unknown = UUID.randomUUID();
        assertThrows(TreatyNotFoundException.class, () -> reinsuranceApi.getTreaty(unknown));
    }

    /** Not merely an empty list: a treaty genuinely existing in another tenant must be invisible,
     * proven under real RLS with app_role (NOSUPERUSER NOBYPASSRLS). */
    @Test
    void aTreatyIsInvisibleToAnyOtherTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        TenantContext.set(tenantA);
        TreatyView inA = reinsuranceApi.createTreaty(quotaShare("30.00"), "finance-officer");

        TenantContext.set(tenantB);
        assertThrows(TreatyNotFoundException.class, () -> reinsuranceApi.getTreaty(inA.treatyId()));
        assertThat(reinsuranceApi.listTreaties(null)).isEmpty();
    }

    @Test
    void listTreatiesFiltersByStatus() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        reinsuranceApi.createTreaty(quotaShare("30.00"), "finance-officer");

        TenantContext.set(tenantId);
        assertThat(reinsuranceApi.listTreaties(null)).hasSize(1);
        TenantContext.set(tenantId);
        assertThat(reinsuranceApi.listTreaties(TreatyStatus.ACTIVE)).hasSize(1);
        // The falsifiable half -- a filter that ignored its argument would return the row here too.
        TenantContext.set(tenantId);
        assertThat(reinsuranceApi.listTreaties(TreatyStatus.EXPIRED)).isEmpty();
    }
}
```

- [ ] **Step 4: Run it and confirm it fails**

```bash
./mvnw -B -o test -Dtest=ReinsuranceApiIntegrationTest
```
Expected: FAIL — no `ReinsuranceApi` bean exists yet.

- [ ] **Step 5: Write `ReinsuranceApiImpl`**

```java
package tz.co.nlolo.lifeplatform.reinsurance.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reinsurance.api.CessionView;
import tz.co.nlolo.lifeplatform.reinsurance.api.ClaimRecoveryView;
import tz.co.nlolo.lifeplatform.reinsurance.api.RecoveryNotFoundException;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceValidationException;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyNotFoundException;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyStatus;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyView;
import tz.co.nlolo.lifeplatform.reinsurance.domain.Cession;
import tz.co.nlolo.lifeplatform.reinsurance.domain.CessionCalculator;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ClaimRecovery;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.CessionRepository;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.ClaimRecoveryRepository;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.ReinsuranceTreatyRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@link ReinsuranceApi}, and additionally exposes the package-private cession/recovery
 * plumbing that {@link PolicyEventListener} and {@link ClaimEventListener} call directly (injecting
 * this concrete class rather than the interface -- the same shape {@code distribution}'s listeners
 * use against {@code DistributionApiImpl}). Those methods are not part of the published surface;
 * they exist so the listeners reuse this class's already-wired repositories instead of duplicating
 * selection and persistence logic.
 */
@Service
public class ReinsuranceApiImpl implements ReinsuranceApi {

    private final ReinsuranceTreatyRepository treatyRepository;
    private final CessionRepository cessionRepository;
    private final ClaimRecoveryRepository claimRecoveryRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ReinsuranceApiImpl(ReinsuranceTreatyRepository treatyRepository,
                               CessionRepository cessionRepository,
                               ClaimRecoveryRepository claimRecoveryRepository,
                               ApplicationEventPublisher eventPublisher) {
        this.treatyRepository = treatyRepository;
        this.cessionRepository = cessionRepository;
        this.claimRecoveryRepository = claimRecoveryRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public TreatyView createTreaty(CreateTreatyRequest request, String createdBy) {
        UUID tenantId = TenantContext.get();

        if (request.reinsurerName() == null || request.reinsurerName().isBlank()) {
            throw new ReinsuranceValidationException("A reinsurer name is required");
        }
        if (request.reinsurerName().length() > 200) {
            throw new ReinsuranceValidationException("Reinsurer name is " + request.reinsurerName().length()
                + " characters; the maximum is 200");
        }
        if (request.retentionLimitAmount() == null || request.retentionLimitAmount().signum() < 0) {
            throw new ReinsuranceValidationException("Retention limit must be zero or positive");
        }
        // Mirrors V2's treaty_cession_percent_required_for_quota_share exactly, so a violation is
        // a clean 422 rather than an integrity violation surfacing as a 500.
        boolean isQuotaShare = request.treatyType() == TreatyType.QUOTA_SHARE;
        if (isQuotaShare && request.cessionPercent() == null) {
            throw new ReinsuranceValidationException("A QUOTA_SHARE treaty requires a cession percent");
        }
        if (!isQuotaShare && request.cessionPercent() != null) {
            throw new ReinsuranceValidationException(
                "A " + request.treatyType() + " treaty must not carry a cession percent: SURPLUS cedes by "
                + "retention limit, and XOL does not cede at issuance at all");
        }
        if (request.cessionPercent() != null
                && (request.cessionPercent().signum() <= 0
                    || request.cessionPercent().compareTo(new BigDecimal("100")) > 0)) {
            throw new ReinsuranceValidationException("Cession percent must be greater than 0 and at most 100");
        }
        if (request.effectiveFrom() == null) {
            throw new ReinsuranceValidationException("An effective-from date is required");
        }
        if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw new ReinsuranceValidationException("Effective-to " + request.effectiveTo()
                + " is before effective-from " + request.effectiveFrom());
        }

        ReinsuranceTreaty treaty = new ReinsuranceTreaty(tenantId, request.reinsurerName().trim(),
            request.treatyType(), request.retentionLimitAmount(), request.retentionLimitCurrency(),
            request.cessionPercent(), request.effectiveFrom(), request.effectiveTo(), createdBy);
        treatyRepository.save(treaty);
        return toTreatyView(treaty);
    }

    @Override
    public TreatyView getTreaty(UUID treatyId) {
        UUID tenantId = TenantContext.get();
        return treatyRepository.findByTreatyIdAndTenantId(treatyId, tenantId)
            .map(this::toTreatyView)
            .orElseThrow(() -> new TreatyNotFoundException("Treaty " + treatyId + " not found"));
    }

    @Override
    public List<TreatyView> listTreaties(TreatyStatus status) {
        UUID tenantId = TenantContext.get();
        List<ReinsuranceTreaty> treaties = status == null
            ? treatyRepository.findByTenantIdOrderByEffectiveFromDesc(tenantId)
            : treatyRepository.findByTenantIdAndStatusOrderByEffectiveFromDesc(tenantId, status);
        return treaties.stream().map(this::toTreatyView).toList();
    }

    @Override
    public List<CessionView> listCessionsForPolicy(String policyNumber) {
        UUID tenantId = TenantContext.get();
        return cessionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber).stream()
            .map(this::toCessionView).toList();
    }

    @Override
    public List<ClaimRecoveryView> listRecoveriesForClaim(UUID claimId) {
        UUID tenantId = TenantContext.get();
        return claimRecoveryRepository.findByTenantIdAndClaimId(tenantId, claimId).stream()
            .map(this::toRecoveryView).toList();
    }

    @Override
    @Transactional
    public ClaimRecoveryView confirmRecovery(UUID recoveryId, String confirmedBy) {
        UUID tenantId = TenantContext.get();
        ClaimRecovery recovery = claimRecoveryRepository.findByRecoveryIdAndTenantId(recoveryId, tenantId)
            .orElseThrow(() -> new RecoveryNotFoundException("Recovery " + recoveryId + " not found"));

        // ClaimRecovery.confirm throws InvalidRecoveryStateException on a repeat, which the
        // boundary maps to 409. That is what makes the publish below safe to run unconditionally:
        // control only reaches it on a genuine transition, so a double-confirm can never emit a
        // second RecoveryConfirmed -- M6's I1 finding, where a duplicate reached finaccounting as
        // a double journal entry.
        recovery.confirm(Instant.now(), confirmedBy);
        claimRecoveryRepository.save(recovery);

        eventPublisher.publishEvent(DomainEventEnvelope.of("reinsurance.RecoveryConfirmed", tenantId,
            Map.of("recoveryId", recoveryId, "confirmedAt", recovery.getConfirmedAt().toString())));
        return toRecoveryView(recovery);
    }

    /**
     * Exactly one ACTIVE treaty whose effective window covers {@code issueDate}. Where several
     * match, the newest {@code effective_from} wins and a tie on that is broken by {@code treatyId}
     * so selection is fully deterministic rather than dependent on row order -- an invented rule,
     * flagged, and the reason multi-treaty layering is deferred (see the design spec).
     */
    Optional<ReinsuranceTreaty> selectApplicableTreaty(UUID tenantId, LocalDate issueDate) {
        return treatyRepository.findByTenantIdAndStatusOrderByEffectiveFromDesc(tenantId, TreatyStatus.ACTIVE)
            .stream()
            .filter(t -> t.isActiveOn(issueDate))
            .max(Comparator.comparing(ReinsuranceTreaty::getEffectiveFrom)
                .thenComparing(ReinsuranceTreaty::getTreatyId));
    }

    /** @return the persisted cession, or empty if one already exists for this (policy, treaty) --
     * making a redelivered PolicyIssued a no-op. {@code ux_cession_once} is the real backstop. */
    Optional<Cession> persistCession(UUID tenantId, String policyNumber, ReinsuranceTreaty treaty,
                                      CessionCalculator.CededAmounts amounts) {
        if (cessionRepository.existsByTenantIdAndPolicyNumberAndTreatyId(tenantId, policyNumber, treaty.getTreatyId())) {
            return Optional.empty();
        }
        Cession cession = new Cession(tenantId, policyNumber, treaty.getTreatyId(),
            amounts.cededRisk(), amounts.riskCurrency(), amounts.cededPremium(), amounts.premiumCurrency());
        cessionRepository.save(cession);
        return Optional.of(cession);
    }

    /** @return the persisted recovery, or empty if one already exists for this (claim, treaty). */
    Optional<ClaimRecovery> persistRecovery(UUID tenantId, UUID claimId, UUID treatyId,
                                             BigDecimal amount, String currency, String createdBy) {
        if (claimRecoveryRepository.existsByTenantIdAndClaimIdAndTreatyId(tenantId, claimId, treatyId)) {
            return Optional.empty();
        }
        ClaimRecovery recovery = new ClaimRecovery(tenantId, claimId, treatyId, amount, currency, createdBy);
        claimRecoveryRepository.save(recovery);
        return Optional.of(recovery);
    }

    private TreatyView toTreatyView(ReinsuranceTreaty t) {
        return new TreatyView(t.getTreatyId(), t.getReinsurerName(), t.getTreatyType(), t.getStatus(),
            t.getRetentionLimitAmount(), t.getRetentionLimitCurrency(), t.getCessionPercent(),
            t.getEffectiveFrom(), t.getEffectiveTo());
    }

    private CessionView toCessionView(Cession c) {
        return new CessionView(c.getCessionId(), c.getPolicyNumber(), c.getTreatyId(),
            c.getCededAmount(), c.getCededCurrency(), c.getCededPremiumAmount(), c.getCededPremiumCurrency());
    }

    private ClaimRecoveryView toRecoveryView(ClaimRecovery r) {
        return new ClaimRecoveryView(r.getRecoveryId(), r.getClaimId(), r.getTreatyId(),
            r.getRecoverableAmount(), r.getRecoverableCurrency(), r.getConfirmedAt());
    }
}
```

- [ ] **Step 6: Run both tests and confirm they pass**

```bash
./mvnw -B -o test -Dtest='ReinsuranceApiIntegrationTest,TreatySelectionTest'
```
Expected: PASS, 13 tests (8 + 5).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/reinsurance/application/ src/test/java/tz/co/nlolo/lifeplatform/reinsurance/
git commit -m "feat: reinsurance API implementation, treaty authoring and deterministic treaty selection"
```

---

### Task 7: The two event listeners, and enriching `claims.ClaimSettled`

**Files:**
- Create: `reinsurance/application/PolicyEventListener.java`, `ClaimEventListener.java`
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/claims/application/PaymentEventListener.java` (one publish site)
- Modify: `api/asyncapi-events.yaml`, `docs/05-event-catalog.md`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/reinsurance/CessionEndToEndTest.java`, `RecoveryEndToEndTest.java`

**Interfaces:**
- Consumes: `ReinsuranceApiImpl.selectApplicableTreaty`/`persistCession`/`persistRecovery`, `CessionCalculator.calculate`, `RecoveryCalculator.proportional`/`excessOfLoss`, `PolicyProjectionRepository`, `ReinsuranceTreatyRepository`, `CessionRepository`.
- Produces: `reinsurance.CessionRecorded`, `reinsurance.RecoveryCalculated`; an enriched `claims.ClaimSettled`.

- [ ] **Step 1: Enrich `claims.ClaimSettled`**

In `claims/application/PaymentEventListener.java`, replace the publish block (currently at `:205-206`):

```java
            if (!alreadySettled) {
                eventPublisher.publishEvent(DomainEventEnvelope.of("claims.ClaimSettled", tenantId,
                    Map.of("claimId", claimId, "settledAt", Instant.now().toString())));
            }
```

with:

```java
            if (!alreadySettled) {
                // M8: policyNumber and settledAmount added. Until now this event carried only
                // claimId + settledAt, so no consumer could attribute a settlement to a policy or
                // know what was paid -- reinsurance needs both to compute a claim recovery, and
                // finaccounting (M9) needs the amount for its journal posting. Both values are
                // already on the loaded Claim, so this is purely additive; the alternative
                // (triggering recovery from claims.ClaimApproved, which does carry them) was
                // rejected because approval precedes the rail and a settlement can still fail,
                // and a recoverable booked against money that never moved overstates assets.
                eventPublisher.publishEvent(DomainEventEnvelope.of("claims.ClaimSettled", tenantId,
                    Map.of("claimId", claimId,
                           "policyNumber", claim.getPolicyNumber(),
                           "settledAmount", Map.of("amount", claim.getApprovedAmount().toPlainString(),
                                                    "currencyCode", claim.getApprovedCurrency()),
                           "settledAt", Instant.now().toString())));
            }
```

`Map.of` is safe here: a claim can only reach SETTLED via APPROVED, and `Claim.approve` rejects a null or non-positive amount, so neither value can be null on this path.

- [ ] **Step 2: Update `api/asyncapi-events.yaml`**

Change `ClaimSettledPayload` to add the two fields, and note reinsurance as a consumer. Find `ClaimSettledPayload:` and replace its `properties` line with:

```yaml
          properties: { claimId: {type: string, format: uuid}, policyNumber: {type: string}, settledAmount: {type: object}, settledAt: {type: string, format: date-time} }
```

Then update the channel description for `claims.ClaimSettled` to:

```yaml
    description: >-
      "Producer: claims (confirmation, after consuming payment.DisbursementCompleted). Consumers:
      reinsurance (claim recovery), finaccounting, communication, audit, regreporting. policyNumber
      and settledAmount were added in M8: without them no consumer could attribute a settlement to
      a policy or know what was paid."
```

Also add `cededPremium` to `CessionRecordedPayload`:

```yaml
          properties: { cessionId: {type: string, format: uuid}, policyNumber: {type: string}, treatyId: {type: string, format: uuid}, cededAmount: {type: object}, cededPremium: {type: object, nullable: true} }
```

- [ ] **Step 3: Update `docs/05-event-catalog.md`**

In the producer table, `claims`' consumer list already includes `reinsurance` — verify and leave it. Confirm the `reinsurance` row still reads `| reinsurance | 3 | finaccounting, audit |` (M8 adds no new reinsurance events; all three were already declared). If the total event count line mentions a number, leave it unchanged — this task adds no event types.

- [ ] **Step 4: Write `PolicyEventListener`**

```java
package tz.co.nlolo.lifeplatform.reinsurance.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reinsurance.domain.Cession;
import tz.co.nlolo.lifeplatform.reinsurance.domain.CessionCalculator;
import tz.co.nlolo.lifeplatform.reinsurance.domain.PolicyProjection;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.PolicyProjectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Consumes {@code policy.PolicyIssued} and cedes risk to the applicable treaty.
 *
 * <p>{@code policy} is not in this module's {@code allowedDependencies}, so everything here comes
 * from the event payload plus this module's own {@code policy_projection}. Mechanics copied from
 * {@code distribution/application/PolicyEventListener}: {@code AFTER_COMMIT} (policy's write must
 * be durable before reinsurance reacts), one reusable {@code PROPAGATION_REQUIRES_NEW}
 * {@link TransactionTemplate} (a plain {@code @Transactional} called from an AFTER_COMMIT callback
 * silently joins the already-committed producer transaction and never commits -- empirically
 * confirmed on this project), and {@code TenantContext} save/set/restore rather than an
 * unconditional clear, since this runs synchronously on the producer's own thread.
 *
 * <p>ONE transaction per handler: nothing here calls another module, so there is no foreign-module
 * failure to phase-separate against (contrast {@code claims.application.PaymentEventListener},
 * whose two-phase split exists because a {@code PolicyApi} call could otherwise roll back a
 * settled claim).
 *
 * <p><b>Bean name is explicit</b> -- {@code billing} and {@code distribution} each already declare
 * a {@code PolicyEventListener}, and a third unqualified {@code @Component} with the same simple
 * name is a bean-name collision that fails context startup for the whole suite.
 */
@Component("reinsurancePolicyEventListener")
public class PolicyEventListener {

    private static final Logger log = LoggerFactory.getLogger(PolicyEventListener.class);

    private final PolicyProjectionRepository policyProjectionRepository;
    private final ReinsuranceApiImpl reinsuranceApiImpl;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PolicyEventListener(PolicyProjectionRepository policyProjectionRepository,
                                ReinsuranceApiImpl reinsuranceApiImpl,
                                ApplicationEventPublisher eventPublisher,
                                PlatformTransactionManager transactionManager) {
        this.policyProjectionRepository = policyProjectionRepository;
        this.reinsuranceApiImpl = reinsuranceApiImpl;
        this.eventPublisher = eventPublisher;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "policy.PolicyIssued" -> withTenant(envelope, this::handlePolicyIssued);
            default -> { /* not reinsurance-relevant */ }
        }
    }

    private void withTenant(DomainEventEnvelope<?> envelope, Consumer<Map<String, Object>> handler) {
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            requiresNewTransactionTemplate.executeWithoutResult(status -> handler.accept(payload));
        } catch (Exception e) {
            log.error("reinsurance failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void handlePolicyIssued(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String policyNumber = (String) payload.get("policyNumber");
        UUID productId = (UUID) payload.get("productId");
        LocalDate issueDate = LocalDate.parse((String) payload.get("issueDate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sumAssured = (Map<String, Object>) payload.get("sumAssured");
        BigDecimal sumAssuredAmount = new BigDecimal((String) sumAssured.get("amount"));
        String sumAssuredCurrency = (String) sumAssured.get("currencyCode");
        @SuppressWarnings("unchecked")
        Map<String, Object> premium = (Map<String, Object>) payload.get("premium");
        BigDecimal premiumAmount = new BigDecimal((String) premium.get("amount"));
        String premiumCurrency = (String) premium.get("currencyCode");

        // The projection is written FIRST and unconditionally: it is the only place this module
        // ever learns this policy's sum assured and premium, and recovery (ClaimEventListener)
        // needs it later even if no treaty applies today.
        if (policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber).isEmpty()) {
            policyProjectionRepository.save(new PolicyProjection(tenantId, policyNumber, productId,
                sumAssuredAmount, sumAssuredCurrency, premiumAmount, premiumCurrency, issueDate));
        }

        Optional<ReinsuranceTreaty> maybeTreaty = reinsuranceApiImpl.selectApplicableTreaty(tenantId, issueDate);
        if (maybeTreaty.isEmpty()) {
            log.info("Policy {} issued with no ACTIVE reinsurance treaty covering {} -- nothing ceded",
                policyNumber, issueDate);
            return;
        }
        ReinsuranceTreaty treaty = maybeTreaty.get();

        Optional<CessionCalculator.CededAmounts> maybeAmounts = CessionCalculator.calculate(
            treaty, sumAssuredAmount, sumAssuredCurrency, premiumAmount, premiumCurrency);
        if (maybeAmounts.isEmpty()) {
            log.info("Treaty {} ({}) cedes nothing for policy {} -- an XOL treaty, a sum assured within "
                + "retention, or a currency mismatch", treaty.getTreatyId(), treaty.getTreatyType(), policyNumber);
            return;
        }

        reinsuranceApiImpl.persistCession(tenantId, policyNumber, treaty, maybeAmounts.get())
            .ifPresent(cession -> publishCessionRecorded(tenantId, cession));
    }

    /** Matches asyncapi-events.yaml's CessionRecordedPayload. LinkedHashMap, not Map.of:
     * cededPremium is legitimately null when a ceded premium rounds to zero. */
    private void publishCessionRecorded(UUID tenantId, Cession cession) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cessionId", cession.getCessionId());
        payload.put("policyNumber", cession.getPolicyNumber());
        payload.put("treatyId", cession.getTreatyId());
        payload.put("cededAmount", Map.of("amount", cession.getCededAmount().toPlainString(),
                                           "currencyCode", cession.getCededCurrency()));
        payload.put("cededPremium", cession.getCededPremiumAmount() == null ? null
            : Map.of("amount", cession.getCededPremiumAmount().toPlainString(),
                     "currencyCode", cession.getCededPremiumCurrency()));
        eventPublisher.publishEvent(DomainEventEnvelope.of("reinsurance.CessionRecorded", tenantId, payload));
    }
}
```

- [ ] **Step 5: Write `ClaimEventListener`**

```java
package tz.co.nlolo.lifeplatform.reinsurance.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.domain.Cession;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ClaimRecovery;
import tz.co.nlolo.lifeplatform.reinsurance.domain.PolicyProjection;
import tz.co.nlolo.lifeplatform.reinsurance.domain.RecoveryCalculator;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.CessionRepository;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.PolicyProjectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Consumes the M8-enriched {@code claims.ClaimSettled} and books what the reinsurer owes.
 *
 * <p>Triggering on SETTLED rather than APPROVED is deliberate: a recoverable is a real receivable,
 * and approval precedes the payment rail -- a settlement can still fail (PAYOUT_FAILED) or land
 * IN_DOUBT, and booking a recoverable against money that never left would overstate assets. That
 * is why M8 enriched {@code ClaimSettled} (which carried only claimId + settledAt) rather than
 * consuming {@code ClaimApproved}, the same trade-off M7 resolved the same way for
 * {@code billing.PremiumCollected}.
 *
 * <p>Mechanics and bean-naming rationale: see {@link PolicyEventListener}.
 */
@Component("reinsuranceClaimEventListener")
public class ClaimEventListener {

    private static final Logger log = LoggerFactory.getLogger(ClaimEventListener.class);

    private final PolicyProjectionRepository policyProjectionRepository;
    private final CessionRepository cessionRepository;
    private final ReinsuranceApiImpl reinsuranceApiImpl;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ClaimEventListener(PolicyProjectionRepository policyProjectionRepository,
                               CessionRepository cessionRepository,
                               ReinsuranceApiImpl reinsuranceApiImpl,
                               ApplicationEventPublisher eventPublisher,
                               PlatformTransactionManager transactionManager) {
        this.policyProjectionRepository = policyProjectionRepository;
        this.cessionRepository = cessionRepository;
        this.reinsuranceApiImpl = reinsuranceApiImpl;
        this.eventPublisher = eventPublisher;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "claims.ClaimSettled" -> withTenant(envelope, this::handleClaimSettled);
            default -> { /* not reinsurance-relevant */ }
        }
    }

    private void withTenant(DomainEventEnvelope<?> envelope, Consumer<Map<String, Object>> handler) {
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            requiresNewTransactionTemplate.executeWithoutResult(status -> handler.accept(payload));
        } catch (Exception e) {
            log.error("reinsurance failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void handleClaimSettled(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        UUID claimId = (UUID) payload.get("claimId");
        String policyNumber = (String) payload.get("policyNumber");
        @SuppressWarnings("unchecked")
        Map<String, Object> settled = (Map<String, Object>) payload.get("settledAmount");
        BigDecimal settledAmount = new BigDecimal((String) settled.get("amount"));
        String settledCurrency = (String) settled.get("currencyCode");

        Optional<PolicyProjection> maybeProjection =
            policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber);
        if (maybeProjection.isEmpty()) {
            log.info("Claim {} settled on policy {} which has no reinsurance projection row (pre-M8 policy) "
                + "-- nothing to recover", claimId, policyNumber);
            return;
        }
        PolicyProjection projection = maybeProjection.get();

        // PATH 1 -- the policy was ceded at issuance (QUOTA_SHARE or SURPLUS): the reinsurer's
        // share of this loss is the same fraction it took of the sum assured.
        List<Cession> cessions = cessionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber);
        if (!cessions.isEmpty()) {
            Cession cession = cessions.get(0);   // one treaty per policy -- see selectApplicableTreaty
            RecoveryCalculator.proportional(cession, projection.getSumAssuredAmount(), settledAmount, settledCurrency)
                .flatMap(amount -> reinsuranceApiImpl.persistRecovery(tenantId, claimId, cession.getTreatyId(),
                    amount, settledCurrency, "system:claims.ClaimSettled"))
                .ifPresent(recovery -> publishRecoveryCalculated(tenantId, recovery));
            return;
        }

        // PATH 2 -- no cession, so check for an XOL treaty, which cedes nothing at issuance BY
        // DESIGN and recovers the excess of the loss over retention instead. Without this branch
        // an XOL treaty would never recover anything and the type would be inert.
        Optional<ReinsuranceTreaty> maybeXol =
            reinsuranceApiImpl.selectApplicableTreaty(tenantId, projection.getIssueDate())
                .filter(t -> t.getTreatyType() == TreatyType.XOL);
        if (maybeXol.isEmpty()) {
            log.info("Claim {} settled on policy {} which was never ceded and has no applicable XOL treaty "
                + "-- nothing to recover", claimId, policyNumber);
            return;
        }
        ReinsuranceTreaty xol = maybeXol.get();
        RecoveryCalculator.excessOfLoss(xol, settledAmount, settledCurrency)
            .flatMap(amount -> reinsuranceApiImpl.persistRecovery(tenantId, claimId, xol.getTreatyId(),
                amount, settledCurrency, "system:claims.ClaimSettled"))
            .ifPresentOrElse(recovery -> publishRecoveryCalculated(tenantId, recovery),
                () -> log.info("Claim {} of {} {} falls within XOL treaty {}'s retention -- nothing recoverable",
                    claimId, settledAmount, settledCurrency, xol.getTreatyId()));
    }

    /** Matches asyncapi-events.yaml's RecoveryCalculatedPayload field-for-field. */
    private void publishRecoveryCalculated(UUID tenantId, ClaimRecovery recovery) {
        eventPublisher.publishEvent(DomainEventEnvelope.of("reinsurance.RecoveryCalculated", tenantId,
            Map.of("recoveryId", recovery.getRecoveryId(),
                   "claimId", recovery.getClaimId(),
                   "treatyId", recovery.getTreatyId(),
                   "recoverableAmount", Map.of("amount", recovery.getRecoverableAmount().toPlainString(),
                                                "currencyCode", recovery.getRecoverableCurrency()))));
    }
}
```

- [ ] **Step 6: Write `CessionEndToEndTest`**

Real Postgres as `app_role`, a real policy issued through `PolicyApi`. Migration list: `audit/V1`, `refdata/V1`, `refdata/V2`, `refdata/V3`, `party/V1`, `product/V1`, `underwriting/V1`, `policy/V1`-`V4`, `reinsurance/V1`, `reinsurance/V2`. Bootstrap `app_role` exactly as `ReinsuranceApiIntegrationTest` does.

Assert:
1. A QUOTA_SHARE treaty at 30% + a 2,000,000 policy → one cession, `ceded_amount = 600000.00`, ceded premium 30% of the premium, and exactly one `CessionRecorded`.
2. A SURPLUS treaty with 500,000 retention + a 2,000,000 policy → `ceded_amount = 1500000.00`.
3. A SURPLUS treaty with 5,000,000 retention + a 2,000,000 policy → **no** cession row at all, and no event.
4. An XOL treaty + any policy → **no** cession row, but the projection row IS written (recovery depends on it).
5. A policy issued with no ACTIVE treaty → projection written, no cession.
6. A redelivered `policy.PolicyIssued` (same envelope, republished through `ApplicationEventPublisher` inside a `TransactionTemplate`) → still exactly one cession, and no second `CessionRecorded`. Use the AFTER_COMMIT event recorder pattern from `DistributionContractTest`'s `EventRecorderConfiguration` (a `@TestConfiguration` bean, `@Import`-ed because `@SpringBootTest(classes = Application.class)` pins explicit classes).

- [ ] **Step 7: Write `RecoveryEndToEndTest`**

Same harness plus `claims/V1`-`V3` and `payment/V1`-`V4`, and WireMock for the rail (copy `ClaimSettlementEndToEndTest`'s setup, including `registry.add("mobile-money.base-url", () -> wireMock.baseUrl())`).

Assert:
1. Issue a policy under a QUOTA_SHARE treaty, register + assess + settle a DEATH claim for the full sum assured → one `claim_recovery` with `recoverable_amount` equal to the ceded fraction of the settled amount, and exactly one `RecoveryCalculated`.
2. `confirmRecovery` stamps `confirmed_at` and publishes exactly one `RecoveryConfirmed`; a **second** `confirmRecovery` throws `InvalidRecoveryStateException` and publishes **no** second event.
3. Under an XOL treaty with retention below the settled amount → a recovery equal to the excess, even though no cession exists.
4. Under an XOL treaty with retention above the settled amount → **no** recovery row.
5. A claim on a policy with no projection row (insert a claim directly for a policy never issued through the listener) → no recovery, no exception escaping.
6. A redelivered `claims.ClaimSettled` → still exactly one recovery, no second event.

- [ ] **Step 8: Run the FULL suite and commit**

```bash
# FULL SUITE REQUIRED (Global Constraints trigger 1): this task changes production code in
# `claims`. Enriching a published event can affect anything consuming claims' events or asserting
# on their payloads. Expect 500 + your new tests.
./mvnw -B -o test
git add -A
git commit -m "feat: cede on issuance, recover on settlement, and enrich claims.ClaimSettled"
```

---

### Task 8: REST layer, OpenAPI spec and exception handler

**Files:**
- Create: `reinsurance/infrastructure/TreatyController.java`, `RecoveryController.java`, `ReinsuranceExceptionHandler.java`, `MoneyDto.java`, `TreatyResponseDto.java`, `CessionResponseDto.java`, `ClaimRecoveryResponseDto.java`, `CreateTreatyRequestDto.java`
- Create: `api/openapi/openapi-reinsurance.yaml`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/reinsurance/ReinsuranceSpecParsesTest.java`

**Interfaces:**
- Consumes: `ReinsuranceApi` (Task 2), all views.
- Produces: six HTTP endpoints and the DTO shapes `ReinsuranceContractTest` (Task 9) asserts against.

- [ ] **Step 1: Write the exception handler**

Copy `distribution/infrastructure/DistributionExceptionHandler.java`'s exact shape — `@RestControllerAdvice` + `@Order(Ordered.HIGHEST_PRECEDENCE)`, a private `problem(...)` helper setting both `errorCode` and `traceId` (openapi-common marks `traceId` required; an M6 contract test caught a filter omitting it). Map:

| Exception | Status | errorCode |
|---|---|---|
| `TreatyNotFoundException` | 404 | `TREATY_NOT_FOUND` |
| `RecoveryNotFoundException` | 404 | `RECOVERY_NOT_FOUND` |
| `ReinsuranceValidationException` | 422 | `REINSURANCE_VALIDATION_FAILED` |
| `InvalidRecoveryStateException` | 409 | `REINSURANCE_INVALID_STATE` |

Do **not** map `IllegalArgumentException` or `AccessDeniedException` — `GlobalExceptionHandler:78` and `:83` already map them to 400 `VALIDATION_ERROR` and 403 `FORBIDDEN`. A duplicate advice is the shadowing bug the `@Order` discipline exists to prevent.

- [ ] **Step 2: Write `MoneyDto` and the response DTOs**

`MoneyDto` — copy `distribution/infrastructure/MoneyDto.java` verbatim (pattern `^-?\\d+(\\.\\d{1,2})?$`, no `@DecimalMin`: reinsurance amounts are always positive by CHECK, but keeping the shared shape avoids a needless divergence, and the constraint would add nothing the DB does not already enforce).

`TreatyResponseDto` — **`cessionPercent` is a decimal STRING**, not a number:

```java
package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyStatus;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyView;

import java.time.LocalDate;
import java.util.UUID;

/** {@code cessionPercent} is a decimal STRING on the wire, never a JSON number: it multiplies
 * money exactly as a commission rate does, docs/06-database-schema.md:32 forbids floats "anywhere
 * in the stack, wire format or storage", and M6 fixed this exact class of defect for
 * DisabilityClaimDetails.impairmentPercent -- which openApi().isValid() provably does not catch. */
public record TreatyResponseDto(UUID treatyId, String reinsurerName, TreatyType treatyType, TreatyStatus status,
                                 MoneyDto retentionLimit, String cessionPercent,
                                 LocalDate effectiveFrom, LocalDate effectiveTo) {

    public static TreatyResponseDto from(TreatyView view) {
        return new TreatyResponseDto(view.treatyId(), view.reinsurerName(), view.treatyType(), view.status(),
            new MoneyDto(view.retentionLimitAmount().toPlainString(), view.retentionLimitCurrency()),
            view.cessionPercent() == null ? null : view.cessionPercent().toPlainString(),
            view.effectiveFrom(), view.effectiveTo());
    }
}
```

`CessionResponseDto` and `ClaimRecoveryResponseDto` — same pattern, mapping their views; `cededPremium` is a nullable `MoneyDto`, `confirmedAt` a nullable `Instant`.

`CreateTreatyRequestDto` — `@NotBlank String reinsurerName`, `@NotNull TreatyType treatyType`, `@Valid @NotNull MoneyDto retentionLimit`, `@Pattern(regexp = "^\\d+(\\.\\d{1,2})?$") String cessionPercent` (nullable), `@NotNull LocalDate effectiveFrom`, nullable `LocalDate effectiveTo`. The QUOTA_SHARE/percent pairing is **not** a bean-validation constraint: `ReinsuranceApiImpl` already enforces it as a 422, and duplicating it as a 400 would give the same malformed request two different status codes depending on which layer noticed first.

- [ ] **Step 3: Write the controllers**

`TreatyController` — `POST /treaties` (201, `Idempotency-Key` required and explicitly rejected when missing/blank via `IllegalArgumentException` → 400, copying `AgentController.requireIdempotencyKey`), `GET /treaties/{treatyId}` (200), `GET /treaties` (200, optional `status` query param). All gated:

```java
@PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
```

Record in the class javadoc that FINANCE_OFFICER/ADMIN is a decision, not a spec quote: `docs/04-api-contracts.md:21` deferred this surface entirely and names no role, there is no reinsurance-specific staff role, and reinsurance is a finance-adjacent back-office concern — the same decision and reasoning M7 recorded for `distribution`.

`RecoveryController` — `GET /policies/{policyNumber}/cessions`, `GET /claims/{claimId}/recoveries`, `POST /claims/{claimId}/recoveries/{recoveryId}/confirm` (202, `Idempotency-Key` required). Same gate.

**The confirm endpoint has two ids in its path and MUST verify they belong together** before acting — resolve `claimId`'s recoveries and require `recoveryId` to be among them, else 404. M7's final review found exactly this class of same-tenant IDOR where a nested id was trusted:

```java
    boolean belongsToClaim = reinsuranceApi.listRecoveriesForClaim(claimId).stream()
        .anyMatch(r -> r.recoveryId().equals(recoveryId));
    if (!belongsToClaim) {
        throw new RecoveryNotFoundException(
            "Recovery " + recoveryId + " not found for claim " + claimId);
    }
```

- [ ] **Step 4: Write `openapi-reinsurance.yaml`**

Model it on `openapi-distribution.yaml`'s structure: `openapi: 3.1.0`, `info.version: "1.0.0"`, shared `$ref`s into `openapi-common.yaml` for `Money`/`ProblemDetails`/`PolicyNumberRef`, reusable `components/parameters` for `TreatyId`/`ClaimId`/`RecoveryId`/`IdempotencyKey`, reusable `components/responses` for `BadRequest`/`Unauthorized`/`Forbidden`/`NotFound`, and `required` lists on every schema. Declare 400/401/403/404/409/422 wherever each is genuinely reachable.

Two rules that are not negotiable: **`cessionPercent` is `type: string` with `pattern: '^\d+(\.\d{1,2})?$'`**, never `type: number`; and **quote every description containing a comma** — an unquoted flow-style YAML description broke an entire contract test's spec load in M4.

- [ ] **Step 5: Write `ReinsuranceSpecParsesTest`**

Copy `distribution/DistributionSpecParsesTest.java`, changing the spec path to `api/openapi/openapi-reinsurance.yaml` and the asserted path keys to `/treaties`, `/treaties/{treatyId}`, `/claims/{claimId}/recoveries`, `/claims/{claimId}/recoveries/{recoveryId}/confirm`. Container-free and millisecond-fast, so a spec mistake surfaces as one localised failure rather than breaking every contract test at once.

- [ ] **Step 6: Run and commit**

```bash
# Targeted: all new code is inside `reinsurance` plus a new OpenAPI file -- nothing shared, no
# other module's production code. Check for URL-mapping collisions first, since new controllers
# load into every Spring context in the suite:
grep -rn '"/treaties\|"/policies/{policyNumber}/cessions\|"/claims/{claimId}/recoveries' \
  --include=*.java src/main/java | grep -v reinsurance/infrastructure
# Expected: no output. NOTE: `claims` already maps /claims/** paths -- verify no exact collision.
./mvnw -B -o test -Dtest='ReinsuranceSpecParsesTest,ReinsuranceApiIntegrationTest'
git add -A
git commit -m "feat: the reinsurance REST layer, OpenAPI contract and exception handler"
```

---

### Task 9: Contract tests, guardrail coverage and doc reconciliation

**Files:**
- Test: `src/test/java/tz/co/nlolo/lifeplatform/reinsurance/ReinsuranceContractTest.java`
- Modify: `src/test/java/tz/co/nlolo/lifeplatform/AppRolePrivilegesIntegrationTest.java`, `RowLevelSecurityIntegrationTest.java`
- Modify: `docs/06-database-schema.md`

- [ ] **Step 1: Write `ReinsuranceContractTest`**

Follow `DistributionContractTest`'s structure exactly: `@Testcontainers` + `@AutoConfigureMockMvc` + `@SpringBootTest(classes = Application.class, webEnvironment = MOCK)`, `jwt()` post-processors, and `openApi().isValid(SPEC_PATH)` **paired with** `SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "<Schema>")` on every response carrying a decimal.

Token helpers: `financeStaffOf(tenantId)` (`ROLE_REALM_STAFF` + `ROLE_FINANCE_OFFICER`), `underwriterStaffOf(tenantId)` (staff but the WRONG fine-grained role), `agentOf(tenantId, partyId)` (`ROLE_REALM_AGENTS`).

One test per genuinely reachable status:
- `POST /treaties`: 201; 422 for a QUOTA_SHARE with no percent; 422 for a SURPLUS carrying a percent; 422 for a blank reinsurer name; 403 for `underwriterStaffOf`; 403 for `agentOf`; 400 for a missing `Idempotency-Key`
- `GET /treaties/{treatyId}`: 200; 404 unknown; 404 cross-tenant (**not** 403 — a 403 would confirm the id exists somewhere)
- `GET /treaties`: 200, and the `status` filter genuinely narrowing (assert the `EXPIRED` case returns an empty array, since a filter ignoring its argument would still pass a positive-only assertion)
- `GET /policies/{policyNumber}/cessions`: 200 with the array
- `GET /claims/{claimId}/recoveries`: 200 with the array
- `POST .../confirm`: 202; 409 on a second confirm; 404 when `recoveryId` belongs to a **different claim** (seed two real claims each with a real recovery, so a broken check returns 202 rather than an incidental 404); 400 for a missing `Idempotency-Key`

Make every 403 non-vacuous by seeding a real, valid target first, so a broken `@PreAuthorize` returns 201/202 rather than an incidental 404. Where a test sends a deliberately schema-invalid request (the missing-header cases), omit the `isValid` matcher and assert status + `errorCode` only, with a comment — `isValid` validates requests too.

- [ ] **Step 2: Add `reinsurance` to `AppRolePrivilegesIntegrationTest`**

Append to its migration list (after the distribution entries):

```java
            "db-migrations/reinsurance/V1__create_reinsurance_schema.sql",
            "db-migrations/reinsurance/V2__grants_rls_money_checks_reinsurer_and_projection.sql");
```

Then add a test proving `app_role` can insert, read back and **update** a `reinsurance.claim_recovery` row through the app's own `DataSource`. The UPDATE half is the point: `confirmed_at` is stamped after the row is written, so a grant allowing only INSERT/SELECT would leave recovery confirmation dead in production while every superuser-connected test passed. Insert a treaty first (`claim_recovery.treaty_id` is a real FK), and `SET` the tenant via `TenantContext.set(tenantId)` as the neighbouring tests do.

- [ ] **Step 3: Add `reinsurance` to `RowLevelSecurityIntegrationTest`**

Append the same two migrations, then add `@Order(10) void cessionAndClaimRecoveryAreTenantIsolatedUnderRls()`: seed a treaty + cession + recovery in each of two tenants as the superuser, assert both rows exist without RLS (the negative control), then read through a genuinely restricted `app_role` connection with `SET ROLE app_role; SET app.current_tenant_id = '<tenantA>'` and assert only tenant A's rows are visible on **both** tables. `cession` and `claim_recovery` are the two whose policies V2 adds — V1 protected neither.

- [ ] **Step 4: Reconcile `docs/06-database-schema.md`**

Add `reinsurance.reinsurance_treaty` and `reinsurance.claim_recovery` to the optimistic-locking list (V2 added `version` to both). State in the same sentence that `reinsurance.cession` is deliberately excluded because it is write-once. The WORM list needs nothing — no reinsurance table is append-only.

- [ ] **Step 5: Run the FULL suite and commit**

```bash
# FULL SUITE REQUIRED (Global Constraints trigger 2): this task changes
# AppRolePrivilegesIntegrationTest and RowLevelSecurityIntegrationTest, which every module's
# correctness leans on.
./mvnw -B -o test
git add -A
git commit -m "test: reinsurance contract tests, the two guardrail gaps, and doc reconciliation"
```

---

### Task 10: Full verification

- [ ] **Step 1: Full clean verify**

```bash
./mvnw -B -o clean verify
```

Expected `BUILD SUCCESS`, including `ModularityTests` (confirming `reinsurance`'s `allowedDependencies` are still exactly `{ refdata::api }` — it consumes policy's and claims' events and produces events `finaccounting` will consume, which is precisely why the dependency must stay event-only), `NoCircularDependencyTest`, `NoCrossModuleJoinTest`, `AlertRuleMetricProducerTest`, `ActuatorExposureTest`.

Aggregate real counts rather than trusting the log tail:

```bash
grep -h "Tests run:" target/surefire-reports/*.txt | awk -F'[:,]' '{t+=$2; f+=$4; e+=$6} END {print "Total:", t, "Failures:", f, "Errors:", e}'
```

- [ ] **Step 2: Mirror the CI `db-migration-validation` job**

Apply every migration in `scripts/migrate.sh`'s order (read that file for the real module ordering — note `reinsurance` runs AFTER `distribution` and BEFORE `refdata`) against a fresh disposable `postgres:16`, then run `db-migrations/_post-migration/verify-partition-controls.sql` and confirm zero drift. Re-run Task 1's privilege query and confirm **four** reinsurance tables all report `rls=t has_policy=t sel=t upd=t`.

- [ ] **Step 3: Acceptance-criteria confirmation**

Confirm `docs/08-implementation-roadmap.md:174` — "Cession calculation tested against a sample treaty; recovery tracking tested against a sample claim" — naming the specific tests, not a blanket pass:
1. **Cession calculation** → `CessionCalculatorTest` (7 cases incl. XOL and currency mismatch) + `CessionEndToEndTest` (a real issued policy against real QUOTA_SHARE and SURPLUS treaties)
2. **Recovery tracking** → `RecoveryCalculatorTest` (6 cases across both paths) + `RecoveryEndToEndTest` (a real claim driven to SETTLED, its recovery calculated and confirmed)
3. **State explicitly** that cession is event-driven per policy rather than a recomputation batch, and that XOL cedes nothing at issuance by design — both are deliberate divergences from a naive reading of "cession calculation" and must not be glossed.

- [ ] **Step 4: Report and stop**

Do not merge. Report the aggregate test count, the acceptance-criteria mapping, every deviation, and every deferred item, so the final whole-branch review has an accurate baseline.

---

## Self-Review Notes

**Judgment calls flagged for the final review — each a place where this plan chose a side.** The cession and recovery algorithms as a whole are invented; these are the specific choices most worth challenging.

1. **Both calculators are invented placeholders.** No document defines cession or recovery on this platform (`docs/03-aggregate-design.md:165` defers to a Rev 1 that is not in this repo). Needs Actuarial sign-off before production use.
2. **XOL cedes nothing at issuance and recovers excess-over-retention instead.** Conceptually right, but it means an XOL treaty produces no `cession` row at all, so anything reading cessions to understand exposure will not see XOL business.
3. **One treaty per policy.** V1's own cession comment implies cumulative cessions across multiple treaties; layering is deferred and needs a priority column V1 lacks.
4. **Ceded premium's proportionality rule** — same percent for quota share, risk-proportional for surplus — is invented. An XOL treaty's separately-negotiated reinsurance premium is not modelled at all.
5. **`claims.ClaimSettled` was enriched rather than adding a new event.** Purely additive and it had zero code consumers, but it is still a shared contract change; `finaccounting` (M9) should consume the new fields rather than re-deriving them.
6. **Recovery confirmation is manual, with no `payment` integration.** A confirmed recovery records that the reinsurer paid; no money actually moves through this platform. Inbound settlement is a milestone of its own.
7. **FINANCE_OFFICER/ADMIN gate everything**, because no reinsurance-specific role exists and `docs/04:21` deferred this surface without naming one.
8. **`cession` has no `version` column**, deliberately — it is write-once. If a future milestone ever mutates a cession, it needs optimistic locking added first.
9. **`RecoveryCalculator.proportional` takes the first cession** when several exist. Today one treaty per policy makes that unambiguous; if layering is ever implemented, this silently picks one and must change.
10. **A ceded premium that rounds to zero records a cession with a null premium** rather than dropping the cession. The ceded risk is real, so dropping it would lose the exposure — but a null premium on a quota-share cession may confuse `finaccounting`.
11. **No `product_id` scoping on a treaty.** Selection (Task 6) considers every ACTIVE treaty in the tenant regardless of what product a policy is. A tenant running two treaty programmes for two different product lines cannot express that today. Deliberately not built — no document mentions this dimension and no acceptance criterion requires it.
12. **The reinsurer is a plain name, not a `party`.** `party` models no reinsurer/corporate-counterparty type today, and `reinsurance` cannot call `party` synchronously in any case. A future milestone giving `party` such a type would need a projection here, exactly like `policy_projection`, not a new dependency edge.
