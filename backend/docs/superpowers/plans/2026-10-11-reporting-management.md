# Reporting Module, Batch 1 (Management) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A new `reporting` module that projects domain events into its own read model, rebuilds it by replaying the audit journal, and serves six management reports (policies in force, new business, lapse rate, persistency, premium collections, clients) on screen and as xlsx/csv/pdf.

**Architecture:** Event listeners (AFTER_COMMIT, REQUIRES_NEW, tenant-scoped) and a rebuild job both feed one handler class that writes idempotent rows (`INSERT ... ON CONFLICT`) into schema `reporting` with JdbcTemplate. Reports are aggregate SQL over those rows, shaped into one generic `ReportResult` that the REST API returns and the three export writers render. The console gets a Reports nav group with a catalogue page and one generic report page.

**Tech Stack:** Java 21, Spring Boot 3, Spring Modulith, JdbcTemplate, PostgreSQL 16, Testcontainers, Apache POI, PDFBox 3, commons-csv; React + TypeScript + zustand + Vite, vitest, Playwright.

**Spec:** `backend/docs/superpowers/specs/2026-10-11-reporting-management-design.md`

## Global Constraints

- Package `tz.co.nlolo.lifeplatform.reporting`; layers `api/` (public Java interface, `@NamedInterface("api")`), `domain/`, `application/`, `infrastructure/`.
- `allowedDependencies = { "audit::api", "party::api", "product::api", "distribution::api" }` -- nothing else.
- No SQL string in `reporting` may name another module's schema (`ReportingSqlStaysInSchemaTest`, Task 1).
- Every SQL query filters `tenant_id = ?` explicitly. Tests connect as the Postgres superuser, where RLS is not enforced, so RLS alone proves nothing in a test.
- Every date stored is an `Africa/Dar_es_Salaam` calendar day (`CivilDates`).
- In force = latest status change on or before the date is `ACTIVE`, `SUSPENDED` or `PAID_UP`, ordered by `effective_on`, `occurred_at`, `event_id`.
- Annualised premium: MONTHLY x12, QUARTERLY x4, ANNUALLY x1; SINGLE reported separately; unrecognised frequency excluded and counted on `lifeplatform_reporting_unknown_premium_frequency_total`; a `PAID_UP` policy contributes 0 annualised premium.
- Money and counts travel as strings in JSON, never JSON numbers.
- Read access: `hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('FINANCE_APPROVER') or hasRole('ADMIN'))`. Rebuild: `hasRole('REALM_STAFF') and hasRole('ADMIN')`.
- Maven runs on the host, in the foreground, never in Docker:
  `export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"` then `./mvnw -B ...` from `backend/`. Run only the affected test classes (`-Dtest=ClassA,ClassB`), never `| head`; confirm the "Tests run:" count is non-zero.
- After any signature change run `./mvnw -B clean test-compile` (incremental compile hides test breaks).
- Stop the dev backend (port 8080) before `clean`.
- Never run Prettier. Edit files with Write/Edit, not shell heredocs.
- Frontend: lint forbids `setState` inside `useEffect` bodies; `openapi-typescript` makes a schema's `default:` field required, so the reporting spec declares no `default:`.

## File Map

Backend (`backend/`):

| File | Responsibility |
|---|---|
| `db-migrations/reporting/V1__create_reporting_schema.sql` | Six tables, RLS, grants |
| `scripts/migrate.sh`, `../.github/workflows/ci-cd.yml` | Add `reporting` to the module lists |
| `src/main/java/.../reporting/package-info.java` | Module + allowed dependencies |
| `reporting/api/*` | `ReportingApi`, `ReportDefinitionView`, `ReportFilter`, `ReportResult`, `ReportSection`, `ReportColumn`, `ColumnType`, `DateFilterKind`, `ReportCategory`, `GroupBy`, `RebuildStatusView`, `ReportNotFoundException`, `ReportParameterException` |
| `reporting/domain/*` | `PayloadReader`, `CivilDates`, `PolicyStatus`, `AnnualisedPremium`, `StatusTimeline`, `AgeBands`, `PersistencyMonths`, `Figures` |
| `reporting/infrastructure/ProjectionStore.java` | All writes |
| `reporting/infrastructure/ReportSql.java` | Shared SQL fragments (CTE, annualised premium, dimensions, filters) |
| `reporting/infrastructure/ReportQueries.java` | All report reads |
| `reporting/infrastructure/ReportController.java`, `ReportingExceptionHandler.java`, `ReportFilterParams.java` | REST |
| `reporting/infrastructure/ReportExporter.java`, `ReportXlsx.java`, `ReportCsv.java`, `ReportPdf.java` | Exports |
| `reporting/application/ReportingEventHandlers.java` | Event -> rows |
| `reporting/application/EventApplier.java` | Tenant + REQUIRES_NEW + failure counting, shared by live and rebuild |
| `reporting/application/ReportingEventListener.java` | Live AFTER_COMMIT listener |
| `reporting/application/RebuildService.java`, `ReportingConfig.java` | Journal replay, clock bean |
| `reporting/application/Labels.java`, `LabelsFactory.java`, `FilterSummary.java`, `ReportFilters.java`, `Report.java` | Report plumbing |
| `reporting/application/PoliciesInForceReport.java`, `NewBusinessReport.java`, `LapseRateReport.java`, `PersistencyReport.java`, `PremiumCollectionsReport.java`, `ClientsReport.java` | The six reports |
| `reporting/application/ReportingApiImpl.java` | Catalogue, run, rebuild |
| `api/openapi/openapi-reporting.yaml` | REST contract |

Frontend (`frontend/`): `src/types/api/reporting.ts` (generated), `src/api/reporting.ts`, `src/store/reportingStore.ts`, `src/auth/claims.ts` (`canSeeReports`), `src/features/reporting/reportParams.ts` (+test), `ReportsCataloguePage.tsx`, `ReportPage.tsx`, `ReportFilterBar.tsx`, `ReportSectionTable.tsx`, `src/lazyPages.ts`, `src/screens.tsx`, `src/screens.test.ts`, `e2e/staff-reports.spec.ts`.

Tests (`backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/`): `ReportingTestMigrations`, `ReportingTestEvents`, domain unit tests, `ProjectionIntegrationTest`, `LiveProjectionIntegrationTest`, `RebuildIntegrationTest`, `ReportQueriesIntegrationTest`, `ReportingSpecParsesTest`, `ReportingContractTest`, `ReportExportTest`, `ReportingSqlStaysInSchemaTest`.

---

### Task 1: Schema, module skeleton, boundary guards

**Files:**
- Create: `backend/db-migrations/reporting/V1__create_reporting_schema.sql`
- Modify: `backend/scripts/migrate.sh` (MODULES line), `.github/workflows/ci-cd.yml:129` (module loop)
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/package-info.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/api/package-info.java`
- Create: `backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/ReportingSqlStaysInSchemaTest.java`
- Create: `backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/ReportingTestMigrations.java`

**Interfaces:**
- Produces: tables `reporting.applied_event`, `report_policy`, `report_policy_status_change`, `report_collection`, `report_client`, `report_lives_change` (columns exactly as below); `ReportingTestMigrations.ALL` (String[]) for every later integration test.

- [ ] **Step 1: Write the boundary test (fails: no module yet, so it passes vacuously -- make it non-vacuous)**

```java
package tz.co.nlolo.lifeplatform.reporting;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reporting module reads nothing but its own schema. NoCrossModuleJoinTest only inspects JPA
 * {@code @Query} annotations, and this module uses JdbcTemplate, so without this test a query
 * joining policy.policy would slip through every existing guard.
 */
class ReportingSqlStaysInSchemaTest {

    private static final Path SOURCE = Path.of("src/main/java/tz/co/nlolo/lifeplatform/reporting");
    private static final Pattern SCHEMA_REF = Pattern.compile("\\b([a-z]+)\\.(?:[a-z_]+)\\b");
    private static final List<String> OTHER_SCHEMAS = List.of("party", "product", "underwriting", "policy",
        "policyloan", "billing", "claims", "distribution", "payment", "reinsurance", "finaccounting",
        "regreporting", "communication", "omnichannel", "audit", "iam", "document", "refdata",
        "benefitpayout", "accumulation", "bonus", "annuity", "unitlinked");

    @Test
    void everySqlStringNamesOnlyTheReportingSchema() throws IOException {
        assertThat(SOURCE).as("the module must exist for this test to mean anything").isDirectory();
        List<String> violations = new ArrayList<>();
        int sqlFiles = 0;
        try (Stream<Path> files = Files.walk(SOURCE)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                if (!text.contains("reporting.report_") && !text.contains("reporting.applied_event")) continue;
                sqlFiles++;
                for (String line : text.split("\\R")) {
                    // Only string literals can carry SQL. Event type names ("policy.PolicyLapsed") never match:
                    // the pattern wants a lower-case table name after the dot.
                    if (!line.contains("\"")) continue;
                    Matcher m = SCHEMA_REF.matcher(line);
                    while (m.find()) {
                        if (OTHER_SCHEMAS.contains(m.group(1))) {
                            violations.add(file.getFileName() + ": " + line.trim());
                        }
                    }
                }
            }
        }
        assertThat(sqlFiles).as("at least one class carries reporting SQL").isPositive();
        assertThat(violations).isEmpty();
    }
}
```

This test needs a class with SQL, so it stays red until Task 3 adds `ProjectionStore`. Mark it `@org.junit.jupiter.api.Disabled("enabled in Task 3 when ProjectionStore exists")` now and remove the annotation in Task 3 Step 1.

- [ ] **Step 2: Write the migration**

`backend/db-migrations/reporting/V1__create_reporting_schema.sql`:

```sql
-- db-migrations/reporting/V1__create_reporting_schema.sql
-- The reporting module's read model (docs/superpowers/specs/2026-10-11-reporting-management-design.md
-- section 5). Every row is projected from a domain event, or re-derived by replaying the audit journal,
-- so nothing here is the record of anything: deleting a tenant's rows and rebuilding is always safe.
-- No foreign key into another module's tables -- a policy number or party id is carried as a value.
CREATE SCHEMA IF NOT EXISTS reporting;
GRANT USAGE ON SCHEMA reporting TO app_role;

-- One row per event already applied. Inserted in the same transaction as the event's rows, so a
-- redelivered or replayed event is a no-op.
CREATE TABLE reporting.applied_event (
    tenant_id  UUID NOT NULL,
    event_id   UUID NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, event_id)
);

-- One row per policy. No status column on purpose: status is always derived from
-- report_policy_status_change, so the order events arrive in cannot leave a stale status behind.
-- product_id 00000000-0000-0000-0000-000000000000 marks a policy seen only through a later event.
CREATE TABLE reporting.report_policy (
    tenant_id          UUID NOT NULL,
    policy_number      VARCHAR(30) NOT NULL,
    holder_party_id    UUID,
    product_id         UUID NOT NULL,
    product_version_id UUID,
    product_category   VARCHAR(40),
    portfolio_code     VARCHAR(10),
    group_scheme       BOOLEAN NOT NULL DEFAULT FALSE,
    agent_id           UUID,
    sales_channel      VARCHAR(40),
    branch_code        VARCHAR(40),
    sum_assured        NUMERIC(19,2),
    premium            NUMERIC(19,2),
    premium_frequency  VARCHAR(20),
    currency           CHAR(3) NOT NULL DEFAULT 'TZS',
    issue_date         DATE,
    activated_on       DATE,
    not_taken_up_on    DATE,
    PRIMARY KEY (tenant_id, policy_number)
);
CREATE INDEX ix_report_policy_activated ON reporting.report_policy (tenant_id, activated_on);
CREATE INDEX ix_report_policy_holder ON reporting.report_policy (tenant_id, holder_party_id);

CREATE TABLE reporting.report_policy_status_change (
    tenant_id     UUID NOT NULL,
    event_id      UUID NOT NULL,
    policy_number VARCHAR(30) NOT NULL,
    to_status     VARCHAR(25) NOT NULL CHECK (to_status IN ('ACTIVE','SUSPENDED','PAID_UP','LAPSED','MATURED',
                      'EXPIRED','CLAIM_TERMINATED','SURRENDERED','CANCELLED_FREE_LOOK','ENDED')),
    cause         VARCHAR(60) NOT NULL,
    effective_on  DATE NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, event_id)
);
CREATE INDEX ix_status_change_policy ON reporting.report_policy_status_change (tenant_id, policy_number, effective_on);
CREATE INDEX ix_status_change_effective ON reporting.report_policy_status_change (tenant_id, effective_on);

CREATE TABLE reporting.report_collection (
    tenant_id     UUID NOT NULL,
    event_id      UUID NOT NULL,
    invoice_id    UUID,
    policy_number VARCHAR(30) NOT NULL,
    amount        NUMERIC(19,2) NOT NULL,
    currency      CHAR(3) NOT NULL DEFAULT 'TZS',
    collected_on  DATE NOT NULL,
    channel       VARCHAR(20) NOT NULL CHECK (channel IN ('MOBILE_MONEY','FIELD_CASH')),
    PRIMARY KEY (tenant_id, event_id)
);
CREATE INDEX ix_collection_date ON reporting.report_collection (tenant_id, collected_on);

CREATE TABLE reporting.report_client (
    tenant_id     UUID NOT NULL,
    party_id      UUID NOT NULL,
    party_type    VARCHAR(15) NOT NULL CHECK (party_type IN ('INDIVIDUAL','CORPORATE','GROUP')),
    kyc_status    VARCHAR(15),
    display_name  VARCHAR(300),
    registered_on DATE,
    date_of_birth DATE,
    sex           VARCHAR(10),
    PRIMARY KEY (tenant_id, party_id)
);

-- One row per member or covered-life change. GroupMemberAdded carries no member id, so members are
-- not tracked one by one; every such event carries livesCovered, the lives on the policy AFTER it.
CREATE TABLE reporting.report_lives_change (
    tenant_id            UUID NOT NULL,
    event_id             UUID NOT NULL,
    policy_number        VARCHAR(30) NOT NULL,
    kind                 VARCHAR(10) NOT NULL CHECK (kind IN ('ACTIVATED','JOINED','EXITED')),
    lives_covered        INTEGER NOT NULL CHECK (lives_covered >= 0),
    scheme_total_covered NUMERIC(19,2),
    effective_on         DATE NOT NULL,
    occurred_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, event_id)
);
CREATE INDEX ix_lives_change_policy ON reporting.report_lives_change (tenant_id, policy_number, effective_on);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['applied_event','report_policy','report_policy_status_change',
                             'report_collection','report_client','report_lives_change'] LOOP
        EXECUTE format('ALTER TABLE reporting.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON reporting.%I USING (tenant_id = '
            'NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)', t || '_tenant_isolation', t);
    END LOOP;
END $$;

-- DELETE is granted because a rebuild clears the tenant's rows before replaying the journal.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA reporting TO app_role;
```

- [ ] **Step 3: Add `reporting` to both module lists**

In `backend/scripts/migrate.sh`, change the `MODULES=` line so it ends `... unitlinked omnichannel reporting"`.
In `.github/workflows/ci-cd.yml` line 129, change `... unitlinked omnichannel; do` to `... unitlinked omnichannel reporting; do`.

- [ ] **Step 4: Module skeleton**

`reporting/package-info.java`:

```java
/**
 * Operational and management reports (spec 2026-10-11-reporting-management-design.md). Learns about
 * the platform from domain events and the audit journal only; reads no other module's tables.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "audit::api", "party::api", "product::api", "distribution::api" })
package tz.co.nlolo.lifeplatform.reporting;
```

`reporting/api/package-info.java`:

```java
@org.springframework.modulith.NamedInterface("api")
package tz.co.nlolo.lifeplatform.reporting.api;
```

`ReportingTestMigrations.java` (test):

```java
package tz.co.nlolo.lifeplatform.reporting;

import tz.co.nlolo.lifeplatform.accumulation.DepositTestMigrations;

import java.util.Arrays;
import java.util.stream.Stream;

/** Every migration a reporting integration test needs: the issuance path (party, product, policy, billing,
 * audit -- DepositTestMigrations.ALL) plus this module's own schema. One list, so the next reporting
 * migration is added in one place. */
public final class ReportingTestMigrations {
    private ReportingTestMigrations() {}

    public static final String[] ALL = Stream.concat(Arrays.stream(DepositTestMigrations.ALL),
        Stream.of("db-migrations/reporting/V1__create_reporting_schema.sql")).toArray(String[]::new);
}
```

- [ ] **Step 5: Run the guards**

Run: `./mvnw -B test -Dtest=MigrationScriptCoverageTest,ModularityTests,TenantTableRlsCoverageIntegrationTest`
Expected: PASS, Tests run > 0. `TenantTableRlsCoverageIntegrationTest` discovers modules from `migrate.sh`, so it now applies `reporting/V1` and checks every `tenant_id` table has RLS.

- [ ] **Step 6: Commit**

```bash
git add backend/db-migrations/reporting backend/scripts/migrate.sh .github/workflows/ci-cd.yml \
  backend/src/main/java/tz/co/nlolo/lifeplatform/reporting backend/src/test/java/tz/co/nlolo/lifeplatform/reporting
git commit -m "feat(reporting): schema and module skeleton"
```

---

### Task 2: Domain helpers

**Files:**
- Create in `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/domain/`: `PayloadReader.java`, `CivilDates.java`, `PolicyStatus.java`, `AnnualisedPremium.java`, `StatusTimeline.java`, `AgeBands.java`, `PersistencyMonths.java`, `Figures.java`
- Test in `backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/domain/`: `PayloadReaderTest.java`, `StatusTimelineTest.java`, `DomainArithmeticTest.java`

**Interfaces:**
- Produces:
  - `PayloadReader.text/uuid/date/instant/integer/flag/amount/currency(Map<String,Object>, String)` returning `String/UUID/LocalDate/Instant/Integer/boolean/BigDecimal/String` (null when absent)
  - `CivilDates.ZONE`, `CivilDates.of(Instant) -> LocalDate`, `CivilDates.today(Clock) -> LocalDate`
  - `enum PolicyStatus { ACTIVE, SUSPENDED, PAID_UP, LAPSED, MATURED, EXPIRED, CLAIM_TERMINATED, SURRENDERED, CANCELLED_FREE_LOOK, ENDED; boolean inForce(); }`
  - `AnnualisedPremium.of(BigDecimal, String) -> Optional<BigDecimal>`, `isSingle(String)`, `isRecognised(String)`
  - `StatusTimeline(Collection<StatusTimeline.Change>)`, `record Change(LocalDate effectiveOn, Instant occurredAt, UUID eventId, PolicyStatus status)`, `statusOn(LocalDate) -> Optional<PolicyStatus>`, `inForceOn(LocalDate)`, `ever(PolicyStatus)`
  - `AgeBands.ORDER (List<String>)`, `AgeBands.of(LocalDate dob, LocalDate asOf) -> String`
  - `PersistencyMonths.MONTHS (List<Integer>)`, `measuredOn(LocalDate, int)`, `reached(YearMonth cohortTo, int month, LocalDate today)`
  - `Figures.money(BigDecimal) -> String`, `count(long) -> String`, `percent(BigDecimal num, BigDecimal den) -> String`

- [ ] **Step 1: Write the failing tests**

`PayloadReaderTest.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A live payload carries Java objects; the same payload read back from the audit journal is JSON. Every
 * reader must give the same answer for both, or a rebuild would project different rows than live did. */
class PayloadReaderTest {

    private static final UUID PRODUCT = UUID.fromString("7f8e0f9a-1111-4c2d-9e0b-222222222222");

    private static Map<String, Object> typed() {
        Map<String, Object> p = new HashMap<>();
        p.put("productId", PRODUCT);
        p.put("issueDate", LocalDate.of(2026, 3, 4));
        p.put("lapsedAt", Instant.parse("2026-05-31T22:15:00Z"));
        p.put("livesCovered", 12);
        p.put("groupScheme", true);
        p.put("sumAssured", Map.of("amount", "1500000.00", "currencyCode", "TZS"));
        p.put("paidUpSumAssured", "400000.00");
        p.put("agentOfRecordId", null);
        return p;
    }

    private static Map<String, Object> asJournal(Map<String, Object> typed) throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper.readValue(mapper.writeValueAsString(typed), new TypeReference<>() {});
    }

    @Test
    void typedAndJournalPayloadsReadTheSame() throws Exception {
        for (Map<String, Object> p : new Map[] { typed(), asJournal(typed()) }) {
            assertThat(PayloadReader.uuid(p, "productId")).isEqualTo(PRODUCT);
            assertThat(PayloadReader.date(p, "issueDate")).isEqualTo(LocalDate.of(2026, 3, 4));
            assertThat(PayloadReader.instant(p, "lapsedAt")).isEqualTo(Instant.parse("2026-05-31T22:15:00Z"));
            assertThat(PayloadReader.integer(p, "livesCovered")).isEqualTo(12);
            assertThat(PayloadReader.flag(p, "groupScheme")).isTrue();
            assertThat(PayloadReader.amount(p, "sumAssured")).isEqualByComparingTo("1500000.00");
            assertThat(PayloadReader.currency(p, "sumAssured")).isEqualTo("TZS");
            assertThat(PayloadReader.amount(p, "paidUpSumAssured")).isEqualByComparingTo(new BigDecimal("400000.00"));
            assertThat(PayloadReader.uuid(p, "agentOfRecordId")).isNull();
            assertThat(PayloadReader.text(p, "missing")).isNull();
        }
    }

    @Test
    void anEpochSecondsInstantIsAccepted() {
        assertThat(PayloadReader.instant(Map.of("at", "1780265700.000000000"), "at"))
            .isEqualTo(Instant.ofEpochSecond(1780265700L));
    }
}
```

`StatusTimelineTest.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StatusTimelineTest {

    private static StatusTimeline.Change change(String date, String at, PolicyStatus status) {
        return new StatusTimeline.Change(LocalDate.parse(date), Instant.parse(at), UUID.randomUUID(), status);
    }

    @Test
    void theLatestChangeOnOrBeforeTheDateWins() {
        StatusTimeline t = new StatusTimeline(List.of(
            change("2025-03-01", "2025-03-01T08:00:00Z", PolicyStatus.LAPSED),     // given out of order
            change("2025-01-10", "2025-01-10T08:00:00Z", PolicyStatus.ACTIVE),
            change("2025-04-15", "2025-04-15T08:00:00Z", PolicyStatus.ACTIVE)));   // reinstated
        assertThat(t.statusOn(LocalDate.parse("2025-01-09"))).isEmpty();
        assertThat(t.inForceOn(LocalDate.parse("2025-01-10"))).isTrue();
        assertThat(t.inForceOn(LocalDate.parse("2025-03-01"))).isFalse();
        assertThat(t.inForceOn(LocalDate.parse("2025-04-15"))).isTrue();
    }

    @Test
    void sameDayChangesAreOrderedByTheirTimestamps() {
        StatusTimeline t = new StatusTimeline(List.of(
            change("2025-06-01", "2025-06-01T15:00:00Z", PolicyStatus.SURRENDERED),
            change("2025-06-01", "2025-06-01T09:00:00Z", PolicyStatus.ACTIVE)));
        assertThat(t.statusOn(LocalDate.parse("2025-06-01"))).contains(PolicyStatus.SURRENDERED);
    }

    @Test
    void suspendedAndPaidUpAreInForce() {
        assertThat(PolicyStatus.SUSPENDED.inForce()).isTrue();
        assertThat(PolicyStatus.PAID_UP.inForce()).isTrue();
        assertThat(PolicyStatus.LAPSED.inForce()).isFalse();
        assertThat(new StatusTimeline(List.of(change("2025-01-01", "2025-01-01T00:00:00Z",
            PolicyStatus.CANCELLED_FREE_LOOK))).ever(PolicyStatus.CANCELLED_FREE_LOOK)).isTrue();
    }
}
```

`DomainArithmeticTest.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class DomainArithmeticTest {

    @Test
    void annualisedPremiumMultipliesByInstalmentsAndRefusesSingleAndUnknown() {
        assertThat(AnnualisedPremium.of(new BigDecimal("1000"), "MONTHLY")).contains(new BigDecimal("12000"));
        assertThat(AnnualisedPremium.of(new BigDecimal("1000"), "QUARTERLY")).contains(new BigDecimal("4000"));
        assertThat(AnnualisedPremium.of(new BigDecimal("1000"), "ANNUALLY")).contains(new BigDecimal("1000"));
        assertThat(AnnualisedPremium.of(new BigDecimal("1000"), "SINGLE")).isEmpty();
        assertThat(AnnualisedPremium.of(new BigDecimal("1000"), "FORTNIGHTLY")).isEmpty();
        assertThat(AnnualisedPremium.isSingle("SINGLE")).isTrue();
        assertThat(AnnualisedPremium.isRecognised("FORTNIGHTLY")).isFalse();
    }

    @Test
    void aLateUtcEveningIsTheNextTanzanianDay() {
        assertThat(CivilDates.of(Instant.parse("2026-10-10T22:30:00Z"))).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(CivilDates.of(Instant.parse("2026-10-10T20:59:59Z"))).isEqualTo(LocalDate.of(2026, 10, 10));
    }

    @Test
    void ageBandsAreAgeOnTheAsOfDate() {
        LocalDate asOf = LocalDate.of(2026, 10, 11);
        assertThat(AgeBands.of(LocalDate.of(2008, 10, 12), asOf)).isEqualTo("0-17");
        assertThat(AgeBands.of(LocalDate.of(2008, 10, 11), asOf)).isEqualTo("18-24");
        assertThat(AgeBands.of(LocalDate.of(1990, 1, 1), asOf)).isEqualTo("35-44");
        assertThat(AgeBands.of(LocalDate.of(1950, 1, 1), asOf)).isEqualTo("65+");
        assertThat(AgeBands.of(null, asOf)).isEqualTo("Unknown");
        assertThat(AgeBands.ORDER).containsExactly("0-17", "18-24", "25-34", "35-44", "45-54", "55-64", "65+", "Unknown");
    }

    @Test
    void persistencyMonthIsMeasuredAtTheStartOfThatMonthAndOnlyOnceTheWholeCohortReachedIt() {
        assertThat(PersistencyMonths.measuredOn(LocalDate.of(2024, 3, 15), 13)).isEqualTo(LocalDate.of(2025, 3, 15));
        assertThat(PersistencyMonths.reached(YearMonth.of(2024, 3), 13, LocalDate.of(2025, 3, 31))).isTrue();
        assertThat(PersistencyMonths.reached(YearMonth.of(2024, 3), 13, LocalDate.of(2025, 3, 30))).isFalse();
        assertThat(PersistencyMonths.MONTHS).containsExactly(13, 25, 37, 61);
    }

    @Test
    void figuresAreStringsWithFixedScale() {
        assertThat(Figures.money(new BigDecimal("1234.5"))).isEqualTo("1234.50");
        assertThat(Figures.money(null)).isEqualTo("0.00");
        assertThat(Figures.count(7)).isEqualTo("7");
        assertThat(Figures.percent(new BigDecimal("1"), new BigDecimal("3"))).isEqualTo("33.33");
        assertThat(Figures.percent(BigDecimal.ONE, BigDecimal.ZERO)).isEqualTo("");
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -B test -Dtest=PayloadReaderTest,StatusTimelineTest,DomainArithmeticTest`
Expected: compilation FAILURE (classes do not exist).

- [ ] **Step 3: Implement**

`PayloadReader.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Reads event payload fields whether they arrive as Java objects (a live event) or as JSON strings and
 * numbers (the same event replayed from the audit journal). Every field goes through
 * {@code String.valueOf} and is parsed, so no handler ever casts {@code (UUID) payload.get(..)} -- the
 * cast regreporting uses, which a replayed payload would break.
 */
public final class PayloadReader {
    private PayloadReader() {}

    public static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public static UUID uuid(Map<String, Object> payload, String key) {
        String s = text(payload, key);
        return s == null || s.isBlank() ? null : UUID.fromString(s);
    }

    public static LocalDate date(Map<String, Object> payload, String key) {
        String s = text(payload, key);
        return s == null || s.isBlank() ? null : LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }

    /** ISO-8601, or epoch seconds with a fraction -- the shape Jackson writes an Instant in when dates are
     * serialised as timestamps. */
    public static Instant instant(Map<String, Object> payload, String key) {
        String s = text(payload, key);
        if (s == null || s.isBlank()) return null;
        if (s.matches("\\d+(\\.\\d+)?")) {
            BigDecimal seconds = new BigDecimal(s);
            return Instant.ofEpochSecond(seconds.longValue(),
                seconds.remainder(BigDecimal.ONE).movePointRight(9).longValue());
        }
        return Instant.parse(s);
    }

    public static Integer integer(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        return Integer.valueOf(String.valueOf(value));
    }

    public static boolean flag(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof Boolean b ? b : value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    /** A money object's amount ({@code {"amount": "...", "currencyCode": "..."}}), or a plain decimal. */
    public static BigDecimal amount(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Map<?, ?> money) value = money.get("amount");
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }

    public static String currency(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Map<?, ?> money && money.get("currencyCode") != null) {
            return String.valueOf(money.get("currencyCode"));
        }
        return null;
    }
}
```

`CivilDates.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Every date this module stores is a Tanzanian calendar day. An event at 22:30 UTC on the 10th happened on
 * the 11th in Dar es Salaam, and a month-end report must count it there. */
public final class CivilDates {
    private CivilDates() {}

    public static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    public static LocalDate of(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }
}
```

`PolicyStatus.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.domain;

/** The statuses a policy's history can move through. SUSPENDED and PAID_UP still count as in force: a
 * suspended policy has not lapsed (regreporting made the same decision), and a paid-up one is still on cover. */
public enum PolicyStatus {
    ACTIVE(true), SUSPENDED(true), PAID_UP(true),
    LAPSED(false), MATURED(false), EXPIRED(false), CLAIM_TERMINATED(false), SURRENDERED(false),
    CANCELLED_FREE_LOOK(false), ENDED(false);

    private final boolean inForce;

    PolicyStatus(boolean inForce) {
        this.inForce = inForce;
    }

    public boolean inForce() {
        return inForce;
    }
}
```

`AnnualisedPremium.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.domain;

import java.math.BigDecimal;
import java.util.Optional;

/** Premium x instalments per year (product.api.PremiumFrequency: MONTHLY 12, QUARTERLY 4, ANNUALLY 1).
 * SINGLE is reported separately and never blended in. An unrecognised frequency gives nothing rather than
 * a default of 12: a wrong multiplier on a live figure is worse than a visibly missing one. */
public final class AnnualisedPremium {
    private AnnualisedPremium() {}

    public static Optional<BigDecimal> of(BigDecimal premium, String frequency) {
        if (premium == null || frequency == null) return Optional.empty();
        return switch (frequency) {
            case "MONTHLY" -> Optional.of(premium.multiply(BigDecimal.valueOf(12)));
            case "QUARTERLY" -> Optional.of(premium.multiply(BigDecimal.valueOf(4)));
            case "ANNUALLY" -> Optional.of(premium);
            default -> Optional.empty();
        };
    }

    public static boolean isSingle(String frequency) {
        return "SINGLE".equals(frequency);
    }

    public static boolean isRecognised(String frequency) {
        return frequency != null && switch (frequency) {
            case "MONTHLY", "QUARTERLY", "ANNUALLY", "SINGLE" -> true;
            default -> false;
        };
    }
}
```

`StatusTimeline.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One policy's status history. Ordered exactly as the SQL in ReportSql orders it -- effective date, then the
 * event's timestamp, then the event id as text (Postgres orders uuid bytewise, which is the order of the
 * lower-case hex string) -- so a Java answer and a SQL answer never disagree on a tie.
 */
public final class StatusTimeline {

    public record Change(LocalDate effectiveOn, Instant occurredAt, UUID eventId, PolicyStatus status) {}

    private static final Comparator<Change> ORDER = Comparator.comparing(Change::effectiveOn)
        .thenComparing(Change::occurredAt).thenComparing(c -> c.eventId().toString());

    private final List<Change> changes;

    public StatusTimeline(Collection<Change> changes) {
        this.changes = changes.stream().sorted(ORDER).toList();
    }

    public Optional<PolicyStatus> statusOn(LocalDate date) {
        PolicyStatus status = null;
        for (Change c : changes) {
            if (c.effectiveOn().isAfter(date)) break;
            status = c.status();
        }
        return Optional.ofNullable(status);
    }

    public boolean inForceOn(LocalDate date) {
        return statusOn(date).map(PolicyStatus::inForce).orElse(false);
    }

    public boolean ever(PolicyStatus status) {
        return changes.stream().anyMatch(c -> c.status() == status);
    }
}
```

`AgeBands.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.domain;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;

public final class AgeBands {
    private AgeBands() {}

    public static final List<String> ORDER = List.of("0-17", "18-24", "25-34", "35-44", "45-54", "55-64", "65+", "Unknown");

    public static String of(LocalDate dateOfBirth, LocalDate asOf) {
        if (dateOfBirth == null) return "Unknown";
        int age = Period.between(dateOfBirth, asOf).getYears();
        if (age < 18) return "0-17";
        if (age < 25) return "18-24";
        if (age < 35) return "25-34";
        if (age < 45) return "35-44";
        if (age < 55) return "45-54";
        if (age < 65) return "55-64";
        return "65+";
    }
}
```

`PersistencyMonths.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** Persistency at month N is measured at the START of month N: activation date plus N-1 months. A month is
 * reported only once every policy in the cohort has reached it, so a half-measured column never appears. */
public final class PersistencyMonths {
    private PersistencyMonths() {}

    public static final List<Integer> MONTHS = List.of(13, 25, 37, 61);

    public static LocalDate measuredOn(LocalDate activatedOn, int month) {
        return activatedOn.plusMonths(month - 1L);
    }

    public static boolean reached(YearMonth cohortTo, int month, LocalDate today) {
        return !measuredOn(cohortTo.atEndOfMonth(), month).isAfter(today);
    }
}
```

`Figures.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** How a figure is written into a report row. Strings, because money and counts never travel as JSON
 * numbers on this platform. A rate with a zero denominator is blank, not zero and not infinity. */
public final class Figures {
    private Figures() {}

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static String count(long value) {
        return Long.toString(value);
    }

    public static String percent(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) return "";
        return numerator.multiply(HUNDRED).divide(denominator, 2, RoundingMode.HALF_UP).toPlainString();
    }
}
```

- [ ] **Step 4: Run to verify they pass**

Run: `./mvnw -B test -Dtest=PayloadReaderTest,StatusTimelineTest,DomainArithmeticTest`
Expected: PASS, Tests run: 10.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/domain backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/domain
git commit -m "feat(reporting): domain helpers for payloads, dates, status history and figures"
```

---

### Task 3: Projection store and policy/billing/lives handlers

**Files:**
- Create: `reporting/infrastructure/ProjectionStore.java`
- Create: `reporting/application/ReportingEventHandlers.java`
- Modify: `ReportingSqlStaysInSchemaTest.java` (remove `@Disabled`)
- Test: `reporting/ProjectionIntegrationTest.java`

**Interfaces:**
- Consumes: Task 2 domain classes.
- Produces:
  - `ProjectionStore.markApplied(UUID tenant, UUID eventId) -> boolean`, `upsertPolicy(UUID, ProjectionStore.PolicyFacts)`, `setActivated(UUID, String, LocalDate)`, `setNotTakenUp(UUID, String, LocalDate)`, `setSumAssured(UUID, String, BigDecimal)`, `isGroupScheme(UUID, String) -> boolean`, `ensurePolicy(UUID, String) -> boolean` (true when it had to create an UNKNOWN row), `addStatusChange(UUID, UUID eventId, String policyNumber, PolicyStatus, String cause, LocalDate, Instant)`, `addCollection(UUID, UUID eventId, UUID invoiceId, String policyNumber, BigDecimal, String currency, LocalDate, String channel)`, `upsertClient(UUID, ProjectionStore.ClientFacts)`, `setKycStatus(UUID, UUID partyId, String) -> boolean`, `addLivesChange(UUID, UUID eventId, String policyNumber, String kind, int lives, BigDecimal total, LocalDate, Instant)`, `deleteTenant(UUID)`
  - `record PolicyFacts(String policyNumber, UUID holderPartyId, UUID productId, UUID productVersionId, String productCategory, String portfolioCode, boolean groupScheme, UUID agentId, String salesChannel, String branchCode, BigDecimal sumAssured, BigDecimal premium, String premiumFrequency, String currency, LocalDate issueDate)`
  - `record ClientFacts(UUID partyId, String partyType, String kycStatus, String displayName, LocalDate registeredOn, LocalDate dateOfBirth, String sex)`
  - `ReportingEventHandlers.HANDLED (Set<String>)`, `handles(String) -> boolean`, `apply(UUID tenant, UUID eventId, String eventType, Instant occurredAt, Map<String,Object> payload) -> boolean` (false = duplicate); constant `UNKNOWN_PRODUCT = new UUID(0L, 0L)`

- [ ] **Step 1: Write the failing integration test**

Remove the `@Disabled` line from `ReportingSqlStaysInSchemaTest`.

`ProjectionIntegrationTest.java`:

```java
package tz.co.nlolo.lifeplatform.reporting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reporting.application.ReportingEventHandlers;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = Application.class)
class ProjectionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            ReportingTestMigrations.ALL);
    }

    @Autowired private ReportingEventHandlers handlers;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private MeterRegistry meters;
    private final ObjectMapper mapper = new ObjectMapper();

    private final UUID tenant = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID HOLDER = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private boolean apply(UUID eventId, String type, Instant at, Map<String, Object> payload) {
        TenantContext.set(tenant);
        Boolean applied = new TransactionTemplate(txManager).execute(s -> handlers.apply(tenant, eventId, type, at, payload));
        return Boolean.TRUE.equals(applied);
    }

    private Map<String, Object> journal(Map<String, Object> typed) {
        try {
            return mapper.readValue(mapper.writeValueAsString(typed), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> issued(String policyNumber) {
        Map<String, Object> p = new HashMap<>();
        p.put("policyNumber", policyNumber);
        p.put("policyholderPartyId", HOLDER);
        p.put("productId", PRODUCT);
        p.put("productVersionId", UUID.randomUUID());
        p.put("sumAssured", Map.of("amount", "2000000.00", "currencyCode", "TZS"));
        p.put("premium", Map.of("amount", "15000.00", "currencyCode", "TZS"));
        p.put("premiumFrequency", "MONTHLY");
        p.put("issueDate", "2026-02-01");
        p.put("agentOfRecordId", AGENT);
        p.put("status", "PROPOSED");
        p.put("salesChannel", "AGENCY");
        p.put("branchCode", "DSM-01");
        p.put("portfolioCode", "FUN");
        return p;
    }

    private static Map<String, Object> activated(String policyNumber) {
        Map<String, Object> p = new HashMap<>(issued(policyNumber));
        p.remove("salesChannel");
        p.remove("branchCode");
        p.put("activatedAt", "2026-02-03");
        p.put("productCategory", "FUNERAL");
        p.put("groupScheme", false);
        p.put("livesCovered", 1);
        return p;
    }

    private Map<String, Object> policyRow(String policyNumber) {
        return jdbc.queryForMap("SELECT holder_party_id, product_id, product_category, portfolio_code, group_scheme, agent_id,"
            + " sales_channel, branch_code, sum_assured, premium, premium_frequency, currency, issue_date, activated_on"
            + " FROM reporting.report_policy WHERE tenant_id = ? AND policy_number = ?", tenant, policyNumber);
    }

    private List<Map<String, Object>> statuses(String policyNumber) {
        return jdbc.queryForList("SELECT to_status, cause, effective_on FROM reporting.report_policy_status_change"
            + " WHERE tenant_id = ? AND policy_number = ? ORDER BY effective_on, occurred_at", tenant, policyNumber);
    }

    @Test
    void issuedThenActivatedProjectsOnePolicyAndOneActiveChange() {
        apply(UUID.randomUUID(), "policy.PolicyIssued", Instant.parse("2026-02-01T09:00:00Z"), issued("FUN-1"));
        apply(UUID.randomUUID(), "policy.PolicyActivated", Instant.parse("2026-02-03T09:00:00Z"), activated("FUN-1"));

        Map<String, Object> row = policyRow("FUN-1");
        assertThat(row.get("product_id")).isEqualTo(PRODUCT);
        assertThat(row.get("agent_id")).isEqualTo(AGENT);
        assertThat(row.get("sales_channel")).isEqualTo("AGENCY");
        assertThat(row.get("branch_code")).isEqualTo("DSM-01");
        assertThat(row.get("product_category")).isEqualTo("FUNERAL");
        assertThat(String.valueOf(row.get("activated_on"))).isEqualTo("2026-02-03");
        assertThat(statuses("FUN-1")).extracting(r -> r.get("to_status")).containsExactly("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT lives_covered FROM reporting.report_lives_change WHERE tenant_id = ?"
            + " AND policy_number = 'FUN-1'", Integer.class, tenant)).isEqualTo(1);
    }

    @Test
    void aJournalShapedPayloadProjectsTheSameRowsAsALiveOne() {
        apply(UUID.randomUUID(), "policy.PolicyIssued", Instant.parse("2026-02-01T09:00:00Z"), issued("FUN-2"));
        apply(UUID.randomUUID(), "policy.PolicyActivated", Instant.parse("2026-02-03T09:00:00Z"), activated("FUN-2"));
        apply(UUID.randomUUID(), "policy.PolicyIssued", Instant.parse("2026-02-01T09:00:00Z"), journal(issued("FUN-3")));
        apply(UUID.randomUUID(), "policy.PolicyActivated", Instant.parse("2026-02-03T09:00:00Z"), journal(activated("FUN-3")));
        assertThat(policyRow("FUN-3")).isEqualTo(policyRow("FUN-2"));
    }

    @Test
    void activationArrivingBeforeIssuanceStillKeepsChannelAndBranch() {
        Instant same = Instant.parse("2026-02-03T09:00:00Z");
        apply(UUID.randomUUID(), "policy.PolicyActivated", same, activated("FUN-4"));
        apply(UUID.randomUUID(), "policy.PolicyIssued", same, issued("FUN-4"));
        assertThat(policyRow("FUN-4").get("sales_channel")).isEqualTo("AGENCY");
        assertThat(statuses("FUN-4")).extracting(r -> r.get("to_status")).containsExactly("ACTIVE");
    }

    @Test
    void aRepeatedEventIdIsAppliedOnce() {
        UUID eventId = UUID.randomUUID();
        apply(UUID.randomUUID(), "policy.PolicyActivated", Instant.parse("2026-02-03T09:00:00Z"), activated("FUN-5"));
        Map<String, Object> lapse = Map.of("policyNumber", "FUN-5", "lapsedAt", "2026-05-01T10:00:00Z");
        assertThat(apply(eventId, "policy.PolicyLapsed", Instant.parse("2026-05-01T10:00:00Z"), lapse)).isTrue();
        assertThat(apply(eventId, "policy.PolicyLapsed", Instant.parse("2026-05-01T10:00:00Z"), lapse)).isFalse();
        assertThat(statuses("FUN-5")).extracting(r -> r.get("to_status")).containsExactly("ACTIVE", "LAPSED");
    }

    @Test
    void aLapseForAnUnseenPolicyIsKeptUnderTheUnknownProductAndCounted() {
        double before = meters.counter("lifeplatform_reporting_unattributed_total", "eventType", "policy.PolicyLapsed").count();
        apply(UUID.randomUUID(), "policy.PolicyLapsed", Instant.parse("2026-05-01T10:00:00Z"),
            Map.of("policyNumber", "GHOST-1", "lapsedAt", "2026-05-01T10:00:00Z"));
        assertThat(policyRow("GHOST-1").get("product_id")).isEqualTo(ReportingEventHandlers.UNKNOWN_PRODUCT);
        assertThat(meters.counter("lifeplatform_reporting_unattributed_total", "eventType", "policy.PolicyLapsed").count())
            .isEqualTo(before + 1);
    }

    @Test
    void policySurrenderedMeansThreeDifferentThings() {
        apply(UUID.randomUUID(), "policy.PolicyActivated", Instant.parse("2026-02-03T09:00:00Z"), activated("END-1"));
        apply(UUID.randomUUID(), "policy.PolicyActivated", Instant.parse("2026-02-03T09:00:00Z"), activated("END-2"));
        Map<String, Object> scheme = new HashMap<>(activated("GRP-9"));
        scheme.put("groupScheme", true);
        apply(UUID.randomUUID(), "policy.PolicyActivated", Instant.parse("2026-02-03T09:00:00Z"), scheme);

        apply(UUID.randomUUID(), "policy.PolicySurrendered", Instant.parse("2026-06-01T10:00:00Z"),
            Map.of("policyNumber", "END-1", "surrenderedAt", "2026-06-01T10:00:00Z", "claimId", UUID.randomUUID()));
        apply(UUID.randomUUID(), "policy.PolicySurrendered", Instant.parse("2026-06-01T10:00:00Z"),
            Map.of("policyNumber", "END-2", "surrenderedAt", "2026-06-01T10:00:00Z"));
        apply(UUID.randomUUID(), "policy.PolicySurrendered", Instant.parse("2026-06-01T10:00:00Z"),
            Map.of("policyNumber", "GRP-9", "surrenderedAt", "2026-06-01T10:00:00Z"));

        assertThat(statuses("END-1").get(1).get("to_status")).isEqualTo("CLAIM_TERMINATED");
        assertThat(statuses("END-2").get(1).get("to_status")).isEqualTo("SURRENDERED");
        assertThat(statuses("GRP-9").get(1).get("to_status")).isEqualTo("ENDED");
    }

    @Test
    void paidUpRecordsTheReducedSumAssured() {
        apply(UUID.randomUUID(), "policy.PolicyActivated", Instant.parse("2026-02-03T09:00:00Z"), activated("END-3"));
        apply(UUID.randomUUID(), "policy.PolicyMadePaidUp", Instant.parse("2026-07-01T10:00:00Z"), Map.of(
            "policyNumber", "END-3", "madePaidUpAt", "2026-07-01T10:00:00Z",
            "paidUpSumAssured", Map.of("amount", "800000.00", "currencyCode", "TZS"),
            "originalSumAssured", Map.of("amount", "2000000.00", "currencyCode", "TZS")));
        assertThat(policyRow("END-3").get("sum_assured")).isEqualTo(new java.math.BigDecimal("800000.00"));
        assertThat(statuses("END-3").get(1).get("to_status")).isEqualTo("PAID_UP");
    }

    @Test
    void aCollectionIsMobileMoneyOnlyWhenItHasAPayerNumber() {
        apply(UUID.randomUUID(), "billing.PremiumCollected", Instant.parse("2026-03-31T21:30:00Z"), Map.of(
            "invoiceId", UUID.randomUUID(), "policyNumber", "FUN-1", "policyholderPartyId", HOLDER,
            "amount", Map.of("amount", "15000.00", "currencyCode", "TZS"), "collectedAt", "2026-03-31T21:30:00Z",
            "payerRef", "255712000111"));
        apply(UUID.randomUUID(), "billing.PremiumCollected", Instant.parse("2026-04-02T08:00:00Z"), Map.of(
            "invoiceId", UUID.randomUUID(), "policyNumber", "FUN-1", "policyholderPartyId", HOLDER,
            "amount", Map.of("amount", "15000.00", "currencyCode", "TZS"), "collectedAt", "2026-04-02T08:00:00Z"));
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT collected_on, channel FROM reporting.report_collection"
            + " WHERE tenant_id = ? ORDER BY collected_on", tenant);
        // 21:30 UTC on 31 March is 00:30 on 1 April in Dar es Salaam.
        assertThat(String.valueOf(rows.get(0).get("collected_on"))).isEqualTo("2026-04-01");
        assertThat(rows).extracting(r -> r.get("channel")).containsExactly("MOBILE_MONEY", "FIELD_CASH");
    }

    @Test
    void memberEventsRecordTheLivesAfterEachChange() {
        apply(UUID.randomUUID(), "policy.GroupMemberAdded", Instant.parse("2026-03-01T09:00:00Z"), Map.of(
            "policyNumber", "GRP-1", "joinedOn", "2026-03-01", "livesCovered", 41,
            "schemeTotalCovered", Map.of("amount", "41000000.00", "currencyCode", "TZS")));
        apply(UUID.randomUUID(), "policy.GroupMemberExited", Instant.parse("2026-03-09T09:00:00Z"), Map.of(
            "policyNumber", "GRP-1", "policyMemberId", UUID.randomUUID(), "leftOn", "2026-03-09", "reason", "REPAID",
            "livesCovered", 40, "schemeTotalCovered", Map.of("amount", "40000000.00", "currencyCode", "TZS")));
        assertThat(jdbc.queryForList("SELECT kind, lives_covered FROM reporting.report_lives_change WHERE tenant_id = ?"
            + " AND policy_number = 'GRP-1' ORDER BY effective_on", tenant))
            .extracting(r -> r.get("kind") + ":" + r.get("lives_covered")).containsExactly("JOINED:41", "EXITED:40");
    }

    @Test
    void anUnhandledEventTypeIsNotApplied() {
        assertThat(handlers.handles("claims.ClaimRegistered")).isFalse();
        assertThat(apply(UUID.randomUUID(), "claims.ClaimRegistered", Instant.now(), Map.of())).isFalse();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -B test -Dtest=ProjectionIntegrationTest,ReportingSqlStaysInSchemaTest`
Expected: compilation FAILURE (`ReportingEventHandlers` missing).

- [ ] **Step 3: Implement `ProjectionStore`**

```java
package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tz.co.nlolo.lifeplatform.reporting.domain.PolicyStatus;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Every write the reporting module makes. Each one is idempotent on its own (ON CONFLICT), and the
 * applied_event row makes a whole event idempotent. Callers run inside one transaction per event with
 * TenantContext set, and pass the tenant explicitly as well: tests connect as a superuser, where RLS is off.
 */
@Repository
public class ProjectionStore {

    private static final UUID UNKNOWN_PRODUCT = new UUID(0L, 0L);
    private static final List<String> TABLES = List.of("report_policy", "report_policy_status_change",
        "report_collection", "report_client", "report_lives_change", "applied_event");

    private final JdbcTemplate jdbc;

    public ProjectionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PolicyFacts(String policyNumber, UUID holderPartyId, UUID productId, UUID productVersionId,
                              String productCategory, String portfolioCode, boolean groupScheme, UUID agentId,
                              String salesChannel, String branchCode, BigDecimal sumAssured, BigDecimal premium,
                              String premiumFrequency, String currency, LocalDate issueDate) {}

    public record ClientFacts(UUID partyId, String partyType, String kycStatus, String displayName,
                              LocalDate registeredOn, LocalDate dateOfBirth, String sex) {}

    /** True when the event was not applied before. */
    public boolean markApplied(UUID tenantId, UUID eventId) {
        return jdbc.update("INSERT INTO reporting.applied_event (tenant_id, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            tenantId, eventId) == 1;
    }

    /**
     * Inserts the policy, or fills only the columns still empty. PolicyIssued and PolicyActivated can arrive
     * in either order (a replay orders by timestamp, and both can share one), so neither may overwrite what
     * the other already said. The UNKNOWN product placeholder is the one value that IS replaced.
     */
    public void upsertPolicy(UUID tenantId, PolicyFacts f) {
        jdbc.update("""
            INSERT INTO reporting.report_policy (tenant_id, policy_number, holder_party_id, product_id, product_version_id,
                product_category, portfolio_code, group_scheme, agent_id, sales_channel, branch_code, sum_assured, premium,
                premium_frequency, currency, issue_date)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, 'TZS'), ?)
            ON CONFLICT (tenant_id, policy_number) DO UPDATE SET
                holder_party_id    = COALESCE(reporting.report_policy.holder_party_id, EXCLUDED.holder_party_id),
                product_id         = CASE WHEN reporting.report_policy.product_id = '00000000-0000-0000-0000-000000000000'
                                          THEN EXCLUDED.product_id ELSE reporting.report_policy.product_id END,
                product_version_id = COALESCE(reporting.report_policy.product_version_id, EXCLUDED.product_version_id),
                product_category   = COALESCE(reporting.report_policy.product_category, EXCLUDED.product_category),
                portfolio_code     = COALESCE(reporting.report_policy.portfolio_code, EXCLUDED.portfolio_code),
                group_scheme       = reporting.report_policy.group_scheme OR EXCLUDED.group_scheme,
                agent_id           = COALESCE(reporting.report_policy.agent_id, EXCLUDED.agent_id),
                sales_channel      = COALESCE(reporting.report_policy.sales_channel, EXCLUDED.sales_channel),
                branch_code        = COALESCE(reporting.report_policy.branch_code, EXCLUDED.branch_code),
                sum_assured        = COALESCE(reporting.report_policy.sum_assured, EXCLUDED.sum_assured),
                premium            = COALESCE(reporting.report_policy.premium, EXCLUDED.premium),
                premium_frequency  = COALESCE(reporting.report_policy.premium_frequency, EXCLUDED.premium_frequency),
                issue_date         = COALESCE(reporting.report_policy.issue_date, EXCLUDED.issue_date)
            """,
            tenantId, f.policyNumber(), f.holderPartyId(), f.productId() == null ? UNKNOWN_PRODUCT : f.productId(),
            f.productVersionId(), f.productCategory(), f.portfolioCode(), f.groupScheme(), f.agentId(), f.salesChannel(),
            f.branchCode(), f.sumAssured(), f.premium(), f.premiumFrequency(), f.currency(), f.issueDate());
    }

    public void setActivated(UUID tenantId, String policyNumber, LocalDate on) {
        jdbc.update("UPDATE reporting.report_policy SET activated_on = COALESCE(activated_on, ?)"
            + " WHERE tenant_id = ? AND policy_number = ?", on, tenantId, policyNumber);
    }

    public void setNotTakenUp(UUID tenantId, String policyNumber, LocalDate on) {
        jdbc.update("UPDATE reporting.report_policy SET not_taken_up_on = COALESCE(not_taken_up_on, ?)"
            + " WHERE tenant_id = ? AND policy_number = ?", on, tenantId, policyNumber);
    }

    public void setSumAssured(UUID tenantId, String policyNumber, BigDecimal sumAssured) {
        jdbc.update("UPDATE reporting.report_policy SET sum_assured = ? WHERE tenant_id = ? AND policy_number = ?",
            sumAssured, tenantId, policyNumber);
    }

    public boolean isGroupScheme(UUID tenantId, String policyNumber) {
        List<Boolean> rows = jdbc.queryForList("SELECT group_scheme FROM reporting.report_policy"
            + " WHERE tenant_id = ? AND policy_number = ?", Boolean.class, tenantId, policyNumber);
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
    }

    /** Creates an UNKNOWN-product row for a policy first seen through a later event. True when it had to. */
    public boolean ensurePolicy(UUID tenantId, String policyNumber) {
        return jdbc.update("INSERT INTO reporting.report_policy (tenant_id, policy_number, product_id) VALUES (?, ?, ?)"
            + " ON CONFLICT DO NOTHING", tenantId, policyNumber, UNKNOWN_PRODUCT) == 1;
    }

    public void addStatusChange(UUID tenantId, UUID eventId, String policyNumber, PolicyStatus status, String cause,
                                LocalDate effectiveOn, Instant occurredAt) {
        jdbc.update("INSERT INTO reporting.report_policy_status_change (tenant_id, event_id, policy_number, to_status, cause,"
            + " effective_on, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            tenantId, eventId, policyNumber, status.name(), cause, effectiveOn, Timestamp.from(occurredAt));
    }

    public void addCollection(UUID tenantId, UUID eventId, UUID invoiceId, String policyNumber, BigDecimal amount,
                              String currency, LocalDate collectedOn, String channel) {
        jdbc.update("INSERT INTO reporting.report_collection (tenant_id, event_id, invoice_id, policy_number, amount, currency,"
            + " collected_on, channel) VALUES (?, ?, ?, ?, ?, COALESCE(?, 'TZS'), ?, ?) ON CONFLICT DO NOTHING",
            tenantId, eventId, invoiceId, policyNumber, amount, currency, collectedOn, channel);
    }

    public void upsertClient(UUID tenantId, ClientFacts c) {
        jdbc.update("""
            INSERT INTO reporting.report_client (tenant_id, party_id, party_type, kyc_status, display_name, registered_on,
                date_of_birth, sex)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, party_id) DO UPDATE SET
                party_type = EXCLUDED.party_type,
                kyc_status = COALESCE(EXCLUDED.kyc_status, reporting.report_client.kyc_status),
                display_name = COALESCE(EXCLUDED.display_name, reporting.report_client.display_name),
                registered_on = COALESCE(reporting.report_client.registered_on, EXCLUDED.registered_on),
                date_of_birth = COALESCE(EXCLUDED.date_of_birth, reporting.report_client.date_of_birth),
                sex = COALESCE(EXCLUDED.sex, reporting.report_client.sex)
            """, tenantId, c.partyId(), c.partyType(), c.kycStatus(), c.displayName(), c.registeredOn(),
            c.dateOfBirth(), c.sex());
    }

    /** False when the client row does not exist yet. */
    public boolean setKycStatus(UUID tenantId, UUID partyId, String kycStatus) {
        return jdbc.update("UPDATE reporting.report_client SET kyc_status = ? WHERE tenant_id = ? AND party_id = ?",
            kycStatus, tenantId, partyId) == 1;
    }

    public void addLivesChange(UUID tenantId, UUID eventId, String policyNumber, String kind, int lives,
                               BigDecimal schemeTotalCovered, LocalDate effectiveOn, Instant occurredAt) {
        jdbc.update("INSERT INTO reporting.report_lives_change (tenant_id, event_id, policy_number, kind, lives_covered,"
            + " scheme_total_covered, effective_on, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            tenantId, eventId, policyNumber, kind, lives, schemeTotalCovered, effectiveOn, Timestamp.from(occurredAt));
    }

    /** A rebuild starts here: every row this tenant has, including the record of which events were applied. */
    public void deleteTenant(UUID tenantId) {
        for (String table : TABLES) {
            jdbc.update("DELETE FROM reporting." + table + " WHERE tenant_id = ?", tenantId);
        }
    }
}
```

- [ ] **Step 4: Implement `ReportingEventHandlers` (policy, billing and lives events; party events come in Task 4)**

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.domain.AnnualisedPremium;
import tz.co.nlolo.lifeplatform.reporting.domain.CivilDates;
import tz.co.nlolo.lifeplatform.reporting.domain.PolicyStatus;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ProjectionStore;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.domain.PayloadReader.amount;
import static tz.co.nlolo.lifeplatform.reporting.domain.PayloadReader.currency;
import static tz.co.nlolo.lifeplatform.reporting.domain.PayloadReader.date;
import static tz.co.nlolo.lifeplatform.reporting.domain.PayloadReader.flag;
import static tz.co.nlolo.lifeplatform.reporting.domain.PayloadReader.instant;
import static tz.co.nlolo.lifeplatform.reporting.domain.PayloadReader.integer;
import static tz.co.nlolo.lifeplatform.reporting.domain.PayloadReader.text;
import static tz.co.nlolo.lifeplatform.reporting.domain.PayloadReader.uuid;

/**
 * Turns one domain event into reporting rows. The only place event payloads are read. Called by the live
 * listener and by the rebuild, always through EventApplier, inside one transaction with TenantContext set.
 *
 * <p>Payload fields were verified against the producers (PolicyApiImpl, BillingApiImpl, PartyApiImpl), not
 * against intent. Three facts worth knowing before changing anything here:
 * <ul>
 *   <li>{@code policy.PolicySurrendered} fires for three things: a settled claim (carries {@code claimId}),
 *       a credit-life scheme whose last borrower left (a group scheme, no claimId), and a policyholder
 *       surrender being APPROVED (an individual policy, no claimId) -- cover stops at approval, so that is
 *       the status date. {@code policy.SurrenderPaid} only reports the money and changes no status.</li>
 *   <li>{@code GroupMemberAdded} has no member id; every member and covered-life event carries
 *       {@code livesCovered}, the lives AFTER the change.</li>
 *   <li>{@code PolicyNotTakenUp} is never in force, so it sets a date and adds no status change.</li>
 * </ul>
 */
@Component
public class ReportingEventHandlers {

    public static final UUID UNKNOWN_PRODUCT = new UUID(0L, 0L);
    static final String UNATTRIBUTED = "lifeplatform_reporting_unattributed_total";
    static final String UNKNOWN_FREQUENCY = "lifeplatform_reporting_unknown_premium_frequency_total";

    public static final Set<String> HANDLED = Set.of(
        "policy.PolicyIssued", "policy.PolicyActivated", "policy.PolicyNotTakenUp", "policy.PolicySuspended",
        "policy.PolicyResumed", "policy.PolicyLapsed", "policy.PolicyReinstated", "policy.PolicyMadePaidUp",
        "policy.PolicyMatured", "policy.PolicyExpired", "policy.PolicySurrendered", "policy.PolicyCancelledFreeLook",
        "policy.AnnuityEnded", "policy.GroupMemberAdded", "policy.GroupMemberExited", "policy.CoveredLifeAdded",
        "policy.CoveredLifeEnded", "billing.PremiumCollected");

    private final ProjectionStore store;
    private final MeterRegistry meters;

    public ReportingEventHandlers(ProjectionStore store, MeterRegistry meters) {
        this.store = store;
        this.meters = meters;
    }

    public boolean handles(String eventType) {
        return HANDLED.contains(eventType);
    }

    /** False for an unhandled type or an event already applied. */
    public boolean apply(UUID tenantId, UUID eventId, String eventType, Instant occurredAt, Map<String, Object> p) {
        if (!handles(eventType) || !store.markApplied(tenantId, eventId)) {
            return false;
        }
        String policyNumber = text(p, "policyNumber");
        switch (eventType) {
            case "policy.PolicyIssued" -> store.upsertPolicy(tenantId, facts(p, eventType));
            case "policy.PolicyActivated" -> {
                store.upsertPolicy(tenantId, facts(p, eventType));
                LocalDate on = onDate(p, "activatedAt", occurredAt);
                store.setActivated(tenantId, policyNumber, on);
                store.addStatusChange(tenantId, eventId, policyNumber, PolicyStatus.ACTIVE, eventType, on, occurredAt);
                Integer lives = integer(p, "livesCovered");
                if (lives != null) {
                    store.addLivesChange(tenantId, UUID.nameUUIDFromBytes((eventId + ":lives").getBytes()),
                        policyNumber, "ACTIVATED", lives, null, on, occurredAt);
                }
            }
            case "policy.PolicyNotTakenUp" -> {
                store.ensurePolicy(tenantId, policyNumber);
                store.setNotTakenUp(tenantId, policyNumber, onDate(p, "expiredAt", occurredAt));
            }
            case "policy.PolicySuspended" -> status(tenantId, eventId, eventType, occurredAt, p, PolicyStatus.SUSPENDED, "suspendedAt");
            case "policy.PolicyResumed" -> status(tenantId, eventId, eventType, occurredAt, p, PolicyStatus.ACTIVE, "resumedAt");
            case "policy.PolicyLapsed" -> status(tenantId, eventId, eventType, occurredAt, p, PolicyStatus.LAPSED, "lapsedAt");
            case "policy.PolicyReinstated" -> status(tenantId, eventId, eventType, occurredAt, p, PolicyStatus.ACTIVE, "reinstatedAt");
            case "policy.PolicyMadePaidUp" -> {
                status(tenantId, eventId, eventType, occurredAt, p, PolicyStatus.PAID_UP, "madePaidUpAt");
                if (amount(p, "paidUpSumAssured") != null) {
                    store.setSumAssured(tenantId, policyNumber, amount(p, "paidUpSumAssured"));
                }
            }
            case "policy.PolicyMatured" -> status(tenantId, eventId, eventType, occurredAt, p, PolicyStatus.MATURED, "maturedAt");
            case "policy.PolicyExpired" -> status(tenantId, eventId, eventType, occurredAt, p, PolicyStatus.EXPIRED, "expiredAt");
            case "policy.PolicySurrendered" -> {
                PolicyStatus outcome = p.get("claimId") != null ? PolicyStatus.CLAIM_TERMINATED
                    : store.isGroupScheme(tenantId, policyNumber) ? PolicyStatus.ENDED : PolicyStatus.SURRENDERED;
                status(tenantId, eventId, eventType, occurredAt, p, outcome, "surrenderedAt");
            }
            case "policy.PolicyCancelledFreeLook" ->
                status(tenantId, eventId, eventType, occurredAt, p, PolicyStatus.CANCELLED_FREE_LOOK, "cancelledAt");
            case "policy.AnnuityEnded" -> status(tenantId, eventId, eventType, occurredAt, p, PolicyStatus.ENDED, "endedAt");
            case "policy.GroupMemberAdded" -> lives(tenantId, eventId, occurredAt, p, "JOINED", "joinedOn");
            case "policy.CoveredLifeAdded" -> lives(tenantId, eventId, occurredAt, p, "JOINED", "coverStart");
            case "policy.GroupMemberExited" -> lives(tenantId, eventId, occurredAt, p, "EXITED", "leftOn");
            case "policy.CoveredLifeEnded" -> lives(tenantId, eventId, occurredAt, p, "EXITED", "endedOn");
            case "billing.PremiumCollected" -> {
                String payer = text(p, "payerRef");
                store.addCollection(tenantId, eventId, uuid(p, "invoiceId"), policyNumber, amount(p, "amount"),
                    currency(p, "amount"), onInstant(p, "collectedAt", occurredAt),
                    payer == null || payer.isBlank() ? "FIELD_CASH" : "MOBILE_MONEY");
            }
            default -> throw new IllegalStateException("HANDLED lists " + eventType + " but no case handles it");
        }
        return true;
    }

    private ProjectionStore.PolicyFacts facts(Map<String, Object> p, String eventType) {
        String frequency = text(p, "premiumFrequency");
        if (frequency != null && !AnnualisedPremium.isRecognised(frequency)) {
            meters.counter(UNKNOWN_FREQUENCY, "eventType", eventType).increment();
        }
        return new ProjectionStore.PolicyFacts(text(p, "policyNumber"), uuid(p, "policyholderPartyId"),
            uuid(p, "productId"), uuid(p, "productVersionId"), text(p, "productCategory"), text(p, "portfolioCode"),
            flag(p, "groupScheme"), uuid(p, "agentOfRecordId"), text(p, "salesChannel"), text(p, "branchCode"),
            amount(p, "sumAssured"), amount(p, "premium"), frequency,
            currency(p, "sumAssured") != null ? currency(p, "sumAssured") : currency(p, "premium"), date(p, "issueDate"));
    }

    private void status(UUID tenantId, UUID eventId, String eventType, Instant occurredAt, Map<String, Object> p,
                        PolicyStatus status, String timestampKey) {
        String policyNumber = text(p, "policyNumber");
        if (store.ensurePolicy(tenantId, policyNumber)) {
            meters.counter(UNATTRIBUTED, "eventType", eventType).increment();
        }
        store.addStatusChange(tenantId, eventId, policyNumber, status, eventType,
            onInstant(p, timestampKey, occurredAt), occurredAt);
    }

    private void lives(UUID tenantId, UUID eventId, Instant occurredAt, Map<String, Object> p, String kind, String dateKey) {
        Integer lives = integer(p, "livesCovered");
        if (lives == null) return;
        store.addLivesChange(tenantId, eventId, text(p, "policyNumber"), kind, lives, amount(p, "schemeTotalCovered"),
            onDate(p, dateKey, occurredAt), occurredAt);
    }

    /** A date-only field (activatedAt, joinedOn), else the Tanzanian day the event happened. */
    private static LocalDate onDate(Map<String, Object> p, String key, Instant occurredAt) {
        LocalDate d = date(p, key);
        return d != null ? d : CivilDates.of(occurredAt);
    }

    /** A timestamp field (lapsedAt, collectedAt) as a Tanzanian day, else the day the event happened. */
    private static LocalDate onInstant(Map<String, Object> p, String key, Instant occurredAt) {
        Instant at = instant(p, key);
        return CivilDates.of(at != null ? at : occurredAt);
    }
}
```

Note: the activation's lives row needs its own primary key, so it uses a name-based UUID derived from the event id. That is deterministic, so a replay produces the same id.

- [ ] **Step 5: Run to verify it passes**

Run: `./mvnw -B test -Dtest=ProjectionIntegrationTest,ReportingSqlStaysInSchemaTest`
Expected: PASS, Tests run: 11.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/reporting backend/src/test/java/tz/co/nlolo/lifeplatform/reporting
git commit -m "feat(reporting): project policy, collection and lives events into the read model"
```

---

### Task 4: Party handlers, EventApplier and the live listener

**Files:**
- Modify: `reporting/application/ReportingEventHandlers.java` (party events, `PartyApi`)
- Create: `reporting/application/EventApplier.java`, `reporting/application/ReportingEventListener.java`
- Test: `reporting/LiveProjectionIntegrationTest.java`

**Interfaces:**
- Consumes: `PartyApi.getPartyDetail(UUID) -> PartyDetailView` (`partyType()`, `kycStatus()`, `displayName()`, `createdAt()`, `dateOfBirth()`, `sex()`), `PartyNotFoundException`.
- Produces: `EventApplier.apply(UUID tenantId, UUID eventId, String eventType, Instant occurredAt, Map<String,Object> payload) -> EventApplier.Outcome` with `enum Outcome { APPLIED, DUPLICATE, IGNORED, FAILED }`; metric `lifeplatform_reporting_event_processing_failed_total{eventType}`; bean `reportingDomainEventListener`.

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.reporting;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.bonus.BonusTestFixtures;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.product.api.BonusPlan;
import tz.co.nlolo.lifeplatform.product.api.CashValuePlan;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The real thing, end to end: issuing and activating a policy through PolicyApi, and registering a client
 * through PartyApi, reach the reporting tables with no test code in between. */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(BonusTestFixtures.class)
class LiveProjectionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            ReportingTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();
    @Autowired private BonusTestFixtures fixtures;
    @Autowired private PartyApi partyApi;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void anIssuedAndActivatedPolicyAndItsHolderReachTheReadModel() {
        var issued = fixtures.issue(TENANT, BonusPlan.none(), CashValuePlan.none());

        assertThat(jdbc.queryForObject("SELECT product_id FROM reporting.report_policy WHERE tenant_id = ? AND policy_number = ?",
            UUID.class, TENANT, issued.policyNumber())).isEqualTo(issued.productId());
        assertThat(jdbc.queryForList("SELECT to_status FROM reporting.report_policy_status_change WHERE tenant_id = ?"
            + " AND policy_number = ?", String.class, TENANT, issued.policyNumber())).containsExactly("ACTIVE");
        UUID holder = jdbc.queryForObject("SELECT holder_party_id FROM reporting.report_policy WHERE tenant_id = ?"
            + " AND policy_number = ?", UUID.class, TENANT, issued.policyNumber());
        assertThat(jdbc.queryForObject("SELECT party_type FROM reporting.report_client WHERE tenant_id = ? AND party_id = ?",
            String.class, TENANT, holder)).isEqualTo("INDIVIDUAL");
        assertThat(jdbc.queryForObject("SELECT date_of_birth FROM reporting.report_client WHERE tenant_id = ? AND party_id = ?",
            LocalDate.class, TENANT, holder)).isEqualTo(LocalDate.of(1985, 1, 1));
    }

    @Test
    void aKycDecisionUpdatesTheClient() {
        TenantContext.set(TENANT);
        UUID corporate;
        try {
            corporate = partyApi.registerCorporate("Mkombozi Traders Ltd", "REG-" + UUID.randomUUID().toString().substring(0, 8),
                "+255717000999", null, "test-staff").partyId();
        } finally {
            TenantContext.clear();
        }
        assertThat(jdbc.queryForObject("SELECT party_type || ':' || kyc_status FROM reporting.report_client"
            + " WHERE tenant_id = ? AND party_id = ?", String.class, TENANT, corporate)).isEqualTo("CORPORATE:PENDING");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -B test -Dtest=LiveProjectionIntegrationTest`
Expected: FAIL -- `EmptyResultDataAccessException` (no listener yet writes rows).

- [ ] **Step 3: Add party events to `ReportingEventHandlers`**

Add `"party.PartyRegistered", "party.PartyKycStatusChanged", "party.PartyDetailsAmended"` to `HANDLED`. Add a `PartyApi partyApi` constructor parameter (import `tz.co.nlolo.lifeplatform.party.api.PartyApi`, `PartyDetailView`, `PartyNotFoundException`). Add three cases before `default`:

```java
            case "party.PartyRegistered", "party.PartyDetailsAmended" ->
                refreshClient(tenantId, uuid(p, "partyId"), text(p, "partyType"), occurredAt);
            case "party.PartyKycStatusChanged" -> {
                UUID partyId = uuid(p, "partyId");
                if (!store.setKycStatus(tenantId, partyId, text(p, "newStatus"))) {
                    refreshClient(tenantId, partyId, null, occurredAt);
                    store.setKycStatus(tenantId, partyId, text(p, "newStatus"));
                }
            }
```

And the helper:

```java
    /**
     * Party events carry no details (PartyRegistered: id and type; PartyDetailsAmended: id, type, who), so the
     * client is read through PartyApi. On a replay that read is today's record, not the record at the time --
     * acceptable for date of birth and sex, which do not change, and KYC is then restated by the replayed
     * PartyKycStatusChanged events that follow.
     */
    private void refreshClient(UUID tenantId, UUID partyId, String partyTypeFromEvent, Instant occurredAt) {
        try {
            PartyDetailView d = partyApi.getPartyDetail(partyId);
            store.upsertClient(tenantId, new ProjectionStore.ClientFacts(partyId, d.partyType().name(),
                d.kycStatus().name(), d.displayName(), CivilDates.of(d.createdAt() != null ? d.createdAt() : occurredAt),
                d.dateOfBirth(), d.sex() == null ? null : d.sex().name()));
        } catch (PartyNotFoundException e) {
            if (partyTypeFromEvent != null) {
                store.upsertClient(tenantId, new ProjectionStore.ClientFacts(partyId, partyTypeFromEvent, null, null,
                    CivilDates.of(occurredAt), null, null));
            }
        }
    }
```

Update `ProjectionIntegrationTest` only if it constructs the handler directly (it does not -- it autowires).

- [ ] **Step 4: Implement `EventApplier`**

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Applies one event in its own transaction, as its own tenant. Shared by the live listener and the rebuild
 * so both take exactly one path.
 *
 * <p>REQUIRES_NEW, through a TransactionTemplate: from an AFTER_COMMIT callback a plain @Transactional
 * silently joins the producer's already-committed transaction and never commits (regreporting's
 * PolicyEventListener records finding this the hard way). The tenant is set BEFORE the transaction opens,
 * because TenantAwareDataSource sets the RLS variable when the connection is borrowed.
 *
 * <p>A failure is counted and logged, never thrown: a reporting bug must not fail the business transaction
 * that published the event, and the rebuild is the documented way to repair a missed event.
 */
@Component
public class EventApplier {

    public enum Outcome { APPLIED, DUPLICATE, IGNORED, FAILED }

    static final String FAILED_COUNTER = "lifeplatform_reporting_event_processing_failed_total";
    private static final Logger log = LoggerFactory.getLogger(EventApplier.class);

    private final ReportingEventHandlers handlers;
    private final MeterRegistry meters;
    private final TransactionTemplate requiresNew;

    public EventApplier(ReportingEventHandlers handlers, MeterRegistry meters, PlatformTransactionManager transactionManager) {
        this.handlers = handlers;
        this.meters = meters;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Outcome apply(UUID tenantId, UUID eventId, String eventType, Instant occurredAt, Map<String, Object> payload) {
        if (!handlers.handles(eventType)) {
            return Outcome.IGNORED;
        }
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            Boolean applied = requiresNew.execute(status -> handlers.apply(tenantId, eventId, eventType, occurredAt, payload));
            return Boolean.TRUE.equals(applied) ? Outcome.APPLIED : Outcome.DUPLICATE;
        } catch (RuntimeException e) {
            meters.counter(FAILED_COUNTER, "eventType", eventType).increment();
            log.error("reporting failed to apply {} {} for tenant {}", eventType, eventId, tenantId, e);
            return Outcome.FAILED;
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
```

- [ ] **Step 5: Implement the live listener**

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.util.Map;

/**
 * Every domain event, after its producer commits. The bean name is explicit: billing, distribution,
 * reinsurance and regreporting each declare an unqualified PolicyEventListener, and a duplicate simple
 * name fails context startup for the whole suite.
 */
@Component("reportingDomainEventListener")
public class ReportingEventListener {

    private final EventApplier applier;

    public ReportingEventListener(EventApplier applier) {
        this.applier = applier;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!(envelope.payload() instanceof Map<?, ?> raw)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) raw;
        applier.apply(envelope.tenantId(), envelope.eventId(), envelope.eventType(), envelope.occurredAt(), payload);
    }
}
```

- [ ] **Step 6: Run to verify it passes, with the earlier classes**

Run: `./mvnw -B test -Dtest=LiveProjectionIntegrationTest,ProjectionIntegrationTest,ModularityTests`
Expected: PASS, Tests run: 13. `ModularityTests` proves the `party::api` use is allowed.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/reporting backend/src/test/java/tz/co/nlolo/lifeplatform/reporting
git commit -m "feat(reporting): live listener and client projection"
```

---

### Task 5: Rebuild from the audit journal

**Files:**
- Create: `reporting/api/RebuildStatusView.java`
- Create: `reporting/application/RebuildService.java`
- Test: `reporting/RebuildIntegrationTest.java`

**Interfaces:**
- Consumes: `AuditApi.listEvents(String prefix, DateRange range, Pageable) -> Page<AuditEntryView>`; `AuditEntryView(UUID eventId, String eventType, Instant occurredAt, String payloadJson)`; `DateRange(Instant from, Instant to)`; `EventApplier.apply(...)`; `ProjectionStore.deleteTenant(UUID)`.
- Produces: `record RebuildStatusView(String state, long eventsReplayed, long eventsFailed, Instant startedAt, Instant finishedAt)` with `static RebuildStatusView idle()`; states `IDLE`, `RUNNING`, `DONE`, `DONE_WITH_FAILURES`, `FAILED`. `RebuildService.start(UUID tenantId, String requestedBy) -> RebuildStatusView`, `status(UUID) -> RebuildStatusView`, package-private synchronous `rebuild(UUID tenantId, Instant startedAt) -> RebuildStatusView`.

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.bonus.BonusTestFixtures;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.product.api.BonusPlan;
import tz.co.nlolo.lifeplatform.product.api.CashValuePlan;
import tz.co.nlolo.lifeplatform.reporting.ReportingTestMigrations;
import tz.co.nlolo.lifeplatform.reporting.api.RebuildStatusView;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rebuild's promise: what it derives from the journal is exactly what live projection derived. If the
 * two ever differ, a rebuild "repairs" a tenant into a different answer. Lives in the application package
 * so it can call the synchronous rebuild(...) directly.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(BonusTestFixtures.class)
class RebuildIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            ReportingTestMigrations.ALL);
    }

    @Autowired private BonusTestFixtures fixtures;
    @Autowired private PartyApi partyApi;
    @Autowired private RebuildService rebuild;
    @Autowired private JdbcTemplate jdbc;

    private static final List<String> SNAPSHOTS = List.of(
        "SELECT policy_number, holder_party_id, product_id, product_category, portfolio_code, group_scheme, agent_id,"
            + " sales_channel, branch_code, sum_assured, premium, premium_frequency, currency, issue_date, activated_on,"
            + " not_taken_up_on FROM reporting.report_policy WHERE tenant_id = ? ORDER BY policy_number",
        "SELECT event_id, policy_number, to_status, cause, effective_on, occurred_at"
            + " FROM reporting.report_policy_status_change WHERE tenant_id = ? ORDER BY event_id",
        "SELECT event_id, policy_number, kind, lives_covered, scheme_total_covered, effective_on"
            + " FROM reporting.report_lives_change WHERE tenant_id = ? ORDER BY event_id",
        "SELECT party_id, party_type, kyc_status, display_name, registered_on, date_of_birth, sex"
            + " FROM reporting.report_client WHERE tenant_id = ? ORDER BY party_id",
        "SELECT event_id FROM reporting.applied_event WHERE tenant_id = ? ORDER BY event_id");

    private List<List<Map<String, Object>>> snapshot(UUID tenant) {
        return SNAPSHOTS.stream().map(sql -> jdbc.queryForList(sql, tenant)).toList();
    }

    @Test
    void aRebuildReproducesExactlyWhatLiveProjectionWrote() {
        UUID tenant = UUID.randomUUID();
        var first = fixtures.issue(tenant, BonusPlan.none(), CashValuePlan.none());
        fixtures.issue(tenant, BonusPlan.none(), CashValuePlan.none());
        fixtures.publish(tenant, "policy.PolicyLapsed",
            Map.of("policyNumber", first.policyNumber(), "lapsedAt", Instant.now().toString()));
        TenantContext.set(tenant);
        try {
            partyApi.registerCorporate("Upendo Cooperative", "REG-" + UUID.randomUUID().toString().substring(0, 8),
                "+255717000123", null, "test-staff");
        } finally {
            TenantContext.clear();
        }
        var live = snapshot(tenant);
        assertThat(live.get(0)).hasSize(2);

        RebuildStatusView result = rebuild.rebuild(tenant, Instant.now());

        assertThat(result.state()).isEqualTo("DONE");
        assertThat(result.eventsReplayed()).isPositive();
        assertThat(snapshot(tenant)).isEqualTo(live);
    }

    @Test
    void aRebuildRestoresATenantWhoseRowsWereLost() {
        UUID tenant = UUID.randomUUID();
        fixtures.issue(tenant, BonusPlan.none(), CashValuePlan.none());
        var live = snapshot(tenant);
        for (String table : List.of("report_policy", "report_policy_status_change", "report_lives_change",
                "report_client", "applied_event")) {
            jdbc.update("DELETE FROM reporting." + table + " WHERE tenant_id = ?", tenant);
        }
        rebuild.rebuild(tenant, Instant.now());
        assertThat(snapshot(tenant)).isEqualTo(live);
    }

    @Test
    void startRunsInTheBackgroundAndReportsWhenDone() throws InterruptedException {
        UUID tenant = UUID.randomUUID();
        fixtures.issue(tenant, BonusPlan.none(), CashValuePlan.none());
        assertThat(rebuild.status(tenant).state()).isEqualTo("IDLE");
        assertThat(rebuild.start(tenant, "admin").state()).isEqualTo("RUNNING");
        RebuildStatusView status = rebuild.status(tenant);
        for (int i = 0; i < 60 && "RUNNING".equals(status.state()); i++) {
            Thread.sleep(500);
            status = rebuild.status(tenant);
        }
        assertThat(status.state()).isEqualTo("DONE");
        assertThat(status.finishedAt()).isNotNull();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -B test -Dtest=RebuildIntegrationTest`
Expected: compilation FAILURE (`RebuildService` missing).

- [ ] **Step 3: Implement**

`RebuildStatusView.java` (api):

```java
package tz.co.nlolo.lifeplatform.reporting.api;

import java.time.Instant;

/** state: IDLE, RUNNING, DONE, DONE_WITH_FAILURES (some events failed to apply; see the failure metric and
 * log), FAILED (the run itself stopped). Held in memory per tenant: a restart forgets it, the rows do not. */
public record RebuildStatusView(String state, long eventsReplayed, long eventsFailed, Instant startedAt, Instant finishedAt) {

    public static RebuildStatusView idle() {
        return new RebuildStatusView("IDLE", 0, 0, null, null);
    }
}
```

`RebuildService.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.api.AuditApi;
import tz.co.nlolo.lifeplatform.audit.api.AuditEntryView;
import tz.co.nlolo.lifeplatform.audit.api.DateRange;
import tz.co.nlolo.lifeplatform.reporting.api.RebuildStatusView;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ProjectionStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Re-derives one tenant's reporting rows from the audit journal (AuditApi, the domain-event journal every
 * event is written to). Clears the tenant, then replays party, policy and billing events oldest first through
 * the same EventApplier the live listener uses.
 *
 * <p>Three passes, one per module prefix, because AuditApi filters by one prefix. Order matters only within
 * a module's own events (a lapse after its activation), and each pass keeps that order. Live events keep
 * arriving during a rebuild; applied_event makes any overlap harmless.
 *
 * <p>What it cannot recover: an event the audit listener itself failed to store (audit.failed_event). The
 * journal starts at the oldest audit partition, August 2026.
 */
@Service
public class RebuildService {

    static final List<String> PREFIXES = List.of("party.", "policy.", "billing.");
    private static final int PAGE_SIZE = 500;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final Logger log = LoggerFactory.getLogger(RebuildService.class);

    private final AuditApi auditApi;
    private final ProjectionStore store;
    private final EventApplier applier;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Map<UUID, RebuildStatusView> statuses = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "reporting-rebuild");
        t.setDaemon(true);
        return t;
    });

    public RebuildService(AuditApi auditApi, ProjectionStore store, EventApplier applier, ObjectMapper mapper,
                          PlatformTransactionManager transactionManager) {
        this.auditApi = auditApi;
        this.store = store;
        this.applier = applier;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Starts a rebuild unless one is already running for this tenant, in which case that one is returned. */
    public RebuildStatusView start(UUID tenantId, String requestedBy) {
        RebuildStatusView running = new RebuildStatusView("RUNNING", 0, 0, Instant.now(), null);
        RebuildStatusView current = statuses.compute(tenantId,
            (key, prior) -> prior != null && "RUNNING".equals(prior.state()) ? prior : running);
        if (current != running) {
            return current;
        }
        log.info("reporting rebuild for tenant {} requested by {}", tenantId, requestedBy);
        executor.submit(() -> statuses.put(tenantId, rebuild(tenantId, running.startedAt())));
        return running;
    }

    public RebuildStatusView status(UUID tenantId) {
        return statuses.getOrDefault(tenantId, RebuildStatusView.idle());
    }

    /** Synchronous; events after {@code startedAt} are left to the live listener. */
    RebuildStatusView rebuild(UUID tenantId, Instant startedAt) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        long replayed = 0;
        long failed = 0;
        try {
            tx.executeWithoutResult(status -> store.deleteTenant(tenantId));
            DateRange upTo = new DateRange(null, startedAt);
            for (String prefix : PREFIXES) {
                int page = 0;
                Page<AuditEntryView> entries;
                do {
                    entries = auditApi.listEvents(prefix, upTo,
                        PageRequest.of(page++, PAGE_SIZE, Sort.by("occurredAt", "auditLogId")));
                    for (AuditEntryView e : entries.getContent()) {
                        Map<String, Object> payload = mapper.readValue(e.payloadJson(), MAP);
                        switch (applier.apply(tenantId, e.eventId(), e.eventType(), e.occurredAt(), payload)) {
                            case APPLIED, DUPLICATE -> replayed++;
                            case FAILED -> failed++;
                            case IGNORED -> { }
                        }
                    }
                } while (entries.hasNext());
            }
            return new RebuildStatusView(failed == 0 ? "DONE" : "DONE_WITH_FAILURES", replayed, failed, startedAt, Instant.now());
        } catch (Exception e) {
            log.error("reporting rebuild for tenant {} stopped", tenantId, e);
            return new RebuildStatusView("FAILED", replayed, failed, startedAt, Instant.now());
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -B test -Dtest=RebuildIntegrationTest,ModularityTests`
Expected: PASS, Tests run: 4. If `aRebuildReproducesExactlyWhatLiveProjectionWrote` fails on `occurred_at`, the live and journal timestamps differ in precision: compare the two values printed by AssertJ before changing anything -- do not loosen the assertion.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/reporting backend/src/test/java/tz/co/nlolo/lifeplatform/reporting
git commit -m "feat(reporting): rebuild a tenant by replaying the audit journal"
```

---

### Task 6: Report plumbing, policies in force, new business

**Files:**
- Create in `reporting/api/`: `ReportingApi.java`, `ReportCategory.java`, `DateFilterKind.java`, `ColumnType.java`, `GroupBy.java`, `ReportDefinitionView.java`, `ReportFilter.java`, `ReportColumn.java`, `ReportSection.java`, `ReportResult.java`, `ReportNotFoundException.java`, `ReportParameterException.java`
- Create in `reporting/infrastructure/`: `ReportSql.java`, `ReportQueries.java`
- Create in `reporting/application/`: `ReportingConfig.java`, `Report.java`, `Sections.java`, `ReportFilters.java`, `Labels.java`, `LabelsFactory.java`, `FilterSummary.java`, `PoliciesInForceReport.java`, `NewBusinessReport.java`, `ReportingApiImpl.java`
- Test: `reporting/ReportingTestEvents.java`, `reporting/ReportQueriesIntegrationTest.java`

**Interfaces:**
- Consumes: `EventApplier`, `ProjectionStore.upsertClient`, `RebuildService`, `ProductApi.getProduct(UUID).productName()`, `DistributionApi.getAgent(UUID)` (`partyId()`, `licenseNumber()`), `PartyApi.getParty(UUID).displayName()`.
- Produces:
  - `interface ReportingApi { List<ReportDefinitionView> catalogue(); ReportResult run(String key, ReportFilter filter); RebuildStatusView startRebuild(String requestedBy); RebuildStatusView rebuildStatus(); }`
  - `enum GroupBy { PRODUCT, PRODUCT_CATEGORY, AGENT, CHANNEL, BRANCH; String label(); }`
  - `record ReportDefinitionView(String key, String title, String description, ReportCategory category, DateFilterKind dateFilterKind, List<GroupBy> groupByOptions, boolean comparable)`
  - `record ReportFilter(LocalDate asOf, LocalDate compareAsOf, LocalDate from, LocalDate to, YearMonth cohortFrom, YearMonth cohortTo, UUID productId, String productCategory, UUID agentId, String salesChannel, String branchCode, String clientType, GroupBy groupBy)` with `static none()`, `asOf(LocalDate)`, `period(LocalDate, LocalDate)`, `cohort(YearMonth, YearMonth)`, and withers `withAsOf`, `withPeriod`, `withCompareAsOf`, `withGroupBy`, `withClientType`, `withProductId`, `groupByOr(GroupBy)`
  - `record ReportColumn(String key, String label, ColumnType type)`, `record ReportSection(String title, List<ReportColumn> columns, List<Map<String,String>> rows, Map<String,String> totals)` (totals never null; empty when none), `record ReportResult(String key, String title, String filterSummary, Instant generatedAt, List<ReportSection> sections)`
  - `interface Report { ReportDefinitionView definition(); List<ReportSection> run(UUID tenantId, ReportFilter filter, Labels labels); }`
  - `ReportQueries.inForce(UUID, LocalDate, GroupBy, ReportFilter) -> List<InForceRow>`, `activations(UUID, LocalDate, LocalDate, GroupBy, ReportFilter) -> List<NewBusinessRow>`, `notTakenUp(UUID, LocalDate, LocalDate, GroupBy, ReportFilter) -> List<MonthCount>`
  - `Labels.product(UUID)`, `Labels.agent(UUID)`, `Labels.dimension(GroupBy, String raw)`
  - `Sections.col(String key, String label, ColumnType)`, `Sections.totals(List<ReportColumn>, List<Map<String,String>>) -> Map<String,String>`

- [ ] **Step 1: Write the test helper**

`ReportingTestEvents.java`:

```java
package tz.co.nlolo.lifeplatform.reporting;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reporting.application.EventApplier;
import tz.co.nlolo.lifeplatform.reporting.domain.CivilDates;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ProjectionStore;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Seeds the read model with exactly the events a report test needs, through the real EventApplier -- the
 * payload shapes are the producers' (see ReportingEventHandlers' javadoc), dated where the test says. */
@TestComponent
public class ReportingTestEvents {

    public record Sale(String policyNumber, UUID productId, String category, String frequency, String premium,
                       String sumAssured, LocalDate activatedOn, UUID agentId, String channel, String branch,
                       UUID holderPartyId, String portfolio, boolean groupScheme, int lives) {
        public static Sale of(String policyNumber, UUID productId, LocalDate activatedOn) {
            return new Sale(policyNumber, productId, "TERM_LIFE", "MONTHLY", "10000.00", "1000000.00", activatedOn,
                null, "AGENCY", "DSM-01", null, "TRM", false, 1);
        }
        public Sale premium(String frequency, String amount) {
            return new Sale(policyNumber, productId, category, frequency, amount, sumAssured, activatedOn, agentId,
                channel, branch, holderPartyId, portfolio, groupScheme, lives);
        }
        public Sale agent(UUID agent) {
            return new Sale(policyNumber, productId, category, frequency, premium, sumAssured, activatedOn, agent,
                channel, branch, holderPartyId, portfolio, groupScheme, lives);
        }
        public Sale holder(UUID holder) {
            return new Sale(policyNumber, productId, category, frequency, premium, sumAssured, activatedOn, agentId,
                channel, branch, holder, portfolio, groupScheme, lives);
        }
        public Sale portfolio(String code) {
            return new Sale(policyNumber, productId, category, frequency, premium, sumAssured, activatedOn, agentId,
                channel, branch, holderPartyId, code, groupScheme, lives);
        }
        public Sale scheme(int livesAtActivation) {
            return new Sale(policyNumber, productId, "GROUP_LIFE", frequency, premium, sumAssured, activatedOn, agentId,
                channel, branch, holderPartyId, portfolio, true, livesAtActivation);
        }
    }

    private final EventApplier applier;
    private final ProjectionStore store;
    private final TransactionTemplate tx;

    public ReportingTestEvents(EventApplier applier, ProjectionStore store, PlatformTransactionManager transactionManager) {
        this.applier = applier;
        this.store = store;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public static Instant at(LocalDate day) {
        return day.atTime(9, 0).atZone(CivilDates.ZONE).toInstant();
    }

    private void apply(UUID tenant, String type, LocalDate day, Map<String, Object> payload) {
        EventApplier.Outcome outcome = applier.apply(tenant, UUID.randomUUID(), type, at(day), payload);
        if (outcome != EventApplier.Outcome.APPLIED) {
            throw new IllegalStateException(type + " was not applied: " + outcome);
        }
    }

    private static Map<String, Object> policyPayload(Sale s) {
        Map<String, Object> p = new HashMap<>();
        p.put("policyNumber", s.policyNumber());
        p.put("policyholderPartyId", s.holderPartyId());
        p.put("productId", s.productId());
        p.put("productVersionId", UUID.randomUUID());
        p.put("sumAssured", Map.of("amount", s.sumAssured(), "currencyCode", "TZS"));
        p.put("premium", Map.of("amount", s.premium(), "currencyCode", "TZS"));
        p.put("premiumFrequency", s.frequency());
        p.put("issueDate", s.activatedOn().toString());
        p.put("agentOfRecordId", s.agentId());
        p.put("portfolioCode", s.portfolio());
        return p;
    }

    public void sell(UUID tenant, Sale s) {
        Map<String, Object> issued = policyPayload(s);
        issued.put("status", "PROPOSED");
        issued.put("salesChannel", s.channel());
        issued.put("branchCode", s.branch());
        apply(tenant, "policy.PolicyIssued", s.activatedOn(), issued);
        Map<String, Object> activated = policyPayload(s);
        activated.put("activatedAt", s.activatedOn().toString());
        activated.put("productCategory", s.category());
        activated.put("groupScheme", s.groupScheme());
        activated.put("livesCovered", s.lives());
        apply(tenant, "policy.PolicyActivated", s.activatedOn(), activated);
    }

    public void offerNotTakenUp(UUID tenant, Sale s, LocalDate expiredOn) {
        Map<String, Object> issued = policyPayload(s);
        issued.put("status", "PROPOSED");
        apply(tenant, "policy.PolicyIssued", s.activatedOn(), issued);
        apply(tenant, "policy.PolicyNotTakenUp", expiredOn, Map.of("policyNumber", s.policyNumber(),
            "productId", s.productId(), "expiredAt", expiredOn.toString()));
    }

    public void lapse(UUID tenant, String policyNumber, LocalDate on) {
        apply(tenant, "policy.PolicyLapsed", on, Map.of("policyNumber", policyNumber, "lapsedAt", at(on).toString()));
    }

    public void reinstate(UUID tenant, String policyNumber, LocalDate on) {
        apply(tenant, "policy.PolicyReinstated", on, Map.of("policyNumber", policyNumber, "reinstatedAt", at(on).toString()));
    }

    public void suspend(UUID tenant, String policyNumber, LocalDate on) {
        apply(tenant, "policy.PolicySuspended", on, Map.of("policyNumber", policyNumber, "suspendedAt", at(on).toString(),
            "reason", "test"));
    }

    public void paidUp(UUID tenant, String policyNumber, LocalDate on) {
        apply(tenant, "policy.PolicyMadePaidUp", on, Map.of("policyNumber", policyNumber, "madePaidUpAt", at(on).toString(),
            "paidUpSumAssured", Map.of("amount", "500000.00", "currencyCode", "TZS"),
            "originalSumAssured", Map.of("amount", "1000000.00", "currencyCode", "TZS")));
    }

    public void cancelFreeLook(UUID tenant, String policyNumber, LocalDate on) {
        apply(tenant, "policy.PolicyCancelledFreeLook", on, Map.of("policyNumber", policyNumber,
            "cancelledAt", at(on).toString(), "cancelledBy", "approver"));
    }

    public void collect(UUID tenant, String policyNumber, String amount, LocalDate on, boolean mobileMoney) {
        Map<String, Object> p = new HashMap<>();
        p.put("invoiceId", UUID.randomUUID());
        p.put("policyNumber", policyNumber);
        p.put("amount", Map.of("amount", amount, "currencyCode", "TZS"));
        p.put("collectedAt", at(on).toString());
        if (mobileMoney) p.put("payerRef", "255712000111");
        apply(tenant, "billing.PremiumCollected", on, p);
    }

    public void members(UUID tenant, String policyNumber, String kind, int livesAfter, String totalAfter, LocalDate on) {
        boolean joined = "JOINED".equals(kind);
        Map<String, Object> p = new HashMap<>();
        p.put("policyNumber", policyNumber);
        p.put(joined ? "joinedOn" : "leftOn", on.toString());
        p.put("livesCovered", livesAfter);
        p.put("schemeTotalCovered", Map.of("amount", totalAfter, "currencyCode", "TZS"));
        if (!joined) {
            p.put("policyMemberId", UUID.randomUUID());
            p.put("reason", "REPAID");
        }
        apply(tenant, joined ? "policy.GroupMemberAdded" : "policy.GroupMemberExited", on, p);
    }

    /** Clients are written straight to the store: the party module is not what a report test is about. */
    public void client(UUID tenant, UUID partyId, String type, String kyc, String name, LocalDate registeredOn,
                       LocalDate dateOfBirth, String sex) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenant);
        try {
            tx.executeWithoutResult(s -> store.upsertClient(tenant, new ProjectionStore.ClientFacts(partyId, type, kyc,
                name, registeredOn, dateOfBirth, sex)));
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
```

- [ ] **Step 2: Write the failing test**

`ReportQueriesIntegrationTest.java` (Tasks 7 and 8 add more tests to this class):

```java
package tz.co.nlolo.lifeplatform.reporting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reporting.ReportingTestEvents.Sale;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportNotFoundException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(ReportingTestEvents.class)
class ReportQueriesIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            ReportingTestMigrations.ALL);
    }

    @Autowired private ReportingTestEvents events;
    @Autowired private ReportingApi reportingApi;

    // A fresh tenant per test: JUnit creates a new instance for every test method.
    private final UUID tenant = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();

    @BeforeEach
    void enterTenant() {
        TenantContext.set(tenant);
    }

    @AfterEach
    void leaveTenant() {
        TenantContext.clear();
    }

    private ReportResult run(String key, ReportFilter filter) {
        return reportingApi.run(key, filter);
    }

    private static List<Map<String, String>> rows(ReportResult result, int section) {
        return result.sections().get(section).rows();
    }

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    // ---- policies in force -------------------------------------------------------------------------------

    @Test
    void inForceCountsActiveSuspendedAndPaidUpButNotLapsed() {
        events.sell(tenant, Sale.of("P-A", product, d("2026-01-05")));
        events.sell(tenant, Sale.of("P-B", product, d("2026-01-06")).premium("QUARTERLY", "30000.00"));
        events.sell(tenant, Sale.of("P-C", product, d("2026-01-07")).premium("SINGLE", "500000.00"));
        events.sell(tenant, Sale.of("P-D", product, d("2026-01-08")));
        events.lapse(tenant, "P-D", d("2026-03-01"));
        events.sell(tenant, Sale.of("P-E", product, d("2026-01-09")));
        events.suspend(tenant, "P-E", d("2026-03-02"));
        events.sell(tenant, Sale.of("P-F", product, d("2026-01-10")));
        events.paidUp(tenant, "P-F", d("2026-03-03"));

        ReportResult result = run("policies-in-force", ReportFilter.asOf(d("2026-03-31")));

        assertThat(rows(result, 0)).singleElement().satisfies(row -> {
            assertThat(row.get("dimension")).isEqualTo(product.toString());
            assertThat(row.get("policies")).isEqualTo("5");
            // P-A 120000 + P-B 120000 + P-E 120000; P-F is paid up (0); P-C is single.
            assertThat(row.get("annualisedPremium")).isEqualTo("360000.00");
            assertThat(row.get("singlePremiums")).isEqualTo("500000.00");
            // P-F's sum assured is its paid-up 500000.
            assertThat(row.get("sumAssured")).isEqualTo("4500000.00");
        });
        assertThat(result.sections().get(0).totals().get("policies")).isEqualTo("5");
        assertThat(rows(run("policies-in-force", ReportFilter.asOf(d("2026-01-04"))), 0)).isEmpty();
    }

    @Test
    void inForceComparesTwoDates() {
        events.sell(tenant, Sale.of("P-A", product, d("2026-01-05")));
        events.sell(tenant, Sale.of("P-B", product, d("2026-02-05")));
        ReportResult result = run("policies-in-force",
            ReportFilter.asOf(d("2026-02-28")).withCompareAsOf(d("2026-01-31")));
        assertThat(rows(result, 0)).singleElement().satisfies(row -> {
            assertThat(row.get("policies")).isEqualTo("2");
            assertThat(row.get("comparePolicies")).isEqualTo("1");
            assertThat(row.get("policyChange")).isEqualTo("1");
        });
    }

    @Test
    void inForceGroupsByAgentAndNamesADirectSale() {
        events.sell(tenant, Sale.of("P-A", product, d("2026-01-05")));
        ReportResult result = run("policies-in-force", ReportFilter.asOf(d("2026-01-31")).withGroupBy(GroupBy.AGENT));
        assertThat(rows(result, 0)).extracting(r -> r.get("dimension")).containsExactly("Direct (no agent)");
        assertThat(result.sections().get(0).columns().get(0).label()).isEqualTo("Agent");
    }

    @Test
    void theClientTypeFilterNarrowsByTheHoldersType() {
        UUID person = UUID.randomUUID();
        UUID company = UUID.randomUUID();
        events.client(tenant, person, "INDIVIDUAL", "VERIFIED", "Asha", d("2025-12-01"), d("1990-01-01"), "FEMALE");
        events.client(tenant, company, "CORPORATE", "VERIFIED", "Kilimo Ltd", d("2025-12-01"), null, null);
        events.sell(tenant, Sale.of("P-A", product, d("2026-01-05")).holder(person));
        events.sell(tenant, Sale.of("P-B", product, d("2026-01-05")).holder(company));
        ReportResult result = run("policies-in-force", ReportFilter.asOf(d("2026-01-31")).withClientType("CORPORATE"));
        assertThat(rows(result, 0)).singleElement().satisfies(row -> assertThat(row.get("policies")).isEqualTo("1"));
    }

    // ---- new business ------------------------------------------------------------------------------------

    @Test
    void newBusinessCountsActivationsAndOffersNotTakenUpByMonth() {
        events.sell(tenant, Sale.of("N-1", product, d("2026-01-10")));
        events.sell(tenant, Sale.of("N-2", product, d("2026-01-20")).premium("SINGLE", "200000.00"));
        events.sell(tenant, Sale.of("N-3", product, d("2026-02-03")));
        events.offerNotTakenUp(tenant, Sale.of("N-4", product, d("2026-01-15")), d("2026-02-14"));

        ReportResult result = run("new-business", ReportFilter.period(d("2026-01-01"), d("2026-02-28")));

        assertThat(rows(result, 0)).extracting(r -> r.get("month") + " " + r.get("policies") + " "
                + r.get("notTakenUp") + " " + r.get("takeUpRate") + " " + r.get("annualisedPremium") + " " + r.get("singlePremiums"))
            .containsExactly("2026-01 2 0 100.00 120000.00 200000.00", "2026-02 1 1 50.00 120000.00 0.00");
        assertThat(result.sections().get(0).totals().get("takeUpRate")).isEqualTo("75.00");
    }

    // ---- plumbing ----------------------------------------------------------------------------------------

    @Test
    void aDateFilterThatDoesNotMatchTheReportIsRefused() {
        assertThatThrownBy(() -> run("policies-in-force", ReportFilter.period(d("2026-01-01"), d("2026-01-31"))))
            .isInstanceOf(ReportParameterException.class).hasMessageContaining("asOf");
        assertThatThrownBy(() -> run("new-business", ReportFilter.period(d("2026-02-01"), d("2026-01-01"))))
            .isInstanceOf(ReportParameterException.class).hasMessageContaining("from");
        assertThatThrownBy(() -> run("new-business", ReportFilter.period(d("2026-01-01"), d("2026-01-31"))
            .withCompareAsOf(d("2025-12-31")))).isInstanceOf(ReportParameterException.class);
        assertThatThrownBy(() -> run("no-such-report", ReportFilter.asOf(d("2026-01-31"))))
            .isInstanceOf(ReportNotFoundException.class);
    }

    @Test
    void theCatalogueListsReportsInBusinessOrder() {
        assertThat(reportingApi.catalogue()).extracting(r -> r.key())
            .startsWith("policies-in-force", "new-business");
    }

    @Test
    void theResultCarriesTitleSummaryAndGenerationTime() {
        ReportResult result = run("policies-in-force", ReportFilter.asOf(d("2026-01-31")));
        assertThat(result.title()).isEqualTo("Policies in force");
        assertThat(result.filterSummary()).contains("As of 2026-01-31");
        assertThat(result.generatedAt()).isNotNull();
        ReportSection section = result.sections().get(0);
        assertThat(section.totals()).isNotNull();
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./mvnw -B test -Dtest=ReportQueriesIntegrationTest`
Expected: compilation FAILURE (api types missing).

- [ ] **Step 4: Implement the api types**

`ReportCategory.java`: `public enum ReportCategory { MANAGEMENT }`

`DateFilterKind.java`: `public enum DateFilterKind { AS_OF, PERIOD, COHORT, AS_OF_AND_PERIOD }`

`ColumnType.java`: `public enum ColumnType { TEXT, COUNT, MONEY, PERCENT, DATE }`

(each in `package tz.co.nlolo.lifeplatform.reporting.api;`)

`GroupBy.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.api;

public enum GroupBy {
    PRODUCT("Product"), PRODUCT_CATEGORY("Product category"), AGENT("Agent"), CHANNEL("Sales channel"), BRANCH("Branch");

    private final String label;

    GroupBy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
```

`ReportDefinitionView.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.api;

import java.util.List;

/** One catalogue entry. comparable: the report accepts a second as-of date to compare against. */
public record ReportDefinitionView(String key, String title, String description, ReportCategory category,
                                   DateFilterKind dateFilterKind, List<GroupBy> groupByOptions, boolean comparable) {}
```

`ReportFilter.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.api;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/** Every filter any report takes. Which date fields a report needs is its DateFilterKind; the rest must be
 * null (ReportFilters enforces both). */
public record ReportFilter(LocalDate asOf, LocalDate compareAsOf, LocalDate from, LocalDate to, YearMonth cohortFrom,
                           YearMonth cohortTo, UUID productId, String productCategory, UUID agentId, String salesChannel,
                           String branchCode, String clientType, GroupBy groupBy) {

    public static ReportFilter none() {
        return new ReportFilter(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static ReportFilter asOf(LocalDate asOf) {
        return none().withAsOf(asOf);
    }

    public static ReportFilter period(LocalDate from, LocalDate to) {
        return none().withPeriod(from, to);
    }

    public static ReportFilter cohort(YearMonth from, YearMonth to) {
        return new ReportFilter(null, null, null, null, from, to, null, null, null, null, null, null, null);
    }

    public ReportFilter withAsOf(LocalDate date) {
        return new ReportFilter(date, compareAsOf, from, to, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, clientType, groupBy);
    }

    public ReportFilter withPeriod(LocalDate start, LocalDate end) {
        return new ReportFilter(asOf, compareAsOf, start, end, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, clientType, groupBy);
    }

    public ReportFilter withCompareAsOf(LocalDate date) {
        return new ReportFilter(asOf, date, from, to, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, clientType, groupBy);
    }

    public ReportFilter withGroupBy(GroupBy by) {
        return new ReportFilter(asOf, compareAsOf, from, to, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, clientType, by);
    }

    public ReportFilter withClientType(String type) {
        return new ReportFilter(asOf, compareAsOf, from, to, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, type, groupBy);
    }

    public ReportFilter withProductId(UUID id) {
        return new ReportFilter(asOf, compareAsOf, from, to, cohortFrom, cohortTo, id, productCategory, agentId,
            salesChannel, branchCode, clientType, groupBy);
    }

    public GroupBy groupByOr(GroupBy fallback) {
        return groupBy != null ? groupBy : fallback;
    }
}
```

`ReportColumn.java`: `public record ReportColumn(String key, String label, ColumnType type) {}`

`ReportSection.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.api;

import java.util.List;
import java.util.Map;

/** One table. Every value is a string keyed by column key. totals is empty, never null, when a section has
 * no total row (a sum across currencies, for instance, means nothing). */
public record ReportSection(String title, List<ReportColumn> columns, List<Map<String, String>> rows,
                            Map<String, String> totals) {}
```

`ReportResult.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.api;

import java.time.Instant;
import java.util.List;

public record ReportResult(String key, String title, String filterSummary, Instant generatedAt, List<ReportSection> sections) {}
```

`ReportNotFoundException.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.api;

public class ReportNotFoundException extends RuntimeException {
    public ReportNotFoundException(String key) {
        super("No report called '" + key + "'");
    }
}
```

`ReportParameterException.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.api;

public class ReportParameterException extends RuntimeException {
    public ReportParameterException(String message) {
        super(message);
    }
}
```

`ReportingApi.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.api;

import java.util.List;

/** Every call is scoped to the caller's tenant (TenantContext). */
public interface ReportingApi {
    List<ReportDefinitionView> catalogue();

    /** @throws ReportNotFoundException for an unknown key; @throws ReportParameterException for a filter the report cannot take */
    ReportResult run(String key, ReportFilter filter);

    RebuildStatusView startRebuild(String requestedBy);

    RebuildStatusView rebuildStatus();
}
```

- [ ] **Step 5: Implement `ReportSql` and the first `ReportQueries` methods**

`ReportSql.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;

import java.util.List;

/** SQL fragments every report shares. Each fragment states the parameters it binds, in order. */
public final class ReportSql {
    private ReportSql() {}

    public static final String IN_FORCE_STATUSES = "('ACTIVE','SUSPENDED','PAID_UP')";

    /** Binds (tenant, as-of date). The latest status change per policy on or before the date, ordered as
     * StatusTimeline orders it. */
    public static final String LAST_CHANGE_CTE = """
        last_change AS (
            SELECT DISTINCT ON (s.policy_number) s.policy_number, s.to_status
            FROM reporting.report_policy_status_change s
            WHERE s.tenant_id = ? AND s.effective_on <= ?
            ORDER BY s.policy_number, s.effective_on DESC, s.occurred_at DESC, s.event_id DESC)
        """;

    /** Binds nothing. Alias c, joined to policy alias p. */
    public static final String CLIENT_JOIN =
        " LEFT JOIN reporting.report_client c ON c.tenant_id = p.tenant_id AND c.party_id = p.holder_party_id";

    /** Annualised premium as sold (AnnualisedPremium in SQL). An unrecognised frequency is NULL, so SUM skips it. */
    public static final String ANNUALISED_AT_SALE =
        "(CASE p.premium_frequency WHEN 'MONTHLY' THEN p.premium * 12 WHEN 'QUARTERLY' THEN p.premium * 4"
            + " WHEN 'ANNUALLY' THEN p.premium END)";

    /** Needs last_change alias l: a paid-up policy pays nothing. */
    public static final String ANNUALISED_IN_FORCE =
        "(CASE WHEN l.to_status = 'PAID_UP' THEN 0 ELSE " + ANNUALISED_AT_SALE + " END)";

    public static final String SINGLE_PREMIUM = "(CASE WHEN p.premium_frequency = 'SINGLE' THEN p.premium END)";

    /** The grouping column, from a closed list -- never from request text. */
    public static String dimension(GroupBy groupBy) {
        return switch (groupBy) {
            case PRODUCT -> "COALESCE(p.product_id, '00000000-0000-0000-0000-000000000000'::uuid)::text";
            case PRODUCT_CATEGORY -> "COALESCE(p.product_category, 'UNKNOWN')";
            case AGENT -> "COALESCE(p.agent_id::text, 'DIRECT')";
            case CHANNEL -> "COALESCE(p.sales_channel, 'UNSPECIFIED')";
            case BRANCH -> "COALESCE(p.branch_code, 'UNSPECIFIED')";
        };
    }

    /** Appends the common filters on policy alias p and client alias c, binding their values. */
    public static void appendFilters(StringBuilder sql, List<Object> args, ReportFilter f) {
        if (f.productId() != null) { sql.append(" AND p.product_id = ?"); args.add(f.productId()); }
        if (f.productCategory() != null) { sql.append(" AND p.product_category = ?"); args.add(f.productCategory()); }
        if (f.agentId() != null) { sql.append(" AND p.agent_id = ?"); args.add(f.agentId()); }
        if (f.salesChannel() != null) { sql.append(" AND p.sales_channel = ?"); args.add(f.salesChannel()); }
        if (f.branchCode() != null) { sql.append(" AND p.branch_code = ?"); args.add(f.branchCode()); }
        if (f.clientType() != null) { sql.append(" AND c.party_type = ?"); args.add(f.clientType()); }
    }
}
```

`ReportQueries.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportSql.ANNUALISED_AT_SALE;
import static tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportSql.ANNUALISED_IN_FORCE;
import static tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportSql.CLIENT_JOIN;
import static tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportSql.IN_FORCE_STATUSES;
import static tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportSql.LAST_CHANGE_CTE;
import static tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportSql.SINGLE_PREMIUM;
import static tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportSql.appendFilters;
import static tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportSql.dimension;

/** Every report read. Each query filters tenant_id explicitly. */
@Repository
public class ReportQueries {

    private final JdbcTemplate jdbc;

    public ReportQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record InForceRow(String dimension, String currency, long policies, BigDecimal sumAssured,
                             BigDecimal annualised, BigDecimal single) {}

    public record NewBusinessRow(String month, String dimension, String currency, long policies, BigDecimal sumAssured,
                                 BigDecimal annualised, BigDecimal single) {}

    public record MonthCount(String month, String dimension, String currency, long count) {}

    public List<InForceRow> inForce(UUID tenantId, LocalDate asOf, GroupBy groupBy, ReportFilter f) {
        List<Object> args = new ArrayList<>(List.of(tenantId, asOf, tenantId));
        StringBuilder sql = new StringBuilder("WITH " + LAST_CHANGE_CTE + " SELECT " + dimension(groupBy) + " AS dim,"
            + " p.currency, COUNT(*) AS policies, COALESCE(SUM(p.sum_assured), 0) AS sum_assured,"
            + " COALESCE(SUM(" + ANNUALISED_IN_FORCE + "), 0) AS annualised,"
            + " COALESCE(SUM(" + SINGLE_PREMIUM + "), 0) AS single_premium"
            + " FROM reporting.report_policy p JOIN last_change l ON l.policy_number = p.policy_number" + CLIENT_JOIN
            + " WHERE p.tenant_id = ? AND l.to_status IN " + IN_FORCE_STATUSES);
        appendFilters(sql, args, f);
        sql.append(" GROUP BY dim, p.currency ORDER BY dim, p.currency");
        return jdbc.query(sql.toString(), (rs, i) -> new InForceRow(rs.getString("dim"), rs.getString("currency").trim(),
            rs.getLong("policies"), rs.getBigDecimal("sum_assured"), rs.getBigDecimal("annualised"),
            rs.getBigDecimal("single_premium")), args.toArray());
    }

    public List<NewBusinessRow> activations(UUID tenantId, LocalDate from, LocalDate to, GroupBy groupBy, ReportFilter f) {
        List<Object> args = new ArrayList<>(List.of(tenantId, from, to));
        StringBuilder sql = new StringBuilder("SELECT to_char(p.activated_on, 'YYYY-MM') AS month, " + dimension(groupBy)
            + " AS dim, p.currency, COUNT(*) AS policies, COALESCE(SUM(p.sum_assured), 0) AS sum_assured,"
            + " COALESCE(SUM(" + ANNUALISED_AT_SALE + "), 0) AS annualised,"
            + " COALESCE(SUM(" + SINGLE_PREMIUM + "), 0) AS single_premium"
            + " FROM reporting.report_policy p" + CLIENT_JOIN
            + " WHERE p.tenant_id = ? AND p.activated_on BETWEEN ? AND ?");
        appendFilters(sql, args, f);
        sql.append(" GROUP BY month, dim, p.currency ORDER BY month, dim, p.currency");
        return jdbc.query(sql.toString(), (rs, i) -> new NewBusinessRow(rs.getString("month"), rs.getString("dim"),
            rs.getString("currency").trim(), rs.getLong("policies"), rs.getBigDecimal("sum_assured"),
            rs.getBigDecimal("annualised"), rs.getBigDecimal("single_premium")), args.toArray());
    }

    public List<MonthCount> notTakenUp(UUID tenantId, LocalDate from, LocalDate to, GroupBy groupBy, ReportFilter f) {
        List<Object> args = new ArrayList<>(List.of(tenantId, from, to));
        StringBuilder sql = new StringBuilder("SELECT to_char(p.not_taken_up_on, 'YYYY-MM') AS month, " + dimension(groupBy)
            + " AS dim, p.currency, COUNT(*) AS n FROM reporting.report_policy p" + CLIENT_JOIN
            + " WHERE p.tenant_id = ? AND p.not_taken_up_on BETWEEN ? AND ?");
        appendFilters(sql, args, f);
        sql.append(" GROUP BY month, dim, p.currency");
        return jdbc.query(sql.toString(), (rs, i) -> new MonthCount(rs.getString("month"), rs.getString("dim"),
            rs.getString("currency").trim(), rs.getLong("n")), args.toArray());
    }
}
```

- [ ] **Step 6: Implement the application plumbing**

`ReportingConfig.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.co.nlolo.lifeplatform.reporting.domain.CivilDates;

import java.time.Clock;

@Configuration
public class ReportingConfig {

    /** Qualified, as unitlinked's clock is: an unqualified Clock bean would be injected into every module. */
    @Bean("reportingClock")
    Clock reportingClock() {
        return Clock.system(CivilDates.ZONE);
    }
}
```

`Report.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.util.List;
import java.util.UUID;

/** One report. The filter has already been validated against definition() when run is called. */
public interface Report {
    ReportDefinitionView definition();

    List<ReportSection> run(UUID tenantId, ReportFilter filter, Labels labels);
}
```

`Sections.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.reporting.api.ColumnType;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class Sections {
    private Sections() {}

    static ReportColumn col(String key, String label, ColumnType type) {
        return new ReportColumn(key, label, type);
    }

    /**
     * Sums every COUNT and MONEY column; the first column reads "Total"; everything else is blank. Empty --
     * no total row -- when the rows carry more than one currency, because adding shillings to dollars is a
     * number that means nothing.
     */
    static Map<String, String> totals(List<ReportColumn> columns, List<Map<String, String>> rows) {
        if (rows.isEmpty()) return Map.of();
        long currencies = rows.stream().map(r -> r.get("currency")).filter(Objects::nonNull).distinct().count();
        if (currencies > 1) return Map.of();
        Map<String, String> totals = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            ReportColumn c = columns.get(i);
            if (i == 0) {
                totals.put(c.key(), "Total");
            } else if (c.type() == ColumnType.COUNT) {
                totals.put(c.key(), Figures.count(rows.stream().mapToLong(r -> Long.parseLong(r.getOrDefault(c.key(), "0"))).sum()));
            } else if (c.type() == ColumnType.MONEY) {
                totals.put(c.key(), Figures.money(rows.stream().map(r -> new BigDecimal(r.getOrDefault(c.key(), "0")))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)));
            } else if ("currency".equals(c.key())) {
                totals.put(c.key(), rows.get(0).get("currency"));
            } else {
                totals.put(c.key(), "");
            }
        }
        return totals;
    }
}
```

`ReportFilters.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;

import java.util.Set;

/** A report gets exactly the date fields its kind names -- a stray one is refused, not silently ignored, so a
 * caller never believes a filter was applied when it was not. */
final class ReportFilters {
    private ReportFilters() {}

    private static final Set<String> CLIENT_TYPES = Set.of("INDIVIDUAL", "CORPORATE", "GROUP");

    static void validate(ReportDefinitionView d, ReportFilter f) {
        String key = d.key();
        switch (d.dateFilterKind()) {
            case AS_OF -> {
                require(f.asOf() != null, "asOf is required for " + key);
                refuse(f.from() != null || f.to() != null, "from/to do not apply to " + key + "; it takes asOf");
                refuse(f.cohortFrom() != null || f.cohortTo() != null, "cohortFrom/cohortTo do not apply to " + key);
            }
            case PERIOD -> {
                require(f.from() != null && f.to() != null, "from and to are required for " + key);
                refuse(f.from().isAfter(f.to()), "from must not be after to");
                refuse(f.asOf() != null, "asOf does not apply to " + key + "; it takes from and to");
                refuse(f.cohortFrom() != null || f.cohortTo() != null, "cohortFrom/cohortTo do not apply to " + key);
            }
            case COHORT -> {
                require(f.cohortFrom() != null && f.cohortTo() != null, "cohortFrom and cohortTo are required for " + key);
                refuse(f.cohortFrom().isAfter(f.cohortTo()), "cohortFrom must not be after cohortTo");
                refuse(f.asOf() != null || f.from() != null || f.to() != null,
                    "asOf/from/to do not apply to " + key + "; it takes cohortFrom and cohortTo");
            }
            case AS_OF_AND_PERIOD -> {
                require(f.asOf() != null && f.from() != null && f.to() != null, "asOf, from and to are required for " + key);
                refuse(f.from().isAfter(f.to()), "from must not be after to");
                refuse(f.cohortFrom() != null || f.cohortTo() != null, "cohortFrom/cohortTo do not apply to " + key);
            }
        }
        refuse(f.compareAsOf() != null && !d.comparable(), "compareAsOf does not apply to " + key);
        refuse(f.groupBy() != null && !d.groupByOptions().contains(f.groupBy()),
            key + " cannot be grouped by " + f.groupBy());
        refuse(f.clientType() != null && !CLIENT_TYPES.contains(f.clientType()),
            "clientType must be one of " + CLIENT_TYPES);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new ReportParameterException(message);
    }

    private static void refuse(boolean condition, String message) {
        if (condition) throw new ReportParameterException(message);
    }
}
```

`Labels.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.distribution.api.AgentView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Names for ids, resolved through the owning modules' APIs and cached for one report run. A name that cannot
 * be resolved falls back to the id: a label is cosmetic, and a report must not fail because a product was
 * deleted or an agent record moved.
 */
public class Labels {

    static final String DIRECT = "Direct (no agent)";
    static final String UNKNOWN_PRODUCT = "Unknown product";

    private final ProductApi productApi;
    private final DistributionApi distributionApi;
    private final PartyApi partyApi;
    private final Map<UUID, String> products = new HashMap<>();
    private final Map<UUID, String> agents = new HashMap<>();

    Labels(ProductApi productApi, DistributionApi distributionApi, PartyApi partyApi) {
        this.productApi = productApi;
        this.distributionApi = distributionApi;
        this.partyApi = partyApi;
    }

    public String product(UUID productId) {
        if (productId == null || ReportingEventHandlers.UNKNOWN_PRODUCT.equals(productId)) return UNKNOWN_PRODUCT;
        return products.computeIfAbsent(productId, id -> {
            try {
                return productApi.getProduct(id).productName();
            } catch (RuntimeException e) {
                return id.toString();
            }
        });
    }

    public String agent(UUID agentId) {
        if (agentId == null) return DIRECT;
        return agents.computeIfAbsent(agentId, id -> {
            try {
                AgentView agent = distributionApi.getAgent(id);
                return partyApi.getParty(agent.partyId()).displayName() + " (" + agent.licenseNumber() + ")";
            } catch (RuntimeException e) {
                return id.toString();
            }
        });
    }

    public String dimension(GroupBy groupBy, String raw) {
        return switch (groupBy) {
            case PRODUCT -> product(UUID.fromString(raw));
            case AGENT -> "DIRECT".equals(raw) ? DIRECT : agent(UUID.fromString(raw));
            default -> raw;
        };
    }
}
```

`LabelsFactory.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

@Component
public class LabelsFactory {

    private final ProductApi productApi;
    private final DistributionApi distributionApi;
    private final PartyApi partyApi;

    public LabelsFactory(ProductApi productApi, DistributionApi distributionApi, PartyApi partyApi) {
        this.productApi = productApi;
        this.distributionApi = distributionApi;
        this.partyApi = partyApi;
    }

    public Labels create() {
        return new Labels(productApi, distributionApi, partyApi);
    }
}
```

`FilterSummary.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;

import java.util.ArrayList;
import java.util.List;

/** The one line under a report's title -- and the About line of every export -- saying what was asked for. */
final class FilterSummary {
    private FilterSummary() {}

    static String describe(ReportDefinitionView d, ReportFilter f, Labels labels) {
        List<String> parts = new ArrayList<>();
        if (f.asOf() != null) parts.add("As of " + f.asOf());
        if (f.compareAsOf() != null) parts.add("compared with " + f.compareAsOf());
        if (f.from() != null) parts.add((f.asOf() != null ? "Period " : "From ") + f.from() + " to " + f.to());
        if (f.cohortFrom() != null) parts.add("Activated " + f.cohortFrom() + " to " + f.cohortTo());
        if (!d.groupByOptions().isEmpty()) parts.add("Grouped by " + f.groupByOr(d.groupByOptions().get(0)).label());
        if (f.productId() != null) parts.add("Product: " + labels.product(f.productId()));
        if (f.productCategory() != null) parts.add("Category: " + f.productCategory());
        if (f.agentId() != null) parts.add("Agent: " + labels.agent(f.agentId()));
        if (f.salesChannel() != null) parts.add("Channel: " + f.salesChannel());
        if (f.branchCode() != null) parts.add("Branch: " + f.branchCode());
        if (f.clientType() != null) parts.add("Client type: " + f.clientType());
        return String.join(" · ", parts);
    }
}
```

- [ ] **Step 7: Implement the two reports and the API**

`PoliciesInForceReport.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.InForceRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

@Component
public class PoliciesInForceReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("policies-in-force", "Policies in force",
        "Policies on cover on a date: count, sum assured and premium. Suspended and paid-up policies are in force; "
            + "a paid-up policy contributes no premium.",
        ReportCategory.MANAGEMENT, DateFilterKind.AS_OF, List.of(GroupBy.values()), true);

    private final ReportQueries queries;

    public PoliciesInForceReport(ReportQueries queries) {
        this.queries = queries;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        GroupBy groupBy = f.groupByOr(GroupBy.PRODUCT);
        Map<String, InForceRow> now = index(queries.inForce(tenantId, f.asOf(), groupBy, f));
        Map<String, InForceRow> then = f.compareAsOf() == null ? Map.of()
            : index(queries.inForce(tenantId, f.compareAsOf(), groupBy, f));

        List<ReportColumn> columns = new ArrayList<>(List.of(col("dimension", groupBy.label(), TEXT),
            col("currency", "Currency", TEXT), col("policies", "Policies", COUNT), col("sumAssured", "Sum assured", MONEY),
            col("annualisedPremium", "Annualised premium", MONEY), col("singlePremiums", "Single premiums", MONEY)));
        if (f.compareAsOf() != null) {
            columns.add(col("comparePolicies", "Policies on " + f.compareAsOf(), COUNT));
            columns.add(col("policyChange", "Change", COUNT));
        }

        Set<String> keys = new LinkedHashSet<>(now.keySet());
        keys.addAll(then.keySet());
        List<Map<String, String>> rows = new ArrayList<>();
        for (String key : keys) {
            InForceRow n = now.get(key);
            InForceRow c = then.get(key);
            InForceRow any = n != null ? n : c;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("dimension", labels.dimension(groupBy, any.dimension()));
            row.put("currency", any.currency());
            row.put("policies", Figures.count(n == null ? 0 : n.policies()));
            row.put("sumAssured", Figures.money(n == null ? null : n.sumAssured()));
            row.put("annualisedPremium", Figures.money(n == null ? null : n.annualised()));
            row.put("singlePremiums", Figures.money(n == null ? null : n.single()));
            if (f.compareAsOf() != null) {
                long before = c == null ? 0 : c.policies();
                row.put("comparePolicies", Figures.count(before));
                row.put("policyChange", Figures.count((n == null ? 0 : n.policies()) - before));
            }
            rows.add(row);
        }
        rows.sort(Comparator.comparing((Map<String, String> r) -> r.get("dimension")).thenComparing(r -> r.get("currency")));
        return List.of(new ReportSection("Policies in force on " + f.asOf(), columns, rows, Sections.totals(columns, rows)));
    }

    private static Map<String, InForceRow> index(List<InForceRow> rows) {
        Map<String, InForceRow> byKey = new LinkedHashMap<>();
        rows.forEach(r -> byKey.put(r.dimension() + "|" + r.currency(), r));
        return byKey;
    }
}
```

`NewBusinessReport.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.MonthCount;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.NewBusinessRow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.PERCENT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

@Component
public class NewBusinessReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("new-business", "New business",
        "Policies activated each month, offers that expired unpaid, and the take-up rate between them.",
        ReportCategory.MANAGEMENT, DateFilterKind.PERIOD, List.of(GroupBy.values()), false);

    private final ReportQueries queries;

    public NewBusinessReport(ReportQueries queries) {
        this.queries = queries;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        GroupBy groupBy = f.groupByOr(GroupBy.PRODUCT);
        Map<String, NewBusinessRow> sold = new HashMap<>();
        queries.activations(tenantId, f.from(), f.to(), groupBy, f)
            .forEach(r -> sold.put(r.month() + "|" + r.dimension() + "|" + r.currency(), r));
        Map<String, MonthCount> expired = new HashMap<>();
        queries.notTakenUp(tenantId, f.from(), f.to(), groupBy, f)
            .forEach(r -> expired.put(r.month() + "|" + r.dimension() + "|" + r.currency(), r));

        List<ReportColumn> columns = List.of(col("month", "Month", TEXT), col("dimension", groupBy.label(), TEXT),
            col("currency", "Currency", TEXT), col("policies", "Policies activated", COUNT),
            col("sumAssured", "Sum assured", MONEY), col("annualisedPremium", "Annualised premium", MONEY),
            col("singlePremiums", "Single premiums", MONEY), col("notTakenUp", "Offers not taken up", COUNT),
            col("takeUpRate", "Take-up rate %", PERCENT));

        List<Map<String, String>> rows = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>(sold.keySet());
        keys.addAll(expired.keySet());
        for (String key : keys) {
            String[] parts = key.split("\\|", -1);
            NewBusinessRow s = sold.get(key);
            long activated = s == null ? 0 : s.policies();
            long lapsedOffers = expired.containsKey(key) ? expired.get(key).count() : 0;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("month", parts[0]);
            row.put("dimension", labels.dimension(groupBy, parts[1]));
            row.put("currency", parts[2]);
            row.put("policies", Figures.count(activated));
            row.put("sumAssured", Figures.money(s == null ? null : s.sumAssured()));
            row.put("annualisedPremium", Figures.money(s == null ? null : s.annualised()));
            row.put("singlePremiums", Figures.money(s == null ? null : s.single()));
            row.put("notTakenUp", Figures.count(lapsedOffers));
            row.put("takeUpRate", Figures.percent(BigDecimal.valueOf(activated), BigDecimal.valueOf(activated + lapsedOffers)));
            rows.add(row);
        }
        rows.sort(Comparator.comparing((Map<String, String> r) -> r.get("month")).thenComparing(r -> r.get("dimension")));
        Map<String, String> totals = new LinkedHashMap<>(Sections.totals(columns, rows));
        if (!totals.isEmpty()) {
            long activated = Long.parseLong(totals.get("policies"));
            long offers = Long.parseLong(totals.get("notTakenUp"));
            totals.put("takeUpRate", Figures.percent(BigDecimal.valueOf(activated), BigDecimal.valueOf(activated + offers)));
        }
        return List.of(new ReportSection("Activated " + f.from() + " to " + f.to(), columns, rows, totals));
    }
}
```

`ReportingApiImpl.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reporting.api.RebuildStatusView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportNotFoundException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Not @Transactional on purpose: a report is several independent reads, and a label lookup that fails in
 * another module must not abort the transaction the report's own queries run in. */
@Service
public class ReportingApiImpl implements ReportingApi {

    /** Catalogue order: the order a manager reads the business in. */
    static final List<String> ORDER = List.of("policies-in-force", "new-business", "lapse-rate", "persistency",
        "premium-collections", "clients");

    private final Map<String, Report> reports = new LinkedHashMap<>();
    private final LabelsFactory labelsFactory;
    private final RebuildService rebuildService;
    private final Clock clock;

    public ReportingApiImpl(List<Report> beans, LabelsFactory labelsFactory, RebuildService rebuildService,
                            @Qualifier("reportingClock") Clock clock) {
        beans.stream().sorted(Comparator.comparingInt(r -> {
            int i = ORDER.indexOf(r.definition().key());
            return i < 0 ? Integer.MAX_VALUE : i;
        })).forEach(r -> reports.put(r.definition().key(), r));
        this.labelsFactory = labelsFactory;
        this.rebuildService = rebuildService;
        this.clock = clock;
    }

    @Override
    public List<ReportDefinitionView> catalogue() {
        return reports.values().stream().map(Report::definition).toList();
    }

    @Override
    public ReportResult run(String key, ReportFilter filter) {
        Report report = reports.get(key);
        if (report == null) throw new ReportNotFoundException(key);
        ReportFilters.validate(report.definition(), filter);
        Labels labels = labelsFactory.create();
        List<ReportSection> sections = report.run(TenantContext.get(), filter, labels);
        return new ReportResult(key, report.definition().title(), FilterSummary.describe(report.definition(), filter, labels),
            Instant.now(clock), sections);
    }

    @Override
    public RebuildStatusView startRebuild(String requestedBy) {
        return rebuildService.start(TenantContext.get(), requestedBy);
    }

    @Override
    public RebuildStatusView rebuildStatus() {
        return rebuildService.status(TenantContext.get());
    }
}
```

- [ ] **Step 8: Run to verify it passes**

Run: `./mvnw -B clean test-compile` then `./mvnw -B test -Dtest=ReportQueriesIntegrationTest,ModularityTests,ReportingSqlStaysInSchemaTest`
Expected: PASS, Tests run: 10. `ModularityTests` proves the `product::api` and `distribution::api` uses are allowed.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/reporting backend/src/test/java/tz/co/nlolo/lifeplatform/reporting
git commit -m "feat(reporting): report plumbing, policies in force and new business"
```

---

### Task 7: Lapse rate and persistency

**Files:**
- Modify: `reporting/infrastructure/ReportQueries.java` (add `causeCounts`, `cohort`)
- Create: `reporting/application/LapseRateReport.java`, `reporting/application/PersistencyReport.java`
- Modify test: `ReportQueriesIntegrationTest.java` (add tests)

**Interfaces:**
- Consumes: `ReportQueries.inForce`, `StatusTimeline`, `PersistencyMonths`, `AnnualisedPremium`, `CivilDates.today(Clock)`.
- Produces: `ReportQueries.causeCounts(UUID, String cause, LocalDate from, LocalDate to, GroupBy, ReportFilter) -> Map<String, Long>`; `ReportQueries.cohort(UUID, YearMonth from, YearMonth to, GroupBy, ReportFilter) -> List<CohortRow>` with `record CohortRow(String policyNumber, LocalDate activatedOn, BigDecimal premium, String frequency, String dimension, PolicyStatus status, LocalDate effectiveOn, Instant occurredAt, UUID eventId)`; report keys `lapse-rate`, `persistency`.

- [ ] **Step 1: Add the failing tests to `ReportQueriesIntegrationTest`**

```java
    // ---- lapse rate --------------------------------------------------------------------------------------

    @Test
    void lapseRateIsLapsesOverAverageInForceGrossAndNetOfReinstatements() {
        for (String n : List.of("L-1", "L-2", "L-3", "L-4")) {
            events.sell(tenant, Sale.of(n, product, d("2025-01-10")));
        }
        events.lapse(tenant, "L-1", d("2025-03-05"));
        events.lapse(tenant, "L-2", d("2025-03-20"));
        events.reinstate(tenant, "L-2", d("2025-03-25"));

        ReportResult result = run("lapse-rate", ReportFilter.period(d("2025-03-01"), d("2025-03-31")));

        assertThat(rows(result, 0)).singleElement().satisfies(row -> {
            assertThat(row.get("inForceStart")).isEqualTo("4");
            assertThat(row.get("inForceEnd")).isEqualTo("3");
            assertThat(row.get("averageInForce")).isEqualTo("3.50");
            assertThat(row.get("lapses")).isEqualTo("2");
            assertThat(row.get("reinstatements")).isEqualTo("1");
            assertThat(row.get("grossLapseRate")).isEqualTo("57.14");
            assertThat(row.get("netLapseRate")).isEqualTo("28.57");
        });
    }

    // ---- persistency -------------------------------------------------------------------------------------

    @Test
    void persistencyFollowsACohortAndLeavesOutFreeLookSingleAndUnreachedMonths() {
        LocalDate jan = d("2023-01-15");
        events.sell(tenant, Sale.of("C-A", product, jan));                                       // stays in force
        events.sell(tenant, Sale.of("C-B", product, jan).premium("QUARTERLY", "30000.00"));      // lapses before month 13
        events.lapse(tenant, "C-B", d("2023-08-01"));
        events.sell(tenant, Sale.of("C-C", product, jan).premium("ANNUALLY", "60000.00"));       // lapses between 13 and 25
        events.lapse(tenant, "C-C", d("2024-06-01"));
        events.sell(tenant, Sale.of("C-D", product, jan));                                       // free-look: excluded
        events.cancelFreeLook(tenant, "C-D", d("2023-01-20"));
        events.sell(tenant, Sale.of("C-E", product, jan).premium("SINGLE", "500000.00"));        // single: excluded
        events.sell(tenant, Sale.of("C-F", product, jan).portfolio("SAV"));                      // investment: excluded

        ReportResult result = run("persistency", ReportFilter.cohort(java.time.YearMonth.of(2023, 1),
            java.time.YearMonth.of(2023, 1)));

        assertThat(result.sections().get(0).columns()).extracting(c -> c.key())
            .contains("month13ByCount", "month25ByCount", "month37ByCount").doesNotContain("month61ByCount");
        assertThat(rows(result, 0)).singleElement().satisfies(row -> {
            assertThat(row.get("policies")).isEqualTo("3");
            assertThat(row.get("annualisedPremium")).isEqualTo("300000.00");
            assertThat(row.get("month13ByCount")).isEqualTo("66.67");
            assertThat(row.get("month13ByPremium")).isEqualTo("60.00");
            assertThat(row.get("month25ByCount")).isEqualTo("33.33");
            assertThat(row.get("month25ByPremium")).isEqualTo("40.00");
        });
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -B test -Dtest=ReportQueriesIntegrationTest`
Expected: 2 FAIL with `ReportNotFoundException: No report called 'lapse-rate'` / `'persistency'`.

- [ ] **Step 3: Add the queries**

Add to `ReportQueries` (imports: `java.time.Instant`, `java.time.YearMonth`, `java.util.HashMap`, `java.util.Map`, `tz.co.nlolo.lifeplatform.reporting.domain.PolicyStatus`):

```java
    public record CohortRow(String policyNumber, LocalDate activatedOn, BigDecimal premium, String frequency,
                            String dimension, PolicyStatus status, LocalDate effectiveOn, Instant occurredAt, UUID eventId) {}

    /** Status changes with this cause (an event type) whose effective date falls in the period, per dimension. */
    public Map<String, Long> causeCounts(UUID tenantId, String cause, LocalDate from, LocalDate to, GroupBy groupBy,
                                         ReportFilter f) {
        List<Object> args = new ArrayList<>(List.of(tenantId, cause, from, to));
        StringBuilder sql = new StringBuilder("SELECT " + dimension(groupBy) + " AS dim, COUNT(*) AS n"
            + " FROM reporting.report_policy_status_change s"
            + " JOIN reporting.report_policy p ON p.tenant_id = s.tenant_id AND p.policy_number = s.policy_number"
            + CLIENT_JOIN + " WHERE s.tenant_id = ? AND s.cause = ? AND s.effective_on BETWEEN ? AND ?");
        appendFilters(sql, args, f);
        sql.append(" GROUP BY dim");
        Map<String, Long> counts = new HashMap<>();
        jdbc.query(sql.toString(), rs -> { counts.put(rs.getString("dim"), rs.getLong("n")); }, args.toArray());
        return counts;
    }

    /**
     * Every status change of every policy activated in the cohort months -- one row per change -- leaving out
     * single premiums and investment portfolios, which cannot lapse. Free-look cancellations are dropped by the
     * caller from the history itself.
     */
    public List<CohortRow> cohort(UUID tenantId, YearMonth from, YearMonth to, GroupBy groupBy, ReportFilter f) {
        List<Object> args = new ArrayList<>(List.of(tenantId, from.atDay(1), to.atEndOfMonth()));
        StringBuilder sql = new StringBuilder("SELECT p.policy_number, p.activated_on, p.premium, p.premium_frequency, "
            + dimension(groupBy) + " AS dim, s.to_status, s.effective_on, s.occurred_at, s.event_id"
            + " FROM reporting.report_policy p"
            + " JOIN reporting.report_policy_status_change s ON s.tenant_id = p.tenant_id AND s.policy_number = p.policy_number"
            + CLIENT_JOIN + " WHERE p.tenant_id = ? AND p.activated_on BETWEEN ? AND ?"
            + " AND COALESCE(p.premium_frequency, '') <> 'SINGLE'"
            + " AND COALESCE(p.portfolio_code, '') NOT IN ('SAV','DEP','PEN','DANN')");
        appendFilters(sql, args, f);
        return jdbc.query(sql.toString(), (rs, i) -> new CohortRow(rs.getString("policy_number"),
            rs.getObject("activated_on", LocalDate.class), rs.getBigDecimal("premium"), rs.getString("premium_frequency"),
            rs.getString("dim"), PolicyStatus.valueOf(rs.getString("to_status")),
            rs.getObject("effective_on", LocalDate.class), rs.getTimestamp("occurred_at").toInstant(),
            rs.getObject("event_id", UUID.class)), args.toArray());
    }
```

- [ ] **Step 4: Implement `LapseRateReport`**

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.PERCENT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

@Component
public class LapseRateReport implements Report {

    static final String LAPSED = "policy.PolicyLapsed";
    static final String REINSTATED = "policy.PolicyReinstated";

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("lapse-rate", "Lapse rate",
        "Lapses in the period divided by the average number of policies in force (start plus end, halved), "
            + "gross and net of reinstatements in the same period.",
        ReportCategory.MANAGEMENT, DateFilterKind.PERIOD, List.of(GroupBy.values()), false);

    private final ReportQueries queries;

    public LapseRateReport(ReportQueries queries) {
        this.queries = queries;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        GroupBy groupBy = f.groupByOr(GroupBy.PRODUCT);
        Map<String, Long> start = policiesByDimension(tenantId, f.from().minusDays(1), groupBy, f);
        Map<String, Long> end = policiesByDimension(tenantId, f.to(), groupBy, f);
        Map<String, Long> lapses = queries.causeCounts(tenantId, LAPSED, f.from(), f.to(), groupBy, f);
        Map<String, Long> reinstated = queries.causeCounts(tenantId, REINSTATED, f.from(), f.to(), groupBy, f);

        List<ReportColumn> columns = List.of(col("dimension", groupBy.label(), TEXT),
            col("inForceStart", "In force at start", COUNT), col("inForceEnd", "In force at end", COUNT),
            col("averageInForce", "Average in force", TEXT), col("lapses", "Lapses", COUNT),
            col("reinstatements", "Reinstatements", COUNT), col("grossLapseRate", "Gross lapse rate %", PERCENT),
            col("netLapseRate", "Net lapse rate %", PERCENT));

        TreeSet<String> keys = new TreeSet<>(start.keySet());
        keys.addAll(end.keySet());
        keys.addAll(lapses.keySet());
        List<Map<String, String>> rows = new ArrayList<>();
        long[] sums = new long[4];
        for (String key : keys) {
            long s = start.getOrDefault(key, 0L), e = end.getOrDefault(key, 0L);
            long l = lapses.getOrDefault(key, 0L), r = reinstated.getOrDefault(key, 0L);
            rows.add(row(labels.dimension(groupBy, key), s, e, l, r));
            sums[0] += s; sums[1] += e; sums[2] += l; sums[3] += r;
        }
        rows.sort(Comparator.comparing(r -> r.get("dimension")));
        Map<String, String> totals = rows.isEmpty() ? Map.of() : row("Total", sums[0], sums[1], sums[2], sums[3]);
        return List.of(new ReportSection("Lapses " + f.from() + " to " + f.to(), columns, rows, totals));
    }

    private Map<String, Long> policiesByDimension(UUID tenantId, java.time.LocalDate asOf, GroupBy groupBy, ReportFilter f) {
        Map<String, Long> counts = new HashMap<>();
        queries.inForce(tenantId, asOf, groupBy, f).forEach(r -> counts.merge(r.dimension(), r.policies(), Long::sum));
        return counts;
    }

    private static Map<String, String> row(String label, long start, long end, long lapses, long reinstatements) {
        BigDecimal average = BigDecimal.valueOf(start + end).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        Map<String, String> row = new LinkedHashMap<>();
        row.put("dimension", label);
        row.put("inForceStart", Figures.count(start));
        row.put("inForceEnd", Figures.count(end));
        row.put("averageInForce", average.toPlainString());
        row.put("lapses", Figures.count(lapses));
        row.put("reinstatements", Figures.count(reinstatements));
        row.put("grossLapseRate", Figures.percent(BigDecimal.valueOf(lapses), average));
        row.put("netLapseRate", Figures.percent(BigDecimal.valueOf(lapses - reinstatements), average));
        return row;
    }
}
```

- [ ] **Step 5: Implement `PersistencyReport`**

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.AnnualisedPremium;
import tz.co.nlolo.lifeplatform.reporting.domain.CivilDates;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.domain.PersistencyMonths;
import tz.co.nlolo.lifeplatform.reporting.domain.PolicyStatus;
import tz.co.nlolo.lifeplatform.reporting.domain.StatusTimeline;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.CohortRow;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.PERCENT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * Of the policies activated in the cohort months, the share still in force at the start of month 13, 25, 37
 * and 61, by count and by annualised premium. Free-look cancellations, single premiums and investment
 * contracts are left out (SAMA's rule for the first; the others cannot lapse). Computed in Java over each
 * policy's StatusTimeline because the measurement date differs per policy.
 */
@Component
public class PersistencyReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("persistency", "Persistency",
        "Share of a cohort of new policies still in force at months 13, 25, 37 and 61, by count and by premium. "
            + "A month appears once every policy in the cohort has reached it.",
        ReportCategory.MANAGEMENT, DateFilterKind.COHORT, List.of(GroupBy.values()), false);

    private final ReportQueries queries;
    private final Clock clock;

    public PersistencyReport(ReportQueries queries, @Qualifier("reportingClock") Clock clock) {
        this.queries = queries;
        this.clock = clock;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    private record CohortPolicy(String dimension, LocalDate activatedOn, BigDecimal annualised,
                                List<StatusTimeline.Change> changes) {}

    private static final class Tally {
        long policies;
        BigDecimal premium = BigDecimal.ZERO;
        final Map<Integer, long[]> counts = new TreeMap<>();
        final Map<Integer, BigDecimal> premiums = new TreeMap<>();
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        GroupBy groupBy = f.groupByOr(GroupBy.PRODUCT);
        LocalDate today = CivilDates.today(clock);
        List<Integer> months = PersistencyMonths.MONTHS.stream()
            .filter(m -> PersistencyMonths.reached(f.cohortTo(), m, today)).toList();

        Map<String, CohortPolicy> policies = new LinkedHashMap<>();
        for (CohortRow r : queries.cohort(tenantId, f.cohortFrom(), f.cohortTo(), groupBy, f)) {
            policies.computeIfAbsent(r.policyNumber(), k -> new CohortPolicy(r.dimension(), r.activatedOn(),
                    AnnualisedPremium.of(r.premium(), r.frequency()).orElse(BigDecimal.ZERO), new ArrayList<>()))
                .changes().add(new StatusTimeline.Change(r.effectiveOn(), r.occurredAt(), r.eventId(), r.status()));
        }

        Map<String, Tally> byDimension = new TreeMap<>();
        Tally all = new Tally();
        for (CohortPolicy p : policies.values()) {
            StatusTimeline timeline = new StatusTimeline(p.changes());
            if (timeline.ever(PolicyStatus.CANCELLED_FREE_LOOK)) continue;
            for (Tally t : List.of(byDimension.computeIfAbsent(p.dimension(), k -> new Tally()), all)) {
                t.policies++;
                t.premium = t.premium.add(p.annualised());
                for (int m : months) {
                    boolean persists = timeline.inForceOn(PersistencyMonths.measuredOn(p.activatedOn(), m));
                    t.counts.computeIfAbsent(m, k -> new long[1])[0] += persists ? 1 : 0;
                    t.premiums.merge(m, persists ? p.annualised() : BigDecimal.ZERO, BigDecimal::add);
                }
            }
        }

        List<ReportColumn> columns = new ArrayList<>(List.of(col("dimension", groupBy.label(), TEXT),
            col("policies", "Policies in cohort", COUNT), col("annualisedPremium", "Annualised premium", MONEY)));
        for (int m : months) {
            columns.add(col("month" + m + "ByCount", "Month " + m + " by count %", PERCENT));
            columns.add(col("month" + m + "ByPremium", "Month " + m + " by premium %", PERCENT));
        }
        List<Map<String, String>> rows = new ArrayList<>();
        byDimension.forEach((dimension, tally) -> rows.add(row(labels.dimension(groupBy, dimension), tally, months)));
        rows.sort(Comparator.comparing(r -> r.get("dimension")));
        String title = "Activated " + f.cohortFrom() + " to " + f.cohortTo()
            + (months.isEmpty() ? " -- no persistency month reached yet" : "");
        return List.of(new ReportSection(title, columns, rows, rows.isEmpty() ? Map.of() : row("Total", all, months)));
    }

    private static Map<String, String> row(String label, Tally t, List<Integer> months) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("dimension", label);
        row.put("policies", Figures.count(t.policies));
        row.put("annualisedPremium", Figures.money(t.premium));
        for (int m : months) {
            row.put("month" + m + "ByCount", Figures.percent(BigDecimal.valueOf(t.counts.get(m)[0]), BigDecimal.valueOf(t.policies)));
            row.put("month" + m + "ByPremium", Figures.percent(t.premiums.get(m), t.premium));
        }
        return row;
    }
}
```

- [ ] **Step 6: Run to verify they pass**

Run: `./mvnw -B test -Dtest=ReportQueriesIntegrationTest`
Expected: PASS, Tests run: 10.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/reporting backend/src/test/java/tz/co/nlolo/lifeplatform/reporting
git commit -m "feat(reporting): lapse rate and persistency"
```

---

### Task 8: Premium collections and clients

**Files:**
- Modify: `reporting/infrastructure/ReportQueries.java` (add `collections`, `topPolicies`, `clientSummary`, `individuals`, `schemes`)
- Create: `reporting/application/PremiumCollectionsReport.java`, `reporting/application/ClientsReport.java`
- Modify test: `ReportQueriesIntegrationTest.java`

**Interfaces:**
- Produces: report keys `premium-collections`, `clients`; records `CollectionRow(String month, String dimension, String channel, String currency, long receipts, BigDecimal amount)`, `TopPolicyRow(String policyNumber, String currency, long receipts, BigDecimal amount)`, `ClientSummaryRow(String partyType, long total, long newInPeriod, long verified, long pending, long rejected, long withoutInForce)`, `IndividualRow(LocalDate dateOfBirth, String sex, long inForcePolicies)`, `SchemeRow(String policyNumber, String holder, String productCategory, String status, Integer lives, long joined, long exited, BigDecimal totalCovered, BigDecimal annualised, String currency)`.

- [ ] **Step 1: Add the failing tests**

```java
    // ---- premium collections -----------------------------------------------------------------------------

    @Test
    void collectionsAreTotalledByMonthAndChannelWithTheTopPolicies() {
        events.sell(tenant, Sale.of("K-1", product, d("2026-01-02")));
        events.sell(tenant, Sale.of("K-2", product, d("2026-01-02")));
        events.collect(tenant, "K-1", "10000.00", d("2026-01-05"), true);
        events.collect(tenant, "K-1", "10000.00", d("2026-01-25"), true);
        events.collect(tenant, "K-2", "4000.00", d("2026-01-26"), false);
        events.collect(tenant, "K-2", "10000.00", d("2026-02-05"), true);

        ReportResult result = run("premium-collections", ReportFilter.period(d("2026-01-01"), d("2026-02-28")));

        assertThat(rows(result, 0)).extracting(r -> r.get("month") + " " + r.get("channel") + " " + r.get("receipts")
                + " " + r.get("amount"))
            .containsExactly("2026-01 FIELD_CASH 1 4000.00", "2026-01 MOBILE_MONEY 2 20000.00", "2026-02 MOBILE_MONEY 1 10000.00");
        assertThat(result.sections().get(0).totals().get("amount")).isEqualTo("34000.00");
        assertThat(rows(result, 1)).extracting(r -> r.get("policyNumber")).containsExactly("K-1", "K-2");
    }

    // ---- clients -----------------------------------------------------------------------------------------

    @Test
    void clientsAreCountedByTypeAgeBandAndSchemeMembership() {
        UUID asha = UUID.randomUUID();
        UUID juma = UUID.randomUUID();
        UUID coop = UUID.randomUUID();
        events.client(tenant, asha, "INDIVIDUAL", "VERIFIED", "Asha", d("2026-01-05"), d("1990-04-01"), "FEMALE");
        events.client(tenant, juma, "INDIVIDUAL", "PENDING", "Juma", d("2026-02-10"), d("2000-01-01"), "MALE");
        events.client(tenant, coop, "CORPORATE", "VERIFIED", "Upendo Cooperative", d("2025-12-01"), null, null);
        events.sell(tenant, Sale.of("I-1", product, d("2026-01-06")).holder(asha));
        events.sell(tenant, Sale.of("GRP-1", product, d("2026-01-02")).holder(coop).scheme(10));
        events.members(tenant, "GRP-1", "JOINED", 12, "12000000.00", d("2026-02-01"));
        events.members(tenant, "GRP-1", "EXITED", 11, "11000000.00", d("2026-03-01"));

        ReportResult result = run("clients", ReportFilter.asOf(d("2026-03-31")).withPeriod(d("2026-02-01"), d("2026-03-31")));

        assertThat(rows(result, 0)).extracting(r -> r.get("partyType") + " total=" + r.get("total") + " new=" + r.get("newInPeriod")
                + " verified=" + r.get("kycVerified") + " pending=" + r.get("kycPending") + " none=" + r.get("withoutPolicyInForce"))
            .containsExactly("CORPORATE total=1 new=0 verified=1 pending=0 none=0",
                "INDIVIDUAL total=2 new=1 verified=1 pending=1 none=1");
        Map<String, String> band3544 = rows(result, 1).stream().filter(r -> r.get("ageBand").equals("35-44")).findFirst().orElseThrow();
        assertThat(band3544.get("female")).isEqualTo("1");
        Map<String, String> band2534 = rows(result, 1).stream().filter(r -> r.get("ageBand").equals("25-34")).findFirst().orElseThrow();
        assertThat(band2534.get("male")).isEqualTo("1");
        assertThat(rows(result, 2)).extracting(r -> r.get("policiesInForce") + "=" + r.get("clients"))
            .containsExactly("0=1", "1=1", "2=0", "3 or more=0");
        assertThat(rows(result, 3)).singleElement().satisfies(row -> {
            assertThat(row.get("policyNumber")).isEqualTo("GRP-1");
            assertThat(row.get("holder")).isEqualTo("Upendo Cooperative");
            assertThat(row.get("livesCovered")).isEqualTo("11");
            assertThat(row.get("joined")).isEqualTo("1");
            assertThat(row.get("exited")).isEqualTo("1");
            assertThat(row.get("sumAssured")).isEqualTo("11000000.00");
        });
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -B test -Dtest=ReportQueriesIntegrationTest`
Expected: 2 FAIL with `ReportNotFoundException`.

- [ ] **Step 3: Add the queries**

Add to `ReportQueries`:

```java
    public record CollectionRow(String month, String dimension, String channel, String currency, long receipts, BigDecimal amount) {}
    public record TopPolicyRow(String policyNumber, String currency, long receipts, BigDecimal amount) {}
    public record ClientSummaryRow(String partyType, long total, long newInPeriod, long verified, long pending,
                                   long rejected, long withoutInForce) {}
    public record IndividualRow(LocalDate dateOfBirth, String sex, long inForcePolicies) {}
    public record SchemeRow(String policyNumber, String holder, String productCategory, String status, Integer lives,
                            long joined, long exited, BigDecimal totalCovered, BigDecimal annualised, String currency) {}

    private static final String COLLECTION_FROM = " FROM reporting.report_collection r"
        + " LEFT JOIN reporting.report_policy p ON p.tenant_id = r.tenant_id AND p.policy_number = r.policy_number"
        + CLIENT_JOIN + " WHERE r.tenant_id = ? AND r.collected_on BETWEEN ? AND ?";

    public List<CollectionRow> collections(UUID tenantId, LocalDate from, LocalDate to, GroupBy groupBy, ReportFilter f) {
        List<Object> args = new ArrayList<>(List.of(tenantId, from, to));
        StringBuilder sql = new StringBuilder("SELECT to_char(r.collected_on, 'YYYY-MM') AS month, " + dimension(groupBy)
            + " AS dim, r.channel, r.currency, COUNT(*) AS receipts, SUM(r.amount) AS amount" + COLLECTION_FROM);
        appendFilters(sql, args, f);
        sql.append(" GROUP BY month, dim, r.channel, r.currency ORDER BY month, dim, r.channel");
        return jdbc.query(sql.toString(), (rs, i) -> new CollectionRow(rs.getString("month"), rs.getString("dim"),
            rs.getString("channel"), rs.getString("currency").trim(), rs.getLong("receipts"), rs.getBigDecimal("amount")),
            args.toArray());
    }

    public List<TopPolicyRow> topPolicies(UUID tenantId, LocalDate from, LocalDate to, ReportFilter f, int limit) {
        List<Object> args = new ArrayList<>(List.of(tenantId, from, to));
        StringBuilder sql = new StringBuilder("SELECT r.policy_number, r.currency, COUNT(*) AS receipts, SUM(r.amount) AS amount"
            + COLLECTION_FROM);
        appendFilters(sql, args, f);
        sql.append(" GROUP BY r.policy_number, r.currency ORDER BY amount DESC, r.policy_number LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> new TopPolicyRow(rs.getString("policy_number"),
            rs.getString("currency").trim(), rs.getLong("receipts"), rs.getBigDecimal("amount")), args.toArray());
    }

    /** Binds (tenant, asOf, tenant). Holders of a policy in force on the date. */
    private static final String HELD_CTE = ", held AS (SELECT DISTINCT p.holder_party_id FROM reporting.report_policy p"
        + " JOIN last_change l ON l.policy_number = p.policy_number"
        + " WHERE p.tenant_id = ? AND p.holder_party_id IS NOT NULL AND l.to_status IN " + IN_FORCE_STATUSES + ")";

    public List<ClientSummaryRow> clientSummary(UUID tenantId, LocalDate asOf, LocalDate from, LocalDate to, String clientType) {
        List<Object> args = new ArrayList<>(List.of(tenantId, asOf, tenantId, asOf, from, to, asOf, asOf, asOf, asOf, tenantId));
        StringBuilder sql = new StringBuilder("WITH " + LAST_CHANGE_CTE + HELD_CTE + " SELECT c.party_type,"
            + " COUNT(*) FILTER (WHERE c.registered_on <= ?) AS total,"
            + " COUNT(*) FILTER (WHERE c.registered_on BETWEEN ? AND ?) AS new_in_period,"
            + " COUNT(*) FILTER (WHERE c.registered_on <= ? AND c.kyc_status = 'VERIFIED') AS verified,"
            + " COUNT(*) FILTER (WHERE c.registered_on <= ? AND c.kyc_status = 'PENDING') AS pending,"
            + " COUNT(*) FILTER (WHERE c.registered_on <= ? AND c.kyc_status = 'REJECTED') AS rejected,"
            + " COUNT(*) FILTER (WHERE c.registered_on <= ? AND c.party_id NOT IN (SELECT holder_party_id FROM held)) AS without_in_force"
            + " FROM reporting.report_client c WHERE c.tenant_id = ?");
        if (clientType != null) { sql.append(" AND c.party_type = ?"); args.add(clientType); }
        sql.append(" GROUP BY c.party_type ORDER BY c.party_type");
        return jdbc.query(sql.toString(), (rs, i) -> new ClientSummaryRow(rs.getString("party_type"), rs.getLong("total"),
            rs.getLong("new_in_period"), rs.getLong("verified"), rs.getLong("pending"), rs.getLong("rejected"),
            rs.getLong("without_in_force")), args.toArray());
    }

    public List<IndividualRow> individuals(UUID tenantId, LocalDate asOf) {
        return jdbc.query("WITH " + LAST_CHANGE_CTE + ", in_force AS (SELECT p.holder_party_id, COUNT(*) AS n"
                + " FROM reporting.report_policy p JOIN last_change l ON l.policy_number = p.policy_number"
                + " WHERE p.tenant_id = ? AND l.to_status IN " + IN_FORCE_STATUSES + " GROUP BY p.holder_party_id)"
                + " SELECT c.date_of_birth, c.sex, COALESCE(i.n, 0) AS n FROM reporting.report_client c"
                + " LEFT JOIN in_force i ON i.holder_party_id = c.party_id"
                + " WHERE c.tenant_id = ? AND c.party_type = 'INDIVIDUAL' AND c.registered_on <= ?",
            (rs, i) -> new IndividualRow(rs.getObject("date_of_birth", LocalDate.class), rs.getString("sex"), rs.getLong("n")),
            tenantId, asOf, tenantId, tenantId, asOf);
    }

    public List<SchemeRow> schemes(UUID tenantId, LocalDate asOf, LocalDate from, LocalDate to, ReportFilter f) {
        List<Object> args = new ArrayList<>(List.of(tenantId, asOf, tenantId, asOf, tenantId, from, to, tenantId, asOf));
        StringBuilder sql = new StringBuilder("WITH " + LAST_CHANGE_CTE + ","
            + " lives AS (SELECT DISTINCT ON (x.policy_number) x.policy_number, x.lives_covered, x.scheme_total_covered"
            + "   FROM reporting.report_lives_change x WHERE x.tenant_id = ? AND x.effective_on <= ?"
            + "   ORDER BY x.policy_number, x.effective_on DESC, x.occurred_at DESC, x.event_id DESC),"
            + " moves AS (SELECT x.policy_number, COUNT(*) FILTER (WHERE x.kind = 'JOINED') AS joined,"
            + "   COUNT(*) FILTER (WHERE x.kind = 'EXITED') AS exited FROM reporting.report_lives_change x"
            + "   WHERE x.tenant_id = ? AND x.effective_on BETWEEN ? AND ? GROUP BY x.policy_number)"
            + " SELECT p.policy_number, c.display_name, p.product_category, l.to_status, lv.lives_covered,"
            + " COALESCE(m.joined, 0) AS joined, COALESCE(m.exited, 0) AS exited,"
            + " COALESCE(lv.scheme_total_covered, p.sum_assured) AS total_covered, " + ANNUALISED_IN_FORCE + " AS annualised,"
            + " p.currency FROM reporting.report_policy p"
            + " LEFT JOIN last_change l ON l.policy_number = p.policy_number"
            + " LEFT JOIN lives lv ON lv.policy_number = p.policy_number"
            + " LEFT JOIN moves m ON m.policy_number = p.policy_number" + CLIENT_JOIN
            + " WHERE p.tenant_id = ? AND p.group_scheme AND p.activated_on <= ?");
        appendFilters(sql, args, f);
        sql.append(" ORDER BY p.policy_number");
        return jdbc.query(sql.toString(), (rs, i) -> new SchemeRow(rs.getString("policy_number"), rs.getString("display_name"),
            rs.getString("product_category"), rs.getString("to_status"), (Integer) rs.getObject("lives_covered"),
            rs.getLong("joined"), rs.getLong("exited"), rs.getBigDecimal("total_covered"), rs.getBigDecimal("annualised"),
            rs.getString("currency").trim()), args.toArray());
    }
```

- [ ] **Step 4: Implement `PremiumCollectionsReport`**

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/** Channel is MOBILE_MONEY or FIELD_CASH only: PremiumCollected names no payment method, just whether a payer
 * number was present. Billed-versus-collected belongs to the Finance batch. */
@Component
public class PremiumCollectionsReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("premium-collections", "Premium collections",
        "Premiums received each month by channel (mobile money or cash field receipt), and the policies that paid most.",
        ReportCategory.MANAGEMENT, DateFilterKind.PERIOD, List.of(GroupBy.values()), false);

    private final ReportQueries queries;

    public PremiumCollectionsReport(ReportQueries queries) {
        this.queries = queries;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        GroupBy groupBy = f.groupByOr(GroupBy.PRODUCT);
        List<ReportColumn> byMonth = List.of(col("month", "Month", TEXT), col("dimension", groupBy.label(), TEXT),
            col("channel", "Channel", TEXT), col("currency", "Currency", TEXT), col("receipts", "Receipts", COUNT),
            col("amount", "Amount collected", MONEY));
        List<Map<String, String>> monthRows = queries.collections(tenantId, f.from(), f.to(), groupBy, f).stream().map(r -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("month", r.month());
            row.put("dimension", labels.dimension(groupBy, r.dimension()));
            row.put("channel", r.channel());
            row.put("currency", r.currency());
            row.put("receipts", Figures.count(r.receipts()));
            row.put("amount", Figures.money(r.amount()));
            return row;
        }).toList();

        List<ReportColumn> top = List.of(col("policyNumber", "Policy", TEXT), col("currency", "Currency", TEXT),
            col("receipts", "Receipts", COUNT), col("amount", "Amount collected", MONEY));
        List<Map<String, String>> topRows = queries.topPolicies(tenantId, f.from(), f.to(), f, 10).stream().map(r -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("policyNumber", r.policyNumber());
            row.put("currency", r.currency());
            row.put("receipts", Figures.count(r.receipts()));
            row.put("amount", Figures.money(r.amount()));
            return row;
        }).toList();

        return List.of(
            new ReportSection("Collected " + f.from() + " to " + f.to(), byMonth, monthRows, Sections.totals(byMonth, monthRows)),
            new ReportSection("Top 10 policies by amount collected", top, topRows, Map.of()));
    }
}
```

- [ ] **Step 5: Implement `ClientsReport`**

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.AgeBands;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.IndividualRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * Who the clients are. Individuals, corporates and groups are counted separately; a scheme's members are its
 * lives, not clients of their own. Members are counts over time (report_lives_change) because the member
 * events carry no member id.
 */
@Component
public class ClientsReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("clients", "Clients",
        "Clients by type and KYC status, individuals by age band and sex, and every group or corporate scheme with "
            + "its lives covered, joiners and leavers.",
        ReportCategory.MANAGEMENT, DateFilterKind.AS_OF_AND_PERIOD, List.of(), false);

    private final ReportQueries queries;

    public ClientsReport(ReportQueries queries) {
        this.queries = queries;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        List<ReportSection> sections = new ArrayList<>();

        List<ReportColumn> summary = List.of(col("partyType", "Client type", TEXT), col("total", "Clients", COUNT),
            col("newInPeriod", "New in period", COUNT), col("kycVerified", "KYC verified", COUNT),
            col("kycPending", "KYC pending", COUNT), col("kycRejected", "KYC rejected", COUNT),
            col("withoutPolicyInForce", "No policy in force", COUNT));
        List<Map<String, String>> summaryRows = queries.clientSummary(tenantId, f.asOf(), f.from(), f.to(), f.clientType())
            .stream().map(r -> {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("partyType", r.partyType());
                row.put("total", Figures.count(r.total()));
                row.put("newInPeriod", Figures.count(r.newInPeriod()));
                row.put("kycVerified", Figures.count(r.verified()));
                row.put("kycPending", Figures.count(r.pending()));
                row.put("kycRejected", Figures.count(r.rejected()));
                row.put("withoutPolicyInForce", Figures.count(r.withoutInForce()));
                return row;
            }).toList();
        sections.add(new ReportSection("Clients on " + f.asOf(), summary, summaryRows, Sections.totals(summary, summaryRows)));

        if (f.clientType() == null || "INDIVIDUAL".equals(f.clientType())) {
            List<IndividualRow> people = queries.individuals(tenantId, f.asOf());
            sections.add(ageAndSex(people, f));
            sections.add(policiesPerClient(people));
        }
        sections.add(schemes(tenantId, f));
        return sections;
    }

    private static ReportSection ageAndSex(List<IndividualRow> people, ReportFilter f) {
        Map<String, long[]> bands = new LinkedHashMap<>();
        AgeBands.ORDER.forEach(b -> bands.put(b, new long[3]));
        for (IndividualRow p : people) {
            long[] counts = bands.get(AgeBands.of(p.dateOfBirth(), f.asOf()));
            counts["FEMALE".equals(p.sex()) ? 0 : "MALE".equals(p.sex()) ? 1 : 2]++;
        }
        List<ReportColumn> columns = List.of(col("ageBand", "Age band", TEXT), col("female", "Female", COUNT),
            col("male", "Male", COUNT), col("unknown", "Not recorded", COUNT), col("total", "Total", COUNT));
        List<Map<String, String>> rows = new ArrayList<>();
        bands.forEach((band, c) -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("ageBand", band);
            row.put("female", Figures.count(c[0]));
            row.put("male", Figures.count(c[1]));
            row.put("unknown", Figures.count(c[2]));
            row.put("total", Figures.count(c[0] + c[1] + c[2]));
            rows.add(row);
        });
        return new ReportSection("Individuals by age band and sex, age on " + f.asOf(), columns, rows,
            Sections.totals(columns, rows));
    }

    private static ReportSection policiesPerClient(List<IndividualRow> people) {
        long[] buckets = new long[4];
        people.forEach(p -> buckets[(int) Math.min(p.inForcePolicies(), 3)]++);
        List<ReportColumn> columns = List.of(col("policiesInForce", "Policies in force", TEXT), col("clients", "Clients", COUNT));
        List<Map<String, String>> rows = new ArrayList<>();
        String[] labels = { "0", "1", "2", "3 or more" };
        for (int i = 0; i < 4; i++) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("policiesInForce", labels[i]);
            row.put("clients", Figures.count(buckets[i]));
            rows.add(row);
        }
        return new ReportSection("Individuals by number of policies in force", columns, rows, Map.of());
    }

    private ReportSection schemes(UUID tenantId, ReportFilter f) {
        List<ReportColumn> columns = List.of(col("policyNumber", "Scheme", TEXT), col("holder", "Holder", TEXT),
            col("productCategory", "Product category", TEXT), col("status", "Status", TEXT),
            col("livesCovered", "Lives covered", COUNT), col("joined", "Joined in period", COUNT),
            col("exited", "Left in period", COUNT), col("currency", "Currency", TEXT), col("sumAssured", "Total cover", MONEY),
            col("annualisedPremium", "Annualised premium", MONEY));
        List<Map<String, String>> rows = queries.schemes(tenantId, f.asOf(), f.from(), f.to(), f).stream().map(r -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("policyNumber", r.policyNumber());
            row.put("holder", r.holder() == null ? "" : r.holder());
            row.put("productCategory", r.productCategory() == null ? "" : r.productCategory());
            row.put("status", r.status() == null ? "NOT YET ACTIVE" : r.status());
            row.put("livesCovered", Figures.count(r.lives() == null ? 0 : r.lives()));
            row.put("joined", Figures.count(r.joined()));
            row.put("exited", Figures.count(r.exited()));
            row.put("currency", r.currency());
            row.put("sumAssured", Figures.money(r.totalCovered()));
            row.put("annualisedPremium", Figures.money(r.annualised()));
            return row;
        }).toList();
        return new ReportSection("Group and corporate schemes on " + f.asOf(), columns, rows, Sections.totals(columns, rows));
    }
}
```

- [ ] **Step 6: Run to verify they pass**

Run: `./mvnw -B test -Dtest=ReportQueriesIntegrationTest`
Expected: PASS, Tests run: 12. Then extend `theCatalogueListsReportsInBusinessOrder` to `containsExactly("policies-in-force", "new-business", "lapse-rate", "persistency", "premium-collections", "clients")` and re-run: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/reporting backend/src/test/java/tz/co/nlolo/lifeplatform/reporting
git commit -m "feat(reporting): premium collections and clients reports"
```

---

### Task 9: REST surface and OpenAPI contract

**Files:**
- Create: `backend/api/openapi/openapi-reporting.yaml`
- Create: `reporting/infrastructure/ReportFilterParams.java`, `ReportController.java`, `ReportingExceptionHandler.java`
- Test: `reporting/ReportingSpecParsesTest.java`, `reporting/ReportingContractTest.java`

**Interfaces:**
- Consumes: `ReportingApi`.
- Produces: `GET /reports/catalogue`, `GET /reports/{reportKey}`, `POST /reports/rebuild`, `GET /reports/rebuild/status`; `ReportFilterParams.parse(Map<String,String>) -> ReportFilter`; 400 `REPORT_PARAMETER_INVALID`, 404 `REPORT_NOT_FOUND`.

- [ ] **Step 1: Write the spec**

`backend/api/openapi/openapi-reporting.yaml`:

```yaml
openapi: 3.1.0
info:
  title: Reporting API
  version: "0.1.0-draft"
  description: >
    Operational and management reports (spec 2026-10-11-reporting-management-design.md). Every report returns
    one shape, ReportResult: sections of typed columns and rows whose values are all strings -- money and counts
    never travel as JSON numbers. Which date parameters a report takes is its dateFilterKind in the catalogue;
    a date parameter the report does not take is refused with 400, never ignored. Read access is
    FINANCE_OFFICER, FINANCE_APPROVER or ADMIN; rebuilding is ADMIN only.
servers:
  - url: https://api.nlolo-lifeplatform.tz/v1
paths:
  /reports/catalogue:
    get:
      summary: Every report this tenant can run, in business order
      security:
        - staffAuth: []
      responses:
        '200':
          description: The catalogue
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/ReportDefinition' }
        '401': { $ref: '#/components/responses/Problem' }
        '403': { $ref: '#/components/responses/Problem' }
  /reports/{reportKey}:
    get:
      summary: Run a report
      security:
        - staffAuth: []
      parameters:
        - { name: reportKey, in: path, required: true, schema: { type: string } }
        - { name: asOf, in: query, required: false, schema: { type: string, format: date } }
        - { name: compareAsOf, in: query, required: false, schema: { type: string, format: date } }
        - { name: from, in: query, required: false, schema: { type: string, format: date } }
        - { name: to, in: query, required: false, schema: { type: string, format: date } }
        - { name: cohortFrom, in: query, required: false, schema: { type: string, pattern: '^\d{4}-\d{2}$' } }
        - { name: cohortTo, in: query, required: false, schema: { type: string, pattern: '^\d{4}-\d{2}$' } }
        - { name: productId, in: query, required: false, schema: { type: string, format: uuid } }
        - { name: productCategory, in: query, required: false, schema: { type: string } }
        - { name: agentId, in: query, required: false, schema: { type: string, format: uuid } }
        - { name: salesChannel, in: query, required: false, schema: { type: string } }
        - { name: branchCode, in: query, required: false, schema: { type: string } }
        - { name: clientType, in: query, required: false, schema: { $ref: '#/components/schemas/ClientType' } }
        - { name: groupBy, in: query, required: false, schema: { $ref: '#/components/schemas/GroupBy' } }
      responses:
        '200':
          description: The report
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ReportResult' }
        '400': { $ref: '#/components/responses/Problem' }
        '401': { $ref: '#/components/responses/Problem' }
        '403': { $ref: '#/components/responses/Problem' }
        '404': { $ref: '#/components/responses/Problem' }
  /reports/rebuild:
    post:
      summary: Rebuild this tenant's reporting data from the audit journal (ADMIN)
      description: "Runs in the background. A second request while one runs returns the running one."
      security:
        - staffAuth: []
      responses:
        '202':
          description: Started, or already running
          content:
            application/json:
              schema: { $ref: '#/components/schemas/RebuildStatus' }
        '401': { $ref: '#/components/responses/Problem' }
        '403': { $ref: '#/components/responses/Problem' }
  /reports/rebuild/status:
    get:
      summary: The latest rebuild for this tenant
      security:
        - staffAuth: []
      responses:
        '200':
          description: Status
          content:
            application/json:
              schema: { $ref: '#/components/schemas/RebuildStatus' }
        '401': { $ref: '#/components/responses/Problem' }
        '403': { $ref: '#/components/responses/Problem' }
components:
  responses:
    Problem:
      description: An error, as RFC 7807 problem details
      content:
        application/problem+json:
          schema: { $ref: 'openapi-common.yaml#/components/schemas/ProblemDetails' }
  schemas:
    GroupBy:
      type: string
      enum: [PRODUCT, PRODUCT_CATEGORY, AGENT, CHANNEL, BRANCH]
    ClientType:
      type: string
      enum: [INDIVIDUAL, CORPORATE, GROUP]
    DateFilterKind:
      type: string
      enum: [AS_OF, PERIOD, COHORT, AS_OF_AND_PERIOD]
    ColumnType:
      type: string
      enum: [TEXT, COUNT, MONEY, PERCENT, DATE]
    ReportDefinition:
      type: object
      additionalProperties: false
      required: [key, title, description, category, dateFilterKind, groupByOptions, comparable]
      properties:
        key: { type: string }
        title: { type: string }
        description: { type: string }
        category: { type: string, enum: [MANAGEMENT] }
        dateFilterKind: { $ref: '#/components/schemas/DateFilterKind' }
        groupByOptions: { type: array, items: { $ref: '#/components/schemas/GroupBy' } }
        comparable: { type: boolean, description: "Accepts compareAsOf" }
    ReportColumn:
      type: object
      additionalProperties: false
      required: [key, label, type]
      properties:
        key: { type: string }
        label: { type: string }
        type: { $ref: '#/components/schemas/ColumnType' }
    ReportSection:
      type: object
      additionalProperties: false
      required: [title, columns, rows, totals]
      properties:
        title: { type: string }
        columns: { type: array, items: { $ref: '#/components/schemas/ReportColumn' } }
        rows:
          type: array
          items:
            type: object
            additionalProperties: { type: string }
        totals:
          type: object
          description: "Empty when the section has no total row"
          additionalProperties: { type: string }
    ReportResult:
      type: object
      additionalProperties: false
      required: [key, title, filterSummary, generatedAt, sections]
      properties:
        key: { type: string }
        title: { type: string }
        filterSummary: { type: string }
        generatedAt: { type: string, format: date-time }
        sections: { type: array, items: { $ref: '#/components/schemas/ReportSection' } }
    RebuildStatus:
      type: object
      additionalProperties: false
      required: [state, eventsReplayed, eventsFailed]
      properties:
        state: { type: string, enum: [IDLE, RUNNING, DONE, DONE_WITH_FAILURES, FAILED] }
        eventsReplayed: { type: integer, format: int64 }
        eventsFailed: { type: integer, format: int64 }
        startedAt: { type: [string, 'null'], format: date-time }
        finishedAt: { type: [string, 'null'], format: date-time }
```

- [ ] **Step 2: Write the parse test and the contract test**

`ReportingSpecParsesTest.java`:

```java
package tz.co.nlolo.lifeplatform.reporting;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportingSpecParsesTest {

    @Test
    void theReportingSpecParsesAndEveryRefResolves() {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation("api/openapi/openapi-reporting.yaml", null, options);
        assertThat(result.getMessages()).isEmpty();
        assertThat(result.getOpenAPI().getPaths()).containsKeys("/reports/catalogue", "/reports/{reportKey}",
            "/reports/rebuild", "/reports/rebuild/status");
    }
}
```

(If an import does not resolve, copy the import list from `regreporting/RegreportingSpecParsesTest.java`, which loads a spec the same way.)

`ReportingContractTest.java`:

```java
package tz.co.nlolo.lifeplatform.reporting;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reporting.ReportingTestEvents.Sale;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(ReportingTestEvents.class)
class ReportingContractTest {

    private static final String SPEC = "api/openapi/openapi-reporting.yaml";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            ReportingTestMigrations.ALL);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ReportingTestEvents events;
    private final UUID tenant = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static RequestPostProcessor staff(UUID tenant, String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_" + role))
            .jwt(b -> b.subject(role.toLowerCase()).claim("tenant_id", tenant.toString()));
    }

    @Test
    void theCatalogueIsReadableByEachFinanceRoleAndAdmin() throws Exception {
        for (String role : new String[] { "FINANCE_OFFICER", "FINANCE_APPROVER", "ADMIN" }) {
            mockMvc.perform(get("/reports/catalogue").with(staff(tenant, role)))
                .andExpect(status().isOk())
                .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
                .andExpect(jsonPath("$[0].key").value("policies-in-force"));
        }
    }

    @Test
    void anUnderwriterIsRefused() throws Exception {
        mockMvc.perform(get("/reports/catalogue").with(staff(tenant, "UNDERWRITER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/policies-in-force").param("asOf", "2026-01-31").with(staff(tenant, "UNDERWRITER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void aReportRunsAndMatchesTheContractWithStringFigures() throws Exception {
        events.sell(tenant, Sale.of("W-1", UUID.randomUUID(), LocalDate.parse("2026-01-05")));
        mockMvc.perform(get("/reports/policies-in-force").param("asOf", "2026-01-31").with(staff(tenant, "FINANCE_OFFICER")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.sections[0].rows[0].policies").value("1"))
            .andExpect(jsonPath("$.sections[0].rows[0].policies").isString())
            .andExpect(jsonPath("$.sections[0].rows[0].sumAssured").isString());
    }

    @Test
    void aMissingOrMismatchedDateIs400AndAnUnknownReport404() throws Exception {
        mockMvc.perform(get("/reports/policies-in-force").with(staff(tenant, "FINANCE_OFFICER")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("REPORT_PARAMETER_INVALID"));
        mockMvc.perform(get("/reports/policies-in-force").param("asOf", "31/01/2026").with(staff(tenant, "FINANCE_OFFICER")))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/reports/no-such-report").param("asOf", "2026-01-31").with(staff(tenant, "FINANCE_OFFICER")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("REPORT_NOT_FOUND"));
    }

    @Test
    void onlyAnAdminMayRebuild() throws Exception {
        mockMvc.perform(post("/reports/rebuild").with(staff(tenant, "FINANCE_OFFICER"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/reports/rebuild").with(staff(tenant, "ADMIN")))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));
        mockMvc.perform(get("/reports/rebuild/status").with(staff(tenant, "FINANCE_OFFICER")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));
    }
}
```

- [ ] **Step 3: Run to verify they fail**

Run: `./mvnw -B test -Dtest=ReportingSpecParsesTest,ReportingContractTest`
Expected: `ReportingSpecParsesTest` PASS; `ReportingContractTest` FAIL with 404 (no controller).

- [ ] **Step 4: Implement**

`ReportFilterParams.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Query string to ReportFilter. A value that does not parse is a 400 naming the parameter and the shape it wants. */
final class ReportFilterParams {
    private ReportFilterParams() {}

    static ReportFilter parse(Map<String, String> q) {
        return new ReportFilter(
            value(q, "asOf", LocalDate::parse, "a date, YYYY-MM-DD"),
            value(q, "compareAsOf", LocalDate::parse, "a date, YYYY-MM-DD"),
            value(q, "from", LocalDate::parse, "a date, YYYY-MM-DD"),
            value(q, "to", LocalDate::parse, "a date, YYYY-MM-DD"),
            value(q, "cohortFrom", YearMonth::parse, "a month, YYYY-MM"),
            value(q, "cohortTo", YearMonth::parse, "a month, YYYY-MM"),
            value(q, "productId", UUID::fromString, "a uuid"),
            blankToNull(q.get("productCategory")),
            value(q, "agentId", UUID::fromString, "a uuid"),
            blankToNull(q.get("salesChannel")),
            blankToNull(q.get("branchCode")),
            blankToNull(q.get("clientType")),
            value(q, "groupBy", GroupBy::valueOf, "one of PRODUCT, PRODUCT_CATEGORY, AGENT, CHANNEL, BRANCH"));
    }

    private static <T> T value(Map<String, String> q, String name, Function<String, T> parser, String shape) {
        String raw = blankToNull(q.get(name));
        if (raw == null) return null;
        try {
            return parser.apply(raw);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new ReportParameterException(name + " must be " + shape + "; got '" + raw + "'");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
```

`ReportController.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.reporting.api.RebuildStatusView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;

import java.util.List;
import java.util.Map;

/**
 * The /reports surface. FINANCE_APPROVER reads reports too (user decision, 2026-10-11): no reporting role
 * exists, and the people who approve journals are the people who read the management figures. Rebuilding
 * rewrites every row the tenant has, so it is ADMIN only.
 */
@RestController
public class ReportController {

    static final String READ = "hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('FINANCE_APPROVER')"
        + " or hasRole('ADMIN'))";

    private final ReportingApi reportingApi;

    public ReportController(ReportingApi reportingApi) {
        this.reportingApi = reportingApi;
    }

    @GetMapping("/reports/catalogue")
    @PreAuthorize(READ)
    public List<ReportDefinitionView> catalogue() {
        return reportingApi.catalogue();
    }

    @GetMapping("/reports/{reportKey}")
    @PreAuthorize(READ)
    public ReportResult run(@PathVariable String reportKey, @RequestParam Map<String, String> query) {
        return reportingApi.run(reportKey, ReportFilterParams.parse(query));
    }

    @PostMapping("/reports/rebuild")
    @PreAuthorize("hasRole('REALM_STAFF') and hasRole('ADMIN')")
    public ResponseEntity<RebuildStatusView> rebuild(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(reportingApi.startRebuild(jwt.getSubject()));
    }

    @GetMapping("/reports/rebuild/status")
    @PreAuthorize(READ)
    public RebuildStatusView rebuildStatus() {
        return reportingApi.rebuildStatus();
    }
}
```

`ReportingExceptionHandler.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tz.co.nlolo.lifeplatform.reporting.api.ReportNotFoundException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;

import java.util.UUID;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReportingExceptionHandler {

    @ExceptionHandler(ReportNotFoundException.class)
    public ProblemDetail notFound(ReportNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "REPORT_NOT_FOUND");
    }

    @ExceptionHandler(ReportParameterException.class)
    public ProblemDetail badParameter(ReportParameterException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "REPORT_PARAMETER_INVALID");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
```

- [ ] **Step 5: Run to verify they pass**

Run: `./mvnw -B test -Dtest=ReportingSpecParsesTest,ReportingContractTest,ModularityTests`
Expected: PASS, Tests run: 7. If `isValid(SPEC)` rejects `startedAt: null` on `RebuildStatus`, the `type: [string, 'null']` form is the swagger-request-validator 3.1 limitation `RegreportingContractTest`'s javadoc describes: make the controller omit nulls for that record (`@JsonInclude(JsonInclude.Include.NON_NULL)` on `RebuildStatusView`) and change both properties to `{ type: string, format: date-time }`; do not drop the `isValid` assertion.

- [ ] **Step 6: Commit**

```bash
git add backend/api/openapi/openapi-reporting.yaml backend/src/main/java/tz/co/nlolo/lifeplatform/reporting backend/src/test/java/tz/co/nlolo/lifeplatform/reporting
git commit -m "feat(reporting): REST surface and OpenAPI contract"
```

---

### Task 10: Excel, CSV and PDF export

**Files:**
- Create: `reporting/infrastructure/ReportExporter.java`, `ReportXlsx.java`, `ReportCsv.java`, `ReportPdf.java`
- Modify: `reporting/infrastructure/ReportController.java` (export endpoint), `backend/api/openapi/openapi-reporting.yaml` (export path)
- Test: `reporting/infrastructure/ReportExportTest.java`; modify `ReportingContractTest.java`

**Interfaces:**
- Produces: `ReportExporter.export(ReportResult, String format) -> ReportExporter.Export` with `record Export(byte[] bytes, String contentType, String fileName)`; `GET /reports/{reportKey}/export?format=xlsx|csv|pdf`.

- [ ] **Step 1: Write the failing unit test**

```java
package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.reporting.api.ColumnType;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportExportTest {

    private static ReportResult sample() {
        List<ReportColumn> cols = List.of(new ReportColumn("dimension", "Product", ColumnType.TEXT),
            new ReportColumn("policies", "Policies", ColumnType.COUNT),
            new ReportColumn("sumAssured", "Sum assured", ColumnType.MONEY));
        Map<String, String> row = new LinkedHashMap<>();
        row.put("dimension", "Family Funeral, Gold");
        row.put("policies", "12");
        row.put("sumAssured", "36000000.00");
        Map<String, String> totals = new LinkedHashMap<>(row);
        totals.put("dimension", "Total");
        return new ReportResult("policies-in-force", "Policies in force", "As of 2026-10-11 · Grouped by Product",
            Instant.parse("2026-10-11T08:00:00Z"), List.of(
                new ReportSection("Policies in force on 2026-10-11", cols, List.of(row), totals),
                new ReportSection("Second section", cols, List.of(), Map.of())));
    }

    private final ReportExporter exporter = new ReportExporter();

    @Test
    void xlsxHasAnAboutSheetAndOneSheetPerSectionWithNumericFigures() throws Exception {
        ReportExporter.Export export = exporter.export(sample(), "xlsx");
        assertThat(export.contentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(export.fileName()).isEqualTo("policies-in-force-2026-10-11.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(export.bytes()))) {
            assertThat(wb.getSheetName(0)).isEqualTo("About");
            assertThat(wb.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).contains("As of 2026-10-11");
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            var sheet = wb.getSheetAt(1);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Product");
            assertThat(sheet.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(36000000.00);
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Total");
        }
    }

    @Test
    void csvQuotesValuesAndSeparatesSections() {
        String csv = new String(exporter.export(sample(), "csv").bytes(), StandardCharsets.UTF_8);
        assertThat(csv).contains("Policies in force on 2026-10-11").contains("Product,Policies,Sum assured")
            .contains("\"Family Funeral, Gold\",12,36000000.00").contains("Total,12,36000000.00").contains("Second section");
    }

    @Test
    void pdfCarriesTheTitleSummaryAndFigures() throws Exception {
        ReportExporter.Export export = exporter.export(sample(), "pdf");
        assertThat(export.contentType()).isEqualTo("application/pdf");
        try (PDDocument doc = Loader.loadPDF(export.bytes())) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Policies in force").contains("As of 2026-10-11").contains("36000000.00");
        }
    }

    @Test
    void anUnknownFormatIsRefused() {
        assertThatThrownBy(() -> exporter.export(sample(), "docx")).isInstanceOf(ReportParameterException.class);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -B test -Dtest=ReportExportTest`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement**

`ReportExporter.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.domain.CivilDates;

/** Renders the same ReportResult the screen shows -- never a separate query -- so an export cannot disagree
 * with what was on screen. */
@Component
public class ReportExporter {

    public record Export(byte[] bytes, String contentType, String fileName) {}

    public Export export(ReportResult result, String format) {
        String base = result.key() + "-" + CivilDates.of(result.generatedAt());
        return switch (format == null ? "" : format) {
            case "xlsx" -> new Export(ReportXlsx.render(result),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", base + ".xlsx");
            case "csv" -> new Export(ReportCsv.render(result), "text/csv", base + ".csv");
            case "pdf" -> new Export(ReportPdf.render(result), "application/pdf", base + ".pdf");
            default -> throw new ReportParameterException("format must be xlsx, csv or pdf; got '" + format + "'");
        };
    }
}
```

`ReportXlsx.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tz.co.nlolo.lifeplatform.reporting.api.ColumnType;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An About sheet, then one sheet per section. Counts, money and percentages are written as numbers so a
 * reader can sum them; a blank figure (a rate with no denominator) stays blank. */
final class ReportXlsx {
    private ReportXlsx() {}

    static byte[] render(ReportResult result) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            CellStyle count = wb.createCellStyle();
            count.setDataFormat(wb.createDataFormat().getFormat("#,##0"));

            Sheet about = wb.createSheet("About");
            about.createRow(0).createCell(0).setCellValue(result.title());
            Row summary = about.createRow(1);
            summary.createCell(0).setCellValue("Filters");
            summary.createCell(1).setCellValue(result.filterSummary());
            Row generated = about.createRow(2);
            generated.createCell(0).setCellValue("Generated");
            generated.createCell(1).setCellValue(result.generatedAt().toString());

            Set<String> names = new HashSet<>(Set.of("About"));
            for (ReportSection section : result.sections()) {
                Sheet sheet = wb.createSheet(uniqueName(section.title(), names));
                Row header = sheet.createRow(0);
                for (int c = 0; c < section.columns().size(); c++) {
                    Cell cell = header.createCell(c);
                    cell.setCellValue(section.columns().get(c).label());
                    cell.setCellStyle(bold);
                }
                int r = 1;
                for (Map<String, String> values : section.rows()) {
                    write(sheet.createRow(r++), section.columns(), values, money, count, null);
                }
                if (!section.totals().isEmpty()) {
                    write(sheet.createRow(r), section.columns(), section.totals(), money, count, bold);
                }
                for (int c = 0; c < section.columns().size(); c++) sheet.autoSizeColumn(c);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Row row, List<ReportColumn> columns, Map<String, String> values, CellStyle money,
                              CellStyle count, CellStyle bold) {
        for (int c = 0; c < columns.size(); c++) {
            ReportColumn column = columns.get(c);
            String value = values.getOrDefault(column.key(), "");
            Cell cell = row.createCell(c);
            boolean numeric = column.type() == ColumnType.COUNT || column.type() == ColumnType.MONEY
                || column.type() == ColumnType.PERCENT;
            if (numeric && !value.isBlank() && value.matches("-?\\d+(\\.\\d+)?")) {
                cell.setCellValue(new BigDecimal(value).doubleValue());
                cell.setCellStyle(column.type() == ColumnType.COUNT ? count : money);
            } else {
                cell.setCellValue(value);
                if (bold != null) cell.setCellStyle(bold);
            }
        }
    }

    private static String uniqueName(String title, Set<String> taken) {
        String base = WorkbookUtil.createSafeSheetName(title.length() > 28 ? title.substring(0, 28) : title);
        String name = base;
        for (int i = 2; taken.contains(name); i++) name = base + " " + i;
        taken.add(name);
        return name;
    }
}
```

`ReportCsv.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** One file: the title and filters, then each section as its title, a header row, the rows, the total row
 * and a blank line. */
final class ReportCsv {
    private ReportCsv() {}

    static byte[] render(ReportResult result) {
        StringWriter out = new StringWriter();
        try (CSVPrinter csv = new CSVPrinter(out, CSVFormat.DEFAULT)) {
            csv.printRecord(result.title());
            csv.printRecord(result.filterSummary());
            csv.println();
            for (ReportSection section : result.sections()) {
                csv.printRecord(section.title());
                csv.printRecord(section.columns().stream().map(ReportColumn::label).toList());
                for (Map<String, String> row : section.rows()) {
                    csv.printRecord(section.columns().stream().map(c -> row.getOrDefault(c.key(), "")).toList());
                }
                if (!section.totals().isEmpty()) {
                    csv.printRecord(section.columns().stream().map(c -> section.totals().getOrDefault(c.key(), "")).toList());
                }
                csv.println();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
}
```

`ReportPdf.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/** Landscape A4, Standard-14 Helvetica (accumulation's StatementPdf does the same: no font file to ship).
 * Columns share the width equally; a value too long for its column is cut with "~". */
final class ReportPdf {
    private ReportPdf() {}

    private static final float MARGIN = 36f;
    private static final float LINE = 13f;
    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    static byte[] render(ReportResult result) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Writer w = new Writer(doc);
            w.text(BOLD, 14, MARGIN, result.title());
            w.newLine();
            w.text(REGULAR, 9, MARGIN, result.filterSummary() + "    Generated " + result.generatedAt());
            w.newLine();
            for (ReportSection section : result.sections()) {
                w.gap();
                w.text(BOLD, 11, MARGIN, section.title());
                w.newLine();
                float width = (w.pageWidth() - 2 * MARGIN) / Math.max(1, section.columns().size());
                w.cells(BOLD, section.columns().stream().map(c -> c.label()).toList(), width);
                for (Map<String, String> row : section.rows()) {
                    w.cells(REGULAR, section.columns().stream().map(c -> row.getOrDefault(c.key(), "")).toList(), width);
                }
                if (!section.totals().isEmpty()) {
                    w.cells(BOLD, section.columns().stream().map(c -> section.totals().getOrDefault(c.key(), "")).toList(), width);
                }
                if (section.rows().isEmpty()) {
                    w.text(REGULAR, 9, MARGIN, "No rows.");
                    w.newLine();
                }
            }
            w.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class Writer {
        private final PDDocument doc;
        private PDPageContentStream stream;
        private float y;
        private float pageWidth;

        Writer(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        float pageWidth() {
            return pageWidth;
        }

        private void newPage() throws IOException {
            if (stream != null) stream.close();
            PDRectangle landscape = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
            PDPage page = new PDPage(landscape);
            doc.addPage(page);
            stream = new PDPageContentStream(doc, page);
            pageWidth = landscape.getWidth();
            y = landscape.getHeight() - MARGIN;
        }

        void text(PDType1Font font, float size, float x, String s) throws IOException {
            if (y < MARGIN) newPage();
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(x, y);
            // Standard-14 fonts encode WinAnsi only; anything else is replaced rather than failing the export.
            stream.showText(s.replace('·', '-').replaceAll("[^\\x20-\\x7E]", "?"));
            stream.endText();
        }

        void cells(PDType1Font font, List<String> values, float width) throws IOException {
            if (y < MARGIN + LINE) newPage();
            int maxChars = Math.max(3, (int) (width / 4.6f));
            for (int i = 0; i < values.size(); i++) {
                String v = values.get(i);
                text(font, 8, MARGIN + i * width, v.length() > maxChars ? v.substring(0, maxChars - 1) + "~" : v);
            }
            newLine();
        }

        void newLine() {
            y -= LINE;
        }

        void gap() {
            y -= LINE / 2;
        }

        void close() throws IOException {
            stream.close();
        }
    }
}
```

Add to `ReportController` (imports `tz.co.nlolo.lifeplatform.FileDownloadResponses`, `ReportExporter`):

```java
    private final ReportExporter exporter;   // add to the constructor alongside reportingApi

    @GetMapping("/reports/{reportKey}/export")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> export(@PathVariable String reportKey, @RequestParam Map<String, String> query) {
        ReportExporter.Export export = exporter.export(reportingApi.run(reportKey, ReportFilterParams.parse(query)),
            query.get("format"));
        return FileDownloadResponses.fileResponse(export.bytes(), export.contentType(), export.fileName(), export.fileName());
    }
```

Add to `openapi-reporting.yaml` under `paths:` (after `/reports/{reportKey}`):

```yaml
  /reports/{reportKey}/export:
    get:
      summary: The same report as a file
      description: "Takes every parameter GET /reports/{reportKey} takes, plus format."
      security:
        - staffAuth: []
      parameters:
        - { name: reportKey, in: path, required: true, schema: { type: string } }
        - { name: format, in: query, required: true, schema: { type: string, enum: [xlsx, csv, pdf] } }
        - { name: asOf, in: query, required: false, schema: { type: string, format: date } }
        - { name: compareAsOf, in: query, required: false, schema: { type: string, format: date } }
        - { name: from, in: query, required: false, schema: { type: string, format: date } }
        - { name: to, in: query, required: false, schema: { type: string, format: date } }
        - { name: cohortFrom, in: query, required: false, schema: { type: string, pattern: '^\d{4}-\d{2}$' } }
        - { name: cohortTo, in: query, required: false, schema: { type: string, pattern: '^\d{4}-\d{2}$' } }
        - { name: productId, in: query, required: false, schema: { type: string, format: uuid } }
        - { name: productCategory, in: query, required: false, schema: { type: string } }
        - { name: agentId, in: query, required: false, schema: { type: string, format: uuid } }
        - { name: salesChannel, in: query, required: false, schema: { type: string } }
        - { name: branchCode, in: query, required: false, schema: { type: string } }
        - { name: clientType, in: query, required: false, schema: { $ref: '#/components/schemas/ClientType' } }
        - { name: groupBy, in: query, required: false, schema: { $ref: '#/components/schemas/GroupBy' } }
      responses:
        '200':
          description: The file, as an attachment
          content:
            application/vnd.openxmlformats-officedocument.spreadsheetml.sheet:
              schema: { type: string, format: binary }
            text/csv:
              schema: { type: string, format: binary }
            application/pdf:
              schema: { type: string, format: binary }
        '400': { $ref: '#/components/responses/Problem' }
        '401': { $ref: '#/components/responses/Problem' }
        '403': { $ref: '#/components/responses/Problem' }
        '404': { $ref: '#/components/responses/Problem' }
```

Add `"/reports/{reportKey}/export"` to the `containsKeys` in `ReportingSpecParsesTest`, and this test to `ReportingContractTest` (import `static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header`):

```java
    @Test
    void aReportDownloadsAsEachFormat() throws Exception {
        events.sell(tenant, Sale.of("X-1", UUID.randomUUID(), LocalDate.parse("2026-01-05")));
        for (String[] f : new String[][] { { "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" },
                { "csv", "text/csv" }, { "pdf", "application/pdf" } }) {
            mockMvc.perform(get("/reports/policies-in-force/export").param("asOf", "2026-01-31").param("format", f[0])
                    .with(staff(tenant, "FINANCE_APPROVER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith(f[1])))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("." + f[0])));
        }
        mockMvc.perform(get("/reports/policies-in-force/export").param("asOf", "2026-01-31").param("format", "docx")
                .with(staff(tenant, "FINANCE_APPROVER")))
            .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -B test -Dtest=ReportExportTest,ReportingSpecParsesTest,ReportingContractTest`
Expected: PASS, Tests run: 11.

- [ ] **Step 5: Commit**

```bash
git add backend/api/openapi/openapi-reporting.yaml backend/src/main/java/tz/co/nlolo/lifeplatform/reporting backend/src/test/java/tz/co/nlolo/lifeplatform/reporting
git commit -m "feat(reporting): export reports as xlsx, csv and pdf"
```

---

### Task 11: Console data layer and navigation

**Files (all under `frontend/`):**
- Generate: `src/types/api/reporting.ts` (`npm run generate:api`)
- Create: `src/api/reporting.ts`, `src/store/reportingStore.ts`, `src/features/reporting/reportParams.ts`, `src/features/reporting/reportParams.test.ts`
- Modify: `src/auth/claims.ts` (add `canSeeReports`), `src/lazyPages.ts`, `src/screens.tsx`, `src/screens.test.ts`
- Create (placeholders replaced in Task 12): `src/features/reporting/ReportsCataloguePage.tsx`, `src/features/reporting/ReportPage.tsx`

**Interfaces:**
- Consumes: backend `openapi-reporting.yaml` schemas `ReportDefinition`, `ReportResult`, `ReportSection`, `ReportColumn`, `RebuildStatus`.
- Produces:
  - `api/reporting.ts`: `getCatalogue()`, `runReport(key, params)`, `exportReport(key, params, format)`; types `ReportDefinition`, `ReportResult`, `ReportSection`, `ReportColumn`, `ReportParams = Record<string, string>`, `ExportFormat = 'xlsx' | 'csv' | 'pdf'`
  - `useReportingStore`: `catalogue: Resource<ReportDefinition[]>`, `result: Resource<ReportResult>`, `loadCatalogue()`, `runReport(key, params)`
  - `reportParams.ts`: `interface ReportFilterState`, `defaultFilters(definition, today)`, `stateFromSearch(definition, search, today)`, `toParams(definition, state)`, `validateFilters(definition, state) -> string | null`, `formatCell(type, value)`
  - `canSeeReports(identity)`; nav group id `reports`, label `Reports`; routes `reports` and `reports/:reportKey`

- [ ] **Step 1: Generate the types**

Run (from `frontend/`): `npm run generate:api`
Expected: `src/types/api/reporting.ts` created; `git status` shows only that new file under `src/types/api/` (the other modules' files unchanged). If others changed, the backend specs moved since the last generation -- keep them, they are the truth, and mention it in the commit message.

- [ ] **Step 2: Write the failing unit test**

`src/features/reporting/reportParams.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import type { ReportDefinition } from '@/api/reporting';
import { defaultFilters, formatCell, stateFromSearch, toParams, validateFilters } from './reportParams';

const definition = (dateFilterKind: ReportDefinition['dateFilterKind'], comparable = false): ReportDefinition => ({
  key: 'k',
  title: 'T',
  description: 'D',
  category: 'MANAGEMENT',
  dateFilterKind,
  groupByOptions: ['PRODUCT', 'AGENT'],
  comparable,
});

const TODAY = new Date(2026, 9, 11); // 11 October 2026, local time

describe('report filters', () => {
  it('defaults each date kind to a sensible window ending today', () => {
    expect(defaultFilters(definition('AS_OF'), TODAY).asOf).toBe('2026-10-11');
    expect(defaultFilters(definition('PERIOD'), TODAY)).toMatchObject({ from: '2026-10-01', to: '2026-10-11' });
    // Two years back, so months 13 and 25 have both been reached.
    expect(defaultFilters(definition('COHORT'), TODAY)).toMatchObject({ cohortFrom: '2024-09', cohortTo: '2024-09' });
    expect(defaultFilters(definition('AS_OF_AND_PERIOD'), TODAY)).toMatchObject({
      asOf: '2026-10-11',
      from: '2026-10-01',
      to: '2026-10-11',
    });
  });

  it('sends only the parameters the report takes', () => {
    const state = { ...defaultFilters(definition('PERIOD'), TODAY), asOf: '2026-01-01', groupBy: 'AGENT', clientType: '' };
    expect(toParams(definition('PERIOD'), state)).toEqual({ from: '2026-10-01', to: '2026-10-11', groupBy: 'AGENT' });
    const compared = { ...defaultFilters(definition('AS_OF', true), TODAY), compareAsOf: '2026-09-30' };
    expect(toParams(definition('AS_OF', true), compared)).toEqual({ asOf: '2026-10-11', compareAsOf: '2026-09-30' });
    expect(toParams(definition('AS_OF', false), compared)).toEqual({ asOf: '2026-10-11' });
  });

  it('reads filters back from the address bar, falling back to defaults', () => {
    const state = stateFromSearch(definition('PERIOD'), new URLSearchParams('from=2026-01-01&groupBy=AGENT'), TODAY);
    expect(state).toMatchObject({ from: '2026-01-01', to: '2026-10-11', groupBy: 'AGENT' });
  });

  it('refuses a missing date or a reversed range', () => {
    expect(validateFilters(definition('AS_OF'), { ...defaultFilters(definition('AS_OF'), TODAY), asOf: '' })).toMatch(/As of/);
    expect(
      validateFilters(definition('PERIOD'), { ...defaultFilters(definition('PERIOD'), TODAY), from: '2026-12-01' }),
    ).toMatch(/From/);
    expect(validateFilters(definition('PERIOD'), defaultFilters(definition('PERIOD'), TODAY))).toBeNull();
  });

  it('formats figures for reading without changing them', () => {
    expect(formatCell('MONEY', '1234567.5')).toBe('1,234,567.50');
    expect(formatCell('COUNT', '12345')).toBe('12,345');
    expect(formatCell('PERCENT', '66.67')).toBe('66.67%');
    expect(formatCell('PERCENT', '')).toBe('—');
    expect(formatCell('TEXT', 'Family Funeral')).toBe('Family Funeral');
    expect(formatCell('MONEY', 'not-a-number')).toBe('not-a-number');
  });
});
```

- [ ] **Step 3: Run to verify it fails**

Run (from `frontend/`): `npx vitest run src/features/reporting/reportParams.test.ts`
Expected: FAIL -- cannot resolve `./reportParams` / `@/api/reporting`.

- [ ] **Step 4: Implement the API module, params and store**

`src/api/reporting.ts`:

```ts
import { get } from '@/lib/http';
import type { components } from '@/types/api/reporting';

/**
 * Management reports (backend `reporting` module). Every report returns one shape -- sections of typed columns
 * whose values are all strings -- so one page renders all of them, and an export is the same report as a file.
 */

export type ReportDefinition = components['schemas']['ReportDefinition'];
export type ReportResult = components['schemas']['ReportResult'];
export type ReportSection = components['schemas']['ReportSection'];
export type ReportColumn = components['schemas']['ReportColumn'];
export type ReportParams = Record<string, string>;
export type ExportFormat = 'xlsx' | 'csv' | 'pdf';

const ACCEPT: Record<ExportFormat, string> = {
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  csv: 'text/csv',
  pdf: 'application/pdf',
};

export function getCatalogue(): Promise<ReportDefinition[]> {
  return get<ReportDefinition[]>('/reports/catalogue');
}

export function runReport(key: string, params: ReportParams): Promise<ReportResult> {
  return get<ReportResult>(`/reports/${encodeURIComponent(key)}`, { params });
}

export function exportReport(key: string, params: ReportParams, format: ExportFormat): Promise<Blob> {
  return get<Blob>(`/reports/${encodeURIComponent(key)}/export`, {
    params: { ...params, format },
    responseType: 'blob',
    headers: { Accept: ACCEPT[format] },
  });
}

export type RebuildStatus = components['schemas']['RebuildStatus'];

/** ADMIN only. Re-derives the tenant's reporting data from the event journal, in the background. */
export function startRebuild(): Promise<RebuildStatus> {
  return post<RebuildStatus>('/reports/rebuild', {});
}

export function getRebuildStatus(): Promise<RebuildStatus> {
  return get<RebuildStatus>('/reports/rebuild/status');
}
```

(Change the first import line to `import { get, post } from '@/lib/http';`.)

`src/features/reporting/reportParams.ts`:

```ts
import type { ReportColumn, ReportDefinition, ReportParams } from '@/api/reporting';
import { isValidAmount } from '@/lib/money';

/** The filter form's state: every field as the string an input holds. Empty means "not set". */
export interface ReportFilterState {
  asOf: string;
  compareAsOf: string;
  from: string;
  to: string;
  cohortFrom: string;
  cohortTo: string;
  groupBy: string;
  clientType: string;
}

const pad = (n: number) => String(n).padStart(2, '0');
/** Local calendar date, not toISOString(): that is UTC, which is yesterday for three hours every night in Dar. */
const isoDate = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const isoMonth = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}`;

export function defaultFilters(definition: ReportDefinition, today: Date): ReportFilterState {
  const monthStart = new Date(today.getFullYear(), today.getMonth(), 1);
  const cohort = new Date(today.getFullYear() - 2, today.getMonth() - 1, 1);
  const kind = definition.dateFilterKind;
  return {
    asOf: kind === 'AS_OF' || kind === 'AS_OF_AND_PERIOD' ? isoDate(today) : '',
    compareAsOf: '',
    from: kind === 'PERIOD' || kind === 'AS_OF_AND_PERIOD' ? isoDate(monthStart) : '',
    to: kind === 'PERIOD' || kind === 'AS_OF_AND_PERIOD' ? isoDate(today) : '',
    cohortFrom: kind === 'COHORT' ? isoMonth(cohort) : '',
    cohortTo: kind === 'COHORT' ? isoMonth(cohort) : '',
    groupBy: '',
    clientType: '',
  };
}

const FIELDS: (keyof ReportFilterState)[] = ['asOf', 'compareAsOf', 'from', 'to', 'cohortFrom', 'cohortTo', 'groupBy', 'clientType'];

export function stateFromSearch(definition: ReportDefinition, search: URLSearchParams, today: Date): ReportFilterState {
  const state = defaultFilters(definition, today);
  for (const field of FIELDS) {
    const value = search.get(field);
    if (value) state[field] = value;
  }
  return state;
}

/** The query string the backend expects: only the date fields this report's kind takes (the server refuses
 * a stray one), plus grouping and client type when chosen. */
export function toParams(definition: ReportDefinition, state: ReportFilterState): ReportParams {
  const kind = definition.dateFilterKind;
  const params: ReportParams = {};
  const put = (field: keyof ReportFilterState) => {
    if (state[field]) params[field] = state[field];
  };
  if (kind === 'AS_OF' || kind === 'AS_OF_AND_PERIOD') put('asOf');
  if (kind === 'AS_OF' && definition.comparable) put('compareAsOf');
  if (kind === 'PERIOD' || kind === 'AS_OF_AND_PERIOD') {
    put('from');
    put('to');
  }
  if (kind === 'COHORT') {
    put('cohortFrom');
    put('cohortTo');
  }
  if (definition.groupByOptions.length > 0) put('groupBy');
  put('clientType');
  return params;
}

export function validateFilters(definition: ReportDefinition, state: ReportFilterState): string | null {
  const kind = definition.dateFilterKind;
  if ((kind === 'AS_OF' || kind === 'AS_OF_AND_PERIOD') && !state.asOf) return 'Choose an As of date.';
  if (kind === 'PERIOD' || kind === 'AS_OF_AND_PERIOD') {
    if (!state.from || !state.to) return 'Choose both a From and a To date.';
    if (state.from > state.to) return 'From must not be after To.';
  }
  if (kind === 'COHORT') {
    if (!state.cohortFrom || !state.cohortTo) return 'Choose the first and last activation month.';
    if (state.cohortFrom > state.cohortTo) return 'The first month must not be after the last.';
  }
  return null;
}

const GROUPED_2DP = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: true });
const GROUPED_0DP = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0, useGrouping: true });

/** Display only: grouping and a % sign. A value that is not a number is shown as it came, so a backend
 * defect is visible rather than coerced into a plausible figure. */
export function formatCell(type: ReportColumn['type'], value: string | undefined): string {
  if (value === undefined || value === '') return '—';
  if (type === 'TEXT' || type === 'DATE') return value;
  if (!isValidAmount(value)) return value;
  if (type === 'MONEY') return GROUPED_2DP.format(value);
  if (type === 'COUNT') return GROUPED_0DP.format(value);
  return `${value}%`;
}
```

`src/store/reportingStore.ts`:

```ts
import { create } from 'zustand';
import { getCatalogue, runReport, type ReportDefinition, type ReportParams, type ReportResult } from '@/api/reporting';
import { idle, track, type Resource } from './createResourceSlice';

/** The `reporting` domain store. One result slot: a single report page is open at a time, and track()'s
 * anti-clobber guard (constant key) keeps a slow earlier run from overwriting a newer one. */
interface ReportingState {
  catalogue: Resource<ReportDefinition[]>;
  result: Resource<ReportResult>;
  loadCatalogue: () => Promise<void>;
  runReport: (key: string, params: ReportParams) => Promise<void>;
}

export const useReportingStore = create<ReportingState>((set, getState) => ({
  catalogue: idle(),
  result: idle(),

  loadCatalogue: () =>
    track('reporting.catalogue', getState().catalogue, (next) => set({ catalogue: next }), () => getCatalogue()),

  runReport: (key, params) =>
    track('reporting.result', getState().result, (next) => set({ result: next }), () => runReport(key, params)),
}));
```

- [ ] **Step 5: Run to verify it passes**

Run: `npx vitest run src/features/reporting/reportParams.test.ts`
Expected: PASS, 5 tests.

- [ ] **Step 6: Gate, nav group and routes**

In `src/auth/claims.ts`, after `canSeeFinance`:

```ts
/**
 * Management reports. Mirrors the reporting controller: FINANCE_OFFICER, FINANCE_APPROVER or ADMIN (user's
 * decision 2026-10-11 -- no reporting role exists, and the approvers read the management figures).
 */
export function canSeeReports(identity: TokenIdentity): boolean {
  const roles = staffRoles(identity);
  return roles.FINANCE_OFFICER || roles.FINANCE_APPROVER || roles.ADMIN;
}
```

In `src/screens.tsx`:
1. Add `| 'reports'` to `NavGroupId`.
2. Import `canSeeReports` beside `canSeeFinance`, and add `ChartColumn` to the lucide-react import list.
3. In the staff `NAV_GROUPS` array, insert after the `distribution` entry:
   ```tsx
    // Its own group, after the finance jobs and before Records: reports read across every area above, and
    // FINANCE_APPROVER can see it without seeing the finance groups (canSeeReports, not canSeeFinance).
    { id: 'reports', label: 'Reports', requires: canSeeReports },
   ```
4. Import `ReportsCataloguePage, ReportPage` from `@/lazyPages` and add to `STAFF_SCREENS` (after the regulatory-returns entries):
   ```tsx
  { path: 'reports', element: <ReportsCataloguePage />, reach: { group: 'reports', label: 'Reports', icon: ChartColumn } },
  { path: 'reports/:reportKey', element: <ReportPage />, reach: 'drill-in' },
   ```

In `src/lazyPages.ts`, beside `RegulatoryReturnsPage`:

```ts
export const ReportsCataloguePage = lazy(() =>
  import('@/features/reporting/ReportsCataloguePage').then((m) => ({ default: m.ReportsCataloguePage })),
);
export const ReportPage = lazy(() =>
  import('@/features/reporting/ReportPage').then((m) => ({ default: m.ReportPage })),
);
```

Create minimal placeholders so the build compiles (Task 12 replaces them):

`src/features/reporting/ReportsCataloguePage.tsx`:

```tsx
export function ReportsCataloguePage() {
  return null;
}
```

`src/features/reporting/ReportPage.tsx`:

```tsx
export function ReportPage() {
  return null;
}
```

- [ ] **Step 7: Update the nav tests**

In `src/screens.test.ts`:
- In `nav order follows the business flow`, insert `'Reports',` between `'Distribution',` and `'Records',` with the comment `// Reads across every area above; after the finance jobs, before Records.`
- In `hides every finance-gated group from an identity without the role`, add `'Reports'` to the `gated` list.
- Add:

```ts
  it('shows Reports, and only Reports among the finance groups, to a finance approver', () => {
    const approver = { roles: ['REALM_STAFF', 'FINANCE_APPROVER'] } as unknown as Parameters<typeof navFor>[1];
    const labels = navFor('staff', approver).map((g) => g.label);
    expect(labels).toContain('Reports');
    expect(labels).not.toContain('Ledger');
  });
```

- [ ] **Step 8: Run the frontend checks**

Run: `npx vitest run src/screens.test.ts src/features/reporting` then `npm run lint` then `npm run build`
Expected: all PASS; build has no type errors.

- [ ] **Step 9: Commit**

```bash
git add frontend/src
git commit -m "feat(console): reporting data layer and Reports navigation"
```

---

### Task 12: Report screens

**Files (under `frontend/src/features/reporting/`):**
- Replace: `ReportsCataloguePage.tsx`, `ReportPage.tsx`
- Create: `ReportFilterBar.tsx`, `ReportSectionTable.tsx`

**Interfaces:**
- Consumes: Task 11's store, api and `reportParams`; components `PageHeader`, `DataTable`/`Column`, `EmptyState`, `ErrorPanel`, `TableSkeleton` (`@/components/states`), `Button`, `Input`, `InlineError`; `saveBlob` (`@/lib/download`); `isInitialLoad` (`@/store/createResourceSlice`).
- Produces: headings and accessible names the e2e spec (Task 13) relies on -- page heading = report title; buttons `Run report`, `Excel`, `CSV`, `PDF`; inputs labelled `As of`, `Compare with`, `From`, `To`, `First activation month`, `Last activation month`, `Group by`, `Client type`; each section's table caption = section title.

Before writing, read `DESIGN.md` (repo root) sections "Tables", "Loading, Empty and Error States" and "Buttons", and match an existing list page (`features/regreporting/RegulatoryReturnsPage.tsx`) for spacing classes. Do not invent new tokens.

- [ ] **Step 1: `ReportSectionTable.tsx`**

```tsx
import type { ReportSection } from '@/api/reporting';
import { DataTable, type Column } from '@/components/DataTable';
import { cn } from '@/lib/utils';
import { formatCell } from './reportParams';

interface Line {
  index: number;
  total: boolean;
  cells: Record<string, string>;
}

/** One report section. Figures right-aligned so they compare down a column; the total row, when the section
 * has one, last and in bold. */
export function ReportSectionTable({ section }: { section: ReportSection }) {
  const lines: Line[] = section.rows.map((cells, index) => ({ index, total: false, cells }));
  if (Object.keys(section.totals).length > 0) {
    lines.push({ index: lines.length, total: true, cells: section.totals });
  }
  const columns: Column<Line>[] = section.columns.map((c) => ({
    key: c.key,
    header: c.label,
    align: c.type === 'TEXT' || c.type === 'DATE' ? 'left' : 'right',
    render: (line) => (
      <span className={cn(line.total && 'font-semibold')}>{formatCell(c.type, line.cells[c.key])}</span>
    ),
  }));
  return (
    <section className="space-y-2">
      <h2 className="text-sm font-semibold">{section.title}</h2>
      <div className="rounded-lg border border-border bg-surface">
        {lines.length === 0 ? (
          <p className="px-4 py-6 text-sm text-muted">No rows for these filters.</p>
        ) : (
          <DataTable columns={columns} rows={lines} rowKey={(l) => String(l.index)} caption={section.title} />
        )}
      </div>
    </section>
  );
}
```

If `@/lib/utils` does not export `cn`, use the import `DataTable.tsx` itself uses for `cn`.

- [ ] **Step 2: `ReportFilterBar.tsx`**

```tsx
import { useState, type FormEvent } from 'react';
import type { ReportDefinition } from '@/api/reporting';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { InlineError } from '@/components/InlineError';
import { validateFilters, type ReportFilterState } from './reportParams';

const GROUP_LABELS: Record<string, string> = {
  PRODUCT: 'Product',
  PRODUCT_CATEGORY: 'Product category',
  AGENT: 'Agent',
  CHANNEL: 'Sales channel',
  BRANCH: 'Branch',
};

/**
 * The filters a report takes, and nothing else: which dates show is the report's dateFilterKind. Keyed by
 * report at the call site, so moving to another report starts from that report's own defaults.
 */
export function ReportFilterBar({
  definition,
  initial,
  onRun,
}: {
  definition: ReportDefinition;
  initial: ReportFilterState;
  onRun: (state: ReportFilterState) => void;
}) {
  const [state, setState] = useState(initial);
  const [error, setError] = useState<string | null>(null);
  const kind = definition.dateFilterKind;
  const set = (field: keyof ReportFilterState) => (e: { target: { value: string } }) =>
    setState((s) => ({ ...s, [field]: e.target.value }));

  function submit(e: FormEvent) {
    e.preventDefault();
    const problem = validateFilters(definition, state);
    setError(problem);
    if (!problem) onRun(state);
  }

  const field = (id: keyof ReportFilterState, label: string, type: 'date' | 'month') => (
    <label className="flex flex-col gap-1 text-sm" htmlFor={`report-${id}`}>
      {label}
      <Input id={`report-${id}`} type={type} value={state[id]} onChange={set(id)} inputSize="sm" />
    </label>
  );

  return (
    <form onSubmit={submit} className="space-y-2 rounded-lg border border-border bg-surface p-3">
      <div className="flex flex-wrap items-end gap-3">
        {(kind === 'AS_OF' || kind === 'AS_OF_AND_PERIOD') && field('asOf', 'As of', 'date')}
        {kind === 'AS_OF' && definition.comparable && field('compareAsOf', 'Compare with', 'date')}
        {(kind === 'PERIOD' || kind === 'AS_OF_AND_PERIOD') && field('from', 'From', 'date')}
        {(kind === 'PERIOD' || kind === 'AS_OF_AND_PERIOD') && field('to', 'To', 'date')}
        {kind === 'COHORT' && field('cohortFrom', 'First activation month', 'month')}
        {kind === 'COHORT' && field('cohortTo', 'Last activation month', 'month')}
        {definition.groupByOptions.length > 0 && (
          <label className="flex flex-col gap-1 text-sm" htmlFor="report-groupBy">
            Group by
            <select id="report-groupBy" value={state.groupBy} onChange={set('groupBy')}
              className="h-8 rounded-md border border-border bg-surface px-2 text-sm">
              {definition.groupByOptions.map((g, i) => (
                <option key={g} value={i === 0 ? '' : g}>{GROUP_LABELS[g] ?? g}</option>
              ))}
            </select>
          </label>
        )}
        <label className="flex flex-col gap-1 text-sm" htmlFor="report-clientType">
          Client type
          <select id="report-clientType" value={state.clientType} onChange={set('clientType')}
            className="h-8 rounded-md border border-border bg-surface px-2 text-sm">
            <option value="">All clients</option>
            <option value="INDIVIDUAL">Individuals</option>
            <option value="CORPORATE">Corporates</option>
            <option value="GROUP">Groups</option>
          </select>
        </label>
        <Button type="submit" size="sm" variant="primary">Run report</Button>
      </div>
      {error && <InlineError>{error}</InlineError>}
    </form>
  );
}
```

The first group-by option maps to `''` because it is each report's default -- sending nothing keeps the URL clean. Check `Input`'s `inputSize` prop values and `InlineError`'s props in their source before relying on them; adjust to what they actually accept.

- [ ] **Step 3: `ReportPage.tsx`**

```tsx
import { useEffect, useMemo, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { exportReport, type ExportFormat } from '@/api/reporting';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { InlineError } from '@/components/InlineError';
import { saveBlob } from '@/lib/download';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useReportingStore } from '@/store/reportingStore';
import { ReportFilterBar } from './ReportFilterBar';
import { ReportSectionTable } from './ReportSectionTable';
import { stateFromSearch, toParams, validateFilters } from './reportParams';

/**
 * One page for every report. The filters live in the address bar, so a report can be bookmarked or sent to
 * a colleague exactly as it was run; the page runs the report whenever the address changes.
 */
export function ReportPage() {
  const { reportKey = '' } = useParams();
  const [search, setSearch] = useSearchParams();
  const catalogue = useReportingStore((s) => s.catalogue);
  const loadCatalogue = useReportingStore((s) => s.loadCatalogue);
  const result = useReportingStore((s) => s.result);
  const runReport = useReportingStore((s) => s.runReport);
  const [exportError, setExportError] = useState<string | null>(null);

  useEffect(() => {
    if (catalogue.data === null && catalogue.status !== 'loading') void loadCatalogue();
  }, [catalogue.data, catalogue.status, loadCatalogue]);

  const definition = catalogue.data?.find((d) => d.key === reportKey) ?? null;
  const today = useMemo(() => new Date(), []);
  const filters = definition ? stateFromSearch(definition, search, today) : null;
  const params = definition && filters ? toParams(definition, filters) : null;
  const paramsKey = params ? new URLSearchParams(params).toString() : '';

  useEffect(() => {
    if (!definition || !filters || validateFilters(definition, filters)) return;
    void runReport(reportKey, toParams(definition, filters));
    // paramsKey is the whole filter state as a string; depending on the objects would rerun every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reportKey, paramsKey, definition?.key]);

  async function download(format: ExportFormat) {
    if (!params) return;
    setExportError(null);
    try {
      const blob = await exportReport(reportKey, params, format);
      const stamp = new Date().toISOString().slice(0, 10);
      saveBlob(blob, `${reportKey}-${stamp}.${format}`);
    } catch {
      setExportError('The file could not be produced. Run the report again, then retry.');
    }
  }

  if (catalogue.status === 'error' && catalogue.data === null && catalogue.error) {
    return <ErrorPanel error={catalogue.error} onRetry={() => void loadCatalogue()} />;
  }
  if (!definition) {
    return catalogue.data === null ? (
      <TableSkeleton columns={4} />
    ) : (
      <EmptyState title="No such report" description={<Link to="../reports">Back to all reports</Link>} />
    );
  }

  const current = result.data && result.data.key === reportKey ? result.data : null;
  return (
    <>
      <PageHeader
        title={definition.title}
        description={current?.filterSummary ?? definition.description}
        actions={
          <div className="flex gap-2">
            {(['xlsx', 'csv', 'pdf'] as const).map((f) => (
              <Button key={f} size="sm" variant="secondary" onClick={() => void download(f)} disabled={!current}>
                {f === 'xlsx' ? 'Excel' : f.toUpperCase()}
              </Button>
            ))}
          </div>
        }
      />
      <div className="space-y-4 px-6 pb-6">
        <ReportFilterBar
          key={reportKey}
          definition={definition}
          initial={filters ?? stateFromSearch(definition, search, today)}
          onRun={(state) => setSearch(toParams(definition, state))}
        />
        {exportError && <InlineError>{exportError}</InlineError>}
        {isInitialLoad(result) || (result.status === 'loading' && !current) ? (
          <TableSkeleton columns={5} />
        ) : result.status === 'error' && result.error ? (
          <ErrorPanel error={result.error} onRetry={() => params && void runReport(reportKey, params)} />
        ) : (
          current?.sections.map((section) => <ReportSectionTable key={section.title} section={section} />)
        )}
      </div>
    </>
  );
}
```

Check `EmptyState`'s `description` prop type and `Button`'s `variant` values in their source; if `description` takes only a string, render the back link below the `EmptyState` instead.

- [ ] **Step 4: `ReportsCataloguePage.tsx`**

```tsx
import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import type { ReportDefinition } from '@/api/reporting';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useReportingStore } from '@/store/reportingStore';

const CATEGORY_LABELS: Record<ReportDefinition['category'], string> = { MANAGEMENT: 'Management' };

/** Every report this tenant can run, grouped by the department that reads it. Only Management exists today;
 * Accounting is the next batch. */
export function ReportsCataloguePage() {
  const catalogue = useReportingStore((s) => s.catalogue);
  const loadCatalogue = useReportingStore((s) => s.loadCatalogue);

  useEffect(() => {
    void loadCatalogue();
  }, [loadCatalogue]);

  function body() {
    if (isInitialLoad(catalogue)) return <TableSkeleton columns={2} />;
    if (catalogue.status === 'error' && catalogue.error && catalogue.data === null) {
      return <ErrorPanel error={catalogue.error} onRetry={() => void loadCatalogue()} />;
    }
    const reports = catalogue.data ?? [];
    if (reports.length === 0) return <EmptyState title="No reports" description="Nothing is available to run yet." />;
    const categories = [...new Set(reports.map((r) => r.category))];
    return categories.map((category) => (
      <section key={category} className="space-y-2">
        <h2 className="text-sm font-semibold">{CATEGORY_LABELS[category] ?? category}</h2>
        <ul className="divide-y divide-border rounded-lg border border-border bg-surface">
          {reports
            .filter((r) => r.category === category)
            .map((r) => (
              <li key={r.key} className="px-4 py-3">
                <Link to={r.key} className="font-medium hover:underline">
                  {r.title}
                </Link>
                <p className="text-sm text-muted">{r.description}</p>
              </li>
            ))}
        </ul>
      </section>
    ));
  }

  return (
    <>
      <PageHeader title="Reports" description="Management reports, on screen or as Excel, CSV or PDF." />
      <div className="space-y-4 px-6 pb-6">
        {body()}
        {isAdmin && <RebuildPanel />}
      </div>
    </>
  );
}

/**
 * ADMIN only, matching POST /reports/rebuild. Needed once after the module is first deployed (the read model
 * starts empty) and whenever the failure metric shows events were missed. Status is fetched on request --
 * no polling timer for a job an administrator runs a few times a year.
 */
function RebuildPanel() {
  const [status, setStatus] = useState<RebuildStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function act(action: () => Promise<RebuildStatus>) {
    setError(null);
    try {
      setStatus(await action());
    } catch {
      setError('The request failed. Try again in a moment.');
    }
  }

  return (
    <section className="space-y-2 rounded-lg border border-border bg-surface p-3">
      <h2 className="text-sm font-semibold">Reporting data</h2>
      <p className="text-sm text-muted">
        Rebuild clears this organisation's reporting data and replays every recorded event. Reports read
        partial figures until it finishes.
      </p>
      <div className="flex gap-2">
        <Button size="sm" variant="secondary" onClick={() => void act(startRebuild)}>Rebuild reporting data</Button>
        <Button size="sm" variant="secondary" onClick={() => void act(getRebuildStatus)}>Check status</Button>
      </div>
      {status && (
        <p className="text-sm" role="status">
          {status.state} -- {status.eventsReplayed} events replayed, {status.eventsFailed} failed
          {status.finishedAt ? `, finished ${status.finishedAt}` : ''}
        </p>
      )}
      {error && <InlineError>{error}</InlineError>}
    </section>
  );
}
```

Add to this file's imports: `useState` (from `react`), `useAuth` (from `react-oidc-context`), `readIdentity` (from `@/auth/claims`), `Button` (`@/components/ui/button`), `InlineError` (`@/components/InlineError`), and `getRebuildStatus, startRebuild, type RebuildStatus` (from `@/api/reporting`). Inside `ReportsCataloguePage`, before `useEffect`, add (the same pattern `features/communications/TemplatesPage.tsx` uses):

```tsx
  const auth = useAuth();
  const isAdmin = readIdentity(auth.user?.access_token).roles.includes('ADMIN');
```

- [ ] **Step 5: Run the frontend checks**

Run: `npx vitest run` then `npm run lint` then `npm run build`
Expected: all PASS. Fix any lint finding in the new files; do not add new `eslint-disable` lines beyond the one in `ReportPage`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/reporting
git commit -m "feat(console): report catalogue and report page"
```

---

### Task 13: Real stack and end-to-end

**Files:**
- Create: `frontend/e2e/staff-reports.spec.ts`

- [ ] **Step 1: Apply the migration to the dev database and restart the backend**

The dev backend and its database are not migrated automatically. From `backend/`:

```bash
docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 -q < db-migrations/reporting/V1__create_reporting_schema.sql
```

Stop the running backend by its port, not by `pkill` (that kills the Maven wrapper and leaves the JVM on 8080):
PowerShell: `Get-NetTCPConnection -LocalPort 8080 -State Listen | Select -Expand OwningProcess` then `Stop-Process -Id <pid> -Force`. Confirm the port is free, then start it in the background: `./mvnw -B -o spring-boot:run -Dspring-boot.run.profiles=local`. Wait for "Started Application"; probe `curl -s -o /dev/null -w "%{http_code}" localhost:8080/reports/catalogue` -> `401`.

- [ ] **Step 2: Fill the read model from history**

Sign in to the console as the dev ADMIN user, open `/staff/reports`, press **Rebuild reporting data**, then **Check status** until it reads `DONE`. `DONE_WITH_FAILURES` means some events failed to apply: read the backend log for `reporting failed to apply`, fix the cause, rebuild again. Then open `/staff/reports/policies-in-force` and check the count against the Policies screen filtered to ACTIVE (suspended and paid-up policies count as in force in the report, so add those too). A mismatch is a bug to diagnose now, not after merge.

- [ ] **Step 3: Write the e2e spec**

```ts
import { expect, test } from '@playwright/test';
import { expectNavItemsHidden, expectRouteDenied } from './guards';

/**
 * Reports (reporting module, batch 1). Assertions are on structure -- headings, the section table, a
 * download -- not on figures: the dev tenant's numbers change with every other spec's run.
 */
test.describe('staff reports', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('runs a report from the catalogue and downloads it', async ({ page }) => {
    await page.goto('/staff/reports');
    await expect(page.getByRole('heading', { name: 'Reports' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Management' })).toBeVisible();

    await page.getByRole('link', { name: 'Policies in force' }).click();
    // exact: the section heading "Policies in force on <date>" would otherwise match too.
    await expect(page.getByRole('heading', { name: 'Policies in force', exact: true })).toBeVisible();
    await page.getByLabel('As of').fill('2026-10-01');
    await page.getByRole('button', { name: 'Run report' }).click();

    await expect(page).toHaveURL(/asOf=2026-10-01/);
    await expect(page.getByRole('heading', { name: 'Policies in force on 2026-10-01' })).toBeVisible({ timeout: 15_000 });

    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Excel' }).click();
    expect((await download).suggestedFilename()).toMatch(/^policies-in-force-\d{4}-\d{2}-\d{2}\.xlsx$/);
  });

  test('a cohort report asks for months and a clients report for both a date and a period', async ({ page }) => {
    await page.goto('/staff/reports/persistency');
    await expect(page.getByLabel('First activation month')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByLabel('As of')).toHaveCount(0);

    await page.goto('/staff/reports/clients');
    await expect(page.getByLabel('As of')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByLabel('From')).toBeVisible();
    await expect(page.getByRole('heading', { name: /^Clients on / })).toBeVisible({ timeout: 15_000 });
  });

  test('a reversed period is refused before it reaches the server', async ({ page }) => {
    await page.goto('/staff/reports/new-business');
    await page.getByLabel('From').fill('2026-12-01');
    await page.getByLabel('To').fill('2026-01-01');
    await page.getByRole('button', { name: 'Run report' }).click();
    await expect(page.getByText('From must not be after To.')).toBeVisible();
  });
});

test.describe('staff reports -- role gating', () => {
  test('a staff.underwriter session cannot see or reach reports', async ({ page }) => {
    await page.goto('/staff/policies');
    await expectNavItemsHidden(page, 'Reports');
    await expectRouteDenied(page, '/staff/reports');
  });
});
```

- [ ] **Step 4: Run it, with its setup dependencies**

Run (from `frontend/`): `npx playwright test e2e/staff-reports.spec.ts --project=staff`
Expected: 4 passed. Run with dependencies (never `--no-deps`: stale saved sessions produce failures that look like regressions). If a test times out, re-run that one test alone before diagnosing.

- [ ] **Step 5: Commit**

```bash
git add frontend/e2e/staff-reports.spec.ts
git commit -m "test(e2e): reports catalogue, report page, export and role gating"
```

---

### Task 14: Whole-branch review and merge gate

- [ ] **Step 1: Full backend compile and the reporting classes together**

From `backend/`: `./mvnw -B clean test-compile`, then
`./mvnw -B test -Dtest='PayloadReaderTest,StatusTimelineTest,DomainArithmeticTest,ProjectionIntegrationTest,LiveProjectionIntegrationTest,RebuildIntegrationTest,ReportQueriesIntegrationTest,ReportingSpecParsesTest,ReportingContractTest,ReportExportTest,ReportingSqlStaysInSchemaTest,ModularityTests,MigrationScriptCoverageTest,TenantTableRlsCoverageIntegrationTest,AppRolePrivilegesIntegrationTest'`
Expected: every class reports "Tests run" > 0 and no failures. Compare the class count in the surefire summary with the list (15) -- a class that silently did not run is a pass that proves nothing.

- [ ] **Step 2: Neighbouring suites that share the event bus**

The new listener sees every event, so run the suites most likely to notice a slow or failing listener:
`./mvnw -B test -Dtest='StatusRecordIntegrationTest,ProjectionEndToEndTest,RegreportingContractTest,FinaccountingContractTest'`
Expected: PASS. (The full ~80-minute suite is the user's merge gate; ask before running it.)

- [ ] **Step 3: Review the whole diff against the spec**

`git diff main...HEAD --stat`, then read every file. Check in particular, because bugs live in the seams between tasks:
- every SQL query has `tenant_id = ?` (grep `reporting.report_` and read each statement);
- `ReportingEventHandlers.HANDLED` and its `switch` agree (the `default` throws if not);
- the column keys a report writes are the keys its `ReportColumn`s declare (a mismatch renders blank cells, silently);
- the OpenAPI spec's paths, parameters and schemas match the controller and records exactly;
- the frontend's input labels and button names match `staff-reports.spec.ts`.
Fix what you find, re-run the affected classes, commit each fix separately.

- [ ] **Step 4: Spec status and memory**

Set the spec's `Status:` line to `implemented (batch 1), <commit>`. Update `C:\Users\USER\.claude\projects\c--Users-USER-Desktop-digital-life-insurance-platform\memory\project_reporting_module.md` with the merge commit and anything surprising found on the way.

- [ ] **Step 5: Finish the branch**

Use superpowers:finishing-a-development-branch. The user's convention: merge locally with `--no-ff` into `main`, push `main`, and push the feature branch as a backup.
