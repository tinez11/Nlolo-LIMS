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
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures;
import tz.co.nlolo.lifeplatform.payment.domain.PaymentGatewayPort;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;
import tz.co.nlolo.lifeplatform.unitlinked.api.DeathValueView;
import tz.co.nlolo.lifeplatform.unitlinked.api.PolicyUnitsView;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitsNotYetPricedException;
import tz.co.nlolo.lifeplatform.unitlinked.application.ChargeSweep;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.eat;

/**
 * A death on a unit-linked policy (spec §7): registration freezes it and sells its units at the first price after
 * the registration instant; the claim cannot be approved until they are priced; it pays the higher of the sum
 * assured and the proceeds (or both), plus every cost of insurance taken after the death; a rejection puts the
 * money back into units.
 *
 * <p>Claims stamps registration with the real now, so the funds' cut-off is one second past midnight: any
 * registration binds to TOMORROW. unitlinked's clock is then moved to tomorrow to approve tomorrow's price.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, FuneralTestFixtures.class})
class UnitLinkedDeathIntegrationTest {

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
    private static final LocalDate TOMORROW = TODAY.plusDays(1);
    private static final LocalDate ISSUED = TODAY.minusDays(70);
    private static final LocalDate FIRST_CHARGE = ISSUED.plusMonths(1);

    @MockBean(name = "unitLinkedClock") private Clock clock;
    @MockBean private PaymentGatewayPort gateway;
    private final AtomicReference<Instant> now = new AtomicReference<>();

    @Autowired private UnitLinkedTestFixtures fixtures;
    @Autowired private FuneralTestFixtures people;
    @Autowired private UnitLinkedApi api;
    @Autowired private ClaimsApi claimsApi;
    @Autowired private ChargeSweep sweep;

    @BeforeEach
    void realTimeAndARailThatPays() {
        now.set(Instant.now());
        when(clock.instant()).thenAnswer(i -> now.get());
        when(clock.getZone()).thenReturn(EAT);
        when(gateway.submitDisbursement(any())).thenReturn(new PaymentGatewayPort.GatewayResult(true, "MM-UL", null));
    }

    private record Sold(UUID tenant, String policyNumber) {}

    /** Issued 70 days ago, its 100,000 premium bought at 1.000000, and one month's charges taken at 1.000000. */
    private Sold invested(UnitLinkedPlan terms) {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandardBindingTomorrow(tenant, terms);
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        fixtures.backdate(policy, ISSUED);
        fixtures.collectAt(tenant, policy, "100000.00", eat(ISSUED, 9, 0), UUID.randomUUID());
        fixtures.priceBoth(tenant, ISSUED.plusDays(1), "1.000000", "1.000000");
        sweep.sweepOne(policy, tenant, FIRST_CHARGE);
        fixtures.priceBoth(tenant, FIRST_CHARGE.plusDays(1), "1.000000", "1.000000");
        return new Sold(tenant, policy);
    }

    private UUID registerDeath(Sold s, LocalDate dateOfDeath) {
        UUID claimant = fixtures.person(s.tenant(), 33);
        return asTenant(s.tenant(), () -> claimsApi.registerClaim(new ClaimsApi.RegisterClaimRequest(s.policyNumber(), null,
            claimant, ClaimType.DEATH, dateOfDeath, new DeathClaimDetails("Heart failure", "Dar es Salaam", dateOfDeath,
                "Dr. Test", null)), UUID.randomUUID().toString(), "claims-clerk").claimId());
    }

    private void priceTomorrow(Sold s, String eq, String bd) {
        now.set(TOMORROW.atTime(0, 0, 2).atZone(EAT).toInstant());
        fixtures.priceBoth(s.tenant(), TOMORROW, eq, bd);
    }

    private PolicyUnitsView units(Sold s) {
        return asTenant(s.tenant(), () -> api.units(s.policyNumber()));
    }

    @Test
    void registrationFreezesAndApprovalWaitsForTheFirstPriceAfterIt() {
        Sold s = invested(UnitLinkedTestFixtures.standardTerms(java.util.List.of("EQ1", "BD1")));
        UUID claim = registerDeath(s, TODAY.minusDays(1));

        assertThat(units(s).frozen()).isTrue();
        assertThat(units(s).pending()).isNotEmpty().allSatisfy(o -> {
            assertThat(o.purpose()).isEqualTo("DEATH");
            assertThat(o.boundDate()).isEqualTo(TOMORROW); // after the registration, never a price already known
        });
        asTenant(s.tenant(), () -> claimsApi.submitAssessment(claim, "Death certificate seen", null, null, false,
            "claims-assessor", null));
        assertThatThrownBy(() -> asTenant(s.tenant(), () -> api.deathValue(claim, TODAY.minusDays(1))))
            .isInstanceOf(UnitsNotYetPricedException.class)
            .hasMessageContaining("waiting for the unit prices of " + TOMORROW);

        priceTomorrow(s, "1.200000", "1.000000");

        DeathValueView value = asTenant(s.tenant(), () -> api.deathValue(claim, TODAY.minusDays(1)));
        assertThat(value.deathRule()).isEqualTo("HIGHER_OF");
        assertThat(value.proceeds()).isPositive().isLessThan(value.sumAssured());
        assertThat(value.benefit()).isEqualByComparingTo(value.sumAssured()); // the fund is smaller: the cover pays
        asTenant(s.tenant(), () -> {
            claimsApi.decideSettlement(claim, true, value.benefit(), "TZS", null, "+255700000777",
                UUID.randomUUID().toString(), "claims-manager");
            return null;
        });
        assertThat(asTenant(s.tenant(), () -> claimsApi.getClaim(claim)).status()).isEqualTo(ClaimStatus.SETTLED);
    }

    @Test
    void sumAssuredPlusFundPaysBoth() {
        Sold s = invested(UnitLinkedTestFixtures.withDeathRule(UnitLinkedPlan.DeathRule.SUM_ASSURED_PLUS_FUND));
        UUID claim = registerDeath(s, TODAY.minusDays(1));
        priceTomorrow(s, "1.000000", "1.000000");
        DeathValueView value = asTenant(s.tenant(), () -> api.deathValue(claim, TODAY.minusDays(1)));
        assertThat(value.benefit()).isEqualByComparingTo(value.sumAssured().add(value.proceeds()).add(value.costOfInsuranceRefund()));
    }

    @Test
    void costOfInsuranceTakenAfterTheDeathIsRefundedIntoTheClaim() {
        Sold s = invested(UnitLinkedTestFixtures.standardTerms(java.util.List.of("EQ1", "BD1")));
        BigDecimal coiTaken = units(s).entries().stream().filter(e -> e.type().equals("COST_OF_INSURANCE"))
            .map(e -> e.amount().negate()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(coiTaken).isPositive();
        // Died five days before that month's charge: the charge was for cover the life no longer had.
        UUID claim = registerDeath(s, FIRST_CHARGE.minusDays(5));
        priceTomorrow(s, "1.000000", "1.000000");
        DeathValueView value = asTenant(s.tenant(), () -> api.deathValue(claim, FIRST_CHARGE.minusDays(5)));
        assertThat(value.costOfInsuranceRefund()).isEqualByComparingTo(coiTaken);
        assertThat(value.benefit()).isEqualByComparingTo(value.sumAssured().max(value.proceeds()).add(coiTaken));
    }

    @Test
    void aPremiumWaitingAtTheDeathIsReturnedNotInvested() {
        Sold s = invested(UnitLinkedTestFixtures.standardTerms(java.util.List.of("EQ1", "BD1")));
        // Collected today, so waiting for tomorrow's price -- and then the death is registered.
        fixtures.collectAt(s.tenant(), s.policyNumber(), "50000.00", Instant.now(), UUID.randomUUID());
        assertThat(units(s).pending()).anySatisfy(o -> assertThat(o.purpose()).isEqualTo("ALLOCATION"));
        UUID claim = registerDeath(s, TODAY.minusDays(1));
        assertThat(units(s).pending()).noneSatisfy(o -> assertThat(o.purpose()).isEqualTo("ALLOCATION"));

        priceTomorrow(s, "1.000000", "1.000000");
        DeathValueView value = asTenant(s.tenant(), () -> api.deathValue(claim, TODAY.minusDays(1)));
        BigDecimal sold = units(s).entries().stream().filter(e -> e.type().equals("DEATH_SALE"))
            .map(e -> e.amount().negate()).reduce(BigDecimal.ZERO, BigDecimal::add);
        // The 50,000 premium never reached units, so it comes back WHOLE -- its 10% allocation charge refunded with it
        // (the user's decision, 2026-10-05: a premium that bought no unit earns no allocation charge).
        assertThat(value.proceeds()).isEqualByComparingTo(sold.add(new BigDecimal("50000.00")));
    }

    @Test
    void aRejectedDeathClaimPutsTheMoneyBackIntoUnitsAndUnfreezes() {
        Sold s = invested(UnitLinkedTestFixtures.standardTerms(java.util.List.of("EQ1", "BD1")));
        UUID claim = registerDeath(s, TODAY.minusDays(1));
        priceTomorrow(s, "1.000000", "1.000000");
        assertThat(units(s).holdings()).allSatisfy(h -> assertThat(h.units()).isEqualByComparingTo("0"));

        asTenant(s.tenant(), () -> {
            claimsApi.submitAssessment(claim, "Not a covered death", null, null, false, "claims-assessor", null);
            claimsApi.decideSettlement(claim, false, null, null, "Death not established", null,
                UUID.randomUUID().toString(), "claims-manager");
            return null;
        });

        assertThat(units(s).frozen()).isFalse();
        assertThat(units(s).pending()).isNotEmpty().allSatisfy(o -> assertThat(o.purpose()).isEqualTo("REINVESTMENT"));
    }
}
