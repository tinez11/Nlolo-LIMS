// In billing.application rather than billing, so the redelivery test can call
// BillingApiImpl.raiseSinglePremiumInvoice -- package-private, like every other listener entry
// point on that class. Simulating a redelivered AFTER_COMMIT event any other way would mean
// driving a TransactionTemplate just to reach a method this package can already see.
package tz.co.nlolo.lifeplatform.billing.application;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceStatus;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
import tz.co.nlolo.lifeplatform.billing.api.NothingOwedException;
import tz.co.nlolo.lifeplatform.billing.api.PremiumCreditView;
import tz.co.nlolo.lifeplatform.billing.domain.PremiumCredit;
import tz.co.nlolo.lifeplatform.billing.domain.PremiumInvoice;
import tz.co.nlolo.lifeplatform.billing.infrastructure.BillingScheduleRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.PremiumCreditRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.PremiumInvoiceRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.product.api.*;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

/**
 * A credit-life master policy is a container for borrowers, not a thing that is itself billed.
 *
 * <p>Before this class existed, issuing one produced a {@code BillingSchedule} and twelve
 * {@code PremiumInvoice} rows ahead of it, for the premium figure whoever called
 * {@code issueGroupScheme} happened to type. Those invoices then fell due, aged into arrears,
 * and dunned the lender for money the contract never asked for. The cause is structural rather
 * than a slip: {@code billing.PolicyEventListener} reacts to <b>every</b> {@code PolicyIssued},
 * and {@code PremiumFrequency} had no way to say "paid once".
 *
 * <p>The real premium arrives file by file — see {@code EnrolmentApiImpl.accept} — and that is
 * the subject of the second half of this class.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class SinglePremiumIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    /*
     * Its OWN MinIO: submit() stores the lender's file through DocumentApi. Without this the
     * class silently uses whatever object store the dev compose stack happens to be running,
     * which passes on a developer machine and fails in CI -- the same trap EnrolmentIntegrationTest
     * and ClaimEvidenceIntegrationTest already carry the fix for.
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
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
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
            "db-migrations/product/V29__funeral_group_rate.sql",
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
            "db-migrations/underwriting/V18__sale_channel_and_branch.sql",
            "db-migrations/underwriting/V19__group_funeral_proposal.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
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
            "db-migrations/policy/V38__group_funeral_scheme.sql",
            "db-migrations/document/V1__create_document_schema.sql",
            "db-migrations/document/V2__add_content_type_and_file_name.sql",
            "db-migrations/document/V4__enrolment_schedule_document_type.sql",
            "db-migrations/document/V7__journal_support_document_type.sql",
            "db-migrations/document/V8__reinsurance_statement_document_type.sql",
            "db-migrations/document/V9__ifrs17_engine_document_types.sql",
            "db-migrations/billing/V1__create_billing_schema.sql",
            "db-migrations/billing/V2__grants_rls_money_checks_and_notification_columns.sql",
            "db-migrations/billing/V3__amount_paid.sql",
            "db-migrations/billing/V5__single_premium_invoice.sql",
            "db-migrations/billing/V6__premium_credit.sql",
            "db-migrations/billing/V7__policy_inception_invoice.sql",
            "db-migrations/billing/V8__schedule_premium_paying_until.sql");

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
    @Autowired private BillingScheduleRepository billingScheduleRepository;
    @Autowired private PremiumInvoiceRepository premiumInvoiceRepository;
    @Autowired private PremiumCreditRepository premiumCreditRepository;
    /** Only for the redelivery test, which calls the consumer directly rather than re-firing an event. */
    @Autowired private BillingApiImpl billingApiImpl;

    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(7000);
    private static final AtomicInteger CODE_SEQ = new AtomicInteger(1);

    private UUID tenantId;

    /**
     * Published once per test, lazily, so the retail cases share one version within a test and
     * the redelivery case can name the same one the policy was written on.
     */
    private GroupProduct singlePremiumProductCache;

    @BeforeEach
    void setTenant() {
        tenantId = UUID.randomUUID();
        singlePremiumProductCache = null;
        TenantContext.set(tenantId);
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    // ---------------------------------------------------------------------------------
    // The master policy does not bill itself
    // ---------------------------------------------------------------------------------

    @Test
    void aCreditLifeSchemeGeneratesNoBillingScheduleOfItsOwn() {
        // The defect this closes: a BillingSchedule plus twelve invoices for a hand-typed
        // premium nobody agreed, which then fell due and dunned the lender.
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));

        assertThat(billingScheduleRepository
            .findByPolicyNumberAndTenantId(scheme.policyNumber(), tenantId)).isEmpty();
        assertThat(premiumInvoiceRepository
            .findByPolicyNumberAndTenantIdOrderByDueDate(scheme.policyNumber(), tenantId)).isEmpty();
    }

    @Test
    void aRetailSinglePremiumRaisesOneInvoiceDueTheDayCoverBegins() {
        // The gap this closes, and it was not a small one: SINGLE meant "raise nothing" for
        // EVERY contract, because the only single-premium product was a credit-life master
        // policy billed file by file. A retail single premium is also SINGLE -- so the policy
        // was written, the premium was rated and stored on it, and no invoice was ever raised.
        // The customer owed money the platform never asked for, and cover ran regardless.
        PolicyView policy = issueRetailSinglePremium(new BigDecimal("125000.00"));

        // No schedule: billing_schedule's own CHECK admits only MONTHLY, QUARTERLY, ANNUALLY.
        assertThat(billingScheduleRepository
            .findByPolicyNumberAndTenantId(policy.policyNumber(), tenantId)).isEmpty();

        List<PremiumInvoice> invoices = premiumInvoiceRepository
            .findByPolicyNumberAndTenantIdOrderByDueDate(policy.policyNumber(), tenantId);
        assertThat(invoices).hasSize(1);
        PremiumInvoice only = invoices.get(0);
        assertThat(only.getAmount()).isEqualByComparingTo(new BigDecimal("125000.00"));
        // Due the day cover begins. A due date later than commencement would mean the insurer
        // carries risk it has not been paid for and cannot dun anybody for.
        assertThat(only.getDueDate()).isEqualTo(LocalDate.now());
        // Belongs to neither a cycle nor a file. Its origin is the policy itself, which is
        // what chk_premium_invoice_at_most_one_origin was relaxed to permit.
        assertThat(only.getBillingScheduleId()).isNull();
        assertThat(only.getEnrolmentSubmissionId()).isNull();
    }

    @Test
    void aRedeliveredIssuanceDoesNotChargeTheCustomerTwice() {
        // policy.PolicyIssued is consumed by an AFTER_COMMIT listener, and an AFTER_COMMIT
        // listener gets redelivered. Guarded in Java AND by ux_premium_invoice_policy_inception,
        // whose exactness depends on the due date coming from the policy's own issue date
        // rather than now() -- so calling the method twice must find the first invoice.
        PolicyView policy = issueRetailSinglePremium(new BigDecimal("125000.00"));

        billingApiImpl.raisePolicyInceptionInvoice(tenantId, policy.policyNumber(),
            singlePremiumProduct().productVersionId(), LocalDate.now(),
            new BigDecimal("125000.00"), "TZS");

        assertThat(premiumInvoiceRepository
            .findByPolicyNumberAndTenantIdOrderByDueDate(policy.policyNumber(), tenantId)).hasSize(1);
    }

    @Test
    void aSinglePremiumMayNotBePaidAcrossSeveralMonths() {
        // The two fields were free to disagree, and the dev database holds the proof: policies
        // carrying MONTHLY with a premium-paying term of 1, which reads as "monthly
        // instalments, paid for one month" and is neither. This is the SINGLE arm of that.
        GroupProduct product = singlePremiumProduct();
        UUID applicant = person("Single Premium Applicant");

        assertThatThrownBy(() -> policyApi.issuePolicy(null,
            new PolicyApi.IssueRequest(applicant, product.productId(), product.productVersionId(),
                new BigDecimal("5000000.00"), "TZS", new BigDecimal("125000.00"), "TZS",
                "SINGLE", null, List.of(), "single premium paying-term test",
                LocalDate.now(), 12, 6, null, null),
            "test-staff"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("charged once");
    }

    /**
     * A scheme paid ONCE for a year of cover is charged once, like any other contract.
     *
     * <p>The distinction billing needs is not "is this a group scheme" -- that was this flag's
     * first shape, and under it a family or employer scheme bought with a single yearly
     * contribution would have been issued and then charged nobody, because SINGLE plus
     * group-ness meant "raise nothing". It is "does the premium arrive per enrolment file",
     * which only a loan-basis scheme does.
     */
    @Test
    void aSchemePaidOnceForItsYearIsInvoicedAtInceptionLikeAnyOtherContract() {
        GroupSchemeView scheme = issueEmployerScheme("SINGLE");

        assertThat(billingScheduleRepository
            .findByPolicyNumberAndTenantId(scheme.policyNumber(), tenantId)).isEmpty();

        List<PremiumInvoice> invoices = premiumInvoiceRepository
            .findByPolicyNumberAndTenantIdOrderByDueDate(scheme.policyNumber(), tenantId);
        assertThat(invoices).hasSize(1);
        assertThat(invoices.get(0).getEnrolmentSubmissionId()).isNull();
        assertThat(invoices.get(0).getBillingScheduleId()).isNull();
    }

    @Test
    void aCreditLifeSchemeIsStillNeverChargedEvenThoughItIsAlsoSinglePremium() {
        // The other side of the same distinction: both are SINGLE, and only this one is billed
        // file by file. If this ever starts raising an invoice, a lender is being charged twice
        // -- once here for a figure nobody agreed, and again per accepted file.
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));

        assertThat(premiumInvoiceRepository
            .findByPolicyNumberAndTenantIdOrderByDueDate(scheme.policyNumber(), tenantId)).isEmpty();
    }

    @Test
    void anOrdinaryGroupSchemeStillBillsOnItsCycleAsItAlwaysDid() {
        // The guard is on the FREQUENCY, not on the product category. An employer scheme must
        // be untouched -- and this is the test that fails if the guard is written as
        // "if CREDIT_LIFE", which billing has no business knowing about anyway.
        GroupSchemeView scheme = issueEmployerScheme();

        assertThat(billingScheduleRepository
            .findByPolicyNumberAndTenantId(scheme.policyNumber(), tenantId)).isNotEmpty();
        assertThat(premiumInvoiceRepository
            .findByPolicyNumberAndTenantIdOrderByDueDate(scheme.policyNumber(), tenantId)).isNotEmpty();
    }

    // ---------------------------------------------------------------------------------
    // The rate a lender agreed
    // ---------------------------------------------------------------------------------

    @Test
    void aCreditLifeSchemeMustStateItsRate() {
        // Client answer 3.1, 2026-09-22: the rate is negotiated per lender -- 0.4% for one,
        // 0.5% for another -- so there is no default to fall back on. Finding that out at the
        // first accepted file would bounce a lender's whole month.
        assertThatThrownBy(() -> issueCreditLifeScheme(null))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("rate");
    }

    @Test
    void anEmployerSchemeMayNotCarryARate() {
        // Symmetric with the interest-method check already in issueGroupScheme: a field that
        // means nothing on this shape of contract is refused rather than stored and ignored.
        GroupProduct product = groupProduct();
        assertThatThrownBy(() -> policyApi.issueGroupScheme(
            new PolicyApi.IssueGroupSchemeRequest(person("Employer Co"), product.productId(),
                product.productVersionId(), null, BenefitBasis.FLAT, new BigDecimal("1000000.00"),
                null, null, "TZS", null, oneEmployee(),
                new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now(), null,
                "group onboarding", IssuanceBasis.MIGRATION, null, null, new BigDecimal("0.5000"), null),
            "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("only on a credit-life scheme");
    }

    @Test
    void aCreditLifeSchemeMayNotBeBilledOnACycle() {
        // ANNUALLY here is exactly how the defect got in: every existing credit-life fixture
        // passed it, and billing dutifully raised a year of invoices against it.
        GroupProduct product = creditLifeProduct();
        assertThatThrownBy(() -> policyApi.issueGroupScheme(
            creditLifeRequest(product, new BigDecimal("0.5000"), "ANNUALLY"), "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("single premium");
    }

    @Test
    void anImplausibleRateIsRefusedByTheDatabaseToo() {
        // A fat finger here misprices an entire lender's book -- every borrower on every future
        // file, silently, until somebody reconciles. 50 reads as 50%, not 0.5%.
        assertThatThrownBy(() -> issueCreditLifeScheme(new BigDecimal("50.0000")))
            .hasMessageContaining("chk_group_scheme_premium_rate_sane");
    }

    // ---------------------------------------------------------------------------------
    // One invoice per accepted file
    // ---------------------------------------------------------------------------------

    /**
     * Three borrowers at 0.5% per annum on the original principal:
     * <pre>
     *   2,400,000 x 0.005 x (18/12) =  18,000.00
     *   1,200,000 x 0.005 x (12/12) =   6,000.00
     *   6,000,000 x 0.005 x (24/12) =  60,000.00
     *                                  ---------
     *                                  84,000.00
     * </pre>
     */
    private static final String THREE_BORROWERS =
        ",Amina Hassan Mwinyi,1988-03-14,F,,,2400000.00,18,2026-08-03\n"
        + ",Joseph Mkenda,1975-11-02,M,,,1200000.00,12,2026-08-05\n"
        + ",Grace Shirima,1992-06-21,F,,,6000000.00,24,2026-08-06\n";

    private static final BigDecimal THREE_BORROWER_TOTAL = new BigDecimal("84000.00");

    @Test
    void anAcceptedFileRaisesExactlyOneInvoiceForTheSumOfItsMembers() {
        // "One file, one invoice" (spec 2.8). Three borrowers, one charge -- which is what a
        // reconciliation argument with a lender is actually about.
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));
        UUID submissionId = submitAndAccept(scheme.policyNumber(), THREE_BORROWERS);

        List<PremiumInvoice> invoices = premiumInvoiceRepository
            .findByPolicyNumberAndTenantIdOrderByDueDate(scheme.policyNumber(), tenantId);

        assertThat(invoices).hasSize(1);
        assertThat(invoices.get(0).getAmount()).isEqualByComparingTo(THREE_BORROWER_TOTAL);
        assertThat(invoices.get(0).getEnrolmentSubmissionId()).isEqualTo(submissionId);
        // Not on a schedule: chk_premium_invoice_has_exactly_one_origin says exactly one of
        // the two, and this one belongs to a file.
        assertThat(invoices.get(0).getBillingScheduleId()).isNull();
    }

    @Test
    void thePremiumIsChargedAtTheSchemesOwnRateSoTwoLendersPayDifferently() {
        // Client answer 3.1: the rate is negotiated per lender. Same three loans, 0.4% instead
        // of 0.5%, so four fifths of the premium.
        GroupSchemeView cheaper = issueCreditLifeScheme(new BigDecimal("0.4000"));
        submitAndAccept(cheaper.policyNumber(), THREE_BORROWERS);

        assertThat(premiumInvoiceRepository
            .findByPolicyNumberAndTenantIdOrderByDueDate(cheaper.policyNumber(), tenantId).get(0)
            .getAmount()).isEqualByComparingTo("67200.00");
    }

    @Test
    void aSecondFileRaisesASecondInvoiceAndDoesNotAmendTheFirst() {
        // Monthly files, monthly charges. An implementation that "topped up" a running invoice
        // would destroy the file-to-invoice correspondence the whole design rests on.
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));
        submitAndAccept(scheme.policyNumber(), THREE_BORROWERS);
        submitAndAccept(scheme.policyNumber(),
            ",Salum Juma Rashid,1969-01-30,M,,,3600000.00,12,2026-09-02\n");

        List<PremiumInvoice> invoices = premiumInvoiceRepository
            .findByPolicyNumberAndTenantIdOrderByDueDate(scheme.policyNumber(), tenantId);

        assertThat(invoices).hasSize(2);
        assertThat(invoices).extracting(PremiumInvoice::getAmount)
            .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .containsExactlyInAnyOrder(THREE_BORROWER_TOTAL, new BigDecimal("18000.00"));
        // Two files, two distinct origins. Neither invoice may claim the other's submission.
        assertThat(invoices).extracting(PremiumInvoice::getEnrolmentSubmissionId)
            .doesNotHaveDuplicates();
    }

    @Test
    void everyEnrolledRowRecordsWhatThatBorrowerWasCharged() {
        // Per row, not merely as a file total: a refund is computed against what THIS loan
        // paid, and a share of the file total would be the wrong number as soon as a lender
        // renegotiates their rate between files.
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));
        UUID submissionId = submitAndAccept(scheme.policyNumber(), THREE_BORROWERS);

        List<EnrolmentRowView> rows = enrolmentApi.listRows(submissionId);
        assertThat(rows).extracting(EnrolmentRowView::premiumAmount)
            .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .containsExactly(new BigDecimal("18000.00"), new BigDecimal("6000.00"),
                             new BigDecimal("60000.00"));
    }

    @Test
    void theRowPremiumsSumToTheInvoiceExactly() {
        // The property that has to hold across four hundred borrowers: every row is rounded to
        // the cent on its own, so the total must be checked rather than assumed.
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.4500"));
        UUID submissionId = submitAndAccept(scheme.policyNumber(), THREE_BORROWERS);

        BigDecimal rowSum = enrolmentApi.listRows(submissionId).stream()
            .map(EnrolmentRowView::premiumAmount)
            .filter(java.util.Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(premiumInvoiceRepository
            .findByPolicyNumberAndTenantIdOrderByDueDate(scheme.policyNumber(), tenantId).get(0)
            .getAmount()).isEqualByComparingTo(rowSum);
    }

    /**
     * A row that is always rejected, whatever the product is configured to allow.
     *
     * <p>Deliberately NOT an out-of-bounds age: {@link #publish} creates its versions with no
     * eligibility bounds, so an eighty-year-old borrower enrols perfectly happily here. A
     * disbursement date in the future is refused by the judge itself -- cover cannot commence
     * before the loan exists -- and so does not depend on how the fixture product is set up.
     */
    private static final String ONE_ROW_ALWAYS_REJECTED =
        ",Zainabu Ally,1987-10-30,F,,,1500000.00,12," + LocalDate.now().plusMonths(1) + "\n";

    @Test
    void aFileThatEnrolledNobodyRaisesNoInvoice() {
        // Every row rejected: nothing was insured, so nothing is owed. A zero invoice would
        // fail chk_premium_invoice_amount_positive anyway, and is not a thing to send a lender.
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));
        submitAndAccept(scheme.policyNumber(), ONE_ROW_ALWAYS_REJECTED);

        assertThat(premiumInvoiceRepository
            .findByPolicyNumberAndTenantIdOrderByDueDate(scheme.policyNumber(), tenantId)).isEmpty();
    }

    @Test
    void aRejectedRowIsNeverCharged() {
        // One good borrower, one refused. The invoice is for the good one alone.
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));
        UUID submissionId = submitAndAccept(scheme.policyNumber(),
            ",Amina Hassan Mwinyi,1988-03-14,F,,,2400000.00,18,2026-08-03\n"
            + ONE_ROW_ALWAYS_REJECTED);

        assertThat(premiumInvoiceRepository
            .findByPolicyNumberAndTenantIdOrderByDueDate(scheme.policyNumber(), tenantId).get(0)
            .getAmount()).isEqualByComparingTo("18000.00");

        List<EnrolmentRowView> rows = enrolmentApi.listRows(submissionId);
        assertThat(rows).filteredOn(r -> r.outcome() == RowOutcome.REJECTED)
            .allSatisfy(r -> assertThat(r.premiumAmount()).isNull());
    }

    @Test
    void aRedeliveredAcceptanceDoesNotChargeTheLenderTwice() {
        // policy.EnrolmentAccepted is consumed AFTER_COMMIT, and AFTER_COMMIT listeners get
        // redelivered. A second delivery must return the invoice already raised rather than
        // charging the lender again -- and must do it WITHOUT letting the insert fail first,
        // because a failed statement poisons the whole Postgres transaction and nothing can be
        // read back after it. Hence the pre-check in raiseSinglePremiumInvoice, which this
        // test is the reason for.
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));
        UUID submissionId = submitAndAccept(scheme.policyNumber(), THREE_BORROWERS);
        UUID firstInvoiceId = premiumInvoiceRepository
            .findByTenantIdAndEnrolmentSubmissionId(tenantId, submissionId).orElseThrow()
            .getInvoiceId();

        UUID redelivered = billingApiImpl.raiseSinglePremiumInvoice(tenantId, scheme.policyNumber(),
            submissionId, THREE_BORROWER_TOTAL, "TZS",
            // The same acceptance date the event carried. A clock-derived due date would land
            // in a different partition row and slip past the index.
            LocalDate.now());

        assertThat(redelivered).isEqualTo(firstInvoiceId);
        assertThat(premiumInvoiceRepository
            .findByPolicyNumberAndTenantIdOrderByDueDate(scheme.policyNumber(), tenantId)).hasSize(1);
    }

    // ---------------------------------------------------------------------------------
    // Early settlement gives the premium back
    // ---------------------------------------------------------------------------------

    @Test
    void aLoanSettledHalfwayThroughItsTermRefundsHalfItsPremium() {
        // Amina: 2,400,000 over 18 months at 0.5% = 18,000 charged. Disbursed 2026-08-03,
        // settled nine months later, so nine of eighteen months are unexpired: 9,000 back.
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));
        submitAndAccept(scheme.policyNumber(), THREE_BORROWERS);
        PolicyMemberView amina = memberNamed(scheme.policyNumber(), "Amina Hassan Mwinyi");

        policyApi.exitMember(scheme.policyNumber(), amina.policyMemberId(),
            LocalDate.of(2027, 5, 3), ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one");

        assertThat(creditFor(amina.policyMemberId()).getAmount()).isEqualByComparingTo("9000.00");
    }

    @Test
    void aLoanThatRanItsFullTermRefundsNothing() {
        // And specifically does not refund a NEGATIVE amount, which would be a further charge
        // dressed up as a credit.
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));
        submitAndAccept(scheme.policyNumber(), THREE_BORROWERS);
        PolicyMemberView amina = memberNamed(scheme.policyNumber(), "Amina Hassan Mwinyi");

        policyApi.exitMember(scheme.policyNumber(), amina.policyMemberId(),
            LocalDate.of(2028, 2, 3), ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one");

        assertThat(creditsFor(amina.policyMemberId())).isEmpty();
    }

    @Test
    void aSettledClaimRefundsNothingBecauseTheCoverWasUsed() {
        // The distinction exit_reason exists for. The insurer paid out, so the premium was
        // fully earned the moment it did -- refunding here would pay the claim AND give back
        // the money that funded it.
        //
        // Driven through the CLAIM path because exitMember refuses CLAIM_SETTLED outright,
        // which is itself the first line of this defence.
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));
        submitAndAccept(scheme.policyNumber(), THREE_BORROWERS);
        PolicyMemberView amina = memberNamed(scheme.policyNumber(), "Amina Hassan Mwinyi");

        policyApi.dischargeForSettledClaim(scheme.policyNumber(), amina.policyMemberId(),
            LocalDate.of(2027, 5, 3), UUID.randomUUID(), "claims.officer");

        assertThat(creditsFor(amina.policyMemberId())).isEmpty();
    }

    @Test
    void aWrittenOffLoanStillRefundsTheUnexpiredTerm() {
        // Confirmed with the client, 2026-09-22. The lender's credit loss is not the insurer's
        // premium to keep: cover ended, so the unexpired premium goes back. Stated as its own
        // test because the instinct is to treat a write-off like a claim, and it is not one --
        // nothing was paid out.
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));
        submitAndAccept(scheme.policyNumber(), THREE_BORROWERS);
        PolicyMemberView amina = memberNamed(scheme.policyNumber(), "Amina Hassan Mwinyi");

        policyApi.exitMember(scheme.policyNumber(), amina.policyMemberId(),
            LocalDate.of(2027, 5, 3), ExitReason.WRITTEN_OFF, new BigDecimal("900000.00"),
            "staff.one");

        assertThat(creditFor(amina.policyMemberId()).getAmount()).isEqualByComparingTo("9000.00");
    }

    @Test
    void theRefundIsCreditedAgainstTheInvoiceThatActuallyChargedIt() {
        // enrolment_submission_id is what makes this answerable. Without it, "which of the
        // eleven monthly invoices charged this borrower" is a date guess -- and a borrower who
        // enrolled eight files ago is exactly the one who settles early.
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));
        UUID firstFile = submitAndAccept(scheme.policyNumber(), THREE_BORROWERS);
        submitAndAccept(scheme.policyNumber(),
            ",Salum Juma Rashid,1969-01-30,M,,,3600000.00,12,2026-09-02\n");
        PolicyMemberView amina = memberNamed(scheme.policyNumber(), "Amina Hassan Mwinyi");

        policyApi.exitMember(scheme.policyNumber(), amina.policyMemberId(),
            LocalDate.of(2027, 5, 3), ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one");

        UUID invoiceOfFirstFile = premiumInvoiceRepository
            .findByTenantIdAndEnrolmentSubmissionId(tenantId, firstFile).orElseThrow()
            .getInvoiceId();
        assertThat(creditFor(amina.policyMemberId()).getOriginalInvoiceId())
            .isEqualTo(invoiceOfFirstFile);
    }

    @Test
    void creditsAcrossEveryExitNeverExceedWhatTheFileCharged() {
        // The property that has to hold once four hundred borrowers exit one at a time. Every
        // credit is rounded to the cent on its own, so the total must be checked rather than
        // assumed -- and exiting everybody on the day of disbursement is the worst case,
        // because nothing was earned and the whole invoice should come back.
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));
        UUID submissionId = submitAndAccept(scheme.policyNumber(), THREE_BORROWERS);
        BigDecimal invoiced = premiumInvoiceRepository
            .findByTenantIdAndEnrolmentSubmissionId(tenantId, submissionId).orElseThrow()
            .getAmount();

        for (PolicyMemberView member : policyApi.listMembers(scheme.policyNumber(), null, null,
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent()) {
            policyApi.exitMember(scheme.policyNumber(), member.policyMemberId(),
                member.joinedOn(), ExitReason.CANCELLED, BigDecimal.ZERO, "staff.one");
        }

        BigDecimal credited = premiumCreditRepository
            .findByTenantIdAndPolicyNumber(tenantId, scheme.policyNumber()).stream()
            .map(PremiumCredit::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(credited).isEqualByComparingTo(invoiced);
    }

    @Test
    void aMemberWhoWasNeverChargedIsNeverCredited() {
        // An opening-schedule member joined at issuance, before any enrolment file existed, so
        // no row records a premium for them. They must produce no credit rather than a zero
        // one -- and certainly not a NullPointerException in an AFTER_COMMIT listener, which
        // is how this would fail if the lookup were assumed to succeed.
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));
        PolicyMemberView opening = policyApi.listMembers(scheme.policyNumber(), null, null,
            org.springframework.data.domain.PageRequest.of(0, 10)).getContent().get(0);

        policyApi.exitMember(scheme.policyNumber(), opening.policyMemberId(),
            LocalDate.of(2026, 10, 3), ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one");

        assertThat(creditsFor(opening.policyMemberId())).isEmpty();
    }

    @Test
    void aRedeliveredExitDoesNotCreditTheLenderTwice() {
        // AFTER_COMMIT listeners get redelivered, and a member can also be exited twice by a
        // resent exits file. One member refunds once.
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));
        submitAndAccept(scheme.policyNumber(), THREE_BORROWERS);
        PolicyMemberView amina = memberNamed(scheme.policyNumber(), "Amina Hassan Mwinyi");

        policyApi.exitMember(scheme.policyNumber(), amina.policyMemberId(),
            LocalDate.of(2027, 5, 3), ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one");
        policyApi.exitMember(scheme.policyNumber(), amina.policyMemberId(),
            LocalDate.of(2027, 5, 3), ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.two");

        assertThat(creditsFor(amina.policyMemberId())).hasSize(1);
    }

    // ---------------------------------------------------------------------------------
    // What is OWED, not what was charged
    // ---------------------------------------------------------------------------------

    /** The policy page showed a 13,800 invoice DUE when 4,200 of it had been credited back and
     * 9,600 was owed. The invoice now carries what was credited and the balance, and the credits
     * themselves are readable. */
    @Test
    void anInvoiceSaysWhatWasCreditedAndWhatIsStillOwed() {
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));
        UUID file = submitAndAccept(scheme.policyNumber(), THREE_BORROWERS);
        PolicyMemberView amina = memberNamed(scheme.policyNumber(), "Amina Hassan Mwinyi");
        policyApi.exitMember(scheme.policyNumber(), amina.policyMemberId(),
            LocalDate.of(2027, 5, 3), ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one");

        InvoiceView invoice = billingApiImpl.listInvoices(scheme.policyNumber(), null).get(0);
        assertThat(invoice.amount()).isEqualByComparingTo(THREE_BORROWER_TOTAL);   // still what was charged
        assertThat(invoice.amountCredited()).isEqualByComparingTo("9000.00");
        assertThat(invoice.amountPaid()).isEqualByComparingTo("0.00");
        assertThat(invoice.balanceDue()).isEqualByComparingTo("75000.00");          // 84,000 - 9,000
        assertThat(invoice.enrolmentSubmissionId()).isEqualTo(file);

        List<PremiumCreditView> credits = billingApiImpl.listCredits(scheme.policyNumber());
        assertThat(credits).singleElement().satisfies(c -> {
            assertThat(c.policyMemberId()).isEqualTo(amina.policyMemberId());
            assertThat(c.originalInvoiceId()).isEqualTo(invoice.invoiceId());
            assertThat(c.exitReason()).isEqualTo("SETTLED_EARLY");
        });
    }

    /** Paying the BALANCE settles it. Compared to the charged amount alone, a 75,000 payment on an
     * 84,000 invoice carrying a 9,000 credit sat PARTIALLY_PAID for ever. */
    @Test
    void payingTheBalanceSettlesAnInvoiceThatCarriesACredit() {
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));
        submitAndAccept(scheme.policyNumber(), THREE_BORROWERS);
        PolicyMemberView amina = memberNamed(scheme.policyNumber(), "Amina Hassan Mwinyi");
        policyApi.exitMember(scheme.policyNumber(), amina.policyMemberId(),
            LocalDate.of(2027, 5, 3), ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one");
        UUID invoiceId = billingApiImpl.listInvoices(scheme.policyNumber(), null).get(0).invoiceId();

        InvoiceView paid = billingApiImpl.applyConfirmedPayment(invoiceId, new BigDecimal("75000.00"), "TZS", "MM-1");

        assertThat(paid.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(paid.balanceDue()).isEqualByComparingTo("0.00");
    }

    /** Every borrower on a file left on the day of disbursement, so all of it came back. Nothing is
     * owed: it reads PAID rather than sitting DUE for the arrears sweep to chase, and a payment
     * request is refused rather than asking the lender for money already returned. */
    @Test
    void aFullyCreditedInvoiceOwesNothingAndCannotBeRequested() {
        GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.5000"));
        submitAndAccept(scheme.policyNumber(), THREE_BORROWERS);
        for (PolicyMemberView member : policyApi.listMembers(scheme.policyNumber(), null, null,
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent()) {
            policyApi.exitMember(scheme.policyNumber(), member.policyMemberId(),
                member.joinedOn(), ExitReason.CANCELLED, BigDecimal.ZERO, "staff.one");
        }

        InvoiceView invoice = billingApiImpl.listInvoices(scheme.policyNumber(), null).get(0);
        assertThat(invoice.balanceDue()).isEqualByComparingTo("0.00");
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PAID);
        assertThatThrownBy(() -> billingApiImpl.requestPaymentForInvoice(invoice.invoiceId(), "payer", "idem-1"))
            .isInstanceOf(NothingOwedException.class);
    }

    // ---------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------

    /**
     * The one member of this scheme with that name.
     *
     * <p>Asserts there is exactly one rather than taking the first. Two members sharing a name
     * is legitimate in production — a scheme may genuinely insure two people called the same
     * thing — but in a FIXTURE it means the test is reaching for one member and getting
     * another, which is how four refund tests here passed their exit call and then asserted
     * against a borrower who was never charged.
     */
    private PolicyMemberView memberNamed(String policyNumber, String name) {
        List<PolicyMemberView> matches = policyApi.listMembers(policyNumber, null, null,
                org.springframework.data.domain.PageRequest.of(0, 20)).getContent().stream()
            .filter(m -> name.equals(m.memberName())).toList();
        assertThat(matches)
            .as("exactly one member of %s named %s", policyNumber, name)
            .hasSize(1);
        return matches.get(0);
    }

    private List<PremiumCredit> creditsFor(UUID policyMemberId) {
        return premiumCreditRepository.findByTenantIdAndPolicyMemberId(tenantId, policyMemberId);
    }

    private PremiumCredit creditFor(UUID policyMemberId) {
        List<PremiumCredit> credits = creditsFor(policyMemberId);
        assertThat(credits).hasSize(1);
        return credits.get(0);
    }

    private static final String CSV_HEADER =
        "member_reference,borrower_full_name,borrower_date_of_birth,borrower_sex,"
        + "borrower_national_id,borrower_phone,loan_principal_amount,"
        + "loan_term_months,disbursement_date\n";

    /** Submit a file and have a DIFFERENT staff user accept it, which is the only way cover exists. */
    private UUID submitAndAccept(String policyNumber, String rows) {
        EnrolmentSubmissionView submitted = enrolmentApi.submit(policyNumber,
            new ByteArrayInputStream((CSV_HEADER + rows).getBytes(StandardCharsets.UTF_8)),
            "lender-file.csv", "staff.proposer");
        enrolmentApi.accept(submitted.submissionId(), "staff.accepter");
        return submitted.submissionId();
    }

    private record GroupProduct(UUID productId, UUID productVersionId) {}

    private GroupSchemeView issueCreditLifeScheme(BigDecimal ratePercent) {
        return policyApi.issueGroupScheme(
            creditLifeRequest(creditLifeProduct(), ratePercent, "SINGLE"), "staff-1");
    }

    private PolicyApi.IssueGroupSchemeRequest creditLifeRequest(GroupProduct product,
                                                                 BigDecimal ratePercent,
                                                                 String premiumFrequency) {
        return new PolicyApi.IssueGroupSchemeRequest(person("Lender Co"), product.productId(),
            product.productVersionId(), null, BenefitBasis.AMORTISING_LOAN, null, null,
            new BigDecimal("600000000.00"), "TZS", null, oneBorrower(),
            new BigDecimal("52000.00"), "TZS", premiumFrequency,
            // Commences before the loans it covers: a lender scheme is signed first and then
            // fed monthly files of loans disbursed under it.
            LocalDate.of(2026, 6, 1), null, "credit life onboarding", IssuanceBasis.MIGRATION,
            InterestMethod.FLAT_RATE, RepaymentFrequency.MONTHLY, ratePercent, CreditLifePremiumBasis.PER_ANNUM_ON_PRINCIPAL);
    }

    private GroupSchemeView issueEmployerScheme() {
        return issueEmployerScheme("ANNUALLY");
    }

    private GroupSchemeView issueEmployerScheme(String premiumFrequency) {
        GroupProduct product = groupProduct();
        return policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            person("Employer Co"), product.productId(), product.productVersionId(), null,
            BenefitBasis.FLAT, new BigDecimal("1000000.00"), null, null, "TZS", null, oneEmployee(),
            new BigDecimal("1200000.00"), "TZS", premiumFrequency, LocalDate.now(), null,
            "group onboarding", IssuanceBasis.MIGRATION), "staff-1");
    }

    /**
     * The scheme's opening schedule — a borrower who came from no enrolment file.
     *
     * <p>Every scheme must be issued with at least one member, so this borrower exists on every
     * scheme in this class and was never charged a per-file premium. Their name says so, and it
     * must stay distinct from every name in {@link #THREE_BORROWERS}: it used to be "Amina
     * Hassan Mwinyi" as well, so {@code memberNamed} returned THIS member instead of the
     * enrolled one, and four refund tests quietly asserted against somebody who correctly has
     * no credit at all.
     */
    private List<PolicyApi.MemberInput> oneBorrower() {
        return List.of(PolicyApi.MemberInput.borrower("Opening Schedule Borrower",
            LocalDate.of(1988, 3, 14), null,
            new LoanTerms(new BigDecimal("8500000.00"), BigDecimal.ZERO, 48,
                RepaymentFrequency.MONTHLY, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 3))));
    }

    private List<PolicyApi.MemberInput> oneEmployee() {
        return List.of(new PolicyApi.MemberInput(person("Employee"), null, null, null));
    }

    /**
     * A retail single-premium policy: one life, one contract, one charge.
     *
     * <p>Deliberately a TERM_LIFE product and an individual issuance, so nothing about this
     * test passes because of a credit-life code path. It is the case the SINGLE guard used to
     * swallow.
     */
    private PolicyView issueRetailSinglePremium(BigDecimal premium) {
        GroupProduct product = singlePremiumProduct();
        return policyApi.issuePolicy(null,
            new PolicyApi.IssueRequest(person("Single Premium Customer"), product.productId(),
                product.productVersionId(), new BigDecimal("5000000.00"), "TZS",
                premium, "TZS", "SINGLE", null, List.of(), "retail single premium test",
                LocalDate.now(), 12, 1, null, null),
            "test-staff");
    }

    /** A TERM_LIFE product for the retail single-premium cases, published once per test. */
    private GroupProduct singlePremiumProduct() {
        if (singlePremiumProductCache == null) {
            singlePremiumProductCache = publish(ProductCategory.TERM_LIFE, "SP-" + CODE_SEQ.incrementAndGet());
        }
        return singlePremiumProductCache;
    }

    private GroupProduct creditLifeProduct() {
        return publish(ProductCategory.CREDIT_LIFE, "CL-SP-" + CODE_SEQ.incrementAndGet());
    }

    private GroupProduct groupProduct() {
        return publish(ProductCategory.GROUP_LIFE, "GRP-SP-" + CODE_SEQ.incrementAndGet());
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
            "+2557" + String.format("%08d", PHONE_SEQ.incrementAndGet()), null, "test-agent").partyId();
    }
}
