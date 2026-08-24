# M1 — Generic Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first four modules with real business logic and persistence — `refdata`, `party`, `document`, `audit` — per `docs/08-implementation-roadmap.md`'s M1 definition, satisfying its three explicit acceptance criteria: `party`'s REST API is contract-tested against `openapi-party.yaml`; the Deliverable 6 Row-Level Security smoke test is an automated integration test; `audit_log` receives a row for a synthetic event end-to-end.

**Architecture:** Each module gets real JPA entities (in `domain`), Spring Data repositories (in `infrastructure`), a public Java interface + view/DTO records (in `api`), and a `@Service` implementation orchestrating them (in `application`) — the same four-layer convention M0 stubbed out, now filled in. Two genuinely cross-cutting concerns that no single module owns get a home outside the 18 modules: `DomainEventEnvelope<T>` and `TenantContext` live directly in the root package `tz.co.nlolo.lifeplatform` (Spring Modulith treats only direct subpackages of the base package as modules — the root package itself is not one, and is the correct home for shared kernel types every module needs). Multi-issuer JWT security configuration lives inside the `iam` module (`iam.infrastructure`) — this is `iam`'s first real content since M0 left it as an empty stub, and Identity & Access is exactly its conceptual ownership per `docs/01-domain-map.md`/`docs/02-module-architecture.md`. REST controllers and other inbound/outbound adapters (MinIO client, HTTP controllers) live in each module's `infrastructure` package, symmetric with the outbound MinIO adapter.

**Tech Stack:** Everything from M0 (Java 21, Spring Boot 3.3.5, Spring Modulith 1.2.5) plus: Spring Security + OAuth2 Resource Server (multi-issuer JWT), Testcontainers (PostgreSQL + MinIO modules) for integration tests, MinIO Java client, `swagger-request-validator-mockmvc` for OpenAPI contract testing.

## Global Constraints

- Base package `tz.co.nlolo.lifeplatform` (unchanged from M0). Module `allowedDependencies` declarations from M0's Task 3 are already correct for M1's needs (`party → document, refdata`; `document`, `refdata`, `audit`, `iam` → none) — do not modify any `package-info.java` in this plan.
- **No local `java`/`mvn`** in this environment — every build/test step runs through Docker, exactly as in the M0 plan: `docker run --rm -v "$(pwd):/workspace" -w /workspace maven:3.9.9-eclipse-temurin-21 ./mvnw ...` (or the explicit `-v "/c/Users/USER/.../<worktree>:/workspace"` form if `$(pwd)` misbehaves in Git-Bash-on-Windows).
- **Every task from Task 3 onward runs Testcontainers-based tests**, which need to reach the host's Docker daemon from inside the `maven` build container (Docker-outside-of-Docker). Every such `docker run ... ./mvnw test` command in this plan **must** additionally mount the host's Docker socket: add `-v /var/run/docker.sock:/var/run/docker.sock` alongside the project volume mount. This was verified working in this environment (`docker run --rm -v /var/run/docker.sock:/var/run/docker.sock docker:cli docker version` succeeded, reporting the same daemon version as the host). **Known residual risk:** Testcontainers' network reachability from inside a container can require `-e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` on Docker Desktop, depending on version/networking mode — if a Testcontainers-based test hangs or fails with connection-refused/timeout errors despite the container logs showing it started, add that env var to the `docker run` command and retry before treating it as a real test failure.
- **`refdata`'s four seeded parameters are explicit, non-production placeholders** (`db-migrations/refdata/V1__create_refdata_schema.sql`) pending Legal/Product/Actuarial sign-off (`docs/03-aggregate-design.md` Rev 2 §13 item 4, `docs/06-database-schema.md` §6 item 5) — every place this plan reads them must carry a code comment saying so, not just rely on the migration file's own comment.
- **Per-module Postgres schema convention holds**: no cross-schema JPA joins, no shared entities across module packages. `GroupMembership` is `party`'s own, separate aggregate/repository — **never** loaded as a collection on the `Party` entity (`docs/03-aggregate-design.md` §7.1, Pa1 — a 10,000-member SACCO group would blow up the aggregate-size principle).
- **Tenant isolation is defense-in-depth, not optional**: every tenant-scoped write must go through `TenantContext.get()` (throws if unset — a deliberate fail-loud guard against a caller forgetting to establish tenant context) for the `tenant_id` column value, **and** every DB connection must have `SET app.current_tenant_id` issued for Postgres RLS to do anything (`docs/06-database-schema.md` §5). `refdata` is the deliberate global exception — no `tenant_id`, no RLS.
- **Transactional event publishing is mandatory** (`docs/02-module-architecture.md` §2, unchanged since Rev 2): every domain event is published via `@TransactionalEventListener(phase = AFTER_COMMIT)` (or equivalent), never observable before the publisher's own transaction has durably committed.
- **Object-level authorization is mandatory on customer/agent-scoped endpoints** (`docs/04-api-contracts.md` §3) — this is the OWASP API Top-10 #1 risk and must be checked explicitly in code, not assumed from role membership alone.
- **`PartyRelationship` is explicitly out of scope for M1** — nothing in `openapi-party.yaml` or the M1 acceptance criteria exercises it, and building unused persistence/API surface for it now would be untested dead code. Flagged here as a deliberate scope cut, not a silent gap; it lands when a real consumer (e.g. `AUTHORIZED_SIGNATORY` support) needs it.
- **Never invent Keycloak role names doc04 doesn't give.** `docs/04-api-contracts.md` §3 only names concrete realm roles for the `staff` realm (`UNDERWRITER`, `CLAIMS_ASSESSOR`, `CLAIMS_MANAGER`, `FINANCE_OFFICER`, `CUSTOMER_SERVICE_REP`, `ADMIN` — already seeded in `keycloak/staff-realm.json` by M0). For `customers`/`agents`/`regulators`, no specific role names are specified anywhere — authorization for those realms in this plan is by **realm membership** (a synthetic `ROLE_REALM_<CUSTOMERS|AGENTS|STAFF|REGULATORS>` authority tagged per issuer, since we already resolve requests by issuer) plus **object-level checks**, which doc04 §3 itself frames as the actually load-bearing control ("the single easiest thing to get wrong").
- **Fine-grained agent/agency-hierarchy scoping is out of scope for M1** — no agent or policy data exists yet (that's M2/M3/M7). Agent-realm object-level checks beyond realm membership are deferred; flagged explicitly in Task 8, not silently skipped.
- Dependency versions: if any exact version named in this plan (`io.minio:minio:8.5.12`, `com.atlassian.oai:swagger-request-validator-mockmvc:2.40.2`) fails to resolve, pin to the nearest available GA version reported by Maven's error output and note the substitution in the task report — same latitude as M0's Task 1.

---

## File Structure

```
pom.xml                                          # modified — Security, OAuth2 resource server,
                                                  #   Testcontainers, MinIO, contract-test deps
src/main/java/tz/co/nlolo/lifeplatform/
  DomainEventEnvelope.java                       # new — shared event envelope record
  TenantContext.java                             # new — ThreadLocal tenant holder
  TenantAwareDataSource.java                     # new — SET app.current_tenant_id per connection
  TenantDataSourceConfig.java                    # new — wraps the autoconfigured DataSource
  iam/infrastructure/SecurityConfig.java         # new — multi-issuer JWT resource server config
  refdata/domain/ReferenceCodeSet.java           # new
  refdata/infrastructure/ReferenceCodeSetRepository.java
  refdata/api/{ReferenceDataApi,ReferenceCodeView}.java
  refdata/application/ReferenceDataApiImpl.java
  audit/domain/{AuditLogEntry,FailedEvent}.java
  audit/infrastructure/{AuditLogRepository,FailedEventRepository,DomainEventAuditListener}.java
  audit/api/{AuditApi,AuditEntryView,EntityRef,DateRange}.java
  audit/application/AuditApiImpl.java
  document/domain/DocumentRecord.java
  document/infrastructure/{DocumentRecordRepository,MinioClientConfig,MinioDocumentStorage,DocumentStorageException}.java
  document/api/{DocumentApi,DocumentType,DocumentMetadataView}.java
  document/application/DocumentApiImpl.java
  party/domain/{Party,KycRecord,GroupMembership}.java
  party/infrastructure/{PartyRepository,KycRecordRepository,GroupMembershipRepository}.java
  party/infrastructure/{PartyController,RegisterIndividualRequest,RegisterCorporateRequest,
                         ContactInfo,KycUpdateRequest,AddGroupMemberRequest,PageResponse,
                         PartyExceptionHandler}.java
  party/api/{PartyApi,PartyView,PartyType,KycStatus,GroupMembershipView,
              PartyNotFoundException,DuplicateRegistrationNumberException}.java
  party/application/PartyApiImpl.java
src/main/resources/application.yml               # modified — issuer + MinIO properties
src/test/java/tz/co/nlolo/lifeplatform/
  MigrationTestSupport.java                      # new — shared Testcontainers migration helper
  RowLevelSecurityIntegrationTest.java           # new — Deliverable 6 §1 RLS smoke test, automated
  refdata/ReferenceDataApiIntegrationTest.java
  audit/DomainEventAuditListenerIntegrationTest.java
  document/DocumentApiIntegrationTest.java
  party/{PartyApiIntegrationTest,PartyContractTest}.java
keycloak/*.json                                  # NOT modified in this plan (see note below)
```

**Files deliberately NOT touched:** `docs/**`, `api/**`, `db-migrations/**`, `observability/**`, `infra/**`, `.github/workflows/ci-cd.yml`, `scripts/**`, `keycloak/*.json` — none of M1's work requires changing Phase 0 deliverables or M0's infra/CI wiring. (`keycloak/README.md`, written in M0's final-review fix wave, already documents that custom claim mappers like `party_id` are not configured — that stays true after M1; the object-level check in Task 8 reads a `party_id` claim that a real login would need a protocol mapper to populate, which is explicitly not built here — see Task 8.)

---

### Task 1: Shared kernel types + M1 dependencies

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/DomainEventEnvelope.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/TenantContext.java`
- Create: `src/test/java/tz/co/nlolo/lifeplatform/MigrationTestSupport.java`

**Interfaces:**
- Produces: `DomainEventEnvelope<T>` (with a static `of(String eventType, UUID tenantId, T payload)` factory) and `TenantContext.set/get/getOrNull/clear` — every subsequent task's event publishing and tenant-scoped persistence depends on these exact names. `MigrationTestSupport.applyMigration(String jdbcUrl, String username, String password, String... migrationPaths)` — every subsequent Testcontainers-based test in this plan calls this.

- [ ] **Step 1: Add M1 dependencies to `pom.xml`**

Add inside the existing `<dependencies>` block (after M0's entries, before `</dependencies>`):

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
    </dependency>
    <dependency>
      <groupId>io.minio</groupId>
      <artifactId>minio</artifactId>
      <version>8.5.12</version>
    </dependency>

    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-testcontainers</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>postgresql</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>minio</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.atlassian.oai</groupId>
      <artifactId>swagger-request-validator-mockmvc</artifactId>
      <version>2.40.2</version>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 2: Write `DomainEventEnvelope.java`**

```java
package tz.co.nlolo.lifeplatform;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared event envelope every module publishes and audit.DomainEventAuditListener
 * consumes generically (docs/02-module-architecture.md §2, docs/05-event-catalog.md
 * §1's EventEnvelopeMeta). sequenceNumber is nullable -- only order-sensitive async
 * consumers need it (docs/05-event-catalog.md §5); none exist yet at M1.
 */
public record DomainEventEnvelope<T>(
    UUID eventId,
    String eventType,
    int schemaVersion,
    UUID tenantId,
    Instant occurredAt,
    Long sequenceNumber,
    T payload
) {
    public static <T> DomainEventEnvelope<T> of(String eventType, UUID tenantId, T payload) {
        return new DomainEventEnvelope<>(UUID.randomUUID(), eventType, 1, tenantId, Instant.now(), null, payload);
    }
}
```

- [ ] **Step 3: Write `TenantContext.java`**

```java
package tz.co.nlolo.lifeplatform;

import java.util.UUID;

/**
 * Per-thread tenant identity. Business code MUST call get() (throws if unset --
 * a deliberate fail-loud guard against silently writing rows with no tenant).
 * getOrNull() exists ONLY for TenantAwareDataSource, which must not crash
 * connections acquired outside any tenant-scoped operation (health checks,
 * refdata's non-tenant-scoped queries).
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static UUID get() {
        UUID tenantId = CURRENT_TENANT.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set for the current thread");
        }
        return tenantId;
    }

    public static UUID getOrNull() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
```

- [ ] **Step 4: Write `MigrationTestSupport.java`**

```java
package tz.co.nlolo.lifeplatform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Applies real Flyway migration SQL files (db-migrations/<module>/V1__...sql)
 * directly via JDBC against a Testcontainers Postgres instance, mirroring how
 * .github/workflows/ci-cd.yml's db-migration-validation job and scripts/migrate.sh
 * apply them -- so integration tests run against the SAME schema-creation SQL
 * that ships, not a hand-maintained copy that could drift.
 */
public final class MigrationTestSupport {

    private MigrationTestSupport() {}

    public static void applyMigration(String jdbcUrl, String username, String password, String... migrationPaths)
            throws IOException, SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            for (String migrationPath : migrationPaths) {
                String sql = Files.readString(Path.of(migrationPath));
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
        }
    }
}
```

- [ ] **Step 5: Verify dependency resolution and compilation**

```bash
docker run --rm -v "$(pwd):/workspace" -w /workspace maven:3.9.9-eclipse-temurin-21 \
  ./mvnw -B -q -DskipTests dependency:resolve
docker run --rm -v "$(pwd):/workspace" -w /workspace maven:3.9.9-eclipse-temurin-21 \
  ./mvnw -B -q -DskipTests compile test-compile
```

Expected: both exit 0. If `io.minio:minio:8.5.12` or `swagger-request-validator-mockmvc:2.40.2` fail to resolve, pin to the nearest available GA version from Maven's error output and note the substitution in your report.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/tz/co/nlolo/lifeplatform/DomainEventEnvelope.java \
        src/main/java/tz/co/nlolo/lifeplatform/TenantContext.java \
        src/test/java/tz/co/nlolo/lifeplatform/MigrationTestSupport.java
git commit -m "feat: add shared event envelope, tenant context, and M1 dependencies"
```

---

### Task 2: Multi-issuer JWT security configuration (`iam` module)

**Files:**
- Create: `src/main/java/tz/co/nlolo/lifeplatform/iam/infrastructure/SecurityConfig.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/iam/SecurityConfigTest.java`

**Interfaces:**
- Consumes: `KEYCLOAK_ISSUER_CUSTOMERS`/`_AGENTS`/`_STAFF`/`_REGULATORS` env vars (already set by `infra/docker-compose.yml`'s `app` service, lines 125-128 — no compose changes needed).
- Produces: a `SecurityFilterChain` that authenticates JWTs from any of the 4 realms and tags each authenticated request with a `ROLE_REALM_<CUSTOMERS|AGENTS|STAFF|REGULATORS>` authority plus `ROLE_<x>` for every Keycloak `realm_access.roles` entry. Task 8's `@PreAuthorize` annotations depend on these exact authority name formats.

- [ ] **Step 1: Add issuer properties to `application.yml`**

Add a new top-level block (anywhere after the existing `spring:` block):

```yaml
app:
  security:
    issuers:
      customers: ${KEYCLOAK_ISSUER_CUSTOMERS:http://localhost:8081/realms/customers}
      agents: ${KEYCLOAK_ISSUER_AGENTS:http://localhost:8081/realms/agents}
      staff: ${KEYCLOAK_ISSUER_STAFF:http://localhost:8081/realms/staff}
      regulators: ${KEYCLOAK_ISSUER_REGULATORS:http://localhost:8081/realms/regulators}

minio:
  endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
  access-key: ${MINIO_ACCESS_KEY:minioadmin}
  secret-key: ${MINIO_SECRET_KEY:minioadmin}
```

(The `minio:` block is added here too, in the same edit, since it's a one-line addition needed by Task 5 and this is the natural place to add config alongside the issuer properties — avoids a near-empty follow-up edit to the same file later.)

- [ ] **Step 2: Write `SecurityConfig.java`**

```java
package tz.co.nlolo.lifeplatform.iam.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Multi-issuer JWT resource server, one AuthenticationManager per Keycloak
 * realm from docs/04-api-contracts.md §3. Every authenticated request gets a
 * synthetic ROLE_REALM_<X> authority (used by @PreAuthorize checks that need
 * "any token from this realm" -- customers/agents/regulators have no specific
 * role names defined anywhere in the Phase 0 docs, only staff does) plus a
 * ROLE_<Y> authority per Keycloak realm_access.roles entry (staff's six roles).
 *
 * Decoders are constructed lazily (see lazyDecoder below): NimbusJwtDecoder.
 * withIssuerLocation(...).build() makes an EAGER HTTP call to the issuer's
 * OIDC discovery endpoint. If that ran at bean-creation time, the whole app
 * context would fail to start whenever Keycloak's realm import hasn't
 * finished yet when `app` boots (infra/docker-compose.yml's app service
 * depends_on keycloak with condition: service_started, not service_healthy)
 * -- exactly the kind of boot-time crash M0's Task 10 had to fix once already
 * for a different dependency. Deferring the discovery call to first actual
 * token validation avoids re-introducing that failure mode.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public AuthenticationManagerResolver<HttpServletRequest> issuerAuthenticationManagerResolver(
            @Value("${app.security.issuers.customers}") String customersIssuer,
            @Value("${app.security.issuers.agents}") String agentsIssuer,
            @Value("${app.security.issuers.staff}") String staffIssuer,
            @Value("${app.security.issuers.regulators}") String regulatorsIssuer) {

        Map<String, AuthenticationManager> managersByIssuer = Map.of(
            customersIssuer, authenticationManagerFor(customersIssuer, "CUSTOMERS"),
            agentsIssuer, authenticationManagerFor(agentsIssuer, "AGENTS"),
            staffIssuer, authenticationManagerFor(staffIssuer, "STAFF"),
            regulatorsIssuer, authenticationManagerFor(regulatorsIssuer, "REGULATORS")
        );
        return new JwtIssuerAuthenticationManagerResolver(managersByIssuer::get);
    }

    private AuthenticationManager authenticationManagerFor(String issuer, String realmMarker) {
        JwtDecoder decoder = lazyDecoder(issuer);
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> authoritiesFor(jwt, realmMarker));
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter);
        return provider::authenticate;
    }

    private static JwtDecoder lazyDecoder(String issuer) {
        return new JwtDecoder() {
            private volatile JwtDecoder delegate;

            @Override
            public Jwt decode(String token) {
                JwtDecoder current = delegate;
                if (current == null) {
                    synchronized (this) {
                        current = delegate;
                        if (current == null) {
                            current = delegate = NimbusJwtDecoder.withIssuerLocation(issuer).build();
                        }
                    }
                }
                return current.decode(token);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Collection<GrantedAuthority> authoritiesFor(Jwt jwt, String realmMarker) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_REALM_" + realmMarker));

        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null && realmAccess.get("roles") != null) {
            for (String role : (Collection<String>) realmAccess.get("roles")) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
        }
        return authorities;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationManagerResolver<HttpServletRequest> issuerAuthenticationManagerResolver) throws Exception {
        http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationManagerResolver(issuerAuthenticationManagerResolver));
        return http.build();
    }
}
```

- [ ] **Step 3: Write `SecurityConfigTest.java`**

Tests the pure authority-derivation logic directly — the full authenticated-request path (issuer resolution + `@PreAuthorize`) gets end-to-end MockMvc coverage in Task 8/9 once real controllers exist; there's nothing to point a full HTTP test at yet.

```java
package tz.co.nlolo.lifeplatform.iam;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Test
    void staffJwtWithRealmRolesGetsRoleAuthoritiesAndRealmMarker() {
        Jwt jwt = Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .claim("realm_access", Map.of("roles", List.of("ADMIN", "UNDERWRITER")))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();

        var authorities = tz.co.nlolo.lifeplatform.iam.infrastructure.SecurityConfigTestSupport
            .authoritiesFor(jwt, "STAFF");

        assertThat(authorities).extracting(a -> a.getAuthority())
            .containsExactlyInAnyOrder("ROLE_REALM_STAFF", "ROLE_ADMIN", "ROLE_UNDERWRITER");
    }

    @Test
    void jwtWithNoRealmAccessClaimStillGetsRealmMarker() {
        Jwt jwt = Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();

        var authorities = tz.co.nlolo.lifeplatform.iam.infrastructure.SecurityConfigTestSupport
            .authoritiesFor(jwt, "CUSTOMERS");

        assertThat(authorities).extracting(a -> a.getAuthority())
            .containsExactly("ROLE_REALM_CUSTOMERS");
    }
}
```

This test calls a package-visible `authoritiesFor` — `SecurityConfig`'s method is `private static`. Add a tiny test-support seam so the pure logic is testable without reflection: change `SecurityConfig.authoritiesFor` to package-private (drop `private`, keep `static`) and add, in the same `iam.infrastructure` package, a one-line delegator for the test:

```java
package tz.co.nlolo.lifeplatform.iam.infrastructure;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;

public final class SecurityConfigTestSupport {
    private SecurityConfigTestSupport() {}

    public static Collection<GrantedAuthority> authoritiesFor(Jwt jwt, String realmMarker) {
        return SecurityConfig.authoritiesFor(jwt, realmMarker);
    }
}
```

(Change `private static Collection<GrantedAuthority> authoritiesFor(...)` in `SecurityConfig.java` to just `static Collection<GrantedAuthority> authoritiesFor(...)` for this to compile.)

- [ ] **Step 4: Run the test and verify the app still boots under Docker Compose**

```bash
docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q test -Dtest=SecurityConfigTest
```

Expected: `BUILD SUCCESS`, both tests pass.

Then confirm adding Spring Security didn't break `docker compose up` (the exact concern the lazy-decoder design addresses):

```bash
docker build -f infra/app/Dockerfile -t lifeplatform:m1 .
cd infra && docker compose up -d && sleep 20
curl -sf http://localhost:8080/actuator/health
docker compose logs app --tail 50
docker compose down -v
cd ..
```

Expected: `{"status":"UP",...}`, and `app`'s logs show no repeated restarts or Keycloak-discovery-related stack traces. If `app` is crash-looping, check whether the failure happens BEFORE Keycloak's realms finish importing — that would mean the lazy-decoder pattern didn't actually defer the discovery call as intended, and needs a closer look at `NimbusJwtDecoder`'s actual laziness before proceeding.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/iam src/main/resources/application.yml \
        src/test/java/tz/co/nlolo/lifeplatform/iam
git commit -m "feat: add multi-issuer JWT resource server security configuration"
```

---

### Task 3: `refdata` module

**Files:**
- Create: `src/main/java/tz/co/nlolo/lifeplatform/refdata/domain/ReferenceCodeSet.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/refdata/infrastructure/ReferenceCodeSetRepository.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/refdata/api/{ReferenceCodeView,ReferenceDataApi}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/refdata/application/ReferenceDataApiImpl.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/refdata/ReferenceDataApiIntegrationTest.java`

**Interfaces:**
- Consumes: `db-migrations/refdata/V1__create_refdata_schema.sql` (existing), `MigrationTestSupport` (Task 1).
- Produces: `ReferenceDataApi.getCodes(String codeSetKey)` / `getValue(String codeSetKey, String jurisdiction)` — this is the exact call shape `docs/02-module-architecture.md` §3.3 already names (`ReferenceDataApi.getCodes("TZ_CONTESTABILITY_MONTHS")`) for future modules (`underwriting`, `policy`, `billing`) to consume once they're built.

- [ ] **Step 1: Write `ReferenceCodeSet.java`**

```java
package tz.co.nlolo.lifeplatform.refdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reference_code_set", schema = "refdata")
public class ReferenceCodeSet {

    @Id
    @GeneratedValue
    @Column(name = "reference_code_set_id")
    private UUID id;

    @Column(name = "code_set_key", nullable = false)
    private String codeSetKey;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "value", nullable = false)
    private String value;

    @Column(name = "jurisdiction")
    private String jurisdiction;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReferenceCodeSet() {}

    public String getCode() { return code; }
    public String getLabel() { return label; }
    public String getValue() { return value; }
    public String getJurisdiction() { return jurisdiction; }
}
```

- [ ] **Step 2: Write `ReferenceCodeSetRepository.java`**

```java
package tz.co.nlolo.lifeplatform.refdata.infrastructure;

import tz.co.nlolo.lifeplatform.refdata.domain.ReferenceCodeSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReferenceCodeSetRepository extends JpaRepository<ReferenceCodeSet, UUID> {
    List<ReferenceCodeSet> findByCodeSetKeyOrderByCode(String codeSetKey);
    List<ReferenceCodeSet> findByCodeSetKeyAndJurisdictionOrderByCode(String codeSetKey, String jurisdiction);
}
```

- [ ] **Step 3: Write `api/ReferenceCodeView.java` and `api/ReferenceDataApi.java`**

```java
package tz.co.nlolo.lifeplatform.refdata.api;

public record ReferenceCodeView(String code, String label, String value, String jurisdiction) {}
```

```java
package tz.co.nlolo.lifeplatform.refdata.api;

import java.util.List;

public interface ReferenceDataApi {
    List<ReferenceCodeView> getCodes(String codeSetKey);
    String getValue(String codeSetKey, String jurisdiction);
}
```

- [ ] **Step 4: Write `ReferenceDataApiImpl.java`**

```java
package tz.co.nlolo.lifeplatform.refdata.application;

import tz.co.nlolo.lifeplatform.refdata.api.ReferenceCodeView;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import tz.co.nlolo.lifeplatform.refdata.domain.ReferenceCodeSet;
import tz.co.nlolo.lifeplatform.refdata.infrastructure.ReferenceCodeSetRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * NON-PRODUCTION NOTICE: four seeded code_set_keys --
 * TZ_CONTESTABILITY_MONTHS, TZ_REINSTATEMENT_WINDOW_MONTHS,
 * TZ_SUSPENSION_TO_LAPSE_MONTHS, OFFLINE_RECEIPT_SLA_HOURS -- are explicit
 * PLACEHOLDER values (db-migrations/refdata/V1__create_refdata_schema.sql),
 * pending Legal/Product/Actuarial/Operational sign-off per
 * docs/03-aggregate-design.md Rev 2 §13 item 4 and
 * docs/06-database-schema.md §6 item 5. Callers must not treat values
 * returned for these four keys as statutorily or operationally confirmed.
 */
@Service
public class ReferenceDataApiImpl implements ReferenceDataApi {

    private final ReferenceCodeSetRepository repository;

    public ReferenceDataApiImpl(ReferenceCodeSetRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ReferenceCodeView> getCodes(String codeSetKey) {
        return repository.findByCodeSetKeyOrderByCode(codeSetKey).stream()
            .map(e -> new ReferenceCodeView(e.getCode(), e.getLabel(), e.getValue(), e.getJurisdiction()))
            .toList();
    }

    @Override
    public String getValue(String codeSetKey, String jurisdiction) {
        return repository.findByCodeSetKeyAndJurisdictionOrderByCode(codeSetKey, jurisdiction).stream()
            .filter(e -> "DEFAULT".equals(e.getCode()))
            .map(ReferenceCodeSet::getValue)
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException(
                "No refdata value for codeSetKey=" + codeSetKey + " jurisdiction=" + jurisdiction));
    }
}
```

- [ ] **Step 5: Write `ReferenceDataApiIntegrationTest.java`**

```java
package tz.co.nlolo.lifeplatform.refdata;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = Application.class)
class ReferenceDataApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigration() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/refdata/V1__create_refdata_schema.sql");
    }

    @Autowired
    private ReferenceDataApi referenceDataApi;

    @Test
    void returnsSeededPlaceholderValues() {
        assertThat(referenceDataApi.getValue("TZ_CONTESTABILITY_MONTHS", "TZ")).isEqualTo("24");
        assertThat(referenceDataApi.getValue("TZ_REINSTATEMENT_WINDOW_MONTHS", "TZ")).isEqualTo("12");
        assertThat(referenceDataApi.getValue("TZ_SUSPENSION_TO_LAPSE_MONTHS", "TZ")).isEqualTo("6");
        assertThat(referenceDataApi.getValue("OFFLINE_RECEIPT_SLA_HOURS", "TZ")).isEqualTo("24");
    }

    @Test
    void getCodesReturnsAllCodesForAKey() {
        assertThat(referenceDataApi.getCodes("TZ_CONTESTABILITY_MONTHS")).hasSize(1);
    }
}
```

- [ ] **Step 6: Run the test**

```bash
docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q test -Dtest=ReferenceDataApiIntegrationTest
```

Expected: `BUILD SUCCESS`. **This is the first Testcontainers-based test in this plan** — if it hangs or fails to connect rather than failing on assertion content, this is where the `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` fallback from Global Constraints applies. Try:

```bash
docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q test -Dtest=ReferenceDataApiIntegrationTest
```

Report which variant worked in your task report — it affects every remaining task's verification commands.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/refdata src/test/java/tz/co/nlolo/lifeplatform/refdata
git commit -m "feat: implement refdata module (ReferenceDataApi, seeded parameter access)"
```

---

### Task 4: `audit` module

**Files:**
- Create: `src/main/java/tz/co/nlolo/lifeplatform/audit/domain/{AuditLogEntry,FailedEvent}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/audit/infrastructure/{AuditLogRepository,FailedEventRepository,DomainEventAuditListener}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/audit/api/{EntityRef,DateRange,AuditEntryView,AuditApi}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/audit/application/AuditApiImpl.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/audit/DomainEventAuditListenerIntegrationTest.java`

**Interfaces:**
- Consumes: `DomainEventEnvelope<T>` (Task 1), `db-migrations/audit/V1__create_audit_schema.sql` (existing).
- Produces: a generic listener that persists every `DomainEventEnvelope<?>` published anywhere in the app — Task 6's `party` module (and every later module) depends on this existing first for its own event-reaches-audit tests to mean anything.

**Note on scope:** this task implements dead-lettering on write failure (the `failed_event` table gets a row), but **not** the exponential-backoff retry scheduler `docs/05-event-catalog.md` §4 describes — that scheduler needs a real retry-count-driven job runner, which nothing in M1's acceptance criteria requires yet (`docs/05-event-catalog.md` §7 item 2 already flags the dead-letter table's own schema and a Deliverable 7 observability tie-in as forward-looking items; both exist already — the schema from Deliverable 6, the `FailedEventBacklogGrowing` alert in `observability/alert-rules.yml` from Deliverable 7). Building the scheduler now would be scope beyond what any acceptance criterion asks for; flagged here rather than silently built or silently skipped.

- [ ] **Step 1: Write `AuditLogEntry.java`**

```java
package tz.co.nlolo.lifeplatform.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.IdClass;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "audit_log", schema = "audit")
@IdClass(AuditLogEntry.AuditLogEntryId.class)
public class AuditLogEntry {

    @Id
    @Column(name = "audit_log_id")
    private UUID auditLogId;

    @Id
    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "sequence_number")
    private Long sequenceNumber;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    protected AuditLogEntry() {}

    public AuditLogEntry(UUID auditLogId, UUID tenantId, UUID eventId, String eventType, int schemaVersion,
                          Long sequenceNumber, Instant occurredAt, Instant recordedAt, String payload) {
        this.auditLogId = auditLogId;
        this.tenantId = tenantId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.sequenceNumber = sequenceNumber;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
        this.payload = payload;
    }

    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getPayload() { return payload; }

    public static class AuditLogEntryId implements Serializable {
        private UUID auditLogId;
        private Instant occurredAt;

        public AuditLogEntryId() {}

        public AuditLogEntryId(UUID auditLogId, Instant occurredAt) {
            this.auditLogId = auditLogId;
            this.occurredAt = occurredAt;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AuditLogEntryId that)) return false;
            return Objects.equals(auditLogId, that.auditLogId) && Objects.equals(occurredAt, that.occurredAt);
        }

        @Override
        public int hashCode() { return Objects.hash(auditLogId, occurredAt); }
    }
}
```

- [ ] **Step 2: Write `FailedEvent.java`**

```java
package tz.co.nlolo.lifeplatform.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "failed_event", schema = "audit")
public class FailedEvent {

    @Id
    @GeneratedValue
    @Column(name = "failed_event_id")
    private UUID failedEventId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "consumer_module", nullable = false)
    private String consumerModule;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "first_failed_at", nullable = false)
    private Instant firstFailedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    protected FailedEvent() {}

    public FailedEvent(UUID tenantId, UUID eventId, String eventType, String consumerModule,
                        String failureReason, Instant firstFailedAt, String payload) {
        this.tenantId = tenantId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.consumerModule = consumerModule;
        this.failureReason = failureReason;
        this.retryCount = 0;
        this.firstFailedAt = firstFailedAt;
        this.payload = payload;
    }
}
```

- [ ] **Step 3: Write the two repositories**

```java
package tz.co.nlolo.lifeplatform.audit.infrastructure;

import tz.co.nlolo.lifeplatform.audit.domain.AuditLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, AuditLogEntry.AuditLogEntryId> {

    List<AuditLogEntry> findByTenantIdAndEventTypeAndOccurredAtBetween(
        UUID tenantId, String eventType, Instant from, Instant to);

    @Query(value = "SELECT * FROM audit.audit_log WHERE tenant_id = :tenantId " +
        "AND event_type LIKE :eventTypePrefix AND occurred_at BETWEEN :from AND :to " +
        "AND payload::text ILIKE :entityIdPattern ORDER BY occurred_at DESC", nativeQuery = true)
    List<AuditLogEntry> searchTrail(@Param("tenantId") UUID tenantId, @Param("eventTypePrefix") String eventTypePrefix,
                                     @Param("from") Instant from, @Param("to") Instant to,
                                     @Param("entityIdPattern") String entityIdPattern);
}
```

```java
package tz.co.nlolo.lifeplatform.audit.infrastructure;

import tz.co.nlolo.lifeplatform.audit.domain.FailedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FailedEventRepository extends JpaRepository<FailedEvent, UUID> {}
```

- [ ] **Step 4: Write `DomainEventAuditListener.java`**

```java
package tz.co.nlolo.lifeplatform.audit.infrastructure;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.audit.domain.AuditLogEntry;
import tz.co.nlolo.lifeplatform.audit.domain.FailedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Generic listener for every DomainEventEnvelope<?> published platform-wide
 * (docs/02-module-architecture.md §3.18) -- keyed on the envelope type, not on
 * per-event classes, so audit needs no dependency edge to any producer module.
 * AFTER_COMMIT per Deliverable 2 Rev 2 §2's mandatory transactional event
 * publishing convention: an event is only durable here once the producer's
 * own state change has committed.
 */
@Component
public class DomainEventAuditListener {

    private static final Logger log = LoggerFactory.getLogger(DomainEventAuditListener.class);

    private final AuditLogRepository auditLogRepository;
    private final FailedEventRepository failedEventRepository;
    private final ObjectMapper objectMapper;

    public DomainEventAuditListener(AuditLogRepository auditLogRepository,
                                     FailedEventRepository failedEventRepository,
                                     ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.failedEventRepository = failedEventRepository;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        try {
            String payloadJson = objectMapper.writeValueAsString(envelope.payload());
            auditLogRepository.save(new AuditLogEntry(
                UUID.randomUUID(), envelope.tenantId(), envelope.eventId(), envelope.eventType(),
                envelope.schemaVersion(), envelope.sequenceNumber(), envelope.occurredAt(), Instant.now(), payloadJson));
        } catch (Exception e) {
            log.error("Failed to persist audit_log entry for event {}", envelope.eventType(), e);
            recordFailure(envelope, e);
        }
    }

    private void recordFailure(DomainEventEnvelope<?> envelope, Exception cause) {
        try {
            String payloadJson = objectMapper.writeValueAsString(envelope.payload());
            failedEventRepository.save(new FailedEvent(
                envelope.tenantId(), envelope.eventId(), envelope.eventType(), "audit",
                cause.getMessage(), Instant.now(), payloadJson));
        } catch (Exception dlqFailure) {
            log.error("Failed to write dead-letter failed_event for event {} -- not recorded anywhere further",
                envelope.eventType(), dlqFailure);
        }
    }
}
```

- [ ] **Step 5: Write `api/EntityRef.java`, `api/DateRange.java`, `api/AuditEntryView.java`, `api/AuditApi.java`**

```java
package tz.co.nlolo.lifeplatform.audit.api;

public record EntityRef(String entityType, String entityId) {}
```

```java
package tz.co.nlolo.lifeplatform.audit.api;

import java.time.Instant;

public record DateRange(Instant from, Instant to) {}
```

```java
package tz.co.nlolo.lifeplatform.audit.api;

import java.time.Instant;
import java.util.UUID;

public record AuditEntryView(UUID eventId, String eventType, Instant occurredAt, String payloadJson) {}
```

```java
package tz.co.nlolo.lifeplatform.audit.api;

import java.util.List;

public interface AuditApi {
    List<AuditEntryView> getTrail(EntityRef entity, DateRange range);
}
```

- [ ] **Step 6: Write `AuditApiImpl.java`**

```java
package tz.co.nlolo.lifeplatform.audit.application;

import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.api.AuditApi;
import tz.co.nlolo.lifeplatform.audit.api.AuditEntryView;
import tz.co.nlolo.lifeplatform.audit.api.DateRange;
import tz.co.nlolo.lifeplatform.audit.api.EntityRef;
import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * getTrail's entity search is a deliberately simple V1: audit_log has no
 * structured "which entity does this row concern" column (only tenant_id,
 * event_type, and a JSONB payload whose shape varies per event type) --
 * matching entityId as a substring of the JSONB payload's text form is
 * correct and testable without inventing a schema change docs/06 never
 * specified. Not indexed/optimized; fine for M1 since nothing calls this yet.
 */
@Service
public class AuditApiImpl implements AuditApi {

    private final AuditLogRepository repository;

    public AuditApiImpl(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<AuditEntryView> getTrail(EntityRef entity, DateRange range) {
        String eventTypePrefix = entity.entityType() + ".%";
        String entityIdPattern = "%" + entity.entityId() + "%";
        return repository.searchTrail(TenantContext.get(), eventTypePrefix, range.from(), range.to(), entityIdPattern)
            .stream()
            .map(e -> new AuditEntryView(e.getEventId(), e.getEventType(), e.getOccurredAt(), e.getPayload()))
            .toList();
    }
}
```

- [ ] **Step 7: Write `DomainEventAuditListenerIntegrationTest.java`**

`@TransactionalEventListener(AFTER_COMMIT)` only fires on a REAL commit — the test method itself must not be `@Transactional` (Spring's test framework auto-rolls-back `@Transactional` test methods by default, which would mean AFTER_COMMIT never fires). A small helper `@Service` with its own `@Transactional` method does the real commit; by default, Spring's transaction-synchronization AFTER_COMMIT callbacks run synchronously in the committing thread — no polling/`Awaitility` needed.

```java
package tz.co.nlolo.lifeplatform.audit;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = Application.class)
class DomainEventAuditListenerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigration() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @Autowired
    private EventPublishingProbe probe;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void publishedEventLandsInAuditLogAfterCommit() {
        UUID tenantId = UUID.randomUUID();
        Instant before = Instant.now();

        probe.publishAndCommit(new DomainEventEnvelope<>(
            UUID.randomUUID(), "test.SyntheticEvent", 1, tenantId, before, null, Map.of("hello", "world")));

        List<?> rows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "test.SyntheticEvent", before.minusSeconds(5), before.plusSeconds(5));
        assertThat(rows).hasSize(1);
    }

    @Service
    static class EventPublishingProbe {
        private final ApplicationEventPublisher publisher;

        EventPublishingProbe(ApplicationEventPublisher publisher) {
            this.publisher = publisher;
        }

        @Transactional
        public void publishAndCommit(DomainEventEnvelope<?> envelope) {
            publisher.publishEvent(envelope);
        }
    }
}
```

- [ ] **Step 8: Run the test**

```bash
docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q test -Dtest=DomainEventAuditListenerIntegrationTest
```

Expected: `BUILD SUCCESS`. This directly proves M1's third acceptance criterion ("audit_log receives a row for a synthetic test event end-to-end").

- [ ] **Step 9: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/audit src/test/java/tz/co/nlolo/lifeplatform/audit
git commit -m "feat: implement audit module (generic event listener, WORM audit_log writer)"
```

---

### Task 5: `document` module

**Files:**
- Create: `src/main/java/tz/co/nlolo/lifeplatform/document/domain/DocumentRecord.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/document/infrastructure/{DocumentRecordRepository,MinioClientConfig,MinioDocumentStorage,DocumentStorageException}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/document/api/{DocumentType,DocumentMetadataView,DocumentApi}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/document/application/DocumentApiImpl.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/document/DocumentApiIntegrationTest.java`

**Interfaces:**
- Consumes: `db-migrations/document/V1__create_document_schema.sql` (existing), `minio.*` properties (Task 2, Step 1), `MigrationTestSupport`/`TenantContext`/`DomainEventEnvelope` (Task 1).
- Produces: `DocumentApi.upload/download/getMetadata` — `party`'s KYC evidence flow (Task 6) references a `DocumentRef` this module issues, per `docs/02-module-architecture.md`'s `party → document` dependency edge (already declared in M0).

- [ ] **Step 1: Write `api/DocumentType.java`, `api/DocumentMetadataView.java`, `api/DocumentApi.java`**

```java
package tz.co.nlolo.lifeplatform.document.api;

public enum DocumentType { KYC_EVIDENCE, POLICY_DOCUMENT, CLAIM_EVIDENCE, SIGNED_FORM }
```

```java
package tz.co.nlolo.lifeplatform.document.api;

import java.time.Instant;

public record DocumentMetadataView(String documentRef, String ownerContext, DocumentType documentType,
                                    String uploadedBy, Instant uploadedAt) {}
```

```java
package tz.co.nlolo.lifeplatform.document.api;

import java.io.InputStream;

public interface DocumentApi {
    String upload(String ownerContext, DocumentType documentType, String uploadedBy,
                  InputStream content, long contentLength, String contentType);
    byte[] download(String documentRef);
    DocumentMetadataView getMetadata(String documentRef);
}
```

- [ ] **Step 2: Write `domain/DocumentRecord.java`**

```java
package tz.co.nlolo.lifeplatform.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_record", schema = "document")
public class DocumentRecord {

    @Id
    @Column(name = "document_ref")
    private String documentRef;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "owner_context", nullable = false)
    private String ownerContext;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false)
    private DocumentType documentType;

    @Column(name = "uploaded_by")
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected DocumentRecord() {}

    public DocumentRecord(String documentRef, UUID tenantId, String ownerContext, DocumentType documentType,
                           String uploadedBy, Instant uploadedAt) {
        this.documentRef = documentRef;
        this.tenantId = tenantId;
        this.ownerContext = ownerContext;
        this.documentType = documentType;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
    }

    public String getDocumentRef() { return documentRef; }
    public String getOwnerContext() { return ownerContext; }
    public DocumentType getDocumentType() { return documentType; }
    public String getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
}
```

- [ ] **Step 3: Write `infrastructure/DocumentRecordRepository.java`, `MinioClientConfig.java`, `DocumentStorageException.java`, `MinioDocumentStorage.java`**

```java
package tz.co.nlolo.lifeplatform.document.infrastructure;

import tz.co.nlolo.lifeplatform.document.domain.DocumentRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRecordRepository extends JpaRepository<DocumentRecord, String> {}
```

```java
package tz.co.nlolo.lifeplatform.document.infrastructure;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioClientConfig {

    @Bean
    public MinioClient minioClient(
            @Value("${minio.endpoint}") String endpoint,
            @Value("${minio.access-key}") String accessKey,
            @Value("${minio.secret-key}") String secretKey) {
        return MinioClient.builder()
            .endpoint(endpoint)
            .credentials(accessKey, secretKey)
            .build();
    }
}
```

```java
package tz.co.nlolo.lifeplatform.document.infrastructure;

public class DocumentStorageException extends RuntimeException {
    public DocumentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

```java
package tz.co.nlolo.lifeplatform.document.infrastructure;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Bucket routing matches infra/docker-compose.yml's minio-init job, which
 * pre-creates exactly two buckets: policy-documents and kyc-evidence.
 */
@Component
public class MinioDocumentStorage {

    private static final String KYC_BUCKET = "kyc-evidence";
    private static final String GENERAL_BUCKET = "policy-documents";

    private final MinioClient minioClient;

    public MinioDocumentStorage(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    public void put(String objectKey, DocumentType documentType, InputStream content, long contentLength, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucketFor(documentType))
                .object(objectKey)
                .stream(content, contentLength, -1)
                .contentType(contentType)
                .build());
        } catch (Exception e) {
            throw new DocumentStorageException("Failed to upload document " + objectKey, e);
        }
    }

    public byte[] get(String objectKey, DocumentType documentType) {
        try (InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucketFor(documentType))
                .object(objectKey)
                .build());
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            stream.transferTo(buffer);
            return buffer.toByteArray();
        } catch (Exception e) {
            throw new DocumentStorageException("Failed to download document " + objectKey, e);
        }
    }

    private static String bucketFor(DocumentType documentType) {
        return documentType == DocumentType.KYC_EVIDENCE ? KYC_BUCKET : GENERAL_BUCKET;
    }
}
```

- [ ] **Step 4: Write `application/DocumentApiImpl.java`**

```java
package tz.co.nlolo.lifeplatform.document.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentMetadataView;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import tz.co.nlolo.lifeplatform.document.domain.DocumentRecord;
import tz.co.nlolo.lifeplatform.document.infrastructure.DocumentRecordRepository;
import tz.co.nlolo.lifeplatform.document.infrastructure.MinioDocumentStorage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class DocumentApiImpl implements DocumentApi {

    private final DocumentRecordRepository repository;
    private final MinioDocumentStorage storage;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentApiImpl(DocumentRecordRepository repository, MinioDocumentStorage storage,
                            ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.storage = storage;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public String upload(String ownerContext, DocumentType documentType, String uploadedBy,
                          InputStream content, long contentLength, String contentType) {
        String documentRef = UUID.randomUUID().toString();
        storage.put(documentRef, documentType, content, contentLength, contentType);

        UUID tenantId = TenantContext.get();
        repository.save(new DocumentRecord(documentRef, tenantId, ownerContext, documentType, uploadedBy, Instant.now()));

        eventPublisher.publishEvent(DomainEventEnvelope.of("document.DocumentUploaded", tenantId,
            Map.of("documentRef", documentRef, "ownerContext", ownerContext, "documentType", documentType.name())));

        return documentRef;
    }

    @Override
    public byte[] download(String documentRef) {
        DocumentRecord record = findOrThrow(documentRef);
        return storage.get(documentRef, record.getDocumentType());
    }

    @Override
    public DocumentMetadataView getMetadata(String documentRef) {
        DocumentRecord record = findOrThrow(documentRef);
        return new DocumentMetadataView(record.getDocumentRef(), record.getOwnerContext(), record.getDocumentType(),
            record.getUploadedBy(), record.getUploadedAt());
    }

    private DocumentRecord findOrThrow(String documentRef) {
        return repository.findById(documentRef)
            .orElseThrow(() -> new NoSuchElementException("No document found for ref " + documentRef));
    }
}
```

- [ ] **Step 5: Write `DocumentApiIntegrationTest.java`**

```java
package tz.co.nlolo.lifeplatform.document;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = Application.class)
class DocumentApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("minio.endpoint", MINIO::getS3URL);
        registry.add("minio.access-key", MINIO::getUserName);
        registry.add("minio.secret-key", MINIO::getPassword);
    }

    @BeforeAll
    static void applyMigrationAndCreateBuckets() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/document/V1__create_document_schema.sql");

        MinioClient client = MinioClient.builder()
            .endpoint(MINIO.getS3URL())
            .credentials(MINIO.getUserName(), MINIO.getPassword())
            .build();
        client.makeBucket(MakeBucketArgs.builder().bucket("policy-documents").build());
        client.makeBucket(MakeBucketArgs.builder().bucket("kyc-evidence").build());
    }

    @Autowired
    private DocumentApi documentApi;

    @BeforeEach
    void setTenant() {
        TenantContext.set(UUID.randomUUID());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void uploadAndDownloadRoundTrips() {
        byte[] originalContent = "kyc-scan-bytes".getBytes();

        String documentRef = documentApi.upload("party:test-party-id", DocumentType.KYC_EVIDENCE, "test-uploader",
            new ByteArrayInputStream(originalContent), originalContent.length, "application/octet-stream");

        byte[] downloaded = documentApi.download(documentRef);
        assertThat(downloaded).isEqualTo(originalContent);

        var metadata = documentApi.getMetadata(documentRef);
        assertThat(metadata.ownerContext()).isEqualTo("party:test-party-id");
        assertThat(metadata.documentType()).isEqualTo(DocumentType.KYC_EVIDENCE);
    }
}
```

If `org.testcontainers.containers.MinIOContainer`'s exact method names (`getS3URL()`, `getUserName()`, `getPassword()`) differ slightly in the resolved Testcontainers version, adjust to match — the intent (spin up a real MinIO container, point the app's `minio.*` properties at it, create the same two buckets `minio-init` creates in `infra/docker-compose.yml`) is what matters.

- [ ] **Step 6: Run the test**

```bash
docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q test -Dtest=DocumentApiIntegrationTest
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/document src/test/java/tz/co/nlolo/lifeplatform/document
git commit -m "feat: implement document module (MinIO-backed storage, metadata persistence)"
```

---

### Task 6: `party` module — aggregates, persistence, and internal API

**Files:**
- Create: `src/main/java/tz/co/nlolo/lifeplatform/party/domain/{Party,KycRecord,GroupMembership}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/party/infrastructure/{PartyRepository,KycRecordRepository,GroupMembershipRepository}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/party/api/{PartyType,KycStatus,PartyView,GroupMembershipView,PartyNotFoundException,DuplicateRegistrationNumberException,PartyApi}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/party/application/PartyApiImpl.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/party/PartyApiIntegrationTest.java`

**Interfaces:**
- Consumes: `db-migrations/party/V1__create_party_schema.sql` (existing), `TenantContext`/`DomainEventEnvelope` (Task 1), audit's listener (Task 4, for the end-to-end event test).
- Produces: `PartyApi` — Task 8's REST controller calls every method here; Task 7's RLS test uses `registerIndividual` to create rows.

**Note on scope:** `party.party_relationship` (the table) is **not** given an entity/repository in this task — see Global Constraints. Only `party`, `kyc_record`, `group_membership` are implemented, matching what `openapi-party.yaml` and the M1 acceptance criteria actually exercise.

- [ ] **Step 1: Write `api/PartyType.java`, `api/KycStatus.java`**

```java
package tz.co.nlolo.lifeplatform.party.api;

public enum PartyType { INDIVIDUAL, CORPORATE, GROUP }
```

```java
package tz.co.nlolo.lifeplatform.party.api;

public enum KycStatus { PENDING, VERIFIED, REJECTED }
```

- [ ] **Step 2: Write `domain/Party.java`**

```java
package tz.co.nlolo.lifeplatform.party.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "party", schema = "party")
public class Party {

    @Id
    @GeneratedValue
    @Column(name = "party_id")
    private UUID partyId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "party_type", nullable = false)
    private PartyType partyType;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "registration_number")
    private String registrationNumber;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "email")
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false)
    private KycStatus kycStatus;

    @Column(name = "kyc_verified_at")
    private Instant kycVerifiedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected Party() {}

    public static Party newIndividual(UUID tenantId, String displayName, LocalDate dateOfBirth,
                                       String phoneNumber, String email, String createdBy) {
        Party party = new Party();
        party.tenantId = tenantId;
        party.partyType = PartyType.INDIVIDUAL;
        party.displayName = displayName;
        party.dateOfBirth = dateOfBirth;
        party.phoneNumber = phoneNumber;
        party.email = email;
        party.kycStatus = KycStatus.PENDING;
        party.createdAt = Instant.now();
        party.createdBy = createdBy;
        return party;
    }

    public static Party newCorporate(UUID tenantId, String displayName, String registrationNumber,
                                      String phoneNumber, String email, String createdBy) {
        Party party = new Party();
        party.tenantId = tenantId;
        party.partyType = PartyType.CORPORATE;
        party.displayName = displayName;
        party.registrationNumber = registrationNumber;
        party.phoneNumber = phoneNumber;
        party.email = email;
        party.kycStatus = KycStatus.PENDING;
        party.createdAt = Instant.now();
        party.createdBy = createdBy;
        return party;
    }

    public void applyKycDecision(KycStatus newStatus, String decidedBy) {
        this.kycStatus = newStatus;
        this.kycVerifiedAt = Instant.now();
        this.updatedAt = Instant.now();
        this.updatedBy = decidedBy;
    }

    public UUID getPartyId() { return partyId; }
    public UUID getTenantId() { return tenantId; }
    public PartyType getPartyType() { return partyType; }
    public String getDisplayName() { return displayName; }
    public KycStatus getKycStatus() { return kycStatus; }
    public String getRegistrationNumber() { return registrationNumber; }
}
```

- [ ] **Step 3: Write `domain/KycRecord.java`**

```java
package tz.co.nlolo.lifeplatform.party.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kyc_record", schema = "party")
public class KycRecord {

    @Id
    @GeneratedValue
    @Column(name = "kyc_record_id")
    private UUID kycRecordId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "party_id", nullable = false)
    private UUID partyId;

    @Column(name = "evidence_document_ref", nullable = false)
    private String evidenceDocumentRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private KycStatus status;

    @Column(name = "verified_by")
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected KycRecord() {}

    public KycRecord(UUID tenantId, UUID partyId, String evidenceDocumentRef, KycStatus status, String verifiedBy) {
        this.tenantId = tenantId;
        this.partyId = partyId;
        this.evidenceDocumentRef = evidenceDocumentRef;
        this.status = status;
        this.verifiedBy = verifiedBy;
        this.verifiedAt = Instant.now();
        this.createdAt = Instant.now();
    }
}
```

- [ ] **Step 4: Write `domain/GroupMembership.java`**

```java
package tz.co.nlolo.lifeplatform.party.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Deliverable 3 Rev 2 §7.1 (Pa1): its own entity/repository, paginated,
 * queried independently -- NEVER loaded as a collection on Party. A
 * 10,000-member SACCO group would blow up the aggregate-size principle
 * everything else in this design relies on.
 */
@Entity
@Table(name = "group_membership", schema = "party")
public class GroupMembership {

    @Id
    @GeneratedValue
    @Column(name = "group_membership_id")
    private UUID groupMembershipId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "group_party_id", nullable = false)
    private UUID groupPartyId;

    @Column(name = "member_party_id", nullable = false)
    private UUID memberPartyId;

    @Column(name = "join_date", nullable = false)
    private LocalDate joinDate;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GroupMembership() {}

    public GroupMembership(UUID tenantId, UUID groupPartyId, UUID memberPartyId) {
        this.tenantId = tenantId;
        this.groupPartyId = groupPartyId;
        this.memberPartyId = memberPartyId;
        this.joinDate = LocalDate.now();
        this.status = "ACTIVE";
        this.createdAt = Instant.now();
    }

    public UUID getGroupPartyId() { return groupPartyId; }
    public UUID getMemberPartyId() { return memberPartyId; }
    public LocalDate getJoinDate() { return joinDate; }
    public String getStatus() { return status; }
}
```

- [ ] **Step 5: Write the three repositories**

```java
package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.party.domain.Party;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PartyRepository extends JpaRepository<Party, UUID> {
    Optional<Party> findByTenantIdAndRegistrationNumber(UUID tenantId, String registrationNumber);
}
```

```java
package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.party.domain.KycRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface KycRecordRepository extends JpaRepository<KycRecord, UUID> {}
```

```java
package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.party.domain.GroupMembership;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface GroupMembershipRepository extends JpaRepository<GroupMembership, UUID> {
    Page<GroupMembership> findByGroupPartyIdAndStatus(UUID groupPartyId, String status, Pageable pageable);
}
```

- [ ] **Step 6: Write the remaining `api/*` types**

```java
package tz.co.nlolo.lifeplatform.party.api;

import java.util.UUID;

public record PartyView(UUID partyId, PartyType partyType, KycStatus kycStatus, String displayName) {}
```

```java
package tz.co.nlolo.lifeplatform.party.api;

import java.time.LocalDate;
import java.util.UUID;

public record GroupMembershipView(UUID memberPartyId, LocalDate joinDate, String status) {}
```

```java
package tz.co.nlolo.lifeplatform.party.api;

import java.util.UUID;

public class PartyNotFoundException extends RuntimeException {
    public PartyNotFoundException(UUID partyId) {
        super("No party found for id " + partyId);
    }
}
```

```java
package tz.co.nlolo.lifeplatform.party.api;

public class DuplicateRegistrationNumberException extends RuntimeException {
    public DuplicateRegistrationNumberException(String registrationNumber) {
        super("A corporate party with registration number " + registrationNumber + " already exists for this tenant");
    }
}
```

```java
package tz.co.nlolo.lifeplatform.party.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.UUID;

public interface PartyApi {
    PartyView registerIndividual(String fullName, LocalDate dateOfBirth, String phoneNumber, String email, String registeredBy);
    PartyView registerCorporate(String registeredName, String registrationNumber, String phoneNumber, String email, String registeredBy);
    PartyView getParty(UUID partyId);
    void submitKycEvidence(UUID partyId, KycStatus status, String evidenceDocumentRef, String verifiedBy);
    void addGroupMember(UUID groupPartyId, UUID memberPartyId);
    Page<GroupMembershipView> listGroupMembers(UUID groupPartyId, Pageable pageable);
}
```

- [ ] **Step 7: Write `application/PartyApiImpl.java`**

```java
package tz.co.nlolo.lifeplatform.party.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.DuplicateRegistrationNumberException;
import tz.co.nlolo.lifeplatform.party.api.GroupMembershipView;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.party.domain.GroupMembership;
import tz.co.nlolo.lifeplatform.party.domain.KycRecord;
import tz.co.nlolo.lifeplatform.party.domain.Party;
import tz.co.nlolo.lifeplatform.party.infrastructure.GroupMembershipRepository;
import tz.co.nlolo.lifeplatform.party.infrastructure.KycRecordRepository;
import tz.co.nlolo.lifeplatform.party.infrastructure.PartyRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Phone validation: docs/04-api-contracts.md §5 -- Tanzanian E.164 pattern
 * (+255 + 9 digits), enforced at the API boundary. Enforced here (not only in
 * Task 8's REST layer) so it holds for every caller, internal or external.
 */
@Service
public class PartyApiImpl implements PartyApi {

    private static final Pattern TZ_PHONE_PATTERN = Pattern.compile("^\\+255\\d{9}$");

    private final PartyRepository partyRepository;
    private final KycRecordRepository kycRecordRepository;
    private final GroupMembershipRepository groupMembershipRepository;
    private final ApplicationEventPublisher eventPublisher;

    public PartyApiImpl(PartyRepository partyRepository, KycRecordRepository kycRecordRepository,
                         GroupMembershipRepository groupMembershipRepository, ApplicationEventPublisher eventPublisher) {
        this.partyRepository = partyRepository;
        this.kycRecordRepository = kycRecordRepository;
        this.groupMembershipRepository = groupMembershipRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public PartyView registerIndividual(String fullName, LocalDate dateOfBirth, String phoneNumber, String email, String registeredBy) {
        validatePhone(phoneNumber);
        UUID tenantId = TenantContext.get();
        Party party = partyRepository.save(Party.newIndividual(tenantId, fullName, dateOfBirth, phoneNumber, email, registeredBy));
        publishRegistered(party);
        return toView(party);
    }

    @Override
    @Transactional
    public PartyView registerCorporate(String registeredName, String registrationNumber, String phoneNumber, String email, String registeredBy) {
        validatePhone(phoneNumber);
        UUID tenantId = TenantContext.get();
        if (partyRepository.findByTenantIdAndRegistrationNumber(tenantId, registrationNumber).isPresent()) {
            throw new DuplicateRegistrationNumberException(registrationNumber);
        }
        Party party = partyRepository.save(Party.newCorporate(tenantId, registeredName, registrationNumber, phoneNumber, email, registeredBy));
        publishRegistered(party);
        return toView(party);
    }

    @Override
    public PartyView getParty(UUID partyId) {
        return toView(findPartyOrThrow(partyId));
    }

    @Override
    @Transactional
    public void submitKycEvidence(UUID partyId, KycStatus status, String evidenceDocumentRef, String verifiedBy) {
        Party party = findPartyOrThrow(partyId);
        KycStatus previousStatus = party.getKycStatus();

        kycRecordRepository.save(new KycRecord(party.getTenantId(), partyId, evidenceDocumentRef, status, verifiedBy));
        party.applyKycDecision(status, verifiedBy);
        partyRepository.save(party);

        eventPublisher.publishEvent(DomainEventEnvelope.of("party.PartyKycStatusChanged", party.getTenantId(),
            Map.of("partyId", partyId, "previousStatus", previousStatus.name(), "newStatus", status.name(),
                   "evidenceDocumentRef", evidenceDocumentRef)));
    }

    @Override
    @Transactional
    public void addGroupMember(UUID groupPartyId, UUID memberPartyId) {
        Party group = findPartyOrThrow(groupPartyId);
        findPartyOrThrow(memberPartyId);
        groupMembershipRepository.save(new GroupMembership(group.getTenantId(), groupPartyId, memberPartyId));
    }

    @Override
    public Page<GroupMembershipView> listGroupMembers(UUID groupPartyId, Pageable pageable) {
        return groupMembershipRepository.findByGroupPartyIdAndStatus(groupPartyId, "ACTIVE", pageable)
            .map(m -> new GroupMembershipView(m.getMemberPartyId(), m.getJoinDate(), m.getStatus()));
    }

    private void publishRegistered(Party party) {
        eventPublisher.publishEvent(DomainEventEnvelope.of("party.PartyRegistered", party.getTenantId(),
            Map.of("partyId", party.getPartyId(), "partyType", party.getPartyType().name())));
    }

    private Party findPartyOrThrow(UUID partyId) {
        return partyRepository.findById(partyId).orElseThrow(() -> new PartyNotFoundException(partyId));
    }

    private static void validatePhone(String phoneNumber) {
        if (phoneNumber != null && !TZ_PHONE_PATTERN.matcher(phoneNumber).matches()) {
            throw new IllegalArgumentException("Phone number must match the Tanzanian E.164 pattern +255XXXXXXXXX");
        }
    }

    private static PartyView toView(Party party) {
        return new PartyView(party.getPartyId(), party.getPartyType(), party.getKycStatus(), party.getDisplayName());
    }
}
```

- [ ] **Step 8: Write `PartyApiIntegrationTest.java`**

```java
package tz.co.nlolo.lifeplatform.party;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import tz.co.nlolo.lifeplatform.party.api.DuplicateRegistrationNumberException;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = Application.class)
class PartyApiIntegrationTest {

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
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @Autowired
    private PartyApi partyApi;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private UUID tenantId;

    @BeforeEach
    void setTenant() {
        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void registeringAnIndividualPublishesEventThatReachesAuditLog() {
        Instant before = Instant.now();

        var view = partyApi.registerIndividual("Amina Hassan", LocalDate.of(1990, 5, 12),
            "+255712345678", "amina@example.tz", "test-agent");

        assertThat(view.partyId()).isNotNull();
        assertThat(view.displayName()).isEqualTo("Amina Hassan");

        List<?> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "party.PartyRegistered", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
    }

    @Test
    void duplicateCorporateRegistrationNumberIsRejected() {
        partyApi.registerCorporate("Acme SACCO", "REG-001", "+255712345000", "acme@example.tz", "test-agent");

        Assertions.assertThrows(DuplicateRegistrationNumberException.class,
            () -> partyApi.registerCorporate("Acme SACCO Duplicate", "REG-001", "+255712345001", "acme2@example.tz", "test-agent"));
    }

    @Test
    void invalidPhoneNumberIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> partyApi.registerIndividual("Bad Phone", LocalDate.of(1990, 1, 1), "0712345678", null, "test-agent"));
    }
}
```

- [ ] **Step 9: Run the tests**

```bash
docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q test -Dtest=PartyApiIntegrationTest
```

Expected: `BUILD SUCCESS`, all 3 tests pass. The first test is a real (not synthetic) proof of `party → audit` event flow, strengthening M1's third acceptance criterion beyond Task 4's isolated listener test.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/party/domain src/main/java/tz/co/nlolo/lifeplatform/party/infrastructure/PartyRepository.java \
        src/main/java/tz/co/nlolo/lifeplatform/party/infrastructure/KycRecordRepository.java \
        src/main/java/tz/co/nlolo/lifeplatform/party/infrastructure/GroupMembershipRepository.java \
        src/main/java/tz/co/nlolo/lifeplatform/party/api src/main/java/tz/co/nlolo/lifeplatform/party/application \
        src/test/java/tz/co/nlolo/lifeplatform/party/PartyApiIntegrationTest.java
git commit -m "feat: implement party module aggregates, persistence, and internal API"
```

---

### Task 7: Tenant-isolation DB wiring + automated Row-Level Security smoke test

**Files:**
- Create: `src/main/java/tz/co/nlolo/lifeplatform/TenantAwareDataSource.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/TenantDataSourceConfig.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/RowLevelSecurityIntegrationTest.java`

**Interfaces:**
- Consumes: `TenantContext` (Task 1), `party.api.PartyApi` (Task 6, used to create real rows through the app's own persistence path — not raw SQL — so the test proves the wrapper works end-to-end, not just in isolation).
- Produces: the mechanism every RLS policy in every module's migration depends on (`docs/06-database-schema.md` §2: `USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)`).

This is M1's second acceptance criterion, automated: "the Deliverable 6 RLS smoke test (insert as two tenants, query as `app_role` with `SET ROLE`, confirm exactly one tenant's rows visible)."

- [ ] **Step 1: Write `TenantAwareDataSource.java`**

```java
package tz.co.nlolo.lifeplatform;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Sets the Postgres session variable app.current_tenant_id on every
 * connection this application acquires, read from TenantContext -- the
 * mechanism docs/06-database-schema.md §5 describes as "typically via a
 * connection-acquisition interceptor." Every RLS policy in every module's
 * migration depends on this being set before that connection issues any
 * query.
 *
 * Uses getOrNull(), not get(): connections acquired outside any
 * tenant-scoped operation (actuator health checks, boot-time Hibernate
 * metadata validation, refdata's non-tenant-scoped queries) must not crash --
 * they simply don't get the session variable set, which means RLS-protected
 * tables show zero rows to that connection (fail-closed, never fail-open).
 *
 * String concatenation into the SET statement is safe here specifically
 * because TenantContext only ever holds a UUID -- UUID#toString() can only
 * ever produce its fixed 36-character hex-and-dash form, so there is no
 * free-text injection surface. This would NOT be safe with an untyped
 * identifier.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return applyTenantContext(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return applyTenantContext(super.getConnection(username, password));
    }

    private Connection applyTenantContext(Connection connection) throws SQLException {
        UUID tenantId = TenantContext.getOrNull();
        if (tenantId != null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET app.current_tenant_id = '" + tenantId + "'");
            }
        }
        return connection;
    }
}
```

- [ ] **Step 2: Write `TenantDataSourceConfig.java`**

```java
package tz.co.nlolo.lifeplatform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Wraps Spring Boot's autoconfigured DataSource. Marking this bean @Primary
 * means JPA's EntityManagerFactory autoconfiguration (and anything else that
 * autowires DataSource by type) picks this wrapped instance over the
 * original -- a standard Spring Boot technique for layering behavior onto an
 * autoconfigured bean without disabling that autoconfiguration.
 */
@Configuration
public class TenantDataSourceConfig {

    @Bean
    @Primary
    public DataSource tenantAwareDataSource(DataSource dataSource) {
        return new TenantAwareDataSource(dataSource);
    }
}
```

- [ ] **Step 3: Write `RowLevelSecurityIntegrationTest.java`**

```java
package tz.co.nlolo.lifeplatform;

import tz.co.nlolo.lifeplatform.party.api.PartyApi;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Automates the Deliverable 6 §1 point-4 smoke test: insert as two tenants
 * (through the app's own PartyApi -- proving TenantAwareDataSource actually
 * threads TenantContext into persisted rows, not a hand-crafted row), then
 * query as a genuinely restricted app_role via SET ROLE (never the Postgres
 * superuser, which always bypasses RLS regardless of policy -- the exact
 * mistake Deliverable 6's own first validation attempt made), and confirm
 * exactly one tenant's rows are visible.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class RowLevelSecurityIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrationAndCreateAppRole() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql");

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            // Mirrors infra/postgres/init/01-create-app-role.sql.template's load-bearing
            // NOSUPERUSER NOBYPASSRLS attributes -- either one absent silently voids
            // every RLS policy platform-wide (Deliverable 6 §1).
            statement.execute("CREATE ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD 'test_app_role_password'");
            statement.execute("GRANT USAGE ON SCHEMA party TO app_role");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA party TO app_role");
        }
    }

    @Autowired
    private PartyApi partyApi;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void appRoleOnlySeesItsOwnTenantsRowsUnderRls() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        TenantContext.set(tenantA);
        partyApi.registerIndividual("Tenant A Person", LocalDate.of(1985, 1, 1), "+255700000001", null, "test");

        TenantContext.set(tenantB);
        partyApi.registerIndividual("Tenant B Person", LocalDate.of(1985, 1, 1), "+255700000002", null, "test");

        // Superuser sees both -- confirms the data really is there for both tenants,
        // and is the exact mistake Deliverable 6 §1's first validation attempt made
        // (querying as superuser always bypasses RLS regardless of policy).
        try (Connection superuserConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = superuserConnection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM party.party")) {
            resultSet.next();
            assertThat(resultSet.getInt(1)).isEqualTo(2);
        }

        // app_role, restricted to tenant A via SET ROLE + the session variable every
        // RLS policy checks, must see exactly tenant A's one row.
        try (Connection restrictedConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = restrictedConnection.createStatement()) {
            statement.execute("SET ROLE app_role");
            statement.execute("SET app.current_tenant_id = '" + tenantA + "'");
            try (ResultSet resultSet = statement.executeQuery("SELECT display_name FROM party.party")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("Tenant A Person");
                assertThat(resultSet.next()).isFalse();
            }
        }
    }
}
```

- [ ] **Step 4: Run the test**

```bash
docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q test -Dtest=RowLevelSecurityIntegrationTest
```

Expected: `BUILD SUCCESS`. This directly proves M1's second acceptance criterion.

- [ ] **Step 5: Confirm the wrapped DataSource didn't break `docker compose up`**

```bash
docker build -f infra/app/Dockerfile -t lifeplatform:m1 .
cd infra && docker compose up -d && sleep 20
curl -sf http://localhost:8080/actuator/health
docker compose down -v
cd ..
```

Expected: `{"status":"UP",...}` — confirms `@Primary DataSource` wiring didn't confuse Spring Boot's autoconfiguration or break the actuator health indicator's own DB connectivity check (which acquires a connection with no tenant context set — the exact case `getOrNull()` exists to make safe).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/TenantAwareDataSource.java \
        src/main/java/tz/co/nlolo/lifeplatform/TenantDataSourceConfig.java \
        src/test/java/tz/co/nlolo/lifeplatform/RowLevelSecurityIntegrationTest.java
git commit -m "feat: wire per-connection tenant context for Postgres RLS, automate Deliverable 6 smoke test"
```

---

### Task 8: `party` REST layer

**Files:**
- Create: `src/main/java/tz/co/nlolo/lifeplatform/party/infrastructure/{PartyController,RegisterIndividualRequest,RegisterCorporateRequest,ContactInfo,KycUpdateRequest,AddGroupMemberRequest,PageResponse,PartyExceptionHandler}.java`

**Interfaces:**
- Consumes: `PartyApi` (Task 6), the `ROLE_REALM_*` authorities (Task 2).
- Produces: the 5 HTTP endpoints `api/openapi/openapi-party.yaml` declares — Task 9's contract tests call these directly.

**Note on scope (read before implementing):** the object-level check below compares a path `partyId` against a `party_id` JWT claim. **No Keycloak protocol mapper populating that claim exists** — M0's `keycloak/README.md` already documents that custom claim mappers are out of scope, and nothing in this plan adds one (it would require user-provisioning tied to party registration, which is beyond M1's stated scope of "aggregates and public API"). The authorization LOGIC is real, correct, and tested against mock JWTs bearing that claim (the right way to test authorization logic regardless). Making it work against a REAL Keycloak login is a tracked, explicitly-flagged gap for whichever later milestone adds user provisioning — not a silent omission.

- [ ] **Step 1: Write the request/response DTOs**

```java
package tz.co.nlolo.lifeplatform.party.infrastructure;

public record ContactInfo(String phoneNumber, String email) {}
```

```java
package tz.co.nlolo.lifeplatform.party.infrastructure;

import java.time.LocalDate;

public record RegisterIndividualRequest(String fullName, LocalDate dateOfBirth, ContactInfo contactInfo) {}
```

```java
package tz.co.nlolo.lifeplatform.party.infrastructure;

public record RegisterCorporateRequest(String registeredName, String registrationNumber, ContactInfo contactInfo) {}
```

```java
package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.party.api.KycStatus;

public record KycUpdateRequest(KycStatus status, String evidenceDocumentRef) {}
```

```java
package tz.co.nlolo.lifeplatform.party.infrastructure;

import java.util.UUID;

public record AddGroupMemberRequest(UUID memberPartyId) {}
```

```java
package tz.co.nlolo.lifeplatform.party.infrastructure;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageResponse<T>(List<T> items, PageMeta page) {

    public record PageMeta(int page, int pageSize, long totalElements) {}

    public static <T> PageResponse<T> from(Page<T> springPage) {
        return new PageResponse<>(springPage.getContent(),
            new PageMeta(springPage.getNumber(), springPage.getSize(), springPage.getTotalElements()));
    }
}
```

- [ ] **Step 2: Write `PartyController.java`**

```java
package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.party.api.GroupMembershipView;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PartyController {

    private final PartyApi partyApi;

    public PartyController(PartyApi partyApi) {
        this.partyApi = partyApi;
    }

    @PostMapping("/parties/individuals")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS')")
    public ResponseEntity<PartyView> registerIndividual(@RequestBody RegisterIndividualRequest request,
                                                          @AuthenticationPrincipal Jwt jwt) {
        PartyView view = partyApi.registerIndividual(request.fullName(), request.dateOfBirth(),
            request.contactInfo() != null ? request.contactInfo().phoneNumber() : null,
            request.contactInfo() != null ? request.contactInfo().email() : null,
            jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @PostMapping("/parties/corporates")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PartyView> registerCorporate(@RequestBody RegisterCorporateRequest request,
                                                         @AuthenticationPrincipal Jwt jwt) {
        PartyView view = partyApi.registerCorporate(request.registeredName(), request.registrationNumber(),
            request.contactInfo() != null ? request.contactInfo().phoneNumber() : null,
            request.contactInfo() != null ? request.contactInfo().email() : null,
            jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/parties/{partyId}")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PartyView> getParty(@PathVariable UUID partyId, @AuthenticationPrincipal Jwt jwt,
                                               Authentication authentication) {
        // Object-level authorization (docs/04-api-contracts.md §3): a customers-realm
        // token may only read the party matching its own party_id claim. Agents/staff
        // are scoped by realm role alone at M1 -- fine-grained agency-hierarchy/
        // book-of-business scoping needs agent/policy data that doesn't exist until
        // later milestones, and is explicitly deferred, not silently skipped.
        boolean isCustomer = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch("ROLE_REALM_CUSTOMERS"::equals);
        if (isCustomer) {
            String ownPartyId = jwt.getClaimAsString("party_id");
            if (ownPartyId == null || !ownPartyId.equals(partyId.toString())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }
        return ResponseEntity.ok(partyApi.getParty(partyId));
    }

    @PostMapping("/parties/{partyId}/kyc")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<Void> submitKyc(@PathVariable UUID partyId, @RequestBody KycUpdateRequest request,
                                            @AuthenticationPrincipal Jwt jwt) {
        partyApi.submitKycEvidence(partyId, request.status(), request.evidenceDocumentRef(), jwt.getSubject());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/parties/{partyId}/groups/{groupId}/members")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PageResponse<GroupMembershipView>> listGroupMembers(
            @PathVariable UUID partyId, @PathVariable UUID groupId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int pageSize) {
        Page<GroupMembershipView> result = partyApi.listGroupMembers(groupId, PageRequest.of(page, Math.min(pageSize, 200)));
        return ResponseEntity.ok(PageResponse.from(result));
    }

    @PostMapping("/parties/{partyId}/groups/{groupId}/members")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<Void> addGroupMember(@PathVariable UUID partyId, @PathVariable UUID groupId,
                                                 @RequestBody AddGroupMemberRequest request) {
        partyApi.addGroupMember(groupId, request.memberPartyId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
```

- [ ] **Step 3: Write `PartyExceptionHandler.java`**

```java
package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.party.api.DuplicateRegistrationNumberException;
import tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * Maps domain exceptions to RFC 7807 application/problem+json responses
 * (docs/04-api-contracts.md §2). Spring's ProblemDetail already produces
 * type/title/status/detail/instance; errorCode and traceId are the two
 * platform-specific extensions doc04 §2 describes. Not implementing the full
 * dereferenceable type-URI scheme (".../problems/...") here -- party has no
 * semantically rich 422 cases yet that would need one; a simplification, not
 * a silent gap.
 */
@RestControllerAdvice
public class PartyExceptionHandler {

    @ExceptionHandler(PartyNotFoundException.class)
    public ProblemDetail handleNotFound(PartyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "PARTY_NOT_FOUND");
    }

    @ExceptionHandler(DuplicateRegistrationNumberException.class)
    public ProblemDetail handleDuplicate(DuplicateRegistrationNumberException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "DUPLICATE_REGISTRATION_NUMBER");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleValidation(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "VALIDATION_ERROR");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
```

- [ ] **Step 4: Compile and run the existing party tests (no new tests in this task — Task 9 covers the REST layer)**

```bash
docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q test -Dtest=PartyApiIntegrationTest,SecurityConfigTest
```

Expected: `BUILD SUCCESS` (this task only adds files; it doesn't change tested behavior yet — Task 9 is where the REST layer itself gets exercised).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/party/infrastructure
git commit -m "feat: add party REST controller implementing openapi-party.yaml"
```

---

### Task 9: Contract tests against `openapi-party.yaml`

**Files:**
- Test: `src/test/java/tz/co/nlolo/lifeplatform/party/PartyContractTest.java`

**Interfaces:**
- Consumes: `PartyController` (Task 8), `api/openapi/openapi-party.yaml` (existing, unmodified), `com.atlassian.oai:swagger-request-validator-mockmvc` (Task 1).

This directly satisfies M1's first acceptance criterion: "`party`'s API is contract-tested against `openapi-party.yaml`."

- [ ] **Step 1: Write `PartyContractTest.java`**

```java
package tz.co.nlolo.lifeplatform.party;

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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PartyContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-party.yaml";

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
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registerIndividualMatchesOpenApiContract() throws Exception {
        mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Amina Hassan","dateOfBirth":"1990-05-12","contactInfo":{"phoneNumber":"+255712345678","email":"amina@example.tz"}}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi(SPEC_PATH).isValid());
    }

    @Test
    void registerCorporateMatchesOpenApiContract() throws Exception {
        mockMvc.perform(post("/parties/corporates")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"registeredName":"Kilimanjaro SACCO","registrationNumber":"CONTRACT-TEST-001","contactInfo":{"phoneNumber":"+255712345999"}}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi(SPEC_PATH).isValid());
    }

    @Test
    void registerIndividualRejectsUnauthenticatedRequest() throws Exception {
        mockMvc.perform(post("/parties/individuals")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void getPartyRejectsCustomerReadingSomeoneElsesRecord() throws Exception {
        mockMvc.perform(get("/parties/" + java.util.UUID.randomUUID())
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("party_id", java.util.UUID.randomUUID().toString()))))
            .andExpect(status().isForbidden());
    }
}
```

If `com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers`'s exact method signature differs from `openApi(String).isValid()` in the resolved library version, adjust to match its actual API — the intent (validate the real MockMvc request/response pair against `api/openapi/openapi-party.yaml` using a genuine OpenAPI contract-validation library, not a hand-rolled shape check) is what satisfies the acceptance criterion.

- [ ] **Step 2: Run the contract tests**

```bash
docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q test -Dtest=PartyContractTest
```

Expected: `BUILD SUCCESS`, all 4 tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/tz/co/nlolo/lifeplatform/party/PartyContractTest.java
git commit -m "test: contract-test party REST API against openapi-party.yaml"
```

---

### Task 10: Full verification

**Files:** none (verification-only task).

- [ ] **Step 1: Run the complete test suite**

```bash
docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  maven:3.9.9-eclipse-temurin-21 ./mvnw -B test
```

Expected: `BUILD SUCCESS`, 0 failures. This includes M0's three structural tests (`ModularityTests`, `NoCircularDependencyTest`, `NoCrossModuleJoinTest`) — **`NoCrossModuleJoinTest` is now load-bearing for real**, since `audit`'s and `refdata`'s `@Query` usage (Task 3's `ReferenceCodeSetRepository`, Task 4's `AuditLogRepository.searchTrail`) are the first actual JPA queries in the codebase. If `ModularityTests` fails, read the violation — it will name the offending dependency; do not weaken the test or the module's `allowedDependencies` without understanding why a new edge appeared.

- [ ] **Step 2: Full-stack smoke test**

```bash
docker build -f infra/app/Dockerfile -t lifeplatform:m1 .
cd infra && docker compose up -d && sleep 20
curl -sf http://localhost:8080/actuator/health
docker compose logs app --tail 80
docker compose down -v
cd ..
```

Expected: `{"status":"UP",...}`, no restart loop. This is the same check M0's Task 10 ran — confirms M1's additions (Security, MinIO, the wrapped DataSource) didn't regress the baseline `docker compose up` guarantee.

- [ ] **Step 3: Confirm all three M1 acceptance criteria are independently demonstrated**

```bash
docker run --rm -v "$(pwd):/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  maven:3.9.9-eclipse-temurin-21 ./mvnw -B -q test \
  -Dtest=PartyContractTest,RowLevelSecurityIntegrationTest,DomainEventAuditListenerIntegrationTest
```

Expected: `BUILD SUCCESS` — `PartyContractTest` proves criterion 1 (contract-tested against `openapi-party.yaml`), `RowLevelSecurityIntegrationTest` proves criterion 2 (automated RLS smoke test), `DomainEventAuditListenerIntegrationTest` proves criterion 3 (synthetic event reaches `audit_log`) — with `PartyApiIntegrationTest`'s first test as a second, end-to-end proof of criterion 3 using a real event.

- [ ] **Step 4: No commit** — this task only verifies Tasks 1-9.

---

## Self-Review Notes

- **Spec coverage:** all three M1 acceptance criteria (`docs/08-implementation-roadmap.md` §4, M1 section) map to a specific task: contract-testing → Task 9; RLS smoke test → Task 7; synthetic event → audit_log → Task 4 (and Task 6 for the real-event variant). The four listed deliverables (refdata seed access, party/KYC/group aggregates, MinIO document port, generic audit listener) map to Tasks 3, 6, 5, 4 respectively.
- **Placeholder scan:** the one deliberately incomplete piece (`AuditApi.getTrail`'s JSONB substring search) is explained as a stated, reasoned simplification with a rationale, not a TODO. The one deliberately deferred piece (Keycloak `party_id` claim mapper / real user provisioning) is flagged explicitly in Task 8's note, with the authorization logic itself fully implemented and tested against mock claims — not skipped.
- **Type/name consistency:** `PartyApi`'s method signatures are identical between Task 6 (definition), Task 7 (RLS test consumer), Task 8 (REST controller consumer), and Task 9 (contract test, via the controller). `DomainEventEnvelope`/`TenantContext`'s method names from Task 1 are used identically in Tasks 3-8. `ROLE_REALM_<X>` authority strings are identical between Task 2 (producer) and Tasks 8-9 (consumers).
