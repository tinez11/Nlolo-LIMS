package tz.co.nlolo.lifeplatform.unitlinked;

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
import tz.co.nlolo.lifeplatform.product.api.InvalidProductVersionException;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/** A UNIT_LINKED version's terms written at publish and read back exactly; a refusal writes nothing (spec §4). */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.class})
class UnitLinkedTermsIntegrationTest {

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
    @Autowired private ProductApi productApi;
    @Autowired private UnitLinkedApi unitLinkedApi;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void publishesTheTermsAndReadsThemBackExactly() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandard(tenant);

        UnitLinkedPlan read = asTenant(tenant, () -> productApi.resolveUnitLinkedPlan(product.versionId()));
        UnitLinkedPlan published = UnitLinkedTestFixtures.standardTerms(List.of("EQ1", "BD1"));
        assertThat(read.unitLinked()).isTrue();
        assertThat(read.fundCodes()).containsExactlyInAnyOrder("EQ1", "BD1");
        assertThat(read.allocationBands()).extracting(UnitLinkedPlan.AllocationBand::fromYear, UnitLinkedPlan.AllocationBand::toYear)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(1, 2), org.assertj.core.groups.Tuple.tuple(3, null));
        assertThat(read.mortality()).hasSameSizeAs(published.mortality());
        assertThat(read.allocationPercent(1)).isEqualByComparingTo("90");
        assertThat(read.allocationPercent(3)).isEqualByComparingTo("98");
        assertThat(read.monthlyPolicyFee()).isEqualByComparingTo("2000.00");
        assertThat(read.annualRatePerMille(97, null)).isEqualByComparingTo("25");
        assertThat(read.deathRule()).isEqualTo(UnitLinkedPlan.DeathRule.HIGHER_OF);
        assertThat(read.lapseRule()).isEqualTo(UnitLinkedPlan.LapseRule.EXHAUSTION);
        assertThat(read.minimumPremiumYears()).isNull();
        assertThat(read.lowFundWarningMonths()).isEqualTo(3);
        assertThat(read.minimumPremium("MONTHLY")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("50000.00"));
        assertThat(read.minimumPremium("ANNUALLY")).isEmpty();
        assertThat(read.sumAssuredMultipleMin()).isEqualByComparingTo("5");
        assertThat(read.sumAssuredMultipleMax()).isEqualByComparingTo("20");
    }

    @Test
    void aVersionOfferingAFundNotInTheRegisterIsRefusedAndWritesNothing() {
        UUID tenant = UUID.randomUUID();
        fixtures.fund(tenant, "EQ1");
        assertThatThrownBy(() -> fixtures.publish(tenant, UnitLinkedTestFixtures.standardTerms(List.of("EQ1", "NOPE"))))
            .isInstanceOf(InvalidProductVersionException.class)
            .hasMessage("Fund NOPE is not in the fund register");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.unit_linked_terms WHERE tenant_id = ?", Integer.class, tenant))
            .isZero();
    }

    @Test
    void aClosedFundCannotBeOfferedOnANewVersion() {
        UUID tenant = UUID.randomUUID();
        fixtures.fund(tenant, "EQ1");
        asTenant(tenant, () -> unitLinkedApi.closeFund("EQ1", UnitLinkedTestFixtures.ADMIN));
        assertThatThrownBy(() -> fixtures.publish(tenant, UnitLinkedTestFixtures.standardTerms(List.of("EQ1"))))
            .hasMessageContaining("Fund EQ1 is closed");
    }

    @Test
    void anotherTenantsFundIsNotInThisRegister() {
        UUID other = UUID.randomUUID();
        fixtures.fund(other, "EQ1");
        UUID tenant = UUID.randomUUID();
        assertThatThrownBy(() -> fixtures.publish(tenant, UnitLinkedTestFixtures.standardTerms(List.of("EQ1"))))
            .hasMessage("Fund EQ1 is not in the fund register");
    }

    @Test
    void aNonUnitLinkedVersionReadsNone() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandard(tenant);
        assertThat(asTenant(tenant, () -> productApi.resolveUnitLinkedPlan(UUID.randomUUID())).unitLinked()).isFalse();
        assertThat(asTenant(tenant, () -> productApi.resolveFuneralPlan(product.versionId())).funeral()).isFalse();
    }
}
