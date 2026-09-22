package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.GroupSchemeView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.underwriting.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
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
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

/**
 * Group business through the underwriting pipeline, end to end.
 *
 * <p>Proposed as a case, assessed, decided by a person, issued as an OFFER, and on risk when
 * the employer's first premium clears — the same journey individual business takes. Until
 * this, {@code POST /group-schemes} created the policy, the scheme and every member in one
 * call, on risk on return, with nobody having looked at it.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class GroupPipelineEndToEndTest {

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
            "db-migrations/policy/V8__group_policies_have_no_single_life_assured.sql",
            "db-migrations/policy/V9__group_scheme_and_members.sql",
            "db-migrations/policy/V13__freeform_members.sql",
            "db-migrations/policy/V14__credit_life_scheme.sql",
            "db-migrations/policy/V15__enrolment_submission.sql",
            "db-migrations/policy/V16__insurer_issued_member_reference.sql",
            "db-migrations/policy/V10__one_policy_per_underwriting_case.sql",
            "db-migrations/policy/V11__not_taken_up_status.sql",
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @Autowired private UnderwritingApi underwritingApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;

    private static final AtomicInteger SEQ = new AtomicInteger(5000);

    private UUID tenantId;

    private record ProductFixture(UUID productId, UUID productVersionId) {}

    @BeforeEach
    void freshTenant() {
        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private UUID person(String name) {
        return partyApi.registerIndividual(name, LocalDate.of(1985, 6, 15),
            "+2557" + String.format("%08d", SEQ.incrementAndGet()), null, "test").partyId();
    }

    private ProductFixture groupProduct(String code) {
        ProductSummaryView product = productApi.createProduct(code, "Group Life " + code,
            ProductCategory.GROUP_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)), null, ANY_FILING, "actuary");
        return new ProductFixture(product.productId(),
            productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId());
    }

    private GroupProposal flatProposal(List<UUID> lives) {
        return new GroupProposal(GroupBenefitBasis.FLAT, new BigDecimal("5000000.00"), null, null, "TZS",
            List.of(),
            lives.stream().map(id -> new GroupProposal.MemberLine(id, null, null)).toList(),
            new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now(), null);
    }

    /**
     * The scheme the decision listener issued for this employer.
     *
     * <p>Polled rather than read once. The listener is AFTER_COMMIT on the calling thread, so
     * in practice the row is already there when decide() returns — but a test depending on
     * that reads as though it were asserting the timing, and would fail confusingly if the
     * listener were ever made asynchronous. Same shape as
     * {@code PolicyApiIntegrationTest.issueFromProposal}.
     */
    private String awaitSchemeFor(UUID employerPartyId) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            TenantContext.set(tenantId);
            List<PolicyView> found = policyApi
                .searchPolicies(employerPartyId, null, null, null, null, PageRequest.of(0, 10))
                .getContent();
            if (!found.isEmpty()) {
                return found.get(0).policyNumber();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No scheme was issued for employer " + employerPartyId);
    }

    @Test
    void anAcceptedGroupCaseBecomesASchemeOfferThatGoesOnRiskOnTheFirstPremium() throws InterruptedException {
        TenantContext.set(tenantId);
        UUID employer = person("ABC Company");
        UUID first = person("Juma Employee");
        UUID second = person("Asha Employee");
        ProductFixture product = groupProduct("GRP-PIPE-01");

        UnderwritingCaseView opened = underwritingApi.openCase(employer, product.productId(),
            product.productVersionId(), null, flatProposal(List.of(first, second)), "underwriter1");

        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.FINANCIAL,
            "Employer accounts reviewed", new BigDecimal("10"), "uw");

        // No engine opinion on a scheme, so no senior is demanded for "departing" from one.
        assertThat(underwritingApi.getCase(opened.caseId()).recommendationOutcome()).isNull();

        underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Scheme accepted"),
            "uw", false);

        String policyNumber = awaitSchemeFor(employer);
        TenantContext.set(tenantId);
        GroupSchemeView scheme = policyApi.getGroupScheme(policyNumber);

        // Issued as an OFFER, with the proposal's own premium -- not a computed one.
        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.PROPOSED);
        assertThat(policyApi.getPolicy(policyNumber).premiumAmount())
            .as("a scheme's premium is agreed with the employer, never derived from an age band")
            .isEqualByComparingTo(new BigDecimal("1200000.00"));
        // And the schedule came across, valued by policy's own calculator.
        assertThat(scheme.activeMemberCount()).isEqualTo(2);
        assertThat(scheme.totalCoveredAmount()).isEqualByComparingTo(new BigDecimal("10000000.00"));

        policyApi.activateOnFirstPremium(policyNumber);
        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void aDeclinedGroupCaseIssuesNothing() {
        TenantContext.set(tenantId);
        UUID employer = person("Rejected Company");
        ProductFixture product = groupProduct("GRP-PIPE-DECLINE");
        UnderwritingCaseView opened = underwritingApi.openCase(employer, product.productId(),
            product.productVersionId(), null, flatProposal(List.of(person("A Life"))), "underwriter1");
        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.FINANCIAL,
            "Poor claims experience", new BigDecimal("80"), "uw");

        underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.DECLINED, null, "Claims experience"),
            "uw", false);

        TenantContext.set(tenantId);
        assertThat(policyApi.searchPolicies(employer, null, null, null, null, PageRequest.of(0, 10))
            .getContent()).isEmpty();
    }
}
