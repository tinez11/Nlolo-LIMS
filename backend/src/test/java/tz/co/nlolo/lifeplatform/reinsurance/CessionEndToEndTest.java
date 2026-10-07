package tz.co.nlolo.lifeplatform.reinsurance;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.BenefitBasis;
import tz.co.nlolo.lifeplatform.policy.api.IssuanceBasis;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyUtilisationView;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyView;
import tz.co.nlolo.lifeplatform.reinsurance.domain.Cession;
import tz.co.nlolo.lifeplatform.reinsurance.domain.PolicyProjection;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.CessionRepository;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.ReinsurancePolicyProjectionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;

/**
 * Task 7, Step 6 -- the real end-to-end proof of cession on issuance: {@code
 * PolicyApi.issuePolicy} (real API) -> {@code policy.PolicyIssued} (real event) -> {@code
 * reinsurance.application.PolicyEventListener} -> {@code CessionCalculator} -> a real {@code
 * reinsurance.cession} row (or, for XOL/no-treaty/sub-retention, deliberately none) -> {@code
 * reinsurance.CessionRecorded} (real event, recorded via the AFTER_COMMIT {@link EventRecorder},
 * the same pattern {@code CommissionPayoutEndToEndTest} uses).
 *
 * <p>Runs against real Postgres as {@code app_role} (NOSUPERUSER NOBYPASSRLS), mirroring {@code
 * ReinsuranceApiIntegrationTest}'s bootstrap plus the full policy-issuance migration chain {@code
 * ClaimSettlementEndToEndTest} already uses.
 *
 * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} chains are synchronous, same thread:
 * {@code issuePolicy}'s own {@code @Transactional} commits when the call returns, firing {@code
 * PolicyEventListener} before control comes back to this test. No await/sleep anywhere here.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(CessionEndToEndTest.EventRecorderConfiguration.class)
class CessionEndToEndTest {

    private static final String APP_ROLE_PASSWORD = "cession_e2e_password";
    private static final String CURRENCY = "TZS";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_role");
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
    }

    @BeforeAll
    static void applyMigrationsAndBootstrapAppRole() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
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
            "db-migrations/reinsurance/V1__create_reinsurance_schema.sql",
            "db-migrations/reinsurance/V2__grants_rls_money_checks_reinsurer_and_projection.sql",
            "db-migrations/reinsurance/V4__projection_product_category.sql",
            "db-migrations/reinsurance/V5__bordereau.sql",
            "db-migrations/reinsurance/V6__scheme_may_open_empty.sql",
            "db-migrations/reinsurance/V7__statement.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    /** Records reinsurance.CessionRecorded so "exactly one, and none on redelivery" is assertable
     * directly rather than inferred from state -- same pattern as CommissionPayoutEndToEndTest. */
    @TestConfiguration
    static class EventRecorderConfiguration {
        @Bean
        EventRecorder cessionTestEventRecorder() { return new EventRecorder(); }
    }

    static class EventRecorder {
        private final List<DomainEventEnvelope<?>> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void record(DomainEventEnvelope<?> envelope) { received.add(envelope); }

        void clear() { received.clear(); }

        List<DomainEventEnvelope<?>> ofType(String eventType) {
            return received.stream().filter(e -> eventType.equals(e.eventType())).toList();
        }
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private ReinsuranceApi reinsuranceApi;
    @Autowired private CessionRepository cessionRepository;
    @Autowired private ReinsurancePolicyProjectionRepository policyProjectionRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EventRecorder eventRecorder;

    private TransactionTemplate transactionTemplate;

    @AfterEach
    void resetAfterEach() { TenantContext.clear(); }

    private TransactionTemplate transactionTemplate() {
        if (transactionTemplate == null) {
            transactionTemplate = new TransactionTemplate(transactionManager);
        }
        return transactionTemplate;
    }

    private record Fixture(UUID applicantId, UUID productId, UUID productVersionId) {}

    /** Mirrors ClaimSettlementEndToEndTest.buildFixture. */
    private Fixture buildFixture(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Cession E2E Applicant " + productCode, LocalDate.of(1985, 3, 1),
            "+25571700" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Cession E2E Product", ProductCategory.TERM_LIFE, CURRENCY, "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    /**
     * An offer: issued, but with no premium yet collected, so the platform carries no risk and
     * has nothing to cede.
     *
     * <p>Split out of {@link #issuePolicy} when cession moved from {@code PolicyIssued} to
     * {@code PolicyActivated}.
     */
    private String issueOffer(UUID tenantId, Fixture fixture, BigDecimal sumAssured, BigDecimal premium) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), sumAssured, CURRENCY, premium, CURRENCY, "MONTHLY", null, List.of(),
            "Cession E2E test");
        return policyApi.issuePolicy(null, request, "test-staff").policyNumber();
    }

    private String issuePolicy(UUID tenantId, Fixture fixture, BigDecimal sumAssured, BigDecimal premium) {
        String policyNumber = issueOffer(tenantId, fixture, sumAssured, premium);
        // Cover starts with the first premium, and only cover is cedable.
        TenantContext.set(tenantId);
        policyApi.activateOnFirstPremium(policyNumber);
        return policyNumber;
    }

    private TreatyView createTreaty(UUID tenantId, TreatyType type, BigDecimal retention, BigDecimal cessionPercent) {
        TenantContext.set(tenantId);
        return reinsuranceApi.createTreaty(new ReinsuranceApi.CreateTreatyRequest(
            "Africa Re", type, retention, CURRENCY, cessionPercent, LocalDate.now().minusMonths(1), null),
            "finance-officer");
    }

    @Test
    void aGroupSchemeIsNotCededBecauseItsSumAssuredIsManyLivesNotOne() {
        // A scheme's sumAssured is the TOTAL of a member schedule. Everything in this module
        // treats that figure as one life's cover and tests it against the treaty's retention,
        // so ceding a scheme would cede a whole book against a retention meant for one person
        // -- here, 10,000,000 of total cover ceded as though one borrower were insured for it,
        // against a retention of 1,000,000. A surplus treaty retains and cedes PER LIFE, and
        // nothing in this module knows how to do that.
        //
        // Schemes reached this listener for the first time on 2026-09-22: issueGroupScheme used
        // to activate a policy WITHOUT publishing PolicyActivated, which is the defect that also
        // meant no group scheme ever accrued commission.
        //
        // The client answered the treaty question the same day, and the answer keeps this test
        // as it is. A treaty states which CLASSES OF BUSINESS it covers and may carry special
        // provisions for group schemes -- free cover limits, automatic acceptance limits, a
        // maximum exposure per scheme, aggregation rules. ReinsuranceTreaty has none of those
        // concepts, so there is nothing to evaluate a scheme against. And where a treaty cedes a
        // proportion, the ceded amount follows the INSURED amount, which on credit life declines
        // monthly -- something a single immutable Cession row written at activation cannot do.
        //
        // So this is not a placeholder awaiting an answer; the answer is in, and it says the
        // treaty model has to grow before a scheme can be ceded at all. When it does, this test
        // is where the new behaviour gets stated.
        UUID tenantId = UUID.randomUUID();
        // Null cession percent: a SURPLUS treaty cedes by retention limit and refuses to carry
        // one. The retention is deliberately well below the scheme total, so this test would
        // see a cession if the guard were removed.
        createTreaty(tenantId, TreatyType.SURPLUS, new BigDecimal("1000000.00"), null);
        eventRecorder.clear();

        String schemePolicyNumber = issueGroupScheme(tenantId, "CESSION-E2E-GROUP");

        assertThat(cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(
            tenantId, schemePolicyNumber)).isEmpty();
    }

    /** Two lives of 5,000,000 each: a 10,000,000 scheme total that is not one 10,000,000 risk. */
    private String issueGroupScheme(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        PartyView employer = partyApi.registerIndividual("Cession E2E Employer " + productCode,
            LocalDate.of(1980, 1, 1),
            "+25571800" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Cession E2E Group Product",
            ProductCategory.GROUP_LIFE, CURRENCY, "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());

        PartyView memberOne = partyApi.registerIndividual("Scheme Member One " + productCode,
            LocalDate.of(1988, 4, 1),
            "+25571900" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent");
        PartyView memberTwo = partyApi.registerIndividual("Scheme Member Two " + productCode,
            LocalDate.of(1990, 5, 2),
            "+25572000" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent");

        return policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            employer.partyId(), product.productId(), snapshot.productVersionId(), null,
            BenefitBasis.FLAT, new BigDecimal("5000000.00"), null, null, CURRENCY, null,
            List.of(new PolicyApi.MemberInput(memberOne.partyId(), null, null, null),
                    new PolicyApi.MemberInput(memberTwo.partyId(), null, null, null)),
            new BigDecimal("120000.00"), CURRENCY, "ANNUALLY", LocalDate.now(), null,
            "cession e2e group", IssuanceBasis.MIGRATION), "test-staff").policyNumber();
    }

    @Test
    void aQuotaShareTreatyCedesTheStatedPercentageOfRiskAndPremium() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CESSION-E2E-QS");
        createTreaty(tenantId, TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), new BigDecimal("30.00"));
        eventRecorder.clear();

        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("2000000"), new BigDecimal("100000.00"));

        TenantContext.set(tenantId);
        List<Cession> cessions = cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber);
        assertThat(cessions).hasSize(1);
        Cession cession = cessions.get(0);
        assertThat(cession.getCededAmount()).isEqualByComparingTo("600000.00");
        assertThat(cession.getCededPremiumAmount()).isEqualByComparingTo("30000.00");
        assertThat(eventRecorder.ofType("reinsurance.CessionRecorded")).hasSize(1);
    }

    /**
     * The other half of every cession test in this class: an offer is not risk, so there is
     * nothing to cede.
     *
     * <p>A live QUOTA_SHARE treaty is deliberately in place, so the emptiness below is caused by
     * the policy being unpaid and nothing else. Before cession moved to {@code PolicyActivated}
     * this would have ceded 30% of a contract the platform was not on risk for -- and paid the
     * reinsurer a premium for the privilege.
     */
    @Test
    void nothingIsCededForAnOfferNobodyHasPaidFor() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CESSION-E2E-OFFER");
        createTreaty(tenantId, TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), new BigDecimal("30.00"));
        eventRecorder.clear();

        String policyNumber = issueOffer(tenantId, fixture, new BigDecimal("2000000"), new BigDecimal("100000.00"));

        TenantContext.set(tenantId);
        assertThat(cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber))
            .as("an offer is not risk the platform carries, so there is nothing to cede")
            .isEmpty();
        assertThat(policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber))
            .as("reinsurance should not have heard of a policy that is only an offer")
            .isEmpty();
        assertThat(eventRecorder.ofType("reinsurance.CessionRecorded")).isEmpty();
    }

    @Test
    void aSurplusTreatyCedesTheExcessOverRetention() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CESSION-E2E-SURPLUS");
        createTreaty(tenantId, TreatyType.SURPLUS, new BigDecimal("500000.00"), null);
        eventRecorder.clear();

        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("2000000"), new BigDecimal("100000.00"));

        TenantContext.set(tenantId);
        List<Cession> cessions = cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber);
        assertThat(cessions).hasSize(1);
        assertThat(cessions.get(0).getCededAmount()).isEqualByComparingTo("1500000.00");
        assertThat(eventRecorder.ofType("reinsurance.CessionRecorded")).hasSize(1);
    }

    @Test
    void aSurplusTreatyWithRetentionAboveTheSumAssuredCedesNothing() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CESSION-E2E-SURPLUS-NONE");
        createTreaty(tenantId, TreatyType.SURPLUS, new BigDecimal("5000000.00"), null);
        eventRecorder.clear();

        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("2000000"), new BigDecimal("100000.00"));

        TenantContext.set(tenantId);
        assertThat(cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber)).isEmpty();
        assertThat(eventRecorder.ofType("reinsurance.CessionRecorded")).isEmpty();
    }

    @Test
    void anXolTreatyCedesNothingAtIssuanceButTheProjectionRowIsStillWritten() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CESSION-E2E-XOL");
        createTreaty(tenantId, TreatyType.XOL, new BigDecimal("500000.00"), null);
        eventRecorder.clear();

        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("2000000"), new BigDecimal("100000.00"));

        TenantContext.set(tenantId);
        assertThat(cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber)).isEmpty();
        assertThat(eventRecorder.ofType("reinsurance.CessionRecorded")).isEmpty();

        TenantContext.set(tenantId);
        Optional<PolicyProjection> projection = policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber);
        assertThat(projection).as("XOL recovery depends on the projection row existing").isPresent();
        assertThat(projection.get().getSumAssuredAmount()).isEqualByComparingTo("2000000");
    }

    @Test
    void aPolicyIssuedWithNoActiveTreatyWritesTheProjectionButCedesNothing() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CESSION-E2E-NOTREATY");
        eventRecorder.clear();

        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("2000000"), new BigDecimal("100000.00"));

        TenantContext.set(tenantId);
        assertThat(cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber)).isEmpty();
        assertThat(eventRecorder.ofType("reinsurance.CessionRecorded")).isEmpty();
        TenantContext.set(tenantId);
        assertThat(policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber)).isPresent();
    }

    /**
     * A real at-least-once redelivery of {@code policy.PolicyActivated} -- the same envelope
     * republished through {@code ApplicationEventPublisher} inside a {@code TransactionTemplate}
     * (not a second call through {@code PolicyApi}, which cannot legally re-issue the same policy
     * number) -- must still leave exactly one cession row, backstopped by {@code ux_cession_once}
     * and made a no-op earlier by {@code ReinsuranceApiImpl.persistCession}'s existence check, and
     * must publish no second {@code CessionRecorded}.
     */
    @Test
    void aRedeliveredPolicyIssuedStillLeavesExactlyOneCessionAndPublishesNoSecondEvent() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CESSION-E2E-REDELIVER");
        createTreaty(tenantId, TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), new BigDecimal("30.00"));
        eventRecorder.clear();

        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("2000000"), new BigDecimal("100000.00"));
        TenantContext.set(tenantId);
        assertThat(cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber)).hasSize(1);
        assertThat(eventRecorder.ofType("reinsurance.CessionRecorded")).hasSize(1);

        // policy.PolicyActivated, field-for-field from PolicyApiImpl's published shape -- only the
        // fields PolicyEventListener actually reads.
        Map<String, Object> payload = Map.of(
            "policyNumber", policyNumber,
            "productId", fixture.productId(),
            "issueDate", LocalDate.now().toString(),
            "sumAssured", Map.of("amount", "2000000", "currencyCode", CURRENCY),
            "premium", Map.of("amount", "100000.00", "currencyCode", CURRENCY));
        var envelope = DomainEventEnvelope.of("policy.PolicyActivated", tenantId, payload);
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(envelope));

        TenantContext.set(tenantId);
        assertThat(cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber))
            .as("a redelivered PolicyIssued must not double-cede")
            .hasSize(1);
        assertThat(eventRecorder.ofType("reinsurance.CessionRecorded"))
            .as("a redelivered PolicyIssued must not publish a second CessionRecorded")
            .hasSize(1);
    }

    // ---- What has been ceded TO a treaty ------------------------------------------------------

    /**
     * Cessions could previously be reached only through the policy they were made on, so a treaty
     * stated a retention limit and a cession percent while every cession naming it was
     * unreachable from it -- "how much have we ceded to this reinsurer" had no query behind it.
     */
    @Test
    void aTreatyListsTheCessionsMadeToItAndTotalsThem() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CESSION-E2E-BYTREATY");
        TreatyView treaty = createTreaty(tenantId, TreatyType.QUOTA_SHARE,
            new BigDecimal("0.00"), new BigDecimal("30.00"));

        issuePolicy(tenantId, fixture, new BigDecimal("2000000"), new BigDecimal("100000.00"));
        issuePolicy(tenantId, fixture, new BigDecimal("1000000"), new BigDecimal("50000.00"));

        TenantContext.set(tenantId);
        assertThat(reinsuranceApi.listCessionsForTreaty(treaty.treatyId(), Pageable.unpaged()))
            .as("both policies ceded to this treaty")
            .hasSize(2);

        TreatyUtilisationView utilisation = reinsuranceApi.getTreatyUtilisation(treaty.treatyId());
        assertThat(utilisation.cessionCount()).isEqualTo(2);
        // 30% of 2,000,000 + 30% of 1,000,000.
        assertThat(utilisation.cededAmount()).isEqualByComparingTo("900000.00");
        // 30% of 100,000 + 30% of 50,000.
        assertThat(utilisation.cededPremiumAmount()).isEqualByComparingTo("45000.00");
    }

    /**
     * The totals are summed in the DATABASE, not over the page in hand. Summing a page would
     * report one page's worth of cession as the treaty's utilisation -- a number that is wrong in
     * the way nobody notices, because it is always plausible and always too small.
     */
    @Test
    void treatyTotalsCoverEveryCessionNotJustTheFirstPage() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CESSION-E2E-PAGED");
        TreatyView treaty = createTreaty(tenantId, TreatyType.QUOTA_SHARE,
            new BigDecimal("0.00"), new BigDecimal("30.00"));

        for (int i = 0; i < 3; i++) {
            issuePolicy(tenantId, fixture, new BigDecimal("1000000"), new BigDecimal("50000.00"));
        }

        TenantContext.set(tenantId);
        // One row per page, so a page-summing implementation would report 300,000.00 here.
        assertThat(reinsuranceApi.listCessionsForTreaty(treaty.treatyId(), PageRequest.of(0, 1)).getContent())
            .hasSize(1);
        assertThat(reinsuranceApi.getTreatyUtilisation(treaty.treatyId()).cededAmount())
            .isEqualByComparingTo("900000.00");
    }

    /** An ACTIVE treaty nobody has ceded to yet reports zero, never null -- a null on a money
     *  screen reads as a failure to load rather than as "nothing ceded". */
    @Test
    void aTreatyWithNoCessionsReportsZeroRatherThanNull() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        TreatyView treaty = createTreaty(tenantId, TreatyType.QUOTA_SHARE,
            new BigDecimal("0.00"), new BigDecimal("30.00"));

        TenantContext.set(tenantId);
        TreatyUtilisationView utilisation = reinsuranceApi.getTreatyUtilisation(treaty.treatyId());
        assertThat(utilisation.cessionCount()).isZero();
        assertThat(utilisation.cededAmount()).isEqualByComparingTo("0");
        assertThat(utilisation.cededPremiumAmount()).isEqualByComparingTo("0");
        // The currency comes from the treaty itself, since there is no cession row to read one off.
        assertThat(utilisation.cededCurrency()).isEqualTo("TZS");
    }
}
