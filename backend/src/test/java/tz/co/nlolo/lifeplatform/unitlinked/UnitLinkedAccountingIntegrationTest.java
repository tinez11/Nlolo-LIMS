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
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;
import tz.co.nlolo.lifeplatform.unitlinked.application.ChargeSweep;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.eat;

/**
 * Unit-linked business in the ledger (spec §8): after every step -- a premium bought, a price rise, a month's
 * charges, a surrender -- the ledger's 2150 equals exactly what the units are carried at, and the charges are income.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, FuneralTestFixtures.class})
class UnitLinkedAccountingIntegrationTest {

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
    private static final LocalDate ISSUED = TODAY.minusDays(70);
    private static final LocalDate FIRST_CHARGE = ISSUED.plusMonths(1);

    @MockBean(name = "unitLinkedClock") private Clock clock;
    @MockBean private PaymentGatewayPort gateway;
    private final AtomicReference<Instant> now = new AtomicReference<>();

    @Autowired private UnitLinkedTestFixtures fixtures;
    @Autowired private PolicyApi policyApi;
    @Autowired private UnitLinkedApi api;
    @Autowired private ChargeSweep sweep;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void realTimeAndARailThatPays() {
        now.set(Instant.now());
        when(clock.instant()).thenAnswer(i -> now.get());
        when(clock.getZone()).thenReturn(EAT);
        when(gateway.submitDisbursement(any())).thenReturn(new PaymentGatewayPort.GatewayResult(true, "MM-UL", null));
    }

    /** CR less DR on one account, for one tenant: the balance of a credit-normal account. */
    private BigDecimal credit(UUID tenant, String account) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN direction = 'CR' THEN amount ELSE -amount END), 0)"
            + " FROM finaccounting.gl_posting WHERE tenant_id = ? AND account_code = ?", BigDecimal.class, tenant, account);
    }

    private BigDecimal carried(UUID tenant) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(carried), 0) FROM unitlinked.fund_liability WHERE tenant_id = ?",
            BigDecimal.class, tenant);
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
    void theLiabilityIsExactlyTheUnitsAfterEveryStep() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandardBindingTomorrow(tenant, UnitLinkedTestFixtures.standardTerms(List.of("EQ1", "BD1")));
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        fixtures.backdate(policy, ISSUED);

        // A premium bought: 90,000 into units, 10,000 of allocation charge earned.
        fixtures.collectAt(tenant, policy, "100000.00", eat(ISSUED, 9, 0), UUID.randomUUID());
        priceOn(tenant, ISSUED.plusDays(1), "1.000000", "1.000000");
        assertThat(credit(tenant, "2131")).isEqualByComparingTo("90000.00");
        assertThat(credit(tenant, "2132")).isEqualByComparingTo("10000.00");
        assertLiabilityIsTheUnits(tenant);

        // A price rise on awkward prices: the true-up carries the movement and every sub-cent residue.
        priceOn(tenant, ISSUED.plusDays(2), "1.333333", "0.987654");
        assertLiabilityIsTheUnits(tenant);

        // A month's fee and cost of insurance: out of the liability, into income.
        sweep.sweepOne(policy, tenant, FIRST_CHARGE);
        priceOn(tenant, FIRST_CHARGE.plusDays(1), "1.300000", "1.000000");
        assertThat(credit(tenant, "2132")).isGreaterThan(new BigDecimal("12000.00")); // 10,000 + the 2,000 fee + coi
        assertLiabilityIsTheUnits(tenant);

        // A surrender: the units sold release the liability, which ends at nothing.
        var request = asTenant(tenant, () -> policyApi.requestSurrender(policy, "+255700000555", "staff-one"));
        asTenant(tenant, () -> policyApi.approveSurrender(request.surrenderRequestId(), "staff-two"));
        priceOn(tenant, TODAY.plusDays(1), "1.250000", "1.010000");
        assertLiabilityIsTheUnits(tenant);
        assertThat(carried(tenant)).isEqualByComparingTo("0.00");
        assertThat(credit(tenant, "2131")).isEqualByComparingTo("0.00");

        // ...and the payout's cash leg: the proceeds ExitPriced credited to 5100 leave through cash on the paid
        // disbursement, once -- unitlinked.PayoutPaid, never policy.SurrenderPaid as well, which would pay it twice.
        assertThat(credit(tenant, "5110")).isEqualByComparingTo("0.00");
        assertThat(entries(tenant, "unitlinked.PayoutPaid")).isEqualTo(1);
        assertThat(entries(tenant, "policy.SurrenderPaid")).isZero();
        assertThat(asTenant(tenant, () -> policyApi.findLatestSurrenderRequest(policy)).orElseThrow().status()).isEqualTo("PAID");
    }

    private int entries(UUID tenant, String sourceEvent) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM finaccounting.journal_entry WHERE tenant_id = ? AND source_event = ?",
            Integer.class, tenant, sourceEvent);
    }

    @Test
    void anAdjustmentCollectedOrWaivedClearsWhatTheCorrectionLeftOwed() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandardBindingTomorrow(tenant, UnitLinkedTestFixtures.standardTerms(List.of("EQ1", "BD1")));
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        fixtures.backdate(policy, ISSUED);
        fixtures.collectAt(tenant, policy, "100000.00", eat(ISSUED, 9, 0), UUID.randomUUID());
        priceOn(tenant, ISSUED.plusDays(1), "1.000000", "1.000000");

        var request = asTenant(tenant, () -> policyApi.requestSurrender(policy, "+255700000555", "staff-one"));
        asTenant(tenant, () -> policyApi.approveSurrender(request.surrenderRequestId(), "staff-two"));
        priceOn(tenant, TODAY.plusDays(1), "1.100000", "1.000000"); // EQ1 should have been 1.000000: paid too much

        var wrong = asTenant(tenant, () -> api.listPrices("EQ1", TODAY.plusDays(1), TODAY.plusDays(1))).get(0);
        // Since IFRS 17 I1 an amount owed by the customer sits in 2122 Premiums due from policyholders, beside the
        // premiums themselves, so the adjustment is measured as the movement it causes there.
        BigDecimal dueBefore = credit(tenant, "2122");
        asTenant(tenant, () -> api.approvePrice(
            api.proposeCorrection(wrong.priceId(), new BigDecimal("1.000000"), "Feed typo", UnitLinkedTestFixtures.FINANCE).priceId(),
            UnitLinkedTestFixtures.ADMIN));
        var owed = asTenant(tenant, () -> api.listAdjustments("OPEN")).get(0);
        assertThat(owed.direction()).isEqualTo("OWED_BY_CUSTOMER");
        assertThat(dueBefore.subtract(credit(tenant, "2122"))).isEqualByComparingTo(owed.amount()); // the receivable it raised

        asTenant(tenant, () -> api.settleAdjustment(owed.adjustmentId(), "RCPT-77", UnitLinkedTestFixtures.FINANCE));
        assertThat(credit(tenant, "2122")).isEqualByComparingTo(dueBefore); // collected: cleared against cash
        assertThat(entries(tenant, "unitlinked.AdjustmentCollected")).isEqualTo(1);
        assertLiabilityIsTheUnits(tenant);
    }
}
