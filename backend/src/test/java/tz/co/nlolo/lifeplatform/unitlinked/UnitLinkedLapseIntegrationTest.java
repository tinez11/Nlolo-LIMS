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
import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policyloan.api.PolicyLoanApi;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;
import tz.co.nlolo.lifeplatform.unitlinked.api.PolicyUnitsView;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;
import tz.co.nlolo.lifeplatform.unitlinked.application.ChargeSweep;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.eat;

/**
 * When a unit-linked policy lapses (spec §6): under EXHAUSTION, only when its fund can no longer meet a month's
 * charges -- never on billing's arrears recommendation once its minimum premium-paying years have passed -- and a
 * shortfall is written off, never billed. Paid-up and policy loans are refused in U1 (plan R9).
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, FuneralTestFixtures.class})
class UnitLinkedLapseIntegrationTest {

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

    private static final LocalDate ISSUED = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam")).minusDays(70);
    private static final LocalDate FIRST_CHARGE = ISSUED.plusMonths(1);

    @Autowired private UnitLinkedTestFixtures fixtures;
    @Autowired private UnitLinkedApi api;
    @Autowired private PolicyApi policyApi;
    @Autowired private PolicyLoanApi loanApi;
    @Autowired private ChargeSweep sweep;

    private String status(UUID tenant, String policy) {
        return asTenant(tenant, () -> policyApi.getPolicy(policy)).status().name();
    }

    private String sellOn(UUID tenant, UnitLinkedPlan terms, String premium) {
        var product = fixtures.publish(tenant, terms);
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        fixtures.backdate(policy, ISSUED);
        fixtures.collectAt(tenant, policy, premium, eat(ISSUED, 9, 0), UUID.randomUUID());
        fixtures.priceBoth(tenant, ISSUED, "1.000000", "1.000000");
        return policy;
    }

    private void recommendLapse(UUID tenant, String policy) {
        fixtures.publish(tenant, "billing.PolicyLapseRecommended", Map.of("policyNumber", policy,
            "invoiceId", UUID.randomUUID().toString(), "recommendedAt", Instant.now().toString()));
    }

    private static UnitLinkedPlan withLapse(UnitLinkedPlan.LapseRule rule, Integer minimumPremiumYears) {
        UnitLinkedPlan t = UnitLinkedTestFixtures.standardTerms(List.of("EQ1", "BD1"));
        return UnitLinkedPlan.of(t.fundCodes(), t.allocationBands(), t.monthlyPolicyFee(), t.mortalityBasis(), t.mortality(),
            t.deathRule(), rule, minimumPremiumYears, t.minimumSurrenderYears(), t.lowFundWarningMonths(), t.premiumMinimums(),
            t.sumAssuredMultipleMin(), t.sumAssuredMultipleMax());
    }

    private void funds(UUID tenant) {
        fixtures.fund(tenant, "EQ1");
        fixtures.fund(tenant, "BD1");
    }

    @Test
    void anExhaustedFundSellsEverythingWritesOffTheRestAndLapses() {
        UUID tenant = UUID.randomUUID();
        funds(tenant);
        // 900 of units against a 2,000 fee and its cost of insurance: the first month cannot be met.
        String policy = sellOn(tenant, withLapse(UnitLinkedPlan.LapseRule.EXHAUSTION, null), "1000.00");
        sweep.sweepOne(policy, tenant, FIRST_CHARGE);
        fixtures.priceBoth(tenant, FIRST_CHARGE, "1.000000", "1.000000");

        PolicyUnitsView units = asTenant(tenant, () -> api.units(policy));
        assertThat(units.holdings()).allSatisfy(h -> assertThat(h.units()).isEqualByComparingTo("0"));
        BigDecimal writtenOff = units.entries().stream().filter(e -> e.type().equals("WRITE_OFF"))
            .map(PolicyUnitsView.Entry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sold = units.entries().stream().filter(e -> e.type().equals("POLICY_FEE") || e.type().equals("COST_OF_INSURANCE"))
            .map(e -> e.amount().negate()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sold).isEqualByComparingTo("900.00");
        assertThat(writtenOff).isPositive();
        assertThat(units.frozen()).isTrue();
        assertThat(units.frozenReason()).isEqualTo("EXHAUSTED");
        assertThat(status(tenant, policy)).isEqualTo("LAPSED");
    }

    @Test
    void billingDoesNotLapseAnExhaustionPolicyPastItsMinimumYears() {
        UUID tenant = UUID.randomUUID();
        funds(tenant);
        String policy = sellOn(tenant, withLapse(UnitLinkedPlan.LapseRule.EXHAUSTION, null), "100000.00");
        recommendLapse(tenant, policy);
        assertThat(status(tenant, policy)).isEqualTo("ACTIVE");
    }

    @Test
    void billingDoesLapseAnExhaustionPolicyInsideItsMinimumYears() {
        UUID tenant = UUID.randomUUID();
        funds(tenant);
        String policy = sellOn(tenant, withLapse(UnitLinkedPlan.LapseRule.EXHAUSTION, 2), "100000.00");
        recommendLapse(tenant, policy);
        assertThat(status(tenant, policy)).isEqualTo("LAPSED");
    }

    @Test
    void aNonPaymentVersionLapsesOnBillingsRecommendation() {
        UUID tenant = UUID.randomUUID();
        funds(tenant);
        String policy = sellOn(tenant, withLapse(UnitLinkedPlan.LapseRule.NON_PAYMENT, null), "100000.00");
        recommendLapse(tenant, policy);
        assertThat(status(tenant, policy)).isEqualTo("LAPSED");
    }

    @Test
    void paidUpAndPolicyLoansAreRefusedInWords() {
        UUID tenant = UUID.randomUUID();
        funds(tenant);
        String policy = sellOn(tenant, withLapse(UnitLinkedPlan.LapseRule.EXHAUSTION, null), "100000.00");
        assertThatThrownBy(() -> asTenant(tenant, () -> policyApi.makePaidUp(policy, "staff")))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("its value is its units");
        assertThatThrownBy(() -> asTenant(tenant, () -> loanApi.originateLoan(policy, new BigDecimal("1000.00"), "TZS",
                "+255700000001", "staff")))
            .hasMessageContaining("its value is its units");
    }
}
