package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.domain.AuditLogEntry;
import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.product.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * M6 Task 2: closes the gap where PolicyStatus.MATURED/SURRENDERED existed but nothing could
 * ever reach them, and the gap where issuePolicy's underwritingCaseId parameter was accepted
 * and silently discarded. Mirrors PolicyApiIntegrationTest's fixture/migration/audit-log-assertion
 * idiom rather than inventing a new one.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class PolicyClaimClosureTest {

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
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private record Fixture(UUID applicantId, UUID productId, UUID productVersionId) {}

    private Fixture buildFixture(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Claim Closure Test Applicant " + productCode, LocalDate.of(1990, 1, 1),
            "+25571400" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Claim Closure Test Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    private String issueDirectly(UUID tenantId, Fixture fixture, UUID underwritingCaseId) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY", null, List.of(), "Claim closure test");
        return policyApi.issuePolicy(underwritingCaseId, request, "test-staff").policyNumber();
    }

    @Test
    void issuePolicyPersistsUnderwritingCaseIdAndGetPolicyReturnsIt() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIM-CLOSURE-UWC-01");
        UUID underwritingCaseId = UUID.randomUUID();
        String policyNumber = issueDirectly(tenantId, fixture, underwritingCaseId);

        TenantContext.set(tenantId);
        PolicyView view = policyApi.getPolicy(policyNumber);
        // Falsifiable proof for Step 2: fails if issuePolicy still discards the parameter.
        assertEquals(underwritingCaseId, view.underwritingCaseId());
    }

    @Test
    void markMaturedMovesAnActivePolicyToMaturedAndPublishesPolicyMatured() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIM-CLOSURE-MATURE-01");
        String policyNumber = issueDirectly(tenantId, fixture, UUID.randomUUID());

        TenantContext.set(tenantId);
        Instant before = Instant.now();
        policyApi.markMatured(policyNumber, "test-staff");

        assertEquals(PolicyStatus.MATURED, policyApi.getPolicy(policyNumber).status());

        List<AuditLogEntry> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "policy.PolicyMatured", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
        JsonNode payload = objectMapper.readTree(auditRows.get(0).getPayload());
        assertThat(payload.path("policyNumber").asText()).isEqualTo(policyNumber);
    }

    @Test
    void terminateForSettledClaimMovesAnActivePolicyToSurrenderedAndPublishesPolicySurrenderedWithClaimId() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIM-CLOSURE-TERM-01");
        String policyNumber = issueDirectly(tenantId, fixture, UUID.randomUUID());
        UUID claimId = UUID.randomUUID();

        TenantContext.set(tenantId);
        Instant before = Instant.now();
        policyApi.terminateForSettledClaim(policyNumber, claimId, "test-staff");

        assertEquals(PolicyStatus.SURRENDERED, policyApi.getPolicy(policyNumber).status());

        List<AuditLogEntry> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "policy.PolicySurrendered", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
        JsonNode payload = objectMapper.readTree(auditRows.get(0).getPayload());
        assertThat(payload.path("policyNumber").asText()).isEqualTo(policyNumber);
        assertThat(payload.path("claimId").asText()).isEqualTo(claimId.toString());
    }

    @Test
    void markMaturedIsIdempotentOnRepeatWithNoSecondEvent() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIM-CLOSURE-MATURE-IDEMP-01");
        String policyNumber = issueDirectly(tenantId, fixture, UUID.randomUUID());

        TenantContext.set(tenantId);
        Instant before = Instant.now();
        policyApi.markMatured(policyNumber, "test-staff");
        policyApi.markMatured(policyNumber, "test-staff"); // second call: silent no-op

        assertEquals(PolicyStatus.MATURED, policyApi.getPolicy(policyNumber).status());
        // Exactly one audit row across BOTH calls -- if the second call published a second
        // event, this would find two.
        List<AuditLogEntry> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "policy.PolicyMatured", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
    }

    @Test
    void terminateForSettledClaimIsIdempotentOnRepeatWithNoSecondEvent() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIM-CLOSURE-TERM-IDEMP-01");
        String policyNumber = issueDirectly(tenantId, fixture, UUID.randomUUID());
        UUID claimId = UUID.randomUUID();

        TenantContext.set(tenantId);
        Instant before = Instant.now();
        policyApi.terminateForSettledClaim(policyNumber, claimId, "test-staff");
        policyApi.terminateForSettledClaim(policyNumber, claimId, "test-staff"); // second call: silent no-op

        assertEquals(PolicyStatus.SURRENDERED, policyApi.getPolicy(policyNumber).status());
        // Exactly one audit row across BOTH calls -- if the second call published a second
        // event, this would find two.
        List<AuditLogEntry> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "policy.PolicySurrendered", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
    }

    @Test
    void markMaturedRejectsAnAlreadyLapsedPolicy() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIM-CLOSURE-MATURE-LAPSED-01");
        String policyNumber = issueDirectly(tenantId, fixture, UUID.randomUUID());

        TenantContext.set(tenantId);
        policyApi.lapsePolicy(policyNumber, "test-staff");
        assertThrows(InvalidPolicyStateException.class, () -> policyApi.markMatured(policyNumber, "test-staff"));
    }

    @Test
    void terminateForSettledClaimRejectsAnAlreadyLapsedPolicy() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIM-CLOSURE-TERM-LAPSED-01");
        String policyNumber = issueDirectly(tenantId, fixture, UUID.randomUUID());

        TenantContext.set(tenantId);
        policyApi.lapsePolicy(policyNumber, "test-staff");
        UUID claimId = UUID.randomUUID();
        assertThrows(InvalidPolicyStateException.class, () -> policyApi.terminateForSettledClaim(policyNumber, claimId, "test-staff"));
    }
}
