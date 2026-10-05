package tz.co.nlolo.lifeplatform.funeral;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.FuneralApplication;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingValidationException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.dependant;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.family;

/** A funeral case records its plan and dependants, priced by the one funeral quote; R2 and R3 hold. */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class})
class FuneralCaseIntegrationTest {

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
            FuneralTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();

    @Autowired private FuneralTestFixtures fixtures;
    @Autowired private AnnuityTestFixtures annuityFixtures;
    @Autowired private UnderwritingApi underwritingApi;

    private void refused(Runnable r, String message) {
        assertThatThrownBy(r::run).isInstanceOf(UnderwritingValidationException.class).hasMessage(message);
    }

    @Test
    void recordsThePlanAndDependantsAndQuotesTheFamily() {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        UUID caseId = fixtures.openFamilyCase(TENANT, product, juma, family());

        FuneralApplication application = asTenant(TENANT, () -> underwritingApi.funeralApplication(caseId)).orElseThrow();
        assertThat(application.planCode()).isEqualTo("B");
        assertThat(application.dependants()).containsExactlyElementsOf(family());
        assertThat(application.quote().lines()).hasSize(5);
        assertThat(application.quote().lines().get(0).role()).isEqualTo(FuneralRole.MAIN_MEMBER);
        assertThat(application.quote().lines().get(0).age()).isEqualTo(40);
        // 60,000 + 60,000 + 3 x 6,000 = 138,000 a year; x 1.05 / 12
        assertThat(application.quote().totalYearlyPremium()).isEqualByComparingTo("138000");
        assertThat(application.quote().instalment()).isEqualByComparingTo("12075.00");
    }

    @Test
    void recordingAgainReplacesTheFamily() {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        UUID caseId = fixtures.openFamilyCase(TENANT, product, juma, family());
        asTenant(TENANT, () -> underwritingApi.recordFuneralApplication(caseId, "B",
            List.of(dependant(FuneralRole.SPOUSE, "Asha", 38)), "staff-opener"));

        assertThat(asTenant(TENANT, () -> underwritingApi.funeralApplication(caseId)).orElseThrow().dependants()).hasSize(1);
    }

    @Test
    void aSumAssuredThatIsNotTheMainMembersBenefitIsRefusedNamingTheRightFigure() {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        UUID caseId = fixtures.openCase(TENANT, product, juma, "1500000.00", "MONTHLY");

        refused(() -> asTenant(TENANT, () -> underwritingApi.recordFuneralApplication(caseId, "B", family(), "staff-opener")),
            "The sum assured on a funeral case is the main member's benefit on plan B: 2,000,000.00 TZS, not 1,500,000.00");
    }

    @Test
    void aChildTooOldForEntryIsRefused() {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 45, Sex.MALE);
        UUID caseId = fixtures.openCase(TENANT, product, juma, "2000000.00", "MONTHLY");

        refused(() -> asTenant(TENANT, () -> underwritingApi.recordFuneralApplication(caseId, "B",
                List.of(dependant(FuneralRole.CHILD, "Neema", 21)), "staff-opener")),
            "Neema: a child must be 0 to 20 at entry, not 21");
    }

    @Test
    void aSecondSpouseIsRefused() {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        UUID caseId = fixtures.openCase(TENANT, product, juma, "2000000.00", "MONTHLY");
        List<FuneralApplication.Life> twoWives = new ArrayList<>(family());
        twoWives.add(dependant(FuneralRole.SPOUSE, "Mwanaisha", 35));

        refused(() -> asTenant(TENANT, () -> underwritingApi.recordFuneralApplication(caseId, "B", twoWives, "staff-opener")),
            "At most 1 spouse may be covered");
    }

    @Test
    void theMainMemberIsNotADependant() {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        UUID caseId = fixtures.openCase(TENANT, product, juma, "2000000.00", "MONTHLY");

        refused(() -> asTenant(TENANT, () -> underwritingApi.recordFuneralApplication(caseId, "B",
                List.of(dependant(FuneralRole.MAIN_MEMBER, "Juma again", 40)), "staff-opener")),
            "Each dependant needs a role other than main member: the main member is the life assured");
    }

    @Test
    void aLoadedDecisionIsRefused() {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        UUID caseId = fixtures.openFamilyCase(TENANT, product, juma, family());

        refused(() -> fixtures.decide(TENANT, caseId, DecisionOutcome.LOADED, new BigDecimal("25")),
            "A funeral plan is priced by its premium table; accept, decline or postpone it");
    }

    @Test
    void acceptanceWithoutAnApplicationIsRefused() {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        UUID caseId = fixtures.openCase(TENANT, product, juma, "2000000.00", "MONTHLY");

        refused(() -> fixtures.decide(TENANT, caseId, DecisionOutcome.ACCEPT, null),
            "A funeral case must record its plan and lives before it is accepted");
    }

    @Test
    void aDeclineNeedsNoApplication() {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        UUID caseId = fixtures.openCase(TENANT, product, juma, "2000000.00", "MONTHLY");

        fixtures.decide(TENANT, caseId, DecisionOutcome.DECLINED, null);
        assertThat(asTenant(TENANT, () -> underwritingApi.getCase(caseId)).decisionOutcome()).isEqualTo(DecisionOutcome.DECLINED);
    }

    @Test
    void anOrdinaryCaseHasNoFuneralApplicationAndCannotRecordOne() {
        var ordinary = annuityFixtures.publishOrdinary(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        UUID caseId = asTenant(TENANT, () -> underwritingApi.openCase(juma, ordinary.productId(), ordinary.versionId(),
            new BigDecimal("2000000.00"), "TZS", null, tz.co.nlolo.lifeplatform.underwriting.api.ProposalDetails.selfInsured(),
            "staff-opener").caseId());

        assertThat(asTenant(TENANT, () -> underwritingApi.funeralApplication(caseId))).isEmpty();
        refused(() -> asTenant(TENANT, () -> underwritingApi.recordFuneralApplication(caseId, "B", family(), "staff-opener")),
            "Only a funeral plan case records a funeral application");
    }
}
