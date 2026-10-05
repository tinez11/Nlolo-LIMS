package tz.co.nlolo.lifeplatform.funeral;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.product.FuneralPlans;
import tz.co.nlolo.lifeplatform.product.api.AccumulationPlan;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPlan;
import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.BonusPlan;
import tz.co.nlolo.lifeplatform.product.api.CashValuePlan;
import tz.co.nlolo.lifeplatform.product.api.DepositPlan;
import tz.co.nlolo.lifeplatform.product.api.EligibilityBounds;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.FrequencyLoading;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlan;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;
import tz.co.nlolo.lifeplatform.product.api.InvalidProductVersionException;
import tz.co.nlolo.lifeplatform.product.api.PayoutPlan;
import tz.co.nlolo.lifeplatform.product.api.PayoutTerms;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.FuneralLifeInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuoteInput;
import tz.co.nlolo.lifeplatform.product.api.PremiumFrequency;
import tz.co.nlolo.lifeplatform.product.domain.FuneralQuoter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/** A FUNERAL version's terms written at publish and read back as published; a refusal writes nothing. */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(FuneralTestFixtures.class)
class FuneralProductIntegrationTest {

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

    @Autowired private FuneralTestFixtures fixtures;
    @Autowired private ProductApi productApi;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void publishesAFamilyPlanAndReadsItBack() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishFamilia(tenant);

        FuneralPlan read = asTenant(tenant, () -> productApi.resolveFuneralPlan(product.versionId()));
        FuneralPlan published = FuneralPlans.familia();
        assertThat(read.funeral()).isTrue();
        assertThat(read.plans()).containsExactlyElementsOf(published.plans());
        assertThat(read.benefits()).containsExactlyInAnyOrderElementsOf(published.benefits());
        assertThat(read.premiums()).containsExactlyInAnyOrderElementsOf(published.premiums());
        assertThat(read.roles()).containsExactlyInAnyOrderElementsOf(published.roles());
        assertThat(read.maxPricedAge()).isEqualTo(100);
        assertThat(read.waitingPeriodMonths()).isEqualTo(6);
        assertThat(read.accidentWaivesWaiting()).isTrue();
        assertThat(read.dependantClaimPayee()).isEqualTo(published.dependantClaimPayee());
        assertThat(read.onMainMemberDeath()).isEqualTo(published.onMainMemberDeath());
        assertThat(read.freeCoverToPaidDate()).isTrue();
        assertThat(read.yearlyPremium("B", FuneralRole.MAIN_MEMBER, 40)).contains(new BigDecimal("60000.00"));
    }

    @Test
    void quotesThroughTheApiAsThePureQuoterDoes() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishFamilia(tenant);
        LocalDate on = LocalDate.of(2026, 10, 4);
        var input = new FuneralQuoteInput("B", PremiumFrequency.MONTHLY, on, List.of(
            new FuneralLifeInput(FuneralRole.MAIN_MEMBER, "Juma", on.minusYears(40), false),
            new FuneralLifeInput(FuneralRole.SPOUSE, "Asha", on.minusYears(38), false),
            new FuneralLifeInput(FuneralRole.CHILD, "Neema", on.minusYears(10), false)));

        var viaApi = asTenant(tenant, () -> productApi.quoteFuneral(product.versionId(), input));
        var pure = FuneralQuoter.quote(FuneralPlans.familia(), FuneralTestFixtures.LOADING, input);

        assertThat(viaApi).isEqualTo(pure);
        // 126,000 x 1.05 / 12
        assertThat(viaApi.instalment()).isEqualByComparingTo("11025.00");
        assertThat(asTenant(tenant, () -> productApi.funeralYearlyPremium(product.versionId(), "A", FuneralRole.PARENT, 70)))
            .isEqualByComparingTo("45000");
    }

    @Test
    void anAgeGapIsRefusedAndNoVersionIsWritten() {
        UUID tenant = UUID.randomUUID();
        var rows = FuneralPlans.premiums().stream()
            .filter(p -> !(p.planCode().equals("B") && p.role() == FuneralRole.PARENT && p.ageFrom() == 66)).toList();
        FuneralPlan gapped = FuneralPlans.of(FuneralPlans.plans(), FuneralPlans.benefits(), rows, FuneralPlans.roles());

        assertThatThrownBy(() -> fixtures.publish(tenant, gapped))
            .isInstanceOf(InvalidProductVersionException.class)
            .hasMessage("Plan B, PARENT: no premium for age 66");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.funeral_terms WHERE tenant_id = ?",
            Integer.class, tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.product_version WHERE tenant_id = ?",
            Integer.class, tenant)).isZero();
    }

    @Test
    void aFuneralVersionWithARatingFactorIsRefusedInTheFuneralWords() {
        UUID tenant = UUID.randomUUID();
        assertThatThrownBy(() -> asTenant(tenant, () -> {
            var product = productApi.createProduct("FUN-RATED-" + tenant.toString().substring(0, 4), "Rated",
                ProductCategory.FUNERAL, "TZS", "actuary");
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-80", BigDecimal.ONE, 18, 80)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, List.of(), EligibilityBounds.none(), FrequencyLoading.none(), ANY_FILING, CashValuePlan.none(),
                PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of()), AccumulationPlan.none(),
                DepositPlan.none(), BonusPlan.none(), AnnuityPlan.none(), FuneralPlans.familia(), "actuary");
            return null;
        })).isInstanceOf(InvalidProductVersionException.class)
            .hasMessage("A FUNERAL version is priced by its premium table alone; remove the base rates and rating factors");
    }
}
