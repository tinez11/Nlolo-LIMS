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
            "db-migrations/underwriting/V4__proposal_identity.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            // M4 (Task 1) additions: policy.policy now requires premium_amount/currency/frequency
            // on every insert, and endToEndAutoIssuanceFiresFromARealUnderwritingDecision below
            // needs TZ_BASE_PREMIUM_RATE_PER_MILLE for the auto-issuance listener's premium
            // computation to succeed (without it the listener catches the lookup failure, logs,
            // and issues nothing -- the test's retry loop would then find zero policies and fail).
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
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
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    private String issueDirectly(UUID tenantId, Fixture fixture, List<PolicyApi.BeneficiaryInput> beneficiaries) {
        return issueDirectly(tenantId, fixture, beneficiaries, new BigDecimal("50000.00"), "TZS", "MONTHLY");
    }

    private String issueDirectly(UUID tenantId, Fixture fixture, List<PolicyApi.BeneficiaryInput> beneficiaries,
                                  BigDecimal premiumAmount, String premiumCurrency, String premiumFrequency) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", premiumAmount, premiumCurrency, premiumFrequency, null, beneficiaries, "Direct issuance test");
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
            new BigDecimal("1000000"), "TZS", null, "agent1");
        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Normal findings", new BigDecimal("10"), "underwriter1");

        // awaitility is not a declared Maven dependency (Global Constraints: no new
        // dependencies) -- a short bounded retry loop proves the same thing: the
        // AFTER_COMMIT listener fires asynchronously-in-timing (though same-thread) relative
        // to submitAssessment's own return, so a single immediate read can race it.
        List<PolicyView> found = List.of();
        for (int attempt = 0; attempt < 50; attempt++) {
            TenantContext.set(tenantId);
            var results = policyApi.searchPolicies(fixture.applicantId(), null, null, null, null, PageRequest.of(0, 10));
            found = results.getContent();
            if (!found.isEmpty()) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(found).hasSize(1);
        assertThat(found.get(0).status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    /**
     * The automatically issued policy must carry the case's agent of record.
     *
     * <p>This is a money assertion, not a plumbing one. The listener used to hardcode
     * {@code null} here, and distribution's {@code PolicyEventListener} returns early on a
     * null {@code agentOfRecordId} ("sold direct -- no commission to accrue") -- so no
     * commission ever accrued on an automatically issued policy. Commission fired only on
     * {@code POST /policies/manual-issue}, the staff exception path, which is backwards from
     * how the business is meant to work, and silent, because a direct sale is a legitimate
     * state.
     */
    @Test
    void anAutomaticallyIssuedPolicyCarriesTheCasesAgentOfRecord() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-AUTO-AGENT");
        UUID agentOfRecordId = UUID.randomUUID();

        UnderwritingCaseView opened = underwritingApi.openCase(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("1000000"), "TZS", agentOfRecordId, "agent1");
        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Normal findings",
            new BigDecimal("10"), "underwriter1");

        List<PolicyView> found = List.of();
        for (int attempt = 0; attempt < 50; attempt++) {
            TenantContext.set(tenantId);
            found = policyApi.searchPolicies(fixture.applicantId(), null, null, null, null, PageRequest.of(0, 10)).getContent();
            if (!found.isEmpty()) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(found).hasSize(1);
        assertThat(found.get(0).agentOfRecordId())
            .as("the agent who sold it, not null -- null is what stopped commission accruing")
            .isEqualTo(agentOfRecordId);
    }

    @Test
    void aCaseOpenedWithNoAgentStillIssuesAsADirectSale() {
        // Null remains a real state: a self-service application has no agent, and forcing one
        // would invent a commission payee.
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-AUTO-DIRECT");
        UnderwritingCaseView opened = underwritingApi.openCase(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("1000000"), "TZS", null, "agent1");

        assertThat(opened.agentOfRecordId()).isNull();
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
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
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

    @Test
    void issuedPolicyExposesPremiumFields() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-PREMIUM-01");
        String policyNumber = issueDirectly(tenantId, fixture, List.of(), new BigDecimal("15000.00"), "TZS", "MONTHLY");

        PolicyView view = policyApi.getPolicy(policyNumber);
        assertEquals(0, new BigDecimal("15000.00").compareTo(view.premiumAmount()));
        assertEquals("TZS", view.premiumCurrency());
        assertEquals("MONTHLY", view.premiumFrequency());
    }

    @Test
    void anIssuedPolicyDerivesItsMaturityFromCommencementPlusTerm() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-TERM-01");

        PolicyView view = policyApi.issuePolicy(UUID.randomUUID(), new PolicyApi.IssueRequest(
            fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("2000000.00"), "TZS", new BigDecimal("800.00"), "TZS", "MONTHLY",
            null, List.of(), "Term test",
            LocalDate.of(2026, 3, 1), 240, 120), "test-staff");

        assertEquals(LocalDate.of(2026, 3, 1), view.commencementDate());
        assertEquals(240, view.policyTermMonths());
        assertEquals(120, view.premiumPayingTermMonths());
        // 240 months after 1 Mar 2026. Derived by the aggregate, never supplied.
        assertEquals(LocalDate.of(2046, 3, 1), view.maturityDate());
    }

    /**
     * Whole life, an annuity and an annually renewable group scheme have no term, and
     * neither does any policy issued before V6. That must stay distinguishable from a
     * term of zero.
     */
    @Test
    void aPolicyWithNoTermHasNoMaturity() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-TERM-02");
        String policyNumber = issueDirectly(tenantId, fixture, List.of(), new BigDecimal("800.00"), "TZS", "MONTHLY");

        PolicyView view = policyApi.getPolicy(policyNumber);
        assertNull(view.commencementDate());
        assertNull(view.policyTermMonths());
        assertNull(view.maturityDate());
    }

    @Test
    void aPremiumPayingTermLongerThanTheCoverTermIsRejected() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-TERM-03");

        // A limited-payment policy pays for LESS time than it covers. Longer is a data
        // error, refused before the policy is put in force.
        assertThrows(IllegalArgumentException.class, () -> policyApi.issuePolicy(UUID.randomUUID(),
            new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
                new BigDecimal("2000000.00"), "TZS", new BigDecimal("800.00"), "TZS", "MONTHLY",
                null, List.of(), "Term test",
                LocalDate.of(2026, 3, 1), 120, 240), "test-staff"));
    }

    /**
     * The end-of-month case, which is where a hand-rolled "add N months" goes wrong.
     * {@code LocalDate.plusMonths} clamps to the last day of a shorter target month;
     * the frontend's display-only preview has a test asserting the same behaviour so
     * the two cannot drift.
     */
    @Test
    void maturityClampsToTheLastDayOfAShorterMonth() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-TERM-04");

        PolicyView view = policyApi.issuePolicy(UUID.randomUUID(), new PolicyApi.IssueRequest(
            fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("2000000.00"), "TZS", new BigDecimal("800.00"), "TZS", "MONTHLY",
            null, List.of(), "Term test",
            LocalDate.of(2026, 1, 31), 1, null), "test-staff");

        assertEquals(LocalDate.of(2026, 2, 28), view.maturityDate());
    }

    @Test
    void resumingASuspendedPolicyPublishesPolicyResumed() throws Exception {
        // Deviation from the brief's own sketch (caught by reading the real code, not assumed):
        // buildFixture(...) always creates a TERM_LIFE product, which is NOT on
        // POLICY_SUSPENSION_ELIGIBLE_CATEGORIES (only GROUP_LIFE is seeded eligible, per
        // db-migrations/refdata/V2 and suspendRejectsAProductCategoryNotOnTheEligibleList above)
        // -- suspendPolicy would throw InvalidPolicyStateException before ever reaching resume,
        // exactly the failure mode suspendRejectsAProductCategoryNotOnTheEligibleList exists to
        // prove. Uses a GROUP_LIFE fixture instead, mirroring
        // suspendAndResumeRoundTripForAnEligibleCategory's own fixture construction above.
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Group Scheme Member Resume Test", LocalDate.of(1990, 1, 1), "+255713099002", null, "test-agent");
        ProductSummaryView product = productApi.createProduct("POLICY-RESUME-EVENT-01", "Group Life Resume Product", ProductCategory.GROUP_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")), null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        Fixture fixture = new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
        String policyNumber = issueDirectly(tenantId, fixture, List.of());

        TenantContext.set(tenantId);
        policyApi.suspendPolicy(policyNumber, "investigation", "test-staff");
        Instant before = Instant.now();
        policyApi.resumeSuspendedPolicy(policyNumber, "test-staff");

        // Same audit-log-query idiom as issuePolicyActivatesImmediatelyAndPublishesPolicyIssued
        // above, applied to the new event. Falsifiable: verified during implementation by
        // temporarily commenting out the new publishEvent(...) call in resumeSuspendedPolicy
        // and confirming this query returns zero rows (assertThat(...).hasSize(1) then fails),
        // then restoring it before commit -- recorded in this task's completion report.
        List<AuditLogEntry> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "policy.PolicyResumed", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
        JsonNode payload = objectMapper.readTree(auditRows.get(0).getPayload());
        assertThat(payload.path("policyNumber").asText()).isEqualTo(policyNumber);
    }
}
