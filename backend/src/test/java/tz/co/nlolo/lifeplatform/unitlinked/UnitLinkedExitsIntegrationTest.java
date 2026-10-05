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
import tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi;
import tz.co.nlolo.lifeplatform.benefitpayout.api.FreeLookCancellationView;
import tz.co.nlolo.lifeplatform.benefitpayout.api.FreeLookDeductionInput;
import tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures;
import tz.co.nlolo.lifeplatform.payment.domain.PaymentGatewayPort;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.unitlinked.api.PolicyUnitsView;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;
import tz.co.nlolo.lifeplatform.unitlinked.application.MaturitySweep;

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
 * A surrender, a maturity and a free-look on a unit-linked policy (spec §7): each freezes the policy and sells every
 * unit FORWARD; a surrender binds at its approval, a maturity at its own date; a free-look refunds the actual
 * entries. Funds cut off one second past midnight, so anything done today binds to tomorrow, and unitlinked's clock
 * is moved to price it.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, FuneralTestFixtures.class})
class UnitLinkedExitsIntegrationTest {

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
    @Autowired private BenefitPayoutApi payoutApi;
    @Autowired private MaturitySweep maturitySweep;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void realTimeAndARailThatPays() {
        now.set(Instant.now());
        when(clock.instant()).thenAnswer(i -> now.get());
        when(clock.getZone()).thenReturn(EAT);
        when(gateway.submitDisbursement(any())).thenReturn(new PaymentGatewayPort.GatewayResult(true, "MM-UL", null));
    }

    private record Sold(UUID tenant, String policyNumber) {}

    /** A premium bought on {@code boughtOn}: collected the day before, priced on it. */
    private Sold invested(LocalDate issued, LocalDate boughtOn) {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandardBindingTomorrow(tenant,
            UnitLinkedTestFixtures.standardTerms(List.of("EQ1", "BD1")));
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        fixtures.backdate(policy, issued);
        fixtures.collectAt(tenant, policy, "100000.00", eat(boughtOn.minusDays(1), 9, 0), UUID.randomUUID());
        priceOn(tenant, boughtOn, "1.000000", "1.000000");
        return new Sold(tenant, policy);
    }

    private void priceOn(UUID tenant, LocalDate date, String eq, String bd) {
        Instant at = date.atTime(0, 0, 2).atZone(EAT).toInstant();
        if (at.isAfter(now.get())) {
            now.set(at); // only ever forward: a price for a later day needs the clock at that day's cut-off
        }
        fixtures.priceBoth(tenant, date, eq, bd);
    }

    private PolicyUnitsView units(Sold s) {
        return asTenant(s.tenant(), () -> api.units(s.policyNumber()));
    }

    private BigDecimal sum(PolicyUnitsView v, String type) {
        return v.entries().stream().filter(e -> e.type().equals(type)).map(PolicyUnitsView.Entry::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void aSurrenderBindsAtApprovalSellsForwardAndIsPaid() {
        Sold s = invested(TODAY.minusDays(70), TODAY.minusDays(60));
        // Forward pricing: no value exists to quote, so the quote is refused rather than a zero shown as the value.
        assertThatThrownBy(() -> asTenant(s.tenant(), () -> policyApi.quoteSurrenderValue(s.policyNumber())))
            .hasMessageContaining("no value can be quoted now");
        var request = asTenant(s.tenant(), () -> policyApi.requestSurrender(s.policyNumber(), "+255700000555", "staff-one"));
        assertThat(request.quotedValueAmount()).isNull(); // priced forward: nothing to quote (plan R11)

        Instant approvedFrom = now.get();
        asTenant(s.tenant(), () -> policyApi.approveSurrender(request.surrenderRequestId(), "staff-two"));
        PolicyUnitsView waiting = units(s);
        assertThat(waiting.frozen()).isTrue();
        assertThat(waiting.pending()).isNotEmpty().allSatisfy(o -> {
            assertThat(o.purpose()).isEqualTo("SURRENDER");
            assertThat(o.receivedAt()).isAfterOrEqualTo(approvedFrom); // the approval's instant, not the request's
            assertThat(o.boundDate()).isEqualTo(TODAY.plusDays(1));
        });

        priceOn(s.tenant(), TODAY.plusDays(1), "1.100000", "1.000000");

        PolicyUnitsView sold = units(s);
        assertThat(sold.holdings()).allSatisfy(h -> assertThat(h.units()).isEqualByComparingTo("0"));
        assertThat(sum(sold, "SURRENDER_SALE").negate()).isEqualByComparingTo("95400.00"); // 54,000 x 1.10 + 36,000
        assertThat(asTenant(s.tenant(), () -> policyApi.findLatestSurrenderRequest(s.policyNumber())).orElseThrow().status())
            .isEqualTo("PAID");
    }

    @Test
    void aCorrectedSurrenderAlreadyPaidBecomesAnAdjustmentNotAClawback() {
        Sold s = invested(TODAY.minusDays(70), TODAY.minusDays(60));
        var request = asTenant(s.tenant(), () -> policyApi.requestSurrender(s.policyNumber(), "+255700000555", "staff-one"));
        asTenant(s.tenant(), () -> policyApi.approveSurrender(request.surrenderRequestId(), "staff-two"));
        priceOn(s.tenant(), TODAY.plusDays(1), "1.100000", "1.000000"); // EQ1 should have been 1.000000
        assertThat(asTenant(s.tenant(), () -> policyApi.findLatestSurrenderRequest(s.policyNumber())).orElseThrow().status())
            .isEqualTo("PAID");

        var wrong = asTenant(s.tenant(), () -> api.listPrices("EQ1", TODAY.plusDays(1), TODAY.plusDays(1))).get(0);
        asTenant(s.tenant(), () -> {
            var proposed = api.proposeCorrection(wrong.priceId(), new BigDecimal("1.000000"), "Feed typo", UnitLinkedTestFixtures.FINANCE);
            return api.approvePrice(proposed.priceId(), UnitLinkedTestFixtures.ADMIN);
        });

        var open = asTenant(s.tenant(), () -> api.listAdjustments("OPEN"));
        assertThat(open).singleElement().satisfies(a -> {
            assertThat(a.policyNumber()).isEqualTo(s.policyNumber());
            assertThat(a.direction()).isEqualTo("OWED_BY_CUSTOMER");
            assertThat(a.amount()).isEqualByComparingTo("5400.00"); // 54,000 units x 0.10 paid too much
        });
        // The payment that went out is not touched; a second person settles or waives the difference.
        assertThatThrownBy(() -> asTenant(s.tenant(), () -> api.waiveAdjustment(open.get(0).adjustmentId(), "Too small",
                UnitLinkedTestFixtures.ADMIN)))
            .hasMessageContaining("a second person settles or waives it");
        assertThat(asTenant(s.tenant(), () -> api.waiveAdjustment(open.get(0).adjustmentId(), "Below the recovery threshold",
            UnitLinkedTestFixtures.FINANCE)).status()).isEqualTo("WAIVED");
    }

    @Test
    void aSurrenderInsideTheMinimumYearsIsRefused() {
        UUID tenant = UUID.randomUUID();
        var terms = UnitLinkedTestFixtures.standardTerms(List.of("EQ1", "BD1"));
        var strict = tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan.of(terms.fundCodes(), terms.allocationBands(),
            terms.monthlyPolicyFee(), terms.mortalityBasis(), terms.mortality(), terms.deathRule(), terms.lapseRule(),
            terms.minimumPremiumYears(), 2, terms.lowFundWarningMonths(), terms.premiumMinimums(),
            terms.sumAssuredMultipleMin(), terms.sumAssuredMultipleMax());
        var product = fixtures.publishStandardBindingTomorrow(tenant, strict);
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        fixtures.collectAt(tenant, policy, "100000.00", Instant.now(), UUID.randomUUID());
        assertThatThrownBy(() -> asTenant(tenant, () -> policyApi.requestSurrender(policy, "+255700000555", "staff-one")))
            .hasMessageContaining("has no surrender value until 2 years");
    }

    @Test
    void aMaturitySellsAtTheMaturityDatesOwnPriceAndClosesThePolicyMatured() {
        Sold s = invested(TODAY.minusDays(70), TODAY.minusDays(60));
        LocalDate maturity = TODAY.minusDays(1);
        // A two-month term ending yesterday: policy_maturity_matches_term pins the date to commencement + term.
        jdbc.update("UPDATE policy.policy SET commencement_date = ?, policy_term_months = 2, maturity_date = ? WHERE policy_number = ?",
            maturity.minusMonths(2), maturity, s.policyNumber());

        maturitySweep.sweepOne(s.policyNumber(), s.tenant(), TODAY);
        assertThat(units(s).pending()).isNotEmpty().allSatisfy(o -> {
            assertThat(o.purpose()).isEqualTo("MATURITY");
            assertThat(o.boundDate()).isEqualTo(maturity);
        });
        priceOn(s.tenant(), maturity, "1.050000", "1.000000");

        assertThat(sum(units(s), "MATURITY_SALE").negate()).isEqualByComparingTo("92700.00"); // 54,000 x 1.05 + 36,000
        assertThat(asTenant(s.tenant(), () -> policyApi.getPolicy(s.policyNumber())).status().name()).isEqualTo("MATURED");
    }

    @Test
    void aFreeLookRefundIsTheUnwoundEntriesNotAFormula() {
        // Issued today: inside the 14-day free-look. The premium buys at tomorrow's price.
        Sold s = invested(TODAY, TODAY.plusDays(1));
        FreeLookCancellationView requested = asTenant(s.tenant(), () ->
            payoutApi.requestFreeLook(s.policyNumber(), "+255700000555", List.of(), "staff-one"));
        assertThat(requested.refundAmount()).isEqualByComparingTo("0.00"); // known only once the units are sold
        asTenant(s.tenant(), () -> payoutApi.approveFreeLook(requested.cancellationId(), "staff-two"));

        // Cancelled now, so its sale binds to tomorrow -- whose price was approved BEFORE the sale existed, so it is
        // not used: the first price approved after it, the day after's, prices it.
        priceOn(s.tenant(), TODAY.plusDays(2), "1.300000", "0.900000");

        PolicyUnitsView unwound = units(s);
        BigDecimal refunds = sum(unwound, "CHARGE_REFUND");
        BigDecimal sales = sum(unwound, "FREE_LOOK_SALE").negate();
        assertThat(refunds).isEqualByComparingTo("10000.00");               // the allocation charge, given back
        assertThat(sales).isEqualByComparingTo("102600.00");                // 54,000 x 1.30 + 36,000 x 0.90
        BigDecimal refund = asTenant(s.tenant(), () -> payoutApi.findFreeLook(s.policyNumber())).orElseThrow().refundAmount();
        assertThat(refund).isEqualByComparingTo(refunds.add(sales));        // the real entries, nothing else
        assertThat(refund).isNotEqualByComparingTo("100000.00");            // and genuinely moved by the market
    }

    @Test
    void aUnitLinkedFreeLookTakesNoDeductions() {
        Sold s = invested(TODAY, TODAY.plusDays(1));
        assertThatThrownBy(() -> asTenant(s.tenant(), () -> payoutApi.requestFreeLook(s.policyNumber(), "+255700000555",
                List.of(new FreeLookDeductionInput("Medical exam", new BigDecimal("1000.00"), null)), "staff-one")))
            .hasMessageContaining("not deductions");
    }
}
