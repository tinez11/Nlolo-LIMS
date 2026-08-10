package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.domain.AuditLogEntry;
import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.policy.domain.Endorsement;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyAccount;
import tz.co.nlolo.lifeplatform.policy.infrastructure.EndorsementRepository;
import tz.co.nlolo.lifeplatform.policy.infrastructure.PolicyAccountRepository;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.ProductVersion;
import tz.co.nlolo.lifeplatform.product.infrastructure.ProductVersionRepository;
import tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes = Application.class)
class PolicyApiIntegrationTest {

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
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private UnderwritingApi underwritingApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EndorsementRepository endorsementRepository;
    @Autowired private PolicyAccountRepository policyAccountRepository;
    @Autowired private ProductVersionRepository productVersionRepository;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private record Fixture(UUID applicantId, UUID productId, UUID productVersionId) {}

    private Fixture buildFixture(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Policy Test Applicant " + productCode, LocalDate.of(1990, 1, 1),
            // +255 + exactly 9 digits (TZ_PHONE_PATTERN) -- zero-padded so a small hash%10000
            // result (e.g. "3") can't produce a too-short, pattern-rejected phone number.
            "+25571300" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Policy Test Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    private String issueDirectly(UUID tenantId, Fixture fixture, List<PolicyApi.BeneficiaryInput> beneficiaries) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", null, "MONTHLY", beneficiaries, "Direct issuance test");
        return policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
    }

    @Test
    void issuePolicyActivatesImmediatelyAndPublishesPolicyIssued() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-ISSUE-01");
        Instant before = Instant.now();
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        PolicyView view = policyApi.getPolicy(policyNumber);
        assertEquals(PolicyStatus.ACTIVE, view.status());
        assertEquals(fixture.applicantId(), view.policyholderPartyId());
        assertTrue(policyApi.isPolicyInForce(policyNumber, LocalDate.now()));

        // Falsifiable proof that policy.PolicyIssued was genuinely published (not just that the
        // policy row exists): DomainEventAuditListener persists every published
        // DomainEventEnvelope to audit.audit_log AFTER_COMMIT -- same precedent as
        // AppRolePrivilegesIntegrationTest's and PartyApiIntegrationTest's
        // party.PartyRegistered audit-log assertions. If eventPublisher.publishEvent(...) were
        // removed from PolicyApiImpl.issuePolicy, this would find zero rows and fail (verified
        // by temporarily commenting the call out -- see task-2-report.md addendum).
        List<AuditLogEntry> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "policy.PolicyIssued", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
        JsonNode payload = objectMapper.readTree(auditRows.get(0).getPayload());
        assertThat(payload.path("policyNumber").asText()).isEqualTo(policyNumber);
        assertThat(payload.path("policyholderPartyId").asText()).isEqualTo(fixture.applicantId().toString());
        assertThat(payload.path("productId").asText()).isEqualTo(fixture.productId().toString());
        assertThat(payload.path("productVersionId").asText()).isEqualTo(fixture.productVersionId().toString());
        assertThat(payload.path("sumAssured").path("amount").asText()).isEqualTo("1000000");
        assertThat(payload.path("sumAssured").path("currencyCode").asText()).isEqualTo("TZS");
    }

    @Test
    void endToEndAutoIssuanceFiresFromARealUnderwritingDecision() throws InterruptedException {
        // Proves the real producer -> consumer path: underwritingApi.submitAssessment publishes
        // underwriting.UnderwritingDecisionMade for real, and
        // policy.application.UnderwritingDecisionEventListener consumes it and issues a policy
        // -- not a fabricated event injected directly into the publisher.
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-AUTO-01");
        UnderwritingCaseView opened = underwritingApi.openCase(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", "agent1");
        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Normal findings", new BigDecimal("10"), "underwriter1");

        // awaitility is not a declared Maven dependency (Global Constraints: no new
        // dependencies) -- a short bounded retry loop proves the same thing: the
        // AFTER_COMMIT listener fires asynchronously-in-timing (though same-thread) relative
        // to submitAssessment's own return, so a single immediate read can race it.
        List<PolicyView> found = List.of();
        for (int attempt = 0; attempt < 50; attempt++) {
            TenantContext.set(tenantId);
            var results = policyApi.searchPolicies(fixture.applicantId(), null, PageRequest.of(0, 10));
            found = results.getContent();
            if (!found.isEmpty()) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(found).hasSize(1);
        assertThat(found.get(0).status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void replaceBeneficiariesRejectsBothPartyIdAndFreeformDesignee() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-BENE-01");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        assertThrows(BeneficiaryValidationException.class, () -> policyApi.replaceBeneficiaries(policyNumber,
            List.of(new PolicyApi.BeneficiaryInput(BeneficiaryType.PARTY, fixture.applicantId(), "estate", new BigDecimal("100"), true)),
            "test-agent"));
    }

    @Test
    void replaceBeneficiariesRejectsSharesNotSummingTo100() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-BENE-02");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        assertThrows(BeneficiaryValidationException.class, () -> policyApi.replaceBeneficiaries(policyNumber,
            List.of(new PolicyApi.BeneficiaryInput(BeneficiaryType.FREEFORM, null, "estate", new BigDecimal("60"), true)),
            "test-agent"));
    }

    @Test
    void replaceBeneficiariesAcceptsAValidExactlyOneOfSetSummingTo100() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-BENE-03");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        policyApi.replaceBeneficiaries(policyNumber,
            List.of(new PolicyApi.BeneficiaryInput(BeneficiaryType.PARTY, fixture.applicantId(), null, new BigDecimal("60"), true),
                    new PolicyApi.BeneficiaryInput(BeneficiaryType.FREEFORM, null, "estate", new BigDecimal("40"), true)),
            "test-agent");
        PolicyView view = policyApi.getPolicy(policyNumber);
        assertEquals(2, view.beneficiaries().size());
    }

    @Test
    void suspendRejectsAProductCategoryNotOnTheEligibleList() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-SUSP-01"); // TERM_LIFE -- not on POLICY_SUSPENSION_ELIGIBLE_CATEGORIES
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        assertThrows(InvalidPolicyStateException.class, () -> policyApi.suspendPolicy(policyNumber, "admin hold", "test-staff"));
    }

    @Test
    void suspendAndResumeRoundTripForAnEligibleCategory() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Group Scheme Member", LocalDate.of(1990, 1, 1), "+255713099001", null, "test-agent");
        ProductSummaryView product = productApi.createProduct("POLICY-SUSP-02", "Group Life Product", ProductCategory.GROUP_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")), null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        Fixture fixture = new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
        String policyNumber = issueDirectly(tenantId, fixture, List.of());

        TenantContext.set(tenantId);
        policyApi.suspendPolicy(policyNumber, "SACCO group non-payment", "test-staff");
        assertEquals(PolicyStatus.SUSPENDED, policyApi.getPolicy(policyNumber).status());
        assertFalse(policyApi.isPolicyInForce(policyNumber, LocalDate.now()));

        policyApi.resumeSuspendedPolicy(policyNumber, "test-staff");
        assertEquals(PolicyStatus.ACTIVE, policyApi.getPolicy(policyNumber).status());
    }

    @Test
    void lapseAndReinstateWithinTheWindowSucceeds() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-LAPSE-01");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());

        TenantContext.set(tenantId);
        policyApi.lapsePolicy(policyNumber, "test-staff");
        assertEquals(PolicyStatus.LAPSED, policyApi.getPolicy(policyNumber).status());

        // TZ_REINSTATEMENT_WINDOW_MONTHS is seeded 12 (PLACEHOLDER) -- lapsedAt is "now," so
        // reinstatement must succeed immediately.
        policyApi.reinstatePolicy(policyNumber, "test-staff");
        assertEquals(PolicyStatus.REINSTATED, policyApi.getPolicy(policyNumber).status());
        assertTrue(policyApi.isPolicyInForce(policyNumber, LocalDate.now()));
    }

    @Test
    void endorsementIsRejectedWhenPolicyIsNotInForce() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-ENDORSE-01");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        TenantContext.set(tenantId);
        policyApi.lapsePolicy(policyNumber, "test-staff");

        assertThrows(InvalidPolicyStateException.class, () -> policyApi.applyEndorsement(policyNumber,
            new PolicyApi.EndorsementInput("SUM_ASSURED_CHANGE", LocalDate.now(), java.util.Map.of("newSumAssured", "2000000")), "test-agent"));
    }

    @Test
    void applyEndorsementOnAnInForcePolicySucceedsPersistsChangesAndPublishesPolicyEndorsed() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-ENDORSE-02");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        TenantContext.set(tenantId);
        Instant before = Instant.now();

        PolicyView view = policyApi.applyEndorsement(policyNumber,
            new PolicyApi.EndorsementInput("SUM_ASSURED_CHANGE", LocalDate.now(), Map.of("newSumAssured", "2000000")),
            "test-agent");
        // Endorsing an in-force policy doesn't itself transition lifecycle status.
        assertEquals(PolicyStatus.ACTIVE, view.status());

        List<Endorsement> endorsements = endorsementRepository.findByPolicyNumberOrderByEffectiveDateDesc(policyNumber);
        assertThat(endorsements).hasSize(1);
        Endorsement persisted = endorsements.get(0);
        assertEquals("SUM_ASSURED_CHANGE", persisted.getEndorsementType());
        assertEquals(LocalDate.now(), persisted.getEffectiveDate());
        assertEquals("2000000", persisted.getChanges().get("newSumAssured")); // real changes JSONB round-trip, not a stub

        List<AuditLogEntry> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "policy.PolicyEndorsed", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
        JsonNode payload = objectMapper.readTree(auditRows.get(0).getPayload());
        assertThat(payload.path("policyNumber").asText()).isEqualTo(policyNumber);
        assertThat(payload.path("endorsementType").asText()).isEqualTo("SUM_ASSURED_CHANGE");
    }

    @Test
    void quoteSurrenderValueDoesNotCrashWhenNoScheduleIsConfigured() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-SURR-01");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        TenantContext.set(tenantId);
        SurrenderQuoteView quote = policyApi.quoteSurrenderValue(policyNumber);
        assertEquals(0, BigDecimal.ZERO.compareTo(quote.quotedValueAmount())); // zero cash value, zero charge -- zero quote, no exception
    }

    @Test
    void quoteSurrenderValueAppliesTheChargePercentFromTheMatchingBandToTheQuotedValue() {
        // resolveSurrenderChargePercent's own band-matching/boundary logic is unit-tested in
        // isolation by PolicyApiImplSurrenderChargeTest; this proves the real, wired-together
        // pipeline (schedule parsing -> percent selection -> charge/quotedValue arithmetic)
        // actually applies that percent to a real SurrenderQuoteView.
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-SURR-02");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        TenantContext.set(tenantId);

        // ProductApi exposes no public method to configure surrender_charge_schedule (a flagged,
        // Actuarial-pending placeholder shape -- see PolicyApiImpl.resolveSurrenderChargePercent's
        // javadoc), so the persisted ProductVersion row created by buildFixture is updated
        // directly via the same repository ProductApiIntegrationTest already reads through.
        ProductVersion version = productVersionRepository
            .findByTenantIdAndProductIdOrderByEffectiveDateDesc(tenantId, fixture.productId()).get(0);
        version.setSurrenderChargeScheduleJson(
            "[{\"minMonths\":0,\"maxMonths\":12,\"chargePercent\":10},{\"minMonths\":12,\"chargePercent\":2}]");
        productVersionRepository.save(version);

        // Cash value starts at zero at issuance (no premium/billing accrual path exists yet in
        // M3) and there is likewise no public API to credit it. Without a non-zero cash value,
        // quotedValue would be zero regardless of chargePercent and this test would be exactly
        // the kind of vacuous test this task exists to eliminate -- so the persisted
        // PolicyAccount row's field is set directly via reflection instead.
        PolicyAccount account = policyAccountRepository.findById(policyNumber).orElseThrow();
        ReflectionTestUtils.setField(account, "cashValueAmount", new BigDecimal("100000"));
        policyAccountRepository.save(account);

        SurrenderQuoteView quote = policyApi.quoteSurrenderValue(policyNumber);
        // issueDate is "today" -> 0 months in force -> matches the first band (0 <= 0 < 12) -> 10% charge
        // charge = 100000 * 10 / 100 = 10000; quotedValue = 100000 - 10000 = 90000
        assertEquals(0, new BigDecimal("90000").compareTo(quote.quotedValueAmount()));
    }
}
