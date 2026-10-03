package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.product.api.*;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

/**
 * A lender's monthly exits file, end to end.
 *
 * <p>The assertion this class exists for is the mirror of the enrolment one: submitting takes
 * NOBODY off cover. Everything else follows from it.
 *
 * <p>Getting this wrong is quieter than getting enrolment wrong, which is why the control is
 * the same. A borrower wrongly left uninsured by an enrolment file eventually surfaces — the
 * lender chases it, or a claim is refused and somebody asks why. A borrower wrongly taken OFF
 * cover surfaces only at the claim.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class ExitFileIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    /*
     * Its OWN MinIO: submit() stores the lender's file through DocumentApi. Without this the
     * class silently uses whatever object store the dev compose stack happens to be running,
     * which passes here and fails in CI.
     */
    @Container
    static MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("minio.endpoint", MINIO::getS3URL);
        registry.add("minio.access-key", MINIO::getUserName);
        registry.add("minio.secret-key", MINIO::getPassword);
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
            "db-migrations/product/V14__credit_life_category.sql",
            "db-migrations/product/V15__exclusion_periods.sql",
            "db-migrations/product/V16__base_rate_term_bands.sql",
            "db-migrations/product/V17__cash_value.sql",
            "db-migrations/product/V18__payout_schedule.sql",
            "db-migrations/product/V19__accumulation_terms.sql",
            "db-migrations/product/V20__deposit_rate_grid.sql",
            "db-migrations/product/V21__bonus_terms.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
            "db-migrations/accumulation/V1__create_accumulation_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/underwriting/V4__proposal_identity.sql",
            "db-migrations/underwriting/V5__explicit_decision.sql",
            "db-migrations/underwriting/V6__proposal_terms_and_beneficiaries.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/underwriting/V8__rating_multiplier.sql",
            "db-migrations/underwriting/V9__group_proposal.sql",
            "db-migrations/underwriting/V10__issuance_failure.sql",
            "db-migrations/underwriting/V11__member_evidence_case.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
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
            "db-migrations/policy/V21__exit_submission.sql",
            "db-migrations/document/V1__create_document_schema.sql",
            "db-migrations/document/V2__add_content_type_and_file_name.sql",
            "db-migrations/document/V4__enrolment_schedule_document_type.sql",
            "db-migrations/document/V5__exits_file_document_type.sql",
            "db-migrations/document/V6__account_statement_document_type.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");

        MinioClient minio = MinioClient.builder()
            .endpoint(MINIO.getS3URL())
            .credentials(MINIO.getUserName(), MINIO.getPassword())
            .build();
        for (String bucket : new String[] {"policy-documents", "kyc-evidence",
                "underwriting-evidence", "claim-evidence"}) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private ExitApi exitApi;

    private static final AtomicInteger SEQ = new AtomicInteger(3000);

    private static final String HEADER =
        "member_reference,exit_date,exit_reason,outstanding_balance_at_exit\n";

    private static final LocalDate DISBURSED = LocalDate.of(2026, 8, 3);
    private static final LocalDate EXIT_DATE = LocalDate.of(2026, 11, 3);

    private String scheme;
    private List<PolicyMemberView> members;

    @BeforeEach
    void seedSchemeOfThree() {
        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        scheme = issueCreditLifeScheme(List.of(
            borrower("Amina Hassan Mwinyi", LocalDate.of(1988, 3, 14)),
            borrower("Joseph Mkenda", LocalDate.of(1975, 11, 2)),
            borrower("Grace Shirima", LocalDate.of(1992, 6, 21))));
        members = policyApi.listMembers(scheme, null, null, PageRequest.of(0, 10)).getContent();
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private ByteArrayInputStream csv(String body) {
        return new ByteArrayInputStream((HEADER + body).getBytes(StandardCharsets.UTF_8));
    }

    private String reference(int index) { return members.get(index).memberReference(); }

    private MemberStatus statusOf(int index) {
        return policyApi.listMembers(scheme, null, null, PageRequest.of(0, 10)).getContent()
            .stream().filter(m -> m.policyMemberId().equals(members.get(index).policyMemberId()))
            .findFirst().orElseThrow().status();
    }

    // ---- submitting takes NOBODY off cover ----------------------------------

    @Test
    void submittingJudgesEveryRowAndTakesNobodyOffCover() {
        // THE assertion this class exists for.
        ExitSubmissionView submitted = exitApi.submit(scheme,
            csv(reference(0) + ",2026-11-03,SETTLED_EARLY,0.00\n"
              + reference(1) + ",2026-11-04,REFINANCED,500000.00\n"),
            "november-exits.csv", "staff.one");

        assertThat(submitted.status()).isEqualTo(SubmissionStatus.PENDING);
        assertThat(submitted.rowCount()).isEqualTo(2);
        assertThat(submitted.rejectedCount()).isZero();
        assertThat(submitted.exitedCount()).isZero();

        assertThat(statusOf(0)).isEqualTo(MemberStatus.ACTIVE);
        assertThat(statusOf(1)).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void acceptanceTakesTheGoodRowsOffCoverAndOnlyThose() {
        ExitSubmissionView submitted = exitApi.submit(scheme,
            csv(reference(0) + ",2026-11-03,SETTLED_EARLY,0.00\n"
              + "CL-NOT-A-REAL-REF,2026-11-04,REFINANCED,500000.00\n"),
            "november-exits.csv", "staff.one");

        ExitSubmissionView accepted = exitApi.accept(submitted.submissionId(), "staff.two");

        assertThat(accepted.status()).isEqualTo(SubmissionStatus.ACCEPTED);
        assertThat(accepted.exitedCount()).isEqualTo(1);
        assertThat(accepted.rejectedCount()).isEqualTo(1);
        assertThat(statusOf(0)).isEqualTo(MemberStatus.EXITED);
        assertThat(statusOf(1)).isEqualTo(MemberStatus.ACTIVE);
        assertThat(statusOf(2)).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void oneBadRowDoesNotStopTheOtherLoansLeaving() {
        // Partial accept, exactly as on the enrolment side: a 400-row file bouncing over one
        // typo strands 399 refunds.
        ExitSubmissionView submitted = exitApi.submit(scheme,
            csv("CL-NONSENSE,2026-11-03,SETTLED_EARLY,0.00\n"
              + reference(0) + ",2026-11-03,SETTLED_EARLY,0.00\n"
              + reference(1) + ",2026-11-03,WRITTEN_OFF,900000.00\n"),
            "november-exits.csv", "staff.one");

        exitApi.accept(submitted.submissionId(), "staff.two");

        assertThat(statusOf(0)).isEqualTo(MemberStatus.EXITED);
        assertThat(statusOf(1)).isEqualTo(MemberStatus.EXITED);
    }

    // ---- the two-person control ---------------------------------------------

    @Test
    void theUploaderCannotAcceptTheirOwnExitsFile() {
        ExitSubmissionView submitted = exitApi.submit(scheme,
            csv(reference(0) + ",2026-11-03,SETTLED_EARLY,0.00\n"),
            "november-exits.csv", "staff.one");

        assertThatThrownBy(() -> exitApi.accept(submitted.submissionId(), "staff.one"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("second person");

        // And nobody moved.
        assertThat(statusOf(0)).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void onlyOneExitsFileMayBeInFlightPerScheme() {
        exitApi.submit(scheme, csv(reference(0) + ",2026-11-03,SETTLED_EARLY,0.00\n"),
            "november-exits.csv", "staff.one");

        assertThatThrownBy(() -> exitApi.submit(scheme,
            csv(reference(1) + ",2026-11-03,SETTLED_EARLY,0.00\n"),
            "november-again.csv", "staff.one"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("awaiting acceptance");
    }

    @Test
    void anAcceptedFileFreesTheSchemeForNextMonth() {
        ExitSubmissionView first = exitApi.submit(scheme,
            csv(reference(0) + ",2026-11-03,SETTLED_EARLY,0.00\n"),
            "november-exits.csv", "staff.one");
        exitApi.accept(first.submissionId(), "staff.two");

        assertThatCode(() -> exitApi.submit(scheme,
            csv(reference(1) + ",2026-12-03,SETTLED_EARLY,0.00\n"),
            "december-exits.csv", "staff.one")).doesNotThrowAnyException();
    }

    @Test
    void withdrawingAPendingFileTakesNobodyOffCoverAndFreesTheScheme() {
        ExitSubmissionView submitted = exitApi.submit(scheme,
            csv(reference(0) + ",2026-11-03,SETTLED_EARLY,0.00\n"),
            "november-exits.csv", "staff.one");

        exitApi.withdraw(submitted.submissionId(), "staff.two");

        assertThat(statusOf(0)).isEqualTo(MemberStatus.ACTIVE);
        assertThatCode(() -> exitApi.submit(scheme,
            csv(reference(0) + ",2026-11-03,SETTLED_EARLY,0.00\n"),
            "november-fixed.csv", "staff.one")).doesNotThrowAnyException();
    }

    // ---- what is refused, and why the lender is told ------------------------

    @Test
    void aReferenceBelongingToAnotherSchemeIsRejectedAndNotExited() {
        // Two lenders, one tenant. THE cross-scheme leak test: the reference names a real
        // member, and touching them would take a different lender's borrower off cover.
        String otherScheme = issueCreditLifeScheme(List.of(
            borrower("Salum Juma Rashid", LocalDate.of(1969, 1, 30))));
        PolicyMemberView theirs = policyApi.listMembers(otherScheme, null, null,
            PageRequest.of(0, 10)).getContent().get(0);

        ExitSubmissionView submitted = exitApi.submit(scheme,
            csv(theirs.memberReference() + ",2026-11-03,SETTLED_EARLY,0.00\n"),
            "november-exits.csv", "staff.one");
        exitApi.accept(submitted.submissionId(), "staff.two");

        List<ExitRowView> rows = exitApi.listRows(submitted.submissionId());
        assertThat(rows.get(0).reasonCode()).isEqualTo(ExitRejection.UNKNOWN_MEMBER_REFERENCE);
        assertThat(policyApi.listMembers(otherScheme, null, null, PageRequest.of(0, 10))
            .getContent().get(0).status()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void aLoanAlreadyOffCoverIsReportedRatherThanSilentlyIgnored() {
        // exitMember is idempotent, so a repeat would no-op quietly. Reporting it is how the
        // lender learns their file and our roll disagree -- which is the whole point of the
        // quarterly reconciliation the spec asks for.
        policyApi.exitMember(scheme, members.get(0).policyMemberId(), EXIT_DATE,
            ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.earlier");

        ExitSubmissionView submitted = exitApi.submit(scheme,
            csv(reference(0) + ",2026-11-03,SETTLED_EARLY,0.00\n"),
            "november-exits.csv", "staff.one");

        List<ExitRowView> rows = exitApi.listRows(submitted.submissionId());
        assertThat(rows.get(0).reasonCode()).isEqualTo(ExitRejection.ALREADY_EXITED);
        assertThat(rows.get(0).reason()).contains("already left cover on 2026-11-03");
    }

    /** The lender reports a death the only way an exits file lets them -- as a write-off. The
     * claim already open on that borrower decides how they leave, so the row is refused and says
     * why; the others on the same file still leave. Before V23 this exited the borrower with the
     * wrong reason and a premium refund a death never earns, and the claim's own discharge then
     * found them gone and did nothing. */
    @Test
    void aBorrowerWithADeathClaimInProgressIsNotExitedByTheFile() {
        UUID deathClaimId = UUID.randomUUID();
        policyApi.recordOpenDeathClaim(scheme, members.get(0).policyMemberId(), deathClaimId);

        ExitSubmissionView submitted = exitApi.submit(scheme,
            csv(reference(0) + ",2026-11-03,WRITTEN_OFF,1600000.00\n"
              + reference(1) + ",2026-11-03,SETTLED_EARLY,0.00\n"),
            "november-exits.csv", "staff.one");
        List<ExitRowView> rows = exitApi.listRows(submitted.submissionId());
        assertThat(rows.get(0).reasonCode()).isEqualTo(ExitRejection.DEATH_CLAIM_IN_PROGRESS);
        assertThat(rows.get(0).reason()).contains("death claim in progress");

        exitApi.accept(submitted.submissionId(), "staff.two");
        assertThat(statusOf(0)).isEqualTo(MemberStatus.ACTIVE);
        assertThat(statusOf(1)).isEqualTo(MemberStatus.EXITED);

        // And not by hand either: the staff exit path reaches the same verdict.
        assertThatThrownBy(() -> policyApi.exitMember(scheme, members.get(0).policyMemberId(), EXIT_DATE,
                ExitReason.WRITTEN_OFF, BigDecimal.ZERO, "staff.three"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining(deathClaimId.toString());

        // Once the claim is rejected the loan is an ordinary live loan again.
        policyApi.clearOpenDeathClaim(scheme, members.get(0).policyMemberId(), deathClaimId);
        policyApi.exitMember(scheme, members.get(0).policyMemberId(), EXIT_DATE,
            ExitReason.WRITTEN_OFF, BigDecimal.ZERO, "staff.three");
        assertThat(statusOf(0)).isEqualTo(MemberStatus.EXITED);
    }

    @Test
    void anExitDatedBeforeCoverStartedIsRejected() {
        ExitSubmissionView submitted = exitApi.submit(scheme,
            csv(reference(0) + "," + DISBURSED.minusDays(1) + ",SETTLED_EARLY,0.00\n"),
            "november-exits.csv", "staff.one");

        List<ExitRowView> rows = exitApi.listRows(submitted.submissionId());
        assertThat(rows.get(0).reasonCode()).isEqualTo(ExitRejection.EXIT_BEFORE_COVER_STARTED);
        assertThat(statusOf(0)).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void aLenderMayNotAssertTheInsurerPaidAClaim() {
        // Suppressing the refund and the clawback on a loan that was merely repaid is worth
        // real money to the lender, so this is refused at the parser and asserted end to end.
        ExitSubmissionView submitted = exitApi.submit(scheme,
            csv(reference(0) + ",2026-11-03,CLAIM_SETTLED,0.00\n"),
            "november-exits.csv", "staff.one");

        List<ExitRowView> rows = exitApi.listRows(submitted.submissionId());
        assertThat(rows.get(0).reasonCode()).isEqualTo(ExitRejection.UNKNOWN_EXIT_REASON);
        assertThat(statusOf(0)).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void anEmployerSchemeCannotBeSentAnExitsFile() {
        String employerScheme = issueEmployerScheme();

        assertThatThrownBy(() -> exitApi.submit(employerScheme,
            csv("CL-A-000001,2026-11-03,SETTLED_EARLY,0.00\n"), "exits.csv", "staff.one"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("AMORTISING_LOAN");
    }

    @Test
    void aFileMissingARequiredColumnIsRefusedWholeAndBlocksNothing() {
        assertThatThrownBy(() -> exitApi.submit(scheme,
            new ByteArrayInputStream("member_reference,exit_date\nCL-A,2026-11-03\n"
                .getBytes(StandardCharsets.UTF_8)), "broken.csv", "staff.one"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("exit_reason");

        // Nothing was stored, so the corrected file the lender is about to send is not blocked
        // by a pending row nobody can accept.
        assertThatCode(() -> exitApi.submit(scheme,
            csv(reference(0) + ",2026-11-03,SETTLED_EARLY,0.00\n"),
            "fixed.csv", "staff.one")).doesNotThrowAnyException();
    }

    // ---- the report ---------------------------------------------------------

    @Test
    void theReportNamesEveryRowAndSaysWhatIsStillOnCover() {
        ExitSubmissionView submitted = exitApi.submit(scheme,
            csv(reference(0) + ",2026-11-03,SETTLED_EARLY,0.00\n"
              + "CL-NONSENSE,2026-11-03,SETTLED_EARLY,0.00\n"),
            "november-exits.csv", "staff.one");

        String report = exitApi.renderReport(submitted.submissionId());

        assertThat(report.lines().findFirst().orElseThrow())
            .isEqualTo("row_number,member_reference,exit_date,exit_reason,"
                + "outstanding_balance_at_exit,outcome,reason_code,reason");
        assertThat(report).contains("UNKNOWN_MEMBER_REFERENCE");
        assertThat(report).contains("THIS LOAN IS STILL ON COVER.");
        // The accepted row must NOT carry that sentence -- it is coming off cover.
        assertThat(report.lines().filter(l -> l.startsWith("2,")).findFirst().orElseThrow())
            .doesNotContain("STILL ON COVER");
    }

    // ---- fixtures -----------------------------------------------------------

    private record GroupProduct(UUID productId, UUID productVersionId) {}

    private PolicyApi.MemberInput borrower(String name, LocalDate dateOfBirth) {
        return PolicyApi.MemberInput.borrower(name, dateOfBirth, null,
            new LoanTerms(new BigDecimal("2400000.00"), BigDecimal.ZERO, 18,
                RepaymentFrequency.MONTHLY, DISBURSED, DISBURSED.plusMonths(1)));
    }

    private String issueCreditLifeScheme(List<PolicyApi.MemberInput> borrowers) {
        GroupProduct product = publish(ProductCategory.CREDIT_LIFE, "CL-EXITS-" + SEQ.incrementAndGet());
        return policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            person("Lender Co"), product.productId(), product.productVersionId(), null,
            BenefitBasis.AMORTISING_LOAN, null, null, new BigDecimal("600000000.00"), "TZS",
            null, borrowers, new BigDecimal("52000.00"), "TZS", "SINGLE",
            LocalDate.of(2026, 6, 1), null, "credit life onboarding", IssuanceBasis.MIGRATION,
            InterestMethod.FLAT_RATE, RepaymentFrequency.MONTHLY, new BigDecimal("0.5000"), CreditLifePremiumBasis.PER_ANNUM_ON_PRINCIPAL),
            "staff-1").policyNumber();
    }

    private String issueEmployerScheme() {
        GroupProduct product = publish(ProductCategory.GROUP_LIFE, "GRP-EXITS-" + SEQ.incrementAndGet());
        return policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            person("Employer Co"), product.productId(), product.productVersionId(), null,
            BenefitBasis.FLAT, new BigDecimal("1000000.00"), null, null, "TZS", null,
            List.of(new PolicyApi.MemberInput(person("Employee A"), null, null, null)),
            new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now(), null,
            "group onboarding", IssuanceBasis.MIGRATION), "staff-1").policyNumber();
    }

    private GroupProduct publish(ProductCategory category, String code) {
        ProductSummaryView product = productApi.createProduct(code, category + " " + code,
            category, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new GroupProduct(product.productId(), snapshot.productVersionId());
    }

    private UUID person(String name) {
        return partyApi.registerIndividual(name, LocalDate.of(1985, 6, 15),
            "+2557" + String.format("%08d", SEQ.incrementAndGet()), null, "test-agent").partyId();
    }

    // ---- the same file, over HTTP -----------------------------------------
    //
    // Plan 3 built everything above and shipped it with NO CONTROLLER: ExitApi and
    // ExitApiImpl were the only two files in the backend that named it, so a lender's monthly
    // file could not be run by any HTTP client. These cases live in this class rather than a
    // new one because the harness they need -- a credit-life scheme with three members on it
    // and their minted references -- is exactly what @BeforeEach above already builds.

    /** Captured in @BeforeEach rather than read from TenantContext inside the JWT lambda: that
     * lambda is evaluated while MockMvc builds the request, by which point the ambient thread
     * context is gone and the claim blew up with "No tenant context set". A request identity must
     * not depend on ambient state anyway. */
    private UUID tenantId;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private org.springframework.test.web.servlet.request.RequestPostProcessor staff(String subject) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(b -> b.subject(subject).claim("tenant_id", tenantId.toString()));
    }

    private MockMultipartFile exitsFile(String name, String body) {
        return new MockMultipartFile("file", name, "text/csv",
            (HEADER + body).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theExitsFileIsReachableOverHttpAndAcceptedByASecondPerson() throws Exception {
        // Every call below 404'd before ExitController existed.
        var upload = mockMvc.perform(multipart("/credit-life-schemes/" + scheme + "/exits")
                .file(exitsFile("november-exits.csv", reference(0) + ",2026-11-03,SETTLED_EARLY,450000.00\n"))
                .with(staff("staff.one")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn();
        String submissionId = objectMapper.readTree(upload.getResponse().getContentAsString())
            .path("submissionId").asText();

        mockMvc.perform(get("/credit-life-schemes/" + scheme + "/exits").with(staff("staff.one")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].submissionId").value(submissionId))
            .andExpect(jsonPath("$[0].fileName").value("november-exits.csv"));

        mockMvc.perform(get("/credit-life-schemes/" + scheme + "/exits/" + submissionId + "/rows")
                .with(staff("staff.one")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].reasonCode").doesNotExist())
            /*
             * THE BALANCE IS MONEY ON THE WIRE, never a bare number.
             *
             * This endpoint returned the domain view straight out, so the BigDecimal serialised as
             * 450000 -- no currency, no decimal string -- and the console, reading the Money object
             * this contract promises, rendered "undefined undefined" in the column.
             *
             * The assertion above passed throughout. It checked that a reason code was ABSENT and
             * never looked at a field that was present, and the only row it ever sent carried 0.00
             * -- so the test exercised the endpoint without exercising what was broken.
             */
            .andExpect(jsonPath("$[0].outstandingBalanceAtExit.amount").value("450000.00"))
            .andExpect(jsonPath("$[0].outstandingBalanceAtExit.currencyCode").value("TZS"));

        // A DIFFERENT subject accepts. Using the same one would prove the endpoint exists while
        // proving nothing about the two-person rule the actor claim is there to enforce.
        mockMvc.perform(post("/credit-life-schemes/" + scheme + "/exits/" + submissionId + "/acceptance")
                .with(staff("staff.two")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.exitedCount").value(1));

        // The request filter clears TenantContext on its way out, so a policyApi read made
        // AFTER a MockMvc call has to re-establish it.
        TenantContext.set(tenantId);
        assertThat(statusOf(0)).isEqualTo(MemberStatus.EXITED);
    }

    @Test
    void theActorComesFromTheTokenSoTheSubmitterCannotAcceptTheirOwnFile() throws Exception {
        // The refusal lives in ExitApiImpl; this asserts the CONTROLLER feeds it a real identity.
        // A controller that passed a literal, or read the wrong claim, would silently turn two
        // people into one and the rule would be decoration.
        var upload = mockMvc.perform(multipart("/credit-life-schemes/" + scheme + "/exits")
                .file(exitsFile("november-exits.csv", reference(0) + ",2026-11-03,SETTLED_EARLY,0.00\n"))
                .with(staff("staff.one")))
            .andExpect(status().isCreated())
            .andReturn();
        String submissionId = objectMapper.readTree(upload.getResponse().getContentAsString())
            .path("submissionId").asText();

        mockMvc.perform(post("/credit-life-schemes/" + scheme + "/exits/" + submissionId + "/acceptance")
                .with(staff("staff.one")))
            .andExpect(status().is4xxClientError());

        // The request filter clears TenantContext on its way out, so a policyApi read made
        // AFTER a MockMvc call has to re-establish it.
        TenantContext.set(tenantId);
        assertThat(statusOf(0)).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void anExitsUploadRefusesACustomerToken() throws Exception {
        mockMvc.perform(multipart("/credit-life-schemes/" + scheme + "/exits")
                .file(exitsFile("november-exits.csv", reference(0) + ",2026-11-03,SETTLED_EARLY,0.00\n"))
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void theReportIsServedAsADownloadNamedAfterItsSubmission() throws Exception {
        // The report is the deliverable: it is the only place a lender learns which exits were
        // refused and why.
        var upload = mockMvc.perform(multipart("/credit-life-schemes/" + scheme + "/exits")
                .file(exitsFile("november-exits.csv", reference(0) + ",2026-11-03,SETTLED_EARLY,0.00\n"))
                .with(staff("staff.one")))
            .andExpect(status().isCreated())
            .andReturn();
        String submissionId = objectMapper.readTree(upload.getResponse().getContentAsString())
            .path("submissionId").asText();

        mockMvc.perform(get("/credit-life-schemes/" + scheme + "/exits/" + submissionId + "/report")
                .with(staff("staff.one")))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", containsString("text/csv")))
            .andExpect(header().string("Content-Disposition", containsString(submissionId)));
    }
}
