package tz.co.nlolo.lifeplatform.annuity;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.AnnuityTiming;
import tz.co.nlolo.lifeplatform.underwriting.api.AnnuityChoice;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingValidationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * An annuity through underwriting (product step 5, Task 2): the choice, the light path -- proof of
 * age instead of an assessment -- the re-price at acceptance, and automatic issuance at the purchase
 * price, single premium, no term.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AnnuityTestFixtures.class)
class AnnuityCaseIntegrationTest {

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
            AnnuityTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();

    @Autowired private AnnuityTestFixtures fixtures;
    @Autowired private UnderwritingApi underwritingApi;
    @Autowired private PolicyApi policyApi;

    private static final AnnuityChoice LIFE_MONTHLY = AnnuityChoice.of("LIFE-0G", "MONTHLY", null);

    private UnderwritingApi.DecisionInput accept(boolean ageEvidence) {
        return new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Age proven by passport", ageEvidence);
    }

    private List<PolicyView> policiesOf(UUID party) {
        return asTenant(TENANT, () -> policyApi.searchPolicies(party, null, null, null, null, PageRequest.of(0, 10)).getContent());
    }

    @Test
    void anAnnuityCaseIsAcceptedOnProofOfAgeWithNoAssessmentAndIssuesAtThePurchasePrice() {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.everyForm());
        UUID annuitant = fixtures.person(TENANT, 61, Sex.FEMALE);
        UUID caseId = fixtures.openCase(TENANT, product, annuitant, "50000000.00", LIFE_MONTHLY);

        var decided = asTenant(TENANT, () -> underwritingApi.decide(caseId, accept(true), "senior-two", true));
        assertThat(decided.status()).isEqualTo(UnderwritingCaseStatus.DECIDED);
        assertThat(asTenant(TENANT, () -> underwritingApi.annuityChoice(caseId)).orElseThrow().ageEvidenceConfirmedBy())
            .isEqualTo("senior-two");

        assertThat(policiesOf(annuitant)).singleElement().satisfies(p -> {
            assertThat(p.status()).isEqualTo(PolicyStatus.PROPOSED);
            assertThat(p.premiumFrequency()).isEqualTo("SINGLE");
            assertThat(p.premiumAmount()).isEqualByComparingTo("50000000.00");
            assertThat(p.sumAssuredAmount()).isEqualByComparingTo("50000000.00");
            assertThat(p.maturityDate()).isNull();
            assertThat(p.policyTermMonths()).isNull();
        });
    }

    @Test
    void withoutProofOfAgeAnAnnuityCannotBeAccepted() {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.everyForm());
        UUID caseId = fixtures.openCase(TENANT, product, fixtures.person(TENANT, 61, null), "1000000.00", LIFE_MONTHLY);
        assertThatThrownBy(() -> asTenant(TENANT, () -> underwritingApi.decide(caseId, accept(false), "senior-two", true)))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessage("An annuity is accepted only once proof of age is confirmed");
    }

    @Test
    void anAnnuityCaseWithoutAChoiceCannotBeAccepted() {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.everyForm());
        UUID caseId = fixtures.openCase(TENANT, product, fixtures.person(TENANT, 61, null), "1000000.00", null);
        assertThatThrownBy(() -> asTenant(TENANT, () -> underwritingApi.decide(caseId, accept(true), "senior-two", true)))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessage("An annuity case must record the chosen form and frequency before it is decided");
    }

    @Test
    void acceptanceRepricesAndRefusesWhatCannotBePriced() {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.everyForm());
        UUID caseId = fixtures.openCase(TENANT, product, fixtures.person(TENANT, 61, null), "1000000.00",
            AnnuityChoice.of("LIFE-BS", "MONTHLY", null));
        assertThatThrownBy(() -> asTenant(TENANT, () -> underwritingApi.decide(caseId, accept(true), "senior-two", true)))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessage("Form LIFE-BS is priced by sex and the annuitant's sex is not recorded");
    }

    @Test
    void anAnnuityIsAcceptedOrDeclinedNeverLoadedOrPostponed() {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.everyForm());
        UUID caseId = fixtures.openCase(TENANT, product, fixtures.person(TENANT, 61, null), "1000000.00", LIFE_MONTHLY);
        assertThatThrownBy(() -> asTenant(TENANT, () -> underwritingApi.decide(caseId,
                new UnderwritingApi.DecisionInput(DecisionOutcome.LOADED, new BigDecimal("25"), "Loaded", true), "senior-two", true)))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessage("An annuity is accepted or declined; it is not loaded or postponed");
        // Declining needs neither the choice nor proof of age.
        var declined = asTenant(TENANT, () -> underwritingApi.decide(caseId,
            new UnderwritingApi.DecisionInput(DecisionOutcome.DECLINED, null, "Applicant withdrew"), "senior-two", true));
        assertThat(declined.decisionOutcome()).isEqualTo(DecisionOutcome.DECLINED);
    }

    @Test
    void theChoiceMustBeOneTheVersionOffers() {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.everyForm());
        UUID annuitant = fixtures.person(TENANT, 61, null);
        UUID caseId = fixtures.openCase(TENANT, product, annuitant, "1000000.00", null);
        assertThatThrownBy(() -> asTenant(TENANT, () -> underwritingApi.recordAnnuityChoice(caseId,
                AnnuityChoice.of("NOPE", "MONTHLY", null), "staff-opener")))
            .isInstanceOf(UnderwritingValidationException.class).hasMessage("This version does not offer form NOPE");
        assertThatThrownBy(() -> asTenant(TENANT, () -> underwritingApi.recordAnnuityChoice(caseId,
                AnnuityChoice.of("LIFE-0G", "QUARTERLY", null), "staff-opener")))
            .isInstanceOf(UnderwritingValidationException.class).hasMessage("This version does not offer QUARTERLY payments");
        assertThatThrownBy(() -> asTenant(TENANT, () -> underwritingApi.recordAnnuityChoice(caseId,
                AnnuityChoice.of("JOINT-50", "MONTHLY", null), "staff-opener")))
            .isInstanceOf(UnderwritingValidationException.class).hasMessage("Form JOINT-50 is joint-life: name the joint life");
        assertThatThrownBy(() -> asTenant(TENANT, () -> underwritingApi.recordAnnuityChoice(caseId,
                AnnuityChoice.of("LIFE-0G", "MONTHLY", fixtures.person(TENANT, 58, null)), "staff-opener")))
            .isInstanceOf(UnderwritingValidationException.class).hasMessage("Form LIFE-0G is single-life: it takes no joint life");
        assertThatThrownBy(() -> asTenant(TENANT, () -> underwritingApi.recordAnnuityChoice(caseId,
                AnnuityChoice.of("JOINT-50", "MONTHLY", annuitant), "staff-opener")))
            .isInstanceOf(UnderwritingValidationException.class).hasMessage("The joint life must be someone other than the annuitant");
    }

    @Test
    void aJointCaseIsPricedOnBothLivesAtAcceptance() {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.everyForm());
        UUID annuitant = fixtures.person(TENANT, 62, null);
        UUID joint = fixtures.person(TENANT, 57, null);
        UUID caseId = fixtures.openCase(TENANT, product, annuitant, "1000000.00", AnnuityChoice.of("JOINT-50", "MONTHLY", joint));
        asTenant(TENANT, () -> underwritingApi.decide(caseId, accept(true), "senior-two", true));
        assertThat(policiesOf(annuitant)).hasSize(1);
    }

    /** The light path must not have weakened the evidence rule for every other product. */
    @Test
    void anOrdinaryCaseRecordsNoChoiceAndStillNeedsAnAssessment() {
        var wholeLife = fixtures.publishOrdinary(TENANT);
        UUID ordinaryCase = fixtures.openCase(TENANT, wholeLife, fixtures.person(TENANT, 61, null), "1000000.00", null);
        assertThatThrownBy(() -> asTenant(TENANT, () -> underwritingApi.decide(ordinaryCase, accept(true), "senior-two", true)))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessageContaining("has no assessment");
        assertThatThrownBy(() -> asTenant(TENANT, () -> underwritingApi.recordAnnuityChoice(ordinaryCase, LIFE_MONTHLY, "staff-opener")))
            .isInstanceOf(UnderwritingValidationException.class).hasMessage("Only an annuity case records an annuity choice");
        assertThat(asTenant(TENANT, () -> underwritingApi.annuityChoice(ordinaryCase))).isEmpty();
    }

    /** The manual path enforces the same shape the automatic one builds. */
    @Test
    void anAnnuityIsIssuedOnlyAsASinglePremiumOfItsPriceWithNoTerm() {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.plan(AnnuityTiming.ADVANCE, AnnuityTestFixtures.lifeOnly()));
        UUID annuitant = fixtures.person(TENANT, 61, null);
        PolicyApi.IssueRequest monthly = new PolicyApi.IssueRequest(annuitant, product.productId(), product.versionId(),
            new BigDecimal("1000000.00"), "TZS", new BigDecimal("1000000.00"), "TZS", "MONTHLY", null, List.of(),
            "migration", null, null, null, null);
        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.issuePolicy(UUID.randomUUID(), monthly, "staff-one")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("An annuity is bought with a single premium equal to its purchase price");
        PolicyApi.IssueRequest withTerm = new PolicyApi.IssueRequest(annuitant, product.productId(), product.versionId(),
            new BigDecimal("1000000.00"), "TZS", new BigDecimal("1000000.00"), "TZS", "SINGLE", null, List.of(),
            "migration", AnnuityTestFixtures.TODAY, 120, null, null);
        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.issuePolicy(UUID.randomUUID(), withTerm, "staff-one")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("An annuity has no term; it pays for life");
    }
}
