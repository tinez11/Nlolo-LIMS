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
import tz.co.nlolo.lifeplatform.annuity.api.AnnuityApi;
import tz.co.nlolo.lifeplatform.annuity.api.AnnuityContractView;
import tz.co.nlolo.lifeplatform.annuity.api.ContractStatus;
import tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi;
import tz.co.nlolo.lifeplatform.benefitpayout.api.InstalmentStatus;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutInstalmentView;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.product.api.AnnuityTiming;
import tz.co.nlolo.lifeplatform.product.api.PayoutKind;
import tz.co.nlolo.lifeplatform.underwriting.api.AnnuityChoice;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * The annuity contract (product step 5, Task 5): created from the case's choice at issue, its income
 * locked once when the single premium arrives, the stream opened -- and an ordinary policy never touched.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AnnuityTestFixtures.class)
class AnnuityLockIntegrationTest {

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
    private static final String PRICE = "50000000.00";

    @Autowired private AnnuityTestFixtures fixtures;
    @Autowired private AnnuityApi annuityApi;
    @Autowired private BenefitPayoutApi payouts;
    @Autowired private PolicyApi policyApi;

    private AnnuityContractView contract(String policy) {
        return asTenant(TENANT, () -> annuityApi.contract(policy)).orElseThrow();
    }

    private List<PayoutInstalmentView> annuityRows(String policy) {
        return asTenant(TENANT, () -> payouts.listForPolicy(policy)).stream().filter(i -> i.kind() == PayoutKind.ANNUITY).toList();
    }

    @Test
    void issueCreatesAContractAwaitingPaymentWithItsFormCopied() {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.everyForm());
        String policy = fixtures.buy(TENANT, product, fixtures.person(TENANT, 61, Sex.FEMALE), PRICE,
            AnnuityChoice.of("LIFE-10G", "MONTHLY", null));
        AnnuityContractView c = contract(policy);
        assertThat(c.status()).isEqualTo(ContractStatus.AWAITING_PAYMENT);
        assertThat(c.formCode()).isEqualTo("LIFE-10G");
        assertThat(c.guaranteeYears()).isEqualTo(10);
        assertThat(c.purchasePrice()).isEqualByComparingTo(PRICE);
        assertThat(c.instalment()).isNull();
        assertThat(annuityRows(policy)).isEmpty();
    }

    @Test
    void collectionLocksTheIncomeAndOpensTheStreamInArrears() {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.everyForm());
        String policy = fixtures.buy(TENANT, product, fixtures.person(TENANT, 61, null), PRICE,
            AnnuityChoice.of("LIFE-0G", "MONTHLY", null));
        fixtures.collect(TENANT, policy, PRICE, TODAY);

        AnnuityContractView c = contract(policy);
        assertThat(c.status()).isEqualTo(ContractStatus.IN_PAYMENT);
        // Age 61: 74 per mille. 50,000,000 x 74 x 0.98 / 12,000 = 302,166.666... -> 302,166.67, rounded once.
        assertThat(c.annuitantAge()).isEqualTo(61);
        assertThat(c.annualRatePerMille()).isEqualByComparingTo("74");
        assertThat(c.annualIncome()).isEqualByComparingTo("3700000.00");
        assertThat(c.instalment()).isEqualByComparingTo("302166.67");
        assertThat(c.lockedOn()).isEqualTo(TODAY);
        assertThat(c.firstDueDate()).isEqualTo(TODAY.plusMonths(1));
        assertThat(c.guaranteeEndDate()).isNull();
        assertThat(annuityRows(policy)).hasSize(12)
            .allSatisfy(i -> assertThat(i.currentAmount()).isEqualByComparingTo("302166.67"));
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policy)).status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void inAdvanceTheFirstPaymentIsDueOnCollectionAndTheGuaranteeRunsFromIt() {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.plan(AnnuityTiming.ADVANCE, AnnuityTestFixtures.guaranteed10()));
        String policy = fixtures.buy(TENANT, product, fixtures.person(TENANT, 61, null), PRICE,
            AnnuityChoice.of("LIFE-10G", "MONTHLY", null));
        fixtures.collect(TENANT, policy, PRICE, TODAY);
        AnnuityContractView c = contract(policy);
        assertThat(c.firstDueDate()).isEqualTo(TODAY);
        assertThat(c.guaranteeEndDate()).isEqualTo(TODAY.plusYears(10));
        assertThat(annuityRows(policy).get(0).dueDate()).isEqualTo(TODAY);
    }

    @Test
    void aSecondCollectionLocksNothingTwice() {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.everyForm());
        String policy = fixtures.buy(TENANT, product, fixtures.person(TENANT, 61, null), PRICE,
            AnnuityChoice.of("LIFE-0G", "MONTHLY", null));
        fixtures.collect(TENANT, policy, PRICE, TODAY);
        fixtures.collect(TENANT, policy, PRICE, TODAY.plusDays(3));
        assertThat(contract(policy).lockedOn()).isEqualTo(TODAY);
        assertThat(annuityRows(policy)).hasSize(12);
    }

    @Test
    void anAnnuityIssuedWithoutACaseIsVisibleAsLockFailedAndPaysNothing() {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.everyForm());
        String policy = fixtures.issueInForce(TENANT, product, fixtures.person(TENANT, 61, null), PRICE);
        AnnuityContractView c = contract(policy);
        assertThat(c.status()).isEqualTo(ContractStatus.LOCK_FAILED);
        assertThat(c.lockFailureReason()).startsWith("An annuity is issued from an underwriting case");
        fixtures.collect(TENANT, policy, PRICE, TODAY);
        assertThat(annuityRows(policy)).isEmpty();
    }

    @Test
    void anOrdinaryPolicyNeverBecomesAnAnnuity() {
        var wholeLife = fixtures.publishOrdinary(TENANT);
        UUID party = fixtures.person(TENANT, 40, null);
        String policy = asTenant(TENANT, () -> policyApi.issuePolicy(UUID.randomUUID(), new PolicyApi.IssueRequest(party,
            wholeLife.productId(), wholeLife.versionId(), new java.math.BigDecimal("1000000.00"), "TZS",
            new java.math.BigDecimal("5000.00"), "TZS", "MONTHLY", null, List.of(), "test", null, null, null, null,
            tz.co.nlolo.lifeplatform.policy.api.IssuanceBasis.MIGRATION), "staff").policyNumber());
        fixtures.collect(TENANT, policy, "5000.00", TODAY);
        assertThat(asTenant(TENANT, () -> annuityApi.isAnnuity(policy))).isFalse();
        assertThat(asTenant(TENANT, () -> annuityApi.contract(policy))).isEmpty();
    }

    @Test
    void aFreeLookCancellationEndsTheStreamAndCancelsTheContract() {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.everyForm());
        String policy = fixtures.buy(TENANT, product, fixtures.person(TENANT, 61, null), PRICE,
            AnnuityChoice.of("LIFE-0G", "MONTHLY", null));
        fixtures.collect(TENANT, policy, PRICE, TODAY);
        fixtures.publish(TENANT, "policy.PolicyCancelledFreeLook", Map.of("policyNumber", policy,
            "cancelledAt", Instant.now().toString(), "cancelledBy", "staff-one"));
        assertThat(contract(policy).status()).isEqualTo(ContractStatus.CANCELLED);
        assertThat(annuityRows(policy)).allSatisfy(i -> assertThat(i.status()).isEqualTo(InstalmentStatus.CANCELLED));
    }
}
