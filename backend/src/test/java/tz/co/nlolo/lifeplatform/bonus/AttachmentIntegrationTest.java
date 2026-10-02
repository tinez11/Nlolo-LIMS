package tz.co.nlolo.lifeplatform.bonus;

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
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.DepositTestMigrations;
import tz.co.nlolo.lifeplatform.bonus.application.BonusApiImpl;
import tz.co.nlolo.lifeplatform.bonus.application.DeclarationDrain;
import tz.co.nlolo.lifeplatform.bonus.domain.Eligibility;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.AttachmentEntryRepository;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.DeclarationOutcomeRepository;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyBonus;
import tz.co.nlolo.lifeplatform.policy.infrastructure.PolicyBonusRepository;
import tz.co.nlolo.lifeplatform.product.api.BonusMethod;
import tz.co.nlolo.lifeplatform.product.api.BonusPlan;
import tz.co.nlolo.lifeplatform.product.api.BonusSurrenderBasis;
import tz.co.nlolo.lifeplatform.product.api.CashValuePlan;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Attaching: once per declaration and policy, judged on the status record, and policy's projection follows. */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(BonusTestFixtures.class)
class AttachmentIntegrationTest {

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
            DepositTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();
    // The civil date the eligibility rule judges on -- not the JVM's, which may be a day behind at 00:00-03:00.
    private static final LocalDate TODAY = LocalDate.now(Eligibility.CIVIL_ZONE);

    @Autowired private BonusTestFixtures fixtures;
    @Autowired private BonusApiImpl api;
    @Autowired private DeclarationDrain drain;
    @Autowired private AttachmentEntryRepository entries;
    @Autowired private DeclarationOutcomeRepository outcomes;
    @Autowired private PolicyBonusRepository policyBonuses;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    private UUID approvedDeclaration(UUID product, LocalDate valuation, String rate) {
        var d = asTenant(() -> api.proposeDeclaration(product, valuation, new BigDecimal(rate), new BigDecimal("50"), "admin-one"));
        asTenant(() -> api.approveDeclaration(d.declarationId(), "finance-two"));
        return d.declarationId();
    }

    private void drainOne(UUID declarationId) {
        drain.drainOne(declarationId, TENANT);
    }

    private BigDecimal policyBonusAmount(String policyNumber) {
        return policyBonuses.findById(policyNumber).map(PolicyBonus::getAttachedBonusAmount).orElse(null);
    }

    @Test
    void anApprovedDeclarationAttachesOnceAndTheProjectionFollows() {
        var issued = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        UUID declaration = approvedDeclaration(issued.productId(), TODAY, "3");
        drainOne(declaration);
        drainOne(declaration); // a re-run attaches nothing more
        var ledger = asTenant(() -> entries.findByPolicyNumberOrderBySeqAsc(issued.policyNumber()));
        assertThat(ledger).singleElement().satisfies(e -> {
            assertThat(e.getAmount()).isEqualByComparingTo("30000.00");
            assertThat(e.getBasisAmount()).isEqualByComparingTo("1000000.00");
            assertThat(e.getTotalAfter()).isEqualByComparingTo("30000.00");
            assertThat(e.getSeq()).isEqualTo(1);
        });
        assertThat(asTenant(() -> policyBonusAmount(issued.policyNumber()))).isEqualByComparingTo("30000.00");
        assertThat(asTenant(() -> api.listDeclarations(issued.productId())).get(0).completedAt()).isNotNull();
    }

    @Test
    void aSecondCompoundDeclarationIsOnTheSumAssuredPlusWhatWasAttachedBefore() {
        var issued = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        drainOne(approvedDeclaration(issued.productId(), TODAY, "3"));
        // A later valuation date: 3% of (1,000,000 + 30,000).
        drainOne(approvedDeclaration(issued.productId(), TODAY.plusDays(1), "3"));
        assertThat(asTenant(() -> entries.findByPolicyNumberOrderBySeqAsc(issued.policyNumber())))
            .extracting(e -> e.getAmount().stripTrailingZeros().toPlainString())
            .containsExactly("30000", "30900");
        assertThat(asTenant(() -> policyBonusAmount(issued.policyNumber()))).isEqualByComparingTo("60900.00");
    }

    @Test
    void aDeclarationWithAFutureValuationDateWaits() {
        var issued = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        approvedDeclaration(issued.productId(), TODAY.plusDays(10), "3");
        drain.drain(); // declarations_due() does not select it
        assertThat(asTenant(() -> entries.findByPolicyNumberOrderBySeqAsc(issued.policyNumber()))).isEmpty();
        assertThat(asTenant(() -> outcomes.findByPolicyNumberOrderByDecidedAtDesc(issued.policyNumber()))).isEmpty();
    }

    @Test
    void aPolicyLapsedOnTheValuationDateGetsAnOutcomeThatSaysWhyAndNoEntry() {
        var issued = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        fixtures.publish(TENANT, "policy.PolicyLapsed", Map.of("policyNumber", issued.policyNumber(),
            "lapsedAt", Instant.now().toString()));
        drainOne(approvedDeclaration(issued.productId(), TODAY, "3"));
        assertThat(asTenant(() -> entries.findByPolicyNumberOrderBySeqAsc(issued.policyNumber()))).isEmpty();
        assertThat(asTenant(() -> outcomes.findByPolicyNumberOrderByDecidedAtDesc(issued.policyNumber())))
            .singleElement().satisfies(o -> assertThat(o.getReason()).isEqualTo("The policy was LAPSED on " + TODAY));
        assertThat(asTenant(() -> policyBonusAmount(issued.policyNumber()))).isNull();
    }

    @Test
    void aPaidUpPolicyFollowsItsVersionsRule() {
        var off = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        var on = fixtures.issue(TENANT, new BonusPlan(true, BonusMethod.SIMPLE, true, BonusSurrenderBasis.NONE, List.of()),
            CashValuePlan.none());
        for (var p : List.of(off, on)) {
            // PolicyMadePaidUp states its sums assured as plain decimal strings, not money objects.
            fixtures.publish(TENANT, "policy.PolicyMadePaidUp", Map.of("policyNumber", p.policyNumber(),
                "paidUpSumAssured", "400000.00", "originalSumAssured", "1000000.00",
                "madePaidUpAt", Instant.now().toString()));
        }
        drainOne(approvedDeclaration(off.productId(), TODAY, "3"));
        drainOne(approvedDeclaration(on.productId(), TODAY, "3"));
        assertThat(asTenant(() -> entries.findByPolicyNumberOrderBySeqAsc(off.policyNumber()))).isEmpty();
        // On the REDUCED sum assured: 400,000 x 3%.
        assertThat(asTenant(() -> entries.findByPolicyNumberOrderBySeqAsc(on.policyNumber()))).singleElement()
            .satisfies(e -> assertThat(e.getAmount()).isEqualByComparingTo("12000.00"));
    }
}
