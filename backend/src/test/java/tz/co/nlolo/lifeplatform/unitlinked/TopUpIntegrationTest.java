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
import tz.co.nlolo.lifeplatform.unitlinked.api.PolicyUnitsView;
import tz.co.nlolo.lifeplatform.unitlinked.api.TopUpInput;
import tz.co.nlolo.lifeplatform.unitlinked.api.TopUpView;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.eat;

/**
 * Top-ups (U2, spec §4): collected under UL_TOP_UP once per Idempotency-Key, allocated when payment confirms the money at
 * the version's own top-up percent (98%) -- not the policy year's band (90% in year 1) -- by the split in force; and a
 * top-up whose money arrives after the policy froze for an exit is carried back with the exit, never invested.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.class})
class TopUpIntegrationTest {

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
    void realTimeAndARailThatAccepts() {
        now.set(Instant.now());
        when(clock.instant()).thenAnswer(i -> now.get());
        when(clock.getZone()).thenReturn(EAT);
        when(gateway.submitDisbursement(any())).thenReturn(new PaymentGatewayPort.GatewayResult(true, "MM-UL", null));
        when(gateway.submitCollection(any())).thenReturn(new PaymentGatewayPort.GatewayResult(true, "MM-COL", null));
    }

    private record Sold(UUID tenant, String policyNumber) {}

    /** In force in policy year 1, holding 54,000 EQ1 and 36,000 BD1 units, on a version taking top-ups at 98%. */
    private Sold invested(boolean takesTopUps) {
        UUID tenant = UUID.randomUUID();
        var terms = UnitLinkedTestFixtures.standardTerms(List.of("EQ1", "BD1"));
        var product = fixtures.publishStandardBindingTomorrow(tenant,
            takesTopUps ? terms.withOptions(UnitLinkedTestFixtures.standardOptions()) : terms);
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        fixtures.backdate(policy, TODAY.minusDays(70));
        fixtures.collectAt(tenant, policy, "100000.00", eat(TODAY.minusDays(61), 9, 0), UUID.randomUUID());
        fixtures.priceBoth(tenant, TODAY.minusDays(60), "1.000000", "1.000000");
        return new Sold(tenant, policy);
    }

    private TopUpView topUp(Sold s, String amount, String key) {
        return asTenant(s.tenant(), () -> api.requestTopUp(s.policyNumber(),
            new TopUpInput(new BigDecimal(amount), "+255700000700", List.of()), "staff-one", key));
    }

    /** payment's confirmation of the collection, as the gateway's callback would publish it. */
    private void confirm(Sold s, UUID topUpId) {
        fixtures.publish(s.tenant(), "payment.PaymentConfirmed", Map.of("paymentRequestId", UUID.randomUUID(),
            "sourceRef", topUpId.toString(), "purpose", "UL_TOP_UP", "confirmedAt", now.get().toString(),
            "amount", Map.of("amount", "200000.00", "currencyCode", "TZS")));
    }

    @Test
    void aTopUpIsCollectedOncePerKeyAndBuysUnitsAtItsOwnRate() {
        Sold s = invested(true);
        String key = UUID.randomUUID().toString();
        TopUpView first = topUp(s, "200000.00", key);
        TopUpView again = topUp(s, "200000.00", key);                      // a retry after a timeout
        assertThat(again.topUpId()).isEqualTo(first.topUpId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payment_transaction WHERE purpose = 'UL_TOP_UP'"
            + " AND source_ref = ?", Integer.class, first.topUpId().toString())).isEqualTo(1);

        confirm(s, first.topUpId());
        PolicyUnitsView units = asTenant(s.tenant(), () -> api.units(s.policyNumber()));
        // 98% of 200,000 = 196,000 by the 60/40 split in force, waiting for tomorrow's price.
        assertThat(units.pending()).extracting(o -> o.fundCode() + ":" + o.amount().toPlainString())
            .containsExactlyInAnyOrder("EQ1:117600.00", "BD1:78400.00");
        assertThat(units.entries().stream().filter(e -> e.type().equals("ALLOCATION_CHARGE")
                && e.sourceRef().equals("top-up:" + first.topUpId()))
            .map(PolicyUnitsView.Entry::amount).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("-4000.00");
        assertThat(asTenant(s.tenant(), () -> api.listTopUps(s.policyNumber()))).singleElement()
            .satisfies(t -> assertThat(t.status()).isEqualTo("RECEIVED"));
    }

    @Test
    void aTopUpWhoseMoneyArrivesAfterThePolicyFrozeGoesBackWithTheExit() {
        Sold s = invested(true);
        TopUpView requested = topUp(s, "200000.00", UUID.randomUUID().toString());
        var surrender = asTenant(s.tenant(), () -> policyApi.requestSurrender(s.policyNumber(), "+255700000555", "staff-one"));
        asTenant(s.tenant(), () -> policyApi.approveSurrender(surrender.surrenderRequestId(), "staff-two"));

        confirm(s, requested.topUpId());                                    // money in after the freeze
        assertThat(asTenant(s.tenant(), () -> api.listTopUps(s.policyNumber()))).singleElement()
            .satisfies(t -> assertThat(t.status()).isEqualTo("REFUNDED"));
        assertThat(asTenant(s.tenant(), () -> api.units(s.policyNumber())).pending())
            .noneMatch(o -> o.purpose().equals("ALLOCATION"));             // never invested
        assertThat(jdbc.queryForObject("SELECT returned_money FROM unitlinked.exit_state WHERE policy_number = ?",
            BigDecimal.class, s.policyNumber())).isEqualByComparingTo("200000.00");
    }

    @Test
    void aTopUpBelowTheMinimumOrOnAVersionWithoutTopUpsIsRefused() {
        Sold s = invested(true);
        assertThatThrownBy(() -> topUp(s, "10000.00", UUID.randomUUID().toString()))
            .hasMessageContaining("A top-up is at least 50,000.00 TZS");
        Sold plain = invested(false);
        assertThatThrownBy(() -> topUp(plain, "200000.00", UUID.randomUUID().toString()))
            .hasMessageContaining("This product does not take top-ups");
        assertThatThrownBy(() -> topUp(s, "200000.00", " "))
            .hasMessageContaining("Idempotency-Key header is required");
    }
}
