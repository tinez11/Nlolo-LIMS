package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V15's constraints, asserted by name.
 *
 * <p>A migration that merely applies proves nothing about whether its constraints refuse
 * anything, and these three are the difference between a controlled intake and a
 * counterparty writing cover unattended.
 *
 * <p>Written against JDBC rather than the API because the service does not exist until
 * the next task, and because these are database guarantees rather than service rules --
 * the service's own checks are the readable errors in front of them.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class EnrolmentSubmissionConstraintTest {

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
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/policy/V10__one_policy_per_underwriting_case.sql",
            "db-migrations/policy/V11__not_taken_up_status.sql",
            "db-migrations/policy/V8__group_policies_have_no_single_life_assured.sql",
            "db-migrations/policy/V9__group_scheme_and_members.sql",
            "db-migrations/policy/V13__freeform_members.sql",
            "db-migrations/policy/V14__credit_life_scheme.sql",
            "db-migrations/policy/V15__enrolment_submission.sql",
            "db-migrations/policy/V16__insurer_issued_member_reference.sql",
            "db-migrations/policy/V17__enrolment_row_member_reference.sql",
            "db-migrations/policy/V18__scheme_premium_rate.sql",
            "db-migrations/policy/V19__enrolment_premium.sql",
            "db-migrations/policy/V20__member_exit_reason.sql",
            "db-migrations/policy/V22__member_promoted_party.sql",
            "db-migrations/policy/V23__member_open_death_claim.sql",
            "db-migrations/policy/V24__issuance_record.sql",
            "db-migrations/policy/V25__credit_life_premium_basis.sql",
            "db-migrations/policy/V26__enrolment_stated_premium.sql",
            "db-migrations/policy/V27__expired_status.sql",
            "db-migrations/policy/V28__policies_due_to_expire.sql",
            "db-migrations/policy/V29__paid_up.sql",
            "db-migrations/policy/V30__surrender.sql",
            "db-migrations/policy/V31__free_look_status.sql",
            "db-migrations/policy/V37__sale_classification.sql",
            "db-migrations/policy/V38__group_funeral_scheme.sql");
    }

    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID tenantId;
    private String policyNumber;

    @BeforeEach
    void seedScheme() {
        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        policyNumber = "GRP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // A scheme row is all these constraints need; the policy row behind it is a plain
        // FK target. Inserted directly because PolicyApi is not the subject here.
        jdbcTemplate.update("""
            insert into policy.policy
                (policy_number, tenant_id, policyholder_party_id, product_id, product_version_id,
                 product_category, sum_assured_amount, sum_assured_currency, premium_amount,
                 premium_currency, premium_frequency, status)
            values (?, ?, ?, ?, ?, 'CREDIT_LIFE', 1000000.00, 'TZS', 5000.00, 'TZS', 'ANNUALLY', 'ACTIVE')
            """, policyNumber, tenantId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        // premium_rate_percent is not optional on a loan basis -- V18's
        // chk_group_scheme_rate_iff_loan_basis refuses a credit-life scheme without the rate
        // its lender agreed, because a scheme that reached its first accepted file without one
        // could not price a single member. V25 then requires the basis alongside the rate.
        jdbcTemplate.update("""
            insert into policy.group_scheme
                (policy_number, tenant_id, benefit_basis, currency, interest_method,
                 repayment_frequency, premium_rate_percent, premium_basis)
            values (?, ?, 'AMORTISING_LOAN', 'TZS', 'FLAT_RATE', 'MONTHLY', 0.5000, 'PER_ANNUM_ON_PRINCIPAL')
            """, policyNumber, tenantId);
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private UUID insertPendingSubmission(String submittedBy) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            insert into policy.enrolment_submission
                (submission_id, tenant_id, policy_number, document_ref, file_name,
                 row_count, submitted_by)
            values (?, ?, ?, ?, 'june.csv', 3, ?)
            """, id, tenantId, policyNumber, "doc-" + id, submittedBy);
        return id;
    }

    @Test
    void onlyOneSubmissionMayBeInFlightPerScheme() {
        insertPendingSubmission("staff.one");

        // Two files in flight can enrol the same loan twice, and propose-then-accept
        // widens the window between reading the schedule and writing to it.
        assertThatThrownBy(() -> insertPendingSubmission("staff.two"))
            .hasMessageContaining("ux_enrolment_submission_in_flight");
    }

    @Test
    void anAcceptedSubmissionFreesTheSchemeForNextMonth() {
        UUID first = insertPendingSubmission("staff.one");
        jdbcTemplate.update("update policy.enrolment_submission set status='ACCEPTED', "
            + "accepted_by='staff.two', accepted_at=now(), premium_total=84000.00 "
            + "where submission_id=?", first);

        // The index is partial on PENDING, so history never blocks the next file.
        assertThatCode(() -> insertPendingSubmission("staff.three")).doesNotThrowAnyException();
    }

    @Test
    void aWithdrawnSubmissionAlsoFreesTheScheme() {
        UUID first = insertPendingSubmission("staff.one");
        jdbcTemplate.update("update policy.enrolment_submission set status='WITHDRAWN' "
            + "where submission_id=?", first);

        assertThatCode(() -> insertPendingSubmission("staff.one")).doesNotThrowAnyException();
    }

    @Test
    void theSamePersonCannotAcceptWhatTheySubmitted() {
        UUID id = insertPendingSubmission("staff.one");

        // One user who can upload a file and then accept it has an audit trail and no
        // control.
        assertThatThrownBy(() -> jdbcTemplate.update(
            "update policy.enrolment_submission set status='ACCEPTED', accepted_by='staff.one', "
                + "accepted_at=now(), premium_total=84000.00 where submission_id=?", id))
            .hasMessageContaining("chk_enrolment_submission_two_person");
    }

    @Test
    void anAcceptedSubmissionMustSayWhoAcceptedItAndWhen() {
        UUID id = insertPendingSubmission("staff.one");

        assertThatThrownBy(() -> jdbcTemplate.update(
            "update policy.enrolment_submission set status='ACCEPTED' where submission_id=?", id))
            .hasMessageContaining("chk_enrolment_submission_accepted_complete");
    }

    @Test
    void anAcceptedSubmissionMustAlsoSayWhatItCost() {
        UUID id = insertPendingSubmission("staff.one");

        // The same constraint, widened by V19 rather than joined by a second one. A file is
        // accepted and the premium it earned is knowable at that instant; an acceptance that
        // did not record it would leave an invoice with nothing to be raised from.
        //
        // Zero is legal -- a file whose every row was rejected enrols nobody and earns
        // nothing -- so it is the NULL that is refused, not the amount.
        assertThatThrownBy(() -> jdbcTemplate.update(
            "update policy.enrolment_submission set status='ACCEPTED', accepted_by='staff.two', "
                + "accepted_at=now() where submission_id=?", id))
            .hasMessageContaining("chk_enrolment_submission_accepted_complete");

        assertThatCode(() -> jdbcTemplate.update(
            "update policy.enrolment_submission set status='ACCEPTED', accepted_by='staff.two', "
                + "accepted_at=now(), premium_total=0.00 where submission_id=?", id))
            .doesNotThrowAnyException();
    }

    @Test
    void aRejectedRowMustCarryAReason() {
        UUID submissionId = insertPendingSubmission("staff.one");

        // The reason is the entire product of this feature; a rejection without one is a
        // row nobody can act on.
        assertThatThrownBy(() -> jdbcTemplate.update("""
            insert into policy.enrolment_submission_row
                (tenant_id, submission_id, line_number, loan_account_number, outcome)
            values (?, ?, 2, 'LN-1', 'REJECTED')
            """, tenantId, submissionId))
            .hasMessageContaining("chk_enrolment_row_rejection_has_reason");
    }

    /** An enrollable row must carry everything needed to create the cover. */
    private void insertEnrollableRow(UUID submissionId, int lineNumber, String accountNumber) {
        jdbcTemplate.update("""
            insert into policy.enrolment_submission_row
                (tenant_id, submission_id, line_number, loan_account_number, borrower_full_name,
                 borrower_date_of_birth, loan_principal_amount, loan_term_months,
                 disbursement_date, outcome)
            values (?, ?, ?, ?, 'Amina Hassan Mwinyi', DATE '1988-03-14', 8500000.00, 48,
                    DATE '2026-06-30', 'ENROLLED')
            """, tenantId, submissionId, lineNumber, accountNumber);
    }

    @Test
    void oneLineOfTheLendersFileAppearsOnceInASubmission() {
        UUID submissionId = insertPendingSubmission("staff.one");
        insertEnrollableRow(submissionId, 2, "LN-1");

        assertThatThrownBy(() -> insertEnrollableRow(submissionId, 2, "LN-2"))
            .hasMessageContaining("enrolment_submission_row_submission_id_line_number_key");
    }

    @Test
    void anEnrollableRowMissingItsLoanIsRefused() {
        UUID submissionId = insertPendingSubmission("staff.one");

        // Without this, acceptance could reach a row it cannot enrol, half-way through a
        // file, having already put earlier borrowers on risk.
        assertThatThrownBy(() -> jdbcTemplate.update("""
            insert into policy.enrolment_submission_row
                (tenant_id, submission_id, line_number, loan_account_number, outcome)
            values (?, ?, 2, 'LN-INCOMPLETE', 'ENROLLED')
            """, tenantId, submissionId))
            .hasMessageContaining("chk_enrolment_row_enrollable_is_complete");
    }

    @Test
    void aRejectedRowNeedsNoLoanAtAll() {
        UUID submissionId = insertPendingSubmission("staff.one");

        // A row that failed at parse has no usable values by definition.
        assertThatCode(() -> jdbcTemplate.update("""
            insert into policy.enrolment_submission_row
                (tenant_id, submission_id, line_number, outcome, reason_code, reason)
            values (?, ?, 2, 'REJECTED', 'MALFORMED_VALUE', 'not a date. NOT COVERED.')
            """, tenantId, submissionId)).doesNotThrowAnyException();
    }
}
