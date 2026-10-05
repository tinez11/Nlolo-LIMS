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
import tz.co.nlolo.lifeplatform.unitlinked.api.PolicyUnitsView;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.eat;

/**
 * The forward-pricing invariant on the real stack (spec §5): a premium waits for the first approved price dated on
 * or after the valuation date its arrival and the fund's cut-off fix -- never a price already known when it
 * arrived, never an earlier one, never zero. Every date here is in the past, so each price approves at once and no
 * clock is needed: the binding is fixed by the collection instant the test chooses.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, FuneralTestFixtures.class})
class ForwardPricingIntegrationTest {

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

    /** Ten days ago: every price around it is past its cut-off. */
    private static final LocalDate D = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam")).minusDays(10);

    @Autowired private UnitLinkedTestFixtures fixtures;
    @Autowired private UnitLinkedApi api;
    @Autowired private JdbcTemplate jdbc;

    private record Sold(UUID tenant, String policyNumber) {}

    /** Funds EQ1 and BD1 (14:00 cut-offs) priced at 1.000000 on D-1, and a 60/40 policy sold on them. */
    private Sold sold() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandard(tenant);
        fixtures.approvedPrice(tenant, "EQ1", D.minusDays(1), "1.000000");
        fixtures.approvedPrice(tenant, "BD1", D.minusDays(1), "1.000000");
        String policyNumber = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        return new Sold(tenant, policyNumber);
    }

    private PolicyUnitsView units(Sold s) {
        return asTenant(s.tenant(), () -> api.units(s.policyNumber()));
    }

    @Test
    void aPremiumAfterTheCutOffWaitsForTheNextDaysPriceAndNeverUsesTheOneAlreadyKnown() {
        Sold s = sold();
        // Collected on D-1 at 15:00, after the 14:00 cut-off, while D-1's price of 1.000000 is already known.
        fixtures.collectAt(s.tenant(), s.policyNumber(), "100000.00", eat(D.minusDays(1), 15, 0), UUID.randomUUID());

        PolicyUnitsView waiting = units(s);
        assertThat(waiting.pending()).hasSize(2).allSatisfy(o -> assertThat(o.boundDate()).isEqualTo(D));
        assertThat(waiting.entries()).extracting(PolicyUnitsView.Entry::type).containsExactly("ALLOCATION_CHARGE");
        assertThat(waiting.holdings()).isEmpty();

        fixtures.approvedPrice(s.tenant(), "EQ1", D, "1.050000");
        fixtures.approvedPrice(s.tenant(), "BD1", D, "2.000000");

        PolicyUnitsView priced = units(s);
        assertThat(priced.pending()).isEmpty();
        // 90% of 100,000 buys units: 54,000 into EQ1 at 1.05 and 36,000 into BD1 at 2.00, each truncated.
        assertThat(priced.holdings()).extracting(PolicyUnitsView.Holding::fundCode, PolicyUnitsView.Holding::units)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("BD1", new BigDecimal("18000.000000")),
                org.assertj.core.groups.Tuple.tuple("EQ1", new BigDecimal("51428.571428")));
        assertThat(priced.entries()).filteredOn(e -> e.type().equals("ALLOCATION")).hasSize(2)
            .allSatisfy(e -> {
                assertThat(e.valuationDate()).isEqualTo(D);
                assertThat(e.price()).isNotEqualByComparingTo("1.000000"); // never the price known on arrival
            });
    }

    @Test
    void aPremiumBeforeTheCutOffIsPricedAtThatDaysOwnPrice() {
        Sold s = sold();
        fixtures.collectAt(s.tenant(), s.policyNumber(), "100000.00", eat(D, 9, 0), UUID.randomUUID());
        assertThat(units(s).pending()).allSatisfy(o -> assertThat(o.boundDate()).isEqualTo(D));

        fixtures.approvedPrice(s.tenant(), "EQ1", D, "1.000000");
        fixtures.approvedPrice(s.tenant(), "BD1", D, "1.000000");
        assertThat(units(s).holdings()).extracting(PolicyUnitsView.Holding::units)
            .containsExactly(new BigDecimal("36000.000000"), new BigDecimal("54000.000000"));
    }

    @Test
    void aMissingDayIsSweptToTheNextApprovedPriceNeverAnEarlierOne() {
        Sold s = sold();
        fixtures.collectAt(s.tenant(), s.policyNumber(), "100000.00", eat(D.minusDays(1), 15, 0), UUID.randomUUID());
        // No price is ever approved for D (a holiday). D+1's is the first after it.
        fixtures.approvedPrice(s.tenant(), "EQ1", D.plusDays(1), "1.100000");
        fixtures.approvedPrice(s.tenant(), "BD1", D.plusDays(1), "1.000000");

        PolicyUnitsView priced = units(s);
        assertThat(priced.pending()).isEmpty();
        assertThat(priced.entries()).filteredOn(e -> e.type().equals("ALLOCATION")).hasSize(2).allSatisfy(e -> {
            assertThat(e.boundDate()).isEqualTo(D);
            assertThat(e.valuationDate()).isEqualTo(D.plusDays(1));
        });
    }

    @Test
    void aRedeliveredPremiumBuysNothingTwice() {
        Sold s = sold();
        UUID invoice = UUID.randomUUID();
        fixtures.collectAt(s.tenant(), s.policyNumber(), "100000.00", eat(D, 9, 0), invoice);
        fixtures.collectAt(s.tenant(), s.policyNumber(), "100000.00", eat(D, 9, 0), invoice);
        assertThat(units(s).pending()).hasSize(2);
        assertThat(units(s).entries()).hasSize(1); // one allocation charge
    }

    @Test
    void aPremiumOnAFrozenPolicyIsNotInvested() {
        Sold s = sold();
        jdbc.update("INSERT INTO unitlinked.frozen_policy (policy_number, tenant_id, reason, source_ref) VALUES (?, ?, 'DEATH', 'test')",
            s.policyNumber(), s.tenant());
        fixtures.collectAt(s.tenant(), s.policyNumber(), "100000.00", eat(D, 9, 0), UUID.randomUUID());
        assertThat(units(s).pending()).isEmpty();
        assertThat(units(s).entries()).isEmpty();
        assertThat(units(s).frozen()).isTrue();
    }

    @Test
    void theAllocationChargeAndTheMoneyIntoUnitsSumToThePremium() {
        Sold s = sold();
        fixtures.collectAt(s.tenant(), s.policyNumber(), "100000.00", eat(D, 9, 0), UUID.randomUUID());
        fixtures.approvedPrice(s.tenant(), "EQ1", D, "1.000000");
        fixtures.approvedPrice(s.tenant(), "BD1", D, "1.000000");

        List<PolicyUnitsView.Entry> entries = units(s).entries();
        BigDecimal charge = entries.stream().filter(e -> e.type().equals("ALLOCATION_CHARGE")).map(PolicyUnitsView.Entry::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal intoUnits = entries.stream().filter(e -> e.type().equals("ALLOCATION")).map(PolicyUnitsView.Entry::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(charge).isEqualByComparingTo("-10000.00");
        assertThat(intoUnits).isEqualByComparingTo("90000.00");
        assertThat(intoUnits.subtract(charge)).isEqualByComparingTo("100000.00");
    }
}
