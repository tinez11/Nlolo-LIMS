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
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationApi;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.underwriting.api.AnnuityChoice;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingValidationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * A deferred annuity through underwriting (product step 5 D2, Task 2): a retirement age instead of a
 * form, proof of age with the date of birth and sex it saw, no pricing at acceptance, and automatic
 * issuance as an account policy paying contributions to the target date with no policy term.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AnnuityTestFixtures.class)
class DeferredAnnuityCaseIntegrationTest {

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
    private static final LocalDate BORN = LocalDate.of(1980, 6, 15);
    private static final LocalDate COMMENCES = LocalDate.of(2026, 11, 1);

    @Autowired private AnnuityTestFixtures fixtures;
    @Autowired private UnderwritingApi underwritingApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private AccumulationApi accumulationApi;

    private UnderwritingApi.DecisionInput accept(boolean ageEvidence) {
        return new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Age proven by passport", ageEvidence);
    }

    private UUID openDeferredCase(UUID saver, Integer retirementAge) {
        return fixtures.openDeferredCase(TENANT, fixtures.publishDeferred(TENANT, false), saver, "200000.00", "MONTHLY",
            COMMENCES, retirementAge);
    }

    private List<PolicyView> policiesOf(UUID party) {
        return asTenant(TENANT, () -> policyApi.searchPolicies(party, null, null, null, null, PageRequest.of(0, 10)).getContent());
    }

    @Test
    void aDeferredCaseRecordsARetirementAgeAndItsTargetDate() {
        UUID caseId = openDeferredCase(fixtures.personBorn(TENANT, BORN, Sex.FEMALE), 60);
        var choice = asTenant(TENANT, () -> underwritingApi.deferredAnnuityChoice(caseId)).orElseThrow();
        assertThat(choice.retirementAge()).isEqualTo(60);
        assertThat(choice.targetDate()).isEqualTo(LocalDate.of(2040, 6, 15));
        assertThat(choice.ageEvidenceConfirmedBy()).isNull();
    }

    @Test
    void aRetirementAgeOutsideTheWindowIsRefused() {
        UUID caseId = openDeferredCase(fixtures.personBorn(TENANT, BORN, Sex.FEMALE), null);
        assertThatThrownBy(() -> asTenant(TENANT, () -> underwritingApi.recordDeferredAnnuityChoice(caseId, 71, "staff-opener")))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessage("The retirement age must be within the vesting window, 55 to 70");
        assertThatThrownBy(() -> asTenant(TENANT, () -> underwritingApi.recordDeferredAnnuityChoice(caseId, 54, "staff-opener")))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessage("The retirement age must be within the vesting window, 55 to 70");
    }

    @Test
    void aRetirementAgeAlreadyReachedIsRefused() {
        // Fifty-five last birthday, two days ago at most a year back: 55 is in the window but behind them.
        UUID saver = fixtures.personBorn(TENANT, AnnuityTestFixtures.TODAY.minusYears(55).minusDays(2), null);
        UUID caseId = openDeferredCase(saver, null);
        assertThatThrownBy(() -> asTenant(TENANT, () -> underwritingApi.recordDeferredAnnuityChoice(caseId, 55, "staff-opener")))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessage("A retirement age of 55 has already been reached");
    }

    @Test
    void aDeferredCaseTakesNoFormChoice() {
        UUID caseId = openDeferredCase(fixtures.personBorn(TENANT, BORN, Sex.FEMALE), 60);
        assertThatThrownBy(() -> asTenant(TENANT, () -> underwritingApi.recordAnnuityChoice(caseId,
                AnnuityChoice.of("LIFE-0G", "MONTHLY", null), "staff-opener")))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessage("A deferred annuity records a retirement age; its form is chosen when it vests");
    }

    @Test
    void anImmediateAnnuityCaseRecordsNoRetirementAge() {
        var immediate = fixtures.publish(TENANT, AnnuityTestFixtures.everyForm());
        UUID caseId = fixtures.openCase(TENANT, immediate, fixtures.person(TENANT, 61, null), "1000000.00", null);
        assertThatThrownBy(() -> asTenant(TENANT, () -> underwritingApi.recordDeferredAnnuityChoice(caseId, 65, "staff-opener")))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessage("Only a deferred annuity case records a retirement age");
        assertThat(asTenant(TENANT, () -> underwritingApi.deferredAnnuityChoice(caseId))).isEmpty();
    }

    @Test
    void withoutARetirementAgeADeferredCaseCannotBeAccepted() {
        UUID caseId = openDeferredCase(fixtures.personBorn(TENANT, BORN, Sex.FEMALE), null);
        assertThatThrownBy(() -> asTenant(TENANT, () -> underwritingApi.decide(caseId, accept(true), "senior-two", true)))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessage("A deferred annuity case must record the retirement age before it is decided");
    }

    @Test
    void withoutAgeEvidenceADeferredAnnuityCannotBeAccepted() {
        UUID caseId = openDeferredCase(fixtures.personBorn(TENANT, BORN, Sex.FEMALE), 60);
        assertThatThrownBy(() -> asTenant(TENANT, () -> underwritingApi.decide(caseId, accept(false), "senior-two", true)))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessage("An annuity is accepted only once proof of age is confirmed");
    }

    @Test
    void acceptanceStoresTheConfirmedDateOfBirthAndSex() {
        UUID caseId = openDeferredCase(fixtures.personBorn(TENANT, BORN, Sex.FEMALE), 60);
        asTenant(TENANT, () -> underwritingApi.decide(caseId, accept(true), "senior-two", true));
        var choice = asTenant(TENANT, () -> underwritingApi.deferredAnnuityChoice(caseId)).orElseThrow();
        assertThat(choice.ageEvidenceConfirmedBy()).isEqualTo("senior-two");
        assertThat(choice.ageEvidenceConfirmedAt()).isNotNull();
        assertThat(choice.confirmedDateOfBirth()).isEqualTo(BORN);
        assertThat(choice.confirmedSex()).isEqualTo("FEMALE");
    }

    @Test
    void acceptanceIssuesAnAccountPolicyPayingToTheTargetDate() {
        UUID saver = fixtures.personBorn(TENANT, BORN, Sex.FEMALE);
        UUID caseId = openDeferredCase(saver, 60);
        asTenant(TENANT, () -> underwritingApi.decide(caseId, accept(true), "senior-two", true));

        assertThat(policiesOf(saver)).singleElement().satisfies(p -> {
            assertThat(p.premiumAmount()).isEqualByComparingTo("200000.00");
            assertThat(p.sumAssuredAmount()).isEqualByComparingTo("200000.00");
            assertThat(p.premiumFrequency()).isEqualTo("MONTHLY");
            assertThat(p.policyTermMonths()).isNull();
            assertThat(p.maturityDate()).isNull();
            // 2026-11-01 to 2040-06-15: 163 whole months.
            assertThat(p.premiumPayingTermMonths()).isEqualTo(163);
            assertThat(asTenant(TENANT, () -> accumulationApi.findAccount(p.policyNumber()))).isPresent();
        });
    }

    /** The manual path enforces the same shape the automatic one builds. */
    @Test
    void aDeferredAnnuityIsIssuedWithNoPolicyTermAndAPayingTerm() {
        var product = fixtures.publishDeferred(TENANT, false);
        UUID saver = fixtures.personBorn(TENANT, BORN, null);
        PolicyApi.IssueRequest unequal = new PolicyApi.IssueRequest(saver, product.productId(), product.versionId(),
            new BigDecimal("1000000.00"), "TZS", new BigDecimal("200000.00"), "TZS", "MONTHLY", null, List.of(),
            "migration", COMMENCES, null, 120, null);
        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.issuePolicy(UUID.randomUUID(), unequal, "staff-one")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A deferred annuity's sum assured is its contribution: the two must be equal");
        PolicyApi.IssueRequest withTerm = new PolicyApi.IssueRequest(saver, product.productId(), product.versionId(),
            new BigDecimal("200000.00"), "TZS", new BigDecimal("200000.00"), "TZS", "MONTHLY", null, List.of(),
            "migration", COMMENCES, 120, 120, null);
        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.issuePolicy(UUID.randomUUID(), withTerm, "staff-one")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A deferred annuity has no policy term: it saves to its vesting date and then pays for life");
        PolicyApi.IssueRequest noPayingTerm = new PolicyApi.IssueRequest(saver, product.productId(), product.versionId(),
            new BigDecimal("200000.00"), "TZS", new BigDecimal("200000.00"), "TZS", "MONTHLY", null, List.of(),
            "migration", COMMENCES, null, null, null);
        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.issuePolicy(UUID.randomUUID(), noPayingTerm, "staff-one")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A deferred annuity pays contributions to its vesting date: state the premium-paying term");
    }
}
