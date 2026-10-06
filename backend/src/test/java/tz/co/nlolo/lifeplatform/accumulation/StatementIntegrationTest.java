package tz.co.nlolo.lifeplatform.accumulation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.api.*;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.accumulation.application.AccumulationApiImpl;
import tz.co.nlolo.lifeplatform.accumulation.application.StatementDrain;
import tz.co.nlolo.lifeplatform.product.api.AccumulationChargeRow;
import tz.co.nlolo.lifeplatform.product.api.AccumulationPlan;
import tz.co.nlolo.lifeplatform.product.api.ValueBasis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Statements against a real database and a real object store: one reconciles and its PDF carries the
 * closing balance, regenerating with nothing new returns the same filing, a later entry makes a new one,
 * and the annual run files last year once and asks for the SMS.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AccumulationTestFixtures.class)
class StatementIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    /** Its OWN object store: a statement is a real PDF filed through DocumentApi. */
    @Container
    static MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("minio.endpoint", MINIO::getS3URL);
        registry.add("minio.access-key", MINIO::getUserName);
        registry.add("minio.secret-key", MINIO::getPassword);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/party/V5__registered_by_name.sql",
            "db-migrations/party/V6__client_reference.sql",
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
            "db-migrations/accumulation/V1__create_accumulation_schema.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
            "db-migrations/benefitpayout/V3__withholding.sql",
            "db-migrations/benefitpayout/V4__withholding_rule_end.sql",
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
            "db-migrations/underwriting/V18__sale_channel_and_branch.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
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
            "db-migrations/policy/V37__sale_classification.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/document/V1__create_document_schema.sql",
            "db-migrations/document/V2__add_content_type_and_file_name.sql",
            "db-migrations/document/V3__rls_fail_closed.sql",
            "db-migrations/document/V4__enrolment_schedule_document_type.sql",
            "db-migrations/document/V5__exits_file_document_type.sql",
            "db-migrations/document/V6__account_statement_document_type.sql",
            "db-migrations/communication/V1__create_communication_schema.sql",
            "db-migrations/communication/V2__template_identity.sql",
            "db-migrations/communication/V3__seed_offer_templates.sql",
            "db-migrations/communication/V4__dispatch_reason_and_policy.sql",
            "db-migrations/communication/V5__dispatch_claimed_status.sql",
            "db-migrations/communication/V6__grants_and_rls.sql",
            "db-migrations/communication/V7__null_safe_rls_and_pending_reminders.sql",
            "db-migrations/communication/V8__platform_default_templates.sql",
            "db-migrations/communication/V9__payment_received_template.sql",
            "db-migrations/communication/V10__account_statement_template.sql");

        // The container never runs compose's minio-init job, so the bucket is made here --
        // ACCOUNT_STATEMENT routes to the general bucket (MinioDocumentStorage.bucketFor's default arm).
        MinioClient minio = MinioClient.builder()
            .endpoint(MINIO.getS3URL()).credentials(MINIO.getUserName(), MINIO.getPassword()).build();
        minio.makeBucket(MakeBucketArgs.builder().bucket("policy-documents").build());
    }

    private static final UUID TENANT = UUID.randomUUID();
    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private AccumulationApiImpl api;
    @Autowired private StatementDrain drain;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    private String fundedLastYear() {
        LocalDate start = LocalDate.now().withDayOfYear(1).minusMonths(6);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, start);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("100000.00"), start);
        asTenant(() -> { api.postMonthEnds(issued.policyNumber(), LocalDate.now()); return null; });
        return issued.policyNumber();
    }

    @Test
    void aStatementReconcilesAndItsPdfCarriesTheClosingBalance() throws Exception {
        String policy = fundedLastYear();
        LocalDate from = LocalDate.now().withDayOfYear(1).minusYears(1);
        LocalDate to = LocalDate.now().withDayOfYear(1).minusDays(1);
        StatementView view = asTenant(() -> api.statement(policy, from, to));
        StatementRecordView record = asTenant(() -> api.generateStatement(policy, from, to, "staff-one"));

        byte[] pdf = asTenant(() -> api.statementPdf(record.statementId()));
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            assertThat(text).contains(policy);
            assertThat(text).contains(new java.text.DecimalFormat("#,##0.00",
                java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ROOT)).format(view.closingBalance()));
        }
    }

    @Test
    void regeneratingWithNothingNewReturnsTheSameStatement() {
        String policy = fundedLastYear();
        LocalDate from = LocalDate.now().withDayOfYear(1).minusYears(1);
        LocalDate to = LocalDate.now().withDayOfYear(1).minusDays(1);
        var first = asTenant(() -> api.generateStatement(policy, from, to, "staff-one"));
        var second = asTenant(() -> api.generateStatement(policy, from, to, "staff-two"));
        assertThat(second.statementId()).isEqualTo(first.statementId());
        assertThat(asTenant(() -> api.listStatements(policy))).hasSize(1);
    }

    @Test
    void aLaterEntryMakesANewStatementAndLeavesTheOldOneAlone() {
        String policy = fundedLastYear();
        LocalDate from = LocalDate.now().withDayOfYear(1).minusYears(1);
        LocalDate to = LocalDate.now();
        var first = asTenant(() -> api.generateStatement(policy, from, to, "staff-one"));
        fixtures.collectPremium(TENANT, policy, UUID.randomUUID(), new BigDecimal("10000.00"), LocalDate.now());
        var second = asTenant(() -> api.generateStatement(policy, from, to, "staff-one"));
        assertThat(second.statementId()).isNotEqualTo(first.statementId());
        assertThat(second.lastSeq()).isGreaterThan(first.lastSeq());
    }

    @Test
    void theAnnualRunFilesLastYearOnceAndAsksForTheSms() {
        String policy = fundedLastYear();
        drain.drain();
        drain.drain();
        assertThat(asTenant(() -> api.listStatements(policy))).singleElement()
            .satisfies(s -> assertThat(s.periodTo()).isEqualTo(LocalDate.now().withDayOfYear(1).minusDays(1)));
        // communication's dispatch row for ACCOUNT_STATEMENT, read over the owner JDBC connection.
        assertThat(dispatchCount(policy, "ACCOUNT_STATEMENT")).isEqualTo(1);
    }

    /** communication's dispatch rows for one policy and template, over the owner connection. */
    private int dispatchCount(String policyNumber, String templateKey) {
        try (var c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var st = c.prepareStatement("SELECT count(*) FROM communication.notification_dispatch WHERE policy_number = ? AND template_key = ?")) {
            st.setString(1, policyNumber);
            st.setString(2, templateKey);
            var rs = st.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
