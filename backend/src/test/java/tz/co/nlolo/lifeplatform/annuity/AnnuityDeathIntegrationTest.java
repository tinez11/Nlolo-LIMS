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
import tz.co.nlolo.lifeplatform.benefitpayout.api.ProofOfLifeMethod;
import tz.co.nlolo.lifeplatform.benefitpayout.application.BenefitPayoutApiImpl;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.product.api.AnnuityForm;
import tz.co.nlolo.lifeplatform.product.api.AnnuityRateBasis;
import tz.co.nlolo.lifeplatform.product.api.AnnuityRateRow;
import tz.co.nlolo.lifeplatform.product.api.AnnuityTiming;
import tz.co.nlolo.lifeplatform.product.api.PayoutKind;
import tz.co.nlolo.lifeplatform.underwriting.api.AnnuityChoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * Death through the death claim (product step 5, Task 6): the claim's ceiling from the annuity, a
 * zero approval settled with nothing to pay, and what each death does to the stream and the contract.
 *
 * <p>Every annuity here is bought, then its premium collected three months ago, in ADVANCE: so
 * instalments are already due and can be paid before the death. At age 60 on that date the life-only
 * rate is 72, so each instalment is 50,000,000 x 72 x 0.98 / 12,000 = 294,000.00.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AnnuityTestFixtures.class)
class AnnuityDeathIntegrationTest {

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
    private static final LocalDate COLLECTED = TODAY.minusMonths(3);

    @Autowired private AnnuityTestFixtures fixtures;
    @Autowired private AnnuityApi annuityApi;
    @Autowired private BenefitPayoutApi payouts;
    @Autowired private BenefitPayoutApiImpl engine;
    @Autowired private ClaimsApi claimsApi;
    @Autowired private PolicyApi policyApi;

    private static AnnuityForm capitalProtected() {
        return new AnnuityForm("LIFE-CP", 0, false, null, BigDecimal.ZERO, true, AnnuityRateBasis.UNISEX, List.of(
            new AnnuityRateRow(null, 60, null, null, new BigDecimal("72")),
            new AnnuityRateRow(null, 61, null, null, new BigDecimal("74")),
            new AnnuityRateRow(null, 62, null, null, new BigDecimal("76"))));
    }

    private AnnuityTestFixtures.Product product() {
        return fixtures.publish(TENANT, AnnuityTestFixtures.plan(AnnuityTiming.ADVANCE, AnnuityTestFixtures.lifeOnly(),
            AnnuityTestFixtures.guaranteed10(), AnnuityTestFixtures.joint50(), capitalProtected()));
    }

    /** Bought, collected three months ago, and the first {@code paid} instalments paid. */
    private String inPayment(AnnuityChoice choice, UUID annuitant, int paid) {
        String policy = fixtures.buy(TENANT, product(), annuitant, PRICE, choice);
        fixtures.collect(TENANT, policy, PRICE, COLLECTED);
        List<PayoutInstalmentView> rows = rows(policy);
        for (int n = 0; n < paid; n++) {
            UUID id = rows.get(n).instalmentId();
            asTenant(TENANT, () -> {
                engine.fallDue(id);
                payouts.review(id, "+255700000777", ProofOfLifeMethod.IN_PERSON, null, "finance-reviewer");
                payouts.approve(id, "finance-approver");
                engine.markPaid(id, UUID.randomUUID());
                return null;
            });
        }
        return policy;
    }

    private List<PayoutInstalmentView> rows(String policy) {
        return asTenant(TENANT, () -> payouts.listForPolicy(policy)).stream().filter(i -> i.kind() == PayoutKind.ANNUITY).toList();
    }

    private AnnuityContractView contract(String policy) {
        return asTenant(TENANT, () -> annuityApi.contract(policy)).orElseThrow();
    }

    /** Registers, assesses and decides a death claim; returns its id. */
    private UUID deathClaim(String policy, LocalDate died, UUID deceased, String amount) {
        UUID claimId = registerDeath(policy, died, deceased);
        asTenant(TENANT, () -> {
            claimsApi.submitAssessment(claimId, "Death certificate seen", null, null, false, "claims-assessor", null);
            claimsApi.decideSettlement(claimId, true, new BigDecimal(amount), "TZS", null, "+255700000888",
                UUID.randomUUID().toString(), "claims-manager");
            return null;
        });
        return claimId;
    }

    private UUID registerDeath(String policy, LocalDate died, UUID deceased) {
        UUID claimant = fixtures.person(TENANT, 35, null);
        return asTenant(TENANT, () -> claimsApi.registerClaim(new ClaimsApi.RegisterClaimRequest(policy, null, claimant,
            ClaimType.DEATH, died, new DeathClaimDetails("Natural causes", "Dar es Salaam", died, "Dr. Test", deceased)),
            UUID.randomUUID().toString(), "claims-clerk").claimId());
    }

    @Test
    void lifeOnlyAVerifiedDeathPaysNothingAndEndsTheAnnuity() {
        String policy = inPayment(AnnuityChoice.of("LIFE-0G", "MONTHLY", null), fixtures.person(TENANT, 61, null), 3);
        LocalDate died = TODAY.minusDays(5);
        UUID claim = registerDeath(policy, died, null);
        assertThat(asTenant(TENANT, () -> claimsApi.claimableCover(claim)).amount()).isEqualByComparingTo("0.00");
        asTenant(TENANT, () -> {
            claimsApi.submitAssessment(claim, "Death certificate seen", null, null, false, "claims-assessor", null);
            claimsApi.decideSettlement(claim, true, BigDecimal.ZERO, "TZS", null, null, null, "claims-manager");
            return null;
        });
        assertThat(asTenant(TENANT, () -> claimsApi.getClaim(claim)).status()).isEqualTo(ClaimStatus.SETTLED);
        assertThat(contract(policy).status()).isEqualTo(ContractStatus.ENDED);
        assertThat(rows(policy).stream().filter(i -> i.dueDate().isAfter(died)))
            .allSatisfy(i -> assertThat(i.status()).isEqualTo(InstalmentStatus.CANCELLED));
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policy)).status()).isEqualTo(PolicyStatus.ANNUITY_ENDED);
    }

    @Test
    void insideAGuaranteeTheInstalmentsContinueAndThePolicyStaysOpen() {
        String policy = inPayment(AnnuityChoice.of("LIFE-10G", "MONTHLY", null), fixtures.person(TENANT, 61, null), 3);
        LocalDate died = TODAY.minusDays(5);
        deathClaim(policy, died, null, "0");
        assertThat(contract(policy).status()).isEqualTo(ContractStatus.GUARANTEE);
        assertThat(rows(policy).stream().filter(i -> i.dueDate().isAfter(died)))
            .isNotEmpty().allSatisfy(i -> assertThat(i.status()).isNotEqualTo(InstalmentStatus.CANCELLED));
        // Not closed by the settled claim: the guarantee still pays.
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policy)).status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void aJointAnnuityPaysTheSurvivorAfterTheFirstDeathAndEndsAtTheLast() {
        UUID annuitant = fixtures.person(TENANT, 62, null);
        UUID joint = fixtures.person(TENANT, 57, null);
        String policy = inPayment(AnnuityChoice.of("JOINT-50", "MONTHLY", joint), annuitant, 2);
        LocalDate first = TODAY.minusDays(20);
        deathClaim(policy, first, annuitant, "0");
        assertThat(contract(policy).status()).isEqualTo(ContractStatus.SURVIVOR);
        BigDecimal full = rows(policy).get(0).currentAmount();
        assertThat(rows(policy).stream().filter(i -> i.dueDate().isAfter(first))).allSatisfy(i -> {
            assertThat(i.restatementReason()).startsWith("The survivor's 50%");
            assertThat(i.currentAmount()).isLessThan(full);
        });
        // The survivor is a second life on the same policy: their death is a second, separate claim.
        deathClaim(policy, TODAY.minusDays(2), joint, "0");
        assertThat(contract(policy).status()).isEqualTo(ContractStatus.ENDED);
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policy)).status()).isEqualTo(PolicyStatus.ANNUITY_ENDED);
    }

    @Test
    void capitalProtectionRefundsThePriceLessIncomePaidThroughTheClaim() {
        String policy = inPayment(AnnuityChoice.of("LIFE-CP", "MONTHLY", null), fixtures.person(TENANT, 61, null), 3);
        LocalDate died = TODAY.minusDays(5);
        UUID claim = registerDeath(policy, died, null);
        // 50,000,000 less three instalments of 294,000 paid in life.
        assertThat(asTenant(TENANT, () -> claimsApi.claimableCover(claim)).amount()).isEqualByComparingTo("49118000.00");
        asTenant(TENANT, () -> {
            claimsApi.submitAssessment(claim, "Death certificate seen", null, null, false, "claims-assessor", null);
            claimsApi.decideSettlement(claim, true, new BigDecimal("49118000.00"), "TZS", null, "+255700000888",
                UUID.randomUUID().toString(), "claims-manager");
            return null;
        });
        assertThat(asTenant(TENANT, () -> claimsApi.getClaim(claim)).status()).isIn(ClaimStatus.SETTLEMENT_REQUESTED, ClaimStatus.SETTLED);
        assertThat(contract(policy).status()).isEqualTo(ContractStatus.ENDED);
    }

    @Test
    void incomePaidAfterALateNotifiedDeathIsRecordedAsOwed() {
        String policy = inPayment(AnnuityChoice.of("LIFE-0G", "MONTHLY", null), fixtures.person(TENANT, 61, null), 4);
        // Dues: collected-3m, -2m, -1m and today, all paid. A death 45 days ago: the last two were paid after it.
        deathClaim(policy, TODAY.minusDays(45), null, "0");
        assertThat(contract(policy).overpaymentOwed()).isEqualByComparingTo("588000.00");
    }

    @Test
    void aJointClaimNamingNeitherLifeIsRefused() {
        UUID annuitant = fixtures.person(TENANT, 62, null);
        String policy = inPayment(AnnuityChoice.of("JOINT-50", "MONTHLY", fixtures.person(TENANT, 57, null)), annuitant, 0);
        UUID stranger = fixtures.person(TENANT, 70, null);
        UUID claim = registerDeath(policy, TODAY.minusDays(3), stranger);
        assertThatThrownBy(() -> asTenant(TENANT, () -> claimsApi.claimableCover(claim)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("The deceased named on this claim is not a life on annuity");
    }
}
