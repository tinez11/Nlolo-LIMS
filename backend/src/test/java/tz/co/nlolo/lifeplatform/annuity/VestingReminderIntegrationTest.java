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
import tz.co.nlolo.lifeplatform.annuity.api.AnnuityApi;
import tz.co.nlolo.lifeplatform.annuity.api.VestingInstructionInput;
import tz.co.nlolo.lifeplatform.annuity.application.AnnuityVesting;
import tz.co.nlolo.lifeplatform.annuity.application.VestingReminders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * A pension's reminders (product step 5 D2, Task 7): 90 and 30 days before it vests, once each per
 * vesting date; a deferral re-arms them; a vested pension is sent nothing.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AnnuityTestFixtures.class)
class VestingReminderIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** communication's own list, as StatementIntegrationTest applies it, plus the reminder template. */
    private static final String[] COMMUNICATION = {
        "db-migrations/communication/V1__create_communication_schema.sql",
        "db-migrations/communication/V2__template_identity.sql",
        "db-migrations/communication/V3__seed_offer_templates.sql",
        "db-migrations/communication/V4__dispatch_reason_and_policy.sql",
        "db-migrations/communication/V5__dispatch_claimed_status.sql",
        "db-migrations/communication/V6__grants_and_rls.sql",
        "db-migrations/communication/V7__null_safe_rls_and_pending_reminders.sql",
        "db-migrations/communication/V8__platform_default_templates.sql",
        "db-migrations/communication/V9__payment_received_template.sql",
        "db-migrations/communication/V10__account_statement_template.sql",
        "db-migrations/communication/V11__vesting_reminder_template.sql",
        "db-migrations/communication/V15__dispatch_body_and_inbox.sql",
    };

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            Stream.concat(Stream.of(AnnuityTestMigrations.ALL), Stream.of(COMMUNICATION)).toArray(String[]::new));
    }

    private static final UUID TENANT = UUID.randomUUID();

    @Autowired private AnnuityTestFixtures fixtures;
    @Autowired private AnnuityApi annuityApi;
    @Autowired private VestingReminders reminders;
    @Autowired private AnnuityVesting vesting;
    @Autowired private JdbcTemplate jdbc;

    /** Retiring at 56 in exactly 90 days. */
    private String pensionVestingIn90Days() {
        var product = fixtures.publishDeferred(TENANT, false);
        LocalDate born = TODAY.plusDays(90).minusYears(56);
        return fixtures.issueDeferred(TENANT, product, fixtures.personBorn(TENANT, born, null), 56);
    }

    private int remind(String policy, LocalDate today) {
        return asTenant(TENANT, () -> reminders.remind(policy, today));
    }

    private int sent(String policy) {
        return jdbc.queryForObject("SELECT count(*) FROM communication.notification_dispatch "
            + "WHERE policy_number = ? AND template_key = 'PENSION_VESTING_REMINDER'", Integer.class, policy);
    }

    @Test
    void ninetyAndThirtyDaysBeforeOnceEach() {
        String policy = pensionVestingIn90Days();
        LocalDate target = TODAY.plusDays(90);

        assertThat(remind(policy, TODAY)).isEqualTo(1);
        assertThat(remind(policy, TODAY)).isZero();
        assertThat(remind(policy, TODAY.plusDays(10))).isZero();
        assertThat(sent(policy)).isEqualTo(1);

        assertThat(remind(policy, target.minusDays(30))).isEqualTo(1);
        assertThat(remind(policy, target.minusDays(29))).isZero();
        assertThat(sent(policy)).isEqualTo(2);
    }

    @Test
    void aDeferralReArmsThem() {
        String policy = pensionVestingIn90Days();
        LocalDate target = TODAY.plusDays(90);
        assertThat(remind(policy, TODAY)).isEqualTo(1);

        LocalDate deferred = target.plusYears(1);
        asTenant(TENANT, () -> annuityApi.recordVestingInstruction(policy,
            new VestingInstructionInput(deferred, "LIFE-0G", "MONTHLY", null, BigDecimal.ZERO, "STOP"), "staff-one"));
        assertThat(remind(policy, deferred.minusDays(90))).isEqualTo(1);
        assertThat(sent(policy)).isEqualTo(2);
    }

    @Test
    void aVestedPensionIsSentNothing() {
        String policy = pensionVestingIn90Days();
        LocalDate target = TODAY.plusDays(90);
        fixtures.collect(TENANT, policy, "200000.00", TODAY);
        assertThat(asTenant(TENANT, () -> vesting.vest(policy, target))).isEqualTo("vested");
        assertThat(remind(policy, target.minusDays(30))).isZero();
        assertThat(sent(policy)).isZero();
    }
}
