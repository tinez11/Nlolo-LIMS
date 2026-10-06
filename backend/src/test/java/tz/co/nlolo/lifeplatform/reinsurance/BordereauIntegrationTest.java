package tz.co.nlolo.lifeplatform.reinsurance;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reinsurance.api.BordereauView;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceValidationException;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyView;
import tz.co.nlolo.lifeplatform.reinsurance.application.BordereauJob;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.ReinsurancePolicyProjectionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * IFRS 17 I3c -- the monthly bordereau end to end inside reinsurance: policy events -> cover periods and the
 * projection's frequency/premium end -> {@link BordereauJob} -> a bordereau per treaty and month, final once written,
 * and {@code reinsurance.BordereauPosted} for every month that charged something (finaccounting posts it: K-01/K-02,
 * proven in {@code ReinsuranceAndLoanPostingEndToEndTest}).
 *
 * <p>Policy events are published directly, as {@code RecoveryEndToEndTest} does for its synthetic cases: what is under
 * test is how reinsurance reads them, and the reinsurance migrations alone carry it. Treaties are effective from
 * 1 January 2026 and every case drains to a fixed month, so the months are deterministic whatever day this runs.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(BordereauIntegrationTest.EventRecorderConfiguration.class)
class BordereauIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "bordereau_it_password";
    private static final String CURRENCY = "TZS";
    private static final LocalDate TREATY_FROM = LocalDate.of(2026, 1, 1);

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
            "db-migrations/reinsurance/V1__create_reinsurance_schema.sql",
            "db-migrations/reinsurance/V2__grants_rls_money_checks_reinsurer_and_projection.sql",
            "db-migrations/reinsurance/V4__projection_product_category.sql",
            "db-migrations/reinsurance/V5__bordereau.sql",
            "db-migrations/reinsurance/V6__scheme_may_open_empty.sql",
            "db-migrations/reinsurance/V7__statement.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @TestConfiguration
    static class EventRecorderConfiguration {
        @Bean
        EventRecorder bordereauTestEventRecorder() { return new EventRecorder(); }
    }

    static class EventRecorder {
        private final List<DomainEventEnvelope<?>> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void record(DomainEventEnvelope<?> envelope) { received.add(envelope); }

        List<DomainEventEnvelope<?>> bordereauxOf(UUID tenantId) {
            return received.stream().filter(e -> "reinsurance.BordereauPosted".equals(e.eventType())
                && tenantId.equals(e.tenantId())).toList();
        }
    }

    @Autowired private ReinsuranceApi reinsuranceApi;
    @Autowired private BordereauJob bordereauJob;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EventRecorder eventRecorder;
    @Autowired private ReinsurancePolicyProjectionRepository policyProjections;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private void publish(UUID tenantId, String type, Map<String, Object> payload) {
        TenantContext.set(tenantId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            eventPublisher.publishEvent(DomainEventEnvelope.of(type, tenantId, payload)));
    }

    private TreatyView quotaShare(UUID tenantId, String percent, String commission) {
        TenantContext.set(tenantId);
        return reinsuranceApi.createTreaty(new ReinsuranceApi.CreateTreatyRequest("Africa Re", TreatyType.QUOTA_SHARE,
            new BigDecimal("0.00"), CURRENCY, new BigDecimal(percent), new BigDecimal(commission), null,
            TREATY_FROM, null), "finance-officer");
    }

    private String activate(UUID tenantId, String category, String frequency, String premium, LocalDate on) {
        return activate(tenantId, category, frequency, premium, on, "2000000.00");
    }

    private String activate(UUID tenantId, String category, String frequency, String premium, LocalDate on,
                            String sumAssured) {
        String policyNumber = "POL-BDX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Map<String, Object> payload = new HashMap<>();
        payload.put("policyNumber", policyNumber);
        payload.put("productId", UUID.randomUUID());
        payload.put("productCategory", category);
        payload.put("issueDate", on.toString());
        payload.put("activatedAt", on.toString());
        payload.put("premiumFrequency", frequency);
        payload.put("sumAssured", Map.of("amount", sumAssured, "currencyCode", CURRENCY));
        payload.put("premium", Map.of("amount", premium, "currencyCode", CURRENCY));
        publish(tenantId, "policy.PolicyActivated", payload);
        return policyNumber;
    }

    /** Month -> the written bordereau's premium; months with no bordereau are absent. */
    private Map<String, BigDecimal> premiumsByMonth(UUID tenantId, UUID treatyId) {
        TenantContext.set(tenantId);
        Map<String, BigDecimal> byMonth = new HashMap<>();
        reinsuranceApi.listBordereaux(treatyId).forEach(b -> byMonth.put(b.period(), b.premium()));
        return byMonth;
    }

    /**
     * A full month for every month on risk at any point; nothing for a month cover had ended before; a lapse in the
     * middle of a month still charges that month; a reinstatement charges again. Monthly 100,000 at 50% = 50,000,
     * less the treaty's 20% commission = 10,000.
     */
    @Test
    void chargesEveryMonthOnRiskAtAnyPointAndRestartsOnReinstatement() {
        UUID tenantId = UUID.randomUUID();
        TreatyView treaty = quotaShare(tenantId, "50.00", "20.00");
        String policy = activate(tenantId, "TERM_LIFE", "MONTHLY", "100000.00", LocalDate.of(2026, 2, 10));
        publish(tenantId, "policy.PolicyLapsed", Map.of("policyNumber", policy, "lapsedAt", "2026-03-15T09:00:00Z"));

        bordereauJob.drain(LocalDate.of(2026, 5, 1));

        Map<String, BigDecimal> premiums = premiumsByMonth(tenantId, treaty.treatyId());
        assertThat(premiums).containsOnlyKeys("2026-01", "2026-02", "2026-03", "2026-04");
        assertThat(premiums.get("2026-01")).isZero();
        assertThat(premiums.get("2026-02")).isEqualByComparingTo("50000.00");
        assertThat(premiums.get("2026-03")).as("lapsed mid-March: March is still charged").isEqualByComparingTo("50000.00");
        assertThat(premiums.get("2026-04")).isZero();
        assertThat(eventRecorder.bordereauxOf(tenantId)).as("only months that charged something are posted")
            .hasSize(2).allSatisfy(e -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) e.payload();
                assertThat(p.get("premium")).isEqualTo(Map.of("amount", "50000.00", "currencyCode", CURRENCY));
                assertThat(p.get("commission")).isEqualTo(Map.of("amount", "10000.00", "currencyCode", CURRENCY));
            });

        // Final once written: a second run writes and publishes nothing.
        bordereauJob.drain(LocalDate.of(2026, 5, 1));
        assertThat(eventRecorder.bordereauxOf(tenantId)).hasSize(2);

        publish(tenantId, "policy.PolicyReinstated", Map.of("policyNumber", policy, "reinstatedAt", "2026-05-03T08:00:00Z"));
        bordereauJob.drain(LocalDate.of(2026, 6, 1));
        assertThat(premiumsByMonth(tenantId, treaty.treatyId()).get("2026-05")).isEqualByComparingTo("50000.00");

        TenantContext.set(tenantId);
        UUID mayId = reinsuranceApi.listBordereaux(treaty.treatyId()).get(0).bordereauId();
        TenantContext.set(tenantId);
        BordereauView may = reinsuranceApi.getBordereau(mayId);
        assertThat(may.period()).isEqualTo("2026-05");
        assertThat(may.policyCount()).isEqualTo(1);
        assertThat(may.lines()).singleElement().satisfies(l -> {
            assertThat(l.type()).isEqualTo("PREMIUM");
            assertThat(l.policyNumber()).isEqualTo(policy);
            assertThat(l.premiumShare()).isEqualByComparingTo("0.5");
            assertThat(l.policyPremium()).isEqualByComparingTo("100000.00");
            assertThat(l.commission()).isEqualByComparingTo("10000.00");
        });
    }

    /** A quarterly 300,000 is 100,000 a month; the reinsurer's premium stops the month after the policy goes paid up. */
    @Test
    void aQuarterlyPremiumIsChargedMonthlyAndStopsAfterPaidUp() {
        UUID tenantId = UUID.randomUUID();
        TreatyView treaty = quotaShare(tenantId, "50.00", "0");
        String policy = activate(tenantId, "ENDOWMENT", "QUARTERLY", "300000.00", LocalDate.of(2026, 1, 20));
        publish(tenantId, "policy.PolicyMadePaidUp", Map.of("policyNumber", policy, "madePaidUpAt", "2026-02-20T10:00:00Z"));

        bordereauJob.drain(LocalDate.of(2026, 4, 1));

        Map<String, BigDecimal> premiums = premiumsByMonth(tenantId, treaty.treatyId());
        assertThat(premiums.get("2026-01")).isEqualByComparingTo("50000.00");
        assertThat(premiums.get("2026-02")).isEqualByComparingTo("50000.00");
        assertThat(premiums.get("2026-03")).as("paid up in February: no premium, so none ceded").isZero();
    }

    /** A free-look cancellation voids cover from inception: the month it began is not charged. */
    @Test
    void aFreeLookCancellationIsNeverCharged() {
        UUID tenantId = UUID.randomUUID();
        TreatyView treaty = quotaShare(tenantId, "50.00", "0");
        String policy = activate(tenantId, "TERM_LIFE", "MONTHLY", "100000.00", LocalDate.of(2026, 2, 10));
        publish(tenantId, "policy.PolicyCancelledFreeLook", Map.of("policyNumber", policy,
            "cancelledAt", "2026-02-20T10:00:00Z", "cancelledBy", "staff"));

        bordereauJob.drain(LocalDate.of(2026, 3, 1));

        assertThat(premiumsByMonth(tenantId, treaty.treatyId()).get("2026-02")).isZero();
        assertThat(eventRecorder.bordereauxOf(tenantId)).isEmpty();
    }

    /** A scheme is never ceded (client 2026-09-22), so no bordereau charges it. */
    @Test
    void aSchemeIsNeverOnABordereau() {
        UUID tenantId = UUID.randomUUID();
        TreatyView treaty = quotaShare(tenantId, "50.00", "0");
        activate(tenantId, "CREDIT_LIFE", "SINGLE", "900000.00", LocalDate.of(2026, 2, 10));

        bordereauJob.drain(LocalDate.of(2026, 3, 1));

        assertThat(premiumsByMonth(tenantId, treaty.treatyId()).get("2026-02")).isZero();
    }

    /**
     * A credit-life scheme is set up with no borrowers -- they arrive with the lender's first file -- so it activates
     * with a total of zero. Until reinsurance V6 the projection's CHECK refused it, the activation failed, and a later
     * claim on the scheme read as a "pre-M8 policy" instead of a scheme.
     */
    @Test
    void aSchemeSetUpWithNoBorrowersIsStillKnownAsAScheme() {
        UUID tenantId = UUID.randomUUID();
        String scheme = activate(tenantId, "CREDIT_LIFE", "SINGLE", "52000.00", LocalDate.of(2026, 2, 10), "0.00");

        TenantContext.set(tenantId);
        assertThat(policyProjections.findByTenantIdAndPolicyNumber(tenantId, scheme))
            .hasValueSatisfying(p -> {
                assertThat(p.isScheme()).isTrue();
                assertThat(p.getSumAssuredAmount()).isEqualByComparingTo("0.00");
            });
    }

    /** A single premium is charged once, its share, in the month cover began. */
    @Test
    void aSinglePremiumIsChargedOnceInTheMonthCoverBegan() {
        UUID tenantId = UUID.randomUUID();
        TreatyView treaty = quotaShare(tenantId, "40.00", "10.00");
        activate(tenantId, "WHOLE_LIFE", "SINGLE", "1000000.00", LocalDate.of(2026, 2, 10));

        bordereauJob.drain(LocalDate.of(2026, 4, 1));

        Map<String, BigDecimal> premiums = premiumsByMonth(tenantId, treaty.treatyId());
        assertThat(premiums.get("2026-02")).isEqualByComparingTo("400000.00");
        assertThat(premiums.get("2026-03")).isZero();
    }

    /** An XOL treaty's flat annual premium, a twelfth a month, with no policy lines. */
    @Test
    void anXolTreatyChargesATwelfthOfItsAnnualPremiumEachMonth() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        TreatyView treaty = reinsuranceApi.createTreaty(new ReinsuranceApi.CreateTreatyRequest("Munich Re",
            TreatyType.XOL, new BigDecimal("1500000.00"), CURRENCY, null, new BigDecimal("5.00"),
            new BigDecimal("120000.00"), TREATY_FROM, null), "finance-officer");
        assertThat(treaty.xolAnnualPremium()).isEqualByComparingTo("120000.00");

        bordereauJob.drain(LocalDate.of(2026, 3, 1));

        TenantContext.set(tenantId);
        List<BordereauView> written = reinsuranceApi.listBordereaux(treaty.treatyId());
        assertThat(written).extracting(BordereauView::period).containsExactly("2026-02", "2026-01");
        assertThat(written).allSatisfy(b -> {
            assertThat(b.premium()).isEqualByComparingTo("10000.00");
            assertThat(b.commission()).isEqualByComparingTo("500.00");
            assertThat(b.policyCount()).isZero();
        });
    }

    /** The current month as it would be written now -- including the recoveries recorded in it, matched only. */
    @Test
    void thePreviewShowsTheCurrentMonthWithItsRecoveriesAndStoresNothing() {
        UUID tenantId = UUID.randomUUID();
        TreatyView treaty = quotaShare(tenantId, "50.00", "0");
        String policy = activate(tenantId, "TERM_LIFE", "MONTHLY", "100000.00", LocalDate.of(2026, 2, 10));
        UUID claimId = UUID.randomUUID();
        publish(tenantId, "claims.ClaimApproved", Map.of("claimId", claimId, "policyNumber", policy,
            "approvedAmount", Map.of("amount", "2000000", "currencyCode", CURRENCY), "investmentComponent", "0"));

        TenantContext.set(tenantId);
        BordereauView preview = reinsuranceApi.previewBordereau(treaty.treatyId(), YearMonth.now(BordereauJobZone.CIVIL));

        assertThat(preview.bordereauId()).isNull();
        assertThat(preview.premium()).isEqualByComparingTo("50000.00");
        assertThat(preview.recoveries()).isEqualByComparingTo("1000000.00");
        assertThat(preview.lines()).filteredOn(l -> "RECOVERY".equals(l.type())).singleElement()
            .satisfies(l -> assertThat(l.claimId()).isEqualTo(claimId));
        TenantContext.set(tenantId);
        assertThat(reinsuranceApi.listBordereaux(treaty.treatyId())).isEmpty();
    }

    @Test
    void everyTreatyStatesItsCommissionAndOnlyXolCarriesAFlatPremium() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        assertThrows(ReinsuranceValidationException.class, () -> reinsuranceApi.createTreaty(
            new ReinsuranceApi.CreateTreatyRequest("Africa Re", TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), CURRENCY,
                new BigDecimal("50.00"), null, null, TREATY_FROM, null), "finance-officer"));
        assertThrows(ReinsuranceValidationException.class, () -> reinsuranceApi.createTreaty(
            new ReinsuranceApi.CreateTreatyRequest("Africa Re", TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), CURRENCY,
                new BigDecimal("50.00"), new BigDecimal("0"), new BigDecimal("1000.00"), TREATY_FROM, null),
            "finance-officer"));
        assertThrows(ReinsuranceValidationException.class, () -> reinsuranceApi.createTreaty(
            new ReinsuranceApi.CreateTreatyRequest("Africa Re", TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), CURRENCY,
                new BigDecimal("50.00"), new BigDecimal("100.01"), null, TREATY_FROM, null), "finance-officer"));
    }

    /** Dar es Salaam, the calendar the job and the preview use. */
    private static final class BordereauJobZone {
        static final java.time.ZoneId CIVIL = java.time.ZoneId.of("Africa/Dar_es_Salaam");
    }
}
