package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.domain.AuditLogEntry;
import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import tz.co.nlolo.lifeplatform.party.api.IndividualRegistration;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.distribution.api.AgentView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
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
import java.time.Duration;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

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
            "db-migrations/product/V18__payout_schedule.sql",
            "db-migrations/product/V19__accumulation_terms.sql",
            "db-migrations/product/V20__deposit_rate_grid.sql",
            "db-migrations/product/V21__bonus_terms.sql",
            "db-migrations/product/V27__ifrs17_classification.sql",
            "db-migrations/product/V28__survival_investment_component.sql",
            "db-migrations/product/V29__funeral_group_rate.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
            "db-migrations/benefitpayout/V3__withholding.sql",
            "db-migrations/benefitpayout/V4__withholding_rule_end.sql",
            "db-migrations/accumulation/V1__create_accumulation_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/underwriting/V4__proposal_identity.sql",
            "db-migrations/underwriting/V5__explicit_decision.sql",
            "db-migrations/underwriting/V6__proposal_terms_and_beneficiaries.sql",
            "db-migrations/underwriting/V8__rating_multiplier.sql",
            "db-migrations/underwriting/V9__group_proposal.sql",
            "db-migrations/underwriting/V10__issuance_failure.sql",
            "db-migrations/underwriting/V11__member_evidence_case.sql",
            "db-migrations/underwriting/V18__sale_channel_and_branch.sql",
            "db-migrations/underwriting/V19__group_funeral_proposal.sql",
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
            "db-migrations/policy/V24__issuance_record.sql",
            "db-migrations/policy/V29__paid_up.sql",
            "db-migrations/policy/V30__surrender.sql",
            "db-migrations/policy/V31__free_look_status.sql",
            "db-migrations/policy/V37__sale_classification.sql",
            "db-migrations/policy/V38__group_funeral_scheme.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            // The offer-validity window the expiry sweep reads.
            "db-migrations/refdata/V5__seed_offer_validity.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
            // Issuance now validates agentOfRecordId against distribution, so this schema has to
            // exist here -- without it the check fails on a missing relation rather than on the
            // agent, which is a different (and much less useful) failure.
            "db-migrations/distribution/V1__create_distribution_schema.sql",
            "db-migrations/distribution/V2__grants_rls_money_checks_projection_and_statement_lifecycle.sql",
            "db-migrations/distribution/V3__rls_fail_closed.sql",
            "db-migrations/distribution/V5__agent_channel_and_home_branch.sql",
            "db-migrations/distribution/V6__commission_withholding.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");
    }

    @Autowired private PolicyRepository policyRepository;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private UnderwritingApi underwritingApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private DistributionApi distributionApi;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EndorsementRepository endorsementRepository;
    @Autowired private PolicyAccountRepository policyAccountRepository;
    @Autowired private ProductVersionRepository productVersionRepository;
    @Autowired private tz.co.nlolo.lifeplatform.product.infrastructure.RatingFactorRepository ratingFactorRepository;
    /** Only for reproducing a pre-V13 version, which publishVersion can no longer create. */
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
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
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Standard risk"), "decider1", false);

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
        // A REAL agent, not a random uuid. It used to be fabricated, which stopped working when
        // openCase began refusing an agent of record that is not an agent -- and a fabricated one
        // was never a fair test of "the agent who sold it" anyway, since no such agent could ever
        // have been paid.
        UUID agentOfRecordId = onboardTestAgent(tenantId, "AUTO-AGENT", "+255713099557").agentId();

        UnderwritingCaseView opened = underwritingApi.openCase(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("1000000"), "TZS", agentOfRecordId, "agent1");
        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Normal findings",
            new BigDecimal("10"), "underwriter1");
        underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Standard risk"), "decider1", false);

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
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Standard risk"), "uw-decider", false);

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

    /**
     * The rating table has to reach the premium, and for a long time it did not.
     *
     * <p>{@code product.rating_table} has held real per-band multipliers since M1, and
     * underwriting resolves the applicant's age band against it on every assessment. The
     * result went nowhere: {@code SimpleRulesEngine} used it to pick an outcome and then
     * dropped it, and the issuance formula was {@code sumAssured x baseRate x (1 + loading)}
     * with no multiplier term at all. On an ACCEPT — which by definition carries no loading —
     * two applicants thirty years apart, buying identical cover from a product that prices
     * them 1.6x apart, were charged exactly the same premium.
     *
     * <p>Both lives here are clean (risk score 10, well under the load threshold), so a
     * loading cannot be smuggling the difference in: the only thing separating them is the
     * age band, which is precisely what is under test. The figures are exact rather than a
     * "greater than" so a future change that halves the effect still fails: TZS 1,000,000 at
     * the 5.0 per mille base rate is 5,000 a year, 416.67 a month at 1.0x and 666.67 at 1.6x.
     */
    @Test
    void theRatingTableMultiplierReachesThePremium() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        ProductSummaryView product = productApi.createProduct("POLICY-RATED-01", "Age rated product",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.AGE, "50-59", new BigDecimal("1.6"), 50, 59),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        ProposalDetails monthly = new ProposalDetails(null, null, null, null, null, null, "MONTHLY", List.of());
        PolicyView young = issueFromProposal(tenantId,
            new Fixture(applicantAged(tenantId, 36, "0001"), product.productId(), versionId), monthly);
        PolicyView older = issueFromProposal(tenantId,
            new Fixture(applicantAged(tenantId, 56, "0002"), product.productId(), versionId), monthly);

        assertThat(young.premiumAmount())
            .as("the 30-39 band is 1.0x, so this is the unrated price")
            .isEqualByComparingTo(new BigDecimal("416.67"));
        assertThat(older.premiumAmount())
            .as("the 50-59 band is 1.6x, and that multiplier must survive the trip to the premium")
            .isEqualByComparingTo(new BigDecimal("666.67"));
    }

    /**
     * A 1.6x rating is a price, not a verdict on the applicant.
     *
     * <p>Pinned separately because the engine used to express the rating AS a loading, and the
     * cheapest way to reintroduce the old behaviour is to multiply the premium by the rating
     * while still deriving a loading from it — which would square the effect and charge this
     * life 1,066.67. A clean life is ACCEPTED whatever their age band says.
     */
    @Test
    void anOlderCleanLifeIsRatedNotLoaded() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        ProductSummaryView product = productApi.createProduct("POLICY-RATED-02", "Age rated product",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "50-59", new BigDecimal("1.6"), 50, 59),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        UUID applicantId = applicantAged(tenantId, 56, "0003");
        UnderwritingCaseView opened = underwritingApi.openCase(applicantId, product.productId(), versionId,
            new BigDecimal("1000000"), "TZS", null,
            new ProposalDetails(null, null, null, null, null, null, "MONTHLY", List.of()), "agent1");
        UnderwritingCaseView assessed = underwritingApi.submitAssessment(opened.caseId(),
            AssessmentType.MEDICAL, "Standard", new BigDecimal("10"), "uw");

        assertThat(assessed.recommendationOutcome())
            .as("nothing the evidence found justifies a loading; the age is a price, not a finding")
            .isEqualTo(DecisionOutcome.ACCEPT);
        assertThat(assessed.recommendationLoadingPercent()).isNull();
        assertThat(assessed.ratingMultiplier())
            .as("and the rating is recorded on the case, which is how it reaches issuance at all")
            .isEqualByComparingTo(new BigDecimal("1.6"));
    }

    /**
     * AN ACCEPTANCE THAT ISSUES NOTHING MUST SAY SO ON THE CASE.
     *
     * <p>This is a production defect, reproduced. A product was published with its AGE band
     * carrying a multiplier of 0.0000; an applicant was accepted against it; the premium computed
     * to 0.00; {@code chk_premium_amount_positive} refused the insert. Because issuance runs in an
     * AFTER_COMMIT listener the decision had already committed, so the case stood as ACCEPT, no
     * policy existed, and the only record was a stack trace in a log file. It was found days later
     * because somebody happened to ask why a customer had no policy.
     *
     * <p>Three things are asserted, and the third is the one that was missing:
     * <ol>
     *   <li>no policy is created — a nil premium must not become a contract;</li>
     *   <li>the decision still stands, because it was real and AFTER_COMMIT cannot undo it;</li>
     *   <li><b>the case says what went wrong</b>, in words naming the terms, so the person who
     *       accepted it can tell a product misconfiguration from an outage.</li>
     * </ol>
     *
     * <p>The zero multiplier is written straight to the repository because
     * {@code publishVersion} now refuses one — which is the other half of this fix. That refusal
     * makes this state unreachable through the API and does not make it unreachable: seed
     * scripts, migrations and older rows all write to that table, and the whole lesson here is
     * that the failure has to be visible when it happens anyway.
     */
    @Test
    void anAcceptanceThatCannotBeIssuedSaysSoOnTheCase() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-NIL-PREMIUM");

        TenantContext.set(tenantId);
        // Replace the version's rating table with the one that caused this: an AGE band covering
        // every adult, at nil. Replaced rather than added to, because two AGE rows covering one
        // applicant is a different defect with its own test.
        ratingFactorRepository.deleteAll(
            ratingFactorRepository.findByProductVersionId(fixture.productVersionId()));
        ratingFactorRepository.saveAndFlush(new tz.co.nlolo.lifeplatform.product.domain.RatingFactor(
            tenantId, fixture.productVersionId(), FactorType.AGE.name(), "18-78", BigDecimal.ZERO, 18, 78));
        ratingFactorRepository.saveAndFlush(new tz.co.nlolo.lifeplatform.product.domain.RatingFactor(
            tenantId, fixture.productVersionId(), FactorType.SUM_ASSURED_BAND.name(), "LOW", BigDecimal.ONE));

        UnderwritingCaseView opened = underwritingApi.openCase(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("1000000"), "TZS", null,
            new ProposalDetails(null, null, null, null, null, null, "MONTHLY", List.of()), "agent1");
        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Standard",
            new BigDecimal("10"), "uw");
        underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Standard risk"), "uw-decider", false);

        TenantContext.set(tenantId);
        assertThat(policyApi.searchPolicies(fixture.applicantId(), null, null, null, null,
                PageRequest.of(0, 10)).getContent())
            .as("a premium of nil must not become a policy")
            .isEmpty();

        UnderwritingCaseView decided = underwritingApi.getCase(opened.caseId());
        assertThat(decided.decisionOutcome())
            .as("the decision was real and AFTER_COMMIT cannot take it back")
            .isEqualTo(DecisionOutcome.ACCEPT);
        assertThat(decided.issuanceFailureReason())
            .as("and the case must say that nothing came of it -- this is the whole fix")
            .isNotNull();
        assertThat(decided.issuanceFailureReason())
            .as("naming the terms, so the reader knows to go and look at the rating table")
            .contains("rating multiplier");
        assertThat(decided.issuanceFailedAt()).isNotNull();
    }

    /**
     * A PRICED PRODUCT CHARGES ITS OWN RATE, not one flat number for the whole platform.
     *
     * <p>Every automatically issued policy was priced from a single
     * {@code TZ_BASE_PREMIUM_RATE_PER_MILLE} in reference data. An actuary could author a full
     * mortality table — age bands, sex, smoker status, real rates — publish it, see it on the
     * product screen, and watch it change no premium at all: {@code quotePremium} read the
     * table, and nothing that issued a contract did. So the illustration a customer was shown
     * and the policy they actually got were priced by two different mechanisms, which is the
     * exact thing the quote breakdown exists to prevent.
     *
     * <p>The figure is exact so that a future change which merely moves the price still fails.
     * TZS 1,000,000 of cover at this product's own 12.0 per mille is 12,000 a year, 1,000.00 a
     * month. At the platform's flat 5.0 it would be 416.67 — the number this test produced
     * before, and the number it must never produce again.
     */
    @Test
    void aPricedProductIssuesAtItsOwnRateAndNotThePlatformFlatRate() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        ProductSummaryView product = productApi.createProduct("POLICY-PRICED-01", "Priced product",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            // No AGE and no SMOKER_STATUS factor: both are KEYS of the rate table below, and a
            // multiplier for either would charge the same fact twice -- publishVersion refuses
            // them on a priced version for that reason.
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null,
            List.of(new ProductApi.BaseRateInput(18, 78, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("12.0000")),
                    // Priced but never issued against here -- the life below is male. Present
                    // because a priced version must be able to price every life it accepts.
                    new ProductApi.BaseRateInput(18, 78, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("9.0000"))),
            new EligibilityBounds(18, 78, null, null, null, null), ANY_FILING, "actuary");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        PolicyView issued = issueFromProposal(tenantId,
            new Fixture(pricedLife(tenantId, 40, "5001"), product.productId(), versionId),
            new ProposalDetails(null, null, null, null, null, null, "MONTHLY", List.of()));

        assertThat(issued.premiumAmount())
            .as("the product's own 12.0 per mille, not the platform's flat 5.0")
            .isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    /**
     * A HOLE IN THE RATE TABLE REFUSES THE POLICY, and says so where a person will read it.
     *
     * <p>A combination the actuary never priced has no price. Filling it with a platform
     * default would sell cover nobody costed — the quietest possible way for a product to lose
     * money, since every policy issued that way looks perfectly normal.
     *
     * <p>The same three assertions as the nil-premium test above, and for the same reason:
     * issuance runs AFTER_COMMIT, so a refusal that only reaches a log leaves a customer with
     * an acceptance and no policy and nobody any the wiser.
     */
    @Test
    void aLifeTheRateTableDoesNotCoverIsRefusedRatherThanPricedAtADefault() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        ProductSummaryView product = productApi.createProduct("POLICY-PRICED-02", "Half-priced product",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null,
            // NON_SMOKER only, both sexes. "We never priced smokers" is a real state of a real
            // rate table, and the applicant below is one -- this is the hole, authored
            // deliberately.
            //
            // It used to be a SEX-shaped hole ("we never priced women"), which is no longer
            // publishable: a priced version must cover both sexes across the ages it accepts. A
            // smoker-status hole is the same defect in the shape the rules still permit, because
            // demanding a declaration is a real underwriting stance -- and it exercises exactly
            // that decision. The assertions below are unchanged; none of them was about sex.
            List.of(new ProductApi.BaseRateInput(18, 78, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("12.0000")),
                    new ProductApi.BaseRateInput(18, 78, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("9.0000"))),
            new EligibilityBounds(18, 78, null, null, null, null), ANY_FILING, "actuary");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        UUID applicantId = pricedLife(tenantId, 40, "5002", Sex.FEMALE, SmokerStatus.SMOKER);
        UnderwritingCaseView opened = underwritingApi.openCase(applicantId, product.productId(), versionId,
            new BigDecimal("1000000"), "TZS", null,
            new ProposalDetails(null, null, null, null, null, null, "MONTHLY", List.of()), "agent1");
        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Standard",
            new BigDecimal("10"), "uw");
        underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Standard risk"), "uw-decider", false);

        TenantContext.set(tenantId);
        assertThat(policyApi.searchPolicies(applicantId, null, null, null, null,
                PageRequest.of(0, 10)).getContent())
            .as("a life the actuary never priced must not become a contract at a guessed rate")
            .isEmpty();

        UnderwritingCaseView decided = underwritingApi.getCase(opened.caseId());
        assertThat(decided.decisionOutcome()).isEqualTo(DecisionOutcome.ACCEPT);
        assertThat(decided.issuanceFailureReason())
            .as("the case must say why, naming the life it could not price")
            .isNotNull();
        assertThat(decided.issuanceFailureReason())
            .as("in words that send the reader to the rate table rather than to an outage")
            .contains("base rate");
        assertThat(decided.issuanceFailedAt()).isNotNull();
    }

    /**
     * SmokerStatus.UNKNOWN is documented as "a real, ratable value rather than a null stand-in:
     * a product may price undeclared smoker status deliberately". It was unreachable from the
     * only path that issues a contract: issuance passed null for an unrecorded status, and
     * resolveBaseRatePerMille returns empty for a null. So an actuary could author that cell,
     * see it on the product screen, and watch it price nothing -- the same shape V5 and V9
     * removed for AGE and SUM_ASSURED_BAND.
     */
    @Test
    void automaticIssuancePricesAnUnrecordedSmokerStatusFromTheProductsUnknownCell() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        ProductSummaryView product = productApi.createProduct("POLICY-UNK-01", "Prices the undeclared case",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null,
            List.of(new ProductApi.BaseRateInput(18, 78, Sex.FEMALE, SmokerStatus.UNKNOWN, new BigDecimal("12.0000")),
                    new ProductApi.BaseRateInput(18, 78, Sex.MALE, SmokerStatus.UNKNOWN, new BigDecimal("14.0000"))),
            new EligibilityBounds(18, 78, null, null, null, null), ANY_FILING, "actuary");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        PolicyView issued = issueFromProposal(tenantId,
            new Fixture(lifeWithNoSmokerStatus(tenantId, 40, "5003"), product.productId(), versionId),
            new ProposalDetails(null, null, null, null, null, null, "MONTHLY", List.of()));

        assertThat(issued.premiumAmount())
            .as("priced from the product's own UNKNOWN cell -- 12.0 per mille on 1,000,000, monthly")
            .isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    /**
     * And a product that did NOT price the undeclared case still refuses, saying which of the
     * three fixable things is wrong rather than reading like a rate-table gap alone.
     */
    @Test
    void aProductThatDoesNotPriceTheUndeclaredCaseRefusesAndSaysSo() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        ProductSummaryView product = productApi.createProduct("POLICY-UNK-02", "Demands a declaration",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null,
            // NON_SMOKER only: a real stance, and one UNKNOWN cannot satisfy.
            List.of(new ProductApi.BaseRateInput(18, 78, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("12.0000")),
                    new ProductApi.BaseRateInput(18, 78, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("14.0000"))),
            new EligibilityBounds(18, 78, null, null, null, null), ANY_FILING, "actuary");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        UUID applicantId = lifeWithNoSmokerStatus(tenantId, 40, "5004");
        UnderwritingCaseView opened = underwritingApi.openCase(applicantId, product.productId(), versionId,
            new BigDecimal("1000000"), "TZS", null,
            new ProposalDetails(null, null, null, null, null, null, "MONTHLY", List.of()), "agent1");
        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Standard",
            new BigDecimal("10"), "uw");
        underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Standard risk"), "uw-decider", false);

        TenantContext.set(tenantId);
        assertThat(policyApi.searchPolicies(applicantId, null, null, null, null, PageRequest.of(0, 10)).getContent())
            .isEmpty();
        assertThat(underwritingApi.getCase(opened.caseId()).issuanceFailureReason())
            .as("names the undeclared status rather than printing a bare null")
            .contains("UNKNOWN (never recorded)");
    }

    // ---- Batch 2b: a contract covers what its product authored ------------------

    @Test
    void issuanceWritesOneCoveragePerAuthoredBenefit() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        ProductSummaryView product = productApi.createProduct("POLICY-B2B-01", "Death plus CI",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED),
                    new ProductApi.BenefitInput(BenefitType.CRITICAL_ILLNESS,
                        BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED, new BigDecimal("25.00"), null)),
            null, ANY_FILING, "actuary");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        String policyNumber = issueDirectly(tenantId,
            new Fixture(pricedLife(tenantId, 35, "7001"), product.productId(), versionId), List.of());

        // issueDirectly insures 1,000,000: DEATH pays all of it, CRITICAL_ILLNESS a quarter.
        CoverageStatusView status = policyApi.getCoverageStatus(policyNumber, LocalDate.now());
        assertThat(status.activeCoverages()).hasSize(2);
        assertThat(status.activeCoverages())
            .filteredOn(c -> c.benefitType() == BenefitType.CRITICAL_ILLNESS)
            .singleElement()
            .satisfies(ci -> assertThat(ci.sumAssuredAmount()).isEqualByComparingTo(new BigDecimal("250000.00")));
        assertThat(status.activeCoverages())
            .filteredOn(c -> c.benefitType() == BenefitType.DEATH)
            .singleElement()
            .satisfies(d -> assertThat(d.sumAssuredAmount()).isEqualByComparingTo(new BigDecimal("1000000")));
    }

    /**
     * A critical-illness claim was valued at the FULL DEATH SUM ASSURED, because claimableCover
     * took no claim type and returned the policy's single sum assured for everything. A survivable
     * condition paid the whole cover, on a rider nobody had costed.
     */
    @Test
    void claimableCoverValuesEachBenefitAtItsOwnAmount() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        ProductSummaryView product = productApi.createProduct("POLICY-B2B-CLAIM", "Death plus CI",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED),
                    new ProductApi.BenefitInput(BenefitType.CRITICAL_ILLNESS,
                        BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED, new BigDecimal("25.00"), null)),
            null, ANY_FILING, "actuary");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        String policyNumber = issueDirectly(tenantId,
            new Fixture(pricedLife(tenantId, 35, "7002"), product.productId(), versionId), List.of());

        assertThat(policyApi.claimableCover(policyNumber, null, LocalDate.now(), "DEATH").amount())
            .isEqualByComparingTo(new BigDecimal("1000000"));
        assertThat(policyApi.claimableCover(policyNumber, null, LocalDate.now(), "CRITICAL_ILLNESS").amount())
            .as("a quarter of the cover, not all of it")
            .isEqualByComparingTo(new BigDecimal("250000.00"));
    }

    @Test
    void claimableCoverRefusesABenefitTheProductDoesNotCover() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-B2B-DEATHONLY");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());

        assertThatThrownBy(() ->
            policyApi.claimableCover(policyNumber, null, LocalDate.now(), "CRITICAL_ILLNESS"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("CRITICAL_ILLNESS");
    }

    /**
     * THE GRANDFATHERING ASSERTION, and the one that proves nothing in force changed.
     *
     * <p>143 of 146 versions have no benefit rows, because the console allowed an empty schedule
     * until now. Refusing to issue on them would have made almost the whole catalogue unsellable,
     * and they cannot simply be republished -- that now needs entry-age bounds, rate-table
     * coverage and a TIRA filing. So they keep exactly today's behaviour: one DEATH coverage at
     * the policy's own sum assured, which is the number claimableCover already returns.
     *
     * <p>The rows are deleted directly because publishVersion can no longer create a benefit-less
     * version -- the same technique batches 1 and 3 each needed once a new rule made a real state
     * unreachable through the API.
     */
    @Test
    void aVersionWithNoAuthoredBenefitsStillIssuesWithASingleDeathCoverage() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-B2B-LEGACY");

        jdbcTemplate.update("DELETE FROM product.benefit_schedule WHERE product_version_id = ?",
            fixture.productVersionId());

        String policyNumber = issueDirectly(tenantId, fixture, List.of());

        CoverageStatusView status = policyApi.getCoverageStatus(policyNumber, LocalDate.now());
        assertThat(status.activeCoverages()).singleElement()
            .satisfies(c -> {
                assertThat(c.benefitType()).isEqualTo(BenefitType.DEATH);
                assertThat(c.sumAssuredAmount()).isEqualByComparingTo(new BigDecimal("1000000"));
            });
    }

    /**
     * ONE PRICE FOR ONE LIFE. The same product, the same life, the same frequency: the illustration
     * a customer is shown and the premium they are actually billed must be the same number.
     *
     * <p>They were not. The quote resolved the sum-assured factor by matching a band string while
     * issuance matched by range, and neither applied a frequency loading at all.
     */
    @Test
    void aQuoteAndTheIssuedPolicyChargeTheSameInstalment() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        ProductSummaryView product = productApi.createProduct("POLICY-AGREE-01", "One price",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "0-20m",
                        new BigDecimal("1.2000"), null, null,
                        new BigDecimal("0"), new BigDecimal("20000000")),
                    // Required by the QUOTE path, which resolves occupation strictly and refuses a
                    // class it cannot find. Issuance resolves the same factor neutrally from the
                    // party record, which carries no occupation class -- so both arrive at 1.0 and
                    // the two paths still agree. That asymmetry is deliberate: the quote will not
                    // guess a multiplier a caller typed, and issuance will not reject a case an
                    // underwriter has already decided.
                    new ProductApi.RatingFactorInput(FactorType.OCCUPATION_CLASS, "CLASS_1", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null,
            List.of(new ProductApi.BaseRateInput(18, 78, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("12.0000")),
                    new ProductApi.BaseRateInput(18, 78, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("12.0000"))),
            new EligibilityBounds(18, 78, null, null, null, null),
            new FrequencyLoading(new BigDecimal("8"), new BigDecimal("3")), ANY_FILING, "actuary");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        // The illustration.
        ProductApi.PremiumQuoteView quote = productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            product.productId(), new BigDecimal("1000000"), "TZS",
            LocalDate.now().minusYears(40).minusDays(1),
            Sex.MALE, SmokerStatus.NON_SMOKER, "CLASS_1", PremiumFrequency.MONTHLY, LocalDate.now()));

        // The contract, for the same life, through the real decision-to-issuance path. The
        // applicant has no OCCUPATION_CLASS recorded, which resolves neutral there and is asserted
        // as CLASS_1 above -- the product carries no occupation multiplier, so both are 1.0.
        PolicyView issued = issueFromProposal(tenantId,
            new Fixture(pricedLife(tenantId, 40, "6001"), product.productId(), versionId),
            new ProposalDetails(null, null, null, null, null, null, "MONTHLY", List.of()));

        assertThat(issued.premiumAmount())
            .as("the illustration and the first invoice must be one number")
            .isEqualByComparingTo(quote.instalmentAmount());
        // 1,000,000 / 1000 * 12.0 = 12,000, x 1.2 band = 14,400, x 1.08 monthly = 15,552, / 12.
        assertThat(issued.premiumAmount()).isEqualByComparingTo(new BigDecimal("1296.00"));
    }

    /** A life with a sex and a date of birth but NO recorded smoker status. */
    private UUID lifeWithNoSmokerStatus(UUID tenantId, int years, String phoneSuffix) {
        TenantContext.set(tenantId);
        return partyApi.registerIndividual(new IndividualRegistration(
            "Undeclared Smoker " + years + phoneSuffix,
            LocalDate.now().minusYears(years).minusDays(1),
            "+25571302" + phoneSuffix, null,
            tz.co.nlolo.lifeplatform.party.api.Sex.FEMALE, null,
            null, null, null, null, null, null), "test-agent").partyId();
    }

    /** A life a rate table can actually be keyed on: an age, a sex and a smoker status. */
    private UUID pricedLife(UUID tenantId, int years, String phoneSuffix) {
        return pricedLife(tenantId, years, phoneSuffix, Sex.MALE, SmokerStatus.NON_SMOKER);
    }

    private UUID pricedLife(UUID tenantId, int years, String phoneSuffix, Sex sex, SmokerStatus smokerStatus) {
        TenantContext.set(tenantId);
        // party's own Sex/SmokerStatus, which are a different pair of enums from product's --
        // neither module may depend on the other. Mapped by name, exactly as the listener does.
        return partyApi.registerIndividual(new IndividualRegistration(
            "Priced Life " + years + phoneSuffix,
            LocalDate.now().minusYears(years).minusDays(1),
            "+25571302" + phoneSuffix, null,
            tz.co.nlolo.lifeplatform.party.api.Sex.valueOf(sex.name()),
            tz.co.nlolo.lifeplatform.party.api.SmokerStatus.valueOf(smokerStatus.name()),
            null, null, null, null, null, null), "test-agent").partyId();
    }

    /** An applicant of a given age today, so the age bands under test resolve the same way every year. */
    private UUID applicantAged(UUID tenantId, int years, String phoneSuffix) {
        TenantContext.set(tenantId);
        return partyApi.registerIndividual("Rated Applicant " + years,
            LocalDate.now().minusYears(years).minusDays(1),
            "+25571301" + phoneSuffix, null, "test-agent").partyId();
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
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)), null, ANY_FILING, "actuary");
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

    // ---- IFRS 17 I2: classified at sale (V37) ------------------------------------------------

    @Test
    void aDirectSaleIsClassifiedFromItsProductAndTheHeadOfficeAndNeverChanges() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "IFRS-SALE-01");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        PolicyView view = policyApi.getPolicy(policyNumber);

        assertThat(view.portfolioCode()).isEqualTo("TERM");
        assertThat(view.cohortYear()).isEqualTo(LocalDate.now().getYear());
        assertThat(view.profitabilityBucket()).isEqualTo("REMAINING");
        assertThat(view.measurementModelOverride()).isNull();
        assertThat(view.salesChannel()).as("no case, no agent: a direct sale").isEqualTo("DIRECT");
        assertThat(view.branchCode()).as("nothing named a branch: head office").isEqualTo("DSM");

        assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE policy.policy SET sales_channel = 'BROKER' WHERE policy_number = ?", policyNumber))
            .hasStackTraceContaining("POLICY_CLASSIFICATION_IMMUTABLE");
    }

    @Test
    void anAgentsSaleTakesTheAgentsChannelAndBranch() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "IFRS-SALE-02");
        TenantContext.set(tenantId);
        PartyView agentParty = partyApi.registerIndividual("Broker For Sale", LocalDate.of(1985, 1, 1),
            "+255713990021", null, "test-staff");
        partyApi.submitKycEvidence(agentParty.partyId(), KycStatus.VERIFIED, "doc-sale-02", "kyc-officer");
        AgentView broker = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            agentParty.partyId(), "LIC-SALE-02", LocalDate.now().plusYears(1), null,
            tz.co.nlolo.lifeplatform.distribution.api.SalesChannel.BROKER, "ARU"), "test-staff");

        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("1000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY",
            broker.agentId(), List.of(), "Direct issuance test");
        PolicyView view = policyApi.getPolicy(policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber());

        assertThat(view.salesChannel()).isEqualTo("BROKER");
        assertThat(view.branchCode()).isEqualTo("ARU");
    }

    @Test
    void theCasesSaleWinsAndIsFixedOnceThePolicyIsIssued() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "IFRS-SALE-03");
        TenantContext.set(tenantId);
        UnderwritingCaseView opened = underwritingApi.openCase(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("1000000"), "TZS", null, ProposalDetails.selfInsured(), "agent1");
        underwritingApi.recordSale(opened.caseId(), "DIGITAL", "ZNZ", "uw");
        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Standard", new BigDecimal("10"), "uw");
        underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Standard risk"), "uw-decider", false);

        List<PolicyView> found = List.of();
        for (int attempt = 0; attempt < 50 && found.isEmpty(); attempt++) {
            TenantContext.set(tenantId);
            found = policyApi.searchPolicies(fixture.applicantId(), null, null, null, null, PageRequest.of(0, 10)).getContent();
            if (found.isEmpty()) Thread.sleep(100);
        }
        assertThat(found).hasSize(1);
        assertThat(found.get(0).salesChannel()).isEqualTo("DIGITAL");
        assertThat(found.get(0).branchCode()).isEqualTo("ZNZ");

        TenantContext.set(tenantId);
        assertThat(underwritingApi.getCase(opened.caseId()).saleLockedAt()).isNotNull();
        assertThatThrownBy(() -> underwritingApi.recordSale(opened.caseId(), "AGENT", "DSM", "uw"))
            .isInstanceOf(tz.co.nlolo.lifeplatform.underwriting.api.SaleFixedException.class);
    }

    // ---- The agent of record must be a real agent ---------------------------------------------

    /** A VERIFIED party onboarded as an agent, for the tests that need a real agent of record. */
    private AgentView onboardTestAgent(UUID tenantId, String tag, String phone) {
        TenantContext.set(tenantId);
        PartyView agentParty = partyApi.registerIndividual("Policy Test Agent " + tag,
            LocalDate.of(1985, 1, 1), phone, null, "test-staff");
        partyApi.submitKycEvidence(agentParty.partyId(), KycStatus.VERIFIED, "doc-" + tag, "kyc-officer");
        return distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            agentParty.partyId(), "LIC-POLICY-" + tag, LocalDate.now().plusYears(1), null), "test-staff");
    }

    /**
     * A hand-typed agent of record used to be stored without ever being checked, and a wrong one
     * was not inert: the policy issued, was attributed to nobody, and the accrual listener logged
     * "does not resolve to an agent" into a server log nobody reads. The sale earned the named
     * agent nothing and no surface ever said so. Six such policies exist in this platform's own
     * dev data, all pointing at one id that is not an agent.
     */
    @Test
    void issuingWithAnAgentOfRecordThatIsNotAnAgentIsRefused() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-AOR-BAD");
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(
            fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY",
            UUID.randomUUID(), List.of(), null,
            null, null, null, null, null);

        assertThrows(UnknownAgentOfRecordException.class,
            () -> policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff"));
    }

    /** Null is the honest way to say "nobody earns on this". It must keep working. */
    @Test
    void issuingWithNoAgentOfRecordIsStillADirectSale() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-AOR-NULL");
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(
            fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY",
            null, List.of(), null,
            null, null, null, null, null);

        PolicyView issued = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff");
        assertThat(issued.agentOfRecordId()).isNull();
    }

    /** The check must not reject a REAL agent -- a validation nobody can satisfy is worse than none. */
    @Test
    void issuingWithARealAgentOfRecordKeepsTheAttribution() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-AOR-GOOD");
        AgentView agent = onboardTestAgent(tenantId, "AOR", "+255713099555");
        TenantContext.set(tenantId);

        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(
            fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY",
            agent.agentId(), List.of(), null,
            null, null, null, null, null);

        PolicyView issued = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff");
        assertThat(issued.agentOfRecordId()).isEqualTo(agent.agentId());
    }

    /**
     * An agent from ANOTHER tenant is not an agent here. `getAgent` 404s cross-tenant under RLS,
     * so this lands on the same refusal as a made-up uuid rather than silently attributing a
     * policy across a tenant boundary.
     */
    @Test
    void anAgentFromAnotherTenantIsNotAValidAgentOfRecord() {
        UUID otherTenant = UUID.randomUUID();
        AgentView foreignAgent = onboardTestAgent(otherTenant, "FOREIGN", "+255713099556");

        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-AOR-XT");
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(
            fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY",
            foreignAgent.agentId(), List.of(), null,
            null, null, null, null, null);

        assertThrows(UnknownAgentOfRecordException.class,
            () -> policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff"));
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
            () -> policyApi.dischargeForSettledClaim(policyNumber, null, LocalDate.now(), UUID.randomUUID(), "test-claims"));
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

    /**
     * Closing an offer has to be audible.
     *
     * <p>Until this event existed an expired offer changed state in total silence: the pg_cron
     * sweep sets NOT_TAKEN_UP with a raw UPDATE, and SQL cannot publish a Spring event. Fine
     * while nothing consumed it; not fine once a customer needs telling they are not insured.
     */
    @Test
    void expiringAnOfferPublishesPolicyNotTakenUp() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-EXPIRE-01");
        Instant before = Instant.now();
        String policyNumber = issueDirectly(tenantId, fixture, List.of());

        TenantContext.set(tenantId);
        policyApi.expireOffer(policyNumber);

        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.NOT_TAKEN_UP);
        List<AuditLogEntry> published = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "policy.PolicyNotTakenUp", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(published).hasSize(1);
        JsonNode payload = objectMapper.readTree(published.get(0).getPayload());
        assertThat(payload.path("policyNumber").asText()).isEqualTo(policyNumber);
        // The consumer that needs this most -- communication -- may not depend on policy, so an
        // event naming only the policy would leave it with nobody to tell.
        assertThat(payload.path("policyholderPartyId").asText()).isEqualTo(fixture.applicantId().toString());
    }

    @Test
    void expiringSomethingThatIsAlreadyInForceIsSilentAndPublishesNothing() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-EXPIRE-02");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        TenantContext.set(tenantId);
        policyApi.activateOnFirstPremium(policyNumber);
        Instant before = Instant.now();

        // The race this guard exists for: the customer paid between the sweep selecting their
        // policy and this call reaching it. Not a fault -- the customer won, which is the outcome
        // everybody wanted -- so it must neither throw nor tell them their offer expired.
        policyApi.expireOffer(policyNumber);

        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.ACTIVE);
        assertThat(auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "policy.PolicyNotTakenUp", before.minusSeconds(5), Instant.now().plusSeconds(5)))
            .as("a policy somebody paid for must never be told it expired")
            .isEmpty();
    }

    @Test
    void expiringAnAlreadyExpiredOfferPublishesNoSecondEvent() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-EXPIRE-03");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        TenantContext.set(tenantId);
        policyApi.expireOffer(policyNumber);
        Instant afterFirst = Instant.now();

        policyApi.expireOffer(policyNumber);

        assertThat(auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "policy.PolicyNotTakenUp", afterFirst, Instant.now().plusSeconds(5)))
            .as("re-running the sweep must not tell the customer twice")
            .isEmpty();
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

    /** Seed a cash-value config + scale for a version, so a policy on it can be valued (step 1). */
    private void seedCashValue(UUID tenantId, UUID versionId) {
        TenantContext.set(tenantId);
        // The test connects as the table owner, so RLS is bypassed and tenant_id is set explicitly.
        jdbcTemplate.update("INSERT INTO product.cash_value_config "
            + "(product_version_id, tenant_id, basis_reference, basis_date, paid_up_basis, min_years_for_value) "
            + "VALUES (?, ?, 'TEST-BASIS-2026', '2026-01-01', 'PROPORTIONATE', 2)", versionId, tenantId);
        // Year 2 -> 200 per 1,000; year 3 -> 300. Unbanded (any entry age).
        jdbcTemplate.update("INSERT INTO product.cash_value_table "
            + "(tenant_id, product_version_id, policy_year, cash_value_per_mille) VALUES (?, ?, 2, 200)", tenantId, versionId);
        jdbcTemplate.update("INSERT INTO product.cash_value_table "
            + "(tenant_id, product_version_id, policy_year, cash_value_per_mille) VALUES (?, ?, 3, 300)", tenantId, versionId);
    }

    @Test
    void aSavingsPolicysCashValueFollowsTheTableAndBacksALoan() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "SAVINGS-CV-01");
        seedCashValue(tenantId, fixture.productVersionId());

        // A policy that commenced three years ago on this savings version, sum assured 2,000,000.
        TenantContext.set(tenantId);
        LocalDate commencement = LocalDate.now().minusYears(3);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("2000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY",
            null, List.of(), "cash value test", commencement, 240, null, null, null);
        String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
        policyApi.activateOnFirstPremium(policyNumber);

        // One year paid: below the 2-year minimum, so no value yet.
        policyApi.recalculateCashValue(policyNumber, commencement.plusYears(1));
        assertEquals(0, BigDecimal.ZERO.compareTo(
            policyAccountRepository.findById(policyNumber).orElseThrow().getCashValueAmount()));

        // Two years paid: year-2 scale, 2,000,000 * 200 / 1000 = 400,000.
        policyApi.recalculateCashValue(policyNumber, commencement.plusYears(2));
        assertEquals(0, new BigDecimal("400000.00").compareTo(
            policyAccountRepository.findById(policyNumber).orElseThrow().getCashValueAmount()));

        // Three years paid: year-3 scale, 600,000.
        policyApi.recalculateCashValue(policyNumber, commencement.plusYears(3));
        assertEquals(0, new BigDecimal("600000.00").compareTo(
            policyAccountRepository.findById(policyNumber).orElseThrow().getCashValueAmount()));

        // The cash value now backs a loan -- the loan module reads exactly this field, which was
        // 0 for every policy until this step. No LTV on this version, so the whole 600,000 is
        // borrowable: 700,000 is refused, and 500,000 reserves.
        assertThrows(tz.co.nlolo.lifeplatform.policy.api.InsufficientLoanValueException.class, () ->
            policyApi.reserveLoanValue(policyNumber, new BigDecimal("700000.00"), "TZS", Duration.ofMinutes(10)));
        assertThat(policyApi.reserveLoanValue(policyNumber, new BigDecimal("500000.00"), "TZS", Duration.ofMinutes(10)))
            .isNotNull();
    }

    @Test
    void makingASavingsPolicyPaidUpReducesCoverAndStopsPremiums() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "SAVINGS-PU-01");
        seedCashValue(tenantId, fixture.productVersionId());

        // Commenced 3 years ago, 20-year cover, premiums payable over 10 years (120 months).
        TenantContext.set(tenantId);
        LocalDate commencement = LocalDate.now().minusYears(3);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("2000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY",
            null, List.of(), "paid-up test", commencement, 240, 120, null, null);
        String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
        policyApi.activateOnFirstPremium(policyNumber);
        // Premiums paid to 3 years in: records paid_to_date, which paid-up reads.
        policyApi.recalculateCashValue(policyNumber, commencement.plusYears(3));

        PolicyView paidUp = policyApi.makePaidUp(policyNumber, "finance-officer");

        // Proportionate: 2,000,000 * 36 months paid / 120 payable = 600,000, and the policy is now
        // PAID_UP -- in force, no premium due.
        assertEquals(PolicyStatus.PAID_UP, paidUp.status());
        assertEquals(0, new BigDecimal("600000.00").compareTo(paidUp.sumAssuredAmount()));
        assertTrue(policyApi.isPolicyInForce(policyNumber, LocalDate.now()));
        // The coverage a claim pays against carries the reduced figure.
        assertThat(policyApi.getCoverageStatus(policyNumber, null).activeCoverages())
            .allSatisfy(c -> assertEquals(0, new BigDecimal("600000.00").compareTo(c.sumAssuredAmount())));
        // Already paid-up: not convertible again.
        assertThrows(InvalidPolicyStateException.class, () -> policyApi.makePaidUp(policyNumber, "finance-officer"));
    }

    @Test
    void paidUpIsRefusedOnANonSavingsPolicy() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "PROTECTION-PU");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        policyApi.activateOnFirstPremium(policyNumber);
        // No cash-value config on this version -> nothing to make paid-up.
        assertThrows(InvalidPolicyStateException.class, () -> policyApi.makePaidUp(policyNumber, "finance-officer"));
    }

    @Test
    void theLoanToValuePercentCapsWhatCanBeBorrowedAgainstCashValue() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "SAVINGS-CV-LTV");
        seedCashValue(tenantId, fixture.productVersionId());
        // Cap this version's loan-to-value at 50%.
        TenantContext.set(tenantId);
        jdbcTemplate.update("UPDATE product.product_version SET max_loan_to_value_percent = 50 WHERE product_version_id = ?",
            fixture.productVersionId());

        LocalDate commencement = LocalDate.now().minusYears(3);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("2000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY",
            null, List.of(), "ltv test", commencement, 240, null, null, null);
        String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
        policyApi.activateOnFirstPremium(policyNumber);
        policyApi.recalculateCashValue(policyNumber, commencement.plusYears(3)); // cash value 600,000

        // 50% of 600,000 = 300,000 borrowable: 350,000 is refused (it would be allowed without the
        // cap, since cash value is 600,000), and 300,000 reserves.
        assertThrows(tz.co.nlolo.lifeplatform.policy.api.InsufficientLoanValueException.class, () ->
            policyApi.reserveLoanValue(policyNumber, new BigDecimal("350000.00"), "TZS", Duration.ofMinutes(10)));
        assertThat(policyApi.reserveLoanValue(policyNumber, new BigDecimal("300000.00"), "TZS", Duration.ofMinutes(10)))
            .isNotNull();
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
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)), null, ANY_FILING, "actuary");
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

    @Test
    void surrenderRequestAndApprovalByADifferentUserSurrendersThePolicy() {
        // Two-person rule: the requester may not also approve. Test the happy path (two different
        // users) and the same-user rejection in one fixture to avoid spinning up two Postgres
        // contexts. Setup mirrors makingASavingsPolicyPaidUpReducesCoverAndStopsPremiums above.
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "SAVINGS-SURR-01");
        seedCashValue(tenantId, fixture.productVersionId());

        TenantContext.set(tenantId);
        LocalDate commencement = LocalDate.now().minusYears(3);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("2000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY",
            null, List.of(), "surrender test", commencement, 240, null, null, null);
        String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
        policyApi.activateOnFirstPremium(policyNumber);
        // Three years paid: year-3 scale gives 600,000 cash value, well above zero.
        policyApi.recalculateCashValue(policyNumber, commencement.plusYears(3));

        // Step 1: request a surrender. Returns a REQUESTED surrender with the quoted value.
        PolicyApi.SurrenderRequestView req = policyApi.requestSurrender(policyNumber, "MPESA-0712345678", "alice");
        assertEquals("REQUESTED", req.status());
        assertEquals(policyNumber, req.policyNumber());
        assertEquals(0, new BigDecimal("600000.00").compareTo(req.quotedValueAmount()));
        assertEquals("alice", req.requestedBy());
        // Cover is still in force during REQUESTED state.
        assertTrue(policyApi.isPolicyInForce(policyNumber, LocalDate.now()));

        // Two-person rule: alice cannot approve her own request.
        UUID requestId = req.surrenderRequestId();
        assertThrows(InvalidPolicyStateException.class, () -> policyApi.approveSurrender(requestId, "alice"));

        // Step 2: a different officer approves. Cover stops as of today (Q2 decision).
        PolicyApi.SurrenderRequestView approved = policyApi.approveSurrender(requestId, "bob");
        assertEquals("APPROVED", approved.status());
        assertEquals("bob", approved.approvedBy());
        // And the policy page can find it again, which is how the approver reached it.
        assertEquals(requestId, policyApi.findLatestSurrenderRequest(policyNumber).orElseThrow().surrenderRequestId());

        // The policy is now SURRENDERED and no longer in force.
        assertEquals(PolicyStatus.SURRENDERED, policyApi.getPolicy(policyNumber).status());
        assertFalse(policyApi.isPolicyInForce(policyNumber, LocalDate.now()));

        // A second surrender request on the same policy is refused (no in-flight surrender allowed
        // AND the policy is no longer in a surrenderable state).
        assertThrows(InvalidPolicyStateException.class,
            () -> policyApi.requestSurrender(policyNumber, "MPESA-0712345678", "carol"));
    }

    @Test
    void surrenderIsRefusedBeforeTheMinimumYears() {
        // min_years_for_value is 2 (seeded by seedCashValue). A policy issued today has not passed
        // the minimum and must be refused immediately -- before any cash value exists.
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "SAVINGS-SURR-02");
        seedCashValue(tenantId, fixture.productVersionId());

        TenantContext.set(tenantId);
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        policyApi.activateOnFirstPremium(policyNumber);
        // Cash value is zero (no recalculate call) -- minimum-years gate fires first.
        assertThrows(InvalidPolicyStateException.class,
            () -> policyApi.requestSurrender(policyNumber, "MPESA-0712345678", "requester"));
    }
}
