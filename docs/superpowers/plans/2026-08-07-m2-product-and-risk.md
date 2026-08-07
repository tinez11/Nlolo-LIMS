# M2 — Product & Risk Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `product` module (product/version/rating-table/benefit-schedule/fund-definition configuration) and the `underwriting` module (application intake, risk assessment, and an accept/rate-up/decline/postpone decision), both with real JPA persistence, REST APIs contract-tested against their existing OpenAPI specs, and a `RulesEnginePort` abstraction for the underwriting decision.

**Architecture:** Two new Spring Modulith modules on top of M1's foundation. `product` depends only on `refdata`. `underwriting` depends on `party` (applicant data), `product` (rating tables), `document` (medical evidence), and `refdata` (contestability period) — all already-declared `allowedDependencies` from M0, unchanged by this plan. The underwriting decision is computed behind a `RulesEnginePort` interface (owned by `underwriting`, per `docs/01-domain-map.md`'s framing: "Underwriting owns the rule outcomes, not the engine itself") with a straightforward Java implementation now; real Drools/DRL integration is explicitly deferred until actual underwriting rules are formalized by Product/Actuarial — swapping the implementation behind the port later requires no API changes.

**Tech Stack:** Same as M1 — Spring Modulith, Spring Data JPA, Spring Security (multi-issuer JWT, already built), Postgres with Row-Level Security, `swagger-request-validator-mockmvc` for contract tests, Testcontainers. No new dependencies.

## Global Constraints

- Base package `tz.co.nlolo.lifeplatform` (unchanged). Module `allowedDependencies` are already correct from M0 and confirmed matching `docs/02-module-architecture.md`'s dependency table — **do not modify any `package-info.java` in this plan**: `product` → `{refdata}`, `underwriting` → `{party, product, document, refdata}`.
- **No local `java`/`mvn`** in this environment — every build/test step runs through Docker: `docker run --rm -v "$(pwd):/workspace" -w /workspace maven:3.9.9-eclipse-temurin-21 ./mvnw ...`.
- **Every Testcontainers-based test run needs both** `-v /var/run/docker.sock:/var/run/docker.sock` **and** `-e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` on the `docker run` command — confirmed the only working combination in this environment across all of M0/M1. Prefix with `MSYS_NO_PATHCONV=1` when running from Git Bash on Windows.
- **Tenant isolation is defense-in-depth, not optional, on EVERY tenant-scoped table — not just the aggregate root.** M1's final whole-branch review found and fixed a real gap where `audit.audit_log`/`audit.failed_event` had no Row-Level Security despite having a `tenant_id` column — only the aggregate root (`party.party`) had gotten a policy. **This plan's own migration files already have the identical gap, inherited from Phase 0 and never yet reviewed:** `product.rating_table`, `product.benefit_schedule`, and `product.fund_definition` all have a `tenant_id` column but no RLS policy; `underwriting.risk_assessment` and `underwriting.medical_disclosure` have the same gap. Every tenant-scoped table this plan touches must get `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` + a `USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)` policy, mirroring `party.party`'s existing pattern exactly — not just the two tables that already happen to have one.
- **`app_role` needs explicit schema/table grants on every new schema — this is not automatic.** M1's final whole-branch review found the single most severe bug in that milestone: the application's actual runtime database role (`app_role`, per `infra/docker-compose.yml`) had zero privileges on any of M1's schemas, because migrations are applied as the `postgres` superuser (`scripts/migrate.sh`), which becomes the owner of every object, and nothing grants `app_role` anything beyond schema `public`. **Confirmed: `db-migrations/product/V1__create_product_schema.sql` and `db-migrations/underwriting/V1__create_underwriting_schema.sql` currently have zero `GRANT` statements** — the exact same bug, about to ship in a second milestone unless fixed now. Every module task in this plan must end its migration file with the same pattern already established in `db-migrations/party/V1__create_party_schema.sql` (added during M1's final review — read it for the exact form):
  ```sql
  GRANT USAGE ON SCHEMA <schema> TO app_role;
  GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA <schema> TO app_role;
  ALTER DEFAULT PRIVILEGES IN SCHEMA <schema> GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;
  ```
- **`refdata`'s seeded parameters remain non-production placeholders.** `underwriting`'s contestability check reads `TZ_CONTESTABILITY_MONTHS` (already seeded in M1, value `24`, explicitly marked PLACEHOLDER pending Legal/Compliance sign-off per `db-migrations/refdata/V1__create_refdata_schema.sql`'s own comment) — no new placeholder needed, just consume the existing one via `ReferenceDataApi.getCodes("TZ_CONTESTABILITY_MONTHS")` (signature: `List<ReferenceCodeView> getCodes(String codeSetKey)`, already built in M1, do not modify `refdata`).
- **The underwriting decision algorithm is an explicit, flagged placeholder, not a guess baked in silently.** No Phase 0 doc specifies actual numeric accept/rate-up/decline/postpone thresholds anywhere — `docs/03-aggregate-design.md` only specifies the `UnderwritingCase` aggregate's *shape* (fields, state transitions), never rule *content*, and no `.drl` file or rules specification exists in the repository. Per the explicit decision made before this plan was written (port + simple Java logic now, real Drools deferred until actual business rules exist), Task 4's `SimpleRulesEngine` implementation is a genuinely reasoned but placeholder decision algorithm, and every method/class must carry a code comment saying so — mirroring `refdata`'s seed-data convention, not a silent guess.
- **The rules engine only uses data that genuinely exists today.** `party.Party` (M1) has no occupation/smoker-status fields, and `OpenCaseRequest.medicalDisclosure` is explicitly documented as free-form JSON ("schema varies by product — see Deliverable 6 for the concrete field set") — there is no structured field to parse occupation class or smoker status from yet. The rules engine therefore uses only: (a) the applicant's age (derivable from `Party.dateOfBirth`, resolved via `PartyApi.getParty`, already real) crossed against `product`'s `AGE` and `SUM_ASSURED_BAND` rating-table entries, and (b) submitted `RiskAssessment.riskScore` values (a real, already-structured field). `OCCUPATION_CLASS`/`SMOKER_STATUS` rating-table rows exist in the schema (for a future, richer intake form) but are not consulted by this milestone's decision logic — each defaults to a 1.0 multiplier contribution, flagged in code, not silently dropped.
- **Object-level authorization pattern, established in M1, applies identically here.** `underwriting`'s `GET /cases/{caseId}` has no per-caller object-level scoping in `openapi-underwriting.yaml` (unlike `party`'s customer-realm `party_id` claim check) — only realm-membership gating (`agentsAuth`/`staffAuth`). Fine-grained agent/agency-hierarchy scoping (e.g., "an agent may only see cases they personally submitted") is **out of scope for M2**, exactly as M1's Task 8 explicitly deferred the same class of scoping for `party` — no agent/policy data model exists yet to scope against. Flagged here as a deliberate, explicit scope cut, not a silent gap.
- **First fine-grained staff-role gate in the codebase.** Every M1 staff-only endpoint used `hasRole('REALM_STAFF')` (realm membership only) because `docs/04-api-contracts.md` §3 doesn't name specific role requirements for `party`'s endpoints. `openapi-underwriting.yaml`'s `POST /cases/{caseId}/assessments` and `POST /cases/{caseId}/referral` endpoints explicitly document themselves as "Staff-only (UNDERWRITER role)" — and `UNDERWRITER` is a real, already-seeded Keycloak staff-realm role (`keycloak/staff-realm.json`, confirmed present). This is the first case in the codebase where a specific staff role (not just realm membership) is both named by the spec and actually seeded — use `@PreAuthorize("hasRole('UNDERWRITER')")` for those two endpoints specifically. `product`'s staff-only endpoints (`POST /products`, `POST /products/{id}/versions`) have no specific role named anywhere — use `hasRole('REALM_STAFF')` only, consistent with M1's "never invent Keycloak role names doc04 doesn't give" rule.
- **RFC 7807 error handling reuses M1's `GlobalExceptionHandler` as-is.** M1's final review established the pattern: a root-package `GlobalExceptionHandler extends ResponseEntityExceptionHandler` (`@Order(Ordered.LOWEST_PRECEDENCE)`) handles all standard MVC exceptions and unmapped errors with `errorCode`/`traceId`; module-specific domain exceptions get their own `@RestControllerAdvice` at `@Order(Ordered.HIGHEST_PRECEDENCE)` so they're resolved before the catch-all (confirmed necessary — Spring resolves `@ExceptionHandler` by first-matching-*advice-bean*-wins, not merged-by-specificity-across-beans, the exact regression M1's final review caught and fixed). This plan's `ProductExceptionHandler` and `UnderwritingExceptionHandler` must each carry `@Order(Ordered.HIGHEST_PRECEDENCE)` from the start — do not repeat the bug.
- **Document evidence linkage reuses M1's `DocumentApi` exactly**, with one new `DocumentType` enum value added in Task 5: `document.api.DocumentType` currently has `{KYC_EVIDENCE, POLICY_DOCUMENT, CLAIM_EVIDENCE, SIGNED_FORM}` — none fit underwriting medical evidence. Add `UNDERWRITING_EVIDENCE`, routed to its own `underwriting-evidence` MinIO bucket (medical evidence is its own sensitivity class, kept separate from `party`'s `kyc-evidence` bucket) — this is the only change this plan makes to the `document` module, and it is additive only (no existing behavior changes).
- **Idempotency-Key header is accepted but not enforced.** `openapi-underwriting.yaml`'s `POST /underwriting/cases` references `openapi-common.yaml`'s `IdempotencyKey` parameter (`required: false`, no persistence/dedup mechanism specified anywhere in Phase 0 docs for this module — unlike M5's `payment` module, which the roadmap explicitly says gets "idempotency registries tested against replay scenarios"). Building a real dedup mechanism now would be speculative scope invented ahead of its documented milestone. This plan's controller accepts the header (so client code can start sending it) but does not act on it — flagged here as a deliberate scope cut for M2, real enforcement lands with `payment` in M5 if `underwriting` ever needs the same pattern.
- Dependency versions: no new dependencies are needed for this plan (no Drools/KIE library, per the port+simple-logic decision). If any assumption about an already-present dependency turns out wrong, note the substitution in the task report — same latitude as M0/M1.

---

### Task 1: `product` module — aggregates, persistence, application layer, defense-in-depth fixes

**Files:**
- Modify: `db-migrations/product/V1__create_product_schema.sql` (add missing RLS on `rating_table`/`benefit_schedule`/`fund_definition`, add `app_role` grants — see Global Constraints)
- Create: `src/main/java/tz/co/nlolo/lifeplatform/product/domain/{ProductDefinition,ProductVersion,RatingFactor,BenefitScheduleEntry,FundDefinition}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/product/infrastructure/{ProductDefinitionRepository,ProductVersionRepository,RatingFactorRepository,BenefitScheduleEntryRepository,FundDefinitionRepository}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/product/api/{ProductCategory,ProductStatus,IfrsMeasurementModel,FactorType,BenefitType,ProductSummaryView,ProductSnapshotView,ProductNotFoundException,InvalidProductVersionException,ProductApi}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/product/ProductApiIntegrationTest.java`

**Interfaces:**
- Consumes: `TenantContext.get()`/`getOrNull()` (M1), `MigrationTestSupport.applyMigration` (M1, test-only).
- Produces: `ProductApi` — Task 2 (REST controller) and Task 5 (`underwriting`'s rating-table lookups) both consume it. Exact signatures:
  ```java
  package tz.co.nlolo.lifeplatform.product.api;

  public interface ProductApi {
      ProductSummaryView createProduct(String productCode, String productName, ProductCategory category, String defaultCurrency, String createdBy);
      java.util.List<ProductSummaryView> listActiveProducts(ProductCategory categoryFilter);
      void publishVersion(java.util.UUID productId, IfrsMeasurementModel ifrsMeasurementModel, java.time.LocalDate effectiveDate, java.time.LocalDate retirementDate,
                           java.util.List<RatingFactorInput> ratingTable, java.util.List<BenefitInput> benefitSchedule, java.util.List<FundInput> fundDefinitions, String publishedBy);
      ProductSnapshotView getActiveSnapshot(java.util.UUID productId, java.time.LocalDate asOfDate);
      java.math.BigDecimal resolveRatingMultiplier(java.util.UUID productVersionId, FactorType factorType, String band);
  }
  ```
  `RatingFactorInput`, `BenefitInput`, `FundInput` are simple records defined alongside `ProductApi` (Step 2 below). `resolveRatingMultiplier` is the one method Task 4/5's rules engine calls — not part of `openapi-product.yaml` (internal-only, per Spring Modulith's in-process call convention already established for `underwriting → refdata` in M1).

- [ ] **Step 1: Fix the migration file's RLS and grants gaps**

Read the current file first (`db-migrations/product/V1__create_product_schema.sql`) to get exact current structure, then add, after the existing `CREATE POLICY product_version_tenant_isolation ...` block at the end of the file:

```sql
ALTER TABLE product.rating_table ENABLE ROW LEVEL SECURITY;
CREATE POLICY rating_table_tenant_isolation ON product.rating_table
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE product.benefit_schedule ENABLE ROW LEVEL SECURITY;
CREATE POLICY benefit_schedule_tenant_isolation ON product.benefit_schedule
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE product.fund_definition ENABLE ROW LEVEL SECURITY;
CREATE POLICY fund_definition_tenant_isolation ON product.fund_definition
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- app_role privileges -- migrations run as the postgres superuser (scripts/migrate.sh),
-- which becomes owner of every object created above; without these explicit grants
-- app_role (the application's runtime DB role) has no access to this schema at all
-- and every request against it fails with "permission denied for schema product"
-- (the exact bug M1's final whole-branch review found and fixed for party/document/
-- refdata/audit -- fixed here from the start instead of waiting for the same review
-- to catch it again).
GRANT USAGE ON SCHEMA product TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA product TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA product GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;
```

- [ ] **Step 2: Write the domain entities**

```java
package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_definition", schema = "product")
public class ProductDefinition {

    @Id
    @UuidGenerator
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_code", nullable = false)
    private String productCode;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String status = "DRAFT";

    @Column(name = "default_currency", nullable = false)
    private String defaultCurrency;

    @Column(name = "ifrs_measurement_model")
    private String ifrsMeasurementModel;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    protected ProductDefinition() {}

    public ProductDefinition(UUID tenantId, String productCode, String productName, String category, String defaultCurrency, String createdBy) {
        this.tenantId = tenantId;
        this.productCode = productCode;
        this.productName = productName;
        this.category = category;
        this.defaultCurrency = defaultCurrency;
        this.createdBy = createdBy;
    }

    public UUID getProductId() { return productId; }
    public UUID getTenantId() { return tenantId; }
    public String getProductCode() { return productCode; }
    public String getProductName() { return productName; }
    public String getCategory() { return category; }
    public String getStatus() { return status; }
    public String getDefaultCurrency() { return defaultCurrency; }
    public String getIfrsMeasurementModel() { return ifrsMeasurementModel; }

    public void activateWithMeasurementModel(String ifrsMeasurementModel) {
        // Deliverable 3 invariant: ifrsMeasurementModel mandatory before leaving DRAFT.
        // Enforced here (aggregate boundary) rather than only at the DB CHECK level so
        // the failure surfaces as a readable 422 at the API layer, not a raw constraint error.
        if (ifrsMeasurementModel == null || ifrsMeasurementModel.isBlank()) {
            throw new IllegalArgumentException("ifrsMeasurementModel is required before a product can become ACTIVE");
        }
        this.ifrsMeasurementModel = ifrsMeasurementModel;
        this.status = "ACTIVE";
    }
}
```

```java
package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "product_version", schema = "product")
public class ProductVersion {

    @Id
    @UuidGenerator
    @Column(name = "product_version_id")
    private UUID productVersionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "retirement_date")
    private LocalDate retirementDate;

    @Column(name = "is_active_for_new_business", nullable = false)
    private boolean activeForNewBusiness = true;

    @Column(name = "grace_period_days", nullable = false)
    private int gracePeriodDays;

    @Column(name = "max_loan_to_value_percent")
    private BigDecimal maxLoanToValuePercent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    protected ProductVersion() {}

    public ProductVersion(UUID tenantId, UUID productId, LocalDate effectiveDate, LocalDate retirementDate,
                           int gracePeriodDays, BigDecimal maxLoanToValuePercent, String createdBy) {
        this.tenantId = tenantId;
        this.productId = productId;
        this.effectiveDate = effectiveDate;
        this.retirementDate = retirementDate;
        this.gracePeriodDays = gracePeriodDays;
        this.maxLoanToValuePercent = maxLoanToValuePercent;
        this.createdBy = createdBy;
    }

    public UUID getProductVersionId() { return productVersionId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getProductId() { return productId; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public LocalDate getRetirementDate() { return retirementDate; }
    public boolean isActiveForNewBusiness() { return activeForNewBusiness; }
    public int getGracePeriodDays() { return gracePeriodDays; }
    public BigDecimal getMaxLoanToValuePercent() { return maxLoanToValuePercent; }
}
```

```java
package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "rating_table", schema = "product")
public class RatingFactor {

    @Id
    @UuidGenerator
    @Column(name = "rating_table_id")
    private UUID ratingTableId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_version_id", nullable = false)
    private UUID productVersionId;

    @Column(name = "factor_type", nullable = false)
    private String factorType;

    @Column(nullable = false)
    private String band;

    @Column(nullable = false)
    private BigDecimal multiplier;

    protected RatingFactor() {}

    public RatingFactor(UUID tenantId, UUID productVersionId, String factorType, String band, BigDecimal multiplier) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.factorType = factorType;
        this.band = band;
        this.multiplier = multiplier;
    }

    public UUID getProductVersionId() { return productVersionId; }
    public String getFactorType() { return factorType; }
    public String getBand() { return band; }
    public BigDecimal getMultiplier() { return multiplier; }
}
```

```java
package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Entity
@Table(name = "benefit_schedule", schema = "product")
public class BenefitScheduleEntry {

    @Id
    @UuidGenerator
    @Column(name = "benefit_schedule_id")
    private UUID benefitScheduleId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_version_id", nullable = false)
    private UUID productVersionId;

    @Column(name = "benefit_type", nullable = false)
    private String benefitType;

    @Column(name = "calculation_method", nullable = false)
    private String calculationMethod;

    protected BenefitScheduleEntry() {}

    public BenefitScheduleEntry(UUID tenantId, UUID productVersionId, String benefitType, String calculationMethod) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.benefitType = benefitType;
        this.calculationMethod = calculationMethod;
    }

    public UUID getProductVersionId() { return productVersionId; }
    public String getBenefitType() { return benefitType; }
    public String getCalculationMethod() { return calculationMethod; }
}
```

```java
package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "fund_definition", schema = "product")
public class FundDefinition {

    @Id
    @UuidGenerator
    @Column(name = "fund_definition_id")
    private UUID fundDefinitionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_version_id", nullable = false)
    private UUID productVersionId;

    @Column(name = "fund_code", nullable = false)
    private String fundCode;

    @Column(name = "current_nav", nullable = false)
    private BigDecimal currentNav;

    @Column(name = "nav_currency", nullable = false)
    private String navCurrency = "TZS";

    protected FundDefinition() {}

    public FundDefinition(UUID tenantId, UUID productVersionId, String fundCode, BigDecimal currentNav) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.fundCode = fundCode;
        this.currentNav = currentNav;
    }

    public UUID getProductVersionId() { return productVersionId; }
    public String getFundCode() { return fundCode; }
    public BigDecimal getCurrentNav() { return currentNav; }
}
```

- [ ] **Step 3: Write the repositories**

```java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.domain.ProductDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProductDefinitionRepository extends JpaRepository<ProductDefinition, UUID> {
    Optional<ProductDefinition> findByTenantIdAndProductCode(UUID tenantId, String productCode);
    java.util.List<ProductDefinition> findByTenantIdAndStatusAndCategory(UUID tenantId, String status, String category);
    java.util.List<ProductDefinition> findByTenantIdAndStatus(UUID tenantId, String status);
}
```

```java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.domain.ProductVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ProductVersionRepository extends JpaRepository<ProductVersion, UUID> {
    List<ProductVersion> findByTenantIdAndProductIdOrderByEffectiveDateDesc(UUID tenantId, UUID productId);

    @org.springframework.data.jpa.repository.Query(
        "SELECT v FROM ProductVersion v WHERE v.tenantId = :tenantId AND v.productId = :productId " +
        "AND v.effectiveDate <= :asOfDate AND (v.retirementDate IS NULL OR v.retirementDate > :asOfDate) " +
        "ORDER BY v.effectiveDate DESC")
    List<ProductVersion> findActiveAsOf(UUID tenantId, UUID productId, LocalDate asOfDate);
}
```

```java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.domain.RatingFactor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RatingFactorRepository extends JpaRepository<RatingFactor, UUID> {
    List<RatingFactor> findByProductVersionId(UUID productVersionId);
    List<RatingFactor> findByProductVersionIdAndFactorType(UUID productVersionId, String factorType);
}
```

```java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.domain.BenefitScheduleEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BenefitScheduleEntryRepository extends JpaRepository<BenefitScheduleEntry, UUID> {
    List<BenefitScheduleEntry> findByProductVersionId(UUID productVersionId);
}
```

```java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.domain.FundDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FundDefinitionRepository extends JpaRepository<FundDefinition, UUID> {
    List<FundDefinition> findByProductVersionId(UUID productVersionId);
}
```

- [ ] **Step 4: Write the public API types**

```java
package tz.co.nlolo.lifeplatform.product.api;

public enum ProductCategory { TERM_LIFE, ENDOWMENT, WHOLE_LIFE, ANNUITY, UNIT_LINKED, GROUP_LIFE, EDUCATION_SAVINGS }
```

```java
package tz.co.nlolo.lifeplatform.product.api;

public enum ProductStatus { DRAFT, ACTIVE, RETIRED }
```

```java
package tz.co.nlolo.lifeplatform.product.api;

public enum IfrsMeasurementModel { GMM, PAA }
```

```java
package tz.co.nlolo.lifeplatform.product.api;

public enum FactorType { AGE, OCCUPATION_CLASS, SMOKER_STATUS, SUM_ASSURED_BAND }
```

```java
package tz.co.nlolo.lifeplatform.product.api;

public enum BenefitType { DEATH, DISABILITY, CRITICAL_ILLNESS, MATURITY, SURRENDER }
```

```java
package tz.co.nlolo.lifeplatform.product.api;

import java.util.UUID;

public record ProductSummaryView(UUID productId, String productCode, String productName, ProductCategory category, ProductStatus status, String defaultCurrency) {}
```

```java
package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ProductSnapshotView(UUID productId, UUID productVersionId, LocalDate effectiveDate,
                                   IfrsMeasurementModel ifrsMeasurementModel, int gracePeriodDays, BigDecimal maxLoanToValuePercent) {}
```

```java
package tz.co.nlolo.lifeplatform.product.api;

import java.util.UUID;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(UUID productId) {
        super("No product found for id " + productId);
    }
}
```

```java
package tz.co.nlolo.lifeplatform.product.api;

public class InvalidProductVersionException extends RuntimeException {
    public InvalidProductVersionException(String message) {
        super(message);
    }
}
```

```java
package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ProductApi {

    record RatingFactorInput(FactorType factorType, String band, BigDecimal multiplier) {}
    record BenefitInput(BenefitType benefitType, String calculationMethod) {}
    record FundInput(String fundCode, BigDecimal currentNav) {}

    ProductSummaryView createProduct(String productCode, String productName, ProductCategory category, String defaultCurrency, String createdBy);

    List<ProductSummaryView> listActiveProducts(ProductCategory categoryFilter);

    void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                         List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions, String publishedBy);

    ProductSnapshotView getActiveSnapshot(UUID productId, LocalDate asOfDate);

    /**
     * Internal-only (not part of openapi-product.yaml), consumed by underwriting's
     * rules engine (Task 4/5). Returns 1.0 (neutral, no adjustment) if no rating-table
     * row matches the given band for that factor type on that product version --
     * callers must not treat a missing band as an error, since not every product
     * defines every factor type/band combination.
     */
    BigDecimal resolveRatingMultiplier(UUID productVersionId, FactorType factorType, String band);
}
```

- [ ] **Step 5: Write `ProductApiImpl.java`**

```java
package tz.co.nlolo.lifeplatform.product.application;

import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.*;
import tz.co.nlolo.lifeplatform.product.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProductApiImpl implements ProductApi {

    private final ProductDefinitionRepository productDefinitionRepository;
    private final ProductVersionRepository productVersionRepository;
    private final RatingFactorRepository ratingFactorRepository;
    private final BenefitScheduleEntryRepository benefitScheduleEntryRepository;
    private final FundDefinitionRepository fundDefinitionRepository;

    public ProductApiImpl(ProductDefinitionRepository productDefinitionRepository, ProductVersionRepository productVersionRepository,
                           RatingFactorRepository ratingFactorRepository, BenefitScheduleEntryRepository benefitScheduleEntryRepository,
                           FundDefinitionRepository fundDefinitionRepository) {
        this.productDefinitionRepository = productDefinitionRepository;
        this.productVersionRepository = productVersionRepository;
        this.ratingFactorRepository = ratingFactorRepository;
        this.benefitScheduleEntryRepository = benefitScheduleEntryRepository;
        this.fundDefinitionRepository = fundDefinitionRepository;
    }

    @Override
    @Transactional
    public ProductSummaryView createProduct(String productCode, String productName, ProductCategory category, String defaultCurrency, String createdBy) {
        UUID tenantId = TenantContext.get();
        ProductDefinition product = new ProductDefinition(tenantId, productCode, productName, category.name(), defaultCurrency, createdBy);
        productDefinitionRepository.save(product);
        return toSummaryView(product);
    }

    @Override
    public List<ProductSummaryView> listActiveProducts(ProductCategory categoryFilter) {
        UUID tenantId = TenantContext.get();
        List<ProductDefinition> products = categoryFilter != null
            ? productDefinitionRepository.findByTenantIdAndStatusAndCategory(tenantId, "ACTIVE", categoryFilter.name())
            : productDefinitionRepository.findByTenantIdAndStatus(tenantId, "ACTIVE");
        return products.stream().map(this::toSummaryView).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions, String publishedBy) {
        UUID tenantId = TenantContext.get();
        ProductDefinition product = productDefinitionRepository.findById(productId)
            .filter(p -> p.getTenantId().equals(tenantId))
            .orElseThrow(() -> new ProductNotFoundException(productId));

        // Deliverable 3 invariant: fund definitions restricted to UNIT_LINKED products.
        if (fundDefinitions != null && !fundDefinitions.isEmpty() && !"UNIT_LINKED".equals(product.getCategory())) {
            throw new InvalidProductVersionException("Fund definitions are only valid for UNIT_LINKED products");
        }
        // Deliverable 3 invariant: full rating-factor coverage validated at publish --
        // every declared FactorType must have at least one band defined so the rules
        // engine (Task 4) never silently falls back to a neutral 1.0 for a factor type
        // this product intended to rate on.
        java.util.Set<FactorType> coveredFactorTypes = ratingTable.stream().map(RatingFactorInput::factorType).collect(Collectors.toSet());
        if (!coveredFactorTypes.containsAll(List.of(FactorType.AGE, FactorType.SUM_ASSURED_BAND))) {
            throw new InvalidProductVersionException("Rating table must cover at least AGE and SUM_ASSURED_BAND factor types");
        }

        int gracePeriodDays = 30; // Deliverable 3 doesn't specify a grace-period source yet at this layer -- see Global Constraints; this is a fixed, flagged default, not read from an OpenAPI field (ProductVersionSpec has no gracePeriodDays field).
        ProductVersion version = new ProductVersion(tenantId, productId, effectiveDate, retirementDate, gracePeriodDays, null, publishedBy);
        productVersionRepository.save(version);

        for (RatingFactorInput input : ratingTable) {
            ratingFactorRepository.save(new RatingFactor(tenantId, version.getProductVersionId(), input.factorType().name(), input.band(), input.multiplier()));
        }
        for (BenefitInput input : benefitSchedule) {
            benefitScheduleEntryRepository.save(new BenefitScheduleEntry(tenantId, version.getProductVersionId(), input.benefitType().name(), input.calculationMethod()));
        }
        if (fundDefinitions != null) {
            for (FundInput input : fundDefinitions) {
                fundDefinitionRepository.save(new FundDefinition(tenantId, version.getProductVersionId(), input.fundCode(), input.currentNav()));
            }
        }

        product.activateWithMeasurementModel(ifrsMeasurementModel.name());
        productDefinitionRepository.save(product);
    }

    @Override
    public ProductSnapshotView getActiveSnapshot(UUID productId, LocalDate asOfDate) {
        UUID tenantId = TenantContext.get();
        LocalDate effectiveAsOf = asOfDate != null ? asOfDate : LocalDate.now();
        ProductVersion version = productVersionRepository.findActiveAsOf(tenantId, productId, effectiveAsOf).stream()
            .findFirst()
            .orElseThrow(() -> new ProductNotFoundException(productId));
        return new ProductSnapshotView(productId, version.getProductVersionId(), version.getEffectiveDate(),
            IfrsMeasurementModel.valueOf(productDefinitionRepository.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId)).getIfrsMeasurementModel()),
            version.getGracePeriodDays(), version.getMaxLoanToValuePercent());
    }

    @Override
    public BigDecimal resolveRatingMultiplier(UUID productVersionId, FactorType factorType, String band) {
        return ratingFactorRepository.findByProductVersionIdAndFactorType(productVersionId, factorType.name()).stream()
            .filter(f -> f.getBand().equals(band))
            .map(RatingFactor::getMultiplier)
            .findFirst()
            .orElse(BigDecimal.ONE); // No matching band -- neutral multiplier, not an error (see ProductApi.resolveRatingMultiplier's javadoc).
    }

    private ProductSummaryView toSummaryView(ProductDefinition p) {
        return new ProductSummaryView(p.getProductId(), p.getProductCode(), p.getProductName(),
            ProductCategory.valueOf(p.getCategory()), ProductStatus.valueOf(p.getStatus()), p.getDefaultCurrency());
    }
}
```

- [ ] **Step 6: Write `ProductApiIntegrationTest.java`**

```java
package tz.co.nlolo.lifeplatform.product;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.product.api.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes = Application.class)
class ProductApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/product/V1__create_product_schema.sql");
    }

    @BeforeEach
    void setTenant() { TenantContext.set(UUID.randomUUID()); }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Autowired
    private ProductApi productApi;

    @Test
    void createProductStartsInDraft() {
        ProductSummaryView product = productApi.createProduct("TERM-01", "Simple Term Life", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertEquals(ProductStatus.DRAFT, product.status());
        assertEquals("TERM-01", product.productCode());
    }

    @Test
    void draftProductIsExcludedFromActiveListing() {
        productApi.createProduct("TERM-02", "Another Term Life", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        List<ProductSummaryView> active = productApi.listActiveProducts(ProductCategory.TERM_LIFE);
        assertTrue(active.stream().noneMatch(p -> p.productCode().equals("TERM-02")));
    }

    @Test
    void publishVersionActivatesProductAndAppearsInListing() {
        ProductSummaryView product = productApi.createProduct("TERM-03", "Published Term Life", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");

        List<ProductSummaryView> active = productApi.listActiveProducts(ProductCategory.TERM_LIFE);
        assertTrue(active.stream().anyMatch(p -> p.productCode().equals("TERM-03") && p.status() == ProductStatus.ACTIVE));
    }

    @Test
    void publishVersionRejectsFundDefinitionsOnNonUnitLinkedProduct() {
        ProductSummaryView product = productApi.createProduct("TERM-04", "Term with bad fund", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                List.of(new ProductApi.FundInput("FUND-A", BigDecimal.TEN)),
                "actuary@nlolo.co.tz"));
    }

    @Test
    void publishVersionRejectsIncompleteRatingFactorCoverage() {
        ProductSummaryView product = productApi.createProduct("TERM-05", "Term missing coverage", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE)), // missing SUM_ASSURED_BAND
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null, "actuary@nlolo.co.tz"));
    }

    @Test
    void getActiveSnapshotReturnsPublishedVersion() {
        ProductSummaryView product = productApi.createProduct("TERM-06", "Snapshot test", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.GMM, LocalDate.now().minusDays(1), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");

        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        assertEquals(IfrsMeasurementModel.GMM, snapshot.ifrsMeasurementModel());
    }

    @Test
    void resolveRatingMultiplierReturnsNeutralWhenBandNotFound() {
        ProductSummaryView product = productApi.createProduct("TERM-07", "Multiplier test", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.GMM, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", new BigDecimal("1.5")),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());

        assertEquals(0, new BigDecimal("1.5").compareTo(productApi.resolveRatingMultiplier(snapshot.productVersionId(), FactorType.AGE, "30-39")));
        assertEquals(0, BigDecimal.ONE.compareTo(productApi.resolveRatingMultiplier(snapshot.productVersionId(), FactorType.AGE, "NO-SUCH-BAND")));
    }

    @Test
    void productsAreTenantIsolated() {
        productApi.createProduct("SHARED-CODE", "Tenant A product", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        TenantContext.clear();
        TenantContext.set(UUID.randomUUID());
        List<ProductSummaryView> tenantBProducts = productApi.listActiveProducts(null);
        assertTrue(tenantBProducts.stream().noneMatch(p -> p.productCode().equals("SHARED-CODE")));
    }
}
```

- [ ] **Step 7: Compile and run**

```bash
docker run --rm -v "$(pwd):/workspace" -w /workspace maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q compile
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q test -Dtest=ProductApiIntegrationTest
```

Expected: `BUILD SUCCESS`, all 8 tests pass.

- [ ] **Step 8: Commit**

```bash
git add db-migrations/product src/main/java/tz/co/nlolo/lifeplatform/product src/test/java/tz/co/nlolo/lifeplatform/product
git commit -m "feat: implement product module aggregates, persistence, and internal API"
```

---

### Task 2: `product` REST layer

**Files:**
- Create: `src/main/java/tz/co/nlolo/lifeplatform/product/infrastructure/{ProductController,CreateProductRequest,RatingFactorRequest,BenefitRequest,FundDefinitionRequest,PublishVersionRequest,ProductExceptionHandler}.java`

**Interfaces:**
- Consumes: `ProductApi` (Task 1), `ROLE_REALM_*` authorities (M1's `SecurityConfig`).
- Produces: the 3 HTTP endpoints `api/openapi/openapi-product.yaml` declares — Task 3's contract tests call these directly.

- [ ] **Step 1: Write the request DTOs**

```java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.ProductCategory;

public record CreateProductRequest(String productCode, String productName, ProductCategory category, String defaultCurrency) {}
```

```java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.FactorType;
import java.math.BigDecimal;

public record RatingFactorRequest(FactorType factorType, String band, BigDecimal multiplier) {}
```

```java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.BenefitType;

public record BenefitRequest(BenefitType benefitType, String calculationMethod) {}
```

```java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import java.math.BigDecimal;

public record FundDefinitionRequest(String fundCode, BigDecimal currentNav) {}
```

```java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;

import java.time.LocalDate;
import java.util.List;

public record PublishVersionRequest(IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                     List<RatingFactorRequest> ratingTable, List<BenefitRequest> benefitSchedule,
                                     List<FundDefinitionRequest> fundDefinitions) {}
```

- [ ] **Step 2: Write `ProductController.java`**

```java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
public class ProductController {

    private final ProductApi productApi;

    public ProductController(ProductApi productApi) {
        this.productApi = productApi;
    }

    @GetMapping("/products")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<List<ProductSummaryView>> listProducts(@RequestParam(required = false) ProductCategory category) {
        return ResponseEntity.ok(productApi.listActiveProducts(category));
    }

    @PostMapping("/products")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<ProductSummaryView> createProduct(@RequestBody CreateProductRequest request) {
        ProductSummaryView view = productApi.createProduct(request.productCode(), request.productName(), request.category(), request.defaultCurrency(), "staff");
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @PostMapping("/products/{productId}/versions")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<Void> publishVersion(@PathVariable UUID productId, @RequestBody PublishVersionRequest request) {
        productApi.publishVersion(productId, request.ifrsMeasurementModel(), request.effectiveDate(), request.retirementDate(),
            request.ratingTable().stream().map(r -> new ProductApi.RatingFactorInput(r.factorType(), r.band(), r.multiplier())).collect(Collectors.toList()),
            request.benefitSchedule().stream().map(b -> new ProductApi.BenefitInput(b.benefitType(), b.calculationMethod())).collect(Collectors.toList()),
            request.fundDefinitions() != null
                ? request.fundDefinitions().stream().map(f -> new ProductApi.FundInput(f.fundCode(), f.currentNav())).collect(Collectors.toList())
                : null,
            "staff");
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/products/{productId}/active-snapshot")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<ProductSnapshotView> getActiveSnapshot(@PathVariable UUID productId, @RequestParam(required = false) LocalDate effectiveDate) {
        return ResponseEntity.ok(productApi.getActiveSnapshot(productId, effectiveDate));
    }
}
```

- [ ] **Step 3: Write `ProductExceptionHandler.java`**

```java
package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.InvalidProductVersionException;
import tz.co.nlolo.lifeplatform.product.api.ProductNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * @Order(HIGHEST_PRECEDENCE) is required -- Spring resolves @ExceptionHandler methods
 * by first-matching-ADVICE-BEAN-wins, not merged-by-specificity across beans. Without
 * this, GlobalExceptionHandler's catch-all could shadow these domain-specific mappings
 * depending on classpath-scan order (the exact regression M1's final review found and
 * fixed for PartyExceptionHandler -- do not repeat it here).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProductExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ProblemDetail handleNotFound(ProductNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "PRODUCT_NOT_FOUND");
    }

    @ExceptionHandler(InvalidProductVersionException.class)
    public ProblemDetail handleInvalidVersion(InvalidProductVersionException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "INVALID_PRODUCT_VERSION");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
```

- [ ] **Step 4: Compile and run the existing product tests (no new tests in this task — Task 3 covers the REST layer)**

```bash
docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q test -Dtest=ProductApiIntegrationTest
```

Expected: `BUILD SUCCESS` — this task only adds files, doesn't change tested behavior yet.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/product/infrastructure
git commit -m "feat: add product REST controller implementing openapi-product.yaml"
```

---

### Task 3: `product` contract tests

**Files:**
- Test: `src/test/java/tz/co/nlolo/lifeplatform/product/ProductContractTest.java`

**Interfaces:**
- Consumes: `ProductController` (Task 2), `api/openapi/openapi-product.yaml`.

This directly satisfies M2's second acceptance criterion: "product configuration API contract-validated."

- [ ] **Step 1: Write `ProductContractTest.java`**

```java
package tz.co.nlolo.lifeplatform.product;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ProductContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-product.yaml";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/product/V1__create_product_schema.sql");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createProductMatchesOpenApiContract() throws Exception {
        mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", UUID.randomUUID().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"CONTRACT-01","productName":"Contract Term Life","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void listProductsMatchesOpenApiContract() throws Exception {
        mockMvc.perform(get("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", UUID.randomUUID().toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void publishVersionAndActiveSnapshotMatchOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String createResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"CONTRACT-02","productName":"Contract Endowment","category":"ENDOWMENT","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String productId = com.jayway.jsonpath.JsonPath.read(createResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"MATURITY","calculationMethod":"SUM_ASSURED_PLUS_BONUS"}]}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void publishVersionRejectsMissingRatingCoverageWithUnprocessableEntity() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String createResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"CONTRACT-03","productName":"Contract Incomplete","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String productId = com.jayway.jsonpath.JsonPath.read(createResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"MATURITY","calculationMethod":"SUM_ASSURED_PLUS_BONUS"}]}
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.errorCode").value("INVALID_PRODUCT_VERSION"));
    }
}
```

If `com.jayway.jsonpath.JsonPath` isn't already resolvable (check `PartyContractTest.java` from M1 for whether a JSON-path library is already used elsewhere), extract the `productId` via Jackson's `ObjectMapper` instead — the intent (chain a create-response's generated id into a follow-up request) is what matters, not the exact extraction mechanism.

- [ ] **Step 2: Run the contract tests**

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q test -Dtest=ProductContractTest
```

Expected: `BUILD SUCCESS`, all 4 tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/tz/co/nlolo/lifeplatform/product/ProductContractTest.java
git commit -m "test: contract-test product REST API against openapi-product.yaml"
```

---

### Task 4: `RulesEnginePort` + `SimpleRulesEngine` (the underwriting decision algorithm)

**Files:**
- Create: `src/main/java/tz/co/nlolo/lifeplatform/underwriting/domain/{RulesEnginePort,RiskProfile,UnderwritingDecision}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/underwriting/infrastructure/SimpleRulesEngine.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/underwriting/SimpleRulesEngineTest.java`

**Interfaces:**
- Consumes: nothing external — pure logic, takes already-resolved multipliers/scores as input (Task 5's `UnderwritingApiImpl` is responsible for resolving them via `ProductApi.resolveRatingMultiplier` and the case's own `RiskAssessment` records before calling this).
- Produces: `RulesEnginePort.evaluate(RiskProfile)` — Task 5 consumes this exact signature:
  ```java
  package tz.co.nlolo.lifeplatform.underwriting.domain;

  public interface RulesEnginePort {
      UnderwritingDecision evaluate(RiskProfile riskProfile);
  }
  ```

This task directly satisfies M2's first acceptance criterion: "underwriting decision test suite covers the rule scenarios documented in Deliverable 3" — read the Global Constraints section above first: no Phase 0 doc actually specifies numeric rule content, so this task's own test suite IS the rule-scenario coverage the acceptance criterion refers to, built against the placeholder algorithm below (explicitly flagged as such throughout).

- [ ] **Step 1: Write `RiskProfile.java` and `UnderwritingDecision.java`**

```java
package tz.co.nlolo.lifeplatform.underwriting.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inputs to the underwriting decision. ageBandMultiplier and sumAssuredBandMultiplier
 * are resolved by the caller (UnderwritingApiImpl, Task 5) from product.ProductApi.
 * assessmentRiskScores are the case's submitted RiskAssessment.riskScore values
 * (0-100 scale, higher = more risk), empty if no assessments have been submitted yet.
 *
 * OCCUPATION_CLASS and SMOKER_STATUS rating factors are deliberately NOT part of this
 * profile -- no structured data source exists yet for either (see plan Global
 * Constraints). A future milestone that adds structured medical/occupational intake
 * fields should extend this record and SimpleRulesEngine together, not silently ignore
 * the new data by leaving it out of the profile passed in.
 */
public record RiskProfile(BigDecimal ageBandMultiplier, BigDecimal sumAssuredBandMultiplier, List<BigDecimal> assessmentRiskScores) {}
```

```java
package tz.co.nlolo.lifeplatform.underwriting.domain;

import java.math.BigDecimal;

public record UnderwritingDecision(Outcome outcome, BigDecimal loadingPercent, String reason) {
    public enum Outcome { ACCEPT, LOADED, DECLINED, POSTPONED }
}
```

- [ ] **Step 2: Write `SimpleRulesEngine.java`**

```java
package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.domain.RiskProfile;
import tz.co.nlolo.lifeplatform.underwriting.domain.RulesEnginePort;
import tz.co.nlolo.lifeplatform.underwriting.domain.UnderwritingDecision;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * PLACEHOLDER underwriting decision algorithm, pending Actuarial/Underwriting sign-off
 * on real rules (Global Constraints: no Phase 0 doc specifies actual thresholds or a
 * .drl rule set anywhere). Implements the RulesEnginePort abstraction so a real Drools
 * engine can be substituted later with zero change to callers -- Underwriting owns the
 * rule OUTCOMES (this class's return contract), not the engine mechanism itself
 * (docs/01-domain-map.md §2.2).
 *
 * Combined multiplier = ageBandMultiplier x sumAssuredBandMultiplier (occupation-class
 * and smoker-status factors are not yet consulted -- see RiskProfile's javadoc).
 * Decision thresholds below are placeholder values chosen to be explainable and
 * testable, not actuarially validated:
 *   - any submitted risk score >= 90                          -> POSTPONED (inconclusive, needs senior/medical review)
 *   - combinedMultiplier > 2.5 OR max risk score >= 75         -> DECLINED
 *   - combinedMultiplier > 1.0 OR max risk score >= 40         -> LOADED (loading% derived below)
 *   - otherwise                                                -> ACCEPT
 */
@Component
public class SimpleRulesEngine implements RulesEnginePort {

    private static final BigDecimal POSTPONE_RISK_SCORE_THRESHOLD = new BigDecimal("90");
    private static final BigDecimal DECLINE_MULTIPLIER_THRESHOLD = new BigDecimal("2.5");
    private static final BigDecimal DECLINE_RISK_SCORE_THRESHOLD = new BigDecimal("75");
    private static final BigDecimal LOAD_RISK_SCORE_THRESHOLD = new BigDecimal("40");

    @Override
    public UnderwritingDecision evaluate(RiskProfile riskProfile) {
        BigDecimal combinedMultiplier = riskProfile.ageBandMultiplier().multiply(riskProfile.sumAssuredBandMultiplier());
        BigDecimal maxRiskScore = riskProfile.assessmentRiskScores().stream()
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);

        if (maxRiskScore.compareTo(POSTPONE_RISK_SCORE_THRESHOLD) >= 0) {
            return new UnderwritingDecision(UnderwritingDecision.Outcome.POSTPONED, null,
                "Risk assessment score " + maxRiskScore + " requires further medical evidence before a decision can be made");
        }
        if (combinedMultiplier.compareTo(DECLINE_MULTIPLIER_THRESHOLD) > 0 || maxRiskScore.compareTo(DECLINE_RISK_SCORE_THRESHOLD) >= 0) {
            return new UnderwritingDecision(UnderwritingDecision.Outcome.DECLINED, null,
                "Combined rating multiplier " + combinedMultiplier + " or risk score " + maxRiskScore + " exceeds acceptable risk threshold");
        }
        if (combinedMultiplier.compareTo(BigDecimal.ONE) > 0 || maxRiskScore.compareTo(LOAD_RISK_SCORE_THRESHOLD) >= 0) {
            BigDecimal loadingFromMultiplier = combinedMultiplier.subtract(BigDecimal.ONE).multiply(new BigDecimal("100"));
            BigDecimal loadingPercent = loadingFromMultiplier.max(new BigDecimal("5")).setScale(2, RoundingMode.HALF_UP);
            return new UnderwritingDecision(UnderwritingDecision.Outcome.LOADED, loadingPercent,
                "Elevated risk profile (multiplier " + combinedMultiplier + ", max risk score " + maxRiskScore + ") accepted with premium loading");
        }
        return new UnderwritingDecision(UnderwritingDecision.Outcome.ACCEPT, null, "Standard risk profile");
    }
}
```

- [ ] **Step 3: Write `SimpleRulesEngineTest.java` — this is the "rule scenario" test suite the M2 acceptance criterion refers to**

```java
package tz.co.nlolo.lifeplatform.underwriting;

import tz.co.nlolo.lifeplatform.underwriting.domain.RiskProfile;
import tz.co.nlolo.lifeplatform.underwriting.domain.UnderwritingDecision;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.SimpleRulesEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimpleRulesEngineTest {

    private final SimpleRulesEngine engine = new SimpleRulesEngine();

    @Test
    void standardRiskProfileIsAccepted() {
        RiskProfile profile = new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of());
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.ACCEPT, decision.outcome());
        assertNull(decision.loadingPercent());
    }

    @Test
    void moderatelyElevatedMultiplierIsLoadedNotDeclined() {
        RiskProfile profile = new RiskProfile(new BigDecimal("1.3"), BigDecimal.ONE, List.of());
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.LOADED, decision.outcome());
        assertEquals(0, new BigDecimal("30.00").compareTo(decision.loadingPercent()));
    }

    @Test
    void highMultiplierIsDeclined() {
        RiskProfile profile = new RiskProfile(new BigDecimal("2.0"), new BigDecimal("1.5"), List.of());
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.DECLINED, decision.outcome());
        assertNull(decision.loadingPercent());
    }

    @Test
    void veryHighRiskScoreIsPostponedRegardlessOfMultiplier() {
        RiskProfile profile = new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of(new BigDecimal("95")));
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.POSTPONED, decision.outcome());
    }

    @Test
    void highRiskScoreAloneDeclinesEvenWithNeutralMultiplier() {
        RiskProfile profile = new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of(new BigDecimal("80")));
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.DECLINED, decision.outcome());
    }

    @Test
    void moderateRiskScoreAloneLoadsWithMinimumFivePercent() {
        RiskProfile profile = new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of(new BigDecimal("50")));
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.LOADED, decision.outcome());
        assertEquals(0, new BigDecimal("5.00").compareTo(decision.loadingPercent()));
    }

    @Test
    void multipleAssessmentsUseTheHighestRiskScore() {
        RiskProfile profile = new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of(new BigDecimal("20"), new BigDecimal("92"), new BigDecimal("55")));
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.POSTPONED, decision.outcome());
    }

    @Test
    void postponeThresholdTakesPrecedenceOverDeclineThreshold() {
        // A very high risk score (>=90) postpones even when the multiplier alone would decline --
        // postponement (need more evidence) outranks an automatic decline in this placeholder algorithm.
        RiskProfile profile = new RiskProfile(new BigDecimal("3.0"), BigDecimal.ONE, List.of(new BigDecimal("91")));
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.POSTPONED, decision.outcome());
    }

    @Test
    void reasonStringIsPopulatedForEveryOutcome() {
        for (RiskProfile profile : List.of(
                new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of()),
                new RiskProfile(new BigDecimal("1.3"), BigDecimal.ONE, List.of()),
                new RiskProfile(new BigDecimal("3.0"), BigDecimal.ONE, List.of()),
                new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of(new BigDecimal("95"))))) {
            assertNotNull(engine.evaluate(profile).reason());
        }
    }
}
```

- [ ] **Step 4: Run the tests**

```bash
docker run --rm -v "$(pwd):/workspace" -w /workspace maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q test -Dtest=SimpleRulesEngineTest
```

Expected: `BUILD SUCCESS`, all 9 tests pass. (No Testcontainers needed — pure unit test, no Spring context.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/underwriting/domain src/main/java/tz/co/nlolo/lifeplatform/underwriting/infrastructure/SimpleRulesEngine.java src/test/java/tz/co/nlolo/lifeplatform/underwriting/SimpleRulesEngineTest.java
git commit -m "feat: implement RulesEnginePort and placeholder SimpleRulesEngine for underwriting decisions"
```

---

### Task 5: `underwriting` module — aggregates, persistence, application layer, defense-in-depth fixes

**Files:**
- Modify: `db-migrations/underwriting/V1__create_underwriting_schema.sql` (add missing RLS on `risk_assessment`/`medical_disclosure`, add `app_role` grants — see Global Constraints)
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/document/api/DocumentType.java` (add `UNDERWRITING_EVIDENCE`)
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/document/infrastructure/MinioDocumentStorage.java` (route `UNDERWRITING_EVIDENCE` to a new `underwriting-evidence` bucket)
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/document/infrastructure/MinioClientConfig.java` (ensure the new bucket is created at startup, matching the existing pattern for `kyc-evidence`/`policy-documents`)
- Create: `src/main/java/tz/co/nlolo/lifeplatform/underwriting/domain/{UnderwritingCase,RiskAssessment,MedicalDisclosure}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/underwriting/infrastructure/{UnderwritingCaseRepository,RiskAssessmentRepository,MedicalDisclosureRepository}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/underwriting/api/{UnderwritingCaseStatus,ReferralStatus,AssessmentType,DecisionOutcome,UnderwritingCaseView,UnderwritingCaseNotFoundException,UnderwritingApi}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/underwriting/application/UnderwritingApiImpl.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/underwriting/UnderwritingApiIntegrationTest.java`

**Interfaces:**
- Consumes: `PartyApi.getParty(UUID)` (M1, for applicant date-of-birth → age-band resolution), `ProductApi.resolveRatingMultiplier`/`getActiveSnapshot` (Task 1), `DocumentApi.upload`/`download` (M1, plus the new `UNDERWRITING_EVIDENCE` type from this task), `ReferenceDataApi.getCodes` (M1, for `TZ_CONTESTABILITY_MONTHS`), `RulesEnginePort` (Task 4), `TenantContext.get()` (M1).
- Produces: `UnderwritingApi` — Task 6 (REST controller) consumes it:
  ```java
  package tz.co.nlolo.lifeplatform.underwriting.api;

  public interface UnderwritingApi {
      UnderwritingCaseView openCase(UUID applicantPartyId, UUID productId, UUID productVersionId, BigDecimal sumAssuredAmount, String sumAssuredCurrency, String openedBy);
      UnderwritingCaseView submitAssessment(UUID caseId, AssessmentType assessmentType, String findings, BigDecimal riskScore, String assessedBy);
      UnderwritingCaseView getCase(UUID caseId);
      void referToSeniorUnderwriter(UUID caseId);
      boolean checkContestability(UUID caseId, java.time.LocalDate asOfDate);
  }
  ```

- [ ] **Step 1: Fix the migration file's RLS and grants gaps**

Read the current file first, then add after the existing `underwriting_case_tenant_isolation` policy at the end of the file:

```sql
ALTER TABLE underwriting.risk_assessment ENABLE ROW LEVEL SECURITY;
CREATE POLICY risk_assessment_tenant_isolation ON underwriting.risk_assessment
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE underwriting.medical_disclosure ENABLE ROW LEVEL SECURITY;
CREATE POLICY medical_disclosure_tenant_isolation ON underwriting.medical_disclosure
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- app_role privileges -- see product/V1's identical comment (Global Constraints);
-- same bug class, fixed here from the start.
GRANT USAGE ON SCHEMA underwriting TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA underwriting TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA underwriting GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;
```

- [ ] **Step 2: Add `UNDERWRITING_EVIDENCE` to `document`'s `DocumentType` and bucket routing**

Read `src/main/java/tz/co/nlolo/lifeplatform/document/api/DocumentType.java`, `MinioDocumentStorage.java`, and `MinioClientConfig.java` first to see the exact current bucket-routing logic and startup bucket-creation list (M1's Task 5 established the pattern: `KYC_EVIDENCE → kyc-evidence`, else `→ policy-documents`). Change `DocumentType` to:

```java
package tz.co.nlolo.lifeplatform.document.api;

public enum DocumentType { KYC_EVIDENCE, POLICY_DOCUMENT, CLAIM_EVIDENCE, SIGNED_FORM, UNDERWRITING_EVIDENCE }
```

In `MinioDocumentStorage`'s bucket-routing method, add `UNDERWRITING_EVIDENCE → underwriting-evidence` as its own case (medical evidence is its own sensitivity class, kept separate from `kyc-evidence` — do not fold it into the existing KYC bucket or the `else` branch). In `MinioClientConfig`'s startup bucket-creation list, add `"underwriting-evidence"` alongside the existing bucket names so it's created idempotently at boot exactly like the other two.

- [ ] **Step 3: Write the domain entities**

```java
package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "underwriting_case", schema = "underwriting")
public class UnderwritingCase {

    @Id
    @UuidGenerator
    @Column(name = "case_id")
    private UUID caseId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "applicant_party_id", nullable = false)
    private UUID applicantPartyId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_version_id", nullable = false)
    private UUID productVersionId;

    @Column(nullable = false)
    private String status = "OPEN";

    @Column(name = "referral_status", nullable = false)
    private String referralStatus = "NONE";

    @Column(name = "decision_outcome")
    private String decisionOutcome;

    @Column(name = "decision_loading_percent")
    private BigDecimal decisionLoadingPercent;

    @Column(name = "decision_decline_reason")
    private String decisionDeclineReason;

    @Column(name = "decision_decided_at")
    private Instant decisionDecidedAt;

    @Column(name = "sum_assured_amount")
    private BigDecimal sumAssuredAmount;

    @Column(name = "sum_assured_currency")
    private String sumAssuredCurrency;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    protected UnderwritingCase() {}

    public UnderwritingCase(UUID tenantId, UUID applicantPartyId, UUID productId, UUID productVersionId,
                             BigDecimal sumAssuredAmount, String sumAssuredCurrency, String createdBy) {
        this.tenantId = tenantId;
        this.applicantPartyId = applicantPartyId;
        this.productId = productId;
        this.productVersionId = productVersionId;
        this.sumAssuredAmount = sumAssuredAmount;
        this.sumAssuredCurrency = sumAssuredCurrency;
        this.createdBy = createdBy;
    }

    public UUID getCaseId() { return caseId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getApplicantPartyId() { return applicantPartyId; }
    public UUID getProductId() { return productId; }
    public UUID getProductVersionId() { return productVersionId; }
    public String getStatus() { return status; }
    public String getReferralStatus() { return referralStatus; }
    public String getDecisionOutcome() { return decisionOutcome; }
    public BigDecimal getDecisionLoadingPercent() { return decisionLoadingPercent; }
    public String getDecisionDeclineReason() { return decisionDeclineReason; }
    public Instant getDecisionDecidedAt() { return decisionDecidedAt; }
    public BigDecimal getSumAssuredAmount() { return sumAssuredAmount; }
    public String getSumAssuredCurrency() { return sumAssuredCurrency; }

    public void recordDecision(String outcome, BigDecimal loadingPercent, String declineReason) {
        this.decisionOutcome = outcome;
        this.decisionLoadingPercent = loadingPercent;
        this.decisionDeclineReason = declineReason;
        this.decisionDecidedAt = Instant.now();
        this.status = "DECIDED";
    }

    public void markInReview() {
        this.status = "IN_REVIEW";
    }

    public void referToSeniorUnderwriter() {
        // U1: referralStatus tracked separately from decision.outcome -- a borderline
        // case under senior review doesn't need a fake interim outcome value.
        this.referralStatus = "REFERRED_TO_SENIOR";
    }
}
```

```java
package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "risk_assessment", schema = "underwriting")
public class RiskAssessment {

    @Id
    @UuidGenerator
    @Column(name = "risk_assessment_id")
    private UUID riskAssessmentId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "assessment_type", nullable = false)
    private String assessmentType;

    @Column(nullable = false)
    private String assessor;

    @Column
    private String findings;

    @Column(name = "risk_score")
    private BigDecimal riskScore;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected RiskAssessment() {}

    public RiskAssessment(UUID tenantId, UUID caseId, String assessmentType, String assessor, String findings, BigDecimal riskScore) {
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.assessmentType = assessmentType;
        this.assessor = assessor;
        this.findings = findings;
        this.riskScore = riskScore;
    }

    public UUID getCaseId() { return caseId; }
    public String getAssessmentType() { return assessmentType; }
    public BigDecimal getRiskScore() { return riskScore; }
}
```

```java
package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Not exercised by this milestone's REST surface (openapi-underwriting.yaml's
 * OpenCaseRequest.medicalDisclosure is accepted but its structured Q&A schema is
 * explicitly "product-specific, see Deliverable 6" -- undefined at this layer).
 * Entity/repository built now so the table exists and a future milestone can populate
 * it without a schema migration; not wired into UnderwritingApiImpl's decision flow.
 */
@Entity
@Table(name = "medical_disclosure", schema = "underwriting")
public class MedicalDisclosure {

    @Id
    @UuidGenerator
    @Column(name = "medical_disclosure_id")
    private UUID medicalDisclosureId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "question_response_set", nullable = false, columnDefinition = "jsonb")
    private String questionResponseSet;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected MedicalDisclosure() {}

    public MedicalDisclosure(UUID tenantId, UUID caseId, String questionResponseSet) {
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.questionResponseSet = questionResponseSet;
    }

    public UUID getCaseId() { return caseId; }
}
```

- [ ] **Step 4: Write the repositories**

```java
package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.domain.UnderwritingCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UnderwritingCaseRepository extends JpaRepository<UnderwritingCase, UUID> {
    Optional<UnderwritingCase> findByCaseIdAndTenantId(UUID caseId, UUID tenantId);
}
```

```java
package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.domain.RiskAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, UUID> {
    List<RiskAssessment> findByCaseId(UUID caseId);
}
```

```java
package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.domain.MedicalDisclosure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MedicalDisclosureRepository extends JpaRepository<MedicalDisclosure, UUID> {}
```

- [ ] **Step 5: Write the public API types**

```java
package tz.co.nlolo.lifeplatform.underwriting.api;

public enum UnderwritingCaseStatus { OPEN, IN_REVIEW, DECIDED }
```

```java
package tz.co.nlolo.lifeplatform.underwriting.api;

public enum ReferralStatus { NONE, REFERRED_TO_SENIOR, REFERRAL_RESOLVED }
```

```java
package tz.co.nlolo.lifeplatform.underwriting.api;

public enum AssessmentType { MEDICAL, FINANCIAL, OCCUPATIONAL }
```

```java
package tz.co.nlolo.lifeplatform.underwriting.api;

public enum DecisionOutcome { ACCEPT, LOADED, DECLINED, POSTPONED }
```

```java
package tz.co.nlolo.lifeplatform.underwriting.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record UnderwritingCaseView(UUID caseId, UUID applicantPartyId, UUID productId, UnderwritingCaseStatus status,
                                    ReferralStatus referralStatus, DecisionOutcome decisionOutcome,
                                    BigDecimal decisionLoadingPercent, String decisionDeclineReason, Instant decisionDecidedAt) {}
```

```java
package tz.co.nlolo.lifeplatform.underwriting.api;

import java.util.UUID;

public class UnderwritingCaseNotFoundException extends RuntimeException {
    public UnderwritingCaseNotFoundException(UUID caseId) {
        super("No underwriting case found for id " + caseId);
    }
}
```

```java
package tz.co.nlolo.lifeplatform.underwriting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public interface UnderwritingApi {
    UnderwritingCaseView openCase(UUID applicantPartyId, UUID productId, UUID productVersionId, BigDecimal sumAssuredAmount, String sumAssuredCurrency, String openedBy);
    UnderwritingCaseView submitAssessment(UUID caseId, AssessmentType assessmentType, String findings, BigDecimal riskScore, String assessedBy);
    UnderwritingCaseView getCase(UUID caseId);
    void referToSeniorUnderwriter(UUID caseId);
    boolean checkContestability(UUID caseId, LocalDate asOfDate);
}
```

- [ ] **Step 6: Write `UnderwritingApiImpl.java`**

```java
package tz.co.nlolo.lifeplatform.underwriting.application;

import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import tz.co.nlolo.lifeplatform.underwriting.api.*;
import tz.co.nlolo.lifeplatform.underwriting.domain.*;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.RiskAssessmentRepository;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.UnderwritingCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.UUID;

@Service
public class UnderwritingApiImpl implements UnderwritingApi {

    private final UnderwritingCaseRepository underwritingCaseRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final PartyApi partyApi;
    private final ProductApi productApi;
    private final ReferenceDataApi referenceDataApi;
    private final RulesEnginePort rulesEnginePort;

    public UnderwritingApiImpl(UnderwritingCaseRepository underwritingCaseRepository, RiskAssessmentRepository riskAssessmentRepository,
                                PartyApi partyApi, ProductApi productApi, ReferenceDataApi referenceDataApi, RulesEnginePort rulesEnginePort) {
        this.underwritingCaseRepository = underwritingCaseRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.referenceDataApi = referenceDataApi;
        this.rulesEnginePort = rulesEnginePort;
    }

    @Override
    @Transactional
    public UnderwritingCaseView openCase(UUID applicantPartyId, UUID productId, UUID productVersionId, BigDecimal sumAssuredAmount, String sumAssuredCurrency, String openedBy) {
        UUID tenantId = TenantContext.get();
        // Confirms the applicant party genuinely exists and belongs to this tenant --
        // PartyApi.getParty already throws PartyNotFoundException on cross-tenant access
        // (M1's anti-enumeration pattern), which is exactly the failure mode we want here too.
        partyApi.getParty(applicantPartyId);

        UnderwritingCase underwritingCase = new UnderwritingCase(tenantId, applicantPartyId, productId, productVersionId, sumAssuredAmount, sumAssuredCurrency, openedBy);
        underwritingCaseRepository.save(underwritingCase);
        return toView(underwritingCase);
    }

    @Override
    @Transactional
    public UnderwritingCaseView submitAssessment(UUID caseId, AssessmentType assessmentType, String findings, BigDecimal riskScore, String assessedBy) {
        UUID tenantId = TenantContext.get();
        UnderwritingCase underwritingCase = findOrThrow(caseId, tenantId);
        underwritingCase.markInReview();

        RiskAssessment assessment = new RiskAssessment(tenantId, caseId, assessmentType.name(), assessedBy, findings, riskScore);
        riskAssessmentRepository.save(assessment);

        decideIfPossible(underwritingCase);
        underwritingCaseRepository.save(underwritingCase);
        return toView(underwritingCase);
    }

    private void decideIfPossible(UnderwritingCase underwritingCase) {
        PartyView applicant = partyApi.getParty(underwritingCase.getApplicantPartyId());
        String ageBand = resolveAgeBand(applicant);
        String sumAssuredBand = resolveSumAssuredBand(underwritingCase.getSumAssuredAmount());

        BigDecimal ageMultiplier = productApi.resolveRatingMultiplier(underwritingCase.getProductVersionId(), FactorType.AGE, ageBand);
        BigDecimal sumAssuredMultiplier = productApi.resolveRatingMultiplier(underwritingCase.getProductVersionId(), FactorType.SUM_ASSURED_BAND, sumAssuredBand);

        List<BigDecimal> riskScores = riskAssessmentRepository.findByCaseId(underwritingCase.getCaseId()).stream()
            .map(RiskAssessment::getRiskScore)
            .filter(java.util.Objects::nonNull)
            .toList();

        RiskProfile profile = new RiskProfile(ageMultiplier, sumAssuredMultiplier, riskScores);
        UnderwritingDecision decision = rulesEnginePort.evaluate(profile);

        underwritingCase.recordDecision(decision.outcome().name(), decision.loadingPercent(), decision.reason());
    }

    /**
     * Placeholder age-banding matching the rating table's own placeholder band naming
     * ("30-39" used throughout this plan's tests) -- pending Actuarial sign-off on the
     * real band boundaries, same status as the rest of SimpleRulesEngine's thresholds.
     */
    private String resolveAgeBand(PartyView applicant) {
        // PartyView doesn't currently expose dateOfBirth (M1's PartyApi.PartyView is a
        // narrow read model) -- flagged here rather than guessed: without it, age-band
        // rating cannot be resolved from real data yet, so this defaults to a single
        // placeholder band until PartyView is extended. Not silently wrong -- the
        // rating-table lookup itself already returns a neutral 1.0 for an unmatched
        // band (ProductApi.resolveRatingMultiplier's contract), so this doesn't produce
        // an incorrect decision, only an under-differentiated one.
        return "30-39";
    }

    private String resolveSumAssuredBand(BigDecimal sumAssuredAmount) {
        if (sumAssuredAmount == null) return "LOW";
        if (sumAssuredAmount.compareTo(new BigDecimal("10000000")) >= 0) return "HIGH";
        if (sumAssuredAmount.compareTo(new BigDecimal("2000000")) >= 0) return "MEDIUM";
        return "LOW";
    }

    @Override
    public UnderwritingCaseView getCase(UUID caseId) {
        return toView(findOrThrow(caseId, TenantContext.get()));
    }

    @Override
    @Transactional
    public void referToSeniorUnderwriter(UUID caseId) {
        UnderwritingCase underwritingCase = findOrThrow(caseId, TenantContext.get());
        underwritingCase.referToSeniorUnderwriter();
        underwritingCaseRepository.save(underwritingCase);
    }

    @Override
    public boolean checkContestability(UUID caseId, LocalDate asOfDate) {
        UnderwritingCase underwritingCase = findOrThrow(caseId, TenantContext.get());
        if (underwritingCase.getDecisionDecidedAt() == null) {
            return true; // No decision yet -- treat as within the contestability window (nothing to contest).
        }
        int contestabilityMonths = Integer.parseInt(referenceDataApi.getValue("TZ_CONTESTABILITY_MONTHS", "TZ"));
        LocalDate decidedDate = underwritingCase.getDecisionDecidedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        LocalDate effectiveAsOf = asOfDate != null ? asOfDate : LocalDate.now();
        return Period.between(decidedDate, effectiveAsOf).toTotalMonths() < contestabilityMonths;
    }

    private UnderwritingCase findOrThrow(UUID caseId, UUID tenantId) {
        return underwritingCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
            .orElseThrow(() -> new UnderwritingCaseNotFoundException(caseId));
    }

    private UnderwritingCaseView toView(UnderwritingCase c) {
        return new UnderwritingCaseView(c.getCaseId(), c.getApplicantPartyId(), c.getProductId(),
            UnderwritingCaseStatus.valueOf(c.getStatus()), ReferralStatus.valueOf(c.getReferralStatus()),
            c.getDecisionOutcome() != null ? DecisionOutcome.valueOf(c.getDecisionOutcome()) : null,
            c.getDecisionLoadingPercent(), c.getDecisionDeclineReason(), c.getDecisionDecidedAt());
    }
}
```

Note: `ReferenceDataApi.getValue(String codeSetKey, String jurisdiction)` returns a single `String` — confirm this against the actual M1 signature (`src/main/java/tz/co/nlolo/lifeplatform/refdata/api/ReferenceDataApi.java`) before using it; if the seeded row's `code` column value (not `jurisdiction`) is what `getValue` actually keys on, adjust the call accordingly — the seed data uses `code = 'DEFAULT'`, `jurisdiction = 'TZ'` for `TZ_CONTESTABILITY_MONTHS` (confirmed in `db-migrations/refdata/V1__create_refdata_schema.sql`).

- [ ] **Step 7: Write `UnderwritingApiIntegrationTest.java`**

```java
package tz.co.nlolo.lifeplatform.underwriting;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.underwriting.api.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes = Application.class)
class UnderwritingApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql");
    }

    @BeforeEach
    void setTenant() { TenantContext.set(UUID.randomUUID()); }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Autowired
    private UnderwritingApi underwritingApi;
    @Autowired
    private PartyApi partyApi;
    @Autowired
    private ProductApi productApi;

    private UUID openTestCase(BigDecimal sumAssured) {
        var applicant = partyApi.registerIndividual("Test Applicant", LocalDate.of(1990, 1, 1), "+255712345678", null, "test");
        var product = productApi.createProduct("UW-TEST-" + UUID.randomUUID(), "UW Test Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        var snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return underwritingApi.openCase(applicant.partyId(), product.productId(), snapshot.productVersionId(), sumAssured, "TZS", "agent1").caseId();
    }

    @Test
    void openCaseStartsAsOpenWithNoDecision() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        UnderwritingCaseView view = underwritingApi.getCase(caseId);
        assertEquals(UnderwritingCaseStatus.OPEN, view.status());
        assertNull(view.decisionOutcome());
    }

    @Test
    void submitAssessmentWithLowRiskScoreAcceptsTheCase() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        UnderwritingCaseView view = underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Normal findings", new BigDecimal("10"), "underwriter1");
        assertEquals(UnderwritingCaseStatus.DECIDED, view.status());
        assertEquals(DecisionOutcome.ACCEPT, view.decisionOutcome());
    }

    @Test
    void submitAssessmentWithHighRiskScoreDeclinesTheCase() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        UnderwritingCaseView view = underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Serious findings", new BigDecimal("80"), "underwriter1");
        assertEquals(DecisionOutcome.DECLINED, view.decisionOutcome());
    }

    @Test
    void submitAssessmentWithVeryHighRiskScorePostponesTheCase() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        UnderwritingCaseView view = underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Inconclusive test result", new BigDecimal("95"), "underwriter1");
        assertEquals(DecisionOutcome.POSTPONED, view.decisionOutcome());
    }

    @Test
    void referToSeniorUnderwriterSetsReferralStatusIndependentlyOfDecision() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        underwritingApi.referToSeniorUnderwriter(caseId);
        UnderwritingCaseView view = underwritingApi.getCase(caseId);
        assertEquals(ReferralStatus.REFERRED_TO_SENIOR, view.referralStatus());
        assertEquals(UnderwritingCaseStatus.OPEN, view.status()); // Referral doesn't force a decision -- U1's point.
    }

    @Test
    void checkContestabilityIsTrueImmediatelyAfterDecision() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Normal", new BigDecimal("10"), "underwriter1");
        assertTrue(underwritingApi.checkContestability(caseId, LocalDate.now()));
    }

    @Test
    void checkContestabilityIsFalseAfterTheWindowElapses() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Normal", new BigDecimal("10"), "underwriter1");
        // TZ_CONTESTABILITY_MONTHS seed value is 24 (placeholder, see refdata migration comment).
        assertFalse(underwritingApi.checkContestability(caseId, LocalDate.now().plusMonths(25)));
    }

    @Test
    void openCaseRejectsUnknownApplicant() {
        var product = productApi.createProduct("UW-TEST-BAD-" + UUID.randomUUID(), "Bad Applicant Test", ProductCategory.TERM_LIFE, "TZS", "actuary");
        assertThrows(tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException.class, () ->
            underwritingApi.openCase(UUID.randomUUID(), product.productId(), UUID.randomUUID(), new BigDecimal("1000000"), "TZS", "agent1"));
    }

    @Test
    void casesAreTenantIsolated() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        TenantContext.clear();
        TenantContext.set(UUID.randomUUID());
        assertThrows(UnderwritingCaseNotFoundException.class, () -> underwritingApi.getCase(caseId));
    }
}
```

- [ ] **Step 8: Compile and run**

```bash
docker run --rm -v "$(pwd):/workspace" -w /workspace maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q compile
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q test -Dtest=UnderwritingApiIntegrationTest
```

Expected: `BUILD SUCCESS`, all 9 tests pass.

- [ ] **Step 9: Commit**

```bash
git add db-migrations/underwriting src/main/java/tz/co/nlolo/lifeplatform/underwriting src/main/java/tz/co/nlolo/lifeplatform/document/api/DocumentType.java src/main/java/tz/co/nlolo/lifeplatform/document/infrastructure/MinioDocumentStorage.java src/main/java/tz/co/nlolo/lifeplatform/document/infrastructure/MinioClientConfig.java src/test/java/tz/co/nlolo/lifeplatform/underwriting/UnderwritingApiIntegrationTest.java
git commit -m "feat: implement underwriting module aggregates, persistence, and internal API"
```

---

### Task 6: `underwriting` REST layer

**Files:**
- Create: `src/main/java/tz/co/nlolo/lifeplatform/underwriting/infrastructure/{UnderwritingController,OpenCaseRequest,SubmitAssessmentRequest,UnderwritingExceptionHandler}.java`

**Interfaces:**
- Consumes: `UnderwritingApi` (Task 5), `ROLE_REALM_*`/`ROLE_UNDERWRITER` authorities.
- Produces: the 4 HTTP endpoints `api/openapi/openapi-underwriting.yaml` declares — Task 7's contract tests call these directly.

- [ ] **Step 1: Write the request DTOs**

```java
package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import java.math.BigDecimal;
import java.util.UUID;

public record OpenCaseRequest(UUID applicantPartyId, UUID productId, UUID productVersionId, BigDecimal sumAssuredAmount, String sumAssuredCurrency) {}
```

```java
package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType;
import java.math.BigDecimal;

public record SubmitAssessmentRequest(AssessmentType assessmentType, String findings, BigDecimal riskScore) {}
```

- [ ] **Step 2: Write `UnderwritingController.java`**

```java
package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/underwriting")
public class UnderwritingController {

    private final UnderwritingApi underwritingApi;

    public UnderwritingController(UnderwritingApi underwritingApi) {
        this.underwritingApi = underwritingApi;
    }

    @PostMapping("/cases")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<UnderwritingCaseView> openCase(@RequestBody OpenCaseRequest request,
                                                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // Idempotency-Key accepted but not yet enforced -- see plan Global Constraints;
        // real dedup registry lands with payment's idempotency work in M5.
        UnderwritingCaseView view = underwritingApi.openCase(request.applicantPartyId(), request.productId(), request.productVersionId(),
            request.sumAssuredAmount(), request.sumAssuredCurrency(), "agent");
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/cases/{caseId}")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<UnderwritingCaseView> getCase(@PathVariable UUID caseId) {
        return ResponseEntity.ok(underwritingApi.getCase(caseId));
    }

    @PostMapping("/cases/{caseId}/assessments")
    @PreAuthorize("hasRole('UNDERWRITER')")
    public ResponseEntity<UnderwritingCaseView> submitAssessment(@PathVariable UUID caseId, @RequestBody SubmitAssessmentRequest request) {
        UnderwritingCaseView view = underwritingApi.submitAssessment(caseId, request.assessmentType(), request.findings(), request.riskScore(), "underwriter");
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @PostMapping("/cases/{caseId}/referral")
    @PreAuthorize("hasRole('UNDERWRITER')")
    public ResponseEntity<Void> referCase(@PathVariable UUID caseId) {
        underwritingApi.referToSeniorUnderwriter(caseId);
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 3: Write `UnderwritingExceptionHandler.java`**

```java
package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UnderwritingExceptionHandler {

    @ExceptionHandler(UnderwritingCaseNotFoundException.class)
    public ProblemDetail handleNotFound(UnderwritingCaseNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setProperty("errorCode", "UNDERWRITING_CASE_NOT_FOUND");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
```

Note: `PartyNotFoundException` (thrown by `openCase` when the applicant doesn't exist) is already handled globally — check whether M1's `PartyExceptionHandler` (also `@Order(HIGHEST_PRECEDENCE)`) already covers it correctly when thrown from a different module's controller. Both advices are at the same order value; Spring's `ControllerAdviceBean` sort is stable, so the one that matches first wins for a given exception type — since `PartyNotFoundException` and `UnderwritingCaseNotFoundException` are disjoint types, there's no collision to worry about here, but confirm this reasoning holds by testing it (Task 7) rather than assuming.

- [ ] **Step 4: Compile and run the existing underwriting tests**

```bash
docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q test -Dtest=UnderwritingApiIntegrationTest,SimpleRulesEngineTest
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/underwriting/infrastructure
git commit -m "feat: add underwriting REST controller implementing openapi-underwriting.yaml"
```

---

### Task 7: `underwriting` contract tests

**Files:**
- Test: `src/test/java/tz/co/nlolo/lifeplatform/underwriting/UnderwritingContractTest.java`

**Interfaces:**
- Consumes: `UnderwritingController` (Task 6), `api/openapi/openapi-underwriting.yaml`.

- [ ] **Step 1: Write `UnderwritingContractTest.java`**

```java
package tz.co.nlolo.lifeplatform.underwriting;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class UnderwritingContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-underwriting.yaml";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql");
    }

    @Autowired
    private MockMvc mockMvc;

    private String openCaseViaHttp(UUID tenantId, UUID applicantPartyId, UUID productId, UUID productVersionId) throws Exception {
        return mockMvc.perform(post("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"applicantPartyId":"%s","productId":"%s","productVersionId":"%s","sumAssuredAmount":1000000,"sumAssuredCurrency":"TZS"}
                    """.formatted(applicantPartyId, productId, productVersionId)))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();
    }

    private record ProductFixture(UUID productId, UUID productVersionId) {}

    private ProductFixture publishTestProduct(UUID tenantId) throws Exception {
        String createResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"UW-CONTRACT-%s","productName":"UW Contract Test","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(createResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]}
                    """))
            .andExpect(status().isCreated());

        String snapshotResponse = mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String productVersionId = JsonPath.read(snapshotResponse, "$.productVersionId");

        return new ProductFixture(UUID.fromString(productId), UUID.fromString(productVersionId));
    }

    private UUID registerTestApplicant(UUID tenantId) throws Exception {
        String response = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"UW Contract Applicant","dateOfBirth":"1988-03-15","contactInfo":{"phoneNumber":"+255712340000"}}
                    """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    @Test
    void openCaseMatchesOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);
        openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());
    }

    @Test
    void getCaseMatchesOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);
        String caseResponse = openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());
        String caseId = JsonPath.read(caseResponse, "$.caseId");

        mockMvc.perform(get("/underwriting/cases/" + caseId)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void submitAssessmentMatchesOpenApiContractAndRequiresUnderwriterRole() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);
        String caseResponse = openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());
        String caseId = JsonPath.read(caseResponse, "$.caseId");

        // A plain staff token without the UNDERWRITER role is forbidden.
        mockMvc.perform(post("/underwriting/cases/" + caseId + "/assessments")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assessmentType":"MEDICAL","findings":"Routine","riskScore":10}
                    """))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/underwriting/cases/" + caseId + "/assessments")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_UNDERWRITER"), new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assessmentType":"MEDICAL","findings":"Routine","riskScore":10}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void referralMatchesOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);
        String caseResponse = openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());
        String caseId = JsonPath.read(caseResponse, "$.caseId");

        mockMvc.perform(post("/underwriting/cases/" + caseId + "/referral")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_UNDERWRITER"), new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk());
    }

    @Test
    void getCaseForNonexistentCaseReturnsNotFoundNotServerError() throws Exception {
        mockMvc.perform(get("/underwriting/cases/" + UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", UUID.randomUUID().toString()))))
            .andExpect(status().isNotFound())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.errorCode").value("UNDERWRITING_CASE_NOT_FOUND"));
    }
}
```

- [ ] **Step 2: Run the contract tests**

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q test -Dtest=UnderwritingContractTest
```

Expected: `BUILD SUCCESS`, all 5 tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/tz/co/nlolo/lifeplatform/underwriting/UnderwritingContractTest.java
git commit -m "test: contract-test underwriting REST API against openapi-underwriting.yaml"
```

---

### Task 8: Full verification

**Files:** none (verification-only task).

- [ ] **Step 1: Run the complete test suite**

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 ./mvnw -B test
```

Expected: `BUILD SUCCESS`, 0 failures. Includes M0's `ModularityTests`/`NoCircularDependencyTest`/`NoCrossModuleJoinTest` — `underwriting`'s new cross-module calls (`PartyApi`, `ProductApi`, `DocumentApi`, `ReferenceDataApi`) must all resolve through each module's public `api` package only; if `ModularityTests` fails, read the violation before changing anything.

- [ ] **Step 2: Full-stack smoke test**

```bash
docker build -f infra/app/Dockerfile -t lifeplatform:m2 .
cd infra && docker compose up -d
```

Wait at least 90 seconds before checking (M1's final verification found this environment's real cold-start is ~75s, longer than a short fixed sleep).

```bash
curl -sf http://localhost:8080/actuator/health
docker compose logs app --tail 80
docker compose down -v
cd ..
```

Expected: `{"status":"UP",...}`, no restart loop, no errors in the app logs.

- [ ] **Step 3: Confirm both M2 acceptance criteria are independently demonstrated**

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q test \
  -Dtest=SimpleRulesEngineTest,UnderwritingApiIntegrationTest,ProductContractTest,UnderwritingContractTest
```

Expected: `BUILD SUCCESS` — `SimpleRulesEngineTest` + `UnderwritingApiIntegrationTest` together prove criterion 1 ("underwriting decision test suite covers the rule scenarios documented in Deliverable 3" — per this plan's Global Constraints, the rule *scenarios* are this plan's own explicitly-flagged placeholder algorithm, since no numeric rule content exists in Deliverable 3 itself); `ProductContractTest` + `UnderwritingContractTest` together prove criterion 2 ("product configuration API contract-validated" — extended to cover `underwriting`'s contract too, since both modules ship in this milestone).

- [ ] **Step 4: No commit** — this task only verifies Tasks 1-7.

---

## Self-Review Notes

- **Spec coverage:** both M2 acceptance criteria (`docs/08-implementation-roadmap.md` M2 section) map to specific tasks: underwriting decision test suite → Task 4 (`SimpleRulesEngineTest`) + Task 5 (`UnderwritingApiIntegrationTest`); product API contract validation → Task 3 (`ProductContractTest`). The module scope line ("Product configuration ... and the underwriting decision workflow") maps to Tasks 1-3 (`product`) and Tasks 4-7 (`underwriting`).
- **Placeholder scan:** the underwriting decision algorithm (Task 4) is a deliberately incomplete/placeholder piece, explicitly flagged in the Global Constraints, in `RiskProfile`'s javadoc, in `SimpleRulesEngine`'s class javadoc, and in `UnderwritingApiImpl.resolveAgeBand`'s comment — not a silent TODO. The `Idempotency-Key` header (Task 6) is accepted-but-unenforced, explicitly flagged as deferred to M5. `MedicalDisclosure`'s entity/repository (Task 5) is built but not wired into the decision flow, explicitly flagged as unexercised by this milestone's REST surface.
- **Type/name consistency:** `ProductApi.resolveRatingMultiplier`'s signature (Task 1) is used identically by `UnderwritingApiImpl` (Task 5). `RulesEnginePort.evaluate(RiskProfile)` (Task 4) is used identically by `UnderwritingApiImpl` (Task 5). `UnderwritingApi`'s method signatures are identical between Task 5 (definition) and Task 6 (REST controller consumer). `FactorType`/`BenefitType` enum values match `openapi-product.yaml`'s and the existing `db-migrations/product` CHECK constraints exactly (`AGE, OCCUPATION_CLASS, SMOKER_STATUS, SUM_ASSURED_BAND` / `DEATH, DISABILITY, CRITICAL_ILLNESS, MATURITY, SURRENDER`).
- **Proactive fixes carried forward from M1's final review:** every migration file this plan touches (`product`, `underwriting`) gets full RLS coverage on every tenant-scoped table and full `app_role` grants from Task 1/5 onward, rather than waiting for a final whole-branch review to find the same gap a third time.
