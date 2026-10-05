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
import tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures;
import tz.co.nlolo.lifeplatform.payment.domain.PaymentGatewayPort;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedOptions;
import tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice;
import tz.co.nlolo.lifeplatform.unitlinked.api.SwitchInput;
import tz.co.nlolo.lifeplatform.unitlinked.api.TopUpInput;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;
import tz.co.nlolo.lifeplatform.unitlinked.api.WithdrawalInput;

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
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.FINANCE;
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.eat;

/**
 * U2 in the ledger (spec §7): a switch fee, a withdrawal and its surrender charge, a top-up and a charged surrender, each
 * leaving 2150 exactly what the units are carried at, every charge in 4310, and 5100 cleared once the money is paid.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, FuneralTestFixtures.class})
class UnitLinkedU2AccountingIntegrationTest {

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
    @Autowired private PolicyApi policyApi;
    @Autowired private UnitLinkedApi api;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void realTimeAndARailThatPays() {
        now.set(Instant.now());
        when(clock.instant()).thenAnswer(i -> now.get());
        when(clock.getZone()).thenReturn(EAT);
        when(gateway.submitDisbursement(any())).thenReturn(new PaymentGatewayPort.GatewayResult(true, "MM-UL", null));
        when(gateway.submitCollection(any())).thenReturn(new PaymentGatewayPort.GatewayResult(true, "MM-COL", null));
    }

    private BigDecimal credit(UUID tenant, String account) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN direction = 'CR' THEN amount ELSE -amount END), 0)"
            + " FROM finaccounting.gl_posting WHERE tenant_id = ? AND account_code = ?", BigDecimal.class, tenant, account);
    }

    private BigDecimal carried(UUID tenant) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(carried), 0) FROM unitlinked.fund_liability WHERE tenant_id = ?",
            BigDecimal.class, tenant);
    }

    private int entries(UUID tenant, String sourceEvent) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM finaccounting.journal_entry WHERE tenant_id = ? AND source_event = ?",
            Integer.class, tenant, sourceEvent);
    }

    private void assertLiabilityIsTheUnits(UUID tenant) {
        assertThat(credit(tenant, "2131")).isEqualByComparingTo(carried(tenant));
    }

    private void priceOn(UUID tenant, LocalDate date, String eq, String bd) {
        Instant at = date.atTime(0, 0, 2).atZone(EAT).toInstant();
        if (at.isAfter(now.get())) {
            now.set(at);
        }
        fixtures.priceBoth(tenant, date, eq, bd);
    }

    @Test
    void everyU2MovementKeepsTheLiabilityExactlyTheUnits() {
        UUID tenant = UUID.randomUUID();
        UnitLinkedOptions s = UnitLinkedTestFixtures.standardOptions();
        UnitLinkedOptions noFreeSwitch = new UnitLinkedOptions(0, s.switchFee(), s.minimumWithdrawal(),
            s.minimumRemainingValue(), false, s.topUpAllocationPercent(), s.minimumTopUp(), s.surrenderCharges());
        var product = fixtures.publishStandardBindingTomorrow(tenant,
            UnitLinkedTestFixtures.standardTerms(List.of("EQ1", "BD1")).withOptions(noFreeSwitch));
        UnitLinkedChoice choice = new UnitLinkedChoice(List.of(new UnitLinkedChoice.Split("EQ1", 60),
            new UnitLinkedChoice.Split("BD1", 40)), new BigDecimal("100000"), "MONTHLY", new BigDecimal("6000000"));
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), choice);
        fixtures.backdate(policy, TODAY.minusYears(3).minusDays(10));               // policy year 4: a 5% charge
        fixtures.collectAt(tenant, policy, "800000.00", eat(TODAY.minusDays(2), 9, 0), UUID.randomUUID());
        priceOn(tenant, TODAY.minusDays(1), "1.000000", "1.000000");
        BigDecimal income = credit(tenant, "2132");
        assertLiabilityIsTheUnits(tenant);

        // A switch beyond the free ones: its 5,000 fee comes out of the liability into income; the move itself stays in 2150.
        asTenant(tenant, () -> api.requestSwitch(policy, new SwitchInput(List.of(new SwitchInput.Out("EQ1", 50)),
            List.of(new UnitLinkedChoice.Split("BD1", 100))), "staff-one"));
        priceOn(tenant, TODAY.plusDays(1), "1.000000", "1.000000");
        assertThat(credit(tenant, "2132").subtract(income)).isEqualByComparingTo("5000.00");
        assertThat(entries(tenant, "unitlinked.SwitchExecuted")).isEqualTo(1);
        assertLiabilityIsTheUnits(tenant);

        // A 150,000 withdrawal at 5%: 150,000 out of the liability, 7,500 into income, 142,500 out through cash.
        var withdrawal = asTenant(tenant, () -> api.requestWithdrawal(policy,
            new WithdrawalInput(new BigDecimal("150000.00"), List.of(), "+255700000600"), "staff-one"));
        asTenant(tenant, () -> api.approveWithdrawal(withdrawal.withdrawalId(), FINANCE));
        priceOn(tenant, TODAY.plusDays(2), "1.000000", "1.000000");
        assertThat(credit(tenant, "2132").subtract(income)).isEqualByComparingTo("12500.00");
        assertThat(credit(tenant, "5110")).isEqualByComparingTo("0.00");
        assertLiabilityIsTheUnits(tenant);

        // A 200,000 top-up: cash into 2140, then 196,000 of it into units and 4,000 of allocation charge. The mock rail
        // accepts the collection, so payment confirms it at once; the publish below is a redelivery, posting nothing twice.
        BigDecimal unearned = credit(tenant, "2121");
        var topUp = asTenant(tenant, () -> api.requestTopUp(policy,
            new TopUpInput(new BigDecimal("200000.00"), "+255700000700", List.of()), "staff-one", UUID.randomUUID().toString()));
        assertThat(credit(tenant, "2121").subtract(unearned)).isEqualByComparingTo("200000.00");
        fixtures.publish(tenant, "payment.PaymentConfirmed", Map.of("paymentRequestId", UUID.randomUUID(),
            "sourceRef", topUp.topUpId().toString(), "purpose", "UL_TOP_UP", "confirmedAt", now.get().toString(),
            "amount", Map.of("amount", "200000.00", "currencyCode", "TZS")));
        assertThat(entries(tenant, "unitlinked.TopUpReceived")).isEqualTo(1);
        priceOn(tenant, TODAY.plusDays(3), "1.000000", "1.000000");
        assertThat(credit(tenant, "2121")).isEqualByComparingTo(unearned);        // in and straight out again
        assertThat(credit(tenant, "2132").subtract(income)).isEqualByComparingTo("16500.00");
        assertLiabilityIsTheUnits(tenant);

        // A surrender at 5%: the liability ends at nothing, the charge is income, and 5100 clears through cash.
        BigDecimal held = carried(tenant);
        var surrender = asTenant(tenant, () -> policyApi.requestSurrender(policy, "+255700000555", "staff-one"));
        asTenant(tenant, () -> policyApi.approveSurrender(surrender.surrenderRequestId(), "staff-two"));
        priceOn(tenant, TODAY.plusDays(4), "1.000000", "1.000000");
        assertThat(carried(tenant)).isEqualByComparingTo("0.00");
        assertThat(credit(tenant, "2131")).isEqualByComparingTo("0.00");
        assertThat(credit(tenant, "2132").subtract(income).subtract(new BigDecimal("16500.00")))
            .isEqualByComparingTo(held.multiply(new BigDecimal("0.05")).setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(entries(tenant, "unitlinked.SurrenderCharged")).isEqualTo(1);
        assertThat(credit(tenant, "5110")).isEqualByComparingTo("0.00");
        assertThat(entries(tenant, "unitlinked.PayoutPaid")).isEqualTo(2);       // the withdrawal's and the surrender's
    }
}
