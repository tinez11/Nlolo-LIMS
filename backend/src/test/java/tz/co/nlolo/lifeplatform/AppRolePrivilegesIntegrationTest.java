package tz.co.nlolo.lifeplatform;

import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.policyloan.api.*;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
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
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

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
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/party/V5__registered_by_name.sql",
            "db-migrations/party/V6__client_reference.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            // M2 additions (final-review finding 4): product/underwriting's GRANT/RLS SQL
            // read correct by inspection but were never exercised under the real app_role
            // identity -- exactly the M1 blind spot this class's own javadoc describes.
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/product/V6__eligibility_bounds.sql",
            "db-migrations/product/V9__rating_table_sum_assured_bounds.sql",
            "db-migrations/product/V10__ifrs_measurement_model_on_version.sql",
            "db-migrations/product/V11__frequency_loading.sql",
            "db-migrations/product/V12__tira_filing.sql",
            "db-migrations/product/V13__benefit_calculation_method.sql",
            "db-migrations/product/V15__exclusion_periods.sql",
            "db-migrations/product/V16__base_rate_term_bands.sql",
            "db-migrations/product/V17__cash_value.sql",
            "db-migrations/product/V18__payout_schedule.sql",
            "db-migrations/product/V19__accumulation_terms.sql",
            "db-migrations/product/V20__deposit_rate_grid.sql",
            "db-migrations/product/V21__bonus_terms.sql",
            "db-migrations/product/V27__ifrs17_classification.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
            "db-migrations/benefitpayout/V3__withholding.sql",
            "db-migrations/benefitpayout/V4__withholding_rule_end.sql",
            "db-migrations/accumulation/V1__create_accumulation_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/underwriting/V4__proposal_identity.sql",
            "db-migrations/underwriting/V5__explicit_decision.sql",
            "db-migrations/underwriting/V6__proposal_terms_and_beneficiaries.sql",
            "db-migrations/underwriting/V8__rating_multiplier.sql",
            "db-migrations/underwriting/V9__group_proposal.sql",
            "db-migrations/underwriting/V10__issuance_failure.sql",
            "db-migrations/underwriting/V11__member_evidence_case.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            // M3 (Task 6) additions: policyloan.PolicyLoanApiImpl.originateLoan reads
            // TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE via ReferenceDataApi and writes through
            // policyloan's own new grants -- both needed for this class's own app_role smoke test.
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            // M9 (Task 9) addition: policyloan/V2 (trg_partition_controls, a database-wide event
            // trigger) was never in this class's migration list at all, even though V1 was --
            // harmless until now because nothing after V1 in this list creates a NEW partitioned
            // table. finaccounting/V1 below does (finaccounting.gl_posting), and it must land
            // AFTER this so the trigger is already active and mirrors gl_posting's RLS/policy/ACL
            // onto its partitions live, as finaccounting/V2's own ALTER TABLE/CREATE POLICY/GRANT/
            // REVOKE statements execute -- not merely via V2's one-time backfill sweep, which would
            // run too early to see a schema that does not exist yet.
            "db-migrations/policyloan/V2__partition_tenant_controls.sql",
            "db-migrations/policyloan/V3__money_check_constraints.sql",
            "db-migrations/policyloan/V4__persist_reservation_id.sql",
            "db-migrations/policyloan/V5__loan_interest_accrual.sql",
            "db-migrations/policyloan/V7__q4_2026_partitions.sql",
            // M4 (Task 1) additions: policy.policy now requires premium_amount/currency/frequency
            // on every insert (this class's own policy-issuing tests would otherwise fail), the
            // auto-issuance listener invoked by submitAssessment below needs
            // TZ_BASE_PREMIUM_RATE_PER_MILLE, and billing needs its own schema/grants for
            // appRoleCanReadAndWriteBillingSchedule below.
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/policy/V10__one_policy_per_underwriting_case.sql",
            "db-migrations/policy/V11__not_taken_up_status.sql",
            "db-migrations/policy/V24__issuance_record.sql",
            "db-migrations/policy/V27__expired_status.sql",
            "db-migrations/policy/V28__policies_due_to_expire.sql",
            "db-migrations/policy/V29__paid_up.sql",
            "db-migrations/policy/V30__surrender.sql",
            "db-migrations/policy/V31__free_look_status.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/billing/V1__create_billing_schema.sql",
            "db-migrations/billing/V2__grants_rls_money_checks_and_notification_columns.sql",
            // M5 (Task 7) addition: PremiumInvoice now maps amount_paid -- every JPA insert this
            // class's own policy-issuing tests trigger (via billing's PolicyEventListener ->
            // generateInvoicesAhead) would otherwise fail against a table missing this column.
            "db-migrations/billing/V3__amount_paid.sql",
            "db-migrations/billing/V5__single_premium_invoice.sql",
            "db-migrations/billing/V6__premium_credit.sql",
            "db-migrations/billing/V7__policy_inception_invoice.sql",
            "db-migrations/billing/V8__schedule_premium_paying_until.sql",
            // M6 (Task 1) additions: appRoleCanReadWriteAndUpdateAClaim below needs claims' own
            // schema/grants -- V1 alone had zero GRANT statements anywhere in the file (again),
            // the exact M1/M5 failure mode this class exists to catch, and RLS on only 1 of its
            // 3 original tables. Ordered before payment here to match scripts/migrate.sh's real
            // deployment order (claims applies before payment).
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/claims/V5__claim_policy_member.sql",
            "db-migrations/claims/V6__exclusion_decline.sql",
            "db-migrations/claims/V7__claim_assessment_assessor_name.sql",
            "db-migrations/claims/V8__claim_evidence_uploaded_by_name.sql",
            // M5 (Task 1) additions: appRoleCanReadWriteAndUpdateDisbursementInstruction below
            // needs payment's own schema/grants -- V1 alone had zero GRANT statements anywhere
            // in the file, the exact M1 failure mode this class exists to catch.
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            // M5 final-review fix wave (I2). payment/V3 and V4 issue SIX `GRANT EXECUTE ... TO
            // app_role` statements between them -- the only function grants anywhere in this
            // platform -- and this class is the dedicated guard for exactly the bug class of "a
            // migration adds a database object app_role cannot actually reach". Until now those
            // grants were exercised only FUNCTIONALLY (MobileMoneyCallbackIntegrationTest happens to
            // call the functions), never by the test built to catch a missing grant, so a future
            // migration that added a SECURITY DEFINER function and forgot its GRANT could slip past
            // the one test written for that failure mode. See
            // appRoleCanExecuteEveryPaymentCallbackResolverFunction below.
            "db-migrations/payment/V3__inbound_callback_tenant_resolver.sql",
            "db-migrations/payment/V4__in_doubt_status_and_id_based_callback_resolvers.sql",
            "db-migrations/payment/V6__disbursement_method.sql",
            "db-migrations/payment/V7__q4_2026_partitions.sql",
            "db-migrations/payment/V8__benefit_payout_purposes.sql",
            "db-migrations/payment/V9__account_purposes.sql",
            // M7 (Task 10) additions: distribution appeared in NEITHER this class nor
            // RowLevelSecurityIntegrationTest until now -- the same gap claims had entering M6.
            // distribution/V1 has zero GRANT statements (the recurring V1 pattern this class
            // exists to catch); V2 is what grants app_role anything at all here.
            "db-migrations/refdata/V4__seed_distribution_parameters.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/distribution/V1__create_distribution_schema.sql",
            "db-migrations/distribution/V2__grants_rls_money_checks_projection_and_statement_lifecycle.sql",
            "db-migrations/distribution/V5__agent_channel_and_home_branch.sql",
            // M8 (Task 9) additions: reinsurance appeared in NEITHER this class nor
            // RowLevelSecurityIntegrationTest until now -- the same gap distribution had entering
            // M7. reinsurance/V1 has zero GRANT statements (the recurring V1 pattern this class
            // exists to catch); V2 is what grants app_role anything at all here.
            "db-migrations/reinsurance/V1__create_reinsurance_schema.sql",
            "db-migrations/reinsurance/V2__grants_rls_money_checks_reinsurer_and_projection.sql",
            "db-migrations/reinsurance/V4__projection_product_category.sql",
            // M9 (Task 9) additions: finaccounting appeared in NEITHER this class nor
            // RowLevelSecurityIntegrationTest until now -- the same gap reinsurance had entering
            // M8. finaccounting/V1 has zero GRANT statements and ends with a REVOKE UPDATE, DELETE
            // with NO PRIOR GRANT anywhere in the file (app_role therefore had NO privileges on the
            // ledger at all -- worse than the recurring "V1 grants nothing" pattern, since V1 here
            // also actively revokes on a role that never held anything); V2 is what grants app_role
            // anything at all and is the first point this platform's append-only ledger becomes
            // genuinely writable.
            "db-migrations/finaccounting/V1__create_finaccounting_schema.sql",
            "db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql",
            "db-migrations/finaccounting/V3__account_code_foreign_key.sql",
            "db-migrations/finaccounting/V4__chart_of_account_writable_via_api.sql",
            "db-migrations/finaccounting/V5__chart_of_account_hierarchy.sql",
            "db-migrations/finaccounting/V7__q4_2026_partitions.sql",
            "db-migrations/finaccounting/V10__ifrs17_ledger_foundation.sql",
            // M10 (Task 9) additions: regreporting appeared in NEITHER this class nor
            // RowLevelSecurityIntegrationTest until now -- the same gap finaccounting had entering
            // M9. regreporting/V1 has zero GRANT statements and zero RLS on either of its two
            // original tables (the recurring V1 pattern this class exists to catch); V2 is what
            // grants app_role anything at all here and adds RLS everywhere -- and, unlike
            // finaccounting's append-only ledger, V2 leaves UPDATE/DELETE genuinely granted, because
            // nothing in this module is append-only (see
            // appRoleCanInsertSelectUpdateAndDeleteRegreportingProjectionsAndReturnLines below).
            "db-migrations/regreporting/V1__create_regreporting_schema.sql",
            "db-migrations/regreporting/V2__grants_rls_dimensions_movements_and_return_lines.sql",
            "db-migrations/regreporting/V3__optimistic_locking_on_movement_tables.sql",
            "db-migrations/regreporting/V5__member_movement_columns.sql",
            "db-migrations/regreporting/V6__free_look_cancellation_movement.sql");

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
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", java.math.BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", java.math.BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");

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
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", java.math.BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", java.math.BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), java.time.LocalDate.now());

        UnderwritingCaseView opened = underwritingApi.openCase(applicant.partyId(), product.productId(), snapshot.productVersionId(),
            new java.math.BigDecimal("1000000"), "TZS", null, "agent1");
        assertThat(opened.caseId()).isNotNull();

        // Both writes, because they touch different columns: the assessment inserts a
        // risk_assessment row and fills recommendation_*, the decision fills decision_* --
        // including decision_decided_by and decision_overrode_recommendation, which V5 added
        // and which app_role must be able to write like any other column on the table.
        UnderwritingCaseView assessed = underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Normal findings", new java.math.BigDecimal("10"), "underwriter1");
        assertThat(assessed.recommendationOutcome()).isNotNull();

        UnderwritingCaseView decided = underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Standard risk"), "decider1", false);
        assertThat(decided.decisionOutcome()).isNotNull();
        assertThat(decided.decisionDecidedBy()).isEqualTo("decider1");

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
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", java.math.BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", java.math.BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), java.time.LocalDate.now());
        UnderwritingCaseView opened = underwritingApi.openCase(policyholder.partyId(), product.productId(), snapshot.productVersionId(),
            new java.math.BigDecimal("1000000"), "TZS", null, "agent1");
        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Normal findings", new java.math.BigDecimal("10"), "underwriter1");

        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(policyholder.partyId(), product.productId(), snapshot.productVersionId(),
            new java.math.BigDecimal("1000000"), "TZS", new java.math.BigDecimal("50000.00"), "TZS", "MONTHLY", null, java.util.List.of(), "App role smoke test");
        PolicyView issued = policyApi.issuePolicy(opened.caseId(), request, "test-staff");
        assertThat(issued.policyNumber()).isNotNull();
        // The point of this test is that app_role can write and read policy.policy through the
        // application's own DataSource, so it follows the policy all the way to in force --
        // which now takes a collected premium as well as an issuance.
        policyApi.activateOnFirstPremium(issued.policyNumber());

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
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", java.math.BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", java.math.BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)), null, ANY_FILING, "actuary@nlolo.co.tz");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), java.time.LocalDate.now());
        PolicyView issued = policyApi.issuePolicy(UUID.randomUUID(),
            new PolicyApi.IssueRequest(policyholder.partyId(), product.productId(), snapshot.productVersionId(),
                new java.math.BigDecimal("1000000"), "TZS", new java.math.BigDecimal("50000.00"), "TZS", "MONTHLY", null, java.util.List.of(), "App role loan smoke test"),
            "test-staff");
        // A loan can only be taken against a policy in force.
        policyApi.activateOnFirstPremium(issued.policyNumber());

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
        // PaymentEventListener consumes the outcome.
        //
        // M5 FINAL-REVIEW FIX WAVE (C2) CHANGED THIS TEST'S EXPECTED OUTCOME, and the change is the
        // point rather than an accommodation. A connection to a dead address is a TRANSPORT failure
        // -- indeterminate, not a rail decline -- so payment now records IN_DOUBT and publishes NO
        // event at all, instead of recording FAILED and publishing payment.DisbursementFailed.
        // policyloan therefore hears nothing and the loan correctly REMAINS at
        // DISBURSEMENT_REQUESTED with its encumbrance intact, rather than being compensated
        // (a REVERSAL loan_transaction plus releaseEncumbrance) for a payout whose fate is unknown.
        // This assertion is the platform-level proof of the "no *Failed event for IN_DOUBT" half of
        // C2: it observes the DOWNSTREAM MODULE's state, through the real cross-module event chain,
        // rather than payment's own row.
        //
        // This still exercises app_role's own privileges through the app's DataSource (the whole
        // point of this class) -- originateLoan itself writes policy_loan, loan_transaction and
        // policy.policy_account rows, and payment's phase-1 insert plus its IN_DOUBT update both go
        // through app_role too. What it no longer exercises is the compensation path; that is
        // covered directly by policyloan's own tests.
        LoanView fetched = policyLoanApi.getLoan(loan.loanId());
        assertThat(fetched.status())
            .as("an indeterminate gateway outcome must NOT compensate -- the loan stays at "
                + "DISBURSEMENT_REQUESTED with the encumbrance intact")
            .isEqualTo(LoanStatus.DISBURSEMENT_REQUESTED);
    }

    /**
     * Review fix (I2). This class is the platform's dedicated guard for "app_role cannot actually
     * reach something a migration created", and it had a blind spot: db-migrations/payment/V3 and V4
     * are the only migrations anywhere that {@code GRANT EXECUTE} on functions, and neither file was
     * in this class's migration list at all -- so those six grants were covered only incidentally, by
     * a different test that happens to call the functions through the webhook. A future migration
     * adding a SECURITY DEFINER function and forgetting its GRANT would have slipped past the one
     * test written for exactly that failure mode.
     *
     * <p>Calls all six through the application's OWN DataSource (real {@code app_role},
     * NOSUPERUSER NOBYPASSRLS), which is what makes this non-vacuous: {@code has_function_privilege}
     * would read the catalogue back, whereas an actual invocation proves the runtime identity can
     * execute them. Deliberately calls with values that match nothing -- the assertion is about
     * EXECUTE privilege, not about resolution behaviour (covered by
     * MobileMoneyCallbackIntegrationTest), and a missing grant fails with
     * "permission denied for function" regardless of arguments.
     */
    @Test
    void appRoleCanExecuteEveryPaymentCallbackResolverFunction() throws Exception {
        TenantContext.set(UUID.randomUUID());
        UUID unmatchedId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThat(connection.getMetaData().getUserName()).isEqualTo("app_role");
            for (String call : List.of(
                    "SELECT payment.resolve_disbursement_tenant('no-such-reference')",
                    "SELECT payment.resolve_payment_transaction_tenant('no-such-reference')",
                    "SELECT payment.disbursement_gateway_reference_is_ambiguous('no-such-reference')",
                    "SELECT payment.payment_transaction_gateway_reference_is_ambiguous('no-such-reference')",
                    "SELECT payment.resolve_disbursement_tenant_by_id('" + unmatchedId + "')",
                    "SELECT payment.resolve_payment_transaction_tenant_by_id('" + unmatchedId + "')")) {
                try (ResultSet rs = statement.executeQuery(call)) {
                    assertThat(rs.next()).as("%s must return exactly one row", call).isTrue();
                }
            }
        }
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

    /**
     * M6 (Task 1) addition: direct SQL round-trip through app_role's own restricted connection --
     * proves the GRANT block in claims/V2 actually took effect under the real runtime identity,
     * not just the migration/superuser identity this suite's other integration tests use. Same
     * "app_role, not just the migration text" proof this class exists for, applied to claims'
     * grants for the first time -- this class had zero claims coverage until now (Task 11's own
     * final-verification pass found the gap and reported it rather than letting it merge silently,
     * which is exactly the vacuous-verification trap this class was built to close for every prior
     * module). The UPDATE assertion proves claim.status is genuinely mutable under app_role (the
     * claim state machine transitions many times over a real claim's life), unlike the genuinely
     * append-only policy.endorsement tested above.
     */
    @Test
    void appRoleCanReadWriteAndUpdateAClaim() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO claims.claim (tenant_id, policy_number, claimant_party_id, claim_type, date_of_event, details) " +
                 "VALUES (?, ?, ?, 'MATURITY', ?, ?::jsonb)")) {
            insert.setObject(1, tenantId);
            insert.setString(2, "APPROLE-CLAIM-01");
            insert.setObject(3, UUID.randomUUID());
            insert.setObject(4, LocalDate.of(2026, 1, 1));
            insert.setString(5, "{\"claimType\":\"MATURITY\",\"maturityDate\":\"2026-01-01\"}");
            assertThat(insert.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not insert into claims.claim: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                 "SELECT status FROM claims.claim WHERE policy_number = ?")) {
            select.setString(1, "APPROLE-CLAIM-01");
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).as("app_role could not read back the row it just inserted").isTrue();
                assertThat(rs.getString(1)).isEqualTo("REGISTERED");
            }
        } catch (SQLException e) {
            fail("app_role could not select from claims.claim: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE claims.claim SET status = 'APPROVED' WHERE policy_number = ?")) {
            update.setString(1, "APPROLE-CLAIM-01");
            assertThat(update.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not update claims.claim: " + e.getMessage());
        }
    }

    /**
     * M7 (Task 10). The UPDATE half is the point: {@code commission_statement.status} mutates
     * repeatedly over a statement's life (OPEN -> CLOSED -> PAYOUT_REQUESTED -> PAID, or ->
     * PAYOUT_FAILED and round again on a retry), so a grant that allowed only INSERT/SELECT would
     * leave the whole lifecycle dead in production while every superuser-connected test passed.
     *
     * <p>It also writes {@code PAYOUT_REQUESTED} specifically -- the longest value in the status
     * vocabulary at 16 characters. V1 sized the column VARCHAR(15) for its old two-state
     * vocabulary and V2's CHECK swap did not widen it, so this exact write failed with "value too
     * long for type character varying(15)" until M7 fixed it. Writing the longest value rather
     * than a convenient short one is what keeps that regression caught here too.
     */
    @Test
    void appRoleCanReadWriteAndUpdateACommissionStatement() {
        UUID tenantId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        TenantContext.set(tenantId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insertAgent = connection.prepareStatement(
                 "INSERT INTO distribution.agent_profile (agent_id, tenant_id, party_id, license_number, "
                 + "license_expiry_date) VALUES (?, ?, ?, 'APPROLE-LIC-01', ?)")) {
            insertAgent.setObject(1, agentId);
            insertAgent.setObject(2, tenantId);
            insertAgent.setObject(3, UUID.randomUUID());
            insertAgent.setObject(4, LocalDate.of(2030, 1, 1));
            assertThat(insertAgent.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not insert into distribution.agent_profile: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO distribution.commission_statement (tenant_id, agent_id, period, total_amount, "
                 + "total_currency, status) VALUES (?, ?, '2026-01', 10000.00, 'TZS', 'OPEN')")) {
            insert.setObject(1, tenantId);
            insert.setObject(2, agentId);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not insert into distribution.commission_statement: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                 "SELECT status FROM distribution.commission_statement WHERE agent_id = ?")) {
            select.setObject(1, agentId);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).as("app_role could not read back the row it just inserted").isTrue();
                assertThat(rs.getString(1)).isEqualTo("OPEN");
            }
        } catch (SQLException e) {
            fail("app_role could not select from distribution.commission_statement: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE distribution.commission_statement SET status = 'PAYOUT_REQUESTED' WHERE agent_id = ?")) {
            update.setObject(1, agentId);
            assertThat(update.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not update distribution.commission_statement: " + e.getMessage());
        }
    }

    /**
     * M8 (Task 9). The UPDATE half is the point: {@code confirmed_at} is stamped strictly AFTER the
     * row is written -- a recovery is calculated first (event-driven, on claim settlement) and
     * confirmed later, sometime after, once staff verify the reinsurer actually paid -- so a grant
     * allowing only INSERT/SELECT would leave recovery confirmation dead in production while every
     * superuser-connected test stayed green. {@code claim_recovery.treaty_id} is a real FK, so a
     * treaty is inserted first.
     */
    @Test
    void appRoleCanReadWriteAndUpdateAClaimRecovery() {
        UUID tenantId = UUID.randomUUID();
        UUID treatyId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        UUID recoveryId = UUID.randomUUID();
        TenantContext.set(tenantId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insertTreaty = connection.prepareStatement(
                 "INSERT INTO reinsurance.reinsurance_treaty (treaty_id, tenant_id, reinsurer_name, treaty_type, "
                 + "retention_limit_amount, retention_limit_currency, effective_from) "
                 + "VALUES (?, ?, 'Africa Re', 'XOL', 1500000.00, 'TZS', CURRENT_DATE)")) {
            insertTreaty.setObject(1, treatyId);
            insertTreaty.setObject(2, tenantId);
            assertThat(insertTreaty.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not insert into reinsurance.reinsurance_treaty: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO reinsurance.claim_recovery (recovery_id, tenant_id, claim_id, treaty_id, "
                 + "recoverable_amount, recoverable_currency) VALUES (?, ?, ?, ?, 500000.00, 'TZS')")) {
            insert.setObject(1, recoveryId);
            insert.setObject(2, tenantId);
            insert.setObject(3, claimId);
            insert.setObject(4, treatyId);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not insert into reinsurance.claim_recovery: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                 "SELECT confirmed_at FROM reinsurance.claim_recovery WHERE recovery_id = ?")) {
            select.setObject(1, recoveryId);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).as("app_role could not read back the row it just inserted").isTrue();
                assertThat(rs.getTimestamp(1)).as("confirmed_at must start null").isNull();
            }
        } catch (SQLException e) {
            fail("app_role could not select from reinsurance.claim_recovery: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE reinsurance.claim_recovery SET confirmed_at = now() WHERE recovery_id = ?")) {
            update.setObject(1, recoveryId);
            assertThat(update.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not update reinsurance.claim_recovery: " + e.getMessage());
        }
    }

    /**
     * M9 (Task 9). Direct SQL round-trip through app_role's own restricted connection -- proves the
     * GRANT block in finaccounting/V2 actually took effect under the real runtime identity, not
     * just the migration/superuser identity this suite's other integration tests use. This matters
     * more here than for any prior module: V1's {@code REVOKE UPDATE, DELETE ON
     * finaccounting.gl_posting FROM app_role} ran with NO PRIOR GRANT anywhere in the file, so
     * app_role had NO privileges on the ledger at all -- this platform's append-only GL had never
     * once been verified as WRITABLE before V2 (see V2's own javadoc, which records this same
     * history). Proves INSERT and SELECT on both {@code journal_entry} (the aggregate root) and
     * {@code gl_posting} (its legs, landing in the {@code gl_posting_2026_08} partition since this
     * suite runs in August 2026 -- itself only correctly protected because of this class's own
     * {@code policyloan/V2} migration-list fix above).
     */
    @Test
    void appRoleCanInsertAndSelectAJournalEntryAndAGlPosting() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        seedChartAccount(tenantId, "1000", "Cash / Mobile Money", "ASSET", "DR");
        seedChartAccount(tenantId, "2000", "Premiums", "LIABILITY", "CR");
        UUID journalEntryId = null;
        try {
            // One balanced journal in one transaction: since finaccounting V10 a journal balances at commit and its
            // lines may only be written by the transaction that wrote it.
            journalEntryId = postBalancedAsAppRole(tenantId, "billing.PremiumInvoiceGenerated", "approle-je-01",
                "APPROLE-GL-01", "1000", "2000", "15000.00");
        } catch (SQLException e) {
            fail("app_role could not insert into finaccounting.journal_entry and gl_posting: " + e.getMessage());
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                 "SELECT account_code FROM finaccounting.gl_posting WHERE journal_entry_id = ?")) {
            select.setObject(1, journalEntryId);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).as("app_role could not read back the row it just inserted").isTrue();
                assertThat(rs.getString(1)).isEqualTo("1000");
            }
        } catch (SQLException e) {
            fail("app_role could not select from finaccounting.gl_posting: " + e.getMessage());
        }
    }

    /**
     * M9 (Task 9). The append-only counterpart to
     * {@link #appRoleCanInsertAndSelectAJournalEntryAndAGlPosting}: proves app_role can genuinely
     * NOT update or delete either table, under its own real restricted connection -- V1's REVOKE
     * ran against a role that held nothing, so this guarantee had literally never been exercised
     * before V2 existed. Mirrors {@link #appRoleCannotUpdateOrDeletePolicyEndorsementsBecauseTheLedgerIsAppendOnly}'s
     * shape: a positive-control SELECT first (proving app_role does have real access to both
     * tables), then the two denials, so the failure is specific to UPDATE/DELETE rather than a
     * blanket permission problem.
     */
    @Test
    void appRoleCannotUpdateOrDeleteAJournalEntryOrAGlPostingBecauseTheLedgerIsAppendOnly() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        seedChartAccount(tenantId, "5000", "Claims Expense", "EXPENSE", "DR");
        seedChartAccount(tenantId, "1000", "Cash / Mobile Money", "ASSET", "DR");
        UUID journalEntryId = postBalancedAsAppRole(tenantId, "claims.ClaimSettled", "approle-je-02", "APPROLE-GL-02",
            "5000", "1000", "25000.00");

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThat(connection.getMetaData().getUserName()).isEqualTo("app_role");

            statement.execute("SELECT count(*) FROM finaccounting.journal_entry");   // positive control
            statement.execute("SELECT count(*) FROM finaccounting.gl_posting");      // positive control

            assertThatThrownBy(() -> statement.execute("UPDATE finaccounting.journal_entry SET policy_number = "
                    + "'tamper' WHERE journal_entry_id = '" + journalEntryId + "'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.execute(
                    "DELETE FROM finaccounting.journal_entry WHERE journal_entry_id = '" + journalEntryId + "'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.execute("UPDATE finaccounting.gl_posting SET account_code = "
                    + "'9999' WHERE journal_entry_id = '" + journalEntryId + "'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.execute(
                    "DELETE FROM finaccounting.gl_posting WHERE journal_entry_id = '" + journalEntryId + "'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
        }
    }

    /**
     * M9 (Task 9). <b>Closes a gap in {@code trg_partition_controls}'s OWN test coverage, not just
     * a finaccounting gap.</b> Every existing proof of that mechanism -- policyloan's own, and this
     * class's insert/select/update/delete tests above -- only ever exercises the BACKFILL path:
     * partitions that already existed at the moment {@code policyloan/V2} ran. Nothing anywhere on
     * this platform had, until this test, created a partition <em>after</em> the event trigger was
     * already installed and asserted it was protected with zero manual steps -- which is exactly
     * the real pg_partman maintenance-job scenario {@code policyloan/V2}'s own code comment
     * describes and names {@code finaccounting.gl_posting} as a future beneficiary of.
     *
     * <p>Issues {@code CREATE TABLE ... PARTITION OF} directly as the superuser/migration role
     * (mirroring what pg_partman's maintenance job does under its own configured role), then reads
     * back through the exact {@code pg_class}/{@code pg_policy}/{@code has_table_privilege} shape
     * {@code db-migrations/_post-migration/verify-partition-controls.sql} uses in production.
     */
    @Test
    void newlyCreatedGlPostingPartitionInheritsRlsPolicyAndAppendOnlyPrivilegesWithNoManualStep() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE finaccounting.gl_posting_2030_01 PARTITION OF finaccounting.gl_posting "
                + "FOR VALUES FROM ('2030-01-01') TO ('2030-02-01')");

            try (ResultSet rs = statement.executeQuery(
                    "SELECT relrowsecurity FROM pg_class WHERE oid = 'finaccounting.gl_posting_2030_01'::regclass")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).as("RLS must be enabled on a brand-new partition with zero manual steps")
                    .isTrue();
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT count(*) FROM pg_policy WHERE polrelid = 'finaccounting.gl_posting_2030_01'::regclass")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("exactly one tenant-isolation policy must be mirrored").isEqualTo(1);
            }
            // BOTH halves are asserted deliberately (M9 final review, finding M1). Checking only the
            // two denials would let the exact defect this milestone exists to fix pass unnoticed:
            // finaccounting/V1's REVOKE-with-no-prior-GRANT left app_role holding NOTHING on the
            // ledger, which satisfies "no UPDATE, no DELETE" perfectly while making the append-only
            // GL unwritable. A regression that revoked everything again would be invisible to a
            // negative-only test.
            try (ResultSet rs = statement.executeQuery(
                    "SELECT has_table_privilege('app_role', 'finaccounting.gl_posting_2030_01'::regclass, 'UPDATE'), "
                    + "has_table_privilege('app_role', 'finaccounting.gl_posting_2030_01'::regclass, 'DELETE'), "
                    + "has_table_privilege('app_role', 'finaccounting.gl_posting_2030_01'::regclass, 'SELECT'), "
                    + "has_table_privilege('app_role', 'finaccounting.gl_posting_2030_01'::regclass, 'INSERT')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).as("app_role must NOT hold UPDATE on a brand-new partition").isFalse();
                assertThat(rs.getBoolean(2)).as("app_role must NOT hold DELETE on a brand-new partition").isFalse();
                assertThat(rs.getBoolean(3))
                    .as("app_role MUST still hold SELECT on a brand-new partition -- an append-only "
                        + "ledger nobody can read is as broken as one nobody can write")
                    .isTrue();
                assertThat(rs.getBoolean(4))
                    .as("app_role MUST still hold INSERT on a brand-new partition -- this is the exact "
                        + "V1 defect (REVOKE with no prior GRANT) that finaccounting/V2 exists to fix")
                    .isTrue();
            }
        }
    }

    /**
     * M10 (Task 9). Direct SQL round-trip through app_role's own restricted connection -- proves
     * the GRANT block in regreporting/V2 actually took effect under the real runtime identity, not
     * just the migration/superuser identity this suite's other integration tests use.
     *
     * <p><b>Opposite polarity from finaccounting's append-only guard above, deliberately.</b>
     * {@link #appRoleCannotUpdateOrDeleteAJournalEntryOrAGlPostingBecauseTheLedgerIsAppendOnly}
     * above asserts UPDATE/DELETE are REVOKED, because finaccounting's GL is append-only. Nothing in
     * regreporting is append-only: {@code policy_movement} (and its sibling fact tables) is upserted
     * in place as events arrive, and {@code return_line} rows are deleted and rewritten wholesale
     * every time a return is regenerated (design spec §7 -- generateReturn is idempotent per
     * (tenant, return_type, period) and REPLACES its prior lines rather than accumulating
     * duplicates). So THIS test asserts UPDATE and DELETE actually SUCCEED for app_role on all three
     * tables below -- do NOT "fix" this into a REVOKE-style assertion to match the finaccounting
     * shape above; that polarity would be wrong for this module.
     */
    @Test
    void appRoleCanInsertSelectUpdateAndDeleteRegreportingProjectionsAndReturnLines() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        TenantContext.set(tenantId);

        // --- policy_movement: upserted in place by projections, so UPDATE/DELETE must succeed. ---
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO regreporting.policy_movement (tenant_id, period, product_id, policies_issued) " +
                 "VALUES (?, '2026-Q3', ?, 1)")) {
            insert.setObject(1, tenantId);
            insert.setObject(2, productId);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not insert into regreporting.policy_movement: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                 "SELECT policies_issued FROM regreporting.policy_movement WHERE tenant_id = ? AND period = '2026-Q3' AND product_id = ?")) {
            select.setObject(1, tenantId);
            select.setObject(2, productId);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).as("app_role could not read back the row it just inserted").isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        } catch (SQLException e) {
            fail("app_role could not select from regreporting.policy_movement: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE regreporting.policy_movement SET policies_issued = 2 WHERE tenant_id = ? AND period = '2026-Q3' AND product_id = ?")) {
            update.setObject(1, tenantId);
            update.setObject(2, productId);
            assertThat(update.executeUpdate())
                .as("regreporting projections are upserted in place, not append-only -- UPDATE must succeed")
                .isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not update regreporting.policy_movement: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                 "DELETE FROM regreporting.policy_movement WHERE tenant_id = ? AND period = '2026-Q3' AND product_id = ?")) {
            delete.setObject(1, tenantId);
            delete.setObject(2, productId);
            assertThat(delete.executeUpdate())
                .as("regreporting projections are upserted in place, not append-only -- DELETE must succeed")
                .isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not delete from regreporting.policy_movement: " + e.getMessage());
        }

        // --- return_definition_line: the return catalog's data half, mutable by a seed change. ---
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO regreporting.return_definition_line (tenant_id, return_type, line_no, line_code, label, metric_name) " +
                 "VALUES (?, 'APPROLE_TEST_RETURN', 1, 'AR-01', 'App Role Test Line', 'POLICIES_ISSUED')")) {
            insert.setObject(1, tenantId);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not insert into regreporting.return_definition_line: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                 "SELECT label FROM regreporting.return_definition_line WHERE tenant_id = ? AND return_type = 'APPROLE_TEST_RETURN' AND line_no = 1")) {
            select.setObject(1, tenantId);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).as("app_role could not read back the row it just inserted").isTrue();
                assertThat(rs.getString(1)).isEqualTo("App Role Test Line");
            }
        } catch (SQLException e) {
            fail("app_role could not select from regreporting.return_definition_line: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE regreporting.return_definition_line SET label = 'Updated Label' WHERE tenant_id = ? AND return_type = 'APPROLE_TEST_RETURN' AND line_no = 1")) {
            update.setObject(1, tenantId);
            assertThat(update.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not update regreporting.return_definition_line: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                 "DELETE FROM regreporting.return_definition_line WHERE tenant_id = ? AND return_type = 'APPROLE_TEST_RETURN' AND line_no = 1")) {
            delete.setObject(1, tenantId);
            assertThat(delete.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not delete from regreporting.return_definition_line: " + e.getMessage());
        }

        // --- return_line: deleted and rewritten wholesale every time a return regenerates. Needs a
        // parent regulatory_return row first -- return_id is a real FK, ON DELETE CASCADE. ---
        UUID returnId;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO regreporting.regulatory_return (tenant_id, return_type, period, status) " +
                 "VALUES (?, 'APPROLE_TEST_RETURN', '2026-Q3', 'READY') RETURNING return_id")) {
            insert.setObject(1, tenantId);
            try (ResultSet rs = insert.executeQuery()) {
                assertThat(rs.next()).as("app_role could not insert into regreporting.regulatory_return").isTrue();
                returnId = (UUID) rs.getObject(1);
            }
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO regreporting.return_line (return_id, tenant_id, line_no, line_code, label, metric_name, numeric_value) " +
                 "VALUES (?, ?, 1, 'AR-01', 'App Role Test Line', 'POLICIES_ISSUED', 1)")) {
            insert.setObject(1, returnId);
            insert.setObject(2, tenantId);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not insert into regreporting.return_line: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                 "SELECT numeric_value FROM regreporting.return_line WHERE return_id = ? AND line_no = 1")) {
            select.setObject(1, returnId);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).as("app_role could not read back the row it just inserted").isTrue();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo(BigDecimal.ONE);
            }
        } catch (SQLException e) {
            fail("app_role could not select from regreporting.return_line: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE regreporting.return_line SET numeric_value = 2 WHERE return_id = ? AND line_no = 1")) {
            update.setObject(1, returnId);
            assertThat(update.executeUpdate())
                .as("return_line must be genuinely mutable under app_role -- a regenerated return rewrites its lines, this is not an append-only ledger")
                .isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not update regreporting.return_line: " + e.getMessage());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                 "DELETE FROM regreporting.return_line WHERE return_id = ? AND line_no = 1")) {
            delete.setObject(1, returnId);
            assertThat(delete.executeUpdate())
                .as("return_line must be genuinely deletable under app_role -- regeneration deletes and rewrites lines wholesale")
                .isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not delete from regreporting.return_line: " + e.getMessage());
        }
    }

    /** Since finaccounting/V3, {@code gl_posting.account_code} is a real foreign key into
     * {@code chart_of_account (tenant_id, account_code)}, so a posting cannot be inserted for a
     * tenant with no chart. Production seeds the chart in every listener before posting
     * ({@code ChartOfAccountSeeder}); these direct-SQL privilege tests write one row by hand instead,
     * since what they are asserting is privileges, not the seeder. Inserted through app_role's own
     * restricted connection on purpose: doing so also confirms app_role really can write
     * chart_of_account under RLS, which V2 grants and nothing else here exercises. */
    private void seedChartAccount(UUID tenantId, String accountCode, String name, String accountType,
                                  String normalBalance) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO finaccounting.chart_of_account (tenant_id, account_code, name, account_type, "
                 + "normal_balance, posting_mode, created_by) VALUES (?, ?, ?, ?, ?, 'AUTO', 'system:test')")) {
            insert.setObject(1, tenantId);
            insert.setString(2, accountCode);
            insert.setString(3, name);
            insert.setString(4, accountType);
            insert.setString(5, normalBalance);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            fail("app_role could not seed finaccounting.chart_of_account: " + e.getMessage());
        }
    }

    /**
     * One balanced two-line journal, written through app_role's own connection in ONE transaction -- the only way
     * finaccounting V10 accepts a journal (balanced at commit; lines from the journal's own transaction). The accounts
     * are seeded AUTO, so an event journal may post to them.
     */
    private UUID postBalancedAsAppRole(UUID tenantId, String sourceEvent, String sourceRef, String policyNumber,
                                       String debitCode, String creditCode, String amount) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            UUID journalEntryId;
            try (PreparedStatement insertEntry = connection.prepareStatement(
                    "INSERT INTO finaccounting.journal_entry (tenant_id, source_event, source_ref, period, policy_number) "
                    + "VALUES (?, ?, ?, '2026-08', ?) RETURNING journal_entry_id")) {
                insertEntry.setObject(1, tenantId);
                insertEntry.setString(2, sourceEvent);
                insertEntry.setString(3, sourceRef);
                insertEntry.setString(4, policyNumber);
                try (ResultSet rs = insertEntry.executeQuery()) {
                    rs.next();
                    journalEntryId = (UUID) rs.getObject(1);
                }
            }
            for (String[] leg : new String[][] {{debitCode, "DR"}, {creditCode, "CR"}}) {
                try (PreparedStatement insertPosting = connection.prepareStatement(
                        "INSERT INTO finaccounting.gl_posting (tenant_id, journal_entry_id, account_code, direction, "
                        + "amount, currency, period, policy_number, posting_type, source_event, source_ref) "
                        + "VALUES (?, ?, ?, ?, ?::numeric, 'TZS', '2026-08', ?, ?, ?, ?)")) {
                    insertPosting.setObject(1, tenantId);
                    insertPosting.setObject(2, journalEntryId);
                    insertPosting.setString(3, leg[0]);
                    insertPosting.setString(4, leg[1]);
                    insertPosting.setString(5, amount);
                    insertPosting.setString(6, policyNumber);
                    insertPosting.setString(7, sourceEvent);
                    insertPosting.setString(8, sourceEvent);
                    insertPosting.setString(9, sourceRef);
                    assertThat(insertPosting.executeUpdate()).isEqualTo(1);
                }
            }
            connection.commit();
            return journalEntryId;
        }
    }
}
