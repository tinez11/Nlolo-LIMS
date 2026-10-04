package tz.co.nlolo.lifeplatform.annuity;

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
import tz.co.nlolo.lifeplatform.billing.application.BillingApiImpl;
import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * A pension on the policy's record (product step 5 D2, Task 4): the vesting stops billing and waives
 * what is unpaid, a deferral can extend contributions, and surrender, paid-up and a death's closing
 * follow whether it has vested and whether the version locks it.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AnnuityTestFixtures.class)
class PensionPolicyIntegrationTest {

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
    private static final String LOCKED = "This pension cannot be surrendered or withdrawn from before it vests";

    @Autowired private AnnuityTestFixtures fixtures;
    @Autowired private PolicyApi policyApi;
    @Autowired private BillingApiImpl billing;
    @Autowired private JdbcTemplate jdbc;

    /** A pension in force: issued and its first contribution collected. */
    private String pension(boolean surrenderBeforeVesting) {
        var product = fixtures.publishDeferred(TENANT, surrenderBeforeVesting);
        String policy = fixtures.issueDeferred(TENANT, product, fixtures.personBorn(TENANT, LocalDate.of(1980, 6, 15), null), 60);
        fixtures.collect(TENANT, policy, "200000.00", TODAY);
        return policy;
    }

    private List<Map<String, Object>> invoices(String policy) {
        return jdbc.queryForList("SELECT status, waiver_reason FROM billing.premium_invoice WHERE policy_number = ?", policy);
    }

    private String scheduleStatus(String policy) {
        return jdbc.queryForObject("SELECT status FROM billing.billing_schedule WHERE policy_number = ?", String.class, policy);
    }

    private int vestingRows(String policy) {
        return jdbc.queryForObject("SELECT count(*) FROM policy.annuity_vesting WHERE policy_number = ?", Integer.class, policy);
    }

    @Test
    void vestingStopsBillingAndWaivesEveryUnpaidInvoiceOnce() {
        String policy = pension(false);
        assertThat(invoices(policy)).anySatisfy(i -> assertThat(i.get("status")).isEqualTo("DUE"));

        LocalDate vestedOn = TODAY.plusDays(10);
        asTenant(TENANT, () -> { policyApi.recordVesting(policy, vestedOn); return null; });
        asTenant(TENANT, () -> { policyApi.recordVesting(policy, vestedOn); return null; });

        assertThat(vestingRows(policy)).isEqualTo(1);
        assertThat(scheduleStatus(policy)).isEqualTo("TERMINATED");
        assertThat(invoices(policy)).allSatisfy(i -> assertThat(i.get("status")).isIn("PAID", "WAIVED"));
        assertThat(invoices(policy)).filteredOn(i -> "WAIVED".equals(i.get("status"))).isNotEmpty()
            .allSatisfy(i -> assertThat(i.get("waiver_reason"))
                .isEqualTo("The pension vested on " + vestedOn + "; no further contributions are due"));
    }

    @Test
    void aDeferralWithContributionsContinuingExtendsBilling() {
        // Fifty-five, retiring at 56: the contributions end within the year.
        var product = fixtures.publishDeferred(TENANT, false);
        LocalDate born = TODAY.minusYears(55).minusDays(10);
        String policy = fixtures.issueDeferred(TENANT, product, fixtures.personBorn(TENANT, born, null), 56);
        LocalDate oldEnd = asTenant(TENANT, () -> policyApi.getPolicy(policy)).commencementDate()
            .plusMonths(asTenant(TENANT, () -> policyApi.getPolicy(policy)).premiumPayingTermMonths());
        int before = invoices(policy).size();

        LocalDate newEnd = asTenant(TENANT, () -> policyApi.extendPremiumPayingTerm(policy, born.plusYears(58)));
        assertThat(newEnd).isAfter(oldEnd);
        LocalDate stored = jdbc.queryForObject("SELECT premium_paying_until FROM billing.billing_schedule WHERE policy_number = ?",
            LocalDate.class, policy);
        assertThat(stored).isEqualTo(newEnd);

        UUID scheduleId = jdbc.queryForObject("SELECT billing_schedule_id FROM billing.billing_schedule WHERE policy_number = ?",
            UUID.class, policy);
        asTenant(TENANT, () -> { billing.rollForward(scheduleId); return null; });
        assertThat(invoices(policy).size()).isGreaterThan(before);
    }

    @Test
    void afterVestingAPensionCannotBeSurrenderedOrMadePaidUp() {
        String policy = pension(true);
        asTenant(TENANT, () -> { policyApi.recordVesting(policy, TODAY); return null; });
        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.requestSurrender(policy, "+255700000961", "staff-one")))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessage("Policy " + policy + " is a pension in payment; it cannot be surrendered");
        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.makePaidUp(policy, "staff-one")))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessage("Policy " + policy + " is a pension in payment; it has no contributions to stop");
    }

    @Test
    void beforeVestingTheVersionsLockDecidesSurrender() {
        String locked = pension(false);
        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.requestSurrender(locked, "+255700000962", "staff-one")))
            .isInstanceOf(InvalidPolicyStateException.class).hasMessage(LOCKED);

        String unlocked = pension(true);
        Throwable refused = catchThrowable(() -> asTenant(TENANT, () -> policyApi.requestSurrender(unlocked, "+255700000963", "staff-one")));
        // Whatever an account policy's own rules say, it is not the pension lock.
        assertThat(refused == null ? "" : refused.getMessage()).doesNotContain("before it vests").doesNotContain("pension in payment");
    }

    @Test
    void aDeathBeforeVestingClosesThePolicyLikeAnAccountsAndAfterVestingItDoesNot() {
        String saving = pension(false);
        asTenant(TENANT, () -> { policyApi.dischargeForSettledClaim(saving, null, TODAY, UUID.randomUUID(), "claims"); return null; });
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(saving)).status()).isEqualTo(PolicyStatus.SURRENDERED);

        String vested = pension(false);
        asTenant(TENANT, () -> { policyApi.recordVesting(vested, TODAY); return null; });
        asTenant(TENANT, () -> { policyApi.dischargeForSettledClaim(vested, null, TODAY, UUID.randomUUID(), "claims"); return null; });
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(vested)).status()).isNotEqualTo(PolicyStatus.SURRENDERED);
    }
}
