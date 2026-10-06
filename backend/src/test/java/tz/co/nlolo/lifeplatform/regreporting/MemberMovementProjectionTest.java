package tz.co.nlolo.lifeplatform.regreporting;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.PolicyDimensionRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.PolicyMovementRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.MetricReaderRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The member-movement projection: a scheme's cover follows its member schedule.
 *
 * <p>Until Plan 5, {@code policy.GroupMemberAdded} and {@code policy.GroupMemberExited} had no
 * consumer anywhere, so {@code policy_dimension.sum_assured_amount} was frozen at whatever the
 * scheme was activated with. On credit life that is continuous drift in both directions —
 * borrowers arrive monthly in files of several hundred and leave on every settled claim (design
 * spec §2.14).
 *
 * <p><b>The handler projects a DELTA</b> between the total each event carries and the total the
 * dimension last recorded, rather than the joining or leaving member's own cover. That is what
 * makes {@link #aRedeliveredMemberEventChangesNothing} pass: {@code regreporting} has no
 * de-duplication anywhere, and every other handler in this module double-counts a redelivered
 * event. Idempotency here falls out of the arithmetic instead of needing a mechanism the module
 * does not have.
 *
 * <p>Events are published for real through {@code ApplicationEventPublisher} inside a committing
 * transaction, never hand-inserted, so the whole {@code AFTER_COMMIT} chain runs exactly as it
 * would in production. Real Postgres, real RLS, {@code app_role} (NOSUPERUSER NOBYPASSRLS) — the
 * harness shape {@code MissingDimensionTest} uses.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class MemberMovementProjectionTest {

    private static final String APP_ROLE_PASSWORD = "regreporting_member_movement_password";
    private static final String CURRENCY = "TZS";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_role");
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
    }

    @BeforeAll
    static void applyMigrationsAndBootstrapAppRole() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
            "db-migrations/regreporting/V1__create_regreporting_schema.sql",
            "db-migrations/regreporting/V2__grants_rls_dimensions_movements_and_return_lines.sql",
            "db-migrations/regreporting/V3__optimistic_locking_on_movement_tables.sql",
            "db-migrations/regreporting/V5__member_movement_columns.sql",
            "db-migrations/regreporting/V6__free_look_cancellation_movement.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PolicyMovementRepository policyMovementRepository;
    @Autowired private PolicyDimensionRepository policyDimensionRepository;
    @Autowired private MeterRegistry meterRegistry;

    private TransactionTemplate transactionTemplate;

    @AfterEach
    void resetAfterEach() { TenantContext.clear(); }

    // ---- the tests ----------------------------------------------------------

    @Test
    void aJoiningMemberRaisesInForceSumAssured() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String scheme = "POL-JOIN-" + shortId();
        activateScheme(tenantId, scheme, productId, "1000000.00");

        memberAdded(tenantId, scheme, "1250000.00");

        assertThat(inForceSumAssured(tenantId)).isEqualByComparingTo("1250000.00");
        // The flow metric does not move: whether a joiner is "new business written" is an
        // unanswered actuarial question, and Plan 5 refuses to answer it by accident.
        assertThat(newBusinessSumAssured(tenantId)).isEqualByComparingTo("1000000.00");
        assertThat(dimensionTotal(tenantId, scheme)).isEqualByComparingTo("1250000.00");
    }

    @Test
    void aLeavingMemberLowersInForceSumAssured() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String scheme = "POL-LEAVE-" + shortId();
        activateScheme(tenantId, scheme, productId, "1000000.00");

        memberExited(tenantId, scheme, "600000.00", "CLAIM_SETTLED");

        assertThat(inForceSumAssured(tenantId)).isEqualByComparingTo("600000.00");
        assertThat(dimensionTotal(tenantId, scheme)).isEqualByComparingTo("600000.00");
    }

    @Test
    void aRedeliveredMemberEventChangesNothing() {
        // THE REASON THIS PROJECTS A DELTA RATHER THAN THE MEMBER'S OWN COVER. regreporting has no
        // de-duplication anywhere -- every other handler in this module double-counts on
        // redelivery. Here the second delivery finds the dimension already equal to the total it
        // carries, computes a delta of zero, and does nothing.
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String scheme = "POL-REDLV-" + shortId();
        activateScheme(tenantId, scheme, productId, "1000000.00");

        memberAdded(tenantId, scheme, "1250000.00");
        memberAdded(tenantId, scheme, "1250000.00");

        assertThat(inForceSumAssured(tenantId)).isEqualByComparingTo("1250000.00");
    }

    @Test
    void theLastMemberLeavingIsLeftToTheCloseEvent() {
        // THE MOST IMPORTANT TEST IN THIS PLAN. When the last member goes, PolicyApiImpl restates
        // the scheme to ZERO and closes it, and policy.PolicySurrendered then terminates the last
        // recorded total. If this handler also acted it would (a) try to write 0 into a column
        // whose CHECK forbids it and (b) remove cover the close is about to remove again.
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String scheme = "POL-LAST-" + shortId();
        activateScheme(tenantId, scheme, productId, "1000000.00");

        memberExited(tenantId, scheme, "0.00", "CLAIM_SETTLED");

        // Untouched: the dimension still carries the last positive total, and no member movement
        // was recorded.
        assertThat(dimensionTotal(tenantId, scheme)).isEqualByComparingTo("1000000.00");
        assertThat(inForceSumAssured(tenantId)).isEqualByComparingTo("1000000.00");

        // And the close event then takes the whole scheme out exactly once.
        publish(tenantId, "policy.PolicySurrendered", Map.of(
            "policyNumber", scheme,
            "surrenderedAt", Instant.now().toString()));
        assertThat(inForceSumAssured(tenantId)).isEqualByComparingTo("0.00");
    }

    @Test
    void aMemberEventForAnUnknownSchemeIsCountedAndDropped() {
        // A scheme regreporting never saw PolicyActivated for. Unlike a termination -- which can
        // fall back to the UNKNOWN product sentinel because the event itself carries no amount --
        // a member delta is meaningless without the previous total. Attributing it to the sentinel
        // would book the WHOLE scheme total as a movement against a product that does not exist.
        // Dropped, but counted: a silent drop is how a projection goes quietly wrong.
        UUID tenantId = UUID.randomUUID();
        String scheme = "POL-UNKNOWN-" + shortId();
        double before = unattributedCount("policy.GroupMemberAdded");

        memberAdded(tenantId, scheme, "1250000.00");

        assertThat(unattributedCount("policy.GroupMemberAdded")).isEqualTo(before + 1);
        assertThat(policyDimensionRepository.findByTenantIdAndPolicyNumber(tenantId, scheme)).isEmpty();
    }

    @Test
    void theMovementLandsInThePeriodTheCOVERCHANGED() {
        // Not the scheme's issue quarter. A borrower enrolled in a later quarter is that quarter's
        // movement even on a scheme issued long before -- otherwise every file a lender sends for
        // the rest of the scheme's life would be reported in the quarter it opened.
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String scheme = "POL-PERIOD-" + shortId();
        activateScheme(tenantId, scheme, productId, "1000000.00");

        // joinedOn deliberately a quarter after the scheme's issue date.
        memberAddedOn(tenantId, scheme, "1250000.00", ISSUED.plusMonths(4));

        assertThat(policyMovementRepository
            .findByTenantIdAndPeriodAndProductId(tenantId, quarterOf(ISSUED.plusMonths(4)), productId))
            .as("the joiner belongs to the quarter they joined in")
            .isPresent();
    }

    // ---- fixtures -----------------------------------------------------------

    private static final LocalDate ISSUED = LocalDate.of(2026, 8, 3);

    private static String shortId() { return UUID.randomUUID().toString().substring(0, 6).toUpperCase(); }

    private static String quarterOf(LocalDate date) {
        return date.getYear() + "-Q" + ((date.getMonthValue() - 1) / 3 + 1);
    }

    private TransactionTemplate transactionTemplate() {
        if (transactionTemplate == null) {
            transactionTemplate = new TransactionTemplate(transactionManager);
        }
        return transactionTemplate;
    }

    private void publish(UUID tenantId, String eventType, Map<String, Object> payload) {
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status ->
            eventPublisher.publishEvent(DomainEventEnvelope.of(eventType, tenantId, payload)));
    }

    /** The real policy.PolicyActivated shape -- the only event that creates the dimension row
     * every later movement depends on. */
    private void activateScheme(UUID tenantId, String policyNumber, UUID productId, String total) {
        publish(tenantId, "policy.PolicyActivated", Map.of(
            "policyNumber", policyNumber,
            "productId", productId,
            "productCategory", "CREDIT_LIFE",
            "issueDate", ISSUED.toString(),
            "sumAssured", Map.of("amount", total, "currencyCode", CURRENCY),
            "premium", Map.of("amount", "52000.00", "currencyCode", CURRENCY)));
    }

    private void memberAdded(UUID tenantId, String policyNumber, String schemeTotal) {
        memberAddedOn(tenantId, policyNumber, schemeTotal, ISSUED);
    }

    /** Field-for-field what PolicyApiImpl.addMember publishes. memberPartyId is carried as an
     * explicit null because a FREEFORM credit-life borrower has no party id -- which is also why
     * this event has no member id a consumer could key on, and why the handler keys on nothing. */
    private void memberAddedOn(UUID tenantId, String policyNumber, String schemeTotal, LocalDate joinedOn) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyNumber", policyNumber);
        payload.put("memberPartyId", null);
        payload.put("memberType", "FREEFORM");
        payload.put("memberName", "Amina Hassan Mwinyi");
        payload.put("joinedOn", joinedOn.toString());
        payload.put("coveredAmount", Map.of("amount", "250000.00", "currencyCode", CURRENCY));
        payload.put("underwritingStatus", "WITHIN_FCL");
        payload.put("schemeTotalCovered", Map.of("amount", schemeTotal, "currencyCode", CURRENCY));
        publish(tenantId, "policy.GroupMemberAdded", payload);
    }

    private void memberExited(UUID tenantId, String policyNumber, String schemeTotal, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyNumber", policyNumber);
        payload.put("policyMemberId", UUID.randomUUID());
        payload.put("leftOn", ISSUED.plusMonths(6).toString());
        payload.put("reason", reason);
        payload.put("schemeTotalCovered", Map.of("amount", schemeTotal, "currencyCode", CURRENCY));
        publish(tenantId, "policy.GroupMemberExited", payload);
    }

    /** Each test uses a fresh tenant, so tenant-and-period scoping isolates it and the existing
     * finders are enough -- no repository method was added for this. */
    private BigDecimal inForceSumAssured(UUID tenantId) {
        TenantContext.set(tenantId);
        String asOf = quarterOf(ISSUED.plusMonths(6));
        return MetricReaderRegistry.cumulativeSumAssured(
            policyMovementRepository.findByTenantIdAndPeriodLessThanEqual(tenantId, asOf), asOf);
    }

    /** sumSumAssuredIssued is a FLOW metric and expects the rows for ONE period only. */
    private BigDecimal newBusinessSumAssured(UUID tenantId) {
        TenantContext.set(tenantId);
        return MetricReaderRegistry.sumSumAssuredIssued(
            policyMovementRepository.findByTenantIdAndPeriod(tenantId, quarterOf(ISSUED)));
    }

    private BigDecimal dimensionTotal(UUID tenantId, String policyNumber) {
        TenantContext.set(tenantId);
        return policyDimensionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber)
            .orElseThrow(() -> new AssertionError("No policy_dimension row for " + policyNumber))
            .getSumAssuredAmount();
    }

    private double unattributedCount(String eventType) {
        return meterRegistry.counter("lifeplatform_regreporting_unattributed_movement_total",
            "eventType", eventType).count();
    }
}
