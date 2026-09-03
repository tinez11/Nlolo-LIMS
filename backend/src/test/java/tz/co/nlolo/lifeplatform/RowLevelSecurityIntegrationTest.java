package tz.co.nlolo.lifeplatform;

import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.policyloan.api.*;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.sql.PreparedStatement;
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
 *
 * <p>M3 (Task 6) addition: explicit @Order pinning. Every "exactly N rows" assertion below
 * (product/underwriting/policy) counts its WHOLE table, shared across every test method against
 * the SAME static Postgres container/@BeforeAll-applied schema -- there is no per-test rollback.
 * Before this class had a fifth test that also creates products/underwriting cases/policies
 * (policyLoanIsTenantIsolatedUnderRls), JUnit 5's default method order (deterministic but
 * intentionally unspecified, and not guaranteed stable when the method set changes) happened to
 * run product-before-underwriting-before-policy; adding a new method without pinning order broke
 * that by coincidence (each earlier count-based test started seeing the new test's rows too).
 * Pinned explicitly here instead of relying on default ordering to keep matching by luck.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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
            "db-migrations/party/V2__individual_person_record.sql",
            // M2 additions (final-review finding 4): prove RLS actually isolates tenants
            // on product/underwriting tables too, not merely that the CREATE POLICY SQL
            // reads correctly.
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/product/V6__eligibility_bounds.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/underwriting/V4__proposal_identity.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            // M3 (Task 6) additions: policyLoanIsTenantIsolatedUnderRls below needs refdata
            // (PolicyLoanApiImpl.originateLoan reads TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE) and
            // policyloan's own schema.
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            // M9 (Task 9) addition: policyloan/V2 (trg_partition_controls) was never in this
            // class's migration list either, harmless until now because nothing after V1 in this
            // list creates a new partitioned table. finaccounting/V1 below does
            // (finaccounting.gl_posting) and must land after this, so the event trigger is already
            // active and mirrors gl_posting's RLS/policy/ACL onto its partitions live as
            // finaccounting/V2 runs -- without it, journalEntryGlPostingAndChartOfAccountAreTenantIsolatedUnderRls
            // below would fail its own RLS check on the gl_posting_2026_08 partition even though the
            // parent enforces it correctly.
            "db-migrations/policyloan/V2__partition_tenant_controls.sql",
            "db-migrations/policyloan/V3__money_check_constraints.sql",
            "db-migrations/policyloan/V4__persist_reservation_id.sql",
            "db-migrations/policyloan/V5__loan_interest_accrual.sql",
            // M4 (Task 1) additions: policy.policy now requires premium_amount/currency/frequency
            // on every insert (every auto-issued policy in policyIsTenantIsolatedUnderRls/
            // policyLoanIsTenantIsolatedUnderRls below would otherwise fail at persist time), the
            // auto-issuance listener needs TZ_BASE_PREMIUM_RATE_PER_MILLE, and
            // billingScheduleIsTenantIsolatedUnderRls below needs billing's own schema/grants.
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/billing/V1__create_billing_schema.sql",
            "db-migrations/billing/V2__grants_rls_money_checks_and_notification_columns.sql",
            // M5 (Task 7) addition: PremiumInvoice now maps amount_paid -- every JPA insert this
            // class's own auto-issued policies trigger (via billing's PolicyEventListener ->
            // generateInvoicesAhead) would otherwise fail against a table missing this column.
            "db-migrations/billing/V3__amount_paid.sql",
            // M5 (Task 1) additions: disbursementInstructionIsTenantIsolatedUnderRls/
            // disbursementIdempotencyRegistryIsTenantIsolatedUnderRls below need payment's own
            // schema/grants/RLS -- V1 alone shipped zero GRANTs and zero RLS on any table.
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            // M7 (Task 10) additions. distribution/V1 enabled RLS on NONE of its four tables and
            // granted app_role nothing; V2 is what adds both, plus commission_accrual and
            // policy_projection with their own policies. Until now no test in this class or
            // AppRolePrivilegesIntegrationTest touched the module at all.
            "db-migrations/distribution/V1__create_distribution_schema.sql",
            "db-migrations/distribution/V2__grants_rls_money_checks_projection_and_statement_lifecycle.sql",
            // M8 (Task 9) additions. reinsurance/V1 enabled RLS on only ONE of its three tables
            // (reinsurance_treaty) and granted app_role nothing; V2 is what adds both cession's and
            // claim_recovery's policies, plus the grants that let app_role reach the schema at all.
            // Until now no test in this class or AppRolePrivilegesIntegrationTest touched the
            // module at all.
            "db-migrations/reinsurance/V1__create_reinsurance_schema.sql",
            "db-migrations/reinsurance/V2__grants_rls_money_checks_reinsurer_and_projection.sql",
            // M9 (Task 9) additions. finaccounting/V1 enabled RLS on NONE of its five original
            // tables and granted app_role nothing at all (worse: it REVOKEs UPDATE/DELETE on
            // gl_posting from a role that never held anything); V2 is what adds both RLS and the
            // grants, for chart_of_account/journal_entry/gl_posting among others. Until now no test
            // in this class or AppRolePrivilegesIntegrationTest touched the module at all.
            "db-migrations/finaccounting/V1__create_finaccounting_schema.sql",
            "db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql",
            "db-migrations/finaccounting/V3__account_code_foreign_key.sql",
            "db-migrations/finaccounting/V4__chart_of_account_writable_via_api.sql",
            // M10 (Task 9) additions. regreporting/V1 enabled RLS on NEITHER of its two original
            // tables and granted app_role nothing at all; V2 is what adds both, for
            // policy_dimension/policy_movement/regulatory_return/return_line among others. Until now
            // no test in this class or AppRolePrivilegesIntegrationTest touched the module at all.
            "db-migrations/regreporting/V1__create_regreporting_schema.sql",
            "db-migrations/regreporting/V2__grants_rls_dimensions_movements_and_return_lines.sql",
            "db-migrations/regreporting/V3__optimistic_locking_on_movement_tables.sql");

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
            // refdata/policyloan's own migrations already GRANT app_role these same privileges
            // (verified by reading both files) -- redundant with that, kept only to mirror this
            // test's existing pattern for every other schema above.
            statement.execute("GRANT USAGE ON SCHEMA refdata TO app_role");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA refdata TO app_role");
            statement.execute("GRANT USAGE ON SCHEMA policyloan TO app_role");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA policyloan TO app_role");
            // payment's own migration (V2) already GRANTs app_role these same privileges
            // (verified by reading the file) -- redundant with that, kept only to mirror this
            // test's existing pattern for every other schema above.
            statement.execute("GRANT USAGE ON SCHEMA payment TO app_role");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA payment TO app_role");
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

    @Autowired
    private PolicyLoanApi policyLoanApi;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @Order(1)
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
    @Order(2)
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
    @Order(3)
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
    @Order(4)
    void policyIsTenantIsolatedUnderRls() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        TenantContext.set(tenantA);
        UUID caseIdA = openCaseForCurrentTenant("RLS-POLICY-A", "3");
        underwritingApi.submitAssessment(caseIdA, tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType.MEDICAL, "ok", new java.math.BigDecimal("10"), "underwriter1");
        UnderwritingCaseView decidedA = underwritingApi.getCase(caseIdA);
        String policyNumberA = policyApi.searchPolicies(decidedA.applicantPartyId(), null, null, null, PageRequest.of(0, 10))
            .getContent().get(0).policyNumber();

        TenantContext.set(tenantB);
        UUID caseIdB = openCaseForCurrentTenant("RLS-POLICY-B", "4");
        underwritingApi.submitAssessment(caseIdB, tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType.MEDICAL, "ok", new java.math.BigDecimal("10"), "underwriter1");
        UnderwritingCaseView decidedB = underwritingApi.getCase(caseIdB);
        assertThat(policyApi.searchPolicies(decidedB.applicantPartyId(), null, null, null, PageRequest.of(0, 10)).getContent()).hasSize(1);

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

    /**
     * M3 addition: proves policy_loan_tenant_isolation actually isolates tenants for
     * policyloan.policy_loan, not merely that the CREATE POLICY statement parses.
     *
     * <p>@Order(5), strictly after every other "exactly N rows" count-based test in this class:
     * openCaseForCurrentTenant below adds 2 more rows each to product.product_definition and
     * underwriting.underwriting_case, which would otherwise inflate productDefinitionIsTenantIsolatedUnderRls's/
     * underwritingCaseIsTenantIsolatedUnderRls's own blanket COUNT(*) assertions if this test ran
     * first. Also mirrors policyIsTenantIsolatedUnderRls's own documented deviation: looks the
     * auto-issued policy up via searchPolicies rather than also calling policyApi.issuePolicy
     * directly, which would double-issue (policy.application.UnderwritingDecisionEventListener
     * already auto-issues synchronously on submitAssessment's ACCEPT/LOADED decision commit).
     */
    @Test
    @Order(5)
    void policyLoanIsTenantIsolatedUnderRls() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        TenantContext.set(tenantA);
        UUID caseIdA = openCaseForCurrentTenant("RLS-LOAN-A", "5");
        underwritingApi.submitAssessment(caseIdA, tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType.MEDICAL, "ok", new java.math.BigDecimal("10"), "underwriter1");
        UnderwritingCaseView decidedA = underwritingApi.getCase(caseIdA);
        String policyNumberA = policyApi.searchPolicies(decidedA.applicantPartyId(), null, null, null, PageRequest.of(0, 10))
            .getContent().get(0).policyNumber();
        bumpCashValue(policyNumberA, "1000000");
        String loanIdA = policyLoanApi.originateLoan(policyNumberA, new java.math.BigDecimal("100000"), "TZS", "MPESA-0700000001", "test-agent").loanId().toString();

        TenantContext.set(tenantB);
        UUID caseIdB = openCaseForCurrentTenant("RLS-LOAN-B", "6");
        underwritingApi.submitAssessment(caseIdB, tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType.MEDICAL, "ok", new java.math.BigDecimal("10"), "underwriter1");
        UnderwritingCaseView decidedB = underwritingApi.getCase(caseIdB);
        String policyNumberB = policyApi.searchPolicies(decidedB.applicantPartyId(), null, null, null, PageRequest.of(0, 10))
            .getContent().get(0).policyNumber();
        bumpCashValue(policyNumberB, "2000000");
        policyLoanApi.originateLoan(policyNumberB, new java.math.BigDecimal("200000"), "TZS", "MPESA-0700000002", "test-agent");

        try (Connection superuserConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = superuserConnection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM policyloan.policy_loan")) {
            resultSet.next();
            assertThat(resultSet.getInt(1)).isEqualTo(2);
        }

        try (Connection restrictedConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = restrictedConnection.createStatement()) {
            statement.execute("SET ROLE app_role");
            statement.execute("SET app.current_tenant_id = '" + tenantA + "'");
            try (ResultSet resultSet = statement.executeQuery("SELECT loan_id FROM policyloan.policy_loan")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo(loanIdA);
                assertThat(resultSet.next()).isFalse();
            }
        }
    }

    /**
     * M4 (Task 1) addition: proves billing_schedule_tenant_isolation (declared in billing/V1,
     * completed by billing/V2's grants -- V1 alone had zero GRANT statements, so app_role could
     * not even reach the table to be isolated) actually isolates tenants. Rows are seeded
     * directly via SQL as the superuser rather than through an application API, then read back
     * exclusively via a genuinely restricted app_role connection -- same proof shape as
     * policyIsTenantIsolatedUnderRls above.
     *
     * <p>M4 (Task 3) note: unlike policy.policy/policyloan.policy_loan above, this test's
     * superuser count assertion is scoped to its own two tenant IDs rather than a blanket
     * COUNT(*) -- Task 3's billing.application.PolicyEventListener now auto-generates a
     * billing_schedule row for every policy issuance, so the four policies
     * policyIsTenantIsolatedUnderRls and policyLoanIsTenantIsolatedUnderRls issue above (each
     * @Order'd before this test) have already added four unrelated schedule rows to this same
     * shared container by the time this test runs; @Order can no longer isolate this table's
     * count the way it does for the earlier tests, since this test is already last.
     */
    @Test
    @Order(6)
    void billingScheduleIsTenantIsolatedUnderRls() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO billing.billing_schedule (tenant_id, policy_number, premium_frequency, premium_amount, premium_currency) " +
                 "VALUES (?, ?, 'MONTHLY', 15000.00, 'TZS')")) {
            insert.setObject(1, tenantA);
            insert.setString(2, "RLS-BILLING-A");
            assertThat(insert.executeUpdate()).isEqualTo(1);
            insert.setObject(1, tenantB);
            insert.setString(2, "RLS-BILLING-B");
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }

        try (Connection superuserConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement select = superuserConnection.prepareStatement(
                 "SELECT COUNT(*) FROM billing.billing_schedule WHERE tenant_id IN (?, ?)")) {
            select.setObject(1, tenantA);
            select.setObject(2, tenantB);
            try (ResultSet resultSet = select.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isEqualTo(2);
            }
        }

        try (Connection restrictedConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = restrictedConnection.createStatement()) {
            statement.execute("SET ROLE app_role");
            statement.execute("SET app.current_tenant_id = '" + tenantA + "'");
            try (ResultSet resultSet = statement.executeQuery("SELECT policy_number FROM billing.billing_schedule")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("RLS-BILLING-A");
                assertThat(resultSet.next()).isFalse();
            }
        }
    }

    /**
     * M5 addition: proves disbursement_instruction_tenant_isolation actually isolates tenants.
     * payment/V1 shipped with zero RLS and zero GRANTs -- app_role could not even reach the
     * schema -- so this test is only meaningful as of payment/V2. Seeded directly via SQL as the
     * superuser (payment has no synchronous write API by design), then read back exclusively
     * through a genuinely restricted app_role connection.
     */
    @Test
    @Order(7)
    void disbursementInstructionIsTenantIsolatedUnderRls() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO payment.disbursement_instruction (tenant_id, idempotency_key, payee_ref, " +
                 "amount, currency, purpose, source_ref) VALUES (?, ?, ?, 50000.00, 'TZS', 'LOAN_DISBURSEMENT', ?)")) {
            insert.setObject(1, tenantA);
            insert.setString(2, "rls-disb-a");
            insert.setString(3, "MPESA-0700000001");
            insert.setString(4, "loan-a");
            assertThat(insert.executeUpdate()).isEqualTo(1);
            insert.setObject(1, tenantB);
            insert.setString(2, "rls-disb-b");
            insert.setString(3, "MPESA-0700000002");
            insert.setString(4, "loan-b");
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }

        try (Connection superuserConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement select = superuserConnection.prepareStatement(
                 "SELECT COUNT(*) FROM payment.disbursement_instruction WHERE tenant_id IN (?, ?)")) {
            select.setObject(1, tenantA);
            select.setObject(2, tenantB);
            try (ResultSet resultSet = select.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isEqualTo(2);
            }
        }

        try (Connection restrictedConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = restrictedConnection.createStatement()) {
            statement.execute("SET ROLE app_role");
            statement.execute("SET app.current_tenant_id = '" + tenantA + "'");
            try (ResultSet resultSet = statement.executeQuery("SELECT source_ref FROM payment.disbursement_instruction")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("loan-a");
                assertThat(resultSet.next()).isFalse();
            }
        }
    }

    /**
     * M5 addition: regression test for section 3's fix -- V1's disbursement_idempotency_registry
     * had NO tenant_id column at all, so the PK was `idempotency_key` alone: two different
     * tenants' agents sharing the same client-generated key would collide, and the second
     * tenant's genuine, unrelated disbursement would be silently dropped as a "safe duplicate".
     * Under V1's schema the second insert below would have failed outright with a PK violation;
     * proving both inserts succeed AND that app_role restricted to tenant A sees only its own
     * row is what makes this test genuinely falsifiable against that regression.
     */
    @Test
    @Order(8)
    void disbursementIdempotencyRegistryIsTenantIsolatedUnderRls() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        String sharedKey = "rls-shared-idem-key";

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO payment.disbursement_idempotency_registry (tenant_id, idempotency_key, disbursement_id) " +
                 "VALUES (?, ?, ?)")) {
            insert.setObject(1, tenantA);
            insert.setString(2, sharedKey);
            insert.setObject(3, UUID.randomUUID());
            assertThat(insert.executeUpdate()).isEqualTo(1);
            insert.setObject(1, tenantB);
            insert.setString(2, sharedKey);
            insert.setObject(3, UUID.randomUUID());
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }

        try (Connection superuserConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement select = superuserConnection.prepareStatement(
                 "SELECT COUNT(*) FROM payment.disbursement_idempotency_registry WHERE idempotency_key = ?")) {
            select.setString(1, sharedKey);
            try (ResultSet resultSet = select.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isEqualTo(2);
            }
        }

        try (Connection restrictedConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = restrictedConnection.createStatement()) {
            statement.execute("SET ROLE app_role");
            statement.execute("SET app.current_tenant_id = '" + tenantA + "'");
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT tenant_id FROM payment.disbursement_idempotency_registry WHERE idempotency_key = '" + sharedKey + "'")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo(tenantA.toString());
                assertThat(resultSet.next()).isFalse();
            }
        }
    }

    /**
     * M7 addition: proves distribution's two money-bearing tables are genuinely tenant-isolated.
     * Both are asserted in one test because an accrual only exists inside a statement, so the same
     * two-tenant fixture serves both -- and checking the accrual as well as its parent matters:
     * {@code commission_accrual} is created by V2 (not V1) and therefore carries a separately
     * written policy that could have been omitted without any other test noticing.
     */
    @Test
    @Order(9)
    void commissionStatementAndAccrualAreTenantIsolatedUnderRls() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID agentA = UUID.randomUUID();
        UUID agentB = UUID.randomUUID();
        UUID statementA = UUID.randomUUID();
        UUID statementB = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (PreparedStatement insertAgent = connection.prepareStatement(
                    "INSERT INTO distribution.agent_profile (agent_id, tenant_id, party_id, license_number, "
                    + "license_expiry_date) VALUES (?, ?, ?, ?, DATE '2030-01-01')")) {
                insertAgent.setObject(1, agentA);
                insertAgent.setObject(2, tenantA);
                insertAgent.setObject(3, UUID.randomUUID());
                insertAgent.setString(4, "RLS-LIC-A");
                assertThat(insertAgent.executeUpdate()).isEqualTo(1);
                insertAgent.setObject(1, agentB);
                insertAgent.setObject(2, tenantB);
                insertAgent.setObject(3, UUID.randomUUID());
                insertAgent.setString(4, "RLS-LIC-B");
                assertThat(insertAgent.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement insertStatement = connection.prepareStatement(
                    "INSERT INTO distribution.commission_statement (statement_id, tenant_id, agent_id, period, "
                    + "total_amount, total_currency, status) VALUES (?, ?, ?, '2026-01', 10000.00, 'TZS', 'OPEN')")) {
                insertStatement.setObject(1, statementA);
                insertStatement.setObject(2, tenantA);
                insertStatement.setObject(3, agentA);
                assertThat(insertStatement.executeUpdate()).isEqualTo(1);
                insertStatement.setObject(1, statementB);
                insertStatement.setObject(2, tenantB);
                insertStatement.setObject(3, agentB);
                assertThat(insertStatement.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement insertAccrual = connection.prepareStatement(
                    "INSERT INTO distribution.commission_accrual (tenant_id, agent_id, statement_id, policy_number, "
                    + "tier_type, amount, currency, period, source_ref) "
                    + "VALUES (?, ?, ?, ?, 'FIRST_YEAR', 10000.00, 'TZS', '2026-01', ?)")) {
                insertAccrual.setObject(1, tenantA);
                insertAccrual.setObject(2, agentA);
                insertAccrual.setObject(3, statementA);
                insertAccrual.setString(4, "RLS-DIST-A");
                insertAccrual.setString(5, "RLS-DIST-A");
                assertThat(insertAccrual.executeUpdate()).isEqualTo(1);
                insertAccrual.setObject(1, tenantB);
                insertAccrual.setObject(2, agentB);
                insertAccrual.setObject(3, statementB);
                insertAccrual.setString(4, "RLS-DIST-B");
                insertAccrual.setString(5, "RLS-DIST-B");
                assertThat(insertAccrual.executeUpdate()).isEqualTo(1);
            }
        }

        // Negative control: both tenants' rows really are present when RLS is not in play.
        try (Connection superuserConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement select = superuserConnection.prepareStatement(
                 "SELECT COUNT(*) FROM distribution.commission_accrual WHERE tenant_id IN (?, ?)")) {
            select.setObject(1, tenantA);
            select.setObject(2, tenantB);
            try (ResultSet resultSet = select.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isEqualTo(2);
            }
        }

        try (Connection restrictedConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = restrictedConnection.createStatement()) {
            statement.execute("SET ROLE app_role");
            statement.execute("SET app.current_tenant_id = '" + tenantA + "'");
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT period FROM distribution.commission_statement")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.next()).as("tenant B's statement must be invisible").isFalse();
            }
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT policy_number FROM distribution.commission_accrual")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("RLS-DIST-A");
                assertThat(resultSet.next()).as("tenant B's accrual must be invisible").isFalse();
            }
        }
    }

    /**
     * M8 addition: proves the two tables reinsurance/V2 protects for the first time -- {@code
     * cession} and {@code claim_recovery} -- are genuinely tenant-isolated. V1 enabled RLS on
     * {@code reinsurance_treaty} only; these two carried {@code tenant_id NOT NULL} and no policy
     * at all, so app_role could read every tenant's ceded amounts and recoveries. Checked together
     * because a {@code claim_recovery} row only makes sense once its parent treaty exists, and the
     * same two-tenant fixture serves both.
     */
    @Test
    @Order(10)
    void cessionAndClaimRecoveryAreTenantIsolatedUnderRls() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID treatyA = UUID.randomUUID();
        UUID treatyB = UUID.randomUUID();
        UUID cessionA = UUID.randomUUID();
        UUID cessionB = UUID.randomUUID();
        UUID recoveryA = UUID.randomUUID();
        UUID recoveryB = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (PreparedStatement insertTreaty = connection.prepareStatement(
                    "INSERT INTO reinsurance.reinsurance_treaty (treaty_id, tenant_id, reinsurer_name, "
                    + "treaty_type, retention_limit_amount, retention_limit_currency, effective_from) "
                    + "VALUES (?, ?, 'Africa Re', 'XOL', 1500000.00, 'TZS', CURRENT_DATE)")) {
                insertTreaty.setObject(1, treatyA);
                insertTreaty.setObject(2, tenantA);
                assertThat(insertTreaty.executeUpdate()).isEqualTo(1);
                insertTreaty.setObject(1, treatyB);
                insertTreaty.setObject(2, tenantB);
                assertThat(insertTreaty.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement insertCession = connection.prepareStatement(
                    "INSERT INTO reinsurance.cession (cession_id, tenant_id, policy_number, treaty_id, "
                    + "ceded_amount, ceded_currency) VALUES (?, ?, ?, ?, 500000.00, 'TZS')")) {
                insertCession.setObject(1, cessionA);
                insertCession.setObject(2, tenantA);
                insertCession.setString(3, "RLS-RI-POL-A");
                insertCession.setObject(4, treatyA);
                assertThat(insertCession.executeUpdate()).isEqualTo(1);
                insertCession.setObject(1, cessionB);
                insertCession.setObject(2, tenantB);
                insertCession.setString(3, "RLS-RI-POL-B");
                insertCession.setObject(4, treatyB);
                assertThat(insertCession.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement insertRecovery = connection.prepareStatement(
                    "INSERT INTO reinsurance.claim_recovery (recovery_id, tenant_id, claim_id, treaty_id, "
                    + "recoverable_amount, recoverable_currency) VALUES (?, ?, ?, ?, 250000.00, 'TZS')")) {
                insertRecovery.setObject(1, recoveryA);
                insertRecovery.setObject(2, tenantA);
                insertRecovery.setObject(3, UUID.randomUUID());
                insertRecovery.setObject(4, treatyA);
                assertThat(insertRecovery.executeUpdate()).isEqualTo(1);
                insertRecovery.setObject(1, recoveryB);
                insertRecovery.setObject(2, tenantB);
                insertRecovery.setObject(3, UUID.randomUUID());
                insertRecovery.setObject(4, treatyB);
                assertThat(insertRecovery.executeUpdate()).isEqualTo(1);
            }
        }

        // Negative control: both tenants' rows really are present when RLS is not in play.
        try (Connection superuserConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement selectCessions = superuserConnection.prepareStatement(
                 "SELECT COUNT(*) FROM reinsurance.cession WHERE tenant_id IN (?, ?)");
             PreparedStatement selectRecoveries = superuserConnection.prepareStatement(
                 "SELECT COUNT(*) FROM reinsurance.claim_recovery WHERE tenant_id IN (?, ?)")) {
            selectCessions.setObject(1, tenantA);
            selectCessions.setObject(2, tenantB);
            try (ResultSet resultSet = selectCessions.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isEqualTo(2);
            }
            selectRecoveries.setObject(1, tenantA);
            selectRecoveries.setObject(2, tenantB);
            try (ResultSet resultSet = selectRecoveries.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isEqualTo(2);
            }
        }

        try (Connection restrictedConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = restrictedConnection.createStatement()) {
            statement.execute("SET ROLE app_role");
            statement.execute("SET app.current_tenant_id = '" + tenantA + "'");
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT policy_number FROM reinsurance.cession")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("RLS-RI-POL-A");
                assertThat(resultSet.next()).as("tenant B's cession must be invisible").isFalse();
            }
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT recovery_id FROM reinsurance.claim_recovery")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo(recoveryA.toString());
                assertThat(resultSet.next()).as("tenant B's recovery must be invisible").isFalse();
            }
        }
    }

    /**
     * M9 addition: proves the three tables finaccounting/V2 protects for the first time --
     * {@code chart_of_account}, {@code journal_entry} and {@code gl_posting} -- are genuinely
     * tenant-isolated. V1 enabled RLS on none of them and granted app_role nothing at all (see this
     * class's own migration-list comment above); V2 is what adds both. Rows are seeded directly via
     * SQL as the superuser -- {@code FinaccountingApi} is READ-ONLY by design (every real posting is
     * derived from a domain event by the module's own listeners, never hand-entered), so there is no
     * synchronous write API to drive fixtures through, same situation as payment's and distribution's
     * own tables above. {@code gl_posting}'s row lands in the {@code gl_posting_2026_08} partition
     * (this suite runs in August 2026), which is only correctly protected because of this class's own
     * {@code policyloan/V2} migration-list addition.
     */
    @Test
    @Order(11)
    void journalEntryGlPostingAndChartOfAccountAreTenantIsolatedUnderRls() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID journalEntryA = UUID.randomUUID();
        UUID journalEntryB = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (PreparedStatement insertEntry = connection.prepareStatement(
                    "INSERT INTO finaccounting.journal_entry (journal_entry_id, tenant_id, source_event, "
                    + "source_ref, period, policy_number) VALUES (?, ?, 'billing.PremiumInvoiceGenerated', ?, "
                    + "'2026-08', ?)")) {
                insertEntry.setObject(1, journalEntryA);
                insertEntry.setObject(2, tenantA);
                insertEntry.setString(3, "rls-je-a");
                insertEntry.setString(4, "RLS-FA-POL-A");
                assertThat(insertEntry.executeUpdate()).isEqualTo(1);
                insertEntry.setObject(1, journalEntryB);
                insertEntry.setObject(2, tenantB);
                insertEntry.setString(3, "rls-je-b");
                insertEntry.setString(4, "RLS-FA-POL-B");
                assertThat(insertEntry.executeUpdate()).isEqualTo(1);
            }
            // chart_of_account is seeded BEFORE gl_posting, and the order is now load-bearing rather
            // than incidental: finaccounting/V3 makes gl_posting.account_code a real foreign key into
            // chart_of_account (tenant_id, account_code), so account '1000' must exist for BOTH
            // tenants before either tenant's posting can be written.
            try (PreparedStatement insertAccount = connection.prepareStatement(
                    "INSERT INTO finaccounting.chart_of_account (tenant_id, account_code, name, account_type, "
                    + "normal_balance) VALUES (?, '1000', 'Cash / Mobile Money', 'ASSET', 'DR')")) {
                insertAccount.setObject(1, tenantA);
                assertThat(insertAccount.executeUpdate()).isEqualTo(1);
                insertAccount.setObject(1, tenantB);
                assertThat(insertAccount.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement insertPosting = connection.prepareStatement(
                    "INSERT INTO finaccounting.gl_posting (tenant_id, journal_entry_id, account_code, direction, "
                    + "amount, currency, period, policy_number, posting_type, source_event, source_ref) "
                    + "VALUES (?, ?, '1000', 'DR', 15000.00, 'TZS', '2026-08', ?, "
                    + "'billing.PremiumInvoiceGenerated', 'billing.PremiumInvoiceGenerated', ?)")) {
                insertPosting.setObject(1, tenantA);
                insertPosting.setObject(2, journalEntryA);
                insertPosting.setString(3, "RLS-FA-POL-A");
                insertPosting.setString(4, "rls-je-a");
                assertThat(insertPosting.executeUpdate()).isEqualTo(1);
                insertPosting.setObject(1, tenantB);
                insertPosting.setObject(2, journalEntryB);
                insertPosting.setString(3, "RLS-FA-POL-B");
                insertPosting.setString(4, "rls-je-b");
                assertThat(insertPosting.executeUpdate()).isEqualTo(1);
            }
        }

        // Negative control: both tenants' rows really are present when RLS is not in play.
        try (Connection superuserConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement selectEntries = superuserConnection.prepareStatement(
                 "SELECT COUNT(*) FROM finaccounting.journal_entry WHERE tenant_id IN (?, ?)");
             PreparedStatement selectPostings = superuserConnection.prepareStatement(
                 "SELECT COUNT(*) FROM finaccounting.gl_posting WHERE tenant_id IN (?, ?)");
             PreparedStatement selectAccounts = superuserConnection.prepareStatement(
                 "SELECT COUNT(*) FROM finaccounting.chart_of_account WHERE tenant_id IN (?, ?)")) {
            selectEntries.setObject(1, tenantA);
            selectEntries.setObject(2, tenantB);
            try (ResultSet resultSet = selectEntries.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isEqualTo(2);
            }
            selectPostings.setObject(1, tenantA);
            selectPostings.setObject(2, tenantB);
            try (ResultSet resultSet = selectPostings.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isEqualTo(2);
            }
            selectAccounts.setObject(1, tenantA);
            selectAccounts.setObject(2, tenantB);
            try (ResultSet resultSet = selectAccounts.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isEqualTo(2);
            }
        }

        try (Connection restrictedConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = restrictedConnection.createStatement()) {
            statement.execute("SET ROLE app_role");
            statement.execute("SET app.current_tenant_id = '" + tenantA + "'");
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT policy_number FROM finaccounting.journal_entry")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("RLS-FA-POL-A");
                assertThat(resultSet.next()).as("tenant B's journal entry must be invisible").isFalse();
            }
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT policy_number FROM finaccounting.gl_posting")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("RLS-FA-POL-A");
                assertThat(resultSet.next()).as("tenant B's gl_posting must be invisible").isFalse();
            }
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT account_code FROM finaccounting.chart_of_account")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("1000");
                assertThat(resultSet.next()).as("tenant B's chart_of_account row must be invisible").isFalse();
            }
        }
    }

    /**
     * M10 addition: proves the four tables regreporting/V2 protects for the first time --
     * {@code policy_dimension}, {@code policy_movement}, {@code regulatory_return} and
     * {@code return_line} -- are genuinely tenant-isolated. V1 enabled RLS on neither original
     * table and granted app_role nothing at all (see this class's own migration-list comment
     * above). Rows are seeded directly via SQL as the superuser -- regreporting has no synchronous
     * write API reachable from outside the module's own projections/return generation, same
     * situation as payment's, distribution's, reinsurance's and finaccounting's own tables above.
     */
    @Test
    @Order(12)
    void policyDimensionPolicyMovementRegulatoryReturnAndReturnLineAreTenantIsolatedUnderRls() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        UUID returnIdA;
        UUID returnIdB;

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (PreparedStatement insertDimension = connection.prepareStatement(
                    "INSERT INTO regreporting.policy_dimension (tenant_id, policy_number, product_id, "
                    + "sum_assured_amount, issue_date) VALUES (?, ?, ?, 1000000.00, CURRENT_DATE)")) {
                insertDimension.setObject(1, tenantA);
                insertDimension.setString(2, "RLS-REG-POL-A");
                insertDimension.setObject(3, productA);
                assertThat(insertDimension.executeUpdate()).isEqualTo(1);
                insertDimension.setObject(1, tenantB);
                insertDimension.setString(2, "RLS-REG-POL-B");
                insertDimension.setObject(3, productB);
                assertThat(insertDimension.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement insertMovement = connection.prepareStatement(
                    "INSERT INTO regreporting.policy_movement (tenant_id, period, product_id, policies_issued) "
                    + "VALUES (?, '2026-Q3', ?, 1)")) {
                insertMovement.setObject(1, tenantA);
                insertMovement.setObject(2, productA);
                assertThat(insertMovement.executeUpdate()).isEqualTo(1);
                insertMovement.setObject(1, tenantB);
                insertMovement.setObject(2, productB);
                assertThat(insertMovement.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement insertReturn = connection.prepareStatement(
                    "INSERT INTO regreporting.regulatory_return (tenant_id, return_type, period, status) "
                    + "VALUES (?, 'RLS_TEST_RETURN', '2026-Q3', 'READY') RETURNING return_id")) {
                insertReturn.setObject(1, tenantA);
                try (ResultSet rs = insertReturn.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    returnIdA = (UUID) rs.getObject(1);
                }
                insertReturn.setObject(1, tenantB);
                try (ResultSet rs = insertReturn.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    returnIdB = (UUID) rs.getObject(1);
                }
            }
            try (PreparedStatement insertLine = connection.prepareStatement(
                    "INSERT INTO regreporting.return_line (return_id, tenant_id, line_no, line_code, label, "
                    + "metric_name, numeric_value) VALUES (?, ?, 1, 'AR-01', ?, 'POLICIES_ISSUED', 1)")) {
                insertLine.setObject(1, returnIdA);
                insertLine.setObject(2, tenantA);
                insertLine.setString(3, "RLS Test Line A");
                assertThat(insertLine.executeUpdate()).isEqualTo(1);
                insertLine.setObject(1, returnIdB);
                insertLine.setObject(2, tenantB);
                insertLine.setString(3, "RLS Test Line B");
                assertThat(insertLine.executeUpdate()).isEqualTo(1);
            }
        }

        // Negative control: both tenants' rows really are present when RLS is not in play -- this
        // is what makes the restricted-connection assertions below meaningful rather than
        // vacuously passing because the seed itself silently failed.
        try (Connection superuserConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement selectDimensions = superuserConnection.prepareStatement(
                 "SELECT COUNT(*) FROM regreporting.policy_dimension WHERE tenant_id IN (?, ?)");
             PreparedStatement selectMovements = superuserConnection.prepareStatement(
                 "SELECT COUNT(*) FROM regreporting.policy_movement WHERE tenant_id IN (?, ?)");
             PreparedStatement selectReturns = superuserConnection.prepareStatement(
                 "SELECT COUNT(*) FROM regreporting.regulatory_return WHERE tenant_id IN (?, ?)");
             PreparedStatement selectLines = superuserConnection.prepareStatement(
                 "SELECT COUNT(*) FROM regreporting.return_line WHERE tenant_id IN (?, ?)")) {
            selectDimensions.setObject(1, tenantA);
            selectDimensions.setObject(2, tenantB);
            try (ResultSet rs = selectDimensions.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
            selectMovements.setObject(1, tenantA);
            selectMovements.setObject(2, tenantB);
            try (ResultSet rs = selectMovements.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
            selectReturns.setObject(1, tenantA);
            selectReturns.setObject(2, tenantB);
            try (ResultSet rs = selectReturns.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
            selectLines.setObject(1, tenantA);
            selectLines.setObject(2, tenantB);
            try (ResultSet rs = selectLines.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }

        // app_role, restricted to tenant A via SET ROLE + the session variable every RLS policy
        // checks, must see exactly tenant A's row on all four tables.
        try (Connection restrictedConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = restrictedConnection.createStatement()) {
            statement.execute("SET ROLE app_role");
            statement.execute("SET app.current_tenant_id = '" + tenantA + "'");
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT policy_number FROM regreporting.policy_dimension")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("RLS-REG-POL-A");
                assertThat(resultSet.next()).as("tenant B's policy_dimension row must be invisible").isFalse();
            }
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT product_id FROM regreporting.policy_movement")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo(productA.toString());
                assertThat(resultSet.next()).as("tenant B's policy_movement row must be invisible").isFalse();
            }
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT return_id FROM regreporting.regulatory_return")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo(returnIdA.toString());
                assertThat(resultSet.next()).as("tenant B's regulatory_return row must be invisible").isFalse();
            }
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT label FROM regreporting.return_line")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("RLS Test Line A");
                assertThat(resultSet.next()).as("tenant B's return_line row must be invisible").isFalse();
            }
        }
    }

    /**
     * policy.policy_account.cash_value_amount starts at ZERO at issuance (PolicyApiImpl has no
     * premium-accrual path yet) -- bumped directly here, exactly as PolicyLoanApiIntegrationTest's
     * own issuePolicyWithCashValue helper does, or originateLoan would reject every amount with
     * InsufficientLoanValueException regardless of RLS, defeating the point of this test.
     */
    private void bumpCashValue(String policyNumber, String cashValue) throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE policy.policy_account SET cash_value_amount = " + cashValue + " WHERE policy_number = '" + policyNumber + "'");
        }
    }

    private UUID openCaseForCurrentTenant(String productCode, String phoneSuffixDigit) {
        PartyView applicant = partyApi.registerIndividual("RLS Test Applicant", LocalDate.of(1990, 1, 1), "+25571200000" + phoneSuffixDigit, null, "test");
        ProductSummaryView product = productApi.createProduct(productCode, "RLS Test Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", java.math.BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", java.math.BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return underwritingApi.openCase(applicant.partyId(), product.productId(), snapshot.productVersionId(),
            new java.math.BigDecimal("1000000"), "TZS", null, "agent1").caseId();
    }
}
