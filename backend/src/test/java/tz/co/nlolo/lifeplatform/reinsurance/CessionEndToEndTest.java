package tz.co.nlolo.lifeplatform.reinsurance;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
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
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyView;
import tz.co.nlolo.lifeplatform.reinsurance.domain.Cession;
import tz.co.nlolo.lifeplatform.reinsurance.domain.PolicyProjection;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.CessionRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 7, Step 6 -- the real end-to-end proof of cession on issuance: {@code
 * PolicyApi.issuePolicy} (real API) -> {@code policy.PolicyIssued} (real event) -> {@code
 * reinsurance.application.PolicyEventListener} -> {@code CessionCalculator} -> a real {@code
 * reinsurance.cession} row (or, for XOL/no-treaty/sub-retention, deliberately none) -> {@code
 * reinsurance.CessionRecorded} (real event, recorded via the AFTER_COMMIT {@link EventRecorder},
 * the same pattern {@code CommissionPayoutEndToEndTest} uses).
 *
 * <p>Runs against real Postgres as {@code app_role} (NOSUPERUSER NOBYPASSRLS), mirroring {@code
 * ReinsuranceApiIntegrationTest}'s bootstrap plus the full policy-issuance migration chain {@code
 * ClaimSettlementEndToEndTest} already uses.
 *
 * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} chains are synchronous, same thread:
 * {@code issuePolicy}'s own {@code @Transactional} commits when the call returns, firing {@code
 * PolicyEventListener} before control comes back to this test. No await/sleep anywhere here.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(CessionEndToEndTest.EventRecorderConfiguration.class)
class CessionEndToEndTest {

    private static final String APP_ROLE_PASSWORD = "cession_e2e_password";
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
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/reinsurance/V1__create_reinsurance_schema.sql",
            "db-migrations/reinsurance/V2__grants_rls_money_checks_reinsurer_and_projection.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    /** Records reinsurance.CessionRecorded so "exactly one, and none on redelivery" is assertable
     * directly rather than inferred from state -- same pattern as CommissionPayoutEndToEndTest. */
    @TestConfiguration
    static class EventRecorderConfiguration {
        @Bean
        EventRecorder cessionTestEventRecorder() { return new EventRecorder(); }
    }

    static class EventRecorder {
        private final List<DomainEventEnvelope<?>> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void record(DomainEventEnvelope<?> envelope) { received.add(envelope); }

        void clear() { received.clear(); }

        List<DomainEventEnvelope<?>> ofType(String eventType) {
            return received.stream().filter(e -> eventType.equals(e.eventType())).toList();
        }
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private ReinsuranceApi reinsuranceApi;
    @Autowired private CessionRepository cessionRepository;
    @Autowired private ReinsurancePolicyProjectionRepository policyProjectionRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EventRecorder eventRecorder;

    private TransactionTemplate transactionTemplate;

    @AfterEach
    void resetAfterEach() { TenantContext.clear(); }

    private TransactionTemplate transactionTemplate() {
        if (transactionTemplate == null) {
            transactionTemplate = new TransactionTemplate(transactionManager);
        }
        return transactionTemplate;
    }

    private record Fixture(UUID applicantId, UUID productId, UUID productVersionId) {}

    /** Mirrors ClaimSettlementEndToEndTest.buildFixture. */
    private Fixture buildFixture(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Cession E2E Applicant " + productCode, LocalDate.of(1985, 3, 1),
            "+25571700" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Cession E2E Product", ProductCategory.TERM_LIFE, CURRENCY, "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    private String issuePolicy(UUID tenantId, Fixture fixture, BigDecimal sumAssured, BigDecimal premium) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), sumAssured, CURRENCY, premium, CURRENCY, "MONTHLY", null, List.of(),
            "Cession E2E test");
        return policyApi.issuePolicy(null, request, "test-staff").policyNumber();
    }

    private TreatyView createTreaty(UUID tenantId, TreatyType type, BigDecimal retention, BigDecimal cessionPercent) {
        TenantContext.set(tenantId);
        return reinsuranceApi.createTreaty(new ReinsuranceApi.CreateTreatyRequest(
            "Africa Re", type, retention, CURRENCY, cessionPercent, LocalDate.now().minusMonths(1), null),
            "finance-officer");
    }

    @Test
    void aQuotaShareTreatyCedesTheStatedPercentageOfRiskAndPremium() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CESSION-E2E-QS");
        createTreaty(tenantId, TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), new BigDecimal("30.00"));
        eventRecorder.clear();

        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("2000000"), new BigDecimal("100000.00"));

        TenantContext.set(tenantId);
        List<Cession> cessions = cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber);
        assertThat(cessions).hasSize(1);
        Cession cession = cessions.get(0);
        assertThat(cession.getCededAmount()).isEqualByComparingTo("600000.00");
        assertThat(cession.getCededPremiumAmount()).isEqualByComparingTo("30000.00");
        assertThat(eventRecorder.ofType("reinsurance.CessionRecorded")).hasSize(1);
    }

    @Test
    void aSurplusTreatyCedesTheExcessOverRetention() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CESSION-E2E-SURPLUS");
        createTreaty(tenantId, TreatyType.SURPLUS, new BigDecimal("500000.00"), null);
        eventRecorder.clear();

        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("2000000"), new BigDecimal("100000.00"));

        TenantContext.set(tenantId);
        List<Cession> cessions = cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber);
        assertThat(cessions).hasSize(1);
        assertThat(cessions.get(0).getCededAmount()).isEqualByComparingTo("1500000.00");
        assertThat(eventRecorder.ofType("reinsurance.CessionRecorded")).hasSize(1);
    }

    @Test
    void aSurplusTreatyWithRetentionAboveTheSumAssuredCedesNothing() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CESSION-E2E-SURPLUS-NONE");
        createTreaty(tenantId, TreatyType.SURPLUS, new BigDecimal("5000000.00"), null);
        eventRecorder.clear();

        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("2000000"), new BigDecimal("100000.00"));

        TenantContext.set(tenantId);
        assertThat(cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber)).isEmpty();
        assertThat(eventRecorder.ofType("reinsurance.CessionRecorded")).isEmpty();
    }

    @Test
    void anXolTreatyCedesNothingAtIssuanceButTheProjectionRowIsStillWritten() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CESSION-E2E-XOL");
        createTreaty(tenantId, TreatyType.XOL, new BigDecimal("500000.00"), null);
        eventRecorder.clear();

        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("2000000"), new BigDecimal("100000.00"));

        TenantContext.set(tenantId);
        assertThat(cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber)).isEmpty();
        assertThat(eventRecorder.ofType("reinsurance.CessionRecorded")).isEmpty();

        TenantContext.set(tenantId);
        Optional<PolicyProjection> projection = policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber);
        assertThat(projection).as("XOL recovery depends on the projection row existing").isPresent();
        assertThat(projection.get().getSumAssuredAmount()).isEqualByComparingTo("2000000");
    }

    @Test
    void aPolicyIssuedWithNoActiveTreatyWritesTheProjectionButCedesNothing() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CESSION-E2E-NOTREATY");
        eventRecorder.clear();

        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("2000000"), new BigDecimal("100000.00"));

        TenantContext.set(tenantId);
        assertThat(cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber)).isEmpty();
        assertThat(eventRecorder.ofType("reinsurance.CessionRecorded")).isEmpty();
        TenantContext.set(tenantId);
        assertThat(policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber)).isPresent();
    }

    /**
     * A real at-least-once redelivery of {@code policy.PolicyIssued} -- the same envelope
     * republished through {@code ApplicationEventPublisher} inside a {@code TransactionTemplate}
     * (not a second call through {@code PolicyApi}, which cannot legally re-issue the same policy
     * number) -- must still leave exactly one cession row, backstopped by {@code ux_cession_once}
     * and made a no-op earlier by {@code ReinsuranceApiImpl.persistCession}'s existence check, and
     * must publish no second {@code CessionRecorded}.
     */
    @Test
    void aRedeliveredPolicyIssuedStillLeavesExactlyOneCessionAndPublishesNoSecondEvent() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CESSION-E2E-REDELIVER");
        createTreaty(tenantId, TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), new BigDecimal("30.00"));
        eventRecorder.clear();

        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("2000000"), new BigDecimal("100000.00"));
        TenantContext.set(tenantId);
        assertThat(cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber)).hasSize(1);
        assertThat(eventRecorder.ofType("reinsurance.CessionRecorded")).hasSize(1);

        // policy.PolicyIssued, field-for-field from PolicyApiImpl's published shape -- only the
        // fields PolicyEventListener actually reads.
        Map<String, Object> payload = Map.of(
            "policyNumber", policyNumber,
            "productId", fixture.productId(),
            "issueDate", LocalDate.now().toString(),
            "sumAssured", Map.of("amount", "2000000", "currencyCode", CURRENCY),
            "premium", Map.of("amount", "100000.00", "currencyCode", CURRENCY));
        var envelope = DomainEventEnvelope.of("policy.PolicyIssued", tenantId, payload);
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(envelope));

        TenantContext.set(tenantId);
        assertThat(cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber))
            .as("a redelivered PolicyIssued must not double-cede")
            .hasSize(1);
        assertThat(eventRecorder.ofType("reinsurance.CessionRecorded"))
            .as("a redelivered PolicyIssued must not publish a second CessionRecorded")
            .hasSize(1);
    }
}
