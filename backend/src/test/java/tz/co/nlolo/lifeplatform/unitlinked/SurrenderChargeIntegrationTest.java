package tz.co.nlolo.lifeplatform.unitlinked;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.payment.domain.PaymentGatewayPort;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;
import tz.co.nlolo.lifeplatform.unitlinked.api.PolicyUnitsView;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.eat;

/**
 * The surrender charge (U2, spec Q6/Q7): a full surrender and a non-payment lapse with value pay the version's percent
 * for the policy year of the sale -- 10% in year 1 here -- and a version published without bands charges nothing.
 * (Death, maturity and free-look never reach the charge: their own tests still assert their full payouts.)
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.class})
class SurrenderChargeIntegrationTest {

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

    private static final ZoneId EAT = ZoneId.of("Africa/Dar_es_Salaam");
    private static final LocalDate TODAY = LocalDate.now(EAT);

    @MockBean(name = "unitLinkedClock") private Clock clock;
    @MockBean private PaymentGatewayPort gateway;
    private final AtomicReference<Instant> now = new AtomicReference<>();

    @Autowired private UnitLinkedTestFixtures fixtures;
    @Autowired private UnitLinkedApi api;
    @Autowired private PolicyApi policyApi;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void realTimeAndARailThatPays() {
        now.set(Instant.now());
        when(clock.instant()).thenAnswer(i -> now.get());
        when(clock.getZone()).thenReturn(EAT);
        when(gateway.submitDisbursement(any())).thenReturn(new PaymentGatewayPort.GatewayResult(true, "MM-UL", null));
    }

    private record Sold(UUID tenant, String policyNumber) {}

    /** Policy year 1: 100,000 collected, 90% bought at 1.00 -- 54,000 EQ1 and 36,000 BD1 units. */
    private Sold invested(UnitLinkedPlan terms) {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandardBindingTomorrow(tenant, terms);
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        fixtures.backdate(policy, TODAY.minusDays(70));
        fixtures.collectAt(tenant, policy, "100000.00", eat(TODAY.minusDays(61), 9, 0), UUID.randomUUID());
        priceOn(tenant, TODAY.minusDays(60));
        return new Sold(tenant, policy);
    }

    private static UnitLinkedPlan charged(UnitLinkedPlan.LapseRule rule) {
        UnitLinkedPlan t = UnitLinkedTestFixtures.standardTerms(List.of("EQ1", "BD1"));
        return UnitLinkedPlan.of(t.fundCodes(), t.allocationBands(), t.monthlyPolicyFee(), t.mortalityBasis(), t.mortality(),
                t.deathRule(), rule, null, t.minimumSurrenderYears(), t.lowFundWarningMonths(), t.premiumMinimums(),
                t.sumAssuredMultipleMin(), t.sumAssuredMultipleMax())
            .withOptions(UnitLinkedTestFixtures.standardOptions());
    }

    private void priceOn(UUID tenant, LocalDate date) {
        Instant at = date.atTime(0, 0, 2).atZone(EAT).toInstant();
        if (at.isAfter(now.get())) {
            now.set(at);
        }
        fixtures.priceBoth(tenant, date, "1.000000", "1.000000");
    }

    private BigDecimal sum(Sold s, String type) {
        return asTenant(s.tenant(), () -> api.units(s.policyNumber())).entries().stream()
            .filter(e -> e.type().equals(type)).map(PolicyUnitsView.Entry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal surrender(Sold s) {
        var request = asTenant(s.tenant(), () -> policyApi.requestSurrender(s.policyNumber(), "+255700000555", "staff-one"));
        asTenant(s.tenant(), () -> policyApi.approveSurrender(request.surrenderRequestId(), "staff-two"));
        priceOn(s.tenant(), TODAY.plusDays(1));
        return jdbc.queryForObject("SELECT amount FROM payment.disbursement_instruction WHERE idempotency_key = ?",
            BigDecimal.class, "unit-linked:surrender:" + request.surrenderRequestId());
    }

    @Test
    void aSurrenderInPolicyYearOnePaysTenPercent() {
        Sold s = invested(charged(UnitLinkedPlan.LapseRule.EXHAUSTION));
        assertThat(surrender(s)).isEqualByComparingTo("81000.00");               // 90,000 less 10%
        assertThat(sum(s, "SURRENDER_CHARGE")).isEqualByComparingTo("-9000.00");
    }

    @Test
    void aNonPaymentLapseWithValueIsCharged() {
        Sold s = invested(charged(UnitLinkedPlan.LapseRule.NON_PAYMENT));
        fixtures.publish(s.tenant(), "billing.PolicyLapseRecommended", Map.of("policyNumber", s.policyNumber(),
            "invoiceId", UUID.randomUUID().toString(), "recommendedAt", Instant.now().toString()));
        priceOn(s.tenant(), TODAY.plusDays(1));
        assertThat(asTenant(s.tenant(), () -> policyApi.getPolicy(s.policyNumber())).status().name()).isEqualTo("LAPSED");
        assertThat(sum(s, "SURRENDER_CHARGE")).isEqualByComparingTo("-9000.00");
        assertThat(jdbc.queryForObject("SELECT surrender_charge FROM unitlinked.exit_state WHERE policy_number = ?",
            BigDecimal.class, s.policyNumber())).isEqualByComparingTo("9000.00");
    }

    @Test
    void aVersionWithoutBandsChargesNothing() {
        Sold s = invested(UnitLinkedTestFixtures.standardTerms(List.of("EQ1", "BD1")));
        assertThat(surrender(s)).isEqualByComparingTo("90000.00");
        assertThat(sum(s, "SURRENDER_CHARGE")).isEqualByComparingTo("0");
    }
}
