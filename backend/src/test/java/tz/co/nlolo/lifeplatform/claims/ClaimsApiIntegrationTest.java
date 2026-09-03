package tz.co.nlolo.lifeplatform.claims;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.claims.api.ClaimAssessmentView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimNotFoundException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimValidationException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.DisabilityClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.InvalidClaimStateException;
import tz.co.nlolo.lifeplatform.claims.api.MaturityClaimDetails;
import tz.co.nlolo.lifeplatform.claims.domain.Claim;
import tz.co.nlolo.lifeplatform.claims.infrastructure.ClaimRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.*;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Task 4: registration's full validation chain, run under REAL {@code app_role}
 * (NOSUPERUSER NOBYPASSRLS) rather than the Testcontainers superuser every other module's
 * integration test uses -- copies {@code MobileMoneyCallbackIntegrationTest}'s
 * {@code @DynamicPropertySource} + {@code ALTER ROLE} setup, the freshest correct example in
 * this codebase, so RLS on {@code claims.claim} is genuinely exercised, not merely declared.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class ClaimsApiIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "claims_it_password";

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
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @Autowired private ClaimsApi claimsApi;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private ClaimRepository claimRepository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Fixture(UUID applicantId, UUID productId, UUID productVersionId) {}

    private Fixture buildFixture(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Claims IT Applicant " + productCode, LocalDate.of(1985, 3, 1),
            "+25571500" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Claims IT Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    /** Issues a policy with a NULL underwritingCaseId -- the exact pre-M6 / unbackfillable shape
     * Task 4's fail-closed contestability path exists for. Every scenario below except the
     * dedicated cross-tenant/unknown-party ones uses this, since none of the OTHER assertions
     * this task cares about (in-force check, coverage check, details/type mismatch) depend on
     * which contestability answer comes back. */
    private String issuePolicyWithNullUnderwritingCase(UUID tenantId, Fixture fixture) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("2000000"), "TZS", new BigDecimal("40000.00"), "TZS", "MONTHLY", null, List.of(), "Claims IT test");
        return policyApi.issuePolicy(null, request, "test-staff").policyNumber();
    }

    private ClaimsApi.RegisterClaimRequest deathRequest(String policyNumber, UUID claimantId, LocalDate dateOfEvent) {
        return new ClaimsApi.RegisterClaimRequest(policyNumber, claimantId, ClaimType.DEATH, dateOfEvent,
            new DeathClaimDetails("Natural causes", "Dar es Salaam", dateOfEvent, "Dr. Test"));
    }

    private ClaimsApi.RegisterClaimRequest maturityRequest(String policyNumber, UUID claimantId, LocalDate dateOfEvent) {
        return new ClaimsApi.RegisterClaimRequest(policyNumber, claimantId, ClaimType.MATURITY, dateOfEvent,
            new MaturityClaimDetails(dateOfEvent));
    }

    /** Registers and returns a fresh DEATH claim id, tenant already set on {@link TenantContext}. */
    private UUID registerDeathClaim(UUID tenantId, Fixture fixture, String policyNumber, String idempotencyKey) {
        TenantContext.set(tenantId);
        return claimsApi.registerClaim(deathRequest(policyNumber, fixture.applicantId(), LocalDate.now().minusDays(1)),
            idempotencyKey, "claims-staff").claimId();
    }

    /** Drives SETTLEMENT_REQUESTED -> SETTLED directly on the aggregate, bypassing
     * {@link ClaimsApi} -- ClaimsApi has no public method for this transition, since the real
     * caller is Task 6's payment-completion listener, which is out of scope here. This is test
     * setup only, exercising {@code Claim.markSettled()} which Task 3's unit test already covers
     * in isolation; the point of this helper is reaching SETTLED so reopenClaim's second source
     * state can be exercised, not re-testing markSettled itself. */
    private void settleDirectly(UUID claimId, UUID tenantId) {
        TenantContext.set(tenantId);
        Claim claim = claimRepository.findByClaimIdAndTenantId(claimId, tenantId).orElseThrow();
        claim.markSettled();
        claimRepository.save(claim);
    }

    /** Forces UNDER_ASSESSMENT with NO ClaimAssessment row, bypassing submitAssessment (which
     * always inserts one). Exists to isolate decideSettlement's assessment-count guard from
     * Claim.approve()'s own state-machine guard: with a REGISTERED claim, either guard throws
     * InvalidClaimStateException, so a REGISTERED-claim test alone cannot tell which one fired --
     * a review of this task found exactly that ambiguity and confirmed by mutation that removing
     * the count guard entirely still left the suite green. */
    private void beginAssessmentDirectlyWithNoAssessmentRow(UUID claimId, UUID tenantId) {
        TenantContext.set(tenantId);
        Claim claim = claimRepository.findByClaimIdAndTenantId(claimId, tenantId).orElseThrow();
        claim.beginAssessment();
        claimRepository.save(claim);
    }

    @Test
    void registersADeathClaimAndPublishesRegistrationWithFailClosedContestabilityFlagged() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-REG-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);

        TenantContext.set(tenantId);
        LocalDate dateOfEvent = LocalDate.now().minusDays(3);
        ClaimView view = claimsApi.registerClaim(deathRequest(policyNumber, fixture.applicantId(), dateOfEvent),
            "reg-idem-01", "claims-staff");

        assertThat(view.claimId()).isNotNull();
        assertThat(view.policyNumber()).isEqualTo(policyNumber);
        assertThat(view.claimantPartyId()).isEqualTo(fixture.applicantId());
        assertThat(view.claimType()).isEqualTo(ClaimType.DEATH);
        assertThat(view.dateOfEvent()).isEqualTo(dateOfEvent);
        assertThat(view.details()).isInstanceOf(DeathClaimDetails.class);
        // The policy's underwritingCaseId is NULL -- the fail-closed path MUST flag this claim
        // for review, not silently wave it through as if it were safely outside the window.
        assertThat(view.requiresContestabilityReview())
            .as("a NULL underwritingCaseId must fail closed to true, never silently pass as false")
            .isTrue();

        // Round-trips through getClaim too, proving it is genuinely persisted, not just an
        // in-memory view returned by registerClaim.
        ClaimView reloaded = claimsApi.getClaim(view.claimId());
        assertThat(reloaded.claimId()).isEqualTo(view.claimId());
        assertThat(reloaded.requiresContestabilityReview()).isTrue();
    }

    @Test
    void rejectsRegistrationWhenThePolicyIsNotInForce() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-LAPSE-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);

        TenantContext.set(tenantId);
        policyApi.lapsePolicy(policyNumber, "test-staff"); // isPolicyInForce now returns false

        ClaimsApi.RegisterClaimRequest request = deathRequest(policyNumber, fixture.applicantId(), LocalDate.now());
        assertThrows(ClaimValidationException.class, () -> claimsApi.registerClaim(request, "reg-idem-02", "claims-staff"));
    }

    @Test
    void rejectsRegistrationWhenDetailsClaimTypeDisagreesWithTheDeclaredClaimType() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-MISMATCH-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);

        TenantContext.set(tenantId);
        LocalDate dateOfEvent = LocalDate.now().minusDays(1);
        // claimType says DEATH, details says DISABILITY -- Claim's own constructor
        // (Claim.java:107-113) must reject this, and ClaimsApiImpl must let it propagate.
        ClaimsApi.RegisterClaimRequest request = new ClaimsApi.RegisterClaimRequest(policyNumber, fixture.applicantId(),
            ClaimType.DEATH, dateOfEvent, new DisabilityClaimDetails("Loss of limb", dateOfEvent, true, new BigDecimal("50")));

        assertThrows(ClaimValidationException.class, () -> claimsApi.registerClaim(request, "reg-idem-03", "claims-staff"));
    }

    @Test
    void rejectsRegistrationForAnUnknownClaimant() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-NOPARTY-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);

        TenantContext.set(tenantId);
        UUID unknownPartyId = UUID.randomUUID();
        ClaimsApi.RegisterClaimRequest request = deathRequest(policyNumber, unknownPartyId, LocalDate.now());

        assertThrows(PartyNotFoundException.class, () -> claimsApi.registerClaim(request, "reg-idem-04", "claims-staff"));
    }

    @Test
    void aNullUnderwritingCaseIdFailsClosedToRequiringContestabilityReview() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-CONTEST-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);

        TenantContext.set(tenantId);
        ClaimView view = claimsApi.registerClaim(deathRequest(policyNumber, fixture.applicantId(), LocalDate.now()),
            "reg-idem-05", "claims-staff");

        // Falsifiable: if the fail-closed guard were missing or inverted, this would come back
        // false (or throw an NPE calling checkContestability with a null case id) instead.
        assertThat(view.requiresContestabilityReview()).isTrue();
    }

    @Test
    void aClaimIsInvisibleToAnyOtherTenantUnderRealRls() {
        UUID ownerTenant = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        Fixture fixture = buildFixture(ownerTenant, "CLAIMS-IT-TENANT-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(ownerTenant, fixture);

        TenantContext.set(ownerTenant);
        ClaimView view = claimsApi.registerClaim(deathRequest(policyNumber, fixture.applicantId(), LocalDate.now()),
            "reg-idem-06", "claims-staff");
        UUID claimId = view.claimId();

        // Same claimId, DIFFERENT tenant context -- must come back not-found, proving
        // ClaimRepository.findByClaimIdAndTenantId is genuinely enforced by Postgres RLS under
        // real app_role (NOSUPERUSER NOBYPASSRLS), not merely by the tenantId argument the
        // application layer happens to pass.
        TenantContext.set(otherTenant);
        assertThrows(ClaimNotFoundException.class, () -> claimsApi.getClaim(claimId));

        // Sanity check: the SAME tenant can still read it, so the not-found above is genuinely
        // about tenant isolation and not some other bug (e.g. a broken claimId).
        TenantContext.set(ownerTenant);
        assertThat(claimsApi.getClaim(claimId).claimId()).isEqualTo(claimId);
    }

    // ---- Task 5: submitAssessment ----------------------------------------------------------

    @Test
    void submitAssessmentMovesRegisteredToUnderAssessment() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-ASSESS-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-assess-01");

        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.REGISTERED);

        ClaimAssessmentView assessment = claimsApi.submitAssessment(claimId, "Consistent with cause of death",
            new BigDecimal("2000000"), "TZS", false, "assessor-1");

        assertThat(assessment.claimId()).isEqualTo(claimId);
        assertThat(assessment.assessor()).isEqualTo("assessor-1");
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.UNDER_ASSESSMENT);
    }

    // ---- Task 5: decideSettlement -- assessment-count invariant (Cl3) ----------------------

    @Test
    void approvalWithoutAnyAssessmentThrowsForDeath() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-NOASSESS-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-noassess-01");

        // DEATH has no assessment rows at all -- claim.approve would even reject the state
        // transition itself (still REGISTERED), but the assessment-count guard must fire first.
        assertThrows(InvalidClaimStateException.class, () -> claimsApi.decideSettlement(claimId, true,
            new BigDecimal("2000000"), "TZS", null, "payee-ref-1", "settle-idem-noassess-01", "manager-1"));
    }

    /** Isolates the assessment-count guard from Claim.approve()'s own state-machine guard: unlike
     * approvalWithoutAnyAssessmentThrowsForDeath (still REGISTERED, so EITHER guard could be the
     * one throwing), this claim is genuinely UNDER_ASSESSMENT with zero ClaimAssessment rows --
     * approve()'s state check alone would allow this transition, so only the count guard can be
     * the cause if this still throws. */
    @Test
    void approvalWithZeroAssessmentRowsThrowsForDeathEvenWhenUnderAssessment() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-NOASSESS-02");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-noassess-02");

        beginAssessmentDirectlyWithNoAssessmentRow(claimId, tenantId);
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.UNDER_ASSESSMENT);

        assertThrows(InvalidClaimStateException.class, () -> claimsApi.decideSettlement(claimId, true,
            new BigDecimal("2000000"), "TZS", null, "payee-ref-1", "settle-idem-noassess-02", "manager-1"));
    }

    @Test
    void approvalWithoutAnyAssessmentSucceedsForMaturity() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-MATURITY-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);

        TenantContext.set(tenantId);
        UUID claimId = claimsApi.registerClaim(maturityRequest(policyNumber, fixture.applicantId(), LocalDate.now()),
            "reg-idem-maturity-01", "claims-staff").claimId();

        // Falsifiable: MATURITY is the ONLY claim type allowed to auto-approve with zero
        // assessments (docs/03-aggregate-design.md:134, Cl3). No submitAssessment call precedes
        // this, and it still succeeds.
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "payee-ref-2", "settle-idem-maturity-01", "manager-1");

        ClaimView view = claimsApi.getClaim(claimId);
        assertThat(view.status()).isEqualTo(ClaimStatus.SETTLEMENT_REQUESTED);
        // isEqualByComparingTo, not isEqualTo -- Postgres NUMERIC(19,2) round-trips as
        // "2000000.00", which is BigDecimal-unequal but numerically equal to "2000000".
        assertThat(view.approvedAmount()).isEqualByComparingTo(new BigDecimal("2000000"));
    }

    // ---- Task 5: decideSettlement -- separation of duties -----------------------------------

    @Test
    void theSameUserAssessingThenDecidingIsRejected() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-SOD-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-sod-01");

        claimsApi.submitAssessment(claimId, "Findings", new BigDecimal("2000000"), "TZS", false, "same-person");

        // "same-person" both assessed and is now trying to decide -- must be rejected even
        // though nothing here checks their Keycloak role, only the persisted assessor identity.
        assertThrows(ClaimValidationException.class, () -> claimsApi.decideSettlement(claimId, true,
            new BigDecimal("2000000"), "TZS", null, "payee-ref-3", "settle-idem-sod-01", "same-person"));
    }

    @Test
    void aDifferentDeciderThanTheAssessorIsAllowed() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-SOD-02");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-sod-02");

        claimsApi.submitAssessment(claimId, "Findings", new BigDecimal("2000000"), "TZS", false, "assessor-2");

        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "payee-ref-4", "settle-idem-sod-02", "manager-2");

        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.SETTLEMENT_REQUESTED);
    }

    // ---- Task 5: decideSettlement -- payeeRef / idempotencyKey required on approval --------

    @Test
    void aBlankPayeeRefOnApprovalIsRejected() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-BLANKPAYEE-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-blankpayee-01");
        claimsApi.submitAssessment(claimId, "Findings", new BigDecimal("2000000"), "TZS", false, "assessor-3");

        assertThrows(ClaimValidationException.class, () -> claimsApi.decideSettlement(claimId, true,
            new BigDecimal("2000000"), "TZS", null, "   ", "settle-idem-blankpayee-01", "manager-3"));
    }

    @Test
    void aBlankIdempotencyKeyOnApprovalIsRejected() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-BLANKIDEM-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-blankidem-01");
        claimsApi.submitAssessment(claimId, "Findings", new BigDecimal("2000000"), "TZS", false, "assessor-4");

        // A blank key would otherwise silently reach the payment rail zero times.
        assertThrows(ClaimValidationException.class, () -> claimsApi.decideSettlement(claimId, true,
            new BigDecimal("2000000"), "TZS", null, "payee-ref-5", "   ", "manager-4"));
    }

    // ---- Task 5: fraudIndicator must never itself block approval ---------------------------

    @Test
    void fraudIndicatorTrueDoesNotBlockApproval() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-FRAUD-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-fraud-01");

        ClaimAssessmentView assessment = claimsApi.submitAssessment(claimId, "Suspicious circumstances",
            new BigDecimal("2000000"), "TZS", true, "assessor-5");
        assertThat(assessment.fraudIndicator()).isTrue();

        // Falsifiable: if fraudIndicator wrongly gated approval, this would throw instead.
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "payee-ref-6", "settle-idem-fraud-01", "manager-5");

        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.SETTLEMENT_REQUESTED);
    }

    // ---- Task 5: decideSettlement -- rejection path -----------------------------------------

    @Test
    void rejectionRecordsTheReasonAndReachesRejected() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-REJECT-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-reject-01");
        claimsApi.submitAssessment(claimId, "Insufficient evidence", new BigDecimal("2000000"), "TZS", false, "assessor-6");

        claimsApi.decideSettlement(claimId, false, null, null, "Cause of death not covered",
            null, null, "manager-6");

        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.REJECTED);
    }

    // ---- Task 5: reopenClaim -----------------------------------------------------------------

    @Test
    void reopenWorksFromRejected() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-REOPEN-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-reopen-01");
        claimsApi.submitAssessment(claimId, "Findings", new BigDecimal("2000000"), "TZS", false, "assessor-7");
        claimsApi.decideSettlement(claimId, false, null, null, "Not covered", null, null, "manager-7");
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.REJECTED);

        claimsApi.reopenClaim(claimId, "New evidence submitted", "manager-7");

        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.REOPENED);
    }

    @Test
    void reopenWorksFromSettled() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-REOPEN-02");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-reopen-02");
        claimsApi.submitAssessment(claimId, "Findings", new BigDecimal("2000000"), "TZS", false, "assessor-8");
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "payee-ref-7", "settle-idem-reopen-02", "manager-8");
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.SETTLEMENT_REQUESTED);

        // decideSettlement only reaches SETTLEMENT_REQUESTED (Task 6 owns the payment
        // completion callback that marks SETTLED) -- drive the remaining hop directly on the
        // aggregate via the repository so this test can reach the SETTLED source state without
        // depending on Task 6's payment plumbing.
        settleDirectly(claimId, tenantId);
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.SETTLED);

        claimsApi.reopenClaim(claimId, "Dispute raised", "manager-8");

        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.REOPENED);
    }

    // ---- Task 9 review fix (Part B): registration idempotency ------------------------------

    @Test
    void registrationRejectsABlankOrMissingIdempotencyKey() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-REGIDEM-BLANK-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        TenantContext.set(tenantId);

        ClaimsApi.RegisterClaimRequest request = deathRequest(policyNumber, fixture.applicantId(), LocalDate.now());
        assertThrows(ClaimValidationException.class, () -> claimsApi.registerClaim(request, null, "claims-staff"));
        assertThrows(ClaimValidationException.class, () -> claimsApi.registerClaim(request, "   ", "claims-staff"));
    }

    /** The exact double-payout path Task 9's review surfaced: two independently-created Claim
     * rows for the same real-world event could each be independently assessed and settled.
     * Repeating the SAME registration idempotency key must return the SAME claim -- not error,
     * not create a second row -- matching this platform's idempotency semantics everywhere else. */
    @Test
    void repeatingTheSameRegistrationIdempotencyKeyReturnsTheSameClaimAndCreatesOnlyOneRow() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-REGIDEM-SAME-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        TenantContext.set(tenantId);

        ClaimsApi.RegisterClaimRequest request = deathRequest(policyNumber, fixture.applicantId(), LocalDate.now().minusDays(1));
        ClaimView first = claimsApi.registerClaim(request, "regidem-same-key-01", "claims-staff");
        ClaimView second = claimsApi.registerClaim(request, "regidem-same-key-01", "claims-staff");

        assertThat(second.claimId()).isEqualTo(first.claimId());
        // Falsifiable: without the dedup fix in ClaimsApiImpl.registerClaim, this repeated call
        // would throw claims/V3's unique-constraint violation as an uncaught 500, or (before V3
        // existed at all) would silently insert a SECOND row -- this asserts there is exactly one.
        assertThat(claimRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber)).hasSize(1);
    }

    /** A DIFFERENT key for what might be the same real-world event is a deliberate, correct
     * design choice -- NOT a gap -- mirroring billing's retry-with-a-new-key idempotency pattern.
     * Asserted explicitly so a future reader does not "fix" this into single-claim-per-policy. */
    @Test
    void registeringTwiceWithDifferentKeysForTheSameEventCreatesTwoDistinctClaims() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-REGIDEM-DIFF-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        TenantContext.set(tenantId);

        ClaimsApi.RegisterClaimRequest request = deathRequest(policyNumber, fixture.applicantId(), LocalDate.now().minusDays(1));
        ClaimView first = claimsApi.registerClaim(request, "regidem-diff-key-A", "claims-staff");
        ClaimView second = claimsApi.registerClaim(request, "regidem-diff-key-B", "claims-staff");

        assertThat(second.claimId()).isNotEqualTo(first.claimId());
        assertThat(claimRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber)).hasSize(2);
    }
}
