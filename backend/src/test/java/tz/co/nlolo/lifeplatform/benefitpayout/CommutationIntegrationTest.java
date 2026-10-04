package tz.co.nlolo.lifeplatform.benefitpayout;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures;
import tz.co.nlolo.lifeplatform.annuity.AnnuityTestMigrations;
import tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi;
import tz.co.nlolo.lifeplatform.benefitpayout.api.InstalmentStatus;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutInstalmentView;
import tz.co.nlolo.lifeplatform.benefitpayout.application.BenefitPayoutApiImpl;
import tz.co.nlolo.lifeplatform.benefitpayout.application.WithholdingRules;
import tz.co.nlolo.lifeplatform.product.api.PayoutKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * A pension's lump sum (product step 5 D2, Task 3): its own payout kind, scheduled once, never held
 * for contributions -- the account it came from has closed -- owing no proof of life, paid under its
 * own purpose, and taxed only if an approved rule names it.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AnnuityTestFixtures.class)
class CommutationIntegrationTest {

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

    private static final BigDecimal LUMP_SUM = new BigDecimal("1250000.00");
    /** Three months out: the contributions are not paid that far, so any other kind would be held. */
    private static final LocalDate DUE = TODAY.plusMonths(3);

    @Autowired private AnnuityTestFixtures fixtures;
    @Autowired private BenefitPayoutApi payouts;
    @Autowired private BenefitPayoutApiImpl engine;
    @Autowired private WithholdingRules rules;
    @Autowired private JdbcTemplate jdbc;

    /** A pension in force: one contribution collected, so the policy is IN_FORCE but paid only to today. */
    private String pension(UUID tenant) {
        var product = fixtures.publishDeferred(tenant, false);
        String policy = fixtures.issueDeferred(tenant, product, fixtures.personBorn(tenant, LocalDate.of(1980, 6, 15), null), 60);
        fixtures.collect(tenant, policy, "200000.00", TODAY);
        return policy;
    }

    private PayoutInstalmentView instalment(UUID tenant, UUID id) {
        return asTenant(tenant, () -> payouts.getInstalment(id));
    }

    @Test
    void aLumpSumIsOneScheduledCommutationAndSchedulingItAgainReturnsTheSameOne() {
        UUID tenant = UUID.randomUUID();
        String policy = pension(tenant);
        UUID id = asTenant(tenant, () -> payouts.scheduleCommutation(policy, DUE, LUMP_SUM, "TZS"));
        UUID again = asTenant(tenant, () -> payouts.scheduleCommutation(policy, DUE, LUMP_SUM, "TZS"));
        assertThat(again).isEqualTo(id);

        List<PayoutInstalmentView> lumpSums = asTenant(tenant, () -> payouts.listForPolicy(policy)).stream()
            .filter(i -> i.kind() == PayoutKind.COMMUTATION).toList();
        assertThat(lumpSums).singleElement().satisfies(i -> {
            assertThat(i.status()).isEqualTo(InstalmentStatus.SCHEDULED);
            assertThat(i.dueDate()).isEqualTo(DUE);
            assertThat(i.currentAmount()).isEqualByComparingTo(LUMP_SUM);
            assertThat(i.proofOfLifeRequired()).isFalse();
        });
    }

    @Test
    void itFallsDueUnheldOwesNoProofOfLifeAndIsPaidAsACommutation() {
        UUID tenant = UUID.randomUUID();
        String policy = pension(tenant);
        UUID id = asTenant(tenant, () -> payouts.scheduleCommutation(policy, DUE, LUMP_SUM, "TZS"));

        asTenant(tenant, () -> { engine.fallDue(id); return null; });
        assertThat(instalment(tenant, id).status()).isEqualTo(InstalmentStatus.DUE);

        // No proof-of-life method: none is owed.
        asTenant(tenant, () -> payouts.review(id, "+255700000951", null, null, "finance-reviewer"));
        PayoutInstalmentView approved = asTenant(tenant, () -> payouts.approve(id, "finance-approver"));
        assertThat(approved.netAmount()).isEqualByComparingTo(LUMP_SUM);

        String purpose = jdbc.queryForObject("SELECT purpose FROM payment.disbursement_instruction WHERE source_ref LIKE ?",
            String.class, id + "%");
        assertThat(purpose).isEqualTo("COMMUTATION_PAYOUT");
    }

    @Test
    void anApprovedRuleNamingCommutationWithholdsItsRate() {
        UUID tenant = UUID.randomUUID();
        asTenant(tenant, () -> {
            var rule = rules.propose(List.of("COMMUTATION"), new BigDecimal("10"), TODAY, null, "Income Tax Act (test)",
                "finance-one", UUID.randomUUID().toString());
            return rules.approve(rule.getRuleId(), "finance-two");
        });
        String policy = pension(tenant);
        UUID id = asTenant(tenant, () -> payouts.scheduleCommutation(policy, DUE, LUMP_SUM, "TZS"));
        asTenant(tenant, () -> {
            engine.fallDue(id);
            payouts.review(id, "+255700000952", null, null, "finance-reviewer");
            return null;
        });
        PayoutInstalmentView approved = asTenant(tenant, () -> payouts.approve(id, "finance-approver"));
        assertThat(approved.grossAmount()).isEqualByComparingTo("1250000.00");
        assertThat(approved.withheldAmount()).isEqualByComparingTo("125000.00");
        assertThat(approved.netAmount()).isEqualByComparingTo("1125000.00");
    }
}
