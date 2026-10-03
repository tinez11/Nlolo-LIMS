package tz.co.nlolo.lifeplatform.underwriting;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.underwriting.api.*;
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
 * A group scheme as an underwriting case: the terms and the schedule an employer is asking
 * for, held where an underwriter can look at them before any contract exists.
 *
 * <p>Group business used to bypass underwriting entirely — {@code POST /group-schemes} created
 * the policy, the scheme and every member in one call, ACTIVE on return, with no case, no
 * assessment and no decision. Individual business had a queue, a person's decision, a senior
 * gate and an offer the customer accepts by paying. The flow insuring five hundred people at a
 * time was the unsupervised one.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class GroupProposalIntegrationTest {

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
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
            "db-migrations/benefitpayout/V3__withholding.sql",
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
            "db-migrations/refdata/V1__create_refdata_schema.sql");
    }

    @Autowired private UnderwritingApi underwritingApi;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;

    private static final AtomicInteger SEQ = new AtomicInteger(3000);

    private UUID tenantId;
    private ProductFixture individualProduct;

    private record ProductFixture(UUID productId, UUID productVersionId) {}

    @BeforeEach
    void freshTenant() {
        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        individualProduct = publishProduct("IND-" + SEQ.incrementAndGet(), ProductCategory.TERM_LIFE);
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private UUID person(String name) {
        return partyApi.registerIndividual(name, LocalDate.of(1985, 6, 15),
            "+2557" + String.format("%08d", SEQ.incrementAndGet()), null, "test").partyId();
    }

    private ProductFixture groupProduct(String code) {
        return publishProduct(code, ProductCategory.GROUP_LIFE);
    }

    private ProductFixture publishProduct(String code, ProductCategory category) {
        ProductSummaryView product = productApi.createProduct(code, "Test " + code, category, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)), null, ANY_FILING, "actuary");
        return new ProductFixture(product.productId(),
            productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId());
    }

    /** A flat proposal for the given lives, at 5,000,000 each and 1,200,000 a year. */
    private GroupProposal flatProposal(List<UUID> lives) {
        return new GroupProposal(GroupBenefitBasis.FLAT, new BigDecimal("5000000.00"), null, null, "TZS",
            List.of(),
            lives.stream().map(id -> new GroupProposal.MemberLine(id, null, null)).toList(),
            new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now(), null);
    }

    @Test
    void aGroupCaseCarriesItsTermsAndItsOpeningSchedule() {
        TenantContext.set(tenantId);
        UUID employer = person("ABC Company");
        UUID first = person("Juma Employee");
        UUID second = person("Asha Employee");
        ProductFixture product = groupProduct("GRP-PROPOSAL-01");

        UnderwritingCaseView opened = underwritingApi.openCase(employer, product.productId(),
            product.productVersionId(), null, flatProposal(List.of(first, second)), "underwriter1");

        UnderwritingCaseView read = underwritingApi.getCase(opened.caseId());
        assertThat(read.groupScheme()).isTrue();
        assertThat(read.groupProposal().benefitBasis()).isEqualTo(GroupBenefitBasis.FLAT);
        assertThat(read.groupProposal().flatBenefitAmount()).isEqualByComparingTo(new BigDecimal("5000000.00"));
        assertThat(read.groupProposal().openingSchedule()).hasSize(2);
        assertThat(read.groupProposal().premiumAmount()).isEqualByComparingTo(new BigDecimal("1200000.00"));

        // NULL sum assured, on purpose: valuing the schedule needs GroupBenefitCalculator,
        // which lives in policy and which underwriting must not re-implement. The figure
        // appears when policy derives it at issuance, in the one place that owns it.
        assertThat(read.sumAssuredAmount()).isNull();
        assertThat(read.lifeAssuredPartyId())
            .as("an employer is not a life assured; the lives are the schedule")
            .isNull();
    }

    @Test
    void anIndividualCaseCarriesNoGroupProposal() {
        TenantContext.set(tenantId);
        UnderwritingCaseView opened = underwritingApi.openCase(person("Solo Applicant"),
            individualProduct.productId(), individualProduct.productVersionId(),
            new BigDecimal("2000000"), "TZS", null, "underwriter1");

        assertThat(underwritingApi.getCase(opened.caseId()).groupScheme()).isFalse();
        assertThat(underwritingApi.getCase(opened.caseId()).groupProposal()).isNull();
    }

    @Test
    void aGroupProposalMustNameAtLeastOneLife() {
        // Same rule issueGroupScheme already enforces, moved to where the proposal is taken:
        // a scheme's sum assured IS the total of its schedule, so an empty one is a contract
        // insuring nobody for nothing. Catching it here means it is refused before an
        // underwriter spends time on the case.
        TenantContext.set(tenantId);
        UUID employer = person("ABC Company");
        ProductFixture product = groupProduct("GRP-PROPOSAL-EMPTY");

        assertThatThrownBy(() -> underwritingApi.openCase(employer, product.productId(),
                product.productVersionId(), null, flatProposal(List.of()), "underwriter1"))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessageContaining("at least one life");
    }

    @Test
    void aGroupProposalMustBeAgainstAGroupProduct() {
        // The mirror of issueGroupScheme's own check ("A group scheme needs a GROUP_LIFE
        // product"), applied at proposal time so a case cannot be decided into an issuance
        // that will then refuse it.
        TenantContext.set(tenantId);
        assertThatThrownBy(() -> underwritingApi.openCase(person("ABC Company"),
                individualProduct.productId(), individualProduct.productVersionId(), null,
                flatProposal(List.of(person("A Life"))), "underwriter1"))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessageContaining("GROUP_LIFE");
    }

    @Test
    void aGroupCaseGetsNoEngineRecommendation() {
        // Decided, not a gap. RiskProfile is age band x sum assured band plus assessment
        // scores; the age band it would resolve is the EMPLOYER's, which insures nobody, and
        // group underwriting looks at scheme size, industry and claims experience — none of
        // which this platform holds.
        //
        // A case with no recommendation is already legal: decide()'s own rule is that "an
        // ABSENT recommendation is not a disagreement", so an underwriter settles a scheme
        // without a senior being demanded for departing from advice never given.
        TenantContext.set(tenantId);
        UUID employer = person("ABC Company");
        ProductFixture product = groupProduct("GRP-PROPOSAL-NOENGINE");
        UnderwritingCaseView opened = underwritingApi.openCase(employer, product.productId(),
            product.productVersionId(), null, flatProposal(List.of(person("A Life"))), "underwriter1");

        UnderwritingCaseView assessed = underwritingApi.submitAssessment(opened.caseId(),
            AssessmentType.FINANCIAL, "Employer accounts reviewed", new BigDecimal("10"), "uw");

        assertThat(assessed.recommendationOutcome()).isNull();
        assertThat(assessed.recommendationLoadingPercent()).isNull();
        assertThat(assessed.ratingMultiplier())
            .as("a company has no age band and no sum assured band; rating one would be fiction")
            .isNull();
    }
}
