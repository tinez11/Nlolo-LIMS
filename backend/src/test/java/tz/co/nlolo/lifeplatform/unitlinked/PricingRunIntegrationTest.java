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
import tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.eat;

/**
 * What an approved price does to the fund (spec §5, §8, plan D1): after every run the carried liability is exactly
 * units in issue × price, rounded once -- the price movement on units already held and every sub-cent residue
 * land in that one true-up -- and a holding can never go below zero, even written by hand.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, FuneralTestFixtures.class})
class PricingRunIntegrationTest {

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

    private static final LocalDate D = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam")).minusDays(10);

    @Autowired private UnitLinkedTestFixtures fixtures;
    @Autowired private JdbcTemplate jdbc;

    private void assertCarriedIsUnitsTimesPrice(UUID tenant, String fundCode, String expectedPrice) {
        Map<String, Object> row = jdbc.queryForMap("SELECT l.carried, l.units_in_issue, p.price FROM unitlinked.fund_liability l"
            + " JOIN unitlinked.fund f ON f.fund_id = l.fund_id JOIN unitlinked.fund_price p ON p.price_id = l.price_id"
            + " WHERE f.tenant_id = ? AND f.code = ?", tenant, fundCode);
        BigDecimal units = jdbc.queryForObject("SELECT COALESCE(SUM(e.units), 0) FROM unitlinked.unit_entry e"
            + " JOIN unitlinked.fund f ON f.fund_id = e.fund_id WHERE f.tenant_id = ? AND f.code = ?", BigDecimal.class, tenant, fundCode);
        BigDecimal price = (BigDecimal) row.get("price");
        assertThat(price).isEqualByComparingTo(expectedPrice);
        assertThat((BigDecimal) row.get("units_in_issue")).isEqualByComparingTo(units);
        assertThat((BigDecimal) row.get("carried")).isEqualByComparingTo(units.multiply(price).setScale(2, RoundingMode.HALF_EVEN));
    }

    @Test
    void afterEveryPriceTheCarriedLiabilityIsExactlyUnitsInIssueTimesPrice() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandard(tenant);
        String first = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        String second = fixtures.sell(tenant, product, fixtures.person(tenant, 41), UnitLinkedTestFixtures.standardChoice());

        // An awkward price, so each entry's money leaves a sub-cent residue the true-up must absorb.
        fixtures.collectAt(tenant, first, "100000.00", eat(D, 9, 0), UUID.randomUUID());
        fixtures.approvedPrice(tenant, "EQ1", D, "1.333333");
        fixtures.approvedPrice(tenant, "BD1", D, "0.777777");
        assertCarriedIsUnitsTimesPrice(tenant, "EQ1", "1.333333");
        assertCarriedIsUnitsTimesPrice(tenant, "BD1", "0.777777");

        // A second policy buys at the next price while the first's units are revalued by it.
        fixtures.collectAt(tenant, second, "100000.00", eat(D.plusDays(1), 9, 0), UUID.randomUUID());
        fixtures.approvedPrice(tenant, "EQ1", D.plusDays(1), "1.400000");
        fixtures.approvedPrice(tenant, "BD1", D.plusDays(1), "0.800000");
        assertCarriedIsUnitsTimesPrice(tenant, "EQ1", "1.400000");
        assertCarriedIsUnitsTimesPrice(tenant, "BD1", "0.800000");

        // A price with nothing waiting still revalues what is held.
        fixtures.approvedPrice(tenant, "EQ1", D.plusDays(2), "1.300000");
        assertCarriedIsUnitsTimesPrice(tenant, "EQ1", "1.300000");
    }

    @Test
    void aHoldingNeverGoesNegativeEvenWrittenByHand() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandard(tenant);
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        var price = fixtures.approvedPrice(tenant, "EQ1", D, "1.000000");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO unitlinked.unit_entry (tenant_id, policy_number, fund_id, entry_type,"
            + " units, price, price_id, amount, valuation_date, source_type, source_ref, created_by)"
            + " VALUES (?, ?, ?, 'SURRENDER_SALE', -1, 1, ?, -1, ?, 'test', 'by-hand', 'test')",
            tenant, policy, price.fundId(), price.priceId(), D))
            .hasMessageContaining("would hold -1.000000 units");
    }

    @Test
    void theLedgerIsAppendOnlyEvenForTheOwner() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandard(tenant);
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        fixtures.collectAt(tenant, policy, "100000.00", eat(D, 9, 0), UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.update("UPDATE unitlinked.unit_entry SET amount = 0 WHERE policy_number = ?", policy))
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM unitlinked.unit_entry WHERE policy_number = ?", policy))
            .hasMessageContaining("append-only");
    }
}
