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
import org.springframework.data.domain.PageRequest;
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
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

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
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/policy/V8__group_policies_have_no_single_life_assured.sql",
            "db-migrations/policy/V9__group_scheme_and_members.sql",
            "db-migrations/policy/V13__freeform_members.sql",
            "db-migrations/policy/V14__credit_life_scheme.sql",
            "db-migrations/policy/V15__enrolment_submission.sql",
            "db-migrations/policy/V16__insurer_issued_member_reference.sql",
            "db-migrations/policy/V18__scheme_premium_rate.sql",
            "db-migrations/policy/V19__enrolment_premium.sql",
            "db-migrations/policy/V20__member_exit_reason.sql",
            "db-migrations/policy/V22__member_promoted_party.sql",
            "db-migrations/policy/V23__member_open_death_claim.sql",
            "db-migrations/policy/V24__issuance_record.sql",
            "db-migrations/policy/V25__credit_life_premium_basis.sql",
            "db-migrations/policy/V26__enrolment_stated_premium.sql",
            "db-migrations/policy/V10__one_policy_per_underwriting_case.sql",
            "db-migrations/policy/V11__not_taken_up_status.sql",
            "db-migrations/policy/V27__expired_status.sql",
            "db-migrations/policy/V28__policies_due_to_expire.sql",
            "db-migrations/policy/V29__paid_up.sql",
            "db-migrations/policy/V30__surrender.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");
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
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    private String issueDirectly(UUID tenantId, Fixture fixture, UUID underwritingCaseId) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY", null, List.of(), "Claim closure test");
        String issuedPolicyNumber = policyApi.issuePolicy(underwritingCaseId, request, "test-staff").policyNumber();
        // Cover starts with the first premium. This fixture needs a policy on risk.
        policyApi.activateOnFirstPremium(issuedPolicyNumber);
        return issuedPolicyNumber;
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

    /**
     * A one-life group scheme, in force. The smallest thing the group guard can be tested against.
     *
     * <p>Not built on {@link #issueDirectly}: a scheme is issued by {@code issueGroupScheme}, in
     * one call together with its opening schedule, because a scheme's sum assured IS the total of
     * its members and one issued empty would have a sum assured of nil.
     */
    private GroupFixture issueGroupScheme(UUID tenantId, String productCode) {
        Fixture fixture = buildFixture(tenantId, productCode, ProductCategory.GROUP_LIFE);
        TenantContext.set(tenantId);
        PartyView life = partyApi.registerIndividual("Insured Life " + productCode, LocalDate.of(1990, 1, 1),
            "+25571500" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent");
        PartyView survivor = partyApi.registerIndividual("Surviving Life " + productCode, LocalDate.of(1990, 1, 1),
            "+25571600" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent");
        // TWO lives, not one, and that is not incidental. Exiting the only member drives the
        // scheme total to zero, which Policy.restateSumAssured refuses outright -- see
        // dischargingTheLastMemberOfASchemeIsNotYetRepresentable below.
        String policyNumber = policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            fixture.applicantId(), fixture.productId(), fixture.productVersionId(), null,
            BenefitBasis.FLAT, new BigDecimal("5000000.00"), null, null, "TZS", null,
            List.of(new PolicyApi.MemberInput(life.partyId(), null, null, null),
                    new PolicyApi.MemberInput(survivor.partyId(), null, null, null)),
            new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now().minusMonths(9), null,
            "Claim closure group fixture", IssuanceBasis.MIGRATION), "test-staff").policyNumber();
        UUID memberId = policyApi.listMembers(policyNumber, null, null, PageRequest.of(0, 25))
            .getContent().stream()
            .filter(m -> m.memberPartyId().equals(life.partyId()))
            .findFirst().orElseThrow().policyMemberId();
        return new GroupFixture(policyNumber, memberId);
    }

    /** A one-life scheme, for the last-member case that has no answer yet. */
    private GroupFixture issueSingleLifeGroupScheme(UUID tenantId, String productCode) {
        Fixture fixture = buildFixture(tenantId, productCode, ProductCategory.GROUP_LIFE);
        TenantContext.set(tenantId);
        PartyView life = partyApi.registerIndividual("Only Life " + productCode, LocalDate.of(1990, 1, 1),
            "+25571700" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent");
        String policyNumber = policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            fixture.applicantId(), fixture.productId(), fixture.productVersionId(), null,
            BenefitBasis.FLAT, new BigDecimal("5000000.00"), null, null, "TZS", null,
            List.of(new PolicyApi.MemberInput(life.partyId(), null, null, null)),
            new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now().minusMonths(9), null,
            "Single-life scheme fixture", IssuanceBasis.MIGRATION), "test-staff").policyNumber();
        return new GroupFixture(policyNumber, policyApi.listMembers(policyNumber, null, null,
            PageRequest.of(0, 25)).getContent().get(0).policyMemberId());
    }

    /**
     * When the LAST life goes, the scheme goes with it.
     *
     * <p>This is the mirror of the bug the branch exists to fix, not a return to it. That one
     * closed a scheme when one of many members died; this closes it when there is nobody left
     * to insure — the same fact an individual policy states when its single life dies, so it
     * takes the same status and publishes the same event.
     *
     * <p>Both events fire, and both are true: the member left, and the contract then had no
     * covered lives. A consumer tracking membership needs the first; billing needs the second.
     */
    @Test
    void dischargingTheLastLifeClosesTheSchemeItself() throws Exception {
        UUID tenantId = UUID.randomUUID();
        GroupFixture scheme = issueSingleLifeGroupScheme(tenantId, "CLAIM-CLOSURE-GRP-LAST");
        LocalDate dateOfEvent = LocalDate.now().minusMonths(6);
        UUID claimId = UUID.randomUUID();

        TenantContext.set(tenantId);
        Instant before = Instant.now();
        policyApi.dischargeForSettledClaim(scheme.policyNumber(), scheme.policyMemberId(),
            dateOfEvent, claimId, "test-staff");

        assertEquals(PolicyStatus.SURRENDERED, policyApi.getPolicy(scheme.policyNumber()).status(),
            "a scheme insuring nobody is a closed contract, and billing must stop");
        assertEquals(MemberStatus.EXITED,
            memberById(scheme.policyNumber(), scheme.policyMemberId()).status());

        // The sum assured keeps its last positive value rather than going to zero:
        // policy_sum_assured_positive forbids zero, and every other closed policy on the
        // platform keeps the figure it was last insured for.
        assertThat(policyApi.getPolicy(scheme.policyNumber()).sumAssuredAmount())
            .isEqualByComparingTo(new BigDecimal("5000000.00"));

        List<AuditLogEntry> surrendered = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "policy.PolicySurrendered", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(surrendered).hasSize(1);
        assertThat(objectMapper.readTree(surrendered.get(0).getPayload()).path("claimId").asText())
            .isEqualTo(claimId.toString());
    }

    /** Redelivery on a one-life scheme must not publish a second PolicySurrendered either. */
    @Test
    void dischargingTheLastLifeTwiceClosesItOnceAndSaysSoOnce() throws Exception {
        UUID tenantId = UUID.randomUUID();
        GroupFixture scheme = issueSingleLifeGroupScheme(tenantId, "CLAIM-CLOSURE-GRP-LAST2");
        LocalDate dateOfEvent = LocalDate.now().minusMonths(6);
        UUID claimId = UUID.randomUUID();

        TenantContext.set(tenantId);
        Instant before = Instant.now();
        policyApi.dischargeForSettledClaim(scheme.policyNumber(), scheme.policyMemberId(), dateOfEvent, claimId, "test-staff");
        policyApi.dischargeForSettledClaim(scheme.policyNumber(), scheme.policyMemberId(), dateOfEvent, claimId, "test-staff");

        assertEquals(PolicyStatus.SURRENDERED, policyApi.getPolicy(scheme.policyNumber()).status());
        assertThat(auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
                tenantId, "policy.PolicySurrendered", before.minusSeconds(5), Instant.now().plusSeconds(5)))
            .as("the second call short-circuits on the already-EXITED member, before reaching the close")
            .hasSize(1);
    }

    private record GroupFixture(String policyNumber, UUID policyMemberId) {}

    /**
     * The member under test, by id.
     *
     * <p>NOT getContent().get(0): both fixture members join on the same day, so listMembers
     * orders them by policyMemberId and the first row is an arbitrary one of the two.
     */
    private PolicyMemberView memberById(String policyNumber, UUID policyMemberId) {
        return policyApi.listMembers(policyNumber, null, null, PageRequest.of(0, 25))
            .getContent().stream().filter(m -> m.policyMemberId().equals(policyMemberId))
            .findFirst().orElseThrow(() -> new AssertionError("Member " + policyMemberId + " vanished"));
    }

    /**
     * A settled claim discharges one LIFE on a scheme, not the contract.
     *
     * <p>Individual life is the case {@code dischargeForSettledClaim} was originally written
     * for: the single insured life is dead, the contract is over, and billing must stop
     * invoicing it. On a scheme none of that holds — the other members are alive and insured
     * and the employer still owes premium — and doing it anyway set the master policy to
     * SURRENDERED and uninsured the whole workforce, silently, with the claim money already
     * paid.
     *
     * <p>Exercised here through the policy API alone, without the payment rail:
     * {@code ClaimSettlementEndToEndTest.aSettledMemberClaimExitsThatLifeAndLeavesTheSchemeInForce}
     * covers the same behaviour through a real disbursement, and this one isolates the branch
     * itself. The mirror case — that an individual policy still closes — is
     * {@link #terminateForSettledClaimMovesAnActivePolicyToSurrenderedAndPublishesPolicySurrenderedWithClaimId},
     * which is what keeps the branch narrow rather than a blanket disabling of closure.
     */
    @Test
    void dischargingASettledClaimOnASchemeExitsTheMemberAndLeavesTheSchemeInForce() {
        UUID tenantId = UUID.randomUUID();
        GroupFixture scheme = issueGroupScheme(tenantId, "CLAIM-CLOSURE-GRP-01");
        LocalDate dateOfEvent = LocalDate.now().minusMonths(6);

        TenantContext.set(tenantId);
        policyApi.dischargeForSettledClaim(scheme.policyNumber(), scheme.policyMemberId(),
            dateOfEvent, UUID.randomUUID(), "test-staff");

        assertEquals(PolicyStatus.ACTIVE, policyApi.getPolicy(scheme.policyNumber()).status(),
            "a scheme survives its members");
        PolicyMemberView member = memberById(scheme.policyNumber(), scheme.policyMemberId());
        assertEquals(MemberStatus.EXITED, member.status());
        assertEquals(dateOfEvent, member.leftOn(),
            "dated to the event, not to the day the payment cleared");
    }

    /** Redelivery must not re-exit a member or publish a second GroupMemberExited. */
    @Test
    void dischargingTheSameSettledClaimTwiceIsASilentNoOpOnAScheme() {
        UUID tenantId = UUID.randomUUID();
        GroupFixture scheme = issueGroupScheme(tenantId, "CLAIM-CLOSURE-GRP-02");
        LocalDate dateOfEvent = LocalDate.now().minusMonths(6);
        UUID claimId = UUID.randomUUID();

        TenantContext.set(tenantId);
        policyApi.dischargeForSettledClaim(scheme.policyNumber(), scheme.policyMemberId(), dateOfEvent, claimId, "test-staff");
        policyApi.dischargeForSettledClaim(scheme.policyNumber(), scheme.policyMemberId(), dateOfEvent, claimId, "test-staff");

        PolicyMemberView member = memberById(scheme.policyNumber(), scheme.policyMemberId());
        assertEquals(MemberStatus.EXITED, member.status());
        assertEquals(dateOfEvent, member.leftOn());
    }

    @Test
    void terminateForSettledClaimMovesAnActivePolicyToSurrenderedAndPublishesPolicySurrenderedWithClaimId() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIM-CLOSURE-TERM-01");
        String policyNumber = issueDirectly(tenantId, fixture, UUID.randomUUID());
        UUID claimId = UUID.randomUUID();

        TenantContext.set(tenantId);
        Instant before = Instant.now();
        policyApi.dischargeForSettledClaim(policyNumber, null, LocalDate.now(), claimId, "test-staff");

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
        policyApi.dischargeForSettledClaim(policyNumber, null, LocalDate.now(), claimId, "test-staff");
        policyApi.dischargeForSettledClaim(policyNumber, null, LocalDate.now(), claimId, "test-staff"); // second call: silent no-op

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
        policyApi.dischargeForSettledClaim(policyNumber, null, LocalDate.now(), UUID.randomUUID(), "test-staff");

        assertEquals(PolicyStatus.SURRENDERED, policyApi.getPolicy(policyNumber).status());
    }

    @Test
    void terminateForSettledClaimClosesASuspendedPolicy() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIM-CLOSURE-TERM-SUSP-01", ProductCategory.GROUP_LIFE);
        String policyNumber = issueDirectly(tenantId, fixture, UUID.randomUUID());

        TenantContext.set(tenantId);
        policyApi.suspendPolicy(policyNumber, "Under investigation", "test-staff");
        policyApi.dischargeForSettledClaim(policyNumber, null, LocalDate.now(), UUID.randomUUID(), "test-staff");

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
        policyApi.dischargeForSettledClaim(maturedFirst, null, LocalDate.now(), UUID.randomUUID(), "test-staff"); // no-op
        assertEquals(PolicyStatus.MATURED, policyApi.getPolicy(maturedFirst).status());

        policyApi.dischargeForSettledClaim(surrenderedFirst, null, LocalDate.now(), UUID.randomUUID(), "test-staff");
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
