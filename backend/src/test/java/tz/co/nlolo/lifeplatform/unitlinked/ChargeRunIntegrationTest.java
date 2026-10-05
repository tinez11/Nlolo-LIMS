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
import tz.co.nlolo.lifeplatform.unitlinked.application.ChargeSweep;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.eat;

/**
 * The monthly charges (spec §6): the fee and the cost of insurance sold from units on each charge date, priced
 * forward; the cost of insurance sized at the latest price before the date; run once per date; never on a frozen
 * policy or one whose first premium has not bought units.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, FuneralTestFixtures.class})
class ChargeRunIntegrationTest {

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

    /** Issued 70 days ago, so the first charge date (issue + 1 month) is about 40 days ago: priceable. */
    private static final LocalDate ISSUED = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam")).minusDays(70);
    private static final LocalDate FIRST_CHARGE = ISSUED.plusMonths(1);

    @Autowired private UnitLinkedTestFixtures fixtures;
    @Autowired private UnitLinkedApi api;
    @Autowired private ChargeSweep sweep;
    @Autowired private JdbcTemplate jdbc;

    private record Sold(UUID tenant, String policyNumber) {}

    /** A 35-year-old's 60/40 policy issued on ISSUED, its 100,000 premium bought there at 1.000000 in both funds. */
    private Sold invested() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandard(tenant);
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        fixtures.backdate(policy, ISSUED);
        fixtures.collectAt(tenant, policy, "100000.00", eat(ISSUED, 9, 0), UUID.randomUUID());
        fixtures.priceBoth(tenant, ISSUED, "1.000000", "1.000000");
        return new Sold(tenant, policy);
    }

    private PolicyUnitsView units(Sold s) {
        return asTenant(s.tenant(), () -> api.units(s.policyNumber()));
    }

    private BigDecimal charged(Sold s, String type) {
        return units(s).entries().stream().filter(e -> e.type().equals(type)).map(e -> e.amount().negate())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void feeAndCostOfInsuranceAreSoldAtTheChargeDatesForwardPrice() {
        Sold s = invested();
        sweep.sweepOne(s.policyNumber(), s.tenant(), FIRST_CHARGE);
        PolicyUnitsView waiting = units(s);
        assertThat(waiting.pending()).isNotEmpty().allSatisfy(o -> {
            assertThat(o.purpose()).isEqualTo("CHARGES");
            assertThat(o.boundDate()).isEqualTo(FIRST_CHARGE);
        });

        fixtures.priceBoth(s.tenant(), FIRST_CHARGE, "1.000000", "1.000000");

        assertThat(units(s).pending()).isEmpty();
        assertThat(charged(s, "POLICY_FEE")).isEqualByComparingTo("2000.00");
        // Sized at the latest price BEFORE the charge date: 90,000 of units, so 5,910,000 at risk of 6,000,000.
        // 1.2 per mille a year = 7,092.00, a month 591.00.
        assertThat(charged(s, "COST_OF_INSURANCE")).isEqualByComparingTo("591.00");
        assertThat(units(s).entries()).filteredOn(e -> e.type().equals("POLICY_FEE") || e.type().equals("COST_OF_INSURANCE"))
            .allSatisfy(e -> assertThat(e.valuationDate()).isEqualTo(FIRST_CHARGE));
    }

    @Test
    void chargesAreTakenFromEachFundInProportionToItsValue() {
        Sold s = invested();
        sweep.sweepOne(s.policyNumber(), s.tenant(), FIRST_CHARGE);
        fixtures.priceBoth(s.tenant(), FIRST_CHARGE, "1.000000", "1.000000");
        // 54,000 in EQ1 and 36,000 in BD1: the 2,000 fee is taken 1,200 / 800.
        assertThat(units(s).entries()).filteredOn(e -> e.type().equals("POLICY_FEE"))
            .extracting(PolicyUnitsView.Entry::fundCode, PolicyUnitsView.Entry::amount)
            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("EQ1", new BigDecimal("-1200.00")),
                org.assertj.core.groups.Tuple.tuple("BD1", new BigDecimal("-800.00")));
    }

    @Test
    void aChargeDateRunsOnceHoweverOftenItIsSwept() {
        Sold s = invested();
        sweep.sweepOne(s.policyNumber(), s.tenant(), FIRST_CHARGE);
        sweep.sweepOne(s.policyNumber(), s.tenant(), FIRST_CHARGE);
        sweep.sweepOne(s.policyNumber(), s.tenant(), FIRST_CHARGE.plusDays(3));
        assertThat(units(s).pending()).filteredOn(o -> o.purpose().equals("CHARGES")).hasSize(4); // fee + coi, two funds
    }

    @Test
    void aFrozenPolicyIsNotCharged() {
        Sold s = invested();
        jdbc.update("INSERT INTO unitlinked.frozen_policy (policy_number, tenant_id, reason, source_ref) VALUES (?, ?, 'DEATH', 'test')",
            s.policyNumber(), s.tenant());
        sweep.sweepOne(s.policyNumber(), s.tenant(), FIRST_CHARGE);
        assertThat(units(s).pending()).isEmpty();
    }

    @Test
    void aPolicyWhoseFirstPremiumHasNotBoughtUnitsIsNotChargedOrExhausted() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandard(tenant);
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        fixtures.backdate(policy, ISSUED);
        // Collected (so in force) but still waiting for its price: no ALLOCATION entry yet.
        fixtures.collectAt(tenant, policy, "100000.00", eat(ISSUED, 15, 0), UUID.randomUUID());
        sweep.sweepOne(policy, tenant, FIRST_CHARGE);
        PolicyUnitsView view = asTenant(tenant, () -> api.units(policy));
        assertThat(view.pending()).allSatisfy(o -> assertThat(o.purpose()).isEqualTo("ALLOCATION"));
        assertThat(view.frozen()).isFalse();
    }
}
