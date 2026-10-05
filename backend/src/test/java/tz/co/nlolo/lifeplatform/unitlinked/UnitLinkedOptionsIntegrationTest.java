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
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedOptions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * U2's version terms (product V26): written at publish and read back exactly; absent -- every feature off -- on a
 * version published without them; and, once published, never changed (spec A2): the database refuses an UPDATE or a
 * DELETE on any unit-linked terms table, so a later version can never reach back into a policy already sold.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.class})
class UnitLinkedOptionsIntegrationTest {

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
    @Autowired private JdbcTemplate jdbc;

    private UnitLinkedTestFixtures.Product publishWithOptions(UUID tenant) {
        fixtures.fund(tenant, "EQ1");
        fixtures.fund(tenant, "BD1");
        return fixtures.publish(tenant, UnitLinkedTestFixtures.standardTerms(List.of("EQ1", "BD1"))
            .withOptions(UnitLinkedTestFixtures.standardOptions()));
    }

    @Test
    void theOptionsAreReadBackExactlyAndAVersionWithoutThemOffersNothing() {
        UUID tenant = UUID.randomUUID();
        var u2 = publishWithOptions(tenant);
        UnitLinkedOptions read = asTenant(tenant, () -> productApi.resolveUnitLinkedPlan(u2.versionId())).options();
        assertThat(read.freeSwitchesPerYear()).isEqualTo(2);
        assertThat(read.switchFee()).isEqualByComparingTo("5000.00");
        assertThat(read.minimumWithdrawal()).isEqualByComparingTo("100000.00");
        assertThat(read.minimumRemainingValue()).isEqualByComparingTo("500000.00");
        assertThat(read.withdrawalReducesSumAssured()).isFalse();
        assertThat(read.topUpAllocationPercent()).isEqualByComparingTo("98");
        assertThat(read.minimumTopUp()).isEqualByComparingTo("50000.00");
        assertThat(read.surrenderChargePercent(1)).isEqualByComparingTo("10");
        assertThat(read.surrenderChargePercent(4)).isEqualByComparingTo("5");
        assertThat(read.surrenderChargePercent(20)).isEqualByComparingTo("0");

        UUID other = UUID.randomUUID();
        var u1 = fixtures.publishStandard(other);
        assertThat(asTenant(other, () -> productApi.resolveUnitLinkedPlan(u1.versionId())).options())
            .isEqualTo(UnitLinkedOptions.none());
    }

    @Test
    void aPublishedVersionsTermsCannotBeChanged() {
        UUID tenant = UUID.randomUUID();
        var product = publishWithOptions(tenant);
        for (String sql : List.of(
                "UPDATE product.unit_linked_terms SET monthly_policy_fee = 0 WHERE product_version_id = ?",
                "UPDATE product.unit_linked_options SET switch_fee = 0 WHERE product_version_id = ?",
                "DELETE FROM product.unit_linked_surrender_charge WHERE product_version_id = ?",
                "DELETE FROM product.unit_linked_fund WHERE product_version_id = ?",
                "UPDATE product.unit_linked_allocation_band SET allocation_percent = 100 WHERE product_version_id = ?",
                "DELETE FROM product.unit_linked_mortality WHERE product_version_id = ?",
                "UPDATE product.unit_linked_premium_minimum SET minimum_amount = 1 WHERE product_version_id = ?")) {
            assertThatThrownBy(() -> jdbc.update(sql, product.versionId()))
                .as(sql).hasMessageContaining("terms are never changed");
        }
    }
}
