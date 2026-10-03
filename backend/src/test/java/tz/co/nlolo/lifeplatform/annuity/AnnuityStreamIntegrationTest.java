package tz.co.nlolo.lifeplatform.annuity;

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
import tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi;
import tz.co.nlolo.lifeplatform.benefitpayout.api.InstalmentStatus;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutInstalmentView;
import tz.co.nlolo.lifeplatform.benefitpayout.application.BenefitPayoutApiImpl;
import tz.co.nlolo.lifeplatform.product.api.AnnuityTiming;
import tz.co.nlolo.lifeplatform.product.api.PayoutKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * An annuity's open-ended stream in benefitpayout (product step 5, Task 3): expanded a year ahead,
 * rolled forward exactly once, and ended, reduced or redirected by a death.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AnnuityTestFixtures.class)
class AnnuityStreamIntegrationTest {

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
            AnnuityTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();
    private static final BigDecimal BASE = new BigDecimal("294000.00");

    @Autowired private AnnuityTestFixtures fixtures;
    @Autowired private BenefitPayoutApi payouts;
    @Autowired private BenefitPayoutApiImpl engine;

    /** An in-force annuity policy with its stream opened, first payment a month from today. */
    private String withStream(String escalation) {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.plan(AnnuityTiming.ARREARS, AnnuityTestFixtures.lifeOnly()));
        String policy = fixtures.issueInForce(TENANT, product, fixtures.person(TENANT, 61, null), "50000000.00");
        asTenant(TENANT, () -> payouts.openAnnuityStream(policy, TODAY.plusMonths(1), "MONTHLY", BASE, "TZS",
            new BigDecimal(escalation), 12));
        return policy;
    }

    private List<PayoutInstalmentView> annuityRows(String policy) {
        return asTenant(TENANT, () -> payouts.listForPolicy(policy)).stream()
            .filter(i -> i.kind() == PayoutKind.ANNUITY).toList();
    }

    @Test
    void aStreamIsExpandedTwelveMonthsAheadAsAnnuityInstalments() {
        String policy = withStream("0");
        List<PayoutInstalmentView> rows = annuityRows(policy);
        assertThat(rows).hasSize(12).allSatisfy(i -> {
            assertThat(i.status()).isEqualTo(InstalmentStatus.SCHEDULED);
            assertThat(i.currentAmount()).isEqualByComparingTo("294000.00");
            assertThat(i.streamId()).isNotNull();
        });
        assertThat(rows.get(0).dueDate()).isEqualTo(TODAY.plusMonths(1));
        assertThat(rows.get(11).dueDate()).isEqualTo(TODAY.plusMonths(12));
    }

    @Test
    void openingTwiceOpensOnce() {
        String policy = withStream("0");
        UUID again = asTenant(TENANT, () -> payouts.openAnnuityStream(policy, TODAY.plusMonths(1), "MONTHLY", BASE, "TZS",
            BigDecimal.ZERO, 12));
        assertThat(again).isEqualTo(annuityRows(policy).get(0).streamId());
        assertThat(annuityRows(policy)).hasSize(12);
    }

    @Test
    void rollingForwardAddsTheNextMonthsOnceAndEscalatesOnTheAnniversary() {
        String policy = withStream("3");
        UUID stream = annuityRows(policy).get(0).streamId();
        LocalDate horizon = TODAY.plusMonths(14);
        asTenant(TENANT, () -> { engine.rollForward(stream, horizon); return null; });
        asTenant(TENANT, () -> { engine.rollForward(stream, horizon); return null; });
        List<PayoutInstalmentView> rows = annuityRows(policy);
        assertThat(rows).hasSize(14);
        // The 13th payment is the first anniversary of the first: escalated once.
        assertThat(rows.get(11).currentAmount()).isEqualByComparingTo("294000.00");
        assertThat(rows.get(12).currentAmount()).isEqualByComparingTo("302820.00");
    }

    @Test
    void reducingHalvesWhatIsStillAheadAndLeavesWhatCameBefore() {
        String policy = withStream("0");
        LocalDate from = TODAY.plusMonths(6);
        asTenant(TENANT, () -> { payouts.reduceAnnuityStream(policy, from, new BigDecimal("50")); return null; });
        List<PayoutInstalmentView> rows = annuityRows(policy);
        assertThat(rows.stream().filter(i -> i.dueDate().isBefore(from)))
            .allSatisfy(i -> assertThat(i.currentAmount()).isEqualByComparingTo("294000.00"));
        assertThat(rows.stream().filter(i -> !i.dueDate().isBefore(from)))
            .allSatisfy(i -> {
                assertThat(i.currentAmount()).isEqualByComparingTo("147000.00");
                assertThat(i.restatementReason()).startsWith("The survivor's 50%");
            });
    }

    @Test
    void redirectingPaysUntilTheGuaranteeEndsAndNothingAfter() {
        String policy = withStream("0");
        LocalDate until = TODAY.plusMonths(4);
        asTenant(TENANT, () -> { payouts.redirectAnnuityStream(policy, TODAY.plusMonths(1), until, "+255700000555"); return null; });
        List<PayoutInstalmentView> rows = annuityRows(policy);
        assertThat(rows.stream().filter(i -> i.status() != InstalmentStatus.CANCELLED)).hasSize(4);
        assertThat(rows.stream().filter(i -> i.dueDate().isAfter(until)))
            .allSatisfy(i -> assertThat(i.status()).isEqualTo(InstalmentStatus.CANCELLED));
        // Rolling forward never reaches past the guarantee.
        UUID stream = rows.get(0).streamId();
        asTenant(TENANT, () -> { engine.rollForward(stream, TODAY.plusMonths(24)); return null; });
        assertThat(annuityRows(policy)).hasSize(12);
    }

    @Test
    void endingCancelsEverythingAfterTheDate() {
        String policy = withStream("0");
        asTenant(TENANT, () -> { payouts.endAnnuityStream(policy, TODAY.plusMonths(2), "The annuitant died"); return null; });
        List<PayoutInstalmentView> rows = annuityRows(policy);
        assertThat(rows.stream().filter(i -> i.status() == InstalmentStatus.SCHEDULED)).hasSize(2);
        assertThat(rows.stream().filter(i -> i.status() == InstalmentStatus.CANCELLED))
            .hasSize(10).allSatisfy(i -> assertThat(i.statusReason()).isEqualTo("The annuitant died"));
    }

    @Test
    void theStreamValuesWhatIsStillToComeBeyondWhatItHasExpanded() {
        String policy = withStream("0");
        // 24 monthly payments after today up to two years out -- only 12 exist as rows.
        assertThat(asTenant(TENANT, () -> payouts.annuityScheduledGrossBetween(policy, TODAY, TODAY.plusMonths(24))))
            .isEqualByComparingTo("7056000.00");
        assertThat(asTenant(TENANT, () -> payouts.annuityPaidGross(policy))).isEqualByComparingTo("0.00");
    }
}
