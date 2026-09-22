package tz.co.nlolo.lifeplatform.billing;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.infrastructure.BillingScheduleRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.PremiumInvoiceRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.product.api.*;
import org.junit.jupiter.api.AfterEach;
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

import java.math.BigDecimal;
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
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
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
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/underwriting/V4__proposal_identity.sql",
            "db-migrations/underwriting/V5__explicit_decision.sql",
            "db-migrations/underwriting/V6__proposal_terms_and_beneficiaries.sql",
            "db-migrations/underwriting/V8__rating_multiplier.sql",
            "db-migrations/underwriting/V9__group_proposal.sql",
            "db-migrations/underwriting/V10__issuance_failure.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
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
            "db-migrations/billing/V1__create_billing_schema.sql",
            "db-migrations/billing/V2__grants_rls_money_checks_and_notification_columns.sql",
            "db-migrations/billing/V3__amount_paid.sql");
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private BillingScheduleRepository billingScheduleRepository;
    @Autowired private PremiumInvoiceRepository premiumInvoiceRepository;

    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(7000);
    private static final AtomicInteger CODE_SEQ = new AtomicInteger(1);

    private UUID tenantId;

    @BeforeEach
    void setTenant() {
        tenantId = UUID.randomUUID();
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
                "group onboarding", IssuanceBasis.MIGRATION, null, null, new BigDecimal("0.5000")),
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
    // Fixtures
    // ---------------------------------------------------------------------------------

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
            InterestMethod.FLAT_RATE, RepaymentFrequency.MONTHLY, ratePercent);
    }

    private GroupSchemeView issueEmployerScheme() {
        GroupProduct product = groupProduct();
        return policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            person("Employer Co"), product.productId(), product.productVersionId(), null,
            BenefitBasis.FLAT, new BigDecimal("1000000.00"), null, null, "TZS", null, oneEmployee(),
            new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now(), null,
            "group onboarding", IssuanceBasis.MIGRATION), "staff-1");
    }

    private List<PolicyApi.MemberInput> oneBorrower() {
        return List.of(PolicyApi.MemberInput.borrower("Amina Hassan Mwinyi",
            LocalDate.of(1988, 3, 14), null,
            new LoanTerms(new BigDecimal("8500000.00"), BigDecimal.ZERO, 48,
                RepaymentFrequency.MONTHLY, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 3))));
    }

    private List<PolicyApi.MemberInput> oneEmployee() {
        return List.of(new PolicyApi.MemberInput(person("Employee"), null, null, null));
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
