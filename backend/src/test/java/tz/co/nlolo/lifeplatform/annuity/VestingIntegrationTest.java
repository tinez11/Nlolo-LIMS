package tz.co.nlolo.lifeplatform.annuity;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.accumulation.api.AccountStatus;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationApi;
import tz.co.nlolo.lifeplatform.accumulation.application.AccumulationApiImpl;
import tz.co.nlolo.lifeplatform.annuity.api.AnnuityApi;
import tz.co.nlolo.lifeplatform.annuity.api.ContractStatus;
import tz.co.nlolo.lifeplatform.annuity.api.VestingInstructionInput;
import tz.co.nlolo.lifeplatform.annuity.api.VestingRefusedException;
import tz.co.nlolo.lifeplatform.annuity.api.VestingView;
import tz.co.nlolo.lifeplatform.annuity.application.AnnuityVesting;
import tz.co.nlolo.lifeplatform.annuity.application.VestingSweep;
import tz.co.nlolo.lifeplatform.annuity.domain.CommutationSplit;
import tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutInstalmentView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.product.api.PayoutKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * A pension vesting (product step 5 D2, Task 6): by default on its target, or as instructed; priced on
 * the product's version current that day; the lump sum paid apart; deferred with contributions
 * continuing or stopped; held -- touching nothing -- for a changed age, an unpriceable choice or a
 * reported death; and never half-done.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AnnuityTestFixtures.class)
class VestingIntegrationTest {

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
    /** Fifty-five last month: inside the window (55-70) today, so an early vesting for today is allowed. */
    private static final LocalDate BORN = TODAY.minusYears(55).minusDays(30);

    @Autowired private AnnuityTestFixtures fixtures;
    @Autowired private AnnuityApi annuityApi;
    @Autowired private AnnuityVesting vesting;
    @Autowired private VestingSweep sweep;
    @Autowired private AccumulationApi accumulationApi;
    @Autowired private AccumulationApiImpl accumulation;
    @Autowired private PolicyApi policyApi;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private BenefitPayoutApi payouts;

    private record Sold(AnnuityTestFixtures.Product product, UUID saver, String policy) {}

    /** Sold at 55 retiring at 56, with two contributions of 200,000.00 collected today. */
    private Sold sold() {
        var product = fixtures.publishDeferred(TENANT, false);
        UUID saver = fixtures.personBorn(TENANT, BORN, null);
        String policy = fixtures.issueDeferred(TENANT, product, saver, 56);
        fixtures.collect(TENANT, policy, "200000.00", TODAY);
        fixtures.collect(TENANT, policy, "200000.00", TODAY);
        return new Sold(product, saver, policy);
    }

    private VestingView vestingOf(String policy) {
        return asTenant(TENANT, () -> annuityApi.vesting(policy)).orElseThrow();
    }

    private String vest(String policy, LocalDate today) {
        return asTenant(TENANT, () -> vesting.vest(policy, today));
    }

    private VestingView instruct(String policy, LocalDate date, String form, String pct, String contributions) {
        return asTenant(TENANT, () -> annuityApi.recordVestingInstruction(policy,
            new VestingInstructionInput(date, form, "MONTHLY", null, new BigDecimal(pct), contributions), "staff-one"));
    }

    private List<PayoutInstalmentView> instalments(String policy, PayoutKind kind) {
        return asTenant(TENANT, () -> payouts.listForPolicy(policy)).stream().filter(i -> i.kind() == kind).toList();
    }

    private AccountStatus accountStatus(String policy) {
        return asTenant(TENANT, () -> accumulationApi.findAccount(policy)).orElseThrow().status();
    }

    @Test
    void withNoInstructionItVestsOnTheTargetIntoTheDefaultWithNoLumpSum() {
        Sold s = sold();
        LocalDate target = vestingOf(s.policy()).targetDate();
        assertThat(vest(s.policy(), target)).isEqualTo("vested");

        var c = asTenant(TENANT, () -> annuityApi.contract(s.policy())).orElseThrow();
        VestingView v = vestingOf(s.policy());
        assertThat(c.status()).isEqualTo(ContractStatus.IN_PAYMENT);
        assertThat(c.formCode()).isEqualTo("LIFE-0G");
        assertThat(c.frequency()).isEqualTo("MONTHLY");
        assertThat(c.lockedOn()).isEqualTo(target);
        // The sold version's LIFE-0G rate at 56: 60 + (56 - 55).
        assertThat(c.annualRatePerMille()).isEqualByComparingTo("61");
        assertThat(v.vestedOn()).isEqualTo(target);
        assertThat(c.purchasePrice()).isEqualByComparingTo(v.vestedBalance());
        assertThat(v.lumpSum()).isEqualByComparingTo("0.00");
        assertThat(instalments(s.policy(), PayoutKind.COMMUTATION)).isEmpty();
        assertThat(instalments(s.policy(), PayoutKind.ANNUITY)).isNotEmpty();
        assertThat(accountStatus(s.policy())).isEqualTo(AccountStatus.CLOSED);
        assertThat(jdbc.queryForObject("SELECT vested_on FROM policy.annuity_vesting WHERE policy_number = ?",
            LocalDate.class, s.policy())).isEqualTo(target);
    }

    @Test
    void anEarlyVestingWithALumpSumIsPricedOnTheVersionCurrentThatDay() {
        Sold s = sold();
        // Published AFTER the sale, with a higher rate: spec Q7 prices at vesting, not at sale.
        fixtures.publishDeferredVersion(TENANT, s.product().productId(), AnnuityTestFixtures.deferredPlanWith("LIFE-0G", 90));
        instruct(s.policy(), TODAY, "LIFE-0G", "25", null);
        assertThat(vest(s.policy(), TODAY)).isEqualTo("vested");

        VestingView v = vestingOf(s.policy());
        CommutationSplit.Split split = CommutationSplit.of(v.vestedBalance(), new BigDecimal("25"));
        assertThat(v.lumpSum()).isEqualByComparingTo(split.lumpSum());
        assertThat(instalments(s.policy(), PayoutKind.COMMUTATION)).singleElement()
            .satisfies(i -> assertThat(i.currentAmount()).isEqualByComparingTo(split.lumpSum()));
        var c = asTenant(TENANT, () -> annuityApi.contract(s.policy())).orElseThrow();
        assertThat(c.annualRatePerMille()).isEqualByComparingTo("90");
        assertThat(c.purchasePrice()).isEqualByComparingTo(split.purchasePrice());
    }

    @Test
    void aDeferralWithContributionsContinuingExtendsThemAndTheOldTargetDoesNothing() {
        Sold s = sold();
        LocalDate target = vestingOf(s.policy()).targetDate();
        int before = asTenant(TENANT, () -> policyApi.getPolicy(s.policy())).premiumPayingTermMonths();
        instruct(s.policy(), target.plusYears(2), "LIFE-0G", "0", "CONTINUE");
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(s.policy())).premiumPayingTermMonths()).isGreaterThan(before);
        assertThat(vest(s.policy(), target)).startsWith("not due");
        assertThat(asTenant(TENANT, () -> annuityApi.contract(s.policy())).orElseThrow().status())
            .isEqualTo(ContractStatus.ACCUMULATING);
    }

    @Test
    void aDeferralWithContributionsStoppedLeavesThemAndTheOldTargetDoesNothing() {
        Sold s = sold();
        LocalDate target = vestingOf(s.policy()).targetDate();
        int before = asTenant(TENANT, () -> policyApi.getPolicy(s.policy())).premiumPayingTermMonths();
        VestingView v = instruct(s.policy(), target.plusYears(2), "LIFE-0G", "0", "STOP");
        assertThat(v.contributions()).isEqualTo("STOP");
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(s.policy())).premiumPayingTermMonths()).isEqualTo(before);
        assertThat(vest(s.policy(), target)).startsWith("not due");
    }

    @Test
    void aRefusedInstructionSaysWhy() {
        Sold s = sold();
        assertThatThrownBy(() -> instruct(s.policy(), TODAY, "LIFE-0G", "26", null))
            .isInstanceOf(VestingRefusedException.class).hasMessage("The lump sum can be from 0% to 25% of the balance");
        assertThatThrownBy(() -> instruct(s.policy(), TODAY, "LIFE-9G", "0", null))
            .isInstanceOf(VestingRefusedException.class).hasMessage("This version does not offer form LIFE-9G");
        assertThatThrownBy(() -> instruct(s.policy(), TODAY, "JOINT-50", "0", null))
            .isInstanceOf(VestingRefusedException.class).hasMessage("Form JOINT-50 is joint-life: name the joint life");
    }

    @Test
    void aChangedDateOfBirthHoldsItUntilAgeIsReconfirmedAndTheDatesMoveWithIt() {
        Sold s = sold();
        LocalDate target = vestingOf(s.policy()).targetDate();
        fixtures.amendDateOfBirth(TENANT, s.saver(), BORN.minusYears(1));

        assertThat(vest(s.policy(), target)).startsWith("held");
        VestingView held = vestingOf(s.policy());
        assertThat(held.holdReason())
            .isEqualTo("Age re-confirmation needed: the date of birth or sex on record has changed since it was confirmed");
        assertThat(accountStatus(s.policy())).isEqualTo(AccountStatus.OPEN);
        assertThat(asTenant(TENANT, () -> annuityApi.listHeldVestings()))
            .extracting(VestingView::policyNumber).contains(s.policy());

        VestingView reconfirmed = asTenant(TENANT, () -> annuityApi.reconfirmAge(s.policy(), "staff-two"));
        assertThat(reconfirmed.holdReason()).isNull();
        assertThat(reconfirmed.targetDate()).isEqualTo(target.minusYears(1));
        assertThat(reconfirmed.confirmedDateOfBirth()).isEqualTo(BORN.minusYears(1));
        // The moved target is now behind today: it vests today, the day the sweep reaches it.
        assertThat(reconfirmed.targetDate()).isBefore(TODAY);
        assertThat(vest(s.policy(), TODAY)).isEqualTo("vested");
        assertThat(vestingOf(s.policy()).vestedOn()).isEqualTo(TODAY);
    }

    @Test
    void anUnpriceableChoiceHoldsItAndANewInstructionClearsIt() {
        Sold s = sold();
        instruct(s.policy(), TODAY, "LIFE-0G", "0", null);
        // The current version no longer offers LIFE-0G.
        fixtures.publishDeferredVersion(TENANT, s.product().productId(), AnnuityTestFixtures.deferredPlanWith("LIFE-5Y", 80));
        assertThat(vest(s.policy(), TODAY)).startsWith("held");
        assertThat(vestingOf(s.policy()).holdReason()).isNotBlank();
        assertThat(accountStatus(s.policy())).isEqualTo(AccountStatus.OPEN);

        instruct(s.policy(), TODAY, "LIFE-5Y", "0", null);
        assertThat(vestingOf(s.policy()).holdReason()).isNull();
        assertThat(vest(s.policy(), TODAY)).isEqualTo("vested");
    }

    @Test
    void aDeathReportedBeforeVestingHoldsItUntilTheClaimIsRejected() {
        Sold s = sold();
        instruct(s.policy(), TODAY, "LIFE-0G", "0", null);
        String claimId = UUID.randomUUID().toString();
        fixtures.publish(TENANT, "claims.ClaimRegistered",
            Map.of("claimId", claimId, "policyNumber", s.policy(), "claimType", "DEATH"));
        assertThat(vest(s.policy(), TODAY)).isEqualTo("held: A death has been reported on this policy; it vests only if that claim is rejected");

        fixtures.publish(TENANT, "claims.ClaimRejected",
            Map.of("claimId", claimId, "policyNumber", s.policy(), "claimType", "DEATH", "reason", "Not dead"));
        assertThat(vestingOf(s.policy()).holdReason()).isNull();
        assertThat(vest(s.policy(), TODAY)).isEqualTo("vested");
    }

    @Test
    void aFailureAfterTheAccountIsTouchedRollsAllOfItBackAndHoldsIt() {
        Sold s = sold();
        instruct(s.policy(), TODAY, "LIFE-0G", "25", null);
        Mockito.doThrow(new IllegalStateException("payment rail down")).when(payouts)
            .scheduleCommutation(any(), any(), any(), any());
        try {
            sweep.drain();
        } finally {
            Mockito.reset(payouts);
        }
        assertThat(accountStatus(s.policy())).isEqualTo(AccountStatus.OPEN);
        assertThat(asTenant(TENANT, () -> annuityApi.contract(s.policy())).orElseThrow().status())
            .isEqualTo(ContractStatus.ACCUMULATING);
        assertThat(vestingOf(s.policy()).holdReason()).isEqualTo("Vesting failed and was rolled back: payment rail down");
        assertThat(instalments(s.policy(), PayoutKind.COMMUTATION)).isEmpty();

        sweep.drain();
        assertThat(asTenant(TENANT, () -> annuityApi.contract(s.policy())).orElseThrow().status())
            .isEqualTo(ContractStatus.IN_PAYMENT);
        assertThat(vestingOf(s.policy()).holdReason()).isNull();
    }

    @Test
    void itVestsOnceAndTheClosedAccountIsNeverLapsedAsExhausted() {
        Sold s = sold();
        LocalDate target = vestingOf(s.policy()).targetDate();
        assertThat(vest(s.policy(), target)).isEqualTo("vested");
        assertThat(vest(s.policy(), target)).isEqualTo("not saving");
        assertThat(instalments(s.policy(), PayoutKind.ANNUITY)).isNotEmpty();

        asTenant(TENANT, () -> { accumulation.postMonthEnds(s.policy(), target.plusMonths(3)); return null; });
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(s.policy())).status()).isNotEqualTo(PolicyStatus.LAPSED);
    }
}
