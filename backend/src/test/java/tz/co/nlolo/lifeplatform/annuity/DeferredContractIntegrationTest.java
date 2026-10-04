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
import tz.co.nlolo.lifeplatform.annuity.application.AnnuityApiImpl;
import tz.co.nlolo.lifeplatform.party.api.Sex;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * A deferred annuity's contract before it vests (product step 5 D2, Task 5): ACCUMULATING from issue
 * with its vesting dates, untouched by contributions, and ended or cancelled -- never bought -- by a
 * death or surrender before vesting. While it saves, a death is the account's to value, not the annuity's.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AnnuityTestFixtures.class)
class DeferredContractIntegrationTest {

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
    private static final LocalDate BORN = LocalDate.of(1980, 6, 15);

    @Autowired private AnnuityTestFixtures fixtures;
    @Autowired private AnnuityApi annuityApi;
    @Autowired private AnnuityApiImpl engine;

    private String pension() {
        var product = fixtures.publishDeferred(TENANT, true);
        return fixtures.issueDeferred(TENANT, product, fixtures.personBorn(TENANT, BORN, Sex.FEMALE), 60);
    }

    private AnnuityContractView contract(String policy) {
        return asTenant(TENANT, () -> annuityApi.contract(policy)).orElseThrow();
    }

    @Test
    void issueOpensAnAccumulatingContractWithItsVestingDates() {
        String policy = pension();
        AnnuityContractView c = contract(policy);
        assertThat(c.status()).isEqualTo(ContractStatus.ACCUMULATING);
        assertThat(c.formCode()).isNull();
        assertThat(c.purchasePrice()).isNull();
        assertThat(c.lockedOn()).isNull();

        var v = asTenant(TENANT, () -> annuityApi.vesting(policy)).orElseThrow();
        assertThat(v.targetDate()).isEqualTo(LocalDate.of(2040, 6, 15));
        assertThat(v.earliestVestingDate()).isEqualTo(LocalDate.of(2035, 6, 15));
        assertThat(v.latestVestingDate()).isEqualTo(LocalDate.of(2050, 6, 15));
        assertThat(v.vestingDate()).isEqualTo(v.targetDate());
        assertThat(v.formCode()).isEqualTo("LIFE-0G");
        assertThat(v.frequency()).isEqualTo("MONTHLY");
        assertThat(v.lumpSumPercent()).isEqualByComparingTo("0");
        assertThat(v.maxCommutationPercent()).isEqualByComparingTo("25");
        assertThat(v.instructed()).isFalse();
        assertThat(v.confirmedDateOfBirth()).isEqualTo(BORN);
        assertThat(v.confirmedSex()).isEqualTo("FEMALE");
        assertThat(v.ageConfirmedBy()).isEqualTo("senior-two");
        assertThat(v.holdReason()).isNull();
    }

    @Test
    void aContributionChangesNothingOnTheContract() {
        String policy = pension();
        fixtures.collect(TENANT, policy, "200000.00", TODAY);
        AnnuityContractView c = contract(policy);
        assertThat(c.status()).isEqualTo(ContractStatus.ACCUMULATING);
        assertThat(c.lockedOn()).isNull();
    }

    @Test
    void whileItSavesADeathIsTheAccountsAndApprovalEndsTheContractUnbought() {
        String policy = pension();
        assertThat(asTenant(TENANT, () -> annuityApi.decidesDeath(policy))).isFalse();
        assertThat(asTenant(TENANT, () -> annuityApi.isAnnuity(policy))).isTrue();

        asTenant(TENANT, () -> { engine.onDeathApproved(policy, null, TODAY, UUID.randomUUID()); return null; });
        AnnuityContractView c = contract(policy);
        assertThat(c.status()).isEqualTo(ContractStatus.ENDED);
        assertThat(c.endReason()).isEqualTo("Died before vesting");
        assertThat(c.lockedOn()).isNull();
    }

    @Test
    void aSurrenderBeforeVestingCancelsTheContractButAClaimsClosingDoesNot() {
        String closedByClaim = pension();
        fixtures.publish(TENANT, "policy.PolicySurrendered",
            Map.of("policyNumber", closedByClaim, "claimId", UUID.randomUUID().toString(), "surrenderedAt", "2026-10-04T09:00:00Z"));
        assertThat(contract(closedByClaim).status()).isEqualTo(ContractStatus.ACCUMULATING);

        String surrendered = pension();
        fixtures.publish(TENANT, "policy.PolicySurrendered",
            Map.of("policyNumber", surrendered, "surrenderedAt", "2026-10-04T09:00:00Z"));
        AnnuityContractView c = contract(surrendered);
        assertThat(c.status()).isEqualTo(ContractStatus.CANCELLED);
        assertThat(c.endReason()).isEqualTo("Surrendered before vesting");
    }

    @Test
    void aFreeLookCancellationCancelsAContractWithNoStream() {
        String policy = pension();
        asTenant(TENANT, () -> { engine.onFreeLookCancelled(policy); return null; });
        assertThat(contract(policy).status()).isEqualTo(ContractStatus.CANCELLED);
    }

    @Test
    void anImmediateAnnuityStillDecidesItsOwnDeath() {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.everyForm());
        String policy = fixtures.issueInForce(TENANT, product, fixtures.person(TENANT, 61, null), "1000000.00");
        // Issued with no case, so its contract failed at issue -- but it is an immediate annuity's, not saving.
        assertThat(asTenant(TENANT, () -> annuityApi.decidesDeath(policy))).isTrue();
        assertThat(asTenant(TENANT, () -> annuityApi.vesting(policy))).isEmpty();
    }
}
