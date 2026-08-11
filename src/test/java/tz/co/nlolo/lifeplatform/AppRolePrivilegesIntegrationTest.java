package tz.co.nlolo.lifeplatform;

import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.policyloan.api.*;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;
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

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Every other integration test in this suite (PartyApiIntegrationTest,
 * RowLevelSecurityIntegrationTest, etc.) points spring.datasource.username at
 * the Testcontainers Postgres superuser -- the owner of every table these
 * migrations create -- so none of them can ever notice that the application's
 * REAL runtime identity, app_role (infra/postgres/init/01-create-app-role.sql.template,
 * wired via SPRING_DATASOURCE_USERNAME in infra/docker-compose.yml), had no
 * privileges at all on party/document/refdata/audit until this fix: migrations
 * run as the postgres superuser (scripts/migrate.sh), which becomes owner of
 * every schema/table, and the only grant that previously existed anywhere was
 * scoped to schema public.
 *
 * <p>This test closes that blind spot by pointing the application's OWN
 * Spring-managed DataSource (spring.datasource.username/password, not a
 * hand-rolled side connection) at app_role, then performing a genuine business
 * operation through PartyApi -- proving the identity the app actually runs as
 * in every real deployment can reach its own tables. Before the migration
 * grants were added this failed with "permission denied for schema party";
 * this test is the regression guard against that ever silently coming back.
 *
 * <p>App_role bootstrap mirrors RowLevelSecurityIntegrationTest exactly: ALTER
 * (not CREATE) because MigrationTestSupport.applyMigration already bootstraps
 * a bare NOLOGIN app_role idempotently so the GRANT statements inside the
 * migrations themselves have a role to target; this upgrades it to a real
 * LOGIN role with the NOSUPERUSER NOBYPASSRLS attributes production requires
 * (either one missing silently voids every RLS policy platform-wide).
 *
 * <p>Ordering this relies on: @Testcontainers starts POSTGRES via its
 * BeforeAllCallback before this class's own static @BeforeAll runs, and
 * Spring's ApplicationContext (and therefore the real DataSource bean built
 * from the @DynamicPropertySource-registered app_role credentials) is not
 * created until the first test method's instance is prepared -- i.e. strictly
 * after @BeforeAll has applied migrations and elevated app_role to LOGIN. If
 * that ordering ever changed, this test would fail with an authentication
 * error rather than a false pass, so it is not a silent hazard.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class AppRolePrivilegesIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "test_app_role_password";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        // The point of this test: the application connects as app_role, its actual
        // runtime identity in every real deployment -- never the Testcontainers
        // superuser every other integration test in this suite uses.
        registry.add("spring.datasource.username", () -> "app_role");
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
    }

    /**
     * Review fix (Task 6, Important finding 1): pins {@code mobile-money.base-url} to a
     * deterministically-dead address, rather than leaving it at
     * {@code application.yml}'s default ({@code http://localhost:8082}). That default is exactly
     * the host/port {@code infra/docker-compose.yml} maps the real {@code mock-mobile-money}
     * WireMock service onto, and that service's own {@code disburse-success.json} stub mapping
     * ACCEPTs any {@code payeeRef} other than the specific reject-sentinel
     * {@code MPESA-0000000000} -- which this test's {@code appRoleCanOriginateALoanThroughTheApplicationsOwnDataSource}
     * does not use. Without this override, running this test on a developer machine with the
     * compose stack up would silently flip its outcome from {@code DISBURSEMENT_FAILED} to
     * {@code DISBURSED}, because the real gateway would actually accept the call. Port 1 is
     * privileged and nothing in this test process (or realistically anywhere) binds to it, so the
     * connection is refused deterministically regardless of what else is running on the host --
     * this test's outcome must depend only on the code under test, never on machine state.
     */
    @DynamicPropertySource
    static void mobileMoneyProperties(DynamicPropertyRegistry registry) {
        registry.add("mobile-money.base-url", () -> "http://127.0.0.1:1");
    }

    @BeforeAll
    static void applyMigrationsAndBootstrapAppRole() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            // M2 additions (final-review finding 4): product/underwriting's GRANT/RLS SQL
            // read correct by inspection but were never exercised under the real app_role
            // identity -- exactly the M1 blind spot this class's own javadoc describes.
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            // M3 (Task 6) additions: policyloan.PolicyLoanApiImpl.originateLoan reads
            // TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE via ReferenceDataApi and writes through
            // policyloan's own new grants -- both needed for this class's own app_role smoke test.
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V3__money_check_constraints.sql",
            "db-migrations/policyloan/V4__persist_reservation_id.sql",
            // M4 (Task 1) additions: policy.policy now requires premium_amount/currency/frequency
            // on every insert (this class's own policy-issuing tests would otherwise fail), the
            // auto-issuance listener invoked by submitAssessment below needs
            // TZ_BASE_PREMIUM_RATE_PER_MILLE, and billing needs its own schema/grants for
            // appRoleCanReadAndWriteBillingSchedule below.
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/billing/V1__create_billing_schema.sql",
            "db-migrations/billing/V2__grants_rls_money_checks_and_notification_columns.sql",
            // M5 (Task 1) additions: appRoleCanReadWriteAndUpdateDisbursementInstruction below
            // needs payment's own schema/grants -- V1 alone had zero GRANT statements anywhere
            // in the file, the exact M1 failure mode this class exists to catch.
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql");

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @Autowired
    private PartyApi partyApi;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ProductApi productApi;

    @Autowired
    private UnderwritingApi underwritingApi;

    @Autowired
    private PolicyApi policyApi;

    @Autowired
    private PolicyLoanApi policyLoanApi;

    /** The application's own DataSource, pointed at a real app_role LOGIN by
     * {@link #datasourceProperties} -- the same connection pool every business read and write in
     * this class travels through. */
    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void appRoleCanRegisterAndReadBackAPartyThroughTheApplicationsOwnDataSource() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        Instant before = Instant.now();

        PartyView registered = partyApi.registerIndividual("Neema Kileo", LocalDate.of(1993, 4, 18),
            "+255713000000", "neema@example.tz", "test-agent");

        assertThat(registered.partyId()).isNotNull();

        // Round trip through app_role, not merely the write succeeding: proves
        // SELECT (not just INSERT) is actually granted on party.party too.
        PartyView fetched = partyApi.getParty(registered.partyId());
        assertThat(fetched.displayName()).isEqualTo("Neema Kileo");
        assertThat(fetched.partyId()).isEqualTo(registered.partyId());

        // The AFTER_COMMIT audit listener also writes through app_role's grants
        // (audit.audit_log's USAGE + INSERT/SELECT) -- confirms Part A's audit
        // grants as well, not only party's, in the same round trip.
        List<?> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "party.PartyRegistered", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
    }

    /**
     * M2 addition (final-review finding 4): proves app_role can actually write and read
     * through product.product_definition/product_version/rating_table via the app's own
     * DataSource -- not merely that the migration's GRANT statements read correctly.
     * Before the migration grants existed, this would fail with "permission denied for
     * schema product", the exact M1 failure mode.
     */
    @Test
    void appRoleCanCreateAndPublishAProductThroughTheApplicationsOwnDataSource() {
        TenantContext.set(UUID.randomUUID());

        ProductSummaryView product = productApi.createProduct("APP-ROLE-PROD", "App Role Product Test", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertThat(product.productId()).isNotNull();

        // publishVersion writes product_version + rating_table + benefit_schedule rows,
        // and (Task's rollover fix) reads product_version back to retire the prior active
        // one -- exercising SELECT, INSERT and UPDATE on app_role's grants, not just INSERT.
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, java.time.LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", java.math.BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", java.math.BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");

        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), java.time.LocalDate.now());
        assertThat(snapshot.productVersionId()).isNotNull();
    }

    /**
     * M2 addition (final-review finding 4): proves app_role can actually write and read
     * through underwriting.underwriting_case/risk_assessment via the app's own DataSource.
     * Before the migration grants existed, this would fail with "permission denied for
     * schema underwriting", the exact M1 failure mode -- and openCase/submitAssessment
     * also round-trip through party (getParty) and product (resolveRatingMultiplier),
     * exercising all three new-and-existing schemas' grants together in one real business
     * operation, the way a real deployment actually exercises them.
     */
    @Test
    void appRoleCanOpenAndDecideAnUnderwritingCaseThroughTheApplicationsOwnDataSource() {
        TenantContext.set(UUID.randomUUID());

        PartyView applicant = partyApi.registerIndividual("App Role Underwriting Applicant", LocalDate.of(1990, 1, 1),
            "+255713000001", null, "test-agent");
        ProductSummaryView product = productApi.createProduct("APP-ROLE-UW", "App Role Underwriting Product", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, java.time.LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", java.math.BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", java.math.BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), java.time.LocalDate.now());

        UnderwritingCaseView opened = underwritingApi.openCase(applicant.partyId(), product.productId(), snapshot.productVersionId(),
            new java.math.BigDecimal("1000000"), "TZS", "agent1");
        assertThat(opened.caseId()).isNotNull();

        UnderwritingCaseView decided = underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Normal findings", new java.math.BigDecimal("10"), "underwriter1");
        assertThat(decided.decisionOutcome()).isNotNull();

        UnderwritingCaseView fetched = underwritingApi.getCase(opened.caseId());
        assertThat(fetched.caseId()).isEqualTo(opened.caseId());
    }

    /**
     * M3 addition: proves app_role can write and read through policy.policy/policy_account
     * via the app's own DataSource -- issuePolicy persists both tables in one transaction, and
     * getPolicy reads them back, exercising INSERT+SELECT on both new grants together.
     */
    @Test
    void appRoleCanIssueAndReadAPolicyThroughTheApplicationsOwnDataSource() {
        TenantContext.set(UUID.randomUUID());

        PartyView policyholder = partyApi.registerIndividual("App Role Policy Applicant", LocalDate.of(1988, 6, 1),
            "+255713000002", null, "test-agent");
        ProductSummaryView product = productApi.createProduct("APP-ROLE-POLICY", "App Role Policy Product", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, java.time.LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", java.math.BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", java.math.BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), java.time.LocalDate.now());
        UnderwritingCaseView opened = underwritingApi.openCase(policyholder.partyId(), product.productId(), snapshot.productVersionId(),
            new java.math.BigDecimal("1000000"), "TZS", "agent1");
        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Normal findings", new java.math.BigDecimal("10"), "underwriter1");

        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(policyholder.partyId(), product.productId(), snapshot.productVersionId(),
            new java.math.BigDecimal("1000000"), "TZS", new java.math.BigDecimal("50000.00"), "TZS", "MONTHLY", null, java.util.List.of(), "App role smoke test");
        PolicyView issued = policyApi.issuePolicy(opened.caseId(), request, "test-staff");
        assertThat(issued.policyNumber()).isNotNull();

        PolicyView fetched = policyApi.getPolicy(issued.policyNumber());
        assertThat(fetched.status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    /**
     * M3 addition: proves app_role can write and read through policyloan.policy_loan/
     * loan_interest_term via the app's own DataSource, exercising the full
     * Module-Architecture-B1 reserve/confirm/release round trip -- confirmReservation writes
     * through policy.policy_account's grants (already proven above) AND policyloan's own new
     * grants in the same call.
     */
    @Test
    void appRoleCanOriginateALoanThroughTheApplicationsOwnDataSource() throws Exception {
        TenantContext.set(UUID.randomUUID());

        PartyView policyholder = partyApi.registerIndividual("App Role Loan Applicant", LocalDate.of(1988, 6, 1), "+255713000003", null, "test-agent");
        ProductSummaryView product = productApi.createProduct("APP-ROLE-LOAN", "App Role Loan Product", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, java.time.LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", java.math.BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", java.math.BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")), null, "actuary@nlolo.co.tz");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), java.time.LocalDate.now());
        PolicyView issued = policyApi.issuePolicy(UUID.randomUUID(),
            new PolicyApi.IssueRequest(policyholder.partyId(), product.productId(), snapshot.productVersionId(),
                new java.math.BigDecimal("1000000"), "TZS", new java.math.BigDecimal("50000.00"), "TZS", "MONTHLY", null, java.util.List.of(), "App role loan smoke test"),
            "test-staff");

        // policy.policy_account.cash_value_amount starts at ZERO at issuance (PolicyApiImpl
        // has no premium-accrual path yet) -- bumped directly here, exactly as
        // PolicyLoanApiIntegrationTest's own issuePolicyWithCashValue helper does, or
        // originateLoan below would reject every amount with InsufficientLoanValueException
        // regardless of app_role's grants, defeating the point of this test.
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE policy.policy_account SET cash_value_amount = 1000000 WHERE policy_number = '" + issued.policyNumber() + "'");
        }

        LoanView loan = policyLoanApi.originateLoan(issued.policyNumber(), new java.math.BigDecimal("100000"), "TZS", "MPESA-0700000000", "test-agent");
        assertThat(loan.loanId()).isNotNull();

        // M5 (Task 6) changed this test's outcome, and the corrected reason (review fix, Important
        // finding 2 -- the original version of this comment claimed a pre-existing failing
        // gateway call had merely gone unnoticed, which is NOT what happened): before Task 6,
        // originateLoan's LoanDisbursementRequested payload carried NO idempotencyKey at all (see
        // the removed comment this task's own diff deleted -- "idempotencyKey isn't a parameter
        // on this method ... omitted entirely here"), and payment.PaymentRequestListener
        // .requireKey throws IllegalArgumentException on a missing key, swallowed by that
        // listener's own withTenant catch -- so payment recorded NOTHING and called NOTHING; there
        // was no gateway call to fail. Task 6 is what made this pipe live for the first time, by
        // BOTH adding the idempotencyKey (so payment.PaymentRequestListener now actually acts) AND
        // adding policyloan.application.PaymentEventListener (so policyloan now reacts to the
        // outcome). With mobile-money.base-url pinned to a dead address by this class's own
        // mobileMoneyProperties override above (review fix, Important finding 1 -- the previous
        // version relied on nothing happening to listen on the application.yml default port,
        // which the real infra/docker-compose.yml mock-mobile-money service binds to on a
        // developer machine), the gateway call now deterministically fails, and policyloan's
        // PaymentEventListener consumes the resulting payment.DisbursementFailed and drives the
        // loan to DISBURSEMENT_FAILED, synchronously, before getLoan below ever runs. This is not
        // a workaround -- it further strengthens this test's own point (app_role privileges
        // through the app's DataSource): markDisbursementFailed additionally writes a REVERSAL
        // loan_transaction row and updates policy.policy_account's encumbrance, both through
        // app_role's own connection.
        LoanView fetched = policyLoanApi.getLoan(loan.loanId());
        assertThat(fetched.status()).isEqualTo(LoanStatus.DISBURSEMENT_FAILED);
    }

    /**
     * M3 final review, I5. {@code db-migrations/policy/V1:63} states, inside the CREATE TABLE,
     * "Append-only: no UPDATE/DELETE grant for the application role" -- and M3's own
     * {@code GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA policy TO app_role}
     * at {@code V1:168} silently made that false, with no compensating REVOKE anywhere in the
     * repository. Both sibling schemas got it right in the same branch
     * ({@code policyloan/V1:147}, {@code audit/V1:44}); {@code db-migrations/policy/V2} brings
     * this one into line, and this is the assertion that keeps it there. Before V2 this class
     * asserted no REVOKE at all, anywhere.
     *
     * <p>Uses the application's OWN Spring-managed DataSource, which this class points at a real
     * {@code app_role} login -- so this is the runtime identity's actual ACL being tested, not
     * the migration text read back. Postgres checks table privileges before touching any rows,
     * so no endorsement fixture is needed for the denial; the SELECT below is the positive
     * control proving app_role does still have real access to the table and the denial is
     * specific to UPDATE/DELETE rather than a blanket permission failure.
     */
    @Test
    void appRoleCannotUpdateOrDeletePolicyEndorsementsBecauseTheLedgerIsAppendOnly() throws Exception {
        // A tenant must be in scope before borrowing a connection: TenantAwareDataSource issues
        // RESET app.current_tenant_id when TenantContext is empty, and every policy-schema RLS
        // policy then evaluates ''::uuid and errors out -- fail-closed, but it would mask the
        // privilege denial this test is actually about.
        TenantContext.set(UUID.randomUUID());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThat(connection.getMetaData().getUserName()).isEqualTo("app_role");

            statement.execute("SELECT count(*) FROM policy.endorsement");   // positive control

            assertThatThrownBy(() -> statement.execute("UPDATE policy.endorsement SET approved_by = 'tamper'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.execute("DELETE FROM policy.endorsement"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");

            // Counter-control: a table in the same schema that is NOT append-only must still be
            // updatable, so the two denials above cannot be explained by app_role having lost
            // UPDATE across the whole schema.
            statement.execute("UPDATE policy.policy_account SET updated_at = now() WHERE policy_number = 'no-such-policy'");
        }
    }

    /**
     * M4 (Task 1) addition: direct SQL round-trip through app_role's own restricted connection --
     * proves the GRANT block in billing/V2 actually took effect under the real runtime identity,
     * not just the migration/superuser identity this suite's other integration tests use. Same
     * "app_role, not just the migration text" proof this class exists for, applied to billing's
     * grants for the first time.
     */
    @Test
    void appRoleCanReadAndWriteBillingSchedule() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO billing.billing_schedule (tenant_id, policy_number, premium_frequency, premium_amount, premium_currency) " +
                 "VALUES (?, ?, 'MONTHLY', 15000.00, 'TZS')")) {
            insert.setObject(1, tenantId);
            insert.setString(2, "APPROLE-BILLING-01");
            assertThat(insert.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not insert into billing.billing_schedule: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                 "SELECT premium_amount FROM billing.billing_schedule WHERE policy_number = ?")) {
            select.setString(1, "APPROLE-BILLING-01");
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).as("app_role could not read back the row it just inserted").isTrue();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo(new BigDecimal("15000.00"));
            }
        } catch (SQLException e) {
            fail("app_role could not select from billing.billing_schedule: " + e.getMessage());
        }
    }

    /**
     * M5 (Task 1) addition: direct SQL round-trip through app_role's own restricted connection --
     * proves the GRANT block in payment/V2 actually took effect under the real runtime identity,
     * not just the migration/superuser identity this suite's other integration tests use. Same
     * "app_role, not just the migration text" proof this class exists for, applied to payment's
     * grants for the first time. The UPDATE assertion is the one that proves the append-only
     * decision (section 1 of payment/V2 -- deliberately NOT re-issuing V1's REVOKE UPDATE, DELETE
     * on this table) actually took effect: disbursement_instruction has a real status lifecycle
     * (PENDING -> CONFIRMED/COMPLETED/FAILED), so app_role must be able to UPDATE it, unlike the
     * genuinely append-only policy.endorsement tested above.
     */
    @Test
    void appRoleCanReadWriteAndUpdateDisbursementInstruction() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO payment.disbursement_instruction (tenant_id, idempotency_key, payee_ref, " +
                 "amount, currency, purpose, source_ref) VALUES (?, ?, ?, 75000.00, 'TZS', 'LOAN_DISBURSEMENT', ?)")) {
            insert.setObject(1, tenantId);
            insert.setString(2, "approle-disb-01");
            insert.setString(3, "MPESA-0700000099");
            insert.setString(4, "loan-approle-01");
            assertThat(insert.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not insert into payment.disbursement_instruction: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                 "SELECT status FROM payment.disbursement_instruction WHERE idempotency_key = ?")) {
            select.setString(1, "approle-disb-01");
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).as("app_role could not read back the row it just inserted").isTrue();
                assertThat(rs.getString(1)).isEqualTo("PENDING");
            }
        } catch (SQLException e) {
            fail("app_role could not select from payment.disbursement_instruction: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE payment.disbursement_instruction SET status = 'COMPLETED' WHERE idempotency_key = ?")) {
            update.setString(1, "approle-disb-01");
            assertThat(update.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not update payment.disbursement_instruction: " + e.getMessage());
        }
    }
}
