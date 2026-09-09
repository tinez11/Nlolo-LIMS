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
import tz.co.nlolo.lifeplatform.policy.domain.Policy;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyAccount;
import tz.co.nlolo.lifeplatform.policy.infrastructure.EndorsementRepository;
import tz.co.nlolo.lifeplatform.policy.infrastructure.PolicyAccountRepository;
import tz.co.nlolo.lifeplatform.policy.infrastructure.PolicyRepository;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.ProductVersion;
import tz.co.nlolo.lifeplatform.product.infrastructure.ProductVersionRepository;
import tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType;
import tz.co.nlolo.lifeplatform.underwriting.api.BeneficiaryNomination;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.NominationType;
import tz.co.nlolo.lifeplatform.underwriting.api.ProposalDetails;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
            "db-migrations/underwriting/V5__explicit_decision.sql",
            "db-migrations/underwriting/V6__proposal_terms_and_beneficiaries.sql",
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
            "db-migrations/policy/V10__one_policy_per_underwriting_case.sql",
            "db-migrations/policy/V11__not_taken_up_status.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            // The offer-validity window the expiry sweep reads.
            "db-migrations/refdata/V5__seed_offer_validity.sql",
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @Autowired private PolicyRepository policyRepository;
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
    void issuePolicyCreatesTheContractRecordAndPublishesPolicyIssued() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-ISSUE-01");
        Instant before = Instant.now();
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        PolicyView view = policyApi.getPolicy(policyNumber);
        // Renamed and corrected rather than activated. This test's subject IS issuance, and
        // issuance no longer starts cover -- PolicyIssued now means only that the contract
        // record exists. Adding a premium here to keep the old ACTIVE expectation would have
        // been testing a different thing under the old name.
        assertEquals(PolicyStatus.PROPOSED, view.status());
        assertEquals(fixture.applicantId(), view.policyholderPartyId());
        assertFalse(policyApi.isPolicyInForce(policyNumber, LocalDate.now()));

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
        // Proves the real producer -> consumer path: underwritingApi.decide publishes
        // underwriting.UnderwritingDecisionMade for real, and
        // policy.application.UnderwritingDecisionEventListener consumes it and issues a policy
        // -- not a fabricated event injected directly into the publisher.
        //
        // The producer is decide, NOT submitAssessment. It was submitAssessment, which is
        // precisely the defect: a placeholder rules engine settled the case and put a real
        // contract in force with no person involved anywhere in the chain.
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-AUTO-01");
        UnderwritingCaseView opened = underwritingApi.openCase(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", null, "agent1");
        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Normal findings", new BigDecimal("10"), "underwriter1");
        underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Standard risk"), "underwriter1", false);

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
        // PROPOSED, not ACTIVE. This test's subject is the decision -> issuance chain, and what
        // that chain now produces is an offer: the underwriter has accepted the risk, the
        // customer has not yet paid for it. Corrected rather than paid off, for the same reason
        // issuePolicyCreatesTheContractRecordAndPublishesPolicyIssued was -- collecting a premium
        // here would keep the old assertion alive while quietly testing something else.
        assertThat(found.get(0).status()).isEqualTo(PolicyStatus.PROPOSED);

        // And the offer becomes cover when the money arrives, which is the half that would
        // otherwise go unproven end to end.
        TenantContext.set(tenantId);
        policyApi.activateOnFirstPremium(found.get(0).policyNumber());
        assertThat(policyApi.getPolicy(found.get(0).policyNumber()).status()).isEqualTo(PolicyStatus.ACTIVE);
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
        underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Standard risk"), "underwriter1", false);

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

    /** Opens a case carrying proposal terms, assesses it, accepts it, and waits for issuance. */
    private PolicyView issueFromProposal(UUID tenantId, Fixture fixture, ProposalDetails proposal)
            throws InterruptedException {
        UnderwritingCaseView opened = underwritingApi.openCase(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("1000000"), "TZS", null, proposal, "agent1");
        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Standard", new BigDecimal("10"), "uw");
        underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Standard risk"), "uw", false);

        List<PolicyView> found = List.of();
        for (int attempt = 0; attempt < 50; attempt++) {
            TenantContext.set(tenantId);
            found = policyApi.searchPolicies(fixture.applicantId(), null, null, null, null, PageRequest.of(0, 10)).getContent();
            if (!found.isEmpty()) break;
            Thread.sleep(100);
        }
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    /**
     * The proposal's term reaches the policy, and with it a maturity date.
     *
     * <p>Before the case captured a term, an automatically issued policy had none -- and
     * therefore no maturity date either, since {@code Policy.applyTerm} derives maturity from
     * commencement plus term. Only the staff manual-issue path produced a complete contract,
     * which is very likely why staff reached for it.
     */
    @Test
    void anAutomaticallyIssuedPolicyCarriesTheProposalsTerm() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-PROP-TERM");
        LocalDate commencement = LocalDate.now().minusDays(1);

        PolicyView issued = issueFromProposal(tenantId, fixture, new ProposalDetails(
            null, null, null, commencement, 120, 60, "MONTHLY", List.of()));

        assertThat(issued.commencementDate()).isEqualTo(commencement);
        assertThat(issued.policyTermMonths()).isEqualTo(120);
        assertThat(issued.premiumPayingTermMonths()).isEqualTo(60);
        assertThat(issued.maturityDate())
            .as("maturity is derived from commencement plus term, so a term is what makes it exist")
            .isEqualTo(commencement.plusMonths(120));
    }

    /**
     * The regression this whole change could most easily have introduced.
     *
     * <p>The listener divided the annual premium by 12 unconditionally and hardcoded MONTHLY,
     * which was harmless only while nothing captured a frequency. Capturing one without
     * changing the arithmetic would bill a quarterly payer a monthly figure.
     */
    @Test
    void aQuarterlyProposalIsIssuedWithAQuarterlyInstalment() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();
        Fixture monthlyFixture = buildFixture(tenantId, "POLICY-PROP-FREQ-M");
        PolicyView monthly = issueFromProposal(tenantId, monthlyFixture,
            new ProposalDetails(null, null, null, null, null, null, "MONTHLY", List.of()));

        UUID quarterlyTenant = UUID.randomUUID();
        Fixture quarterlyFixture = buildFixture(quarterlyTenant, "POLICY-PROP-FREQ-Q");
        PolicyView quarterly = issueFromProposal(quarterlyTenant, quarterlyFixture,
            new ProposalDetails(null, null, null, null, null, null, "QUARTERLY", List.of()));

        assertThat(quarterly.premiumFrequency()).isEqualTo("QUARTERLY");
        assertThat(quarterly.premiumAmount())
            .as("a quarterly payer must not be billed the monthly figure")
            .isNotEqualByComparingTo(monthly.premiumAmount());

        // Roughly three monthly instalments, and only roughly -- deliberately not asserted as
        // exactly 3x. Both are the same annual premium rounded to the cent at different
        // divisors, so 3 x round(annual/12) is 1250.01 while round(annual/4) is 1250.00. The
        // penny is real: instalments do not sum to the annual premium, which is ordinary
        // premium arithmetic rather than a defect, and pinning the test to exact equality
        // would have made the correct implementation look wrong.
        assertThat(quarterly.premiumAmount().subtract(monthly.premiumAmount().multiply(new BigDecimal("3"))))
            .as("a quarterly instalment is three monthly ones, give or take the rounding")
            .isBetween(new BigDecimal("-0.05"), new BigDecimal("0.05"));
    }

    /** A nomination taken on the proposal becomes a beneficiary on the issued policy. */
    @Test
    void anAutomaticallyIssuedPolicyCarriesTheProposalsBeneficiaries() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-PROP-BENE");

        PolicyView issued = issueFromProposal(tenantId, fixture, new ProposalDetails(
            null, null, null, null, null, null, null,
            List.of(new BeneficiaryNomination(NominationType.FREEFORM, null, "The estate", new BigDecimal("100"), true))));

        TenantContext.set(tenantId);
        List<BeneficiaryView> beneficiaries = policyApi.getPolicy(issued.policyNumber()).beneficiaries();
        assertThat(beneficiaries).hasSize(1);
        assertThat(beneficiaries.get(0).freeformDesignee()).isEqualTo("The estate");
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
        // Suspension puts in-force cover on hold; there is nothing to hold on an unpaid offer.
        policyApi.activateOnFirstPremium(policyNumber);
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
        // Only an in-force policy can lapse. An unpaid offer expires NOT_TAKEN_UP instead --
        // see anOfferThatExpiresIsNotTakenUpRatherThanLapsed.
        policyApi.activateOnFirstPremium(policyNumber);
        policyApi.lapsePolicy(policyNumber, "test-staff");
        assertEquals(PolicyStatus.LAPSED, policyApi.getPolicy(policyNumber).status());

        // TZ_REINSTATEMENT_WINDOW_MONTHS is seeded 12 (PLACEHOLDER) -- lapsedAt is "now," so
        // reinstatement must succeed immediately.
        policyApi.reinstatePolicy(policyNumber, "test-staff");
        assertEquals(PolicyStatus.REINSTATED, policyApi.getPolicy(policyNumber).status());
        assertTrue(policyApi.isPolicyInForce(policyNumber, LocalDate.now()));
    }

    @Test
    void anIssuedPolicyIsAnOfferUntilItsFirstPremium() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-OFFER-01");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());

        PolicyView view = policyApi.getPolicy(policyNumber);
        assertThat(view.status()).isEqualTo(PolicyStatus.PROPOSED);
        assertThat(policyApi.isPolicyInForce(policyNumber, LocalDate.now()))
            .as("an offer nobody has paid for is not cover")
            .isFalse();
    }

    @Test
    void aMigrationIsInForceImmediatelyBecauseItAlreadyWas() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-OFFER-MIG");
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(
            fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY",
            null, List.of(), "Brought in from the legacy book",
            null, null, null, null, IssuanceBasis.MIGRATION);

        PolicyView issued = policyApi.issuePolicy(UUID.randomUUID(), request, "test-agent");
        assertThat(issued.status()).isEqualTo(PolicyStatus.ACTIVE);
        assertTrue(policyApi.isPolicyInForce(issued.policyNumber(), LocalDate.now()));
    }

    @Test
    void anUnderwritingOverrideStillWaitsForTheMoney() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-OFFER-OVR");
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(
            fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY",
            null, List.of(), "Senior underwriter overturned the automated decline",
            null, null, null, null, IssuanceBasis.UNDERWRITING_OVERRIDE);

        PolicyView issued = policyApi.issuePolicy(UUID.randomUUID(), request, "test-agent");
        assertThat(issued.status())
            .as("overturning a block changes who may be covered, not whether they pay")
            .isEqualTo(PolicyStatus.PROPOSED);
    }

    @Test
    void theFirstPremiumStartsTheCover() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-ACT-01");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.PROPOSED);

        policyApi.activateOnFirstPremium(policyNumber);

        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.ACTIVE);
        assertTrue(policyApi.isPolicyInForce(policyNumber, LocalDate.now()));
    }

    /**
     * Idempotent on purpose. A second PremiumCollected is the ordinary second month, and
     * re-firing PolicyActivated would double-accrue the agent's commission and double-cede the
     * risk.
     */
    @Test
    void aSecondPremiumDoesNotReactivateOrRepublish() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-ACT-02");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        Instant before = Instant.now();
        policyApi.activateOnFirstPremium(policyNumber);

        // Scoped to this tenant and this window rather than a findAll() count, so a concurrent
        // test class activating its own policy cannot make this pass or fail spuriously.
        assertThat(activationsFor(tenantId, before)).hasSize(1);

        policyApi.activateOnFirstPremium(policyNumber);

        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.ACTIVE);
        assertThat(activationsFor(tenantId, before))
            .as("the second premium is the ordinary second month, not a second activation")
            .hasSize(1);
    }

    private List<AuditLogEntry> activationsFor(UUID tenantId, Instant since) {
        return auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "policy.PolicyActivated", since.minusSeconds(5), Instant.now().plusSeconds(5));
    }

    /**
     * An offer is not cover, and the clearest proof is that nobody can claim on it.
     *
     * <p>{@code Policy.terminateForSettledClaim} and {@code markMatured} have always refused any
     * status but ACTIVE, REINSTATED, LAPSED or SUSPENDED -- "PROPOSED above all", in their own
     * words -- but no policy could reach PROPOSED before this change, so the guard had never once
     * been exercised against a real one. This asserts the actual settlement entry points rather
     * than the isInForce read they sit behind: settling a claim against a contract nobody has
     * paid for is the single most consequential thing offer-and-acceptance could get wrong.
     */
    @Test
    void aClaimCannotBeSettledAgainstAnOfferNobodyHasPaidFor() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-OFFER-CLAIM");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.PROPOSED);

        assertThrows(InvalidPolicyStateException.class,
            () -> policyApi.terminateForSettledClaim(policyNumber, UUID.randomUUID(), "test-claims"));
        assertThrows(InvalidPolicyStateException.class,
            () -> policyApi.markMatured(policyNumber, "test-claims"));

        assertFalse(policyApi.isPolicyInForce(policyNumber, LocalDate.now()));
        assertThat(policyApi.getPolicy(policyNumber).status())
            .as("a refused settlement must leave the offer exactly as it was")
            .isEqualTo(PolicyStatus.PROPOSED);
    }

    /**
     * The sweep closes an offer past the window and leaves a younger one alone.
     *
     * <p>The function lives in {@code _post-migration}, which {@code scripts/migrate.sh}
     * deliberately does not apply, so it is loaded here from the same file operations runs. A copy
     * inlined in this test would keep passing while the real file rotted -- which is exactly how
     * the billing sweep shipped a broken {@code CALL} for three milestones.
     */
    @Test
    void theSweepClosesAnOfferPastTheWindowAndLeavesAYoungerOneAlone() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String stale = issueDirectly(tenantId, buildFixture(tenantId, "POLICY-SWEEP-OLD"), List.of());
        String fresh = issueDirectly(tenantId, buildFixture(tenantId, "POLICY-SWEEP-NEW"), List.of());

        String sweepSql = Files.readString(
            Path.of("db-migrations/_post-migration/configure-offer-expiry-sweep.sql"));
        // Everything above the cron.schedule line is the function itself, which is what is under
        // test; scheduling needs the pg_cron extension, which this container does not load.
        String functionOnly = sweepSql.substring(0, sweepSql.indexOf("-- Daily at 02:00"));

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(functionOnly);
            // 31 days against a seeded 30-day window. Ageing the row is the only way to test a
            // deadline without waiting for it.
            statement.execute("UPDATE policy.policy SET created_at = now() - INTERVAL '31 days' "
                + "WHERE policy_number = '" + stale + "'");
            statement.execute("SELECT policy.sweep_expired_offers()");
        }

        TenantContext.set(tenantId);
        assertThat(policyApi.getPolicy(stale).status()).isEqualTo(PolicyStatus.NOT_TAKEN_UP);
        assertThat(policyApi.getPolicy(fresh).status())
            .as("a fresh offer is still open; the window is a deadline, not a suggestion")
            .isEqualTo(PolicyStatus.PROPOSED);
    }

    /**
     * The sweep does not touch cover.
     *
     * <p>It sets the status with a raw UPDATE, so it never passes through
     * {@link tz.co.nlolo.lifeplatform.policy.domain.Policy#markNotTakenUp()} and does not inherit
     * that guard. Its {@code WHERE status = 'PROPOSED'} is the guard, and this is what proves it:
     * an old ACTIVE policy is exactly what a missing predicate would silently terminate.
     */
    @Test
    void theSweepLeavesAnOldInForcePolicyAlone() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-SWEEP-INFORCE");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        TenantContext.set(tenantId);
        policyApi.activateOnFirstPremium(policyNumber);

        String sweepSql = Files.readString(
            Path.of("db-migrations/_post-migration/configure-offer-expiry-sweep.sql"));
        String functionOnly = sweepSql.substring(0, sweepSql.indexOf("-- Daily at 02:00"));

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(functionOnly);
            statement.execute("UPDATE policy.policy SET created_at = now() - INTERVAL '400 days' "
                + "WHERE policy_number = '" + policyNumber + "'");
            statement.execute("SELECT policy.sweep_expired_offers()");
        }

        TenantContext.set(tenantId);
        assertThat(policyApi.getPolicy(policyNumber).status())
            .as("a policy on risk for over a year is not an expired offer")
            .isEqualTo(PolicyStatus.ACTIVE);
        assertTrue(policyApi.isPolicyInForce(policyNumber, LocalDate.now()));
    }

    @Test
    void anOfferThatExpiresIsNotTakenUpRatherThanLapsed() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-NTU-01");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());

        // Issuance now lands in PROPOSED on its own, so this is a real offer rather than a
        // reflectively-staged one.
        TenantContext.set(tenantId);
        Policy policy = policyRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId).orElseThrow();
        assertEquals("PROPOSED", ReflectionTestUtils.getField(policy, "status"));
        policy.markNotTakenUp();
        policyRepository.save(policy);

        // Reads back through the API, so this also proves policy_status_check accepts the new
        // value: without V11 the save above would fail on the constraint, not on the enum.
        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.NOT_TAKEN_UP);
        assertFalse(policyApi.isPolicyInForce(policyNumber, LocalDate.now()));
    }

    @Test
    void onlyAProposedPolicyCanBecomeNotTakenUp() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-NTU-02");
        TenantContext.set(tenantId);
        // MIGRATION is the shortest honest route to a genuinely ACTIVE policy now that the
        // ordinary path stops at PROPOSED. Setting the status by reflection would test the guard
        // against a state the aggregate never actually reaches.
        String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), new PolicyApi.IssueRequest(
            fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY",
            null, List.of(), "Brought in from the legacy book",
            null, null, null, null, IssuanceBasis.MIGRATION), "test-staff").policyNumber();

        Policy active = policyRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId).orElseThrow();
        // An ACTIVE policy is on risk. Expiring it as an unpaid offer would silently drop cover.
        assertThrows(InvalidPolicyStateException.class, active::markNotTakenUp);
        assertEquals(PolicyStatus.ACTIVE, policyApi.getPolicy(policyNumber).status());
    }

    @Test
    void endorsementIsRejectedWhenPolicyIsNotInForce() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-ENDORSE-01");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        TenantContext.set(tenantId);
        // The "not in force" this test is about is LAPSED, reached from cover -- so the policy
        // has to get into force first.
        policyApi.activateOnFirstPremium(policyNumber);
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
        // "On an in-force policy" is this test's own name; that now takes a premium.
        policyApi.activateOnFirstPremium(policyNumber);
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
        // Suspension puts in-force cover on hold; there is nothing to hold on an unpaid offer.
        policyApi.activateOnFirstPremium(policyNumber);
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
