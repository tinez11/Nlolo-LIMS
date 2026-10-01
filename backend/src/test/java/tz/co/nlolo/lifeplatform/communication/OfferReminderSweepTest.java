package tz.co.nlolo.lifeplatform.communication;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.communication.application.OfferReminderDispatcher;
import tz.co.nlolo.lifeplatform.communication.domain.NotificationDispatch;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationDispatchRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static tz.co.nlolo.lifeplatform.communication.NextSmsStubs.NEXTSMS_ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;

/**
 * The reminder, queued in SQL and sent in Java.
 *
 * <p>The sweep is loaded from the file operations actually applies rather than an inlined copy.
 * A copy would keep passing while the real file rotted — which is precisely how the billing sweep
 * shipped a {@code CALL} against a function for three milestones before anybody noticed.
 */
@Testcontainers
@SpringBootTest(classes = Application.class,
    // The drain is driven explicitly below. Left on its own schedule it would race every
    // assertion here, and a test that sometimes finds a row already sent proves nothing either
    // way.
    properties = { "communication.reminder-drain-interval-ms=3600000", "communication.sms-gateway.live=true" })
class OfferReminderSweepTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer smsGateway;

    private static final String APP_ROLE_PASSWORD = "offer_reminder_e2e_password";
    private static final UUID SEEDED_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_role");
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
        registry.add("communication.sms-gateway-url", () -> smsGateway.baseUrl());
    }

    @BeforeAll
    static void startEverything() throws Exception {
        smsGateway = new WireMockServer(options().dynamicPort());
        smsGateway.start();
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/party/V5__registered_by_name.sql",
            "db-migrations/party/V6__client_reference.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/product/V6__eligibility_bounds.sql",
            "db-migrations/product/V9__rating_table_sum_assured_bounds.sql",
            "db-migrations/product/V10__ifrs_measurement_model_on_version.sql",
            "db-migrations/product/V11__frequency_loading.sql",
            "db-migrations/product/V12__tira_filing.sql",
            "db-migrations/product/V13__benefit_calculation_method.sql",
            "db-migrations/product/V15__exclusion_periods.sql",
            "db-migrations/product/V16__base_rate_term_bands.sql",
            "db-migrations/product/V17__cash_value.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/refdata/V5__seed_offer_validity.sql",
            "db-migrations/refdata/V6__seed_offer_reminder_days.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/policy/V10__one_policy_per_underwriting_case.sql",
            "db-migrations/policy/V11__not_taken_up_status.sql",
            "db-migrations/policy/V24__issuance_record.sql",
            "db-migrations/policy/V27__expired_status.sql",
            "db-migrations/policy/V28__policies_due_to_expire.sql",
            "db-migrations/policy/V29__paid_up.sql",
            "db-migrations/policy/V30__surrender.sql",
            "db-migrations/communication/V1__create_communication_schema.sql",
            "db-migrations/communication/V2__template_identity.sql",
            "db-migrations/communication/V3__seed_offer_templates.sql",
            "db-migrations/communication/V4__dispatch_reason_and_policy.sql",
            "db-migrations/communication/V5__dispatch_claimed_status.sql",
            "db-migrations/communication/V6__grants_and_rls.sql",
            "db-migrations/communication/V7__null_safe_rls_and_pending_reminders.sql",
            "db-migrations/communication/V8__platform_default_templates.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");

        // Runs as app_role, NOSUPERUSER NOBYPASSRLS -- and that is the point of this class rather
        // than a detail. Connecting as the owning superuser, as every other communication test
        // does, silently bypasses RLS and every grant: the missing grants in communication/V1
        // survived a green suite that way and only appeared on a dev restart, and the drain's
        // cross-tenant read would pass here while returning nothing in production. This test now
        // exercises the credential the application actually runs as.
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }

        String sweepSql = Files.readString(
            Path.of("db-migrations/_post-migration/configure-offer-reminder-sweep.sql"));
        // Everything above the cron.schedule line is the function; scheduling needs the pg_cron
        // extension, which this container does not load.
        String functionOnly = sweepSql.substring(0, sweepSql.indexOf("-- Daily at 03:00"));
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(functionOnly);
        }
    }

    @AfterAll
    static void stopGateway() {
        smsGateway.stop();
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private NotificationDispatchRepository dispatchRepository;
    @Autowired private OfferReminderDispatcher dispatcher;

    @BeforeEach
    void acceptEverySms() {
        smsGateway.resetAll();
        smsGateway.stubFor(post(urlPathEqualTo("/api/sms/v1/text/single"))
            .willReturn(okJson(NEXTSMS_ACCEPTED)));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** Issue an offer and age it, so its 30-day deadline falls a given number of days from now. */
    private String offerClosingIn(long daysFromNow, String tag) throws Exception {
        TenantContext.set(SEEDED_TENANT);
        PartyView applicant = partyApi.registerIndividual("Reminder " + tag, LocalDate.of(1990, 1, 1),
            "+25571500" + String.format("%04d", Math.abs(tag.hashCode() % 10000)), null, "test-staff");
        ProductSummaryView product = productApi.createProduct(tag, "Reminder Product",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)), null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());

        String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), new PolicyApi.IssueRequest(
            applicant.partyId(), product.productId(), snapshot.productVersionId(),
            new BigDecimal("1000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY",
            null, List.of(), "Reminder sweep test"), "test-staff").policyNumber();

        // Age the row so the offer closes daysFromNow. Ageing is the only way to test a deadline
        // without waiting for it.
        long ageDays = 30 - daysFromNow;
        execute("UPDATE policy.policy SET created_at = now() - INTERVAL '" + ageDays + " days' "
            + "WHERE policy_number = '" + policyNumber + "'");
        return policyNumber;
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void runSweep() throws Exception {
        execute("SELECT communication.sweep_offer_reminders()");
    }

    private List<NotificationDispatch> remindersFor(String policyNumber) {
        TenantContext.set(SEEDED_TENANT);
        return dispatchRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtDesc(SEEDED_TENANT, policyNumber)
            .stream().filter(d -> "OFFER_CLOSING".equals(d.getTemplateKey())).toList();
    }

    @Test
    void queuesAReminderForAnOfferInsideTheWindow() throws Exception {
        String policyNumber = offerClosingIn(3, "REMIND-INSIDE");

        runSweep();

        assertThat(remindersFor(policyNumber)).singleElement()
            .satisfies(d -> assertThat(d.getStatus()).isEqualTo("PENDING"));
    }

    @Test
    void leavesAnOfferWithWeeksLeftAlone() throws Exception {
        // 20 days out against a 7-day reminder window. Reminding this customer now would train
        // them to ignore the message that arrives when it actually matters.
        String policyNumber = offerClosingIn(20, "REMIND-EARLY");

        runSweep();

        assertThat(remindersFor(policyNumber)).isEmpty();
    }

    @Test
    void leavesAnOfferWhoseDeadlineHasAlreadyPassedAlone() throws Exception {
        // Past its deadline and not yet swept to NOT_TAKEN_UP. Without the upper bound in the
        // sweep's window this customer would be reminded of a date that has already gone by.
        String policyNumber = offerClosingIn(-2, "REMIND-LATE");

        runSweep();

        assertThat(remindersFor(policyNumber)).isEmpty();
    }

    @Test
    void doesNotQueueASecondReminderOnASecondRun() throws Exception {
        // The sweep runs daily and the window is a week wide, so without this guard the same
        // customer is reminded seven times.
        String policyNumber = offerClosingIn(3, "REMIND-TWICE");

        runSweep();
        runSweep();
        runSweep();

        assertThat(remindersFor(policyNumber)).hasSize(1);
    }

    @Test
    void leavesAPolicyThatIsAlreadyInForceAlone() throws Exception {
        String policyNumber = offerClosingIn(3, "REMIND-PAID");
        TenantContext.set(SEEDED_TENANT);
        policyApi.activateOnFirstPremium(policyNumber);

        runSweep();

        assertThat(remindersFor(policyNumber))
            .as("somebody who has paid must never be chased to pay")
            .isEmpty();
    }

    @Test
    void theDispatcherSendsAQueuedReminderAndRecordsIt() throws Exception {
        String policyNumber = offerClosingIn(3, "REMIND-SEND");
        runSweep();
        UUID dispatchId = remindersFor(policyNumber).get(0).getDispatchId();

        dispatcher.dispatch(dispatchId, SEEDED_TENANT);

        assertThat(remindersFor(policyNumber)).singleElement()
            .satisfies(d -> assertThat(d.getStatus()).isEqualTo("SENT"));
        // What actually reached the customer, read off the wire. Selected by content rather than
        // position: issuing the policy in the fixture already sent an OFFER_MADE to this same
        // number, so indexing into the list would assert against whichever the ordering happened
        // to put first. "Kumbusho" is the Swahili reminder's opening word.
        String sent = smsGateway.getAllServeEvents().stream()
            .map(event -> event.getRequest().getBodyAsString())
            .filter(body -> body.contains("Kumbusho"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no reminder SMS was sent"));
        assertThat(sent).contains(policyNumber);
        // ON THE TANZANIAN CALENDAR, not the JVM's. This read `LocalDate.now()` and the
        // dispatcher read the UTC date, so the two agreed only outside 00:00-03:00 local --
        // EAT is UTC+3, and inside that window the assertion failed on a date the dispatcher
        // was rendering exactly as it had been written to. It was a real defect in the copy
        // rather than a flaky test: a customer reading the SMS at half past midnight was told
        // the offer closed a day before it did.
        assertThat(sent)
            .as("a reminder whose date is wrong is worse than no reminder")
            .contains(LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam")).plusDays(7).toString());
    }

    /**
     * The drain itself, as app_role, under RLS — the path that was broken in production while
     * every test passed.
     *
     * <p>Two separate faults hid behind the other tests in this class, both because they call
     * {@code dispatch(id, tenant)} directly and never go through the scheduled drain:
     *
     * <ol>
     *   <li>{@code communication/V1} granted app_role nothing, so the drain's query raised
     *       "permission denied for schema communication" on every pass.</li>
     *   <li>Once granted, the RLS predicate cast an empty string to uuid and raised — because the
     *       drain is the platform's first query that legitimately runs with NO tenant set, and
     *       {@code current_setting(..., true)} returns '' rather than NULL on a RESET GUC.</li>
     * </ol>
     *
     * <p>Both appeared only on a dev restart. This test is what would have caught them: it drives
     * the real scheduled method, over a queue produced for a tenant it has not set, as the
     * credential the application actually runs as.
     */
    @Test
    void theScheduledDrainFindsAndSendsAcrossTenantsWithNoTenantSet() throws Exception {
        String policyNumber = offerClosingIn(3, "REMIND-DRAIN");
        runSweep();
        assertThat(remindersFor(policyNumber)).singleElement()
            .satisfies(d -> assertThat(d.getStatus()).isEqualTo("PENDING"));

        // No ambient tenant, exactly as the scheduler runs it. An RLS-scoped query would find
        // nothing here and the reminder would sit PENDING forever.
        TenantContext.clear();
        dispatcher.drainPendingReminders();

        assertThat(remindersFor(policyNumber)).singleElement()
            .satisfies(d -> assertThat(d.getStatus()).isEqualTo("SENT"));
    }

    /**
     * The claim, which is what stops two application instances sending the same reminder twice.
     * A read-then-send would let both pass the PENDING check.
     */
    @Test
    void aSecondDispatcherFindsNothingToClaim() throws Exception {
        String policyNumber = offerClosingIn(3, "REMIND-CLAIM");
        runSweep();
        UUID dispatchId = remindersFor(policyNumber).get(0).getDispatchId();

        dispatcher.dispatch(dispatchId, SEEDED_TENANT);
        assertThat(remindersFor(policyNumber).get(0).getStatus())
            .as("the first pass must leave the row resolved, or nothing can stop a second send")
            .isEqualTo("SENT");
        // Counted as a delta rather than an absolute. Issuing the policy in the fixture above
        // already sent this customer their OFFER_MADE message through the real listener, so an
        // absolute verify(1) here would be measuring that too -- and would fail for a reason
        // that has nothing to do with claiming.
        int sendsBefore = smsGateway.getAllServeEvents().size();

        dispatcher.dispatch(dispatchId, SEEDED_TENANT);

        assertThat(smsGateway.getAllServeEvents().size())
            .as("a second dispatcher must find nothing to claim and send nothing")
            .isEqualTo(sendsBefore);
    }
}
