package tz.co.nlolo.lifeplatform;

import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
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
import java.util.List;
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
            "db-migrations/party/V1__create_party_schema.sql",
            // M2 additions (final-review finding 4): prove RLS actually isolates tenants
            // on product/underwriting tables too, not merely that the CREATE POLICY SQL
            // reads correctly.
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/policy/V1__create_policy_schema.sql");

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            // Mirrors infra/postgres/init/01-create-app-role.sql.template's load-bearing
            // NOSUPERUSER NOBYPASSRLS attributes -- either one absent silently voids
            // every RLS policy platform-wide (Deliverable 6 §1). ALTER, not CREATE:
            // MigrationTestSupport.applyMigration already bootstrapped a bare NOLOGIN
            // app_role idempotently, so this upgrades it to the exact attributes needed
            // rather than colliding with "role app_role already exists".
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD 'test_app_role_password'");
            statement.execute("GRANT USAGE ON SCHEMA party TO app_role");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA party TO app_role");
            // product/underwriting's own migrations already GRANT app_role these same
            // privileges (verified by reading both files) -- these two statements are
            // redundant with that, kept only to mirror this test's existing party-schema
            // pattern exactly rather than relying on a different code path than the one
            // already proven here.
            statement.execute("GRANT USAGE ON SCHEMA product TO app_role");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA product TO app_role");
            statement.execute("GRANT USAGE ON SCHEMA underwriting TO app_role");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA underwriting TO app_role");
            statement.execute("GRANT USAGE ON SCHEMA policy TO app_role");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA policy TO app_role");
        }
    }

    @Autowired
    private PartyApi partyApi;

    @Autowired
    private ProductApi productApi;

    @Autowired
    private UnderwritingApi underwritingApi;

    @Autowired
    private PolicyApi policyApi;

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

    /**
     * M2 addition (final-review finding 4): the M1 bug class this class exists to catch
     * was grants/RLS that read correctly in the migration SQL but were never exercised
     * under a real restricted role. product's RLS policy (product_definition_tenant_isolation)
     * read correctly by inspection but had never been exercised this way -- this proves it
     * actually works, not merely that the CREATE POLICY statement parses.
     */
    @Test
    void productDefinitionIsTenantIsolatedUnderRls() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        TenantContext.set(tenantA);
        productApi.createProduct("RLS-PROD-A", "Tenant A Product", ProductCategory.TERM_LIFE, "TZS", "actuary");

        TenantContext.set(tenantB);
        productApi.createProduct("RLS-PROD-B", "Tenant B Product", ProductCategory.TERM_LIFE, "TZS", "actuary");

        try (Connection superuserConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = superuserConnection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM product.product_definition")) {
            resultSet.next();
            assertThat(resultSet.getInt(1)).isEqualTo(2);
        }

        try (Connection restrictedConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = restrictedConnection.createStatement()) {
            statement.execute("SET ROLE app_role");
            statement.execute("SET app.current_tenant_id = '" + tenantA + "'");
            try (ResultSet resultSet = statement.executeQuery("SELECT product_code FROM product.product_definition")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("RLS-PROD-A");
                assertThat(resultSet.next()).isFalse();
            }
        }
    }

    /**
     * M2 addition (final-review finding 4): same proof as above for
     * underwriting_case_tenant_isolation -- underwriting's RLS policy read correctly by
     * inspection but had never been exercised under a real restricted role either.
     */
    @Test
    void underwritingCaseIsTenantIsolatedUnderRls() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        TenantContext.set(tenantA);
        UUID caseIdA = openCaseForCurrentTenant("RLS-UW-A", "1");

        TenantContext.set(tenantB);
        openCaseForCurrentTenant("RLS-UW-B", "2");

        try (Connection superuserConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = superuserConnection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM underwriting.underwriting_case")) {
            resultSet.next();
            assertThat(resultSet.getInt(1)).isEqualTo(2);
        }

        try (Connection restrictedConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = restrictedConnection.createStatement()) {
            statement.execute("SET ROLE app_role");
            statement.execute("SET app.current_tenant_id = '" + tenantA + "'");
            try (ResultSet resultSet = statement.executeQuery("SELECT case_id FROM underwriting.underwriting_case")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo(caseIdA.toString());
                assertThat(resultSet.next()).isFalse();
            }
        }
    }

    /**
     * M3 addition: proves policy_tenant_isolation actually isolates tenants for policy.policy,
     * not merely that the CREATE POLICY statement parses -- same proof shape as the existing
     * product/underwriting tests in this class.
     *
     * <p>Deviation from the originally-drafted (deferred) version of this test: it used to call
     * policyApi.issuePolicy(...) manually after submitAssessment. Task 2 wires up
     * policy.application.UnderwritingDecisionEventListener, which auto-issues a policy
     * synchronously (same thread, AFTER_COMMIT) the instant submitAssessment's ACCEPT/LOADED
     * decision commits -- so a manual issuePolicy call here would double-issue (2 policies per
     * tenant instead of 1), breaking this test's own "exactly 2 policies total" assertion. The
     * auto-issued policy is looked up via searchPolicies instead.
     */
    @Test
    void policyIsTenantIsolatedUnderRls() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        TenantContext.set(tenantA);
        UUID caseIdA = openCaseForCurrentTenant("RLS-POLICY-A", "3");
        underwritingApi.submitAssessment(caseIdA, tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType.MEDICAL, "ok", new java.math.BigDecimal("10"), "underwriter1");
        UnderwritingCaseView decidedA = underwritingApi.getCase(caseIdA);
        String policyNumberA = policyApi.searchPolicies(decidedA.applicantPartyId(), null, PageRequest.of(0, 10))
            .getContent().get(0).policyNumber();

        TenantContext.set(tenantB);
        UUID caseIdB = openCaseForCurrentTenant("RLS-POLICY-B", "4");
        underwritingApi.submitAssessment(caseIdB, tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType.MEDICAL, "ok", new java.math.BigDecimal("10"), "underwriter1");
        UnderwritingCaseView decidedB = underwritingApi.getCase(caseIdB);
        assertThat(policyApi.searchPolicies(decidedB.applicantPartyId(), null, PageRequest.of(0, 10)).getContent()).hasSize(1);

        try (Connection superuserConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = superuserConnection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM policy.policy")) {
            resultSet.next();
            assertThat(resultSet.getInt(1)).isEqualTo(2);
        }

        try (Connection restrictedConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = restrictedConnection.createStatement()) {
            statement.execute("SET ROLE app_role");
            statement.execute("SET app.current_tenant_id = '" + tenantA + "'");
            try (ResultSet resultSet = statement.executeQuery("SELECT policy_number FROM policy.policy")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo(policyNumberA);
                assertThat(resultSet.next()).isFalse();
            }
        }
    }

    private UUID openCaseForCurrentTenant(String productCode, String phoneSuffixDigit) {
        PartyView applicant = partyApi.registerIndividual("RLS Test Applicant", LocalDate.of(1990, 1, 1), "+25571200000" + phoneSuffixDigit, null, "test");
        ProductSummaryView product = productApi.createProduct(productCode, "RLS Test Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", java.math.BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", java.math.BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return underwritingApi.openCase(applicant.partyId(), product.productId(), snapshot.productVersionId(),
            new java.math.BigDecimal("1000000"), "TZS", "agent1").caseId();
    }
}
