package tz.co.nlolo.lifeplatform.unitlinked;

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
import tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingValidationException;
import tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * Selling a unit-linked policy (spec §4): the customer's split, premium and cover, checked against the version and
 * refused in its words; accepted by a second person; issued at exactly what was chosen; and its fund split written
 * once at issue.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, FuneralTestFixtures.class})
class UnitLinkedSaleIntegrationTest {

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
            UnitLinkedTestMigrations.ALL);
    }

    @Autowired private UnitLinkedTestFixtures fixtures;
    @Autowired private FuneralTestFixtures people;
    @Autowired private UnderwritingApi underwritingApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private UnitLinkedApi unitLinkedApi;

    private static UnitLinkedChoice choice(List<UnitLinkedChoice.Split> split, String premium, String frequency, String cover) {
        return new UnitLinkedChoice(split, new BigDecimal(premium), frequency, new BigDecimal(cover));
    }

    private static final List<UnitLinkedChoice.Split> SIXTY_FORTY =
        List.of(new UnitLinkedChoice.Split("EQ1", 60), new UnitLinkedChoice.Split("BD1", 40));

    @Test
    void aSplitNotTotalling100IsRefused() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandard(tenant);
        UUID life = fixtures.person(tenant, 35);
        assertThatThrownBy(() -> fixtures.openCase(tenant, product, life,
                choice(List.of(new UnitLinkedChoice.Split("EQ1", 60), new UnitLinkedChoice.Split("BD1", 30)),
                    "100000.00", "MONTHLY", "6000000.00")))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessage("The fund split totals 90%; it must total 100%");
    }

    @Test
    void aFundTheProductDoesNotOfferIsRefused() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandard(tenant);
        fixtures.fund(tenant, "MM1");
        UUID life = fixtures.person(tenant, 35);
        assertThatThrownBy(() -> fixtures.openCase(tenant, product, life,
                choice(List.of(new UnitLinkedChoice.Split("MM1", 100)), "100000.00", "MONTHLY", "6000000.00")))
            .hasMessageContaining("Fund MM1 is not offered by this product");
    }

    @Test
    void aPremiumBelowTheMinimumOrAtAnUnofferedFrequencyIsRefused() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandard(tenant);
        UUID life = fixtures.person(tenant, 35);
        assertThatThrownBy(() -> fixtures.openCase(tenant, product, life, choice(SIXTY_FORTY, "40000.00", "MONTHLY", "3000000.00")))
            .hasMessage("The MONTHLY premium is at least 50,000.00 TZS");
        assertThatThrownBy(() -> fixtures.openCase(tenant, product, life, choice(SIXTY_FORTY, "1200000.00", "ANNUALLY", "6000000.00")))
            .hasMessage("This product does not take ANNUALLY premiums");
    }

    @Test
    void aSumAssuredOutsideTheMultiplesIsRefusedWithTheRange() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandard(tenant);
        UUID life = fixtures.person(tenant, 35);
        assertThatThrownBy(() -> fixtures.openCase(tenant, product, life, choice(SIXTY_FORTY, "100000.00", "MONTHLY", "2000000.00")))
            .hasMessage("The sum assured must be between 6,000,000.00 and 24,000,000.00 TZS (5x to 20x the annual premium of 1,200,000.00)");
    }

    @Test
    void aLoadedDecisionIsRefused() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandard(tenant);
        UUID life = fixtures.person(tenant, 35);
        UUID caseId = fixtures.openCase(tenant, product, life, UnitLinkedTestFixtures.standardChoice());
        assertThatThrownBy(() -> people.decide(tenant, caseId, DecisionOutcome.LOADED, new BigDecimal("25")))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessageContaining("premium is the customer's own choice");
    }

    @Test
    void acceptingIssuesAtTheChosenPremiumAndCoverAndWritesTheSplitOnce() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandard(tenant);
        UUID life = fixtures.person(tenant, 35);

        String policyNumber = fixtures.sell(tenant, product, life, UnitLinkedTestFixtures.standardChoice());

        PolicyView policy = asTenant(tenant, () -> policyApi.getPolicy(policyNumber));
        assertThat(policy.productCategory()).isEqualTo("UNIT_LINKED");
        assertThat(policy.premiumAmount()).isEqualByComparingTo("100000.00"); // the customer's choice, never quoted
        assertThat(policy.premiumFrequency()).isEqualTo("MONTHLY");
        assertThat(policy.sumAssuredAmount()).isEqualByComparingTo("6000000.00");
        // Written after the issue commits, by unitlinked hearing policy.PolicyIssued -- on this thread, AFTER_COMMIT.
        assertThat(asTenant(tenant, () -> unitLinkedApi.allocationOf(policyNumber)))
            .containsExactly(new UnitLinkedApi.AllocationView("BD1", 40), new UnitLinkedApi.AllocationView("EQ1", 60));
    }

    @Test
    void theChoiceCanBeReadAndReplacedUntilDecided() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandard(tenant);
        UUID life = fixtures.person(tenant, 35);
        UUID caseId = fixtures.openCase(tenant, product, life, UnitLinkedTestFixtures.standardChoice());

        asTenant(tenant, () -> underwritingApi.recordUnitLinkedChoice(caseId,
            choice(List.of(new UnitLinkedChoice.Split("EQ1", 100)), "100000.00", "MONTHLY", "6000000.00"), "staff-opener"));
        assertThat(asTenant(tenant, () -> underwritingApi.unitLinkedChoice(caseId)).orElseThrow().split())
            .containsExactly(new UnitLinkedChoice.Split("EQ1", 100));

        people.decide(tenant, caseId, DecisionOutcome.ACCEPT, null);
        assertThatThrownBy(() -> asTenant(tenant, () -> underwritingApi.recordUnitLinkedChoice(caseId,
                UnitLinkedTestFixtures.standardChoice(), "staff-opener")))
            .hasMessageContaining(caseId.toString());
    }
}
