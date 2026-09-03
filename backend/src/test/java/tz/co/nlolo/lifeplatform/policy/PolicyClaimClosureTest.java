package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.domain.AuditLogEntry;
import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.policy.domain.Policy;
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
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/product/V6__eligibility_bounds.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
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
        return buildFixture(tenantId, productCode, ProductCategory.TERM_LIFE);
    }

    /** Category overload, mirroring {@code BillingApiIntegrationTest.buildFixture}'s own: only
     * GROUP_LIFE is seeded into POLICY_SUSPENSION_ELIGIBLE_CATEGORIES (refdata/V2), so a test that
     * needs a genuinely SUSPENDED policy cannot use the TERM_LIFE default. */
    private Fixture buildFixture(UUID tenantId, String productCode, ProductCategory category) {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Claim Closure Test Applicant " + productCode, LocalDate.of(1990, 1, 1),
            "+25571400" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Claim Closure Test Product", category, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
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

    // ============================================================================================
    // M6 final-review fix (C1, part 2): the accepted source states are now ACTIVE, REINSTATED,
    // LAPSED and SUSPENDED, and EITHER terminal status satisfies the post-condition.
    //
    // The two tests below replace earlier ones that asserted the OPPOSITE for LAPSED
    // (markMaturedRejectsAnAlreadyLapsedPolicy / terminateForSettledClaimRejectsAnAlreadyLapsedPolicy).
    // That old behaviour was the Critical: PolicyLapseRecommendedEventListener lapses a policy
    // unattended at dunning level >= 5, a deceased policyholder stops paying premiums, so any death
    // claim whose assessment outlasted dunning escalation hit the throw -- and inside claims'
    // settlement listener that exception rolled back the claim's own SETTLED transition after the
    // money had already left. Rejecting a LAPSED policy also left billing invoicing a policy whose
    // claim had been paid out, which is the exact condition closing the policy exists to prevent.
    // ============================================================================================

    @Test
    void markMaturedClosesAnAlreadyLapsedPolicy() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIM-CLOSURE-MATURE-LAPSED-01");
        String policyNumber = issueDirectly(tenantId, fixture, UUID.randomUUID());

        TenantContext.set(tenantId);
        policyApi.lapsePolicy(policyNumber, "test-staff");
        policyApi.markMatured(policyNumber, "test-staff");

        assertEquals(PolicyStatus.MATURED, policyApi.getPolicy(policyNumber).status());
    }

    @Test
    void terminateForSettledClaimClosesAnAlreadyLapsedPolicy() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIM-CLOSURE-TERM-LAPSED-01");
        String policyNumber = issueDirectly(tenantId, fixture, UUID.randomUUID());

        TenantContext.set(tenantId);
        policyApi.lapsePolicy(policyNumber, "test-staff");
        policyApi.terminateForSettledClaim(policyNumber, UUID.randomUUID(), "test-staff");

        assertEquals(PolicyStatus.SURRENDERED, policyApi.getPolicy(policyNumber).status());
    }

    @Test
    void terminateForSettledClaimClosesASuspendedPolicy() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIM-CLOSURE-TERM-SUSP-01", ProductCategory.GROUP_LIFE);
        String policyNumber = issueDirectly(tenantId, fixture, UUID.randomUUID());

        TenantContext.set(tenantId);
        policyApi.suspendPolicy(policyNumber, "Under investigation", "test-staff");
        policyApi.terminateForSettledClaim(policyNumber, UUID.randomUUID(), "test-staff");

        assertEquals(PolicyStatus.SURRENDERED, policyApi.getPolicy(policyNumber).status());
    }

    /** The sibling-terminal-status case: a SECOND claim settling against a policy the FIRST one
     * already closed must be a silent no-op, not an error. Previously each method no-opped only on
     * its OWN target status and threw on the other one -- which, from inside claims' settlement
     * listener, was the same Critical as the LAPSED case above. Asserts BOTH directions and that
     * neither publishes a second closure event announcing a transition that did not happen. */
    @Test
    void eitherClosureMethodIsASilentNoOpOnAnAlreadyClosedPolicy() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIM-CLOSURE-XTERMINAL-01");
        String maturedFirst = issueDirectly(tenantId, fixture, UUID.randomUUID());
        String surrenderedFirst = issueDirectly(tenantId, fixture, UUID.randomUUID());

        TenantContext.set(tenantId);
        Instant before = Instant.now();

        policyApi.markMatured(maturedFirst, "test-staff");
        policyApi.terminateForSettledClaim(maturedFirst, UUID.randomUUID(), "test-staff"); // no-op
        assertEquals(PolicyStatus.MATURED, policyApi.getPolicy(maturedFirst).status());

        policyApi.terminateForSettledClaim(surrenderedFirst, UUID.randomUUID(), "test-staff");
        policyApi.markMatured(surrenderedFirst, "test-staff"); // no-op
        assertEquals(PolicyStatus.SURRENDERED, policyApi.getPolicy(surrenderedFirst).status());

        // One PolicyMatured and one PolicySurrendered across all four calls -- the no-ops must not
        // announce a closure they did not perform.
        assertThat(auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "policy.PolicyMatured", before.minusSeconds(5), Instant.now().plusSeconds(5))).hasSize(1);
        assertThat(auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "policy.PolicySurrendered", before.minusSeconds(5), Instant.now().plusSeconds(5))).hasSize(1);
    }

    /** The guards were WIDENED, not removed: a genuinely nonsensical source state still throws.
     * PROPOSED is unreachable for an issued policy through any published API (issuePolicy activates
     * immediately and nothing transitions back), so this asserts against the domain object directly
     * -- a pure unit assertion, deliberately in this class rather than a new one, so the widened and
     * the still-rejected states are read side by side. */
    @Test
    void neitherClosureMethodAcceptsAProposedPolicy() {
        Policy proposed = new Policy("POL-PROPOSED-01", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "TERM_LIFE", null, new BigDecimal("1000000"), "TZS",
            new BigDecimal("50000.00"), "TZS", "MONTHLY", null, "test-staff");

        assertEquals("PROPOSED", proposed.getStatus());
        assertThrows(InvalidPolicyStateException.class, proposed::mature);
        assertThrows(InvalidPolicyStateException.class, proposed::terminateForSettledClaim);
        assertEquals("PROPOSED", proposed.getStatus());
    }
}
