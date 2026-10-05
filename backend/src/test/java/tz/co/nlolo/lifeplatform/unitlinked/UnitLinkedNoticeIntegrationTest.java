package tz.co.nlolo.lifeplatform.unitlinked;

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
import tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures;
import tz.co.nlolo.lifeplatform.unitlinked.application.ChargeSweep;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.eat;

/**
 * The policyholder hears about their units (spec §10): the first premium bought units (once, not every month), the
 * fund is running low (at most once a month), and the policy lapsed because the fund was exhausted.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, FuneralTestFixtures.class})
class UnitLinkedNoticeIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

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
        "db-migrations/communication/V12__funeral_templates.sql",
        "db-migrations/communication/V13__unit_linked_templates.sql",
    };

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            Stream.concat(Stream.of(UnitLinkedTestMigrations.ALL), Stream.of(COMMUNICATION)).toArray(String[]::new));
    }

    private static final LocalDate ISSUED = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam")).minusDays(70);
    private static final LocalDate FIRST_CHARGE = ISSUED.plusMonths(1);

    @Autowired private UnitLinkedTestFixtures fixtures;
    @Autowired private ChargeSweep sweep;
    @Autowired private JdbcTemplate jdbc;

    private int sent(String policyNumber, String templateKey) {
        return jdbc.queryForObject("SELECT count(*) FROM communication.notification_dispatch"
            + " WHERE policy_number = ? AND template_key = ?", Integer.class, policyNumber, templateKey);
    }

    private String invested(UUID tenant, String premium) {
        var product = fixtures.publishStandard(tenant);
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        fixtures.backdate(policy, ISSUED);
        fixtures.collectAt(tenant, policy, premium, eat(ISSUED, 9, 0), UUID.randomUUID());
        fixtures.priceBoth(tenant, ISSUED, "1.000000", "1.000000");
        return policy;
    }

    @Test
    void theFirstPremiumIsToldOnceAndALaterOneIsNot() {
        UUID tenant = UUID.randomUUID();
        String policy = invested(tenant, "100000.00");
        int first = sent(policy, "UNIT_LINKED_ALLOCATED");
        assertThat(first).isPositive();

        fixtures.collectAt(tenant, policy, "100000.00", eat(ISSUED.plusDays(1), 9, 0), UUID.randomUUID());
        fixtures.priceBoth(tenant, ISSUED.plusDays(1), "1.000000", "1.000000");
        assertThat(sent(policy, "UNIT_LINKED_ALLOCATED")).isEqualTo(first); // not told again
    }

    @Test
    void aFundCoveringFewerThanThreeMonthsOfChargesWarnsTheCustomer() {
        UUID tenant = UUID.randomUUID();
        // 4,500 of units against about 2,600 a month of charges.
        String policy = invested(tenant, "5000.00");
        sweep.sweepOne(policy, tenant, FIRST_CHARGE);
        fixtures.priceBoth(tenant, FIRST_CHARGE, "1.000000", "1.000000");
        assertThat(sent(policy, "UNIT_LINKED_LOW_FUND")).isPositive();
    }

    @Test
    void anExhaustedFundTellsTheCustomerTheirCoverLapsed() {
        UUID tenant = UUID.randomUUID();
        String policy = invested(tenant, "1000.00");
        sweep.sweepOne(policy, tenant, FIRST_CHARGE);
        fixtures.priceBoth(tenant, FIRST_CHARGE, "1.000000", "1.000000");
        assertThat(sent(policy, "UNIT_LINKED_LAPSED_EXHAUSTED")).isPositive();
        assertThat(sent(policy, "UNIT_LINKED_LOW_FUND")).isZero();
    }
}
