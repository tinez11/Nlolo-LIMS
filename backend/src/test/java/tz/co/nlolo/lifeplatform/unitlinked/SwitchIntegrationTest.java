package tz.co.nlolo.lifeplatform.unitlinked;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.payment.domain.PaymentGatewayPort;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice;
import tz.co.nlolo.lifeplatform.unitlinked.api.PolicyUnitsView;
import tz.co.nlolo.lifeplatform.unitlinked.api.SwitchInput;
import tz.co.nlolo.lifeplatform.unitlinked.api.SwitchView;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;

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
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.eat;

/**
 * Fund switches (U2, spec §2): both legs priced on ONE date -- and a switch waits while any involved fund is unpriced --
 * the version's free switches each policy year, then its fee; a switch waiting when an exit freezes the policy is
 * cancelled; one waits at a time, and a version that does not offer switching refuses one.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.class})
class SwitchIntegrationTest {

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

    @BeforeEach
    void realTimeAndARailThatPays() {
        now.set(Instant.now());
        when(clock.instant()).thenAnswer(i -> now.get());
        when(clock.getZone()).thenReturn(EAT);
        when(gateway.submitDisbursement(any())).thenReturn(new PaymentGatewayPort.GatewayResult(true, "MM-UL", null));
    }

    private record Sold(UUID tenant, String policyNumber) {}

    /** 54,000 EQ1 and 36,000 BD1 units at 1.00, on a version that offers switching (2 free a year, then 5,000). */
    private Sold invested(boolean offersSwitching) {
        UUID tenant = UUID.randomUUID();
        var terms = UnitLinkedTestFixtures.standardTerms(List.of("EQ1", "BD1"));
        var product = fixtures.publishStandardBindingTomorrow(tenant,
            offersSwitching ? terms.withOptions(UnitLinkedTestFixtures.standardOptions()) : terms);
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        fixtures.backdate(policy, TODAY.minusDays(70));
        fixtures.collectAt(tenant, policy, "100000.00", eat(TODAY.minusDays(61), 9, 0), UUID.randomUUID());
        priceOne(tenant, "EQ1", TODAY.minusDays(60), "1.000000");
        priceOne(tenant, "BD1", TODAY.minusDays(60), "1.000000");
        return new Sold(tenant, policy);
    }

    /** One fund's price for {@code date}, approved after that date's cut-off (the clock only ever moves forward). */
    private void priceOne(UUID tenant, String fund, LocalDate date, String price) {
        Instant at = date.atTime(0, 0, 2).atZone(EAT).toInstant();
        if (at.isAfter(now.get())) {
            now.set(at);
        }
        fixtures.approvedPrice(tenant, fund, date, price);
    }

    private PolicyUnitsView units(Sold s) {
        return asTenant(s.tenant(), () -> api.units(s.policyNumber()));
    }

    private BigDecimal held(Sold s, String fund) {
        return units(s).holdings().stream().filter(h -> h.fundCode().equals(fund)).map(PolicyUnitsView.Holding::units)
            .findFirst().orElse(BigDecimal.ZERO);
    }

    private SwitchView requestSwitch(Sold s, String from, int percent, String to) {
        return asTenant(s.tenant(), () -> api.requestSwitch(s.policyNumber(),
            new SwitchInput(List.of(new SwitchInput.Out(from, percent)), List.of(new UnitLinkedChoice.Split(to, 100))),
            "staff-one"));
    }

    @Test
    void bothLegsPriceOnOneDateAndTheSwitchWaitsForTheSlowerFund() {
        Sold s = invested(true);
        SwitchView sw = requestSwitch(s, "EQ1", 100, "BD1");
        assertThat(sw.boundDate()).isEqualTo(TODAY.plusDays(1));      // cut-off one second past midnight

        priceOne(s.tenant(), "EQ1", TODAY.plusDays(1), "1.200000");     // only one fund priced: nothing moves
        assertThat(held(s, "EQ1")).isEqualByComparingTo("54000");
        assertThat(units(s).switches()).singleElement().satisfies(v -> assertThat(v.status()).isEqualTo("WAITING"));

        priceOne(s.tenant(), "BD1", TODAY.plusDays(1), "1.000000");     // both priced for the date: it executes
        assertThat(held(s, "EQ1")).isEqualByComparingTo("0");
        assertThat(held(s, "BD1")).isEqualByComparingTo("100800");      // 36,000 + 54,000 x 1.20
        assertThat(units(s).switches()).singleElement().satisfies(v -> {
            assertThat(v.status()).isEqualTo("EXECUTED");
            assertThat(v.executedOn()).isEqualTo(TODAY.plusDays(1));
            assertThat(v.fee()).isEqualByComparingTo("0");
        });
    }

    @Test
    void theThirdSwitchInAPolicyYearPaysTheFee() {
        Sold s = invested(true);
        for (int i = 1; i <= 3; i++) {
            requestSwitch(s, "EQ1", 50, "BD1");
            LocalDate date = TODAY.plusDays(i);
            priceOne(s.tenant(), "EQ1", date, "1.000000");
            priceOne(s.tenant(), "BD1", date, "1.000000");
        }
        List<SwitchView> done = units(s).switches();
        assertThat(done).hasSize(3).allSatisfy(v -> assertThat(v.status()).isEqualTo("EXECUTED"));
        assertThat(done).extracting(v -> v.fee().stripTrailingZeros().toPlainString())
            .containsExactlyInAnyOrder("0", "0", "5000");
        assertThat(units(s).entries().stream().filter(e -> e.type().equals("SWITCH_FEE")).map(PolicyUnitsView.Entry::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("-5000.00");
    }

    @Test
    void aSwitchWaitingWhenThePolicyFreezesIsCancelled() {
        Sold s = invested(true);
        requestSwitch(s, "EQ1", 100, "BD1");
        var request = asTenant(s.tenant(), () -> policyApi.requestSurrender(s.policyNumber(), "+255700000555", "staff-one"));
        asTenant(s.tenant(), () -> policyApi.approveSurrender(request.surrenderRequestId(), "staff-two"));
        assertThat(units(s).switches()).singleElement().satisfies(v -> assertThat(v.status()).isEqualTo("CANCELLED"));
    }

    @Test
    void oneSwitchWaitsAtATimeAndAVersionWithoutSwitchingRefusesOne() {
        Sold s = invested(true);
        requestSwitch(s, "EQ1", 100, "BD1");
        assertThatThrownBy(() -> requestSwitch(s, "BD1", 100, "EQ1"))
            .hasMessageContaining("A switch is already waiting on policy " + s.policyNumber());

        Sold plain = invested(false);
        assertThatThrownBy(() -> requestSwitch(plain, "EQ1", 100, "BD1"))
            .hasMessageContaining("This product does not offer fund switches");
    }
}
