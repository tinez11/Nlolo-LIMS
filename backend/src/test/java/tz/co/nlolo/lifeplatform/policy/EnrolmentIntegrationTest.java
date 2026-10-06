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
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentSubmissionView;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

/**
 * Bulk enrolment end to end: a lender's file is judged, and a SECOND person turns it
 * into cover.
 *
 * <p>The assertion this class exists for is the one no unit test can make -- that
 * submitting enrols NOBODY. Everything else follows from it.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class EnrolmentIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    /*
     * Its OWN MinIO, following ClaimEvidenceIntegrationTest verbatim. Without it this
     * class silently uses whatever object store the dev compose stack happens to be
     * running, which passes on a developer machine and fails in CI -- the same shape as
     * ActuatorExposureTest depending on Mailpit.
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
            "db-migrations/product/V27__ifrs17_classification.sql",
            "db-migrations/product/V28__survival_investment_component.sql",
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
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/underwriting/V8__rating_multiplier.sql",
            "db-migrations/underwriting/V9__group_proposal.sql",
            "db-migrations/underwriting/V10__issuance_failure.sql",
            "db-migrations/underwriting/V11__member_evidence_case.sql",
            "db-migrations/underwriting/V18__sale_channel_and_branch.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
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
            "db-migrations/document/V1__create_document_schema.sql",
            "db-migrations/document/V2__add_content_type_and_file_name.sql",
            "db-migrations/document/V4__enrolment_schedule_document_type.sql",
            "db-migrations/document/V7__journal_support_document_type.sql",
            "db-migrations/document/V8__reinsurance_statement_document_type.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");

        // The container never runs compose's minio-init job, so the buckets are made here.
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
    @Autowired private EnrolmentApi enrolmentApi;

    private static final AtomicInteger SEQ = new AtomicInteger(5000);

    private String creditLifeScheme;
    private String employerScheme;

    private static final String HEADER =
        "loan_account_number,borrower_full_name,borrower_date_of_birth,borrower_sex,"
        + "borrower_national_id,borrower_phone,loan_principal_amount,"
        + "loan_term_months,disbursement_date\n";

    private static final String ONE_GOOD_ROW =
        "LN-2026-00417,Amina Hassan Mwinyi,1988-03-14,F,,,8500000.00,48,2026-06-30\n";


    /** A DIFFERENT borrower from ONE_GOOD_ROW. The second file in the listing test must not
     * re-send the first one: once accepted a repeat is ALREADY_ENROLLED (see
     * aBorrowerAlreadyOnTheSchemeIsRejectedRatherThanDoubled), which would leave the test
     * asserting a listing built from a file that enrolled nobody. */
    private static final String ANOTHER_GOOD_ROW =
        "LN-2026-00512,Juma Rajabu Kimaro,1990-07-02,M,,,3200000.00,24,2026-07-15\n";
    private ByteArrayInputStream csv(String body) {
        return new ByteArrayInputStream((HEADER + body).getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        creditLifeScheme = issueScheme(ProductCategory.CREDIT_LIFE, BenefitBasis.AMORTISING_LOAN);
        employerScheme = issueScheme(ProductCategory.GROUP_LIFE, BenefitBasis.FLAT);
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private String issueScheme(ProductCategory category, BenefitBasis basis) {
        String code = "CL-" + SEQ.incrementAndGet();
        ProductSummaryView product = productApi.createProduct(code, "Credit life " + code,
            category, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());

        boolean loan = basis == BenefitBasis.AMORTISING_LOAN;
        PolicyApi.MemberInput opening = loan
            ? PolicyApi.MemberInput.borrower("Opening Borrower", LocalDate.of(1985, 1, 1),
                "LN-OPENING-" + SEQ.incrementAndGet(),
                new LoanTerms(new BigDecimal("1000000.00"), BigDecimal.ZERO, 12,
                    RepaymentFrequency.MONTHLY, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1)))
            : new PolicyApi.MemberInput(person("Opening Employee"), null, null, null);

        return policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            person("Lender " + code), product.productId(), snapshot.productVersionId(), null,
            basis, loan ? null : new BigDecimal("1000000.00"), null,
            new BigDecimal("25000000.00"), "TZS", null, List.of(opening),
            new BigDecimal("52000.00"), "TZS",
            // A credit-life scheme is paid once per accepted file, so it is never on a cycle.
            loan ? "SINGLE" : "ANNUALLY",
            LocalDate.of(2026, 6, 1), null,
            "onboarding", IssuanceBasis.MIGRATION,
            loan ? InterestMethod.FLAT_RATE : null,
            loan ? RepaymentFrequency.MONTHLY : null,
            loan ? new BigDecimal("0.5000") : null,
            loan ? CreditLifePremiumBasis.PER_ANNUM_ON_PRINCIPAL : null), "staff-setup").policyNumber();
    }

    private UUID person(String name) {
        return partyApi.registerIndividual(name, LocalDate.of(1985, 6, 15),
            "+2557" + String.format("%08d", SEQ.incrementAndGet()), null, "test-agent").partyId();
    }

    // ---- the rows, as a BROWSER reads them ---------------------------------
    //
    // Everything above this line calls EnrolmentApi directly, which is the right level for
    // judging rules and the wrong level for finding out what the console receives: the domain
    // view never crosses a serialiser here, so a field that is present in Java and absent or
    // mistyped on the wire is invisible to every one of those cases.
    //
    // Both ways it can go wrong were live at once on the sibling exits endpoint, which returned
    // the domain view straight out: an amount reached the browser as a bare JSON number and
    // rendered "undefined undefined", and the reference was carried by the view but never
    // declared in the OpenAPI schema, so the generated client had no field to read.

    /** Captured in @BeforeEach rather than read from TenantContext inside the JWT lambda, which is
     * evaluated while MockMvc builds the request -- by then the ambient thread context is gone. */
    private UUID tenantId;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private org.springframework.test.web.servlet.request.RequestPostProcessor staff(String subject) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(b -> b.subject(subject).claim("tenant_id", tenantId.toString()));
    }

    @Test
    void anAcceptedRowReachesTheBrowserWithItsReferenceAndItsPremiumAsMoney() throws Exception {
        var upload = mockMvc.perform(multipart("/credit-life-schemes/" + creditLifeScheme + "/enrolments")
                .file(new MockMultipartFile("file", "june.csv", "text/csv",
                    (HEADER + ONE_GOOD_ROW).getBytes(StandardCharsets.UTF_8)))
                .with(staff("staff.one")))
            .andExpect(status().isCreated())
            .andReturn();
        String submissionId = objectMapper.readTree(upload.getResponse().getContentAsString())
            .path("submissionId").asText();

        // A DIFFERENT subject: the reference and the premium are both written at acceptance, so
        // before this call the row legitimately has neither and asserting on it proves nothing.
        mockMvc.perform(post("/credit-life-schemes/" + creditLifeScheme + "/enrolments/"
                    + submissionId + "/acceptance")
                .with(staff("staff.two")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACCEPTED"));

        mockMvc.perform(get("/credit-life-schemes/" + creditLifeScheme + "/enrolments/"
                    + submissionId + "/rows")
                .with(staff("staff.one")))
            .andExpect(status().isOk())
            // THE deliverable. This is the only place a lender learns what the insurer minted.
            .andExpect(jsonPath("$[0].memberReference").value(startsWith("CL-")))
            // Money, not a number: a decimal STRING with a currency beside it. The pattern is the
            // assertion -- a bare BigDecimal serialises as 42500 and would fail it.
            .andExpect(jsonPath("$[0].premiumAmount.amount").value(matchesPattern("\\d+\\.\\d{2}")))
            .andExpect(jsonPath("$[0].premiumAmount.currencyCode").value("TZS"));
    }

    // ---- submitting judges, and enrols nobody --------------------------------

    @Test
    void aFileIsJudgedRowByRowAndNobodyIsEnrolledYet() {
        var submission = enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW
            + "LN-2026-00418,,1975-11-02,M,,,24000000.00,60,2026-07-05\n"),
            "june.csv", "staff.one");

        assertThat(submission.status()).isEqualTo(SubmissionStatus.PENDING);
        assertThat(submission.rowCount()).isEqualTo(2);
        assertThat(submission.rejectedCount()).isEqualTo(1);
        assertThat(submission.enrolledCount()).isZero();

        // The scheme still has ONLY its opening member. Judging is not enrolling, and
        // this is the assertion the whole design turns on.
        assertThat(policyApi.getGroupScheme(creditLifeScheme).activeMemberCount()).isEqualTo(1);
    }

    @Test
    void aRejectedRowSaysTheBorrowerIsNotCovered() {
        var submission = enrolmentApi.submit(creditLifeScheme,
            csv("LN-BAD,,1975-11-02,M,,,24000000.00,60,2026-07-05\n"), "bad.csv", "staff.one");

        var row = enrolmentApi.listRows(submission.submissionId()).get(0);
        assertThat(row.outcome()).isEqualTo(RowOutcome.REJECTED);
        assertThat(row.reasonCode()).isEqualTo(EnrolmentRejection.MISSING_REQUIRED_FIELD);
        assertThat(row.reason())
            .as("the one sentence in this system that has to be unmistakable")
            .contains("THIS BORROWER IS NOT COVERED.");
    }

    @Test
    void aLoanDisbursedBeforeTheSchemeCommencedIsRejected() {
        var submission = enrolmentApi.submit(creditLifeScheme,
            csv("LN-EARLY,Early Borrower,1990-01-01,F,,,1000000.00,12,2026-05-01\n"),
            "early.csv", "staff.one");

        var row = enrolmentApi.listRows(submission.submissionId()).get(0);
        assertThat(row.reasonCode()).isEqualTo(EnrolmentRejection.LOAN_BEFORE_SCHEME_COMMENCED);
    }

    @Test
    void aBorrowerAlreadyOnTheSchemeIsRejectedRatherThanDoubled() {
        var first = enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW), "june.csv", "staff.one");
        enrolmentApi.accept(first.submissionId(), "staff.two");

        var second = enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW), "again.csv", "staff.one");

        assertThat(enrolmentApi.listRows(second.submissionId()).get(0).reasonCode())
            .isEqualTo(EnrolmentRejection.ALREADY_ENROLLED);
    }

    @Test
    void aSecondFileCannotBeSubmittedWhileOneIsPending() {
        enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW), "first.csv", "staff.one");

        assertThatThrownBy(() -> enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW),
                "second.csv", "staff.one"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("already has a submission awaiting acceptance");
    }

    @Test
    void aFileMissingARequiredColumnIsRefusedWhole() {
        assertThatThrownBy(() -> enrolmentApi.submit(creditLifeScheme,
                new ByteArrayInputStream(
                    "borrower_full_name,loan_principal_amount\nAmina,8500000.00\n"
                        .getBytes(StandardCharsets.UTF_8)),
                "wrong.csv", "staff.one"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("borrower_date_of_birth");
    }

    @Test
    void aFileWithNoLoanAccountNumberIsPerfectlyFine() {
        // Reversed by the client on 2026-09-22: neither lender holds a per-loan
        // identifier, so requiring one would have rejected every real file. The insurer
        // issues the reference instead, and the report carries it back.
        String noAccountColumn =
            "borrower_full_name,borrower_date_of_birth,borrower_sex,borrower_national_id,"
            + "borrower_phone,loan_principal_amount,loan_term_months,disbursement_date\n"
            + "Amina Hassan Mwinyi,1988-03-14,F,,,8500000.00,48,2026-06-30\n";

        var submission = enrolmentApi.submit(creditLifeScheme,
            new ByteArrayInputStream(noAccountColumn.getBytes(StandardCharsets.UTF_8)),
            "no-account-numbers.csv", "staff.one");

        assertThat(submission.rejectedCount()).isZero();
        enrolmentApi.accept(submission.submissionId(), "staff.two");

        assertThat(enrolmentApi.listRows(submission.submissionId()).get(0).memberReference())
            .as("the lender learns the reference from the report, and quotes it afterwards")
            .isNotNull().startsWith("CL-");
    }

    @Test
    void aWholeFileRefusalLeavesNoSubmissionBlockingTheCorrectedOne() {
        assertThatThrownBy(() -> enrolmentApi.submit(creditLifeScheme,
            new ByteArrayInputStream("nonsense\n".getBytes(StandardCharsets.UTF_8)),
            "wrong.csv", "staff.one")).isInstanceOf(InvalidPolicyStateException.class);

        // A pending row here would block the corrected file the lender is about to send.
        assertThatCode(() -> enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW),
            "corrected.csv", "staff.one")).doesNotThrowAnyException();
    }

    @Test
    void anEmployerSchemeRefusesALendersFileEntirely() {
        assertThatThrownBy(() -> enrolmentApi.submit(employerScheme, csv(ONE_GOOD_ROW),
                "wrong.csv", "staff.one"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("AMORTISING_LOAN");
    }

    // ---- acceptance ----------------------------------------------------------

    @Test
    void acceptanceEnrolsTheGoodRowsAndOnlyThose() {
        var submission = enrolmentApi.submit(creditLifeScheme, csv(
            "LN-A,Amina Hassan Mwinyi,1988-03-14,F,,,8500000.00,48,2026-06-30\n"
            + "LN-B,,1975-11-02,M,,,24000000.00,60,2026-07-05\n"
            + "LN-C,Grace Shirima,1992-06-21,F,,,3200000.00,24,2026-07-06\n"),
            "june.csv", "staff.one");

        var accepted = enrolmentApi.accept(submission.submissionId(), "staff.two");

        assertThat(accepted.status()).isEqualTo(SubmissionStatus.ACCEPTED);
        assertThat(accepted.enrolledCount()).isEqualTo(2);
        // 1 opening member + 2 accepted rows. The rejected row enrolled nobody.
        assertThat(policyApi.getGroupScheme(creditLifeScheme).activeMemberCount()).isEqualTo(3);
        assertThat(policyApi.getGroupScheme(creditLifeScheme).totalCoveredAmount())
            .isEqualByComparingTo("12700000.00"); // 1,000,000 + 8,500,000 + 3,200,000
    }

    @Test
    void theUploaderCannotAcceptTheirOwnFile() {
        var submission = enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW), "june.csv", "staff.one");

        assertThatThrownBy(() -> enrolmentApi.accept(submission.submissionId(), "staff.one"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("cannot accept a submission they uploaded");

        // And nobody was enrolled on the way to finding that out.
        assertThat(policyApi.getGroupScheme(creditLifeScheme).activeMemberCount()).isEqualTo(1);
    }

    @Test
    void anAcceptedRowRemembersTheMemberItBecame() {
        var submission = enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW), "june.csv", "staff.one");
        enrolmentApi.accept(submission.submissionId(), "staff.two");

        // Without this a report cannot answer "which member is this row?" six months on.
        assertThat(enrolmentApi.listRows(submission.submissionId()).get(0).policyMemberId())
            .isNotNull();
    }

    @Test
    void acceptingTwiceIsRefused() {
        var submission = enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW), "june.csv", "staff.one");
        enrolmentApi.accept(submission.submissionId(), "staff.two");

        assertThatThrownBy(() -> enrolmentApi.accept(submission.submissionId(), "staff.three"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("already accepted");
    }

    @Test
    void anAcceptedFileFreesTheSchemeForNextMonth() {
        var june = enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW), "june.csv", "staff.one");
        enrolmentApi.accept(june.submissionId(), "staff.two");

        assertThatCode(() -> enrolmentApi.submit(creditLifeScheme,
            csv("LN-JULY,Joseph Mkenda,1975-11-02,M,,,2400000.00,24,2026-07-31\n"),
            "july.csv", "staff.one")).doesNotThrowAnyException();
    }

    @Test
    void withdrawingAPendingFileEnrolsNobodyAndFreesTheScheme() {
        var submission = enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW), "june.csv", "staff.one");
        enrolmentApi.withdraw(submission.submissionId(), "staff.one");

        assertThat(policyApi.getGroupScheme(creditLifeScheme).activeMemberCount()).isEqualTo(1);
        assertThatCode(() -> enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW),
            "corrected.csv", "staff.one")).doesNotThrowAnyException();
    }

    @Test
    void anAboveFclRowIsEnrolledCappedAndCarriesItsReferral() {
        var submission = enrolmentApi.submit(creditLifeScheme,
            csv("LN-BIG,Peter Massawe,1980-07-19,M,,,30000000.00,72,2026-07-13\n"),
            "big.csv", "staff.one");

        var judged = enrolmentApi.listRows(submission.submissionId()).get(0);
        assertThat(judged.outcome()).isEqualTo(RowOutcome.ENROLLED_CAPPED);
        assertThat(judged.reason()).contains("25000000.00").contains("NOT covered");

        enrolmentApi.accept(submission.submissionId(), "staff.two");

        var member = policyApi.listMembers(creditLifeScheme, null, "Peter",
            PageRequest.of(0, 10)).getContent().get(0);
        assertThat(member.coveredAmount()).isEqualByComparingTo("25000000.00");
        assertThat(member.underwritingCaseId())
            .as("a capped borrower is covered, and the excess must be referred")
            .isNotNull();
    }

    @Test
    void aLendersWorkbookEnrolsBorrowersJustAsACsvDoes() throws Exception {
        // BOTH real client schedules are .xlsx. The conversion happens at the edge, so
        // everything downstream sees one shape -- this asserts the seam actually joins.
        byte[] xlsx;
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             var out = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Sheet1");
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper()
                .createDataFormat().getFormat("dd/mm/yyyy"));

            String[] header = HEADER.strip().split(",");
            var headerRow = sheet.createRow(0);
            for (int i = 0; i < header.length; i++) headerRow.createCell(i).setCellValue(header[i]);

            var data = sheet.createRow(1);
            data.createCell(0).setCellValue("LN-XLSX-001");
            data.createCell(1).setCellValue("Amina Hassan Mwinyi");
            var dob = data.createCell(2);
            dob.setCellValue(java.sql.Date.valueOf(LocalDate.of(1988, 3, 14)));
            dob.setCellStyle(dateStyle);
            data.createCell(3).setCellValue("F");
            data.createCell(6).setCellValue(8500000.00);
            data.createCell(7).setCellValue(48);
            var disbursed = data.createCell(8);
            disbursed.setCellValue(java.sql.Date.valueOf(LocalDate.of(2026, 6, 30)));
            disbursed.setCellStyle(dateStyle);

            workbook.write(out);
            xlsx = out.toByteArray();
        }

        var submission = enrolmentApi.submit(creditLifeScheme, new ByteArrayInputStream(xlsx),
            "june.xlsx", "staff.one");

        assertThat(submission.rejectedCount()).isZero();
        assertThat(enrolmentApi.listRows(submission.submissionId()).get(0).outcome())
            .isEqualTo(RowOutcome.ENROLLED);

        enrolmentApi.accept(submission.submissionId(), "staff.two");
        assertThat(policyApi.getGroupScheme(creditLifeScheme).activeMemberCount()).isEqualTo(2);
    }

    @Test
    void theReportCarriesEveryRowInTheLendersOwnLineOrder() {
        var submission = enrolmentApi.submit(creditLifeScheme, csv(
            "LN-A,Amina Hassan Mwinyi,1988-03-14,F,,,8500000.00,48,2026-06-30\n"
            + "LN-B,,1975-11-02,M,,,24000000.00,60,2026-07-05\n"),
            "june.csv", "staff.one");

        String report = enrolmentApi.renderReport(submission.submissionId());

        assertThat(report.lines().findFirst().orElseThrow())
            .isEqualTo("row_number,member_reference,loan_account_number,borrower_full_name,"
                + "outcome,premium_amount,reason_code,reason");
        assertThat(report).contains("Amina Hassan Mwinyi,ENROLLED");
        assertThat(report).contains("MISSING_REQUIRED_FIELD");
        assertThat(report).contains("THIS BORROWER IS NOT COVERED.");
    }

    // ---- the submission history a console page opens on -------------------

    @Test
    void submissionsAreListedNewestFirstAndScopedToTheirOwnScheme() {
        // Every other operation on EnrolmentApi takes a submissionId the caller is assumed to
        // already hold. That is true of the upload flow and false of anybody arriving at a
        // scheme cold, so without this a page that opens on a scheme has nothing to render.
        String otherScheme = issueScheme(ProductCategory.CREDIT_LIFE, BenefitBasis.AMORTISING_LOAN);

        UUID first = enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW),
            "august.csv", "staff.one").submissionId();
        // NOT padding: a scheme allows only ONE submission in flight (see
        // aSecondFileCannotBeSubmittedWhileOneIsPending), so the second submit below is refused
        // until this one is resolved. A version of this test that simply submitted twice fails
        // on the second call and reads as a bug in listSubmissions.
        enrolmentApi.accept(first, "staff.two");
        UUID second = enrolmentApi.submit(creditLifeScheme, csv(ANOTHER_GOOD_ROW),
            "september.csv", "staff.one").submissionId();
        enrolmentApi.submit(otherScheme, csv(ONE_GOOD_ROW), "other-bank.csv", "staff.one");

        List<EnrolmentSubmissionView> listed = enrolmentApi.listSubmissions(creditLifeScheme);

        assertThat(listed).extracting(EnrolmentSubmissionView::submissionId)
            .as("newest first -- a lender's latest file is what staff came to look at")
            .containsExactly(second, first);
        assertThat(listed).extracting(EnrolmentSubmissionView::fileName)
            .as("another lender's file is not this scheme's business")
            .doesNotContain("other-bank.csv");
    }

    @Test
    void aSchemeWithNoSubmissionsListsEmptyRatherThanThrowing() {
        // The normal state of a scheme on its first day. Throwing here would make a page render
        // an error for a situation that is not one.
        assertThat(enrolmentApi.listSubmissions(
            issueScheme(ProductCategory.CREDIT_LIFE, BenefitBasis.AMORTISING_LOAN)))
            .isEmpty();
    }

    /**
     * A scheme whose free cover limit and opening loan are chosen by the caller.
     *
     * <p>{@code issueScheme} fixes both, which is right for the enrolment tests above and useless
     * for the amendment tests below: they need a member the limit actually CAPS, and that only
     * happens when the limit is smaller than the loan.
     */
    private String issueSchemeWithLimit(BigDecimal fclAmount, BigDecimal principal) {
        String code = "CL-" + SEQ.incrementAndGet();
        ProductSummaryView product = productApi.createProduct(code, "Credit life " + code,
            ProductCategory.CREDIT_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());

        PolicyApi.MemberInput opening = PolicyApi.MemberInput.borrower(
            "Opening Borrower", LocalDate.of(1985, 1, 1), "LN-FCL-" + SEQ.incrementAndGet(),
            new LoanTerms(principal, BigDecimal.ZERO, 12, RepaymentFrequency.MONTHLY,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1)));

        return policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            person("Lender " + code), product.productId(), snapshot.productVersionId(), null,
            BenefitBasis.AMORTISING_LOAN, null, null, fclAmount, "TZS", null, List.of(opening),
            new BigDecimal("52000.00"), "TZS", "SINGLE",
            LocalDate.of(2026, 6, 1), null, "onboarding", IssuanceBasis.MIGRATION,
            InterestMethod.FLAT_RATE, RepaymentFrequency.MONTHLY,
            new BigDecimal("0.5000"), CreditLifePremiumBasis.PER_ANNUM_ON_PRINCIPAL),
            "staff-setup").policyNumber();
    }

    /** As above, but the opening borrower joins TODAY -- so their benefit row is already dated today. */
    private String issueSchemeWithLimitJoiningToday(BigDecimal fclAmount, BigDecimal principal) {
        String code = "CL-" + SEQ.incrementAndGet();
        ProductSummaryView product = productApi.createProduct(code, "Credit life " + code,
            ProductCategory.CREDIT_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        LocalDate today = LocalDate.now();
        PolicyApi.MemberInput opening = PolicyApi.MemberInput.borrower(
            "Opening Borrower", LocalDate.of(1985, 1, 1), "LN-TODAY-" + SEQ.incrementAndGet(),
            new LoanTerms(principal, BigDecimal.ZERO, 12, RepaymentFrequency.MONTHLY,
                today, today.plusMonths(1)));
        return policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            person("Lender " + code), product.productId(), snapshot.productVersionId(), null,
            BenefitBasis.AMORTISING_LOAN, null, null, fclAmount, "TZS", null, List.of(opening),
            new BigDecimal("52000.00"), "TZS", "SINGLE", today, null, "onboarding",
            IssuanceBasis.MIGRATION, InterestMethod.FLAT_RATE, RepaymentFrequency.MONTHLY,
            new BigDecimal("0.5000"), CreditLifePremiumBasis.PER_ANNUM_ON_PRINCIPAL),
            "staff-setup").policyNumber();
    }
    /** A credit-life scheme issued with NO borrowers -- the shape a lender relationship starts in. */
    private String issueEmptyCreditLifeScheme() {
        return issueEmptyScheme(ProductCategory.CREDIT_LIFE, BenefitBasis.AMORTISING_LOAN);
    }

    /** The same, on an employer basis, which the service must still refuse. */
    private String issueEmptyEmployerScheme() {
        return issueEmptyScheme(ProductCategory.GROUP_LIFE, BenefitBasis.FLAT);
    }

    private String issueEmptyScheme(ProductCategory category, BenefitBasis basis) {
        String code = "CL-" + SEQ.incrementAndGet();
        ProductSummaryView product = productApi.createProduct(code, "Credit life " + code,
            category, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        boolean loan = basis == BenefitBasis.AMORTISING_LOAN;

        return policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            person("Lender " + code), product.productId(), snapshot.productVersionId(), null,
            basis, loan ? null : new BigDecimal("1000000.00"), null,
            new BigDecimal("25000000.00"), "TZS", null,
            // The empty schedule this whole nested class is about.
            List.of(),
            // A premium is still required: chk_premium_amount_positive forbids zero on the master
            // policy. On a credit-life scheme it is never billed -- billing returns early on
            // SINGLE and the real premium arrives file by file -- so this is a recorded figure
            // rather than a charge, which is what the console's label now says.
            new BigDecimal("52000.00"), "TZS", loan ? "SINGLE" : "ANNUALLY",
            LocalDate.of(2026, 6, 1), null, "onboarding", IssuanceBasis.MIGRATION,
            loan ? InterestMethod.FLAT_RATE : null,
            loan ? RepaymentFrequency.MONTHLY : null,
            loan ? new BigDecimal("0.5000") : null,
            loan ? CreditLifePremiumBasis.PER_ANNUM_ON_PRINCIPAL : null), "staff-setup").policyNumber();
    }

    private PolicyMemberView onlyMemberOf(String policyNumber) {
        return policyApi.listMembers(policyNumber, null, null, PageRequest.of(0, 10))
            .getContent().get(0);
    }

    /**
     * Moving a live scheme's free cover limit.
     *
     * <p><b>From a real scheme.</b> GRP-60107C78 was set up with a limit of 1,000,000 against
     * loans of 5,000,000, so every borrower was insured for a fifth of their debt and referred for
     * medical evidence -- three underwriting cases nobody wanted, on a product whose agreed limit
     * is 600,000,000 precisely so the cap never fires. There was no amendment path at all, so the
     * only remedy was a second scheme and a lender told to send their file again.
     *
     * <p>The asymmetry below is the design. Raising a limit gives people cover they were always
     * meant to have, so it restates freely. Lowering one below live cover would leave borrowers
     * part-uninsured from a date nobody told them about, so it is refused WHOLE rather than
     * applied to the members it happens not to touch.
     */
    /**
     * Opening a scheme with no borrowers on it.
     *
     * <p>An employer scheme cannot: its schedule IS the contract. A credit-life scheme is an
     * agreement with a lender that exists before any borrower, and the book arrives by file --
     * so requiring one at set-up made somebody type a life they then met again on the member
     * roll without recognising them.
     */
    @Nested
    class OpeningASchemeWithNobodyOnIt {

        @Test
        void aCreditLifeSchemeMayOpenEmptyAndTheFirstFileFillsIt() {
            String policyNumber = issueEmptyCreditLifeScheme();

            GroupSchemeView scheme = policyApi.getGroupScheme(policyNumber);
            assertThat(scheme.activeMemberCount()).isZero();
            // Zero, not null and not a refusal: chk_policy_sum_assured_non_negative permits it
            // and totalCovered coalesces for a scheme with no members.
            assertThat(scheme.totalCoveredAmount()).isEqualByComparingTo("0");

            // And it takes a file like any other scheme, which is the whole point.
            EnrolmentSubmissionView submitted = enrolmentApi.submit(policyNumber,
                csv(ONE_GOOD_ROW), "january.csv", "staff-one");
            enrolmentApi.accept(submitted.submissionId(), "staff-two");

            assertThat(policyApi.getGroupScheme(policyNumber).activeMemberCount()).isEqualTo(1);
        }

        @Test
        void anEmployerSchemeStillCannotOpenEmpty() {
            // Unchanged, and asserted so the credit-life exception cannot quietly become global.
            assertThatThrownBy(() -> issueEmptyEmployerScheme())
                .isInstanceOf(InvalidPolicyStateException.class)
                .hasMessageContaining("at least one member");
        }
    }
    @Nested
    class MovingTheFreeCoverLimit {

        @Test
        void raisingItRestatesEveryCappedMemberAndTheSchemeTotal() {
            String policyNumber = issueSchemeWithLimit(new BigDecimal("1000000.00"),
                new BigDecimal("5000000.00"));

            PolicyMemberView before = onlyMemberOf(policyNumber);
            assertThat(before.coveredAmount()).isEqualByComparingTo("1000000.00");
            assertThat(before.underwritingStatus())
                .isEqualTo(MemberUnderwritingStatus.EVIDENCE_REQUIRED);

            GroupSchemeView amended = policyApi.amendFreeCoverLimit(policyNumber,
                new BigDecimal("600000000.00"),
                "Limit typed wrong at set-up; the agreed figure is 600,000,000", "staff.underwriter");

            // The borrower now carries their whole loan, and stops waiting on evidence for an
            // excess that no longer exists.
            PolicyMemberView after = onlyMemberOf(policyNumber);
            assertThat(after.coveredAmount()).isEqualByComparingTo("5000000.00");
            assertThat(after.underwritingStatus()).isEqualTo(MemberUnderwritingStatus.WITHIN_FCL);
            // And the scheme's own total agrees, which is the figure a TIRA return reads.
            assertThat(amended.totalCoveredAmount()).isEqualByComparingTo("5000000.00");
            assertThat(amended.membersRequiringEvidence()).isZero();
        }

        @Test
        void aLimitThatWouldUninsureSomebodyIsRefusedWhole() {
            String policyNumber = issueSchemeWithLimit(null, new BigDecimal("5000000.00"));

            assertThatThrownBy(() -> policyApi.amendFreeCoverLimit(policyNumber,
                    new BigDecimal("1000000.00"), "tightening", "staff.underwriter"))
                .isInstanceOf(InvalidPolicyStateException.class)
                .hasMessageContaining("would reduce the cover of 1 member");

            // WHOLE: the scheme keeps its limit and the member keeps their cover. A partial
            // restatement would leave the roll disagreeing with the terms printed above it.
            assertThat(onlyMemberOf(policyNumber).coveredAmount()).isEqualByComparingTo("5000000.00");
            assertThat(policyApi.getGroupScheme(policyNumber).fclAmount()).isNull();
        }

        @Test
        void loweringIsAllowedWhereItCapsNobodyAlreadyOnTheRoll() {
            // The honest half of the case: a limit binds members yet to come, so lowering it to a
            // figure above everybody's current cover changes nothing about them.
            String policyNumber = issueSchemeWithLimit(null, new BigDecimal("1000000.00"));

            GroupSchemeView amended = policyApi.amendFreeCoverLimit(policyNumber,
                new BigDecimal("2000000.00"), "Reducing exposure on new borrowers", "staff.underwriter");

            assertThat(amended.fclAmount()).isEqualByComparingTo("2000000.00");
            assertThat(onlyMemberOf(policyNumber).coveredAmount()).isEqualByComparingTo("1000000.00");
        }

        @Test
        void aMemberWhoseCoverStartsTODAY_isCorrectedRatherThanGivenASecondRow() {
            /*
             * The case that broke this the first time it ran against a real scheme. Correcting a
             * limit the same day the scheme was set up means every member already has a benefit
             * row effective today, and policy_member_benefit is unique on (member, day) -- so a
             * second insert is a 500. Effective dating here has day granularity: today holds one
             * row, carrying the final state of the day.
             */
            String policyNumber = issueSchemeWithLimitJoiningToday(new BigDecimal("1000000.00"),
                new BigDecimal("5000000.00"));
            assertThat(onlyMemberOf(policyNumber).coveredAmount()).isEqualByComparingTo("1000000.00");

            policyApi.amendFreeCoverLimit(policyNumber, new BigDecimal("600000000.00"),
                "corrected on the day it was set up", "staff.underwriter");

            assertThat(onlyMemberOf(policyNumber).coveredAmount()).isEqualByComparingTo("5000000.00");
        }
        @Test
        void amendingToTheLimitItAlreadyHasIsRefusedRatherThanRecorded() {
            // An endorsement saying nothing changed is noise in the one place a scheme's history
            // has to stay readable.
            String policyNumber = issueSchemeWithLimit(new BigDecimal("1000000.00"),
                new BigDecimal("500000.00"));

            assertThatThrownBy(() -> policyApi.amendFreeCoverLimit(policyNumber,
                    new BigDecimal("1000000.00"), "no change", "staff.underwriter"))
                .isInstanceOf(InvalidPolicyStateException.class)
                .hasMessageContaining("is already 1000000.00");
        }
    }
}
