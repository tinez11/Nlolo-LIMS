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
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedOptions;
import tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice;
import tz.co.nlolo.lifeplatform.unitlinked.api.PolicyUnitsView;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;
import tz.co.nlolo.lifeplatform.unitlinked.api.WithdrawalInput;
import tz.co.nlolo.lifeplatform.unitlinked.api.WithdrawalView;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.FINANCE;
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.eat;

/**
 * Partial withdrawals (U2, spec §3): a gross amount sold pro rata or from named funds at the first price after a SECOND
 * person approves, less the surrender charge for the policy year, paid through payment; never more units than held;
 * refused below the minimum, when too little would stay, or when cutting the cover would go below the product's floor.
 * The policy is issued three years ago -- year 4: 98% allocation, a 5% surrender charge.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.class})
class WithdrawalIntegrationTest {

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

    /** 800,000 collected and priced at 1.00 in policy year 4: 98% buys 470,400 EQ1 and 313,600 BD1 units. */
    private Sold invested(UnitLinkedOptions options, String sumAssured) {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandardBindingTomorrow(tenant,
            UnitLinkedTestFixtures.standardTerms(List.of("EQ1", "BD1")).withOptions(options));
        UnitLinkedChoice choice = new UnitLinkedChoice(List.of(new UnitLinkedChoice.Split("EQ1", 60),
            new UnitLinkedChoice.Split("BD1", 40)), new BigDecimal("100000"), "MONTHLY", new BigDecimal(sumAssured));
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), choice);
        fixtures.backdate(policy, TODAY.minusYears(3).minusDays(10));
        fixtures.collectAt(tenant, policy, "800000.00", eat(TODAY.minusDays(2), 9, 0), UUID.randomUUID());
        priceOn(tenant, TODAY.minusDays(1), "1.000000", "1.000000");
        return new Sold(tenant, policy);
    }

    private Sold invested() {
        return invested(UnitLinkedTestFixtures.standardOptions(), "6000000");
    }

    private void priceOn(UUID tenant, LocalDate date, String eq, String bd) {
        Instant at = date.atTime(0, 0, 2).atZone(EAT).toInstant();
        if (at.isAfter(now.get())) {
            now.set(at);
        }
        fixtures.priceBoth(tenant, date, eq, bd);
    }

    private WithdrawalView withdrawal(Sold s, UUID id) {
        return asTenant(s.tenant(), () -> api.listWithdrawals(s.policyNumber())).stream()
            .filter(w -> w.withdrawalId().equals(id)).findFirst().orElseThrow();
    }

    private BigDecimal held(Sold s, String fund) {
        return asTenant(s.tenant(), () -> api.units(s.policyNumber())).holdings().stream()
            .filter(h -> h.fundCode().equals(fund)).map(PolicyUnitsView.Holding::units).findFirst().orElse(BigDecimal.ZERO);
    }

    private BigDecimal paid(UUID withdrawalId) {
        return jdbc.queryForObject("SELECT amount FROM payment.disbursement_instruction WHERE idempotency_key = ?",
            BigDecimal.class, "unit-linked:withdrawal:" + withdrawalId);
    }

    @Test
    void aWithdrawalSellsProRataAfterASecondPersonApprovesLessTheSurrenderCharge() {
        Sold s = invested();
        WithdrawalView requested = asTenant(s.tenant(), () -> api.requestWithdrawal(s.policyNumber(),
            new WithdrawalInput(new BigDecimal("150000.00"), List.of(), "+255700000600"), "staff-one"));
        // The estimates the approver sees: 5% in policy year 4, and what would stay at today's prices.
        assertThat(requested.estimatedCharge()).isEqualByComparingTo("7500.00");
        assertThat(requested.estimatedNet()).isEqualByComparingTo("142500.00");
        assertThat(requested.estimatedRemaining()).isEqualByComparingTo("634000.00");

        assertThatThrownBy(() -> asTenant(s.tenant(), () -> api.approveWithdrawal(requested.withdrawalId(), "staff-one")))
            .hasMessageContaining("a second person approves");
        asTenant(s.tenant(), () -> api.approveWithdrawal(requested.withdrawalId(), FINANCE));
        assertThat(held(s, "EQ1")).isEqualByComparingTo("470400");          // nothing sold before the next price

        priceOn(s.tenant(), TODAY.plusDays(1), "1.000000", "1.000000");
        WithdrawalView done = withdrawal(s, requested.withdrawalId());
        assertThat(done.proceeds()).isEqualByComparingTo("150000.00");
        assertThat(done.surrenderCharge()).isEqualByComparingTo("7500.00");
        assertThat(done.status()).isEqualTo("PAID");
        assertThat(paid(requested.withdrawalId())).isEqualByComparingTo("142500.00");
        assertThat(held(s, "EQ1")).isEqualByComparingTo("380400");           // 90,000 of the 150,000: 60/40 by value
        assertThat(held(s, "BD1")).isEqualByComparingTo("253600");
    }

    @Test
    void aNamedFundSellsOnlyThatFundAndAPriceFallCapsItAtTheHolding() {
        Sold s = invested();
        WithdrawalView requested = asTenant(s.tenant(), () -> api.requestWithdrawal(s.policyNumber(), new WithdrawalInput(
            new BigDecimal("200000.00"), List.of(new WithdrawalInput.Named("BD1", new BigDecimal("200000.00"))),
            "+255700000600"), "staff-one"));
        asTenant(s.tenant(), () -> api.approveWithdrawal(requested.withdrawalId(), FINANCE));
        priceOn(s.tenant(), TODAY.plusDays(1), "1.000000", "0.600000");      // BD1 falls to 0.60

        WithdrawalView done = withdrawal(s, requested.withdrawalId());
        assertThat(done.proceeds()).isEqualByComparingTo("188160.00");       // all 313,600 BD1 units x 0.60
        assertThat(done.shortfall()).isEqualByComparingTo("11840.00");
        assertThat(held(s, "BD1")).isEqualByComparingTo("0");
        assertThat(held(s, "EQ1")).isEqualByComparingTo("470400");          // untouched
    }

    @Test
    void aWithdrawalBelowTheMinimumOrLeavingTooLittleIsRefused() {
        Sold s = invested();
        assertThatThrownBy(() -> asTenant(s.tenant(), () -> api.requestWithdrawal(s.policyNumber(),
            new WithdrawalInput(new BigDecimal("50000.00"), List.of(), "+255700000600"), "staff-one")))
            .hasMessageContaining("A withdrawal is at least 100,000.00 TZS");
        assertThatThrownBy(() -> asTenant(s.tenant(), () -> api.requestWithdrawal(s.policyNumber(),
            new WithdrawalInput(new BigDecimal("400000.00"), List.of(), "+255700000600"), "staff-one")))
            .hasMessageContaining("at least 500,000.00 TZS must stay in the policy");
    }

    @Test
    void aWithdrawalCutsCoverOnlyWhenTheVersionSaysSoAndNeverBelowItsFloor() {
        UnitLinkedOptions cuts = new UnitLinkedOptions(2, new BigDecimal("5000"), new BigDecimal("100000"),
            new BigDecimal("500000"), true, new BigDecimal("98"), new BigDecimal("50000"),
            UnitLinkedTestFixtures.standardOptions().surrenderCharges());
        Sold s = invested(cuts, "10000000");                                 // 5x-20x of 1,200,000 a year
        WithdrawalView requested = asTenant(s.tenant(), () -> api.requestWithdrawal(s.policyNumber(),
            new WithdrawalInput(new BigDecimal("150000.00"), List.of(), "+255700000600"), "staff-one"));
        assertThat(requested.newSumAssured()).isEqualByComparingTo("9850000");
        asTenant(s.tenant(), () -> api.approveWithdrawal(requested.withdrawalId(), FINANCE));
        priceOn(s.tenant(), TODAY.plusDays(1), "1.000000", "1.000000");
        assertThat(asTenant(s.tenant(), () -> policyApi.getPolicy(s.policyNumber())).sumAssuredAmount())
            .isEqualByComparingTo("9850000");

        Sold atFloor = invested(cuts, "6000000");                            // exactly 5 x 1,200,000
        assertThatThrownBy(() -> asTenant(atFloor.tenant(), () -> api.requestWithdrawal(atFloor.policyNumber(),
            new WithdrawalInput(new BigDecimal("150000.00"), List.of(), "+255700000600"), "staff-one")))
            .hasMessageContaining("Cutting the cover by this withdrawal would leave 5,850,000.00 TZS");
    }

    @Test
    void aShortfallCutsCoverByWhatTheSaleRaisedNotWhatWasAsked() {
        UnitLinkedOptions cuts = new UnitLinkedOptions(2, new BigDecimal("5000"), new BigDecimal("100000"),
            new BigDecimal("500000"), true, new BigDecimal("98"), new BigDecimal("50000"),
            UnitLinkedTestFixtures.standardOptions().surrenderCharges());
        Sold s = invested(cuts, "10000000");
        WithdrawalView requested = asTenant(s.tenant(), () -> api.requestWithdrawal(s.policyNumber(), new WithdrawalInput(
            new BigDecimal("200000.00"), List.of(new WithdrawalInput.Named("BD1", new BigDecimal("200000.00"))),
            "+255700000600"), "staff-one"));
        asTenant(s.tenant(), () -> api.approveWithdrawal(requested.withdrawalId(), FINANCE));
        priceOn(s.tenant(), TODAY.plusDays(1), "1.000000", "0.600000");      // BD1 raises only 188,160
        assertThat(withdrawal(s, requested.withdrawalId()).proceeds()).isEqualByComparingTo("188160.00");
        assertThat(asTenant(s.tenant(), () -> policyApi.getPolicy(s.policyNumber())).sumAssuredAmount())
            .isEqualByComparingTo("9811840");                                // 10,000,000 less 188,160, not 200,000
    }
}
